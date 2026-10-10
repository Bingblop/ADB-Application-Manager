package com.bloatware.bingblop;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The app's authorization code: its shape, that it is kept, replaced, repaired when damaged, and how a typed code is compared. */
public class AuthManagerTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

  static final class Mem implements AuthManager.Store {
    final Map<String, String> m = new HashMap<String, String>();
    public String get(String k) { return m.get(k); }
    public void put(String k, String v) { m.put(k, v); }
  }

  public static void main(String[] args) {
    Mem mem = new Mem();
    AuthManager a = new AuthManager(mem, new SecureRandom());
    String code = a.code();
    check("the code is five groups of five of Crockford's characters (no I, L, O, U)", code.matches("[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){4}"));
    check("the same code comes back, and it is kept", code.equals(a.code()) && code.equals(mem.get("auth_code")) && new AuthManager(mem, new SecureRandom()).code().equals(code));
    String fresh = a.refresh();
    check("a new code is different, kept, and the old one is gone", !fresh.equals(code) && fresh.equals(mem.get("auth_code")) && fresh.equals(a.code()));
    mem.put("auth_code", "junk");
    check("a damaged code is replaced by a good one", new AuthManager(mem, new SecureRandom()).code().matches("[0-9A-Z-]{29}"));
    Set<String> seen = new HashSet<String>();
    SecureRandom r = new SecureRandom();
    for (int i = 0; i < 200; i++) seen.add(AuthManager.generate(r));
    check("200 codes are all different", seen.size() == 200);
    check("a typed code is compared without dashes, spaces or case; O is 0, I and L are 1", AuthManager.normalize("abcde-fghjk mnpqr").equals("ABCDEFGHJKMNPQR") && AuthManager.normalize("o0il1").equals("00111") && AuthManager.normalize(null).isEmpty());
    System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
