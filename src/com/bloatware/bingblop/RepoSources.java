package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-Droid and IzzyOnDroid as download sources of the Application Updater's search: the repositories' public package API lists the
 * versions of a package and the repository serves each build as {@code <package>_<versionCode>.apk}. The answers have the shape of
 * {@link MorpheHelper#versions} and {@link MorpheHelper#resolve}, so the same download (with its check that the file is a real
 * package of the asked name and version) saves the file. Kept apart from Morphe Helper's own ten sources, which stay as they were.
 */
public final class RepoSources {
    private RepoSources() { }

    /** Test hook: another address for a source ("fdroid" or "izzy"). */
    static final Map<String, String> baseOverride = new HashMap<String, String>();

    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36";
    private static final java.util.regex.Pattern PKG = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    public static boolean handles(String id) { return "fdroid".equals(id) || "izzy".equals(id); }

    private static String name(String id) { return "izzy".equals(id) ? "IzzyOnDroid" : "F-Droid"; }

    private static String base(String id) {
        String o = baseOverride.get(id);
        if (o != null && !o.isEmpty()) return o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
        return "izzy".equals(id) ? "https://apt.izzysoft.de/fdroid" : "https://f-droid.org";
    }

    /** The page of the package on the site. */
    public static String pageUrl(String id, String pkg) {
        return "izzy".equals(id) ? "https://apt.izzysoft.de/fdroid/index/apk/" + pkg : "https://f-droid.org/packages/" + pkg + "/";
    }

    private static void check(String id, String pkg) throws IOException {
        if (!handles(id)) throw new IOException("unknown source: " + id);
        if (pkg == null || !PKG.matcher(pkg).matches()) throw new IOException("that is not a package name");
    }

    private static final class Build { String version; long code; boolean suggested; }

    private static List<Build> builds(String id, String pkg) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/json");
        MorpheNet.Response r;
        try {
            r = MorpheNet.request("GET", base(id) + "/api/v1/packages/" + pkg, h, null);
        } catch (IOException e) {
            throw new IOException("Could not reach " + name(id) + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (r.status == 404 || r.status == 410) throw new IOException(name(id) + " does not list " + pkg + ".");
        if (r.status < 200 || r.status >= 300) throw new IOException(name(id) + " answered HTTP " + r.status + ".");
        List<Build> out = new ArrayList<Build>();
        try {
            JSONObject j = new JSONObject(r.text == null ? "" : r.text.trim());
            long suggested = j.optLong("suggestedVersionCode", 0);
            JSONArray a = j.optJSONArray("packages");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                Build b = new Build();
                b.version = p.optString("versionName", "").trim();
                b.code = p.optLong("versionCode", 0);
                if (b.code > 0) { if (b.version.isEmpty()) b.version = String.valueOf(b.code); b.suggested = b.code == suggested; out.add(b); }
            }
        } catch (JSONException e) {
            throw new IOException(name(id) + " answered in a form this app does not know (the site may have changed).");
        }
        if (out.isEmpty()) throw new IOException(name(id) + " lists no build of " + pkg + ".");
        Collections.sort(out, new Comparator<Build>() { @Override public int compare(Build x, Build y) { return Long.compare(y.code, x.code); } });
        return out;
    }

    /**
     * Whether a package's native libraries (the ABI folders of its lib/ directory) can run on a phone with these ABIs (best first). A package with
     * no native code fits every phone; the repositories' API does not say which CPU a build is for, so the file itself is looked at after the download.
     */
    public static boolean abiFits(JSONArray apkAbis, String[] deviceAbis) {
        if (apkAbis == null || apkAbis.length() == 0) return true;
        for (int i = 0; i < apkAbis.length(); i++) {
            String a = apkAbis.optString(i, "");
            for (String d : deviceAbis) {
                if (a.equals(d)) return true;
                if (a.equals("armeabi") && d.equals("armeabi-v7a")) return true;
            }
        }
        return false;
    }

    private static String fileUrl(String id, String pkg, long code) { return base(id) + "/repo/" + pkg + "_" + code + ".apk"; }

    /** {ok, source, pkg, name, versions:[{version, versionCode, format, abi, size, page, url, sha256}]}, newest first. */
    public static JSONObject versions(String id, String pkg) throws IOException {
        check(id, pkg);
        List<Build> bs = builds(id, pkg);
        try {
            JSONArray arr = new JSONArray();
            for (Build b : bs) {
                arr.put(new JSONObject().put("version", b.version).put("versionCode", b.code).put("format", "apk").put("abi", "").put("size", 0)
                        .put("page", pageUrl(id, pkg)).put("url", fileUrl(id, pkg, b.code)).put("sha256", "").put("suggested", b.suggested));
            }
            return new JSONObject().put("ok", true).put("source", id).put("pkg", pkg).put("name", pkg).put("versions", arr);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /**
     * What to download: policy "requested" is the exact version name (a trailing "(code)" names the build too), "latest" the newest build.
     * The answer is what {@link MorpheHelper#download} takes.
     */
    public static JSONObject resolve(String id, String pkg, String wantedVersion, String policy) throws IOException {
        check(id, pkg);
        boolean requested = "requested".equals(policy);
        if (!requested && !"latest".equals(policy)) throw new IOException("unknown version policy: " + policy + " (requested or latest)");
        String wanted = wantedVersion == null ? "" : wantedVersion.trim();
        long wantedCode = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s*\\((\\d+)\\)$").matcher(wanted);
        if (m.matches()) { wanted = m.group(1).trim(); wantedCode = Long.parseLong(m.group(2)); }
        if (requested && wanted.isEmpty()) throw new IOException("the requested version is needed for the \"requested\" policy");
        List<Build> bs = builds(id, pkg);
        Build pick = null;
        if (requested) {
            for (Build b : bs) if (b.version.equalsIgnoreCase(wanted) && (wantedCode <= 0 || b.code == wantedCode)) { pick = b; break; }
            if (pick == null) throw new IOException(name(id) + " does not list version " + wanted + " of " + pkg + " (the newest it lists is " + bs.get(0).version + ").");
        } else {
            // the repository's own recommendation (its suggested build, the newest stable one) before the highest number, which may be a pre-release
            pick = bs.get(0);
            for (Build b : bs) if (b.suggested) { pick = b; break; }
        }
        try {
            return new JSONObject().put("ok", true).put("source", id).put("pkg", pkg).put("name", pkg).put("version", pick.version).put("versionCode", pick.code)
                    .put("format", "apk").put("abi", "").put("url", fileUrl(id, pkg, pick.code)).put("size", 0).put("sha256", "").put("md5", "")
                    .put("page", pageUrl(id, pkg)).put("referer", "").put("exact", requested).put("newer", false).put("wanted", requested ? wanted : "")
                    .put("wantedCode", wantedCode).put("policy", requested ? "requested" : "latest");
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }
}
