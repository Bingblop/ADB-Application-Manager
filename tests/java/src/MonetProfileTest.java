package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MonetProfile, which Android build family builds the theme colours (findings T-049, T-050): every release row (supported, not supported yet),
 * unknown SDK levels, missing names, the order of the probe tests, every combination of the probe names, the contrast value, the One UI value
 * (which only shows the page's note: One UI is decided like any other build since PR 13); and (source scans) how MainActivity looks the
 * names up and reads One UI, and what getSystemPalette reports (profile, profileReason, build, contrast, oneUi).
 */
public class MonetProfileTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void eq(String what, Object got, Object want) { n++; if (want == null ? got != null : !want.equals(got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }

  static final String INV = MonetProfile.PROBE_INVERSE_SURFACE, NOTIF = MonetProfile.PROBE_THEME_NOTIF, RING = MonetProfile.PROBE_ON_THEME_APP_RING,
      DIM = MonetProfile.PROBE_PRIMARY_DIM, RED = MonetProfile.PROBE_SEMANTIC_RED, FIX = MonetProfile.PROBE_PRIMARY_FIXED,
      REPAIR = MonetProfile.PROBE_REPAIR_MODE;

  static Set<String> names(String... a) { return new HashSet<String>(Arrays.asList(a)); }

  // the probe names each AOSP branch's core/res/res/values/colors.xml has (read in the branches, see MonetProfile's header)
  // A14Q: android14-qpr1/qpr2/qpr3-release have the boolean config_repairModeSupported (values/config.xml) and no probe colour
  static final Set<String> A14 = names(), A14Q = names(REPAIR), A15 = names(RING), A15Q1 = names(NOTIF), A15Q2 = names(NOTIF, INV), A16 = names(NOTIF, INV),
      A16Q1 = names(NOTIF, INV, DIM), A16Q2 = names(NOTIF, INV, DIM, RED),
      // android17-release has primary_fixed_light and no longer semantic_red (core/res values/colors.xml of each branch, read 2026-10-21)
      A17 = names(NOTIF, INV, DIM, FIX);

  static String of(int sdk, Set<String> present) { return MonetProfile.of(sdk, present).toString(); }

  public static void main(String[] args) throws Exception {
    // ---- the names themselves
    eq("the probe names, in the order MainActivity looks them up", MonetProfile.PROBES,
        Arrays.asList("system_inverse_surface_light", "system_theme_notif_light", "system_on_theme_app_ring_light", "system_primary_dim_light",
            "system_semantic_red_container_high_light", "system_primary_fixed_light"));
    boolean frozen = false;
    try { MonetProfile.PROBES.add("x"); } catch (UnsupportedOperationException e) { frozen = true; }
    check("the probe list cannot be changed", frozen);
    eq("the boolean probe names (looked up as bool, not as colour)", MonetProfile.BOOL_PROBES, Arrays.asList("config_repairModeSupported"));
    boolean frozenB = false;
    try { MonetProfile.BOOL_PROBES.add("x"); } catch (UnsupportedOperationException e) { frozenB = true; }
    check("the boolean probe list cannot be changed", frozenB);
    check("no name is both a colour and a boolean probe", !MonetProfile.PROBES.contains(REPAIR));

    // ---- the profiles the page computes (reason ok)
    eq("Android 14: P14 by the SDK level alone", of(34, A14), "P14/ok");
    eq("Android 14: the colour names of later releases change nothing (only the boolean probe counts on 34)", of(34, names(INV, NOTIF, RING, DIM, RED)), "P14/ok");
    eq("Android 14: a probe read that failed changes nothing", of(34, null), "P14/ok");
    eq("Android 14 QPR1-QPR3: config_repairModeSupported", of(34, A14Q), "P14Q/ok");
    eq("Android 14 QPR: names of later releases beside it change nothing", of(34, names(REPAIR, INV, NOTIF, RING, DIM, RED, FIX)), "P14Q/ok");
    eq("Android 14 without the boolean stays P14 (fail closed to today's answer), with or without colour names",
        of(34, names()) + " " + of(34, names(INV, NOTIF, RING, DIM, RED, FIX)) + " " + of(34, null), "P14/ok P14/ok P14/ok");
    eq("Android 14: a name close to the boolean's counts for nothing",
        of(34, names("config_repairModeSupport", "config_RepairModeSupported", "repairModeSupported", "config_repairModeSupported ")), "P14/ok");
    eq("the boolean on any other SDK level changes nothing", of(33, A14Q) + " " + of(35, A14Q) + " " + of(35, names(REPAIR, RING)) + " "
        + of(36, names(REPAIR, INV)) + " " + of(37, names(REPAIR, FIX)) + " " + of(37, A14Q) + " " + of(38, A14Q) + " " + of(30, A14Q),
        "P12/ok unknown/unknown P15/ok P16/ok P17/ok unknown/unknown unknown/unknown unknown/unknown");
    eq("Android 15.0: on_theme_app_ring", of(35, A15), "P15/ok");
    eq("Android 15 QPR1: theme_notif without inverse_surface", of(35, A15Q1), "P15Q1/ok");
    eq("Android 15 QPR2: inverse_surface", of(35, A15Q2), "P15Q2/ok");
    eq("Android 16.0: inverse_surface without primary_dim", of(36, A16), "P16/ok");
    eq("Android 16 QPR1: primary_dim without semantic_red", of(36, A16Q1), "P16Q1/ok");
    eq("Android 16 QPR2: semantic_red", of(36, A16Q2), "P16Q2/ok");
    eq("Android 17 (37): primary_fixed_light", of(37, A17), "P17/ok");
    eq("Android 17: primary_fixed_light alone is enough", of(37, names(FIX)), "P17/ok");
    eq("Android 17: with semantic_red too (a build that kept it)", of(37, names(NOTIF, INV, DIM, RED, FIX)), "P17/ok");
    eq("Android 12 (31): Pure black only, as before", of(31, A14), "P12/ok");
    eq("Android 12L (32)", of(32, A14), "P12/ok");
    eq("Android 13 (33)", of(33, null), "P12/ok");

    // ---- recognised, not computed yet (reason notYet): no profile gives it any more (Android 15.0 was the last)
    eq("the notYet key stays as the page reads it", MonetProfile.REASON_NOT_YET, "notYet");
    eq("Android 15.0 is not on 34 or 36", of(34, A15) + " " + of(36, A15), "P14/ok unknown/unknown");
    eq("Android 15.0 with names of no other release beside it", of(35, names(RING, DIM, RED, FIX)), "P15/ok");

    // ---- unknown SDK levels and missing names (fail closed)
    eq("37 without primary_fixed_light: unknown (Android 16 QPR2's names)", of(37, A16Q2), "unknown/unknown");
    eq("37 with no names: unknown", of(37, A14), "unknown/unknown");
    eq("37 with every name but primary_fixed_light: unknown", of(37, names(INV, NOTIF, RING, DIM, RED)), "unknown/unknown");
    eq("37 when the names could not be read: unknown", of(37, null), "unknown/unknown");
    eq("SDK 38 with Android 17's names: unknown", of(38, A17), "unknown/unknown");
    eq("SDK 36 with Android 17's names is still read as 16 QPR1 (no 36 row looks at primary_fixed_light)", of(36, A17), "P16Q1/ok");
    eq("SDK 35 with primary_fixed_light only: unknown", of(35, names(FIX)), "unknown/unknown");
    eq("SDK 38: unknown", of(38, A16Q2), "unknown/unknown");
    eq("SDK 38 with no names", of(38, A14), "unknown/unknown");
    eq("SDK 40", of(40, A16), "unknown/unknown");
    eq("the largest int", of(Integer.MAX_VALUE, A16), "unknown/unknown");
    eq("below Android 12 (30): unknown", of(30, A14), "unknown/unknown");
    eq("SDK 0 and a negative level: unknown", of(0, A14) + " " + of(-1, A14), "unknown/unknown unknown/unknown");
    eq("35 without any probe name: unknown, not Android 15.0", of(35, A14), "unknown/unknown");
    eq("36 without any probe name: unknown, not Android 16.0", of(36, A14), "unknown/unknown");
    eq("36 with theme_notif only (no inverse_surface): unknown", of(36, names(NOTIF)), "unknown/unknown");
    eq("36 with the Android 15.0 name only: unknown", of(36, A15), "unknown/unknown");
    eq("35 with only Android 16 QPR names: unknown", of(35, names(DIM, RED)), "unknown/unknown");
    eq("35 when the names could not be read: unknown", of(35, null), "unknown/unknown");
    eq("36 when the names could not be read: unknown", of(36, null), "unknown/unknown");
    eq("a name that is not a probe counts for nothing", of(36, names("system_error_0", "system_primary_fixed")), "unknown/unknown");

    // ---- One UI (PR 13): no short-circuit any more; of() takes no One UI value, so a One UI phone gets the profile of its SDK level and names
    // like any other phone (Android 16 One UI 8.5 -> P16 family by its names), and a build this app does not know stays locked
    check("of() takes the SDK level and the names only (no One UI parameter)", ofArity() == 2);
    eq("One UI 6.1 on Android 14 and 14 QPR (the names a Samsung build of those branches would have): P14 and P14Q", of(34, A14) + " " + of(34, A14Q), "P14/ok P14Q/ok");
    eq("One UI 7.0 on Android 15 QPR2's names: P15Q2, as any other phone", of(35, A15Q2), "P15Q2/ok");
    eq("One UI 8.0 on Android 16.0's names: P16", of(36, A16), "P16/ok");
    eq("One UI 8.5 on Android 16 with 16 QPR1 or QPR2 names: the P16 family by its names", of(36, A16Q1) + " " + of(36, A16Q2), "P16Q1/ok P16Q2/ok");
    eq("One UI 5.1 on Android 13: P12 (Pure black)", of(33, A14), "P12/ok");
    eq("a One UI build this app does not know stays locked: 37 without Android 17's name, 36 whose names match no release or could not be read, 38",
        of(37, A16Q2) + " " + of(36, names(NOTIF)) + " " + of(36, null) + " " + of(38, A17), "unknown/unknown unknown/unknown unknown/unknown unknown/unknown");
    // ---- the One UI value getSystemPalette reports for the page's note (MonetProfile.oneUi): a Samsung phone whose version could not be read is One UI
    eq("not a Samsung: no One UI, whatever was read", MonetProfile.oneUi(false, "", false) + "|" + MonetProfile.oneUi(false, "8.0", true) + "|" + MonetProfile.oneUi(false, null, false), "||");
    eq("a Samsung with a version read: that version", MonetProfile.oneUi(true, "6.1.1", false) + "|" + MonetProfile.oneUi(true, "8.0", true), "6.1.1|8.0");
    eq("... trimmed", MonetProfile.oneUi(true, " 8.5 ", false), "8.5");
    eq("a Samsung without a version, the property not set (another build of Android on a Samsung phone): not One UI", MonetProfile.oneUi(true, "", true), "");
    eq("a Samsung without a version, the property not read (getprop failed or timed out): the marker", MonetProfile.oneUi(true, "", false), MonetProfile.ONE_UI_UNREAD);
    eq("... the same for a null or blank version", MonetProfile.oneUi(true, null, false) + MonetProfile.oneUi(true, "  ", false), MonetProfile.ONE_UI_UNREAD + MonetProfile.ONE_UI_UNREAD);
    check("the marker is not blank", MonetProfile.ONE_UI_UNREAD != null && !MonetProfile.ONE_UI_UNREAD.trim().isEmpty());
    check("there is no One UI reason any more", !hasField("REASON_ONE_UI"));

    // ---- the order of the tests: on 35 inverse_surface, then theme_notif, then on_theme_app_ring; on 36 QPR2, then QPR1, then 16.0
    eq("35: inverse_surface wins over theme_notif and on_theme_app_ring", of(35, names(INV, NOTIF, RING)), "P15Q2/ok");
    eq("35: inverse_surface alone is enough for 15 QPR2", of(35, names(INV)), "P15Q2/ok");
    eq("35: theme_notif wins over on_theme_app_ring", of(35, names(NOTIF, RING)), "P15Q1/ok");
    eq("35: inverse_surface with on_theme_app_ring", of(35, names(INV, RING)), "P15Q2/ok");
    eq("35: on_theme_app_ring alone is enough for 15.0", of(35, names(RING)), "P15/ok");
    eq("36: semantic_red wins over primary_dim and inverse_surface", of(36, names(RED, DIM, INV)), "P16Q2/ok");
    eq("36: semantic_red alone", of(36, names(RED)), "P16Q2/ok");
    eq("36: primary_dim wins over inverse_surface", of(36, names(DIM, INV)), "P16Q1/ok");
    eq("36: primary_dim alone", of(36, names(DIM)), "P16Q1/ok");
    eq("36: inverse_surface with the Android 15.0 name is still 16.0", of(36, names(INV, RING)), "P16/ok");

    // ---- every combination of the seven names (six colours, one boolean) at SDK 29..40: a known id, a known reason, ok exactly for the ten
    // computed profiles (no notYet),
    // never an exception, and names that are not probes never change the answer
    List<String> ids = Arrays.asList("P12", "P14", "P14Q", "P15Q1", "P15Q2", "P16", "P15", "P16Q1", "P16Q2", "P17", "unknown");
    List<String> okIds = Arrays.asList("P12", "P14", "P14Q", "P15", "P15Q1", "P15Q2", "P16", "P16Q1", "P16Q2", "P17");
    List<String> reasons = Arrays.asList("ok", "notYet", "unknown");
    int bad = 0, combos = 0;
    List<String> firstBad = new ArrayList<String>();
    for (int sdk = 29; sdk <= 40; sdk++) {
      for (int mask = 0; mask < 128; mask++) {
        Set<String> set = new HashSet<String>();
        for (int i = 0; i < 6; i++) if ((mask & (1 << i)) != 0) set.add(MonetProfile.PROBES.get(i));
        if ((mask & 64) != 0) set.add(REPAIR);
        combos++;
        MonetProfile.Result r;
        try { r = MonetProfile.of(sdk, set); } catch (RuntimeException e) { bad++; if (firstBad.size() < 3) firstBad.add(sdk + "/" + set + ": " + e); continue; }
        Set<String> more = new HashSet<String>(set);
        more.add("system_error_0");
        more.add("system_accent1_500");
        MonetProfile.Result r2 = MonetProfile.of(sdk, more);
        boolean good = r != null && ids.contains(r.profile) && reasons.contains(r.reason) && !"oneUi".equals(r.reason)
            && r.ok() == okIds.contains(r.profile) && r.ok() == "ok".equals(r.reason)
            && ("unknown".equals(r.profile) == "unknown".equals(r.reason))
            && r.toString().equals(r2.toString())
            && ("P17".equals(r.profile) == (sdk == 37 && set.contains(FIX)))
            && ("P14Q".equals(r.profile) == (sdk == 34 && set.contains(REPAIR)))
            && ("P14".equals(r.profile) == (sdk == 34 && !set.contains(REPAIR)))
            && ("P15".equals(r.profile) == (sdk == 35 && set.contains(RING) && !set.contains(NOTIF) && !set.contains(INV)))
            && !"notYet".equals(r.reason);
        if (!good) { bad++; if (firstBad.size() < 3) firstBad.add(sdk + "/" + set + " -> " + r); }
      }
    }
    eq("every combination of the names at SDK 29..40 (" + combos + "): consistent " + firstBad, bad, 0);
    eq("... 12 levels times 128 sets", combos, 12 * 128);
    check("the result names its profile and reason", MonetProfile.of(36, A16).profile.equals("P16") && MonetProfile.of(36, A16).reason.equals("ok"));
    check("ok() is true on 15.0", MonetProfile.of(35, A15).ok());
    check("ok() is false for a profile that is not computed, true on 14", !MonetProfile.of(37, A16Q2).ok() && !MonetProfile.of(38, A16).ok() && MonetProfile.of(34, A14).ok());
    check("ok() is true on 16 QPR1 and QPR2", MonetProfile.of(36, A16Q1).ok() && MonetProfile.of(36, A16Q2).ok());
    check("ok() is true on 17 with its name, false without it", MonetProfile.of(37, A17).ok() && !MonetProfile.of(37, A16Q2).ok() && !MonetProfile.of(37, null).ok());

    // ---- which profiles write the palette shades as _light and _dark (16 QPR1, QPR2 and 17), and the fixed colours too (17 only)
    check("suffixed palettes on P16Q1, P16Q2 and P17", MonetProfile.suffixedPalettes(MonetProfile.P16Q1) && MonetProfile.suffixedPalettes("P16Q2") && MonetProfile.suffixedPalettes(MonetProfile.P17));
    for (String p : new String[] {"P12", "P14", "P14Q", "P15", "P15Q1", "P15Q2", "P16", "unknown", "", "p16q1", "P16Q", "P16Q3", "p17", "P18", "P17 "}) check("no suffixed palettes on " + p, !MonetProfile.suffixedPalettes(p));
    check("no suffixed palettes without a profile", !MonetProfile.suffixedPalettes(null));
    check("suffixed fixed colours on P17", MonetProfile.suffixedFixed("P17"));
    for (String p : new String[] {"P12", "P14", "P14Q", "P15", "P15Q1", "P15Q2", "P16", "P16Q1", "P16Q2", "unknown", "", "p17", "P18", "P17 "}) check("no suffixed fixed colours on " + p, !MonetProfile.suffixedFixed(p));
    check("no suffixed fixed colours without a profile", !MonetProfile.suffixedFixed(null));

    // ---- the contrast getSystemPalette reports
    eq("contrast 0 (standard)", MonetProfile.contrast(0f), 0.0);
    eq("contrast 0.5 (medium)", MonetProfile.contrast(0.5f), 0.5);
    eq("contrast 1 (high)", MonetProfile.contrast(1f), 1.0);
    eq("contrast -1", MonetProfile.contrast(-1f), -1.0);
    eq("a float is widened, not rounded to a short decimal (SystemUI passes the same float to its double)", MonetProfile.contrast(0.33f), (double) 0.33f);
    check("... so 0.33 arrives as 0.33000001311302185", MonetProfile.contrast(0.33f) == 0.33000001311302185 && MonetProfile.contrast(0.33f) != 0.33);
    eq("NaN counts as 0", MonetProfile.contrast(Float.NaN), 0.0);
    eq("infinities count as 0", MonetProfile.contrast(Float.POSITIVE_INFINITY) + " " + MonetProfile.contrast(Float.NEGATIVE_INFINITY), "0.0 0.0");
    eq("above 1 is held at 1", MonetProfile.contrast(2.5f), 1.0);
    eq("below -1 is held at -1", MonetProfile.contrast(-3f), -1.0);
    eq("just inside the range is kept", MonetProfile.contrast(0.999f), (double) 0.999f);

    // ---- which wallpaper's colours getSystemPalette reports (T-079): the one set last, by WallpaperManager id, as SystemUI's getLatestWallpaperType
    check("the lock wallpaper set after the home one (a higher id) is the latest", MonetProfile.lockWallpaperIsLatest(12, 7));
    check("the home wallpaper set after the lock one is the latest", !MonetProfile.lockWallpaperIsLatest(7, 12));
    check("no separate lock wallpaper (getWallpaperId answers -1): the home one", !MonetProfile.lockWallpaperIsLatest(-1, 3) && !MonetProfile.lockWallpaperIsLatest(-1, 0));
    check("equal ids (one wallpaper set for both): the home one, as SystemUI's strict > has it", !MonetProfile.lockWallpaperIsLatest(5, 5));
    check("neither readable (both -1): the home one", !MonetProfile.lockWallpaperIsLatest(-1, -1));

    sourceScan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
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

  static int ofArity() {
    int a = -1;
    for (java.lang.reflect.Method mt : MonetProfile.class.getMethods()) if (mt.getName().equals("of")) a = a < 0 ? mt.getParameterTypes().length : -2;
    return a;
  }

  static boolean hasField(String name) {
    try { MonetProfile.class.getField(name); return true; } catch (NoSuchFieldException e) { return false; }
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
    if (dir == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String m = read(new File(dir, "MainActivity.java"));
    String mp = read(new File(dir, "MonetProfile.java"));

    check("source: MonetProfile uses no Android class (tested on a computer)", !mp.contains("import android.") && !mp.contains("android.content") && !mp.contains("android.os"));

    String look = bodyOf(m, "private MonetProfile.Result monetProfile() {");
    check("source: MainActivity has monetProfile()", look != null);
    if (look != null) {
      check("source: it looks every probe name up as a colour of package android, present when the id is not 0",
          look.contains("for (String name : MonetProfile.PROBES) if (res.getIdentifier(name, \"color\", \"android\") != 0) present.add(name);"));
      int col = look.indexOf("for (String name : MonetProfile.PROBES) if (res.getIdentifier(name, \"color\", \"android\") != 0) present.add(name);");
      int bool = look.indexOf("for (String name : MonetProfile.BOOL_PROBES) if (res.getIdentifier(name, \"bool\", \"android\") != 0) present.add(name);");
      check("source: it looks every boolean probe name up as a bool of package android (no root, no shell), into the same set, after the colours",
          bool > col && col > 0 && look.indexOf("getIdentifier(name, \"bool\", \"android\")") == look.lastIndexOf("getIdentifier(name, \"bool\", \"android\")"));
      check("source: ... inside the try whose catch gives no names (P14 on 34)", look.indexOf("try {") < col && bool < look.indexOf("catch (Throwable t) {"));
      check("source: the boolean names are not looked up as colours, nor the colour names as booleans",
          !look.contains("MonetProfile.BOOL_PROBES) if (res.getIdentifier(name, \"color\"") && !look.contains("MonetProfile.PROBES) if (res.getIdentifier(name, \"bool\""));
      check("source: it decides with the SDK level and the names found only, never with One UI (PR 13)",
          look.contains("MonetProfile.of(Build.VERSION.SDK_INT, present)") && !look.contains("oneUi") && !look.contains("isSamsung()"));
      check("source: a lookup that throws gives no names (unknown on 35 and 36) and is not kept",
          look.contains("catch (Throwable t) {") && look.contains("return MonetProfile.of(Build.VERSION.SDK_INT, null);")
          && look.indexOf("monetProfileCache = r;") > look.indexOf("catch (Throwable t) {"));
      check("source: the answer is worked out once (kept in a volatile field)", look.contains("MonetProfile.Result r = monetProfileCache;")
          && look.contains("if (r != null) return r;") && m.contains("private volatile MonetProfile.Result monetProfileCache;"));
    }
    String note = bodyOf(m, "private String monetOneUi() {");
    check("source: MainActivity has monetOneUi() (the One UI value for the card's note)", note != null);
    if (note != null) {
      check("source: it reads the One UI value of MonetProfile.oneUi (Samsung, the version, the property read)",
          note.contains("String version = oneUiVersion(unset);") && note.contains("String oneUi = MonetProfile.oneUi(isSamsung(), version, unset[0]);"));
      check("source: an answer from a One UI version that could not be read is not kept (the next call reads again)",
          note.contains("if (!MonetProfile.ONE_UI_UNREAD.equals(oneUi)) monetOneUiCache = oneUi;") && m.contains("private volatile String monetOneUiCache;"));
    }
    check("source: MonetProfile has no One UI reason or short-circuit", !mp.contains("REASON_ONE_UI") && !mp.contains("\"oneUi\";"));
    String ver = bodyOf(m, "private String oneUiVersion(boolean[] propertyUnset) {");
    check("source: MainActivity has oneUiVersion(boolean[])", ver != null);
    if (ver != null) {
      check("source: it says the property is not set only when getprop printed nothing (an error or a timeout prints text)",
          ver.contains("if (propertyUnset != null) propertyUnset[0] = line.isEmpty();")
          && ver.indexOf("runProcessWithTimeout(new ProcessBuilder(\"getprop\", \"ro.build.version.oneui\"), 2000).trim();") >= 0
          && ver.indexOf("propertyUnset[0] = line.isEmpty();") > ver.indexOf("runProcessWithTimeout(new ProcessBuilder(\"getprop\", \"ro.build.version.oneui\"), 2000).trim();"));
      check("source: the other callers keep the old oneUiVersion() (device info, telemetry)", m.contains("private String oneUiVersion() {\n        return oneUiVersion(null);\n    }"));
    }

    String con = bodyOf(m, "private double systemContrast() {");
    check("source: MainActivity has systemContrast()", con != null);
    if (con != null) {
      check("source: getContrast is asked only on Android 14 and newer (the API exists from 34), 0 before",
          con.contains("if (Build.VERSION.SDK_INT < 34) return 0;") && con.indexOf("if (Build.VERSION.SDK_INT < 34) return 0;") < con.indexOf("getContrast()"));
      check("source: the contrast is read inside a try whose catch gives 0, through MonetProfile.contrast",
          con.indexOf("try {") < con.indexOf("um.getContrast()") && con.contains("MonetProfile.contrast(um.getContrast())")
          && con.contains("catch (Throwable t) {\n            return 0;"));
      check("source: a missing UiModeManager gives 0", con.contains("um == null ? 0 :"));
    }

    String pal = bodyOf(m, "private String systemPaletteJson() {");
    check("source: MainActivity has systemPaletteJson()", pal != null);
    if (pal != null) {
      int early = pal.indexOf("if (Build.VERSION.SDK_INT < 31) {"), put = pal.indexOf("o.put(\"profile\", profile.profile);");
      check("source: getSystemPalette reports profile and profileReason from monetProfile()", pal.contains("MonetProfile.Result profile = monetProfile();")
          && put > 0 && pal.contains("o.put(\"profileReason\", profile.reason);"));
      check("source: ... and oneUi (monetOneUi()) only when it is not blank",
          pal.contains("String oneUi = monetOneUi();") && pal.contains("if (!oneUi.isEmpty()) o.put(\"oneUi\", oneUi);")
          && pal.indexOf("o.put(\"oneUi\", oneUi);") < pal.indexOf("o.put(\"ok\", true);"));
      check("source: ... and build (Build.FINGERPRINT, never null) and contrast (systemContrast())",
          pal.contains("o.put(\"build\", Build.FINGERPRINT == null ? \"\" : Build.FINGERPRINT);") && pal.contains("o.put(\"contrast\", systemContrast());"));
      check("source: the new fields come after the Android 12 check and before the palettes are read (in every answer with colours)",
          early >= 0 && put > early && put < pal.indexOf("int[] tones = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};")
          && put < pal.indexOf("o.put(\"ok\", true);"));
    }
    if (pal != null) {
      int lockId = pal.indexOf("wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK)"), sysId = pal.indexOf("wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM)");
      int lockColors = pal.indexOf("wc = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_LOCK);"), sysColors = pal.indexOf("if (wc == null) wc = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);");
      check("source (T-079): \"wallpaper\" comes from the wallpaper set last: both ids are read and compared through MonetProfile.lockWallpaperIsLatest",
          lockId > 0 && sysId > lockId && pal.contains("if (MonetProfile.lockWallpaperIsLatest(wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK), wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM))) {"));
      check("source (T-079): the lock colours only when it is the latest, else (or when it has none) the home wallpaper's",
          lockColors > sysId && sysColors > lockColors && pal.indexOf("getWallpaperColors(") == pal.indexOf("getWallpaperColors(android.app.WallpaperManager.FLAG_LOCK)"));
      check("source (T-079): still inside the API 27 check and its try (anything going wrong leaves \"wallpaper\" out)",
          pal.indexOf("if (Build.VERSION.SDK_INT >= 27) {") < lockId && pal.lastIndexOf("try {", lockId) > pal.indexOf("if (Build.VERSION.SDK_INT >= 27) {") && pal.indexOf("} catch (Throwable ignored) {}", sysColors) > sysColors);
    }
    String bridge = bodyOf(m, "public String getSystemPalette() {");
    check("source: the bridge's getSystemPalette answers with systemPaletteJson()", bridge != null && bridge.contains("return systemPaletteJson();"));
  }
}
