package com.bloatware.bingblop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;

/*
 * The internet speed test of Task Manager > Network: latency, jitter, download and upload against Cloudflare's public speed test servers
 * (speed.cloudflare.com: GET /__down?bytes=N sends N bytes, POST /__up takes a body; the answer's headers name the data centre). Pure Java
 * (java.net through HttpSafe, org.json), no Android classes; the bridge in MainActivity is a thin wrapper around Runner.
 *
 * WHY THIS AND NOT OPENSPEEDTEST. OpenSpeedTest's page measures against an OpenSpeedTest server that someone has to run; there is no public one to
 * point an app at. Cloudflare's endpoints are public, served from the nearest of its data centres, and need nothing installed.
 *
 * WHAT IT DOES, in this order (each phase only after the one before):
 *   latency   1 warm-up request (opens the connection; not counted), then `pings` requests of 0 bytes, one after another on the kept-alive
 *             connection; each sample is the time from sending the request to having the answer's headers, less the time the server says
 *             it spent on the answer (Server-Timing cfSpeed*, SpeedMath.serverMs; kept as measured when it says nothing). Latency = the
 *             median, jitter = the mean absolute difference of consecutive samples (SpeedMath). More than half failed: the test stops with
 *             an error.
 *   download  `downStreams` connections at once, each fetching `downChunk` bytes again and again, for `phaseMs`; the first `rampMs` are a
 *             ramp-up (TCP growing its window) and are left out of the result. Stops early at `downCap` bytes.
 *   upload    the same with `upStreams` POSTs of `upChunk` bytes each (random bytes, so nothing on the way can compress them), cap `upCap`.
 * The byte count is sampled every `tickMs`; the live speed is the last `windowMs`; a progress event goes out every `reportMs`.
 *
 * LIMITS. At most `downCap` + `upCap` bytes per test (250 MB + 100 MB by default) whatever the line's speed; every connection has connect and
 * read timeouts; Stop (cancel), a new start and shutdown close every open connection at once, so no thread waits for a timeout. Nothing is
 * logged; no address, header or exception text but the short English error below goes to the page.
 *
 * CONTRACT (page <-> Java):
 *   speedTestStart(requestJson) -> {"ok":true,"id":"st1"} or {"ok":false,"error":"<short English text>"}
 *     requestJson = {"id":"st1"}; id: 1 to 40 characters [A-Za-z0-9_-]. A new start stops the running test first.
 *   speedTestStop(id)  stops that test if it is the running one; other ids are ignored.
 *   speedTestNetInfo() (MainActivity) -> {"type":"wifi|cellular|ethernet|vpn|other|none","metered":bool,"maxBytes":Runner.maxBytes()}
 *   Java -> page: window.onSpeedTest(jsonText), every event carries "id":
 *     {"type":"phase","phase":"latency"|"download"|"upload","phaseMs":10000,"rampMs":2000}
 *     {"type":"server","colo":"IAD"}                                       once, from the first answer that names it
 *     {"type":"ping","ms":12.3,"n":3,"of":15,"medianMs":12.1}               each latency sample
 *     {"type":"latency","pingMs":12.1,"jitterMs":1.4,"samples":15}
 *     {"type":"progress","phase":"download","t":1200,"mbps":94.2,"bytes":1234567}   several times a second
 *     {"type":"result","phase":"download","mbps":93.8,"bytes":123456789,"ms":10000}
 *     then exactly once: {"type":"done","ok":true,"pingMs":..,"jitterMs":..,"downMbps":..,"upMbps":..,"downBytes":..,"upBytes":..,"colo":".."}
 *       or {"type":"done","ok":false,"stopped":true} after Stop, or {"type":"done","ok":false,"error":"<short English text>"}
 *   Speeds are megabits per second rounded by SpeedMath.roundMbps; times are milliseconds rounded by SpeedMath.roundMs.
 *
 * Not run on a phone. The numbers are this phone, its radio and its network to the nearest Cloudflare data centre, not a line's contract speed.
 */
public final class SpeedTest {
    private SpeedTest() {}

    public static final String BASE = "https://speed.cloudflare.com";
    public static final int MAX_ID_CHARS = 40;
    /** The most threads one test uses at once: its clock and the streams of a phase. */
    public static final int MAX_THREADS = 1 + 6;

    public interface Sink { void emit(String json); }

    /** What the test does; the app uses the defaults, the tests a local server and short phases. */
    public static final class Config {
        public String base = BASE;
        public int pings = 15;
        public int downStreams = 4, upStreams = 3;
        public long phaseMs = 10000, rampMs = 2000;
        public long downCap = 250L * 1000 * 1000, upCap = 100L * 1000 * 1000;
        public long downChunk = 25L * 1000 * 1000, upChunk = 8L * 1000 * 1000;
        public long tickMs = 100, reportMs = 200, windowMs = 1000;
        public int connectMs = 8000, readMs = 10000;
    }

    /** A pool for the tests' threads: up to MAX_THREADS x 2 (a stopping test's threads may still be unwinding when the next one starts), idle ones end. */
    public static ExecutorService newPool() {
        ThreadPoolExecutor p = new ThreadPoolExecutor(0, MAX_THREADS * 2, 15, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "speedtest-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        return p;
    }

    static boolean validId(String id) {
        return id != null && id.length() >= 1 && id.length() <= MAX_ID_CHARS && id.matches("[A-Za-z0-9_-]+");
    }

    static String error(String text) {
        try { return new JSONObject().put("ok", false).put("error", text).toString(); } catch (Exception e) { return "{\"ok\":false}"; }
    }

    /** Starts, stops and owns the one running test. */
    public static final class Runner {
        private final Config cfg;
        private Run current;
        private boolean closed;

        public Runner(Config cfg) { this.cfg = cfg == null ? new Config() : cfg; }

        /** The most bytes one test can move (both caps): what the page's mobile-data warning names. */
        public long maxBytes() { return Math.max(0, cfg.downCap) + Math.max(0, cfg.upCap); }

        public synchronized String start(String requestJson, ExecutorService exec, Sink sink) {
            if (closed) return error("stopped");
            String id;
            try {
                if (requestJson == null || requestJson.length() > 1000) return error("bad request");
                id = new JSONObject(requestJson).optString("id", "");
            } catch (Exception e) {
                return error("bad request");
            }
            if (!validId(id)) return error("bad id");
            if (current != null) current.stop();
            Run run = new Run(id, cfg, exec, sink);
            try {
                exec.execute(run);
            } catch (RejectedExecutionException e) {
                return error("busy");
            }
            current = run;
            try { return new JSONObject().put("ok", true).put("id", id).toString(); } catch (Exception e) { return "{\"ok\":true}"; }
        }

        public synchronized void cancel(String id) {
            if (current != null && current.id.equals(id)) current.stop();
        }

        /** Stops the running test and refuses new ones (the activity is going). */
        public synchronized void shutdown() {
            closed = true;
            if (current != null) current.stop();
        }

        synchronized boolean running() { return current != null && !current.finished.get(); }
    }

    /** Thrown inside a test when it was stopped; ends it with {"done","stopped"}. */
    static final class Stopped extends Exception {
        Stopped() { super("stopped"); }
    }

    static final class Run implements Runnable {
        final String id;
        final Config cfg;
        final ExecutorService exec;
        final Sink sink;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Set<HttpURLConnection> open = java.util.Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());
        volatile String colo = "";
        private final byte[] noise = new byte[64 * 1024];

        Run(String id, Config cfg, ExecutorService exec, Sink sink) {
            this.id = id; this.cfg = cfg; this.exec = exec; this.sink = sink;
            new SecureRandom().nextBytes(noise);
        }

        void stop() {
            stopped.set(true);
            closeAll();
        }

        private void closeAll() {
            for (HttpURLConnection c : open.toArray(new HttpURLConnection[0])) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }

        void emit(JSONObject o) {
            try {
                o.put("id", id);
                sink.emit(o.toString());
            } catch (Throwable ignored) {}
        }

        private void check() throws Stopped { if (stopped.get()) throw new Stopped(); }

        @Override
        public void run() {
            JSONObject done = new JSONObject();
            try {
                double[] lat = latency();
                long[] down = transfer(true);
                check();
                long[] up = transfer(false);
                check();
                done.put("type", "done").put("ok", true)
                        .put("pingMs", SpeedMath.roundMs(lat[0])).put("jitterMs", Double.isNaN(lat[1]) ? 0 : SpeedMath.roundMs(lat[1]))
                        .put("downMbps", SpeedMath.roundMbps(Double.longBitsToDouble(down[0]))).put("downBytes", down[1])
                        .put("upMbps", SpeedMath.roundMbps(Double.longBitsToDouble(up[0]))).put("upBytes", up[1])
                        .put("colo", colo);
            } catch (Stopped s) {
                done = new JSONObject();
                try { done.put("type", "done").put("ok", false).put("stopped", true); } catch (Exception ignored) {}
            } catch (Throwable t) {
                done = new JSONObject();
                boolean wasStopped = stopped.get();
                try {
                    done.put("type", "done").put("ok", false);
                    if (wasStopped) done.put("stopped", true); else done.put("error", t instanceof TestError ? t.getMessage() : "the test failed");
                } catch (Exception ignored) {}
            } finally {
                closeAll();
                finished.set(true);
            }
            emit(done);
        }

        // ------------------------------------------------------------------------------------------------- latency

        /** {median, jitter} in ms. */
        private double[] latency() throws Exception {
            emit(new JSONObject().put("type", "phase").put("phase", "latency"));
            List<Double> samples = new ArrayList<Double>();
            int failed = 0;
            boolean warmed = false;
            while (samples.size() < cfg.pings) {                         // ends: every failure counts towards the limit below
                check();
                double ms;
                try {
                    ms = oneTinyRequest();
                } catch (IOException e) {
                    check();
                    failed++;
                    if (failed > cfg.pings / 2) throw new TestError("no answer from the test server");
                    continue;
                }
                if (!warmed) { warmed = true; continue; }                // the warm-up (the first answer, not the first try): it also opened the connection
                samples.add(ms);
                double[] a = toArray(samples);
                emit(new JSONObject().put("type", "ping").put("ms", SpeedMath.roundMs(ms)).put("n", samples.size()).put("of", cfg.pings)
                        .put("medianMs", SpeedMath.roundMs(SpeedMath.median(a))));
            }
            if (samples.isEmpty()) throw new TestError("no answer from the test server");
            double[] a = toArray(samples);
            double med = SpeedMath.median(a), jit = SpeedMath.jitter(a);
            emit(new JSONObject().put("type", "latency").put("pingMs", SpeedMath.roundMs(med)).put("jitterMs", Double.isNaN(jit) ? 0 : SpeedMath.roundMs(jit))
                    .put("samples", a.length));
            return new double[]{med, jit};
        }

        private double oneTinyRequest() throws IOException {
            long t0 = System.nanoTime();
            HttpURLConnection c = HttpSafe.open(cfg.base + "/__down?bytes=0", cfg.connectMs, cfg.readMs, null);
            open.add(c);
            boolean clean = false;
            try {
                long t1 = System.nanoTime();
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                noteColo(c);
                double serverMs = SpeedMath.serverMs(serverTiming(c));
                drain(c.getInputStream(), -1);
                clean = true;
                double ms = (t1 - t0) / 1e6;
                return serverMs > 0 ? Math.max(0.01, ms - serverMs) : ms;   // the server's own work is not the line's latency (as Cloudflare's client counts it)
            } finally {
                open.remove(c);
                if (!clean || stopped.get()) { try { c.disconnect(); } catch (Throwable ignored) {} }   // an error answer or a failed read: close the socket, it cannot be reused
            }
        }

        /** Every Server-Timing header's value: Cloudflare sends two (cfSpeed* and cfL4), and getHeaderField would return only the last one. */
        private static List<String> serverTiming(HttpURLConnection c) {
            List<String> out = new ArrayList<String>();
            Map<String, List<String>> h = c.getHeaderFields();
            if (h == null) return out;
            for (Map.Entry<String, List<String>> e : h.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("server-timing") && e.getValue() != null) out.addAll(e.getValue());
            }
            return out;
        }

        private void noteColo(HttpURLConnection c) {
            if (!colo.isEmpty()) return;
            String found = SpeedMath.colo(c.getHeaderField("colo"), c.getHeaderField("cf-meta-colo"), c.getHeaderField("cf-ray"));
            if (found.isEmpty()) return;
            synchronized (this) {
                if (!colo.isEmpty()) return;
                colo = found;
            }
            try { emit(new JSONObject().put("type", "server").put("colo", found)); } catch (Exception ignored) {}
        }

        // ------------------------------------------------------------------------------------------------- download / upload

        /** {Double bits of the result in Mbps (raw), bytes}. */
        private long[] transfer(final boolean down) throws Exception {
            final String phase = down ? "download" : "upload";
            emit(new JSONObject().put("type", "phase").put("phase", phase).put("phaseMs", cfg.phaseMs).put("rampMs", cfg.rampMs));
            final AtomicLong total = new AtomicLong();
            final AtomicBoolean phaseStop = new AtomicBoolean(false);
            final AtomicInteger alive = new AtomicInteger();
            final long cap = down ? cfg.downCap : cfg.upCap;
            final String[] firstError = new String[1];
            int streams = Math.max(1, Math.min(MAX_THREADS - 1, down ? cfg.downStreams : cfg.upStreams));
            List<Future<?>> workers = new ArrayList<Future<?>>();
            SpeedMath.Meter meter = new SpeedMath.Meter();
            final long start = System.nanoTime();
            meter.add(0, 0);
            try {
                for (int s = 0; s < streams; s++) {
                    alive.incrementAndGet();
                    try {
                        workers.add(exec.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    stream(down, total, phaseStop, cap, firstError);
                                } finally {
                                    alive.decrementAndGet();
                                }
                            }
                        }));
                    } catch (RejectedExecutionException e) {
                        alive.decrementAndGet();
                    }
                }
                if (workers.isEmpty()) throw new TestError("could not start the " + phase);
                long lastReport = 0;
                while (true) {
                    check();
                    try { Thread.sleep(cfg.tickMs); } catch (InterruptedException e) { stop(); throw new Stopped(); }
                    check();
                    long t = (System.nanoTime() - start) / 1000000L;
                    long bytes = total.get();
                    meter.add(t, bytes);
                    boolean end = t >= cfg.phaseMs || bytes >= cap || alive.get() == 0;
                    if (t - lastReport >= cfg.reportMs || end) {
                        lastReport = t;
                        emit(new JSONObject().put("type", "progress").put("phase", phase).put("t", t)
                                .put("mbps", SpeedMath.roundMbps(SpeedMath.mbps(meter.currentBps(cfg.windowMs)))).put("bytes", bytes));
                    }
                    if (end) break;
                }
            } finally {
                phaseStop.set(true);
                closeAll();                                                      // a stream in the middle of a chunk stops now, not at its timeout
                long until = System.nanoTime() + 3000L * 1000000L;               // at most 3 s for all of them together (one still connecting ends at its timeout)
                for (Future<?> f : workers) {
                    long left = Math.max(1, (until - System.nanoTime()) / 1000000L);
                    try { f.get(left, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
                }
            }
            check();
            long bytes = total.get();
            if (bytes <= 0) throw new TestError(phase + " failed" + (firstError[0] != null ? ": " + firstError[0] : ""));
            double mbps = SpeedMath.mbps(meter.averageBps(cfg.rampMs));
            emit(new JSONObject().put("type", "result").put("phase", phase).put("mbps", SpeedMath.roundMbps(mbps)).put("bytes", bytes).put("ms", meter.lastTime()));
            return new long[]{Double.doubleToLongBits(mbps), bytes};
        }

        /** One stream: chunk after chunk until the phase ends, the cap is reached or it failed three times in a row. */
        private void stream(boolean down, AtomicLong total, AtomicBoolean phaseStop, long cap, String[] firstError) {
            int failures = 0;
            while (!phaseStop.get() && !stopped.get() && total.get() < cap) {
                try {
                    if (down) downloadChunk(total, phaseStop, cap); else uploadChunk(total, phaseStop, cap);
                    failures = 0;
                } catch (IOException e) {
                    if (phaseStop.get() || stopped.get()) return;
                    synchronized (firstError) { if (firstError[0] == null) firstError[0] = shortReason(e); }
                    if (++failures >= 3) return;
                    try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
                }
            }
        }

        private void downloadChunk(AtomicLong total, AtomicBoolean phaseStop, long cap) throws IOException {
            HttpURLConnection c = HttpSafe.open(cfg.base + "/__down?bytes=" + cfg.downChunk, cfg.connectMs, cfg.readMs, null);
            open.add(c);
            boolean aborted = false, clean = false;
            try {
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                noteColo(c);
                InputStream in = c.getInputStream();
                byte[] buf = new byte[64 * 1024];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n > 0) total.addAndGet(n);
                        if (phaseStop.get() || stopped.get() || total.get() >= cap) { aborted = true; break; }
                    }
                } finally {
                    if (!aborted) in.close();
                }
                clean = !aborted;
            } finally {
                open.remove(c);
                if (!clean) c.disconnect();                          // the rest of the chunk is not wanted, or the answer was an error: drop the connection instead of leaving it open
            }
        }

        private void uploadChunk(final AtomicLong total, final AtomicBoolean phaseStop, final long cap) throws IOException {
            final long len = cfg.upChunk;
            final HttpURLConnection[] held = new HttpURLConnection[1];
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Content-Type", "application/octet-stream");
            HttpURLConnection c;
            try {
                c = HttpSafe.post(cfg.base + "/__up", cfg.connectMs, cfg.readMs, h, len, new HttpSafe.Body() {
                @Override
                public void opened(HttpURLConnection conn) {
                    held[0] = conn;
                    open.add(conn);                                  // Stop can close it while it writes (a stalled write has no timeout of its own); writeTo checks Stop before its first write
                }
                @Override
                public void writeTo(OutputStream out) throws IOException {
                    long left = len;
                    while (left > 0) {
                        if (phaseStop.get() || stopped.get() || total.get() >= cap) throw new IOException("phase over");
                        int n = (int) Math.min(noise.length, left);
                        out.write(noise, 0, n);
                        total.addAndGet(n);
                        left -= n;
                    }
                }
                });
            } catch (IOException e) {
                if (held[0] != null) { open.remove(held[0]); try { held[0].disconnect(); } catch (Throwable ignored) {} }
                throw e;
            }
            boolean clean = false;
            try {
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                noteColo(c);
                drain(c.getInputStream(), 64 * 1024);
                clean = true;
            } finally {
                open.remove(c);
                if (!clean) { try { c.disconnect(); } catch (Throwable ignored) {} }   // an error answer or a failed read: close the socket instead of leaving it open
            }
        }
    }

    /** The test's own failures: their message is the short English text the page shows. */
    static final class TestError extends Exception {
        TestError(String m) { super(m); }
    }

    static String shortReason(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) return "no answer in time";
        if (e instanceof java.net.UnknownHostException) return "no internet connection";
        if (e instanceof java.net.ConnectException) return "could not connect";
        if (e instanceof javax.net.ssl.SSLException) return "secure connection failed";
        String m = e.getMessage();
        if (m != null && m.startsWith("HTTP ")) return "the server answered " + m.substring(5);
        return "connection lost";
    }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** Reads an answer to its end (at most `max` bytes when max >= 0) and closes it, so the connection can be kept alive. */
    private static void drain(InputStream in, long max) throws IOException {
        try {
            byte[] buf = new byte[8192];
            long read = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                read += n;
                if (max >= 0 && read > max) break;
            }
        } finally {
            in.close();
        }
    }
}
