package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Listing a compressed tar can be stopped (audit Z-6). A tar.zst / xz / bz2 / gz has no directory: to find the next header the listing
 * unpacks every entry's data, so a 290 KB tar.zst holding one 8 GiB entry of zeros took 4 s to list and a real one can take minutes. The page
 * could not stop it (one bridge call at a time, and the lock was held). The listing now asks a callback every 64 KB of unpacked data and at every
 * entry, and ends with IOException("Cancelled") when it says stop.
 *
 * What is checked: the "which formats are slow" decision; a callback that says stop ends the listing of a big compressed tar (made here by
 * streaming a tar with a huge entry of zeros through zstd / xz / bzip2 / gzip, a few hundred KB on disk) within a small margin, with
 * "Cancelled", with no file left behind and no file handle open; the asks are never more than 64 KB of data apart; a normal archive lists the same
 * with and without a callback; and a source scan of the parts that cannot run here (the app's open must not hold the lock while it unpacks, a
 * stale open must not publish over a newer one, the page must have the callback and the Cancel).
 */
public class ArchiveCancelTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    // ------------------------------------------------------------------------------------------------------------ tar writing

    static byte[] header(String name, long size, char type) {
        byte[] b = new byte[512];
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nm, 0, b, 0, Math.min(nm.length, 100));
        put(b, 100, "0000644"); put(b, 108, "0000000"); put(b, 116, "0000000");
        put(b, 124, String.format("%011o", size));
        put(b, 136, "00000000000");
        put(b, 148, "        ");
        b[156] = (byte) type;
        put(b, 257, "ustar  ");
        int sum = 0;
        for (byte x : b) sum += x & 0xff;
        put(b, 148, String.format("%06o", sum)); b[154] = 0; b[155] = ' ';
        return b;
    }

    static void put(byte[] b, int off, String s) { byte[] x = s.getBytes(StandardCharsets.US_ASCII); System.arraycopy(x, 0, b, off, x.length); }

    static void zeros(OutputStream o, long n) throws IOException {
        byte[] z = new byte[1 << 20];
        for (long left = n; left > 0; ) { int k = (int) Math.min(z.length, left); o.write(z, 0, k); left -= k; }
    }

    static void entry(OutputStream o, String name, String text) throws IOException {
        byte[] d = text.getBytes(StandardCharsets.UTF_8);
        o.write(header(name, d.length, '0')); o.write(d); zeros(o, (512 - d.length % 512) % 512);
    }

    /** A tar with a few small entries (a folder, a nested file, a 20 KB one), the same for every format. */
    static void smallTar(OutputStream o) throws IOException {
        o.write(header("dir/", 0, '5'));
        entry(o, "a.txt", "hello");
        entry(o, "dir/b.txt", "bee");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("line ").append(i).append('\n');
        entry(o, "dir/long.txt", sb.toString());
        zeros(o, 1024);
    }

    /** A tar of first.txt, one entry of {@code bigBytes} zeros and last.txt. */
    static void bigTar(OutputStream o, long bigBytes) throws IOException {
        entry(o, "first.txt", "first");
        o.write(header("big.bin", bigBytes, '0'));
        zeros(o, bigBytes);
        zeros(o, (512 - bigBytes % 512) % 512);
        entry(o, "last.txt", "last");
        zeros(o, 1024);
    }

    interface TarWriter { void write(OutputStream o) throws IOException; }

    /** Streams a tar through an external compressor into {@code dest}; false when the tool is not there. */
    static boolean compress(String[] cmd, File dest, TarWriter w) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(dest);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            try (OutputStream o = p.getOutputStream()) { w.write(o); }
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;          // the tool is not installed
        } catch (InterruptedException e) {
            return false;
        }
    }

    static boolean make(File dest, String ext, TarWriter w) throws IOException {
        if ("gz".equals(ext)) {
            try (OutputStream o = new GZIPOutputStream(new FileOutputStream(dest), 1 << 16) { { def.setLevel(1); } }) { w.write(o); }
            return true;
        }
        String[] cmd = "zst".equals(ext) ? new String[] {"zstd", "-q", "-1", "-c"}
                : "xz".equals(ext) ? new String[] {"xz", "-1", "-T1", "-c"}
                : "bz2".equals(ext) ? new String[] {"bzip2", "-1", "-c"}
                : "lz4".equals(ext) ? new String[] {"lz4", "-q", "-1", "-c"} : null;
        return cmd != null && compress(cmd, dest, w);
    }

    // ------------------------------------------------------------------------------------------------------------ measuring

    /** The callback: counts asks, remembers the largest step between two, can stop once {@code stopAt} bytes were seen or when {@link #stop} is set. */
    static final class Probe implements ArchiveIo.Progress {
        final long stopAt;
        volatile boolean stop;
        int asks;
        long last, maxStep;
        Probe(long stopAt) { this.stopAt = stopAt; }
        @Override public boolean tick(long done) {
            asks++;
            maxStep = Math.max(maxStep, done - last);
            last = done;
            if (stop) return false;
            return stopAt < 0 || done < stopAt;
        }
    }

    static final class Outcome {
        ArchiveIo.Info info; Throwable error; long ms;
        String msg() { return error == null ? "" : String.valueOf(error.getMessage()); }
        boolean cancelled() { return error instanceof IOException && "Cancelled".equals(error.getMessage()); }
        List<String> names() { List<String> n = new ArrayList<String>(); if (info != null) for (ArchiveIo.Item it : info.items) n.add(it.name); return n; }
    }

    static Outcome list(File f, String format, ArchiveIo.Progress cb) {
        Outcome r = new Outcome();
        long t = System.nanoTime();
        try { r.info = ArchiveIo.list(f, format, null, cb); } catch (Throwable e) { r.error = e; }
        r.ms = (System.nanoTime() - t) / 1000000;
        return r;
    }

    static int openFds() {
        String[] n = new File("/proc/self/fd").list();
        return n == null ? -1 : n.length;
    }

    static List<String> names(File dir) {
        String[] n = dir.list();
        List<String> l = n == null ? new ArrayList<String>() : new ArrayList<String>(Arrays.asList(n));
        java.util.Collections.sort(l);
        return l;
    }

    // ------------------------------------------------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        pure();
        File root = Files.createTempDirectory("archivecancel").toFile();
        try {
            smallArchives(root);
            bigArchives(root);
            sourceScan();
        } finally {
            deleteTree(root);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("ALL PASSED");
    }

    // ------------------------------------------------------------------------------------------------------------ the decision

    static void pure() {
        for (String f : new String[] {"tar.gz", "tar.bz2", "tar.xz", "tar.zst", "tar.lz4"})
            check("listing a " + f + " needs a full pass (slowToList)", ArchiveIo.slowToList(f), null);
        for (String f : new String[] {"tar", "7z", "zip", "rar", "gz", "bz2", "xz", "zst", "lz4", "", "tar.", "tar.rar", "nonsense", null})
            check("listing a " + f + " does not (slowToList is false)", !ArchiveIo.slowToList(f), null);
        for (String n : new String[] {"a.tar.zst", "a.tar.zstd", "A.TAR.GZ", "x.tgz", "x.taz", "x.tbz2", "x.tbz", "x.tb2", "x.txz", "x.tzst", "x.tar.lz4", "x.tlz4", "Backup 1.tar.bz2", "a.tar.xz"})
            check("a file named " + n + " is slow to list (slowToListByName)", ArchiveIo.slowToListByName(n), null);
        for (String n : new String[] {"a.tar", "a.zip", "a.apk", "a.7z", "a.rar", "a.zst", "a.gz", "a.xz", "a.bz2", "a.lz4", "tar.gz.txt", "readme", "", null})
            check("a file named " + n + " is not (slowToListByName is false)", !ArchiveIo.slowToListByName(n), null);
    }

    // ------------------------------------------------------------------------------------------------------------ a normal archive

    static void smallArchives(File root) throws Exception {
        List<String> want = Arrays.asList("dir/", "a.txt", "dir/b.txt", "dir/long.txt");
        for (String ext : new String[] {"gz", "zst", "xz", "bz2", "lz4"}) {
            File f = new File(root, "small.tar." + ext);
            if (!make(f, ext, new TarWriter() { public void write(OutputStream o) throws IOException { smallTar(o); } })) {
                System.out.println("SKIP tar." + ext + ": the tool to make one is not installed");
                continue;
            }
            String fmt = "tar." + ext;
            Outcome plain = list(f, fmt, null);
            Probe pr = new Probe(-1);
            Outcome withCb = list(f, fmt, pr);
            check("tar." + ext + ": lists " + want, plain.error == null && plain.names().equals(want), plain.msg() + " " + plain.names());
            boolean same = plain.error == null && withCb.error == null && plain.info.items.size() == withCb.info.items.size() && plain.info.solid == withCb.info.solid && plain.info.truncated == withCb.info.truncated;
            for (int i = 0; same && i < plain.info.items.size(); i++) {
                ArchiveIo.Item a = plain.info.items.get(i), b = withCb.info.items.get(i);
                same = a.name.equals(b.name) && a.dir == b.dir && a.size == b.size && a.mode == b.mode && a.mtime == b.mtime;
            }
            check("tar." + ext + ": the same listing with a callback that never stops (names, sizes, modes, times)", same, withCb.msg());
            check("tar." + ext + ": the callback was asked at every entry (" + pr.asks + " times for " + want.size() + " entries)", pr.asks >= want.size(), "asks=" + pr.asks);
            Probe now = new Probe(0);
            Outcome stopped = list(f, fmt, now);
            check("tar." + ext + ": a callback that says stop at once ends even a small archive with Cancelled", stopped.cancelled(), stopped.msg());
            Outcome again = list(f, fmt, null);
            check("tar." + ext + ": after a stop the same file lists again", again.error == null && again.names().equals(want), again.msg());
            if ("zst".equals(ext) || "xz".equals(ext)) {
                try {
                    ZipTool.Archive a = ArchiveIoSource.open(f, fmt, null, new Probe(-1));
                    check("tar." + ext + ": ArchiveIoSource.open (what the app calls) passes the callback and lists the same", a.entries.size() == want.size(), null);
                } catch (IOException e) { check("tar." + ext + ": ArchiveIoSource.open with a callback", false, e.toString()); }
                try {
                    ArchiveIoSource.open(f, fmt, null, new Probe(0));
                    check("tar." + ext + ": ArchiveIoSource.open stops when the callback says so", false, "no exception");
                } catch (IOException e) { check("tar." + ext + ": ArchiveIoSource.open stops when the callback says so (Cancelled)", "Cancelled".equals(e.getMessage()), e.toString()); }
            }
        }
        // formats that are not slow take the callback and ignore it
        File sevenOrSingle = new File(root, "single.txt.gz");
        try (OutputStream o = new GZIPOutputStream(new FileOutputStream(sevenOrSingle))) { o.write("single file".getBytes(StandardCharsets.UTF_8)); }
        Outcome one = list(sevenOrSingle, "gz", new Probe(0));
        check("a single .gz lists without being asked anything (one entry, nothing to unpack)", one.error == null && one.names().equals(Arrays.asList("single.txt")), one.msg());
    }

    // ------------------------------------------------------------------------------------------------------------ a big one, stopped

    static void bigArchives(File root) throws Exception {
        // sizes picked so that an unstopped listing takes seconds, and the files stay small: runs of zeros pack to almost nothing
        final Object[][] cases = { {"zst", 3L << 30}, {"xz", 1L << 30}, {"bz2", 192L << 20}, {"gz", 1L << 30} };
        for (Object[] c : cases) {
            String ext = (String) c[0];
            final long big = (Long) c[1];
            File f = new File(root, "big.tar." + ext);
            if (!make(f, ext, new TarWriter() { public void write(OutputStream o) throws IOException { bigTar(o, big); } })) {
                System.out.println("SKIP tar." + ext + " big: the tool to make one is not installed");
                continue;
            }
            String fmt = "tar." + ext;
            check("tar." + ext + ": the fixture is small on disk (" + f.length() / 1024 + " KB for " + (big >> 20) + " MB of entry data)", f.length() < 8 << 20, "len=" + f.length());

            Outcome full = list(f, fmt, null);
            check("tar." + ext + ": unstopped, it lists first.txt, big.bin and last.txt (" + full.ms + " ms)", full.error == null && full.names().equals(Arrays.asList("first.txt", "big.bin", "last.txt")), full.msg());

            // 1. stops by itself once a megabyte of data went by: exactly where, and how often it asks
            List<String> before = names(root);
            List<String> tmpBefore = names(new File(System.getProperty("java.io.tmpdir")));
            int fdBefore = openFds();
            Probe p1 = new Probe(1 << 20);
            Outcome o1 = list(f, fmt, p1);
            check("tar." + ext + ": a callback that says stop ends the listing with IOException(\"Cancelled\")", o1.cancelled(), o1.msg());
            check("tar." + ext + ": ... within a small margin (" + o1.ms + " ms; the unstopped listing took " + full.ms + " ms)", o1.ms < 1500 && (full.ms < 1500 || o1.ms * 3 < full.ms), "stopped " + o1.ms + " unstopped " + full.ms);
            check("tar." + ext + ": ... right after the asked-for point (last ask at " + p1.last + " bytes, stop was at " + (1 << 20) + ")", p1.last >= 1 << 20 && p1.last <= (1 << 20) + ArchiveIo.POLL_BYTES, "last=" + p1.last);
            check("tar." + ext + ": ... and it was asked at least every 64 KB of data (largest step " + p1.maxStep + " bytes over " + p1.asks + " asks)", p1.maxStep <= ArchiveIo.POLL_BYTES && p1.asks >= (1 << 20) / ArchiveIo.POLL_BYTES, "maxStep=" + p1.maxStep + " asks=" + p1.asks);
            check("tar." + ext + ": ... leaving no file behind (next to it, in the temp folder) and no file open", names(root).equals(before) && names(new File(System.getProperty("java.io.tmpdir"))).equals(tmpBefore) && openFds() == fdBefore,
                    "fds " + fdBefore + " -> " + openFds());

            // 2. stopped from another thread, as the page's Cancel does: a flag that is set a moment after the start
            final Probe p2 = new Probe(-1);
            Thread canceller = new Thread() { public void run() { try { Thread.sleep(150); } catch (InterruptedException e) { return; } p2.stop = true; } };
            long t0 = System.nanoTime();
            canceller.start();
            Outcome o2 = list(f, fmt, p2);
            canceller.join();
            long sinceFlag = o2.ms - 150;
            check("tar." + ext + ": a flag set from another thread after 150 ms stops it (Cancelled, " + Math.max(sinceFlag, 0) + " ms after the flag)", o2.cancelled() && sinceFlag < 1000, o2.msg() + " total " + o2.ms + " ms");

            // 3. still fine afterwards
            Outcome again = list(f, fmt, null);
            check("tar." + ext + ": after a stop the same file lists in full again", again.error == null && again.names().size() == 3, again.msg());
        }
    }

    // ------------------------------------------------------------------------------------------------------------ source scan

    static File srcDir() {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
            File f = new File(d, "src/com/bloatware/bingblop");
            if (f.isDirectory()) return f;
        }
        return null;
    }

    /** The text from the line that holds {@code sig} to the next method of the same or lower indentation (null if not exactly one). */
    static String bodyOf(String src, String sig) {
        int i = src.indexOf(sig);
        if (i < 0 || src.indexOf(sig, i + 1) >= 0) return null;
        int e = -1;
        for (String stop : new String[] {"\n        @JavascriptInterface", "\n        private ", "\n    private ", "\n    /**"}) {
            int q = src.indexOf(stop, i + sig.length());
            if (q >= 0 && (e < 0 || q < e)) e = q;
        }
        return e < 0 ? src.substring(i) : src.substring(i, e);
    }

    /** Whether position {@code at} of {@code body} lies inside a {@code synchronized (archiveLock) { ... }} block. */
    static boolean insideLock(String body, int at) {
        java.util.ArrayDeque<Boolean> stack = new java.util.ArrayDeque<Boolean>();
        for (int i = 0; i < at; i++) {
            char c = body.charAt(i);
            if (c == '{') {
                int s = Math.max(0, i - 40);
                stack.push(body.substring(s, i).replaceAll("\\s+", " ").trim().endsWith("synchronized (archiveLock)"));
            } else if (c == '}' && !stack.isEmpty()) stack.pop();
        }
        for (boolean b : stack) if (b) return true;
        return false;
    }

    static void sourceScan() throws Exception {
        File sdir = srcDir();
        if (sdir == null) { System.out.println("SKIP source scan: src/com/bloatware/bingblop not found"); return; }
        String m = new String(Files.readAllBytes(new File(sdir, "MainActivity.java").toPath()), StandardCharsets.UTF_8);
        String af = bodyOf(m, "private ZipTool.Archive archiveFor(String path, boolean fresh, char[] password, ArchiveIo.Progress cb)");
        check("source: archiveFor (the one that opens) is found", af != null, null);
        if (af != null) {
            int open = af.indexOf("openAnyArchive(");
            int stage = af.indexOf("stageArchive(");
            int put = af.indexOf("archiveSlots.put(");
            check("source: archiveFor does not hold archiveLock while it unpacks the archive (openAnyArchive)", open > 0 && !insideLock(af, open), null);
            check("source: ... nor while it copies a file out through the shell (stageArchive)", stage > 0 && !insideLock(af, stage), null);
            check("source: ... it takes the lock only to publish into archiveSlots", put > 0 && insideLock(af, put), null);
            int newest = af.indexOf("newest.longValue() != ticket");
            check("source: ... and checks it is still the newest open of that archive first (a stale open never publishes over a newer one)", newest > 0 && newest < put && af.contains("if (newest == null || newest.longValue() != ticket ||") && af.contains("throw new IOException(\"Cancelled\")"), null);
            check("source: ... a discarded open removes its staged copy", af.contains("if (!published && stagedPath != null) deleteStagedFiles("), null);
            check("source: ... and it asks the callback once more just before publishing (a Cancel that came last still wins)", af.contains("cb != null && !cb.tick(0)"), null);
        }
        String any = bodyOf(m, "private String archiveOpenAny(");
        check("source: archiveOpen answers pending for a slow format and runs it on a worker thread, with a token",
                any != null && any.contains("archiveOpenIsSlow(") && any.contains("submitJob(") && any.contains("\"pending\", true") && any.contains("\"token\"") && any.contains("window.onArchiveOpened"), null);
        check("source: the worker passes the page's cancel flag, and its answer carries cancelled:true after a Cancel", any != null && any.contains("if (job.cancel) return false;") && any.contains("\"cancelled\", true"), null);
        check("source: a fast format is still answered in the call (archiveFor then archiveOpenJson)", any != null && any.indexOf("archiveOpenJson(a, p).toString()") > 0, null);
        check("source: archiveOpen and archiveOpen2 both go through it", m.contains("return archiveOpenAny(path, null);") && m.contains("return archiveOpenAny(path, password == null"), null);
        String cancel = bodyOf(m, "public void archiveOpenCancel()");
        check("source: archiveOpenCancel is a bridge method that sets the running open's flag", cancel != null && cancel.contains("archiveOpening.cancel = true") && m.contains("@JavascriptInterface\n        public void archiveOpenCancel()"), null);
        String close = bodyOf(m, "public void archiveClose()");
        check("source: Close cancels a running open and clears the tickets, so nothing publishes after it", close != null && close.contains("archiveOpening.cancel = true") && close.contains("archiveOpenNewest.clear()"), null);
        String rel = bodyOf(m, "public void archiveRelease(String path)");
        check("source: Release of one archive drops its ticket too", rel != null && rel.contains("archiveOpenNewest.remove(p)"), null);
        String drop = bodyOf(m, "private void dropSlot(String canonicalPath)");
        check("source: dropSlot drops its ticket too", drop != null && drop.contains("archiveOpenNewest.remove(canonicalPath)"), null);
        for (String sig : new String[] {"public String archiveList(", "public String archiveRead(", "public String archiveSetPassword("}) {
            String b = bodyOf(m, sig);
            check("source: " + sig.replace("public String ", "") + " (on the bridge thread) can give up a long re-listing in words", b != null && b.contains("archiveForUi(path)") && !b.contains("archiveFor(path, false)"), null);
        }
        String ui = bodyOf(m, "private ZipTool.Archive archiveForUi(String path)");
        check("source: archiveForUi is bounded by a time limit and says \"too big to list here: extract it instead\"", ui != null && ui.contains("ARCHIVE_LIST_LIMIT_MS") && m.contains("This archive is too big to list here: extract it instead"), null);
        String ex = bodyOf(m, "public String archiveExtract2(");
        check("source: an extraction's own opening of the archive stops with its Cancel", ex != null && ex.contains("archiveFor(path, false, null, new ArchiveIo.Progress()") && ex.contains("return !archiveCancel;"), null);

        File html = new File(sdir.getParentFile().getParentFile().getParentFile().getParentFile(), "assets/index.html");
        if (!html.isFile()) { System.out.println("SKIP page scan: assets/index.html not found"); return; }
        String h = new String(Files.readAllBytes(html.toPath()), StandardCharsets.UTF_8);
        check("page: onArchiveOpened exists (the name the app calls)", h.contains("function onArchiveOpened("), null);
        check("page: no call to archiveOpen / archiveOpen2 bypasses the pending-aware helper", !h.contains("arcBridge('archiveOpen") && !h.contains("AndroidBridge.archiveOpen(") && !h.contains("AndroidBridge.archiveOpen2("), null);
        check("page: Cancel calls archiveOpenCancel", h.contains("window.AndroidBridge.archiveOpenCancel()"), null);
        check("page: leaving the File Manager and closing the archive view cancel an open that is waiting", h.contains("viewName !== 'files' && typeof arcOpenAbort === 'function') arcOpenAbort()") && h.contains("haptic();\n            arcOpenAbort();"), null);
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
