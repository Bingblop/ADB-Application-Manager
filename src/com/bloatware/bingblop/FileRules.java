package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The file manager's path rules, free of Android classes so they can be tested off the device: how a typed path is folded into
 * one canonical form, which places a delete or move must never touch, what a signed copy is called, and when a shell's
 * answer means the link to the device is gone.
 */
final class FileRules {

    private FileRules() {}

    /**
     * Makes the path absolute, collapses duplicate slashes, resolves the storage aliases (/sdcard, /storage/self/primary) to
     * {@code primary}, folds "." and ".." (after the aliases, as a shell would follow them) and strips a trailing slash. So a
     * typed or composed path such as /storage/emulated/0/../0 can neither reach nor hide a place {@link #isProtected} names.
     */
    static String canonical(String path, String primary) {
        if (path == null || path.trim().isEmpty()) return "/";
        String p = path.replaceAll("/{2,}", "/");
        if (!p.startsWith("/")) p = "/" + p;                  // never relative to wherever a shell happens to start
        String[] aliases = {"/sdcard", "/storage/self/primary"};
        for (String a : aliases) {
            if (p.equals(a)) { p = primary; break; }
            if (p.startsWith(a + "/")) { p = primary + p.substring(a.length()); break; }
        }
        ArrayList<String> parts = new ArrayList<String>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(seg);
        }
        if (parts.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String seg : parts) sb.append('/').append(seg);
        return sb.toString();
    }

    /** Places a delete / move must not touch: the root, any top-level folder, a user's whole storage, the system trees. Expects a canonical path. */
    static boolean isProtected(String p) {
        String c = p;
        while (c.length() > 1 && c.endsWith("/")) c = c.substring(0, c.length() - 1);
        if (c.isEmpty() || c.equals("/")) return true;
        int depth = c.split("/").length - 1;                  // "/data" is 1
        if (depth <= 1) return true;
        if (c.equals("/storage/emulated") || c.equals("/storage/self") || c.equals("/storage/self/primary")) return true;
        if (c.matches("/storage/emulated/[0-9]+") || c.matches("/storage/[^/]+") || c.matches("/mnt/[^/]+")) return true;      // a user's whole storage, an SD card or USB volume root
        if (c.matches("/mnt/(runtime|user|pass_through|androidwritable)(/.*)?") && depth <= 5) return true;                    // the same storage seen through its other mounts
        if (c.startsWith("/system/") || c.startsWith("/vendor/") || c.startsWith("/product/") || c.startsWith("/system_ext/")
                || c.startsWith("/apex/") || c.startsWith("/odm/") || c.startsWith("/proc/") || c.startsWith("/sys/") || c.startsWith("/dev/")) {
            return depth <= 2;
        }
        if (c.matches("/data/(user|user_de|media)/[0-9]+")) return true;                                                      // any user's app data, device-encrypted data or storage root
        return c.equals("/data/data") || c.equals("/data/app") || c.equals("/data/user") || c.equals("/data/user_de") || c.equals("/data/media")
                || c.equals("/data/misc") || c.equals("/data/system") || c.equals("/data/local") || c.equals("/data/local/tmp")
                || c.equals("/data/system/users") || c.equals("/data/system_ce") || c.equals("/data/system_de") || c.equals("/data/misc_ce")
                || c.equals("/data/misc_de") || c.equals("/data/adb") || c.equals("/data/apex") || c.equals("/data/vendor")
                || c.equals("/data/dalvik-cache") || c.equals("/data/property");
    }

    private static final Pattern ALREADY_SIGNED = Pattern.compile("^(.*-signed)(?:-(\\d{1,6}))?$");

    /**
     * The file name of a signed copy: app.apk -> app-signed.apk. A name that already ends "-signed" (or "-signed-2") gets
     * the next number, so a copy can never land on the file it was made from.
     */
    static String signedName(String fileName) {
        String base = fileName;
        if (base.toLowerCase(Locale.US).endsWith(".apk")) base = base.substring(0, base.length() - 4);
        Matcher m = ALREADY_SIGNED.matcher(base);
        if (m.matches()) base = m.group(1) + "-" + ((m.group(2) == null ? 1 : Integer.parseInt(m.group(2))) + 1);
        else base += "-signed";
        return base + ".apk";
    }

    /** True when a shell answer says the link to the device is gone (adb offline / not found, a timeout), so trying item after item is pointless. */
    static boolean transportLost(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.US);
        return m.contains("device offline") || m.contains("no devices/emulators") || (m.contains("device '") && m.contains("not found"))
                || m.contains("connection refused") || m.contains("error: closed") || m.contains("process timed out")
                || m.contains("unauthorized") || m.contains("shizuku is not authorized") || m.contains("protocol fault");
    }
}
