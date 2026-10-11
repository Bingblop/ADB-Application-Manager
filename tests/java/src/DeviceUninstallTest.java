package com.bloatware.bingblop;

import java.util.*;

// Removing an app on another device (Connected Devices) the way this phone does for itself, against a fake adb: pm uninstall --user 0, and when the device says
// only root can remove a system app, the Binder helper pushed there, run with app_process and taken off again.
public class DeviceUninstallTest {
    static int fails = 0, n = 0;
    static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL: " + what); } }

    /** A fake device: answers by what the command starts with, and keeps every call. */
    static class Fake implements DeviceLink.Adb {
        final List<String> calls = new ArrayList<String>();
        String pm = "Success", push = "/x/uninstall_runner.jar: 1 file pushed, 0 skipped. 3.1 MB/s (2048 bytes in 0.001s)", runner = "RESULT:OK";
        int pmRc = 0, runnerRc = 0;
        public String run(List<String> a, int timeoutMs) {
            String line = String.join(" ", a);
            calls.add(line);
            if (a.get(0).equals("push")) return push;
            String cmd = a.get(1);
            if (cmd.startsWith("pm uninstall")) return pm + "\n__DEVRC__:" + pmRc + "\n";
            if (cmd.startsWith("chmod")) return runner + "\n__DEVRC__:" + runnerRc + "\n";
            if (cmd.startsWith("rm -f")) return "";
            return "Error: unexpected " + line;
        }
        boolean did(String start) { for (String c : calls) if (c.startsWith(start)) return true; return false; }
        int count(String part) { int k = 0; for (String c : calls) if (c.contains(part)) k++; return k; }
    }

    static final String ROOT = "Failure [only root can delete system app for a particular user]";
    static final String HELPER = "/data/user/0/app/files/uninstall_runner_820.jar";

    public static void main(String[] args) {
        // a plain app: pm uninstall is enough, nothing is pushed
        Fake f = new Fake();
        DeviceUninstall.Result r = DeviceUninstall.run("com.example.app", f, HELPER, "abc");
        check("a plain uninstall succeeds", r.ok && r.text.contains("Success"));
        check("it ran pm uninstall --user 0 on the quoted package", f.calls.size() == 1 && f.calls.get(0).equals("shell pm uninstall --user 0 'com.example.app'; echo \"__DEVRC__:$?\""));
        check("nothing was pushed", !f.did("push"));

        // a system app: the device refuses, the helper goes over and removes it
        f = new Fake(); f.pm = ROOT; f.pmRc = 1;
        r = DeviceUninstall.run("com.android.chrome", f, HELPER, "k3j9");
        check("the helper's answer RESULT:OK makes it a success", r.ok && r.text.startsWith("Success") && r.text.contains("Binder"));
        check("the helper was pushed to /data/local/tmp under its own name", f.did("push " + HELPER + " /data/local/tmp/adbam_unin_k3j9.jar"));
        check("it is made read-only, then run with app_process on the runner class for user 0",
                f.count("chmod 444 /data/local/tmp/adbam_unin_k3j9.jar; CLASSPATH=/data/local/tmp/adbam_unin_k3j9.jar app_process /system/bin com.bloatware.bingblop.SystemlessUninstallRunner 'com.android.chrome' 0") == 1);
        check("and taken off the device again", f.did("shell rm -f /data/local/tmp/adbam_unin_k3j9.jar"));
        check("the order: pm, push, run, remove", f.calls.size() == 4 && f.calls.get(0).contains("pm uninstall") && f.calls.get(1).startsWith("push") && f.calls.get(2).contains("app_process") && f.calls.get(3).contains("rm -f"));

        // the helper runs but the device says no
        f = new Fake(); f.pm = ROOT; f.pmRc = 1; f.runner = "RESULT:FAIL:-3";
        r = DeviceUninstall.run("com.android.chrome", f, HELPER, "z");
        check("RESULT:FAIL is a failure with the device's own words and the helper's", !r.ok && r.text.contains("only root can delete system app") && r.text.contains("RESULT:FAIL:-3"));
        check("with the advice for a device (disable it instead), not 'switch to Root mode'", r.text.contains("Disable the app instead") && !r.text.contains("Root mode"));
        check("the helper is removed from the device even then", f.did("shell rm -f /data/local/tmp/adbam_unin_z.jar"));

        f = new Fake(); f.pm = ROOT; f.pmRc = 1; f.runner = "RESULT:ERROR:IPackageManager.deletePackageAsUser not found on this device";
        r = DeviceUninstall.run("com.android.chrome", f, HELPER, "z");
        check("RESULT:ERROR is a failure too", !r.ok && r.text.contains("deletePackageAsUser not found"));

        f = new Fake(); f.pm = ROOT; f.pmRc = 1; f.runner = "";
        r = DeviceUninstall.run("com.android.chrome", f, HELPER, "z");
        check("a helper that printed nothing is a failure, not a success", !r.ok && r.text.contains("only root can delete system app"));

        // the helper cannot be sent
        f = new Fake(); f.pm = ROOT; f.pmRc = 1; f.push = "adb: error: failed to copy '/x' to '/data/local/tmp/a.jar': remote couldn't create file: Read-only file system";
        r = DeviceUninstall.run("com.android.chrome", f, HELPER, "z");
        check("a failed push says so and stops there", !r.ok && r.text.contains("could not be sent") && r.text.contains("Read-only file system") && !f.did("shell chmod"));

        // no helper file on this phone: only the refusal, with advice
        f = new Fake(); f.pm = ROOT; f.pmRc = 1;
        r = DeviceUninstall.run("com.android.chrome", f, null, "z");
        check("without a helper file nothing is pushed", !r.ok && !f.did("push") && r.text.contains("Disable the app instead"));
        r = DeviceUninstall.run("com.android.chrome", f, "", "z");
        check("an empty path is no helper either", !r.ok && !f.did("push"));

        // other refusals have no workaround
        f = new Fake(); f.pm = "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]"; f.pmRc = 1;
        r = DeviceUninstall.run("com.example.app", f, HELPER, "z");
        check("a device policy refusal is shown with its note and nothing is pushed", !r.ok && !f.did("push") && r.text.toLowerCase().contains("device policy") && r.text.contains("Note:"));
        f = new Fake(); f.pm = "Failure [DELETE_FAILED_USER_RESTRICTED]"; f.pmRc = 1;
        r = DeviceUninstall.run("com.example.app", f, HELPER, "z");
        check("a user restriction is explained", !r.ok && !f.did("push") && r.text.toLowerCase().contains("restriction"));
        f = new Fake(); f.pm = "Failure [DELETE_FAILED_INTERNAL_ERROR]"; f.pmRc = 1;
        r = DeviceUninstall.run("com.example.app", f, HELPER, "z");
        check("an unknown failure is shown as it is, without a note", !r.ok && r.text.equals("Failure [DELETE_FAILED_INTERNAL_ERROR]"));

        // the exit status and the words must agree
        f = new Fake(); f.pm = "Success"; f.pmRc = 1;
        check("Success with a failing exit status is not a success", !DeviceUninstall.run("com.example.app", f, HELPER, "z").ok);
        f = new Fake(); f.pm = "Failure [x]"; f.pmRc = 0;
        check("a zero exit status without Success is not a success", !DeviceUninstall.run("com.example.app", f, HELPER, "z").ok);
        f = new Fake() { public String run(List<String> a, int t) { calls.add(String.join(" ", a)); return "Performing Streamed Uninstall\nSuccess"; } };
        check("a shell that gives no exit marker is judged by its words", DeviceUninstall.run("com.example.app", f, HELPER, "z").ok);

        // adb itself failing
        f = new Fake() { public String run(List<String> a, int t) { calls.add(String.join(" ", a)); return "error: device offline"; } };
        r = DeviceUninstall.run("com.example.app", f, HELPER, "z");
        check("a device that is offline is a failure with adb's words", !r.ok && r.text.contains("device offline"));
        f = new Fake() { public String run(List<String> a, int t) { calls.add(String.join(" ", a)); return "Error: stopped"; } };
        check("a stopped command is a failure", !DeviceUninstall.run("com.example.app", f, HELPER, "z").ok);

        // what may go into the device's shell
        check("package names", DeviceUninstall.validPackage("com.android.chrome") && DeviceUninstall.validPackage("a.b") && DeviceUninstall.validPackage("com.x_y.z9"));
        for (String bad : new String[]{null, "", "nodot", "com..x", ".com.x", "com.x'; rm -rf /; '", "com.x y", "com.x\n", "com.x;id", "9com.x", "com.x$(id)", "-r.x"}) {
            check("refused: " + bad, !DeviceUninstall.validPackage(bad));
        }
        f = new Fake();
        r = DeviceUninstall.run("com.x'; reboot; '", f, HELPER, "z");
        check("a bad name runs nothing", !r.ok && f.calls.isEmpty());
        f = new Fake(); f.pm = ROOT; f.pmRc = 1;
        DeviceUninstall.run("com.android.chrome", f, HELPER, "../../etc; id");
        check("the helper's name on the device cannot be bent either", f.did("push " + HELPER + " /data/local/tmp/adbam_unin_etcid.jar"));

        // the device advice
        check("adviceForDevice: root", UninstallHints.adviceForDevice(ROOT).contains("Disable the app instead"));
        check("adviceForDevice: success has none", UninstallHints.adviceForDevice("Success").isEmpty() && UninstallHints.adviceForDevice(null).isEmpty());
        check("adviceForDevice: the others are the same as for this phone", UninstallHints.adviceForDevice("Failure [DELETE_FAILED_USER_RESTRICTED]").equals(UninstallHints.advice("Failure [DELETE_FAILED_USER_RESTRICTED]")));

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
