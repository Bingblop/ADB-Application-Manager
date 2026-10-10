package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The data areas and everything that reads or deletes through them: the availability probes of spec 8.3 (Java, privileged shell or unavailable with the reason
 * text) on a temp tree with a fake working mode, the shell over a real sh (SdmShell streaming, timeout, kill on cancel, the uid), SdmFsShell against this
 * machine's sh, find, stat, rm, sha256sum, head and od on a temp tree, SdmFs choosing per area, the running-app parser and SdmSafety rejecting dangerous targets.
 * The shell part says SKIP when `stat -c '%f %s %Y %u %n'` is not supported here (GNU stat is).
 */
public class SdmAreasTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    /** A working mode that runs the script in this machine's sh; uid probes can be faked. */
    static class Runner implements SdmShell.ModeRunner {
        volatile String mode = "shizuku";
        volatile int fakeUid = -2;                      // -2: ask the real id -u
        volatile int opened = 0;
        @Override public String mode() { return mode; }
        @Override public Process open(String script) throws IOException {
            opened++;
            if (script.startsWith("id -u") && fakeUid != -2) script = "echo " + fakeUid;
            return new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start();
        }
    }

    /** What an app without privileges sees on a new Android: Android/data and Android/obb cannot be listed. */
    static class Restricted extends SdmFsJava {
        final List<String> blocked = new ArrayList<String>();
        @Override public String[] list(String dir) {
            for (String b : blocked) if (dir.equals(b) || dir.startsWith(b + "/")) return null;
            return super.list(dir);
        }
    }

    static File mk(File root, String rel, String content) throws Exception {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes("UTF-8"));
        return f;
    }

    static String sha(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static Sdm.AreaInfo find(List<Sdm.AreaInfo> l, Sdm.Area a, String rootEnd) {
        for (Sdm.AreaInfo i : l) if (i.area == a && (rootEnd == null || i.root.endsWith(rootEnd))) return i;
        return null;
    }

    static String via(List<Sdm.AreaInfo> l, Sdm.Area a) {
        Sdm.AreaInfo i = find(l, a, null);
        return i == null ? "none" : i.via.isEmpty() ? "-" : i.via;
    }

    static class FakeAreas implements Sdm.Areas {
        final List<Sdm.AreaInfo> l = new ArrayList<Sdm.AreaInfo>();
        @Override public List<Sdm.AreaInfo> all() { return l; }
        @Override public Sdm.AreaInfo get(Sdm.Area a) { for (Sdm.AreaInfo i : l) if (i.area == a) return i; return null; }
        @Override public Sdm.AreaInfo areaOf(String path) {
            Sdm.AreaInfo best = null;
            for (Sdm.AreaInfo i : l) if ((path.equals(i.root) || Sdm.isInside(path, i.root)) && (best == null || i.root.length() > best.root.length())) best = i;
            return best;
        }
    }

    public static void main(String[] args) throws Exception {
        File T = Files.createTempDirectory("sdmareas").toFile();
        File sd = new File(T, "storage/emulated/0");
        mk(T, "storage/emulated/0/DCIM/a.jpg", "jpg");
        mk(T, "storage/emulated/0/Android/data/com.other/cache/x", "x");
        mk(T, "storage/emulated/0/Android/data/com.me/files/y", "y");
        new File(sd, "Android/obb/com.game").mkdirs();
        new File(sd, "Android/media/com.m").mkdirs();
        File data = new File(T, "data");
        for (String d : new String[] { "data/com.a", "system", "system_ce/0", "system_de/0", "misc", "app/x", "app-lib", "dalvik-cache/arm64", "dalvik-cache/profiles", "cache" }) new File(data, d).mkdirs();
        new File(T, "data_mirror/data_ce/null/0").mkdirs();
        new File(T, "data_mirror/data_de/null/0").mkdirs();
        new File(T, "data_mirror/ref_profiles").mkdirs();

        // ======================================================================= SdmShell
        Runner rn = new Runner();
        SdmShell sh = new SdmShell(rn);
        eq("run returns the output", sh.run("echo one; echo two", 10000), "one\ntwo");
        final List<String> got = new ArrayList<String>();
        sh.stream("printf 'a\\nb\\nc\\n'", 10000, new Sdm.LineSink() { @Override public void line(String l) { got.add(l); } }, null);
        eq("stream line by line", got.toString(), "[a, b, c]");
        eq("stderr is part of the output", sh.run("echo err 1>&2", 10000), "err");
        rn.fakeUid = 2000;
        eq("uid of the shell user", sh.uid(), 2000);
        int opened = rn.opened;
        sh.uid();
        eq("uid is asked once per mode", rn.opened, opened);
        rn.mode = "root"; rn.fakeUid = 0;
        eq("another mode asks again", sh.uid(), 0);
        rn.mode = "standard";
        eq("standard mode: no shell", sh.uid(), -1);
        boolean threw = false;
        try { sh.run("echo hi", 1000); } catch (IOException e) { threw = true; }
        is("standard mode: run throws", threw, true);
        rn.mode = "shizuku"; rn.fakeUid = -2;
        sh.forget();
        long t0 = System.currentTimeMillis();
        threw = false;
        try { sh.run("sleep 5; echo late", 300); } catch (IOException e) { threw = e.getMessage().contains("Timed out"); }
        is("timeout throws", threw, true);
        is("and does not wait for the command (" + (System.currentTimeMillis() - t0) + " ms)", System.currentTimeMillis() - t0 < 3000, true);
        final boolean[] stop = { false };
        final int[] lines = { 0 };
        t0 = System.currentTimeMillis();
        sh.stream("yes line", 60000, new Sdm.LineSink() { @Override public void line(String l) { if (++lines[0] == 50) stop[0] = true; } }, new Sdm.Cancel() { @Override public boolean cancelled() { return stop[0]; } });
        is("cancel stops an endless stream (" + (System.currentTimeMillis() - t0) + " ms)", System.currentTimeMillis() - t0 < 5000 && lines[0] >= 50, true);
        final boolean[] stop2 = { false };
        t0 = System.currentTimeMillis();
        new Thread(new Runnable() { @Override public void run() { try { Thread.sleep(300); } catch (InterruptedException e) { } stop2[0] = true; } }).start();
        sh.stream("sleep 30", 60000, new Sdm.LineSink() { @Override public void line(String l) { } }, new Sdm.Cancel() { @Override public boolean cancelled() { return stop2[0]; } });
        is("cancel kills a silent process (" + (System.currentTimeMillis() - t0) + " ms)", System.currentTimeMillis() - t0 < 5000, true);
        threw = false;
        try { sh.run(new String(new char[SdmShell.MAX_SCRIPT_BYTES + 10]).replace('\0', 'x'), 1000); } catch (IOException e) { threw = true; }
        is("a script that is too long is refused", threw, true);
        eq("running apps from ps", SdmShell.parseRunning("USER PID NAME\nroot 1 init\nu0_a123 456 com.foo.bar\nu0_a124 457 com.foo.baz:remote\nu0_a1 3 surfaceflinger\nu10_a5 9 /system/bin/x\nsystem 5 com.android.systemui\n").toString().length() > 0
                ? new java.util.TreeSet<String>(SdmShell.parseRunning("USER PID NAME\nroot 1 init\nu0_a123 456 com.foo.bar\nu0_a124 457 com.foo.baz:remote\nu0_a1 3 surfaceflinger\nu10_a5 9 /system/bin/x\nsystem 5 com.android.systemui\n")).toString() : "", "[com.foo.bar, com.foo.baz]");
        eq("running apps, toybox layout", new java.util.TreeSet<String>(SdmShell.parseRunning("u0_a8  1234  1  100 200 0 0 S com.a.b\n")).toString(), "[com.a.b]");
        eq("running apps: no shell", SdmShell.runningPackages(new SdmShell(new Runner() {{ mode = "standard"; }})).size(), 0);

        // ======================================================================= SdmSafety
        FakeAreas fa = new FakeAreas();
        fa.l.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, "/storage/emulated/0", true, "java", ""));
        fa.l.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, "/storage/emulated/0/Android/data", true, "shell", ""));
        fa.l.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_OBB, "/storage/emulated/0/Android/obb", true, "", "no"));
        fa.l.add(new Sdm.AreaInfo(Sdm.Area.PRIVATE_DATA, "/data/data", true, "", "no root"));
        fa.l.add(new Sdm.AreaInfo(Sdm.Area.DATA, "/data", true, "shell", ""));
        fa.l.add(new Sdm.AreaInfo(Sdm.Area.DATA_SYSTEM, "/data/system", true, "shell", ""));
        String[] bad = { null, "", "relative/path", "/", "/data", "/storage", "/storage/emulated", "/storage/emulated/0", "/storage/emulated/0/", "/storage/emulated/0/Android", "/storage/emulated/0/android",
                "/storage/emulated/0/DCIM", "/storage/emulated/0/dcim", "/storage/emulated/0/Download", "/storage/ABCD-1234", "/storage/abcd-1234/Music", "/sdcard", "/sdcard/x", "/storage/self/primary",
                "/storage/emulated/0/a/../b", "/storage/emulated/0/..", "/storage/emulated/0/a/./b", "/storage/emulated/0/a\nb", "/storage/emulated/0/a\rb", "/storage/emulated/0/a\0b", "/storage/emulated/0/a//b",
                "/storage/emulated/0/a/", "/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb/com.x", "/data/data/com.x", "/data/system", "/system/app/x", "/proc/1", "/data/media/0", "/data/local/tmp",
                "/mnt/sdcard/x", "/storage/emulated/1/x", "/data_mirror/data_ce/null/0/com.x" };
        for (String b : bad) is("rejected: " + (b == null ? "null" : b.replace("\n", "\\n").replace("\r", "\\r").replace("\0", "\\0")), SdmSafety.check(b, fa) != null, true);
        String[] good = { "/storage/emulated/0/Download/x.apk", "/storage/emulated/0/a..b", "/storage/emulated/0/..hidden", "/storage/emulated/0/Android/data/com.x/cache", "/storage/emulated/0/Pictures/sub/x.jpg",
                "/storage/emulated/0/DCIM/Camera", "/data/app/x", "/data/dalvik-cache/x", "/storage/emulated/0/My Folder/it's.txt" };
        for (String g : good) is("accepted: " + g, SdmSafety.check(g, fa) == null, true);
        is("DCIM below the top is fine, DCIM itself is not", SdmSafety.check("/storage/emulated/0/DCIM/Camera", fa) == null && SdmSafety.check("/storage/emulated/0/DCIM", fa) != null, true);
        is("lexical alone does not know the areas", SdmSafety.lexical("/somewhere/else/file") == null && SdmSafety.lexical("/data") != null, true);
        is("no areas: lexical rules only", SdmSafety.check("/somewhere/else/file", null) == null, true);
        Map<String, String> rej = new LinkedHashMap<String, String>();
        List<String> okp = SdmSafety.accepted(Arrays.asList("/storage/emulated/0/Download/a", "/", "/storage/emulated/0/x/../y", "/storage/emulated/0/Download/b"), fa, rej);
        eq("accepted() keeps the good ones in order", okp.toString(), "[/storage/emulated/0/Download/a, /storage/emulated/0/Download/b]");
        eq("accepted() says why for the others", rej.size(), 2);
        eq("a reason is in words", rej.get("/"), "Is the root of the file system");

        // ======================================================================= SdmAreas
        Restricted jfs = new Restricted();
        jfs.blocked.add(sd.getPath() + "/Android/data");
        jfs.blocked.add(sd.getPath() + "/Android/obb");
        SdmAreas.Config cfg = new SdmAreas.Config();
        cfg.publicRoots = new ArrayList<String>(Arrays.asList(sd.getPath()));
        cfg.dataRoot = data.getPath(); cfg.cacheRoot = new File(T, "cache2").getPath(); cfg.dataMirror = new File(T, "data_mirror").getPath();
        cfg.sdkInt = 34; cfg.ownPackage = "com.me";
        final boolean[] storage = { true };
        Runner ar = new Runner();
        ar.mode = "standard";
        SdmAreas areas = new SdmAreas(new SdmShell(ar), jfs, cfg, new SdmAreas.Access() { @Override public boolean storage() { return storage[0]; } });

        // no privilege, All-files access
        List<Sdm.AreaInfo> l = areas.all();
        eq("standard: public storage by Java", via(l, Sdm.Area.SDCARD), "java");
        eq("standard: public media by Java", via(l, Sdm.Area.PUBLIC_MEDIA), "java");
        eq("standard: public app data unavailable", via(l, Sdm.Area.PUBLIC_DATA), "-");
        eq("standard: the reason in words", find(l, Sdm.Area.PUBLIC_DATA, null).reason, "This feature needs root or ADB access, which isn't available on this device.");
        eq("standard: obb unavailable too", via(l, Sdm.Area.PUBLIC_OBB), "-");
        eq("standard: private data unavailable", via(l, Sdm.Area.PRIVATE_DATA), "-");
        eq("standard: and why", find(l, Sdm.Area.PRIVATE_DATA, null).reason, "This feature needs root access, but SD Maid couldn't get it on this device.");
        eq("standard: portable storage", find(l, Sdm.Area.PORTABLE, null).reason, "No portable storage found");
        is("the primary flag", find(l, Sdm.Area.SDCARD, null).primary, true);
        is("an unavailable area is not available()", find(l, Sdm.Area.PRIVATE_DATA, null).available(), false);
        File[] stray = sd.listFiles();
        int testFiles = 0;
        if (stray != null) for (File f : stray) if (f.getName().startsWith("eu.darken.sdmse-test")) testFiles++;
        eq("the write test leaves nothing behind", testFiles, 0);
        eq("get() of an available area", areas.get(Sdm.Area.SDCARD).root, sd.getPath());
        is("get() of an unavailable area still answers", !areas.get(Sdm.Area.PRIVATE_DATA).available(), true);

        // areaOf
        eq("areaOf: a photo", areas.areaOf(sd.getPath() + "/DCIM/a.jpg").area, Sdm.Area.SDCARD);
        eq("areaOf: below Android/data is never the sdcard", areas.areaOf(sd.getPath() + "/Android/data/com.other/cache").area, Sdm.Area.PUBLIC_DATA);
        eq("areaOf: media", areas.areaOf(sd.getPath() + "/Android/media/com.m/x").area, Sdm.Area.PUBLIC_MEDIA);
        eq("areaOf: obb", areas.areaOf(sd.getPath() + "/Android/obb/com.game").area, Sdm.Area.PUBLIC_OBB);
        eq("areaOf: the root itself", areas.areaOf(sd.getPath()).area, Sdm.Area.SDCARD);
        eq("areaOf: something else", areas.areaOf("/nowhere/x"), null);
        eq("areaOf: a name that only starts the same", areas.areaOf(sd.getPath() + "2/x"), null);
        eq("areaOf: below /data", areas.areaOf(data.getPath() + "/other/x").area, Sdm.Area.DATA);
        eq("areaOf: deepest wins (app)", areas.areaOf(data.getPath() + "/app/x/base.apk").area, Sdm.Area.APP_APP);

        // no All-files access, no privilege
        storage[0] = false;
        l = areas.all();
        eq("no access: public storage unavailable", via(l, Sdm.Area.SDCARD), "-");
        eq("no access: the reason", find(l, Sdm.Area.SDCARD, null).reason, "Can't access " + sd.getPath() + ".");
        eq("no access: media too", via(l, Sdm.Area.PUBLIC_MEDIA), "-");
        storage[0] = true;

        // ADB (uid 2000): the shell reads Android/data and Android/obb, but not /data
        ar.mode = "adb_tcp"; ar.fakeUid = 2000;
        l = areas.all();
        eq("adb: public storage still by Java", via(l, Sdm.Area.SDCARD), "java");
        eq("adb: public app data through the shell", via(l, Sdm.Area.PUBLIC_DATA), "shell");
        eq("adb: public app resources through the shell", via(l, Sdm.Area.PUBLIC_OBB), "shell");
        eq("adb: private data still unavailable", via(l, Sdm.Area.PRIVATE_DATA), "-");
        eq("adb: /data areas unavailable", via(l, Sdm.Area.DATA) + via(l, Sdm.Area.APP_APP), "--");
        eq("areaOf follows: Android/data via shell", areas.areaOf(sd.getPath() + "/Android/data/com.other/cache").via, "shell");
        // a mount that is missing even for the shell
        new File(sd, "Android/obb").renameTo(new File(sd, "Android/obb_gone"));
        areas.refresh();
        eq("adb: a folder that does not exist", via(areas.all(), Sdm.Area.PUBLIC_OBB), "-");
        eq("adb: said in words", find(areas.all(), Sdm.Area.PUBLIC_OBB, null).reason, "Can't access " + sd.getPath() + "/Android/obb.");
        new File(sd, "Android/obb_gone").renameTo(new File(sd, "Android/obb"));

        // root
        ar.mode = "root"; ar.fakeUid = 0;
        l = areas.all();
        eq("root: public app data", via(l, Sdm.Area.PUBLIC_DATA), "shell");
        eq("root: private data", via(l, Sdm.Area.PRIVATE_DATA), "shell");
        eq("root: private data on Android 11+ comes from the mirror, CE and DE", find(l, Sdm.Area.PRIVATE_DATA, "data_ce/null/0").via + find(l, Sdm.Area.PRIVATE_DATA, "data_de/null/0").via, "shellshell");
        eq("root: /data", via(l, Sdm.Area.DATA), "shell");
        eq("root: system", via(l, Sdm.Area.DATA_SYSTEM) + via(l, Sdm.Area.DATA_SYSTEM_CE) + via(l, Sdm.Area.DATA_SYSTEM_DE) + via(l, Sdm.Area.DATA_MISC), "shellshellshellshell");
        eq("root: a folder this phone does not have", via(l, Sdm.Area.APP_APP_PRIVATE) + " " + find(l, Sdm.Area.APP_APP_PRIVATE, null).reason, "- Not present on this device");
        eq("root: dalvik-cache per architecture", find(l, Sdm.Area.DALVIK_DEX, "arm64").via, "shell");
        eq("root: dalvik profiles", via(l, Sdm.Area.DALVIK_PROFILE), "shell");
        eq("root: ART profiles from the mirror", find(l, Sdm.Area.ART_PROFILE, "ref_profiles").via, "shell");
        eq("areaOf: a private data folder of an app", areas.areaOf(new File(T, "data_mirror/data_ce/null/0/com.a/cache").getPath()).area, Sdm.Area.PRIVATE_DATA);
        // Android 10 style: no mirror, /data/data
        cfg.sdkInt = 29;
        areas.refresh();
        l = areas.all();
        eq("Android 10: private data is /data/data", find(l, Sdm.Area.PRIVATE_DATA, null).root, data.getPath() + "/data");
        cfg.sdkInt = 34;

        // Java can list Android/data when it shows another app's folder (old Android, or a phone that allows it)
        Restricted open = new Restricted();
        SdmAreas oa = new SdmAreas(new SdmShell(new Runner() {{ mode = "standard"; }}), open, cfg, null);
        eq("Java sees Android/data with another app in it", via(oa.all(), Sdm.Area.PUBLIC_DATA), "java");
        File own = new File(T, "own/storage/0");
        new File(own, "Android/data/com.me").mkdirs();
        SdmAreas.Config c2 = new SdmAreas.Config();
        c2.publicRoots = new ArrayList<String>(Arrays.asList(own.getPath())); c2.ownPackage = "com.me"; c2.dataRoot = new File(T, "nodata").getPath(); c2.dataMirror = new File(T, "nomirror").getPath(); c2.cacheRoot = new File(T, "nocache").getPath();
        SdmAreas oa2 = new SdmAreas(new SdmShell(new Runner() {{ mode = "standard"; }}), new SdmFsJava(), c2, null);
        eq("only this app's own folder: not usable with Java", via(oa2.all(), Sdm.Area.PUBLIC_DATA), "-");
        c2.sdkInt = 29;
        oa2.refresh();
        eq("Android 10: readable is enough", via(oa2.all(), Sdm.Area.PUBLIC_DATA), "java");
        // two volumes and a USB stick
        File sd2 = new File(T, "storage/ABCD-1234"), usb = new File(T, "usb");
        new File(sd2, "Android/data").mkdirs(); usb.mkdirs();
        SdmAreas.Config c3 = new SdmAreas.Config();
        c3.publicRoots = new ArrayList<String>(Arrays.asList(sd.getPath(), sd2.getPath())); c3.portableRoots = new ArrayList<String>(Arrays.asList(usb.getPath()));
        c3.dataRoot = c2.dataRoot; c3.dataMirror = c2.dataMirror; c3.cacheRoot = c2.cacheRoot;
        SdmAreas a3 = new SdmAreas(new SdmShell(new Runner() {{ mode = "standard"; }}), new SdmFsJava(), c3, null);
        l = a3.all();
        is("a second volume is its own SDCARD area, not the primary", find(l, Sdm.Area.SDCARD, "ABCD-1234").via.equals("java") && !find(l, Sdm.Area.SDCARD, "ABCD-1234").primary, true);
        eq("portable storage", find(l, Sdm.Area.PORTABLE, "usb").via, "java");
        eq("areaOf the second volume", a3.areaOf(sd2.getPath() + "/x").root, sd2.getPath());
        eq("discoverPublicRoots starts with the primary one", SdmAreas.discoverPublicRoots(new SdmFsJava(), "/storage/emulated/0").get(0), "/storage/emulated/0");
        eq("labels of the spec", SdmAreas.label(Sdm.Area.SDCARD) + "|" + SdmAreas.label(Sdm.Area.PUBLIC_MEDIA) + "|" + SdmAreas.label(Sdm.Area.PUBLIC_DATA) + "|" + SdmAreas.label(Sdm.Area.PUBLIC_OBB) + "|" + SdmAreas.label(Sdm.Area.PRIVATE_DATA) + "|" + SdmAreas.label(Sdm.Area.PORTABLE),
                "Public storage|Public app media|Public app data|Public app resources|Private app data|Portable storage");

        // ======================================================================= SdmFs
        ar.mode = "root"; ar.fakeUid = 0;
        areas.refresh();
        SdmFs fs = new SdmFs(areas, jfs, new SdmFsShell(new SdmShell(ar)));
        is("Android/data goes through the shell", fs.viaShell(sd.getPath() + "/Android/data/com.other"), true);
        is("DCIM is read by Java", fs.viaShell(sd.getPath() + "/DCIM/a.jpg"), false);
        eq("list below Android/data (java cannot, the shell can)", Arrays.asList(fs.list(sd.getPath() + "/Android/data")).contains("com.other"), true);
        is("stat through the right side", fs.stat(sd.getPath() + "/Android/data/com.other/cache/x").size == 1, true);
        // deletes: rails first
        is("deleting the area root is refused", !fs.delete(sd.getPath()) && sd.isDirectory(), true);
        is("deleting Android/data itself is refused", !fs.delete(sd.getPath() + "/Android/data") && new File(sd, "Android/data").isDirectory(), true);
        is("deleting something outside every area is refused", !fs.delete(T.getPath() + "/outside") && true, true);
        File outside = mk(T, "outside/keep.txt", "keep");
        is("an existing file outside the areas is not touched", !fs.delete(outside.getPath()) && outside.exists(), true);
        is("a path with .. is refused", !fs.delete(sd.getPath() + "/DCIM/../Android") && new File(sd, "Android").isDirectory(), true);
        File junk = mk(T, "storage/emulated/0/Junk/j.txt", "j");
        is("delete by Java", fs.delete(sd.getPath() + "/Junk") && !new File(sd, "Junk").exists(), true);
        is("delete through the shell", fs.delete(sd.getPath() + "/Android/data/com.other") && !new File(sd, "Android/data/com.other").exists(), true);
        mk(T, "storage/emulated/0/Junk2/a", "a"); mk(T, "storage/emulated/0/Android/data/com.z/b", "b"); mk(T, "storage/emulated/0/Android/data/com.me/files/y2", "y");
        Set<String> gone = fs.deleteAll(Arrays.asList(sd.getPath() + "/Junk2", sd.getPath() + "/Android/data/com.z", sd.getPath(), T.getPath() + "/outside", sd.getPath() + "/never-existed"), null);
        is("deleteAll mixes Java and shell and refuses the rails", gone.contains(sd.getPath() + "/Junk2") && gone.contains(sd.getPath() + "/Android/data/com.z") && !gone.contains(sd.getPath()) && !gone.contains(T.getPath() + "/outside"), true);
        is("a path that is not there counts as deleted", gone.contains(sd.getPath() + "/never-existed"), true);
        is("what was refused is still there", sd.isDirectory() && outside.exists(), true);
        // walk with a prune list, on the Java side and on the shell side
        mk(T, "storage/emulated/0/W/keep/a", "a"); mk(T, "storage/emulated/0/W/skip/b", "b");
        final List<String> seen = new ArrayList<String>();
        fs.walk(sd.getPath() + "/W", Arrays.asList(sd.getPath() + "/W/skip"), new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { seen.add(e.path.substring(e.path.indexOf("/W/") + 3)); return true; } }, null);
        java.util.Collections.sort(seen);
        eq("pruned walk (Java)", seen.toString(), "[keep, keep/a]");
        seen.clear();
        mk(T, "storage/emulated/0/Android/data/com.w/keep/a", "a"); mk(T, "storage/emulated/0/Android/data/com.w/skip/b", "b");
        fs.walk(sd.getPath() + "/Android/data/com.w", Arrays.asList(sd.getPath() + "/Android/data/com.w/skip"), new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { seen.add(e.path.substring(e.path.indexOf("com.w/") + 6)); return true; } }, null);
        java.util.Collections.sort(seen);
        eq("pruned walk (shell)", seen.toString(), "[keep, keep/a]");

        // ======================================================================= SdmFsShell on a real tree
        Runner pr = new Runner();
        SdmShell psh = new SdmShell(pr);
        SdmFsShell fsh = new SdmFsShell(psh);
        String probe = "";
        try { probe = psh.run("stat -c '%f %s %Y %u %n' / 2>/dev/null", 10000); } catch (IOException e) { probe = ""; }
        boolean statOk = SdmFsShell.parseStat(probe) != null;
        // the parser itself needs no stat
        Sdm.Entry pe = SdmFsShell.parseStat("81a4 12 1700000000 10123 /a b/c d.txt");
        is("parseStat: a file, spaces in the name", pe != null && pe.type == Sdm.FILE && pe.size == 12 && pe.mtime == 1700000000L && pe.uid == 10123 && pe.path.equals("/a b/c d.txt") && pe.name.equals("c d.txt"), true);
        eq("parseStat: directory", SdmFsShell.parseStat("41ed 4096 1 0 /d").type, Sdm.DIR);
        eq("parseStat: link", SdmFsShell.parseStat("a1ff 7 1 0 /l").type, Sdm.LINK);
        eq("parseStat: other", SdmFsShell.parseStat("1180 0 1 0 /dev/x").type, Sdm.OTHER);
        for (String junk2 : new String[] { "", "stat: cannot stat", "zz 1 1 1 /x", "81a4 1 1 1", "81a4 1 1 1 relative", "81a4 x 1 1 /x", "__SDM_END__" }) eq("parseStat: not a stat line: " + junk2, SdmFsShell.parseStat(junk2), null);
        eq("globEscape", SdmFsShell.globEscape("/a/we*ird/[x]?\\"), "/a/we\\*ird/\\[x]\\?\\\\");

        if (!statOk) {
            System.out.println("SKIP SdmFsShell: `stat -c '%f %s %Y %u %n'` is not supported by this machine's stat");
        } else {
            File R = Files.createTempDirectory("sdmfsshell").toFile();
            mk(R, "tree/a.txt", "hello");
            mk(R, "tree/d/b.txt", "bb");
            mk(R, "tree/name with space.txt", "sp");
            mk(R, "tree/sp ace/x", "x");
            mk(R, "tree/it's.txt", "q");
            mk(R, "tree/\u00fc.txt", "u");
            new File(R, "tree/e").mkdirs();
            Files.createSymbolicLink(Paths.get(R.getPath(), "tree/l"), Paths.get("a.txt"));
            Files.createSymbolicLink(Paths.get(R.getPath(), "tree/dangling"), Paths.get("/does/not/exist"));
            byte[] bin = new byte[300];
            for (int i = 0; i < bin.length; i++) bin[i] = (byte) (i * 7);
            Files.write(new File(R, "tree/bin.dat").toPath(), bin);
            String tr = R.getPath() + "/tree";

            Sdm.Entry e1 = fsh.stat(tr + "/a.txt");
            is("stat: a file", e1 != null && e1.type == Sdm.FILE && e1.size == 5 && e1.mtime > 1600000000L && e1.path.equals(tr + "/a.txt"), true);
            int myUid = Integer.parseInt(psh.run("id -u", 5000).trim());
            eq("stat: uid", e1.uid, myUid);
            eq("stat: mtime like Java's", e1.mtime, new File(tr + "/a.txt").lastModified() / 1000);
            eq("stat: a directory", fsh.stat(tr + "/d").type, Sdm.DIR);
            eq("stat: a link is the link itself", fsh.stat(tr + "/l").type, Sdm.LINK);
            eq("stat: a dangling link", fsh.stat(tr + "/dangling").type, Sdm.LINK);
            eq("stat: nothing there", fsh.stat(tr + "/nope"), null);
            eq("stat: a quote in the name", fsh.stat(tr + "/it's.txt").size, 1L);
            eq("stat: spaces in the name", fsh.stat(tr + "/name with space.txt").name, "name with space.txt");
            eq("stat: unicode", fsh.stat(tr + "/\u00fc.txt") != null, true);
            Map<String, Sdm.Entry> sa = fsh.statAll(Arrays.asList(tr + "/a.txt", tr + "/d", tr + "/missing", tr + "/it's.txt"), null);
            eq("statAll", sa.keySet().size() + " " + sa.containsKey(tr + "/missing"), "3 false");

            Set<String> names = new HashSet<String>(Arrays.asList(fsh.list(tr)));
            eq("list", names, new HashSet<String>(Arrays.asList("a.txt", "d", "name with space.txt", "sp ace", "it's.txt", "\u00fc.txt", "e", "l", "dangling", "bin.dat")));
            eq("list of an empty directory", fsh.list(tr + "/e").length, 0);
            eq("list of a missing directory", fsh.list(tr + "/nope"), null);
            eq("list of a file", fsh.list(tr + "/a.txt"), null);
            is("exists", fsh.exists(tr + "/a.txt") && fsh.exists(tr + "/d") && !fsh.exists(tr + "/nope") && fsh.exists(tr + "/dangling"), true);

            final Map<String, Sdm.Entry> all = new LinkedHashMap<String, Sdm.Entry>();
            fsh.walk(tr, new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { all.put(e.path, e); return true; } }, null);
            eq("walk finds everything below the root, not the root", all.size(), 12);
            is("walk: not the root itself", !all.containsKey(tr), true);
            is("walk: nested", all.containsKey(tr + "/d/b.txt") && all.containsKey(tr + "/sp ace/x"), true);
            is("walk: a link is not followed", all.get(tr + "/l").type == Sdm.LINK, true);
            final List<String> order = new ArrayList<String>();
            fsh.walk(tr, new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { order.add(e.path); return true; } }, null);
            is("walk: a directory comes before what is in it", order.indexOf(tr + "/d") < order.indexOf(tr + "/d/b.txt"), true);
            final Set<String> pruned = new HashSet<String>();
            fsh.walk(tr, new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { pruned.add(e.path); return !e.path.equals(tr + "/d"); } }, null);
            is("walk: the sink leaves a directory out", pruned.contains(tr + "/d") && !pruned.contains(tr + "/d/b.txt") && pruned.contains(tr + "/sp ace/x"), true);
            final Set<String> p2 = new HashSet<String>();
            fsh.walk(tr, Arrays.asList(tr + "/d", tr + "/sp ace", tr + "/outside/not-below"), new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { p2.add(e.path); return true; } }, null);
            is("walk: prune list (find -prune)", !p2.contains(tr + "/d") && !p2.contains(tr + "/d/b.txt") && !p2.contains(tr + "/sp ace/x") && p2.contains(tr + "/a.txt"), true);
            mk(R, "glob/we*ird/in", "i"); mk(R, "glob/weXird/in", "i");
            final Set<String> p3 = new HashSet<String>();
            fsh.walk(R.getPath() + "/glob", Arrays.asList(R.getPath() + "/glob/we*ird"), new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { p3.add(e.path); return true; } }, null);
            is("prune: a * in the name is a star, not a pattern", !p3.contains(R.getPath() + "/glob/we*ird/in") && p3.contains(R.getPath() + "/glob/weXird/in"), true);
            final int[] cnt = { 0 };
            fsh.walk(R.getPath() + "/nope", new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { cnt[0]++; return true; } }, null);
            eq("walk of a missing root is empty", cnt[0], 0);

            // a file name with a newline cannot forge the stat record of another file
            String fr = R.getPath() + "/frame";
            mk(R, "frame/ok.txt", "ok");
            Files.write(new File(R, "frame/victim.txt").toPath(), "vv".getBytes("UTF-8"));
            String forgedName = "x\n81a4 999 1 10000 " + fr + "/victim.txt";
            mk(R, "frame/" + forgedName, "z");
            final Map<String, Sdm.Entry> fm = new LinkedHashMap<String, Sdm.Entry>();
            fsh.walk(fr, new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { fm.put(e.path, e); return true; } }, null);
            is("walk: the real files are listed with their real sizes", fm.containsKey(fr + "/ok.txt") && fm.get(fr + "/ok.txt").size == 2 && fm.containsKey(fr + "/victim.txt") && fm.get(fr + "/victim.txt").size == 2, true);
            is("walk: nothing is listed with the forged size or under the forged name", fm.size() == 2, true);
            Map<String, Sdm.Entry> frA = fsh.statAll(Arrays.asList(fr + "/victim.txt", fr + "/" + forgedName), null);
            is("statAll: the victim keeps its real size, the file with the odd name gives nothing", frA.size() == 1 && frA.get(fr + "/victim.txt").size == 2, true);
            is("stat: the file with the odd name gives nothing", fsh.stat(fr + "/" + forgedName) == null, true);

            // ... and neither can a name that holds the shared end marker (it used to end the output early and leave "victim2" believed)
            String fr2 = R.getPath() + "/frame2";
            Files.createDirectories(new File(R, "frame2").toPath());
            Files.write(new File(R, "frame2/victim2").toPath(), "vv".getBytes("UTF-8"));
            String endName = "victim2\n__SDM_END__";
            Files.write(new File(R, "frame2/" + endName).toPath(), "zzzzzz".getBytes("UTF-8"));
            Files.write(new File(R, "frame2/zlast.txt").toPath(), "l".getBytes("UTF-8"));
            final Map<String, Sdm.Entry> fm2 = new LinkedHashMap<String, Sdm.Entry>();
            boolean cutWalk = false;
            try { fsh.walk(fr2, new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { fm2.put(e.path, e); return true; } }, null); } catch (IOException ex) { cutWalk = true; }
            is("walk: a name holding the end marker does not end the walk", !cutWalk, true);
            is("walk: 'victim2' keeps its real size, the odd name is not believed, and the files after it are still listed",
                fm2.containsKey(fr2 + "/victim2") && fm2.get(fr2 + "/victim2").size == 2 && fm2.containsKey(fr2 + "/zlast.txt") && fm2.size() == 2, true);
            Map<String, Sdm.Entry> frB = fsh.statAll(Arrays.asList(fr2 + "/victim2", fr2 + "/" + endName), null);
            is("statAll: same, the real file's size is kept", frB.size() == 1 && frB.get(fr2 + "/victim2").size == 2, true);
            is("stat: the file with the end marker in its name gives nothing", fsh.stat(fr2 + "/" + endName) == null, true);

            eq("sha256", fsh.sha256(tr + "/bin.dat", null), sha(bin));
            eq("sha256 of a small file", fsh.sha256(tr + "/a.txt", null), sha("hello".getBytes("UTF-8")));
            threw = false;
            try { fsh.sha256(tr + "/nope", null); } catch (IOException e) { threw = true; }
            is("sha256 of a missing file throws", threw, true);
            byte[] h = fsh.head(tr + "/bin.dat", 100);
            is("head: exact bytes, including 0x00 and 0xff", h != null && h.length == 100 && Arrays.equals(h, Arrays.copyOf(bin, 100)), true);
            eq("head: more than the file has", fsh.head(tr + "/a.txt", 1000).length, 5);
            eq("head: a missing file", fsh.head(tr + "/nope", 10), null);
            eq("head: a directory", fsh.head(tr + "/d", 10), null);

            // output that is cut short is an error, not a short list
            Sdm.Shell cut = new Sdm.Shell() {
                @Override public int uid() { return 2000; }
                @Override public String run(String s, int t) { return ""; }
                @Override public void stream(String s, int t, Sdm.LineSink k, Sdm.Cancel c) { k.line("81a4 1 1 1 /x"); }
            };
            threw = false;
            try { new SdmFsShell(cut).walk("/x", new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { return true; } }, null); } catch (IOException e) { threw = true; }
            is("walk without the end marker throws", threw, true);
            threw = false;
            try { new SdmFsShell(cut).list("/x"); } catch (IOException e) { threw = true; }
            is("list without the end marker throws", threw, true);

            // cancel in the middle of a big tree
            for (int i = 0; i < 3000; i++) mk(R, "big/d" + (i % 30) + "/f" + i, "x");
            final int[] seenBig = { 0 };
            final boolean[] cancelBig = { false };
            long t1 = System.currentTimeMillis();
            fsh.walk(R.getPath() + "/big", new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { if (++seenBig[0] == 100) cancelBig[0] = true; return true; } }, new Sdm.Cancel() { @Override public boolean cancelled() { return cancelBig[0]; } });
            is("cancelled walk stops early and without an error (" + seenBig[0] + " entries)", seenBig[0] >= 100 && seenBig[0] <= 3030, true);
            final int[] fullBig = { 0 };
            fsh.walk(R.getPath() + "/big", new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { fullBig[0]++; return true; } }, null);
            eq("the whole big tree", fullBig[0], 3030);

            // deletes
            is("delete a file", fsh.delete(tr + "/a.txt") && !new File(tr + "/a.txt").exists(), true);
            is("the link to it is still a link", Files.isSymbolicLink(Paths.get(tr + "/l")), true);
            is("delete a link removes the link, not the target", fsh.delete(tr + "/l") && !Files.isSymbolicLink(Paths.get(tr + "/l")), true);
            is("delete a directory tree", fsh.delete(tr + "/d") && !new File(tr + "/d").exists(), true);
            is("delete something that is not there counts as deleted", fsh.delete(tr + "/nope"), true);
            is("delete: a quote in the name", fsh.delete(tr + "/it's.txt") && !new File(tr + "/it's.txt").exists(), true);
            is("delete refuses '/'", !fsh.delete("/"), true);
            is("delete refuses a relative path", !fsh.delete("relative"), true);
            is("delete refuses a path with ..", !fsh.delete(tr + "/sp ace/../name with space.txt") && new File(tr + "/name with space.txt").exists(), true);
            is("delete refuses a newline", !fsh.delete(tr + "/x\ny"), true);
            // a name that looks like a shell command is just a name
            File evil = mk(R, "evil/$(touch pwned); `touch pwned2`.txt", "e");
            mk(R, "evil/semi;colon", "s");
            Set<String> g2 = fsh.deleteAll(Arrays.asList(evil.getPath(), R.getPath() + "/evil/semi;colon"), null);
            is("deleteAll: shell metacharacters in names are only names", g2.size() == 2 && !evil.exists() && !new File(R, "pwned").exists() && !new File(R, "pwned2").exists() && !new File("pwned").exists(), true);
            // batching: 400 paths, some missing
            List<String> many = new ArrayList<String>();
            for (int i = 0; i < 400; i++) { if (i % 4 != 0) mk(R, "many/f" + i, "m"); many.add(R.getPath() + "/many/f" + i); }
            Set<String> g3 = fsh.deleteAll(many, null);
            eq("deleteAll over several batches: every path is gone", g3.size(), 400);
            eq("and the folder is empty", new File(R, "many").list().length, 0);
            mk(R, "c/1", "1"); mk(R, "c/2", "2");
            final boolean[] cc = { true };
            Set<String> g4 = fsh.deleteAll(Arrays.asList(R.getPath() + "/c/1", R.getPath() + "/c/2"), new Sdm.Cancel() { @Override public boolean cancelled() { return cc[0]; } });
            eq("deleteAll cancelled before it started deletes nothing", g4.size() + "/" + new File(R, "c").list().length, "0/2");
            Set<String> g5 = fsh.deleteAll(Arrays.asList("/", "relative", R.getPath() + "/c/../c/1"), null);
            eq("deleteAll refuses the rails", g5.size(), 0);
            // output cut in a delete batch: what was not answered is verified
            final String gonePath = R.getPath() + "/v1";
            Sdm.Shell flaky = new Sdm.Shell() {
                @Override public int uid() { return 0; }
                @Override public String run(String s, int t) { return ""; }
                @Override public void stream(String s, int t, Sdm.LineSink k, Sdm.Cancel c) { if (s.startsWith("for p in") && s.contains("rm -rf")) k.line("D:" + gonePath); else if (s.contains("echo \"G:$p\"")) k.line("G:" + R.getPath() + "/v2"); k.line(SdmFsShell.END); }
            };
            Set<String> g6 = new SdmFsShell(flaky).deleteAll(Arrays.asList(gonePath, R.getPath() + "/v2", R.getPath() + "/v3"), null);
            eq("a path without an answer is checked again", new java.util.TreeSet<String>(g6).toString(), "[" + gonePath + ", " + R.getPath() + "/v2]");
        }

        System.out.println((fails == 0 ? "PASS" : "FAIL") + " SdmAreasTest: " + n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
