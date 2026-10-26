package com.bloatware.bingblop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

/**
 * FontPackage, the Samsung FlipFont package of the Custom Fonts tab: the display name rules, the suffix and package name, versionCode
 * bumps, the FlipFont xml (escaping hostile names, parsed back with the JDK's XML parser), the binary manifest (byte for byte the one aapt2
 * compiled at dev time for every case here: tests/java/fixtures/fonts/fontpackage/expected.txt; checked structure by structure the way
 * Android's parser reads it, decoded with ManifestDecoder, rendered as aapt2's xmltree), the zip (entries, order, stored and 4-byte aligned
 * fonts, fixed dates, no code, no resources, bytes or files, refusals, progress and Cancel), signing with ApkSigner.prepare + signV2 and a
 * throwaway key, and a seeded fuzz of hostile names and font headers. With an Android SDK on this computer (APKSIGNER / AAPT2 or on PATH,
 * or ANDROID_HOME build-tools), apksigner verifies the signed packages and aapt2 reads them; without, those checks are skipped.
 *
 * <p>{@code --dump DIR} writes every manifest case and signed sample packages for {@code make_fixtures.py verify DIR};
 * {@code --fuzz SEED COUNT} runs only the fuzz.
 */
public class FontPackageTest {
  static int fails = 0, skips = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  static void check(String name, boolean ok, String detail) { check(ok || detail == null ? name : name + " -- " + detail, ok); }
  static void skip(String why) { System.out.println("SKIP " + why); skips++; }

  interface Body { void run() throws Exception; }

  /** Passes when {@code body} ends in a FontException with this code (null: any). */
  static void throwsFont(String name, String code, Body body) {
    try {
      body.run();
      check(name, false, "no exception");
    } catch (Sfnt.FontException e) {
      check(name, code == null || code.equals(e.code), e.code + ": " + e.getMessage());
    } catch (Throwable e) {
      check(name, false, e.toString());
    }
  }

  static void section(String title, Body body) {
    System.out.println("== " + title);
    try {
      body.run();
    } catch (Throwable e) {
      check(title + ": ran to the end", false, e.toString() + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : ""));
    }
  }

  static final String PKG = "com.monotype.android.font.MyFont_3fa9c1";
  static final String SAMPLE_NAME = "Inter & \"Friends\" <b>\u00dc</b> 'x' \u6f22\u5b57 \ud83d\ude00\u202e\n";
  static final String SAMPLE_SALT = "fixture";
  static File TMP;

  /** The manifest cases: id, package, versionCode, versionName, label. make_fixtures.py verify compiled each with aapt2. */
  static final String[][] CASES = {
    { "basic", PKG, "1", "1.0", "My Font" },
    { "hostile", PKG, "7", "1.0", "A & B <i>\"q\"</i> 'x'" },
    { "attrname", PKG, "2", "label", "label" },
    { "shared", PKG, "3", "manifest", "android" },
    { "order", PKG, "4", "2.0", "\ue000 private \ud83d\ude00 emoji \uffeb \u00e9" },
    { "cjk", PKG, "5", "3.0", "\u6f22\u5b57 \u30d5\u30a9\u30f3\u30c8" },
    { "reference", PKG, "6", "1.0", "@string/app_name" },
    { "question", PKG, "8", "?attr", "?attr/x" },
    { "latin", PKG, "9", "1.0", "\u00dc\u00c4\u00d6 \u00df \u0141\u00f3d\u017a" },
    { "rtl", PKG, "10", "1.0", "\u05e9\u05dc\u05d5\u05dd font \u0645\u0631\u062d\u0628\u0627" },
    { "backslash", PKG, "11", "1.0", "a\\b \\n \\u0041" },
    { "elements", PKG, "12", "application", "uses-sdk" },
    { "pkglabel", PKG, "13", "1.0", PKG },
    { "long", PKG, "14", "1.0", repeat("\ud83d\ude00a", 40) },
    { "dirty", PKG, "15", "\t1.5\u0000 beta\n", "  Dirty\u0007\n\tName\u202e \u2066x\u2069\ufffe\ud800 " },
    { "maxversion", PKG, "2147483647", "2147483647.0", "Max" },
    { "otherpkg", "com.example.a_b.C9", "1", "1.0", "Other" },
    { "codepoint", PKG, "16", "\ud83d\ude00 1.0", "\ue000 label" },        // U+E000 sorts before U+1F600 by code point, after it by UTF-16 unit
  };

  public static void main(String[] args) throws Exception {
    TMP = Files.createTempDirectory("fontpkg").toFile();
    try {
      if (args.length == 2 && args[0].equals("--dump")) { dump(new File(args[1])); return; }
      if (args.length == 3 && args[0].equals("--fuzz")) {
        fuzz(Long.parseLong(args[1], 16), Integer.parseInt(args[2]));
      } else {
        final File dir = fixtureDir();
        Expected ex = null;
        if (dir == null || !new File(dir, "expected.txt").isFile()) check("the fixtures are found (tests/java/fixtures/fonts/fontpackage)", false);
        else ex = Expected.read(new File(dir, "expected.txt"));
        final Expected e = ex;
        section("display names", FontPackageTest::names);
        section("suffix and package name", FontPackageTest::suffixes);
        section("versionCode", FontPackageTest::versions);
        section("FlipFont xml", FontPackageTest::fontXml);
        section("manifest", () -> manifest(e));
        if (e != null) {
          section("package layout", () -> layout(dir, e));
          section("refusals", () -> refusals(dir));
          section("progress and cancel", () -> progress(dir));
          section("signing", () -> signing(dir, e));
        }
        section("fuzz", () -> fuzz(0xF0E7L, 3000));
      }
      System.out.println(fails == 0 ? "ALL PASS" + (skips > 0 ? " (" + skips + " skipped: no Android SDK tools here)" : "") : fails + " FAILED");
    } finally {
      deleteTree(TMP);
    }
    System.exit(fails == 0 ? 0 : 1);
  }

  // ---------- fixtures and expected.txt ----------

  static File fixtureDir() {
    String p = System.getProperty("fixtures");
    if (p != null && new File(p, "fonts/fontpackage").isDirectory()) return new File(p, "fonts/fontpackage");
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts/fontpackage");
    return null;
  }

  /**
   * expected.txt: "aapt2 VERSION" (the aapt2 that printed the blocks), "font NAME SHA", "manifest ID SHA", then aapt2's xmltree and badging
   * of the sample between begin / end lines.
   */
  static final class Expected {
    final Map<String, String> fonts = new HashMap<String, String>(), manifests = new HashMap<String, String>();
    String xmltree = "", badging = "", aapt2 = "";

    static Expected read(File f) throws IOException {
      Expected e = new Expected();
      String block = null;
      StringBuilder b = new StringBuilder();
      for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1)) {
        if (block != null) {
          if (line.equals(block + "-end")) {
            if (block.equals("xmltree")) e.xmltree = b.toString(); else e.badging = b.toString();
            block = null;
            b.setLength(0);
          } else b.append(line).append('\n');
          continue;
        }
        if (line.equals("xmltree-begin") || line.equals("badging-begin")) { block = line.substring(0, line.indexOf('-')); continue; }
        if (line.startsWith("aapt2 ")) e.aapt2 = line.substring(6).trim();
        String[] p = line.split(" ");
        if (p.length == 3 && p[0].equals("font")) e.fonts.put(p[1], p[2]);
        if (p.length == 3 && p[0].equals("manifest")) e.manifests.put(p[1], p[2]);
      }
      return e;
    }
  }

  static byte[] fixtureFont(File dir, Expected e, String name) throws Exception {
    byte[] b = Files.readAllBytes(new File(dir, name).toPath());
    check("fixture " + name + " is the one make_fixtures.py made", sha(b).equals(e.fonts.get(name)));
    return b;
  }

  // ---------- display names ----------

  static void names() throws Exception {
    check("spaces collapse and trim", FontPackage.cleanName("  My   Font  ").equals("My Font"));
    check("tabs, line breaks and other spaces become one space", FontPackage.cleanName("My\tFont\r\n\u2028X\u00a0\u3000Y").equals("My Font X Y"));
    check("control characters go", FontPackage.cleanName("A\u0000B\u0007C\u007f\u0085D\u009f").equals("ABCD"));
    check("bidi embedding, override and isolate controls go", FontPackage.cleanName("\u202eevil\u202c \u2066a\u2069\u200e\u200f\u061c\ufeff").equals("evil a"));
    check("unpaired surrogates go, pairs stay", FontPackage.cleanName("a\ud800b\udc00c\ud83d\ude00").equals("abc\ud83d\ude00"));
    check("noncharacters go", FontPackage.cleanName("a\ufffeb\uffff\ufdd0\ufdefc\ud83f\udffe").equals("abc"));
    check("a ZWJ emoji sequence stays whole", FontPackage.cleanName("\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67").equals("\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67"));
    check("XML specials stay (they are escaped where they are written)", FontPackage.cleanName("<a href=\"x\">&'").equals("<a href=\"x\">&'"));
    check("cut at 64 characters", FontPackage.cleanName(repeat("x", 100)).equals(repeat("x", 64)));
    String emoji = FontPackage.cleanName(repeat("\ud83d\ude00", 70));
    check("cut at 64 code points, never inside a surrogate pair", emoji.equals(repeat("\ud83d\ude00", 64)), emoji.length() + " chars");
    check("no space left at the cut", FontPackage.cleanName(repeat("ab ", 30)).equals(repeat("ab ", 21) + "a"));
    for (String bad : new String[] { null, "", "   ", "\u0000\u0001", "\u202e\u202c", "\ud800", "\n\t" })
      throwsFont("refused when nothing is left: " + visible(bad), "bad_name", () -> FontPackage.cleanName(bad));
  }

  // ---------- suffix and package name ----------

  static void suffixes() throws Exception {
    String s = FontPackage.suffixFor("My Font", "x");
    check("suffix of \"My Font\" is the name's letters, _ and the first 6 hex of SHA-256(name + salt)", s.equals("MyFont_" + sha("My Font" + "x").substring(0, 6)), s);
    check("the same name and salt give the same suffix", FontPackage.suffixFor("My Font", "x").equals(s));
    check("the name is cleaned before it is hashed", FontPackage.suffixFor("  My \u202e  Font\n", "x").equals(s));
    check("another salt gives another suffix", !FontPackage.suffixFor("My Font", "y").equals(s));
    check("null salt is the empty salt", FontPackage.suffixFor("My Font", null).equals(FontPackage.suffixFor("My Font", "")));
    String[][] folds = {
      { "\u00dcn\u00efc\u00f6d\u00e9 Stra\u00dfe", "UnicodeStrasse" },
      { "\u0152uvre \u00c6r\u00f8 \u0141\u00f3d\u017a \u00de\u00f3r \u0110ak \u0127 \u0131", "OEuvreAEroLodzThorDakhi" },
      { "\ufb01ne \uff26\uff4f\uff4e\uff54", "fineFont" },
      { "\u65e5\u672c\u8a9e\u30d5\u30a9\u30f3\u30c8", "Font" },
      { "", "Font" },
      { null, "Font" },
      { "\ud83d\ude00\ud83d\ude00", "Font" },
      { "123 Go", "Font123Go" },
      { "\u2460\u2461", "Font12" },
      { "../../etc/passwd", "etcpasswd" },
      { "Caf\u00e9 \u00c5ngstr\u00f6m", "CafeAngstrom" },
      { "\u0391\u03b8\u03ae\u03bd\u03b1 Sans", "Sans" },
      { "x" + repeat("y", 60), "x" + repeat("y", 40) },
      { "9" + repeat("a", 60), "Font9" + repeat("a", 36) },
    };
    for (String[] f : folds) {
      String got = FontPackage.suffixFor(f[0], "salt");
      check("suffix name of " + visible(f[0]) + " is " + f[1], got.substring(0, got.length() - 7).equals(f[1]) && FontPackage.isValidSuffix(got), got);
    }
    check("the hash covers names that fold the same", !FontPackage.suffixFor("Caf\u00e9", "s").equals(FontPackage.suffixFor("Cafe", "s")));
    for (String ok : new String[] { s, "A_000000", "a" + repeat("b", 40) + "_abcdef", "X9_0f0f0f" })
      check("valid suffix " + ok, FontPackage.isValidSuffix(ok));
    for (String bad : new String[] { null, "", "_abcdef", "1a_abcdef", "a_abcde", "a_abcdefa", "a_ABCDEF", "a.b_abcdef", "a b_abcdef",
        "a" + repeat("b", 41) + "_abcdef", "a__abcdef", "\u00e9_abcdef", "a_abcdef\n", "foundation" })
      check("not a valid suffix: " + visible(bad), !FontPackage.isValidSuffix(bad));
    check("packageName", FontPackage.packageName(s).equals("com.monotype.android.font." + s));
    throwsFont("packageName refuses a bad suffix", "bad_package", () -> FontPackage.packageName("../x"));
    throwsFont("fontFileBase refuses a bad suffix", "bad_package", () -> FontPackage.fontFileBase("a.b"));
    check("suffixOf gives the suffix back", s.equals(FontPackage.suffixOf(FontPackage.packageName(s))));
    check("suffixOf: Samsung's own fonts are not this tab's", FontPackage.suffixOf("com.monotype.android.font.foundation") == null);
    check("suffixOf: another package", FontPackage.suffixOf("com.example.MyFont_3fa9c1") == null && FontPackage.suffixOf(null) == null);
    check("fontFileBase", FontPackage.fontFileBase("MyFont_3fa9c1").equals("MyFont"));
    for (String ok : new String[] { "a.b", PKG, "com.example.a_b.C9", "A.B_", "a." + repeat("b", 253) })
      check("valid package name " + ok.substring(0, Math.min(40, ok.length())), FontPackage.isValidPackageName(ok));
    for (String bad : new String[] { null, "", "com", "com.", ".com", "com..x", "1com.x", "com.1x", "com.x-y", "com.x y", "com._x", "com.\u00e9",
        "a." + repeat("b", 254) })
      check("not a package name: " + visible(bad == null ? null : bad.substring(0, Math.min(40, bad.length()))), !FontPackage.isValidPackageName(bad));
    List<String> names = FontPackage.entryNames(new FontPackage.Spec("My Font", "MyFont_3fa9c1", 1, new byte[0], new byte[0]));
    check("entry names in file order", names.equals(Arrays.asList("AndroidManifest.xml", "assets/xml/MyFont_3fa9c1.xml", "assets/fonts/MyFont.ttf", "assets/fonts/MyFont-Bold.ttf")), names.toString());
  }

  // ---------- versions ----------

  static void versions() throws Exception {
    int[][] next = { { 0, 0, 1 }, { 1, 0, 2 }, { 1, -1, 2 }, { 3, 7, 8 }, { 9, 2, 10 }, { -5, -5, 1 }, { Integer.MAX_VALUE - 1, 0, Integer.MAX_VALUE } };
    for (int[] n : next) check("nextVersionCode(" + n[0] + ", " + n[1] + ") = " + n[2], FontPackage.nextVersionCode(n[0], n[1]) == n[2]);
    throwsFont("no version above the largest", "bad_version", () -> FontPackage.nextVersionCode(Integer.MAX_VALUE, 0));
    throwsFont("no version above the largest installed one", "bad_version", () -> FontPackage.nextVersionCode(3, Integer.MAX_VALUE));
    check("versionName", FontPackage.versionName(1).equals("1.0") && FontPackage.versionName(12).equals("12.0"));
  }

  // ---------- FlipFont xml ----------

  static void fontXml() throws Exception {
    String both = FontPackage.fontXml("My Font", "MyFont.ttf", "MyFont-Bold.ttf");
    check("Regular + Bold, exactly as the FlipFont format wants", both.equals(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
      + "<font displayname=\"My Font\">\n"
      + "    <sans>\n"
      + "        <file>\n"
      + "            <filename>MyFont.ttf</filename>\n"
      + "            <droidname>DroidSans.ttf</droidname>\n"
      + "        </file>\n"
      + "        <file>\n"
      + "            <filename>MyFont-Bold.ttf</filename>\n"
      + "            <droidname>DroidSans-Bold.ttf</droidname>\n"
      + "        </file>\n"
      + "    </sans>\n"
      + "</font>\n"), both);
    String one = FontPackage.fontXml("My Font", "MyFont.ttf", null);
    check("Regular only", one.equals(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
      + "<font displayname=\"My Font\">\n"
      + "    <sans>\n"
      + "        <file>\n"
      + "            <filename>MyFont.ttf</filename>\n"
      + "            <droidname>DroidSans.ttf</droidname>\n"
      + "        </file>\n"
      + "    </sans>\n"
      + "</font>\n"), one);
    String hostile = FontPackage.fontXml("A & B <i>\"q\"</i> 'x'", "A.ttf", null);
    check("the five XML specials are escaped", hostile.contains("displayname=\"A &amp; B &lt;i&gt;&quot;q&quot;&lt;/i&gt; &apos;x&apos;\""), hostile);
    check("escapeXml", FontPackage.escapeXml("&amp;<>\"'").equals("&amp;amp;&lt;&gt;&quot;&apos;"));
    for (String name : new String[] { "A & B <i>\"q\"</i> 'x'", "]]><!-- -->", "&amp; &#65; &lt;", "x\u0000y\u0001\n\tz", "\u202e\u2066abc\u2069",
        "\ud83d\ude00 \u6f22\u5b57 \u05e9\u05dc\u05d5\u05dd", "a\ud800b\ufffe", SAMPLE_NAME, "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"boom\">]>&e;" }) {
      String xml = FontPackage.fontXml(name, "N.ttf", "N-Bold.ttf");
      Element root = parseXml(xml);
      check("parses with the JDK's parser and reads back the cleaned name: " + visible(name),
          root != null && root.getTagName().equals("font") && root.getAttribute("displayname").equals(FontPackage.cleanName(name)), xml);
    }
    throwsFont("an empty name is refused", "bad_name", () -> FontPackage.fontXml(" \u0000 ", "A.ttf", null));
    for (String bad : new String[] { null, "", "../x.ttf", "a/b.ttf", "x.otf", "x.ttf\n", ".ttf", "-x.ttf", "x y.ttf", "\u00e9.ttf", repeat("a", 65) + ".ttf" }) {
      throwsFont("Regular file name refused: " + visible(bad), "bad_name", () -> FontPackage.fontXml("A", bad, null));
      if (bad != null) throwsFont("Bold file name refused: " + visible(bad), "bad_name", () -> FontPackage.fontXml("A", "A.ttf", bad));
    }
  }

  /** The root element, or null when the text is not well-formed XML. DOCTYPEs refused, as Android's parser has none to read. */
  static Element parseXml(String xml) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setExpandEntityReferences(false);
      DocumentBuilder b = f.newDocumentBuilder();
      b.setErrorHandler(null);
      return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    } catch (Exception e) {
      return null;
    }
  }

  // ---------- manifest ----------

  static byte[] caseManifest(String[] c) throws Exception { return FontPackage.manifest(c[1], Integer.parseInt(c[2]), c[3], c[4]); }

  static void manifest(Expected e) throws Exception {
    for (String[] c : CASES) {
      byte[] m = caseManifest(c);
      Axml t = Axml.parse(m);
      String vn = FontPackage.clean(c[3], FontPackage.MAX_NAME), label = FontPackage.cleanName(c[4]);
      check("manifest " + c[0] + ": Android's structure rules hold, and it says what was asked", t.problem == null
          && t.attr("manifest", "package").equals(c[1]) && t.intAttr("manifest", "versionCode") == Integer.parseInt(c[2])
          && t.attr("manifest", "versionName").equals(vn) && t.intAttr("uses-sdk", "minSdkVersion") == 26
          && t.intAttr("uses-sdk", "targetSdkVersion") == 34 && t.attr("application", "label").equals(label)
          && t.boolAttr("application", "hasCode") == Boolean.FALSE && t.boolAttr("application", "allowBackup") == Boolean.FALSE, t.problem);
      if (e != null) check("manifest " + c[0] + " is byte for byte the one aapt2 compiled at dev time", sha(m).equals(e.manifests.get(c[0])),
          e.manifests.containsKey(c[0]) ? sha(m) : "not in expected.txt (run make_fixtures.py verify)");
      String dec = ManifestDecoder.decodeBytes(m, null);
      check("manifest " + c[0] + ": the app's ManifestDecoder reads the label", dec.contains("android:label=\"" + label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;") + "\""), dec);
    }
    String dec = ManifestDecoder.decodeBytes(caseManifest(CASES[0]), null);
    check("ManifestDecoder's text of the basic manifest", dec.equals(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
      + "<manifest\n"
      + "        xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
      + "        android:versionCode=\"1\"\n"
      + "        android:versionName=\"1.0\"\n"
      + "        package=\"com.monotype.android.font.MyFont_3fa9c1\">\n"
      + "    <uses-sdk\n"
      + "            android:minSdkVersion=\"26\"\n"
      + "            android:targetSdkVersion=\"34\" />\n"
      + "    <application\n"
      + "            android:label=\"My Font\"\n"
      + "            android:hasCode=\"false\"\n"
      + "            android:allowBackup=\"false\" />\n"
      + "</manifest>\n"), dec);
    if (e != null) {
      check("the sample manifest is byte for byte the one aapt2 compiled at dev time", sha(sampleManifest()).equals(e.manifests.get("sample")));
      String sampleTree = Axml.parse(sampleManifest()).xmltree();
      check("the sample manifest renders as aapt2's dump xmltree printed it at dev time", sampleTree.equals(e.xmltree), "\n" + sampleTree + "--- want\n" + e.xmltree);
    }
    throwsFont("package name refused", "bad_package", () -> FontPackage.manifest("com", 1, "1.0", "A"));
    throwsFont("package name with a dash refused", "bad_package", () -> FontPackage.manifest("com.x-y", 1, "1.0", "A"));
    throwsFont("null package refused", "bad_package", () -> FontPackage.manifest(null, 1, "1.0", "A"));
    throwsFont("versionCode 0 refused", "bad_version", () -> FontPackage.manifest(PKG, 0, "1.0", "A"));
    throwsFont("negative versionCode refused", "bad_version", () -> FontPackage.manifest(PKG, -3, "1.0", "A"));
    throwsFont("empty versionName refused", "bad_version", () -> FontPackage.manifest(PKG, 1, "\u0000 ", "A"));
    throwsFont("null versionName refused", "bad_version", () -> FontPackage.manifest(PKG, 1, null, "A"));
    throwsFont("empty label refused", "bad_name", () -> FontPackage.manifest(PKG, 1, "1.0", "\u202e"));
  }

  static byte[] sampleManifest() throws Exception {
    String suffix = FontPackage.suffixFor(SAMPLE_NAME, SAMPLE_SALT);
    return FontPackage.manifest(FontPackage.packageName(suffix), 3, FontPackage.versionName(3), SAMPLE_NAME);
  }

  static FontPackage.Spec sampleSpec(byte[] regular, byte[] bold) {
    FontPackage.Spec s = new FontPackage.Spec(SAMPLE_NAME, FontPackage.suffixFor(SAMPLE_NAME, SAMPLE_SALT), 3, regular, bold);
    return s;
  }

  /**
   * Binary XML read the way Android's ResXMLTree / ResStringPool check it (chunk and header sizes, the UTF-16 pool and its terminators,
   * the resource map, node sizes, attribute layout and order by resource id, balanced namespaces and elements, value types), into a tree.
   * {@code problem} names the first rule broken, or is null.
   */
  static final class Axml {
    String problem;
    final List<String> pool = new ArrayList<String>();
    int[] resMap = new int[0];
    String nsPrefix, nsUri;
    int nsLine;
    final List<Object[]> elements = new ArrayList<Object[]>();     // {depth, name, line, List<int[]> attrs {ns, name, raw, type, data}}

    static Axml parse(byte[] b) {
      Axml t = new Axml();
      try { t.read(b); } catch (RuntimeException e) { t.problem = e.getMessage() == null ? e.toString() : e.getMessage(); }
      return t;
    }

    static int u16(byte[] b, int o) { if (o < 0 || o + 2 > b.length) throw new IllegalStateException("read past the end at " + o); return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    static int u32(byte[] b, int o) { return u16(b, o) | (u16(b, o + 2) << 16); }
    static void rule(boolean ok, String what) { if (!ok) throw new IllegalStateException(what); }

    String str(int i) { rule(i >= 0 && i < pool.size(), "string index " + i + " out of range"); return pool.get(i); }

    void read(byte[] b) {
      rule(u16(b, 0) == 0x0003 && u16(b, 2) == 8 && u32(b, 4) == b.length, "file header");
      int pos = 8, depth = 0;
      boolean sawPool = false, sawMap = false, nsOpen = false, nsClosed = false;
      List<String> open = new ArrayList<String>();
      while (pos < b.length) {
        int type = u16(b, pos), hs = u16(b, pos + 2), size = u32(b, pos + 4);
        rule(hs >= 8 && size >= hs && (long) pos + size <= b.length && size % 4 == 0, "chunk at " + pos + ": sizes");
        if (type == 0x0001) {
          rule(!sawPool && pos == 8, "one string pool, first");
          sawPool = true;
          rule(hs == 28, "pool header size");
          int count = u32(b, pos + 8), styles = u32(b, pos + 12), flags = u32(b, pos + 16), start = u32(b, pos + 20), stylesStart = u32(b, pos + 24);
          rule(styles == 0 && stylesStart == 0 && flags == 0, "pool: UTF-16, no styles");
          rule(start == 28 + 4 * count && start <= size, "pool: strings start right after the offsets");
          for (int i = 0; i < count; i++) {
            int o = pos + start + u32(b, pos + 28 + 4 * i);
            int len = u16(b, o);
            rule(len < 0x8000, "pool: short string length");
            rule(o + 2 + 2 * len + 2 <= pos + size && u16(b, o + 2 + 2 * len) == 0, "pool: string " + i + " ends with a 0 inside the chunk");
            char[] c = new char[len];
            for (int k = 0; k < len; k++) c[k] = (char) u16(b, o + 2 + 2 * k);
            pool.add(new String(c));
          }
          rule(u16(b, pos + size - 2) == 0, "pool: the string data ends with a 0");
        } else if (type == 0x0180) {
          rule(sawPool && !sawMap && hs == 8 && depth == 0 && !nsOpen, "resource map after the pool, before the nodes");
          sawMap = true;
          resMap = new int[(size - 8) / 4];
          rule(resMap.length <= pool.size(), "resource map longer than the pool");
          for (int i = 0; i < resMap.length; i++) {
            resMap[i] = u32(b, pos + 8 + 4 * i);
            rule((resMap[i] >>> 24) == 0x01 && (i == 0 || resMap[i] > resMap[i - 1]), "resource map: framework ids in order");
          }
        } else {
          rule(sawPool && hs == 16 && u32(b, pos + 12) == -1, "node header (size 16, no comment)");
          int line = u32(b, pos + 8);
          if (type == 0x0100 || type == 0x0101) {
            rule(size == 24, "namespace node size");
            String prefix = str(u32(b, pos + 16)), uri = str(u32(b, pos + 20));
            if (type == 0x0100) {
              rule(!nsOpen && depth == 0, "one namespace around the root");
              nsOpen = true; nsPrefix = prefix; nsUri = uri; nsLine = line;
            } else {
              rule(nsOpen && !nsClosed && depth == 0 && prefix.equals(nsPrefix) && uri.equals(nsUri), "namespace end matches its start");
              nsClosed = true;
            }
          } else if (type == 0x0102) {
            rule(nsOpen && !nsClosed && (depth > 0 || elements.isEmpty()), "elements inside the namespace, one root");
            rule(u32(b, pos + 16) == -1, "element without a namespace");
            String name = str(u32(b, pos + 20));
            int aStart = u16(b, pos + 24), aSize = u16(b, pos + 26), count = u16(b, pos + 28);
            rule(aStart == 20 && aSize == 20 && size == 36 + 20 * count, "attribute layout");
            rule(u16(b, pos + 30) == 0 && u16(b, pos + 32) == 0 && u16(b, pos + 34) == 0, "no id / class / style attribute");
            List<int[]> attrs = new ArrayList<int[]>();
            long lastId = -1;
            boolean plain = false;
            for (int i = 0; i < count; i++) {
              int a = pos + 36 + 20 * i;
              int ns = u32(b, a), nm = u32(b, a + 4), raw = u32(b, a + 8), vsize = u16(b, a + 12), res0 = b[a + 14] & 0xff, vtype = b[a + 15] & 0xff, data = u32(b, a + 16);
              rule(vsize == 8 && res0 == 0, "Res_value header");
              rule(ns == -1 || str(ns).equals(nsUri), "attribute namespace is the declared one");
              str(nm);
              long id = nm < resMap.length ? resMap[nm] & 0xFFFFFFFFL : 0;
              rule((ns == -1) == (id == 0), "android: attributes have a resource id, plain ones none");
              if (id != 0) { rule(!plain && id > lastId, "attributes sorted by resource id, plain ones after"); lastId = id; } else plain = true;
              if (vtype == 0x03) rule(raw == data && raw >= 0 && raw < pool.size(), "string value: raw = data = a pool index");
              else if (vtype == 0x10) rule(raw == -1, "integer value without a raw string");
              else if (vtype == 0x12) rule(raw == -1 && (data == 0 || data == -1), "boolean value");
              else rule(false, "value type " + vtype);
              attrs.add(new int[] { ns, nm, raw, vtype, data });
            }
            elements.add(new Object[] { depth, name, line, attrs });
            open.add(name);
            depth++;
          } else if (type == 0x0103) {
            rule(size == 24 && depth > 0 && u32(b, pos + 16) == -1 && str(u32(b, pos + 20)).equals(open.get(open.size() - 1)), "element end matches its start");
            open.remove(open.size() - 1);
            depth--;
          } else {
            rule(false, "chunk type " + type);
          }
        }
        pos += size;
      }
      rule(sawMap && nsClosed && depth == 0 && !elements.isEmpty(), "a complete tree");
    }

    @SuppressWarnings("unchecked")
    private int[] find(String element, String attr) {
      for (Object[] el : elements) {
        if (!el[1].equals(element)) continue;
        for (int[] a : (List<int[]>) el[3]) if (str(a[1]).equals(attr)) return a;
      }
      return null;
    }

    String attr(String element, String attr) { int[] a = problem == null ? find(element, attr) : null; return a == null || a[3] != 0x03 ? "" : str(a[4]); }

    int intAttr(String element, String attr) { int[] a = problem == null ? find(element, attr) : null; return a == null || a[3] != 0x10 ? Integer.MIN_VALUE : a[4]; }

    Boolean boolAttr(String element, String attr) { int[] a = problem == null ? find(element, attr) : null; return a == null || a[3] != 0x12 ? null : a[4] != 0; }

    /** The tree as {@code aapt2 dump xmltree --file AndroidManifest.xml} prints it. */
    @SuppressWarnings("unchecked")
    String xmltree() {
      if (problem != null) return "(" + problem + ")\n";
      StringBuilder b = new StringBuilder();
      b.append("N: ").append(nsPrefix).append('=').append(nsUri).append(" (line=").append(nsLine).append(")\n");
      for (Object[] el : elements) {
        String ind = repeat(" ", 2 + 4 * (Integer) el[0]);
        b.append(ind).append("E: ").append(el[1]).append(" (line=").append(el[2]).append(")\n");
        for (int[] a : (List<int[]>) el[3]) {
          b.append(ind).append("  A: ");
          if (a[0] != -1) b.append(str(a[0])).append(':');
          b.append(str(a[1]));
          if (a[1] < resMap.length) b.append(String.format("(0x%08x)", resMap[a[1]]));
          b.append('=');
          if (a[3] == 0x03) b.append('"').append(str(a[4])).append("\" (Raw: \"").append(str(a[2])).append("\")");
          else if (a[3] == 0x12) b.append(a[4] != 0 ? "true" : "false");
          else b.append(a[4]);
          b.append('\n');
        }
      }
      return b.toString();
    }
  }

  // ---------- package layout ----------

  /** The entry's data offset in the file, from its local header. */
  static long dataOffset(byte[] apk, ZipTool.Entry en) {
    int lho = (int) en.lho;
    int n = (apk[lho + 26] & 0xff) | ((apk[lho + 27] & 0xff) << 8), x = (apk[lho + 28] & 0xff) | ((apk[lho + 29] & 0xff) << 8);
    return lho + 30 + n + x;
  }

  /** Checks a package against its spec; returns the problems (empty: none). */
  static List<String> inspect(File apk, FontPackage.Spec spec, byte[] regular, byte[] bold) throws Exception {
    List<String> bad = new ArrayList<String>();
    byte[] raw = Files.readAllBytes(apk.toPath());
    ZipTool.Archive a = ZipTool.open(apk);
    List<String> names = new ArrayList<String>();
    for (ZipTool.Entry en : a.entries) names.add(en.name);
    List<String> want = FontPackage.entryNames(spec);
    if (!names.equals(want)) bad.add("entries " + names + " instead of " + want);
    for (ZipTool.Entry en : a.entries) {
      boolean font = en.name.startsWith("assets/fonts/");
      if (en.method != (font ? 0 : 8)) bad.add(en.name + " method " + en.method);
      if (font && dataOffset(raw, en) % 4 != 0) bad.add(en.name + " data at " + dataOffset(raw, en) + ", not 4-byte aligned");
      if (en.dosDate != 0x3821 || en.dosTime != 0) bad.add(en.name + " not dated 2008-01-01 00:00");
      if ((en.flags & 0x0008) != 0) bad.add(en.name + " has a data descriptor");
    }
    String vName = spec.versionName != null ? spec.versionName : FontPackage.versionName(spec.versionCode);
    String base = FontPackage.fontFileBase(spec.suffix);
    Map<String, byte[]> wantBytes = new LinkedHashMap<String, byte[]>();
    wantBytes.put("AndroidManifest.xml", FontPackage.manifest(FontPackage.packageName(spec.suffix), spec.versionCode, vName, spec.displayName));
    wantBytes.put("assets/xml/" + spec.suffix + ".xml", FontPackage.fontXml(spec.displayName, base + ".ttf", bold != null ? base + "-Bold.ttf" : null).getBytes(StandardCharsets.UTF_8));
    wantBytes.put("assets/fonts/" + base + ".ttf", regular);
    if (bold != null) wantBytes.put("assets/fonts/" + base + "-Bold.ttf", bold);
    try (ZipFile z = new ZipFile(apk)) {                          // the JDK's reader checks every CRC as it reads
      int count = 0;
      for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
        ZipEntry ze = en.nextElement();
        count++;
        byte[] got = readAll(z.getInputStream(ze));
        byte[] w = wantBytes.get(ze.getName());
        if (w == null || !Arrays.equals(got, w)) bad.add(ze.getName() + ": not the bytes asked for");
      }
      if (count != wantBytes.size()) bad.add(count + " entries in the JDK's view");
    }
    return bad;
  }

  static void layout(File dir, Expected e) throws Exception {
    byte[] regular = fixtureFont(dir, e, "Inter-Regular-sub.ttf"), bold = fixtureFont(dir, e, "Inter-Bold-sub.ttf");
    FontPackage.Spec spec = sampleSpec(regular, bold);
    File out = new File(TMP, "layout/a.apk");                     // the folder does not exist yet: it is made
    FontPackage.writeUnsigned(out, spec);
    List<String> bad = inspect(out, spec, regular, bold);
    check("Regular + Bold: manifest, xml, fonts in order; fonts stored and aligned; dated; bytes and CRCs right", bad.isEmpty(), bad.toString());
    check("no .part file left", !new File(out.getPath() + ".part").exists());
    ZipTool.Archive a = ZipTool.open(out);
    check("no classes.dex, resources.arsc or META-INF", a.find("classes.dex") == null && a.find("resources.arsc") == null && ZipTool.under(a, "META-INF/").isEmpty());
    byte[] first = Files.readAllBytes(out.toPath());
    FontPackage.writeUnsigned(out, spec);                         // over the old file
    check("the same spec gives the same file, byte for byte", Arrays.equals(first, Files.readAllBytes(out.toPath())));

    File rf = new File(TMP, "Regular.ttf"), bf = new File(TMP, "Bold.ttf");
    Files.write(rf.toPath(), regular);
    Files.write(bf.toPath(), bold);
    FontPackage.Spec fromFiles = sampleSpec(null, null);
    fromFiles.regularFile = rf;
    fromFiles.boldFile = bf;
    File out2 = new File(TMP, "files.apk");
    FontPackage.writeUnsigned(out2, fromFiles);
    check("fonts given as files make the same package as bytes", Arrays.equals(first, Files.readAllBytes(out2.toPath())));
    FontPackage.Spec mixed = sampleSpec(regular, null);
    mixed.boldFile = bf;
    FontPackage.writeUnsigned(out2, mixed);
    check("one as bytes, one as a file: the same package", Arrays.equals(first, Files.readAllBytes(out2.toPath())));

    FontPackage.Spec one = sampleSpec(regular, null);
    File out3 = new File(TMP, "one.apk");
    FontPackage.writeUnsigned(out3, one);
    bad = inspect(out3, one, regular, null);
    check("Regular only: three entries, the xml names no Bold file", bad.isEmpty() && !new String(readEntry(out3, "assets/xml/" + one.suffix + ".xml"), StandardCharsets.UTF_8).contains("Bold"), bad.toString());

    // every padding case: the second font's offset depends on the first font's length
    for (int extra = 0; extra < 8; extra++) {
      byte[] r = Arrays.copyOf(regular, regular.length + extra), bo = Arrays.copyOf(bold, bold.length + 3 * extra);
      FontPackage.Spec s = new FontPackage.Spec("Pad " + extra, FontPackage.suffixFor("Pad " + extra, "s"), 1 + extra, r, bo);
      s.versionName = "v" + extra + " beta";
      File o = new File(TMP, "pad" + extra + ".apk");
      FontPackage.writeUnsigned(o, s);
      bad = inspect(o, s, r, bo);
      check("fonts of " + r.length + " and " + bo.length + " bytes: aligned, right bytes, versionName as given", bad.isEmpty()
          && Axml.parse(readEntry(o, "AndroidManifest.xml")).attr("manifest", "versionName").equals("v" + extra + " beta"), bad.toString());
    }
    check("the versionName defaults to versionCode.0", Axml.parse(readEntry(out, "AndroidManifest.xml")).attr("manifest", "versionName").equals("3.0"));
    check("the package name is com.monotype.android.font.<suffix>", Axml.parse(readEntry(out, "AndroidManifest.xml")).attr("manifest", "package").equals("com.monotype.android.font." + spec.suffix));
  }

  static byte[] readEntry(File apk, String name) throws IOException {
    try (ZipFile z = new ZipFile(apk)) {
      ZipEntry ze = z.getEntry(name);
      return ze == null ? null : readAll(z.getInputStream(ze));
    }
  }

  // ---------- refusals ----------

  static void refusals(File dir) throws Exception {
    final byte[] font = Files.readAllBytes(new File(dir, "Inter-Regular-sub.ttf").toPath());
    final File out = new File(TMP, "refused/x.apk");
    refused("a damaged suffix", "bad_package", out, new FontPackage.Spec("A", "../evil_abcdef", 1, font, null));
    refused("no suffix", "bad_package", out, new FontPackage.Spec("A", null, 1, font, null));
    refused("no Regular font", null, out, new FontPackage.Spec("A", "A_abcdef", 1, null, font));
    FontPackage.Spec both = new FontPackage.Spec("A", "A_abcdef", 1, font, null);
    both.regularFile = new File(dir, "Inter-Regular-sub.ttf");
    refused("a Regular font as bytes and as a file", null, out, both);
    FontPackage.Spec twoBold = new FontPackage.Spec("A", "A_abcdef", 1, font, font);
    twoBold.boldFile = new File(dir, "Inter-Bold-sub.ttf");
    refused("a Bold font as bytes and as a file", null, out, twoBold);
    refused("an empty name", "bad_name", out, new FontPackage.Spec(" \u0007 ", "A_abcdef", 1, font, null));
    refused("versionCode 0", "bad_version", out, new FontPackage.Spec("A", "A_abcdef", 0, font, null));
    byte[] rnd = new byte[2000];
    new Random(5).nextBytes(rnd);
    rnd[0] = 0x12;
    refused("random bytes as the Regular font", "not_a_font", out, new FontPackage.Spec("A", "A_abcdef", 1, rnd, null));
    refused("random bytes as the Bold font", "not_a_font", out, new FontPackage.Spec("A", "A_abcdef", 1, font, rnd));
    for (String magic : new String[] { "wOFF", "wOF2", "ttcf", "<?xm", "PK\u0003\u0004" }) {
      byte[] b = Arrays.copyOf(font, font.length);
      byte[] m = magic.getBytes(StandardCharsets.ISO_8859_1);
      System.arraycopy(m, 0, b, 0, 4);
      refused("a file starting " + visible(magic), "not_a_font", out, new FontPackage.Spec("A", "A_abcdef", 1, b, null));
    }
    refused("an empty font", "not_a_font", out, new FontPackage.Spec("A", "A_abcdef", 1, new byte[0], null));
    refused("a font cut to 11 bytes", "not_a_font", out, new FontPackage.Spec("A", "A_abcdef", 1, Arrays.copyOf(font, 11), null));
    FontPackage.Spec missing = new FontPackage.Spec("A", "A_abcdef", 1, null, null);
    missing.regularFile = new File(TMP, "no-such.ttf");
    refused("a missing font file", "not_found", out, missing);
    File big = new File(TMP, "big.ttf");
    try (RandomAccessFile r = new RandomAccessFile(big, "rw")) {      // sparse: nothing is written but the head
      r.write(font, 0, 12);
      r.setLength(Sfnt.MAX_FONT_BYTES + 1L);
    }
    FontPackage.Spec tooBig = new FontPackage.Spec("A", "A_abcdef", 1, null, null);
    tooBig.regularFile = big;
    refused("a font over 96 MB", "too_big", out, tooBig);
    big.delete();
    File isDir = new File(TMP, "adir.apk");
    isDir.mkdirs();
    throwsFont("a folder where the package should go", "write_failed", () -> FontPackage.writeUnsigned(isDir, new FontPackage.Spec("A", "A_abcdef", 1, font, null)));
    File aFile = new File(TMP, "afile");
    Files.write(aFile.toPath(), new byte[1]);
    throwsFont("a file where the folder should be", "write_failed", () -> FontPackage.writeUnsigned(new File(aFile, "x.apk"), new FontPackage.Spec("A", "A_abcdef", 1, font, null)));
    throwsFont("no spec", null, () -> FontPackage.writeUnsigned(out, null));
    Sfnt.FontException full = FontPackage.saveError(new IOException("/data/user/0/x/files/f.apk.part: write failed: ENOSPC (No space left on device)"));
    check("a full disk is its own sentence, without the path", full.code.equals("no_space") && !full.getMessage().contains("/"), full.getMessage());
    Sfnt.FontException denied = FontPackage.saveError(new IOException("/data/x.apk.part: open failed: EACCES (Permission denied)"));
    check("a reason that names a path is left out", denied.code.equals("write_failed") && denied.getMessage().equals("The font package could not be saved."), denied.getMessage());
    check("a plain reason is kept", FontPackage.saveError(new IOException("Stream closed")).getMessage().equals("The font package could not be saved (Stream closed)."));
  }

  static void refused(String what, String code, File out, FontPackage.Spec spec) {
    throwsFont("refused: " + what, code, () -> FontPackage.writeUnsigned(out, spec));
    check("refused: " + what + ": nothing left behind", !out.exists() && !new File(out.getPath() + ".part").exists());
  }

  // ---------- progress and cancel ----------

  static final class Steps implements FontProgress {
    final List<int[]> steps = new ArrayList<int[]>();
    final List<String> stages = new ArrayList<String>();
    int cancelAfter = -1;

    @Override public void step(String stage, int done, int total) { stages.add(stage); steps.add(new int[] { done, total }); }

    @Override public boolean cancelled() { return cancelAfter >= 0 && steps.size() > cancelAfter; }
  }

  static void progress(File dir) throws Exception {
    byte[] regular = Files.readAllBytes(new File(dir, "Inter-Regular-sub.ttf").toPath());
    byte[] big = Arrays.copyOf(regular, 3 * 1024 * 1024 + 5);         // a 3 MB "font": the sfnt head is all the package looks at
    Steps s = new Steps();
    File out = new File(TMP, "progress.apk");
    FontPackage.writeUnsigned(out, new FontPackage.Spec("P", "P_abcdef", 1, big, regular), s);
    int total = big.length + regular.length;
    boolean mono = true;
    for (int i = 1; i < s.steps.size(); i++) if (s.steps.get(i)[0] < s.steps.get(i - 1)[0]) mono = false;
    boolean shape = !s.steps.isEmpty() && s.steps.get(0)[0] == 0 && s.steps.get(s.steps.size() - 1)[0] == total;
    for (int[] st : s.steps) if (st[1] != total || st[0] > st[1]) shape = false;
    for (String st : s.stages) if (!st.equals("package")) shape = false;
    check("progress: stage \"package\", 0 to the font bytes, never backwards, in steps", mono && shape && s.steps.size() >= 10, s.steps.size() + " steps");
    File rf = new File(TMP, "big.ttf");
    Files.write(rf.toPath(), big);
    FontPackage.Spec files = new FontPackage.Spec("P", "P_abcdef", 1, null, null);
    files.regularFile = rf;
    Steps s2 = new Steps();
    FontPackage.writeUnsigned(out, files, s2);
    check("progress from a file counts both reads (the CRC, then the copy)", s2.steps.get(s2.steps.size() - 1)[0] == 2 * big.length && s2.steps.get(0)[1] == 2 * big.length);
    for (int after : new int[] { 0, 1, 5 }) {
      Steps c = new Steps();
      c.cancelAfter = after;
      out.delete();
      throwsFont("Cancel after " + after + " steps", "cancelled", () -> FontPackage.writeUnsigned(out, new FontPackage.Spec("P", "P_abcdef", 1, big, regular), c));
      check("Cancel after " + after + " steps leaves nothing", !out.exists() && !new File(out.getPath() + ".part").exists());
    }
    Steps c = new Steps();
    c.cancelAfter = 3;
    throwsFont("Cancel while a font file is read", "cancelled", () -> FontPackage.writeUnsigned(out, files, c));
    rf.delete();
  }

  // ---------- signing ----------

  static void signing(File dir, Expected e) throws Exception {
    byte[] regular = fixtureFont(dir, e, "Inter-Regular-sub.ttf"), bold = fixtureFont(dir, e, "Inter-Bold-sub.ttf");
    KeyStore.PrivateKeyEntry key = throwawayKey(TMP);
    if (key == null) { skip("signing: no keytool to make a throwaway key"); return; }
    FontPackage.Spec spec = sampleSpec(regular, bold);
    File unsigned = new File(TMP, "sign-unsigned.apk"), prepared = new File(TMP, "sign-prepared.apk"), signed = new File(TMP, "sign.apk");
    FontPackage.writeUnsigned(unsigned, spec);
    ApkSigner.prepare(unsigned, prepared);
    List<String> bad = inspect(prepared, spec, regular, bold);
    check("ApkSigner.prepare keeps the layout (fonts stored and aligned, same bytes)", bad.isEmpty(), bad.toString());
    ApkSigner.signV2(prepared, signed, key.getPrivateKey(), (X509Certificate) key.getCertificate());
    bad = inspect(signed, spec, regular, bold);
    check("the signed package keeps the layout", bad.isEmpty(), bad.toString());
    ZipTool.Archive a = ZipTool.open(signed);
    byte[] raw = Files.readAllBytes(signed.toPath());
    check("the signed package has a v2 signing block in front of the central directory",
        new String(raw, (int) a.cdOffset - 16, 16, StandardCharsets.ISO_8859_1).equals("APK Sig Block 42"));
    File direct = new File(TMP, "sign-direct.apk");
    ApkSigner.signV2(unsigned, direct, key.getPrivateKey(), (X509Certificate) key.getCertificate());
    check("signV2 straight on writeUnsigned's file works too (it is prepared already)", inspect(direct, spec, regular, bold).isEmpty());

    String apksigner = tool("apksigner", "APKSIGNER");
    if (apksigner == null) skip("apksigner checks: no apksigner (set APKSIGNER or ANDROID_HOME, or put build-tools on PATH)");
    else {
      String v = run(apksigner, "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", signed.getAbsolutePath());
      check("apksigner verify --min-sdk-version 26: verifies with v2, one signer, the throwaway key", lastExit == 0 && v.startsWith("Verifies")
          && v.contains("Verified using v2 scheme (APK Signature Scheme v2): true") && v.contains("Number of signers: 1")
          && v.contains("certificate SHA-256 digest: " + ApkSigner.certSha256((X509Certificate) key.getCertificate())), v);
      String v2 = run(apksigner, "verify", "--verbose", signed.getAbsolutePath());
      check("apksigner verify with minSdkVersion read from the manifest by apksigner's own parser", lastExit == 0 && v2.startsWith("Verifies"), v2);
      String v3 = run(apksigner, "verify", "--verbose", "--min-sdk-version", "26", direct.getAbsolutePath());
      check("apksigner verify of the directly signed package", lastExit == 0 && v3.startsWith("Verifies"), v3);
      ZipTool.Entry font = a.find("assets/fonts/" + FontPackage.fontFileBase(spec.suffix) + ".ttf");
      byte[] t = raw.clone();
      t[(int) dataOffset(raw, font) + 100] ^= 0x40;
      File tampered = new File(TMP, "tampered.apk");
      Files.write(tampered.toPath(), t);
      String v4 = run(apksigner, "verify", "--verbose", "--min-sdk-version", "26", tampered.getAbsolutePath());
      check("apksigner rejects a package with one font byte changed", lastExit != 0 && v4.contains("DOES NOT VERIFY"), v4);
    }
    String aapt2 = tool("aapt2", "AAPT2");
    if (aapt2 == null) skip("aapt2 checks: no aapt2 (set AAPT2 or ANDROID_HOME, or put build-tools on PATH)");
    else {
      String suffix = spec.suffix;
      String badging = run(aapt2, "dump", "badging", signed.getAbsolutePath());
      check("aapt2 dump badging: package, versions, SDK levels", badging.contains("package: name='com.monotype.android.font." + suffix + "' versionCode='3' versionName='3.0'")
          && badging.contains("minSdkVersion:'26'") && badging.contains("targetSdkVersion:'34'"), badging);
      String tree = run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", signed.getAbsolutePath());
      check("aapt2 dump xmltree: no code, the label as a literal string", tree.contains("hasCode(0x0101000c)=false")
          && tree.contains("label(0x01010001)=\"" + FontPackage.cleanName(SAMPLE_NAME) + "\""), tree);
      String version = run(aapt2, "version").trim();
      if (!version.equals(e.aapt2)) skip("aapt2's exact text: this is " + version + ", expected.txt was printed by " + e.aapt2);
      else {
        check("aapt2 dump badging prints what it printed at dev time", badging.trim().equals(e.badging.trim()), badging);
        check("aapt2 dump xmltree is this suite's rendering of the manifest", tree.trim().equals(Axml.parse(readEntry(signed, "AndroidManifest.xml")).xmltree().trim()), tree);
      }
    }
  }

  /** A throwaway RSA key made with the JDK's keytool (the suite's "keys" folder when the runner made one). */
  static KeyStore.PrivateKeyEntry throwawayKey(File tmp) throws Exception {
    File p12 = null;
    String keys = System.getProperty("keys");
    if (keys != null && new File(keys, "rsa.p12").isFile()) p12 = new File(keys, "rsa.p12");
    if (p12 == null) {
      File kt = new File(System.getProperty("java.home"), "bin/keytool");
      String keytool = kt.canExecute() ? kt.getPath() : onPath("keytool");
      if (keytool == null) return null;
      p12 = new File(tmp, "rsa.p12");
      run(keytool, "-genkeypair", "-storetype", "PKCS12", "-keystore", p12.getPath(), "-storepass", "password", "-keypass", "password", "-validity", "3650",
          "-alias", "rsa", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=fontpackage test");
      if (!p12.isFile()) return null;
    }
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(p12)) { ks.load(in, "password".toCharArray()); }
    return (KeyStore.PrivateKeyEntry) ks.getEntry("rsa", new KeyStore.PasswordProtection("password".toCharArray()));
  }

  /** An Android SDK tool: -D{name}, the environment variable, PATH, then the newest ANDROID_HOME / ANDROID_SDK_ROOT build-tools. */
  static String tool(String name, String env) {
    String[] direct = { System.getProperty(name), System.getenv(env) };
    for (String d : direct) if (d != null && new File(d).canExecute()) return d;
    String p = onPath(name);
    if (p != null) return p;
    for (String home : new String[] { System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT") }) {
      if (home == null) continue;
      String[] versions = new File(home, "build-tools").list();
      if (versions == null) continue;
      Arrays.sort(versions);
      for (int i = versions.length - 1; i >= 0; i--) {
        File f = new File(new File(new File(home, "build-tools"), versions[i]), name);
        if (f.canExecute()) return f.getPath();
      }
    }
    return null;
  }

  static String onPath(String name) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String d : path.split(File.pathSeparator)) {
      File f = new File(d, name);
      if (f.isFile() && f.canExecute()) return f.getPath();
    }
    return null;
  }

  static int lastExit;

  /** Runs a command; its output (stdout and stderr) is returned and its exit code left in {@link #lastExit}. */
  static String run(String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    byte[] out = readAll(p.getInputStream());
    lastExit = p.waitFor();
    StringBuilder sb = new StringBuilder();
    for (String line : new String(out, StandardCharsets.UTF_8).split("\n")) if (!line.startsWith("Picked up ")) sb.append(line).append('\n');
    return sb.toString();
  }

  // ---------- fuzz ----------

  /** Code points a hostile name is made of. */
  static final int[] NASTY = { 0, 1, 7, 9, 10, 13, 0x1f, ' ', '"', '&', '\'', '<', '>', '\\', '@', '?', ']', 0x7f, 0x85, 0xa0, 0xe9, 0x300, 0x61c,
      0x200b, 0x200d, 0x200e, 0x2028, 0x202a, 0x202e, 0x2066, 0x2069, 0x3000, 0xd800, 0xdbff, 0xdc00, 0xdfff, 0xe000, 0xfdd0, 0xfeff, 0xfffd,
      0xfffe, 0xffff, 0x1f600, 0x1f468, 0x1fffe, 0x10ffff, 0x6f22, 0x5d0, 0x645, 0xfb01, 0xff21, 0x2460, 0xdf, 0x141 };

  static String randomName(Random r) {
    StringBuilder b = new StringBuilder();
    int n = r.nextInt(8) == 0 ? r.nextInt(200) : r.nextInt(24);
    for (int i = 0; i < n; i++) {
      int k = r.nextInt(4);
      if (k == 0) b.append((char) ('a' + r.nextInt(26)));
      else if (k == 1) b.append((char) r.nextInt(0x10000));            // lone surrogates included
      else {
        int cp = NASTY[r.nextInt(NASTY.length)];
        if (cp >= 0xd800 && cp <= 0xdfff) b.append((char) cp); else b.appendCodePoint(cp);
      }
    }
    return b.toString();
  }

  static boolean cleanShape(String s) {
    if (s.isEmpty() || s.startsWith(" ") || s.endsWith(" ") || s.contains("  ") || s.codePointCount(0, s.length()) > FontPackage.MAX_NAME) return false;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      i += Character.charCount(cp);
      int t = Character.getType(cp);
      if (t == Character.CONTROL || t == Character.SURROGATE || (cp & 0xfffe) == 0xfffe || (cp >= 0xfdd0 && cp <= 0xfdef)) return false;
      if ((cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069) || cp == 0xfeff) return false;
      if (cp != ' ' && (Character.isWhitespace(cp) || Character.isSpaceChar(cp))) return false;
    }
    return true;
  }

  static void fuzz(long seed, int count) throws Exception {
    Random r = new Random(seed);
    int names = 0, refused = 0, other = 0;
    String firstOther = null;
    long t0 = System.nanoTime();
    for (int i = 0; i < count; i++) {
      String name = randomName(r);
      try {
        String suffix = FontPackage.suffixFor(name, Integer.toString(i));
        if (!FontPackage.isValidSuffix(suffix)) throw new IllegalStateException("suffix " + suffix);
        String clean;
        try {
          clean = FontPackage.cleanName(name);
        } catch (Sfnt.FontException e) {
          if (!e.code.equals("bad_name") || !FontPackage.clean(name, FontPackage.MAX_NAME).isEmpty()) throw new IllegalStateException("refused a usable name");
          refused++;
          continue;
        }
        if (!cleanShape(clean)) throw new IllegalStateException("cleaned name " + visible(clean));
        if (!FontPackage.cleanName(clean).equals(clean)) throw new IllegalStateException("cleaning twice changes " + visible(clean));
        Element root = parseXml(FontPackage.fontXml(name, "A.ttf", "A-Bold.ttf"));
        if (root == null || !root.getAttribute("displayname").equals(clean)) throw new IllegalStateException("xml of " + visible(name));
        byte[] m = FontPackage.manifest(FontPackage.packageName(suffix), 1 + r.nextInt(1000), randomName(r) + "1", name);
        Axml t = Axml.parse(m);
        if (t.problem != null || !t.attr("application", "label").equals(clean)) throw new IllegalStateException("manifest of " + visible(name) + ": " + t.problem);
        ManifestDecoder.decodeBytes(m, null);
        names++;
      } catch (Throwable e) {
        other++;
        if (firstOther == null) firstOther = visible(name) + ": " + e;
      }
    }
    check("fuzz " + count + " hostile names (seed " + Long.toHexString(seed) + "): valid suffix, clean name, XML and manifest that parse, or a bad_name refusal", other == 0,
        firstOther + " (" + other + " bad)");
    // font heads: random first bytes and lengths, sometimes a real magic
    int heads = 0, headOther = 0;
    String firstHead = null;
    int[] magics = { Sfnt.TRUETYPE, Sfnt.OTTO, Sfnt.TRUE_MAC, 0x74746366, 0x774F4646 };
    File out = new File(TMP, "fuzz.apk");
    for (int i = 0; i < Math.max(50, count / 10); i++) {
      byte[] b = new byte[r.nextInt(40)];
      r.nextBytes(b);
      if (b.length >= 4 && r.nextBoolean()) Sfnt.w32(b, 0, magics[r.nextInt(magics.length)]);
      try {
        FontPackage.writeUnsigned(out, new FontPackage.Spec("F", "F_abcdef", 1, b, r.nextBoolean() ? b : null));
        int magic = b.length >= 4 ? ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff) : 0;
        if (b.length < 12 || (magic != Sfnt.TRUETYPE && magic != Sfnt.OTTO && magic != Sfnt.TRUE_MAC)) throw new IllegalStateException("accepted a non-font");
        heads++;
      } catch (Sfnt.FontException e) {
        if (!e.code.equals("not_a_font")) { headOther++; if (firstHead == null) firstHead = e.code + ": " + e.getMessage(); }
        heads++;
      } catch (Throwable e) {
        headOther++;
        if (firstHead == null) firstHead = e.toString();
      }
    }
    check("fuzz " + heads + " font heads: a package or a not_a_font refusal, nothing else", headOther == 0, firstHead);
    long ms = (System.nanoTime() - t0) / 1000000;
    check("fuzz finished in seconds (" + ms + " ms; " + names + " names packaged, " + refused + " refused)", ms < 60000);
  }

  // ---------- dump for make_fixtures.py verify ----------

  static void dump(File dir) throws Exception {
    dir.mkdirs();
    File fixtures = fixtureDir();
    StringBuilder tsv = new StringBuilder();
    for (String[] c : CASES) {
      byte[] m = caseManifest(c);
      Files.write(new File(dir, c[0] + ".axml").toPath(), m);
      tsv.append(c[0]).append('\t').append(c[1]).append('\t').append(c[2]).append('\t')
         .append(hex(FontPackage.clean(c[3], FontPackage.MAX_NAME).getBytes(StandardCharsets.UTF_8))).append('\t')
         .append(hex(FontPackage.cleanName(c[4]).getBytes(StandardCharsets.UTF_8))).append('\n');
    }
    Files.write(new File(dir, "sample.axml").toPath(), sampleManifest());
    tsv.append("sample\t").append(FontPackage.packageName(FontPackage.suffixFor(SAMPLE_NAME, SAMPLE_SALT))).append("\t3\t").append(hex("3.0".getBytes(StandardCharsets.UTF_8)))
       .append('\t').append(hex(FontPackage.cleanName(SAMPLE_NAME).getBytes(StandardCharsets.UTF_8))).append('\n');
    Files.write(new File(dir, "cases.tsv").toPath(), tsv.toString().getBytes(StandardCharsets.UTF_8));
    byte[] regular = Files.readAllBytes(new File(fixtures, "Inter-Regular-sub.ttf").toPath()), bold = Files.readAllBytes(new File(fixtures, "Inter-Bold-sub.ttf").toPath());
    KeyStore.PrivateKeyEntry key = throwawayKey(dir);
    if (key == null) throw new IOException("keytool is needed to sign the samples");
    String[][] samples = { { "sample", "b" }, { "sample-regular-only", "" } };
    for (String[] s : samples) {
      File unsigned = new File(dir, s[0] + "-unsigned.apk"), prepared = new File(dir, s[0] + "-prepared.apk");
      FontPackage.writeUnsigned(unsigned, sampleSpec(regular, s[1].isEmpty() ? null : bold));
      ApkSigner.prepare(unsigned, prepared);
      ApkSigner.signV2(prepared, new File(dir, s[0] + ".apk"), key.getPrivateKey(), (X509Certificate) key.getCertificate());
      prepared.delete();
    }
    Files.write(new File(dir, "sample.xmltree.txt").toPath(), Axml.parse(sampleManifest()).xmltree().getBytes(StandardCharsets.UTF_8));
    Files.write(new File(dir, "sample.name.txt").toPath(), FontPackage.cleanName(SAMPLE_NAME).getBytes(StandardCharsets.UTF_8));
    System.out.println("wrote " + dir);
  }

  // ---------- helpers ----------

  static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

  static byte[] readAll(InputStream in) throws IOException {
    try {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      return bo.toByteArray();
    } finally {
      in.close();
    }
  }

  static String sha(byte[] b) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(b)); }

  static String sha(String s) throws Exception { return sha(s.getBytes(StandardCharsets.UTF_8)); }

  static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x & 0xff)); return sb.toString(); }

  /** A string with everything outside printable ASCII as \\uXXXX, for check names. */
  static String visible(String s) {
    if (s == null) return "null";
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length() && i < 60; i++) {
      char c = s.charAt(i);
      if (c >= 0x20 && c < 0x7f) b.append(c); else b.append(String.format("\\u%04x", (int) c));
    }
    return b.append(s.length() > 60 ? "...\"" : "\"").toString();
  }

  static void deleteTree(File f) {
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) deleteTree(k);
    f.delete();
  }
}
