package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the System UI Tuner tab asks for, in the page's words, turned into the commands of {@link SysUiRules}: one call per operation, the answer is
 * {"ok":true,"cmd":"..."} (a command for the page to run through the working mode), {"ok":true,"data":{...}} (a parsed answer) or
 * {"ok":false,"error":"a sentence"}. Pure Java, so the same checks run on a plain JDK. Nothing here runs a command.
 *
 * The commands, the Quick Settings tiles and the demo mode protocol follow the System UI Tuner app (Tweaker) by Zachary Wander, MIT,
 * https://github.com/zacharee/Tweaker ; see NOTICE.
 */
public final class SysUiOps {
    private SysUiOps() {}

    public static String run(String op, String argsJson) {
        JSONObject a;
        try {
            a = argsJson == null || argsJson.trim().isEmpty() ? new JSONObject() : new JSONObject(argsJson);
        } catch (Exception e) {
            return err("The request could not be read");
        }
        try {
            return dispatch(op == null ? "" : op, a);
        } catch (IllegalArgumentException e) {
            return err(e.getMessage() == null ? "That value is not allowed" : e.getMessage());
        } catch (Exception e) {
            return err("That did not work: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static String cmd(String c) {
        try { return new JSONObject().put("ok", true).put("cmd", c).toString(); } catch (Exception e) { return err("internal"); }
    }

    private static String data(JSONObject d) {
        try { return new JSONObject().put("ok", true).put("data", d).toString(); } catch (Exception e) { return err("internal"); }
    }

    private static String err(String m) {
        try { return new JSONObject().put("ok", false).put("error", m).toString(); } catch (Exception e) { return "{\"ok\":false}"; }
    }

    private static List<String> strings(JSONArray arr) {
        List<String> l = new ArrayList<String>();
        for (int i = 0; arr != null && i < arr.length(); i++) l.add(arr.optString(i));
        return l;
    }

    private static String yesNo(boolean b) { return b ? "true" : "false"; }

    /** The page's demo state (clock, battery, bars, icons, wifi, mobile) as the keys of {@link SysUiRules#demoApply}. */
    static Map<String, String> demoState(JSONObject a) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (a.has("clock")) m.put("clock.hhmm", a.optString("clock"));
        JSONObject b = a.optJSONObject("battery");
        if (b != null) {
            if (b.has("level")) m.put("battery.level", String.valueOf(b.optInt("level")));
            if (b.has("plugged")) m.put("battery.plugged", yesNo(b.optBoolean("plugged")));
            if (b.has("powersave")) m.put("battery.powersave", yesNo(b.optBoolean("powersave")));
        }
        if (a.has("bars")) m.put("bars.mode", a.optString("bars"));
        if (a.has("notifications")) m.put("notifications.visible", yesNo("show".equals(a.optString("notifications"))));
        JSONObject ic = a.optJSONObject("icons");
        if (ic != null) {
            String[] keys = {"volume", "bluetooth", "location", "alarm", "tty", "mute", "speakerphone", "airplane", "zen", "cast", "hotspot"};
            for (String k : keys) if (ic.has(k)) m.put("status." + k, ic.optString(k));
        }
        if (a.has("sims")) m.put("network.sims", String.valueOf(a.optInt("sims", 1)));
        JSONObject w = a.optJSONObject("wifi");
        if (w != null) {
            if (w.has("show")) m.put("wifi.show", w.optString("show"));
            if (w.has("level")) m.put("wifi.level", String.valueOf(w.optInt("level")));
            if (w.has("fully")) m.put("wifi.fully", yesNo(w.optBoolean("fully")));
            if (w.has("activity")) m.put("wifi.activity", w.optString("activity"));
            if (w.has("ssid")) m.put("wifi.ssid", w.optString("ssid"));
        }
        JSONObject mo = a.optJSONObject("mobile");
        if (mo != null) {
            if (mo.has("show")) m.put("mobile.show", mo.optString("show"));
            if (mo.has("level")) m.put("mobile.level", String.valueOf(mo.optInt("level")));
            if (mo.has("fully")) m.put("mobile.fully", yesNo(mo.optBoolean("fully")));
            if (mo.has("datatype")) m.put("mobile.datatype", mo.optString("datatype"));
            if (mo.has("roam")) m.put("mobile.roam", mo.optString("roam"));
            if (mo.has("carrierchange")) m.put("mobile.carriernetworkchange", mo.optString("carrierchange"));
            if (mo.has("inflate")) m.put("mobile.inflate", yesNo(mo.optBoolean("inflate")));
            if (mo.has("activity")) m.put("mobile.activity", mo.optString("activity"));
            if (mo.has("networkname")) m.put("mobile.networkname", mo.optString("networkname"));
        }
        return m;
    }

    private static String dispatch(String op, JSONObject a) throws Exception {
        switch (op) {
            // ---- demo mode ----
            case "demo.allowedRead": return cmd(SysUiRules.demoAllowedRead());
            case "demo.allow": return cmd(SysUiRules.demoAllowCommands(a.optBoolean("allow")));
            case "demo.apply": return cmd(SysUiRules.demoEnter() + "\n" + SysUiRules.demoApply(demoState(a)));
            case "demo.exit": return cmd(SysUiRules.demoExit());
            // ---- status bar ----
            case "shade": return cmd(SysUiRules.shadeCommand(a.optString("which")));
            case "disable": return cmd(SysUiRules.disableFlagsCommand(strings(a.optJSONArray("flags"))));
            case "disableRisky": {
                JSONArray r = new JSONArray();
                for (String f : SysUiRules.disableFlagsRisky(strings(a.optJSONArray("flags")))) r.put(f);
                return data(new JSONObject().put("risky", r));
            }
            case "icons": return cmd(SysUiRules.statusIconSlotsCommand());
            case "info": return cmd(SysUiRules.infoCommand(a.optString("which")));
            // ---- notifications ----
            case "notify.post":
                return cmd(SysUiRules.notifyPostCommand(a.optString("style", "basic"), a.optString("title", ""), a.optString("text", ""), a.optString("tag", ""),
                        strings(a.optJSONArray("lines")), strings(a.optJSONArray("messages")), a.optString("conversation", "")));
            case "notify.list": return cmd(SysUiRules.notifyListCommand());
            case "notify.snooze": return cmd(SysUiRules.notifySnoozeCommand(a.optString("key"), a.optLong("ms")));
            case "notify.unsnooze": return cmd(SysUiRules.notifyUnsnoozeCommand(a.optString("key")));
            // ---- system ----
            case "action": return cmd(SysUiRules.systemActionCommand(a.optInt("id")));
            case "restartUi": return cmd(SysUiRules.restartSystemUiCommand());
            // ---- battery ----
            case "battery.set": return cmd(SysUiRules.batterySetCommand(a.optString("key"), a.optInt("value")));
            case "battery.unplug": return cmd(SysUiRules.batteryUnplugCommand());
            case "battery.reset": return cmd(SysUiRules.batteryResetCommand());
            case "battery.read": return cmd(SysUiRules.batteryReadCommand());
            // ---- navigation ----
            case "nav.list": return cmd(SysUiRules.navListCommand());
            case "nav.enable": return cmd(SysUiRules.navEnableCommand(a.optString("mode")));
            // ---- display and windows ----
            case "wm.read": return cmd(SysUiRules.wmReadCommand());
            case "wm.size": return cmd(SysUiRules.wmSizeCommand(a.optInt("w"), a.optInt("h")));
            case "wm.density": return cmd(SysUiRules.wmDensityCommand(a.optInt("value")));
            case "wm.size.reset": return cmd(SysUiRules.wmSizeResetCommand());
            case "wm.density.reset": return cmd(SysUiRules.wmDensityResetCommand());
            case "wm.ignoreOrientation": return cmd(SysUiRules.wmIgnoreOrientationCommand(a.optBoolean("on")));
            case "wm.scaling": return cmd(SysUiRules.wmScalingCommand(a.optString("mode")));
            // ---- the answers of those commands ----
            case "parse.demoAllowed": {
                Boolean b = SysUiRules.parseDemoAllowed(a.optString("text"));
                return data(new JSONObject().put("allowed", b != null && b.booleanValue()).put("known", b != null));
            }
            case "parse.disableState": {
                JSONArray r = new JSONArray();
                for (String f : SysUiRules.parseDisableState(a.optString("text"))) r.put(f);
                return data(new JSONObject().put("flags", r));
            }
            case "parse.slots": {
                JSONArray r = new JSONArray();
                for (String f : SysUiRules.parseStatusIconSlots(a.optString("text"))) r.put(f);
                return data(new JSONObject().put("slots", r));
            }
            case "parse.notificationKeys": {
                JSONArray r = new JSONArray();
                for (String f : SysUiRules.parseNotificationKeys(a.optString("text"))) r.put(f);
                return data(new JSONObject().put("keys", r));
            }
            case "parse.battery": {
                JSONObject d = new JSONObject();
                for (Map.Entry<String, Integer> e : SysUiRules.parseBatteryState(a.optString("text")).entrySet()) d.put(e.getKey(), e.getValue().intValue());
                return data(d.put("frozen", SysUiRules.isFrozen(a.optString("text"))));
            }
            case "parse.navModes": {
                Map<String, String> modes = SysUiRules.parseNavModes(a.optString("text"));
                JSONObject d = new JSONObject();
                for (Map.Entry<String, String> e : modes.entrySet()) d.put(e.getKey(), e.getValue());
                String on = SysUiRules.navEnabled(modes);
                JSONArray avail = new JSONArray();
                for (Map.Entry<String, String> e : modes.entrySet()) if (!"unavailable".equals(e.getValue())) avail.put(e.getKey());
                return data(new JSONObject().put("modes", d).put("available", avail).put("enabled", on == null ? "" : on));
            }
            case "parse.wm": {
                JSONObject d = new JSONObject();
                for (Map.Entry<String, String> e : SysUiRules.parseWmState(a.optString("text")).entrySet()) d.put(e.getKey(), e.getValue());
                return data(d);
            }
            default:
                return err("Unknown operation \"" + (op.length() > 40 ? op.substring(0, 40) : op) + "\"");
        }
    }
}
