package com.bloatware.bingblop;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the Quick Settings tiles and the home-screen widget share: reading the saved mode and the
 * "quick list", and building the intents that run an action in {@link QuickActionActivity}.
 * Nothing here talks to a backend; the actual work happens in MainActivity.
 */
final class QuickActions {

    static final String PREFS = "adb_app_manager_prefs";
    static final String EXTRA_ACTION = "quick_action";
    static final String ACTION_CYCLE_MODE = "cycle_mode";
    static final String ACTION_STOP_LIST = "stop_list";
    /** The System UI Tuner's Demo Mode tile: turns demo mode on or off through the working mode. */
    static final String ACTION_DEMO_TOGGLE = "demo_toggle";

    private QuickActions() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String modeLabel(String mode) {
        if ("adb_tcp".equals(mode)) return "ADB TCP";
        if ("adb_wireless".equals(mode)) return "Wireless Debugging";
        if ("shizuku".equals(mode)) return "Shizuku";
        if ("root".equals(mode)) return "Root";
        if ("unprivileged".equals(mode)) return "Read-Only";
        return "Automatic";
    }

    static String currentModeLabel(Context c) {
        return modeLabel(prefs(c).getString("working_mode", "auto"));
    }

    /** The saved list chosen as the quick list: {id, name, packages}, or null when none is set. */
    static JSONObject quickList(Context c) {
        try {
            SharedPreferences p = prefs(c);
            String id = p.getString("quick_list_id", "");
            if (id.isEmpty()) return null;
            JSONArray lists = new JSONArray(p.getString("custom_app_lists", "[]"));
            for (int i = 0; i < lists.length(); i++) {
                JSONObject l = lists.optJSONObject(i);
                if (l != null && id.equals(l.optString("id"))) return l;
            }
        } catch (Exception ignored) {}
        return null;
    }

    static String quickListSummary(Context c) {
        JSONObject l = quickList(c);
        if (l == null) return "No quick list set";
        JSONArray pk = l.optJSONArray("packages");
        return l.optString("name", "List") + " (" + (pk == null ? 0 : pk.length()) + " apps)";
    }

    static Intent actionIntent(Context c, String action) {
        Intent i = new Intent(c, QuickActionActivity.class);
        i.putExtra(EXTRA_ACTION, action);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return i;
    }

    static PendingIntent pending(Context c, String action, int requestCode) {
        return PendingIntent.getActivity(c, requestCode, actionIntent(c, action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
