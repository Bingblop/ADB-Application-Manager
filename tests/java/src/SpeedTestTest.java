package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;

/**
 * The internet speed test's runner against a local server that stands in for speed.cloudflare.com (GET /__down?bytes=N, POST /__up, a colo
 * header): the order and shape of the events, live progress several times a second, the byte cap, Stop at once (connections closed, nothing
 * after "done"), a new start stopping the old test, failures in words, request checks, the pool's threads ending, HttpSafe.post (a streamed
 * body of a fixed length, no redirect followed), and a scan of the bridge and onDestroy in MainActivity.
 */
public class SpeedTestTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static final AtomicLong upBytes = new AtomicLong();
  static final AtomicInteger openDown = new AtomicInteger();
  static final AtomicBoolean failPing = new AtomicBoolean(false), failDown = new AtomicBoolean(false);
  static final AtomicLong postBytes = new AtomicLong();
  static final AtomicInteger okHits = new AtomicInteger();

  static final class Events implements SpeedTest.Sink {
    final List<JSONObject> list = Collections.synchronizedList(new ArrayList<JSONObject>());
    volatile long doneAt;
    public void emit(String json) {
      try { JSONObject o = new JSONObject(json); list.add(o); if ("done".equals(o.optString("type"))) doneAt = System.nanoTime(); } catch (Exception e) { list.add(new JSONObject()); }
    }
    List<JSONObject> of(String type) { List<JSONObject> r = new ArrayList<JSONObject>(); synchronized (list) { for (JSONObject o : list) if (type.equals(o.optString("type"))) r.add(o); } return r; }
    JSONObject done() { List<JSONObject> d = of("done"); return d.isEmpty() ? null : d.get(0); }
    boolean waitDone(long ms) throws InterruptedException { long until = System.currentTimeMillis() + ms; while (done() == null && System.currentTimeMillis() < until) Thread.sleep(10); return done() != null; }
    int index(JSONObject o) { synchronized (list) { return list.indexOf(o); } }
  }

  public static void main(String[] args) throws Exception {
    final HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
    final int port = srv.getAddress().getPort();
    srv.createContext("/__down", ex -> {
      String q = ex.getRequestURI().getQuery();
      long want = Long.parseLong(q.substring(q.indexOf('=') + 1));
      if ((want == 0 && failPing.get()) || (want > 0 && failDown.get())) { ex.sendResponseHeaders(500, -1); ex.close(); return; }
      ex.getResponseHeaders().add("colo", "TST");
      ex.sendResponseHeaders(200, want == 0 ? -1 : want);
      if (want > 0) {
        openDown.incrementAndGet();
        byte[] b = new byte[32 * 1024];
        try (OutputStream o = ex.getResponseBody()) {
          long left = want;
          while (left > 0) { int k = (int) Math.min(b.length, left); o.write(b, 0, k); left -= k; Thread.sleep(1); }   // about 30 MB/s a stream
        } catch (Exception e) { /* the client closed it */ } finally { openDown.decrementAndGet(); }
      }
      ex.close();
    });
    srv.createContext("/__up", ex -> {
      byte[] b = new byte[32 * 1024];
      try (InputStream in = ex.getRequestBody()) { int k; while ((k = in.read(b)) >= 0) { upBytes.addAndGet(k); Thread.sleep(1); } } catch (IOException | InterruptedException e) { /* closed */ }   // about 30 MB/s a stream, like the download: unthrottled, a fast machine fills the 100 MB cap before the page gets its live progress
      ex.getResponseHeaders().add("cf-meta-colo", "TST");
      try { ex.sendResponseHeaders(200, -1); } catch (IOException ignored) {}
      ex.close();
    });
    srv.createContext("/post", ex -> {
      byte[] b = new byte[8192]; long got = 0;
      try (InputStream in = ex.getRequestBody()) { int k; while ((k = in.read(b)) >= 0) got += k; }
      postBytes.set(got);
      byte[] r = ("got " + got).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, r.length); ex.getResponseBody().write(r); ex.close();
    });
    srv.createContext("/redir", ex -> { try (InputStream in = ex.getRequestBody()) { while (in.read() >= 0) {} } ex.getResponseHeaders().add("Location", "/ok"); ex.sendResponseHeaders(307, -1); ex.close(); });
    srv.createContext("/ok", ex -> { okHits.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close(); });
    srv.start();
    String base = "http://127.0.0.1:" + port;
    ExecutorService pool = SpeedTest.newPool();
    try {
      // ---------------------------------------------------------------- HttpSafe.post
      final long len = 3L * 1024 * 1024;
      final AtomicBoolean opened = new AtomicBoolean(false);
      HttpURLConnection c = HttpSafe.post(base + "/post", 3000, 3000, null, len, new HttpSafe.Body() {
        public void opened(HttpURLConnection conn) { opened.set(conn != null); }
        public void writeTo(OutputStream out) throws IOException { byte[] b = new byte[100000]; long left = len; while (left > 0) { int k = (int) Math.min(b.length, left); out.write(b, 0, k); left -= k; } }
      });
      check("HttpSafe.post streams a 3 MB body of a fixed length; the server gets exactly that; the status is read", c.getResponseCode() == 200 && postBytes.get() == len && opened.get());
      c.disconnect();
      boolean refused = false;
      try { HttpSafe.post(base + "/redir", 3000, 3000, null, 10, body(10)); } catch (IOException e) { refused = e.getMessage().contains("redirect"); }
      check("HttpSafe.post refuses a redirect (307) instead of sending the upload on, and nothing reaches the target", refused && okHits.get() == 0);

      // ---------------------------------------------------------------- a whole test
      SpeedTest.Config cfg = cfg(base);
      SpeedTest.Runner runner = new SpeedTest.Runner(cfg);
      Events ev = new Events();
      upBytes.set(0);
      String r = runner.start("{\"id\":\"st1\"}", pool, ev);
      check("start answers ok with the id", new JSONObject(r).optBoolean("ok") && "st1".equals(new JSONObject(r).optString("id")));
      check("the test ends with one done event", ev.waitDone(15000) && ev.of("done").size() == 1);
      JSONObject done = ev.done();
      check("done: ok, with ping, jitter, download and upload speeds above 0, the bytes and the server code", done.optBoolean("ok") && done.has("pingMs") && done.has("jitterMs")
          && done.optDouble("downMbps") > 0 && done.optDouble("upMbps") > 0 && done.optLong("downBytes") > 0 && done.optLong("upBytes") > 0 && "TST".equals(done.optString("colo")));
      boolean allId = true; synchronized (ev.list) { for (JSONObject o : ev.list) if (!"st1".equals(o.optString("id"))) allId = false; }
      check("every event carries the test's id", allId);
      List<JSONObject> phases = ev.of("phase");
      check("the phases come in order: latency, download, upload", phases.size() == 3 && "latency".equals(phases.get(0).optString("phase")) && "download".equals(phases.get(1).optString("phase")) && "upload".equals(phases.get(2).optString("phase")));
      check("download and upload tell the page their length and ramp-up", phases.get(1).optLong("phaseMs") == cfg.phaseMs && phases.get(1).optLong("rampMs") == cfg.rampMs && phases.get(2).optLong("phaseMs") == cfg.phaseMs);
      List<JSONObject> pings = ev.of("ping");
      boolean pingShape = pings.size() == cfg.pings;
      for (int i = 0; i < pings.size(); i++) pingShape &= pings.get(i).optInt("n") == i + 1 && pings.get(i).optInt("of") == cfg.pings && pings.get(i).has("ms") && pings.get(i).has("medianMs");
      check("one ping event per sample (the warm-up is not one), numbered n of " + cfg.pings + ", each with the running median", pingShape);
      JSONObject lat = ev.of("latency").get(0);
      check("then the latency: the median and the jitter of " + cfg.pings + " samples", lat.optInt("samples") == cfg.pings && lat.has("pingMs") && lat.has("jitterMs") && lat.optDouble("pingMs") == done.optDouble("pingMs"));
      List<JSONObject> server = ev.of("server");
      check("the server's code is announced once, before the download", server.size() == 1 && "TST".equals(server.get(0).optString("colo")) && ev.index(server.get(0)) < ev.index(phases.get(1)));
      List<JSONObject> downProg = new ArrayList<JSONObject>(), upProg = new ArrayList<JSONObject>();
      for (JSONObject o : ev.of("progress")) (o.optString("phase").equals("download") ? downProg : upProg).add(o);
      boolean rising = true; long lastT = -1, lastB = -1;
      for (JSONObject o : downProg) { if (o.optLong("t") <= lastT || o.optLong("bytes") < lastB) rising = false; lastT = o.optLong("t"); lastB = o.optLong("bytes"); }
      check("live download progress several times a second (" + downProg.size() + " in " + cfg.phaseMs + " ms), time and bytes only going up", downProg.size() >= cfg.phaseMs / 1000 * 4 && rising);
      check("live upload progress too (" + upProg.size() + ")", upProg.size() >= cfg.phaseMs / 1000 * 4);
      boolean liveMbps = false; for (JSONObject o : downProg) if (o.optDouble("mbps") > 0) liveMbps = true;
      check("the live speed is a number above 0 while bytes move", liveMbps);
      List<JSONObject> results = ev.of("result");
      check("a result per phase, with the speed, the bytes and the time; the done event repeats them", results.size() == 2 && results.get(0).optDouble("mbps") == done.optDouble("downMbps")
          && results.get(1).optDouble("mbps") == done.optDouble("upMbps") && results.get(0).optLong("bytes") == done.optLong("downBytes") && results.get(0).optLong("ms") >= cfg.phaseMs);
      check("the server received upload bytes (" + upBytes.get() + ", reported " + done.optLong("upBytes") + ")", upBytes.get() > 0 && upBytes.get() <= done.optLong("upBytes"));
      Thread.sleep(300);
      check("nothing comes after done", ev.list.get(ev.list.size() - 1).optString("type").equals("done"));
      check("no download connection stays open after the test", waitFor(() -> openDown.get() == 0, 3000));

      // ---------------------------------------------------------------- the byte cap
      SpeedTest.Config capped = cfg(base);
      capped.downCap = 3L * 1000 * 1000; capped.phaseMs = 5000;
      Events evCap = new Events();
      new SpeedTest.Runner(capped).start("{\"id\":\"cap\"}", pool, evCap);
      check("a capped test ends", evCap.waitDone(20000));
      JSONObject capRes = evCap.of("result").get(0);
      check("the download stops at the cap (" + capRes.optLong("bytes") + " bytes, " + capRes.optLong("ms") + " ms of 5000), just past it at most", capRes.optLong("bytes") >= capped.downCap && capRes.optLong("bytes") < capped.downCap + 4L * 1024 * 1024 && capRes.optLong("ms") < 5000);

      // ---------------------------------------------------------------- Stop
      SpeedTest.Config slow = cfg(base);
      slow.phaseMs = 8000;
      SpeedTest.Runner stopper = new SpeedTest.Runner(slow);
      Events evStop = new Events();
      stopper.start("{\"id\":\"st2\"}", pool, evStop);
      check("a progress event of the download arrives", waitFor(() -> { for (JSONObject o : evStop.of("progress")) if ("download".equals(o.optString("phase"))) return true; return false; }, 10000));
      stopper.cancel("other");
      Thread.sleep(150);
      check("Stop with another id does nothing", evStop.done() == null);
      long t0 = System.nanoTime();
      stopper.cancel("st2");
      check("Stop ends the test with done {ok:false, stopped:true}", evStop.waitDone(5000) && !evStop.done().optBoolean("ok") && evStop.done().optBoolean("stopped"));
      long tookMs = (evStop.doneAt - t0) / 1000000L;
      check("at once (" + tookMs + " ms), not at the end of the phase", tookMs < 1500);
      check("its download connections are closed (the server sees them go)", waitFor(() -> openDown.get() == 0, 3000));
      int after = evStop.list.size(); Thread.sleep(400);
      check("and nothing comes after its done", evStop.list.size() == after && evStop.of("upload").isEmpty() && noPhase(evStop, "upload"));

      // ---------------------------------------------------------------- a new start stops the running one
      SpeedTest.Runner twice = new SpeedTest.Runner(slow);
      Events a = new Events(), bEv = new Events();
      twice.start("{\"id\":\"a1\"}", pool, a);
      waitFor(() -> !a.of("ping").isEmpty(), 5000);
      twice.start("{\"id\":\"b1\"}", pool, bEv);
      check("a second start stops the first (done stopped for a1)", a.waitDone(5000) && a.done().optBoolean("stopped"));
      check("and runs the second", waitFor(() -> !bEv.of("ping").isEmpty(), 5000));
      twice.shutdown();
      check("shutdown stops the running test", bEv.waitDone(5000) && bEv.done().optBoolean("stopped"));
      check("and refuses a new one", !new JSONObject(twice.start("{\"id\":\"c1\"}", pool, new Events())).optBoolean("ok"));

      // ---------------------------------------------------------------- failures in words
      failPing.set(true);
      Events evFail = new Events();
      new SpeedTest.Runner(cfg(base)).start("{\"id\":\"f1\"}", pool, evFail);
      check("no answer to the latency requests: done with the error 'no answer from the test server'", evFail.waitDone(10000) && !evFail.done().optBoolean("ok") && "no answer from the test server".equals(evFail.done().optString("error")));
      failPing.set(false); failDown.set(true);
      Events evFail2 = new Events();
      new SpeedTest.Runner(cfg(base)).start("{\"id\":\"f2\"}", pool, evFail2);
      check("a download that only gets errors: 'download failed: the server answered 500'", evFail2.waitDone(15000) && "download failed: the server answered 500".equals(evFail2.done().optString("error")));
      failDown.set(false);
      Events evNone = new Events();
      SpeedTest.Config nowhere = cfg("http://127.0.0.1:1");
      new SpeedTest.Runner(nowhere).start("{\"id\":\"f3\"}", pool, evNone);
      check("no server at all: done with an error, not a hang", evNone.waitDone(15000) && !evNone.done().optBoolean("ok") && !evNone.done().optString("error").isEmpty());

      // ---------------------------------------------------------------- request checks
      SpeedTest.Runner v = new SpeedTest.Runner(cfg(base));
      check("a request that is not JSON is refused", "bad request".equals(new JSONObject(v.start("nope", pool, new Events())).optString("error")));
      check("an id with other characters is refused", "bad id".equals(new JSONObject(v.start("{\"id\":\"a b\"}", pool, new Events())).optString("error")));
      check("an id of 41 characters is refused, 40 is not", "bad id".equals(new JSONObject(v.start("{\"id\":\"" + rep('x', 41) + "\"}", pool, new Events())).optString("error"))
          && new JSONObject(v.start("{\"id\":\"" + rep('x', 40) + "\"}", pool, new Events())).optBoolean("ok"));
      v.shutdown();
      check("no id is refused", "bad id".equals(new JSONObject(new SpeedTest.Runner(cfg(base)).start("{}", pool, new Events())).optString("error")));
      check("the most a default test can move is its two caps, 350 MB (what the mobile-data warning names)", new SpeedTest.Runner(null).maxBytes() == 350L * 1000 * 1000);

      // ---------------------------------------------------------------- threads end
      check("the pool's threads finish their work once the tests are over", waitFor(() -> ((ThreadPoolExecutor) pool).getActiveCount() == 0, 12000));

      // ---------------------------------------------------------------- the bridge in MainActivity
      File ma = find("src/com/bloatware/bingblop/MainActivity.java");
      if (ma == null) { System.out.println("SKIP MainActivity.java not found"); }
      else {
        String src = new String(Files.readAllBytes(ma.toPath()), StandardCharsets.UTF_8);
        int destroy = src.indexOf("protected void onDestroy()");
        String od = destroy < 0 ? "" : src.substring(destroy, Math.min(src.length(), destroy + 8000));
        check("MainActivity: speedTestStart, speedTestStop and speedTestNetInfo are bridge methods", src.matches("(?s).*@JavascriptInterface\\s+public String speedTestStart\\(String requestJson\\).*") && src.matches("(?s).*@JavascriptInterface\\s+public void speedTestStop\\(String id\\).*") && src.matches("(?s).*@JavascriptInterface\\s+public String speedTestNetInfo\\(\\).*"));
        check("MainActivity: events go to window.onSpeedTest as one quoted JSON text", src.contains("notifyJs(\"window.onSpeedTest && window.onSpeedTest(\" + JSONObject.quote(json) + \")\")"));
        check("MainActivity: the network info names the test's byte cap", src.contains("o.put(\"maxBytes\", speedTest.maxBytes());"));
        check("MainActivity: onDestroy stops the runner and the pool", od.contains("speedTest.shutdown()") && od.contains("speedTestExec.shutdownNow()"));
      }
    } finally {
      pool.shutdownNow();
      srv.stop(0);
    }
    System.out.println(fails == 0 ? "PASS SpeedTestTest: " + n + " checks" : "FAILED SpeedTestTest: " + fails + " of " + n);
    if (fails > 0) System.exit(1);
  }

  static SpeedTest.Config cfg(String base) {
    SpeedTest.Config c = new SpeedTest.Config();
    c.base = base; c.pings = 6; c.phaseMs = 1500; c.rampMs = 300; c.tickMs = 50; c.reportMs = 100; c.windowMs = 500;
    c.downChunk = 2L * 1000 * 1000; c.upChunk = 1000L * 1000; c.connectMs = 2000; c.readMs = 3000;
    return c;
  }

  static HttpSafe.Body body(final int len) {
    return new HttpSafe.Body() {
      public void opened(HttpURLConnection conn) {}
      public void writeTo(OutputStream out) throws IOException { out.write(new byte[len]); }
    };
  }

  static boolean noPhase(Events e, String phase) { for (JSONObject o : e.of("phase")) if (phase.equals(o.optString("phase"))) return false; return true; }

  interface Cond { boolean ok() throws Exception; }
  static boolean waitFor(Cond c, long ms) throws Exception { long until = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < until) { if (c.ok()) return true; Thread.sleep(20); } return c.ok(); }

  static String rep(char ch, int k) { StringBuilder b = new StringBuilder(); for (int i = 0; i < k; i++) b.append(ch); return b.toString(); }

  static File find(String rel) {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) { File f = new File(d, rel); if (f.isFile()) return f; }
    return null;
  }
}
