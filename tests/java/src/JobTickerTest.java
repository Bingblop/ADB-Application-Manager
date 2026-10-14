package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The ticker reports on its own thread (also when the worker is stuck), names a stall, and stops. */
public class JobTickerTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  interface Cond { boolean ok(); }
  /** Polls for up to 5 s: a loaded CI machine may run the ticker thread late, so the checks wait for the condition instead of for a fixed time. */
  static boolean waitFor(Cond c) throws InterruptedException {
    long end = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < end) { if (c.ok()) return true; Thread.sleep(10); }
    return c.ok();
  }

  public static void main(String[] args) throws Exception {
    final ProgressMeter m = new ProgressMeter(1000, 2, System.currentTimeMillis());
    final List<String> texts = Collections.synchronizedList(new ArrayList<String>());
    final List<Long> stalls = Collections.synchronizedList(new ArrayList<Long>());
    final AtomicInteger inFlight = new AtomicInteger();                 // callbacks that have started and not yet finished
    JobTicker t = new JobTicker(m, "Copying", 50, 200, new JobTicker.Listener() {
      public void onTick(String text, int pct, long stalledMs) {
        inFlight.incrementAndGet();
        try { texts.add(text); stalls.add(stalledMs); } finally { inFlight.decrementAndGet(); }
      }
    });
    t.start();
    check("it reports on its own, many times", waitFor(() -> texts.size() >= 2));
    check("while the job has just begun nothing is stalled", stalls.get(0) == 0);
    synchronized (m) { m.update(500, 1, "a.bin", System.currentTimeMillis()); }
    check("the text follows the meter", waitFor(() -> { String x = texts.get(texts.size() - 1); return x.contains("a.bin") && x.contains("50%"); }));
    // the worker is stuck: no update; the ticker has to notice
    check("a stuck worker is noticed by the ticker and the text says so", waitFor(() -> {
      String x = texts.get(texts.size() - 1);
      return stalls.get(stalls.size() - 1) >= 200 && x.contains("nothing has moved for") && x.contains("Cancel stops it");
    }));
    synchronized (m) { m.update(501, 1, "a.bin", System.currentTimeMillis()); }
    check("movement ends the stall note", waitFor(() -> stalls.get(stalls.size() - 1) == 0 && !texts.get(texts.size() - 1).contains("nothing has moved")));
    t.stop();
    check("stop() leaves no report in flight (waited for, not guessed)", waitFor(() -> inFlight.get() == 0));
    int c = texts.size();
    Thread.sleep(200);
    check("after stop() nothing more is reported", texts.size() == c);
    t.start();
    check("it can be started again", waitFor(() -> texts.size() > c));
    t.stop();
    // a report under way when stop() is called finishes before stop() returns, and none starts after it (a stale one would write over the result)
    final AtomicInteger late = new AtomicInteger(), slowIn = new AtomicInteger();
    final boolean[] stoppedFlag = { false };
    JobTicker slow = new JobTicker(new ProgressMeter(100, 0, System.currentTimeMillis()), "Y", 1, 1000, new JobTicker.Listener() {
      public void onTick(String text, int pct, long s) {
        slowIn.incrementAndGet();
        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        if (stoppedFlag[0]) late.incrementAndGet();
        slowIn.decrementAndGet();
      }
    });
    slow.start();
    check("the slow listener is being called", waitFor(() -> slowIn.get() > 0));
    slow.stop();
    stoppedFlag[0] = true;
    check("stop() returns only when no report is under way", slowIn.get() == 0);
    Thread.sleep(200);
    check("no report runs on after stop() returned", late.get() == 0);
    JobTicker direct = new JobTicker(new ProgressMeter(100, 0, System.currentTimeMillis()), "Z", 1000, 1000, new JobTicker.Listener() {
      public void onTick(String text, int pct, long s) { late.incrementAndGet(); }
    });
    direct.tick();
    check("a direct tick() before start() still reports", late.get() == 1);
    direct.stop();
    direct.tick();
    check("a direct tick() after stop() does not", late.get() == 1);
    // a listener that throws does not end the ticker
    final AtomicInteger k = new AtomicInteger();
    JobTicker bad = new JobTicker(new ProgressMeter(0, 0, System.currentTimeMillis()), "x", 30, 1000, new JobTicker.Listener() {
      public void onTick(String text, int pct, long s) { k.incrementAndGet(); throw new RuntimeException("boom"); }
    });
    bad.start();
    check("a listener that throws does not stop the ticker", waitFor(() -> k.get() >= 3));
    bad.stop();
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
