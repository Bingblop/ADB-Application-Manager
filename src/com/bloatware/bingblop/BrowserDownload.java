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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ------------------------------------------------------------------------------------------------------------------------ resumable jobs

    public enum State { QUEUED, RUNNING, PAUSED, FAILED, DONE, CANCELLED }

    /**
     * One download that can be paused, resumed and retried. What is already on disk stays in {@code name.part} when it is paused or breaks; a later {@link #run} asks the server
     * for the rest (Range, with If-Range so a changed file starts over) and starts over when the server does not do ranges. Fields are written by the downloading thread and read by others.
     */
    public static final class Job {
        public final String id;
        public final String url;
        public final String userAgent;
        public final String referer;
        public final File dir;
        public volatile String name = "";
        public volatile String resolvedUrl = "";
        public volatile String etag = "";
        public volatile String lastModified = "";
        public volatile long done;
        public volatile long total = -1;
        public volatile State state = State.QUEUED;
        public volatile String error = "";
        public volatile String mime = "";
        public volatile File file;
        public volatile long updated = System.currentTimeMillis();
        volatile boolean pauseRequested, cancelRequested;

        public Job(String id, String url, String userAgent, String referer, File dir) {
            this.id = id;
            this.url = url;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.referer = referer == null ? "" : referer;
            this.dir = dir;
        }

        public File part() {
            return name.isEmpty() ? null : new File(dir, name + ".part");
        }

        /** Stops after the bytes in hand and keeps the .part file: {@link #run} goes on from there. */
        public void pause() {
            pauseRequested = true;
        }

        /** Stops and removes the .part file. */
        public void cancel() {
            cancelRequested = true;
        }

        public boolean active() {
            return state == State.RUNNING;
        }
    }

    /** Called from the downloading thread, about every 150 ms while bytes arrive, and at every change of state. */
    public interface Listener {
        void onChange(Job job);
    }

    /**
     * Runs {@code job} until it is whole, paused, cancelled or broken, and leaves the outcome in {@code job.state} (never throws for a download problem; {@code job.error} says what
     * went wrong in words for the person). Calling it again on a PAUSED or FAILED job resumes it.
     */
    public static void run(Job job, Cookies cookies, Listener listener) {
        if (job.state != State.QUEUED) {           // a job that was queued may already have been asked to pause or cancel: that stays
            job.pauseRequested = false;
            job.cancelRequested = false;
        }
        job.error = "";
        job.state = State.RUNNING;
        tell(listener, job);
        try {
            transfer(job, cookies, listener);
        } catch (Cancelled c) {
            File p = job.part();
            if (p != null) p.delete();                 // the part goes before the state says so: whoever sees Cancelled sees the file gone
            job.state = State.CANCELLED;
        } catch (Paused p) {
            job.state = State.PAUSED;
        } catch (IOException e) {
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.state = State.FAILED;
        } catch (RuntimeException e) {
            job.error = String.valueOf(e.getMessage());
            job.state = State.FAILED;
        }
        job.updated = System.currentTimeMillis();
        tell(listener, job);
    }

    private static final class Paused extends IOException {
        Paused() {
            super("paused");
        }
    }

    private static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    private static void tell(Listener l, Job j) {
        if (l != null) {
            try {
                l.onChange(j);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void check(Job job) throws IOException {
        if (job.cancelRequested) throw new Cancelled();
        if (job.pauseRequested) throw new Paused();
    }

    /**
     * Where a redirect from {@code cur} to {@code loc} leads, or an IOException in words when it must not be followed: not a web address,
     * a step down from https to http, or an outside address pointing at this phone (the same judgement as {@link HttpSafe#open}).
     * The caller works out the Cookie header again for each hop's own address instead of forwarding the previous one; which cookies that
     * returns for a host is the cookie store's call (a domain cookie can match several hosts), so this does not promise host filtering itself.
     */
    static String nextHop(String cur, String loc) throws IOException {
        String next = new URL(new URL(cur), loc).toString();
        if (!(next.startsWith("https://") || next.startsWith("http://"))) throw new IOException("the site redirected to something that is not a web address");
        if (!HttpSafe.redirectAllowed(cur, next)) throw new IOException("the site redirected to an address that is not safe to follow (from https to http, or to this phone)");
        return next;
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");

    private static void transfer(Job job, Cookies cookies, Listener listener) throws IOException {
        String url = job.url;
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) throw new IOException("only web addresses (http, https) can be downloaded here");
        if (!job.dir.isDirectory() && !job.dir.mkdirs()) throw new IOException("cannot make the folder " + job.dir.getName());
        File part = job.part();
        long have = part != null && part.isFile() ? part.length() : 0;
        // a validator is needed to trust what is on disk: without one (or without a known name) the file starts over
        boolean canResume = have > 0 && (!job.etag.isEmpty() || !job.lastModified.isEmpty());
        if (!canResume && part != null && part.exists()) {
            part.delete();
            have = 0;
        }
        // the address the last attempt ended at is tried first (a signed storage address outlives the page link that led to it), then the one the browser gave
        String[] tries = canResume && !job.resolvedUrl.isEmpty() && !job.resolvedUrl.equals(url) ? new String[]{job.resolvedUrl, url} : new String[]{url};
        IOException last = null;
        for (int t = 0; t < tries.length; t++) {
            try {
                attempt(job, tries[t], cookies, listener, canResume ? have : 0);
                return;
            } catch (Paused | Cancelled stop) {
                throw stop;
            } catch (HttpFailure f) {
                last = f;
                if (!(f.code >= 400 && f.code < 500) || t == tries.length - 1) throw f;
            }
        }
        if (last != null) throw last;
    }

    private static final class HttpFailure extends IOException {
        final int code;

        HttpFailure(int code, String m) {
            super(m);
            this.code = code;
        }
    }

    private static void attempt(Job job, String startUrl, Cookies cookies, Listener listener, long from) throws IOException {
        String cur = startUrl;
        HttpURLConnection c;
        int restarts = 0;
        for (int hop = 0; ; ) {
            check(job);
            c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            if (!job.userAgent.isEmpty()) c.setRequestProperty("User-Agent", job.userAgent);
            String ck = cookies == null ? null : cookies.forUrl(cur);
            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            if (!job.referer.isEmpty()) c.setRequestProperty("Referer", job.referer);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");     // a range is about the bytes of the file, not of a compressed copy
            if (from > 0) {
                c.setRequestProperty("Range", "bytes=" + from + "-");
                c.setRequestProperty("If-Range", !job.etag.isEmpty() ? job.etag : job.lastModified);
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || ++hop > MAX_REDIRECTS) throw new IOException("the site redirected too often");
                cur = nextHop(cur, loc);
                continue;
            }
            if (code == 416 && from > 0 && restarts == 0) {
                // what is on disk does not fit the file any more (or is all of it): start over once
                c.disconnect();
                File p = job.part();
                if (p != null) p.delete();
                job.done = 0;
                from = 0;
                restarts++;
                continue;
            }
            if (code != 200 && code != 206) {
                c.disconnect();
                throw new HttpFailure(code, "the site answered " + code + (code == 403 ? " (it wants the browser check first: open the page again in the browser and press its download button)" : code == 410 || code == 404 ? " (the link has expired: open the page again in the browser and press its download button)" : ""));
            }
            break;
        }
        boolean partial = false;
        long total;
        try {
            String mime = c.getContentType() == null ? "" : c.getContentType();
            if (mime.toLowerCase(Locale.ROOT).startsWith("text/html")) throw new HttpFailure(200, "the site sent a web page, not a file");
            long len = c.getContentLengthLong();
            if (c.getResponseCode() == 206) {
                Matcher m = CONTENT_RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
                if (!m.find() || Long.parseLong(m.group(1)) != from) throw new IOException("the site answered with a different part of the file than was asked for");
                partial = true;
                total = "*".equals(m.group(3)) ? -1 : Long.parseLong(m.group(3));
            } else {
                total = len;
            }
            job.mime = mime;
            job.resolvedUrl = cur;
            String name = job.name;
            if (name.isEmpty() || !partial) {
                if (name.isEmpty()) name = fileName(cur, c.getHeaderField("Content-Disposition"), mime);
                job.name = name;
            }
            String et = c.getHeaderField("ETag");
            String lm = c.getHeaderField("Last-Modified");
            if (!partial) {
                job.etag = et == null || et.startsWith("W/") ? "" : et;          // a weak validator does not allow a range
                job.lastModified = lm == null ? "" : lm;
            }
            job.total = total;
            File part = job.part();
            long done = partial ? from : 0;
            job.done = done;
            tell(listener, job);
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = c.getInputStream();
                out = new FileOutputStream(part, partial);
                byte[] buf = new byte[65536];
                int n;
                long lastTick = 0;
                while ((n = readChunk(in, buf, done, total)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    job.done = done;
                    long now = System.nanoTime() / 1000000L;
                    if (now - lastTick >= 150) {
                        lastTick = now;
                        job.updated = System.currentTimeMillis();
                        tell(listener, job);
                    }
                    check(job);
                }
                out.getFD().sync();
            } finally {
                try {
                    if (in != null) in.close();
                } catch (IOException ignored) {
                }
                try {
                    if (out != null) out.close();
                } catch (IOException ignored) {
                }
            }
            if (total >= 0 && done != total) throw new IOException("the download ended early (" + done + " of " + total + " bytes): Retry goes on from there");
            File dest = freeFile(job.dir, job.name);
            if (!part.renameTo(dest)) throw new IOException("cannot save " + job.name);
            job.file = dest;
            job.done = done;
            job.total = done;
            job.state = State.DONE;
        } finally {
            c.disconnect();
        }
    }

    private static int readChunk(InputStream in, byte[] buf, long done, long total) throws IOException {
        try {
            return in.read(buf);
        } catch (IOException e) {
            throw new IOException("the connection broke after " + (done / 1048576) + (total > 0 ? " of " + (total / 1048576) : "") + " MB: Retry goes on from there");
        }
    }

    /** Downloads {@code url} into {@code dir} as the browser would, in one go (no resuming: a part that is left behind is removed). Throws IOException with a sentence for the person when it cannot. */
    public static Result download(String url, String userAgent, Cookies cookies, String referer, File dir, final Progress progress) throws IOException {
        final Job job = new Job("one-shot", url, userAgent, referer, dir);
        Listener l = new Listener() {
            @Override
            public void onChange(Job j) {
                if (progress != null) {
                    progress.onProgress(j.done, j.total);
                    if (progress.cancelled()) j.pauseRequested = true;
                }
            }
        };
        run(job, cookies, l);
        if (job.state == State.DONE) return new Result(job.file, job.done, job.mime);
        File p = job.part();
        if (p != null) p.delete();
        if (job.state == State.PAUSED || job.state == State.CANCELLED) throw new IOException("cancelled");
        throw new IOException(job.error.isEmpty() ? "the download failed" : job.error);
    }
}
