package com.bloatware.bingblop;

import android.content.Context;
import android.os.Build;


import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Which adb this build runs and what it needs around it. The 64-bit build of adb is one static file; the 32-bit build (v7a and universal APKs) is a
 * dynamic one that needs its libraries (assets/adb32libs.zip), unpacked once into the app's private folder and found through LD_LIBRARY_PATH.
 */
final class AdbRuntime {
    static final String LIBS_ZIP = "adb32libs.zip";
    static final String ASSET_64 = "libadb.so";
    static final String ASSET_32 = "libadb_v7a.so";

    private AdbRuntime() {}

    /** The asset the app falls back to when the native library folder holds no adb: the 64-bit one on a 64-bit phone, the 32-bit one otherwise. */
    static String assetName(String[] abis) {
        return AbiPick.is64(abis) ? ASSET_64 : ASSET_32;
    }

    static boolean is32BitPhone() {
        return !ASSET_64.equals(assetName(Build.SUPPORTED_ABIS));
    }

    static File libDir(Context ctx) {
        return new File(ctx.getFilesDir(), "adblib32");
    }

    /** The adb to run: the one Android unpacked into the native library folder, else the extracted fallback in the app's files. */
    static File adbBin(Context ctx) {
        try {
            File nativeAdb = new File(ctx.getApplicationInfo().nativeLibraryDir, "libadb.so");
            if (nativeAdb.exists()) return nativeAdb;
        } catch (Exception ignored) {}
        return new File(ctx.getFilesDir(), assetName(Build.SUPPORTED_ABIS));
    }

    private static int versionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Unpacks the libraries of the 32-bit adb (once per app version) when this phone is a 32-bit one and the APK carries them. Safe to call often. */
    static void prepare(Context ctx) {
        if (!is32BitPhone()) return;
        try {
            File dir = libDir(ctx);
            File marker = new File(dir, ".ready-" + versionCode(ctx));
            if (marker.exists()) return;
            InputStream raw;
            try { raw = ctx.getAssets().open(LIBS_ZIP); } catch (Exception noZip) { return; }      // a 64-bit-only APK has none
            if (!dir.exists()) dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            ZipInputStream z = new ZipInputStream(raw);
            try {
                ZipEntry e;
                byte[] buf = new byte[16384];
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = new File(e.getName()).getName();                                 // a name only: nothing leaves the folder
                    if (name.isEmpty() || name.startsWith(".")) continue;
                    File out = new File(dir, name);
                    OutputStream os = new FileOutputStream(out);
                    try {
                        int n;
                        while ((n = z.read(buf)) > 0) os.write(buf, 0, n);
                    } finally { os.close(); }
                    out.setReadable(true, false);
                    out.setExecutable(true, false);
                    out.setWritable(false, false);
                }
            } finally { z.close(); }
            new FileOutputStream(marker).close();
        } catch (Exception ignored) {}
    }

    // ---- where the adb server listens (see AdbServerSpec) ----
    private static boolean probed;
    private static String socketPath;

    /**
     * The arguments that put adb's server on a private unix socket in the app's own folder, or on the old loopback port when the phone
     * will not let this app bind such a socket (checked once per process, by binding and listening on one here: adb runs in the same app domain).
     */
    static synchronized List<String> serverArgs(Context ctx) {
        if (!probed) {
            probed = true;
            socketPath = probeSocket(ctx);
        }
        return AdbServerSpec.args(socketPath);
    }

    static boolean usesPrivateSocket(Context ctx) {
        return AdbServerSpec.isPrivate(serverArgs(ctx));
    }

    private static String probeSocket(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), AdbServerSpec.SOCKET_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            // owner only: other apps cannot enter the folder, so they cannot reach the socket in it
            dir.setReadable(false, false); dir.setWritable(false, false); dir.setExecutable(false, false);
            dir.setReadable(true, true); dir.setWritable(true, true); dir.setExecutable(true, true);
            String path = AdbServerSpec.socketPath(ctx.getFilesDir().getAbsolutePath());
            if (!AdbServerSpec.pathFits(path)) return null;
            File probe = new File(dir, "probe");
            probe.delete();
            // bind() and listen() are separate permissions: adb's server needs both, so try both (a unix stream socket, the kind adb makes)
            java.io.FileDescriptor fd = null;
            try {
                fd = android.system.Os.socket(android.system.OsConstants.AF_UNIX, android.system.OsConstants.SOCK_STREAM, 0);
                android.system.Os.bind(fd, android.system.UnixSocketAddress.createFileSystem(probe.getAbsolutePath()));
                android.system.Os.listen(fd, 1);
            } finally {
                if (fd != null) { try { android.system.Os.close(fd); } catch (Exception ignored) {} }
                probe.delete();
            }
            return path;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Puts what adb needs to start into its environment (nothing on a 64-bit phone). */
    static void applyEnv(Context ctx, Map<String, String> env) {
        File dir = libDir(ctx);
        if (!is32BitPhone() || !dir.isDirectory()) return;
        String old = env.get("LD_LIBRARY_PATH");
        env.put("LD_LIBRARY_PATH", dir.getAbsolutePath() + (old == null || old.isEmpty() ? "" : ":" + old));
    }
}
