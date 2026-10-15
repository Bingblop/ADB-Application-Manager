package com.bloatware.bingblop;

/** The arithmetic of the internet speed test: median, jitter, bits per second, the rounding the page shows, the server code, the byte meter. */
public class SpeedMathTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

  public static void main(String[] args) {
    // median
    check("median of an odd count is the middle value", near(SpeedMath.median(new double[]{30, 10, 20}), 20));
    check("median of an even count is the mean of the two middle values", near(SpeedMath.median(new double[]{40, 10, 30, 20}), 25));
    check("median of one sample is that sample", near(SpeedMath.median(new double[]{7.5}), 7.5));
    check("median of none is NaN", Double.isNaN(SpeedMath.median(new double[0])) && Double.isNaN(SpeedMath.median(null)));
    check("median leaves NaN and infinite samples out", near(SpeedMath.median(new double[]{Double.NaN, 5, Double.POSITIVE_INFINITY, 9, 7}), 7));
    check("median does not reorder the caller's array", sameOrder());
    check("one slow outlier does not move the median far (why the median and not the mean)", near(SpeedMath.median(new double[]{12, 13, 12, 14, 900}), 13));

    // jitter
    check("jitter is the mean absolute difference of consecutive samples: 10,20,15,15 -> (10+5+0)/3", near(SpeedMath.jitter(new double[]{10, 20, 15, 15}), 5));
    check("jitter follows the order the samples were taken, not sorted (10,30,10,30 -> 20)", near(SpeedMath.jitter(new double[]{10, 30, 10, 30}), 20));
    check("steady samples have no jitter", near(SpeedMath.jitter(new double[]{12, 12, 12}), 0));
    check("jitter needs two samples (NaN otherwise)", Double.isNaN(SpeedMath.jitter(new double[]{5})) && Double.isNaN(SpeedMath.jitter(new double[0])) && Double.isNaN(SpeedMath.jitter(null)));
    check("jitter leaves NaN samples out (10, NaN, 14 -> 4)", near(SpeedMath.jitter(new double[]{10, Double.NaN, 14}), 4));

    // bits per second
    check("1,250,000 bytes in 1 s is 10 Mbps", near(SpeedMath.mbps(SpeedMath.bitsPerSecond(1250000, 1000)), 10));
    check("125,000,000 bytes in 2 s is 500 Mbps", near(SpeedMath.mbps(SpeedMath.bitsPerSecond(125000000L, 2000)), 500));
    check("no time or no bytes is 0, not infinity", SpeedMath.bitsPerSecond(1000, 0) == 0 && SpeedMath.bitsPerSecond(0, 1000) == 0 && SpeedMath.bitsPerSecond(-5, 1000) == 0);
    check("a large count does not overflow (100 GB in 1 s)", near(SpeedMath.mbps(SpeedMath.bitsPerSecond(100_000_000_000L, 1000)), 800000));

    // rounding for the page
    check("three significant figures: 9.4237 -> 9.42, 94.237 -> 94.2, 942.37 -> 942, 1249.6 -> 1250",
        near(SpeedMath.roundMbps(9.4237), 9.42) && near(SpeedMath.roundMbps(94.237), 94.2) && near(SpeedMath.roundMbps(942.37), 942) && near(SpeedMath.roundMbps(1249.6), 1250));
    check("slow lines keep two decimals: 0.4567 -> 0.46, 0.004 -> 0", near(SpeedMath.roundMbps(0.4567), 0.46) && near(SpeedMath.roundMbps(0.004), 0));
    check("negative, NaN and infinite speeds are 0", SpeedMath.roundMbps(-3) == 0 && SpeedMath.roundMbps(Double.NaN) == 0 && SpeedMath.roundMbps(Double.POSITIVE_INFINITY) == 0);
    check("the boundaries: 9.999 -> 10, 99.96 -> 100", near(SpeedMath.roundMbps(9.999), 10) && near(SpeedMath.roundMbps(99.96), 100));
    check("milliseconds: one decimal under 10, whole from there (4.26 -> 4.3, 12.4 -> 12, 99.5 -> 100)", near(SpeedMath.roundMs(4.26), 4.3) && near(SpeedMath.roundMs(12.4), 12) && near(SpeedMath.roundMs(99.5), 100));
    check("milliseconds: NaN stays NaN (no samples), negative is 0", Double.isNaN(SpeedMath.roundMs(Double.NaN)) && SpeedMath.roundMs(-1) == 0);
    check("as text without trailing zeros: 9.4 -> \"9.4\", 94.2 -> \"94.2\", 942 -> \"942\", 0 -> \"0\", 9.5 -> \"9.5\", 2.05 -> \"2.05\"",
        SpeedMath.formatMbps(9.4).equals("9.4") && SpeedMath.formatMbps(94.237).equals("94.2") && SpeedMath.formatMbps(942.37).equals("942") && SpeedMath.formatMbps(0).equals("0")
        && SpeedMath.formatMbps(9.5).equals("9.5") && SpeedMath.formatMbps(2.05).equals("2.05") && SpeedMath.formatMbps(1249.6).equals("1250"));

    // the data centre
    check("the colo header wins", SpeedMath.colo("IAD", "FRA", "abc-LHR").equals("IAD"));
    check("cf-meta-colo when there is no colo header (uploads)", SpeedMath.colo(null, "fra", null).equals("FRA"));
    check("the end of cf-ray when neither is there", SpeedMath.colo("", null, "8c1f0a2b3c4d5e6f-SJC").equals("SJC"));
    check("anything that is not three letters is not a code", SpeedMath.colo("IAD1", "<b>", "abc-12").isEmpty() && SpeedMath.colo(null, null, null).isEmpty() && SpeedMath.colo("  ", " ", "nodash").isEmpty());

    // the meter
    SpeedMath.Meter m = new SpeedMath.Meter();
    check("an empty meter reads 0", m.currentBps(1000) == 0 && m.averageBps(0) == 0 && m.bytes() == 0 && m.lastTime() == 0);
    for (int t = 0; t <= 4000; t += 100) m.add(t, t < 2000 ? (long) t * 500 : 1_000_000L + (long) (t - 2000) * 1250);   // 4 Mbps for 2 s, then 10 Mbps
    check("the current speed is the last second: 10 Mbps", near(SpeedMath.mbps(m.currentBps(1000)), 10));
    check("the average after a 2 s ramp-up leaves the slow start out: 10 Mbps", near(SpeedMath.mbps(m.averageBps(2000)), 10));
    check("the average of the whole phase includes it: (1 MB + 2.5 MB) in 4 s = 7 Mbps", near(SpeedMath.mbps(m.averageBps(0)), 7));
    check("bytes and time are the last sample's", m.bytes() == 3_500_000L && m.lastTime() == 4000);
    m.add(3900, 4_000_000L);
    m.add(4100, 3_000_000L);
    check("a sample that goes back in time or in bytes is dropped", m.lastTime() == 4000 && m.bytes() == 3_500_000L);
    check("a window longer than the phase reads from the first sample", near(SpeedMath.mbps(m.currentBps(60000)), 7));

    SpeedMath.Meter fast = new SpeedMath.Meter();
    fast.add(0, 0); fast.add(500, 20_000_000L); fast.add(800, 30_000_000L);                          // the cap reached during the ramp-up
    check("a phase that ended inside the ramp-up is measured over all of it (30 MB in 0.8 s = 300 Mbps)", near(SpeedMath.mbps(fast.averageBps(2000)), 300));
    SpeedMath.Meter one = new SpeedMath.Meter();
    one.add(0, 0);
    check("one sample is no speed yet", one.currentBps(1000) == 0 && one.averageBps(0) == 0);
    SpeedMath.Meter many = new SpeedMath.Meter();
    for (int i = 0; i < 1000; i++) many.add(i * 10L, i * 1000L);
    check("the meter grows past its first array (1000 samples)", many.size() == 1000 && near(SpeedMath.mbps(many.currentBps(1000)), 0.8));

    System.out.println(fails == 0 ? "PASS SpeedMathTest: " + n + " checks" : "FAILED SpeedMathTest: " + fails + " of " + n);
    if (fails > 0) System.exit(1);
  }

  static boolean sameOrder() {
    double[] a = {3, 1, 2};
    SpeedMath.median(a);
    return a[0] == 3 && a[1] == 1 && a[2] == 2;
  }
}
