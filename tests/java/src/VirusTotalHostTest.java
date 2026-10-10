package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpServer;

/** The VirusTotal API key goes only to https addresses on the API's own host (the upload address of a big file comes from the server's answer), and no redirect carries it elsewhere. */
public class VirusTotalHostTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  public static void main(String[] args) throws Exception {
    check("the API itself", VirusTotal.isApiHost("https://www.virustotal.com/api/v3/files"));
    check("a real-looking upload address", VirusTotal.isApiHost("https://www.virustotal.com/_ah/upload/AMmfu6b/abc="));
    check("upper-case host", VirusTotal.isApiHost("https://WWW.VirusTotal.com/x"));
    check("explicit default port", VirusTotal.isApiHost("https://www.virustotal.com:443/x"));
    check("another host is refused", !VirusTotal.isApiHost("https://evil.example/upload"));
    check("a look-alike suffix is refused", !VirusTotal.isApiHost("https://www.virustotal.com.evil.example/x"));
    check("a look-alike prefix is refused", !VirusTotal.isApiHost("https://evilwww.virustotal.com/x"));
    check("another sub-domain is refused", !VirusTotal.isApiHost("https://upload.virustotal.com/x"));
    check("user info trick is refused", !VirusTotal.isApiHost("https://www.virustotal.com@evil.example/x"));
    check("user info on the real host is refused", !VirusTotal.isApiHost("https://user:pw@www.virustotal.com/x"));
    check("plain http is refused", !VirusTotal.isApiHost("http://www.virustotal.com/x"));
    check("another port is refused", !VirusTotal.isApiHost("https://www.virustotal.com:8443/x"));
    check("a loopback address is refused", !VirusTotal.isApiHost("https://127.0.0.1/x") && !VirusTotal.isApiHost("http://localhost:8080/x"));
    check("other schemes are refused", !VirusTotal.isApiHost("ftp://www.virustotal.com/x") && !VirusTotal.isApiHost("file:///etc/passwd"));
    check("garbage and empty are refused", !VirusTotal.isApiHost("") && !VirusTotal.isApiHost("not a url") && !VirusTotal.isApiHost(null));
    // ---- request level, against a local server standing in for the API (the "other host" is the same server under another name) ----
    final java.util.concurrent.atomic.AtomicInteger evilHits = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<String> evilKey = new java.util.concurrent.atomic.AtomicReference<String>(null);
    final java.util.concurrent.atomic.AtomicInteger apiKeyHits = new java.util.concurrent.atomic.AtomicInteger();
    final HttpServer srv = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    final int port = srv.getAddress().getPort();
    srv.createContext("/evil", ex -> { evilHits.incrementAndGet(); evilKey.set(ex.getRequestHeaders().getFirst("x-apikey")); byte[] b = "{}".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
    srv.createContext("/api/v3/files/upload_url", ex -> { apiKeyHits.incrementAndGet(); byte[] b = ("{\"data\":\"http://localhost:" + port + "/evil\"}").getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
    srv.createContext("/api/v3/files/aaaa", ex -> { apiKeyHits.incrementAndGet(); ex.getResponseHeaders().add("Location", "http://localhost:" + port + "/evil"); ex.sendResponseHeaders(302, -1); ex.close(); });
    srv.createContext("/api/v3/files", ex -> {      // the direct upload: answers with a redirect to the other name
      if (ex.getRequestURI().getPath().equals("/api/v3/files")) { apiKeyHits.incrementAndGet(); ex.getRequestBody().readAllBytes(); ex.getResponseHeaders().add("Location", "http://localhost:" + port + "/evil"); ex.sendResponseHeaders(307, -1); }
      else ex.sendResponseHeaders(404, -1);
      ex.close();
    });
    srv.start();
    String savedBase = VirusTotal.apiBase; long savedLimit = VirusTotal.directUploadLimit;
    try {
      VirusTotal.apiBase = "http://127.0.0.1:" + port + "/api/v3";
      VirusTotal.insecureForTests = true;
      check("(with the test base) the same host passes, the other name does not", VirusTotal.isApiHost("http://127.0.0.1:" + port + "/x") && !VirusTotal.isApiHost("http://localhost:" + port + "/x"));
      java.io.File f = java.io.File.createTempFile("vtest", ".apk");
      java.nio.file.Files.write(f.toPath(), new byte[2048]);

      // 1. a big file: the upload address the server hands out is on another host -> refused, nothing sent there
      VirusTotal.directUploadLimit = 1024;
      String err = "";
      try { VirusTotal.uploadAndWait("SECRETKEY", f, "ab", 1000); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
      check("an upload address on another host is refused with a clear message", err.contains("not on its own https host"));
      check("and the other host never saw a request or the key", evilHits.get() == 0 && evilKey.get() == null);

      // 2. a GET answered with a redirect to the other name -> not followed
      evilHits.set(0);
      String err2 = "";
      try { VirusTotal.lookup("SECRETKEY", "aaaa"); } catch (Exception e) { err2 = String.valueOf(e.getMessage()); }
      check("a redirected lookup fails as an HTTP error", err2.contains("HTTP 302"));
      check("and the redirect target never saw a request or the key", evilHits.get() == 0 && evilKey.get() == null);

      // 3. a POST (small file, direct upload) answered with a redirect -> not followed
      VirusTotal.directUploadLimit = 32L << 20;
      evilHits.set(0);
      String err3 = "";
      try { VirusTotal.uploadAndWait("SECRETKEY", f, "ab", 1000); } catch (Exception e) { err3 = String.valueOf(e.getMessage()); }
      check("a redirected upload fails as an HTTP error", err3.contains("HTTP 307"));
      check("and the redirect target never saw the file or the key", evilHits.get() == 0 && evilKey.get() == null);
      check("the API itself did receive the requests (the test reached it)", apiKeyHits.get() >= 3);
    } finally {
      VirusTotal.apiBase = savedBase; VirusTotal.directUploadLimit = savedLimit; VirusTotal.insecureForTests = false;
      srv.stop(0);
    }
    System.out.println(fails == 0 ? "PASS VirusTotalHostTest: " + n + " checks" : "FAILED VirusTotalHostTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
