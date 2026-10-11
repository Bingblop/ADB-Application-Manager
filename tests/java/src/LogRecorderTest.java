package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The log recorder: only new lines are written, bad moments are skipped, the size limit, stop, the list, reading a long file, names that are not allowed. */
public class LogRecorderTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static class Fake implements LogRecorder.Source {
    volatile String next = "";
    int reads = 0;
    public synchronized String read() { reads++; return next; }
  }

  static String L(int i) { return String.format("10-08 12:00:%02d.000  1000  1001 E Tag: message %d", i % 60, i); }

  public static void main(String[] args) throws Exception {
    File dir = Files.createTempDirectory("logrec").toFile();
    Fake src = new Fake();
    LogRecorder r = new LogRecorder(dir, src, 10000, 1 << 20);        // the thread waits long: ticks are driven by hand
    src.next = L(1) + "\n" + L(2) + "\n";
    String name = r.start(Arrays.asList("ADB Application Manager - recording", "Level: Error"));
    check("start returns a valid file name", LogRecorder.validName(name) && name.startsWith("logcat_") && name.endsWith(".log"));
    check("a second start while recording is refused", r.start(null).isEmpty());
    for (int i = 0; i < 50 && r.status().lines < 2; i++) Thread.sleep(20);       // the thread's first tick runs at once
    check("the first snapshot is written", r.status().lines == 2);
    r.tick();
    check("the same snapshot again writes nothing", r.status().lines == 2);
    src.next = L(2) + "\n" + L(3) + "\n" + L(4) + "\n";                              // the buffer moved on by two lines
    r.tick();
    check("only the new lines are added", r.status().lines == 4);
    src.next = "(no matching log lines)";
    r.tick();
    check("'no matching lines' changes nothing", r.status().lines == 4 && r.status().lastError.isEmpty());
    src.next = "Error: reading logcat needs ADB, Shizuku or Root.";
    r.tick();
    check("an error is remembered but nothing is written", r.status().lines == 4 && r.status().lastError.startsWith("Error:") && r.status().running);
    src.next = "note: shares its user ID with 1 other package(s), so their lines appear too\n" + L(5) + "\n";
    r.tick();
    check("a note line is not written, the next snapshot clears the error", r.status().lines == 5 && r.status().lastError.isEmpty());
    src.next = L(6) + "\r\n\n   \n" + L(7);
    r.tick();
    check("CRLF and blank lines are tolerated", r.status().lines == 7);
    String rec = "10-08 12:01:00.000  1000  1001 E AndroidRuntime: \tat com.x.Y.f(Y.java:1)";
    src.next = rec + "\n" + rec + "\n" + rec + "\n";
    r.tick();
    check("identical lines inside one snapshot (recursion) are all kept", r.status().lines == 10);
    r.tick();
    check("and are not written again by the next snapshot", r.status().lines == 10);
    LogRecorder.Status st = r.stop("stopped");
    check("stop ends the recording and keeps the counts", !st.running && st.lines == 10 && st.bytes > 0 && st.stopReason.equals("stopped"));
    src.next = L(8);
    r.tick();
    check("nothing is written after stop", r.status().lines == 10);
    String text = LogRecorder.read(dir, name, 1 << 20);
    String[] got = text.split("\n");
    check("the file starts with the header as comments", got[0].equals("# ADB Application Manager - recording") && got[1].equals("# Level: Error"));
    List<String> body = new ArrayList<String>();
    for (String s : got) if (!s.startsWith("#")) body.add(s);
    check("the body is the ten lines in order, once each", body.size() == 10 && body.get(0).endsWith("message 1") && body.get(6).endsWith("message 7") && body.get(9).equals(rec));

    // a long recording read back from its end
    String longText = LogRecorder.read(dir, name, 200);
    check("a long file is cut to its end with a note", longText.startsWith("# (the first ") && longText.endsWith("Y.java:1)\n"));

    // the size limit stops the recording
    Fake big = new Fake();
    LogRecorder small = new LogRecorder(dir, big, 10000, 300);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 20; i++) sb.append(L(i)).append('\n');
    big.next = sb.toString();
    small.start(null);
    for (int i = 0; i < 50 && small.status().running; i++) Thread.sleep(20);
    LogRecorder.Status ss = small.status();
    check("the size limit stops the recording and says why", !ss.running && ss.stopReason.contains("size limit") && ss.lines > 0);

    // the list
    Thread.sleep(1100);
    LogRecorder two = new LogRecorder(dir, big, 10000, 1 << 20);
    String n2 = two.start(null);
    two.stop("stopped");
    List<LogRecorder.Info> list = LogRecorder.list(dir);
    check("the list holds the recordings, newest first", list.size() == 3 && list.get(0).name.equals(n2));
    check("a recording started in the same second gets its own name", !n2.equals(name));

    // names that are not allowed
    check("names with folders or odd endings are refused", !LogRecorder.validName("../x.log") && !LogRecorder.validName("a/b.log") && !LogRecorder.validName("x.txt") && !LogRecorder.validName(".hidden.log") && !LogRecorder.validName(null) && !LogRecorder.validName(""));
    check("reading a refused name gives nothing", LogRecorder.read(dir, "../etc/passwd", 100).isEmpty());
    File outside = new File(dir.getParentFile(), "keep_" + System.nanoTime() + ".log");
    Files.write(outside.toPath(), "x".getBytes("UTF-8"));
    check("delete refuses a path outside the folder", !LogRecorder.delete(dir, "../" + outside.getName()) && outside.exists());
    outside.delete();
    check("delete removes a recording", LogRecorder.delete(dir, name) && LogRecorder.list(dir).size() == 2 && !LogRecorder.delete(dir, name));

    // unicode survives
    Fake u = new Fake();
    LogRecorder ur = new LogRecorder(dir, u, 10000, 1 << 20);
    u.next = "10-08 12:00:00.000  1  2 I Tag: café 日本語 😀";
    String un = ur.start(null);
    for (int i = 0; i < 50 && ur.status().lines < 1; i++) Thread.sleep(20);
    ur.stop("stopped");
    check("non-ASCII text is written as UTF-8", LogRecorder.read(dir, un, 1 << 20).contains("café 日本語 😀"));

    // a source that throws does not end the recording
    LogRecorder boom = new LogRecorder(dir, new LogRecorder.Source() { public String read() { throw new RuntimeException("adb busy"); } }, 10000, 1 << 20);
    boom.start(null);
    boom.tick();
    check("a failing source is noted and the recording goes on", boom.status().running && boom.status().lastError.contains("adb busy"));
    boom.stop("stopped");

    for (File f : dir.listFiles()) f.delete();
    dir.delete();
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
