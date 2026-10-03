package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds installable package files (.apk, .apks, .apkm, .xapk) on storage for the Installer tab.
 *
 * Two sources feed one list: a bounded walk of the folders this app can read itself, and - when a
 * privileged mode is active - the output of a shell `find` for the folders it can't (Android/data,
 * Android/obb, or everything when All-files access isn't granted). Pure java.io / org.json so the walk
 * and the parsers are unit-testable off-device.
 */
public final class ApkScan {
    private ApkScan() {}

    public static final String[] EXTS = { ".apk", ".apks", ".apkm", ".xapk" };

    /** One package file found on storage. */
    public static final class Entry {
        public String path;
        public String name;
        public String kind;      // apk | apks | apkm | xapk
        public long size;        // bytes, 0 when unknown
        public long mtime;       // epoch millis, 0 when unknown
        public boolean shell;    // true when only the shell can read it (needs staging before install)
    }

    /** Limits that keep a scan from running away on a big or pathological storage tree. */
    public static final class Limits {
        public int maxDepth = 14;
        public int maxResults = 2000;
        public int maxVisited = 600000;
        public long deadlineMs;                      // absolute System.currentTimeMillis() cutoff
        public boolean hitLimit;                     // set when any limit stopped the scan early
        int visited;
    }

    /** "apk" / "apks" / "apkm" / "xapk" for a file name, or null for anything else. */
    public static String kindOf(String name) {
        if (name == null) return null;
        String low = name.toLowerCase(Locale.US);
        for (String ext : EXTS) {
            if (low.endsWith(ext) && low.length() > ext.length()) return ext.substring(1);
        }
        return null;
    }

    private static boolean skipDir(String name) {
        return name.startsWith(".trashed") || name.equals(".thumbnails") || name.equals(".Trash") || name.equals(".trash");
    }

    /**
     * Walks {@code root} for package files this app can read. Directories that can't be listed are
     * skipped silently; symlinked directories are not followed (no loops).
     */
    public static void walk(File root, int depth, List<Entry> out, Set<String> seen, Limits lim) {
        if (root == null || lim.hitLimit) return;
        if (System.currentTimeMillis() > lim.deadlineMs) { lim.hitLimit = true; return; }
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (lim.hitLimit) return;
            if (out.size() >= lim.maxResults || ++lim.visited > lim.maxVisited) { lim.hitLimit = true; return; }
            String n = k.getName();
            if (k.isDirectory()) {
                if (depth >= lim.maxDepth || skipDir(n)) continue;
                try {
                    if (!k.getAbsolutePath().equals(k.getCanonicalPath())) continue; // symlink
                } catch (Exception e) { continue; }
                walk(k, depth + 1, out, seen, lim);
            } else {
                String kind = kindOf(n);
                if (kind == null) continue;
                long len = k.length();
                if (len <= 0) continue; // empty placeholder
                String path = k.getAbsolutePath();
                if (!seen.add(path)) continue;
                Entry e = new Entry();
                e.path = path;
                e.name = n;
                e.kind = kind;
                e.size = len;
                e.mtime = k.lastModified();
                out.add(e);
            }
        }
    }

    /** Absolute file paths from `find` output (one per line); blank and non-absolute lines are ignored. */
    public static List<String> parseFindOutput(String out, int max) {
        List<String> paths = new ArrayList<String>();
        if (out == null) return paths;
        for (String line : out.split("\n")) {
            String p = line.trim();
            if (p.isEmpty() || p.charAt(0) != '/') continue;
            if (kindOf(p.substring(p.lastIndexOf('/') + 1)) == null) continue;
            paths.add(p);
            if (paths.size() >= max) break;
        }
        return paths;
    }

    /** Parses `stat -c '%s|%Y|%n'` output into path -> {size, mtimeSeconds}. */
    public static Map<String, long[]> parseStatOutput(String out) {
        Map<String, long[]> m = new HashMap<String, long[]>();
        if (out == null) return m;
        for (String line : out.split("\n")) {
            int a = line.indexOf('|');
            int b = a < 0 ? -1 : line.indexOf('|', a + 1);
            if (a < 0 || b < 0) continue;
            try {
                long size = Long.parseLong(line.substring(0, a).trim());
                long mtime = Long.parseLong(line.substring(a + 1, b).trim());
                String path = line.substring(b + 1).trim();
                if (!path.isEmpty()) m.put(path, new long[]{ size, mtime });
            } catch (NumberFormatException ignored) {}
        }
        return m;
    }

    /** Newest first, then by name. */
    public static void sort(List<Entry> list) {
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.mtime != b.mtime) return a.mtime < b.mtime ? 1 : -1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    public static JSONObject toJson(Entry e) {
        JSONObject o = new JSONObject();
        try {
            o.put("path", e.path);
            o.put("name", e.name);
            o.put("kind", e.kind);
            o.put("size", e.size);
            o.put("mtime", e.mtime);
            o.put("shell", e.shell);
        } catch (Exception ignored) {}
        return o;
    }
}
