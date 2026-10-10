package com.bloatware.bingblop;

/** What a split's manifest says about it: its split name, the feature module it configures, whether it is a feature module. */
public class SplitInfoTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static String j(String[] a) { return a == null ? "null" : (a[0] == null ? "null" : a[0]) + "|" + a[1] + "|" + a[2]; }

    static final String HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

    public static void main(String[] args) {
        // a base APK has no split attribute
        eq("base", j(SplitInfo.parse(HEAD + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example\" android:versionCode=\"1\">\n  <application android:label=\"x\"/>\n</manifest>")), "null||false");
        // config splits of the base
        eq("abi", j(SplitInfo.parse(HEAD + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example\" split=\"config.arm64_v8a\">\n  <application/>\n</manifest>")), "config.arm64_v8a||false");
        eq("density", j(SplitInfo.parse(HEAD + "<manifest package=\"com.example\" split=\"config.xxhdpi\"><application/></manifest>")), "config.xxhdpi||false");
        eq("language", j(SplitInfo.parse(HEAD + "<manifest package=\"com.example\" split=\"config.en\"><application/></manifest>")), "config.en||false");
        eq("region", j(SplitInfo.parse(HEAD + "<manifest package=\"com.example\" split=\"config.pt_rBR\"/>")), "config.pt_rBR||false");
        // a feature module, and a config split of it
        eq("feature", j(SplitInfo.parse(HEAD + "<manifest xmlns:android=\"x\" package=\"com.example\" split=\"dyn\" android:isFeatureSplit=\"true\"><application/></manifest>")), "dyn||true");
        eq("config split of a feature", j(SplitInfo.parse(HEAD + "<manifest xmlns:android=\"x\" package=\"com.example\" split=\"dyn.config.arm64_v8a\" configForSplit=\"dyn\"><application/></manifest>")), "dyn.config.arm64_v8a|dyn|false");
        eq("feature flag false", j(SplitInfo.parse(HEAD + "<manifest xmlns:android=\"x\" package=\"p\" split=\"config.fr\" android:isFeatureSplit=\"false\"/>")), "config.fr||false");
        eq("empty configForSplit", j(SplitInfo.parse(HEAD + "<manifest package=\"p\" split=\"config.fr\" configForSplit=\"\"/>")), "config.fr||false");
        // attributes on several lines, odd spacing and case
        eq("multi-line", j(SplitInfo.parse(HEAD + "<manifest\n    xmlns:android=\"x\"\n    package=\"p\"\n    split = \"config.de\"\n    android:isFeatureSplit = \"TRUE\">\n</manifest>")), "config.de||true");
        // only the manifest element's own attributes count
        eq("a split on a later element is not the manifest's", j(SplitInfo.parse(HEAD + "<manifest package=\"p\"><application><meta-data split=\"config.xx\"/></application></manifest>")), "null||false");
        eq("splitTypes / splitName are not split", j(SplitInfo.parse(HEAD + "<manifest package=\"p\" android:splitTypes=\"language\" splitName=\"a\"/>")), "null||false");
        eq("a later feature flag is not the manifest's", j(SplitInfo.parse(HEAD + "<manifest package=\"p\" split=\"config.en\"><application android:isFeatureSplit=\"true\"/></manifest>")), "config.en||false");
        // no text
        eq("null", j(SplitInfo.parse(null)), "null");
        eq("empty", j(SplitInfo.parse("")), "null");
        eq("no manifest element: the whole text is read", j(SplitInfo.parse("package=\"p\" split=\"config.en\"")), "config.en||false");
        eq("unterminated tag", j(SplitInfo.parse("<manifest package=\"p\" split=\"config.en\"")), "config.en||false");

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
