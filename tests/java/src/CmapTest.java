package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cmap (the character map: best subtable, variation sequences, the table writer) and EmojiData. Against every font under
 * tests/java/fixtures/fonts (this suite's folder and the other suites'): the map and sequences fontTools reads (cmap/expected.txt, keyed by
 * the font file's SHA-256), and each table built from them read back the same here; make_fixtures.py verify checked those tables with
 * fontTools and HarfBuzz and recorded their SHA-256, which this suite compares. Hand-built subtables cover every format and the rules around
 * them; format 4's encoding is checked against a brute-force optimum and its 64 KB overflow with 40,000 scattered characters; a seeded fuzz
 * runs read, readUvs and build on damaged tables.
 *
 * <p>{@code --dump DIR} writes the tables this suite builds, for {@code make_fixtures.py verify DIR} ({@code --dump DIR FONT...}: the tables
 * built from those fonts' maps instead, for {@code verify --no-record}); {@code --read FONT} prints what Cmap reads from a font, for
 * {@code make_fixtures.py check}; {@code --fuzz SEED COUNT} runs only the fuzz.
 */
public class CmapTest {
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
    File fonts = fontsDir();
    if (args.length >= 2 && args[0].equals("--dump")) { dump(fonts, new File(args[1]), java.util.Arrays.copyOfRange(args, 2, args.length)); return; }
    if (args.length == 2 && args[0].equals("--read")) { readMode(new File(args[1])); return; }
    if (args.length == 3 && args[0].equals("--fuzz")) {
      fuzz(fonts, Long.parseLong(args[1], 16), Integer.parseInt(args[2]));
      System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
      System.exit(fails == 0 ? 0 : 1);
    }
    Expected ex = null;
    if (fonts == null || !new File(fonts, "cmap/expected.txt").isFile()) check("the fixtures are found (tests/java/fixtures/fonts/cmap)", false);
    else ex = Expected.read(new File(fonts, "cmap/expected.txt"));
    final Expected e = ex;
    if (e != null) {
      section("fixture fonts", () -> fixtureFonts(fonts, e));
      section("emoji data", () -> emojiData(e));
      section("synthetic tables against verify", () -> synthetic(e));
    }
    section("hand-built subtables", CmapTest::handBuilt);
    section("subtable order and fallback", CmapTest::preference);
    section("variation sequences", CmapTest::uvs);
    section("format 4 encoding", CmapTest::format4);
    section("format 4 overflow", CmapTest::overflow);
    section("build input", CmapTest::buildInput);
    section("hostile counts", CmapTest::hostile);
    section("cancel and progress", () -> cancel(fonts));
    section("emoji rules", CmapTest::emojiRules);
    section("fuzz", () -> fuzz(fonts, 0x0C3A9L, 4000));
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
    if (p != null && new File(p, "fonts/cmap").isDirectory()) return new File(p, "fonts");
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts");
    return null;
  }

  /** expected.txt: see make_fixtures.py. */
  static final class Expected {
    String emojiVersion, emojiSha, variationVersion, variationSha;
    int emojiCount, variationCount;
    final Map<String, String[]> fonts = new HashMap<>();   // file SHA-256 -> the font line's words
    final Map<String, String> built = new HashMap<>();     // case -> SHA-256 of the table

    static Expected read(File f) throws IOException {
      Expected e = new Expected();
      for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
        if (l.isEmpty() || l.startsWith("#")) continue;
        String[] w = l.split(" ");
        if (w[0].equals("emoji")) { e.emojiVersion = w[1]; e.emojiCount = Integer.parseInt(w[2]); e.emojiSha = w[3]; }
        else if (w[0].equals("variation")) { e.variationVersion = w[1]; e.variationCount = Integer.parseInt(w[2]); e.variationSha = w[3]; }
        else if (w[0].equals("font")) e.fonts.put(w[2], w);
        else if (w[0].equals("built")) e.built.put(w[1], w[2]);
      }
      return e;
    }
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

  static String mapText(Map<Integer, Integer> m) {
    StringBuilder s = new StringBuilder();
    for (Map.Entry<Integer, Integer> e : m.entrySet()) s.append(Integer.toHexString(e.getKey()).toUpperCase()).append(' ').append(e.getValue()).append('\n');
    return s.toString();
  }

  /** The sequences as make_fixtures.py writes them: selector, character, "D" or the glyph, in order. */
  static String uvsText(Cmap.Uvs u) {
    StringBuilder s = new StringBuilder();
    for (int sel : u.selectors()) {
      TreeMap<Integer, String> line = new TreeMap<>();
      for (int[] r : u.defaultRanges(sel)) for (int c = r[0]; c <= r[1]; c++) line.put(c, "D");
      for (Map.Entry<Integer, Integer> e : u.glyphs(sel).entrySet()) line.put(e.getKey(), String.valueOf(e.getValue()));
      for (Map.Entry<Integer, String> e : line.entrySet())
        s.append(Integer.toHexString(sel).toUpperCase()).append(' ').append(Integer.toHexString(e.getKey()).toUpperCase()).append(' ').append(e.getValue()).append('\n');
    }
    return s.toString();
  }

  static String shaText(String s) throws Exception { return sha(s.getBytes(StandardCharsets.US_ASCII)); }

  /** A font of just maxp and cmap: all Cmap reads. */
  static Sfnt font(int numGlyphs, byte[] cmap) {
    Sfnt f = new Sfnt(Sfnt.TRUETYPE);
    byte[] maxp = new byte[6];
    Sfnt.w32(maxp, 0, 0x00005000);
    Sfnt.w16(maxp, 4, numGlyphs);
    f.put("maxp", maxp);
    if (cmap != null) f.put("cmap", cmap);
    return f;
  }

  /** Every table of a font file, without Sfnt.parse's checks (for --read on bitmap-only fonts). */
  static Sfnt lenient(byte[] data) throws Exception {
    try {
      return Sfnt.parse(data);
    } catch (Sfnt.FontException e) {
      Sfnt f = new Sfnt(Sfnt.TRUETYPE);
      int n = Sfnt.u16(data, 4);
      for (int i = 0; i < n; i++) {
        int r = 12 + 16 * i;
        long off = Sfnt.u32(data, r + 8), len = Sfnt.u32(data, r + 12);
        f.put(Sfnt.tagString(data, r), java.util.Arrays.copyOfRange(data, (int) off, (int) (off + len)));
      }
      return f;
    }
  }

  /** A fixture font as Sfnt reads it; a bitmap-only one (which Sfnt refuses with no_outlines) table by table, as its cmap is still read. */
  static Sfnt fixture(byte[] data) throws Exception {
    try {
      return Sfnt.parse(data);
    } catch (Sfnt.FontException e) {
      if (!"no_outlines".equals(e.code)) throw e;
      return lenient(data);
    }
  }

  static void readMode(File font) throws Exception {
    Sfnt f = lenient(Files.readAllBytes(font.toPath()));
    Cmap.Best b = Cmap.readBest(f, null);
    Cmap.Uvs u = Cmap.readUvs(f);
    System.out.println("best " + b.platform + " " + b.encoding + " " + b.format);
    System.out.println("map " + b.map.size() + " " + shaText(mapText(b.map)));
    System.out.println("uvs " + u.size() + " " + shaText(uvsText(u)));
  }

  // ---------- the fixture fonts ----------

  static void fixtureFonts(File fonts, Expected ex) throws Exception {
    int recorded = 0;
    for (File file : fontFiles(fonts)) {
      String name = rel(fonts, file);
      byte[] data = Files.readAllBytes(file.toPath());
      String[] w = ex.fonts.get(sha(data));
      Sfnt f;
      try {
        f = fixture(data);
      } catch (Sfnt.FontException e) {
        check(name + ": parses (or is a font Sfnt refuses on purpose)", w == null, e.getMessage());
        continue;
      }
      Cmap.Best b = Cmap.readBest(f, null);
      Cmap.Uvs u = Cmap.readUvs(f);
      if (w == null) {
        System.out.println("NOTE " + name + ": not in cmap/expected.txt (run make_fixtures.py expect); checked only that it reads and builds back");
      } else {
        recorded++;
        check(name + ": the subtable fontTools reads (" + w[3] + ", " + w[4] + ") format " + w[5],
            b.platform == Integer.parseInt(w[3]) && b.encoding == Integer.parseInt(w[4]) && b.format == Integer.parseInt(w[5]),
            b.platform + " " + b.encoding + " " + b.format);
        check(name + ": " + w[6] + " characters, each with fontTools' glyph", b.map.size() == Integer.parseInt(w[6]) && shaText(mapText(b.map)).equals(w[7]),
            b.map.size() + " characters");
        check(name + ": " + w[8] + " variation sequences as fontTools reads them", u.size() == Long.parseLong(w[8]) && shaText(uvsText(u)).equals(w[9]),
            u.size() + " sequences");
      }
      List<String> warn = new ArrayList<>();
      byte[] t = Cmap.build(b.map, u, warn, null);
      Sfnt back = font(f.numGlyphs(), t);
      check(name + ": the table built from it reads back the same", Cmap.read(back).equals(b.map) && Cmap.readUvs(back).equals(u) && warn.isEmpty());
      String want = ex.built.get(caseName(name));
      if (w != null) check(name + ": the built table is the one verify checked with fontTools and HarfBuzz", sha(t).equals(want),
          want == null ? "not recorded: run --dump and make_fixtures.py verify" : "hash differs: run --dump and verify again");
    }
    check("the fixture fonts of this suite are recorded", recorded >= 5, recorded + " recorded");
  }

  static String caseName(String rel) { return "font-" + rel.replace('/', '-').replaceAll("\\.[a-z]+$", ""); }

  // ---------- synthetic tables (also dumped for verify) ----------

  static final class Case {
    final String name;
    final int glyphs;
    final TreeMap<Integer, Integer> map;
    final Cmap.Uvs uvs;

    Case(String name, int glyphs, TreeMap<Integer, Integer> map, Cmap.Uvs uvs) { this.name = name; this.glyphs = glyphs; this.map = map; this.uvs = uvs; }
  }

  /** 40,000 distinct BMP characters (no surrogates) with random glyphs: format 4 cannot hold them. */
  static TreeMap<Integer, Integer> scattered(long seed, int count) {
    Random r = new Random(seed);
    TreeMap<Integer, Integer> m = new TreeMap<>();
    while (m.size() < count) {
      int c = 0x20 + r.nextInt(0xFFFE - 0x20);
      if (c >= 0xD800 && c <= 0xDFFF) continue;
      m.put(c, 1 + r.nextInt(65534));
    }
    return m;
  }

  static List<Case> cases() {
    List<Case> out = new ArrayList<>();
    out.add(new Case("syn-scattered40k", 65535, scattered(40000L, 40000), null));
    TreeMap<Integer, Integer> dense = new TreeMap<>();
    for (int c = 0; c < 0xFFFF; c++) dense.put(c, (int) ((c * 7L) % 65534) + 1);
    out.add(new Case("syn-densebmp", 65535, dense, null));
    TreeMap<Integer, Integer> planes = new TreeMap<>();
    Random r = new Random(7);
    for (int c = 0x20; c < 0x7F; c++) planes.put(c, c - 0x1D);                        // one delta run
    for (int c = 0x100; c < 0x180; c += 2) planes.put(c, 200 + r.nextInt(100));        // gaps of one: a glyph array is smaller
    for (int c = 0x400; c < 0x460; c++) planes.put(c, 300 + (c & 15));                  // runs of 16 with the same delta
    planes.put(0xFFFD, 7);
    planes.put(0xFFFF, 8);
    for (int c = 0x1F600; c < 0x1F650; c++) planes.put(c, 500 + c - 0x1F600);
    for (int c = 0x20000; c < 0x20100; c++) planes.put(c, 700 + r.nextInt(300));
    planes.put(0xE0100, 9);
    planes.put(0x10FFFF, 10);
    Cmap.Uvs u = new Cmap.Uvs();
    u.addDefaultRange(0xFE00, 0x4E00, 0x4FFF);                                          // 512 characters: two 256-character ranges
    u.addDefaultRange(0xFE0F, 0x1F600, 0x1F64F);
    u.put(0xFE0F, 0x2764, 11);
    u.put(0xE0100, 0x20000, 12);
    u.put(0xE0101, 0x20000, 13);
    u.addDefault(EmojiData.TEXT_SELECTOR, 0x2764);
    out.add(new Case("syn-planes", 1000, planes, u));
    out.add(new Case("syn-empty", 10, new TreeMap<Integer, Integer>(), null));
    TreeMap<Integer, Integer> one = new TreeMap<>();
    one.put(0x41, 1);
    Cmap.Uvs onlyUvs = new Cmap.Uvs();
    onlyUvs.put(0xFE0F, 0x41, 2);
    out.add(new Case("syn-one", 3, one, onlyUvs));
    return out;
  }

  static void synthetic(Expected ex) throws Exception {
    for (Case c : cases()) {
      List<String> warn = new ArrayList<>();
      byte[] t = Cmap.build(c.map, c.uvs, warn, null);
      String want = ex.built.get(c.name);
      check(c.name + ": the table is the one verify checked", sha(t).equals(want), want == null ? "not recorded" : "hash differs: run --dump and verify again");
      Sfnt back = font(c.glyphs, t);
      check(c.name + ": reads back the same", Cmap.read(back).equals(c.map) && Cmap.readUvs(back).equals(c.uvs == null ? new Cmap.Uvs() : c.uvs));
    }
  }

  /** The suite's tables, or (with font paths, dev time) the tables built from those fonts' maps. */
  static void dump(File fonts, File dir, String[] extra) throws Exception {
    dir.mkdirs();
    List<Case> all = new ArrayList<>();
    if (extra.length == 0) {
      all.addAll(cases());
      for (File file : fontFiles(fonts)) {
        Sfnt f;
        try { f = fixture(Files.readAllBytes(file.toPath())); } catch (Sfnt.FontException e) { continue; }
        all.add(new Case(caseName(rel(fonts, file)), f.numGlyphs(), Cmap.read(f), Cmap.readUvs(f)));
      }
    }
    for (String path : extra) {
      Sfnt f = lenient(Files.readAllBytes(new File(path).toPath()));
      all.add(new Case("dev-" + new File(path).getName().replaceAll("[^A-Za-z0-9._-]", "_"), f.numGlyphs(), Cmap.read(f), Cmap.readUvs(f)));
    }
    for (Case c : all) {
      byte[] t = Cmap.build(c.map, c.uvs, null, null);
      Files.write(new File(dir, c.name + ".cmap").toPath(), t);
      StringBuilder s = new StringBuilder("glyphs " + c.glyphs + "\n");
      for (Map.Entry<Integer, Integer> e : c.map.entrySet()) s.append("m ").append(Integer.toHexString(e.getKey())).append(' ').append(e.getValue()).append('\n');
      if (c.uvs != null) {
        for (int sel : c.uvs.selectors()) {
          for (int[] r : c.uvs.defaultRanges(sel)) for (int k = r[0]; k <= r[1]; k++) s.append("d ").append(Integer.toHexString(sel)).append(' ').append(Integer.toHexString(k)).append('\n');
          for (Map.Entry<Integer, Integer> e : c.uvs.glyphs(sel).entrySet())
            s.append("u ").append(Integer.toHexString(sel)).append(' ').append(Integer.toHexString(e.getKey())).append(' ').append(e.getValue()).append('\n');
        }
      }
      Files.write(new File(dir, c.name + ".txt").toPath(), s.toString().getBytes(StandardCharsets.US_ASCII));
      System.out.println("wrote " + c.name + " (" + t.length + " bytes)");
    }
  }

  // ---------- hand-built subtables ----------

  /** A big-endian byte builder for hand-made tables. */
  static final class B {
    final ByteArrayOutputStream o = new ByteArrayOutputStream();
    B u8(int v) { o.write(v); return this; }
    B u16(int v) { o.write(v >> 8); o.write(v); return this; }
    B u24(int v) { o.write(v >> 16); return u16(v); }
    B u32(long v) { u16((int) (v >>> 16)); return u16((int) v); }
    B bytes(byte[] b) { o.write(b, 0, b.length); return this; }
    int size() { return o.size(); }
    byte[] get() { return o.toByteArray(); }
  }

  /** A cmap table: recs = {platform, encoding, index into subs}. */
  static byte[] cmap(int[][] recs, byte[]... subs) {
    B b = new B().u16(0).u16(recs.length);
    long[] at = new long[subs.length];
    long pos = 4 + 8L * recs.length;
    for (int i = 0; i < subs.length; i++) { at[i] = pos; pos += subs[i].length; }
    for (int[] r : recs) b.u16(r[0]).u16(r[1]).u32(r[2] < 0 ? 0xFFFFFFF0L : at[r[2]]);
    for (byte[] s : subs) b.bytes(s);
    return b.get();
  }

  static byte[] f0(int[] codeGlyph) {
    byte[] ids = new byte[256];
    for (int i = 0; i < codeGlyph.length; i += 2) ids[codeGlyph[i]] = (byte) codeGlyph[i + 1];
    return new B().u16(0).u16(262).u16(0).bytes(ids).get();
  }

  /** Format 4 from raw segments {first, last, delta, rangeOffset} (the closing segment is not added) and a glyph id array. */
  static byte[] f4raw(int[][] segs, int[] ids, int lengthOverride) {
    int seg = segs.length;
    int length = 16 + 8 * seg + 2 * ids.length;
    B b = new B().u16(4).u16(lengthOverride >= 0 ? lengthOverride : length).u16(0).u16(2 * seg).u16(0).u16(0).u16(0);
    for (int[] s : segs) b.u16(s[1]);
    b.u16(0);
    for (int[] s : segs) b.u16(s[0]);
    for (int[] s : segs) b.u16(s[2]);
    for (int[] s : segs) b.u16(s[3]);
    for (int g : ids) b.u16(g);
    return b.get();
  }

  static byte[] f6(int first, int... ids) {
    B b = new B().u16(6).u16(10 + 2 * ids.length).u16(0).u16(first).u16(ids.length);
    for (int g : ids) b.u16(g);
    return b.get();
  }

  static byte[] f10(long first, int... ids) {
    B b = new B().u16(10).u16(0).u32(20 + 2 * ids.length).u32(0).u32(first).u32(ids.length);
    for (int g : ids) b.u16(g);
    return b.get();
  }

  /** Format 12 or 13 from groups {first, last, glyph}. */
  static byte[] f12(int format, long[]... groups) {
    B b = new B().u16(format).u16(0).u32(16 + 12 * groups.length).u32(0).u32(groups.length);
    for (long[] g : groups) b.u32(g[0]).u32(g[1]).u32(g[2]);
    return b.get();
  }

  static TreeMap<Integer, Integer> map(int... cpGlyph) {
    TreeMap<Integer, Integer> m = new TreeMap<>();
    for (int i = 0; i < cpGlyph.length; i += 2) m.put(cpGlyph[i], cpGlyph[i + 1]);
    return m;
  }

  static void handBuilt() throws Exception {
    // format 0, Mac Roman: codes above 0x7F become their Unicode characters
    Cmap.Best b = Cmap.readBest(font(10, cmap(new int[][] { { 1, 0, 0 } }, f0(new int[] { 0x41, 5, 0x8E, 6, 0xDB, 7, 0xF0, 8, 0x20, 0 }))), null);
    check("format 0 (Mac Roman): A, e acute, the euro sign, the Apple logo; glyph 0 left out", b.map.equals(map(0x41, 5, 0xE9, 6, 0x20AC, 7, 0xF8FF, 8)) && b.format == 0 && b.platform == 1, b.map.toString());
    check("format 0 on (3, 0) keeps its codes", Cmap.read(font(10, cmap(new int[][] { { 3, 0, 0 } }, f0(new int[] { 0x8E, 6 })))).equals(map(0x8E, 6)));
    // format 6, also Mac Roman
    check("format 6 (Mac): first code and a glyph id array", Cmap.read(font(10, cmap(new int[][] { { 1, 0, 0 } }, f6(0x80, 1, 0, 3)))).equals(map(0xC4, 1, 0xC7, 3)));
    check("format 6 (Unicode): ids past the glyph count left out", Cmap.read(font(3, cmap(new int[][] { { 3, 1, 0 } }, f6(0x41, 1, 2, 3, 4)))).equals(map(0x41, 1, 0x42, 2)));
    // format 4: delta segments, glyph array segments with their delta, out-of-array references, a reversed segment, U+FFFF
    int[][] segs = { { 0x20, 0x22, 0xFFE1 & 0xFFFF, 0 },              // 0x20 -> 1 (delta -31)
        { 0x30, 0x33, 2, 8 },                                         // glyph array from its first entry, + 2 on non-zero ids
        { 0x40, 0x41, 0, 14 },                                        // 0x40 is the array's last entry, 0x41 past it
        { 0x50, 0x4F, 5, 0 },                                         // last < first: nothing
        { 0xFFF0, 0xFFFF, 0x0010, 0 } };                              // U+FFF0 -> 0 (none), ..., U+FFFF never read
    byte[] t = f4raw(segs, new int[] { 10, 0, 12, 13, 40 }, -1);
    TreeMap<Integer, Integer> want = map(0x20, 1, 0x21, 2, 0x22, 3, 0x30, 12, 0x32, 14, 0x33, 15, 0x40, 40);
    for (int c = 0xFFF1; c <= 0xFFFE; c++) want.put(c, (c + 0x10) & 0xFFFF);
    check("format 4: deltas, glyph arrays (+ delta), unmapped references, a reversed segment, no U+FFFF", Cmap.read(font(100, cmap(new int[][] { { 3, 1, 0 } }, t))).equals(want), Cmap.read(font(100, cmap(new int[][] { { 3, 1, 0 } }, t))).toString());
    byte[] shortLen = f4raw(segs, new int[] { 10, 0, 12, 13, 40 }, 20);
    check("format 4 with a length field too small (the glyph ids run to the table's end)", Cmap.read(font(100, cmap(new int[][] { { 3, 1, 0 } }, shortLen))).equals(want));
    check("format 4 with no segments: an empty map", Cmap.readBest(font(5, cmap(new int[][] { { 3, 1, 0 } }, f4raw(new int[0][], new int[0], -1))), null).format == 4);
    // format 10 and 12 / 13
    check("format 10: plane 1 characters", Cmap.read(font(10, cmap(new int[][] { { 3, 10, 0 } }, f10(0x1F600, 3, 0, 5)))).equals(map(0x1F600, 3, 0x1F602, 5)));
    TreeMap<Integer, Integer> m12 = Cmap.read(font(20, cmap(new int[][] { { 3, 10, 0 } }, f12(12, new long[] { 0x41, 0x43, 0 },  // glyph 0 at 0x41: B, C
        new long[] { 0x1F600, 0x1F6FF, 15 },                                         // only up to glyph 19
        new long[] { 0x60, 0x50, 1 },                                                // reversed: nothing
        new long[] { 0x10FFFE, 0xFFFFFFFFL, 3 }))));                                // clipped at U+10FFFF
    check("format 12: glyph 0 skipped, ids past the glyph count cut, reversed groups ignored, U+10FFFF the end",
        m12.equals(map(0x42, 1, 0x43, 2, 0x1F600, 15, 0x1F601, 16, 0x1F602, 17, 0x1F603, 18, 0x1F604, 19, 0x10FFFE, 3, 0x10FFFF, 4)), m12.toString());
    check("format 12: a later group wins a character two groups map, as in fontTools",
        Cmap.read(font(20, cmap(new int[][] { { 3, 10, 0 } }, f12(12, new long[] { 0x41, 0x42, 5 }, new long[] { 0x42, 0x42, 9 })))).equals(map(0x41, 5, 0x42, 9)));
    check("format 13: many characters to one glyph", Cmap.read(font(20, cmap(new int[][] { { 0, 6, 0 } }, f12(13, new long[] { 0x4E00, 0x4E02, 7 }, new long[] { 0x30, 0x30, 0 })))).equals(map(0x4E00, 7, 0x4E01, 7, 0x4E02, 7)));
    check("ids at or past the glyph count are left out", Cmap.read(font(3, cmap(new int[][] { { 3, 1, 0 } }, f4raw(new int[][] { { 0x41, 0x44, 0xFFC0, 0 } }, new int[0], -1)))).equals(map(0x41, 1, 0x42, 2)));
    // fixture: the symbol font
    File fonts = fontsDir();
    if (fonts != null) {
      Sfnt sym = Sfnt.parse(Files.readAllBytes(new File(fonts, "cmap/Symbol-cmap.ttf").toPath()));
      Cmap.Best sb = Cmap.readBest(sym, null);
      check("a Windows symbol font: (3, 0) before Mac (1, 0), its characters kept at U+F020..F07E", sb.symbol() && sb.map.firstKey() == 0xF020 && sb.map.lastKey() == 0xF07E && sb.map.size() == 95);
      Sfnt lato = Sfnt.parse(Files.readAllBytes(new File(fonts, "cmap/Lato-cmap.ttf").toPath()));
      check("Lato: (3, 1) format 4, not its Mac format 6", Cmap.readBest(lato, null).platform == 3 && !Cmap.readBest(lato, null).symbol());
    }
  }

  static void preference() throws Exception {
    byte[] a = f6(0x41, 1), b2 = f6(0x41, 2), c = f12(12, new long[] { 0x41, 0x41, 3 });
    check("(3, 10) before (3, 1)", Cmap.read(font(9, cmap(new int[][] { { 3, 1, 0 }, { 3, 10, 1 } }, a, c))).get(0x41) == 3);
    check("(0, 4) before (3, 1), as getBestCmap", Cmap.read(font(9, cmap(new int[][] { { 0, 4, 1 }, { 3, 1, 0 } }, a, c))).get(0x41) == 3);
    check("(3, 1) before (0, 3)", Cmap.read(font(9, cmap(new int[][] { { 0, 3, 0 }, { 3, 1, 1 } }, a, b2))).get(0x41) == 2);
    check("(0, 3) before (3, 0) and (1, 0)", Cmap.read(font(9, cmap(new int[][] { { 0, 3, 0 }, { 1, 0, 1 }, { 3, 0, 1 } }, a, b2))).get(0x41) == 1);
    check("the first of two (3, 1) records", Cmap.read(font(9, cmap(new int[][] { { 3, 1, 0 }, { 3, 1, 1 } }, a, b2))).get(0x41) == 1);
    check("(3, 0) and (1, 0) only: the symbol map", Cmap.readBest(font(9, cmap(new int[][] { { 1, 0, 0 }, { 3, 0, 1 } }, f0(new int[] { 0x41, 1 }), f6(0xF041, 2))), null).symbol());
    byte[] format2 = new B().u16(2).u16(518).u16(0).bytes(new byte[512]).get();
    check("a format this class does not read (2) is passed over", Cmap.read(font(9, cmap(new int[][] { { 3, 1, 0 }, { 0, 3, 1 } }, format2, b2))).get(0x41) == 2);
    byte[] zero = new B().u16(4).u16(0).u16(0).u16(0).get();
    check("a zero-length subtable is passed over, as fontTools skips it", Cmap.read(font(9, cmap(new int[][] { { 3, 1, 0 }, { 0, 3, 1 } }, zero, b2))).get(0x41) == 2);
    byte[] cut = java.util.Arrays.copyOf(f12(12, new long[] { 0x41, 0x41, 3 }), 20);
    check("a damaged best subtable: the next one is read", Cmap.read(font(9, cmap(new int[][] { { 3, 1, 0 }, { 3, 10, 1 } }, a, cut))).get(0x41) == 1);
    check("a record pointing outside the table: the next one", Cmap.read(font(9, cmap(new int[][] { { 3, 10, -1 }, { 3, 1, 0 } }, a))).get(0x41) == 1);
    throwsFont("every subtable damaged: refused", null, "character map (cmap) is damaged", () -> Cmap.read(font(9, cmap(new int[][] { { 3, 10, 0 } }, cut))));
    Cmap.Best none = Cmap.readBest(font(9, cmap(new int[][] { { 3, 1, 0 } }, format2)), null);
    check("no subtable this class reads: an empty map, platform -1", none.map.isEmpty() && none.platform == -1 && none.format == -1);
    throwsFont("a cmap header cut off", null, "cut off", () -> Cmap.read(font(9, new byte[] { 0, 0, 0, 5, 0, 3 })));
    throwsFont("no cmap table", "missing_table", null, () -> Cmap.read(font(9, null)));
  }

  // ---------- variation sequences ----------

  static byte[] f14(int sel, int[] defaults, int[] glyphs) {
    B body = new B();
    int head = 10 + 11;
    long def = 0, nd = 0;
    if (defaults != null) {
      def = head + body.size();
      body.u32(defaults.length / 2);
      for (int i = 0; i < defaults.length; i += 2) body.u24(defaults[i]).u8(defaults[i + 1]);
    }
    if (glyphs != null) {
      nd = head + body.size();
      body.u32(glyphs.length / 2);
      for (int i = 0; i < glyphs.length; i += 2) body.u24(glyphs[i]).u16(glyphs[i + 1]);
    }
    return new B().u16(14).u32(head + body.size()).u32(1).u24(sel).u32(def).u32(nd).bytes(body.get()).get();
  }

  static void uvs() throws Exception {
    File fonts = fontsDir();
    if (fonts != null) {
      Sfnt tw = Sfnt.parse(Files.readAllBytes(new File(fonts, "cmap/Twemoji-cmap.ttf").toPath()));
      Cmap.Uvs u = Cmap.readUvs(tw);
      check("Twemoji: 4 sequences of U+FE0F with glyphs of their own", u.size() == 4 && u.glyphs(0xFE0F).size() == 4 && u.defaultRanges(0xFE0F).isEmpty());
      Sfnt noto = Sfnt.parse(Files.readAllBytes(new File(fonts, "cmap/NotoColorEmoji-cmap.ttf").toPath()));
      Cmap.Uvs nu = Cmap.readUvs(noto);
      check("Noto Color Emoji: 371 default sequences of U+FE0F (keycap bases, (c), (R), text-default emoji)",
          nu.size() == 371 && nu.glyphs(0xFE0F).isEmpty() && nu.isDefault(0xFE0F, 0x23) && nu.isDefault(0xFE0F, 0x2764) && !nu.isDefault(0xFE0F, 0x41));
      check("Noto's sequences round-trip through build", Cmap.readUvs(font(noto.numGlyphs(), Cmap.build(Cmap.read(noto), nu))).equals(nu));
    }
    Cmap.Uvs h = Cmap.readUvs(font(10, cmap(new int[][] { { 0, 5, 0 } }, f14(0xFE0F, new int[] { 0x30, 9, 0x2764, 0, 0x10FFFF, 255 }, new int[] { 0x23, 3, 0x2764, 4, 0x41, 0, 0x42, 10 }))));
    check("format 14 by hand: default ranges (clipped at U+10FFFF), own glyphs, glyph 0 and ids past the count left out, a pair listed both ways is default",
        h.isDefault(0xFE0F, 0x30) && h.isDefault(0xFE0F, 0x39) && !h.isDefault(0xFE0F, 0x3A) && h.isDefault(0xFE0F, 0x10FFFF) && h.glyph(0xFE0F, 0x23) == 3
            && h.isDefault(0xFE0F, 0x2764) && h.glyph(0xFE0F, 0x2764) == -1 && !h.has(0xFE0F, 0x41) && !h.has(0xFE0F, 0x42) && h.size() == 13, h.toString());
    check("a format 14 table on (3, 10) is found when there is no (0, 5)", Cmap.readUvs(font(10, cmap(new int[][] { { 3, 10, 0 } }, f14(0xFE0E, null, new int[] { 0x2764, 2 })))).glyph(0xFE0E, 0x2764) == 2);
    check("no format 14: no sequences", Cmap.readUvs(font(10, cmap(new int[][] { { 3, 1, 0 } }, f6(0x41, 1)))).isEmpty());
    byte[] bad = f14(0xFE0F, null, new int[] { 0x23, 3 });
    Sfnt.w32(bad, 10 + 7, 0x7FFFFFF0);                                                // the glyph table's offset far outside
    throwsFont("format 14 pointing outside the table", null, "variation sequences", () -> Cmap.readUvs(font(10, cmap(new int[][] { { 0, 5, 0 } }, bad))));
    byte[] huge = f14(0xFE0F, null, new int[] { 0x23, 3 });
    Sfnt.w32(huge, 21, -1);                                                           // 4 billion mappings claimed
    throwsFont("format 14 claiming 4 billion mappings", null, "variation sequences", () -> Cmap.readUvs(font(10, cmap(new int[][] { { 0, 5, 0 } }, huge))));
    byte[] many = new B().u16(14).u32(10).u32(0x7FFFFFFF).get();
    throwsFont("format 14 claiming 2 billion selectors", null, "variation sequences", () -> Cmap.readUvs(font(10, cmap(new int[][] { { 0, 5, 0 } }, many))));
    // the Uvs model
    Cmap.Uvs m = new Cmap.Uvs();
    m.addDefaultRange(0xFE0F, 10, 20);
    m.addDefaultRange(0xFE0F, 21, 25);
    m.addDefaultRange(0xFE0F, 5, 12);
    check("default ranges merge when they touch or overlap", m.defaultRanges(0xFE0F).size() == 1 && m.defaultRanges(0xFE0F).get(0)[0] == 5 && m.defaultRanges(0xFE0F).get(0)[1] == 25 && m.size() == 21);
    m.put(0xFE0F, 15, 7);
    check("a glyph of its own splits a default range", m.defaultRanges(0xFE0F).size() == 2 && !m.isDefault(0xFE0F, 15) && m.glyph(0xFE0F, 15) == 7 && m.size() == 21);
    m.addDefault(0xFE0F, 15);
    check("and default again replaces the glyph", m.isDefault(0xFE0F, 15) && m.glyph(0xFE0F, 15) == -1 && m.defaultRanges(0xFE0F).size() == 1);
    m.remove(0xFE0F, 5);
    m.remove(0xFE0F, 25);
    m.put(0xE0100, 0x4E00, 3);
    Cmap.Uvs copy = m.copy();
    check("remove trims the range; copy is equal and separate", m.size() == 20 && copy.equals(m) && m.selectors().size() == 2);
    copy.remove(0xE0100, 0x4E00);
    check("an emptied selector goes away", !copy.equals(m) && copy.selectors().size() == 1 && copy.glyphs(0xE0100).isEmpty());
    // ranges longer than 256 are split in the table and merge back on reading
    Cmap.Uvs big = new Cmap.Uvs();
    big.addDefaultRange(0xFE00, 0x4E00, 0x4E00 + 700);
    big.put(0xFE01, 0x4E00, 5);
    byte[] bt = Cmap.build(map(0x41, 1), big);
    check("a 701-character default range is written as three ranges and reads back as one", Cmap.readUvs(font(10, bt)).equals(big) && bt.length > 0);
  }

  // ---------- format 4 ----------

  static int f4Length(byte[] cmapTable) throws Exception {
    int num = Sfnt.u16(cmapTable, 2);
    for (int i = 0; i < num; i++) {
      int r = 4 + 8 * i;
      if (Sfnt.u16(cmapTable, r) == 3 && Sfnt.u16(cmapTable, r + 2) == 1) return Sfnt.u16(cmapTable, (int) Sfnt.u32(cmapTable, r + 4) + 2);
    }
    return -1;
  }

  static long offset(byte[] t, int pid, int eid) throws Exception {
    for (int i = 0; i < Sfnt.u16(t, 2); i++)
      if (Sfnt.u16(t, 4 + 8 * i) == pid && Sfnt.u16(t, 6 + 8 * i) == eid) return Sfnt.u32(t, 8 + 8 * i);
    return -1;
  }

  /** The cmap table with only the (pid, eid) record (the subtable copied after a one-record header). */
  static byte[] only(byte[] t, int pid, int eid) throws Exception {
    int num = Sfnt.u16(t, 2);
    for (int i = 0; i < num; i++) {
      int r = 4 + 8 * i;
      if (Sfnt.u16(t, r) == pid && Sfnt.u16(t, r + 2) == eid) {
        int off = (int) Sfnt.u32(t, r + 4);
        return cmap(new int[][] { { pid, eid, 0 } }, java.util.Arrays.copyOfRange(t, off, t.length));
      }
    }
    return null;
  }

  /** The smallest format 4 for these mappings, by trying every way of cutting them into segments (small inputs). */
  static long bruteForce(int[] cps, int[] gids) {
    int n = cps.length;
    long[] best = new long[n + 1];
    for (int i = 1; i <= n; i++) {
      best[i] = Long.MAX_VALUE;
      for (int j = 0; j < i; j++) {
        boolean run = true;
        for (int k = j + 1; k < i; k++) run &= cps[k] == cps[k - 1] + 1 && gids[k] - cps[k] == gids[j] - cps[j];
        long cost = run ? 8 : 8 + 2L * (cps[i - 1] - cps[j] + 1);
        best[i] = Math.min(best[i], best[j] + cost);
      }
    }
    return 16 + 8 + best[n];
  }

  static void format4() throws Exception {
    TreeMap<Integer, Integer> ascii = new TreeMap<>();
    for (int c = 0x20; c < 0x7F; c++) ascii.put(c, c - 29);
    byte[] t = Cmap.build(ascii, null);
    check("printable ASCII on consecutive glyphs: one delta segment (32 bytes) and no format 12", f4Length(t) == 32 && Sfnt.u16(t, 2) == 2, "length " + f4Length(t));
    Random r = new Random(3);
    TreeMap<Integer, Integer> shuffled = new TreeMap<>();
    for (int c = 0x20; c < 0x7F; c++) shuffled.put(c, 1 + r.nextInt(500));
    check("printable ASCII on scattered glyphs: one glyph array segment (32 + 190 bytes)", f4Length(Cmap.build(shuffled, null)) == 32 + 190);
    TreeMap<Integer, Integer> gaps = new TreeMap<>();
    for (int c = 0x100; c < 0x200; c += 2) gaps.put(c, 1 + r.nextInt(500));
    check("128 characters with gaps of one: one array segment across the gaps (32 + 2 * 255) instead of 128 segments", f4Length(Cmap.build(gaps, null)) == 32 + 2 * 255);
    TreeMap<Integer, Integer> twoRuns = map(0x41, 1, 0x42, 2, 0x43, 3, 0x45, 10, 0x46, 11, 0x47, 12);
    check("two runs with a gap: two delta segments", f4Length(Cmap.build(twoRuns, null)) == 16 + 8 * 3);
    // the run-based plan against every possible cut, on small random maps
    int worse = 0, tried = 0;
    for (int trial = 0; trial < 3000; trial++) {
      int n = 1 + r.nextInt(11);
      TreeMap<Integer, Integer> m = new TreeMap<>();
      int c = r.nextInt(50);
      while (m.size() < n) {
        c += 1 + (r.nextInt(3) == 0 ? r.nextInt(6) : 0);
        int g = r.nextInt(2) == 0 && m.containsKey(c - 1) ? m.get(c - 1) + 1 : 1 + r.nextInt(40);
        m.put(c, g);
      }
      int[] cps = new int[n], gids = new int[n];
      int k = 0;
      for (Map.Entry<Integer, Integer> e : m.entrySet()) { cps[k] = e.getKey(); gids[k++] = e.getValue(); }
      tried++;
      long plan = Cmap.format4Size(cps, gids, n), brute = bruteForce(cps, gids);
      if (plan != brute) { worse++; if (worse < 4) System.out.println("  plan " + plan + " brute " + brute + " for " + m); }
      if (f4Length(Cmap.build(m, null)) != plan) worse++;
    }
    check("format 4 is as small as the best cut of the mappings into segments (" + tried + " random maps)", worse == 0, worse + " differ");
  }

  static void overflow() throws Exception {
    TreeMap<Integer, Integer> m = scattered(40000L, 40000);
    List<String> warn = new ArrayList<>();
    long t0 = System.nanoTime();
    byte[] t = Cmap.build(m, null, warn, null);
    long ms = (System.nanoTime() - t0) / 1000000;
    check("40,000 scattered BMP characters: built in " + ms + " ms", ms < 5000);
    check("a sentence says format 4 holds only part", warn.size() == 1 && warn.get(0).contains("of the 40000 characters below U+FFFF"), warn.toString());
    Sfnt f = font(65535, t);
    Cmap.Best b = Cmap.readBest(f, null);
    check("the format 12 part has every character", b.format == 12 && b.platform == 3 && b.encoding == 10 && b.map.equals(m));
    TreeMap<Integer, Integer> f4 = Cmap.read(font(65535, only(t, 3, 1)));
    int fit = f4.size();
    TreeMap<Integer, Integer> prefix = new TreeMap<>();
    for (Map.Entry<Integer, Integer> e : m.entrySet()) { if (prefix.size() == fit) break; prefix.put(e.getKey(), e.getValue()); }
    check("format 4 holds the first " + fit + " characters in code point order", fit > 1000 && f4.equals(prefix) && f4Length(t) <= 0xFFFF);
    int[] cps = new int[m.size()], gids = new int[m.size()];
    int k = 0;
    for (Map.Entry<Integer, Integer> e : m.entrySet()) { cps[k] = e.getKey(); gids[k++] = e.getValue(); }
    check("and one more would not fit", Cmap.format4Size(cps, gids, fit) <= 0xFFFF && Cmap.format4Size(cps, gids, fit + 1) > 0xFFFF);
    check("(0, 3) shares (3, 1)'s subtable and (0, 4) (3, 10)'s", offset(t, 0, 3) == offset(t, 3, 1) && offset(t, 0, 4) == offset(t, 3, 10) && offset(t, 0, 4) > offset(t, 0, 3));
    TreeMap<Integer, Integer> fits = scattered(5L, 3000);
    List<String> none = new ArrayList<>();
    byte[] small = Cmap.build(fits, null, none, null);
    check("3,000 scattered characters fit: no format 12, no sentence", none.isEmpty() && Sfnt.u16(small, 2) == 2 && Cmap.read(font(65535, small)).equals(fits));
  }

  static void buildInput() throws Exception {
    check("glyph 0 mappings are left out", Cmap.read(font(10, Cmap.build(map(0x41, 0, 0x42, 2), null))).equals(map(0x42, 2)));
    TreeMap<Integer, Integer> ff = map(0xFFFF, 3, 0x41, 1);
    byte[] t = Cmap.build(ff, null);
    check("U+FFFF goes only into format 12", Cmap.read(font(10, t)).equals(ff) && Cmap.read(font(10, only(t, 3, 1))).equals(map(0x41, 1)));
    throwsFont("a glyph above 65535 is refused", null, "glyph numbers end at 65535", () -> Cmap.build(map(0x41, 70000), null));
    throwsFont("a code point above U+10FFFF is refused", null, "code point", () -> Cmap.build(map(0x110000, 1), null));
    throwsFont("a negative code point is refused", null, "code point", () -> Cmap.build(map(-1, 1), null));
    Map<Integer, Integer> nulls = new HashMap<>();
    nulls.put(0x41, null);
    throwsFont("a missing glyph number is refused", null, "glyph", () -> Cmap.build(nulls, null));
    byte[] e = Cmap.build(new TreeMap<Integer, Integer>(), new Cmap.Uvs());
    check("an empty map: format 4 with only the closing segment", e.length == 4 + 16 + 24 && Cmap.read(font(1, e)).isEmpty());
    check("the same input gives the same bytes", java.util.Arrays.equals(Cmap.build(scattered(9L, 5000), null), Cmap.build(scattered(9L, 5000), null)));
  }

  static void hostile() throws Exception {
    long t0 = System.nanoTime();
    throwsFont("format 13 mapping all of Unicode to one glyph: refused, not read", null, "more characters",
        () -> Cmap.read(font(10, cmap(new int[][] { { 3, 10, 0 } }, f12(13, new long[] { 0, 0xFFFFFFFFL, 1 })))));
    check("  in " + (System.nanoTime() - t0) / 1000000 + " ms", System.nanoTime() - t0 < 3_000_000_000L);
    t0 = System.nanoTime();
    int[][] segs = new int[8000][];
    for (int i = 0; i < segs.length; i++) segs[i] = new int[] { 0, 0xFFFE, 0x100, 0 };   // 8,000 segments over the whole BMP, onto glyphs past the count
    throwsFont("8,000 overlapping format 4 segments: refused after a bounded walk", null, "over and over",
        () -> Cmap.read(font(10, cmap(new int[][] { { 3, 1, 0 } }, f4raw(segs, new int[0], 0xFFFF)))));
    check("  in " + (System.nanoTime() - t0) / 1000000 + " ms", System.nanoTime() - t0 < 3_000_000_000L);
    t0 = System.nanoTime();
    long[][] groups = new long[200000][];
    for (int i = 0; i < groups.length; i++) groups[i] = new long[] { 0x10000, 0x10FFFF, 1 };
    throwsFont("200,000 format 12 groups each over planes 1-16: refused", null, null,
        () -> Cmap.read(font(65535, cmap(new int[][] { { 3, 10, 0 } }, f12(12, groups)))));
    check("  in " + (System.nanoTime() - t0) / 1000000 + " ms", System.nanoTime() - t0 < 3_000_000_000L);
    byte[] claim = f12(12, new long[] { 0x41, 0x41, 1 });
    Sfnt.w32(claim, 12, -1);
    throwsFont("format 12 claiming 4 billion groups (and no other subtable)", null, "damaged", () -> Cmap.read(font(10, cmap(new int[][] { { 3, 10, 0 } }, claim))));
    byte[] claim4 = f4raw(new int[][] { { 0x41, 0x41, 0, 0 } }, new int[0], -1);
    Sfnt.w16(claim4, 6, 0xFFFE);
    throwsFont("format 4 claiming 32,767 segments", null, "damaged", () -> Cmap.read(font(10, cmap(new int[][] { { 3, 1, 0 } }, claim4))));
    byte[] header = new B().u16(0).u16(0xFFFF).get();
    throwsFont("65,535 encoding records in a 4-byte table", null, "cut off", () -> Cmap.read(font(10, header)));
  }

  static void cancel(File fonts) throws Exception {
    final int[] steps = { 0 };
    final String[] stage = { null };
    FontProgress stop = new FontProgress() {
      int calls;
      @Override public void step(String s, int done, int total) { steps[0]++; stage[0] = s; }
      @Override public boolean cancelled() { return ++calls > 1; }
    };
    if (fonts != null) {
      Sfnt inter = Sfnt.parse(Files.readAllBytes(new File(fonts, "cmap/Inter-cmap.ttf").toPath()));
      throwsFont("read stops with Cancel", "cancelled", null, () -> Cmap.read(inter, stop));
      check("  after reporting progress as \"cmap\"", steps[0] >= 1 && "cmap".equals(stage[0]));
    }
    FontProgress stop2 = new FontProgress() {
      int calls;
      @Override public void step(String s, int done, int total) {}
      @Override public boolean cancelled() { return ++calls > 2; }
    };
    throwsFont("build stops with Cancel", "cancelled", null, () -> Cmap.build(scattered(1L, 40000), null, null, stop2));
    final int[] reported = { 0 };
    FontProgress count = new FontProgress() {
      @Override public void step(String s, int done, int total) { reported[0]++; if (done > total) reported[0] += 1000000; }
      @Override public boolean cancelled() { return false; }
    };
    Cmap.build(scattered(1L, 40000), null, null, count);
    check("build reports progress (done never above total)", reported[0] > 10 && reported[0] < 1000000, reported[0] + "");
  }

  // ---------- EmojiData ----------

  static void emojiData(Expected ex) throws Exception {
    StringBuilder s = new StringBuilder();
    int n = 0;
    for (int c = 0; c <= Cmap.MAX_CP; c++) if (EmojiData.isEmojiPresentation(c)) { s.append(Integer.toHexString(c).toUpperCase()).append('\n'); n++; }
    check("EmojiData's Emoji_Presentation is Unicode " + ex.emojiVersion + "'s (" + ex.emojiCount + " code points, the list's hash)",
        n == ex.emojiCount && shaText(s.toString()).equals(ex.emojiSha) && ex.emojiVersion.equals(EmojiData.UNICODE_VERSION), n + " code points");
    StringBuilder v = new StringBuilder();
    int k = 0;
    for (int c = 0; c <= Cmap.MAX_CP; c++) if (EmojiData.hasEmojiVariation(c)) { v.append(Integer.toHexString(c).toUpperCase()).append('\n'); k++; }
    check("EmojiData's emoji variation sequences are Unicode " + ex.variationVersion + "'s (" + ex.variationCount + " characters with U+FE0F, the list's hash)",
        ex.variationSha != null && k == ex.variationCount && shaText(v.toString()).equals(ex.variationSha) && ex.variationVersion.equals(EmojiData.UNICODE_VERSION),
        k + " characters");
  }

  static void emojiRules() {
    check("Emoji_Presentation: watch, grinning face, the Unicode 18 additions; not heart, smiley, digits", EmojiData.isEmojiPresentation(0x231A)
        && EmojiData.isEmojiPresentation(0x1F600) && EmojiData.isEmojiPresentation(0x1F6D9) && EmojiData.isEmojiPresentation(0x1FAFA)
        && !EmojiData.isEmojiPresentation(0x2764) && !EmojiData.isEmojiPresentation(0x263A) && !EmojiData.isEmojiPresentation(0x31)
        && !EmojiData.isEmojiPresentation(0x1FAFB) && !EmojiData.isEmojiPresentation(-1) && !EmojiData.isEmojiPresentation(0x110000));
    boolean text = true;
    for (int c : new int[] { 0x20, 0x23, 0x2A, 0x30, 0x39, 0x41, 0x7A, 0xA9, 0xAE, 0xE9, 0xFF, 0x2E, 0x21 }) text &= EmojiData.textAlwaysWins(c) && !EmojiData.emojiWins(c);
    check("space, # * 0-9, (c) (R), letters, punctuation, Latin-1: the text font's glyph always", text);
    boolean emoji = true;
    for (int c : new int[] { 0x1F600, 0x1F3FB, 0x1F3FF, 0x1F1E6, 0x1F1FF, 0xFE0F, 0x20E3, 0xE0020, 0xE007F, 0x1F000, 0x1FFFF, 0x231A, 0x2705, 0x1F170 })
      emoji &= EmojiData.emojiWins(c);
    check("emoji presentation, skin tones, flags, VS16, keycap, tags, U+1F000..1FFFF: the emoji font's glyph", emoji);
    boolean neither = true;
    for (int c : new int[] { 0x2764, 0x263A, 0x2640, 0x200D, 0xFE0E, 0x2122, 0x2190, 0x3030, 0xE0080, 0x20000, 0xF0000, 0x10FFFF, 0x1EFFF, 0x2014 })
      neither &= !EmojiData.emojiWins(c) && !EmojiData.textAlwaysWins(c);
    check("text-default emoji (heart, smiley, female sign), ZWJ (the text font's own rules name it), VS15, arrows, CJK and private use "
        + "planes: the text font's when it has one", neither);
    boolean vs = true;
    for (int c : new int[] { 0x23, 0x2A, 0x30, 0x39, 0xA9, 0xAE, 0x2764, 0x263A, 0x2640, 0x2642, 0x2122, 0x2194, 0x261D, 0x26F9, 0x1F3F3, 0x1F321, 0x231A, 0x1F6F3 })
      vs &= EmojiData.hasEmojiVariation(c);
    for (int c : new int[] { 0x20, 0x41, 0x2190, 0x200D, 0xFE0F, 0x20E3, 0x1F600, 0x1F3FB, 0x1F1E6, 0x1F7E0, 0x1F6F4, -1, 0x110000 })
      vs &= !EmojiData.hasEmojiVariation(c);
    check("emoji variation sequences: # * 0-9 (c) (R), heart, smiley, gender signs, (TM), arrows, white flag, watch; not letters, ZWJ, the "
        + "selectors, the keycap mark, grinning face, skin tones, flag letters", vs);
  }

  // ---------- fuzz ----------

  static List<byte[]> seeds(File fonts) throws Exception {
    List<byte[]> out = new ArrayList<>();
    if (fonts != null)
      for (String n : new String[] { "cmap/Inter-cmap.ttf", "cmap/Lato-cmap.ttf", "cmap/Twemoji-cmap.ttf", "cmap/NotoColorEmoji-cmap.ttf", "cmap/Symbol-cmap.ttf" }) {
        File f = new File(fonts, n);
        if (f.isFile()) out.add(Sfnt.parse(Files.readAllBytes(f.toPath())).table("cmap"));
      }
    out.add(cmap(new int[][] { { 1, 0, 0 }, { 3, 1, 1 } }, f0(new int[] { 0x41, 1, 0x80, 2 }), f4raw(new int[][] { { 0x30, 0x33, 2, 8 }, { 0x40, 0x41, 0, 0 } }, new int[] { 3, 4, 5, 6 }, -1)));
    out.add(cmap(new int[][] { { 0, 5, 0 }, { 0, 6, 1 }, { 3, 10, 2 } }, f14(0xFE0F, new int[] { 0x30, 9 }, new int[] { 0x23, 3 }),
        f12(13, new long[] { 0x4E00, 0x4E10, 7 }), f12(12, new long[] { 0x1F600, 0x1F610, 3 })));
    out.add(cmap(new int[][] { { 3, 10, 0 }, { 1, 0, 1 } }, f10(0x1F600, 3, 0, 5), f6(0x20, 1, 2, 3)));
    out.add(Cmap.build(scattered(2L, 300), new Cmap.Uvs()));
    return out;
  }

  static void fuzz(File fonts, long seed, int count) throws Exception {
    Random r = new Random(seed);
    List<byte[]> base = seeds(fonts);
    int ok = 0, refused = 0, wrong = 0, slow = 0;
    long t0 = System.nanoTime();
    for (int i = 0; i < count; i++) {
      byte[] t = base.get(r.nextInt(base.size())).clone();
      switch (r.nextInt(5)) {
        case 0: t = java.util.Arrays.copyOf(t, r.nextInt(t.length + 1)); break;                         // cut off
        case 1: for (int k = 1 + r.nextInt(8); k > 0; k--) t[r.nextInt(t.length)] ^= 1 << r.nextInt(8); break;   // bit flips
        case 2: { int p = r.nextInt(Math.max(1, t.length - 4)); for (int k = 0; k < 4 && p + k < t.length; k++) t[p + k] = (byte) 0xFF; break; }  // a huge count
        case 3: { int p = r.nextInt(Math.max(1, t.length - 2)); if (p + 1 < t.length) { t[p] = (byte) 0x7F; t[p + 1] = (byte) 0xFF; } break; }
        default: { t = new byte[r.nextInt(600)]; r.nextBytes(t); if (t.length > 4) { t[0] = 0; t[1] = 0; t[2] = 0; t[3] = (byte) (1 + r.nextInt(4)); } }  // random bytes
      }
      long c0 = System.nanoTime();
      Sfnt f = font(1 + r.nextInt(65535), t);
      try {
        TreeMap<Integer, Integer> m = Cmap.read(f);
        Cmap.Uvs u;
        try { u = Cmap.readUvs(f); } catch (Sfnt.FontException e) { u = null; refused++; }
        byte[] back = Cmap.build(m, u);
        Sfnt fb = font(f.numGlyphs(), back);
        if (!Cmap.read(fb).equals(m) || (u != null && !Cmap.readUvs(fb).equals(u))) wrong++;
        ok++;
      } catch (Sfnt.FontException e) {
        refused++;
      } catch (Throwable e) {
        wrong++;
        if (wrong < 5) System.out.println("  " + e + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : ""));
      }
      if (System.nanoTime() - c0 > 2_000_000_000L) slow++;
    }
    long ms = (System.nanoTime() - t0) / 1000000;
    check("fuzz: " + count + " damaged tables read, built back the same or refused with a FontException (" + ok + " read, " + refused + " refused, " + ms + " ms)",
        wrong == 0 && slow == 0, wrong + " wrong, " + slow + " slow");
  }
}
