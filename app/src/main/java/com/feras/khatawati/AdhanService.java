package com.feras.khatawati;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** يشغّل صوت الأذان (ملفك المختار أو نغمة المنبّه الافتراضية) مع زر إيقاف في الإشعار. */
public class AdhanService extends Service {

    static final String STOP = "com.feras.khatawati.STOP_ADHAN";
    static final int NID = 2;

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private AudioManager am;
    private AudioFocusRequest afr;
    private String title = "";
    private int prayer;

    public static void start(Context c, int prayer, boolean test) {
        Intent i = new Intent(c, AdhanService.class).putExtra("prayer", prayer).putExtra("test", test);
        try {
            c.startForegroundService(i);
        } catch (Throwable e) {
            Log.w(StepService.TAG, "adhan start: " + e);
            fallback(c, prayer);
        }
    }

    /** إن منع النظام تشغيل الخدمة: إشعار بصوت التنبيه العادي. */
    static void fallback(Context c, int prayer) {
        if (!Notif.canPost(c)) return;
        Notification n = new Notification.Builder(c, Notif.CH_PRE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("🕌 حان الآن موعد أذان " + Prayer.name(prayer))
                .setAutoCancel(true)
                .setContentIntent(PrayerReceiver.openApp(c))
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NID, n);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification notif(boolean playing) {
        Notification.Builder b = new Notification.Builder(this, Notif.CH_ADHAN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(Store.clock(System.currentTimeMillis()) + " · " + Prayer.place(this).name)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(PrayerReceiver.openApp(this))
                .setAutoCancel(!playing)
                .setOngoing(playing);
        if (playing) {
            PendingIntent stop = PendingIntent.getService(this, 9, new Intent(this, AdhanService.class).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "⏹ إيقاف الأذان", stop).build());
            b.setDeleteIntent(stop);
        }
        return b.build();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        Notif.channels(this);
        if (i != null && STOP.equals(i.getAction())) {
            if (title.isEmpty()) title = "الأذان";
            fgStart();
            stopAll(true);
            return START_NOT_STICKY;
        }
        prayer = i == null ? Prayer.DHUHR : i.getIntExtra("prayer", Prayer.DHUHR);
        boolean test = i != null && i.getBooleanExtra("test", false);
        title = (test ? "تجربة: " : "🕌 ") + "حان الآن موعد أذان " + Prayer.name(prayer);
        if (!fgStart()) {
            fallback(this, prayer);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!test && !Store.p(this).getBoolean("adhan_sound", true)) {
            stopAll(false);
            return START_NOT_STICKY;
        }
        String u = Store.p(this).getString("adhan_uri", "");
        play(u.isEmpty() ? null : Uri.parse(u));
        return START_NOT_STICKY;
    }

    private boolean fgStart() {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, notif(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NID, notif(true));
            return true;
        } catch (Throwable e) {
            Log.w(StepService.TAG, "adhan fg: " + e);
            return false;
        }
    }

    private void play(Uri custom) {
        stopPlayer();
        Uri uri = custom;
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            mp = new MediaPlayer();
            mp.setAudioAttributes(aa);
            mp.setDataSource(this, uri);
            mp.setOnCompletionListener(x -> stopAll(false));
            mp.setOnErrorListener((x, a, b) -> { stopAll(false); return true; });
            mp.prepare();
            am = getSystemService(AudioManager.class);
            afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(aa).build();
            if (am != null) am.requestAudioFocus(afr);
            mp.start();
            // النغمة الافتراضية قد تتكرر بلا نهاية: تتوقف بعد دقيقة. ملف الأذان يُترك حتى ينتهي (بحد أقصى 8 دقائق).
            h.postDelayed(() -> stopAll(false), custom != null ? 8 * 60_000L : 60_000L);
        } catch (Throwable e) {
            Log.w(StepService.TAG, "adhan play: " + e);
            if (custom != null) play(null);
            else stopAll(false);
        }
    }

    private void stopPlayer() {
        h.removeCallbacksAndMessages(null);
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) { }
            try { mp.release(); } catch (Throwable ignored) { }
            mp = null;
        }
        if (am != null && afr != null) {
            try { am.abandonAudioFocusRequest(afr); } catch (Throwable ignored) { }
        }
    }

    /** removeNotif=true عند الضغط على «إيقاف»؛ وإلا يبقى إشعار عادي بموعد الأذان. */
    private void stopAll(boolean removeNotif) {
        stopPlayer();
        if (removeNotif) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(STOP_FOREGROUND_DETACH);
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && Notif.canPost(this)) nm.notify(NID, notif(false));
            } catch (Throwable ignored) { }
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopPlayer();
        super.onDestroy();
    }
}
