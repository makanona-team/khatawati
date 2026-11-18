package com.feras.khatawati;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * يشغّل صوت الأذان. الإيقاف بأي طريقة: زر الإيقاف أو النقر على الإشعار أو سحبه، شاشة الأذان، أو أزرار الصوت.
 */
public class AdhanService extends Service {

    static final String STOP = "com.feras.khatawati.STOP_ADHAN";
    static final int NID = 2;

    /** تقرأه شاشة الأذان لتُغلق نفسها عند انتهاء الصوت. */
    static volatile boolean playing;
    static volatile String nowTitle = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private AudioManager am;
    private AudioFocusRequest afr;
    private MediaSession session;
    private BroadcastReceiver volRx;
    private long startedAt;
    private String title = "";

    public static void start(Context c, int prayer, boolean test) { start(c, prayer, test, null); }

    /** sound: معرّف صوت من المكتبة لتجربته، أو null لصوت الصلاة المختار. */
    public static void start(Context c, int prayer, boolean test, String sound) {
        Intent i = new Intent(c, AdhanService.class).putExtra("prayer", prayer).putExtra("test", test);
        if (sound != null) i.putExtra("sound", sound);
        try {
            c.startForegroundService(i);
        } catch (Throwable e) {
            Log.w(StepService.TAG, "adhan start: " + e);
            fallback(c, prayer);
        }
    }

    public static void stop(Context c) {
        try {
            c.startService(new Intent(c, AdhanService.class).setAction(STOP));
        } catch (Throwable e) {
            Log.w(StepService.TAG, "adhan stop: " + e);
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

    private PendingIntent stopIntent() {
        return PendingIntent.getService(this, 9, new Intent(this, AdhanService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notif(boolean active) {
        Notification.Builder b = new Notification.Builder(this, Notif.CH_ADHAN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(active ? "اضغط هنا لإيقاف الصوت" : Store.clock(System.currentTimeMillis()) + " · " + Prayer.place(this).name)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(!active)
                .setOngoing(false);
        if (active) {
            PendingIntent stop = stopIntent();
            b.setContentIntent(stop);   // النقر على الإشعار يوقف الأذان
            b.setDeleteIntent(stop);    // سحب الإشعار جانباً يوقفه
            b.addAction(new Notification.Action.Builder(null, "⏹ إيقاف الأذان", stop).build());
            PendingIntent full = PendingIntent.getActivity(this, 10,
                    new Intent(this, AdhanActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setFullScreenIntent(full, true); // شاشة الأذان فوق شاشة القفل
        } else {
            b.setContentIntent(PrayerReceiver.openApp(this));
        }
        return b.build();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        Notif.channels(this);
        if (i != null && STOP.equals(i.getAction())) {
            if (!playing) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            stopAll(true);
            return START_NOT_STICKY;
        }
        int prayer = i == null ? Prayer.DHUHR : i.getIntExtra("prayer", Prayer.DHUHR);
        boolean test = i != null && i.getBooleanExtra("test", false);
        String sound = i == null ? null : i.getStringExtra("sound");
        title = (test ? "تجربة: " : "🕌 ") + "حان الآن موعد أذان " + Prayer.name(prayer);
        nowTitle = title;
        boolean withSound = test || Store.p(this).getBoolean("adhan_sound", true);
        playing = withSound;
        if (!fgStart(withSound)) {
            playing = false;
            fallback(this, prayer);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!withSound) {
            stopAll(false);
            return START_NOT_STICKY;
        }
        String sid = sound != null ? sound : AdhanLib.selectedId(this, prayer);
        Uri custom = AdhanLib.uri(this, sid);
        if (custom == null && sound == null) {
            String old = Store.p(this).getString("adhan_uri", ""); // اختيار الإصدار 1.0 قبل نقله للمكتبة
            if (!old.isEmpty()) custom = Uri.parse(old);
        }
        play(custom);
        return START_NOT_STICKY;
    }

    private boolean fgStart(boolean active) {
        try {
            Notification n = notif(active);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NID, n);
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
            mp.setLooping(false);
            mp.setOnCompletionListener(x -> stopAll(false));
            mp.setOnErrorListener((x, a, b) -> { stopAll(false); return true; });
            mp.prepare();
            am = getSystemService(AudioManager.class);
            afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(aa).build();
            if (am != null) am.requestAudioFocus(afr);
            mp.start();
            startedAt = SystemClock.elapsedRealtime();
            listenVolumeKeys();
            // النغمة الافتراضية قد تتكرر بلا نهاية: تتوقف بعد دقيقة. ملف الأذان يُترك حتى ينتهي (بحد أقصى 8 دقائق).
            h.postDelayed(() -> stopAll(false), custom != null ? 8 * 60_000L : 60_000L);
        } catch (Throwable e) {
            Log.w(StepService.TAG, "adhan play: " + e);
            if (custom != null) play(null);
            else stopAll(false);
        }
    }

    /** أزرار رفع/خفض الصوت توقف الأذان (حتى والشاشة مطفأة). */
    private void listenVolumeKeys() {
        try {
            session = new MediaSession(this, "khatawati-adhan");
            session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                @Override
                public void onAdjustVolume(int direction) { h.post(() -> stopAll(true)); }
            });
            session.setCallback(new MediaSession.Callback() {
                @Override public void onStop() { stopAll(true); }
                @Override public void onPause() { stopAll(true); }
                @Override public boolean onMediaButtonEvent(Intent i) { stopAll(true); return true; }
            });
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackState.ACTION_STOP | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                    .build());
            session.setActive(true);
        } catch (Throwable e) {
            Log.w(StepService.TAG, "session: " + e);
        }
        // احتياط: أي تغيير في مستوى الصوت أثناء الأذان يوقفه
        try {
            volRx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (SystemClock.elapsedRealtime() - startedAt > 1500) stopAll(true);
                }
            };
            IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(volRx, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(volRx, f);
        } catch (Throwable e) {
            volRx = null;
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
        if (session != null) {
            try { session.setActive(false); session.release(); } catch (Throwable ignored) { }
            session = null;
        }
        if (volRx != null) {
            try { unregisterReceiver(volRx); } catch (Throwable ignored) { }
            volRx = null;
        }
    }

    /** removeNotif=true عند الإيقاف يدوياً؛ وإلا يبقى إشعار عادي بموعد الأذان. */
    private void stopAll(boolean removeNotif) {
        stopPlayer();
        playing = false;
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
        playing = false;
        super.onDestroy();
    }
}
