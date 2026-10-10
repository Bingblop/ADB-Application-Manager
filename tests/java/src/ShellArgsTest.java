package com.bloatware.bingblop;

import java.util.Arrays;
import java.util.List;

/** What the page may pass into a shell command through the bridge: install options, app op names and values. */
public class ShellArgsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static boolean is(String flags, String... want) { List<String> r = ShellArgs.installFlags(flags); return r != null && r.equals(Arrays.asList(want)); }
  static boolean bad(String flags) { return ShellArgs.installFlags(flags) == null; }

  public static void main(String[] args) {
    check("empty and null mean the default -r", is("", "-r") && is(null, "-r") && is("   ", "-r"));
    check("what the Installer page builds at most", is("-r -g -d -t --user all --bypass-low-target-sdk-block --update-ownership -i com.android.vending --originating-uri https://example.org/a?b=1&c=2 --install-reason 4 --package-source 2",
        "-r", "-g", "-d", "-t", "--user", "all", "--bypass-low-target-sdk-block", "--update-ownership", "-i", "com.android.vending", "--originating-uri", "https://example.org/a?b=1&c=2", "--install-reason", "4", "--package-source", "2"));
    check("a user number and 'current'", is("-r --user 10", "-r", "--user", "10") && is("--user current", "--user", "current"));
    check("extra white space is fine", is("  -r   -g  ", "-r", "-g"));
    check("a shell separator in a flag is refused", bad("-r; reboot") && bad("-r && id") && bad("-r | sh") && bad("-r `id`") && bad("-r $(id)") && bad("-r > /sdcard/x"));
    check("a separator inside a value is refused where the value is a name or a number", bad("-i com.x;reboot") && bad("-i 'a'") && bad("--user 0;id") && bad("--install-reason 1;id") && bad("--package-source $X"));
    check("an unknown option is refused", bad("-r --force-uuid x") && bad("--abi arm64-v8a") && bad("-S 100") && bad("-p com.x") && bad("--dont-kill"));
    check("an option without its value is refused", bad("-r --user") && bad("-i") && bad("--originating-uri"));
    check("a value that is not a user is refused", bad("--user bad") && bad("--user -1") && bad("--user ''"));
    check("a package name for -i must be one", bad("-i ../x") && bad("-i 1abc") && bad("-i a/b"));
    check("an originating address must have a scheme and no white space or control characters", bad("--originating-uri example.org/x") && bad("--originating-uri \u0001http://x") && bad("--originating-uri http://xé"));
    check("a very long list is refused", bad(String.join(" ", java.util.Collections.nCopies(41, "-r"))));
    check("a very long address is refused", bad("--originating-uri https://" + String.join("", java.util.Collections.nCopies(2100, "a"))));
    check("join single-quotes every argument", ShellArgs.join(Arrays.asList("-r", "--originating-uri", "https://x/?a=1&b=$(id)")).equals("'-r' '--originating-uri' 'https://x/?a=1&b=$(id)'"));
    check("join escapes a quote", ShellArgs.join(Arrays.asList("a'b")).equals("'a'\\''b'"));
    check("app op names", ShellArgs.isAppOp("RUN_IN_BACKGROUND") && ShellArgs.isAppOp("WIFI_SCAN") && !ShellArgs.isAppOp("run_in_background") && !ShellArgs.isAppOp("A") && !ShellArgs.isAppOp("X; id") && !ShellArgs.isAppOp("") && !ShellArgs.isAppOp(null) && !ShellArgs.isAppOp("A B"));
    check("app op values", ShellArgs.isAppOpMode("allow") && ShellArgs.isAppOpMode("foreground") && ShellArgs.isAppOpMode("default") && !ShellArgs.isAppOpMode("allow; id") && !ShellArgs.isAppOpMode("ALLOW") && !ShellArgs.isAppOpMode("") && !ShellArgs.isAppOpMode(null));
    check("package and permission names (the checks the bridge uses)", BackupScripts.isPackageName("com.example.app") && !BackupScripts.isPackageName("com.x; reboot") && !BackupScripts.isPackageName("a b") && !BackupScripts.isPackageName("")
        && BackupScripts.isPermission("android.permission.CAMERA") && !BackupScripts.isPermission("android.permission.CAMERA; id") && !BackupScripts.isPermission("a b") && !BackupScripts.isPermission(""));
    check("compile modes ART knows", ShellArgs.isCompileMode("speed") && ShellArgs.isCompileMode("speed-profile") && ShellArgs.isCompileMode("space") && ShellArgs.isCompileMode("everything") && ShellArgs.isCompileMode("verify") && ShellArgs.isCompileMode("quicken") && !ShellArgs.isCompileMode("reset") && !ShellArgs.isCompileMode("") && !ShellArgs.isCompileMode(null) && !ShellArgs.isCompileMode("speed;id") && !ShellArgs.isCompileMode("--reset") && !ShellArgs.isCompileMode("Speed"));
    check("compile command: mode and force", "pm compile -m speed -f com.x.y".equals(ShellArgs.compileCommand("com.x.y", "speed", true)) && "pm compile -m space com.x.y".equals(ShellArgs.compileCommand("com.x.y", "space", false)) && "pm compile -m speed com.x.y".equals(ShellArgs.compileCommand("com.x.y", null, false)) && "pm compile -m speed com.x.y".equals(ShellArgs.compileCommand("com.x.y", "", false)));
    check("compile command: reset uses its own command and ignores force", "pm compile --reset com.x.y".equals(ShellArgs.compileCommand("com.x.y", "reset", true)) && "pm compile --reset com.x.y".equals(ShellArgs.compileCommand("com.x.y", "reset", false)));
    check("compile command: a bad mode or package gives nothing", ShellArgs.compileCommand("com.x.y", "fast", false) == null && ShellArgs.compileCommand("com.x.y", "speed;reboot", false) == null && ShellArgs.compileCommand("com.x.y; reboot", "speed", false) == null && ShellArgs.compileCommand(null, "speed", false) == null && ShellArgs.compileCommand("", "speed", false) == null);
    System.out.println(fails == 0 ? "PASS ShellArgsTest: " + n + " checks" : "FAILED ShellArgsTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
