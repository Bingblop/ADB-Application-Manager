package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** What the in-app browser saves: names from Content-Disposition and the address, the browser's cookies / user agent / referer on every hop, redirects, refusals in words, a web page is not a file, partial files never stay. */
public class BrowserDownloadTest {
    static int n = 0, fails = 0;
    static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static void is(String what, boolean ok, String d) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + d); } }
    static final Map<String, String> seen = new ConcurrentHashMap<String, String>();

    public static void main(String[] args) throws Exception {
        // ---- names ----
        is("name: filename in Content-Disposition", BrowserDownload.fileName("https://x/dl.php?id=1", "attachment; filename=\"com.google.android.inputmethod.latin_18.0.3_apkmirror.com.apkm\"", "application/octet-stream").equals("com.google.android.inputmethod.latin_18.0.3_apkmirror.com.apkm"));
        is("name: filename* (UTF-8) wins over filename (the accent becomes _)", BrowserDownload.fileName("https://x/a", "attachment; filename=\"a.apk\"; filename*=UTF-8''caf%C3%A9.apks", "").equals("caf_.apks"));
        is("name: unquoted filename", BrowserDownload.fileName("https://x/a", "attachment; filename=app.xapk; size=5", "").equals("app.xapk"));
        is("name: from the address when there is no header", BrowserDownload.fileName("https://cdn.example/files/My%20App-1.2.apk?token=abc", null, "application/octet-stream").equals("My App-1.2.apk"));
        is("name: download.php with an Android package type becomes download.apk", BrowserDownload.fileName("https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=9&key=k", null, "application/vnd.android.package-archive").equals("download.apk"));
        is("name: path tricks and odd characters are removed", BrowserDownload.fileName("https://x/a", "attachment; filename=\"../../etc/pass:wd*.apk\"", "").equals("pass_wd_.apk"), BrowserDownload.fileName("https://x/a", "attachment; filename=\"../../etc/pass:wd*.apk\"", ""));
        is("name: a hidden-file name loses its dots", !BrowserDownload.fileName("https://x/a", "attachment; filename=\".hidden.apk\"", "").startsWith("."));
        is("name: very long names are cut, the extension kept", BrowserDownload.fileName("https://x/a", "attachment; filename=\"" + "a".repeat(300) + ".apkm\"", "").length() <= 120 && BrowserDownload.fileName("https://x/a", "attachment; filename=\"" + "a".repeat(300) + ".apkm\"", "").endsWith(".apkm"));

        // ---- a local server ----
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final byte[] body = new byte[300000];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31);
        srv.createContext("/file", ex -> {
            seen.put("ua", String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            seen.put("cookie", String.valueOf(ex.getRequestHeaders().getFirst("Cookie")));
            seen.put("referer", String.valueOf(ex.getRequestHeaders().getFirst("Referer")));
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"example_1.0.apkm\"");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        srv.createContext("/redirect", ex -> { ex.getResponseHeaders().add("Location", "/file"); ex.sendResponseHeaders(302, -1); ex.close(); });
        srv.createContext("/loop", ex -> { ex.getResponseHeaders().add("Location", "/loop"); ex.sendResponseHeaders(302, -1); ex.close(); });
        srv.createContext("/forbidden", ex -> { ex.sendResponseHeaders(403, -1); ex.close(); });
        srv.createContext("/page", ex -> { byte[] b = "<html>challenge</html>".getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.createContext("/short", ex -> { ex.getResponseHeaders().add("Content-Type", "application/octet-stream"); ex.sendResponseHeaders(200, 100000); ex.getResponseBody().write(new byte[1000]); ex.close(); });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        File dir = Files.createTempDirectory("bd").toFile();
        try {
            final StringBuilder asked = new StringBuilder();
            BrowserDownload.Cookies ck = url -> { asked.append(url).append('\n'); return "cf_clearance=abc; sid=1"; };
            final long[] last = {0, 0};
            BrowserDownload.Progress pr = new BrowserDownload.Progress() { public void onProgress(long d, long t) { last[0] = d; last[1] = t; } public boolean cancelled() { return false; } };
            BrowserDownload.Result r = BrowserDownload.download(base + "/file", "Mozilla/5.0 (Linux; Android 14) Chrome/121", ck, base + "/page-it-came-from", dir, pr);
            is("saved under the server's name, byte for byte", r.file.getName().equals("example_1.0.apkm") && Arrays.equals(Files.readAllBytes(r.file.toPath()), body) && r.size == body.length, r.file.getName());
            is("the browser's user agent, cookies and referer were sent", seen.get("ua").contains("Chrome/121") && seen.get("cookie").equals("cf_clearance=abc; sid=1") && seen.get("referer").endsWith("/page-it-came-from"), String.valueOf(seen));
            is("progress ends at the whole size", last[0] == body.length && last[1] == body.length, last[0] + "/" + last[1]);
            is("no .part file is left", Arrays.stream(dir.list()).noneMatch(f -> f.endsWith(".part")), Arrays.toString(dir.list()));
            BrowserDownload.Result r2 = BrowserDownload.download(base + "/redirect", "UA", ck, null, dir, null);
            is("a redirect is followed (the cookies are asked for each address) and a second copy gets a free name", r2.file.getName().equals("example_1.0 (1).apkm") && asked.toString().contains("/redirect") && asked.toString().contains("/file"), r2.file.getName() + " " + asked);
            Throwable t = err(() -> BrowserDownload.download(base + "/forbidden", "UA", null, null, dir, null));
            is("403 says that the browser check comes first", t instanceof IOException && t.getMessage().contains("403") && t.getMessage().contains("browser check"), String.valueOf(t));
            t = err(() -> BrowserDownload.download(base + "/page", "UA", null, null, dir, null));
            is("a web page is refused (it is a challenge or an error page, not the file)", t != null && t.getMessage().contains("web page"), String.valueOf(t));
            t = err(() -> BrowserDownload.download(base + "/loop", "UA", null, null, dir, null));
            is("a redirect loop ends", t != null && t.getMessage().contains("redirected too often"), String.valueOf(t));
            // which redirects may be followed (the same judgement as HttpSafe.open): no step down from https to http, no outside address leading to this phone
            is("a relative redirect stays on its host", BrowserDownload.nextHop("https://a.example/x/y", "/z").equals("https://a.example/z"));
            is("http to https and http to http are followed", BrowserDownload.nextHop("http://a.example/x", "https://b.example/z").equals("https://b.example/z") && BrowserDownload.nextHop("http://a.example/x", "http://b.example/z").equals("http://b.example/z"));
            is("a loopback address may lead to another loopback address (a local server)", BrowserDownload.nextHop("http://127.0.0.1:8080/a", "http://127.0.0.1:8080/b").equals("http://127.0.0.1:8080/b"));
            for (String[] bad : new String[][]{
                    {"https://a.example/x", "http://a.example/y"}, {"https://a.example/x", "http://b.example/y"},
                    {"https://a.example/x", "http://127.0.0.1:8080/y"}, {"https://a.example/x", "https://localhost/y"},
                    {"http://a.example/x", "http://[::1]/y"}, {"https://a.example/x", "http://2130706433/y"}, {"https://a.example/x", "https://0x7f.1/y"}}) {
                Throwable rt = err(() -> BrowserDownload.nextHop(bad[0], bad[1]));
                is("refused: " + bad[0] + " -> " + bad[1], rt instanceof IOException && rt.getMessage().contains("not safe to follow"), String.valueOf(rt));
            }
            Throwable ft = err(() -> BrowserDownload.nextHop("https://a.example/x", "ftp://b.example/y"));
            is("a redirect to something that is not a web address is refused in words", ft instanceof IOException && ft.getMessage().contains("not a web address"), String.valueOf(ft));
            ft = err(() -> BrowserDownload.nextHop("https://a.example/x", "javascript:alert(1)"));
            is("a script address is refused", ft instanceof IOException, String.valueOf(ft));
            t = err(() -> BrowserDownload.download(base + "/short", "UA", null, null, dir, null));
            is("a download that ends early is an error", t instanceof IOException, String.valueOf(t));
            String[] left = dir.list();
            is("only the two good files are in the folder", left.length == 2, Arrays.toString(left));
            t = err(() -> BrowserDownload.download("file:///etc/passwd", "UA", null, null, dir, null));
            is("only http and https", t != null && t.getMessage().contains("web addresses"), String.valueOf(t));
            t = err(() -> BrowserDownload.download("javascript:alert(1)", "UA", null, null, dir, null));
            is("javascript: and the like are refused", t != null);
            BrowserDownload.Progress cancel = new BrowserDownload.Progress() { public void onProgress(long d, long tt) {} public boolean cancelled() { return true; } };
            t = err(() -> BrowserDownload.download(base + "/file", "UA", null, null, dir, cancel));
            is("cancelling stops and removes the part", t != null && t.getMessage().contains("cancelled") && dir.list().length == 2, String.valueOf(t) + Arrays.toString(dir.list()));
            resumeTests(srv, base);
            sameNameTests();
        } finally {
            srv.stop(0);
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
        System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- pause, resume, retry
    static final java.util.List<String> reqs = new CopyOnWriteArrayList<String>();
    static volatile int cutAt = -1;                 // /ranged: end the connection after this many bytes of the answer (once)
    static volatile String etagNow = "\"v1\"";
    static volatile int slowMs = 0;

    static void sendBody(HttpExchange ex, byte[] body, int from, int cut) throws IOException {
        int len = body.length - from;
        ex.sendResponseHeaders(from > 0 ? 206 : 200, len);
        java.io.OutputStream o = ex.getResponseBody();
        int step = 20000;
        for (int p = 0; p < len; p += step) {
            int n = Math.min(step, len - p);
            if (cut >= 0 && p >= cut) { ex.close(); return; }
            o.write(body, from + p, n);
            o.flush();
            if (slowMs > 0) try { Thread.sleep(slowMs); } catch (InterruptedException ignored) {}
        }
        ex.close();
    }

    static void resumeTests(HttpServer srv, String base) throws Exception {
        final byte[] body = new byte[400000];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 7 + 3);
        srv.createContext("/ranged", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range"), ifr = ex.getRequestHeaders().getFirst("If-Range");
            reqs.add("range=" + range + " if-range=" + ifr + " cookie=" + ex.getRequestHeaders().getFirst("Cookie"));
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"big.apkm\"");
            ex.getResponseHeaders().add("ETag", etagNow);
            ex.getResponseHeaders().add("Accept-Ranges", "bytes");
            int from = 0;
            if (range != null && range.startsWith("bytes=") && (ifr == null || ifr.equals(etagNow))) {
                from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                if (from >= body.length) { ex.sendResponseHeaders(416, -1); ex.close(); return; }
                ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
            }
            int cut = cutAt; if (cut >= 0) cutAt = -1;
            sendBody(ex, body, from, cut);
        });
        srv.createContext("/noranges", ex -> {
            reqs.add("noranges range=" + ex.getRequestHeaders().getFirst("Range"));
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"plain.apks\"");
            ex.getResponseHeaders().add("ETag", "\"p\"");
            int cut = cutAt; if (cut >= 0) cutAt = -1;
            sendBody(ex, body, 0, cut);
        });
        srv.createContext("/signed", ex -> {            // a link that works once (the page link), then sends to a storage address that keeps working
            reqs.add("signed " + ex.getRequestURI().getPath());
            ex.getResponseHeaders().add("Location", "/ranged");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        File dir = Files.createTempDirectory("bdr").toFile();
        try {
            // a connection that breaks leaves the part, and Retry goes on from there
            cutAt = 100000; slowMs = 0; etagNow = "\"v1\""; reqs.clear();
            BrowserDownload.Job j = new BrowserDownload.Job("a", base + "/ranged", "UA", "http://ref/", dir);
            BrowserDownload.Cookies ck = u -> "sid=9";
            BrowserDownload.run(j, ck, null);
            is("resume: a broken connection is FAILED with words and the part is kept", j.state == BrowserDownload.State.FAILED && (j.error.contains("connection broke") || j.error.contains("ended early")) && j.error.contains("Retry") && j.part().length() > 0 && j.part().length() < body.length, j.state + " " + j.error);
            long kept = j.part().length();
            is("resume: the job knows the name, size and validator", j.name.equals("big.apkm") && j.total == body.length && j.etag.equals("\"v1\"") && j.done == kept, j.name + " " + j.total + " " + j.etag + " " + j.done + "/" + kept);
            BrowserDownload.run(j, ck, null);
            is("resume: Retry asks for the rest only (Range from the bytes on disk, If-Range with the ETag, the cookies again)", reqs.size() == 2 && reqs.get(1).equals("range=bytes=" + kept + "- if-range=\"v1\" cookie=sid=9"), String.valueOf(reqs));
            is("resume: the finished file is the whole file, byte for byte", j.state == BrowserDownload.State.DONE && j.file.getName().equals("big.apkm") && Arrays.equals(Files.readAllBytes(j.file.toPath()), body), j.state + " " + j.error);
            is("resume: no .part is left", !j.part().exists() && dir.list().length == 1, Arrays.toString(dir.list()));
            j.file.delete();

            // pause keeps the part, resume completes
            slowMs = 25; reqs.clear(); cutAt = -1;
            final BrowserDownload.Job p = new BrowserDownload.Job("b", base + "/ranged", "UA", null, dir);
            final boolean[] paused = {false};
            BrowserDownload.Listener pauseAt = jj -> { if (!paused[0] && jj.done >= 100000) { paused[0] = true; jj.pause(); } };
            BrowserDownload.run(p, null, pauseAt);
            is("pause: stops with the bytes in hand, state PAUSED, no error", p.state == BrowserDownload.State.PAUSED && p.error.isEmpty() && p.done >= 100000 && p.done < body.length, p.state + " " + p.done);
            is("pause: the part holds exactly what was read", p.part().length() == p.done, p.part().length() + " vs " + p.done);
            slowMs = 0;
            final long pausedAt = p.done;
            BrowserDownload.run(p, null, null);
            is("pause: resume finishes it, from where it stopped", p.state == BrowserDownload.State.DONE && Arrays.equals(Files.readAllBytes(p.file.toPath()), body) && reqs.get(1).startsWith("range=bytes=" + pausedAt + "-"), String.valueOf(reqs) + " pausedAt=" + pausedAt);
            p.file.delete();

            // cancel removes the part
            slowMs = 25; reqs.clear();
            final BrowserDownload.Job c = new BrowserDownload.Job("c", base + "/ranged", "UA", null, dir);
            BrowserDownload.run(c, null, jj -> { if (jj.done >= 50000) jj.cancel(); });
            is("cancel: state CANCELLED and the part is gone", c.state == BrowserDownload.State.CANCELLED && !c.part().exists() && dir.list().length == 0, c.state + Arrays.toString(dir.list()));
            slowMs = 0;

            // the file changed on the server (the validator no longer matches): the answer is the whole new file, the old part is not mixed in
            cutAt = 120000; etagNow = "\"v1\""; reqs.clear();
            BrowserDownload.Job ch = new BrowserDownload.Job("d", base + "/ranged", "UA", null, dir);
            BrowserDownload.run(ch, null, null);
            is("changed: first try breaks", ch.state == BrowserDownload.State.FAILED);
            etagNow = "\"v2\"";
            BrowserDownload.run(ch, null, null);
            is("changed: a new validator gives the whole file again (200), which replaces the old part", ch.state == BrowserDownload.State.DONE && Arrays.equals(Files.readAllBytes(ch.file.toPath()), body) && ch.etag.equals("\"v2\""), ch.state + " " + ch.error + " " + ch.etag);
            ch.file.delete();

            // a server that does not do ranges: Retry starts over, the result is still right
            cutAt = 90000; reqs.clear();
            BrowserDownload.Job nr = new BrowserDownload.Job("e", base + "/noranges", "UA", null, dir);
            BrowserDownload.run(nr, null, null);
            is("noranges: the break is FAILED, part kept", nr.state == BrowserDownload.State.FAILED && nr.part().exists());
            BrowserDownload.run(nr, null, null);
            is("noranges: Retry asked for a range, got the whole file, and the result is whole and not doubled", nr.state == BrowserDownload.State.DONE && nr.file.length() == body.length && Arrays.equals(Files.readAllBytes(nr.file.toPath()), body) && reqs.get(1).contains("range=bytes="), nr.state + " " + nr.file.length() + " " + reqs);
            nr.file.delete();

            // a part without a validator is not trusted
            BrowserDownload.Job nv = new BrowserDownload.Job("f", base + "/ranged", "UA", null, dir);
            nv.name = "big.apkm";
            File lone = nv.part();
            Files.write(lone.toPath(), new byte[5000]);
            reqs.clear(); cutAt = -1;
            BrowserDownload.run(nv, null, null);
            is("a leftover part with no validator is not trusted: the file starts over", nv.state == BrowserDownload.State.DONE && Arrays.equals(Files.readAllBytes(nv.file.toPath()), body) && reqs.get(0).startsWith("range=null"), nv.state + " " + reqs);
            nv.file.delete();

            // 416: the part is longer than the file
            BrowserDownload.Job big = new BrowserDownload.Job("g", base + "/ranged", "UA", null, dir);
            big.name = "big.apkm"; big.etag = "\"v2\""; etagNow = "\"v2\""; reqs.clear();
            Files.write(big.part().toPath(), new byte[body.length + 10]);
            BrowserDownload.run(big, null, null);
            is("416: a part longer than the file starts over once and succeeds", big.state == BrowserDownload.State.DONE && Arrays.equals(Files.readAllBytes(big.file.toPath()), body), big.state + " " + big.error + " " + reqs);
            big.file.delete();

            // the page link works once; Retry uses the address it ended at
            cutAt = 80000; etagNow = "\"v1\""; reqs.clear();
            BrowserDownload.Job sg = new BrowserDownload.Job("h", base + "/signed", "UA", null, dir);
            BrowserDownload.run(sg, null, null);
            is("signed: the address it ended at is remembered", sg.state == BrowserDownload.State.FAILED && sg.resolvedUrl.endsWith("/ranged"), sg.state + " " + sg.resolvedUrl);
            reqs.clear();
            BrowserDownload.run(sg, null, null);
            is("signed: Retry goes straight to that address (the page link is not asked again) and finishes", sg.state == BrowserDownload.State.DONE && reqs.size() == 1 && reqs.get(0).startsWith("range=bytes="), sg.state + " " + reqs);
            sg.file.delete();

            // a refusal on a retry says so and keeps the part
            cutAt = 60000; reqs.clear(); etagNow = "\"v1\"";
            BrowserDownload.Job rf = new BrowserDownload.Job("i", base + "/ranged", "UA", null, dir);
            BrowserDownload.run(rf, null, null);
            File keep = rf.part();
            is("refusal: setup (part kept)", rf.state == BrowserDownload.State.FAILED && keep.exists());
            BrowserDownload.Job ghost = new BrowserDownload.Job("i", base + "/forbidden", "UA", null, dir);      // the same job, its link now refused
            ghost.name = "big.apkm"; ghost.etag = "\"v1\"";
            BrowserDownload.run(ghost, null, null);
            is("refusal: 403 on a resume is FAILED with the browser-check advice and the part is kept for another try", ghost.state == BrowserDownload.State.FAILED && ghost.error.contains("browser check") && keep.exists(), ghost.state + " " + ghost.error);
            keep.delete();

            // a state listener sees RUNNING first and the end state last
            final java.util.List<BrowserDownload.State> seenStates = new java.util.ArrayList<BrowserDownload.State>();
            BrowserDownload.Job ls = new BrowserDownload.Job("k", base + "/file", "UA", null, dir);
            BrowserDownload.run(ls, null, jj -> { if (seenStates.isEmpty() || seenStates.get(seenStates.size() - 1) != jj.state) seenStates.add(jj.state); });
            is("listener: RUNNING, then DONE", seenStates.equals(Arrays.asList(BrowserDownload.State.RUNNING, BrowserDownload.State.DONE)), String.valueOf(seenStates));
            ls.file.delete();
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    // ---------------------------------------------------------------- two jobs, one name (NET-1)
    /** Two jobs whose server names their file the same ("app.apk": a fresh signed link to the same file, or two different "download.apk"s) each keep their own part file. */
    static void sameNameTests() throws Exception {
        final int size = 400000;
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());     // both answers at the same time
        srv.createContext("/same", ex -> {
            String key = String.valueOf(ex.getRequestURI().getQuery()).replace("key=", "");
            byte fill = (byte) ("1".equals(key) ? 'A' : 'B');
            String range = ex.getRequestHeaders().getFirst("Range"), ifr = ex.getRequestHeaders().getFirst("If-Range");
            String etag = "\"k" + key + "\"";
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"app.apk\"");
            ex.getResponseHeaders().add("ETag", etag);
            int from = 0;
            if (range != null && range.startsWith("bytes=") && (ifr == null || ifr.equals(etag))) {
                from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                if (from >= size) { ex.sendResponseHeaders(416, -1); ex.close(); return; }
                ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (size - 1) + "/" + size);
            }
            int len = size - from;
            ex.sendResponseHeaders(from > 0 ? 206 : 200, len);
            java.io.OutputStream o = ex.getResponseBody();
            byte[] chunk = new byte[20000];
            Arrays.fill(chunk, fill);
            try {
                for (int p = 0; p < len; p += chunk.length) {
                    o.write(chunk, 0, Math.min(chunk.length, len - p));
                    o.flush();
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                }
            } catch (IOException gone) {        // the client paused: it hung up
            }
            ex.close();
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        File dir = Files.createTempDirectory("bds").toFile();
        try {
            BrowserDownload.Job x = new BrowserDownload.Job("s1", base + "/same?key=1", "UA", null, dir), y = new BrowserDownload.Job("s2", base + "/same?key=2", "UA", null, dir);
            x.name = "app.apk"; y.name = "app.apk";
            is("same name: two jobs get two part files", !x.part().equals(y.part()) && x.part().getParentFile().equals(dir), x.part() + " " + y.part());
            BrowserDownload.Job evil = new BrowserDownload.Job("../../x", base + "/same?key=1", "UA", null, dir);
            evil.name = "app.apk";
            is("same name: an id from a journal cannot lead the part file out of the folder", evil.part().getParentFile().equals(dir) && !evil.part().getName().contains("/"), evil.part().getPath());
            x.name = ""; y.name = "";

            // at the same time
            Thread tx = new Thread(() -> BrowserDownload.run(x, null, null)), ty = new Thread(() -> BrowserDownload.run(y, null, null));
            tx.start(); ty.start(); tx.join(30000); ty.join(30000);
            is("same name, at the same time: both finish", x.state == BrowserDownload.State.DONE && y.state == BrowserDownload.State.DONE, x.state + " " + x.error + " / " + y.state + " " + y.error);
            is("same name, at the same time: two files, each holds only its own bytes", x.file != null && y.file != null && !x.file.equals(y.file) && only(x.file, 'A', size) && only(y.file, 'B', size), x.file + " " + y.file);
            is("same name, at the same time: no part is left", dir.list().length == 2 && Arrays.stream(dir.list()).noneMatch(f -> f.endsWith(".part")), Arrays.toString(dir.list()));
            for (File f : dir.listFiles()) f.delete();

            // one paused, another of that name started and paused, the first resumed: it goes on from its own bytes, not the other's
            BrowserDownload.Job a = new BrowserDownload.Job("p1", base + "/same?key=1", "UA", null, dir), b = new BrowserDownload.Job("p2", base + "/same?key=2", "UA", null, dir);
            BrowserDownload.run(a, null, jj -> { if (jj.done >= 100000) jj.pause(); });
            BrowserDownload.run(b, null, jj -> { if (jj.done >= 200000) jj.pause(); });
            is("same name, paused: both are PAUSED with their bytes", a.state == BrowserDownload.State.PAUSED && b.state == BrowserDownload.State.PAUSED && a.part().length() == a.done && b.part().length() == b.done, a.state + " " + a.done + " / " + b.state + " " + b.done);
            BrowserDownload.run(a, null, null);
            is("same name, paused: the first resumes into a whole file of its own bytes", a.state == BrowserDownload.State.DONE && only(a.file, 'A', size), a.state + " " + a.error + " " + (a.file == null ? "" : a.file.length()));
            BrowserDownload.run(b, null, null);
            is("same name, paused: the second too, under a free name", b.state == BrowserDownload.State.DONE && only(b.file, 'B', size) && !b.file.equals(a.file), b.state + " " + b.error + " " + b.file);
        } finally {
            srv.stop(0);
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    static boolean only(File f, char c, int size) throws IOException {
        if (f == null || !f.isFile()) return false;
        byte[] b = Files.readAllBytes(f.toPath());
        if (b.length != size) return false;
        for (byte x : b) if (x != (byte) c) return false;
        return true;
    }

    interface Call { void run() throws Exception; }
    static Throwable err(Call c) { try { c.run(); return null; } catch (Throwable t) { return t; } }
}
