package com.bloatware.bingblop;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * FontBuilder, the step of the Custom Fonts engine that makes the fonts of one package from the fonts the page picked. Against
 * tests/java/fixtures/fonts/fontbuilder: subsets of Inter (variable, TrueType), Fixture Sans 3 (Source Sans 3 renamed: the OTF Regular and
 * Bold and the CFF2 variable font), Noto Sans Regular and Italic (hinted), Twemoji (COLRv0) and Noto Color Emoji (COLRv1), built in the six
 * cases of {@link #CASES}. Each face is compared line by line with what fontTools read in the same build (expected.txt: the tables, every
 * name record, OS/2 weight and fsSelection, head macStyle and modified, post format, and CRC-32s of the cmap, hmtx and every glyph); when
 * make_fixtures.py expect wrote it, it also shaped every face with HarfBuzz and drew it with FreeType against its sources. Then each face is
 * compared with what the engines make on their own: FontBuilder may change only the name table, the OS/2 weight and fsSelection, head's
 * macStyle and modified, and drop DSIG. Then the style and name rules, the name table's 16-bit limits, the refusals with their codes and
 * the face they name, progress and Cancel, and a seeded fuzz of all three inputs.
 *
 * <p>{@code --dump DIR} writes the fonts the suite builds for {@code make_fixtures.py expect DIR}; {@code --e2e OUT NAME REGULAR[@tag=v,...]
 * BOLD[@tag=v,...]|- EMOJI|-} builds and signs a whole package from full fonts for {@code make_fixtures.py e2e OUT}; {@code --fuzz SEED
 * COUNT} runs only the fuzz (SEED in hex).
 */
public class FontBuilderTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  static void check(String name, boolean ok, String detail) { check(ok || detail == null ? name : name + " -- " + detail, ok); }
  static void note(String s) { System.out.println("NOTE " + s); }

  interface Body { void run() throws Exception; }

  /** Passes when {@code body} ends in a FontException with this code (null: any) whose message starts with {@code start} (null: any). */
  static Sfnt.FontException throwsFont(String name, String code, String start, Body body) {
    try {
      body.run();
      check(name, false, "no exception");
    } catch (Sfnt.FontException e) {
      check(name, (code == null || code.equals(e.code)) && (start == null || e.getMessage().startsWith(start)), e.code + ": " + e.getMessage());
      return e;
    } catch (Throwable e) {
      check(name, false, e.toString());
    }
    return null;
  }

  static void section(String name, Body body) {
    try {
      body.run();
    } catch (Throwable e) {
      check(name + ": ran to the end", false, e.toString() + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : ""));
    }
  }

  // ---------- fixtures and cases ----------

  static final String INTER = "Inter-var.ttf", SS3 = "FixtureSans3-Regular.otf", SS3_BOLD = "FixtureSans3-Bold.otf", SS3_VF = "FixtureSans3VF.otf",
      NOTO = "NotoSans-Regular.ttf", NOTO_ITALIC = "NotoSans-Italic.ttf", TWEMOJI = "Twemoji-sub.ttf", NOTO_EMOJI = "Noto-COLRv1-sub.ttf";
  static final String[] FILES = { INTER, SS3, SS3_BOLD, SS3_VF, NOTO, NOTO_ITALIC, TWEMOJI, NOTO_EMOJI };
  /** 2026-06-01 00:00 UTC: when the suite's fonts are made. */
  static final long TIME = 1780272000000L;
  static final String DAY = "2026-06-01";

  /** One build: a display name, the Regular and Bold sources (Bold may be the Regular file again), their locations, the emoji font. */
  static final class Case {
    final String name, display, regular, bold, emoji;
    final Map<String, Double> regularLoc, boldLoc;
    /** Bold is the Regular's file read a second time (a distinct array with the same bytes), not the same array. */
    boolean boldCopy;
    /** A DSIG table is added to the Regular source, to see it go. */
    boolean dsig;

    Case(String name, String display, String regular, Map<String, Double> regularLoc, String bold, Map<String, Double> boldLoc, String emoji) {
      this.name = name; this.display = display; this.regular = regular; this.regularLoc = regularLoc; this.bold = bold; this.boldLoc = boldLoc;
      this.emoji = emoji;
    }

    boolean variable() { return regular.equals(INTER) || regular.equals(SS3_VF); }
  }

  static Map<String, Double> loc(Object... kv) {
    Map<String, Double> m = new LinkedHashMap<String, Double>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
    return m;
  }

  static final List<Case> CASES = new ArrayList<Case>();
  static {
    // Inter: the Regular where FontInstancer guesses it, the Bold at wght 700, both from the one array
    CASES.add(new Case("var", "Inter Test", INTER, null, INTER, loc("wght", 700), null));
    // PostScript outlines: both faces converted, then Noto Color Emoji; a name with a letter outside ASCII
    CASES.add(new Case("cff", "Caf\u00e9 Sans", SS3, null, SS3_BOLD, null, NOTO_EMOJI));
    // A CFF2 variable font at two locations, with Twemoji
    CASES.add(new Case("cff2", "Fixture VF", SS3_VF, loc("wght", 300), SS3_VF, loc("wght", 900), TWEMOJI));
    // A static TrueType font, no Bold, with Twemoji
    CASES.add(new Case("static", "Noto Emoji Test", NOTO, null, null, null, TWEMOJI));
    // An italic font with a DSIG, alone
    Case italic = new Case("italic", "Slanted", NOTO_ITALIC, null, null, null, null);
    italic.dsig = true;
    CASES.add(italic);
    // The same file picked twice: a Bold no heavier than the Regular
    Case same = new Case("same", "Same Twice", NOTO, null, NOTO, null, null);
    same.boldCopy = true;
    CASES.add(same);
  }

  static final Map<String, byte[]> FONTS = new HashMap<String, byte[]>();
  /** A copy of every fixture as read, to see that no build changes its sources. */
  static final Map<String, byte[]> PRISTINE = new HashMap<String, byte[]>();

  static void load(File dir) throws IOException {
    for (String f : FILES) {
      byte[] b = Files.readAllBytes(new File(dir, f).toPath());
      FONTS.put(f, b);
      PRISTINE.put(f, b.clone());
    }
  }

  static byte[] font(String name) { return FONTS.get(name); }

  static File fixtureDir() {
    String p = System.getProperty("fixtures");
    if (p != null && new File(p, "fonts/fontbuilder").isDirectory()) return new File(p, "fonts/fontbuilder");
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts/fontbuilder");
    return null;
  }

  static byte[] withDsig(byte[] data) throws Exception {
    Sfnt f = Sfnt.parse(data);
    f.put("DSIG", new byte[] { 0, 0, 0, 1, 0, 0, 0, 0 });
    return f.toBytes();
  }

  static FontBuilder.Request request(Case c) throws Exception {
    FontBuilder.Request r = new FontBuilder.Request();
    r.name = c.display;
    byte[] reg = font(c.regular);
    if (c.dsig) reg = withDsig(reg);
    r.regular = new FontBuilder.Face(reg, c.regularLoc);
    if (c.bold != null) r.bold = new FontBuilder.Face(c.bold.equals(c.regular) ? (c.boldCopy ? reg.clone() : reg) : font(c.bold), c.boldLoc);
    if (c.emoji != null) r.emoji = font(c.emoji);
    r.time = TIME;
    return r;
  }

  /** Every step a build reported, and how often it asked whether it was cancelled. */
  static final class Recorder implements FontProgress {
    final List<Object[]> steps = new ArrayList<Object[]>();
    int asked;
    @Override public void step(String stage, int done, int total) { steps.add(new Object[] { stage, done, total }); }
    @Override public boolean cancelled() { asked++; return false; }
  }

  static final class Built {
    FontBuilder.Result result;
    Recorder progress = new Recorder();
    long ms;
  }

  static final Map<String, Built> BUILT = new LinkedHashMap<String, Built>();

  static Built built(Case c) throws Exception {
    Built b = BUILT.get(c.name);
    if (b != null) return b;
    b = new Built();
    long t = System.nanoTime();
    b.result = FontBuilder.build(request(c), b.progress);
    b.ms = (System.nanoTime() - t) / 1000000L;
    BUILT.put(c.name, b);
    return b;
  }

  // ---------- what fontTools reads, computed from our own reading (the lines of make_fixtures.py face_lines) ----------

  static final class Expected {
    final Map<String, String> sources = new LinkedHashMap<String, String>();
    final Map<String, List<String>> faces = new LinkedHashMap<String, List<String>>();

    static Expected read(File f) throws IOException {
      Expected e = new Expected();
      List<String> cur = null;
      for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
        if (line.startsWith("#") || line.isEmpty()) continue;
        if (line.startsWith("source ")) {
          String[] p = line.split(" ");
          e.sources.put(p[1], p[2]);
        } else if (line.startsWith("face ")) {
          cur = new ArrayList<String>();
          e.faces.put(line.substring(5), cur);
        } else if (line.equals("end")) {
          cur = null;
        } else if (cur != null) {
          cur.add(line);
        }
      }
      return e;
    }
  }

  static String crc(List<String> lines) {
    CRC32 c = new CRC32();
    for (String l : lines) c.update((l + "\n").getBytes(StandardCharsets.UTF_8));
    return String.format(Locale.ROOT, "%08x", c.getValue());
  }

  static String escape(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      i += Character.charCount(cp);
      if (cp >= 0x20 && cp < 0x7F && cp != '\\') b.append((char) cp);
      else if (cp < 0x10000) b.append(String.format(Locale.ROOT, "\\u%04x", cp));
      else b.append(String.format(Locale.ROOT, "\\U%08x", cp));
    }
    return b.toString();
  }

  /** Every record of a name table: platform, encoding, language, name id, text. */
  static List<Object[]> nameRecords(byte[] t) throws Exception {
    List<Object[]> out = new ArrayList<Object[]>();
    int count = Sfnt.u16(t, 2), strOff = Sfnt.u16(t, 4);
    for (int i = 0; i < count; i++) {
      int r = 6 + 12 * i, pid = Sfnt.u16(t, r), len = Sfnt.u16(t, r + 8), off = Sfnt.u16(t, r + 10);
      if (strOff + off + len > t.length) throw new IOException("name record " + i + " points outside the table");
      byte[] b = Arrays.copyOfRange(t, strOff + off, strOff + off + len);
      String s = pid == 1 ? new String(b, StandardCharsets.ISO_8859_1) : new String(b, StandardCharsets.UTF_16BE);
      out.add(new Object[] { pid, Sfnt.u16(t, r + 2), Sfnt.u16(t, r + 4), Sfnt.u16(t, r + 6), s });
    }
    return out;
  }

  static String glyphLine(byte[] raw) throws Exception {
    Glyf.Glyph g = Glyf.decode(raw);
    if (g.contours == 0) return "E";
    StringBuilder b = new StringBuilder();
    if (g.isComposite()) {
      b.append("C ");
      for (int i = 0; i < g.components.size(); i++) {
        Glyf.Component k = g.components.get(i);
        if (i > 0) b.append(';');
        b.append(k.glyph).append(',').append(k.xy ? "" : "p").append(k.dx).append(',').append(k.dy)
            .append(',').append(k.a).append(',').append(k.b).append(',').append(k.c).append(',').append(k.d);
      }
    } else {
      b.append("S ");
      for (int i = 0; i < g.endPts.length; i++) b.append(i > 0 ? "," : "").append(g.endPts[i]);
      b.append(' ');
      for (int i = 0; i < g.x.length; i++) b.append(i > 0 ? ";" : "").append(g.x[i]).append(',').append(g.y[i]).append(',').append(g.on[i] ? 1 : 0);
    }
    return b.append(" i").append(g.instructions.length).toString();
  }

  static String fmtG(double v) { return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v); }

  static List<String> faceLines(byte[] bytes) throws Exception {
    Sfnt f = Sfnt.parse(bytes);
    List<String> out = new ArrayList<String>();
    List<String> tags = f.tags();
    Collections.sort(tags);
    out.add("tables " + String.join(" ", tags));
    int n = f.numGlyphs();
    out.add("glyphs " + n);
    for (Object[] r : nameRecords(f.table("name"))) out.add("name " + r[0] + " " + r[1] + " " + r[2] + " " + r[3] + " " + escape((String) r[4]));
    byte[] os2 = f.table("OS/2"), head = f.table("head");
    out.add(String.format(Locale.ROOT, "os2 %d 0x%04x", Sfnt.u16(os2, 4), Sfnt.u16(os2, 62)));
    out.add("head " + Sfnt.u16(head, 44) + " " + Long.toUnsignedString((Sfnt.u32(head, 28) << 32) | Sfnt.u32(head, 32)));
    out.add("post " + fmtG(Sfnt.s32(f.table("post"), 0) / 65536.0));
    TreeMap<Integer, Integer> cmap = Cmap.read(f);
    List<String> lines = new ArrayList<String>();
    for (Map.Entry<Integer, Integer> e : cmap.entrySet()) lines.add(e.getKey() + " " + e.getValue());
    out.add("cmap " + cmap.size() + " " + crc(lines));
    Glyf.Metrics m = Glyf.Metrics.read(f);
    lines.clear();
    for (int g = 0; g < n; g++) lines.add(m.advance[g] + " " + m.lsb[g]);
    out.add("hmtx " + crc(lines));
    lines.clear();
    for (byte[] raw : Glyf.readAll(f)) lines.add(glyphLine(raw));
    out.add("glyf " + crc(lines));
    return out;
  }

  static String sha(byte[] b) throws Exception {
    StringBuilder s = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
    return s.toString();
  }

  static String locSpec(Map<String, Double> loc) {
    if (loc == null) return "-";
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, Double> e : loc.entrySet()) b.append(b.length() > 0 ? "," : "").append(e.getKey()).append(':').append(fmtG(e.getValue()));
    return b.toString();
  }

  // ---------- main ----------

  public static void main(String[] args) throws Exception {
    if (args.length >= 1 && args[0].equals("--e2e")) {
      e2e(args);
      return;
    }
    File fix = fixtureDir();
    if (args.length == 2 && args[0].equals("--dump")) {
      if (fix == null) throw new IOException("fixtures not found");
      load(fix);
      dump(new File(args[1]));
      return;
    }
    if (args.length == 3 && args[0].equals("--fuzz")) {
      if (fix == null) throw new IOException("fixtures not found");
      load(fix);
      fuzz(Long.parseLong(args[1], 16), Integer.parseInt(args[2]), 0);
      System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
      System.exit(fails == 0 ? 0 : 1);
    }
    if (fix == null || !new File(fix, INTER).isFile()) {
      check("the fixtures are found (tests/java/fixtures/fonts/fontbuilder)", false);
    } else {
      load(fix);
      File exf = new File(fix, "expected.txt");
      final Expected ex = exf.isFile() ? Expected.read(exf) : null;
      check("expected.txt is there", ex != null, "run --dump DIR, then make_fixtures.py expect DIR");
      if (ex != null) section("fixtures", () -> fixtures(ex));
      section("cases", () -> cases(ex));
      section("the engines' own output", FontBuilderTest::engines);
      section("style and names", FontBuilderTest::styleAndNames);
      section("the name table's limits", FontBuilderTest::nameLimits);
      section("refusals", FontBuilderTest::refusals);
      section("damaged glyphs", FontBuilderTest::damagedGlyphs);
      section("bitmap glyphs", FontBuilderTest::bitmaps);
      section("progress", FontBuilderTest::progress);
      section("cancel", FontBuilderTest::cancel);
      section("fuzz", () -> fuzz(0x26102026L, 600, 12));
    }
    section("steps", FontBuilderTest::steps);
    section("fitNames", FontBuilderTest::fitNamesUnit);
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  static void dump(File out) throws Exception {
    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot make " + out);
    StringBuilder cases = new StringBuilder();
    for (Case c : CASES) {
      FontBuilder.Result r = built(c).result;
      String ro = c.name + "-Regular.ttf", bo = r.bold == null ? "-" : c.name + "-Bold.ttf";
      Files.write(new File(out, ro).toPath(), r.regular);
      if (r.bold != null) Files.write(new File(out, bo).toPath(), r.bold);
      cases.append("case\t").append(c.name).append("\tregular=").append(c.regular).append("\tregularLoc=").append(locSpec(r.regularLocation))
          .append("\tregularOut=").append(ro).append("\tbold=").append(c.bold == null ? "-" : c.bold).append("\tboldLoc=").append(locSpec(r.boldLocation))
          .append("\tboldOut=").append(bo).append("\temoji=").append(c.emoji == null ? "-" : c.emoji).append('\n');
      System.out.println(c.name + ": " + r.regular.length + (r.bold == null ? "" : " + " + r.bold.length) + " bytes, " + r.timings + ", " + r.warnings);
    }
    Files.write(new File(out, "cases.txt").toPath(), cases.toString().getBytes(StandardCharsets.UTF_8));
  }

  // ---------- fixtures and cases ----------

  static void fixtures(Expected ex) throws Exception {
    for (String f : FILES) {
      String want = ex.sources.get(f), got = sha(font(f));
      if (want == null) check(f + ": in expected.txt", false, "run make_fixtures.py expect");
      else if (!want.equals(got)) note(f + ": not the file expected.txt was made from (SHA-256 " + got.substring(0, 16) + "...); run --dump DIR and make_fixtures.py expect DIR");
      else check(f + ": is the file expected.txt was made from", true);
    }
  }

  static final String[] DROPPED = { "fvar", "gvar", "cvar", "avar", "HVAR", "VVAR", "MVAR", "STAT", "CFF ", "CFF2", "VORG", "DSIG" };

  static void cases(Expected ex) throws Exception {
    for (Case c : CASES) {
      Built b = built(c);
      FontBuilder.Result r = b.result;
      String fam = FontPackage.cleanName(c.display);
      check(c.name + ": built (" + b.ms + " ms)", r.regular != null && (c.bold == null) == (r.bold == null));
      check(c.name + ": the family is the cleaned display name", fam.equals(r.family), r.family);
      for (String face : new String[] { "Regular", "Bold" }) {
        byte[] bytes = face.equals("Regular") ? r.regular : r.bold;
        if (bytes == null) continue;
        String label = c.name + " " + face;
        if (ex != null) {
          List<String> want = ex.faces.get(label);
          if (want == null) check(label + ": in expected.txt", false, "run --dump DIR, then make_fixtures.py expect DIR");
          else {
            List<String> got = faceLines(bytes);
            String diff = null;
            for (int i = 0; i < Math.max(got.size(), want.size()) && diff == null; i++) {
              String g = i < got.size() ? got.get(i) : "(nothing)", w = i < want.size() ? want.get(i) : "(nothing)";
              if (!g.equals(w)) diff = "line " + (i + 1) + ": ours " + cut(g) + " / fontTools " + cut(w);
            }
            check(label + ": what fontTools read in this face, line by line (" + want.size() + " lines)", diff == null, diff);
          }
        }
        Sfnt f = Sfnt.parse(bytes);
        boolean bold = face.equals("Bold"), italic = c.regular.equals(NOTO_ITALIC);
        String style = bold ? "Bold" : italic ? "Italic" : "Regular";
        Map<Integer, String> n = f.names();
        check(label + ": names 1, 2 and 4 are the family, the style and both", fam.equals(n.get(1)) && style.equals(n.get(2))
            && (fam + " " + style).equals(n.get(4)), n.get(1) + " / " + n.get(2) + " / " + n.get(4));
        check(label + ": name 6 is the PostScript name", FontBuilder.postScriptName(fam, style).equals(n.get(6)) && n.get(6).matches("[A-Za-z0-9-]{1,63}"), n.get(6));
        check(label + ": name 3 is unique to this family, style and day", (fam + " " + style + "; " + DAY).equals(n.get(3)), n.get(3));
        boolean none = true;
        for (int id : new int[] { 16, 17, 18, 20, 21, 22, 25 }) none &= !n.containsKey(id);
        check(label + ": no typographic, WWS or variation names", none, n.keySet().toString());
        byte[] os2 = f.table("OS/2"), head = f.table("head");
        int fs = Sfnt.u16(os2, 62);
        check(label + ": OS/2 weight " + (bold ? 700 : 400), Sfnt.u16(os2, 4) == (bold ? 700 : 400), "" + Sfnt.u16(os2, 4));
        check(label + ": fsSelection " + (bold ? "BOLD" : "") + (italic ? " ITALIC" : "") + (!bold && !italic ? "REGULAR" : ""),
            ((fs & 0x20) != 0) == bold && ((fs & 1) != 0) == italic && ((fs & 0x40) != 0) == (!bold && !italic), String.format("0x%04x", fs));
        check(label + ": macStyle bold " + bold + ", italic " + italic, (Sfnt.u16(head, 44) & 3) == ((bold ? 1 : 0) | (italic ? 2 : 0)), "" + Sfnt.u16(head, 44));
        long modified = (Sfnt.u32(head, 28) << 32) | Sfnt.u32(head, 32);
        check(label + ": head.modified is the build's time", modified == TIME / 1000 + 2082844800L, "" + modified);
        List<String> left = new ArrayList<String>();
        for (String t : DROPPED) if (f.has(t)) left.add(t);
        check(label + ": static, TrueType, no DSIG", f.sfntVersion == Sfnt.TRUETYPE && f.has("glyf") && left.isEmpty(), left.toString());
        if (c.emoji != null) check(label + ": has the emoji (COLR, CPAL)", f.has("COLR") && f.has("CPAL"));
      }
      boolean warned = r.warnings.contains(FontBuilder.W_BOLD_NOT_BOLDER);
      check(c.name + ": " + (c.name.equals("same") ? "warns" : "does not warn") + " that the Bold is no heavier", warned == c.name.equals("same"), r.warnings.toString());
      check(c.name + ": " + (c.variable() ? "the locations made static" : "no locations (static sources)"),
          c.variable() == (r.regularLocation != null) && (r.boldLocation != null) == (c.variable() && c.bold != null),
          r.regularLocation + " / " + r.boldLocation);
      Set<String> want = new HashSet<String>(Arrays.asList(FontBuilder.READ, FontBuilder.WRITE));
      if (c.variable()) want.add(FontBuilder.INSTANCE);
      if (c.regular.equals(SS3)) want.add(FontBuilder.CONVERT);
      if (c.emoji != null) want.add(FontBuilder.EMOJI);
      check(c.name + ": timings for " + want, r.timings.keySet().equals(want), r.timings.toString());
      boolean same = true;
      for (String fn : FILES) same &= Arrays.equals(font(fn), PRISTINE.get(fn));
      check(c.name + ": the sources are as they were", same);
    }
    FontBuilder.Result v = built(CASES.get(0)).result;
    check("var: the Regular is at wght 400, the Bold at wght 700 (one file)", v.regularLocation != null && v.boldLocation != null
        && Double.valueOf(400).equals(v.regularLocation.get("wght")) && Double.valueOf(700).equals(v.boldLocation.get("wght")),
        v.regularLocation + " / " + v.boldLocation);
    check("var: the two faces differ", !Arrays.equals(v.regular, v.bold));
    FontBuilder.Result s = built(CASES.get(5)).result;
    check("same: the Bold is the Regular renamed (same outlines)", Arrays.equals(Sfnt.parse(s.regular).table("glyf"), Sfnt.parse(s.bold).table("glyf")));
  }

  static String cut(String s) { return s.length() <= 160 ? s : s.substring(0, 157) + "..."; }

  // ---------- the engines' own output ----------

  /** What the engines make of one face without FontBuilder: instance, conversion, merge, nothing renamed. */
  static Sfnt direct(byte[] data, Map<String, Double> loc, byte[] emoji) throws Exception {
    Sfnt src = Sfnt.parse(data), f;
    if (src.isVariable()) {
      VarStore.Fvar fv = VarStore.Fvar.read(src);
      FontInstancer.Options o = new FontInstancer.Options();
      o.keepHinting = false;
      f = FontInstancer.instance(src, FontInstancer.userCoords(fv, loc), o);
    } else if (!src.has("glyf")) {
      f = CffToGlyf.convert(src, null, FontProgress.NONE);
    } else {
      f = src.copy();
      for (String t : FontBuilder.STATIC_DROPPED) f.remove(t);
    }
    if (emoji != null) f = EmojiMerge.merge(f, Sfnt.parse(emoji), null).font;
    return Sfnt.parse(f.toBytes());
  }

  static boolean sameExcept(byte[] a, byte[] b, int... ranges) {
    if (a == null || b == null || a.length != b.length) return false;
    a = a.clone(); b = b.clone();
    for (int i = 0; i < ranges.length; i += 2) for (int k = ranges[i]; k < ranges[i + 1] && k < a.length; k++) a[k] = b[k] = 0;
    return Arrays.equals(a, b);
  }

  static void engines() throws Exception {
    for (Case c : CASES) {
      FontBuilder.Result r = built(c).result;
      for (int face = 0; face < 2; face++) {
        byte[] bytes = face == 0 ? r.regular : r.bold;
        if (bytes == null) continue;
        String label = c.name + " " + (face == 0 ? "Regular" : "Bold");
        String src = face == 0 ? c.regular : c.bold;
        Map<String, Double> loc = face == 0 ? r.regularLocation : r.boldLocation;
        Sfnt mine = Sfnt.parse(bytes), theirs = direct(font(src), loc, c.emoji == null ? null : font(c.emoji));
        Set<String> tags = new HashSet<String>(mine.tags());
        tags.addAll(theirs.tags());
        tags.remove("DSIG");
        List<String> differ = new ArrayList<String>();
        for (String t : tags) {
          byte[] a = mine.table(t), b = theirs.table(t);
          boolean ok = t.equals("name") ? a != null && b != null
              : t.equals("OS/2") ? sameExcept(a, b, 4, 6, 62, 64)
              : t.equals("head") ? sameExcept(a, b, 8, 12, 28, 36, 44, 46)
              : Arrays.equals(a, b);
          if (!ok) differ.add(t);
        }
        check(label + ": every table but name, the OS/2 weight and fsSelection and head's checksum, modified and macStyle is the engines' own",
            differ.isEmpty(), differ.toString());
      }
    }
  }

  // ---------- style and names ----------

  static Map<Integer, Integer> recordsPerId(byte[] name, int platform) throws Exception {
    Map<Integer, Integer> m = new TreeMap<Integer, Integer>();
    for (Object[] r : nameRecords(name)) if ((Integer) r[0] == platform) m.merge((Integer) r[3], 1, Integer::sum);
    return m;
  }

  /** A cmap with only a (3, 0) symbol subtable mapping U+F020..F07E. */
  static byte[] symbolCmap() {
    Sfnt.Writer w = new Sfnt.Writer();
    w.u16(0).u16(1).u16(3).u16(0).u32(12);
    int segs = 2;
    w.u16(4).u16(16 + 8 * segs).u16(0).u16(2 * segs).u16(4).u16(1).u16(0);
    w.u16(0xF07E).u16(0xFFFF).u16(0).u16(0xF020).u16(0xFFFF);
    w.u16((1 - 0xF020) & 0xFFFF).u16(1).u16(0).u16(0);
    return w.toByteArray();
  }

  static FontBuilder.Request simple(byte[] regular, String name) {
    FontBuilder.Request r = new FontBuilder.Request();
    r.name = name;
    r.regular = new FontBuilder.Face(regular, null);
    r.time = TIME;
    return r;
  }

  static void styleAndNames() throws Exception {
    // Bold Italic: both bits, no REGULAR; USE_TYPO_METRICS and the other fsSelection bits kept
    Sfnt f = Sfnt.parse(font(NOTO_ITALIC));
    int srcFs = Sfnt.u16(f.table("OS/2"), 62);
    byte[] srcOs2 = f.table("OS/2").clone(), srcHead = f.table("head").clone();
    FontBuilder.style(f, "Fam", true, true, TIME);
    Map<Integer, String> n = f.names();
    int fs = Sfnt.u16(f.table("OS/2"), 62);
    check("style: Bold Italic is named so, PostScript name Fam-BoldItalic", "Bold Italic".equals(n.get(2)) && "Fam Bold Italic".equals(n.get(4))
        && "Fam-BoldItalic".equals(n.get(6)), n.toString());
    check("style: Bold Italic sets ITALIC and BOLD, clears REGULAR, keeps the other bits", fs == ((srcFs & ~0x61) | 0x21), String.format("0x%04x from 0x%04x", fs, srcFs));
    check("style: macStyle bold and italic", (Sfnt.u16(f.table("head"), 44) & 3) == 3);
    check("style: the source's tables are not written into", Arrays.equals(srcOs2, Sfnt.parse(font(NOTO_ITALIC)).table("OS/2"))
        && Arrays.equals(srcHead, Sfnt.parse(font(NOTO_ITALIC)).table("head")) && Arrays.equals(font(NOTO_ITALIC), PRISTINE.get(NOTO_ITALIC)));

    // Names kept from the source: copyright, version, license; the source's own family names gone
    Map<Integer, String> src = Sfnt.parse(font(NOTO)).names();
    Map<Integer, String> out = Sfnt.parse(built(CASES.get(3)).result.regular).names();
    boolean kept = true;
    for (int id : new int[] { 0, 5, 7, 8, 9, 11, 12, 13, 14 }) if (src.containsKey(id)) kept &= src.get(id).equals(out.get(id));
    check("names: copyright, version, trademark, manufacturer, designer, URLs and license kept", kept, out.toString());

    // A name outside ASCII: Windows records only for it; the PostScript name folded to Latin
    Sfnt cafe = Sfnt.parse(built(CASES.get(1)).result.regular);
    Map<Integer, Integer> mac = recordsPerId(cafe.table("name"), 1), win = recordsPerId(cafe.table("name"), 3);
    check("names: Caf\u00e9 Sans has no Mac record for names 1, 3, 4 (not ASCII) but has one for 2 and 6", !mac.containsKey(1) && !mac.containsKey(3)
        && !mac.containsKey(4) && mac.containsKey(2) && mac.containsKey(6) && win.containsKey(1) && win.containsKey(4), mac + " / " + win);
    check("names: Caf\u00e9 Sans's PostScript name is CafeSans-Regular", "CafeSans-Regular".equals(cafe.names().get(6)), cafe.names().get(6));
    String ps = FontBuilder.postScriptName("\u0428\u0440\u0438\u0444\u0442 \u0422\u0435\u0441\u0442", "Bold");
    check("names: a Cyrillic name gives a PostScript name of Latin letters and digits", ps.matches("[A-Za-z0-9]+-Bold") && ps.length() <= 63, ps);
    String longName = "Aa Bb Cc Dd Ee Ff Gg Hh Ii Jj Kk Ll Mm Nn Oo Pp Qq Rr Ss Tt Uu Vv Ww Xx Yy Zz";
    FontBuilder.Result lr = FontBuilder.build(simple(font(NOTO), longName), null);
    String lps = Sfnt.parse(lr.regular).names().get(6);
    check("names: a 77-character display name: the family is the cleaned 64, the PostScript name at most 63", lr.family.length() == FontPackage.MAX_NAME
        && lps.matches("[A-Za-z0-9-]{1,63}"), lr.family + " / " + lps);
    check("names: a name with only digits and signs gets the Font fallback", FontBuilder.postScriptName("\u2603\u2603", "Regular").equals("Font-Regular"),
        FontBuilder.postScriptName("\u2603\u2603", "Regular"));

    // Inter's variation names (25) and typographic names (16, 17) left out
    Map<Integer, String> interSrc = Sfnt.parse(font(INTER)).names(), interOut = Sfnt.parse(built(CASES.get(0)).result.regular).names();
    check("names: Inter has names 16 / 17 / 25 and the build leaves them out", (interSrc.containsKey(16) || interSrc.containsKey(25))
        && !interOut.containsKey(16) && !interOut.containsKey(17) && !interOut.containsKey(25), interSrc.keySet() + " -> " + interOut.keySet());

    // No version name: "Version 1.000"
    Sfnt nv = Sfnt.parse(font(NOTO));
    Map<Integer, String> nn = nv.names();
    nn.remove(5);
    nv.put("name", Sfnt.buildName(nn));
    FontBuilder.Result nvr = FontBuilder.build(simple(nv.toBytes(), "No Version"), null);
    check("names: a font without a version name gets Version 1.000", "Version 1.000".equals(Sfnt.parse(nvr.regular).names().get(5)));

    // No OS/2, and a short OS/2
    Sfnt no = Sfnt.parse(font(NOTO));
    no.remove("OS/2");
    FontBuilder.Request rq = simple(no.toBytes(), "No OS2");
    rq.bold = new FontBuilder.Face(rq.regular.data, null);
    FontBuilder.Result nor = FontBuilder.build(rq, null);
    Sfnt nob = Sfnt.parse(nor.bold);
    check("style: a font without OS/2 is built, without one, macStyle bold set on the Bold", !nob.has("OS/2") && (Sfnt.u16(nob.table("head"), 44) & 1) == 1);
    Sfnt sh = Sfnt.parse(font(NOTO));
    sh.put("OS/2", Arrays.copyOf(sh.table("OS/2"), 10));
    FontBuilder.Request rs = simple(sh.toBytes(), "Short OS2");
    rs.bold = new FontBuilder.Face(rs.regular.data, null);
    byte[] so = Sfnt.parse(FontBuilder.build(rs, null).bold).table("OS/2");
    check("style: a 10-byte OS/2 gets its weight (700) and nothing written past its end", so.length == 10 && Sfnt.u16(so, 4) == 700, "" + so.length);

    // head.modified before 1970 and far ahead
    Sfnt t = Sfnt.parse(font(NOTO));
    FontBuilder.style(t, "T", false, false, -1000L);
    check("style: a time before 1970 is still seconds since 1904", ((Sfnt.u32(t.table("head"), 28) << 32) | Sfnt.u32(t.table("head"), 32)) == 2082844799L);
    t = Sfnt.parse(font(NOTO));
    FontBuilder.style(t, "T", false, false, 4102444800000L);
    check("style: 2100-01-01 fits", ((Sfnt.u32(t.table("head"), 28) << 32) | Sfnt.u32(t.table("head"), 32)) == 4102444800L + 2082844800L
        && t.names().get(3).endsWith("; 2100-01-01"), t.names().get(3));

    // time 0 is now
    FontBuilder.Request now = simple(font(NOTO), "Now");
    now.time = 0;
    long before = System.currentTimeMillis() / 1000;
    byte[] nh = Sfnt.parse(FontBuilder.build(now, null).regular).table("head");
    long secs = ((Sfnt.u32(nh, 28) << 32) | Sfnt.u32(nh, 32)) - 2082844800L;
    check("style: time 0 stamps now", secs >= before - 1 && secs <= System.currentTimeMillis() / 1000 + 1, "" + secs);
  }

  // ---------- the name table's limits ----------

  static void fitNamesUnit() throws Exception {
    Map<Integer, String> n = new TreeMap<Integer, String>();
    for (int id : new int[] { 1, 2, 3, 4, 6 }) n.put(id, "Name " + id);
    n.put(0, "Copyright");
    n.put(13, repeat('x', 30000));
    n.put(100, repeat('y', 20000));
    n.put(101, "z");
    FontBuilder.fitNames(n);
    check("fitNames: too much text: the optional names go first, then the license is cut", !n.containsKey(100) && n.get(13).endsWith("\u2026")
        && n.get(13).length() == 15001 && "Copyright".equals(n.get(0)) && FontBuilder.storage(n) <= FontBuilder.NAME_STORAGE, n.keySet() + " " + FontBuilder.storage(n));
    byte[] t = Sfnt.buildName(n);
    check("fitNames: the table it gives reads back", "Name 1".equals(Sfnt.nameString(t, 1)) && n.get(13).equals(Sfnt.nameString(t, 13)));

    n = new TreeMap<Integer, String>();
    for (int id : new int[] { 1, 2, 3, 4, 6 }) n.put(id, "Name " + id);
    for (int id = 256; id < 6256; id++) n.put(id, "n" + id);
    FontBuilder.fitNames(n);
    t = Sfnt.buildName(n);
    int records = Sfnt.u16(t, 2);
    check("fitNames: 6,005 names: cut to the records a table can reach", records <= FontBuilder.NAME_RECORDS && records > 5000 && Sfnt.u16(t, 4) == 6 + 12 * records
        && "Name 6".equals(Sfnt.nameString(t, 6)), "" + records);

    n = new TreeMap<Integer, String>();
    for (int id : new int[] { 1, 2, 3, 4, 6 }) n.put(id, "Name " + id);
    n.put(13, repeat('\u00e9', 40000));
    n.put(14, "\ud83d\ude00" + repeat('w', 33000));
    FontBuilder.fitNames(n);
    check("fitNames: two long kept names outside ASCII: halved until they fit, no surrogate split", FontBuilder.storage(n) <= FontBuilder.NAME_STORAGE
        && n.get(13).endsWith("\u2026") && n.get(14).startsWith("\ud83d\ude00"), "" + FontBuilder.storage(n));

    n = new TreeMap<Integer, String>();
    n.put(1, "A");
    Map<Integer, String> copy = new TreeMap<Integer, String>(n);
    FontBuilder.fitNames(n);
    check("fitNames: names that fit are left as they are", n.equals(copy));
  }

  static String repeat(char c, int n) {
    char[] a = new char[n];
    Arrays.fill(a, c);
    return new String(a);
  }

  static void nameLimits() throws Exception {
    // 65,535 records, all sharing one string: names() and fitNames must stay linear
    int count = 0xFFFF;
    Sfnt.Writer w = new Sfnt.Writer(6 + 12 * count + 2);
    w.u16(0).u16(count).u16(6 + 12 * count);
    for (int i = 0; i < count; i++) w.u16(3).u16(1).u16(0x409).u16(i).u16(2).u16(0);
    w.u16('A');
    Sfnt f = Sfnt.parse(font(NOTO));
    f.put("name", w.toByteArray());
    long t = System.nanoTime();
    FontBuilder.Result r = FontBuilder.build(simple(f.toBytes(), "Many Names"), null);
    long ms = (System.nanoTime() - t) / 1000000L;
    byte[] name = Sfnt.parse(r.regular).table("name");
    int records = Sfnt.u16(name, 2);
    check("names: a name table of 65,535 records is built in under 3 s (" + ms + " ms), cut to " + records + " records that read back",
        ms < 3000 && records <= FontBuilder.NAME_RECORDS && "Many Names".equals(Sfnt.nameString(name, 1)) && "Many Names Regular".equals(Sfnt.nameString(name, 4)));

    // A license and a license URL of 30,000 characters each (120 KB of UTF-16): both cut, not written past the 16-bit offsets
    Sfnt g = Sfnt.parse(font(NOTO));
    Map<Integer, String> n = new TreeMap<Integer, String>();
    n.put(1, "Source");
    n.put(2, "Regular");
    n.put(13, repeat('\u00e9', 30000));
    n.put(14, repeat('\u00fc', 30000));
    g.put("name", Sfnt.buildName(n));
    check("names: the source's two 30,000-character names read back", repeat('\u00fc', 30000).equals(g.names().get(14)));
    FontBuilder.Result lr = FontBuilder.build(simple(g.toBytes(), "Long License"), null);
    Map<Integer, String> out = Sfnt.parse(lr.regular).names();
    check("names: two 30,000-character names are cut so the table's offsets hold", out.get(13) != null && out.get(13).endsWith("\u2026")
        && out.get(14) != null && out.get(14).endsWith("\u2026") && out.get(13).length() == 15001 && out.get(14).length() == 15001
        && "Long License".equals(out.get(1)), out.get(13) == null ? "no 13" : out.get(13).length() + " / " + (out.get(14) == null ? "no 14" : out.get(14).length()));
  }

  // ---------- refusals ----------

  static void refusals() throws Exception {
    try {
      FontBuilder.build(null, null);
      check("no request: IllegalArgumentException", false);
    } catch (IllegalArgumentException e) {
      check("no request: IllegalArgumentException", true);
    }
    throwsFont("no name: bad_name", "bad_name", null, () -> FontBuilder.build(simple(font(NOTO), " \u0007\t "), null));
    throwsFont("no Regular: no_regular", "no_regular", null, () -> {
      FontBuilder.Request r = simple(null, "X");
      FontBuilder.build(r, null);
    });
    throwsFont("no Regular face: no_regular", "no_regular", null, () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.regular = null;
      r.bold = new FontBuilder.Face(font(NOTO), null);
      FontBuilder.build(r, null);
    });
    for (double s : new double[] { 0.5, 1.3, Double.NaN, Double.POSITIVE_INFINITY }) {
      throwsFont("emoji size " + s + ": bad_request", "bad_request", null, () -> {
        FontBuilder.Request r = simple(font(NOTO), "X");
        r.emoji = font(TWEMOJI);
        r.emojiScale = s;
        FontBuilder.build(r, null);
      });
    }
    FontBuilder.Request ok = simple(font(NOTO), "X");
    ok.emojiScale = 3;
    check("an emoji size out of range without emoji is not asked about", FontBuilder.build(ok, null).regular != null);
    FontBuilder.Request big = simple(font(NOTO), "X");
    big.emoji = font(TWEMOJI);
    big.emojiScale = EmojiMerge.MAX_SCALE;
    check("the largest emoji size builds", FontBuilder.build(big, null).regular != null);

    byte[] junk = new byte[200];
    new Random(7).nextBytes(junk);
    throwsFont("random bytes as the Regular: not_a_font, says Regular font", "not_a_font", "Regular font: ", () -> FontBuilder.build(simple(junk, "X"), null));
    byte[] woff = Arrays.copyOf("wOFF".getBytes(StandardCharsets.ISO_8859_1), 100);
    throwsFont("a WOFF as the Bold: woff, says Bold font", "woff", "Bold font: ", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.bold = new FontBuilder.Face(woff, null);
      FontBuilder.build(r, null);
    });
    byte[] ttc = Arrays.copyOf("ttcf".getBytes(StandardCharsets.ISO_8859_1), 100);
    throwsFont("a collection as the emoji font: collection, says Emoji font", "collection", "Emoji font: ", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.emoji = ttc;
      FontBuilder.build(r, null);
    });

    // Bitmap emoji: without outlines (refused by FontInspect's rule) and with them (EmojiMerge's)
    Sfnt cb = Sfnt.parse(font(NOTO));
    cb.remove("glyf");
    cb.remove("loca");
    cb.put("CBDT", new byte[] { 0, 3, 0, 0 });
    cb.put("CBLC", new byte[] { 0, 3, 0, 0, 0, 0, 0, 0 });
    byte[] cbdt = cb.toBytes();
    Recorder rec = new Recorder();
    Sfnt.FontException e = throwsFont("a CBDT emoji font without outlines: bitmap_emoji", "bitmap_emoji", null, () -> {
      FontBuilder.Request r = simple(font(INTER), "X");
      r.emoji = cbdt;
      FontBuilder.build(r, rec);
    });
    check("  ... that says Emoji font in front, for the page", e != null && e.getMessage().startsWith("Emoji font: "), e == null ? null : e.getMessage());
    boolean instanced = false;
    for (Object[] s : rec.steps) instanced |= FontBuilder.INSTANCE.equals(s[0]);
    check("  ... before the variable Regular is made static", !instanced);
    Sfnt cg = Sfnt.parse(font(NOTO));
    cg.put("CBDT", new byte[] { 0, 3, 0, 0 });
    cg.put("CBLC", new byte[] { 0, 3, 0, 0, 0, 0, 0, 0 });
    byte[] cbdtGlyf = cg.toBytes();
    throwsFont("a CBDT emoji font with outlines: bitmap_emoji", "bitmap_emoji", null, () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.emoji = cbdtGlyf;
      FontBuilder.build(r, null);
    });
    throwsFont("a text font as the emoji font: not_emoji, says Emoji font", "not_emoji", "Emoji font: ", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.emoji = font(NOTO);
      FontBuilder.build(r, null);
    });
    // the refusals that are not damage have codes of their own, so the page does not call the file damaged, and the font in front
    throwsFont("a Regular font that has COLR already: text_has_color, says Regular font", "text_has_color", "Regular font: This font already has colour glyphs", () -> {
      FontBuilder.Request r = simple(font(TWEMOJI), "X");
      r.emoji = font(NOTO_EMOJI);
      FontBuilder.build(r, null);
    });
    Sfnt sy = Sfnt.parse(font(NOTO));
    sy.put("cmap", symbolCmap());
    byte[] sym = sy.toBytes();
    throwsFont("a symbol Bold font with emoji: symbol_font, says Bold font", "symbol_font", "Bold font: This is a symbol font", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.bold = new FontBuilder.Face(sym, null);
      r.emoji = font(NOTO_EMOJI);
      FontBuilder.build(r, null);
    });
    check("a symbol Regular without emoji: built", FontBuilder.build(simple(sym, "X"), null).regular != null);
    Sfnt ec = Sfnt.parse(font(TWEMOJI));
    ec.remove("glyf");
    ec.remove("loca");
    ec.put("CFF ", new byte[64]);
    byte[] emojiCff = ec.toBytes();
    throwsFont("a COLR emoji font with PostScript outlines: emoji_outlines, says Emoji font", "emoji_outlines", "Emoji font: ", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.emoji = emojiCff;
      FontBuilder.build(r, null);
    });
    Sfnt np = Sfnt.parse(font(TWEMOJI));
    np.remove("CPAL");
    byte[] noPalette = np.toBytes();
    throwsFont("a COLR emoji font without CPAL: no_palette, says Emoji font", "no_palette", "Emoji font: ", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.emoji = noPalette;
      FontBuilder.build(r, null);
    });
    // what the merge meets in the emoji font while a face is made (the pick reads only COLR's header) names the emoji font, not the face
    Sfnt v2 = Sfnt.parse(font(NOTO_EMOJI));
    byte[] colr2 = v2.table("COLR").clone();
    Sfnt.w16(colr2, 0, 2);
    v2.put("COLR", colr2);
    byte[] colrV2 = v2.toBytes();
    throwsFont("an emoji font with a COLR version 2: emoji_damaged, says Emoji font", "emoji_damaged",
        "Emoji font: The emoji font's colour table (COLR) is a newer version (2)", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.emoji = colrV2;
      FontBuilder.build(r, null);
    });
    Sfnt bl = Sfnt.parse(font(TWEMOJI));
    byte[] colr0 = bl.table("COLR").clone();
    Sfnt.w16(colr0, (int) Sfnt.u32(colr0, 8), 65000);                    // the first layer's glyph
    bl.put("COLR", colr0);
    byte[] badLayer = bl.toBytes();
    throwsFont("an emoji font whose COLR names glyph 65,000: emoji_damaged, says Emoji font", "emoji_damaged",
        "Emoji font: The emoji font's colour table (COLR) names glyph 65000", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.bold = new FontBuilder.Face(font(NOTO_ITALIC), null);
      r.emoji = badLayer;
      FontBuilder.build(r, null);
    });
    // Twemoji's glyphs (up to 512 units) in a font that says 16 units per em: at 125 % of Noto's 1000 units they are scaled 78 times, past 32,767
    Sfnt tb = Sfnt.parse(font(TWEMOJI));
    byte[] tbHead = tb.table("head").clone();
    Sfnt.w16(tbHead, 18, 16);
    tb.put("head", tbHead);
    byte[] tooBigEmoji = tb.toBytes();
    throwsFont("emoji too big for the text font at this size: emoji_too_big, says Emoji font", "emoji_too_big", "Emoji font: ", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.emoji = tooBigEmoji;
      r.emojiScale = 1.25;
      FontBuilder.build(r, null);
    });

    // 65,500 glyphs and Twemoji's 147 pass 65,535
    Sfnt many = Sfnt.parse(font(NOTO));
    byte[][] gl = Arrays.copyOf(Glyf.readAll(many), 65500);
    for (int i = 0; i < gl.length; i++) if (gl[i] == null) gl[i] = new byte[0];
    Glyf.Metrics m = Glyf.Metrics.read(many), mm = new Glyf.Metrics(gl.length);
    System.arraycopy(m.advance, 0, mm.advance, 0, m.advance.length);
    System.arraycopy(m.lsb, 0, mm.lsb, 0, m.lsb.length);
    Glyf.write(many, gl);
    mm.write(many, gl);
    byte[] manyBytes = many.toBytes();
    throwsFont("65,500 glyphs and an emoji font of 147: too_many_glyphs, says Regular font (both fonts' doing, the face it was made for)", "too_many_glyphs",
        "Regular font: The text font has 65,500 glyphs and the emoji font 147", () -> {
      FontBuilder.Request r = simple(manyBytes, "X");
      r.emoji = font(TWEMOJI);
      FontBuilder.build(r, null);
    });
    check("65,500 glyphs without emoji: built", FontBuilder.build(simple(manyBytes, "X"), null).regular != null);
    // 65,387 + 147 leave one glyph id for the 3 U+FE0F copies Twemoji needs: built, the other two characters warned about
    Sfnt near = Sfnt.parse(font(NOTO));
    byte[][] ng = Arrays.copyOf(Glyf.readAll(near), 65387);
    for (int i = 0; i < ng.length; i++) if (ng[i] == null) ng[i] = new byte[0];
    Glyf.Metrics nm = new Glyf.Metrics(ng.length);
    System.arraycopy(m.advance, 0, nm.advance, 0, m.advance.length);
    System.arraycopy(m.lsb, 0, nm.lsb, 0, m.lsb.length);
    Glyf.write(near, ng);
    nm.write(near, ng);
    FontBuilder.Request nr = simple(near.toBytes(), "X");
    nr.emoji = font(TWEMOJI);
    String nearFails;
    FontBuilder.Result built = null;
    try { built = FontBuilder.build(nr, null); nearFails = null; } catch (Sfnt.FontException x) { nearFails = x.code + ": " + x.getMessage() + " cause " + x.getCause(); }
    check("65,387 glyphs and Twemoji with 3 U+FE0F copies, room for 1: built with " + EmojiMerge.W_FE0F_SEQUENCES,
        built != null && Sfnt.parse(built.regular).numGlyphs() == 65535 && built.warnings.contains(EmojiMerge.W_FE0F_SEQUENCES),
        nearFails != null ? nearFails : built.warnings.toString());

    // An engine's RuntimeException is still a FontException, with the cause kept
    final RuntimeException boom = new IllegalStateException("boom");
    for (final int at : new int[] { 1, 3, 12 }) {
      Sfnt.FontException fe = throwsFont("a RuntimeException at progress call " + at + ": bad_font", "bad_font", null, () -> {
        FontBuilder.Request r = simple(font(INTER), "X");
        r.emoji = font(TWEMOJI);
        FontBuilder.build(r, new FontProgress() {
          int n;
          @Override public void step(String stage, int done, int total) { if (++n == at) throw boom; }
          @Override public boolean cancelled() { return false; }
        });
      });
      check("  ... its cause is that exception", fe != null && fe.getCause() == boom, fe == null ? null : String.valueOf(fe.getCause()));
    }
  }

  // ---------- bitmap glyphs ----------

  /**
   * Bitmap glyph tables leave every face, whatever made it, so only the outlines are drawn as FontInspect's W_BITMAP says: FreeType takes
   * a font with CBLC for a bitmap-only one, and its letters without a bitmap would not show.
   */
  static void bitmaps() throws Exception {
    String[][] sources = { { NOTO, "a static" }, { INTER, "a variable" }, { SS3, "a PostScript" } };
    for (String[] src : sources) {
      Sfnt f = Sfnt.parse(font(src[0]));
      for (String t : FontBuilder.BITMAPS_DROPPED) f.put(t, new byte[16]);
      Sfnt out = Sfnt.parse(FontBuilder.build(simple(f.toBytes(), "X"), null).regular);
      List<String> kept = new ArrayList<String>();
      for (String t : FontBuilder.BITMAPS_DROPPED) if (out.has(t)) kept.add(t);
      check(src[1] + " Regular with " + String.join(", ", FontBuilder.BITMAPS_DROPPED) + ": none of them in the package font", kept.isEmpty(), kept.toString());
    }
    // with emoji: the plain bitmaps go too (a text font with colour bitmaps is refused by the merge: text_has_color)
    Sfnt e = Sfnt.parse(font(NOTO));
    e.put("EBDT", new byte[16]);
    e.put("EBLC", new byte[16]);
    FontBuilder.Request r = simple(e.toBytes(), "X");
    r.emoji = font(TWEMOJI);
    Sfnt out = Sfnt.parse(FontBuilder.build(r, null).regular);
    check("a Regular with EBDT and EBLC, with emoji: the emoji in, the bitmaps out", out.has("COLR") && !out.has("EBDT") && !out.has("EBLC"), out.tags().toString());
    check("CBDT, CBLC, sbix, EBDT, EBLC and EBSC are all left out", Arrays.asList(FontBuilder.BITMAPS_DROPPED).containsAll(Arrays.asList("CBDT", "CBLC", "sbix", "EBDT", "EBLC", "EBSC")));
  }

  // ---------- damaged glyphs ----------

  /** NOTO with glyph {@code gid} replaced by a composite of one component naming {@code target}. */
  static byte[] withComposite(int gid, int target) throws Exception {
    Sfnt f = Sfnt.parse(font(NOTO));
    byte[][] g = Glyf.readAll(f);
    Sfnt.Writer w = new Sfnt.Writer(20);
    w.s16(-1).s16(0).s16(0).s16(100).s16(100);
    w.u16(Glyf.ARGS_ARE_XY | Glyf.ARG_WORDS).u16(target).s16(0).s16(0);
    g[gid] = w.toByteArray();
    // Written by hand: Glyf.write would refuse the loop itself
    Sfnt.Writer gw = new Sfnt.Writer(), lw = new Sfnt.Writer();
    for (byte[] raw : g) { lw.u32(gw.size()); gw.bytes(raw).align(4); }
    lw.u32(gw.size());
    f.put("glyf", gw.toByteArray());
    f.put("loca", lw.toByteArray());
    byte[] head = f.table("head").clone();
    Sfnt.w16(head, 50, 1);
    f.put("head", head);
    return f.toBytes();
  }

  static void damagedGlyphs() throws Exception {
    byte[] self = withComposite(5, 5), loop = withComposite(5, 6), far = withComposite(5, 60000);
    byte[] loop2 = loop;
    {
      Sfnt f = Sfnt.parse(loop);
      byte[][] g = Glyf.readAll(f);
      Sfnt.Writer w = new Sfnt.Writer(20);
      w.s16(-1).s16(0).s16(0).s16(100).s16(100).u16(Glyf.ARGS_ARE_XY | Glyf.ARG_WORDS).u16(5).s16(0).s16(0);
      g[6] = w.toByteArray();
      Sfnt.Writer gw = new Sfnt.Writer(), lw = new Sfnt.Writer();
      for (byte[] raw : g) { lw.u32(gw.size()); gw.bytes(raw).align(4); }
      lw.u32(gw.size());
      f.put("glyf", gw.toByteArray());
      f.put("loca", lw.toByteArray());
      loop2 = f.toBytes();
    }
    final byte[] l2 = loop2;
    throwsFont("a composite glyph naming itself: refused, says Regular font", "bad_font", "Regular font: A composite glyph of this font nests too deep",
        () -> FontBuilder.build(simple(self, "X"), null));
    throwsFont("two composites naming each other: refused", "bad_font", "Regular font: A composite glyph of this font nests too deep",
        () -> FontBuilder.build(simple(l2, "X"), null));
    throwsFont("a component naming glyph 60,000: refused", "bad_font", "Regular font: A composite glyph names glyph 60000",
        () -> FontBuilder.build(simple(far, "X"), null));
    throwsFont("a composite naming itself, with emoji: refused", null, "Regular font: ", () -> {
      FontBuilder.Request r = simple(self, "X");
      r.emoji = font(TWEMOJI);
      FontBuilder.build(r, null);
    });
    throwsFont("a composite naming itself in the Bold: says Bold font", "bad_font", "Bold font: ", () -> {
      FontBuilder.Request r = simple(font(NOTO), "X");
      r.bold = new FontBuilder.Face(self, null);
      FontBuilder.build(r, null);
    });
    // A tree of composites, each naming the next level 64 times: legal nesting, but 64^16 glyphs to draw
    final byte[] fan = fan(16, 64);
    long t = System.nanoTime();
    throwsFont("16 levels of 64 components each: too_complex", "too_complex", "Regular font: ", () -> FontBuilder.build(simple(fan, "X"), null));
    long ms = (System.nanoTime() - t) / 1000000L;
    check("  ... in under 2 s (" + ms + " ms)", ms < 2000);
    t = System.nanoTime();
    throwsFont("the same with emoji (every composite is written again): refused", "too_complex", "Regular font: ", () -> {
      FontBuilder.Request r = simple(fan, "X");
      r.emoji = font(TWEMOJI);
      FontBuilder.build(r, null);
    });
    ms = (System.nanoTime() - t) / 1000000L;
    check("  ... in under 10 s (" + ms + " ms)", ms < 10000);
    check("3 levels of 8 components each (512 glyphs to draw) build", FontBuilder.build(simple(fan(3, 8), "X"), null).regular != null);
    // FontBuilder.check on its own: a deep but legal chain, then one past MAX_DEPTH
    check("check: a chain of " + Glyf.MAX_DEPTH + " composites is fine", checks(chain(Glyf.MAX_DEPTH)));
    check("check: a chain of " + (Glyf.MAX_DEPTH + 2) + " composites is refused", !checks(chain(Glyf.MAX_DEPTH + 2)));
  }

  /** NOTO whose glyphs 10 .. 10+levels-1 are each {@code fanout} components naming the next; the last level names an empty glyph. */
  static byte[] fan(int levels, int fanout) throws Exception {
    Sfnt f = Sfnt.parse(font(NOTO));
    byte[][] g = Glyf.readAll(f);
    for (int i = 0; i < levels; i++) {
      Sfnt.Writer w = new Sfnt.Writer(20 + 8 * fanout);
      w.s16(-1).s16(0).s16(0).s16(100).s16(100);
      for (int k = 0; k < fanout; k++)
        w.u16(Glyf.ARGS_ARE_XY | Glyf.ARG_WORDS | (k < fanout - 1 ? Glyf.MORE_COMPONENTS : 0)).u16(i == levels - 1 ? 3 : 11 + i).s16(k).s16(0);
      g[10 + i] = w.toByteArray();
    }
    g[3] = new byte[0];
    Sfnt.Writer gw = new Sfnt.Writer(), lw = new Sfnt.Writer();
    for (byte[] raw : g) { lw.u32(gw.size()); gw.bytes(raw).align(4); }
    lw.u32(gw.size());
    f.put("glyf", gw.toByteArray());
    f.put("loca", lw.toByteArray());
    byte[] head = f.table("head").clone();
    Sfnt.w16(head, 50, 1);
    f.put("head", head);
    return f.toBytes();
  }

  /** NOTO whose glyphs 10 .. 10+depth-1 each name the next; the last one names glyph 36. */
  static byte[] chain(int depth) throws Exception {
    Sfnt f = Sfnt.parse(font(NOTO));
    byte[][] g = Glyf.readAll(f);
    for (int i = 0; i < depth; i++) {
      Sfnt.Writer w = new Sfnt.Writer(20);
      w.s16(-1).s16(0).s16(0).s16(100).s16(100).u16(Glyf.ARGS_ARE_XY | Glyf.ARG_WORDS).u16(i == depth - 1 ? 36 : 11 + i).s16(0).s16(0);
      g[10 + i] = w.toByteArray();
    }
    Sfnt.Writer gw = new Sfnt.Writer(), lw = new Sfnt.Writer();
    for (byte[] raw : g) { lw.u32(gw.size()); gw.bytes(raw).align(4); }
    lw.u32(gw.size());
    f.put("glyf", gw.toByteArray());
    f.put("loca", lw.toByteArray());
    byte[] head = f.table("head").clone();
    Sfnt.w16(head, 50, 1);
    f.put("head", head);
    return f.toBytes();
  }

  static boolean checks(byte[] font) {
    try {
      FontBuilder.check(font, FontProgress.NONE);
      return true;
    } catch (Sfnt.FontException e) {
      return false;
    }
  }

  // ---------- progress and cancel ----------

  static void progress() throws Exception {
    Set<String> stages = new HashSet<String>(Arrays.asList(FontBuilder.READ, FontBuilder.INSTANCE, FontBuilder.CONVERT, FontBuilder.EMOJI));
    Map<String, Integer> files = new HashMap<String, Integer>();
    files.put("var", 1); files.put("cff", 3); files.put("cff2", 2); files.put("static", 2); files.put("italic", 1); files.put("same", 1);
    for (Case c : CASES) {
      List<Object[]> steps = built(c).progress.steps;
      Set<String> unknown = new HashSet<String>();
      Map<String, long[]> last = new HashMap<String, long[]>();       // stage -> {done, total}
      String back = null;
      for (Object[] s : steps) {
        String st = (String) s[0];
        long d = (Integer) s[1], t = (Integer) s[2];
        if (!stages.contains(st)) unknown.add(st);
        if (d < 0 || d > t || t < 0) back = st + " " + d + "/" + t;
        long[] p = last.get(st);
        if (p != null && t > 0 && p[1] > 0 && d * p[1] < p[0] * t && back == null) back = st + " " + p[0] + "/" + p[1] + " then " + d + "/" + t;
        if (t > 0) last.put(st, new long[] { d, t });
      }
      check(c.name + ": only the page's stage names", unknown.isEmpty(), unknown.toString());
      check(c.name + ": no stage's share ever goes back (" + steps.size() + " steps)", back == null, back);
      Object[] first = steps.get(0);
      check(c.name + ": read 0 of " + files.get(c.name) + " comes first", FontBuilder.READ.equals(first[0]) && (Integer) first[1] == 0
          && files.get(c.name).equals(first[2]), Arrays.toString(first));
      boolean ends = true;
      for (Map.Entry<String, long[]> e : last.entrySet()) ends &= e.getValue()[0] == e.getValue()[1];
      check(c.name + ": every stage ends done (" + last.keySet() + ")", ends);
      Set<String> want = new HashSet<String>();
      want.add(FontBuilder.READ);
      if (c.variable()) want.add(FontBuilder.INSTANCE);
      if (c.regular.equals(SS3)) want.add(FontBuilder.CONVERT);
      if (c.emoji != null) want.add(FontBuilder.EMOJI);
      check(c.name + ": reports " + want, last.keySet().equals(want), last.keySet().toString());
      check(c.name + ": asks about Cancel along the way (" + built(c).progress.asked + " times)", built(c).progress.asked >= 2);
    }
  }

  /** Cancel from the n-th question on. */
  static final class CancelAt implements FontProgress {
    final int at;
    int asked;
    CancelAt(int at) { this.at = at; }
    @Override public void step(String stage, int done, int total) {}
    @Override public boolean cancelled() { return asked++ >= at; }
  }

  static void cancel() throws Exception {
    for (Case c : new Case[] { CASES.get(1), CASES.get(2), CASES.get(0) }) {
      int total = built(c).progress.asked;
      List<Integer> at = new ArrayList<Integer>();
      for (int k = 0; k < total; k = k < 4 ? k + 1 : k * 2) at.add(k);
      at.add(total - 1);
      List<String> bad = new ArrayList<String>();
      for (int k : at) {
        try {
          FontBuilder.build(request(c), new CancelAt(k));
          bad.add(k + ": built");
        } catch (Sfnt.FontException e) {
          if (!"cancelled".equals(e.code) || !"Cancelled.".equals(e.getMessage())) bad.add(k + ": " + e.code + " " + e.getMessage());
        }
      }
      check(c.name + ": Cancel at question " + at + " of " + total + " ends in \"cancelled\", with no face named", bad.isEmpty(), bad.toString());
    }
  }

  // ---------- Steps ----------

  static void steps() throws Exception {
    Recorder r = new Recorder();
    FontBuilder.Steps s = new FontBuilder.Steps(r, FontBuilder.INSTANCE, 1, 2);
    s.step(FontBuilder.INSTANCE, 10, 100);
    s.step(FontBuilder.CONVERT, 5, 10);
    s.step("layout", 1, 2);
    s.step(Cmap.STAGE, 1, 2);
    s.step(FontBuilder.EMOJI, 1, 2);
    s.step(FontBuilder.INSTANCE, 0, 0);
    s.step(FontBuilder.INSTANCE, 200, 100);
    s.step(FontBuilder.INSTANCE, -5, 100);
    List<String> got = new ArrayList<String>();
    for (Object[] x : r.steps) got.add(x[0] + " " + x[1] + "/" + x[2]);
    check("Steps: the second of two faces on one scale, convert inside instance counted, other stages left out, done clamped", got.equals(Arrays.asList(
        "instance 110/200", "instance 15/20", "instance 0/0", "instance 200/200", "instance 100/200")), got.toString());
    r.steps.clear();
    new FontBuilder.Steps(r, FontBuilder.EMOJI, 1, 2).step(FontBuilder.EMOJI, Integer.MAX_VALUE - 1, Integer.MAX_VALUE);
    Object[] x = r.steps.get(0);
    check("Steps: a total past int is scaled to 1000", (Integer) x[2] == 1000 && (Integer) x[1] == 999, Arrays.toString(x));
    r.steps.clear();
    new FontBuilder.Steps(r, FontBuilder.CONVERT, 0, 1).step(FontBuilder.INSTANCE, 1, 2);
    check("Steps: instance steps inside convert are left out", r.steps.isEmpty());
    final boolean[] asked = { false };
    FontProgress yes = new FontProgress() {
      @Override public void step(String stage, int done, int total) {}
      @Override public boolean cancelled() { asked[0] = true; return true; }
    };
    check("Steps: Cancel goes straight through", new FontBuilder.Steps(yes, FontBuilder.EMOJI, 0, 1).cancelled() && asked[0]);
  }

  // ---------- fuzz ----------

  /** The cases the fuzz starts from (small ones, so a few hundred builds take seconds). */
  static final String[][] FUZZ_BASES = { { NOTO, null, TWEMOJI }, { SS3, null, NOTO_EMOJI }, { INTER, INTER, null }, { SS3_VF, null, null },
      { NOTO_ITALIC, NOTO, TWEMOJI } };

  static byte[] mutate(byte[] src, Random rnd, int kind) throws Exception {
    byte[] b;
    switch (kind) {
      case 0:
        return Arrays.copyOf(src, rnd.nextInt(src.length));
      case 1:
        b = src.clone();
        for (int i = 1 + rnd.nextInt(24); i > 0; i--) b[rnd.nextInt(b.length)] ^= (byte) (1 << rnd.nextInt(8));
        return b;
      case 2:
        b = new byte[rnd.nextInt(3000)];
        rnd.nextBytes(b);
        if (b.length >= 12 && rnd.nextBoolean()) {
          // A believable directory over random tables
          int num = Math.min(Sfnt.u16(src, 4), (b.length - 12) / 16);
          System.arraycopy(src, 0, b, 0, 12);
          Sfnt.w16(b, 4, num);
          for (int i = 0; i < num; i++) {
            System.arraycopy(src, 12 + 16 * i, b, 12 + 16 * i, 8);
            int off = 12 + 16 * num + rnd.nextInt(Math.max(1, b.length - 12 - 16 * num));
            Sfnt.w32(b, 12 + 16 * i + 8, off);
            Sfnt.w32(b, 12 + 16 * i + 12, rnd.nextInt(b.length - off + 1));
          }
        }
        return b;
      case 3: {
        // A count or offset near the start of one table made huge
        b = src.clone();
        int num = Sfnt.u16(b, 4), rec = 12 + 16 * rnd.nextInt(num);
        long off = Sfnt.u32(b, rec + 8), len = Sfnt.u32(b, rec + 12);
        if (len < 2) return b;
        int at = (int) off + 2 * rnd.nextInt((int) Math.min(len, 80) / 2);
        int v = rnd.nextInt(4);
        Sfnt.w16(b, at, v == 0 ? 0xFFFF : v == 1 ? 0x7FFF : v == 2 ? 0x8000 : 0);
        return b;
      }
      default: {
        // A table directory entry changed
        b = src.clone();
        int num = Sfnt.u16(b, 4), rec = 12 + 16 * rnd.nextInt(num);
        int field = rec + 8 + 4 * rnd.nextInt(2);
        Sfnt.w32(b, field, rnd.nextBoolean() ? rnd.nextInt() : (int) (Sfnt.u32(b, field) + rnd.nextInt(64) - 32));
        return b;
      }
    }
  }

  static void fuzz(long seed, int count, int maxPrinted) throws Exception {
    Random rnd = new Random(seed);
    int built = 0, refused = 0, bad = 0;
    long worst = 0, start = System.nanoTime();
    Map<String, Integer> codes = new TreeMap<String, Integer>();
    for (int i = 0; i < count; i++) {
      String[] base = FUZZ_BASES[rnd.nextInt(FUZZ_BASES.length)];
      FontBuilder.Request r = new FontBuilder.Request();
      r.name = "Fuzz " + i;
      r.time = TIME;
      byte[] reg = font(base[0]), bold = base[1] == null ? null : base[1].equals(base[0]) ? reg : font(base[1]), emoji = base[2] == null ? null : font(base[2]);
      int which = rnd.nextInt(10), kind = rnd.nextInt(5);
      String what;
      if (which < 6 || (bold == null && emoji == null)) { reg = mutate(reg, rnd, kind); what = "regular"; }
      else if (which < 8 && bold != null || emoji == null) { bold = mutate(bold, rnd, kind); what = "bold"; }
      else { emoji = mutate(emoji, rnd, kind); what = "emoji"; }
      r.regular = new FontBuilder.Face(reg, base[0].equals(INTER) ? loc("wght", 300 + rnd.nextInt(600)) : null);
      if (bold != null) r.bold = new FontBuilder.Face(bold, null);
      r.emoji = emoji;
      String label = "fuzz " + i + " (" + base[0] + ", " + what + ", kind " + kind + ")";
      long t = System.nanoTime();
      try {
        FontBuilder.Result res = FontBuilder.build(r, FontProgress.NONE);
        FontBuilder.check(res.regular, FontProgress.NONE);
        if (res.bold != null) FontBuilder.check(res.bold, FontProgress.NONE);
        built++;
      } catch (Sfnt.FontException e) {
        refused++;
        codes.merge(e.code, 1, Integer::sum);
        Throwable cause = e.getCause();
        if ("no_memory".equals(e.code) || cause instanceof RuntimeException || e.getMessage() == null || e.getMessage().isEmpty()) {
          bad++;
          if (bad <= maxPrinted || maxPrinted == 0) check(label + ": refused as a FontException without a crash inside", false, e.code + ": " + e.getMessage()
              + (cause == null ? "" : " <- " + cause + (cause.getStackTrace().length > 0 ? " at " + cause.getStackTrace()[0] : "")));
        }
      } catch (Throwable e) {
        bad++;
        if (bad <= maxPrinted || maxPrinted == 0) check(label + ": no other exception", false, e + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : ""));
      }
      worst = Math.max(worst, (System.nanoTime() - t) / 1000000L);
    }
    long ms = (System.nanoTime() - start) / 1000000L;
    check("fuzz (seed " + Long.toHexString(seed) + "): " + count + " builds in " + ms + " ms, " + built + " built, " + refused + " refused " + codes
        + ", each a FontException without a crash inside", bad == 0, bad + " bad");
    check("fuzz: no build took over 5 s (the slowest " + worst + " ms)", worst < 5000);
  }

  // ---------- --e2e: a whole package from full fonts ----------

  static FontBuilder.Face face(String spec) throws IOException {
    if (spec.equals("-")) return null;
    String path = spec;
    Map<String, Double> loc = null;
    int at = spec.lastIndexOf('@');
    if (at > 0) {
      path = spec.substring(0, at);
      loc = new LinkedHashMap<String, Double>();
      for (String kv : spec.substring(at + 1).split(",")) {
        String[] p = kv.split("=");
        loc.put(p[0], Double.parseDouble(p[1]));
      }
    }
    return new FontBuilder.Face(Files.readAllBytes(new File(path).toPath()), loc);
  }

  static String pathOf(String spec) { return spec.equals("-") ? "-" : new File(spec.lastIndexOf('@') > 0 ? spec.substring(0, spec.lastIndexOf('@')) : spec).getAbsolutePath(); }

  static void e2e(String[] a) throws Exception {
    if (a.length != 6) throw new IllegalArgumentException("--e2e OUT NAME REGULAR[@tag=v,...] BOLD[@tag=v,...]|- EMOJI|-");
    File out = new File(a[1]);
    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot make " + out);
    FontBuilder.Request r = new FontBuilder.Request();
    r.name = a[2];
    r.regular = face(a[3]);
    r.bold = face(a[4]);
    if (r.bold != null && pathOf(a[3]).equals(pathOf(a[4]))) r.bold.data = r.regular.data;
    if (!a[5].equals("-")) r.emoji = Files.readAllBytes(new File(a[5]).toPath());
    final StringBuilder log = new StringBuilder();
    final long t0 = System.nanoTime();
    FontProgress p = new FontProgress() {
      String last = "";
      @Override public void step(String stage, int done, int total) {
        if (!stage.equals(last)) log.append(String.format(Locale.ROOT, "  %6d ms  %s %d/%d%n", (System.nanoTime() - t0) / 1000000L, stage, done, total));
        last = stage;
      }
      @Override public boolean cancelled() { return false; }
    };
    FontBuilder.Result res = FontBuilder.build(r, p);
    long tBuild = (System.nanoTime() - t0) / 1000000L;
    FontPackage.Spec spec = new FontPackage.Spec(res.family, FontPackage.suffixFor(res.family, "e2e"), 1, res.regular, res.bold);
    File unsigned = new File(out, "unsigned.apk"), prepared = new File(out, "prepared.apk"), signed = new File(out, "font.apk");
    long t1 = System.nanoTime();
    FontPackage.writeUnsigned(unsigned, spec, p);
    long tPackage = (System.nanoTime() - t1) / 1000000L;
    KeyStore.PrivateKeyEntry key = throwawayKey(out);
    long t2 = System.nanoTime();
    ApkSigner.prepare(unsigned, prepared);
    ApkSigner.signV2(prepared, signed, key.getPrivateKey(), (X509Certificate) key.getCertificate());
    long tSign = (System.nanoTime() - t2) / 1000000L;
    String build = "regular=" + pathOf(a[3]) + "\nregularLoc=" + locSpec(res.regularLocation) + "\nbold=" + pathOf(a[4]) + "\nboldLoc=" + locSpec(res.boldLocation)
        + "\nemoji=" + pathOf(a[5]) + "\n";
    Files.write(new File(out, "build.txt").toPath(), build.getBytes(StandardCharsets.UTF_8));
    System.out.print(log);
    System.out.println("family " + res.family + ", package " + FontPackage.packageName(spec.suffix));
    System.out.println("locations " + res.regularLocation + " / " + res.boldLocation);
    System.out.println("warnings " + res.warnings);
    System.out.println("fonts " + res.regular.length + (res.bold == null ? "" : " + " + res.bold.length) + " bytes; package " + signed.length() + " bytes");
    System.out.println("build " + tBuild + " ms " + res.timings + ", package " + tPackage + " ms, sign " + tSign + " ms, total " + (tBuild + tPackage + tSign) + " ms");
  }

  static KeyStore.PrivateKeyEntry throwawayKey(File dir) throws Exception {
    File p12 = new File(dir, "throwaway.p12");
    if (p12.exists() && !p12.delete()) throw new IOException("cannot delete " + p12);
    File kt = new File(System.getProperty("java.home"), "bin/keytool");
    Process pr = new ProcessBuilder(kt.getPath(), "-genkeypair", "-storetype", "PKCS12", "-keystore", p12.getPath(), "-storepass", "password", "-keypass",
        "password", "-validity", "3650", "-alias", "rsa", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=fontbuilder e2e").inheritIO().start();
    if (pr.waitFor() != 0 || !p12.isFile()) throw new IOException("keytool failed");
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(p12)) { ks.load(in, "password".toCharArray()); }
    return (KeyStore.PrivateKeyEntry) ks.getEntry("rsa", new KeyStore.PasswordProtection("password".toCharArray()));
  }
}
