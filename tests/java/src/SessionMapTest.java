package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;

/**
 * The terminal session map: a late end of a closed session must not remove or announce the end of the session that replaced it under the same id; a
 * start that was asked for first but finishes last must not replace the newer one; nothing is published after the shutdown drained the map.
 */
public class SessionMapTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  /** A start: asks for a ticket and publishes at once. Returns the sessions it replaced, or null when it was refused. */
  static List<String> start(SessionMap<String> m, String id, String s) {
    long t = m.ticket(id);
    List<String> replaced = new ArrayList<String>();
    return m.publish(id, t, s, replaced) ? replaced : null;
  }

  public static void main(String[] a) throws Exception {
    SessionMap<String> m = new SessionMap<String>();
    is("nothing stored yet", m.get("rt") == null && m.get(null) == null);
    List<String> r = start(m, "rt", "A");
    is("a publish on an empty id replaces nothing", r != null && r.isEmpty());
    is("get finds it", "A".equals(m.get("rt")));

    // restart: the page closes A and starts B under the same id; A's end is reported afterwards
    r = start(m, "rt", "B");
    is("publish replaces A and returns it (the caller closes it)", r != null && r.size() == 1 && "A".equals(r.get(0)));
    is("A's late end does not tell the page (B holds the id)", !m.ended("rt", "A"));
    is("B is still stored after A's late end", "B".equals(m.get("rt")));
    is("B's own end tells the page and removes B", m.ended("rt", "B") && m.get("rt") == null);

    // explicit close: removed first, the end arrives later and the page still waits for it
    start(m, "rt", "C");
    is("remove returns the session", "C".equals(m.remove("rt")));
    is("the end of a session closed on purpose tells the page", m.ended("rt", "C"));

    // the old session's end arriving when the new one has not been stored yet (slow start): nothing stored, the page is told
    is("an end with nothing stored tells the page", m.ended("rt", "D"));

    // other ids are independent
    start(m, "x", "X1"); start(m, "y", "Y1");
    is("ending x does not touch y", m.ended("x", "X1") && "Y1".equals(m.get("y")));

    // out of order: the page asks for P, then for Q; Q finishes first, P (slow) finishes last and must not replace Q
    long tp = m.ticket("o"), tq = m.ticket("o");
    List<String> rep = new ArrayList<String>();
    is("the newer start publishes", m.publish("o", tq, "Q", rep) && "Q".equals(m.get("o")) && rep.isEmpty());
    is("the older, slower start is refused and Q stays", !m.publish("o", tp, "P", rep) && "Q".equals(m.get("o")) && rep.isEmpty());

    is("the newest start is current, an older one is not", m.isCurrent("o", tq) && !m.isCurrent("o", tp));

    // a close of the id while a start is still running makes that start stale
    long tr = m.ticket("c");
    m.remove("c");
    is("a start whose id was closed meanwhile is not current and is refused", !m.isCurrent("c", tr) && !m.publish("c", tr, "R", new ArrayList<String>()) && m.get("c") == null);

    // an end exactly when the same session is not stored does not claim a replacement that is not there
    long ts = m.ticket("e");
    m.publish("e", ts, "S1", new ArrayList<String>());
    m.publish("e", m.ticket("e"), "S2", new ArrayList<String>());
    is("S1's end after S2 replaced it is not announced and does not remove S2", !m.ended("e", "S1") && "S2".equals(m.get("e")));

    // a replaced session's late end is never told, even when the replacement is gone by then (explicit close or its own end)
    start(m, "sup", "A1"); start(m, "sup", "B1");
    is("B1 ends (stored): told, map empty", m.ended("sup", "B1") && m.get("sup") == null);
    is("A1's delayed end after the replacement is gone is still not told", !m.ended("sup", "A1"));
    start(m, "sup2", "A2"); start(m, "sup2", "B2");
    m.remove("sup2");
    is("B2 closed on purpose, then A2's delayed end: not told", !m.ended("sup2", "A2"));
    is("B2's own end after its close is told (the page waits for it)", m.ended("sup2", "B2"));
    is("a session closed on purpose without being replaced still tells its end once", (start(m, "sup3", "C3") != null) && "C3".equals(m.remove("sup3")) && m.ended("sup3", "C3"));

    // an end that is told on the UI thread later: a start stored in between makes the notice stale (A ended, then B started, then "A ended" arrives)
    start(m, "gen", "GA");
    long gAt = m.endedAt("gen", "GA");
    is("an end that is told carries the id's generation", gAt == m.generation("gen") && gAt >= 1);
    is("with nothing started since, the notice is still current", m.generation("gen") == gAt);
    start(m, "gen", "GB");
    is("a start stored after the decision makes the notice stale", m.generation("gen") != gAt);
    start(m, "gen2", "G2A"); start(m, "gen2", "G2B");
    is("an end that is not told has no generation", m.endedAt("gen2", "G2A") == -1);
    long own = m.endedAt("gen2", "G2B");
    is("the stored session's end is told with the current generation", own == m.generation("gen2"));
    is("a close does not move the generation (the page still waits for the end)", (start(m, "gen3", "G3") != null) && "G3".equals(m.remove("gen3")) && m.endedAt("gen3", "G3") == m.generation("gen3"));
    is("an unknown id has generation 0", m.generation("never") == 0);
    m.remove("gen");

    // the page's restart: it closes the old session first (so the old one is not "replaced" by a publish), then starts the new one
    long pa = m.publishGen("rs", m.ticket("rs"), "RA", new ArrayList<String>());
    is("a publish returns the generation it was stored under", pa >= 1 && pa == m.generation("rs"));
    is("the close of the old session moves the generation (its queued output is stale)", "RA".equals(m.remove("rs")) && m.generation("rs") != pa);
    long pb = m.publishGen("rs", m.ticket("rs"), "RB", new ArrayList<String>());
    is("the restarted session is stored under a newer generation", pb > pa);
    is("RB ends and is told", m.endedAt("rs", "RB") == pb);
    is("RA's delayed end, after RB came and went, is not told (it was closed before RB started)", m.endedAt("rs", "RA") == -1);

    // a close with no restart: the end of the closed session is still told, once, while nothing newer was stored
    m.publishGen("cl", m.ticket("cl"), "CA", new ArrayList<String>());
    m.remove("cl");
    long afterClose = m.generation("cl");
    is("the end of a session closed on purpose is told under the generation of its close", m.endedAt("cl", "CA") == afterClose);
    m.publishGen("cl2", m.ticket("cl2"), "DA", new ArrayList<String>());
    m.remove("cl2");
    m.publishGen("cl2", m.ticket("cl2"), "DB", new ArrayList<String>());
    is("a closed session's end after a newer one is stored is not told", m.endedAt("cl2", "DA") == -1 && "DB".equals(m.get("cl2")));
    m.remove("cl2");

    // a session that ends on its own keeps what it queued: its generation does not move, so its start and last output stay current
    long na = m.publishGen("nat", m.ticket("nat"), "NA", new ArrayList<String>());
    long told = m.endedAt("nat", "NA");
    is("a natural end does not move the generation (queued start and output stay valid, in order, before the end)", told == na && m.generation("nat") == na);
    is("a newer start after the natural end makes the queued notices stale", m.publishGen("nat", m.ticket("nat"), "NB", new ArrayList<String>()) > na && m.generation("nat") != na);
    m.remove("nat");

    // replaced by a publish without a prior close: never told
    m.publishGen("rp", m.ticket("rp"), "PA", new ArrayList<String>());
    m.publishGen("rp", m.ticket("rp"), "PB", new ArrayList<String>());
    m.remove("rp");
    is("a session replaced by a publish is never told, even when the replacement is gone", m.endedAt("rp", "PA") == -1);

    // shutdown: drained sessions are returned once, and nothing can be published afterwards
    long tl = m.ticket("late");
    List<String> all = m.drain();
    is("drain returns what was open and empties the map", all.size() == 3 && m.get("y") == null);
    is("a start that finishes after the shutdown is refused", !m.publish("late", tl, "L", new ArrayList<String>()) && m.get("late") == null);
    is("after the shutdown no start is current", !m.isCurrent("late", tl));
    is("a fresh ticket after the shutdown is refused too", !m.publish("z", m.ticket("z"), "Z", new ArrayList<String>()));
    is("a second drain returns nothing", m.drain().isEmpty());
    is("remove(null) is harmless", m.remove(null) == null);

    // concurrency: publishers and a drain run together; every session is either returned by the drain or refused to its publisher, none is lost
    final SessionMap<Integer> cm = new SessionMap<Integer>();
    final int N = 2000;
    final java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger replacedCount = new java.util.concurrent.atomic.AtomicInteger();
    Thread[] ts4 = new Thread[4];
    for (int k = 0; k < ts4.length; k++) {
      final int base = k * N;
      ts4[k] = new Thread(new Runnable() { public void run() {
        for (int i = 0; i < N; i++) {
          String id = "id" + (i % 50);
          long t = cm.ticket(id);
          List<Integer> rp = new ArrayList<Integer>();
          if (!cm.publish(id, t, base + i, rp)) refused.incrementAndGet();
          replacedCount.addAndGet(rp.size());
        }
      } });
      ts4[k].start();
    }
    Thread.sleep(2);
    List<Integer> drained = cm.drain();
    for (Thread t : ts4) t.join();
    int total = ts4.length * N;
    // every publish was either refused, replaced an older one (which its caller closes), or is in the drained list; the map is empty at the end
    boolean empty = true;
    for (int i = 0; i < 50; i++) if (cm.get("id" + i) != null) empty = false;
    is("after a drain racing with publishers nothing is left in the map", empty);
    is("every session is accounted for: refused " + refused + " + replaced " + replacedCount + " + drained " + drained.size() + " = " + total,
        refused.get() + replacedCount.get() + drained.size() == total);

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
