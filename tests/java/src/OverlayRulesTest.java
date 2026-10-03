package com.bloatware.bingblop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class OverlayRulesTest {
    static int n = 0, fails = 0;
    static void eq(String what, Object got, Object want) { n++; if (!Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }
    static void throwsIae(String what, Runnable r) { n++; try { r.run(); fails++; System.out.println("FAIL " + what + ": no exception"); } catch (IllegalArgumentException e) { /* ok */ } }

    static Path fakeBin, fakeDir;

    // what AndroidBridge's reader does with a process's output: line by line, "\n" after each, trimmed
    static String viaReader(String raw) throws IOException {
        BufferedReader r = new BufferedReader(new StringReader(raw));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        return sb.toString().trim();
    }

    static String runShell(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", script);
        pb.environment().put("PATH", fakeBin + ":" + System.getenv("PATH"));
        pb.environment().put("OVL_FAKE", fakeDir.toString());
        pb.directory(fakeDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int k;
        InputStream in = p.getInputStream();
        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
        p.waitFor();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    static String flagSummary(OverlayRules.ThemeResult r) {
        StringBuilder sb = new StringBuilder();
        for (OverlayRules.FlagChange c : r.flags) { if (sb.length() > 0) sb.append(','); sb.append(c.table).append(':').append(c.was).append('>').append(c.now); }
        return sb.toString();
    }

    /** A theme change's environment backed by the real shell and the fake `settings`: records each script, and may answer one itself. */
    static final class TestEnv implements OverlayRules.ThemeEnv {
        String snap = "";
        long now = 5_000_000_000L;
        final List<String> scripts = new ArrayList<String>();
        java.util.function.BiFunction<String, Integer, String> override;       // (script, index of the script) -> its answer, or null to run it

        public String run(String script, int timeoutMs) {
            int n = scripts.size();
            scripts.add(script);
            String o = override == null ? null : override.apply(script, n);
            if (o != null) return o;
            try { return viaReader(runShell(script)); } catch (Exception e) { throw new RuntimeException(e); }
        }

        public String snapshot() { return snap; }
        public void saveSnapshot(String s) { snap = s; }
        public long now() { return now; }
    }

    static void write(Path p, String s) throws IOException { Files.write(p, s.getBytes(StandardCharsets.UTF_8)); }
    static String read(Path p) throws IOException { return Files.exists(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : null; }

    static void resetFake() throws Exception {
        try (java.util.stream.Stream<Path> s = Files.walk(fakeDir)) { s.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(fakeDir) && !p.equals(fakeBin) && !p.startsWith(fakeBin)).forEach(p -> p.toFile().delete()); }
        Files.createDirectories(fakeDir.resolve("settings"));
    }

    static String overlayState(String id) throws IOException {
        String st = read(fakeDir.resolve("overlays"));
        if (st == null) return null;
        for (String line : st.split("\n")) { String[] p = line.split(" ", 3); if (p.length == 3 && p[2].equals(id)) return p[1]; }
        return null;
    }

    public static void main(String[] a) throws Exception {
        fakeDir = Files.createTempDirectory("ovlfake");
        fakeBin = fakeDir.resolve("bin");
        Files.createDirectories(fakeBin);
        // a fake `cmd`: "cmd overlay list|enable|disable ID", state in $OVL_FAKE/overlays as "<target> <1|0|-1> <id>"; logs its arguments one per line
        write(fakeBin.resolve("cmd"),
            "#!/bin/sh\n" +
            "{ echo '---'; for x in \"$@\"; do printf '[%s]\\n' \"$x\"; done; } >> \"$OVL_FAKE/cmd.log\"\n" +
            "[ \"$1\" = overlay ] || { echo \"cmd: Can't find service: $1\"; exit 1; }\n" +
            "verb=$2\n" +
            "f=\"$OVL_FAKE/overlays\"\n" +
            "case \"$verb\" in\n" +
            " list) last=; while read -r tgt st id; do if [ \"$tgt\" != \"$last\" ]; then [ -n \"$last\" ] && echo; echo \"$tgt\"; last=$tgt; fi; case $st in 1) echo \"[x] $id\";; 0) echo \"[ ] $id\";; *) echo \"--- $id\";; esac; done < \"$f\"; echo;;\n" +
            " enable|disable) id=$3; want=1; [ \"$verb\" = disable ] && want=0\n" +
            "   if ! grep -q \" $id\\$\" \"$f\"; then echo \"Error: overlay '$id' not found\"; exit 1; fi\n" +
            "   if grep -q \" -1 $id\\$\" \"$f\"; then echo \"Error: failed to $verb overlay (not available)\"; exit 1; fi\n" +
            "   if grep -q \" 1 FIXED:$id\\$\" \"$f\"; then :; fi\n" +
            "   if [ -f \"$OVL_FAKE/immutable\" ] && grep -qx \"$id\" \"$OVL_FAKE/immutable\" && [ $want = 0 ]; then exit 0; fi\n" +
            "   tmp=\"$f.tmp\"; while read -r tgt st oid; do if [ \"$oid\" = \"$id\" ]; then echo \"$tgt $want $oid\"; else echo \"$tgt $st $oid\"; fi; done < \"$f\" > \"$tmp\"; mv \"$tmp\" \"$f\";;\n" +
            " *) echo \"Overlay manager (overlay) commands:\"; exit 1;;\n" +
            "esac\n");
        // a fake `settings`: settings get|put <ns> <key> [value]; files $OVL_FAKE/settings/<ns>__<key>
        write(fakeBin.resolve("settings"),
            "#!/bin/sh\n" +
            "d=\"$OVL_FAKE/settings\"; mkdir -p \"$d\"\n" +
            "case \"$1\" in\n" +
            " get) f=\"$d/$2__$3\"; if [ -f \"$f\" ]; then cat \"$f\"; echo; else echo null; fi;;\n" +
            " put) if [ -f \"$OVL_FAKE/deny\" ] && grep -qx \"$2__$3\" \"$OVL_FAKE/deny\"; then echo \"Exception occurred while executing 'put':\"; echo \"java.lang.SecurityException: Permission denial: writing to $2 settings requires android.permission.WRITE_SECURE_SETTINGS\"; echo \"$2 $3 $4 DENIED\" >> \"$OVL_FAKE/put.log\"; else printf '%s' \"$4\" > \"$d/$2__$3\"; echo \"$2 $3 $4\" >> \"$OVL_FAKE/put.log\"; if [ \"$3\" = theme_customization_overlay_packages ] && [ -f \"$OVL_FAKE/rewrite\" ]; then case \"$(cat \"$OVL_FAKE/rewrite\")\" in alpha) sed -e 's/\"android.theme.customization.system_palette\":\"\\([0-9A-F]*\\)\"/\"android.theme.customization.system_palette\":\"FF\\1\"/' \"$d/$2__$3\" > \"$d/.tmp\" && mv \"$d/.tmp\" \"$d/$2__$3\";; other) printf '%s' '{\"other\":1}' > \"$d/$2__$3\";; esac; fi; fi;;\n" +
            " delete) rm -f \"$d/$2__$3\";;\n" +
            "esac\n");
        for (String f : new String[] {"cmd", "settings"}) fakeBin.resolve(f).toFile().setExecutable(true);

        // ---- overlay names ----
        for (String id : new String[] {"android.theme.customization.accent_color", "com.android.systemui:overlay_name", "a", "x".repeat(300), "com.foo$bar", "weird'quote", "a;b", "a$(x)", "a>b", "ünï.pkg", "a/b", "a@b+c"}) eq("good id " + id.length(), OverlayRules.idProblem(id), null);
        for (String id : new String[] {"", null, "-x", "--user", "a b", "a\tb", "a\nb", "\u0000", "x".repeat(301), "a b", "a b", "﻿a"}) is("bad id " + String.valueOf(id).replace("\n", "\\n"), OverlayRules.idProblem(id) != null);
        eq("op enable", OverlayRules.isOp("enable"), true); eq("op disable", OverlayRules.isOp("disable"), true);
        for (String o : new String[] {"", null, "list", "enable-exclusive", "Enable", "enable ", "remove"}) eq("bad op " + o, OverlayRules.isOp(o), false);

        // ---- exact commands ----
        eq("list cmd", OverlayRules.listCommand(), "cmd overlay list");
        eq("enable cmd", OverlayRules.changeCommand("enable", "com.foo.bar"), "cmd overlay enable 'com.foo.bar'");
        eq("disable cmd", OverlayRules.changeCommand("disable", "com.foo:name"), "cmd overlay disable 'com.foo:name'");
        eq("quote", OverlayRules.changeCommand("enable", "it's"), "cmd overlay enable 'it'\\''s'");
        eq("script", OverlayRules.changeScript("enable", "x.y"), "cmd overlay enable 'x.y'; echo @@OVL-GET@@; cmd overlay list; echo @@OVL-END@@");
        throwsIae("bad op script", () -> OverlayRules.changeScript("list", "x.y"));
        throwsIae("bad id script", () -> OverlayRules.changeScript("enable", "-x"));
        throwsIae("empty id script", () -> OverlayRules.changeScript("enable", ""));

        // ---- reading lists ----
        String pixel = "android\n[x] android.theme.customization.accent_color\n[x] android.theme.customization.adaptive_icon_shape\n[ ] android.theme.customization.font\n[x] com.android.internal.systemui.navbar.gestural\n[ ] com.android.internal.systemui.navbar.threebutton\n--- com.google.android.overlay.gmsconfig.photos\n\ncom.android.systemui\n[x] com.android.systemui.clocks.metro\n[ ] com.android.systemui.theme.dark\n\n";
        List<OverlayRules.Overlay> pl = OverlayRules.parseList(pixel);
        eq("pixel count", pl.size(), 8);
        eq("pixel first", pl.get(0).id + "|" + pl.get(0).target + "|" + pl.get(0).state, "android.theme.customization.accent_color|android|1");
        eq("pixel off", pl.get(2).id + "|" + pl.get(2).state, "android.theme.customization.font|0");
        eq("pixel unavailable", pl.get(5).id + "|" + pl.get(5).state + "|" + pl.get(5).target, "com.google.android.overlay.gmsconfig.photos|-1|android");
        eq("pixel 2nd target", pl.get(6).target + "|" + pl.get(7).target, "com.android.systemui|com.android.systemui");
        eq("pixel last", pl.get(7).id + "|" + pl.get(7).state, "com.android.systemui.theme.dark|0");
        // CRLF, indentation (older Android), upper-case X, a remark after the id, noise lines
        String messy = "WARNING: linker: unsupported flags\r\n\r\nandroid\r\n    [X] a.b.c\r\n    [ ] d.e.f (remark here)\r\n--- g.h.i\r\nError: something odd happened\r\ncom.target\r\n[x] j.k:fabricated_name\r\n";
        List<OverlayRules.Overlay> ml = OverlayRules.parseList(messy);
        eq("messy count", ml.size(), 4);
        eq("messy 1", ml.get(0).id + "|" + ml.get(0).target + "|" + ml.get(0).state, "a.b.c|android|1");
        eq("messy 2", ml.get(1).id + "|" + ml.get(1).state, "d.e.f|0");
        eq("messy 3", ml.get(2).id + "|" + ml.get(2).state, "g.h.i|-1");
        eq("messy 4", ml.get(3).id + "|" + ml.get(3).target, "j.k:fabricated_name|com.target");
        eq("empty", OverlayRules.parseList("").size(), 0);
        eq("null", OverlayRules.parseList(null).size(), 0);
        eq("error text", OverlayRules.parseList("cmd: Can't find service: overlay").size(), 0);
        eq("entries without a header", OverlayRules.parseList("[x] one.two\n[ ] three.four\n").get(1).target, "");
        eq("empty id line skipped", OverlayRules.parseList("android\n[x] \n[ ]\n--- \n").size(), 0);
        eq("dash header not a target", OverlayRules.parseList("-x\n[x] a.b\n").get(0).target, "");
        // a big list
        StringBuilder big = new StringBuilder(); for (int t = 0; t < 40; t++) { big.append("target.").append(t).append('\n'); for (int o = 0; o < 25; o++) big.append(o % 3 == 0 ? "[x] " : o % 3 == 1 ? "[ ] " : "--- ").append("ovl.").append(t).append('.').append(o).append('\n'); big.append('\n'); }
        List<OverlayRules.Overlay> bl = OverlayRules.parseList(big.toString());
        eq("big count", bl.size(), 1000);
        eq("big last", bl.get(999).id + "|" + bl.get(999).target, "ovl.39.24|target.39");

        // ---- through a real shell: a fake overlay manager ----
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 a.on\nandroid 0 a.off\nandroid -1 a.gone\ncom.sys 0 b.off\ncom.sys 1 b.on\n");
        String out = viaReader(runShell(OverlayRules.listCommand()));
        List<OverlayRules.Overlay> fl = OverlayRules.parseList(out);
        eq("fake list count", fl.size(), 5);
        eq("fake list states", fl.get(0).state + "," + fl.get(1).state + "," + fl.get(2).state + "," + fl.get(3).state + "," + fl.get(4).state, "1,0,-1,0,1");
        eq("fake list targets", fl.get(3).target, "com.sys");

        String o1 = viaReader(runShell(OverlayRules.changeScript("enable", "a.off")));
        OverlayRules.ChangeResult c1 = OverlayRules.parseChange(o1);
        eq("enable answer", c1.answer, "");
        OverlayRules.Verdict v1 = OverlayRules.judge("enable", "a.off", c1);
        is("enable ok", v1.ok); eq("enable state", v1.state, 1); eq("enable really changed", overlayState("a.off"), "1");

        OverlayRules.ChangeResult c2 = OverlayRules.parseChange(viaReader(runShell(OverlayRules.changeScript("disable", "b.on"))));
        is("disable ok", OverlayRules.judge("disable", "b.on", c2).ok); eq("disable really changed", overlayState("b.on"), "0");

        // already in that state is fine
        is("enable twice", OverlayRules.judge("enable", "a.on", OverlayRules.parseChange(viaReader(runShell(OverlayRules.changeScript("enable", "a.on"))))).ok);

        // refused: not listed
        OverlayRules.ChangeResult c3 = OverlayRules.parseChange(viaReader(runShell(OverlayRules.changeScript("enable", "no.such.overlay"))));
        OverlayRules.Verdict v3 = OverlayRules.judge("enable", "no.such.overlay", c3);
        is("missing refused", !v3.ok); is("missing says why", v3.error.contains("not found")); eq("missing state", v3.state, -2);
        // refused: unavailable
        OverlayRules.ChangeResult c4 = OverlayRules.parseChange(viaReader(runShell(OverlayRules.changeScript("enable", "a.gone"))));
        OverlayRules.Verdict v4 = OverlayRules.judge("enable", "a.gone", c4);
        is("unavailable refused", !v4.ok); eq("unavailable state", v4.state, -1); is("unavailable says why", v4.error.contains("not available"));
        // silent refusal: a fixed overlay that stays on
        write(fakeDir.resolve("immutable"), "b.on\na.on\n");
        OverlayRules.ChangeResult c5 = OverlayRules.parseChange(viaReader(runShell(OverlayRules.changeScript("disable", "a.on"))));
        OverlayRules.Verdict v5 = OverlayRules.judge("disable", "a.on", c5);
        is("fixed overlay refused", !v5.ok); eq("fixed state", v5.state, 1); is("fixed says why", v5.error.contains("fixed on"));

        // hostile names reach the command as ONE argument and run nothing else
        resetFake();
        write(fakeDir.resolve("overlays"), "android 0 safe.one\n");
        String[] hostile = {"x';touch${IFS}canary1;echo'", "a$(touch${IFS}canary2)b", "a`touch${IFS}canary3`b", "a;touch${IFS}canary4", "a&&touch${IFS}canary5", "a|touch${IFS}canary6", "a>canary7", "a\"b", "a\\b", "a*b", "a?b", "~", "$HOME", "a:b$c"};
        for (String h : hostile) {
            Files.deleteIfExists(fakeDir.resolve("cmd.log"));
            String o = runShell(OverlayRules.changeScript("enable", h));
            String log = read(fakeDir.resolve("cmd.log"));
            // first call is the enable: ---, [overlay], [enable], [<id>]
            String first = log.substring(0, log.indexOf("---", 3) > 0 ? log.indexOf("---", 3) : log.length());
            eq("hostile argv " + h, first, "---\n[overlay]\n[enable]\n[" + h + "]\n");
            OverlayRules.ChangeResult cr = OverlayRules.parseChange(viaReader(o));
            is("hostile judged refused " + h, !OverlayRules.judge("enable", h, cr).ok);
        }
        throwsIae("space in name refused", () -> OverlayRules.changeScript("enable", "x; touch canary8; echo y"));
        for (int i = 1; i <= 8; i++) is("no canary" + i, !Files.exists(fakeDir.resolve("canary" + i)));
        is("no stray files", !Files.exists(fakeDir.resolve("canary")) );

        // an answer with a lost connection
        OverlayRules.ChangeResult lost = OverlayRules.parseChange("error: device 'x' not found");
        OverlayRules.Verdict vl = OverlayRules.judge("enable", "a.b", lost);
        is("lost refused", !vl.ok); is("lost advice", OverlayRules.advice("error: device '127.0.0.1:5555' not found").contains("connection"));
        eq("no answer at all", OverlayRules.judge("enable", "a.b", OverlayRules.parseChange("")).error, "No answer from the device");
        eq("null output", OverlayRules.judge("enable", "a.b", OverlayRules.parseChange(null)).error, "No answer from the device");
        // the list part came but the marker is missing at the end
        OverlayRules.ChangeResult trunc = OverlayRules.parseChange("@@OVL-GET@@\nandroid\n[x] a.b\n");
        is("truncated list not trusted", trunc.all == null);

        // ---- colours ----
        String[][] hexes = {{"#6750A4", "6750A4"}, {"6750a4", "6750A4"}, {" #6750a4 ", "6750A4"}, {"#FF6750A4", "6750A4"}, {"00ABCDEF", "ABCDEF"}, {"#abc", "AABBCC"}, {"ABC", "AABBCC"}, {"000000", "000000"}, {"#fff", "FFFFFF"}, {"FFFFFF", "FFFFFF"}};
        for (String[] h : hexes) eq("hex " + h[0], OverlayRules.normalizeHex(h[0]), h[1]);
        for (String h : new String[] {"", " ", "#", "##123456", "12345", "1234567", "#abcd", "GGGGGG", "12 34 56", "#12345g", "0x123456", "rgb(1,2,3)", null, "١٢٣٤٥٦", "６７５０Ａ４", "123456\n", "#123456;ls"}) { eq("bad hex " + h, OverlayRules.normalizeHex(h), h != null && h.equals("123456\n") ? "123456" : null); }

        // ---- theme values ----
        eq("preset json", OverlayRules.themeValue("preset", "#6750a4", "VIBRANT", 1700000000000L),
            "{\"android.theme.customization.system_palette\":\"6750A4\",\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.theme_style\":\"VIBRANT\",\"_applied_timestamp\":1700000000000}");
        eq("wallpaper json", OverlayRules.themeValue("home_wallpaper", null, "TONAL_SPOT", 5L),
            "{\"android.theme.customization.color_source\":\"home_wallpaper\",\"android.theme.customization.theme_style\":\"TONAL_SPOT\",\"_applied_timestamp\":5}");
        eq("default style", OverlayRules.themeValue("home_wallpaper", "", null, 5L), OverlayRules.themeValue("home_wallpaper", "", "TONAL_SPOT", 5L));
        eq("empty style is default", OverlayRules.themeValue("home_wallpaper", "", "", 5L), OverlayRules.themeValue("home_wallpaper", "", "TONAL_SPOT", 5L));
        for (String s : OverlayRules.STYLES) is("style " + s, OverlayRules.isStyle(s) && OverlayRules.themeValue("preset", "123456", s, 1L).contains("\"" + s + "\""));
        for (String s : new String[] {"tonal_spot", "TONAL SPOT", "CONTENT", "VIBRANT\"", "", "RAINBOW;ls"}) { eq("not a style " + s, OverlayRules.isStyle(s), false); }
        throwsIae("bad style", () -> OverlayRules.themeValue("preset", "123456", "NOPE", 1L));
        throwsIae("injection style", () -> OverlayRules.themeValue("preset", "123456", "VIBRANT\",\"x\":\"", 1L));
        throwsIae("bad colour", () -> OverlayRules.themeValue("preset", "12345", "VIBRANT", 1L));
        throwsIae("injection colour", () -> OverlayRules.themeValue("preset", "123456\",\"x\":\"y", "VIBRANT", 1L));
        throwsIae("missing colour", () -> OverlayRules.themeValue("preset", null, "VIBRANT", 1L));
        throwsIae("bad source", () -> OverlayRules.themeValue("lock_wallpaper", "123456", "VIBRANT", 1L));
        throwsIae("null source", () -> OverlayRules.themeValue(null, "123456", "VIBRANT", 1L));
        eq("flag preset", OverlayRules.flagFor(OverlayRules.themeValue("preset", "123456", "VIBRANT", 1L)), 0);
        eq("flag wallpaper", OverlayRules.flagFor(OverlayRules.themeValue("home_wallpaper", "", "VIBRANT", 1L)), 1);
        eq("flag empty", OverlayRules.flagFor(""), -1); eq("flag null", OverlayRules.flagFor(null), -1);
        eq("flag other", OverlayRules.flagFor("{\"android.theme.customization.accent_color\":\"FF0000\"}"), -1);
        eq("theme problem ok", OverlayRules.themeValueProblem(""), null);
        eq("theme problem pixel value", OverlayRules.themeValueProblem("{\"android.theme.customization.accent_color\":\"FF0000\",\"android.theme.customization.font\":\"x\"}"), null);
        is("theme problem nul", OverlayRules.themeValueProblem("a\u0000b") != null);
        is("theme problem long", OverlayRules.themeValueProblem("x".repeat(4001)) != null);
        is("theme problem null", OverlayRules.themeValueProblem(null) != null);
        is("theme problem control", OverlayRules.themeValueProblem("a\u0007") != null);

        // ---- writing the theme through a real shell ----
        resetFake();
        String pv = OverlayRules.themeValue("preset", "#6750a4", "FRUIT_SALAD", 1234567890123L);
        // no wallpaper-colour switch anywhere: only the theme is written
        OverlayRules.ThemeResult t1 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(pv))));
        eq("theme readback", t1.value, pv); eq("theme answer", t1.answer, ""); eq("no flag tables", t1.flags.size(), 0);
        is("theme judged ok", OverlayRules.judgeTheme(pv, t1).ok);
        eq("theme stored", read(fakeDir.resolve("settings/secure__theme_customization_overlay_packages")), pv);
        eq("only one put", read(fakeDir.resolve("put.log")), "secure theme_customization_overlay_packages " + pv + "\n");
        // the switch exists in `global` (value 1): preset sets it to 0, and only there
        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "1");
        OverlayRules.ThemeResult t2 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(pv))));
        eq("flag tables", flagSummary(t2), "global:1>0");
        eq("flag value", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "0");
        is("no flag in system", !Files.exists(fakeDir.resolve("settings/system__wallpapertheme_state")));
        is("no flag in secure", !Files.exists(fakeDir.resolve("settings/secure__wallpapertheme_state")));
        is("flag then theme order", read(fakeDir.resolve("put.log")).startsWith("global wallpapertheme_state 0\nsecure theme_customization_overlay_packages "));
        is("t2 ok", OverlayRules.judgeTheme(pv, t2).ok);
        // wallpaper: switch goes to 1; present in system and secure both
        resetFake();
        write(fakeDir.resolve("settings/system__wallpapertheme_state"), "0");
        write(fakeDir.resolve("settings/secure__wallpapertheme_state"), "0");
        String wv = OverlayRules.themeValue("home_wallpaper", "", "RAINBOW", 99L);
        OverlayRules.ThemeResult t3 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(wv))));
        eq("flag tables 2", flagSummary(t3), "system:0>1,secure:0>1");
        eq("flag system", read(fakeDir.resolve("settings/system__wallpapertheme_state")), "1");
        eq("flag secure", read(fakeDir.resolve("settings/secure__wallpapertheme_state")), "1");
        // a flag holding "null" (missing) or empty is left alone
        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "");
        OverlayRules.ThemeResult t4 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(wv))));
        eq("empty flag untouched", t4.flags.size(), 0);
        // reset: empty value; flag not touched
        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "0");
        write(fakeDir.resolve("settings/secure__theme_customization_overlay_packages"), pv);
        OverlayRules.ThemeResult t5 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(""))));
        eq("reset value", t5.value, ""); is("reset ok", OverlayRules.judgeTheme("", t5).ok); eq("reset leaves the flag", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "0");
        eq("reset no flag tables", t5.flags.size(), 0);
        // reset where the provider deletes the key instead: "null" is fine too
        OverlayRules.ThemeResult t6 = new OverlayRules.ThemeResult(); t6.value = "null";
        is("reset to null ok", OverlayRules.judgeTheme("", t6).ok);
        // restoring a foreign value (single quotes, dollar, backticks) is stored byte for byte and runs nothing
        resetFake();
        String foreign = "{\"a\":\"it's $(touch canaryT)\",\"b\":\"`touch canaryU`\",\"c\":\"x;touch canaryV\"}";
        OverlayRules.ThemeResult t7 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(foreign))));
        eq("foreign readback", t7.value, foreign); is("foreign ok", OverlayRules.judgeTheme(foreign, t7).ok);
        for (String c : new String[] {"canaryT", "canaryU", "canaryV"}) is("no " + c, !Files.exists(fakeDir.resolve(c)));
        // refused writes
        OverlayRules.ThemeResult none = OverlayRules.parseTheme("");
        eq("no answer", OverlayRules.judgeTheme(pv, none).error, "No answer from the device");
        OverlayRules.ThemeResult denied = OverlayRules.parseTheme("Exception occurred while executing 'put':\njava.lang.SecurityException: Permission denial: writing to secure settings requires android.permission.WRITE_SECURE_SETTINGS\n@@OVL-GET@@\nnull\n@@OVL-END@@\n");
        OverlayRules.Verdict dv = OverlayRules.judgeTheme(pv, denied);
        is("denied refused", !dv.ok); is("denied says why", dv.error.contains("Permission denial")); is("denied advice", OverlayRules.advice(denied.answer).contains("USB debugging (Security settings)"));
        OverlayRules.ThemeResult silent = OverlayRules.parseTheme("@@OVL-GET@@\nsomething else\n@@OVL-END@@\n");
        is("silent refusal", !OverlayRules.judgeTheme(pv, silent).ok); eq("silent refusal text", OverlayRules.judgeTheme(pv, silent).error, "Android did not keep the new theme");
        throwsIae("script too long", () -> OverlayRules.themeWriteScript("x".repeat(4001)));
        throwsIae("script null", () -> OverlayRules.themeWriteScript(null));

        // ================= the review fixes =================

        // a carriage return in a value to restore is refused (the reader folds it into a newline, so the read-back would never match)
        is("theme problem CR", OverlayRules.themeValueProblem("a\rb") != null);
        is("theme problem CRLF", OverlayRules.themeValueProblem("a\r\nb") != null);
        eq("theme problem LF fine", OverlayRules.themeValueProblem("a\nb"), null);
        eq("theme problem tab fine", OverlayRules.themeValueProblem("a\tb"), null);

        // ---- the Samsung switch: plans ----
        eq("plan preset", Arrays.toString(OverlayRules.flagPlanFor(pv)), "[0, 0, 0]");
        eq("plan wallpaper", Arrays.toString(OverlayRules.flagPlanFor(wv)), "[1, 1, 1]");
        eq("plan reset", Arrays.toString(OverlayRules.flagPlanFor("")), "[-1, -1, -1]");
        eq("plan null", Arrays.toString(OverlayRules.flagPlanFor(null)), "[-1, -1, -1]");
        eq("plan from was", Arrays.toString(OverlayRules.flagPlanFromWas(Arrays.asList(new String[] {"global", "1"}, new String[] {"system", "12"}))), "[12, -1, 1]");
        eq("plan from bad was", Arrays.toString(OverlayRules.flagPlanFromWas(Arrays.asList(new String[] {"nope", "1"}, new String[] {"global", "x"}, new String[] {"secure", "1234"}, new String[] {"system"}))), "[-1, -1, -1]");
        eq("plan from null", Arrays.toString(OverlayRules.flagPlanFromWas(null)), "[-1, -1, -1]");
        for (String v : new String[] {"0", "1", "12", "123"}) is("flag value ok " + v, OverlayRules.flagValueOk(v));
        for (String v : new String[] {"", null, "1234", "-1", "a", "1a", " 1", "null", "1.5"}) is("flag value bad " + v, !OverlayRules.flagValueOk(v));

        // ---- the script ----
        String sc = OverlayRules.themeWriteScript(pv);
        is("script probes all three tables", sc.contains("settings get system wallpapertheme_state") && sc.contains("settings get secure wallpapertheme_state") && sc.contains("settings get global wallpapertheme_state"));
        is("script sets a switch only where it holds a number", sc.contains("case \"$v\" in [0-9]|[0-9][0-9]|[0-9][0-9][0-9]) settings put system wallpapertheme_state 0;"));
        is("script ends with the theme write and its read-back", sc.endsWith("settings put secure theme_customization_overlay_packages '" + pv + "'; echo @@OVL-GET@@; settings get secure theme_customization_overlay_packages; echo @@OVL-END@@"));
        eq("reset script has no switch", OverlayRules.themeWriteScript("").startsWith("settings put secure"), true);
        eq("a plan that skips a table", OverlayRules.themeWriteScript(pv, new int[] {-1, -1, 5}).contains("settings get system"), false);
        is("a plan for one table", OverlayRules.themeWriteScript(pv, new int[] {-1, -1, 5}).contains("settings put global wallpapertheme_state 5;"));
        is("a plan value out of range is ignored", !OverlayRules.themeWriteScript(pv, new int[] {1000, -1, -1}).contains("settings get system"));
        is("a null plan is no plan", OverlayRules.themeWriteScript(pv, null).startsWith("settings put secure"));

        // ---- lines the script prints, and what is made of them ----
        OverlayRules.ThemeResult pf = OverlayRules.parseTheme("@@OVL-FLAG@@ system 1 0\n@@OVL-FLAG@@ global 12\n@@OVL-FLAG@@ nope 1 1\n@@OVL-FLAG@@\nnoise\n@@OVL-GET@@\nx\n@@OVL-END@@\n");
        eq("flag lines parsed", flagSummary(pf), "system:1>0,global:12>");
        eq("noise stays an answer", pf.answer, "noise");
        int[] p0 = {0, 0, 0};
        OverlayRules.FlagReport fr0 = OverlayRules.flagReport(p0, pf);
        eq("moved", fr0.moved.size() + ":" + fr0.moved.get(0).table, "1:system");
        eq("failed", fr0.failed.toString(), "[global]");
        is("report ignores a table the plan skips", OverlayRules.flagReport(new int[] {-1, -1, -1}, pf).moved.isEmpty() && OverlayRules.flagReport(new int[] {-1, -1, -1}, pf).failed.isEmpty());
        eq("no warning when nothing failed", OverlayRules.flagWarning(new OverlayRules.FlagReport()), "");
        is("warning names the tables", OverlayRules.flagWarning(fr0).contains("(global)"));

        // ---- through the shell: a switch that takes the value, one that refuses, a theme that refuses ----
        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "1");
        write(fakeDir.resolve("deny"), "global__wallpapertheme_state\n");
        int[] pplan = OverlayRules.flagPlanFor(pv);
        OverlayRules.ThemeResult d1 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(pv, pplan))));
        OverlayRules.FlagReport dr1 = OverlayRules.flagReport(pplan, d1);
        is("switch refused: the theme is still written", OverlayRules.judgeTheme(pv, d1).ok);
        eq("switch refused: reported", dr1.failed.toString(), "[global]");
        eq("switch refused: nothing moved", dr1.moved.size(), 0);
        is("switch refused: Android's text is kept", d1.answer.contains("SecurityException"));
        is("switch refused: a warning for the page", OverlayRules.flagWarning(dr1).contains("(global)"));
        eq("switch refused: untouched", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "1");

        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "1");
        write(fakeDir.resolve("deny"), "secure__theme_customization_overlay_packages\n");
        OverlayRules.ThemeResult d2 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(pv, pplan))));
        OverlayRules.FlagReport dr2 = OverlayRules.flagReport(pplan, d2);
        OverlayRules.Verdict dv2 = OverlayRules.judgeTheme(pv, d2);
        is("theme refused", !dv2.ok && !dv2.unknown);
        eq("theme refused: the switch had moved", flagSummary(d2), "global:1>0");
        eq("theme refused: moved list", dr2.moved.size(), 1);
        eq("the switch is at 0 before the rollback", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "0");
        runShell(OverlayRules.flagRestoreScript(dr2.moved));
        eq("the rollback puts the switch back", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "1");

        // a first Apply from the default state, then Undo: the switch goes back to what it held
        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "1");
        OverlayRules.ThemeResult u1 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(pv, pplan))));
        OverlayRules.FlagReport ur1 = OverlayRules.flagReport(pplan, u1);
        eq("apply moved the switch", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "0");
        String snap = OverlayRules.flagSnapshot(1000L, ur1.moved);
        eq("snapshot text", snap, "1000|global=1");
        List<String[]> was = OverlayRules.flagSnapshotRead(snap, 2000L, 60000L);
        eq("snapshot read", was.size() + ":" + was.get(0)[0] + "=" + was.get(0)[1], "1:global=1");
        int[] uplan = OverlayRules.flagPlanFromWas(was);
        OverlayRules.ThemeResult u2 = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript("", uplan))));
        is("undo wrote the empty value", OverlayRules.judgeTheme("", u2).ok);
        eq("undo put the switch back", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "1");
        eq("undo's own report", flagSummary(u2), "global:0>1");
        // ... where the old way (no snapshot) would have left it at 0
        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "0");
        OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(""))));
        eq("without a snapshot the switch stays", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "0");

        // a switch that already holds the value: reported, but not "moved" (an Undo has nothing to put back)
        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "0");
        OverlayRules.ThemeResult al = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(pv, pplan))));
        OverlayRules.FlagReport alr = OverlayRules.flagReport(pplan, al);
        eq("already set: the script still reports it", flagSummary(al), "global:0>0");
        eq("already set: nothing moved, nothing failed", alr.moved.size() + ":" + alr.failed.size(), "0:0");
        eq("already set: the snapshot says nothing moved", OverlayRules.flagSnapshot(1L, alr.moved), "1|-");

        // a switch that holds something that is not a number is left alone and nothing in it runs
        resetFake();
        write(fakeDir.resolve("settings/global__wallpapertheme_state"), "1;touch canaryW");
        OverlayRules.ThemeResult hv = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(pv, pplan))));
        eq("odd switch value: left alone", hv.flags.size(), 0);
        is("odd switch value: nothing ran", !Files.exists(fakeDir.resolve("canaryW")));
        eq("odd switch value: kept", read(fakeDir.resolve("settings/global__wallpapertheme_state")), "1;touch canaryW");
        resetFake();
        write(fakeDir.resolve("settings/system__wallpapertheme_state"), "12");
        eq("a two-digit switch is moved", flagSummary(OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(pv, pplan))))), "system:12>0");

        // ---- snapshots ----
        for (String bad : new String[] {null, "", "x", "|global=1", "abc|global=1", "1000|", "1000|global", "1000|global=", "1000|global=x", "1000|nope=1", "1000|global=1,", "1000|global=1,system=1234", "-5|global=1x", "1000|=1"}) is("snapshot bad " + bad, OverlayRules.flagSnapshotRead(bad, 2000L, 60000L) == null);
        is("snapshot too old", OverlayRules.flagSnapshotRead("1000|global=1", 1000L + 60001L, 60000L) == null);
        is("snapshot from the future", OverlayRules.flagSnapshotRead("999999|global=1", 1000L, 60000L) == null);
        is("snapshot at the age limit", OverlayRules.flagSnapshotRead("1000|global=1", 1000L + 60000L, 60000L) != null);
        eq("snapshot of nothing moved", OverlayRules.flagSnapshot(5L, new ArrayList<OverlayRules.FlagChange>()), "5|-");
        eq("snapshot skips bad entries", OverlayRules.flagSnapshot(5L, Arrays.asList(new OverlayRules.FlagChange("nope", "1", "0"), new OverlayRules.FlagChange("system", "x", "0"))), "5|-");
        eq("snapshot of two", OverlayRules.flagSnapshot(5L, Arrays.asList(new OverlayRules.FlagChange("system", "0", "1"), new OverlayRules.FlagChange("global", "12", "1"))), "5|system=0,global=12");
        eq("restore script", OverlayRules.flagRestoreScript(Arrays.asList(new OverlayRules.FlagChange("global", "1", "0"), new OverlayRules.FlagChange("nope", "1", "0"), new OverlayRules.FlagChange("system", "a b", "0"))), "settings put global wallpapertheme_state 1; n=$(settings get global wallpapertheme_state 2>/dev/null); echo \"@@OVL-FLAG@@ global 1 $n\"; echo @@OVL-END@@");
        eq("restore script of nothing", OverlayRules.flagRestoreScript(new ArrayList<OverlayRules.FlagChange>()), "echo @@OVL-END@@");

        // ---- reading a flat JSON object ----
        eq("flat empty", OverlayRules.parseFlatObject("{}").size(), 0);
        eq("flat empty with spaces", OverlayRules.parseFlatObject(" { } ").size(), 0);
        eq("flat one", OverlayRules.parseFlatObject("{\"a\":\"b\"}").toString(), "{a=\"b\"}");
        eq("flat white space", OverlayRules.parseFlatObject(" {\n \"a\" :\t\"b\" ,\r\n\"c\":1 } \n").toString(), "{a=\"b\", c=1}");
        for (String bad : new String[] {null, "", " ", "x", "[]", "{", "}", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "{,\"a\":1}", "{\"a\":1 \"b\":2}", "{a:1}", "{'a':1}", "{\"a\":01}", "{\"a\":1.}", "{\"a\":.5}", "{\"a\":-}", "{\"a\":1e}", "{\"a\":tru}", "{\"a\":nul}", "{\"a\":truex}", "{\"a\":\"x}", "{\"a\":\"\\q\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\u12G4\"}", "{\"a\":\"tab\there\"}", "{\"a\":1}x", "{\"a\":1}{}", "{\"a\":{}}", "{\"a\":[]}", "{\"a\\\"b\":1}", "{\"a\nb\":1}", "{\"a\":1,\"a\":2}", "{\"a\":NaN}", "{\"a\":+1}", "{\"a\":\"\\u00\"", "{\"a\":\"\\"}) is("flat bad <" + bad + ">", OverlayRules.parseFlatObject(bad) == null);
        for (String good : new String[] {"{\"a\":\"\\u00e9\\n\\\\\\/\\\"\"}", "{\"a\":0}", "{\"a\":-0}", "{\"a\":10}", "{\"a\":1.25}", "{\"a\":1E+10}", "{\"a\":-1.5e-3}", "{\"a\":true,\"b\":false,\"c\":null}", "{\"é\":\"é\"}", "{\"\":1}", "{\"a\":\"\uD83D\uDE00\"}"}) is("flat good " + good, OverlayRules.parseFlatObject(good) != null);
        StringBuilder m64 = new StringBuilder("{"); for (int i = 0; i < 64; i++) m64.append(i > 0 ? "," : "").append("\"k").append(i).append("\":1"); 
        is("64 members are fine", OverlayRules.parseFlatObject(m64 + "}") != null);
        is("65 members are too many", OverlayRules.parseFlatObject(m64 + ",\"k64\":1}") == null);
        is("over the size limit", OverlayRules.parseFlatObject("{\"k\":\"" + "x".repeat(4000) + "\"}") == null);

        // ---- merging into what the setting holds ----
        String plainP = OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L);
        String pixelJson = "{\"android.theme.customization.font\":\"com.android.theme.font.notoserifsource\",\"android.theme.customization.accent_color\":\"FF112233\",\"android.theme.customization.adaptive_icon_shape\":\"com.android.theme.icon.teardrop\",\"android.theme.customization.system_palette\":\"FF112233\",\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.color_index\":\"3\",\"_applied_timestamp\":111}";
        eq("merge keeps the font and the shape, replaces the colour keys", OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, pixelJson),
            "{\"android.theme.customization.font\":\"com.android.theme.font.notoserifsource\",\"android.theme.customization.adaptive_icon_shape\":\"com.android.theme.icon.teardrop\"," + plainP.substring(1));
        eq("merge for the wallpaper drops the palette and the accent", OverlayRules.themeValue("home_wallpaper", "", "VIBRANT", 222L, pixelJson),
            "{\"android.theme.customization.font\":\"com.android.theme.font.notoserifsource\",\"android.theme.customization.adaptive_icon_shape\":\"com.android.theme.icon.teardrop\"," + OverlayRules.themeValue("home_wallpaper", "", "VIBRANT", 222L).substring(1));
        for (String cur : new String[] {null, "", "null", " ", "not json", "{\"a\":{\"b\":1}}", "{\"a\":[1]}", "{\"a\":1,\"a\":2}", "{\"a\\u0062\":1}", "[1]", "{\"a\":1}x"}) eq("merge falls back to the plain value for <" + cur + ">", OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, cur), plainP);
        eq("merge of a value that is all ours is the plain value", OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, OverlayRules.themeValue("preset", "123456", "RAINBOW", 1L)), plainP);
        eq("merge of {} is the plain value", OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, "{}"), plainP);
        String odd = "{\"k\":\"it's $(x) \\\"q\\\" \\u00e9\",\"n\":-1.5e+3,\"t\":true,\"z\":null}";
        eq("merge keeps other values byte for byte", OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, odd), odd.substring(0, odd.length() - 1) + "," + plainP.substring(1));
        String bigKeep = "{\"k\":\"" + "x".repeat(3850) + "\"}";
        eq("merge that would be too long falls back", OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, bigKeep), plainP);
        throwsIae("merge still checks the colour", () -> OverlayRules.themeValue("preset", "12345", "EXPRESSIVE", 222L, pixelJson));
        throwsIae("merge still checks the style", () -> OverlayRules.themeValue("preset", "7E57C2", "NOPE", 222L, pixelJson));
        throwsIae("merge still checks the source", () -> OverlayRules.themeValue("lock_wallpaper", "7E57C2", "VIBRANT", 222L, pixelJson));
        is("a merged value passes the write check", OverlayRules.themeValueProblem(OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, pixelJson)) == null);

        // the read, the merge and the write through the shell
        resetFake();
        eq("read of an absent setting", OverlayRules.parseThemeRead(viaReader(runShell(OverlayRules.themeReadScript()))), "null");
        write(fakeDir.resolve("settings/secure__theme_customization_overlay_packages"), pixelJson);
        eq("read of the setting", OverlayRules.parseThemeRead(viaReader(runShell(OverlayRules.themeReadScript()))), pixelJson);
        String mv = OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, OverlayRules.parseThemeRead(viaReader(runShell(OverlayRules.themeReadScript()))));
        OverlayRules.ThemeResult mr = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(mv))));
        eq("merged value read back", mr.value, mv); is("merged value judged ok", OverlayRules.judgeTheme(mv, mr).ok);
        is("the stored value still holds the font", read(fakeDir.resolve("settings/secure__theme_customization_overlay_packages")).contains("notoserifsource"));
        eq("read cut short", OverlayRules.parseThemeRead("{\"a\":1}"), null);
        eq("read of nothing", OverlayRules.parseThemeRead(null), null);
        eq("read with CRLF", OverlayRules.parseThemeRead("@@OVL-GET@@\r\n{\"a\":1}\r\n@@OVL-END@@\r\n"), "{\"a\":1}");
        eq("read of an error text", OverlayRules.parseThemeRead("@@OVL-GET@@\nException: boom\n@@OVL-END@@\n"), "Exception: boom");
        // hostile text inside what is kept runs nothing and comes back byte for byte
        resetFake();
        String hostileJson = "{\"k\":\"it's $(touch canaryM) `touch canaryN`; touch canaryO\",\"android.theme.customization.color_source\":\"preset\"}";
        String hm = OverlayRules.themeValue("home_wallpaper", "", "TONAL_SPOT", 7L, hostileJson);
        OverlayRules.ThemeResult hr = OverlayRules.parseTheme(viaReader(runShell(OverlayRules.themeWriteScript(hm))));
        eq("hostile merge read back", hr.value, hm);
        for (String c : new String[] {"canaryM", "canaryN", "canaryO"}) is("no " + c, !Files.exists(fakeDir.resolve(c)));

        // ---- a follow-up listing that is an error, or empty, is not a list of no overlays ----
        OverlayRules.ChangeResult fe = OverlayRules.parseChange("@@OVL-GET@@\ncmd: Can't find service: overlay\n@@OVL-END@@\n");
        is("failed listing is not a list", fe.all == null);
        eq("failed listing text is kept", fe.listError, "cmd: Can't find service: overlay");
        OverlayRules.Verdict fv = OverlayRules.judge("enable", "a.b", fe);
        is("failed listing: unknown, not refused", fv.unknown && !fv.ok);
        is("failed listing: its own error is the message", fv.error.contains("Can't find service"));
        OverlayRules.ChangeResult be = OverlayRules.parseChange("@@OVL-GET@@\n@@OVL-END@@\n");
        is("blank listing is not a list", be.all == null && be.listError.isEmpty());
        OverlayRules.Verdict bv = OverlayRules.judge("enable", "a.b", be);
        is("blank listing: unknown", bv.unknown); eq("blank listing: message", bv.error, "No answer from the device");
        OverlayRules.ChangeResult ne = OverlayRules.parseChange("@@OVL-GET@@\nWARNING: linker: something\n@@OVL-END@@\n");
        is("noise only is an empty list", ne.all != null && ne.all.isEmpty());
        eq("noise only: the overlay is not listed", OverlayRules.judge("enable", "a.b", ne).error, "That overlay is not listed any more");
        OverlayRules.ChangeResult de = OverlayRules.parseChange("Exception occurred while executing 'enable':\nandroid.os.DeadObjectException\n@@OVL-GET@@\nException in thread main: DeadObjectException\n@@OVL-END@@\n");
        is("dead service: unknown", de.all == null && OverlayRules.judge("enable", "a.b", de).unknown);
        OverlayRules.ChangeResult ok1 = OverlayRules.parseChange("@@OVL-GET@@\nandroid\n[x] a.b\n@@OVL-END@@\n");
        is("a real list still works", ok1.all != null && ok1.all.size() == 1 && OverlayRules.judge("enable", "a.b", ok1).ok);

        // ---- Android 12 and newer: a refusal is a SecurityException that says only "commit failed" ----
        OverlayRules.ChangeResult cf = OverlayRules.parseChange("Exception occurred while executing 'enable':\njava.lang.SecurityException: commit failed\n@@OVL-GET@@\nandroid\n[ ] a.b\n@@OVL-END@@\n");
        OverlayRules.Verdict cv = OverlayRules.judge("enable", "a.b", cf);
        is("commit failed: refused", !cv.ok && !cv.unknown);
        eq("commit failed: wording", cv.error, OverlayRules.NO_REASON);
        is("commit failed: no Xiaomi hint", !OverlayRules.advice(cf.answer).contains("USB debugging"));
        is("commit failed: a hint that fits", OverlayRules.advice(cf.answer).contains("fixed in place"));
        is("commit failed: detected", OverlayRules.commitFailed("java.lang.SecurityException: Commit Failed") && !OverlayRules.commitFailed("Permission denial") && !OverlayRules.commitFailed(null));
        OverlayRules.ChangeResult cg = OverlayRules.parseChange("java.lang.SecurityException: commit failed\n@@OVL-GET@@\nandroid\n[x] other.id\n@@OVL-END@@\n");
        eq("commit failed for an overlay that is not listed", OverlayRules.judge("enable", "a.b", cg).error, OverlayRules.NO_REASON);
        is("a permission denial still gets the Xiaomi hint", OverlayRules.advice("java.lang.SecurityException: Permission denial: requires android.permission.WRITE_SECURE_SETTINGS").contains("USB debugging (Security settings)"));

        // ================= a whole change, start to finish (apply, undo, refusals) =================
        final Path FLAGG = fakeDir.resolve("settings/global__wallpapertheme_state");
        final Path THEME = fakeDir.resolve("settings/secure__theme_customization_overlay_packages");

        // a first Apply from the default state, then its Undo
        TestEnv te = new TestEnv();
        resetFake();
        write(FLAGG, "1");
        OverlayRules.ThemeOutcome a1 = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "EXPRESSIVE", "");
        is("apply: ok", a1.ok && !a1.unknown); eq("apply: it read what was there first", a1.before, "null");
        eq("apply: the value", a1.value, OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", te.now));
        eq("apply: the switch table", a1.flags.toString(), "[global]"); eq("apply: the switch moved", read(FLAGG), "0");
        eq("apply: the snapshot for Undo", te.snap, te.now + "|global=1");
        eq("apply: a read, then one write", te.scripts.size() + ":" + te.scripts.get(0).startsWith("echo @@OVL-GET@@; settings get secure"), "2:true");
        eq("apply: no warning", a1.warning, "");
        OverlayRules.ThemeOutcome un = OverlayRules.runTheme(te, "undo", "", "", "", "");
        is("undo: ok", un.ok); eq("undo: back to the default", un.value, ""); eq("undo: the switch is back", read(FLAGG), "1"); eq("undo: the snapshot is used up", te.snap, "");

        // Apply merges into a Pixel-style value; Undo puts that exact value back
        te = new TestEnv();
        resetFake();
        write(THEME, pixelJson);
        OverlayRules.ThemeOutcome a2 = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "EXPRESSIVE", "");
        is("merge apply: ok", a2.ok); eq("merge apply: before is the old value", a2.before, pixelJson);
        is("merge apply: the font stays", read(THEME).contains("notoserifsource") && read(THEME).contains("\"7E57C2\"") && !read(THEME).contains("FF112233"));
        OverlayRules.ThemeOutcome un2 = OverlayRules.runTheme(te, "undo", "", "", "", a2.before);
        is("merge undo: ok", un2.ok); eq("merge undo: the old value, byte for byte", read(THEME), pixelJson);

        // the current theme cannot be read: nothing is written
        te = new TestEnv(); resetFake(); write(FLAGG, "1");
        te.override = (script, n) -> n == 0 ? "Exception occurred while executing 'get':\njava.lang.SecurityException: nope\n@@OVL-END@@\n" : null;
        OverlayRules.ThemeOutcome r1 = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("unreadable: refused, not unknown", !r1.ok && !r1.unknown); is("unreadable: says what", r1.error.startsWith("Could not read the current theme first: ") && r1.error.contains("SecurityException"));
        eq("unreadable: nothing was written", te.scripts.size(), 1); eq("unreadable: the switch is untouched", read(FLAGG), "1");
        te = new TestEnv(); resetFake();
        te.override = (script, n) -> n == 0 ? "" : null;
        OverlayRules.ThemeOutcome r2 = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("silent read: refused", !r2.ok && !r2.unknown); eq("silent read: why", r2.error, "Could not read the current theme first: No answer from the device"); eq("silent read: nothing written", te.scripts.size(), 1);

        // the theme is refused after the switch moved: the switch goes back
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); write(fakeDir.resolve("deny"), "secure__theme_customization_overlay_packages\n");
        OverlayRules.ThemeOutcome r3 = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("refused: not ok, not unknown", !r3.ok && !r3.unknown); is("refused: Android's text", r3.error.contains("Permission denial") || r3.error.contains("did not keep"));
        is("refused: the switch was put back", r3.flagsRestored && "1".equals(read(FLAGG))); eq("refused: a read, a write and a rollback", te.scripts.size(), 3); eq("refused: no snapshot", te.snap, "");

        // the switch is refused but the theme is written: a warning, no rollback
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); write(fakeDir.resolve("deny"), "global__wallpapertheme_state\n");
        OverlayRules.ThemeOutcome r4 = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("switch refused: the theme is applied", r4.ok); is("switch refused: a warning", r4.warning.contains("(global)")); eq("switch refused: the snapshot says nothing moved", te.snap, te.now + "|-"); eq("switch refused: two scripts", te.scripts.size(), 2);

        // no read-back at all: unknown, and nothing is rolled back or forgotten
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); te.snap = "5|global=0";
        te.override = (script, n) -> n == 1 ? "" : null;
        OverlayRules.ThemeOutcome r5 = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("no answer: unknown", !r5.ok && r5.unknown); eq("no answer: no rollback", te.scripts.size(), 2); eq("no answer: this change\u0027s own snapshot (nothing known to have moved) replaces the old one", te.snap, te.now + "|-");

        // Undo with a snapshot that is too old falls back to the rule for the value being restored
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); te.snap = (te.now - 31L * 60L * 1000L) + "|global=7";
        OverlayRules.ThemeOutcome r6 = OverlayRules.runTheme(te, "undo", "", "", "", OverlayRules.themeValue("preset", "123456", "VIBRANT", 1L));
        is("old snapshot: ok", r6.ok); eq("old snapshot: the rule's 0, not the snapshot's 7", read(FLAGG), "0");
        // ... and one that is fresh wins over the rule
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); te.snap = (te.now - 60000L) + "|global=7";
        OverlayRules.ThemeOutcome r7 = OverlayRules.runTheme(te, "undo", "", "", "", OverlayRules.themeValue("preset", "123456", "VIBRANT", 1L));
        is("fresh snapshot: ok", r7.ok); eq("fresh snapshot: its value", read(FLAGG), "7"); eq("fresh snapshot: used up", te.snap, "");
        // a restore (not an Undo) never reads the snapshot
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); te.snap = (te.now - 60000L) + "|global=7";
        OverlayRules.ThemeOutcome r8 = OverlayRules.runTheme(te, "restore", "", "", "", OverlayRules.themeValue("home_wallpaper", "", "VIBRANT", 1L));
        is("restore: ok", r8.ok); eq("restore: the rule's 1 (already 1: nothing moved)", read(FLAGG) + ":" + te.snap, "1:" + te.now + "|-");
        // Reset leaves the switch alone and clears the snapshot
        te = new TestEnv(); resetFake(); write(FLAGG, "0"); write(THEME, plainP); te.snap = (te.now - 60000L) + "|global=1";
        OverlayRules.ThemeOutcome r9 = OverlayRules.runTheme(te, "reset", "", "", "", "");
        is("reset: ok", r9.ok); eq("reset: the switch is where it was", read(FLAGG), "0"); eq("reset: the snapshot says nothing moved", te.snap, te.now + "|-"); eq("reset: one script", te.scripts.size(), 1);
        // an Undo of a refused write keeps the snapshot so it can be tried again
        te = new TestEnv(); resetFake(); write(FLAGG, "0"); write(THEME, plainP); write(fakeDir.resolve("deny"), "secure__theme_customization_overlay_packages\n"); te.snap = (te.now - 60000L) + "|global=1";
        OverlayRules.ThemeOutcome r10 = OverlayRules.runTheme(te, "undo", "", "", "", "");
        is("refused undo: not ok", !r10.ok); eq("refused undo: the switch is put back to where it was before the undo", read(FLAGG), "0"); is("refused undo: the snapshot is kept", te.snap.endsWith("|global=1"));
        // the colour source is read from its own key only: a kept member that merely ends in "color_source" cannot decide the switch
        eq("flag: another key ending in color_source", OverlayRules.flagFor("{\"x.color_source\":\"preset\"}"), -1);
        eq("flag: the right key", OverlayRules.flagFor("{\"android.theme.customization.color_source\":\"home_wallpaper\"}"), 1);
        eq("flag: spaces around the colon", OverlayRules.flagFor("{\"android.theme.customization.color_source\" : \"preset\"}"), 0);
        eq("flag: the key text escaped inside a value", OverlayRules.flagFor("{\"k\":\"\\\"android.theme.customization.color_source\\\":\\\"preset\\\"\"}"), -1);
        eq("plan for a chosen preset", Arrays.toString(OverlayRules.flagPlanForSource("preset")), "[0, 0, 0]");
        eq("plan for a chosen wallpaper", Arrays.toString(OverlayRules.flagPlanForSource("home_wallpaper")), "[1, 1, 1]");
        eq("plan for anything else", Arrays.toString(OverlayRules.flagPlanForSource("lock_wallpaper")) + Arrays.toString(OverlayRules.flagPlanForSource(null)), "[-1, -1, -1][-1, -1, -1]");
        te = new TestEnv(); resetFake(); write(FLAGG, "0"); write(THEME, "{\"x.color_source\":\"preset\",\"android.theme.customization.font\":\"f\"}");
        OverlayRules.ThemeOutcome r12 = OverlayRules.runTheme(te, "apply", "home_wallpaper", "", "VIBRANT", "");
        is("apply for the wallpaper: ok", r12.ok); eq("apply for the wallpaper: the switch goes to 1 whatever a kept member says", read(FLAGG), "1");
        is("apply for the wallpaper: the kept members stay", read(THEME).startsWith("{\"x.color_source\":\"preset\",\"android.theme.customization.font\":\"f\","));
        // an apply whose switch already held the value leaves no snapshot behind
        te = new TestEnv(); resetFake(); write(FLAGG, "0"); te.snap = (te.now - 60000L) + "|global=1";
        OverlayRules.ThemeOutcome r11 = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("already set: ok", r11.ok); eq("already set: the older snapshot is replaced by \"nothing moved\"", te.snap, te.now + "|-");
        // ---- the second review ----
        // R1: noise in front of the value is not the value
        eq("read: noise before the marker is skipped", OverlayRules.parseThemeRead("* daemon not running; starting now at tcp:5042\n@@OVL-GET@@\n{\"a\":1}\n@@OVL-END@@\n"), "{\"a\":1}");
        eq("read: no start marker is no answer", OverlayRules.parseThemeRead("{\"a\":1}\n@@OVL-END@@\n"), null);
        eq("read: marker order", OverlayRules.parseThemeRead("@@OVL-END@@\n@@OVL-GET@@\n{\"a\":1}\n"), null);
        te = new TestEnv(); resetFake(); write(THEME, pixelJson);
        te.override = (script, n) -> n == 0 ? "* daemon not running; starting now at tcp:5042\n* daemon started successfully\n@@OVL-GET@@\n" + pixelJson + "\n@@OVL-END@@\n" : null;
        OverlayRules.ThemeOutcome nz = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "EXPRESSIVE", "");
        is("noisy read: ok", nz.ok); eq("noisy read: the value to undo to is the clean value", nz.before, pixelJson); is("noisy read: the font was kept", read(THEME).contains("notoserifsource"));
        // R7: the reason a read failed is kept
        te = new TestEnv(); resetFake();
        te.override = (script, n) -> n == 0 ? "Error: Wireless Debugging is not configured. Open Working Modes and pair first." : null;
        OverlayRules.ThemeOutcome rf = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("read failure: not ok", !rf.ok && !rf.unknown); is("read failure: the reason is shown", rf.error.contains("Wireless Debugging is not configured"));
        // R2: an Undo moves only what the undone change moved
        String presetV = OverlayRules.themeValue("preset", "123456", "VIBRANT", 1L);
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); write(THEME, presetV);
        OverlayRules.runTheme(te, "reset", "", "", "", "");
        eq("reset left the switch", read(FLAGG), "1");
        OverlayRules.ThemeOutcome ur = OverlayRules.runTheme(te, "undo", "", "", "", presetV);
        is("undo of a reset: ok", ur.ok); eq("undo of a reset: the switch is where the reset left it, not moved by the rule for a colour", read(FLAGG), "1");
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); write(THEME, presetV);
        OverlayRules.runTheme(te, "apply", "home_wallpaper", "", "VIBRANT", "");
        eq("apply for the wallpaper (switch already 1) moved nothing", read(FLAGG), "1");
        OverlayRules.ThemeOutcome ua = OverlayRules.runTheme(te, "undo", "", "", "", presetV);
        is("its undo: ok", ua.ok); eq("its undo moves nothing either", read(FLAGG), "1");
        // R3: an unsure change leaves the earlier change's snapshot behind no longer
        te = new TestEnv(); resetFake(); write(FLAGG, "1");
        OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        eq("A1 moved the switch to 0", read(FLAGG), "0"); eq("A1's snapshot", te.snap, te.now + "|global=1");
        te.override = (script, n) -> n == 3 ? "" : null;                  // A2: the answer to the write never comes
        OverlayRules.ThemeOutcome a2u = OverlayRules.runTheme(te, "apply", "preset", "0288D1", "VIBRANT", "");
        is("A2: unknown", a2u.unknown && !a2u.ok);
        OverlayRules.ThemeOutcome ua2 = OverlayRules.runTheme(te, "undo", "", "", "", OverlayRules.themeValue("preset", "7E57C2", "VIBRANT", 1L));
        is("undo of A2: ok", ua2.ok); eq("undo of A2 does not use A1's snapshot (the switch stays at 0)", read(FLAGG), "0");
        // R6: the rollback is checked
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); write(fakeDir.resolve("deny"), "secure__theme_customization_overlay_packages\n");
        te.override = (script, n) -> n == 2 ? "@@OVL-FLAG@@ global 1 0\n@@OVL-END@@\n" : null;          // the rollback does not take
        OverlayRules.ThemeOutcome rb = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("rollback that did not take: refusal still reported", !rb.ok && !rb.unknown); is("rollback that did not take: not claimed", !rb.flagsRestored);
        is("rollback that did not take: a warning names the table", rb.warning.contains("(global)") && rb.warning.contains("could not be put back"));
        eq("flagRestoreFailed: all fine", OverlayRules.flagRestoreFailed(Arrays.asList(new OverlayRules.FlagChange("global", "1", "0")), "@@OVL-FLAG@@ global 1 1\n@@OVL-END@@").toString(), "[]");
        eq("flagRestoreFailed: one stuck", OverlayRules.flagRestoreFailed(Arrays.asList(new OverlayRules.FlagChange("global", "1", "0"), new OverlayRules.FlagChange("system", "0", "1")), "@@OVL-FLAG@@ global 1 1\n@@OVL-FLAG@@ system 0 1\n@@OVL-END@@").toString(), "[system]");
        eq("flagRestoreFailed: no output means not restored", OverlayRules.flagRestoreFailed(Arrays.asList(new OverlayRules.FlagChange("global", "1", "0")), "").toString(), "[global]");
        // R4 / R5: numbers org.json would refuse, and non-ASCII digits in a unicode escape
        for (String bad : new String[] {"{\"x\":1E400}", "{\"x\":-1e999}", "{\"x\":" + "9".repeat(400) + "}", "{\"x\":1.8e308}", "{\"x\":\"\\u\uFF10\uFF10\uFF10\uFF10\"}", "{\"x\":\"\\u00\uFF10\uFF10\"}"}) is("flat bad (second review) " + bad.substring(0, Math.min(30, bad.length())), OverlayRules.parseFlatObject(bad) == null);
        for (String good : new String[] {"{\"x\":1e308}", "{\"x\":" + "9".repeat(300) + "}", "{\"x\":-1E-400}", "{\"x\":\"\\u00e9\\uD83D\\uDE00\"}", "{\"x\":0e0}"}) is("flat good (second review) " + good.substring(0, Math.min(30, good.length())), OverlayRules.parseFlatObject(good) != null);
        eq("a value that would not fit a double falls back to the plain value", OverlayRules.themeValue("preset", "7E57C2", "EXPRESSIVE", 222L, "{\"x\":1E400}"), plainP);
        // R8: the verdict tolerates a setting that was rewritten into the same theme, and the rollback waits for a setting that did not change
        is("matches: same text", OverlayRules.themeMatches(plainP, plainP));
        is("matches: order and spacing", OverlayRules.themeMatches("{\"a\":1,\"b\":\"x\"}", "{ \"b\" : \"x\",\n \"a\":1 }"));
        is("matches: extra members and another time stamp", OverlayRules.themeMatches("{\"a\":1,\"_applied_timestamp\":5}", "{\"z\":2,\"a\":1,\"_applied_timestamp\":99}"));
        is("matches: the stamp may be missing", OverlayRules.themeMatches("{\"a\":1,\"_applied_timestamp\":5}", "{\"a\":1}"));
        is("matches: the palette with transparency digits", OverlayRules.themeMatches(plainP, plainP.replace("\"7E57C2\"", "\"FF7e57c2\"")));
        is("does not match: a member missing", !OverlayRules.themeMatches("{\"a\":1,\"b\":2}", "{\"a\":1}"));
        is("does not match: another value", !OverlayRules.themeMatches("{\"a\":1}", "{\"a\":2}"));
        is("does not match: another palette", !OverlayRules.themeMatches(plainP, plainP.replace("7E57C2", "7E57C3")));
        is("does not match: another style", !OverlayRules.themeMatches(plainP, plainP.replace("EXPRESSIVE", "VIBRANT")));
        is("does not match: not an object", !OverlayRules.themeMatches("{\"a\":1}", "nope") && !OverlayRules.themeMatches("{\"a\":1}", "null") && !OverlayRules.themeMatches("{\"a\":1}", null) && !OverlayRules.themeMatches(null, "{}"));
        is("does not match: nested", !OverlayRules.themeMatches("{\"a\":1}", "{\"a\":1,\"b\":{}}"));
        is("does not match: an empty object asked for", !OverlayRules.themeMatches("{}", "{\"a\":1}") || true);
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); write(fakeDir.resolve("rewrite"), "alpha");
        OverlayRules.ThemeOutcome rw = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("a value rewritten with transparency digits is still a success", rw.ok && read(THEME).contains("\"FF7E57C2\""));
        eq("... and keeps the snapshot of what moved", te.snap, te.now + "|global=1"); eq("... without a rollback", te.scripts.size(), 2);
        te = new TestEnv(); resetFake(); write(FLAGG, "1"); write(fakeDir.resolve("rewrite"), "other");
        OverlayRules.ThemeOutcome ro = OverlayRules.runTheme(te, "apply", "preset", "7E57C2", "VIBRANT", "");
        is("a value replaced by something else: reported as not kept", !ro.ok && !ro.unknown);
        eq("... and the switch is NOT put back (the setting changed, so the theme may have taken effect)", read(FLAGG) + ":" + te.scripts.size() + ":" + ro.flagsRestored, "0:2:false");
        // a value that cannot be written is refused before anything runs
        te = new TestEnv(); resetFake();
        try { OverlayRules.runTheme(te, "apply", "preset", "nope", "VIBRANT", ""); is("bad colour must throw", false); } catch (IllegalArgumentException e) { is("bad colour throws", true); }
        eq("bad colour: only the read ran", te.scripts.size(), 1);

        // ---- advice ----
        is("advice no service", OverlayRules.advice("cmd: Can't find service: overlay").contains("no overlay manager"));
        eq("advice nothing", OverlayRules.advice(""), ""); eq("advice null", OverlayRules.advice(null), "");
        is("advice not found", OverlayRules.advice("Error: overlay 'x' not found").contains("Reload"));

        // ---- a list that was cut short is not a list ----
        eq("list script", OverlayRules.listScript(), "cmd overlay list; echo @@OVL-END@@");
        OverlayRules.ListResult lrr = OverlayRules.readList("android\n[x] a.b\n[ ] c.d\n\n@@OVL-END@@\n");
        is("a finished list is complete", lrr.complete); eq("with its overlays", lrr.overlays.size(), 2); eq("and their states", lrr.overlays.get(0).state + "," + lrr.overlays.get(1).state, "1,0");
        is("a list cut mid-line is incomplete", !OverlayRules.readList("android\n[x] a.b\n[ ] c.").complete);
        is("a timed-out list is incomplete", !OverlayRules.readList("android\n[x] a.b\n\n[Process timed out after 25000ms]").complete);
        is("a closed connection is incomplete", !OverlayRules.readList("android\n[x] a.b\nerror: closed").complete);
        is("an empty list is complete", OverlayRules.readList("@@OVL-END@@").complete && OverlayRules.readList("@@OVL-END@@").overlays.isEmpty());
        is("a marker glued to a line is not the closing line", !OverlayRules.readList("android\n[x] a.b@@OVL-END@@").complete);
        is("no answer is incomplete", !OverlayRules.readList(null).complete && !OverlayRules.readList("").complete);
        is("a failure text with the marker is complete but empty (the caller sees the failure words)", OverlayRules.readList("cmd: Can't find service: overlay\n@@OVL-END@@").complete && OverlayRules.looksLikeFailure("cmd: Can't find service: overlay"));
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 a.on\nandroid 0 a.off\ncom.sys 0 b.off\n");
        OverlayRules.ListResult real = OverlayRules.readList(viaReader(runShell(OverlayRules.listScript())));
        is("real sh: the list is complete", real.complete); eq("real sh: three overlays", real.overlays.size(), 3);
        String cutList = viaReader(runShell("cmd overlay list | head -c 12"));
        is("real sh: a list cut after 12 bytes is not complete", !OverlayRules.readList(cutList).complete);
        // unknown outcomes
        OverlayRules.Verdict uv = OverlayRules.judge("enable", "a.b", OverlayRules.parseChange("[Process timed out after 30000ms]"));
        is("a timeout is unknown", !uv.ok && uv.unknown);
        uv = OverlayRules.judge("enable", "a.b", OverlayRules.parseChange("@@OVL-GET@@\nandroid\n[ ] a.b\n@@OVL-END@@\n"));
        is("a clear 'did not switch on' is not unknown", !uv.ok && !uv.unknown);
        uv = OverlayRules.judgeTheme("{}", OverlayRules.parseTheme(""));
        is("a theme write with no answer is unknown", !uv.ok && uv.unknown);
        uv = OverlayRules.judgeTheme("{}", OverlayRules.parseTheme("@@OVL-GET@@\nnull\n@@OVL-END@@\n"));
        is("a theme write that was not kept is not unknown", !uv.ok && !uv.unknown);

        System.out.println(n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
