package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The five emoji fonts the Custom Fonts tab offers to download, and the helpers that download and unpack them.
 *
 * <p>The catalogue ({@link #ITEMS}) is fixed data: each entry's id, name, version, colour format, whether it can be merged into a text
 * font and the Android version a merge needs, the download URL, the exact size and SHA-256 of the file the app keeps (for Twemoji the zip
 * it comes in as well), and its licence, source and attribution. Every value here was checked against the real file at development time;
 * the suite recomputes them.
 *
 * <p>The network itself is {@code MainActivity}'s ({@code HttpSafe.open}); these helpers take an {@link InputStream}. {@link #download}
 * streams it to a {@code .part} file, counting the bytes and the SHA-256, refuses a file whose size or hash is not exactly what the
 * catalogue says (or that runs over a cap) by deleting the {@code .part}, and only then moves it into place - a download fails closed on a
 * mismatch. {@link #extractEntry} pulls Twemoji's {@code fonts/twemoji.ttf} out of the downloaded zip with the repository's own zip reader
 * ({@link ZipTool}), under the same checks. Pure Java, no Android classes.
 */
public final class EmojiCatalog {

    private EmojiCatalog() {}

    /** One downloadable emoji font. */
    public static final class Item {
        public final String id;
        public final String name;
        public final String version;
        /** "COLRv0", "COLRv1" or "CBDT". */
        public final String format;
        /** Whether this font can be merged into a text font (a colour-bitmap font cannot). */
        public final boolean mergeable;
        /** The Android API level a merge of this font needs (COLRv1 is 33, COLRv0 is 26); 0 when it cannot be merged. */
        public final int minApiForMerge;
        public final String url;
        /** The exact size, in bytes, of the file the app keeps (for Twemoji, the extracted {@code fonts/twemoji.ttf}). */
        public final long size;
        /** The SHA-256 of that file, lower-case hex. */
        public final String sha256;
        /** Twemoji only: the entry inside the downloaded zip, else null. */
        public final String zipEntry;
        /** Twemoji only: the zip's own size, else 0. */
        public final long zipSize;
        /** Twemoji only: the zip's own SHA-256, else null. */
        public final String zipSha256;
        /** A lower-API fallback build (OpenMoji's COLRv0 for phones below Android 13), else null. */
        public final Item fallback;
        public final String license;
        public final String licenseUrl;
        public final String sourceUrl;
        public final String attribution;
        public final String note;

        Item(String id, String name, String version, String format, boolean mergeable, int minApiForMerge,
             String url, long size, String sha256, String zipEntry, long zipSize, String zipSha256, Item fallback,
             String license, String licenseUrl, String sourceUrl, String attribution, String note) {
            this.id = id; this.name = name; this.version = version; this.format = format;
            this.mergeable = mergeable; this.minApiForMerge = minApiForMerge;
            this.url = url; this.size = size; this.sha256 = sha256;
            this.zipEntry = zipEntry; this.zipSize = zipSize; this.zipSha256 = zipSha256; this.fallback = fallback;
            this.license = license; this.licenseUrl = licenseUrl; this.sourceUrl = sourceUrl; this.attribution = attribution; this.note = note;
        }

        /** The file is a zip to unpack (Twemoji), not the font itself. */
        public boolean isZip() { return zipEntry != null; }

        /**
         * The build to use on a phone at API level {@code sdk}: this one, or its lower-API fallback when this one needs a newer Android than
         * {@code sdk} and a fallback exists. A font that cannot be merged, or that this phone can already merge, is returned unchanged.
         */
        public Item forSdk(int sdk) {
            if (!mergeable || sdk >= minApiForMerge || fallback == null) return this;
            return fallback.minApiForMerge <= sdk ? fallback : this;
        }

        /** Whether this phone (API {@code sdk}) could merge this font: always true when it is not meant to be merged. */
        public boolean usableForMerge(int sdk) {
            return !mergeable || sdk >= minApiForMerge || (fallback != null && fallback.minApiForMerge <= sdk);
        }
    }

    // OpenMoji's COLRv0 build, offered as a fallback for phones below Android 13 (COLRv1 needs API 33, COLRv0 works from API 26).
    private static final Item OPENMOJI_COLRV0 = new Item(
            "openmoji", "OpenMoji", "17", "COLRv0", true, 26,
            "https://raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_0/OpenMoji-color-glyf_colr_0.ttf",
            2681320L, "8376fee074649ece235faba0d157d851f3d93632f0540ad1165feba1aba9ba37",
            null, 0, null, null,
            "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", "https://github.com/hfg-gmuend/openmoji",
            "OpenMoji (c) HfG Schwäbisch Gmünd", "");

    /** The five emoji fonts, in the order the page lists them. */
    public static final List<Item> ITEMS = Collections.unmodifiableList(buildItems());

    private static List<Item> buildItems() {
        List<Item> l = new ArrayList<Item>();
        // Noto's licence is the one next to the font, at the tag the font comes from (the repository moved its fonts to 2D/fonts)
        l.add(new Item(
                "noto", "Noto Color Emoji", "Unicode 18", "COLRv1", true, 33,
                "https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf",
                5038236L, "b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c",
                null, 0, null, null,
                "OFL-1.1", "https://github.com/googlefonts/noto-emoji/blob/v2026-09-24-unicode18_0/2D/fonts/LICENSE", "https://github.com/googlefonts/noto-emoji",
                "Noto Color Emoji, Copyright 2022 Google Inc.", ""));
        l.add(new Item(
                "twemoji", "Twemoji", "17", "COLRv0", true, 26,
                "https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip",
                2003200L, "d2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e",
                "fonts/twemoji.ttf", 1541484L, "d4e9b55a4e57dcedeab2b024d790a10d59656a21804957aefb9a3cccec865bd2", null,
                "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", "https://github.com/SableClient/twemoji-font",
                "Graphics (c) Twitter, Inc. and contributors (jdecked/twemoji); COLR build by SableClient (Apache-2.0)", ""));
        l.add(new Item(
                "blobmoji", "Blobmoji", "Emoji 17", "COLRv1", true, 33,
                "https://raw.githubusercontent.com/DavidBerdik/blobmoji2/blobmoji-17r1/fonts/Noto-COLRv1.ttf",
                4444184L, "be12a85b4ac069ef07541e861f96e99710ad4f5264600aff2dd396a640dbfb7f",
                null, 0, null, null,
                "OFL-1.1", "https://github.com/DavidBerdik/blobmoji2/blob/main/LICENSE", "https://github.com/DavidBerdik/blobmoji2",
                "Blobmoji2 by DavidBerdik (a fork of Noto Emoji with the blob art)", ""));
        l.add(new Item(
                "openmoji", "OpenMoji", "17", "COLRv1", true, 33,
                "https://raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_1/OpenMoji-color-glyf_colr_1.ttf",
                2401396L, "b6c86e3d95f557dae9a7d59a993b5a6f2274c401d6d975747d43612c6fab236d",
                null, 0, null, OPENMOJI_COLRV0,
                "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", "https://github.com/hfg-gmuend/openmoji",
                "OpenMoji (c) HfG Schwäbisch Gmünd", ""));
        l.add(new Item(
                "apple", "iOS emoji", "iOS 26.2", "CBDT", false, 0,
                "https://github.com/samuelngs/apple-emoji-ttf/releases/download/v26.2.1/AppleColorEmoji.ttf",
                43877492L, "e7b5f998812d9ab50d32f5626cb1e88edea345d725f8edf44a7ec3333b81043d",
                null, 0, null, null,
                "Proprietary (Apple Inc.)", "https://www.apple.com/legal/intellectual-property/", "https://github.com/samuelngs/apple-emoji-ttf",
                "(c) Apple Inc.; third-party build by samuelngs",
                "Apple's emoji art from a third-party build, not licensed by Apple for redistribution: the app downloads it only when you tap it, for your own phone, and it is applied as a system emoji swap (root), never merged."));
        return l;
    }

    /** The item with this id, or null. */
    public static Item byId(String id) {
        if (id == null) return null;
        for (Item it : ITEMS) if (it.id.equals(id)) return it;
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Downloading
    // ---------------------------------------------------------------------------------------------

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int BUF = 65536;

    /** A download that could not be trusted, with a stable code the page branches on ("size_mismatch", "sha_mismatch", "too_big", "no_space", "zip_entry", "bad_zip"). */
    public static final class VerifyException extends IOException {
        public final String code;
        public VerifyException(String code, String message) { super(message); this.code = code; }
    }

    /** Progress and Cancel for a download; {@link #NONE} reports nothing and never cancels. */
    public interface Progress {
        void onProgress(long done, long total);
        boolean cancelled();

        Progress NONE = new Progress() {
            @Override public void onProgress(long done, long total) {}
            @Override public boolean cancelled() { return false; }
        };
    }

    /**
     * Streams {@code in} to {@code dest} through a hidden {@code .part} file, checks it against {@code expectedSize} and
     * {@code expectedSha256}, and only then moves it into place. The {@code .part} is deleted and a {@link VerifyException} thrown when the
     * size or hash does not match, when the data runs past {@code cap} (a hard ceiling against an endless stream), when the cancel is set,
     * or when there is not room for the file. The caller owns and closes {@code in}. Returns the number of bytes written.
     */
    public static long download(InputStream in, File dest, long expectedSize, String expectedSha256, long cap, Progress p) throws IOException {
        if (in == null || dest == null) throw new VerifyException("bad_font", "Nothing to download.");
        if (expectedSize < 0) throw new VerifyException("size_mismatch", "The expected size is not known.");
        if (expectedSha256 == null || !SHA256.matcher(expectedSha256.toLowerCase(Locale.ROOT)).matches())
            throw new VerifyException("sha_mismatch", "The expected checksum is not valid.");
        if (cap <= 0) cap = Long.MAX_VALUE;
        if (expectedSize > cap) throw new VerifyException("too_big", "This file is larger than the app will download (" + expectedSize + " bytes).");
        String want = expectedSha256.toLowerCase(Locale.ROOT);
        if (p == null) p = Progress.NONE;

        File parent = dest.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Can't create " + parent);
        if (parent != null) {
            long usable = parent.getUsableSpace();
            if (usable > 0 && usable < expectedSize) throw new VerifyException("no_space", "There is not enough free space for this font (" + expectedSize + " bytes).");
        }

        File part = FileOps.partFor(dest, ".");
        MessageDigest md = sha256();
        boolean ok = false;
        long total = 0;
        try {
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[BUF];
                long lastPulse = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (p.cancelled()) throw new VerifyException("cancelled", "The download was cancelled.");
                    if (total + n > cap) throw new VerifyException("too_big", "This download ran past its size limit.");
                    if (total + n > expectedSize) throw new VerifyException("size_mismatch", "This file is larger than it should be.");
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    total += n;
                    if (total - lastPulse >= (1 << 18)) { lastPulse = total; p.onProgress(total, expectedSize); }
                }
            } finally {
                out.close();
            }
            if (p.cancelled()) throw new VerifyException("cancelled", "The download was cancelled.");     // a Cancel that came with the last bytes
            if (total != expectedSize) throw new VerifyException("size_mismatch", "This file is the wrong size (" + total + " bytes, expected " + expectedSize + ").");
            String got = hex(md.digest());
            if (!got.equals(want)) throw new VerifyException("sha_mismatch", "This file did not match its checksum; it was not kept.");
            p.onProgress(total, expectedSize);
            moveInto(part, dest);
            ok = true;
            return total;
        } finally {
            if (!ok) part.delete();
        }
    }

    /**
     * Pulls one entry out of a downloaded zip ({@link Item#zipEntry}, for Twemoji) with {@link ZipTool}, under the same size, SHA-256 and
     * cap checks as {@link #download}. The zip itself is assumed already verified (by its own size and hash); this checks the extracted
     * file. The caller deletes the zip afterwards. Returns the bytes written to {@code dest}.
     */
    public static long extractEntry(File zip, String entryName, File dest, long expectedSize, String expectedSha256, long cap, Progress p) throws IOException {
        if (zip == null || entryName == null) throw new VerifyException("zip_entry", "Nothing to unpack.");
        ZipTool.Archive a;
        try {
            a = ZipTool.open(zip);
        } catch (IOException e) {
            throw new VerifyException("bad_zip", "The downloaded file is not a readable zip.");
        }
        ZipTool.Entry e = a.find(entryName);
        if (e == null) throw new VerifyException("zip_entry", "The download did not contain \"" + entryName + "\".");
        long declared = e.size;
        if (declared > 0 && cap > 0 && declared > cap) throw new VerifyException("too_big", "The font inside the download is too large (" + declared + " bytes).");
        InputStream in = null;
        try {
            in = a.open(e);
            return download(in, dest, expectedSize, expectedSha256, cap, p);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Deletes what downloads that never ended left in the emoji folder: the hidden {@code .part} files of {@link #download} and Twemoji's
     * hidden zip ({@code .twemoji.zip}). A process that is killed (swiped away, or by the low-memory killer) never reaches the code that
     * deletes them, and the folder is not a cache Android clears. Only for a time when no download runs: the app calls it once, when the
     * process first uses the tab. Returns how many files went.
     */
    public static int sweep(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File k : kids) {
            String name = k.getName();
            if (k.isFile() && name.startsWith(".") && (name.endsWith(".part") || name.endsWith(".zip")) && k.delete()) n++;
        }
        return n;
    }

    private static void moveInto(File part, File dest) throws IOException {
        try {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            try {
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new IOException("The font could not be stored.");
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) { c[i * 2] = HEX[(b[i] >> 4) & 0xf]; c[i * 2 + 1] = HEX[b[i] & 0xf]; }
        return new String(c);
    }
}
