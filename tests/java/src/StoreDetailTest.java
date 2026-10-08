package com.bloatware.bingblop;   // same package: resolve / goodPicture / projectItem / isoMillis are package-private
import org.json.*;
import java.io.*;
import java.util.*;

public class StoreDetailTest {
  static int fails = 0;
  static void check(String n, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + n); if (!ok) fails++; }

  public static void main(String[] a) throws Exception {
    String raw = "https://raw.githubusercontent.com/o/r/main/";
    String md = "# App\n[![Build](https://img.shields.io/github/actions/workflow/status/o/r/ci.yml)](https://ci)\n"
      + "![Logo](docs/logo.png)\n"
      + "![Home screen](docs/home.png \"Home\")\n"
      + "<img src=\"/assets/settings.png\" width=\"200\">\n"
      + "<img alt='x' src='https://example.org/shot3.jpg'>\n"
      + "![vector](docs/diagram.svg)\n"
      + "![fdroid](https://f-droid.org/badge/get-it-on.png)\n"
      + "![inline](data:image/png;base64,AAAA)\n"
      + "![same](docs/home.png)\n"
      + "![up](../outside.png)\n"
      + "![plain](http://example.org/plain.png)\n"
      + "![space](docs/my shot.png)\n"
      + "![angle](<docs/angle.png>)\n";
    List<String> im = StoreDetail.imagesIn(md, raw, 12);
    System.out.println("  images: " + im);
    check("relative pictures are resolved against the repository's files", im.contains(raw + "docs/home.png") && im.contains(raw + "assets/settings.png"));
    check("full addresses are kept; http becomes https", im.contains("https://example.org/shot3.jpg") && im.contains("https://example.org/plain.png"));
    check("badges, logos, vector files, inline data and pictures outside the repository are left out", !im.toString().contains("shields.io") && !im.toString().contains("logo.png") && !im.toString().contains("diagram.svg") && !im.toString().contains("badge") && !im.toString().contains("data:") && !im.toString().contains("outside"));
    check("a picture shown twice is listed once", Collections.frequency(im, raw + "docs/home.png") == 1);
    check("a title after the address and angle brackets are understood", im.contains(raw + "docs/angle.png"));
    check("the order is the README's order", im.indexOf(raw + "docs/home.png") < im.indexOf(raw + "assets/settings.png") && im.indexOf(raw + "assets/settings.png") < im.indexOf("https://example.org/shot3.jpg"));
    check("at most max pictures", StoreDetail.imagesIn(md, raw, 2).size() == 2);
    check("no text, no pictures", StoreDetail.imagesIn("", raw, 5).isEmpty() && StoreDetail.imagesIn(null, raw, 5).isEmpty() && StoreDetail.imagesIn("![x](a.png)", "", 5).isEmpty());
    check("goodPicture: a screenshot passes, a button does not", StoreDetail.goodPicture("https://x/screens/1.png") && !StoreDetail.goodPicture("https://x/get-it-on.png") && !StoreDetail.goodPicture("https://x/a.svg?sanitize=true"));

    // ---------- the side file of a repository ----------
    File f = File.createTempFile("detail", ".detail");
    f.deleteOnExit();
    JSONObject d1 = new JSONObject().put("d", "First \"quoted\"\nline two").put("shots", new JSONArray().put("https://x/1.png"));
    JSONObject d2 = new JSONObject().put("d", "Second").put("web", "https://w");
    BufferedWriter w = new BufferedWriter(new FileWriter(f));
    w.write(StoreDetail.detailLine("org.a", d1)); w.write('\n');
    w.write(StoreDetail.detailLine("org.a.nightly", d2)); w.write('\n');
    w.close();
    JSONObject g1 = StoreDetail.fromFile(f, "org.a"), g2 = StoreDetail.fromFile(f, "org.a.nightly");
    check("a side file gives the detail of the app asked for (and only that one)", g1 != null && g1.optString("d").equals("First \"quoted\"\nline two") && g2 != null && "Second".equals(g2.optString("d")));
    check("a package whose name starts like another is not confused with it", g1.optJSONArray("shots") != null && g2.optJSONArray("shots") == null);
    check("an app that is not in the file, or no file, gives null", StoreDetail.fromFile(f, "org.zzz") == null && StoreDetail.fromFile(new File("/nonexistent/x"), "org.a") == null && StoreDetail.fromFile(null, "org.a") == null);
    check("a line is one line (text with line breaks is escaped)", StoreDetail.detailLine("p", d1).indexOf('\n') < 0);

    // ---------- the projects of a custom store ----------
    JSONObject gh = new JSONObject("{\"name\":\"proj\",\"owner\":{\"login\":\"alice\",\"avatar_url\":\"https://a/av.png\"},\"description\":\"A project\",\"language\":\"Kotlin\",\"html_url\":\"https://github.com/alice/proj\",\"stargazers_count\":42,\"pushed_at\":\"2024-05-01T12:00:00Z\"}");
    JSONObject it = StoreDetail.projectItem("github", gh);
    check("a GitHub project becomes an installable item (latest release)", "alice/proj".equals(it.optString("key")) && "github".equals(it.optString("resolveKind")) && "alice".equals(it.optString("owner")) && "proj".equals(it.optString("repo")) && it.optLong("stars") == 42 && "custom".equals(it.optString("source")));
    check("its category is the language, its icon the owner's picture, its time the last push", "Kotlin".equals(it.getJSONArray("cats").getString(0)) && "https://a/av.png".equals(it.optString("icon")) && it.optLong("updated") == 1714564800000L);
    JSONObject cb = new JSONObject("{\"name\":\"tool\",\"owner\":{\"login\":\"bob\",\"avatar_url\":\"\"},\"description\":null,\"language\":null,\"html_url\":\"https://codeberg.org/bob/tool\",\"stars_count\":7,\"updated_at\":\"2023-01-02T03:04:05+01:00\"}");
    JSONObject ci = StoreDetail.projectItem("codeberg", cb);
    check("a Codeberg project uses its own field names; nulls become empty text and Other", "codeberg".equals(ci.optString("resolveKind")) && ci.optLong("stars") == 7 && "".equals(ci.optString("desc")) && "Other".equals(ci.getJSONArray("cats").getString(0)) && ci.optLong("updated") > 0);
    check("a time that is not a time is 0", StoreDetail.isoMillis("") == 0 && StoreDetail.isoMillis("yesterday") == 0);

    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
