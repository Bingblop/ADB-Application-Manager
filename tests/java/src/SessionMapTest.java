package com.bloatware.bingblop;

import java.util.List;

/** The terminal session map: a late end of a closed session must not remove or announce the end of the session that replaced it under the same id. */
public class SessionMapTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  public static void main(String[] a) {
    SessionMap<String> m = new SessionMap<String>();
    is("nothing stored yet", m.get("rt") == null && m.get(null) == null);
    is("put on an empty id replaces nothing", m.put("rt", "A") == null);
    is("get finds it", "A".equals(m.get("rt")));

    // restart: the page closes A and starts B under the same id; A's end is reported afterwards
    is("put replaces A and returns it (the caller closes it)", "A".equals(m.put("rt", "B")));
    is("A's late end does not tell the page (B holds the id)", !m.ended("rt", "A"));
    is("B is still stored after A's late end", "B".equals(m.get("rt")));
    is("B's own end tells the page and removes B", m.ended("rt", "B") && m.get("rt") == null);

    // explicit close: removed first, the end arrives later and the page still waits for it
    m.put("rt", "C");
    is("remove returns the session", "C".equals(m.remove("rt")));
    is("the end of a session closed on purpose tells the page", m.ended("rt", "C"));

    // the old session's end arriving when the new one has not been stored yet (slow start): nothing stored, the page is told
    is("an end with nothing stored tells the page", m.ended("rt", "D"));

    // other ids are independent
    m.put("x", "X1"); m.put("y", "Y1");
    is("ending x does not touch y", m.ended("x", "X1") && "Y1".equals(m.get("y")));

    List<String> all = m.drain();
    is("drain returns what was open and empties the map", all.size() == 1 && "Y1".equals(all.get(0)) && m.get("y") == null);
    is("remove(null) is harmless", m.remove(null) == null);

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
