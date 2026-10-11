package com.bloatware.bingblop;

/** App standby buckets: the buckets a person may set, the commands, and reading what the phone printed. */
public class StandbyBucketsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  public static void main(String[] args) {
    check("the five settable buckets", StandbyBuckets.isSettable("active") && StandbyBuckets.isSettable("working_set") && StandbyBuckets.isSettable("frequent") && StandbyBuckets.isSettable("rare") && StandbyBuckets.isSettable("restricted"));
    check("others are not settable", !StandbyBuckets.isSettable("exempted") && !StandbyBuckets.isSettable("never") && !StandbyBuckets.isSettable("Active") && !StandbyBuckets.isSettable("") && !StandbyBuckets.isSettable(null) && !StandbyBuckets.isSettable("rare; reboot") && !StandbyBuckets.isSettable("10"));
    check("get command", "am get-standby-bucket com.x.y".equals(StandbyBuckets.getCommand("com.x.y")) && StandbyBuckets.getCommand("com.x.y; reboot") == null && StandbyBuckets.getCommand(null) == null && StandbyBuckets.getCommand("") == null);
    check("set command", "am set-standby-bucket com.x.y rare".equals(StandbyBuckets.setCommand("com.x.y", "rare")) && StandbyBuckets.setCommand("com.x.y", "bogus") == null && StandbyBuckets.setCommand("a b", "rare") == null && StandbyBuckets.setCommand("com.x.y", "rare; id") == null && StandbyBuckets.setCommand(null, "rare") == null && StandbyBuckets.setCommand("com.x.y", null) == null);
    check("numbers", "active".equals(StandbyBuckets.parse("10")) && "working_set".equals(StandbyBuckets.parse("20")) && "frequent".equals(StandbyBuckets.parse("30")) && "rare".equals(StandbyBuckets.parse("40")) && "restricted".equals(StandbyBuckets.parse("45")) && "exempted".equals(StandbyBuckets.parse("5")) && "never".equals(StandbyBuckets.parse("50")));
    check("surrounding white space and a newline", "rare".equals(StandbyBuckets.parse("40\n")) && "active".equals(StandbyBuckets.parse("  10  ")));
    check("names", "working_set".equals(StandbyBuckets.parse("WORKING_SET")) && "restricted".equals(StandbyBuckets.parse("restricted\n")) && "never".equals(StandbyBuckets.parse("NEVER")));
    check("anything else gives nothing", StandbyBuckets.parse("").isEmpty() && StandbyBuckets.parse(null).isEmpty() && StandbyBuckets.parse("Error: unknown command").isEmpty() && StandbyBuckets.parse("99").isEmpty() && StandbyBuckets.parse("1000").isEmpty() && StandbyBuckets.parse("-10").isEmpty() && StandbyBuckets.parse("10 20").isEmpty() && StandbyBuckets.parse("Exception occurred while executing 'get-standby-bucket'").isEmpty());
    System.out.println(fails == 0 ? "PASS StandbyBucketsTest: " + n + " checks" : "FAILED StandbyBucketsTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
