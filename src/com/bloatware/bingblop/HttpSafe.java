package com.bloatware.bingblop;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A GET that follows redirects itself, so each hop can be judged: an address is never downgraded from https to http, an http
 * address never leads to a loopback service, and a key or cookie stays with the host it was meant for. (The platform's own redirect
 * handling does none of this reliably, and sends the request headers along wherever the answer points.)
 */
final class HttpSafe {
    private HttpSafe() {}

    static final int MAX_HOPS = 8;

    /** Whether a host name or literal address means this phone: localhost (and *.localhost), 127.0.0.0/8, 0.0.0.0, ::1, ::, and the IPv4-mapped forms. */
    static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return false;
        if (h.equals("localhost") || h.endsWith(".localhost")) return true;
        if (h.startsWith("::ffff:")) h = h.substring(7);                                       // IPv4-mapped IPv6
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] p = h.split("\\.");
            return Integer.parseInt(p[0]) == 127 || h.equals("0.0.0.0");
        }
        return h.equals("::1") || h.equals("::") || h.matches("(0{1,4}:){7}0{0,3}[01]") || h.matches("(0{1,4}:){6}0{1,4}");
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
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            boolean keep = false;
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                for (Map.Entry<String, String> e : hdr.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
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
}
