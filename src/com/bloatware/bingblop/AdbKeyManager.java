package com.bloatware.bingblop;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;

/**
 * Generates and inspects the ADB client key pair used by the bundled adb binary.
 *
 * adbkey      PKCS#8 PEM private key ("-----BEGIN PRIVATE KEY-----")
 * adbkey.pub  base64 of adb's RSAPublicKey struct followed by " name"
 *
 * The struct (system/core/libcrypto_utils/android_pubkey.cpp) is little-endian:
 *   uint32 modulus_size_words (64), uint32 n0inv (-1/n mod 2^32),
 *   uint8 modulus[256], uint8 rr[256] (R^2 mod n, R = 2^2048), uint32 exponent
 */
public final class AdbKeyManager {

    private static final int KEY_BITS = 2048;
    private static final int MODULUS_BYTES = KEY_BITS / 8;

    private AdbKeyManager() {}

    /** Creates a new key pair, overwriting the files. Returns the public key line written to adbkey.pub. */
    public static String generate(File privateFile, File publicFile, String name) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
        KeyPair kp = kpg.generateKeyPair();

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.DEFAULT)
                + "-----END PRIVATE KEY-----\n";
        writeFile(privateFile, pem.getBytes(StandardCharsets.US_ASCII));

        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kp.getPrivate();
        String pubLine;
        try {
            pubLine = Base64.encodeToString(encodePublicKey(priv.getModulus(), priv.getPublicExponent()), Base64.NO_WRAP)
                    + " " + name + "\n";
            writeFile(publicFile, pubLine.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            // A new private key must never be left on disk paired with a stale public key: that combination
            // reads as "already has a private per-install key" to the caller and would never be regenerated.
            privateFile.delete();
            throw e;
        }

        // Owner-only access; the adb client runs as this app's uid
        privateFile.setReadable(false, false);
        privateFile.setReadable(true, true);
        privateFile.setWritable(false, false);
        privateFile.setWritable(true, true);
        return pubLine.trim();
    }

    /** adb's binary RSAPublicKey encoding. */
    static byte[] encodePublicKey(BigInteger n, BigInteger e) {
        // toLittleEndian below silently keeps only the low MODULUS_BYTES bytes of whatever it's given; for a
        // modulus bigger than that this struct's own fixed-size fields can't hold it, so a mismatched public
        // key would be produced (and signed-off-by-the-struct) rather than an obvious error.
        if (n.bitLength() > KEY_BITS) throw new IllegalArgumentException("modulus is larger than " + KEY_BITS + " bits");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS).pow(2).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(MODULUS_BYTES / 4);
        bb.putInt(n0inv.intValue());
        bb.put(toLittleEndian(n));
        bb.put(toLittleEndian(rr));
        bb.putInt(e.intValue());
        return bb.array();
    }

    private static byte[] toLittleEndian(BigInteger v) {
        byte[] be = v.toByteArray(); // big-endian, may carry a leading sign byte
        byte[] le = new byte[MODULUS_BYTES];
        for (int i = 0; i < MODULUS_BYTES && i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    /** Re-derives adbkey.pub from the private key (used when only the private key exists). */
    public static String publicLineFromPrivate(File privateFile, String name) throws Exception {
        String pem = new String(readFile(privateFile), StandardCharsets.US_ASCII)
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)));
        return Base64.encodeToString(encodePublicKey(priv.getModulus(), priv.getPublicExponent()), Base64.NO_WRAP) + " " + name;
    }

    /**
     * MD5 fingerprint of the public key blob, formatted AA:BB:... This is what Android shows in the
     * "Allow USB debugging?" dialog, so the user can confirm the prompt belongs to this app.
     */
    public static String fingerprint(File publicFile) {
        try {
            String line = new String(readFile(publicFile), StandardCharsets.US_ASCII).trim();
            String b64 = line.split("\\s+")[0];
            byte[] digest = MessageDigest.getInstance("MD5").digest(Base64.decode(b64, Base64.DEFAULT));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static byte[] readFile(File f) throws Exception {
        long length = f.length();
        if (length > Integer.MAX_VALUE) throw new IllegalStateException("file too large to read into memory: " + f);
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] data = new byte[(int) length];
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
            return data;
        } finally {
            in.close();
        }
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IllegalStateException("Could not write " + f);
        }
    }
}
