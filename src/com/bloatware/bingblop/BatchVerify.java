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
        State(Set<String> installed, Set<String> disabled, Set<String> suspended, Set<String> unknown) {
            this.installed = installed == null ? new HashSet<String>() : installed;
            this.disabled = disabled == null ? new HashSet<String>() : disabled;
            this.suspended = suspended == null ? new HashSet<String>() : suspended;
            this.unknown = unknown == null ? new HashSet<String>() : unknown;
        }
    }

    /** Whether the result of this action can be read back from the phone (the package lists, or an app's suspended flag). */
    static boolean verifiable(String action) {
        return "uninstall".equals(action) || "uninstall_keep_data".equals(action) || "reinstall".equals(action)
                || "freeze".equals(action) || "unfreeze".equals(action) || needsSuspended(action);
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
            row.put("success", real);
            row.put("label", label);
            row.put("verified", true);
            row.put("commandOk", cmdOk);
            row.put("output", (note + row.optString("output", "")).trim());
            if (real) ok++;
        }
        return ok;
    }
}
