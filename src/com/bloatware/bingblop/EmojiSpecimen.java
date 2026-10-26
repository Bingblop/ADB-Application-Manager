package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The emoji fonts card's samples (v7.18.0): the same 16 emoji drawn by each downloadable emoji font. The four vector fonts ship cut down to
 * those 16 in assets/emoji/&lt;id&gt;.ttf (tools/make-emoji-specimens.sh, the same {@link #SAMPLE}); the iOS emoji cannot ship, so their
 * sample is read out of the 44 MB file itself, the downloaded one when the phone has it, else a few ranges of the file at its address
 * (about 0.3 MB): the table directory, cmap, CBLC, then one read per emoji for its PNG in CBDT.
 *
 * <p>{@link #read} walks a colour-bitmap font through a {@link Source} that hands it exact byte ranges: cmap (format 12, else 4) gives the
 * glyph, the CBLC strike nearest 96 ppem (of those at least 64, else the largest) gives where its image is (index formats 1 to 5), CBDT
 * holds it (image formats 17, 18, 19) and the PNG's signature is checked. Every offset and length is checked against the file and the caps
 * before it is read; a broken font ends in a {@link SpecimenException} ("emo_no_sample"), never another exception and never a read sized by a
 * count that was not checked. Pure Java, no Android classes (tested on the JDK: tests/java/src/EmojiSpecimenTest.java).
 */
public final class EmojiSpecimen {

    private EmojiSpecimen() {}

    /** The 16 sample emoji, the same string as tools/make-emoji-specimens.sh's SAMPLE and the page's FNT_EMO_SAMPLE. */
    public static final String SAMPLE = "😀😂🥰😎🥳😭👍👋❤️🔥🎉🐶🦄🍕🚀🌈";
    /** The first code point of each sample emoji (the heart's variation selector is not a glyph of its own in a bitmap font). */
    public static final int[] CODEPOINTS = { 0x1F600, 0x1F602, 0x1F970, 0x1F60E, 0x1F973, 0x1F62D, 0x1F44D, 0x1F44B, 0x2764, 0x1F525, 0x1F389, 0x1F436,
            0x1F984, 0x1F355, 0x1F680, 0x1F308 };
    /** The emoji fonts whose sample ships as a font in assets/emoji/&lt;id&gt;.ttf. */
    public static final List<String> SPECIMEN_IDS = Collections.unmodifiableList(Arrays.asList("noto", "twemoji", "blobmoji", "openmoji"));

    static final int MAX_CMAP = 1 << 20, MAX_CBLC = 2 << 20, MAX_GLYPH = 512 << 10, MAX_TABLES = 512;
    /** Fewer images than this is no sample. */
    static final int MIN_IMAGES = 8;

    public static final String E_CHANGED = "The iOS emoji file at its address is not the expected one, so its sample was not read. Update the app.";
    public static final String E_NO_SAMPLE = "The sample could not be read from the emoji font.";

    /** A refusal with the page's code (emo_no_sample, emo_changed); the message is the page's sentence for it. */
    public static final class SpecimenException extends IOException {
        public final String code;
        public SpecimenException(String code) { super("emo_changed".equals(code) ? E_CHANGED : E_NO_SAMPLE); this.code = code; }
    }

    /** The font, a byte range at a time: exactly {@code length} bytes from {@code offset}, or an IOException. */
    public interface Source { byte[] read(long offset, int length) throws IOException; }

    /** One sample emoji: its first code point and its PNG. */
    public static final class Image {
        public final int cp;
        public final byte[] png;
        public Image(int cp, byte[] png) { this.cp = cp; this.png = png; }
    }

    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };

    // ---------------------------------------------------------------------------------------------
    // Reading the font
    // ---------------------------------------------------------------------------------------------

    /**
     * The sample's images found in the font, in {@link #CODEPOINTS} order (an emoji the font has no image for is left out). An IOException
     * of the source (the network, or the HTTP source's emo_changed) comes through as it is; anything wrong with the font is emo_no_sample,
     * and so is a font with fewer than 8 of the 16.
     */
    public static List<Image> read(Source s, long fileSize) throws IOException {
        try {
            return walk(s, fileSize);
        } catch (RuntimeException | OutOfMemoryError e) {
            throw new SpecimenException("emo_no_sample");
        }
    }

    private static List<Image> walk(Source s, long fileSize) throws IOException {
        if (fileSize < 12) throw bad();
        byte[] head = get(s, 0, 12, fileSize);
        int version = s32(head, 0), numTables = u16(head, 4);
        if ((version != 0x00010000 && version != 0x74727565) || numTables < 1 || numTables > MAX_TABLES) throw bad();
        byte[] dir = get(s, 12, numTables * 16, fileSize);
        long[] cmap = null, cblc = null, cbdt = null;
        for (int i = 0; i < numTables; i++) {
            int o = i * 16;
            String tag = new String(dir, o, 4, StandardCharsets.ISO_8859_1);
            long off = u32(dir, o + 8), len = u32(dir, o + 12);
            if (off + len > fileSize) {
                if (tag.equals("cmap") || tag.equals("CBLC") || tag.equals("CBDT")) throw bad();
                continue;
            }
            if (tag.equals("cmap")) cmap = new long[] { off, len };
            else if (tag.equals("CBLC")) cblc = new long[] { off, len };
            else if (tag.equals("CBDT")) cbdt = new long[] { off, len };
        }
        if (cmap == null || cblc == null || cbdt == null || cmap[1] > MAX_CMAP || cblc[1] > MAX_CBLC || cbdt[1] < 4) throw bad();
        int[] glyphs = glyphs(get(s, cmap[0], (int) cmap[1], fileSize));
        byte[] loc = get(s, cblc[0], (int) cblc[1], fileSize);
        int strike = strike(loc);

        List<Image> out = new ArrayList<Image>();
        for (int i = 0; i < CODEPOINTS.length; i++) {
            if (glyphs[i] <= 0) continue;
            long[] where = locate(loc, strike, glyphs[i]);
            if (where == null) continue;
            long off = where[0], len = where[1];
            int imageFormat = (int) where[2];
            if (off < 4 || len < 1 || off + len > cbdt[1] || len > MAX_GLYPH + 16) throw bad();
            byte[] g = get(s, cbdt[0] + off, (int) len, fileSize);
            byte[] png = image(g, imageFormat);
            if (png != null) out.add(new Image(CODEPOINTS[i], png));
        }
        if (out.size() < MIN_IMAGES) throw bad();
        return out;
    }

    private static SpecimenException bad() { return new SpecimenException("emo_no_sample"); }

    /** Exactly {@code len} bytes at {@code off}, inside the file. */
    private static byte[] get(Source s, long off, int len, long fileSize) throws IOException {
        if (off < 0 || len < 0 || off + len > fileSize) throw bad();
        if (len == 0) return new byte[0];
        byte[] b = s.read(off, len);
        if (b == null || b.length != len) throw bad();
        return b;
    }

    /** The glyph of each of {@link #CODEPOINTS} (0 when the font has none): a format 12 subtable, else a format 4 one. */
    static int[] glyphs(byte[] cmap) throws SpecimenException {
        if (cmap.length < 4) throw bad();
        int n = u16(cmap, 2);
        if (4 + 8L * n > cmap.length) throw bad();
        int f12 = -1, f4 = -1;
        for (int i = 0; i < n; i++) {
            int p = u16(cmap, 4 + i * 8), e = u16(cmap, 6 + i * 8);
            long off = u32(cmap, 8 + i * 8);
            if (off + 4 > cmap.length) continue;
            int format = u16(cmap, (int) off);
            boolean unicode = p == 0 || (p == 3 && (e == 1 || e == 10));
            if (!unicode) continue;
            if (format == 12 && f12 < 0) f12 = (int) off;
            else if (format == 4 && f4 < 0) f4 = (int) off;
        }
        int[] out = new int[CODEPOINTS.length];
        if (f12 >= 0) {
            if (f12 + 16L > cmap.length) throw bad();
            long len = u32(cmap, f12 + 4), groups = u32(cmap, f12 + 12);
            if (len < 16 || f12 + len > cmap.length || 16 + 12 * groups > len) throw bad();
            for (int k = 0; k < CODEPOINTS.length; k++) {
                long cp = CODEPOINTS[k];
                long lo = 0, hi = groups - 1;
                while (lo <= hi) {
                    long mid = (lo + hi) >>> 1;
                    int g = (int) (f12 + 16 + 12 * mid);
                    long start = u32(cmap, g), end = u32(cmap, g + 4);
                    if (cp < start) hi = mid - 1;
                    else if (cp > end) lo = mid + 1;
                    else { long gid = u32(cmap, g + 8) + (cp - start); out[k] = gid > 0xffff ? 0 : (int) gid; break; }
                }
            }
        } else if (f4 >= 0) {
            if (f4 + 14L > cmap.length) throw bad();
            int len = u16(cmap, f4 + 2), segX2 = u16(cmap, f4 + 6);
            if (len < 16 || f4 + len > cmap.length || (segX2 & 1) != 0 || 16 + 4 * segX2 > len) throw bad();
            int ends = f4 + 14, starts = ends + segX2 + 2, deltas = starts + segX2, ranges = deltas + segX2;
            for (int k = 0; k < CODEPOINTS.length; k++) {
                int cp = CODEPOINTS[k];
                if (cp > 0xffff) continue;
                for (int seg = 0; seg < segX2; seg += 2) {
                    int end = u16(cmap, ends + seg), start = u16(cmap, starts + seg);
                    if (cp > end) continue;
                    if (cp < start) break;
                    int delta = u16(cmap, deltas + seg), ro = u16(cmap, ranges + seg);
                    if (ro == 0) { out[k] = (cp + delta) & 0xffff; break; }
                    long at = (long) ranges + seg + ro + 2L * (cp - start);
                    if (at + 2 > f4 + len) throw bad();
                    int gid = u16(cmap, (int) at);
                    out[k] = gid == 0 ? 0 : (gid + delta) & 0xffff;
                    break;
                }
            }
        } else {
            throw bad();
        }
        return out;
    }

    /** The strike used: ppemY nearest 96 of those at least 64 (the first of two as near), else the largest. Returns its record's offset. */
    static int strike(byte[] cblc) throws SpecimenException {
        if (cblc.length < 8) throw bad();
        int major = u16(cblc, 0);
        long n = u32(cblc, 4);
        if ((major != 2 && major != 3) || n < 1 || 8 + 48 * n > cblc.length) throw bad();
        int best = -1, bestPpem = -1, largest = -1, largestPpem = -1;
        for (int i = 0; i < n; i++) {
            int o = 8 + 48 * i, ppem = cblc[o + 45] & 0xff;
            if (ppem > largestPpem) { largest = o; largestPpem = ppem; }
            if (ppem >= 64 && (best < 0 || Math.abs(ppem - 96) < Math.abs(bestPpem - 96))) { best = o; bestPpem = ppem; }
        }
        return best >= 0 ? best : largest;
    }

    /** Where glyph {@code gid}'s image is in CBDT in the strike at {@code rec}: {offset, length, image format}, or null when it has none. */
    static long[] locate(byte[] cblc, int rec, int gid) throws SpecimenException {
        long arr = u32(cblc, rec), arrSize = u32(cblc, rec + 4), count = u32(cblc, rec + 8);
        int start = u16(cblc, rec + 40), end = u16(cblc, rec + 42);
        if (arr + 8 * count > cblc.length || count > arrSize / 8 + 1) throw bad();
        if (gid < start || gid > end) return null;
        for (int i = 0; i < count; i++) {
            int e = (int) (arr + 8 * i);
            int first = u16(cblc, e), last = u16(cblc, e + 2);
            if (gid < first || gid > last) continue;
            if (last < first) throw bad();
            long sub = arr + u32(cblc, e + 4);
            if (sub + 8 > cblc.length) throw bad();
            int indexFormat = u16(cblc, (int) sub), imageFormat = u16(cblc, (int) sub + 2);
            long base = u32(cblc, (int) sub + 4);
            int k = gid - first, b = (int) sub + 8;
            switch (indexFormat) {
                case 1: {
                    if (b + 4L * (last - first + 2) > cblc.length) throw bad();
                    long a = u32(cblc, b + 4 * k), z = u32(cblc, b + 4 * k + 4);
                    return z <= a ? null : new long[] { base + a, z - a, imageFormat };
                }
                case 3: {
                    if (b + 2L * (last - first + 2) > cblc.length) throw bad();
                    long a = u16(cblc, b + 2 * k), z = u16(cblc, b + 2 * k + 2);
                    return z <= a ? null : new long[] { base + a, z - a, imageFormat };
                }
                case 2: {
                    if (b + 12L > cblc.length) throw bad();
                    long size = u32(cblc, b);
                    return size == 0 ? null : new long[] { base + size * k, size, imageFormat };
                }
                case 4: {
                    if (b + 4L > cblc.length) throw bad();
                    long num = u32(cblc, b);
                    if (b + 4 + 4 * (num + 1) > cblc.length) throw bad();
                    for (int j = 0; j < num; j++) {
                        int p = b + 4 + 4 * j;
                        if (u16(cblc, p) != gid) continue;
                        long a = u16(cblc, p + 2), z = u16(cblc, p + 6);
                        return z <= a ? null : new long[] { base + a, z - a, imageFormat };
                    }
                    return null;
                }
                case 5: {
                    if (b + 16L > cblc.length) throw bad();
                    long size = u32(cblc, b), num = u32(cblc, b + 12);
                    if (b + 16 + 2 * num > cblc.length) throw bad();
                    for (int j = 0; j < num; j++) {
                        if (u16(cblc, b + 16 + 2 * j) == gid) return size == 0 ? null : new long[] { base + size * j, size, imageFormat };
                    }
                    return null;
                }
                default:
                    throw bad();
            }
        }
        return null;
    }

    /** The PNG of a CBDT glyph record (formats 17, 18, 19); null for a record of another format. */
    static byte[] image(byte[] g, int format) throws SpecimenException {
        int at;
        if (format == 17) at = 5;
        else if (format == 18) at = 8;
        else if (format == 19) at = 0;
        else return null;
        if (g.length < at + 4) throw bad();
        long len = u32(g, at);
        if (len < PNG.length || len > MAX_GLYPH || at + 4 + len > g.length) throw bad();
        byte[] png = Arrays.copyOfRange(g, at + 4, at + 4 + (int) len);
        for (int i = 0; i < PNG.length; i++) if (png[i] != PNG[i]) throw bad();
        return png;
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static int s32(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }
    private static long u32(byte[] b, long o) { return s32(b, (int) o) & 0xffffffffL; }

    // ---------------------------------------------------------------------------------------------
    // The HTTP source's answer
    // ---------------------------------------------------------------------------------------------

    private static final Pattern RANGE = Pattern.compile("\\s*bytes\\s+(\\d{1,15})-(\\d{1,15})/(\\d{1,15})\\s*");

    /** A Content-Range header ("bytes 0-11/43877492") as {first, last, total}; null when it is not one (an unknown total included). */
    public static long[] contentRange(String header) {
        if (header == null) return null;
        Matcher m = RANGE.matcher(header);
        if (!m.matches()) return null;
        long first = Long.parseLong(m.group(1)), last = Long.parseLong(m.group(2)), total = Long.parseLong(m.group(3));
        return first <= last && last < total ? new long[] { first, last, total } : null;
    }

    /**
     * Checks one answer of the HTTP source: the part asked for ({@code length} bytes at {@code offset}) of a file of exactly {@code total}
     * bytes. Anything else (a 200 with the whole file, another range, another size, no Content-Range) is emo_changed: the file at the
     * address is not the one the app knows.
     */
    public static void checkPart(int status, String contentRange, long offset, int length, long total) throws SpecimenException {
        long[] r = contentRange(contentRange);
        if (status != 206 || r == null || r[0] != offset || r[1] != offset + length - 1 || r[2] != total) throw new SpecimenException("emo_changed");
    }

    // ---------------------------------------------------------------------------------------------
    // The cache (files/fontmaker/specimen/apple.json)
    // ---------------------------------------------------------------------------------------------

    /** {v:1, sha256, images:[{cp, b64}]}: the sample of the file whose SHA-256 is {@code sha256}. */
    public static JSONObject cacheJson(List<Image> images, String sha256) throws JSONException {
        JSONArray a = new JSONArray();
        for (Image i : images) a.put(new JSONObject().put("cp", i.cp).put("b64", Base64.getEncoder().encodeToString(i.png)));
        return new JSONObject().put("v", 1).put("sha256", sha256).put("images", a);
    }

    /** The cached sample, or null when there is none, it is of another file, or anything in it is off (a code point not of the sample, not a PNG). */
    public static List<Image> readCache(File f, String sha256) {
        try {
            if (f == null || !f.isFile() || f.length() > (long) CODEPOINTS.length * MAX_GLYPH * 4 / 3 + 65536) return null;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (o.optInt("v") != 1 || sha256 == null || !sha256.equals(o.optString("sha256"))) return null;
            JSONArray a = o.optJSONArray("images");
            if (a == null || a.length() > CODEPOINTS.length) return null;
            Set<Integer> allowed = new HashSet<Integer>(), seen = new HashSet<Integer>();
            for (int cp : CODEPOINTS) allowed.add(cp);
            List<Image> out = new ArrayList<Image>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) return null;
                int cp = e.optInt("cp", -1);
                if (!allowed.contains(cp) || !seen.add(cp)) return null;
                byte[] png = Base64.getDecoder().decode(e.optString("b64", ""));
                if (png.length < PNG.length || png.length > MAX_GLYPH) return null;
                for (int k = 0; k < PNG.length; k++) if (png[k] != PNG[k]) return null;
                out.add(new Image(cp, png));
            }
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
