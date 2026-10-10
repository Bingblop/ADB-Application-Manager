package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/** The small HTTP client of the Morphe Patcher tab: redirects, the headers that follow them (a key never leaves its host), https-only, the .part file. */
public class MorpheNetTest {
    static int n = 0, fails = 0;
    static void ok(String name) { n++; System.out.println("ok   " + name); }
    static void fail(String name, String msg) { n++; fails++; System.out.println("FAIL " + name + " " + msg); }
    static void is(String name, boolean c, String extra) { if (c) ok(name); else fail(name, extra); }

    static String seenKeyB = "unset", seenRefB = "unset";

    public static void main(String[] a) throws Exception {
        final HttpServer b = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        b.createContext("/final", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws java.io.IOException {
                seenKeyB = String.valueOf(x.getRequestHeaders().getFirst("x-apikey"));
                seenRefB = String.valueOf(x.getRequestHeaders().getFirst("Referer"));
                byte[] body = "0123456789".getBytes("UTF-8");
                x.sendResponseHeaders(200, body.length);
                OutputStream o = x.getResponseBody(); o.write(body); o.close();
            }
        });
        // an answer of unknown length (chunked): the size cap has to hold while it streams
        b.createContext("/stream", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws java.io.IOException {
                x.sendResponseHeaders(200, 0);
                OutputStream o = x.getResponseBody();
                for (int i = 0; i < 4; i++) o.write("0123456789".getBytes("UTF-8"));
                o.close();
            }
        });
        b.start();
        final int portB = b.getAddress().getPort();
        final HttpServer a1 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        a1.createContext("/same", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws java.io.IOException {
                x.getResponseHeaders().add("Location", "/final");
                x.sendResponseHeaders(302, -1); x.close();
            }
        });
        a1.createContext("/final", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws java.io.IOException {
                seenKeyB = String.valueOf(x.getRequestHeaders().getFirst("x-apikey"));
                seenRefB = String.valueOf(x.getRequestHeaders().getFirst("Referer"));
                byte[] body = "abc".getBytes("UTF-8");
                x.sendResponseHeaders(200, body.length);
                OutputStream o = x.getResponseBody(); o.write(body); o.close();
            }
        });
        a1.createContext("/cross", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws java.io.IOException {
                x.getResponseHeaders().add("Location", "http://localhost:" + portB + "/final");
                x.sendResponseHeaders(302, -1); x.close();
            }
        });
        a1.start();
        String base = "http://127.0.0.1:" + a1.getAddress().getPort();
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("x-apikey", "SECRET");
        h.put("Referer", "https://example.org/page");

        String t = MorpheNet.getString(base + "/same", h);
        is("a redirect inside the same host keeps every header", "abc".equals(t) && "SECRET".equals(seenKeyB) && "https://example.org/page".equals(seenRefB), seenKeyB + " " + seenRefB);
        t = MorpheNet.getString(base + "/cross", h);
        is("a redirect to another host drops the key", "0123456789".equals(t) && "null".equals(seenKeyB), seenKeyB);
        is("a redirect to another host keeps the referer", "https://example.org/page".equals(seenRefB), seenRefB);

        File dir = new File(System.getProperty("java.io.tmpdir"), "morphenet-" + System.nanoTime());
        dir.mkdirs();
        File dest = new File(dir, "f.bin");
        long size = MorpheNet.download(base + "/cross", dest, h, null);
        is("a download across hosts drops the key too and lands whole", size == 10 && dest.length() == 10 && "null".equals(seenKeyB) && !new File(dest.getPath() + ".part").exists(), size + " " + seenKeyB);

        // a file bigger than the cap is refused before anything is written
        long savedCap = MorpheNet.MAX_DOWNLOAD_BYTES;
        MorpheNet.MAX_DOWNLOAD_BYTES = 5;
        File capDest = new File(dir, "cap.bin");
        boolean capRefused = false;
        try { MorpheNet.download(base + "/cross", capDest, h, null); } catch (java.io.IOException e) { capRefused = e.getMessage().contains("bigger than"); }
        MorpheNet.MAX_DOWNLOAD_BYTES = savedCap;
        is("a download over the size cap is refused and leaves no file", capRefused && !capDest.exists() && !new File(capDest.getPath() + ".part").exists(), "");

        MorpheNet.MAX_DOWNLOAD_BYTES = 25;
        File streamDest = new File(dir, "stream.bin");
        boolean streamRefused = false;
        try { MorpheNet.download("http://127.0.0.1:" + portB + "/stream", streamDest, h, null); } catch (java.io.IOException e) { streamRefused = e.getMessage().contains("bigger than"); }
        MorpheNet.MAX_DOWNLOAD_BYTES = savedCap;
        is("a streamed answer of unknown length is stopped at the cap and leaves no file", streamRefused && !streamDest.exists() && !new File(streamDest.getPath() + ".part").exists(), "");
        MorpheNet.MAX_DOWNLOAD_BYTES = 40;
        long exact = MorpheNet.download("http://127.0.0.1:" + portB + "/stream", streamDest, h, null);
        MorpheNet.MAX_DOWNLOAD_BYTES = savedCap;
        is("a streamed answer exactly at the cap is accepted", exact == 40 && streamDest.length() == 40, String.valueOf(exact));
        streamDest.delete();

        boolean refused = false;
        try { MorpheNet.getString("http://example.org/x", null); } catch (java.io.IOException e) { refused = e.getMessage().contains("https"); }
        is("plain http to a real host is refused", refused, "");
        refused = false;
        try { MorpheNet.getString("file:///etc/passwd", null); } catch (java.io.IOException e) { refused = true; } catch (RuntimeException e) { refused = true; }
        is("a file: address is refused", refused, "");
        Map<String, String> p = MorpheNet.plainHeaders(h);
        is("plainHeaders keeps only the browser-like ones", p.size() == 1 && p.containsKey("Referer"), String.valueOf(p));
        dest.delete(); dir.delete();
        a1.stop(0); b.stop(0);
        System.out.println(n + " checks, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
