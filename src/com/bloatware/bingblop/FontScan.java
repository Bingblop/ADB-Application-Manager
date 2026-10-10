package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds font files (.ttf, .otf) on storage for the font setting of the app, and reads a font's family and style names from its "name" table.
 *
 * The walk is the one of {@link ApkScan}: bounded in depth, files, time and folders visited, one top-level folder at a time so a progress bar can
 * count them, symlinked folders not followed. Pure java.io / org.json so it can be tested off-device.
 */
public final class FontScan {
    private FontScan() {}

    /** The extensions the page can use as a web font. */
    public static final String[] EXTS = { ".ttf", ".otf" };
    /** A font file bigger than this is not offered (the page gets the font as base64 through the bridge). */
    public static final long MAX_FONT_BYTES = 12L * 1024 * 1024;
    /** A stream that takes longer than this to deliver a font (a stalled cloud provider) is given up on. */
    public static final long READ_TIMEOUT_MS = 30000;

    /** One font file found on storage. */
    public static final class Entry {
        public String path;
        public String name;       // file name
        public String kind;       // ttf | otf
        public String family;     // from the font itself, or the file name without its extension when the font has none
        public String style;      // "Regular", "Bold Italic" ... or ""
        public boolean variable;  // has an fvar table: one file, many weights
        public long size;
        public long mtime;
    }

    /** What a font file says about itself. */
    public static final class Names {
        public String family = "";
        public String style = "";
        public String full = "";
        public String kind = "ttf";                  // "otf" when the outlines are CFF (an 'OTTO' font), else "ttf"
        public boolean variable;
    }

    /** Limits that keep a scan from running away on a big or pathological storage tree. */
    public static final class Limits {
        public int maxDepth = 12;
        public int maxResults = 1500;
        public int maxVisited = 600000;
        public long deadlineMs;                      // absolute System.currentTimeMillis() cutoff
        public boolean hitLimit;                     // set when any limit stopped the scan early
        int visited;
    }

    /** Told after each top-level folder of a storage root has been searched: how many are done of how many, which one, and how many fonts were found so far. */
    public interface Progress {
        void onProgress(int done, int total, String folder, int found);
    }

    /** "ttf" / "otf" for a file name, or null for anything else. */
    public static String kindOf(String name) {
        if (name == null) return null;
        String low = name.toLowerCase(Locale.US);
        for (String ext : EXTS) {
            // "._x.ttf" is a macOS resource-fork stub, ".trashed-<time>-x.ttf" a file Android moved to its trash
            if (low.endsWith(ext) && low.length() > ext.length() && !low.startsWith("._") && !low.startsWith(".trashed")) return ext.substring(1);
        }
        return null;
    }

    private static boolean skipDir(String name) {
        return name.startsWith(".trashed") || name.equals(".thumbnails") || name.equals(".Trash") || name.equals(".trash") || name.equals(ApkTrash.DIR);
    }

    private static boolean isSymlink(File d) {
        try {
            return !d.getAbsolutePath().equals(d.getCanonicalPath());
        } catch (Exception e) {
            return true;
        }
    }

    /** How many top-level folders of these roots {@link #walkRoot} will go through: the unit the search's progress is counted in. */
    public static int countTopFolders(List<File> roots) {
        int n = 0;
        for (File r : roots) {
            File[] kids = r == null ? null : r.listFiles();
            if (kids == null) continue;
            for (File k : kids) if (k.isDirectory() && !skipDir(k.getName())) n++;
        }
        return n;
    }

    private static void addIfFont(File k, List<Entry> out, Set<String> seen) {
        String n = k.getName();
        String kind = kindOf(n);
        if (kind == null) return;
        long len = k.length();
        if (len <= 0 || len > MAX_FONT_BYTES) return;
        String path = k.getAbsolutePath();
        if (seen.contains(path)) return;
        Names names = readNames(k);
        if (names == null) return;                          // a file called .ttf that is not a font
        seen.add(path);
        Entry e = new Entry();
        e.path = path;
        e.name = n;
        e.kind = kind;
        e.size = len;
        e.mtime = k.lastModified();
        e.variable = names.variable;
        e.family = names.family.isEmpty() ? n.substring(0, n.length() - 4) : names.family;
        e.style = names.style;
        out.add(e);
    }

    /**
     * Searches a whole root, one top-level folder at a time, and tells {@code progress} after each one ({@code doneBefore} folders of
     * {@code total} were done before this root). Fonts lying directly in the root are added first. Returns how many top-level folders were gone through.
     */
    public static int walkRoot(File root, List<Entry> out, Set<String> seen, Limits lim, int doneBefore, int total, Progress progress) {
        if (root == null || lim.hitLimit) return 0;
        if (System.currentTimeMillis() > lim.deadlineMs) { lim.hitLimit = true; return 0; }
        File[] kids = root.listFiles();
        if (kids == null) return 0;
        for (File k : kids) {
            if (lim.hitLimit) return 0;
            if (out.size() >= lim.maxResults || ++lim.visited > lim.maxVisited) { lim.hitLimit = true; return 0; }
            if (k.isFile()) addIfFont(k, out, seen);
        }
        int done = 0;
        for (File k : kids) {
            if (lim.hitLimit) break;
            if (!k.isDirectory() || skipDir(k.getName())) continue;
            if (!isSymlink(k)) walk(k, 1, out, seen, lim);
            done++;
            if (progress != null) progress.onProgress(doneBefore + done, total, k.getName(), out.size());
        }
        return done;
    }

    /** Walks {@code root} for fonts this app can read. Folders that can't be listed are skipped silently; symlinked folders are not followed (no loops). */
    public static void walk(File root, int depth, List<Entry> out, Set<String> seen, Limits lim) {
        if (root == null || lim.hitLimit) return;
        if (System.currentTimeMillis() > lim.deadlineMs) { lim.hitLimit = true; return; }
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (lim.hitLimit) return;
            if (out.size() >= lim.maxResults || ++lim.visited > lim.maxVisited) { lim.hitLimit = true; return; }
            if (k.isDirectory()) {
                if (depth >= lim.maxDepth || skipDir(k.getName())) continue;
                if (isSymlink(k)) continue;
                walk(k, depth + 1, out, seen, lim);
            } else {
                addIfFont(k, out, seen);
            }
        }
    }

    /** By family, then style (Regular first), then file name. */
    public static void sort(List<Entry> list) {
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = a.family.compareToIgnoreCase(b.family);
                if (c != 0) return c;
                boolean ar = a.style.isEmpty() || a.style.equalsIgnoreCase("Regular"), br = b.style.isEmpty() || b.style.equalsIgnoreCase("Regular");
                if (ar != br) return ar ? -1 : 1;
                c = a.style.compareToIgnoreCase(b.style);
                return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    public static JSONObject toJson(Entry e) {
        JSONObject o = new JSONObject();
        try {
            o.put("path", e.path);
            o.put("name", e.name);
            o.put("kind", e.kind);
            o.put("family", e.family);
            o.put("style", e.style);
            o.put("variable", e.variable);
            o.put("size", e.size);
            o.put("mtime", e.mtime);
        } catch (Exception ignored) {}
        return o;
    }

    // ---------------------------------------------------------------------------------------------
    // The font file: sfnt header, table directory, "name" table
    // ---------------------------------------------------------------------------------------------

    private static final int TAG_TTCF = 0x74746366, TAG_OTTO = 0x4F54544F, TAG_TRUE = 0x74727565, TAG_TYP1 = 0x74797031;
    private static final int TAG_NAME = 0x6E616D65, TAG_GLYF = 0x676C7966, TAG_CFF = 0x43464620, TAG_CFF2 = 0x43464632, TAG_FVAR = 0x66766172;

    private static int u16(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }
    private static int u32(byte[] b, int o) { return (u16(b, o) << 16) | u16(b, o + 2); }
    private static long ul32(byte[] b, int o) { return u32(b, o) & 0xFFFFFFFFL; }

    /** What the font at {@code f} says about itself, or null when it is not a TrueType / OpenType font (or is damaged). A collection answers for its first font. */
    public static Names readNames(File f) {
        RandomAccessFile r = null;
        try {
            r = new RandomAccessFile(f, "r");
            long len = r.length();
            if (len < 12 || len > MAX_FONT_BYTES) return null;
            byte[] head = new byte[12];
            r.readFully(head);
            long base = 0;
            if (u32(head, 0) == TAG_TTCF) {                                        // a collection: header, version, count, then the offset of each font
                byte[] ttc = new byte[16];
                r.seek(0);
                r.readFully(ttc);
                if (u32(ttc, 8) < 1) return null;
                base = ul32(ttc, 12);
                if (base < 16 || base + 12 > len) return null;
                r.seek(base);
                r.readFully(head);
            }
            int tag = u32(head, 0);
            if (tag != 0x00010000 && tag != TAG_OTTO && tag != TAG_TRUE && tag != TAG_TYP1) return null;
            int numTables = u16(head, 4);
            if (numTables <= 0 || numTables > 256 || base + 12 + numTables * 16L > len) return null;
            byte[] dir = new byte[numTables * 16];
            r.seek(base + 12);
            r.readFully(dir);
            long nameOff = -1, nameLen = 0;
            boolean outlines = false, variable = false;
            for (int i = 0; i < numTables; i++) {
                int t = u32(dir, i * 16);
                long off = ul32(dir, i * 16 + 8), l = ul32(dir, i * 16 + 12);
                if (off + l > len) { if (t == TAG_NAME) return null; continue; }   // a table that runs past the end of the file
                if (t == TAG_NAME) { nameOff = off; nameLen = l; }
                else if (t == TAG_GLYF || t == TAG_CFF || t == TAG_CFF2) outlines = true;
                else if (t == TAG_FVAR) variable = true;
            }
            if (nameOff < 0 || !outlines || nameLen < 6 || nameLen > (1 << 20)) return null;
            byte[] nm = new byte[(int) nameLen];
            r.seek(nameOff);
            r.readFully(nm);
            Names n = parseName(nm);
            n.variable = variable;
            n.kind = tag == TAG_OTTO ? "otf" : "ttf";
            return n;
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    /** The family, style and full name out of the bytes of a "name" table. The best record of each name wins: Windows English, then any Windows, Unicode, Mac. */
    public static Names parseName(byte[] nm) {
        Names n = new Names();
        if (nm == null || nm.length < 6) return n;
        int count = u16(nm, 2), strOff = u16(nm, 4);
        String[] best = new String[19];
        int[] score = new int[19];
        for (int i = 0; i < count; i++) {
            int rec = 6 + i * 12;
            if (rec + 12 > nm.length) break;
            int platform = u16(nm, rec), encoding = u16(nm, rec + 2), lang = u16(nm, rec + 4), id = u16(nm, rec + 6), len = u16(nm, rec + 8), off = u16(nm, rec + 10);
            if (id != 1 && id != 2 && id != 4 && id != 16 && id != 17) continue;
            int s;
            if (platform == 3 && (encoding == 1 || encoding == 10)) s = lang == 0x0409 ? 100 : 60;
            else if (platform == 0) s = 50;
            else if (platform == 1 && encoding == 0) s = lang == 0 ? 40 : 20;
            else continue;
            int from = strOff + off;
            if (len <= 0 || from < 0 || from + len > nm.length || s <= score[id]) continue;
            String v = platform == 1 ? new String(nm, from, len, Charset.forName("ISO-8859-1")) : new String(nm, from, len, Charset.forName("UTF-16BE"));
            v = v.trim();
            if (v.isEmpty() || v.indexOf('\u0000') >= 0) continue;
            best[id] = v;
            score[id] = s;
        }
        n.family = best[16] != null ? best[16] : best[1] != null ? best[1] : "";
        n.style = best[17] != null ? best[17] : best[2] != null ? best[2] : "";
        n.full = best[4] != null ? best[4] : (n.family + (n.style.isEmpty() ? "" : " " + n.style)).trim();
        return n;
    }

    /**
     * Copies a font from {@code in} to {@code dest} after checking it: at most {@link #MAX_FONT_BYTES}, a TrueType / OpenType font (not a
     * collection, which a page cannot use). Written to a ".part" file first, so a refused file never replaces a good one. Returns what the font
     * says about itself; the exception message is fit to show to the user. {@code in} is not closed.
     */
    public static Names copyChecked(java.io.InputStream in, File dest) throws java.io.IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        java.io.OutputStream out = new java.io.FileOutputStream(tmp);
        long total = 0;
        boolean ok = false;
        try {
            byte[] buf = new byte[65536];
            int n;
            long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_FONT_BYTES) throw new java.io.IOException("That file is too big to use as a font (the limit is 12 MB).");
                if (System.currentTimeMillis() > deadline) throw new java.io.IOException("The file took too long to read.");
                out.write(buf, 0, n);
            }
            ok = true;
        } finally {
            try { out.close(); } catch (Exception ignored) {}
            if (!ok) tmp.delete();
        }
        if (total >= 4) {
            byte[] h = new byte[4];
            RandomAccessFile r = new RandomAccessFile(tmp, "r");
            try { r.readFully(h); } finally { r.close(); }
            if (u32(h, 0) == TAG_TTCF) { tmp.delete(); throw new java.io.IOException("A font collection (.ttc) cannot be used here. Choose a single .ttf or .otf file."); }
        }
        Names names = readNames(tmp);
        if (names == null) { tmp.delete(); throw new java.io.IOException("That file is not a TrueType or OpenType font."); }
        if (!tmp.renameTo(dest)) {                                                    // renameTo replaces the old file on Android; the delete is for a file system that does not
            if (dest.exists() && !dest.delete()) { tmp.delete(); throw new java.io.IOException("Could not replace the stored font."); }
            if (!tmp.renameTo(dest)) { tmp.delete(); throw new java.io.IOException("Could not store the font."); }
        }
        return names;
    }

    /** A name that is safe to use as a CSS font-family and in a file name: letters, digits, space, dash. Never empty. */
    public static String cssName(String family) {
        String s = family == null ? "" : family.replaceAll("[^\\p{L}\\p{N} _-]", "").replaceAll(" {2,}", " ").trim();
        return s.isEmpty() ? "Custom font" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** All the fonts under these roots, found and sorted; {@code lim.hitLimit} says whether a limit stopped the walk. */
    public static List<Entry> scan(List<File> roots, Limits lim, Progress progress) {
        List<Entry> out = new ArrayList<Entry>();
        Set<String> seen = new java.util.HashSet<String>();
        int total = countTopFolders(roots), done = 0;
        for (File r : roots) done += walkRoot(r, out, seen, lim, done, total, progress);
        sort(out);
        return out;
    }
}
