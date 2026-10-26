package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * CffToGlyf, PostScript outlines (CFF, CFF2) to TrueType ones. Against tests/java/fixtures/fonts/cfftoglyf: every glyph of three subsets of
 * real fonts (Source Sans 3 as CFF and as CFF2 at its default and at wght 700, Noto Sans CJK JP CID-keyed) and of three hand-written fonts
 * (seac, the flex operators, hint masks, nested subroutines, the arithmetic operators, CFF2 blends at five locations) is compared with
 * fontTools' Cu2QuPen(TTGlyphPen) outline: contour count, on-curve points within 1 unit, bounds within 1, area within 0.5 %, advance, and the
 * left side bearing equal to xMin (make_fixtures.py wrote the expected values). Fonts built here cover the rest: subroutine biases at their
 * boundaries, FontMatrix, every limit, damaged charstrings and tables, progress and Cancel; a seeded fuzz runs the conversion on damaged
 * tables and must end every run in a FontException.
 *
 * <p>{@code -Dcfftoglyf.out=DIR} writes every converted font (and the FontMatrix fonts built here, before and after) for a FreeType check.
 */
public class CffToGlyfTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  static void check(String name, boolean ok, String detail) { check(ok || detail == null ? name : name + " -- " + detail, ok); }

  interface Body { void run() throws Exception; }

  /** Passes when {@code body} ends in a FontException with this code (null: any) whose message contains {@code text} (null: any) and that wraps nothing. */
  static void throwsFont(String name, String code, String text, Body body) {
    try {
      body.run();
      check(name, false, "no exception");
    } catch (Sfnt.FontException e) {
      check(name, (code == null || code.equals(e.code)) && (text == null || e.getMessage().contains(text)) && e.getCause() == null,
          e.code + ": " + e.getMessage() + (e.getCause() != null ? " (cause " + e.getCause() + ")" : ""));
    } catch (Throwable e) {
      check(name, false, e.toString());
    }
  }

  static final String[] FIXTURES = { "ss3-subset.otf", "ss3vf-subset.otf", "cjk-subset.otf", "synth-cff.otf", "synth-cid.otf", "synth-cff2.otf" };
  static File dumpDir;

  public static void main(String[] args) throws Exception {
    String out = System.getProperty("cfftoglyf.out");
    if (out != null) { dumpDir = new File(out); dumpDir.mkdirs(); }
    File fix = fixtureDir();
    if (fix == null || !new File(fix, "ss3-subset.expect.txt").isFile()) {
      check("the fixtures are found (tests/java/fixtures/fonts/cfftoglyf)", false);
    } else {
      for (String name : FIXTURES) fixture(fix, name);
      structure(fix);
      progressAndCancel(fix);
      fuzz(fix);
    }
    cu2qu();
    biases();
    fontMatrix();
    charstrings();
    limits();
    tables();
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  static File fixtureDir() {
    String p = System.getProperty("fixtures");
    if (p != null && new File(p, "fonts/cfftoglyf").isDirectory()) return new File(p, "fonts/cfftoglyf");
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts/cfftoglyf");
    return null;
  }

  static Sfnt font(File dir, String name) throws IOException { return Sfnt.parse(Files.readAllBytes(new File(dir, name).toPath())); }

  static void dump(String name, Sfnt f) throws IOException {
    if (dumpDir != null) Files.write(new File(dumpDir, name).toPath(), f.toBytes());
  }

  // ---------- the fixtures against fontTools ----------

  /** One "loc" section of an .expect.txt file (make_fixtures.py describes the format). */
  static final class Section {
    String label, user;
    int[] coords;
    final List<String[]> glyphs = new ArrayList<>();
  }

  static List<Section> expectations(File f) throws IOException {
    List<Section> out = new ArrayList<>();
    for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
      if (l.isEmpty() || l.startsWith("#")) continue;
      if (l.startsWith("loc ")) {
        String[] w = l.split(" ");
        Section s = new Section();
        s.label = w[1];
        if (!w[2].equals("-")) {
          String[] c = w[2].split(",");
          s.coords = new int[c.length];
          for (int i = 0; i < c.length; i++) s.coords[i] = Integer.parseInt(c[i]);
        }
        s.user = w.length > 3 ? w[3] : null;
        out.add(s);
      } else {
        out.get(out.size() - 1).glyphs.add(l.startsWith("s ") ? l.split(" ", 11) : l.split(" "));
      }
    }
    return out;
  }

  static void fixture(File dir, String name) throws Exception {
    Sfnt src = font(dir, name);
    List<Section> sections = expectations(new File(dir, name.replace(".otf", ".expect.txt")));
    for (Section s : sections) {
      if (s.user != null) normalizeAgrees(src, name, s);
      long t0 = System.nanoTime();
      Sfnt out = CffToGlyf.convert(src, s.coords, null);
      double ms = (System.nanoTime() - t0) / 1e6;
      dump(name.replace(".otf", "") + "-" + s.label.replace(',', '_').replace('=', '-') + ".ttf", out);
      byte[][] glyphs = Glyf.readAll(Sfnt.parse(out.toBytes()));
      Glyf.Metrics m = Glyf.Metrics.read(out);
      int exact = 0;
      List<String> bad = new ArrayList<>();
      for (String[] w : s.glyphs) {
        int gid = Integer.parseInt(w[1]);
        String why = compare(w, glyphs[gid], m.advance[gid], m.lsb[gid]);
        if (why == null) continue;
        if (why.equals("exact")) { exact++; continue; }
        if (bad.size() < 4) bad.add("glyph " + gid + ": " + why);
        else if (bad.size() == 4) bad.add("...");
      }
      check(String.format("%s %s: %d glyphs match fontTools (contours, on-curve points within 1 unit, bounds within 1, area within 0.5 %%, "
          + "advance, bearing = xMin), %d of them point for point; converted in %.0f ms", name, s.label, s.glyphs.size(), exact, ms),
          bad.isEmpty() && s.glyphs.size() == glyphs.length, bad.isEmpty() ? "glyph count " + glyphs.length : String.join("; ", bad));
    }
  }

  /** VarStore.normalize gives the F2Dot14 location fontTools gave (avar applied; 1 apart at most, from where each rounds). */
  static void normalizeAgrees(Sfnt f, String name, Section s) throws Sfnt.FontException {
    VarStore.Fvar fvar = VarStore.Fvar.read(f);
    double[] user = new double[fvar.axes.size()];
    for (int i = 0; i < user.length; i++) user[i] = fvar.axes.get(i).def;
    for (String kv : s.user.split(",")) {
      String[] p = kv.split("=");
      user[fvar.axisIndex(p[0])] = Double.parseDouble(p[1]);
    }
    int[] mine = VarStore.normalize(f, fvar, user);
    boolean ok = mine.length == s.coords.length;
    for (int i = 0; ok && i < mine.length; i++) ok = Math.abs(mine[i] - s.coords[i]) <= 1;
    check(name + " " + s.label + ": VarStore.normalize gives fontTools' location " + Arrays.toString(s.coords), ok, Arrays.toString(mine));
  }

  /** null when the glyph matches the expected line, "exact" when it matches point for point, else what differs. */
  static String compare(String[] w, byte[] raw, int advance, int lsb) throws Sfnt.FontException {
    if (advance != Integer.parseInt(w[2])) return "advance " + advance + ", expected " + w[2];
    if (w[0].equals("e")) return raw.length == 0 ? "exact" : "not empty";
    Glyf.Glyph g = Glyf.decode(raw);
    if (w[0].equals("c")) {
      if (!g.isComposite() || g.components.size() != w.length - 3) return "components " + g.components.size() + ", expected " + (w.length - 3);
      for (int i = 0; i < g.components.size(); i++) {
        Glyf.Component k = g.components.get(i);
        if (!(k.glyph + "," + k.dx + "," + k.dy).equals(w[3 + i])) return "component " + k.glyph + "," + k.dx + "," + k.dy + ", expected " + w[3 + i];
        if (!k.xy || k.scaleKind != 0) return "component " + i + " is not a plain offset";
      }
      return lsb == g.xMin ? "exact" : "bearing " + lsb + ", xMin " + g.xMin;
    }
    if (g.contours != Integer.parseInt(w[3])) return "contours " + g.contours + ", expected " + w[3];
    String[] exp = w[10].split(" / ");
    List<List<int[]>> mine = onCurve(g);
    for (int c = 0; c < exp.length; c++) {
      String[] pts = exp[c].isEmpty() ? new String[0] : exp[c].split(" ");
      if (pts.length != mine.get(c).size()) return "contour " + c + ": " + mine.get(c).size() + " on-curve points, expected " + pts.length;
      for (int i = 0; i < pts.length; i++) {
        String[] xy = pts[i].split(",");
        int[] p = mine.get(c).get(i);
        if (Math.abs(p[0] - Integer.parseInt(xy[0])) > 1 || Math.abs(p[1] - Integer.parseInt(xy[1])) > 1)
          return "contour " + c + " point " + i + " at " + p[0] + "," + p[1] + ", expected " + pts[i];
      }
    }
    int[] bb = Glyf.headerBounds(raw);
    for (int i = 0; i < 4; i++)
      if (Math.abs(bb[i] - Integer.parseInt(w[4 + i])) > 1) return "bounds " + Arrays.toString(bb) + ", expected " + w[4] + " " + w[5] + " " + w[6] + " " + w[7];
    double a = area(g), ea = Double.parseDouble(w[8]);
    if (Math.abs(a - ea) > Math.max(0.005 * Math.abs(ea), 2)) return String.format("area %.1f, expected %.1f", a, ea);
    if (lsb != g.xMin) return "bearing " + lsb + ", xMin " + g.xMin;
    return Long.toHexString(crc(g) | 0x100000000L).substring(1).equals(w[9]) ? "exact" : null;
  }

  /** Each contour's on-curve points, with repeated points left out as make_fixtures.py leaves them out. */
  static List<List<int[]>> onCurve(Glyf.Glyph g) {
    List<List<int[]>> out = new ArrayList<>();
    int s = 0;
    for (int e : g.endPts) {
      List<int[]> d = new ArrayList<>();
      for (int i = s; i <= e; i++)
        if (i == s || g.x[i] != g.x[i - 1] || g.y[i] != g.y[i - 1] || g.on[i] != g.on[i - 1]) d.add(new int[] { g.x[i], g.y[i], g.on[i] ? 1 : 0 });
      while (d.size() > 1 && Arrays.equals(d.get(d.size() - 1), d.get(0))) d.remove(d.size() - 1);
      List<int[]> on = new ArrayList<>();
      for (int[] p : d) if (p[2] == 1) on.add(p);
      out.add(on);
      s = e + 1;
    }
    return out;
  }

  /** CRC-32 of "x,y,o;" for every point, then "|" and the end points: the crc make_fixtures.py records. */
  static long crc(Glyf.Glyph g) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < g.x.length; i++) sb.append(g.x[i]).append(',').append(g.y[i]).append(',').append(g.on[i] ? 1 : 0).append(';');
    sb.append('|');
    for (int i = 0; i < g.endPts.length; i++) sb.append(i > 0 ? "," : "").append(g.endPts[i]);
    CRC32 c = new CRC32();
    c.update(sb.toString().getBytes(StandardCharsets.US_ASCII));
    return c.getValue();
  }

  /** Signed area of a TrueType outline (quadratic pieces, implied on-curve points included). */
  static double area(Glyf.Glyph g) {
    double total = 0;
    int s = 0;
    for (int e : g.endPts) {
      List<double[]> full = new ArrayList<>();
      int n = e - s + 1;
      for (int i = 0; i < n; i++) {
        int a = s + i, b = s + (i + 1) % n;
        full.add(new double[] { g.x[a], g.y[a], g.on[a] ? 1 : 0 });
        if (!g.on[a] && !g.on[b]) full.add(new double[] { (g.x[a] + g.x[b]) / 2.0, (g.y[a] + g.y[b]) / 2.0, 1 });
      }
      s = e + 1;
      int k = -1;
      for (int i = 0; i < full.size() && k < 0; i++) if (full.get(i)[2] == 1) k = i;
      if (k < 0) continue;
      int m = full.size();
      for (int i = 0; i < m; ) {
        double[] p0 = full.get((k + i) % m), p1 = full.get((k + i + 1) % m);
        if (p1[2] == 1) { total += cross(p0, p1) / 2; i++; }
        else { double[] p2 = full.get((k + i + 2) % m); total += (cross(p0, p1) + cross(p1, p2)) / 3 + cross(p0, p2) / 6; i += 2; }
      }
    }
    return total;
  }

  static double cross(double[] u, double[] v) { return u[0] * v[1] - v[0] * u[1]; }

  // ---------- what the converted font is made of ----------

  static void structure(File dir) throws Exception {
    for (String name : FIXTURES) {
      Sfnt src = font(dir, name);
      Map<String, byte[]> before = new HashMap<>();
      for (String t : src.tags()) before.put(t, src.table(t).clone());
      Sfnt out = CffToGlyf.convert(src, null, null);
      boolean untouched = before.size() == src.tags().size();
      for (String t : src.tags()) untouched &= Arrays.equals(before.get(t), src.table(t));
      List<String> why = new ArrayList<>();
      if (out.sfntVersion != Sfnt.TRUETYPE) why.add("sfntVersion " + Integer.toHexString(out.sfntVersion));
      for (String t : new String[] { "CFF ", "CFF2", "VORG" }) if (out.has(t)) why.add(t.trim() + " kept");
      if (!out.has("glyf") || !out.has("loca")) why.add("no glyf / loca");
      byte[] maxp = out.table("maxp");
      if (maxp.length != 32 || Sfnt.u32(maxp, 0) != 0x00010000L) why.add("maxp is not version 1.0");
      if (out.numGlyphs() != src.numGlyphs()) why.add("glyph count");
      byte[] post = out.table("post");
      if (post.length != 32 || Sfnt.u32(post, 0) != 0x00030000L) why.add("post is not version 3");
      else if (!Arrays.equals(Arrays.copyOfRange(post, 4, 16), Arrays.copyOfRange(src.table("post"), 4, 16))) why.add("post's angle / underline / fixed pitch changed");
      for (String t : src.tags()) {
        if (Arrays.asList("CFF ", "CFF2", "VORG", "head", "hhea", "hmtx", "maxp", "post").contains(t)) continue;
        if (!Arrays.equals(src.table(t), out.table(t))) why.add(t.trim() + " changed");
      }
      byte[] h0 = src.table("head"), h1 = out.table("head");
      for (int i = 0; i < 54; i++)
        if (h0[i] != h1[i] && !(i >= 36 && i < 44) && !(i >= 50 && i < 54) && !(i >= 8 && i < 12)) { why.add("head byte " + i + " changed"); break; }
      Sfnt back = Sfnt.parse(out.toBytes());
      byte[][] glyphs = Glyf.readAll(back);
      int pts = 0;
      for (byte[] g : glyphs) pts += g.length == 0 ? 0 : Glyf.decode(g).numPoints();
      int[] adv0 = Glyf.Metrics.read(src).advance, adv1 = Glyf.Metrics.read(back).advance;
      if (!Arrays.equals(adv0, adv1)) why.add("advances changed");
      check(name + ": the converted font is TrueType (sfntVersion 1.0, glyf / loca, maxp 1.0, post 3; CFF / CFF2 / VORG gone), every other table "
          + "is copied unchanged, it reads back (" + glyphs.length + " glyphs, " + pts + " points), and the source font is untouched",
          why.isEmpty() && untouched, why.isEmpty() ? "source changed" : String.join(", ", why));
      throwsFont(name + ": converting the converted font again is refused", "not_cff", "already has TrueType outlines", () -> CffToGlyf.convert(back, null, null));
    }
    Sfnt vf = font(dir, "ss3vf-subset.otf");
    Sfnt out = CffToGlyf.convert(vf, new int[] { 9000 }, null);
    check("ss3vf-subset.otf: fvar, avar, HVAR, MVAR and STAT stay for the instancer",
        out.has("fvar") && out.has("avar") && out.has("HVAR") && out.has("MVAR") && out.has("STAT"));
    Sfnt cjk = CffToGlyf.convert(font(dir, "cjk-subset.otf"), null, null);
    check("cjk-subset.otf: vhea and vmtx are kept, VORG (CFF only) is dropped", cjk.has("vhea") && cjk.has("vmtx") && !cjk.has("VORG"));
  }

  // ---------- progress and Cancel ----------

  static void progressAndCancel(File dir) throws Exception {
    Builder b = new Builder(false);
    b.glyphs.add(cs("endchar"));
    for (int g = 1; g < 700; g++) b.glyphs.add(cs(SQUARE, "endchar"));
    Sfnt f = b.font(1000);
    final List<String> steps = new ArrayList<>();
    FontProgress rec = new FontProgress() {
      @Override public void step(String stage, int done, int total) { steps.add(stage + " " + done + "/" + total); }
      @Override public boolean cancelled() { return false; }
    };
    CffToGlyf.convert(f, null, rec);
    check("700 glyphs: progress every 256 glyphs, then the end (" + steps + ")",
        steps.equals(Arrays.asList("convert 0/700", "convert 256/700", "convert 512/700", "convert 700/700")));
    steps.clear();
    CffToGlyf.convert(font(dir, "ss3-subset.otf"), null, rec);
    check("ss3-subset.otf: progress from 0 to 169 of 169 glyphs (" + steps + ")", steps.equals(Arrays.asList("convert 0/169", "convert 169/169")));
    final int[] calls = { 0 };
    FontProgress later = new FontProgress() {
      @Override public void step(String stage, int done, int total) { calls[0]++; }
      @Override public boolean cancelled() { return calls[0] >= 2; }
    };
    throwsFont("Cancel during the conversion stops it with the cancelled code", "cancelled", null, () -> CffToGlyf.convert(f, null, later));
    check("... at the next check, 256 glyphs in (" + calls[0] + " progress steps)", calls[0] == 2);
    FontProgress at = new FontProgress() {
      @Override public void step(String stage, int done, int total) {}
      @Override public boolean cancelled() { return true; }
    };
    throwsFont("Cancel before the first glyph stops it too", "cancelled", null, () -> CffToGlyf.convert(f, null, at));
  }

  // ---------- fuzz ----------

  /** Damaged copies of every fixture's CFF / CFF2 table (and of whole files): every run converts or ends in a plain FontException. */
  static void fuzz(final File dir) throws Exception {
    final List<String> bad = new ArrayList<>();
    final int[] counts = new int[3];                             // runs, refused, converted
    final long[] worst = { 0 };
    Thread t = new Thread(() -> {
      try {
        Random r = new Random(0x0CFF2C0FL);
        for (String name : FIXTURES) {
          Sfnt src = font(dir, name);
          boolean cff2 = src.has("CFF2");
          String tag = cff2 ? "CFF2" : "CFF ";
          byte[] table = src.table(tag), file = src.toBytes();
          for (int i = 0; i < 1000; i++) {
            Sfnt f;
            int kind = i % 6;
            if (kind == 5) {
              byte[] b = file.clone();
              for (int k = 1 + r.nextInt(8); k > 0; k--) b[r.nextInt(b.length)] ^= 1 << r.nextInt(8);
              try { f = Sfnt.parse(b); } catch (Sfnt.FontException e) { continue; }
            } else {
              f = src.copy();
              f.put(tag, damage(table, kind, cff2, r));
            }
            int[] coords = null;
            if (cff2 && r.nextBoolean()) {
              coords = new int[r.nextInt(3)];
              for (int k = 0; k < coords.length; k++) coords[k] = r.nextInt(8) == 0 ? r.nextInt() : r.nextInt(32769) - 16384;
            }
            long t0 = System.nanoTime();
            String why = fuzzRun(f, coords);
            worst[0] = Math.max(worst[0], System.nanoTime() - t0);
            counts[0]++;
            if (why == null) counts[2]++;
            else if (why.isEmpty()) counts[1]++;
            else if (bad.size() < 6) bad.add(name + " #" + i + " (" + kind + "): " + why);
          }
        }
      } catch (Throwable e) {
        bad.add("fuzz setup: " + e);
      }
    });
    long t0 = System.nanoTime();
    t.setDaemon(true);
    t.start();
    t.join(120000);
    if (t.isAlive()) {
      check("the fuzz finishes within 120 s", false, "still running after " + counts[0] + " runs");
      System.out.println(fails + " FAILED");
      System.exit(1);
    }
    double s = (System.nanoTime() - t0) / 1e9;
    check(String.format("fuzz: %d damaged fonts (truncated, bit flips, random bytes, huge counts, random tables, damaged files; random CFF2 "
        + "locations): %d refused with a FontException, %d converted and read back, none failed otherwise; %.1f s, slowest %.0f ms",
        counts[0], counts[1], counts[2], s, worst[0] / 1e6), bad.isEmpty() && s < 60 && counts[0] > 5000, String.join("; ", bad));
  }

  /** null: converted and the result reads back; "": refused with a FontException of its own; else what went wrong. */
  static String fuzzRun(Sfnt f, int[] coords) {
    Sfnt out;
    try {
      out = CffToGlyf.convert(f, coords, null);
    } catch (Sfnt.FontException e) {
      return e.getCause() == null ? "" : "FontException caused by " + e.getCause();
    } catch (Throwable e) {
      return e.toString();
    }
    try {
      Sfnt back = Sfnt.parse(out.toBytes());
      for (byte[] g : Glyf.readAll(back)) Glyf.decode(g);
      Glyf.Metrics.read(back);
      return null;
    } catch (Throwable e) {
      return "the converted font does not read back: " + e;
    }
  }

  static byte[] damage(byte[] t, int kind, boolean cff2, Random r) {
    switch (kind) {
      case 0:
        return Arrays.copyOf(t, r.nextInt(t.length));
      case 1: {
        byte[] b = t.clone();
        int span = r.nextBoolean() ? Math.min(b.length, 600) : b.length;      // half the time in the header, DICTs and INDEX heads
        for (int k = 1 + r.nextInt(8); k > 0; k--) b[r.nextInt(span)] ^= 1 << r.nextInt(8);
        return b;
      }
      case 2: {
        byte[] b = t.clone();
        int at = r.nextInt(b.length), n = Math.min(b.length - at, 1 + r.nextInt(16));
        for (int k = 0; k < n; k++) b[at + k] = (byte) r.nextInt(256);
        return b;
      }
      case 3: {
        byte[] b = t.clone();
        int[] big = { 0xFFFF, 0xFFFFFFFF, 0x7FFFFFFF, 0x80000000, 0x10000, 0xFFFFF };
        int v = big[r.nextInt(big.length)], at = r.nextInt(Math.max(1, Math.min(b.length, r.nextBoolean() ? 600 : b.length) - 4));
        if (r.nextBoolean() && at + 2 <= b.length) Sfnt.w16(b, at, v);
        else if (at + 4 <= b.length) Sfnt.w32(b, at, v);
        return b;
      }
      default: {
        byte[] b = new byte[r.nextInt(3000)];
        r.nextBytes(b);
        if (b.length >= 5 && r.nextBoolean()) { b[0] = (byte) (cff2 ? 2 : 1); b[1] = 0; b[2] = (byte) (cff2 ? 5 : 4); }
        return b;
      }
    }
  }

  // ---------- cu2qu ----------

  /** cubicToQuads against values fontTools' cu2qu (4.67) gives for the same cubics, and its error bound on seeded random cubics. */
  static void cu2qu() {
    Object[][] cases = {
      // name, cubic, tolerance, pieces, first control point (fontTools: curve_to_quadratic(cubic, tol))
      { "a quarter circle", new double[] { 0, 0, 0, 552.2847, 447.7153, 1000, 1000, 1000 }, 1.0, 5, 0.0, 165.685410 },
      { "a quarter circle at 0.1", new double[] { 0, 0, 0, 552.2847, 447.7153, 1000, 1000, 1000 }, 0.1, 11, 0.0, 75.311550 },
      { "an S curve", new double[] { 0, 0, 1000, 0, 0, 1000, 1000, 1000 }, 1.0, 11, 136.363636, 0.0 },
      { "a wild curve", new double[] { 310, 150, 1210, 1450, -290, 50, 710, 170 }, 1.0, 13, 413.846154, 300.0 },
      { "a loop", new double[] { 600, 0, 900, 200, 600, 200, 550, 500 }, 1.0, 7, 664.285714, 42.857143 },
      { "a cusp", new double[] { 0, 0, 1000, 1000, 0, 1000, 1000, 0 }, 1.0, 11, 136.363636, 136.363636 },
      { "a straight line", new double[] { 0, 0, 30, 0, 70, 0, 100, 0 }, 1.0, 2, 22.5, 0.0 },
      { "a point", new double[] { 5, 5, 5, 5, 5, 5, 5, 5 }, 1.0, 1, 5.0, 5.0 },
      { "a raised quadratic", new double[] { 0, 0, 1000 / 3.0, 2000 / 3.0, 2000 / 3.0, 2000 / 3.0, 1000, 0 }, 1.0, 1, 500.0, 1000.0 },
    };
    double[] q = new double[2 * CffToGlyf.MAX_PIECES], w = new double[10 * CffToGlyf.MAX_PIECES];
    for (Object[] c : cases) {
      int n = CffToGlyf.cubicToQuads((double[]) c[1], (Double) c[2], q, w);
      boolean ok = n == (Integer) c[3] && Math.abs(q[0] - (Double) c[4]) < 1e-5 && Math.abs(q[1] - (Double) c[5]) < 1e-5;
      check("cu2qu, " + c[0] + ": " + c[3] + " quadratics, the first control point at " + c[4] + "," + c[5] + " as fontTools", ok,
          n + " quadratics, first at " + q[0] + "," + q[1]);
    }
    int n = CffToGlyf.cubicToQuads(new double[] { 0, 0, 30, 0, 70, 0, 100, 0 }, 1.0, q, w);
    check("cu2qu, a straight line: the second control point at 77.5,0 as fontTools", n == 2 && Math.abs(q[2] - 77.5) < 1e-9 && q[3] == 0);
    n = CffToGlyf.cubicToQuads(new double[] { 0, 0, 4e6, 0, -4e6, 0, 0, 0 }, 1.0, q, w);
    check("cu2qu, a curve no 100 pieces follow (fontTools gives up): the 100-piece spline", n == CffToGlyf.MAX_PIECES);
    Random r = new Random(1000);
    double worst = 0;
    int tested = 0;
    for (int k = 0; k < 2000; k++) {
      double[] c = new double[8];
      for (int i = 0; i < 8; i++) c[i] = r.nextInt(4001) - 2000;
      n = CffToGlyf.cubicToQuads(c, 1.0, q, w);
      if (n == CffToGlyf.MAX_PIECES) continue;
      tested++;
      for (int i = 0; i < n; i++) {
        double q0x = i == 0 ? c[0] : (q[2 * i - 2] + q[2 * i]) / 2, q0y = i == 0 ? c[1] : (q[2 * i - 1] + q[2 * i + 1]) / 2;
        double q2x = i == n - 1 ? c[6] : (q[2 * i] + q[2 * i + 2]) / 2, q2y = i == n - 1 ? c[7] : (q[2 * i + 1] + q[2 * i + 3]) / 2;
        for (int s = 0; s <= 32; s++) {
          double u = s / 32.0, v = (i + u) / n, m = 1 - u, mv = 1 - v;
          double qx = m * m * q0x + 2 * m * u * q[2 * i] + u * u * q2x, qy = m * m * q0y + 2 * m * u * q[2 * i + 1] + u * u * q2y;
          double cx = mv * mv * mv * c[0] + 3 * mv * mv * v * c[2] + 3 * mv * v * v * c[4] + v * v * v * c[6];
          double cy = mv * mv * mv * c[1] + 3 * mv * mv * v * c[3] + 3 * mv * v * v * c[5] + v * v * v * c[7];
          worst = Math.max(worst, Math.hypot(qx - cx, qy - cy));
        }
      }
    }
    check(String.format("cu2qu, %d seeded random cubics: every spline stays within the tolerance (1 unit) of its cubic, worst %.4f", tested, worst),
        tested > 1900 && worst <= 1 + 1e-9);
  }

  // ---------- building fonts here ----------

  static final Map<String, Integer> OPS = new HashMap<>();
  static {
    String[] ops = { "hstem", "1", "vstem", "3", "vmoveto", "4", "rlineto", "5", "hlineto", "6", "vlineto", "7", "rrcurveto", "8",
        "callsubr", "10", "return", "11", "endchar", "14", "vsindex", "15", "blend", "16", "hstemhm", "18", "hintmask", "19", "cntrmask", "20",
        "rmoveto", "21", "hmoveto", "22", "vstemhm", "23", "rcurveline", "24", "rlinecurve", "25", "vvcurveto", "26", "hhcurveto", "27",
        "callgsubr", "29", "vhcurveto", "30", "hvcurveto", "31",
        "dotsection", "1200", "and", "1203", "or", "1204", "not", "1205", "abs", "1209", "add", "1210", "sub", "1211", "div", "1212",
        "neg", "1214", "eq", "1215", "drop", "1218", "put", "1220", "get", "1221", "ifelse", "1222", "random", "1223", "mul", "1224",
        "sqrt", "1226", "dup", "1227", "exch", "1228", "index", "1229", "roll", "1230", "hflex", "1234", "flex", "1235", "hflex1", "1236",
        "flex1", "1237" };
    for (int i = 0; i < ops.length; i += 2) OPS.put(ops[i], Integer.parseInt(ops[i + 1]));
  }

  /** A 100-unit square from the origin (CFF 1 glyphs add endchar). */
  static final Object[] SQUARE = { 0, 0, "rmoveto", 100, 0, "rlineto", 0, 100, "rlineto", -100, 0, "rlineto" };

  /** A Type 2 charstring: Integers and Doubles are numbers (Doubles as 16.16), Strings operators, byte[] raw bytes, Object[] spliced in. */
  static byte[] cs(Object... parts) {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    for (Object o : parts) {
      if (o instanceof Object[]) {
        byte[] r = cs((Object[]) o);
        b.write(r, 0, r.length);
      } else if (o instanceof byte[]) {
        byte[] r = (byte[]) o;
        b.write(r, 0, r.length);
      } else if (o instanceof String) {
        Integer op = OPS.get(o);
        if (op == null) throw new IllegalArgumentException("no operator " + o);
        if (op >= 1200) { b.write(12); b.write(op - 1200); } else b.write(op);
      } else if (o instanceof Integer) {
        int v = (Integer) o;
        if (v >= -107 && v <= 107) b.write(v + 139);
        else if (v >= 108 && v <= 1131) { v -= 108; b.write((v >> 8) + 247); b.write(v); }
        else if (v >= -1131 && v <= -108) { v = -v - 108; b.write((v >> 8) + 251); b.write(v); }
        else if (v >= -32768 && v <= 32767) { b.write(28); b.write(v >> 8); b.write(v); }
        else throw new IllegalArgumentException("number " + v);
      } else {
        int f = (int) Math.round(((Number) o).doubleValue() * 65536);
        b.write(255); b.write(f >>> 24); b.write(f >>> 16); b.write(f >>> 8); b.write(f);
      }
    }
    return b.toByteArray();
  }

  static Object[] times(int n, Object... parts) {
    Object[] out = new Object[n];
    for (int i = 0; i < n; i++) out[i] = parts;
    return out;
  }

  static byte[] bytes(int... b) {
    byte[] out = new byte[b.length];
    for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
    return out;
  }

  /** A DICT in operator order; integers are written in the 5-byte form, so a table's layout does not depend on the offsets in it. */
  static final class Dict {
    final List<Object[]> entries = new ArrayList<>();

    Dict put(int op, double... args) {
      for (int i = entries.size() - 1; i >= 0; i--) if ((Integer) entries.get(i)[0] == op) entries.remove(i);
      entries.add(new Object[] { op, args });
      return this;
    }

    Dict raw(byte... b) { entries.add(new Object[] { -1, b }); return this; }

    byte[] bytes() {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      for (Object[] e : entries) {
        int op = (Integer) e[0];
        if (op < 0) { byte[] r = (byte[]) e[1]; b.write(r, 0, r.length); continue; }
        for (double v : (double[]) e[1]) {
          if (v == Math.rint(v) && Math.abs(v) < 2147483648.0) {
            int i = (int) v;
            b.write(29); b.write(i >>> 24); b.write(i >>> 16); b.write(i >>> 8); b.write(i);
            continue;
          }
          String s = java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
          List<Integer> nib = new ArrayList<>();
          for (char ch : s.toCharArray()) nib.add(ch == '.' ? 0xA : ch == '-' ? 0xE : ch - '0');
          nib.add(0xF);
          if (nib.size() % 2 == 1) nib.add(0xF);
          b.write(30);
          for (int i = 0; i < nib.size(); i += 2) b.write(nib.get(i) << 4 | nib.get(i + 1));
        }
        if (op >= 1200) { b.write(12); b.write(op - 1200); } else b.write(op);
      }
      return b.toByteArray();
    }
  }

  static byte[] index(List<byte[]> items, boolean cff2) {
    Sfnt.Writer w = new Sfnt.Writer();
    if (cff2) w.u32(items.size()); else w.u16(items.size());
    if (items.isEmpty()) return w.toByteArray();
    long total = 1;
    for (byte[] i : items) total += i.length;
    int offSize = total < 0x100 ? 1 : total < 0x10000 ? 2 : total < 0x1000000 ? 3 : 4;
    w.u8(offSize);
    long o = 1;
    for (int i = 0; i <= items.size(); i++) {
      for (int k = offSize - 1; k >= 0; k--) w.u8((int) (o >> (8 * k)));
      if (i < items.size()) o += items.get(i).length;
    }
    for (byte[] i : items) w.bytes(i);
    return w.toByteArray();
  }

  /** A Font DICT (CID-keyed and CFF2 fonts) or the one Private DICT of a name-keyed font, with its local subroutines. */
  static final class Fd {
    final Dict dict = new Dict(), priv = new Dict();
    List<byte[]> subrs = new ArrayList<>();
    boolean noPrivate;

    byte[] privateBytes(boolean cff2) {
      Dict p = new Dict();
      p.entries.addAll(priv.entries);
      if (!subrs.isEmpty()) {
        p.put(CffToGlyf.SUBRS, 0);
        p.put(CffToGlyf.SUBRS, p.bytes().length);                  // Subrs follow the Private DICT
      }
      return p.bytes();
    }
  }

  /**
   * A CFF table built here: CFF 1 name-keyed (one Private DICT in {@link #main}), CFF 1 CID-keyed when {@link #fds} is set, or CFF2 (always
   * with an FDArray). Layout: header, [Name, Top DICT, String INDEX | CFF2 Top DICT], Global Subrs, charset, variation store, FDSelect,
   * CharStrings, FDArray, then each Private DICT followed by its Subrs.
   */
  static final class Builder {
    final boolean cff2;
    int major, hdrSize;
    final List<byte[]> glyphs = new ArrayList<>(), gsubrs = new ArrayList<>();
    final Dict top = new Dict();
    final Fd main = new Fd();                // name-keyed: the Private DICT and its subroutines
    byte[] charset;                // written as is (format byte first); null: charsetOffset
    int charsetOffset = -1;        // a predefined charset (0 ISOAdobe, 1 Expert, 2 Expert subset); -1: no charset operator
    List<Fd> fds;                  // CID-keyed CFF 1; CFF2 (one Font DICT unless set)
    byte[] fdSelect, vstore;       // written as is; vstore is an ItemVariationStore (its uint16 length is added)
    boolean charStrings = true;
    byte[] topTail;                // raw bytes at the end of the Top DICT

    Builder(boolean cff2) {
      this.cff2 = cff2;
      major = cff2 ? 2 : 1;
      hdrSize = cff2 ? 5 : 4;
      if (cff2) fds = new ArrayList<>(Arrays.asList(new Fd()));
    }

    Builder withGlyph(int g, byte[] charstring) { glyphs.set(g, charstring); return this; }

    Builder cid(int count) {
      fds = new ArrayList<>();
      for (int i = 0; i < count; i++) fds.add(new Fd());
      return this;
    }

    byte[] build() {
      List<Fd> privs = fds != null ? fds : Arrays.asList(main);
      int[] priv = new int[privs.size()];
      int charsetAt = 0, selAt = 0, csAt = 0, fdaAt = 0, vsAt = 0;
      byte[] out = null;
      for (int pass = 0; pass < 2; pass++) {                      // the first pass finds the offsets, the second writes them
        Sfnt.Writer w = new Sfnt.Writer();
        Dict t = new Dict();
        if (fds != null && !cff2) t.put(CffToGlyf.ROS, 391, 392, 0);
        t.entries.addAll(top.entries);
        if (charset != null) t.put(CffToGlyf.CHARSET, charsetAt);
        else if (charsetOffset >= 0) t.put(CffToGlyf.CHARSET, charsetOffset);
        if (charStrings) t.put(CffToGlyf.CHARSTRINGS, csAt);
        if (vstore != null) t.put(CffToGlyf.VSTORE, vsAt);
        if (fds != null) {
          t.put(CffToGlyf.FD_ARRAY, fdaAt);
          if (fdSelect != null) t.put(CffToGlyf.FD_SELECT, selAt);
        } else if (!main.noPrivate) {
          t.put(CffToGlyf.PRIVATE, main.privateBytes(false).length, priv[0]);
        }
        if (topTail != null) t.raw(topTail);
        byte[] tb = t.bytes();
        if (cff2) {
          w.u8(major).u8(0).u8(hdrSize).u16(tb.length).zeros(Math.max(0, hdrSize - 5)).bytes(tb);
        } else {
          w.u8(major).u8(0).u8(hdrSize).u8(4).zeros(Math.max(0, hdrSize - 4));
          w.bytes(index(Arrays.asList("CffToGlyfTest".getBytes(StandardCharsets.US_ASCII)), false));
          w.bytes(index(Arrays.asList(tb), false));
          w.bytes(index(Arrays.asList("Adobe".getBytes(StandardCharsets.US_ASCII), "Identity".getBytes(StandardCharsets.US_ASCII)), false));
        }
        w.bytes(index(gsubrs, cff2));
        if (charset != null) { charsetAt = w.size(); w.bytes(charset); }
        if (vstore != null) { vsAt = w.size(); w.u16(vstore.length).bytes(vstore); }
        if (fdSelect != null) { selAt = w.size(); w.bytes(fdSelect); }
        csAt = w.size();
        w.bytes(index(glyphs, cff2));
        if (fds != null) {
          fdaAt = w.size();
          List<byte[]> dicts = new ArrayList<>();
          for (int i = 0; i < fds.size(); i++) {
            Dict d = new Dict();
            d.entries.addAll(fds.get(i).dict.entries);
            if (!fds.get(i).noPrivate) d.put(CffToGlyf.PRIVATE, fds.get(i).privateBytes(cff2).length, priv[i]);
            dicts.add(d.bytes());
          }
          w.bytes(index(dicts, cff2));
        }
        for (int i = 0; i < privs.size(); i++) {
          Fd d = privs.get(i);
          if (d.noPrivate) continue;
          priv[i] = w.size();
          w.bytes(d.privateBytes(cff2));
          if (!d.subrs.isEmpty()) w.bytes(index(d.subrs, cff2));
        }
        out = w.toByteArray();
      }
      return out;
    }

    /** The table in a font with head, hhea, maxp, hmtx (advances of 500), post and name. */
    Sfnt font(int upem) { return otf(build(), cff2, glyphs.size(), upem); }
  }

  static Sfnt otf(byte[] table, boolean cff2, int glyphs, int upem) {
    Sfnt f = new Sfnt(Sfnt.OTTO);
    f.put(cff2 ? "CFF2" : "CFF ", table);
    Sfnt.Writer h = new Sfnt.Writer();
    h.u32(0x00010000).u32(0x00010000).u32(0).u32(0x5F0F3CF5).u16(3).u16(upem).zeros(16);
    h.s16(0).s16(-200).s16(1000).s16(800).u16(0).u16(8).s16(2).s16(0).s16(0);
    f.put("head", h.toByteArray());
    Sfnt.Writer hh = new Sfnt.Writer();
    hh.u32(0x00010000).s16(800).s16(-200).s16(0).u16(500).s16(0).s16(0).s16(0).s16(1).s16(0).s16(0).zeros(8).s16(0).u16(glyphs);
    f.put("hhea", hh.toByteArray());
    f.put("maxp", new Sfnt.Writer().u32(0x00005000).u16(glyphs).toByteArray());
    Sfnt.Writer m = new Sfnt.Writer();
    for (int i = 0; i < glyphs; i++) m.u16(500).s16(0);
    f.put("hmtx", m.toByteArray());
    f.put("post", new Sfnt.Writer().u32(0x00030000).zeros(28).toByteArray());
    Map<Integer, String> names = new HashMap<>();
    names.put(1, "CffToGlyf Test");
    names.put(2, "Regular");
    names.put(4, "CffToGlyf Test");
    names.put(6, "CffToGlyfTest");
    f.put("name", Sfnt.buildName(names));
    return f;
  }

  /** An ItemVariationStore with no delta rows (CFF2 keeps its deltas in the charstrings): regions[r][axis] = start, peak, end (F2Dot14). */
  static byte[] ivs(int axes, int[][][] regions, int[][] data) {
    Sfnt.Writer w = new Sfnt.Writer();
    int head = 8 + 4 * data.length, at = head + 4 + 6 * axes * regions.length;
    w.u16(1).u32(head).u16(data.length);
    for (int[] d : data) { w.u32(at); at += 6 + 2 * d.length; }
    w.u16(axes).u16(regions.length);
    for (int[][] r : regions) for (int[] a : r) w.s16(a[0]).s16(a[1]).s16(a[2]);
    for (int[] d : data) { w.u16(0).u16(0).u16(d.length); for (int i : d) w.u16(i); }
    return w.toByteArray();
  }

  /** A name-keyed CFF font of .notdef (empty) and glyphs drawn by these charstrings. */
  static Sfnt simple(byte[]... charstrings) {
    Builder b = new Builder(false);
    b.glyphs.add(cs("endchar"));
    b.glyphs.addAll(Arrays.asList(charstrings));
    return b.font(1000);
  }

  /** A CFF2 font (one Font DICT, no variation store) of .notdef (empty) and these charstrings. */
  static Sfnt simple2(byte[]... charstrings) {
    Builder b = new Builder(true);
    b.glyphs.add(new byte[0]);
    b.glyphs.addAll(Arrays.asList(charstrings));
    return b.font(1000);
  }

  /** Glyph g of the converted font, decoded (null when it is empty). */
  static Glyf.Glyph converted(Sfnt f, int[] coords, int g) throws Sfnt.FontException {
    byte[] raw = Glyf.readAll(CffToGlyf.convert(f, coords, null))[g];
    return raw.length == 0 ? null : Glyf.decode(raw);
  }

  static String bounds(Glyf.Glyph g) { return g == null ? "empty" : g.xMin + "," + g.yMin + "," + g.xMax + "," + g.yMax; }

  // ---------- subroutine biases ----------

  /** Each bias at the INDEX sizes where it changes: the call reaches the one subroutine that draws (the rest only return). */
  static void biases() throws Exception {
    int[][] cases = { { 1239, -107, 0 }, { 1239, 1131, 1238 }, { 1240, -1131, 0 }, { 1240, 108, 1239 }, { 33899, -1131, 0 },
        { 33899, 32767, 33898 }, { 33900, -32768, 0 }, { 33900, 1131, 33899 } };
    for (int[] c : cases) {
      Builder b = new Builder(false);
      for (int i = 0; i < c[0]; i++) b.main.subrs.add(i == c[2] ? cs(SQUARE, "endchar") : cs("return"));
      b.glyphs.add(cs("endchar"));
      b.glyphs.add(cs(c[1], "callsubr", "endchar"));
      Glyf.Glyph g = converted(b.font(1000), null, 1);
      check("local subroutines, " + c[0] + " of them: callsubr " + c[1] + " runs number " + c[2], g != null && g.contours == 1);
    }
    Builder b = new Builder(false);
    for (int i = 0; i < 1240; i++) b.gsubrs.add(i == 1239 ? cs(SQUARE, "endchar") : cs("return"));
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(108, "callgsubr", "endchar"));
    Glyf.Glyph g = converted(b.font(1000), null, 1);
    check("global subroutines, 1240 of them: callgsubr 108 runs number 1239", g != null && g.contours == 1);
    Builder b2 = new Builder(true);
    for (int i = 0; i < 1240; i++) b2.fds.get(0).subrs.add(i == 1239 ? cs(SQUARE) : new byte[0]);
    b2.glyphs.add(new byte[0]);
    b2.glyphs.add(cs(108, "callsubr"));
    g = converted(b2.font(1000), null, 1);
    check("CFF2 local subroutines, 1240 of them: callsubr 108 runs number 1239 (CFF2 subroutines end without return)", g != null && g.contours == 1);
    for (int[] c : new int[][] { { 1239, 1132 }, { 1239, -108 }, { 1240, 109 }, { 1240, -1132 } }) {
      Builder bb = new Builder(false);
      for (int i = 0; i < c[0]; i++) bb.main.subrs.add(cs("return"));
      bb.glyphs.add(cs("endchar"));
      bb.glyphs.add(cs(c[1], "callsubr", "endchar"));
      throwsFont("local subroutines, " + c[0] + " of them: callsubr " + c[1] + " is refused", "bad_font", "calls a subroutine the font does not have",
          () -> CffToGlyf.convert(bb.font(1000), null, null));
    }
  }

  // ---------- FontMatrix ----------

  /** FontMatrix read as FreeType reads it (the converted fonts are dumped for a FreeType check with -Dcfftoglyf.out). */
  static void fontMatrix() throws Exception {
    Object[][] named = {
      // name, upem, Top DICT FontMatrix (null: none), the square's bounds, the seac accent's offset (10,20 in the charstring)
      { "no FontMatrix", 1000, null, "0,0,100,100", "10,20" },
      { "the usual 1/1000", 1000, new double[] { 0.001, 0, 0, 0.001, 0, 0 }, "0,0,100,100", "10,20" },
      { "a uniform 1/500 (FreeType keeps the size)", 1000, new double[] { 0.002, 0, 0, 0.002, 0, 0 }, "0,0,100,100", "10,20" },
      { "1/1000 at 2048 units per em", 2048, new double[] { 0.001, 0, 0, 0.001, 0, 0 }, "0,0,100,100", "10,20" },
      { "1/2048 at 2048 units per em", 2048, new double[] { 1 / 2048.0, 0, 0, 1 / 2048.0, 0, 0 }, "0,0,100,100", "10,20" },
      { "twice as wide", 1000, new double[] { 0.002, 0, 0, 0.001, 0, 0 }, "0,0,200,100", "20,20" },
      { "slanted", 1000, new double[] { 0.001, 0, 0.00025, 0.001, 0, 0 }, "0,0,125,100", "15,20" },
      { "moved", 1000, new double[] { 0.001, 0, 0, 0.001, 0.05, 0.02 }, "50,20,150,120", "10,20" },
      { "mirrored", 1000, new double[] { 0.001, 0, 0, -0.001, 0, 0 }, "0,-100,100,0", "10,-20" },
      { "all zeros (ignored)", 1000, new double[] { 0, 0, 0, 0, 0, 0 }, "0,0,100,100", "10,20" },
      { "huge (ignored)", 1000, new double[] { 1e7, 0, 0, 1e7, 0, 0 }, "0,0,100,100", "10,20" },
    };
    int k = 0;
    for (Object[] c : named) {
      Builder b = new Builder(false);
      if (c[2] != null) b.top.put(CffToGlyf.FONT_MATRIX, (double[]) c[2]);
      b.charset = bytes(0, 0, 34, 0, 125, 0, 203);                // A, acute, Aacute
      b.glyphs.add(cs("endchar"));
      b.glyphs.add(cs(SQUARE, "endchar"));
      b.glyphs.add(cs(0, 200, "rmoveto", 50, 0, "rlineto", 0, 50, "rlineto", "endchar"));
      b.glyphs.add(cs(10, 20, 65, 194, "endchar"));
      Sfnt f = b.font((Integer) c[1]);
      Sfnt out = CffToGlyf.convert(f, null, null);
      dump("fm-" + k + ".otf", f);
      dump("fm-" + k + ".ttf", out);
      k++;
      byte[][] glyphs = Glyf.readAll(out);
      Glyf.Glyph sq = Glyf.decode(glyphs[1]), seac = Glyf.decode(glyphs[3]);
      Glyf.Component acc = seac.isComposite() && seac.components.size() == 2 ? seac.components.get(1) : null;
      check("FontMatrix, " + c[0] + ": the square becomes " + c[3] + ", clockwise; the accent of the seac glyph moves by " + c[4],
          bounds(sq).equals(c[3]) && area(sq) < 0 && acc != null && (acc.dx + "," + acc.dy).equals(c[4]),
          bounds(sq) + ", area " + area(sq) + ", accent " + (acc == null ? "none" : acc.dx + "," + acc.dy));
    }
    Object[][] cid = {
      // name, upem, Top DICT matrix, Font DICT 0 matrix, Font DICT 1 matrix, bounds of glyph 1 (FD 0) and glyph 2 (FD 1)
      { "a Font DICT's 1/500", 1000, null, new double[] { 0.002, 0, 0, 0.002, 0, 0 }, null, "0,0,200,200", "0,0,100,100" },
      { "a Font DICT's 1/1000 at 2048 units per em", 2048, null, new double[] { 0.001, 0, 0, 0.001, 0, 0 }, null, "0,0,205,205", "0,0,100,100" },
      { "an identity Top DICT matrix and a Font DICT's 1/1000", 1000, new double[] { 1, 0, 0, 1, 0, 0 }, new double[] { 0.001, 0, 0, 0.001, 0, 0 },
          null, "0,0,100,100", "0,0,100,100" },
      { "a Top DICT's 1/1000 and a Font DICT's 1/1000 (FreeType shrinks these)", 1000, new double[] { 0.001, 0, 0, 0.001, 0, 0 },
          new double[] { 0.001, 0, 0, 0.001, 0, 0 }, null, "0,0,100,100", "0,0,100,100" },
      { "two Font DICTs with different matrices", 1000, null, null, new double[] { 0.002, 0, 0, 0.002, 0, 0 }, "0,0,100,100", "0,0,200,200" },
      { "a Font DICT's moved matrix", 1000, null, new double[] { 0.001, 0, 0, 0.001, 0.01, 0 }, null, "10,0,110,100", "0,0,100,100" },
      { "a Top DICT's slant after a Font DICT's 1/500", 1000, new double[] { 0.001, 0, 0.0005, 0.001, 0, 0 }, new double[] { 0.002, 0, 0, 0.002, 0, 0 },
          null, "0,0,300,200", "0,0,150,100" },
    };
    for (Object[] c : cid) {
      Builder b = new Builder(false).cid(2);
      if (c[2] != null) b.top.put(CffToGlyf.FONT_MATRIX, (double[]) c[2]);
      if (c[3] != null) b.fds.get(0).dict.put(CffToGlyf.FONT_MATRIX, (double[]) c[3]);
      if (c[4] != null) b.fds.get(1).dict.put(CffToGlyf.FONT_MATRIX, (double[]) c[4]);
      b.charset = bytes(2, 0, 1, 0, 1);                           // CIDs 1 and 2
      b.fdSelect = bytes(3, 0, 2, 0, 0, 0, 0, 2, 1, 0, 3);       // glyphs 0-1 in Font DICT 0, glyph 2 in Font DICT 1
      b.glyphs.add(cs("endchar"));
      b.glyphs.add(cs(SQUARE, "endchar"));
      b.glyphs.add(cs(SQUARE, "endchar"));
      Sfnt f = b.font((Integer) c[1]);
      Sfnt out = CffToGlyf.convert(f, null, null);
      dump("fm-" + k + ".otf", f);
      dump("fm-" + k + ".ttf", out);
      k++;
      byte[][] glyphs = Glyf.readAll(out);
      Glyf.Glyph g1 = Glyf.decode(glyphs[1]), g2 = Glyf.decode(glyphs[2]);
      check("CID FontMatrix, " + c[0] + ": the squares become " + c[5] + " and " + c[6], bounds(g1).equals(c[5]) && bounds(g2).equals(c[6]),
          bounds(g1) + " and " + bounds(g2));
    }
  }

  // ---------- charstrings ----------

  static void refused(String name, String text, Sfnt f) {
    throwsFont(name, null, text, () -> CffToGlyf.convert(f, null, null));
  }

  static void charstrings() throws Exception {
    // arithmetic (CFF 1 only): results are not whole numbers until the point is rounded
    Glyf.Glyph g = converted(simple(cs(0, 0, "rmoveto", 10, 4, "div", 0, "rlineto", 0, 100, "rlineto", "endchar")), null, 1);
    check("10 4 div draws to x 2.5, rounded to 3", g != null && g.xMax == 3, bounds(g));
    g = converted(simple(cs(0, 0, "rmoveto", 2, "sqrt", 100, "mul", 0, "rlineto", 0, 100, "rlineto", "endchar")), null, 1);
    check("2 sqrt 100 mul draws to x 141.42, rounded to 141", g != null && g.xMax == 141, bounds(g));
    g = converted(simple(cs(0, 0, "rmoveto", 7, 0, "put", 3, 1, "put", 0, "get", 1, "get", "mul", 0, "rlineto", 0, 100, "rlineto", "endchar")), null, 1);
    check("put and get keep numbers in the transient array (7 * 3)", g != null && g.xMax == 21, bounds(g));
    g = converted(simple(cs(0, 0, "rmoveto", 1, 2, 3, 3, 1, "roll", "drop", "sub", 0, "exch", "rlineto", 50, 0, "rlineto", "endchar")), null, 1);
    check("roll (1 2 3 -> 3 1 2), drop, sub and exch: a line up by 3 - 1", g != null && bounds(g).equals("0,0,50,2"), bounds(g));
    g = converted(simple(cs(0, 0, "rmoveto", 5, 9, 1, 2, "ifelse", 0, "rlineto", 0, 50, "rlineto", "endchar")), null, 1);
    check("ifelse picks the first value when s1 <= s2", g != null && g.xMax == 5, bounds(g));
    refused("division by zero is refused", "divides by zero", simple(cs(0, 0, "rmoveto", 1, 0, "div", 0, "rlineto", "endchar")));
    refused("the square root of a negative number is refused", "square root of a negative number", simple(cs(-4, "sqrt", "endchar")));
    refused("random is refused (a TrueType outline cannot change)", "random operator", simple(cs("random", "endchar")));
    refused("index below the stack is refused", "indexes the stack out of range", simple(cs(1, 5, "index", "endchar")));
    refused("roll of more numbers than the stack has is refused", "rolls the stack out of range", simple(cs(1, 2, 5, 1, "roll", "endchar")));
    refused("put outside the transient array is refused", "transient array out of range", simple(cs(1, 32, "put", "endchar")));
    refused("get outside the transient array is refused", "transient array out of range", simple(cs(-1, "get", "endchar")));
    refused("an arithmetic operator in CFF2 is refused", "CFF2 does not have", simple2(cs(0, 0, "rmoveto", 1, 2, "add", 0, "rlineto")));
    // the stack: 48 numbers in CFF 1, 513 in CFF2
    Object[] lines48 = times(24, 1, 1);
    g = converted(simple(cs(0, 0, "rmoveto", lines48, "rlineto", "endchar")), null, 1);
    check("48 numbers on the stack (CFF 1's limit) draw 24 lines", g != null && g.numPoints() == 25, g == null ? "empty" : g.numPoints() + " points");
    refused("49 numbers on a CFF 1 stack are refused", "too many numbers on the stack", simple(cs(0, 0, "rmoveto", lines48, 1, "rlineto", "endchar")));
    Object[] lines512 = times(256, 1, 1);
    g = converted(simple2(cs(0, 0, "rmoveto", lines512, 1, "rlineto")), null, 1);
    check("513 numbers on a CFF2 stack (its limit) draw 256 lines", g != null && g.numPoints() == 257, g == null ? "empty" : g.numPoints() + " points");
    refused("514 numbers on a CFF2 stack are refused", "too many numbers on the stack", simple2(cs(0, 0, "rmoveto", lines512, 1, 1, "rlineto")));
    // reserved operators are skipped with their numbers, as FreeType skips them
    g = converted(simple(cs(0, 0, "rmoveto", 7, 7, bytes(2), 100, 0, "rlineto", 3, bytes(12, 1), 0, 100, "rlineto", 5, 5, bytes(12, 38),
        -100, 0, "rlineto", bytes(9), "endchar")), null, 1);
    check("reserved operators (2, 9, escape 1, escape 38) are skipped and clear the stack", g != null && bounds(g).equals("0,0,100,100") && g.numPoints() == 4, bounds(g));
    g = converted(simple(cs(100, 0, "rlineto", 0, 100, "rlineto", "endchar")), null, 1);
    check("a line without a moveto starts its contour at the origin", g != null && g.contours == 1 && g.numPoints() == 3 && bounds(g).equals("0,0,100,100"), bounds(g));
    g = converted(simple(cs(0, 0, "rmoveto", 50, 50, "rmoveto", 100, 0, "rlineto", 0, 100, "rlineto", 0, 0, "rmoveto", "endchar")), null, 1);
    check("a moveto right after a moveto, and one at the end, draw nothing", g != null && g.contours == 1 && g.numPoints() == 3 && g.xMin == 50, bounds(g));
    g = converted(simple(cs("endchar")), null, 1);
    check("a glyph that is only endchar is empty", g == null);
    // subroutines
    refused("callsubr in a font without local subroutines is refused", "calls a subroutine the font does not have", simple(cs(0, "callsubr", "endchar")));
    refused("callgsubr in a font without global subroutines is refused", "calls a subroutine the font does not have", simple(cs(0, "callgsubr", "endchar")));
    Builder b = new Builder(false);
    b.main.subrs.add(cs(-107, "callsubr", "return"));
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(-107, "callsubr", "endchar"));
    refused("a subroutine that calls itself is refused", "nests its subroutines too deep", b.font(1000));
    for (int depth = 10; depth <= 11; depth++) {
      Builder c = new Builder(false);
      for (int i = 0; i < depth; i++) c.main.subrs.add(i < depth - 1 ? cs(i + 1 - 107, "callsubr", "return") : cs(SQUARE, "endchar"));
      c.glyphs.add(cs("endchar"));
      c.glyphs.add(cs(-107, "callsubr", "endchar"));
      if (depth == 10) {
        g = converted(c.font(1000), null, 1);
        check("subroutines nested 10 deep (the Type 2 limit) draw", g != null && g.contours == 1);
      } else {
        refused("subroutines nested 11 deep are refused", "nests its subroutines too deep", c.font(1000));
      }
    }
    b = new Builder(false);
    b.main.subrs.add(cs(0, 0, "rmoveto", 100, 0, "rlineto", "return"));
    b.gsubrs.add(cs(-107, "callsubr", 0, 100, "rlineto", "return"));
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(-107, "callgsubr", "endchar"));
    g = converted(b.font(1000), null, 1);
    check("a global subroutine calls the glyph's local ones", g != null && bounds(g).equals("0,0,100,100"), bounds(g));
    // damaged charstrings
    refused("a charstring cut inside a number is refused", "is cut off", simple(cs(0, 0, "rmoveto", bytes(247))));
    refused("a charstring cut inside a 16-bit number is refused", "is cut off", simple(cs(0, 0, "rmoveto", bytes(28, 1))));
    refused("a charstring cut inside a 16.16 number is refused", "is cut off", simple(cs(0, 0, "rmoveto", bytes(255, 0, 1, 0))));
    refused("a charstring cut after the escape byte is refused", "is cut off", simple(cs(0, 0, "rmoveto", bytes(12))));
    refused("a hintmask whose mask is cut off is refused", "is cut off", simple(cs(0, 10, "hstem", 0, 10, "vstem", "hintmask")));
    refused("a line with one number is refused", "is missing the numbers of an operator", simple(cs(0, 0, "rmoveto", 5, "rlineto", "endchar")));
    refused("a curve with five numbers is refused", "is missing the numbers of an operator", simple(cs(0, 0, "rmoveto", 1, 2, 3, 4, 5, "rrcurveto", "endchar")));
    refused("a point far outside the glyph area is refused", "far outside the glyph area", simple(cs(0, 0, "rmoveto", 30000, 30000, "mul", 0, "rlineto", "endchar")));
    refused("an outline wider than a TrueType glyph can be is refused", "does not fit in a TrueType font",
        simple(cs(0, 0, "rmoveto", 30000, 0, "rlineto", 30000, 0, "rlineto", 0, 100, "rlineto", "endchar")));
    // seac: an accented letter from two glyphs that Standard Encoding names
    b = seacFont(bytes(0, 0, 34, 0, 125, 0, 36, 0, 37, 0, 38, 0, 39));
    Sfnt out = CffToGlyf.convert(b.font(1000), null, null);
    byte[][] glyphs = Glyf.readAll(out);
    Glyf.Glyph aacute = Glyf.decode(glyphs[3]), mixed = Glyf.decode(glyphs[4]);
    check("seac (endchar with 4 numbers) becomes a composite glyph: A, then the acute moved by 10,20", composite(aacute).equals("1,0,0 2,10,20"), composite(aacute));
    check("... with the width first (5 numbers) as well", composite(Glyf.decode(glyphs[6])).equals("1,0,0 2,-30,40"), composite(Glyf.decode(glyphs[6])));
    check("... its bounds cover both glyphs (0,0 to 110,270)", bounds(aacute).equals("0,0,110,270"), bounds(aacute));
    check("contours and a seac in one glyph: everything drawn into one simple glyph (3 contours), as fontTools does",
        mixed != null && !mixed.isComposite() && mixed.contours == 3 && bounds(mixed).equals("0,0,400,270"),
        mixed == null ? "empty" : mixed.contours + " contours, " + bounds(mixed));
    Glyf.Metrics m = Glyf.Metrics.read(out);
    check("... the seac glyphs' bearings are their xMin (0, 0, -30)", m.lsb[3] == 0 && m.lsb[4] == 0 && m.lsb[6] == -30, m.lsb[3] + " " + m.lsb[4] + " " + m.lsb[6]);
    refused("contours and a seac whose base is itself a seac are refused", "from another accented letter",
        seacFont(bytes(0, 0, 34, 0, 125, 0, 36, 0, 37, 0, 38, 0, 39)).withGlyph(5, cs(SQUARE, 0, 0, 67, 194, "endchar")).font(1000));
    b = new Builder(false);
    b.charset = bytes(0, 0, 34, 0, 125);
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(0, 0, 65, 194, "endchar"));                    // A, built from A and acute
    b.glyphs.add(cs(SQUARE, "endchar"));
    refused("a seac glyph whose base is itself (a composite that names itself) is refused", "names itself", b.font(1000));
    for (int[] codes : new int[][] { { 65, 0 }, { 300, 194 }, { 65, 200 } }) {
      refused("seac with codes " + codes[0] + " and " + codes[1] + " is refused", "from a glyph the font does not have",
          seacFont(bytes(0, 0, 34, 0, 125, 0, 36, 0, 37, 0, 38, 0, 39)).withGlyph(3, cs(0, 0, codes[0], codes[1], "endchar")).font(1000));
    }
    b = seacFont(bytes(1, 0, 34, 0, 0, 125, 0, 0, 36, 3));
    check("seac through a format 1 charset", composite(converted(b.font(1000), null, 3)).equals("1,0,0 2,10,20"));
    b = seacFont(bytes(2, 0, 34, 0, 0, 0, 125, 0, 0, 0, 36, 0, 3));
    check("seac through a format 2 charset", composite(converted(b.font(1000), null, 3)).equals("1,0,0 2,10,20"));
    b = new Builder(false);
    b.charsetOffset = 0;                                           // ISOAdobe: glyph i is SID i
    Object[] acute = { 0, 200, "rmoveto", 10, 0, "rlineto", 0, 10, "rlineto" };
    for (int i = 0; i < 130; i++)
      b.glyphs.add(i == 34 ? cs(SQUARE, "endchar") : i == 125 ? cs(acute, "endchar") : i == 129 ? cs(0, 0, 65, 194, "endchar") : cs("endchar"));
    check("seac through the predefined ISOAdobe charset (A is glyph 34)", composite(converted(b.font(1000), null, 129)).equals("34,0,0 125,0,0"));
    b = seacFont(null);
    b.charsetOffset = 1;
    refused("seac in a font with the Expert charset is refused", "from a glyph the font does not have", b.font(1000));
    b = seacFont(bytes(3, 0, 0));
    refused("seac through a charset of an unknown format is refused", "charset has an unknown format", b.font(1000));
    b = new Builder(false).cid(1);
    b.charset = bytes(2, 0, 1, 0, 1);
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(SQUARE, "endchar"));
    b.glyphs.add(cs(0, 0, 65, 194, "endchar"));
    refused("seac in a CID-keyed font is refused", "CID-keyed font cannot do", b.font(1000));
    // CFF2 blends
    refused("a CFF2 blend without a variation store is refused", "without a variation store", simple2(cs(0, 0, "rmoveto", 100, 5, 1, "blend", 0, "rlineto")));
    Builder v = cff2Varied();
    v.glyphs.set(1, cs(1, "vsindex", SQUARE));
    refused("vsindex naming variation data the store does not have is refused", "names variation data the font does not have", v.font(1000));
    v = cff2Varied();
    v.glyphs.set(1, cs(0, 0, "rmoveto", 1, 2, 3, 5, "blend", "rlineto"));
    refused("a blend of more numbers than the stack has is refused", "blends more numbers than it has", v.font(1000));
    v = cff2Varied();
    Sfnt vf = v.font(1000);
    String at = bounds(converted(vf, null, 1)) + " " + bounds(converted(vf, new int[] { 8192 }, 1)) + " " + bounds(converted(vf, new int[] { 16384 }, 1))
        + " " + bounds(converted(vf, new int[] { -16384 }, 1));
    check("a CFF2 blend (100 + 50 at wght max) at the default, half way, the maximum and the minimum: 100, 125, 150, 100",
        at.equals("0,0,100,100 0,0,125,100 0,0,150,100 0,0,100,100"), at);
    // CharStrings and maxp disagree
    b = new Builder(false);
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(SQUARE, "endchar"));
    Sfnt more = otf(b.build(), false, 4, 1000);
    byte[][] four = Glyf.readAll(CffToGlyf.convert(more, null, null));
    check("maxp counts more glyphs than CharStrings has: the rest are empty", four.length == 4 && four[1].length > 0 && four[2].length == 0 && four[3].length == 0);
    b.glyphs.add(cs(SQUARE, "endchar"));
    Sfnt fewer = otf(b.build(), false, 2, 1000);
    check("maxp counts fewer glyphs than CharStrings has: maxp's count is converted", Glyf.readAll(CffToGlyf.convert(fewer, null, null)).length == 2);
    // the path holds a bounded number of points
    CffToGlyf.Path p = new CffToGlyf.Path();
    int n = 0;
    while (p.add(0, 0, false)) n++;
    check("a glyph's path holds at most " + CffToGlyf.MAX_PATH_POINTS + " points", n == CffToGlyf.MAX_PATH_POINTS);
  }

  /** Glyphs: .notdef, A (SID 34), acute (125), Aacute (seac), a square with a seac, C (a seac too), Agrave-like (width + seac). */
  static Builder seacFont(byte[] charset) {
    Builder b = new Builder(false);
    b.charset = charset;
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(SQUARE, "endchar"));
    b.glyphs.add(cs(0, 200, "rmoveto", 100, 0, "rlineto", 0, 50, "rlineto", "endchar"));
    b.glyphs.add(cs(10, 20, 65, 194, "endchar"));
    b.glyphs.add(cs(300, 0, "rmoveto", 100, 0, "rlineto", 0, 100, "rlineto", 10, 20, 65, 194, "endchar"));
    b.glyphs.add(cs(0, 0, 65, 194, "endchar"));
    b.glyphs.add(cs(400, -30, 40, 65, 194, "endchar"));
    return b;
  }

  /** "glyph,dx,dy ..." of a composite glyph, or what it is instead. */
  static String composite(Glyf.Glyph g) {
    if (g == null || !g.isComposite()) return g == null ? "empty" : "simple";
    StringBuilder sb = new StringBuilder();
    for (Glyf.Component k : g.components) sb.append(sb.length() > 0 ? " " : "").append(k.glyph).append(',').append(k.dx).append(',').append(k.dy);
    return sb.toString();
  }

  /** A CFF2 font with one axis and one region (peak at its maximum): glyph 1 is a square 100 + 50 * scalar wide. */
  static Builder cff2Varied() {
    Builder b = new Builder(true);
    b.vstore = ivs(1, new int[][][] { { { 0, 16384, 16384 } } }, new int[][] { { 0 } });
    b.glyphs.add(new byte[0]);
    b.glyphs.add(cs(0, 0, "rmoveto", 100, 50, 1, "blend", 0, "rlineto", 0, 100, "rlineto", -100, -50, 1, "blend", 0, "rlineto"));
    return b;
  }

  // ---------- limits ----------

  static void limits() throws Exception {
    // work: subroutines that call the next one twice, 9 deep, so the innermost runs 512 times
    throwsFont("a glyph that runs more than 100,000 operators is refused", "too_complex", "takes too long to draw",
        () -> CffToGlyf.convert(fanOut(1, 200), null, null));
    long t0 = System.nanoTime();
    throwsFont("a font whose glyphs run more than 100 million operators in all is refused", "too_complex", "take too long to convert",
        () -> CffToGlyf.convert(fanOut(1100, 180), null, null));
    double ms = (System.nanoTime() - t0) / 1e6;
    check(String.format("... and stops in %.0f ms (under 5 s)", ms), ms < 5000);
    // curves: at 16 units per em the tolerance is 0.016 units, so each of these cubics needs cu2qu's 100 pieces
    Object[] wild = times(8, 10000, 0, -20000, 0, 10000, 0);
    Builder b = new Builder(false);
    b.main.subrs.add(cs(wild, "rrcurveto", "return"));
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(0, 0, "rmoveto", times(82, -107, "callsubr"), "endchar"));
    refused("a glyph of more than 65,535 TrueType points (656 cubics of 100 quadratics) is refused", "too many points for a TrueType glyph", b.font(16));
    b = new Builder(false);
    b.main.subrs.add(cs(wild, "rrcurveto", "return"));
    b.glyphs.add(cs("endchar"));
    for (int i = 0; i < 20; i++) b.glyphs.add(cs(0, 0, "rmoveto", times(75, -107, "callsubr"), "endchar"));
    final Sfnt curvy = b.font(16);
    t0 = System.nanoTime();
    throwsFont("a font whose cubics need more than 50 million quadratic tries in all is refused", "too_complex", "take too long to convert",
        () -> CffToGlyf.convert(curvy, null, null));
    ms = (System.nanoTime() - t0) / 1e6;
    check(String.format("... and stops in %.0f ms (under 5 s)", ms), ms < 5000);
    // Font DICTs, INDEX counts, subroutines, variation regions
    Builder many = new Builder(false).cid(4097);
    many.fdSelect = bytes(3, 0, 1, 0, 0, 0, 0, 1);
    many.glyphs.add(cs("endchar"));
    for (Fd fd : many.fds) fd.noPrivate = true;
    refused("more than 4,096 Font DICTs are refused", "too many Font DICTs", many.font(1000));
    Builder huge = new Builder(false).cid(17);
    huge.fdSelect = bytes(3, 0, 1, 0, 0, 0, 0, 1);
    huge.glyphs.add(cs("endchar"));
    List<byte[]> empty = new ArrayList<>(java.util.Collections.nCopies(65535, new byte[0]));
    for (Fd fd : huge.fds) fd.subrs = empty;
    refused("more than 1,048,576 local subroutines in all (17 INDEXes of 65,535) are refused", "too many subroutines", huge.font(1000));
    b = new Builder(false);
    b.glyphs.add(cs("endchar"));
    byte[] t = b.build();
    int csAt = indexOfCharStrings(t);
    byte[] t1 = t.clone();
    Sfnt.w16(t1, csAt, 0xFFFF);
    refused("a CharStrings INDEX that claims 65,535 glyphs in a small table is refused", "cut off", otf(t1, false, 1, 1000));
    Builder b2 = new Builder(true);
    b2.glyphs.add(new byte[0]);
    byte[] t2 = b2.build();
    int cs2 = indexOfCharStrings(t2);
    Sfnt.w32(t2, cs2, 0xFFFFFFFF);
    refused("a CFF2 INDEX that claims 4 billion entries is refused", "INDEX that is too long", otf(t2, true, 1, 1000));
    byte[] store = ivs(1, new int[][][] { { { 0, 16384, 16384 } } }, new int[][] { new int[65535] });
    Sfnt.Writer w = new Sfnt.Writer();                             // 5 data sets that all point at one list of 65,535 region references
    w.u16(1).u32(8 + 4 * 5).u16(5);
    for (int i = 0; i < 5; i++) w.u32(8 + 4 * 5 + 10);
    w.bytes(store, 12, store.length - 12);
    Builder v = cff2Varied();
    v.vstore = w.toByteArray();
    refused("a variation store whose data sets name more than 262,144 regions in all is refused", "too big", v.font(1000));
    v = cff2Varied();
    v.vstore = ivs(1, new int[][][] { { { 0, 16384, 16384 } } }, new int[][] { { 1 } });
    refused("a variation store naming a region it does not have is refused", "names a region it does not have", v.font(1000));
  }

  /** .notdef and {@code glyphs} glyphs that each call subroutine 0, which calls subroutine 1 twice and so on: the 10th (ops hstems) runs 512 times. */
  static Sfnt fanOut(int glyphs, int ops) {
    Builder b = new Builder(false);
    for (int i = 0; i < 9; i++) b.main.subrs.add(cs(i + 1 - 107, "callsubr", i + 1 - 107, "callsubr", "return"));
    byte[] leaf = new byte[ops + 1];
    Arrays.fill(leaf, (byte) 1);
    leaf[ops] = 11;
    b.main.subrs.add(leaf);
    b.glyphs.add(cs("endchar"));
    for (int g = 0; g < glyphs; g++) b.glyphs.add(cs(-107, "callsubr", "endchar"));
    return b.font(1000);
  }

  /** Where a built table's CharStrings INDEX starts (read back from its Top DICT). */
  static int indexOfCharStrings(byte[] t) throws Sfnt.FontException {
    boolean cff2 = t[0] == 2;
    int start, end;
    if (cff2) { start = t[2]; end = start + Sfnt.u16(t, 3); }
    else {
      CffToGlyf.Index names = CffToGlyf.readIndex(t, t[2], false);
      CffToGlyf.Index tops = CffToGlyf.readIndex(t, names.next, false);
      start = tops.start(0);
      end = tops.end(0);
    }
    return (int) CffToGlyf.readDict(t, start, end, cff2, null).get(CffToGlyf.CHARSTRINGS)[0];
  }

  // ---------- tables ----------

  static void tables() throws Exception {
    Sfnt square = simple(cs(SQUARE, "endchar"));
    Sfnt none = square.copy();
    none.remove("CFF ");
    throwsFont("a font with no CFF or CFF2 table is refused", "not_cff", "no PostScript (CFF) outlines", () -> CffToGlyf.convert(none, null, null));
    for (String tag : new String[] { "head", "hhea", "maxp", "hmtx" }) {
      Sfnt f = square.copy();
      f.remove(tag);
      throwsFont("a font without " + tag + " is refused", "missing_table", "missing one of the tables", () -> CffToGlyf.convert(f, null, null));
    }
    Sfnt shortMaxp = square.copy();
    shortMaxp.put("maxp", new byte[4]);
    throwsFont("a maxp table too short for a glyph count is refused", "missing_table", null, () -> CffToGlyf.convert(shortMaxp, null, null));
    // both CFF and CFF2: CFF2 is used, as FreeType and fontTools use it
    Builder b2 = new Builder(true);
    b2.glyphs.add(new byte[0]);
    b2.glyphs.add(cs(0, 0, "rmoveto", 300, 0, "rlineto", 0, 300, "rlineto"));
    Sfnt both = square.copy();
    both.put("CFF2", b2.build());
    Sfnt out = CffToGlyf.convert(both, null, null);
    check("a font with both CFF and CFF2: the CFF2 outlines are converted and both tables dropped",
        bounds(Glyf.decode(Glyf.readAll(out)[1])).equals("0,0,300,300") && !out.has("CFF ") && !out.has("CFF2"));
    // headers and INDEXes
    Builder b = squareFont();
    b.major = 2;
    refused("a CFF table of version 2 is refused", "unknown version (2)", b.font(1000));
    b2 = new Builder(true);
    b2.glyphs.add(new byte[0]);
    b2.major = 1;
    refused("a CFF2 table of version 1 is refused", "CFF2 table has an unknown version (1)", b2.font(1000));
    b = squareFont();
    b.hdrSize = 3;
    refused("a CFF header of 3 bytes is refused", "damaged header", b.font(1000));
    b2 = new Builder(true);
    b2.glyphs.add(new byte[0]);
    b2.hdrSize = 4;
    refused("a CFF2 header of 4 bytes is refused", "damaged header", b2.font(1000));
    Sfnt.Writer w = new Sfnt.Writer();
    w.u8(1).u8(0).u8(4).u8(1).bytes(index(Arrays.asList(new byte[] { 'F' }), false)).u16(0).u16(0).u16(0);
    refused("a CFF table with no Top DICT is refused", "has no font in it", otf(w.toByteArray(), false, 1, 1000));
    byte[] t = squareFont().build();
    int names = 4;                                                   // the Name INDEX: count, offSize, offsets 1 and 14
    byte[] d1 = t.clone();
    d1[names + 2] = 5;
    refused("an INDEX with offsets of 5 bytes is refused", "damaged INDEX", otf(d1, false, 2, 1000));
    byte[] d2 = t.clone();
    d2[names + 3] = 2;
    refused("an INDEX whose first offset is not 1 is refused", "damaged INDEX", otf(d2, false, 2, 1000));
    byte[] d3 = t.clone();
    d3[names + 4] = (byte) 255;
    refused("an INDEX whose data runs past the table is refused", "cut off", otf(d3, false, 2, 1000));
    // the Top DICT
    b = squareFont();
    b.top.put(CffToGlyf.CHARSTRING_TYPE, 1);
    refused("Type 1 charstrings inside CFF are refused", "Type 1 charstrings", b.font(1000));
    b = squareFont();
    b.charStrings = false;
    refused("a CFF table without CharStrings is refused", "has no glyphs", b.font(1000));
    b = new Builder(false);
    refused("a CFF table with an empty CharStrings INDEX is refused", "has no glyphs", otf(b.build(), false, 1, 1000));
    for (Object[] dmg : new Object[][] { { "a reserved byte (255)", bytes(255) }, { "a real number with a bad nibble", bytes(30, 0x1D) },
        { "a number cut off", bytes(247) }, { "an escape cut off", bytes(12) }, { "49 operands", cs(times(49, 0)) }, { "a 16-bit number cut off", bytes(28, 1) } }) {
      b = squareFont();
      b.topTail = (byte[]) dmg[1];
      refused("a Top DICT with " + dmg[0] + " is refused", "damaged DICT", b.font(1000));
    }
    b = squareFont();
    b.main.noPrivate = true;
    b.top.put(CffToGlyf.PRIVATE, 10, 1e6);
    refused("a Private DICT past the end of the table is refused", "points outside itself", b.font(1000));
    b = squareFont();
    b.main.noPrivate = true;
    b.top.put(CffToGlyf.PRIVATE, 1e5, 10);
    refused("a Private DICT longer than the table is refused", "points outside itself", b.font(1000));
    b = squareFont();
    b.main.priv.put(CffToGlyf.SUBRS, 1e6);
    refused("Subrs past the end of the table are refused", "points outside itself", b.font(1000));
    b = squareFont();
    b.top.put(CffToGlyf.CHARSTRINGS, 1.5);
    b.charStrings = false;
    refused("an offset that is not a whole number is refused", "points outside itself", b.font(1000));
    // CID-keyed fonts: Font DICTs and FDSelect
    b = cidFont(1);
    b.fdSelect = null;
    check("a CID-keyed font with one Font DICT and no FDSelect converts", converted(b.font(1000), null, 1) != null);
    b = cidFont(2);
    b.fdSelect = null;
    refused("a CID-keyed font with two Font DICTs and no FDSelect is refused", "no FDSelect", b.font(1000));
    b = squareFont();
    b.top.put(CffToGlyf.ROS, 391, 392, 0);
    refused("a CID-keyed font without an FDArray is refused", "no Font DICTs", b.font(1000));
    b = cidFont(1);
    b.fds.clear();
    refused("a CID-keyed font with an empty FDArray is refused", "no Font DICTs", b.font(1000));
    Object[][] sel = {
      { "an FDSelect (format 0) naming Font DICT 2 of 2", bytes(0, 0, 2), "names a Font DICT it does not have" },
      { "an FDSelect (format 3) whose first range does not start at glyph 0", bytes(3, 0, 1, 0, 1, 0, 0, 2), "out of order" },
      { "an FDSelect (format 3) whose ranges go backwards", bytes(3, 0, 2, 0, 0, 0, 0, 2, 1, 0, 1), "out of order" },
      { "an FDSelect (format 3) naming Font DICT 5 of 2", bytes(3, 0, 1, 0, 0, 5, 0, 2), "names a Font DICT it does not have" },
      { "an FDSelect (format 3) with no ranges", bytes(3, 0, 0, 0, 2), "is empty" },
      { "an FDSelect (format 3) of 65,535 ranges in a small table", bytes(3, 0xFF, 0xFF, 0, 0, 0), "cut off" },
      { "an FDSelect of format 4 in CFF 1", bytes(4, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2), "unknown format" },
      { "an FDSelect of format 5", bytes(5, 0, 0), "unknown format" },
    };
    for (Object[] s : sel) {
      b = cidFont(2);
      b.fdSelect = (byte[]) s[1];
      refused(s[0] + " is refused", (String) s[2], b.font(1000));
    }
    b = cidFont(2);
    b.fdSelect = bytes(3, 0, 1, 0, 0, 1, 0, 1);                   // covers glyph 0 only: glyph 1 uses the first Font DICT
    check("glyphs past FDSelect's last range use the first Font DICT", converted(b.font(1000), null, 1) != null);
    Builder c2 = new Builder(true);
    c2.glyphs.add(new byte[0]);
    c2.glyphs.add(cs(SQUARE));
    c2.fdSelect = bytes(4, 0, 0, 0, 1, 0, 0, 0, 0, 0, 3, 0, 0, 0, 2);
    refused("a CFF2 FDSelect (format 4) naming Font DICT 3 of 1 is refused", "names a Font DICT it does not have", c2.font(1000));
    Builder p2 = new Builder(true);
    p2.glyphs.add(new byte[0]);
    p2.fds.get(0).priv.raw(bytes(140, 141, 140, 23));              // 1 2 1 blend
    refused("a CFF2 Private DICT that blends without a variation store is refused", "damaged DICT", p2.font(1000));
    // post when the font has none: from the Top DICT
    b = squareFont();
    b.top.put(CffToGlyf.ITALIC_ANGLE, -12.5).put(CffToGlyf.UNDERLINE_POSITION, -150).put(CffToGlyf.UNDERLINE_THICKNESS, 60).put(CffToGlyf.IS_FIXED_PITCH, 1);
    Sfnt f = b.font(1000);
    f.remove("post");
    byte[] post = CffToGlyf.convert(f, null, null).table("post");
    check("no post table: post 3 from the Top DICT (angle -12.5, underline top -120 = centre -150 + 60 / 2, thickness 60, fixed pitch)",
        Sfnt.u32(post, 0) == 0x30000 && Sfnt.s32(post, 4) == -819200 && Sfnt.s16(post, 8) == -120 && Sfnt.s16(post, 10) == 60 && Sfnt.u32(post, 12) == 1);
    f = squareFont().font(1000);
    f.remove("post");
    post = CffToGlyf.convert(f, null, null).table("post");
    check("no post table and no values: the CFF defaults (angle 0, underline -100 + 50 / 2, thickness 50, proportional)",
        Sfnt.s32(post, 4) == 0 && Sfnt.s16(post, 8) == -75 && Sfnt.s16(post, 10) == 50 && Sfnt.u32(post, 12) == 0);
    f = squareFont().font(1000);
    f.put("post", new Sfnt.Writer().u32(0x00020000).u32(0xFFFA0000L).s16(-80).s16(40).u32(0).zeros(16).u16(2).u16(0).u16(0).toByteArray());
    post = CffToGlyf.convert(f, null, null).table("post");
    check("a post 2 table becomes post 3 with its own angle, underline and pitch (glyph names dropped)",
        post.length == 32 && Sfnt.u32(post, 0) == 0x30000 && Sfnt.s32(post, 4) == 0xFFFA0000 && Sfnt.s16(post, 8) == -80 && Sfnt.s16(post, 10) == 40);
    // the converted font's metrics
    b = new Builder(false);
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(-40, 10, "rmoveto", 100, 0, "rlineto", 0, 100, "rlineto", "endchar"));
    out = CffToGlyf.convert(b.font(1000), null, null);
    Glyf.Metrics m = Glyf.Metrics.read(out);
    byte[] hhea = out.table("hhea");
    check("bearings become xMin (-40) and hhea's minimum left bearing follows", m.lsb[1] == -40 && Sfnt.s16(hhea, 12) == -40 && m.advance[1] == 500,
        "lsb " + m.lsb[1] + ", hhea " + Sfnt.s16(hhea, 12));
  }

  static Builder squareFont() {
    Builder b = new Builder(false);
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(SQUARE, "endchar"));
    return b;
  }

  static Builder cidFont(int fds) {
    Builder b = new Builder(false).cid(fds);
    b.charset = bytes(2, 0, 1, 0, 0);
    b.fdSelect = bytes(3, 0, 1, 0, 0, 0, 0, 2);
    b.glyphs.add(cs("endchar"));
    b.glyphs.add(cs(SQUARE, "endchar"));
    return b;
  }
}
