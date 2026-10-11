package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The safety rails for every path that is about to be deleted (spec section 8.6). Used by the engine's guard around the file system in front
 * of every tool's delete, and again by {@link SdmFs} and {@link SdmFsShell}, so a path that comes from an old scan, a bad selection or a
 * confused tool never reaches an {@code rm}.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se. The rails are the port's own
 * additions to the upstream behaviour (upstream deletes whatever the snapshot says): a target must be absolute and canonical, must not hold
 * a relative segment, a newline, a carriage return or a NUL, must lie strictly inside an available data area, and is never "/", "/data",
 * "/storage", an area root, a volume root, "&lt;sdcard&gt;/Android" or one of the user's standard top-level folders (DCIM, Pictures, ...).
 * Pure Java, no android.* classes.
 */
public final class SdmSafety {
    private SdmSafety() {}

    /** Exact paths that are never a delete target, whatever the areas say. */
    private static final Set<String> BLOCKED = new HashSet<String>(java.util.Arrays.asList(
            "/", "/data", "/storage", "/storage/emulated", "/storage/self", "/storage/self/primary", "/sdcard", "/mnt", "/mnt/sdcard", "/mnt/runtime",
            "/system", "/system_ext", "/vendor", "/product", "/odm", "/apex", "/sys", "/proc", "/dev", "/cache", "/config", "/metadata", "/acct", "/oem",
            "/data/media", "/data/media/0", "/data/user", "/data/user/0", "/data/user_de", "/data/user_de/0", "/data_mirror", "/data/data", "/data/app", "/data/system",
            "/data/local", "/data/local/tmp", "/data/misc", "/data/vendor", "/data/dalvik-cache", "/data/adb", "/data/property"));

    /** A volume root and the "Android" folder or one of the standard user folders directly below it (case-insensitive: public storage is FAT-like). */
    private static final Pattern VOLUME_TOP = Pattern.compile(
            "^/storage/(?:emulated/\\d+|[0-9a-f]{4}-[0-9a-f]{4})(?:/(?:android|dcim|pictures|movies|music|documents|download|downloads))?$", Pattern.CASE_INSENSITIVE);

    /** The reason a path must not be deleted without looking at the phone's areas, or null. */
    public static String lexical(String path) {
        if (path == null || path.isEmpty()) return "Empty path";
        if (path.charAt(0) != '/') return "Not an absolute path";
        if (path.length() > 4096) return "Path is too long";
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == 0 || c == '\n' || c == '\r') return "Path holds a control character";
        }
        if (path.equals("/")) return "Is the root of the file system";
        if (path.endsWith("/")) return "Not a canonical path (trailing slash)";
        String[] seg = path.substring(1).split("/", -1);
        for (String s : seg) {
            if (s.isEmpty()) return "Not a canonical path (empty segment)";
            if (s.equals("..") || s.equals(".")) return "Path holds a relative segment";
        }
        if (BLOCKED.contains(path)) return "Is a protected system folder";
        if (VOLUME_TOP.matcher(path).matches()) return "Is a storage root or a standard user folder";
        return null;
    }

    /** The reason a path must not be deleted, or null when it may be. {@code areas} may be null: then only {@link #lexical} applies. */
    public static String check(String path, Sdm.Areas areas) {
        String why = lexical(path);
        if (why != null) return why;
        if (areas == null) return null;
        List<Sdm.AreaInfo> all;
        try { all = areas.all(); } catch (RuntimeException e) { return "The data areas are not known"; }
        Sdm.AreaInfo best = null;                              // the deepest area that holds the path decides (Android/data is not "Public storage")
        if (all != null) {
            for (Sdm.AreaInfo a : all) {
                if (a.root == null || a.root.isEmpty()) continue;
                String root = a.root.length() > 1 && a.root.endsWith("/") ? a.root.substring(0, a.root.length() - 1) : a.root;
                if (path.equals(root)) return "Is the root of a data area";
                if (Sdm.isInside(path, root) && (best == null || root.length() > best.root.length())) best = a;
            }
        }
        if (best == null || !best.available()) return "Is not inside a data area that can be read";
        return null;
    }

    public static boolean allowed(String path, Sdm.Areas areas) {
        return check(path, areas) == null;
    }

    /** The paths that pass; the others go into {@code rejected} (path to reason) when it is not null. */
    public static List<String> accepted(Collection<String> paths, Sdm.Areas areas, Map<String, String> rejected) {
        List<String> ok = new ArrayList<String>();
        for (String p : paths) {
            String why = check(p, areas);
            if (why == null) ok.add(p);
            else if (rejected != null) rejected.put(p, why);
        }
        return ok;
    }
}
