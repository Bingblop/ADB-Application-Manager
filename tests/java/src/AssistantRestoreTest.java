package com.bloatware.bingblop;

/** The saved assistant settings: only plain component names are written back, and the record survives a round trip. */
public class AssistantRestoreTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  public static void main(String[] args) {
    check("empty means unset and is allowed", AssistantRestore.isSafeValue(""));
    check("a component name", AssistantRestore.isSafeValue("com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"));
    check("an inner class name", AssistantRestore.isSafeValue("com.x.y/com.x.y.Main$Inner"));
    check("null is not a value", !AssistantRestore.isSafeValue(null));
    check("backend error text is not a value", !AssistantRestore.isSafeValue("Error: Shizuku is not authorized for this app"));
    check("adb's error text is not a value", !AssistantRestore.isSafeValue("error: device offline"));
    check("a quote is not safe", !AssistantRestore.isSafeValue("a'b"));
    check("a shell separator is not safe", !AssistantRestore.isSafeValue("a/b; rm -rf /") && !AssistantRestore.isSafeValue("a$(id)") && !AssistantRestore.isSafeValue("a`id`"));
    check("a newline is not safe", !AssistantRestore.isSafeValue("a\nb"));
    check("a very long value is not safe", !AssistantRestore.isSafeValue(new String(new char[401]).replace('\0', 'a')));
    String enc = AssistantRestore.encode("com.a/com.a.B", "");
    String[] dec = AssistantRestore.decode(enc);
    check("round trip with an unset second value", dec != null && dec[0].equals("com.a/com.a.B") && dec[1].isEmpty());
    dec = AssistantRestore.decode(AssistantRestore.encode("", ""));
    check("round trip with both unset", dec != null && dec[0].isEmpty() && dec[1].isEmpty());
    check("an unsafe value is not encoded", AssistantRestore.encode("Error: x", "") == null && AssistantRestore.encode("", "a b") == null);
    check("garbage does not decode", AssistantRestore.decode(null) == null && AssistantRestore.decode("") == null && AssistantRestore.decode("v2\na\nb") == null && AssistantRestore.decode("v1\na") == null);
    check("a record with an unsafe value does not decode", AssistantRestore.decode("v1\na'b\n") == null);
    System.out.println(fails == 0 ? "PASS AssistantRestoreTest: " + n + " checks" : "FAILED AssistantRestoreTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
