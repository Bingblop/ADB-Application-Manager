package com.bloatware.bingblop;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The fonts of one FlipFont package, made from the fonts the user picked in the Custom Fonts tab: a Regular face and, when one is given,
 * a Bold face.
 *
 * <p>The page's fontMakerBuild bridge call is for MainActivity to turn into a {@link Request}, run {@link #build} on its worker thread and
 * hand the fonts that come back to {@link FontPackage}. Each face goes through the same steps: the file is read; a variable font is made static at
 * the location the page chose, or else at the one {@link FontInstancer#guessRegular} / {@link FontInstancer#guessBold} picks (so one file
 * can give both faces); PostScript outlines become TrueType ones ({@link CffToGlyf}); the emoji font is merged in ({@link EmojiMerge});
 * then the font is renamed to the display name with the Regular or Bold style bits that Android and font menus read, written, parsed again
 * and every glyph decoded before its bytes are handed back. Without a Bold face the package holds only the Regular font and Android
 * draws bold text by thickening it. The sources are not changed. Progress is reported under the page's stage names (read, instance,
 * convert, emoji), each stage on one scale across both faces. The input is untrusted: every failure ends in a {@link Sfnt.FontException}
 * whose message can be shown, and one that comes from a face says which. Pure Java, no Android classes.
 */
public final class FontBuilder {

    private FontBuilder() {}

    /** The stages {@link #build} reports, as the page names them; MainActivity reports package, sign and save after it. */
    public static final String READ = "read", INSTANCE = "instance", CONVERT = "convert", EMOJI = "emoji";
    /** The last step of each face (rename, write, parse again, decode every glyph): a key of {@link Result#timings}, not a progress stage. */
    public static final String WRITE = "write";

    public static final String W_BOLD_NOT_BOLDER = "The Bold font is no heavier than the Regular one, so bold text will look like the rest of the text.";

    /** Names the build writes, and the ones it leaves out because they would name the source family or a variable font. */
    static final int[] NAMES_REPLACED = { 1, 2, 3, 4, 6, 16, 17, 18, 20, 21, 22, 25 };
    /** Names kept whole when the name table would be too big: the copyright, version and license, and the ones the build writes. */
    static final int[] NAMES_KEPT = { 0, 1, 2, 3, 4, 5, 6, 13, 14 };
    /** A name table's string storage and its records are reached by 16-bit offsets. */
    static final int NAME_STORAGE = 0xFFFF, NAME_RECORDS = (0xFFFF - 6) / 12;
    /** Variation tables that mean nothing in a font without fvar, and PostScript outlines a font with glyf does not need. */
    static final String[] STATIC_DROPPED = { "gvar", "cvar", "avar", "HVAR", "VVAR", "MVAR", "CFF ", "CFF2", "VORG" };

    /** One face's source: a font file and, for a variable font, where to make it static. */
    public static final class Face {
        public byte[] data;
        /** Axis tag -> value (see {@link FontInstancer#userCoords(VarStore.Fvar, Map)}); null: the guess for this face. Ignored for a static font. */
        public Map<String, ? extends Number> location;

        public Face(byte[] data, Map<String, ? extends Number> location) {
            this.data = data;
            this.location = location;
        }
    }

    /** What the page asked for. */
    public static final class Request {
        /** The display name; it becomes the family name (cleaned as {@link FontPackage#cleanName} cleans it). */
        public String name;
        public Face regular;
        /** May be null: the package then has no Bold font. Its data may be the Regular's (another location of the same variable font). */
        public Face bold;
        /** A COLR emoji font merged into both faces, or null. */
        public byte[] emoji;
        /** Emoji size on top of the units-per-em ratio, {@link EmojiMerge#MIN_SCALE} to {@link EmojiMerge#MAX_SCALE}. */
        public double emojiScale = 1.0;
        /** Keep a variable font's TrueType instructions (see {@link FontInstancer.Options#keepHinting}); static fonts always keep theirs. */
        public boolean keepHinting;
        /** When the font is made (ms since 1970), for head.modified and the unique name; 0 is now. */
        public long time;
    }

    /** The finished fonts. */
    public static final class Result {
        /** The family name the fonts carry (the cleaned display name). */
        public final String family;
        /** The font files; bold is null when the request had no Bold face. */
        public byte[] regular, bold;
        /** Where a variable source was made static (tag -> value in fvar order), or null for a static source. */
        public LinkedHashMap<String, Double> regularLocation, boldLocation;
        /** Sentences for the page about what the fonts could not keep, each once. */
        public final List<String> warnings = new ArrayList<String>();
        /** Milliseconds spent per step (read, instance, convert, emoji, write), both faces together. */
        public final LinkedHashMap<String, Long> timings = new LinkedHashMap<String, Long>();

        Result(String family) { this.family = family; }
    }

    // ---------------------------------------------------------------------------------------------
    // The build
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the package fonts; see the class comment. Codes besides those of the engines it calls: "bad_name" (no display name), "no_regular",
     * "bad_request" (an emoji size out of range), "too_big" (a finished font over {@link Sfnt#MAX_FONT_BYTES}), "no_memory" and "cancelled".
     */
    public static Result build(Request r, FontProgress progress) throws Sfnt.FontException {
        if (r == null) throw new IllegalArgumentException("build: no request");
        FontProgress p = progress == null ? FontProgress.NONE : progress;
        String family = FontPackage.cleanName(r.name);
        if (r.regular == null || r.regular.data == null) throw new Sfnt.FontException("no_regular", "Choose the Regular font first.");
        if (r.emoji != null && !(r.emojiScale >= EmojiMerge.MIN_SCALE && r.emojiScale <= EmojiMerge.MAX_SCALE))
            throw new Sfnt.FontException("bad_request", "The emoji size must be between 80% and 125%.");
        try {
            return run(r, family, p);
        } catch (RuntimeException e) {
            // A check an engine missed: still a refused font for the app, never a crash (the fuzz test fails on any such cause)
            Sfnt.FontException fe = new Sfnt.FontException("These fonts could not be made into a font package.");
            fe.initCause(e);
            throw fe;
        } catch (OutOfMemoryError e) {
            throw new Sfnt.FontException("no_memory", "The phone ran out of memory while making this font. Try a smaller font, or leave the emoji out.");
        }
    }

    private static Result run(Request r, String family, FontProgress p) throws Sfnt.FontException {
        Job j = new Job(r, family, p);
        Result res = new Result(family);
        boolean hasBold = r.bold != null && r.bold.data != null;
        boolean boldShared = hasBold && (r.bold.data == r.regular.data || Arrays.equals(r.bold.data, r.regular.data));
        int files = 1 + (hasBold && !boldShared ? 1 : 0) + (r.emoji != null ? 1 : 0);

        long t = System.nanoTime();
        p.step(READ, 0, files);
        Sfnt reg = parse(r.regular.data, "Regular font");
        p.step(READ, 1, files);
        Sfnt bold = !hasBold ? null : boldShared ? reg : parse(r.bold.data, "Bold font");
        if (hasBold && !boldShared) p.step(READ, 2, files);
        Sfnt emoji = r.emoji == null ? null : parseEmoji(r.emoji);
        p.step(READ, files, files);
        j.took(READ, t);

        j.plan(reg, emoji != null);
        if (bold != null) j.plan(bold, emoji != null);

        Built a = face(j, reg, r.regular, false, emoji, "Regular font");
        res.regular = a.bytes;
        res.regularLocation = a.location;
        if (bold != null) {
            Built b = face(j, bold, r.bold, true, emoji, "Bold font");
            res.bold = b.bytes;
            res.boldLocation = b.location;
            if (b.weight <= a.weight) j.warnings.add(W_BOLD_NOT_BOLDER);
        }
        res.warnings.addAll(j.warnings);
        res.timings.putAll(j.timings);
        return res;
    }

    /** A finished face and what the page may want to know about it. */
    private static final class Built {
        byte[] bytes;
        LinkedHashMap<String, Double> location;
        int weight;                                     // the weight class of the source style, before it is set to 400 / 700
    }

    /** One face: static, TrueType, with the emoji, renamed, written and checked. */
    private static Built face(Job j, Sfnt src, Face face, boolean bold, Sfnt emoji, String label) throws Sfnt.FontException {
        try {
            Built out = new Built();
            boolean italic = italic(src);
            Sfnt f;
            long t = System.nanoTime();
            if (src.isVariable()) {
                VarStore.Fvar fv = VarStore.Fvar.read(src);
                double[] user = face.location != null ? FontInstancer.userCoords(fv, face.location)
                        : bold ? FontInstancer.guessBold(fv) : FontInstancer.guessRegular(fv);
                if (user == null) user = FontInstancer.defaults(fv);           // a Bold without a weight axis: warned about as not bolder
                FontInstancer.Options o = new FontInstancer.Options();
                o.keepHinting = j.r.keepHinting;
                o.progress = j.steps(INSTANCE);
                f = FontInstancer.instance(src, user, o);
                j.warnings.addAll(o.warnings);
                out.location = FontInstancer.location(fv, user);
                int ital = fv.axisIndex("ital"), slnt = fv.axisIndex("slnt");
                italic |= ital >= 0 && user[ital] >= 0.5 || slnt >= 0 && user[slnt] != 0;
                j.took(INSTANCE, t);
            } else if (!src.has("glyf")) {
                f = CffToGlyf.convert(src, null, j.steps(CONVERT));
                j.took(CONVERT, t);
            } else {
                f = new Sfnt(Sfnt.TRUETYPE);
                for (String tag : src.tags()) f.put(tag, src.table(tag));     // shared with the source: nothing below writes into a table
                for (String tag : STATIC_DROPPED) f.remove(tag);
            }
            if (emoji != null) {
                t = System.nanoTime();
                FontProgress.Check.cancel(j.p);
                EmojiMerge.Options o = new EmojiMerge.Options();
                o.scale = j.r.emojiScale;
                o.progress = j.steps(EMOJI);
                EmojiMerge.Result m = EmojiMerge.merge(f, emoji, o);
                j.warnings.addAll(m.warnings);
                f = m.font;
                j.took(EMOJI, t);
            }
            t = System.nanoTime();
            FontProgress.Check.cancel(j.p);
            out.weight = weight(f);
            style(f, j.family, bold, italic, j.time);
            byte[] bytes = f.toBytes();
            f = null;
            if (bytes.length > Sfnt.MAX_FONT_BYTES)
                throw new Sfnt.FontException("too_big", "The finished font would be over " + (Sfnt.MAX_FONT_BYTES >> 20) + " MB, more than a font package can hold.");
            check(bytes, j.p);
            out.bytes = bytes;
            j.took(WRITE, t);
            return out;
        } catch (Sfnt.FontException e) {
            throw about(label, e);
        } catch (RuntimeException e) {
            Sfnt.FontException fe = new Sfnt.FontException(label + ": this font could not be made into a font package.");
            fe.initCause(e);
            throw fe;
        }
    }

    private static Sfnt parse(byte[] data, String label) throws Sfnt.FontException {
        try {
            return Sfnt.parse(data);
        } catch (Sfnt.FontException e) {
            throw about(label, e);
        }
    }

    /**
     * The emoji font, checked as EmojiMerge checks it before any face is made: a bitmap one (CBDT or sbix, often without outlines) is
     * refused with code "bitmap_emoji" as FontInspect refuses it, a font without COLR with "not_emoji".
     */
    private static Sfnt parseEmoji(byte[] data) throws Sfnt.FontException {
        try {
            Sfnt f;
            try {
                f = Sfnt.parse(data);
            } catch (Sfnt.FontException e) {
                if ("no_outlines".equals(e.code)) FontInspect.inspect(data);
                throw e;
            }
            EmojiMerge.checkEmoji(f);
            return f;
        } catch (Sfnt.FontException e) {
            throw about("Emoji font", e);
        }
    }

    /**
     * The exception with the face named in front of its sentence ("Bold font: ..."). Cancel stays as it is, and so does a sentence that
     * names the emoji font itself (EmojiMerge's, which may come while a face is made).
     */
    private static Sfnt.FontException about(String label, Sfnt.FontException e) {
        String m = e.getMessage();
        if ("cancelled".equals(e.code) || m.startsWith(label + ": ") || m.contains("emoji font")) return e;
        Sfnt.FontException w = new Sfnt.FontException(e.code, label + ": " + m);
        if (e.getCause() != null) w.initCause(e.getCause());
        return w;
    }

    // ---------------------------------------------------------------------------------------------
    // Names and style bits
    // ---------------------------------------------------------------------------------------------

    /** Whether the source says it is italic (OS/2 fsSelection ITALIC, or head.macStyle italic). */
    static boolean italic(Sfnt f) throws Sfnt.FontException {
        byte[] os2 = f.table("OS/2");
        if (os2 != null && os2.length >= 64 && (Sfnt.u16(os2, 62) & 1) != 0) return true;
        return (Sfnt.u16(f.table("head"), 44) & 2) != 0;
    }

    /** OS/2 usWeightClass, or what head.macStyle says when there is no OS/2. */
    static int weight(Sfnt f) throws Sfnt.FontException {
        byte[] os2 = f.table("OS/2");
        if (os2 != null && os2.length >= 6) return Sfnt.u16(os2, 4);
        return (Sfnt.u16(f.table("head"), 44) & 1) != 0 ? 700 : 400;
    }

    /**
     * Renames the font to {@code family} with the style Regular, Bold, Italic or Bold Italic (names 1, 2, 4, 6 and a unique name 3; the
     * typographic, WWS and variation names are left out, every other name kept), sets OS/2 usWeightClass to 400 / 700 and the fsSelection
     * and macStyle bits to match, stamps head.modified and removes DSIG, whose signature no longer holds. Tables are replaced, never
     * written into, so a table shared with the source stays as it was.
     */
    static void style(Sfnt f, String family, boolean bold, boolean italic, long time) throws Sfnt.FontException {
        String style = bold ? (italic ? "Bold Italic" : "Bold") : (italic ? "Italic" : "Regular");
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        Map<Integer, String> names = f.names();
        for (int id : NAMES_REPLACED) names.remove(id);
        names.put(1, family);
        names.put(2, style);
        names.put(3, family + " " + style + "; " + day.format(new Date(time)));
        names.put(4, family + " " + style);
        names.put(6, postScriptName(family, style));
        if (!names.containsKey(5)) names.put(5, "Version 1.000");
        fitNames(names);
        f.put("name", Sfnt.buildName(names));

        byte[] os2 = f.table("OS/2");
        if (os2 != null && os2.length >= 6) {
            os2 = os2.clone();
            Sfnt.w16(os2, 4, bold ? 700 : 400);
            if (os2.length >= 64) {
                int fs = Sfnt.u16(os2, 62) & ~(0x0001 | 0x0020 | 0x0040);      // ITALIC, BOLD, REGULAR
                if (italic) fs |= 0x0001;
                if (bold) fs |= 0x0020;
                if (!bold && !italic) fs |= 0x0040;
                Sfnt.w16(os2, 62, fs);
            }
            f.put("OS/2", os2);
        }
        byte[] head = f.table("head").clone();
        Sfnt.w16(head, 44, (Sfnt.u16(head, 44) & ~3) | (bold ? 1 : 0) | (italic ? 2 : 0));
        long since1904 = Math.floorDiv(time, 1000L) + 2082844800L;
        Sfnt.w32(head, 28, (int) (since1904 >>> 32));
        Sfnt.w32(head, 32, (int) since1904);
        f.put("head", head);
        f.remove("DSIG");
    }

    /** The PostScript name: the family's letters and digits folded to Latin as the package suffix folds them, "-", the style without spaces. */
    static String postScriptName(String family, String style) {
        String base;
        try {
            base = FontPackage.fontFileBase(FontPackage.suffixFor(family, ""));
        } catch (Sfnt.FontException e) {
            base = "Font";
        }
        return base + "-" + style.replace(" ", "");
    }

    /**
     * Makes the names fit one name table, whose record list and string storage are reached by 16-bit offsets: while they do not, the
     * longest name the font can do without is left out, and once only {@link #NAMES_KEPT} remain, the longest of the source's texts (a
     * license, say) is cut in half. Real fonts are far below both limits; a hostile one may have 65,535 names.
     */
    static void fitNames(Map<Integer, String> names) {
        long size = storage(names), records = records(names);
        if (size <= NAME_STORAGE && records <= NAME_RECORDS) return;
        List<Map.Entry<Integer, String>> optional = new ArrayList<Map.Entry<Integer, String>>();
        for (Map.Entry<Integer, String> e : names.entrySet()) if (!kept(e.getKey())) optional.add(e);
        Collections.sort(optional, new Comparator<Map.Entry<Integer, String>>() {
            @Override public int compare(Map.Entry<Integer, String> a, Map.Entry<Integer, String> b) { return b.getValue().length() - a.getValue().length(); }
        });
        for (Map.Entry<Integer, String> e : optional) {
            if (size <= NAME_STORAGE && records <= NAME_RECORDS) return;
            String s = names.remove(e.getKey());
            size -= storage(s);
            records -= Sfnt.isAscii(s) ? 2 : 1;
        }
        while (size > NAME_STORAGE) {
            Integer cut = null;
            for (Map.Entry<Integer, String> e : names.entrySet()) {
                int id = e.getKey();
                if (id != 1 && id != 2 && id != 3 && id != 4 && id != 6 && (cut == null || e.getValue().length() > names.get(cut).length())) cut = id;
            }
            if (cut == null) return;                                  // only the build's own short names: cannot happen
            String s = names.get(cut);
            int half = s.offsetByCodePoints(0, s.codePointCount(0, s.length()) / 2);
            String t = s.substring(0, half) + "\u2026";
            names.put(cut, t);
            size += storage(t) - storage(s);
        }
    }

    private static boolean kept(int id) {
        for (int k : NAMES_KEPT) if (k == id) return true;
        return false;
    }

    /** Bytes of string storage {@link Sfnt#buildName} writes: UTF-16 for Windows, plus a Mac Roman copy of plain ASCII text. */
    static long storage(Map<Integer, String> names) {
        long n = 0;
        for (String s : names.values()) n += storage(s);
        return n;
    }

    private static long storage(String s) {
        return s == null || s.isEmpty() ? 0 : 2L * s.length() + (Sfnt.isAscii(s) ? s.length() : 0);
    }

    /** Records {@link Sfnt#buildName} writes: one Windows record per name and a Mac one for plain ASCII. */
    private static long records(Map<Integer, String> names) {
        long n = 0;
        for (String s : names.values()) if (s != null && !s.isEmpty()) n += Sfnt.isAscii(s) ? 2 : 1;
        return n;
    }

    // ---------------------------------------------------------------------------------------------
    // The final check
    // ---------------------------------------------------------------------------------------------

    /** Glyphs one composite may visit when it is drawn: real ones visit a few dozen; past this, drawing it would stall the phone's text. */
    static final int MAX_VISITS = 1 << 14;

    /**
     * The finished font read back from its bytes: TrueType outlines, every glyph decodes, every component names a glyph, none loops, and
     * none nests deeper than {@link Glyf#MAX_DEPTH} or takes more than {@link #MAX_VISITS} glyphs or {@link Glyf#MAX_POINTS} x 4 points to draw.
     */
    static void check(byte[] bytes, FontProgress p) throws Sfnt.FontException {
        Sfnt f = Sfnt.parse(bytes);
        if (f.sfntVersion != Sfnt.TRUETYPE || !f.has("glyf")) throw new Sfnt.FontException("The finished font has no TrueType outlines.");
        Walk w = new Walk(Glyf.readAll(f));
        for (int g = 0; g < w.glyphs.length; g++) {
            if ((g & 0xFF) == 0) FontProgress.Check.cancel(p);
            w.walk(g, 0);
        }
        Glyf.Metrics.read(f);
    }

    /** What {@link #check} learns of each glyph, once: the levels it spans (1 for a simple glyph), the glyphs and points drawing it takes. */
    private static final class Walk {
        final byte[][] glyphs;
        final int[] depth;                              // 0: not seen yet; -1: being followed (seen again, it loops)
        final long[] visits, points;

        Walk(byte[][] glyphs) {
            this.glyphs = glyphs;
            depth = new int[glyphs.length];
            visits = new long[glyphs.length];
            points = new long[glyphs.length];
        }

        void walk(int g, int level) throws Sfnt.FontException {
            if (depth[g] > 0) return;
            if (depth[g] < 0 || level > Glyf.MAX_DEPTH) throw tooDeep();
            Glyf.Glyph gl = Glyf.decode(glyphs[g]);
            int d = 1;
            long v = 1, n = gl.x.length;
            if (gl.isComposite()) {
                depth[g] = -1;
                for (Glyf.Component k : gl.components) {
                    if (k.glyph >= glyphs.length) throw new Sfnt.FontException("A composite glyph names glyph " + k.glyph + ", which this font does not have.");
                    walk(k.glyph, level + 1);
                    d = Math.max(d, 1 + depth[k.glyph]);
                    v += visits[k.glyph];
                    n += points[k.glyph];
                }
                // A depth found earlier counts too, so a long chain is caught however its glyphs are numbered
                if (d > Glyf.MAX_DEPTH + 1) throw tooDeep();
                if (v > MAX_VISITS || n > Glyf.MAX_POINTS * 4L)
                    throw new Sfnt.FontException("too_complex", "A composite glyph of this font is too complex to draw.");
            }
            depth[g] = d;
            visits[g] = v;
            points[g] = n;
        }
    }

    private static Sfnt.FontException tooDeep() {
        return new Sfnt.FontException("A composite glyph of this font nests too deep (or names itself).");
    }

    // ---------------------------------------------------------------------------------------------
    // Progress
    // ---------------------------------------------------------------------------------------------

    /** One build's request, progress, warnings and timings, and how many faces go through each stage. */
    private static final class Job {
        final Request r;
        final String family;
        final FontProgress p;
        final long time;
        final LinkedHashSet<String> warnings = new LinkedHashSet<String>();
        final LinkedHashMap<String, Long> timings = new LinkedHashMap<String, Long>();
        private final Map<String, int[]> faces = new HashMap<String, int[]>();     // stage -> {faces started, faces in all}

        Job(Request r, String family, FontProgress p) {
            this.r = r;
            this.family = family;
            this.p = p;
            this.time = r.time != 0 ? r.time : System.currentTimeMillis();
        }

        /** Counts the stages a face from {@code src} goes through. */
        void plan(Sfnt src, boolean emoji) {
            count(src.isVariable() ? INSTANCE : !src.has("glyf") ? CONVERT : null);
            if (emoji) count(EMOJI);
        }

        private void count(String stage) {
            if (stage == null) return;
            int[] c = faces.get(stage);
            if (c == null) faces.put(stage, c = new int[2]);
            c[1]++;
        }

        /** The progress to give the next face's engine for {@code stage}. */
        FontProgress steps(String stage) {
            int[] c = faces.get(stage);
            if (c == null) faces.put(stage, c = new int[] { 0, 1 });
            int index = Math.min(c[0]++, c[1] - 1);
            return new Steps(p, stage, index, c[1]);
        }

        void took(String stage, long sinceNanos) {
            Long had = timings.get(stage);
            timings.put(stage, (had == null ? 0 : had) + (System.nanoTime() - sinceNanos) / 1000000L);
        }
    }

    /**
     * An engine's steps under the page's stage name, face {@code index} of {@code count} on one scale (the second face's 10 of 100 is
     * 110 of 200), so the bar never runs back. CffToGlyf's steps inside an instance count as the instance's; the layout and cmap steps the
     * engines report on the way are left out. Cancel goes straight through.
     */
    static final class Steps implements FontProgress {
        private final FontProgress to;
        private final String stage;
        private final int index, count;

        Steps(FontProgress to, String stage, int index, int count) {
            this.to = to; this.stage = stage; this.index = index; this.count = count;
        }

        @Override
        public void step(String s, int done, int total) {
            if (!stage.equals(s) && !(stage.equals(INSTANCE) && CONVERT.equals(s))) return;
            if (total <= 0) { to.step(stage, 0, 0); return; }
            long d = (long) index * total + Math.max(0, Math.min(done, total)), all = (long) count * total;
            if (all > Integer.MAX_VALUE) { d = d * 1000 / all; all = 1000; }
            to.step(stage, (int) d, (int) all);
        }

        @Override
        public boolean cancelled() { return to.cancelled(); }
    }
}
