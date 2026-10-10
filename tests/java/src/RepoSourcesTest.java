package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** F-Droid and IzzyOnDroid as download sources: the version list, "latest" and "requested", a package the site does not list, hostile input, against a fake repository. */
public class RepoSourcesTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  interface Thrower { void run() throws Exception; }
  static String err(Thrower t) { try { t.run(); return ""; } catch (Exception e) { return String.valueOf(e.getMessage()); } }

  public static void main(String[] args) {
    try { run(); } catch (Throwable t) { t.printStackTrace(); System.out.println("FAIL unexpected " + t); System.exit(1); }
  }

  static void run() throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    com.sun.net.httpserver.HttpHandler app = ex -> {
      byte[] b = "{\"packageName\":\"com.example.app\",\"suggestedVersionCode\":12,\"packages\":[{\"versionName\":\"1.1\",\"versionCode\":11},{\"versionName\":\"1.2\",\"versionCode\":12},{\"versionName\":\"1.0\",\"versionCode\":10}]}".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
    };
    s.createContext("/api/v1/packages/com.example.app", app);
    s.createContext("/fdroid/api/v1/packages/com.example.app", app);
    s.createContext("/api/v1/packages/com.example.sugg", ex -> {
      byte[] b = "{\"packageName\":\"com.example.sugg\",\"suggestedVersionCode\":11,\"packages\":[{\"versionName\":\"2.0-rc1\",\"versionCode\":12},{\"versionName\":\"1.9\",\"versionCode\":11}]}".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
    });
    s.createContext("/api/v1/packages/com.example.empty", ex -> {
      byte[] b = "{\"packageName\":\"com.example.empty\",\"packages\":[]}".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
    });
    s.createContext("/", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
    s.start();
    String base = "http://127.0.0.1:" + s.getAddress().getPort();
    RepoSources.baseOverride.put("fdroid", base);
    RepoSources.baseOverride.put("izzy", base + "/fdroid");

    check("the two sources are known, others are not", RepoSources.handles("fdroid") && RepoSources.handles("izzy") && !RepoSources.handles("apkmirror") && !RepoSources.handles(null));
    check("the page of a package on each site", RepoSources.pageUrl("fdroid", "a.b").equals("https://f-droid.org/packages/a.b/") && RepoSources.pageUrl("izzy", "a.b").equals("https://apt.izzysoft.de/fdroid/index/apk/a.b"));
    JSONObject v = RepoSources.versions("fdroid", "com.example.app");
    check("versions: newest first, each with the file address <package>_<code>.apk", v.getBoolean("ok") && v.getJSONArray("versions").length() == 3
        && v.getJSONArray("versions").getJSONObject(0).getString("version").equals("1.2") && v.getJSONArray("versions").getJSONObject(0).getString("url").equals(base + "/repo/com.example.app_12.apk")
        && v.getJSONArray("versions").getJSONObject(2).getLong("versionCode") == 10 && v.getJSONArray("versions").getJSONObject(0).getString("format").equals("apk"));
    JSONObject l = RepoSources.resolve("fdroid", "com.example.app", null, "latest");
    check("latest: the newest build, nothing wanted", l.getString("version").equals("1.2") && l.getLong("versionCode") == 12 && l.getString("wanted").isEmpty() && l.getString("policy").equals("latest") && l.getString("url").endsWith("com.example.app_12.apk"));
    check("latest follows the repository's suggested build, not the highest number (a release candidate)", RepoSources.resolve("fdroid", "com.example.sugg", null, "latest").getString("version").equals("1.9")
        && RepoSources.versions("fdroid", "com.example.sugg").getJSONArray("versions").getJSONObject(0).getString("version").equals("2.0-rc1")
        && RepoSources.versions("fdroid", "com.example.sugg").getJSONArray("versions").getJSONObject(1).getBoolean("suggested") && !RepoSources.versions("fdroid", "com.example.sugg").getJSONArray("versions").getJSONObject(0).getBoolean("suggested"));
    check("native code: none fits any phone; the phone's CPU must be among the build's; armeabi runs on armeabi-v7a", RepoSources.abiFits(new JSONArray(), new String[]{"arm64-v8a"})
        && RepoSources.abiFits(new JSONArray("[\"arm64-v8a\",\"x86_64\"]"), new String[]{"arm64-v8a", "armeabi-v7a"}) && !RepoSources.abiFits(new JSONArray("[\"x86\"]"), new String[]{"arm64-v8a", "armeabi-v7a"})
        && RepoSources.abiFits(new JSONArray("[\"armeabi\"]"), new String[]{"armeabi-v7a"}) && RepoSources.abiFits(null, new String[]{"x86"}));
    JSONObject r = RepoSources.resolve("fdroid", "com.example.app", "1.1", "requested");
    check("requested: that version, which the download then checks", r.getString("version").equals("1.1") && r.getLong("versionCode") == 11 && r.getString("wanted").equals("1.1") && r.getBoolean("exact"));
    check("requested with its build number", RepoSources.resolve("fdroid", "com.example.app", "1.0 (10)", "requested").getLong("versionCode") == 10 && err(() -> RepoSources.resolve("fdroid", "com.example.app", "1.0 (99)", "requested")).contains("does not list version"));
    check("a version the site does not have names the newest", err(() -> RepoSources.resolve("fdroid", "com.example.app", "9.9", "requested")).contains("the newest it lists is 1.2"));
    check("IzzyOnDroid uses its own address", RepoSources.resolve("izzy", "com.example.app", null, "latest").getString("url").equals(base + "/fdroid/repo/com.example.app_12.apk"));
    check("a package the site does not list", err(() -> RepoSources.versions("fdroid", "com.example.missing")).contains("does not list com.example.missing"));
    check("a package with no build", err(() -> RepoSources.versions("fdroid", "com.example.empty")).contains("lists no build"));
    check("hostile input: not a package, unknown source, bad policy, no version", err(() -> RepoSources.versions("fdroid", "../etc/passwd")).contains("not a package")
        && err(() -> RepoSources.versions("nope", "a.b")).contains("unknown source") && err(() -> RepoSources.resolve("fdroid", "com.example.app", "1", "newest")).contains("unknown version policy")
        && err(() -> RepoSources.resolve("fdroid", "com.example.app", "", "requested")).contains("version is needed"));
    s.stop(0);
    System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
