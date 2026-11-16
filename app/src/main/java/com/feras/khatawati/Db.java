package com.feras.khatawati;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

/** قاعدة البيانات على الهاتف: سجل أيام المشي، ورحلات السيارة (منفصلة). */
public final class Db extends SQLiteOpenHelper {

    private static Db inst;

    public static synchronized Db get(Context c) {
        if (inst == null) inst = new Db(c.getApplicationContext());
        return inst;
    }

    private Db(Context c) { super(c, "khatawati.db", null, 1); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE days(day TEXT PRIMARY KEY, steps INTEGER NOT NULL DEFAULT 0, meters REAL NOT NULL DEFAULT 0, "
                + "active_min INTEGER NOT NULL DEFAULT 0, car_m REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, start INTEGER NOT NULL, end_at INTEGER NOT NULL, "
                + "meters REAL NOT NULL, max_kmh REAL NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    public static class Day {
        public String day;
        public long steps;
        public double meters;
        public int activeMin;
        public double carM;
    }

    public static class Trip {
        public long id, start, end;
        public double meters, maxKmh;
    }

    private static void ensure(SQLiteDatabase db, String day) {
        db.execSQL("INSERT OR IGNORE INTO days(day) VALUES(?)", new Object[]{day});
    }

    public synchronized void addWalk(String day, long steps, double meters, int active) {
        SQLiteDatabase db = getWritableDatabase();
        ensure(db, day);
        db.execSQL("UPDATE days SET steps=steps+?, meters=meters+?, active_min=active_min+? WHERE day=?",
                new Object[]{steps, meters, active, day});
    }

    public synchronized void addCar(String day, double m) {
        SQLiteDatabase db = getWritableDatabase();
        ensure(db, day);
        db.execSQL("UPDATE days SET car_m=MAX(0, car_m+?) WHERE day=?", new Object[]{m, day});
    }

    public synchronized void addTrip(long start, long end, double m, double maxKmh) {
        ContentValues v = new ContentValues();
        v.put("start", start);
        v.put("end_at", end);
        v.put("meters", m);
        v.put("max_kmh", maxKmh);
        getWritableDatabase().insert("trips", null, v);
    }

    public synchronized void deleteTrip(Trip t) {
        getWritableDatabase().delete("trips", "id=?", new String[]{String.valueOf(t.id)});
        addCar(Store.dayKey(t.start), -t.meters);
    }

    private static Day read(Cursor c) {
        Day d = new Day();
        d.day = c.getString(0);
        d.steps = c.getLong(1);
        d.meters = c.getDouble(2);
        d.activeMin = c.getInt(3);
        d.carM = c.getDouble(4);
        return d;
    }

    public synchronized Day day(String day) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT day,steps,meters,active_min,car_m FROM days WHERE day=?", new String[]{day})) {
            if (c.moveToFirst()) return read(c);
        }
        Day d = new Day();
        d.day = day;
        return d;
    }

    /** كل الأيام من تاريخ معيّن (yyyy-MM-dd) حتى اليوم. */
    public synchronized HashMap<String, Day> since(String from) {
        HashMap<String, Day> m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT day,steps,meters,active_min,car_m FROM days WHERE day>=?", new String[]{from})) {
            while (c.moveToNext()) {
                Day d = read(c);
                m.put(d.day, d);
            }
        }
        return m;
    }

    public synchronized ArrayList<Trip> trips(int limit) {
        ArrayList<Trip> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,start,end_at,meters,max_kmh FROM trips ORDER BY start DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                Trip t = new Trip();
                t.id = c.getLong(0);
                t.start = c.getLong(1);
                t.end = c.getLong(2);
                t.meters = c.getDouble(3);
                t.maxKmh = c.getDouble(4);
                out.add(t);
            }
        }
        return out;
    }

    // ───────── النسخ الاحتياطي ─────────

    public synchronized JSONObject export() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("app", "khatawati");
        j.put("format", 1);
        j.put("exported", System.currentTimeMillis());
        JSONArray days = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day,steps,meters,active_min,car_m FROM days ORDER BY day", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("d", c.getString(0));
                o.put("s", c.getLong(1));
                o.put("m", c.getDouble(2));
                o.put("a", c.getInt(3));
                o.put("c", c.getDouble(4));
                days.put(o);
            }
        }
        j.put("days", days);
        JSONArray trips = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT start,end_at,meters,max_kmh FROM trips ORDER BY start", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("s", c.getLong(0));
                o.put("e", c.getLong(1));
                o.put("m", c.getDouble(2));
                o.put("x", c.getDouble(3));
                trips.put(o);
            }
        }
        j.put("trips", trips);
        return j;
    }

    /** يدمج النسخة مع الموجود (يأخذ القيمة الأكبر لكل يوم، ولا يكرر الرحلات). يُرجع {أيام، رحلات}. */
    public synchronized int[] importJson(JSONObject j) throws JSONException {
        if (!"khatawati".equals(j.optString("app"))) throw new JSONException("not a khatawati backup");
        SQLiteDatabase db = getWritableDatabase();
        int nd = 0, nt = 0;
        db.beginTransaction();
        try {
            JSONArray days = j.optJSONArray("days");
            if (days != null) for (int i = 0; i < days.length(); i++) {
                JSONObject o = days.getJSONObject(i);
                String d = o.getString("d");
                ensure(db, d);
                db.execSQL("UPDATE days SET steps=MAX(steps,?), meters=MAX(meters,?), active_min=MAX(active_min,?), car_m=MAX(car_m,?) WHERE day=?",
                        new Object[]{o.optLong("s"), o.optDouble("m", 0), o.optInt("a"), o.optDouble("c", 0), d});
                nd++;
            }
            JSONArray trips = j.optJSONArray("trips");
            if (trips != null) for (int i = 0; i < trips.length(); i++) {
                JSONObject o = trips.getJSONObject(i);
                long s = o.getLong("s");
                try (Cursor c = db.rawQuery("SELECT 1 FROM trips WHERE start=?", new String[]{String.valueOf(s)})) {
                    if (c.moveToFirst()) continue;
                }
                ContentValues v = new ContentValues();
                v.put("start", s);
                v.put("end_at", o.optLong("e", s));
                v.put("meters", o.optDouble("m", 0));
                v.put("max_kmh", o.optDouble("x", 0));
                db.insert("trips", null, v);
                nt++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new int[]{nd, nt};
    }
}
