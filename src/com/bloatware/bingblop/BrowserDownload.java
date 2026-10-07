package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * Saves what the in-app browser (HelperBrowser) was asked to download: the same request the browser would have made (its cookies, its user agent, the page it came from),
 * the name from the server's Content-Disposition (or the address), written to a .part file and renamed when whole. No Android classes: tested on a computer.
 */
public final class BrowserDownload {

    private BrowserDownload() {
    }

    public interface Cookies {
        /** The Cookie header the browser would send to {@code url}, or null. */
        String forUrl(String url);
    }

    public interface Progress {
        void onProgress(long done, long total);

        boolean cancelled();
    }

    /** What the in-app browser tells the app. */
    public interface Events {
        void onStatus(String text, int pct);

        /** A file was saved. Return true to close the browser now (the file is what was wanted). */
        boolean onDownloaded(File file);

        void onFailed(String why);

        void onClosed();
    }

    public static final class Result {
        public final File file;
        public final long size;
        public final String mime;

        Result(File file, long size, String mime) {
            this.file = file;
            this.size = size;
            this.mime = mime;
        }
    }

    private static final int MAX_REDIRECTS = 8;

    /** A safe file name for what a server sent: Content-Disposition (filename* or filename), else the last part of the address, else "download" plus an extension from the type. */
    public static String fileName(String url, String contentDisposition, String mime) {
        String name = fromDisposition(contentDisposition);
        if (name.isEmpty()) {
            try {
                String path = new URI(url).getPath();
                if (path != null) {
                    int s = path.lastIndexOf('/');
                    String last = s >= 0 ? path.substring(s + 1) : path;
                    name = URLDecoder.decode(last.replace("+", "%2B"), "UTF-8");
                }
            } catch (Exception ignored) {
            }
        }
        name = clean(name);
        if (name.isEmpty() || name.equals("download.php") || name.equals("download")) name = "download";
        if (!name.contains(".") || name.endsWith(".php")) {
            if (name.endsWith(".php")) name = name.substring(0, name.length() - 4);
            name += extensionOf(mime);
        }
        return name;
    }

    static String extensionOf(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (m.equals("application/vnd.android.package-archive")) return ".apk";
        if (m.equals("application/zip") || m.equals("application/x-zip-compressed")) return ".zip";
        return "";
    }

    private static String fromDisposition(String cd) {
        if (cd == null) return "";
        String low = cd.toLowerCase(Locale.ROOT);
        int i = low.indexOf("filename*=");
        if (i >= 0) {
            String v = cd.substring(i + 10).trim();
            int semi = v.indexOf(';');
            if (semi >= 0) v = v.substring(0, semi);
            int q = v.indexOf("''");
            String enc = q > 0 ? v.substring(0, q) : "UTF-8";
            String val = q >= 0 ? v.substring(q + 2) : v;
            try {
                return URLDecoder.decode(val.replace("+", "%2B"), enc.isEmpty() ? "UTF-8" : enc);
            } catch (Exception ignored) {
            }
        }
        i = low.indexOf("filename=");
        if (i < 0) return "";
        String v = cd.substring(i + 9).trim();
        if (v.startsWith("\"")) {
            int end = v.indexOf('"', 1);
            return end > 0 ? v.substring(1, end) : v.substring(1);
        }
        int semi = v.indexOf(';');
        return (semi >= 0 ? v.substring(0, semi) : v).trim();
    }

    private static String clean(String n) {
        String s = n == null ? "" : n.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[^A-Za-z0-9._ ()+\\-]", "_").replaceAll("\\s+", " ").trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 120) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 12 ? s.substring(dot) : "";
            s = s.substring(0, 120 - ext.length()) + ext;
        }
        return s;
    }

    /** A name that is free in {@code dir}: "x.apkm", then "x (1).apkm" and so on. */
    static File freeFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "-" + System.nanoTime() + ext);
    }

    /** Downloads {@code url} into {@code dir} as the browser would. Throws IOException with a sentence for the person when it cannot. */
    public static Result download(String url, String userAgent, Cookies cookies, String referer, File dir, Progress progress) throws IOException {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) throw new IOException("only web addresses (http, https) can be downloaded here");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make the folder " + dir.getName());
        String cur = url;
        HttpURLConnection c = null;
        for (int hop = 0; ; hop++) {
            c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            if (userAgent != null && !userAgent.isEmpty()) c.setRequestProperty("User-Agent", userAgent);
            String ck = cookies == null ? null : cookies.forUrl(cur);
            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || hop >= MAX_REDIRECTS) throw new IOException("the site redirected too often");
                cur = new URL(new URL(cur), loc).toString();
                if (!(cur.startsWith("https://") || cur.startsWith("http://"))) throw new IOException("the site redirected to something that is not a web address");
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("the site answered " + code + (code == 403 ? " (it wants the browser check first: open the page again in the browser and press its download button)" : ""));
            }
            break;
        }
        String mime = c.getContentType() == null ? "" : c.getContentType();
        if (mime.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            c.disconnect();
            throw new IOException("the site sent a web page, not a file");
        }
        String name = fileName(cur, c.getHeaderField("Content-Disposition"), mime);
        long total = c.getContentLengthLong();
        File part = new File(dir, name + ".part");
        long done = 0;
        InputStream in = null;
        FileOutputStream out = null;
        boolean ok = false;
        try {
            in = c.getInputStream();
            out = new FileOutputStream(part);
            byte[] buf = new byte[65536];
            int n;
            long lastTick = 0;
            while ((n = in.read(buf)) > 0) {
                if (progress != null && progress.cancelled()) throw new IOException("cancelled");
                out.write(buf, 0, n);
                done += n;
                long now = System.nanoTime() / 1000000L;
                if (progress != null && now - lastTick >= 150) {
                    lastTick = now;
                    progress.onProgress(done, total);
                }
            }
            out.getFD().sync();
            out.close();
            out = null;
            if (total >= 0 && done != total) throw new IOException("the download ended early (" + done + " of " + total + " bytes)");
            File dest = freeFile(dir, name);
            if (!part.renameTo(dest)) throw new IOException("cannot save " + name);
            ok = true;
            if (progress != null) progress.onProgress(done, done);
            return new Result(dest, done, mime);
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
            c.disconnect();
            if (!ok) part.delete();
        }
    }
}
