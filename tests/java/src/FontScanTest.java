import com.bloatware.bingblop.FontScan;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The font scan: which files are fonts, what a font calls itself (the name table, in every encoding and shape it comes in), and the bounded walk. */
public class FontScanTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

  // ---------- a font made of just what the scan reads: the sfnt header, a table directory, a name table and an outline table ----------
  static void u16(ByteArrayOutputStream o, int v) { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
  static void u32(ByteArrayOutputStream o, long v) { u16(o, (int) ((v >> 16) & 0xFFFF)); u16(o, (int) (v & 0xFFFF)); }
  static void tag(ByteArrayOutputStream o, String t) { for (int i = 0; i < 4; i++) o.write(t.charAt(i)); }

  /** rec: {platform, encoding, language, nameId, text}. */
  static byte[] nameTable(Object[][] recs) throws IOException {
    ByteArrayOutputStream strings = new ByteArrayOutputStream(), head = new ByteArrayOutputStream();
    u16(head, 0); u16(head, recs.length); u16(head, 6 + recs.length * 12);
    for (Object[] r : recs) {
      int platform = (Integer) r[0];
      byte[] b = ((String) r[4]).getBytes(Charset.forName(platform == 1 ? "ISO-8859-1" : "UTF-16BE"));
      u16(head, platform); u16(head, (Integer) r[1]); u16(head, (Integer) r[2]); u16(head, (Integer) r[3]); u16(head, b.length); u16(head, strings.size());
      strings.write(b);
    }
    head.write(strings.toByteArray());
    return head.toByteArray();
  }

  /** version: "ttf" / "otf" / "true"; extra: more table tags to add (each an 8-byte stub). */
  static byte[] sfnt(String version, byte[] name, String outline, String... extra) throws IOException {
    List<String> tags = new ArrayList<>();
    if (name != null) tags.add("name");
    if (outline != null) tags.add(outline);
    for (String e : extra) tags.add(e);
    int n = tags.size();
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    if (version.equals("ttf")) u32(o, 0x00010000L); else if (version.equals("otf")) tag(o, "OTTO"); else tag(o, "true");
    u16(o, n); u16(o, 0); u16(o, 0); u16(o, 0);
    int off = 12 + n * 16;
    List<byte[]> bodies = new ArrayList<>();
    for (String t : tags) {
      byte[] body = t.equals("name") ? name : new byte[8];
      tag(o, t); u32(o, 0); u32(o, off); u32(o, body.length);
      bodies.add(body);
      off += (body.length + 3) & ~3;
    }
    for (byte[] b : bodies) { o.write(b); for (int i = b.length; (i & 3) != 0; i++) o.write(0); }
    return o.toByteArray();
  }

  static Object[] win(int id, String s) { return new Object[]{3, 1, 0x0409, id, s}; }
  static byte[] simple(String family, String style) throws IOException { return sfnt("ttf", nameTable(new Object[][]{win(1, family), win(2, style)}), "glyf"); }

  static File put(File dir, String rel, byte[] data) throws IOException {
    File f = new File(dir, rel);
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), data);
    return f;
  }

  static FontScan.Names names(byte[] font) throws IOException {
    File f = File.createTempFile("fscan", ".ttf");
    try { Files.write(f.toPath(), font); return FontScan.readNames(f); } finally { f.delete(); }
  }

  public static void main(String[] a) throws Exception {
    // ---------- kindOf ----------
    check("kindOf .ttf", "ttf".equals(FontScan.kindOf("Roboto.ttf")));
    check("kindOf .OTF upper case", "otf".equals(FontScan.kindOf("Lato.OTF")));
    check("kindOf only an extension", FontScan.kindOf(".ttf") == null);
    check("kindOf mac resource stub and a trashed file", FontScan.kindOf("._Roboto.ttf") == null && FontScan.kindOf(".trashed-1700000000-Old.ttf") == null);
    check("kindOf woff, ttc, null", FontScan.kindOf("a.woff") == null && FontScan.kindOf("a.ttc") == null && FontScan.kindOf(null) == null);

    // ---------- the name table ----------
    FontScan.Names n = names(simple("Roboto", "Bold"));
    check("windows names: " + n.family + "/" + n.style + "/" + n.full, n != null && n.family.equals("Roboto") && n.style.equals("Bold") && n.full.equals("Roboto Bold") && !n.variable);

    n = names(sfnt("ttf", nameTable(new Object[][]{win(1, "Roboto Medium"), win(2, "Regular"), win(16, "Roboto"), win(17, "Medium"), win(4, "Roboto Medium")}), "glyf"));
    check("typographic family and style win: " + n.family + "/" + n.style, n.family.equals("Roboto") && n.style.equals("Medium"));

    n = names(sfnt("ttf", nameTable(new Object[][]{{3, 1, 0x040C, 1, "Police"}, {3, 1, 0x040C, 2, "Gras"}}), "glyf"));
    check("a Windows name in another language is used when there is no English one: " + n.family, n.family.equals("Police") && n.style.equals("Gras"));

    n = names(sfnt("ttf", nameTable(new Object[][]{{1, 0, 0, 1, "MacName"}, win(1, "WinName")}), "glyf"));
    check("Windows English beats Mac: " + n.family, n.family.equals("WinName"));

    n = names(sfnt("ttf", nameTable(new Object[][]{{1, 0, 0, 1, "Courier"}, {1, 0, 0, 2, "Regular"}}), "glyf"));
    check("a Mac-only font: " + n.family, n.family.equals("Courier") && n.style.equals("Regular"));

    n = names(sfnt("ttf", nameTable(new Object[][]{win(1, "Noto Sans 日本語"), win(2, "Regular")}), "glyf"));
    check("a family with CJK letters: " + n.family, n.family.equals("Noto Sans 日本語"));

    n = names(sfnt("ttf", nameTable(new Object[][]{{0, 3, 0, 1, "UnicodePlatform"}}), "glyf"));
    check("a Unicode-platform name: " + n.family, n.family.equals("UnicodePlatform"));

    n = names(sfnt("otf", nameTable(new Object[][]{win(1, "Lato"), win(2, "Italic")}), "CFF "));
    check("an OpenType font with CFF outlines is a font", n != null && n.family.equals("Lato") && n.style.equals("Italic"));

    n = names(sfnt("true", nameTable(new Object[][]{win(1, "Old Mac Font")}), "glyf"));
    check("a 'true' sfnt is a font", n != null && n.family.equals("Old Mac Font"));

    n = names(sfnt("ttf", nameTable(new Object[][]{win(1, "Inter"), win(2, "Regular")}), "glyf", "fvar"));
    check("a variable font says so", n != null && n.variable);

    n = names(sfnt("ttf", nameTable(new Object[][]{win(1, "NoOutlines")}), null));
    check("no outline table: not a font", n == null);

    n = names(sfnt("ttf", null, "glyf"));
    check("no name table: not a font", n == null);

    byte[] junk = new byte[4096]; new java.util.Random(7).nextBytes(junk);
    check("random bytes: not a font", names(junk) == null);
    check("an empty file and a tiny one: not a font", names(new byte[0]) == null && names(new byte[]{0, 1, 0, 0}) == null);

    byte[] ok = simple("Trunc", "Regular");
    byte[] cut = new byte[ok.length - 30];
    System.arraycopy(ok, 0, cut, 0, cut.length);
    check("a file cut off inside its name table: not a font", names(cut) == null);

    byte[] many = ok.clone(); many[4] = (byte) 0xFF; many[5] = (byte) 0xFF;
    check("65535 tables: not a font", names(many) == null);

    byte[] zeroTables = ok.clone(); zeroTables[4] = 0; zeroTables[5] = 0;
    check("zero tables: not a font", names(zeroTables) == null);

    // a name record that points outside the table is ignored, the rest is read
    byte[] nt = nameTable(new Object[][]{win(1, "Good"), win(2, "Regular")});
    nt[6 + 10] = (byte) 0x7F; nt[6 + 11] = (byte) 0xFF;                       // first record: offset 0x7FFF
    n = names(sfnt("ttf", nt, "glyf"));
    check("a record outside the table is skipped: '" + n.family + "'/" + n.style, n != null && n.family.isEmpty() && n.style.equals("Regular"));

    // a collection answers for its first font
    {
      byte[] f1 = simple("InCollection", "Regular");
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      tag(o, "ttcf"); u32(o, 0x00010000L); u32(o, 1); u32(o, 16);
      byte[] shifted = f1.clone();                                             // the table offsets inside a collection are from the start of the file
      int nt2 = ((shifted[4] & 0xFF) << 8) | (shifted[5] & 0xFF);
      for (int i = 0; i < nt2; i++) {
        int p = 12 + i * 16 + 8;
        long off = ((shifted[p] & 0xFFL) << 24) | ((shifted[p + 1] & 0xFFL) << 16) | ((shifted[p + 2] & 0xFFL) << 8) | (shifted[p + 3] & 0xFFL);
        off += 16;
        shifted[p] = (byte) (off >> 24); shifted[p + 1] = (byte) (off >> 16); shifted[p + 2] = (byte) (off >> 8); shifted[p + 3] = (byte) off;
      }
      o.write(shifted);
      n = names(o.toByteArray());
      check("a collection: first font: " + (n == null ? null : n.family), n != null && n.family.equals("InCollection"));
    }

    // ---------- cssName ----------
    check("cssName strips what could break out of a rule", FontScan.cssName("a\"; } body {x").equals("a body x"));
    check("cssName keeps letters of any script", FontScan.cssName("Noto Sans 日本語-Bold_2").equals("Noto Sans 日本語-Bold_2"));
    check("cssName of nothing", FontScan.cssName("").equals("Custom font") && FontScan.cssName(null).equals("Custom font") && FontScan.cssName("{}();").equals("Custom font"));
    StringBuilder longName = new StringBuilder(); for (int i = 0; i < 100; i++) longName.append('x');
    check("cssName is cut at 60", FontScan.cssName(longName.toString()).length() == 60);

    // ---------- the walk ----------
    File root = Files.createTempDirectory("fontscan").toFile();
    put(root, "Fonts/Roboto-Regular.ttf", simple("Roboto", "Regular"));
    put(root, "Fonts/Roboto-Bold.ttf", simple("Roboto", "Bold"));
    put(root, "Fonts/lato.OTF", sfnt("otf", nameTable(new Object[][]{win(1, "lato"), win(2, "Regular")}), "CFF "));
    put(root, "Fonts/Inter.ttf", sfnt("ttf", nameTable(new Object[][]{win(1, "Inter")}), "glyf", "fvar"));
    put(root, "Fonts/broken.ttf", junk);                                       // called a font, is not one
    put(root, "Fonts/empty.ttf", new byte[0]);
    put(root, "Fonts/readme.txt", "x".getBytes());
    put(root, "Fonts/._Roboto-Regular.ttf", simple("Ghost", "Regular"));
    put(root, "Download/a/b/c/Deep.ttf", simple("Deep", "Regular"));
    put(root, ".trashed-1700000000-Old.ttf", simple("Trashed", "Regular"));
    put(root, ".trashed-folder/x.ttf", simple("TrashedInFolder", "Regular"));
    put(root, "Top.ttf", simple("Top", "Regular"));
    put(root, "Fonts/nameless.ttf", sfnt("ttf", nameTable(new Object[][]{win(2, "Regular")}), "glyf"));
    File big = new File(root, "Fonts/Huge.ttf");
    try (RandomAccessFile r = new RandomAccessFile(big, "rw")) { r.write(simple("Huge", "Regular")); r.setLength(FontScan.MAX_FONT_BYTES + 1); }
    put(root, "empty-dir/.keep", new byte[0]);
    boolean linked = false;
    try { Files.createSymbolicLink(new File(root, "Loop").toPath(), root.toPath()); linked = true; } catch (Exception e) { /* no symlinks here */ }

    FontScan.Limits lim = new FontScan.Limits();
    lim.deadlineMs = System.currentTimeMillis() + 20000;
    final List<String> steps = new ArrayList<>();
    List<File> roots = new ArrayList<>();
    roots.add(root);
    int top = FontScan.countTopFolders(roots);
    List<FontScan.Entry> found = FontScan.scan(roots, lim, new FontScan.Progress() {
      public void onProgress(int done, int total, String folder, int foundNow) { steps.add(done + "/" + total + ":" + folder); }
    });
    List<String> names = new ArrayList<>();
    for (FontScan.Entry e : found) names.add(e.family + (e.style.isEmpty() ? "" : " " + e.style));
    System.out.println("found: " + names);
    check("the fonts are found, the rest is not: " + names.size(), found.size() == 7);
    check("sorted by family, Regular first: " + names, names.toString().equals("[Deep Regular, Inter, lato Regular, nameless Regular, Roboto Regular, Roboto Bold, Top Regular]"));
    check("a font with no family is named after its file", contains(found, "nameless"));
    check("the variable font is marked", variable(found, "Inter") && !variable(found, "Roboto"));
    check("the font of a collapsed stub, a trash folder, a trashed file and the broken ones are left out", !contains(found, "Ghost") && !contains(found, "Trashed") && !contains(found, "TrashedInFolder") && !contains(found, "broken") && !contains(found, "empty"));
    check("a file over the size limit is left out", !contains(found, "Huge"));
    check("a font three folders down is found", contains(found, "Deep"));
    check("a font lying directly in the root is found", contains(found, "Top"));
    check("the symlinked folder is not followed (no loop)", !linked || count(found, "Roboto") == 2);
    check("top-level folders counted without the trash folder: " + top, top == (linked ? 4 : 3));
    check("progress counts folders and ends at the total: " + steps, !steps.isEmpty() && steps.get(steps.size() - 1).startsWith(top + "/" + top + ":"));
    check("progress goes up one folder at a time", increasing(steps));
    check("not stopped by a limit", !lim.hitLimit);

    JSONObject j = FontScan.toJson(found.get(0));
    check("toJson has what the page needs", j.has("path") && j.has("name") && j.has("kind") && j.has("family") && j.has("style") && j.has("variable") && j.has("size") && j.has("mtime"));

    // limits
    FontScan.Limits l2 = new FontScan.Limits(); l2.deadlineMs = System.currentTimeMillis() + 20000; l2.maxResults = 2;
    List<FontScan.Entry> few = FontScan.scan(roots, l2, null);
    check("maxResults stops the walk and says so", l2.hitLimit && few.size() <= 3);

    FontScan.Limits l3 = new FontScan.Limits(); l3.deadlineMs = System.currentTimeMillis() - 1;
    List<FontScan.Entry> none = FontScan.scan(roots, l3, null);
    check("a deadline that has passed stops the walk before it starts", l3.hitLimit && none.isEmpty());

    FontScan.Limits l4 = new FontScan.Limits(); l4.deadlineMs = System.currentTimeMillis() + 20000; l4.maxDepth = 2;
    List<FontScan.Entry> shallow = FontScan.scan(roots, l4, null);
    check("maxDepth leaves out the deep font", !contains(shallow, "Deep") && contains(shallow, "Roboto"));

    FontScan.Limits l5 = new FontScan.Limits(); l5.deadlineMs = System.currentTimeMillis() + 20000; l5.maxVisited = 3;
    FontScan.scan(roots, l5, null);
    check("maxVisited stops the walk", l5.hitLimit);

    FontScan.Limits l6 = new FontScan.Limits(); l6.deadlineMs = System.currentTimeMillis() + 5000;
    check("a folder that does not exist adds nothing", FontScan.scan(java.util.Collections.singletonList(new File(root, "nope")), l6, null).isEmpty() && !l6.hitLimit);

    // ---------- copyChecked: what the font setting stores ----------
    File stash = Files.createTempDirectory("fcopy").toFile();
    File dest = new File(stash, "current.bin");
    FontScan.Names cn = FontScan.copyChecked(new java.io.ByteArrayInputStream(simple("Stored", "Regular")), dest);
    check("copyChecked stores a font and says what it is: " + cn.family, cn.family.equals("Stored") && dest.isFile() && !new File(stash, "current.bin.part").exists());
    byte[] before = Files.readAllBytes(dest.toPath());
    String msg = "";
    try { FontScan.copyChecked(new java.io.ByteArrayInputStream("not a font at all, just text".getBytes("UTF-8")), dest); } catch (IOException e) { msg = e.getMessage(); }
    check("a file that is not a font is refused with a reason, and the stored font stays: " + msg, msg.contains("not a TrueType or OpenType") && java.util.Arrays.equals(before, Files.readAllBytes(dest.toPath())) && !new File(stash, "current.bin.part").exists());
    msg = "";
    try { FontScan.copyChecked(new java.io.ByteArrayInputStream(new byte[0]), dest); } catch (IOException e) { msg = e.getMessage(); }
    check("an empty file is refused: " + msg, msg.contains("not a TrueType or OpenType"));
    byte[] ttc = new byte[64]; ttc[0] = 't'; ttc[1] = 't'; ttc[2] = 'c'; ttc[3] = 'f'; ttc[11] = 1; ttc[15] = 16;
    msg = "";
    try { FontScan.copyChecked(new java.io.ByteArrayInputStream(ttc), dest); } catch (IOException e) { msg = e.getMessage(); }
    check("a collection is refused for what it is: " + msg, msg.contains("collection"));
    msg = "";
    try {
      FontScan.copyChecked(new java.io.InputStream() {
        long left = FontScan.MAX_FONT_BYTES + 10;
        @Override public int read() { return left-- > 0 ? 0 : -1; }
        @Override public int read(byte[] b, int off, int len) { if (left <= 0) return -1; int n = (int) Math.min(len, left); left -= n; return n; }
      }, dest);
    } catch (IOException e) { msg = e.getMessage(); }
    check("a stream over the limit is cut off and refused: " + msg, msg.contains("too big") && !new File(stash, "current.bin.part").exists() && java.util.Arrays.equals(before, Files.readAllBytes(dest.toPath())));
    FontScan.copyChecked(new java.io.ByteArrayInputStream(simple("Second", "Bold")), dest);
    check("a second good font replaces the first", FontScan.readNames(dest).family.equals("Second"));
    // a read that fails half way leaves no .part file and keeps the stored font
    msg = "";
    try {
      FontScan.copyChecked(new java.io.InputStream() {
        int calls;
        @Override public int read() throws IOException { throw new IOException("grant revoked"); }
        @Override public int read(byte[] b, int off, int len) throws IOException { if (calls++ == 0) { b[off] = 1; return 1; } throw new IOException("grant revoked"); }
      }, dest);
    } catch (IOException e) { msg = e.getMessage(); }
    check("a read error leaves no .part file behind: " + msg, msg.contains("grant revoked") && !new File(stash, "current.bin.part").exists() && FontScan.readNames(dest).family.equals("Second"));
    check("the font tag decides the kind: a TrueType font is ttf", FontScan.readNames(dest).kind.equals("ttf"));
    check("the limit is 12 MB", FontScan.MAX_FONT_BYTES == 12L * 1024 * 1024);
    deleteAll(stash);

    deleteAll(root);
    System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  static boolean contains(List<FontScan.Entry> l, String family) { for (FontScan.Entry e : l) if (e.family.equals(family) || e.name.equals(family + ".ttf")) return true; return false; }
  static int count(List<FontScan.Entry> l, String family) { int c = 0; for (FontScan.Entry e : l) if (e.family.equals(family)) c++; return c; }
  static boolean variable(List<FontScan.Entry> l, String family) { for (FontScan.Entry e : l) if (e.family.equals(family)) return e.variable; return false; }
  static boolean increasing(List<String> steps) {
    int last = 0;
    for (String s : steps) { int d = Integer.parseInt(s.substring(0, s.indexOf('/'))); if (d != last + 1) return false; last = d; }
    return true;
  }
  static void deleteAll(File f) {
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) { if (java.nio.file.Files.isSymbolicLink(k.toPath())) k.delete(); else deleteAll(k); }
    f.delete();
  }
}
