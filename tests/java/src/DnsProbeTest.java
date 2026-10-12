package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The DNS speed test (DnsProbe) against local servers on 127.0.0.1: a DNS-over-TLS server with a certificate made at test time
 * (keytool) that answers correctly, and servers that answer wrongly in every way listed in the brief; plus request validation,
 * the host name rules against the page's own function, the median, the cap of 6 at a time, cancel, the JSON the page receives
 * and a scan of the bridge in MainActivity. Names are pointed at 127.0.0.1 and the port at the test server through the probe's
 * injection points; production uses the real resolver, port 853 and the platform's trust store.
 */
public class DnsProbeTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { check(what, ok, null); }
  static void check(String what, boolean ok, String detail) {
    n++;
    if (!ok) { fails++; System.out.println("FAIL " + what + (detail == null ? "" : ": " + detail)); }
  }

  static final String NAME = "dot.test.example";                     // the good certificate names this
  static final String WILD = "wild.test.example";                    // the wildcard certificate names *.wild.test.example

  static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
    @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "test-pool"); t.setDaemon(true); return t; }
  });

  // ---------------------------------------------------------------------------------------------------------------------------
  // certificates
  // ---------------------------------------------------------------------------------------------------------------------------

  static File tmp;
  static KeyStore ksGood, ksOther, ksWild, ksUntrusted;
  static SSLContext serverGood, serverOther, serverWild, serverUntrusted;
  static SSLSocketFactory clientTrust;                                // trusts good, other and wild, not untrusted
  static KeyStore trustStore;

  static KeyStore makeKeyStore(String alias, String san) throws Exception {
    File f = new File(tmp, alias + ".p12");
    List<String> cmd = new ArrayList<String>(Arrays.asList("keytool", "-genkeypair", "-alias", alias, "-keyalg", "EC", "-keysize", "256", "-sigalg", "SHA256withECDSA",
        "-dname", "CN=dns probe test", "-ext", "SAN=dns:" + san, "-validity", "30", "-keystore", f.getPath(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    byte[] out = readAll(p.getInputStream());
    if (p.waitFor() != 0) throw new IOException("keytool failed: " + new String(out, StandardCharsets.UTF_8));
    KeyStore ks = KeyStore.getInstance("PKCS12");
    InputStream in = Files.newInputStream(f.toPath());
    try { ks.load(in, "changeit".toCharArray()); } finally { in.close(); }
    return ks;
  }

  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    for (int r; (r = in.read(buf)) > 0; ) b.write(buf, 0, r);
    return b.toByteArray();
  }

  static SSLContext serverContext(KeyStore ks) throws Exception {
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, "changeit".toCharArray());
    SSLContext c = SSLContext.getInstance("TLS");
    c.init(kmf.getKeyManagers(), null, null);
    return c;
  }

  static X509Certificate cert(KeyStore ks) throws Exception {
    return (X509Certificate) ks.getCertificate(ks.aliases().nextElement());
  }

  static void setupCertificates() throws Exception {
    tmp = Files.createTempDirectory("dnsprobe").toFile();
    ksGood = makeKeyStore("good", NAME);
    ksOther = makeKeyStore("other", "other.example");
    ksWild = makeKeyStore("wild", "*." + WILD);
    ksUntrusted = makeKeyStore("untrusted", NAME);
    serverGood = serverContext(ksGood);
    serverOther = serverContext(ksOther);
    serverWild = serverContext(ksWild);
    serverUntrusted = serverContext(ksUntrusted);
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("good", cert(ksGood));
    trust.setCertificateEntry("other", cert(ksOther));
    trust.setCertificateEntry("wild", cert(ksWild));
    trustStore = trust;
    clientTrust = factoryWith(trustOf(trust));
  }


  /** Accepts every certificate and checks nothing (no chain, no host name): only DnsProbe's own check of the certificate's names is left. */
  static final class TrustAll extends X509ExtendedTrustManager {
    @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
    @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
    @Override public void checkClientTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {}
    @Override public void checkServerTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {}
    @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
    @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
  }

  /** The real trust check, and a note of the endpoint identification the probe asked the TLS stack for when it was called. */
  static final class Recording extends X509ExtendedTrustManager {
    final X509ExtendedTrustManager inner;
    final List<String> algorithms = new CopyOnWriteArrayList<String>();
    Recording(X509ExtendedTrustManager inner) { this.inner = inner; }
    @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws java.security.cert.CertificateException { inner.checkClientTrusted(c, a, s); }
    @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws java.security.cert.CertificateException {
      algorithms.add(String.valueOf(((SSLSocket) s).getSSLParameters().getEndpointIdentificationAlgorithm()));
      inner.checkServerTrusted(c, a, s);
    }
    @Override public void checkClientTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) throws java.security.cert.CertificateException { inner.checkClientTrusted(c, a, e); }
    @Override public void checkServerTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) throws java.security.cert.CertificateException { inner.checkServerTrusted(c, a, e); }
    @Override public void checkClientTrusted(X509Certificate[] c, String a) throws java.security.cert.CertificateException { inner.checkClientTrusted(c, a); }
    @Override public void checkServerTrusted(X509Certificate[] c, String a) throws java.security.cert.CertificateException { inner.checkServerTrusted(c, a); }
    @Override public X509Certificate[] getAcceptedIssuers() { return inner.getAcceptedIssuers(); }
  }

  static SSLSocketFactory factoryWith(TrustManager tm) throws Exception {
    SSLContext c = SSLContext.getInstance("TLS");
    c.init(null, new TrustManager[] {tm}, null);
    return c.getSocketFactory();
  }

  static X509ExtendedTrustManager trustOf(KeyStore trust) throws Exception {
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    return (X509ExtendedTrustManager) tmf.getTrustManagers()[0];
  }

  static void deleteTree(File f) {
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) deleteTree(k);
    f.delete();
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // a DNS-over-TLS server that can misbehave
  // ---------------------------------------------------------------------------------------------------------------------------

  enum B {
    GOOD, NXDOMAIN, SERVFAIL, REFUSED, WRONG_ID, NOT_RESPONSE,                 // answers (the last three of these are wrong)
    CLOSE,                                                                      // closes right after the handshake
    NOANSWER,                                                                   // reads the query and says nothing
    LEN0, SHORT5, GARBAGE, TRUNCATED,                                           // a length prefix of 0, of 5, garbage bytes, a promise of 100 bytes that stops after 10
    DRIP,                                                                       // the answer one byte every 200 ms
    FRAGMENTED,                                                                 // the good answer one byte per write
    BIG,                                                                        // a good answer of 65535 bytes (the largest the prefix can say)
    PLAIN_SILENT, PLAIN_HTTP, PLAIN_DRIP                                        // not TLS at all: accepts and says nothing / an HTTP error / a TLS record header that promises 16 KB, then one byte every 100 ms
  }

  static final class Server implements Closeable {
    final ServerSocket ss;
    final int port;
    final B[] script;
    final AtomicInteger accepted = new AtomicInteger(), finished = new AtomicInteger(), queriesSeen = new AtomicInteger();
    final List<byte[]> queries = new CopyOnWriteArrayList<byte[]>();
    final List<String> sni = new CopyOnWriteArrayList<String>();
    volatile boolean stop;

    /** ctx null: a plain socket (for the "not TLS" behaviours). The behaviour of connection k is script[min(k, last)]. */
    Server(SSLContext ctx, B... script) throws Exception {
      this.script = script;
      this.ss = ctx == null ? new ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"))
          : ctx.getServerSocketFactory().createServerSocket(0, 100, InetAddress.getByName("127.0.0.1"));
      this.port = ss.getLocalPort();
      Thread t = new Thread(new Runnable() { @Override public void run() { acceptLoop(); } }, "test-dot-accept");
      t.setDaemon(true);
      t.start();
    }

    void acceptLoop() {
      while (!stop) {
        final Socket s;
        try { s = ss.accept(); } catch (IOException e) { return; }
        final B b = script[Math.min(accepted.getAndIncrement(), script.length - 1)];
        Thread h = new Thread(new Runnable() {
          @Override public void run() {
            try { handle(s, b); } catch (Throwable ignored) {}
            finally { finished.incrementAndGet(); try { s.close(); } catch (IOException ignored) {} }
          }
        }, "test-dot-conn");
        h.setDaemon(true);
        h.start();
      }
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
      int g = 0;
      while (g < b.length) { int r = in.read(b, g, b.length - g); if (r < 0) throw new IOException("eof"); g += r; }
    }

    static byte[] answer(byte[] query, int rcode, boolean response, int idDelta, int extra) {
      int qlen = query.length;
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      int id = (((query[0] & 0xff) << 8) | (query[1] & 0xff));
      id = (id + idDelta) & 0xffff;
      o.write(id >> 8); o.write(id);
      o.write((response ? 0x80 : 0x00) | 0x01);                               // QR, RD
      o.write(0x80 | rcode);                                                  // RA, RCODE
      o.write(0); o.write(1);                                                 // QDCOUNT 1
      o.write(0); o.write(rcode == 0 ? 1 : 0);                                // ANCOUNT
      o.write(0); o.write(0); o.write(0); o.write(0);
      o.write(query, 12, qlen - 12);                                          // the question
      if (rcode == 0) {
        o.write(0xc0); o.write(0x0c); o.write(0); o.write(1); o.write(0); o.write(1); o.write(0); o.write(0); o.write(0); o.write(60); o.write(0); o.write(4);
        o.write(93); o.write(184); o.write(216); o.write(34);
      }
      for (int i = 0; i < extra; i++) o.write(0);
      return o.toByteArray();
    }

    static void sendFramed(OutputStream out, byte[] msg) throws IOException {
      byte[] f = new byte[msg.length + 2];
      f[0] = (byte) (msg.length >> 8); f[1] = (byte) msg.length;
      System.arraycopy(msg, 0, f, 2, msg.length);
      out.write(f);
      out.flush();
    }

    void drain(InputStream in) {
      try { while (in.read() >= 0) { /* until the peer closes */ } } catch (IOException ignored) {}
    }

    void handle(Socket s, B b) throws Exception {
      switch (b) {
        case PLAIN_SILENT: drain(s.getInputStream()); return;
        case PLAIN_HTTP: s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().flush(); return;
        case PLAIN_DRIP: {
          OutputStream o = s.getOutputStream();
          o.write(new byte[] {0x16, 0x03, 0x03, 0x40, 0x00}); o.flush();
          for (int i = 0; i < 100 && !stop; i++) { Thread.sleep(100); o.write(0); o.flush(); }
          return;
        }
        default: break;
      }
      SSLSocket ss = (SSLSocket) s;
      try { ss.startHandshake(); } catch (IOException e) { return; }           // the client refused the certificate, or gave up
      try {
        ExtendedSSLSession sess = (ExtendedSSLSession) ss.getSession();
        for (SNIServerName nm : sess.getRequestedServerNames()) if (nm instanceof SNIHostName) sni.add(((SNIHostName) nm).getAsciiName());
      } catch (Throwable ignored) {}
      if (b == B.CLOSE) return;
      InputStream in = ss.getInputStream();
      OutputStream out = ss.getOutputStream();
      byte[] lenb = new byte[2];
      readFully(in, lenb);
      byte[] q = new byte[((lenb[0] & 0xff) << 8) | (lenb[1] & 0xff)];
      readFully(in, q);
      byte[] framed = new byte[q.length + 2];
      System.arraycopy(lenb, 0, framed, 0, 2);
      System.arraycopy(q, 0, framed, 2, q.length);
      queries.add(framed);
      queriesSeen.incrementAndGet();
      switch (b) {
        case GOOD: sendFramed(out, answer(q, 0, true, 0, 0)); break;
        case NXDOMAIN: sendFramed(out, answer(q, 3, true, 0, 0)); break;
        case SERVFAIL: sendFramed(out, answer(q, 2, true, 0, 0)); break;
        case REFUSED: sendFramed(out, answer(q, 5, true, 0, 0)); break;
        case WRONG_ID: sendFramed(out, answer(q, 0, true, 1, 0)); break;
        case NOT_RESPONSE: sendFramed(out, answer(q, 0, false, 0, 0)); break;
        case NOANSWER: break;
        case LEN0: out.write(new byte[] {0, 0}); out.flush(); break;
        case SHORT5: out.write(new byte[] {0, 5, 1, 2, 3, 4, 5}); out.flush(); break;
        case GARBAGE: { byte[] g = new byte[42]; Arrays.fill(g, (byte) 0xff); g[0] = 0; g[1] = 40; out.write(g); out.flush(); break; }
        case TRUNCATED: { byte[] g = new byte[12]; g[1] = 100; out.write(g); out.flush(); Thread.sleep(50); return; }
        case DRIP: {
          byte[] a = answer(q, 0, true, 0, 0);
          byte[] f = new byte[a.length + 2];
          f[0] = (byte) (a.length >> 8); f[1] = (byte) a.length;
          System.arraycopy(a, 0, f, 2, a.length);
          for (int i = 0; i < f.length && !stop; i++) { out.write(f[i]); out.flush(); Thread.sleep(200); }
          break;
        }
        case FRAGMENTED: {
          byte[] a = answer(q, 0, true, 0, 0);
          byte[] f = new byte[a.length + 2];
          f[0] = (byte) (a.length >> 8); f[1] = (byte) a.length;
          System.arraycopy(a, 0, f, 2, a.length);
          for (int i = 0; i < f.length; i++) { out.write(f[i]); out.flush(); }
          break;
        }
        case BIG: sendFramed(out, answer(q, 0, true, 0, 65535 - answer(q, 0, true, 0, 0).length)); break;
        default: break;
      }
      drain(in);                                                                 // wait for the client to close
    }

    @Override public void close() { stop = true; try { ss.close(); } catch (IOException ignored) {} }
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // running a scan and collecting what the page would receive
  // ---------------------------------------------------------------------------------------------------------------------------

  static final class Run implements DnsProbe.Sink {
    final List<String> raw = new CopyOnWriteArrayList<String>();
    final List<JSONObject> hosts = new CopyOnWriteArrayList<JSONObject>();
    final List<JSONObject> dones = new CopyOnWriteArrayList<JSONObject>();
    final CountDownLatch done = new CountDownLatch(1);
    String startResponse;
    long startedAt = System.nanoTime(), doneAt;
    int hostsBeforeDone = -1;
    @Override public void emit(String json) {
      raw.add(json);
      try {
        JSONObject o = new JSONObject(json);
        if (o.has("done")) { dones.add(o); hostsBeforeDone = hosts.size(); doneAt = System.nanoTime(); done.countDown(); }
        else hosts.add(o);
      } catch (Exception e) { raw.add("UNPARSEABLE"); }
    }
    boolean await(long ms) throws InterruptedException { return done.await(ms, TimeUnit.MILLISECONDS); }
    JSONObject host(String h) { for (JSONObject o : hosts) if (h.equals(o.optString("host"))) return o; return null; }
    long elapsedMs() { return ((doneAt == 0 ? System.nanoTime() : doneAt) - startedAt) / 1000000L; }
  }

  static String req(String id, int samples, int timeoutMs, String... hosts) {
    JSONArray a = new JSONArray();
    for (String h : hosts) a.put(h);
    try { return new JSONObject().put("id", id).put("hosts", a).put("samples", samples).put("timeoutMs", timeoutMs).toString(); } catch (Exception e) { throw new RuntimeException(e); }
  }

  static final class Names implements DnsProbe.Resolver {
    final AtomicInteger calls = new AtomicInteger();
    final List<String> asked = new CopyOnWriteArrayList<String>();
    volatile long delayMs;
    volatile CountDownLatch block;
    final AtomicInteger inside = new AtomicInteger(), maxInside = new AtomicInteger();
    @Override public InetAddress[] resolve(String host) throws IOException {
      calls.incrementAndGet();
      asked.add(host);
      int now = inside.incrementAndGet();
      synchronized (maxInside) { if (now > maxInside.get()) maxInside.set(now); }
      try {
        if (block != null) { try { block.await(); } catch (InterruptedException e) { throw new IOException("interrupted"); } }
        if (delayMs > 0) { try { Thread.sleep(delayMs); } catch (InterruptedException e) { throw new IOException("interrupted"); } }
      } finally { inside.decrementAndGet(); }
      if (host.endsWith(".test.example")) return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
      throw new UnknownHostException(host);
    }
  }

  static DnsProbe.Env env(int port, SSLSocketFactory f, DnsProbe.Resolver r) {
    DnsProbe.Env e = new DnsProbe.Env();
    e.port = port; e.sslFactory = f; e.resolver = r;
    return e;
  }

  static Run scan(DnsProbe.Runner runner, String json) {
    Run run = new Run();
    run.startedAt = System.nanoTime();
    run.startResponse = runner.start(json, POOL, run);
    return run;
  }

  static Run scanAndWait(DnsProbe.Env env, String json, long waitMs) throws Exception {
    Run r = scan(new DnsProbe.Runner(env), json);
    r.await(waitMs);
    return r;
  }

  /** One host, one sample, against a server with this behaviour. */
  static JSONObject probeOne(SSLContext ctx, B behaviour, String host, int timeoutMs, long waitMs, String[] unused) throws Exception {
    return probeWith(ctx, behaviour, clientTrust, host, timeoutMs, waitMs);
  }

  static JSONObject probeWith(SSLContext ctx, B behaviour, SSLSocketFactory factory, String host, int timeoutMs, long waitMs) throws Exception {
    Server s = new Server(ctx, behaviour);
    try {
      Run r = scanAndWait(env(s.port, factory, new Names()), req("t1", 1, timeoutMs, host), waitMs);
      if (!r.done.await(0, TimeUnit.MILLISECONDS)) return null;
      return r.host(host);
    } finally { s.close(); }
  }

  static String st(JSONObject o) { return o == null ? "(no result)" : o.optString("status") + "/" + o.optString("error"); }

  // ---------------------------------------------------------------------------------------------------------------------------

  public static void main(String[] args) throws Exception {
    try {
      setupCertificates();
      hostRules();
      requestRules();
      messageRules();
      goodServer();
      wrongAnswers();
      tlsFailures();
      timeouts();
      names();
      medians();
      cap();
      resolverCap();
      shutdownRules();
      connectStage();
      cancel();
      sourceScan();
    } finally {
      if (tmp != null) deleteTree(tmp);
    }
    System.out.println(n + " checks, " + fails + " failed");
    System.out.println(fails == 0 ? "DnsProbe: all passed" : "DnsProbe: FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  // ---- the page's rules for a host name ----

  static final String[][] HOST_EXAMPLES = {
      // input, expected host or "no:<why>"  (the same table the page's own UI script t170 uses)
      {"dns.adguard-dns.com", "dns.adguard-dns.com"}, {"one.one.one.one", "one.one.one.one"}, {"dns.google", "dns.google"}, {"DNS.AdGuard-DNS.COM", "dns.adguard-dns.com"},
      {"  dns.adguard-dns.com  ", "dns.adguard-dns.com"}, {"\tdns.adguard-dns.com\t", "dns.adguard-dns.com"}, {"dns.adguard-dns.com.", "dns.adguard-dns.com"}, {"dns.adguard-dns.com. ", "dns.adguard-dns.com"},
      {"a.b", "a.b"}, {"a-b.c-d.example", "a-b.c-d.example"}, {"xn--bcher-kva.example", "xn--bcher-kva.example"}, {"1dns.example.com", "1dns.example.com"}, {"dns.example.0x1", "dns.example.0x1"},
      {rep("x", 63) + ".example", rep("x", 63) + ".example"},
      {rep(rep("a", 63) + ".", 3) + rep("a", 61), rep(rep("a", 63) + ".", 3) + rep("a", 61)},
      {"", "no:empty"}, {"   ", "no:empty"}, {"\t", "no:empty"}, {".", "no:empty"},
      {"dns", "no:onedot"}, {"localhost", "no:onedot"}, {"a", "no:onedot"},
      {"1.1.1.1", "no:ip"}, {"8.8.8.8", "no:ip"}, {"255.255.255.255", "no:ip"}, {"999.999.999.999", "no:ip"}, {"1.1.1.1.", "no:ip"}, {"1.2.3", "no:ip"}, {"0x7f.0x0.0x0.0x1", "no:ip"}, {"0x7f.1", "no:ip"}, {"127.1", "no:ip"},
      {"2606:4700:4700::1111", "no:chars"}, {"[2606:4700:4700::1111]", "no:chars"}, {"::1", "no:chars"}, {"1.1.1.1:853", "no:chars"},
      {"dns.example.1", "no:tld"}, {"dns.example.123", "no:tld"},
      {"dns adguard.com", "no:chars"}, {"dns.adguard-dns.com\n", "no:chars"}, {"dns.adguard-dns.com\r\n", "no:chars"}, {"\ndns.adguard-dns.com", "no:chars"}, {"dns.adguard-dns.com\u0000", "no:chars"},
      {"dns.adguard-dns.com;reboot", "no:chars"}, {"$(id).example.com", "no:chars"}, {"https://dns.adguard-dns.com", "no:chars"}, {"dns.adguard-dns.com/dns-query", "no:chars"},
      {"user@dns.adguard-dns.com", "no:chars"}, {"dns.adguard-dns.com:853", "no:chars"}, {"*.adguard.com", "no:chars"}, {"dns_adguard.com", "no:chars"},
      {"dns.\u0430dguard.com", "no:chars"}, {"b\u00fccher.example", "no:chars"}, {"\u65e5\u672c\u8a9e.jp", "no:chars"}, {"dns\u3002adguard\u3002com", "no:chars"}, {"\u212a.example", "no:chars"},
      {"a..com", "no:dots"}, {".com", "no:dots"}, {"dns.adguard-dns.com..", "no:dots"}, {"dns..adguard.com", "no:dots"},
      {"-dns.adguard-dns.com", "no:hyphen"}, {"dns-.adguard.com", "no:hyphen"}, {"dns.-adguard.com", "no:hyphen"}, {"dns.adguard-dns.com-", "no:hyphen"}, {"-", "no:hyphen"},
      {rep("x", 64) + ".example", "no:label"}, {"a." + rep("b", 64) + ".com", "no:label"},
      {rep(rep("a", 63) + ".", 3) + rep("a", 62), "no:long"}, {rep("abcd.", 60) + "com", "no:long"},
  };

  static String rep(String s, int k) { StringBuilder b = new StringBuilder(); for (int i = 0; i < k; i++) b.append(s); return b.toString(); }

  static String javaVerdict(String in) {
    DnsProbe.HostCheck h = DnsProbe.checkHost(in);
    return h.ok ? "ok:" + h.host : "no:" + h.why;
  }

  static String hex(String s) {
    StringBuilder b = new StringBuilder();
    for (byte x : s.getBytes(StandardCharsets.UTF_8)) b.append(String.format("%02x", x & 0xff));
    return b.toString();
  }

  static File findUp(String rel) {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, rel);
      if (f.exists()) return f;
    }
    return null;
  }

  static void hostRules() throws Exception {
    int wrong = 0;
    for (String[] ex : HOST_EXAMPLES) {
      String want = ex[1].startsWith("no:") ? ex[1] : "ok:" + ex[1];
      String got = javaVerdict(ex[0]);
      if (!want.equals(got)) { wrong++; System.out.println("FAIL host " + ex[0].replace("\n", "\\n") + " -> " + got + " (wanted " + want + ")"); }
    }
    check("host rules: " + HOST_EXAMPLES.length + " examples from the page's own table give the page's answers", wrong == 0);
    check("host rules: null is not a host name", !DnsProbe.checkHost(null).ok);
    check("host rules: 253 characters pass, 254 do not; 63 in a part pass, 64 do not",
        DnsProbe.checkHost(rep(rep("a", 63) + ".", 3) + rep("a", 61)).ok && !DnsProbe.checkHost(rep(rep("a", 63) + ".", 3) + rep("a", 62)).ok
            && DnsProbe.checkHost(rep("a", 63) + ".example").ok && !DnsProbe.checkHost(rep("a", 64) + ".example").ok);

    // the same inputs, and many more, through the page's own function (node runs pdnsCheckHost from assets/index.html)
    File html = findUp("assets/index.html"), js = findUp("tests/java/parity/dnsprobe_hostcheck.js");
    List<String> inputs = new ArrayList<String>();
    for (String[] ex : HOST_EXAMPLES) inputs.add(ex[0]);
    String alphabet = "abzAZ019.-.-xX \t\n\r:/_@%\u00e9\u65e5\u0430\u3002\u0000f";
    Random rnd = new Random(853);
    for (int i = 0; i < 4000; i++) {
      int len = rnd.nextInt(16);
      StringBuilder b = new StringBuilder();
      for (int k = 0; k < len; k++) b.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
      inputs.add(b.toString());
    }
    String[] shapes = {"a.b", "0x1f.0x2", "12.34", "ab.12", "a-b.c", "-a.b", "a-.b", "a.b-", "a..b", ".a.b", "a.b.", "a.b..", "0x.1", "0xg.1", "00.0X1F"};
    for (String sh : shapes) { inputs.add(sh); inputs.add(" " + sh + " "); inputs.add(sh.toUpperCase()); inputs.add(sh + "\n"); inputs.add(sh + "\t"); }
    if (html == null || js == null) {
      System.out.println("SKIP host rules against the page: assets/index.html or the helper script not found");
    } else {
      StringBuilder sb = new StringBuilder();
      for (String in : inputs) sb.append(hex(in)).append('\n');
      Process p = null;
      byte[] out = null;
      try {
        p = new ProcessBuilder("node", js.getPath(), html.getPath()).redirectErrorStream(false).start();
        final OutputStream stdin = p.getOutputStream();
        stdin.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        stdin.close();
        out = readAll(p.getInputStream());
        p.waitFor();
      } catch (IOException noNode) {
        System.out.println("SKIP host rules against the page: node is not available (" + noNode.getMessage() + ")");
      }
      if (out != null) {
        String[] lines = new String(out, StandardCharsets.UTF_8).split("\n", -1);
        int diffs = 0;
        for (int i = 0; i < inputs.size(); i++) {
          String js1 = i < lines.length ? lines[i] : "(missing)", j = javaVerdict(inputs.get(i));
          if (!js1.equals(j)) { diffs++; if (diffs <= 5) System.out.println("FAIL host differs from the page: " + hex(inputs.get(i)) + " page=" + js1 + " java=" + j); }
        }
        check("host rules: Java and the page agree on " + inputs.size() + " inputs (examples, shapes, 4000 random strings with spaces, tabs, line breaks, dots, hyphens, accents)", diffs == 0 && lines.length >= inputs.size(), diffs + " differ");
      }
    }
  }

  // ---- the request ----

  static String parseError(String json) {
    try { DnsProbe.parseRequest(json); return null; } catch (DnsProbe.BadRequest e) { return e.getMessage(); }
  }

  static DnsProbe.Request parsed(String json) throws Exception { return DnsProbe.parseRequest(json); }

  static void requestRules() throws Exception {
    String h = "[\"dns.google\"]";
    check("request: a plain request is accepted with its defaults (3 samples, 3000 ms)", parseError("{\"id\":\"s1\",\"hosts\":" + h + "}") == null
        && parsed("{\"id\":\"s1\",\"hosts\":" + h + "}").samples == 3 && parsed("{\"id\":\"s1\",\"hosts\":" + h + "}").timeoutMs == 3000);
    String[] badIds = {"", "a b", "a/b", "a.b", "../x", "s\u00e9", "s1\n", rep("a", 41), "id;rm", "a\"b", "\u0000"};
    boolean idsOk = true;
    for (String id : badIds) {
      String j;
      try { j = new JSONObject().put("id", id).put("hosts", new JSONArray(h)).toString(); } catch (Exception e) { throw new RuntimeException(e); }
      if (parseError(j) == null) { idsOk = false; System.out.println("FAIL id accepted: " + hex(id)); }
    }
    check("request: ids with a space, slash, dot, accent, line break, quote, NUL, empty or 41 characters are refused", idsOk);
    check("request: 1 and 40 characters from [A-Za-z0-9_-] are accepted", parseError("{\"id\":\"" + rep("a", 40) + "\",\"hosts\":" + h + "}") == null && parseError("{\"id\":\"A_z-9\",\"hosts\":" + h + "}") == null && parseError("{\"id\":\"s\",\"hosts\":" + h + "}") == null);
    check("request: a missing, null or numeric id is refused", parseError("{\"hosts\":" + h + "}") != null && parseError("{\"id\":null,\"hosts\":" + h + "}") != null && parseError("{\"id\":5,\"hosts\":" + h + "}") != null);
    String okReq = "{\"id\":\"s\",\"hosts\":" + h + "}";
    check("request: one object followed by garbage, by a second object, a comment, a NUL or a second value is refused",
        parseError(okReq + "garbage") != null && parseError(okReq + " {}") != null && parseError(okReq + "{}") != null && parseError(okReq + " // c") != null && parseError(okReq + "/* c */") != null
            && parseError(okReq + "\u0000") != null && parseError(okReq + " 1") != null && parseError(okReq + ",") != null && parseError(okReq + "}") != null && parseError(okReq + " x\n") != null);
    check("request: white space around the object (space, tab, CR, LF) is fine", parseError(okReq + "\n") == null && parseError(okReq + " \t\r\n  ") == null && parseError("\n\t " + okReq) == null && parseError(" " + okReq + " ") == null);
    String pre = "{\"id\":\"s\",\"hosts\":[\"dns.google\"]";
    String[] notStrict = {"{'id':'s','hosts':['dns.google']}", "{id:\"s\",hosts:[\"dns.google\"]}", pre + ",}", "{\"id\":\"s\",\"hosts\":[\"dns.google\",]}", pre + ",\"samples\":01}", pre + ",\"samples\":+1}", pre + ",\"samples\":.5}",
        pre + ",\"samples\":0x3}", pre + ",\"samples\":NaN}", pre + ",\"samples\":1.}", pre + ",\"samples\":1e}", pre + ",\"samples\":-}", pre + ",\"x\":\"a\tb\"}", pre + ",\"x\":\"a\\xb\"}", pre + ",\"x\":\"a\\u12g4\"}",
        pre + ",\"x\":\"a\nb\"}", pre + " /* c */}", pre + ",\"x\":tru}", pre + ";}", pre + ",\"samples\" 3}", pre + ",\"samples\":}", "{\"id\":\"s\" \"hosts\":[]}", "{,\"id\":\"s\"}", "{\"id\":\"s\",\"hosts\":[\"dns.google\"]"};
    boolean strictOk = true;
    for (String x : notStrict) if (parseError(x) == null) { strictOk = false; System.out.println("FAIL accepted non-JSON: " + x); }
    check("request: text that org.json would accept but JSON does not (single quotes, unquoted keys, trailing commas, 01, +1, .5, 0x3, NaN, 1., raw tab or line break in a text, bad escapes, comments, a missing colon) is refused", strictOk);
    check("request: strict JSON in all its shapes is accepted (numbers 0, -0, 1e0, 3E+0, 2.5, escapes \\u00e9 \\n \\/, nested lists and objects, true, false, null)",
        parseError(pre + ",\"samples\":-0,\"x\":[1e0,3E+0,2.5,0,{\"a\":[true,false,null,\"\\u00e9\\n\\/\\\"\"]}]}") == null && parseError(pre + ",\"samples\":1e0}") == null);
    check("request: braces and quotes inside a text do not end the object early or late", DnsProbe.objectEnd("{\"a\":\"}{\"}") == 10 && DnsProbe.objectEnd("{\"a\":\"\\\"}\"} ") == 11 && DnsProbe.objectEnd("{\"a\":\"}\"} x") == -1
        && DnsProbe.objectEnd("{\"a\":{\"b\":[1,{}]}}") == 18 && DnsProbe.objectEnd("{\"a\":") == -1 && DnsProbe.objectEnd("[]") == -1 && DnsProbe.objectEnd("") == -1);
    check("request: not JSON, an array, a number, empty, null are refused", parseError("nope") != null && parseError("[1]") != null && parseError("5") != null && parseError("") != null && parseError(null) != null && parseError("null") != null);
    check("request: hosts missing, not a list, empty or holding a non-text are refused",
        parseError("{\"id\":\"s\"}") != null && parseError("{\"id\":\"s\",\"hosts\":\"dns.google\"}") != null && parseError("{\"id\":\"s\",\"hosts\":[]}") != null
            && parseError("{\"id\":\"s\",\"hosts\":[1]}") != null && parseError("{\"id\":\"s\",\"hosts\":[null]}") != null && parseError("{\"id\":\"s\",\"hosts\":[[\"a.b\"]]}") != null);
    List<String> forty = new ArrayList<String>(), fortyOne = new ArrayList<String>();
    for (int i = 0; i < 41; i++) { if (i < 40) forty.add("h" + i + ".example"); fortyOne.add("h" + i + ".example"); }
    check("request: 40 hosts are accepted, 41 are refused", parseError("{\"id\":\"s\",\"hosts\":" + new JSONArray(forty) + "}") == null && parseError("{\"id\":\"s\",\"hosts\":" + new JSONArray(fortyOne) + "}") != null);
    check("request: an over-long text is refused before it is parsed", parseError("{\"id\":\"s\",\"hosts\":[\"" + rep("a", DnsProbe.MAX_REQUEST_CHARS) + "\"]}") != null);
    int[][] sam = {{0, 1}, {1, 1}, {3, 3}, {5, 5}, {6, 5}, {9, 5}, {-4, 1}, {100000, 5}};
    boolean samOk = true;
    for (int[] c : sam) samOk &= parsed("{\"id\":\"s\",\"hosts\":" + h + ",\"samples\":" + c[0] + "}").samples == c[1];
    check("request: samples are clamped to 1..5 (0, -4 -> 1; 6, 9, 100000 -> 5)", samOk);
    check("request: samples 2.7 becomes 2, a missing or null one 3, a text is refused", parsed("{\"id\":\"s\",\"hosts\":" + h + ",\"samples\":2.7}").samples == 2 && parsed("{\"id\":\"s\",\"hosts\":" + h + ",\"samples\":null}").samples == 3
        && parseError("{\"id\":\"s\",\"hosts\":" + h + ",\"samples\":\"3\"}") != null);
    int[][] tm = {{0, 500}, {100, 500}, {499, 500}, {500, 500}, {3000, 3000}, {10000, 10000}, {10001, 10000}, {99999999, 10000}, {-1, 500}};
    boolean tmOk = true;
    for (int[] c : tm) tmOk &= parsed("{\"id\":\"s\",\"hosts\":" + h + ",\"timeoutMs\":" + c[0] + "}").timeoutMs == c[1];
    check("request: timeoutMs is clamped to 500..10000", tmOk);
    check("request: a text for timeoutMs is refused", parseError("{\"id\":\"s\",\"hosts\":" + h + ",\"timeoutMs\":\"500\"}") != null);

    // through the Runner: the answer the page gets
    DnsProbe.Runner r = new DnsProbe.Runner(env(1, null, new Names()));
    String bad = r.start("{\"id\":\"\",\"hosts\":[\"a.b\"]}", POOL, new Run());
    JSONObject bo = new JSONObject(bad);
    check("request: a refusal is {\"ok\":false,\"error\":<short text>} and nothing else", !bo.getBoolean("ok") && bo.getString("error").length() > 0 && bo.getString("error").length() < 80 && bo.length() == 2, bad);
    r.shutdown();

    // hosts that are not host names are not probed; they come back as dns / "not a host name", with the host as the page sent it
    String[] notNames = {"1.1.1.1", "8.8.8.8:853", "2606:4700:4700::1111", "https://dns.google", "dns.google/dns-query", "dns google", "localhost", "-a.example", "a-.example", rep("a", 64) + ".example", "dns.google\n", "", "0x7f.1"};
    Names names = new Names();
    Server never = new Server(serverGood, B.GOOD);
    try {
      Run run = scanAndWait(env(never.port, clientTrust, names), req("nn", 2, 800, notNames), 10000);
      check("request: a scan of names that are not host names finishes", run.done.getCount() == 0);
      boolean allDns = run.hosts.size() == notNames.length;
      for (String x : notNames) {
        JSONObject o = null;
        for (JSONObject c : run.hosts) if (x.equals(c.optString("host"))) o = c;
        allDns &= o != null && "dns".equals(o.optString("status")) && "not a host name".equals(o.optString("error")) && o.optInt("okSamples", -1) == 0 && o.isNull("connectMs") && o.isNull("tlsMs") && o.isNull("queryMs") && "".equals(o.optString("ip", "x"));
      }
      check("request: each of them (IP addresses, ports, schemes, paths, spaces, hyphens, a 64-character part, 'localhost', empty) is reported as dns / not a host name", allDns);
      check("request: none of them was looked up or connected to", names.calls.get() == 0 && never.accepted.get() == 0, names.calls.get() + " lookups, " + never.accepted.get() + " connections");
      check("request: the done line counts them all", run.dones.size() == 1 && run.dones.get(0).optInt("count") == notNames.length);
    } finally { never.close(); }
    Server good = new Server(serverGood, B.GOOD);
    try {
      Names nm = new Names();
      Run run = scanAndWait(env(good.port, clientTrust, nm), req("cs", 1, 3000, "  DOT.Test.Example. "), 10000);
      JSONObject o = run.host("  DOT.Test.Example. ");
      check("request: a valid host with other case, spaces and a trailing dot is probed under its clean name and reported under the text sent: " + st(o), o != null && "ok".equals(o.optString("status")) && nm.asked.size() == 1 && NAME.equals(nm.asked.get(0)), String.valueOf(nm.asked));
    } finally { good.close(); }
    // samples really limit the connections
    Server count = new Server(serverGood, B.GOOD);
    try {
      Names once = new Names();
      Run run = scanAndWait(env(count.port, clientTrust, once), req("sm", 9, 3000, NAME), 15000);
      check("request: the name is looked up once for all samples", once.calls.get() == 1, once.calls + " lookups");
      check("request: samples 9 means 5 connections, and 5 is what the result says", count.accepted.get() == 5 && run.host(NAME) != null && run.host(NAME).optInt("samples") == 5 && run.host(NAME).optInt("okSamples") == 5, count.accepted.get() + " connections");
    } finally { count.close(); }
    // the reply of a good start
    DnsProbe.Runner r2 = new DnsProbe.Runner(env(1, null, new Names()));
    Run quiet = new Run();
    String okStart = r2.start(req("go-1", 1, 500, "x.test.example"), POOL, quiet);
    JSONObject oo = new JSONObject(okStart);
    check("request: a start is answered with exactly {\"ok\":true,\"id\":<id>}", oo.getBoolean("ok") && "go-1".equals(oo.getString("id")) && oo.length() == 2, okStart);
    String again = r2.start(req("go-1", 1, 500, "x.test.example"), POOL, new Run());
    check("request: the id of the scan that is still running is refused", !new JSONObject(again).getBoolean("ok"), again);
    quiet.await(8000);
    r2.shutdown();
  }

  // ---- the DNS message and its checks ----

  static void messageRules() {
    byte[] q = DnsProbe.buildQuery(0xABCD);
    byte[] want = {(byte) 0xAB, (byte) 0xCD, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1};
    check("message: the query is id, RD, one question example.com type A class IN", Arrays.equals(q, want), Arrays.toString(q));
    byte[] ok = new byte[12];
    ok[0] = (byte) 0xAB; ok[1] = (byte) 0xCD; ok[2] = (byte) 0x81; ok[3] = (byte) 0x80;
    check("message: same id, QR, RCODE 0 is an answer", DnsProbe.checkAnswer(ok, 0xABCD) == null);
    ok[3] = (byte) 0x83;
    check("message: RCODE 3 (NXDOMAIN) is an answer", DnsProbe.checkAnswer(ok, 0xABCD) == null);
    boolean others = true;
    for (int rc : new int[] {1, 2, 4, 5, 6, 9, 15}) { ok[3] = (byte) (0x80 | rc); others &= DnsProbe.checkAnswer(ok, 0xABCD) != null; }
    check("message: RCODE 1, 2, 4, 5, 6, 9, 15 are not", others);
    ok[3] = (byte) 0x80;
    check("message: another id is not", DnsProbe.checkAnswer(ok, 0xABCE) != null && DnsProbe.checkAnswer(ok, 0x01CD) != null);
    ok[2] = 0x01;
    check("message: QR = 0 is not", DnsProbe.checkAnswer(ok, 0xABCD) != null);
    check("message: shorter than a header, empty and null are not", DnsProbe.checkAnswer(new byte[11], 0) != null && DnsProbe.checkAnswer(new byte[0], 0) != null && DnsProbe.checkAnswer(null, 0) != null);
  }

  // ---- a correct server ----

  static final String[] KEYS = {"id", "host", "status", "ip", "connectMs", "tlsMs", "queryMs", "samples", "okSamples", "error"};

  static void goodServer() throws Exception {
    Server s = new Server(serverGood, B.GOOD);
    try {
      Names nm = new Names();
      Run run = scanAndWait(env(s.port, clientTrust, nm), req("s1", 5, 3000, NAME), 15000);
      JSONObject o = run.host(NAME);
      check("good server: the scan finishes and the host is ok", run.done.getCount() == 0 && o != null && "ok".equals(o.optString("status")), st(o));
      if (o == null) return;
      check("good server: JSON fields are exactly the contract's, no more", keySet(o).equals(new HashSet<String>(Arrays.asList(KEYS))), String.valueOf(keySet(o)));
      check("good server: id, host, ip, samples, okSamples and error", "s1".equals(o.optString("id")) && NAME.equals(o.optString("host")) && "127.0.0.1".equals(o.optString("ip")) && o.optInt("samples") == 5 && o.optInt("okSamples") == 5 && "".equals(o.optString("error", "x")));
      check("good server: the three times are integers (not floats, not null)", isInt(o, "connectMs") && isInt(o, "tlsMs") && isInt(o, "queryMs"), o.toString());
      check("good server: exactly one done line, last, {\"id\",\"done\":true,\"count\":1}", run.dones.size() == 1 && run.dones.get(0).length() == 3 && run.dones.get(0).getBoolean("done") && run.dones.get(0).getInt("count") == 1 && "s1".equals(run.dones.get(0).getString("id"))
          && run.raw.get(run.raw.size() - 1).contains("\"done\""), run.raw.toString());
      check("good server: 5 connections, one query on each", s.accepted.get() == 5 && s.queries.size() == 5, s.accepted + " / " + s.queries.size());
      boolean shape = true;
      Set<Integer> ids = new HashSet<Integer>();
      for (byte[] f : s.queries) {
        int len = ((f[0] & 0xff) << 8) | (f[1] & 0xff);
        byte[] m = Arrays.copyOfRange(f, 2, f.length);
        ids.add(((m[0] & 0xff) << 8) | (m[1] & 0xff));
        byte[] tail = Arrays.copyOfRange(m, 2, m.length);
        byte[] expect = {0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1};
        shape &= len == m.length && Arrays.equals(tail, expect);
      }
      check("good server: every query is length-prefixed, RD set, one question example.com A IN", shape);
      check("good server: the ids are random (5 queries, at least 4 different ids)", ids.size() >= 4, String.valueOf(ids));
      boolean sniOk = s.sni.size() == 5;
      for (String x : s.sni) sniOk &= NAME.equals(x);
      check("good server: SNI is the host name on every connection", sniOk, String.valueOf(s.sni));
      long t = System.nanoTime();
      boolean closed = false;
      while (System.nanoTime() - t < 3000000000L && !(closed = s.finished.get() == 5)) Thread.sleep(20);
      check("good server: the client closes each connection after the answer", closed, s.finished + " finished");
    } finally { s.close(); }

    // NXDOMAIN counts, SERVFAIL and REFUSED do not; a fragmented answer and the largest answer are read whole
    JSONObject nx = probeOne(serverGood, B.NXDOMAIN, NAME, 3000, 8000, null);
    check("answers: RCODE 3 (NXDOMAIN) is ok", nx != null && "ok".equals(nx.optString("status")), st(nx));
    JSONObject sf = probeOne(serverGood, B.SERVFAIL, NAME, 3000, 8000, null);
    check("answers: RCODE 2 (SERVFAIL) is a query failure", sf != null && "query".equals(sf.optString("status")) && sf.isNull("queryMs") && !sf.isNull("tlsMs"), st(sf));
    JSONObject rf = probeOne(serverGood, B.REFUSED, NAME, 3000, 8000, null);
    check("answers: RCODE 5 (REFUSED) is a query failure", rf != null && "query".equals(rf.optString("status")), st(rf));
    JSONObject fr = probeOne(serverGood, B.FRAGMENTED, NAME, 3000, 8000, null);
    check("answers: an answer that arrives one byte at a time is read whole", fr != null && "ok".equals(fr.optString("status")), st(fr));
    JSONObject big = probeOne(serverGood, B.BIG, NAME, 3000, 8000, null);
    check("answers: a 65535-byte answer (the longest the prefix can say) is read whole", big != null && "ok".equals(big.optString("status")), st(big));
  }

  static Set<String> keySet(JSONObject o) {
    Set<String> k = new HashSet<String>();
    Iterator<?> it = o.keys();
    while (it.hasNext()) k.add((String) it.next());
    return k;
  }

  static boolean isInt(JSONObject o, String key) {
    Object v = o.opt(key);
    return (v instanceof Integer || v instanceof Long) && ((Number) v).longValue() >= 0 && !o.toString().contains("\"" + key + "\":" + ((Number) v).longValue() + ".");
  }

  // ---- a server that answers badly ----

  static void wrongAnswers() throws Exception {
    Object[][] cases = {
        {B.WRONG_ID, "an answer with another id"}, {B.NOT_RESPONSE, "a message with QR = 0"}, {B.LEN0, "a length prefix of 0"}, {B.SHORT5, "a length prefix of 5 (less than a header)"},
        {B.GARBAGE, "40 bytes of 0xFF"}, {B.TRUNCATED, "a promise of 100 bytes and 10 sent, then the server closes"}, {B.CLOSE, "a server that closes right after the handshake"},
    };
    for (Object[] c : cases) {
      JSONObject o = probeOne(serverGood, (B) c[0], NAME, 2000, 8000, null);
      check("bad answers: " + c[1] + " -> query, with the connect and TLS times kept and no query time", o != null && "query".equals(o.optString("status")) && o.optInt("okSamples", -1) == 0 && !o.isNull("connectMs") && !o.isNull("tlsMs") && o.isNull("queryMs")
          && o.optString("error").length() > 0, st(o));
    }
    // two good connections, then a server that closes: 2 of 3 samples count
    Server s = new Server(serverGood, B.GOOD, B.GOOD, B.CLOSE);
    try {
      Run run = scanAndWait(env(s.port, clientTrust, new Names()), req("mix", 3, 3000, NAME), 12000);
      JSONObject o = run.host(NAME);
      check("bad answers: 2 good samples and 1 failure -> ok, okSamples 2, samples 3, no error", o != null && "ok".equals(o.optString("status")) && o.optInt("okSamples") == 2 && o.optInt("samples") == 3 && "".equals(o.optString("error", "x")), String.valueOf(o));
    } finally { s.close(); }
    // a failure first, then two good ones: still ok (the first sample does not decide)
    Server s1 = new Server(serverGood, B.CLOSE, B.GOOD, B.GOOD);
    try {
      Run run = scanAndWait(env(s1.port, clientTrust, new Names()), req("mix2", 3, 3000, NAME), 12000);
      JSONObject o = run.host(NAME);
      check("bad answers: 1 failure first, then 2 good samples -> ok, okSamples 2, no error", o != null && "ok".equals(o.optString("status")) && o.optInt("okSamples") == 2 && "".equals(o.optString("error", "x")) && !o.isNull("queryMs"), String.valueOf(o));
    } finally { s1.close(); }
    // the last failure decides when none worked
    Server s2 = new Server(serverGood, B.WRONG_ID, B.WRONG_ID, B.SERVFAIL);
    try {
      Run run = scanAndWait(env(s2.port, clientTrust, new Names()), req("last", 3, 3000, NAME), 12000);
      JSONObject o = run.host(NAME);
      check("bad answers: none worked -> the last failure's status and words", o != null && "query".equals(o.optString("status")) && o.optInt("okSamples") == 0 && o.optString("error").equals("server error code"), String.valueOf(o));
    } finally { s2.close(); }
  }

  // ---- TLS failures ----

  static void tlsFailures() throws Exception {
    JSONObject mismatch = probeOne(serverOther, B.GOOD, NAME, 3000, 8000, null);
    check("tls: a trusted certificate for another name is refused (hostname verification) -> tls", mismatch != null && "tls".equals(mismatch.optString("status")) && mismatch.isNull("tlsMs") && mismatch.isNull("queryMs") && !mismatch.isNull("connectMs"), st(mismatch));
    JSONObject untrusted = probeOne(serverUntrusted, B.GOOD, NAME, 3000, 8000, null);
    check("tls: a certificate that is not trusted is refused -> tls", untrusted != null && "tls".equals(untrusted.optString("status")), st(untrusted));
    JSONObject http = probeOne(null, B.PLAIN_HTTP, NAME, 3000, 8000, null);
    check("tls: a server that answers a TLS hello with an HTTP error -> tls", http != null && "tls".equals(http.optString("status")), st(http));
    JSONObject wildOk = probeOne(serverWild, B.GOOD, "a." + WILD, 3000, 8000, null);
    check("tls: a wildcard certificate names one label (a.wild.test.example is ok)", wildOk != null && "ok".equals(wildOk.optString("status")), st(wildOk));
    JSONObject wildTwo = probeOne(serverWild, B.GOOD, "x.y." + WILD, 3000, 8000, null);
    check("tls: ... but not two labels (x.y.wild.test.example -> tls)", wildTwo != null && "tls".equals(wildTwo.optString("status")), st(wildTwo));
    JSONObject wildBare = probeOne(serverWild, B.GOOD, WILD, 3000, 8000, null);
    check("tls: ... and not the bare name (wild.test.example -> tls)", wildBare != null && "tls".equals(wildBare.optString("status")), st(wildBare));
    // each of the two host name checks on its own: the TLS stack's (asked for with HTTPS endpoint identification), and DnsProbe's own check of the certificate's names
    Recording rec = new Recording(trustOf(trustStore));
    JSONObject recorded = probeWith(serverGood, B.GOOD, factoryWith(rec), NAME, 3000, 8000);
    check("tls: the probe asks the TLS stack for HTTPS endpoint identification on the connection it checks", recorded != null && "ok".equals(recorded.optString("status")) && rec.algorithms.size() == 1 && "HTTPS".equals(rec.algorithms.get(0)), st(recorded) + " " + rec.algorithms);
    JSONObject onlyOwn = probeWith(serverOther, B.GOOD, factoryWith(new TrustAll()), NAME, 3000, 8000);
    check("tls: with a trust manager that checks nothing, the probe's own check still refuses a certificate for another name -> tls", onlyOwn != null && "tls".equals(onlyOwn.optString("status")) && onlyOwn.optString("error").contains("does not match"), st(onlyOwn));
    JSONObject onlyOwnOk = probeWith(serverGood, B.GOOD, factoryWith(new TrustAll()), NAME, 3000, 8000);
    check("tls: ... and accepts the certificate that names the host", onlyOwnOk != null && "ok".equals(onlyOwnOk.optString("status")), st(onlyOwnOk));
    // the second check (the certificate's DNS names), on its own
    X509Certificate good = cert(ksGood), other = cert(ksOther), wild = cert(ksWild);
    check("tls: certificateNamesHost: exact name, case-insensitive; another name no", DnsProbe.certificateNamesHost(good, NAME) && DnsProbe.certificateNamesHost(good, "DOT.TEST.EXAMPLE") && !DnsProbe.certificateNamesHost(good, "other.example") && !DnsProbe.certificateNamesHost(other, NAME));
    check("tls: certificateNamesHost: wildcard covers one label only", DnsProbe.certificateNamesHost(wild, "a." + WILD) && !DnsProbe.certificateNamesHost(wild, WILD) && !DnsProbe.certificateNamesHost(wild, "x.y." + WILD) && !DnsProbe.certificateNamesHost(wild, "a.test.example"));
  }

  // ---- no answer in time ----

  static void timeouts() throws Exception {
    long t0 = System.nanoTime();
    JSONObject mute = probeOne(serverGood, B.NOANSWER, NAME, 500, 8000, null);
    long ms = (System.nanoTime() - t0) / 1000000L;
    check("timeout: a server that reads the query and never answers -> timeout, within 3 s (timeoutMs 500)", mute != null && "timeout".equals(mute.optString("status")) && ms < 3000, st(mute) + " after " + ms + " ms");
    t0 = System.nanoTime();
    JSONObject silent = probeOne(null, B.PLAIN_SILENT, NAME, 500, 8000, null);
    ms = (System.nanoTime() - t0) / 1000000L;
    check("timeout: a server that accepts and never speaks (the TLS handshake) -> timeout, within 3 s", silent != null && "timeout".equals(silent.optString("status")) && ms < 3000 && silent.isNull("tlsMs"), st(silent) + " after " + ms + " ms");
    t0 = System.nanoTime();
    JSONObject dripTls = probeOne(null, B.PLAIN_DRIP, NAME, 500, 8000, null);
    ms = (System.nanoTime() - t0) / 1000000L;
    check("timeout: a server that drips one byte every 100 ms during the handshake cannot stretch it -> within 3 s", dripTls != null && !"ok".equals(dripTls.optString("status")) && ms < 3000, st(dripTls) + " after " + ms + " ms");
    t0 = System.nanoTime();
    JSONObject drip = probeOne(serverGood, B.DRIP, NAME, 500, 8000, null);
    ms = (System.nanoTime() - t0) / 1000000L;
    check("timeout: an answer dripped one byte every 200 ms cannot stretch the wait -> timeout within 3 s", drip != null && "timeout".equals(drip.optString("status")) && ms < 3000, st(drip) + " after " + ms + " ms");
    // refused port: a port that was open a moment ago
    ServerSocket tmpSock = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    int closedPort = tmpSock.getLocalPort();
    tmpSock.close();
    Run run = scanAndWait(env(closedPort, clientTrust, new Names()), req("rf", 2, 1000, NAME), 8000);
    JSONObject o = run.host(NAME);
    check("connect: a port nobody listens on -> connect, no TLS or query time, no ip-less crash", o != null && "connect".equals(o.optString("status")) && o.isNull("connectMs") && o.isNull("tlsMs") && o.isNull("queryMs") && o.optInt("okSamples", -1) == 0 && "".equals(o.optString("ip", "x")), String.valueOf(o));
  }

  // ---- the name lookup ----

  static void names() throws Exception {
    Run run = scanAndWait(env(1, clientTrust, new Names()), req("nx", 2, 1000, "nothing.example.invalid", "gone.test.example"), 10000);
    JSONObject a = run.host("nothing.example.invalid"), b = run.host("gone.test.example");
    check("names: a name that does not resolve -> dns / `name not found`", a != null && "dns".equals(a.optString("status")) && "name not found".equals(a.optString("error")) && b != null && !"ok".equals(b.optString("status")), st(a) + " " + st(b));
    check("names: ... with no ip and no times", a != null && "".equals(a.optString("ip", "x")) && a.isNull("connectMs") && a.isNull("tlsMs") && a.isNull("queryMs") && a.optInt("okSamples", -1) == 0);
    // a lookup that never returns: dns after the timeout, and only one lookup (the others are not piled on)
    Names stuck = new Names();
    stuck.block = new CountDownLatch(1);
    long t0 = System.nanoTime();
    Run r2 = scanAndWait(env(1, clientTrust, stuck), req("st", 3, 500, "stuck.test.example"), 8000);
    long ms = (System.nanoTime() - t0) / 1000000L;
    JSONObject s = r2.host("stuck.test.example");
    check("names: a lookup that never returns -> dns / `resolver busy` after about the timeout (not `name not found`: nothing was learned about the name), one lookup for 3 samples", s != null && "dns".equals(s.optString("status")) && "resolver busy".equals(s.optString("error")) && ms >= 400 && ms < 3000 && stuck.calls.get() == 1, st(s) + " after " + ms + " ms, " + stuck.calls + " lookups");
    stuck.block.countDown();
    // a resolver that throws something odd
    DnsProbe.Resolver boom = new DnsProbe.Resolver() { @Override public InetAddress[] resolve(String h) { throw new IllegalStateException("boom"); } };
    Run r3 = scanAndWait(env(1, clientTrust, boom), req("bm", 1, 500, "b.test.example"), 8000);
    check("names: a resolver that throws a RuntimeException is a dns failure, and the scan still ends", r3.done.getCount() == 0 && r3.host("b.test.example") != null && "dns".equals(r3.host("b.test.example").optString("status")));
    DnsProbe.Resolver none = new DnsProbe.Resolver() { @Override public InetAddress[] resolve(String h) { return new InetAddress[0]; } };
    Run r4 = scanAndWait(env(1, clientTrust, none), req("em", 1, 500, "e.test.example"), 8000);
    check("names: no address at all is a dns failure", r4.host("e.test.example") != null && "dns".equals(r4.host("e.test.example").optString("status")));
    // the error text never carries exception text
    boolean clean = true;
    for (JSONObject x : Arrays.asList(a, b, s)) if (x != null) clean &= !x.optString("error").contains("Exception") && !x.optString("error").contains("java") && !x.optString("error").contains("example.invalid");
    check("names: error texts are fixed short phrases (no exception text, no host)", clean);
  }

  // ---- the median ----

  /** A clock that hands out the given readings in order; the probe reads it twice per stage (start, success). */
  static final class Script implements DnsProbe.Clock {
    final long[] v; int i;
    Script(long[] v) { this.v = v; }
    @Override public synchronized long nanoTime() { return i < v.length ? v[i++] : v[v.length - 1]; }
  }

  static long[] stages(int[][] perSample) {
    long[] v = new long[perSample.length * 6];
    int k = 0;
    long base = 5000000000L;
    for (int[] s : perSample) for (int d : s) { v[k++] = base; v[k++] = base + d * 1000000L; }
    return v;
  }

  static void medians() throws Exception {
    check("median: odd count", DnsProbe.median(Arrays.asList(9, 1, 5)) == 5 && DnsProbe.median(Arrays.asList(7)) == 7 && DnsProbe.median(Arrays.asList(10, 100, 12, 14, 11)) == 12);
    check("median: even count is the mean of the middle two, rounded half up", DnsProbe.median(Arrays.asList(10, 20)) == 15 && DnsProbe.median(Arrays.asList(10, 21)) == 16 && DnsProbe.median(Arrays.asList(40, 10, 30, 21)) == 26);
    check("median: no values is null; zeros are zeros", DnsProbe.median(new ArrayList<Integer>()) == null && DnsProbe.median(Arrays.asList(0, 0, 0)) == 0);
    check("median: rounding of a stage time to whole milliseconds", DnsProbe.toMs(0) == 0 && DnsProbe.toMs(499999) == 0 && DnsProbe.toMs(500000) == 1 && DnsProbe.toMs(12400000) == 12 && DnsProbe.toMs(12500000) == 13 && DnsProbe.toMs(-5) == 0);
    // end to end with a scripted clock: 5 samples -> the median of each column
    Server s = new Server(serverGood, B.GOOD);
    try {
      DnsProbe.Env e = env(s.port, clientTrust, new Names());
      e.clock = new Script(stages(new int[][] {{10, 30, 5}, {100, 31, 6}, {12, 500, 7}, {14, 32, 1000}, {11, 33, 8}}));
      Run run = scanAndWait(e, req("md", 5, 3000, NAME), 15000);
      JSONObject o = run.host(NAME);
      check("median: 5 samples -> the median of each time (connect 12, tls 32, query 7; the mean would be 29/119/205 and the first 10/30/5)",
          o != null && "ok".equals(o.optString("status")) && o.optInt("connectMs") == 12 && o.optInt("tlsMs") == 32 && o.optInt("queryMs") == 7, String.valueOf(o));
    } finally { s.close(); }
    Server s2 = new Server(serverGood, B.GOOD);
    try {
      DnsProbe.Env e = env(s2.port, clientTrust, new Names());
      e.clock = new Script(stages(new int[][] {{10, 5, 5}, {21, 6, 6}, {30, 7, 8}, {40, 100, 9}}));
      Run run = scanAndWait(e, req("m4", 4, 3000, NAME), 15000);
      JSONObject o = run.host(NAME);
      check("median: 4 samples -> the mean of the middle two, half up (connect 26 = 25.5 up, tls 7 = 6.5 up, query 7 = 7 exactly)", o != null && o.optInt("connectMs") == 26 && o.optInt("tlsMs") == 7 && o.optInt("queryMs") == 7, String.valueOf(o));
    } finally { s2.close(); }
    // the medians are over the samples that completed all stages
    Server s3 = new Server(serverGood, B.GOOD, B.WRONG_ID, B.GOOD);
    try {
      DnsProbe.Env e = env(s3.port, clientTrust, new Names());
      e.clock = new Script(new long[] {1000000000L, 1010000000L, 2000000000L, 2030000000L, 3000000000L, 3005000000L,                  // sample 1: 10, 30, 5
          4000000000L, 4999000000L, 5000000000L, 5999000000L, 6000000000L, 6999000000L,                                               // sample 2: 999, 999, 999 (the answer is refused)
          7000000000L, 7020000000L, 8000000000L, 8050000000L, 9000000000L, 9007000000L});                                             // sample 3: 20, 50, 7
      Run run = scanAndWait(e, req("mf", 3, 3000, NAME), 15000);
      JSONObject o = run.host(NAME);
      check("median: a sample that failed does not count (connect (10,20) -> 15, tls (30,50) -> 40, query (5,7) -> 6; okSamples 2 of 3)",
          o != null && "ok".equals(o.optString("status")) && o.optInt("okSamples") == 2 && o.optInt("connectMs") == 15 && o.optInt("tlsMs") == 40 && o.optInt("queryMs") == 6, String.valueOf(o));
    } finally { s3.close(); }
  }

  // ---- at most 6 at a time ----

  static void cap() throws Exception {
    Server s = new Server(serverWild, B.GOOD);
    try {
      Names nm = new Names();
      nm.delayMs = 250;
      String[] hosts = new String[14];
      for (int i = 0; i < hosts.length; i++) hosts[i] = "h" + i + "." + WILD;
      Run run = scanAndWait(env(s.port, clientTrust, nm), req("cap", 1, 3000, hosts), 20000);
      check("cap: 14 hosts are all done and reported once each", run.done.getCount() == 0 && run.hosts.size() == 14 && run.hostsBeforeDone == 14 && run.dones.size() == 1 && run.dones.get(0).optInt("count") == 14, run.hosts.size() + " results");
      boolean allOk = true;
      for (JSONObject o : run.hosts) allOk &= "ok".equals(o.optString("status"));
      check("cap: ... and all ok", allOk);
      check("cap: never more than 6 at once, and 6 were at once (the lookups overlapped)", nm.maxInside.get() == DnsProbe.MAX_PARALLEL && DnsProbe.MAX_PARALLEL == 6, "most at once: " + nm.maxInside.get());
    } finally { s.close(); }
    check("cap: the limits are what the brief says (40 hosts, 6 at once, 1..5 samples, 500..10000 ms)", DnsProbe.MAX_HOSTS == 40 && DnsProbe.MAX_PARALLEL == 6 && DnsProbe.MIN_SAMPLES == 1 && DnsProbe.MAX_SAMPLES == 5 && DnsProbe.DEFAULT_SAMPLES == 3
        && DnsProbe.MIN_TIMEOUT_MS == 500 && DnsProbe.MAX_TIMEOUT_MS == 10000 && DnsProbe.DEFAULT_TIMEOUT_MS == 3000 && DnsProbe.PORT == 853 && "example.com".equals(DnsProbe.QUERY_NAME));
  }

  // ---- name lookups that cannot be stopped ----

  static int lookupThreads(String prefix) {
    int k = 0;
    for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().startsWith(prefix) && t.isAlive()) k++;
    return k;
  }

  static void resolverCap() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    DnsProbe.Resolver stubborn = new DnsProbe.Resolver() {
      @Override public InetAddress[] resolve(String host) throws IOException {
        calls.incrementAndGet();
        boolean interrupted = false;
        while (true) { try { release.await(); break; } catch (InterruptedException e) { interrupted = true; } }      // ignores interrupts, like getaddrinfo
        if (interrupted) Thread.currentThread().interrupt();
        return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
      }
    };
    Server good = new Server(serverWild, B.GOOD);
    try {
      DnsProbe.Env e = env(good.port, clientTrust, stubborn);
      DnsProbe.Runner runner = new DnsProbe.Runner(e);
      String[] hosts = new String[20];
      for (int i = 0; i < hosts.length; i++) hosts[i] = "r" + i + "." + WILD;
      Run first = scan(runner, req("rc1", 3, 500, hosts));
      check("resolver cap: a scan whose lookups never return still ends", first.await(15000) && first.hosts.size() == 20, first.hosts.size() + " results");
      int timedOut = 0, busy = 0, other = 0;
      for (JSONObject o : first.hosts) {
        if (!"dns".equals(o.optString("status"))) other++;
        else if ("resolver busy".equals(o.optString("error"))) busy++;
        else other++;
      }
      check("resolver cap: 20 hosts -> at most 8 lookups were started (the cap for the whole app), every host says `resolver busy` (the 8 whose lookup timed out and the 12 refused), none says anything else", calls.get() <= 8 && calls.get() >= 6 && busy == 20 && other == 0,
          calls + " lookups, " + timedOut + " timed out, " + busy + " busy, " + other + " other");
      check("resolver cap: the abandoned lookups are counted as stuck", e.lookups.stuck() == calls.get(), e.lookups.stuck() + " stuck");
      int startedSoFar = calls.get();
      boolean fastAndBusy = true;
      for (int k = 0; k < 5; k++) {
        Run again = scan(runner, req("rc" + (k + 2), 3, 500, hosts));
        fastAndBusy &= again.await(5000) && again.hosts.size() == 20 && again.elapsedMs() < 2500;
        for (JSONObject o : again.hosts) fastAndBusy &= "dns".equals(o.optString("status")) && "resolver busy".equals(o.optString("error"));
      }
      check("resolver cap: five more scans while 8 are stuck: every host fails at once with `resolver busy`, no new lookup is started", fastAndBusy && calls.get() == startedSoFar, calls + " lookups (" + startedSoFar + " before)");
      check("resolver cap: never more than 8 lookup threads exist, however many scans", lookupThreads(e.lookups.prefix) <= 8, lookupThreads(e.lookups.prefix) + " threads");
      release.countDown();                                                                                          // the resolver finally returns
      long t = System.nanoTime();
      while (System.nanoTime() - t < 5000000000L && e.lookups.stuck() > 0) Thread.sleep(20);
      check("resolver cap: when the blocked calls return, the stuck count drops to 0 (the slots are free)", e.lookups.stuck() == 0, e.lookups.stuck() + " stuck");
      Run after = scan(runner, req("rc9", 1, 3000, hosts[0], hosts[1], hosts[2]));
      boolean allOk = after.await(10000) && after.hosts.size() == 3;
      for (JSONObject o : after.hosts) allOk &= "ok".equals(o.optString("status"));
      check("resolver cap: a scan after that resolves and probes normally", allOk, after.hosts.toString());
    } finally { release.countDown(); good.close(); }
  }

  // ---- shutdown ----

  static WeakReference<Run> startAndForget(DnsProbe.Env e) throws Exception {
    DnsProbe.Runner r = new DnsProbe.Runner(e);
    Run run = scan(r, req("wr", 1, 500, "w.test.example"));
    run.await(8000);
    r.shutdown();
    return new WeakReference<Run>(run);
  }

  static void shutdownRules() throws Exception {
    DnsProbe.Env e = env(1, clientTrust, new Names());
    DnsProbe.Runner r = new DnsProbe.Runner(e);
    r.shutdown();
    String resp = r.start(req("late", 1, 500, "x.test.example"), POOL, new Run());
    JSONObject o = new JSONObject(resp);
    check("shutdown: a start after shutdown is refused (ok:false, \"closed\") and starts nothing", !o.getBoolean("ok") && "closed".equals(o.optString("error")), resp);
    check("shutdown: shutdown stops the lookup pool", e.lookups.pool.isShutdown());
    // a lookup that is blocked for good must not keep the scan (and so the sink, and in the app the activity) alive
    Class<?> task = null;
    for (Class<?> c : DnsProbe.class.getDeclaredClasses()) if (c.getSimpleName().equals("LookupTask")) task = c;
    boolean noOuter = task != null && java.lang.reflect.Modifier.isStatic(task.getModifiers());
    if (task != null) for (java.lang.reflect.Field f : task.getDeclaredFields()) {
      Class<?> t = f.getType();
      noOuter &= !f.isSynthetic() && !f.getName().startsWith("this$") && !DnsProbe.Scan.class.isAssignableFrom(t) && !DnsProbe.Sink.class.isAssignableFrom(t) && !DnsProbe.Runner.class.isAssignableFrom(t) && !DnsProbe.Env.class.isAssignableFrom(t);
    }
    check("shutdown: the lookup task is a static class whose fields hold no scan, sink, runner or env", noOuter);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    DnsProbe.Resolver stubborn = new DnsProbe.Resolver() {
      @Override public InetAddress[] resolve(String host) throws IOException {
        calls.incrementAndGet();
        while (true) { try { release.await(); break; } catch (InterruptedException ignored) {} }
        throw new UnknownHostException(host);
      }
    };
    DnsProbe.Env e2 = env(1, clientTrust, stubborn);
    WeakReference<Run> ref = startAndForget(e2);
    for (int i = 0; i < 60 && ref.get() != null; i++) { System.gc(); Thread.sleep(100); }
    check("shutdown: while a lookup is still blocked, the finished scan's sink can be garbage collected (the blocked thread keeps nothing of the app)", ref.get() == null && calls.get() == 1 && e2.lookups.stuck() == 1, "sink " + (ref.get() == null ? "freed" : "still referenced") + ", " + calls + " lookups, " + e2.lookups.stuck() + " stuck");
    release.countDown();
    long t = System.nanoTime();
    while (System.nanoTime() - t < 5000000000L && e2.lookups.stuck() > 0) Thread.sleep(20);
    check("shutdown: ... and the blocked call returning frees its slot", e2.lookups.stuck() == 0);
  }

  // ---- the TCP stage: timeouts and fallbacks, with sockets whose connect is scripted ----

  static final InetAddress BAD_ADDR;
  static { InetAddress a = null; try { a = InetAddress.getByName("127.0.0.2"); } catch (Exception ignored) {} BAD_ADDR = a; }

  /** connect: 0 hangs for the whole timeout and then times out; 1 says "no route"; 2 refuses 127.0.0.2 and connects to anything else for real. */
  static final class FakeSocket extends Socket {
    final int mode;
    final AtomicInteger attempts;
    FakeSocket(int mode, AtomicInteger attempts) { this.mode = mode; this.attempts = attempts; }
    @Override public void connect(SocketAddress a, int timeout) throws IOException {
      attempts.incrementAndGet();
      if (mode == 0) { try { Thread.sleep(timeout); } catch (InterruptedException e) { throw new IOException("interrupted"); } throw new SocketTimeoutException("connect timed out"); }
      if (mode == 1) throw new NoRouteToHostException("No route to host");
      if (((InetSocketAddress) a).getAddress().equals(BAD_ADDR)) throw new ConnectException("Connection refused");
      super.connect(a, timeout);
    }
  }

  static DnsProbe.Env fakeEnv(final int mode, final AtomicInteger attempts, int port, InetAddress... addrs) {
    DnsProbe.Env e = env(port, clientTrust, new Fixed(addrs));
    e.sockets = new DnsProbe.SocketMaker() { @Override public Socket create() { return new FakeSocket(mode, attempts); } };
    return e;
  }

  static final class Fixed implements DnsProbe.Resolver {
    final InetAddress[] a;
    Fixed(InetAddress... a) { this.a = a; }
    @Override public InetAddress[] resolve(String h) { return a; }
  }

  static void connectStage() throws Exception {
    InetAddress lo = InetAddress.getByName("127.0.0.1");
    AtomicInteger at = new AtomicInteger();
    long t0 = System.nanoTime();
    Run hang = scanAndWait(fakeEnv(0, at, 853, lo), req("ct", 2, 500, NAME), 10000);
    long ms = (System.nanoTime() - t0) / 1000000L;
    JSONObject o = hang.host(NAME);
    check("connect stage: a TCP connect that runs out of time -> timeout (not connect), \"connect timed out\", no stage times, 2 samples tried",
        o != null && "timeout".equals(o.optString("status")) && "connect timed out".equals(o.optString("error")) && o.isNull("connectMs") && o.isNull("tlsMs") && o.isNull("queryMs") && o.optInt("okSamples", -1) == 0 && at.get() == 2 && ms >= 900 && ms < 4000,
        st(o) + ", " + at + " attempts, " + ms + " ms");
    at.set(0);
    Run noRoute = scanAndWait(fakeEnv(1, at, 853, lo), req("nr", 1, 500, NAME), 10000);
    o = noRoute.host(NAME);
    check("connect stage: 'no route to host' -> connect, \"connection refused or unreachable\"", o != null && "connect".equals(o.optString("status")) && "connection refused or unreachable".equals(o.optString("error")) && o.isNull("connectMs"), st(o));
    // the time budget of the TCP stage is shared by all addresses: two that hang cost one timeout, not two
    at.set(0);
    t0 = System.nanoTime();
    Run two = scanAndWait(fakeEnv(0, at, 853, lo, BAD_ADDR), req("tw", 1, 500, NAME), 10000);
    ms = (System.nanoTime() - t0) / 1000000L;
    o = two.host(NAME);
    check("connect stage: two addresses that both hang share one timeout (about 500 ms, not 1000) -> timeout", o != null && "timeout".equals(o.optString("status")) && ms < 900 && at.get() == 1, st(o) + ", " + at + " attempts, " + ms + " ms");
    // the first address refuses, the second works: the probe goes on to it and reports it
    Server s = new Server(serverGood, B.GOOD);
    try {
      at.set(0);
      Run fallback = scanAndWait(fakeEnv(2, at, s.port, BAD_ADDR, lo), req("fb", 1, 3000, NAME), 10000);
      o = fallback.host(NAME);
      check("connect stage: the first address refuses, the second is used: ok, ip is the second", o != null && "ok".equals(o.optString("status")) && "127.0.0.1".equals(o.optString("ip")) && at.get() == 2, st(o) + " " + (o == null ? "" : o.optString("ip")));
    } finally { s.close(); }
  }

  // ---- cancel ----

  static void cancel() throws Exception {
    Server s = new Server(serverWild, B.NOANSWER);
    try {
      String[] hosts = new String[10];
      for (int i = 0; i < hosts.length; i++) hosts[i] = "c" + i + "." + WILD;
      DnsProbe.Runner runner = new DnsProbe.Runner(env(s.port, clientTrust, new Names()));
      Run run = scan(runner, req("cx", 3, 10000, hosts));
      long t = System.nanoTime();
      while (System.nanoTime() - t < 8000000000L && s.queriesSeen.get() < 6) Thread.sleep(20);
      check("cancel: six probes are waiting for an answer (and the other four have not started)", s.queriesSeen.get() == 6 && run.done.getCount() == 1 && run.hosts.isEmpty(), s.queriesSeen + " waiting, " + run.hosts.size() + " results");
      runner.cancel("other-id");
      Thread.sleep(300);
      check("cancel: another id does not cancel the scan", run.done.getCount() == 1 && run.hosts.isEmpty());
      long c0 = System.nanoTime();
      runner.cancel("cx");
      boolean done = run.await(3000);
      long ms = (System.nanoTime() - c0) / 1000000L;
      check("cancel: done arrives promptly after the cancel (the timeout was 10 s)", done && ms < 2000, ms + " ms");
      boolean allCancelled = run.hosts.size() == 10;
      for (JSONObject o : run.hosts) allCancelled &= "cancelled".equals(o.optString("status")) && o.isNull("connectMs") && o.optInt("okSamples", -1) == 0;
      check("cancel: all ten hosts are reported, as cancelled (six that were running, four that had not started)", allCancelled, run.hosts.size() + " results");
      check("cancel: exactly one done, after the ten, with count 10", run.dones.size() == 1 && run.hostsBeforeDone == 10 && run.dones.get(0).optInt("count") == 10);
      long u = System.nanoTime();
      while (System.nanoTime() - u < 3000000000L && s.finished.get() < 6) Thread.sleep(20);
      check("cancel: the six open connections were closed (the server sees each close)", s.finished.get() == 6, s.finished + " closed");
      Thread.sleep(300);
      check("cancel: nothing is sent after done", run.raw.get(run.raw.size() - 1).contains("\"done\"") && run.dones.size() == 1);
    } finally { s.close(); }

    // a new scan cancels the running one
    Server never = new Server(serverGood, B.NOANSWER);
    Server good = new Server(serverGood, B.GOOD);
    try {
      Names names = new Names();
      DnsProbe.Env e = env(never.port, clientTrust, names);
      DnsProbe.Runner runner = new DnsProbe.Runner(e);
      Run a = scan(runner, req("A", 1, 10000, NAME, NAME, NAME));
      long t = System.nanoTime();
      while (System.nanoTime() - t < 5000000000L && never.queriesSeen.get() < 3) Thread.sleep(20);
      e.port = good.port;                                                    // the next scan goes to the good server
      Run b = scan(runner, req("B", 1, 3000, NAME));
      check("cancel: a new scan is accepted while the old one runs", new JSONObject(b.startResponse).optBoolean("ok"), b.startResponse);
      check("cancel: ... and the old one ends (cancelled, done)", a.await(3000) && a.hosts.size() == 3 && "cancelled".equals(a.hosts.get(0).optString("status")), a.hosts.size() + " results");
      check("cancel: ... while the new one runs to the end", b.await(8000) && b.host(NAME) != null && "ok".equals(b.host(NAME).optString("status")));
    } finally { never.close(); good.close(); }

    // cancel during a name lookup that does not return
    Names stuck = new Names();
    stuck.block = new CountDownLatch(1);
    DnsProbe.Runner runner = new DnsProbe.Runner(env(1, clientTrust, stuck));
    Run run = scan(runner, req("lk", 3, 10000, "s1.test.example", "s2.test.example"));
    Thread.sleep(300);
    long c0 = System.nanoTime();
    runner.cancel("lk");
    check("cancel: a scan stuck in name lookups is ended at once", run.await(3000) && (System.nanoTime() - c0) / 1000000L < 2000 && run.hosts.size() == 2 && "cancelled".equals(run.hosts.get(0).optString("status")));
    stuck.block.countDown();

    // shutdown (the activity going away) ends a running scan too
    Server never2 = new Server(serverGood, B.NOANSWER);
    try {
      DnsProbe.Runner r2 = new DnsProbe.Runner(env(never2.port, clientTrust, new Names()));
      Run x = scan(r2, req("sd", 1, 10000, NAME));
      long t = System.nanoTime();
      while (System.nanoTime() - t < 5000000000L && never2.queriesSeen.get() < 1) Thread.sleep(20);
      r2.shutdown();
      check("cancel: shutdown (the activity's onDestroy) ends the running scan", x.await(3000) && x.host(NAME) != null && "cancelled".equals(x.host(NAME).optString("status")));
    } finally { never2.close(); }

    // an executor that refuses (shut down): the start says so and nothing is left running
    ExecutorService dead = Executors.newFixedThreadPool(1);
    dead.shutdownNow();
    DnsProbe.Runner r3 = new DnsProbe.Runner(env(1, clientTrust, new Names()));
    String resp = r3.start(req("dd", 1, 500, NAME), dead, new Run());
    check("cancel: an executor that is shut down gives {\"ok\":false,...}, not an exception", !new JSONObject(resp).getBoolean("ok"), resp);
  }

  // ---- the bridge in MainActivity and the shape of the class ----

  static String bodyOf(String src, String sig) {
    int i = src.indexOf(sig);
    if (i < 0 || src.indexOf(sig, i + 1) >= 0) return null;
    int open = src.indexOf('{', i), depth = 0, k = open;
    for (; k < src.length(); k++) { char c = src.charAt(k); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) break; } }
    return src.substring(i, k + 1);
  }

  static void sourceScan() throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    if (dir == null) { System.out.println("SKIP source scan: src/com/bloatware/bingblop not found"); return; }
    String m = new String(Files.readAllBytes(new File(dir, "MainActivity.java").toPath()), StandardCharsets.UTF_8);
    String p = new String(Files.readAllBytes(new File(dir, "DnsProbe.java").toPath()), StandardCharsets.UTF_8);
    check("source: dnsProbeStart is a bridge method with the exact name and signature", m.contains("@JavascriptInterface\n        public String dnsProbeStart(String requestJson) {"));
    check("source: dnsProbeCancel is a bridge method with the exact name and signature", m.contains("@JavascriptInterface\n        public void dnsProbeCancel(String id) {"));
    String start = bodyOf(m, "public String dnsProbeStart(String requestJson)");
    check("source: dnsProbeStart hands the request to the Java-side validation (Runner.start) on the app's own executor", start != null && start.contains("dnsProbe.start(requestJson, dnsProbeExec, dnsProbeSink)"));
    String cancel = bodyOf(m, "public void dnsProbeCancel(String id)");
    check("source: dnsProbeCancel cancels through the Runner", cancel != null && cancel.contains("dnsProbe.cancel(id)"));
    check("source: results are delivered with window.onDnsProbe(<quoted json>) through notifyJs (dropped after onDestroy)", m.contains("notifyJs(\"window.onDnsProbe && window.onDnsProbe(\" + JSONObject.quote(json) + \")\")"));
    check("source: the probe's pool is a fixed pool of DnsProbe.MAX_PARALLEL threads owned by MainActivity", m.contains("private final ExecutorService dnsProbeExec = Executors.newFixedThreadPool(DnsProbe.MAX_PARALLEL);"));
    String destroy = bodyOf(m, "protected void onDestroy()");
    check("source: onDestroy cancels the scan and shuts the probe's executor down", destroy != null && destroy.contains("dnsProbe.shutdown()") && destroy.contains("dnsProbeExec.shutdownNow()"));
    check("source: the bridge methods need no working mode (no mode, root or shell call in them)", start != null && cancel != null && !start.contains("Mode") && !start.contains("runShell") && !cancel.contains("Mode"));
    check("source: DnsProbe imports nothing from Android", !p.contains("import android.") && !p.contains("android.util.Log"));
    check("source: DnsProbe never logs", !p.contains("System.out") && !p.contains("System.err") && !p.contains("printStackTrace") && !p.contains("Log."));
    check("source: DnsProbe checks the host name (endpoint identification HTTPS and the certificate's DNS names) and sets SNI", p.contains("setEndpointIdentificationAlgorithm(\"HTTPS\")") && p.contains("certificateNamesHost((X509Certificate) chain[0], host)") && p.contains("new SNIHostName(host)"));
    check("source: the manifest already declares INTERNET (this change adds no permission)", findUp("AndroidManifest.xml") != null && new String(Files.readAllBytes(findUp("AndroidManifest.xml").toPath()), StandardCharsets.UTF_8).contains("android.permission.INTERNET"));
  }
}
