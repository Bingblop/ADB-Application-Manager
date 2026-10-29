package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The Stop apps tile's run: which apps it stops and which it leaves alone (this app, the WebView, Media Picker), how far it is and what the notification
 * says, the throttle, Cancel (also while an update is being posted), the one run at a time, the tap on the notification, and (source scans) that
 * MainActivity, the notification, the receiver, the manifest and the page are wired the way those rules need.
 */
public class QuickStopRunTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void check(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + detail); } }

  static List<String> list(String... p) { return new ArrayList<String>(Arrays.asList(p)); }
  static final String OWN = "com.bloatware.bingblop", WEBVIEW = "com.google.android.webview", MEDIA = "com.google.android.providers.media.module";

  static QuickStopRun run(String... pkgs) { return new QuickStopRun("list_1", "Game boost", list(pkgs), QuickStopRun.protectedPackages(OWN, WEBVIEW)); }

  public static void main(String[] args) throws Exception {
    // ---- what is never stopped
    Set<String> keep = QuickStopRun.protectedPackages(OWN, "com.android.chrome");
    check("left alone: this app, the WebView the phone uses now (here Chrome), Media Picker", keep.contains(OWN) && keep.contains("com.android.chrome") && keep.contains(MEDIA));
    check("left alone: the other names of the media provider and of the WebView apps", keep.contains("com.android.providers.media.module") && keep.contains("com.android.providers.media") && keep.contains("com.google.android.webview") && keep.contains("com.android.webview"));
    Set<String> noWeb = QuickStopRun.protectedPackages(OWN, "");
    check("a phone that does not name its WebView: nothing blank in the set, the rest as before", !noWeb.contains("") && noWeb.contains(OWN) && noWeb.contains(MEDIA) && noWeb.contains("com.google.android.webview"));
    check("null names are skipped, not stored", !QuickStopRun.protectedPackages(null, null).contains(null) && QuickStopRun.protectedPackages(null, null).contains(MEDIA));
    check("Chrome is stopped like any app when it is not the WebView", run("com.android.chrome").total() == 1 && run("com.android.chrome").skipped().isEmpty());

    // ---- the list: order, repeats, blanks, protected apps
    QuickStopRun r = run("com.a", " com.b ", "com.a", "", "  ", null, OWN, "com.c", WEBVIEW, MEDIA, "com.android.providers.media.module", OWN);
    check("targets: the list's order, trimmed, each once, blanks dropped", r.targets().equals(list("com.a", "com.b", "com.c")), r.targets().toString());
    check("skipped: this app, the WebView, Media Picker and its other name, each once, in the list's order", r.skipped().equals(list(OWN, WEBVIEW, MEDIA, "com.android.providers.media.module")), r.skipped().toString());
    check("a name that only starts like a protected one is stopped (exact match only)", run(MEDIA + ".extra", OWN + "2", "x" + WEBVIEW).total() == 3);
    check("package names are case-sensitive, as on Android", run(MEDIA.toUpperCase()).total() == 1);
    // v7.19.0: the keyboard, the home screen, System UI (it draws the live notification) and the tools the run goes through are stopped last, tools at the very end
    List<String> late = QuickStopRun.lastOrder(list("moe.shizuku.privileged.api", " ", null), "com.samsung.android.honeyboard", "com.sec.android.app.launcher");
    check("lastOrder: keyboard, home, System UI, then the tools; blanks dropped", late.equals(list("com.samsung.android.honeyboard", "com.sec.android.app.launcher", "com.android.systemui", "moe.shizuku.privileged.api")), late.toString());
    check("lastOrder: System UI named as a tool stays before the tools, and nulls do not throw", QuickStopRun.lastOrder(list("com.android.systemui", "a.b"), null, null).equals(list("com.android.systemui", "a.b")));
    QuickStopRun ordered = new QuickStopRun("i", "n", list("com.android.systemui", "x.one", "moe.shizuku.privileged.api", "x.two"), QuickStopRun.protectedPackages(OWN, WEBVIEW), late);
    check("the late apps go after the others, in lastOrder's order", ordered.targets().equals(list("x.one", "x.two", "com.android.systemui", "moe.shizuku.privileged.api")), ordered.targets().toString());
    check("a late app not in the list is not added", !new QuickStopRun("i", "n", list("x.one"), null, late).targets().contains("com.android.systemui"));
    check("a null list or null protected set does not throw", new QuickStopRun("i", "n", null, null).total() == 0 && new QuickStopRun("i", "n", list("a.b"), null).total() == 1);
    check("the list id and name are kept (null become blank, the name trimmed)", r.listId().equals("list_1") && r.listName().equals("Game boost") && new QuickStopRun(null, " Mine ", null, null).listName().equals("Mine") && new QuickStopRun(null, null, null, null).listId().isEmpty());
    boolean readOnly = false;
    try { r.targets().add("x"); } catch (UnsupportedOperationException e) { readOnly = true; }
    check("the lists handed out cannot be changed from outside", readOnly);

    // ---- progress and the words of the notification
    QuickStopRun p = run("a.one", "a.two", "a.three", "a.four", "a.five", "a.six", "a.seven", "a.eight", "a.nine", "a.ten", "a.eleven", "a.twelve");
    check("before the first app: 1 of 12, 0%, 1/12", p.position() == 1 && p.percent() == 0 && p.progressText().equals("1 of 12") && p.shortText().equals("1/12"), p.progressText());
    p.starting(" Chrome ");
    check("the app's name follows after a dot, trimmed", p.progressText().equals("1 of 12 · Chrome"), p.progressText());
    p.finished(true); p.finished(true);
    p.starting("Maps");
    check("two done: 3 of 12 · Maps, 16% (2 of 12 done), chip 3/12", p.position() == 3 && p.percent() == 16 && p.progressText().equals("3 of 12 · Maps") && p.shortText().equals("3/12"), p.progressText() + " " + p.percent());
    p.starting(null);
    check("no name: just the count", p.progressText().equals("3 of 12"), p.progressText());
    for (int i = 0; i < 10; i++) p.finished(i % 5 != 0);
    check("all done: the place stays at 12 of 12 and the bar is full; the count is not past the end", p.done() == 12 && p.position() == 12 && p.percent() == 100 && p.progressText().equals("12 of 12"), p.progressText());
    p.finished(true); p.finished(false);
    check("a finished() past the last app changes nothing", p.done() == 12 && p.ok() + p.failed() == 12);
    check("ok and failed are counted apart (2 + 10 with 2 failed on i = 0 and 5)", p.ok() == 10 && p.failed() == 2, p.ok() + "/" + p.failed());
    QuickStopRun none = run(OWN, MEDIA);
    check("nothing to stop: 0 apps, place 0, 0%, no division by zero", none.total() == 0 && none.position() == 0 && none.percent() == 0 && none.shortText().equals("0/0"));
    QuickStopRun one = run("only.app");
    one.starting("Only");
    check("one app: 1 of 1, 0% until it is done, 100% after", one.percent() == 0 && one.progressText().equals("1 of 1 · Only") && one.percent() == 0);
    one.finished(true);
    check("one app done: 100%", one.percent() == 100 && one.position() == 1);

    // ---- the throttle
    QuickStopRun t = run("a.b");
    check("throttle: the first post is always allowed", t.shouldPost(1000));
    check("throttle: not again within 250 ms", !t.shouldPost(1100) && !t.shouldPost(1249));
    check("throttle: again at 250 ms, then counted from that post", t.shouldPost(1250) && !t.shouldPost(1400) && t.shouldPost(1500));
    check("throttle: a clock that went back is not stuck", t.shouldPost(100));
    check("throttle: the first post at time 0 is allowed", run("a.b").shouldPost(0));

    // ---- Cancel, the end of the run, a swiped notification
    QuickStopRun c = run("a.b", "c.d");
    check("a new run is open and not cancelled", c.open() && !c.cancelled());
    c.hide();
    check("swiped away: not shown again, the run itself is not cancelled", !c.open() && !c.cancelled());
    QuickStopRun e = run("a.b");
    e.close();
    check("closed (the run is over): not shown again, not cancelled", !e.open() && !e.cancelled());
    QuickStopRun x = run("a.b", "c.d", "e.f");
    x.finished(true);
    x.cancel();
    check("Cancel: cancelled and closed at once", x.cancelled() && !x.open());
    check("Cancel: the words say how far it got ('Cancelled after 1 of 3 apps')", x.summary().equals("Cancelled after 1 of 3 apps"), x.summary());

    // ---- the words when it is over
    QuickStopRun s1 = run("a.b", "c.d", "e.f");
    for (int i = 0; i < 3; i++) s1.finished(true);
    check("summary: all stopped", s1.summary().equals("Force-stopped 3 apps"), s1.summary());
    QuickStopRun s2 = run("a.b");
    s2.finished(true);
    check("summary: one app, singular", s2.summary().equals("Force-stopped 1 app"), s2.summary());
    QuickStopRun s3 = run("a.b", "c.d", OWN, MEDIA);
    s3.finished(true); s3.finished(false);
    check("summary: a failure and the apps left alone are counted", s3.summary().equals("Force-stopped 1 app, 1 failed, 2 left alone"), s3.summary());
    QuickStopRun s4 = run("a.b", "c.d", WEBVIEW);
    s4.finished(false);
    s4.cancel();
    check("summary: cancelled with a failure and an app left alone", s4.summary().equals("Cancelled after 1 of 2 apps, 1 failed, 1 left alone"), s4.summary());
    check("summary: an empty list", run().summary().equals("The quick list has no apps to stop"), run().summary());
    check("summary: only apps that are left alone (one)", run(OWN).summary().equals("Nothing to stop: the only app in the list is left alone on purpose"), run(OWN).summary());
    check("summary: only apps that are left alone (several)", run(OWN, MEDIA, WEBVIEW).summary().equals("Nothing to stop: the apps in the list are left alone on purpose"), run(OWN, MEDIA, WEBVIEW).summary());

    // ---- one run at a time
    QuickStopRun a = run("a.b"), b = run("c.d");
    check("begin: the first run starts", QuickStopRun.begin(a, 1000) && QuickStopRun.active() == a);
    check("begin: a second tap while it goes does not start another", !QuickStopRun.begin(b, 5000) && QuickStopRun.active() == a);
    check("begin: the same run again is accepted (nothing to refuse)", QuickStopRun.begin(a, 5000));
    QuickStopRun.end(b);
    check("end: another run's end does not clear this one", QuickStopRun.active() == a);
    check("begin: a run that has not ended after ten minutes is taken to be dead, the next tap starts", QuickStopRun.begin(b, 5000 + QuickStopRun.STALE_MS) && QuickStopRun.active() == b);
    check("begin: a run just under ten minutes old still blocks", !QuickStopRun.begin(a, 5000 + QuickStopRun.STALE_MS + QuickStopRun.STALE_MS - 1));
    check("cancelActive: cancels the run that is going", QuickStopRun.cancelActive() && b.cancelled() && !a.cancelled());
    QuickStopRun.end(b);
    check("end: the guard is free again", QuickStopRun.active() == null && QuickStopRun.begin(a, 1));
    QuickStopRun.end(a);
    check("cancelActive: false when nothing is going", !QuickStopRun.cancelActive());

    // ---- the Kill Apps tile's lit state follows the run (the tile reads tileActive() in onStartListening)
    check("tile: idle (no run) is not lit", QuickStopRun.active() == null && !QuickStopRun.tileActive());
    QuickStopRun g = run("a.b");
    check("tile: lit while a run is going", QuickStopRun.begin(g, 1) && QuickStopRun.tileActive());
    QuickStopRun.end(g);
    check("tile: back to not lit when the run ends", QuickStopRun.active() == null && !QuickStopRun.tileActive());

    // ---- the tap on the notification
    check("open sheet at create: a plain start from the notification opens it", QuickStopRun.openAtCreate(false, 0));
    check("open sheet at create: not after a recreate or a restore (the same old intent comes back)", !QuickStopRun.openAtCreate(true, 0));
    check("open sheet at create: not when the task is started again from Recents", !QuickStopRun.openAtCreate(false, IncomingInstall.FLAG_LAUNCHED_FROM_HISTORY) && QuickStopRun.openAtCreate(false, 0x10000000));

    // ---- threads: every app counted once, and Cancel while another thread posts
    final QuickStopRun big = run(manyPackages(200));
    Thread[] ts = new Thread[8];
    for (int i = 0; i < ts.length; i++) { ts[i] = new Thread(new Runnable() { public void run() { for (int k = 0; k < 40; k++) big.finished(true); } }); ts[i].start(); }
    for (Thread th : ts) th.join();
    check("8 threads finishing apps: each counted once, none past the end", big.done() == 200 && big.ok() == 200 && big.failed() == 0 && big.percent() == 100, big.done() + "");
    // what QuickStopNotice.show does, on one thread, while Cancel comes from another: once the run is closed, no post is made
    final QuickStopRun racing = run(manyPackages(50));
    final boolean[] closedSeen = {false}, postedAfterClose = {false};
    final int[] posted = {0};
    Thread poster = new Thread(new Runnable() { public void run() {
      for (int k = 0; k < 200000; k++) {
        synchronized (racing) {
          if (racing.open()) { posted[0]++; if (closedSeen[0]) postedAfterClose[0] = true; }
          else closedSeen[0] = true;
        }
      }
    } });
    Thread canceller = new Thread(new Runnable() { public void run() { try { Thread.sleep(1); } catch (InterruptedException ignored) {} racing.cancel(); } });
    poster.start(); canceller.start(); poster.join(); canceller.join();
    check("Cancel while posts are going: the run stays closed, nothing is posted after it", racing.cancelled() && !racing.open() && !postedAfterClose[0] && closedSeen[0], posted[0] + " posts");

    sourceScan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  static String[] manyPackages(int count) {
    String[] a = new String[count];
    for (int i = 0; i < count; i++) a[i] = "app.number" + i;
    return a;
  }

  // ------------------------------------------------------------------------------------------ the wiring, read from the source

  static File findUp(String rel) {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, rel);
      if (f.exists()) return f;
    }
    return null;
  }

  static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

  static String bodyOf(String src, String sig) {
    int i = src.indexOf(sig);
    if (i < 0 || src.indexOf(sig, i + 1) >= 0) return null;
    int open = src.indexOf('{', i), depth = 0, k = open;
    for (; k < src.length(); k++) { char ch = src.charAt(k); if (ch == '{') depth++; else if (ch == '}') { depth--; if (depth == 0) break; } }
    return src.substring(i, k + 1);
  }

  static int count(String s, String what) { int c = 0, i = 0; while ((i = s.indexOf(what, i)) >= 0) { c++; i += what.length(); } return c; }

  static void sourceScan() throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    File manifestFile = findUp("AndroidManifest.xml");
    File pageFile = findUp("assets/index.html");
    if (dir == null || manifestFile == null || pageFile == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String m = read(new File(dir, "MainActivity.java"));
    String nt = read(new File(dir, "QuickStopNotice.java"));
    String rc = read(new File(dir, "QuickStopReceiver.java"));
    String mf = read(manifestFile);
    String h = read(pageFile);

    // MainActivity: the run
    String stop = bodyOf(m, "private String quickStopList() throws Exception {");
    check("source: quickStopList takes the apps from QuickStopRun, never from the raw list", stop != null && stop.contains("for (String pkg : run.targets())") && !stop.contains("pkgs.optString(i), \"") && count(stop, "executeAppAction(\"force_stop\"") == 1);
    check("source: the leave-alone set is this app's own package and the phone's WebView", stop != null && stop.contains("QuickStopRun.protectedPackages(getPackageName(), webViewPackageName())"));
    check("source: one run at a time (begin), freed in finally (end), the notification cleared in the same finally", stop != null && stop.contains("QuickStopRun.begin(run,") && stop.indexOf("finally {") > stop.indexOf("QuickStopRun.begin(run,") && stop.indexOf("QuickStopRun.end(run)") > stop.indexOf("finally {") && stop.indexOf("QuickStopNotice.clear(app, run)") > stop.indexOf("finally {"));
    check("source: Cancel is looked at before each app, and the notification is posted through the throttle", stop != null && stop.indexOf("if (run.cancelled()) break;") > 0 && stop.indexOf("if (run.cancelled()) break;") < stop.indexOf("executeAppAction(\"force_stop\"") && stop.contains("if (run.shouldPost(android.os.SystemClock.elapsedRealtime())) QuickStopNotice.show(app, run);"));
    check("source: the notification uses the application context (it does not hold the headless window)", stop != null && stop.contains("final Context app = getApplicationContext();") && !stop.contains("QuickStopNotice.show(this"));
    check("source: the toast says what the run reports (summary)", stop != null && stop.contains("return run.summary();"));
    check("source: the WebView package comes from WebView.getCurrentWebViewPackage, and a phone that throws gives a blank name", m.contains("PackageInfo pi = WebView.getCurrentWebViewPackage();") && bodyOf(m, "private static String webViewPackageName() {") != null && bodyOf(m, "private static String webViewPackageName() {").contains("catch (Throwable t)"));

    // MainActivity: the tap on the notification
    String take = bodyOf(m, "private void takeQuickOpen(Intent intent) {");
    String deliver = bodyOf(m, "private void deliverPendingQuickOpen() {");
    check("source: a tap is taken out of the intent (not opened again by a recreate) and never in the headless window", take != null && take.contains("intent.removeExtra(QuickActions.EXTRA_OPEN_QUICK_LIST);") && take.contains("isHeadless()"));
    check("source: the page gets it once it is ready, as window.onQuickStopOpen(id)", deliver != null && deliver.contains("if (!pageReady || pendingQuickOpen == null) return;") && deliver.contains("window.onQuickStopOpen && window.onQuickStopOpen(\" + JSONObject.quote(id) + \")"));
    check("source: handed over at create (once), at a new intent and when the page finishes loading", m.contains("QuickStopRun.openAtCreate(savedInstanceState != null, launch.getFlags())) takeQuickOpen(launch);") && bodyOf(m, "protected void onNewIntent(Intent intent) {").contains("takeQuickOpen(intent);") && m.contains("deliverPendingInstall();\n                deliverPendingQuickOpen();"));

    // the notification
    check("source: the notification channel is named after the tile (Kill Apps) and keeps its id, so Android renames the old one in place (T-005)", nt.contains("static final String CHANNEL = \"quick_stop\";") && nt.contains("new NotificationChannel(CHANNEL, \"Kill Apps\", ") && !nt.contains("\"Stop apps\""));
    check("source: the notification is not cancelled by a tap (no autoCancel: the run goes on)", !nt.contains("setAutoCancel") && nt.contains("setOngoing(true)"));
    check("source: Cancel is a button, the body opens the app on the quick list, a swipe is heard", nt.contains("\"Cancel\"") && nt.contains("QuickActions.EXTRA_OPEN_QUICK_LIST, run.listId()") && nt.contains("b.setDeleteIntent(") && nt.contains("b.setContentIntent("));
    check("source: the intents are immutable and explicit (the receiver and MainActivity by class)", count(nt, "PendingIntent.FLAG_IMMUTABLE") == 1 && nt.contains("new Intent(c, QuickStopReceiver.class)") && nt.contains("new Intent(c, MainActivity.class)"));
    String show = bodyOf(nt, "static void show(Context c, QuickStopRun run) {");
    String clear = bodyOf(nt, "static void clear(Context c, QuickStopRun run) {");
    String cancelRun = bodyOf(nt, "static void cancelRun(Context c) {");
    check("source: posting looks at the run under its monitor and never posts a closed run", show != null && show.contains("synchronized (run) {") && show.indexOf("if (!run.open()) return;") > show.indexOf("synchronized (run) {") && show.indexOf("nm.notify(ID,") > show.indexOf("if (!run.open()) return;"));
    check("source: clearing closes the run and takes the notification away under the same monitor", clear != null && clear.contains("synchronized (run) {") && clear.indexOf("run.close();") > 0 && clear.indexOf("dismiss(c);") > clear.indexOf("run.close();"));
    check("source: Cancel cancels the run and takes the notification away under the run's monitor, and also clears one whose run is gone", cancelRun != null && cancelRun.contains("synchronized (r) {") && cancelRun.indexOf("r.cancel();") > 0 && cancelRun.lastIndexOf("dismiss(c);") > cancelRun.indexOf("r.cancel();") && cancelRun.contains("if (r == null) { dismiss(c); return; }"));
    check("source: a Live Update is asked for by reflection only, on Android 16 and later, and a failure builds the plain notification", nt.contains("\"canPostPromotedNotifications\"") && nt.contains("\"setRequestPromotedOngoing\"") && nt.contains("\"setShortCriticalText\"") && nt.contains("android.app.Notification$ProgressStyle") && nt.contains("catch (Throwable notAsLiveUpdate)") && nt.contains("SDK_INT >= 36") && !nt.contains("import android.app.Notification.ProgressStyle"));
    check("source: the receiver is Cancel and the swipe, nothing else", rc.contains("QuickStopNotice.ACTION_CANCEL.equals(action)) QuickStopNotice.cancelRun(ctx)") && rc.contains("QuickStopNotice.ACTION_DISMISSED.equals(action)) QuickStopNotice.hideRun()") && count(rc, "else if") == 1);

    // the bridge: the Live updates row of the permissions sheet
    String status = bodyOf(m, "public String getPermissionStatus() {");
    String live = bodyOf(m, "public String requestLiveUpdatesAccess() {");
    String grantAll = bodyOf(m, "public String grantAllPermissions() {");
    String commands = bodyOf(m, "public String getGrantCommands() {");
    check("source: the app reports live_updates only where Live Updates exist (Android 16 and later), from the phone's own check", status != null && status.contains("if (QuickStopNotice.liveSupported()) o.put(\"live_updates\", QuickStopNotice.liveAllowed(MainActivity.this));"));
    check("source: Live updates asks Notifications first (a working mode by command, otherwise Android's dialog) and returns that answer until it is granted, then opens the phone's Live updates screen", live != null && live.indexOf("requestNotificationAccess()") > 0 && live.indexOf("return asked;") > live.indexOf("requestNotificationAccess()") && live.indexOf("QuickStopNotice.liveSettingsIntent(MainActivity.this)") > live.indexOf("return asked;") && live.contains("ACTION_APP_NOTIFICATION_SETTINGS") && live.contains("openOwnAppInfo()") && live.contains("return \"settings\";"));
    check("source: no command is made up for it: 'Allow all' by command and the copyable adb commands do not name Live updates", grantAll != null && commands != null && !grantAll.contains("PROMOTED") && !grantAll.contains("live") && !commands.contains("PROMOTED") && !commands.contains("live"));

    // the page: the tap, and the permissions sheet
    String open = bodyOf(h, "function onQuickStopOpen(listId) {");
    check("source: the page opens Saved Applications and the sheet of the list (the quick list when the id is gone) and stops, saves and changes nothing", open != null && open.contains("switchView('saved-lists')") && open.contains("customLists.find(x => x.id === quickListId)") && open.contains("openListEditorModal(l.id)") && !open.contains("executeAppAction") && !open.contains("setQuickList") && !open.contains("saveCustomLists") && h.contains("window.onQuickStopOpen = onQuickStopOpen;"));
    check("source: a Saved Applications tab that was turned off is turned on again before the switch, with a toast that says so", open != null && open.indexOf("tabIsOn('saved-lists')") >= 0 && open.indexOf("tabConfig.off.filter") > 0 && open.indexOf("tabConfig.off.filter") < open.indexOf("switchView('saved-lists')") && open.contains("showToast('Saved Applications was turned off. It is on again to show this list.')"));
    check("source: the Live updates row is known (only where the app reports it), has no command, and follows Notifications", h.contains("{ key: 'live_updates', name: 'Live updates', perm: 'POST_PROMOTED_NOTIFICATIONS',") && h.indexOf("{ key: 'live_updates'") > h.indexOf("{ key: 'notifications'") && h.indexOf("{ key: 'live_updates'") < h.indexOf("{ key: 'storage_legacy'") && h.contains("known: true, noCommand: true }"));
    String permAllow = bodyOf(h, "function permAllow(key) {");
    check("source: Allow on Live updates calls the app's request; when that is Android's dialog, the screen follows once Notifications is allowed (and only then)", permAllow != null && permAllow.contains("b.requestLiveUpdatesAccess()") && permAllow.contains("if (answer === 'dialog') permThen = key;") && h.contains("if (then && then === waited && permStatus.notifications && !permStatus[waited]) setTimeout(() => permAllow(waited), 300);"));
    check("source: nothing that has no command restarts the app or makes the one-time offer", h.contains("PERM_DEFS.filter(d => !d.noCommand).map(d => (permStatus[d.key] ? '1' : '0')).join('')") && h.contains("visiblePermDefs().every(d => permStatus[d.key] || d.noCommand)"));

    // the manifest
    check("source: the manifest declares the Live Updates permission once (approved by the owner) and the receiver, not exported", count(mf, "<uses-permission android:name=\"android.permission.POST_PROMOTED_NOTIFICATIONS\" />") == 1 && mf.contains("android:name=\".QuickStopReceiver\"\n            android:exported=\"false\" />"));

    // the Kill Apps tile: label, lit when the panel is opened during a run (a passive tile), long-press opens the app (QS_TILE_PREFERENCES)
    String tile = read(new File(dir, "StopListTileService.java"));
    check("source: the tile is labelled Kill Apps and reads its lit state from the run", tile.contains("setLabel(\"Kill Apps\")") && tile.contains("QuickStopRun.tileActive()") && !tile.contains("\"Stop apps\""));
    check("source: the manifest labels the tile Kill Apps and declares a QS_TILE_PREFERENCES activity (long-press opens the app)", mf.contains("android:name=\".StopListTileService\"") && mf.contains("android:label=\"Kill Apps\"") && mf.contains("android.service.quicksettings.action.QS_TILE_PREFERENCES"));
    int aliasStart = mf.indexOf(".TilePreferencesActivity"), aliasEnd = mf.indexOf("</activity-alias>", aliasStart);
    String alias = aliasStart >= 0 && aliasEnd > aliasStart ? mf.substring(aliasStart, aliasEnd) : "";
    check("source: the QS_TILE_PREFERENCES alias is well-formed (exported, DEFAULT category, targets MainActivity) so SystemUI can launch it on long-press", alias.contains("android:targetActivity=\".MainActivity\"") && alias.contains("android:exported=\"true\"") && alias.contains("android.service.quicksettings.action.QS_TILE_PREFERENCES") && alias.contains("android.intent.category.DEFAULT"));
    // T-003: TileService.requestListeningState does nothing for a passive tile, so the app does not pretend to push the light with it
    int tileAt = mf.indexOf("android:name=\".StopListTileService\""), tileEnd = tileAt < 0 ? -1 : mf.indexOf("</service>", tileAt);
    String tileBlock = tileEnd < 0 ? null : mf.substring(tileAt, tileEnd);
    check("source: the Kill Apps tile is passive (no ACTIVE_TILE meta-data), so Android calls onStartListening on every panel open", tileBlock != null && !tileBlock.contains("ACTIVE_TILE"));
    check("source: the tile reads the run's state in onStartListening, the only place a passive tile is redrawn", tile.contains("public void onStartListening()") && tile.contains("QuickStopRun.tileActive() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE"));
    String finishQuick = bodyOf(m, "private void finishQuickAction(final String message) {");
    check("source: neither the start nor the end of a run sends requestListeningState to StopListTileService (a no-op for a passive tile)", stop != null && finishQuick != null && !stop.contains("requestListeningState(") &&!finishQuick.contains("StopListTileService") && !m.contains("StopListTileService.class"));
  }
}
