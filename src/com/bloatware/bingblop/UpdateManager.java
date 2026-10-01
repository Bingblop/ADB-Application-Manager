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
        Matcher m = Pattern.compile("^[vV]?([0-9]+(?:\\.[0-9]+)*)").matcher(v == null ? "" : v.trim());
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
