package com.feras.khatawati;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;

/** ويدجت الشاشة الرئيسية: خطوات اليوم والمسافة والصلاة القادمة مع عدّ تنازلي. */
public class StepsWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { update(c); }

    public static void update(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, StepsWidget.class));
            if (ids == null || ids.length == 0) return;
            Db.Day d = StepService.liveToday(c);
            int goal = Store.goal(c);
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.w_steps, Store.fmtInt(d.steps));
            v.setTextViewText(R.id.w_km, Store.km(d.meters) + " كم");
            v.setProgressBar(R.id.w_prog, 100, (int) Math.min(100, d.steps * 100 / Math.max(1, goal)), false);
            long now = System.currentTimeMillis();
            long[] nx = Prayer.next(c, now);
            if (nx != null) {
                v.setTextViewText(R.id.w_prayer, "🕌 " + Prayer.name((int) nx[0]) + " " + Store.clock(nx[1]));
                v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() + (nx[1] - now), null, true);
                v.setChronometerCountDown(R.id.w_chrono, true);
            }
            v.setTextViewText(R.id.w_state, StepService.inCar ? "🚗 في السيارة · الرحلة " + Store.km(StepService.tripM) + " كم" : "");
            PendingIntent pi = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            v.setOnClickPendingIntent(R.id.w_root, pi);
            m.updateAppWidget(ids, v);
        } catch (Throwable ignored) { }
    }
}
