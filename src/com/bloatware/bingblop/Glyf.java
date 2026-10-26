package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;

/**
 * TrueType glyph outlines (the glyf and loca tables) and horizontal metrics (hmtx / hhea), read and written one glyph at a time.
 *
 * <p>The Custom Fonts tab changes outlines in three places: a variable font's static instance moves every point ({@link FontInstancer}),
 * PostScript outlines become TrueType ones ({@link CffToGlyf}), and an emoji font's glyphs are appended to a text font ({@link EmojiMerge}).
 * They decode a glyph with {@link #decode}, change it, encode it with {@link #encode} and hand the whole list to {@link #write}, which fixes
 * the bounds of composite glyphs, lays out glyf and loca, and updates head (bounds, loca format) and maxp (glyph count and the sizes).
 * Glyphs are kept as their encoded bytes between steps so a 60,000-glyph CJK font is never held as objects all at once.
 * The input is untrusted: every read is bounds-checked and ends in a {@link Sfnt.FontException}.
 */
public final class Glyf {

    private Glyf() {}

    // Simple glyph flags
    static final int ON_CURVE = 0x01, X_SHORT = 0x02, Y_SHORT = 0x04, REPEAT = 0x08, X_SAME_OR_POS = 0x10, Y_SAME_OR_POS = 0x20, OVERLAP_SIMPLE = 0x40;
    // Composite glyph flags
    public static final int ARG_WORDS = 0x0001, ARGS_ARE_XY = 0x0002, ROUND_XY_TO_GRID = 0x0004, HAVE_SCALE = 0x0008, MORE_COMPONENTS = 0x0020,
            HAVE_XY_SCALE = 0x0040, HAVE_2X2 = 0x0080, HAVE_INSTRUCTIONS = 0x0100, USE_MY_METRICS = 0x0200, OVERLAP_COMPOUND = 0x0400,
            SCALED_OFFSET = 0x0800, UNSCALED_OFFSET = 0x1000;

    /** More points than one glyph may have (TrueType counts them in 16 bits). */
    public static final int MAX_POINTS = 0xFFFF;
    /** Composites nested deeper than this are refused (real fonts nest two or three deep). */
    public static final int MAX_DEPTH = 16;
    /** Components in one composite glyph above this are refused. */
    public static final int MAX_COMPONENTS = 1024;

    /** One component of a composite glyph. The transform is kept as the font stores it (F2Dot14) so an unchanged glyph writes back the same. */
    public static final class Component {
        public int glyph;
        /** USE_MY_METRICS, ROUND_XY_TO_GRID, OVERLAP_COMPOUND, SCALED_OFFSET, UNSCALED_OFFSET as read; the encoding flags are recomputed. */
        public int flags;
        /** True: dx / dy are an offset. False: they are point numbers (parent point dx is matched to this component's point dy). */
        public boolean xy = true;
        public int dx, dy;
        /** 0 none, 1 one scale (a), 2 x and y scale (a, d), 3 a 2x2 matrix (a, b, c, d); each in F2Dot14 units (16384 = 1.0). */
        public int scaleKind;
        public int a = 16384, b, c, d = 16384;

        public Component copy() {
            Component k = new Component();
            k.glyph = glyph; k.flags = flags; k.xy = xy; k.dx = dx; k.dy = dy;
            k.scaleKind = scaleKind; k.a = a; k.b = b; k.c = c; k.d = d;
            return k;
        }

        boolean scaled() { return scaleKind != 0 && !(a == 16384 && b == 0 && c == 0 && d == 16384); }
    }

    /** A decoded glyph: empty, simple (contours of points) or composite (components). */
    public static final class Glyph {
        /** -1 for a composite, 0 for an empty glyph, otherwise the number of contours. */
        public int contours;
        public int xMin, yMin, xMax, yMax;
        /** Simple glyphs: the index of the last point of each contour, the points, and which are on the curve. */
        public int[] endPts = new int[0];
        public int[] x = new int[0], y = new int[0];
        public boolean[] on = new boolean[0];
        public boolean overlap;                     // OVERLAP_SIMPLE on the first flag (variable fonts set it)
        /** Hinting instructions (simple or composite); empty when there are none or they were dropped. */
        public byte[] instructions = new byte[0];
        /** Composite glyphs: the components in order. */
        public List<Component> components = new ArrayList<Component>();

        public boolean isEmpty() { return contours == 0 && components.isEmpty(); }

        public boolean isComposite() { return contours < 0; }

        public int numPoints() { return x.length; }

        /** A simple glyph from points; contours given by their end indexes. */
        public static Glyph simple(int[] endPts, int[] x, int[] y, boolean[] on) {
            Glyph g = new Glyph();
            g.contours = endPts.length;
            g.endPts = endPts; g.x = x; g.y = y; g.on = on;
            g.computeSimpleBounds();
            return g;
        }

        public void computeSimpleBounds() {
            if (contours <= 0 || x.length == 0) { xMin = yMin = xMax = yMax = 0; return; }
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for (int i = 0; i < x.length; i++) {
                x0 = Math.min(x0, x[i]); x1 = Math.max(x1, x[i]);
                y0 = Math.min(y0, y[i]); y1 = Math.max(y1, y[i]);
            }
            xMin = x0; yMin = y0; xMax = x1; yMax = y1;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // loca and raw glyph bytes
    // ---------------------------------------------------------------------------------------------

    /** The glyph offsets (numGlyphs + 1 of them), checked: in order and inside glyf. */
    public static long[] readLoca(Sfnt f) throws Sfnt.FontException {
        byte[] loca = f.table("loca"), glyf = f.table("glyf");
        if (loca == null || glyf == null) throw new Sfnt.FontException("This font has no TrueType outlines (glyf / loca).");
        int n = f.numGlyphs();
        boolean longFmt = f.indexToLocFormat() == 1;
        if (f.indexToLocFormat() != 0 && !longFmt) throw new Sfnt.FontException("This font's loca format (" + f.indexToLocFormat() + ") is not valid.");
        long need = (long) (n + 1) * (longFmt ? 4 : 2);
        if (loca.length < need) throw new Sfnt.FontException("This font's loca table is shorter than its glyph count.");
        long[] off = new long[n + 1];
        for (int i = 0; i <= n; i++) {
            off[i] = longFmt ? Sfnt.u32(loca, 4 * i) : 2L * Sfnt.u16(loca, 2 * i);
            if (off[i] > glyf.length) {
                // Some fonts end loca a few bytes past glyf; clamp the last entry, refuse anything else.
                if (i == n && off[i] - glyf.length < 4) off[i] = glyf.length;
                else throw new Sfnt.FontException("Glyph " + i + " of this font points outside its glyf table.");
            }
            if (i > 0 && off[i] < off[i - 1]) throw new Sfnt.FontException("This font's loca table is out of order at glyph " + i + ".");
        }
        return off;
    }

    /** The encoded bytes of every glyph (an empty array for an empty glyph), straight from glyf. */
    public static byte[][] readAll(Sfnt f) throws Sfnt.FontException {
        long[] off = readLoca(f);
        byte[] glyf = f.table("glyf");
        byte[][] out = new byte[off.length - 1][];
        for (int i = 0; i < out.length; i++) {
            int a = (int) off[i], b = (int) off[i + 1];
            out[i] = b - a < 10 ? new byte[0] : java.util.Arrays.copyOfRange(glyf, a, b);    // shorter than a header: empty
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Decode
    // ---------------------------------------------------------------------------------------------

    public static Glyph decode(byte[] raw) throws Sfnt.FontException {
        Glyph g = new Glyph();
        if (raw == null || raw.length == 0) return g;
        int n = Sfnt.s16(raw, 0);
        g.xMin = Sfnt.s16(raw, 2); g.yMin = Sfnt.s16(raw, 4); g.xMax = Sfnt.s16(raw, 6); g.yMax = Sfnt.s16(raw, 8);
        if (n >= 0) decodeSimple(raw, n, g);
        else decodeComposite(raw, g);
        return g;
    }

    private static void decodeSimple(byte[] raw, int n, Glyph g) throws Sfnt.FontException {
        g.contours = n;
        if (n == 0) return;
        int p = 10;
        int[] ends = new int[n];
        int prev = -1;
        for (int i = 0; i < n; i++) {
            ends[i] = Sfnt.u16(raw, p); p += 2;
            // Each contour ends after the one before it (FreeType refuses the glyph otherwise).
            if (ends[i] <= prev) throw new Sfnt.FontException("A glyph of this font has its contours out of order.");
            prev = ends[i];
        }
        int count = prev + 1;
        if (count > MAX_POINTS) throw new Sfnt.FontException("A glyph of this font has too many points.");
        int il = Sfnt.u16(raw, p); p += 2;
        Sfnt.need(raw, p, il);
        g.instructions = java.util.Arrays.copyOfRange(raw, p, p + il);
        p += il;
        int[] flags = new int[count];
        for (int i = 0; i < count; ) {
            int fl = Sfnt.u8(raw, p++);
            flags[i++] = fl;
            if ((fl & REPEAT) != 0) {
                int rep = Sfnt.u8(raw, p++);
                if (i + rep > count) throw new Sfnt.FontException("A glyph of this font repeats a flag past its last point.");
                for (int k = 0; k < rep; k++) flags[i++] = fl;
            }
        }
        int[] xs = new int[count], ys = new int[count];
        int v = 0;
        for (int i = 0; i < count; i++) {
            int fl = flags[i];
            if ((fl & X_SHORT) != 0) { int d = Sfnt.u8(raw, p++); v += (fl & X_SAME_OR_POS) != 0 ? d : -d; }
            else if ((fl & X_SAME_OR_POS) == 0) { v += Sfnt.s16(raw, p); p += 2; }
            xs[i] = v;
        }
        v = 0;
        for (int i = 0; i < count; i++) {
            int fl = flags[i];
            if ((fl & Y_SHORT) != 0) { int d = Sfnt.u8(raw, p++); v += (fl & Y_SAME_OR_POS) != 0 ? d : -d; }
            else if ((fl & Y_SAME_OR_POS) == 0) { v += Sfnt.s16(raw, p); p += 2; }
            ys[i] = v;
        }
        boolean[] on = new boolean[count];
        for (int i = 0; i < count; i++) on[i] = (flags[i] & ON_CURVE) != 0;
        g.endPts = ends; g.x = xs; g.y = ys; g.on = on;
        g.overlap = count > 0 && (flags[0] & OVERLAP_SIMPLE) != 0;
    }

    private static void decodeComposite(byte[] raw, Glyph g) throws Sfnt.FontException {
        g.contours = -1;
        int p = 10;
        boolean more = true, instr = false;
        while (more) {
            if (g.components.size() >= MAX_COMPONENTS) throw new Sfnt.FontException("A glyph of this font has too many components.");
            int fl = Sfnt.u16(raw, p);
            Component k = new Component();
            k.glyph = Sfnt.u16(raw, p + 2);
            p += 4;
            k.xy = (fl & ARGS_ARE_XY) != 0;
            if ((fl & ARG_WORDS) != 0) {
                k.dx = k.xy ? Sfnt.s16(raw, p) : Sfnt.u16(raw, p);
                k.dy = k.xy ? Sfnt.s16(raw, p + 2) : Sfnt.u16(raw, p + 2);
                p += 4;
            } else {
                int a1 = Sfnt.u8(raw, p), a2 = Sfnt.u8(raw, p + 1);
                k.dx = k.xy ? (byte) a1 : a1;
                k.dy = k.xy ? (byte) a2 : a2;
                p += 2;
            }
            if ((fl & HAVE_SCALE) != 0) {
                k.scaleKind = 1; k.a = k.d = Sfnt.s16(raw, p); p += 2;
            } else if ((fl & HAVE_XY_SCALE) != 0) {
                k.scaleKind = 2; k.a = Sfnt.s16(raw, p); k.d = Sfnt.s16(raw, p + 2); p += 4;
            } else if ((fl & HAVE_2X2) != 0) {
                k.scaleKind = 3; k.a = Sfnt.s16(raw, p); k.b = Sfnt.s16(raw, p + 2); k.c = Sfnt.s16(raw, p + 4); k.d = Sfnt.s16(raw, p + 6); p += 8;
            }
            k.flags = fl & (USE_MY_METRICS | ROUND_XY_TO_GRID | OVERLAP_COMPOUND | SCALED_OFFSET | UNSCALED_OFFSET);
            g.components.add(k);
            more = (fl & MORE_COMPONENTS) != 0;
            instr |= (fl & HAVE_INSTRUCTIONS) != 0;
        }
        if (instr && p + 2 <= raw.length) {
            int il = Sfnt.u16(raw, p); p += 2;
            Sfnt.need(raw, p, il);
            g.instructions = java.util.Arrays.copyOfRange(raw, p, p + il);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Encode
    // ---------------------------------------------------------------------------------------------

    /**
     * The glyph in glyf form, padded to 4 bytes. A simple glyph's bounds are computed from its points; a composite's are written as they are
     * set ({@link #write} recomputes them from the finished font). An empty glyph is zero bytes.
     */
    public static byte[] encode(Glyph g) throws Sfnt.FontException {
        if (g.isComposite()) return encodeComposite(g);
        if (g.contours == 0 || g.x.length == 0) return new byte[0];
        return encodeSimple(g);
    }

    private static void checkS16(int v, String what) throws Sfnt.FontException {
        if (v < -32768 || v > 32767) throw new Sfnt.FontException("A glyph's " + what + " (" + v + ") does not fit in a TrueType font.");
    }

    private static byte[] encodeSimple(Glyph g) throws Sfnt.FontException {
        int count = g.x.length;
        if (count > MAX_POINTS) throw new Sfnt.FontException("A glyph has too many points for a TrueType font.");
        if (g.y.length != count || g.on.length != count || g.endPts.length != g.contours || g.endPts[g.contours - 1] != count - 1)
            throw new Sfnt.FontException("A glyph's contours do not match its points.");
        for (int i = 0; i < g.contours; i++)
            if (g.endPts[i] < 0 || (i > 0 && g.endPts[i] <= g.endPts[i - 1])) throw new Sfnt.FontException("A glyph's contours are out of order.");
        g.computeSimpleBounds();
        checkS16(g.xMin, "left edge"); checkS16(g.xMax, "right edge"); checkS16(g.yMin, "bottom"); checkS16(g.yMax, "top");
        Sfnt.Writer w = new Sfnt.Writer(16 + count * 5 + g.instructions.length);
        w.s16(g.contours).s16(g.xMin).s16(g.yMin).s16(g.xMax).s16(g.yMax);
        for (int e : g.endPts) w.u16(e);
        w.u16(g.instructions.length).bytes(g.instructions);
        int[] flags = new int[count];
        Sfnt.Writer xs = new Sfnt.Writer(count * 2), ys = new Sfnt.Writer(count * 2);
        int px = 0, py = 0;
        for (int i = 0; i < count; i++) {
            int fl = g.on[i] ? ON_CURVE : 0;
            if (i == 0 && g.overlap) fl |= OVERLAP_SIMPLE;
            int dx = g.x[i] - px, dy = g.y[i] - py;
            px = g.x[i]; py = g.y[i];
            if (dx == 0) fl |= X_SAME_OR_POS;
            else if (dx >= -255 && dx <= 255) { fl |= X_SHORT | (dx > 0 ? X_SAME_OR_POS : 0); xs.u8(Math.abs(dx)); }
            else { checkS16(dx, "point step"); xs.s16(dx); }
            if (dy == 0) fl |= Y_SAME_OR_POS;
            else if (dy >= -255 && dy <= 255) { fl |= Y_SHORT | (dy > 0 ? Y_SAME_OR_POS : 0); ys.u8(Math.abs(dy)); }
            else { checkS16(dy, "point step"); ys.s16(dy); }
            flags[i] = fl;
        }
        for (int i = 0; i < count; ) {
            int fl = flags[i], rep = 0;
            while (i + 1 + rep < count && flags[i + 1 + rep] == fl && rep < 255) rep++;
            if (rep > 1) { w.u8(fl | REPEAT).u8(rep); i += rep + 1; }
            else { w.u8(fl); i++; }
        }
        w.bytes(xs.toByteArray()).bytes(ys.toByteArray());
        w.align(4);
        return w.toByteArray();
    }

    private static byte[] encodeComposite(Glyph g) throws Sfnt.FontException {
        if (g.components.isEmpty()) return new byte[0];
        if (g.components.size() > MAX_COMPONENTS) throw new Sfnt.FontException("A glyph has too many components.");
        Sfnt.Writer w = new Sfnt.Writer(16 + g.components.size() * 16 + g.instructions.length);
        w.s16(-1).s16(g.xMin).s16(g.yMin).s16(g.xMax).s16(g.yMax);
        for (int i = 0; i < g.components.size(); i++) {
            Component k = g.components.get(i);
            if (k.glyph < 0 || k.glyph > 0xFFFF) throw new Sfnt.FontException("A component names glyph " + k.glyph + ".");
            int fl = k.flags & (USE_MY_METRICS | ROUND_XY_TO_GRID | OVERLAP_COMPOUND | SCALED_OFFSET | UNSCALED_OFFSET);
            if (k.xy) fl |= ARGS_ARE_XY;
            else fl &= ~ROUND_XY_TO_GRID;
            boolean words = k.xy ? (k.dx < -128 || k.dx > 127 || k.dy < -128 || k.dy > 127) : (k.dx > 255 || k.dy > 255);
            if (words) fl |= ARG_WORDS;
            if (k.scaleKind == 1) fl |= HAVE_SCALE;
            else if (k.scaleKind == 2) fl |= HAVE_XY_SCALE;
            else if (k.scaleKind == 3) fl |= HAVE_2X2;
            if (i + 1 < g.components.size()) fl |= MORE_COMPONENTS;
            else if (g.instructions.length > 0) fl |= HAVE_INSTRUCTIONS;
            if (i > 0) fl &= ~OVERLAP_COMPOUND;                    // only the first component carries it
            w.u16(fl).u16(k.glyph);
            if (words) {
                if (k.xy) { checkS16(k.dx, "component offset"); checkS16(k.dy, "component offset"); w.s16(k.dx).s16(k.dy); }
                else {
                    if (k.dx < 0 || k.dx > 0xFFFF || k.dy < 0 || k.dy > 0xFFFF) throw new Sfnt.FontException("A component's anchor point is out of range.");
                    w.u16(k.dx).u16(k.dy);
                }
            } else {
                if (!k.xy && (k.dx < 0 || k.dy < 0)) throw new Sfnt.FontException("A component's anchor point is out of range.");
                w.u8(k.dx).u8(k.dy);
            }
            if (k.scaleKind == 1) w.s16(k.a);
            else if (k.scaleKind == 2) w.s16(k.a).s16(k.d);
            else if (k.scaleKind == 3) w.s16(k.a).s16(k.b).s16(k.c).s16(k.d);
        }
        if (g.instructions.length > 0) w.u16(g.instructions.length).bytes(g.instructions);
        w.align(4);
        return w.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------
    // Flattening (composite bounds, point matching)
    // ---------------------------------------------------------------------------------------------

    /** Points of a glyph with its components resolved: x, y as doubles plus the contour ends. */
    public static final class Outline {
        public double[] x = new double[0], y = new double[0];
        public boolean[] on = new boolean[0];
        public int[] endPts = new int[0];
    }

    /** The glyph's outline with every component placed (recursively), in font units. */
    public static Outline flatten(byte[][] glyphs, int gid) throws Sfnt.FontException {
        return flatten(glyphs, gid, 0);
    }

    private static Outline flatten(byte[][] glyphs, int gid, int depth) throws Sfnt.FontException {
        if (depth > MAX_DEPTH) throw new Sfnt.FontException("A composite glyph of this font nests too deep (or names itself).");
        if (gid < 0 || gid >= glyphs.length) throw new Sfnt.FontException("A composite glyph names glyph " + gid + ", which this font does not have.");
        Glyph g = decode(glyphs[gid]);
        Outline o = new Outline();
        if (!g.isComposite()) {
            int n = g.x.length;
            o.x = new double[n]; o.y = new double[n]; o.on = g.on.clone(); o.endPts = g.endPts.clone();
            for (int i = 0; i < n; i++) { o.x[i] = g.x[i]; o.y[i] = g.y[i]; }
            return o;
        }
        List<double[]> xs = new ArrayList<double[]>(), ys = new ArrayList<double[]>();
        List<boolean[]> ons = new ArrayList<boolean[]>();
        List<int[]> ends = new ArrayList<int[]>();
        int total = 0;
        double[] allX = new double[0], allY = new double[0];
        for (Component k : g.components) {
            Outline c = flatten(glyphs, k.glyph, depth + 1);
            double a = k.a / 16384.0, b = k.b / 16384.0, cc = k.c / 16384.0, d = k.d / 16384.0;
            int n = c.x.length;
            double[] tx = new double[n], ty = new double[n];
            for (int i = 0; i < n; i++) {
                tx[i] = a * c.x[i] + cc * c.y[i];
                ty[i] = b * c.x[i] + d * c.y[i];
            }
            double ox, oy;
            if (k.xy) {
                ox = k.dx; oy = k.dy;
                if ((k.flags & SCALED_OFFSET) != 0 && (k.flags & UNSCALED_OFFSET) == 0 && k.scaled()) {
                    ox = a * k.dx + cc * k.dy;
                    oy = b * k.dx + d * k.dy;
                }
            } else {
                // Point matching: the parent's point dx (so far) lands on the component's point dy.
                if (k.dx >= total || k.dy >= n) throw new Sfnt.FontException("A composite glyph of this font anchors to a point it does not have.");
                ox = allX[k.dx] - tx[k.dy];
                oy = allY[k.dx] - ty[k.dy];
            }
            for (int i = 0; i < n; i++) { tx[i] += ox; ty[i] += oy; }
            xs.add(tx); ys.add(ty); ons.add(c.on);
            int[] e = c.endPts.clone();
            for (int i = 0; i < e.length; i++) e[i] += total;
            ends.add(e);
            total += n;
            if (total > MAX_POINTS * 4) throw new Sfnt.FontException("A composite glyph of this font has too many points.");
            double[] nx = new double[total], ny = new double[total];
            System.arraycopy(allX, 0, nx, 0, allX.length); System.arraycopy(tx, 0, nx, allX.length, n);
            System.arraycopy(allY, 0, ny, 0, allY.length); System.arraycopy(ty, 0, ny, allY.length, n);
            allX = nx; allY = ny;
        }
        o.x = allX; o.y = allY;
        o.on = new boolean[total];
        int at = 0;
        for (boolean[] b : ons) { System.arraycopy(b, 0, o.on, at, b.length); at += b.length; }
        int ne = 0;
        for (int[] e : ends) ne += e.length;
        o.endPts = new int[ne];
        at = 0;
        for (int[] e : ends) { System.arraycopy(e, 0, o.endPts, at, e.length); at += e.length; }
        return o;
    }

    /** The stored bounds of an encoded glyph (xMin, yMin, xMax, yMax), or null for an empty one. */
    public static int[] headerBounds(byte[] raw) throws Sfnt.FontException {
        if (raw == null || raw.length < 10) return null;
        return new int[] { Sfnt.s16(raw, 2), Sfnt.s16(raw, 4), Sfnt.s16(raw, 6), Sfnt.s16(raw, 8) };
    }

    // ---------------------------------------------------------------------------------------------
    // Write glyf + loca
    // ---------------------------------------------------------------------------------------------

    /**
     * Replaces glyf and loca with these glyphs (one encoded glyph per glyph id) and updates head (bounds, loca format) and maxp (glyph count,
     * point / contour / component sizes, the longest instructions). The bounds of each composite glyph are recomputed from its components
     * first, in place in {@code glyphs}. hmtx is the caller's: see {@link Metrics}.
     */
    public static void write(Sfnt f, byte[][] glyphs) throws Sfnt.FontException {
        if (glyphs.length < 1 || glyphs.length > 0xFFFF) throw new Sfnt.FontException("A font holds 1 to 65,535 glyphs; this one would have " + glyphs.length + ".");
        int maxPoints = 0, maxContours = 0, maxCPoints = 0, maxCContours = 0, maxElements = 0, maxDepth = 0, maxInstr = 0;
        for (int i = 0; i < glyphs.length; i++) {
            byte[] raw = glyphs[i];
            if (raw == null) { glyphs[i] = raw = new byte[0]; }
            if (raw.length < 10) continue;
            int n = Sfnt.s16(raw, 0);
            if (n >= 0) {
                Glyph g = decode(raw);
                maxPoints = Math.max(maxPoints, g.x.length);
                maxContours = Math.max(maxContours, n);
                maxInstr = Math.max(maxInstr, g.instructions.length);
                continue;
            }
            Glyph g = decode(raw);
            Outline o = flatten(glyphs, i);
            int[] bb = boundsOf(o);
            if (bb == null) bb = new int[4];
            for (int v : bb) checkS16(v, "edge");
            Sfnt.w16(raw, 2, bb[0]); Sfnt.w16(raw, 4, bb[1]); Sfnt.w16(raw, 6, bb[2]); Sfnt.w16(raw, 8, bb[3]);
            maxCPoints = Math.max(maxCPoints, o.x.length);
            maxCContours = Math.max(maxCContours, o.endPts.length);
            maxElements = Math.max(maxElements, g.components.size());
            maxDepth = Math.max(maxDepth, depthOf(glyphs, i, 0));
            maxInstr = Math.max(maxInstr, g.instructions.length);
        }
        long total = 0;
        for (byte[] raw : glyphs) total += (raw.length + 3) & ~3;
        if (total > Integer.MAX_VALUE - 8) throw new Sfnt.FontException("too_big", "The glyph outlines would be too big for one font.");
        boolean longFmt = total > 0x1FFFE;
        Sfnt.Writer gw = new Sfnt.Writer((int) Math.max(16, total));
        Sfnt.Writer lw = new Sfnt.Writer((glyphs.length + 1) * (longFmt ? 4 : 2));
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (byte[] raw : glyphs) {
            if (longFmt) lw.u32(gw.size()); else lw.u16(gw.size() / 2);
            gw.bytes(raw).align(4);
            int[] bb = headerBounds(raw);
            if (bb != null && !(bb[0] == 0 && bb[1] == 0 && bb[2] == 0 && bb[3] == 0 && Sfnt.s16(raw, 0) == 0)) {
                x0 = Math.min(x0, bb[0]); y0 = Math.min(y0, bb[1]); x1 = Math.max(x1, bb[2]); y1 = Math.max(y1, bb[3]);
            }
        }
        if (longFmt) lw.u32(gw.size()); else lw.u16(gw.size() / 2);
        f.put("glyf", gw.toByteArray());
        f.put("loca", lw.toByteArray());
        byte[] head = f.table("head").clone();
        if (x0 == Integer.MAX_VALUE) { x0 = y0 = x1 = y1 = 0; }
        Sfnt.w16(head, 36, x0); Sfnt.w16(head, 38, y0); Sfnt.w16(head, 40, x1); Sfnt.w16(head, 42, y1);
        Sfnt.w16(head, 50, longFmt ? 1 : 0);
        Sfnt.w16(head, 52, 0);                                     // glyphDataFormat
        f.put("head", head);
        byte[] old = f.table("maxp");
        byte[] maxp = new byte[32];
        if (old != null && old.length >= 32 && Sfnt.u32(old, 0) == 0x00010000L) System.arraycopy(old, 0, maxp, 0, 32);
        else {
            Sfnt.w16(maxp, 14, 2);                                 // maxZones: 2 (with the twilight zone), as for a font without hints
        }
        Sfnt.w32(maxp, 0, 0x00010000);
        Sfnt.w16(maxp, 4, glyphs.length);
        Sfnt.w16(maxp, 6, maxPoints);
        Sfnt.w16(maxp, 8, maxContours);
        Sfnt.w16(maxp, 10, Math.min(0xFFFF, maxCPoints));
        Sfnt.w16(maxp, 12, Math.min(0xFFFF, maxCContours));
        if (Sfnt.u16(maxp, 14) == 0) Sfnt.w16(maxp, 14, 2);
        Sfnt.w16(maxp, 26, maxInstr);
        Sfnt.w16(maxp, 28, maxElements);
        Sfnt.w16(maxp, 30, maxDepth);
        f.put("maxp", maxp);
    }

    private static int depthOf(byte[][] glyphs, int gid, int depth) throws Sfnt.FontException {
        if (depth > MAX_DEPTH) throw new Sfnt.FontException("A composite glyph of this font nests too deep (or names itself).");
        byte[] raw = glyphs[gid];
        if (raw.length < 10 || Sfnt.s16(raw, 0) >= 0) return 0;
        int best = 0;
        for (Component k : decode(raw).components) {
            if (k.glyph >= glyphs.length) throw new Sfnt.FontException("A composite glyph names glyph " + k.glyph + ", which this font does not have.");
            best = Math.max(best, depthOf(glyphs, k.glyph, depth + 1));
        }
        return best + 1;
    }

    /** Bounds of an outline, each edge rounded, or null when it has no points. */
    public static int[] boundsOf(Outline o) {
        if (o.x.length == 0) return null;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int i = 0; i < o.x.length; i++) {
            x0 = Math.min(x0, o.x[i]); x1 = Math.max(x1, o.x[i]);
            y0 = Math.min(y0, o.y[i]); y1 = Math.max(y1, o.y[i]);
        }
        return new int[] { round(x0), round(y0), round(x1), round(y1) };            // as fontTools' calcIntBounds does
    }

    /** The rounding fontTools uses for coordinates (otRound: half rounds up, so -1.5 becomes -1), so results match it point for point. */
    public static int round(double v) { return (int) Math.floor(v + 0.5); }

    // ---------------------------------------------------------------------------------------------
    // hmtx / hhea
    // ---------------------------------------------------------------------------------------------

    /** Horizontal metrics: an advance width and a left side bearing per glyph. */
    public static final class Metrics {
        public int[] advance, lsb;

        public Metrics(int n) { advance = new int[n]; lsb = new int[n]; }

        public static Metrics read(Sfnt f) throws Sfnt.FontException {
            int n = f.numGlyphs();
            byte[] hhea = f.table("hhea"), hmtx = f.table("hmtx");
            int nh = Sfnt.u16(hhea, 34);
            if (nh < 1) throw new Sfnt.FontException("This font's hhea table says it has no horizontal metrics.");
            if (nh > n) nh = n;
            Metrics m = new Metrics(n);
            if (hmtx.length < 4L * nh) throw new Sfnt.FontException("This font's hmtx table is cut off.");
            for (int i = 0; i < nh; i++) { m.advance[i] = Sfnt.u16(hmtx, 4 * i); m.lsb[i] = Sfnt.s16(hmtx, 4 * i + 2); }
            int base = 4 * nh;
            for (int i = nh; i < n; i++) {
                m.advance[i] = m.advance[nh - 1];
                int at = base + 2 * (i - nh);
                m.lsb[i] = at + 2 <= hmtx.length ? Sfnt.s16(hmtx, at) : 0;      // a short table: the missing bearings are 0
            }
            return m;
        }

        /**
         * Writes hmtx (trailing equal advances folded) and the hhea fields that follow from it: advanceWidthMax, the minimum bearings, the
         * largest extent and numberOfHMetrics. {@code glyphs} (encoded, same order) gives each glyph's bounds; pass null to keep hhea's.
         */
        public void write(Sfnt f, byte[][] glyphs) throws Sfnt.FontException {
            int n = advance.length;
            int nh = n;
            while (nh > 1 && advance[nh - 1] == advance[nh - 2]) nh--;
            Sfnt.Writer w = new Sfnt.Writer(4 * n);
            for (int i = 0; i < nh; i++) {
                if (advance[i] < 0 || advance[i] > 0xFFFF) throw new Sfnt.FontException("A glyph's width (" + advance[i] + ") does not fit in a font.");
                checkS16(lsb[i], "left side bearing");
                w.u16(advance[i]).s16(lsb[i]);
            }
            for (int i = nh; i < n; i++) { checkS16(lsb[i], "left side bearing"); w.s16(lsb[i]); }
            f.put("hmtx", w.toByteArray());
            byte[] hhea = f.table("hhea").clone();
            int maxAdv = 0;
            for (int a : advance) maxAdv = Math.max(maxAdv, a);
            Sfnt.w16(hhea, 10, maxAdv);
            if (glyphs != null) {
                int minL = Integer.MAX_VALUE, minR = Integer.MAX_VALUE, maxExt = Integer.MIN_VALUE;
                for (int i = 0; i < n && i < glyphs.length; i++) {
                    int[] bb = headerBounds(glyphs[i]);
                    if (bb == null) continue;
                    int width = bb[2] - bb[0];
                    minL = Math.min(minL, lsb[i]);
                    minR = Math.min(minR, advance[i] - lsb[i] - width);
                    maxExt = Math.max(maxExt, lsb[i] + width);
                }
                if (minL != Integer.MAX_VALUE) {
                    Sfnt.w16(hhea, 12, clamp16(minL));
                    Sfnt.w16(hhea, 14, clamp16(minR));
                    Sfnt.w16(hhea, 16, clamp16(maxExt));
                }
            }
            Sfnt.w16(hhea, 34, nh);
            f.put("hhea", hhea);
        }
    }

    static int clamp16(int v) { return Math.max(-32768, Math.min(32767, v)); }
}
