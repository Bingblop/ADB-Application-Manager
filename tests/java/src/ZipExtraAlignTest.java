package com.bloatware.bingblop;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Rewriting a zip with alignment must align a stored entry even when its local extra field is almost 64 KB (the padding no longer fits next to the
 * extra field, so the unknown records go): found in the ApkSigner review, where zipalign called such entries BAD.
 */
public class ZipExtraAlignTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok, String d) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + d); } }

  static void stored(ZipOutputStream z, String name, byte[] data, byte[] extra) throws IOException {
    ZipEntry e = new ZipEntry(name);
    e.setMethod(ZipEntry.STORED);
    e.setSize(data.length);
    e.setCompressedSize(data.length);
    CRC32 c = new CRC32(); c.update(data);
    e.setCrc(c.getValue());
    if (extra != null) e.setExtra(extra);
    z.putNextEntry(e);
    z.write(data);
    z.closeEntry();
  }

  /** One unknown extra record (id 0x9999) of the given payload size. */
  static byte[] unknownExtra(int payload) {
    byte[] x = new byte[4 + payload];
    x[0] = (byte) 0x99; x[1] = (byte) 0x99; x[2] = (byte) payload; x[3] = (byte) (payload >> 8);
    return x;
  }

  static int le16(byte[] b, int p) { return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8); }
  static long le32(byte[] b, int p) { return (le16(b, p) | ((long) le16(b, p + 2) << 16)); }

  /** Where each entry's data starts in the output, by walking the local headers. */
  static java.util.Map<String, Long> dataOffsets(byte[] b) {
    java.util.Map<String, Long> m = new java.util.LinkedHashMap<String, Long>();
    int p = 0;
    while (p + 30 <= b.length && le32(b, p) == 0x04034b50L) {
      long csize = le32(b, p + 18);
      int nl = le16(b, p + 26), el = le16(b, p + 28);
      m.put(new String(b, p + 30, nl), (long) (p + 30 + nl + el));
      p += 30 + nl + el + (int) csize;
    }
    return m;
  }

  /** The record ids in the local extra field of the named entry (the padding record 0xD935 included). */
  static java.util.Set<Integer> localExtraIds(byte[] b, String name) {
    java.util.Set<Integer> ids = new java.util.HashSet<Integer>();
    int p = 0;
    while (p + 30 <= b.length && le32(b, p) == 0x04034b50L) {
      long csize = le32(b, p + 18);
      int nl = le16(b, p + 26), el = le16(b, p + 28);
      if (new String(b, p + 30, nl).equals(name)) {
        int q = p + 30 + nl, end = q + el;
        while (q + 4 <= end) { ids.add(le16(b, q)); q += 4 + le16(b, q + 2); }
        return ids;
      }
      p += 30 + nl + el + (int) csize;
    }
    return ids;
  }

  public static void main(String[] a) throws Exception {
    File dir = Files.createTempDirectory("zea").toFile();
    File src = new File(dir, "in.zip"), dst = new File(dir, "out.zip");
    ZipOutputStream z = new ZipOutputStream(new FileOutputStream(src));
    stored(z, "assets/plain.bin", new byte[]{1, 2, 3, 4, 5}, null);
    stored(z, "assets/bigextra.bin", "abcde".getBytes(), unknownExtra(65530 - 4));     // extra field of 65530 bytes
    stored(z, "lib/arm64-v8a/libbar.so", new byte[500], unknownExtra(65526 - 4));      // needs 16384-byte alignment
    stored(z, "lib/arm64-v8a/libmid.so", new byte[500], unknownExtra(50000));          // fits only without the pad of up to 16383 bytes
    stored(z, "assets/small.bin", "xyz".getBytes(), unknownExtra(8));                  // a small extra field is kept
    // near-limit extra fields that also hold the records an encrypted entry cannot do without: they must survive, the unknown filler must go
    byte[] aes = new byte[]{(byte) 0x01, (byte) 0x99, 7, 0, 2, 0, 'A', 'E', 3, 8, 0};                         // 0x9901, 7 bytes
    byte[] strong = new byte[]{(byte) 0x17, 0, 4, 0, 1, 2, 3, 4};                                              // 0x0017, 4 bytes
    byte[] big = unknownExtra(65000);
    byte[] mixed = new byte[aes.length + strong.length + big.length];
    System.arraycopy(aes, 0, mixed, 0, aes.length);
    System.arraycopy(big, 0, mixed, aes.length, big.length);
    System.arraycopy(strong, 0, mixed, aes.length + big.length, strong.length);
    stored(z, "lib/arm64-v8a/libenc.so", new byte[300], mixed);
    z.close();

    ZipTool.Archive arc = ZipTool.open(src);
    ZipTool.rewrite(arc, dst, new ArrayList<ZipTool.Edit>(), true, null);
    byte[] out = Files.readAllBytes(dst.toPath());
    java.util.Map<String, Long> off = dataOffsets(out);
    check("all six entries are in the output", off.size() == 6, off.toString());
    check("plain.bin is 4-aligned", off.get("assets/plain.bin") % 4 == 0, String.valueOf(off.get("assets/plain.bin")));
    check("bigextra.bin is 4-aligned", off.get("assets/bigextra.bin") % 4 == 0, String.valueOf(off.get("assets/bigextra.bin")));
    check("libbar.so is 16384-aligned", off.get("lib/arm64-v8a/libbar.so") % 16384 == 0, String.valueOf(off.get("lib/arm64-v8a/libbar.so")));
    check("libmid.so is 16384-aligned", off.get("lib/arm64-v8a/libmid.so") % 16384 == 0, String.valueOf(off.get("lib/arm64-v8a/libmid.so")));
    check("small.bin is 4-aligned", off.get("assets/small.bin") % 4 == 0, String.valueOf(off.get("assets/small.bin")));

    check("libenc.so is 16384-aligned", off.get("lib/arm64-v8a/libenc.so") % 16384 == 0, String.valueOf(off.get("lib/arm64-v8a/libenc.so")));
    // its local extra field in the output: the AES and strong-encryption records are there, the unknown 0x9999 filler is gone
    java.util.Set<Integer> ids = localExtraIds(out, "lib/arm64-v8a/libenc.so");
    check("libenc.so keeps the AES record (0x9901)", ids.contains(0x9901), ids.toString());
    check("libenc.so keeps the strong-encryption record (0x0017)", ids.contains(0x0017), ids.toString());
    check("libenc.so drops the unknown filler (0x9999)", !ids.contains(0x9999), ids.toString());

    // the data is unchanged and the zip still reads
    ZipTool.Archive back = ZipTool.open(dst);
    check("the rewritten zip lists six entries", back.entries.size() == 6, String.valueOf(back.entries.size()));
    File x = new File(dir, "x.bin");
    for (ZipTool.Entry e : back.entries) {
      if (e.name.equals("assets/bigextra.bin")) {
        ZipTool.extractTo(back, e, x);
        check("bigextra.bin data is intact", new String(Files.readAllBytes(x.toPath())).equals("abcde"), "wrong data");
      }
    }
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
