package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EmojiSpecimen, the emoji fonts card's samples (v7.18.0). The iOS emoji file cannot be in the repository, so the colour-bitmap fonts read
 * here are built by this test: cmap format 12 (and 4), a CBLC with three strikes and index subtables of formats 1, 2, 3, 4 and 5, a CBDT
 * with images of formats 17, 18 and 19. A counting source checks what {@link EmojiSpecimen#read} asks for: the 12-byte header, the table
 * directory, cmap, CBLC, then one read per emoji, nothing else (the iOS file is read the same way over HTTP ranges, about 0.3 MB of 44 MB).
 * Broken fonts (cut short, bits flipped, counts made huge) end in emo_no_sample, never another exception and never a read past the file or
 * sized by an unchecked count. Also: Content-Range and the HTTP source's checks, the cache file, the sample string against
 * tools/make-emoji-specimens.sh and the page's FNT_EMO_SAMPLE, and the four sample fonts in assets/emoji/ covering the 16 code points.
 */
public class EmojiSpecimenTest {

    static int n = 0, fails = 0;
    static void check(String name, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + name); } }
    static void eq(String name, Object got, Object want) { n++; if (!java.util.Objects.equals(got, want)) { fails++; System.out.println("FAIL " + name + ": got <" + got + "> want <" + want + ">"); } }

    static File tmp;

    public static void main(String[] a) throws Exception {
        tmp = Files.createTempDirectory("es-").toFile();
        try {
            sample();
            readFonts();
            broken();
            fuzz(424242L, 3000);
            ranges();
            cache();
            assets();
        } finally {
            FontLibrary.deleteTree(tmp);
        }
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " EmojiSpecimenTest: " + (n - fails) + "/" + n + " checks");
        if (fails != 0) System.exit(1);
    }

    static String code(Throwing t) {
        try {
            t.run();
            return "none";
        } catch (EmojiSpecimen.SpecimenException e) {
            return e.code;
        } catch (Throwable e) {
            return "threw " + e;
        }
    }
    interface Throwing { void run() throws Exception; }

    static String repo(String rel) throws Exception {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 6 && d != null; i++, d = d.getParentFile()) {
            File f = new File(d, rel);
            if (f.exists()) return f.getPath();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // The sample
    // ---------------------------------------------------------------------------------------------

    static void sample() throws Exception {
        String s = EmojiSpecimen.SAMPLE;
        eq("16 code points and the heart's VS16", s.codePointCount(0, s.length()), 17);
        List<Integer> firsts = new ArrayList<Integer>();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp != 0xFE0F) firsts.add(cp);
        }
        List<Integer> want = new ArrayList<Integer>();
        for (int cp : EmojiSpecimen.CODEPOINTS) want.add(cp);
        eq("CODEPOINTS are the sample's", firsts, want);
        eq("ids", EmojiSpecimen.SPECIMEN_IDS.toString(), "[noto, twemoji, blobmoji, openmoji]");

        String script = repo("tools/make-emoji-specimens.sh");
        check("tools/make-emoji-specimens.sh is found", script != null);
        if (script != null) {
            Matcher m = Pattern.compile("(?m)^SAMPLE='([^']*)'").matcher(new String(Files.readAllBytes(new File(script).toPath()), StandardCharsets.UTF_8));
            check("the script has SAMPLE='...'", m.find());
            eq("the script's SAMPLE", m.group(1), s);
        }
        String page = repo("assets/index.html");
        check("assets/index.html is found", page != null);
        if (page != null) {
            String p = new String(Files.readAllBytes(new File(page).toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("FNT_EMO_SAMPLE\\s*=\\s*(['\"`])((?:\\\\.|(?!\\1).)*)\\1").matcher(p);
            boolean found = m.find();
            check("the page has FNT_EMO_SAMPLE", found);
            if (found) eq("the page's FNT_EMO_SAMPLE", jsString(m.group(2)), s);
        }
        eq("emo_no_sample sentence", new EmojiSpecimen.SpecimenException("emo_no_sample").getMessage(), "The sample could not be read from the emoji font.");
        eq("emo_changed sentence", new EmojiSpecimen.SpecimenException("emo_changed").getMessage(),
                "The iOS emoji file at its address is not the expected one, so its sample was not read. Update the app.");
    }

    /** A JavaScript string literal's text with its escapes (\\uXXXX, \\u{...}, \\x.., \\n ...) undone. */
    static String jsString(String lit) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lit.length(); i++) {
            char c = lit.charAt(i);
            if (c != '\\' || i + 1 >= lit.length()) { b.append(c); continue; }
            char e = lit.charAt(++i);
            if (e == 'u' && i + 1 < lit.length() && lit.charAt(i + 1) == '{') {
                int end = lit.indexOf('}', i);
                b.appendCodePoint(Integer.parseInt(lit.substring(i + 2, end), 16));
                i = end;
            } else if (e == 'u') {
                b.append((char) Integer.parseInt(lit.substring(i + 1, i + 5), 16));
                i += 4;
            } else if (e == 'x') {
                b.append((char) Integer.parseInt(lit.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
            }
        }
        return b.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // A colour-bitmap font, made here
    // ---------------------------------------------------------------------------------------------

    /** One index subtable: the glyphs it covers, its index format and image format, the glyphs it leaves without an image. */
    static final class Sub {
        final int first, last, indexFormat, imageFormat;
        final int[] without;
        Sub(int first, int last, int indexFormat, int imageFormat, int... without) {
            this.first = first; this.last = last; this.indexFormat = indexFormat; this.imageFormat = imageFormat; this.without = without;
        }
        boolean has(int g) { for (int w : without) if (w == g) return false; return true; }
    }

    /** What to build: the strikes' ppem, their subtables, the cmap (format 12 or 4) and the code points it leaves out. */
    static final class Spec {
        int[] ppems = { 40, 72, 136 };
        Sub[] subs = { new Sub(1, 8, 1, 17), new Sub(9, 16, 3, 18) };
        boolean format12 = true;
        int[] cmapWithout = {};
    }

    /** Glyph g (1..16) is CODEPOINTS[g - 1]. */
    static int gid(int cp) { for (int i = 0; i < EmojiSpecimen.CODEPOINTS.length; i++) if (EmojiSpecimen.CODEPOINTS[i] == cp) return i + 1; return 0; }

    /** A PNG-looking image that says which strike and glyph it is (the reader checks only the signature). */
    static byte[] png(int strike, int g, boolean constant) {
        int len = constant ? 48 : 40 + g;
        byte[] b = new byte[len];
        byte[] sig = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };
        System.arraycopy(sig, 0, b, 0, 8);
        b[8] = (byte) strike;
        b[9] = (byte) g;
        for (int i = 10; i < len; i++) b[i] = (byte) (i * 7 + g);
        return b;
    }

    static final class Buf {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        Buf u8(int v) { o.write(v); return this; }
        Buf u16(int v) { o.write(v >>> 8); o.write(v); return this; }
        Buf u32(long v) { u16((int) (v >>> 16)); u16((int) v); return this; }
        Buf bytes(byte[] b) { o.write(b, 0, b.length); return this; }
        Buf zeros(int k) { for (int i = 0; i < k; i++) o.write(0); return this; }
        Buf pad4() { while (o.size() % 4 != 0) o.write(0); return this; }
        int size() { return o.size(); }
        byte[] get() { return o.toByteArray(); }
    }

    static boolean constant(Sub s) { return s.indexFormat == 2 || s.indexFormat == 5; }

    static byte[] record(int imageFormat, byte[] png) {
        Buf r = new Buf();
        if (imageFormat == 17) r.u8(136).u8(136).u8(0).u8(117).u8(136);           // smallGlyphMetrics
        else if (imageFormat == 18) r.u8(136).u8(136).u8(0).u8(117).u8(136).u8(0).u8(0).u8(136);   // bigGlyphMetrics
        return r.u32(png.length).bytes(png).get();
    }

    static byte[] font(Spec sp) {
        // CBDT: every strike's images, subtable by subtable
        Buf cbdt = new Buf().u16(3).u16(0);
        int[][] dataOffset = new int[sp.ppems.length][sp.subs.length];
        int[][][] glyphOff = new int[sp.ppems.length][sp.subs.length][];
        for (int s = 0; s < sp.ppems.length; s++) {
            for (int k = 0; k < sp.subs.length; k++) {
                Sub sub = sp.subs[k];
                dataOffset[s][k] = cbdt.size();
                int[] offs = new int[sub.last - sub.first + 2];
                for (int g = sub.first; g <= sub.last; g++) {
                    offs[g - sub.first] = cbdt.size() - dataOffset[s][k];
                    boolean present = sub.has(g) || constant(sub) && sub.indexFormat == 2;   // format 2 cannot leave a glyph out
                    if (present) cbdt.bytes(record(sub.imageFormat, png(s, g, constant(sub))));
                }
                offs[offs.length - 1] = cbdt.size() - dataOffset[s][k];
                glyphOff[s][k] = offs;
            }
        }
        // CBLC: header, the size records, then each strike's subtable array and subtables
        Buf tail = new Buf();
        int head = 8 + 48 * sp.ppems.length;
        int[] arrayAt = new int[sp.ppems.length], arraySize = new int[sp.ppems.length];
        for (int s = 0; s < sp.ppems.length; s++) {
            arrayAt[s] = head + tail.size();
            Buf subs = new Buf();
            int[] subAt = new int[sp.subs.length];
            int base = 8 * sp.subs.length;
            for (int k = 0; k < sp.subs.length; k++) {
                Sub sub = sp.subs[k];
                subAt[k] = base + subs.size();
                subs.u16(sub.indexFormat).u16(sub.imageFormat).u32(dataOffset[s][k]);
                int[] offs = glyphOff[s][k];
                switch (sub.indexFormat) {
                    case 1: for (int v : offs) subs.u32(v); break;
                    case 3: for (int v : offs) subs.u16(v); subs.pad4(); break;
                    case 2: subs.u32(record(sub.imageFormat, png(s, sub.first, true)).length).zeros(8); break;
                    case 4: {
                        List<int[]> pairs = new ArrayList<int[]>();
                        for (int g = sub.first; g <= sub.last; g++) if (sub.has(g)) pairs.add(new int[] { g, offs[g - sub.first] });
                        subs.u32(pairs.size());
                        for (int[] p : pairs) subs.u16(p[0]).u16(p[1]);
                        subs.u16(0).u16(offs[offs.length - 1]);
                        break;
                    }
                    case 5: {
                        List<Integer> gs = new ArrayList<Integer>();
                        for (int g = sub.first; g <= sub.last; g++) if (sub.has(g)) gs.add(g);
                        subs.u32(record(sub.imageFormat, png(s, sub.first, true)).length).zeros(8).u32(gs.size());
                        for (int g : gs) subs.u16(g);
                        subs.pad4();
                        break;
                    }
                    default: throw new IllegalArgumentException("index format " + sub.indexFormat);
                }
            }
            Buf arr = new Buf();
            for (int k = 0; k < sp.subs.length; k++) arr.u16(sp.subs[k].first).u16(sp.subs[k].last).u32(subAt[k]);
            arraySize[s] = arr.size() + subs.size();
            tail.bytes(arr.get()).bytes(subs.get());
        }
        Buf cblc = new Buf().u16(3).u16(0).u32(sp.ppems.length);
        for (int s = 0; s < sp.ppems.length; s++) {
            cblc.u32(arrayAt[s]).u32(arraySize[s]).u32(sp.subs.length).u32(0);
            cblc.zeros(12).zeros(12);                                                   // hori, vert line metrics
            cblc.u16(1).u16(16).u8(sp.ppems[s]).u8(sp.ppems[s]).u8(32).u8(1);
        }
        cblc.bytes(tail.get());
        // cmap: one subtable, format 12 (3, 10) or format 4 (3, 1)
        Buf cmap = new Buf().u16(0).u16(1).u16(3).u16(sp.format12 ? 10 : 1).u32(12);
        List<int[]> map = new ArrayList<int[]>();
        for (int cp : EmojiSpecimen.CODEPOINTS) {
            boolean out = false;
            for (int w : sp.cmapWithout) out |= w == cp;
            if (!out && (sp.format12 || cp <= 0xffff)) map.add(new int[] { cp, gid(cp) });
        }
        map.sort((x, y) -> Integer.compare(x[0], y[0]));
        if (sp.format12) {
            cmap.u16(12).u16(0).u32(16 + 12 * map.size()).u32(0).u32(map.size());
            for (int[] m : map) cmap.u32(m[0]).u32(m[0]).u32(m[1]);
        } else {
            int segs = map.size() + 1, len = 16 + 8 * segs;
            cmap.u16(4).u16(len).u16(0).u16(2 * segs).u16(0).u16(0).u16(0);
            for (int[] m : map) cmap.u16(m[0]);
            cmap.u16(0xffff).u16(0);
            for (int[] m : map) cmap.u16(m[0]);
            cmap.u16(0xffff);
            for (int[] m : map) cmap.u16((m[1] - m[0]) & 0xffff);
            cmap.u16(1);
            for (int i = 0; i < segs; i++) cmap.u16(0);
        }
        // the file: CBDT, CBLC, cmap and a head table, each 4-byte aligned
        byte[][] tables = { cbdt.get(), cblc.get(), cmap.get(), new byte[54] };
        String[] tags = { "CBDT", "CBLC", "cmap", "head" };
        Buf f = new Buf().u32(0x00010000).u16(tables.length).u16(64).u16(2).u16(0);
        int at = 12 + 16 * tables.length;
        int[] offs = new int[tables.length];
        for (int i = 0; i < tables.length; i++) { offs[i] = at; at += (tables[i].length + 3) & ~3; }
        for (int i = 0; i < tables.length; i++) f.bytes(tags[i].getBytes(StandardCharsets.ISO_8859_1)).u32(0).u32(offs[i]).u32(tables[i].length);
        for (byte[] t : tables) f.bytes(t).pad4();
        return f.get();
    }

    /** The table directory's (offset, length) of a tag in a font made here. */
    static int[] table(byte[] font, String tag) {
        int num = ((font[4] & 0xff) << 8) | (font[5] & 0xff);
        for (int i = 0; i < num; i++) {
            int o = 12 + 16 * i;
            if (new String(font, o, 4, StandardCharsets.ISO_8859_1).equals(tag)) return new int[] { be32(font, o + 8), be32(font, o + 12) };
        }
        return null;
    }

    static int be32(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }
    static void put32(byte[] b, int o, long v) { b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v; }
    static void put16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }

    /** A source over bytes that counts what is asked of it and refuses a read outside them (as a file or an HTTP range would). */
    static final class Counting implements EmojiSpecimen.Source {
        final byte[] data;
        final List<long[]> reads = new ArrayList<long[]>();
        long bytes = 0, maxLen = 0;
        Counting(byte[] data) { this.data = data; }
        @Override public byte[] read(long offset, int length) throws IOException {
            reads.add(new long[] { offset, length });
            bytes += length;
            maxLen = Math.max(maxLen, length);
            if (offset < 0 || length < 0 || offset + length > data.length) throw new IOException("read past the file: " + offset + "+" + length);
            return Arrays.copyOfRange(data, (int) offset, (int) offset + length);
        }
    }

    static String cps(List<EmojiSpecimen.Image> images) {
        StringBuilder b = new StringBuilder();
        for (EmojiSpecimen.Image i : images) b.append(b.length() > 0 ? " " : "").append(Integer.toHexString(i.cp));
        return b.toString();
    }

    static final String ALL = "1f600 1f602 1f970 1f60e 1f973 1f62d 1f44d 1f44b 2764 1f525 1f389 1f436 1f984 1f355 1f680 1f308";

    static void readFonts() throws Exception {
        Spec sp = new Spec();
        byte[] f = font(sp);
        Counting c = new Counting(f);
        List<EmojiSpecimen.Image> im = EmojiSpecimen.read(c, f.length);
        eq("16 images, in sample order", cps(im), ALL);
        boolean right = true;
        for (EmojiSpecimen.Image i : im) right &= Arrays.equals(i.png, png(1, gid(i.cp), false));
        check("every image is the 72 ppem strike's (nearest 96 of those >= 64)", right);
        // the reads: header, directory, cmap, CBLC, then one per emoji
        eq("reads", c.reads.size(), 4 + 16);
        int[] cmap = table(f, "cmap"), cblc = table(f, "CBLC"), cbdt = table(f, "CBDT");
        eq("read 1: the header", Arrays.toString(c.reads.get(0)), "[0, 12]");
        eq("read 2: the directory", Arrays.toString(c.reads.get(1)), "[12, 64]");
        eq("read 3: cmap", Arrays.toString(c.reads.get(2)), "[" + cmap[0] + ", " + cmap[1] + "]");
        eq("read 4: CBLC", Arrays.toString(c.reads.get(3)), "[" + cblc[0] + ", " + cblc[1] + "]");
        long glyphBytes = 0;
        boolean inCbdt = true;
        for (int i = 4; i < c.reads.size(); i++) {
            long[] r = c.reads.get(i);
            glyphBytes += r[1];
            inCbdt &= r[0] >= cbdt[0] + 4 && r[0] + r[1] <= cbdt[0] + cbdt[1];
        }
        check("glyph reads are inside CBDT", inCbdt);
        long want = 0;
        for (int g = 1; g <= 16; g++) want += record(g <= 8 ? 17 : 18, png(1, g, false)).length;
        eq("glyph reads are exactly the 16 records", glyphBytes, want);
        eq("nothing else read", c.bytes, 12 + 64 + cmap[1] + cblc[1] + want);
        check("the head table is never read", c.bytes < f.length);

        // the strike: nearest 96 of those at least 64; else the largest
        sp = new Spec();
        sp.ppems = new int[] { 160, 109, 64 };
        im = EmojiSpecimen.read(new Counting(font(sp)), font(sp).length);
        check("109 is nearer 96 than 64 and 160", Arrays.equals(im.get(0).png, png(1, 1, false)));
        sp.ppems = new int[] { 20, 48, 32 };
        im = EmojiSpecimen.read(new Counting(font(sp)), font(sp).length);
        check("none at 64: the largest (48)", Arrays.equals(im.get(0).png, png(1, 1, false)));
        sp.ppems = new int[] { 137 };
        im = EmojiSpecimen.read(new Counting(font(sp)), font(sp).length);
        eq("one strike (137, as the iOS file)", im.size(), 16);
        sp.ppems = new int[] { 88, 104 };
        im = EmojiSpecimen.read(new Counting(font(sp)), font(sp).length);
        check("two as near: the first", Arrays.equals(im.get(0).png, png(0, 1, false)));

        // the other index and image formats
        sp = new Spec();
        sp.subs = new Sub[] { new Sub(1, 5, 2, 19), new Sub(6, 11, 4, 17, 8), new Sub(12, 16, 5, 18, 14) };
        f = font(sp);
        c = new Counting(f);
        im = EmojiSpecimen.read(c, f.length);
        eq("index formats 2, 4, 5 and image 19 (glyphs 8 and 14 left out)", cps(im), ALL.replace(" 1f44b", "").replace(" 1f355", ""));
        right = true;
        for (EmojiSpecimen.Image i : im) { int g = gid(i.cp); right &= Arrays.equals(i.png, png(1, g, g <= 5 || g >= 12)); }
        check("their images", right);
        eq("their reads", c.reads.size(), 4 + 14);
        // a format 1 / 3 subtable leaves a glyph out with two equal offsets
        sp = new Spec();
        sp.subs = new Sub[] { new Sub(1, 8, 1, 17, 2, 3), new Sub(9, 16, 3, 18, 16) };
        im = EmojiSpecimen.read(new Counting(font(sp)), font(sp).length);
        eq("format 1 / 3 without glyphs 2, 3, 16", cps(im), ALL.replace(" 1f602", "").replace(" 1f970", "").replace(" 1f308", ""));
        // a subtable array that does not cover a glyph, a code point the cmap does not have
        sp = new Spec();
        sp.subs = new Sub[] { new Sub(1, 6, 1, 17), new Sub(9, 16, 3, 18) };
        sp.cmapWithout = new int[] { 0x1F600 };
        im = EmojiSpecimen.read(new Counting(font(sp)), font(sp).length);
        eq("missing glyphs are skipped", cps(im), "1f602 1f970 1f60e 1f973 1f62d 2764 1f525 1f389 1f436 1f984 1f355 1f680 1f308");
        // fewer than 8 is no sample
        sp = new Spec();
        sp.subs = new Sub[] { new Sub(1, 7, 1, 17) };
        final byte[] seven = font(sp);
        eq("7 of 16: no sample", code(() -> EmojiSpecimen.read(new Counting(seven), seven.length)), "emo_no_sample");
        sp.subs = new Sub[] { new Sub(1, 8, 1, 17) };
        final byte[] eight = font(sp);
        eq("8 of 16: a sample", EmojiSpecimen.read(new Counting(eight), eight.length).size(), 8);
        // cmap format 4 (BMP only): the heart alone, so no sample from the font; the map itself is right
        sp = new Spec();
        sp.format12 = false;
        final byte[] f4 = font(sp);
        eq("format 4 only: no sample", code(() -> EmojiSpecimen.read(new Counting(f4), f4.length)), "emo_no_sample");
        int[] t = table(f4, "cmap");
        int[] g4 = EmojiSpecimen.glyphs(Arrays.copyOfRange(f4, t[0], t[0] + t[1]));
        eq("format 4 maps the heart", g4[8], 9);
        eq("format 4 has no astral glyph", g4[0], 0);
        // a font that is not a colour-bitmap font, a "true" font
        byte[] tru = font(new Spec());
        put32(tru, 0, 0x74727565);
        eq("'true' fonts are read", EmojiSpecimen.read(new Counting(tru), tru.length).size(), 16);
    }

    // ---------------------------------------------------------------------------------------------
    // Broken fonts
    // ---------------------------------------------------------------------------------------------

    static String readCode(final byte[] f, final long size) {
        final Counting c = new Counting(f);
        String r = code(() -> EmojiSpecimen.read(c, size));
        for (long[] x : c.reads) if (x[0] + x[1] > size || x[1] > (2 << 20)) return "read " + x[0] + "+" + x[1] + " of " + size;
        return r;
    }

    static void broken() throws Exception {
        byte[] f = font(new Spec());
        int[] cmap = table(f, "cmap"), cblc = table(f, "CBLC"), cbdt = table(f, "CBDT");
        eq("empty", readCode(new byte[0], 0), "emo_no_sample");
        eq("not a font", readCode("<html>not a font at all</html>".getBytes(StandardCharsets.UTF_8), 30), "emo_no_sample");
        int[] cuts = { 11, 12, 30, 76, cbdt[0] + 3, cbdt[0] + cbdt[1] / 2, cblc[0] + 10, cblc[0] + cblc[1] - 1, cmap[0] + 5, f.length - 60, f.length - 1 };
        for (int cut : cuts) {
            byte[] s = Arrays.copyOf(f, cut);
            String r = readCode(s, cut);
            check("cut at " + cut + ": emo_no_sample or a sample (" + r + ")", r.equals("emo_no_sample") || r.equals("none"));
        }
        eq("cut in the middle of CBDT", readCode(Arrays.copyOf(f, cbdt[0] + cbdt[1] / 3), cbdt[0] + cbdt[1] / 3), "emo_no_sample");
        // a file that says it is larger than it is: the source fails the read past its end, as a short HTTP answer would
        eq("the size says more than there is", readCode(Arrays.copyOf(f, f.length / 2), f.length).startsWith("threw java.io.IOException"), true);

        // huge counts
        byte[] b = f.clone(); put16(b, 4, 0xffff);
        eq("numTables 65535", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put32(b, cblc[0] + 4, 0xffffffffL);
        eq("numSizes huge", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put32(b, cblc[0] + 8 + 48 + 8, 0x7fffffffL);
        eq("numberOfIndexSubTables huge", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put32(b, cblc[0] + 8 + 48, 0xfffffff0L);
        eq("indexSubTableArrayOffset huge", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put32(b, cmap[0] + 12 + 12, 0x7fffffffL);
        eq("cmap groups huge", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put32(b, cmap[0] + 12 + 4, 0x7fffffffL);
        eq("cmap length huge", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put32(b, 12 + 16 * 1 + 12, 0x7fffffffL);
        eq("CBLC length past the file", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put32(b, 12 + 16 * 0 + 8, 0x7ffffff0L);
        eq("CBDT offset past the file", readCode(b, b.length), "emo_no_sample");
        // a CBLC over the 2 MB cap is refused before it is read
        b = f.clone(); put32(b, 12 + 16 * 1 + 12, (2 << 20) + 1);
        eq("CBLC over 2 MB", readCode(b, (long) Integer.MAX_VALUE), "emo_no_sample");
        // a glyph's data length past its record, a PNG without its signature
        int sub = cblc[0] + be32(f, cblc[0] + 8 + 48);                    // strike 72's subtable array
        int first = sub + be32(f, sub + 4);                               // its format 1 subtable
        int rec = cbdt[0] + be32(f, first + 4) + be32(f, first + 8);    // glyph 1's record
        b = f.clone(); put32(b, rec + 5, 0x7fffffffL);
        eq("dataLen past the record", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); b[rec + 9] = 'X';
        eq("not a PNG", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put16(b, first, 9);
        eq("index format 9", readCode(b, b.length), "emo_no_sample");
        b = f.clone(); put16(b, first + 2, 1);
        eq("image format 1 (not colour): those glyphs have no image", readCode(b, b.length), "none");
        b = f.clone(); put32(b, first + 8 + 4, 0);
        eq("offsets going back", readCode(b, b.length), "none");
        b = f.clone(); put16(b, cblc[0], 1);
        eq("CBLC version 1", readCode(b, b.length), "emo_no_sample");

        // the source's own errors come through as they are
        final byte[] ok = f;
        EmojiSpecimen.Source down = new EmojiSpecimen.Source() {
            int k = 0;
            @Override public byte[] read(long offset, int length) throws IOException {
                if (++k == 6) throw new IOException("Connection reset");
                return Arrays.copyOfRange(ok, (int) offset, (int) offset + length);
            }
        };
        eq("a network error is the source's", code(() -> EmojiSpecimen.read(down, ok.length)), "threw java.io.IOException: Connection reset");
        EmojiSpecimen.Source changed = (offset, length) -> { throw new EmojiSpecimen.SpecimenException("emo_changed"); };
        eq("emo_changed is the source's", code(() -> EmojiSpecimen.read(changed, ok.length)), "emo_changed");
        EmojiSpecimen.Source shortRead = (offset, length) -> Arrays.copyOfRange(ok, (int) offset, (int) offset + Math.max(0, length - 1));
        eq("a short answer", code(() -> EmojiSpecimen.read(shortRead, ok.length)), "emo_no_sample");
        EmojiSpecimen.Source nul = (offset, length) -> null;
        eq("no answer", code(() -> EmojiSpecimen.read(nul, ok.length)), "emo_no_sample");
    }

    static void fuzz(long seed, int rounds) throws Exception {
        Random r = new Random(seed);
        Spec[] specs = new Spec[3];
        specs[0] = new Spec();
        specs[1] = new Spec();
        specs[1].subs = new Sub[] { new Sub(1, 5, 2, 19), new Sub(6, 11, 4, 17, 8), new Sub(12, 16, 5, 18, 14) };
        specs[2] = new Spec();
        specs[2].format12 = false;
        byte[][] fonts = { font(specs[0]), font(specs[1]), font(specs[2]) };
        int bad = 0, samples = 0;
        for (int i = 0; i < rounds; i++) {
            byte[] f = fonts[r.nextInt(fonts.length)].clone();
            int flips = 1 + r.nextInt(12);
            for (int k = 0; k < flips; k++) {
                int at = r.nextInt(f.length);
                switch (r.nextInt(4)) {
                    case 0: f[at] ^= (byte) (1 << r.nextInt(8)); break;
                    case 1: f[at] = (byte) r.nextInt(256); break;
                    case 2: f[at] = (byte) 0xff; break;
                    default: if (at + 4 <= f.length) put32(f, at, r.nextBoolean() ? 0xffffffffL : r.nextInt()); break;
                }
            }
            long size = r.nextInt(8) == 0 ? r.nextInt(f.length + 1) : f.length;
            String c = readCode(size == f.length ? f : Arrays.copyOf(f, (int) size), size);
            if (c.equals("none")) samples++;
            else if (!c.equals("emo_no_sample")) { bad++; if (bad < 5) System.out.println("FAIL fuzz round " + i + ": " + c); }
        }
        eq("fuzz: " + rounds + " broken fonts, only emo_no_sample or a sample", bad, 0);
        check("fuzz: some still give a sample (" + samples + ")", samples > 0);
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP ranges
    // ---------------------------------------------------------------------------------------------

    static void ranges() {
        eq("a range", Arrays.toString(EmojiSpecimen.contentRange("bytes 0-11/43877492")), "[0, 11, 43877492]");
        eq("spaces", Arrays.toString(EmojiSpecimen.contentRange("  bytes 12-219/43877492 ")), "[12, 219, 43877492]");
        eq("the last byte", Arrays.toString(EmojiSpecimen.contentRange("bytes 43877491-43877491/43877492")), "[43877491, 43877491, 43877492]");
        for (String bad : new String[] { null, "", "bytes */43877492", "bytes 0-11/*", "bytes 5-4/10", "bytes 0-10/10", "items 0-1/2", "bytes 0-11", "bytes -1-11/20",
                "bytes 0-1/9999999999999999", "bytes 0x0-1/2", "bytes 0-1/2, bytes 3-4/5" })
            check("not a range: " + bad, EmojiSpecimen.contentRange(bad) == null);
        long total = EmojiCatalog.byId("apple").size;
        eq("the pinned size", total, 43877492L);
        eq("a right answer", code(() -> EmojiSpecimen.checkPart(206, "bytes 12-219/43877492", 12, 208, total)), "none");
        eq("the whole file (200)", code(() -> EmojiSpecimen.checkPart(200, null, 12, 208, total)), "emo_changed");
        eq("another size", code(() -> EmojiSpecimen.checkPart(206, "bytes 12-219/43877493", 12, 208, total)), "emo_changed");
        eq("another range", code(() -> EmojiSpecimen.checkPart(206, "bytes 0-207/43877492", 12, 208, total)), "emo_changed");
        eq("a shorter range", code(() -> EmojiSpecimen.checkPart(206, "bytes 12-100/43877492", 12, 208, total)), "emo_changed");
        eq("no Content-Range", code(() -> EmojiSpecimen.checkPart(206, null, 12, 208, total)), "emo_changed");
        eq("an unknown size", code(() -> EmojiSpecimen.checkPart(206, "bytes 12-219/*", 12, 208, total)), "emo_changed");
    }

    // ---------------------------------------------------------------------------------------------
    // The cache file
    // ---------------------------------------------------------------------------------------------

    static void cache() throws Exception {
        byte[] f = font(new Spec());
        List<EmojiSpecimen.Image> im = EmojiSpecimen.read(new Counting(f), f.length);
        String sha = EmojiCatalog.byId("apple").sha256;
        JSONObject o = EmojiSpecimen.cacheJson(im, sha);
        eq("v", o.getInt("v"), 1);
        eq("sha256", o.getString("sha256"), sha);
        eq("16 images", o.getJSONArray("images").length(), 16);
        eq("cp", o.getJSONArray("images").getJSONObject(8).getInt("cp"), 0x2764);
        File c = new File(tmp, "apple.json");
        Files.write(c.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        List<EmojiSpecimen.Image> back = EmojiSpecimen.readCache(c, sha);
        check("read back", back != null && back.size() == 16);
        boolean same = back != null;
        for (int i = 0; same && i < 16; i++) same = back.get(i).cp == im.get(i).cp && Arrays.equals(back.get(i).png, im.get(i).png);
        check("the same images", same);
        check("another file's sample", EmojiSpecimen.readCache(c, "0" + sha.substring(1)) == null);
        check("no sha", EmojiSpecimen.readCache(c, null) == null);
        check("no file", EmojiSpecimen.readCache(new File(tmp, "none.json"), sha) == null);
        check("null file", EmojiSpecimen.readCache(null, sha) == null);
        String[][] damage = {
                { "v", "{\"v\":2,\"sha256\":\"" + sha + "\",\"images\":[]}" },
                { "not JSON", "{" },
                { "images not a list", "{\"v\":1,\"sha256\":\"" + sha + "\",\"images\":{}}" },
                { "a code point not of the sample", o.toString().replace("\"cp\":10084", "\"cp\":65") },
                { "a code point twice", o.toString().replace("\"cp\":128514", "\"cp\":128512") },
                { "not base64", o.toString().replaceFirst("\"b64\":\"[^\"]*\"", "\"b64\":\"***\"") },
                { "not a PNG", o.toString().replaceFirst("\"b64\":\"[^\"]*\"", "\"b64\":\"QUJDREVGR0hJSktMTU5PUA==\"") },
        };
        for (String[] d : damage) {
            Files.write(c.toPath(), d[1].getBytes(StandardCharsets.UTF_8));
            check("damaged cache refused: " + d[0], EmojiSpecimen.readCache(c, sha) == null);
        }
        JSONArray seventeen = new JSONArray();
        for (int i = 0; i < 17; i++) seventeen.put(o.getJSONArray("images").getJSONObject(i % 16));
        Files.write(c.toPath(), new JSONObject(o.toString()).put("images", seventeen).toString().getBytes(StandardCharsets.UTF_8));
        check("more than 16 refused", EmojiSpecimen.readCache(c, sha) == null);
        Files.write(c.toPath(), new JSONObject(o.toString()).put("images", new JSONArray()).toString().getBytes(StandardCharsets.UTF_8));
        eq("an empty sample is a sample", EmojiSpecimen.readCache(c, sha).size(), 0);
    }

    // ---------------------------------------------------------------------------------------------
    // The four sample fonts the app ships
    // ---------------------------------------------------------------------------------------------

    static void assets() throws Exception {
        for (String id : EmojiSpecimen.SPECIMEN_IDS) {
            String p = repo("assets/emoji/" + id + ".ttf");
            check("assets/emoji/" + id + ".ttf is there", p != null);
            if (p == null) continue;
            byte[] f = Files.readAllBytes(new File(p).toPath());
            check(id + " is small (a sample, not the font)", f.length > 1000 && f.length < 64 * 1024);
            int[] t = table(f, "cmap");
            check(id + " has a cmap", t != null);
            if (t == null) continue;
            int[] g = EmojiSpecimen.glyphs(Arrays.copyOfRange(f, t[0], t[0] + t[1]));
            int have = 0;
            StringBuilder missing = new StringBuilder();
            for (int i = 0; i < g.length; i++) { if (g[i] > 0) have++; else missing.append(Integer.toHexString(EmojiSpecimen.CODEPOINTS[i])).append(' '); }
            eq(id + " covers the 16 code points (missing: " + missing.toString().trim() + ")", have, 16);
        }
        check("no specimen for the iOS emoji in assets", repo("assets/emoji/apple.ttf") == null);
    }
}
