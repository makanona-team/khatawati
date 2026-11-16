package com.feras.khatawati;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * قلب التطبيق: يعدّ الخطوات من حساس الهاتف، ويعرف متى تكون في السيارة (خدمة التعرّف على النشاط من غوغل)
 * فيوقف احتساب الخطوات ويقيس مسافة الرحلة بالـGPS في سجل منفصل.
 */
public class StepService extends Service implements SensorEventListener {

    static final String TAG = "Khatawati";
    static final String ACT_TRANSITION = "com.feras.khatawati.TRANSITION";
    static final int NID = 1;

    // حالة حيّة تقرأها الواجهة والويدجت (كلها على الخيط الرئيسي)
    static volatile boolean running;
    static volatile boolean inCar;
    static volatile double tripM;
    static volatile long tripStart;
    static volatile String pDay;
    static volatile long pSteps;
    static volatile double pMeters;
    static volatile int pActive;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences p;
    private SensorManager sm;
    private LocationManager lm;
    private boolean gpsOn;
    private long lastCounter = -1;
    private long curMin = -1;
    private int curMinSteps;
    private boolean curMinCounted;
    private double maxKmh;
    private long lastFastAt;
    private long lastFixAt;
    private Location lastFix;
    private final ArrayList<long[]> carSteps = new ArrayList<>();
    private String lastNotifText = "";

    // ───────── تشغيل الخدمة ─────────

    public static boolean canRun(Context c) {
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasLoc(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static void start(Context c) { start(c, new Intent(c, StepService.class)); }

    public static void start(Context c, Intent i) {
        if (!canRun(c)) return;
        try {
            c.startForegroundService(i);
        } catch (Throwable e) {
            Log.w(TAG, "start: " + e);
        }
    }

    /** خطوات اليوم = المحفوظ + ما لم يُحفظ بعد. */
    public static Db.Day liveToday(Context c) {
        String k = Store.dayKey(System.currentTimeMillis());
        Db.Day d = Db.get(c).day(k);
        if (k.equals(pDay)) {
            d.steps += pSteps;
            d.meters += pMeters;
            d.activeMin += pActive;
        }
        return d;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        p = Store.p(this);
        Notif.channels(this);
        if (!startFg()) { stopSelf(); return; }
        running = true;
        lastCounter = p.getLong("last_counter", -1);
        inCar = p.getBoolean("in_car", false);
        tripStart = p.getLong("trip_start", 0);
        tripM = Double.longBitsToDouble(p.getLong("trip_m", Double.doubleToLongBits(0)));
        maxKmh = p.getFloat("trip_max", 0);
        lastFastAt = p.getLong("trip_fast", System.currentTimeMillis());
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000);
        p.edit().putBoolean("no_counter", s == null).apply();
        registerTransitions();
        if (inCar) startGps();
        h.post(tick);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (!running) { stopSelf(); return START_NOT_STICKY; }
        startFg(); // كل استدعاء startForegroundService يحتاج startForeground، ويضيف نوع «الموقع» إن صار الإذن متاحاً
        if (i != null && ACT_TRANSITION.equals(i.getAction())) {
            onTransition(i.getIntExtra("type", -1), i.getBooleanExtra("enter", false));
        } else {
            registerTransitions();
        }
        updateNotif(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (running) {
            flush();
            persistCar();
        }
        running = false;
        h.removeCallbacks(tick);
        try { if (sm != null) sm.unregisterListener(this); } catch (Throwable ignored) { }
        stopGps();
        super.onDestroy();
    }

    private boolean startFg() {
        Notification n = buildNotif(notifText());
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            if (hasLoc(this)) {
                try {
                    startForeground(NID, n, type | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                    return true;
                } catch (Throwable e) { Log.w(TAG, "fg health|location: " + e); }
            }
            try {
                startForeground(NID, n, type);
                return true;
            } catch (Throwable e) { Log.w(TAG, "fg health: " + e); }
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29 && hasLoc(this)) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NID, n);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "fg: " + e);
            return false;
        }
    }

    // ───────── التعرّف على النشاط (سيارة / مشي) ─────────

    private void registerTransitions() {
        if (!canRun(this)) return;
        try {
            List<ActivityTransition> t = new ArrayList<>();
            int[] types = {DetectedActivity.IN_VEHICLE, DetectedActivity.WALKING, DetectedActivity.RUNNING};
            for (int ty : types) {
                t.add(new ActivityTransition.Builder().setActivityType(ty)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
                t.add(new ActivityTransition.Builder().setActivityType(ty)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build());
            }
            ActivityTransitionRequest req = new ActivityTransitionRequest(t);
            Intent i = new Intent(this, ActivityReceiver.class);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(this, 11, i, fl);
            ActivityRecognition.getClient(this).requestActivityTransitionUpdates(req, pi)
                    .addOnSuccessListener(v -> p.edit().putBoolean("ar_ok", true).apply())
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "AR: " + e);
                        p.edit().putBoolean("ar_ok", false).apply();
                    });
        } catch (Throwable e) {
            Log.w(TAG, "AR register: " + e);
            p.edit().putBoolean("ar_ok", false).apply();
        }
    }

    private void onTransition(int type, boolean enter) {
        long now = System.currentTimeMillis();
        p.edit().putInt("act_type", type).putBoolean("act_enter", enter).putLong("act_at", now).apply();
        if (type == DetectedActivity.IN_VEHICLE) {
            if (enter) enterCar(now);
            else exitCar(now);
        } else if ((type == DetectedActivity.WALKING || type == DetectedActivity.RUNNING) && enter && inCar) {
            if (now - lastFastAt > 60_000) exitCar(now);
        }
    }

    // ───────── الخطوات ─────────

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
        long v = (long) e.values[0];
        long now = System.currentTimeMillis();
        long wall = now - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000L;
        if (wall > now || now - wall > 3_600_000L) wall = now;
        if (lastCounter < 0) {
            lastCounter = v;
            saveCounter();
            return;
        }
        long d = v - lastCounter;
        if (d < 0) d = v; // إعادة تشغيل الهاتف تصفّر العدّاد
        lastCounter = v;
        saveCounter();
        if (d <= 0 || d > 50_000) return;
        if (inCar) {
            // خطوات أثناء السيارة: تُحجز ولا تُحسب. إن كانت كثيرة وسريعة والسيارة متوقفة فقد نزلتَ تمشي.
            carSteps.add(new long[]{wall, d});
            long recent = 0;
            for (long[] s : carSteps) if (s[0] >= now - 120_000) recent += s[1];
            boolean slow = lastFix == null || now - lastFixAt > 60_000 || (lastFix.hasSpeed() && lastFix.getSpeed() < 2.2f);
            if (recent >= 100 && slow) exitCar(now);
            return;
        }
        credit(wall, d);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void saveCounter() { p.edit().putLong("last_counter", lastCounter).apply(); }

    private void credit(long wall, long d) {
        String day = Store.dayKey(wall);
        if (pDay != null && !day.equals(pDay)) flush();
        pDay = day;
        pSteps += d;
        pMeters += d * Store.strideM(this);
        long m = wall / 60_000L;
        if (m != curMin) {
            curMin = m;
            curMinSteps = 0;
            curMinCounted = false;
        }
        curMinSteps += d;
        if (!curMinCounted && curMinSteps >= 10) {
            curMinCounted = true;
            pActive++;
        }
    }

    private void flush() {
        String d = pDay;
        if (d != null && (pSteps > 0 || pActive > 0)) {
            try { Db.get(this).addWalk(d, pSteps, pMeters, pActive); } catch (Throwable e) { Log.w(TAG, "flush: " + e); return; }
        }
        pSteps = 0;
        pMeters = 0;
        pActive = 0;
    }

    // ───────── السيارة ─────────

    private void enterCar(long now) {
        if (inCar) return;
        flush();
        inCar = true;
        tripStart = now;
        tripM = 0;
        maxKmh = 0;
        lastFastAt = now;
        lastFix = null;
        carSteps.clear();
        persistCar();
        startGps();
        updateNotif(true);
        StepsWidget.update(this);
    }

    private void exitCar(long now) {
        if (!inCar) return;
        stopGps();
        inCar = false;
        // الخطوات بعد آخر حركة للسيارة هي مشي حقيقي بعد النزول
        for (long[] s : carSteps) if (s[0] >= lastFastAt) credit(s[0], s[1]);
        carSteps.clear();
        if (tripM >= 300) {
            long end = Math.max(tripStart, Math.min(now, lastFastAt + 60_000));
            try {
                Db.get(this).addTrip(tripStart, end, tripM, maxKmh);
                Db.get(this).addCar(Store.dayKey(tripStart), tripM);
            } catch (Throwable e) { Log.w(TAG, "trip: " + e); }
        }
        tripM = 0;
        lastFix = null;
        persistCar();
        updateNotif(true);
        StepsWidget.update(this);
    }

    private void persistCar() {
        if (p == null) return;
        p.edit().putBoolean("in_car", inCar).putLong("trip_start", tripStart)
                .putLong("trip_m", Double.doubleToLongBits(tripM)).putFloat("trip_max", (float) maxKmh)
                .putLong("trip_fast", lastFastAt).apply();
    }

    private final LocationListener gps = new LocationListener() {
        @Override public void onLocationChanged(Location l) { onFix(l); }
        @Override public void onStatusChanged(String s, int i, Bundle b) { }
        @Override public void onProviderEnabled(String s) { }
        @Override public void onProviderDisabled(String s) { }
    };

    private void startGps() {
        if (gpsOn || lm == null || !hasLoc(this) || !p.getBoolean("car_log", true)) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, gps, Looper.getMainLooper());
            gpsOn = true;
        } catch (Throwable e) {
            Log.w(TAG, "gps: " + e);
        }
    }

    private void stopGps() {
        if (!gpsOn || lm == null) return;
        try { lm.removeUpdates(gps); } catch (Throwable ignored) { }
        gpsOn = false;
    }

    private void onFix(Location l) {
        try {
            if (Prayer.saveFix(this, l.getLatitude(), l.getLongitude())) PrayerSched.schedule(this);
        } catch (Throwable ignored) { }
        if (!inCar) return;
        if (l.hasAccuracy() && l.getAccuracy() > 30) return;
        long now = System.currentTimeMillis();
        float speed = l.hasSpeed() ? l.getSpeed() : -1;
        if (lastFix != null) {
            double d = lastFix.distanceTo(l);
            double dt = (l.getElapsedRealtimeNanos() - lastFix.getElapsedRealtimeNanos()) / 1e9;
            if (dt <= 0) return;
            double v = d / dt;
            if (v > 70) return; // قفزة GPS غير معقولة
            if (speed < 0) speed = (float) v;
            if (speed >= 1.0f || d >= 15) tripM += d;
        }
        if (speed >= 0 && speed * 3.6 > maxKmh) maxKmh = speed * 3.6;
        if (speed >= 2.5f) lastFastAt = now;
        lastFix = l;
        lastFixAt = now;
    }

    // ───────── دورة كل 30 ثانية ─────────

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            flush();
            if (inCar && now - lastFastAt > 15 * 60_000L) exitCar(now); // متوقفة ربع ساعة = انتهت الرحلة
            if (inCar) persistCar();
            updateNotif(false);
            StepsWidget.update(StepService.this);
            Updater.autoCheck(StepService.this);
            h.postDelayed(this, 30_000);
        }
    };

    // ───────── الإشعار الدائم ─────────

    private String notifText() {
        if (inCar) return "الرحلة الحالية: " + Store.km(tripM) + " كم · الخطوات لا تُحسب";
        Db.Day d;
        try { d = liveToday(this); } catch (Throwable e) { d = new Db.Day(); }
        String s = Store.fmtInt(d.steps) + " خطوة · " + Store.km(d.meters) + " كم";
        try {
            long[] nx = Prayer.next(this, System.currentTimeMillis());
            if (nx != null) s += " · " + Prayer.name((int) nx[0]) + " " + Store.clock(nx[1]);
        } catch (Throwable ignored) { }
        return s;
    }

    private Notification buildNotif(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, Notif.CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(inCar ? "🚗 في السيارة" : "🚶 خطواتي")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotif(boolean force) {
        String t = notifText();
        if (!force && t.equals(lastNotifText)) return;
        lastNotifText = t;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NID, buildNotif(t));
        } catch (Throwable ignored) { }
    }
}
