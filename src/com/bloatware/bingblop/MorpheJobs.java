package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The pure parts of a Morphe Patcher run (no Android classes, unit-tested): the job file the engine reads, the choice of where the patched APK is
 * kept, which files the page may hand to the app, and the words for a run that ended without an answer.
 */
public final class MorpheJobs {
    private MorpheJobs() {}

    /** Resolves a source id to its downloaded bundle (MorpheStore::bundleFile). */
    public interface Bundles {
        File fileOf(String id);
    }

    /**
     * The job file of a "patch" command of the engine (see EngineMain.kt). args is what the page sent: bundles [{id, patches[], options{patch:{key:value}}}],
     * stripLibs, failOnError, force, signer. keyInfo is {alias, password, storePassword} of an imported keystore, or null for the one this app makes.
     */
    public static JSONObject patchJob(JSONObject args, File input, File output, File tempDir, Bundles bundles, File keystore, JSONObject keyInfo, String abi) throws JSONException, IOException {
        JSONObject job = new JSONObject();
        job.put("input", input.getAbsolutePath());
        job.put("output", output.getAbsolutePath());
        job.put("tempDir", tempDir.getAbsolutePath());
        JSONArray bs = new JSONArray();
        JSONArray in = args.optJSONArray("bundles");
        if (in == null || in.length() == 0) throw new IOException("no patch is selected");
        for (int i = 0; i < in.length(); i++) {
            JSONObject b = in.getJSONObject(i);
            File f = bundles.fileOf(b.optString("id"));
            if (f == null || !f.isFile()) throw new IOException("the source \"" + b.optString("name", b.optString("id")) + "\" is not downloaded");
            JSONArray names = b.optJSONArray("patches");
            if (names == null || names.length() == 0) continue;
            JSONObject o = new JSONObject();
            o.put("file", f.getAbsolutePath());
            o.put("patches", names);
            o.put("options", b.optJSONObject("options") == null ? new JSONObject() : b.getJSONObject("options"));
            bs.put(o);
        }
        if (bs.length() == 0) throw new IOException("no patch is selected");
        job.put("bundles", bs);
        job.put("failOnError", args.optBoolean("failOnError", true));
        job.put("forceCompatibility", args.optBoolean("force", false));
        String signer = args.optString("signer", "Morphe").trim();
        job.put("signerName", signer.isEmpty() ? "Morphe" : signer);
        job.put("unsigned", false);
        JSONArray keep = new JSONArray();
        if (args.optBoolean("stripLibs", false) && abi != null && !abi.isEmpty()) keep.put(abi);
        job.put("keepArchitectures", keep);
        if (keystore != null) {
            JSONObject k = new JSONObject();
            k.put("path", keystore.getAbsolutePath());
            if (keyInfo != null) {
                k.put("alias", keyInfo.optString("alias", "Morphe"));
                k.put("password", keyInfo.optString("password", "Morphe"));
                if (keyInfo.has("storePassword") && !keyInfo.isNull("storePassword")) k.put("storePassword", keyInfo.getString("storePassword"));
            }
            job.put("keystore", k);
        }
        return job;
    }

    /** The job file of a "list" command: the bundles to read, and where the engine writes the answer. */
    public static JSONObject listJob(List<File> bundles, File out) throws JSONException {
        JSONArray a = new JSONArray();
        for (File f : bundles) a.put(f.getAbsolutePath());
        return new JSONObject().put("out", out.getAbsolutePath()).put("bundles", a);
    }

    /** True when path is inside one of the roots (after resolving links and ..). The page may only name files in these folders. */
    public static boolean inside(File path, List<File> roots) {
        try {
            String p = path.getCanonicalPath();
            for (File r : roots) {
                String rp = r.getCanonicalPath();
                if (p.equals(rp) || p.startsWith(rp + File.separator)) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    /** The CPU the engine keeps libraries for ("arm64-v8a", "armeabi-v7a", "x86_64", "x86"), from Build.SUPPORTED_ABIS[0]. */
    public static String abiName(String first) {
        if (first == null) return "";
        switch (first) {
            case "arm64-v8a": case "armeabi-v7a": case "x86": case "x86_64": return first;
            default: return "";
        }
    }

    /** What to tell when the patcher process ended without writing a result (killed by the system, almost always for memory). */
    public static String diedMessage(String lastLog) {
        String tail = lastLog == null ? "" : lastLog.trim();
        if (tail.length() > 600) tail = tail.substring(tail.length() - 600);
        return "The patcher process ended without an answer. Android most likely stopped it because the phone ran out of memory." + (tail.isEmpty() ? "" : "\n\nLast lines of its log:\n" + tail);
    }

    /** A file name made safe to keep (letters, digits, dot, dash, underscore). */
    public static String safeName(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 90; i++) {
            char c = s.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return b.length() == 0 ? "file" : b.toString();
    }
}
