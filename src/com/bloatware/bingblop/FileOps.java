package com.bloatware.bingblop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Copy, move and delete of files and folders with the app's own file access (no shell), with a rule for a name that is already
 * there: replace it, skip it, or keep both ("name (1).ext"). Pure Java, so the rules run in the desk tests.
 *
 * A file is copied to a temporary name next to its target and renamed when it is whole, so a failed or cancelled copy never leaves a
 * half file under the real name and never costs the file it was going to replace. A folder that meets a folder merges with it.
 * Symbolic links are copied as links and never followed, so a loop or a link out of the tree cannot pull in the rest of the phone.
 */
public final class FileOps {
    private FileOps() {}

    public static final int REPLACE = 0, SKIP = 1, KEEP_BOTH = 2;

    /** Called now and then while a job runs; return false to stop it. */
    public interface Progress {
        boolean onProgress(String name, long bytesDone, int itemsDone);
    }

    public static final class Result {
        public int done;                                   // items finished (a folder counts once, with what is in it)
        public int skipped;                                // items left alone because the name was taken and the rule said skip
        public long bytes;                                 // bytes copied (a move by rename copies none)
        public boolean cancelled;
        public final List<String[]> failed = new ArrayList<String[]>();   // {path, reason}
    }

    public static int policyOf(String s) {
        if ("skip".equals(s)) return SKIP;
        if ("keep".equals(s) || "both".equals(s)) return KEEP_BOTH;
        return REPLACE;
    }

    // ---------------------------------------------------------------------------------------------
    // names
    // ---------------------------------------------------------------------------------------------

    private static final String[] COMPOUND = {".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.z", ".tar.lz", ".tar.lzma"};

    /** Where the extension of a name starts, or the length when it has none: "a.txt" 1, "a.tar.gz" 1, ".bashrc" and "README" have none. */
    static int extStart(String name) {
        String low = name.toLowerCase(java.util.Locale.US);
        for (String c : COMPOUND) if (low.endsWith(c) && name.length() > c.length()) return name.length() - c.length();
        int i = name.lastIndexOf('.');
        return i > 0 ? i : name.length();
    }

    /** A name for {@code name} that is free in {@code dir}: the name itself, else "stem (1).ext", "stem (2).ext" ... */
    public static String uniqueName(File dir, String name) {
        if (!exists(new File(dir, name))) return name;
        int e = extStart(name);
        String stem = name.substring(0, e), ext = name.substring(e);
        for (int i = 1; i < 100000; i++) {
            String n = stem + " (" + i + ")" + ext;
            if (!exists(new File(dir, n))) return n;
        }
        return stem + " (" + System.currentTimeMillis() + ")" + ext;
    }

    /** exists() says false for a link whose target is gone; such a link still holds the name. */
    static boolean exists(File f) {
        return f.exists() || Files.isSymbolicLink(f.toPath());
    }

    /**
     * A shell script that does the same naming for a copy or move done by a shell (a privileged mode, for folders the app itself cannot
     * write): runs {@code verb} ("cp -r" or "mv") with the source and a free target in {@code destDir}, honours the rule, and prints
     * FMOK (done) or FMSKIP (left alone). Arguments are quoted by {@link BackupScripts#quote}.
     */
    public static String shellScript(String verb, String src, String destDir, int policy) {
        String qs = BackupScripts.quote(src), qd = BackupScripts.quote(destDir);
        StringBuilder s = new StringBuilder();
        s.append("d=").append(qd).append("; s=").append(qs).append("; b=\"${s##*/}\"; t=\"$d/$b\"; ");
        if (policy == SKIP) {
            s.append("if [ -e \"$t\" ] || [ -L \"$t\" ]; then echo FMSKIP; else mkdir -p \"$d\" && ").append(verb).append(" \"$s\" \"$t\" && echo FMOK; fi");
        } else if (policy == KEEP_BOTH) {
            s.append("case \"$b\" in ")
                    .append("*.tar.gz|*.tar.bz2|*.tar.xz|*.tar.zst|*.tar.lz4) x=\".tar.${b##*.tar.}\"; n=\"${b%$x}\";; ")
                    .append("?*.*) x=\".${b##*.}\"; n=\"${b%.*}\";; ")
                    .append("*) x=\"\"; n=\"$b\";; esac; ")
                    .append("i=1; while [ -e \"$t\" ] || [ -L \"$t\" ]; do t=\"$d/$n ($i)$x\"; i=$((i+1)); done; ")
                    .append("mkdir -p \"$d\" && ").append(verb).append(" \"$s\" \"$t\" && echo FMOK");
        } else {
            s.append("mkdir -p \"$d\" && ").append(verb).append(" \"$s\" \"$d/\" && echo FMOK");
        }
        return s.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // copy / move / delete
    // ---------------------------------------------------------------------------------------------

    private static final class Ctx {
        final int policy;
        final Progress progress;
        final Result r;
        long lastTick, lastBytes;
        Ctx(int policy, Progress progress, Result r) { this.policy = policy; this.progress = progress; this.r = r; }
        boolean tick(String name, boolean force) {
            if (progress == null) return true;
            long now = System.currentTimeMillis();
            if (!force && now - lastTick < 150 && r.bytes - lastBytes < 2L * 1024 * 1024) return !r.cancelled;     // often enough for a quick answer to Cancel, on a fast disk too
            lastTick = now;
            lastBytes = r.bytes;
            if (!progress.onProgress(name, r.bytes, r.done)) r.cancelled = true;
            return !r.cancelled;
        }
    }

    /** Copies each of {@code sources} into {@code destDir}. */
    public static Result copy(List<File> sources, File destDir, int policy, Progress progress) {
        return run(sources, destDir, policy, progress, false);
    }

    /** Moves each of {@code sources} into {@code destDir}: a rename when it can be, else a copy and then a delete of the original. */
    public static Result move(List<File> sources, File destDir, int policy, Progress progress) {
        return run(sources, destDir, policy, progress, true);
    }

    private static Result run(List<File> sources, File destDir, int policy, Progress progress, boolean move) {
        Result r = new Result();
        Ctx c = new Ctx(policy, progress, r);
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            for (File s : sources) r.failed.add(new String[]{s.getPath(), "The destination folder could not be created"});
            return r;
        }
        for (File s : sources) {
            if (r.cancelled) break;
            try {
                if (transfer(s, destDir, c, move)) r.done++;
            } catch (IOException e) {
                r.failed.add(new String[]{s.getPath(), msg(e)});
            }
            c.tick(s.getName(), true);
        }
        return r;
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }

    private static boolean isLink(File f) { return Files.isSymbolicLink(f.toPath()); }

    private static boolean sameFile(File a, File b) {
        try { return a.getCanonicalPath().equals(b.getCanonicalPath()); } catch (IOException e) { return false; }
    }

    private static boolean inside(File dir, File tree) {                 // is dir the tree itself or inside it
        try {
            String d = dir.getCanonicalPath(), t = tree.getCanonicalPath();
            return d.equals(t) || d.startsWith(t.endsWith("/") ? t : t + "/");
        } catch (IOException e) { return false; }
    }

    /** One item. Returns true when it was done, false when it was skipped (counted in the result); throws when it failed. */
    private static boolean transfer(File src, File destDir, Ctx c, boolean move) throws IOException {
        if (!exists(src)) throw new IOException("No such file or folder");
        if (!isLink(src) && src.isDirectory() && inside(destDir, src)) throw new IOException("A folder cannot go inside itself");
        File target = new File(destDir, src.getName());
        boolean there = exists(target);
        if (there && sameFile(src, target)) {
            if (c.policy != KEEP_BOTH) { c.r.skipped++; return false; }        // the same file on both sides: nothing to copy or move onto itself
            if (move) { c.r.skipped++; return false; }
        }
        if (there) {
            boolean bothDirs = !isLink(src) && src.isDirectory() && !isLink(target) && target.isDirectory();
            if (c.policy == SKIP && !bothDirs) { c.r.skipped++; return false; }          // two folders merge even then: only the names that are taken are left alone
            if (c.policy == KEEP_BOTH) { target = new File(destDir, uniqueName(destDir, src.getName())); there = false; }
        }
        boolean srcDir = !isLink(src) && src.isDirectory();
        if (there && srcDir != (!isLink(target) && target.isDirectory()))
            throw new IOException(srcDir ? "A file with that name is in the way" : "A folder with that name is in the way");
        if (move && !there) {
            if (src.renameTo(target)) return true;                              // the quick way: same volume
        }
        if (move && there && srcDir) {                                          // a folder moved onto a folder: item by item, so what is left alone stays in the source
            File[] kids = src.listFiles();
            if (kids == null) throw new IOException("The folder could not be read");
            IOException first = null;
            for (File k : kids) {
                if (c.r.cancelled) return false;
                try { transfer(k, target, c, true); } catch (IOException e) { if (first == null) first = new IOException(k.getName() + ": " + msg(e)); }
            }
            if (first != null) throw first;
            src.delete();                                                        // only goes when nothing was left behind
            return true;
        }
        copyTree(src, target, c, there);
        if (c.r.cancelled) return false;
        if (move) deleteTree(src, c.r, true);
        return true;
    }

    /** Copies src to exactly target. {@code merge}: target is a folder that is already there. */
    private static void copyTree(File src, File target, Ctx c, boolean merge) throws IOException {
        if (c.r.cancelled) return;
        if (isLink(src)) {
            File dir = target.getParentFile();
            if (exists(target)) deleteTree(target, c.r, false);
            try {
                Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(src.toPath()));
            } catch (IOException | UnsupportedOperationException e) {
                throw new IOException("The link could not be copied");
            }
            if (dir != null) dir.setLastModified(dir.lastModified());
            return;
        }
        if (src.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("The folder could not be created");
            File[] kids = src.listFiles();
            if (kids == null) throw new IOException("The folder could not be read");
            IOException first = null;
            for (File k : kids) {
                if (c.r.cancelled) return;
                File t = new File(target, k.getName());
                try {
                    boolean exists = exists(t);
                    if (exists && c.policy == SKIP) { c.r.skipped++; continue; }
                    if (exists && c.policy == KEEP_BOTH) { t = new File(target, uniqueName(target, k.getName())); exists = false; }
                    if (exists && !isLink(k) && k.isDirectory() != (!isLink(t) && t.isDirectory()))
                        throw new IOException(k.isDirectory() ? "A file with that name is in the way" : "A folder with that name is in the way");
                    copyTree(k, t, c, exists && !isLink(k) && k.isDirectory());
                } catch (IOException e) {
                    if (first == null) first = new IOException(k.getName() + ": " + msg(e));
                }
            }
            if (first != null) throw first;
            target.setLastModified(src.lastModified());
            return;
        }
        copyFile(src, target, c);
    }

    private static void copyFile(File src, File target, Ctx c) throws IOException {
        File dir = target.getParentFile();
        File tmp = new File(dir, "." + target.getName() + ".fmtmp");
        boolean ok = false;
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    c.r.bytes += n;
                    if (!c.tick(src.getName(), false)) return;
                }
            } finally { out.close(); }
            tmp.setLastModified(src.lastModified());
            if (exists(target) && !isLink(target) && target.isDirectory()) throw new IOException("A folder with that name is in the way");
            if (!tmp.renameTo(target)) {                                        // a file system that will not rename onto an existing file
                if (exists(target) && !target.delete()) throw new IOException("The existing file could not be replaced");
                if (!tmp.renameTo(target)) throw new IOException("The file could not be stored");
            }
            ok = true;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            if (!ok) tmp.delete();
        }
    }

    /** Deletes the paths; a folder goes with everything in it. A link is removed, never followed. */
    public static Result delete(List<File> paths, Progress progress) {
        Result r = new Result();
        Ctx c = new Ctx(REPLACE, progress, r);
        for (File f : paths) {
            if (r.cancelled) break;
            if (!exists(f)) { r.failed.add(new String[]{f.getPath(), "No such file or folder"}); continue; }
            int before = r.failed.size();
            deleteTree(f, r, false);
            if (r.failed.size() == before) r.done++;
            c.tick(f.getName(), true);
        }
        return r;
    }

    private static void deleteTree(File f, Result r, boolean quiet) {
        if (!isLink(f) && f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k, r, quiet);
        }
        if (!f.delete() && exists(f) && !quiet) r.failed.add(new String[]{f.getPath(), "Could not be deleted"});
    }

    // ---------------------------------------------------------------------------------------------
    // new files and folders, text
    // ---------------------------------------------------------------------------------------------

    /** A name a person typed for a file or folder: no slash, not empty, not "." or "..", no control characters. Null when fine, else why not. */
    public static String badName(String name) {
        if (name == null || name.trim().isEmpty()) return "Enter a name";
        if (name.equals(".") || name.equals("..")) return "That name is not allowed";
        if (name.indexOf('/') >= 0) return "A name cannot contain a slash";
        if (name.indexOf('\u0000') >= 0 || name.length() > 255) return "That name is not allowed";
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < 32) return "That name is not allowed";
        return null;
    }

    /**
     * Whether the first {@code len} bytes look like text: no NUL byte and valid UTF-8. A character cut off at the very end is fine when
     * {@code cut} says the bytes are only the start of a longer file.
     */
    public static boolean looksLikeText(byte[] b, int len, boolean cut) {
        int i = 0;
        while (i < len) {
            int x = b[i] & 0xFF;
            if (x == 0) return false;
            int need = x < 0x80 ? 0 : (x >= 0xC2 && x < 0xE0) ? 1 : (x >= 0xE0 && x < 0xF0) ? 2 : (x >= 0xF0 && x < 0xF5) ? 3 : -1;
            if (need < 0) return false;
            for (int k = 1; k <= need; k++) {
                if (i + k >= len) return cut;                                  // the sequence runs past the end
                if ((b[i + k] & 0xC0) != 0x80) return false;
            }
            i += need + 1;
        }
        return true;
    }

    /** Writes {@code data} to {@code f} through a temporary file, so a failed write keeps the old content. */
    public static void writeAtomic(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        File tmp = new File(dir, "." + f.getName() + ".fmtmp");
        boolean ok = false;
        try {
            OutputStream out = new FileOutputStream(tmp);
            try { out.write(data); } finally { out.close(); }
            if (!tmp.renameTo(f)) {
                if (f.exists() && !f.delete()) throw new IOException("The file could not be replaced");
                if (!tmp.renameTo(f)) throw new IOException("The file could not be stored");
            }
            ok = true;
        } finally {
            if (!ok) tmp.delete();
        }
    }
}
