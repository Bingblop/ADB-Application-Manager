package com.bloatware.bingblop;

/** The verdict of a privileged command run with an exit-status marker: the leading digits after the marker are the status; a status that
 *  is missing, cut off or followed by the runner's timeout note is a failure; so is any output without the marker (an Error: line, a
 *  timeout note, or plain text: the command never reached its end), never an "ok". */
public class ShellOutcomeTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static final String M = "__RC123__";
  static String sh(String cmd) {
    try {
      Process p = new ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start();
      byte[] b = readAll(p.getInputStream());
      p.waitFor();
      return new String(b, "UTF-8");
    } catch (Exception e) {
      return "";
    }
  }
  static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    return o.toByteArray();
  }
  public static void main(String[] args) {
    ShellOutcome o = ShellOutcome.parse("Success\n" + M + ":0\n", M);
    check("status 0 is ok and the text before the marker is kept without the trailing newline", o.ok && o.text.equals("Success"));
    o = ShellOutcome.parse("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n" + M + ":1\n", M);
    check("a non-zero status is a failure with its text", !o.ok && o.text.equals("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]"));
    o = ShellOutcome.parse(M + ":0", M);
    check("no output and status 0 is ok with empty text", o.ok && o.text.isEmpty());
    o = ShellOutcome.parse("a\nb\n" + M + ":127\n", M);
    check("status 127 (command not found) is a failure", !o.ok && o.text.equals("a\nb"));
    o = ShellOutcome.parse(M + ":255\r\n", M);
    check("a status ended by CR LF is read", !o.ok);
    // the status is only the leading digits: what the runner appends after it is not part of the number
    o = ShellOutcome.parse("Failure [not installed for 0]\n" + M + ":1\n[Process timed out after 10000ms]", M);
    check("status 1 followed by the timeout note is a failure (not a status of 0 for an unreadable number)", !o.ok && o.text.startsWith("Failure [not installed for 0]"));
    o = ShellOutcome.parse("Success\n" + M + ":0\n[Process timed out after 10000ms]", M);
    check("status 0 followed by the timeout note fails closed, and the note is in the text", !o.ok && o.text.startsWith("Success") && o.text.contains("[Process timed out"));
    o = ShellOutcome.parse("x\n" + M + ":abc\n", M);
    check("an unreadable status is a failure (the text is kept)", !o.ok && o.text.equals("x"));
    o = ShellOutcome.parse("x\n" + M + ":", M);
    check("a status cut off right after the marker is a failure", !o.ok && o.text.equals("x"));
    o = ShellOutcome.parse("x\n" + M + ":\n", M);
    check("an empty status is a failure", !o.ok);
    o = ShellOutcome.parse("x\n" + M + ":0abc\n", M);
    check("digits glued to other text are not a status", !o.ok);
    o = ShellOutcome.parse("x\n" + M + ":12345678901\n", M);
    check("a number too long to be a status is not one", !o.ok);
    // no marker at all: the command never reached its end
    // MA2-4: the command as it is really sent, run by a real sh: a trailing "# comment" or a lone "&" must not lose the status line
    if (new java.io.File("/bin/sh").canExecute()) {
      for (String[] c : new String[][]{{"echo done # wipe cache", "0"}, {"echo done", "0"}, {"false # note", "1"}, {"true &", "0"}}) {
        String out = sh(ShellOutcome.withStatus(c[0], M));
        ShellOutcome r = ShellOutcome.parse(out, M);
        check("run by sh: '" + c[0] + "' gives its status (" + out.replace("\n", "|") + ")", out.contains(M + ":" + c[1]) && r.ok == c[1].equals("0"));
      }
    }
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
    check("an error line of any case is a failure", !o.ok);
    o = ShellOutcome.parse("partial output\n[Process timed out after 8000ms]", M);
    check("a timeout note (no marker) is a failure, partial output kept", !o.ok && o.text.contains("partial output"));
    o = ShellOutcome.parse("", M);
    check("empty output without the marker is a failure (the command never reported its status)", !o.ok && o.text.isEmpty());
    o = ShellOutcome.parse(null, M);
    check("null output is treated as empty: a failure with empty text", !o.ok && o.text.isEmpty());
    o = ShellOutcome.parse("Performing Streamed Install\nSuccess", M);
    check("ordinary output without the marker is a failure too (the status never came), text kept", !o.ok && o.text.startsWith("Performing"));
    o = ShellOutcome.parse("errors were found in nothing", M);
    check("any markerless text fails closed, whatever it says", !o.ok);
    // with the marker, only the status counts
    o = ShellOutcome.parse("see the Error: in the text\n" + M + ":0\n", M);
    check("with the marker, only the status counts: an 'Error:' inside the text does not fail a status-0 command", o.ok);
    o = ShellOutcome.parse("Error: x\n[Process timed out after 1ms]\n" + M + ":3\n", M);
    check("with the marker, a non-zero status fails regardless of the text", !o.ok);
    o = ShellOutcome.parse("Error: x\n[Process timed out after 1ms]\n" + M + ":0\n", M);
    check("a timeout note before the marker belongs to the text: the status after it is what counts", o.ok);
    o = ShellOutcome.parse("a " + M + ":0 b\n" + M + ":1\n", M);
    check("the last marker wins", !o.ok);
    // the markerless wording helper
    check("backendFailed: an Error: line", ShellOutcome.backendFailed("Error: x"));
    check("backendFailed: the timeout note", ShellOutcome.backendFailed("x\n[Process timed out after 1ms]"));
    check("backendFailed: plain text is not a backend failure", !ShellOutcome.backendFailed("Performing Streamed Install\nSuccess"));
    System.out.println(fails == 0 ? "PASS ShellOutcomeTest: " + n + " checks" : "FAILED ShellOutcomeTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
