package com.bloatware.bingblop;

import java.util.Arrays;
import java.util.List;

/** The adb arguments the Connected Devices tab accepts: everything a person uses, none that moves or stops the adb this app runs on. */
public class AdbArgsTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static List<String> l(String... a) { return Arrays.asList(a); }

  public static void main(String[] x) {
    // what the tab and its terminal send
    String[][] fine = {
      {"devices", "-l"}, {"connect", "192.168.1.5:5555"}, {"disconnect", "192.168.1.5:5555"}, {"pair", "192.168.1.5:37000", "123456"}, {"mdns", "services"},
      {"reconnect", "offline"}, {"forward", "tcp:5599", "localabstract:/adb-hub"}, {"reboot", "recovery"}, {"shell", "pm", "list", "packages"},
      {"shell", "echo", "kill-server"}, {"pull", "/sdcard/a.txt", "/tmp/a.txt"}, {"push", "a.apk", "/data/local/tmp/"}, {"install", "-r", "a.apk"},
      {"logcat", "-d"}, {"-d", "shell", "id"}, {"-s", "shell", "devices"}, {"-s", "SER123", "-d", "shell", "id"}, {"-t", "2", "shell", "kill-server"}, {"--version"},
      {"wait-for-device"}, {"wait-for-device", "shell", "echo", "kill-server"}, {"wait-for-usb-device", "install", "-r", "a.apk"}, {"-s", "x", "wait-for-device", "shell", "id"}, {"-e", "shell", "id"}, {"version"}, {"keys"},
      {"shell", "raw"}, {"shell", "echo", "raw", "host:kill"},
    };
    for (String[] f : fine) is("allowed: adb " + String.join(" ", f), AdbArgs.check(l(f)) == null);

    // what would move or stop the server the app runs on
    String[][] bad = {
      {"kill-server"}, {"start-server"}, {"server"}, {"nodaemon", "server"}, {"fork-server", "server"}, {"-d", "kill-server"}, {"-d", "-e", "kill-server"},
      {"-s", "shell", "kill-server"}, {"-t", "1", "kill-server"}, {"--one-device", "x", "start-server"}, {"-d", "-P", "5037", "devices"}, {"-d", "-e", "-Hhost", "devices"},
      {"-d", "-L", "tcp:5037", "devices"}, {"-d", "-a", "devices"}, {"-s", "x", "-P5037", "devices"}, {"-q", "devices"}, {"-s", "x"}, {"-d"}, {"-s", "x", "-d", "server"},
      {"wait-for-device", "kill-server"}, {"wait-for-usb-device", "start-server"}, {"wait-for-device", "wait-for-any-device", "server"}, {"-d", "wait-for-device", "nodaemon", "server"},
      {"-s", "x", "wait-for-local-device", "reconnect-server"},
      {"raw", "host:kill"}, {"raw", "host:features"}, {"raw"}, {"-d", "raw", "host:kill"}, {"-s", "x", "raw", "host:kill"}, {"wait-for-device", "raw", "host:kill"},
      {"-P", "5037", "devices"}, {"-P5037", "devices"}, {"-H", "example.com", "devices"}, {"-Hexample.com", "devices"}, {"-L", "tcp:5037", "devices"}, {"-a", "nodaemon", "server"}, {"-a", "start-server"},
    };
    for (String[] b : bad) is("refused: adb " + String.join(" ", b), AdbArgs.check(l(b)) != null);

    is("no arguments is refused", AdbArgs.check(l()) != null && AdbArgs.check(null) != null);
    is("a NUL in an argument is refused", AdbArgs.check(l("shell", "a\u0000b")) != null);
    String[] many = new String[81]; Arrays.fill(many, "devices");
    is("more than 80 arguments are refused", AdbArgs.check(l(many)) != null);
    is("a subcommand word far into a shell command is only text", AdbArgs.check(l("shell", "ls", "/sdcard", "-l", "kill-server")) == null);

    // #190: the arguments that may name a file on this phone, so the bridge can refuse the app's own private files
    String key = "/data/user/0/com.bloatware.bingblop/files/adb_home/.android/adbkey";
    is("push: the local file is among them", AdbArgs.localFileArgs(l("push", key, "/sdcard/")).contains(key));
    is("push after -s and wait-for-device too", AdbArgs.localFileArgs(l("-s", "x", "wait-for-device", "push", "--sync", key, "/sdcard/")).contains(key));
    is("pull: the local destination is among them", AdbArgs.localFileArgs(l("pull", "/sdcard/a", key)).contains(key));
    is("install / install-multiple / sideload / backup -f / restore", AdbArgs.localFileArgs(l("install", "-r", key)).contains(key) && AdbArgs.localFileArgs(l("install-multiple", "a.apk", key)).contains(key)
        && AdbArgs.localFileArgs(l("sideload", key)).contains(key) && AdbArgs.localFileArgs(l("backup", "-f", key, "-all")).contains(key) && AdbArgs.localFileArgs(l("restore", key)).contains(key));
    is("an --option=value names a file too", AdbArgs.localFileArgs(l("install", "--apex=" + key)).contains(key));
    is("a relative name is read from the root, where adb runs", AdbArgs.localFileArgs(l("push", "data/user/0/x", "/sdcard/")).contains("/data/user/0/x"));
    is("shell, logcat and others name no local file", AdbArgs.localFileArgs(l("shell", "cat", key)).isEmpty() && AdbArgs.localFileArgs(l("logcat", "-f", key)).isEmpty() && AdbArgs.localFileArgs(l("-s", "push", "shell", key)).isEmpty());
    // ... and the bridge refuses them (a source check: isPrivateData needs Android)
    try {
      java.io.File src = null;
      for (java.io.File d = new java.io.File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { java.io.File f = new java.io.File(d, "src/com/bloatware/bingblop/MainActivity.java"); if (f.isFile()) src = f; }
      String m = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
      int a = m.indexOf("public void cdAdb("), b = m.indexOf("cdExecutor.submit", a);
      is("cdAdb checks every local file argument with isPrivateData before it runs", a > 0 && b > a && m.substring(a, b).contains("AdbArgs.localFileArgs(given)") && m.substring(a, b).contains("isPrivateData(new File(local))"));
      a = m.indexOf("public void cdInstall("); b = m.indexOf("cdExecutor.submit", a);
      is("cdInstall checks every file it would send with isPrivateData before it runs", a > 0 && b > a && m.substring(a, b).contains("DeviceLink.localFiles(") && m.substring(a, b).contains("isPrivateData(new File(f))"));
    } catch (Exception e) { is("MainActivity.java was read: " + e, false); }

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
