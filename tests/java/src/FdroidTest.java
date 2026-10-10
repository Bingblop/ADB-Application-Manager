package com.bloatware.bingblop;   // same package: parseV2/parseV1/compatible are package-private
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class FdroidTest {
  static final String FIX = System.getProperty("fixtures", "fixtures") + "/";
  static int fails = 0;
  static void check(String n, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + n); if (!ok) fails++; }
  static InputStream in(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }
  static JSONObject find(FdroidIndex.Result r, String pkg) throws Exception { for (int i = 0; i < r.items.length(); i++) if (r.items.getJSONObject(i).optString("pkg").equals(pkg)) return r.items.getJSONObject(i); return null; }

  public static void main(String[] a) throws Exception {
    String[] arm64 = {"arm64-v8a", "armeabi-v7a", "armeabi"};

    // ---------- real fixtures ----------
    FdroidIndex.Result seeker = FdroidIndex.parseV2(new FileInputStream(FIX + "seeker-index-v2.json"), "https://example.org/repo", arm64, 34, null);
    JSONObject s = find(seeker, "com.companyname.andriodapp1");
    check("real(Seeker): 1 app parsed", seeker.items.length() == 1 && s != null);
    check("real(Seeker): name/summary/category", "Seeker".equals(s.optString("name")) && s.optString("desc").startsWith("Android client for the Soulseek") && s.getJSONArray("cats").getString(0).equals("Internet"));
    check("real(Seeker): version + apk url + size + sha256", "2.10.5".equals(s.optString("ver")) && s.optLong("vc") == 104
      && "https://example.org/repo/seeker_v2.10.5.apk".equals(s.optString("apkUrl")) && s.optLong("size") == 18494187L
      && "b9d259e392f3e653ea2f7ca52d2491adad95d35d3a1276745b177ff3be9813ec".equals(s.optString("sha256")));
    check("real(Seeker): icon url joined to repo base", "https://example.org/repo/icons/com.companyname.andriodapp1.104.png".equals(s.optString("icon")));
    check("real(Seeker): updated timestamp captured", s.optLong("updated") > 1.5e12);

    FdroidIndex.Result wg = FdroidIndex.parseV2(new FileInputStream(FIX + "wgtunnel-index-v2.json"), "https://example.org/repo", arm64, 34, null);
    JSONObject w = find(wg, "com.zaneschepke.wireguardautotunnel");
    System.out.println("  WG Tunnel items: " + wg.items.length() + " | repo name: " + wg.repoName + " | skipped: " + wg.skipped);
    check("real(WG Tunnel): newest compatible version chosen (5.7.5 / 50705)", w != null && "5.7.5".equals(w.optString("ver")) && w.optLong("vc") == 50705);
    check("real(WG Tunnel): 2 categories kept", w.getJSONArray("cats").length() == 2 && w.getJSONArray("cats").getString(0).equals("Connectivity"));
    check("real(WG Tunnel): localized icon path", w.optString("icon").startsWith("https://example.org/repo/com.zaneschepke.wireguardautotunnel/en-US/icon_"));
    // An x86-only device must still get an installable version (or none) - never an arm-only build
    FdroidIndex.Result wgx = FdroidIndex.parseV2(new FileInputStream(FIX + "wgtunnel-index-v2.json"), "https://example.org/repo", new String[]{"x86_64"}, 34, null);
    check("real(WG Tunnel): ABI filter runs without error on x86_64 (" + wgx.items.length() + " apps)", wgx.items.length() <= wg.items.length());

    // ---------- selection rules ----------
    String idx = "{\"repo\":{\"name\":{\"en-US\":\"Test Repo\"}},\"packages\":{"
      + "\"p.multi\":{\"metadata\":{\"name\":{\"de\":\"Mehrfach\",\"en-US\":\"Multi\"},\"summary\":{\"fr\":\"Resume\"},\"categories\":[\"System\"],\"icon\":{\"en-US\":{\"name\":\"/p.multi/icon.png\"}}},\"versions\":{"
      + "\"h1\":{\"file\":{\"name\":\"/multi_10.apk\",\"sha256\":\"aa\",\"size\":10},\"manifest\":{\"versionName\":\"1.0\",\"versionCode\":10,\"nativecode\":[\"arm64-v8a\"]}},"
      + "\"h2\":{\"file\":{\"name\":\"/multi_20_x86.apk\",\"sha256\":\"bb\",\"size\":20},\"manifest\":{\"versionName\":\"2.0\",\"versionCode\":20,\"nativecode\":[\"x86\"]}},"
      + "\"h3\":{\"file\":{\"name\":\"/multi_15_arm.apk\",\"sha256\":\"cc\",\"size\":15},\"manifest\":{\"versionName\":\"1.5\",\"versionCode\":15,\"nativecode\":[\"arm64-v8a\",\"armeabi-v7a\"]}}}},"
      + "\"p.sdk\":{\"metadata\":{\"name\":\"Plain String Name\"},\"versions\":{"
      + "\"a\":{\"file\":{\"name\":\"/sdk_30.apk\",\"size\":1},\"manifest\":{\"versionName\":\"3.0\",\"versionCode\":30,\"usesSdk\":{\"minSdkVersion\":35}}},"
      + "\"b\":{\"file\":{\"name\":\"/sdk_20.apk\",\"size\":1},\"manifest\":{\"versionName\":\"2.0\",\"versionCode\":20,\"usesSdk\":{\"minSdkVersion\":21}}}}},"
      + "\"p.noversions\":{\"metadata\":{\"name\":{\"en-US\":\"None\"}},\"versions\":{}},"
      + "\"p.nofile\":{\"metadata\":{\"name\":{\"en-US\":\"NoFile\"}},\"versions\":{\"x\":{\"manifest\":{\"versionCode\":1}}}},"
      + "\"p.notapk\":{\"metadata\":{\"name\":{\"en-US\":\"Zip\"}},\"versions\":{\"x\":{\"file\":{\"name\":\"/a.zip\"},\"manifest\":{\"versionCode\":1}}}},"
      + "\"p.nulls\":{\"metadata\":{\"name\":null,\"summary\":null,\"categories\":null,\"icon\":null},\"versions\":{\"v\":{\"file\":{\"name\":\"/n.apk\",\"size\":5},\"manifest\":{\"versionName\":null,\"versionCode\":7}}}},"
      + "\"p.late\":{\"versions\":{\"v\":{\"file\":{\"name\":\"/late.apk\",\"size\":5},\"manifest\":{\"versionName\":\"9\",\"versionCode\":9}}},\"metadata\":{\"name\":{\"en-US\":\"Metadata After Versions\"},\"categories\":[\"Games\",\"Time\"]}}"
      + "}}";
    FdroidIndex.Result r = FdroidIndex.parseV2(in(idx), "https://r.example/repo/", arm64, 34, null);   // note trailing slash handled by caller; base given w/o
    check("repo name read", "Test Repo".equals(r.repoName));
    JSONObject m = find(r, "p.multi");
    check("highest COMPATIBLE versionCode wins (x86 20 skipped -> arm 15)", m != null && m.optLong("vc") == 15 && m.optString("apkUrl").endsWith("/multi_15_arm.apk"));
    check("en-US preferred over other locales; falls back to first when no en", "Multi".equals(m.optString("name")) && "Resume".equals(m.optString("desc")));
    JSONObject sd = find(r, "p.sdk");
    check("minSdk above device skipped -> older 2.0 chosen; plain-string name accepted", sd != null && sd.optLong("vc") == 20 && "Plain String Name".equals(sd.optString("name")));
    check("apps with no versions / no file / non-apk are skipped (3)", find(r, "p.noversions") == null && find(r, "p.nofile") == null && find(r, "p.notapk") == null && r.skipped == 3);
    JSONObject nl = find(r, "p.nulls");
    check("explicit JSON nulls tolerated; name falls back to package id; uncategorized", nl != null && "p.nulls".equals(nl.optString("name")) && nl.getJSONArray("cats").getString(0).equals("Uncategorized") && nl.optString("ver").equals(""));
    JSONObject late = find(r, "p.late");
    check("metadata may come after versions", late != null && "Metadata After Versions".equals(late.optString("name")) && late.getJSONArray("cats").length() == 2);
    check("no \"null\" strings leaked", r.items.toString().indexOf("\"null\"") < 0);
    // x86 device flips the choice
    FdroidIndex.Result rx = FdroidIndex.parseV2(in(idx), "https://r.example/repo", new String[]{"x86_64", "x86"}, 34, null);
    check("x86 device gets the x86 build (code 20)", find(rx, "p.multi").optLong("vc") == 20);
    check("abis=null accepts any ABI (highest code 20)", find(FdroidIndex.parseV2(in(idx), "https://r.example/repo", null, 0, null), "p.multi").optLong("vc") == 20);
    check("sdk=0 disables the SDK filter (3.0 chosen)", find(FdroidIndex.parseV2(in(idx), "https://r.example/repo", null, 0, null), "p.sdk").optLong("vc") == 30);

    // ---------- v1 fallback ----------
    String v1 = "{\"repo\":{\"name\":\"Old Repo\"},\"requests\":{},\"apps\":["
      + "{\"packageName\":\"o.app\",\"name\":\"Old App\",\"summary\":\"Legacy\",\"icon\":\"o.app.5.png\",\"categories\":[\"Reading\"],\"lastUpdated\":1700000000000},"
      + "{\"packageName\":\"o.loc\",\"localized\":{\"de\":{\"name\":\"Lokal\",\"summary\":\"Kurz\"},\"en-US\":{\"name\":\"Localized\",\"summary\":\"Short\"}}},"
      + "{\"packageName\":\"o.orphan\",\"name\":\"No packages\"}],"
      + "\"packages\":{\"o.app\":[{\"apkName\":\"o.app_4.apk\",\"versionName\":\"0.4\",\"versionCode\":4,\"size\":400,\"hash\":\"h4\",\"hashType\":\"sha256\"},"
      + "{\"apkName\":\"o.app_5.apk\",\"versionName\":\"0.5\",\"versionCode\":5,\"size\":500,\"hash\":\"h5\",\"hashType\":\"sha256\",\"nativecode\":[\"arm64-v8a\"],\"minSdkVersion\":\"21\"},"
      + "{\"apkName\":\"o.app_6_x86.apk\",\"versionName\":\"0.6\",\"versionCode\":6,\"nativecode\":[\"x86\"]}],"
      + "\"o.loc\":[{\"apkName\":\"o.loc_1.apk\",\"versionName\":\"1\",\"versionCode\":1,\"hash\":\"md5only\",\"hashType\":\"md5\"}]}}";
    FdroidIndex.Result r1 = FdroidIndex.parseV1(in(v1), "https://old.example/repo", arm64, 34, null);
    JSONObject oa = find(r1, "o.app"), ol = find(r1, "o.loc");
    check("v1: format + repo name", "v1".equals(r1.format) && "Old Repo".equals(r1.repoName));
    check("v1: best compatible version (code 5, sha256 kept)", oa != null && oa.optLong("vc") == 5 && "h5".equals(oa.optString("sha256")) && oa.optString("apkUrl").equals("https://old.example/repo/o.app_5.apk"));
    check("v1: icon under /icons/, category, updated", oa.optString("icon").equals("https://old.example/repo/icons/o.app.5.png") && oa.getJSONArray("cats").getString(0).equals("Reading") && oa.optLong("updated") == 1700000000000L);
    check("v1: localized name/summary fallback; non-sha256 hash NOT trusted as sha256", ol != null && "Localized".equals(ol.optString("name")) && "Short".equals(ol.optString("desc")) && ol.optString("sha256").isEmpty());
    check("v1: app without packages skipped", find(r1, "o.orphan") == null && r1.skipped == 1);

    // ---------- bad input must fail fast, not hang ----------
    boolean threw = false;
    try { FdroidIndex.parseV2(in("{\"packages\":{\"a\":{\"metadata\":{\"name\":"), "https://x/repo", arm64, 34, null); } catch (Exception e) { threw = true; }
    check("truncated JSON throws", threw);
    threw = false;
    try { FdroidIndex.parseV2(in("[1,2,3]"), "https://x/repo", arm64, 34, null); } catch (Exception e) { threw = true; }
    check("non-object root throws", threw);

    // ---------- progress throttling ----------
    final int[] ticks = {0};
    FdroidIndex.Progress p = new FdroidIndex.Progress(new FdroidIndex.Sink() { public void onProgress(long b, int ap) { ticks[0]++; } });
    for (int i = 0; i < 1000; i++) p.tick(false);
    check("progress is throttled (<= 2 ticks for 1000 rapid calls)", ticks[0] <= 2);

    // ---------- the details for the detail sheet (v7.10.10): description, screenshots, links ----------
    final Map<String, JSONObject> got = new LinkedHashMap<String, JSONObject>();
    FdroidIndex.Progress dp = new FdroidIndex.Progress(null);
    dp.details = new FdroidIndex.Details() { public void put(String pkg, JSONObject d) { got.put(pkg, d); } };
    FdroidIndex.Result wd = FdroidIndex.parseV2(new FileInputStream(FIX + "wgtunnel-index-v2.json"), "https://example.org/repo", arm64, 34, dp);
    JSONObject wdet = got.get("com.zaneschepke.wireguardautotunnel");
    check("details(v2): an entry for every listed app", wd.items.length() == got.size() && wdet != null);
    check("details(v2): the long description (en-US) is kept", wdet.optString("d").startsWith("A WireGuard & AmneziaWG VPN client") && wdet.optString("d").contains("Features"));
    check("details(v2): web site, source code, license and author", "https://wgtunnel.com".equals(wdet.optString("web")) && "https://github.com/wgtunnel/android".equals(wdet.optString("src")) && "MIT".equals(wdet.optString("lic")) && "wgtunnel".equals(wdet.optString("by")));
    JSONArray shots = wdet.optJSONArray("shots");
    check("details(v2): phone screenshots as full addresses, at most 12", shots != null && shots.length() > 0 && shots.length() <= 12 && shots.getString(0).startsWith("https://example.org/repo/com.zaneschepke.wireguardautotunnel/en-US/phoneScreenshots/"));
    check("details(v2): the list items are the same with and without a details receiver", FdroidIndex.parseV2(new FileInputStream(FIX + "wgtunnel-index-v2.json"), "https://example.org/repo", arm64, 34, null).items.toString().equals(wd.items.toString()));

    final Map<String, JSONObject> got1 = new LinkedHashMap<String, JSONObject>();
    FdroidIndex.Progress dp1 = new FdroidIndex.Progress(null);
    dp1.details = new FdroidIndex.Details() { public void put(String pkg, JSONObject d) { got1.put(pkg, d); } };
    String v1d = "{\"repo\":{\"name\":\"Old Repo\"},\"apps\":[{\"packageName\":\"o.app\",\"name\":\"Old App\",\"summary\":\"Legacy\",\"description\":\"The old description\",\"webSite\":\"https://old.example\",\"sourceCode\":\"https://git.example/o\",\"license\":\"GPL-3.0\",\"authorName\":\"Olga\","
      + "\"localized\":{\"de\":{\"phoneScreenshots\":[\"de1.png\"]},\"en-US\":{\"description\":\"English text\",\"phoneScreenshots\":[\"a.png\",\"b.png\"]}}}],"
      + "\"packages\":{\"o.app\":[{\"apkName\":\"o.app_5.apk\",\"versionName\":\"0.5\",\"versionCode\":5,\"size\":500,\"hash\":\"h5\",\"hashType\":\"sha256\"}]}}";
    FdroidIndex.parseV1(in(v1d), "https://old.example/repo", arm64, 34, dp1);
    JSONObject od = got1.get("o.app");
    check("details(v1): description (the en-US one wins), links, license, author", od != null && "English text".equals(od.optString("d")) && "https://old.example".equals(od.optString("web")) && "https://git.example/o".equals(od.optString("src")) && "GPL-3.0".equals(od.optString("lic")) && "Olga".equals(od.optString("by")));
    check("details(v1): screenshots of the en-US folder, built from the app and file names", od.getJSONArray("shots").length() == 2 && "https://old.example/repo/o.app/en-US/phoneScreenshots/a.png".equals(od.getJSONArray("shots").getString(0)));

    // ---------- what an address is: the name of a repository, read from the start of its index ----------
    check("repoNameOf(v2): the localized name", "Test Repo".equals(FdroidIndex.repoNameOf(in(idx))));
    check("repoNameOf(v1): a plain name", "Old Repo".equals(FdroidIndex.repoNameOf(in(v1d))));
    check("repoNameOf: an index whose repo block comes last is still an index (empty name)", "".equals(FdroidIndex.repoNameOf(in("{\"packages\":{}}"))));
    check("repoNameOf: something that is not an index is null", FdroidIndex.repoNameOf(in("{\"hello\":1}")) == null);

    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
