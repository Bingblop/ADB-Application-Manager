package com.bloatware.bingblop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Password protection of zip entries (ZipCrypt): traditional ZipCrypto and WinZip AES, at the level of the raw entry data.
 * Interoperates with real tools: Info-ZIP zip / unzip (ZipCrypto) and pyzipper (WinZip AES, installed into a temp venv when missing; SKIP lines when
 * it cannot be had). Everything is written under the system temp dir.
 */
public class ZipCryptTest {
  static int fails = 0, n = 0, extZip = 0, extUnzip = 0, extPy = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static File tmp;
  static boolean haveZip, havePy;
  static String py;

  // pass phrases; PW_U has a 2 byte, 3 byte and 4 byte (surrogate pair) character and a space
  static final String PW = "s3cret-pw", PW_U = "p\u00e4ssw\u00f6rd-\u65e5\u672c\u8a9e-\uD83D\uDE00 x", BAD = "not-the-password";
  static char[] c(String s) { return s.toCharArray(); }

  // ------------------------------------------------------------------------------------------------------------ helpers
  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] b = new byte[7001];
    int k;
    while ((k = in.read(b)) > 0) o.write(b, 0, k);
    return o.toByteArray();
  }
  static byte[] inflate(InputStream in) throws IOException { return readAll(new InflaterInputStream(in, new Inflater(true))); }
  static byte[] deflate(byte[] d) throws IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    DeflaterOutputStream z = new DeflaterOutputStream(o, new Deflater(6, true));
    z.write(d); z.close();
    return o.toByteArray();
  }
  static long crc(byte[] d) { CRC32 c = new CRC32(); c.update(d); return c.getValue(); }
  static int u16(byte[] b, int p) { return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8; }
  static long u32(byte[] b, int p) { return (u16(b, p) | (long) u16(b, p + 2) << 16) & 0xFFFFFFFFL; }
  static void p16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >>> 8); }
  static void p32(ByteArrayOutputStream o, long v) { p16(o, (int) v); p16(o, (int) (v >>> 16)); }
  static void rm(File f) { if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File x : k) rm(x); } f.delete(); }
  static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
  static Exception thrown(IoRun r) { try { r.run(); return null; } catch (Exception e) { return e; } }
  interface IoRun { void run() throws Exception; }

  static int exec(File out, long seconds, String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp).redirectErrorStream(true).redirectOutput(out);
    pb.environment().put("LC_ALL", "C.UTF-8");
    Process p;
    try { p = pb.start(); } catch (IOException e) { return -998; }           // the program is not there
    p.getOutputStream().close();
    if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return -999; }
    return p.exitValue();
  }
  static String lastOut = "";
  static int sh(String script) throws Exception {
    File f = new File(tmp, "run.sh"), o = new File(tmp, "run.out");
    Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));       // bytes, so that a non-ASCII pass phrase reaches the tool as UTF-8
    int r = exec(o, 600, "sh", f.getPath());
    lastOut = new String(Files.readAllBytes(o.toPath()), StandardCharsets.UTF_8);
    return r;
  }

  // ------------------------------------------------------------------------------------------------------------ a small zip reader / writer
  static class Ent {
    String name; int flags, method, time; long crc, csize, usize; byte[] extra, raw;
    boolean descriptor;
  }
  static List<Ent> readZip(File f) throws IOException {
    byte[] z = Files.readAllBytes(f.toPath());
    int e = z.length - 22;
    while (e >= 0 && u32(z, e) != 0x06054b50L) e--;
    if (e < 0) throw new IOException("no end of central directory in " + f);
    int cnt = u16(z, e + 10), p = (int) u32(z, e + 16);
    List<Ent> r = new ArrayList<Ent>();
    for (int i = 0; i < cnt; i++) {
      if (u32(z, p) != 0x02014b50L) throw new IOException("bad central header");
      Ent x = new Ent();
      x.flags = u16(z, p + 8); x.method = u16(z, p + 10); x.time = u16(z, p + 12); x.crc = u32(z, p + 16); x.csize = u32(z, p + 20); x.usize = u32(z, p + 24);
      int nl = u16(z, p + 28), el = u16(z, p + 30), cl = u16(z, p + 32), lho = (int) u32(z, p + 42);
      x.name = new String(z, p + 46, nl, StandardCharsets.UTF_8);
      x.extra = Arrays.copyOfRange(z, p + 46 + nl, p + 46 + nl + el);
      x.descriptor = (x.flags & 8) != 0;
      int d = lho + 30 + u16(z, lho + 26) + u16(z, lho + 28);
      x.raw = Arrays.copyOfRange(z, d, d + (int) x.csize);
      r.add(x);
      p += 46 + nl + el + cl;
    }
    return r;
  }
  /** Writes entries (name, flags, method, time, crc, usize, extra, raw) as a zip; flag bit 3 entries get a data descriptor and zeros in the local header. */
  static void writeZip(File f, List<Ent> es) throws IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream(), cd = new ByteArrayOutputStream();
    for (Ent e : es) {
      byte[] nm = e.name.getBytes(StandardCharsets.UTF_8);
      int lho = o.size(), ver = e.method == 99 ? 51 : 20;
      boolean dd = (e.flags & 8) != 0;
      p32(o, 0x04034b50L); p16(o, ver); p16(o, e.flags); p16(o, e.method); p16(o, e.time); p16(o, 0x5821);
      p32(o, dd ? 0 : e.crc); p32(o, dd ? 0 : e.raw.length); p32(o, dd ? 0 : e.usize); p16(o, nm.length); p16(o, e.extra.length);
      o.write(nm); o.write(e.extra); o.write(e.raw);
      if (dd) { p32(o, 0x08074b50L); p32(o, e.crc); p32(o, e.raw.length); p32(o, e.usize); }
      p32(cd, 0x02014b50L); p16(cd, 63); p16(cd, ver); p16(cd, e.flags); p16(cd, e.method); p16(cd, e.time); p16(cd, 0x5821);
      p32(cd, e.crc); p32(cd, e.raw.length); p32(cd, e.usize); p16(cd, nm.length); p16(cd, e.extra.length); p16(cd, 0); p16(cd, 0); p16(cd, 0);
      p32(cd, 0); p32(cd, lho); cd.write(nm); cd.write(e.extra);
    }
    int cdOff = o.size();
    byte[] cdb = cd.toByteArray();
    o.write(cdb, 0, cdb.length);
    p32(o, 0x06054b50L); p16(o, 0); p16(o, 0); p16(o, es.size()); p16(o, es.size()); p32(o, cdb.length); p32(o, cdOff); p16(o, 0);
    Files.write(f.toPath(), o.toByteArray());
  }

  // ------------------------------------------------------------------------------------------------------------ test data
  static byte[] text(int len, int seed) {
    StringBuilder s = new StringBuilder();
    Random r = new Random(seed);
    String[] w = { "alpha", "beta", "gamma", "delta", "zip", "crypt", "entry", "milk", "eggs", "\u00fcber" };
    while (s.length() < len) s.append(w[r.nextInt(w.length)]).append(r.nextInt(5) == 0 ? "\n" : " ");
    return Arrays.copyOf(s.toString().getBytes(StandardCharsets.UTF_8), len);
  }
  static byte[] random(int len, long seed) { byte[] b = new byte[len]; new Random(seed).nextBytes(b); return b; }
  static byte[] big() {                                                       // over 5 MB, mostly incompressible
    int len = 7 * 1024 * 1024 + 123;
    byte[] b = new byte[len], r = random(len - len / 5, 8), t = text(len / 5, 9);        // 80% incompressible: well over 5 MB of ciphertext
    System.arraycopy(r, 0, b, 0, r.length);
    System.arraycopy(t, 0, b, r.length, len - r.length);
    return b;
  }

  static final String[] NAMES = { "small.txt", "text.txt", "bin.dat", "empty.txt" };
  static byte[][] DATA;

  // ------------------------------------------------------------------------------------------------------------ the decrypt helpers under test
  /** Decrypts (and inflates when method 8) a traditionally encrypted entry the way ZipTool will, checking the CRC. */
  static byte[] tradOpen(Ent e, char[] pw) throws IOException {
    int cb = (e.flags & 8) != 0 ? ZipCrypt.checkByteFromDosTime(e.time) : ZipCrypt.checkByteFromCrc(e.crc);
    InputStream in = ZipCrypt.decryptTraditional(new ByteArrayInputStream(e.raw), pw, cb);
    byte[] d = e.method == 8 ? inflate(in) : readAll(in);
    if (crc(d) != e.crc) throw new IOException("crc mismatch");
    return d;
  }
  static byte[] aesOpen(Ent e, char[] pw) throws IOException {
    int[] x = ZipCrypt.parseAesExtra(e.extra);
    if (x == null || e.method != 99) throw new IOException("not an AES entry");
    InputStream in = ZipCrypt.decryptAes(new ByteArrayInputStream(e.raw), e.raw.length, pw, x[1]);
    byte[] d = x[2] == 8 ? inflate(in) : readAll(in);
    if (x[0] == 1 && crc(d) != e.crc) throw new IOException("crc mismatch");
    return d;
  }
  static int indexOf(String name) { for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i; return -1; }

  public static void main(String[] args) throws Exception {
    tmp = Files.createTempDirectory("zipcrypt-").toFile();
    try { run(); } finally { rm(tmp); }
    System.out.println("external tools: zip -P " + extZip + " entries decrypted, unzip -P " + extUnzip + " archives accepted, pyzipper " + extPy + " archives (read or written)");
    System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  static void run() throws Exception {
    DATA = new byte[][] { "hello zipcrypt\n".getBytes(StandardCharsets.UTF_8), text(300 * 1000, 1), random(100000, 2), new byte[0] };
    File data = new File(tmp, "data"); data.mkdirs();
    for (int i = 0; i < NAMES.length; i++) Files.write(new File(data, NAMES[i]).toPath(), DATA[i]);
    byte[] big = big();
    Files.write(new File(data, "big.bin").toPath(), big);

    haveZip = exec(new File(tmp, "w.out"), 20, "sh", "-c", "command -v zip && command -v unzip") == 0;
    if (!haveZip) System.out.println("SKIP zip / unzip not installed: the ZipCrypto interop checks are skipped");
    py = findPython();
    havePy = py != null;
    if (!havePy) System.out.println("SKIP pyzipper is not available (python3 -m venv + pip install pyzipper failed): the WinZip AES interop checks are skipped");

    units();
    traditionalExternal(data, big);
    traditionalOurs(big);
    aesFromPyzipper(data, big);
    aesOurs(big);
    aesDamage();
    streaming();
  }

  // ------------------------------------------------------------------------------------------------------------ pure checks
  static void units() throws Exception {
    check("aesOverhead 128 / 192 / 256 bit: salt + 2 + 10", ZipCrypt.aesOverhead(1) == 20 && ZipCrypt.aesOverhead(2) == 24 && ZipCrypt.aesOverhead(3) == 28);
    check("aes salt and key lengths", ZipCrypt.aesSaltLength(1) == 8 && ZipCrypt.aesSaltLength(2) == 12 && ZipCrypt.aesSaltLength(3) == 16
        && ZipCrypt.aesKeyLength(1) == 16 && ZipCrypt.aesKeyLength(2) == 24 && ZipCrypt.aesKeyLength(3) == 32);
    check("a strength outside 1..3 is refused", thrown(new IoRun() { public void run() { ZipCrypt.aesOverhead(4); } }) instanceof IllegalArgumentException
        && thrown(new IoRun() { public void run() throws Exception { ZipCrypt.encryptAes(new ByteArrayOutputStream(), c(PW), 0); } }) instanceof IllegalArgumentException);
    check("the extra field is 0x9901, size 7, version 2, AE, strength 3, method 8", Arrays.equals(ZipCrypt.aesExtra(3, 8, true), new byte[] { 1, (byte) 0x99, 7, 0, 2, 0, 'A', 'E', 3, 8, 0 }));
    check("AE-1 extra and a method above 255", Arrays.equals(ZipCrypt.aesExtra(1, 0x1234, false), new byte[] { 1, (byte) 0x99, 7, 0, 1, 0, 'A', 'E', 1, 0x34, 0x12 }));
    check("parse reads back version, strength, method", Arrays.equals(ZipCrypt.parseAesExtra(ZipCrypt.aesExtra(2, 12, false)), new int[] { 1, 2, 12 }));
    byte[] other = { 0x75, 0x70, 3, 0, 1, 2, 3 };
    ByteArrayOutputStream mixed = new ByteArrayOutputStream();
    mixed.write(other); mixed.write(ZipCrypt.aesExtra(3, 8, true)); mixed.write(other);
    check("parse finds the field between other fields", Arrays.equals(ZipCrypt.parseAesExtra(mixed.toByteArray()), new int[] { 2, 3, 8 }));
    check("parse: none, empty, null", ZipCrypt.parseAesExtra(other) == null && ZipCrypt.parseAesExtra(new byte[0]) == null && ZipCrypt.parseAesExtra(null) == null);
    byte[] vendor = ZipCrypt.aesExtra(3, 8, true); vendor[6] = 'X';
    check("parse: another vendor is not WinZip AES", ZipCrypt.parseAesExtra(vendor) == null);
    byte[] cut = Arrays.copyOf(ZipCrypt.aesExtra(3, 8, true), 9);
    check("parse: a cut field is not read", ZipCrypt.parseAesExtra(cut) == null);
    byte[] short6 = ZipCrypt.aesExtra(3, 8, true); short6[2] = 6;
    check("parse: a field shorter than 7 bytes is not read", ZipCrypt.parseAesExtra(Arrays.copyOf(short6, 10)) == null);
    byte[] bad = ZipCrypt.aesExtra(3, 8, true); bad[8] = 4;
    check("parse: strength 4 is not one of ours", ZipCrypt.parseAesExtra(bad) == null);
    check("UTF-8 pass phrase bytes (incl. a surrogate pair)", Arrays.equals(ZipCrypt.passwordBytes(c(PW_U)), PW_U.getBytes(StandardCharsets.UTF_8)));
    check("empty pass phrase has no bytes", ZipCrypt.passwordBytes(new char[0]).length == 0);
    check("check bytes", ZipCrypt.checkByteFromCrc(0xAB123456L) == 0xAB && ZipCrypt.checkByteFromDosTime(0x7A3C) == 0x7A);

    // the caller's pass phrase array is left alone
    char[] pw = c(PW_U), copy = pw.clone();
    byte[] raw = ZipCrypt.encryptTraditional(DATA[0], pw, crc(DATA[0]));
    ZipCrypt.decryptTraditional(new ByteArrayInputStream(raw), pw, ZipCrypt.checkByteFromCrc(crc(DATA[0]))).close();
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    OutputStream o = ZipCrypt.encryptAes(sink, pw, 2); o.write(1); o.close();
    check("the pass phrase array is not modified", Arrays.equals(pw, copy));

    // finish() does not close the underlying stream, close() does
    final boolean[] closed = { false };
    OutputStream under = new ByteArrayOutputStream() { @Override public void close() { closed[0] = true; } };
    ZipCrypt.AesOutputStream a = ZipCrypt.encryptAes(under, c(PW), 1);
    a.write(new byte[5]); a.finish(); a.finish();
    check("finish() leaves the underlying stream open and writes the code once", !closed[0] && ((ByteArrayOutputStream) under).size() == 5 + ZipCrypt.aesOverhead(1));
    check("no writing after finish()", thrown(afterFinish(a)) instanceof IOException);
    a.close();
    check("close() closes the underlying stream", closed[0]);
    closed[0] = false;
    ZipCrypt.TraditionalOutputStream t = ZipCrypt.encryptTraditional(under, c(PW), 0x55);
    t.finish();
    check("traditional finish() does not close, close() does", !closed[0] && thrownClose(t) == null && closed[0]);

    // a stream of the traditional header only (empty entry) and a header that is cut short
    byte[] hdr = ZipCrypt.encryptTraditional(new byte[0], c(PW), 0x12345678L);
    check("an empty traditional entry is just the 12 byte header", hdr.length == 12);
    check("and decrypts to nothing", readAll(ZipCrypt.decryptTraditional(new ByteArrayInputStream(hdr), c(PW), 0x12)).length == 0);
    check("a traditional entry shorter than 12 bytes is an IOException", thrownTrad(Arrays.copyOf(hdr, 7)) instanceof EOFException);
    check("check byte -1 skips the check", thrownTrad2(hdr, c(BAD)) == null);

    // round trips, no tools: both schemes, odd read and write sizes
    for (int s = 1; s <= 3; s++)
      for (int i = 0; i < NAMES.length; i++) {
        byte[] d = DATA[i];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipCrypt.AesOutputStream ao = ZipCrypt.encryptAes(bo, c(PW_U), s);
        int off = 0, step = 1;
        while (off < d.length) { int k = Math.min(step, d.length - off); ao.write(d, off, k); off += k; step = step * 3 + 1; if (step > 9000) step = 1; }
        ao.close();
        byte[] enc = bo.toByteArray();
        check("AES strength " + s + " " + NAMES[i] + ": length is data + overhead", enc.length == d.length + ZipCrypt.aesOverhead(s));
        check("AES strength " + s + " " + NAMES[i] + ": ciphertext is not the data", d.length < 16 || !Arrays.equals(Arrays.copyOfRange(enc, ZipCrypt.aesSaltLength(s) + 2, ZipCrypt.aesSaltLength(s) + 2 + 16), Arrays.copyOf(d, 16)));
        check("AES strength " + s + " " + NAMES[i] + ": round trip", Arrays.equals(readAll(ZipCrypt.decryptAes(new ByteArrayInputStream(enc), enc.length, c(PW_U), s)), d));
        InputStream one = ZipCrypt.decryptAes(new ByteArrayInputStream(enc), enc.length, c(PW_U), s);
        ByteArrayOutputStream by = new ByteArrayOutputStream();
        int b;
        while ((b = one.read()) >= 0) by.write(b);
        check("AES strength " + s + " " + NAMES[i] + ": read one byte at a time", Arrays.equals(by.toByteArray(), d) && one.read() == -1);
        byte[] te = ZipCrypt.encryptTraditional(d, c(PW_U), crc(d));
        check("traditional " + NAMES[i] + ": round trip, 12 bytes added", te.length == d.length + 12 && Arrays.equals(readAll(ZipCrypt.decryptTraditional(new ByteArrayInputStream(te), c(PW_U), ZipCrypt.checkByteFromCrc(crc(d)))), d));
        bo = new ByteArrayOutputStream();
        ZipCrypt.TraditionalOutputStream to = ZipCrypt.encryptTraditional(bo, c(PW_U), 0x7A);
        for (int k = 0; k < d.length; k++) to.write(d[k]);
        to.close();
        check("traditional " + NAMES[i] + ": stream round trip", Arrays.equals(readAll(ZipCrypt.decryptTraditional(new ByteArrayInputStream(bo.toByteArray()), c(PW_U), 0x7A)), d));
      }
    ByteArrayOutputStream two = new ByteArrayOutputStream();
    OutputStream x = ZipCrypt.encryptAes(two, c(PW), 3); x.write(DATA[1]); x.close();
    ByteArrayOutputStream two2 = new ByteArrayOutputStream();
    x = ZipCrypt.encryptAes(two2, c(PW), 3); x.write(DATA[1]); x.close();
    check("two encryptions of the same data differ (random salt)", !Arrays.equals(two.toByteArray(), two2.toByteArray()));
  }
  static IoRun afterFinish(final ZipCrypt.AesOutputStream a) { return new IoRun() { public void run() throws Exception { a.write(1); } }; }
  static Exception thrownClose(final ZipCrypt.TraditionalOutputStream t) { return thrown(new IoRun() { public void run() throws Exception { t.close(); } }); }
  static Exception thrownTrad(final byte[] raw) { return thrown(new IoRun() { public void run() throws Exception { ZipCrypt.decryptTraditional(new ByteArrayInputStream(raw), c(PW), 0); } }); }
  static Exception thrownTrad2(final byte[] raw, final char[] pw) { return thrown(new IoRun() { public void run() throws Exception { ZipCrypt.decryptTraditional(new ByteArrayInputStream(raw), pw, -1); } }); }

  // ------------------------------------------------------------------------------------------------------------ traditional: archives made by Info-ZIP
  static void traditionalExternal(File data, byte[] big) throws Exception {
    if (!haveZip) return;
    String[][] cases = {
        { "defl", "-6", PW }, { "stored", "-0", PW }, { "unicode", "-6", PW_U },
    };
    for (String[] cs : cases) {
      File z = new File(tmp, "zip_" + cs[0] + ".zip");
      int r = sh("cd data && zip -q " + cs[1] + " -P " + q(cs[2]) + " " + q(z.getPath()) + " small.txt text.txt bin.dat empty.txt big.bin 2>&1");
      check("zip -P (" + cs[0] + ") ran: " + lastOut.trim(), r == 0);
      if (r != 0) continue;
      List<Ent> es = readZip(z);
      check("zip -P (" + cs[0] + ") made five entries", es.size() == 5);
      for (Ent e : es) {
        byte[] want = e.name.equals("big.bin") ? big : DATA[indexOf(e.name)];
        check("zip -P (" + cs[0] + ") " + e.name + " is flagged encrypted", (e.flags & 1) != 0);
        byte[] got = null;
        Exception ex = null;
        try { got = tradOpen(e, c(cs[2])); } catch (Exception t) { ex = t; }
        check("zip -P (" + cs[0] + ") " + e.name + " decrypts to the original" + (ex != null ? " (" + ex + ")" : ""), got != null && Arrays.equals(got, want));
        if (got != null) extZip++;
        check("zip -P (" + cs[0] + ") " + e.name + ": the raw length is the data plus the 12 byte header (stored)", e.method != 0 || e.raw.length == want.length + 12);
        // wrong pass phrases: the check byte catches 255 of 256, and what slips through fails the CRC
        if (e.name.equals("small.txt") || e.name.equals("empty.txt")) {
          int rejected = 0, crcFailed = 0, accepted = 0;
          for (int i = 0; i < 400; i++) {
            try { tradOpen(e, c(BAD + i)); accepted++; }
            catch (ZipCrypt.WrongPassword w) { rejected++; }
            catch (IOException w) { crcFailed++; }
          }
          check("zip -P (" + cs[0] + ") " + e.name + ": wrong pass phrases are rejected by the check byte (" + rejected + " of 400)", rejected >= 385);
          // what slips past the check byte fails the CRC; only an empty entry (header, no data) has nothing to check
          check("zip -P (" + cs[0] + ") " + e.name + ": a wrong pass phrase never yields the data", e.name.equals("empty.txt") || accepted == 0);
        }
      }
      // the same archive through unzip, to be sure the fixture is what we think it is
      Ent small = es.get(0);
      check("zip -P (" + cs[0] + ") wrong pass phrase on a text entry is WrongPassword (or, 1 in 256, a CRC failure)", wrongOrCrc(small, c(BAD)));
    }
    // streaming from stdin: Info-ZIP then uses a data descriptor and the DOS time as the check byte
    int r = sh("cd data && cat text.txt | zip -q -P " + q(PW) + " " + q(new File(tmp, "zip_stdin.zip").getPath()) + " - 2>&1");
    if (r == 0) {
      List<Ent> es = readZip(new File(tmp, "zip_stdin.zip"));
      byte[] got = null;
      try { got = tradOpen(es.get(0), c(PW)); } catch (Exception e) { System.out.println("  stdin zip: " + e); }
      check("zip -P from stdin (flag bit 3 = " + ((es.get(0).flags & 8) != 0) + ") decrypts", got != null && Arrays.equals(got, DATA[1]));
      if (got != null) extZip++;
    }
  }
  static boolean wrongOrCrc(Ent e, char[] pw) {
    try { tradOpen(e, pw); return false; }
    catch (ZipCrypt.WrongPassword w) { return true; }
    catch (IOException w) { return true; }
  }

  // ------------------------------------------------------------------------------------------------------------ traditional: our output read by unzip
  static void traditionalOurs(byte[] big) throws Exception {
    // entries: {name, data, method, timeVariant}
    String[] nm = { "a_small.txt", "b_text.txt", "c_bin.dat", "d_empty.txt", "e_big.bin", "f_text_time.txt", "g_small_time.txt", "h_empty_time.txt" };
    byte[][] dt = { DATA[0], DATA[1], DATA[2], DATA[3], big, DATA[1], DATA[0], DATA[3] };
    int[] meth = { 8, 8, 0, 0, 8, 8, 0, 8 };
    boolean[] time = { false, false, false, false, false, true, true, true };
    for (String pw : new String[] { PW, PW_U }) {
      List<Ent> es = new ArrayList<Ent>();
      for (int i = 0; i < nm.length; i++) {
        Ent e = new Ent();
        e.name = nm[i]; e.method = meth[i]; e.crc = crc(dt[i]); e.usize = dt[i].length; e.extra = new byte[0];
        e.time = 0x7A3C ^ (i << 8) ;                                         // the high byte differs from the CRC's, so a mixed up check byte fails
        e.flags = time[i] ? 9 : 1;
        byte[] body = meth[i] == 8 ? deflate(dt[i]) : dt[i];
        int cb = time[i] ? ZipCrypt.checkByteFromDosTime(e.time) : ZipCrypt.checkByteFromCrc(e.crc);
        if (i % 2 == 0 && !time[i]) e.raw = ZipCrypt.encryptTraditional(body, c(pw), e.crc);             // whole array
        else {                                                                                            // streamed
          ByteArrayOutputStream bo = new ByteArrayOutputStream();
          ZipCrypt.TraditionalOutputStream t = ZipCrypt.encryptTraditional(bo, c(pw), cb);
          for (int off = 0; off < body.length; off += 777) t.write(body, off, Math.min(777, body.length - off));
          t.close();
          e.raw = bo.toByteArray();
        }
        check("traditional zip " + nm[i] + ": raw length is body + 12", e.raw.length == body.length + 12);
        byte[] back = tradOpen(e, c(pw));
        check("traditional zip " + nm[i] + " reads back in Java", Arrays.equals(back, dt[i]));
        es.add(e);
      }
      File z = new File(tmp, "ours_trad.zip");
      writeZip(z, es);
      if (!haveZip) continue;
      int r = sh("unzip -t -P " + q(pw) + " " + q(z.getPath()) + " 2>&1");
      check("unzip -t accepts our ZipCrypto archive (" + (pw == PW ? "ascii" : "unicode") + " pass phrase): " + lastOut.replace('\n', ' ').trim(), r == 0);
      if (r == 0) extUnzip++;
      File out = new File(tmp, "trad_out"); rm(out); out.mkdirs();
      r = sh("cd " + q(out.getPath()) + " && unzip -q -o -P " + q(pw) + " " + q(z.getPath()) + " 2>&1");
      boolean same = r == 0;
      for (int i = 0; i < nm.length && same; i++) same = Arrays.equals(Files.readAllBytes(new File(out, nm[i]).toPath()), dt[i]);
      check("unzip -P extracts every entry we made, byte for byte (" + (pw == PW ? "ascii" : "unicode") + ")", same);
      r = sh("unzip -t -P " + q(BAD) + " " + q(z.getPath()) + " 2>&1");
      check("unzip with a wrong pass phrase fails", r != 0);
    }
  }

  // ------------------------------------------------------------------------------------------------------------ pyzipper
  static String findPython() throws Exception {
    File o = new File(tmp, "py.out");
    List<String> cands = new ArrayList<String>();
    if (System.getenv("ZIPCRYPT_PYTHON") != null) cands.add(System.getenv("ZIPCRYPT_PYTHON"));
    cands.add("python3");
    File venv = new File(System.getProperty("java.io.tmpdir"), "zipcrypt-test-venv");
    cands.add(new File(venv, "bin/python").getPath());
    for (String p : cands) if (exec(o, 60, p, "-c", "import pyzipper") == 0) return p;
    // make a venv in the temp dir and install pyzipper into it
    if (exec(o, 120, "python3", "-m", "venv", venv.getPath()) != 0) return null;
    if (exec(o, 300, new File(venv, "bin/pip").getPath(), "install", "-q", "pyzipper") != 0) return null;
    String p = new File(venv, "bin/python").getPath();
    return exec(o, 60, p, "-c", "import pyzipper") == 0 ? p : null;
  }
  static final String PYZ =
      "import sys, os, pyzipper\n"
    + "mode = sys.argv[1]\n"
    + "if mode == 'make':\n"
    + "    out, pwfile, bits, comp, ver = sys.argv[2:7]\n"
    + "    pw = open(pwfile, 'rb').read()\n"
    + "    kw = {'nbits': int(bits)}\n"
    + "    if ver != '0': kw['force_wz_aes_version'] = int(ver)\n"
    + "    with pyzipper.AESZipFile(out, 'w', compression=int(comp), encryption=pyzipper.WZ_AES) as z:\n"
    + "        z.setpassword(pw)\n"
    + "        z.setencryption(pyzipper.WZ_AES, **kw)\n"
    + "        for item in sys.argv[7:]:\n"
    + "            name, path = item.split('=', 1)\n"
    + "            z.writestr(name, open(path, 'rb').read())\n"
    + "elif mode == 'read':\n"
    + "    zf, pwfile, outdir = sys.argv[2:5]\n"
    + "    pw = open(pwfile, 'rb').read()\n"
    + "    with pyzipper.AESZipFile(zf) as z:\n"
    + "        z.setpassword(pw)\n"
    + "        for i, info in enumerate(z.infolist()):\n"
    + "            try:\n"
    + "                d = z.read(info)\n"
    + "            except RuntimeError as e:\n"
    + "                print('RuntimeError', e)\n"
    + "                sys.exit(3 if 'password' in str(e).lower() else 4)\n"
    + "            except Exception as e:\n"
    + "                print(type(e).__name__, e)\n"
    + "                sys.exit(4)\n"
    + "            open(os.path.join(outdir, str(i)), 'wb').write(d)\n";

  static int pyz(String... args) throws Exception {
    File script = new File(tmp, "pyz.py");
    if (!script.exists()) Files.write(script.toPath(), PYZ.getBytes(StandardCharsets.UTF_8));
    List<String> cmd = new ArrayList<String>();
    cmd.add(py); cmd.add(script.getPath());
    cmd.addAll(Arrays.asList(args));
    File o = new File(tmp, "pyz.out");
    int r = exec(o, 900, cmd.toArray(new String[0]));
    lastOut = new String(Files.readAllBytes(o.toPath()), StandardCharsets.UTF_8);
    return r;
  }
  static File pwFile(String pw) throws IOException { File f = new File(tmp, "pw.bin"); Files.write(f.toPath(), pw.getBytes(StandardCharsets.UTF_8)); return f; }

  static void aesFromPyzipper(File data, byte[] big) throws Exception {
    if (!havePy) return;
    int[] bitsOf = { 0, 128, 192, 256 };
    int idx = 0;
    for (int s = 1; s <= 3; s++)
      for (int ver = 0; ver <= 2; ver++)
        for (int comp : new int[] { 8, 0 }) {
          if (comp == 0 && ver != 0) continue;
          String pw = (s == 3 && ver == 0 && comp == 8) ? PW_U : PW;
          File z = new File(tmp, "py_" + (idx++) + ".zip");
          List<String> a = new ArrayList<String>(Arrays.asList("make", z.getPath(), pwFile(pw).getPath(), String.valueOf(bitsOf[s]), String.valueOf(comp), String.valueOf(ver)));
          for (String nm : NAMES) a.add(nm + "=" + new File(data, nm).getPath());
          int r = pyz(a.toArray(new String[0]));
          check("pyzipper made AES-" + bitsOf[s] + " ver " + ver + " method " + comp + ": " + lastOut.trim(), r == 0);
          if (r != 0) continue;
          extPy++;
          String tag = "pyzipper AES-" + bitsOf[s] + " ver " + ver + " method " + comp + (pw == PW_U ? " unicode" : "");
          List<Ent> es = readZip(z);
          check(tag + ": four entries", es.size() == 4);
          for (Ent e : es) {
            int[] x = ZipCrypt.parseAesExtra(e.extra);
            check(tag + " " + e.name + ": the 0x9901 field is found and says strength " + s + ", method " + (comp == 0 ? 0 : 8) + ", version " + (ver == 0 ? "1 or 2" : ver),
                x != null && x[1] == s && x[2] == comp && (ver == 0 ? x[0] == 1 || x[0] == 2 : x[0] == ver) && e.method == 99);
            if (x != null && x[0] == 2) check(tag + " " + e.name + ": AE-2 has CRC 0", e.crc == 0);
            byte[] got = null; Exception ex = null;
            try { got = aesOpen(e, c(pw)); } catch (Exception t) { ex = t; }
            check(tag + " " + e.name + ": decrypts to the original" + (ex != null ? " (" + ex + ")" : ""), got != null && Arrays.equals(got, DATA[indexOf(e.name)]));
            check(tag + " " + e.name + ": raw length is the compressed data + " + ZipCrypt.aesOverhead(s), e.raw.length == e.csize);
            // a wrong pass phrase (verifier is 2 bytes: allow one lucky pass out of two)
            int rej = 0;
            for (String w : new String[] { BAD, BAD + "!" }) {
              try { ZipCrypt.decryptAes(new ByteArrayInputStream(e.raw), e.raw.length, c(w), s).close(); } catch (ZipCrypt.WrongPassword wp) { rej++; }
            }
            check(tag + " " + e.name + ": a wrong pass phrase is WrongPassword", rej >= 1);
            if (ex == null && e.name.equals("text.txt") && s == 1 && ver == 0 && comp == 8) {
              // the right pass phrase with the wrong strength is a wrong password too, not garbage
              boolean wrongStrength = false;
              try { ZipCrypt.decryptAes(new ByteArrayInputStream(e.raw), e.raw.length, c(pw), 2); } catch (ZipCrypt.WrongPassword wp) { wrongStrength = true; } catch (IOException io) { wrongStrength = true; }
              check(tag + " " + e.name + ": the wrong strength does not decrypt", wrongStrength);
            }
          }
        }
    // over 5 MB, strength 2
    File z = new File(tmp, "py_big.zip");
    int r = pyz("make", z.getPath(), pwFile(PW).getPath(), "192", "8", "2", "big.bin=" + new File(data, "big.bin").getPath());
    check("pyzipper made a >5 MB AES-192 entry: " + lastOut.trim(), r == 0);
    if (r == 0) {
      extPy++;
      Ent e = readZip(z).get(0);
      long t0 = System.nanoTime();
      byte[] got = null; Exception ex = null;
      try { got = aesOpen(e, c(PW)); } catch (Exception t) { ex = t; }
      check("pyzipper AES-192 7 MB entry decrypts to the original" + (ex != null ? " (" + ex + ")" : ""), got != null && Arrays.equals(got, big));
      System.out.println("  decrypt + inflate of a " + big.length / 1048576 + " MB AES-192 entry: " + (System.nanoTime() - t0) / 1000000 + " ms");
    }
  }

  // ------------------------------------------------------------------------------------------------------------ AES: our output read by pyzipper
  static byte[] aesEncrypt(byte[] body, String pw, int strength, boolean chunked) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    ZipCrypt.AesOutputStream a = ZipCrypt.encryptAes(bo, c(pw), strength);
    if (chunked) for (int off = 0; off < body.length; off += 1000) a.write(body, off, Math.min(1000, body.length - off));
    else a.write(body);
    a.close();
    return bo.toByteArray();
  }

  static void aesOurs(byte[] big) throws Exception {
    int[] bitsOf = { 0, 128, 192, 256 };
    for (int s = 1; s <= 3; s++)
      for (int ae2 = 0; ae2 <= 1; ae2++) {
        String pw = (s == 2) ? PW_U : PW;
        String tag = "our AES-" + bitsOf[s] + (ae2 == 1 ? " AE-2" : " AE-1") + (pw == PW_U ? " unicode" : "");
        List<Ent> es = new ArrayList<Ent>();
        for (int i = 0; i < NAMES.length; i++) {
          int method = (i % 2 == 0) ? 0 : 8;                                // small + empty stored, text + bin deflated
          byte[] body = method == 8 ? deflate(DATA[i]) : DATA[i];
          Ent e = new Ent();
          e.name = NAMES[i]; e.flags = 1; e.method = 99; e.time = 0x6000; e.usize = DATA[i].length;
          e.crc = ae2 == 1 ? 0 : crc(DATA[i]);
          e.extra = ZipCrypt.aesExtra(s, method, ae2 == 1);
          e.raw = aesEncrypt(body, pw, s, i % 2 == 1);
          check(tag + " " + NAMES[i] + ": raw length is body + overhead", e.raw.length == body.length + ZipCrypt.aesOverhead(s));
          es.add(e);
        }
        File z = new File(tmp, "ours_aes_" + s + ae2 + ".zip");
        writeZip(z, es);
        List<Ent> back = readZip(z);
        boolean allBack = back.size() == 4;
        for (Ent e : back) allBack &= Arrays.equals(aesOpen(e, c(pw)), DATA[indexOf(e.name)]);
        check(tag + ": the archive reads back in Java", allBack);
        if (!havePy) continue;
        File out = new File(tmp, "py_out"); rm(out); out.mkdirs();
        int r = pyz("read", z.getPath(), pwFile(pw).getPath(), out.getPath());
        check(tag + ": pyzipper reads every entry with the pass phrase (exit " + r + " " + lastOut.trim() + ")", r == 0);
        boolean same = r == 0;
        for (int i = 0; i < NAMES.length && same; i++) same = Arrays.equals(Files.readAllBytes(new File(out, String.valueOf(i)).toPath()), DATA[indexOf(back.get(i).name)]);
        check(tag + ": and what it reads is the original data", same);
        if (r == 0) extPy++;
        r = pyz("read", z.getPath(), pwFile(BAD).getPath(), out.getPath());
        check(tag + ": pyzipper reports a wrong pass phrase as a bad password (exit " + r + " " + lastOut.trim() + ")", r == 3);
      }
    // over 5 MB with strength 3, AE-2, deflated, written in odd pieces through a DeflaterOutputStream like ZipTool will
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    ZipCrypt.AesOutputStream a = ZipCrypt.encryptAes(bo, c(PW), 3);
    DeflaterOutputStream d = new DeflaterOutputStream(a, new Deflater(6, true));
    for (int off = 0; off < big.length; off += 65537) d.write(big, off, Math.min(65537, big.length - off));
    d.close();
    Ent e = new Ent();
    e.name = "big.bin"; e.flags = 1; e.method = 99; e.time = 0; e.crc = 0; e.usize = big.length; e.extra = ZipCrypt.aesExtra(3, 8, true); e.raw = bo.toByteArray();
    check("a >5 MB entry: more than 5 MB of ciphertext", e.raw.length > 5 * 1024 * 1024);
    check("a >5 MB entry reads back in Java", Arrays.equals(aesOpen(e, c(PW)), big));
    File z = new File(tmp, "ours_aes_big.zip");
    writeZip(z, Arrays.asList(e));
    if (havePy) {
      File out = new File(tmp, "py_out_big"); rm(out); out.mkdirs();
      int r = pyz("read", z.getPath(), pwFile(PW).getPath(), out.getPath());
      check("pyzipper reads our >5 MB AES-256 entry (exit " + r + " " + lastOut.trim() + ")", r == 0 && Arrays.equals(Files.readAllBytes(new File(out, "0").toPath()), big));
      if (r == 0) extPy++;
    }
  }

  // ------------------------------------------------------------------------------------------------------------ damage
  static Exception readFails(byte[] raw, long len, String pw, int s) {
    try { readAll(ZipCrypt.decryptAes(new ByteArrayInputStream(raw), len, c(pw), s)); return null; } catch (Exception e) { return e; }
  }
  static boolean authFailed(Exception e) { return e instanceof IOException && !(e instanceof ZipCrypt.WrongPassword) && "authentication failed".equals(e.getMessage()); }

  static void aesDamage() throws Exception {
    for (int s = 1; s <= 3; s++) {
      int salt = ZipCrypt.aesSaltLength(s), head = salt + 2;
      byte[] body = deflate(DATA[1]);
      byte[] raw = aesEncrypt(body, PW, s, false);
      check("strength " + s + ": untouched data reads", readFails(raw, raw.length, PW, s) == null);
      for (int where : new int[] { head, head + raw.length / 3, raw.length - 11 }) {            // first, a middle and the last ciphertext byte
        byte[] t = raw.clone(); t[where] ^= 0x01;
        Exception ex = readFails(t, t.length, PW, s);
        check("strength " + s + ": a flipped ciphertext byte at " + where + " fails authentication (" + ex + ")", authFailed(ex));
      }
      for (int where : new int[] { raw.length - 10, raw.length - 1 }) {
        byte[] t = raw.clone(); t[where] ^= (byte) 0x80;
        check("strength " + s + ": a flipped authentication code byte at " + where + " fails authentication", authFailed(readFails(t, t.length, PW, s)));
      }
      byte[] t = raw.clone(); t[0] ^= 0x01;
      Exception ex = readFails(t, t.length, PW, s);
      check("strength " + s + ": a flipped salt byte is a wrong password or an authentication failure (" + ex + ")", ex instanceof ZipCrypt.WrongPassword || authFailed(ex));
      t = raw.clone(); t[salt] ^= 0x01;
      check("strength " + s + ": a flipped verifier byte is a wrong password", readFails(t, t.length, PW, s) instanceof ZipCrypt.WrongPassword);
      // the exception comes from the read that returns the last bytes; later reads keep failing, never return data
      InputStream in = ZipCrypt.decryptAes(new ByteArrayInputStream(flip(raw, raw.length - 11)), raw.length, c(PW), s);
      Exception first = null, again = null;
      try { readAll(in); } catch (Exception e) { first = e; }
      try { in.read(new byte[16]); } catch (Exception e) { again = e; }
      check("strength " + s + ": after a failure the stream keeps failing", authFailed(first) && again instanceof IOException);
      // short data
      ex = readFails(Arrays.copyOf(raw, raw.length - 5), raw.length, PW, s);
      check("strength " + s + ": data cut short is an IOException (" + ex + ")", ex instanceof EOFException);
      ex = readFails(Arrays.copyOf(raw, raw.length - 1), raw.length, PW, s);
      check("strength " + s + ": a missing last code byte is an IOException", ex instanceof EOFException);
      ex = readFails(raw, raw.length - 1, PW, s);
      check("strength " + s + ": a declared length one short fails authentication (the code is read from the wrong place)", authFailed(ex));
      check("strength " + s + ": shorter than salt + verifier + code is refused", thrown(shortAes(raw, ZipCrypt.aesOverhead(s) - 1, s)) instanceof IOException);
      check("strength " + s + ": a wrong pass phrase on the same data", readFails(raw, raw.length, BAD, s) instanceof ZipCrypt.WrongPassword);
      check("strength " + s + ": the empty entry has nothing but salt, verifier and code and reads as empty",
          readAllQuiet(aesEncrypt(new byte[0], PW, s, false), PW, s).length == 0);
      byte[] emptyRaw = aesEncrypt(new byte[0], PW, s, false);
      emptyRaw[emptyRaw.length - 1] ^= 1;
      check("strength " + s + ": the empty entry's code is checked too", authFailed(readFails(emptyRaw, emptyRaw.length, PW, s)));
      InputStream cl = ZipCrypt.decryptAes(new ByteArrayInputStream(raw), raw.length, c(PW), s);
      cl.close();
      check("strength " + s + ": a closed stream is not readable", thrown(readOn(cl)) instanceof IOException);
    }
    // traditional: a wrong pass phrase through the CRC, and a cut stream just ends (the CRC check of the caller catches it)
    byte[] raw = ZipCrypt.encryptTraditional(DATA[1], c(PW), crc(DATA[1]));
    int cb = ZipCrypt.checkByteFromCrc(crc(DATA[1]));
    int rejected = 0;
    for (int i = 0; i < 300; i++) { try { ZipCrypt.decryptTraditional(new ByteArrayInputStream(raw), c(BAD + i), cb).close(); } catch (ZipCrypt.WrongPassword w) { rejected++; } }
    check("traditional: 300 wrong pass phrases, the check byte rejects almost all (" + rejected + ")", rejected >= 285);
    check("traditional: a wrong check byte is WrongPassword", thrown(tradWith(raw, cb ^ 1)) instanceof ZipCrypt.WrongPassword);
  }
  static byte[] flip(byte[] a, int i) { byte[] b = a.clone(); b[i] ^= 1; return b; }
  static byte[] readAllQuiet(byte[] raw, String pw, int s) throws IOException { return readAll(ZipCrypt.decryptAes(new ByteArrayInputStream(raw), raw.length, c(pw), s)); }
  static IoRun shortAes(final byte[] raw, final long len, final int s) { return new IoRun() { public void run() throws Exception { ZipCrypt.decryptAes(new ByteArrayInputStream(raw), len, c(PW), s); } }; }
  static IoRun readOn(final InputStream in) { return new IoRun() { public void run() throws Exception { in.read(); } }; }
  static IoRun tradWith(final byte[] raw, final int cb) { return new IoRun() { public void run() throws Exception { ZipCrypt.decryptTraditional(new ByteArrayInputStream(raw), c(PW), cb); } }; }

  // ------------------------------------------------------------------------------------------------------------ streaming: a size that would not fit if it were buffered
  static void streaming() throws Exception {
    final long total = 48L * 1024 * 1024;
    final byte[] block = random(1 << 16, 77);
    // a generated stream of 48 MB through AES-256 into a file, back out through a CRC; the memory used stays at a few KB
    File f = new File(tmp, "stream.bin");
    CRC32 want = new CRC32();
    long t0 = System.nanoTime();
    OutputStream fo = new FileOutputStream(f);
    ZipCrypt.AesOutputStream a = ZipCrypt.encryptAes(fo, c(PW), 3);
    for (long done = 0; done < total; done += block.length) { a.write(block, 0, block.length); want.update(block, 0, block.length); }
    a.close();
    long t1 = System.nanoTime();
    check("48 MB through AES-256 into a file: the file is that size plus 28 bytes", f.length() == total + ZipCrypt.aesOverhead(3));
    CRC32 got = new CRC32();
    long cnt = 0;
    InputStream in = ZipCrypt.decryptAes(new FileInputStream(f), f.length(), c(PW), 3);
    byte[] buf = new byte[32768];
    int k;
    while ((k = in.read(buf)) > 0) { got.update(buf, 0, k); cnt += k; }
    in.close();
    long t2 = System.nanoTime();
    check("48 MB read back from the file in 32 KB pieces: same length and CRC, code accepted", cnt == total && got.getValue() == want.getValue());
    System.out.println("  AES-256 stream of 48 MB: encrypt " + 48000000000L / Math.max(1, t1 - t0) + " MB/s, decrypt " + 48000000000L / Math.max(1, t2 - t1) + " MB/s");
    f.delete();
    t0 = System.nanoTime();
    fo = new FileOutputStream(f);
    ZipCrypt.TraditionalOutputStream to = ZipCrypt.encryptTraditional(fo, c(PW), 0x42);
    for (long done = 0; done < total; done += block.length) to.write(block, 0, block.length);
    to.close();
    t1 = System.nanoTime();
    got = new CRC32(); cnt = 0;
    in = ZipCrypt.decryptTraditional(new FileInputStream(f), c(PW), 0x42);
    while ((k = in.read(buf)) > 0) { got.update(buf, 0, k); cnt += k; }
    in.close();
    t2 = System.nanoTime();
    check("48 MB through ZipCrypto: same length and CRC", cnt == total && got.getValue() == want.getValue() && f.length() == total + 12);
    System.out.println("  ZipCrypto stream of 48 MB: encrypt " + 48000000000L / Math.max(1, t1 - t0) + " MB/s, decrypt " + 48000000000L / Math.max(1, t2 - t1) + " MB/s");
    f.delete();
  }
}
