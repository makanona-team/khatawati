package com.feras.khatawati;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;

/** يستقبل من غوغل: دخلت السيارة / نزلت منها / بدأت المشي، ويمرّرها لخدمة العدّ. */
public class ActivityReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!ActivityTransitionResult.hasResult(i)) return;
        ActivityTransitionResult r = ActivityTransitionResult.extractResult(i);
        if (r == null) return;
        for (ActivityTransitionEvent e : r.getTransitionEvents()) {
            Intent s = new Intent(c, StepService.class)
                    .setAction(StepService.ACT_TRANSITION)
                    .putExtra("type", e.getActivityType())
                    .putExtra("enter", e.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER);
            StepService.start(c, s);
        }
    }
}
