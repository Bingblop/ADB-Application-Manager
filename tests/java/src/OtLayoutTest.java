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

/**
 * OtLayout, the GSUB / GPOS / GDEF code of the Custom Fonts engine. Against tests/java/fixtures/fonts/otlayout: the model of every fixture
 * table (fontTools' reading), the Extension writer, glyph and lookup index shifts, the emoji merge, and GPOS deltas, FeatureVariations and
 * ligature carets at instance locations (fontTools' instancer). expected.txt holds those values and the SHA-256 of every table this suite
 * makes; make_fixtures.py checked each of those tables with HarfBuzz and fontTools before recording its hash. Hand-built tables cover the
 * formats the fixtures lack and the edge cases; a seeded fuzz runs every entry point on damaged tables.
 *
 * <p>{@code --dump DIR} writes the tables this suite makes, for {@code make_fixtures.py verify DIR}; {@code --fuzz SEED COUNT} runs only the
 * fuzz, longer or with another seed.
 */
public class OtLayoutTest {
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

  static final String[] TAGS = { "GSUB", "GPOS" };

  public static void main(String[] args) throws Exception {
    File fix = fixtureDir();
    if (args.length == 2 && args[0].equals("--dump")) {
      if (fix == null) throw new IOException("fixtures not found");
      dump(fix, Expected.read(new File(fix, "expected.txt")), new File(args[1]));
      return;
    }
    if (args.length == 3 && args[0].equals("--fuzz")) {
      fuzz(fix, Long.parseLong(args[1], 16), Integer.parseInt(args[2]), 0);
      System.exit(fails == 0 ? 0 : 1);
    }
    if (fix == null || !new File(fix, "expected.txt").isFile()) {
      check("the fixtures are found (tests/java/fixtures/fonts/otlayout)", false);
    } else {
      final Expected ex = Expected.read(new File(fix, "expected.txt"));
      check("expected.txt has the hashes of the tables this suite makes", !ex.sha.isEmpty(), "run make_fixtures.py verify");
      section("fixture tables", () -> fixtureTables(fix, ex));
      section("instances", () -> instances(fix, ex));
      section("merges", () -> merges(fix, ex));
      section("cancel and progress", () -> cancelAndProgress(fix));
      section("fuzz", () -> fuzz(fix, 0x07152026L, 30000, 120));
    }
    section("hand-built GSUB", OtLayoutTest::handGsub);
    section("hand-built GPOS", OtLayoutTest::handGpos);
    section("FeatureVariations", OtLayoutTest::featureVariations);
    section("FeatureParams", OtLayoutTest::params);
    section("damaged tables", OtLayoutTest::damaged);
    section("hand-built merge", OtLayoutTest::handMerge);
    section("writer limits", OtLayoutTest::writerLimits);
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

  static File fixtureDir() {
    String p = System.getProperty("fixtures");
    if (p != null && new File(p, "fonts/otlayout").isDirectory()) return new File(p, "fonts/otlayout");
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts/otlayout");
    return null;
  }

  /** expected.txt: see make_fixtures.py for the format. */
  static final class Expected {
    final Map<String, Integer> glyphs = new LinkedHashMap<>();
    final Map<String, int[]> deltas = new HashMap<>();
    final Map<String, List<String>> blocks = new HashMap<>();     // by their first line: "model F T", "kern F L", "marks F L", "fv F L", "carets F L"
    final Map<String, int[]> coords = new LinkedHashMap<>();      // "F L" -> fontTools' normalized coordinates
    final List<String[]> merges = new ArrayList<>();
    final Map<String, String> sha = new HashMap<>();              // "F what" -> hex

    static Expected read(File f) throws IOException {
      Expected e = new Expected();
      List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String l = lines.get(i);
        if (l.isEmpty() || l.startsWith("#")) continue;
        String[] w = l.split(" ");
        switch (w[0]) {
          case "glyphs": e.glyphs.put(w[1], Integer.parseInt(w[2])); break;
          case "delta": e.deltas.put(w[1], new int[] { Integer.parseInt(w[2]), Integer.parseInt(w[3]) }); break;
          case "location": e.coords.put(w[1] + " " + w[2], ints(w[3])); break;
          case "merge": e.merges.add(new String[] { w[1], w[2] }); break;
          case "sha": e.sha.put(w[1] + " " + w[2], w[3]); break;
          case "model": case "kern": case "marks": case "fv": case "carets": {
            List<String> body = new ArrayList<>();
            while (++i < lines.size() && !lines.get(i).equals("end")) body.add(lines.get(i));
            e.blocks.put(l, body);
            break;
          }
          default: throw new IOException("expected.txt: unknown line " + l);
        }
      }
      return e;
    }

    /** The first block of this kind for the font (any location). */
    List<String> any(String kind, String font) {
      for (String loc : coords.keySet()) if (loc.startsWith(font + " ")) return blocks.get(kind + " " + loc);
      return null;
    }
  }

  static int[] ints(String s) {
    if (s.equals("-") || s.isEmpty()) return new int[0];
    String[] p = s.split(",");
    int[] v = new int[p.length];
    for (int i = 0; i < p.length; i++) v[i] = Integer.parseInt(p[i]);
    return v;
  }

  static final Map<String, Sfnt> FONTS = new HashMap<>();

  static Sfnt font(File fix, String name) throws IOException {
    Sfnt f = FONTS.get(name);
    if (f == null) FONTS.put(name, f = Sfnt.parse(Files.readAllBytes(new File(fix, name).toPath())));
    return f;
  }

  static String sha(byte[] b) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x & 0xFF));
    return sb.toString();
  }

  static void shaCheck(Expected ex, String font, String what, byte[] b, String name) throws Exception {
    String want = ex.sha.get(font + " " + what);
    check(name + " is byte for byte the one make_fixtures.py checked (" + font + " " + what + ")", want != null && want.equals(sha(b)),
        want == null ? "no hash recorded" : "sha " + sha(b));
  }

  static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
    return sb.toString();
  }

  static String join(List<Integer> v) {
    if (v.isEmpty()) return "-";
    StringBuilder sb = new StringBuilder();
    for (int x : v) sb.append(sb.length() == 0 ? "" : ",").append(x);
    return sb.toString();
  }

  static String firstDiff(List<String> want, List<String> got) {
    for (int i = 0; i < Math.max(want.size(), got.size()); i++) {
      String a = i < want.size() ? want.get(i) : "(none)", b = i < got.size() ? got.get(i) : "(none)";
      if (!a.equals(b)) return "line " + i + ": expected [" + a + "] got [" + b + "]";
    }
    return null;
  }

  // ---------- what the suite makes (shared by the checks and --dump) ----------

  static byte[] rewritten(byte[] t, boolean gsub) throws Sfnt.FontException { return OtLayout.write(OtLayout.parse(t, gsub), FontProgress.NONE); }

  static byte[] shifted(byte[] t, boolean gsub, int d) throws Sfnt.FontException {
    byte[] c = t.clone();
    OtLayout.shiftGlyphs(c, gsub, d, FontProgress.NONE);
    return c;
  }

  static byte[] lkshifted(byte[] t, boolean gsub, int d) throws Sfnt.FontException {
    byte[] c = t.clone();
    OtLayout.shiftLookupIndexes(c, gsub, d, FontProgress.NONE);
    return c;
  }

  static int[] normalized(Sfnt f, String loc) throws Sfnt.FontException {
    VarStore.Fvar fvar = VarStore.Fvar.read(f);
    double[] user = new double[fvar.axes.size()];
    for (int i = 0; i < user.length; i++) user[i] = fvar.axes.get(i).def;
    for (String kv : loc.split(",")) {
      String[] p = kv.split("=");
      user[fvar.axisIndex(p[0])] = Double.parseDouble(p[1]);
    }
    return VarStore.normalize(f, fvar, user);
  }

  static final class Instance {
    final Map<String, byte[]> tables = new LinkedHashMap<>();
    int skipped;
  }

  /** A static instance's layout tables, the way the instancer makes them: GPOS deltas, FeatureVariations resolved, GDEF without its store. */
  static Instance instanced(Sfnt f, int[] coords) throws Sfnt.FontException {
    Instance in = new Instance();
    VarStore store = OtLayout.gdefVarStore(f.table("GDEF"));
    if (store != null) store.at(coords);
    for (String tag : TAGS) {
      byte[] t = f.table(tag);
      if (t == null) continue;
      boolean gsub = tag.equals("GSUB");
      if (!gsub && store != null) {
        t = t.clone();
        in.skipped += OtLayout.applyGposDeltas(t, store, FontProgress.NONE);
      }
      OtLayout.Table m = OtLayout.parse(t, gsub);
      if (m.hasFeatureVariations()) {
        OtLayout.applyFeatureVariations(m, coords);
        t = OtLayout.write(m, FontProgress.NONE);
      }
      in.tables.put(tag, t);
    }
    if (f.has("GDEF")) in.tables.put("GDEF", OtLayout.instanceGdef(f.table("GDEF"), store));
    return in;
  }

  /** The emoji font's GSUB as the merge takes it: glyph ids past the text font's (one .notdef when there is no text font), lookup indexes past its lookups. */
  static OtLayout.Table emojiModel(Sfnt text, Sfnt emoji) throws Sfnt.FontException {
    byte[] e = emoji.table("GSUB").clone();
    OtLayout.Table tm = textModel(text);
    OtLayout.shiftGlyphs(e, true, text == null ? 1 : text.numGlyphs(), FontProgress.NONE);
    OtLayout.shiftLookupIndexes(e, true, tm == null ? 0 : tm.lookups.size(), FontProgress.NONE);
    return OtLayout.parse(e, true);
  }

  static OtLayout.Table textModel(Sfnt text) throws Sfnt.FontException {
    return text != null && text.has("GSUB") ? OtLayout.parse(text.table("GSUB"), true) : null;
  }

  static void put(File dir, String name, byte[] b) throws IOException { Files.write(new File(dir, name).toPath(), b); }

  static void dump(File fix, Expected ex, File dir) throws Exception {
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
    int n = 0;
    for (String name : ex.glyphs.keySet()) {
      Sfnt f = font(fix, name);
      int[] d = ex.deltas.get(name);
      for (String tag : TAGS) {
        byte[] t = f.table(tag);
        if (t == null) continue;
        boolean gsub = tag.equals("GSUB");
        put(dir, name + "." + tag + ".write", rewritten(t, gsub));
        put(dir, name + "." + tag + ".shift", shifted(t, gsub, d[0]));
        put(dir, name + "." + tag + ".lkshift", lkshifted(t, gsub, d[1]));
        n += 3;
      }
    }
    for (String key : ex.coords.keySet()) {
      String[] fl = key.split(" ");
      Sfnt f = font(fix, fl[0]);
      Instance in = instanced(f, normalized(f, fl[1]));
      for (Map.Entry<String, byte[]> e : in.tables.entrySet()) { put(dir, fl[0] + ".inst." + fl[1] + "." + e.getKey(), e.getValue()); n++; }
    }
    for (String[] m : ex.merges) {
      Sfnt text = m[0].equals("-") ? null : font(fix, m[0]);
      OtLayout.Table out = OtLayout.merge(textModel(text), emojiModel(text, font(fix, m[1])), "ccmp", FontProgress.NONE);
      put(dir, m[0] + ".merge." + m[1] + ".GSUB", OtLayout.write(out, FontProgress.NONE));
      n++;
    }
    System.out.println("wrote " + n + " tables to " + dir);
  }

  // ---------- the model as make_fixtures.py prints fontTools' ----------

  static List<String> modelLines(OtLayout.Table m) {
    List<String> out = new ArrayList<>();
    for (OtLayout.Lookup l : m.lookups)
      out.add("L " + l.type + " " + l.flag + " " + l.subtables.size() + " " + ((l.flag & OtLayout.USE_MARK_FILTERING_SET) != 0 ? l.markFilteringSet : -1));
    for (OtLayout.Feature f : m.features) out.add("F " + f.tag + " " + join(f.lookups) + " " + (f.params == null ? "-" : hex(f.params)));
    for (OtLayout.Script s : m.scripts) {
      out.add("S " + s.tag + " - - -");
      if (s.dflt != null) out.add("S " + s.tag + " * " + s.dflt.required + " " + join(s.dflt.features));
      for (Map.Entry<String, OtLayout.LangSys> e : s.langs.entrySet())
        out.add("S " + s.tag + " " + e.getKey() + " " + e.getValue().required + " " + join(e.getValue().features));
    }
    return out;
  }

  /** The model without its feature numbering (the writer sorts features by tag): what a shaper would see. */
  static String canonical(OtLayout.Table m) {
    if (m == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (OtLayout.Lookup l : m.lookups) sb.append("L ").append(l.type).append(' ').append(l.flag).append(' ').append(l.markFilteringSet).append(' ').append(l.subtables.size()).append('\n');
    List<String> fs = new ArrayList<>();
    for (int i = 0; i < m.features.size(); i++) fs.add(feature(m, i));
    java.util.Collections.sort(fs);
    for (String f : fs) sb.append("F ").append(f).append('\n');
    List<OtLayout.Script> scripts = new ArrayList<>(m.scripts);
    java.util.Collections.sort(scripts, (a, b) -> a.tag.compareTo(b.tag));
    for (OtLayout.Script s : scripts) {
      sb.append("S ").append(s.tag).append('\n');
      if (s.dflt != null) sb.append(" * ").append(langSys(m, s.dflt)).append('\n');
      for (Map.Entry<String, OtLayout.LangSys> e : s.langs.entrySet()) sb.append(' ').append(e.getKey()).append(' ').append(langSys(m, e.getValue())).append('\n');
    }
    return sb.toString();
  }

  static String feature(OtLayout.Table m, int i) {
    OtLayout.Feature f = m.features.get(i);
    return f.tag + "[" + join(f.lookups) + "]" + (f.params == null ? "" : hex(f.params));
  }

  static String langSys(OtLayout.Table m, OtLayout.LangSys l) {
    StringBuilder sb = new StringBuilder(l.required == 0xFFFF ? "-" : feature(m, l.required)).append(':');
    for (int fi : l.features) sb.append(' ').append(feature(m, fi));
    return sb.toString();
  }

  /** Version 1.0, every lookup with subtables an Extension lookup whose subtables wrap the lookup's real type. */
  static boolean extensionOnly(byte[] w, boolean gsub, OtLayout.Table m) throws Sfnt.FontException {
    if (Sfnt.u16(w, 0) != 1 || Sfnt.u16(w, 2) != 0) return false;
    int ll = Sfnt.u16(w, 8), ext = gsub ? 7 : 9;
    if (Sfnt.u16(w, ll) != m.lookups.size()) return false;
    for (int i = 0; i < m.lookups.size(); i++) {
      OtLayout.Lookup l = m.lookups.get(i);
      int lo = ll + Sfnt.u16(w, ll + 2 + 2 * i), n = Sfnt.u16(w, lo + 4);
      if (Sfnt.u16(w, lo) != (n == 0 ? l.type : ext) || n != l.subtables.size()) return false;
      for (int k = 0; k < n; k++) {
        int so = lo + Sfnt.u16(w, lo + 6 + 2 * k);
        if (Sfnt.u16(w, so) != 1 || Sfnt.u16(w, so + 2) != l.type) return false;
      }
    }
    return true;
  }

  /** The source {@code t} sits in {@code w} byte for byte, 4-aligned, and every subtable of the re-read model {@code r} is the original's there. */
  static boolean embedded(byte[] w, byte[] t, OtLayout.Table m, OtLayout.Table r) {
    long at = -1;
    for (int i = 0; i < m.lookups.size(); i++) {
      List<OtLayout.Subtable> a = m.lookups.get(i).subtables, b = r.lookups.get(i).subtables;
      if (a.size() != b.size()) return false;
      for (int k = 0; k < a.size(); k++) {
        long p = (long) b.get(k).offset - a.get(k).offset;
        if (at < 0) at = p;
        if (p != at || b.get(k).source != 0) return false;
      }
    }
    if (at < 0) return true;
    return at % 4 == 0 && at + t.length <= w.length && Arrays.equals(Arrays.copyOfRange(w, (int) at, (int) at + t.length), t);
  }

  // ---------- GPOS evaluators (HarfBuzz's rules for a two-glyph run, as make_fixtures.py checked them) ----------

  /** The lookups of a feature in latn's default language system (DFLT's when there is no latn), in lookup order. */
  static List<Integer> featureLookups(OtLayout.Table m, String feature) {
    OtLayout.Script s = m.script("latn") != null ? m.script("latn") : m.script("DFLT");
    TreeSet<Integer> out = new TreeSet<>();
    if (s != null && s.dflt != null)
      for (int fi : s.dflt.features) if (m.features.get(fi).tag.equals(feature)) out.addAll(m.features.get(fi).lookups);
    return new ArrayList<>(out);
  }

  static int coverage(byte[] t, int off, int g) throws Sfnt.FontException {
    int fmt = Sfnt.u16(t, off), n = Sfnt.u16(t, off + 2);
    if (fmt == 1) {
      for (int i = 0; i < n; i++) if (Sfnt.u16(t, off + 4 + 2 * i) == g) return i;
    } else if (fmt == 2) {
      for (int i = 0; i < n; i++) {
        int r = off + 4 + 6 * i;
        if (g >= Sfnt.u16(t, r) && g <= Sfnt.u16(t, r + 2)) return Sfnt.u16(t, r + 4) + g - Sfnt.u16(t, r);
      }
    }
    return -1;
  }

  static int classOf(byte[] t, int off, int g) throws Sfnt.FontException {
    if (off < 0) return 0;
    int fmt = Sfnt.u16(t, off);
    if (fmt == 1) {
      int start = Sfnt.u16(t, off + 2), n = Sfnt.u16(t, off + 4);
      return g >= start && g < start + n ? Sfnt.u16(t, off + 6 + 2 * (g - start)) : 0;
    }
    if (fmt == 2) {
      int n = Sfnt.u16(t, off + 2);
      for (int i = 0; i < n; i++) {
        int r = off + 4 + 6 * i;
        if (g >= Sfnt.u16(t, r) && g <= Sfnt.u16(t, r + 2)) return Sfnt.u16(t, r + 4);
      }
    }
    return 0;
  }

  static int xAdvance(byte[] t, int rec, int vf) throws Sfnt.FontException {
    return (vf & 4) == 0 ? 0 : Sfnt.s16(t, rec + 2 * Integer.bitCount(vf & 3));
  }

  /** {first glyph's, second glyph's} x advance adjustment of the pair. */
  static int[] kern(OtLayout.Table m, List<Integer> lookups, int left, int right) throws Sfnt.FontException {
    int v1 = 0, v2 = 0;
    for (int li : lookups) {
      OtLayout.Lookup l = m.lookups.get(li);
      if (l.type != 2) continue;
      for (OtLayout.Subtable s : l.subtables) {
        byte[] t = m.sources.get(s.source);
        int so = s.offset, fmt = Sfnt.u16(t, so);
        int ci = coverage(t, so + Sfnt.u16(t, so + 2), left);
        if (ci < 0) continue;
        int vf1 = Sfnt.u16(t, so + 4), vf2 = Sfnt.u16(t, so + 6), s1 = 2 * Integer.bitCount(vf1), s2 = 2 * Integer.bitCount(vf2);
        if (fmt == 1) {
          int ps = so + Sfnt.u16(t, so + 10 + 2 * ci), pc = Sfnt.u16(t, ps), rec = 2 + s1 + s2, hit = -1;
          for (int k = 0; k < pc && hit < 0; k++) if (Sfnt.u16(t, ps + 2 + k * rec) == right) hit = ps + 2 + k * rec;
          if (hit < 0) continue;
          v1 += xAdvance(t, hit + 2, vf1);
          v2 += xAdvance(t, hit + 2 + s1, vf2);
          break;
        } else if (fmt == 2) {
          int cd1 = Sfnt.u16(t, so + 8), cd2 = Sfnt.u16(t, so + 10);
          int c1 = classOf(t, cd1 == 0 ? -1 : so + cd1, left), c2 = classOf(t, cd2 == 0 ? -1 : so + cd2, right);
          int n1 = Sfnt.u16(t, so + 12), n2 = Sfnt.u16(t, so + 14);
          if (c2 == 0 || c1 >= n1 || c2 >= n2) continue;
          int r = so + 16 + (c1 * n2 + c2) * (s1 + s2);
          v1 += xAdvance(t, r, vf1);
          v2 += xAdvance(t, r + s1, vf2);
          break;
        }
      }
    }
    return new int[] { v1, v2 };
  }

  /** The mark's offset from the base (base anchor - mark anchor) through mark-to-base lookups, or null when none attaches it. */
  static int[] attach(OtLayout.Table m, List<Integer> lookups, int base, int mark) throws Sfnt.FontException {
    int[] got = null;
    for (int li : lookups) {
      OtLayout.Lookup l = m.lookups.get(li);
      if (l.type != 4) continue;
      for (OtLayout.Subtable s : l.subtables) {
        byte[] t = m.sources.get(s.source);
        int so = s.offset;
        int mi = coverage(t, so + Sfnt.u16(t, so + 2), mark), bi = coverage(t, so + Sfnt.u16(t, so + 4), base);
        if (mi < 0 || bi < 0) continue;
        int classes = Sfnt.u16(t, so + 6), ma = so + Sfnt.u16(t, so + 8), ba = so + Sfnt.u16(t, so + 10);
        int cls = Sfnt.u16(t, ma + 2 + 4 * mi), mo = Sfnt.u16(t, ma + 4 + 4 * mi);
        if (cls >= classes) continue;
        int bo = Sfnt.u16(t, ba + 2 + 2 * (bi * classes + cls));
        if (bo == 0) continue;
        int mx = mo == 0 ? 0 : Sfnt.s16(t, ma + mo + 2), my = mo == 0 ? 0 : Sfnt.s16(t, ma + mo + 4);
        got = new int[] { Sfnt.s16(t, ba + bo + 2) - mx, Sfnt.s16(t, ba + bo + 4) - my };
        break;
      }
    }
    return got;
  }

  // ---------- fixture tables: model, writer, shifts ----------

  static void fixtureTables(File fix, Expected ex) throws Exception {
    for (String name : ex.glyphs.keySet()) {
      Sfnt f = font(fix, name);
      check(name + ": " + f.numGlyphs() + " glyphs", f.numGlyphs() == ex.glyphs.get(name));
      int[] d = ex.deltas.get(name);
      for (String tag : TAGS) {
        String id = name + " " + tag;
        byte[] t = f.table(tag);
        List<String> want = ex.blocks.get("model " + id);
        if (t == null || want == null) {
          check(id + ": in the font and in expected.txt alike", t == null && want == null);
          continue;
        }
        boolean gsub = tag.equals("GSUB");
        OtLayout.Table m = OtLayout.parse(t, gsub);
        List<String> got = modelLines(m);
        check(id + ": read as fontTools reads it (" + m.lookups.size() + " lookups, " + m.features.size() + " features, " + m.scripts.size() + " scripts"
            + (m.hasFeatureVariations() ? ", FeatureVariations" : "") + ")", got.equals(want), firstDiff(want, got));

        byte[] w = rewritten(t, gsub);
        OtLayout.Table r = OtLayout.parse(w, gsub);
        check(id + ": written (" + t.length + " -> " + w.length + " bytes) and read back, the same scripts, language systems, features and lookups",
            canonical(r).equals(canonical(m)));
        check(id + ": the written table is version 1.0 and reaches every subtable through an Extension lookup", extensionOnly(w, gsub, m));
        check(id + ": the source is embedded byte for byte and every subtable is found there", embedded(w, t, m, r));
        shaCheck(ex, name, tag + ".write", w, id + ": the written table");

        byte[] s = shifted(t, gsub, d[0]);
        shaCheck(ex, name, tag + ".shift", s, id + ": shiftGlyphs(+" + d[0] + ")");
        check(id + ": shiftGlyphs(+" + d[0] + ") then (-" + d[0] + ") gives back the original bytes", Arrays.equals(shifted(s, gsub, -d[0]), t));
        check(id + ": shiftGlyphs keeps the table's structure", modelLines(OtLayout.parse(s, gsub)).equals(got));
        if (!gsub) shiftedGpos(ex, name, m, OtLayout.parse(s, false), d[0]);

        byte[] k = lkshifted(t, gsub, d[1]);
        shaCheck(ex, name, tag + ".lkshift", k, id + ": shiftLookupIndexes(+" + d[1] + ")");
        check(id + ": shiftLookupIndexes(+" + d[1] + ") then (-" + d[1] + ") gives back the original bytes", Arrays.equals(lkshifted(k, gsub, -d[1]), t));
        check(id + ": shiftLookupIndexes keeps the table's structure", modelLines(OtLayout.parse(k, gsub)).equals(got));
      }
    }
  }

  /** Kerning and mark attachment of the shifted GPOS at glyph + d are the original's at glyph. */
  static void shiftedGpos(Expected ex, String name, OtLayout.Table m, OtLayout.Table s, int d) throws Exception {
    List<String> kb = ex.any("kern", name), mb = ex.any("marks", name);
    if (kb == null || mb == null) return;
    int[] g = ints(kb.get(0).substring(2)), bases = ints(mb.get(0).substring(2)), marks = ints(mb.get(1).substring(2));
    List<Integer> kl = featureLookups(m, "kern"), ml = featureLookups(m, "mark");
    int n = 0, bad = 0, nonzero = 0;
    for (int a : g)
      for (int b : g) {
        int[] x = kern(m, kl, a, b), y = kern(s, kl, a + d, b + d);
        n++;
        if (!Arrays.equals(x, y)) bad++;
        if (x[0] != 0 || x[1] != 0) nonzero++;
      }
    for (int a : bases)
      for (int k : marks) {
        int[] x = attach(m, ml, a, k), y = attach(s, ml, a + d, k + d);
        n++;
        if (!Arrays.equals(x, y)) bad++;
        if (x != null) nonzero++;
      }
    check(name + " GPOS: after shiftGlyphs(+" + d + "), kerning and mark attachment at glyph + " + d + " are the original's (" + n + " pairs, "
        + nonzero + " of them non-zero)", bad == 0 && nonzero > 0, bad + " differ");
  }

  // ---------- instances: GPOS deltas, FeatureVariations, GDEF ----------

  static void instances(File fix, Expected ex) throws Exception {
    for (Map.Entry<String, int[]> e : ex.coords.entrySet()) {
      String name = e.getKey().split(" ")[0], loc = e.getKey().split(" ")[1], id = name + " at " + loc;
      Sfnt f = font(fix, name);
      int[] c = normalized(f, loc);
      check(id + ": normalized coordinates " + Arrays.toString(c) + " are fontTools'", Arrays.equals(c, e.getValue()), Arrays.toString(e.getValue()));
      Instance in = instanced(f, c);
      check(id + ": every GPOS variation delta had a value to go into", in.skipped == 0, in.skipped + " skipped");
      OtLayout.Table gp = OtLayout.parse(in.tables.get("GPOS"), false), gp0 = OtLayout.parse(f.table("GPOS"), false);

      List<String> kb = ex.blocks.get("kern " + e.getKey());
      int[] g = ints(kb.get(0).substring(2));
      Map<Long, int[]> want = new HashMap<>();
      for (String l : kb.subList(1, kb.size())) {
        String[] w = l.split(" ");
        want.put(Long.parseLong(w[1]) << 16 | Long.parseLong(w[2]), new int[] { Integer.parseInt(w[3]), Integer.parseInt(w[4]) });
      }
      List<Integer> kl = featureLookups(gp, "kern");
      int n = 0, bad = 0, moved = 0;
      String first = null;
      for (int a : g)
        for (int b : g) {
          int[] w = want.getOrDefault((long) a << 16 | b, new int[2]), v = kern(gp, kl, a, b);
          n++;
          if (Math.abs(v[0] - w[0]) > 1 || Math.abs(v[1] - w[1]) > 1) {
            bad++;
            if (first == null) first = a + "+" + b + ": " + Arrays.toString(v) + " vs fontTools " + Arrays.toString(w);
          }
          if (!Arrays.equals(kern(gp0, kl, a, b), w)) moved++;
        }
      check(id + ": kerning of " + n + " letter pairs within 1 unit of fontTools' instance (" + want.size() + " non-zero; " + moved
          + " differ from the default location's)", bad == 0, first);

      List<String> mb = ex.blocks.get("marks " + e.getKey());
      int[] bases = ints(mb.get(0).substring(2)), marks = ints(mb.get(1).substring(2));
      Map<Long, int[]> at = new HashMap<>();
      for (String l : mb.subList(2, mb.size())) {
        String[] w = l.split(" ");
        at.put(Long.parseLong(w[1]) << 16 | Long.parseLong(w[2]), new int[] { Integer.parseInt(w[3]), Integer.parseInt(w[4]) });
      }
      List<Integer> ml = featureLookups(gp, "mark");
      n = bad = moved = 0;
      first = null;
      for (int a : bases)
        for (int k : marks) {
          int[] w = at.get((long) a << 16 | k), v = attach(gp, ml, a, k), v0 = attach(gp0, ml, a, k);
          n++;
          if ((w == null) != (v == null) || w != null && (Math.abs(v[0] - w[0]) > 1 || Math.abs(v[1] - w[1]) > 1)) {
            bad++;
            if (first == null) first = a + "+" + k + ": " + Arrays.toString(v) + " vs fontTools " + Arrays.toString(w);
          }
          if (w != null && !Arrays.equals(v0, w)) moved++;
        }
      if (marks.length > 0)
        check(id + ": mark attachment of " + n + " base + mark pairs within 1 unit of fontTools' instance (" + at.size() + " attach; " + moved
            + " differ from the default location's)", bad == 0, first);

      List<String> fb = ex.blocks.get("fv " + e.getKey());
      OtLayout.Table gs = OtLayout.parse(f.table("GSUB"), true);
      if (fb != null) {
        boolean had = gs.hasFeatureVariations(), matched = OtLayout.applyFeatureVariations(gs, c);
        // fontTools drops a feature left with no lookups and renumbers the rest: compare the features that do something, in order
        List<String> got = new ArrayList<>(), fw = new ArrayList<>();
        for (OtLayout.Feature fe : gs.features) if (!fe.lookups.isEmpty()) got.add(fe.tag + " " + join(fe.lookups));
        for (String l : fb) if (!l.endsWith(" -")) fw.add(l.substring(l.indexOf(' ', 2) + 1));
        check(id + ": FeatureVariations " + (matched ? "match and swap in" : "do not match, leaving") + " the lookups fontTools' instance has ("
            + got.size() + " features with lookups)", had && got.equals(fw) && !gs.hasFeatureVariations(), firstDiff(fw, got));
      } else {
        check(id + ": no FeatureVariations, as fontTools has none", !gs.hasFeatureVariations());
      }

      byte[] gdef = in.tables.get("GDEF");
      check(id + ": the instance's GDEF is version 1.2 with no variation store", gdef != null && Sfnt.u32(gdef, 0) == 0x00010002L && Sfnt.u32(gdef, 14) == 0
          && OtLayout.gdefVarStore(gdef) == null);
      List<String> cb = ex.blocks.get("carets " + e.getKey());
      if (cb != null) {
        List<String> got = carets(gdef);
        boolean ok = got.size() == cb.size();
        for (int i = 0; ok && i < got.size(); i++) ok = sameCarets(got.get(i), cb.get(i));
        check(id + ": " + got.size() + " ligature caret lists within 1 unit of fontTools' instance", ok, firstDiff(cb, got));
      }
      for (Map.Entry<String, byte[]> t : in.tables.entrySet()) shaCheck(ex, name, "inst." + loc + "." + t.getKey(), t.getValue(), id + ": the instance's " + t.getKey());
    }
  }

  static List<String> carets(byte[] g) throws Sfnt.FontException {
    List<String> out = new ArrayList<>();
    int lcl = Sfnt.u16(g, 8);
    if (lcl == 0) return out;
    int n = Sfnt.u16(g, lcl + 2);
    for (int i = 0; i < n; i++) {
      int lg = lcl + Sfnt.u16(g, lcl + 4 + 2 * i), cc = Sfnt.u16(g, lg);
      StringBuilder sb = new StringBuilder();
      for (int k = 0; k < cc; k++) {
        int cv = lg + Sfnt.u16(g, lg + 2 + 2 * k);
        sb.append(k == 0 ? "" : ",").append(Sfnt.u16(g, cv) == 2 ? "p" + Sfnt.u16(g, cv + 2) : String.valueOf(Sfnt.s16(g, cv + 2)));
      }
      out.add("R " + i + " " + sb);
    }
    return out;
  }

  static boolean sameCarets(String a, String b) {
    String[] x = a.split(" ", -1), y = b.split(" ", -1);
    if (x.length != 3 || y.length != 3 || !x[1].equals(y[1])) return false;
    String[] p = x[2].split(","), q = y[2].split(",");
    if (p.length != q.length) return false;
    for (int i = 0; i < p.length; i++) {
      if (p[i].startsWith("p") || q[i].startsWith("p") || p[i].isEmpty()) { if (!p[i].equals(q[i])) return false; }
      else if (Math.abs(Integer.parseInt(p[i]) - Integer.parseInt(q[i])) > 1) return false;
    }
    return true;
  }

  // ---------- merges ----------

  static void merges(File fix, Expected ex) throws Exception {
    for (String[] mg : ex.merges) {
      String id = "merge " + mg[0] + " + " + mg[1];
      Sfnt text = mg[0].equals("-") ? null : font(fix, mg[0]);
      OtLayout.Table tm = textModel(text), em = emojiModel(text, font(fix, mg[1]));
      String before = canonical(tm) + canonical(em);
      byte[] emBytes = em.sources.get(0).clone();
      OtLayout.Table out = OtLayout.merge(tm, em, "ccmp", FontProgress.NONE);
      check(id + ": neither input is changed", before.equals(canonical(tm) + canonical(em)) && Arrays.equals(emBytes, em.sources.get(0)));
      byte[] w = OtLayout.write(out, FontProgress.NONE);
      shaCheck(ex, mg[0], "merge." + mg[1] + ".GSUB", w, id + ": the merged GSUB");
      OtLayout.Table r = OtLayout.parse(w, true);
      int base = tm == null ? 0 : tm.lookups.size();
      boolean same = r.lookups.size() == base + em.lookups.size();
      for (int i = 0; same && i < r.lookups.size(); i++) {
        OtLayout.Lookup a = i < base ? tm.lookups.get(i) : em.lookups.get(i - base), b = r.lookups.get(i);
        same = a.type == b.type && a.flag == b.flag && a.subtables.size() == b.subtables.size();
      }
      check(id + ": the text lookups, then the " + em.lookups.size() + " emoji lookups after them (" + r.lookups.size() + ")", same);
      TreeSet<Integer> on = new TreeSet<>();
      for (OtLayout.Feature fe : em.features) for (int li : fe.lookups) on.add(base + li);
      int langSys = 0;
      boolean all = r.script("DFLT") != null, order = true;
      for (OtLayout.Script s : r.scripts) {
        List<OtLayout.LangSys> ls = new ArrayList<>(s.langs.values());
        ls.add(0, s.dflt);
        for (OtLayout.LangSys l : ls) {
          if (l == null) { all = false; continue; }
          langSys++;
          TreeSet<Integer> got = new TreeSet<>();
          for (int fi : l.features) {
            OtLayout.Feature fe = r.features.get(fi);
            if (!fe.tag.equals("ccmp")) continue;
            got.addAll(fe.lookups);
            for (int i = 1; i < fe.lookups.size(); i++) if (fe.lookups.get(i - 1) >= base && fe.lookups.get(i) < base) order = false;
          }
          if (!got.containsAll(on)) all = false;
        }
      }
      check(id + ": DFLT and every one of " + langSys + " language systems of " + r.scripts.size() + " scripts turn on the " + on.size()
          + " emoji lookups through ccmp, after the text font's own", all && order);
      if (text == null) check(id + ": with no text GSUB the scripts are DFLT and latn", r.scripts.size() == 2 && r.script("DFLT") != null && r.script("latn") != null);
    }
  }

  // ---------- cancel and progress ----------

  static FontProgress cancelAfter(final int calls) {
    return new FontProgress() {
      int n;
      @Override public void step(String stage, int done, int total) {}
      @Override public boolean cancelled() { return n++ >= calls; }
    };
  }

  static void cancelAndProgress(File fix) throws Exception {
    Sfnt inter = font(fix, "Inter-sub.ttf");
    final byte[] gpos = inter.table("GPOS");
    throwsFont("shiftGlyphs stops with \"cancelled\" when the job is cancelled", "cancelled", null,
        () -> OtLayout.shiftGlyphs(gpos.clone(), false, 1, cancelAfter(0)));
    final VarStore store = OtLayout.gdefVarStore(inter.table("GDEF")).at(new int[] { 0, 16384 });
    throwsFont("applyGposDeltas stops with \"cancelled\" when the job is cancelled", "cancelled", null,
        () -> OtLayout.applyGposDeltas(gpos.clone(), store, cancelAfter(0)));
    final OtLayout.Table many = new OtLayout.Table(true);
    for (int i = 0; i < 300; i++) { OtLayout.Lookup l = new OtLayout.Lookup(); l.type = 1; many.lookups.add(l); }
    throwsFont("write stops with \"cancelled\" when the job is cancelled", "cancelled", null, () -> OtLayout.write(many, cancelAfter(0)));
    final List<String> steps = new ArrayList<>();
    OtLayout.write(many, new FontProgress() {
      @Override public void step(String stage, int done, int total) { steps.add(stage + " " + done + "/" + total); }
      @Override public boolean cancelled() { return false; }
    });
    check("write reports progress (" + steps + ")", steps.equals(Arrays.asList(OtLayout.STAGE + " 256/300")));
    final byte[] emoji = font(fix, "NotoColorEmoji-sub.ttf").table("GSUB");
    throwsFont("merge stops with \"cancelled\" when the job is cancelled", "cancelled", null,
        () -> OtLayout.merge(null, OtLayout.parse(emoji, true), "ccmp", cancelAfter(0)));
  }

  // ---------- hand-built tables ----------

  /** Bytes with labels: o16 / o32 write the offset of a label from another, filled in by bytes(). */
  static final class Bld {
    private byte[] b = new byte[256];
    private int n;
    private final Map<String, Integer> at = new HashMap<>();
    private final List<Object[]> fix = new ArrayList<>();

    Bld u8(int v) { if (n == b.length) b = Arrays.copyOf(b, 2 * n); b[n++] = (byte) v; return this; }
    Bld u16(int v) { return u8(v >> 8).u8(v); }
    Bld u32(long v) { return u16((int) (v >>> 16)).u16((int) v); }
    Bld tag(String t) { for (int i = 0; i < 4; i++) u8(t.charAt(i)); return this; }
    Bld l(String label) { at.put(label, n); return this; }
    Bld o16(String target, String base) { fix.add(new Object[] { n, 2, target, base }); return u16(0); }
    Bld o32(String target, String base) { fix.add(new Object[] { n, 4, target, base }); return u32(0); }
    int pos(String label) { Integer p = at.get(label); if (p == null) throw new IllegalStateException("no label " + label); return p; }

    byte[] bytes() {
      byte[] out = Arrays.copyOf(b, n);
      for (Object[] f : fix) {
        int p = (Integer) f[0], v = pos((String) f[2]) - pos((String) f[3]);
        if (v < 0 || (Integer) f[1] == 2 && v > 0xFFFF) throw new IllegalStateException("offset " + f[2] + " from " + f[3] + " is " + v);
        if ((Integer) f[1] == 2) Sfnt.w16(out, p, v); else Sfnt.w32(out, p, v);
      }
      return out;
    }
  }

  static int u16(byte[] t, int at) throws Sfnt.FontException { return Sfnt.u16(t, at); }

  /** A copy of {@code t} with the 16-bit words at {@code labels} of {@code b} increased by {@code d} (the others as they were). */
  static byte[] plus(Bld b, byte[] t, int d, String... labels) {
    byte[] c = t.clone();
    for (String l : labels) {
      int p = l.endsWith("+2") ? b.pos(l.substring(0, l.length() - 2)) + 2 : l.endsWith("+4") ? b.pos(l.substring(0, l.length() - 2)) + 4 : b.pos(l);
      Sfnt.w16(c, p, Sfnt.u16q(c, p) + d);
    }
    return c;
  }

  static Bld header(Bld b, int minor) {
    b.l("top").u16(1).u16(minor).o16("SL", "top").o16("FL", "top").o16("LL", "top");
    if (minor == 1) b.o32("FV", "top");
    return b;
  }

  /** GSUB: single format 2 and format 1 sharing a coverage, reverse chaining (8), ligature behind an Extension lookup, context format 1. */
  static Bld handGsubTable() {
    Bld b = header(new Bld(), 0);
    b.l("SL").u16(1).tag("DFLT").o16("S0", "SL");
    b.l("S0").o16("LS0", "S0").u16(0);
    b.l("LS0").u16(0).u16(0xFFFF).u16(2).u16(0).u16(1);
    b.l("FL").u16(2).tag("ccmp").o16("F0", "FL").tag("liga").o16("F1", "FL");
    b.l("F0").u16(0).u16(2).u16(0).u16(1);
    b.l("F1").u16(0).u16(2).u16(2).u16(3);
    b.l("LL").u16(4).o16("L0", "LL").o16("L1", "LL").o16("L2", "LL").o16("L3", "LL");
    b.l("L0").u16(1).u16(0).u16(2).o16("L0a", "L0").o16("L0b", "L0");
    b.l("L0a").u16(2).o16("C1", "L0a").u16(2).l("sub0").u16(10).u16(11);
    b.l("L0b").u16(1).o16("C1", "L0b").l("dg").u16(5);
    b.l("C1").u16(1).u16(2).l("c1g").u16(5).u16(6);
    b.l("L1").u16(8).u16(0).u16(1).o16("L1a", "L1");
    b.l("L1a").u16(1).o16("C2", "L1a").u16(1).o16("C3", "L1a").u16(1).o16("C4", "L1a").u16(1).l("rsub").u16(12);
    b.l("C2").u16(1).u16(1).l("c2g").u16(7);
    b.l("C3").u16(2).u16(1).l("c3r").u16(3).u16(4).u16(0);
    b.l("C4").u16(1).u16(1).l("c4g").u16(9);
    b.l("L2").u16(7).u16(0).u16(1).o16("X2", "L2");
    b.l("X2").u16(1).u16(4).o32("Lig", "X2");
    b.l("L3").u16(5).u16(0).u16(1).o16("Ctx", "L3");
    b.l("Ctx").u16(1).o16("C6", "Ctx").u16(1).o16("RS", "Ctx");
    b.l("RS").u16(1).o16("R", "RS");
    b.l("R").u16(2).u16(1).l("rg").u16(8).u16(0).l("rlk").u16(0);
    b.l("C6").u16(1).u16(1).l("c6g").u16(5);
    b.l("Lig").u16(1).o16("C5", "Lig").u16(1).o16("LS", "Lig");
    b.l("C5").u16(1).u16(1).l("c5g").u16(20);
    b.l("LS").u16(1).o16("LG", "LS");
    b.l("LG").l("lgg").u16(30).u16(3).l("lgc").u16(21).u16(22);
    return b;
  }

  static void handGsub() throws Exception {
    Bld b = handGsubTable();
    byte[] t = b.bytes();

    OtLayout.Table m = OtLayout.parse(t, true);
    check("hand GSUB: read (single 1 + 2, reverse chaining, ligature through Extension, context 1)",
        modelLines(m).equals(Arrays.asList("L 1 0 2 -1", "L 8 0 1 -1", "L 4 0 1 -1", "L 5 0 1 -1", "F ccmp 0,1 -", "F liga 2,3 -", "S DFLT - - -", "S DFLT * 65535 0,1"))
            && m.lookups.get(2).subtables.get(0).offset == b.pos("Lig"), String.valueOf(modelLines(m)));
    byte[] s = shifted(t, true, 100);
    check("hand GSUB: shiftGlyphs moves every glyph id once (a shared coverage once), keeps single format 1's delta, a range's coverage index, "
        + "the component count and lookup indexes", Arrays.equals(s, plus(b, t, 100, "sub0", "sub0+2", "c1g", "c1g+2", "c2g", "c3r", "c3r+2", "c4g", "rsub", "rg", "c6g", "c5g", "lgg", "lgc", "lgc+2")));
    check("hand GSUB: shiftLookupIndexes moves only the context rule's lookup index", Arrays.equals(lkshifted(t, true, 5), plus(b, t, 5, "rlk")));
    byte[] w = rewritten(t, true);
    check("hand GSUB: written and read back the same, through Extension lookups", canonical(OtLayout.parse(w, true)).equals(canonical(m)) && extensionOnly(w, true, m)
        && embedded(w, t, m, OtLayout.parse(w, true)));
    throwsFont("hand GSUB: a glyph id past 65535 is refused", "too_many_glyphs", null, () -> OtLayout.shiftGlyphs(t.clone(), true, 65535 - 25, null));
  }

  static Bld handGdef() {
    Bld g = new Bld();
    g.l("top").u16(1).u16(3).u16(0).u16(0).o16("LCL", "top").u16(0).u16(0).o32("VS", "top");
    g.l("LCL").o16("LCov", "LCL").u16(1).o16("LG0", "LCL");
    g.l("LCov").u16(1).u16(1).u16(40);
    g.l("LG0").u16(2).o16("CV0", "LG0").o16("CV1", "LG0");
    g.l("CV0").u16(3).l("cv0").u16(300).o16("DG", "CV0");
    g.l("CV1").u16(1).l("cv1").u16(400);
    g.l("DG").u16(0).u16(0).u16(0x8000);
    g.l("VS").u16(1).o32("RL", "VS").u16(1).o32("IVD", "VS");
    g.l("RL").u16(1).u16(1).u16(0).u16(16384).u16(16384);           // one axis, one region peaking at 1.0
    g.l("IVD").u16(2).u16(0).u16(1).u16(0).u8(10).u8(-7);            // item 0: +10, item 1: -7 (bytes)
    return g;
  }

  /** GPOS: single format 2 with VariationIndex devices, cursive (3) with an Anchor format 3, context format 3 (7), a device with no value, a hinting device. */
  static Bld handGposTable() {
    Bld b = header(new Bld(), 0);
    b.l("SL").u16(1).tag("latn").o16("S0", "SL");
    b.l("S0").o16("LS0", "S0").u16(0);
    b.l("LS0").u16(0).u16(0xFFFF).u16(1).u16(0);
    b.l("FL").u16(1).tag("kern").o16("F0", "FL");
    b.l("F0").u16(0).u16(5).u16(0).u16(1).u16(2).u16(3).u16(4);
    b.l("LL").u16(5).o16("L0", "LL").o16("L1", "LL").o16("L2", "LL").o16("L3", "LL").o16("L4", "LL");
    b.l("L0").u16(1).u16(0).u16(1).o16("P1", "L0");
    b.l("P1").u16(2).o16("CP", "P1").u16(0x0045).u16(2).l("v0").u16(3).u16(100).o16("D0", "P1").l("v1").u16(4).u16(200).o16("D1", "P1");
    b.l("CP").u16(1).u16(2).l("cpg").u16(5).u16(6);
    b.l("D0").u16(0).u16(0).u16(0x8000);
    b.l("D1").u16(0).u16(1).u16(0x8000);
    b.l("L1").u16(3).u16(0).u16(1).o16("Cur", "L1");
    b.l("Cur").u16(1).o16("CC", "Cur").u16(1).o16("A0", "Cur").o16("A1", "Cur");
    b.l("CC").u16(1).u16(1).l("ccg").u16(7);
    b.l("A0").u16(3).l("ax").u16(50).u16(60).o16("D2", "A0").u16(0);
    b.l("D2").u16(0).u16(0).u16(0x8000);
    b.l("A1").u16(1).u16(70).u16(80);
    b.l("L2").u16(7).u16(0).u16(1).o16("Cp", "L2");
    b.l("Cp").u16(3).u16(1).u16(1).o16("CQ", "Cp").u16(0).l("cplk").u16(0);
    b.l("CQ").u16(1).u16(1).l("cqg").u16(5);
    b.l("L3").u16(1).u16(0).u16(1).o16("P2", "L3");
    b.l("P2").u16(1).o16("CP2", "P2").u16(0x0040).l("v2").o16("D3", "P2");
    b.l("CP2").u16(1).u16(1).l("cp2g").u16(8);
    b.l("D3").u16(0).u16(1).u16(0x8000);
    b.l("L4").u16(1).u16(0).u16(1).o16("P3", "L4");
    b.l("P3").u16(1).o16("CP3", "P3").u16(0x0011).l("v3").u16(9).o16("H", "P3");
    b.l("CP3").u16(1).u16(1).l("cp3g").u16(9);
    b.l("H").u16(12).u16(12).u16(1).u16(0x4000);
    return b;
  }

  static void handGpos() throws Exception {
    Bld b = handGposTable();
    final byte[] t = b.bytes();
    Bld gb = handGdef();
    final byte[] gdef = gb.bytes();

    OtLayout.Table m = OtLayout.parse(t, false);
    check("hand GPOS: read (single 2, cursive, context 3, single 1 twice)", modelLines(m).subList(0, 5).equals(Arrays.asList("L 1 0 1 -1", "L 3 0 1 -1", "L 7 0 1 -1", "L 1 0 1 -1", "L 1 0 1 -1")));
    VarStore store = OtLayout.gdefVarStore(gdef);
    check("GDEF 1.3: its variation store is found", store != null);
    byte[] d = t.clone();
    int skipped = OtLayout.applyGposDeltas(d, store.at(new int[] { 16384 }), FontProgress.NONE);
    byte[] want = plus(b, t, 10, "v0+2", "ax");
    want = plus(b, want, -7, "v1+2");
    for (String l : new String[] { "v0+4", "v1+4", "v2" }) Sfnt.w16(want, l.endsWith("+4") ? b.pos(l.substring(0, 2)) + 4 : b.pos(l), 0);
    Sfnt.w16(want, b.pos("ax") + 4, 0);
    check("hand GPOS at 1.0: values and the anchor get round(delta) and lose their VariationIndex offsets; the hinting device, placements and the "
        + "context are left", Arrays.equals(d, want));
    check("hand GPOS at 1.0: the device with no value to add -7 to is counted as skipped (" + skipped + ")", skipped == 1);
    d = t.clone();
    OtLayout.applyGposDeltas(d, store.at(new int[] { 8192 }), FontProgress.NONE);
    check("hand GPOS at 0.5: +5 and round(-3.5) = -3 (fontTools' rounding)", u16(d, b.pos("v0") + 2) == 105 && u16(d, b.pos("v1") + 2) == 197 && u16(d, b.pos("ax")) == 55);
    check("hand GPOS: shiftGlyphs moves the five coverages only", Arrays.equals(shifted(t, false, 100), plus(b, t, 100, "cpg", "cpg+2", "ccg", "cqg", "cp2g", "cp3g")));
    check("hand GPOS: shiftLookupIndexes moves the context's lookup index only", Arrays.equals(lkshifted(t, false, 3), plus(b, t, 3, "cplk")));
    byte[] w = rewritten(t, false);
    check("hand GPOS: written and read back the same", canonical(OtLayout.parse(w, false)).equals(canonical(m)) && extensionOnly(w, false, m));

    byte[] gi = OtLayout.instanceGdef(gdef, store.at(new int[] { 16384 }));
    check("instanceGdef: CaretValue format 3 gets its delta (300 -> 310) and loses its device; format 1 is left; version 1.2, no store",
        u16(gi, gb.pos("cv0")) == 310 && u16(gi, gb.pos("cv0") + 2) == 0 && u16(gi, gb.pos("cv1")) == 400 && Sfnt.u32(gi, 0) == 0x00010002L
            && Sfnt.u32(gi, 14) == 0 && gi.length == gdef.length && OtLayout.gdefVarStore(gi) == null && u16(gdef, gb.pos("cv0")) == 300);
    byte[] gn = OtLayout.instanceGdef(gdef, null);
    check("instanceGdef with no store: carets as they were, the store dropped", u16(gn, gb.pos("cv0")) == 300 && u16(gn, gb.pos("cv0") + 2) != 0 && Sfnt.u32(gn, 14) == 0);
    // an item whose row would start 4 GB into the data: 32-bit arithmetic wrapped it back inside the table, so it was read as garbage
    Bld big = new Bld();
    big.l("top").u16(1).u16(3).u16(0).u16(0).u16(0).u16(0).u16(0).o32("VS", "top");
    big.l("VS").u16(1).o32("RL", "VS").u16(1).o32("IVD", "VS");
    big.l("RL").u16(1).u16(1).u16(0).u16(16384).u16(16384);
    big.l("IVD").u16(65535).u16(1).u16(65535);
    for (int i = 0; i < 65535 + 32768; i++) big.u16(0);
    final VarStore wrap = OtLayout.gdefVarStore(big.bytes()).at(new int[] { 16384 });
    throwsFont("VarStore: an item whose row lies past the end of the table is refused, not read from a wrapped offset", "bad_font", null,
        () -> wrap.delta(0, 65534));
    // variation data 4 GB past the store (its 32-bit offset + the store's own position): int arithmetic wrapped it onto real data before the store
    Bld fb = new Bld();
    fb.l("top").u16(1).u16(3).u16(0).u16(0).u16(0).u16(0).u16(0).o32("VS", "top");
    fb.l("IVD").u16(2).u16(0).u16(1).u16(0).u8(10).u8(-7);
    fb.l("VS").u16(1).o32("RL", "VS").u16(1).l("dofs").u32(0);
    fb.l("RL").u16(1).u16(1).u16(0).u16(16384).u16(16384);
    final byte[] far = fb.bytes();
    Sfnt.w32(far, fb.pos("dofs"), (int) (0x100000000L - fb.pos("VS") + fb.pos("IVD")));
    throwsFont("VarStore: variation data 4 GB past the store is refused, not read from the wrapped offset", "bad_font", null,
        () -> OtLayout.gdefVarStore(far).at(new int[] { 16384 }).delta(0, 0));
    byte[] g12 = Arrays.copyOf(gdef, 18);
    Sfnt.w16(g12, 2, 2);
    check("gdefVarStore: GDEF 1.2 has none", OtLayout.gdefVarStore(g12) == null && OtLayout.gdefVarStore(null) == null);
    try {
      OtLayout.applyGposDeltas(t.clone(), null, null);
      check("applyGposDeltas without a store is a programming error", false);
    } catch (IllegalArgumentException e) {
      check("applyGposDeltas without a store is a programming error", true);
    }

    // a coverage that another subtable reads as a ClassDef: patching it as both would change different fields of the same bytes
    Bld c = new Bld();
    c.l("top").u16(1).u16(0).u16(0).u16(0).o16("LL", "top");
    c.l("LL").u16(2).o16("L0", "LL").o16("L1", "LL");
    c.l("L0").u16(2).u16(0).u16(1).o16("PP", "L0");
    c.l("PP").u16(2).o16("X", "PP").u16(0).u16(0).o16("X", "PP").u16(0).u16(2).u16(1);
    c.l("L1").u16(1).u16(0).u16(1).o16("SP", "L1");
    c.l("SP").u16(1).o16("X", "SP").u16(0);
    c.l("X").u16(1).u16(1).u16(5).u16(0).u16(0).u16(0).u16(0).u16(0);
    final byte[] ct = c.bytes();
    throwsFont("shiftGlyphs refuses a coverage that is also read as a class definition", "bad_font", "shares data", () -> OtLayout.shiftGlyphs(ct.clone(), false, 1, null));
    Bld o = new Bld();
    o.l("top").u16(1).u16(0).u16(0).u16(0).o16("LL", "top");
    o.l("LL").u16(1).o16("L0", "LL");
    o.l("L0").u16(2).u16(0).u16(1).o16("PP", "L0");
    o.l("PP").u16(2).o16("CV", "PP").u16(0).u16(0).o16("CD", "PP").u16(0).u16(1).u16(1);
    o.l("CV").u16(1).u16(1).u16(65000);
    o.l("CD").u16(1).u16(65000).u16(600);
    final byte[] ot = o.bytes();
    throwsFont("shiftGlyphs refuses a class definition whose range would pass 65535", "too_many_glyphs", null, () -> OtLayout.shiftGlyphs(ot.clone(), false, 100, null));
  }

  /** GSUB 1.1 FeatureVariations: the first record that holds wins, a null substitution blocks the rest, an unknown condition format never holds. */
  static Bld fvTable() {
    Bld b = header(new Bld(), 1);
    b.l("SL").u16(1).tag("DFLT").o16("S0", "SL");
    b.l("S0").o16("LS0", "S0").u16(0);
    b.l("LS0").u16(0).u16(0xFFFF).u16(1).u16(0);
    b.l("FL").u16(1).tag("rvrn").o16("F0", "FL");
    b.l("F0").u16(0).u16(1).u16(0);
    b.l("LL").u16(2).o16("L0", "LL").o16("L1", "LL");
    b.l("L0").u16(1).u16(0).u16(0);
    b.l("L1").u16(1).u16(0).u16(0);
    b.l("FV").u16(1).u16(0).u32(3);
    b.o32("CS0", "FV").u32(0);                       // axis 0 in 0.5..1: null substitution
    b.o32("CS1", "FV").o32("FTS1", "FV");            // condition format 2: never
    b.u32(0).o32("FTS2", "FV");                      // no conditions: always
    b.l("CS0").u16(1).o32("C0", "CS0");
    b.l("C0").u16(1).u16(0).u16(8192).u16(16384);
    b.l("CS1").u16(1).o32("C1", "CS1");
    b.l("C1").u16(2).u16(0).u16(0).u16(0);
    b.l("FTS1").u16(1).u16(0).u16(1).u16(0).o32("AF1", "FTS1");
    b.l("AF1").u16(0).u16(1).u16(0);
    b.l("FTS2").u16(1).u16(0).u16(1).u16(0).o32("AF2", "FTS2");
    b.l("AF2").u16(0).u16(2).u16(1).u16(99);
    return b;
  }

  static void featureVariations() throws Exception {
    Bld b = fvTable();
    byte[] t = b.bytes();
    OtLayout.Table m = OtLayout.parse(t, true);
    boolean hit = OtLayout.applyFeatureVariations(m, new int[] { 16384 });
    check("FeatureVariations at 1.0: the first record matches with a null substitution, so the default lookups stay",
        hit && m.features.get(0).lookups.equals(Arrays.asList(0)) && !m.hasFeatureVariations());
    m = OtLayout.parse(t, true);
    hit = OtLayout.applyFeatureVariations(m, new int[] { 0 });
    check("FeatureVariations at 0: the format 2 condition never holds, the record with no conditions does (lookup 1; index 99 dropped)",
        hit && m.features.get(0).lookups.equals(Arrays.asList(1)));
    check("FeatureVariations: a second call does nothing", !OtLayout.applyFeatureVariations(m, new int[] { 16384 }) && m.features.get(0).lookups.equals(Arrays.asList(1)));
    byte[] w = OtLayout.write(m, null);
    check("FeatureVariations: the written table is 1.0 without them and keeps the chosen lookups",
        Sfnt.u16(w, 2) == 0 && !OtLayout.parse(w, true).hasFeatureVariations() && OtLayout.parse(w, true).features.get(0).lookups.equals(Arrays.asList(1)));

    // records that share one long condition set, and a substitution that names one long alternate many times: refused, not quadratic
    Bld q = header(new Bld(), 1);
    q.l("SL").u16(0).l("FL").u16(1).tag("rvrn").o16("F0", "FL").l("F0").u16(0).u16(0).l("LL").u16(0);
    int recs = 4000, conds = 4000;
    q.l("FV").u16(1).u16(0).u32(recs);
    for (int i = 0; i < recs; i++) q.o32("CS", "FV").u32(0);
    q.l("CS").u16(conds);
    for (int i = 0; i < conds; i++) q.o32(i == conds - 1 ? "CF" : "CT", "CS");
    q.l("CT").u16(1).u16(0).u16(-16384).u16(16384).l("CF").u16(1).u16(0).u16(16384).u16(16384);
    final byte[] qt = q.bytes();
    throwsFont("FeatureVariations: 4000 records sharing a set of 4000 conditions are refused, not evaluated 16 million times", "bad_font", "too many times",
        () -> OtLayout.applyFeatureVariations(OtLayout.parse(qt, true), new int[] { 0 }));
    Bld s = header(new Bld(), 1);
    s.l("SL").u16(0).l("FL").u16(1).tag("rvrn").o16("F0", "FL").l("F0").u16(0).u16(0).l("LL").u16(0);
    s.l("FV").u16(1).u16(0).u32(1).u32(0).o32("FTS", "FV");
    s.l("FTS").u16(1).u16(0).u16(4000);
    for (int i = 0; i < 4000; i++) s.u16(0).o32("AF", "FTS");
    s.l("AF").u16(0).u16(4000);
    for (int i = 0; i < 4000; i++) s.u16(0);
    final byte[] st = s.bytes();
    throwsFont("FeatureVariations: 4000 substitutions naming one alternate of 4000 lookups are refused", "bad_font", "too many times",
        () -> OtLayout.applyFeatureVariations(OtLayout.parse(st, true), new int[] { 0 }));
  }

  /** FeatureParams: 'size' (also where old tools put its offset: from the FeatureList), 'ss01', 'cv01' with characters. */
  static Bld paramsTable() {
    Bld b = header(new Bld(), 0);
    b.l("SL").u16(0);
    b.l("FL").u16(4).tag("cv01").o16("F2", "FL").tag("size").o16("F0", "FL").tag("ss01").o16("F1", "FL").tag("ss02").o16("F3", "FL");
    b.l("F2").o16("P2", "F2").u16(0);
    b.l("F0").o16("P0", "FL").u16(0);                // the old tools' offset: from the feature it points past the end
    b.l("F1").o16("P1", "F1").u16(0);
    b.l("F3").o16("P3", "F3").u16(0);
    b.l("LL").u16(0);
    b.l("P1").u16(0).u16(256);
    b.l("P2").u16(0).u16(256).u16(257).u16(258).u16(0).u16(0).u16(2).u8(0).u8(0).u8(0x41).u8(1).u8(0xF6).u8(0);
    b.l("P0").u16(100).u16(0).u16(0).u16(0).u16(0);
    b.l("P3").u16(0);                                // cut off: ignored
    return b;
  }

  static void params() throws Exception {
    Bld b = paramsTable();
    byte[] t = b.bytes();
    OtLayout.Table m = OtLayout.parse(t, false);
    String[] want = { "000001000101010200000000000200004101f600", "00640000000000000000", "00000100", null };
    boolean ok = m.features.size() == 4;
    for (int i = 0; ok && i < 4; i++) ok = want[i] == null ? m.features.get(i).params == null : m.features.get(i).params != null && hex(m.features.get(i).params).equals(want[i]);
    check("FeatureParams: cv01 (14 + 3 x 2 bytes), size through the FeatureList offset, ss01; a cut-off one is dropped", ok, String.valueOf(modelLines(m)));
    OtLayout.Table r = OtLayout.parse(OtLayout.write(m, null), false);
    check("FeatureParams: written next to their features and read back the same", canonical(r).equals(canonical(m)));
  }

  /** Tables the reader refuses or repairs. */
  static void damaged() throws Exception {
    Bld v = new Bld().u16(2).u16(0).u16(0).u16(0).u16(0);
    final byte[] v2 = v.bytes();
    throwsFont("a GSUB 2.0 is refused", "bad_font", "version", () -> OtLayout.parse(v2, true));
    throwsFont("a GSUB shorter than its header is refused", "bad_font", null, () -> OtLayout.parse(new byte[6], true));

    Bld x = new Bld();
    x.l("top").u16(1).u16(0).u16(0).u16(0).o16("LL", "top");
    x.l("LL").u16(1).o16("L0", "LL");
    x.l("L0").u16(7).u16(0).u16(2).o16("E1", "L0").o16("E2", "L0");
    x.l("E1").u16(1).u16(1).o32("S", "E1");
    x.l("E2").u16(1).u16(4).o32("S", "E2");
    x.l("S").u16(1).u16(0).u16(0);
    final byte[] mixed = x.bytes();
    throwsFont("an Extension lookup mixing subtable types is refused", "bad_font", "mixes subtable types", () -> OtLayout.parse(mixed, true));
    final byte[] inner = mixed.clone();
    Sfnt.w16(inner, x.pos("E2") + 2, 7);
    Sfnt.w16(inner, x.pos("E1") + 2, 7);
    throwsFont("an Extension subtable that wraps an Extension is refused", "bad_font", "damaged", () -> OtLayout.parse(inner, true));
    byte[] unknown = mixed.clone();
    Sfnt.w16(unknown, x.pos("E1"), 2);
    Sfnt.w16(unknown, x.pos("E2"), 2);
    OtLayout.Table u = OtLayout.parse(unknown, true);
    check("Extension subtables of an unknown format are skipped (HarfBuzz)", u.lookups.get(0).subtables.isEmpty() && u.lookups.get(0).type == 7);
    byte[] nul = mixed.clone();
    Sfnt.w16(nul, x.pos("L0"), 1);
    Sfnt.w16(nul, x.pos("L0") + 6, 0);
    u = OtLayout.parse(nul, true);
    check("a null subtable offset is skipped", u.lookups.get(0).subtables.size() == 1 && u.lookups.get(0).subtables.get(0).offset == x.pos("E2"));

    Bld r = header(new Bld(), 0);
    r.l("SL").u16(1).tag("DFLT").o16("S0", "SL");
    r.l("S0").o16("LD", "S0").u16(2).tag("TRK ").o16("LT", "S0").tag("TRK ").o16("LD", "S0");
    r.l("LD").u16(0).u16(77).u16(2).u16(0).u16(50);
    r.l("LT").u16(0).u16(0).u16(1).u16(0);
    r.l("FL").u16(1).tag("liga").o16("F0", "FL");
    r.l("F0").u16(0).u16(2).u16(0).u16(99);
    r.l("LL").u16(1).o16("L0", "LL");
    r.l("L0").u16(4).u16(0x0010).u16(0).u16(3);
    OtLayout.Table m = OtLayout.parse(r.bytes(), true);
    check("indexes past their lists are dropped, a repeated language tag keeps its first entry, the mark filtering set is read",
        modelLines(m).equals(Arrays.asList("L 4 16 0 3", "F liga 0 -", "S DFLT - - -", "S DFLT * 65535 0", "S DFLT TRK  0 0")), String.valueOf(modelLines(m)));
    OtLayout.Table w = OtLayout.parse(OtLayout.write(m, null), true);
    check("a lookup with no subtables and a mark filtering set is written as itself", modelLines(w).equals(modelLines(m)));

    // 2000 lookups that all name one lookup of 2000 subtables: refused by the walkers' budget, not walked 4 million times
    Bld h = new Bld();
    h.l("top").u16(1).u16(0).u16(0).u16(0).o16("LL", "top");
    h.l("LL").u16(2000);
    for (int i = 0; i < 2000; i++) h.o16("L", "LL");
    h.l("L").u16(1).u16(0).u16(2000);
    for (int i = 0; i < 2000; i++) h.o16("S", "L");
    h.l("S").u16(1).o16("C", "S").u16(0).l("C").u16(1).u16(0);
    final byte[] ht = h.bytes();
    throwsFont("shiftGlyphs: 2000 lookups naming one lookup of 2000 subtables are refused", "bad_font", "too many times", () -> OtLayout.shiftGlyphs(ht.clone(), true, 1, null));
    throwsFont("parse: the same table is refused too", "bad_font", "too many times", () -> OtLayout.parse(ht, true));
  }

  /** merge on small models: DFLT from latn, a new shared ccmp where a language system has none, lookup indexes moved in a copy. */
  static void handMerge() throws Exception {
    OtLayout.Table text = new OtLayout.Table(true);
    for (int i = 0; i < 2; i++) { OtLayout.Lookup l = new OtLayout.Lookup(); l.type = i == 0 ? 1 : 4; text.lookups.add(l); }
    OtLayout.Feature ccmp = new OtLayout.Feature("ccmp"), liga = new OtLayout.Feature("liga");
    ccmp.lookups.add(0);
    liga.lookups.add(1);
    text.features.add(ccmp);
    text.features.add(liga);
    OtLayout.Script latn = new OtLayout.Script("latn"), arab = new OtLayout.Script("arab");
    latn.dflt = new OtLayout.LangSys();
    latn.dflt.features.add(0);
    OtLayout.LangSys trk = new OtLayout.LangSys();
    trk.features.add(1);
    latn.langs.put("TRK ", trk);
    OtLayout.LangSys urd = new OtLayout.LangSys();
    urd.features.add(0);
    arab.langs.put("URD ", urd);
    text.scripts.add(latn);
    text.scripts.add(arab);

    Bld e = header(new Bld(), 0);
    e.l("SL").u16(1).tag("DFLT").o16("S0", "SL");
    e.l("S0").o16("LS", "S0").u16(0);
    e.l("LS").u16(0).u16(0xFFFF).u16(2).u16(0).u16(1);
    e.l("FL").u16(2).tag("ccmp").o16("F0", "FL").tag("liga").o16("F1", "FL");
    e.l("F0").u16(0).u16(1).u16(1);
    e.l("F1").u16(0).u16(1).u16(2);
    e.l("LL").u16(3).o16("L0", "LL").o16("L1", "LL").o16("L2", "LL");
    e.l("L0").u16(1).u16(0).u16(1).o16("SS", "L0");
    e.l("SS").u16(1).o16("SC", "SS").u16(1);
    e.l("SC").u16(1).u16(1).u16(3);
    e.l("L1").u16(5).u16(0).u16(1).o16("CX", "L1");
    e.l("CX").u16(3).u16(1).u16(1).o16("CC", "CX").u16(0).l("cxl").u16(0);
    e.l("CC").u16(1).u16(1).u16(3);
    e.l("L2").u16(4).u16(0).u16(0);
    byte[] eb = e.bytes();
    OtLayout.shiftLookupIndexes(eb, true, 2, null);                // as the caller does: past the text font's two lookups
    OtLayout.Table em = OtLayout.parse(eb, true);
    String before = canonical(text) + canonical(em);
    OtLayout.Table out = OtLayout.merge(text, em, "ccmp", null);
    check("merge: the inputs are not changed", before.equals(canonical(text) + canonical(em)) && u16(eb, e.pos("cxl")) == 2);
    check("merge: the emoji lookups come after the text's, their subtables in the emoji table (whose context calls lookup 2)",
        out.lookups.size() == 5 && out.lookups.get(2).type == 1 && out.lookups.get(3).type == 5 && out.lookups.get(4).type == 4
            && out.sources.get(out.lookups.get(3).subtables.get(0).source) == eb);
    OtLayout.Script dflt = out.script("DFLT"), l2 = out.script("latn"), a2 = out.script("arab");
    check("merge: the text ccmp gets lookups 3 and 4 once, after its own; a new ccmp (3, 4) is shared by the language systems without one",
        out.features.size() == 3 && out.features.get(0).lookups.equals(Arrays.asList(0, 3, 4)) && out.features.get(2).tag.equals("ccmp")
            && out.features.get(2).lookups.equals(Arrays.asList(3, 4)) && out.features.get(1).lookups.equals(Arrays.asList(1)));
    check("merge: DFLT is made from latn; arab gets a default language system; every language system has ccmp",
        dflt != null && dflt.dflt.features.equals(Arrays.asList(0)) && dflt.langs.get("TRK ").features.equals(Arrays.asList(1, 2))
            && l2.dflt.features.equals(Arrays.asList(0)) && l2.langs.get("TRK ").features.equals(Arrays.asList(1, 2))
            && a2.dflt != null && a2.dflt.features.equals(Arrays.asList(2)) && a2.langs.get("URD ").features.equals(Arrays.asList(0))
            && latn.langs.get("TRK ").features.equals(Arrays.asList(1)));
    check("merge: written and read back the same", canonical(OtLayout.parse(OtLayout.write(out, null), true)).equals(canonical(out)));

    OtLayout.Table alone = OtLayout.merge(null, em, "ccmp", null);
    check("merge with no text table: DFLT and latn, each with a ccmp of the emoji lookups",
        alone.scripts.size() == 2 && alone.script("DFLT").dflt.features.equals(Arrays.asList(0)) && alone.script("latn").dflt.features.equals(Arrays.asList(0))
            && alone.features.size() == 1 && alone.features.get(0).lookups.equals(Arrays.asList(1, 2)) && alone.lookups.size() == 3);
    check("merge with nothing to add: the text table as it was", canonical(OtLayout.merge(text, null, "ccmp", null)).equals(canonical(text)));
    final OtLayout.Table big = new OtLayout.Table(true);
    for (int i = 0; i < 65534; i++) { OtLayout.Lookup l = new OtLayout.Lookup(); l.type = 1; big.lookups.add(l); }
    final OtLayout.Table em2 = em;
    throwsFont("merge: more than 65535 lookups are refused", "bad_font", "too many", () -> OtLayout.merge(big, em2, "ccmp", null));
    try {
      OtLayout.merge(text, new OtLayout.Table(false), "ccmp", null);
      check("merge of a GSUB and a GPOS is a programming error", false);
    } catch (IllegalArgumentException x) {
      check("merge of a GSUB and a GPOS is a programming error", true);
    }
  }

  static void writerLimits() throws Exception {
    final OtLayout.Table a = new OtLayout.Table(true);
    a.sources.add(new byte[100]);
    OtLayout.Lookup l = new OtLayout.Lookup();
    l.type = 1;
    for (int i = 0; i < 6600; i++) l.subtables.add(new OtLayout.Subtable(0, 10));
    a.lookups.add(l);
    throwsFont("write: a lookup whose Extension subtables cannot all be reached with 16 bits is too big", "too_big", null, () -> OtLayout.write(a, null));
    final OtLayout.Table b = new OtLayout.Table(false);
    OtLayout.Script s = new OtLayout.Script("DFLT");
    s.dflt = new OtLayout.LangSys();
    for (int i = 0; i < 40000; i++) { b.features.add(new OtLayout.Feature("kern")); s.dflt.features.add(i); }
    b.scripts.add(s);
    throwsFont("write: a script list past 64 KB is too big", "too_big", null, () -> OtLayout.write(b, null));
    final OtLayout.Table c = new OtLayout.Table(true);
    OtLayout.Feature f = new OtLayout.Feature("liga");
    f.lookups.add(3);
    c.features.add(f);
    throwsFont("write: a feature naming a lookup that does not exist is refused", "bad_font", "could not be written", () -> OtLayout.write(c, null));
    final OtLayout.Table d = new OtLayout.Table(true);
    OtLayout.Lookup k = new OtLayout.Lookup();
    k.subtables.add(new OtLayout.Subtable(2, 4));
    d.lookups.add(k);
    throwsFont("write: a subtable of a source the model does not have is refused", "bad_font", "could not be written", () -> OtLayout.write(d, null));
  }

  // ---------- fuzz ----------

  static byte[] mutate(Random r, byte[] s) {
    byte[] c;
    switch (r.nextInt(5)) {
      case 0:                                                              // truncation
        return Arrays.copyOf(s, r.nextInt(s.length + 1));
      case 1:                                                              // bit flips, mostly where the headers and offsets are
        c = s.clone();
        for (int k = 1 + r.nextInt(16); k > 0 && c.length > 0; k--) {
          int p = r.nextBoolean() ? r.nextInt(Math.min(c.length, 2048)) : r.nextInt(c.length);
          c[p] ^= 1 << r.nextInt(8);
        }
        return c;
      case 2:                                                              // random bytes, half of them behind a valid version
        c = new byte[r.nextInt(4096)];
        r.nextBytes(c);
        if (c.length >= 4 && r.nextBoolean()) { c[0] = 0; c[1] = 1; c[2] = 0; c[3] = (byte) r.nextInt(2); }
        return c;
      case 3:                                                              // huge counts and offsets
        c = s.clone();
        for (int k = 1 + r.nextInt(8); k > 0 && c.length > 1; k--) {
          int p = (r.nextBoolean() ? r.nextInt(Math.min(c.length, 4096)) : r.nextInt(c.length)) & ~1;
          int v = new int[] { 0xFFFF, 0x7FFF, 0x8000, 0xFFFE, r.nextInt(65536) }[r.nextInt(5)];
          if (p + 1 < c.length) Sfnt.w16(c, p, v);
        }
        return c;
      default:                                                             // a word copied elsewhere: shared and overlapping data
        c = s.clone();
        for (int k = 1 + r.nextInt(6); k > 0 && c.length > 3; k--) {
          int from = r.nextInt(c.length - 1) & ~1, to = r.nextInt(Math.min(c.length - 1, 4096)) & ~1;
          c[to] = c[from];
          c[to + 1] = c[from + 1];
        }
        return c;
    }
  }

  /** {@code count} damaged tables from {@code seed}; with {@code limit} > 0, a run still going after that many seconds is a hang. */
  static void fuzz(File fix, long seed, int count, final int limit) throws Exception {
    final long started = System.nanoTime();
    Thread watchdog = new Thread(() -> {
      try { Thread.sleep(limit * 1000L); } catch (InterruptedException e) { return; }
      System.out.println("FAIL fuzz: still running after " + limit + " s (a hang)");
      System.exit(1);
    });
    watchdog.setDaemon(true);
    if (limit > 0) watchdog.start();
    List<byte[]> gsubs = new ArrayList<>(), gposs = new ArrayList<>(), gdefs = new ArrayList<>();
    for (String name : new String[] { "Inter-sub.ttf", "NotoSans-sub.ttf", "RobotoFlex-sub.ttf", "NotoColorEmoji-sub.ttf", "Twemoji-sub.ttf" }) {
      Sfnt f = font(fix, name);
      if (f.has("GSUB")) gsubs.add(f.table("GSUB"));
      if (f.has("GPOS")) gposs.add(f.table("GPOS"));
      if (f.has("GDEF")) gdefs.add(f.table("GDEF"));
    }
    gsubs.add(handGsubTable().bytes());
    gsubs.add(fvTable().bytes());
    gposs.add(handGposTable().bytes());
    gposs.add(paramsTable().bytes());
    gdefs.add(handGdef().bytes());
    VarStore store = OtLayout.gdefVarStore(font(fix, "NotoSans-sub.ttf").table("GDEF"));
    OtLayout.Table text = OtLayout.parse(font(fix, "NotoSans-sub.ttf").table("GSUB"), true);
    Random r = new Random(seed);
    int runs = 0, refused = 0;
    List<String> bad = new ArrayList<>();
    for (int iter = 0; iter < count; iter++) {
      boolean gsub = r.nextBoolean();
      List<byte[]> pool = gsub ? gsubs : gposs;
      byte[] t = mutate(r, pool.get(r.nextInt(pool.size())));
      int[] coords = new int[13];
      for (int i = 0; i < coords.length; i++) coords[i] = r.nextInt(32769) - 16384;
      int step = r.nextInt(gsub ? 5 : 6);
      runs++;
      try {
        switch (step) {
          case 0: OtLayout.write(OtLayout.parse(t, gsub), null); break;
          case 1: OtLayout.shiftGlyphs(t, gsub, r.nextInt(3000), null); break;
          case 2: OtLayout.shiftLookupIndexes(t, gsub, r.nextInt(300), null); break;
          case 3: { OtLayout.Table m = OtLayout.parse(t, gsub); OtLayout.applyFeatureVariations(m, coords); OtLayout.write(m, null); break; }
          case 4: {
            if (gsub) { OtLayout.write(OtLayout.merge(text, OtLayout.parse(t, true), "ccmp", null), null); break; }
            byte[] g = mutate(r, gdefs.get(r.nextInt(gdefs.size())));
            VarStore v = OtLayout.gdefVarStore(g);
            if (v != null) OtLayout.applyGposDeltas(gposs.get(r.nextInt(gposs.size())).clone(), v.at(coords), null);
            OtLayout.instanceGdef(g, v);
            break;
          }
          default: OtLayout.applyGposDeltas(t, store.at(coords), null); break;
        }
      } catch (Sfnt.FontException e) {
        refused++;
      } catch (Throwable e) {
        if (bad.size() < 3) bad.add("iteration " + iter + " step " + step + ": " + e);
      }
    }
    watchdog.interrupt();
    double secs = (System.nanoTime() - started) / 1e9;
    check(String.format("fuzz (seed %x): %d damaged GSUB / GPOS / GDEF tables through parse, write, both shifts, FeatureVariations, merge, GPOS deltas and "
        + "GDEF in %.1f s; %d refused with a FontException, nothing else thrown", seed, runs, secs, refused), bad.isEmpty() && (limit == 0 || secs < limit / 2),
        String.valueOf(bad));
  }
}
