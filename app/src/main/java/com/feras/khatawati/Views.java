package com.feras.khatawati;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/** الرسوم: حلقة الهدف، أعمدة آخر 7 أيام، بوصلة القبلة. */
final class Views {

    private Views() { }

    static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    static Paint text(Context c, float spSize, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(sp(c, spSize));
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(bold);
        return p;
    }

    /** حلقة التقدم نحو الهدف اليومي. */
    static class Ring extends View {
        long steps;
        int goal = 8000;
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), prog = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint big, small;
        final RectF r = new RectF();
        final float stroke;

        Ring(Context c) {
            super(c);
            stroke = dp(c, 16);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(0xFF243240);
            prog.setStyle(Paint.Style.STROKE);
            prog.setStrokeWidth(stroke);
            prog.setStrokeCap(Paint.Cap.ROUND);
            prog.setColor(MainActivity.ACC);
            big = text(c, 44, 0xFFFFFFFF, true);
            small = text(c, 15, MainActivity.SUB, false);
        }

        void set(long s, int g) {
            steps = s;
            goal = Math.max(1, g);
            prog.setColor(s >= goal ? MainActivity.GOLD : MainActivity.ACC);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float size = Math.min(w, h) - stroke * 2;
            float cx = w / 2, cy = h / 2;
            r.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            cv.drawArc(r, 0, 360, false, track);
            float sweep = 360f * Math.min(1f, steps / (float) goal);
            if (sweep > 0) cv.drawArc(r, -90, sweep, false, prog);
            float y = cy + big.getTextSize() / 3;
            cv.drawText(Store.fmtInt(steps), cx, y, big);
            cv.drawText("خطوة من " + Store.fmtInt(goal), cx, y + small.getTextSize() * 1.7f, small);
        }
    }

    /** أعمدة آخر 7 أيام (الأقدم يميناً، اليوم يساراً). */
    static class Bars extends View {
        long[] v = new long[0];
        String[] labels = new String[0];
        int goal = 8000;
        final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), goalP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint lab, val;
        final RectF rr = new RectF();

        Bars(Context c) {
            super(c);
            goalP.setColor(MainActivity.GOLD);
            goalP.setStyle(Paint.Style.STROKE);
            goalP.setStrokeWidth(dp(c, 1.5f));
            goalP.setPathEffect(new DashPathEffect(new float[]{dp(c, 6), dp(c, 5)}, 0));
            lab = text(c, 12, MainActivity.SUB, false);
            val = text(c, 11, 0xFFFFFFFF, false);
        }

        void set(long[] values, String[] l, int g) {
            v = values;
            labels = l;
            goal = Math.max(1, g);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = v.length;
            if (n == 0) return;
            float w = getWidth(), h = getHeight();
            float top = val.getTextSize() * 1.6f, bottom = h - lab.getTextSize() * 1.8f;
            long max = goal;
            for (long x : v) max = Math.max(max, x);
            float slot = w / n, bw = slot * 0.55f, rad = dp(getContext(), 5);
            float gy = bottom - (bottom - top) * goal / (float) max;
            Path gp = new Path();
            gp.moveTo(0, gy);
            gp.lineTo(w, gy);
            cv.drawPath(gp, goalP);
            for (int i = 0; i < n; i++) {
                float cx = w - (i + 0.5f) * slot;
                float bh = (bottom - top) * v[i] / (float) max;
                bar.setColor(v[i] >= goal ? MainActivity.GOLD : (i == n - 1 ? MainActivity.ACC : 0xFF2E7D5B));
                rr.set(cx - bw / 2, bottom - Math.max(bh, dp(getContext(), 2)), cx + bw / 2, bottom);
                cv.drawRoundRect(rr, rad, rad, bar);
                if (v[i] > 0) cv.drawText(Store.shortNum(v[i]), cx, rr.top - val.getTextSize() * 0.4f, val);
                if (i < labels.length) cv.drawText(labels[i], cx, h - lab.getTextSize() * 0.4f, lab);
            }
        }
    }

    /** بوصلة القبلة: السهم يشير إلى القبلة عندما يكون الهاتف أفقياً. */
    static class Qibla extends View {
        float azimuth;
        double qibla;
        boolean hasSensor;
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint north, kaaba;

        Qibla(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(c, 3));
            ring.setColor(0xFF34495E);
            arrow.setColor(MainActivity.GOLD);
            arrow.setStyle(Paint.Style.FILL);
            north = text(c, 16, 0xFFE74C3C, true);
            kaaba = text(c, 26, 0xFFFFFFFF, false);
        }

        void setQibla(double q) { qibla = q; invalidate(); }

        void setAzimuth(float a) { azimuth = a; hasSensor = true; invalidate(); }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float rad = Math.min(w, h) / 2 - dp(getContext(), 18);
            cv.drawCircle(cx, cy, rad, ring);
            // الشمال
            cv.save();
            cv.rotate(-azimuth, cx, cy);
            cv.drawText("ش", cx, cy - rad - dp(getContext(), 4), north);
            cv.restore();
            // سهم القبلة
            cv.save();
            cv.rotate((float) qibla - azimuth, cx, cy);
            Path p = new Path();
            float aw = dp(getContext(), 12);
            p.moveTo(cx, cy - rad + dp(getContext(), 30));
            p.lineTo(cx - aw, cy);
            p.lineTo(cx + aw, cy);
            p.close();
            cv.drawPath(p, arrow);
            cv.drawText("🕋", cx, cy - rad + dp(getContext(), 22), kaaba);
            cv.restore();
            cv.drawCircle(cx, cy, dp(getContext(), 6), arrow);
        }
    }
}
