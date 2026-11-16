package com.feras.khatawati;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** بعد تشغيل الهاتف أو تحديث التطبيق أو تغيير الوقت: يعيد تشغيل العدّاد وضبط منبّهات الصلاة. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        StepService.start(c);
        PrayerSched.schedule(c);
        StepsWidget.update(c);
    }
}
