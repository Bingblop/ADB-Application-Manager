package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal DEX reader (JDK only): reads ONLY
 *   header (0x70 bytes), string_ids, type_ids, class_defs (to know which types are defined), and
 *   the string_data_items of the strings that type_ids point to (the class descriptors).
 * Everything else (proto/field/method ids, code, debug info, annotations ...) is skipped:
 *   - STORED (uncompressed) dex entry: InputStream.skip() is a pointer bump, nothing is read from disk.
 *   - DEFLATED dex entry: bytes must be inflated sequentially, but they are discarded, never parsed/kept.
 *
 * DEX layout used (https://source.android.com/docs/core/runtime/dex-format):
 *   0x38 string_ids_size, 0x3C string_ids_off, 0x40 type_ids_size, 0x44 type_ids_off,
 *   0x60 class_defs_size, 0x64 class_defs_off   (all u4 little endian)
 *   string_id_item = u4 offset of string_data_item;  type_id_item = u4 index into string_ids;
 *   class_def_item = 32 bytes, first u4 = class_idx (index into type_ids);
 *   string_data_item = uleb128 utf16_size, MUTF-8 bytes, 0x00.
 */
final class DexTypeScanner {
    static final long MAX_DEX_BYTES = 256L << 20;     // refuse absurd entries (zip bombs / corrupt headers)
    static final long MAX_STRINGS = 4L << 20;

    /** Counters filled while scanning (for the benchmark). */
    static final class Stats {
        int apks, dexFiles, storedDex;
        long dexBytes;          // uncompressed size of all classes*.dex entries
        long pulledBytes;       // bytes actually pulled out of the zip stream (read or inflated)
        long skippedBytes;      // bytes skipped for free (STORED entries)
        long types, definedTypes, classTypes;
        void add(Stats o) {
            apks += o.apks; dexFiles += o.dexFiles; storedDex += o.storedDex; dexBytes += o.dexBytes;
            pulledBytes += o.pulledBytes; skippedBytes += o.skippedBytes;
            types += o.types; definedTypes += o.definedTypes; classTypes += o.classTypes;
        }
    }

    /** classes.dex, classes2.dex, classes3.dex ... (ART loads them in this order and stops at the first gap). */
    static List<String> dexEntryNames(ZipFile zf) {
        List<String> names = new ArrayList<String>();
        for (int n = 1; ; n++) {
            String name = n == 1 ? "classes.dex" : "classes" + n + ".dex";
            if (zf.getEntry(name) == null) break;
            names.add(name);
        }
        return names;
    }

    /** Scans every classes*.dex of one APK/zip. A zip without dex yields dexFiles == 0 ("not scannable", not "0 trackers"). */
    static void scanApk(File apk, TrackerMatcher m, TrackerMatcher.Result r, Stats st) throws IOException {
        ZipFile zf = new ZipFile(apk);
        try {
            st.apks++;
            for (String name : dexEntryNames(zf)) {
                ZipEntry e = zf.getEntry(name);
                scanDex(zf, e, m, r, st);
            }
        } finally {
            zf.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------

    static void scanDex(ZipFile zf, ZipEntry e, TrackerMatcher m, TrackerMatcher.Result r, Stats st) throws IOException {
        final long size = e.getSize();
        Cursor c = new Cursor(zf, e);
        try {
            st.dexBytes += size;
            if (e.getMethod() == ZipEntry.STORED) st.storedDex++;

            byte[] h = new byte[0x70];
            c.readFully(h, 0, h.length);
            if (!(h[0] == 'd' && h[1] == 'e' && h[2] == 'x' && h[3] == '\n' && h[7] == 0
                    && h[4] >= '0' && h[4] <= '9' && h[5] >= '0' && h[5] <= '9' && h[6] >= '0' && h[6] <= '9'))
                throw new IOException("not a dex file: " + e.getName());
            if (u4(h, 0x28) != 0x12345678) throw new IOException("unsupported endianness");
            final long strIdsSize = u4(h, 0x38) & 0xFFFFFFFFL, strIdsOff = u4(h, 0x3C) & 0xFFFFFFFFL;
            final long typIdsSize = u4(h, 0x40) & 0xFFFFFFFFL, typIdsOff = u4(h, 0x44) & 0xFFFFFFFFL;
            final long clsDefSize = u4(h, 0x60) & 0xFFFFFFFFL, clsDefOff = u4(h, 0x64) & 0xFFFFFFFFL;
            // untrusted input: bound everything by the entry size
            // hard caps: real dex files have <= 65536 type_ids and a few hundred thousand strings; the entry is untrusted
            if (size > MAX_DEX_BYTES || strIdsSize > MAX_STRINGS || typIdsSize > 65536 || clsDefSize > 65536
                    || strIdsSize > size / 4 || typIdsSize > size / 4 || clsDefSize > size / 32
                    || strIdsOff + strIdsSize * 4 > size || typIdsOff + typIdsSize * 4 > size
                    || clsDefOff + clsDefSize * 32 > size)
                throw new IOException("corrupt dex header in " + e.getName());

            // 1. string_ids: where each string lives
            c.seek(strIdsOff);
            int[] strOff = c.readU4Array((int) strIdsSize);
            // 2. type_ids: which string is the descriptor of each type
            c.seek(typIdsOff);
            int[] typeStr = c.readU4Array((int) typIdsSize);
            // 3. class_defs: which types are actually DEFINED here (vs. merely referenced by some signature/call)
            boolean[] defined = new boolean[(int) typIdsSize];
            if (clsDefSize > 0) {
                c.seek(clsDefOff);
                byte[] def = new byte[32];
                for (int i = 0; i < clsDefSize; i++) {
                    c.readFully(def, 0, 32);
                    int ci = u4(def, 0);
                    if (ci >= 0 && ci < defined.length) defined[ci] = true;
                }
            }
            // 4. descriptor strings, visited in ascending file offset order (already so in real dex files)
            int n = (int) typIdsSize;
            long[] order = new long[n];
            boolean sorted = true;
            for (int t = 0; t < n; t++) {
                int si = typeStr[t];
                if (si < 0 || si >= strOff.length) throw new IOException("bad descriptor_idx in " + e.getName());
                long off = strOff[si] & 0xFFFFFFFFL;
                order[t] = (off << 24) | t;                       // t < 2^24 (<= 65536 types per dex)
                if (t > 0 && order[t] < order[t - 1]) sorted = false;
            }
            if (!sorted) Arrays.sort(order);
            byte[] buf = new byte[4096];
            for (int k = 0; k < n; k++) {
                int t = (int) (order[k] & 0xFFFFFF);
                long off = order[k] >>> 24;
                if (off >= size) throw new IOException("string offset out of range");
                c.seek(off);
                // uleb128 utf16_size (ignored), then MUTF-8 bytes up to the NUL terminator
                int b;
                do { b = c.u1(); } while (b >= 0x80);
                int len = 0;
                while ((b = c.u1()) > 0) {
                    if (len < buf.length) buf[len] = (byte) b;
                    len++;
                }
                if (b < 0) throw new IOException("truncated string data");
                st.types++;
                if (defined[t]) st.definedTypes++;
                if (len > 0 && len <= buf.length) {
                    if (buf[0] == 'L' || buf[0] == '[') st.classTypes++;
                    m.feedDescriptor(buf, len, defined[t], r);
                }
            }
            st.dexFiles++;                                    // counted once it was read to the end: a damaged file is not 'a dex that holds no trackers'
        } finally {
            st.pulledBytes += c.pulled;
            st.skippedBytes += c.skipped;
            c.close();
        }
    }

    static int u4(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    // ---------------------------------------------------------------------------------------------------

    /** Forward-only reader over a zip entry with a cheap seek(): skip() for STORED, read-and-discard for DEFLATED. */
    static final class Cursor {
        private final ZipFile zf;
        private final ZipEntry e;
        private final boolean stored;
        private InputStream in;
        private final byte[] buf = new byte[1 << 16];
        private int bufPos, bufLen;
        private long pos;           // absolute offset (in the dex) of the next byte u1() returns
        long pulled, skipped;

        Cursor(ZipFile zf, ZipEntry e) throws IOException {
            this.zf = zf;
            this.e = e;
            this.stored = e.getMethod() == ZipEntry.STORED;
            this.in = zf.getInputStream(e);
        }

        void close() { try { in.close(); } catch (IOException ignored) { } }

        private boolean fill() throws IOException {
            bufPos = 0;
            bufLen = 0;
            int n = in.read(buf, 0, buf.length);
            if (n <= 0) return false;
            bufLen = n;
            pulled += n;
            return true;
        }

        int u1() throws IOException {
            if (bufPos >= bufLen && !fill()) return -1;
            pos++;
            return buf[bufPos++] & 0xFF;
        }

        void readFully(byte[] dst, int off, int len) throws IOException {
            while (len > 0) {
                if (bufPos >= bufLen && !fill()) throw new IOException("unexpected end of dex");
                int k = Math.min(len, bufLen - bufPos);
                System.arraycopy(buf, bufPos, dst, off, k);
                bufPos += k; off += k; len -= k; pos += k;
            }
        }

        int[] readU4Array(int count) throws IOException {
            int[] a = new int[count];
            byte[] t = new byte[4];
            for (int i = 0; i < count; i++) {
                if (bufLen - bufPos >= 4) {                 // fast path
                    a[i] = (buf[bufPos] & 0xFF) | (buf[bufPos + 1] & 0xFF) << 8 | (buf[bufPos + 2] & 0xFF) << 16 | (buf[bufPos + 3] & 0xFF) << 24;
                    bufPos += 4; pos += 4;
                } else {
                    readFully(t, 0, 4);
                    a[i] = u4(t, 0);
                }
            }
            return a;
        }

        /** Move forward to absolute offset 'target' (reopens the entry in the rare case of a backward seek). */
        void seek(long target) throws IOException {
            if (target < pos) {                              // never happens for canonical dex layouts
                in.close();
                in = zf.getInputStream(e);
                bufPos = bufLen = 0;
                pos = 0;
            }
            long d = target - pos;
            int inBuf = bufLen - bufPos;
            if (d <= inBuf) { bufPos += (int) d; pos = target; return; }
            d -= inBuf;
            bufPos = bufLen = 0;
            pos += inBuf;
            if (stored) {
                while (d > 0) {
                    long s = in.skip(d);
                    if (s <= 0) {
                        if (in.read() < 0) throw new IOException("seek past end");
                        s = 1; pulled++;
                    } else skipped += s;
                    d -= s; pos += s;
                }
            } else {
                while (d > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, d));
                    if (n <= 0) throw new IOException("seek past end");
                    pulled += n; d -= n; pos += n;
                }
            }
        }
    }
}
