package com.bloatware.bingblop;

import java.util.HashMap;
import java.util.Map;

/**
 * PostScript outlines (the CFF table of an .otf font, or the CFF2 table of a variable one) turned into TrueType outlines (glyf / loca).
 *
 * <p>The fonts of a FlipFont package are TrueType files and {@link EmojiMerge} appends glyf glyphs, so {@link FontBuilder} converts a picked
 * .otf font with {@link #convert} first; {@link FontInstancer} calls it for a CFF2 variable font with the instance location, so the blends are
 * resolved here. Each glyph's Type 2 charstring is run (subroutines, flex, hints skipped, an accented letter built with endchar becomes a
 * composite glyph), every cubic curve becomes quadratic ones the way fontTools' cu2qu makes them (the fewest equal pieces that stay within
 * 1/1000 em) and every contour is reversed to the TrueType direction. Advances stay those of hmtx; each left side bearing becomes the new xMin.
 * The input is untrusted: every read is bounds-checked, subroutine nesting and the work per glyph and per font are capped, and every failure
 * ends in a {@link Sfnt.FontException}. Pure Java, no Android classes.
 */
public final class CffToGlyf {

    private CffToGlyf() {}

    /** Subroutines nested deeper than this are refused (the Type 2 limit). */
    static final int MAX_SUBR_DEPTH = 10;
    /** Charstring operators one glyph may run, subroutines included; real glyphs run a few hundred. */
    static final int MAX_OPS_PER_GLYPH = 100000;
    /** Operators the whole font may run: a 65,535-glyph CJK font (Noto Sans CJK) runs 15 million. */
    static final long MAX_OPS_PER_FONT = 100000000L;
    /** Quadratic pieces tried for the whole font (finding n costs 1 + 2 + ... + n): that CJK font needs 4 million. */
    static final long MAX_CURVE_WORK = 50000000L;
    /** Points (on the curve and cubic control points) one glyph's path may hold. */
    static final int MAX_PATH_POINTS = 3 * Glyf.MAX_POINTS;
    /** A point farther out than this (charstring units) is not part of a real outline. */
    static final double MAX_COORD = 4194304;
    /** A cubic is cut into at most this many quadratic pieces (cu2qu's limit). */
    static final int MAX_PIECES = 100;
    /** Font DICTs above this are refused (real CID fonts have a few dozen). */
    static final int MAX_FONT_DICTS = 4096;
    /** Local subroutines read in all Private DICTs; Noto Sans CJK (65,535 glyphs, 18 Font DICTs) has 45,206. */
    static final int MAX_INDEX_ENTRIES = 1 << 20;
    /** Region references of all CFF2 variation data sets (a scalar each is kept); real fonts have a few dozen. */
    static final int MAX_REGION_REFS = 1 << 18;

    /**
     * A copy of the font with TrueType outlines: glyf / loca from the CFF2 table (at {@code normalizedCoords}, F2Dot14 per fvar axis as
     * {@link VarStore#normalize} returns them; null is the default location) or else the CFF table (coordinates are ignored); CFF, CFF2 and
     * VORG removed, hmtx bearings and hhea recomputed, maxp version 1.0, post version 3, sfntVersion 0x00010000. Every other table is copied as
     * it is: for a variable font, fvar / HVAR / MVAR stay for {@link FontInstancer} to resolve. {@code f} is not changed.
     */
    public static Sfnt convert(Sfnt f, int[] normalizedCoords, FontProgress p) throws Sfnt.FontException {
        if (p == null) p = FontProgress.NONE;
        if (f.has("glyf")) throw new Sfnt.FontException("not_cff", "This font already has TrueType outlines.");
        boolean cff2 = f.has("CFF2");                              // as fontTools and FreeType do, CFF2 wins when a font has both
        byte[] table = f.table(cff2 ? "CFF2" : "CFF ");
        if (table == null) throw new Sfnt.FontException("not_cff", "This font has no PostScript (CFF) outlines to convert.");
        byte[] head = f.table("head"), maxp = f.table("maxp"), hhea = f.table("hhea");
        if (head == null || head.length < 54 || maxp == null || maxp.length < 6 || hhea == null || hhea.length < 36 || !f.has("hmtx"))
            throw new Sfnt.FontException("missing_table", "This font is missing one of the tables every font needs (head, maxp, hhea or hmtx).");
        try {
            int n = f.numGlyphs();
            Glyf.Metrics m = Glyf.Metrics.read(f);
            Cff cff = Cff.parse(table, cff2, cff2 ? normalizedCoords : null, f.unitsPerEm());
            Interpreter in = new Interpreter(cff);
            Converter cv = new Converter(f.unitsPerEm() / 1000.0);
            byte[][] glyphs = new byte[n][];
            for (int g = 0; g < n; g++) {
                if ((g & 0xFF) == 0) {
                    p.step("convert", g, n);
                    FontProgress.Check.cancel(p);
                }
                glyphs[g] = cv.glyph(in, g);
                if (in.fontOps > MAX_OPS_PER_FONT || cv.work > MAX_CURVE_WORK)
                    throw new Sfnt.FontException("too_complex", "This font's outlines take too long to convert.");
            }
            p.step("convert", n, n);
            Sfnt out = new Sfnt(Sfnt.TRUETYPE);
            for (String tag : f.tags())
                if (!tag.equals("CFF ") && !tag.equals("CFF2") && !tag.equals("VORG")) out.put(tag, f.table(tag).clone());
            Glyf.write(out, glyphs);                                   // also gives each composite glyph its bounds
            for (int g = 0; g < n; g++) {
                int[] bb = Glyf.headerBounds(glyphs[g]);
                m.lsb[g] = bb == null ? 0 : bb[0];
            }
            m.write(out, glyphs);
            out.put("post", post(f.table("post"), cff.top));
            return out;
        } catch (RuntimeException e) {
            // A check this class missed: still a refused font for the app, never a crash (the fuzz test fails on any such cause).
            Sfnt.FontException fe = new Sfnt.FontException("This font's outlines could not be converted.");
            fe.initCause(e);
            throw fe;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // INDEX and DICT
    // ---------------------------------------------------------------------------------------------

    /** A CFF INDEX: item i is the bytes from {@code start(i)} to {@code end(i)} of the table (absolute positions, checked). */
    static final class Index {
        final int count;
        final int next;                    // the first byte after the INDEX
        private final int[] at;

        Index(int count, int[] at, int next) { this.count = count; this.at = at; this.next = next; }

        int start(int i) { return at[i]; }

        int end(int i) { return at[i + 1]; }
    }

    static Index readIndex(byte[] t, long pos, boolean cff2) throws Sfnt.FontException {
        if (pos < 0 || pos > t.length) throw new Sfnt.FontException("This font's CFF table points outside itself.");
        int at0 = (int) pos;
        long count = cff2 ? Sfnt.u32(t, at0) : Sfnt.u16(t, at0);
        int head = cff2 ? 4 : 2;
        if (count == 0) return new Index(0, new int[] { at0 + head }, at0 + head);
        if (count > 0xFFFFF) throw new Sfnt.FontException("This font's CFF table has an INDEX that is too long.");
        int offSize = Sfnt.u8(t, at0 + head);
        if (offSize < 1 || offSize > 4) throw new Sfnt.FontException("This font's CFF table has a damaged INDEX.");
        long offs = at0 + head + 1L;
        Sfnt.need(t, offs, (count + 1) * offSize);                 // checked before anything is allocated by count
        long base = offs + (count + 1) * offSize - 1;              // offsets count from the byte before the data
        int[] at = new int[(int) count + 1];
        long prev = 1;
        for (int i = 0; i <= count; i++) {
            long o = 0;
            int q = (int) (offs + (long) i * offSize);
            for (int k = 0; k < offSize; k++) o = (o << 8) | (t[q + k] & 0xff);
            if (i == 0 ? o != 1 : o < prev) throw new Sfnt.FontException("This font's CFF table has a damaged INDEX.");
            if (base + o > t.length) throw new Sfnt.FontException("This font's CFF table is cut off.");
            at[i] = (int) (base + o);
            prev = o;
        }
        return new Index((int) count, at, at[(int) count]);
    }

    // DICT operators the conversion reads (two-byte operators are 1200 + the second byte)
    static final int CHARSET = 15, CHARSTRINGS = 17, PRIVATE = 18, SUBRS = 19, VSINDEX = 22, VSTORE = 24;
    static final int IS_FIXED_PITCH = 1201, ITALIC_ANGLE = 1202, UNDERLINE_POSITION = 1203, UNDERLINE_THICKNESS = 1204, CHARSTRING_TYPE = 1206,
            FONT_MATRIX = 1207, ROS = 1230, FD_ARRAY = 1236, FD_SELECT = 1237;

    /**
     * Reads a DICT: operator -> its operands. In a CFF2 Private DICT, {@code regions} gives the region count of each vsindex so a blend keeps
     * its default values (the conversion needs none of the blended hint values). A reserved operator byte is skipped with its operands.
     */
    static Map<Integer, double[]> readDict(byte[] t, int start, int end, boolean cff2, double[][] regions) throws Sfnt.FontException {
        Map<Integer, double[]> d = new HashMap<Integer, double[]>();
        double[] stack = new double[cff2 ? 513 : 48];
        int sp = 0, vsindex = 0;
        int p = start;
        while (p < end) {
            int b0 = t[p] & 0xff;
            double v;
            if (b0 >= 32 && b0 <= 246) { v = b0 - 139; p += 1; }
            else if (b0 >= 247 && b0 <= 254) {
                if (p + 2 > end) throw damagedDict();
                int b1 = t[p + 1] & 0xff;
                v = b0 <= 250 ? (b0 - 247) * 256 + b1 + 108 : -(b0 - 251) * 256 - b1 - 108;
                p += 2;
            } else if (b0 == 28) {
                if (p + 3 > end) throw damagedDict();
                v = Sfnt.s16(t, p + 1);
                p += 3;
            } else if (b0 == 29) {
                if (p + 5 > end) throw damagedDict();
                v = Sfnt.s32(t, p + 1);
                p += 5;
            } else if (b0 == 30) {
                StringBuilder sb = new StringBuilder();
                p = realNumber(t, p + 1, end, sb);
                try {
                    v = Double.parseDouble(sb.toString());
                } catch (NumberFormatException e) {
                    throw damagedDict();
                }
            } else if (b0 <= 21 || (cff2 && (b0 == 23 || b0 == 24 || b0 == 22))) {
                int op = b0;
                p++;
                if (b0 == 12) {
                    if (p >= end) throw damagedDict();
                    op = 1200 + (t[p++] & 0xff);
                }
                if (op == 23) {                                    // blend (CFF2 Private DICT): keep the n defaults, drop the n * k deltas
                    if (sp < 1) throw damagedDict();
                    double nv = stack[--sp];
                    int k = regions != null && vsindex >= 0 && vsindex < regions.length ? regions[vsindex].length : -1;
                    if (k < 0 || !(nv >= 0 && nv <= sp)) throw damagedDict();
                    long drop = (long) nv * k;
                    if ((long) nv + drop > sp) throw damagedDict();
                    sp -= (int) drop;
                    continue;
                }
                if (op == VSINDEX) {
                    if (sp < 1 || !(stack[sp - 1] >= 0 && stack[sp - 1] <= 0xFFFF)) throw damagedDict();
                    vsindex = (int) stack[sp - 1];
                }
                double[] args = new double[sp];
                System.arraycopy(stack, 0, args, 0, sp);
                d.put(op, args);
                sp = 0;
                continue;
            } else {
                // A reserved operator (22-27 in CFF 1, 25-27 in CFF2, 31, 255): ignored with its operands, as the CFF2 specification
                // says for an operator it does not know. CFF2's maxstack (25), dropped after OpenType 1.8.0, is one; FreeType still reads it
                p++;
                sp = 0;
                continue;
            }
            if (sp >= stack.length) throw damagedDict();
            stack[sp++] = v;
        }
        return d;
    }

    /** A real number (nibbles: digits, '.', 'E', 'E-', '-', end) as text for Double.parseDouble; returns the position after it. */
    private static int realNumber(byte[] t, int p, int end, StringBuilder sb) throws Sfnt.FontException {
        while (true) {
            if (p >= end || sb.length() > 64) throw damagedDict();
            int b = t[p++] & 0xff;
            for (int half = 0; half < 2; half++) {
                int nib = half == 0 ? b >> 4 : b & 0xF;
                if (nib <= 9) sb.append((char) ('0' + nib));
                else if (nib == 0xA) sb.append('.');
                else if (nib == 0xB) sb.append('E');
                else if (nib == 0xC) sb.append("E-");
                else if (nib == 0xE) sb.append('-');
                else if (nib == 0xF) return p;
                else throw damagedDict();
            }
        }
    }

    private static Sfnt.FontException damagedDict() { return new Sfnt.FontException("This font's CFF table has a damaged DICT."); }

    /** A DICT operand that is an offset or a size inside the table. */
    private static int offset(double v, byte[] t) throws Sfnt.FontException {
        if (!(v >= 0 && v <= t.length) || v != Math.floor(v)) throw new Sfnt.FontException("This font's CFF table points outside itself.");
        return (int) v;
    }

    // ---------------------------------------------------------------------------------------------
    // The font: CFF 1 (name-keyed or CID-keyed) or CFF2
    // ---------------------------------------------------------------------------------------------

    /** The parts of a CFF or CFF2 table the conversion needs, checked. */
    static final class Cff {
        final byte[] t;
        final boolean cff2;
        Map<Integer, double[]> top;
        Index charStrings, gsubrs;
        Index[] lsubrs;                // local subroutines per Font DICT (one for a CFF 1 font that is not CID-keyed); null entries: none
        int[] fdOf;                    // the Font DICT of each glyph; null: all use the first
        int[] fdVsindex;               // CFF2: the vsindex each Font DICT's Private DICT starts its charstrings with
        double[][] xform;              // per Font DICT: charstring units to font units (a b c d e f), null when that is the identity
        double[][] regions;            // CFF2: per ItemVariationData, the scalar of each of its regions at the location; null: no store
        boolean cid;
        private int[] glyphOfSid;      // for seac: SID -> glyph through the charset, built the first time it is needed
        private boolean charsetRead;
        private final Map<Integer, Index> subrsAt = new HashMap<Integer, Index>();      // Font DICTs often share one Subrs INDEX
        private long subrEntries;

        private Cff(byte[] t, boolean cff2) { this.t = t; this.cff2 = cff2; }

        int fd(int gid) { return fdOf == null || gid >= fdOf.length ? 0 : fdOf[gid]; }

        static Cff parse(byte[] t, boolean cff2, int[] coords, int upem) throws Sfnt.FontException {
            Cff c = new Cff(t, cff2);
            int major = Sfnt.u8(t, 0), hdr = Sfnt.u8(t, 2);
            if (major != (cff2 ? 2 : 1))
                throw new Sfnt.FontException("This font's " + (cff2 ? "CFF2" : "CFF") + " table has an unknown version (" + major + ").");
            if (cff2) {
                int topLen = Sfnt.u16(t, 3);
                if (hdr < 5) throw new Sfnt.FontException("This font's CFF2 table has a damaged header.");
                Sfnt.need(t, hdr, topLen);
                c.top = readDict(t, hdr, hdr + topLen, true, null);
                c.gsubrs = readIndex(t, hdr + topLen, true);
            } else {
                if (hdr < 4) throw new Sfnt.FontException("This font's CFF table has a damaged header.");
                Index names = readIndex(t, hdr, false);
                Index tops = readIndex(t, names.next, false);
                if (tops.count < 1) throw new Sfnt.FontException("This font's CFF table has no font in it.");
                Index strings = readIndex(t, tops.next, false);
                c.gsubrs = readIndex(t, strings.next, false);
                c.top = readDict(t, tops.start(0), tops.end(0), false, null);
            }
            double[] type = c.top.get(CHARSTRING_TYPE);
            if (type != null && type.length > 0 && type[0] != 2)
                throw new Sfnt.FontException("This font uses Type 1 charstrings inside CFF, which cannot be converted.");
            double[] cs = c.top.get(CHARSTRINGS);
            if (cs == null || cs.length < 1) throw new Sfnt.FontException("This font's CFF table has no glyphs.");
            c.charStrings = readIndex(t, offset(cs[0], t), cff2);
            if (c.charStrings.count == 0) throw new Sfnt.FontException("This font's CFF table has no glyphs.");
            if (cff2) {
                double[] vs = c.top.get(VSTORE);
                if (vs != null && vs.length > 0) c.regions = regionScalars(t, offset(vs[0], t) + 2, coords);     // after the store's uint16 length
            }
            double[] topMatrix = c.top.get(FONT_MATRIX);
            c.cid = !cff2 && c.top.containsKey(ROS);
            if (cff2 || c.cid) {
                double[] fda = c.top.get(FD_ARRAY);
                if (fda == null || fda.length < 1) throw new Sfnt.FontException("This font's CFF table has no Font DICTs.");
                Index fds = readIndex(t, offset(fda[0], t), cff2);
                if (fds.count < 1) throw new Sfnt.FontException("This font's CFF table has no Font DICTs.");
                if (fds.count > MAX_FONT_DICTS) throw new Sfnt.FontException("This font's CFF table has too many Font DICTs.");
                c.lsubrs = new Index[fds.count];
                c.fdVsindex = new int[fds.count];
                c.xform = new double[fds.count][];
                for (int i = 0; i < fds.count; i++) {
                    Map<Integer, double[]> fd = readDict(t, fds.start(i), fds.end(i), cff2, null);
                    c.privateDict(i, fd.get(PRIVATE));
                    c.xform[i] = xform(topMatrix, fd.get(FONT_MATRIX), upem);
                }
                double[] sel = c.top.get(FD_SELECT);
                if (sel != null && sel.length > 0) c.fdOf = fdSelect(t, offset(sel[0], t), c.charStrings.count, fds.count, cff2);
                else if (fds.count > 1) throw new Sfnt.FontException("This font's CFF table has several Font DICTs but no FDSelect.");
            } else {
                c.lsubrs = new Index[1];
                c.fdVsindex = new int[1];
                c.xform = new double[][] { xform(topMatrix, null, upem) };
                c.privateDict(0, c.top.get(PRIVATE));
            }
            return c;
        }

        /** Reads Font DICT {@code i}'s Private DICT (size, offset): its local subroutines and, in CFF2, its vsindex. */
        private void privateDict(int i, double[] pr) throws Sfnt.FontException {
            if (pr == null || pr.length < 2) return;                   // no Private DICT: no local subroutines
            int size = offset(pr[0], t), off = offset(pr[1], t);
            if ((long) off + size > t.length) throw new Sfnt.FontException("This font's CFF table points outside itself.");
            Map<Integer, double[]> d = readDict(t, off, off + size, cff2, regions);
            double[] vs = d.get(VSINDEX);
            if (vs != null && vs.length > 0) fdVsindex[i] = (int) vs[vs.length - 1];
            double[] subrs = d.get(SUBRS);
            if (subrs == null || subrs.length == 0) return;
            long at = (long) off + offset(subrs[0], t);
            if (at > t.length) throw new Sfnt.FontException("This font's CFF table points outside itself.");
            Index idx = subrsAt.get((int) at);
            if (idx == null) {
                idx = readIndex(t, at, cff2);
                subrEntries += idx.count;
                if (subrEntries > MAX_INDEX_ENTRIES) throw new Sfnt.FontException("This font's CFF table has too many subroutines.");
                subrsAt.put((int) at, idx);
            }
            lsubrs[i] = idx;
        }

        /** The glyph a Standard Encoding code names through the charset (seac's base and accent), or -1. */
        int glyphForStandardCode(double code) throws Sfnt.FontException {
            if (!charsetRead) {
                charsetRead = true;
                glyphOfSid = readCharset();
            }
            if (glyphOfSid == null || !(code >= 0 && code <= 255)) return -1;
            int sid = standardSid((int) code);
            return sid == 0 ? -1 : glyphOfSid[sid];
        }

        /** SID -> glyph for a name-keyed CFF 1 font; null for the Expert charsets (seac cannot name a glyph through them). */
        private int[] readCharset() throws Sfnt.FontException {
            int n = charStrings.count;
            int[] map = new int[65536];
            java.util.Arrays.fill(map, -1);
            double[] cs = top.get(CHARSET);
            int off = cs == null || cs.length == 0 ? 0 : offset(cs[0], t);
            if (off == 1 || off == 2) return null;
            if (off == 0) {                                            // ISOAdobe: glyph i has SID i
                for (int g = n - 1; g >= 0; g--) if (g <= 228) map[g] = g;
                return map;
            }
            int fmt = Sfnt.u8(t, off), p = off + 1;
            int g = 1;
            if (fmt == 0) {
                Sfnt.need(t, p, 2L * (n - 1));
                for (; g < n; g++) put(map, Sfnt.u16(t, p + 2 * (g - 1)), g);
            } else if (fmt == 1 || fmt == 2) {
                while (g < n) {
                    int first = Sfnt.u16(t, p);
                    int left = fmt == 1 ? Sfnt.u8(t, p + 2) : Sfnt.u16(t, p + 2);
                    p += fmt == 1 ? 3 : 4;
                    for (int k = 0; k <= left && g < n; k++, g++) if (first + k <= 0xFFFF) put(map, first + k, g);
                }
            } else {
                throw new Sfnt.FontException("This font's CFF charset has an unknown format.");
            }
            return map;
        }

        private static void put(int[] map, int sid, int g) { if (map[sid] < 0) map[sid] = g; }       // the first glyph with a name wins
    }

    /** Glyph -> Font DICT from an FDSelect (formats 0 and 3; 4 in CFF2). Glyphs no range covers use the first Font DICT. */
    static int[] fdSelect(byte[] t, int off, int glyphs, int fds, boolean cff2) throws Sfnt.FontException {
        int fmt = Sfnt.u8(t, off);
        int[] out = new int[glyphs];
        if (fmt == 0) {
            Sfnt.need(t, off + 1, glyphs);
            for (int g = 0; g < glyphs; g++) {
                out[g] = t[off + 1 + g] & 0xff;
                if (out[g] >= fds) throw new Sfnt.FontException("This font's FDSelect names a Font DICT it does not have.");
            }
        } else if (fmt == 3 || (fmt == 4 && cff2)) {
            boolean wide = fmt == 4;
            long n = wide ? Sfnt.u32(t, off + 1) : Sfnt.u16(t, off + 1);
            int rec = wide ? 6 : 3, p = off + (wide ? 5 : 3);
            if (n == 0) throw new Sfnt.FontException("This font's FDSelect is empty.");
            Sfnt.need(t, p, n * rec + (wide ? 4 : 2));
            for (int i = 0; i < n; i++) {
                int q = p + i * rec;
                long a = wide ? Sfnt.u32(t, q) : Sfnt.u16(t, q);
                int fd = wide ? Sfnt.u16(t, q + 4) : Sfnt.u8(t, q + 2);
                long b = wide ? Sfnt.u32(t, q + rec) : Sfnt.u16(t, q + rec);     // the next range's first glyph, or the sentinel
                if ((i == 0 && a != 0) || b < a) throw new Sfnt.FontException("This font's FDSelect is out of order.");
                if (fd >= fds) throw new Sfnt.FontException("This font's FDSelect names a Font DICT it does not have.");
                for (long g = a; g < b && g < glyphs; g++) out[(int) g] = fd;
            }
        } else {
            throw new Sfnt.FontException("This font's FDSelect has an unknown format.");
        }
        return out;
    }

    /**
     * For each ItemVariationData of the CFF2 variation store at {@code vs}, the scalar of each region it lists at the location: a blend of
     * vsindex v adds sum(scalar[v][k] * delta[k]). The store is checked by {@link VarStore}, whose region scalar this uses.
     */
    static double[][] regionScalars(byte[] t, int vs, int[] coords) throws Sfnt.FontException {
        new VarStore(t, vs);                                           // format, data offsets and the region list are inside the table
        int list = (int) (vs + Sfnt.u32(t, vs + 2));
        int dataCount = Sfnt.u16(t, vs + 6);
        int axes = Sfnt.u16(t, list), count = Sfnt.u16(t, list + 2);
        double[] scalar = new double[count];
        for (int r = 0; r < count; r++) {
            double s = 1;
            for (int a = 0; a < axes && s != 0; a++) {
                int q = list + 4 + 6 * (r * axes + a);
                int v = coords != null && a < coords.length ? coords[a] : 0;
                s *= VarStore.axisScalar(Sfnt.s16(t, q), Sfnt.s16(t, q + 2), Sfnt.s16(t, q + 4), v);
            }
            scalar[r] = s;
        }
        double[][] out = new double[dataCount][];
        long total = 0;
        for (int d = 0; d < dataCount; d++) {
            long off = Sfnt.u32(t, vs + 8 + 4 * d);
            if (off == 0) { out[d] = new double[0]; continue; }
            if (vs + off + 6 > t.length) throw new Sfnt.FontException("A variation store of this font is cut off.");
            int p = (int) (vs + off);
            int k = Sfnt.u16(t, p + 4);
            Sfnt.need(t, p + 6, 2L * k);
            total += k;                                                // many data sets may point at one long region list
            if (total > MAX_REGION_REFS) throw new Sfnt.FontException("A variation store of this font is too big.");
            out[d] = new double[k];
            for (int i = 0; i < k; i++) {
                int r = Sfnt.u16(t, p + 6 + 2 * i);
                if (r >= count) throw new Sfnt.FontException("A variation store of this font names a region it does not have.");
                out[d][i] = scalar[r];
            }
        }
        return out;
    }

    /**
     * Charstring units to font units, read from the FontMatrix the way FreeType reads it: charstring units are font units and the Top DICT's
     * matrix counts only for its shape and offset (it is divided by its vertical scale, so the usual 1/1000 or 1/upem is the identity); a CID
     * Font DICT's matrix maps to the em (times units per em), followed by the top one's shape. (Where a CID font has both, FreeType also
     * shrinks the glyphs a thousandfold; they keep their size here.) Null when that is the identity, as it is in nearly every font. A matrix
     * that is not finite or flattens everything is ignored.
     */
    static double[] xform(double[] top, double[] sub, int upem) {
        double[] m;
        if (validMatrix(sub)) {
            m = new double[6];
            for (int i = 0; i < 6; i++) m[i] = sub[i] * upem;
            if (validMatrix(top)) {
                double[] t = shape(top);                               // after the Font DICT's matrix: x' = T (S x + s) + t
                m = new double[] { t[0] * m[0] + t[2] * m[1], t[1] * m[0] + t[3] * m[1], t[0] * m[2] + t[2] * m[3], t[1] * m[2] + t[3] * m[3],
                        t[0] * m[4] + t[2] * m[5] + t[4], t[1] * m[4] + t[3] * m[5] + t[5] };
            }
        } else if (validMatrix(top)) {
            m = shape(top);
        } else {
            return null;
        }
        boolean identity = Math.abs(m[0] - 1) < 1e-9 && Math.abs(m[1]) < 1e-9 && Math.abs(m[2]) < 1e-9 && Math.abs(m[3] - 1) < 1e-9
                && Math.abs(m[4]) < 1e-9 && Math.abs(m[5]) < 1e-9;
        if (identity || !validMatrix(m)) return null;
        return m;
    }

    /** A matrix divided by its vertical scale (yy, or yx when yy is 0), offset included: its shape in font units. */
    private static double[] shape(double[] m) {
        double s = m[3] != 0 ? Math.abs(m[3]) : Math.abs(m[1]);
        double[] out = new double[6];
        for (int i = 0; i < 6; i++) out[i] = m[i] / s;
        return out;
    }

    private static boolean validMatrix(double[] m) {
        if (m == null || m.length < 6) return false;
        for (int i = 0; i < 6; i++) if (!(Math.abs(m[i]) < 1e6)) return false;
        return Math.abs(m[0] * m[3] - m[1] * m[2]) > 1e-12;
    }

    // ---------------------------------------------------------------------------------------------
    // Type 2 charstrings
    // ---------------------------------------------------------------------------------------------

    /** One glyph's outline as its charstring draws it, in charstring units: contours of on-curve points and cubic control points. */
    static final class Path {
        double[] x = new double[64], y = new double[64];
        boolean[] ctrl = new boolean[64];
        int size;
        int[] ends = new int[8];          // for each finished contour, the index after its last point
        int contours;
        int start = -1;                   // the first point of the open contour, -1 when none is open

        void clear() { size = 0; contours = 0; start = -1; }

        boolean add(double px, double py, boolean control) {
            if (size == x.length) {
                if (size >= MAX_PATH_POINTS) return false;
                int cap = Math.min(MAX_PATH_POINTS, size * 2);
                x = java.util.Arrays.copyOf(x, cap);
                y = java.util.Arrays.copyOf(y, cap);
                ctrl = java.util.Arrays.copyOf(ctrl, cap);
            }
            x[size] = px; y[size] = py; ctrl[size] = control;
            size++;
            return true;
        }

        /** Ends the open contour; one that is only its start point (a moveto followed by another) is dropped, as fontTools drops it. */
        void close() {
            if (start < 0) return;
            if (size - start < 2) size = start;
            else {
                if (contours == ends.length) ends = java.util.Arrays.copyOf(ends, contours * 2);
                ends[contours++] = size;
            }
            start = -1;
        }
    }

    /** Runs Type 2 charstrings (CFF 1 and CFF2) into a {@link Path}; one per conversion, reused glyph after glyph. */
    static final class Interpreter {
        private final Cff cff;
        private final byte[] t;
        private final double[] s;                    // the argument stack
        private int sp;
        private final double[] store = new double[32];       // the transient array of put / get
        final Path path = new Path();
        private int glyph, ops, stems, depth;
        private boolean widthDone, ended;
        private double x, y;                         // the current point
        private double[] scalars;                    // CFF2: the region scalars of the current vsindex
        long fontOps;
        // An accented letter (endchar with four numbers): base and accent glyph and the accent's offset; seacBase < 0 when there is none.
        int seacBase = -1, seacAccent;
        double seacDx, seacDy;

        Interpreter(Cff cff) {
            this.cff = cff;
            this.t = cff.t;
            this.s = new double[cff.cff2 ? 513 : 48];
        }

        /** Draws glyph g into {@link #path}, or sets the seac fields for an accented letter that has no contours of its own. */
        void run(int g) throws Sfnt.FontException {
            glyph = g;
            ops = 0;
            path.clear();
            seacBase = -1;
            draw(g, 0, 0);
            if (seacBase >= 0 && path.contours > 0) {
                // Contours and an accented letter in one glyph: TrueType cannot mix them, so both glyphs are drawn in, as fontTools does.
                int base = seacBase, accent = seacAccent;
                double dx = seacDx, dy = seacDy;
                seacBase = -1;
                draw(base, 0, 0);
                if (seacBase < 0) draw(accent, dx, dy);
                if (seacBase >= 0) throw bad("builds an accented letter from another accented letter");
            }
            fontOps += ops;
        }

        private void draw(int g, double ox, double oy) throws Sfnt.FontException {
            sp = 0;
            stems = 0;
            depth = 0;
            widthDone = cff.cff2;                    // CFF2 charstrings carry no width
            ended = false;
            x = ox;
            y = oy;
            if (g >= cff.charStrings.count) return;      // maxp counts more glyphs than CharStrings has: empty
            int fd = cff.fd(g);
            int vsi = cff.fdVsindex[fd];
            scalars = cff.regions != null && vsi >= 0 && vsi < cff.regions.length ? cff.regions[vsi] : null;
            exec(cff.charStrings.start(g), cff.charStrings.end(g), cff.lsubrs[fd]);
            path.close();
        }

        Sfnt.FontException bad(String what) {
            return new Sfnt.FontException("Glyph " + glyph + " of this font has a damaged outline: it " + what + ".");
        }

        private void exec(int p, int end, Index local) throws Sfnt.FontException {
            while (p < end) {
                if (++ops > MAX_OPS_PER_GLYPH) throw new Sfnt.FontException("too_complex", "Glyph " + glyph + " of this font takes too long to draw.");
                int b0 = t[p] & 0xff;
                if (b0 >= 32 || b0 == 28) {
                    p = number(p, end, b0);
                    continue;
                }
                p++;
                switch (b0) {
                    case 1: case 3: case 18: case 23:            // hstem, vstem, hstemhm, vstemhm: hints are dropped, only counted
                        width(sp % 2 != 0);
                        stems += sp / 2;
                        sp = 0;
                        break;
                    case 19: case 20: {                          // hintmask, cntrmask: numbers before them are an implied vstem
                        width(sp % 2 != 0);
                        stems += sp / 2;
                        sp = 0;
                        int bytes = (stems + 7) / 8;
                        if (bytes > end - p) throw bad("is cut off");
                        p += bytes;
                        break;
                    }
                    case 21: width(sp > 2); args(2); moveTo(s[sp - 2], s[sp - 1]); break;      // rmoveto
                    case 22: width(sp > 1); args(1); moveTo(s[sp - 1], 0); break;              // hmoveto
                    case 4: width(sp > 1); args(1); moveTo(0, s[sp - 1]); break;               // vmoveto
                    case 5:                                      // rlineto
                        args(2);
                        for (int i = 0; i + 1 < sp; i += 2) lineTo(s[i], s[i + 1]);
                        sp = 0;
                        break;
                    case 6: case 7: {                            // hlineto, vlineto: alternate, starting horizontal / vertical
                        args(1);
                        boolean h = b0 == 6;
                        for (int i = 0; i < sp; i++, h = !h) lineTo(h ? s[i] : 0, h ? 0 : s[i]);
                        sp = 0;
                        break;
                    }
                    case 8:                                      // rrcurveto
                        args(6);
                        for (int i = 0; i + 5 < sp; i += 6) curveTo(s[i], s[i + 1], s[i + 2], s[i + 3], s[i + 4], s[i + 5]);
                        sp = 0;
                        break;
                    case 24: {                                   // rcurveline: curves, then a line
                        args(8);
                        for (int i = 0; i + 6 <= sp - 2; i += 6) curveTo(s[i], s[i + 1], s[i + 2], s[i + 3], s[i + 4], s[i + 5]);
                        lineTo(s[sp - 2], s[sp - 1]);
                        sp = 0;
                        break;
                    }
                    case 25: {                                   // rlinecurve: lines, then a curve
                        args(8);
                        int c = sp - 6;
                        for (int i = 0; i + 1 < c; i += 2) lineTo(s[i], s[i + 1]);
                        curveTo(s[c], s[c + 1], s[c + 2], s[c + 3], s[c + 4], s[c + 5]);
                        sp = 0;
                        break;
                    }
                    case 26: {                                   // vvcurveto: dx1? {dya dxb dyb dyc}+
                        args(4);
                        int i = sp % 2;
                        double d1 = i == 1 ? s[0] : 0;
                        for (; i + 3 < sp; i += 4, d1 = 0) curveTo(d1, s[i], s[i + 1], s[i + 2], 0, s[i + 3]);
                        sp = 0;
                        break;
                    }
                    case 27: {                                   // hhcurveto: dy1? {dxa dxb dyb dxc}+
                        args(4);
                        int i = sp % 2;
                        double d1 = i == 1 ? s[0] : 0;
                        for (; i + 3 < sp; i += 4, d1 = 0) curveTo(s[i], d1, s[i + 1], s[i + 2], s[i + 3], 0);
                        sp = 0;
                        break;
                    }
                    case 30: case 31: {                          // vhcurveto, hvcurveto: curves that alternate their start tangent
                        args(4);
                        boolean h = b0 == 31;
                        for (int i = 0; i + 3 < sp; h = !h) {
                            boolean last = sp - i == 5;          // the last curve takes the fifth number as its final delta
                            double e = last ? s[i + 4] : 0;
                            if (h) curveTo(s[i], 0, s[i + 1], s[i + 2], e, s[i + 3]);
                            else curveTo(0, s[i], s[i + 1], s[i + 2], s[i + 3], e);
                            i += last ? 5 : 4;
                        }
                        sp = 0;
                        break;
                    }
                    case 10: call(local, local); break;          // callsubr
                    case 29: call(cff.gsubrs, local); break;     // callgsubr
                    case 11: return;                             // return (CFF2 subroutines simply end)
                    case 14:                                     // endchar (CFF 1), with an accented letter when four numbers are left
                        width(sp == 1 || sp == 5);
                        if (sp == 4 && !cff.cff2) seac();
                        sp = 0;
                        path.close();
                        ended = true;
                        return;
                    case 15:                                     // vsindex (CFF2)
                        if (cff.cff2) {
                            args(1);
                            double v = s[sp - 1];
                            if (cff.regions == null || !(v >= 0 && v < cff.regions.length)) throw bad("names variation data the font does not have");
                            scalars = cff.regions[(int) v];
                        }
                        sp = 0;
                        break;
                    case 16:                                     // blend (CFF2): n defaults, n * k deltas, n -> n blended values
                        if (cff.cff2) blend();
                        else sp = 0;
                        break;
                    case 12:
                        if (p >= end) throw bad("is cut off");
                        escape(t[p++] & 0xff);
                        break;
                    default:                                     // a reserved operator: ignored with its numbers, as FreeType does
                        sp = 0;
                        break;
                }
                if (ended) return;
            }
        }

        private int number(int p, int end, int b0) throws Sfnt.FontException {
            int len = b0 == 28 ? 3 : b0 <= 246 ? 1 : b0 <= 254 ? 2 : 5;
            if (len > end - p) throw bad("is cut off");
            double v;
            if (b0 == 28) v = (short) (((t[p + 1] & 0xff) << 8) | (t[p + 2] & 0xff));
            else if (b0 <= 246) v = b0 - 139;
            else if (b0 <= 250) v = (b0 - 247) * 256 + (t[p + 1] & 0xff) + 108;
            else if (b0 <= 254) v = -(b0 - 251) * 256 - (t[p + 1] & 0xff) - 108;
            else v = Sfnt.s32(t, p + 1) / 65536.0;          // 16.16 fixed
            push(v);
            return p + len;
        }

        private void push(double v) throws Sfnt.FontException {
            if (sp >= s.length) throw bad("puts too many numbers on the stack");
            s[sp++] = v;
        }

        private void args(int n) throws Sfnt.FontException {
            if (sp < n) throw bad("is missing the numbers of an operator");
        }

        /** CFF 1: the first stack-clearing operator may carry the advance width as an extra first number; it is dropped (hmtx has it). */
        private void width(boolean present) {
            if (widthDone) return;
            widthDone = true;
            if (present && sp > 0) {
                System.arraycopy(s, 1, s, 0, sp - 1);
                sp--;
            }
        }

        private void call(Index subrs, Index local) throws Sfnt.FontException {
            args(1);
            double v = s[--sp];
            if (subrs == null || subrs.count == 0 || !(Math.abs(v) <= 1 << 30)) throw bad("calls a subroutine the font does not have");
            int n = subrs.count;
            int i = (int) v + (n < 1240 ? 107 : n < 33900 ? 1131 : 32768);
            if (i < 0 || i >= n) throw bad("calls a subroutine the font does not have");
            if (depth >= MAX_SUBR_DEPTH) throw bad("nests its subroutines too deep");
            depth++;
            exec(subrs.start(i), subrs.end(i), local);
            depth--;
        }

        private void blend() throws Sfnt.FontException {
            args(1);
            double nv = s[--sp];
            if (scalars == null) throw bad("blends values without a variation store");
            int k = scalars.length;
            if (!(nv >= 0 && nv <= sp) || (long) nv * (k + 1) > sp) throw bad("blends more numbers than it has");
            int n = (int) nv, base = sp - n * (k + 1);
            for (int i = 0; i < n; i++) {
                double d = 0;
                int at = base + n + i * k;
                for (int r = 0; r < k; r++) if (scalars[r] != 0) d += scalars[r] * s[at + r];
                s[base + i] += d;
            }
            sp = base + n;
        }

        private void seac() throws Sfnt.FontException {
            if (cff.cid) throw bad("builds an accented letter (seac), which a CID-keyed font cannot do");
            if (seacBase >= 0) throw bad("builds an accented letter from another accented letter");
            int base = cff.glyphForStandardCode(s[2]), accent = cff.glyphForStandardCode(s[3]);
            if (base < 0 || accent < 0) throw bad("builds an accented letter from a glyph the font does not have");
            if (!(Math.abs(s[0]) <= MAX_COORD && Math.abs(s[1]) <= MAX_COORD)) throw bad("places an accent far outside the glyph area");
            seacBase = base;
            seacAccent = accent;
            seacDx = s[0];
            seacDy = s[1];
        }

        private void escape(int b1) throws Sfnt.FontException {
            switch (b1) {
                case 35:                                         // flex: two curves (the flex depth is a hint)
                    args(13);
                    curveTo(s[0], s[1], s[2], s[3], s[4], s[5]);
                    curveTo(s[6], s[7], s[8], s[9], s[10], s[11]);
                    sp = 0;
                    return;
                case 34:                                         // hflex
                    args(7);
                    curveTo(s[0], 0, s[1], s[2], s[3], 0);
                    curveTo(s[4], 0, s[5], -s[2], s[6], 0);
                    sp = 0;
                    return;
                case 36:                                         // hflex1: ends at the starting height
                    args(9);
                    curveTo(s[0], s[1], s[2], s[3], s[4], 0);
                    curveTo(s[5], 0, s[6], s[7], s[8], -(s[1] + s[3] + s[7]));
                    sp = 0;
                    return;
                case 37: {                                       // flex1: the last number is dx or dy, whichever way the flex runs longer
                    args(11);
                    double dx = s[0] + s[2] + s[4] + s[6] + s[8], dy = s[1] + s[3] + s[5] + s[7] + s[9];
                    curveTo(s[0], s[1], s[2], s[3], s[4], s[5]);
                    if (Math.abs(dx) > Math.abs(dy)) curveTo(s[6], s[7], s[8], s[9], s[10], -dy);
                    else curveTo(s[6], s[7], s[8], s[9], -dx, s[10]);
                    sp = 0;
                    return;
                }
                case 0:                                          // dotsection: an old hint, ignored
                    sp = 0;
                    return;
                default:
                    break;
            }
            boolean arithmetic = (b1 >= 3 && b1 <= 5) || (b1 >= 9 && b1 <= 30 && b1 != 13 && b1 != 16 && b1 != 17 && b1 != 19 && b1 != 25);
            if (!arithmetic) { sp = 0; return; }                 // reserved: ignored with its numbers
            if (cff.cff2) throw bad("uses an arithmetic operator, which CFF2 does not have");
            switch (b1) {
                case 3: args(2); s[sp - 2] = s[sp - 2] != 0 && s[sp - 1] != 0 ? 1 : 0; sp--; break;     // and
                case 4: args(2); s[sp - 2] = s[sp - 2] != 0 || s[sp - 1] != 0 ? 1 : 0; sp--; break;     // or
                case 5: args(1); s[sp - 1] = s[sp - 1] == 0 ? 1 : 0; break;                             // not
                case 9: args(1); s[sp - 1] = Math.abs(s[sp - 1]); break;                                // abs
                case 10: args(2); s[sp - 2] += s[sp - 1]; sp--; break;                                  // add
                case 11: args(2); s[sp - 2] -= s[sp - 1]; sp--; break;                                  // sub
                case 12:                                                                                // div
                    args(2);
                    if (s[sp - 1] == 0) throw bad("divides by zero");
                    s[sp - 2] /= s[sp - 1];
                    sp--;
                    break;
                case 14: args(1); s[sp - 1] = -s[sp - 1]; break;                                        // neg
                case 15: args(2); s[sp - 2] = s[sp - 2] == s[sp - 1] ? 1 : 0; sp--; break;              // eq
                case 18: args(1); sp--; break;                                                          // drop
                case 20: {                                                                              // put: value i
                    args(2);
                    store[storeIndex(s[sp - 1])] = s[sp - 2];
                    sp -= 2;
                    break;
                }
                case 21: args(1); s[sp - 1] = store[storeIndex(s[sp - 1])]; break;                      // get
                case 22: {                                                                              // ifelse: s1 s2 v1 v2
                    args(4);
                    double r = s[sp - 2] <= s[sp - 1] ? s[sp - 4] : s[sp - 3];
                    sp -= 4;
                    push(r);
                    break;
                }
                case 23: throw bad("uses the random operator, which a TrueType outline cannot reproduce");
                case 24: args(2); s[sp - 2] *= s[sp - 1]; sp--; break;                                  // mul
                case 26:                                                                                // sqrt
                    args(1);
                    if (s[sp - 1] < 0) throw bad("takes the square root of a negative number");
                    s[sp - 1] = Math.sqrt(s[sp - 1]);
                    break;
                case 27: args(1); push(s[sp - 1]); break;                                               // dup
                case 28: { args(2); double v = s[sp - 1]; s[sp - 1] = s[sp - 2]; s[sp - 2] = v; break; }       // exch
                case 29: {                                                                              // index: copies the i-th number below
                    args(1);
                    double iv = s[--sp];
                    if (!(Math.abs(iv) < 1e9)) throw bad("indexes the stack out of range");
                    int i = iv < 0 ? 0 : (int) iv;
                    if (i >= sp) throw bad("indexes the stack out of range");
                    push(s[sp - 1 - i]);
                    break;
                }
                case 30: {                                                                              // roll: N J, the top N rotate by J
                    args(2);
                    double nv = s[sp - 2], jv = s[sp - 1];
                    sp -= 2;
                    if (!(nv >= 0 && nv <= sp) || !(Math.abs(jv) < 1e9)) throw bad("rolls the stack out of range");
                    int n = (int) nv;
                    if (n == 0) break;
                    int j = (int) (((long) jv % n + n) % n);
                    double[] tmp = new double[n];
                    for (int i = 0; i < n; i++) tmp[(i + j) % n] = s[sp - n + i];
                    System.arraycopy(tmp, 0, s, sp - n, n);
                    break;
                }
                default: sp = 0; break;
            }
        }

        private int storeIndex(double v) throws Sfnt.FontException {
            if (!(v >= 0 && v < store.length)) throw bad("uses the transient array out of range");
            return (int) v;
        }

        // Path operators: relative to the current point; a path operator without a moveto starts a contour where the pen is.

        private void moveTo(double dx, double dy) throws Sfnt.FontException {
            path.close();
            x += dx;
            y += dy;
            path.start = path.size;
            point(x, y, false);
            sp = 0;
        }

        private void lineTo(double dx, double dy) throws Sfnt.FontException {
            open();
            x += dx;
            y += dy;
            point(x, y, false);
        }

        private void curveTo(double dx1, double dy1, double dx2, double dy2, double dx3, double dy3) throws Sfnt.FontException {
            open();
            double x1 = x + dx1, y1 = y + dy1, x2 = x1 + dx2, y2 = y1 + dy2;
            x = x2 + dx3;
            y = y2 + dy3;
            point(x1, y1, true);
            point(x2, y2, true);
            point(x, y, false);
        }

        private void open() throws Sfnt.FontException {
            if (path.start >= 0) return;
            path.start = path.size;
            point(x, y, false);
        }

        private void point(double px, double py, boolean control) throws Sfnt.FontException {
            if (!(Math.abs(px) <= MAX_COORD && Math.abs(py) <= MAX_COORD)) throw bad("draws a point far outside the glyph area");
            if (!path.add(px, py, control)) throw bad("has too many points");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Path -> TrueType glyph
    // ---------------------------------------------------------------------------------------------

    /** Turns interpreted paths into encoded TrueType glyphs: cubics to quadratics, contours reversed, coordinates rounded. */
    static final class Converter {
        private final double tol;
        private final double[] cubic = new double[8], quads = new double[2 * MAX_PIECES], pieces = new double[10 * MAX_PIECES];
        // one contour, forward: the end of each segment and where its quadratic control points end in fq
        private double[] segX = new double[64], segY = new double[64], fq = new double[256];
        private int[] segQ = new int[64];
        // the glyph, reversed
        private double[] ox = new double[256], oy = new double[256];
        private boolean[] on = new boolean[256];
        private int n;
        long work;                        // quadratic pieces tried so far

        Converter(double tol) { this.tol = tol; }

        byte[] glyph(Interpreter in, int gid) throws Sfnt.FontException {
            in.run(gid);
            double[] xf = in.cff.xform[in.cff.fd(gid)];
            if (in.seacBase >= 0) return Glyf.encode(seacComposite(in, xf));
            Path path = in.path;
            if (path.contours == 0) return new byte[0];
            n = 0;
            int[] endPts = new int[path.contours];
            int from = 0;
            for (int c = 0; c < path.contours; c++) {
                contour(path, from, path.ends[c], xf, in);
                endPts[c] = n - 1;
                from = path.ends[c];
            }
            if (n > Glyf.MAX_POINTS) throw in.bad("has too many points for a TrueType glyph");
            if (xf != null && xf[0] * xf[3] - xf[1] * xf[2] < 0) {
                // A mirroring FontMatrix has turned every contour round already: put each back in its drawn direction.
                for (int c = 0, s = 0; c < endPts.length; s = endPts[c++] + 1) unreverse(s + 1, endPts[c]);
            }
            int[] xs = new int[n], ys = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = Glyf.round(ox[i]);
                ys[i] = Glyf.round(oy[i]);
            }
            return Glyf.encode(Glyf.Glyph.simple(endPts, xs, ys, java.util.Arrays.copyOf(on, n)));
        }

        private static Glyf.Glyph seacComposite(Interpreter in, double[] xf) {
            Glyf.Glyph g = new Glyf.Glyph();
            g.contours = -1;
            Glyf.Component base = new Glyf.Component(), accent = new Glyf.Component();
            base.glyph = in.seacBase;
            accent.glyph = in.seacAccent;
            double dx = in.seacDx, dy = in.seacDy;
            if (xf != null) {                                          // the offset moves with the matrix's linear part
                double tx = xf[0] * dx + xf[2] * dy;
                dy = xf[1] * dx + xf[3] * dy;
                dx = tx;
            }
            accent.dx = Glyf.round(dx);
            accent.dy = Glyf.round(dy);
            base.flags = accent.flags = Glyf.ROUND_XY_TO_GRID;         // the flag fontTools' TrueType pen sets
            g.components.add(base);
            g.components.add(accent);
            return g;
        }

        /**
         * Adds one contour (path points [from, to)) reversed, as fontTools' ReverseContourPen and TTGlyphPen write it: the start point
         * first, then the implied closing line's far end when the contour does not return to its start, then every segment backwards;
         * the segment that would end on the start point again is the implied close.
         */
        private void contour(Path p, int from, int to, double[] xf, Interpreter in) throws Sfnt.FontException {
            int segs = 0, nq = 0;
            double px = tx(p, from, xf), py = ty(p, from, xf);
            double x0 = px, y0 = py;
            for (int i = from + 1; i < to; ) {
                if (p.ctrl[i]) {
                    cubic[0] = px; cubic[1] = py;
                    cubic[2] = tx(p, i, xf); cubic[3] = ty(p, i, xf);
                    cubic[4] = tx(p, i + 1, xf); cubic[5] = ty(p, i + 1, xf);
                    cubic[6] = tx(p, i + 2, xf); cubic[7] = ty(p, i + 2, xf);
                    int k = cubicToQuads(cubic, tol, quads, pieces);
                    work += k * (k + 1L) / 2 + (k == MAX_PIECES ? MAX_PIECES : 0);
                    if (n + (nq >> 1) + k > Glyf.MAX_POINTS) throw in.bad("has too many points for a TrueType glyph");
                    if (nq + 2 * k > fq.length) fq = java.util.Arrays.copyOf(fq, Math.max(fq.length * 2, nq + 2 * k));
                    System.arraycopy(quads, 0, fq, nq, 2 * k);
                    nq += 2 * k;
                    px = cubic[6]; py = cubic[7];
                    i += 3;
                } else {
                    px = tx(p, i, xf); py = ty(p, i, xf);
                    i++;
                }
                if (segs == segX.length) {
                    segX = java.util.Arrays.copyOf(segX, segs * 2);
                    segY = java.util.Arrays.copyOf(segY, segs * 2);
                    segQ = java.util.Arrays.copyOf(segQ, segs * 2);
                }
                segX[segs] = px; segY[segs] = py; segQ[segs] = nq;
                segs++;
            }
            emit(x0, y0, true, in);
            // Compared on the integer grid: blended coordinates rarely return to the start exactly, and a closing line shorter than one
            // unit would only add a duplicate point.
            boolean closes = Glyf.round(segX[segs - 1]) == Glyf.round(x0) && Glyf.round(segY[segs - 1]) == Glyf.round(y0);
            if (!closes) emit(segX[segs - 1], segY[segs - 1], true, in);
            for (int k = segs - 1; k >= 0; k--) {
                int qStart = k == 0 ? 0 : segQ[k - 1];
                for (int q = segQ[k] - 2; q >= qStart; q -= 2) emit(fq[q], fq[q + 1], false, in);
                if (k > 0) emit(segX[k - 1], segY[k - 1], true, in);
            }
        }

        /** Reverses points a..b (inclusive) of the glyph. */
        private void unreverse(int a, int b) {
            for (; a < b; a++, b--) {
                double tx = ox[a], ty = oy[a];
                boolean to = on[a];
                ox[a] = ox[b]; oy[a] = oy[b]; on[a] = on[b];
                ox[b] = tx; oy[b] = ty; on[b] = to;
            }
        }

        private static double tx(Path p, int i, double[] xf) { return xf == null ? p.x[i] : xf[0] * p.x[i] + xf[2] * p.y[i] + xf[4]; }

        private static double ty(Path p, int i, double[] xf) { return xf == null ? p.y[i] : xf[1] * p.x[i] + xf[3] * p.y[i] + xf[5]; }

        private void emit(double px, double py, boolean onCurve, Interpreter in) throws Sfnt.FontException {
            if (n == ox.length) {
                if (n > Glyf.MAX_POINTS) throw in.bad("has too many points for a TrueType glyph");
                ox = java.util.Arrays.copyOf(ox, n * 2);
                oy = java.util.Arrays.copyOf(oy, n * 2);
                on = java.util.Arrays.copyOf(on, n * 2);
            }
            ox[n] = px; oy[n] = py; on[n] = onCurve;
            n++;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cubic to quadratic (fontTools' cu2qu algorithm)
    // ---------------------------------------------------------------------------------------------

    /**
     * The quadratic spline that follows cubic {@code c} (x0 y0 x1 y1 x2 y2 x3 y3) within {@code tol}, chosen as cu2qu chooses it: one
     * quadratic whose control point is where the end tangents meet, else the cubic cut into n = 2, 3 ... 100 pieces of equal parameter
     * length, each given a control point on the line between its two tangent-extended points, the on-curve points between them implied
     * (the midpoints of neighbouring control points). The first n whose error curve stays within tol wins. Writes the n off-curve points
     * into {@code q} (x, y pairs) and returns n. {@code work} holds 10 * {@link #MAX_PIECES} numbers.
     */
    static int cubicToQuads(double[] c, double tol, double[] q, double[] work) {
        double t2 = tol * tol;
        if (oneQuad(c, t2, q)) return 1;
        for (int n = 2; n <= MAX_PIECES; n++) if (spline(c, n, t2, q, work, true)) return n;
        spline(c, MAX_PIECES, t2, q, work, false);           // nothing fits (a wild curve): the finest spline, unchecked
        return MAX_PIECES;
    }

    private static boolean oneQuad(double[] c, double t2, double[] q) {
        double abx = c[2] - c[0], aby = c[3] - c[1], cdx = c[6] - c[4], cdy = c[7] - c[5];
        double px = -aby, py = abx;                          // the start tangent turned a quarter
        double den = px * cdx + py * cdy, qx, qy;
        if (den == 0) {
            // Parallel tangents: no single control point, unless three or four of the points are one point (cu2qu then tries that one).
            boolean inner = c[2] == c[4] && c[3] == c[5];
            if (!inner || !((c[0] == c[2] && c[1] == c[3]) || (c[4] == c[6] && c[5] == c[7]))) return false;
            qx = c[2];
            qy = c[3];
        } else {
            double h = (px * (c[0] - c[4]) + py * (c[1] - c[5])) / den;
            qx = c[4] + cdx * h;
            qy = c[5] + cdy * h;
            if (Double.isNaN(qx) || Double.isNaN(qy)) return false;
        }
        // The quadratic raised to a cubic, minus the given cubic: its end points are the same, so only the inner points differ.
        double e1x = c[0] + (qx - c[0]) * (2.0 / 3) - c[2], e1y = c[1] + (qy - c[1]) * (2.0 / 3) - c[3];
        double e2x = c[6] + (qx - c[6]) * (2.0 / 3) - c[4], e2y = c[7] + (qy - c[7]) * (2.0 / 3) - c[5];
        if (!within(0, 0, e1x, e1y, e2x, e2y, 0, 0, t2, 0)) return false;
        q[0] = qx;
        q[1] = qy;
        return true;
    }

    /**
     * Cuts cubic {@code c} (8 numbers from {@code from}) into n pieces of equal parameter length, written to {@code w} (8 numbers each).
     * Halves and thirds are cut with de Casteljau's closed forms, which are exact for the integer points most fonts have, so the spline
     * rounds exactly as fontTools' does; other counts come from the cubic's power form.
     */
    private static void pieces(double[] c, int from, int n, double[] w, int at) {
        if (n == 2 || n == 3) {
            for (int k = 0; k < 2; k++) {                    // x, then y
                int i = from + k, o = at + k;
                double p0 = c[i], p1 = c[i + 2], p2 = c[i + 4], p3 = c[i + 6];
                if (n == 2) {
                    double m = (p0 + 3 * (p1 + p2) + p3) * 0.125, h = (p3 + p2 - p1 - p0) * 0.125;      // the point at 1/2, a sixth of its tangent
                    w[o] = p0; w[o + 2] = (p0 + p1) * 0.5; w[o + 4] = m - h; w[o + 6] = m;
                    w[o + 8] = m; w[o + 10] = m + h; w[o + 12] = (p2 + p3) * 0.5; w[o + 14] = p3;
                } else {
                    double m1 = (p0 * 8 + p1 * 12 + p2 * 6 + p3) * (1.0 / 27), h1 = (p3 + p2 * 3 - p0 * 4) * (1.0 / 27);   // at 1/3
                    double m2 = (p0 + p1 * 6 + p2 * 12 + p3 * 8) * (1.0 / 27), h2 = (p3 * 4 - p1 * 3 - p0) * (1.0 / 27);   // at 2/3
                    w[o] = p0; w[o + 2] = (p0 * 2 + p1) / 3.0; w[o + 4] = m1 - h1; w[o + 6] = m1;
                    w[o + 8] = m1; w[o + 10] = m1 + h1; w[o + 12] = m2 - h2; w[o + 14] = m2;
                    w[o + 16] = m2; w[o + 18] = m2 + h2; w[o + 20] = (p2 + p3 * 2) / 3.0; w[o + 22] = p3;
                }
            }
            return;
        }
        if (n == 4 || n == 6) {                              // halves, then each cut in two or three
            int tmp = at + 8 * n;
            pieces(c, from, 2, w, tmp);
            pieces(w, tmp, n / 2, w, at);
            pieces(w, tmp + 8, n / 2, w, at + 4 * n);
            return;
        }
        // The cubic in power form a t^3 + b t^2 + c t + d, so each piece [i/n, (i+1)/n] comes straight from it.
        double cx = (c[from + 2] - c[from]) * 3, cy = (c[from + 3] - c[from + 1]) * 3;
        double bx = (c[from + 4] - c[from + 2]) * 3 - cx, by = (c[from + 5] - c[from + 3]) * 3 - cy;
        double dx = c[from], dy = c[from + 1];
        double ax = c[from + 6] - dx - cx - bx, ay = c[from + 7] - dy - cy - by;
        double dt = 1.0 / n, dt2 = dt * dt, dt3 = dt * dt2;
        for (int i = 0; i < n; i++) {
            double t1 = i * dt, t12 = t1 * t1;
            double a1x = ax * dt3, a1y = ay * dt3;
            double b1x = (3 * ax * t1 + bx) * dt2, b1y = (3 * ay * t1 + by) * dt2;
            double c1x = (2 * bx * t1 + cx + 3 * ax * t12) * dt, c1y = (2 * by * t1 + cy + 3 * ay * t12) * dt;
            double d1x = ax * t1 * t12 + bx * t12 + cx * t1 + dx, d1y = ay * t1 * t12 + by * t12 + cy * t1 + dy;
            int o = at + 8 * i;                              // back to Bezier points p0 .. p3
            w[o] = d1x; w[o + 1] = d1y;
            w[o + 2] = c1x / 3 + d1x; w[o + 3] = c1y / 3 + d1y;
            w[o + 4] = (b1x + c1x) / 3 + w[o + 2]; w[o + 5] = (b1y + c1y) / 3 + w[o + 3];
            w[o + 6] = a1x + d1x + c1x + b1x; w[o + 7] = a1y + d1y + c1y + b1y;
        }
    }

    /** The n-piece spline of cubic c; false when {@code check} and a piece strays more than tol from the cubic. */
    private static boolean spline(double[] c, int n, double t2, double[] q, double[] w, boolean check) {
        pieces(c, 0, n, w, 0);
        for (int i = 0; i < n; i++) {
            int o = 8 * i;
            // the piece's control point: on the line between p0 + 1.5 (p1 - p0) and p3 + 1.5 (p2 - p3), at i / (n - 1)
            double f = (double) i / (n - 1);
            double ux = w[o] + (w[o + 2] - w[o]) * 1.5, uy = w[o + 1] + (w[o + 3] - w[o + 1]) * 1.5;
            double vx = w[o + 6] + (w[o + 4] - w[o + 6]) * 1.5, vy = w[o + 7] + (w[o + 5] - w[o + 7]) * 1.5;
            q[2 * i] = ux + (vx - ux) * f;
            q[2 * i + 1] = uy + (vy - uy) * f;
        }
        if (!check) return true;
        double e0x = 0, e0y = 0;                             // how far the spline is from the piece at the piece's start
        for (int i = 0; i < n; i++) {
            int o = 8 * i;
            double q0x = i == 0 ? c[0] : (q[2 * i - 2] + q[2 * i]) * 0.5, q0y = i == 0 ? c[1] : (q[2 * i - 1] + q[2 * i + 1]) * 0.5;
            double q1x = q[2 * i], q1y = q[2 * i + 1];
            double q2x = i == n - 1 ? w[o + 6] : (q[2 * i] + q[2 * i + 2]) * 0.5, q2y = i == n - 1 ? w[o + 7] : (q[2 * i + 1] + q[2 * i + 3]) * 0.5;
            double e3x = q2x - w[o + 6], e3y = q2y - w[o + 7];
            if (e3x * e3x + e3y * e3y > t2) return false;
            double e1x = q0x + (q1x - q0x) * (2.0 / 3) - w[o + 2], e1y = q0y + (q1y - q0y) * (2.0 / 3) - w[o + 3];
            double e2x = q2x + (q1x - q2x) * (2.0 / 3) - w[o + 4], e2y = q2y + (q1y - q2y) * (2.0 / 3) - w[o + 5];
            if (!within(e0x, e0y, e1x, e1y, e2x, e2y, e3x, e3y, t2, 0)) return false;
            e0x = e3x;
            e0y = e3y;
        }
        return true;
    }

    /**
     * Whether the cubic error curve p0..p3 stays within sqrt(t2) of the origin: true once both inner points are inside, false once its
     * midpoint is outside, else both halves are checked (split at t = 1/2).
     */
    private static boolean within(double p0x, double p0y, double p1x, double p1y, double p2x, double p2y, double p3x, double p3y, double t2, int depth) {
        if (p2x * p2x + p2y * p2y <= t2 && p1x * p1x + p1y * p1y <= t2) return true;
        double mx = (p0x + 3 * (p1x + p2x) + p3x) * 0.125, my = (p0y + 3 * (p1y + p2y) + p3y) * 0.125;
        if (mx * mx + my * my > t2 || depth > 40) return false;
        double hx = (p3x + p2x - p1x - p0x) * 0.125, hy = (p3y + p2y - p1y - p0y) * 0.125;
        return within(p0x, p0y, (p0x + p1x) * 0.5, (p0y + p1y) * 0.5, mx - hx, my - hy, mx, my, t2, depth + 1)
                && within(mx, my, mx + hx, my + hy, (p2x + p3x) * 0.5, (p2y + p3y) * 0.5, p3x, p3y, t2, depth + 1);
    }

    // ---------------------------------------------------------------------------------------------
    // post, Standard Encoding
    // ---------------------------------------------------------------------------------------------

    /** post version 3 (no glyph names): the font's own values, or the CFF Top DICT's when the font has no post table. */
    static byte[] post(byte[] old, Map<Integer, double[]> top) {
        byte[] p = new byte[32];
        if (old != null && old.length >= 32) {
            System.arraycopy(old, 0, p, 0, 16);
        } else {
            double angle = first(top, ITALIC_ANGLE, 0), thickness = first(top, UNDERLINE_THICKNESS, 50);
            double position = first(top, UNDERLINE_POSITION, -100) + thickness / 2;     // CFF gives the line's centre, post its top
            Sfnt.w32(p, 4, (int) Math.round(Math.max(-180, Math.min(180, angle)) * 65536));
            Sfnt.w16(p, 8, Glyf.clamp16(Glyf.round(position)));
            Sfnt.w16(p, 10, Glyf.clamp16(Glyf.round(thickness)));
            Sfnt.w32(p, 12, first(top, IS_FIXED_PITCH, 0) != 0 ? 1 : 0);
        }
        Sfnt.w32(p, 0, 0x00030000);
        return p;
    }

    private static double first(Map<Integer, double[]> d, int op, double def) {
        double[] v = d == null ? null : d.get(op);
        return v == null || v.length == 0 || Double.isNaN(v[0]) ? def : v[0];
    }

    /** Standard Encoding (CFF specification, appendix B) as runs: first code, last code, SID of the first code. */
    private static final int[] STANDARD_RUNS = { 32, 126, 1, 161, 175, 96, 177, 180, 111, 182, 189, 115, 191, 191, 123, 193, 200, 124,
            202, 203, 132, 205, 208, 134, 225, 225, 138, 227, 227, 139, 232, 235, 140, 241, 241, 144, 245, 245, 145, 248, 251, 146 };

    /** The SID of the glyph name Standard Encoding gives a code, or 0 (.notdef). */
    static int standardSid(int code) {
        for (int i = 0; i < STANDARD_RUNS.length; i += 3)
            if (code >= STANDARD_RUNS[i] && code <= STANDARD_RUNS[i + 1]) return STANDARD_RUNS[i + 2] + code - STANDARD_RUNS[i];
        return 0;
    }
}
