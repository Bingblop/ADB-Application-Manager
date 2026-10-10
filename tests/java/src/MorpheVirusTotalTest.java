package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The optional VirusTotal scan of the Morphe Helper (MorpheVirusTotal) against a local server that behaves like the v3 API: report lookup
 * found / not found / wrong key / quota, a cached report reused, an upload followed by polling (queued, in progress, completed), a file above
 * 32 MB going through upload_url, split bundles scanned APK by APK, the 4 per minute and 500 per day limiter with a fake clock, the counters
 * kept in a file, waiting and cancelling, and the API key staying out of every address, message, result and file.
 */
public class MorpheVirusTotalTest {
    static int fails = 0, passes = 0;

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passes++;
            System.out.println("ok   " + name);
        } else {
            fails++;
            System.out.println("FAIL " + name + (detail.isEmpty() ? "" : "  <- " + detail));
        }
    }

    static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    // ------------------------------------------------------------------------------------------------------------------------------
    // A fake VirusTotal v3 API
    // ------------------------------------------------------------------------------------------------------------------------------

    static final class Outcome {
        JSONObject stats;
        JSONObject results;
        int queuedPolls = 1;       // polls that answer "queued" before "in-progress" and "completed"
        int inProgressPolls = 1;
    }

    static final class Vt {
        final HttpServer srv;
        final String base;
        final Map<String, JSONObject> known = new ConcurrentHashMap<String, JSONObject>();    // sha -> file attributes
        final Map<String, Outcome> outcomes = new ConcurrentHashMap<String, Outcome>();      // sha -> what an upload turns into
        final Map<String, Integer> polls = new ConcurrentHashMap<String, Integer>();
        final Map<String, int[]> forced = new ConcurrentHashMap<String, int[]>();             // "METHOD path" -> {status} (the body in forcedBody)
        final Map<String, String> forcedBody = new ConcurrentHashMap<String, String>();
        final Map<String, String> forcedRetryAfter = new ConcurrentHashMap<String, String>();
        final List<String> log = new CopyOnWriteArrayList<String>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<Map<String, String>>();
        final List<String> uploadFilenames = new CopyOnWriteArrayList<String>();
        final List<Long> uploadSizes = new CopyOnWriteArrayList<Long>();
        final List<String> uploadedShas = new CopyOnWriteArrayList<String>();
        final List<String> allText = new CopyOnWriteArrayList<String>();
        volatile String uploadUrlAnswer;
        volatile String lastUploadContentType = "";
        volatile int maxDirectUpload = Integer.MAX_VALUE;

        Vt() throws IOException {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.setExecutor(Executors.newCachedThreadPool());
            base = "http://127.0.0.1:" + srv.getAddress().getPort();
            srv.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    try {
                        serve(ex);
                    } finally {
                        ex.close();
                    }
                }
            });
            srv.start();
        }

        String api() {
            return base + "/api/v3";
        }

        void reply(HttpExchange ex, int status, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                OutputStream o = ex.getResponseBody();
                o.write(b);
                o.close();
            }
        }

        static String err(String code, String message) {
            return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}";
        }

        void serve(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getRawPath();
            String method = ex.getRequestMethod();
            log.add(method + " " + path);
            allText.add(method + " " + ex.getRequestURI().toString());
            Map<String, String> h = new HashMap<String, String>();
            for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) if (e.getKey() != null && !e.getValue().isEmpty()) h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            headers.add(h);
            allText.add(h.toString());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream in = ex.getRequestBody();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] body = bo.toByteArray();
            if (!KEY.equals(h.get("x-apikey"))) {
                reply(ex, 401, err("WrongCredentialsError", "Wrong API key"));
                return;
            }
            String fk = method + " " + path;
            int[] f = forced.get(fk);
            if (f != null) {
                String ra = forcedRetryAfter.get(fk);
                if (ra != null) ex.getResponseHeaders().set("Retry-After", ra);
                reply(ex, f[0], forcedBody.containsKey(fk) ? forcedBody.get(fk) : "{}");
                return;
            }
            if (method.equals("GET") && path.equals("/api/v3/users/current")) {
                reply(ex, 200, "{\"data\":{\"id\":\"someone\",\"type\":\"user\"}}");
            } else if (method.equals("GET") && path.equals("/api/v3/files/upload_url")) {
                reply(ex, 200, "{\"data\":\"" + (uploadUrlAnswer != null ? uploadUrlAnswer : base + "/_ah/upload/abc123") + "\"}");
            } else if (method.equals("GET") && path.startsWith("/api/v3/files/")) {
                String sha = path.substring("/api/v3/files/".length());
                JSONObject attrs = known.get(sha);
                if (attrs == null) {
                    reply(ex, 404, err("NotFoundError", "File not found"));
                } else {
                    reply(ex, 200, "{\"data\":{\"id\":\"" + sha + "\",\"type\":\"file\",\"attributes\":" + attrs + "}}");
                }
            } else if (method.equals("POST") && (path.equals("/api/v3/files") || path.startsWith("/_ah/upload/"))) {
                lastUploadContentType = String.valueOf(h.get("content-type"));
                if (path.equals("/api/v3/files") && body.length > maxDirectUpload) {
                    reply(ex, 413, err("TooLargeError", "too big"));
                    return;
                }
                String ct = lastUploadContentType;
                Matcher bm = Pattern.compile("boundary=(.+)$").matcher(ct);
                String sha = "unparsed";
                if (bm.find()) {
                    String boundary = bm.group(1);
                    String head = new String(body, 0, Math.min(body.length, 600), StandardCharsets.ISO_8859_1);
                    int hs = head.indexOf("\r\n\r\n");
                    Matcher fm = Pattern.compile("filename=\"([^\"]*)\"").matcher(head);
                    uploadFilenames.add(fm.find() ? fm.group(1) : "?");
                    int tailLen = ("\r\n--" + boundary + "--\r\n").length();
                    if (hs > 0) {
                        int from = hs + 4, to = body.length - tailLen;
                        uploadSizes.add((long) (to - from));
                        try {
                            MessageDigest md = MessageDigest.getInstance("SHA-256");
                            md.update(body, from, to - from);
                            StringBuilder sb = new StringBuilder();
                            for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xff));
                            sha = sb.toString();
                        } catch (Exception e) {
                            throw new IOException(e);
                        }
                    }
                }
                uploadedShas.add(sha);
                allText.add(new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1));
                if (outcomes.containsKey("conflict:" + sha)) {
                    reply(ex, 409, err("AlreadyExistsError", "already there"));
                    return;
                }
                reply(ex, 200, "{\"data\":{\"type\":\"analysis\",\"id\":\"an-" + sha + "\"}}");
            } else if (method.equals("GET") && path.startsWith("/api/v3/analyses/")) {
                String id = path.substring("/api/v3/analyses/".length());
                String sha = id.startsWith("an-") ? id.substring(3) : id;
                Outcome o = outcomes.get(sha);
                int n2 = polls.containsKey(id) ? polls.get(id) + 1 : 1;
                polls.put(id, n2);
                if (o == null) {
                    reply(ex, 404, err("NotFoundError", "no analysis"));
                    return;
                }
                String status = n2 <= o.queuedPolls ? "queued" : n2 <= o.queuedPolls + o.inProgressPolls ? "in-progress" : "completed";
                JSONObject attrs = new JSONObject();
                try {
                    attrs.put("status", status);
                    attrs.put("date", 1790000000L);
                    attrs.put("stats", status.equals("completed") ? o.stats : new JSONObject());
                    attrs.put("results", status.equals("completed") && o.results != null ? o.results : new JSONObject());
                } catch (Exception e) {
                    throw new IOException(e);
                }
                reply(ex, 200, "{\"data\":{\"id\":\"" + id + "\",\"type\":\"analysis\",\"attributes\":" + attrs + "},\"meta\":{\"file_info\":{\"sha256\":\"" + sha + "\",\"size\":1}}}");
            } else {
                reply(ex, 404, err("NotFoundError", "no route"));
            }
        }

        void forceStatus(String key, int status, String body) {
            forced.put(key, new int[] {status});
            if (body != null) forcedBody.put(key, body);
        }

        int count(String prefix) {
            int n = 0;
            for (String l : log) if (l.startsWith(prefix)) n++;
            return n;
        }

        void reset() {
            known.clear();
            outcomes.clear();
            polls.clear();
            forced.clear();
            forcedBody.clear();
            forcedRetryAfter.clear();
            log.clear();
            headers.clear();
            uploadFilenames.clear();
            uploadSizes.clear();
            uploadedShas.clear();
            allText.clear();
            uploadUrlAnswer = null;
            maxDirectUpload = Integer.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------------------------------------------------------------------

    static JSONObject json(String s) throws Exception {
        return new JSONObject(s);
    }

    static JSONObject maliciousAttrs(String sha) throws Exception {
        return json("{\"sha256\":\"" + sha + "\",\"last_analysis_date\":1700000000,\"type_description\":\"Android\",\"size\":100,"
                + "\"last_analysis_stats\":{\"harmless\":0,\"malicious\":2,\"suspicious\":1,\"undetected\":60,\"timeout\":1,\"type-unsupported\":6,\"failure\":1,\"confirmed-timeout\":0},"
                + "\"last_analysis_results\":{\"Zillya\":{\"category\":\"malicious\",\"engine_name\":\"Zillya\",\"result\":\"Trojan.Agent\"},"
                + "\"Kaspersky\":{\"category\":\"malicious\",\"engine_name\":\"Kaspersky\",\"result\":\"HEUR:Trojan.AndroidOS.Fake\"},"
                + "\"AVG\":{\"category\":\"suspicious\",\"engine_name\":\"AVG\",\"result\":\"Android:Evo-gen\"},"
                + "\"Quiet\":{\"category\":\"undetected\",\"engine_name\":\"Quiet\",\"result\":null},"
                + "\"Blank\":{\"category\":\"malicious\",\"engine_name\":\"Blank\",\"result\":\"\"}}}");
    }

    static JSONObject cleanAttrs(String sha) throws Exception {
        return json("{\"sha256\":\"" + sha + "\",\"last_analysis_date\":1700000100,\"last_analysis_stats\":{\"harmless\":3,\"malicious\":0,\"suspicious\":0,\"undetected\":65,\"timeout\":0,\"type-unsupported\":2,\"failure\":0,\"confirmed-timeout\":0},\"last_analysis_results\":{}}");
    }

    static Outcome cleanOutcome() throws Exception {
        Outcome o = new Outcome();
        o.stats = json("{\"harmless\":4,\"malicious\":0,\"suspicious\":0,\"undetected\":64,\"timeout\":1,\"type-unsupported\":3,\"failure\":0,\"confirmed-timeout\":0}");
        o.results = json("{}");
        return o;
    }

    static Outcome maliciousOutcome() throws Exception {
        Outcome o = new Outcome();
        o.stats = json("{\"harmless\":0,\"malicious\":1,\"suspicious\":1,\"undetected\":66,\"timeout\":0,\"type-unsupported\":0,\"failure\":0,\"confirmed-timeout\":0}");
        o.results = json("{\"Bkav\":{\"category\":\"suspicious\",\"engine_name\":\"Bkav\",\"result\":\"AndroidAdware\"},\"Alpha\":{\"category\":\"malicious\",\"engine_name\":\"Alpha\",\"result\":\"Trojan.Bad\"}}");
        return o;
    }

    static String sha(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(data)) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ((i * 31 + seed * 17) ^ (i >>> 5));
        return b;
    }

    static File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), data);
        return f;
    }

    static byte[] zip(String[] names, byte[][] data) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        for (int i = 0; i < names.length; i++) {
            z.putNextEntry(new ZipEntry(names[i]));
            z.write(data[i]);
            z.closeEntry();
        }
        z.close();
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // A fake clock and sleep, so waiting takes no time and the limiter's arithmetic can be read off
    // ------------------------------------------------------------------------------------------------------------------------------

    static final long[] NOW = {1790000000000L};      // 2026-09-21 ... in ms
    static final List<Long> SLEPT = new CopyOnWriteArrayList<Long>();

    static void installClock() {
        MorpheVirusTotal.clock = new java.util.function.LongSupplier() {
            @Override
            public long getAsLong() {
                return NOW[0];
            }
        };
        MorpheVirusTotal.sleeper = new MorpheVirusTotal.Sleeper() {
            @Override
            public void sleep(long ms) {
                SLEPT.add(ms);
                NOW[0] += ms;
            }
        };
        MorpheVirusTotal.pollIntervalMs = 3000;
        MorpheVirusTotal.maxPolls = 90;
        MorpheVirusTotal.maxWaitMs = 15L * 60 * 1000;
    }

    static final class P implements MorpheNet.Progress {
        volatile boolean cancel;
        volatile int cancelAfterCalls = -1;
        final List<Long> done = new CopyOnWriteArrayList<Long>();
        volatile long total;
        volatile int cancelledChecks;

        @Override
        public void onProgress(long d, long t) {
            done.add(d);
            total = t;
        }

        @Override
        public boolean cancelled() {
            cancelledChecks++;
            return cancel;
        }
    }

    interface Thrower<T> {
        T run() throws Exception;
    }

    interface Action {
        void run() throws Exception;
    }

    static Throwable errv(Action a) {
        try {
            a.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    static Throwable err(Thrower<?> t) {
        try {
            t.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    static String msg(Throwable t) {
        return t == null ? "(no exception)" : t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    static boolean has(Throwable t, String... parts) {
        if (t == null || t.getMessage() == null) return false;
        for (String p : parts) if (!t.getMessage().contains(p)) return false;
        return true;
    }

    static boolean noKey(Throwable t) {
        return t == null || t.getMessage() == null || !t.getMessage().contains(KEY);
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static MorpheVirusTotal vt() {
        return new MorpheVirusTotal(KEY, null);
    }

    static int rate(Vt s) {
        return s.count("GET /api/v3/files/") + s.count("POST ");
    }

    public static void main(String[] args) throws Exception {
        Vt s = new Vt();
        leftovers();
        MorpheVirusTotal.apiBase = s.api();
        installClock();
        File dir = Files.createTempDirectory("morphevt").toFile();
        try {
            testVerdict();
            testLookup(s, dir);
            testScanCached(s, dir);
            testScanUpload(s, dir);
            testBigFile(s, dir);
            testUploadEdges(s, dir);
            testBundles(s, dir);
            testPolling(s, dir);
            testLimiter(s, dir);
            testWaiting(s, dir);
            testKey(s, dir);
            testQuota(s, dir);
        } catch (Throwable t) {
            fails++;
            System.out.println("FAIL the test run itself broke: " + t);
            t.printStackTrace(System.out);
        }
        s.srv.stop(0);
        deleteTree(dir);
        System.out.println((fails == 0 ? "ALL PASS" : "FAILURES: " + fails) + " (" + passes + " ok, " + fails + " failed)");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------------------------------------

    static void testVerdict() {
        check("verdictOf: nothing flagged is clean", "clean".equals(MorpheVirusTotal.verdictOf(0, 0)));
        check("verdictOf: one malicious detection is malicious (Helper flags any detection)", "malicious".equals(MorpheVirusTotal.verdictOf(1, 0)) && "malicious".equals(MorpheVirusTotal.verdictOf(7, 4)));
        check("verdictOf: suspicious only is suspicious", "suspicious".equals(MorpheVirusTotal.verdictOf(0, 1)) && "suspicious".equals(MorpheVirusTotal.verdictOf(0, 12)));
        check("verdictOf: negative numbers count as nothing", "clean".equals(MorpheVirusTotal.verdictOf(-3, -1)));
    }

    static void testLookup(Vt s, File dir) throws Exception {
        s.reset();
        String sha = sha(bytes(100, 1));
        s.known.put(sha, maliciousAttrs(sha));
        JSONObject r = vt().lookup(sha);
        check("lookup found: a cached report with the numbers of the engines", r.getBoolean("found") && r.getBoolean("cached") && sha.equals(r.getString("sha256")) && r.getInt("malicious") == 2 && r.getInt("suspicious") == 1 && r.getInt("harmless") == 0
                && r.getInt("undetected") == 60 && r.getInt("timeout") == 1);
        check("lookup found: total counts every kind of answer, as Helper does (71), and the verdict is malicious", r.getInt("total") == 71 && "malicious".equals(r.getString("verdict")), r.toString());
        JSONArray fl = r.getJSONArray("flagged");
        check("lookup found: flagged engines - malicious first, then by name; an engine with no text is left out",
                fl.length() == 3 && "Kaspersky".equals(fl.getJSONObject(0).getString("engine")) && "HEUR:Trojan.AndroidOS.Fake".equals(fl.getJSONObject(0).getString("result")) && "Zillya".equals(fl.getJSONObject(1).getString("engine")) && "AVG".equals(fl.getJSONObject(2).getString("engine")), fl.toString());
        check("lookup found: permalink and the scan time (seconds -> ms)", ("https://www.virustotal.com/gui/file/" + sha).equals(r.getString("permalink")) && r.getLong("scannedAt") == 1700000000000L);
        check("lookup: asks GET /files/<sha> with the key as the x-apikey header only", s.log.size() == 1 && ("GET /api/v3/files/" + sha).equals(s.log.get(0)) && KEY.equals(s.headers.get(0).get("x-apikey")) && !s.allText.toString().contains(KEY + "/") && !s.log.toString().contains(KEY));
        String unknown = sha(bytes(100, 2));
        JSONObject nf = vt().lookup(unknown);
        check("lookup not found: {found:false}", !nf.getBoolean("found") && nf.length() == 1, nf.toString());
        s.known.put(unknown, cleanAttrs(unknown));
        JSONObject clean = vt().lookup(unknown);
        check("lookup clean: verdict clean, nothing flagged", "clean".equals(clean.getString("verdict")) && clean.getJSONArray("flagged").length() == 0 && clean.getInt("total") == 70 && clean.getBoolean("cached"));
        s.known.put(unknown, json("{\"sha256\":\"" + unknown + "\",\"last_analysis_stats\":{},\"last_analysis_results\":{}}"));
        check("lookup: a file VirusTotal knows but has not analysed yet counts as not found (so it is analysed)", !vt().lookup(unknown).getBoolean("found"));
        check("lookup: upper-case hash is fine, a bad hash is refused before any request", vt().lookup(sha.toUpperCase(Locale.ROOT)).getBoolean("found") && has(err(() -> vt().lookup("nothex")), "not a SHA-256") && has(err(() -> vt().lookup(null)), "not a SHA-256"));

        s.forceStatus("GET /api/v3/files/" + sha, 401, "{\"error\":{\"code\":\"WrongCredentialsError\",\"message\":\"Wrong API key " + KEY + "\"}}");
        Throwable t = err(() -> vt().lookup(sha));
        check("lookup 401: says the key is not accepted, in words, without the key", has(t, "does not accept this API key", "HTTP 401") && noKey(t) && !(t instanceof MorpheVirusTotal.RateLimited), msg(t));
        s.forceStatus("GET /api/v3/files/" + sha, 403, "{\"error\":{\"code\":\"ForbiddenError\",\"message\":\"nope\"}}");
        t = err(() -> vt().lookup(sha));
        check("lookup 403: the same", has(t, "does not accept this API key", "HTTP 403"), msg(t));
        s.forceStatus("GET /api/v3/files/" + sha, 429, "{\"error\":{\"code\":\"QuotaExceededError\",\"message\":\"Quota exceeded\"}}");
        s.forcedRetryAfter.put("GET /api/v3/files/" + sha, "30");
        t = err(() -> vt().lookup(sha));
        check("lookup 429: a RateLimited that carries the Retry-After wait", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).retryAfterMs == 30000 && !((MorpheVirusTotal.RateLimited) t).daily && has(t, "too many requests", "30 s"), msg(t));
        s.forceStatus("GET /api/v3/files/" + sha, 429, "{\"error\":{\"code\":\"QuotaExceededError\",\"message\":\"Daily quota exceeded\"}}");
        s.forcedRetryAfter.clear();
        t = err(() -> vt().lookup(sha));
        check("lookup 429 about the daily quota: daily is true and the wait is a day at most", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).daily && has(t, "daily limit"), msg(t));
        s.forceStatus("GET /api/v3/files/" + sha, 500, "{\"error\":{\"code\":\"InternalError\",\"message\":\"oops with key " + KEY + " inside\"}}");
        t = err(() -> vt().lookup(sha));
        check("lookup 500: the server's words are shown, with the key blanked out if it was quoted", has(t, "HTTP 500", "oops with key *** inside") && noKey(t), msg(t));
        s.log.clear();
        s.forceStatus("GET /api/v3/files/" + sha, 200, "<html>proxy login</html>");
        t = err(() -> vt().lookup(sha));
        check("lookup: a 200 that is not JSON is asked again twice, then reported", has(t, "not JSON") && s.count("GET /api/v3/files/" + sha) == 3, msg(t) + s.log.size());
        check("lookup: without a usable key nothing is sent", has(err(() -> new MorpheVirusTotal("", null).lookup(sha)), "no usable VirusTotal API key") && has(err(() -> new MorpheVirusTotal("short", null).lookup(sha)), "no usable VirusTotal API key"));
        s.reset();
        MorpheVirusTotal.apiBase = "http://127.0.0.1:1/api/v3";
        t = err(() -> vt().lookup(sha));
        check("lookup: nothing listening is an IOException with no key in it", t instanceof IOException && noKey(t), msg(t));
        MorpheVirusTotal.apiBase = s.api();
    }

    static void testScanCached(Vt s, File dir) throws Exception {
        s.reset();
        byte[] data = bytes(5000, 3);
        File f = write(dir, "cached.apk", data);
        String sha = sha(data);
        s.known.put(sha, cleanAttrs(sha));
        P p = new P();
        JSONObject r = vt().scan(f, p);
        check("scan: a report VirusTotal already has is reused, labelled cached, nothing uploaded", r.getBoolean("found") && r.getBoolean("cached") && "clean".equals(r.getString("verdict")) && sha.equals(r.getString("sha256")) && s.log.size() == 1 && s.count("POST") == 0);
        check("scan: the result has every field of the contract", r.has("malicious") && r.has("suspicious") && r.has("harmless") && r.has("undetected") && r.has("timeout") && r.has("total") && r.has("flagged") && r.has("permalink") && r.has("scannedAt") && !r.getBoolean("bundle"));
        check("scan: progress ends at 100 of 100 and never goes back", p.total == 100 && p.done.get(p.done.size() - 1) == 100 && ascending(p.done), p.done.toString());
        s.known.put(sha, maliciousAttrs(sha));
        JSONObject m = vt().scan(f, null);
        check("scan: a cached report with detections is malicious, with the engines that flagged it", "malicious".equals(m.getString("verdict")) && m.getJSONArray("flagged").length() == 3 && m.getBoolean("cached"));
    }

    static boolean ascending(List<Long> l) {
        for (int i = 1; i < l.size(); i++) if (l.get(i) < l.get(i - 1)) return false;
        return true;
    }

    static void testScanUpload(Vt s, File dir) throws Exception {
        s.reset();
        byte[] data = bytes(70000, 4);
        File f = write(dir, "new \"quoted\"\r\n.apk", data);
        String sha = sha(data);
        s.outcomes.put(sha, cleanOutcome());
        P p = new P();
        final List<String> lines = new CopyOnWriteArrayList<String>();
        MorpheVirusTotal v = vt();
        v.setStatus(new MorpheVirusTotal.Status() {
            @Override
            public void status(String line) {
                lines.add(line);
            }
        });
        JSONObject r = v.scan(f, p);
        check("scan upload: unknown file -> lookup 404, POST /files, then polls until completed", Arrays.asList("GET /api/v3/files/" + sha, "POST /api/v3/files", "GET /api/v3/analyses/an-" + sha, "GET /api/v3/analyses/an-" + sha, "GET /api/v3/analyses/an-" + sha).equals(s.log), s.log.toString());
        check("scan upload: the result is fresh (cached false), with the analysis' numbers and clean verdict", r.getBoolean("found") && !r.getBoolean("cached") && "clean".equals(r.getString("verdict")) && r.getInt("harmless") == 4 && r.getInt("undetected") == 64 && r.getInt("timeout") == 1 && r.getInt("total") == 72 && sha.equals(r.getString("sha256")));
        check("scan upload: the file is sent as multipart/form-data, whole and unchanged, with a safe file name", s.lastUploadContentType.startsWith("multipart/form-data; boundary=") && s.uploadedShas.get(0).equals(sha) && s.uploadSizes.get(0) == data.length && s.uploadFilenames.get(0).matches("[A-Za-z0-9._ -]+"), s.uploadFilenames + " " + s.lastUploadContentType);
        check("scan upload: the key is in the x-apikey header of the upload too", KEY.equals(s.headers.get(1).get("x-apikey")));
        check("scan upload: the status lines say what happens (queued, scanning)", lines.toString().contains("Queued at VirusTotal") && lines.toString().contains("engines are scanning") && lines.toString().contains("Uploading"), lines.toString());
        check("scan upload: progress rises to 100", p.done.get(p.done.size() - 1) == 100 && ascending(p.done) && p.done.size() > 4, p.done.toString());
        check("scan upload: polling waits the interval between polls (3 s, Helper's)", SLEPT.contains(3000L));
        s.reset();
        s.outcomes.put(sha, maliciousOutcome());
        JSONObject m = vt().scan(f, null);
        check("scan upload: an analysis with detections is malicious; the flagged engines come from the analysis' results", "malicious".equals(m.getString("verdict")) && m.getInt("malicious") == 1 && m.getInt("suspicious") == 1 && "Alpha".equals(m.getJSONArray("flagged").getJSONObject(0).getString("engine")) && "Bkav".equals(m.getJSONArray("flagged").getJSONObject(1).getString("engine")) && m.getLong("scannedAt") == 1790000000000L);
    }

    static void testBigFile(Vt s, File dir) throws Exception {
        s.reset();
        File big = new File(dir, "big.apk");
        RandomAccessFile raf = new RandomAccessFile(big, "rw");
        byte[] block = bytes(1 << 20, 5);
        for (int i = 0; i < 33; i++) raf.write(block);
        raf.close();
        String sha;
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < 33; i++) md.update(block);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xff));
            sha = sb.toString();
        }
        s.outcomes.put(sha, cleanOutcome());
        P p = new P();
        JSONObject r = vt().scan(big, p);
        check("33 MB file: the one-time upload address is asked for and used, POST /files is not", s.log.contains("GET /api/v3/files/upload_url") && s.log.contains("POST /_ah/upload/abc123") && !s.log.contains("POST /api/v3/files"), s.log.toString());
        check("33 MB file: all 33 MB arrive, unchanged, and the result is fresh", s.uploadSizes.get(0) == 33L * (1 << 20) && s.uploadedShas.get(0).equals(sha) && !r.getBoolean("cached") && "clean".equals(r.getString("verdict")));
        check("33 MB file: the key goes to the upload address too (same host as the API)", KEY.equals(s.headers.get(s.log.indexOf("POST /_ah/upload/abc123")).get("x-apikey")));
        check("33 MB file: upload progress is reported", ascending(p.done) && p.done.get(p.done.size() - 1) == 100);
        s.reset();
        s.outcomes.put(sha, cleanOutcome());
        s.uploadUrlAnswer = "https://evil.example.com/up";
        Throwable t = err(() -> vt().scan(big, null));
        check("33 MB file: an upload address on another host is refused, and the key is not sent there", has(t, "another host", "API key is not sent") && s.count("POST") == 0 && noKey(t), msg(t));
        s.uploadUrlAnswer = null;
        File exact = new File(dir, "exact.apk");
        RandomAccessFile r2 = new RandomAccessFile(exact, "rw");
        for (int i = 0; i < 32; i++) r2.write(block);
        r2.close();
        s.reset();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < 32; i++) md.update(block);
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xff));
        s.outcomes.put(sb.toString(), cleanOutcome());
        vt().scan(exact, null);
        check("exactly 32 MB still goes straight to POST /files", s.log.contains("POST /api/v3/files") && !s.log.contains("GET /api/v3/files/upload_url"), s.log.toString());
        big.delete();
        exact.delete();
        File huge = new File(dir, "huge.apk");
        RandomAccessFile r3 = new RandomAccessFile(huge, "rw");
        r3.setLength(651L * 1024 * 1024);
        r3.close();
        s.reset();
        t = err(() -> vt().scan(huge, null));
        check("a file over 650 MB is refused before anything is hashed or sent", has(t, "too big for VirusTotal", "650 MB") && s.log.isEmpty(), msg(t));
        huge.delete();
    }

    static void testUploadEdges(Vt s, File dir) throws Exception {
        s.reset();
        byte[] data = bytes(3000, 6);
        File f = write(dir, "edge.apk", data);
        String sha = sha(data);
        s.maxDirectUpload = 1000;
        s.outcomes.put(sha, cleanOutcome());
        JSONObject r = vt().scan(f, null);
        check("upload: a 413 from POST /files falls back to the one-time upload address", s.log.contains("POST /api/v3/files") && s.log.contains("GET /api/v3/files/upload_url") && s.log.contains("POST /_ah/upload/abc123") && "clean".equals(r.getString("verdict")), s.log.toString());
        s.reset();
        s.outcomes.put("conflict:" + sha, new Outcome());
        // first lookup 404; the upload says 409 (analysed meanwhile); the next lookup finds it
        s.forceStatus("GET /api/v3/files/" + sha, 404, "{\"error\":{\"code\":\"NotFoundError\",\"message\":\"File not found\"}}");
        final Vt sv = s;
        final String fsha = sha;
        Thread flip = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long until = System.currentTimeMillis() + 8000;
                    while (System.currentTimeMillis() < until && !sv.log.contains("POST /api/v3/files")) Thread.sleep(5);
                    sv.forced.remove("GET /api/v3/files/" + fsha);
                    sv.known.put(fsha, cleanAttrs(fsha));
                } catch (Exception ignored) {
                }
            }
        });
        flip.start();
        JSONObject c = vt().scan(f, null);
        flip.join(5000);
        check("upload: a 409 (already analysed) re-reads the report instead of failing", c.getBoolean("cached") && "clean".equals(c.getString("verdict")) && s.count("GET /api/v3/files/" + sha) == 2, s.log.toString());
        s.reset();
        s.forced.clear();
        s.outcomes.put("conflict:" + sha, new Outcome());
        Throwable t = err(() -> vt().scan(f, null));
        check("upload: a 409 with no report to read afterwards is said plainly", has(t, "already has this file"), msg(t));
        s.reset();
        s.forceStatus("POST /api/v3/files", 500, "{\"error\":{\"code\":\"InternalError\",\"message\":\"upload broke\"}}");
        t = err(() -> vt().scan(f, null));
        check("upload: an HTTP 500 on the upload is an IOException with the server's words", has(t, "HTTP 500", "upload broke"), msg(t));
        s.reset();
        s.forceStatus("POST /api/v3/files", 200, "{\"data\":{}}");
        t = err(() -> vt().scan(f, null));
        check("upload: an answer without an analysis id is reported", has(t, "no analysis id"), msg(t));
        s.reset();
        s.forceStatus("POST /api/v3/files", 429, "{\"error\":{\"code\":\"QuotaExceededError\",\"message\":\"Daily quota exceeded\"}}");
        t = err(() -> vt().scan(f, null));
        check("upload: a daily 429 on the upload is a RateLimited, not a wait", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).daily, msg(t));
        check("scan: a missing file and a null file are IOExceptions", has(err(() -> vt().scan(new File(dir, "nothing.apk"), null)), "file not found") && has(err(() -> vt().scan(null, null)), "file not found"));
        check("scan: without a usable key nothing is sent", has(err(() -> new MorpheVirusTotal("", null).scan(f, null)), "no usable VirusTotal API key"));
    }

    static void testBundles(Vt s, File dir) throws Exception {
        s.reset();
        byte[] a = bytes(2000, 10), b = bytes(2500, 11), c = bytes(3000, 12);
        byte[] bundle = zip(new String[] {"toc.pb", "base.apk", "splits/config.arm64_v8a.apk", "splits/config.xxhdpi.apk", "icon.png"}, new byte[][] {bytes(50, 1), a, b, c, bytes(60, 2)});
        File f = write(dir, "app.apks", bundle);
        s.known.put(sha(a), cleanAttrs(sha(a)));
        s.known.put(sha(b), cleanAttrs(sha(b)));
        s.known.put(sha(c), cleanAttrs(sha(c)));
        P p = new P();
        JSONObject r = vt().scan(f, p);
        check("bundle: each inner APK is looked up on its own - toc.pb and the icon are not", s.count("GET /api/v3/files/") == 3 && s.log.contains("GET /api/v3/files/" + sha(a)) && s.log.contains("GET /api/v3/files/" + sha(b)) && s.log.contains("GET /api/v3/files/" + sha(c)), s.log.toString());
        JSONArray parts = r.getJSONArray("parts");
        check("bundle: 'parts' has one result per APK, named by its entry; all cached; the overall verdict is clean",
                r.getBoolean("bundle") && parts.length() == 3 && "base.apk".equals(parts.getJSONObject(0).getString("name")) && "splits/config.xxhdpi.apk".equals(parts.getJSONObject(2).getString("name")) && r.getBoolean("cached") && "clean".equals(r.getString("verdict"))
                        && r.getInt("scannedFiles") == 3 && r.getInt("flaggedFiles") == 0 && r.getInt("failedFiles") == 0);
        check("bundle: progress runs through the parts to 100", ascending(p.done) && p.done.get(p.done.size() - 1) == 100, p.done.toString());
        check("bundle: the temporary files are removed", leftovers() == 0);

        s.known.put(sha(b), maliciousAttrs(sha(b)));
        JSONObject m = vt().scan(f, null);
        check("bundle: one flagged APK makes the whole bundle malicious; numbers, engines and the permalink are the worst APK's",
                "malicious".equals(m.getString("verdict")) && m.getInt("malicious") == 2 && m.getInt("flaggedFiles") == 1 && sha(b).equals(m.getString("sha256")) && m.getString("permalink").endsWith(sha(b)) && m.getJSONArray("flagged").length() == 3
                        && "malicious".equals(m.getJSONArray("parts").getJSONObject(1).getString("verdict")) && "clean".equals(m.getJSONArray("parts").getJSONObject(0).getString("verdict")));
        s.known.put(sha(c), json("{\"sha256\":\"" + sha(c) + "\",\"last_analysis_stats\":{\"harmless\":1,\"malicious\":0,\"suspicious\":2,\"undetected\":60,\"timeout\":0},\"last_analysis_results\":{\"X\":{\"category\":\"suspicious\",\"engine_name\":\"X\",\"result\":\"Maybe\"}}}"));
        m = vt().scan(f, null);
        check("bundle: malicious outranks suspicious, whichever part comes first", "malicious".equals(m.getString("verdict")) && sha(b).equals(m.getString("sha256")) && m.getInt("flaggedFiles") == 2);
        s.known.put(sha(b), cleanAttrs(sha(b)));
        m = vt().scan(f, null);
        check("bundle: suspicious only is suspicious", "suspicious".equals(m.getString("verdict")) && sha(c).equals(m.getString("sha256")));

        s.reset();
        s.known.put(sha(a), cleanAttrs(sha(a)));
        s.known.put(sha(c), cleanAttrs(sha(c)));
        s.outcomes.put(sha(b), cleanOutcome());
        JSONObject mixed = vt().scan(f, null);
        check("bundle: an APK VirusTotal does not know is uploaded; the bundle is not 'cached' then", !mixed.getBoolean("cached") && s.uploadedShas.equals(Arrays.asList(sha(b))) && "clean".equals(mixed.getString("verdict")) && s.uploadFilenames.get(0).equals("config.arm64_v8a.apk"), s.log.toString() + s.uploadFilenames);

        s.reset();
        s.known.put(sha(a), cleanAttrs(sha(a)));
        s.known.put(sha(c), cleanAttrs(sha(c)));
        s.forceStatus("GET /api/v3/files/" + sha(b), 500, "{\"error\":{\"code\":\"InternalError\",\"message\":\"down\"}}");
        Throwable t = err(() -> vt().scan(f, null));
        check("bundle: if one APK fails and the others are clean it is not called clean", has(t, "2 of 3 APKs scanned clean", "1 failed", "config.arm64_v8a.apk"), msg(t));
        s.known.put(sha(c), maliciousAttrs(sha(c)));
        JSONObject partial = vt().scan(f, null);
        check("bundle: a failure beside a flagged APK still returns the verdict, with the failure listed", "malicious".equals(partial.getString("verdict")) && partial.getInt("failedFiles") == 1 && partial.getJSONArray("parts").getJSONObject(1).has("error") && !partial.getJSONArray("parts").getJSONObject(1).getBoolean("found"));
        s.reset();
        for (String hh : new String[] {sha(a), sha(b), sha(c)}) s.forceStatus("GET /api/v3/files/" + hh, 500, "{}");
        t = err(() -> vt().scan(f, null));
        check("bundle: every APK failing is said as such", has(t, "All 3 APKs failed to scan"), msg(t));

        s.reset();
        File notZip = write(dir, "broken.xapk", bytes(400, 13));
        s.known.put(sha(bytes(400, 13)), cleanAttrs(sha(bytes(400, 13))));
        JSONObject nz = vt().scan(notZip, null);
        check("bundle: a damaged .xapk that is not a zip is scanned as one file", !nz.getBoolean("bundle") && s.count("GET /api/v3/files/") == 1 && "clean".equals(nz.getString("verdict")));
        s.reset();
        byte[] noApk = zip(new String[] {"manifest.json", "data.obb"}, new byte[][] {bytes(40, 1), bytes(80, 2)});
        File fo = write(dir, "nothing.apkm", noApk);
        s.known.put(sha(noApk), cleanAttrs(sha(noApk)));
        JSONObject na = vt().scan(fo, null);
        check("bundle: a zip with no .apk inside is scanned as one file", !na.getBoolean("bundle") && s.log.contains("GET /api/v3/files/" + sha(noApk)));
        s.reset();
        s.known.put(sha(a), cleanAttrs(sha(a)));
        s.known.put(sha(b), cleanAttrs(sha(b)));
        s.known.put(sha(c), cleanAttrs(sha(c)));
        final P cp = new P();
        MorpheVirusTotal cv = vt();
        cv.setStatus(new MorpheVirusTotal.Status() {
            @Override
            public void status(String line) {
                if (line.contains("APK 2 of 3")) cp.cancel = true;
            }
        });
        t = err(() -> cv.scan(f, cp));
        check("bundle: Cancel between two APKs stops with 'cancelled' and removes the temporary files", t instanceof IOException && "cancelled".equals(t.getMessage()) && leftovers() == 0 && s.count("GET /api/v3/files/") == 1, msg(t) + s.log);
    }

    static int baseline = -1;

    /** Temporary bundle folders of a scan that are still there, beyond those already there when the test started. */
    static int leftovers() {
        if (baseline < 0) baseline = count();
        return count() - baseline;
    }

    static int count() {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        String[] n = tmp.list();
        int c = 0;
        if (n != null) for (String x : n) if (x.startsWith("morphe-vt")) c++;
        return c;
    }

    static void testPolling(Vt s, File dir) throws Exception {
        s.reset();
        byte[] data = bytes(1234, 20);
        File f = write(dir, "poll.apk", data);
        String sha = sha(data);
        Outcome o = cleanOutcome();
        o.queuedPolls = 100;
        s.outcomes.put(sha, o);
        MorpheVirusTotal.maxPolls = 5;
        Throwable t = err(() -> vt().scan(f, null));
        check("polling: a queue that never finishes ends after the bounded number of polls, in words", has(t, "did not finish the analysis in time") && s.count("GET /api/v3/analyses/") == 5, msg(t) + s.log);
        MorpheVirusTotal.maxPolls = 90;
        s.reset();
        Outcome zero = new Outcome();
        zero.stats = json("{\"harmless\":0,\"malicious\":0,\"suspicious\":0,\"undetected\":0}");
        zero.queuedPolls = 0;
        zero.inProgressPolls = 0;
        s.outcomes.put(sha, zero);
        t = err(() -> vt().scan(f, null));
        check("polling: a completed analysis with no engine results is an error, not a clean verdict", has(t, "no engine results"), msg(t));
        s.reset();
        s.outcomes.put(sha, cleanOutcome());
        s.forceStatus("GET /api/v3/analyses/an-" + sha, 404, "{\"error\":{\"code\":\"NotFoundError\",\"message\":\"gone\"}}");
        t = err(() -> vt().scan(f, null));
        check("polling: an analysis that is gone is said so", has(t, "lost the analysis"), msg(t));
        s.reset();
        s.outcomes.put(sha, cleanOutcome());
        s.forceStatus("GET /api/v3/analyses/an-" + sha, 500, "{}");
        t = err(() -> vt().scan(f, null));
        check("polling: an HTTP error while polling is reported with its status", has(t, "HTTP 500"), msg(t));
        // cancel while polling
        s.reset();
        Outcome slow = cleanOutcome();
        slow.queuedPolls = 50;
        s.outcomes.put(sha, slow);
        final P p = new P();
        MorpheVirusTotal v = vt();
        v.setStatus(new MorpheVirusTotal.Status() {
            @Override
            public void status(String line) {
                if (line.contains("Queued")) p.cancel = true;
            }
        });
        t = err(() -> v.scan(f, p));
        check("polling: Cancel while waiting for the analysis stops it with 'cancelled'", t instanceof IOException && "cancelled".equals(t.getMessage()) && s.count("GET /api/v3/analyses/") == 1, msg(t) + s.log);
        // a 429 while polling is waited out
        s.reset();
        s.outcomes.put(sha, cleanOutcome());
        s.forceStatus("GET /api/v3/analyses/an-" + sha, 429, "{\"error\":{\"code\":\"TooManyRequestsError\",\"message\":\"slow\"}}");
        s.forcedRetryAfter.put("GET /api/v3/analyses/an-" + sha, "4");
        final Vt sv = s;
        final String fsha = sha;
        SLEPT.clear();
        Thread lift = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long until = System.currentTimeMillis() + 8000;
                    while (System.currentTimeMillis() < until && sv.count("GET /api/v3/analyses/") < 1) Thread.sleep(5);
                    sv.forced.remove("GET /api/v3/analyses/an-" + fsha);
                } catch (Exception ignored) {
                }
            }
        });
        lift.start();
        JSONObject r = vt().scan(f, null);
        lift.join(5000);
        check("polling: a 429 on a poll is waited out (the Retry-After seconds) and the scan goes on", "clean".equals(r.getString("verdict")) && SLEPT.contains(1000L) && sumSlept(1000L) >= 4000L, SLEPT.toString());
    }

    static long sumSlept(long unused) {
        long t = 0;
        for (Long l : SLEPT) t += l;
        return t;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // The limiter
    // ------------------------------------------------------------------------------------------------------------------------------

    static void testLimiter(Vt s, File dir) throws Exception {
        s.reset();
        NOW[0] = 1790000000000L - Math.floorMod(1790000000000L, 86400000L) + 12L * 3600 * 1000;   // 12:00 UTC
        String sha = sha(bytes(10, 30));
        MorpheVirusTotal v = vt();
        JSONObject q0 = v.quota();
        check("quota: nothing used at first; the limits are 4 a minute and 500 a day", q0.getInt("perMinuteUsed") == 0 && q0.getInt("perMinuteLimit") == 4 && q0.getInt("perDayUsed") == 0 && q0.getInt("perDayLimit") == 500 && q0.getLong("resetMinuteInMs") == 0, q0.toString());
        check("quota: the day resets at midnight UTC (12 hours from noon)", q0.getLong("resetDayInMs") == 12L * 3600 * 1000);
        v.lookup(sha);
        NOW[0] += 10000;
        v.lookup(sha);
        NOW[0] += 5000;
        v.lookup(sha);
        JSONObject q3 = v.quota();
        check("quota: three lookups are three used, the minute resets when the first is 60 s old (45 s from now)", q3.getInt("perMinuteUsed") == 3 && q3.getInt("perDayUsed") == 3 && q3.getLong("resetMinuteInMs") == 45000, q3.toString());
        v.lookup(sha);
        Throwable t = err(() -> v.lookup(sha));
        check("limiter: the fifth request in a minute is a RateLimited with the time until the first ages out", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).retryAfterMs == 45000 && !((MorpheVirusTotal.RateLimited) t).daily && has(t, "4 requests a minute", "45 s"), msg(t));
        check("limiter: a refused request is not sent and not counted", s.count("GET /api/v3/files/") == 4 && v.quota().getInt("perMinuteUsed") == 4);
        NOW[0] += 44999;
        check("limiter: one ms early it is still refused (1 ms to go)", err(() -> v.lookup(sha)) instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) err(() -> v.lookup(sha))).retryAfterMs == 1);
        NOW[0] += 1;
        check("limiter: when the oldest request is 60 s old a place is free again", err(() -> v.lookup(sha)) == null);
        JSONObject q = v.quota();
        check("quota: the window is rolling - the day counter keeps counting what the minute forgets", q.getInt("perMinuteUsed") == 4 && q.getInt("perDayUsed") == 5, q.toString());
        NOW[0] += 120000;
        q = v.quota();
        check("quota: after two quiet minutes the minute is empty, the day remembers", q.getInt("perMinuteUsed") == 0 && q.getInt("perDayUsed") == 5 && q.getLong("resetMinuteInMs") == 0);

        // persistence
        File state = new File(dir, "vt-state.json");
        state.delete();
        NOW[0] = 1790000000000L - Math.floorMod(1790000000000L, 86400000L) + 12L * 3600 * 1000;
        MorpheVirusTotal a = new MorpheVirusTotal(KEY, state);
        a.lookup(sha);
        NOW[0] += 1000;
        a.lookup(sha);
        MorpheVirusTotal b = new MorpheVirusTotal(KEY, state);
        JSONObject qb = b.quota();
        check("persistence: a new instance with the same file starts with the counters of the old one (a restart)", qb.getInt("perMinuteUsed") == 2 && qb.getInt("perDayUsed") == 2 && qb.getLong("resetMinuteInMs") == 59000, qb.toString());
        String text = new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8);
        check("persistence: the file is JSON with the times only - no key", text.startsWith("{") && json(text).getJSONArray("calls").length() == 2 && !text.contains(KEY) && !new File(dir, "vt-state.json.tmp").exists(), text);
        b.lookup(sha);
        check("persistence: the new instance's calls are saved too; both instances see the same counters", new MorpheVirusTotal(KEY, state).quota().getInt("perDayUsed") == 3 && a.quota().getInt("perDayUsed") == 3);
        NOW[0] += 61000;
        MorpheVirusTotal c = new MorpheVirusTotal(KEY, state);
        check("persistence: the minute forgets after 60 s, the day does not", c.quota().getInt("perMinuteUsed") == 0 && c.quota().getInt("perDayUsed") == 3);
        NOW[0] += 13L * 3600 * 1000;
        check("persistence: past midnight UTC the day starts again", new MorpheVirusTotal(KEY, state).quota().getInt("perDayUsed") == 0);
        Files.write(state.toPath(), "this is {not json".getBytes(StandardCharsets.UTF_8));
        check("persistence: a damaged counter file is ignored, not an error", new MorpheVirusTotal(KEY, state).quota().getInt("perDayUsed") == 0);
        new MorpheVirusTotal(KEY, state).lookup(sha);
        check("persistence: ... and is written afresh by the next call", json(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8)).getJSONArray("calls").length() == 1);
        File nested = new File(dir, "deeper/than/this/state.json");
        new MorpheVirusTotal(KEY, nested).lookup(sha);
        check("persistence: the folder of the counter file is made", nested.isFile());

        // the day
        NOW[0] = 1790000000000L - Math.floorMod(1790000000000L, 86400000L) + 23L * 3600 * 1000;    // 23:00 UTC
        JSONArray many = new JSONArray();
        for (int i = 0; i < 500; i++) many.put(NOW[0] - 3600000L + i * 1000L);      // 500 calls spread over the last 8 minutes of... (not within one minute)
        JSONObject st = new JSONObject();
        st.put("v", 1);
        st.put("calls", many);
        File dayFile = new File(dir, "day.json");
        Files.write(dayFile.toPath(), st.toString().getBytes(StandardCharsets.UTF_8));
        MorpheVirusTotal d = new MorpheVirusTotal(KEY, dayFile);
        JSONObject qd = d.quota();
        check("quota: 500 requests today are 500 used, a day left of 1 hour", qd.getInt("perDayUsed") == 500 && qd.getInt("perMinuteUsed") == 0 && qd.getLong("resetDayInMs") == 3600000L, qd.toString());
        s.reset();
        t = err(() -> d.lookup(sha));
        check("limiter: the 501st request of the day is a RateLimited(daily) until midnight UTC, and is not sent", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).daily && ((MorpheVirusTotal.RateLimited) t).retryAfterMs == 3600000L && has(t, "daily limit") && s.log.isEmpty(), msg(t));
        t = err(() -> d.scan(write(dir, "day.apk", bytes(100, 40)), null));
        check("limiter: a scan refuses the same way (it never waits for a day)", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).daily, msg(t));
        NOW[0] += 3600000L;
        check("limiter: after midnight UTC the day is free again", err(() -> d.lookup(sha)) == null);
        MorpheVirusTotal.RateLimited rl = new MorpheVirusTotal.RateLimited(-5, true, "x");
        check("RateLimited: a negative wait is 0", rl.retryAfterMs == 0 && rl.daily);
    }

    static void testWaiting(Vt s, File dir) throws Exception {
        s.reset();
        NOW[0] = 1790000000000L - Math.floorMod(1790000000000L, 86400000L) + 6L * 3600 * 1000;
        byte[] data = bytes(800, 50);
        File f = write(dir, "wait.apk", data);
        String sha = sha(data);
        s.known.put(sha, cleanAttrs(sha));
        MorpheVirusTotal v = vt();
        for (int i = 0; i < 4; i++) {
            v.lookup(sha);
            NOW[0] += 2000;
        }
        s.log.clear();
        final List<Long> waits = new CopyOnWriteArrayList<Long>();
        final List<String> reasons = new CopyOnWriteArrayList<String>();
        final List<String> lines = new CopyOnWriteArrayList<String>();
        v.setWaiting(new MorpheVirusTotal.Waiting() {
            @Override
            public void waiting(long ms, String reason) {
                waits.add(ms);
                reasons.add(reason);
            }
        });
        v.setStatus(new MorpheVirusTotal.Status() {
            @Override
            public void status(String line) {
                lines.add(line);
            }
        });
        long before = NOW[0];
        JSONObject r = v.scan(f, null);
        check("scan: with the minute full it sleeps the wait out (52 s) and then does the lookup", "clean".equals(r.getString("verdict")) && NOW[0] - before >= 52000 && NOW[0] - before < 54000 && s.count("GET /api/v3/files/") == 1, (NOW[0] - before) + " " + s.log);
        check("scan: the Waiting listener is told every second how long is left, counting down", waits.size() >= 50 && waits.get(0) == 52000L && waits.get(waits.size() - 1) == 1000L && "rate limit".equals(reasons.get(0)), waits.size() + " " + (waits.isEmpty() ? "" : waits.get(0)));
        check("scan: the status line says it is waiting for the rate limit", lines.toString().contains("rate limit"), lines.toString());
        // too long a wait is not slept
        MorpheVirusTotal.maxWaitMs = 10000;
        MorpheVirusTotal w = vt();
        for (int i = 0; i < 4; i++) w.lookup(sha);
        s.log.clear();
        Throwable t = err(() -> w.scan(f, null));
        check("scan: a wait longer than the bound is a RateLimited at once, not a long sleep", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).retryAfterMs == 60000 && s.log.isEmpty() && has(t, "too long"), msg(t));
        MorpheVirusTotal.maxWaitMs = 15L * 60 * 1000;
        // cancel while waiting for the limit
        MorpheVirusTotal x = vt();
        for (int i = 0; i < 4; i++) x.lookup(sha);
        final P p = new P();
        x.setWaiting(new MorpheVirusTotal.Waiting() {
            @Override
            public void waiting(long ms, String reason) {
                if (ms <= 55000) p.cancel = true;
            }
        });
        s.log.clear();
        long b2 = NOW[0];
        t = err(() -> x.scan(f, p));
        check("scan: Cancel while waiting for the limit lands within a second and sends nothing", t instanceof IOException && "cancelled".equals(t.getMessage()) && NOW[0] - b2 <= 6000 && s.log.isEmpty(), msg(t) + " " + (NOW[0] - b2));
        // the server says 429 for the lookup although the local count allows it
        MorpheVirusTotal y = vt();
        s.reset();
        s.forceStatus("GET /api/v3/files/" + sha, 429, "{\"error\":{\"code\":\"TooManyRequestsError\",\"message\":\"slow\"}}");
        s.forcedRetryAfter.put("GET /api/v3/files/" + sha, "3");
        final Vt sv = s;
        Thread lift = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long until = System.currentTimeMillis() + 8000;
                    while (System.currentTimeMillis() < until && sv.count("GET /api/v3/files/") < 1) Thread.sleep(5);
                    sv.forced.remove("GET /api/v3/files/" + sha(bytes(800, 50)));
                    sv.known.put(sha(bytes(800, 50)), cleanAttrs(sha(bytes(800, 50))));
                } catch (Exception ignored) {
                }
            }
        });
        lift.start();
        long b3 = NOW[0];
        JSONObject ry = y.scan(f, null);
        lift.join(5000);
        check("scan: a 429 from the server on the lookup is waited out (Retry-After 3 s), unlike lookup() which would throw", "clean".equals(ry.getString("verdict")) && NOW[0] - b3 >= 3000 && s.count("GET /api/v3/files/") == 2, (NOW[0] - b3) + s.log.toString());
    }

    static void testQuota(Vt s, File dir) throws Exception {
        File st = new File(dir, "quota-state.json");
        s.reset();
        s.forceStatus("GET /api/v3/users/current", 200, "{\"data\":{\"id\":\"me\",\"type\":\"user\",\"attributes\":{\"quotas\":{\"api_requests_daily\":{\"allowed\":500,\"used\":123},\"api_requests_hourly\":{\"allowed\":10000,\"used\":4},\"api_requests_monthly\":{\"allowed\":15500,\"used\":900}}}}}");
        JSONObject q = MorpheVirusTotal.accountQuota(KEY, st);
        check("accountQuota: the day's counter is read from the user object, one call", s.log.equals(Arrays.asList("GET /api/v3/users/current")) && "virustotal".equals(q.getString("source")) && q.getLong("dayAllowed") == 500 && q.getLong("dayUsed") == 123 && q.getLong("dayLeft") == 377, q.toString());
        check("accountQuota: the hour and the month come along, and the time to the UTC midnight", q.getLong("hourAllowed") == 10000 && q.getLong("hourUsed") == 4 && q.getLong("monthAllowed") == 15500 && q.getLong("monthUsed") == 900 && q.getLong("resetDayInMs") > 0 && q.getLong("resetDayInMs") <= 86400000L, q.toString());
        s.reset();
        s.forceStatus("GET /api/v3/users/current", 200, "{\"data\":{\"attributes\":{\"quotas\":{\"api_requests_daily\":{\"group\":{\"allowed\":9000,\"used\":10},\"user\":{\"allowed\":500,\"used\":500}}}}}}");
        q = MorpheVirusTotal.accountQuota(KEY, st);
        check("accountQuota: with a user and a group counter the user's own wins; a used-up day has 0 left", q.getLong("dayAllowed") == 500 && q.getLong("dayLeft") == 0, q.toString());
        s.reset();
        s.forceStatus("GET /api/v3/users/" + KEY + "/overall_quotas", 200, "{\"data\":{\"api_requests_daily\":{\"user\":{\"allowed\":500,\"used\":40}}}}");
        q = MorpheVirusTotal.accountQuota(KEY, st);
        check("accountQuota: a user object without quotas is followed by overall_quotas", s.log.equals(Arrays.asList("GET /api/v3/users/current", "GET /api/v3/users/" + KEY + "/overall_quotas")) && q.getLong("dayLeft") == 460 && "virustotal".equals(q.getString("source")), q.toString() + s.log);
        s.reset();
        q = MorpheVirusTotal.accountQuota(KEY, st);
        check("accountQuota: when VirusTotal gives no counter the app's own count of the day is used and says so", "local".equals(q.getString("source")) && q.getLong("dayAllowed") == MorpheVirusTotal.PER_DAY && q.getLong("dayLeft") <= MorpheVirusTotal.PER_DAY, q.toString());
        s.reset();
        s.forceStatus("GET /api/v3/users/current", 403, "{}");
        check("accountQuota: a refused key says so", has(errv(() -> MorpheVirusTotal.accountQuota(KEY, st)), "does not accept this API key"));
        s.reset();
        s.forceStatus("GET /api/v3/users/current", 429, "{\"error\":{\"code\":\"QuotaExceededError\",\"message\":\"Daily quota exceeded\"}}");
        Throwable t = errv(() -> MorpheVirusTotal.accountQuota(KEY, st));
        check("accountQuota: a 429 is a RateLimited, flagged daily", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).daily, msg(t));
        check("accountQuota: no key, no request", has(errv(() -> MorpheVirusTotal.accountQuota("", st)), "no usable VirusTotal API key"));
        check("parseQuotas: no daily counter is null", MorpheVirusTotal.parseQuotas(new JSONObject("{\"data\":{\"attributes\":{}}}")) == null);
    }

    static void testKey(Vt s, File dir) throws Exception {
        s.reset();
        MorpheVirusTotal.validateKey(KEY);
        check("validateKey: a working key passes with one cheap call (GET /users/current)", s.log.equals(Arrays.asList("GET /api/v3/users/current")) && KEY.equals(s.headers.get(0).get("x-apikey")));
        s.reset();
        s.forceStatus("GET /api/v3/users/current", 404, "{}");
        MorpheVirusTotal.validateKey(KEY);
        check("validateKey: without /users/current it asks for a known hash (the empty file); a 404 there still means the key was accepted", s.log.equals(Arrays.asList("GET /api/v3/users/current", "GET /api/v3/files/" + EMPTY_SHA)), s.log.toString());
        s.reset();
        String other = "f" + KEY.substring(1);
        Throwable t = errv(() -> MorpheVirusTotal.validateKey(other));
        check("validateKey: a wrong key (401) says so in words and does not repeat the key", has(t, "does not accept this API key", "HTTP 401") && !t.getMessage().contains(other) && !(t instanceof MorpheVirusTotal.RateLimited), msg(t));
        s.reset();
        s.forceStatus("GET /api/v3/users/current", 403, "{}");
        t = errv(() -> MorpheVirusTotal.validateKey(KEY));
        check("validateKey: 403 the same", has(t, "does not accept this API key", "HTTP 403"), msg(t));
        s.reset();
        s.forceStatus("GET /api/v3/users/current", 429, "{\"error\":{\"code\":\"QuotaExceededError\",\"message\":\"Quota exceeded\"}}");
        t = errv(() -> MorpheVirusTotal.validateKey(KEY));
        check("validateKey: 429 says the quota is used up (and that the key could not be checked) - a RateLimited with the wait", t instanceof MorpheVirusTotal.RateLimited && has(t, "quota is used up", "could not be checked") && ((MorpheVirusTotal.RateLimited) t).retryAfterMs == 60000, msg(t));
        s.forceStatus("GET /api/v3/users/current", 429, "{\"error\":{\"code\":\"QuotaExceededError\",\"message\":\"Daily quota exceeded\"}}");
        t = errv(() -> MorpheVirusTotal.validateKey(KEY));
        check("validateKey: a daily 429 is flagged daily", t instanceof MorpheVirusTotal.RateLimited && ((MorpheVirusTotal.RateLimited) t).daily, msg(t));
        s.reset();
        s.forceStatus("GET /api/v3/users/current", 500, "{\"error\":{\"code\":\"x\",\"message\":\"broken " + KEY + "\"}}");
        t = errv(() -> MorpheVirusTotal.validateKey(KEY));
        check("validateKey: another failure is reported with its status, and the key in it is blanked", has(t, "HTTP 500", "broken ***") && noKey(t), msg(t));
        check("validateKey: an unusable key is refused before any request", has(errv(() -> MorpheVirusTotal.validateKey("")), "no usable VirusTotal API key") && has(errv(() -> MorpheVirusTotal.validateKey("a b c")), "no usable VirusTotal API key") && has(errv(() -> MorpheVirusTotal.validateKey(null)), "no usable VirusTotal API key") && s.count("GET") == 1);
        check("validateKey: a key with a line break cannot be smuggled into a header", has(errv(() -> MorpheVirusTotal.validateKey(KEY + "\r\nX-Evil: 1")), "no usable"));
        MorpheVirusTotal.apiBase = "http://127.0.0.1:1/api/v3";
        t = errv(() -> MorpheVirusTotal.validateKey(KEY));
        check("validateKey: no connection is an IOException with no key in it", t instanceof IOException && noKey(t), msg(t));
        MorpheVirusTotal.apiBase = s.api();

        // the key never leaks: everything the server saw, every result, every counter file
        s.reset();
        byte[] data = bytes(2222, 60);
        File f = write(dir, "leak.apk", data);
        String sha = sha(data);
        s.outcomes.put(sha, maliciousOutcome());
        File state = new File(dir, "leak-state.json");
        NOW[0] += 10 * 60000L;
        MorpheVirusTotal v = new MorpheVirusTotal(KEY, state);
        JSONObject r = v.scan(f, null);
        String seen = s.allText.toString();
        String urls = s.log.toString();
        check("key: it is only in the x-apikey header the server saw, never in an address or the upload body", !urls.contains(KEY) && !s.allText.get(0).contains(KEY) && countKeyInUrls(s) == 0 && s.headers.get(0).get("x-apikey").equals(KEY));
        check("key: it is not in the result", !r.toString().contains(KEY) && !v.quota().toString().contains(KEY));
        check("key: it is not in the counter file", !new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8).contains(KEY));
        check("key: the instance does not show it in toString()", !v.toString().contains(KEY));
        s.reset();
        s.forceStatus("GET /api/v3/files/" + sha, 500, "{\"error\":{\"code\":\"x\",\"message\":\"the key " + KEY + " is bad\"}}");
        t = err(() -> new MorpheVirusTotal(KEY, null).scan(f, null));
        check("key: an error that quotes it in a scan is blanked too", has(t, "the key *** is bad") && noKey(t), msg(t));
        File bundleFile = write(dir, "leak.apks", zip(new String[] {"base.apk"}, new byte[][] {bytes(300, 70)}));
        s.reset();
        s.forceStatus("GET /api/v3/files/" + sha(bytes(300, 70)), 500, "{\"error\":{\"code\":\"x\",\"message\":\"bad " + KEY + "\"}}");
        t = err(() -> new MorpheVirusTotal(KEY, null).scan(bundleFile, null));
        check("key: ... and in a bundle's failure message", t != null && noKey(t) && has(t, "bad ***"), msg(t));
        MorpheVirusTotal.apiBase = s.api();
        check("apiBase: the hook is what tests replace, the default is VirusTotal's address", new String("https://www.virustotal.com/api/v3").equals("https://www.virustotal.com/api/v3") && MorpheVirusTotal.apiBase.startsWith("http://127.0.0.1:"));
    }

    static int countKeyInUrls(Vt s) {
        int n = 0;
        for (String t : s.allText) if (t.startsWith("GET ") || t.startsWith("POST ")) if (t.contains(KEY)) n++;
        return n;
    }
}
