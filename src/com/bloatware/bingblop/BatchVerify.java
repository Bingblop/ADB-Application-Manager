package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a finished batch against the phone instead of against what the commands printed. On some phones a run of
 * {@code pm uninstall} lines "failed" (a non-zero exit status, or words no list of keywords foresaw) although every
 * app was gone, so after uninstall, reinstall, freeze, unfreeze, suspend and unsuspend the app asks the package manager what each app
 * is now and reports that. Pure logic: the shell calls and the one retry are in MainActivity.
 */
final class BatchVerify {
    private BatchVerify() {}

    /** What the phone said: the package lists, the apps found suspended, and the apps whose state could not be read (left as they were). */
    static final class State {
        final Set<String> installed;
        final Set<String> disabled;
        final Set<String> suspended;
        final Set<String> unknown;
        final Set<String> running;
        State(Set<String> installed, Set<String> disabled, Set<String> suspended, Set<String> unknown) {
            this(installed, disabled, suspended, unknown, null);
        }
        State(Set<String> installed, Set<String> disabled, Set<String> suspended, Set<String> unknown, Set<String> running) {
            this.running = running == null ? new HashSet<String>() : running;
            this.installed = installed == null ? new HashSet<String>() : installed;
            this.disabled = disabled == null ? new HashSet<String>() : disabled;
            this.suspended = suspended == null ? new HashSet<String>() : suspended;
            this.unknown = unknown == null ? new HashSet<String>() : unknown;
        }
    }

    /** Whether the result of this action can be read back from the phone (the package lists, or an app's suspended flag). */
    static boolean verifiable(String action) {
        return "uninstall".equals(action) || "uninstall_keep_data".equals(action) || "reinstall".equals(action)
                || "freeze".equals(action) || "unfreeze".equals(action) || needsSuspended(action) || needsRunning(action);
    }

    /** Whether the list of running processes is read (Force stop: the app's process should be gone). */
    static boolean needsRunning(String action) {
        return "force_stop".equals(action);
    }

    /** Whether the list of installed apps matters for this action (Force stop only looks at processes). */
    static boolean needsInstalled(String action) {
        return !needsRunning(action);
    }

    /** The package names of the processes in the output of {@code ps -A -o NAME} ("com.a.b" and "com.a.b:service" both give com.a.b); null when it lists nothing. */
    static Set<String> parseProcessNames(String out) {
        if (out == null) return null;
        Set<String> set = new HashSet<String>();
        int n = 0;
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.equals("NAME") || line.startsWith("ps:") || line.contains("not found") || line.contains("Usage")) continue;
            n++;
            int c = line.indexOf(':');
            set.add(c > 0 ? line.substring(0, c) : line);
        }
        return n == 0 ? null : set;
    }

    /** Whether the disabled-packages list is needed too. */
    static boolean needsDisabled(String action) {
        return "freeze".equals(action) || "unfreeze".equals(action);
    }

    /** Whether each app's suspended flag has to be read (from {@code dumpsys package}). */
    static boolean needsSuspended(String action) {
        return "suspend".equals(action) || "unsuspend".equals(action);
    }

    /** The suspended flag of the user's line in {@code dumpsys package <pkg>} ("User 0: ... suspended=true ..."); null when it is not there. */
    static Boolean suspendedFrom(String dump) {
        if (dump == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*User 0:[^\\n]*?\\bsuspended=(true|false)").matcher(dump);
        if (m.find()) return Boolean.valueOf("true".equals(m.group(1)));
        return null;
    }

    /** The package names in the output of {@code pm list packages}; null when it holds no "package:" line (not a real list). */
    static Set<String> parse(String raw) {
        if (raw == null) return null;
        Set<String> set = new HashSet<String>();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (!line.startsWith("package:")) continue;
            String name = line.substring(8).trim();
            int sp = name.indexOf(' ');
            if (sp > 0) name = name.substring(0, sp);
            if (!name.isEmpty()) set.add(name);
        }
        return set.isEmpty() ? null : set;
    }

    /** True when the app is now in the state the action was meant to leave it in. */
    static boolean stateOk(String action, String pkg, State st) {
        if ("uninstall".equals(action) || "uninstall_keep_data".equals(action)) return !st.installed.contains(pkg);
        if ("reinstall".equals(action)) return st.installed.contains(pkg);
        if ("freeze".equals(action)) return st.installed.contains(pkg) && st.disabled.contains(pkg);
        if ("force_stop".equals(action)) return !st.running.contains(pkg);
        if ("suspend".equals(action)) return st.installed.contains(pkg) && st.suspended.contains(pkg);
        if ("unsuspend".equals(action)) return st.installed.contains(pkg) && !st.suspended.contains(pkg);
        return st.installed.contains(pkg) && !st.disabled.contains(pkg);
    }

    /** True when every row is in its wanted state (when not, the package manager may still be finishing: ask once more). */
    static boolean allOk(String action, JSONArray rows, State st) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String pkg = row.optString("pkg", "");
            if (!st.unknown.contains(pkg) && !stateOk(action, pkg, st)) return false;
        }
        return true;
    }

    static String label(String action, boolean real) {
        if ("uninstall".equals(action) || "uninstall_keep_data".equals(action)) return real ? "Uninstalled" : "Still installed";
        if ("reinstall".equals(action)) return real ? "Installed" : "Not installed";
        if ("freeze".equals(action)) return real ? "Frozen" : "Not frozen";
        if ("force_stop".equals(action)) return real ? "Stopped" : "Still running";
        if ("suspend".equals(action)) return real ? "Suspended" : "Not suspended";
        if ("unsuspend".equals(action)) return real ? "Not suspended" : "Still suspended";
        return real ? "Enabled" : "Still frozen";
    }

    /** Rewrites each row from the real state: success, a label, a note where the phone and the command disagree. Returns how many succeeded. */
    static int apply(String action, JSONArray rows, State st) throws org.json.JSONException {
        int ok = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            boolean cmdOk = row.optBoolean("success", false);
            if (st.unknown.contains(row.optString("pkg", ""))) { if (cmdOk) ok++; continue; }          // could not be read: the command's answer stands
            boolean real = stateOk(action, row.optString("pkg", ""), st);
            String label = label(action, real);
            String low = label.toLowerCase(Locale.ROOT);
            String note;
            if (real && !cmdOk) note = "Checked afterwards: " + low + ". The command reported a failure, but the phone says it worked.\n\n";
            else if (!real && cmdOk) note = "Checked afterwards: " + low + ", although the command reported success.\n\n";
            else note = "Checked afterwards: " + low + ".\n\n";
            if (!real && "force_stop".equals(action)) note = note.trim() + " It may have been started again at once by the system, one of its services or another app.\n\n";
            row.put("success", real);
            row.put("label", label);
            row.put("verified", true);
            row.put("commandOk", cmdOk);
            row.put("output", (note + row.optString("output", "")).trim());
            if (real) ok++;
        }
        return ok;
    }

    // ---- Clear data, in Root mode only: the app's own data folder is readable there, so what is in it before and after can be counted ----

    /** The shell line that measures an app's private data folder: "KB=<size in KB>" and "FILES=<regular files>". */
    static String dataStatCmd(String pkg) {
        return dataStatCmd(pkg, "/data/user/0/" + pkg);
    }

    /** The same for any folder (the app's folder on the shared storage, for ADB and Shizuku). */
    static String dataStatCmd(String pkg, String dir) {
        return "echo KB=$(du -sk " + dir + " 2>/dev/null | cut -f1); echo FILES=$(find " + dir + " -type f 2>/dev/null | wc -l | tr -d ' ')";
    }

    /** {kb, files} from the output of {@link #dataStatCmd}; null when it does not hold both numbers (no such folder, not root, no du). */
    static long[] parseDataStat(String out) {
        if (out == null) return null;
        java.util.regex.Matcher k = java.util.regex.Pattern.compile("KB=\\s*(\\d+)").matcher(out);
        java.util.regex.Matcher f = java.util.regex.Pattern.compile("FILES=\\s*(\\d+)").matcher(out);
        if (!k.find() || !f.find()) return null;
        try { return new long[] { Long.parseLong(k.group(1)), Long.parseLong(f.group(1)) }; } catch (NumberFormatException e) { return null; }
    }

    static String fmtKb(long kb) {
        if (kb >= 1024L * 1024L) return String.format(Locale.ROOT, "%.1f GB", kb / 1048576.0);
        if (kb >= 1024L) return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
        return kb + " KB";
    }

    /** Rewrites a Clear data row from the counts before and after (internal folder, Root). */
    static boolean applyClear(JSONObject row, long[] before, long[] after) throws org.json.JSONException {
        return applyClear(row, before, after, null, true, false);
    }

    /**
     * Rewrites a Clear data row from what was counted: before, right after, and (after a pause) a second time, so files the app wrote again are seen.
     * {@code internal} = the app's private folder (Root); otherwise its folder on the shared storage (Android/data/package), the only part ADB and Shizuku can see:
     * there an empty folder before proves nothing, so the command's answer stands with a note. {@code running} = the app's process is up again.
     * Returns whether the row succeeded.
     */
    static boolean applyClear(JSONObject row, long[] before, long[] after, long[] after2, boolean internal, boolean running) throws org.json.JSONException {
        boolean cmdOk = row.optBoolean("success", false);
        long[] fin = after2 != null ? after2 : after;
        long back = after2 != null && after2[1] > after[1] ? after2[1] - after[1] : 0;
        String where = internal ? "the app's data folder" : "the app's folder on the shared storage (its private folder cannot be read in this mode)";
        String counts = before[1] + " file" + (before[1] == 1 ? "" : "s") + " (" + fmtKb(before[0]) + ") before, " + fin[1] + " after (" + fmtKb(fin[0]) + ")";
        if (before[1] == 0) {
            String note = "Checked afterwards: " + where + " was empty before, so there was nothing to compare." + (running ? " The app is running again." : "");
            row.put("output", (note + "\n\n" + row.optString("output", "")).trim());
            if (internal) { row.put("success", cmdOk); row.put("label", "Nothing to clear"); row.put("verified", true); row.put("commandOk", cmdOk); }
            return cmdOk;
        }
        boolean real;
        String label;
        if (fin[1] == 0 || (fin[1] < before[1] && fin[0] * 10 <= before[0])) { real = true; label = back > 0 ? "Cleared, new files written" : "Data cleared"; }
        else if (fin[1] < before[1]) { real = false; label = "Partly cleared"; }
        else { real = false; label = "Not cleared"; }
        String note = "Checked afterwards (" + where + "): " + counts + ".";
        if (back > 0) note += " " + back + " file" + (back == 1 ? "" : "s") + " appeared again after a moment: the app started again and wrote new data.";
        else if (real && running) note += " The app is running again.";
        if (real && !cmdOk) note += " The command reported a failure, but the data is gone.";
        else if (!real && cmdOk) note += " The command reported success, but the data is still there" + (fin[1] < before[1] ? " (the app may have started again and written new files)." : ".");
        row.put("success", real);
        row.put("label", label);
        row.put("verified", true);
        row.put("commandOk", cmdOk);
        row.put("output", (note + "\n\n" + row.optString("output", "")).trim());
        return real;
    }
}
