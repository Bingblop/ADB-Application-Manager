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

    System.out.println(fails == 0 ? "PASS ZipBoundsTest: " + n + " checks" : "FAILED ZipBoundsTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
