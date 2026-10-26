package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A static instance of a variable font: every outline, metric and layout value worked out at one location, and the variation tables removed.
 *
 * <p>A FlipFont package holds static TrueType fonts, so when the user picks a variable font for the Custom Fonts tab's Regular or Bold, the
 * page offers its named instances (or a value per axis) and {@link FontBuilder} calls {@link #instance} once per face (the same file can give
 * both). The location helpers ({@link #userCoords}, {@link #guessRegular}, {@link #guessBold}) turn what the page sends into coordinates.
 * The result follows fontTools' {@code instancer.instantiateVariableFont} for a full instance: glyf points and component offsets move by
 * their gvar deltas (inferred deltas for the points a tuple does not list, summed in the same order and rounded once, so the points match
 * fontTools'), advances and bearings come from the phantom points, MVAR moves the OS/2, hhea and post values, GPOS and GDEF get their
 * deltas and GSUB / GPOS their FeatureVariations ({@link OtLayout}), and OS/2 takes the weight and width classes of the location. A CFF2
 * font is converted at the location ({@link CffToGlyf}) and gets its advances from HVAR. Hinting is dropped unless asked for (variable
 * fonts' instructions often expect the variation tables), in which case cvar is applied to the cvt. The location is normalized as the spec
 * and HarfBuzz do it ({@link #normalize}, avar 2 included), so the static font is the variable font as HarfBuzz lays it out there. The input
 * is untrusted: every read is bounds-checked, the work is capped, and every failure ends in a {@link Sfnt.FontException} whose message can be
 * shown. Pure Java.
 */
public final class FontInstancer {

    private FontInstancer() {}

    /** The progress stage name ({@link CffToGlyf} reports "convert" and {@link OtLayout} "layout" on the way). */
    public static final String STAGE = "instance";
    /** Points touched by all the tuple variations a font applies; a 65,535-glyph CJK variable font touches about 20 million. Not final: the test lowers it. */
    static long maxTupleWork = 1L << 27;
    /** Tables that only make sense in a variable font, or that describe the default instance only (device metrics). */
    static final String[] DROPPED = { "fvar", "avar", "gvar", "HVAR", "VVAR", "MVAR", "STAT", "cvar", "vhea", "vmtx", "VORG", "DSIG", "hdmx",
            "LTSH", "VDMX", "CFF ", "CFF2" };
    /** Hinting tables, dropped with the glyph instructions unless {@link Options#keepHinting}. */
    static final String[] HINTING = { "fpgm", "prep", "cvt " };

    public static final String W_GPOS_SKIPPED = "A few kerning or mark positions of this font could not take their variation at this location.";
    public static final String W_COLOR_VARIES = "This font's colour glyphs change with its axes; the static font keeps them as they are at the default location.";

    /** What to do besides the instance itself, and what came of it. */
    public static final class Options {
        /** Keep the TrueType instructions (and apply cvar to the cvt); by default they are dropped with fpgm, prep and cvt. */
        public boolean keepHinting;
        /** Mark every glyph as one whose contours may overlap, as fontTools does for a static instance (renderers then smooth the overlaps). */
        public boolean overlapFlags = true;
        /** May be null. */
        public FontProgress progress;
        /** Sentences for the page about what the instance could not keep; filled by {@link #instance}. */
        public final List<String> warnings = new ArrayList<String>();
    }

    // ---------------------------------------------------------------------------------------------
    // Locations
    // ---------------------------------------------------------------------------------------------

    /** The default value of every axis, in fvar order. */
    public static double[] defaults(VarStore.Fvar fv) {
        double[] out = new double[fv.axes.size()];
        for (int i = 0; i < out.length; i++) out[i] = fv.axes.get(i).def;
        return out;
    }

    /** A full location from {@code user} (fvar order): missing, NaN or infinite values become the axis default, the rest are clamped to the axis. */
    public static double[] complete(VarStore.Fvar fv, double[] user) {
        double[] out = defaults(fv);
        for (int i = 0; i < out.length && user != null && i < user.length; i++) {
            VarStore.Axis a = fv.axes.get(i);
            double v = user[i];
            if (!Double.isNaN(v) && !Double.isInfinite(v)) out[i] = Math.max(a.min, Math.min(a.max, v));
        }
        return out;
    }

    /** The location of named instance {@code index} (fvar order), clamped to the axes. */
    public static double[] userCoords(VarStore.Fvar fv, int index) throws Sfnt.FontException {
        if (index < 0 || index >= fv.instances.size())
            throw new Sfnt.FontException("no_instance", "This font has no style number " + (index + 1) + ".");
        return complete(fv, fv.instances.get(index).coords);
    }

    /** A location from axis tag -> value ("wght" -> 700): tags the font does not have are ignored, axes not named stay at their default. */
    public static double[] userCoords(VarStore.Fvar fv, Map<String, ? extends Number> values) {
        double[] out = defaults(fv);
        if (values == null) return out;
        for (Map.Entry<String, ? extends Number> e : values.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            int i = fv.axisIndex(e.getKey());
            if (i < 0) i = axisIndexTrimmed(fv, e.getKey());          // "ital" sent for an axis stored as "ital", or a tag padded with spaces
            if (i >= 0) out[i] = e.getValue().doubleValue();
        }
        return complete(fv, out);
    }

    private static int axisIndexTrimmed(VarStore.Fvar fv, String tag) {
        for (int i = 0; i < fv.axes.size(); i++) if (fv.axes.get(i).tag.trim().equals(tag.trim())) return i;
        return -1;
    }

    /** The location as tag -> value in fvar order, for names and the page. */
    public static LinkedHashMap<String, Double> location(VarStore.Fvar fv, double[] user) {
        double[] c = complete(fv, user);
        LinkedHashMap<String, Double> out = new LinkedHashMap<String, Double>();
        for (int i = 0; i < c.length; i++) out.put(fv.axes.get(i).tag, c[i]);
        return out;
    }

    /**
     * Where to put Regular: the named instance whose weight is closest to 400, unless the weight axis itself gets closer (wght clamped to its
     * range, the other axes at their defaults). The same choice the page preselects ({@link FontInspect.Info#guessRegular}).
     */
    public static double[] guessRegular(VarStore.Fvar fv) {
        return userCoords(fv, FontInspect.guess(fv, 400));
    }

    /** Where to put Bold, as {@link #guessRegular} for 700; null when the font has no weight axis (an instance of it would not be bolder). */
    public static double[] guessBold(VarStore.Fvar fv) {
        return fv.axisIndex("wght") < 0 ? null : userCoords(fv, FontInspect.guess(fv, 700));
    }

    /**
     * The normalized coordinates (F2Dot14 per fvar axis) the variation tables are read at, worked out as the OpenType spec says and in
     * single precision as HarfBuzz does, so they are HarfBuzz's to the unit: default normalization to 16.16, avar's segment maps, avar
     * version 2's cross-axis deltas, then one rounding to F2Dot14. ({@link VarStore#normalize} rounds to F2Dot14 before avar, which puts
     * it 1 apart now and then; fontTools 4.67 maps the unrounded value, which does too.)
     */
    public static int[] normalize(Sfnt vf, VarStore.Fvar fv, double[] user) throws Sfnt.FontException {
        double[] u = complete(fv, user);
        int n = u.length;
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            VarStore.Axis a = fv.axes.get(i);
            float min = (float) a.min, def = (float) a.def, max = (float) a.max, v = Math.max(min, Math.min(max, (float) u[i]));
            c[i] = roundf((v < def ? (v - def) / (def - min) : v > def ? (v - def) / (max - def) : 0) * 65536f);
        }
        byte[] avar = vf.table("avar");
        int major = avar == null || avar.length < 8 ? 0 : Sfnt.u16(avar, 0);
        if (major != 1 && major != 2) return to2dot14(c);
        int count = Sfnt.u16(avar, 6);
        long p = 8;
        for (int i = 0; i < count; i++) {
            Sfnt.need(avar, p, 2);
            int maps = Sfnt.u16(avar, (int) p);
            Sfnt.need(avar, p + 2, 4L * maps);
            if (i < n) c[i] = roundf(mapAxis(avar, (int) p + 2, maps, c[i] / 65536f) * 65536f);
            p += 2 + 4L * maps;
        }
        if (major != 2) return to2dot14(c);
        Sfnt.need(avar, p, 8);
        long mapOff = Sfnt.u32(avar, (int) p), storeOff = Sfnt.u32(avar, (int) p + 4);
        if (storeOff == 0) return to2dot14(c);
        if (storeOff + 8 > avar.length || mapOff + 4 > avar.length) throw new Sfnt.FontException("This variable font's axis mapping (avar) is damaged.");
        int[] at = new int[n];
        for (int i = 0; i < n; i++) at[i] = roundf(c[i] / 4f);         // the store is read at F2Dot14
        VarStore store = new VarStore(avar, (int) storeOff).at(at);
        long[] map = mapOff == 0 ? null : VarStore.readIndexMap(avar, (int) mapOff);
        for (int i = 0; i < n; i++) {
            long idx = map == null || map.length == 0 ? i : map[Math.min(i, map.length - 1)];    // no map: item i of the first set
            float d = Math.max(-131072f, Math.min(131072f, (float) store.deltaFor(idx) * 4));
            c[i] = Math.max(-65536, Math.min(65536, c[i] + roundf(d)));
        }
        return to2dot14(c);
    }

    /** One axis's avar segment map at v (1.0 = 1), with HarfBuzz's reading of maps that are short or repeat a point. */
    static float mapAxis(byte[] avar, int p, int maps, float v) throws Sfnt.FontException {
        if (maps == 0) return v;
        if (maps == 1) return v - from(avar, p, 0) + to(avar, p, 0);
        int start = 0, end = maps;
        if (from(avar, p, 0) == -1 && to(avar, p, 0) == -1 && from(avar, p, 1) == -1) start++;
        if (from(avar, p, end - 1) == 1 && to(avar, p, end - 1) == 1 && from(avar, p, end - 2) == 1) end--;
        int i = start;
        while (i < end && v != from(avar, p, i)) i++;
        if (i < end) {                                          // v is a from value: of one map, or of several in a row
            int j = i;
            while (j + 1 < end && v == from(avar, p, j + 1)) j++;
            if (i == j) return to(avar, p, i);
            if (i + 2 == j) return to(avar, p, i + 1);
            if (v != 0) return to(avar, p, v < 0 ? j : i);
            return Math.abs(to(avar, p, i)) < Math.abs(to(avar, p, j)) ? to(avar, p, i) : to(avar, p, j);
        }
        i = start;
        while (i < end && v >= from(avar, p, i)) i++;
        if (i == start) return v - from(avar, p, start) + to(avar, p, start);
        if (i == end) return v - from(avar, p, end - 1) + to(avar, p, end - 1);
        float f0 = from(avar, p, i - 1), t0 = to(avar, p, i - 1);
        return t0 + ((to(avar, p, i) - t0) * (v - f0)) / (from(avar, p, i) - f0);
    }

    private static float from(byte[] avar, int p, int k) throws Sfnt.FontException { return Sfnt.s16(avar, p + 4 * k) / 16384f; }

    private static float to(byte[] avar, int p, int k) throws Sfnt.FontException { return Sfnt.s16(avar, p + 4 * k + 2) / 16384f; }

    /** C's roundf: to the nearest integer, halves away from zero (NaN: 0). */
    static int roundf(float v) {
        return v != v ? 0 : (int) Math.copySign(Math.floor(Math.abs((double) v) + 0.5), v);
    }

    /** 16.16 values as F2Dot14, rounded as the spec says (add 2, shift right by 2) and clamped to -1..1. */
    private static int[] to2dot14(int[] c) {
        for (int i = 0; i < c.length; i++) c[i] = Math.max(-16384, Math.min(16384, (c[i] + 2) >> 2));
        return c;
    }

    // ---------------------------------------------------------------------------------------------
    // The instance
    // ---------------------------------------------------------------------------------------------

    /** {@link #instance(Sfnt, double[], Options)} at a location given as tag -> value (see {@link #userCoords(VarStore.Fvar, Map)}). */
    public static Sfnt instance(Sfnt vf, Map<String, ? extends Number> location, Options opt) throws Sfnt.FontException {
        if (!vf.has("fvar")) throw notVariable();
        return instance(vf, userCoords(VarStore.Fvar.read(vf), location), opt);
    }

    /**
     * The static font at {@code userCoords} (user values in fvar axis order; null or a short array means the defaults, values outside an axis
     * are clamped to it). The result is a new TrueType font that shares no table with {@code vf}, which is left as it was. Codes:
     * "not_variable", "not_supported" (variable components), "too_complex" (more variation work than any real font needs), "cancelled",
     * and "bad_font" for damaged variation data.
     */
    public static Sfnt instance(Sfnt vf, double[] userCoords, Options opt) throws Sfnt.FontException {
        if (opt == null) opt = new Options();
        FontProgress p = opt.progress == null ? FontProgress.NONE : opt.progress;
        if (!vf.has("fvar")) throw notVariable();
        if (vf.has("VARC"))
            throw new Sfnt.FontException("not_supported", "This variable font builds its glyphs from variable components (VARC), which the app cannot make a static font of.");
        try {
            VarStore.Fvar fv = VarStore.Fvar.read(vf);
            double[] user = complete(fv, userCoords);
            int[] coords = normalize(vf, fv, user);
            p.step(STAGE, 0, vf.numGlyphs());
            FontProgress.Check.cancel(p);

            Sfnt out;
            if (vf.has("glyf")) {
                out = new Sfnt(Sfnt.TRUETYPE);
                for (String tag : vf.tags()) out.put(tag, vf.table(tag));     // shared until changed (see own()) and copied at the end
                outlines(vf, out, fv, coords, opt, p);
            } else {
                out = CffToGlyf.convert(vf, coords, p);                         // glyf at the location; advances still the default's
                hvarAdvances(out, coords, p);
                if (opt.overlapFlags) overlapFlags(out);
            }
            if (out.has("MVAR")) mvar(vf, out, coords);
            layout(vf, out, coords, opt, p);
            hinting(vf, out, fv, coords, opt);
            styleValues(vf, out, fv, user);
            byte[] colr = out.table("COLR");
            if (colr != null && colr.length >= 34 && Sfnt.u16(colr, 0) >= 1 && Sfnt.u32(colr, 30) != 0) opt.warnings.add(W_COLOR_VARIES);
            for (String tag : DROPPED) out.remove(tag);
            if (!opt.keepHinting) for (String tag : HINTING) out.remove(tag);
            out.sfntVersion = Sfnt.TRUETYPE;
            for (String tag : out.tags()) if (out.table(tag) == vf.table(tag)) out.put(tag, vf.table(tag).clone());
            p.step(STAGE, vf.numGlyphs(), vf.numGlyphs());
            return out;
        } catch (RuntimeException e) {
            // A check this class missed: still a refused font for the app, never a crash (the fuzz test fails on any such cause).
            Sfnt.FontException fe = new Sfnt.FontException("This variable font could not be made static.");
            fe.initCause(e);
            throw fe;
        }
    }

    private static Sfnt.FontException notVariable() {
        return new Sfnt.FontException("not_variable", "This is not a variable font, so it has no styles to choose from.");
    }

    private static Sfnt.FontException damaged(String what) {
        return new Sfnt.FontException("This variable font's " + what + " is damaged.");
    }

    /** The table {@code tag} of {@code out}, copied first when it is still the source font's array, so it can be changed in place. */
    private static byte[] own(Sfnt out, Sfnt vf, String tag) {
        byte[] t = out.table(tag);
        if (t != null && t == vf.table(tag)) {
            t = t.clone();
            out.put(tag, t);
        }
        return t;
    }

    // ---------------------------------------------------------------------------------------------
    // glyf + gvar
    // ---------------------------------------------------------------------------------------------

    /**
     * Moves every glyph's points (or component offsets) by its gvar deltas and sets each advance and left side bearing from the phantom
     * points, as fontTools' instancer does: advance = round(right - left phantom), bearing = round(xMin - left phantom), where xMin is the
     * instanced glyph's (for a composite, of its instanced components). Without gvar the outlines stay and HVAR gives the advances.
     */
    private static void outlines(Sfnt vf, Sfnt out, VarStore.Fvar fv, int[] coords, Options opt, FontProgress p) throws Sfnt.FontException {
        byte[][] glyphs = Glyf.readAll(vf);
        Glyf.Metrics m = Glyf.Metrics.read(vf);
        int n = glyphs.length;
        Tuples gvar = vf.has("gvar") ? Tuples.gvar(vf.table("gvar"), fv, n, coords) : null;
        double[] left = new double[n];
        long[] work = { 0 };
        for (int g = 0; g < n; g++) {
            if ((g & 0xFF) == 0) {
                p.step(STAGE, g, n);
                FontProgress.Check.cancel(p);
            }
            Glyf.Glyph gl = Glyf.decode(glyphs[g]);
            boolean comp = gl.isComposite();
            int np = comp ? gl.components.size() : gl.x.length;
            // The points gvar indexes: the outline's (one per component for a composite), then the four phantom points.
            int[] x = new int[np + 4], y = new int[np + 4];
            int[] ends;
            if (comp) {
                ends = new int[np + 4];
                for (int i = 0; i < np; i++) {
                    Glyf.Component k = gl.components.get(i);
                    if (k.xy) { x[i] = k.dx; y[i] = k.dy; }                    // a point-matched component has no offset to move (0, 0)
                    ends[i] = i;
                }
            } else {
                System.arraycopy(gl.x, 0, x, 0, np);
                System.arraycopy(gl.y, 0, y, 0, np);
                ends = new int[gl.endPts.length + 4];
                System.arraycopy(gl.endPts, 0, ends, 0, gl.endPts.length);
            }
            for (int k = 0; k < 4; k++) ends[ends.length - 4 + k] = np + k;
            int pp1 = gl.xMin - m.lsb[g];                                      // the stored xMin (0 for an empty glyph), as fontTools reads it
            x[np] = pp1;
            x[np + 1] = pp1 + m.advance[g];                                    // the vertical phantoms stay (0, 0): vmtx is dropped
            double[] dx = new double[np + 4], dy = new double[np + 4];
            if (gvar != null && g < gvar.glyphCount) gvar.glyph(g, x, y, ends, dx, dy, work, p);
            if (comp) {
                for (int i = 0; i < np; i++) {
                    Glyf.Component k = gl.components.get(i);
                    if (k.xy) { k.dx = Glyf.round(x[i] + dx[i]); k.dy = Glyf.round(y[i] + dy[i]); }
                }
                if (opt.overlapFlags && np > 0) gl.components.get(0).flags |= Glyf.OVERLAP_COMPOUND;
            } else if (np > 0) {
                for (int i = 0; i < np; i++) { gl.x[i] = Glyf.round(x[i] + dx[i]); gl.y[i] = Glyf.round(y[i] + dy[i]); }
                if (opt.overlapFlags) gl.overlap = true;
            }
            left[g] = x[np] + dx[np];
            if (gvar != null) m.advance[g] = Math.max(0, Glyf.round((x[np + 1] + dx[np + 1]) - left[g]));
            if (!opt.keepHinting) gl.instructions = new byte[0];
            glyphs[g] = Glyf.encode(gl);
        }
        Glyf.write(out, glyphs);                                               // also gives each composite the bounds of its instanced parts
        if (gvar != null) {
            for (int g = 0; g < n; g++) {
                // The bearing of the stored bounds (those of the rounded outline), so left phantom point = xMin - lsb stays where gvar put it.
                // fontTools takes a composite's bounds and bearing from its offsets before rounding: at most 1 unit apart.
                int[] bb = Glyf.headerBounds(glyphs[g]);
                m.lsb[g] = Glyf.round((bb == null ? 0 : bb[0]) - left[g]);
            }
        } else {
            hvarApply(out, vf.table("HVAR"), coords, m, p);                     // a glyf font without gvar: only HVAR moves the advances
        }
        m.write(out, glyphs);
    }

    /**
     * The tuple variations of gvar (one set per glyph) or cvar (one set for the cvt), applied the way fontTools' instancer applies them for a
     * full instance: each explicit delta is scaled by the region's scalar axis by axis in tag order, a gvar tuple's missing points are
     * inferred from their contour neighbours, and the tuples are summed in order, so the rounded result is the same as fontTools'.
     */
    static final class Tuples {
        final byte[] t;
        final int axisCount, sharedCount, sharedAt;
        final int glyphCount;
        final boolean longOffsets;
        final long offsetsAt, dataAt;
        final int[] coords;
        final int[] order;                              // axis indexes sorted by tag, the order fontTools scales in

        private Tuples(byte[] t, int axisCount, int sharedCount, int sharedAt, int glyphCount, boolean longOffsets, long offsetsAt, long dataAt,
                int[] coords, VarStore.Fvar fv) {
            this.t = t; this.axisCount = axisCount; this.sharedCount = sharedCount; this.sharedAt = sharedAt;
            this.glyphCount = glyphCount; this.longOffsets = longOffsets; this.offsetsAt = offsetsAt; this.dataAt = dataAt;
            this.coords = coords;
            Integer[] o = new Integer[axisCount];
            for (int i = 0; i < axisCount; i++) o[i] = i;
            final VarStore.Fvar f = fv;
            java.util.Arrays.sort(o, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) { return f.axes.get(a).tag.compareTo(f.axes.get(b).tag); }
            });
            order = new int[axisCount];
            for (int i = 0; i < axisCount; i++) order[i] = o[i];
        }

        static Tuples gvar(byte[] t, VarStore.Fvar fv, int numGlyphs, int[] coords) throws Sfnt.FontException {
            if (t.length < 20) throw damaged("glyph variation table (gvar)");
            if (Sfnt.u16(t, 0) != 1) throw new Sfnt.FontException("This variable font's glyph variations (gvar) have a version the app cannot read.");
            int axisCount = Sfnt.u16(t, 4), sharedCount = Sfnt.u16(t, 6);
            long sharedAt = Sfnt.u32(t, 8);
            int glyphCount = Sfnt.u16(t, 12), flags = Sfnt.u16(t, 14);
            long dataAt = Sfnt.u32(t, 16);
            if (axisCount != fv.axes.size()) throw damaged("glyph variation table (gvar)");
            boolean longOffsets = (flags & 1) != 0;
            Sfnt.need(t, 20, (glyphCount + 1L) * (longOffsets ? 4 : 2));
            Sfnt.need(t, sharedAt, 2L * sharedCount * axisCount);
            if (dataAt > t.length) throw damaged("glyph variation table (gvar)");
            return new Tuples(t, axisCount, sharedCount, (int) sharedAt, Math.min(glyphCount, numGlyphs), longOffsets, 20, dataAt, coords, fv);
        }

        static Tuples cvar(byte[] t, VarStore.Fvar fv, int[] coords) throws Sfnt.FontException {
            if (t.length < 8) throw damaged("hinting variation table (cvar)");
            if (Sfnt.u16(t, 0) != 1) throw new Sfnt.FontException("This variable font's hinting variations (cvar) have a version the app cannot read.");
            return new Tuples(t, fv.axes.size(), 0, 0, 0, false, 0, 0, coords, fv);
        }

        private long offset(int g) throws Sfnt.FontException {
            return longOffsets ? Sfnt.u32(t, (int) offsetsAt + 4 * g) : 2L * Sfnt.u16(t, (int) offsetsAt + 2 * g);
        }

        /** Adds glyph {@code g}'s deltas at the location into dx / dy (points x / y with the phantoms, contours ending at {@code ends}). */
        void glyph(int g, int[] x, int[] y, int[] ends, double[] dx, double[] dy, long[] work, FontProgress p) throws Sfnt.FontException {
            long a = dataAt + offset(g), b = dataAt + offset(g + 1);
            if (b == a) return;
            if (b < a || b > t.length) throw damaged("glyph variation table (gvar)");
            int start = (int) a, end = (int) b;
            if (end - start < 4) throw damaged("glyph variation table (gvar)");
            sum(start + 4, Sfnt.u16(t, start), start + Sfnt.u16(t, start + 2), end, x.length, x, y, ends, dx, dy, work, p);
        }

        /** Adds cvar's deltas into the cvt values (one delta per entry; entries a tuple does not list are not moved by it). */
        void cvt(double[] d, long[] work, FontProgress p) throws Sfnt.FontException {
            sum(8, Sfnt.u16(t, 4), Sfnt.u16(t, 6), t.length, d.length, null, null, null, d, null, work, p);
        }

        /**
         * The tuple variation store whose headers start at {@code headers}: {@code countWord} is tupleVariationCount (with the shared
         * points flag), {@code data} where the serialized data starts, {@code end} where this store ends. gvar when {@code ends} != null.
         */
        private void sum(int headers, int countWord, int data, int end, int pointCount, int[] x, int[] y, int[] ends, double[] dx, double[] dy,
                long[] work, FontProgress p) throws Sfnt.FontException {
            boolean gvar = ends != null;
            String what = gvar ? "glyph variation table (gvar)" : "hinting variation table (cvar)";
            int count = countWord & 0x0FFF;
            if (count == 0) return;
            if (data > end) throw damaged(what);
            int[] pos = { data };
            int[] shared = null;
            if ((countWord & 0x8000) != 0) shared = points(t, pos, end, what);
            int tupleData = pos[0];
            double[] tx = new double[pointCount], ty = gvar ? new double[pointCount] : null;
            boolean[] has = new boolean[pointCount];
            int[] rx = new int[pointCount], ry = gvar ? new int[pointCount] : null;
            double[] scalars = new double[axisCount];
            int h = headers;
            for (int k = 0; k < count; k++) {
                if ((k & 63) == 63) FontProgress.Check.cancel(p);
                if (h + 4 > end) throw damaged(what);
                int size = Sfnt.u16(t, h), index = Sfnt.u16(t, h + 2);
                h += 4;
                int peakAt;
                if ((index & 0x8000) != 0) {
                    peakAt = h;
                    h += 2 * axisCount;
                } else {
                    if ((index & 0x0FFF) >= sharedCount) throw damaged(what);
                    peakAt = sharedAt + 2 * axisCount * (index & 0x0FFF);
                }
                int startAt = -1, endAt = -1;
                if ((index & 0x4000) != 0) {
                    startAt = h;
                    endAt = h + 2 * axisCount;
                    h += 4 * axisCount;
                }
                if (h > end) throw damaged(what);
                int at = tupleData, stop = tupleData + size;
                tupleData = stop;
                if (stop > end) throw damaged(what);

                // The region's scalar, one factor per axis that takes part, in the order fontTools multiplies them
                int nf = 0;
                boolean zero = false;
                for (int o = 0; o < axisCount && !zero; o++) {
                    int ax = order[o];
                    int peak = Sfnt.s16(t, peakAt + 2 * ax);
                    if (peak == 0) continue;
                    int lo = startAt < 0 ? Math.min(0, peak) : Sfnt.s16(t, startAt + 2 * ax);
                    int hi = startAt < 0 ? Math.max(0, peak) : Sfnt.s16(t, endAt + 2 * ax);
                    double s = VarStore.axisScalar(lo, peak, hi, ax < coords.length ? coords[ax] : 0);
                    if (s == 0) zero = true;
                    else if (s != 1) scalars[nf++] = s;
                }
                if (zero) continue;
                work[0] += pointCount;
                if (work[0] > maxTupleWork)
                    throw new Sfnt.FontException("too_complex", "This variable font's variations take too long to apply.");

                pos[0] = at;
                int[] pts = (index & 0x2000) != 0 ? points(t, pos, stop, what) : shared;   // null: every point
                int nd = pts == null ? pointCount : pts.length;
                if (rx.length < nd) { rx = new int[nd]; if (gvar) ry = new int[nd]; }
                deltas(t, pos, stop, nd, rx, what);
                if (gvar) deltas(t, pos, stop, nd, ry, what);
                if (pts == null) {
                    for (int i = 0; i < pointCount; i++) {
                        dx[i] += scale(rx[i], scalars, nf);
                        if (gvar) dy[i] += scale(ry[i], scalars, nf);
                    }
                    continue;
                }
                java.util.Arrays.fill(has, false);
                for (int i = 0; i < nd; i++) {
                    int q = pts[i];
                    if (q < 0 || q >= pointCount) continue;                    // as fontTools: a point the glyph does not have is ignored
                    has[q] = true;                                             // a point listed twice: the last delta counts
                    tx[q] = scale(rx[i], scalars, nf);
                    if (gvar) ty[q] = scale(ry[i], scalars, nf);
                }
                if (gvar) {
                    int s = 0;
                    for (int e : ends) {
                        iup(x, has, tx, s, e);
                        iup(y, has, ty, s, e);
                        s = e + 1;
                    }
                    for (int i = 0; i < pointCount; i++) { dx[i] += tx[i]; dy[i] += ty[i]; }
                } else {
                    for (int i = 0; i < pointCount; i++) if (has[i]) dx[i] += tx[i];
                }
            }
        }
    }

    private static double scale(int delta, double[] scalars, int n) {
        double d = delta;
        for (int i = 0; i < n; i++) d *= scalars[i];
        return d;
    }

    /** Packed point numbers at pos[0] (moved past them, which must stay before {@code end}): the point indexes, or null for every point. */
    static int[] points(byte[] t, int[] pos, int end, String what) throws Sfnt.FontException {
        int p = pos[0];
        if (p >= end) throw damaged(what);
        int count = Sfnt.u8(t, p++);
        if ((count & 0x80) != 0) {
            if (p >= end) throw damaged(what);
            count = (count & 0x7F) << 8 | Sfnt.u8(t, p++);
        }
        if (count == 0) { pos[0] = p; return null; }
        int[] out = new int[count];
        int i = 0, cur = 0;
        while (i < count) {
            if (p >= end) throw damaged(what);
            int ctl = Sfnt.u8(t, p++);
            int run = (ctl & 0x7F) + 1;
            boolean words = (ctl & 0x80) != 0;
            if (i + run > count || p + (long) run * (words ? 2 : 1) > end) throw damaged(what);
            for (int k = 0; k < run; k++) {
                cur += words ? Sfnt.u16(t, p) : Sfnt.u8(t, p);
                p += words ? 2 : 1;
                out[i++] = cur;
            }
        }
        pos[0] = p;
        return out;
    }

    /** {@code count} packed deltas at pos[0] (moved past them, which must stay before {@code end}) into {@code out}. */
    static void deltas(byte[] t, int[] pos, int end, int count, int[] out, String what) throws Sfnt.FontException {
        int p = pos[0], i = 0;
        while (i < count) {
            if (p >= end) throw damaged(what);
            int ctl = Sfnt.u8(t, p++);
            int run = (ctl & 0x3F) + 1;
            int kind = ctl & 0xC0;                                      // 0x80 zeros, 0x40 16-bit, 0xC0 32-bit (as fontTools reads it), 0 8-bit
            int size = kind == 0x80 ? 0 : kind == 0x40 ? 2 : kind == 0xC0 ? 4 : 1;
            if (i + run > count || p + (long) run * size > end) throw damaged(what);
            for (int k = 0; k < run; k++, i++) {
                out[i] = size == 0 ? 0 : size == 1 ? (byte) Sfnt.u8(t, p) : size == 2 ? Sfnt.s16(t, p) : Sfnt.s32(t, p);
                p += size;
            }
        }
        pos[0] = p;
    }

    /**
     * Infers, along one axis, the deltas of the points from {@code s} to {@code e} (one contour) that the tuple does not list: a point between
     * its two listed neighbours (around the contour) in that coordinate gets the interpolated delta, one outside them the nearer one's; no
     * listed point leaves the contour unmoved. The arithmetic is fontTools' iup_contour, so the sums round alike.
     */
    static void iup(int[] c, boolean[] has, double[] d, int s, int e) {
        int first = -1, last = -1, listed = 0;
        for (int i = s; i <= e; i++)
            if (has[i]) {
                if (first < 0) first = i;
                last = i;
                listed++;
            }
        if (listed == e - s + 1) return;
        if (first < 0) {
            for (int i = s; i <= e; i++) d[i] = 0;
            return;
        }
        if (first != s) segment(c, d, s, first - 1, first, last);
        int prev = first;
        for (int i = first + 1; i <= last; i++) {
            if (!has[i]) continue;
            if (i - prev > 1) segment(c, d, prev + 1, i - 1, prev, i);
            prev = i;
        }
        if (last != e) segment(c, d, last + 1, e, last, first);
    }

    private static void segment(int[] c, double[] d, int from, int to, int r1, int r2) {
        int x1 = c[r1], x2 = c[r2];
        double d1 = d[r1], d2 = d[r2];
        if (x1 == x2) {
            double v = d1 == d2 ? d1 : 0;
            for (int i = from; i <= to; i++) d[i] = v;
            return;
        }
        if (x1 > x2) {
            int xt = x1; x1 = x2; x2 = xt;
            double dt = d1; d1 = d2; d2 = dt;
        }
        double scale = (d2 - d1) / (x2 - x1);
        for (int i = from; i <= to; i++) {
            int v = c[i];
            d[i] = v <= x1 ? d1 : v >= x2 ? d2 : d1 + (v - x1) * scale;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HVAR and MVAR
    // ---------------------------------------------------------------------------------------------

    /** CFF2 (converted) fonts: the advances of hmtx plus HVAR's deltas; the bearings stay the converted outlines' xMin. */
    private static void hvarAdvances(Sfnt out, int[] coords, FontProgress p) throws Sfnt.FontException {
        if (!out.has("HVAR")) return;
        Glyf.Metrics m = Glyf.Metrics.read(out);
        hvarApply(out, out.table("HVAR"), coords, m, p);
        m.write(out, Glyf.readAll(out));
    }

    /** Adds HVAR's advance deltas to {@code m} (as fontTools does for a font without gvar: max(0, advance + round(delta))). */
    private static void hvarApply(Sfnt out, byte[] hvar, int[] coords, Glyf.Metrics m, FontProgress p) throws Sfnt.FontException {
        if (hvar == null) return;
        if (hvar.length < 20 || Sfnt.u16(hvar, 0) != 1) throw damaged("metrics variation table (HVAR)");
        long storeOff = Sfnt.u32(hvar, 4), mapOff = Sfnt.u32(hvar, 8);
        if (storeOff == 0) return;
        if (storeOff + 8 > hvar.length || mapOff + 4 > hvar.length) throw damaged("metrics variation table (HVAR)");
        VarStore store = new VarStore(hvar, (int) storeOff).at(coords);
        long[] map = mapOff == 0 ? null : VarStore.readIndexMap(hvar, (int) mapOff);
        HashMap<Long, Integer> cache = new HashMap<Long, Integer>();     // a crafted map sends every glyph to one costly item
        int n = m.advance.length;
        for (int g = 0; g < n; g++) {
            if ((g & 0xFF) == 0) FontProgress.Check.cancel(p);
            long idx = map == null || map.length == 0 ? g : map[Math.min(g, map.length - 1)];
            Integer d = cache.get(idx);
            if (d == null) cache.put(idx, d = Glyf.round(store.deltaFor(idx)));
            m.advance[g] = Math.max(0, m.advance[g] + d);
        }
    }

    /** MVAR value tags and the field each moves: table, offset, signed. The vertical ones go with vhea; gasp ones are not applied (as fontTools). */
    private static final Object[][] MVAR_FIELDS = {
            { "hasc", "OS/2", 68, true }, { "hdsc", "OS/2", 70, true }, { "hlgp", "OS/2", 72, true },
            { "hcla", "OS/2", 74, false }, { "hcld", "OS/2", 76, false },
            { "hcrs", "hhea", 18, true }, { "hcrn", "hhea", 20, true }, { "hcof", "hhea", 22, true },
            { "xhgt", "OS/2", 86, true }, { "cpht", "OS/2", 88, true },
            { "sbxs", "OS/2", 10, true }, { "sbys", "OS/2", 12, true }, { "sbxo", "OS/2", 14, true }, { "sbyo", "OS/2", 16, true },
            { "spxs", "OS/2", 18, true }, { "spys", "OS/2", 20, true }, { "spxo", "OS/2", 22, true }, { "spyo", "OS/2", 24, true },
            { "strs", "OS/2", 26, true }, { "stro", "OS/2", 28, true },
            { "unds", "post", 10, true }, { "undo", "post", 8, true },
    };

    /**
     * Adds MVAR's deltas to the OS/2, hhea and post values they name. As fontTools does, when OS/2's typographic ascender, descender and
     * line gap were hhea's before, hhea follows them.
     */
    private static void mvar(Sfnt vf, Sfnt out, int[] coords) throws Sfnt.FontException {
        byte[] t = out.table("MVAR");
        if (t.length < 12 || Sfnt.u16(t, 0) != 1) throw damaged("metrics variation table (MVAR)");
        int recSize = Sfnt.u16(t, 6), count = Sfnt.u16(t, 8), storeOff = Sfnt.u16(t, 10);
        if (count == 0 || storeOff == 0) return;
        if (recSize < 8) throw damaged("metrics variation table (MVAR)");
        Sfnt.need(t, 12, (long) recSize * count);
        VarStore store = new VarStore(t, storeOff).at(coords);
        byte[] os2 = own(out, vf, "OS/2"), hhea = own(out, vf, "hhea"), post = own(out, vf, "post");
        boolean synced = os2 != null && os2.length >= 74 && Sfnt.s16(os2, 68) == Sfnt.s16(hhea, 4) && Sfnt.s16(os2, 70) == Sfnt.s16(hhea, 6)
                && Sfnt.s16(os2, 72) == Sfnt.s16(hhea, 8);
        int[] before = synced ? new int[] { Sfnt.s16(os2, 68), Sfnt.s16(os2, 70), Sfnt.s16(os2, 72) } : null;
        HashMap<Long, Integer> cache = new HashMap<Long, Integer>();
        for (int i = 0; i < count; i++) {
            int r = 12 + recSize * i;
            String tag = Sfnt.tagString(t, r);
            for (Object[] f : MVAR_FIELDS) {
                if (!f[0].equals(tag)) continue;
                byte[] tab = f[1].equals("OS/2") ? os2 : f[1].equals("hhea") ? hhea : post;
                int off = (Integer) f[2];
                if (tab == null || off + 2 > tab.length || tag.equals("xhgt") && Sfnt.u16(tab, 0) < 2 || tag.equals("cpht") && Sfnt.u16(tab, 0) < 2) break;
                long key = (long) Sfnt.u16(t, r + 4) << 16 | Sfnt.u16(t, r + 6);
                Integer d = cache.get(key);
                if (d == null) cache.put(key, d = Glyf.round(store.delta(Sfnt.u16(t, r + 4), Sfnt.u16(t, r + 6))));
                if (d == 0) break;
                boolean signed = (Boolean) f[3];
                long v = (signed ? Sfnt.s16(tab, off) : Sfnt.u16(tab, off)) + (long) d;
                Sfnt.w16(tab, off, (int) (signed ? Math.max(-32768, Math.min(32767, v)) : Math.max(0, Math.min(0xFFFF, v))));
                break;
            }
        }
        if (before != null) {
            int[] after = { Sfnt.s16(os2, 68), Sfnt.s16(os2, 70), Sfnt.s16(os2, 72) };
            if (!java.util.Arrays.equals(before, after)) for (int k = 0; k < 3; k++) Sfnt.w16(hhea, 4 + 2 * k, after[k]);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GSUB, GPOS, GDEF
    // ---------------------------------------------------------------------------------------------

    /** GPOS values and anchors and GDEF carets with their deltas; GSUB / GPOS FeatureVariations resolved at the location (see {@link OtLayout}). */
    private static void layout(Sfnt vf, Sfnt out, int[] coords, Options opt, FontProgress p) throws Sfnt.FontException {
        byte[] gdef = out.table("GDEF");
        VarStore store = gdef == null ? null : OtLayout.gdefVarStore(gdef);
        if (store != null) store.at(coords);
        byte[] gpos = out.table("GPOS");
        if (gpos != null) {
            gpos = gpos.clone();
            if (store != null && OtLayout.applyGposDeltas(gpos, store, p) > 0) opt.warnings.add(W_GPOS_SKIPPED);
            out.put("GPOS", featureVariations(gpos, false, coords, p));
        }
        byte[] gsub = out.table("GSUB");
        if (gsub != null) out.put("GSUB", featureVariations(gsub, true, coords, p));
        if (gdef != null) out.put("GDEF", OtLayout.instanceGdef(gdef, store));
    }

    /** The table with the FeatureVariations record that holds at the location swapped in, or the same array when it has none. */
    private static byte[] featureVariations(byte[] table, boolean gsub, int[] coords, FontProgress p) throws Sfnt.FontException {
        if (table.length < 14 || Sfnt.u16(table, 0) != 1 || Sfnt.u16(table, 2) < 1 || Sfnt.u32(table, 10) == 0) return table;
        OtLayout.Table m = OtLayout.parse(table, gsub);
        if (!m.hasFeatureVariations()) return table;
        OtLayout.applyFeatureVariations(m, coords);
        return OtLayout.write(m, p);
    }

    // ---------------------------------------------------------------------------------------------
    // Hinting, OS/2, post
    // ---------------------------------------------------------------------------------------------

    /** With hinting kept: cvar's deltas added to the cvt. Without: maxp says the font has no instructions (fpgm, prep, cvt are dropped later). */
    private static void hinting(Sfnt vf, Sfnt out, VarStore.Fvar fv, int[] coords, Options opt) throws Sfnt.FontException {
        if (opt.keepHinting) {
            byte[] maxp = out.table("maxp"), old = vf.table("maxp");
            if (maxp != null && maxp.length >= 32 && old.length >= 32 && Sfnt.u32(old, 0) == 0x00010000L && Sfnt.u16(old, 26) > Sfnt.u16(maxp, 26))
                Sfnt.w16(own(out, vf, "maxp"), 26, Sfnt.u16(old, 26));          // some tools count fpgm / prep in it; fontTools keeps it
            byte[] cvar = out.table("cvar"), cvt = out.table("cvt ");
            if (cvar == null || cvt == null) return;
            double[] d = new double[cvt.length / 2];
            Tuples.cvar(cvar, fv, coords).cvt(d, new long[1], opt.progress);
            cvt = own(out, vf, "cvt ");
            for (int i = 0; i < d.length; i++) {
                int v = Glyf.round(d[i]);
                if (v != 0) Sfnt.w16(cvt, 2 * i, Math.max(-32768, Math.min(32767, Sfnt.s16(cvt, 2 * i) + v)));
            }
            return;
        }
        byte[] maxp = out.table("maxp");
        if (maxp != null && maxp.length >= 32 && Sfnt.u32(maxp, 0) == 0x00010000L) {
            maxp = own(out, vf, "maxp");
            for (int off = 16; off <= 26; off += 2) Sfnt.w16(maxp, off, 0);     // twilight points, storage, functions, IDEFs, stack, instructions
        }
    }

    /** Values of the location written where fontTools writes them: OS/2 weight and width classes and average width, post's italic angle. */
    private static void styleValues(Sfnt vf, Sfnt out, VarStore.Fvar fv, double[] user) throws Sfnt.FontException {
        byte[] os2 = out.table("OS/2");
        if (os2 != null && os2.length >= 8) {
            os2 = own(out, vf, "OS/2");
            Glyf.Metrics m = Glyf.Metrics.read(out);
            long sum = 0;
            int count = 0;
            for (int a : m.advance) if (a > 0) { sum += a; count++; }
            Sfnt.w16(os2, 2, count == 0 ? 0 : Glyf.round((double) sum / count));
            int w = fv.axisIndex("wght");
            if (w >= 0) Sfnt.w16(os2, 4, Glyf.round(Math.max(1, Math.min(1000, user[w]))));
            int wd = fv.axisIndex("wdth");
            if (wd >= 0) Sfnt.w16(os2, 6, widthClass(user[wd]));
        }
        int sl = fv.axisIndex("slnt");
        byte[] post = out.table("post");
        if (sl >= 0 && post != null && post.length >= 8) {
            post = own(out, vf, "post");
            Sfnt.w32(post, 4, (int) Math.floor(Math.max(-90, Math.min(90, user[sl])) * 65536 + 0.5));
        }
    }

    /** OS/2 usWidthClass for a wdth value (percent of normal), by the OpenType table: 50 -> 1, 62.5 -> 2 ... 100 -> 5 ... 200 -> 9, rounded. */
    static int widthClass(double wdth) {
        double[][] map = { { 50, 1 }, { 62.5, 2 }, { 75, 3 }, { 87.5, 4 }, { 100, 5 }, { 112.5, 6 }, { 125, 7 }, { 150, 8 }, { 200, 9 } };
        double v = Math.max(50, Math.min(200, wdth));
        for (int i = 1; i < map.length; i++) {
            double a = map[i - 1][0], b = map[i][0], va = map[i - 1][1], vb = map[i][1];
            if (v == a) return (int) va;
            if (v < b) return Glyf.round(va + (vb - va) * (v - a) / (b - a));        // fontTools' piecewiseLinearMap, operation for operation
        }
        return 9;
    }

    /** Marks every glyph of a converted font as one whose contours may overlap (first point / first component), as {@link #outlines} does. */
    private static void overlapFlags(Sfnt out) throws Sfnt.FontException {
        byte[][] glyphs = Glyf.readAll(out);
        for (int g = 0; g < glyphs.length; g++) {
            Glyf.Glyph gl = Glyf.decode(glyphs[g]);
            if (gl.isComposite() && !gl.components.isEmpty()) gl.components.get(0).flags |= Glyf.OVERLAP_COMPOUND;
            else if (gl.contours > 0) gl.overlap = true;
            else continue;
            glyphs[g] = Glyf.encode(gl);
        }
        Glyf.write(out, glyphs);
    }
}
