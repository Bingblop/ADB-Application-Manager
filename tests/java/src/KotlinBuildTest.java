package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Settings, "Try the Kotlin build (beta)": which release and file are chosen from a GitHub releases list (the newest kotlin-* release, drafts and
 * pre-releases out, wrong file names out, hostile names and addresses out, https and this repository only), the checksum the release publishes,
 * the installed / not installed / no release / error answers of the card, the version comparison, and a source scan that the install is the app's
 * one existing install path (no new one, no package or address from the page).
 */
public class KotlinBuildTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static final String P = KotlinBuild.DOWNLOAD_URL_PREFIX;
  static final String H64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  // ---- builders for a fake releases list ----
  static JSONObject asset(String tag, String name) throws Exception {
    return new JSONObject().put("name", name).put("browser_download_url", P + tag + "/" + name).put("state", "uploaded").put("size", 12345678);
  }
  static JSONObject rel(String tag, JSONObject... assets) throws Exception {
    JSONArray a = new JSONArray();
    for (JSONObject x : assets) a.put(x);
    return new JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false).put("published_at", "2026-10-11T12:00:00Z").put("assets", a);
  }
  static JSONObject kotlin(String ver) throws Exception { String tag = "kotlin-" + ver; return rel(tag, asset(tag, "ADB-Manager-Kotlin-" + ver + ".apk")); }
  static String list(JSONObject... rels) { JSONArray a = new JSONArray(); for (JSONObject r : rels) a.put(r); return a.toString(); }
  static KotlinBuild.Choice pick(String json) { return KotlinBuild.choose(json, false); }
  static String ver(KotlinBuild.Choice c) { return c == null ? "null" : c.version; }
  static boolean bad(String json) { try { KotlinBuild.choose(json, false); return false; } catch (IllegalArgumentException e) { return true; } }

  /** A fake web: the body for each address, or an exception; every address asked is kept. */
  static class FakeHttp implements KotlinBuild.Http {
    final java.util.Map<String, byte[]> bodies = new java.util.HashMap<String, byte[]>();
    final java.util.Set<String> failing = new java.util.HashSet<String>();
    final List<String> asked = new ArrayList<String>();
    Exception releasesFail;
    FakeHttp releases(String json) { return page(1, json); }
    FakeHttp page(int n, String json) { bodies.put(KotlinBuild.pageUrl(n), json.getBytes(StandardCharsets.UTF_8)); return this; }
    FakeHttp body(String url, String text) { bodies.put(url, text.getBytes(StandardCharsets.UTF_8)); return this; }
    public byte[] get(String url, int maxBytes) throws Exception {
      asked.add(url);
      if (url.equals(KotlinBuild.pageUrl(1)) && releasesFail != null) throw releasesFail;
      if (failing.contains(url)) throw new IllegalStateException("HTTP 500");
      byte[] b = bodies.get(url);
      if (b == null) throw new IllegalStateException("not found (HTTP 404)");
      if (b.length > maxBytes) throw new IllegalStateException("response too large");
      return b;
    }
  }

  /** A fake package query: the version of what is installed (null = not installed); the package asked for is kept. */
  static class FakePackages implements KotlinBuild.Packages {
    final String version; final boolean boom; final List<String> asked = new ArrayList<String>();
    FakePackages(String version, boolean boom) { this.version = version; this.boom = boom; }
    public String installedVersion(String pkg) throws Exception { asked.add(pkg); if (boom) throw new SecurityException("no"); return version; }
  }

  public static void main(String[] args) throws Exception {
    configuration();
    choice();
    hostile();
    sizes();
    checksums();
    card();
    versions();
    pages();
    sources();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  static void configuration() {
    check("the package id is a valid Android package id and not this app's", KotlinBuild.KOTLIN_PACKAGE_ID.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") && !KotlinBuild.KOTLIN_PACKAGE_ID.equals("com.bloatware.bingblop"));
    check("the lookups and downloads are https and this repository", KotlinBuild.RELEASES_API_URL.startsWith("https://api.github.com/repos/Bingblop/ADB-Application-Manager/releases")
        && KotlinBuild.RELEASES_PAGE_URL.equals("https://github.com/Bingblop/ADB-Application-Manager/releases")
        && KotlinBuild.DOWNLOAD_URL_PREFIX.equals("https://github.com/Bingblop/ADB-Application-Manager/releases/download/"));
    check("the releases are read 100 to a page, at most 5 pages, as page=N of the same address", KotlinBuild.RELEASES_API_URL.endsWith("per_page=100") && KotlinBuild.PAGE_SIZE == 100 && KotlinBuild.MAX_PAGES == 5 && KotlinBuild.pageUrl(3).equals(KotlinBuild.RELEASES_API_URL + "&page=3") && KotlinBuild.pageUrl(1).startsWith("https://"));
    check("the tag prefix and the file pattern are set", KotlinBuild.TAG_PREFIX.equals("kotlin-") && KotlinBuild.ASSET_PATTERN.equals("ADB-Manager-Kotlin*.apk"));
    check("pre-releases are out unless the flag says otherwise", !KotlinBuild.ALLOW_PRERELEASE);
  }

  static void choice() throws Exception {
    check("no release at all: nothing", pick("[]") == null);
    check("one Kotlin release: it", ver(pick(list(kotlin("8.0.0")))).equals("8.0.0") && pick(list(kotlin("8.0.0"))).tag.equals("kotlin-8.0.0"));
    KotlinBuild.Choice c = pick(list(kotlin("8.0.0")));
    check("it carries the file name and the exact release address", c.apkName.equals("ADB-Manager-Kotlin-8.0.0.apk") && c.apkUrl.equals(P + "kotlin-8.0.0/ADB-Manager-Kotlin-8.0.0.apk") && c.apkUrl.startsWith("https://") && c.size == 12345678);
    check("the newest by number, whatever the order of the list", ver(pick(list(kotlin("8.0.0"), kotlin("8.1.0"), kotlin("7.9.9")))).equals("8.1.0") && ver(pick(list(kotlin("7.9.9"), kotlin("8.1.0"), kotlin("8.0.0")))).equals("8.1.0"));
    check("by number, not as text (8.0.10 is above 8.0.9, 10.0 above 9.9)", ver(pick(list(kotlin("8.0.9"), kotlin("8.0.10")))).equals("8.0.10") && ver(pick(list(kotlin("9.9"), kotlin("10.0")))).equals("10.0"));
    check("releases of the WebView app (v7.12.9 ...) are not Kotlin releases, even with a file of the right name", ver(pick(list(rel("v9.0.0", asset("v9.0.0", "ADB-Manager-Kotlin.apk")), kotlin("8.0.0")))).equals("8.0.0"));
    check("a tag of another case or prefix is not taken (Kotlin-1, kotlin1, xkotlin-1)", pick(list(rel("Kotlin-1.0", asset("Kotlin-1.0", "ADB-Manager-Kotlin.apk")), rel("kotlin1.0", asset("kotlin1.0", "ADB-Manager-Kotlin.apk")), rel("xkotlin-1.0", asset("xkotlin-1.0", "ADB-Manager-Kotlin.apk")))) == null);
    check("same number twice: the later publishing date", ver(pick(list(kotlin("8.0.0").put("published_at", "2026-10-01T00:00:00Z"), rel("kotlin-8.0.0-b", asset("kotlin-8.0.0-b", "ADB-Manager-Kotlin-b.apk")).put("published_at", "2026-10-09T00:00:00Z")))).equals("8.0.0-b"));
    check("same number and date: the first listed", ver(pick(list(kotlin("8.0.0"), rel("kotlin-8.0.0-b", asset("kotlin-8.0.0-b", "ADB-Manager-Kotlin-b.apk"))))).equals("8.0.0"));
    check("a newer release without the file does not hide an older one that has it", ver(pick(list(rel("kotlin-9.0.0"), kotlin("8.0.0")))).equals("8.0.0"));

    // drafts and pre-releases
    check("a draft is never taken", pick(list(kotlin("9.0.0").put("draft", true), kotlin("8.0.0"))).version.equals("8.0.0") && pick(list(kotlin("9.0.0").put("draft", true))) == null);
    check("a draft is not taken even when pre-releases are", KotlinBuild.choose(list(kotlin("9.0.0").put("draft", true)), true) == null);
    check("a pre-release is out by default", ver(pick(list(kotlin("9.0.0").put("prerelease", true), kotlin("8.0.0")))).equals("8.0.0") && pick(list(kotlin("9.0.0").put("prerelease", true))) == null);
    check("and in when the flag says so (still the newest)", ver(KotlinBuild.choose(list(kotlin("9.0.0").put("prerelease", true), kotlin("8.0.0")), true)).equals("9.0.0"));

    // file names
    String t = "kotlin-8.0.0";
    check("the plain name ADB-Manager-Kotlin.apk matches (the star can be empty)", pick(list(rel(t, asset(t, "ADB-Manager-Kotlin.apk")))) != null);
    String[] wrong = { "ADB_Application_Manager_Pro.apk", "ADB-Manager-Kotlin.apk.sig", "ADB-Manager-Kotlin.zip", "adb-manager-kotlin.apk", "ADB-Manager-KOTLIN.apk", "xADB-Manager-Kotlin.apk", "ADB-Manager-Kotli.apk", "app-release-unsigned.apk", "ADB-Manager-Kotlin", "ADB-Manager-Kotlin.apk.sha256", "ADB-Manager-Kotlin.APK", "ADB-Manager-Kotlin.apk.txt" };
    boolean none = true;
    for (String w : wrong) if (pick(list(rel(t, asset(t, w)))) != null) { none = false; System.out.println("  taken: " + w); }
    check("wrong file names are ignored", none);
    check("with several files only the right one is taken", pick(list(rel(t, asset(t, "notes.txt"), asset(t, "ADB-Manager-Kotlin-8.0.0.apk.sig"), asset(t, "ADB-Manager-Kotlin-8.0.0.apk"), asset(t, "SHA256SUMS.txt")))).apkName.equals("ADB-Manager-Kotlin-8.0.0.apk"));
    check("a release with no assets, or an assets field that is not a list, is skipped", pick(list(rel(t), new JSONObject().put("tag_name", t).put("assets", "x"), new JSONObject().put("tag_name", t))) == null);
    check("a file still uploading (state) is not taken", pick(list(rel(t, asset(t, "ADB-Manager-Kotlin.apk").put("state", "starter")))) == null && pick(list(rel(t, asset(t, "ADB-Manager-Kotlin.apk").put("state", "open")))) == null);

    // the glob
    check("glob: only the star is special", KotlinBuild.globMatch("ADB-Manager-Kotlin*.apk", "ADB-Manager-Kotlin-1.apk") && !KotlinBuild.globMatch("a?c", "abc") && KotlinBuild.globMatch("a?c", "a?c") && !KotlinBuild.globMatch("a.c", "abc")
        && KotlinBuild.globMatch("a*b*c", "aXXbYYc") && !KotlinBuild.globMatch("a*b*c", "aXXcYYb") && KotlinBuild.globMatch("*", "") && !KotlinBuild.globMatch("a*a", "a") && KotlinBuild.globMatch("a*a", "aa") && !KotlinBuild.globMatch(null, "a") && !KotlinBuild.globMatch("a", null));
  }

  static void hostile() throws Exception {
    String t = "kotlin-8.0.0";
    String f = "ADB-Manager-Kotlin.apk";
    String[] urls = {
      "http://github.com/Bingblop/ADB-Application-Manager/releases/download/" + t + "/" + f,                       // not https
      "https://evil.example/Bingblop/ADB-Application-Manager/releases/download/" + t + "/" + f,
      "https://github.com.evil.example/Bingblop/ADB-Application-Manager/releases/download/" + t + "/" + f,
      "https://github.com@evil.example/Bingblop/ADB-Application-Manager/releases/download/" + t + "/" + f,
      "https://github.com/Evil/ADB-Application-Manager/releases/download/" + t + "/" + f,                          // another repository
      "https://github.com/Bingblop/other/releases/download/" + t + "/" + f,
      "https://github.com/Bingblop/ADB-Application-Manager/releases/download/kotlin-9.9.9/" + f,                   // another release's file
      P + t + "/" + f + "?token=x",
      P + t + "/" + f + "#x",
      P + t + "/../" + f,
      P + t + "//" + f,
      P + t + "/" + f + "/",
      "javascript:alert(1)", "file:///sdcard/a.apk", "content://x/a.apk", "ftp://github.com/x.apk", "", " " + P + t + "/" + f,
    };
    boolean none = true;
    for (String u : urls) {
      JSONObject a = new JSONObject().put("name", f).put("browser_download_url", u).put("size", 100);
      KotlinBuild.Choice c = pick(list(rel(t, a)));
      if (c != null) { none = false; System.out.println("  taken: " + u); }
    }
    check("an address that is not https, this repository, this release and this file is ignored (" + urls.length + " tried)", none);

    JSONObject noUrl = new JSONObject().put("name", f);
    check("no address, or an address that is not text, is ignored", pick(list(rel(t, noUrl))) == null && pick(list(rel(t, new JSONObject().put("name", f).put("browser_download_url", 5)))) == null
        && pick(list(rel(t, new JSONObject().put("name", f).put("browser_download_url", JSONObject.NULL)))) == null && pick(list(rel(t, new JSONObject().put("name", f).put("browser_download_url", new JSONArray().put(P))))) == null);

    // names
    StringBuilder longName = new StringBuilder("ADB-Manager-Kotlin-");
    for (int i = 0; i < 200; i++) longName.append('a');
    longName.append(".apk");
    String[] names = {
      "ADB-Manager-Kotlin/../x.apk", "../ADB-Manager-Kotlin.apk", "ADB-Manager-Kotlin\\x.apk", "ADB-Manager-Kotlin x.apk", "ADB-Manager-Kotlin\nx.apk", "ADB-Manager-Kotlin\u0000.apk",
      "ADB-Manager-Kotlin‮.apk", "ADB-Manager-Kotlin​.apk", "ADB-Manager-Kotlin-é.apk", "ADB-Manager-Kotlin%2e.apk", "ADB-Manager-Kotlin;rm.apk", "ADB-Manager-Kotlin$(x).apk",
      "ADB-Manager-Kotlin'.apk", "ADB-Manager-Kotlin\".apk", "ADB-Manager-Kotlin&x.apk", "ADB-Manager-Kotlin?x.apk", "ADB-Manager-Kotlin..apk", longName.toString(),
    };
    none = true;
    for (String nm : names) {
      JSONObject a = new JSONObject().put("name", nm).put("browser_download_url", P + t + "/" + nm).put("size", 100);
      if (pick(list(rel(t, a))) != null) { none = false; System.out.println("  taken: " + nm.replace("\n", "\\n")); }
    }
    check("a file name with a path, a space, a control or direction character, a quote, a shell character or a length over 120 is ignored (" + names.length + " tried)", none);
    check("a name that is not text is ignored", pick(list(rel(t, new JSONObject().put("name", 7).put("browser_download_url", P + t + "/7")))) == null && pick(list(rel(t, new JSONObject().put("name", JSONObject.NULL).put("browser_download_url", P)))) == null);

    // tags
    String[] tags = { "kotlin-", "kotlin-a/b", "kotlin-a b", "kotlin-../x", "kotlin-1..2", "kotlin-1\n", "kotlin-‮1", "kotlin-1?x", "kotlin-1#x", "kotlin-%41", "kotlin-1;x", "kotlin-1\u0000", "kotlin-" + new String(new char[100]).replace('\0', '1') };
    none = true;
    for (String tg : tags) if (pick(list(rel(tg, asset(tg, f)))) != null) { none = false; System.out.println("  taken tag: " + tg.replace("\n", "\\n")); }
    check("a tag with a slash, a space, a control or direction character, a dot pair, or nothing after the prefix, or over 64 characters is ignored (" + tags.length + " tried)", none);
    check("a tag that is not text is ignored", pick(list(new JSONObject().put("tag_name", 5).put("assets", new JSONArray().put(asset(t, f))))) == null && pick(list(new JSONObject().put("tag_name", JSONObject.NULL).put("assets", new JSONArray().put(asset(t, f))))) == null
        && pick(list(new JSONObject().put("assets", new JSONArray().put(asset(t, f))))) == null && pick(list(new JSONObject().put("tag_name", new JSONArray().put(t)).put("assets", new JSONArray().put(asset(t, f))))) == null);
    check("entries that are not objects are skipped, the rest still read", ver(pick("[1,\"x\",null,true,[],{}," + kotlin("8.0.0").toString() + "]")).equals("8.0.0"));
    check("a hostile release does not hide a good one", ver(pick(list(rel(t, new JSONObject().put("name", f).put("browser_download_url", "http://evil.example/a.apk")), kotlin("7.0.0")))).equals("7.0.0"));
  }

  static void sizes() throws Exception {
    String t = "kotlin-8.0.0", f = "ADB-Manager-Kotlin.apk";
    check("a size of 0, a negative size or one over 600 MB is ignored", pick(list(rel(t, asset(t, f).put("size", 0)))) == null && pick(list(rel(t, asset(t, f).put("size", -5)))) == null && pick(list(rel(t, asset(t, f).put("size", 700L * 1024 * 1024)))) == null
        && pick(list(rel(t, asset(t, f).put("size", 50L * 1024 * 1024)))) != null);
    check("no size at all is allowed (the download is bounded by its own check)", pick(list(rel(t, new JSONObject().put("name", f).put("browser_download_url", P + t + "/" + f)))) != null);

    // malformed
    String[] junk = { null, "", " ", "{", "[", "[{", "}", "null", "true", "123", "\"x\"", "{}", "{\"message\":\"API rate limit exceeded\"}", "<html><body>rate limited</body></html>", "[1,2,", "[{\"tag_name\":\"kotlin-1\"", "\u0000\u0001", "{\"a\":[]}" };
    boolean allBad = true;
    for (String j : junk) if (!bad(j)) { allBad = false; System.out.println("  not refused: " + j); }
    check("an answer that is not a list of releases is refused in words, never a crash (" + junk.length + " tried)", allBad);
    check("a list the parser reads leniently ([,]) is simply a list with no release", !bad("[,]") && pick("[,]") == null);
    check("the same through check(): a release error with a message, not an exception", "error".equals(KotlinBuild.check(new FakeHttp().releases("<html>rate limited</html>"), new FakePackages(null, false), false).optString("release")));

    // huge
    StringBuilder big = new StringBuilder("[");
    for (int i = 0; i < 200000; i++) { if (i > 0) big.append(','); big.append("{\"tag_name\":\"v").append(i).append("\",\"assets\":[]}"); }
    big.append("]");
    check("the test list is over the size limit", big.length() > KotlinBuild.MAX_JSON_BYTES);
    check("a list over 4 MB is refused", bad(big.toString()));
    StringBuilder s3 = new StringBuilder("[\"");
    for (int i = 0; i < KotlinBuild.MAX_JSON_BYTES + 10; i++) s3.append('a');
    s3.append("\"]");
    check("a huge string is refused", bad(s3.toString()));
    final int depth = 200000;
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < depth; i++) deep.append('[');
    for (int i = 0; i < depth; i++) deep.append(']');
    long t0 = System.currentTimeMillis();
    check("a list nested 200,000 deep is refused (no stack overflow)", bad(deep.toString()));
    check("a list nested 100 deep is refused too (a releases list is 4 deep; the limit is 32)", bad(new String(new char[100]).replace("\0", "[") + new String(new char[100]).replace("\0", "]")) && !bad("[" + new String(new char[20]).replace("\0", "[") + new String(new char[20]).replace("\0", "]") + "]"));
    StringBuilder deepO = new StringBuilder();
    for (int i = 0; i < 100000; i++) deepO.append("{\"a\":");
    check("objects nested 100,000 deep are refused", bad("[" + deepO + "]"));
    check("and it is quick", System.currentTimeMillis() - t0 < 5000);
    check("brackets inside strings do not count as nesting", ver(pick("[{\"x\":\"" + new String(new char[500]).replace("\0", "[") + "\"}," + kotlin("8.0.0") + "]")).equals("8.0.0"));
    check("a modest nesting is fine", ver(pick(list(kotlin("8.0.0").put("extra", new JSONArray("[[[[[[[[1]]]]]]]]"))))).equals("8.0.0"));

    // only the first releases and files are looked at
    JSONObject[] many = new JSONObject[KotlinBuild.MAX_RELEASES + 5];
    for (int i = 0; i < many.length; i++) many[i] = rel("v" + i);
    many[KotlinBuild.MAX_RELEASES + 2] = kotlin("9.9.9");
    check("a Kotlin release after the first " + KotlinBuild.MAX_RELEASES + " is not looked at", pick(list(many)) == null);
    many[KotlinBuild.MAX_RELEASES - 1] = kotlin("1.0.0");
    check("one inside the first " + KotlinBuild.MAX_RELEASES + " is", ver(pick(list(many))).equals("1.0.0"));
    JSONObject[] files = new JSONObject[KotlinBuild.MAX_ASSETS + 5];
    for (int i = 0; i < files.length; i++) files[i] = asset(t, "file" + i + ".txt");
    files[KotlinBuild.MAX_ASSETS + 2] = asset(t, f);
    check("a file after the first " + KotlinBuild.MAX_ASSETS + " is not looked at", pick(list(rel(t, files))) == null);
    files[KotlinBuild.MAX_ASSETS - 1] = asset(t, f);
    check("one inside the first " + KotlinBuild.MAX_ASSETS + " is", pick(list(rel(t, files))) != null);
  }

  static void checksums() throws Exception {
    String t = "kotlin-8.0.0", f = "ADB-Manager-Kotlin-8.0.0.apk";
    // 1. the digest of the file (GitHub publishes sha256:<hex>)
    KotlinBuild.Choice c = pick(list(rel(t, asset(t, f).put("digest", "sha256:" + H64))));
    check("the digest GitHub publishes for the file is kept as published", c.sha256.equals("sha256:" + H64) && InstallGuards.checkHash(c.sha256, H64) == null);
    check("and a download with another hash is refused by the existing check", InstallGuards.checkHash(c.sha256, H64.replace('0', '1')) != null);
    KotlinBuild.Choice m = pick(list(rel(t, asset(t, f).put("digest", "md5:abc"))));
    check("a published digest that is not a SHA-256 stays visible, so the install check refuses it (fail closed)", m != null && m.sha256.equals("md5:abc") && InstallGuards.checkHash(m.sha256, H64) != null);
    check("a digest that is not text is not a checksum", pick(list(rel(t, asset(t, f).put("digest", 5)))).sha256.isEmpty() && pick(list(rel(t, asset(t, f).put("digest", JSONObject.NULL)))).sha256.isEmpty());
    check("no digest and no checksum file: nothing published", pick(list(rel(t, asset(t, f)))).sha256.isEmpty() && pick(list(rel(t, asset(t, f)))).sumsUrl.isEmpty());

    // 2. SHA256SUMS.txt (what release.yml writes: sha256sum *.apk)
    JSONObject withSums = rel(t, asset(t, f), asset(t, "SHA256SUMS.txt"));
    String sumsUrl = P + t + "/SHA256SUMS.txt";
    check("a SHA256SUMS.txt in the release is noted, at this release's address", pick(list(withSums)).sumsUrl.equals(sumsUrl));
    FakeHttp h = new FakeHttp().releases(list(withSums)).body(sumsUrl, H64 + "  " + f + "\n" + H64.replace('0', '2') + "  other.apk\n");
    check("find() reads the hash of the file from it", KotlinBuild.find(h, false).sha256.equals(H64) && h.asked.contains(sumsUrl));
    h = new FakeHttp().releases(list(withSums)).body(sumsUrl, H64.replace('0', '2') + " *other.apk\n" + H64.toUpperCase() + " *" + f + "\r\n");
    check("a binary-mode line ('*name'), CRLF and upper case are read (and given in lower case)", KotlinBuild.find(h, false).sha256.equals(H64));
    h = new FakeHttp().releases(list(withSums)).body(sumsUrl, H64 + "  other.apk\n");
    check("a sums file that does not list the file stops the lookup (not another file's hash, and not 'no checksum')", noValid(h));
    h = new FakeHttp().releases(list(withSums)).body(sumsUrl, H64 + "  " + f + ".sig\n" + H64 + "  x" + f + "\n");
    check("a name that only looks like it is not it: the lookup stops", noValid(h));
    h = new FakeHttp().releases(list(withSums));
    h.failing.add(sumsUrl);
    String why = "";
    try { KotlinBuild.find(h, false); } catch (Exception e) { why = String.valueOf(e.getMessage()); }
    check("a checksum file that cannot be read stops the lookup in words (fail closed, not 'no checksum')", why.contains("SHA256SUMS.txt") && why.contains("cannot be verified"));
    h = new FakeHttp().releases(list(withSums)).body(sumsUrl, "not a checksum file at all\n\u0000\u0001");
    check("a sums file of junk stops the lookup", noValid(h));
    h = new FakeHttp().releases(list(withSums)).body(sumsUrl, "");
    check("an empty sums file stops the lookup", noValid(h));
    h = new FakeHttp().releases(list(withSums)).body(sumsUrl, "\n\n  \n");
    check("a sums file of blank lines stops the lookup", noValid(h));
    h = new FakeHttp().releases(list(withSums)).body(sumsUrl, H64.substring(1) + "  " + f + "\n");
    check("a 63-digit line is no checksum: the lookup stops", noValid(h));
    h = new FakeHttp().releases(list(withSums)).body(sumsUrl, H64.replace('0', 'g') + "  " + f + "\n");
    check("a non-hex line for the file stops the lookup", noValid(h));
    String mess = "";
    try { KotlinBuild.find(new FakeHttp().releases(list(withSums)).body(sumsUrl, ""), false); } catch (Exception e) { mess = String.valueOf(e.getMessage()); }
    check("and says why, in words", mess.contains("SHA256SUMS.txt") && mess.contains("no valid SHA-256 for " + f) && mess.contains("cannot be verified"));
    byte[] bigSums = new byte[KotlinBuild.MAX_SUMS_BYTES + 1];
    java.util.Arrays.fill(bigSums, (byte) 'a');
    h = new FakeHttp().releases(list(withSums));
    h.bodies.put(sumsUrl, bigSums);
    why = "";
    try { KotlinBuild.find(h, false); } catch (Exception e) { why = String.valueOf(e.getMessage()); }
    check("a sums file over 64 KB is not read (it stops the lookup)", why.contains("cannot be verified"));
    check("the digest wins: the sums file is not even fetched", fetched(list(rel(t, asset(t, f).put("digest", "sha256:" + H64), asset(t, "SHA256SUMS.txt")))).isEmpty());

    // 3. <file>.sha256
    JSONObject withSide = rel(t, asset(t, f), asset(t, f + ".sha256"), asset(t, "SHA256SUMS.txt"));
    String sideUrl = P + t + "/" + f + ".sha256";
    check("the file's own .sha256 comes before the list", pick(list(withSide)).sumsUrl.equals(sideUrl));
    h = new FakeHttp().releases(list(withSide)).body(sideUrl, H64 + "\n");
    check("a lone hash in the file's own .sha256 is its hash", KotlinBuild.find(h, false).sha256.equals(H64));
    h = new FakeHttp().releases(list(withSide)).body(sideUrl, H64 + "  " + f + "\n");
    check("or 'hash  name'", KotlinBuild.find(h, false).sha256.equals(H64));
    h = new FakeHttp().releases(list(withSide)).body(sideUrl, H64 + "  another.apk\n");
    check("but not a line for another file: the lookup stops", noValid(h));
    h = new FakeHttp().releases(list(withSide)).body(sideUrl, "");
    check("an empty .sha256 stops the lookup", noValid(h));
    h = new FakeHttp().releases(list(withSide)).body(sideUrl, "oops\n");
    check("a .sha256 with junk stops the lookup", noValid(h));
    check("no checksum asset and no digest at all stays as it is: found, nothing published, no second request", fetched(list(rel(t, asset(t, f)))).isEmpty() && KotlinBuild.find(new FakeHttp().releases(list(rel(t, asset(t, f)))), false).sha256.isEmpty());
    check("the card is an error, not an installable build, when the checksum file has no valid hash", "error".equals(KotlinBuild.check(new FakeHttp().releases(list(withSums)).body(sumsUrl, "junk"), new FakePackages(null, false), false).optString("release")));
    check("a .sha256 of another file is not used", pick(list(rel(t, asset(t, f), asset(t, "other.apk.sha256")))).sumsUrl.isEmpty());

    // 4. the lookup asks nothing else
    FakeHttp q = new FakeHttp().releases(list(withSums)).body(sumsUrl, H64 + "  " + f + "\n");
    KotlinBuild.find(q, false);
    boolean ok = true;
    for (String u : q.asked) if (!u.startsWith("https://")) ok = false;
    check("find() asks only the first page of releases and, when needed, the checksum file (all https)", ok && q.asked.size() == 2 && q.asked.get(0).equals(KotlinBuild.pageUrl(1)) && q.asked.get(1).equals(sumsUrl));
    check("find() with no release is null", KotlinBuild.find(new FakeHttp().releases("[]"), false) == null);
    check("find() passes the pre-release flag on", KotlinBuild.find(new FakeHttp().releases(list(kotlin("9.0.0").put("prerelease", true))), false) == null && KotlinBuild.find(new FakeHttp().releases(list(kotlin("9.0.0").put("prerelease", true))), true) != null);
    check("find() with an unreadable list throws", throwsIn(new FakeHttp().releases("<html>")));
    check("find() when the web fails throws the web's error", throwsIn(new FakeHttp()));
  }

  static List<String> fetched(String json) throws Exception {
    FakeHttp h = new FakeHttp().releases(json);
    KotlinBuild.find(h, false);
    List<String> others = new ArrayList<String>(h.asked);
    others.remove(KotlinBuild.pageUrl(1));
    return others;
  }
  static boolean noValid(FakeHttp h) { try { KotlinBuild.find(h, false); return false; } catch (Exception e) { return String.valueOf(e.getMessage()).contains("cannot be verified"); } }
  static boolean throwsIn(FakeHttp h) { try { KotlinBuild.find(h, false); return false; } catch (Exception e) { return true; } }

  /** A full page of 100 releases of the WebView app (none a Kotlin build). */
  static String fullPage(int n) throws Exception {
    JSONObject[] a = new JSONObject[KotlinBuild.PAGE_SIZE];
    for (int i = 0; i < a.length; i++) a[i] = rel("v7." + n + "." + i);
    return list(a);
  }
  /** A full page that holds one Kotlin build among 99 other releases. */
  static String fullPageWith(JSONObject k) throws Exception {
    JSONObject[] a = new JSONObject[KotlinBuild.PAGE_SIZE];
    for (int i = 0; i < a.length; i++) a[i] = i == 40 ? k : rel("v6." + i);
    return list(a);
  }

  static void pages() throws Exception {
    // a Kotlin build only on page 2 or 3: the project has more than 30 releases
    FakeHttp h = new FakeHttp().page(1, fullPage(1)).page(2, fullPageWith(kotlin("8.0.0")));
    KotlinBuild.Choice c = KotlinBuild.find(h, false);
    check("a Kotlin build on page 2 is found", c != null && c.version.equals("8.0.0") && h.asked.size() == 2 && h.asked.get(0).equals(KotlinBuild.pageUrl(1)) && h.asked.get(1).equals(KotlinBuild.pageUrl(2)));
    h = new FakeHttp().page(1, fullPage(1)).page(2, fullPage(2)).page(3, list(kotlin("8.1.0"), rel("v5.0")));
    c = KotlinBuild.find(h, false);
    check("on page 3 (a short page) too, and nothing more is asked", c != null && c.version.equals("8.1.0") && h.asked.size() == 3 && !h.asked.contains(KotlinBuild.pageUrl(4)));
    check("the card finds it as well", KotlinBuild.check(new FakeHttp().page(1, fullPage(1)).page(2, fullPageWith(kotlin("8.0.0"))), new FakePackages(null, false), false).optString("version").equals("8.0.0"));
    // stops
    h = new FakeHttp().page(1, fullPageWith(kotlin("8.0.0"))).page(2, fullPageWith(kotlin("9.0.0")));
    c = KotlinBuild.find(h, false);
    check("a full page that holds a Kotlin build ends the search: older pages cannot be newer, page 2 is not asked", c.version.equals("8.0.0") && h.asked.size() == 1);
    h = new FakeHttp().page(1, list(rel("v7.1"), rel("v7.0")));
    check("a short first page is the last: no Kotlin build, no second request", KotlinBuild.find(h, false) == null && h.asked.size() == 1);
    h = new FakeHttp().page(1, "[]");
    check("an empty page is the last", KotlinBuild.find(h, false) == null && h.asked.size() == 1);
    // the bound
    h = new FakeHttp();
    for (int p = 1; p <= KotlinBuild.MAX_PAGES + 2; p++) h.page(p, p == KotlinBuild.MAX_PAGES + 1 ? fullPageWith(kotlin("8.0.0")) : fullPage(p));
    check("at most " + KotlinBuild.MAX_PAGES + " pages are read: a Kotlin build on page " + (KotlinBuild.MAX_PAGES + 1) + " is not looked for", KotlinBuild.find(h, false) == null && h.asked.size() == KotlinBuild.MAX_PAGES);
    h = new FakeHttp();
    for (int p = 1; p <= KotlinBuild.MAX_PAGES; p++) h.page(p, p == KotlinBuild.MAX_PAGES ? fullPageWith(kotlin("8.0.0")) : fullPage(p));
    check("one on the last page read is found", KotlinBuild.find(h, false) != null && h.asked.size() == KotlinBuild.MAX_PAGES);
    // failures on a later page stop the lookup, they are not 'none published'
    h = new FakeHttp().page(1, fullPage(1)).page(2, fullPageWith(kotlin("8.0.0")));
    h.failing.add(KotlinBuild.pageUrl(2));
    check("a page that cannot be read stops the lookup", throwsIn(h));
    check("and the card says error, not 'none published'", "error".equals(KotlinBuild.check(h, new FakePackages(null, false), false).optString("release")));
    check("a later page that is not a list stops the lookup", throwsIn(new FakeHttp().page(1, fullPage(1)).page(2, "<html>rate limited</html>")));
    check("a later page over the size limit stops the lookup", throwsIn(new FakeHttp().page(1, fullPage(1)).page(2, "[\"" + new String(new char[KotlinBuild.MAX_JSON_BYTES + 10]).replace('\0', 'a') + "\"]")));
    check("every page is asked over https, from this address only", allHttps(new FakeHttp().page(1, fullPage(1)).page(2, fullPage(2)).page(3, "[]")));
    // the rules for the files hold on every page
    JSONObject hostile = rel("kotlin-9.0.0", new JSONObject().put("name", "ADB-Manager-Kotlin.apk").put("browser_download_url", "http://evil.example/a.apk").put("size", 5));
    check("a hostile release on page 2 is ignored like on page 1", KotlinBuild.find(new FakeHttp().page(1, fullPage(1)).page(2, fullPageWith(hostile)).page(3, "[]"), false) == null);
    check("a draft or pre-release on page 2 is ignored like on page 1", KotlinBuild.find(new FakeHttp().page(1, fullPage(1)).page(2, fullPageWith(kotlin("9.0.0").put("draft", true))).page(3, "[]"), false) == null
        && KotlinBuild.find(new FakeHttp().page(1, fullPage(1)).page(2, fullPageWith(kotlin("9.0.0").put("prerelease", true))).page(3, "[]"), false) == null);
    // the digest and the checksum file come from the release found on a later page
    JSONObject withSums = rel("kotlin-8.0.0", asset("kotlin-8.0.0", "ADB-Manager-Kotlin.apk"), asset("kotlin-8.0.0", "SHA256SUMS.txt"));
    String sumsUrl = P + "kotlin-8.0.0/SHA256SUMS.txt";
    h = new FakeHttp().page(1, fullPage(1)).page(2, fullPageWith(withSums)).body(sumsUrl, H64 + "  ADB-Manager-Kotlin.apk\n");
    check("the checksum file of a release found on page 2 is read", KotlinBuild.find(h, false).sha256.equals(H64));
  }
  static boolean allHttps(FakeHttp h) throws Exception {
    KotlinBuild.find(h, false);
    for (String u : h.asked) if (!u.startsWith("https://api.github.com/repos/Bingblop/ADB-Application-Manager/releases?per_page=100&page=")) return false;
    return !h.asked.isEmpty();
  }

  static void card() throws Exception {
    String list8 = list(kotlin("8.0.0"));
    // not installed
    JSONObject o = KotlinBuild.check(new FakeHttp().releases(list8), new FakePackages(null, false), false);
    check("not installed, a release: installed false, release ok, its version and size, no update", !o.getBoolean("installed") && o.getString("release").equals("ok") && o.getString("version").equals("8.0.0") && o.getString("tag").equals("kotlin-8.0.0") && o.getLong("size") == 12345678 && !o.getBoolean("update") && o.getString("installedVersion").isEmpty());
    check("the card always has the package id and the releases page", o.getString("package").equals(KotlinBuild.KOTLIN_PACKAGE_ID) && o.getString("page").equals(KotlinBuild.RELEASES_PAGE_URL));
    // no release
    o = KotlinBuild.check(new FakeHttp().releases("[]"), new FakePackages(null, false), false);
    check("not installed, no release: release none (the page says none has been published) with the releases page", !o.getBoolean("installed") && o.getString("release").equals("none") && !o.has("version") && o.getString("page").equals(KotlinBuild.RELEASES_PAGE_URL));
    o = KotlinBuild.check(new FakeHttp().releases(list(rel("v7.12.9", asset("v7.12.9", "ADB_Application_Manager_Pro.apk")))), new FakePackages(null, false), false);
    check("only WebView app releases: none", o.getString("release").equals("none"));
    // error
    FakeHttp down = new FakeHttp();
    down.releasesFail = new java.io.IOException("Unable to resolve host \"api.github.com\"");
    o = KotlinBuild.check(down, new FakePackages(null, false), false);
    check("the web is down: release error with the message", !o.getBoolean("installed") && o.getString("release").equals("error") && o.getString("error").contains("Unable to resolve host"));
    down.releasesFail = new IllegalStateException("HTTP 403");
    check("rate limited: release error, HTTP 403", KotlinBuild.check(down, new FakePackages(null, false), false).getString("error").equals("HTTP 403"));
    down.releasesFail = new IllegalStateException("bad\nline‮​" + new String(new char[400]).replace('\0', 'x'));
    String em = KotlinBuild.check(down, new FakePackages(null, false), false).getString("error");
    check("an error message is one short line without control or direction characters", em.length() <= 160 && !em.contains("\n") && !em.contains("‮") && !em.contains("​"));
    down.releasesFail = new RuntimeException((String) null);
    check("an error with no message still says something", !KotlinBuild.check(down, new FakePackages(null, false), false).getString("error").isEmpty());

    // installed
    FakePackages pk = new FakePackages("8.0.0-Pro", false);
    o = KotlinBuild.check(new FakeHttp().releases(list8), pk, false);
    check("installed, same version as the release: installed with its version, no update", o.getBoolean("installed") && o.getString("installedVersion").equals("8.0.0-Pro") && o.getString("release").equals("ok") && !o.getBoolean("update"));
    check("the package asked about is the Kotlin app's, once", pk.asked.size() == 1 && pk.asked.get(0).equals(KotlinBuild.KOTLIN_PACKAGE_ID));
    o = KotlinBuild.check(new FakeHttp().releases(list(kotlin("8.0.1"))), new FakePackages("8.0.0-Pro", false), false);
    check("installed, a newer release: update", o.getBoolean("installed") && o.getBoolean("update") && o.getString("version").equals("8.0.1"));
    o = KotlinBuild.check(new FakeHttp().releases(list(kotlin("8.0.0"))), new FakePackages("8.0.9", false), false);
    check("installed, an older release: no update", o.getBoolean("installed") && !o.getBoolean("update"));
    o = KotlinBuild.check(new FakeHttp().releases(list(kotlin("8.0.10"))), new FakePackages("8.0.9", false), false);
    check("8.0.10 is an update to 8.0.9", o.getBoolean("update"));
    o = KotlinBuild.check(new FakeHttp().releases(list8), new FakePackages("", false), false);
    check("installed with no version name: installed, and no claim of an update", o.getBoolean("installed") && o.getString("installedVersion").isEmpty() && !o.getBoolean("update"));
    o = KotlinBuild.check(new FakeHttp().releases(list8), new FakePackages("beta", false), false);
    check("installed with a version that has no number: no claim of an update", o.getBoolean("installed") && !o.getBoolean("update"));
    o = KotlinBuild.check(new FakeHttp().releases("[]"), new FakePackages("8.0.0", false), false);
    check("installed, no release: installed, release none", o.getBoolean("installed") && o.getString("release").equals("none"));
    o = KotlinBuild.check(down, new FakePackages("8.0.0", false), false);
    check("installed, the web is down: still installed (the Open button stays), release error", o.getBoolean("installed") && o.getString("installedVersion").equals("8.0.0") && o.getString("release").equals("error"));
    o = KotlinBuild.check(new FakeHttp().releases(list8), new FakePackages(null, true), false);
    check("a package query that fails reads as not installed", !o.getBoolean("installed") && o.getString("release").equals("ok"));
    o = KotlinBuild.check(new FakeHttp().releases(list(kotlin("9.0.0").put("prerelease", true))), new FakePackages(null, false), false);
    check("the pre-release flag reaches the card: out", o.getString("release").equals("none"));
    o = KotlinBuild.check(new FakeHttp().releases(list(kotlin("9.0.0").put("prerelease", true))), new FakePackages(null, false), true);
    check("and in", o.getString("release").equals("ok") && o.getString("version").equals("9.0.0"));
    FakeHttp sumsDown = new FakeHttp().releases(list(rel("kotlin-8.0.0", asset("kotlin-8.0.0", "ADB-Manager-Kotlin.apk"), asset("kotlin-8.0.0", "SHA256SUMS.txt"))));
    check("an unreadable checksum file makes the card an error, not an installable build", KotlinBuild.check(sumsDown, new FakePackages(null, false), false).getString("release").equals("error"));
  }

  static void versions() {
    check("compare: numbers, not text", KotlinBuild.compareVersions("8.0.10", "8.0.9") > 0 && KotlinBuild.compareVersions("8.0.9", "8.0.10") < 0 && KotlinBuild.compareVersions("10", "9") > 0);
    check("compare: a missing part is 0", KotlinBuild.compareVersions("8.0", "8.0.0") == 0 && KotlinBuild.compareVersions("8", "8.0.0.0") == 0 && KotlinBuild.compareVersions("8.0.1", "8") > 0);
    check("compare: text around the numbers is ignored", KotlinBuild.compareVersions("8.0.0-Pro", "8.0.0") == 0 && KotlinBuild.compareVersions("v8.1", "kotlin-8.0.9") > 0 && KotlinBuild.compareVersions("8.0.0-beta2", "8.0.0") == 0);
    check("compare: no number is below any number", KotlinBuild.compareVersions("beta", "0.0.1") < 0 && KotlinBuild.compareVersions("beta", "alpha") == 0 && KotlinBuild.compareVersions(null, "1") < 0 && KotlinBuild.compareVersions(null, null) == 0);
    check("compare: huge numbers do not overflow or throw", KotlinBuild.compareVersions("99999999999999999999999.1", "1") > 0 && KotlinBuild.compareVersions("1." + new String(new char[5000]).replace('\0', '9'), "1.1") > 0);
    check("compare: a very long dotted version is cut, not a problem", KotlinBuild.compareVersions(new String(new char[3000]).replace("\0", "1."), "1.1.1") > 0);
    check("hasNumber", KotlinBuild.hasNumber("8.0") && KotlinBuild.hasNumber("v1") && !KotlinBuild.hasNumber("") && !KotlinBuild.hasNumber("beta") && !KotlinBuild.hasNumber(null));
    check("safeToken", KotlinBuild.safeToken("a-b_c.1", 10) && !KotlinBuild.safeToken("", 10) && !KotlinBuild.safeToken("a b", 10) && !KotlinBuild.safeToken("abcdefghijk", 10) && !KotlinBuild.safeToken(".x", 10) && !KotlinBuild.safeToken("a..b", 10) && !KotlinBuild.safeToken(null, 10));
  }

  // ---- the source scans: the install is the app's own path, and nothing comes from the page ----
  static String read(String rel) throws Exception {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, rel);
      if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
    return null;
  }
  static String between(String s, String from, String to) {
    int a = s.indexOf(from);
    if (a < 0) return "";
    int b = s.indexOf(to, a + from.length());
    return b < 0 ? s.substring(a) : s.substring(a, b);
  }

  static void sources() throws Exception {
    String main = read("src/com/bloatware/bingblop/MainActivity.java");
    String kb = read("src/com/bloatware/bingblop/KotlinBuild.java");
    String page = read("assets/index.html");
    if (main == null || kb == null || page == null) { System.out.println("SKIP sources not found"); return; }

    String install = between(main, "private void runKotlinBuildInstall()", "private void downloadAndInstall(");
    install = between(install, "private void runKotlinBuildInstall()", "/**\n     * Downloads an https APK");
    check("the install method was found", install.contains("downloadAndInstall("));
    check("it hands the file to the existing download-and-install path, as the Kotlin package id with the release's checksum", Pattern.compile("downloadAndInstall\\(\\s*c\\.apkUrl,\\s*KotlinBuild\\.KOTLIN_PACKAGE_ID,[^;]*c\\.sha256\\)").matcher(install).find());
    for (String banned : new String[] { "installApk(", "launchSystemInstaller(", "getPackageInstaller", "pm install", "UpdateManager.download(", "HttpURLConnection", "openConnection", "confirmPrivilegedInstall", "Runtime.getRuntime", "new File(" }) {
      check("the Kotlin install adds no path of its own: no " + banned, !install.contains(banned));
    }
    String dai = between(main, "private void downloadAndInstall(", "private boolean confirmPrivilegedInstall(");
    check("downloadAndInstall still downloads with the hardened client, checks the hash and the package, and asks before a privileged install", dai.contains("UpdateManager.download(apkUrl") && dai.contains("InstallGuards.checkHash(") && dai.contains("!pkg.equals(archive.packageName)")
        && dai.contains("confirmPrivilegedInstall(InstallConfirm.message(") && dai.contains("launchSystemInstaller(apk)"));
    check("the confirmation comes before the privileged install in it", dai.indexOf("confirmPrivilegedInstall(") > 0 && dai.indexOf("confirmPrivilegedInstall(") < dai.indexOf("installApk(apk)"));

    // the bridge
    Matcher m = Pattern.compile("public (void|boolean) (kotlinBuild\\w+)\\(([^)]*)\\)").matcher(main);
    List<String> names = new ArrayList<String>();
    boolean noArgs = true;
    while (m.find()) { names.add(m.group(2)); if (!m.group(3).trim().isEmpty()) noArgs = false; }
    check("the page can call exactly four things: check, open, install, releases page", names.size() == 4 && names.contains("kotlinBuildCheck") && names.contains("kotlinBuildOpen") && names.contains("kotlinBuildInstall") && names.contains("kotlinBuildReleases"));
    check("none of them takes a package, an address or a checksum from the page", noArgs);
    for (String nm : names) {
      check(nm + " is a @JavascriptInterface method", main.lastIndexOf("@JavascriptInterface", main.indexOf(nm + "(")) > main.indexOf(nm + "(") - 80);
    }
    String open = between(main, "public boolean kotlinBuildOpen()", "public void kotlinBuildInstall()");
    check("opening uses the launch intent of the Kotlin package id", open.contains("getLaunchIntentForPackage(KotlinBuild.KOTLIN_PACKAGE_ID)"));
    String rels = between(main, "public void kotlinBuildReleases()", "/**");
    check("the releases page is opened from the constant", rels.contains("KotlinBuild.RELEASES_PAGE_URL") && rels.contains("ACTION_VIEW"));
    String chk = between(main, "private void runKotlinBuildCheck()", "private void runKotlinBuildInstall()");
    check("the check runs off the UI thread and answers window.onKotlinBuild", chk.contains("executor.submit(") && chk.contains("\"onKotlinBuild\"") && chk.contains("KotlinBuild.check(KOTLIN_HTTP, kotlinPackages,"));
    check("the web request goes through UpdateManager.httpGet (HttpSafe.open)", main.contains("UpdateManager.httpGet(url, \"application/vnd.github+json\", maxBytes)"));
    check("the package query is getPackageInfo (the app's existing package query)", Pattern.compile("kotlinPackages = new KotlinBuild\\.Packages\\(\\)[\\s\\S]{0,400}getPackageManager\\(\\)\\.getPackageInfo\\(pkg, 0\\)").matcher(main).find());

    // the class itself
    check("KotlinBuild has no Android classes and no raw connection", !kb.contains("import android.") && !kb.contains("HttpURLConnection") && !kb.contains("openConnection") && !kb.contains("new URL("));
    check("every address in it is https", !Pattern.compile("\"http://").matcher(kb).find());
    int cfgEnd = kb.indexOf("// ---- Limits");
    String cfg = cfgEnd > 0 ? kb.substring(0, cfgEnd) : "";
    check("the package id, the releases URLs, the tag prefix and the file pattern are all in the one block at the top", cfg.contains("KOTLIN_PACKAGE_ID = \"") && cfg.contains("RELEASES_API_URL") && cfg.contains("RELEASES_PAGE_URL") && cfg.contains("TAG_PREFIX = \"kotlin-\"") && cfg.contains("ASSET_PATTERN = \"ADB-Manager-Kotlin*.apk\""));
    // the package id is written once in src and never in the page
    int count = 0;
    String[] others = { "src/com/bloatware/bingblop/MainActivity.java", "src/com/bloatware/bingblop/UpdateManager.java" };
    for (String o : others) { String t = read(o); if (t != null && t.contains(KotlinBuild.KOTLIN_PACKAGE_ID)) count++; }
    check("the package id is not written anywhere else in the app's Java", count == 0);
    check("nor in the page (the app tells the page, the page never names it)", !page.contains(KotlinBuild.KOTLIN_PACKAGE_ID));

    // the page's calls
    String js = between(page, "// ---- Settings: Try the Kotlin build (beta)", "document.addEventListener('visibilitychange'");
    check("the page script was found", js.contains("function kbInstall()"));
    check("the page calls the four bridge methods with no argument", js.contains("b.kotlinBuildCheck()") && js.contains("b.kotlinBuildOpen()") && js.contains("b.kotlinBuildInstall()") && js.contains("b.kotlinBuildReleases()")
        && !Pattern.compile("kotlinBuild(Check|Open|Install|Releases)\\([^)]").matcher(js).find());
    check("the page does not use storeInstall or open an address of its own for this card", !js.contains("storeInstall(") && !js.contains("openUrl") && !js.contains("https://"));
  }
}
