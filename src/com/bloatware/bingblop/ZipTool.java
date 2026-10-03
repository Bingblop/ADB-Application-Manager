package com.bloatware.bingblop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Reads and rewrites ZIP-family archives (zip, apk, apks, apkm, xapk, jar, aar, ...) in place, without
 * extracting them: lists the central directory (ZIP64 aware) as a folder tree, streams single entries,
 * extracts safely, and rewrites the archive with entries deleted / renamed / replaced / added. Unchanged
 * entries are copied as raw compressed bytes, so editing a large archive neither recompresses nor loads it
 * into memory, and APK-style 4-byte / 4 KB alignment of stored entries is kept.
 *
 * <p>Pure Java, no Android classes: unit-tested off-device against archives made by other tools.
 */
public final class ZipTool {

    private ZipTool() {}

    private static final int SIG_LOCAL = 0x04034b50;
    private static final int SIG_CENTRAL = 0x02014b50;
    private static final int SIG_EOCD = 0x06054b50;
    private static final int SIG_EOCD64 = 0x06064b50;
    private static final int SIG_LOC64 = 0x07064b50;
    private static final long MAX_CD_BYTES = 256L << 20;
    private static final long U32 = 0xFFFFFFFFL;

    // ---------------------------------------------------------------------------------------------
    // Model
    // ---------------------------------------------------------------------------------------------

    public static final class Entry {
        public final String name;
        public final boolean dir;
        public final int versionMadeBy, versionNeeded, flags, method, dosTime, dosDate, internalAttrs;
        public final long crc, csize, size, lho, externalAttrs;
        final byte[] rawName;
        final byte[] comment;

        Entry(byte[] rawName, String name, int versionMadeBy, int versionNeeded, int flags, int method, int dosTime, int dosDate,
              long crc, long csize, long size, long lho, int internalAttrs, long externalAttrs, byte[] comment) {
            this.rawName = rawName;
            this.name = name;
            this.dir = name.endsWith("/");
            this.versionMadeBy = versionMadeBy;
            this.versionNeeded = versionNeeded;
            this.flags = flags;
            this.method = method;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.crc = crc;
            this.csize = csize;
            this.size = size;
            this.lho = lho;
            this.internalAttrs = internalAttrs;
            this.externalAttrs = externalAttrs;
            this.comment = comment;
        }

        public boolean encrypted() {
            return (flags & 1) != 0;
        }

        public long mtime() {
            return dosToMillis(dosDate, dosTime);
        }

        /** Last path segment. */
        public String baseName() {
            String n = dir ? name.substring(0, name.length() - 1) : name;
            int i = n.lastIndexOf('/');
            return i < 0 ? n : n.substring(i + 1);
        }
    }

    /** A parsed archive: the entry list plus where to read the data from. Immutable. */
    public static final class Archive {
        public final File file;
        public final long length;
        public final long lastModified;
        public final List<Entry> entries;
        public final byte[] comment;
        public final boolean zip64;
        private Map<String, Entry> byName;

        Archive(File file, long length, long lastModified, List<Entry> entries, byte[] comment, boolean zip64) {
            this.file = file;
            this.length = length;
            this.lastModified = lastModified;
            this.entries = entries;
            this.comment = comment;
            this.zip64 = zip64;
        }

        public synchronized Entry find(String name) {
            if (byName == null) {
                byName = new HashMap<String, Entry>();
                for (Entry e : entries) if (!byName.containsKey(e.name)) byName.put(e.name, e);
            }
            return byName.get(name);
        }

        public boolean isStale() {
            return file.length() != length || file.lastModified() != lastModified;
        }

        /** Opens the entry's decompressed bytes. The caller closes the stream. */
        public InputStream open(Entry e) throws IOException {
            if (e.encrypted()) throw new IOException("This entry is encrypted");
            if (e.method != 0 && e.method != 8) throw new IOException("Unsupported compression method " + e.method);
            final RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                long start = dataStart(raf, e);
                if (start + e.csize > raf.length()) throw new IOException("The archive is truncated");
                raf.seek(start);
                InputStream raw = new BufferedInputStream(new Bounded(raf, e.csize), 65536);
                if (e.method == 0) return raw;
                final Inflater inf = new Inflater(true);
                return new InflaterInputStream(raw, inf, 65536) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            inf.end();
                        }
                    }
                };
            } catch (IOException ex) {
                raf.close();
                throw ex;
            }
        }
    }

    /** Reads at most {@code limit} bytes of the file from the current position. */
    private static final class Bounded extends InputStream {
        private final RandomAccessFile raf;
        private long left;

        Bounded(RandomAccessFile raf, long limit) {
            this.raf = raf;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = raf.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = raf.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Opening (central directory)
    // ---------------------------------------------------------------------------------------------

    public static Archive open(File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long len = raf.length();
            if (len < 22) throw new IOException("Not a zip archive (the file is too small)");
            int scan = (int) Math.min(len, 22L + 65535L);
            byte[] tail = new byte[scan];
            raf.seek(len - scan);
            raf.readFully(tail);
            int p = -1;
            for (int i = scan - 22; i >= 0; i--) {
                if (le32(tail, i) == SIG_EOCD) {
                    int cl = le16(tail, i + 20);
                    if (i + 22 + cl <= scan) {
                        p = i;
                        break;
                    }
                }
            }
            if (p < 0) throw new IOException("Not a zip archive (no central directory found)");
            long eocdPos = len - scan + p;
            long count = le16(tail, p + 10);
            long cdSize = le32u(tail, p + 12);
            long cdOff = le32u(tail, p + 16);
            byte[] comment = Arrays.copyOfRange(tail, p + 22, p + 22 + le16(tail, p + 20));
            boolean zip64 = false;
            if (count == 0xFFFF || cdSize == U32 || cdOff == U32) {
                long locPos = eocdPos - 20;
                if (locPos >= 0) {
                    byte[] loc = new byte[20];
                    raf.seek(locPos);
                    raf.readFully(loc);
                    if (le32(loc, 0) == SIG_LOC64) {
                        byte[] r = new byte[56];
                        raf.seek(le64(loc, 8));
                        raf.readFully(r);
                        if (le32(r, 0) == SIG_EOCD64) {
                            count = le64(r, 32);
                            cdSize = le64(r, 40);
                            cdOff = le64(r, 48);
                            zip64 = true;
                        }
                    }
                }
            }
            if (cdSize < 0 || cdSize > MAX_CD_BYTES || cdOff < 0 || cdOff + cdSize > len) {
                throw new IOException("Corrupt zip archive (bad central directory)");
            }
            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOff);
            raf.readFully(cd);
            List<Entry> list = new ArrayList<Entry>((int) Math.min(Math.max(count, 16), 1000000));
            int pos = 0;
            while (pos + 46 <= cd.length) {
                if (le32(cd, pos) != SIG_CENTRAL) throw new IOException("Corrupt zip archive (bad central directory entry)");
                int madeBy = le16(cd, pos + 4);
                int needed = le16(cd, pos + 6);
                int flags = le16(cd, pos + 8);
                int method = le16(cd, pos + 10);
                int time = le16(cd, pos + 12);
                int date = le16(cd, pos + 14);
                long crc = le32u(cd, pos + 16);
                long csize = le32u(cd, pos + 20);
                long size = le32u(cd, pos + 24);
                int nl = le16(cd, pos + 28);
                int el = le16(cd, pos + 30);
                int cl = le16(cd, pos + 32);
                int disk = le16(cd, pos + 34);
                int internal = le16(cd, pos + 36);
                long external = le32u(cd, pos + 38);
                long lho = le32u(cd, pos + 42);
                if (pos + 46 + nl + el + cl > cd.length) throw new IOException("Corrupt zip archive (entry runs past the directory)");
                byte[] raw = Arrays.copyOfRange(cd, pos + 46, pos + 46 + nl);
                if (size == U32 || csize == U32 || lho == U32 || disk == 0xFFFF) {
                    int xp = pos + 46 + nl;
                    int xend = xp + el;
                    while (xp + 4 <= xend) {
                        int id = le16(cd, xp);
                        int sz = le16(cd, xp + 2);
                        int dp = xp + 4;
                        if (dp + sz > xend) break;
                        if (id == 0x0001) {
                            if (size == U32 && dp + 8 <= xp + 4 + sz) { size = le64(cd, dp); dp += 8; }
                            if (csize == U32 && dp + 8 <= xp + 4 + sz) { csize = le64(cd, dp); dp += 8; }
                            if (lho == U32 && dp + 8 <= xp + 4 + sz) { lho = le64(cd, dp); dp += 8; }
                            zip64 = true;
                            break;
                        }
                        xp += 4 + sz;
                    }
                }
                byte[] cmt = Arrays.copyOfRange(cd, pos + 46 + nl + el, pos + 46 + nl + el + cl);
                list.add(new Entry(raw, decodeName(raw), madeBy, needed, flags, method, time, date, crc, csize, size, lho, internal, external, cmt));
                pos += 46 + nl + el + cl;
            }
            return new Archive(f, len, f.lastModified(), Collections.unmodifiableList(list), comment, zip64);
        } finally {
            raf.close();
        }
    }

    private static String decodeName(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
    }

    private static long dataStart(RandomAccessFile raf, Entry e) throws IOException {
        byte[] h = new byte[30];
        raf.seek(e.lho);
        raf.readFully(h);
        if (le32(h, 0) != SIG_LOCAL) throw new IOException("Corrupt zip archive (bad local header for " + e.name + ")");
        return e.lho + 30 + le16(h, 26) + le16(h, 28);
    }

    // ---------------------------------------------------------------------------------------------
    // Folder view
    // ---------------------------------------------------------------------------------------------

    public static final class Child {
        public final String name;       // one path segment
        public final String path;       // full path inside the archive (folders end with '/')
        public final boolean dir;
        public final long size, csize;  // folders: totals of everything below
        public final int count;         // folders: number of files below
        public final long mtime;
        public final Entry entry;       // null for a folder that has no entry of its own

        Child(String name, String path, boolean dir, long size, long csize, int count, long mtime, Entry entry) {
            this.name = name;
            this.path = path;
            this.dir = dir;
            this.size = size;
            this.csize = csize;
            this.count = count;
            this.mtime = mtime;
            this.entry = entry;
        }
    }

    private static final class DirAcc {
        long size, csize, mtime;
        int count;
        Entry entry;
    }

    /** The immediate children of a folder ("" is the root, otherwise a path ending in '/'): folders first, then files. */
    public static List<Child> children(Archive a, String prefix) {
        if (prefix == null) prefix = "";
        Map<String, DirAcc> dirs = new LinkedHashMap<String, DirAcc>();
        List<Child> out = new ArrayList<Child>();
        for (Entry e : a.entries) {
            String n = e.name;
            if (n.length() <= prefix.length() || !n.startsWith(prefix)) continue;
            String rest = n.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                out.add(new Child(rest, n, false, e.size, e.csize, 0, e.mtime(), e));
                continue;
            }
            if (slash == 0) continue;
            String d = rest.substring(0, slash);
            DirAcc acc = dirs.get(d);
            if (acc == null) {
                acc = new DirAcc();
                dirs.put(d, acc);
            }
            if (rest.length() == slash + 1) {
                acc.entry = e;                   // the folder's own entry
                acc.mtime = Math.max(acc.mtime, e.mtime());
            } else if (!e.dir) {
                acc.count++;
                acc.size += e.size;
                acc.csize += e.csize;
                acc.mtime = Math.max(acc.mtime, e.mtime());
            }
        }
        List<Child> result = new ArrayList<Child>();
        for (Map.Entry<String, DirAcc> d : dirs.entrySet()) {
            DirAcc acc = d.getValue();
            result.add(new Child(d.getKey(), prefix + d.getKey() + "/", true, acc.size, acc.csize, acc.count, acc.mtime, acc.entry));
        }
        Collections.sort(result, BY_NAME);
        Collections.sort(out, BY_NAME);
        result.addAll(out);
        return result;
    }

    private static final Comparator<Child> BY_NAME = new Comparator<Child>() {
        @Override
        public int compare(Child x, Child y) {
            int c = x.name.compareToIgnoreCase(y.name);
            return c != 0 ? c : x.name.compareTo(y.name);
        }
    };

    /** Files whose full path contains the query (case-insensitive), at most {@code limit}. */
    public static List<Entry> search(Archive a, String query, int limit) {
        List<Entry> out = new ArrayList<Entry>();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return out;
        for (Entry e : a.entries) {
            if (e.dir) continue;
            if (e.name.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /** Every entry at or below a folder prefix (or the single entry when the name has no trailing slash). */
    public static List<Entry> under(Archive a, String path) {
        List<Entry> out = new ArrayList<Entry>();
        boolean tree = path.isEmpty() || path.endsWith("/");
        for (Entry e : a.entries) {
            if (tree ? e.name.startsWith(path) : e.name.equals(path)) out.add(e);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------------------------------------

    /**
     * Normalizes an entry name for writing: forward slashes, no leading slash, no "." segments, no control
     * characters. Returns null for anything unsafe (a ".." segment, empty, too long).
     */
    public static String safeName(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        boolean dir = n.endsWith("/");
        StringBuilder sb = new StringBuilder();
        for (String part : n.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < 0x20 || c == 0x7f) return null;
            }
            sb.append(part).append('/');
        }
        if (sb.length() == 0) return null;
        if (!dir) sb.setLength(sb.length() - 1);
        return sb.length() > 1024 ? null : sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Reading and extracting
    // ---------------------------------------------------------------------------------------------

    public static final class Head {
        public final byte[] data;
        public final boolean truncated;

        Head(byte[] data, boolean truncated) {
            this.data = data;
            this.truncated = truncated;
        }
    }

    /** The first {@code max} bytes of an entry. */
    public static Head readHead(Archive a, Entry e, int max) throws IOException {
        InputStream in = a.open(e);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(16, (int) Math.min(max, Math.max(e.size, 16))));
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while (total < max && (n = in.read(buf, 0, Math.min(buf.length, max - total))) > 0) {
                bo.write(buf, 0, n);
                total += n;
            }
            boolean more = total >= max && in.read() >= 0;
            return new Head(bo.toByteArray(), more);
        } finally {
            in.close();
        }
    }

    public interface Progress {
        /** Return false to cancel. */
        boolean onProgress(long doneBytes, int doneFiles, String current);
    }

    /** Extracts one entry to {@code dest} (parent folders are created). Returns the bytes written. */
    public static long extractTo(Archive a, Entry e, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Can't create " + parent);
        InputStream in = a.open(e);
        try {
            OutputStream out = new FileOutputStream(dest);
            long total = 0;
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            } finally {
                out.close();
            }
            long when = e.mtime();
            if (when > 0) dest.setLastModified(when);
            return total;
        } finally {
            in.close();
        }
    }

    /**
     * Extracts a folder (or a single file when {@code path} has no trailing slash) into {@code destDir}, keeping
     * the folder's own name as the top level. Entry names are checked so nothing can land outside destDir.
     * Returns {files, bytes, skipped}.
     */
    public static long[] extractTree(Archive a, String path, File destDir, Progress cb) throws IOException {
        boolean tree = path.isEmpty() || path.endsWith("/");
        String base = "";
        if (tree && !path.isEmpty()) {
            String trimmed = path.substring(0, path.length() - 1);
            base = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        }
        String canonRoot = destDir.getCanonicalPath();
        long bytes = 0;
        int files = 0, skipped = 0;
        for (Entry e : under(a, path)) {
            if (e.dir) continue;
            String rel;
            if (tree) rel = (base.isEmpty() ? "" : base + "/") + e.name.substring(path.length());
            else rel = e.baseName();
            String safe = safeName(rel);
            if (safe == null) { skipped++; continue; }
            File out = new File(destDir, safe);
            String canon = out.getCanonicalPath();
            if (!canon.startsWith(canonRoot + File.separator)) { skipped++; continue; }
            if (cb != null && !cb.onProgress(bytes, files, e.name)) throw new IOException("Cancelled");
            bytes += extractTo(a, e, out);
            files++;
        }
        if (cb != null) cb.onProgress(bytes, files, "");
        return new long[]{files, bytes, skipped};
    }

    // ---------------------------------------------------------------------------------------------
    // Sniffing (what is this entry?)
    // ---------------------------------------------------------------------------------------------

    /** "image", "axml" (compiled Android XML), "text" or "hex". */
    public static String classify(String name, byte[] head, int n) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("gif") || ext.equals("webp")
                || ext.equals("bmp") || ext.equals("ico")) {
            if (n >= 4 && !isBinaryImageMagic(head, n)) return looksLikeText(head, n) ? "text" : "hex";
            return "image";
        }
        if (n >= 8 && (head[0] & 0xFF) == 0x03 && head[1] == 0x00 && (head[2] & 0xFF) == 0x08 && head[3] == 0x00) return "axml";
        return looksLikeText(head, n) ? "text" : "hex";
    }

    private static boolean isBinaryImageMagic(byte[] h, int n) {
        if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return true;
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return true;
        if (n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return true;
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E') return true;
        if (n >= 2 && h[0] == 'B' && h[1] == 'M') return true;
        return n >= 4 && h[0] == 0 && h[1] == 0 && h[2] == 1 && h[3] == 0;   // .ico
    }

    public static String imageMime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    /** Valid UTF-8 (a cut-off multi-byte character at the very end is fine), no NULs, and mostly printable. */
    public static boolean looksLikeText(byte[] b, int n) {
        if (n == 0) return true;
        int ctrl = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 0) return false;
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != 0x0c && c != 0x1b) ctrl++;
        }
        if (ctrl * 100 > n * 2) return false;
        return isValidUtf8(b, n, true);
    }

    /** Strict UTF-8 check of the first {@code n} bytes. With {@code allowCutTail} an incomplete last character is accepted. */
    public static boolean isValidUtf8(byte[] b, int n, boolean allowCutTail) {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(b, 0, n);
        CharBuffer out = CharBuffer.allocate(Math.max(16, n));
        // endOfInput=false leaves an incomplete trailing character unconsumed instead of calling it malformed
        java.nio.charset.CoderResult r = dec.decode(in, out, false);
        if (r.isError()) return false;
        return !in.hasRemaining() || (allowCutTail && in.remaining() < 4);
    }

    /** "00000000  48 65 6c 6c 6f ...  |Hello...|" lines for the first {@code max} bytes. */
    public static String hexDump(byte[] b, int n, int max) {
        StringBuilder sb = new StringBuilder();
        int lim = Math.min(n, max);
        for (int off = 0; off < lim; off += 16) {
            sb.append(String.format(Locale.ROOT, "%08x ", off));
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (i == 8) sb.append(' ');
                if (off + i < lim) {
                    int c = b[off + i] & 0xFF;
                    sb.append(String.format(Locale.ROOT, " %02x", c));
                    ascii.append(c >= 0x20 && c < 0x7f ? (char) c : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append("  |").append(ascii).append("|\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Rewriting
    // ---------------------------------------------------------------------------------------------

    public static final class Edit {
        public enum Op { DELETE, DELETE_TREE, RENAME, RENAME_TREE, REPLACE, ADD }

        public final Op op;
        public final String name;
        public final String to;
        public final byte[] data;
        public final File file;

        private Edit(Op op, String name, String to, byte[] data, File file) {
            this.op = op;
            this.name = name;
            this.to = to;
            this.data = data;
            this.file = file;
        }

        public static Edit delete(String name) { return new Edit(Op.DELETE, name, null, null, null); }
        public static Edit deleteTree(String prefix) { return new Edit(Op.DELETE_TREE, prefix, null, null, null); }
        public static Edit rename(String from, String to) { return new Edit(Op.RENAME, from, to, null, null); }
        public static Edit renameTree(String fromPrefix, String toPrefix) { return new Edit(Op.RENAME_TREE, fromPrefix, toPrefix, null, null); }
        public static Edit replace(String name, byte[] data) { return new Edit(Op.REPLACE, name, null, data, null); }
        public static Edit replace(String name, File file) { return new Edit(Op.REPLACE, name, null, null, file); }
        public static Edit add(String name, byte[] data) { return new Edit(Op.ADD, name, null, data, null); }
        public static Edit add(String name, File file) { return new Edit(Op.ADD, name, null, null, file); }
    }

    private static final class CdRec {
        byte[] name, comment;
        int madeBy, needed, flags, method, time, date, internal;
        long crc, csize, size, lho, external;
    }

    private static final class Out extends OutputStream {
        private final OutputStream o;
        long pos;

        Out(OutputStream o) {
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
            pos++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            pos += len;
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

    /**
     * Writes {@code dst} as {@code src} with the edits applied. Unchanged entries are copied as raw compressed
     * bytes. With {@code align}, stored entries get zipalign-style padding (4 bytes, 4 KB for .so files), which
     * Android requires of an APK's resources.arsc and uncompressed native libraries.
     */
    public static void rewrite(Archive src, File dst, List<Edit> edits, boolean align, Progress cb) throws IOException {
        Set<String> deleted = new HashSet<String>();
        List<String> deletedTrees = new ArrayList<String>();
        Map<String, String> renames = new HashMap<String, String>();
        List<String[]> renameTrees = new ArrayList<String[]>();
        Map<String, Edit> replaced = new HashMap<String, Edit>();
        List<Edit> added = new ArrayList<Edit>();
        for (Edit ed : edits) {
            switch (ed.op) {
                case DELETE: deleted.add(ed.name); break;
                case DELETE_TREE: deletedTrees.add(ed.name); break;
                case RENAME: renames.put(ed.name, requireSafe(ed.to)); break;
                case RENAME_TREE: renameTrees.add(new String[]{ed.name, requireSafeDir(ed.to)}); break;
                case REPLACE: replaced.put(ed.name, ed); break;
                case ADD: added.add(ed); break;
                default: break;
            }
        }
        RandomAccessFile raf = new RandomAccessFile(src.file, "r");
        Out out = null;
        List<CdRec> cd = new ArrayList<CdRec>();
        Set<String> names = new HashSet<String>();
        boolean ok = false;
        try {
            out = new Out(new BufferedOutputStream(new FileOutputStream(dst), 131072));
            long copied = 0;
            int done = 0;
            for (Entry e : src.entries) {
                String n = e.name;
                if (deleted.contains(n) || startsWithAny(n, deletedTrees)) continue;
                String outName = n;
                String renamed = renames.get(n);
                if (renamed != null) {
                    outName = e.dir && !renamed.endsWith("/") ? renamed + "/" : renamed;
                } else {
                    for (String[] rt : renameTrees) {
                        if (n.startsWith(rt[0])) {
                            outName = rt[1] + n.substring(rt[0].length());
                            break;
                        }
                    }
                }
                if (!names.add(outName)) throw new IOException("An entry named \"" + outName + "\" already exists");
                if (cb != null && !cb.onProgress(copied, done, outName)) throw new IOException("Cancelled");
                Edit rep = replaced.get(n);
                if (rep != null && !e.dir) {
                    writeNew(out, cd, outName, rep, e.method == 0, align, e.externalAttrs, e.versionMadeBy);
                } else {
                    copyRaw(out, cd, raf, e, outName, align);
                    copied += e.csize;
                }
                done++;
            }
            for (Edit ed : added) {
                String name = requireSafe(ed.name);
                if (!names.add(name)) throw new IOException("An entry named \"" + name + "\" already exists");
                if (cb != null && !cb.onProgress(copied, done, name)) throw new IOException("Cancelled");
                writeNew(out, cd, name, ed, false, align, 0, 0);
                done++;
            }
            long cdStart = out.pos;
            for (CdRec r : cd) writeCd(out, r);
            long cdSize = out.pos - cdStart;
            if (cd.size() > 0xFFFE || cdStart > 0xFFFFFFFEL || cdSize > 0xFFFFFFFEL) {
                throw new IOException("This archive is too large to rewrite (ZIP64 output is not supported)");
            }
            byte[] c = src.comment == null ? new byte[0] : src.comment;
            byte[] eocd = new byte[22];
            put32(eocd, 0, SIG_EOCD);
            put16(eocd, 8, cd.size());
            put16(eocd, 10, cd.size());
            put32(eocd, 12, cdSize);
            put32(eocd, 16, cdStart);
            put16(eocd, 20, c.length);
            out.write(eocd);
            out.write(c);
            out.flush();
            ok = true;
        } finally {
            raf.close();
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            if (!ok) dst.delete();
        }
    }

    private static String requireSafe(String name) throws IOException {
        String s = safeName(name);
        if (s == null) throw new IOException("Not a valid name inside an archive: " + name);
        return s;
    }

    private static String requireSafeDir(String name) throws IOException {
        String s = requireSafe(name);
        return s.endsWith("/") ? s : s + "/";
    }

    private static boolean startsWithAny(String n, List<String> prefixes) {
        for (String p : prefixes) if (n.startsWith(p)) return true;
        return false;
    }

    private static int padFor(long headerEnd, int alignment) {
        if (alignment <= 1) return 0;
        return (int) ((alignment - (headerEnd % alignment)) % alignment);
    }

    private static int alignmentFor(String name, int method, boolean align) {
        if (!align || method != 0) return 1;
        return name.toLowerCase(Locale.ROOT).endsWith(".so") ? 4096 : 4;
    }

    private static byte[] extraPadding(int pad) {
        // zipalign's own padding record where it fits (id 0xD935), otherwise plain zero bytes
        byte[] x = new byte[pad];
        if (pad >= 4) {
            put16(x, 0, 0xD935);
            put16(x, 2, pad - 4);
        }
        return x;
    }

    private static void copyRaw(Out out, List<CdRec> cd, RandomAccessFile raf, Entry e, String outName, boolean align) throws IOException {
        boolean same = outName.equals(e.name);
        byte[] nameBytes = same ? e.rawName : outName.getBytes(StandardCharsets.UTF_8);
        // Sizes are known now, so the data descriptor flag goes - except for encrypted entries, whose check byte
        // is derived from the flag (it must stay as it was, and the descriptor is written back below).
        boolean enc = e.encrypted();
        int flags = enc ? e.flags : e.flags & ~0x0008;
        if (!same) flags = isAscii(nameBytes) ? flags & ~0x0800 : flags | 0x0800;
        int method = e.method;
        long headerEnd = out.pos + 30 + nameBytes.length;
        int pad = padFor(headerEnd, alignmentFor(outName, method, align));
        long lho = out.pos;
        byte[] h = new byte[30];
        put32(h, 0, SIG_LOCAL);
        put16(h, 4, e.versionNeeded == 0 ? (method == 8 ? 20 : 10) : e.versionNeeded);
        put16(h, 6, flags);
        put16(h, 8, method);
        put16(h, 10, e.dosTime);
        put16(h, 12, e.dosDate);
        put32(h, 14, e.crc);
        put32(h, 18, e.csize);
        put32(h, 22, e.size);
        put16(h, 26, nameBytes.length);
        put16(h, 28, pad);
        out.write(h);
        out.write(nameBytes);
        if (pad > 0) out.write(extraPadding(pad));
        if (e.csize > 0) {
            raf.seek(dataStart(raf, e));
            byte[] buf = new byte[65536];
            long left = e.csize;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("The archive is truncated");
                out.write(buf, 0, n);
                left -= n;
            }
        }
        if ((flags & 0x0008) != 0) {
            byte[] dd = new byte[16];
            put32(dd, 0, 0x08074b50);
            put32(dd, 4, e.crc);
            put32(dd, 8, e.csize);
            put32(dd, 12, e.size);
            out.write(dd);
        }
        CdRec r = new CdRec();
        r.name = nameBytes;
        r.comment = e.comment;
        r.madeBy = e.versionMadeBy;
        r.needed = e.versionNeeded == 0 ? (method == 8 ? 20 : 10) : e.versionNeeded;
        r.flags = flags;
        r.method = method;
        r.time = e.dosTime;
        r.date = e.dosDate;
        r.internal = e.internalAttrs;
        r.external = e.externalAttrs;
        r.crc = e.crc;
        r.csize = e.csize;
        r.size = e.size;
        r.lho = lho;
        cd.add(r);
    }

    private static boolean isAscii(byte[] b) {
        for (byte x : b) if (x < 0 || x > 0x7e) return false;
        return true;
    }

    private static boolean alreadyCompressed(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        String[] exts = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".zip", ".apk", ".jar", ".aar", ".mp3", ".mp4", ".ogg", ".m4a",
                ".mkv", ".webm", ".7z", ".gz", ".xz", ".bz2", ".rar", ".woff2"};
        for (String x : exts) if (l.endsWith(x)) return true;
        return false;
    }

    private static void writeNew(Out out, List<CdRec> cd, String name, Edit ed, boolean keepStored, boolean align,
                                 long external, int madeBy) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        boolean utf8 = !isAscii(nameBytes);
        boolean store = keepStored || alreadyCompressed(name) || name.endsWith("/");
        long when = System.currentTimeMillis();
        int dosTime = millisToDosTime(when), dosDate = millisToDosDate(when);
        long dataLen = ed.data != null ? ed.data.length : (ed.file != null ? ed.file.length() : 0);
        CdRec r = new CdRec();
        r.name = nameBytes;
        r.comment = new byte[0];
        r.madeBy = madeBy != 0 ? madeBy : 0x031E;
        r.time = dosTime;
        r.date = dosDate;
        r.internal = 0;
        r.external = external != 0 ? external : ((0100644L << 16));
        r.lho = out.pos;
        if (store) {
            CRC32 crc = new CRC32();
            if (ed.data != null) {
                crc.update(ed.data, 0, ed.data.length);
            } else if (ed.file != null) {
                InputStream in = new FileInputStream(ed.file);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
                } finally {
                    in.close();
                }
            }
            r.method = 0;
            r.needed = 10;
            r.flags = utf8 ? 0x0800 : 0;
            r.crc = crc.getValue();
            r.csize = dataLen;
            r.size = dataLen;
            int pad = padFor(out.pos + 30 + nameBytes.length, alignmentFor(name, 0, align));
            writeLocal(out, r, pad);
            copyInto(out, ed);
        } else {
            r.method = 8;
            r.needed = 20;
            r.flags = 0x0008 | (utf8 ? 0x0800 : 0);
            writeLocal(out, r, 0);
            long before = out.pos;
            CRC32 crc = new CRC32();
            Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            DeflaterOutputStream dos = new DeflaterOutputStream(new NoClose(out), def, 65536);
            long size = 0;
            try {
                if (ed.data != null) {
                    dos.write(ed.data, 0, ed.data.length);
                    crc.update(ed.data, 0, ed.data.length);
                    size = ed.data.length;
                } else if (ed.file != null) {
                    InputStream in = new FileInputStream(ed.file);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            dos.write(buf, 0, n);
                            crc.update(buf, 0, n);
                            size += n;
                        }
                    } finally {
                        in.close();
                    }
                }
                dos.finish();
            } finally {
                def.end();
            }
            r.crc = crc.getValue();
            r.csize = out.pos - before;
            r.size = size;
            byte[] dd = new byte[16];
            put32(dd, 0, 0x08074b50);
            put32(dd, 4, r.crc);
            put32(dd, 8, r.csize);
            put32(dd, 12, r.size);
            out.write(dd);
            // the central directory is authoritative; keep the descriptor flag only for the streamed entry
        }
        cd.add(r);
    }

    /** Lets a DeflaterOutputStream finish without closing the archive stream. */
    private static final class NoClose extends OutputStream {
        private final OutputStream o;

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
        public void close() {
        }
    }

    private static void writeLocal(Out out, CdRec r, int pad) throws IOException {
        byte[] h = new byte[30];
        boolean streamed = (r.flags & 0x0008) != 0;
        put32(h, 0, SIG_LOCAL);
        put16(h, 4, r.needed);
        put16(h, 6, r.flags);
        put16(h, 8, r.method);
        put16(h, 10, r.time);
        put16(h, 12, r.date);
        put32(h, 14, streamed ? 0 : r.crc);
        put32(h, 18, streamed ? 0 : r.csize);
        put32(h, 22, streamed ? 0 : r.size);
        put16(h, 26, r.name.length);
        put16(h, 28, pad);
        out.write(h);
        out.write(r.name);
        if (pad > 0) out.write(extraPadding(pad));
    }

    private static void copyInto(Out out, Edit ed) throws IOException {
        if (ed.data != null) {
            out.write(ed.data, 0, ed.data.length);
        } else if (ed.file != null) {
            InputStream in = new FileInputStream(ed.file);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
            }
        }
    }

    private static void writeCd(Out out, CdRec r) throws IOException {
        byte[] h = new byte[46];
        put32(h, 0, SIG_CENTRAL);
        put16(h, 4, r.madeBy);
        put16(h, 6, r.needed);
        put16(h, 8, r.flags);
        put16(h, 10, r.method);
        put16(h, 12, r.time);
        put16(h, 14, r.date);
        put32(h, 16, r.crc);
        put32(h, 20, r.csize);
        put32(h, 24, r.size);
        put16(h, 28, r.name.length);
        put16(h, 30, 0);
        put16(h, 32, r.comment == null ? 0 : r.comment.length);
        put16(h, 34, 0);
        put16(h, 36, r.internal);
        put32(h, 38, r.external);
        put32(h, 42, r.lho);
        out.write(h);
        out.write(r.name);
        if (r.comment != null && r.comment.length > 0) out.write(r.comment);
    }

    // ---------------------------------------------------------------------------------------------
    // Little-endian helpers and DOS dates
    // ---------------------------------------------------------------------------------------------

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static long le32u(byte[] b, int o) {
        return le32(b, o) & U32;
    }

    private static long le64(byte[] b, int o) {
        return le32u(b, o) | (le32u(b, o + 4) << 32);
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

    static long dosToMillis(int date, int time) {
        int year = ((date >> 9) & 0x7f) + 1980;
        int month = (date >> 5) & 0x0f;
        int day = date & 0x1f;
        if (month < 1 || month > 12 || day < 1) return 0;
        int hour = (time >> 11) & 0x1f;
        int min = (time >> 5) & 0x3f;
        int sec = (time & 0x1f) * 2;
        return new GregorianCalendar(year, month - 1, day, Math.min(hour, 23), Math.min(min, 59), Math.min(sec, 59)).getTimeInMillis();
    }

    static int millisToDosDate(long millis) {
        GregorianCalendar c = new GregorianCalendar();
        c.setTimeInMillis(millis);
        int y = c.get(GregorianCalendar.YEAR);
        if (y < 1980) return (1 << 5) | 1;
        return ((y - 1980) << 9) | ((c.get(GregorianCalendar.MONTH) + 1) << 5) | c.get(GregorianCalendar.DAY_OF_MONTH);
    }

    static int millisToDosTime(long millis) {
        GregorianCalendar c = new GregorianCalendar();
        c.setTimeInMillis(millis);
        return (c.get(GregorianCalendar.HOUR_OF_DAY) << 11) | (c.get(GregorianCalendar.MINUTE) << 5) | (c.get(GregorianCalendar.SECOND) / 2);
    }
}
