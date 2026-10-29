package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** The manifest of an app on a connected device (RemoteManifest): straight from the APK by unzip -p when the device has it, else the APK pulled here. */
public class RemoteManifestTest {
    static int n, fails;
    static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

    static final byte[] AXML = { 0x03, 0x00, 0x08, 0x00, 0x10, 0x00, 0x00, 0x00, 1, 2, 3, 4, 5, 6, 7, 8 };

    static class Fake implements RemoteManifest.Adb {
        final List<List<String>> calls = new ArrayList<List<String>>();
        byte[] execOut;
        boolean pullMakesFile = true;
        String pullSays = "/data/app/x/base.apk: 1 file pulled, 0 skipped. 30.1 MB/s (1024 bytes in 0.001s)";
        public byte[] bytes(List<String> args, int timeoutMs, int max) { calls.add(args); return execOut; }
        public String run(List<String> args, int timeoutMs) {
            calls.add(args);
            if (pullMakesFile && "pull".equals(args.get(2))) {
                try { FileOutputStream o = new FileOutputStream(args.get(4)); o.write("PK apk".getBytes(StandardCharsets.UTF_8)); o.close(); } catch (Exception e) { throw new RuntimeException(e); }
            }
            return pullSays;
        }
    }

    static class Dec implements RemoteManifest.Decoder {
        String how = "";
        boolean fileThere;
        public String fromBytes(byte[] data) { how = "bytes:" + data.length; return "<manifest package=\"com.x\" />"; }
        public String fromApk(String path) { how = "apk:" + new File(path).getName(); fileThere = new File(path).isFile(); return "<manifest from=\"apk\" />"; }
    }

    public static void main(String[] a) throws Exception {
        File cache = Files.createTempDirectory("rm").toFile();
        final String S = "192.168.1.30:5555", P = "/data/app/~~a1B2c3==/com.example.app-d4E5f6==/base.apk";

        // paths pm path gives, and ones that are not
        check("an app's APK under /data/app", RemoteManifest.validApkPath(P));
        check("a system APK", RemoteManifest.validApkPath("/system/priv-app/Settings/Settings.apk"));
        check("a split", RemoteManifest.validApkPath("/data/app/com.x-1/split_config.arm64_v8a.apk"));
        for (String bad : new String[] { null, "", "base.apk", "/data/app/x/base.apk'; reboot; '", "/data/app/x y/base.apk", "/data/app/../x/base.apk", "/data//x/base.apk", "/data/app/x/base.zip", "/data/app/$(id)/base.apk", "/data/app/x/base.apk\n" })
            check("refused: " + bad, !RemoteManifest.validApkPath(bad));

        check("binary XML is known by its header", RemoteManifest.isBinaryXml(AXML));
        check("text is not binary XML", !RemoteManifest.isBinaryXml("/system/bin/sh: unzip: inaccessible or not found\n".getBytes(StandardCharsets.UTF_8)));
        check("nothing is not binary XML", !RemoteManifest.isBinaryXml(null) && !RemoteManifest.isBinaryXml(new byte[] { 3, 0, 8 }));

        // 1. the device has unzip: the manifest alone comes over, nothing is pulled
        Fake f = new Fake(); f.execOut = AXML; Dec d = new Dec();
        String out = RemoteManifest.read(f, S, P, cache, d);
        check("1. unzip on the device: decoded from the bytes", out.equals("<manifest package=\"com.x\" />") && d.how.equals("bytes:16"));
        check("   one adb call, exec-out unzip -p of that APK in single quotes", f.calls.size() == 1 && f.calls.get(0).toString().equals("[-s, " + S + ", exec-out, unzip -p '" + P + "' AndroidManifest.xml]"));

        // 2. no unzip (Android 8): the APK is pulled into the cache, read there and deleted
        f = new Fake(); f.execOut = "/system/bin/sh: unzip: not found\n".getBytes(StandardCharsets.UTF_8); d = new Dec();
        out = RemoteManifest.read(f, S, P, cache, d);
        File pulled = f.calls.size() == 2 ? new File(f.calls.get(1).get(4)) : null;
        check("2. no unzip: read from the pulled APK", out.equals("<manifest from=\"apk\" />") && pulled != null && d.how.equals("apk:" + pulled.getName()) && d.fileThere);
        check("   adb pull of that APK into the cache", pulled != null && f.calls.get(1).equals(java.util.Arrays.asList("-s", S, "pull", P, pulled.getPath())) && pulled.getParentFile().equals(cache) && pulled.getName().startsWith("cd-manifest-") && pulled.getName().endsWith(".apk"));
        check("   the pulled copy is gone afterwards", pulled != null && !pulled.exists());

        // 3. exec-out failed outright (null): the pull is tried too
        f = new Fake(); f.execOut = null; d = new Dec();
        out = RemoteManifest.read(f, S, P, cache, d);
        check("3. exec-out gave nothing: the APK is pulled", out.startsWith("<manifest from") && f.calls.size() == 2);

        // 4. the pull fails too: adb's own words, without its prefix, and nothing decoded
        f = new Fake(); f.execOut = null; f.pullMakesFile = false; f.pullSays = "adb: error: failed to stat remote object '" + P + "': No such file or directory"; d = new Dec();
        out = RemoteManifest.read(f, S, P, cache, d);
        check("4. the pull failed: its reason", out.equals("Error: failed to stat remote object '" + P + "': No such file or directory") && d.how.isEmpty());
        f = new Fake(); f.execOut = null; f.pullMakesFile = false; f.pullSays = ""; d = new Dec();
        check("   and in words when adb said nothing", RemoteManifest.read(f, S, P, cache, d).equals("Error: The APK could not be copied from the device."));

        // 5. a decoder that throws: its message, the copy still deleted
        f = new Fake(); f.execOut = null;
        out = RemoteManifest.read(f, S, P, cache, new RemoteManifest.Decoder() {
            public String fromBytes(byte[] data) throws Exception { throw new IllegalStateException("x"); }
            public String fromApk(String path) throws Exception { throw new IllegalStateException("AndroidManifest.xml not found in " + path); }
        });
        check("5. a decoder failure is an Error line", out.startsWith("Error: AndroidManifest.xml not found in ") && f.calls.size() == 2 && !new File(f.calls.get(1).get(4)).exists());

        // 6. a bad serial or path never reaches adb
        f = new Fake(); f.execOut = AXML;
        check("6. a bad serial is refused", RemoteManifest.read(f, "-s evil", P, cache, new Dec()).startsWith("Error: That device name") && f.calls.isEmpty());
        check("   a bad path is refused", RemoteManifest.read(f, S, "/x/base.apk' && rm -rf / '", cache, new Dec()).startsWith("Error: The device gave an APK path") && f.calls.isEmpty());

        System.out.println(n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
