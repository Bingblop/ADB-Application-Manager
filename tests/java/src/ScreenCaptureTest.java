package com.bloatware.bingblop;

import java.io.File;
import java.nio.file.Files;
import java.util.TimeZone;

/** Screenshots and recordings through a shell: the paths, the commands (run for real through sh with fake tools), what they printed, and what counts as written. */
public class ScreenCaptureTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static String run(String cmd) throws Exception {
    Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return out;
  }

  public static void main(String[] x) throws Exception {
    TimeZone utc = TimeZone.getTimeZone("UTC");
    long t = 1760300130000L;      // 2025-10-12 20:15:30 UTC
    String shot = ScreenCapture.shotPath(t, utc), vid = ScreenCapture.videoPath(t, utc);
    is("screenshot path in Pictures, with a sortable stamp", shot.equals("/sdcard/Pictures/ADB App Manager/screenshot-20251012-201530.png"));
    is("recording path in Movies", vid.equals("/sdcard/Movies/ADB App Manager/screenrecord-20251012-201530.mp4"));
    is("both are paths of ours", ScreenCapture.ownPath(shot) && ScreenCapture.ownPath(vid));

    String[] bad = { null, "", "/sdcard/x.png", "/sdcard/Pictures/ADB App Manager/../../x.png", "/sdcard/Pictures/ADB App Manager/screenshot-1.png",
        "/sdcard/Pictures/ADB App Manager/screenshot-20251012-201530.png; rm -rf /", "/sdcard/Pictures/ADB App Manager/screenshot-20251012-201530.mp4",
        "/sdcard/Movies/ADB App Manager/screenrecord-20251012-201530.png", "/sdcard/Pictures/ADB App Manager/screenshot-20251012-201530.png\n" };
    for (String b : bad) is("not our path: " + b, !ScreenCapture.ownPath(b) && ScreenCapture.shotCommand(b) == null && ScreenCapture.recordCommand(b, 30) == null);

    is("delay is limited to 0..10 s", ScreenCapture.clampDelay(-4) == 0 && ScreenCapture.clampDelay(3) == 3 && ScreenCapture.clampDelay(99) == 10);
    is("a recording is 5..180 s", ScreenCapture.clampRecord(0) == 5 && ScreenCapture.clampRecord(60) == 60 && ScreenCapture.clampRecord(9999) == 180);

    String sc = ScreenCapture.shotCommand(shot), rc = ScreenCapture.recordCommand(vid, 9999);
    is("the screenshot command quotes the path and names screencap", sc.contains("screencap -p '" + shot + "'") && sc.startsWith("mkdir -p '/sdcard/Pictures/ADB App Manager' && "));
    is("the recording command limits the time to 180 s", rc.contains("screenrecord --time-limit 180 '" + vid + "'"));
    is("stop asks screenrecord to finish with SIGINT", ScreenCapture.stopCommand().contains("pkill -2 -x screenrecord") && ScreenCapture.stopCommand().contains("killall -2 screenrecord"));
    is("the commands are valid sh", run("sh -n -c " + RishShell.quote(sc) + " && sh -n -c " + RishShell.quote(rc) + " && echo ok").trim().equals("ok"));

    // run them against a fake phone: sdcard in a temp folder, screencap/screenrecord as small scripts on PATH
    File root = Files.createTempDirectory("scap").toFile(), bin = new File(root, "bin"); bin.mkdirs();
    File good = new File(bin, "screencap"); Files.write(good.toPath(), "#!/bin/sh\nshift\nprintf 'PNGDATA' > \"$1\"\n".getBytes()); good.setExecutable(true);
    File rec = new File(bin, "screenrecord"); Files.write(rec.toPath(), "#!/bin/sh\nshift 2\nprintf 'MP4DATA!' > \"$1\"\n".getBytes()); rec.setExecutable(true);
    String env = "PATH=" + bin.getPath() + ":$PATH; ";
    String fakeShot = sc.replace("/sdcard", root.getPath() + "/sdcard"), fakeRec = rc.replace("/sdcard", root.getPath() + "/sdcard");
    // the own-path rule is for the real folders; the fake run swaps only the prefix of the already-built command
    ScreenCapture.Result a = ScreenCapture.parse(run(env + fakeShot));
    is("a screenshot that wrote 7 bytes: exit 0, size 7, written", a.exit == 0 && a.bytes == 7 && a.wrote() && a.text.isEmpty());
    ScreenCapture.Result b = ScreenCapture.parse(run(env + fakeRec));
    is("a recording that wrote 8 bytes: exit 0, size 8", b.exit == 0 && b.bytes == 8 && b.wrote());

    Files.write(good.toPath(), "#!/bin/sh\necho 'screencap: failed to capture' >&2\nexit 1\n".getBytes());
    ScreenCapture.Result c = ScreenCapture.parse(run(env + fakeShot.replace(root.getPath() + "/sdcard/Pictures/ADB App Manager/screenshot-20251012-201530.png", root.getPath() + "/sdcard/Pictures/ADB App Manager/screenshot-20251012-201531.png")));
    is("a failing screencap: its exit status and message are kept, nothing written", c.exit == 1 && c.text.contains("failed to capture") && !c.wrote());

    // the status the bridge reports
    is("written is ok", ScreenCapture.status("EXIT:0\nSIZE:7\n", ScreenCapture.parse("EXIT:0\nSIZE:7\n")).equals("ok"));
    is("exit 0 but an empty file is failed", ScreenCapture.status("EXIT:0\nSIZE:0\n", ScreenCapture.parse("EXIT:0\nSIZE:0\n")).equals("failed"));
    is("no size at all is failed", ScreenCapture.status("EXIT:1\nSIZE:\n", ScreenCapture.parse("EXIT:1\nSIZE:\n")).equals("failed"));
    String err = "Error: this needs ADB, Wireless Debugging, Shizuku or Root";
    is("the backend's own refusal is no_access", ScreenCapture.status(err, ScreenCapture.parse(err)).equals("no_access"));
    is("null and empty answers are failed, not a crash", ScreenCapture.status(null, ScreenCapture.parse(null)).equals("failed") && ScreenCapture.status("", ScreenCapture.parse("")).equals("failed"));
    String longText = "x".repeat(3000) + "\nEXIT:1\nSIZE:\n";
    is("a long message is cut to its last 1000 characters", ScreenCapture.parse(longText).text.length() == 1000);

    System.out.println(fails == 0 ? "all " + n + " ok" : fails + " of " + n + " FAILED");
    if (fails != 0) System.exit(1);
  }
}
