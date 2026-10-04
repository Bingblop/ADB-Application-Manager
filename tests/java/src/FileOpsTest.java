package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Copy, move and delete with a rule for taken names (replace, skip, keep both), the free-name search, file name checks, text detection, atomic write, and the shell form of the same rules. */
public class FileOpsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void eq(String what, String got, String want) { n++; if (!want.equals(got)) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

  static File tmp(String p) throws IOException { return Files.createTempDirectory(p).toFile(); }
  static void write(File f, String s) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }
  static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
  static List<File> l(File... f) { return new ArrayList<File>(Arrays.asList(f)); }
  static void rm(File f) { if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) { File[] k = f.listFiles(); if (k != null) for (File x : k) rm(x); } f.delete(); }
  static List<String> names(File d) { String[] a = d.list(); List<String> r = a == null ? new ArrayList<String>() : new ArrayList<String>(Arrays.asList(a)); Collections.sort(r); return r; }

  public static void main(String[] args) throws Exception {
    // ---------- free names ----------
    File d = tmp("fo-names");
    eq("a free name stays", FileOps.uniqueName(d, "a.txt"), "a.txt");
    write(new File(d, "a.txt"), "x");
    eq("a taken name gets (1) before the extension", FileOps.uniqueName(d, "a.txt"), "a (1).txt");
    write(new File(d, "a (1).txt"), "x");
    eq("(1) taken, so (2)", FileOps.uniqueName(d, "a.txt"), "a (2).txt");
    write(new File(d, "archive.tar.gz"), "x");
    eq("a compound extension stays whole", FileOps.uniqueName(d, "archive.tar.gz"), "archive (1).tar.gz");
    write(new File(d, ".bashrc"), "x");
    eq("a dot file has no extension", FileOps.uniqueName(d, ".bashrc"), ".bashrc (1)");
    write(new File(d, "README"), "x");
    eq("no extension", FileOps.uniqueName(d, "README"), "README (1)");
    new File(d, "photos").mkdir();
    eq("a folder gets the number at the end", FileOps.uniqueName(d, "photos"), "photos (1)");
    new File(d, "v1.2").mkdir();
    eq("a folder with a dot treats the dot as an extension", FileOps.uniqueName(d, "v1.2"), "v1 (1).2");
    Files.createSymbolicLink(new File(d, "dead.txt").toPath(), new File(d, "nowhere").toPath());
    check("a link whose target is gone still holds its name", !FileOps.uniqueName(d, "dead.txt").equals("dead.txt"));
    eq("upper case compound extension", FileOps.uniqueName(d, "Backup.TAR.GZ"), "Backup.TAR.GZ");
    rm(d);

    // ---------- copy ----------
    File src = tmp("fo-src"), dst = tmp("fo-dst");
    write(new File(src, "a.txt"), "A-new");
    write(new File(src, "dir/in.txt"), "IN-new");
    write(new File(src, "dir/sub/deep.txt"), "DEEP");
    FileOps.Result r = FileOps.copy(l(new File(src, "a.txt"), new File(src, "dir")), dst, FileOps.REPLACE, null);
    check("copy: a file and a folder are copied with what is in them", r.done == 2 && r.failed.isEmpty() && read(new File(dst, "a.txt")).equals("A-new") && read(new File(dst, "dir/sub/deep.txt")).equals("DEEP"));
    check("copy: the source is still there", new File(src, "a.txt").isFile() && new File(src, "dir/in.txt").isFile());
    check("copy: no temporary files left", names(dst).equals(Arrays.asList("a.txt", "dir")) && names(new File(dst, "dir")).equals(Arrays.asList("in.txt", "sub")));
    check("copy: the modified time is kept", Math.abs(new File(dst, "a.txt").lastModified() - new File(src, "a.txt").lastModified()) < 2000);

    // replace
    write(new File(src, "a.txt"), "A-v2"); write(new File(dst, "dir/old-only.txt"), "OLD");
    r = FileOps.copy(l(new File(src, "a.txt"), new File(src, "dir")), dst, FileOps.REPLACE, null);
    check("replace: the file is replaced", read(new File(dst, "a.txt")).equals("A-v2") && r.done == 2 && r.skipped == 0);
    check("replace: a folder merges with the one there (files of both stay)", new File(dst, "dir/old-only.txt").isFile() && new File(dst, "dir/in.txt").isFile());

    // skip
    write(new File(src, "a.txt"), "A-v3");
    r = FileOps.copy(l(new File(src, "a.txt")), dst, FileOps.SKIP, null);
    check("skip: the file there is kept and counted as skipped", read(new File(dst, "a.txt")).equals("A-v2") && r.skipped == 1 && r.done == 0);
    write(new File(src, "dir/fresh.txt"), "FRESH"); write(new File(src, "dir/in.txt"), "IN-v3");
    r = FileOps.copy(l(new File(src, "dir")), dst, FileOps.SKIP, null);
    check("skip: inside a merged folder only the new names are added", read(new File(dst, "dir/in.txt")).equals("IN-new") && read(new File(dst, "dir/fresh.txt")).equals("FRESH") && r.skipped >= 1);

    // keep both
    r = FileOps.copy(l(new File(src, "a.txt")), dst, FileOps.KEEP_BOTH, null);
    check("keep both: the new one is a (1), the old one is untouched", read(new File(dst, "a.txt")).equals("A-v2") && read(new File(dst, "a (1).txt")).equals("A-v3") && r.done == 1);
    r = FileOps.copy(l(new File(src, "a.txt")), dst, FileOps.KEEP_BOTH, null);
    check("keep both again: (2)", read(new File(dst, "a (2).txt")).equals("A-v3"));
    r = FileOps.copy(l(new File(src, "dir")), dst, FileOps.KEEP_BOTH, null);
    check("keep both: a folder becomes 'dir (1)' with its content", read(new File(dst, "dir (1)/sub/deep.txt")).equals("DEEP") && new File(dst, "dir/old-only.txt").isFile());

    // a copy into its own folder
    r = FileOps.copy(l(new File(dst, "a.txt")), dst, FileOps.REPLACE, null);
    check("copy onto itself with replace is left alone (the file is not lost)", read(new File(dst, "a.txt")).equals("A-v2") && r.skipped == 1);
    r = FileOps.copy(l(new File(dst, "a.txt")), dst, FileOps.KEEP_BOTH, null);
    check("copy into the same folder with keep both makes a copy", names(dst).contains("a (3).txt") && read(new File(dst, "a (3).txt")).equals("A-v2"));
    // into itself
    r = FileOps.copy(l(new File(dst, "dir")), new File(dst, "dir/sub"), FileOps.REPLACE, null);
    check("a folder cannot be copied into itself", r.failed.size() == 1 && r.failed.get(0)[1].contains("inside itself") && names(new File(dst, "dir/sub")).equals(Arrays.asList("deep.txt")));
    // a file where a folder is
    write(new File(src, "dir2"), "I am a file"); new File(dst, "dir2").mkdir();
    r = FileOps.copy(l(new File(src, "dir2")), dst, FileOps.REPLACE, null);
    check("a file onto a folder of the same name fails with a reason and changes nothing", r.failed.size() == 1 && r.failed.get(0)[1].contains("in the way") && new File(dst, "dir2").isDirectory());
    r = FileOps.copy(l(new File(src, "dir2")), dst, FileOps.KEEP_BOTH, null);
    check("... but keep both puts it beside the folder", read(new File(dst, "dir2 (1)")).equals("I am a file"));
    r = FileOps.copy(l(new File(src, "missing")), dst, FileOps.REPLACE, null);
    check("a source that is gone fails with a reason", r.failed.size() == 1 && r.failed.get(0)[1].contains("No such"));
    // a link is copied as a link
    Files.createSymbolicLink(new File(src, "lnk").toPath(), new File("a.txt").toPath());
    r = FileOps.copy(l(new File(src, "lnk")), dst, FileOps.REPLACE, null);
    check("a link is copied as a link, not as what it points at", Files.isSymbolicLink(new File(dst, "lnk").toPath()) && r.done == 1);
    // a missing destination is made
    r = FileOps.copy(l(new File(src, "a.txt")), new File(dst, "new/sub"), FileOps.REPLACE, null);
    check("a destination that does not exist is created", read(new File(dst, "new/sub/a.txt")).equals("A-v3"));

    // ---------- progress and cancel ----------
    File big = new File(src, "big.bin");
    try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(big, "rw")) { f.setLength(40L * 1024 * 1024); }
    final int[] calls = {0};
    r = FileOps.copy(l(big), tmp("fo-big"), FileOps.REPLACE, new FileOps.Progress() {
      public boolean onProgress(String name, long bytes, int items) { calls[0]++; return true; }
    });
    check("progress is reported while a big file is copied (" + calls[0] + " calls) and at the end", calls[0] >= 2 && r.bytes == 40L * 1024 * 1024);
    File cdst = tmp("fo-cancel");
    r = FileOps.copy(l(big, new File(src, "a.txt")), cdst, FileOps.REPLACE, new FileOps.Progress() {
      public boolean onProgress(String name, long bytes, int items) { return bytes < 4L * 1024 * 1024; }
    });
    check("cancel stops the copy, leaves no half file under any name, and skips the rest", r.cancelled && names(cdst).isEmpty());
    // a failed replace keeps the old file
    File old = new File(cdst, "keep.bin"); write(old, "OLD");
    File bigsrc = new File(src, "keep.bin"); try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(bigsrc, "rw")) { f.setLength(30L * 1024 * 1024); }
    r = FileOps.copy(l(bigsrc), cdst, FileOps.REPLACE, new FileOps.Progress() {
      public boolean onProgress(String name, long bytes, int items) { return bytes < 2L * 1024 * 1024; }
    });
    check("a cancelled replace keeps the old file and leaves nothing else", r.cancelled && read(old).equals("OLD") && names(cdst).equals(Arrays.asList("keep.bin")));

    // ---------- move ----------
    File msrc = tmp("fo-msrc"), mdst = tmp("fo-mdst");
    write(new File(msrc, "m.txt"), "M1"); write(new File(msrc, "tree/x.txt"), "X1"); write(new File(msrc, "tree/y/z.txt"), "Z1");
    r = FileOps.move(l(new File(msrc, "m.txt"), new File(msrc, "tree")), mdst, FileOps.REPLACE, null);
    check("move: the files arrive and the originals are gone", r.done == 2 && read(new File(mdst, "m.txt")).equals("M1") && read(new File(mdst, "tree/y/z.txt")).equals("Z1") && !new File(msrc, "m.txt").exists() && !new File(msrc, "tree").exists());
    write(new File(msrc, "m.txt"), "M2"); write(new File(msrc, "tree/x.txt"), "X2"); write(new File(msrc, "tree/new.txt"), "N2");
    r = FileOps.move(l(new File(msrc, "tree")), mdst, FileOps.REPLACE, null);
    check("move onto a folder merges and replaces, and the source folder is gone", read(new File(mdst, "tree/x.txt")).equals("X2") && read(new File(mdst, "tree/y/z.txt")).equals("Z1") && read(new File(mdst, "tree/new.txt")).equals("N2") && !new File(msrc, "tree").exists());
    r = FileOps.move(l(new File(msrc, "m.txt")), mdst, FileOps.SKIP, null);
    check("move with skip leaves the source where it is (nothing is lost)", r.skipped == 1 && new File(msrc, "m.txt").isFile() && read(new File(mdst, "m.txt")).equals("M1"));
    r = FileOps.move(l(new File(msrc, "m.txt")), mdst, FileOps.KEEP_BOTH, null);
    check("move with keep both arrives as (1) and leaves the old one", read(new File(mdst, "m (1).txt")).equals("M2") && read(new File(mdst, "m.txt")).equals("M1") && !new File(msrc, "m.txt").exists());
    r = FileOps.move(l(new File(mdst, "m.txt")), mdst, FileOps.REPLACE, null);
    check("moving a file into its own folder does nothing and loses nothing", r.skipped == 1 && read(new File(mdst, "m.txt")).equals("M1"));
    r = FileOps.move(l(new File(mdst, "tree")), new File(mdst, "tree/y"), FileOps.REPLACE, null);
    check("a folder cannot be moved into itself", r.failed.size() == 1 && new File(mdst, "tree/y/z.txt").isFile());

    write(new File(mdst, "mt/keep.txt"), "DST"); write(new File(msrc, "mt/keep.txt"), "SRC"); write(new File(msrc, "mt/add.txt"), "ADD");
    r = FileOps.move(l(new File(msrc, "mt")), mdst, FileOps.SKIP, null);
    check("move of a folder onto a folder with skip: the new file moves, the taken name stays in the source", read(new File(mdst, "mt/add.txt")).equals("ADD") && read(new File(mdst, "mt/keep.txt")).equals("DST") && read(new File(msrc, "mt/keep.txt")).equals("SRC") && !new File(msrc, "mt/add.txt").exists());

    // ---------- delete ----------
    File ddir = tmp("fo-del");
    write(new File(ddir, "f.txt"), "1"); write(new File(ddir, "t/a/b.txt"), "2");
    File outside = tmp("fo-outside"); write(new File(outside, "precious.txt"), "KEEP");
    Files.createSymbolicLink(new File(ddir, "t/link").toPath(), outside.toPath());
    r = FileOps.delete(l(new File(ddir, "f.txt"), new File(ddir, "t"), new File(ddir, "ghost")), null);
    check("delete: a file and a folder with everything in it go; a ghost fails with a reason", r.done == 2 && r.failed.size() == 1 && names(ddir).isEmpty());
    check("delete: a link is removed and what it points at is not touched", read(new File(outside, "precious.txt")).equals("KEEP"));

    // ---------- names and text ----------
    check("a good name", FileOps.badName("notes.txt") == null && FileOps.badName("a b (1).md") == null && FileOps.badName(".hidden") == null);
    check("bad names", FileOps.badName("") != null && FileOps.badName("  ") != null && FileOps.badName(".") != null && FileOps.badName("..") != null && FileOps.badName("a/b") != null && FileOps.badName("a\nb") != null && FileOps.badName(null) != null);
    byte[] t1 = "Hello, wörld — 日本語\n".getBytes(StandardCharsets.UTF_8);
    check("text: UTF-8 is text", FileOps.looksLikeText(t1, t1.length, false));
    check("text: a NUL byte is not text", !FileOps.looksLikeText(new byte[]{'a', 0, 'b'}, 3, false));
    check("text: invalid UTF-8 is not text", !FileOps.looksLikeText(new byte[]{'a', (byte) 0xFF, 'b'}, 3, false) && !FileOps.looksLikeText(new byte[]{(byte) 0xC3, 'a'}, 2, false));
    byte[] cut = Arrays.copyOf(t1, t1.length - 3);                      // ends inside a character of the Japanese text
    check("text: a character cut at the end of a long file is fine; the same bytes of a whole file are not", FileOps.looksLikeText(cut, cut.length, true) && !FileOps.looksLikeText(cut, cut.length, false));
    check("text: an empty file is text", FileOps.looksLikeText(new byte[0], 0, false));

    // ---------- atomic write ----------
    File w = tmp("fo-write");
    File wf = new File(w, "n.txt");
    FileOps.writeAtomic(wf, "one".getBytes(StandardCharsets.UTF_8));
    FileOps.writeAtomic(wf, "two".getBytes(StandardCharsets.UTF_8));
    check("atomic write: new content, no temporary file left", read(wf).equals("two") && names(w).equals(Arrays.asList("n.txt")));
    boolean threw = false;
    try { FileOps.writeAtomic(new File(w, "no/such/dir/f.txt"), new byte[]{1}); } catch (IOException e) { threw = true; }
    check("atomic write into a folder that is not there fails", threw && names(w).equals(Arrays.asList("n.txt")));

    // ---------- the same rules in a shell ----------
    File sd = tmp("fo-sh"), ss = tmp("fo-shsrc");
    write(new File(ss, "it's a file.txt"), "S1"); write(new File(ss, "pack.tar.gz"), "P1"); write(new File(ss, ".rc"), "R1"); write(new File(ss, "dir/k.txt"), "K1");
    write(new File(sd, "it's a file.txt"), "OLD"); write(new File(sd, "pack.tar.gz"), "OLD"); write(new File(sd, ".rc"), "OLD"); write(new File(sd, "dir/k.txt"), "OLD");
    String o;
    o = sh(FileOps.shellScript("cp -r", new File(ss, "it's a file.txt").getPath(), sd.getPath(), FileOps.KEEP_BOTH));
    check("shell keep both: a quote in the name is fine, the copy is (1): " + o, o.contains("FMOK") && read(new File(sd, "it's a file (1).txt")).equals("S1") && read(new File(sd, "it's a file.txt")).equals("OLD"));
    o = sh(FileOps.shellScript("cp -r", new File(ss, "pack.tar.gz").getPath(), sd.getPath(), FileOps.KEEP_BOTH));
    check("shell keep both: tar.gz keeps its whole extension: " + o, new File(sd, "pack (1).tar.gz").isFile());
    o = sh(FileOps.shellScript("cp -r", new File(ss, ".rc").getPath(), sd.getPath(), FileOps.KEEP_BOTH));
    check("shell keep both: a dot file: " + o, new File(sd, ".rc (1)").isFile());
    o = sh(FileOps.shellScript("cp -r", new File(ss, "dir").getPath(), sd.getPath(), FileOps.KEEP_BOTH));
    check("shell keep both: a folder: " + o, read(new File(sd, "dir (1)/k.txt")).equals("K1"));
    o = sh(FileOps.shellScript("cp -r", new File(ss, "pack.tar.gz").getPath(), sd.getPath(), FileOps.SKIP));
    check("shell skip: says FMSKIP and changes nothing: " + o, o.contains("FMSKIP") && !o.contains("FMOK") && read(new File(sd, "pack.tar.gz")).equals("OLD"));
    o = sh(FileOps.shellScript("cp -r", new File(ss, "pack.tar.gz").getPath(), sd.getPath(), FileOps.REPLACE));
    check("shell replace: " + o, o.contains("FMOK") && read(new File(sd, "pack.tar.gz")).equals("P1"));
    o = sh(FileOps.shellScript("mv", new File(ss, ".rc").getPath(), sd.getPath(), FileOps.KEEP_BOTH));
    check("shell move with keep both: the source is gone: " + o, !new File(ss, ".rc").exists() && new File(sd, ".rc (2)").isFile());
    o = sh(FileOps.shellScript("cp -r", new File(ss, "dir").getPath(), new File(sd, "fresh/er").getPath(), FileOps.SKIP));
    check("shell skip into a folder that does not exist makes it: " + o, o.contains("FMOK") && new File(sd, "fresh/er/dir/k.txt").isFile());

    // ---------- picture cache ----------
    File tc = tmp("fo-thumbs");
    String k1 = ThumbCache.key("/a/b.jpg", 1000, 2000, 96), k2 = ThumbCache.key("/a/b.jpg", 1001, 2000, 96), k3 = ThumbCache.key("/a/b.jpg", 1000, 2000, 128);
    check("cache keys: the same file gives the same key, an edited file or another size a new one", k1.equals(ThumbCache.key("/a/b.jpg", 1000, 2000, 96)) && !k1.equals(k2) && !k1.equals(k3) && k1.matches("[0-9a-f]{40}"));
    for (int i = 0; i < 5; i++) { File f = new File(tc, "t" + i + ".jpg"); try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(new byte[1000]); } f.setLastModified(1_000_000_000_000L + i * 1000L); }
    ThumbCache.touch(new File(tc, "t0.jpg"));                              // t0 was used last
    check("cache size", ThumbCache.size(tc) == 5000);
    long freed = ThumbCache.purge(tc, 3000);
    check("purge removes the least recently used until the limit is met: " + names(tc), freed == 2000 && names(tc).equals(Arrays.asList("t0.jpg", "t3.jpg", "t4.jpg")));
    check("purge under the limit does nothing", ThumbCache.purge(tc, 10000) == 0);
    check("clear empties the folder", ThumbCache.clear(tc) == 3000 && names(tc).isEmpty());
    check("which files get a picture", ThumbCache.kindOf("IMG_1.JPG").equals("image") && ThumbCache.kindOf("a.mp4").equals("video") && ThumbCache.kindOf("notes.txt").isEmpty() && ThumbCache.kindOf("script.ts").isEmpty() && ThumbCache.kindOf("noext").isEmpty());

    for (File f : new File[]{src, dst, cdst, tc, msrc, mdst, ddir, outside, w, sd, ss}) rm(f);
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  static String sh(String script) throws Exception {
    Process p = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
    byte[] b = p.getInputStream().readAllBytes();
    p.waitFor();
    return new String(b, StandardCharsets.UTF_8).trim();
  }
}
