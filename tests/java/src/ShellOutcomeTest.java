package com.bloatware.bingblop;

/** The verdict of a privileged command run with an exit-status marker: the status when the marker is there, a failure when only an
 *  Error: line or a timeout note came back, the old "taken as fine" reading for any other output without the marker. */
public class ShellOutcomeTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static final String M = "__RC123__";
  public static void main(String[] args) {
    ShellOutcome o = ShellOutcome.parse("Success\n" + M + ":0\n", M);
    check("status 0 is ok and the text before the marker is kept without the trailing newline", o.ok && o.text.equals("Success"));
    o = ShellOutcome.parse("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n" + M + ":1\n", M);
    check("a non-zero status is a failure with its text", !o.ok && o.text.equals("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]"));
    o = ShellOutcome.parse(M + ":0", M);
    check("no output and status 0 is ok with empty text", o.ok && o.text.isEmpty());
    o = ShellOutcome.parse("a\nb\n" + M + ":127\n", M);
    check("status 127 (command not found) is a failure", !o.ok && o.text.equals("a\nb"));
    o = ShellOutcome.parse("x\n" + M + ":abc\n", M);
    check("an unreadable status keeps the old reading (ok)", o.ok);
    o = ShellOutcome.parse("Error: " + "Shizuku is not authorized for this app", M);
    check("Shizuku not authorized (no marker) is a failure, text kept", !o.ok && o.text.startsWith("Error: Shizuku"));
    o = ShellOutcome.parse("  Error: Wireless Debugging is not configured. Connect it in Working Modes.", M);
    check("an Error: line after leading spaces is a failure", !o.ok);
    o = ShellOutcome.parse("Error: Cannot run program \"su\": error=2, No such file or directory", M);
    check("su that cannot start is a failure", !o.ok);
    o = ShellOutcome.parse("error: device offline", M);
    check("adb's lowercase 'error: device offline' (no marker) is a failure", !o.ok && o.text.equals("error: device offline"));
    o = ShellOutcome.parse("error: device unauthorized.\nThis adbd's $ADB_VENDOR_KEYS is not set\n", M);
    check("adb's 'error: device unauthorized' is a failure", !o.ok);
    o = ShellOutcome.parse("error: no devices/emulators found", M);
    check("adb's 'error: no devices/emulators found' is a failure", !o.ok);
    o = ShellOutcome.parse("adb: error: failed to get feature set: device offline", M);
    check("the newer 'adb: error: ...' form is a failure", !o.ok);
    o = ShellOutcome.parse("adb: device offline", M);
    check("'adb: device offline' is a failure", !o.ok);
    o = ShellOutcome.parse("ERROR: Something", M);
    check("the prefix is matched without regard to case", !o.ok);
    o = ShellOutcome.parse("errors were found in nothing", M);
    check("a word that merely starts with 'error' (no colon) is not a failure", o.ok);
    o = ShellOutcome.parse("Warning: x\nerror: device offline", M);
    check("only the start of the output counts (a later error line alone does not fail a markerless output)", o.ok);
    o = ShellOutcome.parse("partial output\n[Process timed out after 8000ms]", M);
    check("a timeout note (no marker) is a failure, partial output kept", !o.ok && o.text.contains("partial output"));
    o = ShellOutcome.parse("", M);
    check("empty output without the marker keeps the old reading (ok)", o.ok && o.text.isEmpty());
    o = ShellOutcome.parse(null, M);
    check("null output is treated as empty (ok, empty text)", o.ok && o.text.isEmpty());
    o = ShellOutcome.parse("Performing Streamed Install\nSuccess", M);
    check("ordinary output without the marker keeps the old reading (ok)", o.ok && o.text.startsWith("Performing"));
    o = ShellOutcome.parse("see the Error: in the text\n" + M + ":0\n", M);
    check("with the marker, only the status counts: an 'Error:' inside the text does not fail a status-0 command", o.ok);
    o = ShellOutcome.parse("Error: x\n[Process timed out after 1ms]\n" + M + ":3\n", M);
    check("with the marker, a non-zero status fails regardless of the text", !o.ok);
    o = ShellOutcome.parse("a " + M + ":0 b\n" + M + ":1\n", M);
    check("the last marker wins", !o.ok);
    System.out.println(fails == 0 ? "PASS ShellOutcomeTest: " + n + " checks" : "FAILED ShellOutcomeTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
