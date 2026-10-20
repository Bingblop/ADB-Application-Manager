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
 * MonetProfile, which Android build family builds the theme colours (finding T-049): every release row (supported, not supported yet),
 * One UI, unknown SDK levels, missing names, the order of the probe tests, every combination of the probe names, the contrast value; and
 * (source scans) how MainActivity looks the names up and what getSystemPalette reports (profile, profileReason, build, contrast).
 */
public class MonetProfileTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void eq(String what, Object got, Object want) { n++; if (want == null ? got != null : !want.equals(got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }

  static final String INV = MonetProfile.PROBE_INVERSE_SURFACE, NOTIF = MonetProfile.PROBE_THEME_NOTIF, RING = MonetProfile.PROBE_ON_THEME_APP_RING,
      DIM = MonetProfile.PROBE_PRIMARY_DIM, RED = MonetProfile.PROBE_SEMANTIC_RED;

  static Set<String> names(String... a) { return new HashSet<String>(Arrays.asList(a)); }

  // the probe names each AOSP branch's core/res/res/values/colors.xml has (read in the branches, see MonetProfile's header)
  static final Set<String> A14 = names(), A15 = names(RING), A15Q1 = names(NOTIF), A15Q2 = names(NOTIF, INV), A16 = names(NOTIF, INV),
      A16Q1 = names(NOTIF, INV, DIM), A16Q2 = names(NOTIF, INV, DIM, RED);

  static String of(int sdk, Set<String> present, String oneUi) { return MonetProfile.of(sdk, present, oneUi).toString(); }

  public static void main(String[] args) throws Exception {
    // ---- the names themselves
    eq("the probe names, in the order MainActivity looks them up", MonetProfile.PROBES,
        Arrays.asList("system_inverse_surface_light", "system_theme_notif_light", "system_on_theme_app_ring_light", "system_primary_dim_light",
            "system_semantic_red_container_high_light"));
    boolean frozen = false;
    try { MonetProfile.PROBES.add("x"); } catch (UnsupportedOperationException e) { frozen = true; }
    check("the probe list cannot be changed", frozen);

    // ---- the profiles the page computes (reason ok)
    eq("Android 14: P14 by the SDK level alone", of(34, A14, ""), "P14/ok");
    eq("Android 14: names of later releases change nothing (no probe on 34)", of(34, names(INV, NOTIF, RING, DIM, RED), ""), "P14/ok");
    eq("Android 14: a probe read that failed changes nothing", of(34, null, ""), "P14/ok");
    eq("Android 15 QPR1: theme_notif without inverse_surface", of(35, A15Q1, ""), "P15Q1/ok");
    eq("Android 15 QPR2: inverse_surface", of(35, A15Q2, ""), "P15Q2/ok");
    eq("Android 16.0: inverse_surface without primary_dim", of(36, A16, ""), "P16/ok");
    eq("Android 12 (31): Pure black only, as before", of(31, A14, ""), "P12/ok");
    eq("Android 12L (32)", of(32, A14, null), "P12/ok");
    eq("Android 13 (33)", of(33, null, ""), "P12/ok");

    // ---- recognised, not computed yet (reason notYet)
    eq("Android 15.0: on_theme_app_ring", of(35, A15, ""), "P15/notYet");
    eq("Android 16 QPR1: primary_dim without semantic_red", of(36, A16Q1, ""), "P16Q1/notYet");
    eq("Android 16 QPR2: semantic_red", of(36, A16Q2, ""), "P16Q2/notYet");
    eq("Android 17 (37): by the SDK level, with any names", of(37, A16Q2, ""), "P17/notYet");
    eq("Android 17 with no names", of(37, A14, ""), "P17/notYet");
    eq("Android 17 when the names could not be read", of(37, null, ""), "P17/notYet");

    // ---- unknown SDK levels and missing names (fail closed)
    eq("SDK 38: unknown", of(38, A16Q2, ""), "unknown/unknown");
    eq("SDK 38 with no names", of(38, A14, ""), "unknown/unknown");
    eq("SDK 40", of(40, A16, ""), "unknown/unknown");
    eq("the largest int", of(Integer.MAX_VALUE, A16, ""), "unknown/unknown");
    eq("below Android 12 (30): unknown", of(30, A14, ""), "unknown/unknown");
    eq("SDK 0 and a negative level: unknown", of(0, A14, "") + " " + of(-1, A14, ""), "unknown/unknown unknown/unknown");
    eq("35 without any probe name: unknown, not Android 15.0", of(35, A14, ""), "unknown/unknown");
    eq("36 without any probe name: unknown, not Android 16.0", of(36, A14, ""), "unknown/unknown");
    eq("36 with theme_notif only (no inverse_surface): unknown", of(36, names(NOTIF), ""), "unknown/unknown");
    eq("36 with the Android 15.0 name only: unknown", of(36, A15, ""), "unknown/unknown");
    eq("35 with only Android 16 QPR names: unknown", of(35, names(DIM, RED), ""), "unknown/unknown");
    eq("35 when the names could not be read: unknown", of(35, null, ""), "unknown/unknown");
    eq("36 when the names could not be read: unknown", of(36, null, ""), "unknown/unknown");
    eq("a name that is not a probe counts for nothing", of(36, names("system_error_0", "system_primary_fixed"), ""), "unknown/unknown");

    // ---- One UI: Samsung builds its theme with its own service, at every SDK level and with any names
    eq("One UI 6.1.1 on Android 14", of(34, A14, "6.1.1"), "unknown/oneUi");
    eq("One UI 7.0 with Android 15 QPR2's names", of(35, A15Q2, "7.0"), "unknown/oneUi");
    eq("One UI 8.0 with Android 16.0's names", of(36, A16, "8.0"), "unknown/oneUi");
    eq("One UI 9.0 on 37", of(37, A16Q2, "9.0"), "unknown/oneUi");
    eq("One UI on Android 13 (Pure black too)", of(33, A14, "5.1"), "unknown/oneUi");
    eq("One UI when the names could not be read", of(36, null, "8.0"), "unknown/oneUi");
    eq("a version with spaces around it is still One UI", of(36, A16, " 8.5 "), "unknown/oneUi");
    eq("an empty, blank or missing version is not One UI", of(36, A16, "") + " " + of(36, A16, "   ") + " " + of(36, A16, null), "P16/ok P16/ok P16/ok");

    // ---- the order of the tests: on 35 inverse_surface, then theme_notif, then on_theme_app_ring; on 36 QPR2, then QPR1, then 16.0
    eq("35: inverse_surface wins over theme_notif and on_theme_app_ring", of(35, names(INV, NOTIF, RING), ""), "P15Q2/ok");
    eq("35: inverse_surface alone is enough for 15 QPR2", of(35, names(INV), ""), "P15Q2/ok");
    eq("35: theme_notif wins over on_theme_app_ring", of(35, names(NOTIF, RING), ""), "P15Q1/ok");
    eq("35: inverse_surface with on_theme_app_ring", of(35, names(INV, RING), ""), "P15Q2/ok");
    eq("36: semantic_red wins over primary_dim and inverse_surface", of(36, names(RED, DIM, INV), ""), "P16Q2/notYet");
    eq("36: semantic_red alone", of(36, names(RED), ""), "P16Q2/notYet");
    eq("36: primary_dim wins over inverse_surface", of(36, names(DIM, INV), ""), "P16Q1/notYet");
    eq("36: primary_dim alone", of(36, names(DIM), ""), "P16Q1/notYet");
    eq("36: inverse_surface with the Android 15.0 name is still 16.0", of(36, names(INV, RING), ""), "P16/ok");

    // ---- every combination of the five names at SDK 29..40: a known id, a known reason, ok exactly for the five computed profiles,
    // never an exception, and names that are not probes never change the answer
    List<String> ids = Arrays.asList("P12", "P14", "P15Q1", "P15Q2", "P16", "P15", "P16Q1", "P16Q2", "P17", "unknown");
    List<String> okIds = Arrays.asList("P12", "P14", "P15Q1", "P15Q2", "P16");
    List<String> reasons = Arrays.asList("ok", "notYet", "oneUi", "unknown");
    int bad = 0, combos = 0;
    List<String> firstBad = new ArrayList<String>();
    for (int sdk = 29; sdk <= 40; sdk++) {
      for (int mask = 0; mask < 32; mask++) {
        Set<String> set = new HashSet<String>();
        for (int i = 0; i < 5; i++) if ((mask & (1 << i)) != 0) set.add(MonetProfile.PROBES.get(i));
        combos++;
        MonetProfile.Result r;
        try { r = MonetProfile.of(sdk, set, ""); } catch (RuntimeException e) { bad++; if (firstBad.size() < 3) firstBad.add(sdk + "/" + set + ": " + e); continue; }
        Set<String> more = new HashSet<String>(set);
        more.add("system_error_0");
        more.add("system_accent1_500");
        MonetProfile.Result r2 = MonetProfile.of(sdk, more, "");
        boolean good = r != null && ids.contains(r.profile) && reasons.contains(r.reason) && !"oneUi".equals(r.reason)
            && r.ok() == okIds.contains(r.profile) && r.ok() == "ok".equals(r.reason)
            && ("unknown".equals(r.profile) == "unknown".equals(r.reason))
            && r.toString().equals(r2.toString())
            && MonetProfile.of(sdk, set, "6.0").toString().equals("unknown/oneUi");
        if (!good) { bad++; if (firstBad.size() < 3) firstBad.add(sdk + "/" + set + " -> " + r); }
      }
    }
    eq("every combination of the names at SDK 29..40 (" + combos + "): consistent " + firstBad, bad, 0);
    check("the result names its profile and reason", MonetProfile.of(36, A16, "").profile.equals("P16") && MonetProfile.of(36, A16, "").reason.equals("ok"));
    check("ok() is false for a profile that is not computed", !MonetProfile.of(36, A16Q1, "").ok() && !MonetProfile.of(38, A16, "").ok() && !MonetProfile.of(34, A14, "1.0").ok());

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
      check("source: it decides with the SDK level, the names found and the One UI version",
          look.contains("MonetProfile.of(Build.VERSION.SDK_INT, present, oneUiVersion())"));
      check("source: a lookup that throws gives no names (unknown on 35 and 36) and is not kept",
          look.contains("catch (Throwable t) {") && look.contains("return MonetProfile.of(Build.VERSION.SDK_INT, null, oneUiVersion());")
          && look.indexOf("monetProfileCache = r;") > look.indexOf("catch (Throwable t) {"));
      check("source: the answer is worked out once (kept in a volatile field)", look.contains("MonetProfile.Result r = monetProfileCache;")
          && look.contains("if (r != null) return r;") && m.contains("private volatile MonetProfile.Result monetProfileCache;"));
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
      check("source: ... and build (Build.FINGERPRINT, never null) and contrast (systemContrast())",
          pal.contains("o.put(\"build\", Build.FINGERPRINT == null ? \"\" : Build.FINGERPRINT);") && pal.contains("o.put(\"contrast\", systemContrast());"));
      check("source: the new fields come after the Android 12 check and before the palettes are read (in every answer with colours)",
          early >= 0 && put > early && put < pal.indexOf("int[] tones = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};")
          && put < pal.indexOf("o.put(\"ok\", true);"));
    }
    String bridge = bodyOf(m, "public String getSystemPalette() {");
    check("source: the bridge's getSystemPalette answers with systemPaletteJson()", bridge != null && bridge.contains("return systemPaletteJson();"));
  }
}
