package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Starting the Shizuku service from this app through a ready adb connection: the shell command, and the reading of what it printed. The start
 * script is the one the installed Shizuku app itself writes into its own data folder (it is not copied or hardcoded here, and not run when it is
 * missing). Whether Shizuku really started is decided by the caller from the binder, never from this text. No Android classes, so the rules run
 * in tests/java.
 */
final class ShizukuStart {
    private ShizukuStart() {}

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    static final String NO_SCRIPT = "NO_START_SCRIPT";
    static final String SCRIPT_MARK = "SCRIPT:";
    static final String EXIT_MARK = "EXIT:";

    /** True for a plain package name; anything else is never put into a command. */
    static boolean validPackage(String pkg) {
        return pkg != null && pkg.length() <= 255 && PACKAGE.matcher(pkg).matches();
    }

    /** Where the Shizuku app (or its Shizuku+ fork) keeps its start script, in the order they are looked at. Empty for a bad package name. */
    static List<String> scriptPaths(String pkg) {
        List<String> out = new ArrayList<String>();
        if (!validPackage(pkg)) return out;
        out.add("/sdcard/Android/data/" + pkg + "/start.sh");
        out.add("/storage/emulated/0/Android/data/" + pkg + "/start.sh");
        return out;
    }

    /**
     * The command for {@code adb shell}: runs the first start script that exists (printing which one, and its exit status) or prints
     * {@value #NO_SCRIPT}. Null for a bad package name.
     */
    static String command(String pkg) {
        List<String> paths = scriptPaths(pkg);
        if (paths.isEmpty()) return null;
        StringBuilder list = new StringBuilder();
        for (String p : paths) list.append(RishShell.quote(p)).append(' ');
        return "for f in " + list + "; do if [ -f \"$f\" ]; then echo \"" + SCRIPT_MARK + "$f\"; sh \"$f\" 2>&1; echo \"" + EXIT_MARK + "$?\"; exit 0; fi; done; echo " + NO_SCRIPT;
    }

    /** What {@link #command} printed: which script ran, its exit status (-1 when unknown), and the script's own output. */
    static final class Result {
        boolean noScript;
        String script = "";
        int exit = -1;
        String text = "";
    }

    static Result parse(String raw) {
        Result r = new Result();
        if (raw == null) return r;
        StringBuilder text = new StringBuilder();
        for (String line : raw.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.equals(NO_SCRIPT)) { r.noScript = true; continue; }
            if (t.startsWith(SCRIPT_MARK)) { r.script = t.substring(SCRIPT_MARK.length()); continue; }
            if (t.startsWith(EXIT_MARK)) {
                try { r.exit = Integer.parseInt(t.substring(EXIT_MARK.length()).trim()); } catch (NumberFormatException ignored) {}
                continue;
            }
            if (t.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        if (!r.script.isEmpty()) r.noScript = false;      // a script that ran and happened to print the marker word is still a script that ran
        r.text = text.toString();
        if (r.text.length() > 4000) r.text = r.text.substring(r.text.length() - 4000);     // the end of a long output is the part that explains
        return r;
    }
}
