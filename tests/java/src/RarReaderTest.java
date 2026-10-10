package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * RarReader: RAR 1.5 - 4.x (through junrar) and RAR 5 / 7 (own parser, unpacker, filters, AES).
 *
 * No rar program and no real archive was available when this was written, so most archives are made here, by writers for the RAR 5 container, for
 * an LZ bit stream encoder (literals, matches, repeats, filters, several blocks, solid, window wrap) and for RAR 3 / 4 stored entries (also encrypted,
 * data and -hp headers). The password key derivation of RAR5 is compared with the JDK's PBKDF2, BLAKE2sp with values from Python's hashlib. Real
 * archives dropped into tests/java/fixtures/rar/ (see the README there) are run through the same checks: list, walk, open, the checksums the
 * archive carries, and the .exp files of the rarfile project when present.
 */
public class RarReaderTest {
  static int fails = 0, n = 0, skipped = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void skip(String what) { skipped++; System.out.println("SKIP " + what); }

  // ------------------------------------------------------------------------------------------------------------ plain helpers

  static byte[] cat(byte[]... parts) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    for (byte[] p : parts) if (p != null) o.write(p, 0, p.length);
    return o.toByteArray();
  }
  static byte[] vint(long v) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    do { int b = (int) (v & 0x7f); v >>>= 7; if (v != 0) b |= 0x80; o.write(b); } while (v != 0);
    return o.toByteArray();
  }
  static byte[] le32(long v) { return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }
  static byte[] le16(int v) { return new byte[]{(byte) v, (byte) (v >> 8)}; }
  static byte[] le64(long v) { byte[] b = new byte[8]; for (int i = 0; i < 8; i++) b[i] = (byte) (v >> (8 * i)); return b; }
  static long crc(byte[] b) { CRC32 c = new CRC32(); c.update(b, 0, b.length); return c.getValue(); }
  static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int k;
    while ((k = in.read(b)) >= 0) o.write(b, 0, k);
    return o.toByteArray();
  }
  static byte[] pad16(byte[] d) { return Arrays.copyOf(d, (d.length + 15) & ~15); }
  static byte[] aes(boolean enc, byte[] key, byte[] iv, byte[] data) throws Exception {
    Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
    c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    return c.doFinal(data);
  }
  static byte[] sha256(byte[] d) throws Exception { return MessageDigest.getInstance("SHA-256").digest(d); }
  static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x & 0xff)); return s.toString(); }
  static byte[] rnd(Random r, int n) { byte[] b = new byte[n]; r.nextBytes(b); return b; }
  static void write(File f, byte[] d) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), d); }
  static void rm(File f) { if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File x : k) rm(x); } f.delete(); }
  static File tmp;

  /** What the listing and the reading must give for one entry. */
  static final class Exp {
    String name; byte[] data; boolean dir; String link;
    Exp(String name, byte[] data) { this.name = name; this.data = data; }
  }

  static boolean sameAs(File f, char[] pw, List<Exp> exp, String what) {
    try {
      RarReader.Info info = RarReader.list(f, pw);
      boolean ok = info.items.size() == exp.size();
      if (!ok) { System.out.println("   " + what + ": " + info.items.size() + " items, expected " + exp.size()); return false; }
      for (int i = 0; i < exp.size(); i++) {
        RarReader.Item it = info.items.get(i);
        Exp e = exp.get(i);
        if (!it.name.equals(e.name) || it.dir != e.dir) { System.out.println("   " + what + ": item " + i + " " + it.name + " expected " + e.name); return false; }
        if (!e.dir && e.link == null && it.size != e.data.length) { System.out.println("   " + what + ": size of " + it.name + " " + it.size + " expected " + e.data.length); return false; }
        if (e.link != null && !e.link.equals(it.linkTarget)) { System.out.println("   " + what + ": link " + it.linkTarget + " expected " + e.link); return false; }
      }
      final List<byte[]> got = new ArrayList<byte[]>();
      RarReader.walk(f, pw, new RarReader.Visitor() {
        public boolean entry(RarReader.Item it, InputStream data) throws IOException { got.add(readAll(data)); return true; }
      });
      if (got.size() != exp.size()) { System.out.println("   " + what + ": walk gave " + got.size()); return false; }
      for (int i = 0; i < exp.size(); i++) {
        byte[] want = exp.get(i).dir || exp.get(i).link != null ? new byte[0] : exp.get(i).data;
        if (!Arrays.equals(got.get(i), want)) { System.out.println("   " + what + ": walk data of " + exp.get(i).name + " differs (" + got.get(i).length + " vs " + want.length + ")"); return false; }
      }
      // open one by one (the last first: a solid archive must decode what is before it)
      for (int i = exp.size() - 1; i >= 0; i--) {
        Exp e = exp.get(i);
        if (e.dir || e.link != null) continue;
        boolean dup = false;
        for (int j = 0; j < i; j++) if (exp.get(j).name.equals(e.name)) dup = true;
        if (dup) continue;                                             // open() gives the first of equal names
        InputStream in = RarReader.open(f, e.name, pw);
        byte[] d;
        try { d = readAll(in); } finally { in.close(); }
        if (!Arrays.equals(d, e.data)) { System.out.println("   " + what + ": open data of " + e.name + " differs"); return false; }
      }
      return true;
    } catch (Throwable t) {
      System.out.println("   " + what + ": " + t);
      t.printStackTrace(System.out);
      return false;
    }
  }

  static String errOf(File f, char[] pw, boolean readData) {
    try {
      RarReader.list(f, pw);
      if (readData) {
        RarReader.walk(f, pw, new RarReader.Visitor() {
          public boolean entry(RarReader.Item it, InputStream data) throws IOException { readAll(data); return true; }
        });
      }
      return null;
    } catch (RarReader.PasswordException e) {
      return e.wrong ? "pw-wrong" : "pw-needed";
    } catch (IOException e) {
      return e.getMessage();
    } catch (Throwable t) {
      return "THROWN " + t;
    }
  }

  // ------------------------------------------------------------------------------------------------------------ RAR5 key derivation (JDK PBKDF2)

  static byte[] pbk(char[] pw, byte[] salt, int iters) throws Exception {
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw, salt, iters, 256)).getEncoded();
  }
  /** {aes key, mac key, 8 byte check value} */
  static byte[][] kdf5(char[] pw, byte[] salt, int lg2) throws Exception {
    int c = 1 << lg2;
    byte[] v2 = pbk(pw, salt, c + 32);
    byte[] chk = new byte[8];
    for (int i = 0; i < 32; i++) chk[i & 7] ^= v2[i];
    return new byte[][]{pbk(pw, salt, c), pbk(pw, salt, c + 16), chk};
  }
  static long macCrc(byte[] hashKey, long crc) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(hashKey, "HmacSHA256"));
    byte[] d = m.doFinal(le32(crc));
    long v = 0;
    for (int i = 0; i < 32; i++) v ^= (long) (d[i] & 0xff) << ((i & 3) * 8);
    return v & 0xffffffffL;
  }

  // ------------------------------------------------------------------------------------------------------------ the LZ bit stream encoder

  static final class BitW {
    final ByteArrayOutputStream o = new ByteArrayOutputStream();
    int cur, nb;
    long bits;
    void put(int value, int n) {
      for (int i = n - 1; i >= 0; i--) {
        cur = (cur << 1) | ((value >> i) & 1);
        nb++; bits++;
        if (nb == 8) { o.write(cur); cur = 0; nb = 0; }
      }
    }
    byte[] finish() { if (nb > 0) { o.write(cur << (8 - nb)); cur = 0; nb = 0; } return o.toByteArray(); }
  }

  /** Huffman code lengths (at most {@code limit}) for the frequencies; symbols with frequency 0 get no code. */
  static int[] huffLens(int[] freq, int limit) {
    int n = freq.length;
    int[] f = freq.clone();
    while (true) {
      int live = 0, only = -1;
      for (int i = 0; i < n; i++) if (f[i] > 0) { live++; only = i; }
      int[] lens = new int[n];
      if (live == 0) return lens;
      if (live == 1) { lens[only] = 1; return lens; }
      java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<long[]>(16, new java.util.Comparator<long[]>() {
        public int compare(long[] x, long[] y) { return x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]); }
      });
      int[] parent = new int[2 * n];
      Arrays.fill(parent, -1);
      for (int i = 0; i < n; i++) if (f[i] > 0) pq.add(new long[]{f[i], i});
      int next = n;
      while (pq.size() > 1) {
        long[] x = pq.poll(), y = pq.poll();
        parent[(int) x[1]] = next; parent[(int) y[1]] = next;
        pq.add(new long[]{x[0] + y[0], next++});
      }
      int max = 0;
      for (int i = 0; i < n; i++) if (f[i] > 0) { int d = 0, k = i; while (parent[k] >= 0) { k = parent[k]; d++; } lens[i] = d; max = Math.max(max, d); }
      if (max <= limit) return lens;
      for (int i = 0; i < n; i++) if (f[i] > 0) f[i] = (f[i] + 1) / 2;
    }
  }
  /** DEFLATE style canonical codes (the shorter first, then by symbol). */
  static int[] canon(int[] lens) {
    int[] count = new int[17];
    for (int l : lens) if (l > 0) count[l]++;
    int[] next = new int[17];
    int code = 0;
    for (int b = 1; b <= 15; b++) { code = (code + count[b - 1]) << 1; next[b] = code; }
    int[] codes = new int[lens.length];
    for (int i = 0; i < lens.length; i++) if (lens[i] > 0) codes[i] = next[lens[i]]++;
    return codes;
  }

  /**
   * Encodes streams the way the decoder expects them and tracks what they decode to. Two modes: fixed codes (LD 9 bits, DD 6, LDD 4, RD 6; the
   * decoder's fast path only), or Huffman codes made from the symbols of each block (lengths 1..15, run length coded tables, tables kept from the
   * block before when a block has none).
   */
  static final class Lz5 {
    byte[] sim = new byte[1 << 16];
    int simLen, fileStart;
    final int[] old = new int[4];
    int lastLen;
    boolean tablesSent;
    final List<long[]> filters = new ArrayList<long[]>();
    ByteArrayOutputStream packed = new ByteArrayOutputStream();
    BitW bw;
    boolean blockTables;
    final boolean variable;
    List<int[]> tokens;
    int[] curLens;          // LD 306 + DD 64 + LDD 16 + RD 44
    int maxLenSeen;
    Lz5() { this(false); }
    Lz5(boolean variable) { this.variable = variable; }

    void beginFile(boolean solid) {
      if (!solid) { simLen = 0; Arrays.fill(old, 0); lastLen = 0; tablesSent = false; curLens = null; }
      fileStart = simLen;
      filters.clear();
      packed = new ByteArrayOutputStream();
      bw = null;
    }

    void block(boolean tables) {
      bw = new BitW();
      blockTables = tables;
      tokens = new ArrayList<int[]>();
      if (tables && !variable) {
        for (int i = 0; i < 20; i++) bw.put(i == 4 || i == 6 || i == 9 ? 2 : 0, 4);   // code lengths of the length codes: 4, 6 and 9 get 2 bits
        for (int i = 0; i < 306; i++) bw.put(2, 2);                                     // 9
        for (int i = 0; i < 64; i++) bw.put(1, 2);                                      // 6
        for (int i = 0; i < 16; i++) bw.put(0, 2);                                      // 4
        for (int i = 0; i < 44; i++) bw.put(1, 2);                                      // 6
      }
      if (tables) tablesSent = true;
    }

    // symbol writers: kind 0 LD, 1 DD, 2 LDD, 3 RD, 4 raw bits
    private void sym(int kind, int v) {
      if (variable) { tokens.add(new int[]{kind, v, 0}); return; }
      bw.put(v, kind == 0 ? 9 : kind == 1 ? 6 : kind == 2 ? 4 : 6);
    }
    private void raw(int v, int n) {
      if (n == 0) return;
      if (variable) tokens.add(new int[]{4, v, n}); else bw.put(v, n);
    }

    private void writeVarTables(BitW o, int[] lens) {
      // the run length coding of the 430 code lengths: symbols 0..15 a length, 16 repeat the last 3..10, 17 repeat the last 11..138, 18 zeros 3..10, 19 zeros 11..138
      List<int[]> sy = new ArrayList<int[]>();   // {symbol, extra, extraBits}
      int i = 0;
      while (i < lens.length) {
        int v = lens[i], run = 1;
        while (i + run < lens.length && lens[i + run] == v) run++;
        if (v == 0 && run >= 3) {
          int k = Math.min(run, 138);
          if (k >= 11) sy.add(new int[]{19, k - 11, 7}); else sy.add(new int[]{18, k - 3, 3});
          i += k;
        } else if (v != 0 && run >= 4 && i > 0 && lens[i - 1] == v) {          // the value before is the same: repeat it
          int k = Math.min(run, 138);
          if (k >= 11) sy.add(new int[]{17, k - 11, 7}); else sy.add(new int[]{16, k - 3, 3});
          i += k;
        } else {
          sy.add(new int[]{v, 0, 0});
          i++;
        }
      }
      int[] bf = new int[20];
      for (int[] t : sy) bf[t[0]]++;
      int[] bl = huffLens(bf, 15);
      int[] bcodes = canon(bl);
      // the 20 lengths as nibbles; a run of zeros may use the escape (15, count - 2)
      int k = 0;
      boolean useEscape = (curLens == null ? 0 : 1) == 0 || true;
      while (k < 20) {
        int z = 0;
        while (k + z < 20 && bl[k + z] == 0) z++;
        if (z >= 3 && z <= 17 && useEscape) { o.put(15, 4); o.put(z - 2, 4); k += z; }
        else if (bl[k] == 15) { o.put(15, 4); o.put(0, 4); k++; }
        else { o.put(bl[k], 4); k++; }
      }
      for (int[] t : sy) {
        o.put(bcodes[t[0]], bl[t[0]]);
        if (t[2] > 0) o.put(t[1], t[2]);
      }
    }

    void endBlock(boolean last) {
      if (variable) {
        int[] ld = new int[306], dd = new int[64], ldd = new int[16], rd = new int[44];
        if (blockTables) {
          Arrays.fill(ld, 1); Arrays.fill(dd, 1); Arrays.fill(ldd, 1); Arrays.fill(rd, 1);      // every symbol gets a code (later blocks may reuse the tables)
          for (int[] t : tokens) { if (t[0] == 0) ld[t[1]] += 20; else if (t[0] == 1) dd[t[1]] += 20; else if (t[0] == 2) ldd[t[1]] += 20; else if (t[0] == 3) rd[t[1]] += 20; }
          // some skew so that code lengths differ a lot
          for (int s = 0; s < 40; s++) ld[s] += 3000 >> (s / 4);
          int[] all = new int[430];
          System.arraycopy(huffLens(ld, 15), 0, all, 0, 306);
          System.arraycopy(huffLens(dd, 15), 0, all, 306, 64);
          System.arraycopy(huffLens(ldd, 15), 0, all, 370, 16);
          System.arraycopy(huffLens(rd, 15), 0, all, 386, 44);
          curLens = all;
          writeVarTables(bw, all);
          int mx = 0; for (int l : all) mx = Math.max(mx, l);
          maxLenSeen = Math.max(maxLenSeen, mx);
        }
        if (curLens == null) throw new IllegalStateException("no tables");
        int[] cl = Arrays.copyOfRange(curLens, 0, 306), cd = Arrays.copyOfRange(curLens, 306, 370), cx = Arrays.copyOfRange(curLens, 370, 386), cr = Arrays.copyOfRange(curLens, 386, 430);
        int[] kl = canon(cl), kd = canon(cd), kx = canon(cx), kr = canon(cr);
        for (int[] t : tokens) {
          switch (t[0]) {
            case 0: if (cl[t[1]] == 0) throw new IllegalStateException("no LD code"); bw.put(kl[t[1]], cl[t[1]]); break;
            case 1: if (cd[t[1]] == 0) throw new IllegalStateException("no DD code"); bw.put(kd[t[1]], cd[t[1]]); break;
            case 2: if (cx[t[1]] == 0) throw new IllegalStateException("no LDD code"); bw.put(kx[t[1]], cx[t[1]]); break;
            case 3: if (cr[t[1]] == 0) throw new IllegalStateException("no RD code"); bw.put(kr[t[1]], cr[t[1]]); break;
            default: bw.put(t[1], t[2]);
          }
        }
      }
      byte[] body = bw.finish();
      int size = body.length;
      int lastBits = (int) (bw.bits % 8 == 0 ? 8 : bw.bits % 8);
      int bc = size < 256 ? 1 : size < 65536 ? 2 : 3;
      int flags = (lastBits - 1) | ((bc - 1) << 3) | (last ? 0x40 : 0) | (blockTables ? 0x80 : 0);
      int chk = (0x5a ^ flags ^ size ^ (size >> 8) ^ (size >> 16)) & 0xff;
      packed.write(flags);
      packed.write(chk);
      for (int i = 0; i < bc; i++) packed.write(size >> (8 * i));
      packed.write(body, 0, body.length);
      bw = null;
    }

    private void sim(int b) {
      if (simLen == sim.length) sim = Arrays.copyOf(sim, sim.length * 2);
      sim[simLen++] = (byte) b;
    }
    private void copy(int len, int dist) { for (int i = 0; i < len; i++) sim(sim[simLen - dist] & 0xff); }

    void lit(int b) { sym(0, b & 0xff); sim(b); }

    private int[] lenCode(int coded) {
      int c = coded - 2;
      if (c < 8) return new int[]{c, 0, 0};
      int lb = 31 - Integer.numberOfLeadingZeros(c) - 2;
      return new int[]{(lb + 1) * 4 + ((c >> lb) & 3), lb, c & ((1 << lb) - 1)};
    }

    void match(int len, int dist) {
      int adj = (dist > 0x100 ? 1 : 0) + (dist > 0x2000 ? 1 : 0) + (dist > 0x40000 ? 1 : 0);
      if (len - adj < 2 || dist < 1 || dist > simLen) throw new IllegalArgumentException("len " + len + " dist " + dist);
      int[] lc = lenCode(len - adj);
      sym(0, 262 + lc[0]);
      raw(lc[2], lc[1]);
      int d = dist - 1, dslot, dbits, extra;
      if (d < 4) { dslot = d; dbits = 0; extra = 0; }
      else { int h = 31 - Integer.numberOfLeadingZeros(d); dbits = h - 1; dslot = 2 * h + ((d >> (h - 1)) & 1); extra = d & ((1 << dbits) - 1); }
      sym(1, dslot);
      if (dbits > 0) {
        if (dbits >= 4) { if (dbits > 4) raw(extra >> 4, dbits - 4); sym(2, extra & 15); }
        else raw(extra, dbits);
      }
      old[3] = old[2]; old[2] = old[1]; old[1] = old[0]; old[0] = dist;
      lastLen = len;
      copy(len, dist);
    }

    void rep(int idx, int len) {
      sym(0, 258 + idx);
      int[] lc = lenCode(len);
      sym(3, lc[0]);
      raw(lc[2], lc[1]);
      int d = old[idx];
      for (int i = idx; i > 0; i--) old[i] = old[i - 1];
      old[0] = d;
      lastLen = len;
      copy(len, d);
    }

    void repLast() { sym(0, 257); if (lastLen != 0) copy(lastLen, old[0]); }

    private void fdata(long v) {
      int nbytes = v < 256 ? 1 : v < 65536 ? 2 : v < (1 << 24) ? 3 : 4;
      raw(nbytes - 1, 2);
      for (int i = 0; i < nbytes; i++) raw((int) ((v >> (8 * i)) & 0xff), 8);
    }

    /** A filter for the {@code len} bytes that start {@code startRel} bytes after the current position. */
    void filter(long startRel, int len, int type, int channels) {
      sym(0, 256);
      fdata(startRel);
      fdata(len);
      raw(type, 3);
      if (type == 0) raw(channels - 1, 5);
      filters.add(new long[]{simLen + startRel, len, type, channels});
    }

    /** Any bits (for hand made streams). */
    void bits(int v, int n) { raw(v, n); }
    void symLD(int s) { sym(0, s); }

    /** What the file must decode to: the window with the filters applied. */
    byte[] expected() {
      byte[] r = Arrays.copyOfRange(sim, fileStart, simLen);
      for (long[] f : filters) {
        int s = (int) (f[0] - fileStart), l = (int) f[1];
        byte[] part = Arrays.copyOfRange(r, s, s + l);
        byte[] res;
        switch ((int) f[2]) {
          case 0: res = fDelta(part, (int) f[3]); break;
          case 1: res = fE8(part, s, false); break;
          case 2: res = fE8(part, s, true); break;
          default: res = fArm(part, s); break;
        }
        System.arraycopy(res, 0, r, s, l);
      }
      return r;
    }
    byte[] packed() { return packed.toByteArray(); }
  }

  static byte[] fDelta(byte[] d, int ch) {
    byte[] o = new byte[d.length];
    int s = 0;
    for (int c = 0; c < ch; c++) { byte prev = 0; for (int p = c; p < d.length; p += ch) { prev -= d[s++]; o[p] = prev; } }
    return o;
  }
  static long u32(byte[] d, int p) { return (d[p] & 0xffL) | (d[p + 1] & 0xffL) << 8 | (d[p + 2] & 0xffL) << 16 | (d[p + 3] & 0xffL) << 24; }
  static void p32(byte[] d, int p, long v) { d[p] = (byte) v; d[p + 1] = (byte) (v >> 8); d[p + 2] = (byte) (v >> 16); d[p + 3] = (byte) (v >> 24); }
  static byte[] fE8(byte[] in, long filePos, boolean e9) {
    byte[] d = in.clone();
    final long FS = 0x1000000L;
    for (int cur = 0; cur + 4 < d.length; ) {
      int c = d[cur++] & 0xff;
      if (c == 0xe8 || (e9 && c == 0xe9)) {
        long off = (cur + filePos) % FS;
        long addr = u32(d, cur);
        if (addr >= 0x80000000L) { if (((addr + off) & 0xffffffffL) < 0x80000000L) p32(d, cur, addr + FS); }
        else if (addr < FS) p32(d, cur, addr - off);
        cur += 4;
      }
    }
    return d;
  }
  static byte[] fArm(byte[] in, long filePos) {
    byte[] d = in.clone();
    for (int cur = 0; cur + 3 < d.length; cur += 4) {
      if ((d[cur + 3] & 0xff) == 0xeb) {
        long off = (d[cur] & 0xff) | (d[cur + 1] & 0xff) << 8 | (d[cur + 2] & 0xff) << 16;
        off -= (filePos + cur) / 4;
        d[cur] = (byte) off; d[cur + 1] = (byte) (off >> 8); d[cur + 2] = (byte) (off >> 16);
      }
    }
    return d;
  }

  /** Random ops: literals, matches (all distance and length ranges), repeats; blocks of random size. */
  static void genOps(Lz5 z, Random r, int total, int maxDist, boolean multiBlock) {
    z.block(!z.tablesSent);
    int opsInBlock = 0;
    while (z.simLen - z.fileStart < total) {
      int k = r.nextInt(100);
      int have = z.simLen;
      if (have < 8 || k < 45) {
        int run = 1 + r.nextInt(12);
        for (int i = 0; i < run; i++) z.lit(r.nextInt(5) == 0 ? r.nextInt(256) : 'a' + r.nextInt(8));
      } else if (k < 85 || z.lastLen == 0) {
        int dist;
        int kind = r.nextInt(6);
        int cap = Math.min(have, maxDist);
        if (kind == 0) dist = 1 + r.nextInt(Math.min(cap, 4));
        else if (kind == 1) dist = 1 + r.nextInt(Math.min(cap, 0x100));
        else if (kind == 2) dist = 1 + r.nextInt(Math.min(cap, 0x2000));
        else if (kind == 3) dist = 1 + r.nextInt(Math.min(cap, 0x40000));
        else dist = 1 + r.nextInt(cap);
        int adj = (dist > 0x100 ? 1 : 0) + (dist > 0x2000 ? 1 : 0) + (dist > 0x40000 ? 1 : 0);
        int len = r.nextInt(8) == 0 ? 2 + adj + r.nextInt(4090) : 2 + adj + r.nextInt(40);
        z.match(len, dist);
      } else if (k < 95) {
        int idx = r.nextInt(4);
        if (z.old[idx] == 0 || z.old[idx] > z.simLen) { z.lit('x'); }
        else z.rep(idx, 2 + r.nextInt(300));
      } else {
        if (z.old[0] != 0 && z.lastLen != 0) z.repLast(); else z.lit('y');
      }
      if (multiBlock && ++opsInBlock > 400 && r.nextInt(50) == 0) {
        z.endBlock(false);
        z.block(r.nextBoolean() || !z.tablesSent);
        opsInBlock = 0;
      }
    }
    z.endBlock(true);
  }

  // ------------------------------------------------------------------------------------------------------------ the RAR5 container writer

  static final byte[] SIG5 = {'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00};

  static final class F5 {
    String name; boolean dir; byte[] packed = new byte[0]; long unp; boolean unknownSize;
    long crc = -1; byte[] blake;
    int method, ver, dictBits = 4; boolean solid;
    long mtime = -1; int host = 1; long attr = 0644;
    ByteArrayOutputStream extra = new ByteArrayOutputStream();
    long blockFlags;
    F5(String name) { this.name = name; }
    F5 stored(byte[] data) { method = 0; packed = data; unp = data.length; crc = RarReaderTest.crc(data); return this; }
    F5 lz(Lz5 z, boolean solid, int dictBits) { method = 3; packed = z.packed(); byte[] e = z.expected(); unp = e.length; crc = RarReaderTest.crc(e); this.solid = solid; this.dictBits = dictBits; return this; }
    F5 rec(int type, byte[]... data) { byte[] body = cat(vint(type), cat(data)); try { extra.write(vint(body.length)); extra.write(body); } catch (IOException e) { throw new RuntimeException(e); } return this; }
    /** Encrypt the packed bytes and add the encryption record. */
    F5 encrypt(char[] pw, int lg2, boolean check, boolean mac, Random r) throws Exception {
      byte[] salt = rnd(r, 16), iv = rnd(r, 16);
      byte[][] k = kdf5(pw, salt, lg2);
      packed = aes(true, k[0], iv, pad16(packed));
      byte[] chk = null;
      if (check) chk = cat(k[2], Arrays.copyOf(sha256(k[2]), 4));
      rec(1, vint(0), vint((check ? 1 : 0) | (mac ? 2 : 0)), new byte[]{(byte) lg2}, salt, iv, chk);
      if (mac && crc >= 0) crc = macCrc(k[1], crc);
      if (mac && blake != null) { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(k[1], "HmacSHA256")); blake = m.doFinal(blake); }
      return this;
    }
  }

  static final class W5 {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] hdrKey;
    final Random r = new Random(77);

    W5() { out.write(SIG5, 0, 8); }

    void block(int type, long flags, byte[] specific, byte[] extra, byte[] data) {
      boolean hasExtra = extra != null && extra.length > 0, hasData = data != null && data.length > 0;
      long fl = flags | (hasExtra ? 1 : 0) | (hasData ? 2 : 0);
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      byte[] a = vint(type), b = vint(fl);
      body.write(a, 0, a.length);
      body.write(b, 0, b.length);
      if (hasExtra) { byte[] x = vint(extra.length); body.write(x, 0, x.length); }
      if (hasData) { byte[] x = vint(data.length); body.write(x, 0, x.length); }
      body.write(specific, 0, specific.length);
      if (hasExtra) body.write(extra, 0, extra.length);
      byte[] bb = body.toByteArray();
      byte[] sizeAndBody = cat(vint(bb.length), bb);
      byte[] plain = cat(le32(crc(sizeAndBody)), sizeAndBody);
      try {
        if (hdrKey == null) out.write(plain);
        else { byte[] iv = rnd(r, 16); out.write(iv); out.write(aes(true, hdrKey, iv, pad16(plain))); }
        if (hasData) out.write(data);
      } catch (Exception e) { throw new RuntimeException(e); }
    }

    void encryptHeaders(char[] pw, int lg2, boolean check) throws Exception {
      byte[] salt = rnd(r, 16);
      byte[][] k = kdf5(pw, salt, lg2);
      byte[] chk = check ? cat(k[2], Arrays.copyOf(sha256(k[2]), 4)) : null;
      block(4, 0, cat(vint(0), vint(check ? 1 : 0), new byte[]{(byte) lg2}, salt, chk), null, null);
      hdrKey = k[0];
    }

    void main(boolean solid) { block(1, 0, cat(vint(solid ? 4 : 0)), null, null); }
    void end() { block(5, 0, vint(0), null, null); }

    void file(F5 f) { file(f, 2); }
    void file(F5 f, int type) {
      long ff = (f.dir ? 1 : 0) | (f.mtime >= 0 ? 2 : 0) | (f.crc >= 0 ? 4 : 0) | (f.unknownSize ? 8 : 0);
      long ci = f.ver | (f.solid ? 0x40 : 0) | ((long) f.method << 7) | ((long) f.dictBits << 10);
      byte[] nm = utf8(f.name);
      byte[] spec = cat(vint(ff), vint(f.unknownSize ? 0 : f.unp), vint(f.attr), f.mtime >= 0 ? le32(f.mtime) : null, f.crc >= 0 ? le32(f.crc) : null,
          vint(ci), vint(f.host), vint(nm.length), nm);
      block(type, f.blockFlags, spec, f.extra.toByteArray(), f.packed);
    }
    byte[] bytes() { return out.toByteArray(); }
  }

  // ------------------------------------------------------------------------------------------------------------ the RAR 3 / 4 writer (stored entries)

  static byte[] rar3Marker() { return new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x00}; }
  static byte[][] kdf3(char[] pw, byte[] salt) throws Exception {
    MessageDigest sha = MessageDigest.getInstance("SHA-1");
    byte[] raw = cat(new String(pw).getBytes("UTF-16LE"), salt);
    byte[] iv = new byte[16];
    final int rounds = 0x40000;
    for (int i = 0; i < rounds; i++) {
      sha.update(raw);
      sha.update(new byte[]{(byte) i, (byte) (i >> 8), (byte) (i >> 16)});
      if (i % (rounds / 16) == 0) iv[i / (rounds / 16)] = ((MessageDigest) sha.clone()).digest()[19];
    }
    byte[] dg = sha.digest();
    byte[] key = new byte[16];
    for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) key[i * 4 + j] = dg[i * 4 + 3 - j];
    return new byte[][]{key, iv};
  }
  static long dosTime(int y, int mo, int d, int h, int mi, int s) { return ((long) (y - 1980) << 25 | (long) mo << 21 | (long) d << 16 | (long) h << 11 | (long) mi << 5 | (s / 2)); }

  static final class W4 {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final char[] hpw;
    final Random r = new Random(5);
    W4(boolean solid, char[] headerPw) {
      hpw = headerPw;
      out.write(rar3Marker(), 0, 7);
      byte[] body = cat(new byte[]{0x73}, le16((solid ? 8 : 0) | (headerPw != null ? 0x80 : 0)), le16(13), le16(0), le32(0));
      raw(cat(le16((int) (crc(body) & 0xffff)), body));
    }
    void raw(byte[] b) { out.write(b, 0, b.length); }
    /** a block (header bytes with the CRC in front), encrypted when the headers are */
    void block(byte[] header) {
      try {
        if (hpw == null) { raw(header); return; }
        byte[] salt = rnd(r, 8);
        byte[][] k = kdf3(hpw, salt);
        raw(salt);
        raw(aes(true, k[0], k[1], pad16(header)));
      } catch (Exception e) { throw new RuntimeException(e); }
    }
    void file(String name, byte[] data, int host, long attr, long ftime, boolean dir, char[] pw, boolean solid) throws Exception {
      byte[] nm = name.getBytes("ISO-8859-1");
      byte[] packed = data;
      int flags = 0x8000 | (dir ? 0xE0 : 0) | (solid ? 0x10 : 0);
      byte[] salt = null;
      if (pw != null) {
        salt = rnd(r, 8);
        byte[][] k = kdf3(pw, salt);
        packed = aes(true, k[0], k[1], pad16(data));
        flags |= 0x04 | 0x400;
      }
      byte[] body = cat(new byte[]{0x74}, le16(flags), le16(0), le32(packed.length), le32(data.length), new byte[]{(byte) host}, le32(crc(data)), le32(ftime),
          new byte[]{29, 0x30}, le16(nm.length), le32(attr), nm, salt);
      int size = 2 + body.length + 2;   // crc + the rest, with the size field (2) counted in body? see below
      // HEAD_SIZE counts everything of the header: crc(2) + body (which already holds the 2 size bytes as le16(0))
      size = 2 + body.length;
      body[3] = (byte) size;
      body[4] = (byte) (size >> 8);
      byte[] hdr = cat(le16((int) (crc(body) & 0xffff)), body);
      block(hdr);
      raw(packed);
    }
    void end() { if (hpw == null) raw(new byte[]{(byte) 0xC4, 0x3D, 0x7B, 0x00, 0x40, 0x07, 0x00}); }
    byte[] bytes() { return out.toByteArray(); }
  }

  // ------------------------------------------------------------------------------------------------------------ main

  static File arc(String name, byte[] data) throws IOException { File f = new File(tmp, name); write(f, data); return f; }

  public static void main(String[] args) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    tmp = Files.createTempDirectory("rarreader-test").toFile();
    Random r = new Random(20240607);
    try {
      testBlake();
      testKdf();
      testRar5Container(r);
      testRar5Lz(r);
      testRar5Filters(r);
      testRar5Solid(r);
      testRar5Crypto(r);
      testRar5Hashes(r);
      testRar5Limits(r);
      testRar5Damage(r);
      testRar5SolidStartFailure();
      testRar5SolidStartFailureChain();
      testRar4(r);
      testSfxAndMagic(r);
      testFixtures();
    } finally {
      rm(tmp);
    }
    System.out.println(fails == 0 ? "ALL PASS (" + n + " checks" + (skipped > 0 ? ", " + skipped + " skipped" : "") + ")" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  // ------------------------------------------------------------------------------------------------------------ pieces

  static void testBlake() throws Exception {
    String[] want = {"dd0e891776933f43c7d032b08a917e25741f8aa9a12c12e1cac8801500f2ca4f", "a6b9eecc25227ad788c99d3f236debc8da408849e9a5178978727a81457f7239",
        "0e3876e610c455f48bf12d4a369f922eee30524b5e48775e0b09a4739c671a9c", "d2155a9b45debaa37d475e384d97c898aebe8cb52cd80e8368a56766e93a813a",
        "9ba0c37f1ab9fdd4b656b42702049eec67f76c1ef76c3722b7b1e2a019674230", "8180a53baa5bdb2cfd292e8b084ce88dbd6b2b61b60193732e392b11f46ac605",
        "ff3f4303d794d7fbfc6a4fee0d289f1649cc315e2fd5c2fa9180ce913fee1e31", "08a65a68f54f72077155adc2e339db1dec6c860f640cb47584cb2f2d8977a8c1",
        "f405ed3422923e4cd0d141ab4ff130914fff2eb9e7f49588a5c02387e5d343ac", "29ded360f74fde02697a0495c85859aa2c4968cce40fba406ff33bc55c3d246e",
        "7aee5ab115bb0dbb0ae48f2a8a1ce2ab385cbf9c659bb09823bba9328ead79ae"};
    int[] lens = {0, 1, 3, 63, 64, 65, 511, 512, 513, 1000, 4103};
    for (int i = 0; i < lens.length; i++) {
      byte[] d = new byte[lens[i]];
      for (int k = 0; k < d.length; k++) d[k] = (byte) ((k * 7 + k / 251) % 256);
      RarReader.Blake2sp b = new RarReader.Blake2sp();
      // fed in odd pieces
      int p = 0;
      while (p < d.length) { int c = Math.min(d.length - p, 1 + (p * 31 + 17) % 150); b.update(d, p, c); p += c; }
      check("BLAKE2sp of " + lens[i] + " bytes (python hashlib reference)", hex(b.digest()).equals(want[i]));
    }
  }

  static void testKdf() throws Exception {
    byte[] salt = new byte[16];
    for (int i = 0; i < 16; i++) salt[i] = (byte) (i * 11 + 3);
    for (int lg2 : new int[]{0, 1, 5, 10}) {
      char[] pw = "pässwörd 密码".toCharArray();
      byte[][] ref = kdf5(pw, salt, lg2);
      RarReader.Keys k = RarReader.deriveKeys(new String(pw).getBytes(StandardCharsets.UTF_8), salt, lg2);
      check("RAR5 key derivation, count 2^" + lg2 + ": key, MAC key and check value equal the JDK PBKDF2 results", Arrays.equals(k.key, ref[0]) && Arrays.equals(k.hashKey, ref[1]) && Arrays.equals(k.check, ref[2]));
    }
    byte[][] ref = kdf5("x".toCharArray(), salt, 4);
    RarReader.Keys k = RarReader.deriveKeys(new byte[]{'x'}, salt, 4);
    check("MAC of a CRC32 (HMAC-SHA256 folded)", RarReader.macCrc(k.hashKey, 0x12345678L) == macCrc(ref[1], 0x12345678L));
    boolean refused = false;
    try { RarReader.deriveKeys(new byte[]{'x'}, salt, 25); } catch (IOException e) { refused = true; }
    check("a key count above 2^24 is refused (no hour long derivation)", refused);
    // empty password
    RarReader.Keys e0 = RarReader.deriveKeys(new byte[0], salt, 3);
    byte[][] refEmpty = null;
    try { refEmpty = kdf5(new char[0], salt, 3); } catch (Exception ex) { /* the JDK may refuse an empty password */ }
    check("an empty password derives keys" + (refEmpty == null ? " (the JDK refuses to compare)" : " equal to the JDK's"), e0.key.length == 32 && (refEmpty == null || Arrays.equals(refEmpty[0], e0.key)));
  }

  static void testRar5Container(Random r) throws Exception {
    // stored files, folders, links, times, modes, unicode, a service header and an unknown block that are skipped
    W5 w = new W5();
    w.main(false);
    byte[] a = utf8("hello rar5\n"), big = rnd(r, 300000), empty = new byte[0];
    F5 d = new F5("dir"); d.dir = true; d.attr = 040755; d.mtime = 1700000000L; w.file(d);
    F5 fa = new F5("dir/a.txt").stored(a); fa.mtime = 1700000123L; fa.attr = 0100640; w.file(fa);
    F5 fu = new F5("dir/ünï/日本語 ж.bin").stored(big); w.file(fu);
    F5 fe = new F5("empty.txt").stored(empty); w.file(fe);
    F5 ln = new F5("link"); ln.stored(empty); ln.crc = -1; ln.attr = 0120777; ln.rec(5, vint(1), vint(0), vint(9), utf8("dir/a.txt")); w.file(ln);
    F5 wl = new F5("wlink"); wl.stored(empty); wl.crc = -1; wl.host = 0; wl.rec(5, vint(2), vint(0), vint(9), utf8("dir\\a.txt")); w.file(wl);
    F5 hl = new F5("hard"); hl.stored(empty); hl.crc = -1; hl.rec(5, vint(4), vint(0), vint(5), utf8("a.txt")); w.file(hl);
    F5 cm = new F5("CMT").stored(utf8("a comment")); w.file(cm, 3);
    w.block(77, 4, new byte[]{1, 2, 3}, null, utf8("unknown block data"));     // type 77, skip if unknown
    F5 fw = new F5("win.txt").stored(a); fw.host = 0; fw.attr = 0x20; fw.mtime = -1;
    // FILETIME of 2020-02-02 02:02:02 UTC as an extra record
    long ft = (1580608922L + 11644473600L) * 10000000L;
    fw.rec(3, vint(2), le64(ft));
    w.file(fw);
    F5 fx = new F5("unixtime.txt").stored(a); fx.rec(3, vint(1 | 2 | 0x10), le32(1600000000L), le32(500000000L)); w.file(fx);
    F5 same = new F5("dir/a.txt").stored(utf8("second with the same name")); w.file(same);
    w.end();
    File f = arc("container.rar", w.bytes());
    check("isRar on a RAR5 file", RarReader.isRar(f));
    RarReader.Info info = RarReader.list(f, null);
    check("format rar5, not solid, not encrypted", "rar5".equals(info.format) && !info.solid && !info.headerEncrypted && !info.anyEncrypted && !info.truncated);
    List<String> names = new ArrayList<String>();
    for (RarReader.Item it : info.items) names.add(it.name);
    check("the entries in archive order, folders end with '/', service and unknown blocks are not entries",
        names.equals(Arrays.asList("dir/", "dir/a.txt", "dir/ünï/日本語 ж.bin", "empty.txt", "link", "wlink", "hard", "win.txt", "unixtime.txt", "dir/a.txt")));
    RarReader.Item i0 = info.items.get(0), i1 = info.items.get(1);
    check("a folder: dir, size 0, mtime, mode", i0.dir && i0.size == 0 && i0.mtime == 1700000000000L && i0.mode == 0755);
    check("a file: size, packed size, mtime, mode", !i1.dir && i1.size == a.length && i1.csize == a.length && i1.mtime == 1700000123000L && i1.mode == 0640 && !i1.encrypted && i1.linkTarget == null);
    check("a Unix symbolic link: target, no size", "dir/a.txt".equals(info.items.get(4).linkTarget) && info.items.get(4).size == 0 && info.items.get(4).hardLink == null);
    check("a Windows symbolic link: target with '/'", "dir/a.txt".equals(info.items.get(5).linkTarget) && info.items.get(5).mode == -1);
    check("a hard link: hardLink set, no linkTarget", "a.txt".equals(info.items.get(6).hardLink) && info.items.get(6).linkTarget == null);
    check("an extra time record (Windows FILETIME) gives the modification time", info.items.get(7).mtime == 1580608922000L);
    check("an extra time record (Unix time, with nanoseconds) gives the modification time", info.items.get(8).mtime == 1600000000000L);
    List<Exp> exp = new ArrayList<Exp>();
    Exp e0 = new Exp("dir/", null); e0.dir = true; exp.add(e0);
    exp.add(new Exp("dir/a.txt", a)); exp.add(new Exp("dir/ünï/日本語 ж.bin", big)); exp.add(new Exp("empty.txt", empty));
    Exp el = new Exp("link", null); el.link = "dir/a.txt"; exp.add(el);
    Exp ew = new Exp("wlink", null); ew.link = "dir/a.txt"; exp.add(ew);
    Exp eh = new Exp("hard", empty); exp.add(eh);
    exp.add(new Exp("win.txt", a)); exp.add(new Exp("unixtime.txt", a)); exp.add(new Exp("dir/a.txt", utf8("second with the same name")));
    check("list, walk and open of the stored RAR5 entries", sameAs2(f, null, exp));
    InputStream o = RarReader.open(f, "dir/a.txt", null);
    check("open: the first of two entries with the same name", Arrays.equals(readAll(o), a));
    o.close();
    boolean nf = false;
    try { RarReader.open(f, "no/such", null); } catch (FileNotFoundException e) { nf = true; }
    check("open of a missing name is a FileNotFoundException", nf);
    InputStream od = RarReader.open(f, "dir", null);
    check("open of a folder gives an empty stream", od.read() < 0);
    od.close();
    // walk: early stop, partial reads, an unread entry
    final List<String> seen = new ArrayList<String>();
    RarReader.walk(f, null, new RarReader.Visitor() {
      public boolean entry(RarReader.Item it, InputStream data) throws IOException {
        seen.add(it.name);
        if (it.name.equals("dir/a.txt") && seen.size() == 2) { data.read(); }          // half read
        return !it.name.equals("empty.txt");
      }
    });
    check("walk stops when the visitor says so, after a partly read entry", seen.equals(Arrays.asList("dir/", "dir/a.txt", "dir/ünï/日本語 ж.bin", "empty.txt")));
    // a stored entry whose data is damaged: the checksum
    byte[] bytes = w.bytes();
    int at = indexOf(bytes, a);
    byte[] bad = bytes.clone();
    bad[at + 2] ^= 1;
    File fb = arc("container-bad.rar", bad);
    check("a changed byte in stored data: IOException checksum on read, the listing is fine", "checksum".equals(errOf(fb, null, true)) && errOf(fb, null, false) == null);
    InputStream ob = RarReader.open(fb, "dir/a.txt", null);
    String msg = null;
    try { readAll(ob); } catch (IOException e) { msg = e.getMessage(); }
    check("open: the checksum is checked at the end of the entry", "checksum".equals(msg));
    ob.close();
    // an entry that goes on in the next volume
    W5 w2 = new W5();
    w2.main(false);
    F5 s1 = new F5("split.bin").stored(rnd(r, 100)); s1.blockFlags = 0x10; w2.file(s1);
    w2.file(new F5("ok.txt").stored(a));
    w2.end();
    File f2 = arc("split.rar", w2.bytes());
    final String[] res = {null};
    final List<String> got = new ArrayList<String>();
    RarReader.walk(f2, null, new RarReader.Visitor() {
      public boolean entry(RarReader.Item it, InputStream data) throws IOException {
        try { readAll(data); got.add(it.name); } catch (IOException e) { res[0] = e.getMessage(); }
        return true;
      }
    });
    check("an entry continued in the next volume: IOException multi-volume, the others are readable", "multi-volume".equals(res[0]) && got.equals(Arrays.asList("ok.txt")));
    // many entries
    W5 w3 = new W5();
    w3.main(false);
    for (int i = 0; i < 20000; i++) w3.file(new F5("d" + (i % 50) + "/file" + i + ".txt").stored(utf8("n" + i)));
    w3.end();
    File f3 = arc("many.rar", w3.bytes());
    long t0 = System.currentTimeMillis();
    RarReader.Info im = RarReader.list(f3, null);
    check("20000 entries are listed quickly", im.items.size() == 20000 && System.currentTimeMillis() - t0 < 5000 && im.items.get(19999).name.equals("d49/file19999.txt"));
    final int[] count = {0};
    RarReader.walk(f3, null, new RarReader.Visitor() {
      public boolean entry(RarReader.Item it, InputStream data) throws IOException { count[0]++; return true; }
    });
    check("walk over 20000 entries without reading them", count[0] == 20000);
  }

  static boolean sameAs2(File f, char[] pw, List<Exp> exp) { return sameAs(f, pw, exp, f.getName()); }

  static int indexOf(byte[] hay, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= hay.length; i++) {
      for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }

  static void testRar5Lz(Random r) throws Exception {
    // a small one with overlapping matches
    Lz5 z = new Lz5();
    z.beginFile(false);
    z.block(true);
    for (char c : "abc".toCharArray()) z.lit(c);
    z.match(30, 3);          // abcabcabc... (overlap)
    z.match(2, 1);
    z.repLast();
    z.rep(1, 5);
    z.endBlock(true);
    W5 w = new W5(); w.main(false); w.file(new F5("small.txt").lz(z, false, 0)); w.end();
    File f = arc("lz-small.rar", w.bytes());
    check("the expected data of the small stream is what the encoder thinks", new String(z.expected(), StandardCharsets.ISO_8859_1).startsWith("abcabcabcabc"));
    List<Exp> exp = new ArrayList<Exp>();
    exp.add(new Exp("small.txt", z.expected()));
    check("a small LZ stream: literals, an overlapping match, repeat codes", sameAs(f, null, exp, "lz-small"));

    // 2.5 MB, distances up to 1.5 MB (dictionary 2 MB), several blocks with and without tables
    Lz5 z2 = new Lz5(true);
    z2.beginFile(false);
    genOps(z2, r, 2500000, 1500000, true);
    W5 w2 = new W5(); w2.main(false); w2.file(new F5("big.bin").lz(z2, false, 4)); w2.end();
    File f2 = arc("lz-big.rar", w2.bytes());
    exp = new ArrayList<Exp>();
    exp.add(new Exp("big.bin", z2.expected()));
    long t0 = System.currentTimeMillis();
    boolean ok = sameAs(f2, null, exp, "lz-big");
    check("a 2.5 MB LZ stream with every distance and length range, several blocks, Huffman codes of up to 15 bits (longest used " + z2.maxLenSeen + ")", ok && z2.maxLenSeen >= 12);
    Lz5 zfx = new Lz5();
    zfx.beginFile(false);
    genOps(zfx, r, 400000, 300000, true);
    W5 wfx = new W5(); wfx.main(false); wfx.file(new F5("fixed.bin").lz(zfx, false, 3)); wfx.end();
    exp = new ArrayList<Exp>();
    exp.add(new Exp("fixed.bin", zfx.expected()));
    check("an LZ stream with fixed 9 / 6 / 4 / 6 bit codes", sameAs(arc("lz-fixed.rar", wfx.bytes()), null, exp, "lz-fixed"));
    check("the LZ decoder is not slow (3 passes over 2.5 MB)", System.currentTimeMillis() - t0 < 20000);

    // the window wraps: dictionary 128 KB (the window is then 512 KB), 3 MB of data, distances up to 400 KB
    Lz5 z3 = new Lz5(true);
    z3.beginFile(false);
    genOps(z3, r, 3000000, 400000, true);
    W5 w3 = new W5(); w3.main(false); w3.file(new F5("wrap.bin").lz(z3, false, 0)); w3.end();
    File f3 = arc("lz-wrap.rar", w3.bytes());
    exp = new ArrayList<Exp>();
    exp.add(new Exp("wrap.bin", z3.expected()));
    check("a 3 MB stream through a 512 KB window (it wraps)", sameAs(f3, null, exp, "lz-wrap"));

    // unknown size
    Lz5 z4 = new Lz5(true);
    z4.beginFile(false);
    genOps(z4, r, 70000, 60000, true);
    F5 fu = new F5("unknown.bin").lz(z4, false, 1);
    fu.unknownSize = true;
    W5 w4 = new W5(); w4.main(false); w4.file(fu); w4.end();
    File f4 = arc("lz-unknown.rar", w4.bytes());
    RarReader.Info iu = RarReader.list(f4, null);
    check("a file of unknown size lists with size -1", iu.items.get(0).size == -1);
    InputStream in = RarReader.open(f4, "unknown.bin", null);
    check("a file of unknown size is decoded to the last block", Arrays.equals(readAll(in), z4.expected()));
    in.close();

    // a RAR 7 (version 1) header with a fractional dictionary
    Lz5 z5 = new Lz5(true);
    z5.beginFile(false);
    genOps(z5, r, 20000, 15000, false);
    F5 f7 = new F5("v7.bin").lz(z5, false, 2);
    f7.ver = 1;
    W5 w5 = new W5(); w5.main(false); w5.file(f7); w5.end();
    File f5 = arc("lz-v7.rar", w5.bytes());
    exp = new ArrayList<Exp>();
    exp.add(new Exp("v7.bin", z5.expected()));
    check("a RAR 7 file header (compression version 1)", sameAs(f5, null, exp, "lz-v7"));
  }

  static void testRar5Filters(Random r) throws Exception {
    // every filter, blocks that start ahead of the decoder, one that spans the 1 MB output flush border, delta with 1..5 channels
    Lz5 z = new Lz5(true);
    z.beginFile(false);
    z.block(true);
    byte[] code = new byte[40000];
    for (int i = 0; i < code.length; i++) {
      int k = r.nextInt(6);
      code[i] = (byte) (k == 0 ? 0xe8 : k == 1 ? 0xe9 : k == 2 ? 0xeb : r.nextInt(256));
    }
    z.filter(0, 40000, 1, 0);                  // E8 over the next 40000 bytes
    for (byte b : code) z.lit(b);
    z.filter(0, 20000, 2, 0);                  // E8E9
    for (int i = 0; i < 20000; i++) z.lit(code[i]);
    z.filter(0, 8001, 3, 0);                   // ARM, length not a multiple of 4
    for (int i = 0; i < 8001; i++) z.lit(i % 4 == 3 ? 0xeb : r.nextInt(256));
    for (int ch = 1; ch <= 5; ch++) {
      z.filter(0, 3001, 0, ch);
      for (int i = 0; i < 3001; i++) z.lit(r.nextInt(256));
    }
    z.filter(10, 100, 1, 0);                   // a block that starts 10 bytes ahead
    for (int i = 0; i < 10; i++) z.lit('p');
    for (int i = 0; i < 100; i++) z.lit(i % 3 == 0 ? 0xe8 : 7);
    z.endBlock(false);
    z.block(false);
    // padding with a match so that the next filter crosses the 1 MB border (the window is 2 MB, the flush chunk 1 MB)
    for (int i = 0; i < 100; i++) z.lit(i);
    while (z.simLen < 1048576 - 300) z.match(4000 > z.simLen ? 100 : 4000, 1 + r.nextInt(50) + 100);
    z.filter(0, 60000, 3, 0);
    for (int i = 0; i < 20000; i++) z.lit(i % 4 == 3 ? 0xeb : r.nextInt(256));
    z.match(2000, 3000);
    while (z.simLen - 60000 < 1048576 - 300 + 60000) z.match(4000, 5000);
    z.filter(0, 4096, 0, 2);
    for (int i = 0; i < 4096; i++) z.lit(r.nextInt(256));
    z.endBlock(true);
    W5 w = new W5(); w.main(false); w.file(new F5("filters.bin").lz(z, false, 4)); w.end();
    File f = arc("filters.rar", w.bytes());
    List<Exp> exp = new ArrayList<Exp>();
    exp.add(new Exp("filters.bin", z.expected()));
    check("E8, E8E9, ARM and delta (1 to 5 channels) filters, also across the output flush border", sameAs(f, null, exp, "filters"));
    check("the filters change the data (the test is not vacuous)", !Arrays.equals(Arrays.copyOfRange(z.sim, 0, 40000), Arrays.copyOfRange(z.expected(), 0, 40000)));

    // an unknown filter type is refused cleanly
    Lz5 zu = new Lz5();
    zu.beginFile(false);
    zu.block(true);
    zu.lit('a');
    zu.bw.put(256, 9);
    zu.bw.put(2, 2); zu.bw.put(0, 8); zu.bw.put(5, 8);   // start 0
    zu.bw.put(0, 2); zu.bw.put(50, 8);                   // length 50
    zu.bw.put(6, 3);                                     // type 6
    for (int i = 0; i < 60; i++) zu.lit('b');
    zu.endBlock(true);
    F5 fz = new F5("f.bin").lz(zu, false, 1);
    fz.unp = 61;
    W5 wu = new W5(); wu.main(false); wu.file(fz); wu.end();
    File fu = arc("filter-unknown.rar", wu.bytes());
    check("an unknown filter type: IOException unsupported-filter", "unsupported-filter".equals(errOf(fu, null, true)));
  }

  static void testRar5Solid(Random r) throws Exception {
    // five entries: lz, lz solid (reaches back into the first), stored in between, lz solid again, lz not solid (fresh window)
    Lz5 z = new Lz5(true);
    W5 w = new W5();
    w.main(true);
    List<Exp> exp = new ArrayList<Exp>();
    z.beginFile(false);
    genOps(z, r, 150000, 100000, true);
    byte[] e1 = z.expected();
    F5 f1 = new F5("one.bin").lz(z, false, 1); w.file(f1); exp.add(new Exp("one.bin", e1));
    z.beginFile(true);
    z.block(false);                                    // tables are those of the first file
    for (int i = 0; i < 50; i++) z.match(200, 1000 + i * 700);
    z.endBlock(false);
    z.block(false);
    genOps2(z, r, 120000, 140000);
    byte[] e2 = z.expected();
    F5 f2 = new F5("two.bin").lz(z, true, 1); w.file(f2); exp.add(new Exp("two.bin", e2));
    byte[] mid = rnd(r, 5000);
    w.file(new F5("mid.bin").stored(mid)); exp.add(new Exp("mid.bin", mid));
    z.beginFile(true);
    z.block(false);
    for (int i = 0; i < 30; i++) z.match(300, 150000 + i * 1000);
    z.rep(2, 77);
    genOps2(z, r, 90000, 200000);
    byte[] e3 = z.expected();
    F5 f3 = new F5("three.bin").lz(z, true, 1); w.file(f3); exp.add(new Exp("three.bin", e3));
    Lz5 zf = new Lz5(true);
    zf.beginFile(false);
    genOps(zf, r, 50000, 40000, false);
    byte[] e4 = zf.expected();
    F5 f4 = new F5("four.bin").lz(zf, false, 1); w.file(f4); exp.add(new Exp("four.bin", e4));
    w.end();
    File f = arc("solid.rar", w.bytes());
    check("a solid archive reads as solid", RarReader.list(f, null).solid);
    check("solid: list, walk, open (the last entries first)", sameAs(f, null, exp, "solid"));
    // a visitor that reads nothing, or reads one byte: the entries after still decode
    final List<byte[]> got = new ArrayList<byte[]>();
    RarReader.walk(f, null, new RarReader.Visitor() {
      public boolean entry(RarReader.Item it, InputStream data) throws IOException {
        if (it.name.equals("one.bin")) return true;                    // unread
        if (it.name.equals("two.bin")) { data.read(); return true; }    // one byte
        got.add(readAll(data));
        return true;
      }
    });
    check("solid: entries that the visitor does not read are decoded in the background", got.size() == 3 && Arrays.equals(got.get(0), mid) && Arrays.equals(got.get(1), e3) && Arrays.equals(got.get(2), e4));
    // the fresh entry does not need the chain: a visitor skipping everything but the last gets it right
    final List<byte[]> only = new ArrayList<byte[]>();
    RarReader.walk(f, null, new RarReader.Visitor() {
      public boolean entry(RarReader.Item it, InputStream data) throws IOException { if (it.name.equals("four.bin")) only.add(readAll(data)); return true; }
    });
    check("solid: skipping everything before a non-solid entry", only.size() == 1 && Arrays.equals(only.get(0), e4));
    // damage inside the chain
    byte[] bytes = w.bytes();
    byte[] bad = bytes.clone();
    bad[(int) (f1.packed.length / 2) + 300] ^= 0x55;        // somewhere in the first entry's packed data
    File fb = arc("solid-bad.rar", bad);
    String err = errOf(fb, null, true);
    check("solid: damage in an early entry is an IOException (not a crash)", err != null && !err.startsWith("THROWN"));
    // a visitor that reads nothing never sees the damage in its own read; the background drain meets it first and must not hide it
    String skipErr = null;
    try {
      RarReader.walk(fb, null, new RarReader.Visitor() {
        public boolean entry(RarReader.Item it, InputStream data) throws IOException { return true; }
      });
    } catch (IOException ex) { skipErr = ex.getMessage(); } catch (Throwable t) { skipErr = "THROWN " + t; }
    check("solid: damage found while draining entries the visitor skipped is reported (" + skipErr + ")", skipErr != null && !skipErr.startsWith("THROWN"));
  }

  /** Ops for a solid continuation (tables sent already). */
  static void genOps2(Lz5 z, Random r, int total, int maxDist) {
    int start = z.simLen - z.fileStart;
    int opsInBlock = 0;
    while (z.simLen - z.fileStart < start + total) {
      int have = z.simLen, k = r.nextInt(10);
      if (k < 4) { int run = 1 + r.nextInt(10); for (int i = 0; i < run; i++) z.lit('a' + r.nextInt(20)); }
      else {
        int dist = 1 + r.nextInt(Math.min(have, maxDist));
        int adj = (dist > 0x100 ? 1 : 0) + (dist > 0x2000 ? 1 : 0) + (dist > 0x40000 ? 1 : 0);
        z.match(2 + adj + r.nextInt(100), dist);
      }
    }
    z.endBlock(true);
  }

  static void testRar5Crypto(Random r) throws Exception {
    char[] pw = "p@ss wörd".toCharArray();
    byte[] a = utf8("secret text, not very long\n"), b = rnd(r, 70001);
    Lz5 z = new Lz5(true);
    z.beginFile(false);
    genOps(z, r, 200000, 150000, true);
    byte[] ez = z.expected();
    W5 w = new W5();
    w.main(false);
    w.file(new F5("plain.txt").stored(utf8("not secret")));
    w.file(new F5("stored.txt").stored(a).encrypt(pw, 6, true, false, r));
    w.file(new F5("stored-big.bin").stored(b).encrypt(pw, 6, true, true, r));
    w.file(new F5("lz.bin").lz(z, false, 2).encrypt(pw, 6, true, false, r));
    w.file(new F5("nocheck.txt").stored(a).encrypt(pw, 6, false, false, r));
    w.end();
    File f = arc("enc-data.rar", w.bytes());
    RarReader.Info info = RarReader.list(f, null);
    check("an archive with encrypted data lists without a password", info.items.size() == 5 && !info.headerEncrypted && info.anyEncrypted && !info.items.get(0).encrypted && info.items.get(1).encrypted && info.items.get(3).encrypted);
    List<Exp> exp = new ArrayList<Exp>();
    exp.add(new Exp("plain.txt", utf8("not secret"))); exp.add(new Exp("stored.txt", a)); exp.add(new Exp("stored-big.bin", b)); exp.add(new Exp("lz.bin", ez)); exp.add(new Exp("nocheck.txt", a));
    check("encrypted data (stored, stored with MAC checksum, compressed, without check value) with the right password", sameAs(f, pw, exp, "enc-data"));
    String e = errOf(f, null, true);
    check("encrypted data without a password: PasswordException wrong=false", "pw-needed".equals(e));
    check("encrypted data with a wrong password: PasswordException wrong=true (check value)", "pw-wrong".equals(errOf(f, "nope".toCharArray(), true)));
    // the visitor can skip entries it cannot read
    final List<String> okNames = new ArrayList<String>();
    RarReader.walk(f, null, new RarReader.Visitor() {
      public boolean entry(RarReader.Item it, InputStream data) throws IOException { if (!it.encrypted) { readAll(data); okNames.add(it.name); } return true; }
    });
    check("a visitor may skip encrypted entries without a password", okNames.equals(Arrays.asList("plain.txt")));
    // no check value: the wrong password shows as a bad checksum, reported as a wrong password
    final String[] one = {null};
    InputStream in = RarReader.open(f, "nocheck.txt", "wrong".toCharArray());
    try { readAll(in); } catch (RarReader.PasswordException pe) { one[0] = pe.wrong ? "wrong" : "needed"; } catch (IOException ex) { one[0] = ex.getMessage(); }
    in.close();
    check("encrypted without check value, wrong password: PasswordException wrong=true", "wrong".equals(one[0]));
    // the default of rar: 2^15 iterations
    W5 w15 = new W5();
    w15.main(false);
    w15.file(new F5("x.txt").stored(a).encrypt(pw, 15, true, false, r));
    w15.end();
    long t0 = System.currentTimeMillis();
    exp = new ArrayList<Exp>();
    exp.add(new Exp("x.txt", a));
    check("the usual 2^15 key derivation count", sameAs(arcFile("enc15.rar", w15.bytes()), pw, exp, "enc15"));
    System.out.println("   (2^15 derivation, read three ways: " + (System.currentTimeMillis() - t0) + " ms)");
    // encrypted headers
    for (final boolean chk : new boolean[]{true, false}) {
      W5 wh = new W5();
      wh.encryptHeaders(pw, 5, chk);
      wh.main(false);
      wh.file(new F5("dir/").stored(new byte[0]));
      Lz5 zh = new Lz5(true);
      zh.beginFile(false);
      genOps(zh, r, 30000, 20000, false);
      wh.file(new F5("h1.txt").stored(a));
      wh.file(new F5("h2.bin").lz(zh, false, 1));
      wh.file(new F5("h3.txt").stored(a).encrypt(pw, 6, true, false, r));
      wh.end();
      File fh = arc("enc-headers-" + chk + ".rar", wh.bytes());
      RarReader.Info ih = RarReader.list(fh, pw);
      check("encrypted headers (check value " + chk + "): the names after the password", ih.headerEncrypted && ih.anyEncrypted && ih.items.size() == 4 && ih.items.get(1).name.equals("h1.txt"));
      exp = new ArrayList<Exp>();
      exp.add(new Exp("dir/", new byte[0])); exp.get(0).dir = false;
      // (the entry named "dir/" is a stored file here, not a folder entry: only the name matters)
      exp.clear();
      exp.add(new Exp("dir/", new byte[0])); exp.add(new Exp("h1.txt", a)); exp.add(new Exp("h2.bin", zh.expected())); exp.add(new Exp("h3.txt", a));
      check("encrypted headers (check value " + chk + "): list, walk, open", sameAs(fh, pw, exp, "enc-headers"));
      check("encrypted headers without a password: PasswordException wrong=false (list and walk)", "pw-needed".equals(errOf(fh, null, false)) && "pw-needed".equals(errOf(fh, null, true)));
      check("encrypted headers, wrong password: PasswordException wrong=true", "pw-wrong".equals(errOf(fh, "wrong".toCharArray(), false)));
    }
    // a damaged header in an encrypted-header archive that opened with the right password is damage, not a wrong password
    W5 wd = new W5();
    wd.encryptHeaders(pw, 5, true);
    wd.main(false);
    wd.file(new F5("a.txt").stored(a));
    wd.end();
    byte[] bd = wd.bytes();
    byte[] bd2 = bd.clone();
    bd2[bd2.length - 32] ^= 1;           // the IV of the last block: flips the first byte of its plain text, the CRC
    String ed = errOf(arc("enc-headers-damaged.rar", bd2), pw, false);
    check("a damaged encrypted header with a verified password is header-crc, not a password error (" + ed + ")", "header-crc".equals(ed));
  }

  static File arcFile(String name, byte[] d) throws IOException { return arc(name, d); }

  static void testRar5Hashes(Random r) throws Exception {
    byte[] data = rnd(r, 100000);
    RarReader.Blake2sp bs = new RarReader.Blake2sp();
    bs.update(data, 0, data.length);
    byte[] h = bs.digest();
    W5 w = new W5();
    w.main(false);
    F5 f1 = new F5("b.bin").stored(data); f1.crc = -1; f1.blake = h; f1.rec(2, vint(0), h); w.file(f1);
    F5 f2 = new F5("both.bin").stored(data); f2.blake = h; f2.rec(2, vint(0), h); w.file(f2);
    w.end();
    File f = arc("blake.rar", w.bytes());
    List<Exp> exp = new ArrayList<Exp>();
    exp.add(new Exp("b.bin", data)); exp.add(new Exp("both.bin", data));
    check("a BLAKE2sp file hash (with and without a CRC32)", sameAs(f, null, exp, "blake"));
    byte[] hb = h.clone();
    hb[3] ^= 1;
    W5 w2 = new W5();
    w2.main(false);
    F5 g = new F5("b.bin").stored(data); g.crc = -1; g.rec(2, vint(0), hb); w2.file(g);
    w2.end();
    check("a wrong BLAKE2sp hash: checksum", "checksum".equals(errOf(arc("blake-bad.rar", w2.bytes()), null, true)));
    // BLAKE2sp with encryption: keyed
    char[] pw = "pw".toCharArray();
    W5 w3 = new W5();
    w3.main(false);
    F5 e = new F5("e.bin").stored(data); e.crc = -1; e.blake = h;
    e.encrypt(pw, 4, true, true, r);
    e.rec(2, vint(0), e.blake);              // the MAC of the digest
    w3.file(e);
    w3.end();
    exp = new ArrayList<Exp>();
    exp.add(new Exp("e.bin", data));
    check("an encrypted file with a keyed (MAC) BLAKE2sp hash", sameAs(arc("blake-enc.rar", w3.bytes()), pw, exp, "blake-enc"));
  }

  static void testRar5Limits(Random r) throws Exception {
    // a dictionary of 256 MB is fine (and takes no 256 MB), 512 MB is refused, but the other entries can be read
    Lz5 z = new Lz5(true);
    z.beginFile(false);
    genOps(z, r, 50000, 40000, false);
    W5 w = new W5();
    w.main(false);
    w.file(new F5("d256.bin").lz(z, false, 11));
    w.file(new F5("plain.txt").stored(utf8("fine")));
    Lz5 z2 = new Lz5(true);
    z2.beginFile(false);
    genOps(z2, r, 50000, 40000, false);
    w.file(new F5("d512.bin").lz(z2, false, 12));
    w.file(new F5("d4g.bin").lz(z2, false, 15));
    w.file(new F5("after.txt").stored(utf8("still fine")));
    w.end();
    File f = arc("dict.rar", w.bytes());
    RarReader.Info info = RarReader.list(f, null);
    check("a huge dictionary does not matter for the listing", info.items.size() == 5);
    final List<String> ok = new ArrayList<String>();
    final List<String> bad = new ArrayList<String>();
    final byte[][] first = {null};
    long mem0 = Runtime.getRuntime().totalMemory();
    RarReader.walk(f, null, new RarReader.Visitor() {
      public boolean entry(RarReader.Item it, InputStream data) throws IOException {
        try { byte[] d = readAll(data); ok.add(it.name); if (it.name.equals("d256.bin")) first[0] = d; }
        catch (IOException e) { bad.add(it.name + ":" + e.getMessage()); }
        return true;
      }
    });
    check("dictionary 256 MB decodes, 512 MB and 4 GB fail with dictionary-too-large, the rest reads",
        ok.equals(Arrays.asList("d256.bin", "plain.txt", "after.txt")) && bad.equals(Arrays.asList("d512.bin:dictionary-too-large", "d4g.bin:dictionary-too-large")) && Arrays.equals(first[0], z.expected()));
    check("a 256 MB dictionary does not allocate 256 MB (the window grows with the data)", Runtime.getRuntime().totalMemory() - mem0 < 100L * 1024 * 1024);

    // an entry that claims a huge size but has little data: truncated, quickly, no huge buffers
    Lz5 z3 = new Lz5(true);
    z3.beginFile(false);
    genOps(z3, r, 20000, 10000, false);
    F5 liar = new F5("liar.bin").lz(z3, false, 3);
    liar.unp = 1L << 40;
    W5 w2 = new W5(); w2.main(false); w2.file(liar); w2.end();
    long t0 = System.currentTimeMillis();
    String e = errOf(arc("liar.rar", w2.bytes()), null, true);
    check("a size far above the data: IOException truncated, fast", "truncated".equals(e) && System.currentTimeMillis() - t0 < 5000);
    // a size below what the stream makes: corrupt
    F5 liar2 = new F5("liar2.bin").lz(z3, false, 3);
    liar2.unp = 5000;
    W5 w3 = new W5(); w3.main(false); w3.file(liar2); w3.end();
    String e2 = errOf(arc("liar2.rar", w3.bytes()), null, true);
    check("a size below the data of the stream: an IOException", e2 != null && !e2.startsWith("THROWN"));
    // a header whose size field claims more than the file has
    byte[] cut = w3.bytes();
    check("an archive cut inside a header or its data: IOException", errOf(arc("cut.rar", Arrays.copyOf(cut, cut.length - 30)), null, true) != null);
    // a stored entry that is shorter than its size
    F5 sh = new F5("short.bin").stored(rnd(r, 100)); sh.unp = 200;
    W5 w4 = new W5(); w4.main(false); w4.file(sh); w4.end();
    check("a stored entry shorter than its size: IOException truncated", "truncated".equals(errOf(arc("short.rar", w4.bytes()), null, true)));
    // an unsupported compression version is listed but not read
    F5 vv = new F5("v9.bin").stored(utf8("x")); vv.ver = 9;
    W5 w5 = new W5(); w5.main(false); w5.file(vv); w5.end();
    check("an unknown compression version: listed, read fails with unsupported-version", errOf(arc("v9.rar", w5.bytes()), null, false) == null && "unsupported-version".equals(errOf(arc("v9.rar", w5.bytes()), null, true)));
  }

  static void testRar5Damage(Random r) throws Exception {
    // build one archive of everything and break it in many ways: only IOExceptions, no other exception, no hang
    char[] pw = "pw".toCharArray();
    W5 w = new W5();
    w.main(true);
    Lz5 z = new Lz5(true);
    z.beginFile(false);
    genOps(z, r, 60000, 50000, true);
    w.file(new F5("a.bin").lz(z, false, 1));
    z.beginFile(true);
    z.block(false);
    for (int i = 0; i < 20; i++) z.match(100, 300 + i);
    z.endBlock(true);
    w.file(new F5("b.bin").lz(z, true, 1));
    Lz5 zf = new Lz5(true);
    zf.beginFile(false);
    zf.block(true);
    zf.filter(0, 3000, 1, 0);
    for (int i = 0; i < 3000; i++) zf.lit(i % 3 == 0 ? 0xe8 : i);
    zf.filter(0, 100, 0, 2);
    for (int i = 0; i < 100; i++) zf.lit(i);
    zf.endBlock(true);
    w.file(new F5("c.bin").lz(zf, false, 1));
    w.file(new F5("d.txt").stored(rnd(r, 1000)).encrypt(pw, 4, true, false, r));
    F5 ln = new F5("l"); ln.stored(new byte[0]); ln.crc = -1; ln.rec(5, vint(1), vint(0), vint(3), utf8("a/b")); w.file(ln);
    w.end();
    byte[] base = w.bytes();
    File ok = arc("fuzz-base.rar", base);
    check("the fuzz base archive reads", errOf(ok, pw, true) == null);
    int thrown = 0, hung = 0;
    long t0 = System.currentTimeMillis();
    for (int round = 0; round < 400; round++) {
      byte[] m = base.clone();
      int kind = round % 4;
      if (kind == 0) { m[r.nextInt(m.length)] ^= 1 << r.nextInt(8); }
      else if (kind == 1) { int p = r.nextInt(m.length); for (int i = 0; i < 4 && p + i < m.length; i++) m[p + i] = (byte) r.nextInt(256); }
      else if (kind == 2) { m = Arrays.copyOf(m, 8 + r.nextInt(m.length - 8)); }
      else { int p = 8 + r.nextInt(300); m[p] ^= (byte) (1 + r.nextInt(255)); }
      File fm = arc("fuzz.rar", m);
      long s = System.currentTimeMillis();
      String e = errOf(fm, pw, true);
      if (e != null && e.startsWith("THROWN")) { thrown++; if (thrown < 4) System.out.println("   fuzz round " + round + " (kind " + kind + "): " + e); }
      if (System.currentTimeMillis() - s > 10000) hung++;
    }
    check("400 damaged copies: only IOException (or success), never another exception (" + thrown + " other)", thrown == 0);
    check("no damaged copy took long", hung == 0);
    System.out.println("   (fuzzing took " + (System.currentTimeMillis() - t0) + " ms)");
  }

  // ------------------------------------------------------------------------------------------------------------ RAR 1.5 - 4.x

  static void testRar4(Random r) throws Exception {
    int before = liveRarThreads();
    byte[] a = utf8("hello rar4\n"), big = rnd(r, 3000000), mid = rnd(r, 20000);
    long t1 = dosTime(2021, 7, 14, 12, 30, 40);
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cal.clear();
    cal.set(2021, Calendar.JULY, 14, 12, 30, 40);
    W4 w = new W4(false, null);
    w.file("docs", new byte[0], 2, 0x10, t1, true, null, false);
    w.file("docs\\a.txt", a, 2, 0x20, t1, false, null, false);
    w.file("big.bin", big, 3, 0100755, t1, false, null, false);
    w.file("mid.bin", mid, 3, 0100644, t1, false, null, false);
    w.file("link", utf8("docs/a.txt"), 3, 0120777, t1, false, null, false);
    w.file("empty", new byte[0], 3, 0100600, t1, false, null, false);
    w.end();
    File f = arc("r4.rar", w.bytes());
    check("isRar on a RAR 4 file", RarReader.isRar(f));
    RarReader.Info info = RarReader.list(f, null);
    check("format rar4, not solid, not encrypted", "rar4".equals(info.format) && !info.solid && !info.headerEncrypted && !info.anyEncrypted && info.items.size() == 6);
    RarReader.Item d = info.items.get(0), ia = info.items.get(1), ib = info.items.get(2);
    check("a RAR 4 folder and a Windows path: '/' separators, folder ends with '/'", d.dir && d.name.equals("docs/") && ia.name.equals("docs/a.txt") && !ia.dir);
    check("RAR 4 size, packed size, mtime (DOS time), no mode from a Windows host", ia.size == a.length && ia.csize == a.length && ia.mtime == cal.getTimeInMillis() && ia.mode == -1);
    check("RAR 4 Unix host: mode", ib.mode == 0755 && info.items.get(3).mode == 0644);
    check("RAR 4 Unix symbolic link: target read from its data", "docs/a.txt".equals(info.items.get(4).linkTarget));
    List<Exp> exp = new ArrayList<Exp>();
    Exp e0 = new Exp("docs/", null); e0.dir = true; exp.add(e0);
    exp.add(new Exp("docs/a.txt", a)); exp.add(new Exp("big.bin", big)); exp.add(new Exp("mid.bin", mid));
    Exp el = new Exp("link", null); el.link = "docs/a.txt"; exp.add(el);
    exp.add(new Exp("empty", new byte[0]));
    check("RAR 4 stored entries: list, walk, open", sameAs(f, null, exp, "r4"));
    // a visitor that reads a little of a big entry, and stops
    final List<String> seen = new ArrayList<String>();
    RarReader.walk(f, null, new RarReader.Visitor() {
      public boolean entry(RarReader.Item it, InputStream data) throws IOException {
        seen.add(it.name);
        if (it.name.equals("big.bin")) { byte[] b = new byte[10]; data.read(b); }
        return !it.name.equals("mid.bin");
      }
    });
    check("RAR 4 walk: partial read of a big entry, early stop", seen.equals(Arrays.asList("docs/", "docs/a.txt", "big.bin", "mid.bin")));
    check("RAR 4: no unpacker thread is left behind", waitThreads(before));
    // damage
    byte[] bytes = w.bytes();
    byte[] bd = bytes.clone();
    bd[indexOf(bytes, a) + 3] ^= 4;
    File fb = arc("r4-bad.rar", bd);
    check("RAR 4: a changed byte in stored data: IOException checksum", "checksum".equals(errOf(fb, null, true)) && errOf(fb, null, false) == null);
    InputStream ib2 = RarReader.open(fb, "docs/a.txt", null);
    String msg = null;
    try { readAll(ib2); } catch (IOException e) { msg = e.getMessage(); }
    check("RAR 4 open: checksum at the end of the entry", "checksum".equals(msg));
    ib2.close();
    check("RAR 4: cut archive: IOException", errOf(arc("r4-cut.rar", Arrays.copyOf(bytes, bytes.length / 2)), null, true) != null);
    // solid flag on entries
    W4 ws = new W4(true, null);
    ws.file("s1", a, 3, 0100644, t1, false, null, false);
    ws.file("s2", mid, 3, 0100644, t1, false, null, true);
    ws.end();
    File fs = arc("r4-solid.rar", ws.bytes());
    exp = new ArrayList<Exp>();
    exp.add(new Exp("s1", a)); exp.add(new Exp("s2", mid));
    check("RAR 4 solid flags (stored entries)", RarReader.list(fs, null).solid && sameAs(fs, null, exp, "r4-solid"));
    // encrypted data
    char[] pw = "geheim".toCharArray();
    W4 we = new W4(false, null);
    we.file("plain", a, 3, 0100644, t1, false, null, false);
    we.file("secret", mid, 3, 0100644, t1, false, pw, false);
    we.end();
    File fe = arc("r4-enc.rar", we.bytes());
    RarReader.Info ie = RarReader.list(fe, null);
    check("RAR 4 encrypted data lists without a password", ie.anyEncrypted && !ie.headerEncrypted && ie.items.get(1).encrypted && !ie.items.get(0).encrypted);
    exp = new ArrayList<Exp>();
    exp.add(new Exp("plain", a)); exp.add(new Exp("secret", mid));
    boolean okEnc = sameAs(fe, pw, exp, "r4-enc");
    check("RAR 4 encrypted data with the right password (key derivation of the test equals junrar's)", okEnc);
    check("RAR 4 encrypted data without a password: PasswordException wrong=false", "pw-needed".equals(errOf(fe, null, true)));
    check("RAR 4 encrypted data with a wrong password: PasswordException wrong=true", "pw-wrong".equals(errOf(fe, "wrong".toCharArray(), true)));
    // encrypted headers
    W4 wh = new W4(false, pw);
    wh.file("h1", a, 3, 0100644, t1, false, null, false);
    wh.file("h2", mid, 3, 0100644, t1, false, pw, false);
    File fh = arc("r4-hp.rar", wh.bytes());
    RarReader.Info ih = null;
    String herr = null;
    try { ih = RarReader.list(fh, pw); } catch (IOException e) { herr = e.toString(); }
    check("RAR 4 encrypted headers (-hp): junrar reads them with the password" + (herr == null ? "" : " [" + herr + "]"), ih != null && ih.headerEncrypted && ih.items.size() == 2 && ih.items.get(0).name.equals("h1"));
    if (ih != null) {
      exp = new ArrayList<Exp>();
      exp.add(new Exp("h1", a)); exp.add(new Exp("h2", mid));
      check("RAR 4 encrypted headers: walk and open", sameAs(fh, pw, exp, "r4-hp"));
    }
    check("RAR 4 encrypted headers without a password: PasswordException wrong=false", "pw-needed".equals(errOf(fh, null, false)));
    String hw = errOf(fh, "wrong".toCharArray(), false);
    check("RAR 4 encrypted headers with a wrong password: PasswordException wrong=true (" + hw + ")", "pw-wrong".equals(hw));
    check("RAR 4: no unpacker thread is left behind (2)", waitThreads(before));
    // fuzz
    byte[] base = w.bytes();
    int thrown = 0;
    for (int round = 0; round < 150; round++) {
      byte[] m = base.clone();
      if (round % 3 == 0) m[r.nextInt(m.length)] ^= 1 << r.nextInt(8);
      else if (round % 3 == 1) m = Arrays.copyOf(m, 7 + r.nextInt(m.length - 7));
      else { int p = 7 + r.nextInt(150); m[p] ^= (byte) (1 + r.nextInt(255)); }
      String e = errOf(arc("r4-fuzz.rar", m), null, true);
      if (e != null && e.startsWith("THROWN")) { thrown++; if (thrown < 4) System.out.println("   rar4 fuzz round " + round + ": " + e); }
    }
    check("RAR 4: 150 damaged copies give IOExceptions only (" + thrown + " other)", thrown == 0);
    check("RAR 4: no unpacker thread is left behind (3)", waitThreads(before));
  }

  static int liveRarThreads() {
    int c = 0;
    for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("rar4-extract") && t.isAlive()) c++;
    return c;
  }
  static boolean waitThreads(int before) throws Exception {
    for (int i = 0; i < 50; i++) { if (liveRarThreads() <= before) return true; Thread.sleep(100); }
    return false;
  }

  static void testSfxAndMagic(Random r) throws Exception {
    byte[] stub = new byte[70000];
    r.nextBytes(stub);
    stub[0] = 'M'; stub[1] = 'Z';
    byte[] fake4 = {'R', 'a', 'r', '!', 0x1A, 0x07, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
    byte[] fake5 = {'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    System.arraycopy(fake4, 0, stub, 1000, fake4.length);
    System.arraycopy(fake5, 0, stub, 65530, fake5.length);          // across the 64 KB chunk border of the scan
    byte[] a = utf8("sfx data\n");
    W5 w = new W5(); w.main(false); w.file(new F5("s.txt").stored(a)); w.end();
    File f5 = arc("sfx5.exe", cat(stub, w.bytes()));
    check("isRar: a RAR5 archive behind a stub (fake signatures in it are ignored)", RarReader.isRar(f5));
    List<Exp> exp = new ArrayList<Exp>();
    exp.add(new Exp("s.txt", a));
    check("RAR5 behind a stub: list, walk, open (offsets from the signature)", sameAs(f5, null, exp, "sfx5"));
    W4 w4 = new W4(false, null);
    w4.file("s.txt", a, 3, 0100644, dosTime(2020, 1, 1, 0, 0, 0), false, null, false);
    w4.end();
    File f4 = arc("sfx4.exe", cat(stub, w4.bytes()));
    check("isRar: a RAR4 archive behind a stub", RarReader.isRar(f4));
    check("RAR4 behind a stub: list, walk, open", sameAs(f4, null, exp, "sfx4"));
    // the signature across the 64 KB chunk border of the scan (RAR5: 8 bytes, RAR4: 7)
    for (int len : new int[]{65528, 65529, 65530, 65531, 65532, 65533, 65534, 65535, 65536}) {
      byte[] st = new byte[len];
      check("a stub of " + len + " bytes: RAR5 found across the scan chunk border", RarReader.isRar(arc("b5-" + len + ".exe", cat(st, w.bytes()))));
      check("a stub of " + len + " bytes: RAR4 found across the scan chunk border", RarReader.isRar(arc("b4-" + len + ".exe", cat(st, w4.bytes()))));
    }
    // a stub of 1 MB: found; of 2 MB: not
    byte[] stub1 = new byte[RarReader.SFX_SCAN - 100];
    check("a stub just inside 1 MB", RarReader.isRar(arc("sfx-1m.exe", cat(stub1, w.bytes()))));
    byte[] stub2 = new byte[RarReader.SFX_SCAN + 5000];
    check("a stub beyond 1 MB is not looked into", !RarReader.isRar(arc("sfx-2m.exe", cat(stub2, w.bytes()))));
    check("isRar: not a RAR file", !RarReader.isRar(arc("t.txt", utf8("hello"))) && !RarReader.isRar(arc("empty", new byte[0])) && !RarReader.isRar(new File(tmp, "missing")));
    check("isRar: only the marker, nothing behind it (offset 0 is taken on its magic)", RarReader.isRar(arc("marker.rar", cat(rar3Marker()))) && RarReader.isRar(arc("marker5.rar", SIG5)));
    check("list of a file that is no RAR: IOException not-rar", "not-rar".equals(errOf(arc("t2.txt", utf8("hello world, no rar here")), null, false)));
    check("list of a RAR5 signature without headers: an empty archive or an IOException", errOf(arc("marker5b.rar", SIG5), null, false) == null);
    // junrar and slf4j: nothing was printed by an uncaught exception, the logger is a no-op
    check("slf4j without a binding is harmless (the RAR4 reads above ran)", true);
  }

  // ------------------------------------------------------------------------------------------------------------ real archives

  static void testFixtures() throws Exception {
    File dir = null;
    String prop = System.getProperty("rarfix");
    if (prop != null) dir = new File(prop);
    else {
      File d = new File(System.getProperty("user.dir"));
      for (int i = 0; i < 8 && d != null; i++, d = d.getParentFile()) {
        File c = new File(d, "java/fixtures/rar");
        File c2 = new File(d, "tests/java/fixtures/rar");
        if (c.isDirectory()) { dir = c; break; }
        if (c2.isDirectory()) { dir = c2; break; }
      }
    }
    File[] files = dir == null ? null : dir.listFiles();
    List<File> rars = new ArrayList<File>();
    if (files != null) { Arrays.sort(files); for (File x : files) if (x.getName().toLowerCase().endsWith(".rar")) rars.add(x); }
    if (rars.isEmpty()) {
      skip("no real RAR archives in tests/java/fixtures/rar (see its README): the checks below the container level were made with archives written by this test only");
      return;
    }
    String[] pws = {null, "password", "test", "secret", "1234", "12345", "rar", "unrar", "pass", "psw", "Password"};
    int real4 = 0, real5 = 0;
    for (File f : rars) {
      String nm = f.getName();
      // multi-volume parts after the first are not archives of their own
      if (nm.matches(".*\\.part0*([2-9]|[1-9][0-9]+)\\.rar")) continue;
      char[] pw = null;
      RarReader.Info info = null;
      String err = null;
      for (String p : pws) {
        try { info = RarReader.list(f, p == null ? null : p.toCharArray()); pw = p == null ? null : p.toCharArray(); err = null; break; }
        catch (RarReader.PasswordException pe) { err = "password"; }
        catch (IOException e) { err = e.getMessage(); break; }
      }
      if (info == null) { check("fixture " + nm + " lists (" + err + ")", false); continue; }
      if (info.format.equals("rar5")) real5++; else real4++;
      final String name = nm;
      final long[] sizes = {0};
      final boolean[] bad = {false};
      final List<String> order = new ArrayList<String>();
      final List<byte[]> hashes = new ArrayList<byte[]>();
      String werr = null;
      char[] usePw = pw;
      try {
        // a password may fit the data but not be the one for the header, try the list again with whichever worked
        RarReader.walk(f, usePw, new RarReader.Visitor() {
          public boolean entry(RarReader.Item it, InputStream data) throws IOException {
            order.add(it.name);
            if (it.dir || it.linkTarget != null || it.hardLink != null) return true;
            try {
              byte[] d = readAll(data);
              sizes[0] += d.length;
              if (it.size >= 0 && d.length != it.size) bad[0] = true;
              hashes.add(MessageDigest.getInstance("SHA-256").digest(d));
            } catch (RarReader.PasswordException pe) {
              if (!pe.wrong) { hashes.add(null); return true; }
              throw pe;
            } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
            return true;
          }
        });
      } catch (IOException e) { werr = e.getMessage(); }
      boolean multi = info.multiVolume || nm.contains("vols") || nm.contains(".part");
      check("fixture " + nm + ": walk (every entry's CRC / hash checked)" + (werr == null ? "" : " [" + werr + "]"), werr == null || (multi && "multi-volume".equals(werr)));
      check("fixture " + nm + ": walk gives the sizes of the listing", !bad[0]);
      // open: every file entry by name equals what walk gave (the first of equal names)
      if (werr == null) {
        int idx = 0;
        boolean allSame = true;
        for (RarReader.Item it : info.items) {
          int k = idx++;
          if (it.dir || it.linkTarget != null || it.hardLink != null || hashes.get(k) == null) continue;
          boolean first = true;
          for (int j = 0; j < k; j++) if (info.items.get(j).name.equals(it.name)) first = false;
          if (!first) continue;
          InputStream in = RarReader.open(f, it.name, pw);
          try { allSame &= Arrays.equals(MessageDigest.getInstance("SHA-256").digest(readAll(in)), hashes.get(k)); } finally { in.close(); }
        }
        check("fixture " + nm + ": open(name) equals the walk", allSame);
      }
      System.out.println("   fixture " + nm + ": " + info.format + ", " + info.items.size() + " entries" + (info.solid ? ", solid" : "") + (info.headerEncrypted ? ", header encrypted" : info.anyEncrypted ? ", encrypted" : "")
          + (pw != null ? ", password " + new String(pw) : "") + ", " + sizes[0] + " bytes");
      File exp = new File(dir, nm.replaceAll("\\.rar$", "") + ".exp");
      if (exp.isFile() && werr == null) checkExp(f, exp, info);
    }
    check("real fixtures of both generations were present (" + real4 + " RAR 1.5-4.x, " + real5 + " RAR 5)", real4 > 0 && real5 > 0);
  }

  /**
   * A solid RAR5 archive whose first entry cannot start (an unsupported compression version) used to end the whole walk with a NullPointerException:
   * the visitor met the error, then the walk read the same stream again to keep the solid state, and the source was still null. Now the entry fails with
   * an IOException the visitor sees, and the walk goes on to the next entry (which fails on its own, as the decoder state is gone).
   */
  static void testRar5SolidStartFailure() throws Exception {
    Lz5 z = new Lz5(true);
    z.beginFile(false); z.block(true); for (int i = 0; i < 50; i++) z.lit('a' + i % 20); z.endBlock(true);
    F5 a = new F5("a.bin").lz(z, false, 1);
    a.ver = 9;                                              // an unsupported compression version: listed, but start() throws
    z.beginFile(true); z.block(false); for (int i = 0; i < 20; i++) z.match(10, 5); z.endBlock(true);
    F5 b = new F5("b.bin").lz(z, true, 1);
    W5 w = new W5(); w.main(true); w.file(a); w.file(b); w.end();
    File f = new File(tmp, "solid-start-failure.rar");
    Files.write(f.toPath(), w.bytes());
    final List<String> seen = new ArrayList<String>();
    final List<String> errs = new ArrayList<String>();
    Throwable thrown = null;
    try {
      RarReader.walk(f, null, new RarReader.Visitor() {
        public boolean entry(RarReader.Item it, InputStream d) throws IOException {
          seen.add(it.name);
          try { readAll(d); errs.add(it.name + ": read"); } catch (IOException e) { errs.add(it.name + ": IOException"); }
          return true;
        }
      });
    } catch (Throwable t) { thrown = t; }
    check("a solid entry that cannot start does not end the walk with an exception (" + thrown + ")", thrown == null);
    check("both entries are visited and fail with an IOException (" + errs + ")", seen.size() == 2 && errs.size() == 2 && errs.get(0).endsWith("IOException") && errs.get(1).endsWith("IOException"));
    ZipTool.Archive ar = RarSource.open(f, null);
    List<String> problems = new ArrayList<String>();
    Throwable t2 = null;
    long[] res = null;
    try { res = ZipTool.extractTree(ar, "", new File(tmp, "solid-start-failure-out"), null, problems, null, FileOps.REPLACE); } catch (Throwable t) { t2 = t; }
    check("extracting it reports the entries as problems instead of crashing (" + t2 + ")", t2 == null && res != null && res[0] == 0 && problems.size() == 2);
  }

  /**
   * A solid entry that cannot start leaves the shared decoder on the state of the entry before it. The entry after it, which has no checksum to
   * catch the difference, must fail instead of being "extracted" from that stale state.
   */
  static void testRar5SolidStartFailureChain() throws Exception {
    Lz5 z = new Lz5(true);
    z.beginFile(false); z.block(true); for (int i = 0; i < 50; i++) z.lit('a' + i % 20); z.endBlock(true);
    F5 a = new F5("a.bin").lz(z, false, 1);
    z.beginFile(true); z.block(false); for (int i = 0; i < 30; i++) z.lit('A' + i % 20); z.endBlock(true);
    F5 b = new F5("b.bin").lz(z, true, 1);
    b.ver = 9;                                              // listed, but start() throws before the decoder is touched
    z.beginFile(true); z.block(false); for (int i = 0; i < 20; i++) z.match(10, 5); z.endBlock(true);
    F5 c = new F5("c.bin").lz(z, true, 1);
    c.crc = -1;                                             // no checksum
    W5 w = new W5(); w.main(true); w.file(a); w.file(b); w.file(c); w.end();
    File f = new File(tmp, "solid-start-failure-chain.rar");
    Files.write(f.toPath(), w.bytes());
    final List<String> res = new ArrayList<String>();
    Throwable thrown = null;
    try {
      RarReader.walk(f, null, new RarReader.Visitor() {
        public boolean entry(RarReader.Item it, InputStream d) throws IOException {
          try { readAll(d); res.add(it.name + " read"); } catch (IOException e) { res.add(it.name + " IOException"); }
          return true;
        }
      });
    } catch (Throwable t) { thrown = t; }
    check("a start failure in the middle of a solid chain does not end the walk (" + thrown + ")", thrown == null);
    check("a.bin reads, b.bin fails to start, and c.bin (no checksum) fails instead of decoding on stale state (" + res + ")",
        res.size() == 3 && res.get(0).equals("a.bin read") && res.get(1).equals("b.bin IOException") && res.get(2).equals("c.bin IOException"));
  }

  /** The .exp files of the rarfile project: "File: name" lines, or plain lists of names; every name must appear in the listing. */
  static void checkExp(File rar, File exp, RarReader.Info info) throws IOException {
    List<String> want = new ArrayList<String>();
    for (String line : new String(Files.readAllBytes(exp.toPath()), StandardCharsets.UTF_8).split("\n")) {
      line = line.replace("\r", "");
      if (line.startsWith("File: ")) want.add(line.substring(6).trim());
      else if (line.startsWith("Dir: ")) want.add(line.substring(5).trim());
    }
    if (want.isEmpty()) return;
    List<String> have = new ArrayList<String>();
    for (RarReader.Item it : info.items) have.add(it.dir && it.name.endsWith("/") ? it.name.substring(0, it.name.length() - 1) : it.name);
    boolean ok = true;
    for (String s : want) if (!have.contains(s.replace('\\', '/'))) { ok = false; System.out.println("   .exp name missing in the listing of " + rar.getName() + ": " + s); }
    check("fixture " + rar.getName() + ": the names of its .exp file are in the listing", ok);
  }
}
