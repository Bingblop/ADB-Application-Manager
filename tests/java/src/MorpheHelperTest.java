package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Morphe Helper (MorpheHelper): the ten sources and their flags, the version rules, every resolver against a local server with page
 * fixtures (the live pages' structure and Helper for Morphe's own test pages): version lists, resolve requested / latest, a missing package, a
 * changed page format, ABI and format choice, a Cloudflare challenge; Fast Mode; the download (checksums, the real manifest, atomic file,
 * cancel); and what a package file says about itself (an APK, an .apks, .xapk, .apkm built here, and the repo's own APK when there is one).
 * Loopback servers and files only: nothing here touches the internet.
 */
public class MorpheHelperTest {
    static int fails = 0, passes = 0;

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passes++;
            System.out.println("ok   " + name);
        } else {
            fails++;
            System.out.println("FAIL " + name + (detail.isEmpty() ? "" : "  <- " + detail));
        }
    }

    static final String FIX = System.getProperty("fixtures", "fixtures") + "/morphehelper/";
    static final Map<String, byte[]> FILES = new ConcurrentHashMap<String, byte[]>();

    // ------------------------------------------------------------------------------------------------------------------------------
    // A small local site: routes by "METHOD /path?query" (or "METHOD /path"), a hit log, files under /files/
    // ------------------------------------------------------------------------------------------------------------------------------

    interface Dyn {
        Resp apply(HttpExchange ex, Mock m) throws IOException;
    }

    static final class Resp {
        int status = 200;
        String type = "text/html; charset=utf-8";
        byte[] body = new byte[0];
        String location;
        Dyn dyn;
        CountDownLatch hold;       // the body after holdAfter bytes waits for this
        int holdAfter;
    }

    static final class Mock {
        final HttpServer srv;
        final String base, host;
        final Map<String, Resp> routes = new ConcurrentHashMap<String, Resp>();
        final List<String> hits = new CopyOnWriteArrayList<String>();
        final List<Map<String, String>> hitHeaders = new CopyOnWriteArrayList<Map<String, String>>();
        final List<String> bodies = new CopyOnWriteArrayList<String>();

        Mock() throws IOException {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.setExecutor(Executors.newCachedThreadPool());
            host = "127.0.0.1:" + srv.getAddress().getPort();
            base = "http://" + host;
            srv.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    serve(ex);
                }
            });
            srv.start();
        }

        void serve(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getRawPath();
                String q = ex.getRequestURI().getRawQuery();
                String method = ex.getRequestMethod();
                String full = method + " " + path + (q == null ? "" : "?" + q);
                hits.add(full);
                Map<String, String> h = new HashMap<String, String>();
                for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) if (e.getKey() != null && !e.getValue().isEmpty()) h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
                hitHeaders.add(h);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                InputStream in = ex.getRequestBody();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                bodies.add(new String(bo.toByteArray(), StandardCharsets.UTF_8));
                Resp r = routes.get(full);
                if (r == null) r = routes.get(method + " " + path);
                if (r != null && r.dyn != null) r = r.dyn.apply(ex, this);
                if (r == null && path.startsWith("/files/") && FILES.containsKey(path.substring(7))) {
                    r = new Resp();
                    r.type = "application/octet-stream";
                    r.body = FILES.get(path.substring(7));
                }
                if (r == null) {
                    r = new Resp();
                    r.status = 404;
                    r.body = "not found".getBytes(StandardCharsets.UTF_8);
                }
                ex.getResponseHeaders().set("Content-Type", r.type);
                if (r.location != null) ex.getResponseHeaders().set("Location", r.location);
                if (r.hold != null) {
                    ex.sendResponseHeaders(r.status, r.body.length);
                    OutputStream o = ex.getResponseBody();
                    o.write(r.body, 0, r.holdAfter);
                    o.flush();
                    try {
                        r.hold.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        o.write(r.body, r.holdAfter, r.body.length - r.holdAfter);
                        o.close();
                    } catch (IOException ignored) {
                    }
                } else {
                    ex.sendResponseHeaders(r.status, r.body.length == 0 ? -1 : r.body.length);
                    if (r.body.length > 0) {
                        OutputStream o = ex.getResponseBody();
                        o.write(r.body);
                        o.close();
                    }
                }
            } finally {
                ex.close();
            }
        }

        String render(String s) {
            String out = s.replace("{{BASE}}", base).replace("{{HOST}}", host);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{(SHA|MD5|SIZE)_([^}]+)\\}\\}").matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                byte[] data = FILES.get(m.group(2));
                String v = data == null ? "MISSING-" + m.group(2) : m.group(1).equals("SIZE") ? String.valueOf(data.length) : digest(m.group(1).equals("SHA") ? "SHA-256" : "MD5", data);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        Resp on(String key, int status, String type, String body) {
            Resp r = new Resp();
            r.status = status;
            r.type = type;
            r.body = body.getBytes(StandardCharsets.UTF_8);
            routes.put(key, r);
            return r;
        }

        void page(String key, String fixture) throws IOException {
            on(key, 200, "text/html; charset=utf-8", render(fx(fixture)));
        }

        void json(String key, String fixture) throws IOException {
            on(key, 200, "application/json", render(fx(fixture)));
        }

        void text(String key, int status, String body) {
            on(key, status, "text/plain; charset=utf-8", body);
        }

        void redirect(String key, String to) {
            Resp r = new Resp();
            r.status = 302;
            r.location = to;
            routes.put(key, r);
        }

        void dyn(String key, Dyn d) {
            Resp r = new Resp();
            r.dyn = d;
            routes.put(key, r);
        }

        void reset() {
            routes.clear();
            hits.clear();
            hitHeaders.clear();
            bodies.clear();
        }

        boolean hit(String key) {
            return hits.contains(key);
        }

        int count(String prefix) {
            int n = 0;
            for (String h : hits) if (h.startsWith(prefix)) n++;
            return n;
        }
    }

    static String fx(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get(FIX + name)), StandardCharsets.UTF_8);
    }

    static String digest(String algo, byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance(algo).digest(data);
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Package files built here: a compiled manifest (binary XML) in a zip
    // ------------------------------------------------------------------------------------------------------------------------------

    static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    static void le32(ByteArrayOutputStream o, int v) {
        le16(o, v & 0xffff);
        le16(o, (v >>> 16) & 0xffff);
    }

    static int idx(List<String> pool, String s) {
        int i = pool.indexOf(s);
        if (i >= 0) return i;
        pool.add(s);
        return pool.size() - 1;
    }

    /** A compiled AndroidManifest.xml: package, versionCode (and Major), versionName (or a resource reference), split. */
    static byte[] axml(String pkg, long code, String vname, String split, long major, boolean nameIsRef) {
        final String NS = "http://schemas.android.com/apk/res/android";
        List<String> pool = new ArrayList<String>();
        int sAndroid = idx(pool, "android"), sNs = idx(pool, NS), sManifest = idx(pool, "manifest");
        int sCode = idx(pool, "versionCode"), sName = idx(pool, "versionName"), sPkg = idx(pool, "package");
        int sMajor = major > 0 ? idx(pool, "versionCodeMajor") : -1;
        int sSplit = split != null ? idx(pool, "split") : -1;
        int vPkg = idx(pool, pkg);
        int vName = nameIsRef ? -1 : idx(pool, vname);
        int vSplit = split != null ? idx(pool, split) : -1;
        List<int[]> attrs = new ArrayList<int[]>();
        attrs.add(new int[] {sNs, sCode, -1, 0x10, (int) code});
        if (major > 0) attrs.add(new int[] {sNs, sMajor, -1, 0x10, (int) major});
        attrs.add(nameIsRef ? new int[] {sNs, sName, -1, 0x01, 0x7f0b0001} : new int[] {sNs, sName, vName, 0x03, vName});
        attrs.add(new int[] {-1, sPkg, vPkg, 0x03, vPkg});
        if (split != null) attrs.add(new int[] {-1, sSplit, vSplit, 0x03, vSplit});

        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offsets = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            offsets[i] = strings.size();
            String s = pool.get(i);
            le16(strings, s.length());
            byte[] b = s.getBytes(StandardCharsets.UTF_16LE);
            strings.write(b, 0, b.length);
            le16(strings, 0);
        }
        while (strings.size() % 4 != 0) strings.write(0);
        ByteArrayOutputStream sp = new ByteArrayOutputStream();
        int headerSize = 28;
        int chunkSize = headerSize + 4 * pool.size() + strings.size();
        le16(sp, 0x0001);
        le16(sp, headerSize);
        le32(sp, chunkSize);
        le32(sp, pool.size());
        le32(sp, 0);
        le32(sp, 0);
        le32(sp, headerSize + 4 * pool.size());
        le32(sp, 0);
        for (int o : offsets) le32(sp, o);
        byte[] sb = strings.toByteArray();
        sp.write(sb, 0, sb.length);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] spb = sp.toByteArray();
        body.write(spb, 0, spb.length);
        // start namespace
        le16(body, 0x0100); le16(body, 16); le32(body, 24); le32(body, 1); le32(body, -1); le32(body, sAndroid); le32(body, sNs);
        // start element
        le16(body, 0x0102); le16(body, 16); le32(body, 36 + 20 * attrs.size()); le32(body, 1); le32(body, -1);
        le32(body, -1); le32(body, sManifest); le16(body, 20); le16(body, 20); le16(body, attrs.size()); le16(body, 0); le16(body, 0); le16(body, 0);
        for (int[] a : attrs) {
            le32(body, a[0]); le32(body, a[1]); le32(body, a[2]);
            le16(body, 8); body.write(0); body.write(a[3]); le32(body, a[4]);
        }
        // end element, end namespace
        le16(body, 0x0103); le16(body, 16); le32(body, 24); le32(body, 1); le32(body, -1); le32(body, -1); le32(body, sManifest);
        le16(body, 0x0101); le16(body, 16); le32(body, 24); le32(body, 1); le32(body, -1); le32(body, sAndroid); le32(body, sNs);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        le16(all, 0x0003);
        le16(all, 8);
        le32(all, 8 + b.length);
        all.write(b, 0, b.length);
        return all.toByteArray();
    }

    static void put(ZipOutputStream z, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (stored) {
            CRC32 c = new CRC32();
            c.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(c.getValue());
        }
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    static byte[] filler(int n, int seed) {
        byte[] b = new byte[n];
        long x = seed * 2654435761L + 1;
        for (int i = 0; i < n; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            b[i] = (byte) (x >>> 56);
        }
        return b;
    }

    static byte[] apk(String pkg, long code, String name) {
        return apk(pkg, code, name, null, 0, false, "arm64-v8a");
    }

    static byte[] apk(String pkg, long code, String name, String split, long major, boolean ref, String lib) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ZipOutputStream z = new ZipOutputStream(bo);
            put(z, "AndroidManifest.xml", axml(pkg, code, name, split, major, ref), false);
            put(z, "classes.dex", filler(1500, (int) code), false);
            if (lib != null) put(z, "lib/" + lib + "/libmorphe.so", filler(300, 7), true);
            z.close();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] bundle(String kind, String pkg, long code, String name) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ZipOutputStream z = new ZipOutputStream(bo);
            String baseName = kind.equals("xapk") ? pkg + ".apk" : "base.apk";
            if (kind.equals("xapk")) {
                String json = "{\"xapk_version\":2,\"package_name\":\"" + pkg + "\",\"version_code\":\"" + code + "\",\"version_name\":\"" + name + "\",\"split_apks\":[{\"file\":\"" + baseName + "\",\"id\":\"base\"},{\"file\":\"config.arm64_v8a.apk\",\"id\":\"config.arm64_v8a\"},{\"file\":\"config.xxhdpi.apk\",\"id\":\"config.xxhdpi\"}]}";
                put(z, "manifest.json", json.getBytes(StandardCharsets.UTF_8), false);
                put(z, "icon.png", filler(100, 3), false);
            } else if (kind.equals("apkm")) {
                String json = "{\"apkm_version\":5,\"pname\":\"" + pkg + "\",\"versioncode\":\"" + code + "\",\"release_version\":\"" + name + "\"}";
                put(z, "info.json", json.getBytes(StandardCharsets.UTF_8), false);
            } else {
                put(z, "toc.pb", filler(40, 9), false);
            }
            put(z, baseName, apk(pkg, code, name, null, 0, false, null), false);
            put(z, "split_config.arm64_v8a.apk", apk(pkg, code, name, "config.arm64_v8a", 0, false, null), false);
            put(z, "split_config.xxhdpi.apk", apk(pkg, code, name, "config.xxhdpi", 0, false, null), false);
            z.close();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Helpers for the checks
    // ------------------------------------------------------------------------------------------------------------------------------

    interface Thrower<T> {
        T run() throws Exception;
    }

    static Throwable err(Thrower<?> t) {
        try {
            t.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    static String msg(Throwable t) {
        return t == null ? "(no exception)" : t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    static boolean has(Throwable t, String... parts) {
        if (t == null || t.getMessage() == null) return false;
        for (String p : parts) if (!t.getMessage().contains(p)) return false;
        return true;
    }

    static JSONArray versionsOf(JSONObject o) throws Exception {
        return o.getJSONArray("versions");
    }

    static List<String> versionNames(JSONObject o) throws Exception {
        List<String> l = new ArrayList<String>();
        JSONArray a = versionsOf(o);
        for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i).getString("version"));
        return l;
    }

    static JSONObject byVersion(JSONObject o, String version, String format) throws Exception {
        JSONArray a = versionsOf(o);
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            if (x.getString("version").equals(version) && (format == null || x.getString("format").equals(format))) return x;
        }
        return null;
    }

    static File tmpDir(String name) throws IOException {
        File d = Files.createTempDirectory("morphehelper-" + name).toFile();
        return d;
    }

    static String[] names(File d) {
        String[] n = d.list();
        if (n == null) return new String[0];
        Arrays.sort(n);
        return n;
    }

    static Mock apt, pure, combo, utd, mirror, cube;
    static final String PKG = "com.example.app";

    static void resetAll() {
        for (Mock m : new Mock[] {apt, pure, combo, utd, mirror, cube}) m.reset();
    }

    public static void main(String[] args) throws Exception {
        FILES.put("example-1.0.3.apk", apk(PKG, 103, "1.0.3"));
        FILES.put("example-1.0.2.apk", apk(PKG, 102, "1.0.2"));
        FILES.put("example-1.0.2-x86.apk", apk(PKG, 102, "1.0.2", null, 0, false, "x86"));
        FILES.put("example-1.0.1.apk", apk(PKG, 101, "1.0.1"));
        FILES.put("example-secondary.apk", apk(PKG, 104, "1.0.4"));
        FILES.put("example-1.0.3-wrongver.apk", apk(PKG, 103, "9.9.9"));
        FILES.put("example-1.0.3-wrongcode.apk", apk(PKG, 999, "1.0.3"));
        FILES.put("other.apk", apk("com.other.app", 5, "5.0"));
        FILES.put("example.xapk", bundle("xapk", PKG, 103, "1.0.3"));
        FILES.put("example.apks", bundle("apks", PKG, 103, "1.0.3"));
        FILES.put("example.apkm", bundle("apkm", PKG, 103, "1.0.3"));
        FILES.put("page.html", "<html><body>Sorry, this is a web page, not an app.</body></html>".getBytes(StandardCharsets.UTF_8));
        apt = new Mock();
        pure = new Mock();
        combo = new Mock();
        utd = new Mock();
        mirror = new Mock();
        cube = new Mock();
        MorpheHelper.baseOverride.put("aptoide", apt.base);
        MorpheHelper.baseOverride.put("apkpure", pure.base);
        MorpheHelper.baseOverride.put("apkcombo", combo.base);
        MorpheHelper.baseOverride.put("uptodown", utd.base);
        MorpheHelper.baseOverride.put("apkmirror", mirror.base);
        MorpheHelper.baseOverride.put("evozi", cube.base);
        try {
            testSources();
            testText();
            testVersionRules();
            testAptoide();
            testApkPure();
            testApkCombo();
            testUptodown();
            testApkMirror();
            testEvozi();
            testManualOnly();
            testFast();
            testInspect();
            testScanFolder();
            testDownload();
        } catch (Throwable t) {
            fails++;
            System.out.println("FAIL the test run itself broke: " + t);
            t.printStackTrace(System.out);
        }
        for (Mock m : new Mock[] {apt, pure, combo, utd, mirror, cube}) m.srv.stop(0);
        System.out.println((fails == 0 ? "ALL PASS" : "FAILURES: " + fails) + " (" + passes + " ok, " + fails + " failed)");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // The registry
    // ------------------------------------------------------------------------------------------------------------------------------

    static void testSources() throws Exception {
        JSONArray s = MorpheHelper.sources();
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < s.length(); i++) ids.add(s.getJSONObject(i).getString("id"));
        check("sources: the ten of Helper for Morphe in its order", ids.equals(Arrays.asList("apkmirror", "uptodown", "apkpure", "apkcombo", "aptoide", "evozi", "mi9", "apkdownloader", "aurora", "play")), String.valueOf(ids));
        boolean fields = true;
        for (int i = 0; i < s.length(); i++) {
            JSONObject o = s.getJSONObject(i);
            fields &= o.getString("name").length() > 0 && o.getString("home").startsWith("https://") && o.getBoolean("manual")
                    && o.has("recommended") && o.has("latest") && o.has("history") && o.has("direct") && o.has("note");
        }
        check("sources: every one has name, https home, manual:true, the four flags and a note", fields);
        Map<String, JSONObject> by = new HashMap<String, JSONObject>();
        for (int i = 0; i < s.length(); i++) by.put(s.getJSONObject(i).getString("id"), s.getJSONObject(i));
        check("sources: Aptoide and APKCombo download without a browser", by.get("aptoide").getBoolean("direct") && by.get("apkcombo").getBoolean("direct"));
        check("sources: APKPure downloads (its API) but lists no history; APKMirror is tried directly", by.get("apkpure").getBoolean("direct") && !by.get("apkpure").getBoolean("history") && by.get("apkmirror").getBoolean("direct"));
        check("sources: Uptodown and Evozi list versions but say why they need the browser",
                !by.get("uptodown").getBoolean("direct") && by.get("uptodown").getBoolean("history") && by.get("uptodown").getString("note").contains("captcha")
                        && !by.get("evozi").getBoolean("direct") && by.get("evozi").getBoolean("latest") && by.get("evozi").getString("note").contains("Turnstile"));
        boolean manual = true;
        for (String id : new String[] {"mi9", "apkdownloader", "aurora", "play"}) {
            JSONObject o = by.get(id);
            manual &= !o.getBoolean("direct") && !o.getBoolean("recommended") && !o.getBoolean("latest") && !o.getBoolean("history") && o.getString("note").length() > 10;
        }
        check("sources: Mi9, APK Downloader, Aurora and Play are manual only, each with a note", manual);
        check("sources: the Cloudflare ones say Cloudflare", by.get("mi9").getString("note").contains("Cloudflare") && by.get("apkdownloader").getString("note").contains("Cloudflare"));

        JSONObject d = MorpheHelper.settingsDefaults();
        check("settingsDefaults: all ten sources enabled, default source apkmirror", d.getJSONArray("enabledSources").length() == 10 && "apkmirror".equals(d.getString("defaultSource")));
        check("settingsDefaults: connection both, save to cache, auto-clear on, http log on (Helper's defaults)",
                "both".equals(d.getString("connection")) && "cache".equals(d.getString("saveTo")) && d.getBoolean("autoClear") && d.getBoolean("logHttp"));
        check("settingsDefaults: fast off with the requested policy", !d.getJSONObject("fast").getBoolean("enabled") && "requested".equals(d.getJSONObject("fast").getString("policy")));
        check("settingsDefaults: VirusTotal off, no key, ask mode", !d.getJSONObject("vt").getBoolean("enabled") && "".equals(d.getJSONObject("vt").getString("apiKey")) && "ask".equals(d.getJSONObject("vt").getString("mode")));

        check("manualUrl: APKMirror search", "https://www.apkmirror.com/?post_type=app_release&searchtype=app&s=com.example.app".equals(MorpheHelper.manualUrl("apkmirror", PKG, "1.0.3")));
        check("manualUrl: Uptodown search", "https://en.uptodown.com/android/search?query=com.example.app".equals(MorpheHelper.manualUrl("uptodown", PKG, null)));
        check("manualUrl: APKPure info page", "https://apkpure.com/apk-info/com.example.app".equals(MorpheHelper.manualUrl("apkpure", PKG, null)));
        check("manualUrl: APKCombo search, and the version page when a version is given",
                "https://apkcombo.com/search/com.example.app".equals(MorpheHelper.manualUrl("apkcombo", PKG, null))
                        && "https://apkcombo.com/search/com.example.app/download/phone-1.0.3-apk".equals(MorpheHelper.manualUrl("apkcombo", PKG, "1.0.3 (103)")));
        check("manualUrl: Aptoide search", "https://en.aptoide.com/search?query=com.example.app".equals(MorpheHelper.manualUrl("aptoide", PKG, null)));
        check("manualUrl: Evozi goes through APKCube", "https://apkcube.com/apk-downloader?url=com.example.app".equals(MorpheHelper.manualUrl("evozi", PKG, null)));
        check("manualUrl: Mi9 version history and APK Downloader", "https://mi9.com/package/com.example.app/versions/".equals(MorpheHelper.manualUrl("mi9", PKG, null))
                && "https://apkdownloader.pages.dev/?package=com.example.app".equals(MorpheHelper.manualUrl("apkdownloader", PKG, null)));
        check("manualUrl: Aurora and Play open the Play listing", "https://play.google.com/store/apps/details?id=com.example.app".equals(MorpheHelper.manualUrl("aurora", PKG, null))
                && "https://play.google.com/store/apps/details?id=com.example.app".equals(MorpheHelper.manualUrl("play", PKG, null)));
        check("manualUrl: an unknown source has no page", "".equals(MorpheHelper.manualUrl("nope", PKG, null)));
        check("manualUrl: the package is encoded", MorpheHelper.manualUrl("aptoide", "a b&c", null).endsWith("a+b%26c"));
        check("unknown source and bad package names are refused in words",
                has(err(() -> MorpheHelper.versions("nope", PKG)), "unknown source") && has(err(() -> MorpheHelper.versions("aptoide", "not a package")), "not a package name")
                        && has(err(() -> MorpheHelper.resolve("aptoide", "../x", "1", "", "requested")), "not a package name")
                        && has(err(() -> MorpheHelper.resolve("aptoide", PKG, "1", "", "newest")), "unknown version policy")
                        && has(err(() -> MorpheHelper.resolve("aptoide", PKG, "", "", "requested")), "requested version is needed"));
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Text and versions
    // ------------------------------------------------------------------------------------------------------------------------------

    static void testText() throws Exception {
        check("unescape: named, decimal and hex entities; unknown ones stay", "Tom & Jerry's \"App\" AB &unknown;  x".equals(MorpheHelper.unescape("Tom &amp; Jerry&#39;s &quot;App&quot; &#x41;&#66; &unknown; &nbsp;x")));
        check("unescape: null, no entity, a lone ampersand, a bad number", "".equals(MorpheHelper.unescape(null)) && "plain".equals(MorpheHelper.unescape("plain")) && "a & b &#xZZ; &#99999999;".equals(MorpheHelper.unescape("a & b &#xZZ; &#99999999;")));
        MorpheHelper.Doc d = new MorpheHelper.Doc(fx("entities.html"));
        check("html: the title is decoded text", "Tom & Jerry's \"App\" AB &unknown; x".equals(d.title()), d.title());
        check("html: a comment, a script and a style hide their tags", d.select(null, "a", "gone").isEmpty() && d.select(null, "a", "fromscript").isEmpty());
        MorpheHelper.El main = d.byId("main");
        check("html: ids and classes (several spaces)", main != null && main.hasClass("a") && main.hasClass("b") && main.hasClass("c") && !main.hasClass("d"));
        MorpheHelper.El a = d.first(null, "a", "lnk");
        check("html: attributes - entity in a value, single quotes, an upper-case name, an unquoted value",
                a != null && "/x?a=1&b=2".equals(a.attr("href")) && "single \"quoted\"".equals(a.attr("data-x")) && "unquoted".equals(a.attr("data-up")));
        check("html: text of a link: entities decoded, tags dropped", a != null && "link <here>".equals(d.text(a)));
        check("html: an unclosed <p> ends at the next one", d.select(null, "p", "").size() == 2 && "one".equals(d.text(d.select(null, "p", "").get(0))));
        check("html: text across inline tags keeps words together", d.text(d.select(null, "p", "").get(1)).startsWith("two bold&it"), d.text(d.select(null, "p", "").get(1)));
        check("html: unclosed <li> ends at the next one", d.select(null, "li", "").size() == 2 && "x".equals(d.text(d.select(null, "li", "").get(0))));
        check("html: table rows without end tags still give label and value", "com.example.app".equals(d.infoValue("Package Name")) && "v".equals(d.infoValue("other")) && "".equals(d.infoValue("Missing")));
        check("html: nested elements are found below an element", d.select(main, "div", "").size() == 3);
        check("html: an unclosed tag at the end of the page is kept", "unclosed".equals(d.text(d.first(null, "span", ""))));
        MorpheHelper.Doc odd = new MorpheHelper.Doc("a < b <a href=\"x\">link</a> <b <i>x</i> <a href=\"never closed");
        check("html: a lone <, a broken tag and an unterminated attribute do not throw", odd.first(null, "a", "") != null && "link".equals(odd.text(odd.first(null, "a", ""))));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 700000; i++) huge.append("<a href=x>");
        long t0 = System.currentTimeMillis();
        MorpheHelper.Doc big = new MorpheHelper.Doc(huge.toString());
        check("html: a page of 700,000 tags is cut at the element cap, quickly", big.all.size() <= 400000 && System.currentTimeMillis() - t0 < 8000, "elements " + big.all.size() + " in " + (System.currentTimeMillis() - t0) + " ms");
    }

    static void testVersionRules() {
        check("version: equal across separators, prefixes and trailing build numbers", MorpheHelper.versionNameEquals("v1.2.3", "1.2.3", false) && MorpheHelper.versionNameEquals("1.2.3 (456)", "1.2.3", false)
                && MorpheHelper.versionNameEquals("2026.04.0", "2026.4.0", false) && !MorpheHelper.versionNameEquals("1.2.3", "1.2.4", false) && !MorpheHelper.versionNameEquals(null, "1", false));
        check("version: a -SECONDARY build is not the plain one, and the other way round", !MorpheHelper.versionNameEquals("21.36.45-SECONDARY", "21.36.45", false) && !MorpheHelper.versionNameEquals("21.36.45", "21.36.45-SECONDARY", false));
        check("version: a request for the variant matches both spellings", MorpheHelper.versionNameEquals("21.36.45", "21.36.45-SECONDARY", true) && MorpheHelper.versionNameEquals("21.36.45-SECONDARY", "21.36.45-SECONDARY", true));
        check("version: the marker needs a number in front (an app called Secondary is no variant)", MorpheHelper.hasVariantBuildMarker("1.0.4-SECONDARY") && !MorpheHelper.hasVariantBuildMarker("Secondary") && !MorpheHelper.hasVariantBuildMarker("Secondary 1.0") && !MorpheHelper.hasVariantBuildMarker(null));
        check("version: compare is numeric, missing parts are 0, null is oldest", MorpheHelper.compareVersionNames("10.0.0", "9.9.9") > 0 && MorpheHelper.compareVersionNames("2.0.1", "2.0.0") > 0 && MorpheHelper.compareVersionNames("1.0", "1.0.0") <= 0
                && MorpheHelper.compareVersionNames("1.0.0-rc1", "1.0.0") > 0 && MorpheHelper.compareVersionNames(null, "1.0.0") < 0 && MorpheHelper.compareVersionNames("1", null) > 0 && MorpheHelper.compareVersionNames("2.0.0", "2.0.0") == 0);
        check("version: a build number in brackets is split off", "1.2.3".equals(MorpheHelper.withoutTrailingVersionCode("1.2.3 (456)")) && MorpheHelper.trailingVersionCode("1.2.3 ( 456 )") == 456 && MorpheHelper.trailingVersionCode("1.2.3") == 0);
        String app = "https://www.apkmirror.com/apk/file-manager-plus/file-manager-7/";
        String rel = app + "file-manager-7-3-5-4-release/";
        check("apkmirror: a digit at the end of the app slug does not leak into the version", "7.3.5.4".equals(MorpheHelper.mirrorVersionFromReleaseUrl(rel, null)) && "3.5.4".equals(MorpheHelper.mirrorVersionFromReleaseUrl(rel, app)));
        check("apkmirror: -secondary in the slug becomes a marker on the version", "21.36.45-SECONDARY".equals(MorpheHelper.mirrorVersionFromReleaseUrl("https://www.apkmirror.com/apk/whatsapp/whatsapp/whatsapp-21-36-45-secondary-release/", null)));
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Aptoide
    // ------------------------------------------------------------------------------------------------------------------------------

    static void aptoideRoutes() throws Exception {
        apt.reset();
        apt.json("GET /api/7/getApp?package_name=com.example.app", "aptoide-getapp-package.json");
        apt.json("GET /api/7/listAppVersions?package_name=com.example.app&limit=100", "aptoide-listappversions.json");
        for (String id : new String[] {"1002", "1001", "1004"}) apt.json("GET /api/7/getApp?app_id=" + id, "aptoide-getapp-id-" + id + ".json");
        apt.json("GET /api/7/getApp?app_id=1003", "aptoide-getapp-package.json");
    }

    static void testAptoide() throws Exception {
        aptoideRoutes();
        JSONObject v = MorpheHelper.versions("aptoide", PKG);
        check("aptoide versions: newest first, the package's own entries only, no duplicates", Arrays.asList("1.0.4-SECONDARY", "1.0.3", "1.0.2", "1.0.1").equals(versionNames(v)), String.valueOf(versionNames(v)));
        JSONObject e3 = byVersion(v, "1.0.3", null);
        check("aptoide versions: name, code, format, size, md5, page and the direct address of the newest",
                "Exämple Åpp ñ 日本".equals(v.getString("name")) && e3.getLong("versionCode") == 103 && "apk".equals(e3.getString("format")) && e3.getLong("size") == 4096
                        && digest("MD5", FILES.get("example-1.0.3.apk")).equals(e3.getString("md5")) && "https://example-app.en.aptoide.com/versions".equals(e3.getString("page"))
                        && (apt.base + "/files/example-1.0.3.apk").equals(e3.getString("url")));
        check("aptoide versions: ABIs - several, one, none; an older version has no address until it is resolved",
                "armeabi-v7a,arm64-v8a,x86,x86_64".equals(e3.getString("abi")) && "x86".equals(byVersion(v, "1.0.2", null).getString("abi")) && "".equals(byVersion(v, "1.0.1", null).getString("abi")) && "".equals(byVersion(v, "1.0.2", null).getString("url")));
        check("aptoide versions: the store a file comes from is told (Aptoide files come from many user stores)", "someone".equals(byVersion(v, "1.0.4-SECONDARY", null).getString("store")) && "apps".equals(byVersion(v, "1.0.2", null).getString("store")));
        check("aptoide versions: every field of the page's contract is there", e3.has("version") && e3.has("versionCode") && e3.has("format") && e3.has("abi") && e3.has("size") && e3.has("page") && e3.has("url") && e3.has("sha256") && v.getBoolean("ok") && "aptoide".equals(v.getString("source")) && PKG.equals(v.getString("pkg")));

        JSONObject r = MorpheHelper.resolve("aptoide", PKG, "1.0.3", "arm64-v8a", "requested");
        check("aptoide resolve requested: the exact version, its address, md5 and the flags",
                r.getBoolean("ok") && "1.0.3".equals(r.getString("version")) && r.getLong("versionCode") == 103 && (apt.base + "/files/example-1.0.3.apk").equals(r.getString("url")) && r.getBoolean("exact") && !r.getBoolean("newer")
                        && "apk".equals(r.getString("format")) && digest("MD5", FILES.get("example-1.0.3.apk")).equals(r.getString("md5")) && "aptoide".equals(r.getString("source")) && "requested".equals(r.getString("policy")));
        JSONObject r2 = MorpheHelper.resolve("aptoide", PKG, "1.0.1", "arm64-v8a", "requested");
        check("aptoide resolve requested: an older version asks for its own address (getApp by id)", (apt.base + "/files/example-1.0.1.apk").equals(r2.getString("url")) && apt.hit("GET /api/7/getApp?app_id=1001"));
        Throwable t = err(() -> MorpheHelper.resolve("aptoide", PKG, "1.0.4", "arm64-v8a", "requested"));
        check("aptoide resolve requested: the SECONDARY build never stands in for the plain version, and the error names the newest", has(t, "does not list version 1.0.4", "newest it lists is 1.0.3"), msg(t));
        JSONObject sec = MorpheHelper.resolve("aptoide", PKG, "1.0.4-SECONDARY", "arm64-v8a", "requested");
        check("aptoide resolve requested: asking for the variant gets it", (apt.base + "/files/example-secondary.apk").equals(sec.getString("url")) && sec.getBoolean("exact") && "1.0.4-SECONDARY".equals(sec.getString("version")));
        JSONObject l = MorpheHelper.resolve("aptoide", PKG, null, "arm64-v8a", "latest");
        check("aptoide resolve latest: the newest plain build, not the SECONDARY one", "1.0.3".equals(l.getString("version")) && !l.getBoolean("exact") && "latest".equals(l.getString("policy")));
        JSONObject l2 = MorpheHelper.resolve("aptoide", PKG, "1.0.0", "arm64-v8a", "latest");
        JSONObject l3 = MorpheHelper.resolve("aptoide", PKG, "1.0.3", "arm64-v8a", "latest");
        check("aptoide resolve latest: says whether it is newer than the wanted version, or the same one", l2.getBoolean("newer") && !l2.getBoolean("exact") && !l3.getBoolean("newer") && l3.getBoolean("exact"));
        t = err(() -> MorpheHelper.resolve("aptoide", PKG, "1.0.3 (999)", "arm64-v8a", "requested"));
        check("aptoide resolve requested: the right name with another build is a build mismatch", has(t, "Build mismatch", "build 103", "build 999"), msg(t));
        JSONObject rc = MorpheHelper.resolve("aptoide", PKG, "1.0.3 (103)", "arm64-v8a", "requested");
        check("aptoide resolve requested: the build in brackets is accepted when it fits", rc.getBoolean("exact") && rc.getLong("wantedCode") == 103 && "1.0.3".equals(rc.getString("wanted")));
        t = err(() -> MorpheHelper.resolve("aptoide", PKG, "1.0.2", "arm64-v8a", "requested"));
        check("aptoide resolve: a version only built for another CPU says so", has(t, "only for x86", "not for arm64-v8a"), msg(t));
        check("aptoide resolve: a list of ABIs counts by its first (the phone's best one)", "1.0.3".equals(MorpheHelper.resolve("aptoide", PKG, "1.0.3", "arm64-v8a, armeabi-v7a", "requested").getString("version"))
                && has(err(() -> MorpheHelper.resolve("aptoide", PKG, "1.0.2", "arm64-v8a,armeabi-v7a", "requested")), "not for arm64-v8a"));
        check("aptoide resolve: no ABI asked takes what there is", "1.0.2".equals(MorpheHelper.resolve("aptoide", PKG, "1.0.2", "", "requested").getString("version")));

        // both a plain 1.0.4 and a 1.0.4-SECONDARY: each request gets its own, never the other
        aptoideRoutes();
        apt.on("GET /api/7/listAppVersions?package_name=com.example.app&limit=100", 200, "application/json",
                "{\"info\":{\"status\":\"OK\"},\"list\":[{\"id\":2001,\"package\":\"com.example.app\",\"uname\":\"example-app\",\"file\":{\"vername\":\"1.0.4-SECONDARY\",\"vercode\":104,\"md5sum\":\"\",\"filesize\":1}},"
                        + "{\"id\":2002,\"package\":\"com.example.app\",\"uname\":\"example-app\",\"file\":{\"vername\":\"1.0.4\",\"vercode\":104,\"md5sum\":\"\",\"filesize\":1}}]}");
        apt.on("GET /api/7/getApp?app_id=2001", 200, "application/json", "{\"info\":{\"status\":\"OK\"},\"nodes\":{\"meta\":{\"data\":{\"id\":2001,\"package\":\"com.example.app\",\"file\":{\"vername\":\"1.0.4-SECONDARY\",\"vercode\":104,\"path\":\"" + apt.base + "/files/example-secondary.apk\"}}}}}");
        apt.on("GET /api/7/getApp?app_id=2002", 200, "application/json", "{\"info\":{\"status\":\"OK\"},\"nodes\":{\"meta\":{\"data\":{\"id\":2002,\"package\":\"com.example.app\",\"file\":{\"vername\":\"1.0.4\",\"vercode\":104,\"path\":\"" + apt.base + "/files/example-1.0.3.apk\"}}}}}");
        check("aptoide resolve requested: with both a plain and a SECONDARY build listed, each request gets its own",
                MorpheHelper.resolve("aptoide", PKG, "1.0.4-SECONDARY", "", "requested").getString("url").endsWith("example-secondary.apk")
                        && MorpheHelper.resolve("aptoide", PKG, "1.0.4", "", "requested").getString("url").endsWith("example-1.0.3.apk")
                        && "1.0.4".equals(MorpheHelper.resolve("aptoide", PKG, null, "", "latest").getString("version")));
        JSONObject full = MorpheHelper.resolve("aptoide", PKG, "1.0.3", "arm64-v8a", "requested");
        boolean all = true;
        for (String k : new String[] {"ok", "source", "pkg", "version", "versionCode", "format", "abi", "url", "size", "sha256", "page", "exact"}) all &= full.has(k);
        check("aptoide resolve: every field of the page's contract is there", all, full.toString());

        apt.reset();
        apt.on("GET /api/7/getApp?package_name=com.nope.app", 404, "application/json", fx("aptoide-notfound.json"));
        apt.on("POST /api/7/listSearchApps", 200, "application/json", "{\"info\":{\"status\":\"OK\"},\"datalist\":{\"total\":0,\"list\":[]}}");
        t = err(() -> MorpheHelper.versions("aptoide", "com.nope.app"));
        check("aptoide: a package it does not know is 'does not list'", has(t, "Aptoide does not list com.nope.app") && !(t instanceof MorpheHelper.NeedsBrowser), msg(t));

        aptoideRoutes();
        apt.on("GET /api/7/getApp?package_name=com.example.app", 404, "application/json", fx("aptoide-notfound.json"));
        apt.json("POST /api/7/listSearchApps", "aptoide-search.json");
        JSONObject viaSearch = MorpheHelper.versions("aptoide", PKG);
        check("aptoide: getApp 404 falls back to the search (and skips another app's entry)", versionNames(viaSearch).contains("1.0.3") && !versionNames(viaSearch).contains("3.0.0"));
        JSONObject body = new JSONObject(apt.bodies.get(apt.hits.indexOf("POST /api/7/listSearchApps")));
        check("aptoide: the search asks for the package in Helper's way (25, no alpha/beta, the two stores)", PKG.equals(body.getString("query")) && "25".equals(body.getString("limit")) && "alpha,beta".equals(body.getString("not_apk_tags")) && body.getJSONArray("store_ids").length() == 2);

        apt.reset();
        apt.on("GET /api/7/getApp?package_name=com.example.app", 200, "application/json", "{\"info\":{\"status\":\"OK\"},\"nodes\":{\"meta\":{}}}");
        t = err(() -> MorpheHelper.versions("aptoide", PKG));
        check("aptoide: an answer without the app's data is 'changed its page format'", has(t, "Aptoide changed its page format, open it in the browser"), msg(t));
        apt.on("GET /api/7/getApp?package_name=com.example.app", 200, "text/html", "<html>maintenance</html>");
        t = err(() -> MorpheHelper.versions("aptoide", PKG));
        check("aptoide: a page instead of JSON is 'changed its page format'", has(t, "changed its page format"), msg(t));
        apt.on("GET /api/7/getApp?package_name=com.example.app", 500, "text/plain", "boom");
        t = err(() -> MorpheHelper.versions("aptoide", PKG));
        check("aptoide: HTTP 500 is said in words, no trace", has(t, "Aptoide answered HTTP 500") && t instanceof IOException, msg(t));
        apt.on("GET /api/7/getApp?package_name=com.example.app", 429, "text/plain", "slow down");
        t = err(() -> MorpheHelper.versions("aptoide", PKG));
        check("aptoide: HTTP 429 says rate-limited", has(t, "rate-limited"), msg(t));
        apt.on("GET /api/7/getApp?package_name=com.example.app", 403, "text/html", "<html>blocked</html>");
        t = err(() -> MorpheHelper.versions("aptoide", PKG));
        check("aptoide: a 403 bot wall is a NeedsBrowser carrying the page to open", t instanceof MorpheHelper.NeedsBrowser && ((MorpheHelper.NeedsBrowser) t).page.startsWith("https://en.aptoide.com/search"), msg(t));
        String keep = MorpheHelper.baseOverride.get("aptoide");
        MorpheHelper.baseOverride.put("aptoide", "http://127.0.0.1:1");
        t = err(() -> MorpheHelper.versions("aptoide", PKG));
        check("aptoide: nothing listening is 'could not connect', not a stack trace", has(t, "Could not connect to Aptoide"), msg(t));
        MorpheHelper.baseOverride.put("aptoide", "ftp://example.com");
        t = err(() -> MorpheHelper.versions("aptoide", PKG));
        check("aptoide: an address that is not https is refused by the network layer", t != null && t.getMessage() != null && t.getMessage().contains("https"), msg(t));
        MorpheHelper.baseOverride.put("aptoide", keep);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // APKPure
    // ------------------------------------------------------------------------------------------------------------------------------

    static void testApkPure() throws Exception {
        pure.reset();
        pure.json("POST /v3/get_app_update", "apkpure-update.json");
        JSONObject v = MorpheHelper.versions("apkpure", PKG);
        JSONObject e = v.getJSONArray("versions").getJSONObject(0);
        check("apkpure versions: the one build its API hands out, with address, sha-256, size and format",
                v.getJSONArray("versions").length() == 1 && "1.0.3".equals(e.getString("version")) && e.getLong("versionCode") == 103 && "xapk".equals(e.getString("format"))
                        && digest("SHA-256", FILES.get("example.xapk")).equals(e.getString("sha256")) && e.getLong("size") == FILES.get("example.xapk").length
                        && e.getString("url").equals(pure.base + "/files/example.xapk?as=98db&k=b4a2") && "arm64-v8a,armeabi-v7a".equals(e.getString("abi")) && "Example App".equals(v.getString("name")));
        Map<String, String> h = pure.hitHeaders.get(0);
        JSONObject dev = new JSONObject(h.get("ual-access-projecta"));
        JSONObject req = new JSONObject(pure.bodies.get(0));
        check("apkpure request: the app's own user agent, business id and device header",
                "APKPure/3.19.39 (Aegon)".equals(h.get("user-agent")) && "projecta".equals(h.get("ual-access-businessid")) && "application/json".equals(h.get("content-type"))
                        && dev.getJSONObject("device_info").getJSONArray("abis").getString(0).equals("arm64-v8a") && dev.getJSONObject("device_info").getString("android_id").length() == 16);
        check("apkpure request: the update check for version code 0 of the package", PKG.equals(req.getJSONArray("app_info_for_update").getJSONObject(0).getString("package_name")) && req.getJSONArray("app_info_for_update").getJSONObject(0).getInt("version_code") == 0 && "com.apkpure.aegon".equals(req.getString("application_id")));
        JSONObject r = MorpheHelper.resolve("apkpure", PKG, "1.0.3", "arm64-v8a", "requested");
        check("apkpure resolve requested: the newest build when that is the one asked for", r.getBoolean("exact") && r.getString("url").contains("/files/example.xapk") && "xapk".equals(r.getString("format")) && r.getString("sha256").length() == 64);
        JSONObject rl = MorpheHelper.resolve("apkpure", PKG, null, "arm64-v8a", "latest");
        check("apkpure resolve latest", "1.0.3".equals(rl.getString("version")) && !rl.getBoolean("exact"));
        Throwable t = err(() -> MorpheHelper.resolve("apkpure", PKG, "1.0.2", "arm64-v8a", "requested"));
        check("apkpure resolve requested: an older version needs the browser, and the error says which build it has",
                t instanceof MorpheHelper.NeedsBrowser && has(t, "newest build (1.0.3)", "not 1.0.2") && "https://apkpure.com/apk-info/com.example.app".equals(((MorpheHelper.NeedsBrowser) t).page), msg(t));
        t = err(() -> MorpheHelper.resolve("apkpure", PKG, "1.0.3", "x86_64", "requested"));
        check("apkpure resolve: a build for other CPUs only is refused in words", has(t, "only for arm64-v8a,armeabi-v7a", "not for x86_64"), msg(t));
        pure.reset();
        pure.json("POST /v3/get_app_update", "apkpure-update.json");
        try {
            MorpheHelper.resolve("apkpure", PKG, "1.0.3", "x86_64", "latest");
        } catch (IOException ignored) {
        }
        JSONObject dev2 = new JSONObject(pure.hitHeaders.get(pure.hitHeaders.size() - 1).get("ual-access-projecta"));
        check("apkpure request: the phone's ABI goes into the device header", "x86_64".equals(dev2.getJSONObject("device_info").getJSONArray("abis").getString(0)));
        pure.json("POST /v3/get_app_update", "apkpure-update-other.json");
        t = err(() -> MorpheHelper.versions("apkpure", PKG));
        check("apkpure: an answer about another package is 'does not list'", has(t, "APKPure does not list"), msg(t));
        pure.json("POST /v3/get_app_update", "apkpure-update-empty.json");
        t = err(() -> MorpheHelper.versions("apkpure", PKG));
        check("apkpure: an empty answer is 'does not list'", has(t, "APKPure does not list"), msg(t));
        pure.json("POST /v3/get_app_update", "apkpure-update-apk.json");
        JSONObject plain = MorpheHelper.versions("apkpure", PKG).getJSONArray("versions").getJSONObject(0);
        check("apkpure: a plain APK, a bad checksum ignored, no ABI information", "apk".equals(plain.getString("format")) && "".equals(plain.getString("sha256")) && "".equals(plain.getString("abi")));
        pure.on("POST /v3/get_app_update", 200, "application/json", "{\"hello\":1}");
        t = err(() -> MorpheHelper.versions("apkpure", PKG));
        check("apkpure: JSON of another shape is 'changed its page format'", has(t, "APKPure changed its page format"), msg(t));
        pure.on("POST /v3/get_app_update", 403, "text/html", "<html>no</html>");
        t = err(() -> MorpheHelper.resolve("apkpure", PKG, "1.0.3", "", "requested"));
        check("apkpure: a 403 is a NeedsBrowser", t instanceof MorpheHelper.NeedsBrowser, msg(t));
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // APKCombo
    // ------------------------------------------------------------------------------------------------------------------------------

    static void comboRoutes() throws Exception {
        combo.reset();
        combo.redirect("GET /search/com.example.app/download/apk", "/example-app/com.example.app/download/apk");
        combo.page("GET /example-app/com.example.app/download/apk", "apkcombo-download.html");
        combo.page("GET /example-app/com.example.app/old-versions/", "apkcombo-old-versions.html");
        combo.text("GET /checkin", 200, fx("apkcombo-checkin.txt"));
        combo.dyn("GET /r2", new Dyn() {
            @Override
            public Resp apply(HttpExchange ex, Mock m) throws IOException {
                String q = ex.getRequestURI().getRawQuery();
                String u = null;
                for (String p : q.split("&")) if (p.startsWith("u=")) u = URLDecoder.decode(p.substring(2), "UTF-8");
                Resp r = new Resp();
                r.status = 302;
                r.location = u;
                return r;
            }
        });
        combo.redirect("GET /search/com.example.app/download/phone-1.0.2-apk", "/example-app/com.example.app/download/phone-1.0.2-apk");
        combo.page("GET /example-app/com.example.app/download/phone-1.0.2-apk", "apkcombo-version-1.0.2.html");
        combo.page("GET /example-app/com.example.app/download/phone-1.0.1-xapk", "apkcombo-version-1.0.1.html");
        combo.redirect("GET /search/com.example.app/download/phone-1.0.9-apk", "/example-app/com.example.app/old-versions/");
        combo.redirect("GET /search/com.example.app/download/phone-1.0.9-xapk", "/example-app/com.example.app/old-versions/");
        combo.redirect("GET /search/com.example.app/download/phone-1.0.9-apks", "/example-app/com.example.app/old-versions/");
    }

    static void testApkCombo() throws Exception {
        comboRoutes();
        JSONObject v = MorpheHelper.versions("apkcombo", PKG);
        check("apkcombo versions: the latest page's files, then the older ones from both lists, no duplicates, newest first",
                Arrays.asList("1.0.3", "1.0.3", "1.0.2", "1.0.1", "0.9.0").equals(versionNames(v)), String.valueOf(versionNames(v)));
        JSONObject apk3 = byVersion(v, "1.0.3", "apk"), xapk3 = byVersion(v, "1.0.3", "xapk");
        check("apkcombo versions: the plain APK variant - code, size, ABI, page", apk3 != null && apk3.getLong("versionCode") == 103 && apk3.getLong("size") == 4096 && "arm64-v8a".equals(apk3.getString("abi")) && apk3.getString("page").equals(combo.base + "/example-app/com.example.app/download/apk"));
        check("apkcombo versions: the bundle variant - several ABIs and a decimal size", xapk3 != null && "arm64-v8a,armeabi-v7a,x86_64".equals(xapk3.getString("abi")) && xapk3.getLong("size") == 1572864);
        check("apkcombo versions: an older version carries its page and the type its list showed", "xapk".equals(byVersion(v, "1.0.1", null).getString("format")) && byVersion(v, "1.0.2", null).getString("page").endsWith("/download/phone-1.0.2-apk") && "".equals(byVersion(v, "1.0.2", null).getString("url")));
        check("apkcombo versions: the old-versions address comes from the page's own folder", combo.hit("GET /example-app/com.example.app/old-versions/") && combo.hit("GET /search/com.example.app/download/apk"));

        JSONObject r = MorpheHelper.resolve("apkcombo", PKG, null, "arm64-v8a", "latest");
        check("apkcombo resolve latest: the APK built for the phone is chosen over the bundle", "apk".equals(r.getString("format")) && "arm64-v8a".equals(r.getString("abi")) && r.getLong("versionCode") == 103 && "1.0.3".equals(r.getString("version")));
        check("apkcombo resolve: the file link goes through /r2 with the check-in key appended, and no Referer", r.getString("url").startsWith(combo.base + "/r2?u=") && r.getString("url").contains("&b=2") && r.getString("url").endsWith("&fp=abc123&ip=203.0.113.9") && "".equals(r.getString("referer")));
        JSONObject rx = MorpheHelper.resolve("apkcombo", PKG, null, "x86_64", "latest");
        check("apkcombo resolve latest: a phone the APK is not built for gets the bundle that has its ABI", "xapk".equals(rx.getString("format")) && rx.getString("url").contains("example.xapk"));
        JSONObject rn = MorpheHelper.resolve("apkcombo", PKG, null, "", "latest");
        check("apkcombo resolve latest: no ABI asked, the plain APK first", "apk".equals(rn.getString("format")));
        JSONObject r2 = MorpheHelper.resolve("apkcombo", PKG, "1.0.2", "arm64-v8a", "requested");
        check("apkcombo resolve requested: an older version's page is read for its files", r2.getBoolean("exact") && r2.getString("url").contains("example-1.0.2.apk") && "arm64-v8a".equals(r2.getString("abi")) && combo.hit("GET /search/com.example.app/download/apk"));
        JSONObject r2x = MorpheHelper.resolve("apkcombo", PKG, "1.0.2", "x86", "requested");
        check("apkcombo resolve requested: ... and the x86 file for an x86 phone", r2x.getString("url").contains("example-1.0.2-x86.apk"));
        JSONObject r1 = MorpheHelper.resolve("apkcombo", PKG, "1.0.1", "arm64-v8a", "requested");
        check("apkcombo resolve requested: Helper's obfuscated link with only the XAPK badge to tell the type", "xapk".equals(r1.getString("format")) && r1.getString("url").contains("V0ZabGNtZHZaMjQ9P2tleT1hYmNkZWY=") && r1.getString("url").contains("fp=abc123"));
        combo.hits.clear();
        Throwable t = err(() -> MorpheHelper.resolve("apkcombo", PKG, "1.0.9", "arm64-v8a", "requested"));
        check("apkcombo resolve requested: a version no list shows is tried by address, then 'does not list' with the newest", has(t, "does not list version 1.0.9", "newest it lists is 1.0.3"), msg(t));
        check("apkcombo resolve requested: the address guess is bounded (apk, xapk, apks)", combo.count("GET /search/com.example.app/download/phone-1.0.9-") == 3, String.valueOf(combo.hits));
        t = err(() -> MorpheHelper.resolve("apkcombo", PKG, "0.9.0", "arm64-v8a", "requested"));
        check("apkcombo resolve requested: a listed version whose page is gone says so", has(t, "no page for version 0.9.0"), msg(t));
        combo.page("GET /example-app/com.example.app/download/phone-1.0.2-apk", "apkcombo-version-gated.html");
        t = err(() -> MorpheHelper.resolve("apkcombo", PKG, "1.0.2", "arm64-v8a", "requested"));
        check("apkcombo resolve requested: a captcha page is a NeedsBrowser with that page", t instanceof MorpheHelper.NeedsBrowser && has(t, "captcha") && ((MorpheHelper.NeedsBrowser) t).page.endsWith("/download/phone-1.0.2-apk"), msg(t));
        comboRoutes();
        combo.page("GET /example-app/com.example.app/download/apk", "apkcombo-latest-gated.html");
        combo.page("GET /example-app/com.example.app/download/phone-1.0.3-apk", "apkcombo-version-gated.html");
        JSONObject gv = MorpheHelper.versions("apkcombo", PKG);
        check("apkcombo versions: a captcha-gated latest page still lists the versions (from its links and the old-versions page)", Arrays.asList("1.0.3", "1.0.2", "1.0.1", "0.9.0").equals(versionNames(gv)), String.valueOf(versionNames(gv)));
        combo.page("GET /example-app/com.example.app/download/apk", "apkcombo-latest-gated.html");
        t = err(() -> MorpheHelper.resolve("apkcombo", PKG, null, "arm64-v8a", "latest"));
        check("apkcombo resolve latest: the gated page is a NeedsBrowser about the captcha", t instanceof MorpheHelper.NeedsBrowser && has(t, "captcha"), msg(t));
        combo.page("GET /example-app/com.example.app/download/apk", "apkcombo-no-variants.html");
        combo.routes.remove("GET /example-app/com.example.app/old-versions/");
        t = err(() -> MorpheHelper.versions("apkcombo", PKG));
        check("apkcombo: a page with nothing to read is 'changed its page format'", has(t, "APKCombo changed its page format, open it in the browser"), msg(t));
        combo.page("GET /example-app/com.example.app/download/apk", "apkcombo-unrelated.html");
        combo.redirect("GET /search/com.example.app/download/apk", "/somewhere/else");
        combo.page("GET /somewhere/else", "apkcombo-unrelated.html");
        t = err(() -> MorpheHelper.versions("apkcombo", PKG));
        check("apkcombo: a page that is not about the package is 'does not list'", has(t, "APKCombo does not list"), msg(t));
        StringBuilder hb = new StringBuilder("<html><head><link rel=\"canonical\" href=\"https://apkcombo.com/example-app/com.example.app/download/apk\"/></head><body>");
        for (int i = 0; i < 30000; i++) {
            hb.append("<a class=\"variant\" href=\"/r2?u=").append(i).append("\"><span class=\"vername\">App 1.0.").append(i % 50).append("</span><span class=\"vtype\">APK</span></a>");
            hb.append("<a class=\"ver-item\" href=\"/example-app/com.example.app/download/phone-2.").append(i).append(".0-apk\"><span class=\"vername\">App 2.").append(i).append(".0</span></a>");
        }
        combo.on("GET /example-app/com.example.app/download/apk", 200, "text/html", hb.toString());
        combo.redirect("GET /search/com.example.app/download/apk", "/example-app/com.example.app/download/apk");
        long t0 = System.currentTimeMillis();
        JSONObject hostile = MorpheHelper.versions("apkcombo", PKG);
        check("apkcombo: a page with 60,000 links is cut at the item cap and read in a few seconds", versionsOf(hostile).length() <= 1000 && versionsOf(hostile).length() > 0 && System.currentTimeMillis() - t0 < 20000, versionsOf(hostile).length() + " in " + (System.currentTimeMillis() - t0) + " ms");
        combo.reset();
        combo.text("GET /search/com.example.app/download/apk", 404, "gone");
        t = err(() -> MorpheHelper.versions("apkcombo", PKG));
        check("apkcombo: a 404 is 'does not list'", has(t, "APKCombo does not list"), msg(t));
        combo.text("GET /search/com.example.app/download/apk", 403, "<html>x</html>");
        t = err(() -> MorpheHelper.resolve("apkcombo", PKG, "1.0.3", "", "requested"));
        check("apkcombo: a 403 is a NeedsBrowser that says it was blocked", t instanceof MorpheHelper.NeedsBrowser && has(t, "blocked automated access"), msg(t));
        combo.text("GET /search/com.example.app/download/apk", 429, "slow");
        t = err(() -> MorpheHelper.versions("apkcombo", PKG));
        check("apkcombo: a 429 is a plain 'rate-limited' message", has(t, "rate-limited") && !(t instanceof MorpheHelper.NeedsBrowser), msg(t));
        combo.page("GET /search/com.example.app/download/apk", "apkcombo-download.html");
        combo.on("GET /search/com.example.app/download/apk", 200, "text/html", fx("apkmirror-challenge.html"));
        t = err(() -> MorpheHelper.versions("apkcombo", PKG));
        check("apkcombo: a Cloudflare 'Just a moment' page answered with 200 is a NeedsBrowser", t instanceof MorpheHelper.NeedsBrowser && has(t, "browser verification"), msg(t));
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Uptodown
    // ------------------------------------------------------------------------------------------------------------------------------

    static void uptodownRoutes() throws Exception {
        utd.reset();
        utd.page("GET /android/search?query=com.example.app", "uptodown-search.html");
        utd.page("GET /other-app/android/download", "uptodown-other-download.html");
        utd.page("GET /example-app/android/download", "uptodown-download.html");
        utd.json("GET /example-app/android/apps/12345/versions/1", "uptodown-versions-1.json");
        utd.json("GET /example-app/android/apps/12345/versions/2", "uptodown-versions-2.json");
        utd.json("GET /example-app/android/apps/12345/versions/3", "uptodown-versions-3.json");
        utd.page("GET /example-app/android/download/1003", "uptodown-version-variants.html");
        utd.page("GET /example-app/android/download/1002", "uptodown-version-direct.html");
        utd.page("GET /example-app/android/download/1001", "uptodown-version-external.html");
        utd.page("GET /example-app/android/download/1004", "uptodown-version-gated.html");
        utd.json("GET /example-app/app/12345/version/777/files", "uptodown-files.json");
        utd.page("GET /example-app/android/download/5002-x", "uptodown-token-5002.html");
        utd.page("GET /example-app/android/download/5003-x", "uptodown-token-5003.html");
    }

    static void testUptodown() throws Exception {
        uptodownRoutes();
        JSONObject v = MorpheHelper.versions("uptodown", PKG);
        check("uptodown versions: the JSON pages until an empty one, newest first; a blank version and one without an address are skipped",
                Arrays.asList("1.0.4-SECONDARY", "1.0.3", "1.0.2", "1.0.1").equals(versionNames(v)), String.valueOf(versionNames(v)));
        check("uptodown versions: the app is the one whose package matches (the first search hit is another app) and each link is read once",
                utd.hit("GET /other-app/android/download") && utd.count("GET /example-app/android/download") == 1 && "Example App".equals(v.getString("name")) && !utd.hit("GET /somewhere/else/download"));
        JSONObject e3 = byVersion(v, "1.0.3", null);
        check("uptodown versions: type, version page (the trailing slash is dropped) and no direct address", "xapk".equals(e3.getString("format")) && e3.getString("page").equals(utd.base + "/example-app/android/download/1003") && "".equals(e3.getString("url"))
                && byVersion(v, "1.0.1", null).getString("page").equals(utd.base + "/example-app/android/download/1001"));
        check("uptodown versions: the versions call carries the app's versions page as Referer", utd.hitHeaders.get(utd.hits.indexOf("GET /example-app/android/apps/12345/versions/1")).get("referer").endsWith("/example-app/android/versions"));
        check("uptodown versions: a search link to another host is never followed", !utd.hits.toString().contains("evil"));

        utd.hits.clear();
        JSONObject direct = MorpheHelper.resolve("uptodown", PKG, "1.0.2", "arm64-v8a", "requested");
        check("uptodown resolve requested: a page that does hand out the file (data-url) gives the dw.uptodown.com address, the SHA-256 of its table and the Referer",
                "https://dw.uptodown.com/dwn/xyz-abc/example-1.0.2.apk".equals(direct.getString("url")) && digest("SHA-256", FILES.get("example-1.0.2.apk")).equals(direct.getString("sha256")) && direct.getString("referer").endsWith("/download/1002") && direct.getBoolean("exact"));
        check("uptodown resolve requested: the walk stops at the page that has the version (no needless pages)", utd.count("GET /example-app/android/apps/12345/versions/2") == 0 && utd.count("GET /example-app/android/apps/12345/versions/1") == 1);
        Throwable t = err(() -> MorpheHelper.resolve("uptodown", PKG, "1.0.4-SECONDARY", "arm64-v8a", "requested"));
        check("uptodown resolve: a page with a Turnstile captcha is a NeedsBrowser with that version's page", t instanceof MorpheHelper.NeedsBrowser && has(t, "captcha") && ((MorpheHelper.NeedsBrowser) t).page.endsWith("/download/1004") && "uptodown".equals(((MorpheHelper.NeedsBrowser) t).source), msg(t));
        t = err(() -> MorpheHelper.resolve("uptodown", PKG, "1.0.1", "arm64-v8a", "requested"));
        check("uptodown resolve: a Play Store listing is no download", t instanceof MorpheHelper.NeedsBrowser && has(t, "shows no download link"), msg(t));
        JSONObject var = MorpheHelper.resolve("uptodown", PKG, "1.0.3", "arm64-v8a", "requested");
        check("uptodown resolve: the variants are read; the plain APK built for arm64 wins over the bundle and the x86 one",
                "apk".equals(var.getString("format")) && "arm64-v8a".equals(var.getString("abi")) && "https://dw.uptodown.com/dwn/tok5002/example-1.0.3-arm64.apk".equals(var.getString("url")) && digest("SHA-256", FILES.get("example-1.0.3.apk")).equals(var.getString("sha256")));
        check("uptodown resolve: the variants answer is asked with the page as Referer; the token page is -x", utd.hit("GET /example-app/app/12345/version/777/files") && utd.hit("GET /example-app/android/download/5002-x"));
        t = err(() -> MorpheHelper.resolve("uptodown", PKG, "1.0.3", "armeabi-v7a", "requested"));
        check("uptodown resolve: a 32-bit phone gets the bundle, whose token page is gated: NeedsBrowser", t instanceof MorpheHelper.NeedsBrowser && has(t, "captcha"), msg(t));
        t = err(() -> MorpheHelper.resolve("uptodown", PKG, "1.0.3", "mips", "requested"));
        check("uptodown resolve: no variant for the phone's CPU is said in words", has(t, "only for", "not for mips"), msg(t));
        t = err(() -> MorpheHelper.resolve("uptodown", PKG, null, "arm64-v8a", "latest"));
        check("uptodown resolve latest: the newest plain version (1.0.3, not the SECONDARY build) goes on to its variants",
                MorpheHelper.resolve("uptodown", PKG, null, "arm64-v8a", "latest").getString("version").equals("1.0.3"));

        utd.page("GET /android/search?query=com.example.app", "apkmirror-noresults.html");
        t = err(() -> MorpheHelper.versions("uptodown", PKG));
        check("uptodown: a search without a matching app page is 'does not list'", has(t, "Uptodown does not list"), msg(t));
        uptodownRoutes();
        utd.routes.remove("GET /example-app/android/download");
        t = err(() -> MorpheHelper.versions("uptodown", PKG));
        check("uptodown: app pages that are gone leave 'does not list'", has(t, "Uptodown does not list"), msg(t));
        uptodownRoutes();
        utd.on("GET /example-app/android/apps/12345/versions/1", 200, "application/json", "{\"success\":0}");
        t = err(() -> MorpheHelper.versions("uptodown", PKG));
        check("uptodown: a versions answer without data is 'changed its page format'", has(t, "Uptodown changed its page format"), msg(t));
        utd.on("GET /example-app/android/apps/12345/versions/1", 200, "text/html", "<html>nope</html>");
        t = err(() -> MorpheHelper.versions("uptodown", PKG));
        check("uptodown: a versions answer that is a page is 'changed its page format'", has(t, "changed its page format"), msg(t));
        utd.on("GET /example-app/android/apps/12345/versions/1", 200, "application/json", "{\"success\":1,\"data\":[]}");
        t = err(() -> MorpheHelper.versions("uptodown", PKG));
        check("uptodown: an app with no versions listed is 'changed its page format'", has(t, "changed its page format"), msg(t));
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // APKMirror
    // ------------------------------------------------------------------------------------------------------------------------------

    static final String MIRROR_SEARCH = "GET /?post_type=app_release&searchtype=app&s=com.example.app";

    static void mirrorRoutes() throws Exception {
        mirror.reset();
        mirror.page(MIRROR_SEARCH, "apkmirror-search.html");
        mirror.page("GET /apk/other-dev/other-app/", "apkmirror-other-app.html");
        mirror.page("GET /apk/example-org/example-app-amazon-fire-tablet-version/", "apkmirror-amazon-app.html");
        mirror.page("GET /apk/example-org/example-app/", "apkmirror-app.html");
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-3-release/", "apkmirror-release.html");
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-3-release/example-app-1-0-3-android-apk-download/", "apkmirror-variant.html");
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-3-release/example-app-1-0-3-android-apk-download/download/", "apkmirror-download.html");
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-2-release/", "apkmirror-release-bundle.html");
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-2-release/example-app-1-0-2-android-apk-download/", "apkmirror-variant.html");
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-1-release/", "apkmirror-single-release.html");
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-2-release/download/", "apkmirror-single-download.html");
        mirror.dyn("GET /wp-content/themes/APKMirror/download.php", new Dyn() {
            @Override
            public Resp apply(HttpExchange ex, Mock m) {
                Resp r = new Resp();
                r.type = "application/octet-stream";
                r.body = FILES.get("example-1.0.3.apk");
                return r;
            }
        });
    }

    static void testApkMirror() throws Exception {
        mirrorRoutes();
        JSONObject v = MorpheHelper.versions("apkmirror", PKG);
        check("apkmirror versions: the app page's release links, the other app's link, a #fragment, a feed and a missing slash handled",
                Arrays.asList("2.0.0.beta", "1.0.4-SECONDARY", "1.0.3", "1.0.2", "1.0.1").equals(versionNames(v)), String.valueOf(versionNames(v)));
        check("apkmirror versions: page = the release page, no direct address, no format yet", byVersion(v, "1.0.3", null).getString("page").equals(mirror.base + "/apk/example-org/example-app/example-app-1-0-3-release/") && "".equals(byVersion(v, "1.0.3", null).getString("url")) && "".equals(byVersion(v, "1.0.3", null).getString("format")));
        check("apkmirror versions: the name is the page's heading, entity decoded", "Example App & Friends".equals(v.getString("name")), v.getString("name"));
        check("apkmirror versions: the best-named app page is fetched first and verified by the Play link (no other app page needed)", mirror.hit("GET /apk/example-org/example-app/") && !mirror.hit("GET /apk/other-dev/other-app/"), String.valueOf(mirror.hits));

        mirror.hits.clear();
        JSONObject r = MorpheHelper.resolve("apkmirror", PKG, null, "arm64-v8a", "latest");
        check("apkmirror resolve latest: release -> variant -> download page -> the final link; the stable plain build only", "1.0.3".equals(r.getString("version")) && r.getString("url").equals(mirror.base + "/wp-content/themes/APKMirror/download.php?id=123&key=abc"));
        check("apkmirror resolve: the nodpi APK variant is chosen over the DPI-specific bundle; its forcebaseapk button is used",
                "apk".equals(r.getString("format")) && "arm64-v8a".equals(r.getString("abi")) && mirror.hit("GET /apk/example-org/example-app/example-app-1-0-3-release/example-app-1-0-3-android-apk-download/download/?forcebaseapk=true"));
        check("apkmirror resolve: the build number comes from the grey line of the variant's row", r.getLong("versionCode") == 103);
        check("apkmirror resolve: the SHA-256 is the file's, not the certificate's (the modal lists both)", digest("SHA-256", FILES.get("example-1.0.3.apk")).equals(r.getString("sha256")));
        check("apkmirror resolve: the Referer for the file is the download button page", r.getString("referer").contains("/download/?forcebaseapk=true"));
        JSONObject rb = MorpheHelper.resolve("apkmirror", PKG, "1.0.2", "arm64-v8a", "requested");
        check("apkmirror resolve requested: a release with only a bundle gives the bundle, the non-forcebaseapk button and no hash",
                "apkm".equals(rb.getString("format")) && "".equals(rb.getString("sha256")) && rb.getBoolean("exact") && "arm64-v8a,armeabi-v7a".equals(rb.getString("abi")) && rb.getString("referer").endsWith("/download/"));
        check("apkmirror resolve requested: the bundle row's build number is 102", rb.getLong("versionCode") == 102);
        Throwable bm = err(() -> MorpheHelper.resolve("apkmirror", PKG, "1.0.3 (999)", "arm64-v8a", "requested"));
        check("apkmirror resolve requested: a build number that is only known from the file's page is still checked (build mismatch)", has(bm, "Build mismatch", "build 103", "build 999"), msg(bm));
        // v7.10.17: the browser check stands in front of the variant page (as it does on the real site from many networks): the in-app browser is sent to THAT variant, the bundle for this phone
        String vkey = "GET /apk/example-org/example-app/example-app-1-0-2-release/example-app-1-0-2-android-apk-download/";
        mirror.on(vkey, 403, "text/html; charset=utf-8", "<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>");
        Throwable wall = err(() -> MorpheHelper.resolve("apkmirror", PKG, "1.0.2", "arm64-v8a", "requested"));
        check("apkmirror resolve requested: a bot wall on the variant page sends the browser to that bundle variant, not to the whole release",
                wall instanceof MorpheHelper.NeedsBrowser && ((MorpheHelper.NeedsBrowser) wall).page.endsWith("/example-app-1-0-2-android-apk-download/") && has(wall, "bundle (split APKs)"), msg(wall));
        mirror.page(vkey, "apkmirror-variant.html");
        JSONObject rs = MorpheHelper.resolve("apkmirror", PKG, "1.0.1", "arm64-v8a", "requested");
        check("apkmirror resolve requested: a release page with the button itself (no variants table) and a download.php link", rs.getString("url").endsWith("/download.php?id=77&key=k") && "".equals(rs.getString("sha256")) && "apk".equals(rs.getString("format")));
        Throwable t = err(() -> MorpheHelper.resolve("apkmirror", PKG, "1.0.4", "", "requested"));
        check("apkmirror resolve requested: the SECONDARY release is not the plain version", has(t, "does not list version 1.0.4"), msg(t));
        t = err(() -> MorpheHelper.resolve("apkmirror", PKG, "7.7.7", "", "requested"));
        check("apkmirror resolve requested: an unknown version names the newest it lists", has(t, "does not list version 7.7.7") && has(t, "newest it lists"), msg(t));

        mirror.on("GET /apk/example-org/example-app/example-app-1-0-3-release/", 403, "text/html", fx("apkmirror-challenge.html"));
        t = err(() -> MorpheHelper.resolve("apkmirror", PKG, "1.0.3", "arm64-v8a", "requested"));
        check("apkmirror resolve: Cloudflare's challenge on the release page is a NeedsBrowser carrying the release page",
                t instanceof MorpheHelper.NeedsBrowser && ((MorpheHelper.NeedsBrowser) t).page.endsWith("/example-app-1-0-3-release/") && has(t, "blocked") && "apkmirror".equals(((MorpheHelper.NeedsBrowser) t).source), msg(t));
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-3-release/", "apkmirror-release.html");
        mirror.on("GET /apk/example-org/example-app/example-app-1-0-3-release/example-app-1-0-3-android-apk-download/", 200, "text/html", fx("apkmirror-challenge.html"));
        t = err(() -> MorpheHelper.resolve("apkmirror", PKG, "1.0.3", "arm64-v8a", "requested"));
        check("apkmirror resolve: a challenge page answered with 200 on a later step is a NeedsBrowser too", t instanceof MorpheHelper.NeedsBrowser && has(t, "browser verification"), msg(t));
        mirror.page("GET /apk/example-org/example-app/example-app-1-0-3-release/example-app-1-0-3-android-apk-download/", "apkmirror-noresults.html");
        t = err(() -> MorpheHelper.resolve("apkmirror", PKG, "1.0.3", "arm64-v8a", "requested"));
        check("apkmirror resolve: a variant page without a download button is a NeedsBrowser, not a trace", t instanceof MorpheHelper.NeedsBrowser && has(t, "no direct download"), msg(t));

        mirrorRoutes();
        mirror.routes.remove("GET /apk/example-org/example-app/");
        mirror.hits.clear();
        JSONObject viaAmazon = MorpheHelper.versions("apkmirror", PKG);
        check("apkmirror versions: an app page that is gone is skipped, another app's page is checked and rejected, the right one is used",
                mirror.hit("GET /apk/other-dev/other-app/") && mirror.hit("GET /apk/example-org/example-app-amazon-fire-tablet-version/") && versionNames(viaAmazon).contains("9.9.9"), String.valueOf(mirror.hits) + versionNames(viaAmazon));
        mirror.page(MIRROR_SEARCH, "apkmirror-noresults.html");
        t = err(() -> MorpheHelper.versions("apkmirror", PKG));
        check("apkmirror: 'No results found' is 'does not list'", has(t, "APKMirror does not list"), msg(t));
        mirror.on(MIRROR_SEARCH, 200, "text/html", "<html><body><a href=\"/apk/other-dev/other-app/\">x</a></body></html>");
        t = err(() -> MorpheHelper.versions("apkmirror", PKG));
        check("apkmirror: search hits that are all other apps are 'does not list'", has(t, "APKMirror does not list"), msg(t));
        mirrorRoutes();
        mirror.on("GET /apk/example-org/example-app/", 200, "text/html", "<html><body><a href=\"https://play.google.com/store/apps/details?id=com.example.app\">p</a><p>no releases at all</p></body></html>");
        t = err(() -> MorpheHelper.versions("apkmirror", PKG));
        check("apkmirror: an app page with no release links is 'changed its page format'", has(t, "APKMirror changed its page format, open it in the browser"), msg(t));
        mirror.on(MIRROR_SEARCH, 403, "text/html", fx("apkmirror-challenge.html"));
        t = err(() -> MorpheHelper.versions("apkmirror", PKG));
        check("apkmirror versions: a challenge on the search itself is a NeedsBrowser with the search page", t instanceof MorpheHelper.NeedsBrowser && ((MorpheHelper.NeedsBrowser) t).page.startsWith("https://www.apkmirror.com/?post_type=app_release"), msg(t));
        // older releases: the app page shows only the latest ones, "See more uploads" has the rest in pages
        mirrorRoutes();
        mirror.page("GET /apk/example-org/example-app/", "apkmirror-app-more.html");
        mirror.page("GET /uploads/?appcategory=example-app", "apkmirror-uploads-1.html");
        mirror.page("GET /uploads/page/2/?appcategory=example-app", "apkmirror-uploads-2.html");
        JSONObject more = MorpheHelper.versions("apkmirror", PKG);
        check("apkmirror versions: the uploads pages add the older releases (once each, only this app's)", versionNames(more).containsAll(Arrays.asList("1.0.3", "1.0.2", "0.9.0", "0.8.0", "0.7.0")) && !versionNames(more).contains("9.9.8") && versionNames(more).stream().filter(x -> x.equals("1.0.3")).count() == 1, String.valueOf(versionNames(more)));
        check("apkmirror versions: it stops when a page cannot be read (page 3 is missing)", mirror.hit("GET /uploads/page/3/?appcategory=example-app"));
        mirror.hits.clear();
        MorpheHelper.resolve("apkmirror", PKG, "1.0.3", "arm64-v8a", "requested");
        check("apkmirror resolve: a version the app page already lists reads no uploads page", !mirror.hit("GET /uploads/?appcategory=example-app"), String.valueOf(mirror.hits));
        mirror.page("GET /apk/example-org/example-app/example-app-0-8-0-release/", "apkmirror-release.html");
        mirror.page("GET /apk/example-org/example-app/example-app-0-8-0-release/example-app-0-8-0-android-apk-download/", "apkmirror-variant.html");
        mirror.page("GET /apk/example-org/example-app/example-app-0-8-0-release/example-app-0-8-0-android-apk-download/download/", "apkmirror-download.html");
        mirror.hits.clear();
        Throwable old08 = err(() -> MorpheHelper.resolve("apkmirror", PKG, "0.8.0", "arm64-v8a", "requested"));
        check("apkmirror resolve: an older version is looked for in the uploads pages, and read from its release page", mirror.hit("GET /uploads/?appcategory=example-app") && mirror.hit("GET /uploads/page/2/?appcategory=example-app") && !mirror.hit("GET /uploads/page/3/?appcategory=example-app") && mirror.hit("GET /apk/example-org/example-app/example-app-0-8-0-release/"), String.valueOf(mirror.hits) + " " + msg(old08));
        mirror.on("GET /uploads/?appcategory=example-app", 403, "text/html", fx("apkmirror-challenge.html"));
        mirror.hits.clear();
        Throwable blocked = err(() -> MorpheHelper.resolve("apkmirror", PKG, "0.8.0", "arm64-v8a", "requested"));
        check("apkmirror resolve: uploads pages that are blocked leave the plain 'does not list' answer", has(blocked, "does not list version 0.8.0"), msg(blocked));
        check("version names: the build flavour inside the name (Gboard's -release-arm64-v8a) is the same release", MorpheHelper.versionNameEquals("18.0.3.954559732", "18.0.3.954559732-release-arm64-v8a", false) && MorpheHelper.versionNameEquals("18.0.3.954559732-release-arm64-v8a", "18.0.3.954559732-release-arm64-v8a", false) && !MorpheHelper.versionNameEquals("18.0.3.954559732", "18.0.4.954559732-release-arm64-v8a", false) && !MorpheHelper.versionNameEquals("18.0.3.954559732", "18.0.3.954559733-release-arm64-v8a", false));
        mirrorRoutes();
        JSONObject flavour = MorpheHelper.resolve("apkmirror", PKG, "1.0.3-release-arm64-v8a", "arm64-v8a", "requested");
        check("apkmirror resolve requested: a version named with its build flavour finds the release APKMirror lists without it", "1.0.3".equals(flavour.getString("version")), flavour.toString());
        check("apkmirror: the uploads page address for page n", "https://x.test/uploads/page/3/?appcategory=a".equals(MorpheHelper.mirrorUploadsPage("https://x.test/uploads/?appcategory=a", 3)));

    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Evozi (APKCube)
    // ------------------------------------------------------------------------------------------------------------------------------

    static void testEvozi() throws Exception {
        cube.reset();
        cube.page("GET /apk-downloader?url=com.example.app", "evozi-app.html");
        cube.page("GET /example-app/com.example.app/old-versions", "evozi-old-versions.html");
        JSONObject v = MorpheHelper.versions("evozi", PKG);
        check("evozi versions: the page's JSON list - newest first, plain APK before its bundle", Arrays.asList("1.0.3", "1.0.3", "1.0.2").equals(versionNames(v)), String.valueOf(versionNames(v)));
        JSONObject apk = byVersion(v, "1.0.3", "apk"), x = byVersion(v, "1.0.3", "xapk"), old = byVersion(v, "1.0.2", null);
        check("evozi versions: code, ABI, size and SHA-256 per file", apk.getLong("versionCode") == 103 && "arm64-v8a".equals(apk.getString("abi")) && apk.getLong("size") == 4096 && digest("SHA-256", FILES.get("example-1.0.3.apk")).equals(apk.getString("sha256"))
                && "universal".equals(x.getString("abi")) && x.getLong("size") == 1500000 && "apks".equals(old.getString("format")) && "".equals(old.getString("sha256")));
        check("evozi versions: the page is the download page of that version, no direct address", apk.getString("page").equals(cube.base + "/example-app/com.example.app/download?version=1.0.3") && "".equals(apk.getString("url")));
        check("evozi versions: brackets and escapes inside a string do not break the list", versionsOf(v).length() == 3);
        check("evozi versions: the app's name comes from the page's heading, in UTF-8, without '- Old versions'", "Exämple Åpp".equals(v.getString("name")), v.getString("name"));
        Throwable t = err(() -> MorpheHelper.resolve("evozi", PKG, "1.0.2", "arm64-v8a", "requested"));
        check("evozi resolve: the file is behind Turnstile - a NeedsBrowser with the exact page", t instanceof MorpheHelper.NeedsBrowser && has(t, "captcha") && ((MorpheHelper.NeedsBrowser) t).page.endsWith("/download?version=1.0.2") && "evozi".equals(((MorpheHelper.NeedsBrowser) t).source), msg(t));
        t = err(() -> MorpheHelper.resolve("evozi", PKG, "5.5.5", "", "requested"));
        check("evozi resolve requested: a version that is not listed is 'does not list'", has(t, "does not list version 5.5.5"), msg(t));
        t = err(() -> MorpheHelper.resolve("evozi", PKG, null, "arm64-v8a", "latest"));
        check("evozi resolve latest: the newest version, then the browser", t instanceof MorpheHelper.NeedsBrowser && ((MorpheHelper.NeedsBrowser) t).page.endsWith("version=1.0.3"), msg(t));

        cube.reset();
        cube.page("GET /apk-downloader?url=com.paget96.batteryguru", "evozi-app-legacy.html");
        cube.page("GET /battery-guru-battery-health/com.paget96.batteryguru/old-versions", "evozi-old-versions-legacy.html");
        JSONObject lv = MorpheHelper.versions("evozi", "com.paget96.batteryguru");
        check("evozi versions: Helper's older page shape (versionName fields only) still lists", Arrays.asList("2.5.0.6", "2.5.0.5").equals(versionNames(lv)) && "apk".equals(lv.getJSONArray("versions").getJSONObject(0).getString("format")));
        cube.page("GET /battery-guru-battery-health/com.paget96.batteryguru/old-versions", "evozi-broken.html");
        t = err(() -> MorpheHelper.versions("evozi", "com.paget96.batteryguru"));
        check("evozi: a truncated payload is 'changed its page format'", has(t, "Evozi changed its page format, open it in the browser"), msg(t));
        cube.routes.remove("GET /battery-guru-battery-health/com.paget96.batteryguru/old-versions");
        t = err(() -> MorpheHelper.versions("evozi", "com.paget96.batteryguru"));
        check("evozi: the old-versions page gone is 'changed its page format'", has(t, "changed its page format"), msg(t));
        cube.page("GET /apk-downloader?url=com.paget96.batteryguru", "apkcombo-unrelated.html");
        t = err(() -> MorpheHelper.versions("evozi", "com.paget96.batteryguru"));
        check("evozi: a downloader page that links no download page is 'does not list'", has(t, "Evozi does not list"), msg(t));
    }

    static void testManualOnly() throws Exception {
        for (String id : new String[] {"mi9", "apkdownloader", "aurora", "play"}) {
            Throwable t = err(() -> MorpheHelper.versions(id, PKG));
            Throwable t2 = err(() -> MorpheHelper.resolve(id, PKG, "1.0.3", "", "requested"));
            check("manual only (" + id + "): versions and resolve say browser, with the page to open",
                    t instanceof MorpheHelper.NeedsBrowser && t2 instanceof MorpheHelper.NeedsBrowser && ((MorpheHelper.NeedsBrowser) t).page.equals(MorpheHelper.manualUrl(id, PKG, null)) && has(t2, "can only be used in the browser"), msg(t) + " / " + msg(t2));
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Fast Mode
    // ------------------------------------------------------------------------------------------------------------------------------

    static JSONArray ids(String... a) {
        JSONArray j = new JSONArray();
        for (String s : a) j.put(s);
        return j;
    }

    static void testFast() throws Exception {
        resetAll();
        mirrorRoutes();
        mirror.on(MIRROR_SEARCH, 403, "text/html", fx("apkmirror-challenge.html"));
        pure.on("POST /v3/get_app_update", 500, "text/plain", "boom");
        comboRoutes();
        aptoideRoutes();
        JSONObject r = MorpheHelper.fast(ids("apkmirror", "uptodown", "apkpure", "apkcombo", "aptoide", "mi9"), PKG, "1.0.2", "arm64-v8a", "requested");
        JSONArray tried = r.getJSONArray("tried");
        check("fast: walks past a source behind a challenge and one that fails, takes the first that works", "apkcombo".equals(r.getString("source")) && "1.0.2".equals(r.getString("version")) && r.getBoolean("exact"));
        check("fast: 'tried' says what each source answered, in order, only the ones that can download", tried.length() == 3 && "apkmirror".equals(tried.getJSONObject(0).getString("source")) && !tried.getJSONObject(0).getBoolean("ok")
                && tried.getJSONObject(0).getString("error").contains("blocked") && "apkpure".equals(tried.getJSONObject(1).getString("source")) && tried.getJSONObject(1).getString("error").contains("HTTP 500")
                && "apkcombo".equals(tried.getJSONObject(2).getString("source")) && tried.getJSONObject(2).getBoolean("ok") && "".equals(tried.getJSONObject(2).getString("error")));
        check("fast: sources that need the browser are not even asked (Uptodown, Mi9)", utd.hits.isEmpty());
        check("fast: the order given is the order walked (Aptoide, after APKCombo, was never asked)", apt.hits.isEmpty());
        JSONObject r2 = MorpheHelper.fast(ids("aptoide", "apkcombo"), PKG, "1.0.3", "arm64-v8a", "requested");
        check("fast: the first one is used when it works", "aptoide".equals(r2.getString("source")) && r2.getJSONArray("tried").length() == 1 && r2.getJSONArray("tried").getJSONObject(0).getBoolean("ok"));

        Throwable t = err(() -> MorpheHelper.fast(ids("aptoide", "apkcombo"), PKG, "9.9.9", "arm64-v8a", "requested"));
        check("fast: nothing found is a FastFailed with the reasons, source by source", t instanceof MorpheHelper.FastFailed && has(t, "No source could provide", "Aptoide: ", "APKCombo: ", "does not list version 9.9.9")
                && ((MorpheHelper.FastFailed) t).tried.length() == 2, msg(t));
        t = err(() -> MorpheHelper.fast(ids("aptoide", "apkcombo"), PKG, "9.0.0", "arm64-v8a", "latest"));
        check("fast latest: a source whose newest build is older than the wanted one is not a 'latest' (Helper)", t instanceof MorpheHelper.FastFailed && has(t, "is older than the requested 9.0.0"), msg(t));
        JSONObject lat = MorpheHelper.fast(ids("aptoide"), PKG, "1.0.0", "arm64-v8a", "latest");
        check("fast latest: a newer build than the wanted one counts", "1.0.3".equals(lat.getString("version")) && lat.getBoolean("newer"));
        JSONObject lat2 = MorpheHelper.fast(ids("aptoide"), PKG, "", "arm64-v8a", "latest");
        check("fast latest: with no version wanted the newest is taken", "1.0.3".equals(lat2.getString("version")));
        t = err(() -> MorpheHelper.fast(ids("uptodown", "mi9", "play", "nope"), PKG, "1.0.3", "", "requested"));
        check("fast: with only browser sources enabled it says so", t instanceof MorpheHelper.FastFailed && has(t, "No enabled source can download without a browser"), msg(t));
        t = err(() -> MorpheHelper.fast(new JSONArray(), PKG, "1.0.3", "", "requested"));
        check("fast: no sources at all is the same message", t instanceof MorpheHelper.FastFailed, msg(t));
        t = err(() -> MorpheHelper.fast(ids("aptoide"), PKG, "1.0.3", "", "ask"));
        check("fast: the policy must be requested or latest", has(t, "unknown version policy"), msg(t));
        t = err(() -> MorpheHelper.fast(ids("aptoide"), "bad pkg", "1.0.3", "", "requested"));
        check("fast: a bad package name is refused before any request", has(t, "not a package name"), msg(t));
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Inspecting files
    // ------------------------------------------------------------------------------------------------------------------------------

    static File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), data);
        return f;
    }

    static void testInspect() throws Exception {
        File dir = tmpDir("inspect");
        JSONObject a = MorpheHelper.inspect(write(dir, "x.apk", apk(PKG, 103, "1.0.3")));
        check("inspect apk: package, version name and code, format, ABIs from lib/", a.getBoolean("ok") && PKG.equals(a.getString("pkg")) && "1.0.3".equals(a.getString("versionName")) && a.getLong("versionCode") == 103 && "apk".equals(a.getString("format"))
                && a.getJSONArray("abis").length() == 1 && "arm64-v8a".equals(a.getJSONArray("abis").getString(0)) && a.getLong("splits") == 0 && "".equals(a.getString("error")));
        check("inspect apk: size, SHA-256 and path", a.getLong("size") == new File(dir, "x.apk").length() && digest("SHA-256", Files.readAllBytes(new File(dir, "x.apk").toPath())).equals(a.getString("sha256")) && a.getString("path").endsWith("x.apk"));
        JSONObject big = MorpheHelper.inspect(write(dir, "big.apk", apk(PKG, 5, "5.0", null, 1, false, null)));
        check("inspect apk: versionCodeMajor makes the 64-bit build number", big.getBoolean("ok") && big.getLong("versionCode") == ((1L << 32) | 5L), String.valueOf(big.optLong("versionCode")));
        JSONObject ref = MorpheHelper.inspect(write(dir, "ref.apk", apk(PKG, 7, "x", null, 0, true, null)));
        check("inspect apk: a version name that is a resource reference is not guessed (empty), the rest is read", ref.getBoolean("ok") && "".equals(ref.getString("versionName")) && ref.getLong("versionCode") == 7 && ref.getJSONArray("abis").length() == 0);
        JSONObject x = MorpheHelper.inspect(write(dir, "b.xapk", bundle("xapk", PKG, 103, "1.0.3")));
        check("inspect xapk: manifest.json says xapk; the base apk's manifest is read; splits counted; ABIs from the split names",
                x.getBoolean("ok") && "xapk".equals(x.getString("format")) && PKG.equals(x.getString("pkg")) && "1.0.3".equals(x.getString("versionName")) && x.getLong("versionCode") == 103 && x.getLong("splits") == 3 && "arm64-v8a".equals(x.getJSONArray("abis").getString(0)));
        JSONObject s = MorpheHelper.inspect(write(dir, "b.apks", bundle("apks", PKG, 103, "1.0.3")));
        check("inspect apks: toc.pb says apks; base.apk is the base", s.getBoolean("ok") && "apks".equals(s.getString("format")) && "1.0.3".equals(s.getString("versionName")) && s.getLong("splits") == 3);
        JSONObject m = MorpheHelper.inspect(write(dir, "b.apkm", bundle("apkm", PKG, 103, "1.0.3")));
        check("inspect apkm: info.json says apkm", m.getBoolean("ok") && "apkm".equals(m.getString("format")) && PKG.equals(m.getString("pkg")));
        JSONObject renamed = MorpheHelper.inspect(write(dir, "renamed.zip", bundle("xapk", PKG, 103, "1.0.3")));
        check("inspect: the kind comes from the content, not the file name", renamed.getBoolean("ok") && "xapk".equals(renamed.getString("format")));
        // a bundle without a file called base.apk: the base is the one whose manifest has no split attribute
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        put(z, "a_config.apk", apk(PKG, 55, "5.5", "config.en", 0, false, null), false);
        put(z, "b_main.apk", apk(PKG, 55, "5.5", null, 0, false, null), false);
        z.close();
        JSONObject nb = MorpheHelper.inspect(write(dir, "odd.apks", bo.toByteArray()));
        check("inspect bundle: with no base.apk the APK without a split attribute is the base", nb.getBoolean("ok") && "5.5".equals(nb.getString("versionName")) && nb.getLong("splits") == 2);
        // a bundle whose base manifest is damaged: the bundle's own manifest.json still names the app
        bo = new ByteArrayOutputStream();
        z = new ZipOutputStream(bo);
        put(z, "manifest.json", ("{\"xapk_version\":2,\"package_name\":\"" + PKG + "\",\"version_code\":\"42\",\"version_name\":\"4.2\",\"split_apks\":[{\"file\":\"base.apk\",\"id\":\"base\"}]}").getBytes(StandardCharsets.UTF_8), false);
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        ZipOutputStream iz = new ZipOutputStream(inner);
        put(iz, "AndroidManifest.xml", "this is not a compiled manifest".getBytes(StandardCharsets.UTF_8), false);
        iz.close();
        put(z, "base.apk", inner.toByteArray(), false);
        z.close();
        JSONObject dm = MorpheHelper.inspect(write(dir, "damaged.xapk", bo.toByteArray()));
        check("inspect xapk: a base manifest that cannot be read falls back to the bundle's manifest.json", dm.getBoolean("ok") && PKG.equals(dm.getString("pkg")) && "4.2".equals(dm.getString("versionName")) && dm.getLong("versionCode") == 42, dm.toString());
        JSONObject nz = MorpheHelper.inspect(write(dir, "page.apk", "<html>not a zip</html>".getBytes(StandardCharsets.UTF_8)));
        check("inspect: a file that is not a zip is ok:false with the reason", !nz.getBoolean("ok") && nz.getString("error").contains("not a zip") && "".equals(nz.getString("pkg")));
        bo = new ByteArrayOutputStream();
        z = new ZipOutputStream(bo);
        put(z, "readme.txt", "hi".getBytes(StandardCharsets.UTF_8), false);
        z.close();
        JSONObject ez = MorpheHelper.inspect(write(dir, "other.zip", bo.toByteArray()));
        check("inspect: a zip with no manifest and no APK is ok:false", !ez.getBoolean("ok") && ez.getString("error").contains("not an Android package"));
        bo = new ByteArrayOutputStream();
        z = new ZipOutputStream(bo);
        put(z, "AndroidManifest.xml", "garbage".getBytes(StandardCharsets.UTF_8), false);
        z.close();
        JSONObject gm = MorpheHelper.inspect(write(dir, "garbage.apk", bo.toByteArray()));
        check("inspect: an APK whose manifest is not compiled XML is ok:false, not an exception", !gm.getBoolean("ok") && gm.getString("error").length() > 0);
        check("inspect: a missing file is an IOException", err(() -> MorpheHelper.inspect(new File(dir, "missing.apk"))) instanceof IOException && err(() -> MorpheHelper.inspect(null)) instanceof IOException);
        String testApk = System.getProperty("testapk");
        if (testApk == null || !new File(testApk).isFile()) {
            System.out.println("skip inspect of the repo's own APK (no TEST_APK / bin/ADB_Application_Manager_Pro.apk)");
        } else {
            JSONObject real = MorpheHelper.inspect(new File(testApk));
            check("inspect: the repo's own APK (a real aapt2 manifest): package, a version, a build number, ABIs", real.getBoolean("ok") && real.getString("pkg").contains(".") && real.getLong("versionCode") > 0 && real.getString("versionName").length() > 0 && "apk".equals(real.getString("format")), real.toString());
            System.out.println("     " + real.getString("pkg") + " " + real.getString("versionName") + " (" + real.getLong("versionCode") + ") abis=" + real.getJSONArray("abis"));
        }
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    static void testScanFolder() throws Exception {
        File dir = tmpDir("scan");
        File sub = new File(dir, "Browser");
        sub.mkdirs();
        File deep = new File(sub, "deeper");
        deep.mkdirs();
        File a = write(dir, "method.latin_18.0.3_apkmirror.com.apkm", bundle("apkm", PKG, 103, "1.0.3"));
        File b = write(dir, "example.apk", apk(PKG, 101, "1.0.1"));
        File c = write(sub, "saved.xapk", bundle("xapk", PKG, 102, "1.0.2"));
        File d = write(dir, "other.apk", apk("com.other.app", 5, "5.0"));
        write(dir, "page.apk", "<html>not a zip</html>".getBytes(StandardCharsets.UTF_8));
        write(dir, "notes.txt", "hello".getBytes(StandardCharsets.UTF_8));
        write(dir, ".hidden.apk", apk(PKG, 9, "9.0"));
        write(deep, "toodeep.apk", apk(PKG, 8, "8.0"));
        long now = System.currentTimeMillis();
        a.setLastModified(now);
        c.setLastModified(now - 60000);
        b.setLastModified(now - 120000);
        d.setLastModified(now - 180000);
        JSONObject r = MorpheHelper.scanFolder(dir, PKG, 12);
        JSONArray it = r.getJSONArray("items");
        check("scan: the three files of the package are found (a bundle, a bundle in a subfolder, an APK), newest first", it.length() == 3 && it.getJSONObject(0).getString("name").endsWith(".apkm") && it.getJSONObject(1).getString("name").equals("saved.xapk") && it.getJSONObject(2).getString("name").equals("example.apk"), r.toString());
        check("scan: the package inside is read, so the file name does not matter", it.getJSONObject(0).getString("pkg").equals(PKG) && it.getJSONObject(0).getString("versionName").equals("1.0.3") && "apkm".equals(it.getJSONObject(0).getString("format")) && it.getJSONObject(0).getLong("splits") == 3);
        check("scan: the subfolder is named, a file in Downloads itself has no folder", "Browser".equals(it.getJSONObject(1).getString("folder")) && "".equals(it.getJSONObject(0).getString("folder")));
        check("scan: other apps are counted, web pages saved as .apk are unreadable, hidden files and deeper folders are left", r.getInt("other") == 1 && r.getInt("unreadable") == 1 && r.getInt("scanned") == 5, r.toString());
        check("scan: it does not compute SHA-256s (cheap)", !it.getJSONObject(0).has("sha256") && !it.getJSONObject(0).has("error"));
        JSONObject all = MorpheHelper.scanFolder(dir, "", 12);
        check("scan: without a package every readable file is listed", all.getJSONArray("items").length() == 4 && all.getInt("other") == 0);
        check("scan: the limit is kept", MorpheHelper.scanFolder(dir, "", 2).getJSONArray("items").length() == 2);
        check("scan: a folder that cannot be read is an empty list, not an error", MorpheHelper.scanFolder(new File(dir, "missing"), PKG, 12).getJSONArray("items").length() == 0);
        for (File f : deep.listFiles()) f.delete();
        deep.delete();
        for (File f : sub.listFiles()) f.delete();
        sub.delete();
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------------------------------------------------------------------

    static JSONObject resolved(String file, String version, long code, String wanted, long wantedCode, String policy) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("source", "aptoide");
        r.put("pkg", PKG);
        r.put("version", version);
        r.put("versionCode", code);
        r.put("format", "apk");
        r.put("url", apt.base + "/files/" + file);
        r.put("sha256", "");
        r.put("md5", "");
        r.put("wanted", wanted);
        r.put("wantedCode", wantedCode);
        r.put("policy", policy);
        r.put("referer", "");
        return r;
    }

    static final class P implements MorpheNet.Progress {
        volatile boolean cancel;
        volatile long done, total;
        volatile int calls;

        @Override
        public void onProgress(long d, long t) {
            done = d;
            total = t;
            calls++;
        }

        @Override
        public boolean cancelled() {
            return cancel;
        }
    }

    static void testDownload() throws Exception {
        resetAll();
        aptoideRoutes();
        File dir = tmpDir("dl");
        File sub = new File(dir, "not/yet/there");
        JSONObject res = MorpheHelper.resolve("aptoide", PKG, "1.0.3", "arm64-v8a", "requested");
        P p = new P();
        JSONObject d = MorpheHelper.download(res, sub, p);
        File got = new File(sub, "com.example.app_1.0.3.apk");
        check("download: saved as pkg_version.ext, the folder made, nothing else left", d.getBoolean("ok") && got.isFile() && Arrays.equals(new String[] {"com.example.app_1.0.3.apk"}, names(sub)) && d.getString("path").equals(got.getAbsolutePath()));
        check("download: the real manifest is what is reported, and the published MD5 counts as verified",
                PKG.equals(d.getString("pkg")) && "1.0.3".equals(d.getString("versionName")) && d.getLong("versionCode") == 103 && "apk".equals(d.getString("format")) && d.getBoolean("verified") && d.getLong("size") == got.length() && "aptoide".equals(d.getString("source")));
        check("download: the SHA-256 is of the saved file; progress ended at the whole size", digest("SHA-256", Files.readAllBytes(got.toPath())).equals(d.getString("sha256")) && p.done == got.length() && p.total == got.length() && p.calls > 0);
        check("download: a browser User-Agent and no Referer when the source gave none", apt.hitHeaders.get(apt.hits.indexOf("GET /files/example-1.0.3.apk")).get("user-agent").startsWith("Mozilla/5.0") && apt.hitHeaders.get(apt.hits.indexOf("GET /files/example-1.0.3.apk")).get("referer") == null);

        JSONObject withRef = resolved("example-1.0.3.apk", "1.0.3", 103, "1.0.3", 0, "requested");
        withRef.put("referer", "https://example.com/page");
        withRef.put("sha256", digest("SHA-256", FILES.get("example-1.0.3.apk")));
        JSONObject dr = MorpheHelper.download(withRef, sub, null);
        int ix = apt.hits.lastIndexOf("GET /files/example-1.0.3.apk");
        check("download: the Referer the source gave is sent, a published SHA-256 that fits counts as verified, a null Progress is fine", "https://example.com/page".equals(apt.hitHeaders.get(ix).get("referer")) && dr.getBoolean("verified"));
        JSONObject pureRes = resolved("example-1.0.3.apk", "1.0.3", 103, "1.0.3", 0, "requested");
        pureRes.put("source", "apkpure");
        MorpheHelper.download(pureRes, sub, null);
        check("download: APKPure files are asked for with the APKPure app's user agent", "APKPure/3.19.39 (Aegon)".equals(apt.hitHeaders.get(apt.hits.lastIndexOf("GET /files/example-1.0.3.apk")).get("user-agent")));
        check("download: a file already there under that name is replaced", new File(sub, "com.example.app_1.0.3.apk").isFile() && names(sub).length == 1);

        File d2 = tmpDir("refuse");
        JSONObject bad = resolved("example-1.0.3.apk", "1.0.3", 103, "1.0.3", 0, "requested");
        bad.put("md5", "00000000000000000000000000000000");
        Throwable t = err(() -> MorpheHelper.download(bad, d2, null));
        check("download: a different MD5 than the source published is refused and leaves nothing", has(t, "MD5") && names(d2).length == 0, msg(t) + Arrays.toString(names(d2)));
        JSONObject bad2 = resolved("example-1.0.3.apk", "1.0.3", 103, "1.0.3", 0, "requested");
        bad2.put("sha256", "ab" + digest("SHA-256", FILES.get("example-1.0.3.apk")).substring(2));
        t = err(() -> MorpheHelper.download(bad2, d2, null));
        check("download: a different SHA-256 than the source published is refused and leaves nothing", has(t, "SHA-256") && names(d2).length == 0, msg(t));
        JSONObject wrongPkg = resolved("other.apk", "1.0.3", 103, "1.0.3", 0, "requested");
        t = err(() -> MorpheHelper.download(wrongPkg, d2, null));
        check("download: a file of another package is refused with both names, and removed", has(t, "Package: requested com.example.app, found com.other.app") && names(d2).length == 0, msg(t));
        JSONObject wrongVer = resolved("example-1.0.3-wrongver.apk", "1.0.3", 103, "1.0.3", 0, "requested");
        t = err(() -> MorpheHelper.download(wrongVer, d2, null));
        check("download: the real version name must be the requested one", has(t, "Version: requested 1.0.3, found 9.9.9") && names(d2).length == 0, msg(t));
        JSONObject wrongCode = resolved("example-1.0.3-wrongcode.apk", "1.0.3", 0, "1.0.3", 103, "requested");
        t = err(() -> MorpheHelper.download(wrongCode, d2, null));
        check("download: the requested build number must be the file's (build mismatch)", has(t, "Build mismatch", "requested build 103", "found 999") && names(d2).length == 0, msg(t));
        JSONObject claimed = resolved("example-1.0.3-wrongcode.apk", "1.0.3", 103, "", 0, "latest");
        t = err(() -> MorpheHelper.download(claimed, d2, null));
        check("download: a build number the source claimed must be the file's", has(t, "Build mismatch", "said build 103", "build 999") && names(d2).length == 0, msg(t));
        JSONObject older = resolved("example-1.0.2.apk", "1.0.2", 102, "1.0.3", 0, "latest");
        t = err(() -> MorpheHelper.download(older, d2, null));
        check("download: a 'latest' that turns out older than the requested version is refused (Helper deletes it too)", has(t, "is older than the requested 1.0.3") && names(d2).length == 0, msg(t));
        JSONObject newer = resolved("example-1.0.3.apk", "1.0.3", 103, "1.0.0", 0, "latest");
        check("download: a 'latest' newer than the requested version is accepted", MorpheHelper.download(newer, d2, null).getBoolean("ok"));
        for (File f : d2.listFiles()) f.delete();
        JSONObject html = resolved("page.html", "1.0.3", 0, "1.0.3", 0, "requested");
        t = err(() -> MorpheHelper.download(html, d2, null));
        check("download: a web page instead of a package is refused as not an Android package", has(t, "not a usable Android package") && names(d2).length == 0, msg(t));
        JSONObject gone = resolved("nothing-here.apk", "1.0.3", 0, "1.0.3", 0, "requested");
        t = err(() -> MorpheHelper.download(gone, d2, null));
        check("download: an HTTP error on the file is an IOException with the status", t instanceof MorpheNet.HttpError && ((MorpheNet.HttpError) t).status == 404 && names(d2).length == 0, msg(t));
        JSONObject noUrl = resolved("x", "1.0.3", 0, "1.0.3", 0, "requested");
        noUrl.put("url", "");
        t = err(() -> MorpheHelper.download(noUrl, d2, null));
        check("download: no address says to use the browser", has(t, "no download address") && err(() -> MorpheHelper.download(null, d2, null)) instanceof IOException, msg(t));
        JSONObject plainHttp = resolved("example-1.0.3.apk", "1.0.3", 0, "1.0.3", 0, "requested");
        plainHttp.put("url", "http://example.com/x.apk");
        t = err(() -> MorpheHelper.download(plainHttp, d2, null));
        check("download: plain http to the internet is refused by the network layer", has(t, "only https"), msg(t));
        JSONObject sneaky = resolved("example-1.0.3.apk", "../../1.0.3/x", 103, "1.0.3", 0, "requested");
        MorpheHelper.download(sneaky, d2, null);
        boolean inside = true;
        for (File f : d2.listFiles()) inside &= f.getParentFile().equals(d2);
        check("download: a version with slashes cannot leave the folder", inside && !new File(d2.getParentFile(), "x.apk").exists() && names(d2).length >= 1);

        JSONObject xr = resolved("example.xapk", "1.0.3", 103, "1.0.3", 0, "requested");
        xr.put("format", "apk");
        JSONObject xd = MorpheHelper.download(xr, d2, null);
        check("download: a bundle the source called an apk is named by what it is (xapk), splits and ABIs reported", "xapk".equals(xd.getString("format")) && xd.getString("path").endsWith("com.example.app_1.0.3.xapk") && xd.getLong("splits") == 3 && "arm64-v8a".equals(xd.getJSONArray("abis").getString(0)) && "1.0.3".equals(xd.getString("versionName")));
        JSONObject sr = resolved("example.apks", "1.0.3", 103, "1.0.3", 0, "requested");
        check("download: an .apks bundle is kept as apks", "apks".equals(MorpheHelper.download(sr, d2, null).getString("format")));
        JSONObject mr = resolved("example.apkm", "1.0.3", 103, "1.0.3", 0, "requested");
        check("download: an .apkm bundle is kept as apkm", "apkm".equals(MorpheHelper.download(mr, d2, null).getString("format")));

        // atomic: while the transfer is held the half file is a .part and the final name does not exist
        File d3 = tmpDir("atomic");
        byte[] bytes = FILES.get("example-1.0.3.apk");
        final CountDownLatch hold = new CountDownLatch(1);
        Resp slow = new Resp();
        slow.type = "application/octet-stream";
        slow.body = bytes;
        slow.hold = hold;
        slow.holdAfter = bytes.length / 2;
        apt.routes.put("GET /files/slow.apk", slow);
        JSONObject sl = resolved("slow.apk", "1.0.3", 103, "1.0.3", 0, "requested");
        final JSONObject[] box = new JSONObject[1];
        final Throwable[] ebox = new Throwable[1];
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    box[0] = MorpheHelper.download(sl, d3, null);
                } catch (Throwable e) {
                    ebox[0] = e;
                }
            }
        });
        th.start();
        long until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until && !new File(d3, "com.example.app_1.0.3.dl.part").exists()) Thread.sleep(20);
        String[] during = names(d3);
        check("download: while it runs there is only a .part file, never the final name", Arrays.equals(new String[] {"com.example.app_1.0.3.dl.part"}, during), Arrays.toString(during));
        hold.countDown();
        th.join(10000);
        check("download: when whole and checked it appears under its name, with no .part or .dl left", box[0] != null && Arrays.equals(new String[] {"com.example.app_1.0.3.apk"}, names(d3)), Arrays.toString(names(d3)) + (ebox[0] == null ? "" : msg(ebox[0])));

        // cancel in the middle
        File d4 = tmpDir("cancel");
        final CountDownLatch hold2 = new CountDownLatch(1);
        Resp slow2 = new Resp();
        slow2.type = "application/octet-stream";
        slow2.body = bytes;
        slow2.hold = hold2;
        slow2.holdAfter = bytes.length / 2;
        apt.routes.put("GET /files/slow2.apk", slow2);
        JSONObject sl2 = resolved("slow2.apk", "1.0.3", 103, "1.0.3", 0, "requested");
        final P cp = new P();
        final Throwable[] cbox = new Throwable[1];
        Thread th2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    MorpheHelper.download(sl2, d4, cp);
                } catch (Throwable e) {
                    cbox[0] = e;
                }
            }
        });
        th2.start();
        until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until && !new File(d4, "com.example.app_1.0.3.dl.part").exists()) Thread.sleep(20);
        cp.cancel = true;
        hold2.countDown();
        th2.join(10000);
        check("download: Cancel stops it with 'cancelled' and leaves no file at all", cbox[0] instanceof IOException && "cancelled".equals(cbox[0].getMessage()) && names(d4).length == 0, msg(cbox[0]) + Arrays.toString(names(d4)));

        for (File x : new File[] {dir, d2, d3, d4}) deleteTree(x);
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
