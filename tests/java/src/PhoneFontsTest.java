package com.bloatware.bingblop;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * PhoneFonts, the Phone font card's reading of the font packages on a Samsung phone (v7.19.1): the name and the Regular / Bold files of a
 * font XML in the shapes FontPackage writes and Samsung's own packages use, XML entities, comments and damaged files; whether Samsung's Font
 * style takes a package by itself; the font magic numbers; and a seeded fuzz (nothing throws, no name or file name it gives is untrusted).
 */
public class PhoneFontsTest {

    static int n = 0, fails = 0;
    static void check(String name, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + name); } }
    static void eq(String name, Object got, Object want) { n++; if (!java.util.Objects.equals(got, want)) { fails++; System.out.println("FAIL " + name + ": got <" + got + "> want <" + want + ">"); } }

    public static void main(String[] a) throws Exception {
        if (a.length >= 3 && a[0].equals("--fuzz")) { fuzz(Long.parseLong(a[1]), Integer.parseInt(a[2])); report(); return; }
        names();
        files();
        recognized();
        magic();
        fuzz(19101L, 4000);
        report();
    }

    static void report() {
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " PhoneFontsTest: " + (n - fails) + "/" + n + " checks");
        if (fails != 0) System.exit(1);
    }

    // the XML FontPackage writes (fontXml) and the shape of Samsung's own packages
    static final String OURS = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<font displayname=\"Inter Display\">\n  <sans>\n"
            + "    <file>\n      <filename>InterDisplay.ttf</filename>\n      <droidname>DroidSans.ttf</droidname>\n    </file>\n"
            + "    <file>\n      <filename>InterDisplay-Bold.ttf</filename>\n      <droidname>DroidSans-Bold.ttf</droidname>\n    </file>\n  </sans>\n</font>\n";
    static final String SAMSUNG = "<?xml version='1.0' encoding='utf-8'?>\r\n<!-- FlipFont <font displayname=\"Not this\"> -->\r\n"
            + "<font displayname='Choco cooky' version=\"2\">\r\n<sans><file><filename>Chococooky.ttf</filename><droidname>DroidSans.ttf</droidname></file>"
            + "<file><filename>Chococooky.ttf</filename><droidname>DroidSans-Bold.ttf</droidname></file></sans>\r\n"
            + "<serif><file><filename>Other.ttf</filename><droidname>DroidSerif-Regular.ttf</droidname></file></serif></font>";

    static void names() {
        eq("our XML's name", PhoneFonts.displayName(OURS), "Inter Display");
        eq("Samsung's name in single quotes, the commented-out tag skipped", PhoneFonts.displayName(SAMSUNG), "Choco cooky");
        eq("an entity XML does not define stays as written", PhoneFonts.displayName("<font displayname=\"t&eacute;\">"), "t&eacute;");
        eq("named and numeric entities resolve", PhoneFonts.displayName("<font displayname=\"Tom &amp; Jerry &#x2014; &#233;t&#233; &lt;b&gt; &quot;x&quot; &apos;\">"),
                "Tom & Jerry — été <b> \"x\" '");
        eq("white space made one space", PhoneFonts.displayName("<font  displayname=\"  A\n\tB  C \">"), "A B C");
        eq("a control character refuses the name", PhoneFonts.displayName("<font displayname=\"A&#7;B\">"), null);
        eq("a raw control character too", PhoneFonts.displayName("<font displayname=\"A\u0001B\">"), null);
        eq("an empty name", PhoneFonts.displayName("<font displayname=\"   \">"), null);
        eq("no displayname", PhoneFonts.displayName("<font version=\"1\">"), null);
        eq("no font tag", PhoneFonts.displayName("<fonts displayname=\"X\">"), null);
        eq("not a part of another attribute", PhoneFonts.displayName("<font olddisplayname=\"X\" displayname=\"Y\">"), "Y");
        eq("upper-case tag and attribute", PhoneFonts.displayName("<FONT DisplayName=\"Up\">"), "Up");
        eq("a surrogate reference", PhoneFonts.displayName("<font displayname=\"&#xD800;\">"), null);
        eq("beyond Unicode", PhoneFonts.displayName("<font displayname=\"&#x110000;\">"), null);
        eq("an emoji by reference", PhoneFonts.displayName("<font displayname=\"Fun &#x1F600;\">"), "Fun 😀");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 70; i++) longName.append("😀");
        String cut = PhoneFonts.displayName("<font displayname=\"" + longName + "\">");
        eq("cut to FontPackage.MAX_NAME characters, never inside a pair", cut == null ? -1 : cut.codePointCount(0, cut.length()), FontPackage.MAX_NAME);
        eq("null XML", PhoneFonts.displayName(null), null);
        StringBuilder huge = new StringBuilder("<font displayname=\"Big\">");
        while (huge.length() <= PhoneFonts.MAX_XML) huge.append("<!-- padding -->");
        eq("an XML over MAX_XML is not read", PhoneFonts.displayName(huge.toString()), null);
        eq("an unclosed comment hides the rest", PhoneFonts.displayName("<!-- <font displayname=\"X\">"), null);
        eq("cleanName of a label", PhoneFonts.cleanName("  Samsung  Sans\n"), "Samsung Sans");
        eq("cleanName of null", PhoneFonts.cleanName(null), null);
    }

    static void files() {
        eq("our XML's files", Arrays.asList(PhoneFonts.sansFiles(OURS)), Arrays.asList("InterDisplay.ttf", "InterDisplay-Bold.ttf"));
        eq("Samsung's: the same file for both", Arrays.asList(PhoneFonts.sansFiles(SAMSUNG)), Arrays.asList("Chococooky.ttf", "Chococooky.ttf"));
        eq("a Regular only", Arrays.asList(PhoneFonts.sansFiles("<font><sans><file><filename>R.ttf</filename><droidname>DroidSans.ttf</droidname></file></sans></font>")),
                Arrays.asList("R.ttf", null));
        eq("the first of each kept", Arrays.asList(PhoneFonts.sansFiles("<sans><file><filename>A.ttf</filename><droidname>droidsans.ttf</droidname></file>"
                + "<file><filename>B.ttf</filename><droidname>DroidSans.ttf</droidname></file></sans>")), Arrays.asList("A.ttf", null));
        eq("a path is not trusted", Arrays.asList(PhoneFonts.sansFiles("<sans><file><filename>../../evil.ttf</filename><droidname>DroidSans.ttf</droidname></file>"
                + "<file><filename>fonts/b.ttf</filename><droidname>DroidSans-Bold.ttf</droidname></file></sans>")), Arrays.asList(null, null));
        eq("a backslash is not trusted", PhoneFonts.sansFiles("<sans><file><filename>a\\b.ttf</filename><droidname>DroidSans.ttf</droidname></file></sans>")[0], null);
        eq("names trimmed, entities resolved", PhoneFonts.sansFiles("<sans><file><filename> A&amp;B.ttf </filename><droidname> DroidSans.ttf </droidname></file></sans>")[0], "A&B.ttf");
        eq("other droidnames ignored", Arrays.asList(PhoneFonts.sansFiles("<sans><file><filename>X.ttf</filename><droidname>DroidSerif.ttf</droidname></file></sans>")), Arrays.asList(null, null));
        eq("a file without a droidname ignored", PhoneFonts.sansFiles("<sans><file><filename>X.ttf</filename></file></sans>")[0], null);
        eq("only the sans part", PhoneFonts.sansFiles("<serif><file><filename>S.ttf</filename><droidname>DroidSans.ttf</droidname></file></serif>")[0], null);
        eq("a commented-out file ignored", PhoneFonts.sansFiles("<sans><!--<file><filename>C.ttf</filename><droidname>DroidSans.ttf</droidname></file>--></sans>")[0], null);
        eq("null", Arrays.asList(PhoneFonts.sansFiles(null)), Arrays.asList(null, null));
        check("asset names: plain is fine", PhoneFonts.isAssetName("Lato-Regular.ttf"));
        check("asset names: .. refused", !PhoneFonts.isAssetName("a..b.ttf") && !PhoneFonts.isAssetName(".") && !PhoneFonts.isAssetName(""));
        check("asset names: a control character refused", !PhoneFonts.isAssetName("a\u0000.ttf") && !PhoneFonts.isAssetName("a\u007f.ttf"));
        StringBuilder longFile = new StringBuilder();
        while (longFile.length() <= PhoneFonts.MAX_FILE_NAME) longFile.append('a');
        check("asset names: too long refused", !PhoneFonts.isAssetName(longFile.toString()));
        check("SamsungFonts.isSuffix: a package suffix", SamsungFonts.isSuffix("samsungone") && SamsungFonts.isSuffix("InterDisplay_3fa9c1"));
        check("SamsungFonts.isSuffix: dots, a digit first, empty refused", !SamsungFonts.isSuffix("foo.bar") && !SamsungFonts.isSuffix("1abc") && !SamsungFonts.isSuffix("") && !SamsungFonts.isSuffix(null));
    }

    static void recognized() {
        Set<String> ref = new HashSet<String>(Arrays.asList("platform", "monotype"));
        check("an older One UI takes every package", PhoneFonts.recognized(true, false, Collections.singleton("mine"), ref));
        check("a package that came with the phone", PhoneFonts.recognized(false, true, Collections.<String>emptySet(), ref));
        check("signed like the phone's own fonts", PhoneFonts.recognized(false, false, new HashSet<String>(Arrays.asList("other", "monotype")), ref));
        check("signed by someone else", !PhoneFonts.recognized(false, false, Collections.singleton("mine"), ref));
        check("signers not read: not recognized", !PhoneFonts.recognized(false, false, Collections.<String>emptySet(), ref) && !PhoneFonts.recognized(false, false, null, ref));
        check("no reference: not recognized", !PhoneFonts.recognized(false, false, Collections.singleton("monotype"), Collections.<String>emptySet()));
        check("an empty signer is no match", !PhoneFonts.recognized(false, false, Collections.singleton(""), new HashSet<String>(Arrays.asList(""))));
    }

    static void magic() {
        check("TrueType 0x00010000", PhoneFonts.isFont(new byte[] { 0, 1, 0, 0, 9 }));
        check("Apple 'true'", PhoneFonts.isFont("true".getBytes()));
        check("OpenType 'OTTO'", PhoneFonts.isFont("OTTO".getBytes()));
        check("a collection is not one font", !PhoneFonts.isFont("ttcf".getBytes()));
        check("WOFF is not", !PhoneFonts.isFont("wOFF".getBytes()) && !PhoneFonts.isFont("wOF2".getBytes()));
        check("short or null", !PhoneFonts.isFont(new byte[] { 0, 1, 0 }) && !PhoneFonts.isFont(null));
    }

    // truncated, bit-flipped and random XML: nothing throws, and what comes out is a trusted name and trusted file names
    static void fuzz(long seed, int rounds) {
        Random r = new Random(seed);
        String[] bases = { OURS, SAMSUNG, "<font displayname=\"&#x1F600; &amp; x\"><sans><file><filename>a.ttf</filename><droidname>DroidSans.ttf</droidname></file></sans></font>" };
        String alphabet = "<>/=\"'&#;x0123456789abcdefABCDEF fontdisplaynamesansfiledroidname.ttf-!\u0000\u0007\n 😀";
        int bad = 0;
        for (int i = 0; i < rounds; i++) {
            StringBuilder sb = new StringBuilder(bases[r.nextInt(bases.length)]);
            int k = r.nextInt(6);
            for (int j = 0; j < k && sb.length() > 0; j++) {
                int at = r.nextInt(sb.length());
                switch (r.nextInt(4)) {
                    case 0: sb.setLength(at); break;
                    case 1: sb.setCharAt(at, alphabet.charAt(r.nextInt(alphabet.length()))); break;
                    case 2: sb.insert(at, alphabet.charAt(r.nextInt(alphabet.length()))); break;
                    default: sb.deleteCharAt(at); break;
                }
            }
            try {
                String name = PhoneFonts.displayName(sb.toString());
                if (name != null && (name.isEmpty() || name.codePointCount(0, name.length()) > FontPackage.MAX_NAME || !name.equals(name.trim()))) bad++;
                if (name != null) for (int c = 0; c < name.length(); c++) if (Character.getType(name.charAt(c)) == Character.CONTROL) bad++;
                for (String f : PhoneFonts.sansFiles(sb.toString())) if (f != null && !PhoneFonts.isAssetName(f)) bad++;
            } catch (Throwable t) {
                bad++;
                System.out.println("fuzz threw on round " + i + ": " + t);
            }
        }
        eq("fuzz (" + rounds + " rounds, seed " + seed + "): no throw, nothing untrusted", bad, 0);
    }
}
