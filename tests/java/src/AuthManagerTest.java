package com.bloatware.bingblop;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/** The Authorization Manager's rules: the code and how it is compared, the switch, the lock after wrong guesses, a new code, the list of recent uses. */
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
    check("the code is five groups of five", code.matches("[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){4}"));
    check("the same code comes back, and it is kept", code.equals(a.code()) && code.equals(mem.get("auth_code")));
    check("two codes are not the same", !AuthManager.generate(new SecureRandom()).equals(AuthManager.generate(new SecureRandom())));

    long t = 1000000L;
    check("the door is shut until the person opens it", a.check(code, t) == AuthManager.Verdict.DISABLED && !a.enabled());
    a.setEnabled(true);
    check("the right code opens it", a.check(code, t) == AuthManager.Verdict.OK);
    check("typed in lower case, without dashes, with spaces, it still opens", a.check(code.toLowerCase().replace("-", " "), t) == AuthManager.Verdict.OK);
    check("O and 0, I and L and 1 are the same character", AuthManager.normalize("o0il1").equals("00111"));
    check("no code at all is a missing code", a.check(null, t) == AuthManager.Verdict.MISSING && a.check("  ", t) == AuthManager.Verdict.MISSING);
    check("a wrong code is wrong", a.check("ABCDE-ABCDE-ABCDE-ABCDE-ABCDE", t) == AuthManager.Verdict.WRONG);
    check("a code one letter short is wrong", a.check(code.substring(0, code.length() - 1), t) == AuthManager.Verdict.WRONG);

    // five wrong guesses in a row lock the door, the right code is turned away too, and the lock lifts by itself
    a.check(code, t);                                       // a right one resets the count
    for (int i = 0; i < AuthManager.MAX_FAILS - 1; i++) check("wrong guess " + (i + 1) + " is only wrong", a.check("WRONG-WRONG-WRONG-WRONG-" + i, t) == AuthManager.Verdict.WRONG);
    check("the fifth wrong guess locks the door", a.check("WRONG", t) == AuthManager.Verdict.WRONG && a.lockedSeconds(t) == 60);
    check("while it is locked even the right code is turned away", a.check(code, t + 1000) == AuthManager.Verdict.LOCKED);
    check("the seconds left count down", a.lockedSeconds(t + 30000) == 30);
    check("a minute later the right code opens it again", a.check(code, t + AuthManager.LOCK_MS + 1) == AuthManager.Verdict.OK && a.lockedSeconds(t + AuthManager.LOCK_MS + 1) == 0);

    // a new code
    String old = code;
    String fresh = a.refresh();
    check("a new code is different and kept", !fresh.equals(old) && fresh.equals(mem.get("auth_code")) && fresh.equals(a.code()));
    check("the old code stops working at once", a.check(old, t) == AuthManager.Verdict.WRONG && a.check(fresh, t) == AuthManager.Verdict.OK);
    for (int i = 0; i < AuthManager.MAX_FAILS; i++) a.check("NOPE", t);
    a.refresh();
    check("a new code also lifts a lock", a.lockedSeconds(t) == 0);

    // the switch
    a.setEnabled(false);
    check("turned off again: nothing is accepted", a.check(a.code(), t) == AuthManager.Verdict.DISABLED);
    check("the switch is remembered", !new AuthManager(mem, new SecureRandom()).enabled());
    a.setEnabled(true);
    check("and so is on", new AuthManager(mem, new SecureRandom()).enabled());
    mem.put("auth_code", "junk");
    check("a damaged code is replaced by a good one", new AuthManager(mem, new SecureRandom()).code().matches("[0-9A-Z-]{29}"));

    // recent uses
    AuthManager b = new AuthManager(new Mem(), new SecureRandom());
    for (int i = 0; i < 13; i++) b.record(i, "ok", "package com.example.app" + i);
    check("the last ten uses are kept, newest first", b.recent().size() == 10 && b.recent().get(0).at == 12 && b.recent().get(9).at == 3);
    b.record(99, "wrong", "a|b\nc");
    check("a line break or a bar in the text cannot split a line", b.recent().get(0).what.equals("a b c") && b.recent().size() == 10);
    b.clearRecent();
    check("the list can be emptied", b.recent().isEmpty());
    check("a long address is cut", new AuthManager(new Mem(), null) != null && cut());

    // what an intent may ask for
    check("package names", AuthManager.validPackage("com.android.settings") && !AuthManager.validPackage("settings") && !AuthManager.validPackage("com.x; rm -rf") && !AuthManager.validPackage(null) && !AuthManager.validPackage("../x.y"));
    check("only safe address kinds", AuthManager.allowedScheme("https") && AuthManager.allowedScheme("market") && AuthManager.allowedScheme("TEL")
        && !AuthManager.allowedScheme("file") && !AuthManager.allowedScheme("content") && !AuthManager.allowedScheme("javascript") && !AuthManager.allowedScheme(null));
    check("grant flags are taken away", AuthManager.strippedFlags(0xFF, new int[]{0x1, 0x2}) == 0xFC);

    System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  static boolean cut() {
    AuthManager m = new AuthManager(new Mem(), null);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 300; i++) sb.append('x');
    m.record(1, "ok", sb.toString());
    return m.recent().get(0).what.length() == 80;
  }
}
