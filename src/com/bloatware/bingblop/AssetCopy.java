package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Copies a file the APK carries (rish, rish_shizuku.dex, the fallback adb, the uninstall runner) into the app's files, once per app version and
 * whole or not at all. The copy is written next to its place and renamed over it only when it is complete, and a stamp for the app version is
 * written after that: a copy cut short (the app was ended in the middle of it, the storage was full) is never taken for a good one, and a new app
 * version brings its own copy instead of keeping the first one for ever. No Android classes: the tests run it on a JVM.
 */
final class AssetCopy {

    /** Opens the asset; only called when a copy is needed. */
    interface Source {
        InputStream open() throws IOException;
    }

    private AssetCopy() {}

    /** The empty file that says {@code dest} is the whole copy made by app version {@code stamp}. */
    static File stampOf(File dest, String stamp) {
        return new File(dest.getAbsoluteFile().getParentFile(), "." + dest.getName() + ".v-" + stamp);
    }

    /** Whether {@code dest} is the whole copy of app version {@code stamp}, so nothing needs copying. */
    static boolean current(File dest, String stamp) {
        return dest.length() > 0 && stampOf(dest, stamp).isFile();
    }

    /** Copies {@code src} to {@code dest} unless it is current; returns whether it is current afterwards. A failure leaves dest as it was. */
    static boolean copy(Source src, File dest, String stamp) {
        if (current(dest, stamp)) return true;
        File dir = dest.getAbsoluteFile().getParentFile();
        File tmp = new File(dir, dest.getName() + ".tmp");
        try {
            InputStream in = src.open();
            try {
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.getFD().sync();                                  // on the storage before the rename makes it the copy in use
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (tmp.length() <= 0) return false;
            // the stamps go before the copy is replaced (a copy without a stamp is copied again), the new one only once it is in place
            String prefix = "." + dest.getName() + ".v-";
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (f.getName().startsWith(prefix)) f.delete();
            if (!tmp.renameTo(dest)) return false;                      // one step, and it also replaces a read-only copy (the folder is ours)
            new FileOutputStream(stampOf(dest, stamp)).close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            tmp.delete();                                                // gone after the rename; what is left of a failed copy is removed
        }
    }
}
