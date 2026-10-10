package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/** Redirects are followed by hand: never https to http, never an outside address to the phone itself, keys stay with their host, at most 8 hops. */
public class HttpSafeTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static volatile String seenAuth = null;

  public static void main(String[] args) throws Exception {
    // pure policy
    check("https to https", HttpSafe.redirectAllowed("https://a.example/x", "https://b.example/y"));
    check("http to https", HttpSafe.redirectAllowed("http://a.example/x", "https://b.example/y"));
    check("https to http is a downgrade", !HttpSafe.redirectAllowed("https://a.example/x", "http://b.example/y"));
    check("https to http loopback is refused", !HttpSafe.redirectAllowed("https://a.example/x", "http://127.0.0.1:5042/"));
    check("http outside to http loopback is refused", !HttpSafe.redirectAllowed("http://a.example/x", "http://localhost:8080/"));
    check("http outside to http outside", HttpSafe.redirectAllowed("http://a.example/x", "http://b.example/y"));
    check("http loopback to http loopback (a local test server)", HttpSafe.redirectAllowed("http://127.0.0.1:1/x", "http://localhost:2/y"));
    check("other schemes are refused", !HttpSafe.redirectAllowed("https://a.example/x", "ftp://b.example/y") && !HttpSafe.redirectAllowed("https://a.example/x", "file:///etc/passwd"));
    check("outside https to https loopback is refused too", !HttpSafe.redirectAllowed("https://a.example/x", "https://127.0.0.1/y") && !HttpSafe.redirectAllowed("https://a.example/x", "https://localhost/y"));
    check("the whole 127/8 range, localhost. and *.localhost are loopback", HttpSafe.isLoopback("127.0.0.2") && HttpSafe.isLoopback("127.255.255.254") && HttpSafe.isLoopback("localhost.") && HttpSafe.isLoopback("app.localhost") && HttpSafe.isLoopback("LOCALHOST"));
    check("0.0.0.0, ::, ::1, [::1], the long ::1 and the mapped forms are loopback", HttpSafe.isLoopback("0.0.0.0") && HttpSafe.isLoopback("::") && HttpSafe.isLoopback("::1") && HttpSafe.isLoopback("[::1]") && HttpSafe.isLoopback("0:0:0:0:0:0:0:1") && HttpSafe.isLoopback("::ffff:127.0.0.1"));
    check("abbreviated and other numeric IPv4 forms of loopback: 127.1, 127.0.1, 2130706433, 0x7f.1, 0177.0.0.1, 0x7f000001, 0",
        HttpSafe.isLoopback("127.1") && HttpSafe.isLoopback("127.0.1") && HttpSafe.isLoopback("2130706433") && HttpSafe.isLoopback("0x7f.1") && HttpSafe.isLoopback("0177.0.0.1") && HttpSafe.isLoopback("0x7f000001") && HttpSafe.isLoopback("0"));
    check("compressed and mapped IPv6 forms: 0:0::1, ::0001, ::ffff:7f00:1, [0::1]",
        HttpSafe.isLoopback("0:0::1") && HttpSafe.isLoopback("::0001") && HttpSafe.isLoopback("::ffff:7f00:1") && HttpSafe.isLoopback("[0::1]"));
    check("numeric forms that are not loopback: 128.1, 1, 0x80.1, 3232235777, 2::1, 300.1, 09.1",
        !HttpSafe.isLoopback("128.1") && !HttpSafe.isLoopback("1") && !HttpSafe.isLoopback("0x80.1") && !HttpSafe.isLoopback("3232235777") && !HttpSafe.isLoopback("2::1") && !HttpSafe.isLoopback("300.1") && !HttpSafe.isLoopback("09.1"));
    check("outside http to 127.1 and to [0::1] is refused", !HttpSafe.redirectAllowed("http://a.example/x", "http://127.1:8080/y") && !HttpSafe.redirectAllowed("https://a.example/x", "https://[0:0::1]/y"));
    check("ordinary hosts and addresses are not", !HttpSafe.isLoopback("example.com") && !HttpSafe.isLoopback("128.0.0.1") && !HttpSafe.isLoopback("126.255.255.255") && !HttpSafe.isLoopback("localhost.example.com") && !HttpSafe.isLoopback("10.0.0.1") && !HttpSafe.isLoopback("") && !HttpSafe.isLoopback(null));
    check("outside http to 127.0.0.2 is refused", !HttpSafe.redirectAllowed("http://a.example/x", "http://127.0.0.2:8080/y"));
    check("same origin needs scheme, host and effective port", HttpSafe.sameOrigin("https://a.example/x", "https://A.example:443/y") && !HttpSafe.sameOrigin("https://a.example/x", "https://a.example:8443/y") && !HttpSafe.sameOrigin("https://a.example/x", "http://a.example/y") && !HttpSafe.sameOrigin("https://a.example/x", "https://b.example/y"));
    check("garbage is refused", !HttpSafe.redirectAllowed("https://a.example/x", "not a url") && !HttpSafe.redirectAllowed(null, null));

    // real redirects against a local server
    final HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final int port = srv.getAddress().getPort();
    srv.createContext("/ok", ex -> { seenAuth = ex.getRequestHeaders().getFirst("Authorization"); byte[] b = "hello".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
    srv.createContext("/r1", ex -> { ex.getResponseHeaders().add("Location", "/ok"); ex.sendResponseHeaders(302, -1); ex.close(); });
    srv.createContext("/loop", ex -> { ex.getResponseHeaders().add("Location", "/loop"); ex.sendResponseHeaders(302, -1); ex.close(); });
    srv.createContext("/down", ex -> { ex.getResponseHeaders().add("Location", "ftp://example.invalid/never"); ex.sendResponseHeaders(302, -1); ex.close(); });
    srv.createContext("/other", ex -> { ex.getResponseHeaders().add("Location", "http://localhost:" + port + "/ok"); ex.sendResponseHeaders(302, -1); ex.close(); });
    srv.createContext("/noloc", ex -> { ex.sendResponseHeaders(302, -1); ex.close(); });
    srv.createContext("/missing", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
    srv.start();
    try {
      String base = "http://127.0.0.1:" + port;
      Map<String, String> h = new LinkedHashMap<String, String>();
      h.put("Authorization", "Bearer secret");
      seenAuth = null;
      HttpURLConnection c = HttpSafe.open(base + "/ok", 3000, 3000, h);
      check("a plain answer comes back with its status and body", c.getResponseCode() == 200 && read(c).equals("hello"));
      check("the header is sent to the first host", "Bearer secret".equals(seenAuth));
      c.disconnect();
      seenAuth = "unset";
      c = HttpSafe.open(base + "/r1", 3000, 3000, h);
      check("a same-host redirect is followed, the header kept", c.getResponseCode() == 200 && read(c).equals("hello") && "Bearer secret".equals(seenAuth));
      c.disconnect();
      seenAuth = "unset";
      c = HttpSafe.open(base + "/other", 3000, 3000, h);
      check("a redirect to another host name is followed, but the Authorization header is dropped", c.getResponseCode() == 200 && seenAuth == null);
      c.disconnect();
      c = HttpSafe.open(base + "/missing", 3000, 3000, h);
      check("a 404 is returned to the caller as is", c.getResponseCode() == 404);
      c.disconnect();
      check("an endless loop is cut", fails("/loop", base, h).contains("too many redirects"));
      check("a redirect to another scheme is refused before any connection", fails("/down", base, h).contains("refused a redirect"));
      check("a redirect without Location is an error", fails("/noloc", base, h).contains("without a Location"));
    } finally { srv.stop(0); }
    System.out.println(fails == 0 ? "PASS HttpSafeTest: " + n + " checks" : "FAILED HttpSafeTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
  static String fails(String path, String base, Map<String, String> h) {
    try { HttpSafe.open(base + path, 3000, 3000, h).disconnect(); return "no error"; } catch (IOException e) { return String.valueOf(e.getMessage()); }
  }
  static String read(HttpURLConnection c) throws IOException {
    InputStream in = c.getInputStream();
    byte[] b = new byte[100]; int k = in.read(b); in.close();
    return new String(b, 0, Math.max(k, 0));
  }
}
