package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The patch sources of the Morphe Patcher tab (MorpheStore) against a local server and temp folders: the address a person types (parseInput),
 * versions, the manifest of a bundle and the metadata file, adding a source from GitHub / GitLab / a metadata address / a file (main, master,
 * dev, the releases fallback, every way it can fail and leave nothing behind), checking and updating (the old bundle survives a download
 * that dies), the built-in source, the saved list across a restart, a damaged or hostile sources.json, ids and names that try to leave the
 * folder, and the community finder's cache (fresh, cached, refresh, stale on failure, nothing to fall back on).
 */
public class MorpheStoreTest {
    static int checks = 0, fails = 0;

    static void check(String name, boolean ok) { check(name, ok, ""); }

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            fails++;
            System.out.println("FAIL " + name + (detail.isEmpty() ? "" : " :: " + detail));
        }
    }

    interface Section { void run() throws Exception; }

    static void section(String name, Section s) {
        try {
            s.run();
        } catch (Throwable t) {
            checks++;
            fails++;
            System.out.println("FAIL " + name + " crashed: " + t);
            t.printStackTrace(System.out);
        }
    }

    // ---------------------------------------------------------------- a local server with routes and a hit log

    static final class Route {
        int status = 200;
        String type = "application/json";
        byte[] body = new byte[0];
    }

    static final Map<String, Route> routes = new ConcurrentHashMap<String, Route>();
    static final List<String> hits = Collections.synchronizedList(new ArrayList<String>());
    static HttpServer srv;
    static String base;
    static File tmp;
    static int dirCounter = 0;

    static void route(String path, int status, String type, byte[] body) {
        Route r = new Route();
        r.status = status;
        r.type = type;
        r.body = body;
        routes.put(path, r);
    }

    static void json(String path, String body) { route(path, 200, "application/json", body.getBytes(StandardCharsets.UTF_8)); }

    static void bytes(String path, byte[] body) { route(path, 200, "application/octet-stream", body); }

    static void status(String path, int code) { route(path, code, "text/plain", ("status " + code).getBytes(StandardCharsets.UTF_8)); }

    static int hitCount(String path) {
        int n = 0;
        synchronized (hits) { for (String h : hits) if (h.equals(path)) n++; }
        return n;
    }

    static void reset() {
        routes.clear();
        hits.clear();
        MorpheStore.rawBaseOverride = base + "/raw";
        MorpheStore.githubApiOverride = base + "/api";
        MorpheStore.gitlabBaseOverride = base + "/gl";
        MorpheStore.communityUrlOverride = base + "/community.json";
    }

    static void reply(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            OutputStream o = ex.getResponseBody();
            o.write(body);
            o.close();
        }
        ex.close();
    }

    /** A server that sends the first part of a file, then waits: for a download that is cut off, and one that takes its time. */
    static final class Slow {
        final HttpServer server;
        final String url;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Slow(final byte[] body, final int firstPart) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", new HttpHandler() {
                public void handle(HttpExchange ex) throws IOException {
                    ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    ex.sendResponseHeaders(200, body.length);
                    OutputStream o = ex.getResponseBody();
                    try {
                        o.write(body, 0, firstPart);
                        o.flush();
                        started.countDown();
                        release.await(20, TimeUnit.SECONDS);
                        o.write(body, firstPart, body.length - firstPart);
                    } catch (Exception ignored) { /* the client is gone */ }
                    try { o.close(); } catch (IOException ignored) {}
                    ex.close();
                }
            });
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow.mpp";
        }
    }

    // ---------------------------------------------------------------- builders

    static String q(String s) { return JSONObject.quote(s); }

    static String meta(String version, String dl, String created, String desc) {
        return "{\"version\":" + q(version) + ",\"download_url\":" + q(dl) + ",\"created_at\":" + q(created) + ",\"description\":" + q(desc)
                + ",\"signature_download_url\":\"\"}";
    }

    static String sha(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    /** A bundle: a jar with a manifest (a continuation line in the license, "Contact: na"), classes.dex and / or a class, and some padding. */
    static byte[] mpp(String name, String version, boolean dex, boolean classes, String patcher, int padKb) throws IOException {
        String mf = "Manifest-Version: 1.0\r\n" + (name == null ? "" : "Name: " + name + "\r\n") + "Description: Patches for tests\r\n"
                + (version == null ? "" : "Version: " + version + "\r\n") + "Source: git@github.com:o/r.git\r\nAuthor: tester\r\nContact: na\r\n"
                + "Website: https://example.org\r\nLicense: GNU General Public License v3.0, with additional GPL section 7 \r\n requirements\r\n"
                + (patcher == null ? "" : "Patcher-Version: " + patcher + "\r\n") + "\r\n";
        return zip(mf, dex, classes, padKb, version);
    }

    static byte[] zip(String manifest, boolean dex, boolean classes, int padKb, String salt) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        if (manifest != null) put(z, "META-INF/MANIFEST.MF", manifest.getBytes(StandardCharsets.UTF_8));
        if (dex) put(z, "classes.dex", ("dex\n035\0" + salt).getBytes(StandardCharsets.UTF_8));
        if (classes) put(z, "app/morphe/Patch.class", new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });
        put(z, "assets/readme.txt", ("readme " + salt).getBytes(StandardCharsets.UTF_8));
        if (padKb > 0) {
            byte[] pad = new byte[padKb * 1024];
            new java.util.Random(7).nextBytes(pad);
            put(z, "assets/pad.bin", pad);
        }
        z.close();
        return bos.toByteArray();
    }

    static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    static byte[] good(String version) throws IOException { return mpp("Test Patches", version, true, false, "1.14.0", 0); }

    static File newBase() {
        File d = new File(tmp, "base" + (dirCounter++));
        d.mkdirs();
        return d;
    }

    static File write(String name, byte[] data) throws IOException {
        File f = new File(tmp, name);
        Files.write(f.toPath(), data);
        return f;
    }

    static String s(JSONObject o, String key) { return MorpheStore.str(o, key); }

    static boolean readOnly(File f) throws IOException {
        try {
            Set<PosixFilePermission> p = Files.getPosixFilePermissions(f.toPath());
            return !p.contains(PosixFilePermission.OWNER_WRITE) && !p.contains(PosixFilePermission.GROUP_WRITE) && !p.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (UnsupportedOperationException e) {
            return !f.canWrite();
        }
    }

    static List<String> names(File dir) {
        String[] n = dir.list();
        List<String> l = n == null ? new ArrayList<String>() : new ArrayList<String>(Arrays.asList(n));
        Collections.sort(l);
        return l;
    }

    static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }

    /** Runs a call that must fail with an IOException; its message, or null when it did not fail. */
    interface Call { void run() throws Exception; }

    static String failure(Call c) {
        try {
            c.run();
            return null;
        } catch (IOException e) {
            return e.getMessage() == null ? "" : e.getMessage();
        } catch (Exception e) {
            return "OTHER " + e;
        }
    }

    static boolean has(String text, String part) { return text != null && text.contains(part); }

    // ---------------------------------------------------------------- the checks

    public static void main(String[] args) throws Exception {
        tmp = Files.createTempDirectory("morphestore-").toFile();
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setExecutor(Executors.newCachedThreadPool());
        srv.createContext("/", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                String path = ex.getRequestURI().getRawPath();
                hits.add(path);
                Route r = routes.get(path);
                if (r == null) reply(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                else reply(ex, r.status, r.type, r.body);
            }
        });
        srv.start();
        base = "http://127.0.0.1:" + srv.getAddress().getPort();
        reset();

        section("parseInput accepted", new Section() { public void run() throws Exception { parseAccepted(); } });
        section("parseInput rejected", new Section() { public void run() throws Exception { parseRejected(); } });
        section("compareVersions", new Section() { public void run() throws Exception { versions(); } });
        section("parseBundleJson", new Section() { public void run() throws Exception { bundleJson(); } });
        section("readManifest", new Section() { public void run() throws Exception { manifests(); } });
        section("built-in source", new Section() { public void run() throws Exception { builtIn(); } });
        section("addRemote github", new Section() { public void run() throws Exception { addGithub(); } });
        section("addRemote releases fallback", new Section() { public void run() throws Exception { addReleases(); } });
        section("addRemote failures leave nothing", new Section() { public void run() throws Exception { addFailures(); } });
        section("addRemote duplicates, names, cancel", new Section() { public void run() throws Exception { addMisc(); } });
        section("addRemote cancel", new Section() { public void run() throws Exception { addCancel(); } });
        section("addRemote gitlab", new Section() { public void run() throws Exception { addGitlab(); } });
        section("addRemote metadata url", new Section() { public void run() throws Exception { addUrl(); } });
        section("addLocal", new Section() { public void run() throws Exception { addLocal(); } });
        section("checkUpdate and update", new Section() { public void run() throws Exception { updates(); } });
        section("pre-release channel", new Section() { public void run() throws Exception { channels(); } });
        section("a download that dies", new Section() { public void run() throws Exception { killed(); } });
        section("list while downloading", new Section() { public void run() throws Exception { concurrent(); } });
        section("persistence", new Section() { public void run() throws Exception { persistence(); } });
        section("damaged and hostile sources.json", new Section() { public void run() throws Exception { damaged(); } });
        section("ids and names that try to leave the folder", new Section() { public void run() throws Exception { traversal(); } });
        section("community", new Section() { public void run() throws Exception { community(); } });

        srv.stop(0);
        deleteAll(tmp);
        System.out.println(fails == 0 ? "ALL PASS (" + checks + " checks)" : "FAILURES: " + fails + " of " + checks + " checks");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- addresses

    static void expectRepo(String input, String host, String owner, String repo, String branch) {
        JSONObject r = MorpheStore.parseInput(input);
        boolean ok = r.optBoolean("ok") && host.equals(s(r, "host")) && owner.equals(s(r, "owner")) && repo.equals(s(r, "repo"))
                && (branch == null ? !r.has("branch") : branch.equals(s(r, "branch"))) && (owner + "/" + repo).equals(s(r, "slug"));
        check("accepts " + input, ok, r.toString());
    }

    static void expectUrl(String input, String meta) {
        JSONObject r = MorpheStore.parseInput(input);
        check("accepts " + input + " as a metadata address", r.optBoolean("ok") && "url".equals(s(r, "host")) && meta.equals(s(r, "metadataUrl")), r.toString());
    }

    static void parseAccepted() throws Exception {
        expectRepo("github.com/owner/repo", "github", "owner", "repo", null);
        expectRepo("https://github.com/owner/repo", "github", "owner", "repo", null);
        expectRepo("  https://github.com/Owner/Repo.git  ", "github", "Owner", "Repo", null);
        expectRepo("https://github.com/owner/repo/", "github", "owner", "repo", null);
        expectRepo("https://github.com/owner/repo/tree/dev", "github", "owner", "repo", "dev");
        expectRepo("https://github.com/owner/repo/tree/dev/src/x", "github", "owner", "repo", "dev");
        expectRepo("https://github.com/owner/repo/releases/tag/v1.0", "github", "owner", "repo", null);
        expectRepo("https://github.com/owner/repo?tab=readme#top", "github", "owner", "repo", null);
        expectRepo("HTTPS://GITHUB.COM/owner/repo", "github", "owner", "repo", null);
        expectRepo("www.github.com/owner/repo", "github", "owner", "repo", null);
        expectRepo("gitlab.com/owner/repo", "gitlab", "owner", "repo", null);
        expectRepo("https://gitlab.com/owner/repo.git", "gitlab", "owner", "repo", null);
        expectRepo("https://gitlab.com/owner/repo/-/tree/dev", "gitlab", "owner", "repo", "dev");
        expectRepo("https://gitlab.com/owner/repo/-/raw/main/patches-bundle.json", "gitlab", "owner", "repo", "main");
        expectRepo("https://gitlab.com/group/sub/repo/-/releases", "gitlab", "group/sub", "repo", null);
        expectRepo("https://raw.githubusercontent.com/owner/repo/dev/patches-bundle.json", "github", "owner", "repo", "dev");
        expectUrl("example.com/patches-bundle.json", "https://example.com/patches-bundle.json");
        expectUrl("https://example.org:8443/a/b/bundle.json?x=1", "https://example.org:8443/a/b/bundle.json?x=1");
        expectUrl("https://Example.COM/Path/Meta.JSON", "https://example.com/Path/Meta.JSON");
        expectUrl("example.com:8443/meta.json", "https://example.com:8443/meta.json");
        expectUrl("https://raw.githubusercontent.com/owner/repo/main/custom.json", "https://raw.githubusercontent.com/owner/repo/main/custom.json");
        expectUrl("http://127.0.0.1:8080/meta.json", "http://127.0.0.1:8080/meta.json");
        expectUrl("https://gitlab.com/owner/repo/-/raw/main/custom-bundle.json", "https://gitlab.com/owner/repo/-/raw/main/custom-bundle.json");

        JSONObject d = MorpheStore.parseInput("https://morphe.software/add-source?github=owner/repo");
        check("accepts the github deep link", d.optBoolean("ok") && "github".equals(s(d, "host")) && "owner".equals(s(d, "owner")) && "repo".equals(s(d, "repo")) && !d.has("name"), d.toString());
        d = MorpheStore.parseInput("https://morphe.software/add-source?github=owner/repo&name=Piko%20%26%20Co");
        check("the deep link's &name= is decoded", d.optBoolean("ok") && "Piko & Co".equals(s(d, "name")), d.toString());
        d = MorpheStore.parseInput("https://morphe.software/add-source?gitlab=owner/repo&name=GL+Name");
        check("accepts the gitlab deep link (+ is a space)", d.optBoolean("ok") && "gitlab".equals(s(d, "host")) && "GL Name".equals(s(d, "name")), d.toString());
        d = MorpheStore.parseInput("https://morphe.software/add-source?github=owner%2Frepo");
        check("an encoded slash in the deep link", d.optBoolean("ok") && "owner".equals(s(d, "owner")) && "repo".equals(s(d, "repo")), d.toString());
        d = MorpheStore.parseInput("github.com/owner/repo");
        check("a repository answer carries its web address", "https://github.com/owner/repo".equals(s(d, "url")));
    }

    static void expectRejected(String label, String input) {
        JSONObject r = MorpheStore.parseInput(input);
        check("rejects " + label, !r.optBoolean("ok") && s(r, "error").startsWith("Invalid source"), r.toString());
    }

    static void parseRejected() throws Exception {
        expectRejected("null", null);
        expectRejected("an empty string", "");
        expectRejected("spaces only", "   ");
        expectRejected("http (github)", "http://github.com/owner/repo");
        expectRejected("http (a metadata file)", "http://example.com/patches-bundle.json");
        expectRejected("ftp", "ftp://example.com/x.json");
        expectRejected("javascript:", "javascript:alert(1)");
        expectRejected("JavaScript: in capitals", "JavaScript:alert(1)");
        expectRejected("file:", "file:///sdcard/bundle.json");
        expectRejected("content:", "content://media/external/x.json");
        expectRejected("data:", "data:application/json,{}");
        expectRejected("mailto:", "mailto:a@b.c");
        expectRejected("an owner only", "github.com/owner");
        expectRejected("a host only", "github.com");
        expectRejected("the github root", "https://github.com/");
        expectRejected("a space in the owner", "github.com/ow ner/repo");
        expectRejected("a $ in the owner", "github.com/ow$ner/repo");
        expectRejected("a percent escape in the repo", "github.com/owner/re%70o");
        expectRejected("an owner of dots", "github.com/../repo");
        expectRejected("a repo of dots", "github.com/owner/..");
        expectRejected("a bad branch", "github.com/owner/repo/tree/de$v");
        expectRejected("a non-ASCII owner", "github.com/\u00f6/repo");
        expectRejected("an owner and repo with a slash trick", "github.com/own\\er/repo");
        expectRejected("a page that is not json", "https://example.com/readme.html");
        expectRejected("a bare host", "example.com");
        expectRejected("an empty path", "https://example.com/");
        expectRejected("user info", "https://user:pw@github.com/owner/repo");
        expectRejected("a look-alike host (user info trick)", "https://github.com@evil.example/owner/repo");
        expectRejected("a port on github", "https://github.com:8443/owner/repo");
        expectRejected("dot-dot in a metadata path", "https://example.com/a/../b.json");
        expectRejected("a gitlab owner only", "https://gitlab.com/owner");
        expectRejected("a deep link with no repository", "https://morphe.software/add-source");
        expectRejected("a deep link with both hosts", "https://morphe.software/add-source?github=a/b&gitlab=c/d");
        expectRejected("a deep link with an owner only", "https://morphe.software/add-source?github=owner");
        expectRejected("a deep link with three parts for github", "https://morphe.software/add-source?github=owner/repo/extra");
        expectRejected("a deep link with a bad owner", "https://morphe.software/add-source?github=ow$ner/repo");
        expectRejected("a deep link on another host", "https://evil.example/add-source?github=owner/repo");
        expectRejected("a deep link with a broken escape", "https://morphe.software/add-source?github=owner/repo&name=%zz");
        JSONObject r = MorpheStore.parseInput("https://example.com/readme.html");
        check("the generic refusal says what is accepted", "Invalid source URL: the address must point to a GitHub or GitLab repository, or to a .json metadata file".equals(s(r, "error")), s(r, "error"));
    }

    // ---------------------------------------------------------------- versions

    static void cmp(String a, String b, int expected) {
        int got;
        try { got = Integer.signum(MorpheStore.compareVersions(a, b)); } catch (Throwable t) { got = 99; }
        check("compareVersions(" + a + ", " + b + ") is " + expected, got == expected, "got " + got);
    }

    static void versions() throws Exception {
        cmp("1.46.0", "1.9.0", 1);
        cmp("1.9.0", "1.46.0", -1);
        cmp("1.0.0", "1.0.0", 0);
        cmp("v1.2.3", "1.2.3", 0);
        cmp("V2.0", "1.9.9", 1);
        cmp("1.0", "1.0.0", 0);
        cmp("1.0.0-dev.3", "1.0.0", -1);
        cmp("1.0.0", "1.0.0-rc1", 1);
        cmp("1.0.0-dev.3", "1.0.0-dev.10", -1);
        cmp("1.0.0-rc9", "1.0.0-rc10", -1);
        cmp("1.0.0-beta", "1.0.0-rc1", -1);
        cmp("1.0.0-beta", "1.0.0-beta.1", -1);
        cmp("2.0.0-dev.1", "1.99.99", 1);
        cmp("1.2.3+build5", "1.2.3", 0);
        cmp("1.2.3.4", "1.2.3", 1);
        cmp("1.02", "1.2", 0);
        cmp("99999999999999999999.0", "1.0", 1);
        cmp("abc", "1.0", -1);
        cmp("abc", "def", 0);
        cmp("", "", 0);
        cmp(null, "1.0", -1);
        cmp("1.0", null, 1);
        cmp(null, null, 0);
        cmp("1..2", "1.0.2", 0);
        cmp("-", "-", 0);
        boolean threw = false;
        try {
            MorpheStore.compareVersions("\u0000\uffff-\u202e..", "9\n9");
            MorpheStore.compareVersions(new String(new char[5000]).replace('\0', '9'), "1");
        } catch (Throwable t) { threw = true; }
        check("compareVersions never throws on odd text", !threw);
    }

    // ---------------------------------------------------------------- the metadata file

    static void expectBad(String label, final String text, String word) {
        String msg;
        try {
            MorpheStore.parseBundleJson(text);
            msg = null;
        } catch (IllegalArgumentException e) {
            msg = e.getMessage();
        } catch (Throwable t) {
            msg = "OTHER " + t;
        }
        check("parseBundleJson refuses " + label, msg != null && !msg.startsWith("OTHER") && msg.contains(word), String.valueOf(msg));
    }

    static void bundleJson() throws Exception {
        String live = "{\"created_at\":\"2026-10-06T11:40:51\",\"description\":\"## [1.46.0](https://x)\\n\\n### Bug Fixes\\n* fix\",\"download_url\":"
                + "\"https://github.com/MorpheApp/morphe-patches/releases/download/v1.46.0/patches-1.46.0.mpp\",\"signature_download_url\":\"\",\"version\":\"1.46.0\"}";
        JSONObject j = MorpheStore.parseBundleJson(live);
        check("the real file's shape: version", "1.46.0".equals(s(j, "version")));
        check("... download url", "https://github.com/MorpheApp/morphe-patches/releases/download/v1.46.0/patches-1.46.0.mpp".equals(s(j, "downloadUrl")));
        check("... created at kept as written", "2026-10-06T11:40:51".equals(s(j, "createdAt")));
        check("... the changelog text", s(j, "description").startsWith("## [1.46.0]") && s(j, "description").contains("\n"));
        check("... an empty signature stays empty", s(j, "signatureUrl").isEmpty() && s(j, "sha256").isEmpty());
        String h = sha("x".getBytes());
        j = MorpheStore.parseBundleJson("{\"version\":\"1\",\"download_url\":\"https://e.org/a.mpp\",\"sha256\":\"SHA256:" + h.toUpperCase() + "\",\"signature_download_url\":\"https://e.org/a.sig\"}");
        check("a sha256 (any case, with a sha256: prefix) comes back as 64 lower-case digits", h.equals(s(j, "sha256")));
        check("... and the signature address", "https://e.org/a.sig".equals(s(j, "signatureUrl")));
        j = MorpheStore.parseBundleJson("{\"version\":\"1\",\"download_url\":\"https://e.org/a.mpp\",\"signature_download_url\":\"http://e.org/a.sig\"}");
        check("a signature address that is not https is dropped", s(j, "signatureUrl").isEmpty());
        j = MorpheStore.parseBundleJson("{\"version\":1.5,\"download_url\":\"http://127.0.0.1:9/a.mpp\"}");
        check("a number as the version, and a loopback address for tests", "1.5".equals(s(j, "version")) && s(j, "downloadUrl").startsWith("http://127.0.0.1"));
        check("a missing description and date are empty", s(j, "description").isEmpty() && s(j, "createdAt").isEmpty());
        expectBad("no version", "{\"download_url\":\"https://e.org/a.mpp\"}", "version");
        expectBad("a blank version", "{\"version\":\"  \",\"download_url\":\"https://e.org/a.mpp\"}", "version");
        expectBad("a null version", "{\"version\":null,\"download_url\":\"https://e.org/a.mpp\"}", "version");
        expectBad("no download_url", "{\"version\":\"1\"}", "download_url");
        expectBad("a blank download_url", "{\"version\":\"1\",\"download_url\":\" \"}", "download_url");
        expectBad("an http download_url", "{\"version\":\"1\",\"download_url\":\"http://e.org/a.mpp\"}", "https");
        expectBad("a javascript: download_url", "{\"version\":\"1\",\"download_url\":\"javascript:alert(1)\"}", "https");
        expectBad("a file: download_url", "{\"version\":\"1\",\"download_url\":\"file:///sdcard/a.mpp\"}", "https");
        expectBad("a relative download_url", "{\"version\":\"1\",\"download_url\":\"/a.mpp\"}", "https");
        expectBad("text that is not JSON", "this is not json", "JSON");
        expectBad("a JSON array", "[]", "JSON");
        expectBad("null", null, "JSON");
        expectBad("a sha256 that is not hex", "{\"version\":\"1\",\"download_url\":\"https://e.org/a.mpp\",\"sha256\":\"xyz\"}", "sha256");
    }

    // ---------------------------------------------------------------- the manifest of a bundle

    static void manifests() throws Exception {
        File f = write("m1.mpp", mpp("Test Patches", "1.2.3", true, false, "1.14.0", 0));
        JSONObject m = MorpheStore.readManifest(f);
        check("manifest: name, version, description", m != null && "Test Patches".equals(s(m, "name")) && "1.2.3".equals(s(m, "version")) && "Patches for tests".equals(s(m, "description")));
        check("manifest: source, author, website, patcher version", m != null && "git@github.com:o/r.git".equals(s(m, "source")) && "tester".equals(s(m, "author"))
                && "https://example.org".equals(s(m, "website")) && "1.14.0".equals(s(m, "patcherVersion")));
        check("manifest: a 72-column continuation line is joined", m != null && "GNU General Public License v3.0, with additional GPL section 7 requirements".equals(s(m, "license")), m == null ? "" : s(m, "license"));
        check("manifest: \"Contact: na\" is no contact", m != null && s(m, "contact").isEmpty());
        check("manifest: classes.dex is seen, the entries are counted", m != null && m.optBoolean("hasDex") && !m.optBoolean("hasClasses") && m.optInt("entries") == 3, m == null ? "" : m.toString());
        m = MorpheStore.readManifest(write("m2.mpp", mpp("C", "1", false, true, null, 0)));
        check("manifest: class files without a dex", m != null && !m.optBoolean("hasDex") && m.optBoolean("hasClasses") && s(m, "patcherVersion").isEmpty());
        m = MorpheStore.readManifest(write("m3.mpp", zip("Manifest-Version: 1.0\r\nName: Only\r\n\r\n", false, false, 0, "x")));
        check("manifest: a zip with neither is still read", m != null && "Only".equals(s(m, "name")) && !m.optBoolean("hasDex") && !m.optBoolean("hasClasses"));
        m = MorpheStore.readManifest(write("m4.mpp", zip("Manifest-Version: 1.0\nname: lower\nLicense: long\n  text\n\nIgnored: after the blank line\n", true, false, 0, "x")));
        check("manifest: LF line ends, attribute names in any case", m != null && "lower".equals(s(m, "name")));
        check("manifest: a continuation keeps its own space, a blank line ends the section", m != null && "long text".equals(s(m, "license")));
        m = MorpheStore.readManifest(write("m5.mpp", zip(null, true, false, 0, "x")));
        check("manifest: a bundle without a manifest has empty attributes", m != null && s(m, "version").isEmpty() && m.optBoolean("hasDex"));
        check("manifest: a text file is no bundle", MorpheStore.readManifest(write("m6.mpp", "just text, not a zip".getBytes())) == null);
        check("manifest: an empty file is no bundle", MorpheStore.readManifest(write("m7.mpp", new byte[0])) == null);
        check("manifest: a missing file", MorpheStore.readManifest(new File(tmp, "nope.mpp")) == null);
        check("manifest: null and a folder", MorpheStore.readManifest(null) == null && MorpheStore.readManifest(tmp) == null);
        byte[] whole = mpp("T", "1", true, false, null, 64);
        check("manifest: a truncated zip", MorpheStore.readManifest(write("m8.mpp", Arrays.copyOf(whole, whole.length / 2))) == null);
    }

    // ---------------------------------------------------------------- the built-in source

    static void builtIn() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        JSONArray l = st.list();
        JSONObject b = l.optJSONObject(0);
        check("a new folder lists the built-in source, alone", l.length() == 1 && b != null && "morphe-official".equals(s(b, "id")));
        check("... named, from GitHub, marked built-in", "Morphe Patches".equals(s(b, "name")) && "remote".equals(s(b, "kind")) && "github".equals(s(b, "host"))
                && "MorpheApp/morphe-patches".equals(s(b, "repo")) && b.optBoolean("builtIn") && "https://github.com/MorpheApp/morphe-patches".equals(s(b, "url")));
        check("... not downloaded yet: no file, no version, no meta, patch count unknown", s(b, "file").isEmpty() && b.optLong("size") == 0 && s(b, "version").isEmpty()
                && b.isNull("meta") && b.optInt("patchCount") == -1);
        check("... enabled, no pre-release, no error, patcher fine", b.optBoolean("enabled") && !b.optBoolean("prerelease") && s(b, "error").isEmpty() && !b.optBoolean("needsNewerPatcher"));
        check("sources.json is written for a new folder", new File(dir, "sources.json").isFile());
        check("get() of an unknown id, and of null, is null", st.get("nope") == null && st.get(null) == null && st.bundleFile("morphe-official") == null);
        String m = failure(() -> st.remove("morphe-official"));
        check("the built-in source cannot be removed", "The pre-installed source cannot be removed".equals(m), m);
        m = failure(() -> st.rename("morphe-official", "Mine"));
        check("the built-in source cannot be renamed", has(m, "pre-installed source cannot be renamed"), m);
        check("... and is still there", st.list().length() == 1 && "Morphe Patches".equals(s(st.get("morphe-official"), "name")));

        // update() downloads it through the metadata on main
        byte[] a = good("1.0.0");
        bytes("/dl/morphe-1.0.0.mpp", a);
        json("/raw/MorpheApp/morphe-patches/main/patches-bundle.json", meta("1.0.0", base + "/dl/morphe-1.0.0.mpp", "2026-10-06T11:40:51", "## 1.0.0"));
        JSONObject u = st.update("morphe-official", null);
        check("update() downloads the built-in source", Arrays.equals(a, Files.readAllBytes(new File(s(u, "file")).toPath())) && "1.0.0".equals(s(u, "version")));
        check("... it stays built-in, named and first", u.optBoolean("builtIn") && "Morphe Patches".equals(s(u, "name")) && "morphe-official".equals(s(st.list().optJSONObject(0), "id")));
        st.update("morphe-official", null);
        check("update() with nothing newer downloads nothing again", hitCount("/dl/morphe-1.0.0.mpp") == 1);
        st.setEnabled("morphe-official", false);
        check("the built-in source can be switched off", !st.get("morphe-official").optBoolean("enabled"));
        st.setEnabled("morphe-official", true);
        check("... and on again", st.get("morphe-official").optBoolean("enabled"));
    }

    // ---------------------------------------------------------------- GitHub

    static class Prog implements MorpheNet.Progress {
        volatile long done = -1, total = -1;
        volatile boolean cancel;
        public void onProgress(long d, long t) { done = d; total = t; }
        public boolean cancelled() { return cancel; }
    }

    static void addGithub() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);

        // main hit
        byte[] a = mpp("Alpha Patches", "1.0.0", true, false, "1.14.0", 4);
        bytes("/dl/a-1.0.0.mpp", a);
        json("/raw/own/alpha/main/patches-bundle.json", meta("1.0.0", base + "/dl/a-1.0.0.mpp", "2026-01-02T03:04:05", "## Changelog\n- first"));
        Prog p = new Prog();
        JSONObject r = st.addRemote("github.com/own/alpha", null, false, p);
        check("github: id from the repository", "gh-own-alpha".equals(s(r, "id")), r.toString());
        check("github: name from the bundle's manifest", "Alpha Patches".equals(s(r, "name")));
        check("github: remote, from github, with its repository and web address", "remote".equals(s(r, "kind")) && "github".equals(s(r, "host")) && "own/alpha".equals(s(r, "repo"))
                && "https://github.com/own/alpha".equals(s(r, "url")));
        check("github: no branch of its own", s(r, "branch").isEmpty());
        check("github: version, changelog and date from the metadata", "1.0.0".equals(s(r, "version")) && "## Changelog\n- first".equals(s(r, "description")) && "2026-01-02T03:04:05".equals(s(r, "createdAt")));
        check("github: defaults (enabled, stable, not built-in, no error, patch count unknown)", r.optBoolean("enabled") && !r.optBoolean("prerelease") && !r.optBoolean("builtIn")
                && s(r, "error").isEmpty() && r.optInt("patchCount") == -1);
        check("github: updatedAt is set", r.optLong("updatedAt") > System.currentTimeMillis() - 60000);
        File f = new File(s(r, "file"));
        check("github: the file is bundles/<id>/bundle.mpp under the folder", f.isFile() && f.equals(new File(dir, "bundles/gh-own-alpha/bundle.mpp")) && f.equals(st.bundleFile("gh-own-alpha")));
        check("github: the file is the download, byte for byte, and size says so", Arrays.equals(a, Files.readAllBytes(f.toPath())) && r.optLong("size") == a.length);
        check("github: the file is read-only (Android 14 refuses a writable dex)", readOnly(f));
        check("github: nothing but bundle.mpp in its folder", names(f.getParentFile()).equals(Arrays.asList("bundle.mpp")), names(f.getParentFile()).toString());
        JSONObject meta = r.optJSONObject("meta");
        check("github: meta is the manifest", meta != null && "Alpha Patches".equals(s(meta, "name")) && meta.optBoolean("hasDex") && !r.optBoolean("needsNewerPatcher"));
        check("github: only main was asked", hitCount("/raw/own/alpha/main/patches-bundle.json") == 1 && hitCount("/raw/own/alpha/master/patches-bundle.json") == 0 && hits.indexOf("/api/repos/own/alpha/releases") < 0);
        check("github: the progress ended whole", p.done == a.length && p.total == a.length, p.done + "/" + p.total);
        check("github: listed after the built-in source", st.list().length() == 2 && "morphe-official".equals(s(st.list().optJSONObject(0), "id")) && "gh-own-alpha".equals(s(st.list().optJSONObject(1), "id")));

        // master after a 404 on main
        bytes("/dl/b.mpp", good("2.0.0"));
        json("/raw/own/beta/master/patches-bundle.json", meta("2.0.0", base + "/dl/b.mpp", "", ""));
        r = st.addRemote("https://github.com/own/beta", null, false, null);
        check("github: main 404 -> master", "2.0.0".equals(s(r, "version")) && hitCount("/raw/own/beta/main/patches-bundle.json") == 1 && hitCount("/raw/own/beta/master/patches-bundle.json") == 1);
        check("github: a missing date becomes the time of the download", s(r, "createdAt").endsWith("Z") && s(r, "createdAt").startsWith("20"), s(r, "createdAt"));

        // pre-release uses dev
        bytes("/dl/c-main.mpp", good("3.0.0"));
        bytes("/dl/c-dev.mpp", good("3.1.0-dev.2"));
        json("/raw/own/gamma/main/patches-bundle.json", meta("3.0.0", base + "/dl/c-main.mpp", "", ""));
        json("/raw/own/gamma/dev/patches-bundle.json", meta("3.1.0-dev.2", base + "/dl/c-dev.mpp", "", ""));
        r = st.addRemote("github.com/own/gamma", null, true, null);
        check("github: pre-release reads the dev branch", "3.1.0-dev.2".equals(s(r, "version")) && r.optBoolean("prerelease") && hitCount("/raw/own/gamma/dev/patches-bundle.json") == 1
                && hitCount("/raw/own/gamma/main/patches-bundle.json") == 0);

        // a branch in the address
        bytes("/dl/d.mpp", good("4.0.0"));
        json("/raw/own/delta/nightly/patches-bundle.json", meta("4.0.0", base + "/dl/d.mpp", "", ""));
        r = st.addRemote("https://github.com/own/delta/tree/nightly", null, false, null);
        check("github: a /tree/<branch> address reads that branch and remembers it", "4.0.0".equals(s(r, "version")) && "nightly".equals(s(r, "branch")) && hitCount("/raw/own/delta/main/patches-bundle.json") == 0);

        // ids stay unique
        bytes("/dl/e.mpp", good("5.0.0"));
        json("/raw/own/a.b/main/patches-bundle.json", meta("5.0.0", base + "/dl/e.mpp", "", ""));
        json("/raw/own/a-b/main/patches-bundle.json", meta("5.0.0", base + "/dl/e.mpp", "", ""));
        JSONObject r1 = st.addRemote("github.com/own/a.b", null, false, null);
        JSONObject r2 = st.addRemote("github.com/own/a-b", null, false, null);
        check("github: two repositories whose names slug alike get different ids", "gh-own-a-b".equals(s(r1, "id")) && "gh-own-a-b-2".equals(s(r2, "id")), s(r1, "id") + " " + s(r2, "id"));
    }

    static void addReleases() throws Exception {
        reset();
        MorpheStore st = new MorpheStore(newBase());
        byte[] rel = mpp("Rel", "2.0.0", true, false, null, 0);
        byte[] pre = mpp("Rel", "2.1.0-beta.1", true, false, null, 0);
        bytes("/dl/rel.mpp", rel);
        bytes("/dl/pre.mpp", pre);
        bytes("/dl/draft.mpp", good("9.9.9"));
        String releases = "[" +
                "{\"tag_name\":\"v3.0.0\",\"draft\":true,\"prerelease\":false,\"assets\":[{\"name\":\"p-3.0.0.mpp\",\"browser_download_url\":\"" + base + "/dl/draft.mpp\"}]}," +
                "{\"tag_name\":\"v2.1.0-beta.1\",\"draft\":false,\"prerelease\":true,\"body\":\"beta notes\",\"published_at\":\"2026-03-04T05:06:07Z\",\"assets\":[{\"name\":\"p-2.1.0-beta.1.mpp\",\"browser_download_url\":\"" + base + "/dl/pre.mpp\"}]}," +
                "{\"tag_name\":\"v2.0.5\",\"draft\":false,\"prerelease\":false,\"assets\":[{\"name\":\"notes.txt\",\"browser_download_url\":\"" + base + "/dl/none.txt\"}]}," +
                "{\"tag_name\":\"v2.0.0\",\"name\":\"Two\",\"body\":\"Release notes\",\"created_at\":\"2026-02-01T00:00:00Z\",\"published_at\":\"2026-02-03T04:05:06Z\",\"draft\":false,\"prerelease\":false,\"assets\":["
                + "{\"name\":\"rel-2.0.0.mpp.sig\",\"browser_download_url\":\"" + base + "/dl/rel.sig\"},"
                + "{\"name\":\"rel-2.0.0.mpp\",\"browser_download_url\":\"" + base + "/dl/rel.mpp\",\"digest\":\"sha256:" + sha(rel) + "\"}]}]";
        json("/api/repos/own/rel/releases", releases);
        JSONObject r = st.addRemote("github.com/own/rel", null, false, null);
        check("releases: main and master 404, then the release list is asked", hitCount("/raw/own/rel/main/patches-bundle.json") == 1 && hitCount("/raw/own/rel/master/patches-bundle.json") == 1 && hitCount("/api/repos/own/rel/releases") == 1);
        check("releases: skips the draft, the pre-release and the release without a .mpp", "2.0.0".equals(s(r, "version")) && hitCount("/dl/draft.mpp") == 0 && hitCount("/dl/pre.mpp") == 0);
        check("releases: the notes and the publication date", "Release notes".equals(s(r, "description")) && "2026-02-03T04:05:06Z".equals(s(r, "createdAt")));
        check("releases: the file is the asset", Arrays.equals(rel, Files.readAllBytes(new File(s(r, "file")).toPath())));
        JSONObject c = st.checkUpdate("gh-own-rel");
        check("releases: the version (tag without its v) is compared to what is installed", "2.0.0".equals(s(c, "latest")) && "2.0.0".equals(s(c, "current")) && !c.optBoolean("newer"), c.toString());

        json("/api/repos/own/rel2/releases", releases);
        r = st.addRemote("github.com/own/rel2", null, true, null);
        check("releases: a pre-release is taken when asked for", "2.1.0-beta.1".equals(s(r, "version")) && "beta notes".equals(s(r, "description")) && hitCount("/raw/own/rel2/dev/patches-bundle.json") == 1
                && hitCount("/raw/own/rel2/master/patches-bundle.json") == 1);

        // a published digest that is not the file's
        bytes("/dl/bad-digest.mpp", rel);
        json("/api/repos/own/dig/releases", "[{\"tag_name\":\"v1\",\"assets\":[{\"name\":\"x.mpp\",\"browser_download_url\":\"" + base + "/dl/bad-digest.mpp\",\"digest\":\"sha256:" + sha("other".getBytes()) + "\"}]}]");
        String m = failure(() -> st.addRemote("github.com/own/dig", null, false, null));
        check("releases: a digest that does not match the file is refused", has(m, "checksum"), m);
        check("releases: ... and nothing was kept", st.get("gh-own-dig") == null);
    }

    static void addFailures() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        int before = st.list().length();

        String m = failure(() -> st.addRemote("github.com/own/ghost", null, false, null));
        check("nothing found: a clear error naming the repository", has(m, "No patch bundle was found in own/ghost") && has(m, "patches-bundle.json") && has(m, ".mpp"), m);
        check("nothing found: no source, no folder", st.list().length() == before && !new File(dir, "bundles/gh-own-ghost").exists());

        status("/api/repos/own/limited/releases", 403);
        m = failure(() -> st.addRemote("github.com/own/limited", null, false, null));
        check("a rate-limited release list says so", has(m, "403") && has(m, "limiting requests"), m);

        json("/raw/own/broken/main/patches-bundle.json", "{\"version\":\"1.0\"}");
        m = failure(() -> st.addRemote("github.com/own/broken", null, false, null));
        check("a metadata file without download_url: the problem is named", has(m, "download_url") && has(m, "not usable"), m);

        json("/raw/own/nodl/main/patches-bundle.json", meta("1.0.0", base + "/dl/missing.mpp", "", ""));
        m = failure(() -> st.addRemote("github.com/own/nodl", null, false, null));
        check("a download that 404s", has(m, "Download failed") && has(m, "404"), m);
        check("... leaves no source and no folder", st.get("gh-own-nodl") == null && !new File(dir, "bundles/gh-own-nodl").exists());

        bytes("/dl/text.mpp", "hello, not a bundle".getBytes());
        json("/raw/own/text/main/patches-bundle.json", meta("1.0.0", base + "/dl/text.mpp", "", ""));
        m = failure(() -> st.addRemote("github.com/own/text", null, false, null));
        check("a download that is not a bundle", "That file is not a Morphe patch bundle".equals(m), m);
        check("... leaves no source and no folder", st.get("gh-own-text") == null && !new File(dir, "bundles/gh-own-text").exists());

        bytes("/dl/nodex.mpp", zip("Manifest-Version: 1.0\r\nName: Empty\r\n\r\n", false, false, 0, "x"));
        json("/raw/own/nodex/main/patches-bundle.json", meta("1.0.0", base + "/dl/nodex.mpp", "", ""));
        m = failure(() -> st.addRemote("github.com/own/nodex", null, false, null));
        check("a zip with no dex and no classes", "That file is not a Morphe patch bundle".equals(m), m);

        byte[] a = good("1.0.0");
        bytes("/dl/sum.mpp", a);
        json("/raw/own/sum/main/patches-bundle.json", "{\"version\":\"1.0.0\",\"download_url\":" + q(base + "/dl/sum.mpp") + ",\"sha256\":\"" + sha("something else".getBytes()) + "\"}");
        m = failure(() -> st.addRemote("github.com/own/sum", null, false, null));
        check("a sha256 in the metadata that does not match", has(m, "checksum"), m);
        check("... leaves nothing", st.get("gh-own-sum") == null && !new File(dir, "bundles/gh-own-sum").exists());
        json("/raw/own/sum2/main/patches-bundle.json", "{\"version\":\"1.0.0\",\"download_url\":" + q(base + "/dl/sum.mpp") + ",\"sha256\":\"" + sha(a) + "\"}");
        JSONObject r = st.addRemote("github.com/own/sum2", null, false, null);
        check("... and one that matches is accepted", "1.0.0".equals(s(r, "version")));

        m = failure(() -> st.addRemote("http://github.com/own/x", null, false, null));
        check("invalid input: the IOException carries the reason", "Invalid source URL: only https:// addresses are allowed".equals(m), m);
        check("failed adds changed nothing", st.list().length() == before + 1);
        check("no stray staging file anywhere", !anyFile(dir, ".new") && !anyFile(dir, ".part"));
    }

    static boolean anyFile(File root, String suffix) {
        File[] kids = root.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (k.getName().endsWith(suffix) || k.getName().contains(suffix + ".")) return true;
            if (k.isDirectory() && anyFile(k, suffix)) return true;
        }
        return false;
    }

    static void addMisc() throws Exception {
        reset();
        MorpheStore st = new MorpheStore(newBase());
        bytes("/dl/m.mpp", good("1.0.0"));
        json("/raw/own/alpha/main/patches-bundle.json", meta("1.0.0", base + "/dl/m.mpp", "", ""));
        st.addRemote("github.com/own/alpha", null, false, null);
        int hitsBefore = hits.size();
        String m = failure(() -> st.addRemote("github.com/own/alpha", null, false, null));
        check("a duplicate: \"This source has already been added\"", "This source has already been added".equals(m), m);
        m = failure(() -> st.addRemote("https://github.com/OWN/Alpha.git", null, false, null));
        check("... also written another way (case, .git, https)", "This source has already been added".equals(m), m);
        m = failure(() -> st.addRemote("https://github.com/own/alpha/tree/dev", null, true, null));
        check("... also with another branch", "This source has already been added".equals(m), m);
        m = failure(() -> st.addRemote("github.com/MorpheApp/morphe-patches", null, false, null));
        check("... the built-in source's repository counts", "This source has already been added".equals(m), m);
        m = failure(() -> st.addRemote("https://github.com/morpheapp/MORPHE-PATCHES/tree/dev", null, false, null));
        check("... in any case", "This source has already been added".equals(m), m);
        check("a duplicate is refused before any network call", hits.size() == hitsBefore);
        bytes("/dl/n.mpp", good("1.0.0"));
        json("/raw/own/other/main/patches-bundle.json", meta("1.0.0", base + "/dl/n.mpp", "", ""));
        check("another repository of the same owner is fine", st.addRemote("github.com/own/other", null, false, null) != null);

        // names
        json("/raw/own/named/main/patches-bundle.json", meta("1.0.0", base + "/dl/n.mpp", "", ""));
        JSONObject r = st.addRemote("github.com/own/named", "  My   Pack\n", false, null);
        check("a name given is used (trimmed, spaces folded)", "My Pack".equals(s(r, "name")), s(r, "name"));
        json("/raw/own/zeta/main/patches-bundle.json", meta("1.0.0", base + "/dl/n.mpp", "", ""));
        r = st.addRemote("https://morphe.software/add-source?github=own/zeta&name=Zeta%20Pack", null, false, null);
        check("the deep link's name is used", "Zeta Pack".equals(s(r, "name")) && "gh-own-zeta".equals(s(r, "id")), s(r, "name"));
        json("/raw/own/eta/main/patches-bundle.json", meta("1.0.0", base + "/dl/n.mpp", "", ""));
        r = st.addRemote("https://morphe.software/add-source?github=own/eta&name=Eta", "Chosen", false, null);
        check("a name given wins over the deep link's", "Chosen".equals(s(r, "name")), s(r, "name"));
        bytes("/dl/noname.mpp", mpp(null, "1.0.0", true, false, null, 0));
        json("/raw/own/omega/main/patches-bundle.json", meta("1.0.0", base + "/dl/noname.mpp", "", ""));
        r = st.addRemote("github.com/own/omega", null, false, null);
        check("no name anywhere: the repository's name", "omega".equals(s(r, "name")), s(r, "name"));
    }

    static void addCancel() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        bytes("/dl/big.mpp", mpp("Big", "1.0.0", true, false, null, 600));
        json("/raw/own/big/main/patches-bundle.json", meta("1.0.0", base + "/dl/big.mpp", "", ""));
        final Prog p = new Prog();
        p.cancel = true;
        String m = failure(() -> st.addRemote("github.com/own/big", null, false, p));
        check("a cancelled add: \"cancelled\", no source, no folder", "cancelled".equals(m) && st.list().length() == 1 && !new File(dir, "bundles/gh-own-big").exists(), m);
        final Prog p2 = new Prog() {
            public boolean cancelled() { return done > 0 || cancel; }
        };
        m = failure(() -> st.addRemote("github.com/own/big", null, false, p2));
        check("a cancel during the download: nothing kept either", m != null && st.get("gh-own-big") == null && !anyFile(dir, ".part") && !anyFile(dir, ".new"), m);
        JSONObject r = st.addRemote("github.com/own/big", null, false, null);
        check("... and the add works when tried again", "1.0.0".equals(s(r, "version")));
    }

    // ---------------------------------------------------------------- GitLab and a metadata address

    static void addGitlab() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        bytes("/dl/gl.mpp", mpp("GL Patches", "1.2.0", true, false, null, 0));
        json("/gl/grp/proj/-/raw/main/patches-bundle.json", meta("1.2.0", base + "/dl/gl.mpp", "2026-05-06T07:08:09", "gl text"));
        JSONObject r = st.addRemote("gitlab.com/grp/proj", null, false, null);
        check("gitlab: the raw metadata on main", "gl-grp-proj".equals(s(r, "id")) && "gitlab".equals(s(r, "host")) && "grp/proj".equals(s(r, "repo")) && "https://gitlab.com/grp/proj".equals(s(r, "url")) && "1.2.0".equals(s(r, "version")), r.toString());

        String releases = "[{\"tag_name\":\"v3.0.0\",\"upcoming_release\":true,\"assets\":{\"links\":[{\"name\":\"x.mpp\",\"url\":\"" + base + "/dl/up.mpp\"}]}},"
                + "{\"tag_name\":\"v2.0.0-rc1\",\"assets\":{\"links\":[{\"name\":\"x.mpp\",\"url\":\"" + base + "/dl/rc.mpp\"}]}},"
                + "{\"tag_name\":\"v1.2.0\",\"name\":\"One\",\"description\":\"gl notes\",\"released_at\":\"2026-06-07T08:09:10Z\",\"assets\":{\"links\":["
                + "{\"name\":\"source.zip\",\"url\":\"" + base + "/dl/src.zip\"},{\"name\":\"bundle-1.2.0.mpp\",\"url\":\"" + base + "/dl/other.mpp\",\"direct_asset_url\":\"" + base + "/dl/gl.mpp\"}]}}]";
        json("/gl/api/v4/projects/grp2%2Fproj2/releases", releases);
        r = st.addRemote("https://gitlab.com/grp2/proj2", null, false, null);
        check("gitlab: the releases API after a 404 (skips an upcoming release and an -rc tag, takes a link ending .mpp, prefers its direct address)",
                "gl-grp2-proj2".equals(s(r, "id")) && "1.2.0".equals(s(r, "version")) && "gl notes".equals(s(r, "description")) && "2026-06-07T08:09:10Z".equals(s(r, "createdAt"))
                        && hitCount("/dl/up.mpp") == 0 && hitCount("/dl/rc.mpp") == 0 && hitCount("/dl/other.mpp") == 0, r.toString());

        json("/gl/g/s/p/-/raw/main/patches-bundle.json", meta("1.2.0", base + "/dl/gl.mpp", "", ""));
        r = st.addRemote("https://gitlab.com/g/s/p/-/tree/main", null, false, null);
        check("gitlab: a project in a subgroup", "gl-g-s-p".equals(s(r, "id")) && "g/s/p".equals(s(r, "repo")) && "https://gitlab.com/g/s/p".equals(s(r, "url")), r.toString());
        String m = failure(() -> st.addRemote("gitlab.com/none/here", null, false, null));
        check("gitlab: nothing found says so", has(m, "No patch bundle was found in none/here"), m);
        m = failure(() -> st.addRemote("gitlab.com/GRP/Proj", null, false, null));
        check("gitlab: a duplicate in another case", "This source has already been added".equals(m), m);
        bytes("/dl/gh-same.mpp", good("1.0.0"));
        json("/raw/grp/proj/main/patches-bundle.json", meta("1.0.0", base + "/dl/gh-same.mpp", "", ""));
        r = st.addRemote("github.com/grp/proj", null, false, null);
        check("a GitHub and a GitLab repository of the same name are different sources", "gh-grp-proj".equals(s(r, "id")) && st.get("gl-grp-proj") != null, r.toString());
    }

    static void addUrl() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        bytes("/dl/u.mpp", mpp("Url Patches", "7.0.0", true, false, null, 0));
        String metaUrl = base + "/meta/custom.json";
        json("/meta/custom.json", meta("7.0.0", base + "/dl/u.mpp", "2026-07-08T09:10:11", "url text"));
        JSONObject r = st.addRemote(metaUrl, null, false, null);
        check("metadata address: host url, the address as its web address, no repo", "url".equals(s(r, "host")) && metaUrl.equals(s(r, "url")) && s(r, "repo").isEmpty() && "7.0.0".equals(s(r, "version")), r.toString());
        check("metadata address: an id from the host and the address", s(r, "id").startsWith("url-127-0-0-1-") && s(r, "id").length() == "url-127-0-0-1-".length() + 8, s(r, "id"));
        check("metadata address: named from the bundle", "Url Patches".equals(s(r, "name")));
        String m = failure(() -> st.addRemote(metaUrl, null, false, null));
        check("metadata address: the same address again is a duplicate", "This source has already been added".equals(m), m);
        m = failure(() -> st.addRemote(base + "/meta/missing.json", null, false, null));
        check("metadata address: a 404 is told", has(m, "metadata file could not be read") && has(m, "404"), m);
        json("/meta/bad.json", "{\"version\":\"1\"}");
        m = failure(() -> st.addRemote(base + "/meta/bad.json", null, false, null));
        check("metadata address: a file that is no metadata is told", has(m, "not usable") && has(m, "download_url"), m);
        JSONObject c = st.checkUpdate(s(r, "id"));
        check("metadata address: checkUpdate reads the same address", "7.0.0".equals(s(c, "latest")) && !c.optBoolean("newer") && "url text".equals(s(c, "description")));
    }

    // ---------------------------------------------------------------- local files

    static void addLocal() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        byte[] a = mpp("Local Pack", "0.9.1", true, false, "1.14.0", 2);
        File src = write("my-bundle.mpp", a);
        JSONObject r = st.addLocal(src, null);
        check("local: id is local- and 8 hex digits", s(r, "id").matches("local-[0-9a-f]{8}"), s(r, "id"));
        check("local: kind local, no host, repository or address", "local".equals(s(r, "kind")) && s(r, "host").isEmpty() && s(r, "repo").isEmpty() && s(r, "url").isEmpty() && !r.optBoolean("builtIn"));
        check("local: name and version from the manifest", "Local Pack".equals(s(r, "name")) && "0.9.1".equals(s(r, "version")) && "Patches for tests".equals(s(r, "description")));
        check("local: the date is the file's, as ISO text", s(r, "createdAt").matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"), s(r, "createdAt"));
        File f = new File(s(r, "file"));
        check("local: copied to bundles/<id>/bundle.mpp, byte for byte, read-only", f.equals(new File(dir, "bundles/" + s(r, "id") + "/bundle.mpp")) && Arrays.equals(a, Files.readAllBytes(f.toPath())) && readOnly(f));
        src.delete();
        check("local: the copy is the store's own (the original can go)", f.isFile() && st.bundleFile(s(r, "id")) != null);
        check("local: nothing but bundle.mpp in its folder", names(f.getParentFile()).equals(Arrays.asList("bundle.mpp")));
        final String id = s(r, "id");
        String m = failure(() -> st.update(id, null));
        check("local: update() says a local source does not update", has(m, "local source"), m);
        m = failure(() -> st.checkUpdate(id));
        check("local: checkUpdate() says the same", has(m, "local source"), m);

        r = st.addLocal(write("UPPER.MPP", a), "Named Local");
        check("local: .MPP in capitals is fine, a name given is used", "Named Local".equals(s(r, "name")));
        r = st.addLocal(write("my-bundle.mpp", a), null);
        check("local: the same file again is a second source with its own id", st.list().length() == 4 && !id.equals(s(r, "id")));
        r = st.addLocal(write("nover.mpp", mpp("NoVersion", null, true, false, null, 0)), null);
        check("local: a manifest without a version", s(r, "version").isEmpty());
        r = st.addLocal(write("plain-name.mpp", zip("Manifest-Version: 1.0\r\n\r\n", true, false, 0, "y")), null);
        check("local: no name in the manifest -> the file's name without .mpp", "plain-name".equals(s(r, "name")), s(r, "name"));

        int count = st.list().length();
        int folders = names(new File(dir, "bundles")).size();
        m = failure(() -> st.addLocal(write("bundle.zip", good("1.0.0")), null));
        check("local: a wrong extension names .mpp", has(m, ".mpp"), m);
        m = failure(() -> st.addLocal(write("text.mpp", "not a zip".getBytes()), null));
        check("local: text is not a bundle", "That file is not a Morphe patch bundle".equals(m), m);
        m = failure(() -> st.addLocal(write("empty.mpp", zip("Manifest-Version: 1.0\r\n\r\n", false, false, 0, "z")), null));
        check("local: a zip with no dex and no classes is not a bundle", "That file is not a Morphe patch bundle".equals(m), m);
        m = failure(() -> st.addLocal(new File(tmp, "absent.mpp"), null));
        check("local: a file that is not there", m != null && !m.isEmpty(), m);
        m = failure(() -> st.addLocal(null, null));
        check("local: null", m != null && !m.isEmpty(), m);
        File folder = new File(tmp, "folder.mpp");
        folder.mkdirs();
        m = failure(() -> st.addLocal(folder, null));
        check("local: a folder named .mpp", m != null && !m.isEmpty(), m);
        check("local: refused files leave no source and no folder", st.list().length() == count && names(new File(dir, "bundles")).size() == folders);

        check("local: a bundle that asks for a newer patcher is flagged", st.addLocal(write("p1.mpp", mpp("P", "1", true, false, "9.9.9", 0)), null).optBoolean("needsNewerPatcher"));
        check("local: ... the same patcher, an older one and none are not", !st.addLocal(write("p2.mpp", mpp("P", "1", true, false, "1.15.1", 0)), null).optBoolean("needsNewerPatcher")
                && !st.addLocal(write("p3.mpp", mpp("P", "1", true, false, "1.14.0", 0)), null).optBoolean("needsNewerPatcher")
                && !st.addLocal(write("p4.mpp", mpp("P", "1", true, false, null, 0)), null).optBoolean("needsNewerPatcher"));
        check("local: ... a newer pre-release of the next patcher is", st.addLocal(write("p5.mpp", mpp("P", "1", true, false, "1.16.0-dev.1", 0)), null).optBoolean("needsNewerPatcher"));
    }

    // ---------------------------------------------------------------- checking and updating

    static void updates() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        byte[] v1 = mpp("Up", "1.0.0", true, false, null, 3);
        byte[] v11 = mpp("Up", "1.1.0", true, false, null, 5);
        bytes("/dl/up-1.0.0.mpp", v1);
        json("/raw/own/up/main/patches-bundle.json", meta("1.0.0", base + "/dl/up-1.0.0.mpp", "2026-01-01T00:00:00", "first"));
        JSONObject r = st.addRemote("github.com/own/up", null, false, null);
        final String id = s(r, "id");
        st.setPatchCount(id, 5);

        JSONObject c = st.checkUpdate(id);
        check("checkUpdate: same version -> not newer", id.equals(s(c, "id")) && "1.0.0".equals(s(c, "current")) && "1.0.0".equals(s(c, "latest")) && !c.optBoolean("newer"), c.toString());
        check("checkUpdate: the download address, text and date are reported", (base + "/dl/up-1.0.0.mpp").equals(s(c, "downloadUrl")) && "first".equals(s(c, "description")) && "2026-01-01T00:00:00".equals(s(c, "createdAt")));

        bytes("/dl/up-1.1.0.mpp", v11);
        json("/raw/own/up/main/patches-bundle.json", meta("1.1.0", base + "/dl/up-1.1.0.mpp", "2026-02-02T00:00:00", "second"));
        c = st.checkUpdate(id);
        check("checkUpdate: a newer version is reported", c.optBoolean("newer") && "1.1.0".equals(s(c, "latest")) && "1.0.0".equals(s(c, "current")) && "second".equals(s(c, "description")), c.toString());
        check("checkUpdate: downloads nothing", hitCount("/dl/up-1.1.0.mpp") == 0 && Arrays.equals(v1, Files.readAllBytes(st.bundleFile(id).toPath())));

        File f = st.bundleFile(id);
        long updated = st.get(id).optLong("updatedAt");
        Thread.sleep(5);
        r = st.update(id, null);
        check("update: the file is the new bundle, in the same place", f.equals(st.bundleFile(id)) && Arrays.equals(v11, Files.readAllBytes(f.toPath())) && "1.1.0".equals(s(r, "version")), r.toString());
        check("update: read-only again, no staging file left", readOnly(f) && names(f.getParentFile()).equals(Arrays.asList("bundle.mpp")), names(f.getParentFile()).toString());
        check("update: the text and date follow, the patch count is to be read again, the time moves on", "second".equals(s(r, "description")) && "2026-02-02T00:00:00".equals(s(r, "createdAt"))
                && r.optInt("patchCount") == -1 && r.optLong("updatedAt") > updated && s(r, "error").isEmpty());
        check("update: size follows the file", r.optLong("size") == v11.length);
        st.setPatchCount(id, 9);
        long u2 = st.get(id).optLong("updatedAt");
        r = st.update(id, null);
        check("update: nothing newer -> nothing downloaded, nothing changed", hitCount("/dl/up-1.1.0.mpp") == 1 && r.optLong("updatedAt") == u2 && r.optInt("patchCount") == 9);

        // the file is gone: downloaded again although the version is the same
        f.delete();
        JSONObject gone = st.get(id);
        check("a missing file: no file, no installed version", s(gone, "file").isEmpty() && s(gone, "version").isEmpty() && st.bundleFile(id) == null && gone.isNull("meta"));
        c = st.checkUpdate(id);
        check("... checkUpdate says newer", c.optBoolean("newer") && s(c, "current").isEmpty(), c.toString());
        r = st.update(id, null);
        check("... update() fetches it again", hitCount("/dl/up-1.1.0.mpp") == 2 && f.isFile() && "1.1.0".equals(s(r, "version")));

        // the metadata goes away
        routes.remove("/raw/own/up/main/patches-bundle.json");
        String m = failure(() -> st.checkUpdate(id));
        check("checkUpdate with the metadata gone: an IOException", m != null && !m.startsWith("OTHER"), m);
        check("... remembered as the source's error", !s(st.get(id), "error").isEmpty(), s(st.get(id), "error"));
        m = failure(() -> st.update(id, null));
        check("update with the metadata gone: an IOException, the bundle untouched", m != null && Arrays.equals(v11, Files.readAllBytes(f.toPath())));
        json("/raw/own/up/main/patches-bundle.json", meta("1.1.0", base + "/dl/up-1.1.0.mpp", "2026-02-02T00:00:00", "second"));
        st.checkUpdate(id);
        check("the error is cleared by the next good answer", s(st.get(id), "error").isEmpty());

        // a new version whose file will not download
        status("/dl/up-1.2.0.mpp", 500);
        json("/raw/own/up/main/patches-bundle.json", meta("1.2.0", base + "/dl/up-1.2.0.mpp", "", "third"));
        m = failure(() -> st.update(id, null));
        check("update with a download that fails: the error is told (500)", has(m, "Download failed") && has(m, "500"), m);
        check("... the old bundle is untouched, and still the installed version", Arrays.equals(v11, Files.readAllBytes(f.toPath())) && "1.1.0".equals(s(st.get(id), "version")) && readOnly(f));
        check("... the error is kept with the source, no staging file is left", has(s(st.get(id), "error"), "500") && names(f.getParentFile()).equals(Arrays.asList("bundle.mpp")));

        bytes("/dl/up-1.2.0.mpp", "this is not a bundle".getBytes());
        m = failure(() -> st.update(id, null));
        check("update with a file that is no bundle: refused, the old one kept", "That file is not a Morphe patch bundle".equals(m) && Arrays.equals(v11, Files.readAllBytes(f.toPath())), m);

        byte[] v12 = mpp("Up", "1.2.0", true, false, null, 1);
        bytes("/dl/up-1.2.0.mpp", v12);
        r = st.update(id, null);
        check("update works once the file is right", "1.2.0".equals(s(r, "version")) && Arrays.equals(v12, Files.readAllBytes(f.toPath())) && s(r, "error").isEmpty());

        m = failure(() -> st.checkUpdate("zzz"));
        check("checkUpdate of an unknown source", "Unknown source".equals(m), m);
        m = failure(() -> st.update("zzz", null));
        check("update of an unknown source", "Unknown source".equals(m), m);
    }

    static void channels() throws Exception {
        reset();
        MorpheStore st = new MorpheStore(newBase());
        bytes("/dl/ch-dev.mpp", mpp("Ch", "2.0.0-dev.1", true, false, null, 0));
        bytes("/dl/ch-stable.mpp", mpp("Ch", "1.5.0", true, false, null, 0));
        json("/raw/own/ch/dev/patches-bundle.json", meta("2.0.0-dev.1", base + "/dl/ch-dev.mpp", "", ""));
        json("/raw/own/ch/main/patches-bundle.json", meta("1.5.0", base + "/dl/ch-stable.mpp", "", ""));
        JSONObject r = st.addRemote("github.com/own/ch", null, true, null);
        check("channels: a pre-release source installs the dev build", "2.0.0-dev.1".equals(s(r, "version")));
        JSONObject c = st.checkUpdate("gh-own-ch");
        check("channels: still on dev, nothing newer", !c.optBoolean("newer"), c.toString());
        st.setPrerelease("gh-own-ch", false);
        check("channels: setPrerelease is saved on the source", !st.get("gh-own-ch").optBoolean("prerelease"));
        c = st.checkUpdate("gh-own-ch");
        check("channels: turned back to stable, the stable build is offered although its number is lower", c.optBoolean("newer") && "1.5.0".equals(s(c, "latest")), c.toString());
        r = st.update("gh-own-ch", null);
        check("channels: ... and installed", "1.5.0".equals(s(r, "version")));
        c = st.checkUpdate("gh-own-ch");
        check("channels: then nothing newer", !c.optBoolean("newer"));
        st.setPrerelease("gh-own-ch", true);
        c = st.checkUpdate("gh-own-ch");
        check("channels: back on pre-releases the newer dev build is offered", c.optBoolean("newer") && "2.0.0-dev.1".equals(s(c, "latest")), c.toString());
    }

    // ---------------------------------------------------------------- a download that dies, and one that takes its time

    static void killed() throws Exception {
        reset();
        File dir = newBase();
        final MorpheStore st = new MorpheStore(dir);
        byte[] v1 = good("1.0.0");
        bytes("/dl/k1.mpp", v1);
        json("/raw/own/kill/main/patches-bundle.json", meta("1.0.0", base + "/dl/k1.mpp", "", ""));
        final String id = s(st.addRemote("github.com/own/kill", null, false, null), "id");
        final File f = st.bundleFile(id);
        final byte[] big = mpp("Kill", "2.0.0", true, false, null, 3000);
        final Slow slow = new Slow(big, 200000);
        json("/raw/own/kill/main/patches-bundle.json", meta("2.0.0", slow.url, "", "v2"));
        final Throwable[] err = new Throwable[1];
        Thread t = new Thread(new Runnable() { public void run() {
            try { st.update(id, null); } catch (Throwable e) { err[0] = e; }
        } });
        t.start();
        check("the server starts the download", slow.started.await(10, TimeUnit.SECONDS));
        slow.server.stop(0);       // the server dies in the middle of the file
        slow.release.countDown();
        t.join(20000);
        check("a server that dies mid-download: update() fails with an IOException", err[0] instanceof IOException && has(err[0].getMessage(), "Download failed"), String.valueOf(err[0]));
        check("... the old bundle survives, byte for byte, still read-only", f.isFile() && Arrays.equals(v1, Files.readAllBytes(f.toPath())) && readOnly(f));
        check("... still the installed version", "1.0.0".equals(s(st.get(id), "version")));
        check("... no half file is left", names(f.getParentFile()).equals(Arrays.asList("bundle.mpp")), names(f.getParentFile()).toString());
        check("... the failure is on the source", !s(st.get(id), "error").isEmpty());

        // a new source whose download dies leaves nothing
        final Slow slow2 = new Slow(big, 100000);
        json("/raw/own/kill2/main/patches-bundle.json", meta("2.0.0", slow2.url, "", ""));
        final Throwable[] err2 = new Throwable[1];
        Thread t2 = new Thread(new Runnable() { public void run() {
            try { st.addRemote("github.com/own/kill2", null, false, null); } catch (Throwable e) { err2[0] = e; }
        } });
        t2.start();
        slow2.started.await(10, TimeUnit.SECONDS);
        slow2.server.stop(0);
        slow2.release.countDown();
        t2.join(20000);
        check("a new source whose download dies: nothing is added", err2[0] instanceof IOException && st.get("gh-own-kill2") == null && !new File(dir, "bundles/gh-own-kill2").exists(), String.valueOf(err2[0]));
        check("... and no staging file is anywhere", !anyFile(dir, ".new") && !anyFile(dir, ".part"));
    }

    static void concurrent() throws Exception {
        reset();
        File dir = newBase();
        final MorpheStore st = new MorpheStore(dir);
        byte[] v1 = good("1.0.0");
        bytes("/dl/c1.mpp", v1);
        json("/raw/own/conc/main/patches-bundle.json", meta("1.0.0", base + "/dl/c1.mpp", "", ""));
        final String id = s(st.addRemote("github.com/own/conc", null, false, null), "id");
        final byte[] v2 = mpp("Conc", "2.0.0", true, false, null, 2000);
        final Slow slow = new Slow(v2, 300000);
        json("/raw/own/conc/main/patches-bundle.json", meta("2.0.0", slow.url, "", ""));
        final Throwable[] err = new Throwable[1];
        Thread t = new Thread(new Runnable() { public void run() {
            try { st.update(id, null); } catch (Throwable e) { err[0] = e; }
        } });
        t.start();
        check("a slow download is under way", slow.started.await(10, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        JSONArray l = st.list();
        JSONObject g = st.get(id);
        st.setEnabled(id, false);
        st.setPatchCount(id, 3);
        File bf = st.bundleFile(id);
        long ms = (System.nanoTime() - t0) / 1000000;
        check("list(), get() and the setters answer while it downloads (" + ms + " ms)", ms < 1500 && l.length() == 2);
        check("... and show the bundle still installed", "1.0.0".equals(s(g, "version")) && bf != null && Arrays.equals(v1, Files.readAllBytes(bf.toPath())));
        slow.release.countDown();
        t.join(30000);
        slow.server.stop(0);
        check("the update then finishes", err[0] == null && "2.0.0".equals(s(st.get(id), "version")) && Arrays.equals(v2, Files.readAllBytes(st.bundleFile(id).toPath())), String.valueOf(err[0]));
        check("... the changes made meanwhile are kept (the count is reset by the new file, the switch stays)", !st.get(id).optBoolean("enabled") && st.get(id).optInt("patchCount") == -1);
    }

    // ---------------------------------------------------------------- the saved list

    static void persistence() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        bytes("/dl/p.mpp", good("1.0.0"));
        json("/raw/own/pa/main/patches-bundle.json", meta("1.0.0", base + "/dl/p.mpp", "", ""));
        json("/raw/own/pb/main/patches-bundle.json", meta("1.0.0", base + "/dl/p.mpp", "", ""));
        String a = s(st.addRemote("github.com/own/pa", null, false, null), "id");
        String b = s(st.addRemote("github.com/own/pb", null, false, null), "id");
        String loc = s(st.addLocal(write("pl.mpp", good("1.0.0")), "Local One"), "id");
        st.rename(a, "  Renamed A ");
        st.setEnabled(b, false);
        st.setPrerelease(b, true);
        st.setPatchCount(a, 12);
        st.setPatchCount(loc, -5);
        st.setEnabled("morphe-official", false);
        check("setPatchCount never goes below -1", st.get(loc).optInt("patchCount") == -1);
        st.setEnabled("no-such-id", true);
        st.setPrerelease("no-such-id", true);
        st.setPatchCount("no-such-id", 1);
        check("a setter with an unknown id does nothing", st.list().length() == 4);

        MorpheStore again = new MorpheStore(dir);
        JSONArray l = again.list();
        check("a new instance reads the same list, in the same order, the built-in first", l.length() == 4 && "morphe-official".equals(s(l.optJSONObject(0), "id")) && a.equals(s(l.optJSONObject(1), "id"))
                && b.equals(s(l.optJSONObject(2), "id")) && loc.equals(s(l.optJSONObject(3), "id")));
        check("... the new name (trimmed)", "Renamed A".equals(s(again.get(a), "name")));
        check("... enabled, pre-release, patch count", !again.get(b).optBoolean("enabled") && again.get(b).optBoolean("prerelease") && again.get(a).optInt("patchCount") == 12 && !again.get("morphe-official").optBoolean("enabled"));
        check("... versions and files come from the bundles", "1.0.0".equals(s(again.get(a), "version")) && again.get(a).optJSONObject("meta") != null && again.bundleFile(loc) != null);
        check("... a local source stays local", "local".equals(s(again.get(loc), "kind")) && "Local One".equals(s(again.get(loc), "name")));
        check("... the fields the page reads, as stored", s(again.get(a), "createdAt").equals(s(st.get(a), "createdAt")) && again.get(a).optLong("updatedAt") == st.get(a).optLong("updatedAt")
                && s(again.get(a), "repo").equals("own/pa") && s(again.get(a), "url").equals("https://github.com/own/pa"));
        String m = failure(() -> again.rename(a, "   "));
        check("rename to nothing is refused", has(m, "empty"), m);
        again.rename(a, "x");
        check("... the name is unchanged by the refusal", "x".equals(s(again.get(a), "name")));
        check("sources.json is valid and leaves no temp file", new File(dir, "sources.json").isFile() && !new File(dir, "sources.json.tmp").exists());

        File folder = new File(dir, "bundles/" + b);
        check("the folder of a source exists before removal", folder.isDirectory());
        again.remove(b);
        check("remove(): gone from the list, its folder deleted (read-only file and all)", again.get(b) == null && !folder.exists() && again.list().length() == 3);
        MorpheStore third = new MorpheStore(dir);
        check("... and gone after a restart", third.get(b) == null && third.list().length() == 3);
        m = failure(() -> third.remove(b));
        check("removing it again: Unknown source", "Unknown source".equals(m), m);
        third.remove(loc);
        check("a local source can be removed too", new MorpheStore(dir).list().length() == 2 && !new File(dir, "bundles/" + loc).exists());
    }

    static void damaged() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        String loc = s(st.addLocal(write("keep.mpp", good("1.0.0")), "Keeper"), "id");
        File sources = new File(dir, "sources.json");
        String good = new String(Files.readAllBytes(sources.toPath()), StandardCharsets.UTF_8);

        // not JSON at all
        Files.write(sources.toPath(), "{ this is not json".getBytes());
        MorpheStore s1 = new MorpheStore(dir);
        JSONArray l = s1.list();
        check("a corrupt sources.json does not crash: the built-in source alone", l.length() == 1 && "morphe-official".equals(s(l.optJSONObject(0), "id")));
        File bak = new File(dir, "sources.json.bak");
        check("... the damaged file is kept as sources.json.bak", bak.isFile() && new String(Files.readAllBytes(bak.toPath())).equals("{ this is not json"));
        check("... and sources.json is valid again", new MorpheStore(dir).list().length() == 1);
        check("... a bundle that is still on disk is not deleted", new File(dir, "bundles/" + loc + "/bundle.mpp").isFile());
        s1.addLocal(write("again.mpp", good("2.0.0")), null);
        check("... the store works after it", new MorpheStore(dir).list().length() == 2);

        // valid JSON of the wrong shape
        Files.write(sources.toPath(), "[1,2,3]".getBytes());
        check("a sources.json that is an array", new MorpheStore(dir).list().length() == 1);
        Files.write(sources.toPath(), "{\"sources\":\"x\"}".getBytes());
        check("a sources.json whose list is not a list", new MorpheStore(dir).list().length() == 1);
        Files.write(sources.toPath(), new byte[0]);
        check("an empty sources.json", new MorpheStore(dir).list().length() == 1);
        Files.write(sources.toPath(), new byte[] { (byte) 0xff, (byte) 0xfe, 0, 1, 2 });
        check("a sources.json of binary rubbish", new MorpheStore(dir).list().length() == 1);

        // one bad record among good ones
        dir = newBase();
        st = new MorpheStore(dir);
        loc = s(st.addLocal(write("keep2.mpp", good("1.0.0")), "Keeper"), "id");
        sources = new File(dir, "sources.json");
        JSONObject root = new JSONObject(new String(Files.readAllBytes(sources.toPath()), StandardCharsets.UTF_8));
        JSONArray arr = root.optJSONArray("sources");
        String[] bad = {
            "{\"id\":\"../../evil\",\"kind\":\"local\",\"name\":\"x\"}",
            "{\"id\":\"UPPER\",\"kind\":\"local\",\"name\":\"x\"}",
            "{\"id\":\"has/slash\",\"kind\":\"local\",\"name\":\"x\"}",
            "{\"id\":\"weird\",\"kind\":\"banana\",\"name\":\"x\"}",
            "{\"id\":\"gh-bad\",\"kind\":\"remote\",\"host\":\"github\",\"repo\":\"a b/c\",\"name\":\"x\"}",
            "{\"id\":\"url-bad\",\"kind\":\"remote\",\"host\":\"url\",\"url\":\"file:///etc/passwd\",\"name\":\"x\"}",
            "{\"id\":\"host-bad\",\"kind\":\"remote\",\"host\":\"ftp\",\"repo\":\"a/b\",\"name\":\"x\"}",
            "{\"id\":\"" + loc + "\",\"kind\":\"local\",\"name\":\"a duplicate id\"}",
            "\"just a string\"",
            "{\"kind\":\"local\"}",
        };
        for (String b : bad) arr.put(b.startsWith("{") ? new JSONObject(b) : (Object) "just a string");
        Files.write(sources.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        MorpheStore s2 = new MorpheStore(dir);
        l = s2.list();
        check("records that are not ones the app wrote are dropped, the good ones kept", l.length() == 2 && loc.equals(s(l.optJSONObject(1), "id")) && "Keeper".equals(s(l.optJSONObject(1), "name")), l.toString());
        check("... an id like ../../evil is nowhere", s2.get("../../evil") == null && s2.bundleFile("../../evil") == null);
        check("... the file with the bad records is kept as sources.json.bak", new File(dir, "sources.json.bak").isFile());
        check("... nothing was created outside the folder", names(dir.getParentFile()).indexOf("evil") < 0 && !new File(dir, "bundles/evil").exists() && !new File(tmp, "evil").exists());

        // the built-in record cannot be turned into something else from the file
        root = new JSONObject(new String(Files.readAllBytes(new File(dir, "sources.json").toPath()), StandardCharsets.UTF_8));
        JSONObject b0 = root.optJSONArray("sources").optJSONObject(0);
        b0.put("repo", "evil/repo");
        b0.put("host", "url");
        b0.put("url", "https://evil.example/x.json");
        b0.put("name", "Hacked");
        b0.put("enabled", false);
        Files.write(new File(dir, "sources.json").toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject bi = new MorpheStore(dir).get("morphe-official");
        check("the built-in source stays what it is (its choices - enabled - are kept)", "MorpheApp/morphe-patches".equals(s(bi, "repo")) && "github".equals(s(bi, "host")) && "Morphe Patches".equals(s(bi, "name"))
                && !bi.optBoolean("enabled") && bi.optBoolean("builtIn"), bi.toString());

        // the built-in record missing, or not first
        root = new JSONObject(new String(Files.readAllBytes(new File(dir, "sources.json").toPath()), StandardCharsets.UTF_8));
        JSONArray srcs = root.optJSONArray("sources");
        JSONArray swapped = new JSONArray();
        for (int i = srcs.length() - 1; i >= 0; i--) swapped.put(srcs.opt(i));
        root.put("sources", swapped);
        Files.write(new File(dir, "sources.json").toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        check("the built-in source is moved back to the top", "morphe-official".equals(s(new MorpheStore(dir).list().optJSONObject(0), "id")));
        root.put("sources", new JSONArray());
        Files.write(new File(dir, "sources.json").toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        l = new MorpheStore(dir).list();
        check("an empty list gets the built-in source back", l.length() == 1 && "morphe-official".equals(s(l.optJSONObject(0), "id")));

        // leftovers of a crashed download are cleaned
        dir = newBase();
        new MorpheStore(dir);
        File sd = new File(dir, "bundles/gh-own-crash");
        sd.mkdirs();
        Files.write(new File(sd, "bundle.mpp.new.part").toPath(), "half".getBytes());
        File sd2 = new File(dir, "bundles/morphe-official");
        sd2.mkdirs();
        Files.write(new File(sd2, "bundle.mpp.new").toPath(), "half".getBytes());
        new MorpheStore(dir);
        check("a new instance removes half-downloaded files and the empty folders they were in", !sd.exists() && !sd2.exists());
    }

    // ---------------------------------------------------------------- hostile ids and names

    static void traversal() throws Exception {
        reset();
        File root = newBase();
        File dir = new File(root, "store");
        final MorpheStore st = new MorpheStore(dir);
        String[] ids = { "../x", "..", "../../etc/passwd", "a/b", "", "%2e%2e", "morphe-official/../x", "C:\\x" };
        boolean allNull = true, noThrow = true;
        for (String id : ids) {
            if (st.get(id) != null || st.bundleFile(id) != null) allNull = false;
            try { st.setEnabled(id, false); st.setPrerelease(id, true); st.setPatchCount(id, 1); } catch (Throwable t) { noThrow = false; }
        }
        check("get / bundleFile of an id that is not one: null", allNull);
        check("the setters ignore an id that is not one", noThrow);
        boolean allRefused = true;
        for (final String id : ids) {
            if (failure(() -> st.remove(id)) == null || failure(() -> st.rename(id, "n")) == null || failure(() -> st.update(id, null)) == null || failure(() -> st.checkUpdate(id)) == null) allRefused = false;
        }
        check("remove / rename / update / checkUpdate of an id that is not one: an IOException", allRefused);

        // names with path parts are display text only
        File src = write("evil-name.mpp", good("1.0.0"));
        JSONObject r = st.addLocal(src, "../../evil\u0000/\n..\\x");
        check("a name with path parts and control characters is only text", s(r, "name").indexOf('\u0000') < 0 && s(r, "name").indexOf('\n') < 0 && s(r, "name").contains("evil"), s(r, "name"));
        check("... the files are where the id says, not where the name says", new File(s(r, "file")).equals(new File(dir, "bundles/" + s(r, "id") + "/bundle.mpp")));
        r = st.addLocal(src, "x");
        st.rename(s(r, "id"), "../../../escape");
        check("rename to a path is only text too", "../../../escape".equals(s(st.get(s(r, "id")), "name")));
        check("zero-width and right-to-left override characters are dropped from a name", "ABC".equals(s(st.addLocal(src, "A\u202eB\u200bC"), "name")));
        check("a very long name is cut", s(st.addLocal(src, new String(new char[500]).replace('\0', 'n')), "name").length() <= 80);
        String m = failure(() -> st.addRemote("github.com/../etc", null, false, null));
        check("an address with .. is refused", has(m, "Invalid source URL"), m);
        m = failure(() -> st.addRemote("github.com/own/%2e%2e%2f%2e%2e", null, false, null));
        check("an address with an escaped .. is refused", has(m, "Invalid source URL"), m);
        m = failure(() -> st.addRemote("github.com/own/repo/tree/..", null, false, null));
        check("a branch of dots is refused", has(m, "Invalid source URL"), m);

        // every file lies under the folder
        List<String> outside = new ArrayList<String>();
        for (String n : names(root)) if (!n.equals("store")) outside.add(n);
        check("nothing was written next to the folder", outside.isEmpty(), outside.toString());
        check("everything is under bundles/<id>/ or one of the three known files", onlyKnown(dir));
    }

    static boolean onlyKnown(File dir) {
        for (File k : dir.listFiles()) {
            if (k.isDirectory()) {
                if (!k.getName().equals("bundles")) return false;
                for (File b : k.listFiles()) {
                    if (!b.isDirectory() || !b.getName().matches("[a-z0-9-]+")) return false;
                    for (File f : b.listFiles()) if (!f.getName().equals("bundle.mpp")) return false;
                }
            } else if (!(k.getName().equals("sources.json") || k.getName().equals("community.json") || k.getName().equals("sources.json.bak"))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- the community finder

    static final String COMMUNITY = "{\"bundles\":[{\"source\":\"github\",\"repo\":\"a/b\",\"name\":\"A\"},{\"source\":\"github\",\"repo\":\"c/d\",\"name\":\"C\"}],"
            + "\"store\":{\"com.x\":{\"name\":\"X\"}},\"compatibilities\":[],\"featured\":[\"com.x\"]}";

    static void community() throws Exception {
        reset();
        File dir = newBase();
        MorpheStore st = new MorpheStore(dir);
        json("/community.json", COMMUNITY);
        final Prog p = new Prog();
        JSONObject c1 = st.community(false, p);
        check("community: the parsed list, with its four keys", c1.optJSONArray("bundles") != null && c1.optJSONArray("bundles").length() == 2 && c1.optJSONObject("store") != null
                && c1.optJSONArray("compatibilities") != null && c1.optJSONArray("featured") != null);
        check("community: fetchedAt (ms) and stale=false", c1.optLong("fetchedAt") > System.currentTimeMillis() - 60000 && c1.has("stale") && !c1.optBoolean("stale"));
        check("community: the progress was reported", p.done > 0 && p.done == p.total);
        File cache = new File(dir, "community.json");
        check("community: cached in community.json, no temp file left", cache.isFile() && !anyFile(dir, "community.json.new") && !new File(dir, "community.json.tmp").exists());
        check("community: asked once", hitCount("/community.json") == 1);

        JSONObject c2 = st.community(false, null);
        check("community: a second call within 6 hours is served from the cache", hitCount("/community.json") == 1 && c2.optLong("fetchedAt") == c1.optLong("fetchedAt") && !c2.optBoolean("stale")
                && c2.optJSONArray("bundles").length() == 2);
        check("community: a new instance on the same folder uses the cache too", new MorpheStore(dir).community(false, null).optLong("fetchedAt") == c1.optLong("fetchedAt") && hitCount("/community.json") == 1);

        Thread.sleep(15);
        json("/community.json", COMMUNITY.replace("\"featured\":[\"com.x\"]", "\"featured\":[]"));
        JSONObject c3 = st.community(true, null);
        check("community: refresh goes to the network again", hitCount("/community.json") == 2 && c3.optLong("fetchedAt") > c1.optLong("fetchedAt") && c3.optJSONArray("featured").length() == 0 && !c3.optBoolean("stale"));

        // an old cache is refreshed
        JSONObject old = new JSONObject(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
        old.put("fetchedAt", System.currentTimeMillis() - 7L * 3600 * 1000);
        Files.write(cache.toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject c4 = st.community(false, null);
        check("community: a cache older than 6 hours is refreshed", hitCount("/community.json") == 3 && c4.optLong("fetchedAt") > System.currentTimeMillis() - 60000);
        old.put("fetchedAt", System.currentTimeMillis() - 5L * 3600 * 1000);
        Files.write(cache.toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        st.community(false, null);
        check("community: a cache of 5 hours is still used", hitCount("/community.json") == 3);
        old.put("fetchedAt", System.currentTimeMillis() + 3L * 3600 * 1000);
        Files.write(cache.toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        st.community(false, null);
        check("community: a cache dated in the future (the clock was set back) is refreshed", hitCount("/community.json") == 4);

        // the network fails: the cache is returned, marked stale
        status("/community.json", 500);
        JSONObject c5 = st.community(true, null);
        check("community: refresh with the server failing -> the cache, stale=true", c5.optBoolean("stale") && c5.optJSONArray("bundles").length() == 2 && c5.optLong("fetchedAt") > 0);
        check("... the cache file is untouched by the failure", cache.isFile() && st.community(false, null).optJSONArray("bundles") != null);
        old = new JSONObject(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
        old.put("fetchedAt", System.currentTimeMillis() - 8L * 3600 * 1000);
        Files.write(cache.toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject c6 = st.community(false, null);
        check("community: an expired cache and a failing server -> the cache, stale=true", c6.optBoolean("stale") && c6.optJSONArray("bundles").length() == 2);
        long keptAt = old.optLong("fetchedAt");

        json("/community.json", "this is not json");
        JSONObject c7 = st.community(true, null);
        check("community: an answer that is not JSON -> the cache, stale", c7.optBoolean("stale"));
        json("/community.json", "{\"hello\":1}");
        JSONObject c8 = st.community(true, null);
        check("community: an answer of an unknown shape -> the cache, stale", c8.optBoolean("stale"));
        JSONObject onDisk = new JSONObject(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
        check("community: a bad answer never overwrites the cache", onDisk.optLong("fetchedAt") == keptAt && onDisk.optJSONArray("bundles").length() == 2);
        check("community: no temp file after the failures", !anyFile(dir, "community.json.new") && !anyFile(dir, ".part"));

        // nothing cached
        File d2 = newBase();
        final MorpheStore fresh = new MorpheStore(d2);
        status("/community.json", 500);
        String m = failure(() -> fresh.community(false, null));
        check("community: no cache and the server failing -> the IOException", has(m, "community list") && has(m, "500"), m);
        json("/community.json", "{\"hello\":1}");
        m = failure(() -> fresh.community(false, null));
        check("community: no cache and an answer of an unknown shape -> the IOException", has(m, "unexpected format"), m);
        json("/community.json", "not json");
        m = failure(() -> fresh.community(true, null));
        check("community: no cache and an answer that is not JSON -> the IOException", has(m, "not valid"), m);
        MorpheStore.communityUrlOverride = "http://127.0.0.1:1/community.json";     // nothing listens there
        m = failure(() -> fresh.community(true, null));
        check("community: no cache and no network at all -> the IOException", m != null && !m.startsWith("OTHER") && !m.isEmpty(), m);
        check("community: a failure creates no cache", !new File(d2, "community.json").exists());
        MorpheStore.communityUrlOverride = base + "/community.json";
        json("/community.json", COMMUNITY);
        check("community: and it works when the server is back", fresh.community(false, null).optJSONArray("bundles").length() == 2);

        // two calls at once load the list once
        File d3 = newBase();
        final MorpheStore twin = new MorpheStore(d3);
        int h0 = hitCount("/community.json");
        final CountDownLatch go = new CountDownLatch(1);
        final Throwable[] terr = new Throwable[2];
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int n = i;
            ts[i] = new Thread(new Runnable() { public void run() {
                try { go.await(); twin.community(false, null); } catch (Throwable e) { terr[n] = e; }
            } });
            ts[i].start();
        }
        go.countDown();
        for (Thread t : ts) t.join(20000);
        check("community: two calls at once fetch the list once", terr[0] == null && terr[1] == null && hitCount("/community.json") == h0 + 1, String.valueOf(terr[0]) + " " + terr[1] + " " + (hitCount("/community.json") - h0));

        // a corrupt cache is not trusted
        Files.write(new File(d2, "community.json").toPath(), "{ broken".getBytes());
        int before = hitCount("/community.json");
        JSONObject c9 = fresh.community(false, null);
        check("community: a corrupt cache is ignored and fetched again", hitCount("/community.json") == before + 1 && !c9.optBoolean("stale") && c9.optJSONArray("bundles").length() == 2);
        MorpheStore.communityUrlOverride = null;
        check("community: the real address is the default", MorpheStore.COMMUNITY_URL.equals("https://morphe-patches.software/data/bundles.json"));
    }
}
