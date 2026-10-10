package com.bloatware.bingblop;

import java.util.HashSet;
import java.util.Set;

/** The pre-install checks fail closed: a published checksum that is not a SHA-256, or an APK whose signer cannot be read, stops the install. */
public class InstallGuardsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static Set<String> set(String... a) { Set<String> s = new HashSet<String>(); for (String x : a) s.add(x); return s; }
  public static void main(String[] args) {
    String h = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    check("64 hex digits is a SHA-256", InstallGuards.isSha256(h));
    check("upper case is accepted", InstallGuards.isSha256(h.toUpperCase()));
    check("63 digits is not", !InstallGuards.isSha256(h.substring(1)));
    check("65 digits is not", !InstallGuards.isSha256(h + "0"));
    check("a non-hex letter is not", !InstallGuards.isSha256("g" + h.substring(1)));
    check("null is not", !InstallGuards.isSha256(null));
    check("nothing published passes", InstallGuards.checkHash(null, "x") == null && InstallGuards.checkHash("", "x") == null && InstallGuards.checkHash("  ", "x") == null);
    check("a matching hash passes (case-insensitive)", InstallGuards.checkHash(h, h.toUpperCase()) == null);
    check("a 'sha256:' label is tolerated", InstallGuards.checkHash("sha256:" + h, h) == null && InstallGuards.checkHash("SHA256: " + h, h) == null);
    String bad = InstallGuards.checkHash("deadbeef", h);
    check("a short published value is refused, not skipped", bad != null && bad.contains("not a SHA-256"));
    check("a published value of other text is refused", InstallGuards.checkHash("md5:abc", h) != null);
    String mism = InstallGuards.checkHash(h, h.replace('0', '1'));
    check("a different hash is refused with both prefixes", mism != null && mism.contains("doesn't match") && mism.contains(h.substring(0, 12)));
    check("a missing actual hash is refused", InstallGuards.checkHash(h, null) != null);
    check("normalize strips blanks and label", InstallGuards.normalize("  sha256:" + h + " ").equals(h) && InstallGuards.normalize(null).isEmpty());

    check("not installed: nothing to compare", InstallGuards.checkSigners(set(), set()) == null && InstallGuards.checkSigners(null, set("a")) == null);
    check("same signer passes", InstallGuards.checkSigners(set("a"), set("a")) == null);
    check("one shared signer among several passes", InstallGuards.checkSigners(set("a", "b"), set("b", "c")) == null);
    check("no shared signer is 'different'", "different".equals(InstallGuards.checkSigners(set("a"), set("b"))));
    check("an unreadable new signer (empty) is refused", "unreadable".equals(InstallGuards.checkSigners(set("a"), set())));
    check("a null new signer set is refused", "unreadable".equals(InstallGuards.checkSigners(set("a"), null)));
    System.out.println(n + " checks, " + fails + " failing");
    if (fails > 0) System.exit(1);
  }
}
