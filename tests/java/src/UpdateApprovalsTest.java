package com.bloatware.bingblop;

import java.util.HashMap;
import java.util.Map;

/** The yes to "Update all": per app, for one version and source, spent when used, dropped in a mode that is not privileged, expired after 15 minutes. */
public class UpdateApprovalsTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static Map<String, String[]> apps(String... kv) { Map<String, String[]> m = new HashMap<String, String[]>(); for (int i = 0; i + 2 < kv.length + 1; i += 3) m.put(kv[i], new String[]{ kv[i + 1], kv[i + 2] }); return m; }

  public static void main(String[] a) {
    long T0 = 1_000_000L;
    UpdateApprovals u = new UpdateApprovals();
    is("nothing is approved to start with", !u.take("com.a", "2", "github", "adb_tcp", T0));
    u.replace(apps("com.a", "2.0", "github", "com.b", "3.1", "fdroid"), "adb_tcp", T0);
    is("two approvals are held", u.size() == 2);
    is("an approved app, same version and source, is let through", u.take("com.a", "2.0", "github", "adb_tcp", T0 + 1000));
    is("...once: the yes is spent", !u.take("com.a", "2.0", "github", "adb_tcp", T0 + 2000));
    is("another approved app is still there", u.size() == 1 && u.take("com.b", "3.1", "fdroid", "adb_tcp", T0 + 3000));
    u.replace(apps("com.a", "2.0", "github"), "adb_tcp", T0);
    is("a different version asks its own question (a new check found another update)", !u.take("com.a", "2.1", "github", "adb_tcp", T0 + 10));
    u.replace(apps("com.a", "2.0", "github"), "adb_tcp", T0);
    is("a different source asks its own question", !u.take("com.a", "2.0", "fdroid", "adb_tcp", T0 + 10));
    is("a failed take also spends the yes (no second try with the right version)", !u.take("com.a", "2.0", "github", "adb_tcp", T0 + 20));
    u.replace(apps("com.a", "2.0", "github"), "adb_tcp", T0);
    is("an app that was not in the question asks its own", !u.take("com.z", "2.0", "github", "adb_tcp", T0 + 10));
    is("...and does not spend the others", u.take("com.a", "2.0", "github", "adb_tcp", T0 + 20));
    u.replace(apps("com.a", "2.0", "github"), "adb_tcp", T0);
    is("the last millisecond of the 15 minutes still holds", u.take("com.a", "2.0", "github", "adb_tcp", T0 + UpdateApprovals.TTL_MS - 1));
    u.replace(apps("com.a", "2.0", "github"), "adb_tcp", T0);
    is("after 15 minutes it is gone", UpdateApprovals.TTL_MS == 15L * 60 * 1000 && !u.take("com.a", "2.0", "github", "adb_tcp", T0 + UpdateApprovals.TTL_MS));
    u.replace(apps("com.a", "2.0", "github"), "adb_tcp", T0);
    is("a clock that went back never lets an approval through", !u.take("com.a", "2.0", "github", "adb_tcp", T0 - 5));
    u.replace(apps("com.a", "2.0", "github", "com.b", "1", "github"), "adb_tcp", T0);
    is("a mode that is not privileged refuses and drops every approval", !u.take("com.a", "2.0", "github", "standard", T0 + 1) && u.size() == 0);
    is("...so that going back to a privileged mode does not bring them back", !u.take("com.b", "1", "github", "adb_tcp", T0 + 2));
    u.replace(apps("com.a", "2.0", "github", "com.b", "1", "github"), "adb_tcp", T0);
    u.clear();
    is("clear (the queue ended) drops all", u.size() == 0 && !u.take("com.a", "2.0", "github", "adb_tcp", T0 + 1));
    u.replace(apps("com.a", "2.0", "github"), "adb_tcp", T0);
    u.replace(apps("com.b", "1", "github"), "adb_tcp", T0 + 5);
    is("a new question replaces the old approvals", !u.take("com.a", "2.0", "github", "adb_tcp", T0 + 6) && u.take("com.b", "1", "github", "adb_tcp", T0 + 6));
    u.replace(apps("com.a", "", ""), "adb_tcp", T0);
    is("a missing version or source is the empty one (never a null pointer)", u.take("com.a", null, null, "adb_tcp", T0 + 1));
    u.replace(apps("com.a", "2.0", "github"), "adb_tcp", T0);
    is("a missing version does not match a real one", !u.take("com.a", null, "github", "adb_tcp", T0 + 1));
    // the mode the question named: another privileged mode asks again
    u.replace(apps("com.a", "2.0", "github", "com.b", "1", "github"), "adb_tcp", T0);
    is("approved through ADB, the working mode is now Root: it asks its own question", !u.take("com.a", "2.0", "github", "root", T0 + 1));
    is("...and the other approval is still the ADB one", !u.take("com.b", "1", "github", "shizuku", T0 + 2));
    u.replace(apps("com.a", "2.0", "github"), "adb_wireless", T0);
    is("ADB over TCP and Wireless Debugging are different modes", !u.take("com.a", "2.0", "github", "adb_tcp", T0 + 1));
    u.replace(apps("com.a", "2.0", "github"), "shizuku", T0);
    is("the same privileged mode still lets it through", u.take("com.a", "2.0", "github", "shizuku", T0 + 1));
    u.replace(apps("com.a", "2.0", "github"), null, T0);
    is("an approval with no mode is never let through", !u.take("com.a", "2.0", "github", "adb_tcp", T0 + 1));
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
