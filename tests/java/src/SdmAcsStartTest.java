package com.bloatware.bingblop;

/** The pure part of starting the accessibility service from the app: the decision table and the edits of the enabled-services list. */
public class SdmAcsStartTest {
    static int n, fails;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    static final String ME = "com.bloatware.bingblop/com.bloatware.bingblop.SdmAccessService", SHORT = "com.bloatware.bingblop/.SdmAccessService";
    static final String OTHER = "com.other.app/com.other.app.Service", THIRD = "org.third/org.third.Svc";

    public static void main(String[] a) {
        // ---- the decision table: reason x working mode
        String[] reasons = { null, "NO_CONSENT", "NOT_ENABLED", "NOT_CONNECTED", "SOMETHING_ELSE" };
        SdmAcsStart.Step[] withShell = { SdmAcsStart.Step.NONE, SdmAcsStart.Step.NONE, SdmAcsStart.Step.ENABLE, SdmAcsStart.Step.REBIND, SdmAcsStart.Step.NONE };
        for (int i = 0; i < reasons.length; i++) {
            eq("with a working mode, reason " + reasons[i], SdmAcsStart.decide(reasons[i], true), withShell[i]);
            eq("without a working mode, reason " + reasons[i], SdmAcsStart.decide(reasons[i], false), SdmAcsStart.Step.NONE);
        }

        // ---- reading the list
        eq("an empty setting", SdmAcsStart.parseList("null\n"), "");
        eq("an empty string", SdmAcsStart.parseList(""), "");
        eq("a null output", SdmAcsStart.parseList(null), "");
        eq("a list", SdmAcsStart.parseList(OTHER + ":" + THIRD + "\n"), OTHER + ":" + THIRD);
        eq("an error is not a list", SdmAcsStart.parseList("Error: permission denied"), null);
        eq("an exception is not a list", SdmAcsStart.parseList("java.lang.SecurityException: x\n\tat ..."), null);

        // ---- adding keeps the others
        eq("add to nothing", SdmAcsStart.add("", ME, SHORT), ME);
        eq("add to null", SdmAcsStart.add(null, ME, SHORT), ME);
        eq("add after the others", SdmAcsStart.add(OTHER + ":" + THIRD, ME, SHORT), OTHER + ":" + THIRD + ":" + ME);
        eq("already there: nothing to write", SdmAcsStart.add(OTHER + ":" + ME, ME, SHORT), null);
        eq("already there in the short form: nothing to write", SdmAcsStart.add(SHORT, ME, SHORT), null);
        eq("already there in other case: nothing to write", SdmAcsStart.add(ME.toUpperCase(), ME, SHORT), null);
        eq("a service whose name only starts the same is not it", SdmAcsStart.add(ME + "2", ME, SHORT), ME + "2:" + ME);

        // ---- removing keeps the others, in order
        eq("remove the only one", SdmAcsStart.remove(ME, ME, SHORT), "");
        eq("remove from the middle", SdmAcsStart.remove(OTHER + ":" + ME + ":" + THIRD, ME, SHORT), OTHER + ":" + THIRD);
        eq("remove from the end", SdmAcsStart.remove(OTHER + ":" + ME, ME, SHORT), OTHER);
        eq("remove the short form", SdmAcsStart.remove(SHORT + ":" + OTHER, ME, SHORT), OTHER);
        eq("not there: nothing to write", SdmAcsStart.remove(OTHER, ME, SHORT), null);
        eq("empty list: nothing to write", SdmAcsStart.remove("", ME, SHORT), null);
        eq("stray colons are dropped", SdmAcsStart.remove(OTHER + "::" + ME + ":", ME, SHORT), OTHER);
        eq("off and on again gives the list back", SdmAcsStart.add(SdmAcsStart.remove(OTHER + ":" + ME + ":" + THIRD, ME, SHORT), ME, SHORT), OTHER + ":" + THIRD + ":" + ME);

        // ---- the commands (the same text the SD Maid tab's button has always written)
        eq("read", SdmAcsStart.READ, "settings get secure enabled_accessibility_services");
        eq("write with the master switch", SdmAcsStart.writeCommand(OTHER + ":" + ME, true),
            "settings put secure enabled_accessibility_services '" + OTHER + ":" + ME + "'; settings put secure accessibility_enabled 1");
        eq("write without it", SdmAcsStart.writeCommand("", false), "settings put secure enabled_accessibility_services ''");
        eq("a quote in a name is quoted", SdmAcsStart.writeCommand("a'b", false), "settings put secure enabled_accessibility_services 'a'\\''b'");

        System.out.println((fails == 0 ? "PASS " : "FAIL ") + "SdmAcsStartTest: " + n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
