package com.bloatware.bingblop;

import com.github.junrar.Archive;
import com.github.junrar.exception.CrcErrorException;
import com.github.junrar.io.SeekableReadOnlyByteChannel;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.HostSystem;
import com.github.junrar.rarfile.MainHeader;
import com.github.junrar.volume.Volume;
import com.github.junrar.volume.VolumeManager;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Read-only access to RAR archives, pure Java.
 *
 * <ul>
 *   <li>RAR 1.5 - 4.x ("rar4"): through the junrar library in {@code libs/} (slf4j has no binding here, which junrar and slf4j accept: log lines are dropped).
 *       Passwords (file data and, for junrar 7.5.5, the -hp encrypted headers) are handled by junrar; the library cannot tell a wrong password from damaged
 *       data, so a failed decode of an encrypted entry is reported as {@code PasswordException(wrong = true)}.</li>
 *   <li>RAR 5 / 7 ("rar5"): implemented here (junrar cannot read it). Block / header parsing with vint and CRC32, stored and compressed files
 *       (solid and not), the LZ decoder with the delta, E8, E8E9 and ARM filters, file / folder / symbolic link entries, modification times,
 *       AES-256-CBC encrypted data and encrypted headers (PBKDF2-HMAC-SHA256, password check value), CRC32 and BLAKE2sp file hashes (also the keyed
 *       "MAC" form used with encryption).</li>
 *   <li>Not supported: archives in several volumes (an entry that goes on in the next volume fails with {@code IOException("multi-volume")}, the other
 *       entries of the volume can be read), RAR 1.3 / 1.4, the RAR 7.10+ ARM64 filter (fails with {@code "unsupported-filter"}), RAR5 hard links and
 *       file copies are listed with {@code hardLink} and have no data. A dictionary above 256 MB is refused with {@code IOException("dictionary-too-large")}.</li>
 * </ul>
 *
 * <p><b>What is verified, and what is not.</b> No real RAR5 archive and no rar program was reachable when the RAR5 part was written (the fixture hosts were
 * refused by the sandbox), so every RAR5 feature below is checked only against archives that tests/java/src/RarReaderTest.java writes itself, with an
 * encoder written from the same reading of the format: the block / header layout, vint, CRC32 and the encrypted header framing, the bit stream of the LZ
 * unpacker (block headers, Huffman tables with run length coded lengths and codes of up to 15 bits, literals, matches, repeats, window wrap, solid
 * continuation, several blocks, unknown size), the four filters, file / folder / link / hard link / time / hash records, the AES-256-CBC framing. Compared
 * with independent implementations: PBKDF2 keys, MAC key and password check value (JDK PBKDF2 at 2^n, +16 and +32 iterations) and BLAKE2sp (Python hashlib).
 * Not checked against anything but memory of unrar / libarchive: the nanosecond fields of the extra time record (ignored here, only the first time is used),
 * the RAR 7 fractional dictionary field of a version 1 file header, the assumption that the 64 distance codes (not 80) are in use for every dictionary up
 * to 4 GB (larger ones are refused anyway), the ARM64 filter of RAR 7.10+ (refused), the keyed ("MAC") form of CRC32 / BLAKE2sp for encrypted files
 * (the writer in the test uses the same formula). RAR 1.5 - 4.x compressed data (LZ, PPMd, the RarVM filters) is junrar's code and was not run here at all:
 * the RAR4 tests use stored entries (also encrypted, and with -hp headers: the key derivation of the test is an independent write-up of unrar's and junrar
 * accepts it). Real archives dropped into tests/java/fixtures/rar are run by the same suite (see the README there).
 *
 * Errors are {@link IOException}s whose message is one of: {@code checksum}, {@code truncated}, {@code corrupt}, {@code header-crc}, {@code multi-volume},
 * {@code dictionary-too-large}, {@code out-of-memory}, {@code unsupported-filter}, {@code unsupported-version}, {@code not-rar}
 * or {@link PasswordException}.
 */
public final class RarReader {
    private RarReader() {}

    /** A listing never holds more items than this ({@link Info#truncated} is set when it was cut). */
    public static final int MAX_ITEMS = 1000000;
    /** The largest LZ dictionary that is decoded (RAR5 allows up to 4 GB, which no phone can hold). */
    public static final long MAX_DICT = 256L * 1024 * 1024;
    /** A stub (self-extractor) in front of the archive may be this long. */
    public static final int SFX_SCAN = 1024 * 1024;

    // ---------------------------------------------------------------------------------------------------------------- public types

    /** One entry. */
    public static final class Item {
        /** Path as stored, forward slashes; a folder ends with '/'. */
        public String name;
        public boolean dir;
        /** Uncompressed size, -1 if unknown. 0 for folders and links. */
        public long size = -1;
        /** Packed size (the data area), -1 if unknown. */
        public long csize = -1;
        /** Last modified, ms since the epoch, 0 if unknown. */
        public long mtime;
        /** Unix permission bits (07777), -1 if unknown (the archive was made on Windows). */
        public int mode = -1;
        public boolean encrypted;
        /** Target of a symbolic link, null if the entry is not one. */
        public String linkTarget;
        /** Name a RAR5 hard link or file copy points at, null if the entry is not one (it has no data of its own). */
        public String hardLink;
        public String toString() { return name + (dir ? "" : " (" + size + ")"); }
    }

    /** What a listing found. */
    public static final class Info {
        /** "rar4" or "rar5". */
        public String format;
        public List<Item> items = new ArrayList<Item>();
        /** Files are packed with the data of the files before them: reading one late in the archive means unpacking the earlier ones. */
        public boolean solid;
        /** The names are encrypted (rar -hp). */
        public boolean headerEncrypted;
        public boolean anyEncrypted;
        /** The listing stopped at {@link #MAX_ITEMS}. */
        public boolean truncated;
        /** The archive is one volume of several (the entries that go on in another volume cannot be read). */
        public boolean multiVolume;
    }

    /** A password is needed ({@code wrong == false}) or the one given does not fit ({@code wrong == true}; the archive may also be damaged). */
    public static class PasswordException extends IOException {
        private static final long serialVersionUID = 1L;
        public final boolean wrong;
        public PasswordException(boolean wrong) { super(wrong ? "password-wrong" : "password-required"); this.wrong = wrong; }
    }

    public interface Visitor {
        /** Called once per entry in archive order. {@code data} is valid only during the call; unread data is skipped. Return false to stop. */
        boolean entry(Item it, InputStream data) throws IOException;
    }

    // ---------------------------------------------------------------------------------------------------------------- public API

    /** True if the file is a RAR archive (RAR 1.5 - 4.x or RAR 5 / 7 magic), also behind a self-extractor stub of up to 1 MB. */
    public static boolean isRar(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            return findSignature(raf) != null;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(raf);
        }
    }

    /** The entries of an archive (headers only; the password is needed only for -hp archives). */
    public static Info list(File f, char[] password) throws IOException {
        Engine e = openEngine(f, password);
        try {
            e.resolveLinks();
            return e.info;
        } finally {
            e.close();
        }
    }

    /** One pass over all entries in archive order; see {@link Visitor}. */
    public static void walk(File f, char[] password, Visitor v) throws IOException {
        Engine e = openEngine(f, password);
        try {
            e.walk(v);
        } finally {
            e.close();
        }
    }

    /** The data of one entry (the first with this name). The stream must be closed. Opening an entry of a solid archive decodes the entries before it. */
    public static InputStream open(File f, String itemName, char[] password) throws IOException {
        Engine e = openEngine(f, password);
        boolean ok = false;
        try {
            int idx = -1;
            List<Item> items = e.info.items;
            for (int i = 0; i < items.size() && idx < 0; i++) if (items.get(i).name.equals(itemName)) idx = i;
            if (idx < 0 && !itemName.endsWith("/")) {
                for (int i = 0; i < items.size() && idx < 0; i++) if (items.get(i).dir && items.get(i).name.equals(itemName + "/")) idx = i;
            }
            if (idx < 0) throw new FileNotFoundException(itemName);
            InputStream in = e.open(idx);
            ok = true;
            return in;
        } finally {
            if (!ok) e.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- engines

    private abstract static class Engine {
        final Info info = new Info();
        /** Entries in archive order. {@link #info} items are the same objects, in the same order. */
        abstract void walk(Visitor v) throws IOException;
        abstract InputStream open(int index) throws IOException;
        void resolveLinks() throws IOException {}
        abstract void close();
    }

    private static Engine openEngine(File f, char[] pw) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        boolean keep = false;
        try {
            long[] sig = findSignature(raf);
            if (sig == null) throw new IOException("not-rar");
            if (sig[1] == 5) {
                Rar5 r = new Rar5(raf, sig[0], pw);
                keep = true;
                try {
                    r.readHeaders();
                } catch (IOException e) {
                    r.close();
                    throw e;
                } catch (RuntimeException e) {
                    r.close();
                    throw new IOException("corrupt", e);
                } catch (OutOfMemoryError e) {
                    r.close();
                    throw new IOException("out-of-memory");
                }
                return r;
            }
            boolean hdrEnc = rar4HeaderEncrypted(raf, sig[0]);
            raf.close();
            keep = true;
            return Rar4.open(f, sig[0], pw, hdrEnc);
        } finally {
            if (!keep) closeQuietly(raf);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- signature

    private static boolean sigAt(byte[] b, int i, int end) {
        return i + 7 <= end && b[i] == 'R' && b[i + 1] == 'a' && b[i + 2] == 'r' && b[i + 3] == '!' && b[i + 4] == 0x1A && b[i + 5] == 0x07;
    }

    /** {offset of the signature, 4 or 5}, or null. */
    static long[] findSignature(RandomAccessFile raf) throws IOException {
        long len = raf.length();
        long limit = Math.min(len, (long) SFX_SCAN + 8);
        byte[] buf = new byte[65536 + 16];
        long off = 0;
        while (off + 7 <= limit) {
            int want = (int) Math.min(65536, limit - off);
            boolean lastChunk = off + want >= limit;
            raf.seek(off);
            raf.readFully(buf, 0, want);
            for (int i = 0; i + 7 <= want; i++) {
                if (!sigAt(buf, i, want)) continue;
                int ver = 0;
                if (buf[i + 6] == 0) ver = 4;
                else if (buf[i + 6] == 1) {
                    if (i + 8 > want) { if (!lastChunk) break; continue; }
                    if (buf[i + 7] == 0) ver = 5;
                }
                if (ver == 0) continue;
                long at = off + i;
                if (at == 0 || validSignature(raf, at, ver, len)) return new long[]{at, ver};
                raf.seek(off);   // (validSignature moved the pointer, the buffer is still ours)
            }
            if (lastChunk) break;
            off += want - 8;
        }
        return null;
    }

    private static boolean validSignature(RandomAccessFile raf, long at, int ver, long len) {
        try {
            if (ver == 4) {
                if (at + 7 + 13 > len) return false;
                byte[] h = new byte[13];
                raf.seek(at + 7);
                raf.readFully(h);
                int size = (h[5] & 0xff) | (h[6] & 0xff) << 8;
                if (h[2] != 0x73 || size < 13 || size > 4096 || at + 7 + size > len) return false;
                byte[] all = new byte[size];
                raf.seek(at + 7);
                raf.readFully(all);
                CRC32 c = new CRC32();
                c.update(all, 2, size - 2);
                return (int) (c.getValue() & 0xFFFF) == ((all[0] & 0xff) | (all[1] & 0xff) << 8);
            }
            long pos = at + 8;
            if (pos + 6 > len) return false;
            byte[] first = new byte[Math.min(8, (int) Math.min(len - pos, 8))];
            raf.seek(pos);
            raf.readFully(first);
            int p = 4;
            long size = 0;
            int vl = 0;
            for (int k = 0; k < 3 && p < first.length; k++) {
                int c = first[p++] & 0xff;
                size |= (long) (c & 0x7f) << (7 * k);
                vl++;
                if ((c & 0x80) == 0) break;
                if (k == 2) return false;
            }
            if (size < 2 || size > 0x200000 || pos + 4 + vl + size > len) return false;
            byte[] blk = new byte[vl + (int) size];
            raf.seek(pos + 4);
            raf.readFully(blk);
            CRC32 c = new CRC32();
            c.update(blk, 0, blk.length);
            long stored = (first[0] & 0xffL) | (first[1] & 0xffL) << 8 | (first[2] & 0xffL) << 16 | (first[3] & 0xffL) << 24;
            if (c.getValue() != stored) return false;
            int type = blk[vl] & 0xff;
            return type == 1 || type == 4;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean rar4HeaderEncrypted(RandomAccessFile raf, long base) {
        try {
            byte[] h = new byte[7];
            raf.seek(base + 7);
            raf.readFully(h);
            return h[2] == 0x73 && (((h[3] & 0xff) | (h[4] & 0xff) << 8) & 0x0080) != 0;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- small helpers

    static void closeQuietly(RandomAccessFile r) {
        if (r != null) try { r.close(); } catch (IOException ignored) { }
    }

    private static final InputStream EMPTY = new InputStream() {
        @Override public int read() { return -1; }
        @Override public int read(byte[] b, int off, int len) { return len == 0 ? 0 : -1; }
    };

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static IOException bad() { return new IOException("corrupt"); }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static void drain(InputStream in) throws IOException {
        byte[] sink = new byte[65536];
        while (in.read(sink, 0, sink.length) >= 0) { /* decode and check, discard */ }
    }

    // ================================================================================================================================
    //  RAR 1.5 - 4.x through junrar
    // ================================================================================================================================

    /** The archive as junrar reads it: from the signature on (a stub before it is not seen), one volume only. */
    private static final class OffVolume implements Volume {
        final File file;
        final long base;
        final long length;
        Archive archive;
        OffVolume(File f, long base) { this.file = f; this.base = base; this.length = f.length() - base; }
        @Override public SeekableReadOnlyByteChannel getChannel() throws IOException { return new OffChannel(file, base); }
        @Override public long getLength() { return length; }
        @Override public Archive getArchive() { return archive; }
    }

    private static final class OffChannel implements SeekableReadOnlyByteChannel {
        final RandomAccessFile raf;
        final long base;
        OffChannel(File f, long base) throws IOException { this.raf = new RandomAccessFile(f, "r"); this.base = base; raf.seek(base); }
        @Override public long getPosition() throws IOException { return raf.getFilePointer() - base; }
        @Override public void setPosition(long pos) throws IOException { raf.seek(base + pos); }
        @Override public int read() throws IOException { return raf.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return raf.read(b, off, len); }
        @Override public int readFully(byte[] b, int count) throws IOException { raf.readFully(b, 0, count); return count; }
        @Override public void close() throws IOException { raf.close(); }
    }

    private static final OutputStream NULL_OUT = new OutputStream() {
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    };

    private static final class Rar4 extends Engine {
        final Archive arc;
        final char[] pw;
        final List<FileHeader> hdrs = new ArrayList<FileHeader>();
        final boolean[] hasData;

        static Rar4 open(File f, long base, char[] pw, boolean headerEnc) throws IOException {
            if (headerEnc && pw == null) throw new PasswordException(false);
            final OffVolume vol = new OffVolume(f, base);
            VolumeManager vm = new VolumeManager() {
                @Override public Volume nextVolume(Archive a, Volume last) {
                    if (last != null) return null;      // a single volume
                    vol.archive = a;
                    return vol;
                }
            };
            Archive a = null;
            try {
                a = new Archive(vm, null, pw == null ? null : new String(pw));
                MainHeader mh = a.getMainHeader();
                if (mh == null) throw bad();
                return new Rar4(a, pw, mh);
            } catch (IOException e) {
                closeArc(a);
                if (headerEnc) throw new PasswordException(true);
                throw e;
            } catch (Exception e) {
                closeArc(a);
                if (headerEnc) throw new PasswordException(true);
                String m = e.getMessage();
                throw new IOException(e instanceof com.github.junrar.exception.RarException && m == null ? "corrupt" : "rar: " + (m == null ? e.getClass().getSimpleName() : m), e);
            } catch (OutOfMemoryError e) {
                closeArc(a);
                throw new IOException("out-of-memory");
            }
        }

        static void closeArc(Archive a) { if (a != null) try { a.close(); } catch (Exception ignored) { } }

        Rar4(Archive a, char[] pw, MainHeader mh) throws IOException {
            this.arc = a;
            this.pw = pw;
            info.format = "rar4";
            info.solid = mh.isSolid();
            info.headerEncrypted = mh.isEncrypted();
            info.multiVolume = mh.isMultiVolume();
            List<FileHeader> all = a.getFileHeaders();
            hasData = new boolean[Math.min(all.size(), MAX_ITEMS)];
            for (FileHeader h : all) {
                if (hdrs.size() >= MAX_ITEMS) { info.truncated = true; break; }
                Item it = new Item();
                String nm = h.getFileName();
                if (nm == null) nm = "";
                HostSystem hs = h.getHostOS();
                boolean unix = hs == HostSystem.unix || hs == HostSystem.macos || hs == HostSystem.beos;
                if (!unix) nm = nm.replace('\\', '/');
                it.dir = h.isDirectory();
                if (it.dir && !nm.endsWith("/")) nm += "/";
                it.name = nm;
                it.size = it.dir ? 0 : h.getFullUnpackSize();
                it.csize = h.getFullPackSize();
                Date d = h.getMTime();
                it.mtime = d == null ? 0 : d.getTime();
                if (unix) it.mode = h.getFileAttr() & 07777;
                it.encrypted = h.isEncrypted();
                if (it.encrypted) info.anyEncrypted = true;
                hasData[hdrs.size()] = !it.dir && it.size > 0;
                if (!it.dir && unix && h.getHostOS() == HostSystem.unix && (h.getFileAttr() & 0170000) == 0120000) {
                    it.linkTarget = "";          // the target is in the data: read by list() and by walk()
                    it.size = 0;
                }
                hdrs.add(h);
                info.items.add(it);
            }
            if (info.headerEncrypted) {
                info.anyEncrypted = true;
                // junrar stops at the first header that does not decrypt into a header (a wrong password), without an error
                if (hdrs.isEmpty()) throw new PasswordException(true);
            }
        }

        private boolean isLink(int i) { return info.items.get(i).linkTarget != null; }

        /** Reads the target of a link entry (the unpacker is in the right state: the entry is next in line). */
        private void readTarget(int i) {
            Item it = info.items.get(i);
            long len = hdrs.get(i).getFullUnpackSize();
            if (len <= 0 || len > 4096 || (it.encrypted && pw == null)) return;
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                extractSync(i, bo, 4096);
                it.linkTarget = new String(bo.toByteArray(), UTF8);
            } catch (IOException e) {
                // the target stays unknown ("")
            }
        }

        /** Does an entry after {@code i} need the unpacker state left by the ones up to {@code i}? */
        private boolean needState(int i) {
            for (int j = i + 1; j < hdrs.size(); j++) {
                if (hasData[j]) return hdrs.get(j).isSolid();
            }
            return false;
        }

        @Override void resolveLinks() {
            for (int i = 0; i < hdrs.size(); i++) {
                if (!isLink(i) || !hasData[i]) continue;
                try {
                    startChain(i);
                } catch (IOException e) {
                    continue;
                }
                readTarget(i);
            }
        }

        /** Decode the entries before {@code t} that {@code t} depends on (solid). */
        private void startChain(int t) throws IOException {
            if (!hasData[t] || !hdrs.get(t).isSolid()) return;
            int c = t;
            for (int k = t - 1; k >= 0; k--) {
                if (!hasData[k]) continue;
                c = k;
                if (!hdrs.get(k).isSolid()) break;
            }
            for (int k = c; k < t; k++) if (hasData[k]) extractSync(k, NULL_OUT, -1);
        }

        private IOException map(Throwable t, int i) {
            if (t instanceof PasswordException) return (PasswordException) t;
            if (t instanceof OutOfMemoryError) return new IOException("out-of-memory");
            for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
                if (c instanceof IOException && ("aborted".equals(c.getMessage()) || "limit".equals(c.getMessage()))) return (IOException) c;
            }
            FileHeader h = hdrs.get(i);
            if (h.isSplitAfter() || h.isSplitBefore()) return new IOException("multi-volume");
            boolean enc = info.items.get(i).encrypted && pw != null;
            if (t instanceof CrcErrorException) return enc ? new PasswordException(true) : new IOException("checksum");
            if (enc) return new PasswordException(true);
            if (t instanceof EOFException) return new IOException("truncated");
            String m = t.getMessage();
            return new IOException("corrupt" + (m == null ? "" : ": " + m), t);
        }

        private void checkEntry(int i) throws IOException {
            FileHeader h = hdrs.get(i);
            if (h.isSplitAfter() || h.isSplitBefore()) throw new IOException("multi-volume");
            if (info.items.get(i).encrypted && pw == null) throw new PasswordException(false);
        }

        /** Run the extraction in this thread; at most {@code cap} bytes are accepted when cap >= 0. */
        private void extractSync(int i, OutputStream to, final long cap) throws IOException {
            checkEntry(i);
            OutputStream o = to;
            if (cap >= 0) {
                final OutputStream inner = to;
                o = new OutputStream() {
                    long n;
                    @Override public void write(int b) throws IOException { if (++n > cap) throw new IOException("limit"); inner.write(b); }
                    @Override public void write(byte[] b, int off, int len) throws IOException { n += len; if (n > cap) throw new IOException("limit"); inner.write(b, off, len); }
                };
            }
            try {
                arc.extractFile(hdrs.get(i), o);
            } catch (Throwable t) {
                throw map(t, i);
            }
        }

        @Override void walk(Visitor v) throws IOException {
            for (int i = 0; i < hdrs.size(); i++) {
                Item it = info.items.get(i);
                boolean cont;
                if (isLink(i)) {
                    if (hasData[i]) readTarget(i);
                    cont = v.entry(it, EMPTY);
                } else if (!hasData[i]) {
                    cont = v.entry(it, EMPTY);
                } else {
                    R4In s = new R4In(this, i, false);
                    try {
                        cont = v.entry(it, s);
                    } catch (Throwable t) {
                        s.abort();
                        if (t instanceof IOException) throw (IOException) t;
                        if (t instanceof RuntimeException) throw (RuntimeException) t;
                        throw (Error) t;
                    }
                    s.finish(needState(i));
                }
                if (!cont) break;
            }
        }

        @Override InputStream open(int index) throws IOException {
            if (!hasData[index] || isLink(index)) { close(); return EMPTY; }
            checkEntry(index);
            startChain(index);
            return new R4In(this, index, true);
        }

        @Override void close() { closeArc(arc); }
    }

    /** The data of one entry; junrar pushes it, a thread of ours hands it over. */
    private static final class R4In extends InputStream {
        private static final byte[] END = new byte[0];
        final Rar4 owner;
        final int idx;
        final boolean closeOwner;
        final long total;
        ArrayBlockingQueue<Object> q;
        Thread thread;
        volatile boolean aborted;
        byte[] cur;
        int curPos;
        long delivered;
        boolean sawEnd, closed, joined;

        R4In(Rar4 owner, int idx, boolean closeOwner) {
            this.owner = owner;
            this.idx = idx;
            this.closeOwner = closeOwner;
            this.total = owner.info.items.get(idx).size;
        }

        private void start() throws IOException {
            owner.checkEntry(idx);
            q = new ArrayBlockingQueue<Object>(6);
            final ArrayBlockingQueue<Object> queue = q;
            final FileHeader h = owner.hdrs.get(idx);
            final OutputStream sink = new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    int o = off;
                    while (len > 0) {
                        int n = Math.min(len, 32768);
                        put(Arrays.copyOfRange(b, o, o + n));
                        o += n;
                        len -= n;
                    }
                }
                void put(Object x) throws IOException {
                    try {
                        while (!queue.offer(x, 50, TimeUnit.MILLISECONDS)) if (aborted) throw new IOException("aborted");
                        if (aborted) throw new IOException("aborted");
                    } catch (InterruptedException e) {
                        throw new IOException("aborted");
                    }
                }
            };
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    Object last = END;
                    try {
                        owner.arc.extractFile(h, sink);
                    } catch (Throwable t) {
                        last = t;
                    }
                    try {
                        while (!queue.offer(last, 50, TimeUnit.MILLISECONDS)) if (aborted) return;
                    } catch (InterruptedException ignored) { }
                }
            }, "rar4-extract");
            thread.setDaemon(true);
            try {
                thread.start();
            } catch (OutOfMemoryError e) {
                thread = null;
                throw new IOException("out-of-memory");
            }
        }

        private boolean fill() throws IOException {
            if (sawEnd) return false;
            if (q == null) start();
            Object x;
            try {
                x = q.take();
            } catch (InterruptedException e) {
                throw new IOException("aborted");
            }
            if (x == END) { sawEnd = true; joinThread(); return false; }
            if (x instanceof Throwable) { sawEnd = true; joinThread(); throw owner.map((Throwable) x, idx); }
            cur = (byte[]) x;
            curPos = 0;
            return true;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (closed) throw new IOException("closed");
            while (cur == null || curPos >= cur.length) {
                if (!fill()) return -1;
            }
            int n = Math.min(len, cur.length - curPos);
            System.arraycopy(cur, curPos, b, off, n);
            curPos += n;
            delivered += n;
            return n;
        }

        private void joinThread() {
            if (thread == null || joined) return;
            try { thread.join(); } catch (InterruptedException ignored) { }
            joined = true;
        }

        void abort() {
            aborted = true;
            if (q != null) q.clear();
            joinThread();
        }

        /** After the visitor: finish or drop what is left. */
        void finish(boolean needState) throws IOException {
            if (q == null) {
                if (needState) owner.extractSync(idx, NULL_OUT, -1);
                return;
            }
            try {
                if (!sawEnd) {
                    if (needState || (total >= 0 && delivered >= total)) {
                        cur = null;
                        while (fill()) { /* drain, errors surface here */ }
                    } else {
                        aborted = true;
                        q.clear();
                        joinThread();
                    }
                }
            } finally {
                aborted = true;
                joinThread();
            }
        }

        @Override public void close() throws IOException {
            if (closeOwner) {
                if (q != null && !sawEnd) { aborted = true; q.clear(); joinThread(); }
                owner.close();
            }
            closed = true;
        }
    }

    // ================================================================================================================================
    //  RAR 5 / 7
    // ================================================================================================================================

    private static final class Rd {
        final byte[] b;
        int p;
        final int end;
        Rd(byte[] b, int p, int end) { this.b = b; this.p = p; this.end = end; }
        int left() { return end - p; }
        int u8() throws IOException { if (p >= end) throw bad(); return b[p++] & 0xff; }
        long u32() throws IOException { if (end - p < 4) throw bad(); long v = le32(b, p); p += 4; return v; }
        long u64() throws IOException { if (end - p < 8) throw bad(); long v = le32(b, p) | le32(b, p + 4) << 32; p += 8; return v; }
        long vint() throws IOException {
            long v = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                if (p >= end) throw bad();
                int c = b[p++] & 0xff;
                if (shift < 63) v |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) return v;
            }
            throw bad();
        }
        /** A size or count: not negative. */
        long vlen() throws IOException { long v = vint(); if (v < 0) throw bad(); return v; }
        byte[] bytes(int n) throws IOException {
            if (n < 0 || n > end - p) throw bad();
            byte[] r = Arrays.copyOfRange(b, p, p + n);
            p += n;
            return r;
        }
    }

    /** Keys of one password + salt + count. */
    static final class Keys {
        final byte[] key = new byte[32];
        final byte[] hashKey = new byte[32];
        final byte[] check = new byte[8];
    }

    /**
     * RAR5 key derivation: PBKDF2-HMAC-SHA256 with 2^lg2 iterations gives the AES key, 16 more iterations the key of the checksum MAC, 16 more the password
     * check value (folded to 8 bytes). Package-private for the test, which compares it with the JDK's PBKDF2.
     */
    static Keys deriveKeys(byte[] pwUtf8, byte[] salt, int lg2) throws IOException {
        if (lg2 < 0 || lg2 > 24) throw new IOException("unsupported-kdf");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pwUtf8.length == 0 ? new byte[1] : pwUtf8, "HmacSHA256"));
            byte[] salted = Arrays.copyOf(salt, salt.length + 4);
            salted[salt.length + 3] = 1;
            byte[] u = mac.doFinal(salted);
            byte[] t = u.clone();
            long count = 1L << lg2;
            Keys k = new Keys();
            long[] steps = {count - 1, 16, 16};
            byte[][] outs = new byte[3][];
            for (int s = 0; s < 3; s++) {
                for (long i = 0; i < steps[s]; i++) {
                    mac.update(u, 0, 32);
                    mac.doFinal(u, 0);
                    for (int j = 0; j < 32; j++) t[j] ^= u[j];
                }
                outs[s] = t.clone();
            }
            System.arraycopy(outs[0], 0, k.key, 0, 32);
            System.arraycopy(outs[1], 0, k.hashKey, 0, 32);
            for (int i = 0; i < 32; i++) k.check[i & 7] ^= outs[2][i];
            return k;
        } catch (GeneralSecurityException e) {
            throw new IOException("unsupported-crypto: " + e.getMessage());
        }
    }

    private static final class Enc {
        byte[] salt, iv, check;
        int lg2;
        boolean mac;
    }

    private static final class R5Entry {
        Item item;
        long dataPos, packSize, unpSize;
        int method, ver;
        boolean solidBit, hasCrc, split;
        long dict;
        long crc;
        byte[] blake;
        Enc enc;
        boolean decode() { return !item.dir && method != 0 && packSize > 0; }
        boolean hasData() { return !item.dir && packSize > 0 && item.linkTarget == null && item.hardLink == null; }
    }

    private static final class Rar5 extends Engine {
        final RandomAccessFile raf;
        final long base, flen;
        final char[] pw;
        final byte[] pwBytes;
        final List<R5Entry> ents = new ArrayList<R5Entry>();
        final Map<String, Keys> keyCache = new HashMap<String, Keys>();
        Keys hdrKeys;
        /** The password check of the header encryption passed (so a header that then fails its CRC is damage, not a wrong password). */
        boolean hdrVerified;
        Rar5Decoder dec;

        Rar5(RandomAccessFile raf, long base, char[] pw) throws IOException {
            this.raf = raf;
            this.base = base;
            this.flen = raf.length();
            this.pw = pw;
            this.pwBytes = pw == null ? null : new String(pw).getBytes(UTF8);
            info.format = "rar5";
        }

        Keys keys(Enc e) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (byte x : e.salt) sb.append(Integer.toHexString((x & 0xff) | 0x100).substring(1));
            sb.append(':').append(e.lg2);
            String k = sb.toString();
            Keys v = keyCache.get(k);
            if (v == null) {
                v = deriveKeys(pwBytes, e.salt, e.lg2);
                keyCache.put(k, v);
            }
            return v;
        }

        /** 1 = the check value fits, 0 = it does not, -1 = there is none (or it is damaged). */
        static int checkValue(byte[] chk, Keys k) {
            if (chk == null) return -1;
            try {
                byte[] sum = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(chk, 8));
                for (int i = 0; i < 4; i++) if (sum[i] != chk[8 + i]) return -1;
            } catch (GeneralSecurityException e) {
                return -1;
            }
            for (int i = 0; i < 8; i++) if (chk[i] != k.check[i]) return 0;
            return 1;
        }

        private void readAt(long pos, byte[] b, int off, int len) throws IOException {
            if (pos < 0 || len < 0 || pos + len > flen) throw new IOException("truncated");
            raf.seek(pos);
            raf.readFully(b, off, len);
        }

        private static final class Blk {
            int type;
            long flags, extraSize, dataSize;
            byte[] hb;
            Rd r;
            long dataPos, next;
        }

        private Blk readBlock(long pos) throws IOException {
            Blk b = new Blk();
            byte[] block;      // the size vint and the header
            int vl;
            long size;
            if (hdrKeys == null) {
                if (flen - pos < 6) return null;
                byte[] first = new byte[(int) Math.min(7, flen - pos)];
                readAt(pos, first, 0, first.length);
                int p = 4;
                size = 0;
                vl = 0;
                for (int k = 0; ; k++) {
                    if (p >= first.length || k == 3) throw bad();
                    int c = first[p++] & 0xff;
                    size |= (long) (c & 0x7f) << (7 * k);
                    vl++;
                    if ((c & 0x80) == 0) break;
                }
                if (size < 2 || size > 0x200000) throw bad();
                if (pos + 4 + vl + size > flen) throw new IOException("truncated");
                block = new byte[vl + (int) size];
                readAt(pos + 4, block, 0, block.length);
                CRC32 c = new CRC32();
                c.update(block, 0, block.length);
                if (c.getValue() != le32(first, 0)) throw new IOException("header-crc");
                b.dataPos = pos + 4 + vl + size;
            } else {
                if (flen - pos < 32) return null;
                byte[] iv = new byte[16];
                readAt(pos, iv, 0, 16);
                byte[] first = new byte[16];
                readAt(pos + 16, first, 0, 16);
                byte[] plain = aesBlocks(hdrKeys.key, iv, first);
                int p = 4;
                size = 0;
                vl = 0;
                for (int k = 0; ; k++) {
                    if (p >= 16 || k == 3) throw hdrBad();
                    int c = plain[p++] & 0xff;
                    size |= (long) (c & 0x7f) << (7 * k);
                    vl++;
                    if ((c & 0x80) == 0) break;
                }
                if (size < 2 || size > 0x200000) throw hdrBad();
                long total = 4 + vl + size;
                long padded = (total + 15) & ~15L;
                if (pos + 16 + padded > flen) throw new IOException("truncated");
                byte[] enc = new byte[(int) padded];
                readAt(pos + 16, enc, 0, enc.length);
                plain = aesBlocks(hdrKeys.key, iv, enc);
                block = Arrays.copyOfRange(plain, 4, 4 + vl + (int) size);
                CRC32 c = new CRC32();
                c.update(block, 0, block.length);
                if (c.getValue() != le32(plain, 0)) throw hdrBad();
                b.dataPos = pos + 16 + padded;
            }
            // block = size vint + header; the header's own fields start after the vint
            Rd r = new Rd(block, vl, block.length);
            b.hb = block;
            b.type = (int) r.vint();
            b.flags = r.vint();
            b.extraSize = (b.flags & 1) != 0 ? r.vlen() : 0;
            b.dataSize = (b.flags & 2) != 0 ? r.vlen() : 0;
            if (b.extraSize > r.left()) throw bad();
            b.r = new Rd(block, r.p, block.length - (int) b.extraSize);
            if (b.dataSize > flen - b.dataPos) throw new IOException("truncated");
            b.next = b.dataPos + b.dataSize;
            return b;
        }

        private IOException hdrBad() { return hdrVerified ? new IOException("header-crc") : new PasswordException(true); }

        void readHeaders() throws IOException {
            long pos = base + 8;
            info.items.clear();
            boolean sawMain = false;
            while (true) {
                Blk b = readBlock(pos);
                if (b == null) break;
                pos = b.next;
                Rd r = b.r;
                if (b.type == 4) {
                    if (hdrKeys != null || sawMain) throw bad();
                    long ver = r.vint();
                    long fl = r.vint();
                    int lg2 = r.u8();
                    byte[] salt = r.bytes(16);
                    byte[] chk = (fl & 1) != 0 ? r.bytes(12) : null;
                    if (ver != 0) throw new IOException("unsupported-version");
                    info.headerEncrypted = true;
                    info.anyEncrypted = true;
                    if (pwBytes == null) throw new PasswordException(false);
                    Enc e = new Enc();
                    e.salt = salt;
                    e.lg2 = lg2;
                    Keys k = keys(e);
                    int cv = checkValue(chk, k);
                    if (cv == 0) throw new PasswordException(true);
                    hdrVerified = cv == 1;
                    hdrKeys = k;
                } else if (b.type == 1) {
                    sawMain = true;
                    long af = r.vint();
                    info.multiVolume = (af & 1) != 0;
                    info.solid = (af & 4) != 0;
                } else if (b.type == 2) {
                    if (ents.size() >= MAX_ITEMS) { info.truncated = true; break; }
                    readFile(b);
                } else if (b.type == 5) {
                    break;
                }
                // (service headers: comment, quick open, recovery record, ... and unknown blocks are skipped)
            }
        }

        private void readFile(Blk b) throws IOException {
            Rd r = b.r;
            R5Entry e = new R5Entry();
            Item it = new Item();
            e.item = it;
            long ff = r.vint();
            long unp = r.vint();
            long attr = r.vint();
            long mtime = 0;
            boolean hasMtime = (ff & 2) != 0;
            if (hasMtime) mtime = r.u32();
            if ((ff & 4) != 0) { e.hasCrc = true; e.crc = r.u32(); }
            long ci = r.vint();
            long host = r.vint();
            long nl = r.vlen();
            if (nl == 0 || nl > 32768 || nl > r.left()) throw bad();
            String name = new String(r.bytes((int) nl), UTF8);
            e.ver = (int) (ci & 0x3f);
            e.solidBit = (ci & 0x40) != 0;
            e.method = (int) ((ci >> 7) & 7);
            long n = (ci >> 10) & 0x1f;
            long dict = 0x20000L << n;
            if (e.ver == 1) dict += dict / 32 * ((ci >> 15) & 0x1f);
            e.dict = dict;
            e.split = (b.flags & 0x18) != 0;
            e.dataPos = b.dataPos;
            e.packSize = b.dataSize;
            boolean dir = (ff & 1) != 0;
            e.unpSize = (ff & 8) != 0 ? -1 : unp;
            if (e.unpSize < 0 && (ff & 8) == 0) throw bad();
            it.dir = dir;
            it.name = dir && !name.endsWith("/") ? name + "/" : name;
            it.size = dir ? 0 : e.unpSize;
            it.csize = b.dataSize;
            if (hasMtime) it.mtime = mtime * 1000L;
            if (host == 1) it.mode = (int) (attr & 07777);
            // extra area
            Rd x = new Rd(b.hb, b.hb.length - (int) b.extraSize, b.hb.length);
            while (x.left() > 0) {
                long sz = x.vlen();
                if (sz == 0 || sz > x.left()) break;
                int recEnd = x.p + (int) sz;
                Rd s = new Rd(b.hb, x.p, recEnd);
                long type = s.vint();
                try {
                    if (type == 1) {                                    // encryption
                        long ver = s.vint();
                        long fl = s.vint();
                        if (ver != 0) throw new IOException("unsupported-version");
                        Enc en = new Enc();
                        en.lg2 = s.u8();
                        en.salt = s.bytes(16);
                        en.iv = s.bytes(16);
                        if ((fl & 1) != 0) en.check = s.bytes(12);
                        en.mac = (fl & 2) != 0;
                        e.enc = en;
                        it.encrypted = true;
                        info.anyEncrypted = true;
                    } else if (type == 2) {                             // hash
                        long ht = s.vint();
                        if (ht == 0 && s.left() >= 32) e.blake = s.bytes(32);
                    } else if (type == 3) {                             // times: the first value is the modification time
                        long fl = s.vint();
                        if ((fl & 2) != 0) {
                            if ((fl & 1) != 0) { long t = s.u32(); if (t != 0) it.mtime = t * 1000L; }
                            else { long ft = s.u64(); if (ft > 0) it.mtime = ft / 10000L - 11644473600000L; }
                        }
                    } else if (type == 5) {                             // redirection
                        long rt = s.vint();
                        s.vint();
                        long l = s.vlen();
                        if (l > s.left()) throw bad();
                        String target = new String(s.bytes((int) l), UTF8);
                        if (rt == 1) it.linkTarget = target;
                        else if (rt == 2 || rt == 3) it.linkTarget = target.replace('\\', '/');
                        else if (rt == 4 || rt == 5) it.hardLink = target;
                    }
                } catch (PasswordException pe) {
                    throw pe;
                } catch (IOException ex) {
                    if ("unsupported-version".equals(ex.getMessage())) { e.ver = 99; } // listed, cannot be read
                    // a damaged record is ignored: the header itself passed its CRC
                }
                x.p = recEnd;
            }
            if (it.dir) { it.size = 0; }
            ents.add(e);
            info.items.add(it);
        }

        private boolean needState(int i) {
            for (int j = i + 1; j < ents.size(); j++) {
                if (ents.get(j).decode()) return ents.get(j).solidBit;
            }
            return false;
        }

        @Override void walk(Visitor v) throws IOException {
            for (int i = 0; i < ents.size(); i++) {
                R5Entry e = ents.get(i);
                boolean cont;
                if (!e.hasData()) {
                    cont = v.entry(e.item, EMPTY);
                } else {
                    R5In s = new R5In(this, i, false);
                    cont = v.entry(e.item, s);
                    s.finish(needState(i));
                }
                if (!cont) break;
            }
        }

        @Override InputStream open(int index) throws IOException {
            R5Entry t = ents.get(index);
            if (!t.hasData()) { close(); return EMPTY; }
            if (t.decode() && t.solidBit) {
                int c = index;
                for (int k = index - 1; k >= 0; k--) {
                    if (!ents.get(k).decode()) continue;
                    c = k;
                    if (!ents.get(k).solidBit) break;
                }
                for (int k = c; k < index; k++) {
                    if (ents.get(k).decode()) {
                        R5In s = new R5In(this, k, false);
                        drain(s);
                    }
                }
            }
            return new R5In(this, index, true);
        }

        Rar5Decoder decoder() {
            if (dec == null) dec = new Rar5Decoder();
            return dec;
        }

        @Override void close() {
            closeQuietly(raf);
            dec = null;
        }
    }

    /** Bytes of a file area, one range. */
    private static final class PackedIn extends InputStream {
        final RandomAccessFile raf;
        long pos, left;
        PackedIn(RandomAccessFile raf, long pos, long len) { this.raf = raf; this.pos = pos; this.left = len; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 0xff; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (left <= 0) return -1;
            int want = (int) Math.min(len, left);
            raf.seek(pos);
            int n = raf.read(b, off, want);
            if (n <= 0) throw new IOException("truncated");
            pos += n;
            left -= n;
            return n;
        }
    }

    private static byte[] aesBlocks(byte[] key, byte[] iv, byte[] data) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("unsupported-crypto: " + e.getMessage());
        }
    }

    /** AES-256-CBC decryption of a stream whose length is a multiple of 16. */
    private static final class AesIn extends InputStream {
        final InputStream in;
        final Cipher cipher;
        final byte[] raw = new byte[65536];
        byte[] plain = new byte[0];
        int pos, lim;
        boolean eof;
        AesIn(InputStream in, byte[] key, byte[] iv) throws IOException {
            this.in = in;
            try {
                cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            } catch (GeneralSecurityException e) {
                throw new IOException("unsupported-crypto: " + e.getMessage());
            }
        }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 0xff; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= lim) {
                if (eof) return -1;
                int got = 0;
                while (got < raw.length) {
                    int n = in.read(raw, got, raw.length - got);
                    if (n < 0) { eof = true; break; }
                    got += n;
                }
                if ((got & 15) != 0) throw bad();
                if (got == 0) return -1;
                plain = cipher.update(raw, 0, got);
                pos = 0;
                lim = plain == null ? 0 : plain.length;
            }
            int n = Math.min(len, lim - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    /** The first {@code limit} bytes of a stream (a stored file inside AES padding). */
    private static final class Stored extends InputStream {
        final InputStream in;
        long left;
        Stored(InputStream in, long limit) { this.in = in; this.left = limit; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 0xff; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("truncated");
            left -= n;
            return n;
        }
    }

    private static final class R5In extends InputStream {
        final Rar5 owner;
        final R5Entry e;
        final boolean closeOwner;
        InputStream src;
        long left;
        CRC32 crc;
        Blake2sp b2;
        byte[] macKey;
        boolean started, eof, checked, pwVerified, closed;

        R5In(Rar5 owner, int idx, boolean closeOwner) {
            this.owner = owner;
            this.e = owner.ents.get(idx);
            this.closeOwner = closeOwner;
        }

        private void start() throws IOException {
            started = true;
            if (e.split) throw new IOException("multi-volume");
            InputStream packed = new PackedIn(owner.raf, e.dataPos, e.packSize);
            if (e.enc != null) {
                if (owner.pwBytes == null) throw new PasswordException(false);
                Keys k = owner.keys(e.enc);
                int cv = Rar5.checkValue(e.enc.check, k);
                if (cv == 0) throw new PasswordException(true);
                pwVerified = cv == 1;
                if (e.packSize % 16 != 0) throw bad();
                packed = new AesIn(packed, k.key, e.enc.iv);
                if (e.enc.mac) macKey = k.hashKey;
            }
            left = e.unpSize;
            if (e.ver > 1) throw new IOException("unsupported-version");
            if (e.method == 0) {
                long n = e.unpSize < 0 ? (e.enc == null ? e.packSize : -1) : e.unpSize;
                if (n < 0) throw bad();
                if (e.enc == null && n > e.packSize) throw new IOException("truncated");
                left = n;
                src = new Stored(packed, n);
            } else {
                if (e.dict > MAX_DICT) throw new IOException("dictionary-too-large");
                Rar5Decoder d = owner.decoder();
                d.begin(packed, e.unpSize, e.solidBit, e.dict);
                src = d;
            }
            if (e.hasCrc) crc = new CRC32();
            if (e.blake != null) b2 = new Blake2sp();
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (closed) throw new IOException("closed");
            try {
                if (!started) start();
                if (eof) return -1;
                if (left == 0) { verify(); eof = true; return -1; }
                int want = left < 0 ? len : (int) Math.min(len, left);
                int n = src.read(b, off, want);
                if (n < 0) {
                    if (left > 0) throw new IOException("truncated");
                    verify();
                    eof = true;
                    return -1;
                }
                if (crc != null) crc.update(b, off, n);
                if (b2 != null) b2.update(b, off, n);
                if (left > 0) {
                    left -= n;
                    if (left == 0) { verify(); eof = true; }
                }
                return n;
            } catch (PasswordException pe) {
                throw pe;
            } catch (IOException ex) {
                if (e.enc != null && !pwVerified && owner.pwBytes != null && !"multi-volume".equals(ex.getMessage())) throw new PasswordException(true);
                throw ex;
            } catch (OutOfMemoryError oom) {
                throw new IOException("out-of-memory");
            }
        }

        private void verify() throws IOException {
            if (checked) return;
            checked = true;
            if (crc != null) {
                long got = crc.getValue();
                if (macKey != null) got = macCrc(macKey, got);
                if (got != e.crc) throw new IOException("checksum");
            }
            if (b2 != null) {
                byte[] d = b2.digest();
                if (macKey != null) d = hmac(macKey, d);
                if (!Arrays.equals(d, e.blake)) throw new IOException("checksum");
            }
        }

        void finish(boolean needState) throws IOException {
            try {
                if (eof || !needState) return;
                // a later entry continues from the state this one leaves (solid): decode and check what is left
                byte[] sink = new byte[65536];
                while (read(sink, 0, sink.length) >= 0) { /* drain */ }
            } finally {
                closed = true;
            }
        }

        @Override public void close() throws IOException {
            closed = true;
            if (closeOwner) owner.close();
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("unsupported-crypto: " + e.getMessage());
        }
    }

    /** With encryption the stored CRC32 is replaced by a keyed digest of it. */
    static long macCrc(byte[] hashKey, long crc) throws IOException {
        byte[] raw = new byte[4];
        put32(raw, 0, (int) crc);
        byte[] d = hmac(hashKey, raw);
        int v = 0;
        for (int i = 0; i < d.length; i++) v ^= (d[i] & 0xff) << ((i & 3) * 8);
        return v & 0xFFFFFFFFL;
    }

    // ---------------------------------------------------------------------------------------------------------------- BLAKE2sp

    /** BLAKE2sp (8 leaves of BLAKE2s, then a root), the optional RAR5 file hash. Package-private for the test. */
    static final class Blake2sp {
        private static final int[] IV = {0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};
        private static final byte[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

        private static final class S {
            final int[] h = new int[8];
            long t;
            final byte[] buf = new byte[64];
            int buflen;
            final boolean lastNode;
            final int[] m = new int[16], v = new int[16];

            S(int nodeOffset, int nodeDepth, boolean lastNode) {
                System.arraycopy(IV, 0, h, 0, 8);
                h[0] ^= 0x02080020;                    // digest 32, key 0, fanout 8, depth 2
                h[2] ^= nodeOffset;
                h[3] ^= (nodeDepth << 16) | (32 << 24);   // node depth, inner length 32
                this.lastNode = lastNode;
            }

            void update(byte[] in, int off, int len) {
                int fill = 64 - buflen;
                if (len > fill) {
                    System.arraycopy(in, off, buf, buflen, fill);
                    t += 64;
                    compress(buf, 0, 0, 0);
                    buflen = 0;
                    off += fill;
                    len -= fill;
                    while (len > 64) {
                        t += 64;
                        compress(in, off, 0, 0);
                        off += 64;
                        len -= 64;
                    }
                }
                System.arraycopy(in, off, buf, buflen, len);
                buflen += len;
            }

            void finish(byte[] out, int outOff) {
                t += buflen;
                Arrays.fill(buf, buflen, 64, (byte) 0);
                compress(buf, 0, -1, lastNode ? -1 : 0);
                for (int i = 0; i < 8; i++) put32(out, outOff + i * 4, h[i]);
            }

            private static int rotr(int x, int n) { return (x >>> n) | (x << (32 - n)); }

            void compress(byte[] b, int off, int f0, int f1) {
                for (int i = 0; i < 16; i++) m[i] = (int) le32(b, off + i * 4);
                for (int i = 0; i < 8; i++) v[i] = h[i];
                v[8] = IV[0]; v[9] = IV[1]; v[10] = IV[2]; v[11] = IV[3];
                v[12] = IV[4] ^ (int) t;
                v[13] = IV[5] ^ (int) (t >>> 32);
                v[14] = IV[6] ^ f0;
                v[15] = IV[7] ^ f1;
                for (int r = 0; r < 10; r++) {
                    byte[] s = SIGMA[r];
                    g(0, 4, 8, 12, m[s[0]], m[s[1]]);
                    g(1, 5, 9, 13, m[s[2]], m[s[3]]);
                    g(2, 6, 10, 14, m[s[4]], m[s[5]]);
                    g(3, 7, 11, 15, m[s[6]], m[s[7]]);
                    g(0, 5, 10, 15, m[s[8]], m[s[9]]);
                    g(1, 6, 11, 12, m[s[10]], m[s[11]]);
                    g(2, 7, 8, 13, m[s[12]], m[s[13]]);
                    g(3, 4, 9, 14, m[s[14]], m[s[15]]);
                }
                for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
            }

            private void g(int a, int b, int c, int d, int x, int y) {
                v[a] = v[a] + v[b] + x;
                v[d] = rotr(v[d] ^ v[a], 16);
                v[c] = v[c] + v[d];
                v[b] = rotr(v[b] ^ v[c], 12);
                v[a] = v[a] + v[b] + y;
                v[d] = rotr(v[d] ^ v[a], 8);
                v[c] = v[c] + v[d];
                v[b] = rotr(v[b] ^ v[c], 7);
            }
        }

        private final S[] leaf = new S[8];
        private final byte[] stripe = new byte[512];
        private int fill;

        Blake2sp() {
            for (int i = 0; i < 8; i++) leaf[i] = new S(i, 0, i == 7);
        }

        void update(byte[] in, int off, int len) {
            while (len > 0) {
                int n = Math.min(len, 512 - fill);
                System.arraycopy(in, off, stripe, fill, n);
                fill += n;
                off += n;
                len -= n;
                if (fill == 512) {
                    for (int i = 0; i < 8; i++) leaf[i].update(stripe, i * 64, 64);
                    fill = 0;
                }
            }
        }

        byte[] digest() {
            for (int i = 0; i < 8; i++) {
                int left = fill - i * 64;
                if (left > 0) leaf[i].update(stripe, i * 64, Math.min(left, 64));
            }
            fill = 0;
            S root = new S(0, 1, true);
            byte[] d = new byte[32];
            for (int i = 0; i < 8; i++) {
                leaf[i].finish(d, 0);
                root.update(d, 0, 32);
            }
            byte[] out = new byte[32];
            root.finish(out, 0);
            return out;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- RAR5 LZ decoder

    /** Reads bits (most significant first) from a stream, with a zero tail so a few bytes can be peeked past the end. */
    private static final class BitIn {
        final InputStream src;
        final byte[] buf = new byte[65536 + 40];
        int pos, lim, bit;
        long base;
        boolean eof;

        BitIn(InputStream src) { this.src = src; }

        void need() throws IOException {
            if (lim - pos >= 24 || eof) return;
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, lim - pos);
                base += pos;
                lim -= pos;
                pos = 0;
            }
            int spins = 0;
            while (lim < 65536) {
                int n = src.read(buf, lim, 65536 - lim);
                if (n < 0) { eof = true; break; }
                if (n == 0 && ++spins > 100) throw new IOException("truncated");
                lim += n;
            }
            Arrays.fill(buf, lim, lim + 32, (byte) 0);
        }

        boolean overrun() { return pos > lim || (pos == lim && bit > 0); }

        long bitPos() { return (base + pos) * 8 + bit; }

        int peek32() {
            long v = (buf[pos] & 0xffL) << 32 | (buf[pos + 1] & 0xffL) << 24 | (buf[pos + 2] & 0xffL) << 16 | (buf[pos + 3] & 0xffL) << 8 | (buf[pos + 4] & 0xffL);
            return (int) (v >>> (8 - bit));
        }

        int peek16() { return peek32() >>> 16; }

        void skip(int n) {
            bit += n;
            pos += bit >> 3;
            bit &= 7;
        }

        void align() { if (bit > 0) { pos++; bit = 0; } }

        int u8() throws IOException {
            if (pos >= lim) throw new IOException("truncated");
            return buf[pos++] & 0xff;
        }
    }

    /** A canonical Huffman code (codes up to 15 bits, shorter codes first, symbols in order within a length). */
    private static final class Huff {
        static final int QB = 10;
        final int[] limit = new int[17];
        final int[] posOff = new int[17];
        final short[] syms;
        final int[] quick = new int[1 << QB];

        Huff(byte[] lens, int off, int n) {
            int[] cnt = new int[16];
            int nsym = 0;
            for (int i = 0; i < n; i++) { int l = lens[off + i]; if (l != 0) { cnt[l]++; nsym++; } }
            syms = new short[nsym];
            int upper = 0;
            for (int l = 1; l <= 15; l++) {
                upper += cnt[l];
                limit[l] = (int) Math.min((long) upper << (16 - l), 0x10000L);
                upper <<= 1;
            }
            posOff[1] = 0;
            for (int l = 2; l <= 15; l++) posOff[l] = posOff[l - 1] + cnt[l - 1];
            int[] tmp = posOff.clone();
            for (int i = 0; i < n; i++) { int l = lens[off + i]; if (l != 0) syms[tmp[l]++] = (short) i; }
            for (int l = 1; l <= QB; l++) {
                int start = limit[l - 1] >>> (16 - QB), end = Math.min(limit[l] >>> (16 - QB), 1 << QB);
                for (int i = start; i < end; i++) {
                    int idx = posOff[l] + (((i << (16 - QB)) - limit[l - 1]) >>> (16 - l));
                    if (idx >= 0 && idx < nsym) quick[i] = (l << 16) | syms[idx];
                }
            }
        }
    }

    private static final class Flt {
        long start;
        int len, type, channels;
    }

    /**
     * The RAR 5 / 7 unpacker. One instance serves the whole archive, so that the window, the old distances and the code tables carry over from one
     * file to the next in a solid archive. Pull style: {@link #read} decodes until a piece of output is ready.
     *
     * Verified here only against streams written by the encoder in the test (no real RAR5 archive was reachable when this was written): the bit
     * layout follows the format as documented by the RAR 5 technical note and implemented by unrar / libarchive.
     */
    private static final class Rar5Decoder extends InputStream {
        static final int NC = 306, DC = 64, LDC = 16, RC = 44, BC = 20;
        static final int MAX_FILTER_BLOCK = 0x400000;
        static final int MIN_WIN = 0x80000;
        static final int MAX_MATCH = 4200;

        byte[] win = new byte[0];
        int wp, maxWin;
        long total;
        final int[] oldDist = new int[4];
        int lastLen;
        Huff ld, dd, ldd, rd;
        boolean tablesRead, hasState, broken;

        BitIn bi;
        long blockEndBits;
        boolean lastBlock, needHeader;

        long unpSize, produced, flushed, nextFlush, chunk;
        boolean done;
        final List<Flt> filters = new ArrayList<Flt>();
        byte[] out = new byte[0];
        int outLen, outPos;

        private static int winSizeFor(long dict) throws IOException {
            if (dict > MAX_DICT) throw new IOException("dictionary-too-large");
            int w = MIN_WIN;
            while (w < dict) w <<= 1;
            return w;
        }

        void begin(InputStream packed, long unpSize, boolean solid, long dict) throws IOException {
            if (broken && solid) throw bad();
            int want = winSizeFor(dict);
            if (!solid || !hasState) {
                total = 0;
                wp = 0;
                Arrays.fill(oldDist, 0);
                lastLen = 0;
                tablesRead = false;
                maxWin = want;
                if (win.length > maxWin || win.length == 0) win = new byte[Math.min(1 << 16, maxWin)];
            } else if (want > maxWin && total <= win.length) {
                maxWin = want;
            }
            broken = false;
            hasState = true;
            bi = new BitIn(packed);
            filters.clear();
            this.unpSize = unpSize;
            produced = flushed = 0;
            done = false;
            outLen = outPos = 0;
            needHeader = true;
            chunk = Math.min(1 << 20, maxWin / 2);
            nextFlush = chunk;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (outPos >= outLen) {
                if (done) return -1;
                outPos = outLen = 0;
                try {
                    run();
                } catch (IOException e) {
                    broken = true;
                    throw e;
                } catch (RuntimeException e) {
                    broken = true;
                    throw bad();
                } catch (OutOfMemoryError e) {
                    broken = true;
                    win = new byte[0];
                    hasState = false;
                    throw new IOException("out-of-memory");
                }
            }
            int n = Math.min(len, outLen - outPos);
            System.arraycopy(out, outPos, b, off, n);
            outPos += n;
            return n;
        }

        // ---- window

        private void put(int b) {
            if (wp == win.length) {
                if (win.length < maxWin) win = Arrays.copyOf(win, Math.min(win.length * 2, maxWin));
                else wp = 0;
            }
            win[wp++] = (byte) b;
        }

        private void copyMatch(int len, long dist) throws IOException {
            if (dist < 1 || dist > total || dist > maxWin) throw bad();
            if (produced - flushed + len > maxWin) {
                flush(false);
                if (produced - flushed + len > maxWin) throw bad();
            }
            int d = (int) dist;
            int src = wp - d;
            if (src < 0) src += win.length;
            int n = len;
            if (wp + n <= win.length && src + n <= win.length && wp != win.length) {
                if (d >= n) {
                    System.arraycopy(win, src, win, wp, n);
                    wp += n;
                } else {
                    byte[] w = win;
                    int p = wp;
                    for (int i = 0; i < n; i++) w[p++] = w[src++];
                    wp = p;
                }
            } else {
                while (n-- > 0) {
                    put(win[src]);
                    if (++src == win.length) src = 0;
                }
            }
            produced += len;
            total += len;
        }

        // ---- output with filters

        private void ensureOut(int more) {
            if (outLen + more > out.length) out = Arrays.copyOf(out, Math.max(outLen + more, Math.min(out.length * 2, 1 << 26)));
        }

        /** Copy {@code n} bytes of the file at offset {@code flushed} from the window into {@code dst}. */
        private void windowCopy(byte[] dst, int dstOff, int n) {
            int pend = (int) (produced - flushed);
            int src = wp - pend;
            if (src < 0) src += win.length;
            while (n > 0) {
                int k = Math.min(n, win.length - src);
                System.arraycopy(win, src, dst, dstOff, k);
                dstOff += k;
                n -= k;
                src += k;
                if (src == win.length) src = 0;
            }
        }

        private void flush(boolean fin) throws IOException {
            if (unpSize >= 0 && produced > unpSize) throw bad();
            while (flushed < produced) {
                if (!filters.isEmpty()) {
                    Flt f = filters.get(0);
                    if (f.start < flushed) throw bad();
                    if (f.start > flushed) {
                        int n = (int) Math.min(f.start, produced) - (int) flushed;
                        emitRaw(n);
                        continue;
                    }
                    long end = f.start + f.len;
                    if (end > produced) {
                        if (fin) throw bad();
                        if (end - flushed + MAX_MATCH > maxWin) throw bad();
                        nextFlush = end;
                        return;
                    }
                    byte[] data = new byte[f.len];
                    windowCopy(data, 0, f.len);
                    byte[] res = applyFilter(f, data, flushed);
                    ensureOut(res.length);
                    System.arraycopy(res, 0, out, outLen, res.length);
                    outLen += res.length;
                    flushed = end;
                    filters.remove(0);
                } else {
                    emitRaw((int) (produced - flushed));
                }
            }
            if (fin) filters.clear();
            nextFlush = produced + chunk;
        }

        private void emitRaw(int n) {
            if (n <= 0) return;
            ensureOut(n);
            windowCopy(out, outLen, n);
            outLen += n;
            flushed += n;
        }

        static byte[] applyFilter(Flt f, byte[] data, long filePos) throws IOException {
            int len = f.len;
            switch (f.type) {
                case 0: {
                    byte[] dst = new byte[len];
                    int src = 0;
                    for (int ch = 0; ch < f.channels; ch++) {
                        byte prev = 0;
                        for (int dp = ch; dp < len; dp += f.channels) {
                            prev -= data[src++];
                            dst[dp] = prev;
                        }
                    }
                    return dst;
                }
                case 1:
                case 2: {
                    final int fileSize = 0x1000000;
                    int cmp2 = f.type == 2 ? 0xe9 : 0xe8;
                    for (int cur = 0; cur + 4 < len; ) {
                        int c = data[cur++] & 0xff;
                        if (c == 0xe8 || c == cmp2) {
                            int offset = (cur + (int) filePos) & (fileSize - 1);
                            int addr = (int) le32(data, cur);
                            if ((addr & 0x80000000) != 0) {
                                if (((addr + offset) & 0x80000000) == 0) put32(data, cur, addr + fileSize);
                            } else if (((addr - fileSize) & 0x80000000) != 0) {
                                put32(data, cur, addr - offset);
                            }
                            cur += 4;
                        }
                    }
                    return data;
                }
                case 3: {
                    for (int cur = 0; cur + 3 < len; cur += 4) {
                        if ((data[cur + 3] & 0xff) == 0xeb) {
                            int offset = (data[cur] & 0xff) | (data[cur + 1] & 0xff) << 8 | (data[cur + 2] & 0xff) << 16;
                            offset -= (int) ((filePos + cur) / 4);
                            data[cur] = (byte) offset;
                            data[cur + 1] = (byte) (offset >>> 8);
                            data[cur + 2] = (byte) (offset >>> 16);
                        }
                    }
                    return data;
                }
                default:
                    throw new IOException("unsupported-filter");
            }
        }

        // ---- the bit stream

        private int huff(Huff h) throws IOException {
            int v = bi.peek16();
            int e = h.quick[v >>> (16 - Huff.QB)];
            if (e != 0) {
                bi.skip(e >>> 16);
                return e & 0xFFFF;
            }
            for (int l = Huff.QB + 1; l <= 15; l++) {
                if (v < h.limit[l]) {
                    int idx = h.posOff[l] + ((v - h.limit[l - 1]) >>> (16 - l));
                    if (idx < 0 || idx >= h.syms.length) throw bad();
                    bi.skip(l);
                    return h.syms[idx];
                }
            }
            throw bad();
        }

        private void readBlockHeader() throws IOException {
            bi.need();
            bi.align();
            int flags = bi.u8();
            int bc = ((flags >> 3) & 3) + 1;
            if (bc == 4) throw bad();
            int saved = bi.u8();
            int size = bi.u8();
            if (bc > 1) size |= bi.u8() << 8;
            if (bc > 2) size |= bi.u8() << 16;
            if (((0x5a ^ flags ^ size ^ (size >> 8) ^ (size >> 16)) & 0xff) != saved) throw bad();
            long blockStart = bi.base + bi.pos;
            blockEndBits = (blockStart + size - 1) * 8 + (flags & 7) + 1;
            lastBlock = (flags & 0x40) != 0;
            if ((flags & 0x80) != 0) readTables();
            else if (!tablesRead) throw bad();
        }

        private void readTables() throws IOException {
            byte[] bl = new byte[BC];
            for (int i = 0; i < BC; ) {
                bi.need();
                int l = bi.peek16() >>> 12;
                bi.skip(4);
                if (l == 15) {
                    int zc = bi.peek16() >>> 12;
                    bi.skip(4);
                    if (zc == 0) bl[i++] = 15;
                    else {
                        zc += 2;
                        while (zc-- > 0 && i < BC) bl[i++] = 0;
                    }
                } else {
                    bl[i++] = (byte) l;
                }
            }
            Huff bd = new Huff(bl, 0, BC);
            byte[] tbl = new byte[NC + DC + LDC + RC];
            for (int i = 0; i < tbl.length; ) {
                bi.need();
                if (bi.overrun()) throw new IOException("truncated");
                int num = huff(bd);
                if (num < 16) {
                    tbl[i++] = (byte) num;
                } else {
                    int n;
                    if (num == 16 || num == 18) { n = (bi.peek16() >>> 13) + 3; bi.skip(3); }
                    else { n = (bi.peek16() >>> 9) + 11; bi.skip(7); }
                    if (num < 18) {
                        if (i == 0) throw bad();
                        byte p = tbl[i - 1];
                        while (n-- > 0 && i < tbl.length) tbl[i++] = p;
                    } else {
                        while (n-- > 0 && i < tbl.length) tbl[i++] = 0;
                    }
                }
            }
            if (bi.overrun()) throw new IOException("truncated");
            ld = new Huff(tbl, 0, NC);
            dd = new Huff(tbl, NC, DC);
            ldd = new Huff(tbl, NC + DC, LDC);
            rd = new Huff(tbl, NC + DC + LDC, RC);
            tablesRead = true;
        }

        private int slotToLength(int slot) {
            int lb, len = 2;
            if (slot < 8) { lb = 0; len += slot; }
            else { lb = slot / 4 - 1; len += (4 | (slot & 3)) << lb; }
            if (lb > 0) { len += bi.peek16() >>> (16 - lb); bi.skip(lb); }
            return len;
        }

        private long filterData() {
            int bc = (bi.peek16() >>> 14) + 1;
            bi.skip(2);
            long v = 0;
            for (int i = 0; i < bc; i++) { v |= (long) (bi.peek16() >>> 8) << (i * 8); bi.skip(8); }
            return v;
        }

        private void readFilter() throws IOException {
            long start = filterData();
            long len = filterData();
            int type = bi.peek16() >>> 13;
            bi.skip(3);
            int ch = 0;
            if (type == 0) { ch = (bi.peek16() >>> 11) + 1; bi.skip(5); }
            if (len == 0 || len > MAX_FILTER_BLOCK) return;
            if (type > 3) throw new IOException("unsupported-filter");
            if (filters.size() >= 8192) {
                flush(false);
                if (filters.size() >= 8192) throw bad();
            }
            Flt f = new Flt();
            f.start = produced + start;
            f.len = (int) len;
            f.type = type;
            f.channels = ch;
            filters.add(f);
        }

        private void finish() throws IOException {
            flush(true);
            done = true;
        }

        private void run() throws IOException {
            final BitIn b = bi;
            while (outLen == 0 && !done) {
                b.need();
                if (b.overrun()) throw new IOException("truncated");
                if (needHeader) {
                    needHeader = false;
                    readBlockHeader();
                    continue;
                }
                if (produced >= nextFlush) {
                    flush(false);
                    if (outLen > 0) return;
                }
                if (b.bitPos() >= blockEndBits) {
                    if (lastBlock) { finish(); return; }
                    readBlockHeader();
                    continue;
                }
                if (unpSize >= 0 && produced >= unpSize) { finish(); return; }
                int sym = huff(ld);
                if (sym < 256) {
                    put(sym);
                    produced++;
                    total++;
                    continue;
                }
                if (sym >= 262) {
                    int length = slotToLength(sym - 262);
                    int dslot = huff(dd);
                    long dist = 1;
                    int dbits;
                    if (dslot < 4) { dbits = 0; dist += dslot; }
                    else { dbits = dslot / 2 - 1; dist += (long) (2 | (dslot & 1)) << dbits; }
                    if (dbits > 0) {
                        if (dbits >= 4) {
                            if (dbits > 4) {
                                dist += (long) (b.peek32() >>> (36 - dbits)) << 4;
                                b.skip(dbits - 4);
                            }
                            dist += huff(ldd);
                        } else {
                            dist += b.peek16() >>> (16 - dbits);
                            b.skip(dbits);
                        }
                    }
                    if (dist > 0x100) {
                        length++;
                        if (dist > 0x2000) {
                            length++;
                            if (dist > 0x40000) length++;
                        }
                    }
                    if (dist > Integer.MAX_VALUE) throw bad();
                    oldDist[3] = oldDist[2];
                    oldDist[2] = oldDist[1];
                    oldDist[1] = oldDist[0];
                    oldDist[0] = (int) dist;
                    lastLen = length;
                    copyMatch(length, dist);
                    continue;
                }
                if (sym == 256) { readFilter(); continue; }
                if (sym == 257) {
                    if (lastLen != 0) copyMatch(lastLen, oldDist[0] & 0xFFFFFFFFL);
                    continue;
                }
                int di = sym - 258;
                int d = oldDist[di];
                for (int i = di; i > 0; i--) oldDist[i] = oldDist[i - 1];
                oldDist[0] = d;
                int length = slotToLength(huff(rd));
                lastLen = length;
                copyMatch(length, d & 0xFFFFFFFFL);
            }
        }
    }
}
