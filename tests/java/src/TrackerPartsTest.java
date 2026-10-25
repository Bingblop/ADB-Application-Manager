package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v7.16.2: an app's tracker parts and their switches. The logic (TrackerParts: which part belongs to which tracker, on whole name segments only;
 * the action strings and their ids; which part needs a command; the report) runs here on a small hand-made list. The wiring (MainActivity: the gate
 * of the switch, the read-back that decides success, the batch action; the page: the lock, the funnel, the batch buttons, the History's Undo) is read
 * from the source, because it needs Android classes.
 */
public class TrackerPartsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void check(String what, boolean ok, Object detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + detail); } }

  static final String DB = "{\"meta\":{\"retrieved\":\"2026-10-03\"},\"trackers\":{"
      + "\"49\":{\"id\":49,\"name\":\"Google Firebase Analytics\",\"code_signature\":\"com.google.firebase.analytics|com.google.android.gms.measurement\",\"categories\":[\"Analytics\"]},"
      + "\"27\":{\"id\":27,\"name\":\"Google CrashLytics\",\"code_signature\":\"io.fabric.|com.crashlytics\",\"categories\":[\"Crash reporting\"]},"
      + "\"312\":{\"id\":312,\"name\":\"Google AdMob\",\"code_signature\":\"com.google.android.gms.ads.identifier|com.google.android.gms.ads\",\"categories\":[\"Advertisement\"]},"
      + "\"313\":{\"id\":313,\"name\":\"Google Ads\",\"code_signature\":\"com.google.android.gms.ads\",\"categories\":[\"Advertisement\"]},"
      + "\"90\":{\"id\":90,\"name\":\"Tune\",\"code_signature\":\"com.tune\",\"categories\":[\"Analytics\"]},"
      + "\"91\":{\"id\":91,\"name\":\"Sizmek\",\"code_signature\":\".sizmek.\",\"categories\":[\"Advertisement\"]},"
      + "\"92\":{\"id\":92,\"name\":\"Short\",\"code_signature\":\"ads\",\"categories\":[]}"
      + "}}";

  public static void main(String[] args) throws Exception {
    TrackerDb db = TrackerDb.parse(DB);

    // ---- which tracker a part belongs to
    check("a part under a tracker's package belongs to it", name(db, "com.google.android.gms.measurement.AppMeasurementReceiver").equals("Google Firebase Analytics"));
    check("any of the signatures counts (io.fabric. for CrashLytics)", name(db, "io.fabric.sdk.android.services.FabricInitProvider").equals("Google CrashLytics"));
    check("a signature ending in '.' is a whole segment already", name(db, "io.fabric.Fabric").equals("Google CrashLytics"));
    check("the longest matching signature wins (gms.ads.identifier is AdMob, not the shorter gms.ads)", name(db, "com.google.android.gms.ads.identifier.AdvertisingIdService").equals("Google AdMob"));
    check("on a tie of length the lower id wins (gms.ads: AdMob 312 before Google Ads 313)", name(db, "com.google.android.gms.ads.AdActivity").equals("Google AdMob"));
    check("only whole name segments count: com.tune is not in TuneIn Radio's com.tunein.player.Main", TrackerParts.trackerOf(db, "com.tunein.player.Main") == -1);
    check("but com.tune is in com.tune.TuneTracker", name(db, "com.tune.TuneTracker").equals("Tune"));
    check("a signature starting with '.' may sit anywhere (.sizmek.)", name(db, "com.example.sizmek.AdView").equals("Sizmek"));
    check("an inner class ($) is a segment boundary", name(db, "com.tune$Receiver").equals("Tune"));
    check("a signature of three characters or fewer is never used (as Exodus does)", TrackerParts.trackerOf(db, "com.example.ads.Banner") == -1);
    check("the app's own parts belong to no tracker", TrackerParts.trackerOf(db, "com.example.app.MainActivity") == -1);
    check("null and empty names: none, no exception", TrackerParts.trackerOf(db, null) == -1 && TrackerParts.trackerOf(db, "") == -1 && TrackerParts.trackerOf(null, "x") == -1);
    check("onSegments: the match at the very end of the name counts", TrackerParts.onSegments("com.crashlytics", "com.crashlytics"));
    check("onSegments: a later occurrence on segments counts when an earlier one does not", TrackerParts.onSegments("com.tunex.com.tune.R", "com.tune"));
    check("onSegments: a segment that only starts with it does not count", !TrackerParts.onSegments("com.crashlyticsx.R", "com.crashlytics") && !TrackerParts.onSegments("xcom.crashlytics.R", "com.crashlytics"));

    // ---- grouping
    List<TrackerParts.Part> parts = new ArrayList<TrackerParts.Part>(Arrays.asList(
        new TrackerParts.Part("com.google.android.gms.measurement.AppMeasurementService", "service", true, null),
        new TrackerParts.Part("com.example.app.MainActivity", "activity", true, null),
        new TrackerParts.Part("com.google.android.gms.measurement.AppMeasurementReceiver", "receiver", true, Boolean.FALSE),
        new TrackerParts.Part("com.google.android.gms.measurement.AppMeasurementJobService", "service", false, null),
        new TrackerParts.Part("com.google.android.gms.ads.AdActivity", "activity", true, null),
        new TrackerParts.Part("io.fabric.sdk.android.services.FabricInitProvider", "provider", false, Boolean.TRUE)));
    List<TrackerParts.Group> g = TrackerParts.group(db, parts);
    List<String> order = new ArrayList<String>();
    for (TrackerParts.Group x : g) order.add(db.name[x.index] + ":" + x.parts.size());
    check("grouped by tracker in the order of the names, the app's own parts left out", order.toString().equals("[Google AdMob:1, Google CrashLytics:1, Google Firebase Analytics:3]"), order);
    TrackerParts.Group fa = g.get(2);
    check("each group's parts sorted by name", fa.parts.get(0).name.endsWith("AppMeasurementJobService") && fa.parts.get(1).name.endsWith("AppMeasurementReceiver") && fa.parts.get(2).name.endsWith("AppMeasurementService"));
    check("a group counts the parts that run and the parts switched off", fa.on() == 1 && fa.switchedOff() == 1, fa.on() + "/" + fa.switchedOff());
    check("null parts: no groups, no exception", TrackerParts.group(db, null).isEmpty());

    // ---- a part's state
    TrackerParts.Part shipOff = new TrackerParts.Part("x.A", "service", false, null), pmOff = new TrackerParts.Part("x.B", "service", true, Boolean.FALSE),
        pmOn = new TrackerParts.Part("x.C", "service", false, Boolean.TRUE), plain = new TrackerParts.Part("x.D", "service", true, null);
    check("on(): the override when there is one, else the manifest", !shipOff.on() && !pmOff.on() && pmOn.on() && plain.on());
    check("switchedOff(): only an explicit DISABLED override", !shipOff.switchedOff() && pmOff.switchedOff() && !pmOn.switchedOff() && !plain.switchedOff());
    check("to switch off, only a part that runs needs a command", TrackerParts.needs(plain, false) && TrackerParts.needs(pmOn, false) && !TrackerParts.needs(pmOff, false) && !TrackerParts.needs(shipOff, false));
    check("to switch on, only a part switched off with pm: never one the manifest ships off", TrackerParts.needs(pmOff, true) && !TrackerParts.needs(shipOff, true) && !TrackerParts.needs(plain, true) && !TrackerParts.needs(pmOn, true));

    // ---- the action strings
    check("isAction: trackers_off / trackers_on, alone or with ids", TrackerParts.isAction("trackers_off") && TrackerParts.isAction("trackers_on") && TrackerParts.isAction("trackers_off:49") && TrackerParts.isAction("trackers_on:1,2"));
    check("isAction: nothing else (freeze, a longer word, null)", !TrackerParts.isAction("freeze") && !TrackerParts.isAction("trackers_offx") && !TrackerParts.isAction("trackers") && !TrackerParts.isAction(null));
    check("enables: trackers_on only", TrackerParts.enables("trackers_on") && TrackerParts.enables("trackers_on:5") && !TrackerParts.enables("trackers_off"));
    check("ids: no colon means every tracker (null)", TrackerParts.ids("trackers_off") == null);
    check("ids: the ids in order, duplicates dropped, spaces allowed", Arrays.toString(TrackerParts.ids("trackers_off:49, 312,49")).equals("[49, 312]"));
    check("ids: anything that is not a list of whole numbers names nothing (empty: nothing is switched)", TrackerParts.ids("trackers_off:").length == 0 && TrackerParts.ids("trackers_off:4x").length == 0
        && TrackerParts.ids("trackers_off:-1").length == 0 && TrackerParts.ids("trackers_off:1,,2").length == 0 && TrackerParts.ids("trackers_off:1234567890").length == 0 && TrackerParts.ids(null).length == 0);
    StringBuilder many = new StringBuilder("trackers_off:");
    for (int i = 0; i <= TrackerParts.MAX_IDS; i++) many.append(i == 0 ? "" : ",").append(i);
    check("ids: more than MAX_IDS names nothing", TrackerParts.ids(many.toString()).length == 0);
    check("wanted: null is every tracker, else only the ids named", TrackerParts.wanted(db, fa, null) && TrackerParts.wanted(db, fa, new int[] { 49 }) && !TrackerParts.wanted(db, fa, new int[] { 312 }));

    // ---- the report
    List<String[]> none = new ArrayList<String[]>();
    List<String> noOut = new ArrayList<String>();
    check("report: an app with no tracker part says the trackers run inside its own code", TrackerParts.report(false, 0, none, 0, none, noOut).equals("No tracker parts in this app: the trackers it holds, if any, run inside its own code."));
    List<String[]> ch = new ArrayList<String[]>();
    ch.add(new String[] { "Google Firebase Analytics", "com.google.android.gms.measurement.AppMeasurementService" });
    ch.add(new String[] { "Google Firebase Analytics", "com.google.android.gms.measurement.AppMeasurementReceiver" });
    ch.add(new String[] { "Google AdMob", "com.google.android.gms.ads.AdActivity" });
    List<String[]> fl = new ArrayList<String[]>();
    fl.add(new String[] { "Google CrashLytics", "io.fabric.sdk.android.services.FabricInitProvider" });
    String rep = TrackerParts.report(false, 5, ch, 1, fl, Arrays.asList("Error: java.lang.SecurityException: Shell cannot change component state"));
    check("report: what was switched, by tracker; what was already so; every part Android did not change, with its answer", rep.equals(
        "Disabled 3 tracker parts:\n  Google Firebase Analytics: AppMeasurementService, AppMeasurementReceiver\n  Google AdMob: AdActivity\n1 part was already off.\n"
        + "Not changed by Android (1):\n  Google CrashLytics: FabricInitProvider - Error: java.lang.SecurityException: Shell cannot change component state"), rep);
    check("report: nothing to switch off", TrackerParts.report(false, 2, none, 2, none, noOut).equals("2 parts were already off."));
    check("report: nothing to switch back on", TrackerParts.report(true, 2, none, 0, none, noOut).equals("No tracker part was switched off, so nothing was enabled."));
    check("report: one part enabled (singular)", TrackerParts.report(true, 2, ch.subList(2, 3), 0, none, noOut).equals("Enabled 1 tracker part:\n  Google AdMob: AdActivity"));
    check("shortName: the last segment, the whole name when there is none", TrackerParts.shortName("a.b.C").equals("C") && TrackerParts.shortName("C").equals("C") && TrackerParts.shortName("a.").equals("a.") && TrackerParts.shortName(null).equals(""));

    sourceScan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  static String name(TrackerDb db, String cls) { int t = TrackerParts.trackerOf(db, cls); return t < 0 ? "-" : db.name[t]; }

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

  static void sourceScan() throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    File pageFile = findUp("assets/index.html");
    if (dir == null || pageFile == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String m = read(new File(dir, "MainActivity.java"));
    String h = read(pageFile);

    // MainActivity: the gate, the commands, the read-back
    String allowed = bodyOf(m, "private boolean trackerSwitchAllowed() {");
    check("source: the switch opens for Root, or a Shizuku server authorized as uid 0 or 1000 (as setComponentEnabled)", allowed != null && allowed.contains("\"root\".equals(trkMode)")
        && allowed.contains("isShizukuAuthorized()") && allowed.contains("trkSzUid == 0 || trkSzUid == 1000") && !allowed.contains("2000"));
    String set = bodyOf(m, "private JSONObject setTrackerParts(String pkg, int[] ids, boolean enable) throws Exception {");
    check("source: setTrackerParts checks the package name and the gate before any command", set != null && set.indexOf("isPackageName(pkg)") < set.indexOf("executeShell(")
        && set.indexOf("if (!trackerSwitchAllowed())") < set.indexOf("executeShell(") && set.contains("needsRoot"));
    check("source: an empty id list switches nothing (not every tracker)", set != null && set.contains("if (ids != null && ids.length == 0)") && set.indexOf("ids.length == 0") < set.indexOf("executeShell("));
    check("source: every command is ShellArgs.componentCommand (pm enable / pm disable on one part), nothing else", set != null && set.contains("ShellArgs.componentCommand(pkg, p.name, enable)") && set.contains("executeShell(cmd)"));
    check("source: success is the part's state read back (componentOverrideState), not what pm printed", set != null && set.contains("componentOverrideState(pkg, p.name)")
        && set.contains("state != null && state.booleanValue() == enable") && set.contains("put(\"ok\", failed.isEmpty())"));
    String read = bodyOf(m, "private List<TrackerParts.Group> readTrackerParts(String pkg, Trackers t) throws PackageManager.NameNotFoundException {");
    check("source: the parts are read with the disabled ones too (MATCH_DISABLED_COMPONENTS), all four kinds", read != null && read.contains("MATCH_DISABLED_COMPONENTS") && read.contains("GET_ACTIVITIES")
        && read.contains("GET_RECEIVERS") && read.contains("GET_SERVICES") && read.contains("GET_PROVIDERS"));
    check("source: the batch runner's action trackers_off / trackers_on goes to setTrackerParts", m.contains("if (TrackerParts.isAction(action)) {") && m.contains("setTrackerParts(pkg, TrackerParts.ids(action), TrackerParts.enables(action))"));
    String ps = bodyOf(m, "public String trackerPartsSet(final String pkg, final String idsJson, final boolean enable, final String token) {");
    check("source: trackerPartsSet runs off the page's thread, one run at a time, and always answers (busy cleared in finally)", ps != null && ps.contains("submitJob(") && ps.contains("return \"busy\"")
        && ps.contains("finally") && ps.contains("trackerPartsBusy = false") && ps.contains("window.onTrackerPartsSet"));

    // the page: the gate, the funnel, the batch, the History
    String sw = bodyOf(h, "function trkSwitch(ids, enable) {");
    check("source: the box's switch goes the Root way off the gate and sends nothing then", sw != null && sw.contains("if (!isRootActive()) { promptRootThenOpen(") && sw.indexOf("promptRootThenOpen(") < sw.indexOf("trackerPartsSet("));
    check("source: switching parts of a system app off asks first", sw != null && sw.contains("isSystemApp") && sw.contains("the system app") && sw.indexOf("confirm(") < sw.indexOf("trackerPartsSet("));
    String ctl = bodyOf(h, "function trkCtlHtml(pkg) {"), parts = bodyOf(h, "function trkPartsHtml(pkg, t) {");
    check("source: the box's switches carry the component lock (compToggleLocked, NEEDS_ROOT_COMP)", ctl != null && parts != null && ctl.contains("compToggleLocked()") && ctl.contains("NEEDS_ROOT_COMP")
        && parts.contains("compToggleLocked()") && parts.contains("NEEDS_ROOT_COMP"));
    String batch = bodyOf(h, "async function batchTrackers(enable) {");
    check("source: Disable Trackers in the batch menu is gated, then confirmed; Enable Trackers runs at once", batch != null && batch.contains("if (!isRootActive())") && batch.contains("promptRootThenOpen(")
        && batch.contains("requestBatchConfirmation('trackers_off', arr)") && batch.contains("runBatchAction('trackers_on', arr)"));
    check("source: History: labels for both, and each one's Undo is the other", h.contains("trackers_off: 'trackers_on', trackers_on: 'trackers_off'") && h.contains("trackers_off: 'Disabled trackers in', trackers_on: 'Enabled trackers in'"));
    String again = bodyOf(h, "function batchRunAgain(onlyFailed) {");
    check("source: Run again of a tracker batch is gated and confirms Disable again", again != null && again.contains("(b.action === 'trackers_off' || b.action === 'trackers_on') && !isRootActive()") && again.contains("'trackers_off'].includes(b.action)"));
    check("source: a change of working mode redraws the locks (rootOnModeChange -> trkOnModeChange)", h.contains("renderRootFeatures(); trkOnModeChange(); }"));
    check("source: Root Features lists \"Trackers in apps\" on the same gate", h.contains("rootFeatureRow('Trackers in apps',") && h.contains("'rootManageTrackers()', isRootActive())"));
  }
}
