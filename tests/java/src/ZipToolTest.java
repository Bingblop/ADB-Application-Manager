import com.bloatware.bingblop.ZipTool;
import com.bloatware.bingblop.ZipTool.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

public class ZipToolTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  static final String FIX = System.getProperty("zipfix1") + "/";
  static File tmpDir;

  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
    while ((n = in.read(b)) > 0) bo.write(b, 0, n); in.close(); return bo.toByteArray();
  }
  static byte[] content(Archive a, String name) throws IOException { return readAll(a.open(a.find(name))); }
  static String text(Archive a, String name) throws IOException { return new String(content(a, name), "UTF-8"); }
  static long crc(byte[] b) { CRC32 c = new CRC32(); c.update(b, 0, b.length); return c.getValue(); }

  /** Every entry streams to exactly its declared size and CRC. */
  static boolean allCrcOk(Archive a) throws IOException {
    for (Entry e : a.entries) {
      if (e.dir || e.encrypted()) continue;
      InputStream in = a.open(e); CRC32 c = new CRC32(); byte[] b = new byte[65536]; int n; long total = 0;
      while ((n = in.read(b)) > 0) { c.update(b, 0, n); total += n; } in.close();
      if (total != e.size || c.getValue() != e.crc) { System.out.println("   bad entry " + e.name + " size " + total + "/" + e.size); return false; }
    }
    return true;
  }
  static int sh(String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true);
    Process p = pb.start(); readAll(p.getInputStream()); return p.waitFor();
  }
  static String shOut(String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true);
    Process p = pb.start(); String s = new String(readAll(p.getInputStream()), "UTF-8"); p.waitFor(); return s;
  }
  /** Independent verification with unzip, python and the JDK reader. */
  static boolean validExternally(File f, String pw) throws Exception {
    int u = pw == null ? sh("unzip", "-tq", f.getPath()) : sh("unzip", "-P", pw, "-tq", f.getPath());
    boolean py = pw != null || sh("python3", "-c", "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); sys.exit(0 if z.testzip() is None else 1)", f.getPath()) == 0;
    boolean jdk = true;
    if (pw == null) {
      try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
        for (Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements();) {
          java.util.zip.ZipEntry e = en.nextElement(); readAll(z.getInputStream(e));
        }
      } catch (Exception ex) { System.out.println("   jdk: " + ex); jdk = false; }
    }
    if (u != 0 || !py || !jdk) System.out.println("   external: unzip=" + u + " py=" + py + " jdk=" + jdk);
    return u == 0 && py && jdk;
  }
  static File out(String n) { return new File(tmpDir, n); }
  static Set<String> names(Archive a) { Set<String> s = new LinkedHashSet<>(); for (Entry e : a.entries) s.add(e.name); return s; }
  static String childNames(List<Child> l) { StringBuilder sb = new StringBuilder(); for (Child c : l) sb.append(c.name).append(c.dir ? "/" : "").append(","); return sb.toString(); }

  public static void main(String[] args) throws Exception {
    tmpDir = Files.createTempDirectory("ziptool").toFile();

    // ---------- opening and listing ----------
    Archive a = ZipTool.open(new File(FIX + "basic.zip"));
    String pyNames = shOut("python3", "-c", "import zipfile,sys; print('\\n'.join(zipfile.ZipFile(sys.argv[1]).namelist()))", FIX + "basic.zip");
    Set<String> py = new LinkedHashSet<>(Arrays.asList(pyNames.trim().split("\n")));
    check("basic: entry names match python (" + a.entries.size() + ")", names(a).equals(py));
    check("basic: archive comment", "hello comment".equals(new String(a.comment, "UTF-8")));
    check("basic: not zip64", !a.zip64);
    check("basic: every entry streams with the right size and CRC", allCrcOk(a));
    check("basic: unicode name found", a.find("ünï/çödé.txt") != null && text(a, "ünï/çödé.txt").equals("unicode name ✓"));
    check("basic: text content", "hello\nworld\n".equals(text(a, "readme.txt")));
    check("basic: empty entry", content(a, "empty.bin").length == 0);
    check("basic: stored entry method 0, deflated 8", a.find("img/pic.png").method == 0 && a.find("readme.txt").method == 8);

    List<Child> root = ZipTool.children(a, "");
    check("root children: folders first, case-insensitive order (" + childNames(root) + ")",
        childNames(root).equals("docs/,img/,ünï/,big.txt,bin.dat,crlf.txt,empty.bin,readme.txt,"));
    Child docs = root.get(0);
    long docsSize = a.find("docs/a.txt").size + a.find("docs/sub/deep/b.md").size;
    check("folder aggregates (files, size)", docs.dir && docs.count == 2 && docs.size == docsSize && docs.path.equals("docs/") && docs.entry != null);
    check("implicit folders have no entry", ZipTool.children(a, "docs/").get(0).name.equals("sub") && ZipTool.children(a, "docs/").get(0).entry == null);
    check("docs/ children", childNames(ZipTool.children(a, "docs/")).equals("sub/,a.txt,"));
    check("deep children", childNames(ZipTool.children(a, "docs/sub/")).equals("deep/,") && childNames(ZipTool.children(a, "docs/sub/deep/")).equals("b.md,"));
    check("children of a missing folder are empty", ZipTool.children(a, "nope/").isEmpty());
    check("search is case-insensitive over full paths", ZipTool.search(a, "A.TXT", 10).size() == 1 && ZipTool.search(a, "deep", 10).size() == 1 && ZipTool.search(a, "", 10).isEmpty());
    check("search limit", ZipTool.search(a, "t", 2).size() == 2);
    check("under(): tree and single", ZipTool.under(a, "docs/").size() == 3 && ZipTool.under(a, "readme.txt").size() == 1);
    check("Entry.baseName", a.find("docs/sub/deep/b.md").baseName().equals("b.md") && a.find("docs/").baseName().equals("docs"));
    check("mtime parsed", a.find("readme.txt").mtime() > 1500000000000L);

    // ---------- other archives ----------
    Archive st = ZipTool.open(new File(FIX + "stream.zip"));
    check("data-descriptor archive reads (flag 8 set: " + ((st.find("a.txt").flags & 8) != 0) + ")", (st.find("a.txt").flags & 8) != 0 && allCrcOk(st) && text(st, "a.txt").startsWith("streamed streamed"));

    Archive enc = ZipTool.open(new File(FIX + "enc.zip"));
    boolean needPw = false; try { enc.open(enc.find("secret.txt")); } catch (ZipTool.NeedPassword e) { needPw = !e.wrong; }
    check("encrypted entry is flagged and asks for a password (not wrong, since none was given)", enc.find("secret.txt").encrypted() && needPw);
    enc.setPassword("wrong".toCharArray());
    boolean wrongRejected = false;
    try { String got = text(enc, "secret.txt"); wrongRejected = !got.startsWith("top secret"); } catch (ZipTool.NeedPassword e) { wrongRejected = e.wrong; } catch (IOException ignored) { wrongRejected = true; }
    check("a wrong password never yields the real text (refused up front, or the CRC catches it)", wrongRejected);
    enc.setPassword("pw123".toCharArray());
    check("the right password decrypts it", text(enc, "secret.txt").startsWith("top secret\ntop secret\n"));

    long t0 = System.currentTimeMillis();
    Archive many = ZipTool.open(new File(FIX + "many.zip"));
    List<Child> mroot = ZipTool.children(many, "");
    long dt = System.currentTimeMillis() - t0;
    check("70000-entry zip64 archive: count, flag, folders (" + dt + " ms)", many.entries.size() == 70000 && many.zip64 && mroot.size() == 50 && dt < 3000);
    check("zip64 folder aggregate", ZipTool.children(many, "d0/").size() == 1400);
    long t1 = System.currentTimeMillis();
    check("all 70000 entries stream (CRC)", allCrcOk(many));
    check("zip64 central-directory extra: 5 GB sizes parsed", true);
    Archive z64 = ZipTool.open(new File(FIX + "zip64synth.zip"));
    Entry huge = z64.find("huge.bin");
    check("zip64 extra field gives real sizes", huge != null && huge.size == 5L * (1L << 30) && huge.csize == 4L * (1L << 30) && huge.lho == 0 && z64.zip64);

    Archive tiny = ZipTool.open(new File(FIX + "tiny.zip"));
    check("empty archive (EOCD only) opens with 0 entries", tiny.entries.isEmpty() && ZipTool.children(tiny, "").isEmpty());
    boolean notZip = false; try { ZipTool.open(new File(FIX + "notzip.bin")); } catch (IOException e) { notZip = true; }
    boolean trunc = false; try { ZipTool.open(new File(FIX + "truncated.zip")); } catch (IOException e) { trunc = true; }
    check("random bytes and a truncated zip are rejected", notZip && trunc);

    // ---------- extraction ----------
    File ex1 = out("ex1"); ex1.mkdirs();
    long n = ZipTool.extractTo(a, a.find("docs/a.txt"), new File(ex1, "sub/dir/a.txt"));
    check("extractTo writes the entry (parents created)", n == a.find("docs/a.txt").size && Arrays.equals(Files.readAllBytes(new File(ex1, "sub/dir/a.txt").toPath()), content(a, "docs/a.txt")));
    File ex2 = out("ex2");
    long[] r = ZipTool.extractTree(a, "docs/", ex2, null);
    check("extractTree keeps the folder name at the top (files=" + r[0] + ")", r[0] == 2 && new File(ex2, "docs/a.txt").isFile() && new File(ex2, "docs/sub/deep/b.md").isFile() && r[2] == 0);
    File ex3 = out("ex3");
    r = ZipTool.extractTree(a, "readme.txt", ex3, null);
    check("extractTree of a single file lands directly in the folder", r[0] == 1 && new File(ex3, "readme.txt").isFile());
    File ex4 = out("ex4");
    r = ZipTool.extractTree(a, "", ex4, null);
    int files = 0; for (Entry e : a.entries) if (!e.dir) files++;
    check("extract everything keeps the folder structure (" + r[0] + " of " + files + " files)", r[0] == files && Arrays.equals(Files.readAllBytes(new File(ex4, "bin.dat").toPath()), content(a, "bin.dat")) && new File(ex4, "docs/sub/deep/b.md").isFile() && !new File(ex4, "b.md").exists());
    List<String> seen = new ArrayList<>();
    File ex5 = out("ex5");
    ZipTool.extractTree(a, "docs/", ex5, (done, cnt, cur) -> { seen.add(cur); return true; });
    check("progress callback sees every file", seen.contains("docs/a.txt") && seen.contains("docs/sub/deep/b.md"));
    boolean cancelled = false;
    try { ZipTool.extractTree(a, "", out("ex6"), (done, cnt, cur) -> false); } catch (IOException e) { cancelled = e.getMessage().equals("Cancelled"); }
    check("progress callback can cancel", cancelled);

    // zip-slip
    Archive evil = ZipTool.open(new File(FIX + "evil.zip"));
    File victimRoot = out("slip"); File dest = new File(victimRoot, "dest"); dest.mkdirs();
    r = ZipTool.extractTree(evil, "", dest, null);
    boolean escaped = new File(victimRoot, "evil.txt").exists() || new File(tmpDir, "evil.txt").exists() || new File("/tmp/evil.txt").exists() || new File(victimRoot, "b.txt").exists();
    check("zip-slip: nothing escapes, 2 traversal names skipped (files=" + r[0] + " skipped=" + r[2] + ")", !escaped && r[2] == 2 && r[0] == 4);
    check("zip-slip: absolute / ./ / backslash names are normalized inside the folder",
        new File(dest, "abs/path.txt").isFile() && new File(dest, "dot/seg.txt").isFile() && new File(dest, "back/slash/win.txt").isFile() && new File(dest, "ok/fine.txt").isFile());

    // safeName
    check("safeName basics", "a/b.txt".equals(ZipTool.safeName("a/b.txt")) && "a/b/".equals(ZipTool.safeName("a/b/")) && "a/b.txt".equals(ZipTool.safeName("/a//./b.txt")) && "a/b".equals(ZipTool.safeName("a\\b")));
    check("safeName rejects traversal / empty / control chars", ZipTool.safeName("../x") == null && ZipTool.safeName("a/../b") == null && ZipTool.safeName("") == null && ZipTool.safeName("/") == null && ZipTool.safeName("a\u0000b") == null && ZipTool.safeName(null) == null);

    // ---------- sniffing ----------
    byte[] png = Arrays.copyOf(content(a, "img/pic.png"), 64);
    check("classify: png image, text, binary xml, hex",
        ZipTool.classify("img/pic.png", png, 64).equals("image")
        && ZipTool.classify("readme.txt", "hello\n".getBytes(), 6).equals("text")
        && ZipTool.classify("AndroidManifest.xml", new byte[]{3, 0, 8, 0, 1, 2, 3, 4, 5}, 9).equals("axml")
        && ZipTool.classify("classes.dex", new byte[]{'d', 'e', 'x', '\n', '0', '3', '5', 0}, 8).equals("hex")
        && ZipTool.classify("fake.png", "<svg/>".getBytes(), 6).equals("text"));
    byte[] cut = "café".getBytes("UTF-8"); cut = Arrays.copyOf(cut, cut.length - 1);
    check("UTF-8 check: cut tail ok, bad bytes rejected, strict mode rejects a cut tail",
        ZipTool.isValidUtf8(cut, cut.length, true) && !ZipTool.isValidUtf8(new byte[]{(byte) 0xC3, 0x28}, 2, true) && !ZipTool.isValidUtf8(cut, cut.length, false) && ZipTool.isValidUtf8("é".getBytes("UTF-8"), 2, false));
    check("looksLikeText: NUL means binary", !ZipTool.looksLikeText(new byte[]{'a', 0, 'b'}, 3) && ZipTool.looksLikeText(new byte[0], 0));
    String hex = ZipTool.hexDump("Hello, world! 0123456789".getBytes(), 24, 16);
    check("hexDump format", hex.startsWith("00000000  48 65 6c 6c 6f 2c 20 77  6f 72 6c 64 21 20 30 31  |Hello, world! 01|\n") && hex.split("\n").length == 1);
    check("imageMime", ZipTool.imageMime("a.PNG").equals("image/png") && ZipTool.imageMime("b.jpeg").equals("image/jpeg") && ZipTool.imageMime("c.webp").equals("image/webp"));
    Head hd = ZipTool.readHead(a, a.find("big.txt"), 1000);
    check("readHead truncates and says so", hd.data.length == 1000 && hd.truncated && !ZipTool.readHead(a, a.find("readme.txt"), 1000).truncated);

    // ---------- rewriting ----------
    File o1 = out("noedit.zip");
    ZipTool.rewrite(a, o1, new ArrayList<Edit>(), false, null);
    Archive b = ZipTool.open(o1);
    boolean sameSizes = true;
    for (Entry e : a.entries) { Entry f = b.find(e.name); if (f == null || f.csize != e.csize || f.size != e.size || f.crc != e.crc || f.method != e.method) sameSizes = false; }
    check("rewrite without edits: valid for unzip + python + JDK", validExternally(o1, null));
    check("rewrite without edits: same entries, order, sizes, CRCs, methods (raw copy)", names(b).equals(names(a)) && sameSizes && allCrcOk(b));
    check("rewrite keeps the archive comment", "hello comment".equals(new String(b.comment, "UTF-8")));
    check("rewrite keeps unicode names", b.find("ünï/çödé.txt") != null);

    File o2 = out("delete.zip");
    ZipTool.rewrite(a, o2, Arrays.asList(Edit.delete("readme.txt"), Edit.deleteTree("docs/")), false, null);
    Archive d = ZipTool.open(o2);
    check("delete entry + delete folder tree", validExternally(o2, null) && d.find("readme.txt") == null && d.find("docs/") == null && d.find("docs/a.txt") == null && d.find("docs/sub/deep/b.md") == null && d.find("big.txt") != null && d.entries.size() == a.entries.size() - 4);

    File o3 = out("rename.zip");
    ZipTool.rewrite(a, o3, Arrays.asList(Edit.rename("readme.txt", "README.md"), Edit.renameTree("docs/", "documents/"), Edit.rename("ünï/çödé.txt", "ünï/日本.txt")), false, null);
    Archive rn = ZipTool.open(o3);
    check("rename entry, folder tree and to a non-ASCII name", validExternally(o3, null) && rn.find("README.md") != null && text(rn, "README.md").equals("hello\nworld\n") && rn.find("readme.txt") == null
        && rn.find("documents/") != null && rn.find("documents/a.txt") != null && rn.find("documents/sub/deep/b.md") != null && rn.find("docs/a.txt") == null
        && rn.find("ünï/日本.txt") != null && (rn.find("ünï/日本.txt").flags & 0x800) != 0);
    boolean collide = false; File o3b = out("collide.zip");
    try { ZipTool.rewrite(a, o3b, Arrays.asList(Edit.rename("readme.txt", "crlf.txt")), false, null); } catch (IOException e) { collide = e.getMessage().contains("already exists"); }
    check("rename onto an existing name is refused and leaves no output", collide && !o3b.exists());
    boolean unsafe = false; try { ZipTool.rewrite(a, out("unsafe.zip"), Arrays.asList(Edit.rename("readme.txt", "../evil")), false, null); } catch (IOException e) { unsafe = true; }
    check("rename to an unsafe name is refused", unsafe && !out("unsafe.zip").exists());

    File o4 = out("replace.zip");
    byte[] newPic = new byte[777]; new Random(3).nextBytes(newPic); System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, 0, newPic, 0, 4);
    ZipTool.rewrite(a, o4, Arrays.asList(Edit.replace("readme.txt", "changed ✓\nline2\n".getBytes("UTF-8")), Edit.replace("img/pic.png", newPic)), false, null);
    Archive rp = ZipTool.open(o4);
    check("replace text (stays deflated) and a stored entry (stays stored)", validExternally(o4, null) && text(rp, "readme.txt").equals("changed ✓\nline2\n") && rp.find("readme.txt").method == 8
        && Arrays.equals(content(rp, "img/pic.png"), newPic) && rp.find("img/pic.png").method == 0 && names(rp).equals(names(a)) && allCrcOk(rp));
    check("replace keeps the entry's position", new ArrayList<>(names(rp)).indexOf("readme.txt") == new ArrayList<>(names(a)).indexOf("readme.txt"));

    File addSrc = out("added_src.bin"); byte[] addBytes = new byte[300000]; for (int i = 0; i < addBytes.length; i++) addBytes[i] = (byte) (i % 251 < 100 ? 'x' : i);
    Files.write(addSrc.toPath(), addBytes);
    File o5 = out("add.zip");
    ZipTool.rewrite(a, o5, Arrays.asList(Edit.add("new/deep/added.bin", addSrc), Edit.add("notes.txt", "added text\n".getBytes()), Edit.add("pics/new.png", newPic), Edit.add("emptydir/", new byte[0])), false, null);
    Archive ad = ZipTool.open(o5);
    check("add file (streamed deflate), bytes, already-compressed (stored) and a folder", validExternally(o5, null)
        && Arrays.equals(content(ad, "new/deep/added.bin"), addBytes) && ad.find("new/deep/added.bin").method == 8 && ad.find("new/deep/added.bin").csize < addBytes.length / 2
        && text(ad, "notes.txt").equals("added text\n") && ad.find("pics/new.png").method == 0 && ad.find("emptydir/").dir && ad.entries.size() == a.entries.size() + 4 && allCrcOk(ad));
    boolean dup = false; try { ZipTool.rewrite(a, out("dup.zip"), Arrays.asList(Edit.add("readme.txt", new byte[1])), false, null); } catch (IOException e) { dup = e.getMessage().contains("already exists"); }
    check("adding an existing name is refused", dup);
    boolean badAdd = false; try { ZipTool.rewrite(a, out("bad.zip"), Arrays.asList(Edit.add("../../x", new byte[1])), false, null); } catch (IOException e) { badAdd = true; }
    check("adding an unsafe name is refused", badAdd);

    File o6 = out("combo.zip");
    ZipTool.rewrite(a, o6, Arrays.asList(Edit.deleteTree("img/"), Edit.rename("big.txt", "docs/big.txt"), Edit.replace("crlf.txt", "x\r\ny\r\n".getBytes()), Edit.add("docs/sub/extra.txt", "e".getBytes())), false, null);
    Archive cb = ZipTool.open(o6);
    check("several edits in one pass", validExternally(o6, null) && cb.find("img/pic.png") == null && cb.find("docs/big.txt") != null && cb.find("big.txt") == null && text(cb, "crlf.txt").equals("x\r\ny\r\n") && text(cb, "docs/sub/extra.txt").equals("e"));

    // chained rewrites stay valid
    File o7 = out("chain.zip");
    ZipTool.rewrite(cb, o7, Arrays.asList(Edit.renameTree("docs/", "d/"), Edit.delete("crlf.txt")), false, null);
    Archive ch = ZipTool.open(o7);
    check("a rewrite of a rewrite stays valid", validExternally(o7, null) && ch.find("d/big.txt") != null && ch.find("crlf.txt") == null && allCrcOk(ch));

    // data descriptors / encryption
    File o8 = out("stream_out.zip");
    ZipTool.rewrite(st, o8, new ArrayList<Edit>(), false, null);
    Archive so = ZipTool.open(o8);
    check("rewriting a data-descriptor archive clears flag 8 and stays valid", validExternally(o8, null) && (so.find("a.txt").flags & 8) == 0 && allCrcOk(so));
    File o9 = out("enc_out.zip");
    ZipTool.rewrite(enc, o9, Arrays.asList(Edit.add("plain.txt", "p".getBytes())), false, null);
    check("encrypted entries are copied raw and still decrypt with the password", validExternally(o9, "pw123") && ZipTool.open(o9).find("secret.txt").encrypted());
    String dec = shOut("unzip", "-P", "pw123", "-p", o9.getPath(), "secret.txt");
    check("   ...and decrypt to the original text", dec.startsWith("top secret\ntop secret\n"));

    // too many entries for a plain zip -> clear error, no half-written output
    File o10 = out("many_out.zip"); boolean tooMany = false;
    try { ZipTool.rewrite(many, o10, new ArrayList<Edit>(), false, null); } catch (IOException e) { tooMany = e.getMessage().contains("ZIP64"); }
    check("an archive that would need ZIP64 output is refused cleanly", tooMany && !o10.exists());

    // progress + cancel during rewrite
    List<String> prog = new ArrayList<>();
    ZipTool.rewrite(a, out("prog.zip"), new ArrayList<Edit>(), false, (done, cnt, cur) -> { prog.add(cur); return true; });
    boolean rwCancel = false; File o11 = out("cancel.zip");
    try { ZipTool.rewrite(a, o11, new ArrayList<Edit>(), false, (done, cnt, cur) -> false); } catch (IOException e) { rwCancel = e.getMessage().equals("Cancelled"); }
    check("rewrite reports progress and can be cancelled (no partial output)", prog.size() == a.entries.size() && rwCancel && !o11.exists());

    // ---------- APK-style alignment ----------
    Archive ap = ZipTool.open(new File(FIX + "apkish.zip"));
    boolean srcUnaligned = false;
    for (Entry e : ap.entries) if (e.method == 0 && dataOffset(ap.file, e) % 4 != 0) srcUnaligned = true;
    check("fixture really has unaligned stored entries", srcUnaligned);
    File al = out("aligned.zip");
    ZipTool.rewrite(ap, al, Arrays.asList(Edit.add("assets/extra.bin", new byte[]{1, 2, 3}), Edit.replace("assets/config.json", "{}\n".getBytes())), true, null);
    Archive aa = ZipTool.open(al);
    boolean aligned = true;
    for (Entry e : aa.entries) {
      if (e.method != 0) continue;
      long off = dataOffset(al, e);
      int need = e.name.endsWith(".so") ? 16384 : 4;
      if (off % need != 0) { aligned = false; System.out.println("   misaligned " + e.name + " @" + off + " need " + need); }
    }
    check("align=true: stored entries 4-byte aligned, .so page-aligned", aligned && validExternally(al, null) && allCrcOk(aa));
    check("align keeps stored entries stored", aa.find("resources.arsc").method == 0 && aa.find("lib/arm64-v8a/libx.so").method == 0 && Arrays.equals(content(aa, "resources.arsc"), content(ap, "resources.arsc")));
    File na = out("unaligned.zip");
    ZipTool.rewrite(ap, na, new ArrayList<Edit>(), false, null);
    Archive nn = ZipTool.open(na);
    check("align=false: no padding added (valid, same layout)", validExternally(na, null) && nn.find("resources.arsc") != null);

    // a real APK, if one is around: list it, read its manifest bytes, rewrite it
    File realApk = System.getProperty("testapk") == null ? null : new File(System.getProperty("testapk"));
    if (realApk != null && realApk.exists()) {
      Archive ra = ZipTool.open(realApk);
      String pyApk = shOut("python3", "-c", "import zipfile,sys; print(len(zipfile.ZipFile(sys.argv[1]).namelist()))", realApk.getPath()).trim();
      check("real APK: entry count matches python (" + ra.entries.size() + ")", String.valueOf(ra.entries.size()).equals(pyApk));
      Head mh = ZipTool.readHead(ra, ra.find("AndroidManifest.xml"), 4096);
      check("real APK: AndroidManifest.xml is compiled XML", ZipTool.classify("AndroidManifest.xml", mh.data, mh.data.length).equals("axml"));
      check("real APK: all entries stream with the right CRC", allCrcOk(ra));
      File ro = out("real_out.apk");
      ZipTool.rewrite(ra, ro, Arrays.asList(Edit.delete("META-INF/MANIFEST.MF")), true, null);
      Archive rr = ZipTool.open(ro);
      boolean arscStored = rr.find("resources.arsc") != null && rr.find("resources.arsc").method == 0 && dataOffset(ro, rr.find("resources.arsc")) % 4 == 0;
      check("real APK: rewritten, valid, resources.arsc still stored + aligned", validExternally(ro, null) && allCrcOk(rr) && arscStored && rr.find("META-INF/MANIFEST.MF") == null);
    }

    // ---------- comparing archives ----------
    {
      Archive base = ZipTool.open(new File(FIX + "basic.zip"));
      File altF = out("alt.zip");
      ZipTool.rewrite(base, altF, Arrays.asList(Edit.delete("readme.txt"), Edit.replace("crlf.txt", "changed\n".getBytes()), Edit.add("brand/new.txt", "n".getBytes()), Edit.rename("big.txt", "big2.txt"), Edit.deleteTree("img/")), false, null);
      Archive alt = ZipTool.open(altF);
      ZipTool.Diff df = ZipTool.diff(base, alt, 100);
      StringBuilder dAdd = new StringBuilder(), dRem = new StringBuilder(), dChg = new StringBuilder();
      for (ZipTool.DiffItem i : df.added) dAdd.append(i.name).append(",");
      for (ZipTool.DiffItem i : df.removed) dRem.append(i.name).append(",");
      for (ZipTool.DiffItem i : df.changed) dChg.append(i.name).append(",");
      check("diff: added (" + dAdd + ")", dAdd.toString().equals("big2.txt,brand/new.txt,"));
      check("diff: removed (" + dRem + ")", dRem.toString().equals("big.txt,img/pic.png,readme.txt,"));
      check("diff: changed (" + dChg + ")", dChg.toString().equals("crlf.txt,"));
      check("diff: unchanged files counted", df.same == 5 && !df.truncated);
      ZipTool.DiffItem chg = df.changed.get(0);
      check("diff: sizes on both sides, -1 when absent", chg.sizeA == 9 && chg.sizeB == 8 && df.added.get(0).sizeA == -1 && df.removed.get(0).sizeB == -1);
      ZipTool.Diff self = ZipTool.diff(base, base, 100);
      check("diff: an archive against itself is all 'same'", self.added.isEmpty() && self.removed.isEmpty() && self.changed.isEmpty() && self.same == 9);
      ZipTool.Diff dCut = ZipTool.diff(base, alt, 1);
      check("diff: lists are cut at the limit and say so", dCut.truncated && dCut.added.size() == 1 && dCut.removed.size() == 1);
      ZipTool.Diff mm = ZipTool.diff(ZipTool.open(new File(FIX + "many.zip")), ZipTool.open(new File(FIX + "many.zip")), 10);
      check("diff: 70000-entry archives compare quickly and match", mm.same == 70000 && mm.changed.isEmpty());
    }

    // Stale detection
    File stale = out("stale.zip"); Files.copy(new File(FIX + "basic.zip").toPath(), stale.toPath());
    Archive sa = ZipTool.open(stale);
    check("isStale false before a change", !sa.isStale());
    Thread.sleep(20); try (FileOutputStream fo = new FileOutputStream(stale, true)) { fo.write(1); }
    check("isStale true after the file changes", sa.isStale());

    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }

  /** Where an entry's data starts in a file (local header + name + extra). */
  static long dataOffset(File f, Entry e) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
      byte[] h = new byte[30]; raf.seek(e.lho); raf.readFully(h);
      int nl = (h[26] & 0xFF) | ((h[27] & 0xFF) << 8), el = (h[28] & 0xFF) | ((h[29] & 0xFF) << 8);
      return e.lho + 30 + nl + el;
    }
  }
}
