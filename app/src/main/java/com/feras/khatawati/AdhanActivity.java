package com.feras.khatawati;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** شاشة الأذان: تظهر عند دخول الوقت (حتى فوق شاشة القفل) وفيها زر إيقاف كبير. أزرار الصوت توقفه أيضاً. */
public class AdhanActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView tv(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(MainActivity.BG);
        getWindow().setNavigationBarColor(MainActivity.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(MainActivity.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        root.addView(tv("🕌", 72, 0xFFFFFFFF, false));
        String title = AdhanService.nowTitle.isEmpty() ? "حان وقت الأذان" : AdhanService.nowTitle.replace("🕌 ", "");
        TextView t = tv(title, 26, MainActivity.GOLD, true);
        t.setPadding(0, dp(16), 0, dp(8));
        root.addView(t);
        root.addView(tv(Store.clock(System.currentTimeMillis()) + " · " + Prayer.place(this).name, 18, MainActivity.SUB, false));

        TextView stop = tv("⏹  إيقاف الأذان", 24, 0xFF0F1720, true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(MainActivity.ACC);
        g.setCornerRadius(dp(40));
        stop.setBackground(g);
        stop.setPadding(dp(24), dp(22), dp(24), dp(22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(48), 0, dp(16));
        root.addView(stop, lp);
        stop.setOnClickListener(v -> stopAdhan());
        root.addView(tv("أو اضغط زر رفع/خفض الصوت", 15, MainActivity.SUB, false));

        setContentView(root);
        h.post(watch);
    }

    /** تُغلق الشاشة تلقائياً عندما ينتهي الأذان. */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!AdhanService.playing) { finish(); return; }
            h.postDelayed(this, 500);
        }
    };

    private void stopAdhan() {
        AdhanService.stop(this);
        finish();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_BACK) {
            stopAdhan();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { stopAdhan(); }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(watch);
        super.onDestroy();
    }
}
