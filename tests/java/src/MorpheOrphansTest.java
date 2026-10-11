package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Runs that finished while the app was not running: their APK is found and filed; debris goes; a run in progress and the catalog folder are left alone. */
public class MorpheOrphansTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    static boolean has(java.util.List<MorpheOrphans.Orphan> l, String name) { for (MorpheOrphans.Orphan o : l) if (o.dir.getName().equals(name)) return true; return false; }
    static boolean has(java.util.List<File> l, File d) { return l.contains(d); }

    public static void main(String[] args) throws Exception {
        File jobs = File.createTempFile("morph", "jobs"); jobs.delete(); jobs.mkdirs();
        final long now = 10L * 24 * 3600 * 1000;
        long old = now - 20L * 3600 * 1000;
        String ok = "LOG INFO hi\nRESULT {\"success\":true,\"pkg\":\"com.a.b\",\"versionName\":\"2.0\",\"versionCode\":\"20\",\"applied\":[\"Hide ads\"]}\n";
        String bad = "LOG INFO hi\nRESULT {\"success\":false,\"error\":\"boom\"}\n";

        File done = new File(jobs, "jdone");                                   // finished, request saved
        write(new File(done, "events.log"), ok); write(new File(done, "patched.apk"), "APK");
        write(new File(done, "request.json"), "{\"pkg\":\"com.a.b\",\"name\":\"App B\",\"version\":\"2.0\",\"keepIn\":\"downloads\",\"job\":\"jdone\"}");
        File noreq = new File(jobs, "jnoreq");                                 // finished by an older build: no request saved
        write(new File(noreq, "events.log"), ok); write(new File(noreq, "patched.apk"), "APK");
        File failedRun = new File(jobs, "jfail");
        write(new File(failedRun, "events.log"), bad); write(new File(failedRun, "patched.apk"), "PARTIAL");
        File noApk = new File(jobs, "jnoapk");
        write(new File(noApk, "events.log"), ok);
        File going = new File(jobs, "jgoing");                                 // the run that is going now
        write(new File(going, "events.log"), ok); write(new File(going, "patched.apk"), "APK");
        File fresh = new File(jobs, "jfresh");                                 // no answer yet, touched just now (the engine may still be working)
        write(new File(fresh, "events.log"), "LOG INFO working\n"); write(new File(fresh, "job.json"), "{}");
        File stale = new File(jobs, "jstale");                                 // no answer, untouched for 20 hours
        write(new File(stale, "events.log"), "LOG INFO working\n"); write(new File(stale, "patched.apk"), "HALF");
        File catalog = new File(jobs, "list");                                 // the catalog read
        write(new File(catalog, "events.log"), "RESULT {\"success\":true}\n"); write(new File(catalog, "out.json"), "{}");
        File odd = new File(jobs, "not a job id");                             // not a job folder
        write(new File(odd, "events.log"), ok); write(new File(odd, "patched.apk"), "APK");
        write(new File(jobs, "stray.txt"), "x");
        for (File f : new File[] { stale, new File(stale, "events.log"), new File(stale, "patched.apk") }) f.setLastModified(old);
        for (File f : new File[] { fresh, new File(fresh, "events.log"), new File(fresh, "job.json") }) f.setLastModified(now);

        // 'now' is the fake clock: the files above were stamped with the real time, so move them around it
        for (File f : new File[] { done, noreq, failedRun, noApk, going, catalog, odd }) f.setLastModified(now);
        MorpheOrphans.Scan s = MorpheOrphans.scan(jobs, "jgoing", now, 6L * 3600 * 1000);

        check("a finished run with its APK is found", has(s.finished, "jdone"), s.finished.size() + "");
        MorpheOrphans.Orphan od = null;
        for (MorpheOrphans.Orphan o : s.finished) if (o.dir.getName().equals("jdone")) od = o;
        check("its saved request comes back whole", od != null && od.request.optString("name").equals("App B") && od.request.optString("keepIn").equals("downloads") && od.apk.getName().equals("patched.apk") && od.result.optBoolean("success"), String.valueOf(od == null ? null : od.request));
        MorpheOrphans.Orphan on = null;
        for (MorpheOrphans.Orphan o : s.finished) if (o.dir.getName().equals("jnoreq")) on = o;
        check("a run that left no request is filed from what the engine reported (package, version, patches)", on != null && on.request.optString("pkg").equals("com.a.b") && on.request.optString("version").equals("2.0") && on.request.optString("keepIn").equals("app") && on.result.optJSONArray("applied").length() == 1, String.valueOf(on == null ? null : on.request));
        check("a failed run is debris, also with a partial APK", has(s.debris, failedRun), null);
        check("a success whose APK is gone is debris", has(s.debris, noApk), null);
        check("a run with no answer for 20 hours is debris, also with a half APK (it is never filed)", has(s.debris, stale) && !has(s.finished, "jstale"), null);
        check("a run with no answer that was touched just now is left alone", !has(s.debris, fresh) && !has(s.finished, "jfresh"), null);
        check("the run that is going now is never touched", !has(s.debris, going) && !has(s.finished, "jgoing"), null);
        check("the catalog folder is never touched", !has(s.debris, catalog) && !has(s.finished, "list"), null);
        check("a folder that is not a job id, and loose files, are ignored", !has(s.debris, odd) && !has(s.finished, "not a job id"), null);
        check("exactly the two finished runs and the three debris folders were found", s.finished.size() == 2 && s.debris.size() == 3, s.finished.size() + "/" + s.debris.size());

        check("a missing jobs folder is an empty scan", MorpheOrphans.scan(new File(jobs, "nope"), "", now, 1000).finished.isEmpty(), null);
        write(new File(jobs, "jhalf/events.log"), "LOG INFO a\nRESULT {\"succ");                 // a half-written result line is not an answer
        new File(jobs, "jhalf").setLastModified(now); new File(jobs, "jhalf/events.log").setLastModified(now);
        MorpheOrphans.Scan s2 = MorpheOrphans.scan(jobs, "jgoing", now, 6L * 3600 * 1000);
        check("a half-written result is not an answer (and the run is not stale)", !has(s2.debris, new File(jobs, "jhalf")) && !has(s2.finished, "jhalf"), null);

        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { File f = new File(d, "src/com/bloatware/bingblop/MorpheBridge.java"); if (f.isFile()) src = f; }
        check("the bridge source was found", src != null, null);
        if (src != null) {
            String m = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
            int pa = m.indexOf("private void patch(JSONObject a");
            String pb = pa < 0 ? "" : m.substring(pa, Math.min(m.length(), pa + 4000));
            check("a patch run saves its request in its folder before the engine starts", pb.contains("request.json") && pb.indexOf("request.json") < pb.indexOf("runEngine("), null);
            int pl = m.indexOf("case \"patchedList\":");
            String lb = pl < 0 ? "" : m.substring(pl, Math.min(m.length(), pl + 160));
            check("the list of patched APKs files the orphans first", lb.contains("adoptOrphans()") && lb.indexOf("adoptOrphans()") < lb.indexOf("library.list()"), lb);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
