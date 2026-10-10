package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks for strings that the page hands to the bridge and the bridge puts into a shell command (no Android classes, so it can be tested).
 * A string that does not pass is refused; nothing is repaired or guessed.
 *
 * <ul>
 * <li>{@link #installFlags}: the options of {@code pm install} / {@code pm install-create} that the Installer page can build, as a list of
 * arguments. Anything else, or a value of the wrong shape, gives {@code null}. {@link #join} puts a list back into one command with every
 * argument single-quoted.</li>
 * <li>{@link #isAppOp}, {@link #isAppOpMode}: the name and value of {@code appops set}.</li>
 * </ul>
 * Package and permission names are checked by {@link BackupScripts#isPackageName} and {@link BackupScripts#isPermission}.
 */
final class ShellArgs {
    private ShellArgs() {}

    private static final Pattern APP_OP = Pattern.compile("[A-Z][A-Z0-9_]+");
    private static final Pattern APP_OP_MODE = Pattern.compile("allow|ignore|deny|foreground|default");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,9}");
    private static final Pattern URI = Pattern.compile("[A-Za-z][A-Za-z0-9+.\\-]*:[\\x21-\\x7e]{0,2000}");     // a scheme and printable ASCII, no white space
    private static final int MAX_FLAGS = 40;

    private static final java.util.Set<String> COMPILE_MODES = new java.util.HashSet<String>(java.util.Arrays.asList(
            "assume-verified", "extract", "verify", "quicken", "space-profile", "space", "speed-profile", "speed", "everything-profile", "everything"));

    /** True for a filter of {@code pm compile -m <mode>}: one of the modes ART knows. */
    static boolean isCompileMode(String s) { return s != null && COMPILE_MODES.contains(s); }

    /**
     * The command that recompiles (or, for {@code reset}, un-compiles) one app: {@code pm compile -m <mode> [-f] <pkg>}, or
     * {@code pm compile --reset <pkg>}. Null when the package or the mode is not valid; an empty or null mode means {@code speed}.
     */
    static String compileCommand(String pkg, String mode, boolean force) {
        if (!BackupScripts.isPackageName(pkg)) return null;
        String m = mode == null || mode.isEmpty() ? "speed" : mode;
        if (m.equals("reset")) return "pm compile --reset " + pkg;
        if (!isCompileMode(m)) return null;
        return "pm compile -m " + m + (force ? " -f " : " ") + pkg;
    }

    static boolean isAppOp(String s) { return s != null && APP_OP.matcher(s).matches(); }

    static boolean isAppOpMode(String s) { return s != null && APP_OP_MODE.matcher(s).matches(); }

    /** The install options as arguments (an empty or null string means the default {@code -r}), or null when anything is not allowed. */
    static List<String> installFlags(String flags) {
        List<String> out = new ArrayList<String>();
        String t = flags == null ? "" : flags.trim();
        if (t.isEmpty()) { out.add("-r"); return out; }
        String[] w = t.split("\\s+");
        if (w.length > MAX_FLAGS) return null;
        for (int i = 0; i < w.length; i++) {
            String f = w[i];
            if (f.equals("-r") || f.equals("-g") || f.equals("-d") || f.equals("-t") || f.equals("--bypass-low-target-sdk-block") || f.equals("--update-ownership")) {
                out.add(f);
                continue;
            }
            if (i + 1 >= w.length) return null;                         // every other option needs a value
            String v = w[++i];
            if (f.equals("--user")) {
                if (!(v.equals("all") || v.equals("current") || DIGITS.matcher(v).matches())) return null;
            } else if (f.equals("--install-reason") || f.equals("--package-source")) {
                if (!DIGITS.matcher(v).matches()) return null;
            } else if (f.equals("-i")) {
                if (!BackupScripts.isPackageName(v)) return null;
            } else if (f.equals("--originating-uri")) {
                if (!URI.matcher(v).matches()) return null;
            } else {
                return null;
            }
            out.add(f);
            out.add(v);
        }
        return out;
    }

    /** The arguments as one string for sh, every one single-quoted. */
    static String join(List<String> args) {
        StringBuilder b = new StringBuilder();
        for (String a : args) {
            if (b.length() > 0) b.append(' ');
            b.append(BackupScripts.quote(a));
        }
        return b.toString();
    }
}
