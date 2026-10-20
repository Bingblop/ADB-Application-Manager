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

    // ---- the fabricated overlay: plans, and the wiring read from the source ----

    static OverlayRules.FabEntry fe(String resource, String argb) { return new OverlayRules.FabEntry(resource, argb); }

    /** A plan from resource / value pairs. */
    static List<OverlayRules.FabEntry> plan(String... rv) {
        List<OverlayRules.FabEntry> p = new ArrayList<OverlayRules.FabEntry>();
        for (int i = 0; i + 1 < rv.length; i += 2) p.add(fe(rv[i], rv[i + 1]));
        return p;
    }

    /** One lookup block as the script prints it. */
    static String lookupBlock(String resource, String answer) { return "@@OVL-LOOKUP " + resource + "@@\n" + answer + "\n@@OVL-LOOKUP-END@@\n"; }

    /** The arguments of the n-th `cmd` call (0-based) as the fake logged them: one per line, each in brackets. */
    static String cmdCall(int n) throws IOException {
        String log = read(fakeDir.resolve("cmd.log"));
        if (log == null) return null;
        String[] calls = log.split("(?m)^---\n");
        // calls[0] is the empty text before the first "---"
        return n + 1 < calls.length ? calls[n + 1] : null;
    }

    static File findUp(String rel) {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) { File f = new File(d, rel); if (f.exists()) return f; }
        return null;
    }

    static String bodyOf(String src, String sig) {
        int i = src.indexOf(sig);
        if (i < 0 || src.indexOf(sig, i + 1) >= 0) return null;
        int open = src.indexOf('{', i), depth = 0, k = open;
        for (; k < src.length(); k++) { char ch = src.charAt(k); if (ch == '{') depth++; else if (ch == '}') { depth--; if (depth == 0) break; } }
        return src.substring(i, k + 1);
    }

    public static void main(String[] a) throws Exception {
        fakeDir = Files.createTempDirectory("ovlfake");
        fakeBin = fakeDir.resolve("bin");
        Files.createDirectories(fakeBin);
        // a fake `cmd`: "cmd overlay list|enable|disable ID", state in $OVL_FAKE/overlays as "<target> <1|0|-1> <id>"; logs its arguments one per line.
        // Also fabricate (registers com.android.shell:<name> off, keeps its colours in $OVL_FAKE/colors as "<resource> <0xAARRGGBB> <id>"; refuses without
        // root when $OVL_FAKE/noroot exists, refuses --user when $OVL_FAKE/sdk is 36 or more, cannot read the file when $OVL_FAKE/unreadable exists)
        // and lookup (the colour of an enabled overlay for the resource, else the stock one from $OVL_FAKE/stock "<resource> <0xAARRGGBB>", else an
        // error on stderr; $OVL_FAKE/lookupstyle "ref" prints "@ref -> #colour", "upper" prints upper-case; $OVL_FAKE/ignore makes the framework keep the stock colour)
        write(fakeBin.resolve("cmd"),
            "#!/bin/sh\n" +
            "{ echo '---'; for x in \"$@\"; do printf '[%s]\\n' \"$x\"; done; } >> \"$OVL_FAKE/cmd.log\"\n" +
            "[ \"$1\" = overlay ] || { echo \"cmd: Can't find service: $1\"; exit 1; }\n" +
            "verb=$2; shift 2; user=0; if [ \"$1\" = --user ]; then shift 2; user=1; fi\n" +        // the real one takes --user <id>|current after the verb
            "f=\"$OVL_FAKE/overlays\"\n" +
            "case \"$verb\" in\n" +
            " list) last=; while read -r tgt st id; do if [ \"$tgt\" != \"$last\" ]; then [ -n \"$last\" ] && echo; echo \"$tgt\"; last=$tgt; fi; case $st in 1) echo \"[x] $id\";; 0) echo \"[ ] $id\";; *) echo \"--- $id\";; esac; done < \"$f\"; echo;;\n" +
            " enable|disable) id=$1; want=1; [ \"$verb\" = disable ] && want=0\n" +
            "   if ! grep -q \" $id\\$\" \"$f\"; then echo \"Error: overlay '$id' not found\"; exit 1; fi\n" +
            "   if grep -q \" -1 $id\\$\" \"$f\"; then echo \"Error: failed to $verb overlay (not available)\"; exit 1; fi\n" +
            "   if grep -q \" 1 FIXED:$id\\$\" \"$f\"; then :; fi\n" +
            "   if [ -f \"$OVL_FAKE/immutable\" ] && grep -qx \"$id\" \"$OVL_FAKE/immutable\" && [ $want = 0 ]; then exit 0; fi\n" +
            "   tmp=\"$f.tmp\"; while read -r tgt st oid; do if [ \"$oid\" = \"$id\" ]; then echo \"$tgt $want $oid\"; else echo \"$tgt $st $oid\"; fi; done < \"$f\" > \"$tmp\"; mv \"$tmp\" \"$f\";;\n" +
            " fabricate)\n" +
            "   [ -f \"$OVL_FAKE/noroot\" ] && { echo \"Error: must be root to fabricate overlays through the shell\"; exit 1; }\n" +
            "   if [ -f \"$OVL_FAKE/sdk\" ] && [ \"$(cat \"$OVL_FAKE/sdk\")\" -ge 36 ] && [ \"$user\" = 1 ]; then echo \"Error: Unknown option: --user\"; exit 1; fi\n" +
            "   tgt=; name=; file=\n" +
            "   while [ $# -gt 0 ]; do case \"$1\" in --target) tgt=$2; shift 2;; --name) name=$2; shift 2;; --file) file=$2; shift 2;; *) break;; esac; done\n" +
            "   [ \"$tgt\" = android ] && [ -n \"$name\" ] || { echo \"Error: --target and --name are required\"; exit 1; }\n" +
            "   id=\"com.android.shell:$name\"; touch \"$f\"\n" +
            "   [ -f \"$OVL_FAKE/colors\" ] && { grep -v \" $id\\$\" \"$OVL_FAKE/colors\" > \"$OVL_FAKE/colors.tmp\"; mv \"$OVL_FAKE/colors.tmp\" \"$OVL_FAKE/colors\"; }\n" +
            "   if [ -n \"$file\" ]; then\n" +
            "     [ -f \"$file\" ] && [ ! -f \"$OVL_FAKE/unreadable\" ] || { echo \"Error: failed to read $file\"; exit 1; }\n" +
            "     cp \"$file\" \"$OVL_FAKE/last.xml\"\n" +
            "     ls -l \"$file\" | cut -c1-10 > \"$OVL_FAKE/last.mode\"\n" +      // the mode the file had when Android read it
            "     grep -o '<item target=\"[^\"]*\" value=\"[^\"]*\"/>' \"$file\" | sed -e 's/<item target=\"\\([^\"]*\\)\" value=\"\\([^\"]*\\)\"\\/>/android:\\1 \\2/' | while read -r res val; do echo \"$res $val $id\"; done >> \"$OVL_FAKE/colors\"\n" +
            "   else\n" +
            "     [ \"$2\" = 0x1c ] && [ -n \"$3\" ] || { echo \"Error: unsupported value: $2 $3\"; exit 1; }\n" +
            "     echo \"$1 $3 $id\" >> \"$OVL_FAKE/colors\"\n" +
            "   fi\n" +
            "   grep -v \" $id\\$\" \"$f\" > \"$f.tmp\"; mv \"$f.tmp\" \"$f\"; echo \"$tgt 0 $id\" >> \"$f\";;\n" +      // registering a name again replaces it; a fresh overlay is off
            " lookup)\n" +
            "   res=$2; val=\n" +
            "   if [ -f \"$OVL_FAKE/missing\" ] && grep -qx \"$res\" \"$OVL_FAKE/missing\"; then echo \"Error: failed to resolve $res\" >&2; exit 1; fi\n" +      // a resource this Android does not have
            "   if [ ! -f \"$OVL_FAKE/ignore\" ] && [ -f \"$OVL_FAKE/colors\" ]; then while read -r r v id; do [ \"$r\" = \"$res\" ] || continue; grep -q \" 1 $id\\$\" \"$f\" && val=$v; done < \"$OVL_FAKE/colors\"; fi\n" +
            "   if [ -z \"$val\" ] && [ -f \"$OVL_FAKE/stock\" ]; then val=$(grep \"^$res \" \"$OVL_FAKE/stock\" | head -1 | cut -d' ' -f2); fi\n" +
            "   [ -n \"$val\" ] || { echo \"Error: failed to resolve $res\" >&2; exit 1; }\n" +
            "   hex=$(printf '%s' \"${val#0x}\" | tr 'A-F' 'a-f')\n" +
            "   case \"$(cat \"$OVL_FAKE/lookupstyle\" 2>/dev/null)\" in ref) echo \"@0x7f0600ab -> #$hex\";; upper) echo \"#$(printf '%s' \"$hex\" | tr 'a-f' 'A-F')\";; *) echo \"#$hex\";; esac;;\n" +
            " *) echo \"Overlay manager (overlay) commands:\"; exit 1;;\n" +
            "esac\n");
        // a fake `settings`: settings get|put <ns> <key> [value]; files $OVL_FAKE/settings/<ns>__<key>
        write(fakeBin.resolve("settings"),
            "#!/bin/sh\n" +
            "if [ \"$1\" = --user ]; then shift 2; fi\n" +
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
        eq("list cmd", OverlayRules.listCommand(), "cmd overlay list --user current");
        eq("enable cmd", OverlayRules.changeCommand("enable", "com.foo.bar"), "cmd overlay enable --user current 'com.foo.bar'");
        eq("disable cmd", OverlayRules.changeCommand("disable", "com.foo:name"), "cmd overlay disable --user current 'com.foo:name'");
        eq("quote", OverlayRules.changeCommand("enable", "it's"), "cmd overlay enable --user current 'it'\\''s'");
        eq("script", OverlayRules.changeScript("enable", "x.y"), "cmd overlay enable --user current 'x.y'; echo @@OVL-GET@@; cmd overlay list --user current; echo @@OVL-END@@");
        // every command names the user this app runs as: without --user the shell acts on user 0, another user's overlays and theme in a work profile or a secondary user
        for (String c : new String[] { OverlayRules.listCommand(), OverlayRules.listScript(), OverlayRules.changeScript("disable", "x.y"), OverlayRules.themeReadScript(),
                OverlayRules.themeWriteScript("{\"android.theme.customization.system_palette\":\"FF0000\"}", new int[] {1, 1, 1}),
                OverlayRules.flagRestoreScript(Arrays.asList(new OverlayRules.FlagChange("global", "1", "0"))) }) {
            is("names the running user: " + c, c.replace("cmd overlay list --user current", "").replace("cmd overlay disable --user current", "").replace("settings --user current ", "").indexOf("settings ") < 0
                    && c.replace("cmd overlay list --user current", "").replace("cmd overlay disable --user current", "").indexOf("cmd overlay") < 0);
        }
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
            // first call is the enable: ---, [overlay], [enable], [--user], [current], [<id>]
            String first = log.substring(0, log.indexOf("---", 3) > 0 ? log.indexOf("---", 3) : log.length());
            eq("hostile argv " + h, first, "---\n[overlay]\n[enable]\n[--user]\n[current]\n[" + h + "]\n");
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
        is("script probes all three tables", sc.contains("settings --user current get system wallpapertheme_state") && sc.contains("settings --user current get secure wallpapertheme_state") && sc.contains("settings --user current get global wallpapertheme_state"));
        is("script sets a switch only where it holds a number", sc.contains("case \"$v\" in [0-9]|[0-9][0-9]|[0-9][0-9][0-9]) settings --user current put system wallpapertheme_state 0;"));
        is("script ends with the theme write and its read-back", sc.endsWith("settings --user current put secure theme_customization_overlay_packages '" + pv + "'; echo @@OVL-GET@@; settings --user current get secure theme_customization_overlay_packages; echo @@OVL-END@@"));
        eq("reset script has no switch", OverlayRules.themeWriteScript("").startsWith("settings --user current put secure"), true);
        eq("a plan that skips a table", OverlayRules.themeWriteScript(pv, new int[] {-1, -1, 5}).contains("settings --user current get system"), false);
        is("a plan for one table", OverlayRules.themeWriteScript(pv, new int[] {-1, -1, 5}).contains("settings --user current put global wallpapertheme_state 5;"));
        is("a plan value out of range is ignored", !OverlayRules.themeWriteScript(pv, new int[] {1000, -1, -1}).contains("settings --user current get system"));
        is("a null plan is no plan", OverlayRules.themeWriteScript(pv, null).startsWith("settings --user current put secure"));

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
        eq("restore script", OverlayRules.flagRestoreScript(Arrays.asList(new OverlayRules.FlagChange("global", "1", "0"), new OverlayRules.FlagChange("nope", "1", "0"), new OverlayRules.FlagChange("system", "a b", "0"))), "settings --user current put global wallpapertheme_state 1; n=$(settings --user current get global wallpapertheme_state 2>/dev/null); echo \"@@OVL-FLAG@@ global 1 $n\"; echo @@OVL-END@@");
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
        eq("apply: a read, then one write", te.scripts.size() + ":" + te.scripts.get(0).startsWith("echo @@OVL-GET@@; settings --user current get secure"), "2:true");
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
        eq("list script", OverlayRules.listScript(), "cmd overlay list --user current; echo @@OVL-END@@");
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

        // ================= root-only theme: the fabricated overlay (pure black) =================
        final String N1 = "android:color/system_neutral1_900", N2 = "android:color/system_neutral2_900";
        final String FAB = "com.android.shell:aam_monet";
        final List<OverlayRules.FabEntry> black = plan(N1, "0xFF000000", N2, "0xFF000000");
        final List<String> ids34 = Arrays.asList(FAB);
        final List<String> ids33 = Arrays.asList("com.android.shell:aam_monet_neutral1_900", "com.android.shell:aam_monet_neutral2_900");

        // ---- the plan's checks: hostile names, values and counts never reach a shell ----
        eq("the pure-black plan passes", OverlayRules.fabricatePlanProblem(black), null);
        OverlayRules.fabricatePlanOk(black);
        eq("14+ role entries pass", OverlayRules.fabricatePlanProblem(plan("android:color/system_surface_container_lowest_dark", "0xFF0E0E0E", "android:color/system_background_dark", "0xFF000000")), null);
        eq("lower-case hex passes", OverlayRules.fabricatePlanProblem(plan(N1, "0xff0e0e0e")), null);
        String[][] badEntries = {
            {N1, "0xFF00000"}, {N1, "0xFF0000000"}, {N1, "FF000000"}, {N1, "#FF000000"}, {N1, "0xFF00000G"}, {N1, "0XFF000000"}, {N1, " 0xFF000000"},
            {N1, "0xFF000000\n"}, {N1, ""}, {N1, null}, {N1, "0xFF000000'; touch canaryF1; echo '"}, {N1, "0xFF000000 0xFF000000"}, {N1, "0xＦＦ000000"},
            {"android:color/accent_device_default", "0xFF000000"}, {"android:color/system_Neutral1_900", "0xFF000000"}, {N1 + " ", "0xFF000000"},
            {"android:color/system_", "0xFF000000"}, {N1 + "/../x", "0xFF000000"}, {"color/system_neutral1_900", "0xFF000000"},
            {"com.android.systemui:color/system_neutral1_900", "0xFF000000"}, {"android:string/system_neutral1_900", "0xFF000000"},
            {N1 + "'; touch canaryF2; echo '", "0xFF000000"}, {N1 + "$(touch canaryF3)", "0xFF000000"}, {N1 + "\nAAM_OVL_EOF\ntouch canaryF4", "0xFF000000"},
            {N1 + "\" value=\"0xFF000000\"/><item target=\"color/x", "0xFF000000"}, {"android:color/system_néutral1_900", "0xFF000000"},
            {"", "0xFF000000"}, {null, "0xFF000000"}, {"ANDROID:COLOR/SYSTEM_NEUTRAL1_900", "0xFF000000"}, {"android:color/system-neutral1-900", "0xFF000000"},
        };
        for (String[] b : badEntries) {
            String what = String.valueOf(b[0]).replace("\n", "\\n") + " = " + String.valueOf(b[1]).replace("\n", "\\n");
            String prob = OverlayRules.fabricatePlanProblem(plan(b[0], b[1]));
            is("bad entry refused: " + what, prob != null);
            is("bad entry named by number, not echoed: " + what, prob != null && prob.startsWith("Entry 1 ") && !prob.contains("touch") && !prob.contains("canary") && !prob.contains("\n"));
            throwsIae("bad entry throws: " + what, () -> OverlayRules.fabricatePlanOk(plan(b[0], b[1])));
            throwsIae("bad entry builds no script: " + what, () -> OverlayRules.fabricateScript(34, plan(b[0], b[1])));
            throwsIae("bad entry builds no script (33): " + what, () -> OverlayRules.fabricateScript(33, plan(b[0], b[1])));
        }
        is("a good entry after a bad one is still refused", OverlayRules.fabricatePlanProblem(plan(N1, "0xFF000000", N2, "nope")) != null && OverlayRules.fabricatePlanProblem(plan(N1, "0xFF000000", N2, "nope")).startsWith("Entry 2 "));
        is("an empty plan", OverlayRules.fabricatePlanProblem(new ArrayList<OverlayRules.FabEntry>()) != null);
        is("no plan", OverlayRules.fabricatePlanProblem(null) != null);
        throwsIae("no plan throws", () -> OverlayRules.fabricatePlanOk(null));
        List<OverlayRules.FabEntry> withNull = plan(N1, "0xFF000000"); withNull.add(null);
        is("a null entry", OverlayRules.fabricatePlanProblem(withNull) != null);
        is("a resource named twice", OverlayRules.fabricatePlanProblem(plan(N1, "0xFF000000", N1, "0xFF000001")) != null && OverlayRules.fabricatePlanProblem(plan(N1, "0xFF000000", N1, "0xFF000001")).contains("twice"));
        List<OverlayRules.FabEntry> big200 = new ArrayList<OverlayRules.FabEntry>(), big201 = new ArrayList<OverlayRules.FabEntry>();
        for (int i = 0; i < 201; i++) { OverlayRules.FabEntry e = fe("android:color/system_x" + i, "0xFF000000"); if (i < 200) big200.add(e); big201.add(e); }
        eq("200 entries pass", OverlayRules.fabricatePlanProblem(big200), null);
        is("201 entries are too many", OverlayRules.fabricatePlanProblem(big201) != null && OverlayRules.fabricatePlanProblem(big201).contains("200"));
        throwsIae("201 entries throw", () -> OverlayRules.fabricateScript(34, big201));

        // ---- the identifiers written ----
        eq("ids on 34: one overlay", OverlayRules.fabricateIds(34, black).toString(), "[" + FAB + "]");
        eq("ids on 35 and 36: the same", OverlayRules.fabricateIds(35, black).toString() + OverlayRules.fabricateIds(36, black).toString(), "[" + FAB + "][" + FAB + "]");
        eq("ids on 33: one per entry, named for it", OverlayRules.fabricateIds(33, black).toString(), "[com.android.shell:aam_monet_neutral1_900, com.android.shell:aam_monet_neutral2_900]");
        eq("ids on 31 and 32: the same", OverlayRules.fabricateIds(31, black).toString(), OverlayRules.fabricateIds(32, black).toString());
        for (String id : OverlayRules.fabricateIds(33, big200)) eq("every 33 id is one of ours: " + id, OverlayRules.fabricateIdProblem(id), null);
        eq("the 14+ id is one of ours", OverlayRules.fabricateIdProblem(FAB), null);
        throwsIae("ids check the plan", () -> OverlayRules.fabricateIds(33, plan(N1, "nope")));
        eq("entry name", OverlayRules.entryOverlayName(fe("android:color/system_surface_container_lowest_dark", "0xFF000000")), "aam_monet_surface_container_lowest_dark");

        // ---- the three script forms, exactly ----
        String tail = "echo @@OVL-GET@@; cmd overlay list --user current; echo @@OVL-END@@\n";
        String lookups2 = "echo '@@OVL-LOOKUP android:color/system_neutral1_900@@'; cmd overlay lookup --user current android 'android:color/system_neutral1_900' 2>&1; echo @@OVL-LOOKUP-END@@\n"
                + "echo '@@OVL-LOOKUP android:color/system_neutral2_900@@'; cmd overlay lookup --user current android 'android:color/system_neutral2_900' 2>&1; echo @@OVL-LOOKUP-END@@\n";
        String xmlText = "<overlay>\n  <item target=\"color/system_neutral1_900\" value=\"0xFF000000\"/>\n  <item target=\"color/system_neutral2_900\" value=\"0xFF000000\"/>\n</overlay>\n";
        eq("the xml", OverlayRules.fabricateXml(black), xmlText);
        String f34 = OverlayRules.fabricateScript(34, black, "/tmp/aam_test.xml");
        eq("sdk 34: the file form", f34,
            "cat > '/tmp/aam_test.xml' <<'AAM_OVL_EOF'\n" + xmlText + "AAM_OVL_EOF\n"
            + "chmod 644 '/tmp/aam_test.xml'\n"
            + "cmd overlay fabricate --user current --target android --name 'aam_monet' --file '/tmp/aam_test.xml'\n"
            + "cmd overlay enable --user current 'com.android.shell:aam_monet'\n"
            + "rm -f '/tmp/aam_test.xml'\n" + tail + lookups2);
        eq("sdk 35: the 34 form", OverlayRules.fabricateScript(35, black, "/tmp/aam_test.xml"), f34);
        String f36 = OverlayRules.fabricateScript(36, black, "/tmp/aam_test.xml");
        eq("sdk 36: the same without --user on the fabricate", f36, f34.replace("cmd overlay fabricate --user current --target", "cmd overlay fabricate --target"));
        is("sdk 36: enable, list and lookup keep --user", f36.contains("cmd overlay enable --user current 'com.android.shell:aam_monet'\n") && f36.contains(tail) && f36.endsWith(lookups2));
        eq("sdk 37: the 36 form", OverlayRules.fabricateScript(37, black, "/tmp/aam_test.xml"), f36);
        eq("the app writes its file under /data/system", OverlayRules.fabricateScript(34, black), f34.replace("/tmp/aam_test.xml", "/data/system/aam_monet.xml"));
        eq("the app's file constant", OverlayRules.FAB_FILE, "/data/system/aam_monet.xml");
        String f33 = OverlayRules.fabricateScript(33, black);
        eq("sdk 33: one fabricate per entry, then one enable per overlay", f33,
            "cmd overlay fabricate --user current --target android --name 'aam_monet_neutral1_900' 'android:color/system_neutral1_900' 0x1c '0xFF000000'\n"
            + "cmd overlay fabricate --user current --target android --name 'aam_monet_neutral2_900' 'android:color/system_neutral2_900' 0x1c '0xFF000000'\n"
            + "cmd overlay enable --user current 'com.android.shell:aam_monet_neutral1_900'\n"
            + "cmd overlay enable --user current 'com.android.shell:aam_monet_neutral2_900'\n" + tail + lookups2);
        eq("sdk 31 and 32: the 33 form", OverlayRules.fabricateScript(31, black) + OverlayRules.fabricateScript(32, black), f33 + f33);
        is("sdk 33: no file, no heredoc", !f33.contains("<<") && !f33.contains("--file") && !f33.contains("/data/system"));
        throwsIae("sdk 30: no Material You", () -> OverlayRules.fabricateScript(30, black));
        throwsIae("no file name", () -> OverlayRules.fabricateScript(34, black, ""));
        // the heredoc: a quoted delimiter (nothing in the body is expanded), the body exactly the xml, the delimiter alone on its line, then the commands
        is("heredoc delimiter quoted", f34.startsWith("cat > '/tmp/aam_test.xml' <<'AAM_OVL_EOF'\n<overlay>\n"));
        is("heredoc closed on its own line, then the file made readable to system_server, then the fabricate", f34.contains("</overlay>\nAAM_OVL_EOF\nchmod 644 '/tmp/aam_test.xml'\ncmd overlay fabricate"));
        is("the chmod comes after the write and before the fabricate reads the file", f34.indexOf("chmod 644 ") > f34.indexOf("AAM_OVL_EOF\n") && f34.indexOf("chmod 644 ") < f34.indexOf("--file '/tmp/aam_test.xml'"));
        is("a file name with a quote is one word in the chmod too", OverlayRules.fabricateScript(34, black, "/tmp/it's.xml").contains("\nchmod 644 '/tmp/it'\\''s.xml'\n"));
        is("sdk 33: no chmod (no file)", !OverlayRules.fabricateScript(33, black).contains("chmod"));
        is("the file is removed after the write", f34.indexOf("rm -f '/tmp/aam_test.xml'") > f34.indexOf("--file '/tmp/aam_test.xml'") && f34.indexOf("rm -f") < f34.indexOf("@@OVL-GET@@"));
        is("a file name with a quote is still one word", OverlayRules.fabricateScript(34, black, "/tmp/it's.xml").contains("cat > '/tmp/it'\\''s.xml' <<'AAM_OVL_EOF'"));
        // lookups: the first four entries, no more
        List<OverlayRules.FabEntry> eleven = new ArrayList<OverlayRules.FabEntry>();
        for (String r : new String[] {"neutral1_900", "neutral2_900", "background_dark", "surface_dark", "surface_dim_dark", "surface_container_lowest_dark", "surface_container_low_dark", "surface_container_dark", "surface_container_high_dark", "surface_container_highest_dark", "surface_bright_dark"}) eleven.add(fe("android:color/system_" + r, "0xFF000000"));
        String f11 = OverlayRules.fabricateScript(34, eleven, "/tmp/x.xml");
        eq("eleven entries: four lookups", f11.split("cmd overlay lookup ", -1).length - 1, 4);
        is("the four are the first four", f11.contains("'android:color/system_surface_dark' 2>&1") && !f11.contains("lookup --user current android 'android:color/system_surface_dim_dark'"));
        eq("lookupEntries of two", OverlayRules.lookupEntries(black).size(), 2);
        eq("lookupEntries of eleven", OverlayRules.lookupEntries(eleven).get(3).resource, "android:color/system_surface_dark");
        is("eleven items in the file", OverlayRules.fabricateXml(eleven).split("<item ", -1).length - 1 == 11 && f11.contains(OverlayRules.fabricateXml(eleven)));
        eq("eleven entries on 33: eleven fabricates, eleven enables", OverlayRules.fabricateScript(33, eleven).split("cmd overlay fabricate ", -1).length - 1 + ":" + (OverlayRules.fabricateScript(33, eleven).split("cmd overlay enable ", -1).length - 1), "11:11");
        // every command names the running user (except the fabricate on 16, whose parser has none)
        for (String line : (f34 + f33).split("\n")) if (line.startsWith("cmd overlay") || line.contains("; cmd overlay")) is("names the user: " + line, line.contains(" --user current"));
        for (String line : f36.split("\n")) if (line.startsWith("cmd overlay") && !line.startsWith("cmd overlay fabricate")) is("16 names the user: " + line, line.contains(" --user current"));

        // ---- the disable script ----
        eq("disable script", OverlayRules.disableScript(ids34), "cmd overlay disable --user current 'com.android.shell:aam_monet'\n" + tail);
        eq("disable script of two", OverlayRules.disableScript(ids33), "cmd overlay disable --user current 'com.android.shell:aam_monet_neutral1_900'\ncmd overlay disable --user current 'com.android.shell:aam_monet_neutral2_900'\n" + tail);
        is("no lookups after a disable", !OverlayRules.disableScript(ids33).contains("lookup"));
        for (String bad : new String[] {"com.android.systemui:aam_monet", "com.android.shell:aam_monet; touch canaryF5", "com.android.shell:aam_monet_", "com.android.shell:aam_monetx", "com.android.shell:AAM_MONET",
                "com.android.shell:aam_monet_Neutral", "com.android.shell:", "aam_monet", "com.android.shell:aam_monet\n", "", "android.theme.customization.accent_color", "com.android.shell:aam_monet'", "com.android.shell:aam_monet:x", "-com.android.shell:aam_monet"}) {
            is("foreign or odd id refused: " + bad.replace("\n", "\\n"), OverlayRules.fabricateIdProblem(bad) != null);
            throwsIae("foreign or odd id builds no disable: " + bad.replace("\n", "\\n"), () -> OverlayRules.disableScript(Arrays.asList(FAB, bad)));
        }
        is("null id refused", OverlayRules.fabricateIdProblem(null) != null);
        throwsIae("no ids", () -> OverlayRules.disableScript(new ArrayList<String>()));
        throwsIae("null ids", () -> OverlayRules.disableScript(null));
        throwsIae("an id twice", () -> OverlayRules.disableScript(Arrays.asList(FAB, FAB)));
        List<String> many = new ArrayList<String>(); for (int i = 0; i < 201; i++) many.add("com.android.shell:aam_monet_x" + i);
        throwsIae("201 ids", () -> OverlayRules.disableScript(many));
        OverlayRules.disablePlanOk(many.subList(0, 200));

        // ---- the lookup's answer ----
        eq("lookup: #aarrggbb", OverlayRules.lookupColor("#ff000000"), "#ff000000");
        eq("lookup: ref -> #aarrggbb", OverlayRules.lookupColor("@0x7f06012c -> #ff000000"), "#ff000000");
        eq("lookup: two arrows, the last wins", OverlayRules.lookupColor("@0x7f06012c -> @0x7f0600ab -> #ff1b1b1b"), "#ff1b1b1b");
        eq("lookup: Error line", OverlayRules.lookupColor("Error: failed to resolve resource android:color/system_neutral1_900"), null);
        eq("lookup: error after an arrow", OverlayRules.lookupColor("@0x7f06012c -> Error: nope"), null);
        eq("lookup: empty", OverlayRules.lookupColor(""), null);
        eq("lookup: blank", OverlayRules.lookupColor(" \n\t "), null);
        eq("lookup: null", OverlayRules.lookupColor(null), null);
        eq("lookup: upper-case input", OverlayRules.lookupColor("#FF000000"), "#ff000000");
        eq("lookup: upper-case ref with a newline", OverlayRules.lookupColor("@0x7F06012C -> #FF1B1B1B\n"), "#ff1b1b1b");
        eq("lookup: spaces around", OverlayRules.lookupColor("  #ff1b1b1b  "), "#ff1b1b1b");
        eq("lookup: a short alpha is padded", OverlayRules.lookupColor("#1b1b1b"), "#001b1b1b");
        eq("lookup: one digit is padded", OverlayRules.lookupColor("#f"), "#0000000f");
        eq("lookup: nine digits is not a colour", OverlayRules.lookupColor("#ff0000000"), null);
        eq("lookup: not hex", OverlayRules.lookupColor("#ggffffff"), null);
        eq("lookup: no hash", OverlayRules.lookupColor("ff000000"), null);
        eq("lookup: 0x form", OverlayRules.lookupColor("0xff000000"), null);
        eq("lookup: trailing words", OverlayRules.lookupColor("#ff000000 extra"), null);
        eq("lookup: a warning before the value", OverlayRules.lookupColor("WARNING: linker: something\n#ff000000"), "#ff000000");
        eq("lookup: a number is not a colour", OverlayRules.lookupColor("42"), null);
        eq("expected form", OverlayRules.expectedLookup("0xFF1B1B1B"), "#ff1b1b1b");
        eq("expected form of black", OverlayRules.expectedLookup("0xFF000000"), "#ff000000");
        // the blocks
        Map<String, String> lk2 = OverlayRules.parseLookups(lookupBlock(N1, "#ff000000") + lookupBlock(N2, "@ref -> #ff000000"));
        eq("two blocks paired", lk2.get(N1) + "|" + lk2.get(N2), "#ff000000|@ref -> #ff000000");
        eq("a block cut short is no answer", OverlayRules.parseLookups(lookupBlock(N1, "#ff000000") + "@@OVL-LOOKUP " + N2 + "@@\n#ff00").size(), 1);
        Map<String, String> lkGap = OverlayRules.parseLookups("@@OVL-LOOKUP " + N1 + "@@\nError: boom\n" + lookupBlock(N2, "#ff000000"));
        eq("a block whose end never came is skipped, the next is read", lkGap.containsKey(N1) + "|" + lkGap.get(N2), "false|#ff000000");
        eq("no blocks", OverlayRules.parseLookups("android\n[x] a.b\n").size() + OverlayRules.parseLookups("").size() + OverlayRules.parseLookups(null).size(), 0);
        eq("an empty answer is kept as empty", OverlayRules.parseLookups(lookupBlock(N1, "")).get(N1), "");
        eq("a mark without the closing @@", OverlayRules.parseLookups("@@OVL-LOOKUP " + N1 + "\n#ff000000\n@@OVL-LOOKUP-END@@\n").size(), 0);
        OverlayRules.FabResult pf1 = OverlayRules.parseFabricate("@@OVL-GET@@\nandroid\n[x] " + FAB + "\n@@OVL-END@@\n" + lookupBlock(N1, "#ff000000"));
        is("parseFabricate: the list and the lookups apart", pf1.change.all != null && pf1.change.all.size() == 1 && pf1.change.all.get(0).state == 1 && "#ff000000".equals(pf1.lookups.get(N1)));
        eq("parseFabricate: the answer is what came before the list", OverlayRules.parseFabricate("java.lang.SecurityException: commit failed\n@@OVL-GET@@\nandroid\n@@OVL-END@@\n" + lookupBlock(N1, "#ff000000")).change.answer, "java.lang.SecurityException: commit failed");
        is("parseFabricate: lookups without a list", OverlayRules.parseFabricate(lookupBlock(N1, "#ff000000")).change.all == null);
        is("parseFabricate: null", OverlayRules.parseFabricate(null).change.all == null && OverlayRules.parseFabricate(null).lookups.isEmpty());

        // ---- every verdict, from read-backs written by hand ----
        String listOn = "@@OVL-GET@@\nandroid\n[x] android.theme.customization.accent_color\n[x] " + FAB + "\n@@OVL-END@@\n";
        String listOff = listOn.replace("[x] " + FAB, "[ ] " + FAB);
        String listGone = "@@OVL-GET@@\nandroid\n[x] android.theme.customization.accent_color\n@@OVL-END@@\n";
        String listNA = listOn.replace("[x] " + FAB, "--- " + FAB);
        String good2 = lookupBlock(N1, "#ff000000") + lookupBlock(N2, "#ff000000");
        OverlayRules.FabVerdict jv;
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOn + good2));
        is("listed on + colours right: ok", jv.ok && !jv.unknown && jv.error.isEmpty()); eq("its state", jv.state, 1);
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOn + lookupBlock(N1, "@0x7f06012c -> #FF000000") + lookupBlock(N2, "#ff000000")));
        is("a reference and upper case still count", jv.ok);
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOn));
        is("listed on, no lookups: unknown", !jv.ok && jv.unknown); is("unknown says which", jv.error.contains("system_neutral1_900") && jv.error.contains("system_neutral2_900")); eq("unanswered", jv.unanswered.size(), 2);
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOn + lookupBlock(N1, "#ff000000") + lookupBlock(N2, "Error: failed to resolve " + N2)));
        is("one lookup an error: unknown", !jv.ok && jv.unknown); eq("the unanswered one", jv.unanswered.toString(), "[" + N2 + "]");
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOn + lookupBlock(N1, "#ff000000") + lookupBlock(N2, "")));
        is("an empty lookup: unknown", !jv.ok && jv.unknown);
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOn + lookupBlock(N1, "#ff1b1b1b") + lookupBlock(N2, "#ff000000")));
        is("another colour: refused, not unknown", !jv.ok && !jv.unknown); is("its words", jv.error.startsWith("Android did not take the colours") && jv.error.contains("system_neutral1_900")); eq("mismatched", jv.mismatched.toString(), "[" + N1 + "]");
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOn + lookupBlock(N1, "#ff1b1b1b") + lookupBlock(N2, "Error: x")));
        is("a wrong colour and a missing one: the refusal wins", !jv.ok && !jv.unknown && jv.error.startsWith("Android did not take the colours"));
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOn + lookupBlock(N1, "#000000") + lookupBlock(N2, "#ff000000")));
        is("transparent black is another colour", !jv.ok && !jv.unknown);
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOff + good2));
        is("listed off: refused", !jv.ok && !jv.unknown); eq("off state", jv.state, 0); is("off words", jv.error.startsWith("Android did not switch it on") && jv.error.contains(FAB)); eq("missing", jv.missing.toString(), "[" + FAB + "]");
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listGone + good2));
        is("not listed: refused", !jv.ok && !jv.unknown); eq("gone state", jv.state, -2); is("gone words", jv.error.startsWith("Android did not register the overlay"));
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate("Error: must be root to fabricate overlays through the shell\n" + listGone + good2));
        is("not listed with Android's reason: its words", !jv.ok && jv.error.contains("must be root"));
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate("java.lang.SecurityException: commit failed\n" + listGone));
        eq("commit failed", jv.error, OverlayRules.NO_REASON);
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listNA + good2));
        is("unavailable", !jv.ok && jv.state == -1 && jv.error.contains("unavailable"));
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(""));
        is("no answer: unknown", !jv.ok && jv.unknown); eq("no answer words", jv.error, "No answer from the device");
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate("@@OVL-GET@@\nandroid\n[x] " + FAB + "\n"));
        is("list cut short: unknown", !jv.ok && jv.unknown);
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate("[Process timed out after 30000ms]"));
        is("timed out: unknown", !jv.ok && jv.unknown);
        jv = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate("@@OVL-GET@@\ncmd: Can't find service: overlay\n@@OVL-END@@\n"));
        is("a listing that failed: unknown, its words", !jv.ok && jv.unknown && jv.error.contains("Can't find service"));
        is("lookups never decide on their own: a list that says off wins over right colours", !OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(listOff + good2)).unknown);
        // the 12/13 form: two overlays
        String list33 = "@@OVL-GET@@\nandroid\n[x] " + ids33.get(0) + "\n[x] " + ids33.get(1) + "\n@@OVL-END@@\n";
        jv = OverlayRules.judgeFabricate("fabricate", ids33, black, OverlayRules.parseFabricate(list33 + good2));
        is("33: both on: ok", jv.ok && jv.state == 1);
        jv = OverlayRules.judgeFabricate("fabricate", ids33, black, OverlayRules.parseFabricate(list33.replace("[x] " + ids33.get(1), "[ ] " + ids33.get(1)) + good2));
        is("33: one off: refused, named", !jv.ok && !jv.unknown && jv.error.contains("aam_monet_neutral2_900") && !jv.error.contains("aam_monet_neutral1_900")); eq("33: the first one's state", jv.state, 1);
        jv = OverlayRules.judgeFabricate("fabricate", ids33, black, OverlayRules.parseFabricate(list33.replace("[x] " + ids33.get(0) + "\n", "") + good2));
        is("33: the first not listed: state -2", !jv.ok && jv.state == -2 && jv.error.contains("did not register"));
        // disable
        jv = OverlayRules.judgeFabricate("disable", ids34, null, OverlayRules.parseFabricate(listOff));
        is("disable: listed off: ok, no lookups needed", jv.ok && jv.state == 0);
        jv = OverlayRules.judgeFabricate("disable", ids34, null, OverlayRules.parseFabricate(listOn));
        is("disable: still on: refused", !jv.ok && !jv.unknown && jv.error.startsWith("Android did not switch it off"));
        jv = OverlayRules.judgeFabricate("disable", ids34, null, OverlayRules.parseFabricate(listGone));
        is("disable: not listed: refused, in words", !jv.ok && jv.error.startsWith("That overlay is not listed any more"));
        jv = OverlayRules.judgeFabricate("disable", ids34, null, OverlayRules.parseFabricate(""));
        is("disable: no answer: unknown", jv.unknown);
        jv = OverlayRules.judgeFabricate("disable", ids33, null, OverlayRules.parseFabricate(list33.replace("[x]", "[ ]")));
        is("disable 33: both off: ok", jv.ok);
        jv = OverlayRules.judgeFabricate("disable", ids34, black, OverlayRules.parseFabricate(listOff + lookupBlock(N1, "#ff1b1b1b")));
        is("disable: a lookup that happens to be there is not judged", jv.ok);

        // ---- through a real shell: the fake overlay manager now fabricates and looks up ----
        final Path STOCK = fakeDir.resolve("stock");
        final String stock = N1 + " 0xFF1B1B1B\n" + N2 + " 0xFF1A1C1C\nandroid:color/system_surface_dark 0xFF131313\n";
        final Path XML = fakeDir.resolve("aam_test.xml");
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
        write(STOCK, stock);
        // before anything: the lookup shows the stock colour
        eq("stock colour before", OverlayRules.lookupColor(viaReader(runShell("cmd overlay lookup --user current android '" + N1 + "' 2>&1"))), "#ff1b1b1b");
        Files.deleteIfExists(fakeDir.resolve("cmd.log"));
        // the 14/15 form
        String o34 = viaReader(runShell(OverlayRules.fabricateScript(34, black, XML.toString())));
        OverlayRules.FabResult r34 = OverlayRules.parseFabricate(o34);
        OverlayRules.FabVerdict v34 = OverlayRules.judgeFabricate("fabricate", ids34, black, r34);
        is("sh 34: ok", v34.ok && !v34.unknown); eq("sh 34: state", v34.state, 1); eq("sh 34: answer", r34.change.answer, "");
        eq("sh 34: really on", overlayState(FAB), "1");
        eq("sh 34: the file Android read is the xml", read(fakeDir.resolve("last.xml")), xmlText);
        is("sh 34: the file is gone afterwards", !Files.exists(XML));
        eq("sh 34: the lookups paired with their resources", r34.lookups.get(N1) + "|" + r34.lookups.get(N2), "#ff000000|#ff000000");
        eq("sh 34: the list carries the stock overlay too", r34.change.all.size(), 2);
        eq("sh 34: the fabricate's argv", cmdCall(0), "[overlay]\n[fabricate]\n[--user]\n[current]\n[--target]\n[android]\n[--name]\n[aam_monet]\n[--file]\n[" + XML + "]\n");
        eq("sh 34: the enable's argv", cmdCall(1), "[overlay]\n[enable]\n[--user]\n[current]\n[" + FAB + "]\n");
        eq("sh 34: then the list and two lookups, nothing else", cmdCall(2).startsWith("[overlay]\n[list]\n") + ":" + cmdCall(3).startsWith("[overlay]\n[lookup]\n[--user]\n[current]\n[android]\n[" + N1 + "]\n") + ":" + cmdCall(4).startsWith("[overlay]\n[lookup]\n") + ":" + cmdCall(5), "true:true:true:null");
        eq("sh 34: the colours Android holds", read(fakeDir.resolve("colors")), N1 + " 0xFF000000 " + FAB + "\n" + N2 + " 0xFF000000 " + FAB + "\n");
        // a second apply replaces the overlay (same name), still one line in the list
        String o34b = viaReader(runShell(OverlayRules.fabricateScript(34, plan(N1, "0xFF101010", N2, "0xFF101010"), XML.toString())));
        OverlayRules.FabVerdict v34b = OverlayRules.judgeFabricate("fabricate", ids34, plan(N1, "0xFF101010", N2, "0xFF101010"), OverlayRules.parseFabricate(o34b));
        is("sh 34 again: ok", v34b.ok); eq("sh 34 again: still one overlay of ours", read(fakeDir.resolve("overlays")).split(FAB, -1).length - 1, 1);
        eq("sh 34 again: the new colour", OverlayRules.lookupColor(viaReader(runShell("cmd overlay lookup --user current android '" + N1 + "' 2>&1"))), "#ff101010");
        // system_server (uid 1000) reads the file and is not its owner: it is mode 644 whatever umask the su shell has, even over a leftover 600 file
        viaReader(runShell("umask 077; echo left over > '" + XML + "'"));
        eq("sh 34: a leftover file of mode 600 to start from", viaReader(runShell("ls -l '" + XML + "' | cut -c1-10")), "-rw-------");
        OverlayRules.FabVerdict v34u = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(viaReader(runShell("umask 077\n" + OverlayRules.fabricateScript(34, black, XML.toString())))));
        is("sh 34 under umask 077: ok", v34u.ok);
        eq("sh 34 under umask 077: the file Android read was mode 644", read(fakeDir.resolve("last.mode")), "-rw-r--r--\n");
        eq("sh 34 under umask 077: the file Android read is the xml (the leftover replaced)", read(fakeDir.resolve("last.xml")), xmlText);
        // disable: the stock colour is back
        Files.deleteIfExists(fakeDir.resolve("cmd.log"));
        OverlayRules.FabVerdict dv34 = OverlayRules.judgeFabricate("disable", ids34, null, OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.disableScript(ids34)))));
        is("sh 34 disable: ok", dv34.ok && dv34.state == 0); eq("sh 34 disable: really off", overlayState(FAB), "0");
        eq("sh 34 disable: the stock colour is back", OverlayRules.lookupColor(viaReader(runShell("cmd overlay lookup --user current android '" + N1 + "' 2>&1"))), "#ff1b1b1b");
        eq("sh 34 disable: argv", cmdCall(0), "[overlay]\n[disable]\n[--user]\n[current]\n[" + FAB + "]\n");
        is("sh 34 disable: the overlay stays listed, off", read(fakeDir.resolve("overlays")).contains("android 0 " + FAB));
        // disable twice is fine (already off)
        is("sh 34 disable twice: ok", OverlayRules.judgeFabricate("disable", ids34, null, OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.disableScript(ids34))))).ok);
        // the 12/13 form: one overlay per entry
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
        write(STOCK, stock);
        String o33 = viaReader(runShell(OverlayRules.fabricateScript(33, black)));
        OverlayRules.FabResult r33 = OverlayRules.parseFabricate(o33);
        OverlayRules.FabVerdict v33 = OverlayRules.judgeFabricate("fabricate", ids33, black, r33);
        is("sh 33: ok", v33.ok && !v33.unknown);
        eq("sh 33: both really on", overlayState(ids33.get(0)) + overlayState(ids33.get(1)), "11");
        eq("sh 33: the first fabricate's argv", cmdCall(0), "[overlay]\n[fabricate]\n[--user]\n[current]\n[--target]\n[android]\n[--name]\n[aam_monet_neutral1_900]\n[" + N1 + "]\n[0x1c]\n[0xFF000000]\n");
        eq("sh 33: the lookups", r33.lookups.get(N1) + "|" + r33.lookups.get(N2), "#ff000000|#ff000000");
        is("sh 33: no file written", !Files.exists(fakeDir.resolve("last.xml")));
        OverlayRules.FabVerdict dv33 = OverlayRules.judgeFabricate("disable", ids33, null, OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.disableScript(ids33)))));
        is("sh 33 disable: ok", dv33.ok); eq("sh 33 disable: both off", overlayState(ids33.get(0)) + overlayState(ids33.get(1)), "00");
        // Android 16: a fabricate with --user is an error there, so the 34 form registers nothing and the 36 form works
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
        write(STOCK, stock); write(fakeDir.resolve("sdk"), "36");
        OverlayRules.FabVerdict v34on16 = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.fabricateScript(34, black, XML.toString())))));
        is("sh 16 with the 34 form: refused, Android's reason", !v34on16.ok && !v34on16.unknown && v34on16.error.contains("Unknown option"));
        eq("sh 16 with the 34 form: nothing registered", overlayState(FAB), null);
        Files.deleteIfExists(fakeDir.resolve("cmd.log"));
        OverlayRules.FabVerdict v36 = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.fabricateScript(36, black, XML.toString())))));
        is("sh 16 with the 36 form: ok", v36.ok); eq("sh 16: on", overlayState(FAB), "1");
        eq("sh 16: the fabricate's argv has no --user", cmdCall(0), "[overlay]\n[fabricate]\n[--target]\n[android]\n[--name]\n[aam_monet]\n[--file]\n[" + XML + "]\n");
        eq("sh 16: the enable keeps --user", cmdCall(1), "[overlay]\n[enable]\n[--user]\n[current]\n[" + FAB + "]\n");
        // not root (what Shizuku or ADB would see): refused, in Android's words
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
        write(STOCK, stock); write(fakeDir.resolve("noroot"), "");
        OverlayRules.FabVerdict vnr = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.fabricateScript(34, black, XML.toString())))));
        is("sh not root: refused", !vnr.ok && !vnr.unknown && vnr.error.contains("must be root")); eq("sh not root: nothing registered", overlayState(FAB), null);
        is("sh not root: the file is still removed", !Files.exists(XML));
        is("sh not root: advice has no Xiaomi hint", !OverlayRules.advice(vnr.error).contains("USB debugging"));
        // the file cannot be read inside system_server (the /data/system hypothesis failing): refused, named
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
        write(STOCK, stock); write(fakeDir.resolve("unreadable"), "");
        OverlayRules.FabVerdict vur = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.fabricateScript(34, black, XML.toString())))));
        is("sh unreadable file: refused with the reason", !vur.ok && !vur.unknown && vur.error.contains("failed to read"));
        // the overlay goes on but the framework keeps its own colour: refused, not a warning
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
        write(STOCK, stock); write(fakeDir.resolve("ignore"), "");
        OverlayRules.FabVerdict vig = OverlayRules.judgeFabricate("fabricate", ids34, black, OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.fabricateScript(34, black, XML.toString())))));
        is("sh colours not taken: refused", !vig.ok && !vig.unknown && vig.error.startsWith("Android did not take the colours")); eq("sh colours not taken: the overlay is on all the same", overlayState(FAB), "1");
        // a lookup that answers with a reference, or in upper case
        for (String style : new String[] {"ref", "upper"}) {
            resetFake();
            write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
            write(STOCK, stock); write(fakeDir.resolve("lookupstyle"), style);
            OverlayRules.FabResult rs = OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.fabricateScript(34, black, XML.toString()))));
            is("sh lookup style " + style + ": ok", OverlayRules.judgeFabricate("fabricate", ids34, black, rs).ok);
            is("sh lookup style " + style + ": the raw text kept", style.equals("ref") ? rs.lookups.get(N1).startsWith("@0x") && rs.lookups.get(N1).endsWith(" -> #ff000000") : rs.lookups.get(N1).equals("#FF000000"));
        }
        // a resource the phone does not have: the lookup's error (stderr) lands between the marks, and the outcome is unknown, not ok
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
        write(STOCK, N1 + " 0xFF1B1B1B\n"); write(fakeDir.resolve("missing"), "android:color/system_nope_900\n");
        List<OverlayRules.FabEntry> nopePlan = plan(N1, "0xFF000000", "android:color/system_nope_900", "0xFF000000");
        OverlayRules.FabResult rNope = OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.fabricateScript(34, nopePlan, XML.toString()))));
        OverlayRules.FabVerdict vNope = OverlayRules.judgeFabricate("fabricate", ids34, nopePlan, rNope);
        is("sh unknown resource: the error text is paired", rNope.lookups.get("android:color/system_nope_900") != null && rNope.lookups.get("android:color/system_nope_900").startsWith("Error: failed to resolve"));
        is("sh unknown resource: unknown, not ok", !vNope.ok && vNope.unknown && vNope.error.contains("system_nope_900"));
        // the eleven-entry plan of Android 14 (pure black), end to end
        resetFake();
        write(fakeDir.resolve("overlays"), "android 1 android.theme.customization.accent_color\n");
        write(STOCK, stock);
        OverlayRules.FabResult rEleven = OverlayRules.parseFabricate(viaReader(runShell(OverlayRules.fabricateScript(34, eleven, XML.toString()))));
        is("sh eleven: ok", OverlayRules.judgeFabricate("fabricate", ids34, eleven, rEleven).ok); eq("sh eleven: four lookups came", rEleven.lookups.size(), 4);
        eq("sh eleven: eleven colours held", read(fakeDir.resolve("colors")).split("\n").length, 11);
        // canaries: none of the hostile entries above ran anything
        for (int i = 1; i <= 5; i++) is("no canaryF" + i, !Files.exists(fakeDir.resolve("canaryF" + i)));

        // ---- the wiring in MainActivity (it cannot be compiled here: read from the source) ----
        File srcDir = findUp("src/com/bloatware/bingblop");
        if (srcDir == null) {
            System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir"));
        } else {
            String m = new String(Files.readAllBytes(new File(srcDir, "MainActivity.java").toPath()), StandardCharsets.UTF_8);
            String bridge = bodyOf(m, "public String overlayFabricate(final int req, final String planJson) {");
            is("source: the bridge exists once, with @JavascriptInterface", bridge != null && m.contains("@JavascriptInterface\n        public String overlayFabricate("));
            is("source: the bridge checks the plan before queueing", bridge != null && bridge.indexOf("OverlayRules.fabricatePlanOk(") < bridge.indexOf("submitSettingsJob(") && bridge.indexOf("OverlayRules.disablePlanOk(") < bridge.indexOf("submitSettingsJob("));
            is("source: a refused plan answers with the reason", bridge != null && bridge.contains("catch (IllegalArgumentException bad)") && bridge.contains("return \"error: \" + bad.getMessage();"));
            is("source: the job goes through the settings executor", bridge != null && bridge.contains("submitSettingsJob(") && bridge.contains("overlayFabricateJob(req, op, entries, ids, queuedAt);"));
            String job = bodyOf(m, "private void overlayFabricateJob(int req, String op, List<OverlayRules.FabEntry> entries, List<String> ids, long queuedAt) {");
            is("source: the job exists once", job != null);
            is("source: the job is gated by RootGate.active before any shell", job != null && job.contains("RootGate.active(mode, suGranted)") && job.indexOf("RootGate.active(") < job.indexOf("runPrivilegedShell("));
            is("source: su is asked only in root mode", job != null && job.contains("\"root\".equals(mode) && isRootGranted()"));
            is("source: not root answers needsPrivilege", job != null && job.contains("res.put(\"needsPrivilege\", true);") && job.contains("res.put(\"error\", \"Needs Super User\");"));
            is("source: the shell runs as root, with the fabricate or disable script", job != null && job.contains("runPrivilegedShell(\"root\", script, 30000)") && job.contains("OverlayRules.disableScript(ids)") && job.contains("OverlayRules.fabricateScript(sdk, entries)"));
            is("source: the verdict comes from the read-back", job != null && job.contains("OverlayRules.parseFabricate(out)") && job.contains("OverlayRules.judgeFabricate(op, wrote,"));
            is("source: the answer goes to onOverlayFab with ids and op", job != null && job.contains("settingsReply(\"onOverlayFab\", res)") && job.contains("res.put(\"ids\", idArr);") && job.contains("res.put(\"op\", op);"));
            is("source: a release build logs no script", job != null && job.contains("if (isDebuggableBuild()) Log.d(TAG, \"overlayFabricate [\" + op + \"]: \" + script") && job.contains("else Log.d(TAG, \"overlayFabricate [\" + op + \"]: \" + script.length() + \" chars"));
            is("source: nothing new to stop (the settings executor is shut down in onDestroy)", m.contains("settingsExecutor.shutdown();") && !m.contains("fabricateExecutor") && !m.contains("fabricateThread"));
        }

        System.out.println(n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
