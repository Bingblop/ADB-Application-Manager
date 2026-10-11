package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The real Morphe engine (engine/, built by engine/build-engine.sh) run as a plain JVM process on this computer, driven exactly as the app's
 * MorpheService drives it on the phone: the job file written by MorpheJobs, the protocol read back by MorpheEvents, the result filed by MorpheLibrary.
 * (The phone runs the same Kotlin code from dex files; this checks the contract between the Java side and the engine, not ART.)
 */
public class MorpheEngineTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8")); o.close();
    }

    static String read(File f) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        java.io.InputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }

    /** Runs EngineMain like the service does and returns what it printed. */
    static String engine(String cmd, File job, File events) throws Exception {
        List<String> c = new ArrayList<String>();
        c.add(System.getProperty("java.home") + "/bin/java");
        c.add("-Xmx2g");
        c.add("-cp");
        c.add(System.getProperty("enginelibs") + "/*");
        c.add("com.bloatware.bingblop.morphe.EngineMain");
        c.add(cmd);
        c.add(job.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(c);
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) out.append(line).append('\n');
        p.waitFor();
        write(events, out.toString());
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        File root = File.createTempFile("meng", "dir"); root.delete(); root.mkdirs();
        final File mpp = new File(System.getProperty("mpp"));
        File apk = new File(System.getProperty("testapk"));

        // ---- list
        File answer = new File(root, "list-out.json");
        List<File> bundles = new ArrayList<File>(); bundles.add(mpp);
        write(new File(root, "list.json"), MorpheJobs.listJob(bundles, answer).toString());
        File ev1 = new File(root, "list-events.log");
        engine("list", new File(root, "list.json"), ev1);
        MorpheEvents.Tail t1 = new MorpheEvents.Tail(ev1); t1.poll();
        check("list: the engine answers with a successful RESULT", t1.sawResult() && t1.result().optBoolean("success"), String.valueOf(t1.result()));
        JSONObject listed = new JSONObject(read(answer)).getJSONArray("bundles").getJSONObject(0);
        JSONArray patches = listed.getJSONArray("patches");
        check("list: the bundle is read (ok, 100+ patches)", listed.getBoolean("ok") && patches.length() > 100, listed.optString("error"));
        int universal = 0; boolean youtube = false, withOptions = false, defaults = false;
        for (int i = 0; i < patches.length(); i++) {
            JSONObject p = patches.getJSONObject(i);
            if (p.getJSONArray("compat").length() == 0) universal++;
            for (int k = 0; k < p.getJSONArray("compat").length(); k++) if (p.getJSONArray("compat").getJSONObject(k).getString("package").equals("com.google.android.youtube")) youtube = true;
            if (p.getJSONArray("options").length() > 0) withOptions = true;
            if (p.getBoolean("default")) defaults = true;
        }
        check("list: universal patches, YouTube patches, options and defaults are all described", universal >= 3 && youtube && withOptions && defaults, "universal=" + universal);
        JSONObject first = patches.getJSONObject(0);
        check("list: a patch has name, description, compat and options fields", first.has("name") && first.has("description") && first.has("compat") && first.has("options") && first.has("default"), first.toString());

        // ---- patch a real APK with two universal patches
        File out = new File(root, "patched.apk");
        JSONObject a = new JSONObject();
        JSONObject b = new JSONObject().put("id", "official").put("patches", new JSONArray().put("Disable Play Store updates").put("Change installer source")).put("options", new JSONObject());
        a.put("bundles", new JSONArray().put(b)).put("failOnError", true).put("signer", "Morphe");
        JSONObject job = MorpheJobs.patchJob(a, apk, out, new File(root, "work"), new MorpheJobs.Bundles() {
            @Override public File fileOf(String id) { return mpp; }
        }, new File(root, "morphe.keystore"), null, "");
        write(new File(root, "job.json"), job.toString());
        File ev2 = new File(root, "events.log");
        engine("patch", new File(root, "job.json"), ev2);
        MorpheEvents.Tail t2 = new MorpheEvents.Tail(ev2);
        List<JSONObject> evs = t2.poll();
        List<String> steps = new ArrayList<String>(), applied = new ArrayList<String>();
        String appPkg = "";
        for (JSONObject e : evs) {
            String t = e.optString("t");
            if (t.equals("step") && e.getString("state").equals("OK")) steps.add(e.getString("name"));
            if (t.equals("patch") && e.getBoolean("ok")) applied.add(e.getString("name"));
            if (t.equals("app")) appPkg = e.getString("pkg");
        }
        check("patch: every step finished, in order", steps.toString().equals("[Loading, Unpacking, Patching, Rebuilding, Signing]"), steps.toString());
        check("patch: both patches were applied", applied.size() == 2, applied.toString());
        check("patch: the app's package was reported", appPkg.equals("com.bloatware.bingblop"), appPkg);
        check("patch: a successful RESULT with the output and the applied patches", t2.sawResult() && t2.result().getBoolean("success") && t2.result().getJSONArray("applied").length() == 2 && t2.result().getString("output").equals(out.getPath()), String.valueOf(t2.result()));
        check("patch: the engine's log lines came through", countLog(evs, "Executing patches") > 0 && countLog(evs, "Signing APK") > 0, null);
        check("patch: the patched APK exists and is a different file from the input", out.isFile() && out.length() > 100000 && out.length() != apk.length(), null);
        check("patch: the signing key was made", new File(root, "morphe.keystore").isFile(), null);

        // ---- file it like the app
        MorpheLibrary lib = new MorpheLibrary(new File(root, "patched"));
        JSONObject meta = new JSONObject().put("pkg", appPkg).put("versionName", "x").put("patches", t2.result().getJSONArray("applied"));
        File log = ev2;
        JSONObject item = lib.add(out, meta, log);
        check("library: the run is filed with its log and hash", lib.list().length() == 1 && item.getString("sha256").length() == 64 && lib.readLog(item.getString("id"), 100000).contains("Signing APK"), item.toString());

        // ---- the signature is real (apksigner)
        String signer = System.getProperty("apksigner");
        Process p = new ProcessBuilder(signer, "verify", "--print-certs", item.getString("file")).redirectErrorStream(true).start();
        StringBuilder so = new StringBuilder(); BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l; while ((l = r.readLine()) != null) so.append(l).append('\n');
        p.waitFor();
        check("signing: apksigner verifies the patched APK and names the signer Morphe", p.exitValue() == 0 && so.toString().contains("CN=Morphe"), so.toString());

        // ---- the same key signs the next run (an update installs over it)
        File out2 = new File(root, "patched2.apk");
        JSONObject job2 = MorpheJobs.patchJob(a, apk, out2, new File(root, "work2"), new MorpheJobs.Bundles() { @Override public File fileOf(String id) { return mpp; } }, new File(root, "morphe.keystore"), null, "");
        write(new File(root, "job2.json"), job2.toString());
        engine("patch", new File(root, "job2.json"), new File(root, "events2.log"));
        Process p2 = new ProcessBuilder(signer, "verify", "--print-certs", out2.getPath()).redirectErrorStream(true).start();
        StringBuilder s2 = new StringBuilder(); BufferedReader r2 = new BufferedReader(new InputStreamReader(p2.getInputStream()));
        while ((l = r2.readLine()) != null) s2.append(l).append('\n');
        p2.waitFor();
        check("signing: the second run uses the same key", digest(so.toString()).equals(digest(s2.toString())) && !digest(so.toString()).isEmpty(), digest(so.toString()) + " / " + digest(s2.toString()));

        // ---- failures are reported, not thrown
        JSONObject bad = new JSONObject().put("bundles", new JSONArray().put(new JSONObject().put("id", "official").put("patches", new JSONArray().put("No such patch")))).put("failOnError", true);
        File out3 = new File(root, "patched3.apk");
        JSONObject job3 = MorpheJobs.patchJob(bad, apk, out3, new File(root, "work3"), new MorpheJobs.Bundles() { @Override public File fileOf(String id) { return mpp; } }, null, null, "");
        write(new File(root, "job3.json"), job3.toString());
        File ev3 = new File(root, "events3.log");
        engine("patch", new File(root, "job3.json"), ev3);
        MorpheEvents.Tail t3 = new MorpheEvents.Tail(ev3); t3.poll();
        check("failure: a name no patch has ends in a RESULT that says no patch is left", t3.sawResult() && !t3.result().getBoolean("success") && t3.result().optString("error").contains("No patch is left"), String.valueOf(t3.result()));
        JSONObject nofile = new JSONObject().put("input", new File(root, "missing.apk").getPath()).put("output", out3.getPath()).put("tempDir", new File(root, "w4").getPath())
                .put("bundles", new JSONArray().put(new JSONObject().put("file", mpp.getPath()).put("patches", new JSONArray().put("Spoof signature")).put("options", new JSONObject())));
        write(new File(root, "job4.json"), nofile.toString());
        File ev4 = new File(root, "events4.log");
        engine("patch", new File(root, "job4.json"), ev4);
        MorpheEvents.Tail t4 = new MorpheEvents.Tail(ev4); t4.poll();
        check("failure: a missing APK is a RESULT with an error and no stack trace thrown at the caller", t4.sawResult() && !t4.result().getBoolean("success") && !t4.result().optString("error").isEmpty(), String.valueOf(t4.result()));

        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
    }

    static int countLog(List<JSONObject> evs, String needle) {
        int n = 0;
        for (JSONObject e : evs) if ("log".equals(e.optString("t")) && e.optString("text").contains(needle)) n++;
        return n;
    }

    static String digest(String apksignerOut) {
        for (String l : apksignerOut.split("\n")) if (l.contains("certificate SHA-256 digest")) return l.substring(l.indexOf(':') + 1).trim();
        return "";
    }
}
