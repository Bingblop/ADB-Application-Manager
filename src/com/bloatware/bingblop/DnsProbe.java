package com.bloatware.bingblop;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/*
 * DNS speed test: how fast do some DNS-over-TLS servers (port 853, RFC 7858) answer? Pure Java, no Android classes.
 *
 * WHAT IT DOES. For each host name, `samples` times in a row:
 *   1. resolve the name with the system resolver (not counted in any time; a failure is status "dns");
 *   2. open a TCP connection to port 853 (time = connectMs);
 *   3. run a TLS handshake with SNI = the host name, the platform's default trust store and the host name checked against the
 *      certificate (HTTPS endpoint identification, plus a second check of the certificate's DNS names here) (time = tlsMs);
 *   4. on the same connection send ONE query: a random 16-bit id, RD set, one question "example.com" type A class IN, in the
 *      RFC 7858 framing (2-byte big-endian length, then the message), and read the answer (time = queryMs). The answer counts only
 *      if it has the same id, QR = 1 and RCODE 0 or 3 (NOERROR or NXDOMAIN). Then the socket is closed.
 * The times reported are the MEDIAN (an even count: the mean of the two middle values, rounded half up). When at least one sample
 * completed all stages, the medians are taken over those samples. When none did, each stage's median is taken over the samples
 * that completed that stage (null if none did), so "TCP works, TLS does not" still shows the connect time.
 *
 * LIMITS. At most 40 hosts per scan, at most 6 probed at once (MAX_PARALLEL), samples 1..5 (default 3), timeoutMs 500..10000
 * (default 3000). The request text is at most 32768 characters, and after the one JSON object only white space may follow.
 * The name is looked up ONCE per host, and the addresses are used for all its samples. Lookups run on a small pool of daemon threads
 * shared by every scan of one Env (LOOKUP_THREADS = 8 for the whole app): InetAddress.getAllByName cannot be interrupted, so a lookup
 * that outlives its timeout is "stuck", keeps its thread until the resolver returns, and is counted. While 8 are stuck (or all 8 threads
 * are busy) a new lookup fails at once with status "dns", error "resolver busy", so no thread is ever added beyond the cap.
 * The timeout is per stage: the name lookup, the TCP connect (shared
 * by all addresses tried, at most 4), the TLS handshake, and the query each get timeoutMs; sockets have SO_TIMEOUT and a watchdog
 * closes a socket that a slow server keeps dripping bytes into. A scan is cancelled by closing its sockets (prompt, no waiting for a
 * timeout). Nothing is logged, and nothing but the fields below is ever returned: no certificate, no answer data, no exception text.
 *
 * HOST NAMES are checked by checkHost(), a port of the page's pdnsCheckHost(): letters, digits, hyphens and dots; spaces or tabs
 * around the name and one trailing dot are dropped, the name is lower-cased; at least one dot; every part 1 to 63 characters, none
 * starting or ending with a hyphen; at most 253 characters in all; not an IP address (all parts numeric or 0x-hex) and the last part
 * not all digits; no scheme, port, path, spaces or other characters. An invalid host is NOT probed: its result has status "dns" and
 * error "not a host name". The result's "host" is the text the page sent, unchanged. Duplicate hosts are probed separately.
 *
 * CONTRACT (the page <-> Java; the bridge in MainActivity is a thin wrapper around Runner):
 *   window.AndroidBridge.dnsProbeStart(requestJson) -> {"ok":true,"id":"s1"} or {"ok":false,"error":"<short English text>"}
 *     requestJson = {"id":"s1","hosts":["dns.google","one.one.one.one"],"samples":3,"timeoutMs":3000}
 *     id: 1 to 40 characters from [A-Za-z0-9_-]. A new scan cancels the running one. The same id as the scan that is still running is refused.
 *   window.AndroidBridge.dnsProbeCancel(id)   cancels that scan if it is the running one; unknown ids are ignored.
 *   Java -> page: window.onDnsProbe(json) is called with a JSON text (a string, like the other callbacks of the bridge):
 *     once per host, as soon as that host is done (in no particular order):
 *       {"id":"s1","host":"dns.google","status":"ok","ip":"8.8.8.8","connectMs":12,"tlsMs":30,"queryMs":18,"samples":3,"okSamples":3,"error":""}
 *       status: "ok" | "dns" | "connect" | "tls" | "query" | "timeout" | "cancelled"
 *         dns       the name did not resolve, the lookup took longer than timeoutMs, the resolver is busy, or it is not a host name
 *         connect   TCP refused or unreachable
 *         timeout   a stage got no answer within timeoutMs (TCP connect, TLS handshake or the DNS answer)
 *         tls       the handshake failed: the server closed or spoke something else, the certificate is not trusted, or does not match the host name
 *         query     the DNS answer was not usable: closed early, garbage, length 0, truncated, other id, not a response, RCODE other than 0 or 3
 *         cancelled the scan was cancelled before this host was done
 *       ip: the address connected to ("" when none); connectMs, tlsMs, queryMs: integers (milliseconds) or null; error: "" when ok.
 *       When no sample completed, status and error are those of the last failure.
 *     then exactly once: {"id":"s1","done":true,"count":<number of hosts in the request>}  (also after a cancel)
 *
 * Not run on a phone; the timings include this phone's resolver, radio and CPU, and are a comparison between hosts, not a benchmark.
 */
public final class DnsProbe {
  private DnsProbe() {}

  public static final int PORT = 853;
  public static final int MAX_HOSTS = 40;
  public static final int MAX_PARALLEL = 6;
  public static final int MIN_SAMPLES = 1, MAX_SAMPLES = 5, DEFAULT_SAMPLES = 3;
  public static final int MIN_TIMEOUT_MS = 500, MAX_TIMEOUT_MS = 10000, DEFAULT_TIMEOUT_MS = 3000;
  public static final int MAX_REQUEST_CHARS = 32768;
  public static final int MAX_ADDRESSES = 4;
  public static final int LOOKUP_THREADS = 8;
  public static final int MAX_ID_CHARS = 40;
  public static final String QUERY_NAME = "example.com";

  public static final String OK = "ok", DNS = "dns", CONNECT = "connect", TLS = "tls", QUERY = "query", TIMEOUT = "timeout", CANCELLED = "cancelled";

  private static final SecureRandom RANDOM = new SecureRandom();

  // ---------------------------------------------------------------------------------------------------------------------------
  // Injection points: the app uses the real ones, the tests point names at 127.0.0.1, the port at a test server and trust at a test certificate.
  // ---------------------------------------------------------------------------------------------------------------------------

  public interface Resolver { InetAddress[] resolve(String host) throws IOException; }
  public interface Clock { long nanoTime(); }
  public interface Sink { void emit(String json); }

  /** The threads that run name lookups, for every scan of one Env: at most `cap` threads exist, however many lookups hang. */
  public static final class Lookups {
    final int cap;
    final AtomicInteger stuck = new AtomicInteger();          // lookups abandoned after their timeout whose resolver call has not returned
    final ThreadPoolExecutor pool;
    private static final AtomicInteger POOLS = new AtomicInteger();
    /** The start of the names of this pool's threads. */
    public final String prefix = "dns-probe-lookup-" + POOLS.incrementAndGet() + "-";
    public Lookups(int cap) {
      this.cap = cap;
      final AtomicInteger n = new AtomicInteger();
      pool = new ThreadPoolExecutor(0, cap, 5, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
          Thread t = new Thread(r, prefix + n.incrementAndGet());
          t.setDaemon(true);
          return t;
        }
      });
    }
    public int stuck() { return stuck.get(); }
  }

  public static final class Env {
    public int port = PORT;
    public Lookups lookups = new Lookups(LOOKUP_THREADS);
    /** null: the platform's default factory (its trust store). */
    public SSLSocketFactory sslFactory;
    public Resolver resolver = new Resolver() {
      @Override public InetAddress[] resolve(String host) throws IOException { return InetAddress.getAllByName(host); }
    };
    public Clock clock = new Clock() {
      @Override public long nanoTime() { return System.nanoTime(); }
    };
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // Host names: the same rules as the page's pdnsCheckHost()
  // ---------------------------------------------------------------------------------------------------------------------------

  public static final class HostCheck {
    public final boolean ok;
    public final String host;     // lower-cased, trimmed, without the trailing dot (when ok)
    public final String why;      // empty | chars | long | dots | label | hyphen | onedot | ip | tld (when not ok)
    HostCheck(boolean ok, String host, String why) { this.ok = ok; this.host = host; this.why = why; }
  }

  private static HostCheck bad(String why) { return new HostCheck(false, null, why); }

  private static boolean isNum(String l) {
    if (l.isEmpty()) return false;
    boolean dec = true;
    for (int i = 0; i < l.length(); i++) { char c = l.charAt(i); if (c < '0' || c > '9') { dec = false; break; } }
    if (dec) return true;
    if (l.length() < 3 || l.charAt(0) != '0' || (l.charAt(1) != 'x' && l.charAt(1) != 'X')) return false;
    for (int i = 2; i < l.length(); i++) {
      char c = l.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
    }
    return true;
  }

  public static HostCheck checkHost(String text) {
    if (text == null) return bad("empty");
    int a = 0, b = text.length();
    while (a < b && (text.charAt(a) == ' ' || text.charAt(a) == '\t')) a++;          // spaces and tabs around a pasted name are dropped; nothing else is
    while (b > a && (text.charAt(b - 1) == ' ' || text.charAt(b - 1) == '\t')) b--;
    String s = text.substring(a, b);
    if (s.isEmpty()) return bad("empty");
    if (s.charAt(s.length() - 1) == '.') s = s.substring(0, s.length() - 1);        // one dot at the end is how a full name is written
    if (s.isEmpty()) return bad("empty");
    for (int i = 0; i < s.length(); i++) {                                          // ASCII only: no spaces, quotes, ; ` $ \ / : @, line breaks, accents or look-alike letters
      char c = s.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-')) return bad("chars");
    }
    if (s.length() > 253) return bad("long");
    String[] labels = s.split("\\.", -1);
    for (String l : labels) if (l.isEmpty()) return bad("dots");
    for (String l : labels) if (l.length() > 63) return bad("label");
    for (String l : labels) if (l.charAt(0) == '-' || l.charAt(l.length() - 1) == '-') return bad("hyphen");
    if (labels.length < 2) return bad("onedot");
    boolean allNum = true;
    for (String l : labels) if (!isNum(l)) { allNum = false; break; }
    if (allNum) return bad("ip");
    String last = labels[labels.length - 1];
    boolean digits = true;
    for (int i = 0; i < last.length(); i++) { char c = last.charAt(i); if (c < '0' || c > '9') { digits = false; break; } }
    if (digits) return bad("tld");
    return new HostCheck(true, s.toLowerCase(Locale.ROOT), "");
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // The request
  // ---------------------------------------------------------------------------------------------------------------------------

  public static final class BadRequest extends Exception {
    public BadRequest(String message) { super(message); }
  }

  public static final class Request {
    public final String id;
    public final List<String> hosts;       // as sent by the page
    public final int samples, timeoutMs;
    Request(String id, List<String> hosts, int samples, int timeoutMs) { this.id = id; this.hosts = hosts; this.samples = samples; this.timeoutMs = timeoutMs; }
  }

  public static boolean validId(String id) {
    if (id == null || id.isEmpty() || id.length() > MAX_ID_CHARS) return false;
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) return false;
    }
    return true;
  }

  private static int clampNumber(JSONObject o, String key, int lo, int hi, int dflt) throws BadRequest {
    if (!o.has(key) || o.isNull(key)) return dflt;
    Object v = o.opt(key);
    if (!(v instanceof Number)) throw new BadRequest(key + " must be a number");
    double d = ((Number) v).doubleValue();
    if (Double.isNaN(d)) return dflt;
    if (d < lo) return lo;
    if (d > hi) return hi;
    return (int) d;
  }

  /**
   * The index just after the first top-level object of the text, or -1 when the text is not exactly one object followed by white space
   * (space, tab, CR, LF) only: anything after it, a second value, a comment, a NUL, makes it -1.
   */
  static int objectEnd(String t) {
    int i = 0, n = t.length();
    while (i < n && isWhite(t.charAt(i))) i++;
    if (i >= n || t.charAt(i) != '{') return -1;
    int depth = 0;
    boolean inString = false;
    for (; i < n; i++) {
      char c = t.charAt(i);
      if (c == 0) return -1;
      if (inString) {
        if (c == '\\') i++;
        else if (c == '"') inString = false;
      } else if (c == '"') {
        inString = true;
      } else if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
        if (depth == 0) {
          int end = i + 1;
          for (int k = end; k < n; k++) if (!isWhite(t.charAt(k))) return -1;
          return end;
        }
        if (depth < 0) return -1;
      }
    }
    return -1;
  }

  private static boolean isWhite(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

  public static Request parseRequest(String json) throws BadRequest {
    if (json == null || json.isEmpty()) throw new BadRequest("empty request");
    if (json.length() > MAX_REQUEST_CHARS) throw new BadRequest("request too large");
    int end = objectEnd(json);
    if (end < 0) throw new BadRequest("request is not valid JSON");
    JSONObject o;
    try {
      o = new JSONObject(json.substring(0, end));
    } catch (JSONException e) {
      throw new BadRequest("request is not valid JSON");
    }
    Object idv = o.opt("id");
    if (!(idv instanceof String) || !validId((String) idv)) throw new BadRequest("bad id (1 to 40 letters, digits, - or _)");
    Object hv = o.opt("hosts");
    if (!(hv instanceof JSONArray)) throw new BadRequest("hosts must be a list");
    JSONArray arr = (JSONArray) hv;
    if (arr.length() == 0) throw new BadRequest("no hosts");
    if (arr.length() > MAX_HOSTS) throw new BadRequest("too many hosts (at most " + MAX_HOSTS + ")");
    List<String> hosts = new ArrayList<String>();
    for (int i = 0; i < arr.length(); i++) {
      Object h = arr.opt(i);
      if (!(h instanceof String)) throw new BadRequest("every host must be a text");
      hosts.add((String) h);
    }
    int samples = clampNumber(o, "samples", MIN_SAMPLES, MAX_SAMPLES, DEFAULT_SAMPLES);
    int timeout = clampNumber(o, "timeoutMs", MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    return new Request((String) idv, Collections.unmodifiableList(hosts), samples, timeout);
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // The DNS message
  // ---------------------------------------------------------------------------------------------------------------------------

  /** The query without the length prefix: header (id, RD), one question example.com A IN. */
  static byte[] buildQuery(int id) {
    byte[] name = encodeName(QUERY_NAME);
    byte[] q = new byte[12 + name.length + 4];
    q[0] = (byte) (id >> 8); q[1] = (byte) id;
    q[2] = 0x01;                      // QR 0, opcode 0, AA 0, TC 0, RD 1
    q[5] = 1;                         // QDCOUNT = 1
    System.arraycopy(name, 0, q, 12, name.length);
    int p = 12 + name.length;
    q[p + 1] = 1;                     // QTYPE A
    q[p + 3] = 1;                     // QCLASS IN
    return q;
  }

  private static byte[] encodeName(String name) {
    String[] labels = name.split("\\.");
    int n = 1;
    for (String l : labels) n += 1 + l.length();
    byte[] out = new byte[n];
    int p = 0;
    for (String l : labels) {
      out[p++] = (byte) l.length();
      for (int i = 0; i < l.length(); i++) out[p++] = (byte) l.charAt(i);
    }
    return out;
  }

  /** null when the message is an acceptable answer to the query with this id; else a short reason. */
  static String checkAnswer(byte[] m, int id) {
    if (m == null || m.length < 12) return "answer shorter than a DNS header";
    int got = ((m[0] & 0xff) << 8) | (m[1] & 0xff);
    if (got != (id & 0xffff)) return "answer for another query";
    if ((m[2] & 0x80) == 0) return "not a response";
    int rcode = m[3] & 0x0f;
    if (rcode != 0 && rcode != 3) return "server error code";
    return null;
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // Median and rounding
  // ---------------------------------------------------------------------------------------------------------------------------

  /** The median of the values; for an even count the mean of the two middle ones, rounded half up. null for no values. */
  public static Integer median(List<Integer> values) {
    if (values == null || values.isEmpty()) return null;
    long[] v = new long[values.size()];
    for (int i = 0; i < v.length; i++) v[i] = values.get(i);
    Arrays.sort(v);
    int n = v.length;
    if (n % 2 == 1) return (int) v[n / 2];
    return (int) ((v[n / 2 - 1] + v[n / 2] + 1) / 2);
  }

  static int toMs(long nanos) {
    if (nanos <= 0) return 0;
    long ms = (nanos + 500000L) / 1000000L;
    return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // Certificate host name check (a second check next to the TLS stack's own endpoint identification)
  // ---------------------------------------------------------------------------------------------------------------------------

  /** True when the certificate names the host in a DNS subjectAltName: an exact name, or "*.rest" for exactly one leftmost label. */
  static boolean certificateNamesHost(X509Certificate cert, String host) {
    try {
      Collection<List<?>> names = cert.getSubjectAlternativeNames();
      if (names == null) return false;
      String h = host.toLowerCase(Locale.ROOT);
      for (List<?> n : names) {
        if (n == null || n.size() < 2 || !(n.get(0) instanceof Integer) || ((Integer) n.get(0)) != 2 || !(n.get(1) instanceof String)) continue;
        String p = ((String) n.get(1)).toLowerCase(Locale.ROOT);
        if (p.endsWith(".")) p = p.substring(0, p.length() - 1);
        if (p.equals(h)) return true;
        if (p.startsWith("*.") && p.indexOf('*', 1) < 0) {
          String rest = p.substring(1);                             // ".example.com"
          int dot = h.indexOf('.');
          if (dot > 0 && h.substring(dot).equals(rest) && rest.indexOf('.', 1) > 0) return true;
        }
      }
    } catch (CertificateException e) {
      return false;
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // One result, as JSON
  // ---------------------------------------------------------------------------------------------------------------------------

  static final class Result {
    String host = "", status = OK, ip = "", error = "";
    Integer connectMs, tlsMs, queryMs;
    int samples, okSamples;
  }

  static String toJson(String id, Result r) {
    try {
      JSONObject o = new JSONObject();
      o.put("id", id);
      o.put("host", r.host);
      o.put("status", r.status);
      o.put("ip", r.ip);
      o.put("connectMs", r.connectMs == null ? JSONObject.NULL : (Object) r.connectMs);
      o.put("tlsMs", r.tlsMs == null ? JSONObject.NULL : (Object) r.tlsMs);
      o.put("queryMs", r.queryMs == null ? JSONObject.NULL : (Object) r.queryMs);
      o.put("samples", r.samples);
      o.put("okSamples", r.okSamples);
      o.put("error", r.error);
      return o.toString();
    } catch (JSONException e) {
      return "{\"id\":\"" + id + "\",\"status\":\"query\"}";
    }
  }

  static String doneJson(String id, int count) {
    try {
      return new JSONObject().put("id", id).put("done", true).put("count", count).toString();
    } catch (JSONException e) {
      return "{\"id\":\"" + id + "\",\"done\":true,\"count\":" + count + "}";
    }
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // One scan
  // ---------------------------------------------------------------------------------------------------------------------------

  private static final class Sample {
    String status = OK, error = "", ip = "";
    int connectMs = -1, tlsMs = -1, queryMs = -1;
    boolean cancelled;
  }

  private static void closeQuietly(Closeable c) {
    if (c == null) return;
    try { c.close(); } catch (Throwable ignored) {}
  }

  private static boolean hasCause(Throwable t, Class<? extends Throwable> kind) {
    for (int i = 0; t != null && i < 8; i++, t = t.getCause()) if (kind.isInstance(t)) return true;
    return false;
  }

  public static final class Scan {
    private final Request req;
    private final Env env;
    private final Sink sink;
    private final ConcurrentLinkedQueue<Integer> todo = new ConcurrentLinkedQueue<Integer>();
    private final AtomicInteger lanesLeft = new AtomicInteger();
    private final AtomicBoolean doneSent = new AtomicBoolean();
    private final Object lock = new Object();
    private final Set<Socket> sockets = new HashSet<Socket>();
    private final Set<Lookup> lookups = new HashSet<Lookup>();
    private final Timer watchdog = new Timer("dns-probe-watchdog", true);
    private volatile boolean cancelled;

    Scan(Request req, Env env, Sink sink) {
      this.req = req; this.env = env; this.sink = sink;
      for (int i = 0; i < req.hosts.size(); i++) todo.add(i);
    }

    public String id() { return req.id; }
    public boolean isFinished() { return doneSent.get(); }
    public boolean isCancelled() { return cancelled; }

    /** Closes the sockets in use and abandons the name lookups; the lanes then report what is left as cancelled and send "done". */
    public void cancel() {
      List<Socket> toClose;
      List<Lookup> toStop;
      synchronized (lock) {
        cancelled = true;
        toClose = new ArrayList<Socket>(sockets);
        toStop = new ArrayList<Lookup>(lookups);
      }
      for (Socket s : toClose) closeQuietly(s);
      for (Lookup l : toStop) l.latch.countDown();               // the waiting lane wakes up; the lookup itself is abandoned there
    }

    void start(Executor ex) {
      int lanes = Math.min(MAX_PARALLEL, req.hosts.size());
      lanesLeft.set(lanes);
      for (int k = 0; k < lanes; k++) {
        try {
          ex.execute(new Runnable() {
            @Override public void run() { lane(); }
          });
        } catch (RejectedExecutionException refused) {
          if (k == 0) { watchdog.cancel(); throw refused; }
          laneFinished();                       // the lanes that did start take the rest of the hosts
        }
      }
    }

    private void lane() {
      try {
        Integer i;
        while ((i = todo.poll()) != null) {
          Result r;
          try {
            r = cancelled ? cancelledResult(i) : probeHost(i);
          } catch (Throwable t) {
            r = cancelled ? cancelledResult(i) : failedResult(i, CONNECT, "probe failed");
          }
          emit(toJson(req.id, r));
        }
      } finally {
        laneFinished();
      }
    }

    private void laneFinished() {
      if (lanesLeft.decrementAndGet() != 0) return;
      Integer i;
      while ((i = todo.poll()) != null) emit(toJson(req.id, cancelledResult(i)));
      watchdog.cancel();
      if (doneSent.compareAndSet(false, true)) emit(doneJson(req.id, req.hosts.size()));
    }

    private void emit(String json) {
      try { sink.emit(json); } catch (Throwable ignored) {}
    }

    private Result base(int index) {
      Result r = new Result();
      r.host = req.hosts.get(index);
      r.samples = req.samples;
      return r;
    }

    private Result cancelledResult(int index) {
      Result r = base(index);
      r.status = CANCELLED; r.error = "cancelled";
      return r;
    }

    private Result failedResult(int index, String status, String error) {
      Result r = base(index);
      r.status = status; r.error = error;
      return r;
    }

    private Result probeHost(int index) {
      String original = req.hosts.get(index);
      HostCheck hc = checkHost(original);
      if (!hc.ok) return failedResult(index, DNS, "not a host name");
      // 1. the name, once for all samples (not counted in any time)
      InetAddress[] addrs;
      try {
        addrs = lookup(hc.host, req.timeoutMs);
      } catch (CancellationException | InterruptedException stopped) {
        if (stopped instanceof InterruptedException) { Thread.currentThread().interrupt(); cancel(); }
        return cancelledResult(index);
      } catch (TimeoutException late) {
        return failedResult(index, DNS, "name lookup timed out");
      } catch (RejectedExecutionException busy) {
        return failedResult(index, DNS, "resolver busy");
      } catch (Exception failed) {
        return failedResult(index, DNS, "name not found");
      }
      if (cancelled) return cancelledResult(index);
      if (addrs == null || addrs.length == 0) return failedResult(index, DNS, "name not found");
      List<Sample> done = new ArrayList<Sample>();
      for (int n = 0; n < req.samples; n++) {
        if (cancelled) return cancelledResult(index);
        Sample s = oneSample(hc.host, addrs);
        if (s.cancelled) return cancelledResult(index);
        done.add(s);
      }
      return summarize(base(index), done);
    }

    private Result summarize(Result r, List<Sample> samples) {
      List<Integer> c = new ArrayList<Integer>(), t = new ArrayList<Integer>(), q = new ArrayList<Integer>();
      int ok = 0;
      for (Sample s : samples) {
        if (OK.equals(s.status)) { ok++; c.add(s.connectMs); t.add(s.tlsMs); q.add(s.queryMs); }
      }
      r.okSamples = ok;
      for (Sample s : samples) if (!s.ip.isEmpty()) r.ip = s.ip;
      if (ok == 0) {                         // none completed: each stage over the samples that got that far
        for (Sample s : samples) {
          if (s.connectMs >= 0) c.add(s.connectMs);
          if (s.tlsMs >= 0) t.add(s.tlsMs);
          if (s.queryMs >= 0) q.add(s.queryMs);
        }
        Sample last = samples.get(samples.size() - 1);
        r.status = last.status; r.error = last.error;
      } else {
        r.status = OK; r.error = "";
      }
      r.connectMs = median(c); r.tlsMs = median(t); r.queryMs = median(q);
      return r;
    }

    // ---- one sample: name, TCP, TLS, one query ----

    private boolean register(Socket s) {
      synchronized (lock) {
        if (!cancelled) { sockets.add(s); return true; }
      }
      closeQuietly(s);
      return false;
    }

    private void unregister(Socket s) {
      synchronized (lock) { sockets.remove(s); }
    }

    /** Closes the socket when the stage takes longer than allowed, even if the peer keeps sending a byte now and then. */
    private final class Guard {
      volatile boolean fired;
      private TimerTask task;
      Guard(final Socket raw, long ms) {
        task = new TimerTask() {
          @Override public void run() { fired = true; closeQuietly(raw); }
        };
        try { watchdog.schedule(task, Math.max(1, ms)); } catch (IllegalStateException timerGone) { fired = true; closeQuietly(raw); }
      }
      void disarm() { task.cancel(); }
    }

    private Sample fail(Sample s, String status, String error) {
      s.status = status; s.error = error;
      return s;
    }

    private Sample oneSample(String host, InetAddress[] addrs) {
      Sample s = new Sample();
      final int timeout = req.timeoutMs;

      // 2. TCP
      Socket raw = null;
      SSLSocket ssl = null;
      try {
        long budgetEnd = System.nanoTime() + timeout * 1000000L;
        String lastStatus = CONNECT, lastError = "connection refused or unreachable";
        for (int a = 0; a < Math.min(addrs.length, MAX_ADDRESSES) && raw == null; a++) {
          long left = (budgetEnd - System.nanoTime()) / 1000000L;
          if (left <= 0) { lastStatus = TIMEOUT; lastError = "connect timed out"; break; }
          Socket cand = new Socket(Proxy.NO_PROXY);
          if (!register(cand)) { s.cancelled = true; return s; }
          long t0 = env.clock.nanoTime();
          try {
            cand.connect(new InetSocketAddress(addrs[a], env.port), (int) left);
            s.connectMs = toMs(env.clock.nanoTime() - t0);
            String ip = addrs[a].getHostAddress();
            int pct = ip.indexOf('%');
            s.ip = pct >= 0 ? ip.substring(0, pct) : ip;
            raw = cand;
          } catch (IOException e) {
            unregister(cand);
            closeQuietly(cand);
            if (cancelled) { s.cancelled = true; return s; }
            if (e instanceof SocketTimeoutException) { lastStatus = TIMEOUT; lastError = "connect timed out"; }
            else { lastStatus = CONNECT; lastError = "connection refused or unreachable"; }
          }
        }
        if (raw == null) return fail(s, lastStatus, lastError);
        raw.setSoTimeout(timeout);
        raw.setTcpNoDelay(true);

        // 3. TLS: SNI = host, default trust, host name checked
        SSLSocketFactory factory = env.sslFactory != null ? env.sslFactory : (SSLSocketFactory) SSLSocketFactory.getDefault();
        Guard g = new Guard(raw, timeout);
        try {
          ssl = (SSLSocket) factory.createSocket(raw, host, env.port, true);
          SSLParameters p = ssl.getSSLParameters();
          p.setEndpointIdentificationAlgorithm("HTTPS");
          try { p.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(host))); } catch (RuntimeException ignored) {}
          ssl.setSSLParameters(p);
          ssl.setSoTimeout(timeout);
          long t0 = env.clock.nanoTime();
          ssl.startHandshake();
          s.tlsMs = toMs(env.clock.nanoTime() - t0);
          Certificate[] chain = ssl.getSession().getPeerCertificates();
          if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate) || !certificateNamesHost((X509Certificate) chain[0], host)) {
            g.disarm();
            s.tlsMs = -1;
            return fail(s, TLS, "certificate does not match the host name");
          }
        } catch (IOException e) {
          g.disarm();
          s.tlsMs = -1;
          if (cancelled) { s.cancelled = true; return s; }
          if (g.fired || hasCause(e, SocketTimeoutException.class)) return fail(s, TIMEOUT, "TLS handshake timed out");
          if (hasCause(e, CertificateException.class)) return fail(s, TLS, "certificate rejected");
          return fail(s, TLS, "TLS handshake failed");
        }
        g.disarm();

        // 4. one query on the same connection
        int id = RANDOM.nextInt(0x10000);
        byte[] q = buildQuery(id);
        Guard g2 = new Guard(raw, timeout);
        try {
          long t0 = env.clock.nanoTime();
          OutputStream out = ssl.getOutputStream();
          byte[] framed = new byte[2 + q.length];
          framed[0] = (byte) (q.length >> 8); framed[1] = (byte) q.length;
          System.arraycopy(q, 0, framed, 2, q.length);
          out.write(framed);
          out.flush();
          InputStream in = ssl.getInputStream();
          long deadline = System.nanoTime() + timeout * 1000000L;
          byte[] lenBytes = new byte[2];
          readFully(ssl, in, lenBytes, deadline);
          int len = ((lenBytes[0] & 0xff) << 8) | (lenBytes[1] & 0xff);
          if (len < 12) { g2.disarm(); return fail(s, QUERY, len == 0 ? "answer of length 0" : "answer shorter than a DNS header"); }
          byte[] msg = new byte[len];
          readFully(ssl, in, msg, deadline);
          s.queryMs = toMs(env.clock.nanoTime() - t0);
          String bad = checkAnswer(msg, id);
          if (bad != null) { g2.disarm(); s.queryMs = -1; return fail(s, QUERY, bad); }
        } catch (IOException e) {
          g2.disarm();
          s.queryMs = -1;
          if (cancelled) { s.cancelled = true; return s; }
          if (g2.fired || hasCause(e, SocketTimeoutException.class)) return fail(s, TIMEOUT, "no answer in time");
          if (e instanceof EOFException) return fail(s, QUERY, "connection closed before an answer");
          return fail(s, QUERY, "no usable answer");
        }
        g2.disarm();
        s.status = OK; s.error = "";
        return s;
      } catch (IOException e) {
        if (cancelled) { s.cancelled = true; return s; }
        return fail(s, CONNECT, "connection refused or unreachable");
      } finally {
        if (ssl != null) { unregister(ssl); closeQuietly(ssl); }
        if (raw != null) { unregister(raw); closeQuietly(raw); }
      }
    }

    /** Reads exactly buf.length bytes; every read has SO_TIMEOUT, and the whole thing has a deadline (a slow drip cannot stretch it). */
    private void readFully(SSLSocket sock, InputStream in, byte[] buf, long deadlineNanos) throws IOException {
      int got = 0;
      while (got < buf.length) {
        long left = (deadlineNanos - System.nanoTime()) / 1000000L;
        if (left <= 0) throw new SocketTimeoutException("deadline");
        sock.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
        int n = in.read(buf, got, buf.length - got);
        if (n < 0) throw new EOFException("closed");
        got += n;
      }
    }

    /**
     * Runs the lookup on the Env's lookup pool and waits at most timeoutMs. Throws TimeoutException (the lookup is then "stuck" until the
     * resolver returns: counted, and it keeps its pool thread), CancellationException when the scan is cancelled, RejectedExecutionException
     * when the resolver is busy (cap reached: no new thread is made), anything else when the lookup failed.
     */
    private InetAddress[] lookup(final String host, int timeoutMs) throws Exception {
      final Lookups lk = env.lookups;
      if (lk.stuck.get() >= lk.cap) throw new RejectedExecutionException("resolver busy");
      final Lookup l = new Lookup();
      synchronized (lock) {
        if (cancelled) throw new CancellationException();
        lookups.add(l);
      }
      try {
        lk.pool.execute(new Runnable() {
          @Override public void run() {
            try { l.result = env.resolver.resolve(host); } catch (Throwable t) { l.failed = true; }
            finally {
              if (!l.state.compareAndSet(0, 2)) lk.stuck.decrementAndGet();          // abandoned meanwhile: it has returned now, the slot is free
              l.latch.countDown();
            }
          }
        });
        l.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        lk.stuck.incrementAndGet();                                                   // counted first, so the count never dips below the truth
        if (l.state.compareAndSet(0, 1)) {                                            // still running (or the scan was cancelled): abandon it
          if (cancelled) throw new CancellationException();
          throw new TimeoutException("lookup");
        }
        lk.stuck.decrementAndGet();                                                   // it finished in time after all
        if (l.failed || l.result == null) throw new java.net.UnknownHostException("lookup failed");
        return l.result;
      } finally {
        synchronized (lock) { lookups.remove(l); }
      }
    }
  }

  private static final class Lookup {
    final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();   // 0 running, 1 abandoned, 2 returned
    final CountDownLatch latch = new CountDownLatch(1);
    volatile InetAddress[] result;
    volatile boolean failed;
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // The part the bridge uses: one running scan at a time
  // ---------------------------------------------------------------------------------------------------------------------------

  public static final class Runner {
    private final Env env;
    private Scan current;

    public Runner(Env env) { this.env = env; }

    private static String error(String text) {
      try { return new JSONObject().put("ok", false).put("error", text).toString(); }
      catch (JSONException e) { return "{\"ok\":false,\"error\":\"failed\"}"; }
    }

    /** Validates, cancels the running scan, starts this one on the executor. Returns {"ok":true,"id":..} or {"ok":false,"error":..}. */
    public synchronized String start(String requestJson, Executor ex, Sink sink) {
      Request req;
      try {
        req = parseRequest(requestJson);
      } catch (BadRequest e) {
        return error(e.getMessage());
      }
      if (current != null && !current.isFinished() && current.id().equals(req.id)) return error("that id is still running");
      Scan old = current;
      Scan scan = new Scan(req, env, sink);
      try {
        if (old != null) old.cancel();
        scan.start(ex);
      } catch (RejectedExecutionException closing) {
        return error("the app is closing");
      }
      current = scan;
      try { return new JSONObject().put("ok", true).put("id", req.id).toString(); }
      catch (JSONException e) { return error("failed"); }
    }

    public synchronized void cancel(String id) {
      if (id != null && current != null && current.id().equals(id)) current.cancel();
    }

    public synchronized void shutdown() {
      if (current != null) current.cancel();
    }
  }
}
