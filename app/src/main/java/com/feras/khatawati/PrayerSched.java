package com.feras.khatawati;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/** يضبط منبّهاً واحداً على أقرب حدث: «قبل الصلاة بربع ساعة» أو «الأذان». */
public final class PrayerSched {

    static final int[] PRAYERS = {Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA};
    static final int KIND_PRE = 0, KIND_ADHAN = 1;

    private PrayerSched() { }

    public static void schedule(Context c) {
        try {
            SharedPreferences p = Store.p(c);
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am == null) return;
            Intent it = new Intent(c, PrayerReceiver.class).setAction("com.feras.khatawati.PRAYER");
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            am.cancel(PendingIntent.getBroadcast(c, 21, it, fl));
            boolean pre = p.getBoolean("pre_on", true);
            boolean adhan = p.getBoolean("adhan_on", true);
            if (!pre && !adhan) return;
            long preMs = p.getInt("pre_min", 15) * 60_000L;
            long now = System.currentTimeMillis();
            long best = Long.MAX_VALUE, bestAt = 0;
            int bp = -1, bk = 0;
            Calendar cal = Calendar.getInstance();
            for (int off = 0; off < 3; off++) {
                Calendar d = (Calendar) cal.clone();
                d.add(Calendar.DAY_OF_MONTH, off);
                int[] t = Prayer.times(c, d);
                for (int i : PRAYERS) {
                    if (t[i] < 0) continue;
                    long at = Prayer.at(d, t[i]);
                    if (adhan && at > now + 1000 && at < best) { best = at; bestAt = at; bp = i; bk = KIND_ADHAN; }
                    long pa = at - preMs;
                    if (pre && pa > now + 1000 && pa < best) { best = pa; bestAt = at; bp = i; bk = KIND_PRE; }
                }
                if (bp >= 0) break;
            }
            if (bp < 0) return;
            it.putExtra("prayer", bp).putExtra("kind", bk).putExtra("at", bestAt).putExtra("fire", best);
            PendingIntent pi = PendingIntent.getBroadcast(c, 21, it, fl);
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
            }
        } catch (Throwable e) {
            Log.w(StepService.TAG, "schedule: " + e);
        }
    }
}
