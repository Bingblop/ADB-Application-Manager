package com.bloatware.bingblop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.compress.MemoryLimitException;
import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.tar.TarUtils;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZOutputStream;

import io.airlift.compress.zstd.ZstdInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;

/**
 * Reads, lists, creates and rewrites 7z archives, tar archives (plain, .gz, .bz2, .xz, .zst, .lz4) and single compressed files (.gz .bz2 .xz .zst .lz4).
 * Pure Java (no Android classes): commons-compress for 7z / tar / gz / bz2 / xz / lz4, aircompressor for zstd (commons-compress's zstd needs the native zstd-jni).
 * zip and rar are only recognised by {@link #detect}, not handled here.
 *
 * <p>Formats: "zip" "7z" "rar" "tar" "tar.gz" "tar.bz2" "tar.xz" "tar.zst" "tar.lz4" "gz" "bz2" "xz" "zst" "lz4".
 *
 * <p>Names are given as they are stored (forward slashes, folders end with '/'), also when they are absolute or have ".." segments: the caller
 * decides what to extract. Nothing here writes outside the archive file that is asked for (and its ".part" file).
 *
 * <p>Limits worth knowing: a listing stops at {@link #MAX_ITEMS} items or {@link #MAX_NAME_BYTES} of names ({@link Info#truncated}; the file
 * manager refuses such an archive in words rather than show part of it); the 7z reader refuses a header or a coder that needs more than
 * 100 MiB; the writers cap the LZMA dictionary at 8 MiB (7z level 0 stores without compression); a 7z password encrypts the content and the header
 * (file names too) with AES-256, the way 7-Zip's "encrypt file names" does.
 */
public final class ArchiveIo {
    private ArchiveIo() {}

    /**
     * A listing never holds more items than this, nor more than {@link #MAX_NAME_BYTES} of names. Each listed entry is held twice (here and in the
     * file manager's entry list), about 250 bytes plus its name: 500,000 short names took 116 MB, 500,000 names of 100 characters 200 MB, and
     * with the old cap of 2,000,000 a 14 MB tar.gz of empty files ran a 256 MB heap out of memory. The zip reader has the same cap.
     */
    public static final int MAX_ITEMS = 500000;
    /** The names of a listing, in UTF-8 bytes (a long name record may be 1 MiB, and compresses to nothing when repeated). */
    public static final long MAX_NAME_BYTES = 40L << 20;
    private static final int BUF = 64 * 1024;
    private static final int MEM_LIMIT_KB = 256 * 1024;
    /**
     * What the 7z reader may need: its header (the library estimates about 208 bytes per entry, so this is about {@link #MAX_ITEMS} entries; it
     * refuses a bigger header before reading it, where a 7z of 1,000,000 empty files, 300 KB on disk, ran a 256 MB heap out of memory) and a coder.
     */
    private static final int MEM_LIMIT_7Z_KB = 100 * 1024;
    private static final int LZMA_DICT_CAP = 8 * 1024 * 1024;
    private static final int S_IFMT = 0170000, S_IFLNK = 0120000, S_IFDIR = 040000, S_IFREG = 0100000;

    // ---------------------------------------------------------------------------------------------------------------- public types

    /** One entry. */
    public static final class Item {
        /** Path as stored, forward slashes; a folder ends with '/'. */
        public String name;
        public boolean dir;
        /** Uncompressed size, -1 if unknown (a gz / bz2 file whose size is not cheap to know). 0 for folders and links. */
        public long size = -1;
        /** Packed size, -1 if unknown (known for a single compressed file only). */
        public long csize = -1;
        /** Last modified, ms since the epoch, 0 if unknown. */
        public long mtime;
        /** Unix permission bits (07777), -1 if unknown. */
        public int mode = -1;
        public boolean encrypted;
        /** Target of a symbolic link, null if the entry is not one. In a 7z listing it is "" (the target is only read by {@link #walk}). */
        public String linkTarget;
        /** Name a tar hard link points at, null if the entry is not one (it has no data of its own). */
        public String hardLink;
        public String toString() { return name + (dir ? "" : " (" + size + ")"); }
    }

    /** What a listing found. */
    public static final class Info {
        public String format;
        public List<Item> items = new ArrayList<Item>();
        /** All files are packed as one block (7z solid block, compressed tar): reading one file late in the archive means unpacking the earlier ones. */
        public boolean solid;
        /** The names themselves are encrypted (7z). */
        public boolean headerEncrypted;
        public boolean anyEncrypted;
        /** The listing stopped at {@link #MAX_ITEMS} items or {@link #MAX_NAME_BYTES} of names: it is not the whole archive. */
        public boolean truncated;
        /** Item sizes come from a 32 bit field and may be too small by a multiple of 4 GiB (a big .gz). */
        public boolean sizesApprox;
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

    public interface Progress {
        /** @return false to cancel */
        boolean tick(long bytesDone);
    }

    /** One thing to put into a new archive. */
    public static final class Source {
        /** Path inside the archive, '/'-separated, a folder ends with '/'. */
        public final String name;
        /** A file, a folder (an entry without content) or a symbolic link (stored as a link). null: an empty folder entry. */
        public final File file;
        public Source(String name, File file) { this.name = name; this.file = file; }
    }

    /** A change to make while copying an archive with {@link #rewrite}. Names are matched against the names as stored. */
    public static final class Edit {
        static final int DELETE = 0, DELETE_TREE = 1, RENAME = 2, RENAME_TREE = 3, ADD = 4, REPLACE = 5;
        final int kind;
        final String name, to;
        final File file;
        private Edit(int kind, String name, String to, File file) { this.kind = kind; this.name = name; this.to = to; this.file = file; }
        /** Leave out this entry (a folder entry too). */
        public static Edit delete(String name) { return new Edit(DELETE, name, null, null); }
        /** Leave out the folder entry and everything below it. */
        public static Edit deleteTree(String prefix) { return new Edit(DELETE_TREE, prefix, null, null); }
        public static Edit rename(String from, String to) { return new Edit(RENAME, from, to, null); }
        /** Move a folder and everything below it. */
        public static Edit renameTree(String fromPrefix, String toPrefix) { return new Edit(RENAME_TREE, fromPrefix, toPrefix, null); }
        /** Append a file (or a folder entry when {@code file} is a folder or null); an entry that has this name already is dropped. */
        public static Edit add(String name, File file) { return new Edit(ADD, name, null, file); }
        /** Put new content where the entry of this name is (appended when there is none). */
        public static Edit replace(String name, File file) { return new Edit(REPLACE, name, null, file); }
    }

    // ---------------------------------------------------------------------------------------------------------------- capabilities

    public static boolean supportsCreate(String format) { return (isTar(format) || "7z".equals(format) || isCompressor(format)) && (!"zst".equals(compOf(format)) || zstdAvailable()); }
    /** Formats {@link #rewrite} can edit: 7z and the tar family. */
    public static boolean supportsEdit(String format) { return isTar(format) || "7z".equals(format); }
    public static boolean supportsPassword(String format) { return "7z".equals(format); }
    /** Formats {@link #list}, {@link #walk} and {@link #open} handle. */
    public static boolean supportsRead(String format) { return supportsCreate(format); }

    private static Boolean zstdOk;

    /**
     * Whether zstd works on this device. aircompressor reaches into sun.misc.Unsafe and java.nio.Buffer's private "address" field when its classes
     * load; where that is refused, this is false (and the zstd formats are not offered) instead of a crash.
     */
    public static synchronized boolean zstdAvailable() {
        if (zstdOk == null) {
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                OutputStream z = new ZstdOut(new ZstdOutputStream(bo));
                byte[] probe = "zstd probe zstd probe zstd probe".getBytes(StandardCharsets.UTF_8);
                z.write(probe, 0, probe.length);
                z.close();
                InputStream in = new ZstdIn(new ByteArrayInputStream(bo.toByteArray()));
                byte[] back = new byte[probe.length + 8];
                int n = readFully(in, back, 0, back.length);
                zstdOk = n == probe.length && Arrays.equals(Arrays.copyOf(back, n), probe);
            } catch (Throwable t) { zstdOk = Boolean.FALSE; }
        }
        return zstdOk.booleanValue();
    }

    private static boolean isTar(String f) { return "tar".equals(f) || (f != null && f.startsWith("tar.") && isCompressor(f.substring(4))); }
    private static boolean isCompressor(String f) { return "gz".equals(f) || "bz2".equals(f) || "xz".equals(f) || "zst".equals(f) || "lz4".equals(f); }
    /** "tar.gz" -> "gz", "tar" -> null, "gz" -> "gz". */
    private static String compOf(String format) { return "tar".equals(format) ? null : isTar(format) ? format.substring(4) : format; }

    // ---------------------------------------------------------------------------------------------------------------- detect

    /** The format of a file by its first bytes, its name as the fallback; null when it is nothing known. */
    public static String detect(File f) {
        String ext = byExt(f.getName());
        byte[] h = new byte[512];
        int n = 0;
        try {
            InputStream in = new FileInputStream(f);
            try { n = readFully(in, h, 0, h.length); } finally { in.close(); }
        } catch (IOException e) { n = 0; }
        String c = n > 0 ? byMagic(h, n) : null;
        if (c == null) return ext;
        if (isCompressor(c)) {
            try {
                if (innerIsTar(f, c, ext)) return "tar." + c;
            } catch (IOException e) {
                if (ext != null && ext.equals("tar." + c)) return ext;
            } catch (RuntimeException e) {
                if (ext != null && ext.equals("tar." + c)) return ext;
            }
        }
        return c;
    }

    private static String byMagic(byte[] b, int n) {
        if (n >= 4 && b[0] == 'P' && b[1] == 'K' && ((b[2] == 3 && b[3] == 4) || (b[2] == 5 && b[3] == 6) || (b[2] == 7 && b[3] == 8))) return "zip";
        if (n >= 6 && (b[0] & 0xff) == 0x37 && (b[1] & 0xff) == 0x7A && (b[2] & 0xff) == 0xBC && (b[3] & 0xff) == 0xAF && b[4] == 0x27 && b[5] == 0x1C) return "7z";
        if (n >= 7 && b[0] == 'R' && b[1] == 'a' && b[2] == 'r' && b[3] == '!' && b[4] == 0x1A && b[5] == 0x07 && (b[6] == 0 || b[6] == 1)) return "rar";
        if (n >= 3 && (b[0] & 0xff) == 0x1F && (b[1] & 0xff) == 0x8B && b[2] == 8) return "gz";
        if (n >= 4 && b[0] == 'B' && b[1] == 'Z' && b[2] == 'h' && b[3] >= '1' && b[3] <= '9') return "bz2";
        if (n >= 6 && (b[0] & 0xff) == 0xFD && b[1] == 0x37 && b[2] == 0x7A && b[3] == 0x58 && b[4] == 0x5A && b[5] == 0) return "xz";
        if (n >= 4 && (b[0] & 0xff) == 0x28 && (b[1] & 0xff) == 0xB5 && b[2] == 0x2F && (b[3] & 0xff) == 0xFD) return "zst";
        if (n >= 4 && b[0] == 0x04 && b[1] == 0x22 && b[2] == 0x4D && b[3] == 0x18) return "lz4";
        if (n >= 512 && isTarHeader(b)) return "tar";
        return null;
    }

    private static String byExt(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        if (s.endsWith(".tar.gz") || s.endsWith(".tgz") || s.endsWith(".taz")) return "tar.gz";
        if (s.endsWith(".tar.bz2") || s.endsWith(".tbz2") || s.endsWith(".tbz") || s.endsWith(".tb2")) return "tar.bz2";
        if (s.endsWith(".tar.xz") || s.endsWith(".txz")) return "tar.xz";
        if (s.endsWith(".tar.zst") || s.endsWith(".tar.zstd") || s.endsWith(".tzst")) return "tar.zst";
        if (s.endsWith(".tar.lz4") || s.endsWith(".tlz4")) return "tar.lz4";
        if (s.endsWith(".tar")) return "tar";
        if (s.endsWith(".gz")) return "gz";
        if (s.endsWith(".bz2")) return "bz2";
        if (s.endsWith(".xz")) return "xz";
        if (s.endsWith(".zst") || s.endsWith(".zstd")) return "zst";
        if (s.endsWith(".lz4")) return "lz4";
        if (s.endsWith(".7z")) return "7z";
        if (s.endsWith(".rar")) return "rar";
        if (s.endsWith(".zip") || s.endsWith(".jar") || s.endsWith(".apk") || s.endsWith(".apks") || s.endsWith(".xapk") || s.endsWith(".aab") || s.endsWith(".apkm")) return "zip";
        return null;
    }

    private static boolean innerIsTar(File f, String comp, String ext) throws IOException {
        InputStream in = decompress(comp, new FileInputStream(f));
        try {
            byte[] b = new byte[512];
            if (readFully(in, b, 0, 512) < 512) return false;
            if (isTarHeader(b)) return true;
            if (ext != null && ext.startsWith("tar.")) { for (byte x : b) if (x != 0) return false; return true; }   // an empty tar
            return false;
        } finally { try { in.close(); } catch (IOException e) { /* ignore */ } }
    }

    /** A tar header block: "ustar" magic, or an old tar whose checksum field adds up. */
    private static boolean isTarHeader(byte[] b) {
        if (b.length < 512) return false;
        if (b[257] == 'u' && b[258] == 's' && b[259] == 't' && b[260] == 'a' && b[261] == 'r') return true;
        if (b[0] == 0) return false;
        long stored = parseOctal(b, 148, 8);
        if (stored < 0) return false;
        long sum = 0, ssum = 0;
        for (int i = 0; i < 512; i++) {
            boolean chk = i >= 148 && i < 156;
            sum += chk ? ' ' : (b[i] & 0xff);
            ssum += chk ? ' ' : b[i];
        }
        return stored == sum || stored == ssum;
    }

    private static long parseOctal(byte[] b, int off, int len) {
        int i = off, end = off + len;
        while (i < end && b[i] == ' ') i++;
        long v = 0; int digits = 0;
        while (i < end && b[i] >= '0' && b[i] <= '7') { v = v * 8 + (b[i] - '0'); digits++; i++; }
        if (digits == 0) return -1;
        if (i < end && b[i] != 0 && b[i] != ' ') return -1;
        return v;
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) { int r = in.read(b, off + n, len - n); if (r < 0) break; n += r; }
        return n;
    }

    // ---------------------------------------------------------------------------------------------------------------- readers

    /** A sequential reader over the entries of one archive. */
    private abstract static class Reader implements Closeable {
        Item item;
        Object raw;
        /** Moves to the next entry; false at the end. */
        abstract boolean next() throws IOException;
        /** The bytes of the current entry (only up to its end). */
        abstract InputStream data() throws IOException;
        IOException mapRead(IOException e) { return e; }
    }

    private static Reader openReader(File f, String format, char[] pw) throws IOException {
        if (format == null) format = detect(f);
        if (format == null || !supportsRead(format)) throw new IOException("unsupported-format" + (format == null ? "" : ":" + format));
        if ("7z".equals(format)) return new SevenZReader(f, pw);
        if (isTar(format)) return new TarReader(f, compOf(format));
        return new SingleReader(f, format);
    }

    /** The longest GNU long name / link name record we read into memory (a path is at most 4096 bytes on Linux). */
    static final long MAX_LONGNAME_RECORD = 1L << 20;
    /** The longest pax extended header record (path, xattrs, a sparse map ...) we read into memory; real ones are a few hundred bytes. */
    static final long MAX_PAX_RECORD = 8L << 20;

    /**
     * A tar reader that looks at every header block before the library acts on it: a GNU long name (L, K) or pax (x, g) record is read into
     * memory whole, sized by the number the header declares, so a 1.5 MB tar.gz declaring 1.5 GiB of header ran the phone out of memory.
     * Such a record over a sane cap is refused as an IOException; nothing is allocated by the declared size.
     */
    private static final class GuardedTarInputStream extends TarArchiveInputStream {
        GuardedTarInputStream(InputStream in) { super(in, "UTF-8"); }
        @Override protected byte[] readRecord() throws IOException {
            byte[] rec = super.readRecord();
            if (rec != null && rec.length >= 157) {
                byte type = rec[156];
                long cap = type == 'L' || type == 'K' ? MAX_LONGNAME_RECORD : type == 'x' || type == 'g' ? MAX_PAX_RECORD : -1;
                if (cap >= 0) {
                    long size;
                    try { size = TarUtils.parseOctalOrBinary(rec, 124, 12); } catch (IllegalArgumentException e) { size = -1; }   // not a number: the library reports it
                    if (size > cap) throw new IOException("damaged or hostile archive: header of " + size + " bytes");
                }
            }
            return rec;
        }
    }

    private static final class TarReader extends Reader {
        private final TarArchiveInputStream in;
        TarReader(File f, String comp) throws IOException {
            InputStream raw = new FileInputStream(f);
            try {
                InputStream chain = comp == null ? new BufferedInputStream(raw, BUF) : decompress(comp, raw);
                in = new GuardedTarInputStream(chain);
            } catch (IOException e) { closeQuietly(raw); throw e; } catch (RuntimeException e) { closeQuietly(raw); throw new IOException(e); }
        }
        boolean next() throws IOException {
            for (;;) {
                TarArchiveEntry e = in.getNextEntry();
                if (e == null) return false;
                if (e.isCharacterDevice() || e.isBlockDevice() || e.isFIFO() || e.isGlobalPaxHeader() || e.isPaxHeader()) continue;
                Item it = new Item();
                it.dir = e.isDirectory();
                it.name = e.getName();
                if (it.dir && !it.name.endsWith("/")) it.name += "/";
                it.mtime = e.getModTime() == null ? 0 : e.getModTime().getTime();
                it.mode = e.getMode() & 07777;
                if (e.isSymbolicLink()) { it.linkTarget = e.getLinkName(); it.size = 0; }
                else if (e.isLink()) { it.hardLink = e.getLinkName(); it.size = 0; }
                else if (it.dir) it.size = 0;
                else it.size = e.isSparse() ? e.getRealSize() : e.getSize();
                item = it; raw = e;
                return true;
            }
        }
        InputStream data() { return in; }
        public void close() throws IOException { in.close(); }
    }

    private static final class SingleReader extends Reader {
        private final File file; private final String comp;
        private boolean done; private InputStream stream;
        SingleReader(File f, String comp) { this.file = f; this.comp = comp; }
        boolean next() {
            if (done) return false;
            done = true;
            long[] approx = new long[1];
            Item it = new Item();
            it.name = singleName(file.getName(), comp);
            it.size = singleSize(file, comp, approx);
            it.csize = file.length();
            it.mtime = file.lastModified();
            item = it;
            return true;
        }
        InputStream data() throws IOException {
            if (stream == null) stream = decompress(comp, new FileInputStream(file));
            return stream;
        }
        public void close() throws IOException { if (stream != null) stream.close(); }
    }

    private static final class SevenZReader extends Reader {
        final char[] pw;
        final boolean hdrEnc;
        final SevenZFile sz;
        boolean anyEnc;
        private byte[] linkBytes;
        private InputStream cur;
        private final File file;

        SevenZReader(File f, char[] password) throws IOException {
            file = f;
            pw = password != null && password.length > 0 ? password : null;
            hdrEnc = sevenZHeaderEncrypted(f);
            if (hdrEnc && pw == null) throw new PasswordException(false);
            SevenZFile s;
            try {
                SevenZFile.Builder b = SevenZFile.builder().setFile(f).setMaxMemoryLimitKb(MEM_LIMIT_7Z_KB).setUseDefaultNameForUnnamedEntries(true);
                if (pw != null) b.setPassword(pw);
                s = b.get();
            } catch (PasswordRequiredException e) {
                PasswordException p = new PasswordException(false); p.initCause(e); throw p;
            } catch (MemoryLimitException e) {
                if (hdrEnc) { PasswordException p = new PasswordException(true); p.initCause(e); throw p; }
                IOException io = new IOException("This 7z archive is too big to open here: it has too many entries (more than " + MAX_ITEMS + " files) or a coder that needs more than "
                    + (MEM_LIMIT_7Z_KB >> 10) + " MB of memory (it needs " + (e.getMemoryNeededInKb() >> 10) + " MB)");
                io.initCause(e); throw io;
            } catch (IOException e) {
                if (hdrEnc) { PasswordException p = new PasswordException(true); p.initCause(e); throw p; }
                throw e;
            } catch (RuntimeException e) {
                if (hdrEnc) { PasswordException p = new PasswordException(true); p.initCause(e); throw p; }
                throw new IOException(e);
            }
            sz = s;
            inspect();
            for (SevenZArchiveEntry e : sz.getEntries()) if (isEncryptedEntry(e)) { anyEnc = true; break; }
        }

        private final java.util.IdentityHashMap<SevenZArchiveEntry, Boolean> encMap = new java.util.IdentityHashMap<SevenZArchiveEntry, Boolean>();
        private boolean solidFlag, inspected;

        private static Object field(Object o, String name) throws Exception {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                try { java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException e) { /* look further up */ }
            }
            throw new NoSuchFieldException(name);
        }

        /** The listing of commons-compress does not say which entries are in an AES block or whether there is more than one file per block; its Archive does. */
        private void inspect() {
            try {
                Object archive = field(sz, "archive");
                Object[] folders = (Object[]) field(archive, "folders");
                Object sm = field(archive, "streamMap");
                int[] ffi = sm == null ? null : (int[]) field(sm, "fileFolderIndex");
                boolean[] fenc = new boolean[folders.length];
                for (int i = 0; i < folders.length; i++) {
                    for (Object coder : (Object[]) field(folders[i], "coders")) {
                        byte[] id = (byte[]) field(coder, "decompressionMethodId");
                        if (id != null && id.length == 4 && id[0] == 0x06 && (id[1] & 0xff) == 0xF1 && id[2] == 0x07 && id[3] == 0x01) fenc[i] = true;
                    }
                    if (((Integer) field(folders[i], "numUnpackSubStreams")) > 1) solidFlag = true;
                }
                int idx = 0;
                for (SevenZArchiveEntry e : sz.getEntries()) {
                    boolean en = ffi != null && idx < ffi.length && ffi[idx] >= 0 && ffi[idx] < fenc.length && fenc[ffi[idx]];
                    encMap.put(e, en);
                    idx++;
                }
                inspected = true;
            } catch (Throwable t) { inspected = false; }
        }

        boolean isEncryptedEntry(SevenZArchiveEntry e) {
            Boolean b = encMap.get(e);
            return b != null ? b.booleanValue() : isEncrypted(e);
        }

        @Override IOException mapRead(IOException e) {
            if (e instanceof PasswordException) return e;
            if (e instanceof PasswordRequiredException) { PasswordException p = new PasswordException(false); p.initCause(e); return p; }
            if (anyEnc) { PasswordException p = new PasswordException(pw != null); p.initCause(e); return p; }
            return e;
        }

        boolean next() throws IOException {
            try {
                SevenZArchiveEntry e;
                do { e = sz.getNextEntry(); } while (e != null && e.isAntiItem());
                if (e == null) return false;
                Item it = toItem(e);
                linkBytes = null;
                if (it.linkTarget != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int r;
                    while ((r = sz.read(buf, 0, buf.length)) > 0 && bo.size() < 65536) bo.write(buf, 0, r);
                    linkBytes = bo.toByteArray();
                    it.linkTarget = new String(linkBytes, StandardCharsets.UTF_8);
                }
                item = it; raw = e;
                cur = null;
                return true;
            } catch (IOException e) { throw mapRead(e); }
        }

        InputStream data() {
            if (linkBytes != null) return new ByteArrayInputStream(new byte[0]);
            if (cur == null) cur = new InputStream() {
                @Override public int read() throws IOException { return sz.read(); }
                @Override public int read(byte[] b, int off, int len) throws IOException { return len == 0 ? 0 : sz.read(b, off, len); }
            };
            return cur;
        }

        byte[] linkBytes() { return linkBytes; }

        /** Item of an entry; a link gets "" as its target (reading it needs the data). */
        Item toItem(SevenZArchiveEntry e) {
            Item it = new Item();
            int type = 0;
            if (e.getHasWindowsAttributes()) {
                int a = e.getWindowsAttributes();
                if ((a & 0x8000) != 0) { int m = a >>> 16; it.mode = m & 07777; type = m & S_IFMT; }
            }
            it.dir = e.isDirectory() || type == S_IFDIR;
            String n = e.getName();
            if (n == null) n = "";
            it.name = it.dir && !n.endsWith("/") ? n + "/" : n;
            it.size = it.dir ? 0 : e.getSize();
            if (type == S_IFLNK && !it.dir) { it.linkTarget = ""; it.size = 0; }
            try { if (e.getHasLastModifiedDate()) it.mtime = e.getLastModifiedDate().getTime(); } catch (RuntimeException ex) { it.mtime = 0; }
            it.encrypted = isEncryptedEntry(e);
            return it;
        }

        boolean solid() {
            if (inspected) return solidFlag;
            int withData = 0;
            for (SevenZArchiveEntry e : sz.getEntries()) if (e.hasStream()) withData++;
            return withData > 1;
        }

        public void close() throws IOException { sz.close(); }
    }

    private static boolean isEncrypted(SevenZArchiveEntry e) {
        Iterable<? extends SevenZMethodConfiguration> ms = e.getContentMethods();
        if (ms == null) return false;
        for (SevenZMethodConfiguration m : ms) if (m != null && m.getMethod() == SevenZMethod.AES256SHA256) return true;
        return false;
    }

    /** True when the 7z header is itself packed behind AES (an "encoded header" that names the AES coder): the file names need the password. */
    private static boolean sevenZHeaderEncrypted(File f) {
        try {
            RandomAccessFile r = new RandomAccessFile(f, "r");
            try {
                long len = r.length();
                if (len < 32) return false;
                byte[] sh = new byte[32];
                r.readFully(sh);
                long off = le64(sh, 12), size = le64(sh, 20);
                if (size <= 0 || off < 0 || 32 + off + size > len) return false;
                r.seek(32 + off);
                int first = r.read();
                if (first != 0x17) return false;
                byte[] h = new byte[(int) Math.min(size - 1, 65536)];
                r.readFully(h);
                for (int i = 0; i + 3 < h.length; i++) if ((h[i] & 0xff) == 0x06 && (h[i + 1] & 0xff) == 0xF1 && h[i + 2] == 0x07 && h[i + 3] == 0x01) return true;
                return false;
            } finally { r.close(); }
        } catch (IOException e) { return false; }
    }

    private static long le64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }

    // ---------------------------------------------------------------------------------------------------------------- compression streams

    private static InputStream decompress(String comp, InputStream raw) throws IOException {
        try {
            InputStream b = new BufferedInputStream(raw, BUF);
            if ("gz".equals(comp)) return new GzipCompressorInputStream(b, true);
            if ("bz2".equals(comp)) return new BZip2CompressorInputStream(b, true);
            if ("xz".equals(comp)) return new XZCompressorInputStream(b, true, MEM_LIMIT_KB);
            if ("zst".equals(comp)) return new ZstdIn(b);
            if ("lz4".equals(comp)) return new Lz4In(b);
            throw new IOException("unsupported-format:" + comp);
        } catch (IOException e) { closeQuietly(raw); throw e; } catch (RuntimeException e) { closeQuietly(raw); throw new IOException(e); }
    }

    private static OutputStream compressor(String comp, OutputStream raw, int level, String gzName, long gzMtime) throws IOException {
        if ("gz".equals(comp)) {
            GzipParameters p = new GzipParameters();
            p.setCompressionLevel(level < 0 ? 6 : Math.min(level, 9));
            p.setOperatingSystem(3);
            if (gzName != null && latin1(gzName)) p.setFileName(gzName);
            if (gzMtime > 0) p.setModificationTime(gzMtime);
            return new GzipCompressorOutputStream(raw, p);
        }
        if ("bz2".equals(comp)) return new BZip2CompressorOutputStream(raw, level < 0 ? 9 : Math.max(1, Math.min(level, 9)));
        if ("xz".equals(comp)) return new XZOutputStream(raw, lzma2(level), XZ.CHECK_CRC64);
        if ("zst".equals(comp)) {
            try { return new ZstdOut(new ZstdOutputStream(raw)); } catch (LinkageError e) { throw new IOException("zstd-unavailable", e); } catch (RuntimeException e) { throw new IOException("zstd: " + e.getMessage(), e); }
        }
        if ("lz4".equals(comp)) return new Lz4Out(raw);
        throw new IOException("unsupported-format:" + comp);
    }

    private static boolean latin1(String s) {
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == 0 || c > 255) return false; }
        return true;
    }

    private static LZMA2Options lzma2(int level) throws IOException {
        LZMA2Options o = new LZMA2Options();
        o.setPreset(level < 0 ? 6 : Math.min(level, 9));
        if (o.getDictSize() > LZMA_DICT_CAP) o.setDictSize(LZMA_DICT_CAP);
        return o;
    }

    /** aircompressor throws unchecked exceptions on damaged input. */
    private static final class ZstdIn extends InputStream {
        private final ZstdInputStream in;
        ZstdIn(InputStream raw) throws IOException {
            try { in = new ZstdInputStream(raw); } catch (LinkageError e) { throw new IOException("zstd-unavailable", e); } catch (RuntimeException e) { throw new IOException("zstd: " + e.getMessage(), e); }
        }
        @Override public int read() throws IOException { try { return in.read(); } catch (RuntimeException e) { throw new IOException("zstd: " + e.getMessage(), e); } }
        @Override public int read(byte[] b, int off, int len) throws IOException { try { return in.read(b, off, len); } catch (RuntimeException e) { throw new IOException("zstd: " + e.getMessage(), e); } }
        @Override public int available() throws IOException { try { return in.available(); } catch (RuntimeException e) { throw new IOException("zstd: " + e.getMessage(), e); } }
        @Override public void close() throws IOException { in.close(); }
    }

    private static final class ZstdOut extends OutputStream {
        private final ZstdOutputStream out;
        ZstdOut(ZstdOutputStream o) { out = o; }
        @Override public void write(int b) throws IOException { try { out.write(b); } catch (RuntimeException e) { throw new IOException("zstd: " + e.getMessage(), e); } }
        @Override public void write(byte[] b, int off, int len) throws IOException { try { out.write(b, off, len); } catch (RuntimeException e) { throw new IOException("zstd: " + e.getMessage(), e); } }
        @Override public void flush() throws IOException { /* the encoder only emits whole blocks */ }
        @Override public void close() throws IOException { try { out.close(); } catch (RuntimeException e) { throw new IOException("zstd: " + e.getMessage(), e); } }
    }

    // ---------------------------------------------------------------------------------------------------------------- LZ4 frames
    // commons-compress's framed LZ4 streams need commons-codec (XXHash32), which is not in libs/. The frame format is small, so it is done here;
    // only commons-compress's block compressor is used for the data.

    private static final int XP1 = 0x9E3779B1, XP2 = 0x85EBCA77, XP3 = 0xC2B2AE3D, XP4 = 0x27D4EB2F, XP5 = 0x165667B1;

    /** xxHash32 with seed 0, streaming. */
    private static final class Xxh32 {
        private int v1, v2, v3, v4;
        private long total;
        private final byte[] mem = new byte[16];
        private int memSize;
        Xxh32() { reset(); }
        void reset() { v1 = XP1 + XP2; v2 = XP2; v3 = 0; v4 = -XP1; total = 0; memSize = 0; }
        private static int le32(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24; }
        private static int round(int acc, int in) { return Integer.rotateLeft(acc + in * XP2, 13) * XP1; }
        private void stripe(byte[] b, int o) { v1 = round(v1, le32(b, o)); v2 = round(v2, le32(b, o + 4)); v3 = round(v3, le32(b, o + 8)); v4 = round(v4, le32(b, o + 12)); }
        void update(byte[] b, int off, int len) {
            total += len;
            int end = off + len;
            if (memSize + len < 16) { System.arraycopy(b, off, mem, memSize, len); memSize += len; return; }
            if (memSize > 0) {
                int fill = 16 - memSize;
                System.arraycopy(b, off, mem, memSize, fill);
                stripe(mem, 0);
                off += fill; memSize = 0;
            }
            while (off + 16 <= end) { stripe(b, off); off += 16; }
            if (off < end) { System.arraycopy(b, off, mem, 0, end - off); memSize = end - off; }
        }
        int digest() {
            int h = total >= 16 ? Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7) + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18) : XP5;
            h += (int) total;
            int o = 0;
            while (o + 4 <= memSize) { h = Integer.rotateLeft(h + le32(mem, o) * XP3, 17) * XP4; o += 4; }
            while (o < memSize) { h = Integer.rotateLeft(h + (mem[o] & 0xff) * XP5, 11) * XP1; o++; }
            h ^= h >>> 15; h *= XP2; h ^= h >>> 13; h *= XP3; h ^= h >>> 16;
            return h;
        }
        static int of(byte[] b, int off, int len) { Xxh32 x = new Xxh32(); x.update(b, off, len); return x.digest(); }
    }

    /** Reads LZ4 frames (the .lz4 file format), one after the other; skippable frames are skipped; blocks may depend on the ones before. */
    private static final class Lz4In extends InputStream {
        private final InputStream in;
        private byte[] win;            // history (up to 64 KiB) followed by the current block
        private int maxBlock, dictLen, pos, lim;
        private boolean indep, blockSum, contentSum, inFrame, eof;
        private final Xxh32 content = new Xxh32();
        private byte[] cbuf = new byte[0];
        Lz4In(InputStream in) throws IOException {
            this.in = in;
            if (!nextFrame()) throw new IOException("lz4: no frame");
        }
        private int u8() throws IOException { int b = in.read(); if (b < 0) throw new java.io.EOFException("lz4: truncated"); return b; }
        private int le32() throws IOException { return u8() | u8() << 8 | u8() << 16 | u8() << 24; }
        private void readFully(byte[] b, int n) throws IOException {
            int k = 0;
            while (k < n) { int r = in.read(b, k, n - k); if (r < 0) throw new java.io.EOFException("lz4: truncated"); k += r; }
        }
        /** Moves to the start of the next frame; false at a clean end of the input. */
        private boolean nextFrame() throws IOException {
            for (;;) {
                int b0 = in.read();
                if (b0 < 0) return false;
                int magic = b0 | u8() << 8 | u8() << 16 | u8() << 24;
                if ((magic & 0xFFFFFFF0) == 0x184D2A50) {          // skippable frame
                    long n = le32() & 0xFFFFFFFFL;
                    while (n > 0) { long k = in.skip(n); if (k <= 0) { if (in.read() < 0) throw new java.io.EOFException("lz4: truncated"); k = 1; } n -= k; }
                    continue;
                }
                if (magic == 0x184C2102) throw new IOException("lz4: legacy frame format is not supported");
                if (magic != 0x184D2204) throw new IOException("lz4: not an lz4 frame");
                int flg = u8(), bd = u8();
                if ((flg >> 6) != 1 || (flg & 0x02) != 0 || (bd & 0x8F) != 0) throw new IOException("lz4: unsupported frame flags");
                indep = (flg & 0x20) != 0; blockSum = (flg & 0x10) != 0; contentSum = (flg & 0x04) != 0;
                ByteArrayOutputStream desc = new ByteArrayOutputStream();
                desc.write(flg); desc.write(bd);
                if ((flg & 0x08) != 0) for (int i = 0; i < 8; i++) desc.write(u8());
                if ((flg & 0x01) != 0) throw new IOException("lz4: dictionary ids are not supported");
                int hc = u8();
                byte[] d = desc.toByteArray();
                if (hc != ((Xxh32.of(d, 0, d.length) >> 8) & 0xff)) throw new IOException("lz4: bad frame header");
                int id = (bd >> 4) & 7;
                if (id < 4) throw new IOException("lz4: bad block size");
                maxBlock = 1 << (8 + 2 * id);           // 64 KiB, 256 KiB, 1 MiB, 4 MiB
                if (win == null || win.length < 65536 + maxBlock) win = new byte[65536 + maxBlock];
                dictLen = 0; pos = lim = 0; inFrame = true; content.reset();
                return true;
            }
        }
        private boolean nextBlock() throws IOException {
            while (!eof) {
                if (!inFrame) { if (!nextFrame()) { eof = true; return false; } }
                int size = le32();
                if (size == 0) {
                    if (contentSum && le32() != content.digest()) throw new IOException("lz4: content checksum mismatch");
                    inFrame = false;
                    continue;
                }
                boolean raw = (size & 0x80000000) != 0;
                size &= 0x7FFFFFFF;
                if (size > maxBlock + (maxBlock >> 8) + 64 || (raw && size > maxBlock)) throw new IOException("lz4: block too big");
                if (cbuf.length < size) cbuf = new byte[Math.max(size, cbuf.length * 2)];
                readFully(cbuf, size);
                if (blockSum && le32() != Xxh32.of(cbuf, 0, size)) throw new IOException("lz4: block checksum mismatch");
                if (indep) dictLen = 0;
                else if (lim > 0) {
                    int keep = Math.min(65536, dictLen + lim);
                    System.arraycopy(win, dictLen + lim - keep, win, 0, keep);
                    dictLen = keep;
                } else dictLen = Math.min(dictLen, 65536);
                int n;
                if (raw) { System.arraycopy(cbuf, 0, win, dictLen, size); n = size; }
                else n = decodeBlock(cbuf, size, win, dictLen, maxBlock);
                if (contentSum) content.update(win, dictLen, n);
                pos = dictLen; lim = n;
                if (n == 0) continue;
                lim = n;
                return true;
            }
            return false;
        }
        /** One LZ4 block into dst at dstOff (earlier bytes of dst are the history); returns the bytes written. */
        private static int decodeBlock(byte[] src, int srcLen, byte[] dst, int dstOff, int maxOut) throws IOException {
            int ip = 0, op = dstOff, limit = dstOff + maxOut;
            try {
                while (ip < srcLen) {
                    int token = src[ip++] & 0xff;
                    int lit = token >> 4;
                    if (lit == 15) { int s; do { s = src[ip++] & 0xff; lit += s; } while (s == 255 && lit >= 0); }
                    if (lit < 0 || lit > srcLen - ip || lit > limit - op) throw new IOException("lz4: damaged block");
                    System.arraycopy(src, ip, dst, op, lit);
                    ip += lit; op += lit;
                    if (ip >= srcLen) break;
                    int off = (src[ip] & 0xff) | (src[ip + 1] & 0xff) << 8;
                    ip += 2;
                    if (off == 0 || off > op) throw new IOException("lz4: damaged block");
                    int ml = token & 15;
                    if (ml == 15) { int s; do { s = src[ip++] & 0xff; ml += s; } while (s == 255 && ml >= 0); }
                    ml += 4;
                    if (ml < 0 || ml > limit - op) throw new IOException("lz4: damaged block");
                    for (int i = 0; i < ml; i++, op++) dst[op] = dst[op - off];
                }
            } catch (ArrayIndexOutOfBoundsException e) { throw new IOException("lz4: damaged block"); }
            return op - dstOff;
        }
        @Override public int read() throws IOException { byte[] one = new byte[1]; int r = read(one, 0, 1); return r < 0 ? -1 : one[0] & 0xff; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= dictLen + lim || lim == 0) { if (eof || !nextBlock()) return -1; }
            int n = Math.min(len, dictLen + lim - pos);
            System.arraycopy(win, pos, b, off, n);
            pos += n;
            return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }

    /** Writes one LZ4 frame: independent 1 MiB blocks, a content checksum, no size in the header. */
    private static final class Lz4Out extends OutputStream {
        private static final int BLOCK = 1 << 20;
        private final OutputStream out;
        private final byte[] blk = new byte[BLOCK];
        private int fill;
        private final Xxh32 content = new Xxh32();
        private boolean closed;
        Lz4Out(OutputStream out) throws IOException {
            this.out = out;
            byte[] h = { 0x04, 0x22, 0x4D, 0x18, 0x64, 0x60, 0 };      // magic; FLG: version 1, independent blocks, content checksum; BD: 1 MiB blocks
            h[6] = (byte) (Xxh32.of(h, 4, 2) >> 8);
            out.write(h);
        }
        @Override public void write(int b) throws IOException { byte[] one = { (byte) b }; write(one, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, BLOCK - fill);
                System.arraycopy(b, off, blk, fill, n);
                fill += n; off += n; len -= n;
                if (fill == BLOCK) flushBlock();
            }
        }
        private void flushBlock() throws IOException {
            if (fill == 0) return;
            content.update(blk, 0, fill);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(fill / 2 + 64);
            BlockLZ4CompressorOutputStream bc = new BlockLZ4CompressorOutputStream(bo);
            bc.write(blk, 0, fill);
            bc.close();
            byte[] c = bo.toByteArray();
            boolean raw = c.length >= fill;
            int size = raw ? fill : c.length;
            byte[] s = { (byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) ((size >> 24) | (raw ? 0x80 : 0)) };
            out.write(s);
            if (raw) out.write(blk, 0, fill); else out.write(c);
            fill = 0;
        }
        @Override public void flush() throws IOException { /* blocks are written whole */ }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                flushBlock();
                int d = content.digest();
                out.write(new byte[] { 0, 0, 0, 0, (byte) d, (byte) (d >> 8), (byte) (d >> 16), (byte) (d >> 24) });
            } finally { out.close(); }
        }
    }

    private static String singleName(String fileName, String comp) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String[] sufs = "zst".equals(comp) ? new String[] { ".zst", ".zstd" } : new String[] { "." + comp };
        for (String s : sufs) if (lower.endsWith(s) && fileName.length() > s.length()) return fileName.substring(0, fileName.length() - s.length());
        return fileName.length() > 0 && !lower.equals("." + comp) ? fileName : "data";
    }

    /** The unpacked size of a single compressed file when it can be read from the file's own headers or trailer; -1 otherwise. */
    private static long singleSize(File f, String comp, long[] approx) {
        try {
            RandomAccessFile r = new RandomAccessFile(f, "r");
            try {
                long len = r.length();
                if ("gz".equals(comp)) {
                    if (len < 18) return -1;
                    byte[] t = new byte[4];
                    r.seek(len - 4); r.readFully(t);
                    long isize = (t[0] & 0xffL) | (t[1] & 0xffL) << 8 | (t[2] & 0xffL) << 16 | (t[3] & 0xffL) << 24;
                    if (len * 1032L >= (1L << 32)) approx[0] = 1;
                    return isize;
                }
                if ("xz".equals(comp)) return xzSize(r, len);
                if ("zst".equals(comp)) {
                    byte[] h = new byte[18];
                    int n = r.read(h);
                    if (n < 6) return -1;
                    int fhd = h[4] & 0xff, fcsFlag = fhd >> 6, single = (fhd >> 5) & 1, dictFlag = fhd & 3;
                    int pos = 5 + (single == 0 ? 1 : 0) + new int[] { 0, 1, 2, 4 }[dictFlag];
                    int fcsSize = fcsFlag == 0 ? (single == 1 ? 1 : 0) : new int[] { 2, 4, 8 }[fcsFlag - 1];
                    if (fcsSize == 0 || pos + fcsSize > n) return -1;
                    long v = 0;
                    for (int i = fcsSize - 1; i >= 0; i--) v = (v << 8) | (h[pos + i] & 0xffL);
                    if (fcsSize == 2) v += 256;
                    return v;
                }
                if ("lz4".equals(comp)) {
                    byte[] h = new byte[15];
                    int n = r.read(h);
                    if (n < 15 || (h[4] & 0x08) == 0) return -1;
                    return le64(h, 6);
                }
                return -1;
            } finally { r.close(); }
        } catch (IOException e) { return -1; } catch (RuntimeException e) { return -1; }
    }

    /** Size from the index at the end of a single-stream .xz file; -1 when it is not a plain single stream. */
    private static long xzSize(RandomAccessFile r, long len) throws IOException {
        byte[] m = new byte[6];
        r.seek(0); r.readFully(m);
        if (byMagic(m, 6) == null || !"xz".equals(byMagic(m, 6))) return -1;
        long end = len;
        byte[] b4 = new byte[4];
        while (end >= 4) { r.seek(end - 4); r.readFully(b4); if (b4[0] == 0 && b4[1] == 0 && b4[2] == 0 && b4[3] == 0) end -= 4; else break; }
        if (end < 32) return -1;
        byte[] ft = new byte[12];
        r.seek(end - 12); r.readFully(ft);
        if (ft[10] != 'Y' || ft[11] != 'Z') return -1;
        long backward = (ft[4] & 0xffL) | (ft[5] & 0xffL) << 8 | (ft[6] & 0xffL) << 16 | (ft[7] & 0xffL) << 24;
        long indexSize = (backward + 1) * 4;
        long indexStart = end - 12 - indexSize;
        if (indexStart < 12 || indexSize > (1 << 24)) return -1;
        byte[] ix = new byte[(int) indexSize];
        r.seek(indexStart); r.readFully(ix);
        int[] pos = { 0 };
        if (ix[pos[0]++] != 0) return -1;
        long records = vli(ix, pos);
        long blocks = 0, total = 0;
        for (long i = 0; i < records; i++) {
            long unpadded = vli(ix, pos), unc = vli(ix, pos);
            blocks += (unpadded + 3) & ~3L;
            total += unc;
        }
        if (12 + blocks + indexSize + 12 != end) return -1;
        return total;
    }

    private static long vli(byte[] b, int[] pos) throws IOException {
        long v = 0;
        for (int i = 0; i < 9; i++) {
            if (pos[0] >= b.length) throw new IOException("bad xz index");
            int x = b[pos[0]++] & 0xff;
            v |= (long) (x & 0x7f) << (7 * i);
            if ((x & 0x80) == 0) return v;
        }
        throw new IOException("bad xz index");
    }

    // ---------------------------------------------------------------------------------------------------------------- list / walk / open

    /** Lists an archive: 7z, the tar family, or a single compressed file (one item). */
    public static Info list(File f, char[] password) throws IOException { return list(f, null, password); }

    public static Info list(File f, String format, char[] password) throws IOException {
        if (format == null) format = detect(f);
        Reader r = openReader(f, format, password);
        try {
            Info info = new Info();
            info.format = format;
            if (r instanceof SevenZReader) {
                SevenZReader z = (SevenZReader) r;
                info.headerEncrypted = z.hdrEnc;
                info.anyEncrypted = z.hdrEnc || z.anyEnc;
                long names = 0;
                for (SevenZArchiveEntry e : z.sz.getEntries()) {
                    if (e.isAntiItem()) continue;
                    Item it = z.toItem(e);
                    if (info.items.size() >= MAX_ITEMS || (names += utf8Len(it.name)) > MAX_NAME_BYTES) { info.truncated = true; break; }
                    info.items.add(it);
                }
                info.solid = z.solid();
            } else {
                long names = 0;
                while (r.next()) {
                    if (info.items.size() >= MAX_ITEMS || (names += utf8Len(r.item.name)) > MAX_NAME_BYTES) { info.truncated = true; break; }
                    info.items.add(r.item);
                }
                info.solid = isTar(format) && !"tar".equals(format);
                if (r instanceof SingleReader) {
                    long[] approx = new long[1];
                    singleSize(f, format, approx);
                    info.sizesApprox = approx[0] != 0;
                }
            }
            return info;
        } finally { closeQuietly(r); }
    }

    private static long utf8Len(String s) {
        if (s == null) return 0;
        long n = 0;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); n += c < 0x80 ? 1 : c < 0x800 ? 2 : 3; }     // a surrogate pair counts 3 + 3 for its 4 bytes: over-counting is safe for a budget
        return n;
    }

    /** One pass over the entries in archive order; the data stream is valid during the call only. */
    public static void walk(File f, String format, char[] password, Visitor v) throws IOException {
        Reader r = openReader(f, format, password);
        try {
            while (r.next()) {
                EntryStream es = new EntryStream(r);
                boolean more;
                try { more = v.entry(r.item, es); } finally { es.dead = true; }
                if (!more) break;
            }
        } finally { closeQuietly(r); }
    }

    private static final class EntryStream extends InputStream {
        private final Reader r;
        private InputStream in;
        boolean dead;
        EntryStream(Reader r) { this.r = r; }
        private InputStream in() throws IOException {
            if (dead) throw new IOException("stream-closed");
            if (in == null) { try { in = r.data(); } catch (IOException e) { throw r.mapRead(e); } }
            return in;
        }
        @Override public int read() throws IOException { InputStream s = in(); try { return s.read(); } catch (IOException e) { throw r.mapRead(e); } }
        @Override public int read(byte[] b, int off, int len) throws IOException { InputStream s = in(); try { return s.read(b, off, len); } catch (IOException e) { throw r.mapRead(e); } }
        @Override public long skip(long n) throws IOException { InputStream s = in(); try { return s.skip(n); } catch (IOException e) { throw r.mapRead(e); } }
        @Override public int available() throws IOException { return dead ? 0 : in().available(); }
        @Override public void close() { /* the archive's own stream stays open */ }
    }

    /**
     * The bytes of one entry (a file, or a link's target text in 7z). 7z uses random access; tar rescans from the start, the first entry of that
     * name wins. Closing the stream closes the archive.
     */
    public static InputStream open(File f, String format, String itemName, char[] password) throws IOException {
        if (format == null) format = detect(f);
        final Reader r = openReader(f, format, password);
        boolean ok = false;
        try {
            InputStream in;
            if (r instanceof SevenZReader) {
                SevenZReader z = (SevenZReader) r;
                SevenZArchiveEntry hit = null;
                for (SevenZArchiveEntry e : z.sz.getEntries()) {
                    if (e.isAntiItem() || e.getName() == null) continue;
                    if (stripSlash(e.getName()).equals(stripSlash(itemName))) { hit = e; break; }
                }
                if (hit == null) throw new FileNotFoundException(itemName);
                if (z.toItem(hit).dir) throw new IOException("is-directory:" + itemName);
                try { in = z.sz.getInputStream(hit); } catch (IOException e) { throw z.mapRead(e); }
            } else if (r instanceof SingleReader) {
                r.next();
                in = r.data();
            } else {
                boolean found = false;
                while (r.next()) if (!r.item.dir && r.item.name.equals(itemName)) { found = true; break; }
                if (!found) throw new FileNotFoundException(itemName);
                in = r.data();
            }
            final InputStream src = in;
            ok = true;
            return new InputStream() {
                @Override public int read() throws IOException { try { return src.read(); } catch (IOException e) { throw r.mapRead(e); } }
                @Override public int read(byte[] b, int off, int len) throws IOException { try { return src.read(b, off, len); } catch (IOException e) { throw r.mapRead(e); } }
                @Override public long skip(long n) throws IOException { try { return src.skip(n); } catch (IOException e) { throw r.mapRead(e); } }
                @Override public int available() throws IOException { return src.available(); }
                @Override public void close() throws IOException { r.close(); }
            };
        } finally { if (!ok) closeQuietly(r); }
    }

    // ---------------------------------------------------------------------------------------------------------------- writing

    /** Counts bytes and asks the caller whether to go on. */
    private static final class Meter {
        final Progress cb;
        long done;
        Meter(Progress cb) { this.cb = cb; }
        void add(long n) throws IOException { done += n; poll(); }
        void poll() throws IOException { if (cb != null && !cb.tick(done)) throw new IOException("Cancelled"); }
    }

    /** Writes entries into a new archive file. */
    private interface EntryWriter {
        void dir(String name, long mtime, int mode) throws IOException;
        void symlink(String name, String target, long mtime, int mode) throws IOException;
        void file(String name, long mtime, int mode, long size, InputStream in, Meter m) throws IOException;
        /** Copies the entry the reader is at, under another name. */
        void copy(Reader r, String newName, Meter m) throws IOException;
        void finish() throws IOException;
        void abort();
    }

    private static void copyExact(InputStream in, OutputStream out, long size, Meter m, String name) throws IOException {
        byte[] buf = new byte[BUF];
        long left = size;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new IOException("file-changed:" + name);
            out.write(buf, 0, r);
            left -= r;
            m.add(r);
        }
        m.poll();
    }

    private static void copyAll(InputStream in, OutputStream out, Meter m) throws IOException {
        byte[] buf = new byte[BUF];
        int r;
        while ((r = in.read(buf)) >= 0) { out.write(buf, 0, r); m.add(r); }
        m.poll();
    }

    /** A file output that is flushed to disk before it is closed; abort() just closes. */
    private static final class SyncOut extends OutputStream {
        private final FileOutputStream fo;
        private final BufferedOutputStream out;
        SyncOut(File f) throws IOException { fo = new FileOutputStream(f); out = new BufferedOutputStream(fo, BUF); }
        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException {
            try { out.flush(); try { fo.getFD().sync(); } catch (IOException e) { /* best effort */ } } finally { fo.close(); }
        }
        void abort() { try { fo.close(); } catch (IOException e) { /* ignore */ } }
    }

    private static final class TarWriter implements EntryWriter {
        final SyncOut raw;
        final TarArchiveOutputStream tos;
        TarWriter(File part, String comp, int level) throws IOException {
            raw = new SyncOut(part);
            try {
                OutputStream chain = comp == null ? raw : compressor(comp, raw, level, null, 0);
                tos = new TarArchiveOutputStream(chain, "UTF-8");
                tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                tos.setAddPaxHeadersForNonAsciiNames(true);
            } catch (IOException e) { raw.abort(); throw e; } catch (RuntimeException e) { raw.abort(); throw new IOException(e); }
        }
        private static TarArchiveEntry entry(String name, byte type) {
            TarArchiveEntry e = type == 0 ? new TarArchiveEntry(name, true) : new TarArchiveEntry(name, type, true);
            e.setUserName(""); e.setGroupName(""); e.setUserId(0); e.setGroupId(0);
            return e;
        }
        public void dir(String name, long mtime, int mode) throws IOException {
            TarArchiveEntry e = entry(dirName(name), (byte) 0);
            e.setModTime(mtime); e.setMode(mode & 07777);
            tos.putArchiveEntry(e); tos.closeArchiveEntry();
        }
        public void symlink(String name, String target, long mtime, int mode) throws IOException {
            TarArchiveEntry e = entry(fileName(name), TarConstants.LF_SYMLINK);
            e.setLinkName(target); e.setModTime(mtime); e.setMode(mode & 07777);
            tos.putArchiveEntry(e); tos.closeArchiveEntry();
        }
        public void file(String name, long mtime, int mode, long size, InputStream in, Meter m) throws IOException {
            TarArchiveEntry e = entry(fileName(name), (byte) 0);
            e.setModTime(mtime); e.setMode(mode & 07777); e.setSize(size);
            tos.putArchiveEntry(e);
            copyExact(in, tos, size, m, name);
            tos.closeArchiveEntry();
        }
        public void copy(Reader r, String newName, Meter m) throws IOException {
            TarArchiveEntry o = (TarArchiveEntry) r.raw;
            Item it = r.item;
            TarArchiveEntry e;
            if (it.linkTarget != null) { e = entry(fileName(newName), TarConstants.LF_SYMLINK); e.setLinkName(it.linkTarget); }
            else if (it.hardLink != null) { e = entry(fileName(newName), TarConstants.LF_LINK); e.setLinkName(it.hardLink); }
            else if (it.dir) e = entry(dirName(newName), (byte) 0);
            else { e = entry(fileName(newName), (byte) 0); e.setSize(it.size); }
            e.setModTime(o.getModTime() == null ? new Date(0) : o.getModTime());
            e.setMode(o.getMode() & 07777);
            e.setUserId(o.getLongUserId()); e.setGroupId(o.getLongGroupId());
            e.setUserName(o.getUserName() == null ? "" : o.getUserName()); e.setGroupName(o.getGroupName() == null ? "" : o.getGroupName());
            tos.putArchiveEntry(e);
            if (!it.dir && it.linkTarget == null && it.hardLink == null) copyExact(r.data(), tos, it.size, m, it.name);
            tos.closeArchiveEntry();
            m.poll();
        }
        public void finish() throws IOException { tos.close(); }
        public void abort() { raw.abort(); }
    }

    /** A byte counter in front of another stream; close() only flushes, the owner closes the file. */
    private static final class CountOut extends OutputStream {
        private final OutputStream out;
        long count;
        CountOut(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.flush(); }
    }

    /** AES-256-CBC as 7-Zip does it: zero padded to whole blocks at the end (the unpack size says where the data stops). */
    private static final class AesCbcOut extends OutputStream {
        private final OutputStream out;
        private final Cipher cipher;
        private final byte[] pend = new byte[16];
        private int pendLen;
        AesCbcOut(OutputStream out, byte[] key, byte[] iv) throws IOException {
            this.out = out;
            try {
                cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            } catch (java.security.GeneralSecurityException e) { throw new IOException("aes-unavailable", e); }
        }
        @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (pendLen == 0 && len >= 16) {
                    int whole = len & ~15;
                    byte[] enc = cipher.update(b, off, whole);
                    if (enc != null) out.write(enc);
                    off += whole; len -= whole;
                } else {
                    int n = Math.min(len, 16 - pendLen);
                    System.arraycopy(b, off, pend, pendLen, n);
                    pendLen += n; off += n; len -= n;
                    if (pendLen == 16) { byte[] enc = cipher.update(pend, 0, 16); if (enc != null) out.write(enc); pendLen = 0; }
                }
            }
        }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException {
            if (pendLen > 0) {
                Arrays.fill(pend, pendLen, 16, (byte) 0);
                byte[] enc = cipher.update(pend, 0, 16);
                if (enc != null) out.write(enc);
                pendLen = 0;
            }
            out.flush();
        }
    }

    /**
     * Writes a 7z archive the way 7-Zip does by default: all files in one solid LZMA2 block (level 0: stored), a CRC for every file, unix mode and
     * modification time per entry, and with a password the block is AES-256 encrypted and the header (the names) too. (commons-compress's own writer
     * makes one block per file, gives all of them one IV and leaves the names readable.)
     */
    private static final class SevenZWriter implements EntryWriter {
        private static final class E {
            String name; boolean dir, hasStream; long size; long crc; long mtime; int attrs; boolean hasAttrs;
        }
        final File part;
        final FileChannel ch;
        final char[] pw;
        final LZMA2Options opts;          // null: stored
        final List<E> entries = new ArrayList<E>();
        private final CountOut pack;
        private CountOut mid;
        private OutputStream cmp, enc;
        private final byte[] salt = new byte[16], iv = new byte[16], hdrIv = new byte[16];
        private byte[] key;
        private long unpackTotal;
        private final CRC32 crc = new CRC32();
        private final OutputStream sink;

        SevenZWriter(File part, char[] password, int level) throws IOException {
            this.part = part;
            pw = password != null && password.length > 0 ? password : null;
            opts = level == 0 ? null : lzma2(level);
            ch = FileChannel.open(part.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                ch.position(32);
                pack = new CountOut(new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(ch), BUF));
                if (pw != null) {
                    SecureRandom rnd = new SecureRandom();
                    rnd.nextBytes(salt); rnd.nextBytes(iv); rnd.nextBytes(hdrIv);
                    key = aesKey(pw, salt, AES_CYCLES);
                }
            } catch (IOException e) { try { ch.close(); } catch (IOException x) { /* ignore */ } throw e; }
            sink = new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws IOException { cmp.write(b, off, len); crc.update(b, off, len); unpackTotal += len; }
            };
        }

        private static int attrs(int type, int mode, boolean dir) { return 0x8000 | ((type | (mode & 07777)) << 16) | (dir ? 0x10 : 0x20); }

        private E entry(String name, boolean dir, long size, long mtime, int attrs, boolean hasAttrs) {
            E e = new E();
            e.name = name; e.dir = dir; e.size = size; e.mtime = mtime; e.attrs = attrs; e.hasAttrs = hasAttrs; e.hasStream = !dir && size > 0;
            entries.add(e);
            return e;
        }

        /** Starts the one block when the first file with content comes. */
        private void begin() throws IOException {
            if (cmp != null) return;
            enc = pw != null ? new AesCbcOut(pack, key, iv) : pack;
            mid = new CountOut(enc);
            cmp = opts == null ? mid : opts.getOutputStream(new org.tukaani.xz.FinishableWrapperOutputStream(mid));
        }

        private void content(E e, InputStream in, Meter m) throws IOException {
            begin();
            crc.reset();
            copyExact(in, sink, e.size, m, e.name);
            e.crc = crc.getValue();
        }
        private void content(E e, byte[] data) throws IOException {
            begin();
            crc.reset();
            sink.write(data, 0, data.length);
            e.crc = crc.getValue();
        }

        public void dir(String name, long mtime, int mode) throws IOException { entry(fileName(name), true, 0, mtime, attrs(S_IFDIR, mode, true), true); }
        public void symlink(String name, String target, long mtime, int mode) throws IOException {
            byte[] t = target.getBytes(StandardCharsets.UTF_8);
            E e = entry(fileName(name), false, t.length, mtime, attrs(S_IFLNK, mode, false), true);
            if (e.hasStream) content(e, t);
        }
        public void file(String name, long mtime, int mode, long size, InputStream in, Meter m) throws IOException {
            E e = entry(fileName(name), false, size, mtime, attrs(S_IFREG, mode, false), true);
            if (e.hasStream) content(e, in, m);
        }
        public void copy(Reader r, String newName, Meter m) throws IOException {
            SevenZArchiveEntry o = (SevenZArchiveEntry) r.raw;
            Item it = r.item;
            long mt = 0;
            try { if (o.getHasLastModifiedDate()) mt = o.getLastModifiedDate().getTime(); } catch (RuntimeException ex) { mt = 0; }
            byte[] link = ((SevenZReader) r).linkBytes();
            E e = entry(fileName(newName), it.dir, it.dir ? 0 : link != null ? link.length : it.size, mt, o.getHasWindowsAttributes() ? o.getWindowsAttributes() : 0, o.getHasWindowsAttributes());
            if (e.hasStream) { if (link != null) content(e, link); else content(e, r.data(), m); }
            m.poll();
        }

        private static final int AES_CYCLES = 19;

        public void finish() throws IOException {
            long packSize = 0, midSize = 0;
            if (cmp != null) { cmp.close(); enc.close(); pack.flush(); packSize = pack.count; midSize = mid.count; }
            else pack.flush();
            ByteArrayOutputStream h = new ByteArrayOutputStream();
            h.write(0x01);                                                    // kHeader
            int streams = 0;
            for (E e : entries) if (e.hasStream) streams++;
            if (streams > 0) {
                h.write(0x04);                                                // kMainStreamsInfo
                h.write(0x06); writeNumber(h, 0); writeNumber(h, 1);          //   kPackInfo: at pack position 0, one stream
                h.write(0x09); writeNumber(h, packSize); h.write(0x00);       //     kSize, kEnd
                h.write(0x07);                                                //   kUnpackInfo
                h.write(0x0B); writeNumber(h, 1); h.write(0x00);              //     kFolder: one folder, not external
                if (pw != null) {
                    writeNumber(h, 2);                                        //       two coders, in the order they run when unpacking: AES, then the compressor
                    byte[] aesProps = new byte[34];
                    aesProps[0] = (byte) (0xC0 | AES_CYCLES); aesProps[1] = (byte) 0xFF;
                    System.arraycopy(salt, 0, aesProps, 2, 16); System.arraycopy(iv, 0, aesProps, 18, 16);
                    h.write(0x24); h.write(new byte[] { 0x06, (byte) 0xF1, 0x07, 0x01 }); writeNumber(h, 34); h.write(aesProps);
                    writeCompressCoder(h);
                    writeNumber(h, 1); writeNumber(h, 0);                      //       bind pair: input of coder 1 is the output of coder 0
                    h.write(0x0C); writeNumber(h, midSize); writeNumber(h, unpackTotal);   // one size per coder output; the last is the real data
                } else {
                    writeNumber(h, 1);
                    writeCompressCoder(h);
                    h.write(0x0C); writeNumber(h, unpackTotal);
                }
                h.write(0x00);                                                //     end of UnpackInfo
                h.write(0x08);                                                //   kSubStreamsInfo
                h.write(0x0D); writeNumber(h, streams);                       //     number of files in the folder
                if (streams > 1) {
                    h.write(0x09);                                            //     kSize: all but the last
                    int seen = 0;
                    for (E e : entries) if (e.hasStream && ++seen < streams) writeNumber(h, e.size);
                }
                h.write(0x0A); h.write(1);                                    //     kCRC, all defined
                for (E e : entries) if (e.hasStream) writeLe32(h, e.crc);
                h.write(0x00);                                                //     end of SubStreamsInfo
                h.write(0x00);                                                //   end of MainStreamsInfo
            }
            h.write(0x05); writeNumber(h, entries.size());                   // kFilesInfo
            boolean anyEmpty = false, anyEmptyFile = false;
            for (E e : entries) if (!e.hasStream) { anyEmpty = true; if (!e.dir) anyEmptyFile = true; }
            if (anyEmpty) {
                boolean[] es = new boolean[entries.size()];
                for (int i = 0; i < es.length; i++) es[i] = !entries.get(i).hasStream;
                byte[] v = bits(es);
                h.write(0x0E); writeNumber(h, v.length); h.write(v);          //   kEmptyStream
                if (anyEmptyFile) {
                    int cnt = 0;
                    for (E e : entries) if (!e.hasStream) cnt++;
                    boolean[] ef = new boolean[cnt];
                    int k = 0;
                    for (E e : entries) if (!e.hasStream) ef[k++] = !e.dir;
                    byte[] v2 = bits(ef);
                    h.write(0x0F); writeNumber(h, v2.length); h.write(v2);     //   kEmptyFile
                }
            }
            ByteArrayOutputStream names = new ByteArrayOutputStream();
            names.write(0);                                                   // not external
            for (E e : entries) { names.write(e.name.getBytes(StandardCharsets.UTF_16LE)); names.write(0); names.write(0); }
            h.write(0x11); writeNumber(h, names.size()); h.write(names.toByteArray());   // kNames
            boolean allTimes = true, anyTime = false;
            for (E e : entries) { if (e.mtime > 0) anyTime = true; else allTimes = false; }
            if (anyTime) {
                ByteArrayOutputStream t = new ByteArrayOutputStream();
                if (allTimes) t.write(1);
                else { t.write(0); boolean[] d = new boolean[entries.size()]; for (int i = 0; i < d.length; i++) d[i] = entries.get(i).mtime > 0; t.write(bits(d)); }
                t.write(0);                                                   // not external
                for (E e : entries) if (e.mtime > 0) putFileTime(t, e.mtime);
                h.write(0x14); writeNumber(h, t.size()); h.write(t.toByteArray());   // kMTime
            }
            boolean allAttr = true, anyAttr = false;
            for (E e : entries) { if (e.hasAttrs) anyAttr = true; else allAttr = false; }
            if (anyAttr) {
                ByteArrayOutputStream t = new ByteArrayOutputStream();
                if (allAttr) t.write(1);
                else { t.write(0); boolean[] d = new boolean[entries.size()]; for (int i = 0; i < d.length; i++) d[i] = entries.get(i).hasAttrs; t.write(bits(d)); }
                t.write(0);
                for (E e : entries) if (e.hasAttrs) writeLe32(t, e.attrs & 0xFFFFFFFFL);
                h.write(0x15); writeNumber(h, t.size()); h.write(t.toByteArray());   // kWinAttributes
            }
            h.write(0x00);                                                    // end of FilesInfo
            h.write(0x00);                                                    // end of Header
            byte[] hdr = h.toByteArray();

            long off = packSize;
            byte[] tail;                                                      // what follows the packed streams: [encrypted header] then the header record
            long hdrOff = off;
            if (pw != null) {
                byte[][] enc = encodeHeader(hdr, off, key, salt, hdrIv);
                ByteArrayOutputStream both = new ByteArrayOutputStream();
                both.write(enc[0]); both.write(enc[1]);
                tail = both.toByteArray();
                hdrOff = off + enc[0].length;
                hdr = enc[1];
            } else tail = hdr;
            ch.position(32 + off);
            ch.write(java.nio.ByteBuffer.wrap(tail));
            CRC32 hc = new CRC32();
            hc.update(hdr, 0, hdr.length);
            byte[] start = new byte[32];
            System.arraycopy(new byte[] { 0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0, 4 }, 0, start, 0, 8);
            putLe64(start, 12, hdrOff); putLe64(start, 20, hdr.length);
            long hv = hc.getValue();
            for (int i = 0; i < 4; i++) start[28 + i] = (byte) (hv >>> (8 * i));
            CRC32 sc = new CRC32();
            sc.update(start, 12, 20);
            long sv = sc.getValue();
            for (int i = 0; i < 4; i++) start[8 + i] = (byte) (sv >>> (8 * i));
            ch.position(0);
            ch.write(java.nio.ByteBuffer.wrap(start));
            ch.force(true);
            ch.close();
            if (key != null) Arrays.fill(key, (byte) 0);
        }

        private void writeCompressCoder(ByteArrayOutputStream h) throws IOException {
            if (opts == null) { h.write(0x01); h.write(0x00); return; }                  // COPY
            int dict = opts.getDictSize();
            int lead = Integer.numberOfLeadingZeros(dict);
            int second = (dict >>> (30 - lead)) - 2;
            int prop = (19 - lead) * 2 + second;
            h.write(0x21); h.write(0x21); writeNumber(h, 1); h.write(prop);               // LZMA2, one property byte: the dictionary size
        }

        public void abort() { try { ch.close(); } catch (IOException e) { /* ignore */ } }
    }

    private static byte[] bits(boolean[] b) {
        byte[] r = new byte[(b.length + 7) / 8];
        for (int i = 0; i < b.length; i++) if (b[i]) r[i >> 3] |= (byte) (0x80 >> (i & 7));
        return r;
    }

    private static void putFileTime(ByteArrayOutputStream o, long ms) {
        long ft = (ms + 11644473600000L) * 10000L;
        for (int i = 0; i < 8; i++) o.write((int) (ft >>> (8 * i)) & 0xff);
    }

    private static String fileName(String n) { int e = n.length(); while (e > 1 && n.charAt(e - 1) == '/') e--; return n.substring(0, e); }
    private static String dirName(String n) { return fileName(n) + "/"; }
    private static String stripSlash(String n) { int e = n.length(); while (e > 0 && n.charAt(e - 1) == '/') e--; return n.substring(0, e); }

    private static EntryWriter newWriter(File part, String format, char[] pw, int level) throws IOException {
        if ("7z".equals(format)) return new SevenZWriter(part, pw, level);
        return new TarWriter(part, compOf(format), level);
    }

    private static void writeSource(EntryWriter w, String name, File file, Meter m) throws IOException {
        m.poll();
        if (name == null || stripSlash(name).length() == 0) throw new IOException("bad-name");
        if (file == null) { w.dir(name, System.currentTimeMillis(), 0755); return; }
        Path p = file.toPath();
        if (Files.isSymbolicLink(p)) {
            long mt = 0;
            try { mt = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis(); } catch (IOException e) { mt = file.lastModified(); }
            w.symlink(name, Files.readSymbolicLink(p).toString(), mt, 0777);
            return;
        }
        if (file.isDirectory()) { w.dir(name, file.lastModified(), modeOf(p, file, true)); return; }
        if (!file.isFile()) throw new FileNotFoundException(file.getPath());
        InputStream in = new FileInputStream(file);
        try { w.file(name, file.lastModified(), modeOf(p, file, false), file.length(), in, m); } finally { in.close(); }
    }

    /** Unix permission bits of a file; a guess (rw-r--r-- / rwxr-xr-x) where the file system does not say. */
    static int modeOf(Path p, File f, boolean dir) {
        try {
            Object o = Files.getAttribute(p, "unix:mode");
            if (o instanceof Integer) return ((Integer) o) & 07777;
        } catch (Throwable t) { /* try the next way */ }
        try {
            Set<PosixFilePermission> s = Files.getPosixFilePermissions(p);
            int m = 0;
            for (PosixFilePermission x : s) {
                switch (x) {
                    case OWNER_READ: m |= 0400; break; case OWNER_WRITE: m |= 0200; break; case OWNER_EXECUTE: m |= 0100; break;
                    case GROUP_READ: m |= 040; break; case GROUP_WRITE: m |= 020; break; case GROUP_EXECUTE: m |= 010; break;
                    case OTHERS_READ: m |= 04; break; case OTHERS_WRITE: m |= 02; break; default: m |= 01; break;
                }
            }
            return m;
        } catch (Throwable t) { /* guess */ }
        return dir || f.canExecute() ? 0755 : 0644;
    }

    private static void movePart(File part, File dest) throws IOException {
        try {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes a new archive. The file is written as {@code dest + ".part"} and renamed at the end, so a failure or a cancel never leaves a broken
     * archive (and never touches an existing {@code dest}). Cancel: {@code cb.tick} returned false, throws {@code IOException("Cancelled")}.
     *
     * @param level 0..9 (-1: default 6); 7z level 0 stores without compression; zstd and lz4 have one level
     * @param password 7z only: AES-256 for the content and the header; any other format throws {@code IOException("password-unsupported")}
     */
    public static void create(File dest, String format, List<Source> sources, char[] password, int level, Progress cb) throws IOException {
        if (!supportsCreate(format)) throw new IOException("unsupported-format" + (format == null ? "" : ":" + format));
        boolean pw = password != null && password.length > 0;
        if (pw && !supportsPassword(format)) throw new IOException("password-unsupported");
        boolean single = isCompressor(format);
        if (single && (sources.size() != 1 || sources.get(0).file == null || !sources.get(0).file.isFile())) throw new IOException("single-file-format-needs-one-file");
        File parent = dest.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        File part = new File(dest.getPath() + ".part");
        Meter m = new Meter(cb);
        EntryWriter w = null;
        SyncOut sout = null;
        boolean ok = false;
        try {
            m.poll();
            if (single) {
                File src = sources.get(0).file;
                sout = new SyncOut(part);
                OutputStream c = compressor(format, sout, level, src.getName(), src.lastModified());
                InputStream in = new FileInputStream(src);
                try { copyAll(in, c, m); } finally { in.close(); }
                c.close();
                sout = null;
            } else {
                w = newWriter(part, format, password, level);
                for (Source s : sources) writeSource(w, s.name, s.file, m);
                EntryWriter done = w;
                w = null;
                done.finish();
            }
            movePart(part, dest);
            ok = true;
        } finally {
            if (!ok) {
                if (w != null) w.abort();
                if (sout != null) sout.abort();
                part.delete();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- rewrite

    /** What to do with one entry of the source archive. */
    private static final class Plan {
        static final int KEEP = 0, DROP = 1, RENAME = 2, REPLACE = 3;
        final List<Edit> edits;
        final boolean[] used;
        int act; String newName; File file;
        Plan(List<Edit> edits) { this.edits = edits; used = new boolean[edits.size()]; }
        boolean tracksNames() {
            for (Edit e : edits) if (e.kind >= Edit.RENAME) return true;
            return false;
        }
        void decide(String name, boolean dir) {
            String key = stripSlash(name);
            act = KEEP; newName = name; file = null;
            for (int i = 0; i < edits.size(); i++) {
                Edit e = edits.get(i);
                if (e.kind == Edit.DELETE || e.kind == Edit.ADD) { if (key.equals(stripSlash(e.name))) { act = DROP; return; } }
                else if (e.kind == Edit.DELETE_TREE) { String p = stripSlash(e.name); if (key.equals(p) || key.startsWith(p + "/")) { act = DROP; return; } }
            }
            for (int i = 0; i < edits.size(); i++) {
                Edit e = edits.get(i);
                if (e.kind == Edit.REPLACE && !dir && key.equals(stripSlash(e.name))) { act = REPLACE; file = e.file; used[i] = true; return; }
            }
            for (int i = 0; i < edits.size(); i++) {
                Edit e = edits.get(i);
                if (e.kind == Edit.RENAME && key.equals(stripSlash(e.name))) { act = RENAME; newName = dir ? dirName(e.to) : fileName(e.to); return; }
                if (e.kind == Edit.RENAME_TREE) {
                    String p = stripSlash(e.name), t = stripSlash(e.to);
                    if (key.equals(p) || key.startsWith(p + "/")) { act = RENAME; String n = t + key.substring(p.length()); newName = dir ? n + "/" : n; return; }
                }
            }
        }
    }

    /**
     * Copies a 7z or tar archive into a new one of the same format with the edits applied, in one pass. Entries keep their order, mode and times;
     * folders are implicit (deleting a file keeps its folder entry, an added file adds none). A 7z archive keeps the password it was opened with.
     * Renames and adds are checked against each other: two entries ending up with one name is an {@code IOException("duplicate-name:...")}.
     * Written as {@code dst + ".part"} and renamed; {@code dst} may be {@code src}.
     */
    public static void rewrite(File src, File dst, char[] password, List<Edit> edits, Progress cb) throws IOException {
        String format = detect(src);
        if (!supportsEdit(format)) throw new IOException("unsupported-format" + (format == null ? "" : ":" + format));
        File part = new File(dst.getPath() + ".part");
        File parent = dst.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        Meter m = new Meter(cb);
        Plan plan = new Plan(edits);
        Map<String, Boolean> names = plan.tracksNames() ? new HashMap<String, Boolean>() : null;   // name -> made by an edit
        Reader r = null;
        EntryWriter w = null;
        boolean ok = false;
        try {
            m.poll();
            r = openReader(src, format, password);
            w = newWriter(part, format, "7z".equals(format) ? password : null, -1);
            while (r.next()) {
                m.poll();
                Item it = r.item;
                plan.decide(it.name, it.dir);
                if (plan.act == Plan.DROP) continue;
                if (plan.act == Plan.REPLACE) {
                    claim(names, it.name, true);
                    writeSource(w, it.name, plan.file, m);
                } else {
                    claim(names, plan.newName, plan.act == Plan.RENAME);
                    w.copy(r, plan.newName, m);
                }
            }
            for (Edit e : edits) if (e.kind == Edit.ADD) { claim(names, e.name, true); writeSource(w, e.name, e.file, m); }
            for (int i = 0; i < edits.size(); i++) {
                Edit e = edits.get(i);
                if (e.kind == Edit.REPLACE && !plan.used[i]) { claim(names, e.name, true); writeSource(w, e.name, e.file, m); }
            }
            r.close();
            r = null;
            EntryWriter done = w;
            w = null;
            done.finish();
            movePart(part, dst);
            ok = true;
        } finally {
            if (r != null) closeQuietly(r);
            if (!ok) {
                if (w != null) w.abort();
                part.delete();
            }
        }
    }

    private static void claim(Map<String, Boolean> names, String name, boolean edited) throws IOException {
        if (names == null) return;
        String key = stripSlash(name);
        Boolean prev = names.get(key);
        if (prev != null && (edited || prev)) throw new IOException("duplicate-name:" + name);
        if (prev == null) names.put(key, edited);
    }

    // ---------------------------------------------------------------------------------------------------------------- 7z header encryption

    /**
     * Packs a plain 7z header the way 7-Zip does for "encrypt file names": LZMA, then AES-256 (the same key and salt as the content, an IV of its
     * own). Returns { the encrypted bytes, the "encoded header" that describes them }; the encrypted bytes go at pack position {@code packPos}.
     */
    private static byte[][] encodeHeader(byte[] hdr, long packPos, byte[] key, byte[] salt, byte[] iv) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(hdr, 0, hdr.length);
        LZMA2Options lo = new LZMA2Options();
        lo.setPreset(5);
        lo.setDictSize(Math.max(LZMA2Options.DICT_SIZE_MIN, Math.min(LZMA_DICT_CAP, Integer.highestOneBit(Math.max(hdr.length, 1) * 2 - 1))));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        LZMAOutputStream lz = new LZMAOutputStream(bo, lo, hdr.length);
        lz.write(hdr);
        lz.close();
        byte[] all = bo.toByteArray();                               // 13 byte .lzma header, then the stream
        byte[] lzProps = Arrays.copyOfRange(all, 0, 5);
        byte[] packed = Arrays.copyOfRange(all, 13, all.length);
        ByteArrayOutputStream eo = new ByteArrayOutputStream();
        AesCbcOut ao = new AesCbcOut(eo, key, iv);
        ao.write(packed, 0, packed.length);
        ao.close();
        byte[] enc = eo.toByteArray();

        byte[] aesProps = new byte[34];
        aesProps[0] = (byte) (0xC0 | 19);
        aesProps[1] = (byte) 0xFF;
        System.arraycopy(salt, 0, aesProps, 2, 16);
        System.arraycopy(iv, 0, aesProps, 18, 16);

        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.write(0x17);                                   // kEncodedHeader: a StreamsInfo that says where the real header is
        h.write(0x06); writeNumber(h, packPos); writeNumber(h, 1);        // kPackInfo
        h.write(0x09); writeNumber(h, enc.length); h.write(0x00);         //   kSize, kEnd
        h.write(0x07);                                                    // kUnpackInfo
        h.write(0x0B); writeNumber(h, 1); h.write(0x00);                   //   kFolder: one folder, not external
        writeNumber(h, 2);                                                //     two coders, in the order they run when unpacking: AES, then LZMA
        h.write(0x24); h.write(new byte[] { 0x06, (byte) 0xF1, 0x07, 0x01 }); writeNumber(h, aesProps.length); h.write(aesProps);
        h.write(0x23); h.write(new byte[] { 0x03, 0x01, 0x01 }); writeNumber(h, 5); h.write(lzProps);
        writeNumber(h, 1); writeNumber(h, 0);                              //     bind pair: the input of coder 1 (LZMA) comes from the output of coder 0 (AES)
        h.write(0x0C); writeNumber(h, packed.length); writeNumber(h, hdr.length);   // kCodersUnpackSize, one per coder output; the last is the real header
        h.write(0x0A); h.write(1); writeLe32(h, crc.getValue());          //   kCRC of the plain header
        h.write(0x00);                                                    // end of UnpackInfo
        h.write(0x00);                                                    // end of StreamsInfo
        return new byte[][] { enc, h.toByteArray() };
    }

    /** 7-Zip's key: SHA-256 over (salt, password as UTF-16LE, 8 byte counter) for 2^cycles rounds. */
    private static byte[] aesKey(char[] pw, byte[] salt, int cycles) throws IOException {
        try {
            java.nio.ByteBuffer pb = StandardCharsets.UTF_16LE.encode(CharBuffer.wrap(pw));
            byte[] p = new byte[pb.remaining()];
            pb.get(p);
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] ctr = new byte[8];
            long rounds = 1L << cycles;
            for (long i = 0; i < rounds; i++) {
                d.update(salt); d.update(p); d.update(ctr);
                for (int j = 0; j < 8; j++) if (++ctr[j] != 0) break;
            }
            Arrays.fill(p, (byte) 0);
            return d.digest();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("sha256-unavailable", e); }
    }

    private static void writeNumber(ByteArrayOutputStream o, long v) {
        int first = 0, mask = 0x80, i;
        for (i = 0; i < 8; i++) {
            if (v < (1L << (7 * (i + 1)))) { first |= (int) (v >>> (8 * i)); break; }
            first |= mask;
            mask >>= 1;
        }
        o.write(first);
        for (int j = 0; j < i; j++) o.write((int) (v >>> (8 * j)) & 0xff);
    }

    private static void writeLe32(ByteArrayOutputStream o, long v) { for (int i = 0; i < 4; i++) o.write((int) (v >>> (8 * i)) & 0xff); }
    private static void putLe64(byte[] b, int o, long v) { for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (8 * i)); }

    private static void closeQuietly(Closeable c) { try { if (c != null) c.close(); } catch (IOException e) { /* ignore */ } }
}
