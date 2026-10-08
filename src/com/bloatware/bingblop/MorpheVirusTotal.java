package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The optional VirusTotal (API v3) scan of a downloaded file in the Morphe Helper. The flow follows the VirusTotalScanner of "Helper for
 * Morphe" (https://github.com/rushiranpise/helper-for-morphe, by rushiranpise, GPL-3.0): the file's SHA-256 is looked up first, so a file
 * VirusTotal already analysed is not uploaded again (the result says "cached"); only an unknown file is uploaded (POST /files up to 32 MB,
 * a one-time /files/upload_url above that, up to 650 MB) and the analysis is polled until it is done; a split bundle (.apkm / .apks / .xapk)
 * is opened and each inner APK is scanned on its own, since engines often skip big containers.
 *
 * Rate limits (free tier: 4 requests a minute, 500 a day) are kept by a small counter that survives restarts (a JSON file the caller names).
 * What counts: the report lookups, the upload requests and the key check; the analysis polls do not (as in Helper). How a wait is told:
 * lookup() and validateKey() never sleep, they throw {@link RateLimited} with the time to wait; scan() sleeps the per-minute wait out (up to
 * {@link #maxWaitMs} in all), tells the optional {@link Waiting} listener every second and stops within a second of Progress.cancelled();
 * a daily limit, or a wait that would be longer than that, is a RateLimited too. A 429 from the server is waited out the same way.
 *
 * The API key is sent only as the x-apikey header. It is never part of an address, a message, a result or the counter file, and a
 * message that quotes the server's words has the key blanked out. Pure Java (no Android classes), unit-tested against a local server.
 * Network: everything goes through {@link MorpheNet}, except the streamed multipart upload (MorpheNet has no streamed request body, and a
 * file of up to 650 MB must not be held in memory); that one keeps MorpheNet's rule of https only (loopback excepted), never follows a redirect
 * and sends the key only to the host of {@link #apiBase}.
 */
public final class MorpheVirusTotal {

    /** Replaced by the tests with a local server. */
    public static String apiBase = "https://www.virustotal.com/api/v3";

    public static final int PER_MINUTE = 4;
    public static final int PER_DAY = 500;
    private static final long MINUTE_MS = 60000L;
    private static final long DAY_MS = 86400000L;
    private static final long DIRECT_UPLOAD_LIMIT = 32L * 1024 * 1024;
    private static final long MAX_FILE = 650L * 1024 * 1024;
    private static final int MAX_PARTS = 100;
    private static final String EMPTY_FILE_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{16,200}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ANALYSIS_ID = Pattern.compile("[A-Za-z0-9_=+/-]{4,200}");

    // Test hooks: a clock and a sleep that can be replaced, and the polling bounds.
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    static volatile LongSupplier clock;
    static volatile Sleeper sleeper;
    /** Pause between two polls of an analysis (Helper: 3 s). */
    static volatile long pollIntervalMs = 3000L;
    /** How many polls (Helper: 90, about 4.5 minutes). */
    static volatile int maxPolls = 90;
    /** The longest a scan() sleeps for the rate limit, in all. */
    static volatile long maxWaitMs = 15L * 60 * 1000;

    /** The time now, in ms since 1970; the tests put a fake clock in. */
    static long now() {
        LongSupplier c = clock;
        return c != null ? c.getAsLong() : System.currentTimeMillis();
    }

    static void sleep(long ms) throws IOException {
        if (ms <= 0) return;
        try {
            Sleeper s = sleeper;
            if (s != null) s.sleep(ms);
            else Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cancelled");
        }
    }

    /** Tells a caller that scan() is waiting out the rate limit. */
    public interface Waiting {
        void waiting(long retryAfterMs, String reason);
    }

    /** Receives a line about what a scan is doing ("Checking VirusTotal...", "APK 2 of 4 (split_1.apk): ..."). */
    public interface Status {
        void status(String line);
    }

    /** The per-minute or per-day limit is reached: try again after {@link #retryAfterMs}. */
    public static final class RateLimited extends IOException {
        public final long retryAfterMs;
        public final boolean daily;

        RateLimited(long retryAfterMs, boolean daily, String message) {
            super(message);
            this.retryAfterMs = Math.max(0, retryAfterMs);
            this.daily = daily;
        }
    }

    private final String key;
    private final File stateFile;
    private final List<Long> calls = new ArrayList<Long>();
    private long loadedStamp = Long.MIN_VALUE;
    private volatile Waiting waiting;
    private volatile Status status;

    /** stateFile keeps the request counters between runs (JSON, no key in it); null keeps them in memory only. */
    public MorpheVirusTotal(String apiKey, File stateFile) {
        this.key = apiKey == null ? "" : apiKey.trim();
        this.stateFile = stateFile;
        synchronized (this) {
            load(now());
        }
    }

    public void setWaiting(Waiting w) {
        this.waiting = w;
    }

    public void setStatus(Status s) {
        this.status = s;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------------------------------------------------------------

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(JSONObject o, String k, long v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(JSONObject o, String k, boolean v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(JSONObject o, String k) {
        if (o == null) return "";
        Object v = o.opt(k);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    private static long lng(JSONObject o, String k) {
        if (o == null) return 0;
        Object v = o.opt(k);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private static JSONObject obj(JSONObject o, String k) {
        Object v = o == null ? null : o.opt(k);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    private static boolean cancelled(MorpheNet.Progress p) {
        return p != null && p.cancelled();
    }

    private static void progress(MorpheNet.Progress p, int percent) {
        if (p != null) p.onProgress(Math.max(0, Math.min(100, percent)), 100);
    }

    private void say(String line) {
        Status s = status;
        if (s != null) s.status(line);
    }

    /** A text with the API key blanked out (the server could quote a header back). */
    private String redact(String s) {
        if (s == null) return "";
        return key.isEmpty() ? s : s.replace(key, "***");
    }

    private static String hex(byte[] d) {
        StringBuilder b = new StringBuilder(d.length * 2);
        for (byte x : d) b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return b.toString();
    }

    /** Thresholds as in Helper: any malicious or suspicious detection flags the file. */
    public static String verdictOf(int malicious, int suspicious) {
        if (malicious >= 1) return "malicious";
        if (suspicious >= 1) return "suspicious";
        return "clean";
    }

    private static int rank(String verdict) {
        return "malicious".equals(verdict) ? 2 : "suspicious".equals(verdict) ? 1 : 0;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // The rate limiter: a list of the times of the counted requests, in the file and in memory
    // ------------------------------------------------------------------------------------------------------------------------------

    /** Reads the counter file when it changed since the last read (another instance may have written it). Caller holds the lock. */
    private void load(long now) {
        if (stateFile == null) return;
        long stamp = stateFile.exists() ? stateFile.lastModified() : -1L;
        if (stamp == loadedStamp) return;
        loadedStamp = stamp;
        if (stamp < 0) return;
        try {
            InputStream in = new FileInputStream(stateFile);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 1024 * 1024) return;
                }
                JSONObject o = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
                JSONArray a = o.optJSONArray("calls");
                if (a == null) return;
                List<Long> read = new ArrayList<Long>();
                for (int i = 0; i < a.length(); i++) {
                    long t = a.optLong(i, 0);
                    if (t > 0 && t <= now + 5000 && t > now - DAY_MS) read.add(t);
                }
                Collections.sort(read);
                calls.clear();
                calls.addAll(read);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            // an unreadable counter starts again from what is in memory
        } catch (JSONException e) {
            // a damaged counter file is not worth a failure
        }
    }

    private void save() {
        if (stateFile == null) return;
        try {
            JSONObject o = new JSONObject();
            put(o, "v", 1L);
            JSONArray a = new JSONArray();
            for (Long t : calls) a.put(t.longValue());
            put(o, "calls", a);
            File dir = stateFile.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(stateFile.getPath() + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (stateFile.exists() && !stateFile.delete()) {
                tmp.delete();
                return;
            }
            if (tmp.renameTo(stateFile)) loadedStamp = stateFile.lastModified();
        } catch (IOException e) {
            // the counter is best effort: a full disk must not stop a scan
        }
    }

    private void prune(long now) {
        long cutoff = now - DAY_MS;
        while (!calls.isEmpty() && calls.get(0) < cutoff) calls.remove(0);
    }

    /** Requests at or after {@code since} (the UTC day). */
    private int countSince(long since) {
        int n = 0;
        for (Long t : calls) if (t >= since) n++;
        return n;
    }

    /** Requests strictly after {@code after}: the window of the last 60 s is (now - 60 s, now], so a request 60 s old no longer counts. */
    private int countAfter(long after) {
        int n = 0;
        for (Long t : calls) if (t > after) n++;
        return n;
    }

    /** The counters: {perMinuteUsed, perMinuteLimit, perDayUsed, perDayLimit, resetMinuteInMs, resetDayInMs}. The day is the UTC day, as VirusTotal's. */
    public JSONObject quota() {
        synchronized (this) {
            long now = now();
            load(now);
            prune(now);
            long minuteStart = now - MINUTE_MS;
            int inMinute = countAfter(minuteStart);
            long resetMinute = 0;
            if (inMinute > 0) {
                for (Long t : calls) if (t > minuteStart) {
                    resetMinute = t + MINUTE_MS - now;
                    break;
                }
            }
            long dayStart = now - Math.floorMod(now, DAY_MS);
            JSONObject o = new JSONObject();
            put(o, "perMinuteUsed", (long) inMinute);
            put(o, "perMinuteLimit", (long) PER_MINUTE);
            put(o, "perDayUsed", (long) countSince(dayStart));
            put(o, "perDayLimit", (long) PER_DAY);
            put(o, "resetMinuteInMs", Math.max(0, resetMinute));
            put(o, "resetDayInMs", dayStart + DAY_MS - now);
            return o;
        }
    }

    /** Takes a place in the quota (returns 0), or tells how long until the per-minute window has room. A used-up day is a RateLimited. */
    private long tryAcquire() throws RateLimited {
        synchronized (this) {
            long now = now();
            load(now);
            prune(now);
            long dayStart = now - Math.floorMod(now, DAY_MS);
            if (countSince(dayStart) >= PER_DAY) {
                throw new RateLimited(dayStart + DAY_MS - now, true, "VirusTotal's daily limit (" + PER_DAY + " requests) is used up: try again after midnight UTC.");
            }
            long minuteStart = now - MINUTE_MS;
            if (countAfter(minuteStart) >= PER_MINUTE) {
                for (Long t : calls) if (t > minuteStart) return Math.max(1, t + MINUTE_MS - now);
            }
            calls.add(now);
            save();
            return 0;
        }
    }

    private void acquireOrThrow() throws RateLimited {
        long wait = tryAcquire();
        if (wait > 0) throw new RateLimited(wait, false, "VirusTotal's limit of " + PER_MINUTE + " requests a minute is reached: try again in " + ((wait + 999) / 1000) + " s.");
    }

    /** Sleeps in one-second slices (so a cancel lands within a second), telling the Waiting listener how long is left. */
    private void sleepSlices(long ms, MorpheNet.Progress p, String reason) throws IOException {
        long left = ms;
        while (left > 0) {
            if (cancelled(p)) throw new IOException("cancelled");
            Waiting w = waiting;
            if (w != null && reason != null) w.waiting(left, reason);
            long slice = Math.min(1000L, left);
            sleep(slice);
            left -= slice;
        }
        if (cancelled(p)) throw new IOException("cancelled");
    }

    private void acquireWaiting(MorpheNet.Progress p, long[] waited) throws IOException {
        while (true) {
            long wait = tryAcquire();
            if (wait <= 0) return;
            if (waited[0] + wait > maxWaitMs) throw new RateLimited(wait, false, "VirusTotal's limit of " + PER_MINUTE + " requests a minute is reached and the wait is too long: try again in " + ((wait + 999) / 1000) + " s.");
            say("Waiting " + ((wait + 999) / 1000) + " s for the VirusTotal rate limit...");
            sleepSlices(wait, p, "rate limit");
            waited[0] += wait;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------------------------------------------------------------------

    private void needKey() throws IOException {
        if (!KEY.matcher(key).matches()) throw new IOException("There is no usable VirusTotal API key: paste the key from your VirusTotal profile into the settings.");
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("x-apikey", key);
        h.put("Accept", "application/json");
        return h;
    }

    private static long retryAfterMs(MorpheNet.Response r, long fallback) {
        if (r.headers != null) {
            for (Map.Entry<String, List<String>> e : r.headers.entrySet()) {
                if (e.getKey() == null || !e.getKey().equalsIgnoreCase("retry-after") || e.getValue() == null || e.getValue().isEmpty()) continue;
                try {
                    long s = Long.parseLong(e.getValue().get(0).trim());
                    if (s >= 0 && s < 24 * 3600) return Math.max(1000L, s * 1000L);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    private String errorWords(MorpheNet.Response r) {
        try {
            JSONObject o = new JSONObject(r.text == null ? "" : r.text.trim());
            JSONObject e = obj(o, "error");
            String m = str(e, "message");
            if (!m.isEmpty()) return redact(m.length() > 200 ? m.substring(0, 200) : m);
        } catch (JSONException ignored) {
        }
        return "";
    }

    private boolean dailyQuota(MorpheNet.Response r) {
        String t = r.text == null ? "" : r.text.toLowerCase(Locale.ROOT);
        return t.contains("daily") || t.contains("per day");
    }

    private IOException statusError(MorpheNet.Response r) {
        int s = r.status;
        if (s == 401 || s == 403) return new IOException("VirusTotal does not accept this API key (HTTP " + s + "). Check that the whole key was copied from your VirusTotal profile.");
        String words = errorWords(r);
        return new IOException("VirusTotal answered HTTP " + s + (words.isEmpty() ? "" : ": " + words));
    }

    /**
     * One request. counted: it takes a place in the quota. patient: a wait for the limit is slept (and a 429 waited out), else it is a RateLimited.
     * 401 / 403 and other failures are IOExceptions that name the status; 404 is returned for the caller to read.
     */
    private MorpheNet.Response api(String method, String path, byte[] body, boolean counted, boolean patient, MorpheNet.Progress p, long[] waited) throws IOException {
        needKey();
        while (true) {
            if (cancelled(p)) throw new IOException("cancelled");
            if (counted) {
                if (patient) acquireWaiting(p, waited);
                else acquireOrThrow();
            }
            MorpheNet.Response r;
            try {
                Map<String, String> h = headers();
                if (body != null) h.put("Content-Type", "application/x-www-form-urlencoded");
                r = MorpheNet.request(method, apiBase + path, h, body);
            } catch (IOException e) {
                throw new IOException(redact(e.getMessage() == null ? "network error" : e.getMessage()));
            }
            if (r.status == 429) {
                boolean daily = dailyQuota(r);
                long wait = retryAfterMs(r, daily ? DAY_MS : MINUTE_MS);
                if (!patient || daily || waited[0] + wait > maxWaitMs) {
                    throw new RateLimited(wait, daily, daily ? "VirusTotal's daily limit is used up: try again tomorrow." : "VirusTotal says too many requests: try again in " + ((wait + 999) / 1000) + " s.");
                }
                say("VirusTotal asked to wait " + ((wait + 999) / 1000) + " s...");
                sleepSlices(wait, p, "rate limit");
                waited[0] += wait;
                continue;
            }
            if (r.status == 404) return r;
            if (r.status < 200 || r.status >= 300) throw statusError(r);
            return r;
        }
    }

    private JSONObject json(MorpheNet.Response r) throws IOException {
        try {
            return new JSONObject(r.text == null ? "" : r.text.trim());
        } catch (JSONException e) {
            throw new IOException("VirusTotal answered with something that is not JSON.");
        }
    }

    /** Checks that an API key is accepted, with one cheap authenticated call; a wrong key (401 / 403) and a used-up quota (429) say so. */
    public static void validateKey(String key) throws IOException {
        new MorpheVirusTotal(key, null).validate();
    }

    private void validate() throws IOException {
        needKey();
        MorpheNet.Response r;
        try {
            r = MorpheNet.request("GET", apiBase + "/users/current", headers(), null);
        } catch (IOException e) {
            throw new IOException(redact(e.getMessage() == null ? "network error" : e.getMessage()));
        }
        if (r.status == 404 || r.status == 405) {
            try {
                r = MorpheNet.request("GET", apiBase + "/files/" + EMPTY_FILE_SHA256, headers(), null);
            } catch (IOException e) {
                throw new IOException(redact(e.getMessage() == null ? "network error" : e.getMessage()));
            }
            if (r.status == 404) return;
        }
        if (r.status >= 200 && r.status < 300) return;
        if (r.status == 429) {
            boolean daily = dailyQuota(r);
            long wait = retryAfterMs(r, daily ? DAY_MS : MINUTE_MS);
            throw new RateLimited(wait, daily, "VirusTotal's quota is used up right now (HTTP 429), so the key could not be checked: try again in " + ((wait + 999) / 1000) + " s.");
        }
        throw statusError(r);
    }

    /**
     * What is left of the key's quota, as VirusTotal itself counts it (so a scan made by the Installer counts too): GET /users/current, then /users/&lt;key&gt;/overall_quotas
     * when that carries no quotas. The answer: {source "virustotal", dayAllowed, dayUsed, dayLeft, hourAllowed, hourUsed, monthAllowed, monthUsed, resetDayInMs}. When VirusTotal answers
     * without any counter, this app's own count of the day is given instead ({source "local"}, the free key's 500 a day). A wrong key, a used-up quota and no connection are
     * IOExceptions as in {@link #validateKey}.
     */
    public static JSONObject accountQuota(String key, File stateFile) throws IOException {
        MorpheVirusTotal v = new MorpheVirusTotal(key, stateFile);
        v.needKey();
        JSONObject q = v.fetchQuota("/users/current");
        if (q == null) q = v.fetchQuota("/users/" + v.key + "/overall_quotas");
        long now = v.now();
        if (q == null) {
            JSONObject l = v.quota();
            q = new JSONObject();
            put(q, "source", "local");
            put(q, "dayAllowed", lng(l, "perDayLimit"));
            put(q, "dayUsed", lng(l, "perDayUsed"));
        } else {
            put(q, "source", "virustotal");
        }
        put(q, "dayLeft", Math.max(0, lng(q, "dayAllowed") - lng(q, "dayUsed")));
        put(q, "resetDayInMs", DAY_MS - Math.floorMod(now, DAY_MS));
        return q;
    }

    private JSONObject fetchQuota(String path) throws IOException {
        MorpheNet.Response r;
        try {
            r = MorpheNet.request("GET", apiBase + path, headers(), null);
        } catch (IOException e) {
            throw new IOException(redact(e.getMessage() == null ? "network error" : e.getMessage()));
        }
        if (r.status == 404 || r.status == 405) return null;
        if (r.status == 429) {
            boolean daily = dailyQuota(r);
            long wait = retryAfterMs(r, daily ? DAY_MS : MINUTE_MS);
            throw new RateLimited(wait, daily, "VirusTotal's quota is used up right now (HTTP 429): try again in " + ((wait + 999) / 1000) + " s.");
        }
        if (r.status < 200 || r.status >= 300) throw statusError(r);
        try {
            return parseQuotas(new JSONObject(r.text == null ? "" : r.text.trim()));
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Reads the counters out of a user object (data.attributes.quotas) or an overall_quotas answer (data); each of api_requests_daily / _hourly / _monthly is {allowed, used} or
     * {user: {allowed, used}, group: {...}} (the user's own counter wins). Null when there is no daily counter.
     */
    public static JSONObject parseQuotas(JSONObject root) {
        JSONObject data = obj(root, "data");
        JSONObject quotas = obj(obj(data, "attributes"), "quotas");
        if (quotas == null) quotas = data;
        JSONObject day = counter(quotas, "api_requests_daily");
        if (day == null) return null;
        JSONObject o = new JSONObject();
        put(o, "dayAllowed", lng(day, "allowed"));
        put(o, "dayUsed", lng(day, "used"));
        JSONObject hour = counter(quotas, "api_requests_hourly");
        if (hour != null) {
            put(o, "hourAllowed", lng(hour, "allowed"));
            put(o, "hourUsed", lng(hour, "used"));
        }
        JSONObject month = counter(quotas, "api_requests_monthly");
        if (month != null) {
            put(o, "monthAllowed", lng(month, "allowed"));
            put(o, "monthUsed", lng(month, "used"));
        }
        return o;
    }

    private static JSONObject counter(JSONObject quotas, String name) {
        JSONObject c = obj(quotas, name);
        if (c == null) return null;
        JSONObject u = obj(c, "user");
        if (u != null) c = u;
        return c.has("allowed") ? c : null;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------------------------------------------------------------------

    /** One result from stats and per-engine results; null when there are no engine results yet. */
    private JSONObject result(JSONObject stats, JSONObject engines, String sha256, long dateSeconds, boolean cached) {
        if (stats == null) return null;
        long harmless = lng(stats, "harmless"), malicious = lng(stats, "malicious"), suspicious = lng(stats, "suspicious");
        long undetected = lng(stats, "undetected"), timeout = lng(stats, "timeout");
        long total = harmless + malicious + suspicious + undetected + timeout + lng(stats, "type-unsupported") + lng(stats, "failure") + lng(stats, "confirmed-timeout");
        if (total <= 0) return null;
        List<String[]> flagged = new ArrayList<String[]>();
        if (engines != null) {
            java.util.Iterator<?> it = engines.keys();
            while (it.hasNext()) {
                String name = String.valueOf(it.next());
                JSONObject e = obj(engines, name);
                String cat = str(e, "category");
                String res = str(e, "result");
                if (e == null || res.isEmpty() || !(cat.equals("malicious") || cat.equals("suspicious"))) continue;
                String engine = str(e, "engine_name");
                flagged.add(new String[] {engine.isEmpty() ? name : engine, res, cat});
            }
        }
        Collections.sort(flagged, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                boolean ma = a[2].equals("malicious"), mb = b[2].equals("malicious");
                if (ma != mb) return ma ? -1 : 1;
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        JSONObject o = new JSONObject();
        put(o, "found", true);
        put(o, "cached", cached);
        put(o, "sha256", sha256);
        put(o, "malicious", malicious);
        put(o, "suspicious", suspicious);
        put(o, "harmless", harmless);
        put(o, "undetected", undetected);
        put(o, "timeout", timeout);
        put(o, "total", total);
        put(o, "verdict", verdictOf((int) Math.min(malicious, Integer.MAX_VALUE), (int) Math.min(suspicious, Integer.MAX_VALUE)));
        JSONArray fl = new JSONArray();
        for (String[] f : flagged) {
            JSONObject x = new JSONObject();
            put(x, "engine", f[0]);
            put(x, "result", f[1]);
            fl.put(x);
        }
        put(o, "flagged", fl);
        put(o, "permalink", "https://www.virustotal.com/gui/file/" + sha256);
        put(o, "scannedAt", dateSeconds > 0 ? dateSeconds * 1000L : now());
        return o;
    }

    private JSONObject notFound() {
        JSONObject o = new JSONObject();
        put(o, "found", false);
        return o;
    }

    private JSONObject fromFileReport(JSONObject root, String sha256) {
        JSONObject data = obj(root, "data");
        JSONObject attrs = data == null ? null : obj(data, "attributes");
        if (attrs == null) return null;
        String sha = str(attrs, "sha256");
        if (!SHA.matcher(sha).matches()) sha = sha256;
        return result(obj(attrs, "last_analysis_stats"), obj(attrs, "last_analysis_results"), sha, lng(attrs, "last_analysis_date"), true);
    }

    private JSONObject lookupImpl(String sha256, MorpheNet.Progress p, long[] waited, boolean patient) throws IOException {
        if (sha256 == null || !SHA.matcher(sha256.toLowerCase(Locale.ROOT)).matches()) throw new IOException("not a SHA-256: " + (sha256 == null ? "" : sha256.length() > 70 ? sha256.substring(0, 70) : sha256));
        String sha = sha256.toLowerCase(Locale.ROOT);
        // a 2xx that is not JSON (a proxy's page) is a blip, not a verdict: ask again a couple of times (Helper does)
        for (int attempt = 1; ; attempt++) {
            MorpheNet.Response r = api("GET", "/files/" + sha, null, true, patient, p, waited);
            if (r.status == 404) return notFound();
            String t = r.text == null ? "" : r.text.trim();
            if (t.startsWith("{")) {
                JSONObject res = fromFileReport(json(r), sha);
                // a file VirusTotal knows but has not analysed yet has no engine results: treat as not found, so it gets uploaded / analysed
                return res == null ? notFound() : res;
            }
            if (attempt >= 3) throw new IOException("VirusTotal keeps answering with something that is not JSON.");
            if (cancelled(p)) throw new IOException("cancelled");
            sleep(1500L * attempt);
        }
    }

    /** The report VirusTotal already has for this SHA-256: {found:false} or {found:true, cached:true, ...}. Never sleeps: a full minute is a RateLimited. */
    public JSONObject lookup(String sha256) throws IOException {
        return lookupImpl(sha256, null, new long[1], false);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------------------------------------------------------------------

    private static String sha256Of(File f, MorpheNet.Progress p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no SHA-256 on this device");
        }
        InputStream in = new BufferedInputStream(new FileInputStream(f), 65536);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled(p)) throw new IOException("cancelled");
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    private boolean sameHost(String a, String b) {
        try {
            URL x = new URL(a), y = new URL(b);
            return x.getHost().equalsIgnoreCase(y.getHost()) && x.getPort() == y.getPort() && x.getProtocol().equalsIgnoreCase(y.getProtocol());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean loopbackHost(String h) {
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h) || "[::1]".equals(h);
    }

    /** Streams the file as multipart/form-data to url (the key as a header only). Returns the status and the body text. */
    private MorpheNet.Response postFile(String url, File f, String displayName, MorpheNet.Progress p, int pctFrom, int pctTo) throws IOException {
        URL u = new URL(url);
        boolean https = "https".equals(u.getProtocol());
        if (!https && !("http".equals(u.getProtocol()) && loopbackHost(u.getHost()))) throw new IOException("only https addresses are allowed: " + u.getProtocol() + "://" + u.getHost());
        String boundary = "----MorpheVT" + Long.toHexString(System.nanoTime()) + Long.toHexString(f.length());
        String fname = displayName.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (fname.isEmpty()) fname = "file.apk";
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fname + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long size = f.length();
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(MorpheNet.CONNECT_TIMEOUT_MS);
            c.setReadTimeout(180000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(head.length + size + tail.length);
            c.setRequestProperty("User-Agent", MorpheNet.USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("x-apikey", key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            OutputStream out = c.getOutputStream();
            try {
                out.write(head);
                InputStream in = new BufferedInputStream(new FileInputStream(f), 65536);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    long done = 0;
                    long lastTick = 0;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled(p)) throw new IOException("cancelled");
                        out.write(buf, 0, n);
                        done += n;
                        long t = System.currentTimeMillis();
                        if (t - lastTick >= 250) {
                            lastTick = t;
                            progress(p, pctFrom + (int) ((pctTo - pctFrom) * done / Math.max(1, size)));
                        }
                    }
                } finally {
                    in.close();
                }
                out.write(tail);
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            int code = c.getResponseCode();
            InputStream rin = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (rin != null) {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = rin.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        if (bo.size() > 4 * 1024 * 1024) break;
                    }
                } finally {
                    rin.close();
                }
            }
            MorpheNet.Response r = new MorpheNet.Response();
            r.status = code;
            r.url = url;
            r.text = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            r.headers = c.getHeaderFields();
            return r;
        } catch (IOException e) {
            if ("cancelled".equals(e.getMessage())) throw e;
            throw new IOException(redact(e.getMessage() == null ? "network error" : e.getMessage()));
        } finally {
            c.disconnect();
        }
    }

    /** Uploads the file; returns the analysis id, or null when VirusTotal says it has this file already (409). */
    private String upload(File f, String displayName, MorpheNet.Progress p, long[] waited, int pctFrom, int pctTo) throws IOException {
        String target = apiBase + "/files";
        boolean viaUrl = f.length() > DIRECT_UPLOAD_LIMIT;
        while (true) {
            if (viaUrl) {
                MorpheNet.Response ur = api("GET", "/files/upload_url", null, true, true, p, waited);
                String addr = str(json(ur), "data");
                if (addr.isEmpty()) throw new IOException("VirusTotal gave no upload address.");
                // the key goes only to the host of the API, whatever the answer says
                if (!sameHost(addr, apiBase)) throw new IOException("VirusTotal gave an upload address on another host: refused, the API key is not sent there.");
                target = addr;
            }
            acquireWaiting(p, waited);
            MorpheNet.Response r = postFile(target, f, displayName, p, pctFrom, pctTo);
            if (r.status == 413 && !viaUrl) {
                viaUrl = true;
                continue;
            }
            if (r.status == 409) return null;
            if (r.status == 429) {
                long wait = retryAfterMs(r, MINUTE_MS);
                if (dailyQuota(r) || waited[0] + wait > maxWaitMs) throw new RateLimited(wait, dailyQuota(r), "VirusTotal says too many requests: try again in " + ((wait + 999) / 1000) + " s.");
                sleepSlices(wait, p, "rate limit");
                waited[0] += wait;
                continue;
            }
            if (r.status < 200 || r.status >= 300) throw statusError(r);
            JSONObject o = json(r);
            JSONObject data = obj(o, "data");
            String id = str(data, "id");
            if (id.isEmpty()) throw new IOException("VirusTotal gave no analysis id for the upload.");
            return id;
        }
    }

    private JSONObject pollAnalysis(String id, String sha256, MorpheNet.Progress p, long[] waited, int pctFrom, int pctTo) throws IOException {
        if (!ANALYSIS_ID.matcher(id).matches()) throw new IOException("VirusTotal gave an analysis id that cannot be used.");
        String last = "";
        int polls = Math.max(1, maxPolls);
        for (int i = 0; i < polls; i++) {
            if (cancelled(p)) throw new IOException("cancelled");
            sleepSlices(pollIntervalMs, p, null);
            MorpheNet.Response r = api("GET", "/analyses/" + id, null, false, true, p, waited);
            if (r.status == 404) throw new IOException("VirusTotal lost the analysis: try the scan again.");
            JSONObject root = json(r);
            JSONObject data = obj(root, "data");
            JSONObject attrs = data == null ? null : obj(data, "attributes");
            String st = str(attrs, "status");
            progress(p, pctFrom + (int) ((long) (pctTo - pctFrom) * (i + 1) / polls));
            if ("completed".equals(st)) {
                String sha = sha256;
                JSONObject meta = obj(root, "meta");
                JSONObject fi = meta == null ? null : obj(meta, "file_info");
                if (fi == null && attrs != null) fi = obj(obj(attrs, "meta"), "file_info");
                String fromServer = str(fi, "sha256");
                if (SHA.matcher(fromServer).matches()) sha = fromServer;
                JSONObject res = result(obj(attrs, "stats"), obj(attrs, "results"), sha, lng(attrs, "date"), false);
                if (res == null) throw new IOException("VirusTotal returned no engine results for this file.");
                return res;
            }
            if (!st.equals(last)) {
                last = st;
                say("queued".equals(st) ? "Queued at VirusTotal: waiting for the engines..." : "in-progress".equals(st) ? "The VirusTotal engines are scanning..." : "Waiting for the analysis...");
            }
        }
        throw new IOException("VirusTotal did not finish the analysis in time (its queue is busy): try again later.");
    }

    private JSONObject scanOne(File f, String displayName, MorpheNet.Progress p, long[] waited, int pctFrom, int pctTo) throws IOException {
        if (cancelled(p)) throw new IOException("cancelled");
        if (f.length() > MAX_FILE) throw new IOException(displayName + " is too big for VirusTotal (the limit is 650 MB).");
        int span = pctTo - pctFrom;
        progress(p, pctFrom);
        say("Checking " + displayName + " on VirusTotal...");
        String sha = sha256Of(f, p);
        progress(p, pctFrom + span / 10);
        JSONObject cached = lookupImpl(sha, p, waited, true);
        if (cached.optBoolean("found", false)) {
            progress(p, pctTo);
            return cached;
        }
        say("Uploading " + displayName + " to VirusTotal...");
        String id = upload(f, displayName, p, waited, pctFrom + span / 5, pctFrom + span / 2);
        if (id == null) {
            // VirusTotal has the file after all (analysed between the lookup and the upload): read its report
            JSONObject again = lookupImpl(sha, p, waited, true);
            if (!again.optBoolean("found", false)) throw new IOException("VirusTotal says it already has this file but gave no report yet: try again in a minute.");
            progress(p, pctTo);
            return again;
        }
        say("Waiting for the analysis of " + displayName + "...");
        JSONObject res = pollAnalysis(id, sha, p, waited, pctFrom + span / 2, pctTo);
        progress(p, pctTo);
        return res;
    }

    /**
     * The whole flow for one file: report lookup by SHA-256 (a report VirusTotal has is reused: "cached":true), else upload and poll. A bundle
     * is split into its APKs, each scanned on its own; "parts" has the per-APK results, the overall verdict is the worst one and the numbers
     * and flagged engines are those of the worst APK. Progress is percent (done 0..100 of 100). Cancel: IOException("cancelled").
     */
    public JSONObject scan(File file, MorpheNet.Progress p) throws IOException {
        needKey();
        if (file == null || !file.isFile()) throw new IOException("file not found: " + (file == null ? "" : file.getName()));
        long[] waited = new long[1];
        String n = file.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".apkm") || n.endsWith(".apks") || n.endsWith(".xapk")) return scanBundle(file, p, waited);
        JSONObject r = scanOne(file, file.getName(), p, waited, 0, 100);
        put(r, "bundle", false);
        return r;
    }

    private JSONObject scanBundle(File file, MorpheNet.Progress p, long[] waited) throws IOException {
        ZipFile zf;
        try {
            zf = new ZipFile(file);
        } catch (IOException e) {
            // not a zip: scan it as one file (Helper does the same)
            JSONObject r = scanOne(file, file.getName(), p, waited, 0, 100);
            put(r, "bundle", false);
            return r;
        }
        File dir = null;
        try {
            List<ZipEntry> apks = new ArrayList<ZipEntry>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements() && apks.size() < MAX_PARTS) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) apks.add(e);
            }
            if (apks.isEmpty()) {
                JSONObject r = scanOne(file, file.getName(), p, waited, 0, 100);
                put(r, "bundle", false);
                return r;
            }
            dir = File.createTempFile("morphe-vt", "");
            if (!dir.delete() || !dir.mkdirs()) throw new IOException("cannot make a temporary folder");
            JSONArray parts = new JSONArray();
            List<JSONObject> done = new ArrayList<JSONObject>();
            String firstError = "";
            int failed = 0;
            int total = apks.size();
            for (int i = 0; i < total; i++) {
                if (cancelled(p)) throw new IOException("cancelled");
                ZipEntry e = apks.get(i);
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                File tmp = new File(dir, i + "_" + base.replaceAll("[^A-Za-z0-9._-]", "_"));
                int from = 5 + i * 95 / total, to = 5 + (i + 1) * 95 / total;
                try {
                    say("APK " + (i + 1) + " of " + total + " (" + base + "): extracting...");
                    copyEntry(zf, e, tmp, p);
                    say("APK " + (i + 1) + " of " + total + " (" + base + "): checking VirusTotal...");
                    JSONObject part = scanOne(tmp, base, p, waited, from, to);
                    put(part, "name", name);
                    parts.put(part);
                    done.add(part);
                } catch (RateLimited rl) {
                    throw rl;
                } catch (IOException ex) {
                    if ("cancelled".equals(ex.getMessage())) throw ex;
                    failed++;
                    String msg = redact(ex.getMessage() == null ? "failed" : ex.getMessage());
                    if (firstError.isEmpty()) firstError = base + ": " + msg;
                    JSONObject bad = new JSONObject();
                    put(bad, "name", name);
                    put(bad, "found", false);
                    put(bad, "error", msg);
                    parts.put(bad);
                } finally {
                    tmp.delete();
                }
            }
            if (done.isEmpty()) throw new IOException("All " + total + " APKs failed to scan: " + firstError);
            JSONObject worst = null;
            int flaggedFiles = 0;
            boolean allCached = true;
            long latest = 0;
            for (JSONObject d : done) {
                String v = str(d, "verdict");
                if (rank(v) > 0) flaggedFiles++;
                if (!d.optBoolean("cached", false)) allCached = false;
                latest = Math.max(latest, lng(d, "scannedAt"));
                if (worst == null || rank(v) > rank(str(worst, "verdict")) || (rank(v) == rank(str(worst, "verdict")) && lng(d, "malicious") + lng(d, "suspicious") > lng(worst, "malicious") + lng(worst, "suspicious"))) worst = d;
            }
            if (failed > 0 && flaggedFiles == 0) throw new IOException(done.size() + " of " + total + " APKs scanned clean; " + failed + " failed (" + firstError + ")");
            JSONObject o = new JSONObject();
            put(o, "found", true);
            put(o, "bundle", true);
            put(o, "cached", allCached);
            put(o, "sha256", str(worst, "sha256"));
            put(o, "malicious", lng(worst, "malicious"));
            put(o, "suspicious", lng(worst, "suspicious"));
            put(o, "harmless", lng(worst, "harmless"));
            put(o, "undetected", lng(worst, "undetected"));
            put(o, "timeout", lng(worst, "timeout"));
            put(o, "total", lng(worst, "total"));
            put(o, "verdict", str(worst, "verdict"));
            Object fl = worst.opt("flagged");
            put(o, "flagged", fl instanceof JSONArray ? fl : new JSONArray());
            put(o, "permalink", str(worst, "permalink"));
            put(o, "scannedAt", latest);
            put(o, "scannedFiles", (long) done.size());
            put(o, "flaggedFiles", (long) flaggedFiles);
            put(o, "failedFiles", (long) failed);
            put(o, "parts", parts);
            progress(p, 100);
            return o;
        } finally {
            try {
                zf.close();
            } catch (IOException ignored) {
            }
            if (dir != null) {
                File[] left = dir.listFiles();
                if (left != null) for (File x : left) x.delete();
                dir.delete();
            }
        }
    }

    private static void copyEntry(ZipFile zf, ZipEntry e, File dest, MorpheNet.Progress p) throws IOException {
        InputStream in = zf.getInputStream(e);
        try {
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled(p)) throw new IOException("cancelled");
                    total += n;
                    if (total > MAX_FILE) throw new IOException(e.getName() + " is too big for VirusTotal (the limit is 650 MB).");
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
