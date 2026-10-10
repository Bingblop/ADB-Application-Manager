package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** The Helper's download list: start, pause, resume, retry, cancel, remove; at most three at a time; the journal brings a paused or broken job back after a restart; what a bad journal may not do. */
public class HelperDownloadsTest {
    static int n = 0, fails = 0;
    static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static void is(String what, boolean ok, String d) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + d); } }

    static final byte[] BODY = new byte[600000];
    static final List<String> reqs = new CopyOnWriteArrayList<String>();
    static volatile int slowMs = 0;
    static final AtomicInteger live = new AtomicInteger(), maxLive = new AtomicInteger();
    static final ConcurrentHashMap<String, Integer> cut = new ConcurrentHashMap<String, Integer>();

    static boolean journalHas(File j, String what) {
        try { return new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8).contains(what); } catch (IOException e) { return false; }
    }

    static boolean until(java.util.function.BooleanSupplier c, long ms) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) { if (c.getAsBoolean()) return true; Thread.sleep(15); }
        return c.getAsBoolean();
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < BODY.length; i++) BODY[i] = (byte) (i * 13 + 1);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        srv.createContext("/f", ex -> {
            String q = String.valueOf(ex.getRequestURI().getQuery());
            String range = ex.getRequestHeaders().getFirst("Range");
            reqs.add(q + " range=" + range);
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + q.replace("n=", "") + ".apkm\"");
            ex.getResponseHeaders().add("ETag", "\"e-" + q + "\"");
            int from = 0;
            if (range != null) { from = Integer.parseInt(range.substring(6, range.indexOf('-'))); ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (BODY.length - 1) + "/" + BODY.length); }
            int len = BODY.length - from;
            ex.sendResponseHeaders(range != null ? 206 : 200, len);
            int now = live.incrementAndGet(); maxLive.accumulateAndGet(now, Math::max);
            try {
                java.io.OutputStream o = ex.getResponseBody();
                Integer cutAt = cut.remove(q);
                for (int p = 0; p < len; p += 30000) {
                    if (cutAt != null && p >= cutAt) { ex.close(); return; }
                    o.write(BODY, from + p, Math.min(30000, len - p)); o.flush();
                    if (slowMs > 0) try { Thread.sleep(slowMs); } catch (InterruptedException ignored) {}
                }
                ex.close();
            } catch (IOException ignored) { } finally { live.decrementAndGet(); }
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        File root = Files.createTempDirectory("hd").toFile();
        File dir = new File(root, "helper");
        File journal = new File(root, "journal.json");
        java.util.function.Predicate<File> ok = f -> f.equals(dir);
        BrowserDownload.Cookies ck = u -> "sid=1";
        try {
            // ---- a plain download
            HelperDownloads dl = new HelperDownloads(journal, ck, ok);
            final List<String> seen = new CopyOnWriteArrayList<String>();
            dl.addListener(j -> { String s = j.state.name(); if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(s)) seen.add(s); });
            BrowserDownload.Job a = dl.start(base + "/f?n=alpha", "UA", "http://ref/", dir);
            is("start: the job is on the list", dl.list().size() == 1 && dl.get(a.id) == a);
            is("start: it finishes", until(() -> a.state == BrowserDownload.State.DONE, 8000), String.valueOf(a.state) + a.error);
            is("start: the file is whole and has the server's name", a.file != null && a.file.getName().equals("alpha.apkm") && Arrays.equals(Files.readAllBytes(a.file.toPath()), BODY));
            is("start: listeners saw QUEUED, RUNNING, DONE in order", seen.equals(Arrays.asList("QUEUED", "RUNNING", "DONE")), String.valueOf(seen));
            is("journal: written, with the finished job", until(() -> journalHas(journal, "\"DONE\""), 3000));
            JSONObject pg = HelperDownloads.forPage(a);
            is("page view: name, state, sizes, host, canUse; no address and no user agent", pg.optString("name").equals("alpha.apkm") && pg.optString("state").equals("done") && pg.optLong("total") == BODY.length && pg.optString("host").equals("127.0.0.1") && pg.optBoolean("canUse") && !pg.toString().contains("/f?n=") && !pg.toString().contains("UA"), pg.toString());

            // ---- the same address twice is one job
            slowMs = 20; reqs.clear();
            BrowserDownload.Job b = dl.start(base + "/f?n=beta", "UA", null, dir);
            BrowserDownload.Job b2 = dl.start(base + "/f?n=beta", "UA", null, dir);
            is("a second tap on the same download is the same job", b == b2 && dl.list().size() == 2);

            // ---- pause and resume
            is("pause: it was running", until(() -> b.state == BrowserDownload.State.RUNNING && b.done > 60000, 8000), b.state + " " + b.done);
            dl.pause(b.id);
            is("pause: PAUSED, bytes kept, no error", until(() -> b.state == BrowserDownload.State.PAUSED, 8000) && b.error.isEmpty() && b.done > 0 && b.done < BODY.length && new File(dir, "beta.apkm.part").length() == b.done, b.state + " " + b.done);
            long at = b.done;
            is("pause: the journal says PAUSED with the validator", until(() -> journalHas(journal, "\"PAUSED\"") && journalHas(journal, "e-n=beta"), 3000));
            slowMs = 0; reqs.clear();
            dl.resume(b.id);
            is("resume: it finishes", until(() -> b.state == BrowserDownload.State.DONE, 8000), b.state + b.error);
            is("resume: only the rest was asked for, and the file is whole", reqs.size() == 1 && reqs.get(0).contains("range=bytes=" + at + "-") && Arrays.equals(Files.readAllBytes(b.file.toPath()), BODY), String.valueOf(reqs));

            // ---- retry after a broken connection
            cut.put("n=gamma", 120000); reqs.clear();
            BrowserDownload.Job g = dl.start(base + "/f?n=gamma", "UA", null, dir);
            is("retry: the broken connection is FAILED, with words", until(() -> g.state == BrowserDownload.State.FAILED, 8000) && g.error.contains("Retry"), g.state + " " + g.error);
            dl.resume(g.id);
            is("retry: finishes from where it broke", until(() -> g.state == BrowserDownload.State.DONE, 8000) && Arrays.equals(Files.readAllBytes(g.file.toPath()), BODY) && reqs.get(1).contains("range=bytes="), g.state + " " + reqs);
            cut.put("n=gamma2", 100000);
            BrowserDownload.Job g2 = dl.start(base + "/f?n=gamma2", "UA", null, dir);
            until(() -> g2.state == BrowserDownload.State.FAILED, 8000);
            BrowserDownload.Job g3 = dl.start(base + "/f?n=gamma2", "UA", null, dir);
            is("retry: starting the same address again after a failure resumes that job", g3 == g2 && until(() -> g2.state == BrowserDownload.State.DONE, 8000), g2.state + " " + g2.error);

            // ---- at most three at a time; the others wait; a waiting one can be paused
            slowMs = 40; maxLive.set(0);
            BrowserDownload.Job[] five = new BrowserDownload.Job[5];
            for (int i = 0; i < 5; i++) five[i] = dl.start(base + "/f?n=q" + i, "UA", null, dir);
            is("queue: five started, three run, two wait", until(() -> java.util.stream.Stream.of(five).filter(j -> j.state == BrowserDownload.State.RUNNING).count() == 3, 8000) && java.util.stream.Stream.of(five).filter(j -> j.state == BrowserDownload.State.QUEUED).count() == 2, Arrays.toString(java.util.stream.Stream.of(five).map(j -> j.state).toArray()));
            BrowserDownload.Job waiting = java.util.stream.Stream.of(five).filter(j -> j.state == BrowserDownload.State.QUEUED).findFirst().get();
            dl.pause(waiting.id);
            is("queue: pausing a waiting job leaves the queue at once, without downloading anything", waiting.state == BrowserDownload.State.PAUSED && waiting.done == 0 && !new File(dir, waiting.name.isEmpty() ? "none" : waiting.name + ".part").exists());
            slowMs = 0;
            is("queue: the others all finish, never more than three at once", until(() -> java.util.stream.Stream.of(five).filter(j -> j != waiting).allMatch(j -> j.state == BrowserDownload.State.DONE), 20000) && maxLive.get() <= 3, "max live " + maxLive.get());
            dl.resume(waiting.id);
            is("queue: the paused one resumes and finishes", until(() -> waiting.state == BrowserDownload.State.DONE, 8000), waiting.state + "");

            // ---- Pause all and Cancel in the notification: everything that is running or waiting
            slowMs = 40;
            is("active count: nothing is going on before", dl.activeCount() == 0, String.valueOf(dl.activeCount()));
            BrowserDownload.Job[] four = new BrowserDownload.Job[4];
            for (int i = 0; i < 4; i++) four[i] = dl.start(base + "/f?n=pa" + i, "UA", null, dir);
            is("active count: four started, three run and one waits, all four count", until(() -> java.util.stream.Stream.of(four).filter(j -> j.state == BrowserDownload.State.RUNNING && j.done > 30000).count() == 3, 8000) && dl.activeCount() == 4, String.valueOf(dl.activeCount()));
            is("pause all: touches all four", dl.pauseAll() == 4);
            is("pause all: every one is PAUSED (bytes kept for the ones that ran), none is counted any more", until(() -> java.util.stream.Stream.of(four).allMatch(j -> j.state == BrowserDownload.State.PAUSED), 8000) && dl.activeCount() == 0 && dl.pauseAll() == 0, Arrays.toString(java.util.stream.Stream.of(four).map(j -> j.state).toArray()));
            for (BrowserDownload.Job j : four) dl.resume(j.id);
            is("pause all: they can all be resumed", until(() -> dl.activeCount() == 4, 3000));
            is("cancel all: touches all four, and every one is CANCELLED with no part left", dl.cancelAll() == 4 && until(() -> java.util.stream.Stream.of(four).allMatch(j -> j.state == BrowserDownload.State.CANCELLED), 8000) && java.util.stream.Stream.of(four).noneMatch(j -> j.part() != null && j.part().exists()) && dl.activeCount() == 0, Arrays.toString(java.util.stream.Stream.of(four).map(j -> j.state).toArray()));
            is("cancel all: the finished ones are untouched", a.state == BrowserDownload.State.DONE && a.file.isFile());
            slowMs = 0;

            // ---- cancel and remove
            slowMs = 30;
            BrowserDownload.Job c = dl.start(base + "/f?n=cc", "UA", null, dir);
            until(() -> c.state == BrowserDownload.State.RUNNING && c.done > 30000, 8000);
            dl.cancel(c.id);
            is("cancel: CANCELLED and the part is gone", until(() -> c.state == BrowserDownload.State.CANCELLED, 8000) && !new File(dir, "cc.apkm.part").exists(), c.state + "");
            BrowserDownload.Job r = dl.start(base + "/f?n=rr", "UA", null, dir);
            until(() -> r.state == BrowserDownload.State.RUNNING && r.done > 30000, 8000);
            dl.pause(r.id); until(() -> r.state == BrowserDownload.State.PAUSED, 8000);
            dl.cancel(r.id);
            is("cancel: a paused job is cancelled at once and its part removed", r.state == BrowserDownload.State.CANCELLED && !new File(dir, "rr.apkm.part").exists());
            slowMs = 0;
            File alpha = a.file;
            dl.remove(a.id);
            is("remove: off the list, and the finished file stays", dl.get(a.id) == null && alpha.isFile());
            int cleared = dl.clearFinished();
            is("clear finished: DONE and CANCELLED go, files stay", cleared >= 1 && dl.list().stream().noneMatch(j -> j.state == BrowserDownload.State.DONE || j.state == BrowserDownload.State.CANCELLED) && b.file.isFile());

            // ---- the journal brings a paused job back
            for (BrowserDownload.Job j : dl.list()) dl.remove(j.id);
            slowMs = 30;
            BrowserDownload.Job pj = dl.start(base + "/f?n=persist", "UA", "http://ref/", dir);
            until(() -> pj.state == BrowserDownload.State.RUNNING && pj.done > 90000, 8000);
            dl.pause(pj.id); until(() -> pj.state == BrowserDownload.State.PAUSED, 8000);
            // the worker sets PAUSED first and writes the journal a moment later: shutting down in between left the journal of the start (no name, 0 bytes) - wait for the write
            until(() -> { try { String t = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8); return t.contains("\"state\":\"PAUSED\"") && t.contains("persist.apkm"); } catch (IOException e) { return false; } }, 8000);
            long keptBytes = pj.done;
            dl.shutdown();
            slowMs = 0; reqs.clear();
            HelperDownloads again = new HelperDownloads(journal, ck, ok);
            BrowserDownload.Job back = again.get(pj.id);
            is("restart: the paused job is back, PAUSED, with its bytes", back != null && back.state == BrowserDownload.State.PAUSED && back.done == keptBytes && back.name.equals("persist.apkm") && back.etag.contains("e-n=persist") && back.referer.equals("http://ref/"), String.valueOf(back == null ? null : back.state + " " + back.done));
            again.resume(back.id);
            is("restart: resuming goes on from the bytes on disk and finishes whole", until(() -> back.state == BrowserDownload.State.DONE, 8000) && reqs.size() == 1 && reqs.get(0).contains("range=bytes=" + keptBytes + "-") && Arrays.equals(Files.readAllBytes(back.file.toPath()), BODY), back.state + " " + reqs);
            again.shutdown();

            // a job that was running when the app died comes back paused
            JSONArray jj = new JSONArray(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
            jj.getJSONObject(0).put("state", "RUNNING");
            Files.write(journal.toPath(), jj.toString().getBytes(StandardCharsets.UTF_8));
            // (that entry is DONE with a file, so make a part-only one)
            File part = new File(dir, "died.apkm.part"); Files.write(part.toPath(), new byte[7000]);
            JSONObject died = new JSONObject().put("id", "died1").put("url", base + "/f?n=died").put("dir", dir.getAbsolutePath()).put("name", "died.apkm").put("etag", "\"x\"").put("state", "RUNNING").put("total", 600000);
            JSONArray arr = new JSONArray().put(died);
            Files.write(journal.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
            HelperDownloads d3 = new HelperDownloads(journal, ck, ok);
            is("crash: a job that was RUNNING comes back PAUSED with the part's size", d3.get("died1") != null && d3.get("died1").state == BrowserDownload.State.PAUSED && d3.get("died1").done == 7000);
            d3.shutdown();

            // ---- a bad journal
            File evil = new File(root, "evil");
            JSONArray bad = new JSONArray()
                .put(new JSONObject().put("id", "e1").put("url", base + "/f?n=x").put("dir", evil.getAbsolutePath()).put("state", "PAUSED"))
                .put(new JSONObject().put("id", "e2").put("url", "file:///etc/passwd").put("dir", dir.getAbsolutePath()).put("state", "PAUSED"))
                .put(new JSONObject().put("id", "e3").put("url", "javascript:alert(1)").put("dir", dir.getAbsolutePath()).put("state", "PAUSED"))
                .put(new JSONObject().put("id", "e4").put("url", base + "/f?n=x").put("dir", dir.getAbsolutePath()).put("name", "../../escape.apkm").put("state", "PAUSED"))
                .put(new JSONObject().put("id", "e5").put("url", base + "/f?n=x").put("dir", dir.getAbsolutePath()).put("state", "DONE").put("file", "/etc/hostname"))
                .put(new JSONObject().put("id", "e6").put("url", base + "/f?n=x").put("dir", dir.getAbsolutePath()).put("state", "NONSENSE"));
            Files.write(journal.toPath(), bad.toString().getBytes(StandardCharsets.UTF_8));
            HelperDownloads d4 = new HelperDownloads(journal, ck, ok);
            is("bad journal: a folder that is not the Helper's, file:, javascript:, a DONE entry that points elsewhere are ignored", d4.get("e1") == null && d4.get("e2") == null && d4.get("e3") == null && d4.get("e5") == null);
            is("bad journal: a name with a path loses it (never leaves the folder); an unknown state is PAUSED", d4.get("e4") != null && d4.get("e4").name.isEmpty() && d4.get("e6") != null && d4.get("e6").state == BrowserDownload.State.PAUSED);
            Files.write(journal.toPath(), "not json at all {".getBytes(StandardCharsets.UTF_8));
            is("bad journal: garbage gives an empty list, no crash", new HelperDownloads(journal, ck, ok).list().isEmpty());
            d4.shutdown();
        } finally {
            srv.stop(0);
            deleteTree(root);
        }
        System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File x : k) deleteTree(x); f.delete(); }
}
