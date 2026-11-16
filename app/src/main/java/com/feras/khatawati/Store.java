package com.feras.khatawati;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** الإعدادات والتنسيق المشترك. */
public final class Store {

    public static final String PREFS = "khatawati";

    static final String[] WEEKDAYS = {"الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
    static final String[] MONTHS = {"كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
            "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"};

    private Store() { }

    public static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    public static String dayKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    public static String dayKey(Calendar c) { return dayKey(c.getTimeInMillis()); }

    public static String monthKey(Calendar c) {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(c.getTime());
    }

    public static int goal(Context c) { return p(c).getInt("goal", 8000); }

    public static int heightCm(Context c) { return p(c).getInt("height_cm", 170); }

    /** طول الخطوة بالمتر: يدوي إن وُجد، وإلا 41.5% من الطول. */
    public static double strideM(Context c) {
        int s = p(c).getInt("stride_cm", 0);
        if (s > 0) return s / 100.0;
        return heightCm(c) * 0.415 / 100.0;
    }

    public static String fmtInt(long n) { return String.format(Locale.US, "%,d", n); }

    public static String km(double meters) {
        return String.format(Locale.US, meters >= 100000 ? "%.0f" : meters >= 10000 ? "%.1f" : "%.2f", meters / 1000.0);
    }

    public static String dayLabel(Calendar c) {
        return WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
    }

    public static String dayLabel(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return dayLabel(c);
    }

    /** «3:12 م». */
    public static String clock(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return Prayer.hhmm(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), false);
    }

    /** «1:05:09» أو «05:09». */
    public static String hms(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, ss);
        return String.format(Locale.US, "%02d:%02d", m, ss);
    }

    /** {steps بالآلاف مختصرة} «5.4k». */
    public static String shortNum(long n) {
        if (n >= 10000) return String.format(Locale.US, "%.0fk", n / 1000.0);
        if (n >= 1000) return String.format(Locale.US, "%.1fk", n / 1000.0);
        return String.valueOf(n);
    }
}
