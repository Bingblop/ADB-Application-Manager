package com.bloatware.bingblop;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An OpenType / TrueType font file (one font, not a collection) as a map of tables, read and written back.
 *
 * <p>The Custom Fonts tab builds font packages from fonts the user picks: it instances variable fonts ({@link FontInstancer}), turns PostScript
 * outlines into TrueType ones ({@link CffToGlyf}) and merges a colour emoji font in ({@link EmojiMerge}). They all read a font with
 * {@link #parse}, change or replace tables, and write it with {@link #toBytes}, which lays the tables out in tag order, pads them, and computes
 * every checksum and head.checkSumAdjustment. The input is untrusted: every read is bounds-checked and a bad file ends in a {@link FontException}
 * whose message can be shown to the user. Pure Java, no Android classes.
 */
public final class Sfnt {

    /** A font file that cannot be used, with a sentence that says why. */
    public static final class FontException extends IOException {
        public final String code;   // a short stable code the page may branch on ("not_a_font", "collection", "too_big" ...), or "bad_font"

        public FontException(String code, String message) {
            super(message);
            this.code = code == null ? "bad_font" : code;
        }

        public FontException(String message) { this("bad_font", message); }
    }

    public static final int TRUETYPE = 0x00010000;
    public static final int OTTO = 0x4F54544F;          // 'OTTO': CFF / CFF2 outlines
    public static final int TRUE_MAC = 0x74727565;      // 'true': old Mac TrueType
    /** A font bigger than this is refused before anything is parsed (a merged emoji font is far below it). */
    public static final int MAX_FONT_BYTES = 96 * 1024 * 1024;
    /** More tables than any real font has: the directory of a hostile file is refused instead of walked. */
    static final int MAX_TABLES = 128;

    public int sfntVersion;
    private final LinkedHashMap<String, byte[]> tables = new LinkedHashMap<String, byte[]>();

    public Sfnt(int sfntVersion) { this.sfntVersion = sfntVersion; }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    /** Reads one font. Refuses a font collection (.ttc), WOFF / WOFF2, and anything that is not an sfnt with a sane table directory. */
    public static Sfnt parse(byte[] data) throws FontException {
        if (data == null || data.length < 12) throw new FontException("not_a_font", "This is not a font file (it is too short).");
        if (data.length > MAX_FONT_BYTES) throw new FontException("too_big", "This font file is too big (over " + (MAX_FONT_BYTES >> 20) + " MB).");
        int ver = s32(data, 0);
        if (ver == 0x74746366) throw new FontException("collection", "This is a font collection (.ttc). Use a single .ttf or .otf font.");
        if (ver == 0x774F4646 || ver == 0x774F4632) throw new FontException("woff", "This is a web font (WOFF). Use a .ttf or .otf font.");
        if (ver != TRUETYPE && ver != OTTO && ver != TRUE_MAC) throw new FontException("not_a_font", "This is not a TrueType or OpenType font file.");
        int num = u16(data, 4);
        if (num == 0 || num > MAX_TABLES) throw new FontException("This font's table directory is damaged.");
        if (12 + 16L * num > data.length) throw new FontException("This font's table directory is cut off.");
        Sfnt f = new Sfnt(ver == TRUE_MAC ? TRUETYPE : ver);
        for (int i = 0; i < num; i++) {
            int rec = 12 + 16 * i;
            String tag = tagString(data, rec);
            long off = u32(data, rec + 8);
            long len = u32(data, rec + 12);
            if (off + len > data.length || off < 12) throw new FontException("The '" + tag.trim() + "' table of this font points outside the file.");
            if (f.tables.containsKey(tag)) throw new FontException("This font names the '" + tag.trim() + "' table twice.");
            f.tables.put(tag, Arrays.copyOfRange(data, (int) off, (int) (off + len)));
        }
        if (!f.has("head") || !f.has("maxp") || !f.has("cmap") || !f.has("hhea") || !f.has("hmtx"))
            throw new FontException("missing_table", "This font is missing one of the tables every font has (head, maxp, cmap, hhea, hmtx).");
        if (f.table("head").length < 54) throw new FontException("This font's head table is too short.");
        if (f.table("maxp").length < 6) throw new FontException("This font's maxp table is too short.");
        if (f.table("hhea").length < 36) throw new FontException("This font's hhea table is too short.");
        if (f.unitsPerEm() < 16 || f.unitsPerEm() > 16384) throw new FontException("This font's units per em (" + f.unitsPerEm() + ") is out of range.");
        if (f.numGlyphs() < 1) throw new FontException("This font has no glyphs.");
        if (!f.has("glyf") && !f.has("CFF ") && !f.has("CFF2"))
            throw new FontException("no_outlines", "This font has no outlines (no glyf, CFF or CFF2 table): it may be a bitmap-only font.");
        if (f.has("glyf") && !f.has("loca")) throw new FontException("This font has glyph outlines but no loca table.");
        return f;
    }

    /** The four characters of the tag at {@code off}. */
    static String tagString(byte[] b, int off) throws FontException {
        need(b, off, 4);
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) (b[off + i] & 0xff);
        return new String(c);
    }

    public boolean has(String tag) { return tables.containsKey(tag); }

    /** The table's bytes (the stored array itself, not a copy), or null. */
    public byte[] table(String tag) { return tables.get(tag); }

    public void put(String tag, byte[] data) {
        if (tag == null || tag.length() != 4) throw new IllegalArgumentException("A table tag has four characters: " + tag);
        tables.put(tag, data);
    }

    public void remove(String tag) { tables.remove(tag); }

    public List<String> tags() { return new ArrayList<String>(tables.keySet()); }

    public boolean isCff() { return has("CFF ") && !has("glyf"); }

    public boolean isCff2() { return has("CFF2") && !has("glyf"); }

    public boolean isVariable() { return has("fvar"); }

    public int unitsPerEm() { return u16q(table("head"), 18); }

    public int numGlyphs() { return u16q(table("maxp"), 4); }

    public int indexToLocFormat() { return s16q(table("head"), 50); }

    /** A copy of the font: the tables are copied, so changes to one do not reach the other. */
    public Sfnt copy() {
        Sfnt c = new Sfnt(sfntVersion);
        for (Map.Entry<String, byte[]> e : tables.entrySet()) c.tables.put(e.getKey(), e.getValue().clone());
        return c;
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    /** The font as a file: tables in tag order, each padded to 4 bytes, the directory's checksums and head.checkSumAdjustment filled in. */
    public byte[] toBytes() throws FontException {
        if (!has("head")) throw new FontException("A font cannot be written without its head table.");
        TreeMap<String, byte[]> sorted = new TreeMap<String, byte[]>(tables);       // tags compare as their bytes do: ASCII
        int num = sorted.size();
        long total = 12 + 16L * num;
        for (byte[] t : sorted.values()) total += (t.length + 3) & ~3L;
        if (total > Integer.MAX_VALUE - 8) throw new FontException("too_big", "The font would be too big to write.");
        byte[] out = new byte[(int) total];
        w32(out, 0, sfntVersion);
        w16(out, 4, num);
        int pow = 1, log = 0;
        while (pow * 2 <= num) { pow *= 2; log++; }
        w16(out, 6, pow * 16);
        w16(out, 8, log);
        w16(out, 10, num * 16 - pow * 16);
        byte[] head = sorted.get("head").clone();
        w32(head, 8, 0);                                   // checkSumAdjustment is 0 while the checksums are taken
        sorted.put("head", head);
        int pos = 12 + 16 * num;
        int rec = 12;
        int headPos = -1;
        for (Map.Entry<String, byte[]> e : sorted.entrySet()) {
            byte[] t = e.getValue();
            String tag = e.getKey();
            for (int i = 0; i < 4; i++) out[rec + i] = (byte) tag.charAt(i);
            w32(out, rec + 4, (int) checksum(t, 0, t.length));
            w32(out, rec + 8, pos);
            w32(out, rec + 12, t.length);
            System.arraycopy(t, 0, out, pos, t.length);
            if (tag.equals("head")) headPos = pos;
            pos += (t.length + 3) & ~3;
            rec += 16;
        }
        long sum = checksum(out, 0, out.length);
        w32(out, headPos + 8, (int) ((0xB1B0AFBAL - sum) & 0xFFFFFFFFL));
        return out;
    }

    /** The OpenType table checksum: the sum of the big-endian uint32 words, the last one padded with zeros. */
    public static long checksum(byte[] b, int off, int len) {
        long sum = 0;
        int end = off + len, i = off;
        for (; i + 4 <= end; i += 4) sum += ((b[i] & 0xffL) << 24) | ((b[i + 1] & 0xffL) << 16) | ((b[i + 2] & 0xffL) << 8) | (b[i + 3] & 0xffL);
        if (i < end) {
            long w = 0;
            for (int k = 0; k < 4; k++) w = (w << 8) | (i + k < end ? (b[i + k] & 0xffL) : 0);
            sum += w;
        }
        return sum & 0xFFFFFFFFL;
    }

    // ---------------------------------------------------------------------------------------------
    // The name table
    // ---------------------------------------------------------------------------------------------

    /** A name of the font (1 family, 2 subfamily, 4 full name, 6 PostScript name, 16 / 17 typographic family / subfamily), or "" when it has none. */
    public String name(int nameId) {
        try {
            return nameString(table("name"), nameId);
        } catch (FontException e) {
            return "";
        }
    }

    /** Reads a name: Windows Unicode English first, then any Windows Unicode, then Mac Roman English, then anything readable. "" when there is none. */
    public static String nameString(byte[] name, int nameId) throws FontException {
        if (name == null || name.length < 6) return "";
        int count = u16(name, 2), strOff = u16(name, 4);
        if (6 + 12L * count > name.length) throw new FontException("This font's name table is cut off.");
        int best = -1, bestScore = 0;
        for (int i = 0; i < count; i++) {
            int r = 6 + 12 * i;
            if (u16(name, r + 6) != nameId) continue;
            int score = score(name, r);
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return best < 0 ? "" : recordString(name, strOff, best);
    }

    /** How much {@link #nameString} prefers the record at {@code r}; 0 for one it cannot read. */
    private static int score(byte[] name, int r) throws FontException {
        int pid = u16(name, r), eid = u16(name, r + 2), lang = u16(name, r + 4);
        if (pid == 3 && (eid == 1 || eid == 10) && lang == 0x409) return 5;
        if (pid == 3 && (eid == 1 || eid == 10)) return 4;
        if (pid == 0) return 3;
        if (pid == 1 && eid == 0 && lang == 0) return 2;
        if (pid == 3 && eid == 0) return 1;                // a symbol font's names are still UTF-16
        return 0;
    }

    private static String recordString(byte[] name, int strOff, int r) throws FontException {
        int pid = u16(name, r), len = u16(name, r + 8), off = strOff + u16(name, r + 10);
        if (off + (long) len > name.length) return "";
        Charset cs = pid == 1 ? Charset.forName("ISO-8859-1") : Charset.forName("UTF-16BE");
        return new String(name, off, len, cs).replace("\u0000", "").trim();
    }

    /**
     * A new name table (format 0) holding the given names, each as a Windows Unicode English record and a Mac Roman English one (when the text
     * is plain ASCII). Names not in the map are left out; the caller passes everything that should stay (see {@link #names}).
     */
    public static byte[] buildName(Map<Integer, String> names) {
        TreeMap<Integer, String> sorted = new TreeMap<Integer, String>(names);
        List<int[]> recs = new ArrayList<int[]>();               // platform, encoding, language, nameId, length, offset
        Writer strings = new Writer();
        Charset utf16 = Charset.forName("UTF-16BE"), latin = Charset.forName("ISO-8859-1");
        List<int[]> mac = new ArrayList<int[]>(), win = new ArrayList<int[]>();
        for (Map.Entry<Integer, String> e : sorted.entrySet()) {
            String s = e.getValue() == null ? "" : e.getValue();
            if (s.isEmpty()) continue;
            if (isAscii(s)) {
                byte[] b = s.getBytes(latin);
                mac.add(new int[] { 1, 0, 0, e.getKey(), b.length, strings.size() });
                strings.bytes(b);
            }
            byte[] b = s.getBytes(utf16);
            win.add(new int[] { 3, 1, 0x409, e.getKey(), b.length, strings.size() });
            strings.bytes(b);
        }
        recs.addAll(mac);                                          // sorted by platform, encoding, language, name id
        recs.addAll(win);
        Writer w = new Writer();
        w.u16(0);
        w.u16(recs.size());
        w.u16(6 + 12 * recs.size());
        for (int[] r : recs) for (int v : r) w.u16(v);
        w.bytes(strings.toByteArray());
        return w.toByteArray();
    }

    /** Every name the font has in Windows Unicode English (or the best other record), by name id. */
    public Map<Integer, String> names() {
        Map<Integer, String> out = new TreeMap<Integer, String>();
        byte[] name = table("name");
        if (name == null || name.length < 6) return out;
        try {
            int count = u16(name, 2), strOff = u16(name, 4);
            if (6 + 12L * count > name.length) return out;
            // One pass for the best record of every id (as nameString picks it): a call per id would be quadratic in a hostile table
            TreeMap<Integer, int[]> best = new TreeMap<Integer, int[]>();          // id -> {score, record}
            for (int i = 0; i < count; i++) {
                int r = 6 + 12 * i, id = u16(name, r + 6), score = score(name, r);
                int[] had = best.get(id);
                if (score > 0 && (had == null || score > had[0])) best.put(id, new int[] { score, r });
            }
            for (Map.Entry<Integer, int[]> e : best.entrySet()) {
                String s = recordString(name, strOff, e.getValue()[1]);
                if (!s.isEmpty()) out.put(e.getKey(), s);
            }
        } catch (FontException ignored) {}
        return out;
    }

    static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20 || s.charAt(i) > 0x7e) return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Big-endian access, bounds-checked
    // ---------------------------------------------------------------------------------------------

    static void need(byte[] b, long off, long len) throws FontException {
        if (b == null || off < 0 || len < 0 || off + len > b.length) throw new FontException("This font is damaged: a table is cut off.");
    }

    public static int u8(byte[] b, int off) throws FontException { need(b, off, 1); return b[off] & 0xff; }

    public static int u16(byte[] b, int off) throws FontException { need(b, off, 2); return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff); }

    public static int s16(byte[] b, int off) throws FontException { return (short) u16(b, off); }

    public static int u24(byte[] b, int off) throws FontException { need(b, off, 3); return ((b[off] & 0xff) << 16) | ((b[off + 1] & 0xff) << 8) | (b[off + 2] & 0xff); }

    public static long u32(byte[] b, int off) throws FontException { return s32(b, off) & 0xFFFFFFFFL; }

    public static int s32(byte[] b, int off) throws FontException {
        need(b, off, 4);
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    /** u16 for a field that {@link #parse} already checked is there. */
    static int u16q(byte[] b, int off) { return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff); }

    static int s16q(byte[] b, int off) { return (short) u16q(b, off); }

    public static void w16(byte[] b, int off, int v) { b[off] = (byte) (v >> 8); b[off + 1] = (byte) v; }

    public static void w32(byte[] b, int off, int v) { b[off] = (byte) (v >> 24); b[off + 1] = (byte) (v >> 16); b[off + 2] = (byte) (v >> 8); b[off + 3] = (byte) v; }

    /** A growing big-endian buffer for building tables. */
    public static final class Writer {
        private byte[] buf;
        private int len;

        public Writer() { this(256); }

        public Writer(int capacity) { buf = new byte[Math.max(16, capacity)]; }

        private void room(int n) {
            if (len + n <= buf.length) return;
            long want = Math.max((long) buf.length * 2, (long) len + n);
            if (want > Integer.MAX_VALUE - 8) throw new IllegalStateException("table too big");
            buf = Arrays.copyOf(buf, (int) want);
        }

        public Writer u8(int v) { room(1); buf[len++] = (byte) v; return this; }

        public Writer u16(int v) { room(2); buf[len++] = (byte) (v >> 8); buf[len++] = (byte) v; return this; }

        public Writer s16(int v) { return u16(v); }

        public Writer u24(int v) { room(3); buf[len++] = (byte) (v >> 16); buf[len++] = (byte) (v >> 8); buf[len++] = (byte) v; return this; }

        public Writer u32(long v) { room(4); w32(buf, len, (int) v); len += 4; return this; }

        public Writer bytes(byte[] b) { return bytes(b, 0, b.length); }

        public Writer bytes(byte[] b, int off, int n) { room(n); System.arraycopy(b, off, buf, len, n); len += n; return this; }

        public Writer zeros(int n) { room(n); len += n; return this; }

        /** Pads with zeros to a multiple of {@code n}. */
        public Writer align(int n) { while (len % n != 0) u8(0); return this; }

        public int size() { return len; }

        public void set16(int off, int v) { w16(buf, off, v); }

        public void set32(int off, int v) { w32(buf, off, v); }

        public byte[] toByteArray() { return Arrays.copyOf(buf, len); }
    }

    /** The tags of the font in file order, for messages and tests. */
    @Override
    public String toString() {
        List<String> t = new ArrayList<String>(tables.keySet());
        Collections.sort(t);
        return "Sfnt[" + Integer.toHexString(sfntVersion) + " " + t + "]";
    }
}
