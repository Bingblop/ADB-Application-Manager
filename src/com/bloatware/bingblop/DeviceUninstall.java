package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Removing an app on ANOTHER device (the Connected Devices tab) the way this phone removes one for itself: {@code pm uninstall --user 0}, and when
 * the device answers that only root can delete a system app for one user, the same direct Binder call this app uses on its own phone
 * ({@link SystemlessUninstallRunner}, which calls {@code IPackageManager.deletePackageAsUser} with the DELETE_SYSTEM_APP flag). That call has to run
 * on the device itself, so a small helper jar is sent there with {@code adb push}, run with {@code app_process} and taken off again.
 *
 * The adb itself is a callback (as in {@link DeviceLink}), so the rules are tested against a fake. No Android classes.
 */
public final class DeviceUninstall {
    private DeviceUninstall() {}

    /** ok: the app is gone for the device's user; text: what to show (the device's own words, then a note when there is one). */
    public static final class Result {
        public final boolean ok;
        public final String text;

        Result(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    static final String RUNNER_CLASS = "com.bloatware.bingblop.SystemlessUninstallRunner";
    private static final String MARK = "__DEVRC__";
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    /** A package name as Android has them (letters, digits, underscores, at least one dot): the only thing that ever goes into the device's shell. */
    public static boolean validPackage(String p) {
        return p != null && p.length() <= 255 && PKG.matcher(p).matches();
    }

    /** What a command printed, and its exit status when the marker we put after it came back. */
    private static final class Out {
        String text = "";
        int rc = -1;
        boolean marked = false;
    }

    private static Out parse(String raw) {
        Out o = new Out();
        String s = raw == null ? "" : raw;
        int i = s.lastIndexOf(MARK + ":");
        if (i < 0) {
            o.text = s.trim();
            return o;
        }
        o.text = s.substring(0, i).trim();
        String num = s.substring(i + MARK.length() + 1).trim();
        int end = 0;
        while (end < num.length() && Character.isDigit(num.charAt(end))) end++;
        try {
            o.rc = Integer.parseInt(num.substring(0, end));
            o.marked = true;
        } catch (NumberFormatException ignored) {}
        return o;
    }

    private static Out shell(DeviceLink.Adb adb, String cmd, int timeoutMs) {
        List<String> a = new ArrayList<String>();
        a.add("shell");
        a.add(cmd + "; echo \"" + MARK + ":$?\"");
        return parse(adb.run(a, timeoutMs));
    }

    private static boolean pushed(String out) {
        if (out == null) return false;
        String o = out.toLowerCase(Locale.US);
        return (o.contains("pushed") || o.contains("bytes in")) && !o.contains("error") && !o.contains("failed");
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.trim();
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(0, nl).trim();
        return t.isEmpty() ? "no answer" : (t.length() > 160 ? t.substring(0, 160) : t);
    }

    private static Result withHint(boolean ok, String text) {
        if (ok) return new Result(true, text);
        String advice = UninstallHints.adviceForDevice(text);
        return new Result(false, advice.isEmpty() ? text : text.trim() + "\n\nNote: " + advice);
    }

    /**
     * Uninstalls pkg for the device's user 0. runnerPath: the helper jar (or an APK holding the runner) on THIS phone, or null / empty when there is none,
     * and then the fallback is not tried. unique: a short text that makes the helper's name on the device its own.
     */
    public static Result run(String pkg, DeviceLink.Adb adb, String runnerPath, String unique) {
        if (!validPackage(pkg)) return new Result(false, "Error: that is not a package name.");
        Out first = shell(adb, "pm uninstall --user 0 '" + pkg + "'", 60000);
        boolean ok = UninstallHints.success(first.text) && (!first.marked || first.rc == 0);
        if (ok) return new Result(true, first.text);

        String refused = first.text;
        if (!UninstallHints.rootRequired(refused) || runnerPath == null || runnerPath.isEmpty()) return withHint(false, refused);

        String safe = unique == null ? "" : unique.replaceAll("[^A-Za-z0-9]", "");
        String remote = "/data/local/tmp/adbam_unin_" + (safe.isEmpty() ? "x" : safe) + ".jar";
        List<String> push = new ArrayList<String>();
        push.add("push");
        push.add(runnerPath);
        push.add(remote);
        String sent = adb.run(push, 120000);
        if (!pushed(sent)) {
            return withHint(false, refused + "\n\nThe helper that removes a system app for one user could not be sent to the device (" + oneLine(sent) + ").");
        }
        Out ran = shell(adb, "chmod 444 " + remote + "; CLASSPATH=" + remote + " app_process /system/bin " + RUNNER_CLASS + " '" + pkg + "' 0", 60000);
        List<String> rm = new ArrayList<String>();
        rm.add("shell");
        rm.add("rm -f " + remote);
        adb.run(rm, 15000);                                                      // the helper does not stay on the device, whatever came of it

        if (ran.text.contains("RESULT:OK")) {
            return new Result(true, "Success\n\nRemoved with a direct Binder call (IPackageManager.deletePackageAsUser), because the device's shell-level `pm uninstall` needs actual root for a system app. "
                    + "The app stays in the device's system partition but is gone for its user, the same result a normal uninstall gives for any other app.");
        }
        if (ran.text.contains("RESULT:FAIL:") || ran.text.contains("RESULT:ERROR:")) {
            return withHint(false, refused + "\n\n" + ran.text);
        }
        return withHint(false, refused + (ran.text.isEmpty() ? "" : "\n\n" + ran.text));
    }
}
