package com.feras.khatawati;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * مكتبة أصوات الأذان: ملفات يضيفها المستخدم من هاتفه، تُنسخ داخل التطبيق (فلا تتعطل إن حُذف الأصل).
 * الاختيار: adhan_main لكل الصلوات، و adhan_fajr للفجر («same» = مثل الباقي). «» = نغمة المنبّه الافتراضية.
 */
final class AdhanLib {

    static final String SAME = "same";

    static class Sound {
        String id, name, file;
    }

    private AdhanLib() { }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "adhan");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static ArrayList<Sound> list(Context c) {
        ArrayList<Sound> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.p(c).getString("adhan_lib", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Sound s = new Sound();
                s.id = o.getString("id");
                s.name = o.optString("name", "أذان");
                s.file = o.getString("file");
                out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void save(Context c, ArrayList<Sound> l) {
        JSONArray a = new JSONArray();
        try {
            for (Sound s : l) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("file", s.file);
                a.put(o);
            }
        } catch (Exception ignored) { }
        Store.p(c).edit().putString("adhan_lib", a.toString()).apply();
    }

    static Sound byId(Context c, String id) {
        if (id == null || id.isEmpty() || SAME.equals(id)) return null;
        for (Sound s : list(c)) if (s.id.equals(id)) return s;
        return null;
    }

    static String displayName(Context c, Uri u) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Throwable ignored) { }
        return name == null ? "أذان" : name;
    }

    /** ينسخ الملف إلى داخل التطبيق ويضيفه للمكتبة. */
    static synchronized Sound add(Context c, Uri u) throws Exception {
        String dn = displayName(c, u);
        String ext = "";
        int dot = dn.lastIndexOf('.');
        if (dot > 0 && dn.length() - dot <= 6) ext = dn.substring(dot).toLowerCase();
        Sound s = new Sound();
        s.id = "a" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        s.name = dot > 0 ? dn.substring(0, dot) : dn;
        s.file = s.id + ext;
        File f = new File(dir(c), s.file);
        try (InputStream in = c.getContentResolver().openInputStream(u); FileOutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new Exception("تعذّر فتح الملف");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        if (f.length() == 0) { f.delete(); throw new Exception("الملف فارغ"); }
        ArrayList<Sound> l = list(c);
        l.add(s);
        save(c, l);
        return s;
    }

    static void rename(Context c, String id, String name) {
        ArrayList<Sound> l = list(c);
        for (Sound s : l) if (s.id.equals(id)) s.name = name;
        save(c, l);
    }

    static void delete(Context c, String id) {
        ArrayList<Sound> l = list(c);
        ArrayList<Sound> keep = new ArrayList<>();
        for (Sound s : l) {
            if (s.id.equals(id)) new File(dir(c), s.file).delete();
            else keep.add(s);
        }
        save(c, keep);
        SharedPreferences p = Store.p(c);
        SharedPreferences.Editor e = p.edit();
        if (id.equals(p.getString("adhan_main", ""))) e.putString("adhan_main", "");
        if (id.equals(p.getString("adhan_fajr", SAME))) e.putString("adhan_fajr", SAME);
        e.apply();
    }

    /** معرّف الصوت المختار لصلاة معيّنة. */
    static String selectedId(Context c, int prayer) {
        SharedPreferences p = Store.p(c);
        if (prayer == Prayer.FAJR) {
            String f = p.getString("adhan_fajr", SAME);
            if (!SAME.equals(f)) return f;
        }
        return p.getString("adhan_main", "");
    }

    static String label(Context c, String id) {
        if (SAME.equals(id)) return "نفس صوت الأذان";
        if (id == null || id.isEmpty()) return "نغمة المنبّه الافتراضية";
        Sound s = byId(c, id);
        return s == null ? "نغمة المنبّه الافتراضية" : s.name;
    }

    /** ملف الصوت، أو null للنغمة الافتراضية. */
    static Uri uri(Context c, String id) {
        Sound s = byId(c, id);
        if (s == null) return null;
        File f = new File(dir(c), s.file);
        return f.exists() ? Uri.fromFile(f) : null;
    }

    /** من الإصدار 1.0: الملف المختار سابقاً يُنقل إلى المكتبة. */
    static void migrate(Context c) {
        SharedPreferences p = Store.p(c);
        String old = p.getString("adhan_uri", "");
        if (old.isEmpty()) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Sound s = add(app, Uri.parse(old));
                String nm = p.getString("adhan_name", "");
                if (!nm.isEmpty()) rename(app, s.id, nm.replaceFirst("\\.[A-Za-z0-9]{1,5}$", ""));
                if (p.getString("adhan_main", "").isEmpty()) p.edit().putString("adhan_main", s.id).apply();
            } catch (Throwable ignored) { }
            p.edit().remove("adhan_uri").remove("adhan_name").apply();
        }).start();
    }
}
