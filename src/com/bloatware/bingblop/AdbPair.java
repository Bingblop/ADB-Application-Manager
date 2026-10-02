package com.bloatware.bingblop;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Minimal, context-only adb invocation used by the Wi-Fi pairing notification. It mirrors the way
 * MainActivity.buildAdbProcess runs the bundled adb (same binary, server port 5042 and HOME), so the
 * pairing notification can pair in the background from a BroadcastReceiver without the Activity being
 * alive. The adb binary and key are extracted by MainActivity on launch, so they already exist by the
 * time the user triggers pairing.
 */
public final class AdbPair {
    private AdbPair() {}

    static File adbBin(Context ctx) {
        try {
            File nativeAdb = new File(ctx.getApplicationInfo().nativeLibraryDir, "libadb.so");
            if (nativeAdb.exists()) return nativeAdb;
        } catch (Exception ignored) {}
        return new File(ctx.getFilesDir(), "libadb.so");
    }

    static File adbHome(Context ctx) {
        return new File(ctx.getFilesDir(), "adb_home");
    }

    /** Runs the bundled adb with the same environment MainActivity uses; returns combined stdout+stderr. */
    static String run(Context ctx, int timeoutMs, String... args) {
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(adbBin(ctx).getAbsolutePath());
            cmd.add("-P");
            cmd.add("5042");
            Collections.addAll(cmd, args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            File home = adbHome(ctx);
            env.put("HOME", home.getAbsolutePath());
            env.put("ANDROID_USER_HOME", home.getAbsolutePath());
            File key = new File(new File(home, ".android"), "adbkey");
            if (key.exists()) env.put("ADB_VENDOR_KEYS", key.getAbsolutePath());
            env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            Thread killer = new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(timeoutMs); p.destroy(); } catch (Exception ignored) {}
                }
            });
            killer.setDaemon(true);
            killer.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            try { p.waitFor(); } catch (InterruptedException ignored) {}
            killer.interrupt();
            return sb.toString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** `adb pair <endpoint> <code>`. */
    static String pair(Context ctx, String endpoint, String code) {
        return run(ctx, 15000, "pair", endpoint, code);
    }

    /** Finds the current `_adb-tls-pairing` endpoint (ip:port) via adb mDNS, or "" if none. */
    static String discoverPairingEndpoint(Context ctx) {
        String raw = run(ctx, 4000, "mdns", "services");
        if (raw == null) return "";
        for (String line : raw.split("\n")) {
            if (line.contains("_adb-tls-pairing")) {
                String[] parts = line.trim().split("\\s+");
                String ep = parts.length > 0 ? parts[parts.length - 1] : "";
                if (ep.contains(":")) return ep;
            }
        }
        return "";
    }
}
