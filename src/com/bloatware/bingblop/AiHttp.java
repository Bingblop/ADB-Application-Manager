package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One HTTPS call of a coding agent: a JSON request, and the answer either streamed (server-sent events, handed on as raw text so
 * the page can parse each provider's event format) or read whole. Cancel() ends it from any thread. Pure Java (no Android
 * classes), unit-tested off-device against a local server.
 */
public final class AiHttp {

    public interface Sink {
        void onChunk(String text);
    }

    public static final class Request {
        public String url;
        public String method = "POST";
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public String body;
        public boolean stream;
        public int connectTimeoutMs = 20000;
        /** The longest silence allowed (a model can think for a while before its first word). */
        public int readTimeoutMs = 300000;
        /** The key that was added to the headers: kept only to mask it in an error message that quotes a header. */
        public String secret;
    }

    public static final class Response {
        public int status;
        public String body = "";
        public String error = "";
        public boolean cancelled;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    /** Answer headers worth passing on: rate-limit waits and request ids for support. */
    private static final String[] KEEP_HEADERS = {"retry-after", "request-id", "x-request-id", "content-type", "x-cursor-stream-retention-seconds"};
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int MAX_ERROR_BODY = 64 * 1024;

    private volatile HttpURLConnection conn;
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
        final HttpURLConnection c = conn;
        if (c == null) return;
        // Closing the connection can wait on a read that is still blocked (it does on a desktop JVM), so not on the caller's thread
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }, "ai-http-cancel");
        t.setDaemon(true);
        t.start();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Runs the call and returns when it ended or was cancelled. The exchange itself happens on a worker thread, so Cancel returns at
     * once on every platform (on some, a read that is waiting for data cannot be woken up from outside); output that a cancelled
     * call still receives is dropped.
     */
    public Response execute(final Request r, final Sink sink) {
        final Response res = new Response();
        if (cancelled) {
            res.cancelled = true;
            res.error = "cancelled";
            return res;
        }
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    exchange(r, sink, res);
                } finally {
                    finished.countDown();
                }
            }
        }, "ai-http");
        worker.setDaemon(true);
        worker.start();
        try {
            while (!finished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (cancelled) break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cancel();
        }
        if (cancelled) {
            Response c = new Response();          // the worker may still be writing into res: answer with a separate object
            c.cancelled = true;
            c.error = "cancelled";
            synchronized (res) {
                c.status = res.status;
                c.headers.putAll(res.headers);
            }
            return c;
        }
        synchronized (res) {
            return res;
        }
    }

    private void exchange(Request r, Sink sink, Response res) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(r.url).openConnection();
            conn = c;
            if (cancelled) throw new IOException("cancelled");
            String method = r.method == null ? "GET" : r.method.toUpperCase(Locale.ROOT);
            c.setRequestMethod(method);
            c.setConnectTimeout(r.connectTimeoutMs);
            c.setReadTimeout(r.readTimeoutMs);
            c.setInstanceFollowRedirects(false);          // a redirect must not carry a key to another host
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "ADB-Application-Manager");
            c.setRequestProperty("Accept-Encoding", "identity");    // streamed events arrive as they are written
            if (r.stream) c.setRequestProperty("Accept", "text/event-stream");
            for (Map.Entry<String, String> h : r.headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            byte[] body = r.body == null ? null : r.body.getBytes(StandardCharsets.UTF_8);
            if (body != null && !"GET".equals(method)) {
                c.setDoOutput(true);
                if (c.getRequestProperty("Content-Type") == null) c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(body.length);
                OutputStream o = c.getOutputStream();
                o.write(body);
                o.close();
            }
            int status = c.getResponseCode();
            synchronized (res) {
                res.status = status;
                for (String k : KEEP_HEADERS) {
                    String v = c.getHeaderField(k);
                    if (v != null) res.headers.put(k, v);
                }
            }
            if (status >= 300) {
                String b = readAll(c.getErrorStream() != null ? c.getErrorStream() : safeInput(c), MAX_ERROR_BODY);
                synchronized (res) {
                    res.body = b;
                }
            } else if (r.stream && sink != null) {
                Reader rd = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8);
                char[] buf = new char[8192];
                int n;
                while ((n = rd.read(buf)) != -1) {
                    if (cancelled) break;
                    if (n > 0) sink.onChunk(new String(buf, 0, n));
                }
                rd.close();
            } else {
                String b = readAll(c.getInputStream(), MAX_BODY);
                synchronized (res) {
                    res.body = b;
                }
            }
        } catch (Throwable t) {
            synchronized (res) {
                res.error = cancelled ? "cancelled" : describe(t);
            }
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
            conn = null;
        }
    }

    private static InputStream safeInput(HttpURLConnection c) {
        try {
            return c.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }

    static String readAll(InputStream in, int cap) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                int room = cap - out.size();
                if (room <= 0) break;
                out.write(buf, 0, Math.min(n, room));
            }
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** A network failure in words a person can act on. */
    static String describe(Throwable t) {
        String cls = t.getClass().getSimpleName();
        String msg = t.getMessage() == null ? "" : t.getMessage();
        if (t instanceof java.net.UnknownHostException) return "No internet connection, or the address could not be found (" + msg + ")";
        if (t instanceof java.net.ConnectException) return "Could not connect: nothing answers at that address (" + msg + ")";
        if (t instanceof java.net.SocketTimeoutException) return "The server took too long to answer";
        if (t instanceof javax.net.ssl.SSLException) return "Secure connection failed (" + msg + ")";
        if (t instanceof java.net.MalformedURLException) return "Not a valid address (" + msg + ")";
        return msg.isEmpty() ? cls : msg;
    }
}
