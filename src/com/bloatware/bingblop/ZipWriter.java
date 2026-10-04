package com.bloatware.bingblop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Makes a new zip archive from files and folders: stored or deflated (level 1-9), optionally password-protected with WinZip AES (128 or 256 bit)
 * or the traditional ZipCrypto. Entries are streamed (data descriptors), so nothing is held in memory, and ZIP64 records are written when a size,
 * an offset or the entry count needs them. The archive is written to a hidden ".part" file and moved into place at the end, so a failure or a
 * Cancel never leaves a broken archive. Pure Java, tested off-device against Info-ZIP, Python's zipfile and pyzipper.
 */
public final class ZipWriter {
    private ZipWriter() {}

    /** One thing to put in the archive: {@code file} null makes a folder entry. {@code name} is the path inside, '/'-separated. */
    public static final class Item {
        public final String name;
        public final File file;

        public Item(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    public static final int NONE = 0, AES256 = 1, ZIPCRYPTO = 2, AES128 = 3;

    public interface Progress {
        /** Return false to cancel. */
        boolean onProgress(long doneBytes, int doneFiles, String current);
    }

    private static final class Rec {
        byte[] name;
        int flags, method, time, date, madeBy = 0x031E, needed = 20, internal;
        long crc, csize, size, lho, external;
        byte[] extra = new byte[0];
    }

    private static final class Count extends OutputStream {
        final OutputStream o;
        long n;

        Count(OutputStream o) {
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
            n++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            n += len;
        }

        @Override
        public void flush() throws IOException {
            o.flush();
        }

        @Override
        public void close() throws IOException {
            o.close();
        }
    }

    /** Keeps the underlying stream open when a wrapper (deflater, cipher) is closed to flush its tail. */
    private static final class NoClose extends OutputStream {
        final OutputStream o;

        NoClose(OutputStream o) {
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            o.flush();
        }

        @Override
        public void close() throws IOException {
            o.flush();
        }
    }

    /**
     * @param level 0 stores, 1-9 deflates (6 is the usual)
     * @param password null or empty for none
     * @param scheme {@link #AES256}, {@link #AES128} or {@link #ZIPCRYPTO} when there is a password
     */
    public static void create(File dest, List<Item> items, int level, char[] password, int scheme, Progress cb) throws IOException {
        boolean enc = password != null && password.length > 0 && scheme != NONE;
        if (level < 0) level = 0;
        if (level > 9) level = 9;
        File parent = dest.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Can't create " + parent);
        File part = new File(parent, "." + dest.getName() + ".part");
        Count out = null;
        boolean ok = false;
        List<Rec> cd = new ArrayList<Rec>();
        Set<String> seen = new HashSet<String>();
        try {
            out = new Count(new BufferedOutputStream(new FileOutputStream(part), 131072));
            long bytes = 0;
            int files = 0;
            for (Item it : items) {
                String name = ZipTool.safeName(it.name);
                if (name == null) throw new IOException("Not a valid name inside an archive: " + it.name);
                boolean dir = it.file == null || it.file.isDirectory();
                if (dir && !name.endsWith("/")) name += "/";
                if (!dir && name.endsWith("/")) throw new IOException("A file can't be named " + name);
                if (!seen.add(name)) continue;               // the same path twice: the first one wins
                if (cb != null && !cb.onProgress(bytes, files, name)) throw new IOException("Cancelled");
                if (dir) {
                    writeEntry(out, cd, name, null, 0, null, NONE, null);
                } else {
                    long[] r = writeEntry(out, cd, name, it.file, level, enc ? password : null, enc ? scheme : NONE, cb == null ? null : new Pulse(cb, bytes, files, name));
                    bytes += r[0];
                    files++;
                }
            }
            writeEnd(out, cd);
            out.flush();
            ok = true;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            if (!ok) part.delete();
        }
        if (!part.renameTo(dest)) {
            dest.delete();
            if (!part.renameTo(dest)) {
                part.delete();
                throw new IOException("Couldn't write " + dest);
            }
        }
    }

    private static final class Pulse {
        final Progress cb;
        final long base;
        final int files;
        final String name;

        Pulse(Progress cb, long base, int files, String name) {
            this.cb = cb;
            this.base = base;
            this.files = files;
            this.name = name;
        }
    }

    /** Writes one entry; returns {bytes read from the source}. */
    private static long[] writeEntry(Count out, List<Rec> cd, String name, File file, int level, char[] pw, int scheme, Pulse pulse) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        long when = file != null ? file.lastModified() : System.currentTimeMillis();
        if (when <= 0) when = System.currentTimeMillis();
        int dosTime = ZipTool.millisToDosTime(when), dosDate = ZipTool.millisToDosDate(when);
        boolean dir = file == null || name.endsWith("/");
        long len = dir ? 0 : file.length();
        boolean big = len >= 0xFFFFFFFFL - 65536;            // sizes will not fit 32 bits: ZIP64 in the local header, 8-byte descriptor
        boolean aes = scheme == AES256 || scheme == AES128;
        boolean trad = scheme == ZIPCRYPTO;
        int strength = scheme == AES128 ? 1 : 3;
        int actual = dir || level == 0 ? 0 : 8;
        int method = aes ? 99 : actual;
        int flags = 0x0800 | (dir ? 0 : 0x0008);
        if (!dir && (aes || trad)) flags |= 1;
        Rec r = new Rec();
        r.name = nameBytes;
        r.flags = flags;
        r.method = dir ? 0 : method;
        r.time = dosTime;
        r.date = dosDate;
        r.needed = aes ? 51 : (big ? 45 : (actual == 8 ? 20 : 10));
        int mode = dir ? 0755 : (file != null && file.canExecute() ? 0755 : 0644);
        r.external = ((long) ((dir ? 040000 : 0100000) | mode) << 16) | (dir ? 0x10 : 0);
        byte[] aesExtra = aes && !dir ? ZipCrypt.aesExtra(strength, actual, true) : new byte[0];
        byte[] localExtra = new byte[(big ? 20 : 0) + aesExtra.length];
        if (big) {
            put16(localExtra, 0, 1);
            put16(localExtra, 2, 16);
        }
        System.arraycopy(aesExtra, 0, localExtra, big ? 20 : 0, aesExtra.length);
        r.lho = out.n;
        byte[] h = new byte[30];
        put32(h, 0, 0x04034b50L);
        put16(h, 4, r.needed);
        put16(h, 6, flags);
        put16(h, 8, r.method);
        put16(h, 10, dosTime);
        put16(h, 12, dosDate);
        put32(h, 14, 0);
        put32(h, 18, big ? 0xFFFFFFFFL : 0);
        put32(h, 22, big ? 0xFFFFFFFFL : 0);
        put16(h, 26, nameBytes.length);
        put16(h, 28, localExtra.length);
        if (dir) {                                           // a folder: sizes are known (zero), no descriptor
            put32(h, 14, 0);
            put32(h, 18, 0);
            put32(h, 22, 0);
            out.write(h);
            out.write(nameBytes);
            out.write(localExtra);
            r.crc = 0;
            r.csize = 0;
            r.size = 0;
            r.extra = new byte[0];
            cd.add(r);
            return new long[]{0};
        }
        out.write(h);
        out.write(nameBytes);
        out.write(localExtra);
        long dataStart = out.n;
        CRC32 crc = new CRC32();
        long size = 0;
        OutputStream sink = new NoClose(out);
        OutputStream encOut = sink;
        if (aes) encOut = ZipCrypt.encryptAes(sink, pw, strength);
        else if (trad) encOut = ZipCrypt.encryptTraditional(sink, pw, (dosTime >>> 8) & 0xFF);
        Deflater def = null;
        OutputStream data = encOut;
        if (actual == 8) {
            def = new Deflater(level, true);
            data = new DeflaterOutputStream(new NoClose(encOut), def, 65536);
        }
        InputStream in = new BufferedInputStream(new FileInputStream(file), 65536);
        try {
            byte[] buf = new byte[65536];
            int n;
            long lastPulse = 0;
            while ((n = in.read(buf)) > 0) {
                data.write(buf, 0, n);
                crc.update(buf, 0, n);
                size += n;
                if (pulse != null && size - lastPulse >= (1 << 20)) {
                    lastPulse = size;
                    if (!pulse.cb.onProgress(pulse.base + size, pulse.files, pulse.name)) throw new IOException("Cancelled");
                }
            }
            if (actual == 8) ((DeflaterOutputStream) data).finish();
            if (aes) encOut.close();                        // writes the authentication code
            else encOut.flush();
        } finally {
            in.close();
            if (def != null) def.end();
        }
        long csize = out.n - dataStart;
        boolean zip64 = big || size > 0xFFFFFFFEL || csize > 0xFFFFFFFEL;
        // the data descriptor
        byte[] dd;
        if (zip64) {
            dd = new byte[24];
            put32(dd, 0, 0x08074b50L);
            put32(dd, 4, aes ? 0 : crc.getValue());
            put64(dd, 8, csize);
            put64(dd, 16, size);
        } else {
            dd = new byte[16];
            put32(dd, 0, 0x08074b50L);
            put32(dd, 4, aes ? 0 : crc.getValue());
            put32(dd, 8, csize);
            put32(dd, 12, size);
        }
        out.write(dd);
        r.crc = aes ? 0 : crc.getValue();                     // AE-2: no CRC (the authentication code checks the data)
        r.csize = csize;
        r.size = size;
        r.internal = 0;
        r.extra = aesExtra;
        if (zip64) r.needed = Math.max(r.needed, 45);
        return new long[]{size};
    }

    private static void writeEnd(Count out, List<Rec> cd) throws IOException {
        long cdStart = out.n;
        for (Rec r : cd) {
            boolean z64size = r.size > 0xFFFFFFFEL || r.csize > 0xFFFFFFFEL;
            boolean z64off = r.lho > 0xFFFFFFFEL;
            int zlen = (z64size ? 16 : 0) + (z64off ? 8 : 0);
            byte[] z = new byte[zlen == 0 ? 0 : zlen + 4];
            if (zlen > 0) {
                put16(z, 0, 1);
                put16(z, 2, zlen);
                int p = 4;
                if (z64size) { put64(z, p, r.size); put64(z, p + 8, r.csize); p += 16; }
                if (z64off) put64(z, p, r.lho);
            }
            byte[] extra = new byte[z.length + r.extra.length];
            System.arraycopy(z, 0, extra, 0, z.length);
            System.arraycopy(r.extra, 0, extra, z.length, r.extra.length);
            byte[] h = new byte[46];
            put32(h, 0, 0x02014b50L);
            put16(h, 4, r.madeBy);
            put16(h, 6, (z64size || z64off) ? Math.max(r.needed, 45) : r.needed);
            put16(h, 8, r.flags);
            put16(h, 10, r.method);
            put16(h, 12, r.time);
            put16(h, 14, r.date);
            put32(h, 16, r.crc);
            put32(h, 20, z64size ? 0xFFFFFFFFL : r.csize);
            put32(h, 24, z64size ? 0xFFFFFFFFL : r.size);
            put16(h, 28, r.name.length);
            put16(h, 30, extra.length);
            put16(h, 32, 0);
            put16(h, 34, 0);
            put16(h, 36, r.internal);
            put32(h, 38, r.external);
            put32(h, 42, z64off ? 0xFFFFFFFFL : r.lho);
            out.write(h);
            out.write(r.name);
            out.write(extra);
        }
        long cdSize = out.n - cdStart;
        boolean z64 = cd.size() > 0xFFFE || cdStart > 0xFFFFFFFEL || cdSize > 0xFFFFFFFEL;
        if (z64) {
            long eocd64 = out.n;
            byte[] r64 = new byte[56];
            put32(r64, 0, 0x06064b50L);
            put64(r64, 4, 44);
            put16(r64, 12, 0x031E);
            put16(r64, 14, 45);
            put32(r64, 16, 0);
            put32(r64, 20, 0);
            put64(r64, 24, cd.size());
            put64(r64, 32, cd.size());
            put64(r64, 40, cdSize);
            put64(r64, 48, cdStart);
            out.write(r64);
            byte[] loc = new byte[20];
            put32(loc, 0, 0x07064b50L);
            put32(loc, 4, 0);
            put64(loc, 8, eocd64);
            put32(loc, 16, 1);
            out.write(loc);
        }
        byte[] e = new byte[22];
        put32(e, 0, 0x06054b50L);
        put16(e, 8, z64 ? 0xFFFF : cd.size());
        put16(e, 10, z64 ? 0xFFFF : cd.size());
        put32(e, 12, z64 ? 0xFFFFFFFFL : cdSize);
        put32(e, 16, z64 ? 0xFFFFFFFFL : cdStart);
        put16(e, 20, 0);
        out.write(e);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
    }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static void put64(byte[] b, int o, long v) {
        put32(b, o, v & 0xFFFFFFFFL);
        put32(b, o + 4, v >>> 32);
    }
}
