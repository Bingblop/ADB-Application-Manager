package com.bloatware.bingblop;

/** The question before a privileged install of a downloaded app: what it shows, and that page-supplied text cannot disguise it. */
public class InstallConfirmTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

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
      }
    } catch (java.io.IOException e) {
      is("MainActivity.java could be read: " + e, false);
    }

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
