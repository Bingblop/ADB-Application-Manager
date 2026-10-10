package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * The small HTTP client of the Morphe Patcher tab (patch sources, the community patch finder, the Morphe Helper downloads and the
 * VirusTotal calls all go through it). Plain HttpURLConnection, no dependency, so the classes that use it are unit-testable off-device
 * against a local server. https only, except for the loopback address (the tests); redirects are followed by hand so a redirect can never
 * leave https, and a download is written to name.part and renamed when it is whole.
 */
public final class MorpheNet {
    private MorpheNet() {}

    public static final String USER_AGENT = "ADB-Application-Manager-Pro (Morphe Patcher)";
    public static final int CONNECT_TIMEOUT_MS = 20000;
    public static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_REDIRECTS = 8;
    /** No bundle or APK comes near this; it stops an open-ended answer from filling the phone's storage. */
    static long MAX_DOWNLOAD_BYTES = 3L << 30;
    private static final int MAX_TEXT = 64 * 1024 * 1024;

    /** A server answer that is not 2xx. The text is the body, cut to 4 KB. */
    public static final class HttpError extends IOException {
        public final int status;
        public final String body;
        public HttpError(int status, String url, String body) {
            super("HTTP " + status + " from " + hostOf(url));
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /** Receives the progress of a download and may stop it. */
    public interface Progress {
        void onProgress(long done, long total);
        boolean cancelled();
    }

    /** The answer of a request: status, final URL (after redirects), the body as text (for the string calls). */
    public static final class Response {
        public int status;
        public String url;
        public String text;
        public Map<String, java.util.List<String>> headers;
    }

    static String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return "?"; }
    }

    /** The headers that may follow a redirect to another host: the browser-like ones. Anything else (x-apikey, Authorization, Cookie) is dropped. */
    static Map<String, String> plainHeaders(Map<String, String> h) {
        if (h == null) return null;
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : h.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase(java.util.Locale.ROOT);
            if (k.equals("referer") || k.equals("accept") || k.equals("accept-language") || k.equals("user-agent")) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static boolean loopback(URL u) {
        String h = u.getHost();
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h) || "[::1]".equals(h);
    }

    private static void check(URL u) throws IOException {
        String p = u.getProtocol();
        if ("https".equals(p)) return;
        if ("http".equals(p) && loopback(u)) return;
        throw new IOException("only https addresses are allowed: " + u.getProtocol() + "://" + u.getHost());
    }

    private static HttpURLConnection open(String url, String method, Map<String, String> headers, byte[] body, int readTimeout) throws IOException {
        URL u = new URL(url);
        check(u);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "*/*");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            try { os.write(body); } finally { os.close(); }
        }
        return c;
    }

    private static String readAll(InputStream in, int max, Charset cs) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > max) throw new IOException("the answer is bigger than " + (max / 1024 / 1024) + " MB");
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), cs);
        } finally { try { in.close(); } catch (IOException ignored) {} }
    }

    private static String errorBody(HttpURLConnection c) {
        try { return readAll(c.getErrorStream(), 4096, Charset.forName("UTF-8")); } catch (Exception e) { return ""; }
    }

    /** One request with the redirects followed (the method and the body only on the first hop of a 307/308, GET afterwards). */
    public static Response request(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
        String cur = url;
        String m = method;
        byte[] b = body;
        Map<String, String> hdr = headers;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = open(cur, m, hdr, b, READ_TIMEOUT_MS);
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without a Location from " + hostOf(cur));
                    String next = new URL(new URL(cur), loc).toString();
                    if (!HttpSafe.redirectAllowed(cur, next)) throw new IOException("refused a redirect from " + hostOf(cur) + " to " + hostOf(next));
                    if (!HttpSafe.sameOrigin(cur, next)) { hdr = plainHeaders(hdr); b = null; m = "GET"; }      // an API key or a body never follows a redirect to another host
                    else if (code != 307 && code != 308) { m = "GET"; b = null; }
                    cur = next;
                    continue;
                }
                Response r = new Response();
                r.status = code;
                r.url = cur;
                r.headers = c.getHeaderFields();
                String charset = "UTF-8";
                r.text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream(), MAX_TEXT, Charset.forName(charset));
                return r;
            } finally { c.disconnect(); }
        }
        throw new IOException("too many redirects from " + hostOf(url));
    }

    /** GET as text; a non-2xx answer is an {@link HttpError}. */
    public static String getString(String url, Map<String, String> headers) throws IOException {
        Response r = request("GET", url, headers, null);
        if (r.status < 200 || r.status >= 300) throw new HttpError(r.status, url, r.text == null ? "" : (r.text.length() > 4096 ? r.text.substring(0, 4096) : r.text));
        return r.text;
    }

    /** Downloads url to dest (via dest.part, renamed when whole). Returns the size. A cancel leaves nothing behind. */
    public static long download(String url, File dest, Map<String, String> headers, Progress progress) throws IOException {
        File parent = dest.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File part = new File(dest.getPath() + ".part");
        String cur = url;
        Map<String, String> hdr = headers;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = open(cur, "GET", hdr, null, READ_TIMEOUT_MS);
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without a Location from " + hostOf(cur));
                    String next = new URL(new URL(cur), loc).toString();
                    if (!HttpSafe.redirectAllowed(cur, next)) throw new IOException("refused a redirect from " + hostOf(cur) + " to " + hostOf(next));
                    if (!HttpSafe.sameOrigin(cur, next)) hdr = plainHeaders(hdr);                // a key or a cookie stays with the host it was meant for
                    cur = next;
                    continue;
                }
                if (code < 200 || code >= 300) throw new HttpError(code, cur, errorBody(c));
                long total = c.getContentLengthLong();
                if (total > MAX_DOWNLOAD_BYTES) throw new IOException("the file is bigger than " + (MAX_DOWNLOAD_BYTES >> 20) + " MB: refused");
                long done = 0;
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(part);
                boolean ok = false;
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    long lastTick = 0;
                    while ((n = in.read(buf)) > 0) {
                        if (progress != null && progress.cancelled()) throw new IOException("cancelled");
                        out.write(buf, 0, n);
                        done += n;
                        if (done > MAX_DOWNLOAD_BYTES) throw new IOException("the download is bigger than " + (MAX_DOWNLOAD_BYTES >> 20) + " MB: stopped");
                        long now = System.currentTimeMillis();
                        if (progress != null && now - lastTick >= 250) { lastTick = now; progress.onProgress(done, total); }
                    }
                    out.flush();
                    ok = true;
                } finally {
                    try { out.close(); } catch (IOException ignored) {}
                    try { in.close(); } catch (IOException ignored) {}
                    if (!ok) part.delete();
                }
                if (total >= 0 && done != total) { part.delete(); throw new IOException("the download ended early (" + done + " of " + total + " bytes)"); }
                if (dest.exists() && !dest.delete()) { part.delete(); throw new IOException("cannot replace " + dest); }
                if (!part.renameTo(dest)) { part.delete(); throw new IOException("cannot move the download to " + dest); }
                if (progress != null) progress.onProgress(done, total < 0 ? done : total);
                return done;
            } finally { c.disconnect(); }
        }
        throw new IOException("too many redirects from " + hostOf(url));
    }
}
