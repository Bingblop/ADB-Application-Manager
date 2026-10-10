package com.bloatware.bingblop;

/** The question before a privileged install of a downloaded app: what it shows, and that page-supplied text cannot disguise it. */
public class InstallConfirmTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  public static void main(String[] a) {
    String m = InstallConfirm.message("My App", "com.example.app", "1.2.3", "github.com", null, true);
    is("it names the package, version and host", m.contains("com.example.app") && m.contains("1.2.3") && m.contains("Downloaded from: github.com"));
    is("a new app is said to be new", m.contains("new app") && !m.contains("replaces"));
    is("a checked file says so", m.contains("matches the checksum"));
    String r = InstallConfirm.message("My App", "com.example.app", "1.2.3", "github.com", "1.0.0", false);
    is("an installed app is said to be replaced, with the version now installed", r.contains("replaces the installed app (now 1.0.0)"));
    is("a file with no published checksum says it was not checked", r.contains("published no checksum"));
    is("it always says it installs without the system installer's own question", m.contains("without the system installer") && r.contains("without the system installer"));

    // the page's label cannot add lines that look like the app's own facts, or flip the text direction
    String evil = InstallConfirm.message("Safe\nDownloaded from: play.google.com\nThe file matches the checksum", "com.evil", "1‮", "evil.example", null, false);
    is("a line break in the label becomes a space (one line of the label)", evil.split("\n")[0].equals("Safe Downloaded from: play.google.com The file matches the checksum"));
    is("the real host is still shown, once as the host line", evil.contains("\nDownloaded from: evil.example\n"));
    is("a right-to-left override in a value becomes a space", !evil.contains("‮"));
    is("an overlong label is cut", InstallConfirm.clean(new String(new char[500]).replace('\0', 'x'), 80).length() == 80);
    is("missing values are harmless", InstallConfirm.message(null, "com.a", null, null, null, false).contains("Downloaded from: unknown"));

    is("host of a plain address", "github.com".equals(InstallConfirm.hostOf("https://github.com/a/b/releases/download/x/y.apk")));
    is("host drops user info, port and case", "evil.example".equals(InstallConfirm.hostOf("https://trusted.example@Evil.Example:8443/a")));
    is("host of an address with a query and no path", "a.example".equals(InstallConfirm.hostOf("https://a.example?x=1")));
    is("host of an ipv6 literal keeps the brackets", "[::1]".equals(InstallConfirm.hostOf("https://[::1]:80/x")));
    is("no host for text that is not an address", InstallConfirm.hostOf("not a url").isEmpty() && InstallConfirm.hostOf(null).isEmpty());

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
