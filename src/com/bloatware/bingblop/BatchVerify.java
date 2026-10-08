package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a finished batch against the phone instead of against what the commands printed. On some phones a run of
 * {@code pm uninstall} lines "failed" (a non-zero exit status, or words no list of keywords foresaw) although every
 * app was gone, so after uninstall, reinstall, freeze and unfreeze the app asks the package manager what each app
 * is now and reports that. Pure logic: the shell calls and the one retry are in MainActivity.
 */
final class BatchVerify {
    private BatchVerify() {}

    /** Whether the result of this action can be read back from the package lists. */
    static boolean verifiable(String action) {
        return "uninstall".equals(action) || "uninstall_keep_data".equals(action) || "reinstall".equals(action)
                || "freeze".equals(action) || "unfreeze".equals(action);
    }

    /** Whether the disabled-packages list is needed too. */
    static boolean needsDisabled(String action) {
        return "freeze".equals(action) || "unfreeze".equals(action);
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
    static boolean stateOk(String action, String pkg, Set<String> installed, Set<String> disabled) {
        if ("uninstall".equals(action) || "uninstall_keep_data".equals(action)) return !installed.contains(pkg);
        if ("reinstall".equals(action)) return installed.contains(pkg);
        if ("freeze".equals(action)) return installed.contains(pkg) && disabled.contains(pkg);
        return installed.contains(pkg) && !disabled.contains(pkg);
    }

    /** True when every row is in its wanted state (when not, the package manager may still be finishing: ask once more). */
    static boolean allOk(String action, JSONArray rows, Set<String> installed, Set<String> disabled) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && !stateOk(action, row.optString("pkg", ""), installed, disabled)) return false;
        }
        return true;
    }

    static String label(String action, boolean real) {
        if ("uninstall".equals(action) || "uninstall_keep_data".equals(action)) return real ? "Uninstalled" : "Still installed";
        if ("reinstall".equals(action)) return real ? "Installed" : "Not installed";
        if ("freeze".equals(action)) return real ? "Frozen" : "Not frozen";
        return real ? "Enabled" : "Still frozen";
    }

    /** Rewrites each row from the real state: success, a label, a note where the phone and the command disagree. Returns how many succeeded. */
    static int apply(String action, JSONArray rows, Set<String> installed, Set<String> disabled) throws org.json.JSONException {
        int ok = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            boolean cmdOk = row.optBoolean("success", false);
            boolean real = stateOk(action, row.optString("pkg", ""), installed, disabled);
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
