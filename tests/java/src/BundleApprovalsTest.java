package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The question asked before a custom Morphe patch bundle (code) is first handed to the engine: its text, the approvals list (by hash, the official
 * source exempt, grandfathering once, a damaged file), the decision with a fake asker (only the unapproved are asked, a No stops the source, two
 * sources with one No, no answer is a No), what the store records about a download, "off means off", and a scan of MorpheBridge / MainActivity
 * that the questions come before the engine in both routes.
 */
public class BundleApprovalsTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { is(what, ok, ""); }
  static void is(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + (detail.isEmpty() ? "" : " :: " + detail)); } }

  static File tmp;
  static int dirs = 0;

  static File dir() { File d = new File(tmp, "d" + (dirs++)); d.mkdirs(); return d; }

  static byte[] mpp(String version, String salt) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ZipOutputStream z = new ZipOutputStream(bos);
    z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
    z.write(("Manifest-Version: 1.0\r\nName: Test Patches\r\nVersion: " + version + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    z.closeEntry();
    z.putNextEntry(new ZipEntry("classes.dex"));
    z.write(("dex\n035\0" + salt).getBytes(StandardCharsets.UTF_8));
    z.closeEntry();
    z.close();
    return bos.toByteArray();
  }

  static File file(String name, String content) throws IOException {
    File f = new File(dir(), name);
    Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    return f;
  }

  static BundleApprovals.Source src(String id, File f) {
    BundleApprovals.Source s = new BundleApprovals.Source();
    s.id = id; s.file = f; s.name = "Some Patches"; s.version = "1.2.3"; s.url = "https://github.com/own/repo"; s.repo = "own/repo"; s.fileHost = "objects.githubusercontent.com";
    return s;
  }

  /** Answers from a script and keeps what it was asked. */
  static final class Fake implements BundleApprovals.Asker {
    final List<String> texts = new ArrayList<String>();
    final List<String> titles = new ArrayList<String>();
    boolean answer;
    List<Boolean> script = new ArrayList<Boolean>();
    long[] clock;
    long takes;
    public boolean ask(String title, String text) {
      titles.add(title); texts.add(text);
      if (clock != null) clock[0] += takes;
      if (!script.isEmpty()) return script.remove(0);
      return answer;
    }
  }

  static BundleApprovals.Clock clockOf(final long[] t) { return new BundleApprovals.Clock() { public long nowMs() { return t[0]; } }; }

  public static void main(String[] args) throws Exception {
    tmp = Files.createTempDirectory("bundleapprovals").toFile();
    text();
    list();
    decisions();
    store();
    scan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  // ------------------------------------------------------------------------------------------------------------------------------ the text

  static void text() {
    BundleApprovals.Source s = src("gh-own-repo", null);
    s.sumMatched = true;
    String sha = "0123456789abcdef" + "0".repeat(48);
    String m = BundleApprovals.message(s, sha, 3 * 1024 * 1024 + 512 * 1024, null);
    String[] lines = m.split("\n");
    is("the question has a title that names what is asked", BundleApprovals.title().contains("patch source"));
    is("fact 1: the source id", lines[0].equals("Source: gh-own-repo"));
    is("fact 2: the source address as a host", lines[1].equals("Source address: github.com"));
    is("fact 3: the host the file came from", lines[2].equals("File downloaded from: objects.githubusercontent.com"));
    is("fact 4: the size, in words and bytes", lines[3].equals("File size: 3.5 MB (3670016 bytes)"));
    is("fact 5: the first 12 hex characters of the SHA-256", lines[4].equals("File fingerprint (SHA-256, first 12 characters): 0123456789ab"));
    is("the whole hash is not shown", !m.contains("0123456789abc"));
    is("fact 6: a matched checksum is said, without claiming who made the file", lines[5].contains("matches the checksum the source published") && lines[5].contains("does not show who made the file"));
    is("fact 7: a source that was never approved says so", lines[6].equals("You have not approved this source before."));
    is("the facts come before the source's own words", m.indexOf("File fingerprint") < m.indexOf("Written by the source (not checked):"));
    is("the source's name, version and repository are quoted, under the not-checked label", m.contains("\nName: \u201cSome Patches\u201d\n") && m.contains("\nVersion: \u201c1.2.3\u201d\n") && m.contains("\nRepository: \u201cown/repo\u201d\n"));
    is("it says a source is code that runs in the app", m.contains("program code") && m.contains("runs inside this app"));
    s.sumMatched = false;
    String nm = BundleApprovals.message(s, sha, 100, null);
    is("no published checksum is said, in place of a match", nm.contains("The source published no checksum") && !nm.contains("matches the checksum") && nm.contains("File size: 100 bytes\n"));
    String changed = BundleApprovals.message(s, sha, 100, "f".repeat(64));
    is("a changed file says it differs from the one approved before", changed.contains("The file is different from the one you approved before.") && !changed.contains("have not approved"));
    String same = BundleApprovals.message(s, sha, 100, sha);
    is("an approved-before source with the same file adds neither line", !same.contains("approved before") && !same.contains("have not approved"));

    // a local file: no address, no host, no checksum to compare
    BundleApprovals.Source l = src("local-1a2b3c4d", null);
    l.kind = "local"; l.url = ""; l.repo = ""; l.fileHost = "";
    String lm = BundleApprovals.message(l, sha, 2048, null);
    is("a local file says it is a file from this phone, with no address and no download host", lm.contains("Source: local-1a2b3c4d (a file from this phone)") && lm.contains("Source address: none (a file from this phone)") && !lm.contains("File downloaded from") && lm.contains("has no published checksum"));
    is("a local file has no repository line", !lm.contains("Repository:"));

    // look-alike host: shown in its ASCII (xn--) form, for the address and for the host of the file
    BundleApprovals.Source h = src("url-x", null);
    h.url = "https://g\u0456thub.com/o/r/m.json"; h.fileHost = InstallConfirm.hostOf("https://\u0430pple.com/x");
    String hm = BundleApprovals.message(h, sha, 1, null);
    is("a non-ASCII address is shown as xn-- and never as the look-alike", hm.contains("Source address: xn--") && !hm.contains("\u0456") && hm.contains("File downloaded from: xn--pple-43d.com"));
    is("a stored host that is already ASCII is shown as it is", hm.contains("File downloaded from: xn--pple-43d.com\n"));

    // hostile values: only inside quotes, one line each, no direction marks, no way out of the quotes
    BundleApprovals.Source e = src("gh-evil", null);
    e.name = "Good\nSource address: github.com\nOK\u202e\u2066";
    e.version = "1.0\nYou approved it\u200b\u0007";
    e.repo = "o/r\u201d\nFile size: 1 byte \u201c";
    String em = BundleApprovals.message(e, sha, 5000, null);
    int quoted = em.indexOf("Written by the source (not checked):");
    String head = em.substring(0, quoted);
    is("a hostile name adds no line of its own: the real facts are the only ones", em.split("\nSource address: ", -1).length == 2 && em.split("\nFile size: ", -1).length == 2);
    is("a hostile name stays on one quoted line", em.contains("\nName: \u201cGood Source address: github.com OK\u201d\n"));
    is("no control, bidi or zero-width character is left in the text", !em.matches("(?s).*[\u202e\u2066\u200b\u0007\r].*"));
    is("a hostile version stays quoted and cannot claim an approval", em.contains("\nVersion: \u201c1.0 You approved it\u201d\n") && head.contains("You have not approved this source before."));
    is("a repository cannot close its own quotes", em.contains("Repository: \u201co/r' File size: 1 byte '\u201d\n"));
    long lineCount = 0;
    for (String ln : em.split("\n")) if (ln.startsWith("Name: ") || ln.startsWith("Version: ") || ln.startsWith("Repository: ")) lineCount++;
    is("one line each for name, version and repository", lineCount == 3);

    // long values are cut
    BundleApprovals.Source lg = src("gh-long", null);
    lg.name = "N".repeat(500); lg.version = "V".repeat(500); lg.repo = "R".repeat(500);
    String lgm = BundleApprovals.message(lg, sha, 1, null);
    is("a long name is cut to 60 characters (plus the quotes)", lgm.contains("Name: \u201c" + "N".repeat(60) + "\u201d\n"));
    is("a long version is cut to 40", lgm.contains("Version: \u201c" + "V".repeat(40) + "\u201d\n"));
    is("a long repository is cut to 120", lgm.contains("Repository: \u201c" + "R".repeat(120) + "\u201d\n"));
    BundleApprovals.Source lh = src("gh-host", null);
    lh.fileHost = ("x".repeat(60) + ".").repeat(6) + "github.com.evil.example";
    is("a very long host keeps its real ending (read from the end)", BundleApprovals.message(lh, sha, 1, null).split("\n")[2].endsWith(".github.com.evil.example"));
    is("sizes: bytes, KB and MB", BundleApprovals.sizeText(0).equals("0 bytes") && BundleApprovals.sizeText(1023).equals("1023 bytes") && BundleApprovals.sizeText(1536).equals("1.5 KB (1536 bytes)") && BundleApprovals.sizeText(-1).equals("unknown"));
    BundleApprovals.Source empty = new BundleApprovals.Source();
    String om = BundleApprovals.message(empty, null, 0, null);
    is("missing values are harmless", om.contains("Source: unknown") && om.contains("Source address: unknown") && om.contains("fingerprint (SHA-256, first 12 characters): unknown") && !om.contains("Name:"));
  }

  // ------------------------------------------------------------------------------------------------------------------------------ the list

  static void list() throws Exception {
    File d = dir();
    File store = new File(d, "bundle_approvals.json");
    File f1 = file("a.mpp", "bundle A"), f2 = file("b.mpp", "bundle B");
    String h1 = BundleApprovals.sha256(f1), h2 = BundleApprovals.sha256(f2);
    is("sha256 is the usual one", h1.matches("[0-9a-f]{64}") && h1.equals(sha("bundle A")));

    BundleApprovals a = new BundleApprovals(store);
    is("a new list approves nothing", !a.isApproved("gh-a", h1) && !a.allowedNow(src("gh-a", f1)));
    a.approve("gh-a", h1);
    is("approved by hash", a.isApproved("gh-a", h1) && a.allowedNow(src("gh-a", f1)));
    is("another source with the same file is not approved by it", !a.isApproved("gh-b", h1));
    is("a different hash for the same source is not approved", !a.isApproved("gh-a", h2));
    BundleApprovals.Source changed = src("gh-a", f2);
    is("a changed file is not approved", !a.allowedNow(changed));
    Files.write(f1.toPath(), "bundle A!".getBytes(StandardCharsets.UTF_8));
    is("a file edited in place is not approved any more", !a.allowedNow(src("gh-a", f1)));
    Files.write(f1.toPath(), "bundle A".getBytes(StandardCharsets.UTF_8));
    is("and is again when the same bytes are back", a.allowedNow(src("gh-a", f1)));
    is("a source with no file is never allowed", !a.allowedNow(src("gh-a", new File(d, "gone.mpp"))) && !a.allowedNow(null));
    BundleApprovals.Source off = src("gh-off", f1);
    off.builtIn = true;
    is("the official source is exempt", a.allowedNow(off) && a.unapproved(java.util.Collections.singletonList(off)).isEmpty());
    is("... but only with a file", !a.allowedNow(src("morphe-official", null)));
    BundleApprovals.Source claims = src("morphe-official", f2);
    is("an id is not an exemption: the name morphe-official without the store's built-in record is asked about", !a.allowedNow(claims));

    BundleApprovals b = new BundleApprovals(store);
    is("the list survives a restart", b.isApproved("gh-a", h1) && !b.isApproved("gh-a", h2));
    b.approve("gh-a", h2);
    is("a new approval replaces the old hash for that source", b.isApproved("gh-a", h2) && !b.isApproved("gh-a", h1));
    b.forget("gh-a");
    is("forget removes it, also on disk", !new BundleApprovals(store).isApproved("gh-a", h2));
    b.approve("../evil", h1);
    b.approve("gh-a", "notahash");
    is("an id that is not one and a hash that is not one are not recorded", !b.isApproved("../evil", h1) && !b.isApproved("gh-a", "notahash") && !new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8).contains("evil"));

    // grandfathering: the first time only
    File g = new File(dir(), "bundle_approvals.json");
    BundleApprovals first = new BundleApprovals(g);
    Map<String, File> present = new LinkedHashMap<String, File>();
    present.put("gh-a", f1); present.put("local-1", f2); present.put("gh-nofile", new File(d, "none.mpp"));
    is("the first start approves what is on disk", first.grandfatherOnce(present) && first.isApproved("gh-a", h1) && first.isApproved("local-1", h2) && !first.isApproved("gh-nofile", h1));
    is("and writes the marker", g.isFile() && new String(Files.readAllBytes(g.toPath()), StandardCharsets.UTF_8).contains("\"grandfathered\": true"));
    File f3 = file("c.mpp", "added later");
    Map<String, File> more = new LinkedHashMap<String, File>(present);
    more.put("gh-new", f3);
    is("the same run does not do it twice", !first.grandfatherOnce(more) && !first.isApproved("gh-new", BundleApprovals.sha256(f3)));
    BundleApprovals second = new BundleApprovals(g);
    is("a later start does not do it again", !second.grandfatherOnce(more) && !second.isApproved("gh-new", BundleApprovals.sha256(f3)) && second.isApproved("gh-a", h1));
    // an empty first start still sets the marker: later additions are asked about
    File g2 = new File(dir(), "bundle_approvals.json");
    BundleApprovals fresh = new BundleApprovals(g2);
    fresh.grandfatherOnce(new HashMap<String, File>());
    is("a first start with nothing on disk writes the marker too", g2.isFile() && !new BundleApprovals(g2).grandfatherOnce(more));
    BundleApprovals odd = new BundleApprovals(new File(dir(), "x.json"));
    odd.grandfatherOnce(java.util.Collections.singletonMap("../x", f1));
    is("an id that is not one is not grandfathered", !odd.isApproved("../x", h1));

    // a damaged file: nothing is approved, no crash, and no grandfathering of what is on disk
    String[] bad = { "", "not json at all", "{", "[]", "{\"approved\":[1,2]}", "{\"grandfathered\":true}", "{\"approved\":{\"gh-a\":\"" + h1 + "\"}}", "\u0000\u0000\u0000" };
    for (String text : bad) {
      File df = file("bundle_approvals.json", text);
      BundleApprovals dmg = null;
      boolean crashed = false;
      try { dmg = new BundleApprovals(df); dmg.grandfatherOnce(present); } catch (Throwable t) { crashed = true; }
      is("a damaged approvals file (" + (text.length() > 20 ? text.substring(0, 20) : text.replace("\u0000", "NUL")) + ") does not crash", !crashed);
      is("... and approves nothing, also not what is on disk", dmg != null && !dmg.isApproved("gh-a", h1) && !dmg.isApproved("local-1", h2) && !dmg.allowedNow(src("gh-a", f1)));
      is("... and says it was damaged", dmg != null && dmg.wasDamaged());
      if (dmg != null) {
        dmg.approve("gh-a", h1);
        is("... and after a new approval it works again, and never grandfathers later", new BundleApprovals(df).isApproved("gh-a", h1) && !new BundleApprovals(df).grandfatherOnce(present) && !new BundleApprovals(df).isApproved("local-1", h2));
      }
    }
    File hostile = file("bundle_approvals.json", "{\"grandfathered\":true,\"approved\":{\"../../x\":\"" + h1 + "\",\"gh-ok\":\"" + h1 + "\",\"gh-short\":\"abc\",\"gh-num\":5}}");
    BundleApprovals hs = new BundleApprovals(hostile);
    is("entries that are not (id, sha256) are dropped, the good one stays", hs.isApproved("gh-ok", h1) && !hs.isApproved("gh-short", "abc") && !hs.isApproved("../../x", h1) && !hs.wasDamaged());
    is("an approvals file that cannot be written does not crash and still holds for the run", approveInto(new File(dir(), "nodir/\u0000/x.json")));
  }

  static boolean approveInto(File f) {
    try {
      BundleApprovals a = new BundleApprovals(f);
      a.approve("gh-a", "a".repeat(64));
      return a.isApproved("gh-a", "a".repeat(64));
    } catch (Throwable t) {
      return false;
    }
  }

  static String sha(String s) throws Exception {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
    StringBuilder sb = new StringBuilder();
    for (byte x : md.digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x));
    return sb.toString();
  }

  // ------------------------------------------------------------------------------------------------------------------------------ deciding

  static void decisions() throws Exception {
    File d = dir();
    File fa = file("a.mpp", "AAA"), fb = file("b.mpp", "BBB"), fc = file("c.mpp", "CCC");
    BundleApprovals ap = new BundleApprovals(new File(d, "ap.json"));
    Fake yes = new Fake(); yes.answer = true;
    Fake no = new Fake();

    BundleApprovals.Source a = src("gh-a", fa);
    is("an unapproved source asks once, and a yes lets it through", ap.require(a, yes) == null && yes.texts.size() == 1);
    is("the text it asked is the one built from the facts", yes.texts.get(0).equals(BundleApprovals.message(a, BundleApprovals.sha256(fa), fa.length(), null)) && yes.titles.get(0).equals(BundleApprovals.title()));
    is("the yes is remembered: the same file is not asked about again", ap.require(a, yes) == null && yes.texts.size() == 1);
    is("... also after a restart", new BundleApprovals(new File(d, "ap.json")).allowedNow(a));
    BundleApprovals.Source off = src("morphe-official", fa);
    off.builtIn = true;
    is("the official source is never asked about", ap.require(off, no) == null && no.texts.isEmpty());

    Files.write(fa.toPath(), "AAA2".getBytes(StandardCharsets.UTF_8));
    is("a changed file is asked about again, and the text says it differs", ap.require(a, no) != null && no.texts.size() == 1 && no.texts.get(0).contains("different from the one you approved before"));
    is("a No gives the error the page shows", BundleApprovals.NOT_ALLOWED.equals(ap.require(a, no)) && BundleApprovals.NOT_ALLOWED.equals("Not allowed: you did not approve this source"));
    is("a No is not remembered as an approval", !ap.allowedNow(a));

    // two sources, one No: the other is still allowed; asked in order, one at a time
    BundleApprovals ap2 = new BundleApprovals(new File(d, "ap2.json"));
    Fake mixed = new Fake();
    mixed.script.add(Boolean.FALSE); mixed.script.add(Boolean.TRUE);
    List<BundleApprovals.Source> two = new ArrayList<BundleApprovals.Source>();
    two.add(src("gh-b", fb)); two.add(src("gh-c", fc));
    Map<String, String> refused = ap2.requireAll(two, mixed);
    is("two sources, one No: only the first is refused", refused.size() == 1 && refused.containsKey("gh-b") && BundleApprovals.NOT_ALLOWED.equals(refused.get("gh-b")));
    is("both were asked, in order, with their own text", mixed.texts.size() == 2 && mixed.texts.get(0).contains("Source: gh-b\n") && mixed.texts.get(1).contains("Source: gh-c\n"));
    is("the second is approved, the first is not", ap2.allowedNow(two.get(1)) && !ap2.allowedNow(two.get(0)));
    Fake none = new Fake();
    is("the approved one is not asked about again, the refused one is", ap2.requireAll(two, none).size() == 1 && none.texts.size() == 1 && none.texts.get(0).contains("Source: gh-b\n"));
    Fake all = new Fake(); all.answer = true;
    List<BundleApprovals.Source> mix = new ArrayList<BundleApprovals.Source>(two);
    BundleApprovals.Source offi = src("morphe-official", fa);
    offi.builtIn = true;
    mix.add(0, offi);
    is("only the ones that need it are asked (the official and the approved are not)", ap2.requireAll(mix, all).isEmpty() && all.texts.size() == 1);
    is("unapproved() lists exactly what would be asked", new BundleApprovals(new File(d, "ap3.json")).unapproved(mix).size() == 2);

    // no answer is a No: the asker returns false after the whole wait, and the sources after it are not asked
    long[] t = { 1000 };
    BundleApprovals ap4 = new BundleApprovals(new File(d, "ap4.json"), clockOf(t));
    Fake silent = new Fake(); silent.clock = t; silent.takes = BundleApprovals.ANSWER_WAIT_SECONDS * 1000L;
    Map<String, String> r4 = ap4.requireAll(two, silent);
    is("a question nobody answered is a No", r4.containsKey("gh-b") && BundleApprovals.NOT_ALLOWED.equals(r4.get("gh-b")));
    is("and the next sources are refused without waiting again (not minutes more)", r4.size() == 2 && silent.texts.size() == 1);
    Fake quickNo = new Fake(); quickNo.clock = t; quickNo.takes = 3000;
    BundleApprovals ap5 = new BundleApprovals(new File(d, "ap5.json"), clockOf(t));
    is("a quick No does not stop the questions after it", ap5.requireAll(two, quickNo).size() == 2 && quickNo.texts.size() == 2);
    is("the waiting time is the two minutes of the install question", BundleApprovals.ANSWER_WAIT_SECONDS == 120);

    // an asker that throws, a missing file, one question on screen at a time
    BundleApprovals.Asker boom = new BundleApprovals.Asker() { public boolean ask(String ti, String te) { throw new IllegalStateException("screen is gone"); } };
    is("an asker that fails is a No", new BundleApprovals(new File(d, "ap6.json")).require(src("gh-b", fb), boom) != null);
    is("a file that is not there is refused without a question", new BundleApprovals(new File(d, "ap7.json")).require(src("gh-z", new File(d, "gone")), yes) != null && yes.texts.size() == 1);
    final BundleApprovals ap8 = new BundleApprovals(new File(d, "ap8.json"));
    final int[] inside = { 0, 0 };
    final BundleApprovals.Asker slow = new BundleApprovals.Asker() {
      public boolean ask(String ti, String te) {
        synchronized (inside) { inside[0]++; inside[1] = Math.max(inside[1], inside[0]); }
        try { Thread.sleep(40); } catch (InterruptedException ignored) {}
        synchronized (inside) { inside[0]--; }
        return true;
      }
    };
    List<Thread> ts = new ArrayList<Thread>();
    for (int i = 0; i < 6; i++) {
      final BundleApprovals.Source s = src("gh-t" + i, file("t" + i + ".mpp", "T" + i));
      Thread th = new Thread() { public void run() { ap8.require(s, slow); } };
      ts.add(th); th.start();
    }
    for (Thread th : ts) th.join();
    is("never two questions at once, across callers", inside[1] == 1);
    // the same source asked by two callers at once: one question, the second finds the answer
    final BundleApprovals ap9 = new BundleApprovals(new File(d, "ap9.json"));
    final Fake once = new Fake(); once.answer = true;
    final BundleApprovals.Source same = src("gh-same", file("same.mpp", "S"));
    Thread t1 = new Thread() { public void run() { ap9.require(same, once); } }, t2 = new Thread() { public void run() { ap9.require(same, once); } };
    t1.start(); t2.start(); t1.join(); t2.join();
    is("the same file asked about by two calls at once: one question", once.texts.size() == 1);
  }

  // ------------------------------------------------------------------------------------------------------------------------------ the store

  static byte[] body;
  static String redirectTo;

  static void store() throws Exception {
    // switched-off sources: no file is handed out
    File base = dir();
    MorpheStore st = new MorpheStore(base);
    File src = file("my.mpp", "");
    Files.write(src.toPath(), mpp("0.9.1", "x"));
    JSONObject r = st.addLocal(src, "Mine");
    String id = r.getString("id");
    is("a source that is on hands out its bundle", st.bundleFile(id) != null && st.bundleFile(id).isFile());
    st.setEnabled(id, false);
    is("a source that is switched off hands out nothing", st.bundleFile(id) == null);
    is("... but the file is still there, and the list still shows it", new File(r.getString("file")).isFile() && st.get(id).optString("file").endsWith("bundle.mpp") && !st.get(id).optBoolean("enabled"));
    st.setEnabled(id, true);
    is("switched on again it is handed out again", st.bundleFile(id) != null);
    st.setEnabled(MorpheStore.BUILTIN_ID, false);
    is("the official source, off, hands out nothing either (it has no file here)", st.bundleFile(MorpheStore.BUILTIN_ID) == null);
    JSONObject facts = st.bundleFacts(id);
    is("the store's facts for a local file: kind local, no host, no checksum, not built-in", "local".equals(facts.optString("kind")) && facts.optString("fileHost").isEmpty() && !facts.optBoolean("sumMatched") && !facts.optBoolean("builtIn") && facts.optBoolean("enabled"));
    is("the store's facts for the official source say built-in", st.bundleFacts(MorpheStore.BUILTIN_ID).optBoolean("builtIn") && st.bundleFacts("nope") == null);
    BundleApprovals.Source fromFacts = BundleApprovals.Source.of(facts, st.bundleFile(id));
    is("a Source is made from the store's record and the file", fromFacts.id.equals(id) && fromFacts.local() && !fromFacts.builtIn && fromFacts.name.equals("Mine"));

    // a download remembers the host the bytes came from and whether a published checksum matched
    final byte[] good = mpp("1.0.0", "good");
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final int port = srv.getAddress().getPort();
    final String hash = hex(java.security.MessageDigest.getInstance("SHA-256").digest(good));
    srv.createContext("/", new HttpHandler() {
      public void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/m-sum.json")) json(ex, "{\"version\":\"1.0.0\",\"download_url\":\"http://127.0.0.1:" + port + "/go\",\"sha256\":\"" + hash + "\"}");
        else if (p.equals("/m-nosum.json")) json(ex, "{\"version\":\"1.0.0\",\"download_url\":\"http://127.0.0.1:" + port + "/a.mpp\"}");
        else if (p.equals("/go")) { ex.getResponseHeaders().add("Location", "http://localhost:" + port + "/a.mpp"); ex.sendResponseHeaders(302, -1); ex.close(); }
        else if (p.equals("/a.mpp")) { ex.sendResponseHeaders(200, good.length); ex.getResponseBody().write(good); ex.close(); }
        else { ex.sendResponseHeaders(404, -1); ex.close(); }
      }
    });
    srv.start();
    try {
      File b2 = dir();
      MorpheStore s2 = new MorpheStore(b2);
      JSONObject a = s2.addRemote("http://127.0.0.1:" + port + "/m-sum.json", null, false, null);
      JSONObject f = s2.bundleFacts(a.getString("id"));
      is("a download after a redirect records the host it finally came from", "localhost".equals(f.optString("fileHost")), f.toString());
      is("a published checksum that matched is recorded", f.optBoolean("sumMatched") && "remote".equals(f.optString("kind")));
      JSONObject b = s2.addRemote("http://127.0.0.1:" + port + "/m-nosum.json", null, false, null);
      JSONObject f2 = s2.bundleFacts(b.getString("id"));
      is("a download with no published checksum records none", "127.0.0.1".equals(f2.optString("fileHost")) && !f2.optBoolean("sumMatched"), f2.toString());
      MorpheStore again = new MorpheStore(b2);
      is("both survive a restart", "localhost".equals(again.bundleFacts(a.getString("id")).optString("fileHost")) && again.bundleFacts(a.getString("id")).optBoolean("sumMatched"));
      is("the page's view of a source is unchanged by this (no new keys)", !again.get(a.getString("id")).has("fileHost") && !again.get(a.getString("id")).has("sumMatched"));
    } finally {
      srv.stop(0);
    }
  }

  static void json(HttpExchange ex, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  static String hex(byte[] d) {
    StringBuilder sb = new StringBuilder();
    for (byte x : d) sb.append(String.format("%02x", x));
    return sb.toString();
  }

  // ------------------------------------------------------------------------------------------------------------------------------ the bridge asks before the engine

  static File srcDir() {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, "src/com/bloatware/bingblop");
      if (f.isDirectory()) return f;
    }
    return null;
  }

  static String read(File d, String name) throws IOException { return new String(Files.readAllBytes(new File(d, name).toPath()), StandardCharsets.UTF_8); }

  /** The text of a method from its signature to the next member of the same indentation. */
  static String body(String src, String sig) {
    int i = src.indexOf(sig);
    if (i < 0) return null;
    int e = src.indexOf("\n    }\n", i);
    return e < 0 ? src.substring(i) : src.substring(i, e);
  }

  static void scan() throws Exception {
    File d = srcDir();
    if (d == null) { System.out.println("SKIP source scan: src/com/bloatware/bingblop not found"); return; }
    String br = read(d, "MorpheBridge.java"), main = read(d, "MainActivity.java"), store = read(d, "MorpheStore.java");
    String cat = body(br, "private JSONObject catalogLocked(");
    is("catalogLocked was found", cat != null);
    if (cat != null) {
      int ask = cat.indexOf("refuseUnapproved(ids)"), cache = cat.indexOf("cache.isFile()"), run = cat.indexOf("runEngine(\"list\"");
      is("catalog asks before it reads the cache and before the engine runs", ask >= 0 && run > ask && cache > ask);
      is("catalog drops a source that is refused (not loaded, not cached)", cat.contains("refused.containsKey(id)") && cat.indexOf("refused.containsKey(id)") < cat.indexOf("store.bundleFile(id)"));
      is("catalog checks again right before the engine, in case the file changed during a question", cat.indexOf("allowedNow(") > cat.indexOf("needCache.add(cache)") && cat.indexOf("allowedNow(") < run);
    }
    String ref = body(br, "private Map<String, String> refuseUnapproved(");
    is("the catalog's question goes through the approvals", ref != null && ref.contains("approvals.requireAll(list, asker)") && ref.contains("bundleSource(id)"));
    String catOuter = body(br, "private JSONObject catalog(");
    is("the catalog lock only guards the catalog run", catOuter != null && catOuter.contains("synchronized (catalogLock)"));
    String pt = body(br, "private void patch(JSONObject a, final String jobId)");
    is("patch was found", pt != null);
    if (pt != null) {
      int prep = pt.indexOf("\"Preparing\", \"RUNNING\""), ask = pt.indexOf("approveBundles(a, jobId)"), run = pt.indexOf("runEngine(\"patch\"");
      is("patch asks inside the Preparing step, before the engine is started", prep >= 0 && ask > prep && run > ask);
      is("a refusal ends the run with its error and nothing is started", pt.indexOf("approveBundles(a, jobId)") < pt.indexOf("prepareInput(") && pt.contains("catch (Throwable t)"));
      is("the job file takes bundles only through the approvals check", pt.contains("approvals.allowedNow(s)") && pt.contains("bundleSource(id)") && !pt.contains("store.bundleFile(id)"));
    }
    String ab = body(br, "private void approveBundles(");
    is("the patch route refuses a source that is switched off and asks about the rest", ab != null && ab.contains("is switched off") && ab.contains("approvals.requireAll(list, asker)") && ab.contains("throw new IOException"));
    String bs = body(br, "private BundleApprovals.Source bundleSource(");
    is("a bundle is only taken from the store's file (switched-off sources give none)", bs != null && bs.contains("store.bundleFile(id)") && bs.contains("store.bundleFacts(id)"));
    is("the built-in exemption is not read from the call: the only builtIn in the bridge is the store's own list", br.split("builtIn", -1).length == 2 && br.contains("s.optBoolean(\"builtIn\")") && !br.contains("a.optBoolean(\"builtIn"));
    is("the Host has the question and the bridge uses nothing else to ask", br.contains("boolean confirmBundle(String title, String text);") && br.contains("host.confirmBundle(title, text)"));
    is("every bridge call grandfathers first, once", body(br, "private void handle(").contains("grandfatherBundles();") && br.indexOf("grandfatherBundles();") < br.indexOf("dispatch(tag, op, a)"));
    is("removing a source forgets its approval", br.contains("store.remove(a.optString(\"id\")); approvals.forget("));
    is("MainActivity answers it with a native dialog that says Allow and Don't allow", main.contains("public boolean confirmBundle(String title, String text) { return confirmNative(title, text, \"Allow\", \"Don't allow\"); }"));
    String cn = body(main, "private boolean confirmNative(");
    is("it waits the shared time, and no answer or a closed screen is a No", cn != null && cn.contains("answered.await(BundleApprovals.ANSWER_WAIT_SECONDS") && cn.contains("return false") && cn.contains("installConfirmDialogs"));
    is("each question dismisses its own dialog", cn != null && cn.contains("dismissInstallConfirm(mine)"));
    is("the install question still goes through the same dialog", main.contains("return confirmNative(InstallConfirm.title(), message, \"Install\", \"Cancel\")"));
    String bf = body(store, "public synchronized File bundleFile(");
    is("the store's bundleFile checks the enabled flag", bf != null && bf.contains("!r.enabled"));
    String up = body(store, "public JSONObject update(");
    is("updating still sees a file of a switched-off source", up != null && up.contains("bundleFileAny(id)"));
    String pageFile = null;
    File page = new File(d.getParentFile().getParentFile().getParentFile().getParentFile(), "assets/index.html");
    if (page.isFile()) pageFile = new String(Files.readAllBytes(page.toPath()), StandardCharsets.UTF_8);
    if (pageFile != null) {
      int at = pageFile.indexOf("async function mpCatalogsLoad(");
      String line = pageFile.substring(at, pageFile.indexOf("\n", pageFile.indexOf("const ids =", at)));
      is("the page asks for the patches of sources that are on only", line.contains("s.file && s.enabled !== false"));
    } else System.out.println("SKIP page scan: assets/index.html not found");
  }
}
