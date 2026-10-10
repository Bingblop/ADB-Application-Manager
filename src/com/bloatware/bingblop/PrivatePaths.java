package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;

/**
 * Which files the bridge may hand to a share sheet, VirusTotal or an "Open with" chooser. The app's own data folder holds its settings, the sealed
 * secrets and the adb key; none of that is for a page to send anywhere. Only the parts the app itself fills for the user (its cache, logs and patched
 * APKs) may leave. Paths are resolved first (symlinks, "..", "."), so a link or a relative path cannot walk into the protected part; a path that
 * cannot be resolved counts as protected. Pure Java, no android.* classes.
 */
final class PrivatePaths {
    private PrivatePaths() {}

    /** A folder of the app's data that may leave, optionally only files with these extensions (lower case, no dot); none listed means any file. */
    static final class Root {
        final File dir;
        final java.util.Set<String> exts;
        Root(File dir, String... exts) {
            this.dir = dir;
            this.exts = new java.util.HashSet<String>(java.util.Arrays.asList(exts));
        }
        boolean accepts(File f) {
            if (exts.isEmpty()) return true;
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            int dot = n.lastIndexOf('.');
            return dot >= 0 && exts.contains(n.substring(dot + 1));
        }
    }

    /**
     * What may leave the app's data folder: only files the user picked or downloaded, or APKs the app made. Patched APKs (not the run's meta.json and
     * log.txt next to them) and the Morphe Helper's downloads (not the Morphe folder itself, which holds the signing key and its password), and the cache
     * folders for files the user picked or downloaded. The rest of the cache (backup_data_*.tar, restore_*, root scripts, temporary files and the store
     * catalog) and the app's logs are its own working data and stay protected.
     */
    static Root[] exportable(File filesDir, File cacheDir) {
        return new Root[] {
            new Root(new File(filesDir, "morphe/patched"), "apk"),
            new Root(new File(filesDir, "morphe/helper"), "apk", "apks", "apkm", "xapk"),
            new Root(new File(cacheDir, "updates")), new Root(new File(cacheDir, "installer")), new Root(new File(cacheDir, "saf_stage")),
            new Root(new File(cacheDir, "cd_pick")), new Root(new File(cacheDir, "morphe_pick")), new Root(new File(cacheDir, "share")),
        };
    }

    /** How many names a file has (its hard-link count); the caller supplies it because android.system.Os is not available here. */
    interface Links { long count(File f) throws Exception; }

    /**
     * True when {@code f} is inside {@code dataDir} and not inside one of the {@code allowed} sub-folders.
     * <ul>
     * <li>An allowed folder only counts when it really is where it should be: textually below {@code dataDir}, and its resolved location equal to the
     * resolved data folder plus the same relative path. A link put in its place (cache/share pointing at shared_prefs) makes it count for nothing, so
     * everything under it stays protected.</li>
     * <li>A regular file inside an allowed folder with more than one name is protected: a hard link made in the cache to a private file resolves to a
     * path in the allowed tree but is the same file as the private one. A link count that cannot be read counts as protected.</li>
     * </ul>
     */
    static boolean blocked(File f, File dataDir, Links links, Root... allowed) {
        if (f == null || dataDir == null) return true;
        try {
            File data = dataDir.getCanonicalFile();
            File file = f.getCanonicalFile();
            if (!inside(file, data)) return false;
            if (allowed != null) for (Root a : allowed) {
                if (a == null) continue;
                File root = genuineRoot(a.dir, dataDir, data);
                if (root == null || !inside(file, root)) continue;
                if (file.isFile()) {
                    if (!a.accepts(file)) continue;
                    if (links == null) return true;
                    if (links.count(file) != 1) return true;
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /** The resolved {@code a} when it is the real folder below the data folder (not a link elsewhere), otherwise null. */
    private static File genuineRoot(File a, File dataDir, File canonData) throws IOException {
        java.nio.file.Path ap = a.toPath().toAbsolutePath().normalize();
        java.nio.file.Path dp = dataDir.toPath().toAbsolutePath().normalize();
        if (!ap.startsWith(dp) || ap.equals(dp)) return null;
        File expected = new File(canonData, dp.relativize(ap).toString());
        File actual = a.getCanonicalFile();
        return actual.equals(expected) ? actual : null;
    }

    /** {@code f} is {@code dir} or below it (a whole path component, so "/data/x2" is not inside "/data/x"). */
    static boolean inside(File f, File dir) {
        String p = f.getPath(), d = dir.getPath();
        if (p.equals(d)) return true;
        if (!d.endsWith(File.separator)) d += File.separator;
        return p.startsWith(d);
    }
}
