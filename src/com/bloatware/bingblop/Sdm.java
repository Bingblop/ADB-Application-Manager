package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shared types of the SD Maid SE tab: what a tool sees of the phone (a file system, the data areas, the installed apps, the exclusions, a shell),
 * what a scan returns and how a deletion is reported. The four tools (SystemCleaner, AppCleaner, CorpseFinder, Deduplicator) are ported from SD Maid SE by
 * darken (d4rken-org/sdmaid-se, GPL-3.0); each one implements {@link ToolImpl} against these interfaces, and {@link SdmEngine} runs them.
 * Only types and constants live here, no logic and no android.* classes, so every tool is unit-testable on a plain JDK.
 */
public final class Sdm {
    private Sdm() {}

    // ---------------------------------------------------------------------------------------------------------- basic types

    public static final int FILE = 0, DIR = 1, LINK = 2, OTHER = 3;

    public enum Tool {
        SYSTEMCLEANER("systemcleaner"), APPCLEANER("appcleaner"), CORPSEFINDER("corpsefinder"), DEDUPLICATOR("deduplicator");
        public final String id;
        Tool(String id) { this.id = id; }
        public static Tool of(String id) {
            for (Tool t : values()) if (t.id.equals(id)) return t;
            throw new IllegalArgumentException("unknown tool: " + id);
        }
    }

    /** The data areas of SD Maid (spec section 1.4). The public ones are case-insensitive (FAT / sdcardfs semantics). */
    public enum Area {
        SDCARD(true), PUBLIC_DATA(true), PUBLIC_MEDIA(true), PUBLIC_OBB(true), PORTABLE(true),
        PRIVATE_DATA(false), DATA(false), DATA_SYSTEM(false), DATA_SYSTEM_CE(false), DATA_SYSTEM_DE(false), DATA_MISC(false), DATA_VENDOR(false),
        DOWNLOAD_CACHE(false), APP_APP(false), APP_APP_PRIVATE(false), APP_LIB(false), APP_ASEC(false), DALVIK_DEX(false), DALVIK_PROFILE(false), ART_PROFILE(false);
        public final boolean caseInsensitive;
        Area(boolean ci) { this.caseInsensitive = ci; }
    }

    /** One entry of a walk or a stat: the file, directory or link itself (a link is never followed). */
    public static final class Entry {
        public final String path;      // absolute, no trailing slash (except "/")
        public final String name;      // last segment
        public final long size;        // bytes of the item itself (a directory: its own entry size)
        public final long mtime;       // seconds since the epoch
        public final int type;         // FILE, DIR, LINK or OTHER
        public final int uid;          // owner, -1 when unknown
        public Entry(String path, long size, long mtime, int type, int uid) {
            this.path = path;
            int i = path.lastIndexOf('/');
            this.name = i < 0 ? path : path.substring(i + 1);
            this.size = size; this.mtime = mtime; this.type = type; this.uid = uid;
        }
        public boolean isDir() { return type == DIR; }
        public boolean isFile() { return type == FILE; }
        @Override public String toString() { return path + " (" + (type == DIR ? "dir" : type == LINK ? "link" : "file") + ", " + size + ")"; }
    }

    /** A data area on this phone: where it is, and how it is read. */
    public static final class AreaInfo {
        public final Area area;
        public final String root;      // absolute path of the area root
        public final boolean primary;
        public final String via;       // "java" (this app reads it itself), "shell" (through the working mode) or "" (not available)
        public final String reason;    // why it is not available, in words; "" when it is
        public AreaInfo(Area area, String root, boolean primary, String via, String reason) {
            this.area = area; this.root = root; this.primary = primary; this.via = via == null ? "" : via; this.reason = reason == null ? "" : reason;
        }
        public boolean available() { return !via.isEmpty(); }
        /** The path below the area root, as segments ("DCIM/a.jpg" -> ["DCIM", "a.jpg"]); null when path is not inside this area. */
        public String[] pfp(String path) {
            if (path.equals(root)) return new String[0];
            String r = root.endsWith("/") ? root : root + "/";
            if (!path.startsWith(r)) return null;
            return path.substring(r.length()).split("/");
        }
    }

    // ---------------------------------------------------------------------------------------------------------- what a tool sees

    public interface Cancel { boolean cancelled(); }

    /** Receives what a walk finds. Return false for a directory to leave it out of the walk (it is not descended); the return value of a file is ignored. */
    public interface EntrySink { boolean accept(Entry e); }

    public interface LineSink { void line(String line); }

    /** Storage as the tools see it. The implementation reads with Java where this app can and through the working mode where it cannot (Android/data ...). */
    public interface Fs {
        /** The names of the direct children of dir, or null when it cannot be read. */
        String[] list(String dir) throws IOException;
        /** lstat of one path (a link is reported as a link), or null when there is nothing. */
        Entry stat(String path) throws IOException;
        /** Walks root depth-first and gives every child (files and directories, links not followed) to sink; root itself is not given. Errors in one directory are skipped. */
        void walk(String root, EntrySink sink, Cancel cancel) throws IOException;
        boolean exists(String path);
        /** Deletes path recursively; true when nothing is left at path afterwards (the rule of SD Maid: gone is deleted). */
        boolean delete(String path);
        /** Deletes many paths (in batches when it goes through a shell); returns the ones that are gone. */
        Set<String> deleteAll(Collection<String> paths, Cancel cancel);
        /** Lower-case hex SHA-256 of a file's content. */
        String sha256(String path, Cancel cancel) throws IOException;
        /** The first bytes of a file (at most max), or null when it cannot be read; for the Deduplicator's cheap pre-check. */
        byte[] head(String path, int max) throws IOException;
    }

    public interface Areas {
        /** Every area this phone has, with how it is read; areas that cannot be read are there too, with via "" and a reason. */
        List<AreaInfo> all();
        AreaInfo get(Area a);
        /** The area a path is in (the deepest root that is an ancestor, never SDCARD for Android/data|media|obb), or null. */
        AreaInfo areaOf(String path);
    }

    public static final class Pkg {
        public String pkg = "", label = "";
        public boolean system, enabled = true, uninstalled, running;
        public int uid = -1;
        public long versionCode = -1;
        public long cacheBytes = -1, dataBytes = -1, appBytes = -1;     // -1: not known
        public String versionName = "";
    }

    public interface Packages {
        /** Installed apps (with MATCH_UNINSTALLED_PACKAGES: apps removed with their data kept are there with uninstalled = true). */
        List<Pkg> installed();
        Pkg get(String pkg);
        /** Packages with a running process now (empty when it cannot be told). */
        Set<String> running();
        /** Cache size of one app through StorageStatsManager (needs Usage access), or -1. */
        long cacheBytes(String pkg);
    }

    public interface Exclusions {
        boolean excludesPath(Tool t, String path);
        boolean excludesPackage(Tool t, String pkg);
        /** The absolute paths excluded for a tool (to prune a walk), without segment rules. */
        List<String> paths(Tool t);
    }

    /** A shell through the working mode. uid() is 0 for root, 2000 for shell, -1 when there is none. */
    public interface Shell {
        int uid();
        String run(String script, int timeoutMs) throws IOException;
        void stream(String script, int timeoutMs, LineSink sink, Cancel cancel) throws IOException;
    }

    public interface Progress {
        /** primary: what is being done ("Scanning /storage/emulated/0/DCIM"), secondary: a detail, done/total: counts (total -1: unknown), bytes found so far. */
        void update(String primary, String secondary, long done, long total, long bytes);
    }

    /** Everything one scan or one deletion gets. */
    public static final class Ctx {
        public final Fs fs;
        public final Areas areas;
        public final Packages pkgs;
        public final Exclusions exclusions;
        public final Shell shell;
        public final JSONObject settings;      // the tool's settings by key (see SdmSettings); a missing key means its default
        public final Progress progress;
        public final Cancel cancel;
        public final long nowSec;
        /** Set by the engine when the accessibility service can click through the system settings (AppCleaner); null otherwise. */
        public Automation automation;
        public Ctx(Fs fs, Areas areas, Packages pkgs, Exclusions ex, Shell shell, JSONObject settings, Progress progress, Cancel cancel, long nowSec) {
            this.fs = fs; this.areas = areas; this.pkgs = pkgs; this.exclusions = ex; this.shell = shell; this.settings = settings == null ? new JSONObject() : settings;
            this.progress = progress == null ? NO_PROGRESS : progress; this.cancel = cancel == null ? NEVER : cancel; this.nowSec = nowSec;
        }
        public boolean bool(String key, boolean def) { return settings.has(key) && !settings.isNull(key) ? settings.optBoolean(key, def) : def; }
        public long num(String key, long def) { return settings.has(key) && !settings.isNull(key) ? settings.optLong(key, def) : def; }
        public String str(String key, String def) { return settings.has(key) && !settings.isNull(key) ? settings.optString(key, def) : def; }
        public boolean cancelled() { return cancel.cancelled(); }
    }

    /**
     * Clears the cache of apps by driving the system settings screens (SD Maid's "automation": the accessibility service opens App info > Storage >
     * Clear cache). Implemented by SdmAccessService and handed to the tools through {@link Ctx#automation}.
     */
    public interface Automation {
        /** True when the service is connected and the user gave consent. */
        boolean ready();
        /** Clears the caches of the given packages one by one; returns the packages it cleared. Throws {@link AutomationError}. */
        Set<String> clearCaches(List<String> pkgs, Progress progress, Cancel cancel) throws AutomationError;
    }

    /** code: SCREEN_UNAVAILABLE | NO_CONSENT | ERROR (the three result variants of spec 7.3 / 3.7.9). done: what had been cleared before the stop. */
    public static final class AutomationError extends Exception {
        public final String code;
        public final Set<String> done;
        public AutomationError(String code, String message, Set<String> done) { super(message); this.code = code; this.done = done == null ? new HashSet<String>() : done; }
    }

    public static final Cancel NEVER = new Cancel() { @Override public boolean cancelled() { return false; } };
    public static final Progress NO_PROGRESS = new Progress() { @Override public void update(String p, String s, long d, long t, long b) {} };

    // ---------------------------------------------------------------------------------------------------------- results

    /**
     * What a scan found, held in memory by the engine and shown page by page. A group is a filter (SystemCleaner), an app (AppCleaner), a corpse
     * (CorpseFinder) or a cluster of duplicates (Deduplicator). Ids are stable strings that the page sends back in a {@link Selection}.
     */
    public interface Result {
        Tool tool();
        int groupCount();
        int itemCount();
        long bytes();
        /** [{id, label, sub, count, bytes, ...tool specific}] */
        JSONArray groups(int offset, int limit) throws Exception;
        /** [{id, path, name, size, mtime, type, ...}] of one group */
        JSONArray items(String groupId, int offset, int limit) throws Exception;
        /** The result line the dashboard shows ({primary, secondary}) and any extras: {itemCount, bytes, groupCount, primary, secondary}. */
        JSONObject summary() throws Exception;
    }

    /** Everything is selected except what is dropped here. */
    public static final class Selection {
        public final Set<String> dropGroups = new HashSet<String>();
        public final Set<String> dropItems = new HashSet<String>();
        /** Tool specific delete options: AppCleaner {includeInaccessible, onlyInaccessible, useAutomation}; Deduplicator {deleteAll}. */
        public JSONObject options = new JSONObject();
        public static Selection of(JSONObject o) {
            Selection s = new Selection();
            if (o != null) {
                JSONObject op = o.optJSONObject("options");
                if (op != null) s.options = op;
                JSONArray g = o.optJSONArray("dropGroups"), i = o.optJSONArray("dropItems");
                if (g != null) for (int k = 0; k < g.length(); k++) s.dropGroups.add(g.optString(k));
                if (i != null) for (int k = 0; k < i.length(); k++) s.dropItems.add(i.optString(k));
            }
            return s;
        }
    }

    public static final class Deleted {
        public final String path, group, label;
        public final long bytes;
        public Deleted(String path, long bytes, String group, String label) { this.path = path; this.bytes = bytes; this.group = group == null ? "" : group; this.label = label == null ? "" : label; }
    }

    public static final class DeleteReport {
        public final java.util.ArrayList<Deleted> deleted = new java.util.ArrayList<Deleted>();
        public final java.util.ArrayList<String> failed = new java.util.ArrayList<String>();
        public final java.util.ArrayList<String> notes = new java.util.ArrayList<String>();
        public long bytes() { long b = 0; for (Deleted d : deleted) b += d.bytes; return b; }
    }

    /** One tool. Implementations are stateless: a scan returns a {@link Result} that a later {@link #delete} receives again. */
    public interface ToolImpl {
        Tool tool();
        Result scan(Ctx ctx) throws Exception;
        DeleteReport delete(Result result, Selection selection, Ctx ctx) throws Exception;
    }

    // ---------------------------------------------------------------------------------------------------------- small helpers shared by the tools

    /** Splits an absolute path the way SD Maid does: "/a/b" -> ["", "a", "b"]; the root is [""]. */
    public static String[] segments(String path) {
        return path.equals("/") ? new String[] { "" } : path.split("/", -1);
    }

    public static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    public static boolean isInside(String path, String root) {
        String r = root.endsWith("/") ? root : root + "/";
        return path.startsWith(r);
    }
}
