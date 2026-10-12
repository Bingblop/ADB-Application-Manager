package com.bloatware.bingblop;

import org.json.JSONObject;

/**
 * The numbers behind the Extra Settings tab's "Device telemetry" section, put together from what the app already reads for the
 * Task Manager and the About tab (MemStats on /proc/meminfo, StatFs on /data, the battery broadcast, Build and /proc/version).
 * Pure Java, free of Android imports, so the shape of the answer is tested off the device. A value that could not be read is left
 * out of the JSON rather than written as 0 or null: the page shows "Not available" for what is missing, never a wrong number.
 */
final class Telemetry {

    private Telemetry() {}

    /**
     * The short kernel version out of /proc/version: the word after "Linux version " ("5.10.168-android13-4-00001-gabc"), or
     * the first word of the text when it does not start that way. "" for nothing.
     */
    static String kernelShort(String procVersion) {
        if (procVersion == null) return "";
        String s = procVersion.trim();
        if (s.isEmpty()) return "";
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl).trim();
        final String head = "Linux version ";
        if (s.startsWith(head)) s = s.substring(head.length()).trim();
        int sp = 0;
        while (sp < s.length() && !Character.isWhitespace(s.charAt(sp))) sp++;
        return s.substring(0, sp);
    }

    /**
     * The JSON the section shows. meminfo is the text of /proc/meminfo ("" when it could not be read); storageTotalBytes and
     * storageFreeBytes come from StatFs (negative when not read); battery is the Task Manager's battery object (may be null);
     * uptimeMs is SystemClock.elapsedRealtime(); the rest are Build values, Samsung's One UI version ("" on another phone) and
     * the text of /proc/version. Only what was read is written: mem needs MemTotal, storage needs a total, battery needs a
     * percent of 0 or more.
     */
    static JSONObject build(String meminfo, long storageTotalBytes, long storageFreeBytes, JSONObject battery, long uptimeMs,
                            String model, String manufacturer, String androidRelease, int sdk, String oneUi, String procVersion) {
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            MemStats.Reading mem = MemStats.parse(meminfo);
            if (mem.totalKb > 0) {
                JSONObject m = new JSONObject();
                m.put("totalKb", mem.totalKb);
                m.put("usedKb", MemStats.usedKb(mem));
                m.put("availableKb", mem.availableKb);
                m.put("swapTotalKb", mem.swapTotalKb);
                m.put("swapUsedKb", MemStats.swapUsedKb(mem));
                res.put("mem", m);
            }
            if (storageTotalBytes > 0 && storageFreeBytes >= 0 && storageFreeBytes <= storageTotalBytes) {
                JSONObject s = new JSONObject();
                s.put("totalBytes", storageTotalBytes);
                s.put("freeBytes", storageFreeBytes);
                s.put("usedBytes", storageTotalBytes - storageFreeBytes);
                res.put("storage", s);
            }
            if (battery != null && battery.optDouble("percent", -1) >= 0) res.put("battery", battery);
            if (uptimeMs >= 0) res.put("uptimeMs", uptimeMs);
            if (model != null && !model.trim().isEmpty()) res.put("model", model.trim());
            if (manufacturer != null && !manufacturer.trim().isEmpty()) res.put("manufacturer", manufacturer.trim());
            if (androidRelease != null && !androidRelease.trim().isEmpty()) res.put("android", androidRelease.trim());
            if (sdk > 0) res.put("sdk", sdk);
            if (oneUi != null && !oneUi.trim().isEmpty()) res.put("oneUi", oneUi.trim());
            String k = kernelShort(procVersion);
            if (!k.isEmpty()) res.put("kernel", k);
        } catch (Exception e) {
            try { res.put("ok", false); res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return res;
    }
}
