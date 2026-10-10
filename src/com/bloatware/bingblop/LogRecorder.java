package com.bloatware.bingblop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Writes the device log to a file while it is switched on, so it can be looked at afterwards as a still picture. The log is read the same way the
 * Logcat tab reads it (a snapshot every couple of seconds); every line that was not written to the file yet is appended, so nothing is written
 * twice and nothing that scrolled out of the device's ring buffer in between is lost unless the device wrote more than a snapshot holds.
 * Plain Java (no Android classes) so it can be tested on a desktop.
 */
public final class LogRecorder {
    /** Where the log text comes from: the same text the Logcat tab shows (log lines, or one line in parentheses / starting "Error:" when there are none). */
    public interface Source { String read(); }

    public static final class Status {
        public final boolean running;
        public final String name;
        public final long lines, bytes, startedAt;
        public final String stopReason, lastError;
        Status(boolean running, String name, long lines, long bytes, long startedAt, String stopReason, String lastError) {
            this.running = running; this.name = name; this.lines = lines; this.bytes = bytes; this.startedAt = startedAt;
            this.stopReason = stopReason; this.lastError = lastError;
        }
    }

    public static final class Info {
        public final String name;
        public final long size, modified;
        Info(String name, long size, long modified) { this.name = name; this.size = size; this.modified = modified; }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int REMEMBER = 40000;                 // lines remembered to tell new ones from ones already written

    private final File dir;
    private final Source source;
    private final long periodMs, maxBytes;
    private ScheduledExecutorService ex;
    private ScheduledFuture<?> task;
    private OutputStream out;
    private File file;
    private long lines, bytes, startedAt;
    private String stopReason = "", lastError = "";
    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > REMEMBER; }
    };

    public LogRecorder(File dir, Source source, long periodMs, long maxBytes) {
        this.dir = dir; this.source = source; this.periodMs = periodMs; this.maxBytes = maxBytes;
    }

    /** A recording's file name: letters, digits, dot, dash, underscore; no folders. */
    public static boolean validName(String name) {
        return name != null && name.length() <= 120 && name.matches("[A-Za-z0-9._-]+\\.log") && !name.startsWith(".");
    }

    /** Starts a recording; header lines (without the leading "# ") go at the top of the file. Returns the file name, or "" when already recording / the file cannot be made. */
    public synchronized String start(List<String> header) {
        if (out != null) return "";
        if (!dir.isDirectory() && !dir.mkdirs()) return "";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(dir, "logcat_" + stamp + ".log");
        for (int i = 2; f.exists() && i < 100; i++) f = new File(dir, "logcat_" + stamp + "_" + i + ".log");
        if (f.exists()) return "";
        try {
            out = new FileOutputStream(f);
            file = f;
            lines = 0; bytes = 0; stopReason = ""; lastError = ""; startedAt = System.currentTimeMillis();
            seen.clear();
            if (header != null) for (String h : header) write("# " + h.replace('\n', ' ') + "\n", false);
        } catch (IOException e) {
            closeQuietly();
            return "";
        }
        ex = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "log-recorder"); t.setDaemon(true); return t; }
        });
        task = ex.scheduleWithFixedDelay(new Runnable() { @Override public void run() { tick(); } }, 0, periodMs, TimeUnit.MILLISECONDS);
        return f.getName();
    }

    /** Stops the recording (the file stays). */
    public synchronized Status stop(String reason) {
        if (task != null) task.cancel(false);
        if (ex != null) ex.shutdownNow();
        task = null; ex = null;
        if (out != null && (stopReason.isEmpty())) stopReason = reason == null ? "stopped" : reason;
        Status s = status();
        closeQuietly();
        return new Status(false, s.name, s.lines, s.bytes, s.startedAt, stopReason, lastError);
    }

    public synchronized Status status() {
        return new Status(out != null, file == null ? "" : file.getName(), lines, bytes, startedAt, stopReason, lastError);
    }

    /** One snapshot (also called by the thread): appends the lines that were not written yet. */
    public void tick() {
        synchronized (this) { if (out == null) return; }
        String text;                                                             // read outside the lock: a snapshot can take seconds and stop() must not wait for it
        try { text = source.read(); } catch (RuntimeException e) { synchronized (this) { lastError = String.valueOf(e.getMessage()); } return; }
        append(text);
    }

    private synchronized void append(String text) {
        if (out == null || text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        if (t.startsWith("Error:")) { lastError = t; return; }                   // a bad moment (no permission yet, adb busy): try again next time
        if (t.startsWith("(") && t.indexOf('\n') < 0) return;                    // "(no matching log lines)"
        lastError = "";
        StringBuilder add = new StringBuilder();
        long n = 0;
        Map<String, Integer> here = new java.util.HashMap<String, Integer>();       // identical lines in one snapshot (a recursion's stack frames) are all kept: a line's key is the line and how many of it came before
        for (String line : text.split("\n")) {
            String raw = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (raw.trim().isEmpty() || raw.startsWith("note: ")) continue;
            Integer k = here.get(raw);
            here.put(raw, k == null ? 1 : k + 1);
            if (seen.put(raw + "\u0001" + (k == null ? 0 : k), Boolean.TRUE) != null) continue;
            add.append(raw).append('\n');
            n++;
        }
        if (n == 0) return;
        if (!write(add.toString(), true)) return;
        lines += n;
        if (bytes >= maxBytes) {
            stopReason = "the file reached its size limit";
            if (task != null) task.cancel(false);
            closeQuietly();
        }
    }

    private boolean write(String s, boolean count) {
        try {
            byte[] b = s.getBytes(UTF8);
            out.write(b);
            out.flush();
            if (count) bytes += b.length;
            return true;
        } catch (IOException e) {
            lastError = String.valueOf(e.getMessage());
            stopReason = "the file could not be written";
            if (task != null) task.cancel(false);
            closeQuietly();
            return false;
        }
    }

    private void closeQuietly() {
        if (out != null) { try { out.close(); } catch (IOException ignored) {} }
        out = null;
    }

    // ---- the saved recordings ----

    /** Newest first. */
    public static List<Info> list(File dir) {
        List<Info> res = new ArrayList<Info>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && validName(f.getName())) res.add(new Info(f.getName(), f.length(), f.lastModified()));
        Collections.sort(res, new Comparator<Info>() {
            @Override public int compare(Info a, Info b) { return a.modified != b.modified ? (a.modified < b.modified ? 1 : -1) : b.name.compareTo(a.name); }
        });
        return res;
    }

    /** The file's text, cut to its last maxBytes when it is longer (a note on the first line says so); "" when it cannot be read. */
    public static String read(File dir, String name, long maxBytes) {
        if (!validName(name)) return "";
        File f = new File(dir, name);
        if (!f.isFile()) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            long len = f.length();
            long skip = len > maxBytes ? len - maxBytes : 0;
            if (skip > 0) { long s = 0; while (s < skip) { long k = in.skip(skip - s); if (k <= 0) break; s += k; } }
            byte[] buf = new byte[(int) (len - skip)];
            int off = 0;
            while (off < buf.length) { int k = in.read(buf, off, buf.length - off); if (k < 0) break; off += k; }
            String text = new String(buf, 0, off, UTF8);
            if (skip > 0) {
                int nl = text.indexOf('\n');
                text = "# (the first " + skip + " bytes of this recording are not shown here)\n" + (nl >= 0 ? text.substring(nl + 1) : text);
            }
            return text;
        } catch (IOException e) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    public static boolean delete(File dir, String name) {
        return validName(name) && new File(dir, name).delete();
    }
}
