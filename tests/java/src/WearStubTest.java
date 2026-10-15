package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The ADB Stub for Wear OS watches (Connected Devices): the status command run through a real sh against a fake pm / dumpsys / pidof, the reading
// of its answer, the start and stop commands (quoting of the phone's name, the force-stop that releases the watch's locks), and that WearStub
// agrees with wearstub/AndroidManifest.xml and the stub's own sources (package, versionCode, the Stop action, the receiver's permission).
public class WearStubTest {
    static int fails = 0, n = 0;
    static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL: " + what); } }

    static File repo() {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) if (new File(d, "wearstub/AndroidManifest.xml").isFile()) return d;
        return null;
    }

    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    static void exe(File f, String body) throws Exception {
        Files.write(f.toPath(), ("#!/bin/sh\n" + body).getBytes(StandardCharsets.UTF_8));
        f.setExecutable(true);
    }

    /** statusCmd() through sh with stand-ins: installed (with versionCode) or not, running or not. */
    static String runStatus(File bin, boolean installed, int ver, boolean running) throws Exception {
        exe(new File(bin, "pm"), installed ? "[ \"$1\" = path ] && [ \"$2\" = " + WearStub.PKG + " ] && echo package:/data/app/~~x==/" + WearStub.PKG + "-1/base.apk\n" : "exit 1\n");
        exe(new File(bin, "dumpsys"), "echo 'Packages:'\necho '  Package [" + WearStub.PKG + "] (abc):'\necho '    versionCode=" + ver + " minSdk=25 targetSdk=34'\necho '    versionCode=99 minSdk=1 targetSdk=1'\n");
        exe(new File(bin, "pidof"), running ? "echo 4242\n" : "exit 1\n");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", WearStub.statusCmd()).redirectErrorStream(true);
        pb.environment().put("PATH", bin.getPath() + File.pathSeparator + System.getenv("PATH"));
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor(20, TimeUnit.SECONDS);
        return new String(out, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        // reading the answer
        WearStub.Status s = WearStub.parseStatus("inst:\npid:\n");
        check("not installed", !s.installed && s.version == 0 && !s.running);
        s = WearStub.parseStatus("inst:package:/data/app/x/base.apk\n    versionCode=3 minSdk=25 targetSdk=34\npid:1234\n");
        check("installed, version 3, running", s.installed && s.version == 3 && s.running);
        s = WearStub.parseStatus("inst:package:/data/app/x/base.apk\n    versionCode=1 minSdk=25\npid:\n");
        check("installed, not running", s.installed && s.version == 1 && !s.running);
        s = WearStub.parseStatus("inst:\nversionCode=7\npid:99\n");
        check("a versionCode or a pid without an installed package counts for nothing", !s.installed && s.version == 0 && !s.running);
        s = WearStub.parseStatus("Error: device offline");
        check("an adb error: nothing", !s.installed && !s.running);
        check("null: nothing", !WearStub.parseStatus(null).installed);

        // the real shell
        File work = Files.createTempDirectory("wearstub").toFile();
        try {
            File bin = new File(work, "bin");
            bin.mkdirs();
            s = WearStub.parseStatus(runStatus(bin, true, 2, true));
            check("sh: installed, version from the first versionCode line, running", s.installed && s.version == 2 && s.running);
            s = WearStub.parseStatus(runStatus(bin, true, 1, false));
            check("sh: installed, not running", s.installed && s.version == 1 && !s.running);
            s = WearStub.parseStatus(runStatus(bin, false, 5, true));
            check("sh: not installed (dumpsys is not even asked)", !s.installed && s.version == 0 && !s.running);
        } finally {
            for (File f : new File(work, "bin").listFiles()) f.delete();
            new File(work, "bin").delete();
            work.delete();
        }

        // start and stop
        String start = WearStub.startCmd("Kyle's \"Note20\"\n; reboot");
        check("start opens the stub's screen", start.startsWith("am start -n " + WearStub.PKG + "/.StubActivity --es phone '"));
        check("the phone's name is one quoted word: no line break, quotes escaped", start.endsWith("'Kyle'\\''s \"Note20\" ; reboot'") && !start.contains("\n"));
        String longName = WearStub.quotedPhone(new String(new char[100]).replace('\0', 'a'));
        check("the phone's name is cut at 60 characters", longName.length() == 62);
        check("no name: an empty word", WearStub.quotedPhone(null).equals("''"));
        check("am start's answer: started", WearStub.started("Starting: Intent { cmp=" + WearStub.PKG + "/.StubActivity (has extras) }"));
        check("am start's answer: an error", !WearStub.started("Starting: Intent { cmp=x }\nError type 3\nError: Activity class {x} does not exist."));
        check("no answer: not started", !WearStub.started(null));
        String stop = WearStub.stopCmd();
        check("stop asks the stub first", stop.startsWith("am broadcast -a " + WearStub.PKG + ".STOP -n " + WearStub.PKG + "/.StopReceiver"));
        check("... then ends its process (the watch drops its locks and Wi-Fi request)", stop.contains("am force-stop " + WearStub.PKG + ";"));
        check("... and says it is done", stop.endsWith("echo stopped"));

        // the stub itself agrees
        File repo = repo();
        if (repo == null) {
            System.out.println("SKIP wearstub/ not found: the manifest checks did not run");
        } else {
            String m = read(new File(repo, "wearstub/AndroidManifest.xml"));
            Matcher v = Pattern.compile("android:versionCode=\"(\\d+)\"").matcher(m);
            check("WearStub.VERSION is the stub's versionCode", v.find() && Integer.parseInt(v.group(1)) == WearStub.VERSION);
            check("WearStub.PKG is the stub's package", m.contains("package=\"" + WearStub.PKG + "\""));
            check("the Stop action is the stub's", m.contains("<action android:name=\"" + WearStub.STOP_ACTION + "\""));
            check("only the shell and the system may send Stop (DUMP)", Pattern.compile("android:name=\"\\.StopReceiver\"[^>]*android:permission=\"android.permission.DUMP\"").matcher(m).find());
            check("the stub's foreground service has a type (Android 14)", m.contains("android:foregroundServiceType=\"connectedDevice\"") && m.contains("FOREGROUND_SERVICE_CONNECTED_DEVICE"));
            String svc = read(new File(repo, "wearstub/src/com/bloatware/bingblop/adbstub/StubService.java"));
            check("StubService.ACTION_STOP is the same action", svc.contains("ACTION_STOP = \"" + WearStub.STOP_ACTION + "\""));
            check("StubService releases the screen, the Wi-Fi locks and the Wi-Fi request", svc.contains("screen.release()") && svc.contains("wifiLock.release()") && svc.contains("unregisterNetworkCallback"));
        }

        System.out.println(fails == 0 ? "PASS WearStubTest: " + n + " checks" : "FAILED WearStubTest: " + fails + " of " + n);
        if (fails != 0) System.exit(1);
    }
}
