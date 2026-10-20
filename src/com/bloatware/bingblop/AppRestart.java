package com.bloatware.bingblop;

/**
 * The restart after "Allow all" through a working mode (ADB, Shizuku or root).
 *
 * <p>What was just allowed by command only holds everywhere once the app runs in a fresh process (the storage group that All files access brings, for one),
 * and a phone may end the process itself while a permission is being set. So the app cannot restart from its own code: a shell that outlives it does it.
 * {@link #watchScript} is run through the working mode before the grant commands. It leaves a watcher behind, in a shell of its own with nothing tying it
 * to this process, that waits for this process to end (the app ends it with {@code restartApp} once something was granted, or the phone does) and then
 * starts the app again with {@code am start}, for the user this process runs as (a copy in a work profile or the private space is that user's, not the
 * foreground user's); the shell user and root may start an activity from the background. A process still alive after
 * {@link #WATCH_SECONDS} is no restart: the watcher leaves it be and ends. The app also ends the watcher by its pid ({@link #killScript}) when nothing was
 * granted, so a later close by hand is not followed by an unasked start.
 *
 * <p>The script prints exactly one line: {@link #MARK_WATCH} and the watcher's pid, or {@link #MARK_NO_PROC} when the shell cannot see this process
 * (then no watcher is left and the app falls back to reloading its page, as before).
 *
 * <p>Pure Java: tested by running the script for real (see AppRestartTest).
 */
public final class AppRestart {
    private AppRestart() {}

    /** Printed with the watcher's pid when the watcher was started. */
    public static final String MARK_WATCH = "@@RESTART-WATCH@@";
    /** Printed when the shell cannot see the app's process: nothing was started. */
    public static final String MARK_NO_PROC = "@@RESTART-NOPROC@@";
    /** How long the watcher waits for the process to end before it gives up (seconds, one look per second). */
    public static final int WATCH_SECONDS = 30;
    /** The pause between the end of the process and {@code am start}, so the old process is fully gone (seconds). */
    public static final int RELAUNCH_PAUSE_SECONDS = 1;
    /** Android's uids per user ({@code UserHandle.PER_USER_RANGE}): a process's user is its uid divided by this. */
    public static final int PER_USER_RANGE = 100000;

    /**
     * The user a process with this uid runs as: 0 for the phone's owner, 10 and up for a work profile, the private space or another user. The watcher
     * starts the app for that user, since {@code am start} without {@code --user} starts the copy of the current (foreground) user.
     */
    public static int userOf(int uid) {
        if (uid < 0) throw new IllegalArgumentException("bad uid");
        return uid / PER_USER_RANGE;
    }

    /** {@code package/activity} for {@code am start -n}; both parts must be plain Java names (letters, digits, dots, underscores, a {@code $}). */
    public static String component(String pkg, String activity) {
        checkName(pkg, "package");
        checkName(activity, "activity");
        return pkg + "/" + activity;
    }

    private static void checkName(String s, String what) {
        if (s == null || s.isEmpty() || s.length() > 300 || !s.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) throw new IllegalArgumentException("bad " + what + " name");
    }

    /**
     * The script that leaves the watcher behind. One line, so it passes unchanged through {@code adb shell}, Shizuku's {@code sh -c} and {@code su -c}.
     * The watcher's body takes the pid, the component and the user ({@link #userOf}) as its arguments, so none is spliced into shell text.
     */
    public static String watchScript(int pid, int user, String component) {
        if (pid <= 0) throw new IllegalArgumentException("bad pid");
        if (user < 0) throw new IllegalArgumentException("bad user");
        if (component == null || !component.matches("[A-Za-z0-9_$.]+/[A-Za-z0-9_$.]+")) throw new IllegalArgumentException("bad component");
        // the watcher: ignore the hang-up (and a closed pipe) the shell that started it gets when the app's connection drops, but not TERM, which is how
        // killScript ends it; look once a second; alive after the limit = no restart; then start the copy of the user this process ran as
        String body = "trap \"\" HUP PIPE; n=0; while [ -d /proc/$1 ] && [ $n -lt " + WATCH_SECONDS + " ]; do sleep 1; n=$((n+1)); done; "
            + "[ -d /proc/$1 ] && exit 0; sleep " + RELAUNCH_PAUSE_SECONDS + "; am start --user \"$3\" -n \"$2\"";
        return "P=" + pid + "; U=" + user + "; A=" + BackupScripts.quote(component) + "; "
            + "[ -d /proc/$P ] || { echo " + MARK_NO_PROC + "; exit 0; }; "
            + "W=" + BackupScripts.quote(body) + "; "
            // setsid where the phone has it (a session of its own, so a signal to the group of the shell that is going away does not reach the watcher), plain sh otherwise
            + "if command -v setsid >/dev/null 2>&1; then setsid sh -c \"$W\" sh \"$P\" \"$A\" \"$U\" >/dev/null 2>&1 </dev/null & "
            + "else sh -c \"$W\" sh \"$P\" \"$A\" \"$U\" >/dev/null 2>&1 </dev/null & fi; "
            + "echo " + MARK_WATCH + " $!";
    }

    /** The watcher's pid from what {@link #watchScript} printed, or null when none was started (no mark, the no-process mark, or a pid that is not a number). */
    public static String watcherPid(String output) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (!t.startsWith(MARK_WATCH)) continue;
            String pid = t.substring(MARK_WATCH.length()).trim();
            if (pid.matches("[1-9][0-9]{0,9}")) return pid;
            return null;
        }
        return null;
    }

    /** Ends the watcher: nothing was granted, so the process is not going to be restarted. */
    public static String killScript(String watcherPid) {
        if (watcherPid == null || !watcherPid.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("bad pid");
        return "kill " + watcherPid + " >/dev/null 2>&1; true";
    }
}
