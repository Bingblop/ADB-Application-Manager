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

    /** True when {@code f} is inside {@code dataDir} and not inside one of the {@code allowed} sub-folders. */
    static boolean blocked(File f, File dataDir, File... allowed) {
        if (f == null || dataDir == null) return true;
        try {
            File data = dataDir.getCanonicalFile();
            File file = f.getCanonicalFile();
            if (!inside(file, data)) return false;
            if (allowed != null) for (File a : allowed) {
                if (a != null && inside(file, a.getCanonicalFile())) return false;
            }
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    /** {@code f} is {@code dir} or below it (a whole path component, so "/data/x2" is not inside "/data/x"). */
    static boolean inside(File f, File dir) {
        String p = f.getPath(), d = dir.getPath();
        if (p.equals(d)) return true;
        if (!d.endsWith(File.separator)) d += File.separator;
        return p.startsWith(d);
    }
}
