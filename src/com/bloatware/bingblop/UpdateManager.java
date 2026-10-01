package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Update sources for the Updates tab:
 *  - Galaxy Store: Samsung's public "stub" update service (stubUpdateCheck.as / stubDownload.as), the
 *    same endpoints the Galaxy Store uses for system apps. Returns small XML documents.
 *  - GitHub Releases: this app's own releases.
 * Pure helpers only (no Android UI), so they can be unit-tested off-device.
 */
public final class UpdateManager {

    public static final String GALAXY_STORE_PKG = "com.sec.android.app.samsungapps";
    public static final String SELF_REPO = "Bingblop/ADB-Application-Manager";
    private static final String STUB_BASE = "https://vas.samsungapps.com/stub/";

    public interface Progress {
        void onProgress(long done, long total);
    }

    /** Device identity Samsung's service uses to pick the right build for this phone and region. */
    public static final class Device {
        public final String model, mcc, mnc, csc;
        public final int sdk;

        public Device(String model, String mcc, String mnc, String csc, int sdk) {
            this.model = model == null || model.isEmpty() ? "SM-S938B" : model;
            this.mcc = mcc == null || mcc.isEmpty() ? "310" : mcc;
            this.mnc = mnc == null || mnc.isEmpty() ? "00" : mnc;
            this.csc = csc == null ? "" : csc;
            this.sdk = sdk;
        }

        String query() throws Exception {
            return "&deviceId=" + enc(model) + "&mcc=" + enc(mcc) + "&mnc=" + enc(mnc)
                    + "&csc=" + enc(csc) + "&sdkVer=" + sdk + "&pd=0";
        }
    }

    private UpdateManager() {}

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    // ------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------

    public static byte[] httpGet(String url, String accept, int maxBytes) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ADB-Application-Manager");
            if (accept != null) conn.setRequestProperty("Accept", accept);
            int code = conn.getResponseCode();
            if (code == 404) throw new IllegalStateException("not found (HTTP 404)");
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) throw new IllegalStateException("response too large");
            }
            in.close();
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    public static void download(String url, File dest, Progress progress) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ADB-Application-Manager");
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("download failed: HTTP " + code);
            long total = conn.getContentLength();
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            long done = 0;
            long lastReport = 0;
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null && done - lastReport > 256 * 1024) {
                        lastReport = done;
                        progress.onProgress(done, total);
                    }
                }
            } finally {
                in.close();
                out.close();
            }
            if (total > 0 && done != total) throw new IllegalStateException("download incomplete");
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) throw new IllegalStateException("could not save download");
            if (progress != null) progress.onProgress(done, total);
        } finally {
            conn.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Galaxy Store
    // ------------------------------------------------------------------------------------------

    /** Text of the first <tag>...</tag>, with CDATA unwrapped, or "" */
    public static String xmlTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</" + tag + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * Asks the Galaxy Store whether a newer version exists.
     * resultCode 2 = update available, 1 = up to date, 0 = not distributed through the Galaxy Store.
     */
    public static JSONObject galaxyCheck(String pkg, long installedVersionCode, Device d) throws Exception {
        String url = STUB_BASE + "stubUpdateCheck.as?appId=" + enc(pkg) + "&versionCode=" + installedVersionCode + d.query();
        String xml = new String(httpGet(url, null, 256 * 1024), "UTF-8");
        return parseGalaxyCheck(xml, installedVersionCode);
    }

    static JSONObject parseGalaxyCheck(String xml, long installedVersionCode) throws Exception {
        JSONObject o = new JSONObject();
        String code = xmlTag(xml, "resultCode");
        long remoteVc = parseLong(xmlTag(xml, "versionCode"));
        o.put("resultCode", code);
        o.put("resultMsg", xmlTag(xml, "resultMsg"));
        o.put("versionCode", remoteVc);
        o.put("versionName", xmlTag(xml, "versionName"));
        o.put("available", "2".equals(code) && remoteVc > installedVersionCode);
        return o;
    }

    /** Official APK download link for the latest version (short-lived). */
    public static String galaxyDownloadUrl(String pkg, Device d) throws Exception {
        String url = STUB_BASE + "stubDownload.as?appId=" + enc(pkg) + d.query();
        String xml = new String(httpGet(url, null, 256 * 1024), "UTF-8");
        String uri = xmlTag(xml, "downloadURI");
        if (uri.isEmpty() || !uri.startsWith("http")) {
            String msg = xmlTag(xml, "resultMsg");
            throw new IllegalStateException("Galaxy Store gave no download link" + (msg.isEmpty() ? "" : ": " + msg));
        }
        return uri.replace("&amp;", "&");
    }

    // ------------------------------------------------------------------------------------------
    // Open-source sources: GitHub, Codeberg, F-Droid, IzzyOnDroid, Obtainium catalog
    // ------------------------------------------------------------------------------------------

    public static final String FDROID_API = "https://f-droid.org/api/v1/packages/";
    public static final String FDROID_REPO = "https://f-droid.org/repo/";
    public static final String IZZY_API = "https://apt.izzysoft.de/fdroid/api/v1/packages/";
    public static final String IZZY_REPO = "https://apt.izzysoft.de/fdroid/repo/";
    private static final String OBTAINIUM_CATALOG = "https://raw.githubusercontent.com/ImranR98/apps.obtainium.imranr.dev/main/public/data/apps/";

    /** A release: tag, display version, page, notes and its APK assets ([name, url] pairs). */
    public static final class Release {
        public String tag = "", version = "", page = "", notes = "";
        public final java.util.List<String[]> assets = new java.util.ArrayList<String[]>();
    }

    /** {"host":"github.com","owner":..,"repo":..} for github.com / codeberg.org URLs, else null */
    public static JSONObject parseRepoUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("^https?://(?:www\\.)?(github\\.com|codeberg\\.org)/([^/\\s]+)/([^/\\s#?]+)").matcher(url.trim());
        if (!m.find()) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("host", m.group(1));
            o.put("owner", m.group(2));
            o.put("repo", m.group(3).replaceAll("\\.git$", ""));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Package id from an F-Droid / IzzyOnDroid package page URL, else null */
    public static String parseFdroidUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("^https?://(?:www\\.)?(?:f-droid\\.org(?:/[a-z_A-Z-]+)?|apt\\.izzysoft\\.de/fdroid/index/apk)/(?:packages/)?([A-Za-z0-9_.]+)/?$").matcher(url.trim());
        return m.find() ? m.group(1) : null;
    }

    private static Release releaseFromApiJson(JSONObject rel, String fallbackPage) {
        Release r = new Release();
        r.tag = rel.optString("tag_name", "");
        r.version = r.tag;
        r.page = rel.optString("html_url", fallbackPage);
        String notes = rel.optString("body", "");
        r.notes = notes.length() > 600 ? notes.substring(0, 600) + "…" : notes;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                if (name.toLowerCase().endsWith(".apk") && url.startsWith("https://")) r.assets.add(new String[]{name, url});
            }
        }
        return r;
    }

    /**
     * Latest GitHub release. Uses the API (60 requests/hour without a token); when rate-limited it
     * falls back to the public release pages, which have no API limit.
     */
    public static Release githubRelease(String owner, String repo, String token, boolean includePrereleases) throws Exception {
        String base = "https://api.github.com/repos/" + owner + "/" + repo;
        String page = "https://github.com/" + owner + "/" + repo + "/releases";
        try {
            if (includePrereleases) {
                JSONArray list = new JSONArray(new String(httpGetAuth(base + "/releases?per_page=10", token), "UTF-8"));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject rel = list.getJSONObject(i);
                    if (!rel.optBoolean("draft")) return releaseFromApiJson(rel, page);
                }
                throw new IllegalStateException("no releases");
            }
            return releaseFromApiJson(new JSONObject(new String(httpGetAuth(base + "/releases/latest", token), "UTF-8")), page);
        } catch (IllegalStateException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("HTTP 403") || msg.contains("HTTP 429")) return githubReleaseFromPages(owner, repo);
            throw e;
        }
    }

    private static byte[] httpGetAuth(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "ADB-Application-Manager");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            if (code == 404) throw new IllegalStateException("no releases (HTTP 404)");
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IllegalStateException("response too large");
            }
            in.close();
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /** Rate-limit-free fallback: /releases/latest redirects to the tag, expanded_assets lists the files. */
    static Release githubReleaseFromPages(String owner, String repo) throws Exception {
        String page = "https://github.com/" + owner + "/" + repo + "/releases/latest";
        HttpURLConnection conn = (HttpURLConnection) new URL(page).openConnection();
        String location;
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "ADB-Application-Manager");
            int code = conn.getResponseCode();
            location = conn.getHeaderField("Location");
            if (code / 100 != 3 || location == null || !location.contains("/releases/tag/")) {
                throw new IllegalStateException("no releases");
            }
        } finally {
            conn.disconnect();
        }
        Release r = new Release();
        r.tag = java.net.URLDecoder.decode(location.substring(location.indexOf("/releases/tag/") + 14), "UTF-8");
        r.version = r.tag;
        r.page = location;
        String html = new String(httpGet("https://github.com/" + owner + "/" + repo + "/releases/expanded_assets/" + enc(r.tag).replace("+", "%20"),
                "text/html", 4 * 1024 * 1024), "UTF-8");
        r.assets.addAll(parseExpandedAssets(html, owner, repo));
        return r;
    }

    static java.util.List<String[]> parseExpandedAssets(String html, String owner, String repo) throws Exception {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        Matcher m = Pattern.compile("href=\"(/" + Pattern.quote(owner) + "/" + Pattern.quote(repo) + "/releases/download/[^\"]+?\\.apk)\"", Pattern.CASE_INSENSITIVE).matcher(html);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        while (m.find()) {
            String path = m.group(1).replace("&amp;", "&");
            if (!seen.add(path)) continue;
            String name = java.net.URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), "UTF-8");
            out.add(new String[]{name, "https://github.com" + path});
        }
        return out;
    }

    /** Latest Codeberg (Forgejo) release */
    public static Release codebergRelease(String owner, String repo) throws Exception {
        String url = "https://codeberg.org/api/v1/repos/" + owner + "/" + repo + "/releases/latest";
        JSONObject rel = new JSONObject(new String(httpGet(url, "application/json", 4 * 1024 * 1024), "UTF-8"));
        return releaseFromApiJson(rel, "https://codeberg.org/" + owner + "/" + repo + "/releases");
    }

    /** {"versionCode":..,"versionName":..} of the suggested version in an F-Droid style repo, or null if unknown there */
    public static JSONObject fdroidLatest(String apiBase, String pkg) throws Exception {
        String json;
        try {
            json = new String(httpGet(apiBase + enc(pkg), "application/json", 1024 * 1024), "UTF-8");
        } catch (IllegalStateException e) {
            if (String.valueOf(e.getMessage()).contains("404")) return null;
            throw e;
        }
        JSONObject o = new JSONObject(json);
        long suggested = o.optLong("suggestedVersionCode", 0);
        JSONArray pkgs = o.optJSONArray("packages");
        JSONObject best = null;
        if (pkgs != null) {
            for (int i = 0; i < pkgs.length(); i++) {
                JSONObject p = pkgs.getJSONObject(i);
                if (suggested > 0 && p.optLong("versionCode") == suggested) {
                    best = p;
                    break;
                }
                if (best == null || p.optLong("versionCode") > best.optLong("versionCode")) best = p;
            }
        }
        if (best == null) return null;
        JSONObject r = new JSONObject();
        r.put("versionCode", best.optLong("versionCode"));
        r.put("versionName", best.optString("versionName"));
        return r;
    }

    /** The Obtainium community catalog entry for a package (first config), or null when it isn't listed */
    public static JSONObject obtainiumCatalog(String pkg) throws Exception {
        for (String kind : new String[]{"simple", "complex"}) {
            try {
                JSONObject doc = new JSONObject(new String(httpGet(OBTAINIUM_CATALOG + kind + "/" + enc(pkg) + ".json", "application/json", 512 * 1024), "UTF-8"));
                JSONObject cfg = doc.optJSONObject("config");
                if (cfg == null && doc.optJSONArray("configs") != null && doc.optJSONArray("configs").length() > 0) {
                    cfg = doc.optJSONArray("configs").optJSONObject(0);
                }
                if (cfg != null) return cfg;
            } catch (IllegalStateException e) {
                if (!String.valueOf(e.getMessage()).contains("404")) throw e;
            }
        }
        return null;
    }

    /** Obtainium's additionalSettings is a JSON string inside the config */
    public static JSONObject obtainiumSettings(JSONObject cfg) {
        if (cfg == null) return new JSONObject();
        Object a = cfg.opt("additionalSettings");
        try {
            if (a instanceof JSONObject) return (JSONObject) a;
            if (a instanceof String && !((String) a).isEmpty()) return new JSONObject((String) a);
        } catch (Exception ignored) {}
        return new JSONObject();
    }

    /** Applies Obtainium's versionExtractionRegEx to a tag ("app-v1.2.3" -> "1.2.3") */
    public static String extractVersion(String tag, String regex, String group) {
        if (regex == null || regex.isEmpty()) return tag;
        try {
            Matcher m = Pattern.compile(regex).matcher(tag);
            if (!m.find()) return tag;
            int g = 0;
            try {
                g = Integer.parseInt(group == null || group.isEmpty() ? "0" : group.replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {}
            return g <= m.groupCount() && m.group(g) != null ? m.group(g) : m.group(0);
        } catch (Exception e) {
            return tag;
        }
    }

    /**
     * Chooses the APK for this phone: honours Obtainium's apkFilterRegEx, prefers the device ABI,
     * then universal builds, and avoids builds for other architectures.
     */
    public static String[] pickApk(java.util.List<String[]> assets, String filterRegex, boolean invertFilter, String[] abis) {
        String[] best = null;
        int bestScore = Integer.MIN_VALUE;
        Pattern filter = null;
        try {
            if (filterRegex != null && !filterRegex.isEmpty()) filter = Pattern.compile(filterRegex);
        } catch (Exception ignored) {}
        String primary = abis != null && abis.length > 0 ? abis[0].toLowerCase() : "arm64-v8a";
        for (String[] a : assets) {
            String name = a[0].toLowerCase();
            if (!name.endsWith(".apk")) continue;
            if (filter != null && filter.matcher(a[0]).find() == invertFilter) continue;
            int score = 0;
            boolean mentionsArch = false;
            String[][] archNames = {{"arm64-v8a", "arm64", "aarch64", "armv8"}, {"armeabi-v7a", "armeabi", "armv7", "arm32"}, {"x86_64", "x64", "amd64"}, {"x86", "i686"}};
            for (String[] group : archNames) {
                boolean matches = false;
                for (String n : group) if (name.contains(n)) matches = true;
                if (!matches) continue;
                mentionsArch = true;
                score += group[0].equals(primary) ? 30 : -50; // group[0] is the Android ABI name
                break;
            }
            if (name.contains("universal") || name.contains("all")) score += 20;
            if (!mentionsArch) score += 10;
            if (name.contains("debug") || name.contains("unsigned")) score -= 25;
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best != null && bestScore > -40 ? best : null;
    }

    // ------------------------------------------------------------------------------------------
    // GitHub Releases (this app)
    // ------------------------------------------------------------------------------------------

    /** {"version":"3.9","tag":"v3.9","url":"...apk","page":"...","notes":"..."} for the latest release */
    public static JSONObject githubLatest(String repo) throws Exception {
        String json = new String(httpGet("https://api.github.com/repos/" + repo + "/releases/latest",
                "application/vnd.github+json", 2 * 1024 * 1024), "UTF-8");
        JSONObject rel = new JSONObject(json);
        JSONObject o = new JSONObject();
        String tag = rel.optString("tag_name", "");
        o.put("tag", tag);
        o.put("version", tag.replaceFirst("^[vV]", ""));
        o.put("page", rel.optString("html_url", "https://github.com/" + repo + "/releases"));
        String notes = rel.optString("body", "");
        o.put("notes", notes.length() > 600 ? notes.substring(0, 600) + "…" : notes);
        JSONArray assets = rel.optJSONArray("assets");
        String apk = "";
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                    apk = a.optString("browser_download_url", "");
                    break;
                }
            }
        }
        o.put("url", apk);
        return o;
    }

    /** Compares dotted versions numerically ("3.10-Pro" > "3.9"); suffixes are ignored. */
    public static int compareVersions(String a, String b) {
        String[] pa = numericParts(a), pb = numericParts(b);
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? parseLong(pa[i]) : 0;
            long y = i < pb.length ? parseLong(pb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static String[] numericParts(String v) {
        // First dotted number anywhere: "v1.2.3", "release-1.2.3", "app_1.2.3-beta"
        Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)*)").matcher(v == null ? "" : v.trim());
        return m.find() ? m.group(1).split("\\.") : new String[]{"0"};
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
