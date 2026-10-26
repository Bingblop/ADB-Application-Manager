package com.bloatware.bingblop;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * FontInspect (what a picked font is: names, weight, outlines, characters, colour format, axes, named instances, the Regular / Bold
 * guesses, warnings). Against every font under tests/java/fixtures/fonts (this suite's folder and the other suites'): every field as
 * fontTools reads it (fontinspect/expected.txt, keyed by the font file's SHA-256, written by make_fixtures.py expect). Hand-built fonts
 * cover the cases the fixtures do not have (no typographic names, Mac-only names, no OS/2, restricted embedding, COLR without CPAL or
 * damaged, sbix / SVG / CBDT next to outlines, bitmap-only fonts, avar 2, unnamed instances, long license texts, refused files); the guess
 * rule gets its own cases; a seeded fuzz runs inspect on damaged fonts.
 *
 * <p>{@code --dump FONT...} prints the lines this suite compares for each font, for {@code make_fixtures.py check}; {@code --fuzz SEED
 * COUNT} runs only the fuzz.
 */
public class FontInspectTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  static void check(String name, boolean ok, String detail) { check(ok || detail == null ? name : name + " -- " + detail, ok); }

  interface Body { void run() throws Exception; }

  /** Passes when {@code body} ends in a FontException with this code (null: any) whose message contains {@code text} (null: any). */
  static void throwsFont(String name, String code, String text, Body body) {
    try {
      body.run();
      check(name, false, "no exception");
    } catch (Sfnt.FontException e) {
      check(name, (code == null || code.equals(e.code)) && (text == null || e.getMessage().contains(text)), e.code + ": " + e.getMessage());
    } catch (Throwable e) {
      check(name, false, e.toString());
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length >= 2 && args[0].equals("--dump")) {
      PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");   // names are not ASCII
      for (int i = 1; i < args.length; i++) {
        byte[] data = Files.readAllBytes(new File(args[i]).toPath());
        out.println("font " + args[i] + " " + sha(data));
        for (String l : lines(data)) out.println(l);
        out.println("end");
      }
      return;
    }
    File fonts = fontsDir();
    if (args.length == 3 && args[0].equals("--fuzz")) {
      fuzz(fonts, Long.parseLong(args[1], 16), Integer.parseInt(args[2]));
      System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
      System.exit(fails == 0 ? 0 : 1);
    }
    if (fonts == null || !new File(fonts, "fontinspect/expected.txt").isFile()) {
      check("the fixtures are found (tests/java/fixtures/fonts/fontinspect)", false);
    } else {
      Map<String, List<String>> ex = expected(new File(fonts, "fontinspect/expected.txt"));
      section("fixture fonts against fontTools", () -> fixtureFonts(fonts, ex));
      section("the fixtures this suite is about", () -> ownFixtures(fonts));
      section("names", () -> names(fonts));
      section("weight, width, style and embedding", () -> styles(fonts));
      section("colour formats", () -> colours(fonts));
      section("refused files", () -> refused(fonts));
      section("variable fonts", () -> variable(fonts));
    }
    section("the Regular / Bold guess", FontInspectTest::guesses);
    if (fonts != null) section("fuzz", () -> fuzz(fonts, 0xF0A7L, 3000));
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  static void section(String name, Body body) {
    try {
      body.run();
    } catch (Throwable e) {
      check(name + ": ran to the end", false, e.toString() + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : ""));
    }
  }

  // ---------- fixtures and expected.txt ----------

  static File fontsDir() {
    String p = System.getProperty("fixtures");
    if (p != null && new File(p, "fonts/fontinspect").isDirectory()) return new File(p, "fonts");
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts");
    return null;
  }

  /** expected.txt: "font REL SHA", the lines {@link #lines} writes for it, "end". By file SHA-256. */
  static Map<String, List<String>> expected(File f) throws IOException {
    Map<String, List<String>> out = new HashMap<>();
    List<String> cur = null;
    for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
      if (l.isEmpty() || l.startsWith("#")) continue;
      if (l.startsWith("font ")) { cur = new ArrayList<>(); out.put(l.split(" ")[2], cur); }
      else if (l.equals("end")) cur = null;
      else if (cur != null) cur.add(l);
    }
    return out;
  }

  static List<File> fontFiles(File dir) {
    List<File> out = new ArrayList<>();
    File[] list = dir.listFiles();
    if (list == null) return out;
    List<File> sorted = new ArrayList<>();
    Collections.addAll(sorted, list);
    Collections.sort(sorted);
    for (File f : sorted) {
      if (f.isDirectory()) out.addAll(fontFiles(f));
      else if (f.getName().toLowerCase().endsWith(".ttf") || f.getName().toLowerCase().endsWith(".otf")) out.add(f);
    }
    return out;
  }

  static String rel(File fonts, File f) { return fonts.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/'); }

  static String sha(byte[] b) throws Exception {
    StringBuilder s = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x & 0xff));
    return s.toString();
  }

  static byte[] fixture(File fonts, String rel) throws IOException { return Files.readAllBytes(new File(fonts, rel).toPath()); }

  static FontInspect.Info inspect(byte[] data) throws Sfnt.FontException { return FontInspect.inspect(data); }

  /** The font after {@code edit} changed its tables, written out again. */
  interface Edit { void apply(Sfnt f) throws Exception; }

  static byte[] edited(byte[] data, Edit e) throws Exception {
    Sfnt f = Sfnt.parse(data).copy();
    e.apply(f);
    return f.toBytes();
  }

  static byte[] patched(byte[] table, int off, int u16) {
    byte[] t = table.clone();
    Sfnt.w16(t, off, u16);
    return t;
  }

  // ---------- the lines compared with make_fixtures.py ----------

  /** What the suite compares: every field of the Info in a fixed order (numbers as exact decimals), or "error CODE". */
  static List<String> lines(byte[] data) {
    List<String> out = new ArrayList<>();
    FontInspect.Info in;
    try {
      in = FontInspect.inspect(data);
    } catch (Sfnt.FontException e) {
      out.add("error " + e.code);
      return out;
    }
    out.add("family " + esc(in.family, false));
    out.add("subfamily " + esc(in.subfamily, false));
    out.add("fullName " + esc(in.fullName, false));
    out.add("postScriptName " + esc(in.postScriptName, false));
    out.add("version " + esc(in.version, false));
    out.add("copyright " + esc(in.copyright, false));
    out.add("designer " + esc(in.designer, false));
    out.add("license " + esc(in.license, false));
    out.add("licenseUrl " + esc(in.licenseUrl, false));
    out.add("weight " + in.weight);
    out.add("width " + in.width);
    out.add("fsType " + in.fsType);
    out.add("italic " + in.italic);
    out.add("outlines " + in.outlines);
    out.add("glyphs " + in.glyphs);
    out.add("upem " + in.upem);
    out.add("codepoints " + in.codepoints);
    out.add("latin " + in.latin);
    out.add("symbol " + in.symbol);
    out.add("color " + in.color);
    out.add("variable " + in.variable);
    out.add("avar2 " + in.avar2);
    for (FontInspect.Axis a : in.axes)
      out.add("axis " + esc(a.tag, true) + " " + num(a.min) + " " + num(a.def) + " " + num(a.max) + " " + a.hidden + " " + esc(a.name, false));
    for (FontInspect.Instance i : in.instances) out.add("instance " + location(i.coords) + " " + esc(i.name, false));
    out.add("guessRegular " + (in.guessRegular == null ? "none" : location(in.guessRegular)));
    out.add("guessBold " + (in.guessBold == null ? "none" : location(in.guessBold)));
    for (String w : in.warnings) out.add("warning " + code(w));
    return out;
  }

  static String esc(String s, boolean spaces) {
    s = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    return spaces ? s.replace(" ", "\\s") : s;
  }

  static String num(double v) {
    if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
    return new BigDecimal(v).stripTrailingZeros().toPlainString();
  }

  static String location(Map<String, Double> m) {
    StringBuilder s = new StringBuilder();
    for (Map.Entry<String, Double> e : m.entrySet()) s.append(s.length() > 0 ? " " : "").append(esc(e.getKey(), true)).append('=').append(num(e.getValue()));
    return s.toString();
  }

  static String code(String w) {
    if (w.equals(FontInspect.W_SYMBOL)) return "symbol";
    if (w.equals(FontInspect.W_NO_CHARACTERS)) return "nocharacters";
    if (w.equals(FontInspect.W_NO_LATIN)) return "nolatin";
    if (w.equals(FontInspect.W_RESTRICTED)) return "restricted";
    if (w.equals(FontInspect.W_AVAR2)) return "avar2";
    if (w.equals(FontInspect.W_NO_PALETTE)) return "nopalette";
    if (w.equals(FontInspect.W_COLOR_DAMAGED)) return "colordamaged";
    if (w.equals(FontInspect.W_SVG)) return "svg";
    for (String t : new String[] { "CBDT", "sbix" }) if (w.equals(String.format(FontInspect.W_BITMAP, t))) return "bitmap:" + t;
    return "? " + w;
  }

  static void fixtureFonts(File fonts, Map<String, List<String>> ex) throws Exception {
    int recorded = 0, own = 0;
    for (File file : fontFiles(fonts)) {
      String name = rel(fonts, file);
      byte[] data = Files.readAllBytes(file.toPath());
      List<String> want = ex.get(sha(data));
      List<String> got = lines(data);
      boolean mine = name.startsWith("fontinspect/") || name.startsWith("cmap/");
      if (want == null) {
        if (mine) check(name + ": recorded in fontinspect/expected.txt", false, "run make_fixtures.py expect");
        else System.out.println("NOTE " + name + ": not in fontinspect/expected.txt (run make_fixtures.py expect); checked only that it is read or refused in words");
        check(name + ": read, or refused with a FontException", got.size() > 1 || got.get(0).startsWith("error "));
        continue;
      }
      recorded++;
      if (mine) own++;
      String diff = null;
      for (int i = 0; i < Math.max(want.size(), got.size()) && diff == null; i++) {
        String w = i < want.size() ? want.get(i) : "(nothing)", g = i < got.size() ? got.get(i) : "(nothing)";
        if (!w.equals(g)) diff = "fontTools: " + clip(w) + " / here: " + clip(g);
      }
      String what = got.get(0).startsWith("error ") ? "refused with code " + got.get(0).substring(6) + " as expected"
          : "names, weight, outlines, characters, colour, " + (countPrefix(got, "axis ") > 0 ? countPrefix(got, "axis ") + " axes, " + countPrefix(got, "instance ") + " instances, guesses, " : "")
            + "warnings as fontTools reads them";
      check(name + ": " + what, diff == null, diff);
    }
    check("every fixture font of this suite and the cmap suite is recorded, " + recorded + " in all", own >= 10, own + " of this suite's");
  }

  static int countPrefix(List<String> l, String p) { int n = 0; for (String s : l) if (s.startsWith(p)) n++; return n; }

  static String clip(String s) { return s.length() > 160 ? s.substring(0, 160) + "..." : s; }

  // ---------- the fixtures, field by field ----------

  static void ownFixtures(File fonts) throws Exception {
    FontInspect.Info it = inspect(fixture(fonts, "fontinspect/FixtureSans-SemiboldIt.otf"));
    check("Semibold Italic (CFF): the typographic family and style, not name ids 1 / 2 (\"Fixture Sans 3 Semibold\" / \"Italic\")",
        it.family.equals("Fixture Sans 3") && it.subfamily.equals("Semibold Italic") && it.fullName.equals("Fixture Sans 3 Semibold Italic"),
        it.family + " / " + it.subfamily + " / " + it.fullName);
    check("Semibold Italic: weight 600, italic, CFF outlines, Latin, not variable, no guesses, no warnings",
        it.weight == 600 && it.italic && it.outlines.equals("CFF") && it.latin && !it.variable && it.guessRegular == null && it.guessBold == null
            && it.warnings.isEmpty() && it.codepoints == 95, it.weight + " " + it.italic + " " + it.outlines + " " + it.codepoints + " " + it.warnings);
    check("Semibold Italic: the license and its address are read", it.license.startsWith("This Font Software is licensed under the SIL Open Font License")
        && it.licenseUrl.contains("OFL") && it.copyright.contains("Adobe"), it.licenseUrl);

    FontInspect.Info vf = inspect(fixture(fonts, "fontinspect/FixtureSansVF.otf"));
    check("VF (CFF2): CFF2 outlines, variable, one axis wght 200..900 (default 200) named Weight",
        vf.outlines.equals("CFF2") && vf.variable && vf.axes.size() == 1 && vf.axes.get(0).tag.equals("wght") && vf.axes.get(0).min == 200
            && vf.axes.get(0).def == 200 && vf.axes.get(0).max == 900 && vf.axes.get(0).name.equals("Weight") && !vf.axes.get(0).hidden,
        vf.outlines + " " + vf.axes.size());
    List<String> names = new ArrayList<>();
    for (FontInspect.Instance i : vf.instances) names.add(i.name + "=" + num(i.coords.get("wght")));
    check("VF: its 7 named instances ExtraLight 200 .. Black 900",
        names.toString().equals("[ExtraLight=200, Light=300, Regular=400, Medium=500, Semibold=600, Bold=700, Black=900]"), names.toString());
    check("VF: Regular preselects wght 400 and Bold wght 700, though the default is 200",
        vf.guessRegular.toString().equals("{wght=400.0}") && vf.guessBold.toString().equals("{wght=700.0}"), vf.guessRegular + " " + vf.guessBold);

    FontInspect.Info inter = inspect(fixture(fonts, "cmap/Inter-cmap.ttf"));
    check("Inter (TrueType variable, opsz + wght): Regular at opsz 14 wght 400, Bold at opsz 14 wght 700, 9 named instances",
        inter.outlines.equals("TrueType") && inter.guessRegular.toString().equals("{opsz=14.0, wght=400.0}")
            && inter.guessBold.toString().equals("{opsz=14.0, wght=700.0}") && inter.instances.size() == 9,
        inter.guessRegular + " " + inter.guessBold + " " + inter.instances.size());

    FontInspect.Info v1 = inspect(fixture(fonts, "fontinspect/NotoColorEmoji-COLRv1.ttf"));
    check("Noto Color Emoji subset: COLRv1, 4 characters, no Latin and no warning about it (an emoji font needs none)",
        v1.color.equals("COLRv1") && v1.codepoints == 4 && !v1.latin && v1.warnings.isEmpty(), v1.color + " " + v1.codepoints + " " + v1.warnings);
    FontInspect.Info v0 = inspect(fixture(fonts, "fontinspect/Twemoji-COLRv0.ttf"));
    check("Twemoji subset: COLRv0, family Twemoji Mozilla", v0.color.equals("COLRv0") && v0.family.equals("Twemoji Mozilla") && v0.warnings.isEmpty(),
        v0.color + " " + v0.family + " " + v0.warnings);
    throwsFont("Noto Color Emoji CBDT subset: refused with code bitmap_emoji, naming CBDT and a COLR font to pick", "bitmap_emoji", "(CBDT)",
        () -> inspect(fixture(fonts, "fontinspect/NotoColorEmoji-CBDT.ttf")));
    FontInspect.Info sym = inspect(fixture(fonts, "cmap/Symbol-cmap.ttf"));
    check("a Windows symbol font: symbol, and only the symbol warning", sym.symbol && sym.warnings.equals(Collections.singletonList(FontInspect.W_SYMBOL)),
        sym.warnings.toString());
    FontInspect.Info lato = inspect(fixture(fonts, "cmap/Lato-cmap.ttf"));
    check("Lato subset (static TrueType): not variable, no axes, no guesses, family from name id 1", !lato.variable && lato.axes.isEmpty()
        && lato.guessRegular == null && lato.guessBold == null && lato.family.equals("Fixture L") && lato.subfamily.equals("Regular"), lato.family);
  }

  // ---------- names ----------

  /** A name table with these records: {pid, eid, lang, id} and the text, encoded as the platform does. */
  static byte[] nameTable(Object... recs) {
    Sfnt.Writer strings = new Sfnt.Writer();
    List<int[]> r = new ArrayList<>();
    for (int i = 0; i < recs.length; i += 2) {
      int[] k = (int[]) recs[i];
      byte[] b = ((String) recs[i + 1]).getBytes(k[0] == 1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE);
      r.add(new int[] { k[0], k[1], k[2], k[3], b.length, strings.size() });
      strings.bytes(b);
    }
    Sfnt.Writer w = new Sfnt.Writer();
    w.u16(0).u16(r.size()).u16(6 + 12 * r.size());
    for (int[] x : r) for (int v : x) w.u16(v);
    return w.bytes(strings.toByteArray()).toByteArray();
  }

  static int[] win(int id) { return new int[] { 3, 1, 0x409, id }; }

  static void names(File fonts) throws Exception {
    byte[] lato = fixture(fonts, "cmap/Lato-cmap.ttf");
    Map<Integer, String> n = new TreeMap<>();
    n.put(1, "Fam Bold Wide");
    n.put(2, "Bold");
    n.put(16, "Fam");
    n.put(17, "Bold Wide");
    FontInspect.Info a = inspect(edited(lato, f -> f.put("name", Sfnt.buildName(n))));
    check("typographic family and style (16 / 17) win over 1 / 2; with no name 4 the full name is family + style",
        a.family.equals("Fam") && a.subfamily.equals("Bold Wide") && a.fullName.equals("Fam Bold Wide"), a.family + " / " + a.subfamily + " / " + a.fullName);
    n.remove(16);
    n.remove(17);
    n.put(4, "Fam Bold Wide Full");
    FontInspect.Info b = inspect(edited(lato, f -> f.put("name", Sfnt.buildName(n))));
    check("without 16 / 17: family and style from 1 / 2, full name from 4", b.family.equals("Fam Bold Wide") && b.subfamily.equals("Bold")
        && b.fullName.equals("Fam Bold Wide Full"), b.family + " / " + b.subfamily + " / " + b.fullName);
    FontInspect.Info c = inspect(edited(lato, f -> f.put("name", nameTable(new int[] { 1, 0, 0, 1 }, "Mac Only", new int[] { 1, 0, 0, 2 }, "Italic"))));
    check("a font with Mac names only: they are read", c.family.equals("Mac Only") && c.subfamily.equals("Italic"), c.family + " / " + c.subfamily);
    FontInspect.Info d = inspect(edited(lato, f -> f.put("name", nameTable(new int[] { 1, 0, 0, 1 }, "Mac", new int[] { 3, 1, 0x407, 1 }, "Deutsch",
        win(1), "  English\u0000 "))));
    check("Windows English is the name shown, before Windows in another language and Mac; NULs and spaces trimmed", d.family.equals("English"), d.family);
    FontInspect.Info e = inspect(edited(lato, f -> f.put("name", nameTable(new int[] { 1, 0, 0, 1 }, "Mac", new int[] { 3, 1, 0x407, 1 }, "Deutsch"))));
    check("Windows in another language before Mac", e.family.equals("Deutsch"), e.family);
    FontInspect.Info none = inspect(edited(lato, f -> f.remove("name")));
    check("no name table: every name is \"\", nothing fails", none.family.isEmpty() && none.subfamily.isEmpty() && none.fullName.isEmpty()
        && none.version.isEmpty() && none.license.isEmpty(), none.family);
    FontInspect.Info cut = inspect(edited(lato, f -> f.put("name", Arrays.copyOf(f.table("name"), 20))));
    check("a cut-off name table: names \"\", the rest is read", cut.family.isEmpty() && cut.glyphs > 0, cut.family);
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 600; i++) big.append("licence ");
    String longText = big.toString();
    FontInspect.Info l = inspect(edited(lato, f -> f.put("name", nameTable(win(13), longText, win(1), "Long"))));
    check("a license text of 4,800 characters is cut to 1000 ending in an ellipsis", l.license.length() == FontInspect.MAX_TEXT && l.license.endsWith("\u2026")
        && l.license.startsWith("licence licence"), l.license.length() + "");
  }

  // ---------- weight, width, style, embedding ----------

  static void styles(File fonts) throws Exception {
    byte[] lato = fixture(fonts, "cmap/Lato-cmap.ttf");
    FontInspect.Info a = inspect(edited(lato, f -> {
      byte[] os2 = patched(patched(f.table("OS/2"), 4, 900), 6, 3);
      f.put("OS/2", patched(os2, 62, Sfnt.u16(os2, 62) | 1));
    }));
    check("OS/2 weight 900, width 3 and the fsSelection italic bit are read", a.weight == 900 && a.width == 3 && a.italic, a.weight + " " + a.width + " " + a.italic);
    FontInspect.Info r = inspect(edited(lato, f -> f.put("OS/2", patched(f.table("OS/2"), 8, 0x0002))));
    check("fsType 0x0002 (restricted license embedding): the restricted warning", r.fsType == 2 && r.warnings.contains(FontInspect.W_RESTRICTED), r.warnings.toString());
    for (int t : new int[] { 0x0004, 0x0008, 0x0100, 0x0200 }) {
      FontInspect.Info p = inspect(edited(lato, f -> f.put("OS/2", patched(f.table("OS/2"), 8, t))));
      check("fsType 0x" + Integer.toHexString(t) + ": no warning (only bit 0x0002 restricts)", p.fsType == t && p.warnings.isEmpty(), p.warnings.toString());
    }
    FontInspect.Info noOs2 = inspect(edited(lato, f -> { f.remove("OS/2"); f.put("head", patched(f.table("head"), 44, 3)); }));
    check("no OS/2: weight and italic from head.macStyle (bold + italic: 700, italic), width 5, fsType 0",
        noOs2.weight == 700 && noOs2.italic && noOs2.width == 5 && noOs2.fsType == 0, noOs2.weight + " " + noOs2.italic);
    FontInspect.Info shortOs2 = inspect(edited(lato, f -> { f.put("OS/2", Arrays.copyOf(f.table("OS/2"), 60)); f.put("head", patched(f.table("head"), 44, 0)); }));
    check("an OS/2 table cut short of 64 bytes is passed over the same way (400, upright)", shortOs2.weight == 400 && !shortOs2.italic, shortOs2.weight + "");
    FontInspect.Info macItalic = inspect(edited(lato, f -> f.put("head", patched(f.table("head"), 44, 2))));
    check("head.macStyle italic with OS/2 upright: italic", macItalic.italic && macItalic.weight == 400, macItalic.italic + "");
    FontInspect.Info empty = inspect(edited(lato, f -> f.put("cmap", new byte[] { 0, 0, 0, 0 })));
    check("a character map with no subtables: 0 characters and the no-characters warning",
        empty.codepoints == 0 && !empty.latin && empty.warnings.equals(Collections.singletonList(FontInspect.W_NO_CHARACTERS)), empty.warnings.toString());
    byte[] digits = Cmap.build(digitsMap(), null);
    FontInspect.Info noLatin = inspect(edited(lato, f -> f.put("cmap", digits)));
    check("digits only: not Latin, and the no-Latin warning", !noLatin.latin && noLatin.codepoints == 10 && noLatin.warnings.equals(Collections.singletonList(FontInspect.W_NO_LATIN)),
        noLatin.warnings.toString());
    throwsFont("a damaged character map refuses the font in words", null, "character map", () -> inspect(edited(lato, f -> {
      byte[] t = f.table("cmap").clone();
      Sfnt.w32(t, 8, t.length + 100);                             // the first subtable's offset past the end
      for (int i = 0, n = Sfnt.u16(t, 2); i < n; i++) Sfnt.w32(t, 4 + 8 * i + 4, t.length + 100);
      f.put("cmap", t);
    })));
  }

  static Map<Integer, Integer> digitsMap() {
    Map<Integer, Integer> m = new TreeMap<>();
    for (int c = '0'; c <= '9'; c++) m.put(c, 1 + c - '0');
    return m;
  }

  // ---------- colour ----------

  static void colours(File fonts) throws Exception {
    byte[] noto = fixture(fonts, "fontinspect/NotoColorEmoji-COLRv1.ttf");
    byte[] twemoji = fixture(fonts, "fontinspect/Twemoji-COLRv0.ttf");
    byte[] lato = fixture(fonts, "cmap/Lato-cmap.ttf");
    FontInspect.Info noPal = inspect(edited(noto, f -> f.remove("CPAL")));
    check("COLR without CPAL: not a colour font, with the no-palette warning (and no no-Latin warning)",
        noPal.color.equals("none") && noPal.warnings.equals(Collections.singletonList(FontInspect.W_NO_PALETTE)), noPal.color + " " + noPal.warnings);
    FontInspect.Info bad = inspect(edited(noto, f -> f.put("COLR", new byte[] { 0, 1, 0, 5 })));
    check("a COLR table cut off: the damaged-colour warning", bad.color.equals("none") && bad.warnings.equals(Collections.singletonList(FontInspect.W_COLOR_DAMAGED)),
        bad.color + " " + bad.warnings);
    FontInspect.Info badList = inspect(edited(noto, f -> {
      byte[] t = f.table("COLR").clone();
      Sfnt.w32(t, 14, t.length + 8);                              // BaseGlyphList past the end
      f.put("COLR", t);
    }));
    check("COLRv1 whose base glyph list points past the table: damaged", badList.warnings.contains(FontInspect.W_COLOR_DAMAGED), badList.warnings.toString());
    FontInspect.Info v0in1 = inspect(edited(noto, f -> {
      byte[] t = f.table("COLR").clone();
      Sfnt.w32(t, 14, 0);                                         // no BaseGlyphList: only what version 0 has
      Sfnt.w16(t, 2, 1);
      f.put("COLR", t);
    }));
    check("COLR version 1 with only version 0 base glyphs: COLRv0", v0in1.color.equals("COLRv0"), v0in1.color);
    FontInspect.Info emptyColr = inspect(edited(twemoji, f -> f.put("COLR", new byte[14])));
    check("an empty COLR (no base glyphs): not a colour font, no warning", emptyColr.color.equals("none") && emptyColr.warnings.isEmpty(),
        emptyColr.color + " " + emptyColr.warnings);
    FontInspect.Info svg = inspect(edited(lato, f -> f.put("SVG ", new byte[10])));
    check("SVG next to outlines: SVG, with the SVG warning", svg.color.equals("SVG") && svg.warnings.equals(Collections.singletonList(FontInspect.W_SVG)),
        svg.color + " " + svg.warnings);
    FontInspect.Info cbdt = inspect(edited(lato, f -> { f.put("CBDT", new byte[8]); f.put("CBLC", new byte[8]); }));
    check("CBDT next to outlines: CBDT, with the bitmap warning naming it", cbdt.color.equals("CBDT")
        && cbdt.warnings.equals(Collections.singletonList(String.format(FontInspect.W_BITMAP, "CBDT"))), cbdt.color + " " + cbdt.warnings);
    FontInspect.Info sbix = inspect(edited(lato, f -> f.put("sbix", new byte[8])));
    check("sbix next to outlines: sbix", sbix.color.equals("sbix") && sbix.warnings.equals(Collections.singletonList(String.format(FontInspect.W_BITMAP, "sbix"))),
        sbix.color + " " + sbix.warnings);
    FontInspect.Info both = inspect(edited(twemoji, f -> f.put("SVG ", new byte[10])));
    check("COLR and SVG: COLR is the one used, no warning", both.color.equals("COLRv0") && both.warnings.isEmpty(), both.color + " " + both.warnings);
  }

  // ---------- refused files ----------

  static void refused(File fonts) throws Exception {
    byte[] twemoji = fixture(fonts, "fontinspect/Twemoji-COLRv0.ttf");
    throwsFont("an sbix font without outlines: bitmap_emoji, naming sbix", "bitmap_emoji", "(sbix)", () -> inspect(edited(twemoji, f -> {
      f.remove("glyf"); f.remove("loca"); f.remove("COLR"); f.put("sbix", new byte[8]);
    })));
    throwsFont("no outlines and no bitmaps: Sfnt's no_outlines", "no_outlines", null, () -> inspect(edited(twemoji, f -> { f.remove("glyf"); f.remove("loca"); })));
    throwsFont("random bytes: not_a_font", "not_a_font", null, () -> inspect("this is not a font, only some text".getBytes(StandardCharsets.US_ASCII)));
    throwsFont("a font collection: collection", "collection", null, () -> inspect(Arrays.copyOf(new byte[] { 't', 't', 'c', 'f', 0, 1 }, 64)));
    throwsFont("WOFF: woff", "woff", null, () -> inspect(Arrays.copyOf(new byte[] { 'w', 'O', 'F', 'F', 0, 1 }, 64)));
    throwsFont("WOFF2: woff", "woff", null, () -> inspect(Arrays.copyOf(new byte[] { 'w', 'O', 'F', '2', 0, 1 }, 64)));
    throwsFont("an empty file: not_a_font", "not_a_font", null, () -> inspect(new byte[0]));
    throwsFont("null: not_a_font", "not_a_font", null, () -> inspect((byte[]) null));
  }

  // ---------- variable fonts ----------

  static void variable(File fonts) throws Exception {
    byte[] inter = fixture(fonts, "cmap/Inter-cmap.ttf");
    FontInspect.Info unnamed = inspect(edited(inter, f -> {
      byte[] t = f.table("fvar").clone();
      int axesOff = Sfnt.u16(t, 4), axisCount = Sfnt.u16(t, 8), axisSize = Sfnt.u16(t, 10);
      Sfnt.w16(t, axesOff + axisCount * axisSize, 0x7FFF);        // the first instance's style name id: a name the font does not have
      f.put("fvar", t);
    }));
    check("an instance whose style name is missing is named by its location", unnamed.instances.get(0).name.equals("opsz 14, wght 100"),
        unnamed.instances.get(0).name);
    FontInspect.Info avar2 = inspect(edited(inter, f -> f.put("avar", patched(f.table("avar"), 0, 2))));
    check("avar version 2: flagged, with the avar2 warning", avar2.avar2 && avar2.warnings.equals(Collections.singletonList(FontInspect.W_AVAR2)), avar2.warnings.toString());
    FontInspect.Info hidden = inspect(edited(inter, f -> {
      byte[] t = f.table("fvar").clone();
      Sfnt.w16(t, Sfnt.u16(t, 4) + 16, 1);                        // the first axis' flags: HIDDEN_AXIS
      f.put("fvar", t);
    }));
    check("a hidden axis is marked hidden", hidden.axes.get(0).hidden && !hidden.axes.get(1).hidden, hidden.axes.get(0).hidden + "");
    throwsFont("a damaged fvar refuses the font in words", null, "variable font", () -> inspect(edited(inter, f -> f.put("fvar", patched(f.table("fvar"), 8, 0)))));
    FontInspect.Info noInst = inspect(edited(inter, f -> f.put("fvar", patched(f.table("fvar"), 12, 0))));
    check("no named instances: Regular wght 400 and Bold wght 700 on the axis", noInst.instances.isEmpty()
        && noInst.guessRegular.toString().equals("{opsz=14.0, wght=400.0}") && noInst.guessBold.toString().equals("{opsz=14.0, wght=700.0}"),
        noInst.guessRegular + " " + noInst.guessBold);
  }

  // ---------- the guess rule ----------

  static VarStore.Fvar fvar(double[][] axes, String[] tags, double[]... instances) {
    VarStore.Fvar v = new VarStore.Fvar();
    for (int i = 0; i < axes.length; i++) {
      VarStore.Axis a = new VarStore.Axis();
      a.tag = tags[i];
      a.min = axes[i][0]; a.def = axes[i][1]; a.max = axes[i][2];
      a.name = tags[i];
      v.axes.add(a);
    }
    for (double[] c : instances) {
      VarStore.Instance in = new VarStore.Instance();
      in.coords = c;
      v.instances.add(in);
    }
    return v;
  }

  static String g(VarStore.Fvar v, double target) { return FontInspect.guess(v, target).toString(); }

  static void guesses() {
    double[][] w = { { 100, 400, 900 } };
    String[] wt = { "wght" };
    VarStore.Fvar steps = fvar(w, wt, new double[] { 100 }, new double[] { 300 }, new double[] { 400 }, new double[] { 700 }, new double[] { 900 });
    check("instances 100..900: Regular 400, Bold 700", g(steps, 400).equals("{wght=400.0}") && g(steps, 700).equals("{wght=700.0}"), g(steps, 400) + g(steps, 700));
    VarStore.Fvar ends = fvar(w, wt, new double[] { 100 }, new double[] { 900 });
    check("only Thin and Black named: the axis itself at 400 and 700 is closer", g(ends, 400).equals("{wght=400.0}") && g(ends, 700).equals("{wght=700.0}"),
        g(ends, 400) + g(ends, 700));
    VarStore.Fvar near = fvar(w, wt, new double[] { 350 }, new double[] { 650 });
    check("named 350 and 650 only: the axis at 400 / 700, as nothing named is as close", g(near, 400).equals("{wght=400.0}") && g(near, 700).equals("{wght=700.0}"),
        g(near, 400) + g(near, 700));
    VarStore.Fvar light = fvar(new double[][] { { 100, 200, 300 } }, wt, new double[] { 100 }, new double[] { 300 });
    check("a light-only axis (100..300): Bold is the 300 instance (as close as the clamped axis), Regular too",
        g(light, 700).equals("{wght=300.0}") && g(light, 400).equals("{wght=300.0}"), g(light, 700) + g(light, 400));
    double[][] two = { { 100, 400, 900 }, { 75, 100, 100 } };
    String[] tt = { "wght", "wdth" };
    VarStore.Fvar wide = fvar(two, tt, new double[] { 700, 75 }, new double[] { 700, 100 }, new double[] { 400, 75 }, new double[] { 400, 100 });
    check("two Bold instances: the one at the default width", g(wide, 700).equals("{wght=700.0, wdth=100.0}") && g(wide, 400).equals("{wght=400.0, wdth=100.0}"),
        g(wide, 700) + g(wide, 400));
    VarStore.Fvar condensed = fvar(two, tt, new double[] { 700, 75 }, new double[] { 400, 75 });
    check("only condensed instances: the named one wins over the axis at the same weight", g(condensed, 700).equals("{wght=700.0, wdth=75.0}"), g(condensed, 700));
    VarStore.Fvar outside = fvar(w, wt, new double[] { 1000 });
    check("an instance outside the axis range is clamped to it", g(outside, 700).equals("{wght=700.0}") && g(outside, 900).equals("{wght=900.0}"), g(outside, 900));
    VarStore.Fvar first = fvar(two, tt, new double[] { 700, 75 }, new double[] { 700, 75 });
    check("a tie in every way: the first instance", FontInspect.guess(first, 700).toString().equals("{wght=700.0, wdth=75.0}"), g(first, 700));
    VarStore.Fvar noW = fvar(new double[][] { { 8, 14, 144 } }, new String[] { "opsz" }, new double[] { 144 });
    check("no wght axis: the defaults", g(noW, 400).equals("{opsz=14.0}") && g(noW, 700).equals("{opsz=14.0}"), g(noW, 400));
    VarStore.Fvar empty = fvar(w, wt);
    check("no instances: the axis clamped", g(empty, 400).equals("{wght=400.0}") && g(empty, 700).equals("{wght=700.0}"), g(empty, 700));
    check("describe: integers without a decimal point, fractions as they are", FontInspect.describe(map("wght", 700.0, "opsz", 14.5)).equals("wght 700, opsz 14.5"),
        FontInspect.describe(map("wght", 700.0, "opsz", 14.5)));
  }

  static Map<String, Double> map(Object... kv) {
    Map<String, Double> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Double) kv[i + 1]);
    return m;
  }

  // ---------- fuzz ----------

  static void fuzz(File fonts, long seed, int count) throws Exception {
    Random r = new Random(seed);
    List<byte[]> base = new ArrayList<>();
    for (String n : new String[] { "fontinspect/FixtureSans-SemiboldIt.otf", "fontinspect/FixtureSansVF.otf", "fontinspect/NotoColorEmoji-COLRv1.ttf",
        "fontinspect/Twemoji-COLRv0.ttf", "fontinspect/NotoColorEmoji-CBDT.ttf", "cmap/Inter-cmap.ttf", "cmap/Lato-cmap.ttf", "cmap/Symbol-cmap.ttf" }) {
      File f = new File(fonts, n);
      if (f.isFile()) base.add(Files.readAllBytes(f.toPath()));
    }
    if (base.isEmpty()) { check("fuzz: the fixture fonts are found", false); return; }
    String[] targets = { "name", "fvar", "COLR", "CPAL", "OS/2", "cmap", "head", "maxp", "avar", "hhea" };
    int ok = 0, refused = 0, wrong = 0, slow = 0, unwritable = 0;
    long t0 = System.nanoTime();
    for (int i = 0; i < count; i++) {
      byte[] t = base.get(r.nextInt(base.size()));
      int kind = r.nextInt(6);
      if (kind < 4) {                                              // one table damaged, the file written again around it
        Sfnt f;
        try { f = Sfnt.parse(t).copy(); } catch (Sfnt.FontException e) { f = null; }
        String tag = targets[r.nextInt(targets.length)];
        if (f != null && f.has(tag)) {
          byte[] d = f.table(tag).clone();
          if (d.length > 0) {
            switch (kind) {
              case 0: d = Arrays.copyOf(d, r.nextInt(d.length + 1)); break;                                   // cut off
              case 1: for (int k = 1 + r.nextInt(8); k > 0; k--) d[r.nextInt(d.length)] ^= 1 << r.nextInt(8); break;   // bit flips
              case 2: { int p = r.nextInt(d.length); for (int k = 0; k < 4 && p + k < d.length; k++) d[p + k] = (byte) 0xFF; break; }  // a huge count
              default: { int p = r.nextInt(d.length); for (int k = 0; k < 16 && p + k < d.length; k++) d[p + k] = (byte) r.nextInt(256); }   // random bytes
            }
            f.put(tag, d);
          }
          try { t = f.toBytes(); } catch (Exception e) { unwritable++; continue; }   // a head cut short of 12 bytes cannot be written

        }
      } else if (kind == 4) {
        t = t.clone();
        for (int k = 1 + r.nextInt(8); k > 0; k--) t[r.nextInt(t.length)] ^= 1 << r.nextInt(8);
      } else {
        t = Arrays.copyOf(t, r.nextInt(t.length + 1));
      }
      long c0 = System.nanoTime();
      try {
        FontInspect.Info in = FontInspect.inspect(t);
        if (in.family == null || in.subfamily == null || in.fullName == null || in.license == null || in.color == null || in.outlines.isEmpty()) wrong++;
        ok++;
      } catch (Sfnt.FontException e) {
        refused++;
        if (e.getMessage() == null || e.getMessage().isEmpty()) wrong++;
      } catch (Throwable e) {
        wrong++;
        if (wrong < 5) System.out.println("  " + e + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : ""));
      }
      if (System.nanoTime() - c0 > 2_000_000_000L) slow++;
    }
    long ms = (System.nanoTime() - t0) / 1000000;
    check("fuzz: " + count + " damaged fonts read or refused with a FontException in words (" + ok + " read, " + refused + " refused, "
        + unwritable + " not written, " + ms + " ms)", wrong == 0 && slow == 0 && ok > count / 10 && refused > count / 10, wrong + " wrong, " + slow + " slow");
  }
}
