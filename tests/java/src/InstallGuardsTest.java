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

    check("an installed app whose signers cannot be read is a failure, not a pass", "installed-unreadable".equals(InstallGuards.checkSigners(set(), set("a"))) && "installed-unreadable".equals(InstallGuards.checkSigners(null, set("a"))) && "installed-unreadable".equals(InstallGuards.checkSigners(set(), set())));
    check("same signer passes", InstallGuards.checkSigners(set("a"), set("a")) == null);
    check("one shared signer among several passes", InstallGuards.checkSigners(set("a", "b"), set("b", "c")) == null);
    check("no shared signer is 'different'", "different".equals(InstallGuards.checkSigners(set("a"), set("b"))));
    check("an unreadable new signer (empty) is refused", "unreadable".equals(InstallGuards.checkSigners(set("a"), set())));
    check("a null new signer set is refused", "unreadable".equals(InstallGuards.checkSigners(set("a"), null)));

    // The authorizer the page asks for is not trusted: in standard (Read-Only) mode no privileged one is honoured
    String[] asks = { "adb", "shizuku", "root", "", null, "auto", "bogus", "ADB" };
    for (String a : asks) {
      check("standard mode: '" + a + "' becomes the system installer", "none".equals(InstallGuards.authorizerFor(a, "standard")));
    }
    check("standard mode: 'none' stays none", "none".equals(InstallGuards.authorizerFor("none", "standard")));
    String[] priv = { "adb_tcp", "adb_wireless", "shizuku", "root" };
    for (String m : priv) {
      check(m + " is a privileged mode", InstallGuards.isPrivilegedMode(m));
      check(m + ": adb, shizuku and root are passed on unchanged", "adb".equals(InstallGuards.authorizerFor("adb", m)) && "shizuku".equals(InstallGuards.authorizerFor("shizuku", m)) && "root".equals(InstallGuards.authorizerFor("root", m)));
      check(m + ": 'none' (the person's own choice of the system installer) stays none", "none".equals(InstallGuards.authorizerFor("none", m)));
      check(m + ": no authorizer means the working mode (empty)", "".equals(InstallGuards.authorizerFor(null, m)) && "".equals(InstallGuards.authorizerFor("", m)));
    }
    check("standard is not a privileged mode", !InstallGuards.isPrivilegedMode("standard") && !InstallGuards.isPrivilegedMode("unprivileged") && !InstallGuards.isPrivilegedMode("auto"));
    check("an unknown or missing mode fails closed", "none".equals(InstallGuards.authorizerFor("root", null)) && "none".equals(InstallGuards.authorizerFor("adb", "")) && "none".equals(InstallGuards.authorizerFor("shizuku", "bogus")) && !InstallGuards.isPrivilegedMode(null));
    check("the mode asked about is the mode now only when both are known and equal", InstallGuards.sameMode("adb_tcp", "adb_tcp") && !InstallGuards.sameMode("adb_tcp", "root") && !InstallGuards.sameMode("adb_tcp", "adb_wireless") && !InstallGuards.sameMode("root", "standard") && !InstallGuards.sameMode(null, null) && !InstallGuards.sameMode("root", null) && !InstallGuards.sameMode(null, "root"));
    System.out.println(n + " checks, " + fails + " failing");
    if (fails > 0) System.exit(1);
  }
}
