package com.feras.khatawati;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements SensorEventListener {

    static final int BG = 0xFF0F1720, CARD = 0xFF1A2430, ACC = 0xFF2ECC71, GOLD = 0xFFF1C40F,
            TXT = 0xFFECF0F1, SUB = 0xFF95A5A6, CAR = 0xFF5DADE2, BAR = 0xFF111A24;
    static final int RC_PERM = 1, RC_ADHAN = 2, RC_EXPORT = 3, RC_IMPORT = 4;
    static final String[] TABS = {"🚶\nاليوم", "📊\nالسجل", "🕌\nالصلاة", "⚙️\nالإعدادات"};

    final Handler h = new Handler(Looper.getMainLooper());
    final HashSet<String> askedThisRun = new HashSet<>();
    SharedPreferences p;
    FrameLayout content;
    LinearLayout tabBar;
    ScrollView scroll;
    int tab = 0, histMode = 0, tickN = 0;
    SensorManager sm;
    float azSmooth = Float.NaN;

    // عناصر تتحدّث حيّاً
    Views.Ring ring;
    Views.Bars bars;
    Views.Qibla qibla;
    TextView tvKm, tvMin, tvPct, tvNext, tvCount, tvCity, tvCar, tvStatus, tvPrCount;

    interface IntCb { void on(int v); }

    // ───────────────────────── دورة الحياة ─────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Store.p(this);
        Notif.channels(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BAR);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        tabBar = new LinearLayout(this);
        tabBar.setBackgroundColor(BAR);
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            TextView t = tv(TABS[i], 13, SUB, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, dp(8));
            t.setOnClickListener(v -> show(k));
            tabBar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(tabBar);
        setContentView(root);
        sm = getSystemService(SensorManager.class);
        if (b != null) tab = b.getInt("tab", 0);
        handleIntent(getIntent());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
        show(tab);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.hasExtra("tab")) {
            tab = i.getIntExtra("tab", 0);
            i.removeExtra("tab");
        }
        if (i.getBooleanExtra("update", false)) {
            i.removeExtra("update");
            h.postDelayed(() -> checkUpdate(false), 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StepService.start(this);
        PrayerSched.schedule(this);
        refreshLocation();
        show(tab);
        h.removeCallbacks(ticker);
        h.post(ticker);
        Updater.autoCheck(this);
        maybeWhatsNew();
        AdhanLib.migrate(this);
        StepsWidget.update(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(ticker);
        if (sm != null) sm.unregisterListener(this);
    }

    final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateCountdown();
            if (++tickN % 5 == 0 && tab == 0 && ring != null) refreshToday();
            h.postDelayed(this, 1000);
        }
    };

    // ───────────────────────── أدوات الواجهة ─────────────────────────

    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    TextView tv(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }

    LinearLayout vcol() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, dp(6), 0, dp(16));
        return l;
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        l.setBackground(g);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(12), dp(6), dp(12), dp(6));
        l.setLayoutParams(lp);
        return l;
    }

    View header(String title, String sub) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(14), dp(18), dp(6));
        l.addView(tv(title, 24, TXT, true));
        if (sub != null && !sub.isEmpty()) l.addView(tv(sub, 14, SUB, false));
        return l;
    }

    TextView section(String s) {
        TextView t = tv(s, 14, ACC, true);
        t.setPadding(dp(20), dp(16), dp(20), dp(4));
        return t;
    }

    TextView button(String s, View.OnClickListener l) {
        TextView t = tv(s, 16, 0xFF0F1720, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACC);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    void ripple(View v) {
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        v.setForeground(getDrawable(out.resourceId));
    }

    /** صف إعداد: عنوان يمين وقيمة يسار. */
    View item(LinearLayout parent, String title, String value, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        TextView a = tv(title, 16, TXT, false);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null && !value.isEmpty()) {
            TextView b = tv(value, 14, SUB, false);
            b.setGravity(Gravity.END);
            b.setMaxWidth(dp(190));
            r.addView(b);
        }
        if (l != null) {
            r.setOnClickListener(l);
            ripple(r);
        }
        parent.addView(r);
        return r;
    }

    void sw(LinearLayout parent, String title, String sub, String key, boolean def, Runnable after) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(tv(title, 16, TXT, false));
        if (sub != null && !sub.isEmpty()) tx.addView(tv(sub, 12, SUB, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this);
        s.setChecked(p.getBoolean(key, def));
        s.setOnCheckedChangeListener((v, on) -> {
            p.edit().putBoolean(key, on).apply();
            if (after != null) after.run();
        });
        r.addView(s);
        r.setOnClickListener(v -> s.toggle());
        parent.addView(r);
    }

    TextView hint(String s) {
        TextView t = tv(s, 12, SUB, false);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    AlertDialog.Builder dlg() { return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert); }

    void alert(String title, String msg) {
        if (isFinishing()) return;
        dlg().setTitle(title).setMessage(msg).setPositiveButton("حسناً", null).show();
    }

    void askNumber(String title, int cur, int min, int max, IntCb cb) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (min < 0 ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        e.setText(String.valueOf(cur));
        e.setSelectAllOnFocus(true);
        e.setTextColor(TXT);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(24), dp(8), dp(24), 0);
        f.addView(e);
        dlg().setTitle(title).setView(f).setPositiveButton("حفظ", (d, w) -> {
            try {
                int v = Integer.parseInt(e.getText().toString().trim());
                if (v < min || v > max) { toast("القيمة يجب أن تكون بين " + min + " و " + max); return; }
                cb.on(v);
            } catch (Exception x) {
                toast("رقم غير صالح");
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    void choose(String title, String[] items, IntCb cb) {
        dlg().setTitle(title).setItems(items, (d, w) -> cb.on(w)).setNegativeButton("إلغاء", null).show();
    }

    boolean granted(String perm) { return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    // ───────────────────────── التبويبات ─────────────────────────

    void show(int k) {
        int keepY = (k == tab && scroll != null) ? scroll.getScrollY() : 0;
        tab = k;
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            t.setTextColor(i == k ? ACC : SUB);
            t.setTypeface(i == k ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (sm != null) sm.unregisterListener(this);
        ring = null; bars = null; qibla = null;
        tvKm = tvMin = tvPct = tvNext = tvCount = tvCity = tvCar = tvStatus = tvPrCount = null;
        content.removeAllViews();
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = vcol();
        scroll.addView(col);
        try {
            if (k == 0) buildToday(col);
            else if (k == 1) buildHistory(col);
            else if (k == 2) buildPrayer(col);
            else buildSettings(col);
        } catch (Throwable e) {
            col.addView(tv("حدث خطأ في العرض: " + e, 14, 0xFFE74C3C, false));
        }
        content.addView(scroll);
        if (keepY > 0) {
            final int y = keepY;
            scroll.post(() -> scroll.scrollTo(0, y));
        }
        updateCountdown();
    }

    // ───────────────────────── اليوم ─────────────────────────

    String missingText() {
        ArrayList<String> m = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) m.add("• إذن «النشاط البدني» (لعدّ الخطوات)");
        if (!Notif.canPost(this)) m.add("• إذن الإشعارات (للأذان والتنبيهات)");
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) m.add("• إذن الموقع (للمدينة ومسافة السيارة)");
        else if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) m.add("• الموقع «طوال الوقت» (للسيارة والتطبيق مغلق)");
        if (!ignoringBattery()) m.add("• استثناء التطبيق من توفير البطارية");
        if (!canFullScreen()) m.add("• السماح بشاشة الأذان فوق شاشة القفل");
        return String.join("\n", m);
    }

    void buildToday(LinearLayout col) {
        Calendar now = Calendar.getInstance();
        col.addView(header("خطواتي", Store.dayLabel(now) + " · " + Prayer.hijriText(this, now)));
        String miss = missingText();
        if (!miss.isEmpty()) {
            LinearLayout c = card();
            c.addView(tv("⚠️ أكمل الإعداد حتى يعمل العدّاد بدقة", 16, GOLD, true));
            c.addView(tv(miss, 14, TXT, false));
            c.addView(button("أكمل الإعداد", v -> { askedThisRun.clear(); runSetup(); }));
            col.addView(c);
        }
        ring = new Views.Ring(this);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, dp(230));
        rl.setMargins(0, dp(8), 0, dp(4));
        col.addView(ring, rl);

        LinearLayout stats = new LinearLayout(this);
        stats.setPadding(dp(8), 0, dp(8), 0);
        tvKm = stat(stats, "كم مشياً");
        tvMin = stat(stats, "دقيقة مشي");
        tvPct = stat(stats, "من الهدف");
        col.addView(stats);

        tvStatus = tv("", 13, SUB, false);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(dp(16), dp(6), dp(16), dp(2));
        col.addView(tvStatus);

        LinearLayout pc = card();
        tvNext = tv("", 17, GOLD, true);
        tvCount = tv("", 28, TXT, true);
        tvCity = tv("", 13, SUB, false);
        pc.addView(tvNext);
        pc.addView(tvCount);
        pc.addView(tvCity);
        pc.setOnClickListener(v -> show(2));
        col.addView(pc);

        LinearLayout cc = card();
        cc.addView(tv("🚗 بالسيارة (سجل منفصل لا يدخل في المشي)", 15, CAR, true));
        tvCar = tv("", 14, TXT, false);
        cc.addView(tvCar);
        cc.setOnClickListener(v -> { histMode = 3; show(1); });
        col.addView(cc);

        LinearLayout bc = card();
        bc.addView(tv("آخر 7 أيام", 15, TXT, true));
        bars = new Views.Bars(this);
        bc.addView(bars, new LinearLayout.LayoutParams(-1, dp(170)));
        bc.setOnClickListener(v -> { histMode = 0; show(1); });
        col.addView(bc);
        refreshToday();
    }

    TextView stat(LinearLayout row, String label) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        TextView v = tv("0", 22, TXT, true);
        v.setGravity(Gravity.CENTER);
        TextView l = tv(label, 12, SUB, false);
        l.setGravity(Gravity.CENTER);
        b.addView(v);
        b.addView(l);
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        return v;
    }

    void refreshToday() {
        if (ring == null) return;
        Db.Day d = StepService.liveToday(this);
        int goal = Store.goal(this);
        ring.set(d.steps, goal);
        tvKm.setText(Store.km(d.meters));
        tvMin.setText(String.valueOf(d.activeMin));
        tvPct.setText(Math.min(999, d.steps * 100 / Math.max(1, goal)) + "%");
        String st;
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) st = "⚠️ العدّاد متوقف: يحتاج إذن «النشاط البدني»";
        else if (p.getBoolean("no_counter", false)) st = "⚠️ هذا الهاتف لا يحوي حساس خطوات";
        else if (!StepService.running) st = "⚠️ العدّاد متوقف — افتح «أكمل الإعداد» أو أعد فتح التطبيق";
        else if (StepService.inCar) st = "🚗 أنت في السيارة الآن — الخطوات لا تُحسب · الرحلة " + Store.km(StepService.tripM) + " كم";
        else st = "🟢 العدّاد يعمل في الخلفية";
        tvStatus.setText(st);

        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        Calendar w = Calendar.getInstance();
        w.add(Calendar.DAY_OF_MONTH, -6);
        String from = Store.dayKey(c.before(w) ? c : w);
        HashMap<String, Db.Day> map = Db.get(this).since(from);
        String month = Store.monthKey(Calendar.getInstance());
        double monthCar = 0;
        for (Db.Day x : map.values()) if (x.day.startsWith(month)) monthCar += x.carM;
        double liveTrip = StepService.inCar ? StepService.tripM : 0;
        tvCar.setText("اليوم: " + Store.km(d.carM + liveTrip) + " كم  ·  هذا الشهر: " + Store.km(monthCar + liveTrip) + " كم");

        long[] v = new long[7];
        String[] l = new String[7];
        Calendar k = Calendar.getInstance();
        k.add(Calendar.DAY_OF_MONTH, -6);
        for (int i = 0; i < 7; i++) {
            String key = Store.dayKey(k);
            Db.Day x = i == 6 ? d : map.get(key);
            v[i] = x == null ? 0 : x.steps;
            l[i] = i == 6 ? "اليوم" : Store.WEEKDAYS[k.get(Calendar.DAY_OF_WEEK) - 1];
            k.add(Calendar.DAY_OF_MONTH, 1);
        }
        bars.set(v, l, goal);
    }

    void updateCountdown() {
        if (tvNext == null && tvPrCount == null) return;
        long now = System.currentTimeMillis();
        long[] nx = Prayer.next(this, now);
        if (nx == null) return;
        String left = "بعد " + Store.hms(nx[1] - now);
        if (tvNext != null) {
            tvNext.setText("🕌 الصلاة القادمة: " + Prayer.name((int) nx[0]) + " · " + Store.clock(nx[1]));
            tvCount.setText(left);
            tvCity.setText("📍 " + Prayer.place(this).name);
        }
        if (tvPrCount != null) tvPrCount.setText(Prayer.name((int) nx[0]) + " " + left);
    }

    // ───────────────────────── السجل ─────────────────────────

    void buildHistory(LinearLayout col) {
        col.addView(header("السجل", null));
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(12), 0, dp(12), dp(4));
        String[] names = {"يومي", "أسبوعي", "شهري", "🚗 السيارة"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView t = tv(names[i], 14, i == histMode ? 0xFF0F1720 : TXT, i == histMode);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            GradientDrawable g = new GradientDrawable();
            g.setColor(i == histMode ? (k == 3 ? CAR : ACC) : CARD);
            g.setCornerRadius(dp(10));
            t.setBackground(g);
            t.setOnClickListener(v -> { histMode = k; show(1); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            seg.addView(t, lp);
        }
        col.addView(seg);
        if (histMode == 3) { buildTrips(col); return; }

        int goal = Store.goal(this);
        Calendar start = Calendar.getInstance();
        start.add(Calendar.DAY_OF_MONTH, -400);
        HashMap<String, Db.Day> map = Db.get(this).since(Store.dayKey(start));
        Db.Day today = StepService.liveToday(this);
        map.put(today.day, today);

        if (histMode == 0) {
            long sum7 = 0;
            Calendar k = Calendar.getInstance();
            for (int i = 0; i < 7; i++) {
                Db.Day d = map.get(Store.dayKey(k));
                if (d != null) sum7 += d.steps;
                k.add(Calendar.DAY_OF_MONTH, -1);
            }
            LinearLayout s = card();
            s.addView(tv("متوسط آخر 7 أيام: " + Store.fmtInt(sum7 / 7) + " خطوة يومياً", 15, GOLD, true));
            col.addView(s);
            k = Calendar.getInstance();
            int shown = 0;
            for (int i = 0; i < 120; i++) {
                Db.Day d = map.get(Store.dayKey(k));
                if (i == 0 || (d != null && d.steps > 0)) {
                    long st = d == null ? 0 : d.steps;
                    double m = d == null ? 0 : d.meters;
                    int a = d == null ? 0 : d.activeMin;
                    row(col, (i == 0 ? "اليوم · " : "") + Store.dayLabel(k) + (st >= goal ? "  ✅" : ""),
                            Store.km(m) + " كم · " + a + " دقيقة", Store.fmtInt(st), ACC);
                    shown++;
                }
                k.add(Calendar.DAY_OF_MONTH, -1);
            }
            if (shown <= 1) col.addView(empty("ستظهر هنا أيامك مع أول خطوات"));
        } else if (histMode == 1) {
            Calendar ws = Calendar.getInstance();
            while (ws.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY) ws.add(Calendar.DAY_OF_MONTH, -1);
            for (int w = 0; w < 26; w++) {
                long st = 0;
                double m = 0;
                int days = 0;
                Calendar k = (Calendar) ws.clone();
                Calendar end = (Calendar) ws.clone();
                end.add(Calendar.DAY_OF_MONTH, 6);
                for (int i = 0; i < 7; i++) {
                    if (k.after(Calendar.getInstance())) break;
                    Db.Day d = map.get(Store.dayKey(k));
                    if (d != null) { st += d.steps; m += d.meters; }
                    days++;
                    k.add(Calendar.DAY_OF_MONTH, 1);
                }
                if (w == 0 || st > 0) {
                    String title = (w == 0 ? "هذا الأسبوع · " : "") + ws.get(Calendar.DAY_OF_MONTH) + " " + Store.MONTHS[ws.get(Calendar.MONTH)]
                            + " – " + end.get(Calendar.DAY_OF_MONTH) + " " + Store.MONTHS[end.get(Calendar.MONTH)];
                    row(col, title, Store.km(m) + " كم · المتوسط " + Store.fmtInt(st / Math.max(1, days)) + " يومياً", Store.fmtInt(st), ACC);
                }
                ws.add(Calendar.DAY_OF_MONTH, -7);
            }
        } else {
            Calendar ms = Calendar.getInstance();
            ms.set(Calendar.DAY_OF_MONTH, 1);
            for (int i = 0; i < 12; i++) {
                String key = Store.monthKey(ms);
                long st = 0;
                double m = 0;
                for (Db.Day d : map.values()) if (d.day.startsWith(key)) { st += d.steps; m += d.meters; }
                int days = i == 0 ? Calendar.getInstance().get(Calendar.DAY_OF_MONTH) : ms.getActualMaximum(Calendar.DAY_OF_MONTH);
                if (i == 0 || st > 0) {
                    row(col, Store.MONTHS[ms.get(Calendar.MONTH)] + " " + ms.get(Calendar.YEAR),
                            Store.km(m) + " كم · المتوسط " + Store.fmtInt(st / Math.max(1, days)) + " يومياً", Store.fmtInt(st), ACC);
                }
                ms.add(Calendar.MONTH, -1);
            }
        }
    }

    void row(LinearLayout col, String title, String sub, String big, int color) {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(tv(title, 15, TXT, true));
        t.addView(tv(sub, 13, SUB, false));
        c.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(tv(big, 20, color, true));
        col.addView(c);
    }

    TextView empty(String s) {
        TextView t = tv(s, 14, SUB, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(30), dp(16), dp(30));
        return t;
    }

    void buildTrips(LinearLayout col) {
        Calendar ms = Calendar.getInstance();
        ms.set(Calendar.DAY_OF_MONTH, 1);
        HashMap<String, Db.Day> map = Db.get(this).since(Store.dayKey(ms));
        double month = 0;
        for (Db.Day d : map.values()) month += d.carM;
        double today = 0;
        Db.Day td = map.get(Store.dayKey(System.currentTimeMillis()));
        if (td != null) today = td.carM;
        LinearLayout s = card();
        s.addView(tv("اليوم: " + Store.km(today) + " كم · هذا الشهر: " + Store.km(month) + " كم", 15, CAR, true));
        s.addView(hint(p.getBoolean("car_log", true)
                ? "تُسجَّل الرحلة تلقائياً عندما يكتشف الهاتف أنك في سيارة. اضغط مطولاً على رحلة لحذفها."
                : "تسجيل مسافة السيارة مطفأ من الإعدادات (الخطوات في السيارة لا تُحسب على أي حال)."));
        if (StepService.inCar) s.addView(tv("🚗 رحلة جارية الآن: " + Store.km(StepService.tripM) + " كم", 14, GOLD, true));
        col.addView(s);
        ArrayList<Db.Trip> trips = Db.get(this).trips(300);
        if (trips.isEmpty()) { col.addView(empty("لا توجد رحلات مسجّلة بعد")); return; }
        for (Db.Trip t : trips) {
            long mins = Math.max(1, (t.end - t.start) / 60_000);
            LinearLayout before = col;
            int idx = before.getChildCount();
            row(col, Store.dayLabel(t.start) + " · " + Store.clock(t.start) + " – " + Store.clock(t.end),
                    mins + " دقيقة · أقصى سرعة " + Math.round(t.maxKmh) + " كم/س", Store.km(t.meters) + " كم", CAR);
            View r = col.getChildAt(idx);
            r.setOnLongClickListener(v -> {
                dlg().setTitle("حذف هذه الرحلة؟").setMessage(Store.dayLabel(t.start) + " · " + Store.km(t.meters) + " كم")
                        .setPositiveButton("حذف", (d, w) -> { Db.get(this).deleteTrip(t); show(1); })
                        .setNegativeButton("إلغاء", null).show();
                return true;
            });
        }
    }

    // ───────────────────────── الصلاة ─────────────────────────

    void buildPrayer(LinearLayout col) {
        Calendar now = Calendar.getInstance();
        col.addView(header("مواقيت الصلاة", Store.dayLabel(now) + " · " + Prayer.hijriText(this, now)));
        Prayer.Place pl = Prayer.place(this);

        LinearLayout c = card();
        c.addView(tv("📍 " + pl.name + (pl.auto ? " (تلقائي حسب موقعك)" : " (مدينة مختارة)"), 16, TXT, true));
        if (pl.fallback) c.addView(hint("لم يُحدَّد موقعك بعد؛ فعّل إذن الموقع أو اختر مدينتك من الإعدادات."));
        c.addView(hint(Prayer.methodName(Prayer.methodIndex(p)) + " · العصر: " + (p.getInt("pr_asr", 0) == 1 ? "الحنفي" : "الجمهور")));
        tvPrCount = tv("", 22, GOLD, true);
        c.addView(tvPrCount);
        col.addView(c);

        int[] t = Prayer.times(this, now);
        long[] nx = Prayer.next(this, System.currentTimeMillis());
        LinearLayout tc = card();
        for (int i = 0; i < 6; i++) {
            boolean isNext = nx != null && nx[0] == i && t[i] >= 0 && Prayer.at(now, t[i]) == nx[1];
            LinearLayout r = new LinearLayout(this);
            r.setPadding(dp(4), dp(11), dp(4), dp(11));
            if (isNext) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x332ECC71);
                g.setCornerRadius(dp(10));
                r.setBackground(g);
            }
            int col1 = i == Prayer.SUNRISE ? SUB : (isNext ? GOLD : TXT);
            r.addView(tv(Prayer.name(i), 18, col1, isNext), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(tv(Prayer.hhmm(t[i], false), 18, col1, isNext));
            tc.addView(r);
        }
        col.addView(tc);

        LinearLayout qc = card();
        double q = Prayer.qibla(pl.lat, pl.lon);
        qc.addView(tv("🧭 القبلة: " + Math.round(q) + "° من الشمال (" + Prayer.compassName(q) + ")", 16, TXT, true));
        qc.addView(hint("ضع الهاتف أفقياً وبعيداً عن المعادن والسيارة؛ السهم الذهبي يشير إلى القبلة."));
        qibla = new Views.Qibla(this);
        qibla.setQibla(q);
        qc.addView(qibla, new LinearLayout.LayoutParams(-1, dp(240)));
        col.addView(qc);
        Sensor rv = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);

        LinearLayout b = card();
        b.addView(button("⚙️ إعدادات الصلاة والتنبيهات", v -> show(3)));
        col.addView(b);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (qibla == null || e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] r = new float[9], o = new float[3];
        SensorManager.getRotationMatrixFromVector(r, e.values);
        SensorManager.getOrientation(r, o);
        float az = (float) Math.toDegrees(o[0]);
        if (Float.isNaN(azSmooth)) azSmooth = az;
        float diff = ((az - azSmooth + 540) % 360) - 180;
        azSmooth = (azSmooth + diff * 0.15f + 360) % 360;
        qibla.setAzimuth(azSmooth);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    // ───────────────────────── الإعدادات ─────────────────────────

    void prayerChanged() {
        PrayerSched.schedule(this);
        StepsWidget.update(this);
        show(3);
    }

    void buildSettings(LinearLayout col) {
        col.addView(header("الإعدادات", null));

        // الخطوات
        col.addView(section("الخطوات والمسافة"));
        LinearLayout c = card();
        int goal = Store.goal(this);
        item(c, "الهدف اليومي", Store.fmtInt(goal) + " خطوة",
                v -> askNumber("الهدف اليومي (خطوات)", goal, 500, 100000, x -> { p.edit().putInt("goal", x).apply(); show(3); }));
        int hcm = Store.heightCm(this);
        item(c, "طولك", hcm + " سم",
                v -> askNumber("طولك بالسنتيمتر", hcm, 100, 230, x -> { p.edit().putInt("height_cm", x).apply(); show(3); }));
        int scm = p.getInt("stride_cm", 0);
        item(c, "طول الخطوة", scm > 0 ? scm + " سم (يدوي)" : Math.round(Store.strideM(this) * 100) + " سم (تلقائي من طولك)",
                v -> askNumber("طول الخطوة بالسنتيمتر (0 = تلقائي)", scm, 0, 150, x -> { p.edit().putInt("stride_cm", x).apply(); show(3); }));
        c.addView(hint("المسافة = عدد الخطوات × طول الخطوة. لقياس دقيق: امشِ 10 خطوات عادية، قِس المسافة بالسنتيمتر واقسمها على 10. "
                + "التغيير يُطبَّق على الخطوات القادمة."));
        col.addView(c);

        // السيارة
        col.addView(section("السيارة"));
        c = card();
        sw(c, "تسجيل مسافة رحلات السيارة", "يستخدم GPS أثناء القيادة فقط. خطوات السيارة لا تُحسب في كل الأحوال.", "car_log", true, null);
        col.addView(c);

        // الصلاة
        col.addView(section("مواقيت الصلاة"));
        c = card();
        Prayer.Place pl = Prayer.place(this);
        String city = p.getString("pr_city", "");
        item(c, "المدينة", city.isEmpty() ? "تلقائي: " + pl.name : city, v -> {
            String[] names = new String[Syria.CITIES.length + 1];
            names[0] = "📍 تلقائي (حسب موقعك)";
            for (int i = 0; i < Syria.CITIES.length; i++) names[i + 1] = Syria.CITIES[i].ar;
            choose("اختر المدينة", names, w -> {
                p.edit().putString("pr_city", w == 0 ? "" : Syria.CITIES[w - 1].ar).apply();
                prayerChanged();
            });
        });
        int mi = Prayer.methodIndex(p);
        item(c, "طريقة الحساب", Prayer.methodName(mi), v -> {
            String[] ms = new String[Prayer.METHOD_IDS.length];
            for (int i = 0; i < ms.length; i++) ms[i] = Prayer.methodName(i);
            choose("طريقة الحساب", ms, w -> { p.edit().putString("pr_method", Prayer.METHOD_IDS[w]).apply(); prayerChanged(); });
        });
        item(c, "وقت العصر", p.getInt("pr_asr", 0) == 1 ? "الحنفي" : "الجمهور (شافعي، مالكي، حنبلي)",
                v -> choose("وقت العصر", new String[]{"الجمهور (شافعي، مالكي، حنبلي)", "الحنفي"},
                        w -> { p.edit().putInt("pr_asr", w).apply(); prayerChanged(); }));
        StringBuilder adj = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int a = p.getInt("pr_adj_" + i, 0);
            if (a != 0) adj.append(adj.length() > 0 ? "، " : "").append(Prayer.name(i)).append(" ").append(a > 0 ? "+" : "").append(a);
        }
        item(c, "تعديل المواقيت بالدقائق", adj.length() == 0 ? "بدون تعديل" : adj.toString(), v -> {
            String[] it = new String[6];
            for (int i = 0; i < 6; i++) {
                int a = p.getInt("pr_adj_" + i, 0);
                it[i] = Prayer.name(i) + "  (" + (a > 0 ? "+" : "") + a + " د)";
            }
            choose("أي صلاة؟", it, w -> askNumber("تعديل " + Prayer.name(w) + " بالدقائق (+ تأخير، − تقديم)",
                    p.getInt("pr_adj_" + w, 0), -60, 60, x -> { p.edit().putInt("pr_adj_" + w, x).apply(); prayerChanged(); }));
        });
        int hadj = p.getInt("pr_hijri_adj", 0);
        item(c, "تعديل التاريخ الهجري", (hadj > 0 ? "+" : "") + hadj + " يوم", v -> {
            String[] it = {"-2", "-1", "0", "+1", "+2"};
            choose("تعديل التاريخ الهجري (أيام)", it, w -> { p.edit().putInt("pr_hijri_adj", w - 2).apply(); prayerChanged(); });
        });
        col.addView(c);

        // التنبيهات
        col.addView(section("التنبيهات والأذان"));
        c = card();
        int pre = p.getInt("pre_min", 15);
        sw(c, "تنبيه قبل الصلاة", "إشعار قبل الأذان بـ " + pre + " دقيقة", "pre_on", true, this::prayerChanged);
        item(c, "التنبيه قبل الأذان بـ", pre + " دقيقة", v -> {
            int[] opts = {5, 10, 15, 20, 30};
            String[] it = new String[opts.length];
            for (int i = 0; i < opts.length; i++) it[i] = opts[i] + " دقيقة";
            choose("التنبيه قبل الأذان بـ", it, w -> { p.edit().putInt("pre_min", opts[w]).apply(); prayerChanged(); });
        });
        sw(c, "تنبيه الأذان", "عند دخول وقت كل صلاة", "adhan_on", true, this::prayerChanged);
        sw(c, "تشغيل صوت الأذان", "إذا أطفأته يظهر إشعار صامت فقط", "adhan_sound", true, null);
        item(c, "صوت الأذان (الظهر، العصر، المغرب، العشاء)", AdhanLib.label(this, p.getString("adhan_main", "")),
                v -> pickSound(false));
        item(c, "صوت أذان الفجر", AdhanLib.label(this, p.getString("adhan_fajr", AdhanLib.SAME)), v -> pickSound(true));
        item(c, "▶️ تجربة أذان الظهر", "", v -> AdhanService.start(this, Prayer.DHUHR, true));
        item(c, "▶️ تجربة أذان الفجر", "", v -> AdhanService.start(this, Prayer.FAJR, true));
        c.addView(hint("لإيقاف الأذان: زر «إيقاف» في شاشة الأذان، أو أي زر من أزرار الصوت، أو النقر على الإشعار أو سحبه. "
                + "الصوت يخرج بمستوى صوت «المنبّه» في الهاتف."));
        if (!canFullScreen()) {
            item(c, "⚠️ السماح بشاشة الأذان فوق القفل", "اضغط للتفعيل", v -> openFullScreenSettings());
        }
        col.addView(c);

        // مكتبة الأذان
        col.addView(section("🎵 مكتبة الأذان"));
        c = card();
        ArrayList<AdhanLib.Sound> lib = AdhanLib.list(this);
        if (lib.isEmpty()) c.addView(hint("أضف ملفات أذان من هاتفك (mp3 أو غيرها)، ثم اختر منها صوت الأذان وصوت الفجر بالأعلى."));
        for (AdhanLib.Sound s : lib) libRow(c, s);
        c.addView(button("➕ إضافة ملفات أذان من الهاتف", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { startActivityForResult(i, RC_ADHAN); } catch (ActivityNotFoundException e) { toast("لا يوجد مدير ملفات"); }
        }));
        TextView net = button("🌐 تنزيل أصوات جاهزة", v -> openCatalog());
        net.setBackground(rounded(CAR));
        c.addView(net);
        TextView link = button("🔗 تنزيل من رابط", v -> askText("الصق رابط ملف الأذان المباشر (mp3)", "https://", url -> {
            if (!url.startsWith("http")) { toast("الرابط يجب أن يبدأ بـ http"); return; }
            askText("اسم هذا الأذان", AdhanLib.nameFromUrl(url), nm -> downloadSound(url, nm));
        }));
        link.setBackground(rounded(CARD_LIGHT));
        link.setTextColor(TXT);
        c.addView(link);
        c.addView(hint("الملفات تُحفظ داخل التطبيق وتعمل بدون إنترنت. اضغط على الاسم لتغييره. "
                + "روابط Google Drive العادية لا تعمل لأنها تفتح صفحة؛ استخدم رابط تنزيل مباشر."));
        col.addView(c);

        // الخلفية والبطارية
        col.addView(section("التشغيل في الخلفية"));
        c = card();
        c.addView(tv(permLine(), 14, TXT, false));
        c.addView(button("أكمل الإعداد / الأذونات", v -> { askedThisRun.clear(); runSetup(); }));
        item(c, "استثناء من توفير البطارية", ignoringBattery() ? "✅ مفعّل" : "اضغط للتفعيل", v -> requestIgnoreBattery());
        item(c, "صفحة التطبيق في إعدادات الهاتف", "البطارية ← غير مقيّد", v -> openAppDetails());
        item(c, "إعدادات البطارية في سامسونغ", "", v -> openSamsungBattery());
        c.addView(hint("في سامسونغ للأمان: الإعدادات ← البطارية ← حدود الاستخدام في الخلفية ← «التطبيقات التي لا تدخل وضع السكون أبداً» ← أضف «خطواتي»."));
        col.addView(c);

        // النسخ الاحتياطي
        col.addView(section("البيانات والنسخ الاحتياطي"));
        c = card();
        c.addView(hint("كل بياناتك محفوظة على هذا الهاتف فقط."));
        item(c, "📤 تصدير نسخة احتياطية", "ملف تحفظه أين تشاء", v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "khatawati-" + Store.dayKey(System.currentTimeMillis()) + ".json");
            try { startActivityForResult(i, RC_EXPORT); } catch (ActivityNotFoundException e) { toast("لا يوجد مدير ملفات"); }
        });
        item(c, "📥 استيراد نسخة احتياطية", "تُدمج مع بياناتك الحالية", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, RC_IMPORT); } catch (ActivityNotFoundException e) { toast("لا يوجد مدير ملفات"); }
        });
        col.addView(c);

        // التحديثات
        col.addView(section("التحديثات"));
        c = card();
        item(c, "الإصدار الحالي", Updater.current(this), null);
        item(c, "🔄 البحث عن تحديثات", "", v -> checkUpdate(false));
        item(c, "📝 ما الجديد", "", v -> showWhatsNew());
        sw(c, "البحث التلقائي يومياً", "إشعار عند صدور إصدار جديد", "auto_update", true, null);
        col.addView(c);
    }

    String permLine() {
        StringBuilder s = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 29) s.append(granted(Manifest.permission.ACTIVITY_RECOGNITION) ? "✅" : "❌").append(" النشاط البدني\n");
        s.append(Notif.canPost(this) ? "✅" : "❌").append(" الإشعارات\n");
        s.append(granted(Manifest.permission.ACCESS_FINE_LOCATION) ? "✅" : "❌").append(" الموقع\n");
        if (Build.VERSION.SDK_INT >= 29) s.append(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ? "✅" : "❌").append(" الموقع طوال الوقت\n");
        s.append(ignoringBattery() ? "✅" : "❌").append(" استثناء البطارية\n");
        s.append(canFullScreen() ? "✅" : "❌").append(" شاشة الأذان فوق القفل\n");
        s.append(p.getBoolean("ar_ok", false) ? "✅" : "⏳").append(" كشف السيارة (خدمات Google)\n");
        s.append(StepService.running ? "✅" : "❌").append(" العدّاد يعمل");
        return s.toString();
    }

    // ───────────────────────── الأذونات والإعداد ─────────────────────────

    boolean ignoringBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Throwable e) {
            openAppDetails();
        }
    }

    void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) { }
    }

    void openSamsungBattery() {
        try {
            startActivity(new Intent().setComponent(new ComponentName("com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity")));
        } catch (Throwable e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Throwable ignored) { openAppDetails(); }
        }
    }

    String permLabel(String perm) {
        if (perm.equals(Manifest.permission.ACTIVITY_RECOGNITION)) return "النشاط البدني";
        if (perm.equals(Manifest.permission.POST_NOTIFICATIONS)) return "الإشعارات";
        return "الموقع";
    }

    /** يطلب الأذونات واحداً تلو الآخر، ثم استثناء البطارية. */
    void runSetup() {
        ArrayList<String[]> groups = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) groups.add(new String[]{Manifest.permission.ACTIVITY_RECOGNITION});
        if (Build.VERSION.SDK_INT >= 33) groups.add(new String[]{Manifest.permission.POST_NOTIFICATIONS});
        groups.add(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        if (Build.VERSION.SDK_INT >= 29) groups.add(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION});
        for (String[] g : groups) {
            String key = g[0];
            if (granted(key) || askedThisRun.contains(key)) continue;
            if (key.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) continue;
                askedThisRun.add(key);
                dlg().setTitle("الموقع «طوال الوقت»")
                        .setMessage("حتى تُسجَّل مسافة السيارة وتُعرف مدينتك للصلاة والتطبيق مغلق:\nفي الصفحة التالية اختر «السماح طوال الوقت» ثم ارجع.")
                        .setPositiveButton("متابعة", (d, w) -> requestPermissions(g, RC_PERM))
                        .setNegativeButton("لاحقاً", (d, w) -> runSetup()).show();
                return;
            }
            askedThisRun.add(key);
            if (p.getBoolean("asked_" + key, false) && !shouldShowRequestPermissionRationale(key)) {
                dlg().setTitle("الإذن مرفوض سابقاً")
                        .setMessage("افتح صفحة التطبيق ← الأذونات ← فعّل «" + permLabel(key) + "».")
                        .setPositiveButton("فتح الصفحة", (d, w) -> openAppDetails())
                        .setNegativeButton("تخطي", (d, w) -> runSetup()).show();
                return;
            }
            p.edit().putBoolean("asked_" + key, true).apply();
            requestPermissions(g, RC_PERM);
            return;
        }
        if (!ignoringBattery() && !askedThisRun.contains("battery")) {
            askedThisRun.add("battery");
            requestIgnoreBattery();
            return;
        }
        if (!canFullScreen() && !askedThisRun.contains("fullscreen")) {
            askedThisRun.add("fullscreen");
            dlg().setTitle("شاشة الأذان")
                    .setMessage("حتى تظهر شاشة الأذان وزر الإيقاف فوق شاشة القفل: فعّل «خطواتي» في الصفحة التالية ثم ارجع.")
                    .setPositiveButton("متابعة", (d, w) -> openFullScreenSettings())
                    .setNegativeButton("لاحقاً", (d, w) -> runSetup()).show();
            return;
        }
        StepService.start(this);
        show(tab);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        StepService.start(this);
        refreshLocation();
        h.postDelayed(this::runSetup, 300);
    }

    void refreshLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return;
        try {
            Location l = Prayer.lastKnown(this);
            if (l != null && Prayer.saveFix(this, l.getLatitude(), l.getLongitude())) PrayerSched.schedule(this);
            if (Build.VERSION.SDK_INT >= 30) {
                LocationManager lm = getSystemService(LocationManager.class);
                if (lm == null) return;
                String prov = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                        : LocationManager.GPS_PROVIDER;
                lm.getCurrentLocation(prov, null, getMainExecutor(), loc -> {
                    if (loc != null && Prayer.saveFix(this, loc.getLatitude(), loc.getLongitude())) {
                        PrayerSched.schedule(this);
                        StepsWidget.update(this);
                        if (!isFinishing() && tab != 3) show(tab);
                    }
                });
            }
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── الملفات (أذان، نسخ احتياطي) ─────────────────────────

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (res != RESULT_OK || data == null) return;
        if (rc == RC_ADHAN) { addSounds(data); return; }
        if (data.getData() == null) return;
        Uri u = data.getData();
        try {
            if (rc == RC_EXPORT) {
                JSONObject j = Db.get(this).export();
                j.put("settings", settingsJson());
                try (OutputStream out = getContentResolver().openOutputStream(u, "wt")) {
                    if (out == null) throw new Exception("تعذّر فتح الملف");
                    out.write(j.toString(1).getBytes(StandardCharsets.UTF_8));
                }
                toast("✅ تم حفظ النسخة الاحتياطية");
            } else if (rc == RC_IMPORT) {
                String txt;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) throw new Exception("تعذّر فتح الملف");
                    txt = Updater.readAll(in);
                }
                JSONObject j = new JSONObject(txt);
                int[] n = Db.get(this).importJson(j);
                JSONObject s = j.optJSONObject("settings");
                if (s != null) restoreSettings(s);
                PrayerSched.schedule(this);
                StepsWidget.update(this);
                alert("✅ تم الاستيراد", n[0] + " يوم مشي و " + n[1] + " رحلة سيارة.");
                show(tab);
            }
        } catch (Throwable e) {
            alert("لم تنجح العملية", rc == RC_IMPORT ? "الملف ليس نسخة احتياطية من «خطواتي».\n" + e.getMessage() : String.valueOf(e.getMessage()));
        }
    }

    // ───────── مكتبة الأذان ─────────

    static final int CARD_LIGHT = 0xFF2C3A4A;

    GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    void openCatalog() {
        AlertDialog wait = dlg().setMessage("جارٍ جلب قائمة الأصوات…").setCancelable(false).show();
        new Thread(() -> {
            try {
                ArrayList<AdhanLib.Item> items = AdhanLib.catalog();
                runOnUiThread(() -> { dismiss(wait); showCatalog(items); });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    dismiss(wait);
                    alert("تعذّر جلب القائمة", "تأكد من الإنترنت وحاول مرة أخرى.\n(" + e.getMessage() + ")");
                });
            }
        }).start();
    }

    void showCatalog(ArrayList<AdhanLib.Item> items) {
        if (isFinishing()) return;
        if (items.isEmpty()) {
            dlg().setTitle("🌐 القائمة فارغة")
                    .setMessage("لم تُضف أي أصوات بعد إلى ملف adhan.json في المستودع.\nاضغط «تعديل القائمة» لإضافة الاسم والرابط لكل صوت.")
                    .setPositiveButton("تعديل القائمة", (d, w) -> openUrl(AdhanLib.CATALOG_EDIT))
                    .setNegativeButton("إغلاق", null).show();
            return;
        }
        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            AdhanLib.Item it = items.get(i);
            names[i] = (AdhanLib.hasSrc(this, it.url) ? "✅ " : "⬇️ ") + it.name + (it.note.isEmpty() ? "" : "  (" + it.note + ")");
        }
        dlg().setTitle("🌐 أصوات جاهزة للتنزيل").setItems(names, (d, w) -> {
            AdhanLib.Item it = items.get(w);
            if (AdhanLib.hasSrc(this, it.url)) {
                dlg().setMessage("«" + it.name + "» موجود في مكتبتك. تنزيله مرة أخرى؟")
                        .setPositiveButton("تنزيل", (d2, w2) -> downloadSound(it.url, it.name))
                        .setNegativeButton("إلغاء", null).show();
            } else {
                downloadSound(it.url, it.name);
            }
        }).setNeutralButton("تعديل القائمة", (d, w) -> openUrl(AdhanLib.CATALOG_EDIT))
                .setNegativeButton("إغلاق", null).show();
    }

    void openUrl(String u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Throwable ignored) { }
    }

    void downloadSound(String url, String name) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView pct = tv("…", 15, TXT, false);
        pct.setGravity(Gravity.CENTER);
        box.addView(bar, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(pct);
        AlertDialog d = dlg().setTitle("جارٍ تنزيل «" + name + "»").setView(box).setCancelable(false).show();
        new Thread(() -> {
            try {
                AdhanLib.Sound s = AdhanLib.addFromUrl(this, url, name,
                        x -> runOnUiThread(() -> { bar.setProgress(x); pct.setText(x + "%"); }));
                runOnUiThread(() -> {
                    dismiss(d);
                    if (p.getString("adhan_main", "").isEmpty()) p.edit().putString("adhan_main", s.id).apply();
                    toast("✅ أُضيف «" + s.name + "» إلى مكتبة الأذان");
                    show(3);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    dismiss(d);
                    alert("لم يكتمل التنزيل", String.valueOf(e.getMessage()));
                });
            }
        }).start();
    }

    void addSounds(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        AlertDialog wait = dlg().setMessage("جارٍ نسخ " + uris.size() + " ملف إلى المكتبة…").setCancelable(false).show();
        new Thread(() -> {
            ArrayList<AdhanLib.Sound> added = new ArrayList<>();
            int failed = 0;
            for (Uri u : uris) {
                try { added.add(AdhanLib.add(this, u)); } catch (Throwable e) { failed++; }
            }
            final int nf = failed;
            runOnUiThread(() -> {
                dismiss(wait);
                if (!added.isEmpty() && p.getString("adhan_main", "").isEmpty()) {
                    p.edit().putString("adhan_main", added.get(0).id).apply(); // أول ملف يصبح صوت الأذان إن لم يُختر صوت بعد
                }
                if (added.size() == 1) {
                    AdhanLib.Sound s = added.get(0);
                    askText("اسم هذا الأذان", s.name, x -> { AdhanLib.rename(this, s.id, x); show(3); });
                } else {
                    toast("أُضيف " + added.size() + " ملف" + (nf > 0 ? " · تعذّر " + nf : ""));
                }
                show(3);
            });
        }).start();
    }

    interface TextCb { void on(String v); }

    void askText(String title, String cur, TextCb cb) {
        EditText e = new EditText(this);
        e.setText(cur);
        e.setSelectAllOnFocus(true);
        e.setTextColor(TXT);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(24), dp(8), dp(24), 0);
        f.addView(e);
        dlg().setTitle(title).setView(f).setPositiveButton("حفظ", (d, w) -> {
            String v = e.getText().toString().trim();
            if (!v.isEmpty()) cb.on(v);
        }).setNegativeButton("إلغاء", null).show();
    }

    void libRow(LinearLayout parent, AdhanLib.Sound s) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        String tag = "";
        if (s.id.equals(p.getString("adhan_main", ""))) tag += " · الأذان";
        if (s.id.equals(p.getString("adhan_fajr", AdhanLib.SAME))) tag += " · الفجر";
        TextView n = tv("🎵 " + s.name + tag, 16, TXT, false);
        n.setPadding(0, dp(8), 0, dp(8));
        n.setOnClickListener(v -> askText("تغيير الاسم", s.name, x -> { AdhanLib.rename(this, s.id, x); show(3); }));
        ripple(n);
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        TextView play = tv("▶️", 22, TXT, false);
        play.setPadding(dp(12), dp(6), dp(12), dp(6));
        play.setOnClickListener(v -> AdhanService.start(this, Prayer.DHUHR, true, s.id));
        ripple(play);
        r.addView(play);
        TextView del = tv("🗑", 20, TXT, false);
        del.setPadding(dp(12), dp(6), dp(4), dp(6));
        del.setOnClickListener(v -> dlg().setTitle("حذف «" + s.name + "»؟")
                .setPositiveButton("حذف", (d, w) -> { AdhanLib.delete(this, s.id); show(3); })
                .setNegativeButton("إلغاء", null).show());
        ripple(del);
        r.addView(del);
        parent.addView(r);
    }

    void pickSound(boolean fajr) {
        ArrayList<AdhanLib.Sound> lib = AdhanLib.list(this);
        ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>();
        if (fajr) { ids.add(AdhanLib.SAME); names.add("↔️ نفس صوت الأذان"); }
        ids.add("");
        names.add("🔔 نغمة المنبّه الافتراضية");
        for (AdhanLib.Sound s : lib) { ids.add(s.id); names.add("🎵 " + s.name); }
        if (lib.isEmpty()) toast("المكتبة فارغة: أضف ملفات أذان من «مكتبة الأذان» بالأسفل");
        choose(fajr ? "صوت أذان الفجر" : "صوت الأذان", names.toArray(new String[0]), w -> {
            p.edit().putString(fajr ? "adhan_fajr" : "adhan_main", ids.get(w)).apply();
            show(3);
        });
    }

    boolean canFullScreen() {
        if (Build.VERSION.SDK_INT < 34) return true;
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        return nm == null || nm.canUseFullScreenIntent();
    }

    void openFullScreenSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())));
        } catch (Throwable e) {
            openAppDetails();
        }
    }

    static final String[] SETTING_KEYS = {"goal", "height_cm", "stride_cm", "car_log", "pre_on", "pre_min", "adhan_on", "adhan_sound",
            "pr_method", "pr_asr", "pr_city", "pr_hijri_adj", "pr_adj_0", "pr_adj_1", "pr_adj_2", "pr_adj_3", "pr_adj_4", "pr_adj_5",
            "auto_update"};

    JSONObject settingsJson() throws Exception {
        JSONObject s = new JSONObject();
        Map<String, ?> all = p.getAll();
        for (String k : SETTING_KEYS) if (all.containsKey(k)) s.put(k, all.get(k));
        return s;
    }

    void restoreSettings(JSONObject s) {
        SharedPreferences.Editor e = p.edit();
        for (String k : SETTING_KEYS) {
            if (!s.has(k)) continue;
            Object v = s.opt(k);
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putInt(k, (int) (long) (Long) v);
            else if (v instanceof String) e.putString(k, (String) v);
        }
        e.apply();
    }

    // ───────────────────────── التحديثات و«ما الجديد» ─────────────────────────

    void maybeWhatsNew() {
        long cur = Updater.currentCode(this);
        long seen = p.getLong("seen_vc", 0);
        if (seen == 0) { p.edit().putLong("seen_vc", cur).apply(); return; } // أول تثبيت
        if (seen < cur) {
            p.edit().putLong("seen_vc", cur).apply();
            h.postDelayed(this::showWhatsNew, 500);
        }
    }

    void showWhatsNew() {
        if (isFinishing()) return;
        String t = Updater.whatsNew(this);
        TextView v = tv(t.isEmpty() ? "لا توجد ملاحظات." : t, 15, TXT, false);
        v.setPadding(dp(22), dp(10), dp(22), dp(10));
        v.setLineSpacing(0, 1.25f);
        ScrollView s = new ScrollView(this);
        s.addView(v);
        dlg().setTitle("📝 ما الجديد في خطواتي").setView(s).setPositiveButton("حسناً", null).show();
    }

    void checkUpdate(boolean silent) {
        if (isFinishing()) return;
        AlertDialog wait = dlg().setMessage("جارٍ البحث عن تحديثات…").setCancelable(false).show();
        new Thread(() -> {
            try {
                Updater.Info inf = Updater.fetch();
                runOnUiThread(() -> {
                    dismiss(wait);
                    if (!inf.version.isEmpty() && Updater.cmp(inf.version, Updater.current(this)) > 0) showUpdate(inf);
                    else if (!silent) alert("✅ أنت على آخر إصدار", "الإصدار الحالي: " + Updater.current(this));
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    dismiss(wait);
                    if (!silent) alert("تعذّر البحث عن تحديثات", "تأكد من الاتصال بالإنترنت ثم حاول مرة أخرى.\n(" + e.getMessage() + ")");
                });
            }
        }).start();
    }

    void dismiss(AlertDialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Throwable ignored) { }
    }

    void showUpdate(Updater.Info inf) {
        if (isFinishing()) return;
        String notes = Updater.clean(inf.notes.replaceAll("(?s)\\n---.*$", ""));
        dlg().setTitle("🆕 إصدار جديد: " + inf.version)
                .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "إصدارك الحالي: " + Updater.current(this))
                .setPositiveButton("تحميل وتثبيت", (d, w) -> startDownload(inf))
                .setNegativeButton("لاحقاً", null).show();
    }

    void startDownload(Updater.Info inf) {
        if (inf.apkUrl.isEmpty()) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(inf.page))); } catch (Throwable ignored) { }
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            dlg().setTitle("اسمح بتثبيت التحديثات")
                    .setMessage("مرة واحدة فقط: في الصفحة التالية فعّل «السماح من هذا المصدر» لتطبيق خطواتي، ثم ارجع واضغط «تحميل وتثبيت» مرة أخرى.")
                    .setPositiveButton("فتح الصفحة", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                        } catch (Throwable e) { openAppDetails(); }
                    })
                    .setNegativeButton("إلغاء", null).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView pct = tv("0%", 15, TXT, false);
        pct.setGravity(Gravity.CENTER);
        box.addView(bar, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(pct);
        AlertDialog d = dlg().setTitle("جارٍ تحميل " + inf.version).setView(box).setCancelable(false).show();
        new Thread(() -> {
            try {
                File f = Updater.download(this, inf.apkUrl, x -> runOnUiThread(() -> { bar.setProgress(x); pct.setText(x + "%"); }));
                runOnUiThread(() -> pct.setText("جارٍ التثبيت…"));
                Updater.install(this, f);
                runOnUiThread(() -> dismiss(d));
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    dismiss(d);
                    alert("لم يكتمل التحديث", "تأكد من الإنترنت وحاول مرة أخرى.\n(" + e.getMessage() + ")");
                });
            }
        }).start();
    }

    static String fmt(String f, Object... a) { return String.format(Locale.US, f, a); }
}
