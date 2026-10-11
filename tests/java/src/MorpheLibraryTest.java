package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** The patched APKs folder: filing, listing, deleting, exporting, the log, path safety, and the .apks bundle of a split app. */
public class MorpheLibraryTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static File temp() throws Exception {
        File d = File.createTempFile("mlib", "dir"); d.delete(); d.mkdirs(); return d;
    }

    static File file(File dir, String name, String text) throws Exception {
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        o.write(text.getBytes("UTF-8")); o.close();
        return f;
    }

    public static void main(String[] args) throws Exception {
        File root = temp();
        MorpheLibrary lib = new MorpheLibrary(new File(root, "patched"));
        check("an empty library lists nothing", lib.list().length() == 0, null);

        File apk = file(root, "in.apk", "APKDATA1");
        File log = file(root, "run.log", "LOG INFO one\nLOG INFO two\n");
        JSONObject meta = new JSONObject().put("pkg", "com.google.android.youtube").put("versionName", "20.21.37").put("appName", "YouTube")
                .put("patches", new JSONArray().put("Hide ads").put("SponsorBlock"));
        JSONObject m = lib.add(apk, meta, log);
        check("add returns the completed meta", m.getString("id").endsWith("-com.google.android.youtube") && m.getLong("size") == 8 && m.getString("sha256").length() == 64 && m.getLong("patchedAt") > 0, m.toString());
        check("the APK was moved into the library under a readable name", !apk.exists() && new File(m.getString("file")).exists() && m.getString("fileName").equals("com.google.android.youtube_20.21.37-patched.apk"), m.getString("fileName"));
        check("the sha256 is right (SHA-256 of APKDATA1)", m.getString("sha256").equals(MorpheLibrary.sha256(new File(m.getString("file")))), null);
        check("the log was kept", lib.readLog(m.getString("id"), 1000).equals("LOG INFO one\nLOG INFO two\n"), lib.readLog(m.getString("id"), 1000));
        check("a long log is cut from the front", lib.readLog(m.getString("id"), 13).equals("LOG INFO two\n"), "[" + lib.readLog(m.getString("id"), 13) + "]");

        Thread.sleep(5);
        JSONObject m2 = lib.add(file(root, "in2.apk", "APK2"), new JSONObject().put("pkg", "com.reddit.frontpage").put("versionName", "2026.1"), null);
        JSONArray list = lib.list();
        check("two entries, newest first", list.length() == 2 && list.getJSONObject(0).getString("id").equals(m2.getString("id")), list.toString());
        check("no log: empty text", lib.readLog(m2.getString("id"), 100).isEmpty(), null);
        check("get returns the meta", lib.get(m.getString("id")).getString("appName").equals("YouTube"), null);
        check("a new instance on the same folder sees them", new MorpheLibrary(new File(root, "patched")).list().length() == 2, null);

        File exp = temp();
        File c1 = lib.exportTo(m.getString("id"), new File(exp, "Morphe Patcher"));
        File c2 = lib.exportTo(m.getString("id"), new File(exp, "Morphe Patcher"));
        check("export copies into a folder it creates", c1.exists() && c1.length() == 8 && c1.getParentFile().getName().equals("Morphe Patcher"), String.valueOf(c1));
        check("a second export takes a free name", c2.exists() && !c2.equals(c1) && c2.getName().contains("(1)"), String.valueOf(c2));
        check("the original is still there after exports", new File(m.getString("file")).exists(), null);

        File dl = temp();
        int before = lib.list().length();
        JSONObject m3 = lib.add(file(root, "in3.apk", "APK3"), new JSONObject().put("pkg", "com.x").put("versionName", "1"), null, new File(dl, "Morphe Patcher"));
        check("an APK can be kept outside (Downloads) while its entry stays", new File(m3.getString("file")).getParentFile().getName().equals("Morphe Patcher") && lib.list().length() == before + 1, m3.toString());
        JSONObject m4 = lib.add(file(root, "in4.apk", "APK4"), new JSONObject().put("pkg", "com.x").put("versionName", "1"), null, new File(dl, "Morphe Patcher"));
        check("a second one with the same name there gets a free name", !m4.getString("file").equals(m3.getString("file")) && new File(m4.getString("file")).exists(), m4.getString("file"));
        lib.delete(m3.getString("id")); lib.delete(m4.getString("id"));
        check("deleting the entry removes the outside copy too", !new File(m3.getString("file")).exists() && !new File(m4.getString("file")).exists(), null);

        boolean threw = false;
        try { lib.get("../x"); } catch (Exception e) { threw = true; }
        check("a path in the id is refused", threw, null);
        threw = false;
        try { lib.delete(""); } catch (Exception e) { threw = true; }
        check("an empty id is refused", threw, null);

        check("delete removes the folder", lib.delete(m.getString("id")) && lib.list().length() == 1 && !new File(m.getString("file")).exists(), null);
        check("deleting again says no", !lib.delete(m.getString("id")), null);
        new File(m2.getString("file")).delete();
        check("an entry whose APK was removed by hand drops out of the list", lib.list().length() == 0, null);

        File a = file(root, "base.apk", "BASE"), b = file(root, "split_config.arm64_v8a.apk", "ARM64"), c = file(root, "split_config.xxhdpi.apk", "XXHDPI");
        List<File> parts = new ArrayList<File>(); parts.add(a); parts.add(b); parts.add(c);
        File apks = new File(root, "app.apks");
        MorpheLibrary.zipApks(parts, apks);
        ZipFile z = new ZipFile(apks);
        check("the .apks has the base first and every split", z.size() == 3 && z.getEntry("base.apk") != null && z.getEntry("split_config.xxhdpi.apk") != null, null);
        z.close();
        File un = new File(root, "un");
        List<File> got = MorpheLibrary.unzipApks(apks, un);
        check("unzipApks writes the APKs, base first", got.size() == 3 && got.get(0).getName().equals("base.apk") && got.get(0).length() == 4 && new File(un, "split_config.xxhdpi.apk").length() == 6, got.toString());
        File evil = new File(root, "evil.apks");
        java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new FileOutputStream(evil));
        zo.putNextEntry(new java.util.zip.ZipEntry("../../escape.apk")); zo.write(1); zo.closeEntry();
        zo.putNextEntry(new java.util.zip.ZipEntry("assets/readme.txt")); zo.write(2); zo.closeEntry();
        zo.close();
        List<File> g2 = MorpheLibrary.unzipApks(evil, new File(root, "un2"));
        check("a path in an entry name stays inside the folder", g2.size() == 1 && g2.get(0).getParentFile().getName().equals("un2") && !new File(root.getParentFile(), "escape.apk").exists(), g2.toString());
        File nothing = new File(root, "none.apks");
        java.util.zip.ZipOutputStream zn = new java.util.zip.ZipOutputStream(new FileOutputStream(nothing));
        zn.putNextEntry(new java.util.zip.ZipEntry("a.txt")); zn.write(1); zn.closeEntry(); zn.close();
        boolean threw2 = false;
        try { MorpheLibrary.unzipApks(nothing, new File(root, "un3")); } catch (IOException e) { threw2 = true; }
        check("a bundle without an APK is refused", threw2, null);
        // M-3: filing never reuses an id, and a filing that fails leaves nothing behind (the fallback into the app folder then works at once)
        File root3 = temp();
        MorpheLibrary lib3 = new MorpheLibrary(new File(root3, "patched"));
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (int i = 0; i < 300; i++) {
            JSONObject r = lib3.add(file(root3, "q" + i + ".apk", "D" + i), new JSONObject().put("pkg", "com.a.b").put("versionName", "1"), null);
            ids.add(r.getString("id"));
        }
        check("300 filings of one app back to back get 300 different ids and all are listed", ids.size() == 300 && lib3.list().length() == 300, ids.size() + " " + lib3.list().length());

        File notDir = file(root3, "blocker", "x");
        File badDownloads = new File(notDir, "Morphe Patcher");                       // its parent is a file: the folder cannot be made
        File keep = file(root3, "fresh.apk", "FRESH");
        int folders0 = new File(root3, "patched").list().length;
        boolean refused = false;
        try { lib3.add(keep, new JSONObject().put("pkg", "com.c.d"), null, badDownloads); } catch (IOException e) { refused = true; }
        check("Downloads that cannot be made refuses the filing", refused, null);
        check("the failed filing left no folder and the APK where it was", new File(root3, "patched").list().length == folders0 && keep.isFile() && keep.length() == 5, new File(root3, "patched").list().length + " vs " + folders0);
        JSONObject fb = lib3.add(keep, new JSONObject().put("pkg", "com.c.d"), null);
        check("the fallback into the app folder works straight after, in the same millisecond", new File(fb.getString("file")).isFile() && !keep.exists() && new File(root3, "patched").list().length == folders0 + 1, fb.toString());

        File dl3 = new File(root3, "Downloads/Morphe Patcher");
        File keep2 = file(root3, "second.apk", "SECOND");
        File logDir = new File(root3, "logdir"); logDir.mkdirs();                    // a log that cannot be read: fails after the APK was already moved
        boolean refused2 = false;
        try { lib3.add(keep2, new JSONObject().put("pkg", "com.e.f"), logDir, dl3); } catch (IOException e) { refused2 = true; }
        check("a failure after the move puts the APK back and removes what was written", refused2 && keep2.isFile() && keep2.length() == 6 && (!dl3.exists() || dl3.list().length == 0) && new File(root3, "patched").list().length == folders0 + 1, null);
        JSONObject ok2 = lib3.add(keep2, new JSONObject().put("pkg", "com.e.f"), null, dl3);
        check("and the next attempt into Downloads works", new File(ok2.getString("file")).getParentFile().equals(dl3) && new File(ok2.getString("file")).length() == 6, ok2.toString());

        // M-10: an APK kept outside (Downloads) that cannot be seen is not forgotten: the entry, its log and its record stay
        File root5 = temp();
        MorpheLibrary lib5 = new MorpheLibrary(new File(root5, "patched"));
        File card = new File(root5, "card/Morphe Patcher");
        JSONObject out5 = lib5.add(file(root5, "o.apk", "OUTSIDE"), new JSONObject().put("pkg", "com.o.p").put("versionName", "2"), file(root5, "o.log", "LOG LINE\n"), card);
        File moved = new File(root5, "card-away");
        check("setup: the APK is in the outside folder", new File(out5.getString("file")).isFile(), out5.toString());
        new File(root5, "card").renameTo(moved);                                           // the card is not there for a moment
        JSONArray l5 = lib5.list();
        check("an entry whose outside APK cannot be seen stays in the list, marked missing", l5.length() == 1 && l5.getJSONObject(0).optBoolean("missing"), l5.toString());
        check("its log and record are still there", lib5.readLog(out5.getString("id"), 100).equals("LOG LINE\n") && lib5.get(out5.getString("id")) != null, null);
        moved.renameTo(new File(root5, "card"));                                           // the card is back
        l5 = lib5.list();
        check("when the file is back the entry is normal again (no missing mark)", l5.length() == 1 && !l5.getJSONObject(0).optBoolean("missing"), l5.toString());
        new File(out5.getString("file")).delete();
        check("deleting the entry still works when its APK is gone", lib5.list().length() == 1 && lib5.delete(out5.getString("id")) && lib5.list().length() == 0, null);
        JSONObject own5 = lib5.add(file(root5, "w.apk", "OWN"), new JSONObject().put("pkg", "com.w.x"), null);
        new File(own5.getString("file")).delete();
        check("an APK in the library's own folder that is gone still drops its entry", lib5.list().length() == 0 && !new File(root5, "patched/" + own5.getString("id")).exists(), null);

        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
    }
}
