package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * What the app does with the answer of "Install when finished" / the Install button for a patched APK. With no working mode the app only
 * hands the APK to Android's installer and says so; it does not know whether the person installed it, so the APK is kept and the entry is
 * not marked installed, "delete after installing" or not. A real answer (a working mode, or a split bundle whose verdict was awaited) is
 * trusted.
 */
public class MorpheInstallStepTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static File temp() throws Exception {
        File d = File.createTempFile("mins", "dir"); d.delete(); d.mkdirs(); return d;
    }

    /** The installer the bridge's host gives: answers with what the test says. */
    interface Installer { JSONObject install(File apk, String pkg, boolean uninstallFirst) throws Exception; }

    static JSONObject file(MorpheLibrary lib, File root, String name) throws Exception {
        File apk = new File(root, name + ".apk");
        FileOutputStream o = new FileOutputStream(apk); o.write("APKDATA".getBytes("UTF-8")); o.close();
        return lib.add(apk, new JSONObject().put("pkg", "com.example." + name).put("versionName", "1"), null);
    }

    /** What the bridge does after saving a run with "install when finished": install, then settle with the answer. */
    static boolean afterRun(Installer host, MorpheLibrary lib, JSONObject saved, boolean deleteAfter) throws Exception {
        JSONObject ir = host.install(new File(saved.optString("file")), saved.optString("pkg"), false);
        return MorpheInstallStep.settle(lib, saved.optString("id"), ir, deleteAfter);
    }

    public static void main(String[] args) throws Exception {
        File root = temp();
        MorpheLibrary lib = new MorpheLibrary(new File(root, "patched"));

        // no working mode: Android's installer was opened, the outcome is unknown
        Installer system = new Installer() { @Override public JSONObject install(File apk, String pkg, boolean u) throws Exception {
            return new JSONObject().put("ok", true).put("system", true).put("handedOff", true).put("output", "The system installer was opened. Confirm the install there.");
        } };
        JSONObject a = file(lib, root, "a");
        boolean deleted = afterRun(system, lib, a, true);
        check("opened in the system installer + delete after: the patched APK is still there", new File(a.getString("file")).isFile(), "file=" + a.getString("file"));
        check("... the entry is still in the library", lib.get(a.getString("id")) != null && lib.list().length() == 1, lib.list().toString());
        check("... and reports that nothing was deleted", !deleted, null);
        check("... and is not marked installed", !lib.get(a.getString("id")).optBoolean("installed"), lib.get(a.getString("id")).toString());

        JSONObject a2 = file(lib, root, "a2");
        afterRun(system, lib, a2, false);
        check("opened in the system installer, no delete: not marked installed either", !lib.get(a2.getString("id")).optBoolean("installed"), lib.get(a2.getString("id")).toString());

        // a working mode: a real answer
        Installer privileged = new Installer() { @Override public JSONObject install(File apk, String pkg, boolean u) throws Exception {
            return new JSONObject().put("ok", true).put("output", "Success");
        } };
        JSONObject b = file(lib, root, "b");
        boolean bDeleted = afterRun(privileged, lib, b, true);
        check("a working-mode install + delete after: the entry and the APK are gone", bDeleted && !new File(b.getString("file")).exists() && lib.get(b.getString("id")) == null, null);
        JSONObject c = file(lib, root, "c");
        boolean cDeleted = afterRun(privileged, lib, c, false);
        check("a working-mode install, no delete: marked installed and kept", !cDeleted && lib.get(c.getString("id")).optBoolean("installed") && new File(c.getString("file")).isFile(), null);

        // a split bundle without a working mode waits for the system's verdict: that answer is real
        Installer bundleOk = new Installer() { @Override public JSONObject install(File apk, String pkg, boolean u) throws Exception {
            return new JSONObject().put("ok", true).put("system", true).put("output", "Success");
        } };
        JSONObject d = file(lib, root, "d");
        check("a bundle whose system verdict was Success is installed", afterRun(bundleOk, lib, d, true) && lib.get(d.getString("id")) == null, null);

        // a failed install changes nothing
        Installer failedInstall = new Installer() { @Override public JSONObject install(File apk, String pkg, boolean u) throws Exception {
            return new JSONObject().put("ok", false).put("output", "Failure");
        } };
        JSONObject e = file(lib, root, "e");
        check("a failed install keeps the APK and does not mark it", !afterRun(failedInstall, lib, e, true) && new File(e.getString("file")).isFile() && !lib.get(e.getString("id")).optBoolean("installed"), null);

        // MOR-5: the "Installing" step of the run is done only when the app was installed; the system installer only opened is not "OK"
        check("the Installing step of an answer that was only handed to the system installer is not OK (OPENED)", "OPENED".equals(MorpheInstallStep.stepState(system.install(null, "", false))), MorpheInstallStep.stepState(system.install(null, "", false)));
        check("...of a working-mode install that worked: OK", "OK".equals(MorpheInstallStep.stepState(privileged.install(null, "", false))), null);
        check("...of a bundle whose system verdict was Success: OK", "OK".equals(MorpheInstallStep.stepState(bundleOk.install(null, "", false))), null);
        check("...of a failed install, a cancelled question or no answer: FAIL", "FAIL".equals(MorpheInstallStep.stepState(failedInstall.install(null, "", false)))
                && "FAIL".equals(MorpheInstallStep.stepState(new JSONObject().put("ok", false).put("cancelled", true))) && "FAIL".equals(MorpheInstallStep.stepState(null)), null);

        // the bridge uses all this
        File src = null;
        for (File dd = new File(System.getProperty("user.dir")).getAbsoluteFile(); dd != null && src == null; dd = dd.getParentFile()) { File f = new File(dd, "src/com/bloatware/bingblop/MorpheBridge.java"); if (f.isFile()) src = f; }
        check("the bridge source was found", src != null, null);
        if (src != null) {
            String m = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
            check("after a run the bridge settles the install answer through MorpheInstallStep (no ok -> delete of its own)", m.contains("MorpheInstallStep.settle(library, saved.optString(\"id\"), ir, a.optBoolean(\"deleteAfter\"))") && !m.contains("if (ir.optBoolean(\"ok\"))"), null);
            check("after a run the bridge marks the Installing step through MorpheInstallStep.stepState (an ok that was only handed off is not OK)", m.contains("step(jobId, \"Installing\", MorpheInstallStep.stepState(ir))") && !m.contains("step(jobId, \"Installing\", ir.optBoolean(\"ok\")"), null);
            check("the install call settles through it too", m.contains("MorpheInstallStep.settle(library, a.optString(\"id\"), r, false)"), null);
            File main = new File(src.getParentFile(), "MainActivity.java");
            String ma = new String(java.nio.file.Files.readAllBytes(main.toPath()), "UTF-8");
            int i = ma.indexOf("private JSONObject morpheInstall(");
            String mi = i < 0 ? "" : ma.substring(i, Math.min(ma.length(), i + 2500));
            check("the system-installer answer without a verdict says it was handed off", mi.contains("r.put(\"handedOff\", true)"), null);
            // MA1-4: the copy into cacheDir/morphe_inst (or the unpacked bundle) is made inside the try whose finally removes that folder, so a copy that
            // fails half way (a full cache, a file that goes away) leaves no partial APK behind
            String mw = i < 0 ? "" : ma.substring(i, Math.min(ma.length(), i + 8000));
            int decl = mw.indexOf("String insidePkg = null;"), tr = mw.indexOf("        try {\n", Math.max(0, decl)), fin = tr < 0 ? -1 : mw.indexOf("        } finally {", tr);
            String finBody = fin < 0 ? "" : mw.substring(fin, Math.min(mw.length(), fin + 200));
            int cp = mw.indexOf("copyFile(apk, copy)"), uz = mw.indexOf("MorpheLibrary.unzipApks(apk, tmp)");
            check("the private copy of the APK, and the unpacked bundle, are made inside the try whose finally removes morphe_inst (MA1-4)",
                    decl > 0 && tr > decl && fin > tr && cp > tr && cp < fin && uz > tr && uz < fin && finBody.contains("tmp.delete()") && mw.indexOf("morphe_inst/") < tr, "try=" + tr + " copy=" + cp + " unzip=" + uz + " finally=" + fin);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
