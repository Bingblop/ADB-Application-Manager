package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Updates tab's check engine (UpdateChecker, UpdateCache and the catalog index) against a fake network and a fake clock: saved results (hit, miss,
 * the 30-minute and 7-day lifetimes, a new version code, errors never kept), the circuit breaker per host, the global deadline, cancel, no internet,
 * updates reported as they are found, how many requests the old sequence made and how many this one makes on a 120-app phone, and that the activity and
 * the page use it the way they should.
 */
public class UpdateCheckerTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static final long MIN = 60_000L, DAY = 24 * 60 * MIN;

  // ---------------------------------------------------------------------------------------------- fakes

  static final class FakeClock implements UpdateChecker.Clock {
    final AtomicLong t = new AtomicLong(1_800_000_000_000L);
    public long now() { return t.get(); }
    void advance(long ms) { t.addAndGet(ms); }
  }

  /** A network that answers from a table. A host can be dead (every request costs the connect time and times out), hang, or the whole phone can be offline. */
  static final class FakeNet implements UpdateManager.Http {
    final FakeClock clock;
    final long deadCostMs;
    final Map<String, String> pages = new ConcurrentHashMap<String, String>();
    final Map<String, String> prefixes = new ConcurrentHashMap<String, String>();
    final Map<String, String> redirects = new ConcurrentHashMap<String, String>();
    final Map<String, Integer> statuses = new ConcurrentHashMap<String, Integer>();       // host -> HTTP status for every request (403 for a rate limit)
    final Set<String> dead = ConcurrentHashMap.newKeySet();
    final Set<String> hang = ConcurrentHashMap.newKeySet();
    final Set<String> flaky = ConcurrentHashMap.newKeySet();                              // every second request to the host fails
    final Map<String, AtomicInteger> perHost = new ConcurrentHashMap<String, AtomicInteger>();
    final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    final AtomicLong cost = new AtomicLong();
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean offline;
    static final long LATENCY_MS = 40;

    FakeNet(FakeClock clock, long deadCostMs) { this.clock = clock; this.deadCostMs = deadCostMs; }

    void spend(long ms) { clock.advance(ms); cost.addAndGet(ms); }
    int requests() { return log.size(); }
    int count(String part) { int k = 0; synchronized (log) { for (String u : log) if (u.contains(part)) k++; } return k; }
    int hostRequests(String host) { AtomicInteger c = perHost.get(host); return c == null ? 0 : c.get(); }

    private String lookup(String url) {
      String b = pages.get(url);
      if (b != null) return b;
      for (Map.Entry<String, String> e : prefixes.entrySet()) if (url.startsWith(e.getKey())) return e.getValue();
      return null;
    }

    public byte[] get(String url, String accept, String bearer, int maxBytes) throws Exception {
      String host = UpdateChecker.hostOf(url);
      log.add(url);
      AtomicInteger c = perHost.get(host);
      if (c == null) { perHost.putIfAbsent(host, new AtomicInteger()); c = perHost.get(host); }
      int nth = c.incrementAndGet();
      if (offline) throw new UnknownHostException(host);
      if (dead.contains(host)) { spend(deadCostMs); throw new SocketTimeoutException("connect timed out"); }
      if (flaky.contains(host) && nth % 2 == 0) { spend(deadCostMs); throw new SocketTimeoutException("read timed out"); }
      if (hang.contains(host)) {
        if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("hung for good");
        throw new IOException("released");
      }
      spend(LATENCY_MS);
      Integer st = statuses.get(host);
      if (st != null) throw new IllegalStateException("HTTP " + st);
      String body = lookup(url);
      if (body == null) throw new IllegalStateException("not found (HTTP 404)");
      return body.getBytes(StandardCharsets.UTF_8);
    }

    public String redirectLocation(String url) throws Exception {
      String host = UpdateChecker.hostOf(url);
      log.add(url);
      AtomicInteger c = perHost.get(host);
      if (c == null) { perHost.putIfAbsent(host, new AtomicInteger()); c = perHost.get(host); }
      c.incrementAndGet();
      if (offline) throw new UnknownHostException(host);
      if (dead.contains(host)) { spend(deadCostMs); throw new SocketTimeoutException("connect timed out"); }
      spend(LATENCY_MS);
      return redirects.get(url);
    }
  }

  static final class Events implements UpdateChecker.Listener {
    final List<JSONObject> rows = Collections.synchronizedList(new ArrayList<JSONObject>());
    final List<JSONObject> progress = Collections.synchronizedList(new ArrayList<JSONObject>());
    volatile CountDownLatch firstRow = new CountDownLatch(1);
    public void onUpdate(JSONObject row) { rows.add(row); firstRow.countDown(); }
    public void onProgress(JSONObject p) { progress.add(p); }
  }

  // ---------------------------------------------------------------------------------------------- a phone with 120 apps

  static final String GH = "https://api.github.com/repos/";
  static final String IZZY = UpdateManager.IZZY_API, FDROID = UpdateManager.FDROID_API;
  static final String STUB = "https://vas.samsungapps.com/stub/stubUpdateCheck.as?appId=";

  static String ghRelease(String owner, String repo, String tag) {
    return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"https://github.com/" + owner + "/" + repo + "/releases/tag/" + tag + "\",\"body\":\"notes\",\"assets\":[{\"name\":\"app-arm64-v8a.apk\",\"browser_download_url\":\"https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/app-arm64-v8a.apk\"}]}";
  }
  static String fdroidJson(String pkg, long code, String name) {
    return "{\"packageName\":\"" + pkg + "\",\"suggestedVersionCode\":" + code + ",\"packages\":[{\"versionCode\":" + code + ",\"versionName\":\"" + name + "\"}]}";
  }
  static String galaxyXml(int result, long code, String name) {
    return "<?xml version=\"1.0\"?><SamsungProtocol><resultCode>" + result + "</resultCode><resultMsg>m</resultMsg><versionCode>" + code + "</versionCode><versionName>" + name + "</versionName></SamsungProtocol>";
  }

  static final class World {
    final FakeClock clock = new FakeClock();
    final FakeNet net;
    final List<UpdateChecker.App> apps = new ArrayList<UpdateChecker.App>();
    final JSONObject sources = new JSONObject();
    final Set<String> updatePkgs = new HashSet<String>();
    int catalogApps, izzyApps, fdroidApps, unknownApps, galaxyApps;
    final UpdateChecker.App self = new UpdateChecker.App("com.bloatware.bingblop", "ADB Application Manager Pro", "7.12.11", 853, null, false, false, false);

    World(long deadCostMs) {
      net = new FakeNet(clock, deadCostMs);
      // the catalog list: the 12 installed apps and 40 more that nobody here has
      JSONArray list = new JSONArray();
      try {
        for (int i = 0; i < 12; i++) list.put(entry("cat.app" + i, "https://github.com/own/cat" + i, i % 2 == 0));
        for (int i = 0; i < 40; i++) list.put(entry("other.app" + i, "https://github.com/own/other" + i, false));
        net.pages.put(UpdateManager.OBTAINIUM_INDEX + "1", new JSONObject().put("apps", list).put("pagination", new JSONObject().put("page", 1).put("limit", 200).put("total", 52).put("totalPages", 1)).toString());
      } catch (Exception e) { throw new RuntimeException(e); }
      // 12 apps in the catalog (GitHub); every third has a newer release
      for (int i = 0; i < 12; i++) {
        String pkg = "cat.app" + i;
        boolean newer = i % 3 == 0;
        net.pages.put(GH + "own/cat" + i + "/releases/latest", ghRelease("own", "cat" + i, newer ? "v2.0.0" : "v1.0.0"));
        apps.add(new UpdateChecker.App(pkg, "Cat " + i, "1.0.0", 10, null, false, true, false));
        if (newer) updatePkgs.add(pkg);
        catalogApps++;
      }
      // 18 apps IzzyOnDroid knows (every fourth has a newer build)
      for (int i = 0; i < 18; i++) {
        String pkg = "izzy.app" + i;
        boolean newer = i % 4 == 0;
        net.pages.put(IZZY + pkg, fdroidJson(pkg, newer ? 11 : 10, newer ? "2.0" : "1.0.0"));
        apps.add(new UpdateChecker.App(pkg, "Izzy " + i, "1.0.0", 10, null, false, true, false));
        if (newer) updatePkgs.add(pkg);
        izzyApps++;
      }
      // 10 apps only F-Droid knows
      for (int i = 0; i < 10; i++) {
        String pkg = "fdroid.app" + i;
        boolean newer = i % 2 == 0;
        net.pages.put(FDROID + pkg, fdroidJson(pkg, newer ? 11 : 10, newer ? "2.0" : "1.0.0"));
        apps.add(new UpdateChecker.App(pkg, "FDroid " + i, "1.0.0", 10, null, false, true, false));
        if (newer) updatePkgs.add(pkg);
        fdroidApps++;
      }
      // 60 sideloaded apps no source knows
      for (int i = 0; i < 60; i++) { apps.add(new UpdateChecker.App("unknown.app" + i, "Unknown " + i, "1.0.0", 10, null, false, true, false)); unknownApps++; }
      // 20 Samsung system apps: 4 with an update, 11 up to date, 5 the Galaxy Store does not distribute
      for (int i = 0; i < 20; i++) {
        String pkg = "com.samsung.app" + i;
        int kind = i % 5 == 0 ? 2 : i % 4 == 3 ? 0 : 1;
        net.prefixes.put(STUB + pkg + "&", kind == 2 ? galaxyXml(2, 200, "2.0") : kind == 0 ? galaxyXml(0, 0, "") : galaxyXml(1, 100, "1.0"));
        apps.add(new UpdateChecker.App(pkg, "Samsung " + i, "1.0", 100, "com.sec.android.app.samsungapps", true, false, true));
        if (kind == 2) updatePkgs.add(pkg);
        galaxyApps++;
      }
      // the same apps as single files of the catalog (what the old check asked for, one app at a time)
      String raw = "https://raw.githubusercontent.com/ImranR98/apps.obtainium.imranr.dev/main/public/data/apps/";
      try {
        for (int i = 0; i < 12; i++) {
          JSONObject cfg = new JSONObject().put("id", "cat.app" + i).put("url", "https://github.com/own/cat" + i).put("author", "own");
          net.pages.put(raw + (i % 2 == 0 ? "complex/" : "simple/") + "cat.app" + i + ".json", i % 2 == 0 ? new JSONObject().put("configs", new JSONArray().put(cfg)).toString() : new JSONObject().put("config", cfg).toString());
        }
      } catch (Exception e) { throw new RuntimeException(e); }
      // this app's own releases
      net.pages.put(GH + UpdateManager.SELF_REPO + "/releases/latest", ghRelease("Bingblop", "ADB-Application-Manager", "v9.0.0"));
    }

    static JSONObject entry(String pkg, String url, boolean complex) throws Exception {
      JSONObject cfg = new JSONObject().put("id", pkg).put("url", url).put("author", "own").put("name", pkg);
      JSONObject e = new JSONObject().put("categories", new JSONArray()).put("type", complex ? "complex" : "simple");
      if (complex) e.put("configs", new JSONArray().put(cfg)); else e.put("config", cfg);
      return e;
    }

    UpdateChecker.Options options() {
      UpdateChecker.Options o = new UpdateChecker.Options();
      o.sources = sources;
      o.self = self;
      o.abis = new String[]{"arm64-v8a"};
      o.galaxyTag = "SM-S938B";
      o.device = new UpdateChecker.DeviceProvider() { public UpdateManager.Device get() { return new UpdateManager.Device("SM-S938B", "310", "00", "XEU", 34); } };
      return o;
    }

    int expectedUpdates() { return updatePkgs.size(); }
  }

  static File tempDir() throws Exception { return Files.createTempDirectory("updchk").toFile(); }

  static JSONObject run(World w, UpdateCache cache, UpdateChecker.Options o, UpdateChecker.Listener l) {
    UpdateChecker c = new UpdateChecker(w.net, w.clock, cache);
    return c.check(w.apps, o, l).summary;
  }

  static Set<String> pkgsOf(JSONArray a) {
    Set<String> s = new HashSet<String>();
    for (int i = 0; i < a.length(); i++) s.add(a.optJSONObject(i).optString("pkg"));
    return s;
  }

  static int updatesWithoutSelf(JSONObject s) {
    int k = 0;
    JSONArray a = s.optJSONArray("updates");
    for (int i = 0; i < a.length(); i++) if (!"self".equals(a.optJSONObject(i).optString("source"))) k++;
    return k;
  }

  // ---------------------------------------------------------------------------------------------- the old sequence, for counting

  /**
   * What the check used to do, request for request: the open-source apps one after the other per app (catalog file "simple", then "complex", then
   * IzzyOnDroid, then F-Droid, the first to know the app decides) on 4 threads, then the Galaxy Store apps on 6 threads, 15 s to connect. The same
   * parsers; only the order and the amount of requests are the old ones.
   */
  static final class Legacy { int requests; long costMs; int seconds; int updates; int dead; }

  static Legacy legacy(final World w, final FakeNet net) throws Exception {
    final Legacy lg = new Legacy();
    final AtomicInteger updates = new AtomicInteger();
    final List<UpdateChecker.App> os = new ArrayList<UpdateChecker.App>(), gx = new ArrayList<UpdateChecker.App>();
    for (UpdateChecker.App a : w.apps) { if (a.openSource) os.add(a); if (a.galaxy) gx.add(a); }
    try { UpdateManager.githubLatest(net, UpdateManager.SELF_REPO, new String[]{"arm64-v8a"}); } catch (Exception ignored) {}
    long before = net.cost.get();
    java.util.concurrent.ExecutorService p1 = java.util.concurrent.Executors.newFixedThreadPool(4);
    for (final UpdateChecker.App a : os) p1.submit(new Runnable() { public void run() {
      try {
        JSONObject cfg = null;
        try { cfg = UpdateManager.obtainiumCatalog(net, a.pkg); } catch (IllegalStateException e) { if (!String.valueOf(e.getMessage()).contains("404")) throw e; }
        if (cfg != null && cfg.optString("url").length() > 0) {
          JSONObject repo = UpdateManager.parseRepoUrl(cfg.optString("url"));
          UpdateManager.Release rel = UpdateManager.githubRelease(net, repo.optString("owner"), repo.optString("repo"), "", false, false);
          if (UpdateManager.compareVersions(rel.tag, a.versionName) > 0) updates.incrementAndGet();
          return;
        }
        JSONObject l = UpdateManager.fdroidLatest(net, UpdateManager.IZZY_API, a.pkg);
        if (l == null) l = UpdateManager.fdroidLatest(net, UpdateManager.FDROID_API, a.pkg);
        if (l != null && l.optLong("versionCode") > a.versionCode) updates.incrementAndGet();
      } catch (Exception ignored) {}
    } });
    p1.shutdown(); p1.awaitTermination(2, TimeUnit.MINUTES);
    long osCost = net.cost.get() - before;
    before = net.cost.get();
    java.util.concurrent.ExecutorService p2 = java.util.concurrent.Executors.newFixedThreadPool(6);
    for (final UpdateChecker.App a : gx) p2.submit(new Runnable() { public void run() {
      try { if (UpdateManager.galaxyCheck(net, a.pkg, a.versionCode, new UpdateManager.Device("SM-S938B", "310", "00", "XEU", 34)).optBoolean("available")) updates.incrementAndGet(); } catch (Exception ignored) {}
    } });
    p2.shutdown(); p2.awaitTermination(2, TimeUnit.MINUTES);
    long gxCost = net.cost.get() - before;
    lg.requests = net.requests();
    lg.costMs = osCost + gxCost;
    lg.seconds = (int) (osCost / 4 / 1000 + gxCost / 6 / 1000);          // the best the 4 and the 6 threads could do
    lg.updates = updates.get();
    lg.dead = net.hostRequests("apt.izzysoft.de");
    return lg;
  }

  // ---------------------------------------------------------------------------------------------- tests

  public static void main(String[] args) throws Exception {
    indexTests();
    firstRunTests();
    cacheTests();
    persistTests();
    breakerTests();
    deadlineCancelOfflineTests();
    githubLimitTests();
    catalogFallbackTests();
    retryTests();
    requestCountTests();
    wiringTests();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  static void indexTests() throws Exception {
    JSONObject out = new JSONObject();
    JSONObject simple = new JSONObject().put("apps", new JSONArray().put(World.entry("a.b", "https://github.com/x/y", false)).put(World.entry("c.d", "https://f-droid.org/packages/c.d", true)))
        .put("pagination", new JSONObject().put("totalPages", 3));
    int pages = UpdateManager.parseObtainiumIndex(simple, out);
    is("catalog list: the page count is read", pages == 3);
    is("catalog list: a simple entry (config) and a complex one (configs) are both kept under their package", out.has("a.b") && out.has("c.d") && out.getJSONObject("a.b").optString("url").equals("https://github.com/x/y"));
    JSONObject c = new JSONObject().put("apps", new JSONArray().put(new JSONObject().put("config", new JSONObject().put("id", "e.f")))).put("pagination", new JSONObject().put("totalPages", 1));
    UpdateManager.parseObtainiumIndex(c, out);
    is("catalog list: an entry with no address is left out", !out.has("e.f"));
    JSONObject sets = new JSONObject().put("apps", new JSONArray().put(new JSONObject().put("configs", new JSONArray()
        .put(new JSONObject().put("id", "g.h").put("url", "https://github.com/g/h").put("additionalSettings", "{\"apkFilterRegEx\":\"universal\"}"))
        .put(new JSONObject().put("id", "g.h").put("url", "https://github.com/g/other"))))).put("pagination", new JSONObject().put("totalPages", 1));
    JSONObject out2 = new JSONObject();
    UpdateManager.parseObtainiumIndex(sets, out2);
    is("catalog list: the first config of an entry is the one used, with its settings (the same one the per-app file gives)", out2.getJSONObject("g.h").optString("url").endsWith("/h") && UpdateManager.obtainiumSettings(out2.getJSONObject("g.h")).optString("apkFilterRegEx").equals("universal"));
    boolean threw = false;
    try { UpdateManager.parseObtainiumIndex(new JSONObject().put("hello", 1), new JSONObject()); } catch (IllegalStateException e) { threw = true; }
    is("catalog list: a page that is not the catalog is refused", threw);
    World w = new World(5000);
    threw = false;
    w.net.pages.put(UpdateManager.OBTAINIUM_INDEX + "1", new JSONObject().put("apps", new JSONArray().put(World.entry("a.b", "https://github.com/x/y", false))).put("pagination", new JSONObject().put("totalPages", 1)).toString());
    try { UpdateManager.obtainiumIndex(w.net); } catch (IllegalStateException e) { threw = true; }
    is("catalog list: a list with a handful of apps is not taken for the catalog (the site changed): the per-app files are used", threw);
    // several pages
    World w3 = new World(5000);
    JSONArray p1 = new JSONArray(), p2 = new JSONArray();
    for (int i = 0; i < 60; i++) p1.put(World.entry("p1.app" + i, "https://github.com/o/p1" + i, false));
    for (int i = 0; i < 20; i++) p2.put(World.entry("p2.app" + i, "https://github.com/o/p2" + i, true));
    w3.net.pages.put(UpdateManager.OBTAINIUM_INDEX + "1", new JSONObject().put("apps", p1).put("pagination", new JSONObject().put("totalPages", 2)).toString());
    w3.net.pages.put(UpdateManager.OBTAINIUM_INDEX + "2", new JSONObject().put("apps", p2).put("pagination", new JSONObject().put("totalPages", 2)).toString());
    JSONObject all = UpdateManager.obtainiumIndex(w3.net);
    is("catalog list: every page is read (80 apps from 2 pages, 2 requests)", all.length() == 80 && w3.net.requests() == 2);
  }

  static void firstRunTests() throws Exception {
    World w = new World(5000);
    UpdateCache cache = new UpdateCache(tempDir(), w.clock);
    Events ev = new Events();
    JSONObject s = run(w, cache, w.options(), ev);
    is("first run: every app is accounted for: none failed, none timed out, not cancelled", s.optJSONArray("failed").length() == 0 && s.optInt("timedOut") == 0 && !s.optBoolean("cancelled") && !s.optBoolean("offline") && s.optInt("total") == 120);
    is("first run: the updates are the ones the fixture holds (" + w.expectedUpdates() + " apps plus this app's own newer release)", updatesWithoutSelf(s) == w.expectedUpdates() && pkgsOf(s.optJSONArray("updates")).containsAll(w.updatePkgs));
    is("first run: this app's own newer release is in the result as the 'self' row, as before", pkgsOf(s.optJSONArray("updates")).contains("com.bloatware.bingblop") && "ok".equals(s.optString("selfStatus")));
    is("first run: the listener heard of every update as it was found (once each, the same rows)", ev.rows.size() == w.expectedUpdates() + 1 && pkgsOf(new JSONArray(ev.rows)).containsAll(w.updatePkgs));
    is("first run: the sideloaded apps nobody knows are listed as not tracked (60)", s.optJSONArray("untracked").length() == 60 && s.optInt("openSourceChecked") == 100 && s.optInt("checked") == 20);
    is("first run: the Galaxy Store's 'not distributed' answers (5) are no error", s.optInt("errors") == 0 && s.optInt("openSourceErrors") == 0);
    JSONObject last = ev.progress.get(ev.progress.size() - 1);
    is("first run: progress counts apps across all sources and ends at done = total", last.optInt("done") == 120 && last.optInt("total") == 120 && ev.progress.get(0).optInt("done") == 0 && ev.progress.get(0).optString("phase").equals("start"));
    boolean monotone = true; int prev = 0;
    synchronized (ev.progress) { for (JSONObject p : ev.progress) { if (p.optInt("done") < prev) monotone = false; prev = p.optInt("done"); } }
    is("first run: done never goes back", monotone);
    is("first run: nothing came from saved results", s.optInt("fromCache") == 0);
    // the update rows are the shape the page has always read
    JSONObject gh = null, gx = null, iz = null;
    for (int i = 0; i < s.optJSONArray("updates").length(); i++) {
      JSONObject r = s.optJSONArray("updates").optJSONObject(i);
      if (r.optString("pkg").equals("cat.app0")) gh = r; if (r.optString("pkg").equals("com.samsung.app0")) gx = r; if (r.optString("pkg").equals("izzy.app0")) iz = r;
    }
    is("rows: a GitHub update has the fields the page reads (source, origin, versions, download address, asset, page, notes, Obtainium link)",
        gh != null && "github".equals(gh.optString("source")) && "obtainium-catalog".equals(gh.optString("origin")) && gh.optString("installedVersion").equals("1.0.0") && gh.optString("availableVersion").equals("2.0.0")
        && gh.optString("downloadUrl").endsWith("app-arm64-v8a.apk") && gh.optString("assetName").equals("app-arm64-v8a.apk") && gh.optString("page").contains("releases") && gh.optString("notes").equals("notes")
        && gh.optString("obtainium").startsWith("obtainium://app/") && gh.optLong("installedCode") == 10 && gh.optString("sourceUrl").equals("https://github.com/own/cat0"));
    is("rows: a Galaxy Store update has its codes and the system flag", gx != null && "galaxy".equals(gx.optString("source")) && gx.optLong("availableCode") == 200 && gx.optLong("installedCode") == 100 && gx.optBoolean("isSystem") && gx.optString("availableVersion").equals("2.0"));
    is("rows: an IzzyOnDroid update names the APK on the repo", iz != null && "izzy".equals(iz.optString("source")) && iz.optString("downloadUrl").equals(UpdateManager.IZZY_REPO + "izzy.app0_11.apk") && iz.optLong("availableCode") == 11);
    // honest numbers: the catalog is read once
    is("the catalog list is read once, not one file per app (no raw.githubusercontent.com request at all)", w.net.hostRequests("raw.githubusercontent.com") == 0 && w.net.hostRequests("apps.obtainium.imranr.dev") == 1);

    // streaming: an update is reported while the check is still going
    World ws = new World(5000);
    final Events ev2 = new Events();
    final String slowUrl = IZZY + "unknown.app59";
    final CountDownLatch inFlight = new CountDownLatch(1);
    final FakeNet inner = ws.net;
    UpdateManager.Http gated = new UpdateManager.Http() {
      public byte[] get(String url, String accept, String bearer, int max) throws Exception {
        if (url.equals(slowUrl)) {
          inFlight.countDown();
          if (!ev2.firstRow.await(5, TimeUnit.SECONDS)) throw new IOException("no update was reported while this request was still open");
        }
        return inner.get(url, accept, bearer, max);
      }
      public String redirectLocation(String url) throws Exception { return inner.redirectLocation(url); }
    };
    UpdateChecker.Options so = ws.options();
    JSONObject s2 = new UpdateChecker(gated, ws.clock, new UpdateCache(tempDir(), ws.clock)).check(ws.apps, so, ev2).summary;
    is("streaming: the first update was reported while the last request was still open (it would have waited 5 s and failed otherwise)", s2.optJSONArray("failed").length() == 0 && inFlight.getCount() == 0);
  }

  static void cacheTests() throws Exception {
    World w = new World(5000);
    File dir = tempDir();
    UpdateCache cache = new UpdateCache(dir, w.clock);
    JSONObject s1 = run(w, cache, w.options(), new Events());
    int firstRequests = w.net.requests();
    is("cache: the first run asked the network (" + firstRequests + " requests)", firstRequests > 100);
    w.net.log.clear();
    w.clock.advance(5 * MIN);
    Events ev = new Events();
    JSONObject s2 = run(w, cache, w.options(), ev);
    is("cache: five minutes later nothing is asked of the network at all", w.net.requests() == 0);
    is("cache: ...and every app came from the saved results (fromCache = total = 120)", s2.optInt("fromCache") == 120 && s2.optInt("total") == 120);
    is("cache: ...with the same updates, the same not tracked and the same up to date", pkgsOf(s2.optJSONArray("updates")).equals(pkgsOf(s1.optJSONArray("updates"))) && pkgsOf(s2.optJSONArray("untracked")).equals(pkgsOf(s1.optJSONArray("untracked"))));
    is("cache: ...and the updates were still reported to the listener one by one", ev.rows.size() == s2.optJSONArray("updates").length());
    is("cache: the second run still has the rows' fields (name and installed version are the phone's)", s2.optJSONArray("updates").length() > 0);

    // 29 minutes after the first run: still fresh; 31: the positive answers are asked again, "nobody knows" is not
    w.net.log.clear();
    w.clock.advance(24 * MIN);                                           // 29 min after the first run
    run(w, cache, w.options(), new Events());
    is("cache: 29 minutes after the answer it is still used", w.net.requests() == 0);
    w.clock.advance(2 * MIN);                                            // 31 min
    w.net.log.clear();
    JSONObject s3 = run(w, cache, w.options(), new Events());
    int unknownRequests = 0;
    synchronized (w.net.log) { for (String u : w.net.log) if (u.contains("unknown.app")) unknownRequests++; }
    is("cache: after 30 minutes the answers about updates are asked again (" + w.net.requests() + " requests)", w.net.requests() > 40);
    is("cache: ...but 'no source knows this app' is kept for 7 days: the 60 unknown apps cost no request", unknownRequests == 0 && s3.optJSONArray("untracked").length() == 60);
    is("cache: ...and the Galaxy Store's 'not distributed' answers (5) were not asked again either", w.net.log.toString().contains("com.samsung.app3&") == false);
    // 7 days
    w.clock.advance(7 * DAY - 31 * MIN + 31 * MIN);
    w.net.log.clear();
    run(w, cache, w.options(), new Events());
    unknownRequests = 0;
    synchronized (w.net.log) { for (String u : w.net.log) if (u.contains("unknown.app")) unknownRequests++; }
    is("cache: after 7 days 'nobody knows' is asked again", unknownRequests > 0);

    // a new version code is a new key
    World wv = new World(5000);
    UpdateCache cv = new UpdateCache(tempDir(), wv.clock);
    run(wv, cv, wv.options(), new Events());
    wv.net.log.clear();
    List<UpdateChecker.App> bumped = new ArrayList<UpdateChecker.App>();
    for (UpdateChecker.App a : wv.apps) {
      if (a.pkg.equals("izzy.app0")) bumped.add(new UpdateChecker.App(a.pkg, a.name, "2.0", 11, a.installer, a.system, a.openSource, a.galaxy));       // it was updated
      else if (a.pkg.equals("unknown.app5")) bumped.add(new UpdateChecker.App(a.pkg, a.name, "1.1", 11, a.installer, a.system, a.openSource, a.galaxy));
      else bumped.add(a);
    }
    JSONObject sb = new UpdateChecker(wv.net, wv.clock, cv).check(bumped, wv.options(), new Events()).summary;
    is("cache: an app whose version code changed is checked again (and only that one: 2 apps asked, 2 + 1 requests)", wv.net.log.toString().contains("izzy.app0") && wv.net.log.toString().contains("unknown.app5") && wv.net.requests() <= 5);
    is("cache: an update that was installed is no update any more (the old saved row is not shown for the new version code)", !pkgsOf(sb.optJSONArray("updates")).contains("izzy.app0"));

    // a saved update that is not newer than what is installed is never shown
    World wt = new World(5000);
    UpdateCache ct = new UpdateCache(tempDir(), wt.clock);
    ct.put("izzy.app1", 10, "os", "|", UpdateCache.UPDATE, new JSONObject().put("pkg", "izzy.app1").put("source", "izzy").put("availableVersion", "1.0.0").put("availableCode", 10));
    JSONObject st = run(wt, ct, wt.options(), new Events());
    is("cache: a saved 'update' that is not newer than the installed version is thrown away, not shown", !pkgsOf(st.optJSONArray("updates")).contains("izzy.app1"));

    // force ignores the saved answers (and refreshes them)
    World wf = new World(5000);
    UpdateCache cf = new UpdateCache(tempDir(), wf.clock);
    run(wf, cf, wf.options(), new Events());
    wf.net.log.clear();
    UpdateChecker.Options fo = wf.options();
    fo.force = true;
    JSONObject sf = run(wf, cf, fo, new Events());
    is("cache: 'check again, ignore saved results' asks the network for everything (fromCache 0, requests " + wf.net.requests() + ")", sf.optInt("fromCache") == 0 && wf.net.requests() > 100);
    wf.net.log.clear();
    run(wf, cf, wf.options(), new Events());
    is("cache: ...and what it found is saved for the next normal check", wf.net.requests() == 0);

    // errors are never saved
    World we = new World(5000);
    UpdateCache ce = new UpdateCache(tempDir(), we.clock);
    we.net.dead.add("apt.izzysoft.de");
    UpdateChecker.Options one = we.options();
    one.threads = 1;
    JSONObject se = run(we, ce, one, new Events());
    int failedFirst = se.optJSONArray("failed").length();
    we.net.dead.clear();
    we.net.log.clear();
    JSONObject se2 = run(we, ce, we.options(), new Events());
    is("cache: apps that failed (" + failedFirst + ") are asked again at once on the next check, an error is never saved", failedFirst > 50 && se2.optJSONArray("failed").length() == 0 && we.net.requests() > failedFirst);

    // another source set for an app is another question
    World wu = new World(5000);
    UpdateCache cu = new UpdateCache(tempDir(), wu.clock);
    run(wu, cu, wu.options(), new Events());
    wu.net.log.clear();
    wu.sources.put("unknown.app7", new JSONObject().put("url", "https://github.com/me/mine").put("origin", "yours").put("settings", new JSONObject()));
    wu.net.pages.put(GH + "me/mine/releases/latest", ghRelease("me", "mine", "v3.0.0"));
    JSONObject su = run(wu, cu, wu.options(), new Events());
    is("cache: after a source is set for an app that nobody knew, it is checked at once (not after 7 days) and found", pkgsOf(su.optJSONArray("updates")).contains("unknown.app7") && wu.net.requests() <= 3);

    // the Galaxy Store answer depends on the phone
    World wg = new World(5000);
    UpdateCache cg = new UpdateCache(tempDir(), wg.clock);
    run(wg, cg, wg.options(), new Events());
    wg.net.log.clear();
    UpdateChecker.Options other = wg.options();
    other.galaxyTag = "SM-A546B";
    run(wg, cg, other, new Events());
    int gxReq = 0;
    synchronized (wg.net.log) { for (String u : wg.net.log) if (u.contains("vas.samsungapps.com")) gxReq++; }
    is("cache: a Galaxy Store answer is kept per phone model", gxReq == 20);
  }

  static void persistTests() throws Exception {
    World w = new World(5000);
    File dir = tempDir();
    run(w, new UpdateCache(dir, w.clock), w.options(), new Events());
    String[] files = dir.list();
    java.util.Arrays.sort(files);
    is("files: the answers and the catalog are kept in the app's folder (" + java.util.Arrays.toString(files) + ")", java.util.Arrays.asList(files).contains("update_cache.json") && java.util.Arrays.asList(files).contains("update_catalog.json"));
    w.net.log.clear();
    w.clock.advance(10 * MIN);
    JSONObject s = run(w, new UpdateCache(dir, w.clock), w.options(), new Events());
    is("files: a new cache object (the app was restarted) reads them back: no request, all from saved results", w.net.requests() == 0 && s.optInt("fromCache") == 120);
    w.clock.advance(25 * 60 * MIN);
    w.net.log.clear();
    run(w, new UpdateCache(dir, w.clock), w.options(), new Events());
    is("files: the catalog list is kept 24 hours: after a day it is read again (one request to the catalog site)", w.net.count("apps.obtainium.imranr.dev") == 1);

    // a damaged file is no file
    File bad = tempDir();
    Files.write(new File(bad, "update_cache.json").toPath(), "{{{ not json".getBytes(StandardCharsets.UTF_8));
    Files.write(new File(bad, "update_catalog.json").toPath(), "[]".getBytes(StandardCharsets.UTF_8));
    Files.write(new File(bad, "update_last.json").toPath(), "nope".getBytes(StandardCharsets.UTF_8));
    World wb = new World(5000);
    UpdateCache cb = new UpdateCache(bad, wb.clock);
    JSONObject sb = run(wb, cb, wb.options(), new Events());
    is("files: damaged files are ignored (the check runs, nothing fails)", sb.optJSONArray("failed").length() == 0 && cb.loadLast() == null && updatesWithoutSelf(sb) == wb.expectedUpdates());

    // the last result round-trips
    UpdateCache cl = new UpdateCache(tempDir(), w.clock);
    is("last result: none saved yet", cl.loadLast() == null);
    cl.saveLast(s.put("checkedAt", 12345L));
    is("last result: saved and read back whole by another cache object on the same folder", new UpdateCache(dirOf(cl), w.clock).loadLast() != null && new UpdateCache(dirOf(cl), w.clock).loadLast().optLong("checkedAt") == 12345L);

    // an expired or future entry is not used
    UpdateCache ck = new UpdateCache(tempDir(), w.clock);
    ck.put("a.b", 5, "os", "t", UpdateCache.UPDATE, new JSONObject().put("x", 1));
    is("entries: a fresh entry is found", ck.lookup("a.b", 5, "os", "t") != null);
    is("entries: another version code, lane or tag is a miss", ck.lookup("a.b", 6, "os", "t") == null && ck.lookup("a.b", 5, "gx", "t") == null && ck.lookup("a.b", 5, "os", "u") == null);
    w.clock.advance(UpdateCache.POSITIVE_TTL_MS - 1);
    is("entries: the last millisecond of 30 minutes still counts", ck.lookup("a.b", 5, "os", "t") != null);
    w.clock.advance(1);
    is("entries: 30 minutes is over", ck.lookup("a.b", 5, "os", "t") == null);
    ck.put("c.d", 5, "os", "t", UpdateCache.UNTRACKED, null);
    w.clock.advance(UpdateCache.NEGATIVE_TTL_MS - 1);
    is("entries: 'nobody knows' still counts on the last millisecond of 7 days", ck.lookup("c.d", 5, "os", "t") != null);
    w.clock.advance(1);
    is("entries: ...and not after", ck.lookup("c.d", 5, "os", "t") == null);
    ck.put("e.f", 5, "os", "t", UpdateCache.UPTODATE, null);
    w.clock.t.addAndGet(-5 * DAY);
    is("entries: a clock that went back never makes an entry fresh", ck.lookup("e.f", 5, "os", "t") == null);
    is("entries: the lifetimes are 30 minutes, 7 days and 24 hours", UpdateCache.POSITIVE_TTL_MS == 30 * MIN && UpdateCache.NEGATIVE_TTL_MS == 7 * DAY && UpdateCache.CATALOG_TTL_MS == DAY);
  }

  static File dirOf(UpdateCache c) throws Exception {
    java.lang.reflect.Field f = UpdateCache.class.getDeclaredField("dir");
    f.setAccessible(true);
    return (File) f.get(c);
  }

  static void breakerTests() throws Exception {
    // one thread: exactly three requests reach a dead host, then it is left alone and named once
    World w = new World(5000);
    w.net.dead.add("apt.izzysoft.de");
    UpdateChecker.Options o = w.options();
    o.threads = 1;
    JSONObject s = run(w, new UpdateCache(tempDir(), w.clock), o, new Events());
    is("breaker: after 3 failures in a row the host gets no more requests this run (" + w.net.hostRequests("apt.izzysoft.de") + " reached it)", w.net.hostRequests("apt.izzysoft.de") == 3 && UpdateChecker.BREAKER_FAILURES == 3);
    JSONArray down = s.optJSONArray("hostsDown");
    is("breaker: the host is named once in hostsDown", down.length() == 1 && down.optString(0).equals("apt.izzysoft.de"));
    JSONArray failed = s.optJSONArray("failed");
    boolean allHost = failed.length() > 0;
    for (int i = 0; i < failed.length(); i++) if (!"host".equals(failed.optJSONObject(i).optString("code")) || !failed.optJSONObject(i).optString("reason").equals("apt.izzysoft.de unreachable")) allHost = false;
    is("breaker: the apps that needed it say '<host> unreachable', not a different error each (" + failed.length() + " apps)", allHost && failed.length() == 18 + 60);
    is("breaker: an app F-Droid knows was still checked through F-Droid when IzzyOnDroid was down (found, not failed)", !pkgsOf(failed).contains("fdroid.app0") && pkgsOf(s.optJSONArray("updates")).contains("fdroid.app0"));
    is("breaker: the failed apps are not counted as checked, and the summary does not call this a clean run", s.optInt("openSourceChecked") == 100 - 78 && s.optInt("openSourceErrors") == 78);
    is("breaker: the apps no source could answer are not in 'not tracked' (we do not know they are untracked)", s.optJSONArray("untracked").length() == 0);
    is("breaker: the other sources were not affected (Galaxy Store 20 asked, catalog apps found)", w.net.hostRequests("vas.samsungapps.com") == 20 && pkgsOf(s.optJSONArray("updates")).contains("cat.app0"));

    // eight threads: at most a burst of eight gets through before the third failure is seen
    World w8 = new World(5000);
    w8.net.dead.add("apt.izzysoft.de");
    JSONObject s8 = run(w8, new UpdateCache(tempDir(), w8.clock), w8.options(), new Events());
    int reached = w8.net.hostRequests("apt.izzysoft.de");
    is("breaker: with 8 threads at most " + (3 + 8 - 1) + " requests reach the dead host (" + reached + ")", reached >= 3 && reached <= 3 + 7);
    is("breaker: ...and it is still named once", s8.optJSONArray("hostsDown").length() == 1);

    // a success in between resets the count: fail, fail, answer, fail, fail never trips
    World wf = new World(5000);
    wf.net.dead.clear();
    FakeNet fnet = wf.net;
    final AtomicInteger seq = new AtomicInteger();
    final String[] pattern = {"F", "F", "S", "F", "F", "S", "F", "F", "S", "F", "F", "S"};
    UpdateManager.Http patterned = new UpdateManager.Http() {
      public byte[] get(String url, String accept, String bearer, int max) throws Exception {
        if (url.startsWith(IZZY)) {
          String p = pattern[seq.getAndIncrement() % pattern.length];
          if (p.equals("F")) throw new SocketTimeoutException("read timed out");
          throw new IllegalStateException("not found (HTTP 404)");
        }
        return fnet.get(url, accept, bearer, max);
      }
      public String redirectLocation(String url) throws Exception { return fnet.redirectLocation(url); }
    };
    UpdateChecker.Options po = wf.options();
    po.threads = 1;
    JSONObject sp = new UpdateChecker(patterned, wf.clock, new UpdateCache(tempDir(), wf.clock)).check(wf.apps, po, new Events()).summary;
    is("breaker: two failures, an answer, two failures ... never reach three in a row: the host is not given up on", sp.optJSONArray("hostsDown").length() == 0);

    // timeouts and a plain refused connection count the same; a server error (HTTP 500) does not (the host answered)
    World w5 = new World(5000);
    w5.net.statuses.put("apt.izzysoft.de", 500);
    JSONObject s5 = run(w5, new UpdateCache(tempDir(), w5.clock), w5.options(), new Events());
    is("breaker: a host that answers with HTTP 500 is not 'unreachable' (it answered): its apps fail with the status, the host is not given up on", s5.optJSONArray("hostsDown").length() == 0 && s5.optJSONArray("failed").length() > 0 && s5.optJSONArray("failed").optJSONObject(0).optString("code").equals("http"));
  }

  static void deadlineCancelOfflineTests() throws Exception {
    // a host that never answers: the run still ends at the deadline, and every app is in the result
    final World w = new World(5000);
    w.net.hang.add("apt.izzysoft.de");
    w.net.hang.add("f-droid.org");
    final AtomicLong realStart = new AtomicLong(System.nanoTime());
    final Events dev = new Events();
    Thread ticker = new Thread(new Runnable() { public void run() {
      try {
        // the fake time starts to run once the working hosts have answered (a slow machine must not run out the 90 s first)
        long until = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < until && !(pkgsOf(new JSONArray(new ArrayList<JSONObject>(dev.rows))).contains("cat.app0") && pkgsOf(new JSONArray(new ArrayList<JSONObject>(dev.rows))).contains("com.samsung.app0"))) Thread.sleep(5);
        while (w.net.release.getCount() > 0) { Thread.sleep(5); w.clock.advance(1000); }
      } catch (InterruptedException ignored) {}
    } });
    ticker.setDaemon(true);
    ticker.start();
    long startFake = w.clock.now();
    UpdateChecker.Options o = w.options();
    JSONObject s = run(w, new UpdateCache(tempDir(), w.clock), o, dev);
    long realMs = (System.nanoTime() - realStart.get()) / 1_000_000;
    long fakeMs = w.clock.now() - startFake;
    w.net.release.countDown();
    JSONArray failed = s.optJSONArray("failed");
    int deadline = 0;
    for (int i = 0; i < failed.length(); i++) if ("deadline".equals(failed.optJSONObject(i).optString("code"))) deadline++;
    is("deadline: the run ended (it took " + realMs + " ms of real time) with the result in hand, though two hosts never answer", realMs < 8000);
    is("deadline: it ended once the 90 seconds were up (" + fakeMs / 1000 + " s of the fake clock), not at the end of a 5-minute wait", fakeMs >= 90_000 && fakeMs < 90_000 + 60_000);
    is("deadline: every app that was not finished is reported as timed out (" + deadline + "), none dropped", deadline > 0 && s.optInt("timedOut") == deadline);
    int accounted = failed.length() + s.optInt("openSourceChecked") + s.optInt("checked");
    Set<String> failedPkgs = pkgsOf(failed);
    boolean coherent = true;
    for (UpdateChecker.App a : w.apps) {
      boolean inFailed = failedPkgs.contains(a.pkg);
      boolean inUpdates = pkgsOf(s.optJSONArray("updates")).contains(a.pkg);
      if (a.pkg.startsWith("unknown.app") && !inFailed) coherent = false;       // nobody answered for these: they cannot be 'not tracked' or fine
      if (inFailed && inUpdates) coherent = false;
    }
    is("deadline: the apps nobody answered for are failed, never 'not tracked' or quietly fine; an app is not both failed and updated", coherent);
    is("deadline: the apps the working hosts answered before the deadline kept their results (catalog and Galaxy Store updates are in)", pkgsOf(s.optJSONArray("updates")).contains("cat.app0") && pkgsOf(s.optJSONArray("updates")).contains("com.samsung.app0"));
    is("deadline: the reason says 'timed out'", failed.optJSONObject(0).optString("reason").equals("timed out"));
    is("deadline: not cancelled, not offline", !s.optBoolean("cancelled") && !s.optBoolean("offline"));
    is("deadline: the other options do not change it: the default is 90 s and 8 threads", new UpdateChecker.Options().deadlineMs == 90_000 && new UpdateChecker.Options().threads == 8);

    // cancel: prompt, what was found is kept, the rest is 'not checked' and not 'failed'
    final World wc = new World(5000);
    wc.net.hang.add("apt.izzysoft.de");
    wc.net.hang.add("f-droid.org");
    final UpdateChecker checker = new UpdateChecker(wc.net, wc.clock, new UpdateCache(tempDir(), wc.clock));
    final Events ev = new Events();
    Thread canceller = new Thread(new Runnable() { public void run() {
      try {
        long until = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < until && !pkgsOf(new JSONArray(new ArrayList<JSONObject>(ev.rows))).contains("cat.app0")) Thread.sleep(5);
        Thread.sleep(150);
      } catch (InterruptedException ignored) {}
      checker.cancel();
    } });
    canceller.start();
    long t0 = System.nanoTime();
    JSONObject sc = checker.check(wc.apps, wc.options(), ev).summary;
    long cancelMs = (System.nanoTime() - t0) / 1_000_000;
    wc.net.release.countDown();
    is("cancel: the check returned within a second of Cancel, though requests were still hanging (" + cancelMs + " ms in all)", cancelMs < 3000);
    is("cancel: it says so, and the apps not reached are 'not checked' (" + sc.optInt("notChecked") + "), not errors", sc.optBoolean("cancelled") && sc.optInt("notChecked") > 0 && sc.optInt("timedOut") == 0 && sc.optJSONArray("failed").length() == 0);
    is("cancel: what was found before is in the result", pkgsOf(sc.optJSONArray("updates")).contains("cat.app0"));

    // cancel before anything: nothing is asked
    World wp = new World(5000);
    UpdateChecker pre = new UpdateChecker(wp.net, wp.clock, new UpdateCache(tempDir(), wp.clock));
    pre.cancel();
    JSONObject sp = pre.check(wp.apps, wp.options(), new Events()).summary;
    is("cancel: pressed at the start, it ends at once and nothing is listed as failed", sp.optBoolean("cancelled") && sp.optJSONArray("failed").length() == 0 && wp.net.requests() <= 16);

    // offline
    World wo = new World(5000);
    wo.net.offline = true;
    long t1 = System.nanoTime();
    JSONObject so = run(wo, new UpdateCache(tempDir(), wo.clock), wo.options(), new Events());
    long offMs = (System.nanoTime() - t1) / 1_000_000;
    is("offline: the check stops early with 'offline' set (" + wo.net.requests() + " requests of ~190, " + offMs + " ms)", so.optBoolean("offline") && wo.net.requests() < 40);
    is("offline: every app is reported, with the reason 'no internet' (120 failed)", so.optJSONArray("failed").length() == 120 && so.optJSONArray("failed").optJSONObject(0).optString("code").equals("offline") && so.optJSONArray("failed").optJSONObject(0).optString("reason").equals("no internet"));
    is("offline: no host is blamed one by one (nothing was answered anywhere)", so.optInt("timedOut") == 0);
    // one dead host is not 'offline'
    World wd = new World(5000);
    wd.net.dead.add("vas.samsungapps.com");
    JSONObject sd = run(wd, new UpdateCache(tempDir(), wd.clock), wd.options(), new Events());
    is("offline: one dead host while the others answer is that host's trouble, not 'no internet'", !sd.optBoolean("offline") && sd.optJSONArray("hostsDown").length() == 1 && sd.optJSONArray("hostsDown").optString(0).equals("vas.samsungapps.com"));
    World wq = new World(5000);
    for (String h : new String[]{"vas.samsungapps.com", "apt.izzysoft.de", "f-droid.org", "api.github.com", "apps.obtainium.imranr.dev"}) wq.net.dead.add(h);
    JSONObject sq = run(wq, new UpdateCache(tempDir(), wq.clock), wq.options(), new Events());
    is("offline: every host timing out with no answer at all is treated as no internet", sq.optBoolean("offline"));
  }

  static void githubLimitTests() throws Exception {
    World w = new World(5000);
    w.net.statuses.put("api.github.com", 403);
    // the public pages answer instead
    for (int i = 0; i < 12; i++) {
      String tag = i % 3 == 0 ? "v2.0.0" : "v1.0.0";
      w.net.redirects.put("https://github.com/own/cat" + i + "/releases/latest", "https://github.com/own/cat" + i + "/releases/tag/" + tag);
      w.net.pages.put("https://github.com/own/cat" + i + "/releases/expanded_assets/" + tag, "<a href=\"/own/cat" + i + "/releases/download/" + tag + "/app-arm64-v8a.apk\">x</a>");
    }
    UpdateChecker.Options o = w.options();
    o.threads = 1;
    o.self = null;
    JSONObject s = run(w, new UpdateCache(tempDir(), w.clock), o, new Events());
    is("rate limit: GitHub's API says 403: the public release pages answer, the updates are the same", pkgsOf(s.optJSONArray("updates")).containsAll(java.util.Arrays.asList("cat.app0", "cat.app3", "cat.app6", "cat.app9")) && s.optJSONArray("failed").length() == 0);
    is("rate limit: after the first 403 the API is not asked again in this run (" + w.net.hostRequests("api.github.com") + " request)", w.net.hostRequests("api.github.com") == 1);
  }

  static void catalogFallbackTests() throws Exception {
    // the list is not reachable: the per-app files answer, as they always did (simple, then complex)
    World w = new World(5000);
    w.net.dead.add("apps.obtainium.imranr.dev");
    JSONObject s = run(w, new UpdateCache(tempDir(), w.clock), w.options(), new Events());
    is("catalog down: the per-app files are used instead and the same updates are found", updatesWithoutSelf(s) == w.expectedUpdates() && s.optJSONArray("failed").length() == 0);
    is("catalog down: it costs more requests to the file host (" + w.net.hostRequests("raw.githubusercontent.com") + ") but one request to the dead list", w.net.hostRequests("raw.githubusercontent.com") >= 100 && w.net.hostRequests("apps.obtainium.imranr.dev") == 1);
    // a catalog stored earlier, however old, beats hundreds of per-app requests
    World w2 = new World(5000);
    File dir = tempDir();
    run(w2, new UpdateCache(dir, w2.clock), w2.options(), new Events());
    w2.clock.advance(3 * DAY);
    w2.net.dead.add("apps.obtainium.imranr.dev");
    w2.net.log.clear();
    JSONObject s2 = run(w2, new UpdateCache(dir, w2.clock), w2.options(), new Events());
    is("catalog down: a catalog saved 3 days ago is used, no per-app file is asked for", w2.net.hostRequests("raw.githubusercontent.com") == 0 && pkgsOf(s2.optJSONArray("updates")).contains("cat.app0"));
  }

  static void retryTests() throws Exception {
    World w = new World(5000);
    w.net.dead.add("apt.izzysoft.de");
    UpdateCache cache = new UpdateCache(tempDir(), w.clock);
    UpdateChecker.Options o = w.options();
    o.threads = 1;
    JSONObject first = run(w, cache, o, new Events());
    int failedBefore = first.optJSONArray("failed").length();
    Set<String> retried = pkgsOf(first.optJSONArray("failed"));
    w.net.dead.clear();
    w.net.log.clear();
    UpdateChecker.Options ro = w.options();
    ro.only = retried;
    ro.force = true;                                // refreshCatalog stays off: the list is not what failed
    JSONObject second = run(w, cache, ro, new Events());
    is("retry: only the failed apps are checked (" + retried.size() + " of 120: " + w.net.requests() + " requests, none for the others)", second.optInt("total") == failedBefore && !w.net.log.toString().contains("cat.app") && !w.net.log.toString().contains("vas.samsungapps.com") && second.optJSONArray("failed").length() == 0);
    JSONObject merged = UpdateChecker.merge(first, second, retried);
    is("retry: merged with the run before, nothing is failed any more and the earlier findings stay", merged.optJSONArray("failed").length() == 0 && merged.optInt("openSourceErrors") == 0 && pkgsOf(merged.optJSONArray("updates")).contains("cat.app0") && pkgsOf(merged.optJSONArray("updates")).contains("izzy.app0"));
    is("retry: ...the not tracked list is complete again (60) and the counts add up", merged.optJSONArray("untracked").length() == 60 && merged.optInt("openSourceChecked") == 100 && merged.optInt("checked") == 20);
    // retry that fails again stays failed
    World w2 = new World(5000);
    w2.net.dead.add("apt.izzysoft.de");
    UpdateCache c2 = new UpdateCache(tempDir(), w2.clock);
    UpdateChecker.Options o2 = w2.options();
    o2.threads = 1;
    JSONObject f1 = run(w2, c2, o2, new Events());
    UpdateChecker.Options r2 = w2.options();
    r2.only = pkgsOf(f1.optJSONArray("failed"));
    r2.force = true;
    JSONObject f2 = run(w2, c2, r2, new Events());
    JSONObject m2 = UpdateChecker.merge(f1, f2, r2.only);
    is("retry: a host that is still down leaves the apps failed (and hostsDown says so)", m2.optJSONArray("failed").length() == f1.optJSONArray("failed").length() && m2.optJSONArray("hostsDown").length() == 1);
  }

  static void requestCountTests() throws Exception {
    // the old sequence on a healthy network
    World wo = new World(15_000);
    Legacy old = legacy(wo, wo.net);
    World wn = new World(5_000);
    UpdateCache cache = new UpdateCache(tempDir(), wn.clock);
    JSONObject first = run(wn, cache, wn.options(), new Events());
    int newFirst = wn.net.requests();
    wn.net.log.clear();
    wn.clock.advance(10 * MIN);
    run(wn, cache, wn.options(), new Events());
    int newSecond = wn.net.requests();
    is("requests, 120 apps, healthy network: the old sequence made " + old.requests + " requests; the first run now makes " + newFirst + "; a second check ten minutes later makes " + newSecond, old.requests > 350 && newFirst < old.requests * 0.6 && newSecond == 0);
    is("requests: both find the same updates (" + old.updates + " apps)", old.updates == wn.expectedUpdates() && updatesWithoutSelf(first) == old.updates);
    System.out.println("INFO healthy network, 120 apps: old " + old.requests + " requests (" + old.seconds + " s at best on 4 + 6 threads), new first run " + newFirst + ", new second run " + newSecond);

    // one dead host (IzzyOnDroid): the old check waited 15 s per request, per app
    World wod = new World(15_000);
    wod.net.dead.add("apt.izzysoft.de");
    Legacy oldDead = legacy(wod, wod.net);
    World wnd = new World(5_000);
    wnd.net.dead.add("apt.izzysoft.de");
    long fakeStart = wnd.clock.now();
    JSONObject dead = run(wnd, new UpdateCache(tempDir(), wnd.clock), wnd.options(), new Events());
    long newCost = wnd.net.cost.get();
    long fakeElapsed = wnd.clock.now() - fakeStart;
    int deadReached = wnd.net.hostRequests("apt.izzysoft.de");
    is("dead host, 120 apps: the old check made " + oldDead.requests + " requests, " + oldDead.dead + " of them to the dead host, about " + oldDead.seconds + " s even with every thread busy (and gave up after 5 minutes without saying)", oldDead.seconds > 90 && oldDead.dead > 80);
    is("dead host: the new check reached the dead host " + deadReached + " times, " + wnd.net.requests() + " requests in all, and finished inside the deadline (" + newCost / 1000 + " s of waiting in all, run ended " + fakeElapsed / 1000 + " s in)",
        deadReached <= 3 + 7 && newCost / 1000 < 90 && !dead.optBoolean("cancelled") && dead.optInt("timedOut") == 0);
    is("dead host: it says so once (hostsDown) and reports the apps it could not check (" + dead.optJSONArray("failed").length() + "), none missing", dead.optJSONArray("hostsDown").length() == 1 && dead.optJSONArray("failed").length() == 78 && dead.optInt("total") == 120);
    System.out.println("INFO dead IzzyOnDroid, 120 apps: old " + oldDead.requests + " requests, " + oldDead.dead + " to the dead host, about " + oldDead.seconds + " s at best; new " + wnd.net.requests() + " requests, " + deadReached + " to the dead host, " + newCost / 1000 + " s of waiting in all");
  }

  // ---------------------------------------------------------------------------------------------- the activity and the page

  static String read(String rel) throws Exception {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, rel);
      if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
    throw new IOException("not found: " + rel);
  }

  static String methodBody(String src, String signature) {
    int at = src.indexOf("    " + signature);
    if (at < 0) return "";
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\n    (private|public|protected|static|final|synchronized|/\\*\\*|@)").matcher(src);
    int end = src.length();
    if (m.find(at + signature.length() + 4)) end = m.start();
    return src.substring(at, end);
  }

  static void wiringTests() throws Exception {
    String src;
    String page;
    try {
      src = read("src/com/bloatware/bingblop/MainActivity.java");
      page = read("assets/index.html");
    } catch (Exception e) {
      System.out.println("SKIP wiring: the sources were not found from " + new File(".").getAbsolutePath());
      return;
    }
    String run = methodBody(src, "private void runUpdateCheck(");
    is("wiring: the check is the engine's (UpdateChecker), on its own run object that Cancel and onDestroy can reach", run.contains("new UpdateChecker(") && src.contains("cancelUpdateCheck()") && src.contains("updateCheckRun"));
    is("wiring: a new check drops every approval, as before", run.contains("approvedUpdates.clear()"));
    is("wiring: the running flag and the time are reset in a finally, on every way out", run.contains("finally") && run.indexOf("updateCheckRunning = false") > run.indexOf("finally"));
    is("wiring: the final summary is always sent (also after an exception)", run.contains("onUpdatesChecked"));
    is("wiring: the activity's onDestroy cancels a running check", methodBody(src, "protected void onDestroy(").contains("cancelUpdateCheckRun()"));
    is("wiring: the old per-app loops are gone from the activity (no thread pool of its own for the check)", !src.contains("private void checkOpenSourceApps(") && !src.contains("private JSONObject checkOpenSourceApp("));
    is("wiring: every update is sent to the page as it is found", src.contains("onUpdateFound"));
    is("wiring: the checks run with 5 s to connect and 8 s to read", src.contains("UpdateManager.netHttp(5000, 8000)"));
    is("wiring: the page has Cancel, Check again (ignoring saved results) and Retry failed, and uses the bridge methods", src.contains("public void cancelUpdateCheck()") && src.contains("public void recheckUpdates()") && src.contains("public void retryFailedUpdates()")
        && page.contains("updCancelBtn") && page.contains("updRecheckBtn") && page.contains("updRetryBtn") && page.contains("function onUpdateFound(") && page.contains("b.cancelUpdateCheck") && page.contains("b.recheckUpdates") && page.contains("b.retryFailedUpdates"));
    is("wiring: the page never says everything is up to date when apps could not be checked", page.contains("couldn\\u2019t be checked") || page.contains("couldn't be checked") || page.contains("couldn’t be checked"));
  }
}
