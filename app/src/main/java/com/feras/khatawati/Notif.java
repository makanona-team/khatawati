package com.feras.khatawati;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Arrays;

/** قنوات الإشعارات. */
final class Notif {

    static final String CH_SERVICE = "svc";
    static final String CH_PRE = "pre";
    static final String CH_ADHAN = "adhan";
    static final String CH_UPD = "upd";

    private Notif() { }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "عدّاد الخطوات", NotificationManager.IMPORTANCE_LOW);
        s.setShowBadge(false);
        s.setDescription("إشعار دائم يُبقي العدّاد يعمل في الخلفية");
        NotificationChannel pre = new NotificationChannel(CH_PRE, "تنبيه قبل الصلاة", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel ad = new NotificationChannel(CH_ADHAN, "الأذان", NotificationManager.IMPORTANCE_HIGH);
        ad.setSound(null, null); // الصوت يشغّله التطبيق نفسه
        NotificationChannel up = new NotificationChannel(CH_UPD, "التحديثات", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannels(Arrays.asList(s, pre, ad, up));
    }

    static boolean canPost(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
}
