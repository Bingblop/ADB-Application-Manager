package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * A coding agent's HTTPS call (AiHttp) against a local server: a whole answer, an error answer with its body, a streamed answer
 * arriving piece by piece, Cancel in the middle of a stream, no redirects (a key must not follow one elsewhere), UTF-8 both ways,
 * the headers worth keeping, and plain network failures in words.
 */
public class AiHttpTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

  static final Map<String, String> seenHeaders = new ConcurrentHashMap<String, String>();
  static volatile int modelsHits = 0;

  static void reply(HttpExchange ex, int code, String type, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
    if (b.length > 0) { OutputStream o = ex.getResponseBody(); o.write(b); o.close(); }
    ex.close();
  }

  public static void main(String[] args) throws Exception {
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    srv.setExecutor(Executors.newCachedThreadPool());
    srv.createContext("/v1/models", new HttpHandler() { public void handle(HttpExchange ex) throws IOException {
      modelsHits++;
      for (String k : new String[]{"x-api-key", "anthropic-version", "user-agent", "accept-encoding"}) {
        String v = ex.getRequestHeaders().getFirst(k);
        if (v != null) seenHeaders.put(k, v);
      }
      ex.getResponseHeaders().set("request-id", "req_123");
      reply(ex, 200, "application/json", "{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}");
    } });
    srv.createContext("/v1/denied", new HttpHandler() { public void handle(HttpExchange ex) throws IOException {
      ex.getResponseHeaders().set("retry-after", "7");
      reply(ex, 401, "application/json", "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
    } });
    srv.createContext("/v1/echo", new HttpHandler() { public void handle(HttpExchange ex) throws IOException {
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      InputStream in = ex.getRequestBody(); byte[] b = new byte[4096]; int n;
      while ((n = in.read(b)) != -1) body.write(b, 0, n);
      seenHeaders.put("echo-method", ex.getRequestMethod());
      seenHeaders.put("echo-type", String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
      reply(ex, 200, "application/json", "{\"got\":" + new String(body.toByteArray(), StandardCharsets.UTF_8) + "}");
    } });
    srv.createContext("/v1/stream", new HttpHandler() { public void handle(HttpExchange ex) throws IOException {
      ex.getResponseHeaders().set("Content-Type", "text/event-stream");
      ex.sendResponseHeaders(200, 0);
      OutputStream o = ex.getResponseBody();
      try {
        for (int i = 1; i <= 4; i++) {
          o.write(("data: {\"n\":" + i + ",\"t\":\"piece " + i + " ✓\"}\n\n").getBytes(StandardCharsets.UTF_8));
          o.flush();
          Thread.sleep(150);
        }
        o.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
      } catch (InterruptedException ignored) {}
      o.close();
      ex.close();
    } });
    srv.createContext("/v1/slow", new HttpHandler() { public void handle(HttpExchange ex) throws IOException {
      ex.getResponseHeaders().set("Content-Type", "text/event-stream");
      ex.sendResponseHeaders(200, 0);
      OutputStream o = ex.getResponseBody();
      try {
        o.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
        o.flush();
        Thread.sleep(20000);
        o.write("data: late\n\n".getBytes(StandardCharsets.UTF_8));
      } catch (Exception ignored) {}
      try { o.close(); } catch (IOException ignored) {}
      ex.close();
    } });
    srv.createContext("/v1/redirect", new HttpHandler() { public void handle(HttpExchange ex) throws IOException {
      ex.getResponseHeaders().set("Location", "/v1/models");
      ex.sendResponseHeaders(302, -1);
      ex.close();
    } });
    srv.start();
    String base = "http://127.0.0.1:" + srv.getAddress().getPort();

    // ---------------------------------------------------------------- a whole answer
    AiHttp.Request r = new AiHttp.Request();
    r.method = "GET";
    r.url = base + "/v1/models";
    r.headers.put("x-api-key", "sk-test");
    r.headers.put("anthropic-version", "2023-06-01");
    AiHttp.Response res = new AiHttp().execute(r, null);
    check("a whole answer: status and body", res.status == 200 && res.body.contains("model-b") && res.error.isEmpty() && !res.cancelled);
    check("the headers given are sent", "sk-test".equals(seenHeaders.get("x-api-key")) && "2023-06-01".equals(seenHeaders.get("anthropic-version")));
    check("it says who it is and asks for no compression (so events arrive as written)", "ADB-Application-Manager".equals(seenHeaders.get("user-agent")) && "identity".equals(seenHeaders.get("accept-encoding")));
    check("a request id is kept for support", "req_123".equals(res.headers.get("request-id")));

    // ---------------------------------------------------------------- an error answer
    r = new AiHttp.Request();
    r.method = "GET";
    r.url = base + "/v1/denied";
    res = new AiHttp().execute(r, null);
    check("an error answer keeps its status and body (for a readable message)", res.status == 401 && res.body.contains("authentication_error") && res.error.isEmpty());
    check("a rate-limit wait is kept", "7".equals(res.headers.get("retry-after")));

    // ---------------------------------------------------------------- a JSON body
    r = new AiHttp.Request();
    r.url = base + "/v1/echo";
    r.body = "{\"text\":\"héllo ✓ 日本\"}";
    res = new AiHttp().execute(r, null);
    check("POST with a JSON body, UTF-8 both ways", res.status == 200 && res.body.contains("héllo ✓ 日本") && "POST".equals(seenHeaders.get("echo-method"))
        && seenHeaders.get("echo-type").startsWith("application/json"));

    // ---------------------------------------------------------------- streaming
    r = new AiHttp.Request();
    r.url = base + "/v1/stream";
    r.body = "{}";
    r.stream = true;
    final List<String> chunks = Collections.synchronizedList(new ArrayList<String>());
    final List<Long> times = Collections.synchronizedList(new ArrayList<Long>());
    long t0 = System.currentTimeMillis();
    res = new AiHttp().execute(r, new AiHttp.Sink() { public void onChunk(String t) { chunks.add(t); times.add(System.currentTimeMillis()); } });
    String all = String.join("", chunks);
    check("a stream arrives whole and in order", res.status == 200 && all.indexOf("piece 1") < all.indexOf("piece 4") && all.endsWith("data: [DONE]\n\n") && all.contains("piece 3 ✓"));
    check("... in several pieces as it is written, not all at the end (" + chunks.size() + " pieces)", chunks.size() >= 3 && times.get(0) - t0 < 400);
    check("a streamed answer leaves the body empty", res.body.isEmpty());

    // ---------------------------------------------------------------- cancel
    r = new AiHttp.Request();
    r.url = base + "/v1/slow";
    r.body = "{}";
    r.stream = true;
    final AiHttp call = new AiHttp();
    final CountDownLatch first = new CountDownLatch(1);
    final AiHttp.Request fr = r;
    final AiHttp.Response[] out = new AiHttp.Response[1];
    Thread th = new Thread(new Runnable() { public void run() {
      out[0] = call.execute(fr, new AiHttp.Sink() { public void onChunk(String t) { first.countDown(); } });
    } });
    th.start();
    first.await(5, TimeUnit.SECONDS);
    long c0 = System.currentTimeMillis();
    call.cancel();
    th.join(5000);
    check("Cancel ends a stream mid-way, quickly (" + (System.currentTimeMillis() - c0) + " ms)", out[0] != null && out[0].cancelled && System.currentTimeMillis() - c0 < 3000);
    check("... and says so", "cancelled".equals(out[0] == null ? "" : out[0].error) && call.isCancelled());
    AiHttp pre = new AiHttp();
    pre.cancel();
    AiHttp.Request q = new AiHttp.Request();
    q.method = "GET";
    q.url = base + "/v1/models";
    int before = modelsHits;
    AiHttp.Response pr = pre.execute(q, null);
    check("a call cancelled before it started never goes out", pr.cancelled && modelsHits == before);

    // ---------------------------------------------------------------- no redirects
    before = modelsHits;
    r = new AiHttp.Request();
    r.method = "GET";
    r.url = base + "/v1/redirect";
    r.headers.put("x-api-key", "sk-must-not-follow");
    res = new AiHttp().execute(r, null);
    check("a redirect is not followed (a key never goes on to another address)", res.status == 302 && modelsHits == before);

    // ---------------------------------------------------------------- failures in words
    srv.stop(0);
    r = new AiHttp.Request();
    r.method = "GET";
    r.url = base + "/v1/models";
    r.connectTimeoutMs = 3000;
    res = new AiHttp().execute(r, null);
    check("nothing listening: \"Could not connect\"", res.status == 0 && res.error.startsWith("Could not connect"));
    r.url = "http://no-such-host.invalid/v1/models";
    res = new AiHttp().execute(r, null);
    check("an unknown host: a readable message", res.status == 0 && !res.error.isEmpty());
    r.url = "not a url";
    res = new AiHttp().execute(r, null);
    check("a broken address: a readable message", res.status == 0 && !res.error.isEmpty());
    check("describe() words", AiHttp.describe(new java.net.SocketTimeoutException("x")).contains("too long")
        && AiHttp.describe(new java.net.UnknownHostException("h")).contains("No internet"));

    System.out.println(fails == 0 ? "ALL PASS" : "FAILURES: " + fails);
    System.exit(fails == 0 ? 0 : 1);
  }
}
