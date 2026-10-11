package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * New zip archives (ZipWriter): stored and deflated, folders, unicode names, empty files, many entries (ZIP64 end record), password protection
 * (AES-256, AES-128, ZipCrypto), reading them back through ZipTool with and without the password, and the same archives read by Info-ZIP unzip,
 * java.util.zip and pyzipper. Also the way ZipTool reports a missing or wrong password, and Cancel.
 */
public class ZipWriterTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static File tmp;

  static byte[] bytes(int len, long seed, boolean text) {
    byte[] b = new byte[len];
    if (text) { byte[] w = "the quick brown fox jumps over the lazy dog\n".getBytes(StandardCharsets.UTF_8); for (int i = 0; i < len; i++) b[i] = w[i % w.length]; }
    else new Random(seed).nextBytes(b);
    return b;
  }
  static File file(File dir, String name, byte[] data) throws IOException {
    File f = new File(dir, name);
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), data);
    return f;
  }
  static String sha(byte[] d) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-256").digest(d)) sb.append(String.format("%02x", x & 0xFF));
    return sb.toString();
  }
  static byte[] read(ZipTool.Archive a, String name) throws IOException {
    ZipTool.Entry e = a.find(name);
    InputStream in = a.open(e);
    try {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int r;
      while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
      return bo.toByteArray();
    } finally { in.close(); }
  }
  static String out;
  static int exec(int sec, String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    Process p = pb.start();
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    InputStream in = p.getInputStream();
    byte[] buf = new byte[8192];
    int r;
    while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
    if (!p.waitFor(sec, TimeUnit.SECONDS)) { p.destroyForcibly(); out = "timeout"; return -1; }
    out = new String(bo.toByteArray(), StandardCharsets.UTF_8);
    return p.exitValue();
  }
  static boolean has(String tool) { try { return exec(10, "sh", "-c", "command -v " + tool) == 0; } catch (Exception e) { return false; } }

  static String python() throws Exception {
    List<String> c = new ArrayList<String>();
    c.add("python3");
    c.add(new File(System.getProperty("java.io.tmpdir"), "zipcrypt-test-venv/bin/python").getPath());
    for (String p : c) if (new File(p).isAbsolute() ? new File(p).exists() && exec(30, p, "-c", "import pyzipper") == 0 : exec(30, p, "-c", "import pyzipper") == 0) return p;
    return null;
  }

  public static void main(String[] args) throws Exception {
    tmp = Files.createTempDirectory("zipwriter").toFile();
    File src = new File(tmp, "src");
    byte[] textBig = bytes(300000, 0, true), randBig = bytes(200000, 7, false), small = "hello".getBytes(StandardCharsets.UTF_8);
    file(src, "docs/readme.txt", textBig);
    file(src, "docs/img.bin", randBig);
    file(src, "docs/empty.txt", new byte[0]);
    file(src, "ünï/日本語 file.txt", small);
    file(src, "run.sh", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8)).setExecutable(true);
    new File(src, "emptydir").mkdirs();
    List<ZipWriter.Item> items = new ArrayList<ZipWriter.Item>();
    items.add(new ZipWriter.Item("docs/", new File(src, "docs")));
    items.add(new ZipWriter.Item("docs/readme.txt", new File(src, "docs/readme.txt")));
    items.add(new ZipWriter.Item("docs/img.bin", new File(src, "docs/img.bin")));
    items.add(new ZipWriter.Item("docs/empty.txt", new File(src, "docs/empty.txt")));
    items.add(new ZipWriter.Item("ünï/日本語 file.txt", new File(src, "ünï/日本語 file.txt")));
    items.add(new ZipWriter.Item("run.sh", new File(src, "run.sh")));
    items.add(new ZipWriter.Item("emptydir/", new File(src, "emptydir")));
    final String[] names = {"docs/readme.txt", "docs/img.bin", "docs/empty.txt", "ünï/日本語 file.txt", "run.sh"};
    final byte[][] data = {textBig, randBig, new byte[0], small, "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8)};

    // ---- plain, deflated and stored
    for (int level : new int[]{0, 1, 6, 9}) {
      File z = new File(tmp, "plain" + level + ".zip");
      ZipWriter.create(z, items, level, null, ZipWriter.NONE, null);
      ZipTool.Archive a = ZipTool.open(z);
      check("level " + level + ": every entry is listed (5 files + 2 folders)", a.entries.size() == 7);
      boolean same = true;
      for (int i = 0; i < names.length; i++) same &= Arrays.equals(read(a, names[i]), data[i]);
      check("level " + level + ": every file reads back exactly through ZipTool", same);
      ZipTool.Entry t = a.find("docs/readme.txt");
      check("level " + level + ": method " + (level == 0 ? "stored" : "deflated") + " and the CRC and size are in the central directory", t.method == (level == 0 ? 0 : 8) && t.size == textBig.length && t.crc != 0);
      if (level >= 1) check("level " + level + ": the text file really is packed", t.csize < t.size / 10);
      check("level " + level + ": folders are entries of their own", a.find("emptydir/") != null && a.find("emptydir/").dir && a.find("docs/") != null);
      check("level " + level + ": the unicode name is flagged UTF-8 and read back", a.find("ünï/日本語 file.txt") != null && (a.find("ünï/日本語 file.txt").flags & 0x800) != 0);
      check("level " + level + ": the executable bit is kept in the Unix attributes", (a.find("run.sh").externalAttrs >>> 16 & 0100) != 0 && (a.find("docs/readme.txt").externalAttrs >>> 16 & 0100) == 0);
      ZipFile jz = new ZipFile(z);
      boolean jok = true;
      for (int i = 0; i < names.length; i++) { InputStream in = jz.getInputStream(jz.getEntry(names[i])); jok &= Arrays.equals(readAll(in), data[i]); in.close(); }
      jz.close();
      check("level " + level + ": java.util.zip reads it too", jok);
      if (has("unzip")) check("level " + level + ": Info-ZIP unzip -t accepts it (" + out.trim().replace('\n', ' ') + ")", exec(60, "unzip", "-t", z.getPath()) == 0);
      File ex = new File(tmp, "ex" + level);
      long[] r = ZipTool.extractTree(a, "", ex, null);
      check("level " + level + ": extractTree writes 5 files", r[0] == 5 && Arrays.equals(Files.readAllBytes(new File(ex, "docs/img.bin").toPath()), randBig));
    }

    // ---- many entries: the ZIP64 end records
    {
      List<ZipWriter.Item> many = new ArrayList<ZipWriter.Item>();
      for (int i = 0; i < 70000; i++) many.add(new ZipWriter.Item("d" + i + "/", null));
      File z = new File(tmp, "many.zip");
      ZipWriter.create(z, many, 6, null, ZipWriter.NONE, null);
      ZipTool.Archive a = ZipTool.open(z);
      check("70,000 entries: listed with the ZIP64 end record (" + a.entries.size() + ")", a.entries.size() == 70000 && a.zip64);
      if (has("unzip")) check("70,000 entries: Info-ZIP unzip -t accepts the ZIP64 archive", exec(120, "unzip", "-tq", z.getPath()) == 0);
    }

    // ---- passwords
    char[] pw = "päss wörd 日本".toCharArray();
    int[] schemes = {ZipWriter.AES256, ZipWriter.AES128, ZipWriter.ZIPCRYPTO};
    String[] sname = {"AES-256", "AES-128", "ZipCrypto"};
    String py = python();
    if (py == null) System.out.println("SKIP pyzipper is not available: the WinZip AES interop checks are skipped");
    for (int si = 0; si < schemes.length; si++) {
      for (int level : new int[]{0, 6}) {
        String tag = sname[si] + " level " + level;
        File z = new File(tmp, "enc" + si + "_" + level + ".zip");
        ZipWriter.create(z, items, level, pw, schemes[si], null);
        ZipTool.Archive a = ZipTool.open(z);
        ZipTool.Entry e = a.find("docs/readme.txt");
        check(tag + ": the files are marked encrypted, the folders and the empty folder are not", e.encrypted() && !a.find("emptydir/").encrypted() && a.find("docs/empty.txt").encrypted());
        check(tag + ": the method says AES (99) or the real method for ZipCrypto", si < 2 ? e.method == 99 : e.method == (level == 0 ? 0 : 8));
        try { a.open(e).close(); check(tag + ": no password: refused", false); } catch (ZipTool.NeedPassword np) { check(tag + ": no password: NeedPassword (not wrong)", !np.wrong); }
        a.setPassword("wrong password".toCharArray());
        try {
          InputStream in = a.open(e);
          // ZipCrypto may accept a wrong password for 1 in 256 (then the data is garbage and the CRC fails): count a refusal at open OR a failed read
          boolean failed = false;
          try { readAll(in); } catch (IOException ex) { failed = true; }
          in.close();
          check(tag + ": a wrong password never gives the real data", failed || !Arrays.equals(read(a, "docs/readme.txt"), textBig));
        } catch (ZipTool.NeedPassword np) { check(tag + ": a wrong password is NeedPassword(wrong)", np.wrong); }
        a.setPassword(pw);
        boolean same = true;
        for (int i = 0; i < names.length; i++) same &= Arrays.equals(read(a, names[i]), data[i]);
        check(tag + ": with the password every file reads back exactly", same);
        File ex = new File(tmp, "exenc" + si + "_" + level);
        long[] r = ZipTool.extractTree(a, "", ex, null);
        check(tag + ": extractTree with the password writes 5 files, and the CRC checks pass", r[0] == 5 && r[2] == 0 && Arrays.equals(Files.readAllBytes(new File(ex, "docs/readme.txt").toPath()), textBig));
        a.setPassword(null);
        try { ZipTool.extractTree(a, "", new File(tmp, "exenc_no"), null); check(tag + ": extractTree without a password asks for one", false); } catch (ZipTool.NeedPassword np) { check(tag + ": extractTree without a password asks for one", !np.wrong); }
        if (si == 2 && has("unzip")) {
          check(tag + ": Info-ZIP unzip -t -P accepts it (" + out.trim().replace('\n', ' ') + ")", exec(60, "unzip", "-t", "-P", "päss wörd 日本", z.getPath()) == 0);
          check(tag + ": Info-ZIP unzip with a wrong password fails", exec(60, "unzip", "-t", "-P", "nope", z.getPath()) != 0);
        }
        if (si < 2 && py != null) {
          String script = "import sys, hashlib, pyzipper\n"
            + "z = pyzipper.AESZipFile(sys.argv[1]); z.setpassword(sys.argv[2].encode('utf-8'))\n"
            + "for i in z.infolist():\n"
            + "    if i.is_dir(): continue\n"
            + "    print(i.filename + ' ' + hashlib.sha256(z.read(i)).hexdigest())\n";
          int rc = exec(120, py, "-c", script, z.getPath(), new String(pw));
          boolean all = rc == 0;
          for (int i = 0; i < names.length; i++) all &= out.contains(names[i] + " " + sha(data[i]));
          check(tag + ": pyzipper reads every file with the password and the content hashes match (rc " + rc + " " + (all ? "" : out.trim()) + ")", all);
          rc = exec(120, py, "-c", script, z.getPath(), "wrong");
          check(tag + ": pyzipper rejects a wrong password", rc != 0);
        }
      }
    }

    // ---- a single new name twice, bad names, cancel and leftovers
    {
      File z = new File(tmp, "dup.zip");
      List<ZipWriter.Item> two = new ArrayList<ZipWriter.Item>();
      two.add(new ZipWriter.Item("a.txt", new File(src, "run.sh")));
      two.add(new ZipWriter.Item("a.txt", new File(src, "docs/readme.txt")));
      ZipWriter.create(z, two, 6, null, ZipWriter.NONE, null);
      ZipTool.Archive a = ZipTool.open(z);
      check("the same path twice: one entry, the first wins", a.entries.size() == 1 && read(a, "a.txt").length == 10);
      File zbad = new File(tmp, "bad.zip");
      List<ZipWriter.Item> evil = new ArrayList<ZipWriter.Item>();
      evil.add(new ZipWriter.Item("../evil.txt", new File(src, "run.sh")));
      try { ZipWriter.create(zbad, evil, 6, null, ZipWriter.NONE, null); check("a name with .. is refused", false); } catch (IOException e) { check("a name with .. is refused and nothing is left behind", !zbad.exists() && !new File(tmp, ".bad.zip.part").exists()); }
      File zc = new File(tmp, "cancel.zip");
      final int[] calls = {0};
      try {
        ZipWriter.create(zc, items, 6, null, ZipWriter.NONE, new ZipWriter.Progress() { public boolean onProgress(long b, int f, String c) { return ++calls[0] < 3; } });
        check("Cancel stops the job", false);
      } catch (IOException e) { check("Cancel stops the job (" + e.getMessage() + "), and leaves neither the archive nor the .part file", e.getMessage().startsWith("Cancelled") && !zc.exists() && !new File(tmp, ".cancel.zip.part").exists()); }
      File existing = new File(tmp, "keep.zip");
      Files.write(existing.toPath(), "old".getBytes(StandardCharsets.UTF_8));
      try { ZipWriter.create(existing, evil, 6, null, ZipWriter.NONE, null); } catch (IOException ignored) {}
      check("a failed job leaves an existing file of that name untouched", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8).equals("old"));
      ZipWriter.create(existing, items, 6, null, ZipWriter.NONE, null);
      check("a good job replaces it", ZipTool.open(existing).entries.size() == 7);
    }

    // ---- an archive made here can be edited by ZipTool.rewrite (encrypted entries are copied as they are)
    {
      File z = new File(tmp, "edit.zip");
      ZipWriter.create(z, items, 6, pw, ZipWriter.AES256, null);
      ZipTool.Archive a = ZipTool.open(z);
      File z2 = new File(tmp, "edit2.zip");
      List<ZipTool.Edit> eds = new ArrayList<ZipTool.Edit>();
      eds.add(ZipTool.Edit.rename("run.sh", "bin/run.sh"));
      eds.add(ZipTool.Edit.delete("docs/empty.txt"));
      ZipTool.rewrite(a, z2, eds, false, null);
      ZipTool.Archive b = ZipTool.open(z2);
      b.setPassword(pw);
      check("rewrite keeps encrypted entries encrypted and readable with the same password", b.find("bin/run.sh") != null && b.find("bin/run.sh").encrypted() && Arrays.equals(read(b, "bin/run.sh"), data[4]) && Arrays.equals(read(b, "docs/img.bin"), randBig) && b.find("docs/empty.txt") == null);
    }

    deleteTree(tmp);
    System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED of " + n);
    System.exit(fails == 0 ? 0 : 1);
  }

  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int r;
    while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
    return bo.toByteArray();
  }
  static void deleteTree(File f) {
    File[] k = f.listFiles();
    if (k != null) for (File c : k) deleteTree(c);
    f.delete();
  }
}
