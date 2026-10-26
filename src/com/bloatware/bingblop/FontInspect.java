package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a font file the user picked is: its names, weight, outlines, characters, colour glyphs and variation axes.
 *
 * <p>The Custom Fonts tab shows this for every font picked for Regular, Bold or Emoji (MainActivity turns an {@link Info} into the page's
 * JSON): the names people see in font menus (the typographic family and style when the font has them), what the build has to do with it
 * (convert PostScript outlines, make a static instance of a variable font, preselected by {@link Info#guessRegular} and
 * {@link Info#guessBold}), and warnings as plain sentences. A colour bitmap font without outlines is refused with code "bitmap_emoji", as
 * the emoji merge cannot use it. Pure Java; everything is read through the bounds-checked readers of {@link Sfnt}, {@link Cmap} and
 * {@link VarStore}, so a bad file ends in a {@link Sfnt.FontException}.
 */
public final class FontInspect {

    private FontInspect() {}

    /** Longest name text kept (license texts can run to pages). */
    static final int MAX_TEXT = 1000;

    public static final String W_SYMBOL = "This is a symbol font: its characters are not at their Unicode places, so ordinary text does not show in it.";
    public static final String W_NO_CHARACTERS = "This font maps no characters, so no text shows in it.";
    public static final String W_NO_LATIN = "This font has no basic Latin letters and digits (A-Z, a-z, 0-9): English text would show in the phone's own font.";
    public static final String W_RESTRICTED = "This font's maker does not allow it to be embedded or changed (\"restricted license\" in the font). Use it only if its license lets you.";
    public static final String W_AVAR2 = "This variable font maps its axes onto each other (avar version 2). The app applies that mapping as HarfBuzz does, so its weights may look a little different from apps that do not.";
    public static final String W_NO_PALETTE = "This font has colour layers (COLR) but no colour palette (CPAL), so its colour glyphs cannot be shown.";
    public static final String W_COLOR_DAMAGED = "This font's colour table (COLR) is damaged; its colour glyphs are left out.";
    public static final String W_BITMAP = "This font's colour glyphs are bitmaps (%s): bitmap emoji cannot be put inside a font, so only its outlines would be used.";
    public static final String W_SVG = "This font's colour glyphs are SVG pictures, which Android does not draw: only its outlines would be used.";

    /** A variation axis. */
    public static final class Axis {
        public String tag, name;
        public double min, def, max;
        public boolean hidden;
    }

    /** A named instance: its style name and the value of every axis (tag -> value, in axis order). */
    public static final class Instance {
        public String name;
        public final LinkedHashMap<String, Double> coords = new LinkedHashMap<String, Double>();
    }

    /** What {@link #inspect} found. Strings are "" when the font does not say. */
    public static final class Info {
        /** The family and style people see: typographic (name ids 16 / 17) when present, else 1 / 2. */
        public String family = "", subfamily = "";
        public String fullName = "", postScriptName = "", version = "";
        /** Copyright (name 0), designer (9), license text (13) and license address (14), trimmed. */
        public String copyright = "", designer = "", license = "", licenseUrl = "";
        /** OS/2 usWeightClass (400 Regular, 700 Bold) and usWidthClass (5 normal), and the embedding bits. */
        public int weight = 400, width = 5, fsType;
        public boolean italic;
        /** "TrueType", "CFF" or "CFF2". */
        public String outlines = "";
        public int glyphs, upem;
        /** Characters the best map has, and whether A-Z, a-z and 0-9 are all among them. */
        public int codepoints;
        public boolean latin;
        /** The map is a Windows symbol one (characters not at their Unicode places). */
        public boolean symbol;
        /** "none", "COLRv0", "COLRv1", "CBDT", "sbix" or "SVG": the colour glyphs, COLR first (the only kind the emoji merge takes). */
        public String color = "none";
        public boolean variable, avar2;
        public final List<Axis> axes = new ArrayList<Axis>();
        public final List<Instance> instances = new ArrayList<Instance>();
        /** For a variable font: the location to preselect for Regular (wght near 400) and Bold (near 700); null when not variable, and Bold null without a wght axis. */
        public LinkedHashMap<String, Double> guessRegular, guessBold;
        public final List<String> warnings = new ArrayList<String>();
    }

    /** Reads a font file. Refuses what {@link Sfnt#parse} refuses, and a colour bitmap font without outlines with code "bitmap_emoji". */
    public static Info inspect(byte[] data) throws Sfnt.FontException {
        Sfnt f;
        try {
            f = Sfnt.parse(data);
        } catch (Sfnt.FontException e) {
            String bitmap = "no_outlines".equals(e.code) ? bitmapTable(data) : null;
            if (bitmap != null)
                throw new Sfnt.FontException("bitmap_emoji", "This is a bitmap emoji font (" + bitmap + "): bitmap emoji cannot be put inside a font. Pick a COLR emoji font, such as Noto Color Emoji or Twemoji.");
            throw e;
        }
        return inspect(f);
    }

    /** CBDT or sbix when the table directory has one (the directory was checked by Sfnt.parse before it found no outlines). */
    private static String bitmapTable(byte[] data) {
        try {
            int n = Sfnt.u16(data, 4);
            for (int i = 0; i < n; i++) {
                String tag = Sfnt.tagString(data, 12 + 16 * i);
                if (tag.equals("CBDT") || tag.equals("sbix")) return tag;
            }
        } catch (Sfnt.FontException ignored) {}
        return null;
    }

    public static Info inspect(Sfnt f) throws Sfnt.FontException {
        Info in = new Info();
        in.family = first(f.name(16), f.name(1));
        in.subfamily = first(f.name(17), f.name(2));
        in.fullName = first(f.name(4), (in.family + " " + in.subfamily).trim());
        in.postScriptName = f.name(6);
        in.version = clip(f.name(5));
        in.copyright = clip(f.name(0));
        in.designer = clip(f.name(9));
        in.license = clip(f.name(13));
        in.licenseUrl = clip(f.name(14));

        int macStyle = Sfnt.u16(f.table("head"), 44);
        byte[] os2 = f.table("OS/2");
        int fsSelection;
        if (os2 != null && os2.length >= 64) {
            in.weight = Sfnt.u16(os2, 4);
            in.width = Sfnt.u16(os2, 6);
            in.fsType = Sfnt.u16(os2, 8);
            fsSelection = Sfnt.u16(os2, 62);
        } else {
            in.weight = (macStyle & 1) != 0 ? 700 : 400;                  // no OS/2 (old Mac fonts): what head says
            fsSelection = (macStyle & 2) != 0 ? 1 : 0;
        }
        in.italic = (fsSelection & 1) != 0 || (macStyle & 2) != 0;
        in.outlines = f.has("glyf") ? "TrueType" : f.has("CFF2") ? "CFF2" : "CFF";
        in.glyphs = f.numGlyphs();
        in.upem = f.unitsPerEm();

        Cmap.Best best = Cmap.readBest(f, null);
        in.codepoints = best.map.size();
        in.symbol = best.symbol();
        boolean latin = true;
        for (int c = '0'; c <= 'z' && latin; c++) if (Character.isLetterOrDigit(c)) latin = best.map.containsKey(c);
        in.latin = latin && in.codepoints > 0;

        in.color = color(f, in.warnings);

        if (f.isVariable()) {
            VarStore.Fvar fv = VarStore.Fvar.read(f);
            in.variable = true;
            in.avar2 = VarStore.hasAvar2(f);
            for (VarStore.Axis a : fv.axes) {
                Axis x = new Axis();
                x.tag = a.tag;
                x.name = a.name;
                x.min = a.min;
                x.def = a.def;
                x.max = a.max;
                x.hidden = a.hidden();
                in.axes.add(x);
            }
            for (VarStore.Instance i : fv.instances) {
                Instance x = new Instance();
                for (int k = 0; k < fv.axes.size(); k++) x.coords.put(fv.axes.get(k).tag, i.coords[k]);
                x.name = i.name.isEmpty() ? describe(x.coords) : i.name;
                in.instances.add(x);
            }
            in.guessRegular = guess(fv, 400);
            in.guessBold = fv.axisIndex("wght") < 0 ? null : guess(fv, 700);
        }

        if (in.symbol) in.warnings.add(W_SYMBOL);
        else if (in.codepoints == 0) in.warnings.add(W_NO_CHARACTERS);
        else if (!in.latin && in.color.equals("none") && !f.has("COLR")) in.warnings.add(W_NO_LATIN);   // an emoji font needs no letters
        if ((in.fsType & 0x0002) != 0) in.warnings.add(W_RESTRICTED);
        if (in.avar2) in.warnings.add(W_AVAR2);
        return in;
    }

    /** The colour format, COLR first; damaged or palette-less COLR adds a warning and is passed over. */
    private static String color(Sfnt f, List<String> warnings) {
        byte[] c = f.table("COLR");
        if (c != null) {
            try {
                int version = Sfnt.u16(c, 0), baseCount = Sfnt.u16(c, 2);
                boolean v1 = false;
                if (version >= 1) {
                    long list = Sfnt.u32(c, 14);                             // BaseGlyphList: a count of paint records first
                    if (list > c.length - 4) throw new Sfnt.FontException("COLR");
                    v1 = list != 0 && Sfnt.u32(c, (int) list) > 0;
                }
                if (v1 || baseCount > 0) {
                    if (!f.has("CPAL")) warnings.add(W_NO_PALETTE);
                    else return v1 ? "COLRv1" : "COLRv0";
                }
            } catch (Sfnt.FontException e) {
                warnings.add(W_COLOR_DAMAGED);
            }
        }
        if (f.has("CBDT")) { warnings.add(String.format(W_BITMAP, "CBDT")); return "CBDT"; }
        if (f.has("sbix")) { warnings.add(String.format(W_BITMAP, "sbix")); return "sbix"; }
        if (f.has("SVG ")) { warnings.add(W_SVG); return "SVG"; }
        return "none";
    }

    /**
     * The location to preselect for a weight: the named instance whose wght is closest to it (ties: the one nearest the default on the other
     * axes, then the first), unless the axis itself gets closer (wght clamped to its range, the other axes at their defaults).
     */
    static LinkedHashMap<String, Double> guess(VarStore.Fvar fv, double target) {
        int w = fv.axisIndex("wght");
        int n = fv.axes.size();
        double[] at = new double[n];
        for (int k = 0; k < n; k++) at[k] = fv.axes.get(k).def;
        if (w >= 0) {
            VarStore.Axis a = fv.axes.get(w);
            at[w] = Math.max(a.min, Math.min(a.max, target));
            VarStore.Instance best = null;
            double bestW = Double.MAX_VALUE, bestRest = Double.MAX_VALUE;
            for (VarStore.Instance i : fv.instances) {
                double dw = Math.abs(clamp(fv.axes.get(w), i.coords[w]) - target), rest = 0;
                for (int k = 0; k < n; k++) if (k != w) rest += distance(fv.axes.get(k), i.coords[k]);
                if (dw < bestW || dw == bestW && rest < bestRest) { best = i; bestW = dw; bestRest = rest; }
            }
            if (best != null && bestW <= Math.abs(at[w] - target))
                for (int k = 0; k < n; k++) at[k] = clamp(fv.axes.get(k), best.coords[k]);
        }
        LinkedHashMap<String, Double> out = new LinkedHashMap<String, Double>();
        for (int k = 0; k < n; k++) out.put(fv.axes.get(k).tag, at[k]);
        return out;
    }

    private static double clamp(VarStore.Axis a, double v) { return Math.max(a.min, Math.min(a.max, v)); }

    /** How far a value is from the axis default, 0..1 of the way to the end of the axis on that side. */
    private static double distance(VarStore.Axis a, double v) {
        v = clamp(a, v);
        if (v < a.def) return a.def == a.min ? 0 : (a.def - v) / (a.def - a.min);
        return a.max == a.def ? 0 : (v - a.def) / (a.max - a.def);
    }

    /** "wght 700, opsz 32" for an instance without a name. */
    static String describe(Map<String, Double> coords) {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Double> e : coords.entrySet()) {
            if (s.length() > 0) s.append(", ");
            double v = e.getValue();
            s.append(e.getKey().trim()).append(' ').append(v == Math.rint(v) && Math.abs(v) < 1e9 ? String.valueOf((long) v) : String.valueOf(v));
        }
        return s.toString();
    }

    private static String first(String a, String b) { return a == null || a.isEmpty() ? (b == null ? "" : b) : a; }

    private static String clip(String s) {
        s = s == null ? "" : s.trim();
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT - 1).trim() + "…";
    }
}
