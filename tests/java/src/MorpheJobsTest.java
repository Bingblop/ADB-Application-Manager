package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The job file of the Morphe engine, path safety and the words for a patcher that died. */
public class MorpheJobsTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    public static void main(String[] args) throws Exception {
        File root = File.createTempFile("mjobs", "dir"); root.delete(); root.mkdirs();
        final File mpp = new File(root, "a.mpp"); new java.io.FileOutputStream(mpp).close();
        MorpheJobs.Bundles bundles = new MorpheJobs.Bundles() {
            @Override public File fileOf(String id) { return id.equals("official") ? mpp : null; }
        };
        JSONObject a = new JSONObject("{\"bundles\":[{\"id\":\"official\",\"name\":\"Morphe Patches\",\"patches\":[\"Hide ads\",\"SponsorBlock\"],\"options\":{\"Hide ads\":{\"x\":1}}}],\"stripLibs\":true,\"failOnError\":false,\"force\":true,\"signer\":\" Me \"}");
        JSONObject job = MorpheJobs.patchJob(a, new File(root, "in.apk"), new File(root, "out.apk"), new File(root, "tmp"), bundles, new File(root, "k.keystore"), null, "arm64-v8a");
        check("the job names the files", job.getString("input").endsWith("in.apk") && job.getString("output").endsWith("out.apk") && job.getString("tempDir").endsWith("tmp"), job.toString());
        check("the bundle file replaces its id and keeps patches and options", job.getJSONArray("bundles").getJSONObject(0).getString("file").equals(mpp.getAbsolutePath()) && job.getJSONArray("bundles").getJSONObject(0).getJSONArray("patches").length() == 2 && job.getJSONArray("bundles").getJSONObject(0).getJSONObject("options").getJSONObject("Hide ads").getInt("x") == 1, job.toString());
        check("the choices go through (stop on failure off, force on, signer trimmed)", !job.getBoolean("failOnError") && job.getBoolean("forceCompatibility") && job.getString("signerName").equals("Me") && !job.getBoolean("unsigned"), job.toString());
        check("strip libraries keeps this phone's CPU", job.getJSONArray("keepArchitectures").length() == 1 && job.getJSONArray("keepArchitectures").getString(0).equals("arm64-v8a"), job.toString());
        check("the keystore path is there, with no alias when none was imported", job.getJSONObject("keystore").getString("path").endsWith("k.keystore") && !job.getJSONObject("keystore").has("alias"), job.toString());
        JSONObject ki = new JSONObject("{\"alias\":\"mine\",\"password\":\"pw\",\"storePassword\":\"sp\"}");
        JSONObject job2 = MorpheJobs.patchJob(a, new File(root, "in.apk"), new File(root, "out.apk"), new File(root, "tmp"), bundles, new File(root, "k.keystore"), ki, "");
        check("an imported key brings its alias and passwords", job2.getJSONObject("keystore").getString("alias").equals("mine") && job2.getJSONObject("keystore").getString("password").equals("pw") && job2.getJSONObject("keystore").getString("storePassword").equals("sp"), job2.toString());
        check("without a known CPU nothing is stripped", job2.getJSONArray("keepArchitectures").length() == 0, null);
        JSONObject off = new JSONObject("{\"bundles\":[{\"id\":\"official\",\"patches\":[\"A\"]}]}");
        JSONObject job3 = MorpheJobs.patchJob(off, new File(root, "in.apk"), new File(root, "out.apk"), new File(root, "tmp"), bundles, null, null, "x86");
        check("defaults: stop on failure, no force, signer Morphe, no keystore entry, nothing stripped", job3.getBoolean("failOnError") && !job3.getBoolean("forceCompatibility") && job3.getString("signerName").equals("Morphe") && !job3.has("keystore") && job3.getJSONArray("keepArchitectures").length() == 0, job3.toString());

        String err = "";
        try { MorpheJobs.patchJob(new JSONObject("{\"bundles\":[{\"id\":\"nope\",\"name\":\"Gone\",\"patches\":[\"A\"]}]}"), mpp, mpp, root, bundles, null, null, ""); } catch (Exception e) { err = e.getMessage(); }
        check("a source that is not downloaded is said in words", err.contains("\"Gone\"") && err.contains("not downloaded"), err);
        err = "";
        try { MorpheJobs.patchJob(new JSONObject("{\"bundles\":[]}"), mpp, mpp, root, bundles, null, null, ""); } catch (Exception e) { err = e.getMessage(); }
        check("no bundles: no patch is selected", err.contains("no patch is selected"), err);
        err = "";
        try { MorpheJobs.patchJob(new JSONObject("{\"bundles\":[{\"id\":\"official\",\"patches\":[]}]}"), mpp, mpp, root, bundles, null, null, ""); } catch (Exception e) { err = e.getMessage(); }
        check("a bundle without patches is dropped, then there is nothing to patch", err.contains("no patch is selected"), err);

        JSONObject list = MorpheJobs.listJob(Arrays.asList(mpp, new File(root, "b.mpp")), new File(root, "out.json"));
        check("the list job holds the bundles and the answer file", list.getJSONArray("bundles").length() == 2 && list.getString("out").endsWith("out.json"), list.toString());

        List<File> roots = new ArrayList<File>(); roots.add(root);
        File sub = new File(root, "pick/x.apk"); sub.getParentFile().mkdirs(); new java.io.FileOutputStream(sub).close();
        check("a file inside a root is allowed", MorpheJobs.inside(sub, roots), null);
        check("the root itself is allowed", MorpheJobs.inside(root, roots), null);
        check("a path with .. that leaves the root is not", !MorpheJobs.inside(new File(root, "pick/../../etc/passwd"), roots), null);
        check("a sibling folder with the same prefix is not", !MorpheJobs.inside(new File(root.getPath() + "2/x"), roots), null);
        File outside = File.createTempFile("outside", ".apk");
        File link = new File(root, "pick/link.apk");
        boolean linked = false;
        try { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()); linked = true; } catch (Exception ignored) {}
        if (linked) check("a link that points out of the root is not allowed", !MorpheJobs.inside(link, roots), null);

        check("abi names", MorpheJobs.abiName("arm64-v8a").equals("arm64-v8a") && MorpheJobs.abiName("armeabi-v7a").equals("armeabi-v7a") && MorpheJobs.abiName("mips").isEmpty() && MorpheJobs.abiName(null).isEmpty(), null);
        check("the died message mentions memory and the last log", MorpheJobs.diedMessage("LOG INFO Applying").contains("out of memory") && MorpheJobs.diedMessage("LOG INFO Applying").contains("Applying") && !MorpheJobs.diedMessage("").contains("Last lines"), null);
        StringBuilder big = new StringBuilder(); for (int i = 0; i < 5000; i++) big.append('x');
        check("a long log is cut to its end", MorpheJobs.diedMessage(big.toString()).length() < 900, null);
        check("safe names", MorpheJobs.safeName("../a b/c.apk").equals(".._a_b_c.apk") && MorpheJobs.safeName("").equals("file"), MorpheJobs.safeName("../a b/c.apk"));
        // the page names the job folder: a dot-only id would make it the Morphe folder, which a patch run empties first
        StringBuilder id40 = new StringBuilder(), id41 = new StringBuilder();
        for (int i = 0; i < 40; i++) id40.append('a');
        id41.append(id40).append('a');
        check("normal job ids are valid", MorpheJobs.validJobId("j1700000000000") && MorpheJobs.validJobId("a-b_C9") && MorpheJobs.validJobId(id40.toString()), null);
        boolean bad = true;
        for (String x : new String[] {null, "", ".", "..", "...", "a/b", "../x", "a\\b", "a b", "a.b", "a\u0000", "\u00e4", id41.toString(), "a\n"}) if (MorpheJobs.validJobId(x)) { bad = false; System.out.println("  accepted: " + x); }
        check("empty, dotted, slashed, spaced, NUL, non-ASCII and over-long job ids are refused", bad, null);
        File jobsBase = new File(root, "morphe"); File jobsDir = new File(jobsBase, "jobs"); jobsDir.mkdirs();
        check("a valid id names a folder inside jobs/, and '..' (what the old code allowed) names the Morphe folder itself",
            new File(jobsBase, "jobs/" + MorpheJobs.safeName("j1")).getParentFile().equals(jobsDir) && new File(jobsBase, "jobs/" + MorpheJobs.safeName("..")).getCanonicalFile().equals(jobsBase.getCanonicalFile()), null);
        // the bridge checks the id before it takes the 'running' slot, and again before it empties the folder
        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { File f = new File(d, "src/com/bloatware/bingblop/MorpheBridge.java"); if (f.isFile()) src = f; }
        if (src != null) {
            String m = new String(java.nio.file.Files.readAllBytes(src.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            int sp = m.indexOf("private JSONObject startPatch("), pa = m.indexOf("private void patch(JSONObject a");
            String startBody = sp < 0 ? "" : m.substring(sp, pa > sp ? pa : m.length());
            check("startPatch refuses a bad id before it takes the running slot", startBody.indexOf("validJobId(jobId)") > 0 && startBody.indexOf("validJobId(jobId)") < startBody.indexOf("runningJob = jobId"), null);
            String patchBody = pa < 0 ? "" : m.substring(pa, Math.min(m.length(), pa + 1200));
            check("patch refuses a bad id before it empties the job folder", patchBody.indexOf("validJobId(jobId)") > 0 && patchBody.indexOf("validJobId(jobId)") < patchBody.indexOf("deleteTree(dir)"), null);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
    }
}
