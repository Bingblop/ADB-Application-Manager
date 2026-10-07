package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** What the in-app browser saves: names from Content-Disposition and the address, the browser's cookies / user agent / referer on every hop, redirects, refusals in words, a web page is not a file, partial files never stay. */
public class BrowserDownloadTest {
    static int n = 0, fails = 0;
    static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static void is(String what, boolean ok, String d) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + d); } }
    static final Map<String, String> seen = new ConcurrentHashMap<String, String>();

    public static void main(String[] args) throws Exception {
        // ---- names ----
        is("name: filename in Content-Disposition", BrowserDownload.fileName("https://x/dl.php?id=1", "attachment; filename=\"com.google.android.inputmethod.latin_18.0.3_apkmirror.com.apkm\"", "application/octet-stream").equals("com.google.android.inputmethod.latin_18.0.3_apkmirror.com.apkm"));
        is("name: filename* (UTF-8) wins over filename (the accent becomes _)", BrowserDownload.fileName("https://x/a", "attachment; filename=\"a.apk\"; filename*=UTF-8''caf%C3%A9.apks", "").equals("caf_.apks"));
        is("name: unquoted filename", BrowserDownload.fileName("https://x/a", "attachment; filename=app.xapk; size=5", "").equals("app.xapk"));
        is("name: from the address when there is no header", BrowserDownload.fileName("https://cdn.example/files/My%20App-1.2.apk?token=abc", null, "application/octet-stream").equals("My App-1.2.apk"));
        is("name: download.php with an Android package type becomes download.apk", BrowserDownload.fileName("https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=9&key=k", null, "application/vnd.android.package-archive").equals("download.apk"));
        is("name: path tricks and odd characters are removed", BrowserDownload.fileName("https://x/a", "attachment; filename=\"../../etc/pass:wd*.apk\"", "").equals("pass_wd_.apk"), BrowserDownload.fileName("https://x/a", "attachment; filename=\"../../etc/pass:wd*.apk\"", ""));
        is("name: a hidden-file name loses its dots", !BrowserDownload.fileName("https://x/a", "attachment; filename=\".hidden.apk\"", "").startsWith("."));
        is("name: very long names are cut, the extension kept", BrowserDownload.fileName("https://x/a", "attachment; filename=\"" + "a".repeat(300) + ".apkm\"", "").length() <= 120 && BrowserDownload.fileName("https://x/a", "attachment; filename=\"" + "a".repeat(300) + ".apkm\"", "").endsWith(".apkm"));

        // ---- a local server ----
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final byte[] body = new byte[300000];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31);
        srv.createContext("/file", ex -> {
            seen.put("ua", String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            seen.put("cookie", String.valueOf(ex.getRequestHeaders().getFirst("Cookie")));
            seen.put("referer", String.valueOf(ex.getRequestHeaders().getFirst("Referer")));
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"example_1.0.apkm\"");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        srv.createContext("/redirect", ex -> { ex.getResponseHeaders().add("Location", "/file"); ex.sendResponseHeaders(302, -1); ex.close(); });
        srv.createContext("/loop", ex -> { ex.getResponseHeaders().add("Location", "/loop"); ex.sendResponseHeaders(302, -1); ex.close(); });
        srv.createContext("/forbidden", ex -> { ex.sendResponseHeaders(403, -1); ex.close(); });
        srv.createContext("/page", ex -> { byte[] b = "<html>challenge</html>".getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.createContext("/short", ex -> { ex.getResponseHeaders().add("Content-Type", "application/octet-stream"); ex.sendResponseHeaders(200, 100000); ex.getResponseBody().write(new byte[1000]); ex.close(); });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        File dir = Files.createTempDirectory("bd").toFile();
        try {
            final StringBuilder asked = new StringBuilder();
            BrowserDownload.Cookies ck = url -> { asked.append(url).append('\n'); return "cf_clearance=abc; sid=1"; };
            final long[] last = {0, 0};
            BrowserDownload.Progress pr = new BrowserDownload.Progress() { public void onProgress(long d, long t) { last[0] = d; last[1] = t; } public boolean cancelled() { return false; } };
            BrowserDownload.Result r = BrowserDownload.download(base + "/file", "Mozilla/5.0 (Linux; Android 14) Chrome/121", ck, base + "/page-it-came-from", dir, pr);
            is("saved under the server's name, byte for byte", r.file.getName().equals("example_1.0.apkm") && Arrays.equals(Files.readAllBytes(r.file.toPath()), body) && r.size == body.length, r.file.getName());
            is("the browser's user agent, cookies and referer were sent", seen.get("ua").contains("Chrome/121") && seen.get("cookie").equals("cf_clearance=abc; sid=1") && seen.get("referer").endsWith("/page-it-came-from"), String.valueOf(seen));
            is("progress ends at the whole size", last[0] == body.length && last[1] == body.length, last[0] + "/" + last[1]);
            is("no .part file is left", new File(dir, "example_1.0.apkm.part").exists() == false);
            BrowserDownload.Result r2 = BrowserDownload.download(base + "/redirect", "UA", ck, null, dir, null);
            is("a redirect is followed (the cookies are asked for each address) and a second copy gets a free name", r2.file.getName().equals("example_1.0 (1).apkm") && asked.toString().contains("/redirect") && asked.toString().contains("/file"), r2.file.getName() + " " + asked);
            Throwable t = err(() -> BrowserDownload.download(base + "/forbidden", "UA", null, null, dir, null));
            is("403 says that the browser check comes first", t instanceof IOException && t.getMessage().contains("403") && t.getMessage().contains("browser check"), String.valueOf(t));
            t = err(() -> BrowserDownload.download(base + "/page", "UA", null, null, dir, null));
            is("a web page is refused (it is a challenge or an error page, not the file)", t != null && t.getMessage().contains("web page"), String.valueOf(t));
            t = err(() -> BrowserDownload.download(base + "/loop", "UA", null, null, dir, null));
            is("a redirect loop ends", t != null && t.getMessage().contains("redirected too often"), String.valueOf(t));
            t = err(() -> BrowserDownload.download(base + "/short", "UA", null, null, dir, null));
            is("a download that ends early is an error", t instanceof IOException, String.valueOf(t));
            String[] left = dir.list();
            is("only the two good files are in the folder", left.length == 2, Arrays.toString(left));
            t = err(() -> BrowserDownload.download("file:///etc/passwd", "UA", null, null, dir, null));
            is("only http and https", t != null && t.getMessage().contains("web addresses"), String.valueOf(t));
            t = err(() -> BrowserDownload.download("javascript:alert(1)", "UA", null, null, dir, null));
            is("javascript: and the like are refused", t != null);
            BrowserDownload.Progress cancel = new BrowserDownload.Progress() { public void onProgress(long d, long tt) {} public boolean cancelled() { return true; } };
            t = err(() -> BrowserDownload.download(base + "/file", "UA", null, null, dir, cancel));
            is("cancelling stops and removes the part", t != null && t.getMessage().contains("cancelled") && dir.list().length == 2, String.valueOf(t) + Arrays.toString(dir.list()));
        } finally {
            srv.stop(0);
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
        System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    interface Call { void run() throws Exception; }
    static Throwable err(Call c) { try { c.run(); return null; } catch (Throwable t) { return t; } }
}
