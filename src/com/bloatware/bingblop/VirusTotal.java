package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Optional VirusTotal (v3) pre-install scan. The user supplies their own personal API key; nothing is
 * sent anywhere without it. The cheap, private path is a SHA-256 lookup (`GET /files/{hash}`) which
 * returns an existing report without uploading the file. Only if the file is unknown to VirusTotal, and
 * only when the user explicitly chooses to, is the APK uploaded for analysis. Pure HTTP/JSON helpers
 * (no Android UI); the stats-parsing is split out so it can be unit-tested off-device.
 */
public final class VirusTotal {

    private static final String API = "https://www.virustotal.com/api/v3";
    /** Direct multipart upload is only accepted up to 32 MB; larger files use a one-time upload URL. */
    private static final long DIRECT_UPLOAD_LIMIT = 32L * 1024 * 1024;

    private VirusTotal() {}

    public static String permalink(String sha256) {
        return "https://www.virustotal.com/gui/file/" + sha256;
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally { in.close(); }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    /** One raw HTTP response: status code + body text. */
    private static final class Resp {
        final int code; final String body;
        Resp(int code, String body) { this.code = code; this.body = body; }
    }

    private static Resp get(String url, String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ADB-Application-Manager");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("x-apikey", apiKey);
            int code = conn.getResponseCode();
            return new Resp(code, readBody(conn, code));
        } finally {
            conn.disconnect();
        }
    }

    private static String readBody(HttpURLConnection conn, int code) throws Exception {
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 4 * 1024 * 1024) break;
        }
        in.close();
        return out.toString("UTF-8");
    }

    /** Human-readable message for a non-200 VirusTotal response. */
    static String errorFor(int code, String body) {
        if (code == 401) return "VirusTotal rejected the API key (401). Check it in your VirusTotal account.";
        if (code == 429) return "VirusTotal rate limit reached (429). The free key allows ~4 lookups/min.";
        if (code == 400) return "VirusTotal rejected the request (400).";
        String detail = "";
        try {
            JSONObject o = new JSONObject(body);
            JSONObject err = o.optJSONObject("error");
            if (err != null) detail = err.optString("message", "");
        } catch (Exception ignored) {}
        return "VirusTotal error (HTTP " + code + ")" + (detail.isEmpty() ? "" : ": " + detail);
    }

    /**
     * Looks up an existing report by hash. Returns {found:true, stats...} when VirusTotal knows the file,
     * {found:false} on 404, or throws with a clear message on auth/rate/other errors.
     */
    public static JSONObject lookup(String apiKey, String sha256) throws Exception {
        Resp r = get(API + "/files/" + sha256, apiKey);
        if (r.code == 404) {
            JSONObject o = new JSONObject();
            o.put("found", false);
            o.put("sha256", sha256);
            return o;
        }
        if (r.code != 200) throw new IllegalStateException(errorFor(r.code, r.body));
        JSONObject result = statsFromFileResponse(r.body);
        result.put("sha256", sha256);
        result.put("permalink", permalink(sha256));
        return result;
    }

    /** Parses a `/files/{id}` body into the flat result the UI shows. */
    static JSONObject statsFromFileResponse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject attr = root.optJSONObject("data") != null ? root.getJSONObject("data").optJSONObject("attributes") : null;
        JSONObject out = new JSONObject();
        out.put("found", true);
        JSONObject stats = attr != null ? attr.optJSONObject("last_analysis_stats") : null;
        int malicious = stats != null ? stats.optInt("malicious", 0) : 0;
        int suspicious = stats != null ? stats.optInt("suspicious", 0) : 0;
        int harmless = stats != null ? stats.optInt("harmless", 0) : 0;
        int undetected = stats != null ? stats.optInt("undetected", 0) : 0;
        int timeout = stats != null ? stats.optInt("timeout", 0) : 0;
        out.put("malicious", malicious);
        out.put("suspicious", suspicious);
        out.put("harmless", harmless);
        out.put("undetected", undetected);
        out.put("timeout", timeout);
        out.put("total", malicious + suspicious + harmless + undetected + timeout);
        if (attr != null) {
            out.put("reputation", attr.optInt("reputation", 0));
            out.put("name", attr.optString("meaningful_name", ""));
            out.put("analysisDate", attr.optLong("last_analysis_date", 0));
        }
        return out;
    }

    /**
     * Uploads the APK for analysis and waits (bounded) for VirusTotal to finish, then returns the same
     * flat result as {@link #lookup}. Used only when a hash lookup says the file is unknown and the user
     * opts in.
     */
    public static JSONObject uploadAndWait(String apiKey, File apk, String sha256, long waitMs) throws Exception {
        String target = API + "/files";
        if (apk.length() > DIRECT_UPLOAD_LIMIT) {
            Resp u = get(API + "/files/upload_url", apiKey);
            if (u.code != 200) throw new IllegalStateException(errorFor(u.code, u.body));
            target = new JSONObject(u.body).optString("data", "");
            if (target.isEmpty()) throw new IllegalStateException("VirusTotal did not return an upload URL");
        }
        String analysisId = postMultipart(target, apiKey, apk);
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            Resp a = get(API + "/analyses/" + analysisId, apiKey);
            if (a.code == 200) {
                JSONObject data = new JSONObject(a.body).optJSONObject("data");
                JSONObject attr = data != null ? data.optJSONObject("attributes") : null;
                String status = attr != null ? attr.optString("status", "") : "";
                if ("completed".equals(status)) {
                    // The analysis object carries stats; re-read the file record for the canonical report.
                    JSONObject result = lookup(apiKey, sha256);
                    if (result.optBoolean("found", false)) return result;
                    return statsFromAnalysis(attr, sha256);
                }
            } else if (a.code != 404) {
                throw new IllegalStateException(errorFor(a.code, a.body));
            }
            Thread.sleep(4000);
        }
        throw new IllegalStateException("VirusTotal is still analyzing the file. Try the scan again in a minute.");
    }

    private static JSONObject statsFromAnalysis(JSONObject attr, String sha256) throws Exception {
        JSONObject out = new JSONObject();
        out.put("found", true);
        JSONObject stats = attr != null ? attr.optJSONObject("stats") : null;
        int malicious = stats != null ? stats.optInt("malicious", 0) : 0;
        int suspicious = stats != null ? stats.optInt("suspicious", 0) : 0;
        int harmless = stats != null ? stats.optInt("harmless", 0) : 0;
        int undetected = stats != null ? stats.optInt("undetected", 0) : 0;
        int timeout = stats != null ? stats.optInt("timeout", 0) : 0;
        out.put("malicious", malicious);
        out.put("suspicious", suspicious);
        out.put("harmless", harmless);
        out.put("undetected", undetected);
        out.put("timeout", timeout);
        out.put("total", malicious + suspicious + harmless + undetected + timeout);
        out.put("sha256", sha256);
        out.put("permalink", permalink(sha256));
        return out;
    }

    private static String postMultipart(String url, String apiKey, File file) throws Exception {
        String boundary = "----ADBAppMgr" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", "ADB-Application-Manager");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("x-apikey", apiKey);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setFixedLengthStreamingMode(multipartLength(boundary, file));
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            try {
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"package.apk\"\r\n");
                out.writeBytes("Content-Type: application/vnd.android.package-archive\r\n\r\n");
                InputStream in = new BufferedInputStream(new FileInputStream(file));
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally { in.close(); }
                out.writeBytes("\r\n--" + boundary + "--\r\n");
            } finally { out.close(); }
            int code = conn.getResponseCode();
            String body = readBody(conn, code);
            if (code != 200) throw new IllegalStateException(errorFor(code, body));
            String id = new JSONObject(body).optJSONObject("data") != null
                    ? new JSONObject(body).getJSONObject("data").optString("id", "") : "";
            if (id.isEmpty()) throw new IllegalStateException("VirusTotal did not return an analysis id");
            return id;
        } finally {
            conn.disconnect();
        }
    }

    private static long multipartLength(String boundary, File file) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"package.apk\"\r\n"
                + "Content-Type: application/vnd.android.package-archive\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        return head.getBytes().length + file.length() + tail.getBytes().length;
    }
}
