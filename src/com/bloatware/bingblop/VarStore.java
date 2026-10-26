package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;

/**
 * Variable font axes and the OpenType ItemVariationStore, for making a static instance of a variable font.
 *
 * <p>{@link Fvar} reads the axes and named instances; {@link #normalize} turns user values (wght 700) into the normalized coordinates the
 * variation tables are indexed by, through avar; {@link VarStore} evaluates deltas from an ItemVariationStore (HVAR, MVAR, GDEF, COLR, CFF2).
 * Normalized coordinates are kept as F2Dot14 integers (16384 = 1.0), rounded the way fontTools and HarfBuzz round them, so every table sees the
 * same location. Pure Java; every read is bounds-checked.
 */
public final class VarStore {

    /** A variation axis of fvar. */
    public static final class Axis {
        public String tag;
        public double min, def, max;
        public int flags, nameId;
        public String name = "";

        public boolean hidden() { return (flags & 1) != 0; }
    }

    /** A named instance of fvar (e.g. "Bold": wght 700). */
    public static final class Instance {
        public int subfamilyNameId, postScriptNameId = 0xFFFF;
        public double[] coords;
        public String name = "";
    }

    /** The axes and named instances of a variable font. */
    public static final class Fvar {
        public final List<Axis> axes = new ArrayList<Axis>();
        public final List<Instance> instances = new ArrayList<Instance>();

        public int axisIndex(String tag) {
            for (int i = 0; i < axes.size(); i++) if (axes.get(i).tag.equals(tag)) return i;
            return -1;
        }

        public static Fvar read(Sfnt f) throws Sfnt.FontException {
            byte[] t = f.table("fvar");
            if (t == null) throw new Sfnt.FontException("This is not a variable font (it has no fvar table).");
            if (Sfnt.u16(t, 0) != 1) throw new Sfnt.FontException("This variable font's fvar version is not supported.");
            int axesOff = Sfnt.u16(t, 4), axisCount = Sfnt.u16(t, 8), axisSize = Sfnt.u16(t, 10);
            int instCount = Sfnt.u16(t, 12), instSize = Sfnt.u16(t, 14);
            if (axisCount == 0 || axisCount > 64 || axisSize < 20) throw new Sfnt.FontException("This variable font's axes are damaged.");
            if (instSize < 4 + 4 * axisCount) throw new Sfnt.FontException("This variable font's named instances are damaged.");
            Fvar v = new Fvar();
            for (int i = 0; i < axisCount; i++) {
                int p = axesOff + i * axisSize;
                Axis a = new Axis();
                a.tag = Sfnt.tagString(t, p);
                a.min = fixed(t, p + 4); a.def = fixed(t, p + 8); a.max = fixed(t, p + 12);
                a.flags = Sfnt.u16(t, p + 16); a.nameId = Sfnt.u16(t, p + 18);
                if (a.min > a.def) a.min = a.def;            // as HarfBuzz does with a damaged axis
                if (a.max < a.def) a.max = a.def;
                a.name = f.name(a.nameId);
                if (a.name.isEmpty()) a.name = a.tag.trim();
                v.axes.add(a);
            }
            int instOff = axesOff + axisCount * axisSize;
            for (int i = 0; i < instCount && i < 1024; i++) {
                int p = instOff + i * instSize;
                Instance in = new Instance();
                in.subfamilyNameId = Sfnt.u16(t, p);
                in.coords = new double[axisCount];
                for (int k = 0; k < axisCount; k++) in.coords[k] = fixed(t, p + 4 + 4 * k);
                if (instSize >= 6 + 4 * axisCount) in.postScriptNameId = Sfnt.u16(t, p + 4 + 4 * axisCount);
                in.name = f.name(in.subfamilyNameId);
                v.instances.add(in);
            }
            return v;
        }
    }

    static double fixed(byte[] t, int p) throws Sfnt.FontException { return Sfnt.s32(t, p) / 65536.0; }

    // ---------------------------------------------------------------------------------------------
    // Normalization (fvar + avar)
    // ---------------------------------------------------------------------------------------------

    /**
     * Normalized coordinates (F2Dot14, one per axis) for user values in axis order: default normalization, then avar's segment maps.
     * Values outside an axis range are clamped to it.
     */
    public static int[] normalize(Sfnt f, Fvar fvar, double[] user) throws Sfnt.FontException {
        int n = fvar.axes.size();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            Axis a = fvar.axes.get(i);
            double v = Math.max(a.min, Math.min(a.max, user[i]));
            double nv;
            if (v < a.def) nv = a.def == a.min ? 0 : -(a.def - v) / (a.def - a.min);
            else if (v > a.def) nv = a.max == a.def ? 0 : (v - a.def) / (a.max - a.def);
            else nv = 0;
            out[i] = f2dot14(nv);
        }
        byte[] avar = f.table("avar");
        if (avar != null && avar.length >= 8) {
            int major = Sfnt.u16(avar, 0);
            if (major == 1 || major == 2) {
                int count = Sfnt.u16(avar, 6);
                int p = 8;
                for (int i = 0; i < count && i < n; i++) {
                    int maps = Sfnt.u16(avar, p);
                    p += 2;
                    Sfnt.need(avar, p, 4L * maps);
                    out[i] = mapSegment(avar, p, maps, out[i]);
                    p += 4 * maps;
                }
                // avar 2's cross-axis mapping (its own variation store) is not applied: the page warns when a font has it.
            }
        }
        return out;
    }

    /** True when the font has avar version 2, whose cross-axis mapping {@link #normalize} does not apply. */
    public static boolean hasAvar2(Sfnt f) {
        byte[] avar = f.table("avar");
        try {
            return avar != null && avar.length >= 2 && Sfnt.u16(avar, 0) == 2;
        } catch (Sfnt.FontException e) {
            return false;
        }
    }

    private static int mapSegment(byte[] avar, int p, int maps, int v) throws Sfnt.FontException {
        if (maps < 3) return v;                                 // fewer than the three required maps: identity, as HarfBuzz does
        int prevFrom = Sfnt.s16(avar, p), prevTo = Sfnt.s16(avar, p + 2);
        if (v <= prevFrom) return prevTo == prevFrom ? v : prevTo + (v - prevFrom);
        for (int i = 1; i < maps; i++) {
            int from = Sfnt.s16(avar, p + 4 * i), to = Sfnt.s16(avar, p + 4 * i + 2);
            if (v <= from) {
                if (from == prevFrom) return to;
                double r = (double) (v - prevFrom) / (from - prevFrom);
                return f2dot14((prevTo + r * (to - prevTo)) / 16384.0);
            }
            prevFrom = from; prevTo = to;
        }
        return prevTo + (v - prevFrom);
    }

    /** A value in -2..2 as F2Dot14, rounded the way fontTools rounds it (floor(v * 16384 + 0.5)) and clamped to -1..1. */
    public static int f2dot14(double v) {
        long r = (long) Math.floor(v * 16384 + 0.5);
        return (int) Math.max(-16384, Math.min(16384, r));
    }

    // ---------------------------------------------------------------------------------------------
    // Region scalars
    // ---------------------------------------------------------------------------------------------

    /** The scalar of one axis of a region (start, peak, end) at normalized coordinate v, all F2Dot14. */
    public static double axisScalar(int start, int peak, int end, int v) {
        if (peak == 0 || start > peak || peak > end) return 1;
        if (start < 0 && end > 0) return 1;
        if (v == peak) return 1;
        if (v <= start || v >= end) return 0;
        return v < peak ? (double) (v - start) / (peak - start) : (double) (end - v) / (end - peak);
    }

    // ---------------------------------------------------------------------------------------------
    // ItemVariationStore
    // ---------------------------------------------------------------------------------------------

    private final byte[] t;
    private final int base;
    private final int axisCount;
    private final int[][][] regions;          // [region][axis] = {start, peak, end}
    private final int[] dataOff;
    private double[] scalars;                 // per region, at the location set by at()

    /** Reads an ItemVariationStore at {@code off} in table {@code t}. */
    public VarStore(byte[] t, int off) throws Sfnt.FontException {
        this.t = t;
        this.base = off;
        if (Sfnt.u16(t, off) != 1) throw new Sfnt.FontException("A variation store of this font has an unknown format.");
        long regOff = off + Sfnt.u32(t, off + 2);
        int dataCount = Sfnt.u16(t, off + 6);
        Sfnt.need(t, off + 8, 4L * dataCount);
        dataOff = new int[dataCount];
        for (int i = 0; i < dataCount; i++) {
            long d = Sfnt.u32(t, off + 8 + 4 * i);
            dataOff[i] = d == 0 ? -1 : (int) Math.min(off + d, Integer.MAX_VALUE);     // past the table either way: delta() refuses it
        }
        if (regOff > t.length) throw new Sfnt.FontException("A variation store of this font is cut off.");
        int r = (int) regOff;
        axisCount = Sfnt.u16(t, r);
        int regionCount = Sfnt.u16(t, r + 2);
        Sfnt.need(t, r + 4, 6L * axisCount * regionCount);
        regions = new int[regionCount][axisCount][3];
        for (int i = 0; i < regionCount; i++)
            for (int a = 0; a < axisCount; a++) {
                int p = r + 4 + 6 * (i * axisCount + a);
                regions[i][a][0] = Sfnt.s16(t, p); regions[i][a][1] = Sfnt.s16(t, p + 2); regions[i][a][2] = Sfnt.s16(t, p + 4);
            }
    }

    /** Sets the location (normalized F2Dot14 coordinates, one per fvar axis; missing axes are 0). */
    public VarStore at(int[] coords) {
        scalars = new double[regions.length];
        for (int i = 0; i < regions.length; i++) {
            double s = 1;
            for (int a = 0; a < axisCount && s != 0; a++) {
                int v = a < coords.length ? coords[a] : 0;
                s *= axisScalar(regions[i][a][0], regions[i][a][1], regions[i][a][2], v);
            }
            scalars[i] = s;
        }
        return this;
    }

    public int dataCount() { return dataOff.length; }

    /** The delta of item (outer, inner) at the location set with {@link #at}; 0 for an index outside the store (as HarfBuzz does). */
    public double delta(int outer, int inner) throws Sfnt.FontException {
        if (scalars == null) throw new IllegalStateException("at() first");
        if (outer < 0 || outer >= dataOff.length || dataOff[outer] < 0) return 0;
        int p = dataOff[outer];
        int itemCount = Sfnt.u16(t, p);
        if (inner < 0 || inner >= itemCount) return 0;
        int wordRaw = Sfnt.u16(t, p + 2);
        boolean longWords = (wordRaw & 0x8000) != 0;
        int wordCount = wordRaw & 0x7FFF;
        int regionIndexCount = Sfnt.u16(t, p + 4);
        if (wordCount > regionIndexCount) throw new Sfnt.FontException("A variation store of this font is damaged.");
        int ri = p + 6;
        int wordSize = longWords ? 4 : 2, smallSize = longWords ? 2 : 1;
        int rowSize = wordCount * wordSize + (regionIndexCount - wordCount) * smallSize;
        long rowAt = ri + 2L * regionIndexCount + (long) inner * rowSize;     // past 2 GB for a damaged count: int arithmetic would wrap
        Sfnt.need(t, rowAt, rowSize);
        int row = (int) rowAt;
        double sum = 0;
        for (int k = 0; k < regionIndexCount; k++) {
            int region = Sfnt.u16(t, ri + 2 * k);
            if (region >= scalars.length) continue;
            double s = scalars[region];
            int d;
            if (k < wordCount) d = longWords ? Sfnt.s32(t, row + 4 * k) : Sfnt.s16(t, row + 2 * k);
            else {
                int q = row + wordCount * wordSize + (k - wordCount) * smallSize;
                d = longWords ? Sfnt.s16(t, q) : (byte) Sfnt.u8(t, q);
            }
            if (s != 0) sum += s * d;
        }
        return sum;
    }

    /** The delta for a 32-bit VarIdx (outer << 16 | inner); 0xFFFFFFFF (no variation) gives 0. */
    public double deltaFor(long varIdx) throws Sfnt.FontException {
        if (varIdx == 0xFFFFFFFFL) return 0;
        return delta((int) (varIdx >>> 16) & 0xFFFF, (int) varIdx & 0xFFFF);
    }

    // ---------------------------------------------------------------------------------------------
    // DeltaSetIndexMap (HVAR, VVAR, COLR)
    // ---------------------------------------------------------------------------------------------

    /** Reads a DeltaSetIndexMap at {@code off} of {@code t}: index i -> (outer << 16 | inner). Past the end, the last entry is used. */
    public static long[] readIndexMap(byte[] t, int off) throws Sfnt.FontException {
        int format = Sfnt.u8(t, off);
        int entryFormat = Sfnt.u8(t, off + 1);
        long count;
        int p;
        if (format == 0) { count = Sfnt.u16(t, off + 2); p = off + 4; }
        else if (format == 1) { count = Sfnt.u32(t, off + 2); p = off + 6; }
        else throw new Sfnt.FontException("A delta-set index map of this font has an unknown format.");
        int size = ((entryFormat >> 4) & 3) + 1, innerBits = (entryFormat & 0xF) + 1;
        if (count > 0x10000) throw new Sfnt.FontException("A delta-set index map of this font is too long.");
        Sfnt.need(t, p, count * size);
        long[] out = new long[(int) count];
        for (int i = 0; i < count; i++) {
            long v = 0;
            for (int k = 0; k < size; k++) v = (v << 8) | (t[p + i * size + k] & 0xff);
            long outer = v >>> innerBits, inner = v & ((1L << innerBits) - 1);
            out[i] = (outer << 16) | inner;
        }
        return out;
    }
}
