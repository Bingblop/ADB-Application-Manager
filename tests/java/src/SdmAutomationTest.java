package com.bloatware.bingblop;

public class SdmAutomationTest {
    static int n, fails;
    static void eq(String what, Object got, Object want) {
        n++;
        boolean same = want == null ? got == null : want.equals(got);
        if (!same) {
            fails++;
            System.out.println("FAIL " + what + ": got " + got + " want " + want);
        }
    }

    public static void main(String[] args) {
        String pkg = "com.bloatware.bingblop";
        String me = pkg + "/" + SdmAccessService.class.getName();
        String short1 = pkg + "/." + SdmAccessService.class.getSimpleName();
        String other = "com.other.app/.Service";

        eq("null value", SdmAutomation.isEnabledInSettings(null, pkg), false);
        eq("empty string", SdmAutomation.isEnabledInSettings("", pkg), false);

        eq("exact full match", SdmAutomation.isEnabledInSettings(me, pkg), true);
        eq("exact short match", SdmAutomation.isEnabledInSettings(short1, pkg), true);

        eq("different casing full match", SdmAutomation.isEnabledInSettings(me.toUpperCase(), pkg), true);
        eq("different casing short match", SdmAutomation.isEnabledInSettings(short1.toUpperCase(), pkg), true);

        eq("multiple services full match first", SdmAutomation.isEnabledInSettings(me + ":" + other, pkg), true);
        eq("multiple services full match last", SdmAutomation.isEnabledInSettings(other + ":" + me, pkg), true);
        eq("multiple services full match middle", SdmAutomation.isEnabledInSettings(other + ":" + me + ":another.app/.Service", pkg), true);

        eq("multiple services short match first", SdmAutomation.isEnabledInSettings(short1 + ":" + other, pkg), true);
        eq("multiple services short match last", SdmAutomation.isEnabledInSettings(other + ":" + short1, pkg), true);
        eq("multiple services short match middle", SdmAutomation.isEnabledInSettings(other + ":" + short1 + ":another.app/.Service", pkg), true);

        eq("no match", SdmAutomation.isEnabledInSettings(other, pkg), false);
        eq("no match in multiple", SdmAutomation.isEnabledInSettings(other + ":another.app/.Service", pkg), false);

        eq("partial match not counted", SdmAutomation.isEnabledInSettings(me + "Suffix", pkg), false);
        eq("partial match not counted short", SdmAutomation.isEnabledInSettings(short1 + "Suffix", pkg), false);

        System.out.println((fails == 0 ? "PASS " : "FAIL ") + "SdmAutomationTest: " + n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
