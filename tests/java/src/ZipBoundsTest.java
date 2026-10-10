package com.bloatware.bingblop;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A small zip cannot fill the storage (extraction stops at the entry's declared size) and cannot list millions of files (entry cap). */
public class ZipBoundsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static File zipOf(File dir, String name, String[] names, byte[] data) throws IOException {
    File f = new File(dir, name);
    ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
    for (String nm : names) { z.putNextEntry(new ZipEntry(nm)); if (data != null) z.write(data); z.closeEntry(); }
    z.close();
    return f;
  }
  /** Changes the uncompressed size the central directory declares for the first entry. */
  static void patchDeclaredSize(File f, long size) throws IOException {
    byte[] b = Files.readAllBytes(f.toPath());
    for (int i = 0; i + 46 < b.length; i++) {
      if (b[i] == 0x50 && b[i + 1] == 0x4b && b[i + 2] == 0x01 && b[i + 3] == 0x02) {
        for (int k = 0; k < 4; k++) b[i + 24 + k] = (byte) (size >> (8 * k));
        Files.write(f.toPath(), b);
        return;
      }
    }
    throw new IOException("no central directory entry");
  }
  static int indexOf(byte[] b, int sig0, int sig1, int sig2, int sig3, boolean last) {
    int found = -1;
    for (int i = 0; i + 4 <= b.length; i++) {
      if ((b[i] & 0xff) == sig0 && (b[i + 1] & 0xff) == sig1 && (b[i + 2] & 0xff) == sig2 && (b[i + 3] & 0xff) == sig3) { found = i; if (!last) return i; }
    }
    return found;
  }
  /** The end record says the zip holds {@code claimed} entries; the directory itself is left alone. */
  static void patchEocdCount(File f, int claimed) throws IOException {
    byte[] b = Files.readAllBytes(f.toPath());
    int e = indexOf(b, 0x50, 0x4b, 0x05, 0x06, true);
    for (int k = 0; k < 2; k++) { b[e + 8 + k] = (byte) (claimed >> (8 * k)); b[e + 10 + k] = (byte) (claimed >> (8 * k)); }
    Files.write(f.toPath(), b);
  }
  /** One-entry zip whose directory record gets a ZIP64 extra field with an uncompressed size of 2^63 (negative as a signed long). */
  static void patchNegativeZip64Size(File f) throws IOException {
    byte[] b = Files.readAllBytes(f.toPath());
    int c = indexOf(b, 0x50, 0x4b, 0x01, 0x02, false);
    int e = indexOf(b, 0x50, 0x4b, 0x05, 0x06, true);
    int nl = (b[c + 28] & 0xff) | ((b[c + 29] & 0xff) << 8);
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    o.write(b, 0, c + 46 + nl);
    byte[] extra = new byte[12];
    extra[0] = 1; extra[1] = 0; extra[2] = 8; extra[3] = 0; extra[11] = (byte) 0x80;
    o.write(extra);
    o.write(b, c + 46 + nl, b.length - (c + 46 + nl));
    byte[] r = o.toByteArray();
    for (int k = 0; k < 4; k++) r[c + 24 + k] = (byte) 0xff;
    r[c + 30] = 12; r[c + 31] = 0;
    int e2 = e + 12;
    int cdSize = (r[e2 + 12] & 0xff) | ((r[e2 + 13] & 0xff) << 8) | ((r[e2 + 14] & 0xff) << 16) | ((r[e2 + 15] & 0xff) << 24);
    cdSize += 12;
    for (int k = 0; k < 4; k++) r[e2 + 12 + k] = (byte) (cdSize >> (8 * k));
    Files.write(f.toPath(), r);
  }
  /** Reads zeros forever and counts what was taken. */
  static final class Endless extends InputStream {
    long taken = 0;
    @Override public int read() { taken++; return 0; }
    @Override public int read(byte[] b, int off, int len) { java.util.Arrays.fill(b, off, off + len, (byte) 0); taken += len; return len; }
  }

  public static void main(String[] args) throws Exception {
    File dir = Files.createTempDirectory("zipbounds").toFile();

    // 1. a zip whose directory declares 1000 bytes but whose data inflates to 8 MB
    byte[] big = new byte[8 << 20];
    File bomb = zipOf(dir, "bomb.zip", new String[] { "x.bin" }, big);
    patchDeclaredSize(bomb, 1000);
    ZipTool.Archive a = ZipTool.open(bomb);
    File dest = new File(dir, "x.out");
    String err = "";
    try { ZipTool.extractTo(a, a.entries.get(0), dest); } catch (IOException e) { err = String.valueOf(e.getMessage()); }
    File part = new File(dir, ".x.out.part");
    check("the real data is longer than the declared size: extraction fails with a damaged-size message", err.contains("damaged") && err.contains("size"));
    check("no file is left behind (neither the result nor the .part file)", !dest.exists() && !part.exists());

    // 2. how much is written before it stops: feed an endless stream straight to the writer
    Method w = ZipTool.class.getDeclaredMethod("writeStream", ZipTool.Entry.class, InputStream.class, File.class);
    w.setAccessible(true);
    Endless in = new Endless();
    String err2 = "";
    try { w.invoke(null, a.entries.get(0), in, new File(dir, "y.out")); } catch (java.lang.reflect.InvocationTargetException e) { err2 = String.valueOf(e.getCause().getMessage()); }
    check("an endless stream is cut at the declared size (read at most one 64 KB block past 1000 bytes)", err2.contains("damaged") && in.taken <= 1000 + 65536);
    check("and nothing is left on disk for it either", !new File(dir, "y.out").exists() && !new File(dir, ".y.out.part").exists());

    // 3. an honest zip still extracts
    File ok = zipOf(dir, "ok.zip", new String[] { "a.txt" }, "hello world".getBytes("UTF-8"));
    ZipTool.Archive oa = ZipTool.open(ok);
    File okDest = new File(dir, "a.out");
    long wrote = ZipTool.extractTo(oa, oa.entries.get(0), okDest);
    check("an honest zip extracts all 11 bytes", wrote == 11 && okDest.length() == 11 && new String(Files.readAllBytes(okDest.toPath()), "UTF-8").equals("hello world"));
    File empty = zipOf(dir, "empty.zip", new String[] { "e.txt" }, new byte[0]);
    ZipTool.Archive ea = ZipTool.open(empty);
    check("an empty entry extracts to an empty file", ZipTool.extractTo(ea, ea.entries.get(0), new File(dir, "e.out")) == 0 && new File(dir, "e.out").length() == 0);

    // 4. the entry cap (lowered for the test)
    String[] names = new String[50];
    for (int i = 0; i < names.length; i++) names[i] = "f" + i + ".txt";
    File many = zipOf(dir, "many.zip", names, null);
    int before = ZipTool.maxEntries;
    ZipTool.maxEntries = 10;
    String err3 = "";
    try { ZipTool.open(many); } catch (IOException e) { err3 = String.valueOf(e.getMessage()); } finally { ZipTool.maxEntries = before; }
    check("a zip with more entries than the cap is refused with a clear message", err3.contains("too many"));
    ZipTool.Archive ma = ZipTool.open(many);
    check("under the normal cap the same zip lists all 50 entries", ma.entries.size() == 50);
    check("the normal cap is 500,000", before == 500000);
    ZipTool.maxEntries = 50;
    boolean exact = true;
    try { exact = ZipTool.open(many).entries.size() == 50; } catch (IOException e) { exact = false; } finally { ZipTool.maxEntries = before; }
    check("exactly as many entries as the cap are allowed", exact);

    // 5. the end record lies about the entry count: 50 records, but it says 5 (a cap of 10 must still hold)
    File liar = zipOf(dir, "liar.zip", names, null);
    patchEocdCount(liar, 5);
    ZipTool.maxEntries = 10;
    String err4 = "";
    try { ZipTool.open(liar); } catch (IOException e) { err4 = String.valueOf(e.getMessage()); } finally { ZipTool.maxEntries = before; }
    check("an end record that claims 5 entries over 50 real records is refused while parsing (cap 10)", err4.contains("too many"));

    // 6. a ZIP64 size with the top bit set reads as negative; it must not look like an unknown size
    File neg = zipOf(dir, "neg.zip", new String[] { "n.bin" }, "abc".getBytes("UTF-8"));
    patchNegativeZip64Size(neg);
    String err5 = "";
    try { ZipTool.open(neg); } catch (IOException e) { err5 = String.valueOf(e.getMessage()); }
    check("a negative ZIP64 size is refused as corrupt", err5.contains("bad size"));

    System.out.println(fails == 0 ? "PASS ZipBoundsTest: " + n + " checks" : "FAILED ZipBoundsTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
