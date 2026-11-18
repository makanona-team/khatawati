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
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * مكتبة أصوات الأذان: ملفات يضيفها المستخدم من هاتفه، تُنسخ داخل التطبيق (فلا تتعطل إن حُذف الأصل).
 * الاختيار: adhan_main لكل الصلوات، و adhan_fajr للفجر («same» = مثل الباقي). «» = نغمة المنبّه الافتراضية.
 */
final class AdhanLib {

    static final String SAME = "same";

    static class Sound {
        String id, name, file;
        /** رابط المصدر إن نُزّل من الإنترنت. */
        String src = "";
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
                s.src = o.optString("src", "");
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
                o.put("src", s.src);
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

    /** رابط قائمة الأصوات الجاهزة (يعدّلها صاحب المستودع). */
    static final String CATALOG = "https://raw.githubusercontent.com/" + Updater.REPO + "/main/adhan.json";
    static final String CATALOG_EDIT = "https://github.com/" + Updater.REPO + "/edit/main/adhan.json";
    static final String UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    static {
        try {
            if (CookieHandler.getDefault() == null) CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        } catch (Throwable ignored) { }
    }

    static class Item {
        String name, url, note = "";
        /** معرّف مجموعة على archive.org إن كان العنصر مجموعة. */
        String archive;
    }

    static ArrayList<Item> catalog() throws Exception {
        java.net.HttpURLConnection con = Updater.open(CATALOG + "?t=" + System.currentTimeMillis() / 60000);
        int code = con.getResponseCode();
        if (code == 404) throw new Exception("ملف adhan.json غير موجود في المستودع");
        if (code != 200) throw new Exception("HTTP " + code);
        String body;
        try (InputStream in = con.getInputStream()) { body = Updater.readAll(in); }
        JSONObject j = new JSONObject(body);
        JSONArray a = j.optJSONArray("sounds");
        ArrayList<Item> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Item it = new Item();
            it.name = o.optString("name", "").trim();
            it.url = o.optString("url", "").trim();
            it.note = o.optString("note", "").trim();
            String arch = o.optString("archive", "").trim();
            if (!arch.isEmpty()) {
                it.archive = arch;
                it.url = "https://archive.org/details/" + arch;
            } else {
                it.archive = archiveId(it.url);
            }
            if (!it.url.startsWith("http")) continue;
            if (it.name.isEmpty()) it.name = it.archive != null ? it.archive : nameFromUrl(it.url);
            out.add(it);
        }
        return out;
    }

    static boolean hasSrc(Context c, String url) {
        for (Sound s : list(c)) if (url.equals(s.src)) return true;
        return false;
    }

    static String nameFromUrl(String url) {
        String path = Uri.parse(url).getLastPathSegment();
        if (path == null || path.isEmpty() || path.matches("\\d+")) return "أذان";
        int dot = path.lastIndexOf('.');
        return (dot > 0 ? path.substring(0, dot) : path).replace('_', ' ').replace('-', ' ').trim();
    }

    // ───────── archive.org ─────────

    /** معرّف مجموعة archive.org من رابط details أو download (بدون ملف محدد). */
    static String archiveId(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("archive\\.org/(?:details|download)/([^/?#]+)/?(?:[?#].*)?$").matcher(url.trim());
        return m.find() ? Uri.decode(m.group(1)) : null;
    }

    static String encodePath(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("/")) {
            if (sb.length() > 0) sb.append('/');
            try { sb.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20")); } catch (Exception e) { sb.append(part); }
        }
        return sb.toString();
    }

    /** كل الملفات الصوتية في مجموعة archive.org (mp3 أولاً، وإلا ogg/m4a). */
    static ArrayList<Item> archiveFiles(String id) throws Exception {
        HttpURLConnection con = connect("https://archive.org/metadata/" + encodePath(id), null);
        int code = con.getResponseCode();
        if (code != 200) throw new Exception("archive.org: HTTP " + code);
        String body;
        try (InputStream in = con.getInputStream()) { body = Updater.readAll(in); }
        JSONObject j = new JSONObject(body);
        JSONArray files = j.optJSONArray("files");
        if (files == null || files.length() == 0) throw new Exception("المجموعة غير موجودة أو فارغة");
        ArrayList<Item> mp3 = new ArrayList<>(), other = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            String n = f.optString("name", "");
            String low = n.toLowerCase();
            boolean isMp3 = low.endsWith(".mp3");
            if (!isMp3 && !low.endsWith(".ogg") && !low.endsWith(".m4a") && !low.endsWith(".opus")) continue;
            Item it = new Item();
            String base = n.contains("/") ? n.substring(n.lastIndexOf('/') + 1) : n;
            int dot = base.lastIndexOf('.');
            String title = f.optString("title", "").trim();
            it.name = !title.isEmpty() ? title : (dot > 0 ? base.substring(0, dot) : base).replace('_', ' ').trim();
            if (it.name.replaceAll("[-\\s\\d]", "").isEmpty()) it.name = "أذان " + it.name.replaceAll("[^\\d]", "");
            it.url = "https://archive.org/download/" + encodePath(id) + "/" + encodePath(n);
            long size = f.optLong("size", 0);
            if (size > 0) it.note = String.format(java.util.Locale.US, "%.1f MB", size / 1048576.0);
            (isMp3 ? mp3 : other).add(it);
        }
        ArrayList<Item> out = mp3.isEmpty() ? other : mp3;
        out.sort((a, b) -> a.url.compareTo(b.url));
        if (out.isEmpty()) throw new Exception("لا توجد ملفات صوتية في هذه المجموعة");
        return out;
    }

    // ───────── الاتصال وفك الروابط ─────────

    /** يحوّل الأحرف العربية والمسافات في الرابط إلى صيغة مقبولة. */
    static String safe(String u) {
        try {
            URL x = new URL(u);
            return new URI(x.getProtocol(), x.getUserInfo(), x.getHost(), x.getPort(), Uri.decode(x.getPath()),
                    x.getQuery() == null ? null : Uri.decode(x.getQuery()), x.getRef()).toASCIIString();
        } catch (Throwable e) {
            return u.replace(" ", "%20");
        }
    }

    /** اتصال كالمتصفح مع تتبّع التحويلات (حتى بين http و https). */
    static HttpURLConnection connect(String url, String referer) throws Exception {
        String u = safe(url);
        for (int i = 0; i < 8; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "ar,en;q=0.8");
            if (referer != null) c.setRequestProperty("Referer", referer);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("تحويل بلا عنوان");
                referer = u;
                u = safe(new URL(new URL(u), loc).toString());
                continue;
            }
            return c;
        }
        throw new Exception("تحويلات كثيرة من الموقع");
    }

    static boolean audioName(String u) {
        String p = Uri.parse(u).getPath();
        if (p == null) return false;
        p = p.toLowerCase();
        return p.endsWith(".mp3") || p.endsWith(".m4a") || p.endsWith(".ogg") || p.endsWith(".aac") || p.endsWith(".wav") || p.endsWith(".opus");
    }

    static boolean isAudio(HttpURLConnection c) {
        String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
        String cd = c.getHeaderField("Content-Disposition");
        return type.startsWith("audio/") || type.contains("octet-stream") || type.contains("mpeg") || type.contains("ogg")
                || (cd != null && cd.toLowerCase().contains("attachment")) || (!type.contains("html") && audioName(c.getURL().toString()));
    }

    static String readLimited(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0 && bo.size() < max) bo.write(b, 0, n);
        return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** يبحث في صفحة HTML عن رابط ملف صوتي (أو تحويل إلى صفحة أخرى). */
    static String findLink(String html, String base) {
        String b = html.replace("\\/", "/").replace("&amp;", "&");
        Pattern[] ps = {
                Pattern.compile("(https?:)?//[^\\s\"'<>()]+?\\.(mp3|m4a|ogg|aac|wav|opus)(\\?[^\\s\"'<>()]*)?", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?:href|src|file|url|data-[a-z-]+)\\s*[=:]\\s*[\"']([^\"']+\\.(?:mp3|m4a|ogg|aac|wav|opus)(?:\\?[^\"']*)?)[\"']", Pattern.CASE_INSENSITIVE),
                Pattern.compile("http-equiv=[\"']?refresh[\"']?[^>]*url=([^\"'>\\s]+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?:window\\.)?location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
        };
        for (int k = 0; k < ps.length; k++) {
            Matcher m = ps[k].matcher(b);
            while (m.find()) {
                String cand = k == 0 ? m.group() : m.group(1);
                if (cand.startsWith("//")) cand = "https:" + cand;
                try {
                    String abs = new URL(new URL(base), cand).toString();
                    if (abs.equals(base)) continue;
                    return abs;
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /** {رابط الملف الصوتي المباشر، الصفحة المُحيلة} من أي رابط: مباشر، صفحة تحميل، تحويلات، أو مجموعة archive.org. */
    static String[] resolve(String url) throws Exception {
        String u = url.trim();
        if (u.contains("dropbox.com")) u = u.replace("dl=0", "dl=1");
        String ref = null;
        for (int depth = 0; depth < 4; depth++) {
            String arch = archiveId(u);
            if (arch != null) return new String[]{archiveFiles(arch).get(0).url, null};
            HttpURLConnection c = connect(u, ref);
            int code = c.getResponseCode();
            if (code == 403) throw new Exception("الموقع رفض الطلب (403)");
            if (code == 404) throw new Exception("الملف غير موجود على الموقع (404)");
            if (code != 200 && code != 206) throw new Exception("الموقع أعاد خطأ HTTP " + code);
            String fin = c.getURL().toString();
            if (isAudio(c)) { c.disconnect(); return new String[]{fin, ref}; }
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
            if (!type.contains("html") && !type.contains("text")) { c.disconnect(); return new String[]{fin, ref}; }
            String body;
            try (InputStream in = c.getInputStream()) { body = readLimited(in, 3 * 1024 * 1024); }
            String next = findLink(body, fin);
            if (next == null) throw new Exception("الرابط يفتح صفحة لا تحتوي ملفاً صوتياً مباشراً");
            ref = fin;
            u = next;
        }
        throw new Exception("تعذّر الوصول إلى الملف الصوتي من هذا الرابط");
    }

    /** رسالة خطأ مفهومة. */
    static String why(Throwable e) {
        if (e instanceof java.net.UnknownHostException) return "لا يوجد اتصال بالإنترنت، أو الموقع محجوب في شبكتك";
        if (e instanceof java.net.SocketTimeoutException) return "انتهت مهلة الاتصال بالموقع؛ حاول مرة أخرى";
        if (e instanceof javax.net.ssl.SSLException) return "مشكلة في الاتصال الآمن بالموقع";
        if (e instanceof java.net.ConnectException) return "تعذّر الاتصال بالموقع";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** ينزّل ملف أذان من أي رابط ويضيفه للمكتبة. */
    static Sound addFromUrl(Context c, String url, String name, Updater.Progress pr) throws Exception {
        String[] r = resolve(url);
        HttpURLConnection con = connect(r[0], r[1]);
        int code = con.getResponseCode();
        if (code != 200 && code != 206) throw new Exception("الموقع أعاد خطأ HTTP " + code);
        String type = con.getContentType() == null ? "" : con.getContentType().toLowerCase();
        if (type.startsWith("text/html")) throw new Exception("الرابط يفتح صفحة وليس ملفاً صوتياً مباشراً");
        String last = Uri.parse(con.getURL().toString()).getLastPathSegment();
        String ext = ".mp3";
        if (last != null) {
            int dot = last.lastIndexOf('.');
            if (dot > 0 && last.length() - dot <= 6) ext = last.substring(dot).toLowerCase();
        }
        if (type.contains("ogg")) ext = ".ogg";
        else if (type.contains("mp4") || type.contains("m4a") || type.contains("aac")) ext = ".m4a";
        else if (type.contains("wav")) ext = ".wav";
        Sound s = new Sound();
        s.id = "a" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        s.name = name == null || name.trim().isEmpty() ? nameFromUrl(r[0]) : name.trim();
        s.file = s.id + ext;
        s.src = url.trim();
        File f = new File(dir(c), s.file);
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[65536];
            long done = 0;
            int n, lastPct = -1;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                done += n;
                if (total > 0 && pr != null) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) { lastPct = pct; pr.on(pct); }
                }
            }
        } catch (Exception e) {
            f.delete();
            throw e;
        }
        if (f.length() < 1024) { f.delete(); throw new Exception("الملف صغير جداً أو ليس ملفاً صوتياً"); }
        android.media.MediaMetadataRetriever mr = new android.media.MediaMetadataRetriever();
        try {
            mr.setDataSource(f.getAbsolutePath());
            if (mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == null
                    && mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) == null) throw new Exception();
        } catch (Exception e) {
            f.delete();
            throw new Exception("الملف المنزّل ليس ملفاً صوتياً صالحاً");
        } finally {
            try { mr.release(); } catch (Exception ignored) { }
        }
        synchronized (AdhanLib.class) {
            ArrayList<Sound> l = list(c);
            l.add(s);
            save(c, l);
        }
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
