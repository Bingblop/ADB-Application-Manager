package com.bloatware.bingblop;

/** The numbers a long job shows: percent, smoothed speed, time left, the stall check, and the wording. */
public class ProgressMeterTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  public static void main(String[] args) {
    long t = 1_000_000;
    ProgressMeter m = new ProgressMeter(100L * 1024 * 1024, 10, t);
    check("at the start: 0 percent, no speed, no time left", m.percent() == 0 && m.bytesPerSecond() == 0 && m.etaSeconds() == -1);
    m.update(10L * 1024 * 1024, 1, "a.bin", t + 1000);
    check("10 MB in 1 s: 10 percent, about 10 MB/s, 9 s left", m.percent() == 10 && Math.abs(m.bytesPerSecond() - 10 * 1048576.0) < 1 && m.etaSeconds() == 9);
    m.update(20L * 1024 * 1024, 2, "b.bin", t + 2000);
    check("a steady speed stays the same", Math.abs(m.bytesPerSecond() - 10 * 1048576.0) < 1 && m.etaSeconds() == 8);
    m.update(21L * 1024 * 1024, 2, "b.bin", t + 3000);              // a slow second: 1 MB/s
    double s = m.bytesPerSecond();
    check("one slow second only pulls the speed part of the way (smoothed): " + s / 1048576, s > 1048576 * 5 && s < 1048576 * 10);
    check("a sample less than 400 ms after the last one does not change the speed", doesNotJump());
    check("the text names the item, the percent, the speed and the time left", m.line("Copying", t + 3000).startsWith("Copying 3 of 10: b.bin — 21% · ") && m.line("Copying", t + 3000).contains("/s") && m.line("Copying", t + 3000).endsWith(" left"));
    check("no time left is shown in the first moments", !new ProgressMeter(1000, 1, t).line("Copying", t + 500).contains("left"));
    check("sizes and durations are worded", ProgressMeter.size(5) .equals("5 B") && ProgressMeter.size(1536).equals("1.5 KB") && ProgressMeter.size(3L * 1048576).equals("3.0 MB") && ProgressMeter.size(5L * 1073741824).equals("5.00 GB")
            && ProgressMeter.duration(9).equals("9 s") && ProgressMeter.duration(125).equals("2 min 5 s") && ProgressMeter.duration(3700).equals("1 h 1 min") && ProgressMeter.duration(-1).isEmpty());

    // stall: nothing moved
    ProgressMeter w = new ProgressMeter(1000, 1, t);
    w.update(100, 0, "x", t + 1000);
    check("not stalled while it moves", !w.stalled(t + 20000, 30000) && w.stalledMs(t + 1000) == 0);
    check("stalled when nothing moved for the time given", w.stalled(t + 31000, 30000) && !w.stalled(t + 30999, 30000) && w.stalledMs(t + 31000) == 30000);
    w.update(100, 0, "x", t + 40000);                              // same numbers again: not a move
    check("the same numbers again are not progress", w.stalled(t + 41000, 30000));
    w.update(101, 0, "x", t + 41000);
    check("one more byte is progress and resets the stall", !w.stalled(t + 42000, 30000));

    // no total known: items decide, else -1
    ProgressMeter u = new ProgressMeter(0, 4, t);
    u.update(0, 1, "f", t + 100);
    check("without a byte total the percent comes from the items", u.percent() == 25 && u.etaSeconds() == -1);
    ProgressMeter v = new ProgressMeter(0, 0, t);
    check("nothing known: -1 percent and a plain line", v.percent() == -1 && v.line("Searching", t + 10).equals("Searching"));
    ProgressMeter big = new ProgressMeter(10, 1, t);
    big.update(50, 1, "x", t + 1000);
    check("more bytes than expected stays at 100 percent with nothing left", big.percent() == 100 && big.etaSeconds() == 0);
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
  static boolean doesNotJump() {
    ProgressMeter m = new ProgressMeter(1000000, 1, 0);
    m.update(1000, 0, "a", 1000);
    double a = m.bytesPerSecond();
    m.update(900000, 0, "a", 1100);                               // 100 ms later: too soon to sample
    return m.bytesPerSecond() == a;
  }
}
