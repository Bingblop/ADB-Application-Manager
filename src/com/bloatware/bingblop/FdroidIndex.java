package com.bloatware.bingblop;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads an F-Droid-style repository's whole app catalog without holding it in memory.
 *
 * The big repositories (F-Droid itself, IzzyOnDroid, ...) publish an index-v2.json of tens of megabytes -
 * far too large to buffer as a String and parse as one JSONObject on a phone. This streams it through
 * {@link JsonReader} and keeps only what the Store tab shows: name, summary, icon, categories, and the one
 * version that suits this device. Repos too old for index-v2 are read from their signed index-v1.jar.
 *
 * Pure java + android.util.JsonReader + org.json, so it is unit-testable off-device (with the framework jar).
 */
public final class FdroidIndex {
    private FdroidIndex() {}

    /** Receives throttled progress while a catalog is being read. */
    public interface Sink {
        void onProgress(long bytesRead, int apps);
    }

    public static final class Result {
        public final JSONArray items = new JSONArray();
        public String repoName = "";
        public String format = "";   // v2 | v1
        public int skipped;          // apps with no version installable on this device
    }

    private static final long MAX_BYTES = 256L * 1024 * 1024;

    /** Progress bookkeeping shared by the byte counter and the parser. */
    static final class Progress {
        long bytes;
        int apps;
        long lastTick;
        final Sink sink;

        Progress(Sink sink) { this.sink = sink; }

        void tick(boolean force) {
            if (sink == null) return;
            long now = System.currentTimeMillis();
            if (force || now - lastTick > 400) {
                lastTick = now;
                sink.onProgress(bytes, apps);
            }
        }
    }

    /** Counts bytes as they stream by (progress) and refuses to read an absurdly large response. */
    private static final class CountingStream extends FilterInputStream {
        private final Progress prog;

        CountingStream(InputStream in, Progress prog) { super(in); this.prog = prog; }

        private void add(int n) throws IOException {
            if (n <= 0) return;
            prog.bytes += n;
            if (prog.bytes > MAX_BYTES) throw new IOException("this repository's catalog is implausibly large");
            prog.tick(false);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            add(n);
            return n;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ADB-Application-Manager");
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    /**
     * Streams {@code address}'s catalog into compact items. Tries index-v2.json first, then index-v1.jar.
     * {@code abis} are the device's supported ABIs (null = accept any) and {@code sdk} its API level (0 = any),
     * used to pick an installable version of each app.
     */
    public static Result load(String address, String[] abis, int sdk, Sink sink) throws Exception {
        String base = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        Progress prog = new Progress(sink);
        String v2Problem;
        HttpURLConnection c2 = open(base + "/index-v2.json");
        try {
            int code = c2.getResponseCode();
            if (code == 200) {
                InputStream in = new CountingStream(c2.getInputStream(), prog);
                try {
                    Result r = parseV2(in, base, abis, sdk, prog);
                    prog.tick(true);
                    return r;
                } finally { in.close(); }
            }
            v2Problem = "HTTP " + code;
        } finally { c2.disconnect(); }

        // Older repositories only publish the signed v1 jar (a zip holding index-v1.json)
        HttpURLConnection c1 = open(base + "/index-v1.jar");
        try {
            int code = c1.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("this repository has no browsable catalog (index-v2: " + v2Problem + ", index-v1: HTTP " + code + ")");
            }
            ZipInputStream z = new ZipInputStream(new CountingStream(c1.getInputStream(), prog));
            try {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if ("index-v1.json".equals(e.getName())) {
                        Result r = parseV1(z, base, abis, sdk, prog);
                        prog.tick(true);
                        return r;
                    }
                }
            } finally { z.close(); }
            throw new IllegalStateException("index-v1.jar has no index-v1.json");
        } finally { c1.disconnect(); }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared reading helpers
    // ---------------------------------------------------------------------------------------------

    /** One candidate version of an app. */
    private static final class Cand {
        String file = "", sha = "", name = "";
        long code, size;
    }

    private static JsonReader reader(InputStream in) throws Exception {
        JsonReader r = new JsonReader(new BufferedReader(new InputStreamReader(in, "UTF-8"), 65536));
        r.setLenient(true);
        return r;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String readString(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING) return r.nextString();
        r.skipValue();
        return "";
    }

    private static long readLong(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NUMBER) return r.nextLong();
        if (t == JsonToken.STRING) {
            try { return Long.parseLong(r.nextString().trim()); } catch (NumberFormatException e) { return 0L; }
        }
        r.skipValue();
        return 0L;
    }

    /** A localized string: either a plain string, or {locale: string}. Prefers en-US, then any en*, then the first. */
    private static String readLocalized(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING) return r.nextString();
        if (t != JsonToken.BEGIN_OBJECT) { r.skipValue(); return ""; }
        String enUS = null, en = null, first = null;
        r.beginObject();
        while (r.hasNext()) {
            String loc = r.nextName();
            if (r.peek() == JsonToken.STRING) {
                String v = r.nextString();
                if (first == null) first = v;
                if ("en-US".equals(loc)) enUS = v;
                else if (en == null && loc.startsWith("en")) en = v;
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return enUS != null ? enUS : (en != null ? en : (first != null ? first : ""));
    }

    /** An icon: {locale: {name, sha256, size}} (or a plain string); returns the chosen file name/path. */
    private static String readLocalizedIcon(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING) return r.nextString();
        if (t != JsonToken.BEGIN_OBJECT) { r.skipValue(); return ""; }
        String enUS = null, en = null, first = null;
        r.beginObject();
        while (r.hasNext()) {
            String loc = r.nextName();
            String name = null;
            if (r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if ("name".equals(k) && r.peek() == JsonToken.STRING) name = r.nextString(); else r.skipValue();
                }
                r.endObject();
            } else {
                r.skipValue();
            }
            if (name != null) {
                if (first == null) first = name;
                if ("en-US".equals(loc)) enUS = name;
                else if (en == null && loc.startsWith("en")) en = name;
            }
        }
        r.endObject();
        return enUS != null ? enUS : (en != null ? en : (first != null ? first : ""));
    }

    private static List<String> readStringArray(JsonReader r) throws IOException {
        List<String> out = new ArrayList<String>();
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return out; }
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() == JsonToken.STRING) out.add(r.nextString()); else r.skipValue();
        }
        r.endArray();
        return out;
    }

    /** True when a version suits the device: its native code (if any) overlaps the device ABIs, and its minSdk fits. */
    static boolean compatible(List<String> nativecode, long minSdk, String[] abis, int sdk) {
        if (sdk > 0 && minSdk > sdk) return false;
        if (nativecode == null || nativecode.isEmpty() || abis == null) return true;
        for (String n : nativecode) for (String a : abis) if (n.equals(a)) return true;
        return false;
    }

    private static String join(String base, String path) {
        if (path == null || path.isEmpty()) return "";
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private static JSONObject buildItem(String base, String pkg, String name, String summary, String icon,
                                        List<String> cats, long updated, Cand best, String iconDir) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", pkg);
        o.put("key", pkg);
        o.put("pkg", pkg);
        o.put("name", name == null || name.isEmpty() ? pkg : name);
        o.put("desc", trim(summary, 160));
        o.put("icon", icon == null || icon.isEmpty() ? "" : join(base, iconDir + icon));
        o.put("ver", best.name);
        o.put("vc", best.code);
        o.put("size", best.size);
        o.put("apkUrl", join(base, best.file));
        o.put("sha256", best.sha);
        JSONArray c = new JSONArray();
        if (cats == null || cats.isEmpty()) c.put("Uncategorized");
        else for (String s : cats) c.put(s);
        o.put("cats", c);
        o.put("updated", updated);
        o.put("source", "fdroid");
        o.put("resolveKind", "direct");
        return o;
    }

    // ---------------------------------------------------------------------------------------------
    // index-v2.json
    // ---------------------------------------------------------------------------------------------

    static Result parseV2(InputStream in, String base, String[] abis, int sdk, Progress prog) throws Exception {
        Result res = new Result();
        res.format = "v2";
        JsonReader r = reader(in);
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            if ("repo".equals(key) && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("name".equals(r.nextName())) res.repoName = readLocalized(r); else r.skipValue();
                }
                r.endObject();
            } else if ("packages".equals(key) && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    String pkg = r.nextName();
                    JSONObject it = readPackageV2(r, pkg, base, abis, sdk);
                    if (it != null) res.items.put(it); else res.skipped++;
                    if (prog != null) { prog.apps = res.items.length(); prog.tick(false); }
                }
                r.endObject();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return res;
    }

    private static JSONObject readPackageV2(JsonReader r, String pkg, String base, String[] abis, int sdk) throws Exception {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null; }
        String name = "", summary = "", icon = "";
        List<String> cats = new ArrayList<String>();
        long updated = 0;
        Cand best = null;
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("metadata".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    String m = r.nextName();
                    if ("name".equals(m)) name = readLocalized(r);
                    else if ("summary".equals(m)) summary = readLocalized(r);
                    else if ("icon".equals(m)) icon = readLocalizedIcon(r);
                    else if ("categories".equals(m)) cats = readStringArray(r);
                    else if ("lastUpdated".equals(m)) updated = readLong(r);
                    else r.skipValue();
                }
                r.endObject();
            } else if ("versions".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    r.nextName(); // the version's hash
                    Cand c = readVersionV2(r, abis, sdk);
                    if (c != null && (best == null || c.code > best.code)) best = c;
                }
                r.endObject();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (best == null) return null;
        return buildItem(base, pkg, name, summary, icon, cats, updated, best, "");
    }

    private static Cand readVersionV2(JsonReader r, String[] abis, int sdk) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null; }
        Cand c = new Cand();
        long minSdk = 0;
        List<String> nat = null;
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("file".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    String f = r.nextName();
                    if ("name".equals(f)) c.file = readString(r);
                    else if ("sha256".equals(f)) c.sha = readString(r);
                    else if ("size".equals(f)) c.size = readLong(r);
                    else r.skipValue();
                }
                r.endObject();
            } else if ("manifest".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    String m = r.nextName();
                    if ("versionName".equals(m)) c.name = readString(r);
                    else if ("versionCode".equals(m)) c.code = readLong(r);
                    else if ("nativecode".equals(m)) nat = readStringArray(r);
                    else if ("usesSdk".equals(m) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject();
                        while (r.hasNext()) {
                            if ("minSdkVersion".equals(r.nextName())) minSdk = readLong(r); else r.skipValue();
                        }
                        r.endObject();
                    } else r.skipValue();
                }
                r.endObject();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (c.file.isEmpty() || !c.file.toLowerCase(java.util.Locale.US).endsWith(".apk")) return null;
        if (!compatible(nat, minSdk, abis, sdk)) return null;
        return c;
    }

    // ---------------------------------------------------------------------------------------------
    // index-v1.json (inside index-v1.jar) - the older format: "apps" and "packages" are separate top-level keys
    // ---------------------------------------------------------------------------------------------

    private static final class MetaV1 {
        String pkg = "", name = "", summary = "", icon = "";
        List<String> cats = new ArrayList<String>();
        long updated;
    }

    static Result parseV1(InputStream in, String base, String[] abis, int sdk, Progress prog) throws Exception {
        Result res = new Result();
        res.format = "v1";
        Map<String, MetaV1> metas = new LinkedHashMap<String, MetaV1>();
        Map<String, Cand> best = new HashMap<String, Cand>();
        JsonReader r = reader(in);
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            if ("repo".equals(key) && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("name".equals(r.nextName())) res.repoName = readString(r); else r.skipValue();
                }
                r.endObject();
            } else if ("apps".equals(key) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    MetaV1 m = readAppV1(r);
                    if (m != null && !m.pkg.isEmpty()) metas.put(m.pkg, m);
                }
                r.endArray();
            } else if ("packages".equals(key) && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    String pkg = r.nextName();
                    Cand c = readVersionsV1(r, abis, sdk);
                    if (c != null) best.put(pkg, c);
                    if (prog != null) { prog.apps = best.size(); prog.tick(false); }
                }
                r.endObject();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        for (MetaV1 m : metas.values()) {
            Cand c = best.get(m.pkg);
            if (c == null) { res.skipped++; continue; }
            res.items.put(buildItem(base, m.pkg, m.name, m.summary, m.icon, m.cats, m.updated, c, "/icons/"));
        }
        return res;
    }

    private static MetaV1 readAppV1(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null; }
        MetaV1 m = new MetaV1();
        String locName = "", locSummary = "";
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("packageName".equals(k)) m.pkg = readString(r);
            else if ("name".equals(k)) m.name = readString(r);
            else if ("summary".equals(k)) m.summary = readString(r);
            else if ("icon".equals(k)) m.icon = readString(r);
            else if ("categories".equals(k)) m.cats = readStringArray(r);
            else if ("lastUpdated".equals(k)) m.updated = readLong(r);
            else if ("localized".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) {
                // {locale: {name, summary, ...}} - used when the app has no top-level name/summary
                String firstName = "", firstSum = "", enName = "", enSum = "";
                r.beginObject();
                while (r.hasNext()) {
                    String loc = r.nextName();
                    if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
                    String n = "", s = "";
                    r.beginObject();
                    while (r.hasNext()) {
                        String f = r.nextName();
                        if ("name".equals(f)) n = readString(r);
                        else if ("summary".equals(f)) s = readString(r);
                        else r.skipValue();
                    }
                    r.endObject();
                    if (firstName.isEmpty()) firstName = n;
                    if (firstSum.isEmpty()) firstSum = s;
                    if (loc.startsWith("en")) { if (enName.isEmpty()) enName = n; if (enSum.isEmpty()) enSum = s; }
                }
                r.endObject();
                locName = !enName.isEmpty() ? enName : firstName;
                locSummary = !enSum.isEmpty() ? enSum : firstSum;
            } else r.skipValue();
        }
        r.endObject();
        if (m.name.isEmpty()) m.name = locName;
        if (m.summary.isEmpty()) m.summary = locSummary;
        return m;
    }

    private static Cand readVersionsV1(JsonReader r, String[] abis, int sdk) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return null; }
        Cand best = null;
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
            Cand c = new Cand();
            long minSdk = 0;
            List<String> nat = null;
            String hash = "", hashType = "";
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if ("apkName".equals(k)) c.file = readString(r);
                else if ("versionName".equals(k)) c.name = readString(r);
                else if ("versionCode".equals(k)) c.code = readLong(r);
                else if ("size".equals(k)) c.size = readLong(r);
                else if ("hash".equals(k)) hash = readString(r);
                else if ("hashType".equals(k)) hashType = readString(r);
                else if ("minSdkVersion".equals(k)) minSdk = readLong(r);
                else if ("nativecode".equals(k)) nat = readStringArray(r);
                else r.skipValue();
            }
            r.endObject();
            if ("sha256".equalsIgnoreCase(hashType)) c.sha = hash;
            if (c.file.isEmpty() || !c.file.toLowerCase(java.util.Locale.US).endsWith(".apk")) continue;
            if (!compatible(nat, minSdk, abis, sdk)) continue;
            if (best == null || c.code > best.code) best = c;
        }
        r.endArray();
        return best;
    }
}
