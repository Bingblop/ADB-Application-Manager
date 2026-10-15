package com.bloatware.bingblop;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A GET that follows redirects itself, so each hop can be judged: an address is never downgraded from https to http, an http
 * address never leads to a loopback service, and a key or cookie stays with the host it was meant for. (The platform's own redirect
 * handling does none of this reliably, and sends the request headers along wherever the answer points.)
 *
 * {@link #post} uploads a body of a known length, streamed (never held in memory whole). An upload is never sent on to another address: a
 * redirect in answer to it is an error, so the body (and the headers) only ever reach the address the caller named.
 */
final class HttpSafe {
    private HttpSafe() {}

    static final int MAX_HOPS = 8;

    /** Whether a host name or numeric address means this phone: localhost (and *.localhost) and any numeric form of 127.0.0.0/8, 0.0.0.0, ::1 or ::. No DNS lookup is made. */
    static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = canonicalHost(host.trim());
        if (h == null) return true;                                                            // a spelling that cannot be read is not trusted to be outside
        h = h.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return false;
        if (h.equals("localhost") || h.endsWith(".localhost")) return true;
        if (h.indexOf(':') >= 0) {                                                            // an IPv6 literal (any compression, mapped IPv4 included)
            try {
                java.net.InetAddress a = java.net.InetAddress.getByName(h);                    // a literal: parsed, not looked up
                return a.isLoopbackAddress() || a.isAnyLocalAddress();
            } catch (Exception e) {
                return false;
            }
        }
        return numericV4IsLoopback(h);
    }

    /**
     * The host as the network stack will use it: percent escapes decoded and Unicode mapped to ASCII (IDNA, which also turns full-width digits
     * into plain ones), so "%31%32%37.0.0.1" and "１２７.0.0.1" are read as 127.0.0.1. Null when the host cannot be read that way.
     */
    private static String canonicalHost(String host) {
        String h = host;
        if (h.indexOf('%') >= 0) {
            try {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                for (int i = 0; i < h.length(); i++) {
                    char c = h.charAt(i);
                    if (c == '%') {
                        if (i + 2 >= h.length()) return null;
                        b.write(Integer.parseInt(h.substring(i + 1, i + 3), 16));
                        i += 2;
                    } else {
                        byte[] u = String.valueOf(c).getBytes("UTF-8");
                        b.write(u, 0, u.length);
                    }
                }
                h = new String(b.toByteArray(), "UTF-8");
            } catch (Exception e) {
                return null;
            }
            if (h.indexOf('%') >= 0) return null;                                              // escaped twice: no plain reading
        }
        boolean ascii = true;
        for (int i = 0; i < h.length(); i++) if (h.charAt(i) > 0x7f) { ascii = false; break; }
        if (ascii) return h;
        try {
            return java.net.IDN.toASCII(h, java.net.IDN.ALLOW_UNASSIGNED);
        } catch (Exception e) {
            return null;
        }
    }

    /** inet_aton forms (what the system resolver accepts): 1 to 4 parts, each decimal, 0octal or 0xhex; the last part fills the remaining bytes. */
    private static boolean numericV4IsLoopback(String h) {
        if (!h.matches("(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+)){0,3}")) return false;
        String[] parts = h.split("\\.");
        long[] v = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i];
            try {
                if (t.startsWith("0x")) v[i] = Long.parseLong(t.substring(2), 16);
                else if (t.length() > 1 && t.startsWith("0")) v[i] = Long.parseLong(t.substring(1), 8);
                else v[i] = Long.parseLong(t);
            } catch (NumberFormatException e) {
                return false;                                                                   // not a number the resolver would take (e.g. 09, or too long)
            }
        }
        long value = 0;
        for (int i = 0; i < v.length - 1; i++) {
            if (v[i] > 255) return false;
            value |= v[i] << (24 - 8 * i);
        }
        long lastMax = (1L << (8 * (5 - v.length))) - 1;                                           // 1 part: 32 bits ... 4 parts: 8 bits
        if (v[v.length - 1] > lastMax) return false;
        value |= v[v.length - 1];
        return (value >>> 24) == 127 || value == 0;
    }

    /** Whether a redirect from one address to another may be followed. */
    static boolean redirectAllowed(String from, String to) {
        try {
            URL f = new URL(from), t = new URL(to);
            String ts = t.getProtocol().toLowerCase(Locale.ROOT);
            if (!ts.equals("https") && !ts.equals("http")) return false;
            if (isLoopback(t.getHost()) && !isLoopback(f.getHost())) return false;            // an outside address does not point into the phone (either scheme)
            if (ts.equals("https")) return true;
            return f.getProtocol().equalsIgnoreCase("http");                                  // https -> http is a downgrade
        } catch (Exception e) {
            return false;
        }
    }

    /** Same scheme, host and effective port: the only case in which a key, a cookie or a request body may follow a redirect. */
    static boolean sameOrigin(String a, String b) {
        try {
            URL x = new URL(a), y = new URL(b);
            int px = x.getPort() == -1 ? x.getDefaultPort() : x.getPort();
            int py = y.getPort() == -1 ? y.getDefaultPort() : y.getPort();
            return x.getHost().equalsIgnoreCase(y.getHost()) && px == py && x.getProtocol().equalsIgnoreCase(y.getProtocol());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sensitive(String header) {
        String k = header.toLowerCase(Locale.ROOT);
        return k.equals("authorization") || k.equals("x-apikey") || k.equals("cookie") || k.equals("proxy-authorization");
    }

    /**
     * Opens {@code url} with GET and returns the connection of the final (non-redirect) answer, its status already read. The caller
     * reads the body and disconnects. Headers named Authorization, x-apikey and Cookie are dropped when a redirect changes the host.
     */
    static HttpURLConnection open(String url, int connectMs, int readMs, Map<String, String> headers) throws IOException {
        String cur = url;
        Map<String, String> hdr = headers == null ? new LinkedHashMap<String, String>() : headers;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            HttpURLConnection c = connection(cur, connectMs, readMs, hdr);
            boolean keep = false;
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) throw new IOException("redirect without a Location from " + new URL(cur).getHost());
                    String next = new URL(new URL(cur), loc).toString();
                    if (!redirectAllowed(cur, next)) throw new IOException("refused a redirect from " + new URL(cur).getHost() + " to " + new URL(next).getProtocol() + "://" + new URL(next).getHost());
                    if (!sameOrigin(cur, next)) {
                        Map<String, String> plain = new LinkedHashMap<String, String>();
                        for (Map.Entry<String, String> e : hdr.entrySet()) if (!sensitive(e.getKey())) plain.put(e.getKey(), e.getValue());
                        hdr = plain;
                    }
                    cur = next;
                    continue;
                }
                keep = true;
                return c;
            } finally {
                if (!keep) c.disconnect();
            }
        }
        throw new IOException("too many redirects from " + new URL(url).getHost());
    }

    /** An upload's body. */
    interface Body {
        /** Called with the connection before anything is sent, so a caller can disconnect it from another thread (a cancel) while it writes. */
        void opened(HttpURLConnection c);
        /** Writes the body into the connection's stream (the stream is closed by {@link #post}). */
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * POSTs {@code length} bytes that {@code body} writes, in fixed-length streaming mode (nothing is buffered whole), and returns the
     * connection with its status already read; the caller reads the answer and disconnects. Any redirect (3xx) is an IOException: the upload
     * is not re-sent anywhere. A body that writes a different number of bytes than {@code length} fails as the platform makes it fail.
     */
    static HttpURLConnection post(String url, int connectMs, int readMs, Map<String, String> headers, long length, Body body) throws IOException {
        if (length < 0) throw new IOException("an upload needs its length");
        HttpURLConnection c = connection(url, connectMs, readMs, headers == null ? new LinkedHashMap<String, String>() : headers);
        boolean keep = false;
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(length);
            body.opened(c);
            OutputStream out = c.getOutputStream();
            try {
                body.writeTo(out);
            } finally {
                out.close();
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) throw new IOException("refused a redirect of an upload to " + new URL(url).getHost());
            keep = true;
            return c;
        } finally {
            if (!keep) c.disconnect();
        }
    }

    /** One connection with automatic redirects off, the timeouts and the headers set; nothing is sent yet. The only place this class opens one. */
    private static HttpURLConnection connection(String url, int connectMs, int readMs, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        return c;
    }
}
