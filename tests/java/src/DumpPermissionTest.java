package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * android.permission.DUMP in the permission sheet. MainActivity needs Android classes, so this is a source scan of the wiring: the manifest declares the permission,
 * the bridge answers from the phone's own permission check (never from what the pm grant command printed), "Allow all" grants it only through a working mode, and the
 * page lists it as a privileged-only row that is shown only when the build reports it, right after Write Secure Settings.
 */
public class DumpPermissionTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static void check(String name, boolean ok) { check(name, ok, null); }

    static File repo() {
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null; d = d.getParentFile())
            if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return d;
        throw new IllegalStateException("repository not found");
    }

    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** The text of the method (or block) that starts at {@code head}: from the head to its matching closing brace. */
    static String body(String src, String head) {
        int i = src.indexOf(head);
        if (i < 0) return "";
        int open = src.indexOf('{', i), depth = 0;
        for (int j = open; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(i, j + 1);
        }
        return "";
    }

    static boolean has(String s, String... parts) { for (String p : parts) if (!s.contains(p)) return false; return true; }

    public static void main(String[] args) throws Exception {
        File repo = repo();
        String main = read(new File(repo, "src/com/bloatware/bingblop/MainActivity.java"));
        String manifest = read(new File(repo, "AndroidManifest.xml"));
        String page = read(new File(repo, "assets/index.html"));

        // ---- manifest
        check("manifest: DUMP is declared", manifest.contains("<uses-permission android:name=\"android.permission.DUMP\" />"));
        int wss = manifest.indexOf("android.permission.WRITE_SECURE_SETTINGS"), dump = manifest.indexOf("android.permission.DUMP\" />");
        check("manifest: it sits next to WRITE_SECURE_SETTINGS, with a comment that says how it is granted", wss > 0 && dump > wss && dump - wss < 600
                && has(manifest.substring(wss, dump), "<!--", "signature|privileged|development", "pm grant", "dumpsys"));

        // ---- the check: the phone's own answer
        String has = body(main, "private boolean hasDumpAccess()");
        check("hasDumpAccess() exists and asks checkSelfPermission(DUMP) == GRANTED",
                has(has, "checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED", "catch (Exception e)", "return false"), has);

        // ---- the request
        String req = body(main, "public String requestDumpAccess()");
        int at = main.indexOf("public String requestDumpAccess()");
        check("requestDumpAccess() is a @JavascriptInterface of the bridge", at > 0 && main.substring(Math.max(0, at - 40), at).contains("@JavascriptInterface"));
        check("   it is already granted: answers \"granted\" without a command", req.indexOf("if (hasDumpAccess()) return \"granted\";") > 0
                && req.indexOf("if (hasDumpAccess()) return \"granted\";") < req.indexOf("executeShell"), req);
        check("   it runs pm grant for this package and DUMP only when the mode is not 'standard'",
                has(req, "!\"standard\".equals(resolveExecMode())", "executeShell(\"pm grant \" + getPackageName() + \" android.permission.DUMP\");")
                        && req.indexOf("!\"standard\".equals(resolveExecMode())") < req.indexOf("executeShell"), req);
        check("   it does not judge by the command's output (the result is not read) but re-checks with the phone's permission check",
                !req.contains("= executeShell") && req.indexOf("executeShell") < req.lastIndexOf("if (hasDumpAccess()) return \"granted\";"), req);
        check("   otherwise it answers \"settings\" (nothing to open: there is no Settings screen for it)", req.contains("return \"settings\";"), req);

        // ---- the status
        String status = body(main, "public String getPermissionStatus()");
        check("getPermissionStatus() reports \"dump\" from hasDumpAccess()", status.contains("o.put(\"dump\", hasDumpAccess());"), status);

        // ---- Allow all
        String all = body(main, "public String grantAllPermissions()");
        int std = all.indexOf("if (!\"standard\".equals(resolveExecMode()))"), g = all.indexOf("if (!hasDumpAccess()) sb.append(\"pm grant \").append(pkg).append(\" android.permission.DUMP; \");");
        check("grantAllPermissions() adds pm grant DUMP when it is not held, inside the working-mode branch, before the single shell run", std >= 0 && g > std
                && g < all.indexOf("executeShell(sb.toString()"), all);
        check("   and answers with the status read back from the phone", all.trim().contains("return getPermissionStatus();"));

        // ---- Copy grant commands
        String cmds = body(main, "public String getGrantCommands()");
        at = main.indexOf("public String getGrantCommands()");
        check("getGrantCommands() is a @JavascriptInterface and fills in this app's package name", at > 0 && main.substring(Math.max(0, at - 40), at).contains("@JavascriptInterface") && cmds.contains("String pkg = getPackageName();"), cmds);
        check("   every line is an 'adb shell pm grant' / 'adb shell appops set' line, one per line", has(cmds, "\"adb shell pm grant \" + pkg", "\"adb shell appops set \" + pkg", "sb.append('\\n')"), cmds);
        check("   each is added only when the phone's own check says the access is not held (including DUMP and WRITE_SECURE_SETTINGS)",
                has(cmds, "if (!hasDumpAccess()) out.add(pm + \"DUMP\");", "if (!hasWriteSecureSettingsAccess()) out.add(pm + \"WRITE_SECURE_SETTINGS\");", "if (!hasUsageAccess())", "if (!hasOverlayAccess())", "if (!hasInstallUnknownAccess())", "if (!hasStorageAccess())", "if (!hasLegacyStorageAccess())"), cmds);
        check("   Allow Restricted Settings is not listed (a command run from a computer cannot set the app's own 'restricted_granted' flag), and the code says why",
                !cmds.contains("ACCESS_RESTRICTED_SETTINGS allow") && !cmds.contains("if (!hasRestrictedSettingsAccess())") && cmds.contains("Allow Restricted Settings is left out on purpose") && cmds.contains("restricted_granted"), cmds);
        check("   the 'restricted_granted' flag is written only by grantRestrictedSettings()", main.split("putBoolean\\(\"restricted_granted\"", -1).length - 1 == 1 && body(main, "private boolean grantRestrictedSettings()").contains("putBoolean(\"restricted_granted\", true)"));
        check("   it runs nothing (a list only)", !cmds.contains("executeShell"), cmds);
        String all2 = body(main, "public String grantAllPermissions()");
        for (String perm : new String[]{"PACKAGE_USAGE_STATS", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "WRITE_SECURE_SETTINGS", "DUMP", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "GET_USAGE_STATS", "MANAGE_EXTERNAL_STORAGE"})
            check("   the same command for " + perm + " as 'Allow all' runs", all2.contains(perm) && cmds.contains(perm));
        check("   the page copies it with the app's clipboard helper, tells where to run it, and hides the button when nothing is missing or there is no list",
                has(page, "id=\"permCopyBtn\"", "onclick=\"permCopyCommands()\"", "window.AndroidBridge.copyToClipboard(cmds)", "Run these on a computer with USB debugging", "!files && permGrantCommands() ? '' : 'none'"));

        // ---- the page
        int sec = page.indexOf("{ key: 'secure_settings'"), dmp = page.indexOf("{ key: 'dump'");
        int end = page.indexOf("];", dmp);
        String row = dmp > 0 ? page.substring(dmp, page.indexOf("}", dmp) + 1) : "";
        int next = page.indexOf("{ key:", dmp + 5);
        check("page: PERM_DEFS has the dump row right after secure_settings and it is the last one", sec > 0 && dmp > sec && page.indexOf("{ key:", sec + 5) == dmp && (next < 0 || next > end), row);
        check("   it is privOnly (a working mode) and known (only when the build reports it)", has(row, "privOnly: true", "known: true"), row);
        check("   its name and reason", has(row, "name: 'Dump system services (DUMP)'", "dumpsys", "battery, power and memory", "working mode"), row);
        check("   Allow calls the bridge's requestDumpAccess", page.contains("else if (key === 'dump') { if (b.requestDumpAccess) answer = String(b.requestDumpAccess()); }"));
        check("   the filter that hides it: privOnly needs isPrivilegedActive, known needs a reported status", page.contains("(!d.privOnly || isPrivilegedActive) && (!d.known || permStatus[d.key] !== undefined)"));

        // ---- the languages: the name and the reason are translated in every dictionary
        File[] langs = new File(repo, "assets/lang").listFiles((d, n) -> n.endsWith(".js"));
        int n = 0, missing = 0;
        for (File f : langs) {
            String t = read(f);
            n++;
            if (!t.contains("\"Dump system services (DUMP)\":") || !t.contains("\"Lets this app read the phone's own status reports (dumpsys)")) missing++;
        }
        check("every dictionary in assets/lang has both new strings (" + n + " dictionaries)", n >= 13 && missing == 0, "missing in " + missing);

        System.out.println(failed == 0 ? "all ok" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
