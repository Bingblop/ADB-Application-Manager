package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The search query (names, ending, size, date, kind, content:, archive:), the bounded walk, links, hidden files, nested folders on and off, archives, limits and Cancel. */
public class FileSearchTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static final TimeZone UTC = TimeZone.getTimeZone("UTC");
  static final long NOW = 1_750_000_000_000L;                         // 2025-06-15 15:06:40 UTC
  static void write(File f, String s) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }
  static void age(File f, long daysAgo) { f.setLastModified(NOW - daysAgo * 86400000L); }
  static void zip(File f, String[][] entries) throws IOException {
    f.getParentFile().mkdirs();
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
      for (String[] e : entries) { z.putNextEntry(new ZipEntry(e[0])); if (e.length > 1) z.write(e[1].getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
    }
  }
  static List<String> names(List<FileSearch.Hit> h, File root) {
    List<String> r = new ArrayList<String>();
    for (FileSearch.Hit x : h) r.add(x.path.substring(root.getPath().length() + 1) + (x.entry != null ? "!" + x.entry : ""));
    Collections.sort(r);
    return r;
  }
  static List<String> find(File root, String q, boolean hidden, boolean rec, boolean arc) {
    FileSearch.Query query = FileSearch.parse(q, NOW, UTC);
    query.includeHidden = hidden; query.recursive = rec; query.inArchives = arc;
    return names(FileSearch.run(Collections.singletonList(root), query, new FileSearch.Limits(), null), root);
  }
  /** A zip of stored entries with the raw name bytes given and no UTF-8 flag, as Windows Explorer writes non-English names. */
  static void legacyZip(File f, byte[][] names, String[] data) throws IOException { legacyZip(f, names, data, 0, 0); }
  /** As above; the first entry carries these general-purpose flags and this method number (its bytes stay stored). */
  static void legacyZip(File f, byte[][] names, String[] data, int firstFlags, int firstMethod) throws IOException {
    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(), cd = new java.io.ByteArrayOutputStream();
    int dosDate = (40 << 9) | (1 << 5) | 1;                                 // 2020-01-01
    for (int i = 0; i < names.length; i++) {
      byte[] d = data[i].getBytes(StandardCharsets.UTF_8), n = names[i];
      java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(d);
      long off = bo.size();
      byte[] h = new byte[30]; le(h, 0, 0x04034b50, 4); le(h, 4, 10, 2); le(h, 12, dosDate, 2); le(h, 14, crc.getValue(), 4); le(h, 18, d.length, 4); le(h, 22, d.length, 4); le(h, 26, n.length, 2);
      if (i == 0) { le(h, 6, firstFlags, 2); le(h, 8, firstMethod, 2); }
      bo.write(h); bo.write(n); bo.write(d);
      byte[] c = new byte[46]; le(c, 0, 0x02014b50, 4); le(c, 4, 20, 2); le(c, 6, 10, 2); le(c, 14, dosDate, 2); le(c, 16, crc.getValue(), 4); le(c, 20, d.length, 4); le(c, 24, d.length, 4); le(c, 28, n.length, 2); le(c, 42, off, 4);
      if (i == 0) { le(c, 8, firstFlags, 2); le(c, 10, firstMethod, 2); }
      cd.write(c); cd.write(n);
    }
    long cdOff = bo.size();
    bo.write(cd.toByteArray());
    byte[] e = new byte[22]; le(e, 0, 0x06054b50, 4); le(e, 8, names.length, 2); le(e, 10, names.length, 2); le(e, 12, cd.size(), 4); le(e, 16, cdOff, 4);
    bo.write(e);
    Files.write(f.toPath(), bo.toByteArray());
  }
  static void le(byte[] b, int o, long v, int n) { for (int i = 0; i < n; i++) b[o + i] = (byte) (v >>> (8 * i)); }
  static void rm(File f) { if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) { File[] k = f.listFiles(); if (k != null) for (File x : k) rm(x); } f.delete(); }
  static List<String> l(String... a) { return Arrays.asList(a); }
  static long timed(Runnable r) { long t = System.currentTimeMillis(); r.run(); return System.currentTimeMillis() - t; }

  public static void main(String[] args) throws Exception {
    File root = Files.createTempDirectory("fs-root").toFile();
    write(new File(root, "Photos/IMG_001.jpg"), "jpgdata"); write(new File(root, "Photos/IMG_002.JPG"), "jpgdata2"); write(new File(root, "Photos/Trip/beach.png"), "png");
    write(new File(root, "Docs/report.pdf"), "pdf"); write(new File(root, "Docs/notes.txt"), "Shopping list\nmilk and eggs\nCall Dr. Smith\n"); write(new File(root, "Docs/todo.md"), "# Todo\nbuy MILK\n");
    write(new File(root, "Docs/bin.dat"), "\u0000\u0001milk\u0000"); write(new File(root, ".hidden/secret.txt"), "milk secret"); write(new File(root, "top.txt"), "milk at top");
    write(new File(root, "Big/huge.log"), new String(new char[2000]).replace('\0', 'x')); write(new File(root, "Big/small.log"), "x");
    new File(root, "Empty").mkdirs();
    age(new File(root, "Photos/IMG_001.jpg"), 0); age(new File(root, "Photos/IMG_002.JPG"), 1); age(new File(root, "Photos/Trip/beach.png"), 10); age(new File(root, "Docs/report.pdf"), 100); age(new File(root, "Docs/notes.txt"), 400);
    zip(new File(root, "pack/data.zip"), new String[][]{{"readme.txt", "hello milk"}, {"img/logo.png", "x"}, {"src/Main.java", "class Main {}"}, {"docs/"}});
    zip(new File(root, "pack/app.apk"), new String[][]{{"AndroidManifest.xml", "<manifest/>"}, {"classes.dex", "dex"}});
    write(new File(root, "pack/broken.zip"), "this is not a zip");

    // ---------- names ----------
    check("a name part, case ignored", find(root, "img_00", true, true, false).equals(l("Photos/IMG_001.jpg", "Photos/IMG_002.JPG")));
    check("two parts must both be in the name", find(root, "img 001", true, true, false).equals(l("Photos/IMG_001.jpg")));
    check("a quoted phrase keeps its space", find(root, "\"Photos\"", true, true, false).isEmpty() == false && find(root, "\"no such thing\"", true, true, false).isEmpty());
    check("-word leaves names out", find(root, "log -small", true, true, false).equals(l("Big/huge.log")));
    check("a wildcard", find(root, "img_*.jpg", true, true, false).equals(l("Photos/IMG_001.jpg", "Photos/IMG_002.JPG")));
    check("? is one letter", find(root, "img_00?.jpg", true, true, false).size() == 2 && find(root, "img_0?.jpg", true, true, false).isEmpty());
    check("folders are found by name too", find(root, "trip", true, true, false).equals(l("Photos/Trip")));
    check("an empty query lists nothing", find(root, "", true, true, false).isEmpty() && find(root, "   ", true, true, false).isEmpty());

    // ---------- endings and types ----------
    check("ext:", find(root, "ext:png", true, true, false).equals(l("Photos/Trip/beach.png")));
    check("ext: a list, with dots, any case", find(root, "ext:.JPG,png", true, true, false).equals(l("Photos/IMG_001.jpg", "Photos/IMG_002.JPG", "Photos/Trip/beach.png")));
    check(".ending on its own", find(root, ".pdf", true, true, false).equals(l("Docs/report.pdf")));
    check("type:image", find(root, "type:image", true, true, false).equals(l("Photos/IMG_001.jpg", "Photos/IMG_002.JPG", "Photos/Trip/beach.png")));
    check("type:folder", find(root, "type:folder", true, true, false).containsAll(l("Photos", "Docs", "Empty", "Photos/Trip")) && !find(root, "type:folder", true, true, false).contains("top.txt"));
    check("type:text|doc", find(root, "type:text|doc", true, true, false).containsAll(l("Docs/notes.txt", "Docs/report.pdf", "top.txt")));
    check("type:archive and type:apk", find(root, "type:archive", true, true, false).equals(l("pack/broken.zip", "pack/data.zip")) && find(root, "type:apk", true, true, false).equals(l("pack/app.apk")));
    check("an unknown kind is reported, and the rest still works", FileSearch.parse("type:banana img", NOW, UTC).problems.size() == 1 && FileSearch.parse("type:banana img", NOW, UTC).names.size() == 1);

    // ---------- size ----------
    check("size:>1k", find(root, "size:>1k", true, true, false).equals(l("Big/huge.log")));
    check("size:<10 (bytes)", find(root, "size:<10 ext:log", true, true, false).equals(l("Big/small.log")));
    check("size range (7 and 8 bytes)", find(root, "size:7..8 ext:jpg", true, true, false).equals(l("Photos/IMG_001.jpg", "Photos/IMG_002.JPG")) && find(root, "size:8..7 ext:jpg", true, true, false).size() == 2 && find(root, "size:8 ext:jpg", true, true, false).equals(l("Photos/IMG_002.JPG")));
    check("units: 1.5k is 1536", FileSearch.parseBytes("1.5k") == 1536 && FileSearch.parseBytes("2MB") == 2L * 1024 * 1024 && FileSearch.parseBytes("1g") == 1L << 30 && FileSearch.parseBytes("12") == 12 && FileSearch.parseBytes("x") == -1);
    check("a size never matches a folder", find(root, "size:>0 type:folder", true, true, false).isEmpty());
    check("a bad size is reported", FileSearch.parse("size:big", NOW, UTC).problems.size() == 1);

    // ---------- date ----------
    check("date:today", find(root, "date:today", true, true, false).contains("Photos/IMG_001.jpg") && !find(root, "date:today", true, true, false).contains("Photos/IMG_002.JPG"));
    check("date:yesterday", find(root, "date:yesterday ext:jpg", true, true, false).equals(l("Photos/IMG_002.JPG")));
    check("date:7d (the last 7 days)", find(root, "date:7d ext:jpg,png", true, true, false).equals(l("Photos/IMG_001.jpg", "Photos/IMG_002.JPG")));
    check("date:30d", find(root, "date:30d ext:jpg,png", true, true, false).size() == 3);
    check("date:<2025-01-01", find(root, "date:<2025-01-01 type:text|doc", true, true, false).contains("Docs/notes.txt") && !find(root, "date:<2025-01-01 type:text|doc", true, true, false).contains("Docs/report.pdf"));
    check("date range", find(root, "date:2025-03-01..2025-03-31 type:doc", true, true, false).equals(l("Docs/report.pdf")));
    check("date:>2025-06-14 (after that day) and date:>=2025-06-14", find(root, "date:>2025-06-14 ext:jpg", true, true, false).equals(l("Photos/IMG_001.jpg")) && find(root, "date:>=2025-06-14 ext:jpg", true, true, false).size() == 2 && find(root, "date:2025-06-14 ext:jpg", true, true, false).equals(l("Photos/IMG_002.JPG")));
    check("a bad date is reported", FileSearch.parse("date:someday", NOW, UTC).problems.size() == 1 && FileSearch.parse("date:2025-02-30", NOW, UTC).problems.size() == 1);

    // ---------- content: ----------
    check("content: finds text files (and not binary ones), case ignored", find(root, "content:milk", true, true, false).equals(l(".hidden/secret.txt", "Docs/notes.txt", "Docs/todo.md", "top.txt")));
    FileSearch.Query cq = FileSearch.parse("content:\"dr. smith\"", NOW, UTC);
    List<FileSearch.Hit> ch = FileSearch.run(Collections.singletonList(root), cq, new FileSearch.Limits(), null);
    check("content: gives the line and its number", ch.size() == 1 && ch.get(0).lineNo == 3 && ch.get(0).line.equals("Call Dr. Smith") && ch.get(0).why.equals("content"));
    check("content: two words must both be in the file", find(root, "content:milk content:eggs", true, true, false).equals(l("Docs/notes.txt")));
    check("content: combined with a name and an ending", find(root, "content:milk ext:md", true, true, false).equals(l("Docs/todo.md")));
    check("content: does not look inside images or archives", find(root, "content:jpgdata", true, true, false).isEmpty());

    // ---------- hidden and nested ----------
    check("hidden files can be left out", !find(root, "content:milk", false, true, false).contains(".hidden/secret.txt") && find(root, "secret", false, true, false).isEmpty() && find(root, "secret", true, true, false).equals(l(".hidden/secret.txt")));
    check("not nested: only the folder itself", find(new File(root, "Photos"), "ext:jpg,png", true, false, false).equals(l("IMG_001.jpg", "IMG_002.JPG")));
    check("not nested leaves out the png in Trip, nested finds it", find(new File(root, "Photos"), "ext:png", true, false, false).isEmpty() && find(new File(root, "Photos"), "ext:png", true, true, false).size() == 1);

    // ---------- archives ----------
    check("archive: finds entries by name", find(root, "archive:readme", true, true, false).equals(l("pack/data.zip!readme.txt")));
    check("archive: matches the whole entry path (a folder name inside)", find(root, "archive:img/", true, true, false).equals(l("pack/data.zip!img/logo.png")));
    check("archive: with a wildcard", find(root, "archive:*.java", true, true, false).equals(l("pack/data.zip!src/Main.java")));
    check("archive: finds the folder entry too", find(root, "archive:docs/", true, true, false).equals(l("pack/data.zip!docs/")));
    check("archive: in an apk", find(root, "archive:classes", true, true, false).equals(l("pack/app.apk!classes.dex")));
    check("archive: with a kind of the entry", find(root, "archive:logo type:image", true, true, false).equals(l("pack/data.zip!img/logo.png")) && find(root, "archive:logo type:text", true, true, false).isEmpty());
    check("a broken archive is skipped without an error", find(root, "archive:anything", true, true, false).isEmpty());
    // a zip from Windows Explorer: an entry name in a legacy code page (GBK bytes), no UTF-8 flag. java.util.zip.ZipFile refuses the name, which
    // dropped every entry of the archive; the names are read as the archive viewer reads them (Latin-1 for the one that is not UTF-8)
    File legacyRoot = Files.createTempDirectory("fs-legacy").toFile();
    legacyZip(new File(legacyRoot, "legacy.zip"), new byte[][]{"readme.txt".getBytes(StandardCharsets.US_ASCII), {(byte) 0xC4, (byte) 0xE3, '.', 't', 'x', 't'}}, new String[]{"hello milk", "other"});
    check("archive: finds an entry of a zip with a non-UTF-8 name in it", find(legacyRoot, "archive:readme", true, true, false).equals(l("legacy.zip!readme.txt")));
    check("and lists the legacy-named entry under the name the archive viewer shows", find(legacyRoot, "archive:.txt", true, true, false).equals(l("legacy.zip!readme.txt", "legacy.zip!\u00c4\u00e3.txt")));
    check("content: looks inside its entries as well", find(legacyRoot, "content:milk", true, true, true).equals(l("legacy.zip!readme.txt")));
    rm(legacyRoot);
    // an entry that can't be read (a password, a method such as bzip2, damage) is no match, and the entries after it are still read
    File oddRoot = Files.createTempDirectory("fs-odd").toFile();
    byte[][] secretFirst = {"secret.txt".getBytes(StandardCharsets.US_ASCII), "readme.txt".getBytes(StandardCharsets.US_ASCII)};
    String[] secretData = {"milk in the secret", "hello milk"};
    legacyZip(new File(oddRoot, "enc.zip"), secretFirst, secretData, 1, 0);                  // flag bit 0: encrypted, and no password is given
    legacyZip(new File(oddRoot, "bz.zip"), secretFirst, secretData, 0, 12);                  // method 12: bzip2
    zip(new File(oddRoot, "dmg.zip"), new String[][]{{"secret.txt", "milk in the secret"}, {"readme.txt", "hello milk"}});
    byte[] dmg = Files.readAllBytes(new File(oddRoot, "dmg.zip").toPath());
    dmg[30 + "secret.txt".length() + ((dmg[28] & 0xff) | (dmg[29] & 0xff) << 8)] = (byte) 0xFF;   // the first deflate block: a reserved block type
    Files.write(new File(oddRoot, "dmg.zip").toPath(), dmg);
    check("content: an unreadable entry does not end the search of its zip " + find(oddRoot, "content:milk", true, true, true),
        find(oddRoot, "content:milk", true, true, true).equals(l("bz.zip!readme.txt", "dmg.zip!readme.txt", "enc.zip!readme.txt")));
    rm(oddRoot);
    check("without the archive option a plain name does not look inside archives", find(root, "readme", true, true, false).isEmpty());
    check("with it, a plain name matches the entries as well", find(root, "readme", true, true, true).equals(l("pack/data.zip!readme.txt")));
    check("with it, content: looks at text entries as well", find(root, "content:hello", true, true, true).equals(l("pack/data.zip!readme.txt")));
    check("with it, ext: and type: apply to the entries", find(root, "ext:java", true, true, true).equals(l("pack/data.zip!src/Main.java")) && find(root, "type:image", true, true, true).contains("pack/data.zip!img/logo.png"));

    // ---------- links, special files, limits, cancel ----------
    File outside = Files.createTempDirectory("fs-out").toFile(); write(new File(outside, "outside.txt"), "milk");
    Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
    check("a link to a folder is not entered", find(root, "outside", true, true, false).isEmpty());
    Files.createSymbolicLink(new File(root, "loop").toPath(), root.toPath());
    check("a link loop ends", find(root, "top", true, true, false).equals(l("top.txt")));
    FileSearch.Limits lim = new FileSearch.Limits(); lim.maxResults = 2;
    FileSearch.Query all = FileSearch.parse("ext:jpg,png,txt,md", NOW, UTC);
    List<FileSearch.Hit> few = FileSearch.run(Collections.singletonList(root), all, lim, null);
    check("the result limit stops the walk and says so", few.size() == 2 && lim.hitLimit);
    lim = new FileSearch.Limits(); lim.maxVisited = 5;
    FileSearch.run(Collections.singletonList(root), all, lim, null);
    check("the limit on files looked at stops it", lim.hitLimit && lim.visited <= 6);
    lim = new FileSearch.Limits(); lim.deadlineMs = System.currentTimeMillis() - 1;
    FileSearch.run(Collections.singletonList(root), all, lim, null);
    check("the time limit stops it", lim.hitLimit);
    final FileSearch.Limits cl = new FileSearch.Limits();
    List<FileSearch.Hit> cancelled = FileSearch.run(Collections.singletonList(root), all, cl, new FileSearch.Progress() { public boolean onProgress(String f, int v, int found) { return false; } });
    check("Cancel (progress says false) stops it and is not a limit", cl.cancelled && !cl.hitLimit);
    final List<String> seen = new ArrayList<String>();
    FileSearch.run(Collections.singletonList(root), all, new FileSearch.Limits(), new FileSearch.Progress() { public boolean onProgress(String f, int v, int found) { seen.add(f); return true; } });
    check("progress names the folders and ends with an empty name", !seen.isEmpty() && seen.get(seen.size() - 1).isEmpty());
    check("a root that does not exist adds nothing", FileSearch.run(Collections.singletonList(new File(root, "nope")), all, new FileSearch.Limits(), null).isEmpty());
    check("a root that is a file is looked at by itself", names(FileSearch.run(Collections.singletonList(new File(root, "top.txt")), FileSearch.parse("top", NOW, UTC), new FileSearch.Limits(), null), root).equals(l("top.txt")));
    check("kinds", FileSearch.kindOf("a.JPG", false).equals("image") && FileSearch.kindOf("a.apk", false).equals("apk") && FileSearch.kindOf("x", true).equals("folder") && FileSearch.kindOf("noext", false).equals("file") && FileSearch.kindOf("a.tar.gz", false).equals("archive"));

    // ---------- the review's findings ----------
    check("a name with many stars cannot hang the matcher", timed(new Runnable() { public void run() { FileSearch.nameHas(new String(new char[200]).replace('\0', 'a'), "*a*a*a*a*a*a*a*a*a*a*a*b"); } }) < 2000);
    check("globMatch: stars, question marks, the whole name", FileSearch.globMatch("img_001.jpg", "img_*.jpg") && FileSearch.globMatch("a", "*a*") && FileSearch.globMatch("abc", "a?c") && !FileSearch.globMatch("abc", "a?d") && FileSearch.globMatch("x", "**") && !FileSearch.globMatch("ab", "a") && FileSearch.globMatch("", "*"));
    // a long single line does not use the memory of the whole line; the word at its start is still found
    final int big = 64 * 1024 * 1024;
    java.io.InputStream endless = new java.io.InputStream() {
      long left = 400L * 1024 * 1024; boolean first = true;
      public int read() { return left-- > 0 ? 'x' : -1; }
      public int read(byte[] b, int off, int len) { if (left <= 0) return -1; int k = (int) Math.min(len, left); for (int i = 0; i < k; i++) b[off + i] = 'x'; if (first) { b[off] = 'm'; b[off + 1] = 'i'; b[off + 2] = 'l'; b[off + 3] = 'k'; first = false; } left -= k; return k; }
    };
    Object[] lm = FileSearch.findInText(endless, Collections.singletonList("milk"), 1024 * 1024);
    check("a 400 MB line with the word at its start: found, reading stops at the limit, no huge buffer", lm != null && ((String) lm[1]).startsWith("milkxxx") && ((String) lm[1]).length() <= 160);
    java.io.InputStream shortReads = new java.io.InputStream() {
      byte[] data = "Grüße aus Köln — ключ 日本語\nsecond line milk\n".getBytes(StandardCharsets.UTF_8); int pos;
      public int read() { return pos < data.length ? data[pos++] & 0xFF : -1; }
      public int read(byte[] b, int off, int len) { if (pos >= data.length) return -1; int k = Math.min(Math.min(len, 7), data.length - pos); System.arraycopy(data, pos, b, off, k); pos += k; return k; }
    };
    Object[] sm = FileSearch.findInText(shortReads, Collections.singletonList("milk"), 1 << 20);
    check("a stream that hands out 7 bytes at a time (like an inflater) is still read as text", sm != null && ((Integer) sm[0]) == 2 && ((String) sm[1]).equals("second line milk"));
    // added storage (a content:// tree) reads a file through a stream: the same rules as on the phone's own storage, and a size the provider did not tell is tried
    FileSearch.Limits csl = new FileSearch.Limits();
    FileSearch.Query csq = FileSearch.parse("content:milk", NOW, UTC);
    check("content: on a stream: text eligible, empty / too big / picture / video / sound / archive not, size not told (-1) is tried",
        FileSearch.contentEligible(csl, "notes.txt", 100) && FileSearch.contentEligible(csl, "notes.txt", -1) && !FileSearch.contentEligible(csl, "notes.txt", 0)
        && !FileSearch.contentEligible(csl, "big.txt", csl.contentMaxBytes + 1) && !FileSearch.contentEligible(csl, "a.jpg", 10) && !FileSearch.contentEligible(csl, "a.mp4", 10)
        && !FileSearch.contentEligible(csl, "a.mp3", 10) && !FileSearch.contentEligible(csl, "a.zip", 10));
    Object[] fc = FileSearch.findContent(csq, csl, new java.io.ByteArrayInputStream("first\nbuy Milk today\n".getBytes(StandardCharsets.UTF_8)));
    check("content: on a stream finds the first line with the word (case ignored)", fc != null && ((Integer) fc[0]) == 2 && ((String) fc[1]).equals("buy Milk today"));
    check("content: on a stream: no word, or a binary stream, is no hit", FileSearch.findContent(csq, csl, new java.io.ByteArrayInputStream("nothing here".getBytes(StandardCharsets.UTF_8))) == null
        && FileSearch.findContent(csq, csl, new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 0, 0, 3, 'm', 'i', 'l', 'k'})) == null);
    File dots = Files.createTempDirectory("fs-dot").toFile(); write(new File(dots, ".gitignore"), "x"); write(new File(dots, ".bashrc"), "y"); write(new File(dots, "a.gitignore"), "z");
    check("a dot file is found by its name with the dot, and by ext:", names(FileSearch.run(Collections.singletonList(dots), FileSearch.parse(".gitignore", NOW, UTC), new FileSearch.Limits(), null), dots).equals(l(".gitignore", "a.gitignore")) && find(dots, ".bashrc", true, true, false).equals(l(".bashrc")));
    rm(dots);
    check("a day whose midnight does not exist in the zone is still a day (America/Sao_Paulo 2018-11-04)", FileSearch.parse("date:2018-11-04", NOW, TimeZone.getTimeZone("America/Sao_Paulo")).problems.isEmpty() && FileSearch.parse("date:2018-03-11..2018-03-12", NOW, TimeZone.getTimeZone("America/Havana")).problems.isEmpty());
    check("a day that is not a day is still refused", FileSearch.parse("date:2025-13-01", NOW, UTC).problems.size() == 1 && FileSearch.parse("date:2025-02-30", NOW, UTC).problems.size() == 1);
    // a guarded search (Limits.skip, as the app sets it): the app's own private data is not listed, not read for content, not descended into, also through a link
    File sk = Files.createTempDirectory("fs-skip").toFile();
    File skData = new File(sk, "data/app");
    write(new File(skData, "shared_prefs/prefs.xml"), "milk secret"); write(new File(skData, "files/adb_home/.android/adbkey"), "milk key");
    write(new File(skData, "cache/share/note.txt"), "milk for everyone"); write(new File(skData, "cache/backup_data_1.tar"), "milk backup");
    write(new File(sk, "sdcard/Download/list.txt"), "milk and bread");
    final File skDataF = skData;
    final PrivatePaths.Root[] skAllowed = PrivatePaths.exportable(new File(skData, "files"), new File(skData, "cache"));
    final PrivatePaths.Links skL = new PrivatePaths.Links() { public long count(File f) throws Exception { return ((Number) Files.getAttribute(f.toPath(), "unix:nlink")).longValue(); } };
    FileSearch.Skip guard = new FileSearch.Skip() { public boolean skip(File f) { return PrivatePaths.blocked(f, skDataF, skL, skAllowed); } };
    boolean skLinks;
    try { Files.createSymbolicLink(new File(sk, "sdcard/Download/to-prefs.txt").toPath(), new File(skData, "shared_prefs/prefs.xml").toPath()); Files.createSymbolicLink(new File(sk, "sdcard/Download/to-data").toPath(), skData.toPath()); skLinks = true; } catch (Exception e) { skLinks = false; }
    FileSearch.Limits gl = new FileSearch.Limits(); gl.skip = guard;
    FileSearch.Query gq = FileSearch.parse("content:milk", NOW, UTC); gq.recursive = true;
    List<String> gAll = names(FileSearch.run(Arrays.asList(new File(sk, "data/app/cache/share"), new File(sk, "data/app/cache"), new File(sk, "sdcard")), gq, gl, null), sk);
    check("guarded content: search finds the allowed cache file and the user's file, nothing from the private part: " + gAll,
        gAll.contains("data/app/cache/share/note.txt") && gAll.contains("sdcard/Download/list.txt")
        && !gAll.contains("data/app/shared_prefs/prefs.xml") && !gAll.contains("data/app/files/adb_home/.android/adbkey") && !gAll.contains("data/app/cache/backup_data_1.tar"));
    check("guarded: a search that starts at the folder above the data folder or at the data folder finds nothing in it (the data folder itself is private)",
        FileSearch.run(Arrays.asList(new File(sk, "data"), new File(sk, "data/app")), gq, gl, null).isEmpty());
    if (skLinks) check("guarded: a link to a private file, and a link to the data folder, give no hit and nothing below them: " + gAll,
        !gAll.contains("sdcard/Download/to-prefs.txt") && !gAll.contains("sdcard/Download/to-data/shared_prefs/prefs.xml"));
    FileSearch.Query gn = FileSearch.parse("prefs", NOW, UTC); gn.recursive = true;
    List<String> gNames = names(FileSearch.run(Collections.singletonList(new File(sk, "data")), gn, gl, null), sk);
    check("guarded name search does not list a private file's name either: " + gNames, gNames.isEmpty());
    check("a root that is itself private is skipped whole", FileSearch.run(Collections.singletonList(new File(skData, "shared_prefs")), gn, gl, null).isEmpty());
    FileSearch.Limits open = new FileSearch.Limits();
    check("without a guard (the default) the walk is as before: the private file is found", names(FileSearch.run(Collections.singletonList(new File(sk, "data")), gn, open, null), sk).contains("data/app/shared_prefs/prefs.xml"));
    FileSearch.Limits boom = new FileSearch.Limits(); boom.skip = new FileSearch.Skip() { public boolean skip(File f) { throw new IllegalStateException("x"); } };
    check("a guard that throws counts as skipped (fails closed, no exception out of the search)", FileSearch.run(Collections.singletonList(new File(sk, "sdcard")), gq, boom, null).isEmpty());
    rm(sk);
    rm(root); rm(outside);
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
