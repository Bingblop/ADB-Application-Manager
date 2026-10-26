package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * FontInstancer, the variable font instancer of the Custom Fonts engine. Against tests/java/fixtures/fonts/fontinstancer: subsets of Inter,
 * Noto Sans, Roboto Flex, Source Sans 3 (renamed) and Roboto (hinted), two hand-made fonts (every gvar / cvar / MVAR / HVAR case; avar 2) and
 * the cfftoglyf CFF2 subset, each instanced at the locations its .expect.txt names and compared with what make_fixtures.py recorded from
 * fontTools' instantiateVariableFont at HarfBuzz's normalized coordinates (with HarfBuzz's advances, which come from HVAR where fontTools
 * takes gvar's phantom points): the coordinates themselves, every glyph's points (CRC-32), advance, bearing and bounds, all the points of
 * sample glyphs, the OS/2 / hhea / post / head / maxp values, the tables kept, the cvt, kerning and mark attachment of sample glyphs and
 * the glyphs FeatureVariations swap in. The CFF2 font is compared with its outlines drawn
 * at the location, as CffToGlyfTest compares them. Hand-built gvar and MVAR data cover the encodings and edge cases; the location helpers,
 * options, warnings, errors, progress and Cancel are checked here; a seeded fuzz runs the instancer on damaged variation tables.
 *
 * <p>{@code --dump DIR} writes every fixture instance for {@code make_fixtures.py verify DIR} (HarfBuzz and FreeType checks);
 * {@code --fuzz SEED COUNT} runs only the fuzz, longer or with another seed.
 */
public class FontInstancerTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  static void check(String name, boolean ok, String detail) { check(ok || detail == null ? name : name + " -- " + detail, ok); }

  interface Body { void run() throws Exception; }

  /** Passes when {@code body} ends in a FontException with this code (null: any) whose message contains {@code text} (null: any) and wraps nothing. */
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

  static void section(String name, Body body) {
    try {
      body.run();
    } catch (Throwable e) {
      check(name + ": ran to the end", false, e.toString() + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : ""));
    }
  }

  /** The expectation files, one per fixture font (make_fixtures.py expect). */
  static final String[] FIXTURES = { "Inter-var", "NotoSans-var", "RobotoFlex-var", "FixtureSans3-var", "Roboto-hinted", "synth-gvar",
      "synth-avar2", "cff2-ss3vf" };

  public static void main(String[] args) throws Exception {
    final File fix = fixtureDir();
    if (args.length == 2 && args[0].equals("--dump")) {
      if (fix == null) throw new IOException("fixtures not found");
      dump(fix, new File(args[1]));
      return;
    }
    if (args.length == 3 && args[0].equals("--fuzz")) {
      fuzz(fix, Long.parseLong(args[1], 16), Integer.parseInt(args[2]), 0);
      System.exit(fails == 0 ? 0 : 1);
    }
    if (fix == null || !new File(fix, "synth-gvar.expect.txt").isFile()) {
      check("the fixtures are found (tests/java/fixtures/fonts/fontinstancer)", false);
    } else {
      final int[] totals = new int[4];
      for (final String name : FIXTURES) section(name, () -> fixture(fix, name, totals));
      check(String.format("all fixtures: %d glyphs at %d locations compared with fontTools' instances (HVAR's advances), %d with other points, %d with another advance",
          totals[0], totals[1], totals[2], totals[3]), totals[0] >= 4700 && totals[1] >= 58 && totals[2] == 0 && totals[3] == 0);
      section("locations", () -> locations(fix));
      section("options", () -> options(fix));
      section("no gvar", () -> noGvar(fix));
      section("hand-built gvar", () -> handGvar(fix));
      section("hand-built MVAR", () -> handMvar(fix));
      section("tables and warnings", () -> tablesAndWarnings(fix));
      section("errors", () -> errors(fix));
      section("progress and cancel", () -> progressAndCancel(fix));
      section("fuzz", () -> fuzz(fix, 0x10262026L, 3000, 60));
    }
    section("tuple encodings", FontInstancerTest::encodings);
    section("width classes", FontInstancerTest::widthClasses);
    section("avar segment maps", FontInstancerTest::avarMaps);
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  // ---------- fixtures and their .expect.txt ----------

  static File fixtureDir() {
    String p = System.getProperty("fixtures");
    if (p != null && new File(p, "fonts/fontinstancer").isDirectory()) return new File(p, "fonts/fontinstancer");
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts/fontinstancer");
    return null;
  }

  static Sfnt font(File fix, String name) throws Exception {
    return Sfnt.parse(Files.readAllBytes(new File(fix, name).toPath()));
  }

  /** One location of an .expect.txt: the lines between "loc" and "end" by kind. */
  static final class Loc {
    String spec;
    final LinkedHashMap<String, Double> user = new LinkedHashMap<>();
    final Map<String, List<String>> lines = new HashMap<>();

    List<String> all(String kind) { List<String> l = lines.get(kind); return l == null ? new ArrayList<String>() : l; }
    String one(String kind) { List<String> l = lines.get(kind); return l == null ? null : l.get(0); }
  }

  static final class Expect {
    String font, sha;
    int glyphs;
    String[] metricNames;
    final List<Loc> locs = new ArrayList<>();

    static Expect read(File f) throws IOException {
      Expect e = new Expect();
      Loc cur = null;
      for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
        if (l.isEmpty() || l.startsWith("#")) continue;
        int sp = l.indexOf(' ');
        String kind = sp < 0 ? l : l.substring(0, sp), rest = sp < 0 ? "" : l.substring(sp + 1);
        if (kind.equals("font")) {
          String[] w = rest.split(" ");
          e.font = w[0]; e.sha = w[1]; e.glyphs = Integer.parseInt(w[2]);
        } else if (kind.equals("MF")) {
          e.metricNames = rest.split(",");
        } else if (kind.equals("loc")) {
          cur = new Loc();
          String[] w = rest.split(" ");
          cur.spec = w[0];
          for (String kv : w[1].split(",")) cur.user.put(kv.substring(0, kv.indexOf('=')), Double.parseDouble(kv.substring(kv.indexOf('=') + 1)));
          e.locs.add(cur);
        } else if (kind.equals("end")) {
          cur = null;
        } else if (cur != null) {
          List<String> list = cur.lines.get(kind);
          if (list == null) cur.lines.put(kind, list = new ArrayList<>());
          list.add(rest);
        } else {
          throw new IOException(f.getName() + ": unknown line " + l);
        }
      }
      return e;
    }
  }

  static int[] ints(String s) {
    if (s == null || s.equals("-") || s.isEmpty()) return new int[0];
    String[] w = s.split(",");
    int[] out = new int[w.length];
    for (int i = 0; i < w.length; i++) out[i] = Integer.parseInt(w[i].trim());
    return out;
  }

  static String sha256(byte[] b) throws Exception {
    StringBuilder s = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x & 0xFF));
    return s.toString();
  }

  /** The user coordinates of a location as the page would send them: a named instance by number, everything else as tag -> value. */
  static double[] userFor(VarStore.Fvar fv, Loc l) throws Sfnt.FontException {
    if (l.spec.startsWith("#")) return FontInstancer.userCoords(fv, Integer.parseInt(l.spec.substring(1)));
    return FontInstancer.userCoords(fv, l.user);
  }

  static Sfnt instanceAt(Sfnt vf, Loc l, boolean hinting) throws Sfnt.FontException {
    FontInstancer.Options o = new FontInstancer.Options();
    o.keepHinting = hinting;
    return FontInstancer.instance(vf, userFor(VarStore.Fvar.read(vf), l), o);
  }

  static String fileOf(Expect ex) { return ex.font; }

  static void fixture(File fix, String name, int[] totals) throws Exception {
    Expect ex = Expect.read(new File(fix, name + ".expect.txt"));
    byte[] data = Files.readAllBytes(new File(fix, ex.font).toPath());
    check(name + ": " + ex.font + " is the font the expectations were made from", sha256(data).equals(ex.sha),
        "run make_fixtures.py expect again after changing a fixture");
    Sfnt vf = Sfnt.parse(data);
    VarStore.Fvar fv = VarStore.Fvar.read(vf);
    boolean cff2 = vf.has("CFF2");
    int ftDiffers = 0;
    for (Loc l : ex.locs) {
      String id = name + " " + l.spec;
      double[] user = userFor(fv, l);
      double[] want = new double[fv.axes.size()];
      for (int i = 0; i < want.length; i++) want[i] = l.user.get(fv.axes.get(i).tag);
      check(id + ": the location is " + l.user, Arrays.equals(user, want), Arrays.toString(user));
      int[] norm = FontInstancer.normalize(vf, fv, user), hbNorm = ints(l.one("norm")), ftNorm = ints(l.one("ftnorm"));
      check(id + ": normalized as HarfBuzz normalizes it " + Arrays.toString(hbNorm), Arrays.equals(norm, hbNorm), Arrays.toString(norm));
      if (!Arrays.equals(hbNorm, ftNorm)) ftDiffers++;
      Sfnt out = instanceAt(vf, l, true);
      Sfnt back = Sfnt.parse(out.toBytes());
      if (cff2) cff2Glyphs(id, back, l, totals);
      else glyfGlyphs(id, back, l, totals);
      metrics(id, back, ex.metricNames, l.one("M"), cff2);
      tableSet(id, back, l.one("T"));
      if (l.one("V") != null) {
        byte[] cvt = back.table("cvt ");
        int[] want2 = ints(l.one("V")), got = new int[cvt == null ? 0 : cvt.length / 2];
        for (int i = 0; i < got.length; i++) got[i] = Sfnt.s16(cvt, 2 * i);
        check(id + ": the cvt " + (vf.has("cvar") ? "(with cvar's deltas)" : "(no cvar: as it was)") + " is fontTools'", Arrays.equals(got, want2),
            Arrays.toString(got));
      }
      if (l.one("KG") != null) layout(id, back, l);
      if (!l.all("S").isEmpty()) {
        OtLayout.Table gsub = OtLayout.parse(back.table("GSUB"), true);
        List<Integer> rvrn = rvrnLookups(gsub);
        Map<Integer, Integer> cmap = Cmap.read(back);
        int bad = 0;
        for (String s : l.all("S")) {
          int[] v = ints(s.replace(' ', ','));
          if (singleSubst(gsub, rvrn, cmap.get(v[0])) != v[1]) bad++;
        }
        check(id + ": FeatureVariations swap in fontTools' (and HarfBuzz's) glyphs for " + l.all("S").size() + " characters", bad == 0, bad + " differ");
      }
      totals[1]++;
    }
    System.out.println("     " + name + ": fontTools' own normalization differs from HarfBuzz's (by 1 unit) at " + ftDiffers + " of " + ex.locs.size()
        + " locations; the expectations use HarfBuzz's, which the app uses");
  }

  /** The text whose CRC-32 the .expect.txt keeps (make_fixtures.py glyph_key builds the same). */
  static String glyphKey(byte[] raw) throws Sfnt.FontException {
    if (raw == null || raw.length < 10) return "";
    Glyf.Glyph g = Glyf.decode(raw);
    StringBuilder s = new StringBuilder();
    if (g.isComposite()) {
      for (Glyf.Component k : g.components) s.append(k.xy ? 'c' : 'm').append(k.glyph).append(',').append(k.dx).append(',').append(k.dy).append(';');
      return s.toString();
    }
    if (g.contours <= 0) return "";
    for (int i = 0; i < g.x.length; i++) s.append(g.x[i]).append(',').append(g.y[i]).append(',').append(g.on[i] ? 1 : 0).append(';');
    s.append('|');
    for (int i = 0; i < g.endPts.length; i++) s.append(i == 0 ? "" : ",").append(g.endPts[i]);
    return s.toString();
  }

  static long crc(String s) {
    CRC32 c = new CRC32();
    c.update(s.getBytes(StandardCharsets.UTF_8));
    return c.getValue();
  }

  static void glyfGlyphs(String id, Sfnt out, Loc l, int[] totals) throws Exception {
    byte[][] glyphs = Glyf.readAll(out);
    Glyf.Metrics m = Glyf.Metrics.read(out);
    int n = 0, pts = 0, adv = 0, lsb = 0, box = 0, advMax = 0, lsbMax = 0, boxMax = 0, simpleOff = 0;
    for (String s : l.all("g")) {
      String[] w = s.split(" ");
      int g = Integer.parseInt(w[0]);
      n++;
      boolean composite = glyphs[g].length >= 10 && Sfnt.s16(glyphs[g], 0) < 0;
      if (!String.format("%08x", crc(glyphKey(glyphs[g]))).equals(w[7])) pts++;
      int da = Math.abs(m.advance[g] - Integer.parseInt(w[1])), dl = Math.abs(m.lsb[g] - Integer.parseInt(w[2]));
      int[] bb = Glyf.headerBounds(glyphs[g]);
      if (bb == null) bb = new int[4];
      int db = 0;
      for (int k = 0; k < 4; k++) db = Math.max(db, Math.abs(bb[k] - Integer.parseInt(w[3 + k])));
      if (da > 0) adv++;
      if (dl > 0) lsb++;
      if (db > 0) box++;
      if (!composite && (dl > 0 || db > 0)) simpleOff++;
      advMax = Math.max(advMax, da); lsbMax = Math.max(lsbMax, dl); boxMax = Math.max(boxMax, db);
    }
    int sampleMax = 0, samples = 0;
    for (String s : l.all("P")) {
      String[] w = s.split(" ");
      Glyf.Glyph g = Glyf.decode(glyphs[Integer.parseInt(w[0])]);
      String[] p = w[2].split(";");
      samples++;
      if (p.length != g.x.length || !Arrays.equals(ints(w[1]), g.endPts)) { sampleMax = Integer.MAX_VALUE; continue; }
      for (int i = 0; i < p.length; i++) {
        int[] v = ints(p[i]);
        sampleMax = Math.max(sampleMax, Math.max(Math.abs(v[0] - g.x[i]), Math.abs(v[1] - g.y[i])));
        if ((v[2] == 1) != g.on[i]) sampleMax = Integer.MAX_VALUE;
      }
    }
    // A composite whose offsets move by a fraction: fontTools takes its bounds and bearing from the offsets before rounding (see FontInstancer.outlines)
    check(String.format("%s: %d glyphs; points of %d differ from fontTools', advances of %d (max %d), bearings of %d (max %d), bounds of %d (max %d); "
        + "%d sample glyphs within %d", id, n, pts, adv, advMax, lsb, lsbMax, box, boxMax, samples, sampleMax),
        n == out.numGlyphs() && pts == 0 && advMax == 0 && lsbMax <= 1 && boxMax <= 1 && simpleOff == 0 && sampleMax == 0);
    totals[0] += n;
    totals[2] += pts;
    totals[3] += adv;
  }

  /** CFF2 at a location: CffToGlyfTest's criteria against the variable font drawn there (contours, on-curve points, bounds, area), advances exact. */
  static void cff2Glyphs(String id, Sfnt out, Loc l, int[] totals) throws Exception {
    byte[][] glyphs = Glyf.readAll(out);
    Glyf.Metrics m = Glyf.Metrics.read(out);
    int n = 0, bad = 0, adv = 0, lsb = 0;
    List<String> why = new ArrayList<>();
    for (String kind : new String[] { "c", "e" })
      for (String s : l.all(kind)) {
        String[] w = s.split(" ", 9);
        int g = Integer.parseInt(w[0]);
        n++;
        if (m.advance[g] != Integer.parseInt(w[1])) adv++;
        Glyf.Glyph gl = Glyf.decode(glyphs[g]);
        if (kind.equals("e")) {
          if (gl.contours > 0) { bad++; why.add(g + ": not empty"); }
          continue;
        }
        String r = cff2Glyph(gl, w);
        if (r != null) { bad++; if (why.size() < 3) why.add(g + ": " + r); }
        if (gl.contours > 0 && m.lsb[g] != gl.xMin) lsb++;
      }
    check(String.format("%s: %d glyphs; %d differ from the variable font drawn at the location (contours, on-curve points within 1, bounds within 1, "
        + "area within 0.5 %%), %d advances differ from fontTools' (HVAR), %d bearings are not xMin", id, n, bad, adv, lsb),
        n == out.numGlyphs() && bad == 0 && adv == 0 && lsb == 0, why.toString());
    totals[0] += n;
    totals[2] += bad;
    totals[3] += adv;
  }

  static String cff2Glyph(Glyf.Glyph g, String[] w) {
    int contours = Integer.parseInt(w[2]);
    if (g.contours != contours) return "contours " + g.contours + " vs " + contours;
    int[] want = { Integer.parseInt(w[3]), Integer.parseInt(w[4]), Integer.parseInt(w[5]), Integer.parseInt(w[6]) };
    int[] got = { g.xMin, g.yMin, g.xMax, g.yMax };
    for (int k = 0; k < 4; k++) if (Math.abs(got[k] - want[k]) > 1) return "bounds " + Arrays.toString(got) + " vs " + Arrays.toString(want);
    double a = area(g), wa = Double.parseDouble(w[7]);
    if (Math.abs(a - wa) > Math.max(0.005 * Math.abs(wa), 2)) return "area " + a + " vs " + wa;
    String[] cs = w[8].split(" / ");
    List<List<int[]>> on = oncurve(g);
    for (int c = 0; c < cs.length; c++) {
      String[] p = cs[c].trim().isEmpty() ? new String[0] : cs[c].trim().split(" ");
      if (p.length != on.get(c).size()) return "contour " + c + ": " + on.get(c).size() + " on-curve points vs " + p.length;
      for (int i = 0; i < p.length; i++) {
        int[] v = ints(p[i]);
        if (Math.abs(v[0] - on.get(c).get(i)[0]) > 1 || Math.abs(v[1] - on.get(c).get(i)[1]) > 1) return "contour " + c + " point " + i;
      }
    }
    return null;
  }

  /** Signed area of a TrueType outline (implied on-curve points included), as make_fixtures.py area. */
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
      List<double[]> f = new ArrayList<>(full.subList(k, full.size()));
      f.addAll(full.subList(0, k));
      int m = f.size();
      for (int i = 0; i < m;) {
        double[] p0 = f.get(i), p1 = f.get((i + 1) % m);
        if (p1[2] == 1) { total += cross(p0, p1) / 2; i++; }
        else { double[] p2 = f.get((i + 2) % m); total += (cross(p0, p1) + cross(p1, p2)) / 3 + cross(p0, p2) / 6; i += 2; }
      }
    }
    return total;
  }

  static double cross(double[] u, double[] v) { return u[0] * v[1] - v[0] * u[1]; }

  static List<List<int[]>> oncurve(Glyf.Glyph g) {
    List<List<int[]>> out = new ArrayList<>();
    int s = 0;
    for (int e : g.endPts) {
      List<int[]> d = new ArrayList<>();
      for (int i = s; i <= e; i++) {
        int[] p = { g.x[i], g.y[i], g.on[i] ? 1 : 0 };
        if (i == s || !Arrays.equals(p, d.get(d.size() - 1))) d.add(p);
      }
      while (d.size() > 1 && Arrays.equals(d.get(d.size() - 1), d.get(0))) d.remove(d.size() - 1);
      List<int[]> on = new ArrayList<>();
      for (int[] p : d) if (p[2] == 1) on.add(p);
      out.add(on);
      s = e + 1;
    }
    return out;
  }

  // ---------- metrics and tables ----------

  /** A metric of the font by fontTools' name ("OS/2.sxHeight"), or null when the font has no such field. */
  static Long metric(Sfnt f, String name) throws Sfnt.FontException {
    String tag = name.substring(0, name.indexOf('.')), field = name.substring(name.indexOf('.') + 1);
    byte[] t = f.table(tag);
    if (t == null) return null;
    Object[][] fields = {
        { "OS/2", "xAvgCharWidth", 2, 's' }, { "OS/2", "usWeightClass", 4, 'u' }, { "OS/2", "usWidthClass", 6, 'u' },
        { "OS/2", "ySubscriptXSize", 10, 's' }, { "OS/2", "ySubscriptYSize", 12, 's' }, { "OS/2", "ySubscriptXOffset", 14, 's' },
        { "OS/2", "ySubscriptYOffset", 16, 's' }, { "OS/2", "ySuperscriptXSize", 18, 's' }, { "OS/2", "ySuperscriptYSize", 20, 's' },
        { "OS/2", "ySuperscriptXOffset", 22, 's' }, { "OS/2", "ySuperscriptYOffset", 24, 's' }, { "OS/2", "yStrikeoutSize", 26, 's' },
        { "OS/2", "yStrikeoutPosition", 28, 's' }, { "OS/2", "sTypoAscender", 68, 's' }, { "OS/2", "sTypoDescender", 70, 's' },
        { "OS/2", "sTypoLineGap", 72, 's' }, { "OS/2", "usWinAscent", 74, 'u' }, { "OS/2", "usWinDescent", 76, 'u' },
        { "OS/2", "sxHeight", 86, 'S' }, { "OS/2", "sCapHeight", 88, 'S' },
        { "hhea", "ascent", 4, 's' }, { "hhea", "descent", 6, 's' }, { "hhea", "lineGap", 8, 's' }, { "hhea", "advanceWidthMax", 10, 'u' },
        { "hhea", "minLeftSideBearing", 12, 's' }, { "hhea", "minRightSideBearing", 14, 's' }, { "hhea", "xMaxExtent", 16, 's' },
        { "hhea", "caretSlopeRise", 18, 's' }, { "hhea", "caretSlopeRun", 20, 's' }, { "hhea", "caretOffset", 22, 's' },
        { "hhea", "numberOfHMetrics", 34, 'u' },
        { "post", "italicAngle", 4, 'l' }, { "post", "underlinePosition", 8, 's' }, { "post", "underlineThickness", 10, 's' },
        { "head", "xMin", 36, 's' }, { "head", "yMin", 38, 's' }, { "head", "xMax", 40, 's' }, { "head", "yMax", 42, 's' },
        { "maxp", "numGlyphs", 4, 'u' }, { "maxp", "maxPoints", 6, 'm' }, { "maxp", "maxContours", 8, 'm' }, { "maxp", "maxCompositePoints", 10, 'm' },
        { "maxp", "maxCompositeContours", 12, 'm' }, { "maxp", "maxComponentElements", 28, 'm' }, { "maxp", "maxComponentDepth", 30, 'm' } };
    for (Object[] d : fields) {
      if (!d[0].equals(tag) || !d[1].equals(field)) continue;
      int off = (Integer) d[2];
      char k = (Character) d[3];
      if (k == 'S' && Sfnt.u16(t, 0) < 2) return null;                          // OS/2 before version 2 has no x height or cap height
      if (k == 'm' && Sfnt.u32(t, 0) != 0x00010000L) return null;               // maxp 0.5 (CFF) has only the glyph count
      if (k == 'l') return (long) Sfnt.s32(t, off);
      return (long) (k == 'u' ? Sfnt.u16(t, off) : Sfnt.s16(t, off));
    }
    throw new IllegalArgumentException("unknown metric " + name);
  }

  /** Values computed from the glyph bounds: a composite's may be 1 unit off fontTools' (see glyfGlyphs); a CFF2 font's are its converted outlines'. */
  static final List<String> FROM_BOUNDS = Arrays.asList("head.xMin", "head.yMin", "head.xMax", "head.yMax", "hhea.minLeftSideBearing",
      "hhea.minRightSideBearing", "hhea.xMaxExtent");

  static void metrics(String id, Sfnt out, String[] names, String line, boolean cff2) throws Sfnt.FontException {
    String[] want = line.split(" ");
    List<String> bad = new ArrayList<>();
    int compared = 0;
    for (int i = 0; i < names.length; i++) {
      Long got = metric(out, names[i]);
      if (want[i].equals("-")) continue;                                       // fontTools' instance has no such field (maxp 0.5 of CFF)
      if (cff2 && FROM_BOUNDS.contains(names[i])) continue;
      compared++;
      long w = Long.parseLong(want[i]);
      boolean ok = got != null && (FROM_BOUNDS.contains(names[i]) ? Math.abs(got - w) <= 1 : got == w);
      if (!ok) bad.add(names[i] + " " + got + " vs " + w);
    }
    check(id + ": " + compared + " OS/2, hhea, post, head and maxp values are fontTools' (MVAR, weight and width classes, slant, average width, bounds)",
        bad.isEmpty(), bad.toString());
  }

  /** Tables fontTools keeps in a full instance and the app drops: STAT (it describes the axes), DSIG and device metrics, vertical metrics. */
  static final List<String> DROPPED_TOO = Arrays.asList("STAT", "DSIG", "hdmx", "LTSH", "VDMX", "vhea", "vmtx");

  static void tableSet(String id, Sfnt out, String line) {
    TreeSet<String> ft = new TreeSet<>(Arrays.asList(line.split(","))), got = new TreeSet<>(out.tags());
    TreeSet<String> extra = new TreeSet<>(got), missing = new TreeSet<>(ft);
    extra.removeAll(ft);
    missing.removeAll(got);
    boolean cff = ft.contains("CFF2") || ft.contains("CFF ");
    if (cff) {                                                                // fontTools keeps CFF2; the app converts it to glyf
      missing.remove("CFF2"); missing.remove("CFF ");
      extra.remove("glyf"); extra.remove("loca");
    }
    missing.removeAll(DROPPED_TOO);
    check(id + ": the tables are fontTools' " + ft + " but STAT, DSIG and device / vertical metrics" + (cff ? ", in glyf" : ""),
        extra.isEmpty() && missing.isEmpty(), "extra " + extra + ", missing " + missing);
  }

  // ---------- GPOS / GSUB evaluators (make_fixtures.py checked its own against HarfBuzz) ----------

  static List<Integer> featureLookups(OtLayout.Table m, String feature) {
    OtLayout.Script sc = m.script("latn");
    if (sc == null) sc = m.script("DFLT");
    TreeSet<Integer> out = new TreeSet<>();
    if (sc == null || sc.dflt == null) return new ArrayList<>(out);
    for (int fi : sc.dflt.features) {
      OtLayout.Feature f = m.features.get(fi);
      if (f.tag.equals(feature)) out.addAll(f.lookups);
    }
    return new ArrayList<>(out);
  }

  static List<Integer> rvrnLookups(OtLayout.Table m) {
    TreeSet<Integer> out = new TreeSet<>();
    for (OtLayout.Feature f : m.features) if (f.tag.equals("rvrn")) out.addAll(f.lookups);
    return new ArrayList<>(out);
  }

  static int coverage(byte[] t, int off, int g) throws Sfnt.FontException {
    int fmt = Sfnt.u16(t, off), n = Sfnt.u16(t, off + 2);
    for (int i = 0; i < n; i++) {
      if (fmt == 1 && Sfnt.u16(t, off + 4 + 2 * i) == g) return i;
      int r = off + 4 + 6 * i;
      if (fmt == 2 && g >= Sfnt.u16(t, r) && g <= Sfnt.u16(t, r + 2)) return Sfnt.u16(t, r + 4) + g - Sfnt.u16(t, r);
    }
    return -1;
  }

  static int classOf(byte[] t, int off, int g) throws Sfnt.FontException {
    int fmt = Sfnt.u16(t, off);
    if (fmt == 1) {
      int start = Sfnt.u16(t, off + 2), n = Sfnt.u16(t, off + 4);
      return g >= start && g < start + n ? Sfnt.u16(t, off + 6 + 2 * (g - start)) : 0;
    }
    int n = Sfnt.u16(t, off + 2);
    for (int i = 0; i < n; i++) {
      int r = off + 4 + 6 * i;
      if (g >= Sfnt.u16(t, r) && g <= Sfnt.u16(t, r + 2)) return Sfnt.u16(t, r + 4);
    }
    return 0;
  }

  /** The XAdvance of a ValueRecord with format {@code vf} at {@code rec} (0 when it has none). */
  static int xAdvance(byte[] t, int rec, int vf) throws Sfnt.FontException {
    return (vf & 4) == 0 ? 0 : Sfnt.s16(t, rec + 2 * Integer.bitCount(vf & 3));
  }

  /** {first, second} glyph's x advance adjustment of a two-glyph run (as make_fixtures.py kern). */
  static int[] kern(OtLayout.Table m, List<Integer> lookups, int left, int right) throws Sfnt.FontException {
    int v1 = 0, v2 = 0;
    for (int li : lookups) {
      OtLayout.Lookup l = m.lookups.get(li);
      if (l.type != 2) continue;
      for (OtLayout.Subtable st : l.subtables) {
        byte[] t = m.sources.get(st.source);
        int so = st.offset, fmt = Sfnt.u16(t, so);
        int ci = coverage(t, so + Sfnt.u16(t, so + 2), left);
        if (ci < 0) continue;
        int vf1 = Sfnt.u16(t, so + 4), vf2 = Sfnt.u16(t, so + 6), s1 = 2 * Integer.bitCount(vf1), s2 = 2 * Integer.bitCount(vf2);
        int hit = -1;
        if (fmt == 1) {
          int ps = so + Sfnt.u16(t, so + 10 + 2 * ci), pc = Sfnt.u16(t, ps), rec = 2 + s1 + s2;
          for (int k = 0; k < pc && hit < 0; k++) if (Sfnt.u16(t, ps + 2 + k * rec) == right) hit = ps + 4 + k * rec;
        } else {
          int c1 = classOf(t, so + Sfnt.u16(t, so + 8), left), c2 = classOf(t, so + Sfnt.u16(t, so + 10), right);
          int n1 = Sfnt.u16(t, so + 12), n2 = Sfnt.u16(t, so + 14);
          if (c2 != 0 && c1 < n1 && c2 < n2) hit = so + 16 + (c1 * n2 + c2) * (s1 + s2);
        }
        if (hit < 0) continue;
        v1 += xAdvance(t, hit, vf1);
        v2 += xAdvance(t, hit + s1, vf2);
        break;
      }
    }
    return new int[] { v1, v2 };
  }

  /** The mark's offset from the base (base anchor - mark anchor) through MarkBasePos, or null. */
  static int[] attach(OtLayout.Table m, List<Integer> lookups, int base, int mark) throws Sfnt.FontException {
    for (int li : lookups) {
      OtLayout.Lookup l = m.lookups.get(li);
      if (l.type != 4) continue;
      for (OtLayout.Subtable st : l.subtables) {
        byte[] t = m.sources.get(st.source);
        int so = st.offset;
        int mi = coverage(t, so + Sfnt.u16(t, so + 2), mark), bi = coverage(t, so + Sfnt.u16(t, so + 4), base);
        if (mi < 0 || bi < 0) continue;
        int classes = Sfnt.u16(t, so + 6), ma = so + Sfnt.u16(t, so + 8), ba = so + Sfnt.u16(t, so + 10);
        int cls = Sfnt.u16(t, ma + 2 + 4 * mi), mo = Sfnt.u16(t, ma + 4 + 4 * mi);
        if (cls >= classes) continue;
        int bo = Sfnt.u16(t, ba + 2 + 2 * (bi * classes + cls));
        if (bo == 0) continue;
        return new int[] { Sfnt.s16(t, ba + bo + 2) - Sfnt.s16(t, ma + mo + 2), Sfnt.s16(t, ba + bo + 4) - Sfnt.s16(t, ma + mo + 4) };
      }
    }
    return null;
  }

  /** The glyph after the SingleSubst lookups, each lookup's first subtable that covers it. */
  static int singleSubst(OtLayout.Table m, List<Integer> lookups, int g) throws Sfnt.FontException {
    for (int li : lookups) {
      OtLayout.Lookup l = m.lookups.get(li);
      if (l.type != 1) continue;
      for (OtLayout.Subtable st : l.subtables) {
        byte[] t = m.sources.get(st.source);
        int so = st.offset, ci = coverage(t, so + Sfnt.u16(t, so + 2), g);
        if (ci < 0) continue;
        g = Sfnt.u16(t, so) == 1 ? (g + Sfnt.s16(t, so + 4)) & 0xFFFF : Sfnt.u16(t, so + 6 + 2 * ci);
        break;
      }
    }
    return g;
  }

  static long pair(int a, int b) { return (long) a << 16 | b; }

  static void layout(String id, Sfnt out, Loc l) throws Sfnt.FontException {
    OtLayout.Table gpos = OtLayout.parse(out.table("GPOS"), false);
    List<Integer> kl = featureLookups(gpos, "kern"), ml = featureLookups(gpos, "mark");
    Map<Long, int[]> want = new HashMap<>();
    for (String s : l.all("K")) { int[] v = ints(s.replace(' ', ',')); want.put(pair(v[0], v[1]), new int[] { v[2], v[3] }); }
    int[] letters = ints(l.one("KG"));
    int bad = 0, nonzero = 0;
    for (int a : letters)
      for (int b : letters) {
        int[] w = want.containsKey(pair(a, b)) ? want.get(pair(a, b)) : new int[2], got = kern(gpos, kl, a, b);
        if (w[0] != 0 || w[1] != 0) nonzero++;
        if (!Arrays.equals(w, got)) bad++;
      }
    check(id + ": kerning of " + letters.length * letters.length + " letter pairs (" + nonzero + " kerned) is fontTools'", bad == 0, bad + " differ");
    Map<Long, int[]> anchors = new HashMap<>();
    for (String s : l.all("A")) { int[] v = ints(s.replace(' ', ',')); anchors.put(pair(v[0], v[1]), new int[] { v[2], v[3] }); }
    int[] bases = ints(l.one("AG")), marks = ints(l.one("AM"));
    bad = 0;
    for (int b : bases)
      for (int mk : marks) {
        int[] got = attach(gpos, ml, b, mk), w = anchors.get(pair(b, mk));
        if (!(w == null ? got == null : Arrays.equals(w, got))) bad++;
      }
    check(id + ": mark attachment of " + bases.length * marks.length + " base + mark pairs (" + anchors.size() + " attached) is fontTools'", bad == 0,
        bad + " differ");
  }

  // ---------- the location helpers ----------

  static void locations(File fix) throws Exception {
    Sfnt inter = font(fix, "Inter-var.ttf"), flex = font(fix, "RobotoFlex-var.ttf"), avar2 = font(fix, "synth-avar2.ttf");
    VarStore.Fvar fi = VarStore.Fvar.read(inter), ff = VarStore.Fvar.read(flex), fa = VarStore.Fvar.read(avar2);
    check("defaults: every axis at its default, in fvar order", Arrays.equals(FontInstancer.defaults(fi), new double[] { 14, 400 }));
    boolean all = true;
    for (int i = 0; i < ff.instances.size(); i++) all &= Arrays.equals(FontInstancer.userCoords(ff, i), ff.instances.get(i).coords);
    check("userCoords(index): each of Roboto Flex's " + ff.instances.size() + " named instances", all);
    throwsFont("userCoords(index): a style the font does not have is refused", "no_instance", "style number 21", () -> FontInstancer.userCoords(ff, 20));
    throwsFont("userCoords(index): a negative index too", "no_instance", null, () -> FontInstancer.userCoords(ff, -1));

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("wght", 650); m.put("ital", 1); m.put("XXXX", 5.5);
    check("userCoords(map): unknown tags are ignored, missing axes stay at their default", Arrays.equals(userCoords(fi, m), new double[] { 14, 650 }));
    m.clear(); m.put("wght", 5000); m.put("opsz", -3.0);
    check("userCoords(map): values are clamped to the axes", Arrays.equals(userCoords(fi, m), new double[] { 14, 900 }));
    m.clear(); m.put("wght", Double.NaN); m.put("opsz", Double.POSITIVE_INFINITY);
    check("userCoords(map): NaN and infinite values give the default", Arrays.equals(userCoords(fi, m), new double[] { 14, 400 }));
    m.clear(); m.put("wght ", 300); m.put(null, 1); m.put("opsz", null);
    check("userCoords(map): a tag with stray spaces still finds its axis; null keys and values are skipped",
        Arrays.equals(userCoords(fi, m), new double[] { 14, 300 }));
    check("userCoords(map): null is the default location", Arrays.equals(FontInstancer.userCoords(fi, (Map<String, Number>) null), new double[] { 14, 400 }));
    check("complete: a short array is filled with defaults", Arrays.equals(FontInstancer.complete(fi, new double[] { 20 }), new double[] { 20, 400 }));
    check("complete: null is the default location", Arrays.equals(FontInstancer.complete(fi, null), new double[] { 14, 400 }));
    LinkedHashMap<String, Double> loc = FontInstancer.location(fi, new double[] { 32, 100 });
    check("location: tag -> value in fvar order", loc.toString().equals("{opsz=32.0, wght=100.0}"), loc.toString());

    check("guessRegular: Inter's Regular instance (wght 400, opsz 14)", Arrays.equals(FontInstancer.guessRegular(fi), new double[] { 14, 400 }));
    check("guessBold: Inter's Bold instance (wght 700, opsz 14)", Arrays.equals(FontInstancer.guessBold(fi), new double[] { 14, 700 }));
    check("guessBold: Roboto Flex's Bold instance", Arrays.equals(FontInstancer.guessBold(ff), ff.instances.get(6).coords));
    check("guessRegular: with only a Black instance, the weight axis itself (wght 400, wdth at its default)",
        Arrays.equals(FontInstancer.guessRegular(fa), new double[] { 400, 100 }));
    check("guessBold: the weight axis at 700 when no instance is as close", Arrays.equals(FontInstancer.guessBold(fa), new double[] { 700, 100 }));
    Sfnt noWght = avar2.copy();
    byte[] fvar = noWght.table("fvar").clone();
    int axesAt = Sfnt.u16(fvar, 4);
    fvar[axesAt] = 'W'; fvar[axesAt + 1] = 'G'; fvar[axesAt + 2] = 'H'; fvar[axesAt + 3] = 'T';
    noWght.put("fvar", fvar);
    VarStore.Fvar fn = VarStore.Fvar.read(noWght);
    check("guessBold: null for a font without a weight axis", FontInstancer.guessBold(fn) == null);
    check("guessRegular: the defaults for a font without a weight axis", Arrays.equals(FontInstancer.guessRegular(fn), FontInstancer.defaults(fn)));

    // avar 2 (synth-avar2): the normalized width also follows the weight; HarfBuzz's coordinates, checked at each location of the fixture
    int[] n = FontInstancer.normalize(avar2, fa, new double[] { 900, 100 });
    check("normalize: avar 2 moves wdth with wght (wght 900 -> wdth -0.25)", Arrays.equals(n, new int[] { 16384, -4096 }), Arrays.toString(n));
    check("normalize: the default location stays at 0 with avar 2", Arrays.equals(FontInstancer.normalize(avar2, fa, null), new int[2]));
  }

  @SuppressWarnings("unchecked")
  static double[] userCoords(VarStore.Fvar fv, Map<String, Object> m) {
    Map<String, Number> n = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : m.entrySet()) n.put(e.getKey(), (Number) e.getValue());
    return FontInstancer.userCoords(fv, n);
  }

  // ---------- options ----------

  static boolean overlapMarked(byte[][] glyphs) throws Sfnt.FontException {
    for (byte[] raw : glyphs) {
      if (raw.length < 10) continue;
      Glyf.Glyph g = Glyf.decode(raw);
      if (g.isComposite() ? (g.components.get(0).flags & Glyf.OVERLAP_COMPOUND) == 0 : g.contours > 0 && !g.overlap) return false;
    }
    return true;
  }

  static boolean noneMarked(byte[][] glyphs) throws Sfnt.FontException {
    for (byte[] raw : glyphs) {
      if (raw.length < 10) continue;
      Glyf.Glyph g = Glyf.decode(raw);
      if (g.isComposite() ? (g.components.get(0).flags & Glyf.OVERLAP_COMPOUND) != 0 : g.overlap) return false;
    }
    return true;
  }

  static void options(File fix) throws Exception {
    Sfnt roboto = font(fix, "Roboto-hinted.ttf");
    Map<String, Number> bold = new LinkedHashMap<>();
    bold.put("wght", 700);
    FontInstancer.Options o = new FontInstancer.Options();
    Sfnt plain = FontInstancer.instance(roboto, bold, o);
    o = new FontInstancer.Options();
    o.keepHinting = true;
    Sfnt hinted = FontInstancer.instance(roboto, bold, o);
    check("hinting dropped by default: no fpgm, prep or cvt", !plain.has("fpgm") && !plain.has("prep") && !plain.has("cvt "));
    byte[][] pg = Glyf.readAll(plain), hg = Glyf.readAll(hinted), src = Glyf.readAll(roboto);
    int instructed = 0, kept = 0, emptied = 0, same = 0;
    for (int g = 0; g < src.length; g++) {
      if (src[g].length < 10) continue;
      Glyf.Glyph s = Glyf.decode(src[g]), p = Glyf.decode(pg[g]), h = Glyf.decode(hg[g]);
      if (s.instructions.length > 0) instructed++;
      if (Arrays.equals(s.instructions, h.instructions)) kept++;
      if (p.instructions.length == 0) emptied++;
      if (glyphKey(pg[g]).equals(glyphKey(hg[g]))) same++;
    }
    int nonEmpty = 0;
    for (byte[] raw : src) if (raw.length >= 10) nonEmpty++;
    check("hinting dropped: every glyph's instructions are gone (" + instructed + " had some), the outlines are the same", emptied == nonEmpty
        && same == nonEmpty && instructed > 50);
    byte[] maxp = plain.table("maxp");
    boolean zeros = true;
    for (int off = 16; off <= 26; off += 2) zeros &= Sfnt.u16(maxp, off) == 0;
    check("hinting dropped: maxp says there are no functions, storage, twilight points, stack or instructions; zones as they were", zeros
        && Sfnt.u16(maxp, 14) == Sfnt.u16(roboto.table("maxp"), 14));
    check("keepHinting: fpgm, prep and cvt kept, every glyph's instructions as they were", hinted.has("fpgm") && hinted.has("prep") && hinted.has("cvt ")
        && kept == nonEmpty && Arrays.equals(hinted.table("fpgm"), roboto.table("fpgm")));
    check("keepHinting: maxp's instruction size is at least the source's", Sfnt.u16(hinted.table("maxp"), 26) >= Sfnt.u16(roboto.table("maxp"), 26));

    check("overlap flags by default: every simple glyph's first point and every composite's first component", overlapMarked(pg));
    o = new FontInstancer.Options();
    o.overlapFlags = false;
    check("overlapFlags off: no glyph is marked", noneMarked(Glyf.readAll(FontInstancer.instance(roboto, bold, o))));
    Sfnt cff2 = font(fix, "../cfftoglyf/ss3vf-subset.otf");
    check("overlap flags on a converted CFF2 font too", overlapMarked(Glyf.readAll(FontInstancer.instance(cff2, bold, new FontInstancer.Options()))));
    o = new FontInstancer.Options();
    o.overlapFlags = false;
    check("overlapFlags off on a converted CFF2 font", noneMarked(Glyf.readAll(FontInstancer.instance(cff2, bold, o))));

    Sfnt synth = font(fix, "synth-gvar.ttf");
    o = new FontInstancer.Options();
    o.keepHinting = true;
    Sfnt sh = FontInstancer.instance(synth, bold, o);
    Glyf.Glyph f = Glyf.decode(Glyf.readAll(sh)[7]);
    check("keepHinting: a glyph's own instructions are kept (synth F)", Arrays.equals(f.instructions, new byte[] { (byte) 0xB0, 1, 0x21, (byte) 0xB0, 2, 0x21 }));
    check("null options are the defaults", FontInstancer.instance(synth, bold, null).has("glyf") && !FontInstancer.instance(synth, bold, null).has("fpgm"));
  }

  // ---------- a glyf font without gvar: HVAR gives the advances ----------

  static void noGvar(File fix) throws Exception {
    Sfnt synth = font(fix, "synth-gvar.ttf");
    Expect ex = Expect.read(new File(fix, "synth-gvar.expect.txt"));
    Sfnt vf = synth.copy();
    vf.remove("gvar");
    VarStore.Fvar fv = VarStore.Fvar.read(vf);
    int bad = 0, n = 0;
    for (Loc l : ex.locs) {
      Sfnt out = FontInstancer.instance(vf, userFor(fv, l), null);
      Glyf.Metrics m = Glyf.Metrics.read(out);
      int[] want = ints(l.one("H"));
      n++;
      if (!Arrays.equals(m.advance, want)) bad++;
      byte[][] a = Glyf.readAll(out), b = Glyf.readAll(vf);
      for (int g = 0; g < a.length; g++) if (!glyphKey(a[g]).equals(glyphKey(b[g]))) bad++;
    }
    check("no gvar: the outlines stay and HVAR (with its advance map) gives HarfBuzz's advances at " + n + " locations", bad == 0, bad + " differ");
  }

  // ---------- hand-built gvar ----------

  /** A gvar table: long offsets, the shared tuples given, one GlyphVariationData (or null) per glyph. */
  static byte[] gvarTable(int axisCount, int[][] shared, byte[][] data) {
    int n = data.length, sharedAt = 20 + 4 * (n + 1), dataAt = sharedAt + 2 * axisCount * shared.length;
    Sfnt.Writer w = new Sfnt.Writer();
    w.u16(1).u16(0).u16(axisCount).u16(shared.length).u32(sharedAt).u16(n).u16(1).u32(dataAt);
    int off = 0;
    for (byte[] d : data) { w.u32(off); off += d == null ? 0 : d.length; }
    w.u32(off);
    for (int[] t : shared) for (int v : t) w.u16(v);
    for (byte[] d : data) if (d != null) w.bytes(d);
    return w.toByteArray();
  }

  /** GlyphVariationData from tuple headers and their serialized data, with shared point numbers when {@code sharedPoints} != null. */
  static byte[] glyphData(int[] sharedPoints, int[][] headers, int[][] datas) {
    Sfnt.Writer w = new Sfnt.Writer();
    int headerBytes = 0;
    for (int[] h : headers) headerBytes += 2 * h.length;
    w.u16(headers.length | (sharedPoints != null ? 0x8000 : 0)).u16(4 + headerBytes);
    for (int[] h : headers) for (int v : h) w.u16(v);
    if (sharedPoints != null) for (int b : sharedPoints) w.u8(b);
    for (int[] d : datas) for (int b : d) w.u8(b);
    return w.toByteArray();
  }

  /** synth-gvar with glyph E's (gid 6) variations replaced; the other glyphs vary not at all. Without HVAR, so the phantom points give the advances. */
  static Sfnt withE(Sfnt synth, int[][] shared, byte[] e) {
    Sfnt f = synth.copy();
    byte[][] data = new byte[f.numGlyphs()][];
    data[6] = e;
    f.put("gvar", gvarTable(4, shared, data));
    f.remove("HVAR");
    return f;
  }

  /** Glyph E's points {x..., y...}, advance and bearing in the instance at {@code wght} (other axes at their defaults). */
  static int[] eAt(Sfnt f, double wght) throws Sfnt.FontException {
    Sfnt out = FontInstancer.instance(f, new double[] { wght }, null);
    Glyf.Glyph g = Glyf.decode(Glyf.readAll(out)[6]);
    Glyf.Metrics m = Glyf.Metrics.read(out);
    int[] r = new int[10];
    for (int i = 0; i < 4; i++) { r[i] = g.x[i]; r[4 + i] = g.y[i]; }
    r[8] = m.advance[6];
    r[9] = m.lsb[6];
    return r;
  }

  static int[] bytes16(int... v) {
    int[] out = new int[2 * v.length];
    for (int i = 0; i < v.length; i++) { out[2 * i] = (v[i] >> 8) & 0xFF; out[2 * i + 1] = v[i] & 0xFF; }
    return out;
  }

  static int[] cat(int[]... parts) {
    int n = 0;
    for (int[] p : parts) n += p.length;
    int[] out = new int[n];
    int at = 0;
    for (int[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
    return out;
  }

  static void handGvar(File fix) throws Exception {
    final Sfnt synth = font(fix, "synth-gvar.ttf");
    // E: (60,-10) (60,650) (420,650) (420,-10), advance 480, bearing 60
    int[] still = eAt(withE(synth, new int[0][], null), 900);
    check("hand gvar: without variations E stays as it is", Arrays.equals(still, new int[] { 60, 60, 420, 420, -10, 650, 650, -10, 480, 60 }),
        Arrays.toString(still));

    // Private points 0 and 2 (16-bit point numbers), 16-bit x deltas 10 and 300, y deltas a run of zeros; the rest inferred
    int[] pts = { 0x02, 0x81, 0x00, 0x00, 0x00, 0x02 }, dx = cat(new int[] { 0x41 }, bytes16(10, 300)), dy = { 0x81 };
    int[] h = cat(new int[] { pts.length + dx.length + dy.length, 0xA000 }, new int[] { 16384, 0, 0, 0 });
    int[] got = eAt(withE(synth, new int[0][], glyphData(null, new int[][] { h }, new int[][] { cat(pts, dx, dy) })), 900);
    check("hand gvar: private 16-bit point numbers, 16-bit deltas and a zero run; unlisted points take the nearer listed point's delta",
        Arrays.equals(got, new int[] { 70, 70, 720, 720, -10, 650, 650, -10, 480, 70 }), Arrays.toString(got));

    // Shared points with a two-byte count (0, 1, 2), 32-bit x deltas, 8-bit y deltas, an intermediate region giving 0.5 at wght 900
    int[] sp = { 0x80, 0x03, 0x02, 0, 1, 1 };
    int[] d2 = cat(new int[] { 0xC2 }, new int[] { 0, 0, 0, 40, 0xFF, 0xFF, 0xFF, 0xEC, 0, 0, 0, 7 }, new int[] { 0x02, 8, 0xF8, 3 });
    int[] h2 = cat(new int[] { d2.length, 0xC000 }, new int[] { 8192, 0, 0, 0 }, new int[] { 0, 0, 0, 0 }, new int[] { 24576, 0, 0, 0 });
    got = eAt(withE(synth, new int[0][], glyphData(sp, new int[][] { h2 }, new int[][] { d2 })), 900);
    check("hand gvar: shared points (two-byte count), 32-bit and 8-bit deltas, an intermediate region (0.5): halves rounded up, the fourth point inferred",
        Arrays.equals(got, new int[] { 80, 50, 424, 424, -6, 646, 652, -6, 480, 50 }), Arrays.toString(got));

    // Shared tuples by index, "all points" (count 0) with phantoms, and a tuple whose region is off at this location
    int[] all = { 0x00 }, xs = { 0x07, 1, 2, 3, 4, 0, 6, 0, 0 }, ys = { 0x87 };
    int[] ha = { all.length + xs.length + ys.length, 0x2000 }, hb = { all.length + xs.length + ys.length, 0x2001 };
    byte[] e3 = glyphData(null, new int[][] { ha, hb }, new int[][] { cat(all, xs, ys), cat(all, xs, ys) });
    got = eAt(withE(synth, new int[][] { { 16384, 0, 0, 0 }, { 0, -16384, 0, 0 } }, e3), 900);
    check("hand gvar: shared tuples, every point listed (phantoms too: advance + 6), a tuple outside its region skipped",
        Arrays.equals(got, new int[] { 61, 62, 423, 424, -10, 650, 650, -10, 486, 61 }), Arrays.toString(got));
    // The same with synth-gvar's HVAR kept: HVAR gives the advance (480 + 11 at wght 900), as HarfBuzz and FreeType take it, and the
    // phantom points' + 6 is not used; the bearing still comes from the left phantom point
    Sfnt hv = withE(synth, new int[][] { { 16384, 0, 0, 0 }, { 0, -16384, 0, 0 } }, e3);
    hv.put("HVAR", synth.table("HVAR"));
    got = eAt(hv, 900);
    check("hand gvar: with HVAR the advance is HVAR's, not the phantom points' (HarfBuzz's and FreeType's choice); the bearing is the phantom points'",
        Arrays.equals(got, new int[] { 61, 62, 423, 424, -10, 650, 650, -10, 491, 61 }), Arrays.toString(got));
    // An HVAR they cannot read (its variation store of format 2, its advance map of format 7) HarfBuzz and FreeType leave out: the phantom
    // points give the advance again (+ 6), and the font is made
    byte[] h0 = synth.table("HVAR");
    int storeAt = (int) Sfnt.u32(h0, 4), mapAt = (int) Sfnt.u32(h0, 8);
    byte[] storeFmt = h0.clone(), mapFmt = h0.clone();
    Sfnt.w16(storeFmt, storeAt, 2);
    mapFmt[mapAt] = 7;
    for (byte[] bad : new byte[][] { storeFmt, mapFmt }) {
      Sfnt dh = withE(synth, new int[][] { { 16384, 0, 0, 0 }, { 0, -16384, 0, 0 } }, e3);
      dh.put("HVAR", bad);
      String fails = null;
      try { got = eAt(dh, 900); } catch (Sfnt.FontException x) { got = null; fails = x.code + ": " + x.getMessage(); }
      check("hand gvar: an HVAR with " + (bad == storeFmt ? "a variation store of format 2" : "an advance map of format 7") + " is left out, the advance is "
          + "the phantom points' (as HarfBuzz and FreeType take it)", got != null && Arrays.equals(got, new int[] { 61, 62, 423, 424, -10, 650, 650, -10, 486, 61 }),
          fails != null ? fails : Arrays.toString(got));
    }

    // A point listed twice (the last delta counts) and one the glyph does not have (ignored); one listed point moves its whole contour
    int[] p4 = { 0x03, 0x02, 0, 0, 100 }, x4 = { 0x02, 5, 9, 77 }, y4 = { 0x82 };
    int[] h4 = cat(new int[] { p4.length + x4.length + y4.length, 0xA000 }, new int[] { 16384, 0, 0, 0 });
    got = eAt(withE(synth, new int[0][], glyphData(null, new int[][] { h4 }, new int[][] { cat(p4, x4, y4) })), 900);
    check("hand gvar: a repeated point takes the last delta, a point past the glyph is ignored, a single listed point moves its contour",
        Arrays.equals(got, new int[] { 69, 69, 429, 429, -10, 650, 650, -10, 480, 69 }), Arrays.toString(got));
    got = eAt(withE(synth, new int[0][], glyphData(null, new int[][] { h4 }, new int[][] { cat(p4, x4, y4) })), 400);
    check("hand gvar: at the default location nothing moves", Arrays.equals(got, still), Arrays.toString(got));

    // Damaged data ends in a FontException
    final int[] hBad = cat(new int[] { 5, 0xA000 }, new int[] { 16384, 0, 0, 0 });
    throwsFont("hand gvar: a point run longer than its count is refused", "bad_font", "damaged",
        () -> eAt(withE(synth, new int[0][], glyphData(null, new int[][] { hBad }, new int[][] { { 0x02, 0x02, 0, 1, 1 } })), 900));
    final int[] hBad2 = cat(new int[] { 9, 0xA000 }, new int[] { 16384, 0, 0, 0 });
    throwsFont("hand gvar: a delta run longer than its count is refused", "bad_font", "damaged",
        () -> eAt(withE(synth, new int[0][], glyphData(null, new int[][] { hBad2 }, new int[][] { { 0x02, 0x01, 0, 2, 0x02, 1, 2, 3, 0x81 } })), 900));
    final int[] hBad3 = cat(new int[] { 200, 0xA000 }, new int[] { 16384, 0, 0, 0 });
    throwsFont("hand gvar: tuple data past the glyph's variation data is refused", "bad_font", "damaged",
        () -> eAt(withE(synth, new int[0][], glyphData(null, new int[][] { hBad3 }, new int[][] { cat(p4, x4, y4) })), 900));
    throwsFont("hand gvar: a shared tuple index past the shared tuples is refused", "bad_font", "damaged",
        () -> eAt(withE(synth, new int[][] { { 16384, 0, 0, 0 } }, glyphData(null, new int[][] { { 9, 0x2003 } }, new int[][] { cat(all, xs, ys) })), 900));
    throwsFont("hand gvar: a tuple header past the data is refused", "bad_font", "damaged",
        () -> eAt(withE(synth, new int[0][], new byte[] { 0, 1, 0, 4, 0, 9 }), 900));
    final Sfnt wrongAxes = synth.copy();
    byte[][] none = new byte[synth.numGlyphs()][];
    wrongAxes.put("gvar", gvarTable(3, new int[0][], none));
    throwsFont("hand gvar: an axis count that is not fvar's is refused", "bad_font", "damaged", () -> FontInstancer.instance(wrongAxes, (double[]) null, null));
    final Sfnt v2 = synth.copy();
    byte[] g2 = gvarTable(4, new int[0][], none);
    g2[1] = 2;
    v2.put("gvar", g2);
    throwsFont("hand gvar: another version is refused", "bad_font", "version", () -> FontInstancer.instance(v2, (double[]) null, null));
    final Sfnt shortG = synth.copy();
    shortG.put("gvar", new byte[12]);
    throwsFont("hand gvar: a truncated header is refused", "bad_font", "damaged", () -> FontInstancer.instance(shortG, (double[]) null, null));
    Sfnt fewer = synth.copy();
    fewer.put("gvar", gvarTable(4, new int[0][], new byte[3][]));
    check("hand gvar: a gvar with fewer glyphs than the font leaves the rest unmoved", Glyf.readAll(FontInstancer.instance(fewer, new double[] { 900 }, null)).length
        == synth.numGlyphs());
  }

  /** The static helpers of the tuple code on their own: packed points, packed deltas, inferred deltas. */
  static void encodings() throws Exception {
    byte[] t = { 0x00, (byte) 0x82, 0x01, 0x00, 0x05, 0x03, 0x02, 0x01, 0x01 };
    int[] pos = { 0 };
    check("points: count 0 is every point", FontInstancer.points(t, pos, t.length, "x") == null && pos[0] == 1);
    t = new byte[] { 0x04, (byte) 0x81, 0x01, 0x00, 0x00, 0x05, 0x01, 0x03, 0x02 };
    pos[0] = 0;
    int[] p = FontInstancer.points(t, pos, t.length, "x");
    check("points: a 16-bit run then an 8-bit run, each number the previous plus the value", Arrays.equals(p, new int[] { 256, 261, 264, 266 }) && pos[0] == 9,
        Arrays.toString(p));
    t = new byte[] { (byte) 0x80, 0x02, 0x01, 0x07, 0x07 };
    pos[0] = 0;
    p = FontInstancer.points(t, pos, t.length, "x");
    check("points: a two-byte count", Arrays.equals(p, new int[] { 7, 14 }), Arrays.toString(p));
    final byte[] over = { 0x02, 0x05, 1, 1, 1, 1, 1, 1 };
    throwsFont("points: a run past the count is refused", "bad_font", "damaged", () -> FontInstancer.points(over, new int[1], over.length, "the table"));
    final byte[] cut = { 0x03, 0x02, 1, 1 };
    throwsFont("points: a run past the end is refused", "bad_font", "damaged", () -> FontInstancer.points(cut, new int[1], cut.length, "the table"));
    final byte[] noCount = { (byte) 0x80 };
    throwsFont("points: a two-byte count cut off is refused", "bad_font", "damaged", () -> FontInstancer.points(noCount, new int[1], 1, "the table"));

    t = new byte[] { 0x01, (byte) 0xFF, 0x7F, (byte) 0x81, 0x40, (byte) 0x80, 0x00, (byte) 0xC0, 0x00, 0x01, 0x00, 0x00 };
    int[] d = new int[6];
    pos[0] = 0;
    FontInstancer.deltas(t, pos, t.length, 6, d, "x");
    check("deltas: 8-bit (signed), zeros, 16-bit and 32-bit runs", Arrays.equals(d, new int[] { -1, 127, 0, 0, -32768, 65536 }) && pos[0] == t.length,
        Arrays.toString(d));
    final byte[] dOver = { 0x03, 1, 2, 3, 4 };
    throwsFont("deltas: a run past the count is refused", "bad_font", "damaged", () -> FontInstancer.deltas(dOver, new int[1], dOver.length, 2, new int[2], "t"));
    final byte[] dCut = { 0x41, 0x00 };
    throwsFont("deltas: a run past the end is refused", "bad_font", "damaged", () -> FontInstancer.deltas(dCut, new int[1], dCut.length, 2, new int[2], "t"));

    // Inferred deltas, against fontTools' iup_contour on the same numbers (worked out by hand)
    int[] c = { 0, 100, 200, 300, 200, 100 };
    boolean[] has = { true, false, false, true, false, false };
    double[] dd = { 10, 0, 0, 40, 0, 0 };
    FontInstancer.iup(c, has, dd, 0, 5);
    check("iup: between two listed points the delta is interpolated, on both sides of the contour",
        Arrays.equals(dd, new double[] { 10, 20, 30, 40, 30, 20 }), Arrays.toString(dd));
    c = new int[] { 50, 0, 200, 50 };
    has = new boolean[] { true, false, false, true };
    dd = new double[] { 5, 0, 0, 9 };
    FontInstancer.iup(c, has, dd, 0, 3);
    check("iup: outside the listed points' range the nearer one's delta; equal coordinates with different deltas give 0",
        Arrays.equals(dd, new double[] { 5, 0, 0, 9 }), Arrays.toString(dd));
    c = new int[] { 50, 0, 200, 50 };
    dd = new double[] { 7, 0, 0, 7 };
    FontInstancer.iup(c, has, dd, 0, 3);
    check("iup: equal coordinates with equal deltas give that delta", Arrays.equals(dd, new double[] { 7, 7, 7, 7 }), Arrays.toString(dd));
    has = new boolean[4];
    dd = new double[] { 1, 2, 3, 4 };
    FontInstancer.iup(c, has, dd, 0, 3);
    check("iup: a contour with no listed point does not move", Arrays.equals(dd, new double[4]), Arrays.toString(dd));
  }

  // ---------- hand-built MVAR ----------

  /** An ItemVariationStore with one VarData: regions {start, peak, end} per axis, rows of 16-bit deltas (one per region). */
  static byte[] varStore(int axisCount, int[][][] regions, int[][] rows) {
    Sfnt.Writer w = new Sfnt.Writer();
    w.u16(1).u32(12).u16(1).u32(0);
    w.u16(axisCount).u16(regions.length);
    for (int[][] r : regions) for (int[] a : r) w.u16(a[0]).u16(a[1]).u16(a[2]);
    int dataAt = w.size();
    w.u16(rows.length).u16(regions.length).u16(regions.length);
    for (int i = 0; i < regions.length; i++) w.u16(i);
    for (int[] row : rows) for (int v : row) w.u16(v);
    byte[] b = w.toByteArray();
    Sfnt.w32(b, 8, dataAt);
    return b;
  }

  /** An MVAR table: records (tag, row of the store), the store over regions peaking at wght 1 twice (both full at wght 900). */
  static byte[] mvar(String[] tags, int[][] rows) {
    int[][][] regions = { { { 0, 16384, 16384 }, { 0, 0, 0 }, { 0, 0, 0 }, { 0, 0, 0 } }, { { 0, 16384, 16384 }, { 0, 0, 0 }, { 0, 0, 0 }, { 0, 0, 0 } } };
    byte[] store = varStore(4, regions, rows);
    Sfnt.Writer w = new Sfnt.Writer();
    int storeAt = 12 + 8 * tags.length;
    w.u16(1).u16(0).u16(0).u16(8).u16(tags.length).u16(storeAt);
    for (int i = 0; i < tags.length; i++) w.bytes(tags[i].getBytes(StandardCharsets.US_ASCII)).u16(0).u16(i);
    w.bytes(store);
    return w.toByteArray();
  }

  static void handMvar(File fix) throws Exception {
    Sfnt synth = font(fix, "synth-gvar.ttf");
    Sfnt base = synth.copy();
    base.remove("MVAR");
    double[] heavy = { 900 };
    Sfnt ref = FontInstancer.instance(base, heavy, null);
    Sfnt f = base.copy();
    f.put("MVAR", mvar(new String[] { "hasc", "hcld", "hdsc", "sbxs", "stro", "undo", "unds", "vasc", "xhgt", "zzzz" },
        new int[][] { { 20, 10 }, { -1000, -1000 }, { -15, -5 }, { 30000, 30000 }, { 7, 0 }, { -3, -2 }, { 1, 1 }, { 50, 0 }, { 11, 0 }, { 99, 0 } }));
    Sfnt out = FontInstancer.instance(f, heavy, null);
    byte[] os2 = out.table("OS/2"), hhea = out.table("hhea"), post = out.table("post"), r2 = ref.table("OS/2"), rh = ref.table("hhea"), rp = ref.table("post");
    check("hand MVAR: hasc / hdsc move the typo ascender and descender (both regions full)", Sfnt.s16(os2, 68) == Sfnt.s16(r2, 68) + 30
        && Sfnt.s16(os2, 70) == Sfnt.s16(r2, 70) - 20);
    check("hand MVAR: hhea follows them, as its values were OS/2's", Sfnt.s16(hhea, 4) == Sfnt.s16(os2, 68) && Sfnt.s16(hhea, 6) == Sfnt.s16(os2, 70)
        && Sfnt.s16(hhea, 8) == Sfnt.s16(rh, 8));
    check("hand MVAR: an unsigned value (usWinDescent) is clamped at 0", Sfnt.u16(os2, 76) == 0);
    check("hand MVAR: a signed value is clamped at 32767", Sfnt.s16(os2, 10) == 32767);
    check("hand MVAR: strikeout, underline position and thickness, x height", Sfnt.s16(os2, 28) == Sfnt.s16(r2, 28) + 7
        && Sfnt.s16(post, 8) == Sfnt.s16(rp, 8) - 5 && Sfnt.s16(post, 10) == Sfnt.s16(rp, 10) + 2 && Sfnt.s16(os2, 86) == Sfnt.s16(r2, 86) + 11);
    byte[] a = os2.clone(), b = r2.clone();
    for (int off : new int[] { 10, 28, 68, 70, 76, 86 }) { Sfnt.w16(a, off, 0); Sfnt.w16(b, off, 0); }
    byte[] ha = hhea.clone(), hb = rh.clone();
    for (int off : new int[] { 4, 6 }) { Sfnt.w16(ha, off, 0); Sfnt.w16(hb, off, 0); }
    check("hand MVAR: nothing else moves (a vertical tag without vhea and an unknown tag are left alone)", Arrays.equals(a, b) && Arrays.equals(ha, hb));

    Sfnt unsynced = f.copy();
    byte[] uh = unsynced.table("hhea").clone();
    Sfnt.w16(uh, 4, 777);
    unsynced.put("hhea", uh);
    Sfnt uo = FontInstancer.instance(unsynced, heavy, null);
    check("hand MVAR: hhea keeps its own values when they were not OS/2's", Sfnt.s16(uo.table("hhea"), 4) == 777
        && Sfnt.s16(uo.table("hhea"), 6) == Sfnt.s16(rh, 6));
    Sfnt v1 = f.copy();
    byte[] o1 = v1.table("OS/2").clone();
    Sfnt.w16(o1, 0, 1);
    v1.put("OS/2", o1);
    check("hand MVAR: xhgt is not applied to an OS/2 table before version 2", Sfnt.s16(FontInstancer.instance(v1, heavy, null).table("OS/2"), 86) == Sfnt.s16(o1, 86));
    check("hand MVAR: at the default location nothing moves", Arrays.equals(FontInstancer.instance(f, (double[]) null, null).table("OS/2"),
        FontInstancer.instance(base, (double[]) null, null).table("OS/2")));
    final Sfnt bad = f.copy();
    byte[] m = bad.table("MVAR").clone();
    Sfnt.w16(m, 10, 60000);
    bad.put("MVAR", m);
    throwsFont("hand MVAR: a store offset past the table is refused", "bad_font", null, () -> FontInstancer.instance(bad, heavy, null));
    final Sfnt small = f.copy();
    byte[] ms = small.table("MVAR").clone();
    Sfnt.w16(ms, 6, 4);
    small.put("MVAR", ms);
    throwsFont("hand MVAR: a record size under 8 is refused", "bad_font", "damaged", () -> FontInstancer.instance(small, heavy, null));
  }

  // ---------- tables, the source left alone, warnings ----------

  static void tablesAndWarnings(File fix) throws Exception {
    Sfnt synth = font(fix, "synth-gvar.ttf");
    byte[] before = synth.toBytes();
    Sfnt out = FontInstancer.instance(synth, new double[] { 900, 60, -6, 50 }, null);
    check("the source font is left as it was", Arrays.equals(before, synth.toBytes()));
    boolean shared = false;
    for (String tag : out.tags()) shared |= synth.table(tag) != null && out.table(tag) == synth.table(tag);
    check("the instance shares no table with the source", !shared);
    boolean dropped = false;
    for (String tag : FontInstancer.DROPPED) dropped |= out.has(tag);
    check("no variation table, STAT, DSIG or CFF table is left; TrueType outlines", !dropped && out.sfntVersion == Sfnt.TRUETYPE && out.has("glyf"));
    check("the instance is a well-formed font (written and read back)", Sfnt.parse(out.toBytes()).numGlyphs() == synth.numGlyphs());

    Sfnt cff2 = font(fix, "../cfftoglyf/ss3vf-subset.otf");
    Sfnt co = FontInstancer.instance(cff2, new double[] { 650 }, null);
    check("a CFF2 font becomes a TrueType font (glyf, loca, maxp 1.0, no CFF2)", co.sfntVersion == Sfnt.TRUETYPE && co.has("glyf") && co.has("loca")
        && !co.has("CFF2") && Sfnt.u32(co.table("maxp"), 0) == 0x00010000L);

    FontInstancer.Options o = new FontInstancer.Options();
    FontInstancer.instance(synth, (double[]) null, o);
    check("no warnings for an ordinary font", o.warnings.isEmpty(), o.warnings.toString());
    Sfnt colr = synth.copy();
    byte[] c = new byte[40];
    Sfnt.w16(c, 0, 1);
    Sfnt.w32(c, 30, 34);
    colr.put("COLR", c);
    o = new FontInstancer.Options();
    FontInstancer.instance(colr, (double[]) null, o);
    check("a COLR version 1 table with variations gets the warning", o.warnings.contains(FontInstancer.W_COLOR_VARIES), o.warnings.toString());
    Sfnt.w32(c, 30, 0);
    o = new FontInstancer.Options();
    FontInstancer.instance(colr, (double[]) null, o);
    check("a COLR table without variations does not", o.warnings.isEmpty(), o.warnings.toString());
  }

  // ---------- errors ----------

  static void errors(File fix) throws Exception {
    final Sfnt synth = font(fix, "synth-gvar.ttf");
    final Sfnt flat = synth.copy();
    flat.remove("fvar");
    throwsFont("a font without fvar is refused", "not_variable", "not a variable font", () -> FontInstancer.instance(flat, (double[]) null, null));
    throwsFont("a font without fvar is refused (location by tag)", "not_variable", null,
        () -> FontInstancer.instance(flat, new LinkedHashMap<String, Number>(), null));
    final Sfnt varc = synth.copy();
    varc.put("VARC", new byte[8]);
    throwsFont("variable components (VARC) are refused", "not_supported", "VARC", () -> FontInstancer.instance(varc, (double[]) null, null));
    long saved = FontInstancer.maxTupleWork;
    FontInstancer.maxTupleWork = 40;
    try {
      throwsFont("more variation work than allowed is refused", "too_complex", "too long", () -> FontInstancer.instance(synth, new double[] { 900 }, null));
    } finally {
      FontInstancer.maxTupleWork = saved;
    }
    final Sfnt badCvar = synth.copy();
    byte[] cv = badCvar.table("cvar").clone();
    Sfnt.w16(cv, 6, 60000);
    badCvar.put("cvar", cv);
    final FontInstancer.Options hint = new FontInstancer.Options();
    hint.keepHinting = true;
    throwsFont("a damaged cvar is refused when hinting is kept", "bad_font", "damaged", () -> FontInstancer.instance(badCvar, new double[] { 900 }, hint));
    check("a damaged cvar does not matter when hinting is dropped", FontInstancer.instance(badCvar, new double[] { 900 }, null).has("glyf"));
    final Sfnt badHvar = synth.copy();
    badHvar.remove("gvar");
    byte[] hv = badHvar.table("HVAR").clone();
    Sfnt.w32(hv, 4, 1 << 30);
    badHvar.put("HVAR", hv);
    throwsFont("a damaged HVAR is refused when it gives the advances", "bad_font", "damaged", () -> FontInstancer.instance(badHvar, new double[] { 900 }, null));
    final Sfnt avar2 = font(fix, "synth-avar2.ttf");
    byte[] av = avar2.table("avar").clone();
    int tail = 8;
    for (int i = 0; i < Sfnt.u16(av, 6); i++) tail += 2 + 4 * Sfnt.u16(av, tail);
    Sfnt.w32(av, tail + 4, av.length + 100);     // the variation store's offset, past the table
    avar2.put("avar", av);
    throwsFont("a damaged avar 2 store is refused", "bad_font", null, () -> FontInstancer.instance(avar2, new double[] { 900 }, null));
  }

  // ---------- progress and cancel ----------

  static final class Recorder implements FontProgress {
    final List<String> steps = new ArrayList<>();
    int cancelAfter = Integer.MAX_VALUE;
    @Override public void step(String stage, int done, int total) { steps.add(stage + " " + done + " " + total); }
    @Override public boolean cancelled() { return steps.size() > cancelAfter; }
  }

  static void progressAndCancel(File fix) throws Exception {
    Sfnt noto = font(fix, "NotoSans-var.ttf");
    Recorder r = new Recorder();
    FontInstancer.Options o = new FontInstancer.Options();
    o.progress = r;
    FontInstancer.instance(noto, new double[] { 700, 80 }, o);
    int n = noto.numGlyphs();
    List<String> mine = new ArrayList<>();
    for (String s : r.steps) if (s.startsWith(FontInstancer.STAGE + " ")) mine.add(s);
    boolean rising = true;
    for (int i = 1; i < mine.size(); i++) rising &= Integer.parseInt(mine.get(i).split(" ")[1]) >= Integer.parseInt(mine.get(i - 1).split(" ")[1]);
    check("progress: \"instance\" steps from 0 to " + n + " of " + n + " (" + r.steps.size() + " steps with OtLayout's)", mine.size() >= 2
        && mine.get(0).equals("instance 0 " + n) && mine.get(mine.size() - 1).equals("instance " + n + " " + n) && rising, mine.toString());
    final Recorder c = new Recorder();
    c.cancelAfter = 1;
    final FontInstancer.Options oc = new FontInstancer.Options();
    oc.progress = c;
    throwsFont("cancel: the instance stops with \"cancelled\"", "cancelled", null, () -> FontInstancer.instance(noto, new double[] { 700, 80 }, oc));
    final Sfnt cff2 = font(fix, "../cfftoglyf/ss3vf-subset.otf");
    final Recorder c2 = new Recorder();
    c2.cancelAfter = 1;
    final FontInstancer.Options oc2 = new FontInstancer.Options();
    oc2.progress = c2;
    throwsFont("cancel: a CFF2 conversion stops too", "cancelled", null, () -> FontInstancer.instance(cff2, new double[] { 700 }, oc2));
    final Recorder c0 = new Recorder();
    c0.cancelAfter = 0;
    final FontInstancer.Options o0 = new FontInstancer.Options();
    o0.progress = c0;
    throwsFont("cancel: before anything starts", "cancelled", null, () -> FontInstancer.instance(noto, (double[]) null, o0));
  }

  // ---------- width classes ----------

  static void widthClasses() {
    double[] in = { 50, 62.5, 75, 87.5, 100, 112.5, 125, 150, 200, 25, 300, 56.25, 56.2, 68.75, 175 };
    int[] want = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 9, 2, 1, 3, 9 };
    int[] got = new int[in.length];
    for (int i = 0; i < in.length; i++) got[i] = FontInstancer.widthClass(in[i]);
    check("usWidthClass from wdth, by the OpenType table, rounded as fontTools rounds it", Arrays.equals(got, want), Arrays.toString(got));
  }

  // ---------- avar segment maps ----------

  /** A segment map's bytes from (from, to) pairs given in 1/4 units (F2Dot14 4096). */
  static byte[] segments(double... pairs) {
    byte[] b = new byte[2 * pairs.length];
    for (int i = 0; i < pairs.length; i++) Sfnt.w16(b, 2 * i, (int) Math.round(pairs[i] * 4096));
    return b;
  }

  static float mapAt(byte[] maps, float v) throws Sfnt.FontException { return FontInstancer.mapAxis(maps, 0, maps.length / 4, v); }

  static void avarMaps() throws Exception {
    byte[] none = new byte[0], one = segments(2, 1), curve = segments(-4, -4, 0, 0, 2, 3, 4, 4);
    check("avar: no map leaves the value; a single map shifts it", mapAt(none, 0.3f) == 0.3f && mapAt(one, 0.75f) == 0.5f);
    check("avar: between two maps the value is interpolated; a from value gives its to value",
        Math.abs(mapAt(curve, 0.25f) - 0.375f) < 1e-6 && mapAt(curve, 0.5f) == 0.75f && mapAt(curve, -0.5f) == -0.5f);
    byte[] twice = segments(-4, -4, 0, 0, 2, 1, 2, 3, 4, 4), thrice = segments(-4, -4, 0, 0, 2, 1, 2, 2, 2, 3, 4, 4);
    check("avar: a from value given twice takes the to value nearer 0, three times the middle one",
        mapAt(twice, 0.5f) == 0.25f && mapAt(twice, 0.6f) > 0.75f && mapAt(thrice, 0.5f) == 0.5f);
    byte[] past = segments(-4, -4, -4, -2, 0, 0, 4, 4);
    check("avar: a leading -1 -> -1 before another -1 is skipped", mapAt(past, -1f) == -0.5f);
    check("roundf: halves away from zero, NaN 0",
        FontInstancer.roundf(2.5f) == 3 && FontInstancer.roundf(-2.5f) == -3 && FontInstancer.roundf(-2.49f) == -2 && FontInstancer.roundf(Float.NaN) == 0);
  }

  // ---------- fuzz ----------

  static byte[] mutate(Random r, byte[] s) {
    byte[] c;
    switch (r.nextInt(5)) {
      case 0:                                                              // truncation
        return Arrays.copyOf(s, r.nextInt(s.length + 1));
      case 1:                                                              // bit flips, mostly near the start (headers, offsets, counts)
        c = s.clone();
        for (int k = 1 + r.nextInt(12); k > 0 && c.length > 0; k--) {
          int p = r.nextBoolean() ? r.nextInt(Math.min(c.length, 64)) : r.nextInt(c.length);
          c[p] ^= 1 << r.nextInt(8);
        }
        return c;
      case 2:                                                              // random bytes, half of them behind version 1
        c = new byte[r.nextInt(512)];
        r.nextBytes(c);
        if (c.length >= 4 && r.nextBoolean()) { c[0] = 0; c[1] = 1; c[2] = 0; c[3] = 0; }
        return c;
      case 3:                                                              // huge counts and offsets
        c = s.clone();
        for (int k = 1 + r.nextInt(4); k > 0 && c.length > 1; k--) {
          int p = (r.nextBoolean() ? r.nextInt(Math.min(c.length, 64)) : r.nextInt(c.length)) & ~1;
          int v = new int[] { 0xFFFF, 0x7FFF, 0x8FFF, 0x8000, 0xFFFE, r.nextInt(65536) }[r.nextInt(6)];
          if (p + 1 < c.length) Sfnt.w16(c, p, v);
        }
        return c;
      default:                                                             // a word copied elsewhere
        c = s.clone();
        for (int k = 1 + r.nextInt(6); k > 0 && c.length > 3; k--) {
          int from = r.nextInt(c.length - 1) & ~1, to = r.nextInt(c.length - 1) & ~1;
          c[to] = c[from];
          c[to + 1] = c[from + 1];
        }
        return c;
    }
  }

  /** {@code count} instances of fonts with one variation table damaged; with {@code limit} > 0 a run still going after that many seconds is a hang. */
  static void fuzz(File fix, long seed, int count, final int limit) throws Exception {
    final long started = System.nanoTime();
    Thread watchdog = new Thread(() -> {
      try { Thread.sleep(limit * 1000L); } catch (InterruptedException e) { return; }
      System.out.println("FAIL fuzz: still running after " + limit + " s (a hang)");
      System.exit(1);
    });
    watchdog.setDaemon(true);
    if (limit > 0) watchdog.start();
    Sfnt[] fonts = { font(fix, "synth-gvar.ttf"), font(fix, "synth-avar2.ttf"), font(fix, "RobotoFlex-var.ttf"), font(fix, "../cfftoglyf/ss3vf-subset.otf") };
    String[] tags = { "gvar", "cvar", "MVAR", "HVAR", "avar", "fvar", "GSUB", "GPOS", "GDEF", "cvt ", "hmtx", "glyf" };
    Random r = new Random(seed);
    int runs = 0, refused = 0;
    List<String> bad = new ArrayList<>();
    for (int iter = 0; iter < count; iter++) {
      Sfnt src = fonts[r.nextInt(10) < 6 ? 0 : 1 + r.nextInt(3)];
      Sfnt f = src.copy();
      String tag = tags[r.nextInt(tags.length)];
      if (!f.has(tag)) tag = "gvar";
      if (f.has(tag)) f.put(tag, mutate(r, f.table(tag)));
      FontInstancer.Options o = new FontInstancer.Options();
      o.keepHinting = r.nextBoolean();
      o.overlapFlags = r.nextBoolean();
      double[] user = new double[1 + r.nextInt(13)];
      for (int i = 0; i < user.length; i++) user[i] = r.nextInt(8) == 0 ? Double.NaN : r.nextDouble() * 1400 - 300;
      runs++;
      try {
        Sfnt out = FontInstancer.instance(f, user, o);
        out.toBytes();
      } catch (Sfnt.FontException e) {
        refused++;
        if (e.getCause() != null && bad.size() < 3) bad.add("iteration " + iter + " (" + tag + "): " + e.getCause() + " at " + e.getCause().getStackTrace()[0]);
      } catch (Throwable e) {
        if (bad.size() < 3) bad.add("iteration " + iter + " (" + tag + "): " + e);
      }
    }
    watchdog.interrupt();
    double secs = (System.nanoTime() - started) / 1e9;
    check(String.format("fuzz (seed %x): %d instances with a damaged gvar, cvar, MVAR, HVAR, avar, fvar, GSUB, GPOS, GDEF, cvt, hmtx or glyf in %.1f s; "
        + "%d refused with a FontException of the instancer's own, nothing else thrown", seed, runs, secs, refused), bad.isEmpty()
        && (limit == 0 || secs < limit / 2.0), String.valueOf(bad));
  }

  // ---------- --dump ----------

  /** The file name make_fixtures.py verify looks for. */
  static String dumpName(String fixture, String spec) {
    return fixture + "@" + spec.replace("#", "i").replace(":", "-").replace("=", "-").replace(",", "_") + ".ttf";
  }

  static void dump(File fix, File dir) throws Exception {
    dir.mkdirs();
    int n = 0;
    for (String name : FIXTURES) {
      Expect ex = Expect.read(new File(fix, name + ".expect.txt"));
      Sfnt vf = font(fix, ex.font);
      for (Loc l : ex.locs) {
        Files.write(new File(dir, dumpName(name, l.spec)).toPath(), instanceAt(vf, l, true).toBytes());
        n++;
      }
    }
    System.out.println("wrote " + n + " instances to " + dir);
  }
}
