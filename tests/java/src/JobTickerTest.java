package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The ticker reports on its own thread (also when the worker is stuck), names a stall, and stops. */
public class JobTickerTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  public static void main(String[] args) throws Exception {
    final ProgressMeter m = new ProgressMeter(1000, 2, System.currentTimeMillis());
    final List<String> texts = Collections.synchronizedList(new ArrayList<String>());
    final List<Long> stalls = Collections.synchronizedList(new ArrayList<Long>());
    JobTicker t = new JobTicker(m, "Copying", 50, 200, new JobTicker.Listener() {
      public void onTick(String text, int pct, long stalledMs) { texts.add(text); stalls.add(stalledMs); }
    });
    t.start();
    Thread.sleep(120);
    check("it reports on its own, many times", texts.size() >= 2);
    check("while the job has just begun nothing is stalled", stalls.get(0) == 0);
    synchronized (m) { m.update(500, 1, "a.bin", System.currentTimeMillis()); }
    Thread.sleep(120);
    check("the text follows the meter", texts.get(texts.size() - 1).contains("a.bin") && texts.get(texts.size() - 1).contains("50%"));
    Thread.sleep(400);                                             // the worker is stuck: no update
    String last = texts.get(texts.size() - 1);
    check("a stuck worker is noticed by the ticker and the text says so", stalls.get(stalls.size() - 1) >= 200 && last.contains("nothing has moved for") && last.contains("Cancel stops it"));
    synchronized (m) { m.update(501, 1, "a.bin", System.currentTimeMillis()); }
    Thread.sleep(120);
    check("movement ends the stall note", stalls.get(stalls.size() - 1) == 0 && !texts.get(texts.size() - 1).contains("nothing has moved"));
    t.stop();
    int c = texts.size();
    Thread.sleep(200);
    check("after stop() nothing more is reported", texts.size() == c);
    t.start(); Thread.sleep(80); t.stop();
    check("it can be started again", texts.size() > c);
    // a listener that throws does not end the ticker
    final int[] k = {0};
    JobTicker bad = new JobTicker(new ProgressMeter(0, 0, System.currentTimeMillis()), "x", 30, 1000, new JobTicker.Listener() {
      public void onTick(String text, int pct, long s) { k[0]++; throw new RuntimeException("boom"); }
    });
    bad.start(); Thread.sleep(150); bad.stop();
    check("a listener that throws does not stop the ticker", k[0] >= 3);
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
