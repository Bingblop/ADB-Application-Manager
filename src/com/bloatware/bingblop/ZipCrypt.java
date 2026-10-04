package com.bloatware.bingblop;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password protection of zip entries, at the level of the raw (stored) entry data: what sits between the local header and the next header.
 * Pure Java (no Android classes), Java 8 API only (Android API 26: javax.crypto AES and HmacSHA1).
 *
 * Two schemes:
 *  - Traditional PKWARE "ZipCrypto" (general purpose flag bit 0, compression method as stored): a 12 byte header whose last byte is a check byte,
 *    then the data. The check byte is the high byte of the CRC-32, or, when flag bit 3 (data descriptor) is set, the high byte of the DOS time
 *    ({@link #checkByteFromCrc}, {@link #checkByteFromDosTime}). It is a weak scheme and the check byte accepts a wrong password 1 time in 256,
 *    so a caller must also verify the CRC-32 of the decompressed data.
 *  - WinZip AES (compression method 99, extra field 0x9901, AE-1 / AE-2, 128 / 192 / 256 bit): salt (8 / 12 / 16 bytes), 2 byte password
 *    verifier, the ciphertext (AES-CTR, little endian counter starting at 1, same length as the compressed data), a 10 byte HMAC-SHA1 of the
 *    ciphertext. Keys come from PBKDF2-HMAC-SHA1 with 1000 iterations over salt and password.
 *
 * Passwords are taken as char[] and encoded as UTF-8 (what WinZip, 7-Zip, Info-ZIP in a UTF-8 locale and pyzipper do), for both schemes. They are
 * never turned into Strings; the encoded bytes and the derived keys are wiped when a stream is finished or closed. The caller's char[] is not
 * modified (wipe it yourself). The JCA objects (Mac, Cipher) keep their own copies of the keys, which this class cannot wipe.
 *
 * All streams work on the fly, a few KB at a time; nothing buffers a whole entry.
 */
public final class ZipCrypt {
  private ZipCrypt() {}

  /** The password does not match (the traditional check byte or the AES password verifier differs). */
  public static class WrongPassword extends IOException {
    private static final long serialVersionUID = 1L;
    public WrongPassword() { super("wrong password"); }
  }

  /** Compression method number of a WinZip AES entry; the real method is in the 0x9901 extra field. */
  public static final int METHOD_AES = 99;
  /** Header id of the WinZip AES extra field. */
  public static final int EXTRA_AES = 0x9901;

  private static final int CHUNK = 4096;                          // bytes of keystream / data handled at once (multiple of 16)
  private static final int MAC_LEN = 10;
  private static final int PBKDF2_ITER = 1000;
  private static final SecureRandom RANDOM = new SecureRandom();

  // ------------------------------------------------------------------------------------------------------------ password

  /** UTF-8 bytes of the password (lone surrogates become '?', as Java's encoder does). The result is the caller's to wipe. */
  static byte[] passwordBytes(char[] pw) {
    byte[] tmp = new byte[pw.length * 3];
    int k = 0;
    for (int i = 0; i < pw.length; i++) {
      char c = pw[i];
      if (c < 0x80) tmp[k++] = (byte) c;
      else if (c < 0x800) { tmp[k++] = (byte) (0xC0 | (c >> 6)); tmp[k++] = (byte) (0x80 | (c & 0x3F)); }
      else if (Character.isHighSurrogate(c) && i + 1 < pw.length && Character.isLowSurrogate(pw[i + 1])) {
        int cp = Character.toCodePoint(c, pw[++i]);
        tmp[k++] = (byte) (0xF0 | (cp >> 18)); tmp[k++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
        tmp[k++] = (byte) (0x80 | ((cp >> 6) & 0x3F)); tmp[k++] = (byte) (0x80 | (cp & 0x3F));
      } else if (Character.isSurrogate(c)) tmp[k++] = (byte) '?';
      else { tmp[k++] = (byte) (0xE0 | (c >> 12)); tmp[k++] = (byte) (0x80 | ((c >> 6) & 0x3F)); tmp[k++] = (byte) (0x80 | (c & 0x3F)); }
    }
    byte[] r = new byte[k];
    System.arraycopy(tmp, 0, r, 0, k);
    java.util.Arrays.fill(tmp, (byte) 0);
    return r;
  }

  private static void wipe(byte[] a) { if (a != null) java.util.Arrays.fill(a, (byte) 0); }

  // ------------------------------------------------------------------------------------------------------------ check bytes

  /** The traditional check byte of an entry whose CRC-32 is known (flag bit 3 clear): the high byte of the CRC. */
  public static int checkByteFromCrc(long crc32) { return (int) ((crc32 >>> 24) & 0xFF); }

  /** The traditional check byte of an entry written with a data descriptor (flag bit 3 set): the high byte of the 16 bit DOS time. */
  public static int checkByteFromDosTime(int dosTime) { return (dosTime >>> 8) & 0xFF; }

  // ------------------------------------------------------------------------------------------------------------ traditional

  private static final int[] CRC = new int[256];
  static {
    for (int i = 0; i < 256; i++) { int c = i; for (int j = 0; j < 8; j++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1; CRC[i] = c; }
  }

  /** The three 32 bit keys of the traditional cipher. */
  private static final class Keys {
    int k0 = 0x12345678, k1 = 0x23456789, k2 = 0x34567890;
    Keys(byte[] pw) { for (int i = 0; i < pw.length; i++) update(pw[i] & 0xFF); }
    void update(int c) {
      k0 = CRC[(k0 ^ c) & 0xFF] ^ (k0 >>> 8);
      k1 = (k1 + (k0 & 0xFF)) * 134775813 + 1;
      k2 = CRC[(k2 ^ (k1 >>> 24)) & 0xFF] ^ (k2 >>> 8);
    }
    int next() { int t = (k2 | 2) & 0xFFFF; return ((t * (t ^ 1)) >>> 8) & 0xFF; }
    void wipe() { k0 = k1 = k2 = 0; }
  }

  private static Keys newKeys(char[] pw) {
    byte[] b = passwordBytes(pw);
    try { return new Keys(b); } finally { wipe(b); }
  }

  /**
   * Decrypts the raw data of a traditionally encrypted entry (12 byte header, then the data, up to the end of rawData; the compression, if any, is
   * still on it). Reads the header at once and throws {@link WrongPassword} when its last byte differs from checkByte (use -1 to skip the check).
   * Closing the stream closes rawData.
   */
  public static InputStream decryptTraditional(InputStream rawData, char[] pw, int checkByte) throws IOException {
    final Keys keys = newKeys(pw);
    int last = 0;
    try {
      for (int i = 0; i < 12; i++) {
        int c = rawData.read();
        if (c < 0) throw new EOFException("encrypted data is shorter than its 12 byte header");
        last = (c ^ keys.next()) & 0xFF;
        keys.update(last);
      }
    } catch (IOException e) { keys.wipe(); throw e; }
    if (checkByte >= 0 && last != (checkByte & 0xFF)) { keys.wipe(); throw new WrongPassword(); }
    return new TradIn(rawData, keys);
  }

  private static final class TradIn extends InputStream {
    private final InputStream in; private final Keys keys; private boolean closed;
    TradIn(InputStream in, Keys keys) { this.in = in; this.keys = keys; }
    @Override public int read() throws IOException {
      int c = in.read();
      if (c < 0) return -1;
      int p = (c ^ keys.next()) & 0xFF;
      keys.update(p);
      return p;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) return 0;
      int n = in.read(b, off, len);
      if (n <= 0) return n;
      final Keys k = keys;
      for (int i = off, e = off + n; i < e; i++) {
        int p = (b[i] ^ k.next()) & 0xFF;
        k.update(p);
        b[i] = (byte) p;
      }
      return n;
    }
    @Override public void close() throws IOException { if (!closed) { closed = true; keys.wipe(); in.close(); } }
  }

  /**
   * An output stream that encrypts what is written with the traditional cipher: it writes the 12 byte header (11 random bytes and checkByte) first,
   * then the data. Use {@link #checkByteFromCrc} when the CRC-32 of the data is known beforehand, {@link #checkByteFromDosTime} when it is not (the
   * entry then needs flag bit 3 and a data descriptor). {@link TraditionalOutputStream#finish()} ends it without closing out; close() also closes out.
   */
  public static TraditionalOutputStream encryptTraditional(OutputStream out, char[] pw, int checkByte) throws IOException {
    return new TraditionalOutputStream(out, newKeys(pw), checkByte);
  }

  /** Encrypts a whole entry data: 12 byte header (check byte from the CRC-32 given) followed by the data. Returns data.length + 12 bytes. */
  public static byte[] encryptTraditional(byte[] data, char[] pw, long crc32) {
    Keys keys = newKeys(pw);
    byte[] r = new byte[12 + data.length];
    byte[] head = new byte[12];
    RANDOM.nextBytes(head);
    head[11] = (byte) checkByteFromCrc(crc32);
    for (int i = 0; i < 12; i++) { int p = head[i] & 0xFF; r[i] = (byte) (p ^ keys.next()); keys.update(p); }
    for (int i = 0; i < data.length; i++) { int p = data[i] & 0xFF; r[12 + i] = (byte) (p ^ keys.next()); keys.update(p); }
    keys.wipe();
    return r;
  }

  public static final class TraditionalOutputStream extends OutputStream {
    private final OutputStream out; private Keys keys; private final byte[] buf = new byte[CHUNK];
    TraditionalOutputStream(OutputStream out, Keys keys, int checkByte) throws IOException {
      this.out = out; this.keys = keys;
      byte[] head = new byte[12];
      RANDOM.nextBytes(head);
      head[11] = (byte) checkByte;
      try { put(head, 0, 12); } catch (IOException e) { keys.wipe(); this.keys = null; throw e; }
    }
    private void put(byte[] b, int off, int len) throws IOException {      // encrypts b[off, off+len) into buf (len <= CHUNK) and writes it
      final Keys k = keys;
      for (int i = 0; i < len; i++) { int p = b[off + i] & 0xFF; buf[i] = (byte) (p ^ k.next()); k.update(p); }
      out.write(buf, 0, len);
    }
    @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }
    @Override public void write(byte[] b, int off, int len) throws IOException {
      if (keys == null) throw new IOException("stream finished");
      if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
      while (len > 0) { int n = Math.min(len, CHUNK); put(b, off, n); off += n; len -= n; }
    }
    @Override public void flush() throws IOException { out.flush(); }
    /** Ends the encryption (wipes the keys) and flushes, without closing the underlying stream. */
    public void finish() throws IOException { if (keys != null) { keys.wipe(); keys = null; out.flush(); } }
    @Override public void close() throws IOException { try { finish(); } finally { out.close(); } }
  }

  // ------------------------------------------------------------------------------------------------------------ AES

  private static void checkStrength(int s) { if (s < 1 || s > 3) throw new IllegalArgumentException("AES strength must be 1, 2 or 3 (128, 192, 256 bit)"); }
  /** Salt length for strength 1, 2, 3: 8, 12, 16. */
  public static int aesSaltLength(int strength) { checkStrength(strength); return 4 + 4 * strength; }
  /** Key length in bytes for strength 1, 2, 3: 16, 24, 32. */
  public static int aesKeyLength(int strength) { checkStrength(strength); return 8 + 8 * strength; }
  /** What AES adds to the compressed data: salt + 2 byte password verifier + 10 byte authentication code (20, 24 or 28 bytes). */
  public static int aesOverhead(int strength) { return aesSaltLength(strength) + 2 + MAC_LEN; }

  /** PBKDF2 with HMAC-SHA1 (RFC 8018) over raw password bytes, written out so that the password is used as bytes whatever the platform's PBE does. */
  private static byte[] pbkdf2(byte[] pw, byte[] salt, int iterations, int dkLen) throws GeneralSecurityException {
    byte[] keyBytes = pw.length == 0 ? new byte[1] : pw;               // HMAC pads a key with zeros, so one zero byte is the empty key
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(keyBytes, "HmacSHA1"));
    byte[] dk = new byte[dkLen];
    byte[] block = new byte[4];
    for (int i = 1, pos = 0; pos < dkLen; i++, pos += 20) {
      block[0] = (byte) (i >>> 24); block[1] = (byte) (i >>> 16); block[2] = (byte) (i >>> 8); block[3] = (byte) i;
      mac.update(salt); mac.update(block);
      byte[] u = mac.doFinal();
      byte[] t = u.clone();
      for (int j = 1; j < iterations; j++) { u = mac.doFinal(u); for (int x = 0; x < t.length; x++) t[x] ^= u[x]; }
      System.arraycopy(t, 0, dk, pos, Math.min(20, dkLen - pos));
      wipe(t); wipe(u);
    }
    return dk;
  }

  /** The AES-CTR keystream of WinZip: the 16 byte counter block is little endian and starts at 1; AES in ECB mode turns counters into keystream. */
  private static final class Ctr {
    private final Cipher cipher;
    private final byte[] counters = new byte[CHUNK], ks = new byte[CHUNK], ctr = new byte[16];
    private int pos, len;                                          // unused keystream is ks[pos, len)
    Ctr(byte[] key) throws GeneralSecurityException {
      cipher = Cipher.getInstance("AES/ECB/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
      ctr[0] = 1;
    }
    private void refill(int need) throws GeneralSecurityException {
      int blocks = (Math.min(need, CHUNK) + 15) / 16;
      for (int b = 0; b < blocks; b++) {
        System.arraycopy(ctr, 0, counters, b * 16, 16);
        for (int j = 0; j < 16; j++) if (++ctr[j] != 0) break;
      }
      len = blocks * 16;
      cipher.doFinal(counters, 0, len, ks, 0);
      pos = 0;
    }
    /** XORs b[off, off+n) with the next n keystream bytes. */
    void xor(byte[] b, int off, int n) throws IOException {
      try {
        while (n > 0) {
          if (pos == len) refill(n);
          int m = Math.min(n, len - pos);
          for (int i = 0; i < m; i++) b[off + i] ^= ks[pos + i];
          pos += m; off += m; n -= m;
        }
      } catch (GeneralSecurityException e) { throw new IOException("AES failed: " + e); }
    }
    void wipe() { ZipCrypt.wipe(counters); ZipCrypt.wipe(ks); ZipCrypt.wipe(ctr); pos = len = 0; }
  }

  /** The three things derived from a password and a salt. */
  private static final class AesKeys {
    final Ctr ctr; final Mac mac; final byte[] verifier = new byte[2];
    AesKeys(char[] pw, byte[] salt, int strength) throws IOException {
      int kl = aesKeyLength(strength);
      byte[] pwb = passwordBytes(pw), dk = null, enc = null, auth = null;
      try {
        dk = pbkdf2(pwb, salt, PBKDF2_ITER, 2 * kl + 2);
        enc = new byte[kl]; auth = new byte[kl];
        System.arraycopy(dk, 0, enc, 0, kl); System.arraycopy(dk, kl, auth, 0, kl);
        verifier[0] = dk[2 * kl]; verifier[1] = dk[2 * kl + 1];
        ctr = new Ctr(enc);
        mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(auth, "HmacSHA1"));
      } catch (GeneralSecurityException e) {
        throw new IOException("AES is not available: " + e);
      } finally { wipe(pwb); wipe(dk); wipe(enc); wipe(auth); }
    }
  }

  private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
    while (len > 0) {
      int n = in.read(b, off, len);
      if (n < 0) throw new EOFException("encrypted data is truncated");
      off += n; len -= n;
    }
  }

  /**
   * Decrypts the raw data of a WinZip AES entry: salt, verifier, ciphertext, 10 byte authentication code; rawLength is the length of all of it (the
   * compressed size in the zip headers). strength is 1, 2 or 3 (128, 192, 256 bit; from {@link #parseAesExtra}). Reads salt and verifier at once and
   * throws {@link WrongPassword} when the verifier does not match. The stream yields the data as it was before encryption (still compressed with the
   * entry's real method). When the last ciphertext byte has been read, the authentication code is read and checked, and a mismatch throws
   * IOException("authentication failed") from that read: read the stream to its end and treat any exception as a damaged or tampered entry. (AE-1
   * entries also carry a real CRC-32 to check; AE-2 entries have CRC 0 and rely on the authentication code alone.) Closing it closes rawData.
   */
  public static InputStream decryptAes(InputStream rawData, long rawLength, char[] pw, int strength) throws IOException {
    int salt = aesSaltLength(strength);
    if (rawLength < aesOverhead(strength)) throw new IOException("AES entry is shorter than its salt, verifier and authentication code");
    byte[] s = new byte[salt], v = new byte[2];
    readFully(rawData, s, 0, salt);
    readFully(rawData, v, 0, 2);
    AesKeys k = new AesKeys(pw, s, strength);
    boolean ok = MessageDigest.isEqual(k.verifier, v);
    if (!ok) { k.ctr.wipe(); throw new WrongPassword(); }
    return new AesIn(rawData, rawLength - aesOverhead(strength), k);
  }

  private static final class AesIn extends InputStream {
    private final InputStream in; private final AesKeys k; private long remaining; private boolean closed, failed;
    private final byte[] one = new byte[1];
    AesIn(InputStream in, long cipherLength, AesKeys k) { this.in = in; this.k = k; this.remaining = cipherLength; }
    @Override public int read() throws IOException { int n = read(one, 0, 1); return n < 0 ? -1 : one[0] & 0xFF; }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
      if (closed) throw new IOException("stream closed");
      if (failed) throw new IOException("authentication failed");
      if (len == 0) return 0;
      if (remaining == 0) return -1;                               // the code was checked when the last ciphertext byte was read
      int want = (int) Math.min(len, remaining);
      int n = in.read(b, off, want);
      if (n < 0) throw new EOFException("encrypted data is truncated");
      k.mac.update(b, off, n);
      k.ctr.xor(b, off, n);
      remaining -= n;
      if (remaining == 0) verify();
      return n;
    }
    private void verify() throws IOException {
      byte[] stored = new byte[MAC_LEN];
      readFully(in, stored, 0, MAC_LEN);
      byte[] calc = k.mac.doFinal();
      byte[] cut = new byte[MAC_LEN];
      System.arraycopy(calc, 0, cut, 0, MAC_LEN);
      boolean ok = MessageDigest.isEqual(cut, stored);
      k.ctr.wipe();
      if (!ok) { failed = true; throw new IOException("authentication failed"); }
    }
    @Override public void close() throws IOException { if (!closed) { closed = true; k.ctr.wipe(); in.close(); } }
  }

  /**
   * An output stream that encrypts with WinZip AES: it writes a random salt and the password verifier first, then the ciphertext of what is written,
   * and the 10 byte authentication code at {@link AesOutputStream#finish()} or close(). Everything written to out is raw entry data of exactly
   * (bytes written to the stream) + {@link #aesOverhead}(strength) bytes. strength is 1, 2 or 3.
   */
  public static AesOutputStream encryptAes(OutputStream out, char[] pw, int strength) throws IOException {
    return new AesOutputStream(out, pw, strength);
  }

  public static final class AesOutputStream extends OutputStream {
    private final OutputStream out; private final AesKeys k; private final byte[] buf = new byte[CHUNK]; private boolean finished;
    AesOutputStream(OutputStream out, char[] pw, int strength) throws IOException {
      this.out = out;
      byte[] salt = new byte[aesSaltLength(strength)];
      RANDOM.nextBytes(salt);
      this.k = new AesKeys(pw, salt, strength);
      out.write(salt);
      out.write(k.verifier);
    }
    @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }
    @Override public void write(byte[] b, int off, int len) throws IOException {
      if (finished) throw new IOException("stream finished");
      if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
      while (len > 0) {
        int n = Math.min(len, CHUNK);
        System.arraycopy(b, off, buf, 0, n);
        k.ctr.xor(buf, 0, n);
        k.mac.update(buf, 0, n);
        out.write(buf, 0, n);
        off += n; len -= n;
      }
    }
    @Override public void flush() throws IOException { out.flush(); }
    /** Writes the authentication code and wipes the keys, without closing the underlying stream. Safe to call twice. */
    public void finish() throws IOException {
      if (finished) return;
      finished = true;
      byte[] m = k.mac.doFinal();
      k.ctr.wipe();
      out.write(m, 0, MAC_LEN);
      out.flush();
    }
    @Override public void close() throws IOException { try { finish(); } finally { out.close(); } }
  }

  // ------------------------------------------------------------------------------------------------------------ the 0x9901 extra field

  /**
   * The WinZip AES extra field (11 bytes, header and data): 0x9901, size 7, version (1 = AE-1, keeps the CRC-32; 2 = AE-2, CRC-32 field is 0),
   * vendor "AE", strength (1, 2, 3), the real compression method of the data (0 stored, 8 deflated ...).
   */
  public static byte[] aesExtra(int strength, int actualMethod, boolean ae2) {
    checkStrength(strength);
    return new byte[] { (byte) EXTRA_AES, (byte) (EXTRA_AES >>> 8), 7, 0, (byte) (ae2 ? 2 : 1), 0, 'A', 'E', (byte) strength,
        (byte) actualMethod, (byte) (actualMethod >>> 8) };
  }

  /** Finds the 0x9901 field in an extra block and returns {version, strength, actualMethod}; null when there is none, it is too short or its vendor is not "AE" or its strength is not 1, 2 or 3. */
  public static int[] parseAesExtra(byte[] extra) {
    if (extra == null) return null;
    int p = 0;
    while (p + 4 <= extra.length) {
      int id = (extra[p] & 0xFF) | (extra[p + 1] & 0xFF) << 8, size = (extra[p + 2] & 0xFF) | (extra[p + 3] & 0xFF) << 8;
      int d = p + 4;
      if (d + size > extra.length) return null;                    // damaged block
      if (id == EXTRA_AES) {
        if (size < 7 || extra[d + 2] != 'A' || extra[d + 3] != 'E') return null;
        int version = (extra[d] & 0xFF) | (extra[d + 1] & 0xFF) << 8, strength = extra[d + 4] & 0xFF;
        if (strength < 1 || strength > 3) return null;
        int method = (extra[d + 5] & 0xFF) | (extra[d + 6] & 0xFF) << 8;
        return new int[] { version, strength, method };
      }
      p = d + size;
    }
    return null;
  }
}
