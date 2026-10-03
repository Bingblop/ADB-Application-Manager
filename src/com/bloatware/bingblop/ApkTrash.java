package com.bloatware.bingblop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deleting a package file the Installer's search found, with an Undo. The file is not removed at once: it is moved into a hidden
 * folder on its own storage volume (a rename inside one volume is instant and cannot run out of space), Undo moves it back, and
 * it is deleted for good when the Undo is no longer offered. These rules only say which paths may be handled and where the
 * trashed copy lives, so the delete cannot be pointed at anything but a package file on shared storage. Free of Android classes,
 * so they are tested off the device.
 */
final class ApkTrash {

    private ApkTrash() {}

    /** The hidden folder, at the top of each storage volume, that holds a deleted file until the Undo runs out. */
    static final String DIR = ".adb_manager_trash";

    // DOTALL: a file name may hold a line break, and such a file must be handled like any other
    private static final Pattern VOLUME = Pattern.compile("^(/storage/emulated/[0-9]+|/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(?:/(.*))?$", Pattern.DOTALL);

    /** A file name on Android's file systems tops out at 255 bytes; the stamp and its "_" take 14 of them, so the name keeps at most this many. */
    static final int MAX_NAME_BYTES = 200;

    /** The storage volume a canonical path lives on ("/storage/emulated/0", "/storage/ABCD-1234"), or null for any other place. */
    static String volumeRoot(String path) {
        if (path == null) return null;
        Matcher m = VOLUME.matcher(path);
        return m.matches() ? m.group(1) : null;
    }

    private static String rest(String path) {
        Matcher m = VOLUME.matcher(path);
        return m.matches() && m.group(2) != null ? m.group(2) : "";
    }

    /** The trash folder of a volume. */
    static String trashDir(String volumeRoot) {
        return volumeRoot + "/" + DIR;
    }

    /** True for a path inside a trash folder, one level down - the only place a trashed file is kept. */
    static boolean isTrashPath(String path) {
        if (volumeRoot(path) == null) return false;
        String r = rest(path);
        if (!r.startsWith(DIR + "/")) return false;
        String name = r.substring(DIR.length() + 1);
        return !name.isEmpty() && name.indexOf('/') < 0;
    }

    /**
     * True when this canonical path may be moved to the trash: a package file (.apk, .apks, .apkm, .xapk) somewhere inside a
     * storage volume, not the trash folder itself and not something already in it. Paths are expected canonical (no "..", no
     * doubled slashes), as the file manager's canonical form makes them.
     */
    static boolean deletable(String path) {
        if (volumeRoot(path) == null) return false;
        String r = rest(path);
        if (r.isEmpty() || r.equals(DIR) || r.startsWith(DIR + "/")) return false;
        if (path.contains("/../") || path.endsWith("/..") || path.contains("//")) return false;
        return ApkScan.kindOf(r.substring(r.lastIndexOf('/') + 1)) != null;
    }

    /** The end of {@code name} that fits in {@code maxBytes} of UTF-8, never cutting a character (a surrogate pair included) in two. */
    static String tail(String name, int maxBytes) {
        int bytes = 0, i = name.length();
        while (i > 0) {
            int cp = name.codePointBefore(i);
            int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + n > maxBytes) break;
            bytes += n;
            i -= Character.charCount(cp);
        }
        return name.substring(i);
    }

    /** Where a deleted file waits: the trash folder of its own volume, named with a stamp so two files of one name can both wait. */
    static String trashPath(String path, long stamp) {
        String root = volumeRoot(path);
        if (root == null) return null;
        String name = path.substring(path.lastIndexOf('/') + 1);
        return trashDir(root) + "/" + stamp + "_" + tail(name, MAX_NAME_BYTES);          // keeps the end, with its extension
    }

    private static long lastStamp = 0;

    /** A stamp that is never the same twice in this process, even for two files deleted in one millisecond (a rename would replace the first). */
    static synchronized long nextStamp(long nowMs) {
        lastStamp = Math.max(nowMs, lastStamp + 1);
        return lastStamp;
    }

    /** True when a trashed file may be put back at {@code original}: both canonical, on the same volume, and the target a package file that could have been deleted. */
    static boolean restorable(String trash, String original) {
        if (!isTrashPath(trash) || !deletable(original)) return false;
        return volumeRoot(trash).equals(volumeRoot(original));
    }

    /**
     * Reads a working mode's answer to a test that prints YES or NO: the last non-empty line decides. Any other reply (the link is down,
     * su is missing - the shell helpers return those as text instead of throwing) is an error, never a "no": a dead shell must not be
     * taken for "that file is not there".
     */
    static boolean yesNo(String out) throws java.io.IOException {
        String last = "";
        if (out != null) for (String line : out.split("\n")) if (!line.trim().isEmpty()) last = line.trim();
        if (last.equals("YES")) return true;
        if (last.equals("NO")) return false;
        throw new java.io.IOException(last.isEmpty() ? "The working mode did not answer." : last);
    }

    /** Whether something is at a path (a file test or a shell's answer, so it may fail). */
    interface Exists {
        boolean at(String path) throws Exception;
    }

    /**
     * Where a restored file goes: {@code original} when nothing is there, else the first free "name (2).ext", "name (3).ext" ... next to it
     * (both files are kept). null when the first 48 are taken too.
     */
    static String uniqueTarget(String original, Exists exists) throws Exception {
        if (!exists.at(original)) return original;
        int slash = original.lastIndexOf('/');
        String dir = original.substring(0, slash), name = original.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 50; i++) {
            String target = dir + "/" + stem + " (" + i + ")" + ext;
            if (!exists.at(target)) return target;
        }
        return null;
    }
}
