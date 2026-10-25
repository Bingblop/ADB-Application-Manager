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
      + "\"92\":{\"id\":92,\"name\":\"Short\",\"code_signature\":\"ads\",\"categories\":[]},"
      + "\"93\":{\"id\":93,\"name\":\"Cheetah Ads\",\"code_signature\":\"com.cmcm.\",\"categories\":[\"Advertisement\"]}"
      + "}}";

  public static void main(String[] args) throws Exception {
    TrackerDb db = TrackerDb.parse(DB);

    // ---- which tracker a part belongs to
    check("a part under a tracker's package belongs to it", name(db, "com.google.android.gms.measurement.AppMeasurementReceiver").equals("Google Firebase Analytics"));
    check("any of the signatures counts (io.fabric. for CrashLytics)", name(db, "io.fabric.sdk.android.services.FabricInitProvider").equals("Google CrashLytics"));
    check("a signature ending in '.' is a whole segment already", name(db, "io.fabric.Fabric").equals("Google CrashLytics"));
    check("the longest matching signature wins (gms.ads.identifier is AdMob, not the shorter gms.ads)", name(db, "com.google.android.gms.ads.identifier.AdvertisingIdService").equals("Google AdMob"));
    check("on a tie of length the lower id wins (gms.ads: AdMob 312 before Google Ads 313)", name(db, "com.google.android.gms.ads.AdActivity").equals("Google AdMob"));
    check("only whole name segments count: com.tune is not in TuneIn Radio's com.tunein.player.Main", TrackerParts.trackerOf(db, APP, "com.tunein.player.Main") == -1);
    check("but com.tune is in com.tune.TuneTracker", name(db, "com.tune.TuneTracker").equals("Tune"));
    check("a signature starting with '.' may sit anywhere (.sizmek.)", name(db, "com.example.sizmek.AdView").equals("Sizmek"));
    check("an inner class ($) is a segment boundary", name(db, "com.tune$Receiver").equals("Tune"));
    check("a signature of three characters or fewer is never used (as Exodus does)", TrackerParts.trackerOf(db, APP, "com.example.ads.Banner") == -1);
    check("the app's own parts belong to no tracker", TrackerParts.trackerOf(db, APP, "com.example.app.MainActivity") == -1);
    check("null and empty names: none, no exception", TrackerParts.trackerOf(db, APP, null) == -1 && TrackerParts.trackerOf(db, APP, "") == -1 && TrackerParts.trackerOf(null, APP, "x") == -1);
    check("onSegments: the match at the very end of the name counts", TrackerParts.onSegments("com.crashlytics", "com.crashlytics"));
    check("onSegments: a later occurrence on segments counts when an earlier one does not", TrackerParts.onSegments("com.tunex.com.tune.R", "com.tune"));
    check("onSegments: a segment that only starts with it does not count", !TrackerParts.onSegments("com.crashlyticsx.R", "com.crashlytics") && !TrackerParts.onSegments("xcom.crashlytics.R", "com.crashlytics"));

    // ---- the app's own code is never a tracker's (the app is the tracker's maker)
    check("ownCode: the app's package lies in the signature's namespace (LiveMe com.cmcm.live under Cheetah's com.cmcm.)", TrackerParts.ownCode("com.cmcm.live", "com.cmcm."));
    check("ownCode: the same without a trailing '.' (the Hound app com.hound.android.app under com.hound)", TrackerParts.ownCode("com.hound.android.app", "com.hound"));
    check("ownCode: the signature names a package inside the app's (WeChat's com.tencent.mm.plugin.location., Play services' gms.measurement)",
        TrackerParts.ownCode("com.tencent.mm", "com.tencent.mm.plugin.location.") && TrackerParts.ownCode("com.google.android.gms", "com.google.android.gms.measurement"));
    check("ownCode: the signature is the package itself", TrackerParts.ownCode("com.tune", "com.tune"));
    check("ownCode: whole segments only (TuneIn's com.tunein.player is not Tune's com.tune; com.cmcmx is not com.cmcm.)",
        !TrackerParts.ownCode("com.tunein.player", "com.tune") && !TrackerParts.ownCode("com.cmcmx.app", "com.cmcm.") && !TrackerParts.ownCode("com.google.android.gmsx", "com.google.android.gms.measurement"));
    check("ownCode: an unrelated app, and null or empty input: false", !TrackerParts.ownCode(APP, "com.google.android.gms.measurement") && !TrackerParts.ownCode(null, "com.tune")
        && !TrackerParts.ownCode("", "com.tune") && !TrackerParts.ownCode(APP, null) && !TrackerParts.ownCode(APP, ""));
    check("ownCode: a signature that may sit anywhere ('.sizmek.') is own code in an app whose package holds it", TrackerParts.ownCode("com.sizmek.app", ".sizmek.") && !TrackerParts.ownCode(APP, ".sizmek."));
    check("trackerOf: LiveMe's own parts are not Cheetah Ads, another app's com.cmcm. parts are", name(db, "com.cmcm.live", "com.cmcm.live.message.PushReceiver").equals("-")
        && name(db, "com.cmcm.ads.AdReceiver").equals("Cheetah Ads"));
    check("trackerOf: Google Play services' own measurement parts are not Firebase Analytics, an app's copy is",
        name(db, "com.google.android.gms", "com.google.android.gms.measurement.AppMeasurementReceiver").equals("-") && name(db, "com.google.android.gms.measurement.AppMeasurementReceiver").equals("Google Firebase Analytics"));
    check("trackerOf: every signature that overlaps the package is skipped (Play services' gms.ads parts are neither AdMob nor Google Ads), no other one is (its io.fabric. parts are still CrashLytics)",
        name(db, "com.google.android.gms", "com.google.android.gms.ads.AdActivity").equals("-") && name(db, "com.google.android.gms", "io.fabric.sdk.android.services.FabricInitProvider").equals("Google CrashLytics"));
    check("trackerOf: a null package skips the own-code test", name(db, null, "com.cmcm.live.X").equals("Cheetah Ads"));

    // ---- grouping
    List<TrackerParts.Part> parts = new ArrayList<TrackerParts.Part>(Arrays.asList(
        new TrackerParts.Part("com.google.android.gms.measurement.AppMeasurementService", "service", true, null),
        new TrackerParts.Part("com.example.app.MainActivity", "activity", true, null),
        new TrackerParts.Part("com.google.android.gms.measurement.AppMeasurementReceiver", "receiver", true, Boolean.FALSE),
        new TrackerParts.Part("com.google.android.gms.measurement.AppMeasurementJobService", "service", false, null),
        new TrackerParts.Part("com.google.android.gms.ads.AdActivity", "activity", true, null),
        new TrackerParts.Part("io.fabric.sdk.android.services.FabricInitProvider", "provider", false, Boolean.TRUE)));
    List<TrackerParts.Group> g = TrackerParts.group(db, APP, parts);
    List<String> order = new ArrayList<String>();
    for (TrackerParts.Group x : g) order.add(db.name[x.index] + ":" + x.parts.size());
    check("grouped by tracker in the order of the names, the app's own parts left out", order.toString().equals("[Google AdMob:1, Google CrashLytics:1, Google Firebase Analytics:3]"), order);
    TrackerParts.Group fa = g.get(2);
    check("each group's parts sorted by name", fa.parts.get(0).name.endsWith("AppMeasurementJobService") && fa.parts.get(1).name.endsWith("AppMeasurementReceiver") && fa.parts.get(2).name.endsWith("AppMeasurementService"));
    check("a group counts the parts that run and the parts switched off", fa.on() == 1 && fa.switchedOff() == 1, fa.on() + "/" + fa.switchedOff());
    check("null parts: no groups, no exception", TrackerParts.group(db, APP, null).isEmpty());

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
    check("isAction / enables: the parts= form too", TrackerParts.isAction("trackers_on:parts=a.B") && TrackerParts.enables("trackers_on:parts=a.B") && !TrackerParts.enables("trackers_off:parts=a.B"));
    check("ids: the parts= form names no ids (null: every tracker; the part names choose)", TrackerParts.ids("trackers_on:parts=com.a.B") == null);
    check("parts: no parts= means every part (null)", TrackerParts.parts("trackers_on") == null && TrackerParts.parts("trackers_on:49,312") == null && TrackerParts.parts(null) == null);
    check("parts: the names in order, duplicates dropped, inner classes ($) kept",
        TrackerParts.parts("trackers_on:parts=com.a.B,com.a.C$D,com.a.B").toString().equals("[com.a.B, com.a.C$D]"), TrackerParts.parts("trackers_on:parts=com.a.B,com.a.C$D,com.a.B"));
    check("parts: anything that is not a list of class names names nothing (empty: nothing is switched)", TrackerParts.parts("trackers_on:parts=").isEmpty()
        && TrackerParts.parts("trackers_on:parts=com.a.B,,com.a.C").isEmpty() && TrackerParts.parts("trackers_on:parts=com.a.B c").isEmpty()
        && TrackerParts.parts("trackers_on:parts=com.a.B;reboot").isEmpty() && TrackerParts.parts("trackers_on:parts=.a.B").isEmpty() && TrackerParts.parts("trackers_on:parts=com/a").isEmpty());
    StringBuilder manyParts = new StringBuilder("trackers_on:parts=");
    for (int i = 0; i <= TrackerParts.MAX_IDS; i++) manyParts.append(i == 0 ? "" : ",").append("a.P").append(i);
    check("parts: more than MAX_IDS names nothing", TrackerParts.parts(manyParts.toString()).isEmpty());
    StringBuilder longName = new StringBuilder("a");
    for (int i = 0; i < 300; i++) longName.append('b');
    check("parts: a name longer than 300 characters names nothing", TrackerParts.parts("trackers_on:parts=" + longName).isEmpty());
    check("wanted(part): null is every part, else only the names given", TrackerParts.wanted(fa.parts.get(0), null) && TrackerParts.wanted(fa.parts.get(0), Arrays.asList(fa.parts.get(0).name))
        && !TrackerParts.wanted(fa.parts.get(0), Arrays.asList(fa.parts.get(1).name)) && !TrackerParts.wanted(fa.parts.get(0), new ArrayList<String>()));

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
    check("missing: the names not reached, in the order asked; none when no names were asked",
        TrackerParts.missing(Arrays.asList("a.A", "a.B", "a.C"), new java.util.HashSet<String>(Arrays.asList("a.B"))).toString().equals("[a.A, a.C]")
        && TrackerParts.missing(null, new java.util.HashSet<String>()).isEmpty() && TrackerParts.missing(Arrays.asList("a.A"), null).toString().equals("[a.A]"));
    List<String[]> notListed = new ArrayList<String[]>();
    notListed.add(new String[] { TrackerParts.NOT_LISTED, "com.google.android.gms.measurement.AppMeasurementReceiver" });
    String undoRep = TrackerParts.report(true, 0, notListed, 0, 1, none, noOut);
    check("report: an Undo part no tracker covers now is still reported (not \"No tracker parts in this app\"), and a part the app lost is named", undoRep.equals(
        "Enabled 1 tracker part:\n  No longer on the tracker list: AppMeasurementReceiver\n1 part is no longer in the app."), undoRep);
    check("report: only parts the app lost", TrackerParts.report(true, 3, none, 0, 2, none, noOut).equals("2 parts are no longer in the app."));
    check("report: the old form is the new one with nothing gone", TrackerParts.report(false, 0, none, 0, none, noOut).equals(TrackerParts.report(false, 0, none, 0, 0, none, noOut)));
    check("shortName: the last segment, the whole name when there is none", TrackerParts.shortName("a.b.C").equals("C") && TrackerParts.shortName("C").equals("C") && TrackerParts.shortName("a.").equals("a.") && TrackerParts.shortName(null).equals(""));

    sourceScan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  static final String APP = "com.example.app";
  static String name(TrackerDb db, String cls) { return name(db, APP, cls); }
  static String name(TrackerDb db, String pkg, String cls) { int t = TrackerParts.trackerOf(db, pkg, cls); return t < 0 ? "-" : db.name[t]; }

  // ------------------------------------------------------------------------------------------ the wiring, read from the source

  static File findUp(String rel) {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, rel);
      if (f.exists()) return f;
    }
    return null;
  }

  /** Both pieces are in s, and a comes first (a missing piece fails: indexOf's -1 would otherwise come "before" anything). */
  static boolean before(String s, String a, String b) {
    if (s == null) return false;
    int i = s.indexOf(a), j = s.indexOf(b);
    return i >= 0 && j >= 0 && i < j;
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
    String set = bodyOf(m, "private JSONObject setTrackerParts(String pkg, int[] ids, List<String> only, boolean enable) throws Exception {");
    String one = bodyOf(m, "private void switchTrackerPart(String pkg, String tracker, TrackerParts.Part p, boolean enable, List<String[]> changed, int[] already,");
    check("source: setTrackerParts runs no command itself: every part goes through switchTrackerPart", set != null && !set.contains("executeShell(") && set.contains("switchTrackerPart(pkg, tracker, p, enable,"));
    check("source: setTrackerParts checks the package name and the gate before any part is switched", before(set, "isPackageName(pkg)", "switchTrackerPart(")
        && before(set, "if (!trackerSwitchAllowed())", "switchTrackerPart(") && set.contains("needsRoot"));
    check("source: an empty id list switches nothing (not every tracker)", before(set, "if (ids != null && ids.length == 0)", "switchTrackerPart("));
    check("source: an empty part list switches nothing (not every part)", before(set, "if (only != null && only.isEmpty())", "switchTrackerPart("));
    check("source: only the parts asked for are looked at, and each one reached is noted", before(set, "TrackerParts.wanted(t.db, g, ids)", "TrackerParts.wanted(p, only)")
        && before(set, "TrackerParts.wanted(p, only)", "seen.add(p.name)") && before(set, "seen.add(p.name)", "switchTrackerPart(pkg, tracker, p, enable,"));
    check("source: a part named by Undo that no tracker covers now is still looked up among the app's parts and switched, or counted as gone (never skipped)",
        before(set, "Set<String> missing = TrackerParts.missing(only, seen);", "readNamedParts(pkg, missing)") && set.contains("if (p == null) gone++;")
        && set.contains("switchTrackerPart(pkg, TrackerParts.NOT_LISTED, p, enable,") && set.contains("TrackerParts.report(enable, partsInApp, changed, already[0], gone, failed, failedOut)"));
    String named = bodyOf(m, "private Map<String, TrackerParts.Part> readNamedParts(String pkg, Set<String> names) throws PackageManager.NameNotFoundException {");
    check("source: readNamedParts reads every part of the app, the disabled ones too, and keeps only the names asked for, with their pm override", named != null && named.contains("MATCH_DISABLED_COMPONENTS")
        && named.contains("GET_ACTIVITIES") && named.contains("GET_RECEIVERS") && named.contains("GET_SERVICES") && named.contains("GET_PROVIDERS") && named.contains("!names.contains(c[0])")
        && named.contains("componentOverrideState(pkg, c[0])"));
    check("source: the answer names the parts changed (changedParts: what the History's Undo switches back)", set != null && set.contains("put(\"changedParts\", names)") && set.contains("for (String[] c : changed) names.put(c[1])"));
    check("source: a part is switched only when it needs it, and every command is ShellArgs.componentCommand (pm enable / pm disable on one part)",
        before(one, "if (!TrackerParts.needs(p, enable))", "ShellArgs.componentCommand(pkg, p.name, enable)") && before(one, "ShellArgs.componentCommand(pkg, p.name, enable)", "executeShell(cmd)")
        && one.indexOf("executeShell(") == one.lastIndexOf("executeShell("));
    check("source: success is the part's state read back (componentOverrideState), not what pm printed", before(one, "executeShell(cmd)", "componentOverrideState(pkg, p.name)")
        && one.contains("state != null && state.booleanValue() == enable") && set.contains("put(\"ok\", failed.isEmpty())"));
    String tar = bodyOf(m, "public String trackerActionResult(String action, String pkg) {");
    check("source: the History's Undo bridge checks the package name and the action before it runs, and answers the whole result (with changedParts)",
        before(tar, "isPackageName(pkg)", "trackerAction(pkg, action)") && before(tar, "TrackerParts.isAction(action)", "trackerAction(pkg, action)") && tar.contains("trackerAction(pkg, action).toString()")
        && m.contains("@JavascriptInterface\n        public String trackerActionResult("));
    String read = bodyOf(m, "private List<TrackerParts.Group> readTrackerParts(String pkg, Trackers t) throws PackageManager.NameNotFoundException {");
    check("source: the parts are read with the disabled ones too (MATCH_DISABLED_COMPONENTS), all four kinds", read != null && read.contains("MATCH_DISABLED_COMPONENTS") && read.contains("GET_ACTIVITIES")
        && read.contains("GET_RECEIVERS") && read.contains("GET_SERVICES") && read.contains("GET_PROVIDERS"));
    String ta = bodyOf(m, "private JSONObject trackerAction(String pkg, String action) throws Exception {");
    check("source: an action string goes to setTrackerParts with its ids, its part names and its direction", ta != null && ta.contains("setTrackerParts(pkg, TrackerParts.ids(action), TrackerParts.parts(action), TrackerParts.enables(action))"));
    String exec = bodyOf(m, "public String executeAppAction(String action, String pkg) {");
    check("source: a single app's action trackers_off / trackers_on (Undo) goes to trackerAction", exec != null && before(exec, "if (TrackerParts.isAction(action)) {", "trackerAction(pkg, action)"));
    check("source: the batch runner runs the tracker actions itself, and each row carries the parts changed", before(m, "if (TrackerParts.isAction(action)) {\n                                // the tracker switches run here directly", "tr = trackerAction(pkg, action)")
        && m.contains("trkParts = tr.optJSONArray(\"changedParts\")") && m.contains("if (trkParts != null) row.put(\"parts\", trkParts)"));
    check("source: the parts are matched with the app's package (its own code is never a tracker's)", read != null && read.contains("TrackerParts.group(t.db, pkg, parts)")
        && m.contains("TrackerParts.trackerOf(t.db, pkg, name) < 0"));
    String ps = bodyOf(m, "public String trackerPartsSet(final String pkg, final String idsJson, final boolean enable, final String token) {");
    check("source: trackerPartsSet runs off the page's thread, one run at a time, and always answers (busy cleared in finally)", ps != null && ps.contains("submitJob(") && ps.contains("return \"busy\"")
        && ps.contains("finally") && ps.contains("trackerPartsBusy = false") && ps.contains("window.onTrackerPartsSet"));

    // the page: the gate, the funnel, the batch, the History
    String sw = bodyOf(h, "function trkSwitch(ids, enable) {");
    check("source: the box's switch goes the Root way off the gate and sends nothing then", sw != null && sw.contains("if (!isRootActive()) { promptRootThenOpen(") && before(sw, "promptRootThenOpen(", "trackerPartsSet("));
    check("source: switching parts of a system app off asks first", sw != null && sw.contains("isSystemApp") && sw.contains("the system app") && before(sw, "confirm(", "trackerPartsSet("));
    String ctl = bodyOf(h, "function trkCtlHtml(pkg) {"), parts = bodyOf(h, "function trkPartsHtml(pkg, t) {");
    check("source: the box's switches carry the component lock (compToggleLocked, NEEDS_ROOT_COMP)", ctl != null && parts != null && ctl.contains("compToggleLocked()") && ctl.contains("NEEDS_ROOT_COMP")
        && parts.contains("compToggleLocked()") && parts.contains("NEEDS_ROOT_COMP"));
    String batch = bodyOf(h, "async function batchTrackers(enable) {");
    check("source: Disable Trackers in the batch menu is gated, then confirmed; Enable Trackers runs at once", batch != null && batch.contains("if (!isRootActive())") && batch.contains("promptRootThenOpen(")
        && batch.contains("requestBatchConfirmation('trackers_off', arr)") && batch.contains("runBatchAction('trackers_on', arr)"));
    check("source: History: labels for both, and each one's Undo is the other", h.contains("trackers_off: 'trackers_on', trackers_on: 'trackers_off'") && h.contains("trackers_off: 'Disabled trackers in', trackers_on: 'Enabled trackers in'"));
    String hu = bodyOf(h, "function histUndoAction(i) {");
    check("source: a tracker step's Undo names exactly the parts it changed (even when Android refused others), a step that changed none has no Undo, any other step needs success",
        before(hu, "if (i.action === 'trackers_off' || i.action === 'trackers_on') return Array.isArray(i.parts) && i.parts.length ? undo + ':parts=' + i.parts.join(',') : '';", "return i.success ? undo : '';"));
    String uh = bodyOf(h, "function undoHistory(id) {");
    check("source: Undo runs histUndoAction's action (not the bare other switch), and Undo is offered by the same test", uh != null && uh.contains("filter(i => histUndoAction(i))")
        && uh.contains("run = histUndoAction(i)") && uh.contains("performDetailedBatchAction(run, [i.pkg])") && h.contains("const undoable = e.items.some(i => histUndoAction(i));"));
    check("source: Undo of a tracker step is gated as the switches are (the Root way first, then the Undo again), before anything runs",
        before(uh, "if (items.some(isTrk) && !isRootActive()) { promptRootThenOpen(txT('Switching trackers off or on')).then(ok => { if (ok) undoHistory(id); }); return; }", "setTimeout("));
    check("source: the Undo step keeps the parts its own run changed (trackerActionResult's changedParts), not the step's list, whatever ok is",
        before(uh, "b.trackerActionResult(run, i.pkg)", "parts: Array.isArray(r.changedParts) ? r.changedParts : []") && !uh.contains("{ parts: i.parts }"));
    check("source: the Root way from the History closes the History first (else it covers Root Features)", bodyOf(h, "function rootOfferTab(featureName) {") != null
        && before(bodyOf(h, "function rootOfferTab(featureName) {"), "classList.contains('show')) document.getElementById('historyModal').classList.remove('show')", "switchView('rootfeatures')"));
    check("source: the History keeps the parts of a step (recordHistory), from the box's switch and from the batch runner's rows",
        h.contains("Array.isArray(r.parts) ? { parts: r.parts } : {}") && h.contains("parts: r.changedParts || []") && h.contains("label: r.label, parts: r.parts }"));
    String again = bodyOf(h, "function batchRunAgain(onlyFailed) {");
    check("source: Run again of a tracker batch is gated and confirms Disable again", again != null && again.contains("(b.action === 'trackers_off' || b.action === 'trackers_on') && !isRootActive()") && again.contains("'trackers_off'].includes(b.action)"));
    check("source: a change of working mode redraws the locks (rootOnModeChange -> trkOnModeChange)", h.contains("renderRootFeatures(); trkOnModeChange(); }"));
    check("source: Root Features lists \"Trackers in apps\" on the same gate", h.contains("rootFeatureRow('Trackers in apps',") && h.contains("'rootManageTrackers()', isRootActive())"));
  }
}
