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

    /**
     * The folders of the app's data that may leave: its logs, its patched APKs and the Morphe Helper's downloads (not the Morphe folder itself: the
     * signing key and its password are in it), and the cache folders for files the user picked or downloaded. The rest of the cache
     * (backup_data_*.tar, restore_*, root scripts, temporary files) is the app's own working data and stays protected.
     */
    static File[] exportable(File filesDir, File cacheDir) {
        return new File[] {
            new File(filesDir, "logs"), new File(filesDir, "morphe/patched"), new File(filesDir, "morphe/helper"),
            new File(cacheDir, "updates"), new File(cacheDir, "installer"), new File(cacheDir, "store"), new File(cacheDir, "saf_stage"),
            new File(cacheDir, "cd_pick"), new File(cacheDir, "morphe_pick"), new File(cacheDir, "share"),
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
    static boolean blocked(File f, File dataDir, Links links, File... allowed) {
        if (f == null || dataDir == null) return true;
        try {
            File data = dataDir.getCanonicalFile();
            File file = f.getCanonicalFile();
            if (!inside(file, data)) return false;
            if (allowed != null) for (File a : allowed) {
                if (a == null) continue;
                File root = genuineRoot(a, dataDir, data);
                if (root == null || !inside(file, root)) continue;
                if (file.isFile()) {
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
