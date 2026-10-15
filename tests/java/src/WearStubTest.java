package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bloatware.bingblop.adbstub.Lease;

// The ADB Stub for Wear OS watches (Connected Devices): the status command run through a real sh against a fake pm / dumpsys / pidof, the reading
// of its answer, the start, renew and stop commands (quoting of the phone's name, the force-stop that releases the watch's locks), the stub's lease
// (wearstub Lease: it lets go of the watch by itself 5 minutes after the phone's last renewal), and that WearStub agrees with
// wearstub/AndroidManifest.xml and the stub's own sources (package, versionCode, the Stop and Renew actions, the receiver's permission).
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

    /** statusCmd() through sh with stand-ins: installed (with versionCode) or not, its service running or not, its process alive or not. */
    static String runStatus(File bin, boolean installed, int ver, boolean running) throws Exception {
        return runStatus(bin, installed, ver, running, running);
    }

    static String runStatus(File bin, boolean installed, int ver, boolean running, boolean process) throws Exception {
        exe(new File(bin, "pm"), installed ? "[ \"$1\" = path ] && [ \"$2\" = " + WearStub.PKG + " ] && echo package:/data/app/~~x==/" + WearStub.PKG + "-1/base.apk\n" : "exit 1\n");
        String services = "echo 'ACTIVITY MANAGER SERVICES (dumpsys activity services)'\n" + (running
                ? "echo '  User 0 active services:'\necho '  * ServiceRecord{9c3b2a u0 " + WearStub.PKG + "/.StubService}'\necho '    intent={cmp=" + WearStub.PKG + "/.StubService}'\n"
                : "echo '  (nothing)'\n");
        exe(new File(bin, "dumpsys"), "if [ \"$1\" = activity ] && [ \"$2\" = services ] && [ \"$3\" = " + WearStub.PKG + " ]; then\n" + services + "exit 0\nfi\n"
                + "echo 'Packages:'\necho '  Package [" + WearStub.PKG + "] (abc):'\necho '    versionCode=" + ver + " minSdk=25 targetSdk=34'\necho '    versionCode=99 minSdk=1 targetSdk=1'\n");
        exe(new File(bin, "pidof"), process ? "echo 4242\n" : "exit 1\n");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", WearStub.statusCmd()).redirectErrorStream(true);
        pb.environment().put("PATH", bin.getPath() + File.pathSeparator + System.getenv("PATH"));
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor(20, TimeUnit.SECONDS);
        return new String(out, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        // reading the answer
        WearStub.Status s = WearStub.parseStatus("inst:\nsvc:0\n");
        check("not installed", !s.installed && s.version == 0 && !s.running);
        s = WearStub.parseStatus("inst:package:/data/app/x/base.apk\n    versionCode=3 minSdk=25 targetSdk=34\nsvc:1\n");
        check("installed, version 3, running", s.installed && s.version == 3 && s.running);
        s = WearStub.parseStatus("inst:package:/data/app/x/base.apk\n    versionCode=1 minSdk=25\nsvc:0\n");
        check("installed, not running", s.installed && s.version == 1 && !s.running);
        s = WearStub.parseStatus("inst:\nversionCode=7\nsvc:1\n");
        check("a versionCode or a service without an installed package counts for nothing", !s.installed && s.version == 0 && !s.running);
        s = WearStub.parseStatus("inst:package:/data/app/x/base.apk\n    versionCode=2 minSdk=25\npid:1234\nsvc:0\n");
        check("a live process without the service is not running", s.installed && !s.running);
        s = WearStub.parseStatus("Error: device offline");
        check("an adb error: nothing", !s.installed && !s.running);
        check("null: nothing", !WearStub.parseStatus(null).installed);
        check("adb's complaints are failures, in any case", WearStub.adbFailed("error: device offline") && WearStub.adbFailed("Error: timed out") && WearStub.adbFailed("adb: device '192.168.1.30:5555' not found")
                && WearStub.adbFailed("  error: closed\n") && WearStub.adbFailed("adb.exe: no devices/emulators found") && WearStub.adbFailed(null));
        check("the watch's answer is not", !WearStub.adbFailed("inst:\nsvc:0\n") && !WearStub.adbFailed("inst:package:/data/app/x/base.apk\n    versionCode=2\nsvc:1\n"));

        // the real shell
        File work = Files.createTempDirectory("wearstub").toFile();
        try {
            File bin = new File(work, "bin");
            bin.mkdirs();
            s = WearStub.parseStatus(runStatus(bin, true, 2, true));
            check("sh: installed, version from the first versionCode line, running", s.installed && s.version == 2 && s.running);
            s = WearStub.parseStatus(runStatus(bin, true, 1, false));
            check("sh: installed, not running", s.installed && s.version == 1 && !s.running);
            s = WearStub.parseStatus(runStatus(bin, true, 2, false, true));
            check("sh: the process is alive but the service was stopped (Stop on the watch): not running", s.installed && s.version == 2 && !s.running);
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
        String renew = WearStub.renewCmd();
        check("renew is a broadcast to the stub's receiver", renew.equals("am broadcast -a " + WearStub.PKG + ".RENEW -n " + WearStub.PKG + "/.StopReceiver"));
        check("am broadcast's answer: renewed", WearStub.renewed("Broadcasting: Intent { act=" + WearStub.PKG + ".RENEW flg=0x400000 cmp=" + WearStub.PKG + "/.StopReceiver }\nBroadcast completed: result=0"));
        check("an adb error: not renewed", !WearStub.renewed("error: device offline") && !WearStub.renewed("Error: timed out") && !WearStub.renewed(null));

        // the lease: without word from the phone the stub lets go by itself
        long t0 = 1_000_000L;
        Lease lease = new Lease(t0);
        check("the lease is 5 minutes", Lease.MS == 5 * 60 * 1000L);
        check("a new lease runs its full length", lease.left(t0) == Lease.MS);
        check("a minute later 4 are left", lease.left(t0 + 60_000L) == 4 * 60_000L);
        check("no renewal for 5 minutes: over (let go)", lease.left(t0 + Lease.MS) == 0 && lease.left(t0 + Lease.MS + 1) == 0);
        check("an hour without renewal: still over, never negative", lease.left(t0 + 3_600_000L) == 0);
        lease.renew(t0 + 4 * 60_000L);
        check("renewed after 4 minutes: 5 from then, not over at the first end", lease.left(t0 + Lease.MS) == 4 * 60_000L);
        check("... and over 5 minutes after the renewal", lease.left(t0 + 9 * 60_000L) == 0);
        Lease kept = new Lease(t0);
        for (long t = t0; t <= t0 + 24 * 3_600_000L; t += 60_000L) kept.renew(t);
        check("renewed every minute for a day: still held", kept.left(t0 + 24 * 3_600_000L + 30_000L) > 0);
        lease = new Lease(t0);
        lease.renew(t0 - 10_000L);
        check("a renewal with an earlier time does not shorten the lease", lease.left(t0) == Lease.MS);
        check("a time before the lease began gives at most the full length", new Lease(t0).left(t0 - 10_000L) == Lease.MS);

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
            check("the Renew action is the stub's (same receiver)", m.contains("<action android:name=\"" + WearStub.RENEW_ACTION + "\""));
            check("the stub's foreground service has a type (Android 14)", m.contains("android:foregroundServiceType=\"connectedDevice\"") && m.contains("FOREGROUND_SERVICE_CONNECTED_DEVICE"));
            String svc = read(new File(repo, "wearstub/src/com/bloatware/bingblop/adbstub/StubService.java"));
            check("StubService.ACTION_STOP is the same action", svc.contains("ACTION_STOP = \"" + WearStub.STOP_ACTION + "\""));
            check("StubService releases the screen, the Wi-Fi locks and the Wi-Fi request", svc.contains("screen.release()") && svc.contains("wifiLock.release()") && svc.contains("unregisterNetworkCallback"));
            check("StubService.ACTION_RENEW is the same action", svc.contains("ACTION_RENEW = \"" + WearStub.RENEW_ACTION + "\""));
            check("StubService starts the lease when it takes hold, and lets go when it is over", svc.contains("lease = new Lease(") && svc.contains("handler.postDelayed(leaseCheck, Lease.MS)") && Pattern.compile("left <= 0\\) stopNow\\(").matcher(svc).find());
            check("the lease check is taken back on release", svc.contains("handler.removeCallbacks(leaseCheck)"));
            String ma = read(new File(repo, "src/com/bloatware/bingblop/MainActivity.java"));
            check("the stop before Disconnect has a short wait", WearStub.QUICK_STOP_MS > 0 && WearStub.QUICK_STOP_MS <= 8000 && ma.contains("quick ? WearStub.QUICK_STOP_MS : 20000"));
            check("... and reads no status after it", ma.contains("final boolean quick = a.equals(\"stop\") && \"quick\".equals(phone);") && Pattern.compile("if \\(!quick\\) \\{\\s*String st = cdExec\\(t, cdStubArgs\\(serial, \"shell\", WearStub.statusCmd\\(\\)\\)").matcher(ma).find());
            check("cdStub reads no stub state from a status adb could not get", ma.contains("if (WearStub.adbFailed(st)) {") && !ma.contains("st.startsWith(\"Error\")"));
            String rcv = read(new File(repo, "wearstub/src/com/bloatware/bingblop/adbstub/StopReceiver.java"));
            check("the receiver renews on Renew", rcv.contains("ACTION_RENEW.equals(intent.getAction())) StubService.renewNow()"));
        }

        System.out.println(fails == 0 ? "PASS WearStubTest: " + n + " checks" : "FAILED WearStubTest: " + fails + " of " + n);
        if (fails != 0) System.exit(1);
    }
}
