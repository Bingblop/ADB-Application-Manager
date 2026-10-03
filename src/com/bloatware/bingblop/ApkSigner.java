package com.bloatware.bingblop;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Signs an APK with APK Signature Scheme v2 (Android 7.0+, which is every device this app runs on): the APK
 * Signing Block holding a SHA-256 digest of the file, the signature over it, the public key and the certificate
 * goes between the ZIP entries and the central directory. An APK edited in the archive browser has lost its
 * signature (its old one no longer matches), so this signs it again with a key of the app's own.
 *
 * <p>Use {@link #prepare} first: it rewrites the APK without any old signature files and signing block, aligned
 * the way Android needs. Pure Java (java.security only), checked off-device against the real apksigner.
 */
public final class ApkSigner {

    private ApkSigner() {}

    private static final long V2_BLOCK_ID = 0x7109871aL;
    private static final byte[] MAGIC = "APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int CHUNK = 1024 * 1024;
    private static final int ALG_RSA_PKCS1_SHA256 = 0x0103;
    private static final int ALG_ECDSA_SHA256 = 0x0201;

    /** Entries that belong to an old v1 (JAR) signature; they no longer match once the APK is edited. */
    public static boolean isV1SignatureEntry(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (!n.startsWith("META-INF/")) return false;
        String rest = n.substring(9);
        if (rest.indexOf('/') >= 0) return false;
        return rest.equals("MANIFEST.MF") || rest.endsWith(".SF") || rest.endsWith(".RSA") || rest.endsWith(".DSA") || rest.endsWith(".EC")
                || rest.startsWith("SIG-");
    }

    /** Rewrites {@code in} to {@code out} without v1 signature files or an old signing block, zip-aligned. */
    public static void prepare(File in, File out) throws IOException {
        ZipTool.Archive a = ZipTool.open(in);
        List<ZipTool.Edit> edits = new ArrayList<ZipTool.Edit>();
        for (ZipTool.Entry e : a.entries) if (!e.dir && isV1SignatureEntry(e.name)) edits.add(ZipTool.Edit.delete(e.name));
        ZipTool.rewrite(a, out, edits, true, null);
    }

    /** SHA-256 of the certificate, as lower-case hex. */
    public static String certSha256(X509Certificate cert) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * Writes {@code out}: {@code in} (which must have no signing block - run {@link #prepare} first) with a v2
     * signature block inserted before the central directory.
     */
    public static void signV2(File in, File out, PrivateKey key, X509Certificate cert) throws Exception {
        ZipTool.Archive a = ZipTool.open(in);
        if (a.zip64) throw new IOException("ZIP64 archives can't be signed here");
        if (a.prefix != 0) throw new IOException("An APK with data in front of the archive can't be signed");
        long cdOffset = a.cdOffset;
        long cdSize = a.cdSize;
        long eocdOffset = a.eocdOffset;
        RandomAccessFile raf = new RandomAccessFile(in, "r");
        try {
            if (cdOffset >= 24) {
                byte[] tail = new byte[16];
                raf.seek(cdOffset - 16);
                raf.readFully(tail);
                if (Arrays.equals(tail, MAGIC)) throw new IOException("The APK already has a signing block (run prepare first)");
            }
            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOffset);
            raf.readFully(cd);
            int eocdLen = (int) (raf.length() - eocdOffset);
            byte[] eocd = new byte[eocdLen];
            raf.seek(eocdOffset);
            raf.readFully(eocd);

            byte[] digest = contentDigest(raf, cdOffset, cd, eocd);
            int alg = algorithmFor(key);
            byte[] signed = signedData(alg, digest, cert.getEncoded());
            byte[] signature = sign(alg, key, signed);
            byte[] pub = cert.getPublicKey().getEncoded();

            byte[] signer = concat(lp(signed), lp(lp(concat(le32(alg), lp(signature)))), lp(pub));
            byte[] v2Value = lp(lp(signer));
            byte[] pair = concat(le64(4 + v2Value.length), le32((int) V2_BLOCK_ID), v2Value);
            byte[] block = concat(le64(pair.length + 8 + 16), pair, le64(pair.length + 8 + 16), MAGIC);
            long blockLen = block.length;

            // The central-directory offset in the end record moves by the size of the block.
            ByteBuffer eb = ByteBuffer.wrap(eocd).order(ByteOrder.LITTLE_ENDIAN);
            eb.putInt(16, (int) (cdOffset + blockLen));
            if (cdOffset + blockLen > 0xFFFFFFFEL) throw new IOException("The signed APK would be too large");

            OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 131072);
            boolean ok = false;
            try {
                byte[] buf = new byte[65536];
                raf.seek(0);
                long left = cdOffset;
                while (left > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("The APK is truncated");
                    os.write(buf, 0, n);
                    left -= n;
                }
                os.write(block);
                os.write(cd);
                os.write(eocd);
                os.flush();
                ok = true;
            } finally {
                os.close();
                if (!ok) out.delete();
            }
        } finally {
            raf.close();
        }
    }

    private static int algorithmFor(PrivateKey key) throws IOException {
        String a = key.getAlgorithm();
        if ("RSA".equalsIgnoreCase(a)) return ALG_RSA_PKCS1_SHA256;
        if ("EC".equalsIgnoreCase(a) || "ECDSA".equalsIgnoreCase(a)) return ALG_ECDSA_SHA256;
        throw new IOException("Unsupported key type: " + a);
    }

    private static byte[] sign(int alg, PrivateKey key, byte[] data) throws Exception {
        Signature s = Signature.getInstance(alg == ALG_RSA_PKCS1_SHA256 ? "SHA256withRSA" : "SHA256withECDSA");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    /** digests + certificates + (no) additional attributes, as the signature covers them. */
    private static byte[] signedData(int alg, byte[] digest, byte[] certDer) {
        byte[] digests = lp(lp(concat(le32(alg), lp(digest))));
        byte[] certs = lp(lp(certDer));
        byte[] attrs = lp(new byte[0]);
        return concat(digests, certs, attrs);
    }

    /**
     * The v2 digest: every section (ZIP entries, central directory, end record) is cut into 1 MB chunks, each chunk
     * hashed as SHA-256(0xa5 | length | bytes), and the chunk hashes hashed again as SHA-256(0x5a | count | hashes).
     * The end record is hashed with its central-directory offset pointing at the start of the signing block, which
     * is where the central directory starts before the block is inserted.
     */
    private static byte[] contentDigest(RandomAccessFile raf, long cdOffset, byte[] cd, byte[] eocd) throws Exception {
        MessageDigest chunkMd = MessageDigest.getInstance("SHA-256");
        long chunks = chunkCount(cdOffset) + chunkCount(cd.length) + chunkCount(eocd.length);
        if (chunks > Integer.MAX_VALUE) throw new IOException("The APK is too large");
        byte[] all = new byte[(int) chunks * 32];
        int at = 0;
        byte[] buf = new byte[CHUNK];
        raf.seek(0);
        long left = cdOffset;
        while (left > 0) {
            int len = (int) Math.min(CHUNK, left);
            raf.readFully(buf, 0, len);
            at = hashChunk(chunkMd, buf, 0, len, all, at);
            left -= len;
        }
        for (int off = 0; off < cd.length; off += CHUNK) at = hashChunk(chunkMd, cd, off, Math.min(CHUNK, cd.length - off), all, at);
        for (int off = 0; off < eocd.length; off += CHUNK) at = hashChunk(chunkMd, eocd, off, Math.min(CHUNK, eocd.length - off), all, at);
        MessageDigest top = MessageDigest.getInstance("SHA-256");
        top.update((byte) 0x5a);
        top.update(le32((int) chunks));
        top.update(all, 0, at);
        return top.digest();
    }

    private static long chunkCount(long bytes) {
        return (bytes + CHUNK - 1) / CHUNK;
    }

    private static int hashChunk(MessageDigest md, byte[] src, int off, int len, byte[] dst, int at) throws Exception {
        md.reset();
        md.update((byte) 0xa5);
        md.update(le32(len));
        md.update(src, off, len);
        md.digest(dst, at, 32);
        return at + 32;
    }

    // ---- little helpers for the length-prefixed format ----

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    private static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    /** uint32 length, then the bytes. */
    private static byte[] lp(byte[] b) {
        return concat(le32(b.length), b);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    /** Whether a public key and a certificate belong together (guards against a mismatched import). */
    public static boolean matches(PublicKey pub, X509Certificate cert) {
        return Arrays.equals(pub.getEncoded(), cert.getPublicKey().getEncoded());
    }
}
