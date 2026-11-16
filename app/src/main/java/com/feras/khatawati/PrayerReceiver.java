package com.feras.khatawati;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** منبّه الصلاة: إشعار «قبل الصلاة» أو تشغيل الأذان، ثم ضبط المنبّه التالي. */
public class PrayerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Notif.channels(c);
        int pr = i.getIntExtra("prayer", -1);
        int kind = i.getIntExtra("kind", PrayerSched.KIND_ADHAN);
        long at = i.getLongExtra("at", 0);
        long fire = i.getLongExtra("fire", at);
        long now = System.currentTimeMillis();
        boolean fresh = pr >= 0 && now - fire < 10 * 60_000L; // منبّه قديم (مثلاً بعد إطفاء الهاتف) لا يُنفَّذ
        if (fresh) {
            if (kind == PrayerSched.KIND_PRE) pre(c, pr, at, now);
            else AdhanService.start(c, pr, false);
        }
        PrayerSched.schedule(c);
        StepsWidget.update(c);
    }

    static PendingIntent openApp(Context c) {
        Intent o = new Intent(c, MainActivity.class).putExtra("tab", 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 5, o, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void pre(Context c, int pr, long at, long now) {
        if (!Notif.canPost(c)) return;
        long mins = Math.max(1, Math.round((at - now) / 60000.0));
        Notification n = new Notification.Builder(c, Notif.CH_PRE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("🕌 اقترب موعد صلاة " + Prayer.name(pr))
                .setContentText("الأذان بعد " + mins + " دقيقة · " + Store.clock(at))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openApp(c))
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(100 + pr, n);
    }
}
