package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * EmojiMerge, the colour emoji merge of the Custom Fonts engine. Against tests/java/fixtures/fonts/emojimerge: three text fonts (subsets of
 * Lato, of an Inter instance and of a Noto Sans instance with a kern table instead of GSUB / GPOS / GDEF) and four emoji fonts (subsets of
 * Noto Color Emoji COLRv1 and Twemoji COLRv0, a synthetic COLRv1 font with every paint format and a synthetic hinted COLRv0 one), merged in
 * the nine pairs of make_fixtures.py. Each merged font is checked table by table against its two sources, then shaped: a small GSUB applier
 * written for this suite (HarfBuzz's rules for its default shaper, checked first against what HarfBuzz gives for every source font alone,
 * in expected.txt) must give the emoji font's glyphs (plus the text font's glyph count) for each emoji sequence and the text font's glyphs
 * for text. make_fixtures.py verify checked the merged fonts themselves with HarfBuzz, FreeType and fontTools and recorded their SHA-256;
 * a merged font with another hash is noted, not failed (the other checks still run). Then the refusals, the options, progress and Cancel,
 * hand-built tables, and a seeded fuzz of both inputs.
 *
 * <p>{@code --dump DIR} writes the merged fonts for {@code make_fixtures.py verify DIR}; {@code --fuzz SEED COUNT} runs only the fuzz,
 * longer or with another seed.
 */
public class EmojiMergeTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  static void check(String name, boolean ok, String detail) { check(ok || detail == null ? name : name + " -- " + detail, ok); }
  static void note(String s) { System.out.println("NOTE " + s); }

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

  static void throwsArg(String name, Body body) {
    try {
      body.run();
      check(name, false, "no exception");
    } catch (IllegalArgumentException e) {
      check(name, true);
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

  // ---------- fixtures and expected.txt ----------

  static final String LATO = "Lato-sub.ttf", INTER = "Inter-sub.ttf", NOTOSANS = "NotoSans-kern.ttf", NOTO = "Noto-sub.ttf",
      TWEMOJI = "Twemoji-sub.ttf", SYNTH1 = "Synth-COLRv1.ttf", SYNTH0 = "Synth-COLRv0.ttf", NO_GDEF = "/noGDEF";
  static final String[] TEXTS = { LATO, INTER, NOTOSANS }, ALL = { LATO, INTER, NOTOSANS, NOTO, TWEMOJI, SYNTH1, SYNTH0 };
  /** The pairs make_fixtures.py verify checks (text, emoji). */
  static final List<String[]> MERGES = new ArrayList<>();
  static {
    for (String t : TEXTS) for (String e : new String[] { NOTO, TWEMOJI }) MERGES.add(new String[] { t, e });
    MERGES.add(new String[] { INTER, SYNTH1 });
    MERGES.add(new String[] { NOTOSANS, SYNTH1 });
    MERGES.add(new String[] { LATO, SYNTH0 });
  }
  static final Map<String, byte[]> files = new LinkedHashMap<>();

  static void load(File dir) throws IOException { for (String n : ALL) files.put(n, Files.readAllBytes(new File(dir, n).toPath())); }

  /** A fresh copy of a fixture. */
  static Sfnt font(String name) throws Sfnt.FontException { return Sfnt.parse(files.get(name)); }

  static String mergedName(String t, String e) { return t.substring(0, t.length() - 4) + "+" + e; }

  static File fixtureDir() {
    String p = System.getProperty("fixtures");
    if (p != null && new File(p, "fonts/emojimerge").isDirectory()) return new File(p, "fonts/emojimerge");
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts/emojimerge");
    return null;
  }

  /** expected.txt: see make_fixtures.py for the format. */
  static final class Expected {
    final Map<String, String[]> font = new LinkedHashMap<>();              // name -> SHA-256, glyph count, units per em
    final Map<String, Map<String, int[]>> seq = new LinkedHashMap<>();      // font -> "1F600 FE0F" -> HarfBuzz's glyph ids
    final List<String[]> runs = new ArrayList<>();                        // font, script, code points, glyph ids
    final Map<String, String> verified = new HashMap<>();                 // merged font name -> SHA-256

    static Expected read(File f) throws IOException {
      Expected e = new Expected();
      for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
        if (l.trim().isEmpty() || l.startsWith("#")) continue;
        String[] w = l.trim().split(" ");
        switch (w[0]) {
          case "font": e.font.put(w[1], new String[] { w[2], w[3], w[4] }); break;
          case "verified": e.verified.put(w[1], w[2]); break;
          case "seq": case "run": {
            int colon = l.indexOf(" :");
            String[] left = l.substring(0, colon).split(" ");
            String right = l.substring(colon + 2).trim();
            if (w[0].equals("seq")) {
              Map<String, int[]> m = e.seq.get(left[1]);
              if (m == null) e.seq.put(left[1], m = new LinkedHashMap<>());
              m.put(String.join(" ", Arrays.copyOfRange(left, 2, left.length)), ints(right));
            } else {
              e.runs.add(new String[] { left[1], left[2], String.join(" ", Arrays.copyOfRange(left, 3, left.length)), right });
            }
            break;
          }
          default: throw new IOException("expected.txt: unknown line " + l);
        }
      }
      return e;
    }
  }

  static int[] ints(String s) {
    s = s.trim();
    if (s.isEmpty()) return new int[0];
    String[] w = s.split(" ");
    int[] r = new int[w.length];
    for (int i = 0; i < w.length; i++) r[i] = Integer.parseInt(w[i]);
    return r;
  }

  static int[] cps(String hex) {
    String[] w = hex.trim().split(" ");
    int[] r = new int[w.length];
    for (int i = 0; i < w.length; i++) r[i] = Integer.parseInt(w[i], 16);
    return r;
  }

  static String hex(int[] cps) {
    StringBuilder b = new StringBuilder();
    for (int c : cps) b.append(b.length() == 0 ? "" : " ").append(Integer.toHexString(c).toUpperCase());
    return b.toString();
  }

  static String sha(byte[] b) throws Exception {
    StringBuilder s = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
    return s.toString();
  }

  static Sfnt without(Sfnt f, String... tags) {
    Sfnt c = f.copy();
    for (String t : tags) c.remove(t);
    return c;
  }

  /** Fixture {@code name} with empty glyphs added up to {@code glyphs} glyphs (its cmap as it was). */
  static Sfnt padded(String name, int glyphs) throws Sfnt.FontException {
    Sfnt f = font(name);
    byte[][] g = Arrays.copyOf(Glyf.readAll(f), glyphs);
    Glyf.Metrics fm = Glyf.Metrics.read(f), mm = new Glyf.Metrics(g.length);
    System.arraycopy(fm.advance, 0, mm.advance, 0, fm.advance.length);
    System.arraycopy(fm.lsb, 0, mm.lsb, 0, fm.lsb.length);
    Glyf.write(f, g);
    mm.write(f, g);
    return f;
  }

  static int[] shifted(int[] ids, int n) {
    int[] r = new int[ids.length];
    for (int i = 0; i < ids.length; i++) r[i] = ids[i] == 0 ? 0 : ids[i] + n;            // .notdef stays .notdef
    return r;
  }

  static int[] toArray(List<Integer> l) {
    int[] a = new int[l.size()];
    for (int i = 0; i < a.length; i++) a[i] = l.get(i);
    return a;
  }

  static int u8(byte[] t, long o) throws Sfnt.FontException { return Sfnt.u8(t, (int) o); }
  static int u16(byte[] t, long o) throws Sfnt.FontException { return Sfnt.u16(t, (int) o); }
  static int s16(byte[] t, long o) throws Sfnt.FontException { return Sfnt.s16(t, (int) o); }
  static int u24(byte[] t, long o) throws Sfnt.FontException { return Sfnt.u24(t, (int) o); }
  static long u32(byte[] t, long o) throws Sfnt.FontException { return Sfnt.u32(t, (int) o); }

  // ---------- the rules the merge follows ----------

  /** Characters U+FE0F asks the emoji form of: above Latin-1 all, in Latin-1 # * 0-9 (c) (R). */
  static boolean emojiBase(int cp) { return cp > 0xFF || cp == '#' || cp == '*' || (cp >= '0' && cp <= '9') || cp == 0xA9 || cp == 0xAE; }

  /** True when the merged font must shape {@code cps} as the text font alone does (else as the emoji font alone). */
  static boolean textExpected(int[] cps, Map<Integer, Integer> tcmap) {
    int c = cps[0];
    if (!tcmap.containsKey(c)) return false;
    if (cps.length == 1) return !EmojiData.emojiWins(c);
    if (cps.length == 2 && cps[1] == 0xFE0E) return true;
    if (cps.length == 2 && cps[1] == 0xFE0F) return !EmojiData.emojiWins(c) && !emojiBase(c);
    return false;
  }

  // ---------- the suite's GSUB applier ----------

  static final int BASE = 0x02, LIG = 0x04, MARK = 0x08;
  static final String[] FEATURES = { "rvrn", "ltra", "ltrm", "rand", "trak", "Harf", "HARF", "Buzz", "BUZZ", "abvm", "blwm", "ccmp", "locl",
      "mark", "mkmk", "rlig", "calt", "clig", "curs", "dist", "kern", "liga", "rclt" };

  interface Match { boolean test(int i, int glyph); }

  /**
   * The GSUB part of HarfBuzz's default shaper (left to right; the Latin and the Common script) for one font, as far as these fixtures
   * need it: the cmap with variation sequences (a found sequence takes its selector in), glyph classes from GDEF or, without them, from
   * Unicode (a non-spacing mark that is not default-ignorable is a mark), the default features in HarfBuzz's two GSUB stages (rvrn, then
   * the others by lookup index; the first feature of a tag in the LangSys counts), lookup types 1-6 with their flags and mark filtering
   * sets, HarfBuzz's skipping (a default-ignorable that does not match is passed over, except ZWNJ and tag characters in the input; ZWJ
   * matches where a rule names it), ligatures that put the glyphs they passed over after themselves, MultipleSubst outputs that keep the
   * input's character, and at the end the removal of the default-ignorables no lookup produced. Not emulated: reverse chaining (type 8),
   * ligature component ids, required features, script shapers; a font that needs one stops the applier.
   */
  static final class Shaper {
    final TreeMap<Integer, Integer> cmap;
    final Cmap.Uvs uvs;
    final OtLayout.Table gsub;
    final Gdef gdef;
    final boolean classes;
    long ops;

    Shaper(Sfnt f) throws Sfnt.FontException {
      cmap = Cmap.read(f, null);
      uvs = Cmap.readUvs(f);
      gsub = f.has("GSUB") ? OtLayout.parse(f.table("GSUB"), true) : null;
      gdef = f.has("GDEF") ? new Gdef(f.table("GDEF")) : null;
      classes = gdef != null && gdef.glyphClass != 0;
    }

    static final class G {
      int gid, cp, props;
      boolean ignorable, zwj, zwnj, hidden, substituted;

      G copy() {
        G c = new G();
        c.gid = gid; c.cp = cp; c.props = props; c.ignorable = ignorable; c.zwj = zwj; c.zwnj = zwnj; c.hidden = hidden; c.substituted = substituted;
        return c;
      }
    }

    int[] shape(int[] cps, String script) throws Sfnt.FontException {
      List<G> buf = map(cps);
      if (gsub != null) for (TreeSet<Integer> stage : stages(script)) for (int li : stage) apply(buf, li);
      List<Integer> out = new ArrayList<>();
      for (G g : buf) if (!(g.ignorable && !g.substituted)) out.add(g.gid);
      return toArray(out);
    }

    static boolean defaultIgnorable(int c) {
      return c == 0xAD || c == 0x34F || c == 0x61C || (c >= 0x17B4 && c <= 0x17B5) || (c >= 0x180B && c <= 0x180E) || (c >= 0x200B && c <= 0x200F)
          || (c >= 0x202A && c <= 0x202E) || (c >= 0x2060 && c <= 0x206F) || (c >= 0xFE00 && c <= 0xFE0F) || c == 0xFEFF || (c >= 0xFFF0 && c <= 0xFFF8)
          || (c >= 0x1D173 && c <= 0x1D17A) || (c >= 0xE0000 && c <= 0xE0FFF);
    }

    static boolean isVs(int c) { return (c >= 0xFE00 && c <= 0xFE0F) || (c >= 0xE0100 && c <= 0xE01EF) || (c >= 0x180B && c <= 0x180D) || c == 0x180F; }

    static boolean isMark(int c) {
      int t = Character.getType(c);
      return t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.COMBINING_SPACING_MARK;
    }

    G glyph(int cp, int gid) throws Sfnt.FontException {
      G g = new G();
      g.cp = cp; g.gid = gid;
      g.ignorable = defaultIgnorable(cp);
      g.zwj = cp == 0x200D; g.zwnj = cp == 0x200C;
      g.hidden = g.ignorable && ((cp >= 0x180B && cp <= 0x180D) || cp == 0x180F || (cp >= 0xE0020 && cp <= 0xE007F) || cp == 0x34F);
      g.props = classes ? gdef.props(gid) : Character.getType(cp) == Character.NON_SPACING_MARK && !g.ignorable ? MARK : BASE;
      return g;
    }

    int nominal(int cp) { Integer g = cmap.get(cp); return g == null ? 0 : g; }

    int variant(int cp, int vs) {
      int g = uvs.glyph(vs, cp);
      if (g >= 0) return g;
      if (uvs.isDefault(vs, cp)) { Integer n = cmap.get(cp); return n == null ? -1 : n; }
      return -1;
    }

    /** HarfBuzz's normalizer as far as these strings need: a base and its marks; a found variation sequence is one glyph. */
    List<G> map(int[] cps) throws Sfnt.FontException {
      List<G> buf = new ArrayList<>();
      for (int i = 0; i < cps.length; ) {
        int end = i + 1;
        while (end < cps.length && isMark(cps[end])) end++;
        boolean vs = false;
        for (int k = i; k < end; k++) vs |= isVs(cps[k]);
        if (!vs) {
          for (int k = i; k < end; k++) buf.add(glyph(cps[k], nominal(cps[k])));
          i = end;
          continue;
        }
        int idx = i;
        while (idx < end - 1) {
          if (isVs(cps[idx + 1])) {
            int v = variant(cps[idx], cps[idx + 1]);
            if (v >= 0) buf.add(glyph(cps[idx], v));
            else { buf.add(glyph(cps[idx], nominal(cps[idx]))); buf.add(glyph(cps[idx + 1], nominal(cps[idx + 1]))); }
            idx += 2;
            while (idx < end && isVs(cps[idx])) { buf.add(glyph(cps[idx], nominal(cps[idx]))); idx++; }
          } else {
            buf.add(glyph(cps[idx], nominal(cps[idx])));
            idx++;
          }
        }
        if (idx < end) buf.add(glyph(cps[idx], nominal(cps[idx])));
        i = end;
      }
      return buf;
    }

    List<TreeSet<Integer>> stages(String script) {
      String[] tags = script.equals("Latn") ? new String[] { "latn", "DFLT", "dflt" } : new String[] { "zyyy", "DFLT", "dflt", "latn" };
      OtLayout.Script s = null;
      for (String tag : tags) if ((s = gsub.script(tag)) != null) break;
      TreeSet<Integer> one = new TreeSet<>(), two = new TreeSet<>();
      if (s != null && s.dflt != null) {
        OtLayout.LangSys ls = s.dflt;
        if (ls.required != 0xFFFF) throw new IllegalStateException("the font has a required feature, which the applier does not emulate");
        for (String tag : FEATURES)
          for (int fi : ls.features) {
            if (fi >= gsub.features.size() || !gsub.features.get(fi).tag.equals(tag)) continue;
            if (tag.equals("rand")) throw new IllegalStateException("the font has a rand feature, which the applier does not emulate");
            for (int li : gsub.features.get(fi).lookups) if (li < gsub.lookups.size()) (tag.equals("rvrn") ? one : two).add(li);
            break;
          }
      }
      return Arrays.asList(one, two);
    }

    static int props(OtLayout.Lookup l) { return l.flag | ((l.flag & 0x10) != 0 ? l.markFilteringSet << 16 : 0); }

    /** HarfBuzz's check_glyph_property. */
    boolean property(G g, int props) throws Sfnt.FontException {
      if ((g.props & props & 0x0E) != 0) return false;
      if ((g.props & MARK) != 0) {
        if ((props & 0x10) != 0) return gdef != null && gdef.inSet(props >>> 16, g.gid);
        if ((props & 0xFF00) != 0) return (props & 0xFF00) == (g.props & 0xFF00);
      }
      return true;
    }

    /** HarfBuzz's skipping iterator: the next ({@code dir} 1) or previous (-1) glyph that matches, or -1. */
    int skip(List<G> buf, int from, int dir, int props, boolean context, IntPredicate match) throws Sfnt.FontException {
      for (int i = from + dir; i >= 0 && i < buf.size(); i += dir) {
        G g = buf.get(i);
        if (!property(g, props)) continue;
        boolean maybe = g.ignorable && !g.substituted && (context || !g.zwnj) && !g.hidden;
        if (match.test(g.gid)) return i;
        if (!maybe) return -1;
      }
      return -1;
    }

    void apply(List<G> buf, int li) throws Sfnt.FontException {
      OtLayout.Lookup l = gsub.lookups.get(li);
      if (l.type == 8) throw new IllegalStateException("lookup " + li + " is a reverse chaining one, which the applier does not emulate");
      int props = props(l);
      for (int i = 0; i < buf.size(); ) {
        if (++ops > 2000000) throw new IllegalStateException("the applier went round too often");
        int next = property(buf.get(i), props) ? at(buf, li, i, 0) : -1;
        i = next >= 0 ? next : i + 1;
      }
    }

    /** Lookup {@code li} at {@code pos}: its first subtable that applies. The position after it, or -1. */
    int at(List<G> buf, int li, int pos, int depth) throws Sfnt.FontException {
      if (li >= gsub.lookups.size() || depth > 64) return -1;
      OtLayout.Lookup l = gsub.lookups.get(li);
      for (OtLayout.Subtable st : l.subtables) {
        int r = subtable(buf, l.type, gsub.sources.get(st.source), st.offset, pos, props(l), depth);
        if (r >= 0) return r;
      }
      return -1;
    }

    void replace(G g, int gid, int classGuess) throws Sfnt.FontException {
      g.substituted = true;
      if (classes) g.props = gdef.props(gid);
      else if (classGuess != 0) g.props = classGuess;
      g.gid = gid;
    }

    int subtable(List<G> buf, int type, byte[] t, int off, int pos, int props, int depth) throws Sfnt.FontException {
      G cur = buf.get(pos);
      int fmt = u16(t, off);
      switch (type) {
        case 1: {
          int ci = cov(t, off + u16(t, off + 2), cur.gid);
          if (ci < 0) return -1;
          int g;
          if (fmt == 1) g = (cur.gid + s16(t, off + 4)) & 0xFFFF;
          else if (fmt == 2 && ci < u16(t, off + 4)) g = u16(t, off + 6 + 2 * ci);
          else return -1;
          replace(cur, g, 0);
          return pos + 1;
        }
        case 2: case 3: {
          int ci = fmt == 1 ? cov(t, off + u16(t, off + 2), cur.gid) : -1;
          if (ci < 0 || ci >= u16(t, off + 4)) return -1;
          int seq = off + u16(t, off + 6 + 2 * ci), n = u16(t, seq);
          if (type == 3 || n == 1) {
            if (n == 0) return -1;
            replace(cur, u16(t, seq + 2), 0);
            return pos + 1;
          }
          if (n == 0) { buf.remove(pos); return pos; }
          int klass = (cur.props & LIG) != 0 ? BASE : 0;
          List<G> outs = new ArrayList<>();
          for (int k = 0; k < n; k++) {
            G c = cur.copy();
            replace(c, u16(t, seq + 2 + 2 * k), klass);
            outs.add(c);
          }
          buf.remove(pos);
          buf.addAll(pos, outs);
          return pos + n;
        }
        case 4: {
          int ci = fmt == 1 ? cov(t, off + u16(t, off + 2), cur.gid) : -1;
          if (ci < 0 || ci >= u16(t, off + 4)) return -1;
          int set = off + u16(t, off + 6 + 2 * ci), ligs = u16(t, set);
          for (int k = 0; k < ligs; k++) {
            int lig = set + u16(t, set + 2 + 2 * k), glyph = u16(t, lig), comps = u16(t, lig + 2);
            if (comps == 0) continue;
            int[] want = new int[comps];
            for (int c = 1; c < comps; c++) want[c] = u16(t, lig + 2 + 2 * c);
            int[] p = input(buf, pos, comps, props, (i, g) -> g == want[i]);
            if (p == null) continue;
            return ligate(buf, p, glyph);
          }
          return -1;
        }
        case 5: case 6: return context(buf, t, off, pos, props, depth, type == 6);
        default: return -1;
      }
    }

    int[] input(List<G> buf, int pos, int count, int props, Match m) throws Sfnt.FontException {
      int[] p = new int[count];
      p[0] = pos;
      for (int i = 1; i < count; i++) {
        final int k = i;
        int q = skip(buf, p[i - 1], 1, props, false, g -> m.test(k, g));
        if (q < 0) return null;
        p[i] = q;
      }
      return p;
    }

    int ligate(List<G> buf, int[] p, int glyph) throws Sfnt.FontException {
      G first = buf.get(p[0]);
      boolean base = (first.props & BASE) != 0, mark = (first.props & MARK) != 0;
      for (int i = 1; i < p.length; i++) if ((buf.get(p[i]).props & MARK) == 0) { base = false; mark = false; break; }
      replace(first, glyph, !base && !mark ? LIG : 0);
      for (int i = p.length - 1; i >= 1; i--) buf.remove(p[i]);
      return p[p.length - 1] + 1 - (p.length - 1);
    }

    int context(List<G> buf, byte[] t, int off, int pos, int props, int depth, boolean chain) throws Sfnt.FontException {
      int fmt = u16(t, off), gid = buf.get(pos).gid;
      if (fmt == 3) {
        int at = off + 2, nb = 0, back = 0, ahead = 0, na = 0;
        if (chain) { nb = u16(t, at); back = at + 2; at += 2 + 2 * nb; }
        int ni = u16(t, at), in = at + (chain ? 2 : 4);
        int nl = chain ? -1 : u16(t, at + 2);
        at = in + 2 * ni;
        if (chain) { na = u16(t, at); ahead = at + 2; at += 2 + 2 * na; nl = u16(t, at); at += 2; }
        if (ni == 0 || cov(t, off + u16(t, in), gid) < 0) return -1;
        final int fb = back, fi = in, fa = ahead;
        Match mi = (i, g) -> cov(t, off + u16q(t, fi + 2 * i), g) >= 0;
        Match mb = (i, g) -> cov(t, off + u16q(t, fb + 2 * i), g) >= 0;
        Match ma = (i, g) -> cov(t, off + u16q(t, fa + 2 * i), g) >= 0;
        return rule(buf, pos, props, depth, ni, mi, nb, mb, na, ma, records(t, at, nl));
      }
      if (fmt != 1 && fmt != 2) return -1;
      int ci = cov(t, off + u16(t, off + 2), gid);
      if (ci < 0) return -1;
      int sets, arr, set;
      int bcd = -1, icd = -1, acd = -1;
      if (fmt == 1) { sets = u16(t, off + 4); arr = off + 6; set = ci; }
      else if (!chain) { icd = off + u16(t, off + 4); sets = u16(t, off + 6); arr = off + 8; set = classOf(t, icd, gid); }
      else {
        bcd = u16(t, off + 4) == 0 ? -1 : off + u16(t, off + 4);
        icd = u16(t, off + 6) == 0 ? -1 : off + u16(t, off + 6);
        acd = u16(t, off + 8) == 0 ? -1 : off + u16(t, off + 8);
        sets = u16(t, off + 10); arr = off + 12; set = classOf(t, icd, gid);
      }
      if (set >= sets || u16(t, arr + 2 * set) == 0) return -1;
      int rs = off + u16(t, arr + 2 * set), rules = u16(t, rs);
      final int fbcd = bcd, ficd = icd, facd = acd;
      for (int r = 0; r < rules; r++) {
        int at = rs + u16(t, rs + 2 + 2 * r), nb = 0, back = 0, na = 0, ahead = 0;
        if (chain) { nb = u16(t, at); back = at + 2; at += 2 + 2 * nb; }
        int ni = u16(t, at), nl;
        if (chain) { at += 2; } else { nl = u16(t, at + 2); at += 4; }
        int in = at;
        at += 2 * Math.max(0, ni - 1);
        if (chain) { na = u16(t, at); ahead = at + 2; at += 2 + 2 * na; }
        nl = u16(t, chain ? at : in - 2);
        if (chain) at += 2;
        if (ni == 0) continue;
        final int fb = back, fi = in, fa = ahead;
        Match mi, mb, ma;
        if (fmt == 1) {
          mi = (i, g) -> g == u16q(t, fi + 2 * (i - 1));
          mb = (i, g) -> g == u16q(t, fb + 2 * i);
          ma = (i, g) -> g == u16q(t, fa + 2 * i);
        } else {
          mi = (i, g) -> classOf(t, ficd, g) == u16q(t, fi + 2 * (i - 1));
          mb = (i, g) -> classOf(t, fbcd, g) == u16q(t, fb + 2 * i);
          ma = (i, g) -> classOf(t, facd, g) == u16q(t, fa + 2 * i);
        }
        int res = rule(buf, pos, props, depth, ni, mi, nb, mb, na, ma, records(t, at, nl));
        if (res >= 0) return res;
      }
      return -1;
    }

    static int[][] records(byte[] t, int at, int n) throws Sfnt.FontException {
      int[][] r = new int[n][];
      for (int i = 0; i < n; i++) r[i] = new int[] { u16(t, at + 4 * i), u16(t, at + 4 * i + 2) };
      return r;
    }

    int rule(List<G> buf, int pos, int props, int depth, int ni, Match mi, int nb, Match mb, int na, Match ma, int[][] recs) throws Sfnt.FontException {
      int[] p;
      try {
        p = input(buf, pos, ni, props, mi);
        if (p == null) return -1;
        int end = p[ni - 1];
        for (int i = 0; i < na; i++) {
          final int k = i;
          if ((end = skip(buf, end, 1, props, true, g -> ma.test(k, g))) < 0) return -1;
        }
        int b = pos;
        for (int i = 0; i < nb; i++) {
          final int k = i;
          if ((b = skip(buf, b, -1, props, true, g -> mb.test(k, g))) < 0) return -1;
        }
      } catch (Unchecked e) {
        throw e.cause;
      }
      return records(buf, p, ni, recs, p[ni - 1] + 1, depth);
    }

    /** HarfBuzz's apply_lookup: the nested lookups at their positions, the positions moved as they change the length. */
    int records(List<G> buf, int[] p0, int count, int[][] recs, int end, int depth) throws Sfnt.FontException {
      int[] pos = Arrays.copyOf(p0, 64);
      for (int[] r : recs) {
        int idx = r[0];
        if (idx >= count) continue;
        int orig = buf.size();
        if (pos[idx] >= orig) continue;
        if (at(buf, r[1], pos[idx], depth + 1) < 0) continue;
        int delta = buf.size() - orig;
        if (delta == 0) continue;
        end += delta;
        if (end < pos[idx]) { delta += pos[idx] - end; end = pos[idx]; }
        int next = idx + 1;
        if (delta > 0) { if (delta + count > 64) break; }
        else { delta = Math.max(delta, next - count); next -= delta; }
        System.arraycopy(pos, next, pos, next + delta, count - next);
        next += delta;
        count += delta;
        for (int j = idx + 1; j < next; j++) pos[j] = pos[j - 1] + 1;
        for (; next < count; next++) pos[next] += delta;
      }
      return end;
    }
  }

  /** A FontException thrown from inside a matching lambda. */
  static final class Unchecked extends RuntimeException {
    final Sfnt.FontException cause;
    Unchecked(Sfnt.FontException c) { super(c); cause = c; }
  }

  static int cov(byte[] t, int off, int g) {
    try {
      int fmt = Sfnt.u16(t, off), n = Sfnt.u16(t, off + 2);
      if (fmt == 1) {
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
          int mid = (lo + hi) >>> 1, v = Sfnt.u16(t, off + 4 + 2 * mid);
          if (v < g) lo = mid + 1; else if (v > g) hi = mid - 1; else return mid;
        }
      } else if (fmt == 2) {
        for (int i = 0; i < n; i++) {
          int r = off + 4 + 6 * i, a = Sfnt.u16(t, r), b = Sfnt.u16(t, r + 2);
          if (g >= a && g <= b) return Sfnt.u16(t, r + 4) + g - a;
        }
      }
      return -1;
    } catch (Sfnt.FontException e) {
      throw new Unchecked(e);
    }
  }

  static int classOf(byte[] t, int off, int g) {
    if (off < 0) return 0;
    try {
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
    } catch (Sfnt.FontException e) {
      throw new Unchecked(e);
    }
  }

  static int u16q(byte[] t, int off) {
    try { return Sfnt.u16(t, off); } catch (Sfnt.FontException e) { throw new Unchecked(e); }
  }

  /** GDEF as HarfBuzz reads it for GSUB: glyph classes, mark attachment classes, mark glyph sets. */
  static final class Gdef {
    final byte[] t;
    final int minor, glyphClass, attach, carets, markAttach, markSets;

    Gdef(byte[] t) throws Sfnt.FontException {
      this.t = t;
      minor = u16(t, 2);
      glyphClass = u16(t, 4); attach = u16(t, 6); carets = u16(t, 8); markAttach = u16(t, 10);
      markSets = minor >= 2 ? u16(t, 12) : 0;
    }

    int cls(int g) { return glyphClass == 0 ? 0 : classOf(t, glyphClass, g); }
    int attachClass(int g) { return markAttach == 0 ? 0 : classOf(t, markAttach, g); }

    int props(int g) {
      switch (cls(g)) {
        case 1: return BASE;
        case 2: return LIG;
        case 3: return MARK | attachClass(g) << 8;
        default: return 0;
      }
    }

    int sets() throws Sfnt.FontException { return markSets == 0 ? 0 : u16(t, markSets + 2); }

    boolean inSet(int set, int g) throws Sfnt.FontException {
      if (set >= sets()) return false;
      return cov(t, (int) (markSets + u32(t, markSets + 4 + 4 * set)), g) >= 0;
    }
  }

  // ---------- COLR as the test reads it ----------

  /** Record size of each paint format. */
  static final int[] PAINT_SIZE = { 0, 6, 5, 9, 16, 20, 16, 20, 12, 16, 6, 3, 7, 7, 8, 12, 8, 12, 12, 16, 6, 10, 10, 14, 6, 10, 10, 14, 8, 12, 12, 16, 8 };

  static final class Colr {
    final byte[] t;
    final int version, numBase, numLayers;
    final long base, layers, bgl, ll, cl;

    Colr(byte[] t) throws Sfnt.FontException {
      this.t = t;
      version = u16(t, 0); numBase = u16(t, 2); base = u32(t, 4); layers = u32(t, 8); numLayers = u16(t, 12);
      bgl = version >= 1 ? u32(t, 14) : 0; ll = version >= 1 ? u32(t, 18) : 0; cl = version >= 1 ? u32(t, 22) : 0;
    }

    int baseCount() throws Sfnt.FontException { return bgl == 0 ? 0 : (int) u32(t, bgl); }
    int baseGlyph(int i) throws Sfnt.FontException { return u16(t, bgl + 4 + 6 * i); }
    int basePaint(int i) throws Sfnt.FontException { return (int) (bgl + u32(t, bgl + 4 + 6 * i + 2)); }
    int layerCount() throws Sfnt.FontException { return ll == 0 ? 0 : (int) u32(t, ll); }
    int layerPaint(int i) throws Sfnt.FontException { return (int) (ll + u32(t, ll + 4 + 4 * i)); }
    int clipCount() throws Sfnt.FontException { return cl == 0 ? 0 : (int) u32(t, cl + 1); }

    /** Clip record i: start glyph, end glyph, box format, x0, y0, x1, y1, var index (or -1). */
    long[] clip(int i) throws Sfnt.FontException {
      long rec = cl + 5 + 7 * i, box = cl + u24(t, rec + 4);
      int fmt = u8(t, box);
      return new long[] { u16(t, rec), u16(t, rec + 2), fmt, s16(t, box + 1), s16(t, box + 3), s16(t, box + 5), s16(t, box + 7), fmt == 2 ? u32(t, box + 9) : -1 };
    }

    interface Ids { int map(int fmt, int g); }

    /**
     * The paint graph under the paint at {@code at}, depth first: "format glyph fields" per paint, where fields are the paint's own bytes
     * (offsets blanked, gradients with their colour line, transforms with their matrix). With {@code inverse} non-zero, a PaintTransform
     * straight around a PaintColrGlyph is the merge's inverse wrapper: its matrix must be (inverse, 0, 0, inverse, 0, 0) and it is left out.
     */
    void walk(int at, List<String> out, Ids ids, int inverse, StringBuilder bad, int depth) throws Sfnt.FontException {
      if (depth > 64 || out.size() > 200000) throw new Sfnt.FontException("paint graph too deep");
      int fmt = u8(t, at);
      if (fmt < 1 || fmt > 32) throw new Sfnt.FontException("paint format " + fmt);
      byte[] own = Arrays.copyOfRange(t, at, at + PAINT_SIZE[fmt]);
      int glyph = 0;
      List<Integer> kids = new ArrayList<>();
      StringBuilder extra = new StringBuilder();
      if (fmt == 1) {
        long first = u32(t, at + 2);
        for (int k = 0; k < u8(t, at + 1); k++) kids.add(layerPaint((int) (first + k)));
      } else if (fmt >= 4 && fmt <= 9) {
        long line = at + u24(t, at + 1);
        int stops = u16(t, line + 1), size = fmt % 2 == 1 ? 10 : 6;
        for (byte b : Arrays.copyOfRange(t, (int) line, (int) line + 3 + stops * size)) extra.append(String.format("%02x", b));
        blank(own, 1, 3);
      } else if (fmt == 10) {
        glyph = ids.map(10, u16(t, at + 4));
        kids.add(at + u24(t, at + 1));
        blank(own, 1, 3); blank(own, 4, 2);
      } else if (fmt == 11) {
        glyph = ids.map(11, u16(t, at + 1));
        blank(own, 1, 2);
      } else if (fmt == 12 || fmt == 13) {
        int child = at + u24(t, at + 1);
        long m = at + u24(t, at + 4);
        if (inverse != 0 && fmt == 12 && u8(t, child) == 11) {
          long[] want = { inverse, 0, 0, inverse, 0, 0 };
          for (int k = 0; k < 6; k++) if (Sfnt.s32(t, (int) m + 4 * k) != want[k]) bad.append("an inverse wrapper's matrix is not 1/s; ");
          walk(child, out, ids, inverse, bad, depth + 1);
          return;
        }
        for (byte b : Arrays.copyOfRange(t, (int) m, (int) m + (fmt == 12 ? 24 : 28))) extra.append(String.format("%02x", b));
        kids.add(child);
        blank(own, 1, 6);
      } else if (fmt >= 14 && fmt <= 31) {
        kids.add(at + u24(t, at + 1));
        blank(own, 1, 3);
      } else if (fmt == 32) {
        kids.add(at + u24(t, at + 1));
        kids.add(at + u24(t, at + 5));
        blank(own, 1, 3); blank(own, 5, 3);
      }
      StringBuilder s = new StringBuilder().append(fmt).append(' ').append(glyph).append(' ');
      for (byte b : own) s.append(String.format("%02x", b));
      out.add(s.append(extra).toString());
      for (int k : kids) walk(k, out, ids, inverse, bad, depth + 1);
    }

    static void blank(byte[] b, int at, int n) { for (int i = 0; i < n; i++) b[at + i] = 0; }
  }

  // ---------- main ----------

  public static void main(String[] args) throws Exception {
    File fix = fixtureDir();
    if (args.length == 2 && args[0].equals("--dump")) {
      if (fix == null) throw new IOException("fixtures not found");
      load(fix);
      File out = new File(args[1]);
      if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot make " + out);
      for (String[] p : MERGES) {
        byte[] b = EmojiMerge.merge(font(p[0]), font(p[1]), null).font.toBytes();
        Files.write(new File(out, mergedName(p[0], p[1])).toPath(), b);
        System.out.println(mergedName(p[0], p[1]) + " " + b.length + " " + sha(b));
      }
      return;
    }
    if (args.length == 3 && args[0].equals("--fuzz")) {
      if (fix == null) throw new IOException("fixtures not found");
      load(fix);
      fuzz(Long.parseLong(args[1], 16), Integer.parseInt(args[2]), 0);
      System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
      System.exit(fails == 0 ? 0 : 1);
    }
    if (fix == null || !new File(fix, "expected.txt").isFile()) {
      check("the fixtures are found (tests/java/fixtures/fonts/emojimerge)", false);
    } else {
      load(fix);
      final Expected ex = Expected.read(new File(fix, "expected.txt"));
      section("fixtures", () -> fixtures(ex));
      section("the suite's GSUB applier", () -> applier(ex));
      section("merges", () -> merges(ex));
      section("COLR v1", EmojiMergeTest::colrV1);
      section("COLR v0", EmojiMergeTest::colrV0);
      section("glyph classes", EmojiMergeTest::glyphClasses);
      section("U+FE0F left after a key", EmojiMergeTest::strayFe0f);
      section("the text font's variation sequences", () -> textSequences(ex));
      section("options", () -> options(ex));
      section("refusals", EmojiMergeTest::refusals);
      section("damaged emoji tables", EmojiMergeTest::damaged);
      section("progress and cancel", EmojiMergeTest::progressAndCancel);
      section("fuzz", () -> fuzz(0x26102026L, 2500, 25));
    }
    section("hand-built tables", EmojiMergeTest::handBuilt);
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  // ---------- fixtures, and the applier against HarfBuzz ----------

  static void fixtures(Expected ex) throws Exception {
    for (String n : ALL) {
      String[] f = ex.font.get(n);
      Sfnt s = font(n);
      check(n + " is the file expected.txt describes (SHA-256, glyph count, units per em)",
          f != null && f[0].equals(sha(files.get(n))) && Integer.parseInt(f[1]) == s.numGlyphs() && Integer.parseInt(f[2]) == s.unitsPerEm(),
          f == null ? "not in expected.txt" : "run make_fixtures.py expect");
    }
    for (String n : new String[] { LATO, INTER, NOTOSANS, NOTO, TWEMOJI, SYNTH1, SYNTH0, SYNTH1 + NO_GDEF })
      check("expected.txt has HarfBuzz's glyphs for the sequences in " + n, ex.seq.containsKey(n) && ex.seq.get(n).size() >= 14);
    check("expected.txt has the hash of every merged font make_fixtures.py verify passed", ex.verified.size() == MERGES.size(),
        ex.verified.size() + " of " + MERGES.size() + ": run --dump, then make_fixtures.py verify");
  }

  static Shaper shaperFor(String name) throws Sfnt.FontException {
    return new Shaper(name.endsWith(NO_GDEF) ? without(font(name.substring(0, name.length() - NO_GDEF.length())), "GDEF") : font(name));
  }

  static void applier(Expected ex) throws Exception {
    for (Map.Entry<String, Map<String, int[]>> e : ex.seq.entrySet()) {
      Shaper s = shaperFor(e.getKey());
      int bad = 0;
      String first = null;
      for (Map.Entry<String, int[]> q : e.getValue().entrySet()) {
        int[] got = s.shape(cps(q.getKey()), "Zyyy");
        if (!Arrays.equals(got, q.getValue()) && bad++ == 0) first = q.getKey() + ": HarfBuzz " + Arrays.toString(q.getValue()) + ", the applier " + Arrays.toString(got);
      }
      check("the applier shapes the " + e.getValue().size() + " sequences in " + e.getKey() + " alone as HarfBuzz does", bad == 0, bad + " differ, first " + first);
    }
    for (String t : TEXTS) {
      Shaper s = shaperFor(t);
      int bad = 0, n = 0;
      String first = null;
      for (String[] r : ex.runs) {
        if (!r[0].equals(t)) continue;
        n++;
        int[] got = s.shape(cps(r[2]), r[1]);
        if (!Arrays.equals(got, ints(r[3])) && bad++ == 0) first = r[1] + " " + r[2] + ": HarfBuzz " + r[3] + ", the applier " + Arrays.toString(got);
      }
      check("the applier shapes the " + n + " text runs in " + t + " (its liga, calt, ccmp, locl lookups) as HarfBuzz does", n > 0 && bad == 0,
          bad + " differ, first " + first);
    }
  }

  // ---------- each merged pair: tables and shaping ----------

  /** The emoji glyphs only COLR v1 paints draw (PaintGlyph targets and their components). */
  static BitSet paintTargets(Sfnt e, byte[][] eg) throws Exception {
    BitSet t = new BitSet();
    if (!e.has("COLR")) return t;
    Colr c = new Colr(e.table("COLR"));
    if (c.version < 1) return t;
    List<String> sink = new ArrayList<>();
    Colr.Ids collect = (fmt, g) -> { if (fmt == 10) t.set(g); return g; };
    for (int i = 0; i < c.baseCount(); i++) c.walk(c.basePaint(i), sink, collect, 0, new StringBuilder(), 0);
    for (int i = 0; i < c.layerCount(); i++) c.walk(c.layerPaint(i), sink, collect, 0, new StringBuilder(), 0);
    List<Integer> work = new ArrayList<>();
    for (int g = t.nextSetBit(0); g >= 0; g = t.nextSetBit(g + 1)) work.add(g);
    while (!work.isEmpty()) {
      byte[] raw = eg[work.remove(work.size() - 1)];
      if (raw.length < 10 || Sfnt.s16(raw, 0) >= 0) continue;
      for (Glyf.Component k : Glyf.decode(raw).components) if (!t.get(k.glyph)) { t.set(k.glyph); work.add(k.glyph); }
    }
    return t;
  }

  /** The emoji glyphs that get an emoji-units copy: the synthetic COLR v1 font's "both" (drawn by a paint and mapped by the cmap). */
  static int[] copiesOf(String e) { return e.equals(SYNTH1) ? new int[] { 6 } : new int[0]; }

  static final String[] DROPPED = { "hdmx", "LTSH", "VDMX", "DSIG", "vhea", "vmtx", "VORG", "CFF ", "CFF2", "CBDT", "CBLC", "sbix", "SVG ", "EBDT", "EBLC", "morx" };

  static void merges(Expected ex) throws Exception {
    for (String[] pair : MERGES) {
      String t = pair[0], e = pair[1], name = mergedName(t, e);
      Sfnt T = font(t), E = font(e);
      long t0 = System.nanoTime();
      EmojiMerge.Result r = EmojiMerge.merge(T, E, null);
      long ms = (System.nanoTime() - t0) / 1000000;
      byte[] bytes = r.font.toBytes();
      Sfnt M = Sfnt.parse(bytes);
      String h = sha(bytes);
      System.out.println("     " + name + ": merged in " + ms + " ms, " + bytes.length + " bytes, " + M.numGlyphs() + " glyphs, s = " + r.scale);
      if (h.equals(ex.verified.get(name))) check(name + ": is the font make_fixtures.py verify checked with HarfBuzz, FreeType and fontTools", true);
      else note(name + ": not the font make_fixtures.py verify checked (SHA-256 " + h.substring(0, 16) + "...); run --dump DIR and make_fixtures.py verify DIR");
      structure(name, t, e, T, E, M, r);
      shaping(name, ex, t, e, T, E, M);
    }
  }

  static void structure(String name, String t, String e, Sfnt T, Sfnt E, Sfnt M, EmojiMerge.Result r) throws Exception {
    int n = T.numGlyphs(), m = E.numGlyphs();
    int[] copies = copiesOf(e);
    double s = r.scale;
    check(name + ": the result names the glyph counts and the scale (units per em " + T.unitsPerEm() + " / " + E.unitsPerEm() + ")",
        r.textGlyphs == n && r.emojiGlyphs == m && s == Math.round(T.unitsPerEm() / (double) E.unitsPerEm() * 65536) / 65536.0);
    // the copies some (c, U+FE0F) sequences map to, past the emoji-units copies: each draws the emoji glyph of the characters mapped to it
    int base = n + m + copies.length;
    TreeMap<Integer, Integer> ecmap0 = Cmap.read(E, null);
    int[] dupOf = new int[Math.max(0, M.numGlyphs() - base)];
    Arrays.fill(dupOf, -1);
    boolean oneGlyph = true;
    for (Map.Entry<Integer, Integer> x : Cmap.readUvs(M).glyphs(0xFE0F).entrySet()) {
      int j = x.getValue() - base;
      if (j < 0) continue;
      Integer g = ecmap0.get(x.getKey());
      if (j >= dupOf.length || g == null || (dupOf[j] >= 0 && dupOf[j] != g + n)) oneGlyph = false;
      else dupOf[j] = g + n;
    }
    for (int d : dupOf) oneGlyph &= d >= 0;
    check(name + ": " + n + " text + " + m + " emoji + " + copies.length + " emoji-units copies + " + dupOf.length + " U+FE0F copies glyphs, "
        + "each U+FE0F copy named by (c, U+FE0F) of characters with one emoji glyph", M.numGlyphs() >= base && oneGlyph,
        "numGlyphs " + M.numGlyphs());

    byte[][] tg = Glyf.readAll(T), eg = Glyf.readAll(E), mg = Glyf.readAll(M);
    int same = 0;
    for (int i = 0; i < n; i++) if (samePadded(tg[i], mg[i])) same++;
    check(name + ": the text glyphs are byte for byte the text font's (hints kept; only the padding after them may differ)", same == n, (n - same) + " differ");

    BitSet targets = paintTargets(E, eg);
    int[] copyOf = new int[m];
    Arrays.fill(copyOf, -1);
    for (int j = 0; j < copies.length; j++) copyOf[copies[j]] = n + m + j;
    int bad = 0;
    String first = null;
    for (int i = 0; i < m + copies.length; i++) {
      int src = i < m ? i : copies[i - m];
      boolean kept = i >= m || (targets.get(src) && copyOf[src] < 0);
      String d = sameGlyph(eg[src], mg[n + i], kept ? 1.0 : s, n, kept ? copyOf : null);
      if (d != null && bad++ == 0) first = "emoji glyph " + src + (i >= m ? " (copy)" : "") + ": " + d;
    }
    check(name + ": each emoji glyph is the emoji font's, scaled by s (or in emoji units when only COLR v1 paints draw it), hints dropped, "
        + "components moved past the text glyphs", bad == 0, bad + " differ, first " + first);

    Glyf.Metrics tm = Glyf.Metrics.read(T), em = Glyf.Metrics.read(E), mm = Glyf.Metrics.read(M);
    // U+FE0F's glyph advances 0 (unless another character shows it): the restore lookup can leave it after a key, where nothing hides it
    Integer fe0f = ecmap0.get(0xFE0F);
    if (fe0f != null && !EmojiMerge.onlyFor(Cmap.read(M, null), fe0f + n, 0xFE0F)) fe0f = null;
    if (fe0f != null) check(name + ": U+FE0F's glyph advances 0 (the emoji font's: " + em.advance[fe0f] + ")", mm.advance[fe0f + n] == 0, "advance " + mm.advance[fe0f + n]);
    bad = 0;
    for (int j = 0; j < dupOf.length; j++) {
      int d = base + j, g = Math.max(0, dupOf[j]);
      if (!samePadded(mg[g], mg[d]) || mm.advance[d] != mm.advance[g] || mm.lsb[d] != mm.lsb[g]) bad++;
    }
    if (dupOf.length > 0) check(name + ": each U+FE0F copy has its glyph's outline, advance and left side bearing", bad == 0, bad + " differ");
    bad = 0;
    first = null;
    for (int i = 0; i < base; i++) {
      int src = i < n ? -1 : i < n + m ? i - n : copies[i - n - m];
      boolean kept = i >= n + m || (src >= 0 && targets.get(src) && copyOf[src] < 0);
      long adv = src < 0 ? tm.advance[i] : fe0f != null && i == fe0f + n ? 0 : kept ? em.advance[src] : Math.round(em.advance[src] * s);
      int[] bb = Glyf.headerBounds(mg[i]), ob = src < 0 ? null : Glyf.headerBounds(eg[src]);
      long gap = ob == null ? 0 : ob[0] - em.lsb[src];                    // xMin - lsb, which renderers move the glyph left by
      long lsb = src < 0 ? tm.lsb[i] : bb == null ? 0 : bb[0] - (kept ? gap : Math.round(gap * s));
      if ((mm.advance[i] != adv || mm.lsb[i] != lsb) && bad++ == 0) first = "glyph " + i + ": advance " + mm.advance[i] + " lsb " + mm.lsb[i] + ", want " + adv + " " + lsb;
    }
    check(name + ": hmtx: the text advances as they were, the emoji ones times s (rounded; U+FE0F's 0), left side bearings from the outlines less the "
        + "emoji font's xMin - lsb (times s)", bad == 0,
        bad + " differ, first " + first);

    int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
    for (int i = 0; i < base; i++) {                                       // a U+FE0F copy has the bounds of its glyph, which is here
      int src = i < n ? -1 : i < n + m ? i - n : copies[i - n - m];
      boolean kept = i >= n + m || (src >= 0 && targets.get(src) && copyOf[src] < 0);
      int[] bb = Glyf.headerBounds(mg[i]);
      if (bb == null || kept || Sfnt.s16(mg[i], 0) == 0) continue;
      x0 = Math.min(x0, bb[0]); y0 = Math.min(y0, bb[1]); x1 = Math.max(x1, bb[2]); y1 = Math.max(y1, bb[3]);
    }
    byte[] head = M.table("head");
    check(name + ": head: the text font's units per em, bounds of every glyph drawn at the text size", M.unitsPerEm() == T.unitsPerEm()
        && Sfnt.s16(head, 36) == x0 && Sfnt.s16(head, 38) == y0 && Sfnt.s16(head, 40) == x1 && Sfnt.s16(head, 42) == y1,
        Sfnt.s16(head, 36) + " " + Sfnt.s16(head, 38) + " " + Sfnt.s16(head, 40) + " " + Sfnt.s16(head, 42) + " want " + x0 + " " + y0 + " " + x1 + " " + y1);

    // cmap and variation sequences
    TreeMap<Integer, Integer> tcmap = Cmap.read(T, null), ecmap = Cmap.read(E, null), mcmap = Cmap.read(M, null), want = new TreeMap<>(tcmap);
    for (Map.Entry<Integer, Integer> x : ecmap.entrySet())
      if (!tcmap.containsKey(x.getKey()) || EmojiData.emojiWins(x.getKey())) want.put(x.getKey(), x.getValue() + n);
    check(name + ": cmap: the text font's characters, the emoji font's where the text font has none or the emoji form wins", mcmap.equals(want),
        mcmap.size() + " characters, want " + want.size());
    Cmap.Uvs tu = Cmap.readUvs(T), eu = Cmap.readUvs(E), mu = Cmap.readUvs(M);
    bad = 0;
    first = null;
    for (int cp : ecmap.keySet()) {
      if (Shaper.isVs(cp) || cp == 0x200D || (cp >= 0xE0000 && cp <= 0xE007F)) continue;
      boolean textWon = tcmap.containsKey(cp) && !EmojiData.emojiWins(cp);
      int fe = resolve(mu, mcmap, cp, 0xFE0F), te = resolve(mu, mcmap, cp, 0xFE0E);
      if (fe >= base) fe = dupOf[fe - base];                               // a U+FE0F copy draws its glyph
      int ownFe = resolve(eu, ecmap, cp, 0xFE0F), ownTe = resolve(eu, ecmap, cp, 0xFE0E);
      // where the emoji font has no (c, U+FE0F), one of Unicode's emoji variation sequences gives the emoji glyph the character shows
      int asked = EmojiData.hasEmojiVariation(cp) ? mcmap.get(cp) : -1;
      int wantFe, wantTe;
      if (textWon) {
        wantFe = emojiBase(cp) ? (eu.glyph(0xFE0F, cp) >= 0 ? eu.glyph(0xFE0F, cp) : ecmap.get(cp)) + n : resolve(tu, tcmap, cp, 0xFE0F);
        wantTe = tu.has(0xFE0E, cp) ? resolve(tu, tcmap, cp, 0xFE0E) : emojiBase(cp) ? tcmap.get(cp) : ownTe < 0 ? -1 : ownTe + n;
      } else if (tcmap.containsKey(cp)) {
        wantFe = ownFe < 0 ? asked : ownFe + n;
        wantTe = tu.has(0xFE0E, cp) && !tu.isDefault(0xFE0E, cp) ? tu.glyph(0xFE0E, cp) : tcmap.get(cp);
      } else {
        wantFe = ownFe < 0 ? asked : ownFe + n;
        wantTe = ownTe < 0 ? -1 : ownTe + n;
      }
      if ((fe != wantFe || te != wantTe) && bad++ == 0) first = String.format("U+%04X: FE0F %d FE0E %d, want %d %d", cp, fe, te, wantFe, wantTe);
    }
    check(name + ": variation sequences: U+FE0F gives the emoji glyph, U+FE0E the text glyph, of every emoji character", bad == 0,
        bad + " differ, first " + first);
    bad = 0;
    first = null;
    int withVs = 0;
    for (int cp : ecmap.keySet()) {
      if (!EmojiData.hasEmojiVariation(cp)) continue;
      withVs++;
      if (!mu.has(0xFE0F, cp) && bad++ == 0) first = String.format("U+%04X", cp);
    }
    check(name + ": every character of an emoji variation sequence the emoji font draws (" + withVs + ") has (c, U+FE0F) in the merged cmap, "
        + "so Android takes the pair from this font and not from the phone's emoji font", bad == 0, bad + " without, first " + first);
    if (tcmap.containsKey(0x200D) && ecmap.containsKey(0x200D))
      check(name + ": U+200D keeps the text font's joiner (its own rules name it)", mcmap.get(0x200D).equals(tcmap.get(0x200D)));

    gsub(name, T, E, M);

    for (String tag : new String[] { "GPOS", "kern" })
      check(name + ": " + tag + " is the text font's", Arrays.equals(T.table(tag), M.table(tag)));
    boolean gdefMerged = t.equals(INTER) && e.equals(SYNTH1);
    if (!gdefMerged) check(name + ": GDEF is the text font's" + (T.has("GDEF") ? "" : " (none)"), Arrays.equals(T.table("GDEF"), M.table("GDEF")));

    // COLR, CPAL
    Colr ec = new Colr(E.table("COLR")), mc = new Colr(M.table("COLR"));
    // a U+FE0F copy gets its glyph's v0 record, v1 record and clip box, after the others: where each is in the emoji font, -1 if none
    int[][] dupAt = new int[dupOf.length][];
    int v0Dups = 0, v1Dups = 0, clipDups = 0;
    for (int j = 0; j < dupOf.length; j++) {
      int g = dupOf[j] - n;
      dupAt[j] = new int[] { -1, -1, -1 };
      for (int i = 0; i < ec.numBase; i++) if (u16(ec.t, ec.base + 6 * i) == g) { dupAt[j][0] = i; v0Dups++; }
      for (int i = 0; i < ec.baseCount(); i++) if (ec.baseGlyph(i) == g) { dupAt[j][1] = i; v1Dups++; }
      for (int i = 0; i < ec.clipCount() && dupAt[j][2] < 0; i++) if (ec.clip(i)[0] <= g && g <= ec.clip(i)[1]) { dupAt[j][2] = i; clipDups++; }
    }
    check(name + ": COLR is version " + ec.version + " with the same counts, plus a record of each U+FE0F copy (" + v0Dups + " v0, " + v1Dups
        + " v1, " + clipDups + " clip boxes)", mc.version == ec.version && mc.numBase == ec.numBase + v0Dups && mc.numLayers == ec.numLayers
        && mc.baseCount() == ec.baseCount() + v1Dups && mc.layerCount() == ec.layerCount() && mc.clipCount() == ec.clipCount() + clipDups);
    if (dupOf.length > 0) {
      bad = 0;
      int k0 = 0, k1 = 0, k2 = 0;
      for (int j = 0; j < dupOf.length; j++) {
        int d = base + j;
        if (dupAt[j][0] >= 0) {
          long a = mc.base + 6 * dupAt[j][0], b = mc.base + 6 * (ec.numBase + k0++);
          if (u16(mc.t, b) != d || u16(mc.t, b + 2) != u16(mc.t, a + 2) || u16(mc.t, b + 4) != u16(mc.t, a + 4)) bad++;
        }
        if (dupAt[j][1] >= 0) {
          int b = ec.baseCount() + k1++;
          if (mc.baseGlyph(b) != d || mc.basePaint(b) != mc.basePaint(dupAt[j][1])) bad++;
        }
        if (dupAt[j][2] >= 0) {
          long[] a = mc.clip(dupAt[j][2]), b = mc.clip(ec.clipCount() + k2++);
          if (b[0] != d || b[1] != d || !Arrays.equals(Arrays.copyOfRange(a, 2, 8), Arrays.copyOfRange(b, 2, 8))) bad++;
        }
      }
      check(name + ": COLR: each U+FE0F copy draws as its glyph (the same layers, the same paint, the same clip box)", bad == 0, bad + " differ");
    }
    bad = 0;
    for (int i = 0; i < ec.numBase; i++) {
      long a = ec.base + 6 * i, b = mc.base + 6 * i;
      if (u16(mc.t, b) != u16(ec.t, a) + n || u16(mc.t, b + 2) != u16(ec.t, a + 2) || u16(mc.t, b + 4) != u16(ec.t, a + 4)) bad++;
    }
    for (int i = 0; i < ec.numLayers; i++) {
      long a = ec.layers + 4 * i, b = mc.layers + 4 * i;
      if (u16(mc.t, b) != u16(ec.t, a) + n || u16(mc.t, b + 2) != u16(ec.t, a + 2)) bad++;
    }
    check(name + ": COLR v0 records: glyphs past the text glyphs, the same layers and palette entries", bad == 0, bad + " differ");
    if (ec.version >= 1) {
      int fx = (int) Math.round(s * 65536), inv = (int) Math.round(65536 / s);
      bad = 0;
      first = null;
      StringBuilder wrappers = new StringBuilder();
      final int[] fcopy = copyOf;
      Colr.Ids src = (fmt, g) -> fmt == 10 && fcopy[g] >= 0 ? fcopy[g] : g + n, ident = (fmt, g) -> g;
      for (int i = 0; i < ec.baseCount(); i++) {
        int root = mc.basePaint(i);
        boolean wrapped = u8(mc.t, root) == 12;
        if (wrapped) {
          long mx = root + u24(mc.t, root + 4);
          int[] got = new int[6];
          for (int k = 0; k < 6; k++) got[k] = Sfnt.s32(mc.t, (int) mx + 4 * k);
          wrapped = Arrays.equals(got, new int[] { fx, 0, 0, fx, 0, 0 });
        }
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        ec.walk(ec.basePaint(i), a, src, 0, new StringBuilder(), 0);
        if (wrapped) mc.walk(root + u24(mc.t, root + 1), b, ident, inv, wrappers, 0);
        if ((mc.baseGlyph(i) != ec.baseGlyph(i) + n || !wrapped || !a.equals(b)) && bad++ == 0)
          first = "base glyph " + ec.baseGlyph(i) + (wrapped ? " graph " + a.size() + " vs " + b.size() + " paints" : " is not wrapped in a scale by s");
      }
      check(name + ": COLR v1: each emoji's paint behind one PaintTransform scaling it by s, the paint graph the emoji font's with its glyphs moved",
          bad == 0 && wrappers.length() == 0, bad + " differ, first " + first + " " + wrappers);
      bad = 0;
      first = null;
      for (int i = 0; i < ec.clipCount(); i++) {
        long[] a = ec.clip(i), b = mc.clip(i);
        long[] w = { a[0] + n, a[1] + n, a[2], (long) Math.floor(a[3] * s + 1e-9), (long) Math.floor(a[4] * s + 1e-9),
            (long) Math.ceil(a[5] * s - 1e-9), (long) Math.ceil(a[6] * s - 1e-9), a[7] };
        if (!Arrays.equals(w, b) && bad++ == 0) first = Arrays.toString(b) + " want " + Arrays.toString(w);
      }
      check(name + ": COLR v1 clip boxes scaled by s, rounded outwards", bad == 0, bad + " differ, first " + first);
    }
    check(name + ": CPAL is the emoji font's", Arrays.equals(E.table("CPAL"), M.table("CPAL")));

    // the other tables
    byte[] post = M.table("post"), tpost = T.table("post");
    check(name + ": post is version 3 with the text font's other fields", post.length == 32 && Sfnt.u32(post, 0) == 0x00030000L
        && Arrays.equals(Arrays.copyOfRange(post, 4, 32), Arrays.copyOfRange(tpost, 4, 32)));
    byte[] os2 = T.table("OS/2").clone();
    Sfnt.w16(os2, 64, Math.min(want.firstKey(), 0xFFFF));
    Sfnt.w16(os2, 66, Math.min(want.lastKey(), 0xFFFF));
    if (want.lastKey() > 0xFFFF) os2[46] |= 0x02;
    check(name + ": OS/2: first and last character, bit 57 (characters past U+FFFF), the rest the text font's", Arrays.equals(os2, M.table("OS/2")));
    List<String> left = new ArrayList<>();
    for (String tag : DROPPED) if (M.has(tag)) left.add(tag);
    check(name + ": hdmx, LTSH, VDMX, DSIG, vhea, vmtx and bitmap tables are left out", left.isEmpty(), left.toString());
    List<String> differ = new ArrayList<>(), extra = new ArrayList<>();
    Set<String> rebuilt = new java.util.HashSet<>(Arrays.asList("glyf", "loca", "head", "maxp", "hmtx", "hhea", "cmap", "GSUB", "GDEF", "post", "OS/2"));
    for (String tag : T.tags()) if (!rebuilt.contains(tag) && !Arrays.asList(DROPPED).contains(tag) && !Arrays.equals(T.table(tag), M.table(tag))) differ.add(tag);
    for (String tag : M.tags()) if (!T.has(tag) && !tag.equals("COLR") && !tag.equals("CPAL") && !tag.equals("GSUB") && !tag.equals("GDEF")) extra.add(tag);
    check(name + ": the text font's other tables (name, fpgm, prep, cvt, gasp ...) as they were, none of the emoji font's but COLR, CPAL, GSUB",
        differ.isEmpty() && extra.isEmpty(), "changed " + differ + ", added " + extra);
    check(name + ": hhea keeps the text font's ascender, descender and line gap", Arrays.equals(Arrays.copyOfRange(T.table("hhea"), 4, 10),
        Arrays.copyOfRange(M.table("hhea"), 4, 10)));
    List<String> warn = new ArrayList<>();
    if (E.has("GPOS")) warn.add(EmojiMerge.W_EMOJI_GPOS);
    if (t.equals(NOTOSANS) && e.equals(SYNTH1)) warn.add(EmojiMerge.W_GLYPH_CLASSES);
    check(name + ": warnings " + (warn.isEmpty() ? "none" : warn.size()), new TreeSet<>(warn).equals(new TreeSet<>(r.warnings)), r.warnings.toString());
  }

  /** What the merged font's cmap gives for (cp, selector): the variant glyph, the nominal one for a default sequence, or -1. */
  static int resolve(Cmap.Uvs u, Map<Integer, Integer> cmap, int cp, int sel) {
    int g = u.glyph(sel, cp);
    if (g >= 0) return g;
    if (u.isDefault(sel, cp)) { Integer x = cmap.get(cp); return x == null ? -1 : x; }
    return -1;
  }

  /** Null when {@code got} is {@code src} scaled by s, without hints, its components renumbered; else what differs. */
  /** Equal glyph data, ignoring the zero bytes that pad a glyph to a 2- or 4-byte boundary in glyf. */
  static boolean samePadded(byte[] a, byte[] b) {
    int n = Math.min(a.length, b.length);
    if (Math.max(a.length, b.length) - n > 3) return false;
    for (int i = 0; i < n; i++) if (a[i] != b[i]) return false;
    for (int i = n; i < a.length; i++) if (a[i] != 0) return false;
    for (int i = n; i < b.length; i++) if (b[i] != 0) return false;
    return true;
  }

  static String sameGlyph(byte[] src, byte[] got, double s, int n, int[] copyOf) throws Sfnt.FontException {
    if (src.length == 0) return got.length == 0 ? null : "an empty glyph has an outline";
    Glyf.Glyph a = Glyf.decode(src), b = Glyf.decode(got);
    if (b.instructions.length != 0) return "hints kept";
    if (a.isComposite() != b.isComposite()) return "kind";
    if (a.isComposite()) {
      if (a.components.size() != b.components.size()) return "component count";
      for (int i = 0; i < a.components.size(); i++) {
        Glyf.Component x = a.components.get(i), y = b.components.get(i);
        int g = copyOf != null && copyOf[x.glyph] >= 0 ? copyOf[x.glyph] : x.glyph + n;
        int dx = x.xy ? Glyf.round(x.dx * s) : x.dx, dy = x.xy ? Glyf.round(x.dy * s) : x.dy;
        if (y.glyph != g || y.dx != dx || y.dy != dy || y.xy != x.xy || y.scaleKind != x.scaleKind || y.a != x.a || y.b != x.b || y.c != x.c || y.d != x.d)
          return "component " + i + ": glyph " + y.glyph + " at " + y.dx + "," + y.dy + ", want " + g + " at " + dx + "," + dy;
      }
      return null;
    }
    if (!Arrays.equals(a.endPts, b.endPts) || !Arrays.equals(a.on, b.on)) return "contours";
    for (int i = 0; i < a.x.length; i++)
      if (b.x[i] != Glyf.round(a.x[i] * s) || b.y[i] != Glyf.round(a.y[i] * s)) return "point " + i + " at " + b.x[i] + "," + b.y[i];
    int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
    for (int i = 0; i < b.x.length; i++) { x0 = Math.min(x0, b.x[i]); x1 = Math.max(x1, b.x[i]); y0 = Math.min(y0, b.y[i]); y1 = Math.max(y1, b.y[i]); }
    if (b.x.length > 0 && (b.xMin != x0 || b.yMin != y0 || b.xMax != x1 || b.yMax != y1)) return "bounds";
    return null;
  }

  /** The first feature of each tag in a LangSys, as HarfBuzz finds them: tag -> its lookups. */
  static Map<String, TreeSet<Integer>> features(OtLayout.Table g, OtLayout.LangSys ls) {
    Map<String, TreeSet<Integer>> r = new LinkedHashMap<>();
    for (int fi : ls.features) {
      OtLayout.Feature f = g.features.get(fi);
      if (!r.containsKey(f.tag)) r.put(f.tag, new TreeSet<>(f.lookups));
    }
    return r;
  }

  static List<OtLayout.LangSys> langSystems(OtLayout.Script s) {
    List<OtLayout.LangSys> l = new ArrayList<>();
    if (s.dflt != null) l.add(s.dflt);
    l.addAll(s.langs.values());
    return l;
  }

  static void gsub(String name, Sfnt T, Sfnt E, Sfnt M) throws Exception {
    OtLayout.Table tg = T.has("GSUB") ? OtLayout.parse(T.table("GSUB"), true) : null, eg = E.has("GSUB") ? OtLayout.parse(E.table("GSUB"), true) : null;
    if (!M.has("GSUB")) {
      check(name + ": no GSUB: neither font has one", tg == null && eg == null);
      return;
    }
    OtLayout.Table mg = OtLayout.parse(M.table("GSUB"), true);
    int tl = tg == null ? 0 : tg.lookups.size(), el = eg == null ? 0 : eg.lookups.size(), extra = mg.lookups.size() - tl - el;
    StringBuilder kinds = new StringBuilder();
    for (int i = tl; i < tl + Math.max(0, extra); i++) kinds.append(mg.lookups.get(i).type);
    String k = kinds.toString();
    check(name + ": GSUB: the text font's " + tl + " lookups, then " + extra + " that make the emoji font's work (" + k + "), then its " + el,
        Arrays.asList("", "62", "61", "621", "6621", "6611", "66211").contains(k), "lookup types " + k);
    boolean same = true;
    for (int i = 0; i < tl; i++) same &= mg.lookups.get(i).type == tg.lookups.get(i).type && mg.lookups.get(i).flag == tg.lookups.get(i).flag;
    for (int i = 0; i < el; i++) same &= mg.lookups.get(tl + extra + i).type == eg.lookups.get(i).type
        && (mg.lookups.get(tl + extra + i).flag & 0xFF) == (eg.lookups.get(i).flag & 0xFF);
    check(name + ": GSUB: the lookups keep their types and flags", same);
    TreeSet<Integer> on = new TreeSet<>();
    if (eg != null) for (OtLayout.Feature f : eg.features) for (int li : f.lookups) on.add(li + tl + extra);
    for (int i = 0; i < k.length() && k.charAt(i) == '6'; i++) on.add(tl + i);           // the contextual ones in front; they call the rest
    int langs = 0, ok = 0;
    for (OtLayout.Script sc : mg.scripts)
      for (OtLayout.LangSys ls : langSystems(sc)) {
        langs++;
        Map<String, TreeSet<Integer>> f = features(mg, ls);
        TreeSet<Integer> rest = f.containsKey(EmojiMerge.FEATURE) ? new TreeSet<>(f.get(EmojiMerge.FEATURE)) : on.isEmpty() ? new TreeSet<>() : null;
        if (rest == null || !rest.containsAll(on)) continue;
        rest.removeAll(on);
        TreeSet<Integer> before = new TreeSet<>();
        OtLayout.Script ts = tg == null ? null : tg.script(sc.tag);
        OtLayout.LangSys tls = ts == null ? null : ls == sc.dflt ? ts.dflt : ts.langs.get(langTag(sc, ls));
        if (tls != null && features(tg, tls).containsKey(EmojiMerge.FEATURE)) before = features(tg, tls).get(EmojiMerge.FEATURE);
        if (rest.equals(before)) ok++;
      }
    check(name + ": GSUB: every script and language system turns the emoji lookups on in " + EmojiMerge.FEATURE + " (" + langs + ")",
        langs > 0 && ok == langs && mg.script("DFLT") != null && (tg != null || mg.script("latn") != null), ok + " of " + langs);
    if (tg != null) {
      int bad = 0;
      for (OtLayout.Script ts : tg.scripts) {
        OtLayout.Script ms = mg.script(ts.tag);
        for (OtLayout.LangSys tls : langSystems(ts)) {
          OtLayout.LangSys mls = ms == null ? null : tls == ts.dflt ? ms.dflt : ms.langs.get(langTag(ts, tls));
          if (mls == null) { bad++; continue; }
          Map<String, TreeSet<Integer>> a = features(tg, tls), b = features(mg, mls);
          for (Map.Entry<String, TreeSet<Integer>> x : a.entrySet())
            if (!x.getKey().equals(EmojiMerge.FEATURE) && !x.getValue().equals(b.get(x.getKey()))) bad++;
        }
      }
      check(name + ": GSUB: the text font's features keep their lookups in every script and language system", bad == 0, bad + " differ");
    }
  }

  static String langTag(OtLayout.Script s, OtLayout.LangSys ls) {
    for (Map.Entry<String, OtLayout.LangSys> e : s.langs.entrySet()) if (e.getValue() == ls) return e.getKey();
    return null;
  }

  static void shaping(String name, Expected ex, String t, String e, Sfnt T, Sfnt E, Sfnt M) throws Exception {
    int n = T.numGlyphs();
    boolean noGdef = E.has("GDEF") && !M.has("GDEF");
    String eName = e + (noGdef ? NO_GDEF : "");
    Shaper sm = new Shaper(M), st = new Shaper(T), se = new Shaper(noGdef ? without(E, "GDEF") : E);
    TreeMap<Integer, Integer> tcmap = Cmap.read(T, null), ecmap = Cmap.read(E, null);
    Map<String, int[]> tseq = ex.seq.get(t), eseq = ex.seq.get(eName);
    if (eseq == null || tseq == null) { check(name + ": expected.txt has the sequences of " + eName + " and " + t, false); return; }
    int bad = 0, asText = 0;
    String first = null;
    for (Map.Entry<String, int[]> q : eseq.entrySet()) {
      int[] c = cps(q.getKey());
      boolean text = textExpected(c, tcmap);
      int[] want = text ? tseq.get(q.getKey()) : shifted(q.getValue(), n), got = sm.shape(c, "Zyyy");
      if (text) asText++;
      if (!Arrays.equals(want, got) && bad++ == 0) first = q.getKey() + ": " + Arrays.toString(got) + ", want " + Arrays.toString(want) + (text ? " (text)" : " (emoji + N)");
    }
    check(name + ": the " + eseq.size() + " sequences shape as HarfBuzz shapes the emoji font alone (+N), or the text font alone where its glyph wins ("
        + asText + ")", bad == 0, bad + " differ, first " + first);

    bad = 0;
    int count = 0;
    first = null;
    for (int cp : ecmap.keySet()) {
      if (Shaper.isVs(cp) || cp == 0x200D || (cp >= 0xE0000 && cp <= 0xE007F)) continue;
      for (int[] c : new int[][] { { cp }, { cp, 0xFE0F }, { cp, 0xFE0E } }) {
        count++;
        int[] want = textExpected(c, tcmap) ? st.shape(c, "Zyyy") : shifted(se.shape(c, "Zyyy"), n), got = sm.shape(c, "Zyyy");
        if (!Arrays.equals(want, got) && bad++ == 0) first = hex(c) + ": " + Arrays.toString(got) + ", want " + Arrays.toString(want);
      }
    }
    check(name + ": every emoji character alone, with U+FE0F and with U+FE0E (" + count + ") shapes as in the font whose form it takes",
        bad == 0, bad + " differ, first " + first);
    bad = 0;
    count = 0;
    first = null;
    for (int cp : tcmap.keySet()) {
      if (ecmap.containsKey(cp) && EmojiData.emojiWins(cp)) continue;
      count++;
      int[] want = st.shape(new int[] { cp }, "Zyyy"), got = sm.shape(new int[] { cp }, "Zyyy");
      if (!Arrays.equals(want, got) && bad++ == 0) first = String.format("U+%04X: %s, want %s", cp, Arrays.toString(got), Arrays.toString(want));
    }
    check(name + ": every text character (" + count + ") shapes as in the text font", bad == 0, bad + " differ, first " + first);
    bad = 0;
    count = 0;
    first = null;
    for (String[] r : ex.runs) {
      if (!r[0].equals(t)) continue;
      int[] c = cps(r[2]);
      boolean emoji = false;
      for (int x : c) emoji |= x != 0x20 && (!tcmap.containsKey(x) || ecmap.containsKey(x) && EmojiData.emojiWins(x));
      if (emoji) continue;                                                 // a character the emoji font draws: not a text-only run
      count++;
      int[] got = sm.shape(c, r[1]);
      if (!Arrays.equals(got, ints(r[3])) && bad++ == 0) first = r[1] + " " + r[2] + ": " + Arrays.toString(got) + ", HarfBuzz on the text font " + r[3];
    }
    check(name + ": the text runs (" + count + ") shape as HarfBuzz shapes the text font alone", count > 0 && bad == 0, bad + " differ, first " + first);
  }

  // ---------- the synthetic fonts ----------

  static void colrV1() throws Exception {
    for (String t : new String[] { INTER, NOTOSANS }) {
      Sfnt T = font(t), E = font(SYNTH1);
      EmojiMerge.Result r = EmojiMerge.merge(T, E, null);
      Sfnt M = Sfnt.parse(r.font.toBytes());
      int n = T.numGlyphs(), m = E.numGlyphs(), copy = n + m;
      String name = mergedName(t, SYNTH1);
      Colr c = new Colr(M.table("COLR"));
      TreeSet<Integer> formats = new TreeSet<>();
      int e2 = -1, e3 = -1;
      for (int i = 0; i < c.baseCount(); i++) {
        List<String> g = new ArrayList<>();
        c.walk(c.basePaint(i), g, (fmt, x) -> x, 0, new StringBuilder(), 0);
        for (String x : g) formats.add(Integer.parseInt(x.split(" ")[0]));
        if (c.baseGlyph(i) == n + 9) e2 = i;
        if (c.baseGlyph(i) == n + 10) e3 = i;
      }
      check(name + ": the merged COLR has all 32 paint formats", formats.size() == 32, formats.toString());
      // "both" (glyph 6) is drawn by e2's paint and mapped by U+1F7E2: the cmap gets the scaled glyph, the paint the emoji-units copy
      byte[][] eg = Glyf.readAll(E), mg = Glyf.readAll(M);
      List<String> g2 = new ArrayList<>();
      c.walk(c.basePaint(e2), g2, (fmt, x) -> x, 0, new StringBuilder(), 0);
      boolean toCopy = false;
      for (String x : g2) toCopy |= x.startsWith("10 " + copy + " ");
      check(name + ": a glyph both a paint draws and the cmap maps: the paint draws an emoji-units copy (glyph " + copy + "), the cmap the scaled one",
          toCopy && Cmap.read(M, null).get(0x1F7E2) == n + 6 && sameGlyph(eg[6], mg[copy], 1.0, n, null) == null
          && sameGlyph(eg[6], mg[n + 6], r.scale, n, null) == null);
      // e3: PaintTranslate -> PaintColrGlyph(e1): behind an inverse scale, since e1's own paint is scaled already
      List<String> g3 = new ArrayList<>();
      c.walk(c.basePaint(e3), g3, (fmt, x) -> x, 0, new StringBuilder(), 0);
      List<String> kinds = new ArrayList<>();
      for (String x : g3) kinds.add(x.split(" ")[0] + (x.startsWith("11 ") ? ":" + x.split(" ")[1] : ""));
      int inv = (int) Math.round(65536 / r.scale);
      // the third paint is the wrapper itself: format 12, offsets blanked, matrix (1/s, 0, 0, 1/s, 0, 0)
      boolean inverse = g3.size() == 4 && g3.get(2).equals("12 0 0c000000000000" + String.format("%08x%08x%08x%08x%08x%08x", inv, 0, 0, inv, 0, 0));
      check(name + ": a PaintColrGlyph sits behind an inverse PaintTransform (scale, translate, 1/s, PaintColrGlyph e1)",
          kinds.equals(Arrays.asList("12", "14", "12", "11:" + (n + 8))) && inverse, kinds.toString());
      // the composite only paints draw keeps emoji units and points at the moved components
      Glyf.Glyph comp = Glyf.decode(mg[n + 5]);
      check(name + ": the composite glyph only a paint draws keeps emoji units, its components moved", comp.isComposite()
          && comp.components.get(0).glyph == n + 2 && comp.components.get(1).glyph == n + 4 && comp.components.get(1).dx == 37 && comp.components.get(1).dy == -63);
      // sq (xMin 100) and mk (xMin 200) have lsb 0: FreeType and HarfBuzz draw them moved left by xMin - lsb, so the gap must stay
      Glyf.Metrics mm = Glyf.Metrics.read(M);
      int[] mk = Glyf.headerBounds(mg[n + 7]);
      check(name + ": glyphs whose lsb is not their xMin keep the gap: sq (emoji units) lsb 0 at xMin 100, mk (scaled) lsb 0 at xMin "
          + Math.round(200 * r.scale), mm.lsb[n + 2] == 0 && Glyf.headerBounds(mg[n + 2])[0] == 100 && mm.lsb[n + 7] == 0
          && mk[0] == Math.round(200 * r.scale), "sq " + mm.lsb[n + 2] + ", mk " + mm.lsb[n + 7] + " at " + mk[0]);
      // the variable clip box keeps its VarIndexBase; clip boxes of e1 and lig
      boolean var = false;
      for (int i = 0; i < c.clipCount(); i++) var |= c.clip(i)[2] == 2 && c.clip(i)[7] == 0xFFFFFFFFL && c.clip(i)[0] == n + 11;
      check(name + ": the variable clip box (format 2) keeps its VarIndexBase", var);
    }
  }

  static void colrV0() throws Exception {
    Sfnt T = font(LATO), E = font(SYNTH0);
    EmojiMerge.Result r = EmojiMerge.merge(T, E, null);
    Sfnt M = Sfnt.parse(r.font.toBytes());
    int n = T.numGlyphs();
    String name = mergedName(LATO, SYNTH0);
    check(name + ": units per em 2048 into 2000: s = 0.9765625", r.scale == 0.9765625);
    byte[][] mg = Glyf.readAll(M);
    Glyf.Glyph a1 = Glyf.decode(mg[n + 2]), a2 = Glyf.decode(mg[n + 3]);
    check(name + ": the hinted layer glyphs lose their instructions; the composite's offset (801, 799) becomes (782, 780)",
        a1.instructions.length == 0 && a2.instructions.length == 0 && a2.components.get(1).glyph == n + 2 && a2.components.get(1).dx == 782
        && a2.components.get(1).dy == 780 && a1.x[2] == 977 && a1.y[1] == 977);
    check(name + ": a layer glyph the cmap maps too (U+1F7E7) is the same glyph, scaled once", Cmap.read(M, null).get(0x1F7E7) == n + 2);
    check(name + ": the text font's fpgm, prep and cvt stay, the emoji font's are not taken", Arrays.equals(M.table("fpgm"), T.table("fpgm"))
        && Arrays.equals(M.table("prep"), T.table("prep")) && Arrays.equals(M.table("cvt "), T.table("cvt ")));
    Glyf.Metrics mm = Glyf.Metrics.read(M);
    check(name + ": advances 2048 -> 2000, 2047 -> 1999, 1801 -> 1759", mm.advance[n + 5] == 2000 && mm.advance[n + 6] == 1999 && mm.advance[n + 3] == 1759);
  }

  /**
   * Twemoji with its [U+2695 U+FE0F] ligature turned off, so that only [1F468 200D 2695 FE0F] names U+2695 before U+FE0F: the restore rule
   * of its key then has the man and the joiner before it. HarfBuzz matches those passing over a ZWNJ, the ligature not: U+FE0F's glyph is
   * left after U+2695, as a glyph that nothing hides. It takes no room, so the run is as wide as with the emoji font alone.
   */
  static void strayFe0f() throws Exception {
    Sfnt E = font(TWEMOJI);
    TreeMap<Integer, Integer> ecmap = Cmap.read(E, null);
    int c = ecmap.get(0x2695), f = ecmap.get(0xFE0F), off = 0;
    byte[] gsub = E.table("GSUB").clone();
    for (OtLayout.Lookup l : OtLayout.parse(gsub, true).lookups) {
      if (l.type != 4) continue;
      for (OtLayout.Subtable st : l.subtables) {
        int k = cov(gsub, st.offset + u16(gsub, st.offset + 2), c);
        if (k < 0) continue;
        int set = st.offset + u16(gsub, st.offset + 6 + 2 * k);
        for (int i = 0; i < u16(gsub, set); i++) {
          int lig = set + u16(gsub, set + 2 + 2 * i);
          // the second component becomes the ligature glyph itself, which no character gives (.notdef would match a ZWNJ Twemoji lacks)
          if (u16(gsub, lig + 2) == 2 && u16(gsub, lig + 4) == f) { Sfnt.w16(gsub, lig + 4, u16(gsub, lig)); off++; }
        }
      }
    }
    check("Twemoji's [2695 FE0F] ligature is turned off", off == 1, off + " ligatures");
    E.put("GSUB", gsub);
    Sfnt T = font(LATO), M = Sfnt.parse(EmojiMerge.merge(T, E, null).font.toBytes());
    int n = T.numGlyphs();
    Shaper sm = new Shaper(M), se = new Shaper(E);
    Glyf.Metrics mm = Glyf.Metrics.read(M);
    String[] seqs = { "1F468 200D 2695 FE0F", "1F468 200C 200D 2695 FE0F", "1F468 200D 200C 2695 FE0F", "2695 FE0F 200C 1F468" };
    int[] left = { 0, 1, 1, 0 };
    for (int k = 0; k < seqs.length; k++) {
      int[] want = shifted(se.shape(cps(seqs[k]), "Zyyy"), n), got = sm.shape(cps(seqs[k]), "Zyyy");
      List<Integer> rest = new ArrayList<>();
      int strays = 0;
      long wide = 0, wideWant = 0;
      for (int g : got) { wide += mm.advance[g]; if (g == f + n) strays++; else rest.add(g); }
      for (int g : want) wideWant += mm.advance[g];
      check(seqs[k] + ": the emoji font's glyphs (+N)" + (left[k] > 0 ? " and U+FE0F's glyph, which takes no room" : ""),
          Arrays.equals(toArray(rest), want) && strays == left[k] && wide == wideWant,
          Arrays.toString(got) + " (" + wide + " wide), want " + Arrays.toString(want) + " (" + wideWant + ")");
    }
  }

  static void glyphClasses() throws Exception {
    Sfnt T = font(INTER), E = font(SYNTH1);
    EmojiMerge.Result r = EmojiMerge.merge(T, E, null);
    Sfnt M = Sfnt.parse(r.font.toBytes());
    int n = T.numGlyphs(), m = E.numGlyphs();
    Gdef a = new Gdef(T.table("GDEF")), b = new Gdef(E.table("GDEF")), g = new Gdef(M.table("GDEF"));
    int bad = 0;
    for (int i = 0; i < n; i++) if (g.cls(i) != a.cls(i) || g.attachClass(i) != a.attachClass(i)) bad++;
    int maxMark = 0;
    for (int i = 0; i < n; i++) maxMark = Math.max(maxMark, a.attachClass(i));
    for (int i = 0; i < m; i++) if (g.cls(n + i) != b.cls(i) || g.attachClass(n + i) != (b.attachClass(i) == 0 ? 0 : b.attachClass(i) + maxMark)) bad++;
    check("Inter+Synth-COLRv1: GDEF has the text font's glyph classes and the emoji font's after them", bad == 0 && g.minor >= 2, bad + " differ");
    boolean sets = g.sets() == a.sets() + b.sets();
    for (int k = 0; k < b.sets() && sets; k++)
      for (int i = 0; i < m; i++) sets &= g.inSet(a.sets() + k, n + i) == b.inSet(k, i);
    for (int k = 0; k < a.sets() && sets; k++)
      for (int i = 0; i < n; i++) sets &= g.inSet(k, i) == a.inSet(k, i);
    check("Inter+Synth-COLRv1: GDEF has the text font's " + a.sets() + " mark glyph sets, then the emoji font's " + b.sets(), sets);
    check("Inter+Synth-COLRv1: GDEF keeps the text font's attachment points and ligature carets", (g.attach != 0) == (a.attach != 0) && (g.carets != 0) == (a.carets != 0));
    OtLayout.Table tg = OtLayout.parse(T.table("GSUB"), true), eg = OtLayout.parse(E.table("GSUB"), true), mg = OtLayout.parse(M.table("GSUB"), true);
    int base = mg.lookups.size() - eg.lookups.size();
    boolean flags = true;
    for (int i = 0; i < eg.lookups.size(); i++) {
      OtLayout.Lookup x = eg.lookups.get(i), y = mg.lookups.get(base + i);
      int mat = x.flag >>> 8;
      flags &= (y.flag & 0xFF) == (x.flag & 0xFF) && (y.flag >>> 8) == (mat == 0 ? 0 : mat + maxMark);
      if ((x.flag & 0x10) != 0) flags &= y.markFilteringSet == x.markFilteringSet + a.sets();
    }
    check("Inter+Synth-COLRv1: the emoji lookups' mark classes and mark sets are renumbered to match", flags && base == tg.lookups.size());
    check("Inter+Synth-COLRv1: no warning about glyph classes", !r.warnings.contains(EmojiMerge.W_GLYPH_CLASSES));

    EmojiMerge.Result r2 = EmojiMerge.merge(font(NOTOSANS), font(SYNTH1), null);
    OtLayout.Table m2 = OtLayout.parse(r2.font.table("GSUB"), true);
    int b2 = m2.lookups.size() - eg.lookups.size();
    boolean kept = true;
    for (int i = 0; i < eg.lookups.size(); i++) kept &= m2.lookups.get(b2 + i).flag == eg.lookups.get(i).flag;
    check("NotoSans-kern+Synth-COLRv1: a text font without GDEF gets none (HarfBuzz keeps guessing its classes), the lookups keep their flags, "
        + "and the merge says so", !r2.font.has("GDEF") && kept && r2.warnings.contains(EmojiMerge.W_GLYPH_CLASSES));

    // No mark glyph sets in either font: the merged GDEF's class definitions sit after the whole text GDEF, and its header points at them
    // with 16 bits. Twemoji with a GDEF (glyph 5 a mark of attachment class 1) and its first lookup set to IgnoreMarks.
    Sfnt E2 = font(TWEMOJI);
    int me = E2.numGlyphs();
    E2.put("GDEF", gdef(me, 5, 0));
    byte[] gs = E2.table("GSUB").clone();
    int ll = u16(gs, 8);
    Sfnt.w16(gs, ll + u16(gs, ll + 2) + 2, 0x0008);
    E2.put("GSUB", gs);
    int nl = font(LATO).numGlyphs(), small = gdef(nl, -1, 0).length;
    // the glyph class definition at 65,530 and the mark one past 65,535; both past 65,535
    for (int pad : new int[] { 0, 65530 - small, 70000 }) {
      Sfnt T2 = font(LATO);
      byte[] padded = gdef(nl, -1, pad);
      T2.put("GDEF", padded);
      EmojiMerge.Result r3 = EmojiMerge.merge(T2, E2, null);
      Gdef g3 = new Gdef(r3.font.table("GDEF"));
      if (pad == 0)
        check("Lato+Twemoji with small GDEFs: the merged GDEF has the emoji font's glyph and mark classes after the text font's, and no warning",
            g3.cls(nl - 1) == 1 && g3.cls(nl + 5) == 3 && g3.attachClass(nl + 5) == 1 && !r3.warnings.contains(EmojiMerge.W_GLYPH_CLASSES));
      else
        check("Lato+Twemoji with a text GDEF of " + padded.length + " bytes: the " + (pad > 0xFFFF ? "glyph" : "mark attachment") + " class definition "
            + "would start past 64 KB, so the merged font keeps the text font's GDEF and the merge says so",
            Arrays.equals(r3.font.table("GDEF"), padded) && r3.warnings.contains(EmojiMerge.W_GLYPH_CLASSES), r3.warnings.toString());
    }
  }

  /** GDEF 1.0: glyphs 1 to {@code glyphs - 1} bases, {@code mark} (unless -1) a mark of attachment class 1; then {@code pad} zero bytes. */
  static byte[] gdef(int glyphs, int mark, int pad) {
    Sfnt.Writer w = new Sfnt.Writer(64);
    w.u16(1).u16(0).u16(12).u16(0).u16(0).u16(0);
    w.u16(1).u16(1).u16(glyphs - 1);                                     // class definition format 1, from glyph 1
    for (int g = 1; g < glyphs; g++) w.u16(g == mark ? 3 : 1);
    if (mark >= 0) {
      w.set16(10, w.size());
      w.u16(1).u16(mark).u16(1).u16(1);
    }
    return w.zeros(pad).toByteArray();
  }

  /** A text font with variation sequences of its own: they are kept, and U+FE0E keeps the text glyph of a character the emoji glyph took. */
  static void textSequences(Expected ex) throws Exception {
    Sfnt T = font(LATO), E = font(NOTO);
    TreeMap<Integer, Integer> map = Cmap.read(T, null);
    int x = map.get((int) 'X'), y = map.get((int) 'Y'), arrow = map.get(0x2194), tm = map.get(0x2122);
    map.put(0x1F44D, x);                                                 // the text font draws a thumbs-up of its own
    Cmap.Uvs u = new Cmap.Uvs();
    u.addDefault(0xFE0E, 0x1F44D);
    u.put(0xFE0E, 0x2194, y);
    u.put(0xFE0F, 0x2122, map.get((int) 'T'));
    T.put("cmap", Cmap.build(map, u));
    EmojiMerge.Result r = EmojiMerge.merge(T, E, null);
    Sfnt M = Sfnt.parse(r.font.toBytes());
    int n = T.numGlyphs();
    Shaper sm = new Shaper(M);
    TreeMap<Integer, Integer> mc = Cmap.read(M, null), ec = Cmap.read(E, null);
    Map<String, int[]> eseq = ex.seq.get(NOTO);
    check("text sequences: U+1F44D shows the emoji (the emoji form wins), with U+FE0E the text font's own glyph",
        mc.get(0x1F44D) == ec.get(0x1F44D) + n && Arrays.equals(sm.shape(new int[] { 0x1F44D, 0xFE0E }, "Zyyy"), new int[] { x }));
    check("text sequences: the skin tone sequence still shapes as the emoji font's", Arrays.equals(sm.shape(new int[] { 0x1F44D, 0x1F3FD }, "Zyyy"),
        shifted(eseq.get("1F44D 1F3FD"), n)));
    check("text sequences: the text font's own (U+2194, U+FE0E) glyph is kept", Arrays.equals(sm.shape(new int[] { 0x2194, 0xFE0E }, "Zyyy"), new int[] { y })
        && Arrays.equals(sm.shape(new int[] { 0x2194 }, "Zyyy"), new int[] { arrow }));
    check("text sequences: (U+2122, U+FE0F) asks for the emoji form, so it gives the emoji glyph over the text font's own",
        Arrays.equals(sm.shape(new int[] { 0x2122, 0xFE0F }, "Zyyy"), shifted(eseq.get("2122 FE0F"), n))
        && Arrays.equals(sm.shape(new int[] { 0x2122 }, "Zyyy"), new int[] { tm }));
  }

  // ---------- options ----------

  static void options(Expected ex) throws Exception {
    for (double k : new double[] { 0.8, 1.25 }) {
      Sfnt T = font(LATO), E = font(NOTO);
      EmojiMerge.Options o = new EmojiMerge.Options();
      o.scale = k;
      EmojiMerge.Result r = EmojiMerge.merge(T, E, o);
      Sfnt M = Sfnt.parse(r.font.toBytes());
      int n = T.numGlyphs(), g = Cmap.read(E, null).get(0x1F600);
      double s = Math.round(2000 / 1024.0 * k * 65536) / 65536.0;
      Colr c = new Colr(M.table("COLR"));
      int root = c.basePaint(0);
      Glyf.Metrics em = Glyf.Metrics.read(E), mm = Glyf.Metrics.read(M);
      check("emoji size " + k + ": s = " + s + ", the COLR wrapper scales by it, advances follow", r.scale == s
          && Sfnt.s32(c.t, root + u24(c.t, root + 4)) == (int) Math.round(s * 65536) && mm.advance[n + g] == Math.round(em.advance[g] * s));
      Shaper sm = new Shaper(M);
      check("emoji size " + k + ": the sequences still shape the same", Arrays.equals(sm.shape(cps("1F469 1F3FD 200D 2695 FE0F"), "Zyyy"),
          shifted(ex.seq.get(NOTO).get("1F469 1F3FD 200D 2695 FE0F"), n)));
    }
    for (double k : new double[] { 0.79, 1.26, Double.NaN, Double.POSITIVE_INFINITY }) {
      EmojiMerge.Options o = new EmojiMerge.Options();
      o.scale = k;
      throwsArg("emoji size " + k + " is refused (0.8 to 1.25)", () -> EmojiMerge.merge(font(LATO), font(NOTO), o));
    }
    throwsArg("a missing text font is refused", () -> EmojiMerge.merge(null, font(NOTO), null));
    throwsArg("a missing emoji font is refused", () -> EmojiMerge.merge(font(LATO), null, null));

    Sfnt T = font(LATO), E = font(NOTO);
    byte[] tb = T.toBytes(), eb = E.toBytes();
    byte[] one = EmojiMerge.merge(T, E, new EmojiMerge.Options()).font.toBytes(), two = EmojiMerge.merge(T, E, null).font.toBytes();
    check("neither input font is changed, and the same inputs give the same bytes", Arrays.equals(tb, T.toBytes()) && Arrays.equals(eb, E.toBytes())
        && Arrays.equals(one, two));

    // units per em 1024 on both sides: s = 1, the emoji glyphs and clip boxes as they were, the wrapper the identity
    Sfnt I = font(INTER);
    byte[] head = I.table("head").clone();
    Sfnt.w16(head, 18, 1024);
    I.put("head", head);
    EmojiMerge.Result r = EmojiMerge.merge(I, font(NOTO), null);
    Sfnt M = Sfnt.parse(r.font.toBytes());
    int n = I.numGlyphs();
    byte[][] eg = Glyf.readAll(font(NOTO)), mg = Glyf.readAll(M);
    int same = 0;
    for (int i = 0; i < eg.length; i++) if (sameGlyph(eg[i], mg[n + i], 1.0, n, null) == null) same++;
    Colr ec = new Colr(font(NOTO).table("COLR")), mc = new Colr(M.table("COLR"));
    boolean clips = true;
    for (int i = 0; i < ec.clipCount(); i++) { long[] a = ec.clip(i), b = mc.clip(i); clips &= a[3] == b[3] && a[4] == b[4] && a[5] == b[5] && a[6] == b[6]; }
    int root = mc.basePaint(0);
    check("a text font of 1024 units per em: s = 1, the emoji outlines and clip boxes as they were, the wrapper the identity", r.scale == 1.0
        && same == eg.length && clips && Sfnt.s32(mc.t, root + u24(mc.t, root + 4)) == 0x10000, same + " of " + eg.length + " glyphs");
  }

  // ---------- refusals ----------

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

  static void refusals() throws Exception {
    // the text font would be fine without emoji, so each refusal has a code of its own (the page's sentence says what to change)
    throwsFont("an emoji font as the text font is refused with code text_has_color", "text_has_color", "colour glyphs (COLR)", () -> EmojiMerge.merge(font(NOTO), font(TWEMOJI), null));
    for (String tag : new String[] { "CBDT", "sbix", "SVG " }) {
      Sfnt T = font(LATO);
      T.put(tag, new byte[16]);
      throwsFont("a text font with " + tag.trim() + " colour glyphs is refused with code text_has_color", "text_has_color", "(" + tag.trim() + ")", () -> EmojiMerge.merge(T, font(NOTO), null));
    }
    Sfnt cff = without(font(LATO), "glyf", "loca");
    cff.put("CFF ", new byte[64]);
    throwsFont("a text font with PostScript outlines is refused", "bad_font", "PostScript", () -> EmojiMerge.merge(cff, font(NOTO), null));
    Sfnt fvar = font(INTER);
    fvar.put("fvar", new byte[16]);
    throwsFont("a variable text font is refused", "bad_font", "static font", () -> EmojiMerge.merge(fvar, font(NOTO), null));
    Sfnt sym = font(LATO);
    sym.put("cmap", symbolCmap());
    throwsFont("a symbol text font is refused with code symbol_font", "symbol_font", "symbol font", () -> EmojiMerge.merge(sym, font(NOTO), null));

    for (String tag : new String[] { "CBDT", "sbix" }) {
      Sfnt E = without(font(NOTO), "COLR", "CPAL");
      E.put(tag, new byte[16]);
      throwsFont("a bitmap emoji font (" + tag + ") is refused with code bitmap_emoji", "bitmap_emoji", tag, () -> EmojiMerge.merge(font(LATO), E, null));
    }
    throwsFont("a font without COLR is refused with code not_emoji", "not_emoji", null, () -> EmojiMerge.merge(font(LATO), font(INTER), null));
    throwsFont("an emoji font without CPAL is refused with code no_palette", "no_palette", "no colour palette", () -> EmojiMerge.merge(font(LATO), without(font(NOTO), "CPAL"), null));
    Sfnt shortCpal = font(NOTO);
    shortCpal.put("CPAL", new byte[8]);
    throwsFont("an emoji font with a cut-off CPAL is refused with code palette_damaged", "palette_damaged", "palette (CPAL) is damaged", () -> EmojiMerge.merge(font(LATO), shortCpal, null));
    Sfnt ecff = without(font(TWEMOJI), "glyf", "loca");
    ecff.put("CFF ", new byte[64]);
    throwsFont("an emoji font with PostScript outlines is refused with code emoji_outlines", "emoji_outlines", "PostScript", () -> EmojiMerge.merge(font(LATO), ecff, null));
    Sfnt esym = font(TWEMOJI);
    esym.put("cmap", symbolCmap());
    throwsFont("an emoji font with a symbol cmap is refused with code emoji_symbol", "emoji_symbol", "symbol", () -> EmojiMerge.merge(font(LATO), esym, null));

    Sfnt big = font(LATO);
    byte[] maxp = big.table("maxp").clone();
    Sfnt.w16(maxp, 4, 65000);
    big.put("maxp", maxp);
    throwsFont("65,000 text glyphs and 772 emoji glyphs are refused with code too_many_glyphs", "too_many_glyphs",
        "The text font has 65,000 glyphs and the emoji font 772: together they pass the 65,535 glyphs a font can hold.", () -> EmojiMerge.merge(big, font(NOTO), null));
    // 65,522 + 13 fit, but not with the emoji-units copy the synthetic font needs
    Sfnt T = padded(NOTOSANS, 65522);
    throwsFont("65,522 text glyphs, 13 emoji glyphs and an emoji-units copy are refused with code too_many_glyphs", "too_many_glyphs", "65,522 glyphs and the emoji font 14",
        () -> EmojiMerge.merge(T, font(SYNTH1), null));
    check("65,522 text glyphs and the 7 of the COLR v0 font fit", EmojiMerge.merge(T, font(SYNTH0), null).font.numGlyphs() == 65529);
    fe0fCopiesAtTheLimit();

    Sfnt upem = font(LATO);
    byte[] head = upem.table("head").clone();
    Sfnt.w16(head, 18, 0);
    upem.put("head", head);
    throwsFont("a text font whose head says 0 units per em is refused", "bad_font", "units per em (0)", () -> EmojiMerge.merge(upem, font(NOTO), null));
    Sfnt shortHead = font(NOTO);
    shortHead.put("head", new byte[20]);
    throwsFont("an emoji font with a cut-off head is refused with code emoji_damaged", "emoji_damaged", "too short", () -> EmojiMerge.merge(font(LATO), shortHead, null));
    Sfnt noMaxp = without(font(LATO), "maxp");
    throwsFont("a text font without maxp is refused", "bad_font", "no maxp", () -> EmojiMerge.merge(noMaxp, font(NOTO), null));
  }

  /**
   * Lato + Twemoji makes 9 U+FE0F copies. With the text font padded so that only some of them (or none) fit, the merge still ends with
   * 65,535 glyphs: the characters left without a copy keep (c, U+FE0F) on the emoji glyph itself, with W_FE0F_SEQUENCES. One glyph more
   * and the emoji font's own 624 glyphs do not fit.
   */
  static void fe0fCopiesAtTheLimit() throws Exception {
    Sfnt E = font(TWEMOJI);
    int m = E.numGlyphs(), dups = EmojiMerge.merge(font(LATO), E, null).font.numGlyphs() - font(LATO).numGlyphs() - m;
    check("Lato + Twemoji: 624 emoji glyphs and 9 U+FE0F copies", m == 624 && dups == 9, m + " and " + dups);
    TreeMap<Integer, Integer> ecmap = Cmap.read(E, null);
    Cmap.Uvs eu = Cmap.readUvs(E);
    for (int n : new int[] { 65535 - m - dups + 1, 65535 - m - dups + 3, 65535 - m }) {
      String name = String.format(Locale.ROOT, "%,d text glyphs + Twemoji (room for %d of the %d copies)", n, 65535 - m - n, dups);
      EmojiMerge.Result r;
      try {
        r = EmojiMerge.merge(padded(LATO, n), E, null);
      } catch (Exception x) {
        check(name + ": merges", false, x.toString());
        continue;
      }
      Sfnt M = Sfnt.parse(r.font.toBytes());
      check(name + ": merges into 65,535 glyphs, warning " + EmojiMerge.W_FE0F_SEQUENCES, M.numGlyphs() == 65535
          && r.warnings.contains(EmojiMerge.W_FE0F_SEQUENCES), M.numGlyphs() + " glyphs, warnings " + r.warnings);
      byte[][] mg = Glyf.readAll(M);
      TreeMap<Integer, Integer> mcmap = Cmap.read(M, null);
      Cmap.Uvs mu = Cmap.readUvs(M);
      int bad = 0, withVs = 0, copied = 0;
      String first = null;
      for (int cp : ecmap.keySet()) {
        if (!EmojiData.hasEmojiVariation(cp)) continue;
        withVs++;
        int g = (eu.glyph(0xFE0F, cp) >= 0 ? eu.glyph(0xFE0F, cp) : ecmap.get(cp)) + n, fe = resolve(mu, mcmap, cp, 0xFE0F);
        boolean copy = fe >= n + m && fe < 65535 && samePadded(mg[fe], mg[g]);
        if (copy) copied++;
        if (fe != g && !copy && bad++ == 0) first = String.format(Locale.ROOT, "U+%04X: glyph %d, want %d or a copy of it", cp, fe, g);
      }
      check(name + ": (c, U+FE0F) of the " + withVs + " characters with an emoji variation sequence gives the emoji glyph, or one of the "
          + (65535 - m - n) + " copies of it", bad == 0 && copied >= 65535 - m - n, bad + " differ, first " + first + "; " + copied + " copies");
    }
    int over = 65535 - m + 1;
    throwsFont(String.format(Locale.ROOT, "%,d text glyphs + Twemoji are refused with code too_many_glyphs, the emoji font counted as its 624 glyphs", over),
        "too_many_glyphs", String.format(Locale.ROOT, "%,d glyphs and the emoji font 624:", over), () -> EmojiMerge.merge(padded(LATO, over), E, null));
  }

  /** Damaged COLR / GSUB / GDEF in the emoji font, each refused with a sentence and code emoji_damaged (FontBuilder names the emoji font for it). */
  static void damaged() throws Exception {
    Sfnt E = font(SYNTH1);
    byte[] colr = E.table("COLR");
    Colr c = new Colr(colr);
    // the first PaintGlyph names a glyph the font does not have
    int pg = -1;
    for (int i = 0; i < c.layerCount() && pg < 0; i++) if (u8(colr, c.layerPaint(i)) == 10) pg = c.layerPaint(i);
    byte[] bad = colr.clone();
    Sfnt.w16(bad, pg + 4, 500);
    throwsFont("a PaintGlyph naming glyph 500 of 13 is refused", "emoji_damaged", "names glyph 500", () -> merge1("COLR", bad));
    byte[] fmt = colr.clone();
    fmt[pg] = 33;
    throwsFont("an unknown paint format is refused", "emoji_damaged", "kind of paint (33)", () -> merge1("COLR", fmt));
    byte[] order = colr.clone();
    Sfnt.w16(order, (int) c.bgl + 4, 12);                                 // the first base glyph after the second
    throwsFont("base glyphs out of order are refused", "emoji_damaged", "COLR) is damaged", () -> merge1("COLR", order));
    byte[] layers = colr.clone();
    for (int i = 0; i < c.baseCount(); i++) if (u8(colr, c.basePaint(i)) == 1) { Sfnt.w32(layers, c.basePaint(i) + 2, 1000); break; }
    throwsFont("a PaintColrLayers past the LayerList is refused", "emoji_damaged", "COLR) is damaged", () -> merge1("COLR", layers));
    byte[] v2 = colr.clone();
    Sfnt.w16(v2, 0, 2);
    throwsFont("a COLR version 2 is refused", "emoji_damaged", "newer version (2)", () -> merge1("COLR", v2));
    byte[] cut = Arrays.copyOf(colr, colr.length - 40);
    throwsFont("a cut-off COLR is refused", "emoji_damaged", null, () -> merge1("COLR", cut));
    byte[] loop = colr.clone();
    // a PaintTranslate whose child offset is 0 (which would be itself): a null offset is no paint, as renderers read it
    for (int i = 0; i < c.baseCount(); i++) if (u8(colr, c.basePaint(i)) == 14) { Colr.blank(loop, c.basePaint(i) + 1, 3); break; }
    String nullChild;
    try { merge1("COLR", loop); nullChild = null; } catch (Exception x) { nullChild = x.toString(); }
    check("a paint with a null child offset merges (it paints nothing)", nullChild == null, nullChild);
    byte[] gdef = E.table("GDEF").clone();
    Sfnt.w16(gdef, u16(gdef, 4), 3);                                      // glyph ClassDef format 3
    throwsFont("an emoji GDEF with an unknown ClassDef format is refused", "emoji_damaged", "GDEF", () -> EmojiMerge.merge(font(INTER), with(font(SYNTH1), "GDEF", gdef), null));
    Sfnt v0 = font(SYNTH0);
    byte[] c0 = v0.table("COLR").clone();
    Sfnt.w16(c0, 12, 400);                                                // 400 layers in a table that has 5
    throwsFont("a COLR v0 with more layers than the table holds is refused", "emoji_damaged", null, () -> EmojiMerge.merge(font(LATO), with(v0, "COLR", c0), null));
    // the same GDEF damage in the text font is the text font's: bad_font (FontBuilder names the face), and the sentence does not name the emoji font
    byte[] tgdef = font(INTER).table("GDEF").clone();
    Sfnt.w16(tgdef, u16(tgdef, 4), 3);
    throwsFont("a text GDEF with an unknown ClassDef format is refused with code bad_font", "bad_font", "This font's glyph definitions (GDEF)",
        () -> EmojiMerge.merge(with(font(INTER), "GDEF", tgdef), font(SYNTH1), null));
    // Twemoji's glyphs (up to 512 units) in a font that says 16 units per em, scaled 125 times to Lato's 2000 units: past 32,767
    Sfnt big = font(TWEMOJI);
    byte[] head = big.table("head").clone();
    Sfnt.w16(head, 18, 16);
    throwsFont("emoji too big for the text font at this size are refused with code emoji_too_big", "emoji_too_big", "too big",
        () -> EmojiMerge.merge(font(LATO), with(big, "head", head), null));
    boolean emojiOnes = true, others = false;
    for (String code : new String[] { "emoji_damaged", "emoji_too_complex", "emoji_too_big", "bitmap_emoji", "not_emoji", "no_palette", "emoji_outlines",
        "palette_damaged", "emoji_symbol" }) emojiOnes &= EmojiMerge.aboutEmoji(new Sfnt.FontException(code, "x"));
    for (String code : new String[] { "bad_font", "too_complex", "too_many_glyphs", "too_big", "text_has_color", "symbol_font", "cancelled" })
      others |= EmojiMerge.aboutEmoji(new Sfnt.FontException(code, "x"));
    check("aboutEmoji: the codes of the emoji font's own faults, not those of the text font's or of both", emojiOnes && !others);
  }

  static Sfnt with(Sfnt f, String tag, byte[] t) { f.put(tag, t); return f; }

  static EmojiMerge.Result merge1(String tag, byte[] t) throws Exception { return EmojiMerge.merge(font(NOTOSANS), with(font(SYNTH1), tag, t), null); }

  // ---------- progress and cancel ----------

  static final class Recorder implements FontProgress {
    final List<int[]> steps = new ArrayList<>();
    final Set<String> stages = new java.util.HashSet<>();
    int asked, cancelAfter = Integer.MAX_VALUE;
    @Override public void step(String stage, int done, int total) { stages.add(stage); steps.add(new int[] { done, total }); }
    @Override public boolean cancelled() { return ++asked > cancelAfter; }
  }

  static void progressAndCancel() throws Exception {
    for (String[] pair : new String[][] { { LATO, NOTO }, { INTER, SYNTH1 }, { LATO, SYNTH0 } }) {
      Recorder p = new Recorder();
      EmojiMerge.Options o = new EmojiMerge.Options();
      o.progress = p;
      EmojiMerge.merge(font(pair[0]), font(pair[1]), o);
      boolean mono = true;
      for (int i = 0; i < p.steps.size(); i++) mono &= p.steps.get(i)[1] == 1000 && p.steps.get(i)[0] >= (i == 0 ? 0 : p.steps.get(i - 1)[0]);
      String name = mergedName(pair[0], pair[1]);
      check(name + ": progress in stage \"emoji\", out of 1000, never going back, ending at 1000 (" + p.steps.size() + " steps)", mono
          && p.stages.equals(new java.util.HashSet<>(Arrays.asList(EmojiMerge.STAGE))) && p.steps.size() >= 8 && p.steps.get(p.steps.size() - 1)[0] == 1000);
      int calls = p.asked;
      int bad = 0;
      String first = null;
      for (int k = 0; k < calls; k += Math.max(1, calls / 60)) {
        Recorder q = new Recorder();
        q.cancelAfter = k;
        EmojiMerge.Options o2 = new EmojiMerge.Options();
        o2.progress = q;
        try {
          EmojiMerge.merge(font(pair[0]), font(pair[1]), o2);
          if (bad++ == 0) first = "cancel at question " + k + " was not seen";
        } catch (Sfnt.FontException e) {
          if (!"cancelled".equals(e.code) && bad++ == 0) first = k + ": " + e;
        } catch (Throwable e) {
          if (bad++ == 0) first = k + ": " + e;
        }
      }
      check(name + ": Cancel at any of the " + calls + " points the merge asks stops it with code \"cancelled\"", calls >= 5 && bad == 0, first);
      Recorder last = new Recorder();
      last.cancelAfter = calls;
      EmojiMerge.Options o3 = new EmojiMerge.Options();
      o3.progress = last;
      check(name + ": a progress that never cancels lets it finish", EmojiMerge.merge(font(pair[0]), font(pair[1]), o3).font != null);
    }
  }

  // ---------- fuzz ----------

  /**
   * Damaged inputs: truncation, bit flips, random bytes and huge counts, in the whole file or in one table of the emoji or the text font.
   * Each merge must succeed or end in a FontException, quickly; a merged font must write and read back.
   */
  static void fuzz(long seed, int count, int seconds) throws Exception {
    Random rnd = new Random(seed);
    String[][] pairs = { { NOTOSANS, SYNTH1 }, { NOTOSANS, SYNTH0 }, { INTER, SYNTH1 }, { NOTOSANS, TWEMOJI }, { LATO, NOTO } };
    int[] weight = { 40, 20, 15, 15, 10 };
    long start = System.nanoTime(), slowest = 0;
    int ok = 0, refused = 0, other = 0, done = 0;
    String first = null;
    for (int it = 0; it < count; it++) {
      if (seconds > 0 && System.nanoTime() - start > seconds * 1000000000L) break;
      int w = rnd.nextInt(100), pi = 0;
      while (w >= weight[pi]) w -= weight[pi++];
      String[] pair = pairs[pi];
      boolean emoji = rnd.nextInt(10) < 7;
      long t0 = System.nanoTime();
      try {
        Sfnt[] f = { font(pair[0]), font(pair[1]) };
        int side = emoji ? 1 : 0;
        if (rnd.nextInt(4) == 0) {
          f[side] = Sfnt.parse(mutate(files.get(pair[side]), rnd, 12));
        } else {
          List<String> tags = f[side].tags();
          String tag = emoji && rnd.nextInt(3) > 0 ? new String[] { "COLR", "GSUB", "cmap", "glyf", "GDEF", "CPAL" }[rnd.nextInt(6)] : tags.get(rnd.nextInt(tags.size()));
          if (!f[side].has(tag)) tag = tags.get(rnd.nextInt(tags.size()));
          f[side].put(tag, mutate(f[side].table(tag), rnd, 0));
        }
        EmojiMerge.Result r = EmojiMerge.merge(f[0], f[1], null);
        Sfnt.parse(r.font.toBytes());
        ok++;
      } catch (Sfnt.FontException e) {
        refused++;
      } catch (Throwable e) {
        if (other++ == 0) {
          StackTraceElement[] st = e.getStackTrace();
          first = e + (st.length > 0 ? " at " + st[0] : "") + " (" + pair[0] + " + " + pair[1] + ", iteration " + it + ")";
        }
      }
      slowest = Math.max(slowest, System.nanoTime() - t0);
      done++;
    }
    System.out.println("     fuzz: " + done + " merges in " + (System.nanoTime() - start) / 1000000 + " ms (" + ok + " merged, " + refused
        + " refused), the slowest " + slowest / 1000000 + " ms");
    check("fuzz (seed " + Long.toHexString(seed) + "): every damaged input merges or is refused with a FontException, never another exception",
        other == 0 && done >= Math.min(count, 300), other + " others, first " + first);
    check("fuzz: no damaged input takes more than 4 s", slowest < 4000000000L, slowest / 1000000 + " ms");
  }

  static byte[] mutate(byte[] b, Random rnd, int keep) {
    byte[] t = b.clone();
    if (t.length <= keep + 2) return t;
    switch (rnd.nextInt(5)) {
      case 0: return Arrays.copyOf(t, keep + rnd.nextInt(t.length - keep));                      // cut off
      case 1: for (int i = 1 + rnd.nextInt(8); i > 0; i--) { int at = keep + rnd.nextInt(t.length - keep); t[at] ^= 1 << rnd.nextInt(8); } return t;
      case 2: { int at = keep + rnd.nextInt(t.length - keep); for (int i = 0; i < 1 + rnd.nextInt(16) && at + i < t.length; i++) t[at + i] = (byte) rnd.nextInt(); return t; }
      case 3: { int at = keep + rnd.nextInt(t.length - keep - 1) & ~1; t[at] = (byte) 0xFF; t[at + 1] = (byte) (rnd.nextBoolean() ? 0xFF : 0xFE); return t; }   // a huge count
      default: { int at = keep + rnd.nextInt(Math.max(1, t.length - keep - 3)) & ~1; if (at + 4 <= t.length) Sfnt.w32(t, at, rnd.nextBoolean() ? -1 : 0x7FFFFFF0); return t; }
    }
  }

  // ---------- hand-built tables ----------

  static void handBuilt() throws Exception {
    // a LigatureSubst over glyphs A=1 B=2 C=3 D=4 and F=9 (U+FE0F's glyph): coverage [A, C]
    //   A: [A F B], [A F B F]     C: [C B F D], and a 33-glyph ligature the scan leaves alone
    int A = 1, B = 2, C = 3, D = 4, F = 9, n = 100, dupBase = 200;
    int[][] setA = { { A, F, B }, { A, F, B, F } }, setC = { { C, B, F, D }, new int[33] };
    setC[1][0] = C;
    Arrays.fill(setC[1], 1, 33, F);
    byte[] t = ligatureSubst(new int[] { A, C }, new int[][][] { setA, setC });
    BitSet of = new BitSet();
    of.set(A);
    of.set(B);
    List<int[]> ligs = new ArrayList<>();
    EmojiMerge.ligatures(t, 0, 5, of, ligs, new long[] { 1000 }, null);
    List<String> got = new ArrayList<>();
    for (int[] l : ligs) got.add(Arrays.toString(l));
    check("ligatures: the ligatures that name A or B, as [lookup, glyphs...] in the font's order, not the one past 32 glyphs",
        got.equals(Arrays.asList("[5, 1, 9, 2]", "[5, 1, 9, 2, 9]", "[5, 3, 2, 9, 4]")), got.toString());
    // A's key is A itself (no character shows it alone); B's is a copy, glyph 200
    EmojiMerge.Front fr = new EmojiMerge.Front(n, dupBase, null);
    fr.keys.put(A, A + n);
    fr.keys.put(B, dupBase);
    fr.dups.add(B);
    EmojiMerge.restoreRules(ligs, F, fr, new long[] { 1000 }, null);
    int R = EmojiMerge.RESTORE, P = EmojiMerge.PASS;
    Map<Integer, List<List<Integer>>> want = new TreeMap<>();
    // A: before B as the cmap gives it (B+n), or before B's key (B with U+FE0F, not turned back yet)
    want.put(A + n, Arrays.asList(Arrays.asList(R, 0, B + n), Arrays.asList(R, 0, dupBase)));
    // B: [A F B] comes first in the font, so after A F the copy only becomes B (the same context in [A F B F] could never apply); then
    // C B F D; then the copy's catch-all
    want.put(dupBase, Arrays.asList(Arrays.asList(P, 2, A + n, F + n), Arrays.asList(R, 1, C + n, D + n), Arrays.asList(P, 0)));
    check("restoreRules: RESTORE where U+FE0F follows a key's glyph, PASS where it does not, with the glyphs before and after, in HarfBuzz's "
        + "order; a later key as its own glyph; a copy ends with a PASS without context", new TreeMap<>(fr.restore).equals(want), fr.restore.toString());

    // lookup 3: [G X] (a skin tone), [G F], [G Y], then [S Z G F] with S a symbol whose text glyph T the swap turns into S later, and Z
    // the joiner the emoji lookups now name as the text font's (glyph 50); lookup 4: [H F X], [H Y]
    int G = 11, X = 12, Y = 13, S = 14, Z = 15, H = 16, T = 40;
    byte[] t3 = ligatureSubst(new int[] { G, S }, new int[][][] { { { G, X }, { G, F }, { G, Y } }, { { S, Z, G, F } } });
    byte[] t4 = ligatureSubst(new int[] { H }, new int[][][] { { { H, F, X }, { H, Y } } });
    BitSet of2 = new BitSet();
    of2.set(G);
    of2.set(H);
    List<int[]> ligs2 = new ArrayList<>();
    EmojiMerge.ligatures(t3, 0, 3, of2, ligs2, new long[] { 1000 }, null);
    EmojiMerge.ligatures(t4, 0, 4, of2, ligs2, new long[] { 1000 }, null);
    EmojiMerge.Front f2 = new EmojiMerge.Front(n, dupBase, new int[] { Z, 50 });
    f2.keys.put(G, G + n);
    f2.keys.put(H, H + n);
    f2.swap.put(T, S + n);
    EmojiMerge.restoreRules(ligs2, F, f2, new long[] { 1000 }, null);
    Map<Integer, List<List<Integer>>> want2 = new TreeMap<>();
    // the ligature that starts furthest left first, as the symbol's emoji glyph and as its text glyph; then [G X] before [G F] (a key
    // before a skin tone stays one glyph, so the skin tone ligature still forms); [G Y] could never apply after the rule with no context
    want2.put(G + n, Arrays.asList(Arrays.asList(R, 2, T, 50), Arrays.asList(R, 2, S + n, 50), Arrays.asList(P, 0, X + n), Arrays.asList(R, 0)));
    // H: the PASS rule at the end does what no rule does, so it is left out
    want2.put(H + n, Arrays.asList(Arrays.asList(R, 0, X + n)));
    check("restoreRules: a twin rule with the text glyph of a symbol the swap changes later; PASS before a skin tone in the font's order; "
        + "the joiner as the text font's; nothing after a rule with no context; no PASS at the end", new TreeMap<>(f2.restore).equals(want2),
        f2.restore.toString());

    throwsFont("ligatures: a scan past its budget is refused as too complex: emoji_too_complex", "emoji_too_complex", "too complex",
        () -> EmojiMerge.ligatures(t, 0, 0, of, new ArrayList<>(), new long[] { 3 }, null));
    throwsFont("restoreRules: rules past the budget are refused as too complex: emoji_too_complex", "emoji_too_complex", "too complex",
        () -> EmojiMerge.restoreRules(ligs, F, fr, new long[] { 3 }, null));
    byte[] cov = { 0, 2, 0, 1, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0 };
    throwsFont("coverageList: a format 2 coverage of 65,536 glyphs is refused: emoji_too_complex", "emoji_too_complex", "too complex", () -> EmojiMerge.coverageList(cov, 0, new long[] { 1L << 30 }));
    throwsFont("ligatures: a ligature set offset past the table is refused", "bad_font", null,
        () -> EmojiMerge.ligatures(Arrays.copyOf(t, 12), 0, 0, of, new ArrayList<>(), new long[] { 1000 }, null));

    // coverage(): format 1 or 2, whichever is smaller; read back by the test's reader
    int[] runs = { 5, 6, 7, 8, 9, 10, 20, 21, 22, 23, 40 }, scattered = { 3, 9, 27, 81 };
    byte[] c1 = EmojiMerge.coverage(runs), c2 = EmojiMerge.coverage(scattered);
    boolean okc = u16(c1, 0) == 2 && u16(c2, 0) == 1;
    for (int i = 0; i < runs.length; i++) okc &= cov(c1, 0, runs[i]) == i;
    for (int i = 0; i < scattered.length; i++) okc &= cov(c2, 0, scattered[i]) == i;
    okc &= cov(c1, 0, 11) < 0 && cov(c2, 0, 4) < 0;
    check("coverage(): ranges as format 2, scattered glyphs as format 1, each glyph at its index", okc);
    int[] cls = { 0, 1, 1, 3, 3, 3, 0, 0, 2 };
    byte[] cd = EmojiMerge.classDef(cls);
    boolean okd = true;
    for (int i = 0; i < cls.length + 3; i++) okd &= classOf(cd, 0, i) == (i < cls.length ? cls[i] : 0);
    check("classDef(): one class per glyph, class 0 left out", okd && u16(cd, 2) == 3);
  }

  /** A LigatureSubst format 1 subtable: coverage of {@code first}, one set per first glyph of ligatures (each with glyph 77). */
  static byte[] ligatureSubst(int[] first, int[][][] sets) {
    Sfnt.Writer w = new Sfnt.Writer();
    w.u16(1).u16(0).u16(sets.length);
    for (int i = 0; i < sets.length; i++) w.u16(0);
    for (int i = 0; i < sets.length; i++) {
      int set = w.size();
      w.set16(6 + 2 * i, set);
      w.u16(sets[i].length);
      for (int k = 0; k < sets[i].length; k++) w.u16(0);
      for (int k = 0; k < sets[i].length; k++) {
        w.set16(set + 2 + 2 * k, w.size() - set);
        int[] l = sets[i][k];
        w.u16(77).u16(l.length);
        for (int c = 1; c < l.length; c++) w.u16(l[c]);
      }
    }
    w.set16(2, w.size());
    w.u16(1).u16(first.length);
    for (int g : first) w.u16(g);
    return w.toByteArray();
  }
}
