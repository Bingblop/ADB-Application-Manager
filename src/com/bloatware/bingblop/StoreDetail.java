package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What the App Stores show when an app of any store (not only ShizuStore) is opened: the long description, the screenshots, the web site, the
 * source code address, the license, and for a GitHub or Codeberg project its stars and topics. And the catalogs of the custom stores that
 * are a GitHub or Codeberg user, organization or repository (the "+" tab).
 *
 * <p>Where the details come from: an F-Droid style repository's own index (kept in a side file while the catalog was read, see
 * {@link #detailLine}), a GitHub or Codeberg project's API, README and fastlane screenshots. Pure HTTP and JSON, no Android classes, so the
 * mapping and the picture finding can be tested off the phone.
 */
public final class StoreDetail {
    private StoreDetail() {}

    // ---------------------------------------------------------------------------------------------------------------- the side file of an F-Droid repository

    /** One line of the side file: {"p":"pkg","v":{...detail...}}. */
    public static String detailLine(String pkg, JSONObject detail) {
        return "{\"p\":" + JSONObject.quote(pkg) + ",\"v\":" + detail.toString() + "}";
    }

    /** The detail of {@code pkg} from a side file, or null when the file or the app is not there. */
    public static JSONObject fromFile(File f, String pkg) {
        if (f == null || !f.isFile() || pkg == null || pkg.isEmpty()) return null;
        String head = "{\"p\":" + JSONObject.quote(pkg) + ",";
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 65536);
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith(head)) continue;
                JSONObject o = new JSONObject(line);
                return o.optJSONObject("v");
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------- pictures in a README

    private static final String[] BAD_HOSTS = {"shields.io", "badge", "travis-ci", "codecov", "circleci", "coveralls", "sonarcloud", "weblate", "buymeacoffee", "ko-fi.com",
            "liberapay", "opencollective", "paypal", "star-history", "komarev", "seeyoufarm", "img.youtube", "f-droid.org/badge", "play.google.com/intl", "api.codacy", "visitor"};
    private static final String[] BAD_NAMES = {"badge", "shield", "logo", "icon", "button", "get-it-on", "getiton", "download-on", "donate", "sponsor", "banner", "header", "favicon", "avatar", "emoji"};

    /**
     * The pictures a README shows, in order, as full addresses: Markdown images and HTML img tags, relative ones resolved against
     * {@code rawBase} (the address of the repository's files, ending in a slash). Badges, logos, buttons, vector files and inline data are left out.
     */
    public static List<String> imagesIn(String text, String rawBase, int max) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)|<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find() && out.size() < max) {
            String u = m.group(1) != null ? m.group(1) : m.group(2);
            u = resolve(u == null ? "" : u.trim(), rawBase);
            if (u.isEmpty() || out.contains(u) || !goodPicture(u)) continue;
            out.add(u);
        }
        return out;
    }

    static String resolve(String u, String rawBase) {
        if (u.isEmpty() || u.startsWith("data:") || u.startsWith("#")) return "";
        if (u.startsWith("https://")) return u;
        if (u.startsWith("http://")) return "https://" + u.substring(7);
        if (u.startsWith("//")) return "https:" + u;
        if (rawBase == null || rawBase.isEmpty()) return "";
        while (u.startsWith("./")) u = u.substring(2);
        if (u.startsWith("/")) u = u.substring(1);
        if (u.startsWith("../")) return "";
        return rawBase + u.replace(" ", "%20");
    }

    static boolean goodPicture(String url) {
        String l = url.toLowerCase(Locale.US);
        int q = l.indexOf('?');
        String path = q >= 0 ? l.substring(0, q) : l;
        if (path.endsWith(".svg") || path.endsWith(".gif") && path.contains("badge")) return false;
        for (String b : BAD_HOSTS) if (l.contains(b)) return false;
        String file = path.substring(path.lastIndexOf('/') + 1);
        for (String b : BAD_NAMES) if (file.contains(b)) return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------- GitHub and Codeberg projects

    private static final int MAX_README = 6000;

    static String get(String url, String accept, String token, int maxBytes) throws Exception {
        java.util.Map<String, String> hdr = new java.util.LinkedHashMap<String, String>();
        hdr.put("User-Agent", "ADB-Application-Manager");
        if (accept != null) hdr.put("Accept", accept);
        if (token != null && !token.isEmpty()) hdr.put("Authorization", "Bearer " + token);
        HttpURLConnection c = HttpSafe.open(url, 15000, 30000, hdr);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new IllegalStateException("not found (HTTP 404)");
            if (code == 403 || code == 429) throw new IllegalStateException("the limit for requests is used up (HTTP " + code + ")");
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) break;                                            // a README is read as far as it is useful
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally { c.disconnect(); }
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** The detail of a GitHub project: the repository's data, its README (as far as it is useful) and its screenshots. */
    public static JSONObject github(String owner, String repo, String token) throws Exception {
        String api = "https://api.github.com/repos/" + owner + "/" + repo;
        JSONObject info = new JSONObject(get(api, "application/vnd.github+json", token, 1024 * 1024));
        JSONObject out = new JSONObject();
        String branch = info.optString("default_branch", "main");
        String rawBase = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/";
        String desc = KomiApi.str(info, "description");
        String readme = "";
        try { readme = get(api + "/readme", "application/vnd.github.raw+json", token, 256 * 1024); } catch (Exception ignored) {}
        List<String> shots = new ArrayList<String>();
        try {
            JSONArray dir = new JSONArray(get(api + "/contents/fastlane/metadata/android/en-US/images/phoneScreenshots", "application/vnd.github+json", token, 256 * 1024));
            for (int i = 0; i < dir.length() && shots.size() < 12; i++) {
                JSONObject e = dir.optJSONObject(i);
                String u = e == null ? "" : e.optString("download_url", "");
                if (u.startsWith("https://") && u.toLowerCase(Locale.US).matches(".*\\.(png|jpe?g|webp)(\\?.*)?$")) shots.add(u);
            }
        } catch (Exception ignored) {}
        if (shots.isEmpty()) shots = imagesIn(readme, rawBase, 12);
        out.put("d", (desc.isEmpty() ? "" : desc + "\n\n") + cut(readme, MAX_README));
        out.put("shots", new JSONArray(shots));
        out.put("web", KomiApi.str(info, "homepage"));
        out.put("src", KomiApi.str(info, "html_url"));
        JSONObject lic = info.optJSONObject("license");
        out.put("lic", lic == null ? "" : KomiApi.str(lic, "spdx_id").replace("NOASSERTION", ""));
        out.put("stars", info.optLong("stargazers_count", 0));
        out.put("forks", info.optLong("forks_count", 0));
        out.put("topics", info.optJSONArray("topics") == null ? new JSONArray() : info.optJSONArray("topics"));
        out.put("by", owner);
        return out;
    }

    /** The detail of a Codeberg (Gitea) project. */
    public static JSONObject codeberg(String owner, String repo) throws Exception {
        String api = "https://codeberg.org/api/v1/repos/" + owner + "/" + repo;
        JSONObject info = new JSONObject(get(api, "application/json", null, 1024 * 1024));
        String branch = info.optString("default_branch", "main");
        String rawBase = "https://codeberg.org/" + owner + "/" + repo + "/raw/branch/" + branch + "/";
        String readme = "";
        String[] names = {"README.md", "readme.md", "README.MD", "README"};
        for (String n : names) {
            try { readme = get(rawBase + n, "text/plain", null, 256 * 1024); if (!readme.isEmpty()) break; } catch (Exception ignored) {}
        }
        List<String> shots = new ArrayList<String>();
        try {
            JSONArray dir = new JSONArray(get(api + "/contents/fastlane/metadata/android/en-US/images/phoneScreenshots?ref=" + branch, "application/json", null, 256 * 1024));
            for (int i = 0; i < dir.length() && shots.size() < 12; i++) {
                JSONObject e = dir.optJSONObject(i);
                String u = e == null ? "" : e.optString("download_url", "");
                if (u.startsWith("https://") && u.toLowerCase(Locale.US).matches(".*\\.(png|jpe?g|webp)(\\?.*)?$")) shots.add(u);
            }
        } catch (Exception ignored) {}
        if (shots.isEmpty()) shots = imagesIn(readme, rawBase, 12);
        String desc = KomiApi.str(info, "description");
        JSONObject out = new JSONObject();
        out.put("d", (desc.isEmpty() ? "" : desc + "\n\n") + cut(readme, MAX_README));
        out.put("shots", new JSONArray(shots));
        out.put("web", KomiApi.str(info, "website"));
        out.put("src", KomiApi.str(info, "html_url"));
        out.put("lic", "");
        out.put("stars", info.optLong("stars_count", 0));
        out.put("forks", info.optLong("forks_count", 0));
        out.put("topics", info.optJSONArray("topics") == null ? new JSONArray() : info.optJSONArray("topics"));
        out.put("by", owner);
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------- the catalog of a custom store

    static long isoMillis(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) { return 0; }
    }

    /** One project of a GitHub ("github") or Codeberg ("codeberg") listing as a store item that installs the latest release's APK. */
    static JSONObject projectItem(String host, JSONObject r) throws Exception {
        JSONObject owner = r.optJSONObject("owner");
        String ownerName = owner == null ? "" : owner.optString("login", "");
        String name = r.optString("name", "");
        JSONObject o = new JSONObject();
        o.put("id", ownerName + "/" + name);
        o.put("key", ownerName + "/" + name);
        o.put("name", name);
        o.put("owner", ownerName);
        o.put("repo", name);
        o.put("pkg", "");
        o.put("assetFilter", "");
        String lang = KomiApi.str(r, "language");
        o.put("cats", new JSONArray().put(lang.isEmpty() ? "Other" : lang));
        o.put("desc", cut(KomiApi.str(r, "description"), 200));
        o.put("icon", owner == null ? "" : owner.optString("avatar_url", ""));
        o.put("page", KomiApi.str(r, "html_url"));
        o.put("author", ownerName);
        o.put("stars", "github".equals(host) ? r.optLong("stargazers_count", 0) : r.optLong("stars_count", 0));
        o.put("updated", isoMillis("github".equals(host) ? r.optString("pushed_at", "") : r.optString("updated_at", "")));
        o.put("source", "custom");
        o.put("resolveKind", host);
        return o;
    }

    /** All the projects of a GitHub user or organization (not archived, not forks), as store items. */
    public static JSONArray githubOwner(String owner, String token) throws Exception {
        JSONArray out = new JSONArray();
        String base = "https://api.github.com/users/" + owner + "/repos";
        boolean org = false;
        for (int page = 1; page <= 3; page++) {
            JSONArray part;
            try {
                part = new JSONArray(get(base + "?per_page=100&sort=pushed&page=" + page, "application/vnd.github+json", token, 4 * 1024 * 1024));
            } catch (IllegalStateException e) {
                if (page == 1 && !org && String.valueOf(e.getMessage()).contains("404")) { org = true; base = "https://api.github.com/orgs/" + owner + "/repos"; page--; continue; }
                throw e;
            }
            for (int i = 0; i < part.length(); i++) {
                JSONObject r = part.optJSONObject(i);
                if (r == null || r.optBoolean("archived") || r.optBoolean("fork")) continue;
                out.put(projectItem("github", r));
            }
            if (part.length() < 100) break;
        }
        return out;
    }

    /** One GitHub repository as a one-item store. */
    public static JSONArray githubRepo(String owner, String repo, String token) throws Exception {
        JSONObject r = new JSONObject(get("https://api.github.com/repos/" + owner + "/" + repo, "application/vnd.github+json", token, 1024 * 1024));
        return new JSONArray().put(projectItem("github", r));
    }

    /** All the projects of a Codeberg user or organization (not archived, not forks). */
    public static JSONArray codebergOwner(String owner) throws Exception {
        JSONArray out = new JSONArray();
        String base = "https://codeberg.org/api/v1/users/" + owner + "/repos";
        boolean org = false;
        for (int page = 1; page <= 4; page++) {
            JSONArray part;
            try {
                part = new JSONArray(get(base + "?limit=50&page=" + page, "application/json", null, 4 * 1024 * 1024));
            } catch (IllegalStateException e) {
                if (page == 1 && !org && String.valueOf(e.getMessage()).contains("404")) { org = true; base = "https://codeberg.org/api/v1/orgs/" + owner + "/repos"; page--; continue; }
                throw e;
            }
            for (int i = 0; i < part.length(); i++) {
                JSONObject r = part.optJSONObject(i);
                if (r == null || r.optBoolean("archived") || r.optBoolean("fork")) continue;
                out.put(projectItem("codeberg", r));
            }
            if (part.length() < 50) break;
        }
        return out;
    }

    /** One Codeberg repository as a one-item store. */
    public static JSONArray codebergRepo(String owner, String repo) throws Exception {
        JSONObject r = new JSONObject(get("https://codeberg.org/api/v1/repos/" + owner + "/" + repo, "application/json", null, 1024 * 1024));
        return new JSONArray().put(projectItem("codeberg", r));
    }
}
