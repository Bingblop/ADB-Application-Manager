package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The character map of a font (the cmap table): which glyph shows each Unicode character, read from a font and written for a new one.
 *
 * <p>The Custom Fonts tab reads the map of every font the user picks: {@link FontInspect} counts its characters, {@code EmojiMerge} combines
 * a text font's map ({@link #read}) and variation sequences (the emoji or text form of a character asked for with U+FE0F / U+FE0E,
 * {@link #readUvs}) with an emoji font's, and {@link #build} writes the merged table. {@link #read} takes the subtable fontTools' getBestCmap
 * and HarfBuzz take (full Unicode, then BMP Unicode, then a Windows symbol map, then an old Mac map) and goes on to the next one when that one
 * is damaged. {@link #build} writes format 4 (BMP) and, when needed, format 12 (every plane) and format 14 (variation sequences), the
 * subtables every Android version reads. The input is untrusted: every read is bounds-checked, the work is bounded whatever the counts say,
 * and a bad table ends in a {@link Sfnt.FontException}.
 */
public final class Cmap {

    private Cmap() {}

    /** The last Unicode code point. */
    public static final int MAX_CP = 0x10FFFF;
    /** More mapped characters than this is refused (all of Unicode's assigned characters are about 160,000). */
    public static final int MAX_MAPPINGS = 300000;
    /** Code points visited while reading, at most: overlapping ranges of a hostile file stop here instead of running for minutes. */
    static final int MAX_WORK = 3 * 0x110000;
    /** Progress stage name used by the long steps here. */
    public static final String STAGE = "cmap";

    /** The subtables {@link #read} looks for, best first: fontTools' getBestCmap order, then symbol and Mac Roman as HarfBuzz falls back. */
    static final int[][] PREFERENCE = { { 3, 10 }, { 0, 6 }, { 0, 4 }, { 3, 1 }, { 0, 3 }, { 0, 2 }, { 0, 1 }, { 0, 0 }, { 3, 0 }, { 1, 0 } };

    /** Mac Roman 0x80..0xFF as Unicode (Apple's ROMAN.TXT: 0xDB is the euro sign, 0xF0 the Apple logo); 0x00..0x7F are ASCII. */
    private static final int[] MAC_ROMAN = {
            0x00C4, 0x00C5, 0x00C7, 0x00C9, 0x00D1, 0x00D6, 0x00DC, 0x00E1, 0x00E0, 0x00E2, 0x00E4, 0x00E3, 0x00E5, 0x00E7, 0x00E9, 0x00E8,
            0x00EA, 0x00EB, 0x00ED, 0x00EC, 0x00EE, 0x00EF, 0x00F1, 0x00F3, 0x00F2, 0x00F4, 0x00F6, 0x00F5, 0x00FA, 0x00F9, 0x00FB, 0x00FC,
            0x2020, 0x00B0, 0x00A2, 0x00A3, 0x00A7, 0x2022, 0x00B6, 0x00DF, 0x00AE, 0x00A9, 0x2122, 0x00B4, 0x00A8, 0x2260, 0x00C6, 0x00D8,
            0x221E, 0x00B1, 0x2264, 0x2265, 0x00A5, 0x00B5, 0x2202, 0x2211, 0x220F, 0x03C0, 0x222B, 0x00AA, 0x00BA, 0x03A9, 0x00E6, 0x00F8,
            0x00BF, 0x00A1, 0x00AC, 0x221A, 0x0192, 0x2248, 0x2206, 0x00AB, 0x00BB, 0x2026, 0x00A0, 0x00C0, 0x00C3, 0x00D5, 0x0152, 0x0153,
            0x2013, 0x2014, 0x201C, 0x201D, 0x2018, 0x2019, 0x00F7, 0x25CA, 0x00FF, 0x0178, 0x2044, 0x20AC, 0x2039, 0x203A, 0xFB01, 0xFB02,
            0x2021, 0x00B7, 0x201A, 0x201E, 0x2030, 0x00C2, 0x00CA, 0x00C1, 0x00CB, 0x00C8, 0x00CD, 0x00CE, 0x00CF, 0x00CC, 0x00D3, 0x00D4,
            0xF8FF, 0x00D2, 0x00DA, 0x00DB, 0x00D9, 0x0131, 0x02C6, 0x02DC, 0x00AF, 0x02D8, 0x02D9, 0x02DA, 0x00B8, 0x02DD, 0x02DB, 0x02C7,
    };

    private static final String DAMAGED = "This font's character map (cmap) is damaged.";

    // ---------------------------------------------------------------------------------------------
    // Reading the map
    // ---------------------------------------------------------------------------------------------

    /** What {@link #readBest} found: the map and the subtable it came from. */
    public static final class Best {
        /** Code point -> glyph id; glyph 0 (the missing glyph) and ids the font does not have are left out. */
        public final TreeMap<Integer, Integer> map;
        /** Platform, encoding and format of the subtable; all -1 when the font has no subtable this class reads (the map is then empty). */
        public final int platform, encoding, format;

        Best(TreeMap<Integer, Integer> map, int platform, int encoding, int format) {
            this.map = map;
            this.platform = platform;
            this.encoding = encoding;
            this.format = format;
        }

        /** A Windows symbol font (3, 0): its characters sit where the font put them, usually U+F020..F0FF, not at their Unicode places. */
        public boolean symbol() { return platform == 3 && encoding == 0; }
    }

    /** The font's best Unicode map: code point -> glyph id. Empty when the font has no Unicode, symbol or Mac Roman subtable. */
    public static TreeMap<Integer, Integer> read(Sfnt f) throws Sfnt.FontException { return readBest(f, null).map; }

    public static TreeMap<Integer, Integer> read(Sfnt f, FontProgress p) throws Sfnt.FontException { return readBest(f, p).map; }

    /**
     * The font's best map and where it came from. A damaged subtable is passed over for the next one in the order; when every subtable the
     * font has in that order is damaged, the font is refused. Mac Roman codes become Unicode; a symbol map is kept as the font has it.
     */
    public static Best readBest(Sfnt f, FontProgress p) throws Sfnt.FontException {
        byte[] t = f.table("cmap");
        if (t == null) throw new Sfnt.FontException("missing_table", "This font has no character map (cmap table).");
        int num = records(t);
        int glyphs = f.numGlyphs();
        Work w = new Work(p, MAX_WORK);
        boolean damaged = false;
        for (int[] pref : PREFERENCE) {
            for (int i = 0; i < num; i++) {
                int rec = 4 + 8 * i;
                if (Sfnt.u16(t, rec) != pref[0] || Sfnt.u16(t, rec + 2) != pref[1]) continue;
                TreeMap<Integer, Integer> m = new TreeMap<Integer, Integer>();
                try {
                    int format = subtable(t, Sfnt.u32(t, rec + 4), pref[0] == 1, glyphs, m, w);
                    if (format >= 0) return new Best(m, pref[0], pref[1], format);
                } catch (Sfnt.FontException e) {
                    if (w.stop) throw e;                                 // cancelled or over a limit: no point in trying another subtable
                    damaged = true;
                }
            }
        }
        if (damaged) throw new Sfnt.FontException(DAMAGED);
        return new Best(new TreeMap<Integer, Integer>(), -1, -1, -1);
    }

    /** The number of encoding records, checked to be inside the table. */
    private static int records(byte[] t) throws Sfnt.FontException {
        if (t.length < 4) throw new Sfnt.FontException("This font's character map (cmap) is cut off.");
        int num = Sfnt.u16(t, 2);
        if (4 + 8L * num > t.length) throw new Sfnt.FontException("This font's character map (cmap) is cut off.");
        return num;
    }

    /** Counts the work, checks Cancel, reports progress, and adds mappings. */
    private static final class Work {
        final FontProgress p;
        final long limit;
        long done;
        int at, of;                    // the entry (segment, group, run) being worked on, of how many: what the progress shows
        boolean stop;

        Work(FontProgress p, long limit) {
            this.p = p;
            this.limit = limit;
        }

        void where(int at, int of) {
            this.at = at;
            this.of = of;
        }

        void tick() throws Sfnt.FontException {
            if (++done > limit) {
                stop = true;
                throw new Sfnt.FontException("This font's character map (cmap) is damaged: it lists the same characters over and over.");
            }
            if ((done & 1023) == 0 && p != null) {
                try {
                    FontProgress.Check.cancel(p);
                } catch (Sfnt.FontException e) {
                    stop = true;
                    throw e;
                }
                p.step(STAGE, at, of);
            }
        }

        void add(TreeMap<Integer, Integer> m, boolean mac, int cp, int gid, int glyphs) throws Sfnt.FontException {
            tick();
            if (gid == 0 || gid >= glyphs) return;
            if (mac) {
                if (cp > 0xFF) return;
                if (cp >= 0x80) cp = MAC_ROMAN[cp - 0x80];
            }
            m.put(cp, gid);                                          // a later mapping of the same character wins, as in fontTools
            if (m.size() > MAX_MAPPINGS) {
                stop = true;
                throw new Sfnt.FontException("This font maps more characters (over " + MAX_MAPPINGS + ") than the app can work with.");
            }
        }
    }

    /** Reads one subtable into {@code m}; returns its format, or -1 for a format this class does not read or a zero length (fontTools skips those). */
    private static int subtable(byte[] t, long off, boolean mac, int glyphs, TreeMap<Integer, Integer> m, Work w) throws Sfnt.FontException {
        if (off > t.length - 4) throw new Sfnt.FontException(DAMAGED);
        int o = (int) off;
        int format = Sfnt.u16(t, o);
        switch (format) {
            case 0: {
                if (Sfnt.u16(t, o + 2) == 0) return -1;
                Sfnt.need(t, o + 6, 256);
                for (int c = 0; c < 256; c++) w.add(m, mac, c, t[o + 6 + c] & 0xff, glyphs);
                return 0;
            }
            case 4:
                return format4(t, o, mac, glyphs, m, w);
            case 6: {
                if (Sfnt.u16(t, o + 2) == 0) return -1;
                int first = Sfnt.u16(t, o + 6), count = Sfnt.u16(t, o + 8);
                Sfnt.need(t, o + 10, 2L * count);
                for (int i = 0; i < count; i++) w.add(m, mac, first + i, Sfnt.u16(t, o + 10 + 2 * i), glyphs);
                return 6;
            }
            case 10: {
                if (Sfnt.u32(t, o + 4) == 0) return -1;
                long first = Sfnt.u32(t, o + 12), count = Sfnt.u32(t, o + 16);
                Sfnt.need(t, o + 20L, 2 * count);
                for (long i = 0; i < count && first + i <= MAX_CP; i++) w.add(m, mac, (int) (first + i), Sfnt.u16(t, o + 20 + 2 * (int) i), glyphs);
                return 10;
            }
            case 12:
            case 13: {
                if (Sfnt.u32(t, o + 4) == 0) return -1;
                long groups = Sfnt.u32(t, o + 12);
                Sfnt.need(t, o + 16L, 12 * groups);
                for (long i = 0; i < groups; i++) {
                    w.where((int) i, (int) groups);
                    w.tick();
                    int g = o + 16 + 12 * (int) i;
                    long first = Sfnt.u32(t, g), last = Math.min(Sfnt.u32(t, g + 4), MAX_CP), gid = Sfnt.u32(t, g + 8);
                    if (gid == 0 && format == 13 || gid >= glyphs) continue;
                    if (format == 12) {
                        // Only the code points whose glyph (gid + distance from first) is a real glyph: a hostile group cannot make us walk 1.1M
                        long lo = Math.max(first, first + 1 - gid), hi = Math.min(last, first + (glyphs - 1 - gid));
                        for (long c = lo; c <= hi; c++) w.add(m, mac, (int) c, (int) (gid + c - first), glyphs);
                    } else {
                        for (long c = first; c <= last; c++) w.add(m, mac, (int) c, (int) gid, glyphs);
                    }
                }
                return format;
            }
            default:
                return -1;
        }
    }

    private static int format4(byte[] t, int o, boolean mac, int glyphs, TreeMap<Integer, Integer> m, Work w) throws Sfnt.FontException {
        int length = Sfnt.u16(t, o + 2);
        if (length == 0) return -1;
        int seg = Sfnt.u16(t, o + 6) / 2;
        Sfnt.need(t, o, 16 + 8L * seg);
        long end = Math.min(t.length, (long) o + length);
        if (end < o + 16 + 8L * seg) end = t.length;                     // a length too small (or wrapped past 64K): the ids run to the table's end
        int endAt = o + 14, startAt = endAt + 2 * seg + 2, deltaAt = startAt + 2 * seg, rangeAt = deltaAt + 2 * seg, idsAt = rangeAt + 2 * seg;
        long ids = (end - idsAt) / 2;
        for (int i = 0; i < seg; i++) {
            w.where(i, seg);
            w.tick();
            int last = Sfnt.u16(t, endAt + 2 * i), first = Sfnt.u16(t, startAt + 2 * i);
            int delta = Sfnt.u16(t, deltaAt + 2 * i), range = Sfnt.u16(t, rangeAt + 2 * i);
            for (int c = first; c <= last && c < 0xFFFF; c++) {         // U+FFFF belongs to the closing segment
                int g;
                if (range == 0) {
                    g = (c + delta) & 0xFFFF;
                } else {
                    long k = range / 2 + (c - first) + i - seg;          // index into glyphIdArray; outside it means unmapped, as in HarfBuzz
                    g = k < 0 || k >= ids ? 0 : Sfnt.u16(t, idsAt + 2 * (int) k);
                    if (g != 0) g = (g + delta) & 0xFFFF;
                }
                w.add(m, mac, c, g, glyphs);
            }
        }
        return 4;
    }

    // ---------------------------------------------------------------------------------------------
    // Variation sequences (format 14)
    // ---------------------------------------------------------------------------------------------

    /**
     * Unicode variation sequences: for a selector (U+FE0F emoji form, U+FE0E text form, or an ideographic one) and a base character, either
     * "the default glyph" (the one the main map gives the character) or a glyph of its own. A pair has at most one of the two; the later call
     * replaces the earlier.
     */
    public static final class Uvs {
        // selector -> first -> last (inclusive), merged so that ranges neither overlap nor touch
        private final TreeMap<Integer, TreeMap<Integer, Integer>> defaults = new TreeMap<Integer, TreeMap<Integer, Integer>>();
        // selector -> code point -> glyph
        private final TreeMap<Integer, TreeMap<Integer, Integer>> glyphs = new TreeMap<Integer, TreeMap<Integer, Integer>>();

        public boolean isEmpty() { return defaults.isEmpty() && glyphs.isEmpty(); }

        /** Every selector that has a default range or a glyph of its own, in order. */
        public List<Integer> selectors() {
            TreeSet<Integer> s = new TreeSet<Integer>(defaults.keySet());
            s.addAll(glyphs.keySet());
            return new ArrayList<Integer>(s);
        }

        public void addDefault(int selector, int cp) { addDefaultRange(selector, cp, cp); }

        /** Characters first..last take their default glyph with this selector. */
        public void addDefaultRange(int selector, int first, int last) {
            check(selector);
            check(first);
            check(last);
            if (first > last) return;
            TreeMap<Integer, Integer> nd = glyphs.get(selector);
            if (nd != null) {
                nd.subMap(first, true, last, true).clear();
                if (nd.isEmpty()) glyphs.remove(selector);
            }
            TreeMap<Integer, Integer> r = defaults.get(selector);
            if (r == null) defaults.put(selector, r = new TreeMap<Integer, Integer>());
            Map.Entry<Integer, Integer> lo = r.floorEntry(first);
            if (lo != null && lo.getValue() >= first - 1) {
                first = lo.getKey();
                last = Math.max(last, lo.getValue());
                r.remove(lo.getKey());
            }
            for (Map.Entry<Integer, Integer> e = r.ceilingEntry(first); e != null && e.getKey() <= last + 1L; e = r.ceilingEntry(first)) {
                last = Math.max(last, e.getValue());
                r.remove(e.getKey());
            }
            r.put(first, last);
        }

        /** The sequence (cp, selector) shows this glyph. */
        public void put(int selector, int cp, int glyph) {
            check(selector);
            check(cp);
            if (glyph < 0 || glyph > 0xFFFF) throw new IllegalArgumentException("glyph " + glyph);
            removeDefault(selector, cp);
            TreeMap<Integer, Integer> nd = glyphs.get(selector);
            if (nd == null) glyphs.put(selector, nd = new TreeMap<Integer, Integer>());
            nd.put(cp, glyph);
        }

        /** Forgets the sequence, whichever kind it is. */
        public void remove(int selector, int cp) {
            removeDefault(selector, cp);
            TreeMap<Integer, Integer> nd = glyphs.get(selector);
            if (nd != null && nd.remove(cp) != null && nd.isEmpty()) glyphs.remove(selector);
        }

        private void removeDefault(int selector, int cp) {
            TreeMap<Integer, Integer> r = defaults.get(selector);
            if (r == null) return;
            Map.Entry<Integer, Integer> e = r.floorEntry(cp);
            if (e == null || e.getValue() < cp) return;
            r.remove(e.getKey());
            if (e.getKey() < cp) r.put(e.getKey(), cp - 1);
            if (e.getValue() > cp) r.put(cp + 1, e.getValue());
            if (r.isEmpty()) defaults.remove(selector);
        }

        /** True when (cp, selector) takes cp's default glyph. */
        public boolean isDefault(int selector, int cp) {
            TreeMap<Integer, Integer> r = defaults.get(selector);
            if (r == null) return false;
            Map.Entry<Integer, Integer> e = r.floorEntry(cp);
            return e != null && e.getValue() >= cp;
        }

        /** The glyph of its own that (cp, selector) shows, or -1. */
        public int glyph(int selector, int cp) {
            TreeMap<Integer, Integer> nd = glyphs.get(selector);
            Integer g = nd == null ? null : nd.get(cp);
            return g == null ? -1 : g;
        }

        public boolean has(int selector, int cp) { return isDefault(selector, cp) || glyph(selector, cp) >= 0; }

        /** The sequences of this selector that have a glyph of their own: code point -> glyph, read-only. */
        public SortedMap<Integer, Integer> glyphs(int selector) {
            TreeMap<Integer, Integer> nd = glyphs.get(selector);
            return nd == null ? Collections.<Integer, Integer>emptySortedMap() : Collections.unmodifiableSortedMap(nd);
        }

        /** The default ranges of this selector, {first, last} in order. */
        public List<int[]> defaultRanges(int selector) {
            List<int[]> out = new ArrayList<int[]>();
            TreeMap<Integer, Integer> r = defaults.get(selector);
            if (r != null) for (Map.Entry<Integer, Integer> e : r.entrySet()) out.add(new int[] { e.getKey(), e.getValue() });
            return out;
        }

        /** How many (cp, selector) pairs there are, default ones counted one by one. */
        public long size() {
            long n = 0;
            for (TreeMap<Integer, Integer> r : defaults.values()) for (Map.Entry<Integer, Integer> e : r.entrySet()) n += e.getValue() - e.getKey() + 1;
            for (TreeMap<Integer, Integer> nd : glyphs.values()) n += nd.size();
            return n;
        }

        public Uvs copy() {
            Uvs u = new Uvs();
            for (Map.Entry<Integer, TreeMap<Integer, Integer>> e : defaults.entrySet()) u.defaults.put(e.getKey(), new TreeMap<Integer, Integer>(e.getValue()));
            for (Map.Entry<Integer, TreeMap<Integer, Integer>> e : glyphs.entrySet()) u.glyphs.put(e.getKey(), new TreeMap<Integer, Integer>(e.getValue()));
            return u;
        }

        @Override
        public boolean equals(Object o) { return o instanceof Uvs && ((Uvs) o).defaults.equals(defaults) && ((Uvs) o).glyphs.equals(glyphs); }

        @Override
        public int hashCode() { return defaults.hashCode() * 31 + glyphs.hashCode(); }

        @Override
        public String toString() { return "Uvs[default " + defaults + ", glyphs " + glyphs + "]"; }

        private static void check(int cp) { if (cp < 0 || cp > MAX_CP) throw new IllegalArgumentException("code point " + cp); }
    }

    /**
     * The font's variation sequences: the first (0, 5) format 14 subtable, else any format 14 one; empty when there is none. Glyph 0 and ids
     * the font does not have are left out; a pair that is listed both ways keeps "default", as HarfBuzz reads it.
     */
    public static Uvs readUvs(Sfnt f) throws Sfnt.FontException {
        Uvs u = new Uvs();
        byte[] t = f.table("cmap");
        if (t == null) return u;
        int num = records(t);
        long off = -1;
        for (int pass = 0; pass < 2 && off < 0; pass++) {
            for (int i = 0; i < num && off < 0; i++) {
                int rec = 4 + 8 * i;
                long o = Sfnt.u32(t, rec + 4);
                if (pass == 0 && (Sfnt.u16(t, rec) != 0 || Sfnt.u16(t, rec + 2) != 5)) continue;
                if (o <= t.length - 2 && Sfnt.u16(t, (int) o) == 14) off = o;
            }
        }
        if (off < 0) return u;
        int o = (int) off, glyphs = f.numGlyphs();
        String bad = "This font's variation sequences (cmap format 14) are damaged.";
        long count = Sfnt.u32(t, o + 6);
        if (o + 10 + 11 * count > t.length) throw new Sfnt.FontException(bad);
        long work = 0;
        for (int i = 0; i < count; i++) {
            int r = o + 10 + 11 * i;
            int sel = Sfnt.u24(t, r);
            long def = Sfnt.u32(t, r + 3), nd = Sfnt.u32(t, r + 7);
            if (sel > MAX_CP) continue;
            if (nd != 0) {
                if (o + nd > t.length - 4) throw new Sfnt.FontException(bad);
                int p = (int) (o + nd);
                long n = Sfnt.u32(t, p);
                if (p + 4 + 5 * n > t.length || (work += n) > MAX_WORK) throw new Sfnt.FontException(bad);
                for (int k = 0; k < n; k++) {
                    int cp = Sfnt.u24(t, p + 4 + 5 * k), g = Sfnt.u16(t, p + 7 + 5 * k);
                    if (cp <= MAX_CP && g != 0 && g < glyphs) u.put(sel, cp, g);
                }
            }
            if (def != 0) {                                                // after the glyphs of its own: "default" wins a pair listed both ways
                if (o + def > t.length - 4) throw new Sfnt.FontException(bad);
                int p = (int) (o + def);
                long n = Sfnt.u32(t, p);
                if (p + 4 + 4 * n > t.length || (work += n) > MAX_WORK) throw new Sfnt.FontException(bad);
                for (int k = 0; k < n; k++) {
                    int first = Sfnt.u24(t, p + 4 + 4 * k), extra = t[p + 7 + 4 * k] & 0xff;
                    if (first <= MAX_CP) u.addDefaultRange(sel, first, Math.min(first + extra, MAX_CP));
                }
            }
        }
        return u;
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    /** A cmap table for this map and these variation sequences (uvs may be null); see {@link #build(Map, Uvs, List, FontProgress)}. */
    public static byte[] build(Map<Integer, Integer> map, Uvs uvs) throws Sfnt.FontException { return build(map, uvs, null, null); }

    /**
     * A cmap table: (0, 3) and (3, 1) format 4 with the BMP, (0, 4) and (3, 10) format 12 with everything when anything is above the BMP or
     * format 4 cannot hold it all, and (0, 5) format 14 when there are variation sequences. Format 4 uses a delta for each run of characters
     * whose glyphs follow them and a glyph array elsewhere, split where that is smallest. When format 4 would pass its 64 KB limit it holds the
     * BMP characters that fit, in code point order, and a sentence saying so goes to {@code warnings} (may be null). Mappings to glyph 0 are
     * left out; U+FFFF is only in format 12.
     */
    public static byte[] build(Map<Integer, Integer> map, Uvs uvs, List<String> warnings, FontProgress p) throws Sfnt.FontException {
        TreeMap<Integer, Integer> m = new TreeMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            Integer cp = e.getKey(), g = e.getValue();
            if (cp == null || cp < 0 || cp > MAX_CP) throw new Sfnt.FontException("A character map cannot hold the code point " + cp + ".");
            if (g == null || g < 0 || g > 0xFFFF) throw new Sfnt.FontException("A character map cannot point to glyph " + g + " (glyph numbers end at 65535).");
            if (g != 0) m.put(cp, g);
        }
        int n = m.size(), bmp = 0;
        int[] cps = new int[n], gids = new int[n];
        int k = 0;
        for (Map.Entry<Integer, Integer> e : m.entrySet()) {
            cps[k] = e.getKey();
            gids[k] = e.getValue();
            if (cps[k] < 0xFFFF) bmp = k + 1;
            k++;
        }
        Work w = new Work(p, Long.MAX_VALUE);
        int fit = bmp;
        if (format4Size(cps, gids, bmp, w) > 0xFFFF) {
            int lo = 0, hi = bmp;                                          // the size grows with the prefix: the longest prefix that fits
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (format4Size(cps, gids, mid, w) <= 0xFFFF) lo = mid; else hi = mid - 1;
            }
            fit = lo;
            if (warnings != null)
                warnings.add("The font has more characters than the older part of its character map (format 4) can hold: it lists the first "
                        + fit + " of the " + bmp + " characters below U+FFFF. Android reads the newer part, which has them all; a few older apps may miss the rest.");
        }
        byte[] f4 = format4(cps, gids, fit, w);
        byte[] f12 = fit < n ? format12(cps, gids, n, w) : null;
        byte[] f14 = uvs == null || uvs.isEmpty() ? null : format14(uvs);
        List<int[]> recs = new ArrayList<int[]>();                         // platform, encoding, which subtable: 0 format 4, 1 format 12, 2 format 14
        recs.add(new int[] { 0, 3, 0 });
        if (f12 != null) recs.add(new int[] { 0, 4, 1 });
        if (f14 != null) recs.add(new int[] { 0, 5, 2 });
        recs.add(new int[] { 3, 1, 0 });
        if (f12 != null) recs.add(new int[] { 3, 10, 1 });
        byte[][] subs = { f4, f12, f14 };
        long[] at = new long[3];
        long pos = 4 + 8L * recs.size();
        for (int i = 0; i < 3; i++) if (subs[i] != null) { at[i] = pos; pos += subs[i].length; }
        if (pos > Integer.MAX_VALUE - 8) throw new Sfnt.FontException("too_big", "The character map would be too big to write.");
        Sfnt.Writer out = new Sfnt.Writer((int) pos);
        out.u16(0).u16(recs.size());
        for (int[] r : recs) out.u16(r[0]).u16(r[1]).u32(at[r[2]]);
        for (byte[] s : subs) if (s != null) out.bytes(s);
        return out.toByteArray();
    }

    /**
     * The plan of a format 4 subtable for the first {@code n} mappings: runs of consecutive characters whose glyph - character is constant,
     * and for each run whether it starts a glyph-array segment (and which run that segment ends at) or is a delta segment of its own.
     * Dynamic programming over the runs: a delta segment costs 8 bytes, an array segment 8 + 2 per character it spans (gaps included).
     */
    private static final class Plan {
        int runs;
        int[] first, last, entry;          // per run: first and last code point, index of its first mapping
        int[] segEnd;                      // per segment start run: the run the segment ends at; -1 for a delta segment
        long size;
    }

    private static Plan plan(int[] cps, int[] gids, int n, Work w) throws Sfnt.FontException {
        Plan pl = new Plan();
        int[] first = new int[n], last = new int[n], entry = new int[n];
        int r = 0;
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && cps[j + 1] == cps[j] + 1 && gids[j + 1] - cps[j + 1] == gids[i] - cps[i]) j++;
            first[r] = cps[i];
            last[r] = cps[j];
            entry[r] = i;
            r++;
            i = j + 1;
            w.where(i, n);
            w.tick();
        }
        long[] cost = new long[r + 1];
        int[] from = new int[r + 1];                     // -1: the run before is a delta segment; else the run its array segment starts at
        long bestStart = Long.MAX_VALUE;                 // min over j of cost[j] - 2 * first[j]
        int bestJ = -1;
        for (int k = 1; k <= r; k++) {
            if (cost[k - 1] - 2L * first[k - 1] < bestStart) { bestStart = cost[k - 1] - 2L * first[k - 1]; bestJ = k - 1; }
            long delta = cost[k - 1] + 8, array = bestStart + 8 + 2L * (last[k - 1] + 1);
            if (delta <= array) { cost[k] = delta; from[k] = -1; } else { cost[k] = array; from[k] = bestJ; }
            if ((k & 1023) == 0) w.tick();
        }
        int[] segEnd = new int[r];
        for (int k = r; k > 0; ) {
            if (from[k] < 0) { segEnd[k - 1] = -1; k--; } else { segEnd[from[k]] = k - 1; k = from[k]; }
        }
        pl.runs = r;
        pl.first = first;
        pl.last = last;
        pl.entry = entry;
        pl.segEnd = segEnd;
        pl.size = 16 + 8 + cost[r];                      // header and reservedPad, the closing U+FFFF segment, the segments
        return pl;
    }

    static long format4Size(int[] cps, int[] gids, int n, Work w) throws Sfnt.FontException { return plan(cps, gids, n, w).size; }

    /** The size of the smallest format 4 subtable for the first n mappings (code points ascending, below U+FFFF); for tests. */
    static long format4Size(int[] cps, int[] gids, int n) throws Sfnt.FontException { return format4Size(cps, gids, n, new Work(null, Long.MAX_VALUE)); }

    private static byte[] format4(int[] cps, int[] gids, int n, Work w) throws Sfnt.FontException {
        Plan pl = plan(cps, gids, n, w);
        List<int[]> segs = new ArrayList<int[]>();       // first cp, last cp, idDelta, first run (array segments) or -1
        for (int k = 0; k < pl.runs; ) {
            if (pl.segEnd[k] < 0) {
                segs.add(new int[] { pl.first[k], pl.last[k], (gids[pl.entry[k]] - cps[pl.entry[k]]) & 0xFFFF, -1 });
                k++;
            } else {
                segs.add(new int[] { pl.first[k], pl.last[pl.segEnd[k]], 0, k });
                k = pl.segEnd[k] + 1;
            }
        }
        segs.add(new int[] { 0xFFFF, 0xFFFF, 1, -1 });
        int seg = segs.size();
        Sfnt.Writer ids = new Sfnt.Writer();
        int[] rangeOffset = new int[seg];
        for (int i = 0; i < seg; i++) {
            int[] s = segs.get(i);
            if (s[3] < 0) continue;
            rangeOffset[i] = 2 * (seg - i) + ids.size();                  // from this idRangeOffset entry to the segment's first glyph id
            int e = pl.entry[s[3]];
            for (int c = s[0]; c <= s[1]; c++) {
                if (e < n && cps[e] == c) ids.u16(gids[e++]); else ids.u16(0);
            }
            w.tick();
        }
        int length = 16 + 8 * seg + ids.size();
        if (length > 0xFFFF) throw new Sfnt.FontException("The character map's format 4 part would pass 64 KB.");   // plan() and the prefix search keep it below
        int pow = 1, log = 0;
        while (pow * 2 <= seg) { pow *= 2; log++; }
        Sfnt.Writer o = new Sfnt.Writer(length);
        o.u16(4).u16(length).u16(0).u16(2 * seg).u16(2 * pow).u16(log).u16(2 * seg - 2 * pow);
        for (int[] s : segs) o.u16(s[1]);
        o.u16(0);
        for (int[] s : segs) o.u16(s[0]);
        for (int[] s : segs) o.u16(s[2]);
        for (int i = 0; i < seg; i++) o.u16(rangeOffset[i]);
        o.bytes(ids.toByteArray());
        return o.toByteArray();
    }

    private static byte[] format12(int[] cps, int[] gids, int n, Work w) throws Sfnt.FontException {
        Sfnt.Writer groups = new Sfnt.Writer();
        int count = 0;
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && cps[j + 1] == cps[j] + 1 && gids[j + 1] == gids[j] + 1) j++;
            groups.u32(cps[i]).u32(cps[j]).u32(gids[i]);
            count++;
            i = j + 1;
            w.where(i, n);
            w.tick();
        }
        Sfnt.Writer o = new Sfnt.Writer(16 + groups.size());
        o.u16(12).u16(0).u32(16 + groups.size()).u32(0).u32(count);
        o.bytes(groups.toByteArray());
        return o.toByteArray();
    }

    private static byte[] format14(Uvs u) {
        List<Integer> sels = u.selectors();
        Sfnt.Writer body = new Sfnt.Writer();
        long[][] offs = new long[sels.size()][2];
        int head = 10 + 11 * sels.size();
        for (int i = 0; i < sels.size(); i++) {
            int sel = sels.get(i);
            List<int[]> ranges = new ArrayList<int[]>();
            for (int[] r : u.defaultRanges(sel))
                for (int a = r[0]; a <= r[1]; a += 256) ranges.add(new int[] { a, Math.min(255, r[1] - a) });  // additionalCount is one byte
            if (!ranges.isEmpty()) {
                offs[i][0] = head + body.size();
                body.u32(ranges.size());
                for (int[] r : ranges) body.u24(r[0]).u8(r[1]);
            }
            SortedMap<Integer, Integer> nd = u.glyphs(sel);
            if (!nd.isEmpty()) {
                offs[i][1] = head + body.size();
                body.u32(nd.size());
                for (Map.Entry<Integer, Integer> e : nd.entrySet()) body.u24(e.getKey()).u16(e.getValue());
            }
        }
        Sfnt.Writer o = new Sfnt.Writer(head + body.size());
        o.u16(14).u32(head + body.size()).u32(sels.size());
        for (int i = 0; i < sels.size(); i++) o.u24(sels.get(i)).u32(offs[i][0]).u32(offs[i][1]);
        o.bytes(body.toByteArray());
        return o.toByteArray();
    }
}
