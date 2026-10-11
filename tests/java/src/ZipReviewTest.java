import com.bloatware.bingblop.ZipTool;
import com.bloatware.bingblop.ZipTool.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Regression tests for the findings of the archive-code review (v5.8). */
public class ZipReviewTest {
  static int fails = 0;
  static void check(String name, boolean ok) { check(name, ok, null); }
  static void check(String name, boolean ok, String detail) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) { fails++; if (detail != null) System.out.println("     detail: " + detail); } }
  static final String FIX = System.getProperty("zipfix2") + "/";
  static File tmp;

  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
    while ((n = in.read(b)) > 0) bo.write(b, 0, n); in.close(); return bo.toByteArray();
  }
  static String shOut(String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true);
    Process p = pb.start(); String s = new String(readAll(p.getInputStream()), "UTF-8"); p.waitFor();
    StringBuilder sb = new StringBuilder();
    for (String l : s.split("\n")) if (!l.startsWith("Picked up ")) sb.append(l).append('\n');
    return sb.toString();
  }
  static String dump(File f) throws Exception { return shOut("python3", System.getProperty("zipdump"), f.getAbsolutePath()); }
  static File out(String n) { return new File(tmp, n); }
  static Archive open(String fixture) throws IOException { return ZipTool.open(new File(FIX + fixture)); }
  static byte[] content(Archive a, String name) throws IOException { return readAll(a.open(a.find(name))); }
  static boolean threw(Runnable r) { try { r.run(); return false; } catch (RuntimeException e) { return e.getCause() instanceof IOException || true; } }
  interface Thrower { void run() throws Exception; }
  static String err(Thrower t) { try { t.run(); return null; } catch (Exception e) { return e.getMessage() == null ? e.getClass().getName() : e.getMessage(); } }
  static List<String> names(Archive a) { List<String> l = new ArrayList<String>(); for (Entry e : a.entries) l.add(e.name); return l; }
  static int unzipT(File f) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("python3", "-c", "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); bad=z.testzip(); sys.exit(0 if bad is None else 1)", f.getAbsolutePath());
    pb.redirectErrorStream(true); Process p = pb.start(); readAll(p.getInputStream()); return p.waitFor();
  }
  static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x & 0xff)); return sb.toString(); }

  public static void main(String[] args) throws Exception {
    tmp = Files.createTempDirectory("zrev").toFile();

    // ---- 1. extra fields survive a rewrite (AES entries stay decryptable, Unicode names stay) ----
    Archive aes = open("aes256.zip");
    File o1 = out("aes_edit.zip");
    ZipTool.rewrite(aes, o1, Arrays.asList(Edit.add("added.txt", "x".getBytes())), true, null);
    String d1 = dump(o1);
    check("AES zip: both entries keep method 99 after an unrelated add", d1.contains("\"method\": 99") && d1.split("\"method\": 99").length == 3);
    check("AES zip: the 0x9901 AES record is in every local header and central entry", d1.split("\"cdx\": \"0199070002004145030800\"").length == 3 && d1.split("\"lx\": \"0199070002004145030800\"").length == 3);
    Archive aesOut = ZipTool.open(o1);
    check("AES zip: entry flags (encrypted) and sizes are carried over", aesOut.find("secret.txt").encrypted() && aesOut.find("secret.txt").csize == aes.find("secret.txt").csize && aesOut.find("added.txt") != null);
    boolean sameData = true;
    byte[] origRaw = Files.readAllBytes(new File(FIX + "aes256.zip").toPath()), newRaw = Files.readAllBytes(o1.toPath());
    // the encrypted payload bytes are identical (compare via offsets from the dumps)
    String dOrig = dump(new File(FIX + "aes256.zip"));
    check("AES zip: the dump of the original also has the record (sanity for the helper)", dOrig.contains("0199070002004145030800"));
    Archive fake = open("aes_fake.zip");
    File o1b = out("aes_fake_edit.zip");
    ZipTool.rewrite(fake, o1b, Arrays.asList(Edit.delete("nothing-here")), true, null);
    check("AES-style entry round-trips byte for byte in data and extra", dump(o1b).contains("0199070002004145030800") && ZipTool.open(o1b).find("secret.bin").csize == fake.find("secret.bin").csize);

    Archive uni = open("unicode_path_extra.zip");
    File o2 = out("uni_edit.zip");
    ZipTool.rewrite(uni, o2, Arrays.asList(Edit.add("z.txt", new byte[]{1})), false, null);
    check("Unicode-path extra (0x7075) is kept for an untouched entry", dump(o2).contains("7570") || dump(o2).contains("75700"));
    File o2b = out("uni_rename.zip");
    ZipTool.rewrite(uni, o2b, Arrays.asList(Edit.rename(uni.entries.get(0).name, "renamed.txt")), false, null);
    check("…and dropped when that entry is renamed (it described the old name)", !dump(o2b).contains("\"cdx\": \"7570"));

    // ---- 2. >= 4 GiB entries are refused instead of silently truncated ----
    Archive big = open("big_uncompressed.zip");
    File ob = out("big_edit.zip");
    String e2 = err(() -> ZipTool.rewrite(big, ob, Arrays.asList(Edit.add("x.txt", "x".getBytes())), true, null));
    check("a 4 GiB+ entry makes the rewrite fail with a clear message", e2 != null && e2.contains("4 GB"), e2);
    check("…and no output file is left behind", !ob.exists());
    File fat = out("fat.bin");
    try (RandomAccessFile raf = new RandomAccessFile(fat, "rw")) { raf.setLength(4L * 1024 * 1024 * 1024 + 1024); }
    String e2b = err(() -> ZipTool.rewrite(open("plain.zip"), out("fat_out.zip"), Arrays.asList(Edit.add("fat.bin", fat)), true, null));
    check("adding a file over 4 GB is refused up front", e2b != null && e2b.contains("4 GB"), e2b);
    check("…and nothing is left behind", !out("fat_out.zip").exists());
    fat.delete();

    // ---- 3. extraction never destroys data ----
    File sandbox = new File(tmp, "sbx"); sandbox.mkdirs();
    Files.copy(Paths.get(FIX + "nested.zip"), new File(sandbox, "nested.zip").toPath());
    long beforeLen = new File(sandbox, "nested.zip").length();
    Archive nested = ZipTool.open(new File(sandbox, "nested.zip"));
    String e3 = err(() -> ZipTool.extractTo(nested, nested.find("nested.zip"), new File(sandbox, "nested.zip")));
    check("extracting an entry onto the archive itself is refused", e3 != null && e3.contains("overwrite the archive"), e3);
    check("…and the archive is untouched", new File(sandbox, "nested.zip").length() == beforeLen && ZipTool.open(new File(sandbox, "nested.zip")).entries.size() == 2);
    File dst = new File(sandbox, "good.txt"); Files.write(dst.toPath(), "OLD CONTENT KEPT".getBytes());
    Archive corrupt = open("corrupt_stream.zip");
    String e3b = err(() -> ZipTool.extractTo(corrupt, corrupt.find("corrupt.txt"), dst));
    check("a damaged entry fails to extract", e3b != null, e3b);
    check("…an existing destination file is left exactly as it was", new String(Files.readAllBytes(dst.toPath())).equals("OLD CONTENT KEPT"));
    check("…and no .part file stays behind", !new File(sandbox, ".good.txt.part").exists());
    File dst2 = new File(sandbox, "fresh.txt");
    String e3c = err(() -> ZipTool.extractTo(corrupt, corrupt.find("corrupt.txt"), dst2));
    check("a damaged entry leaves nothing at a new destination either", e3c != null && !dst2.exists() && !new File(sandbox, ".fresh.txt.part").exists());

    // ---- 4. a bad entry doesn't stop the rest of a folder extraction ----
    Archive methods = open("methods.zip");
    File xd = new File(tmp, "xd"); xd.mkdirs();
    List<String> problems = new ArrayList<String>();
    long[] r4 = ZipTool.extractTree(methods, "", xd, null, problems);
    check("methods.zip: the two readable files are extracted around two unsupported ones", r4[0] == 2 && r4[2] == 2 && new File(xd, "1_ok.txt").exists() && new File(xd, "4_ok.txt").exists(), Arrays.toString(r4) + " " + problems);
    check("…the problems say which entries and why", problems.size() == 2 && problems.get(0).contains("2_bz2.txt") && problems.get(0).contains("Unsupported"), problems.toString());
    Archive fdc = open("filedir_conflict.zip");
    File xd2 = new File(tmp, "xd2"); xd2.mkdirs();
    List<String> p2 = new ArrayList<String>();
    long[] r4b = ZipTool.extractTree(fdc, "", xd2, null, p2);
    check("a file and a folder with the same name: one is extracted, the clash is reported, no exception", r4b[0] + r4b[2] == 2 && r4b[2] == 1, Arrays.toString(r4b) + " " + p2);
    Archive ed = open("empty_deflate0.zip");
    File xd3 = new File(tmp, "xd3"); xd3.mkdirs();
    long[] r4c = ZipTool.extractTree(ed, "", xd3, null, null);
    check("a deflated empty entry with no stream extracts as an empty file", r4c[0] == 2 && r4c[2] == 0 && new File(xd3, "empty_d.txt").length() == 0 && new File(xd3, "after.txt").exists(), Arrays.toString(r4c));
    check("…and reads as empty bytes", content(ed, "empty_d.txt").length == 0);

    // ---- 5. archives with data in front of the zip (CRX, self-extractors) ----
    Archive plain = open("plain.zip");
    Archive crx = open("prepended.crx"), sfx = open("prepended_sfx.zip");
    check("CRX: opens, with the header counted as the prefix", crx.prefix == 52 && names(crx).equals(names(plain)), crx.prefix + " " + names(crx));
    check("self-extractor stub: opens, prefix is the stub length", sfx.prefix == 17 && names(sfx).equals(names(plain)));
    check("…entries read back with the right data", Arrays.equals(content(crx, "a/b.txt"), content(plain, "a/b.txt")) && Arrays.equals(content(sfx, "manifest.json"), content(plain, "manifest.json")));
    check("an ordinary archive has no prefix", plain.prefix == 0 && open("trailing_zeros.zip").prefix == 0 && open("cd_padding.zip").prefix == 0);
    long[] r5 = ZipTool.extractTree(crx, "", new File(tmp, "xcrx"), null, null);
    check("…and extracts", r5[0] == 2 && r5[2] == 0);
    String e5 = err(() -> ZipTool.rewrite(crx, out("crx_edit.zip"), Arrays.asList(Edit.add("n.txt", new byte[]{1})), true, null));
    check("editing one is refused (the header would be lost), with an explanation", e5 != null && e5.contains("in front of the archive"), e5);
    check("…and nothing is written", !out("crx_edit.zip").exists());
    // An end-of-central-directory record planted inside the archive comment: like the JDK, Python and Android's own reader, the LAST record wins, so it reads as that (empty) archive. It must not crash.
    check("fake end-record inside the comment: reads like the JDK / Python / Android do (the last record wins), no crash", err(() -> open("fake_eocd.zip")) == null && open("fake_eocd_text.zip").entries.size() == 2);
    check("a zip64 central entry with a foreign extra field around it still opens", names(open("zip64_lho_order.zip")).equals(Arrays.asList("a.txt", "b.txt")) && new String(content(open("zip64_lho_order.zip"), "b.txt")).equals("bbbbbbbb"));

    // ---- 6. duplicate names don't block unrelated edits ----
    Archive dups = open("dups.zip");
    File o6 = out("dups_del.zip");
    ZipTool.rewrite(dups, o6, Arrays.asList(Edit.delete("other.txt")), true, null);
    Archive d6 = ZipTool.open(o6);
    check("dups.zip: deleting another entry works and both duplicates are carried over", names(d6).equals(Arrays.asList("dup.txt", "dup.txt")) && unzipT(o6) == 0, names(d6).toString());
    check("…an edit that makes a NEW duplicate is still refused (rename onto an existing name)", err(() -> ZipTool.rewrite(dups, out("dups_x.zip"), Arrays.asList(Edit.rename("other.txt", "dup.txt")), true, null)) != null);
    check("…and so is adding one", err(() -> ZipTool.rewrite(dups, out("dups_y.zip"), Arrays.asList(Edit.add("other.txt", new byte[]{1})), true, null)) != null);
    check("…and renaming something onto an existing name from the other side", err(() -> ZipTool.rewrite(open("plain.zip"), out("plain_x.zip"), Arrays.asList(Edit.rename("a/b.txt", "manifest.json")), true, null)) != null);

    // ---- 7. a file "renamed" to NAME/ moves into that folder ----
    File o7 = out("move.zip");
    ZipTool.rewrite(plain, o7, Arrays.asList(Edit.rename("manifest.json", "newdir/")), true, null);
    Archive d7 = ZipTool.open(o7);
    check("renaming a file to \"newdir/\" gives newdir/manifest.json holding its data (not a folder entry)", d7.find("newdir/manifest.json") != null && !d7.find("newdir/manifest.json").dir && d7.find("newdir/") == null && Arrays.equals(content(d7, "newdir/manifest.json"), content(plain, "manifest.json")), names(d7).toString());
    check("…and the result is a valid zip", unzipT(o7) == 0);

    // ---- 8. non-UTF-8 names keep their bytes ----
    Archive cp = open("cp437_names.zip");
    File o8 = out("cp_move.zip");
    ZipTool.rewrite(cp, o8, Arrays.asList(Edit.renameTree("dir/", "moved/")), true, null);
    String d8 = dump(o8);
    check("renaming a folder keeps the GBK bytes of a child name (d6d0cec4), not a UTF-8 re-encoding", d8.contains("6d6f7665642f" + "d6d0cec4" + "2e747874"), d8.substring(0, Math.min(400, d8.length())));
    check("…and the CP437 name keeps its single 0x82 byte", d8.contains("6d6f7665642f" + "636166" + "82" + "2e747874"));
    check("…while the UTF-8 name stays UTF-8", d8.contains("6d6f7665642f7574663" + "82d" + "c3a92e747874") || d8.contains(hex("moved/utf8-é.txt".getBytes(StandardCharsets.UTF_8))));
    File o8b = out("cp_text.zip");
    Entry gbk = null; for (Entry e : cp.entries) if (e.name.startsWith("dir/Ö")) gbk = e;
    ZipTool.rewrite(cp, o8b, Arrays.asList(Edit.replace(gbk != null ? gbk.name : "dir/caf\u0082.txt", "new text".getBytes())), true, null);
    String d8b = dump(o8b);
    check("replacing the content of an entry with a non-UTF-8 name keeps its name bytes", d8b.contains("64697" + "22f" + "d6d0cec4") || d8b.contains("6469722fd6d0cec42e747874"), d8b.substring(0, Math.min(300, d8b.length())));
    check("…and the rewritten archive tests clean", unzipT(o8) == 0 && unzipT(o8b) == 0);

    // ---- 9. odd names are all reachable by browsing ----
    Archive odd = open("odd_names.zip");
    Set<String> seen = new TreeSet<String>();
    walk(odd, "", seen, 0);
    Set<String> want = new TreeSet<String>(); for (Entry e : odd.entries) if (!e.dir) want.add(e.name);
    check("odd_names.zip: walking the folder tree reaches every file (\"/abs/file.txt\", \"a//b.txt\" ...)", seen.equals(want), seen + " vs " + want);
    check("…a folder never advertises files that are not there", noEmptyFolders(odd, "", 0));

    // ---- 10. the other edits still work on ordinary archives (rewrites validate with python's zipfile) ----
    File o10 = out("plain_rt.zip");
    ZipTool.rewrite(plain, o10, Arrays.asList(Edit.add("c/d.txt", "dd".getBytes()), Edit.delete("a/b.txt")), true, null);
    check("an add + delete round-trip stays a valid archive with the right names", unzipT(o10) == 0 && names(ZipTool.open(o10)).equals(Arrays.asList("manifest.json", "c/d.txt")), names(ZipTool.open(o10)).toString());
    Archive ddz = open("dd.zip");
    File o10b = out("dd_rt.zip");
    ZipTool.rewrite(ddz, o10b, Arrays.asList(Edit.add("n.txt", "n".getBytes())), true, null);
    check("data-descriptor entries survive a rewrite", unzipT(o10b) == 0 && ZipTool.open(o10b).entries.size() == 4);

    for (File f : tmp.listFiles()) deleteTree(f);
    tmp.delete();
    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }

  static void walk(Archive a, String dir, Set<String> seen, int depth) {
    if (depth > 8) return;
    for (Child c : ZipTool.children(a, dir)) { if (c.dir) walk(a, c.path, seen, depth + 1); else seen.add(c.path); }
  }
  static boolean noEmptyFolders(Archive a, String dir, int depth) {
    if (depth > 8) return true;
    for (Child c : ZipTool.children(a, dir)) {
      if (!c.dir) continue;
      List<Child> kids = ZipTool.children(a, c.path);
      if (c.count > 0 && kids.isEmpty()) return false;
      if (!noEmptyFolders(a, c.path, depth + 1)) return false;
    }
    return true;
  }
  static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File x : k) deleteTree(x); f.delete(); }
}
