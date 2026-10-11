package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Thin client for the ShizuStore catalog API (https://github.com/timschneeb/ShizuStore). The backend
 * indexes timschneeb's awesome-shizuku list and serves app metadata plus direct upstream APK links
 * (GitHub/GitLab/F-Droid/...). We only read the public catalog and let this app's existing installer
 * plumbing install the APK; nothing is rehosted. Pure HTTP/JSON helpers (no Android UI) so the catalog
 * merge logic can be unit-tested off-device.
 */
public final class ShizuStore {

    /** Production catalog server (BuildConfig.API_BASE_URL in the upstream app). */
    public static final String BASE = "https://shizustore.timschneeberger.me";

    private ShizuStore() {}

    public static String appsUrl(int page, int pageSize) {
        return BASE + "/v1/apps?page=" + page + "&pageSize=" + pageSize;
    }

    public static String appUrl(String slug) {
        return BASE + "/v1/apps/" + android.net.Uri.encode(slug);
    }

    /** Immutable icon URL for a summary/detail iconHash, or "" when the app ships none. */
    public static String iconUrl(String iconHash) {
        if (iconHash == null || iconHash.isEmpty()) return "";
        return BASE + "/icons/" + iconHash + ".png";
    }

    /**
     * Fetches the whole catalog, following the `total` count across pages (capped so a runaway total
     * can't spin forever). Returns {"items":[...],"total":N}.
     */
    public static JSONObject catalog(int pageSize, int maxItems) throws Exception {
        JSONArray all = new JSONArray();
        int total = Integer.MAX_VALUE;
        int page = 1;
        while (all.length() < total && all.length() < maxItems && page <= 50) {
            String body = new String(UpdateManager.httpGet(appsUrl(page, pageSize), "application/json", 8 * 1024 * 1024), "UTF-8");
            JSONObject obj = new JSONObject(body);
            total = obj.optInt("total", all.length());
            JSONArray items = obj.optJSONArray("items");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) all.put(items.get(i));
            page++;
        }
        JSONObject out = new JSONObject();
        out.put("items", all);
        out.put("total", total == Integer.MAX_VALUE ? all.length() : total);
        return out;
    }

    /** App detail document for a slug. */
    public static JSONObject app(String slug) throws Exception {
        String body = new String(UpdateManager.httpGet(appUrl(slug), "application/json", 8 * 1024 * 1024), "UTF-8");
        return new JSONObject(body);
    }

    /**
     * Picks the APK to install from a detail document's downloads[]: the primary flavor first, else the
     * one whose packageName matches the entry, else the first with an apkUrl. Returns {apkUrl, packageName,
     * versionName, versionCode, size, sha256, source} or null when the entry has no direct APK (Play-only).
     */
    public static JSONObject primaryDownload(JSONObject detail) {
        if (detail == null) return null;
        JSONArray downloads = detail.optJSONArray("downloads");
        if (downloads == null || downloads.length() == 0) return null;
        String entryPkg = detail.optString("packageName", "");
        JSONObject primary = null, pkgMatch = null, first = null;
        for (int i = 0; i < downloads.length(); i++) {
            JSONObject d = downloads.optJSONObject(i);
            if (d == null) continue;
            String apkUrl = d.optString("apkUrl", "");
            if (apkUrl.isEmpty() || !apkUrl.startsWith("https://")) continue;
            if (first == null) first = d;
            if (d.optBoolean("primary", false) && primary == null) primary = d;
            if (!entryPkg.isEmpty() && entryPkg.equals(d.optString("packageName", "")) && pkgMatch == null) pkgMatch = d;
        }
        JSONObject chosen = primary != null ? primary : (pkgMatch != null ? pkgMatch : first);
        if (chosen == null) return null;
        try {
            JSONObject out = new JSONObject();
            out.put("apkUrl", chosen.optString("apkUrl", ""));
            out.put("packageName", chosen.optString("packageName", entryPkg));
            out.put("versionName", chosen.optString("versionName", detail.optString("versionName", "")));
            out.put("versionCode", chosen.optLong("versionCode", detail.optLong("versionCode", 0)));
            out.put("size", chosen.optLong("size", 0));
            out.put("sha256", chosen.optString("sha256", ""));
            out.put("source", chosen.optString("source", detail.optString("sourceName", "")));
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
