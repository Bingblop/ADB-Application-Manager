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
 * The real Morphe engine (engine/build/engine-libs, from engine/build-engine.sh) when patches fail with "Stop when a patch fails" off (MOR-1). Needs no
 * official bundle and no APK: it builds a tiny APK with the engine's own ARSCLib and dexlib2, and a patch bundle (a jar) with two patches, "Boom" that
 * always throws and "Fine" that does nothing, compiled against the engine with this JDK. Every selected patch failed: the engine answers a failure and
 * writes no APK (before, it signed the unchanged app and answered success). One applied and one failed: still a success with the APK.
 */
public class MorpheEngineFailTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static void write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    static String java() { return System.getProperty("java.home") + "/bin/java"; }

    /** Runs a command; returns its standard output (standard error goes to this process's). */
    static String run(List<String> c) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(c);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) out.append(line).append('\n');
        p.waitFor();
        return out.toString();
    }

    static final String MAKE_APK =
        "import com.reandroid.apk.ApkModule;\n"
        + "import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;\n"
        + "import com.reandroid.archive.ByteInputSource;\n"
        + "import com.android.tools.smali.dexlib2.Opcodes;\n"
        + "import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;\n"
        + "import com.android.tools.smali.dexlib2.writer.pool.DexPool;\n"
        + "import com.android.tools.smali.dexlib2.writer.io.FileDataStore;\n"
        + "import java.io.File;\n"
        + "public class MakeApk {\n"
        + "  public static void main(String[] a) throws Exception {\n"
        + "    File dex = new File(a[0] + \".dex\");\n"
        + "    DexPool.writeTo(new FileDataStore(dex), new ImmutableDexFile(Opcodes.getDefault(), java.util.Collections.emptySet()));\n"
        + "    AndroidManifestBlock m = AndroidManifestBlock.empty();\n"
        + "    m.setPackageName(\"com.example.morfix\"); m.setVersionCode(1); m.setVersionName(\"1.0\"); m.setMinSdkVersion(26); m.refresh();\n"
        + "    ApkModule apk = new ApkModule();\n"
        + "    apk.setManifest(m);\n"
        + "    apk.add(new ByteInputSource(java.nio.file.Files.readAllBytes(dex.toPath()), \"classes.dex\"));\n"
        + "    apk.writeApk(new File(a[0]));\n"
        + "  }\n"
        + "}\n";

    static final String BUNDLE =
        "import app.morphe.patcher.patch.*;\n"
        + "import kotlin.Unit;\n"
        + "public final class Boom {\n"
        + "  public static BytecodePatch boom() { return PatchKt.bytecodePatch(\"Boom\", \"Always throws\", true, b -> { b.execute(ctx -> { throw new IllegalStateException(\"boom on purpose\"); }); return Unit.INSTANCE; }); }\n"
        + "  public static BytecodePatch fine() { return PatchKt.bytecodePatch(\"Fine\", \"Does nothing\", true, b -> { b.execute(ctx -> Unit.INSTANCE); return Unit.INSTANCE; }); }\n"
        + "}\n";

    /** One patch run of the engine with "Stop when a patch fails" off; returns the RESULT. */
    static JSONObject patch(File root, String name, File apk, final File bundle, String... patches) throws Exception {
        File dir = new File(root, name);
        File out = new File(dir, "patched.apk");
        JSONArray names = new JSONArray();
        for (String p : patches) names.put(p);
        JSONObject a = new JSONObject().put("bundles", new JSONArray().put(new JSONObject().put("id", "test").put("patches", names).put("options", new JSONObject())))
            .put("failOnError", false).put("signer", "Morphe");
        JSONObject job = MorpheJobs.patchJob(a, apk, out, new File(dir, "work"), new MorpheJobs.Bundles() {
            @Override public File fileOf(String id) { return bundle; }
        }, new File(root, "morphe.keystore"), null, "");
        File jf = new File(dir, "job.json");
        write(jf, job.toString());
        List<String> c = new ArrayList<String>();
        c.add(java()); c.add("-Xmx1g"); c.add("-cp"); c.add(System.getProperty("enginelibs") + "/*");
        c.add("com.bloatware.bingblop.morphe.EngineMain"); c.add("patch"); c.add(jf.getAbsolutePath());
        File events = new File(dir, "events.log");
        write(events, run(c));
        MorpheEvents.Tail t = new MorpheEvents.Tail(events);
        t.poll();
        return t.sawResult() ? t.result() : null;
    }

    public static void main(String[] args) throws Exception {
        String libs = System.getProperty("enginelibs");
        File root = File.createTempFile("mengfail", "dir"); root.delete(); root.mkdirs();
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        check("a Java compiler is available (a JDK, not a JRE)", jc != null, null);
        if (jc == null) { System.exit(1); }
        File srcDir = new File(root, "src"), classes = new File(root, "classes");
        classes.mkdirs();
        write(new File(srcDir, "MakeApk.java"), MAKE_APK);
        write(new File(srcDir, "Boom.java"), BUNDLE);
        StringBuilder cp = new StringBuilder();
        File[] jars = new File(libs).listFiles();
        if (jars != null) for (File j : jars) if (j.getName().endsWith(".jar")) cp.append(cp.length() == 0 ? "" : File.pathSeparator).append(j.getAbsolutePath());
        int rc = jc.run(null, null, null, "-nowarn", "-d", classes.getPath(), "-cp", cp.toString(), new File(srcDir, "MakeApk.java").getPath(), new File(srcDir, "Boom.java").getPath());
        check("the test APK maker and the test patch bundle compile against the engine", rc == 0, "javac " + rc);
        if (rc != 0) { System.exit(1); }

        // the bundle: a jar with the Boom class only
        File bundle = new File(root, "boom.jar");
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(bundle));
        try {
            z.putNextEntry(new java.util.zip.ZipEntry("Boom.class"));
            z.write(java.nio.file.Files.readAllBytes(new File(classes, "Boom.class").toPath()));
            z.closeEntry();
        } finally { z.close(); }
        File apk = new File(root, "tiny.apk");
        List<String> mk = new ArrayList<String>();
        mk.add(java()); mk.add("-cp"); mk.add(classes.getPath() + File.pathSeparator + cp); mk.add("MakeApk"); mk.add(apk.getAbsolutePath());
        run(mk);
        check("the tiny APK was made", apk.isFile() && apk.length() > 100, null);

        JSONObject none = patch(root, "none", apk, bundle, "Boom");
        check("every selected patch failed (Stop when a patch fails off): the engine answers a failure, not success", none != null && !none.optBoolean("success"), String.valueOf(none));
        check("   in words, with the failed patch listed and nothing applied", none != null && none.optString("error").equals("No patch could be applied") && none.getJSONArray("applied").length() == 0
            && none.getJSONArray("failed").length() == 1 && none.getJSONArray("failed").getJSONObject(0).getString("name").equals("Boom"), String.valueOf(none));
        check("   and it wrote no APK (nothing the app could file or install)", !new File(root, "none/patched.apk").exists(), null);

        JSONObject some = patch(root, "some", apk, bundle, "Boom", "Fine");
        check("one failed, one applied: still a success with the APK (the rest was applied)", some != null && some.optBoolean("success") && some.getJSONArray("applied").length() == 1
            && some.getJSONArray("applied").getString(0).equals("Fine") && some.getJSONArray("failed").length() == 1 && new File(root, "some/patched.apk").isFile(), String.valueOf(some));
        check("   the app's own check (MorpheJobs.checkApplied) lets that one through", some != null && MorpheJobs.checkApplied(some) == some, null);

        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
