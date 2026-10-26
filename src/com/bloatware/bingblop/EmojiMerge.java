package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A colour emoji font merged into a text font, so the font package the Custom Fonts tab builds shows the chosen emoji wherever its text
 * font is used. Android draws a character with the first font that has it, but a character followed by U+FE0F only with a font whose
 * cmap has that variation sequence, else with the system emoji font: so every emoji the merged font shows gets its (c, U+FE0F).
 *
 * <p>FontBuilder calls {@link #merge} for the Regular and the Bold face once the text font has TrueType outlines and no variations left.
 * Only COLR emoji (vector layers, version 0 or 1) can go inside a font: bitmap emoji (CBDT, sbix) are refused with code "bitmap_emoji". The
 * emoji glyphs are appended after the text font's, so emoji glyph g becomes N + g (N: the text font's glyph count) and every emoji table is
 * patched in place by that one constant. Outlines and advances are scaled from the emoji font's units per em to the text font's; a COLR
 * v1 emoji is scaled as a whole by a PaintTransform around its paint, so the glyphs only its paints draw keep the emoji font's units. The
 * text font keeps its glyphs, hinting, GPOS, kern and GDEF. Its characters keep their glyphs except where Unicode shows the emoji form
 * ({@link EmojiData#emojiWins}); variation sequences then give either form (U+FE0F the emoji glyph, U+FE0E the text glyph). U+200D keeps
 * the text font's joiner, which the emoji sequences then name instead of their own ({@link #joiner}). The emoji font's GSUB lookups (ZWJ sequences, flags, keycaps, skin tones) follow the text font's and are turned on in {@link #FEATURE} for every
 * script ({@link OtLayout#merge}), behind a few lookups that give them the glyphs they expect (see {@link Front}). The input is untrusted:
 * every read is bounds-checked and a bad font ends in a {@link Sfnt.FontException}.
 * Pure Java, no Android classes.
 */
public final class EmojiMerge {

    private EmojiMerge() {}

    /** Progress stage name; the steps of {@link Cmap} and {@link OtLayout} are reported under it too. */
    public static final String STAGE = "emoji";
    /** The emoji size the page offers, on top of the units-per-em ratio. */
    public static final double MIN_SCALE = 0.8, MAX_SCALE = 1.25;

    public static final String W_EMOJI_GPOS = "The emoji font's positioning table (GPOS) was left out; its emoji do not need it to show.";
    public static final String W_GLYPH_CLASSES = "A few of the emoji font's sequences rely on glyph classes the merged font cannot keep, so they may show as separate emoji.";
    public static final String W_MORX = "The text font's Apple layout table (morx) was left out so that the emoji sequences work; its OpenType features are kept.";

    /** Lookup flag bits that read GDEF's glyph classes or mark sets (IgnoreBase, IgnoreLigatures, IgnoreMarks, UseMarkFilteringSet, MarkAttachmentType). */
    static final int CLASS_FLAGS = 0xFF1E;
    /**
     * The feature the emoji lookups are turned on by. Not ccmp: HarfBuzz's Indic and Khmer shapers apply ccmp one syllable at a time,
     * which splits emoji sequences in a run of those scripts (emoji take the script of the text around them); every shaper applies rlig
     * to the whole run, and the others at the same point as ccmp.
     */
    static final String FEATURE = "rlig";
    private static final int TOTAL = 1000;

    public static final class Options {
        /** Emoji size relative to the text: 1.0 makes an emoji as tall in the text font as it was in its own font. */
        public double scale = 1.0;
        /** May be null. */
        public FontProgress progress;
    }

    /** The merged font (a new font; neither input is changed) and sentences about what was left out. */
    public static final class Result {
        public final Sfnt font;
        public final List<String> warnings = new ArrayList<String>();
        /** The text font's glyph count: emoji glyph g is glyph textGlyphs + g in the merged font. */
        public final int textGlyphs, emojiGlyphs;
        /** Emoji font units to text font units. */
        public final double scale;

        Result(Sfnt font, int textGlyphs, int emojiGlyphs, double scale) {
            this.font = font; this.textGlyphs = textGlyphs; this.emojiGlyphs = emojiGlyphs; this.scale = scale;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The merge
    // ---------------------------------------------------------------------------------------------

    /** Merges {@code emoji} into a copy of {@code text}; see the class comment. Throws IllegalArgumentException for a scale outside 0.8..1.25. */
    public static Result merge(Sfnt text, Sfnt emoji, Options opt) throws Sfnt.FontException {
        if (text == null || emoji == null) throw new IllegalArgumentException("merge: a font is missing");
        Options o = opt == null ? new Options() : opt;
        if (!(o.scale >= MIN_SCALE && o.scale <= MAX_SCALE)) throw new IllegalArgumentException("merge: emoji scale " + o.scale);
        FontProgress p = o.progress;
        checkBasics(text, "text");
        checkBasics(emoji, "emoji");
        checkText(text);
        checkEmoji(emoji);
        int n = text.numGlyphs(), m = emoji.numGlyphs();
        if ((long) n + m > 0xFFFF) throw tooMany(n, m);
        // Emoji units to text units, kept to 16.16 so the COLR v1 wrapper scales the paints exactly as much as the outlines are scaled
        double s = Math.round(text.unitsPerEm() / (double) emoji.unitsPerEm() * o.scale * 65536) / 65536.0;
        step(p, 0);
        Maps maps = new Maps(text, emoji, p);
        int[] zwj = joiner(maps, emoji, sub(p, 20, 30));
        byte[][] tg = Glyf.readAll(text), eg = Glyf.readAll(emoji);
        Colr scan = new Colr(emoji.table("COLR"), m, p);
        scan.walk();
        Layers ly = new Layers(eg, scan, maps, n, m, p);
        Sfnt out = text.copy();
        out.sfntVersion = Sfnt.TRUETYPE;
        Result r = new Result(out, n, m, s);
        TreeMap<Integer, Integer> map = new TreeMap<Integer, Integer>();
        Front front = characters(maps, emoji, n, n + m + ly.copies.size(), zwj, out, map, r.warnings, p);
        int total = front.dupBase + front.dups.size();
        if (total > 0xFFFF) throw tooMany(n, total - n);
        step(p, 60);

        // Outlines: the text glyphs as they are; the emoji glyphs without hints, their components moved past the text glyphs, scaled
        // unless only COLR v1 paints draw them (the paints are scaled as a whole); then the emoji-units copies, and the copies some
        // (c, U+FE0F) sequences map to (see characters())
        byte[][] all = new byte[total][];
        System.arraycopy(tg, 0, all, 0, n);
        for (int i = 0; i < m; i++) {
            if ((i & 255) == 0) { FontProgress.Check.cancel(p); step(p, 60 + 440L * i / m); }
            all[n + i] = ly.kept(i) ? scaleGlyph(eg[i], n, m, 1.0, ly.copy) : scaleGlyph(eg[i], n, m, s, null);
        }
        for (int j = 0; j < ly.copies.size(); j++) all[n + m + j] = scaleGlyph(eg[ly.copies.get(j)], n, m, 1.0, ly.copy);
        for (int j = 0; j < front.dups.size(); j++) all[front.dupBase + j] = all[n + front.dups.get(j)];
        Glyf.write(out, all);
        headBounds(out, all, n, m, ly);
        step(p, 560);

        Glyf.Metrics tm = Glyf.Metrics.read(text), em = Glyf.Metrics.read(emoji), mm = new Glyf.Metrics(total);
        System.arraycopy(tm.advance, 0, mm.advance, 0, n);
        System.arraycopy(tm.lsb, 0, mm.lsb, 0, n);
        for (int i = n; i < total; i++) {
            int src = i < n + m ? i - n : i < front.dupBase ? ly.copies.get(i - n - m) : front.dups.get(i - front.dupBase);
            boolean unscaled = ly.unscaled(i, n, m);
            long adv = unscaled ? em.advance[src] : Math.round(em.advance[src] * s);
            if (adv > 0xFFFF) throw tooBig();
            mm.advance[i] = (int) adv;
            // FreeType and HarfBuzz draw a glyph moved left by xMin - lsb: that gap is kept (scaled), the rest follows the new outline
            int[] bb = Glyf.headerBounds(all[i]), ob = Glyf.headerBounds(eg[src]);
            long gap = ob == null ? 0 : ob[0] - em.lsb[src];
            long lsb = bb == null ? 0 : bb[0] - (unscaled ? gap : Math.round(gap * s));
            if (lsb < -32768 || lsb > 32767) throw tooBig();
            mm.lsb[i] = (int) lsb;
        }
        mm.write(out, all);
        step(p, 600);

        layout(text, emoji, n, total, out, front, r.warnings, p);
        out.put("COLR", scan.merged(n, s, ly.copy, front.dups, front.dupBase));
        out.put("CPAL", emoji.table("CPAL").clone());
        step(p, 880);

        os2(out, map);
        post(out);
        for (String t : new String[] { "hdmx", "LTSH", "VDMX", "DSIG", "vhea", "vmtx", "VORG", "CFF ", "CFF2" }) out.remove(t);
        all = null;
        verify(out, total, map, sub(p, 900, TOTAL));
        step(p, TOTAL);
        return r;
    }

    static Sfnt.FontException tooMany(int n, int m) {
        return new Sfnt.FontException("too_many_glyphs", String.format(Locale.ROOT, "The text font has %,d glyphs and the emoji font %,d: "
                + "together they pass the 65,535 glyphs a font can hold.", n, m));
    }

    /** head's bounds from the glyphs drawn at the text font's size: the emoji-units glyphs (inside COLR v1 paints) would make them too big. */
    private static void headBounds(Sfnt out, byte[][] all, int n, int m, Layers ly) throws Sfnt.FontException {
        if (ly.keep.isEmpty()) return;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int i = 0; i < all.length; i++) {
            int[] bb = Glyf.headerBounds(all[i]);
            if (bb == null || ly.unscaled(i, n, m) || Sfnt.s16(all[i], 0) == 0) continue;
            x0 = Math.min(x0, bb[0]); y0 = Math.min(y0, bb[1]); x1 = Math.max(x1, bb[2]); y1 = Math.max(y1, bb[3]);
        }
        if (x0 == Integer.MAX_VALUE) return;
        byte[] head = out.table("head").clone();
        Sfnt.w16(head, 36, x0); Sfnt.w16(head, 38, y0); Sfnt.w16(head, 40, x1); Sfnt.w16(head, 42, y1);
        out.put("head", head);
    }

    /** What Sfnt.parse checks, again: the caller may have replaced a table since. */
    private static void checkBasics(Sfnt f, String which) throws Sfnt.FontException {
        for (String t : new String[] { "head", "maxp", "hhea", "hmtx", "cmap" })
            if (!f.has(t)) throw new Sfnt.FontException("The " + which + " font has no " + t + " table.");
        if (f.table("head").length < 54 || f.table("maxp").length < 6 || f.table("hhea").length < 36)
            throw new Sfnt.FontException("The " + which + " font's head, maxp or hhea table is too short.");
        if (f.unitsPerEm() < 16 || f.unitsPerEm() > 16384)
            throw new Sfnt.FontException("The " + which + " font's units per em (" + f.unitsPerEm() + ") is out of range.");
        if (f.numGlyphs() < 1) throw new Sfnt.FontException("The " + which + " font has no glyphs.");
    }

    private static void checkText(Sfnt f) throws Sfnt.FontException {
        if (!f.has("glyf")) throw new Sfnt.FontException("Emoji can be added only to a font with TrueType outlines; this one has PostScript outlines.");
        if (f.has("fvar") || f.has("gvar")) throw new Sfnt.FontException("Emoji can be added only to a static font; pick an instance of this variable font first.");
        for (String t : new String[] { "COLR", "CBDT", "sbix", "SVG " })
            if (f.has(t)) throw new Sfnt.FontException("This font already has colour glyphs (" + t.trim() + "), so emoji cannot be added to it.");
        if (Cmap.readBest(f, null).symbol()) throw new Sfnt.FontException("This is a symbol font, so emoji cannot be added to it.");
    }

    /** Whether {@code f} can be merged in as the emoji font; FontBuilder asks before its slow steps, so the refusal names the emoji font. */
    static void checkEmoji(Sfnt f) throws Sfnt.FontException {
        if (!f.has("COLR")) {
            if (f.has("CBDT") || f.has("sbix"))
                throw new Sfnt.FontException("bitmap_emoji", "This emoji font draws its emoji as bitmaps (" + (f.has("CBDT") ? "CBDT" : "sbix")
                        + "): bitmap emoji cannot be put inside a font. Use a COLR emoji font, such as Noto Color Emoji or Twemoji.");
            throw new Sfnt.FontException("not_emoji", "This font has no colour emoji (no COLR table) to add.");
        }
        if (!f.has("CPAL")) throw new Sfnt.FontException("This emoji font has colour layers (COLR) but no colour palette (CPAL).");
        if (!f.has("glyf")) throw new Sfnt.FontException("This emoji font's outlines are PostScript ones; only an emoji font with TrueType outlines (glyf) can be added.");
        if (f.table("CPAL").length < 12) throw new Sfnt.FontException("This emoji font's colour palette (CPAL) is damaged.");
        if (Cmap.readBest(f, null).symbol()) throw new Sfnt.FontException("This emoji font's character map is a symbol one, so its emoji cannot be found.");
    }

    /**
     * An emoji glyph scaled by {@code s}, its hints dropped, its components renumbered past the {@code n} text glyphs (or to the emoji-units
     * copy {@code copy} names, when it is not null).
     */
    static byte[] scaleGlyph(byte[] raw, int n, int m, double s, int[] copy) throws Sfnt.FontException {
        if (raw.length == 0) return raw;
        Glyf.Glyph g = Glyf.decode(raw);
        if (g.isComposite()) {
            for (Glyf.Component k : g.components) {
                if (k.glyph >= m) throw new Sfnt.FontException("A composite glyph of the emoji font names glyph " + k.glyph + ", which the font does not have.");
                k.glyph = copy != null && copy[k.glyph] >= 0 ? copy[k.glyph] : k.glyph + n;
                if (k.xy) { k.dx = Glyf.round(k.dx * s); k.dy = Glyf.round(k.dy * s); }
            }
        } else if (s != 1.0) {
            for (int i = 0; i < g.x.length; i++) { g.x[i] = Glyf.round(g.x[i] * s); g.y[i] = Glyf.round(g.y[i] * s); }
        } else if (g.instructions.length == 0) {
            return raw;
        }
        g.instructions = new byte[0];
        try {
            return Glyf.encode(g);
        } catch (Sfnt.FontException e) {
            throw tooBig();
        }
    }

    static Sfnt.FontException tooBig() {
        return new Sfnt.FontException("The emoji would be too big for this font at this size.");
    }

    // ---------------------------------------------------------------------------------------------
    // Characters: cmap and variation sequences
    // ---------------------------------------------------------------------------------------------

    /** Both fonts' characters and variation sequences. */
    static final class Maps {
        final TreeMap<Integer, Integer> text, emoji;
        final Cmap.Uvs textUvs, emojiUvs;

        Maps(Sfnt t, Sfnt e, FontProgress p) throws Sfnt.FontException {
            text = Cmap.read(t, sub(p, 0, 10));
            emoji = Cmap.read(e, sub(p, 10, 20));
            textUvs = Cmap.readUvs(t);
            emojiUvs = Cmap.readUvs(e);
        }
    }

    /**
     * The emoji font's joiner glyph and the text font's, {@code [emoji, text]}, when the emoji sequences can name the text font's joiner
     * instead of their own: U+200D then keeps the text font's glyph, which the text font's own rules name (Indic conjuncts such as the
     * eyelash ra, Malayalam chillus and Sinhala yansaya). That needs both fonts to give U+200D a glyph no other character of theirs has, and
     * the emoji GSUB to name its joiner only inside sequences ({@link OtLayout#onlyInSequences}), as every emoji font checked does. Null
     * otherwise, and when either font has no joiner: then the emoji font's joiner takes U+200D where both have one, as its sequences need.
     */
    static int[] joiner(Maps maps, Sfnt emoji, FontProgress p) throws Sfnt.FontException {
        Integer t = maps.text.get(EmojiData.ZWJ), e = maps.emoji.get(EmojiData.ZWJ);
        if (t == null || e == null) return null;
        int same = 0, work = 0;
        for (int g : maps.text.values()) { if ((++work & 1023) == 0) FontProgress.Check.cancel(p); if (g == t) same++; }
        for (int g : maps.emoji.values()) { if ((++work & 1023) == 0) FontProgress.Check.cancel(p); if (g == e) same++; }
        if (same != 2) return null;
        byte[] gsub = emoji.table("GSUB");
        if (gsub != null && !OtLayout.onlyInSequences(gsub, true, e, p)) return null;
        return new int[] { e, t };
    }

    /**
     * What the lookups put in front of the emoji font's own fix up, so its sequences see the glyphs they would see in the emoji font alone
     * (all merged ids). Where the emoji font alone keeps U+FE0F after a character as a glyph and one of its ligatures names the two, the
     * merged cmap gives (c, U+FE0F) a key: the character's emoji glyph g, or a copy of g when a character shows g alone (the copy draws the
     * same). The first lookup turns a key back into [g, U+FE0F] where a ligature names the two, and into g where one names g before
     * something else (rules as glyphs before, in reading order, and after); a copy no rule matches becomes g too. A text glyph that won its
     * character becomes the emoji glyph next to a joiner or a skin tone, or before the keycap mark, as the emoji font would show that
     * character there.
     */
    static final class Front {
        final int n, dupBase, zwjFrom, zwjTo;
        int fe0f = -1;
        /** Emoji glyph -> its key (a merged id): the glyph itself, or its copy. */
        final TreeMap<Integer, Integer> keys = new TreeMap<Integer, Integer>();
        /** The emoji glyphs that have a copy, in copy order: copy j is merged glyph dupBase + j. */
        final List<Integer> dups = new ArrayList<Integer>();
        /** Key -> its rules in order, each {@code [RESTORE or PASS, count before, before..., after...]}. */
        final TreeMap<Integer, List<List<Integer>>> restore = new TreeMap<Integer, List<List<Integer>>>();
        /** Text glyph -> emoji glyph; the keycap bases (# * 0-9) and the symbols (above U+00FF) among them. */
        final TreeMap<Integer, Integer> swap = new TreeMap<Integer, Integer>();
        final TreeSet<Integer> keycapBases = new TreeSet<Integer>(), symbols = new TreeSet<Integer>();
        /** The keycap mark's and the joiner's glyphs (-1 when the font has none), and the glyphs a symbol takes the emoji form before. */
        int keycap = -1, zwj = -1;
        final TreeSet<Integer> joiners = new TreeSet<Integer>();

        /** {@code n}: the text glyph count; {@code dupBase}: the first copy's id; {@code joiner}: from {@link EmojiMerge#joiner}, or null. */
        Front(int n, int dupBase, int[] joiner) {
            this.n = n;
            this.dupBase = dupBase;
            zwjFrom = joiner == null ? -1 : joiner[0];
            zwjTo = joiner == null ? -1 : joiner[1];
        }

        /** The merged glyph the emoji lookups name emoji glyph g by. */
        int id(int g) { return g == zwjFrom ? zwjTo : g + n; }
    }

    /**
     * Builds the merged cmap into {@code out} and fills {@code map} with it: the text font's characters, the emoji font's where the text font
     * has none or where {@link EmojiData#emojiWins} (U+200D stays with the text font when {@code joiner} is not null). Variation sequences:
     * the text font's; the emoji font's (a "default" one becomes the emoji glyph where the text glyph won); (c, U+FE0E) to the text glyph
     * where the emoji glyph won (and to the default where the text glyph won); (c, U+FE0F) to the emoji glyph where the text glyph won, and
     * to the default for each character with an emoji variation sequence ({@link EmojiData#hasEmojiVariation}) that shows the emoji glyph:
     * Android takes (c, U+FE0F) from the first font whose cmap has the pair and else from the system emoji font. Where a ligature of the
     * emoji font names the character's glyph and U+FE0F, (c, U+FE0F) maps to a key instead (see {@link Front}).
     */
    private static Front characters(Maps maps, Sfnt emoji, int n, int dupBase, int[] joiner, Sfnt out, TreeMap<Integer, Integer> map,
            List<String> warnings, FontProgress p) throws Sfnt.FontException {
        TreeMap<Integer, Integer> tmap = maps.text, emap = maps.emoji;
        Cmap.Uvs tuvs = maps.textUvs, euvs = maps.emojiUvs;
        map.putAll(tmap);
        TreeSet<Integer> textWon = new TreeSet<Integer>(), emojiWon = new TreeSet<Integer>();
        int work = 0;
        for (Map.Entry<Integer, Integer> e : emap.entrySet()) {
            if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
            int cp = e.getKey();
            if (!tmap.containsKey(cp)) map.put(cp, e.getValue() + n);
            else if (cp == EmojiData.ZWJ && joiner != null) continue;             // the text font's joiner, which the emoji lookups name
            else if (EmojiData.emojiWins(cp) || cp == EmojiData.ZWJ) { map.put(cp, e.getValue() + n); emojiWon.add(cp); }
            else textWon.add(cp);
        }
        Cmap.Uvs uvs = tuvs.copy();
        // A text "default" sequence of a character the emoji glyph took over keeps showing the text glyph
        for (int sel : tuvs.selectors())
            for (int[] r : tuvs.defaultRanges(sel))
                for (int cp : emojiWon.subSet(r[0], true, r[1], true)) {
                    if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
                    uvs.put(sel, cp, tmap.get(cp));
                }
        // The emoji font's sequences, unless the text font has the same one
        for (int sel : euvs.selectors()) {
            for (Map.Entry<Integer, Integer> e : euvs.glyphs(sel).entrySet()) {
                if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
                if (!tuvs.has(sel, e.getKey())) uvs.put(sel, e.getKey(), e.getValue() + n);
            }
            for (int[] r : euvs.defaultRanges(sel))
                for (Map.Entry<Integer, Integer> e : emap.subMap(r[0], true, r[1], true).entrySet()) {
                    if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
                    int cp = e.getKey();
                    if (tuvs.has(sel, cp)) continue;
                    int g = e.getValue() + n;
                    if (map.get(cp) == g || sel == EmojiData.TEXT_SELECTOR) uvs.addDefault(sel, cp);     // the text glyph is the text form
                    else uvs.put(sel, cp, g);
                }
        }
        // U+FE0F asks for the emoji form, U+FE0E for the text form, whichever glyph the character shows alone. asked: character -> emoji
        // glyph, where the emoji font alone has no (c, U+FE0F), so that its shaping sees U+FE0F as a glyph after c's
        int fe = EmojiData.EMOJI_SELECTOR, te = EmojiData.TEXT_SELECTOR;
        TreeMap<Integer, Integer> asked = new TreeMap<Integer, Integer>();
        for (int cp : textWon) {
            if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
            if (!emojiBase(cp)) continue;
            int own = euvs.glyph(fe, cp);
            uvs.put(fe, cp, (own >= 0 ? own : emap.get(cp)) + n);
            if (!euvs.has(fe, cp)) asked.put(cp, emap.get(cp));
            if (!tuvs.has(te, cp)) uvs.addDefault(te, cp);
        }
        for (int cp : emojiWon)
            if (!(tuvs.has(te, cp) && !tuvs.isDefault(te, cp))) uvs.put(te, cp, tmap.get(cp));
        for (Map.Entry<Integer, Integer> e : emap.entrySet()) {
            if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
            int cp = e.getKey(), g = e.getValue();
            if (EmojiData.hasEmojiVariation(cp) && map.get(cp) == g + n && !uvs.has(fe, cp)) asked.put(cp, g);
        }

        Front fr = new Front(n, dupBase, joiner);
        swaps(tmap, emap, textWon, map, n, fr);
        // The asked glyphs that a ligature names before U+FE0F get a key; the others need no U+FE0F glyph, so a default sequence will do
        Integer f = emap.get(fe);
        byte[] gsub = emoji.table("GSUB");
        long[] budget = { LIGATURE_WORK };
        List<int[]> ligs = new ArrayList<int[]>();
        BitSet named = new BitSet();
        if (f != null && gsub != null && !asked.isEmpty()) {
            BitSet of = new BitSet();
            for (int g : asked.values()) of.set(g);
            ligs = ligatures(gsub, of, budget, p);
            for (int[] l : ligs)
                for (int c = 1; c + 1 < l.length; c++) if (l[c + 1] == f && of.get(l[c])) named.set(l[c]);
        }
        HashSet<Integer> nominal = new HashSet<Integer>(map.values());
        for (Map.Entry<Integer, Integer> e : asked.entrySet()) {
            int cp = e.getKey(), g = e.getValue();
            if (!named.get(g)) {
                if (map.get(cp) == g + n) uvs.addDefault(fe, cp);
                continue;
            }
            Integer key = fr.keys.get(g);
            if (key == null) {
                if (nominal.contains(g + n)) {                                // a character shows g alone: U+FE0F gets a copy to tell
                    key = dupBase + fr.dups.size();
                    fr.dups.add(g);
                } else {
                    key = g + n;
                }
                fr.keys.put(g, key);
            }
            uvs.put(fe, cp, key);
        }
        out.put("cmap", Cmap.build(map, uvs, warnings, sub(p, 30, 50)));
        if (!fr.keys.isEmpty()) {
            fr.fe0f = f + n;
            restoreRules(ligs, f, fr, budget, p);
        }
        return fr;
    }

    /** Characters U+FE0F can ask the emoji form of: above Latin-1 anything, in Latin-1 the emoji ones (# * 0-9 (c) (R)). */
    static boolean emojiBase(int cp) {
        return cp > 0xFF || keycapBase(cp) || cp == 0xA9 || cp == 0xAE;
    }

    static boolean keycapBase(int cp) { return cp == '#' || cp == '*' || (cp >= '0' && cp <= '9'); }

    /** Fills the swaps of {@link Front}: each text glyph that won a character the emoji font has, unless two such characters share it. */
    private static void swaps(TreeMap<Integer, Integer> tmap, TreeMap<Integer, Integer> emap, Set<Integer> textWon, TreeMap<Integer, Integer> map,
            int n, Front fr) {
        Integer k = map.get(EmojiData.KEYCAP), z = map.get(EmojiData.ZWJ);
        fr.keycap = k == null ? -1 : k;
        fr.zwj = z == null ? -1 : z;
        if (z != null) fr.joiners.add(z);
        for (int cp = 0x1F3FB; cp <= 0x1F3FF; cp++) if (map.containsKey(cp)) fr.joiners.add(map.get(cp));
        HashSet<Integer> shared = new HashSet<Integer>();
        for (int cp : textWon) {
            if (!(cp > 0xFF || keycapBase(cp))) continue;
            int t = tmap.get(cp), e = emap.get(cp) + n;
            Integer was = fr.swap.put(t, e);
            if (was != null && was != e) shared.add(t);
            (cp > 0xFF ? fr.symbols : fr.keycapBases).add(t);
        }
        for (int t : shared) fr.swap.remove(t);
        fr.symbols.retainAll(fr.swap.keySet());
        fr.keycapBases.retainAll(fr.swap.keySet());
        fr.keycapBases.removeAll(fr.symbols);
        if (fr.keycap < 0) fr.keycapBases.clear();
        if (fr.joiners.isEmpty()) fr.symbols.clear();
        fr.swap.keySet().retainAll(union(fr.symbols, fr.keycapBases));
    }

    static TreeSet<Integer> union(Set<Integer> a, Set<Integer> b) {
        TreeSet<Integer> u = new TreeSet<Integer>(a);
        u.addAll(b);
        return u;
    }

    /** Most glyph reads the ligature scan for the U+FE0F rules may do: a hundred times what the catalog fonts need. */
    static final long LIGATURE_WORK = 1L << 23;
    /** Longest ligature the U+FE0F rules look at; emoji sequences have up to about a dozen glyphs. */
    static final int LONGEST = 32;
    /** What a rule of {@link Front#restore} does with its key: back to [glyph, U+FE0F], or to the glyph alone. */
    static final int RESTORE = 0, PASS = 1;

    /**
     * The ligatures of the emoji font's LigatureSubst lookups (those a feature turns on) that name a glyph of {@code of}, in the order
     * HarfBuzz tries them (lookup, subtable, then the font's order), each as {@code [lookup, first glyph, other glyphs...]}.
     */
    static List<int[]> ligatures(byte[] gsub, BitSet of, long[] budget, FontProgress p) throws Sfnt.FontException {
        OtLayout.Table et = OtLayout.parse(gsub, true);
        TreeSet<Integer> on = new TreeSet<Integer>();
        for (OtLayout.Feature ft : et.features) on.addAll(ft.lookups);
        List<int[]> out = new ArrayList<int[]>();
        for (int li : on) {
            if (li >= et.lookups.size()) continue;
            OtLayout.Lookup l = et.lookups.get(li);
            if (l.type != 4) continue;
            for (OtLayout.Subtable st : l.subtables) ligatures(et.sources.get(st.source), st.offset, li, of, out, budget, p);
        }
        return out;
    }

    /** Adds the ligatures of the LigatureSubst subtable at {@code off} (of lookup {@code lookup}) that name a glyph of {@code of}. */
    static void ligatures(byte[] t, int off, int lookup, BitSet of, List<int[]> out, long[] budget, FontProgress p) throws Sfnt.FontException {
        if (Sfnt.u16(t, off) != 1) return;
        int[] first = coverageList(t, off + Sfnt.u16(t, off + 2), budget);
        int sets = Math.min(first.length, Sfnt.u16(t, off + 4));
        Sfnt.need(t, off + 6, 2L * sets);
        for (int k = 0; k < sets; k++) {
            int set = off + Sfnt.u16(t, off + 6 + 2 * k);
            int ligs = Sfnt.u16(t, set);
            Sfnt.need(t, set + 2, 2L * ligs);
            for (int i = 0; i < ligs; i++) {
                if ((budget[0] -= 1) < 0) throw tooComplex();
                if ((budget[0] & 1023) == 0) FontProgress.Check.cancel(p);
                int lig = set + Sfnt.u16(t, set + 2 + 2 * i);
                int comps = Sfnt.u16(t, lig + 2);
                if (comps < 2 || comps > LONGEST) continue;
                Sfnt.need(t, lig + 4, 2L * (comps - 1));
                if ((budget[0] -= comps) < 0) throw tooComplex();
                int[] l = new int[comps + 1];
                l[0] = lookup;
                l[1] = first[k];
                boolean any = of.get(l[1]);
                for (int c = 1; c < comps; c++) {
                    l[c + 1] = Sfnt.u16(t, lig + 2 + 2 * c);
                    any |= of.get(l[c + 1]);
                }
                if (any) out.add(l);
            }
        }
    }

    /**
     * Fills {@link Front#restore} from the ligatures (as {@link #ligatures} lists them, {@code f} the emoji font's U+FE0F glyph) that name a
     * key's glyph: a RESTORE rule where U+FE0F follows the glyph, a PASS rule elsewhere, with the glyphs before it as the buffer has them by
     * then (U+FE0F back after the keys before) and those after it (keys not turned back yet). A rule with a symbol's emoji glyph in it gets a
     * twin with the text glyph there, since the swap lookup changes that glyph only after this one. A key's rules go in the order HarfBuzz
     * tries the ligatures in the emoji font alone: by lookup, then the ligature that starts furthest left, then the font's order. A rule that
     * could never apply (after one with the same context, or after one with none) is left out, and so are the PASS rules at the end; a copy
     * ends with a PASS rule without context, so that it always becomes its glyph.
     */
    static void restoreRules(List<int[]> ligs, int f, Front fr, long[] budget, FontProgress p) throws Sfnt.FontException {
        // A swap's emoji glyph -> its text glyph
        HashMap<Integer, Integer> text = new HashMap<Integer, Integer>();
        HashSet<Integer> shared = new HashSet<Integer>();
        for (Map.Entry<Integer, Integer> e : fr.swap.entrySet()) if (text.put(e.getValue(), e.getKey()) != null) shared.add(e.getValue());
        for (int x : shared) text.remove(x);
        HashMap<Integer, List<Place>> places = new HashMap<Integer, List<Place>>();
        int seq = 0;
        for (int[] l : ligs) {
            int k = l.length - 1;
            for (int at = 0; at < k; at++) {
                Integer key = fr.keys.get(l[1 + at]);
                if (key == null) continue;
                if ((budget[0] -= 4L * k) < 0) throw tooComplex();
                if ((seq & 255) == 0) FontProgress.Check.cancel(p);
                boolean back = at + 1 < k && l[2 + at] == f;
                List<Integer> rule = new ArrayList<Integer>(), alt = new ArrayList<Integer>();
                rule.add(back ? RESTORE : PASS);
                rule.add(at);
                alt.addAll(rule);
                boolean swapped = false;
                for (int c = 0; c < at; c++) {
                    int x = fr.id(l[1 + c]);
                    Integer t = l[2 + c] == f ? null : text.get(x);
                    rule.add(x);
                    alt.add(t != null ? t : x);
                    swapped |= t != null;
                }
                for (int c = at + (back ? 2 : 1); c < k; c++) {
                    boolean fe = c + 1 < k && l[2 + c] == f;
                    Integer kx = fe ? fr.keys.get(l[1 + c]) : null;               // a later key: still one glyph, U+FE0F not back yet
                    int x = kx != null ? kx : fr.id(l[1 + c]);
                    Integer t = fe ? null : text.get(x);
                    rule.add(x);
                    alt.add(t != null ? t : x);
                    swapped |= t != null;
                    if (kx != null) c++;
                }
                List<Place> ps = places.get(key);
                if (ps == null) places.put(key, ps = new ArrayList<Place>());
                if (swapped) ps.add(new Place(l[0], at, seq++, alt));
                ps.add(new Place(l[0], at, seq++, rule));
            }
        }
        for (int key : new TreeSet<Integer>(fr.keys.values())) {
            List<Place> ps = places.get(key);
            List<List<Integer>> rules = new ArrayList<List<Integer>>();
            if (ps != null) {
                Collections.sort(ps);
                HashSet<List<Integer>> contexts = new HashSet<List<Integer>>();
                for (Place pl : ps) {
                    if (!contexts.add(pl.rule.subList(1, pl.rule.size()))) continue;
                    rules.add(pl.rule);
                    if (pl.rule.size() == 2) break;
                }
            }
            while (!rules.isEmpty() && rules.get(rules.size() - 1).get(0) == PASS) rules.remove(rules.size() - 1);
            if (key >= fr.dupBase && (rules.isEmpty() || rules.get(rules.size() - 1).size() > 2)) rules.add(Arrays.asList(PASS, 0));
            if (!rules.isEmpty()) fr.restore.put(key, rules);
        }
    }

    /** A rule of {@link #restoreRules} and where its ligature is: lookup, the key's place in it, the order the ligatures were listed in. */
    private static final class Place implements Comparable<Place> {
        final int lookup, at, seq;
        final List<Integer> rule;

        Place(int lookup, int at, int seq, List<Integer> rule) { this.lookup = lookup; this.at = at; this.seq = seq; this.rule = rule; }

        @Override public int compareTo(Place o) {
            if (lookup != o.lookup) return lookup < o.lookup ? -1 : 1;
            if (at != o.at) return at > o.at ? -1 : 1;
            return seq < o.seq ? -1 : seq > o.seq ? 1 : 0;
        }
    }

    static Sfnt.FontException tooComplex() {
        return new Sfnt.FontException("The emoji font's substitution table (GSUB) is too complex to merge.");
    }

    /** The glyphs of the Coverage table at {@code off} in coverage index order (a format 2 range counts against {@code budget}). */
    static int[] coverageList(byte[] t, int off, long[] budget) throws Sfnt.FontException {
        int fmt = Sfnt.u16(t, off), count = Sfnt.u16(t, off + 2);
        if (fmt == 1) {
            Sfnt.need(t, off + 4, 2L * count);
            int[] g = new int[count];
            for (int i = 0; i < count; i++) g[i] = Sfnt.u16(t, off + 4 + 2 * i);
            return g;
        }
        if (fmt != 2) return new int[0];
        Sfnt.need(t, off + 4, 6L * count);
        long total = 0;
        for (int i = 0; i < count; i++) {
            int r = off + 4 + 6 * i, a = Sfnt.u16(t, r), b = Sfnt.u16(t, r + 2);
            if (b >= a) total += b - a + 1;
        }
        if (total > 0xFFFF || (budget[0] -= total) < 0) throw tooComplex();
        int[] g = new int[(int) total];
        int k = 0;
        for (int i = 0; i < count; i++) {
            int r = off + 4 + 6 * i, a = Sfnt.u16(t, r), b = Sfnt.u16(t, r + 2);
            for (int x = a; x <= b; x++) g[k++] = x;
        }
        return g;
    }

    // ---------------------------------------------------------------------------------------------
    // Layout: GSUB (and GDEF when the emoji lookups need glyph classes)
    // ---------------------------------------------------------------------------------------------

    private static void layout(Sfnt text, Sfnt emoji, int n, int total, Sfnt out, Front fr, List<String> warnings, FontProgress p)
            throws Sfnt.FontException {
        if (emoji.has("GPOS")) warnings.add(W_EMOJI_GPOS);
        byte[] egsub = emoji.table("GSUB");
        if (egsub == null) return;
        OtLayout.Table t = text.has("GSUB") ? OtLayout.parse(text.table("GSUB"), true) : null;
        int tl = t == null ? 0 : t.lookups.size();
        // In front of the emoji lookups: [keys back] [text glyph -> emoji glyph in context], then the steps those two call: [key ->
        // glyph, FE0F] [copy -> glyph] [text glyph -> emoji glyph]
        TreeMap<Integer, Integer> withFe = new TreeMap<Integer, Integer>(), copyBack = new TreeMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> k : fr.keys.entrySet()) {
            List<List<Integer>> rules = fr.restore.get(k.getValue());
            if (rules == null) continue;
            for (List<Integer> r : rules) {
                if (r.get(0) == RESTORE) withFe.put(k.getValue(), k.getKey() + n);
                else if (k.getValue() >= fr.dupBase) copyBack.put(k.getValue(), k.getKey() + n);
            }
        }
        boolean restore = !fr.restore.isEmpty(), swap = !fr.swap.isEmpty();
        int next = tl + (restore ? 1 : 0) + (swap ? 1 : 0);
        int restoreStep = withFe.isEmpty() ? -1 : next++, copyStep = copyBack.isEmpty() ? -1 : next++, swapStep = swap ? next++ : -1;
        int extra = next - tl;
        byte[] e = egsub.clone();
        if (fr.zwjFrom >= 0) OtLayout.shiftGlyphs(e, true, n, fr.zwjFrom, fr.zwjTo, sub(p, 700, 740));
        else OtLayout.shiftGlyphs(e, true, n, sub(p, 700, 740));
        OtLayout.shiftLookupIndexes(e, true, tl + extra, sub(p, 740, 760));
        OtLayout.Table added = OtLayout.parse(e, true);
        for (OtLayout.Feature f : added.features)
            for (int i = 0; i < f.lookups.size(); i++) f.lookups.set(i, f.lookups.get(i) + extra);
        if (extra > 0) {
            Sfnt.Writer w = new Sfnt.Writer();
            w.u32(0);                                                    // subtables start past offset 0, which means "none"
            int src = added.sources.size();
            OtLayout.Feature f = new OtLayout.Feature(FEATURE);
            List<OtLayout.Lookup> front = new ArrayList<OtLayout.Lookup>(), steps = new ArrayList<OtLayout.Lookup>();
            if (restore) {
                OtLayout.Lookup ctx = lookup(6);
                for (Map.Entry<Integer, List<List<Integer>>> en : fr.restore.entrySet())
                    restoreSubtables(w, src, ctx, en.getKey(), en.getValue(), restoreStep, en.getKey() >= fr.dupBase ? copyStep : -1);
                front.add(ctx);
            }
            if (swap) {
                OtLayout.Lookup ctx = lookup(6);
                int[] none = new int[0], joiners = toArray(fr.joiners);
                if (!fr.keycapBases.isEmpty()) {
                    ctx.subtables.add(new OtLayout.Subtable(src, w.size()));
                    chain(w, none, toArray(fr.keycapBases), new int[] { fr.keycap }, swapStep);
                }
                if (!fr.symbols.isEmpty()) {
                    ctx.subtables.add(new OtLayout.Subtable(src, w.size()));
                    chain(w, none, toArray(fr.symbols), joiners, swapStep);
                    if (fr.zwj >= 0) {
                        ctx.subtables.add(new OtLayout.Subtable(src, w.size()));
                        chain(w, new int[] { fr.zwj }, toArray(fr.symbols), none, swapStep);
                    }
                }
                front.add(ctx);
            }
            if (restoreStep >= 0) {
                OtLayout.Lookup step = lookup(2);
                step.subtables.add(new OtLayout.Subtable(src, w.size()));
                multiple(w, withFe, fr.fe0f);
                steps.add(step);
            }
            if (copyStep >= 0) {
                OtLayout.Lookup step = lookup(1);
                step.subtables.add(new OtLayout.Subtable(src, w.size()));
                single(w, copyBack);
                steps.add(step);
            }
            if (swap) {
                OtLayout.Lookup step = lookup(1);
                step.subtables.add(new OtLayout.Subtable(src, w.size()));
                single(w, fr.swap);
                steps.add(step);
            }
            for (int i = 0; i < front.size(); i++) f.lookups.add(i);
            front.addAll(steps);
            added.sources.add(w.toByteArray());
            added.lookups.addAll(0, front);
            added.features.add(f);
        }
        boolean classes = false;
        for (OtLayout.Lookup l : added.lookups) classes |= (l.flag & CLASS_FLAGS) != 0;
        if (classes && !mergeGdef(text, emoji, n, total, fr, out, added, extra)) warnings.add(W_GLYPH_CLASSES);
        OtLayout.Table merged = OtLayout.merge(t, added, FEATURE, sub(p, 760, 780));
        out.put("GSUB", OtLayout.write(merged, sub(p, 780, 850)));
        if (out.has("morx") || out.has("mort")) {
            out.remove("morx");
            out.remove("mort");
            warnings.add(W_MORX);
        }
    }

    private static OtLayout.Lookup lookup(int type) {
        OtLayout.Lookup l = new OtLayout.Lookup();
        l.type = type;
        return l;
    }

    /**
     * ChainContextSubst format 1 subtables for {@code key}: its rules (see {@link Front#restore}) in order, a RESTORE rule running lookup
     * {@code restoreStep} on the key, a PASS rule {@code copyStep} (none when it is -1), split so each subtable stays within its 16-bit
     * offsets. A rule with no lookup to run still applies: HarfBuzz moves past the key and tries no later rule.
     */
    private static void restoreSubtables(Sfnt.Writer w, int src, OtLayout.Lookup ctx, int key, List<List<Integer>> rules, int restoreStep,
            int copyStep) {
        for (int from = 0; from < rules.size(); ) {
            int to = from, bytes = 16;
            while (to < rules.size() && bytes + 14 + 2 * rules.get(to).size() < 0xF000) bytes += 14 + 2 * rules.get(to++).size();
            int start = w.size(), k = to - from;
            ctx.subtables.add(new OtLayout.Subtable(src, start));
            // format, coverage, one rule set (at 8): its rule count and offsets, the rules, then the coverage
            w.u16(1).u16(0).u16(1).u16(8).u16(k);
            for (int i = 0; i < k; i++) w.u16(0);
            for (int i = 0; i < k; i++) {
                List<Integer> r = rules.get(from + i);
                int before = r.get(1), after = r.size() - 2 - before, step = r.get(0) == RESTORE ? restoreStep : copyStep;
                w.set16(start + 10 + 2 * i, w.size() - start - 8);
                w.u16(before);
                for (int c = before + 1; c >= 2; c--) w.u16(r.get(c));         // backtrack: the nearest glyph first
                w.u16(1).u16(after);
                for (int c = before + 2; c < r.size(); c++) w.u16(r.get(c));
                if (step < 0) w.u16(0);
                else w.u16(1).u16(0).u16(step);
            }
            w.set16(start + 2, w.size() - start);
            w.bytes(coverage(new int[] { key }));
            from = to;
        }
    }

    /** ChainContextSubst format 3 with one glyph class per position: backtrack (nearest first), one input, lookahead; runs {@code step}. */
    private static void chain(Sfnt.Writer w, int[] back, int[] in, int[] ahead, int step) {
        int start = w.size(), nb = back.length == 0 ? 0 : 1, na = ahead.length == 0 ? 0 : 1;
        w.u16(3).u16(nb);
        if (nb > 0) w.u16(0);
        w.u16(1).u16(0).u16(na);
        if (na > 0) w.u16(0);
        w.u16(1).u16(0).u16(step);
        int at = start + 4;
        if (nb > 0) { w.set16(at, w.size() - start); w.bytes(coverage(back)); at += 2; }
        w.set16(at + 2, w.size() - start);
        w.bytes(coverage(in));
        if (na > 0) { w.set16(at + 6, w.size() - start); w.bytes(coverage(ahead)); }
    }

    /** MultipleSubst format 1: each key of {@code m} becomes its glyph in {@code m} followed by {@code fe0f}. */
    private static void multiple(Sfnt.Writer w, TreeMap<Integer, Integer> m, int fe0f) {
        int start = w.size(), k = m.size();
        w.u16(1).u16(0).u16(k);
        for (int i = 0; i < k; i++) w.u16(0);
        int i = 0;
        for (int g : m.values()) {
            w.set16(start + 6 + 2 * i++, w.size() - start);
            w.u16(2).u16(g).u16(fe0f);
        }
        w.set16(start + 2, w.size() - start);
        w.bytes(coverage(toArray(m.keySet())));
    }

    /** SingleSubst format 2 from a glyph map. */
    private static void single(Sfnt.Writer w, TreeMap<Integer, Integer> m) {
        int start = w.size();
        w.u16(2).u16(0).u16(m.size());
        for (int v : m.values()) w.u16(v);
        w.set16(start + 2, w.size() - start);
        w.bytes(coverage(toArray(m.keySet())));
    }

    static int[] toArray(Set<Integer> s) {
        int[] a = new int[s.size()];
        int i = 0;
        for (int v : s) a[i++] = v;
        return a;
    }

    /** A Coverage table of sorted, distinct glyphs: format 1 or 2, whichever is smaller. */
    static byte[] coverage(int[] gl) {
        int ranges = 0;
        for (int i = 0; i < gl.length; i++) if (i == 0 || gl[i] != gl[i - 1] + 1) ranges++;
        Sfnt.Writer w = new Sfnt.Writer(8 + 2 * gl.length);
        if (6L * ranges < 2L * gl.length) {
            w.u16(2).u16(ranges);
            for (int i = 0; i < gl.length; ) {
                int j = i;
                while (j + 1 < gl.length && gl[j + 1] == gl[j] + 1) j++;
                w.u16(gl[i]).u16(gl[j]).u16(i);
                i = j + 1;
            }
        } else {
            w.u16(1).u16(gl.length);
            for (int g : gl) w.u16(g);
        }
        return w.toByteArray();
    }

    /**
     * Gives the merged font a GDEF with the emoji font's glyph classes, mark attachment classes and mark glyph sets next to the text font's,
     * and renumbers the emoji lookups' flags to match. Only when both fonts have a GDEF: one made for the emoji alone would take the text
     * font's classes away from HarfBuzz's guess from Unicode. False when that cannot be done.
     */
    private static boolean mergeGdef(Sfnt text, Sfnt emoji, int n, int total, Front fr, Sfnt out, OtLayout.Table added, int extra)
            throws Sfnt.FontException {
        byte[] tg = text.table("GDEF"), eg = emoji.table("GDEF");
        if (tg == null && eg == null) return true;                     // HarfBuzz guesses the classes of both from Unicode, as for each alone
        if (tg == null || eg == null) return false;
        int m = emoji.numGlyphs();
        Gdef a = new Gdef(tg), b = new Gdef(eg);
        if (!a.ok || !b.ok || (a.glyphClass == 0) != (b.glyphClass == 0)) return false;
        int[] cls = new int[total], mark = new int[total];
        int[] ac = a.classes(a.glyphClass, n), bc = b.classes(b.glyphClass, m);
        int[] am = a.classes(a.markClass, n), bm = b.classes(b.markClass, m);
        int maxMark = 0;
        for (int v : am) maxMark = Math.max(maxMark, v);
        System.arraycopy(ac, 0, cls, 0, n);
        System.arraycopy(am, 0, mark, 0, n);
        for (int i = 0; i < m; i++) {
            cls[n + i] = bc[i];
            if (bm[i] != 0) {
                if (bm[i] + maxMark > 255) return false;
                mark[n + i] = bm[i] + maxMark;
            }
        }
        List<int[]> sets = a.sets(n, 0);
        int textSets = sets.size();
        sets.addAll(b.sets(m, n));
        if (sets.size() > 0xFFFF) return false;
        // A copy (see Front) has its glyph's classes and mark sets
        for (int j = 0; j < fr.dups.size(); j++) {
            int g = n + fr.dups.get(j), d = fr.dupBase + j;
            cls[d] = cls[g];
            mark[d] = mark[g];
            for (int i = textSets; i < sets.size(); i++) {
                int[] s = sets.get(i);
                if (Arrays.binarySearch(s, g) < 0) continue;
                s = Arrays.copyOf(s, s.length + 1);
                s[s.length - 1] = d;                                       // past every emoji glyph, so the set stays in order
                sets.set(i, s);
            }
        }
        for (int i = extra; i < added.lookups.size(); i++) {
            OtLayout.Lookup l = added.lookups.get(i);
            if ((l.flag >>> 8 & 0xFF) + maxMark > 255 || l.markFilteringSet + textSets > 0xFFFF) return false;
        }
        int minor = Math.max(a.minor, sets.isEmpty() ? 0 : 2);
        int hdr = minor >= 3 ? 18 : minor == 2 ? 14 : 12, shift = hdr - a.header;
        Sfnt.Writer w = new Sfnt.Writer(tg.length + 64);
        w.u16(1).u16(minor).zeros(hdr - 4);
        w.bytes(tg, a.header, tg.length - a.header).align(2);
        int gcOff = w.size();
        w.bytes(classDef(cls)).align(2);
        int maOff = 0;
        boolean anyMark = false;
        for (int v : mark) anyMark |= v != 0;
        if (anyMark) { maOff = w.size(); w.bytes(classDef(mark)).align(2); }
        int msOff = 0;
        if (!sets.isEmpty()) {
            msOff = w.size();
            w.u16(1).u16(sets.size());
            int at = w.size();
            for (int i = 0; i < sets.size(); i++) w.u32(0);
            for (int i = 0; i < sets.size(); i++) {
                w.set32(at + 4 * i, w.size() - msOff);
                w.bytes(coverage(sets.get(i)));
            }
        }
        // Every offset in the header is 16 bits: past 64 KB the table cannot point at its parts (gcOff and maOff too, when there are no sets)
        if (gcOff > 0xFFFF || maOff > 0xFFFF || msOff > 0xFFFF || (a.attach != 0 && a.attach + shift > 0xFFFF)
                || (a.carets != 0 && a.carets + shift > 0xFFFF)) return false;
        w.set16(4, gcOff);
        w.set16(6, a.attach == 0 ? 0 : a.attach + shift);
        w.set16(8, a.carets == 0 ? 0 : a.carets + shift);
        w.set16(10, maOff);
        if (minor >= 2) w.set16(12, msOff);
        if (minor >= 3) w.set32(14, (int) (a.store == 0 ? 0 : a.store + shift));
        out.put("GDEF", w.toByteArray());
        // The emoji lookups' mark classes and mark sets now come after the text font's
        for (int i = extra; i < added.lookups.size(); i++) {
            OtLayout.Lookup l = added.lookups.get(i);
            int mat = l.flag >>> 8 & 0xFF;
            if (mat != 0) l.flag = (l.flag & 0xFF) | (mat + maxMark) << 8;
            if ((l.flag & OtLayout.USE_MARK_FILTERING_SET) != 0) l.markFilteringSet += textSets;
        }
        return true;
    }

    /** The GDEF header: where its parts are. {@code ok} false when it is not one this code can rebuild. */
    static final class Gdef {
        final byte[] t;
        final int minor, header, glyphClass, attach, carets, markClass, markSets;
        final long store;
        final boolean ok;

        Gdef(byte[] t) throws Sfnt.FontException {
            this.t = t;
            int major = Sfnt.u16(t, 0);
            minor = Sfnt.u16(t, 2);
            header = minor >= 3 ? 18 : minor == 2 ? 14 : 12;
            Sfnt.need(t, 0, header);
            glyphClass = Sfnt.u16(t, 4); attach = Sfnt.u16(t, 6); carets = Sfnt.u16(t, 8); markClass = Sfnt.u16(t, 10);
            markSets = minor >= 2 ? Sfnt.u16(t, 12) : 0;
            store = minor >= 3 ? Sfnt.u32(t, 14) : 0;
            ok = major == 1 && (attach == 0 || attach >= header) && (carets == 0 || carets >= header) && (store == 0 || store >= header);
        }

        /** The ClassDef at {@code off} as one class per glyph (0 for glyphs it does not list). */
        int[] classes(int off, int glyphs) throws Sfnt.FontException {
            int[] c = new int[glyphs];
            if (off == 0) return c;
            int fmt = Sfnt.u16(t, off);
            if (fmt == 1) {
                int start = Sfnt.u16(t, off + 2), count = Sfnt.u16(t, off + 4);
                Sfnt.need(t, off + 6, 2L * count);
                for (int i = 0; i < count && start + i < glyphs; i++) c[start + i] = Sfnt.u16(t, off + 6 + 2 * i);
            } else if (fmt == 2) {
                int count = Sfnt.u16(t, off + 2);
                Sfnt.need(t, off + 4, 6L * count);
                long work = 0;
                for (int i = 0; i < count; i++) {
                    int r = off + 4 + 6 * i, a = Sfnt.u16(t, r), b = Math.min(Sfnt.u16(t, r + 2), glyphs - 1), v = Sfnt.u16(t, r + 4);
                    if ((work += Math.max(0, b - a + 1)) > 2L * glyphs + 65536) throw damaged();     // ranges laid over each other
                    for (int g = a; g <= b; g++) c[g] = v;
                }
            } else {
                throw damaged();
            }
            return c;
        }

        /** The mark glyph sets, each as sorted glyph ids below {@code glyphs}, plus {@code shift}. */
        List<int[]> sets(int glyphs, int shift) throws Sfnt.FontException {
            List<int[]> out = new ArrayList<int[]>();
            if (markSets == 0) return out;
            if (Sfnt.u16(t, markSets) != 1) throw damaged();
            int count = Sfnt.u16(t, markSets + 2);
            for (int i = 0; i < count; i++) {
                long off = markSets + Sfnt.u32(t, markSets + 4 + 4 * i);
                if (off > t.length - 4) throw damaged();
                BitSet b = coverageGlyphs(t, (int) off, glyphs);
                int[] g = new int[b.cardinality()];
                int k = 0;
                for (int x = b.nextSetBit(0); x >= 0; x = b.nextSetBit(x + 1)) g[k++] = x + shift;
                out.add(g);
            }
            return out;
        }

        static Sfnt.FontException damaged() { return new Sfnt.FontException("The emoji font's glyph definitions (GDEF) are damaged."); }
    }

    /** The glyphs (below {@code glyphs}) a Coverage table lists. */
    static BitSet coverageGlyphs(byte[] t, int off, int glyphs) throws Sfnt.FontException {
        BitSet b = new BitSet();
        int fmt = Sfnt.u16(t, off), count = Sfnt.u16(t, off + 2);
        if (fmt == 1) {
            Sfnt.need(t, off + 4, 2L * count);
            for (int i = 0; i < count; i++) { int g = Sfnt.u16(t, off + 4 + 2 * i); if (g < glyphs) b.set(g); }
        } else if (fmt == 2) {
            Sfnt.need(t, off + 4, 6L * count);
            for (int i = 0; i < count; i++) {
                int r = off + 4 + 6 * i, a = Sfnt.u16(t, r), e = Math.min(Sfnt.u16(t, r + 2), glyphs - 1);
                if (a <= e) b.set(a, e + 1);
            }
        } else {
            throw Gdef.damaged();
        }
        return b;
    }

    /** A ClassDef format 2 table of one class per glyph (class 0 left out). */
    static byte[] classDef(int[] cls) {
        Sfnt.Writer r = new Sfnt.Writer();
        int count = 0;
        for (int i = 0; i < cls.length; ) {
            int j = i;
            while (j + 1 < cls.length && cls[j + 1] == cls[i]) j++;
            if (cls[i] != 0) { r.u16(i).u16(j).u16(cls[i]); count++; }
            i = j + 1;
        }
        Sfnt.Writer w = new Sfnt.Writer(4 + r.size());
        w.u16(2).u16(count).bytes(r.toByteArray());
        return w.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------
    // COLR
    // ---------------------------------------------------------------------------------------------

    /**
     * The emoji glyphs that only COLR v1 paints draw (PaintGlyph targets and their components): they keep the emoji font's units, because
     * the merged COLR scales each emoji's whole paint instead (see {@link Colr#merged}), which leaves the paints' own coordinates as they
     * were. A glyph that is also needed at the text font's size (a base glyph, a COLR v0 layer, a character's glyph, a component of a
     * scaled glyph) gets an emoji-units copy after the emoji glyphs for the paints to use. None of the catalog fonts has one.
     */
    static final class Layers {
        final BitSet keep = new BitSet();
        /** Emoji glyph -> merged id of its emoji-units copy, or -1. */
        final int[] copy;
        /** The emoji glyphs that have a copy, in copy order. */
        final List<Integer> copies = new ArrayList<Integer>();
        private final BitSet both = new BitSet();

        Layers(byte[][] eg, Colr scan, Maps maps, int n, int m, FontProgress p) throws Sfnt.FontException {
            copy = new int[m];
            java.util.Arrays.fill(copy, -1);
            if (scan.targets.isEmpty()) return;
            keep.or(scan.targets);
            closure(eg, keep, m, p);
            BitSet need = new BitSet();
            need.set(0, m);
            need.andNot(keep);
            BitSet direct = new BitSet();
            direct.or(scan.bases);
            direct.or(scan.v0);
            for (int g : maps.emoji.values()) direct.set(g);
            for (int sel : maps.emojiUvs.selectors()) for (int g : maps.emojiUvs.glyphs(sel).values()) if (g < m) direct.set(g);
            direct.and(keep);
            need.or(direct);
            closure(eg, need, m, p);
            both.or(keep);
            both.and(need);
            for (int g = both.nextSetBit(0); g >= 0; g = both.nextSetBit(g + 1)) {
                copy[g] = n + m + copies.size();
                copies.add(g);
            }
        }

        /** Adds the components of every composite glyph in {@code set}, recursively. */
        private static void closure(byte[][] eg, BitSet set, int m, FontProgress p) throws Sfnt.FontException {
            List<Integer> work = new ArrayList<Integer>();
            for (int g = set.nextSetBit(0); g >= 0; g = set.nextSetBit(g + 1)) work.add(g);
            int done = 0;
            while (!work.isEmpty()) {
                if ((++done & 1023) == 0) FontProgress.Check.cancel(p);
                byte[] raw = eg[work.remove(work.size() - 1)];
                if (raw.length < 10 || Sfnt.s16(raw, 0) >= 0) continue;
                for (Glyf.Component k : Glyf.decode(raw).components) {
                    if (k.glyph >= m) throw new Sfnt.FontException("A composite glyph of the emoji font names glyph " + k.glyph + ", which the font does not have.");
                    if (!set.get(k.glyph)) { set.set(k.glyph); work.add(k.glyph); }
                }
            }
        }

        /** True when emoji glyph g keeps the emoji font's units in its own place (it is drawn only inside paints). */
        boolean kept(int g) { return keep.get(g) && !both.get(g); }

        /** True when merged glyph i is in emoji units: a kept glyph or an emoji-units copy. */
        boolean unscaled(int i, int n, int m) { return i >= n + m ? i < n + m + copies.size() : i >= n && kept(i - n); }
    }

    /**
     * A COLR table (version 0 or 1). {@link #walk} reads it once and checks it: every glyph id below the glyph count, every offset inside
     * the table, base glyphs in order, layers in range, no two patched fields laid over each other; it collects the glyphs each part names.
     * The walk is iterative and visits each paint, clip box and record once, so its work is bounded by the table's size. {@link #merged}
     * writes the table for the merged font: glyph ids past the text glyphs, each emoji's paint wrapped in a PaintTransform that scales it to
     * the text font's size (the paints' coordinates stay as they are: scaled in place, a few of Noto's 16.16 translations would overflow),
     * clip boxes scaled (rounded outwards), and every PaintColrGlyph behind an inverse scale, since the glyph it names is scaled already.
     */
    static final class Colr {
        private byte[] t;
        private final int limit;
        private final FontProgress p;
        // what the walk saw
        final BitSet bases = new BitSet(), v0 = new BitSet(), targets = new BitSet();
        private int version, layers, work;
        private long baseList;
        private final List<long[]> roots = new ArrayList<long[]>();    // per base record: record position, root paint position
        // patching: shift, copies, scale; the PaintColrGlyph nodes and the offsets that point at them
        private boolean patch;
        private int shift;
        private int[] copy;
        private double s;
        // the copies some (c, U+FE0F) sequences map to (see Front): their emoji glyphs, and the merged id of the first
        private List<Integer> dups = Collections.emptyList();
        private int dupBase;
        private BitSet seen, touched;
        private final List<Integer> colrGlyphs = new ArrayList<Integer>();
        private final List<int[]> edges = new ArrayList<int[]>();      // field position, base it is relative to, width (3 or 4), node

        Colr(byte[] t, int limit, FontProgress p) { this.t = t; this.limit = limit; this.p = p; }

        static Sfnt.FontException damaged() { return new Sfnt.FontException("The emoji font's colour table (COLR) is damaged."); }

        /** Reads and checks the table, collecting its glyphs. */
        void walk() throws Sfnt.FontException {
            patch = false;
            run();
        }

        /** The table for the merged font (a new array); see the class comment. Call {@link #walk} first. */
        byte[] merged(int shift, double s, int[] copy, List<Integer> dups, int dupBase) throws Sfnt.FontException {
            this.shift = shift; this.s = s; this.copy = copy; this.dups = dups; this.dupBase = dupBase; patch = true;
            byte[] orig = t;
            if (version == 1 && baseList != 0 && !roots.isEmpty()) t = wrapped(orig);
            else t = orig.clone();
            if (!dups.isEmpty()) t = v0Copies(t);
            run();
            if (!colrGlyphs.isEmpty()) t = inverses();
            byte[] out = t;
            t = orig;
            return out;
        }

        private void run() throws Sfnt.FontException {
            seen = new BitSet();
            touched = new BitSet();
            work = 0;
            colrGlyphs.clear();
            edges.clear();
            boolean first = !patch;
            version = Sfnt.u16(t, 0);
            if (version > 1) throw new Sfnt.FontException("The emoji font's colour table (COLR) is a newer version (" + version + ") than this app can read.");
            int numBase = Sfnt.u16(t, 2), numLayers = Sfnt.u16(t, 12);
            long base = Sfnt.u32(t, 4), layer = Sfnt.u32(t, 8);
            if (numBase > 0) Sfnt.need(t, base, 6L * numBase);
            if (numLayers > 0) Sfnt.need(t, layer, 4L * numLayers);
            for (int i = 0; i < numBase; i++) glyph((int) base + 6 * i, v0);
            for (int i = 0; i < numLayers; i++) glyph((int) layer + 4 * i, v0);
            if (version == 0) return;
            Sfnt.need(t, 0, 34);
            long bgl = Sfnt.u32(t, 14), ll = Sfnt.u32(t, 18), cl = Sfnt.u32(t, 22);
            List<Integer> stack = new ArrayList<Integer>();
            layers = 0;
            if (ll != 0) {
                if (ll > t.length - 4) throw damaged();
                long count = Sfnt.u32(t, (int) ll);
                Sfnt.need(t, ll + 4, 4 * count);
                layers = (int) count;
                for (int i = 0; i < count; i++) {
                    int at = (int) ll + 4 + 4 * i;
                    push(stack, ll + Sfnt.u32(t, at), at, (int) ll, 4);
                }
            }
            if (first) { baseList = bgl; roots.clear(); }
            if (bgl != 0) {
                if (bgl > t.length - 4) throw damaged();
                long count = Sfnt.u32(t, (int) bgl);
                Sfnt.need(t, bgl + 4, 6 * count);
                int prev = -1;
                for (int i = 0; i < count; i++) {
                    if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
                    int rec = (int) bgl + 4 + 6 * i;
                    int g = Sfnt.u16(t, rec);
                    if (g <= prev) throw damaged();                   // renderers search the list: it must be in order
                    prev = g;
                    glyph(rec, bases);
                    long root = bgl + Sfnt.u32(t, rec + 2);
                    if (first) roots.add(new long[] { rec, root });
                    push(stack, root, rec + 2, (int) bgl, 4);
                }
            }
            while (!stack.isEmpty()) paint(stack, stack.remove(stack.size() - 1));
            if (cl != 0) clips(cl);
        }

        /** Queues the paint at {@code off}, noting the offset field that points at it (for redirecting PaintColrGlyph). */
        private void push(List<Integer> stack, long off, int field, int rel, int width) throws Sfnt.FontException {
            if (off <= 0 || off >= t.length) throw damaged();
            if (patch && t[(int) off] == 11) edges.add(new int[] { field, rel, width, (int) off });
            stack.add((int) off);
        }

        /** A glyph id at {@code at}: checked and noted in {@code into}; when patching, moved past the text glyphs (or to its copy). */
        private void glyph(int at, BitSet into) throws Sfnt.FontException {
            int g = Sfnt.u16(t, at);
            if (patch && g >= limit && g - limit < dups.size()) {           // a copy's record (wrapped, v0Copies): id past the glyph count
                mark(at, 2);
                Sfnt.w16(t, at, dupBase + g - limit);
                return;
            }
            if (g >= limit) throw new Sfnt.FontException("The emoji font's colour table (COLR) names glyph " + g + ", which the font does not have.");
            if (!patch) { if (into != null) into.set(g); return; }
            mark(at, 2);
            Sfnt.w16(t, at, into == targets && copy != null && copy[g] >= 0 ? copy[g] : g + shift);
        }

        private void mark(int at, int len) throws Sfnt.FontException {
            int c = touched.nextSetBit(at);
            if (c >= 0 && c < at + len) throw damaged();                   // two records laid over each other
            touched.set(at, at + len);
        }

        private void paint(List<Integer> stack, int at) throws Sfnt.FontException {
            if (seen.get(at)) return;
            seen.set(at);
            if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
            int fmt = Sfnt.u8(t, at);
            switch (fmt) {
                case 1: {                                                    // PaintColrLayers: a run of the LayerList
                    long first = Sfnt.u32(t, at + 2), num = Sfnt.u8(t, at + 1);
                    if (first + num > layers) throw damaged();
                    return;
                }
                case 2: Sfnt.need(t, at, 5); return;                         // PaintSolid
                case 3: Sfnt.need(t, at, 9); return;                         // PaintVarSolid
                case 4: case 6: Sfnt.need(t, at, 16); colorLine(at, fmt == 6); return;   // linear, radial gradient
                case 5: case 7: Sfnt.need(t, at, 20); colorLine(at, true); return;
                case 8: Sfnt.need(t, at, 12); colorLine(at, false); return;  // sweep gradient
                case 9: Sfnt.need(t, at, 16); colorLine(at, true); return;
                case 10:                                                     // PaintGlyph: an outline filled by the paint below it
                    child(stack, at, 1);
                    glyph(at + 4, targets);
                    return;
                case 11:                                                     // PaintColrGlyph: another emoji's paint
                    glyph(at + 1, null);
                    if (patch) colrGlyphs.add(at);
                    return;
                case 12: case 13: {                                          // PaintTransform: the matrix must be there
                    child(stack, at, 1);
                    int m = Sfnt.u24(t, at + 4);
                    if (m == 0) throw damaged();
                    Sfnt.need(t, at + (long) m, fmt == 12 ? 24 : 28);
                    return;
                }
                case 14: case 16: case 28: Sfnt.need(t, at, 8); child(stack, at, 1); return;      // translate, scale, skew
                case 15: case 17: case 29: Sfnt.need(t, at, 12); child(stack, at, 1); return;
                case 18: case 30: Sfnt.need(t, at, 12); child(stack, at, 1); return;              // scale / skew around a centre
                case 19: case 31: Sfnt.need(t, at, 16); child(stack, at, 1); return;
                case 20: case 24: Sfnt.need(t, at, 6); child(stack, at, 1); return;               // uniform scale, rotate
                case 21: case 25: Sfnt.need(t, at, 10); child(stack, at, 1); return;
                case 22: case 26: Sfnt.need(t, at, 10); child(stack, at, 1); return;              // ... around a centre
                case 23: case 27: Sfnt.need(t, at, 14); child(stack, at, 1); return;
                case 32:                                                     // PaintComposite: a source over a backdrop
                    Sfnt.need(t, at, 8);
                    child(stack, at, 1);
                    child(stack, at, 5);
                    return;
                default:
                    throw new Sfnt.FontException("The emoji font's colour table (COLR) uses a kind of paint (" + fmt + ") this app does not know.");
            }
        }

        /** The paint an Offset24 at {@code at + k} points to (relative to the paint at {@code at}); a null offset is no paint. */
        private void child(List<Integer> stack, int at, int k) throws Sfnt.FontException {
            int o = Sfnt.u24(t, at + k);
            if (o != 0) push(stack, (long) at + o, at + k, at, 3);
        }

        /** A gradient's ColorLine must be inside the table with all its stops. */
        private void colorLine(int at, boolean var) throws Sfnt.FontException {
            int o = Sfnt.u24(t, at + 1);
            if (o == 0) throw damaged();
            long cl = (long) at + o;
            if (cl > t.length - 3) throw damaged();
            Sfnt.need(t, cl + 3, (long) Sfnt.u16(t, (int) cl + 1) * (var ? 10 : 6));
        }

        private void clips(long cl) throws Sfnt.FontException {
            if (cl >= t.length || Sfnt.u8(t, (int) cl) != 1) throw damaged();
            int at = (int) cl;
            long count = Sfnt.u32(t, at + 1);
            Sfnt.need(t, at + 5, 7 * count);
            BitSet boxes = new BitSet();
            for (int i = 0; i < count; i++) {
                if ((++work & 1023) == 0) FontProgress.Check.cancel(p);
                int rec = at + 5 + 7 * i;
                if (Sfnt.u16(t, rec) > Sfnt.u16(t, rec + 2)) throw damaged();
                glyph(rec, bases);
                glyph(rec + 2, bases);
                int bo = Sfnt.u24(t, rec + 4), box = at + bo;
                if (bo == 0) throw damaged();
                int fmt = Sfnt.u8(t, box);
                if (fmt != 1 && fmt != 2) throw damaged();
                Sfnt.need(t, box, fmt == 1 ? 9 : 13);
                if (!patch || boxes.get(box)) continue;
                boxes.set(box);
                edge(box + 1, -1); edge(box + 3, -1); edge(box + 5, 1); edge(box + 7, 1);
            }
        }

        /** A clip box edge times s, rounded outwards ({@code dir} -1 down, 1 up) so the scaled outlines stay inside. */
        private void edge(int at, int dir) throws Sfnt.FontException {
            double x = Sfnt.s16(t, at) * s;
            long r = dir < 0 ? (long) Math.floor(x + 1e-9) : (long) Math.ceil(x - 1e-9);
            if (r < -32768 || r > 32767) throw tooBig();
            mark(at, 2);
            Sfnt.w16(t, at, (int) r);
        }

        /**
         * The table with a new BaseGlyphList in front whose paints are PaintTransform wrappers (one shared scale matrix) around the old root
         * paints. Paint offsets cannot point backwards, so the wrappers go before everything else, which moves by a constant: the header's
         * offsets get it added, the offsets inside the moved part are relative and stay. The old BaseGlyphList stays behind, unused. A copy
         * of a glyph that has a paint gets a record of its own (a placeholder id past the glyph count, see {@link #glyph}) that points at
         * the same wrapper; when a clip box covers the glyph, the ClipList moves in front too (its boxes must come after it) with a record
         * for the copy.
         */
        private byte[] wrapped(byte[] orig) throws Sfnt.FontException {
            int count = roots.size();
            TreeMap<Integer, Integer> rootOf = new TreeMap<Integer, Integer>(), dupAt = new TreeMap<Integer, Integer>();
            for (int i = 0; i < count; i++) rootOf.put(Sfnt.u16(orig, (int) roots.get(i)[0]), i);
            for (int j = 0; j < dups.size(); j++) dupAt.put(dups.get(j), limit + j);
            List<int[]> more = new ArrayList<int[]>();                       // placeholder id, root
            for (int j = 0; j < dups.size(); j++) {
                Integer i = rootOf.get(dups.get(j));
                if (i != null) more.add(new int[] { limit + j, i });
            }
            long cl = Sfnt.u32(orig, 22);
            int clips = 0;
            TreeMap<Integer, Integer> boxOf = new TreeMap<Integer, Integer>(); // placeholder id -> its glyph's clip box (in orig)
            if (cl != 0 && !more.isEmpty()) {
                clips = (int) Sfnt.u32(orig, (int) cl + 1);
                for (int i = 0; i < clips; i++) {
                    int rec = (int) cl + 5 + 7 * i, a = Sfnt.u16(orig, rec), b = Sfnt.u16(orig, rec + 2);
                    for (int v : dupAt.subMap(a, true, b, true).values()) if (!boxOf.containsKey(v)) boxOf.put(v, (int) cl + Sfnt.u24(orig, rec + 4));
                }
            }
            int records = count + more.size(), clipCount = boxOf.isEmpty() ? 0 : clips + boxOf.size();
            long list = 34, wraps = list + 4 + 6L * records, affine = wraps + 7L * count, clipAt = affine + 24;
            long body = (clipAt + (clipCount == 0 ? 0 : 5 + 7L * clipCount) + 3) & ~3L;
            long size = body + orig.length - 34;
            if (size > Integer.MAX_VALUE - 8 || size > 0xFFFFFF) throw new Sfnt.FontException("The emoji font's colour table (COLR) is too big to merge.");
            long delta = body - 34;
            byte[] n = new byte[(int) size];
            System.arraycopy(orig, 0, n, 0, 34);
            System.arraycopy(orig, 34, n, (int) body, orig.length - 34);
            for (int f : new int[] { 4, 8, 18, 22, 26, 30 }) {
                long o = Sfnt.u32(orig, f);
                if (o == 0 || (f == 4 && Sfnt.u16(orig, 2) == 0) || (f == 8 && Sfnt.u16(orig, 12) == 0)) continue;
                if (o < 34) throw damaged();
                Sfnt.w32(n, f, (int) (o + delta));
            }
            Sfnt.w32(n, 14, (int) list);
            Sfnt.w32(n, (int) list, records);
            for (int i = 0; i < count; i++) {
                long[] r = roots.get(i);
                if (r[1] < 34) throw damaged();
                int rec = (int) list + 4 + 6 * i, w = (int) (wraps + 7L * i);
                Sfnt.w16(n, rec, Sfnt.u16(orig, (int) r[0]));
                Sfnt.w32(n, rec + 2, (int) (w - list));
                n[w] = 12;
                w24(n, w + 1, r[1] + delta - w);
                w24(n, w + 4, affine - w);
            }
            for (int i = 0; i < more.size(); i++) {
                int rec = (int) list + 4 + 6 * (count + i);
                Sfnt.w16(n, rec, more.get(i)[0]);
                Sfnt.w32(n, rec + 2, (int) (wraps + 7L * more.get(i)[1] - list));
            }
            Sfnt.w32(n, (int) affine, fixed(s));
            Sfnt.w32(n, (int) affine + 12, fixed(s));
            if (clipCount > 0) {
                int at = (int) clipAt;
                n[at] = 1;
                Sfnt.w32(n, at + 1, clipCount);
                for (int i = 0; i < clips; i++) {
                    int rec = (int) cl + 5 + 7 * i, w = at + 5 + 7 * i;
                    Sfnt.w16(n, w, Sfnt.u16(orig, rec));
                    Sfnt.w16(n, w + 2, Sfnt.u16(orig, rec + 2));
                    w24(n, w + 4, cl + Sfnt.u24(orig, rec + 4) + delta - at);
                }
                int w = at + 5 + 7 * clips;
                for (Map.Entry<Integer, Integer> b : boxOf.entrySet()) {
                    Sfnt.w16(n, w, b.getKey());
                    Sfnt.w16(n, w + 2, b.getKey());
                    w24(n, w + 4, b.getValue() + delta - at);
                    w += 7;
                }
                Sfnt.w32(n, 22, at);
            }
            return n;
        }

        /**
         * The table with a BaseGlyphRecord (COLR v0 layers) for each copy whose glyph has one: all the records, the copies' (placeholder ids
         * past every other) at the end, go in a new array after the table; the old one stays behind, unused.
         */
        private byte[] v0Copies(byte[] t) throws Sfnt.FontException {
            int numBase = Sfnt.u16(t, 2);
            if (numBase == 0) return t;
            long base = Sfnt.u32(t, 4);
            Sfnt.need(t, base, 6L * numBase);
            HashMap<Integer, Integer> recOf = new HashMap<Integer, Integer>();
            for (int i = 0; i < numBase; i++) recOf.put(Sfnt.u16(t, (int) base + 6 * i), (int) base + 6 * i);
            List<int[]> more = new ArrayList<int[]>();                       // placeholder id, the glyph's record
            for (int j = 0; j < dups.size(); j++) {
                Integer r = recOf.get(dups.get(j));
                if (r != null) more.add(new int[] { limit + j, r });
            }
            if (more.isEmpty()) return t;
            long at = (t.length + 3) & ~3L, size = at + 6L * (numBase + more.size());
            if (numBase + more.size() > 0xFFFF || size > Integer.MAX_VALUE - 8)
                throw new Sfnt.FontException("The emoji font's colour table (COLR) is too big to merge.");
            byte[] n = Arrays.copyOf(t, (int) size);
            System.arraycopy(t, (int) base, n, (int) at, 6 * numBase);
            int w = (int) at + 6 * numBase;
            for (int[] r : more) {
                Sfnt.w16(n, w, r[0]);
                Sfnt.w16(n, w + 2, Sfnt.u16(t, r[1] + 2));
                Sfnt.w16(n, w + 4, Sfnt.u16(t, r[1] + 4));
                w += 6;
            }
            Sfnt.w16(n, 2, numBase + more.size());
            Sfnt.w32(n, 4, (int) at);
            return n;
        }

        /** Appends an inverse-scale wrapper and a fresh copy of each PaintColrGlyph, and points every offset that named one at its wrapper. */
        private byte[] inverses() throws Sfnt.FontException {
            int k = colrGlyphs.size();
            long size = t.length + 10L * k + 24;
            if (size > 0xFFFFFF) throw new Sfnt.FontException("The emoji font's colour table (COLR) is too big to merge.");
            byte[] n = java.util.Arrays.copyOf(t, (int) size);
            int affine = t.length + 10 * k;
            java.util.HashMap<Integer, Integer> wrapperOf = new java.util.HashMap<Integer, Integer>();
            for (int i = 0; i < k; i++) {
                int node = colrGlyphs.get(i), w = t.length + 10 * i;
                wrapperOf.put(node, w);
                n[w] = 12;
                w24(n, w + 1, 7);
                w24(n, w + 4, affine - w);
                n[w + 7] = 11;
                Sfnt.w16(n, w + 8, Sfnt.u16(t, node + 1));
            }
            Sfnt.w32(n, affine, fixed(1 / s));
            Sfnt.w32(n, affine + 12, fixed(1 / s));
            for (int[] e : edges) {
                Integer w = wrapperOf.get(e[3]);
                if (w == null) continue;
                long v = w - (long) e[1];
                if (v <= 0 || (e[2] == 3 && v > 0xFFFFFF)) throw new Sfnt.FontException("The emoji font's colour table (COLR) is too big to merge.");
                if (e[2] == 3) w24(n, e[0], v); else Sfnt.w32(n, e[0], (int) v);
            }
            return n;
        }

        static int fixed(double v) { return (int) Math.round(v * 65536); }

        static void w24(byte[] b, int at, long v) { b[at] = (byte) (v >> 16); b[at + 1] = (byte) (v >> 8); b[at + 2] = (byte) v; }
    }

    // ---------------------------------------------------------------------------------------------
    // The other tables, and the check of the result
    // ---------------------------------------------------------------------------------------------

    /** OS/2: the first and last character (capped at U+FFFF) and the "beyond the BMP" Unicode range bit (57). */
    private static void os2(Sfnt out, TreeMap<Integer, Integer> map) {
        byte[] t = out.table("OS/2");
        if (t == null || map.isEmpty()) return;
        t = t.clone();
        if (t.length >= 68) {
            Sfnt.w16(t, 64, Math.min(map.firstKey(), 0xFFFF));
            Sfnt.w16(t, 66, Math.min(map.lastKey(), 0xFFFF));
        }
        if (t.length >= 50 && map.lastKey() > 0xFFFF) t[46] |= 0x02;      // bit 57 is bit 25 of ulUnicodeRange2 (big-endian at 46)
        out.put("OS/2", t);
    }

    /** post version 3 (no glyph names: the emoji glyphs' names would not match the text font's list). */
    private static void post(Sfnt out) {
        byte[] t = out.table("post");
        byte[] p = new byte[32];
        if (t != null) System.arraycopy(t, 0, p, 0, Math.min(32, t.length));
        Sfnt.w32(p, 0, 0x00030000);
        out.put("post", p);
    }

    /** Writes the merged font, reads it back and checks it: every glyph decodes, the map is the one built, COLR and GSUB read. */
    private static void verify(Sfnt out, int glyphs, TreeMap<Integer, Integer> map, FontProgress p) throws Sfnt.FontException {
        Sfnt f = Sfnt.parse(out.toBytes());
        if (f.numGlyphs() != glyphs) throw notChecked();
        byte[][] gl = Glyf.readAll(f);
        for (int i = 0; i < gl.length; i++) {
            if ((i & 1023) == 0) { FontProgress.Check.cancel(p); p.step(STAGE, i, gl.length); }
            Glyf.decode(gl[i]);
        }
        if (!Cmap.read(f, null).equals(map)) throw notChecked();
        new Colr(f.table("COLR"), glyphs, p).walk();
        if (f.has("GSUB")) OtLayout.parse(f.table("GSUB"), true);
    }

    private static Sfnt.FontException notChecked() { return new Sfnt.FontException("The merged font did not read back as it was written."); }

    // ---------------------------------------------------------------------------------------------
    // Progress
    // ---------------------------------------------------------------------------------------------

    /** Reports {@code done} of {@link #TOTAL}; between the parts of the merge, Cancel is honoured here too. */
    private static void step(FontProgress p, long done) throws Sfnt.FontException {
        if (p == null) return;
        if (done < TOTAL) FontProgress.Check.cancel(p);
        p.step(STAGE, (int) done, TOTAL);
    }

    /** A progress that reports the steps of a part as the part {@code from..to} of this stage, and cancels with {@code p}. */
    private static FontProgress sub(final FontProgress p, final int from, final int to) {
        return new FontProgress() {
            @Override public void step(String stage, int done, int total) {
                if (p == null || total <= 0) return;
                long d = Math.max(0, Math.min(done, total));
                p.step(STAGE, (int) (from + (to - from) * d / total), TOTAL);
            }

            @Override public boolean cancelled() { return p != null && p.cancelled(); }
        };
    }
}
