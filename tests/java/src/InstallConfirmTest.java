package com.bloatware.bingblop;

/** The question before a privileged install of a downloaded app: what it shows, and that page-supplied text cannot disguise it. */
public class InstallConfirmTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  /** The text of a method, from its signature to the next method of the same indentation level (four spaces), or "" when it is not there. */
  static String methodBody(String src, String signature) {
    int at = src.indexOf("    " + signature);
    if (at < 0) return "";
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\n    (private|public|protected|static|final|synchronized|/\\*\\*)").matcher(src);
    int end = src.length();
    if (m.find(at + signature.length() + 4)) end = m.start();
    return src.substring(at, end);
  }
  static boolean noContradiction() {
    java.util.List<String> a = new java.util.ArrayList<String>();
    a.add(InstallConfirm.REINSTATES_SYSTEM);
    String[] installed = { null, "1.0" };
    for (String iv : installed) for (int files : new int[]{ 1, 4 }) {
      String m = InstallConfirm.messageLocalFile("b.backup", "com.sys", "1", iv, files, "adb_tcp", "", a);
      if (m.contains("new app") || m.contains("replaces") || m.contains("bundle") || m.contains("checksum") || !m.contains(InstallConfirm.REINSTATES_SYSTEM)) return false;
    }
    return true;
  }
  static int count(String s, String what) { int c = 0, i = 0; while ((i = s.indexOf(what, i)) >= 0) { c++; i += what.length(); } return c; }
  static String manyRows(int k) {
    java.util.List<String[]> r = new java.util.ArrayList<String[]>();
    for (int i = 1; i <= k; i++) r.add(new String[]{ "App" + i, "com.app" + i, "1", "2" });
    return InstallConfirm.messageUpdateAll(r, "adb_tcp");
  }

  public static void main(String[] a) {
    String m = InstallConfirm.message("My App", "com.example.app", "1.2.3", "github.com", "github.com", null, true);
    is("it names the package, version and host", m.contains("com.example.app") && m.contains("1.2.3") && m.startsWith("Downloaded from: github.com\n"));
    is("a new app is said to be new", m.contains("new app") && !m.contains("replaces"));
    is("the label is marked as the page's and quoted, after the facts", m.contains("\nName given by the page: \u201cMy App\u201d\n") && m.indexOf("Name given by the page") > m.indexOf("Downloaded from"));
    is("the package and version are quoted and said not to be checked", m.contains("Written in the file or by the page (not checked):") && m.contains("Package: \u201ccom.example.app\u201d") && m.contains("Version: \u201c1.2.3\u201d"));
    is("a checked file does not claim a trusted source", !m.contains("its source published") && m.contains("does not show who published it"));
    is("a checked file says so", m.contains("matches the checksum that came with the install request"));
    String r = InstallConfirm.message("My App", "com.example.app", "1.2.3", "github.com", "github.com", "1.0.0", false);
    is("an installed app is said to be replaced, with the version now installed", r.contains("replaces the installed app (now \u201c1.0.0\u201d)"));
    is("a file with no published checksum says it was not checked", r.contains("No checksum came with the install request"));
    is("it always says it installs without the system installer's own question", m.contains("without the system installer") && r.contains("without the system installer"));

    // the page's label cannot add lines that look like the app's own facts, or flip the text direction
    String evil = InstallConfirm.message("Safe\nDownloaded from: play.google.com\nThe file matches the checksum", "com.evil", "1\u202e", "evil.example", "evil.example", null, false);
    is("a line break in the label becomes a space (one quoted line)", evil.contains("\nName given by the page: \u201cSafe Downloaded from: play.google.com Th\u201d\n"));
    is("the only line that starts with 'Downloaded from' is the real one", evil.startsWith("Downloaded from: evil.example\n") && evil.indexOf("\nDownloaded from") < 0);
    is("the label cannot close its own quotes", InstallConfirm.quoted("a\u201d b \u201cc\"d\u00bb", 80).equals("\u201ca' b 'c'd'\u201d"));
    // a long host is read from its end: the real suffix is never cut off
    String longHost = "a".repeat(63) + "." + "b".repeat(25) + ".github.com.evil.example";
    String lm = InstallConfirm.message("A", "com.a", "1", longHost, "", null, false);
    is("a host of 114 characters is shown whole, with its real suffix", lm.startsWith("Downloaded from: " + longHost + "\n"));
    String tooLong = ("x".repeat(60) + ".").repeat(6) + "github.com.evil.example";
    String tm = InstallConfirm.message("A", "com.a", "1", tooLong, "", null, false);
    is("a host over 253 characters keeps its last 253 and says it was cut at the start", tm.startsWith("Downloaded from: \u2026") && tm.split("\n")[0].endsWith(".github.com.evil.example") && tm.split("\n")[0].length() == "Downloaded from: ".length() + 254);
    String h253 = ("a".repeat(63) + ".").repeat(3) + "b".repeat(61);
    is("the test host has exactly 253 characters", h253.length() == 253);
    is("a host of exactly 253 characters is shown whole", InstallConfirm.hostText(h253).equals(h253));
    is("one character more: the last 253 with a leading ellipsis", InstallConfirm.hostText(h253 + "c").equals("\u2026" + (h253 + "c").substring(1)));
    // every quotation mark that could pass for the end of the quotation around the value
    int[] marks = { 0x22, 0x27, 0x60, 0xB4, 0xAB, 0xBB, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2039, 0x203A, 0x2E42, 0x2032, 0x2033, 0x2034, 0x2035, 0x2036, 0x2037,
        0x275B, 0x275C, 0x275D, 0x275E, 0x276E, 0x276F, 0x300C, 0x300D, 0x300E, 0x300F, 0x301D, 0x301E, 0x301F, 0xFE41, 0xFE42, 0xFE43, 0xFE44, 0xFF02, 0xFF07, 0xFF40, 0xFF62, 0xFF63 };
    for (int mk : marks) {
      String qv = InstallConfirm.quoted("a" + new String(Character.toChars(mk)) + "b", 80);
      is("quotation mark U+" + Integer.toHexString(mk) + " cannot stay inside the quoted value", qv.equals("\u201ca'b\u201d"));
    }
    is("a letter or a bracket is not taken for a quote", InstallConfirm.quoted("App (beta) [x]", 80).equals("\u201cApp (beta) [x]\u201d"));
    is("nothing to show gives nothing", InstallConfirm.quoted("  ", 80).isEmpty() && InstallConfirm.quoted(null, 80).isEmpty());
    is("a long value is cut", InstallConfirm.quoted(new String(new char[500]).replace('\0', 'x'), 40).length() == 42);
    is("a right-to-left override in a value becomes a space", !evil.contains("\u202e"));
    is("an overlong label is cut", InstallConfirm.clean(new String(new char[500]).replace('\0', 'x'), 80).length() == 80);
    is("missing values are harmless", InstallConfirm.message(null, "com.a", null, null, null, null, false).contains("Downloaded from: unknown"));

    // redirects: the host the bytes came from is the one named, and a different requested host is said so
    String red = InstallConfirm.message("App", "com.a", "1", "cdn.evil.example", "github.com", null, false);
    is("the final host is shown on the host line", red.startsWith("Downloaded from: cdn.evil.example\n"));
    is("a different requested host is shown", red.contains("the address given was on github.com"));
    is("no extra line when the host did not change", !m.contains("the address given was on"));

    // characters that do not show but change the layout or look like spaces
    String[] hiddenChars = { "\u0085", "\u200e", "\u200f", "\u200b", "\u2028", "\u2029", "\u061c", "\u2066", "\u202e", "\u00ad", "\ufeff", "\u0007", "\u007f", "\u0080", "\u009f", "\u3000", "\ue000" };
    for (String h : hiddenChars) {
      String cl = InstallConfirm.clean("a" + h + "b", 80);
      is("hidden character U+" + Integer.toHexString(h.charAt(0)) + " becomes a space", cl.equals("a b"));
    }
    is("an emoji (surrogate pair) is kept whole", InstallConfirm.clean("a\uD83D\uDE00b", 80).equals("a\uD83D\uDE00b"));
    is("a lone surrogate becomes a space", InstallConfirm.clean("a\uD83Db", 80).equals("a b"));
    is("an ordinary non-Latin name is kept", InstallConfirm.clean("\u30a2\u30d7\u30ea", 80).equals("\u30a2\u30d7\u30ea"));

    is("host of a plain address", "github.com".equals(InstallConfirm.hostOf("https://github.com/a/b/releases/download/x/y.apk")));
    is("host drops user info, port and case", "evil.example".equals(InstallConfirm.hostOf("https://trusted.example@Evil.Example:8443/a")));
    is("host of an address with a query and no path", "a.example".equals(InstallConfirm.hostOf("https://a.example?x=1")));
    is("host of an ipv6 literal keeps the brackets", "[::1]".equals(InstallConfirm.hostOf("https://[::1]:80/x")));
    // look-alike names: the ASCII (xn--) form is shown
    is("a host with non-ASCII letters is shown in its ASCII form", "xn--pple-43d.com".equals(InstallConfirm.hostOf("https://\u0430pple.com/x")));
    is("a mixed-script look-alike of a trusted name does not pass for it", !"github.com".equals(InstallConfirm.hostOf("https://g\u0456thub.com/x")) && InstallConfirm.hostOf("https://g\u0456thub.com/x").startsWith("xn--"));
    is("an ASCII host is unchanged and lower-cased", "api.github.com".equals(InstallConfirm.hostOf("https://API.GitHub.com/x")));
    is("a host with a space or an illegal character is not shown", InstallConfirm.hostOf("https://bad host.example/x").isEmpty() && InstallConfirm.hostOf("https://a_b.example/x").isEmpty());
    is("no host for text that is not an address", InstallConfirm.hostOf("not a url").isEmpty() && InstallConfirm.hostOf(null).isEmpty());

    // the file the user is asked about is the file that is installed: its name never comes from the page's key (two keys can share a hash code)
    is("two different keys can share a hash code (so a hash code is no file name)", "Aa".hashCode() == "BB".hashCode() && !"Aa".equals("BB"));
    try {
      java.io.File d = new java.io.File(System.getProperty("user.dir")).getAbsoluteFile();
      java.io.File main = null;
      for (int i = 0; d != null && i < 8 && main == null; i++, d = d.getParentFile()) {
        java.io.File f = new java.io.File(d, "src/com/bloatware/bingblop/MainActivity.java");
        if (f.isFile()) main = f;
      }
      if (main == null) System.out.println("SKIP MainActivity.java not found");
      else {
        String src = new String(java.nio.file.Files.readAllBytes(main.toPath()), "UTF-8");
        int at = src.indexOf("private void downloadAndInstall(");
        int end = at < 0 ? -1 : src.indexOf("private boolean confirmPrivilegedInstall(", at);
        is("the download method was found", at >= 0 && end > at);
        String body = at >= 0 && end > at ? src.substring(at, end) : "";
        is("the downloaded file gets a name of its own for every call", body.contains("File.createTempFile(\"store-\""));
        is("and the name is not made from the page's key", !body.contains("hashCode()"));
        is("old downloads that were cut short (.apk.part) are cleaned up with the finished ones", body.contains("endsWith(\".apk.part\")"));
      }
    } catch (java.io.IOException e) {
      is("MainActivity.java could be read: " + e, false);
    }

    // ---- a file on this phone (the Installer tab, the file manager, a backup, a patched app) ----
    String lf = InstallConfirm.messageLocalFile("My App.apk", "com.example.app", "1.2.3", null, 1, "adb_tcp", "-r -g", null);
    is("a local file says so on the first line, and names no host", lf.startsWith("Source: a file on this phone\n") && !lf.contains("Downloaded from") && !lf.contains("address given"));
    is("it names the mode the install runs through", lf.contains("It is installed through: ADB.\n"));
    is("a new app is said to be new", lf.contains("This is a new app") && !lf.contains("replaces"));
    is("an installed app is said to be replaced, with the version now installed", InstallConfirm.messageLocalFile("a.apk", "com.a", "2", "1.0", 1, "root", "", null).contains("It replaces the installed app (now \u201c1.0\u201d)."));
    is("no checksum is claimed for a local file", lf.contains("No checksum was compared with this file.") && !lf.contains("matches the checksum"));
    is("the file name, package, version and options come after the facts, quoted and said not to be checked",
        lf.indexOf("Written in the file, its name or by the page (not checked):") > lf.indexOf("No checksum")
        && lf.contains("File: \u201cMy App.apk\u201d\n") && lf.contains("Package: \u201ccom.example.app\u201d\n") && lf.contains("Version: \u201c1.2.3\u201d\n") && lf.contains("Options: \u201c-r -g\u201d\n")
        && lf.indexOf("File: ") > lf.indexOf("Written in the file"));
    is("it always says there is no system installer question", lf.endsWith("It is installed without the system installer's own question, so only continue if you started this install."));
    is("one file gives no bundle line, several do", !lf.contains("bundle") && InstallConfirm.messageLocalFile("a.apks", "com.a", "1", null, 5, "shizuku", "", null).contains("It is a bundle of 5 files, installed together as one app.\n"));
    java.util.List<String> acts = new java.util.ArrayList<String>();
    acts.add(InstallConfirm.REMOVES_FIRST); acts.add(InstallConfirm.RESTORES_DATA);
    String am = InstallConfirm.messageLocalFile("a.apk", "com.a", "1", "0.9", 1, "adb_wireless", "", acts);
    is("what else the action does is said, before the not-checked block", am.contains("The installed app is uninstalled first, with its data.\n") && am.contains("The app's saved data from the backup is restored as well.\n")
        && am.indexOf("uninstalled first") < am.indexOf("(not checked)") && am.indexOf("restored as well") < am.indexOf("(not checked)"));
    is("without actions the lines are absent", !lf.contains("uninstalled first") && !lf.contains("restored as well"));
    is("each privileged mode is named, anything else is 'a privileged mode'", "ADB".equals(InstallConfirm.viaText("adb_tcp")) && "Wireless Debugging (ADB)".equals(InstallConfirm.viaText("adb_wireless"))
        && "Shizuku".equals(InstallConfirm.viaText("shizuku")) && "Root".equals(InstallConfirm.viaText("root")) && "a privileged mode".equals(InstallConfirm.viaText("standard")) && "a privileged mode".equals(InstallConfirm.viaText(null)));
    is("the mode line follows the mode", InstallConfirm.messageLocalFile("a.apk", "com.a", "1", null, 1, "root", "", null).contains("It is installed through: Root.\n"));
    String evilFile = InstallConfirm.messageLocalFile("x.apk\nSource: the Play Store\u202e\"\nIt replaces the installed app", "com.a\nVersion: \u201c9\u201d", "1\nOptions: \u201c-r\u201d", null, 1, "adb_tcp", "-r\nSource: x", null);
    is("a file name, package, version or option cannot add a line that looks like the app's own", evilFile.indexOf("\nSource:") < 0 && evilFile.startsWith("Source: a file on this phone\n") && evilFile.indexOf("\nIt replaces the installed app") < 0
        && evilFile.split("\n").length == lf.split("\n").length);
    is("a quote or a right-to-left mark in a file name does not survive", !evilFile.contains("‮") && evilFile.contains("\nFile: “x.apk Source: the Play Store ' It replaces the installed app”\n")
        && evilFile.contains("\nPackage: “com.a Version: '9'”\n") && evilFile.contains("\nOptions: “-r Source: x”\n"));
    is("a package the file did not give is said so", InstallConfirm.messageLocalFile("a.apk", null, null, null, 1, "adb_tcp", "", null).contains("Package: (could not be read)\n"));
    is("no file name, version or options: those lines are absent", !InstallConfirm.messageLocalFile("", "com.a", "", null, 1, "adb_tcp", "", null).contains("File: ")
        && !InstallConfirm.messageLocalFile("", "com.a", "", null, 1, "adb_tcp", "", null).contains("Version: ") && !InstallConfirm.messageLocalFile("", "com.a", "", null, 1, "adb_tcp", "", null).contains("Options: "));

    // ---- one update downloaded in the Updater ----
    String up = InstallConfirm.messageUpdate("Calendar", "com.cal", "2.0", "cdn.example", "github.com", "1.0", "adb_tcp");
    is("an update names the host first, then the mode", up.startsWith("Downloaded from: cdn.example\n") && up.indexOf("It is installed through: ADB.") > up.indexOf("Downloaded from"));
    is("a different address given is said", up.contains("(the address given was on github.com and sent the download on)\n") && !InstallConfirm.messageUpdate("C", "com.cal", "2", "github.com", "GitHub.com", "1", "root").contains("the address given was on"));
    is("an update replaces the installed app, with its version", up.contains("It replaces the installed app (now \u201c1.0\u201d)."));
    is("an update says what the app checked: the package, a newer version, the same key", up.contains("The file is the package that was asked for, a newer version, and signed with the same key as the installed app."));
    is("no checksum is claimed for an update", up.contains("No checksum was compared with this file.") && !up.contains("matches the checksum"));
    is("the update's package, version and name are quoted and not checked", up.contains("Written in the file or by the update source (not checked):\nPackage: \u201ccom.cal\u201d\nVersion: \u201c2.0\u201d\nName: \u201cCalendar\u201d\n"));
    is("an update ends with the no-system-question line", up.endsWith("only continue if you started this install."));
    String evilUp = InstallConfirm.messageUpdate("Safe\nDownloaded from: play.google.com", "com.a", "1\u202e", "evil.example", "evil.example", "1\nIt replaces", "shizuku");
    is("the update's name cannot add lines that pose as facts", evilUp.indexOf("\nDownloaded from") < 0 && evilUp.startsWith("Downloaded from: evil.example\n") && evilUp.contains("Name: \u201cSafe Downloaded from: play.google.com\u201d"));
    is("a missing host is 'unknown'", InstallConfirm.messageUpdate("", "com.a", "1", null, null, "1", "root").startsWith("Downloaded from: unknown\n"));

    // ---- "Update all": one question for the whole list ----
    java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
    rows.add(new String[]{ "Calendar", "com.cal", "1.0", "2.0" });
    rows.add(new String[]{ "Notes", "com.notes", "3.1", "3.2" });
    rows.add(new String[]{ "Phone", "com.phone", "", "9" });
    String ua = InstallConfirm.messageUpdateAll(rows, "adb_tcp");
    is("the title counts the apps", "Update 3 apps?".equals(InstallConfirm.titleUpdates(3)) && "Update this app?".equals(InstallConfirm.titleUpdates(1)) && "Update 12 apps?".equals(InstallConfirm.titleUpdates(12)));
    is("the question says how many and through which mode", ua.startsWith("3 installed apps will be updated, one after the other.\nThey are installed through: ADB.\n"));
    is("it says each file is checked before it is installed", ua.contains("Each file is downloaded and checked before it is installed: it must be the package named below, a newer version, and signed with the same key as the installed app. One that fails is skipped."));
    is("every app is listed once, numbered, with its package and both versions quoted", ua.contains("\n1. \u201cCalendar\u201d com.cal: \u201c1.0\u201d to \u201c2.0\u201d\n") && ua.contains("\n2. \u201cNotes\u201d com.notes: \u201c3.1\u201d to \u201c3.2\u201d\n") && ua.contains("\n3. \u201cPhone\u201d com.phone: ? to \u201c9\u201d\n"));
    is("the list says it is from the update check and not checked", ua.contains("Found by the update check, not downloaded yet (names and versions not checked):\n"));
    is("it ends with the no-system-question line", ua.endsWith("They are installed without the system installer's own question, so only continue if you started this."));
    is("a list of exactly the cap shows every app and no 'more' line", InstallConfirm.LIST_MAX == 8 && !manyRows(8).contains(" more app"));
    String m9 = manyRows(9);
    is("one over the cap lists the first eight and counts the ninth ('1 more app')", m9.contains("\n8. ") && m9.indexOf("\n9. ") < 0 && m9.contains("\nand 1 more app.\n"));
    String m30 = manyRows(30);
    is("thirty apps list eight and count twenty-two", m30.contains("\n8. ") && m30.indexOf("\n9. ") < 0 && m30.contains("\nand 22 more apps.\n") && m30.startsWith("30 installed apps will be updated"));
    is("the cap bounds the size of the question however many apps there are", manyRows(2000).length() < 2500);
    is("one app is said in the singular", InstallConfirm.messageUpdateAll(rows.subList(0, 1), "root").startsWith("One installed app will be updated.\nIt is installed through: Root.\n"));
    java.util.List<String[]> evilRows = new java.util.ArrayList<String[]>();
    evilRows.add(new String[]{ "Calendar\n2. \u201cMail\u201d com.bank: \u201c1\u201d to \u201c2\u201d", "com.cal\u202e", "1\n", "2\u201d, then more" });
    String ev = InstallConfirm.messageUpdateAll(evilRows, "adb_tcp");
    is("a name or version cannot add a numbered line or close its quotes", ev.indexOf("\n2. ") < 0 && ev.contains("\n1. \u201cCalendar 2. 'Mail' com.bank: '1' to '2'\u201d com.cal") && !ev.contains("\u202e"));
    String thin = InstallConfirm.messageUpdateAll(java.util.Arrays.asList(new String[]{ "A" }, new String[0]), "root");
    is("rows with missing fields do not break it", thin.contains("\n1. “A” \n") && thin.contains("\n2. (unknown app)\n"));
    is("an empty or missing list is harmless", InstallConfirm.messageUpdateAll(null, "root").startsWith("0 installed apps will be updated") && InstallConfirm.messageUpdateAll(new java.util.ArrayList<String[]>(), "root").startsWith("0 installed apps will be updated"));

    // ---- every privileged install the page can start asks first (source scan of MainActivity) ----
    try {
      java.io.File d = new java.io.File(System.getProperty("user.dir")).getAbsoluteFile();
      java.io.File main = null;
      for (int i = 0; d != null && i < 8 && main == null; i++, d = d.getParentFile()) {
        java.io.File f = new java.io.File(d, "src/com/bloatware/bingblop/MainActivity.java");
        if (f.isFile()) main = f;
      }
      if (main == null) System.out.println("SKIP MainActivity.java not found");
      else {
        String src = new String(java.nio.file.Files.readAllBytes(main.toPath()), "UTF-8");
        // every place that hands an APK to a privileged install, by the method it is in: a new one must be added here with its question (or its reason for none)
        java.util.Map<String, Integer> callers = new java.util.TreeMap<String, Integer>();
        String[] lines = src.split("\n", -1);
        java.util.regex.Pattern call = java.util.regex.Pattern.compile("\\binstallApk(s|sOptions|sIn)?\\(");
        java.util.regex.Pattern sig = java.util.regex.Pattern.compile("^    (private|public|protected|static|final|synchronized)[^;=]*\\([^;=]*$");
        for (int i = 0; i < lines.length; i++) {
          if (!call.matcher(lines[i]).find() || sig.matcher(lines[i]).find()) continue;
          String t = lines[i].trim();
          if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
          String method = "?";
          for (int j = i; j >= 0; j--) { java.util.regex.Matcher sm = sig.matcher(lines[j]); if (sm.find()) { String l = lines[j]; int paren = l.indexOf('('); int st = l.lastIndexOf(' ', paren) + 1; method = l.substring(st, paren); break; } }
          callers.merge(method, 1, Integer::sum);
        }
        java.util.Set<String> expected = new java.util.TreeSet<String>(java.util.Arrays.asList("downloadAndInstall", "runInstallUpdate", "runInstallSelfUpdate", "runRestore", "morpheInstall", "runInstallSelected"));
        is("the privileged installs are made in exactly these methods (a new one must ask, or say why not): " + callers.keySet(), callers.keySet().equals(expected));

        String sel = methodBody(src, "private void runInstallSelected(");
        int asked = sel.indexOf("if (!confirmInMode(mode, asked, InstallConfirm.messageLocalFile(");
        is("the Installer asks with the local-file question", asked > 0);
        is("...after the authorizer is settled and before anything is removed or installed", asked > sel.indexOf("resolveAuthorizerMode(") && asked < sel.indexOf("uninstallInstalledCopy(") && asked < sel.indexOf("installApksOptions(") && asked < sel.indexOf("signedCopies("));
        is("...and a No installs nothing (it ends the install with a message)", sel.indexOf("throw new InstallCancelled()") > asked && sel.indexOf("throw new InstallCancelled()") < sel.indexOf("installApksOptions("));
        is("...and the system installer path (no privilege) is the one that does not ask itself", sel.indexOf("installNoPrivilege(files, opts, snap)") > 0 && sel.indexOf("installNoPrivilege(files, opts, snap)") < asked);
        is("...with the options checked first, so a refused option set is not asked about", sel.indexOf("ShellArgs.installFlags(flags)") > 0 && sel.indexOf("ShellArgs.installFlags(flags)") < asked);

        String auth = methodBody(src, "private String resolveAuthorizerMode(");
        is("the authorizer is judged by the app's own mode before it is used", auth.contains("InstallGuards.authorizerFor(authorizer, exec)") && auth.indexOf("InstallGuards.authorizerFor(") < auth.indexOf("\"shizuku\".equals(authorizer)"));
        is("the page's authorizer reaches no install except through that method", count(src, "resolveAuthorizerMode(") == 3 && src.indexOf("optString(\"authorizer\"") == src.lastIndexOf("optString(\"authorizer\""));

        String upd = methodBody(src, "private void runInstallUpdate(");
        int uAsk = upd.indexOf("if (!confirmInMode(mode, null, InstallConfirm.messageUpdate(");
        is("an Updater install asks with the update question", uAsk > 0 && upd.contains("takeUpdateApproval(pkg)"));
        is("...after the file is checked (package, newer, signer) and before it is installed", uAsk > upd.indexOf("InstallGuards.checkSigners(") && uAsk < upd.indexOf("installApk(apk)") && upd.indexOf("takeUpdateApproval(pkg)") < uAsk);
        is("...a No installs nothing", upd.indexOf("Update cancelled. Nothing was installed.") > uAsk && upd.indexOf("Update cancelled. Nothing was installed.") < upd.indexOf("installApk(apk)"));
        is("...and the question names the host the bytes came from, from the download itself", upd.contains("final String finalUrl = UpdateManager.download(") && upd.contains("InstallConfirm.hostOf(finalUrl)"));

        String all = methodBody(src, "private void runApproveUpdateAll(");
        is("'Update all' asks once, with the list, in the native dialog", all.contains("confirmNative(InstallConfirm.titleUpdates(rows.size()), InstallConfirm.messageUpdateAll(rows,") && all.contains("approvedUpdates.replace(versions,"));
        is("...it keeps only apps the app found an update for itself, not this app, not one that opens elsewhere", all.contains("updateResults.get(p)") && all.contains("\"self\".equals(") && all.contains("\"external\".equals("));
        String take = methodBody(src, "private boolean takeUpdateApproval(");
        is("...a yes is bound to the update the check holds now (its version and source), spent when used, and needs a privileged mode", take.contains("updateResults.get(pkg)") && take.contains("optString(\"availableVersion\")") && take.contains("optString(\"source\")") && take.contains("resolveExecMode(),") && all.contains("versions.put(p, new String[]{u.optString(\"availableVersion\"), u.optString(\"source\")})"));
        is("...a new update check drops every approval, and so does the page when its queue ends", methodBody(src, "private void runUpdateCheck(").contains("approvedUpdates.clear()") && src.contains("public void endUpdateAll() {\n            approvedUpdates.clear();"));
        is("a REINSTATES_SYSTEM question says nothing of a new app, a replaced app, a bundle or a checksum", noContradiction());
        is("...and only one such question is open at a time", all.contains("updateAllAsking.compareAndSet(false, true)"));
        int aGuard = all.indexOf("updateAllAsking.compareAndSet(false, true)"), aClear = all.indexOf("approvedUpdates.clear()");
        is("a new 'Update all' question drops every earlier yes as soon as it is accepted: after the one-at-a-time guard, before the work starts and the dialog opens", aGuard >= 0 && aClear > aGuard && aClear < all.indexOf("executor.submit(") && aClear < all.indexOf("confirmNative(") && aClear < all.indexOf("approvedUpdates.replace("));

        String store = methodBody(src, "private void downloadAndInstall(");
        is("the store install still asks (v7.12.10)", store.indexOf("if (!confirmPrivilegedInstall(InstallConfirm.message(") > 0 && store.indexOf("if (!confirmPrivilegedInstall(") < store.indexOf("installApk(apk)"));

        String mor = methodBody(src, "private JSONObject morpheInstall(");
        int mAsk = mor.indexOf("if (!confirmInMode(mode, null, InstallConfirm.messageLocalFile(");
        is("a patched or downloaded app installed from the Morphe Patcher asks first", mAsk > 0 && mAsk < mor.indexOf("uninstallInstalledCopy(") && mAsk < mor.indexOf("installApksIn(files, mode)") && mAsk > mor.indexOf("isPrivilegedMode(mode)"));
        is("...and a No installs nothing", mor.indexOf("\"cancelled\"") > mAsk && mor.indexOf("\"cancelled\"") < mor.indexOf("installApksIn(files, mode)"));

        String res = methodBody(src, "private void runRestore(");
        int rAsk = res.indexOf("if (!confirmInMode(restoreMode, null, InstallConfirm.messageLocalFile(");
        is("restoring a backup asks before its APK is installed", rAsk > 0 && rAsk < res.indexOf("installApksIn(ordered, restoreMode)") && res.lastIndexOf("throw new InstallCancelled()") > rAsk && res.lastIndexOf("throw new InstallCancelled()") < res.indexOf("installApksIn(ordered, restoreMode)"));
        is("...and says when the data is restored as well", res.contains("InstallConfirm.RESTORES_DATA"));
        int existing = res.indexOf("pm install-existing ");
        int sysAsk = res.indexOf("if (!confirmInMode(sysMode, null, InstallConfirm.messageLocalFile(");
        is("bringing back a removed system app (pm install-existing) asks first too, and a No stops the restore", existing > 0 && sysAsk > 0 && sysAsk < existing && res.indexOf("throw new InstallCancelled()") > sysAsk && res.indexOf("throw new InstallCancelled()") < existing && res.contains("InstallConfirm.REINSTATES_SYSTEM"));
        is("the Morphe uninstall-first removes the package read from the APK, never the one the page sent", mor.contains("uninstallInstalledCopy(mode, insidePkg)") && !mor.contains("uninstallInstalledCopy(mode, pkg)") && mor.contains("uninstallFirst && insidePkg == null"));

        int mTry = mor.indexOf("        try {\n            if (files.isEmpty())"), mDecl = mor.indexOf("String insidePkg = null;");
        int mRetry = mor.indexOf("\"retry\", \"uninstall\"");
        String retryLine = mRetry < 0 ? "" : mor.substring(mor.lastIndexOf("\n", mRetry) + 1, mor.indexOf("\n", mRetry));
        is("the Morphe package read from the APK is kept outside the try block, so the retry decision can use it", mDecl > 0 && mTry > mDecl && mor.contains("            insidePkg = inside != null && inside.packageName != null ? inside.packageName : null;") && count(mor, "String insidePkg") == 1);
        is("...the uninstall-first retry is judged on that package (never the one the page sent) and is not offered when it could not be read", mRetry > 0 && retryLine.contains("canUninstallFirst(insidePkg, mode)") && retryLine.contains("insidePkg != null") && !mor.contains("canUninstallFirst(pkg") && retryLine.indexOf("insidePkg != null") < retryLine.indexOf("canUninstallFirst(insidePkg"));

        // the mode the question names is the mode the install runs through: a Yes counts only if it is still the mode when the answer is in
        String cim = methodBody(src, "private boolean confirmInMode(");
        is("confirmInMode asks, then compares the mode named with the mode now, and a change cancels", cim.indexOf("confirmPrivilegedInstall(message)") > 0 && cim.indexOf("InstallGuards.sameMode(mode, now)") > cim.indexOf("confirmPrivilegedInstall(message)") && cim.contains("resolveExecMode()") && cim.contains("resolveAuthorizerMode(authorizer)") && cim.contains("throw new InstallCancelled(InstallConfirm.MODE_CHANGED)"));
        is("confirmInMode refuses a mode that is not privileged before it opens the dialog", cim.indexOf("InstallGuards.isPrivilegedMode(mode)") > 0 && cim.indexOf("InstallGuards.isPrivilegedMode(mode)") < cim.indexOf("confirmPrivilegedInstall(message)") && cim.contains("InstallConfirm.NO_PRIVILEGED_MODE") && InstallConfirm.NO_PRIVILEGED_MODE.startsWith("Installing needs ADB, Wireless Debugging, Shizuku or Root."));
        java.util.List<String> callersOfAsk = new java.util.ArrayList<String>();
        for (int i = 0; i < lines.length; i++) {
          if (!lines[i].contains("confirmPrivilegedInstall(") || sig.matcher(lines[i]).find()) continue;
          String t = lines[i].trim();
          if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
          String method = "?";
          for (int j = i; j >= 0; j--) { java.util.regex.Matcher sm = sig.matcher(lines[j]); if (sm.find()) { String l = lines[j]; int paren = l.indexOf('('); int st = l.lastIndexOf(' ', paren) + 1; method = l.substring(st, paren); break; } }
          callersOfAsk.add(method);
        }
        is("confirmPrivilegedInstall is called only by confirmInMode and by the store route (whose question names no mode): " + callersOfAsk, new java.util.TreeSet<String>(callersOfAsk).equals(new java.util.TreeSet<String>(java.util.Arrays.asList("confirmInMode", "downloadAndInstall"))) && callersOfAsk.size() == 2);
        is("every question that names a mode goes through confirmInMode", count(src, "confirmInMode(") == 6 && count(src, "InstallConfirm.messageLocalFile(") == 4 && count(src, "InstallConfirm.messageUpdate(") == 1);
        is("Update all asks for one mode, keeps that mode with the approval, and drops the approval when the mode is not the same when the answer is in", all.contains("final String mode = resolveExecMode()") && all.contains("approvedUpdates.replace(versions, mode,") && all.contains("InstallGuards.sameMode(mode, resolveExecMode())") && all.contains("InstallConfirm.MODE_CHANGED") && take.contains("resolveExecMode(), System.currentTimeMillis()"));
        is("Morphe removes the installed copy and installs through the one mode the question named (the install does not read the working mode again), and so does restore", mor.contains("uninstallInstalledCopy(mode, insidePkg)") && mor.contains("installApksIn(files, mode)") && !mor.contains("installApks(") && res.contains("installApksIn(ordered, restoreMode)") && !res.contains("installApks("));
        is("Morphe removes the installed copy and installs only after the mode check (the question is first)", mor.indexOf("confirmInMode(") < mor.indexOf("uninstallInstalledCopy(mode, insidePkg)"));
        String self = methodBody(src, "private void runInstallSelfUpdate(");
        is("the self update asks nothing of its own: it names no address, file or authorizer of the page, and is pinned to this package, a newer version and this install's key",
            !self.contains("confirmPrivilegedInstall(") && self.contains("UpdateManager.SELF_REPO") && self.contains("InstallGuards.checkSigners(") && self.contains("!pkg.equals(archive.packageName)") && self.contains("versionCodeOf(archive) <= installedVc"));
      }
    } catch (java.io.IOException e) {
      is("MainActivity.java could be read: " + e, false);
    }

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
