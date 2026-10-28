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
     * The result is canonical itself: running it through this again changes nothing.
     */
    static String canonical(String path, String primary) {
        if (path == null || path.trim().isEmpty()) return "/";
        String p = path.replaceAll("/{2,}", "/");
        if (!p.startsWith("/")) p = "/" + p;                  // never relative to wherever a shell happens to start
        p = fold(swapAlias(p, primary));
        String again = swapAlias(p, primary);                 // folding can surface an alias that was hidden behind ".." ("/a/../sdcard")
        return again.equals(p) ? p : fold(again);
    }

    private static String swapAlias(String p, String primary) {
        String[] aliases = {"/sdcard", "/storage/self/primary"};
        for (String a : aliases) {
            if (p.equals(a)) return primary;
            if (p.startsWith(a + "/")) return primary + p.substring(a.length());
        }
        return p;
    }

    /** Resolves "." and ".." segments of an absolute path and drops empty ones. */
    private static String fold(String p) {
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
        if (c.matches("/mnt/media_rw/[^/]+")) return true;                                                                      // the same SD card / USB volume root, seen raw (what is inside it is not protected)
        if (c.matches("/mnt/(runtime|user|pass_through|androidwritable|installer)(/.*)?") && depth <= 5) return true;          // the same storage seen through its other mounts
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

    /** Whether a plain path is part of the app's own private data (the app answers it from PrivatePaths; a test can answer it from a list). */
    interface PrivateTest { boolean isPrivate(String path); }

    /**
     * Why item {@code q} of a cp / mv / rm batch (into {@code dest}; ignored for rm) is refused before anything runs, or null: a system location
     * {@link #isProtected} names, or {@code privateWhy} when {@code q}, or for a copy or move {@code dest}, is the app's private data. Added storage (a
     * content:// document) is neither, but the plain path on the other side of a batch that crosses into or out of it is judged here just as in an
     * all-plain batch, so the route a batch takes cannot skip the guard.
     */
    static String batchRefusal(String op, String q, String dest, PrivateTest priv, String privateWhy) {
        boolean qSaf = q.startsWith("content://");
        boolean toDest = !"rm".equals(op) && dest != null && !dest.isEmpty() && !dest.startsWith("content://");
        if (!qSaf && isProtected(q)) return "System location: not touched";
        if ((!qSaf && priv.isPrivate(q)) || (toDest && priv.isPrivate(dest))) return privateWhy;
        return null;
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

    // ---- Root file management (v7.18.2): permissions and owner, as root only ----

    private static final Pattern MODE = Pattern.compile("[0-7]{3,4}");
    private static final Pattern WHO = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,31}");

    private static String sq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /**
     * Why a permissions or owner change of {@code path} is refused before anything runs, or null: no path, a place {@link #isProtected} names
     * (a storage root, a top-level or system folder: a change there can stop the phone from starting), or the whole of a folder inside one of
     * the system partitions' trees changed at once.
     */
    static String permRefusal(String path, boolean recursive) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) return "No path";
        if (isProtected(path)) return "System location: not touched";
        if (recursive && path.matches("/(system|vendor|product|system_ext|odm|apex)(/.*)?")) return "A whole folder of the system partitions: not changed at once";
        return null;
    }

    /** The ops of Root file management (stat, chmod, chown, restorecon, and their "-r" forms): their second argument is a mode or owner:group, not a path. */
    static boolean isPermOp(String op) {
        return op != null && (op.equals("stat") || op.equals("chmod") || op.equals("chmod-r") || op.equals("chown") || op.equals("chown-r")
                || op.equals("restorecon") || op.equals("restorecon-r"));
    }

    /** What chmod answers for a symbolic link: chmod would change what the link points to, which no check here has looked at. */
    static final String LINK_NO_CHMOD = "This is a link: open the file it points to and change the permissions there";

    /** The shell line that reads a path's mode, owner and SELinux label: a line "FMSTAT mode|user|group|uid|gid|type", then the label line. */
    static String statCommand(String path) {
        String q = sq(path);
        return "stat -c 'FMSTAT %a|%U|%G|%u|%g|%F' -- " + q + " && { ls -Zd -- " + q + " 2>/dev/null | head -n 1; true; }";
    }

    /**
     * chmod as a shell line ending in FMOK; the mode is three or four octal digits (else IllegalArgumentException with the reason). A symbolic
     * link is refused ({@link #LINK_NO_CHMOD}): chmod follows it, so it would change a file the protection checks never saw.
     */
    static String chmodCommand(String path, String mode, boolean recursive) {
        if (mode == null || !MODE.matcher(mode).matches()) throw new IllegalArgumentException("The mode must be three or four digits from 0 to 7, such as 644");
        String q = sq(path);
        return "if [ -L " + q + " ]; then echo " + sq(LINK_NO_CHMOD) + "; else chmod " + (recursive ? "-R " : "") + mode + " -- " + q + " && echo FMOK; fi";
    }

    /**
     * chown as a shell line ending in FMOK: {@code owner} and/or {@code group} (a name or a number; an empty group leaves the group as it is,
     * an empty owner with a group changes only the group). A symbolic link itself is changed, not what it points to (-h).
     */
    static String chownCommand(String path, String owner, String group, boolean recursive) {
        String o = owner == null ? "" : owner.trim(), g = group == null ? "" : group.trim();
        if (o.isEmpty() && g.isEmpty()) throw new IllegalArgumentException("Enter an owner or a group");
        if (!o.isEmpty() && !WHO.matcher(o).matches()) throw new IllegalArgumentException("The owner must be a user name or number, such as root or 1000");
        if (!g.isEmpty() && !WHO.matcher(g).matches()) throw new IllegalArgumentException("The group must be a group name or number, such as root or 1000");
        String who = o.isEmpty() ? ":" + g : g.isEmpty() ? o : o + ":" + g;
        return "chown -h " + (recursive ? "-R " : "") + who + " -- " + sq(path) + " && echo FMOK";
    }

    /**
     * restorecon as a shell line ending in FMOK: puts back the SELinux label the system policy gives {@code path} (and, recursive, everything
     * in it), the fix for a file copied or moved into another app's data that keeps a label that app cannot use. It sets only the policy's own
     * default, never a label of the person's choosing.
     */
    static String restoreconCommand(String path, boolean recursive) {
        return "restorecon " + (recursive ? "-R " : "") + "-- " + sq(path) + " && echo FMOK";
    }

    /**
     * True when {@code out} holds {@code marker} as a whole line (a trailing carriage return or blanks are fine). A command that ends with "&amp;&amp; echo OK" prints it on a line of its own;
     * a message that merely contains the letters (a file called "BOOKS", an error about "/sdcard/OKAY") is not a success.
     */
    static boolean okLine(String out, String marker) {
        if (out == null || marker == null || marker.isEmpty()) return false;
        for (String line : out.split("\\r?\\n")) if (line.trim().equals(marker)) return true;
        return false;
    }

    /**
     * True when a shell answer says the link to the device is gone (adb offline / not found / closed, a timeout), so trying
     * item after item is pointless. Only a line that starts with such a message counts - adb and this app put theirs at the
     * start of a line - so a file whose name happens to contain the words ("mv: unauthorized.txt: Permission denied") doesn't.
     */
    static boolean transportLost(String msg) {
        if (msg == null) return false;
        for (String line : msg.split("\\r?\\n")) {
            String m = line.trim().toLowerCase(Locale.US);
            if (m.startsWith("[process timed out")) return true;
            if (m.startsWith("adb: ")) m = m.substring(5).trim();                      // newer adb puts "adb: " in front of its errors
            if (!m.startsWith("error:")) continue;
            String e = m.substring(6).trim();
            if (e.startsWith("device offline") || e.startsWith("no devices/emulators") || e.startsWith("closed") || e.startsWith("protocol fault")
                    || e.startsWith("device unauthorized") || e.startsWith("connection refused") || e.startsWith("shizuku is not authorized")
                    || (e.startsWith("device '") && e.contains("not found"))) {
                return true;
            }
        }
        return false;
    }
}
