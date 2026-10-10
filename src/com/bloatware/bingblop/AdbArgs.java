package com.bloatware.bingblop;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What the page may ask the Connected Devices tab to run as {@code adb ...}. The page already refuses a few things (the adb server this app runs on
 * must not be stopped or moved), but the page is also what a hostile script would be; the same rules are applied here, before the command is built.
 * The device and its commands are not limited: the tab is an adb console for other devices on purpose. Pure Java, no android.* classes.
 */
final class AdbArgs {
    private AdbArgs() {}

    /** adb's own options that move it to another server or make it listen on every interface: the app sets the server and the device itself. */
    private static final Pattern SERVER_OPTION = Pattern.compile("-(?:[PLH].*|a)");
    /** Subcommands that stop, start or detach the adb server this app depends on. */
    private static final Pattern SERVER_COMMAND = Pattern.compile("kill-server|start-server|server|nodaemon|fork-server|reconnect-server");

    /**
     * @param args the arguments after {@code adb} (and after the {@code -s serial} the app adds itself)
     * @return null when they may be run, otherwise what to tell the user
     */
    static String check(List<String> args) {
        if (args == null || args.isEmpty() || args.size() > 80) return "no command";
        for (String a : args) {
            if (a == null || a.indexOf('\u0000') >= 0) return "bad argument";
        }
        String first = args.get(0);
        if (SERVER_OPTION.matcher(first).matches()) return "That option would move adb to another server, so it is not run.";
        for (String a : args) {
            if (a.equals("shell") || a.equals("exec-out")) break;        // what comes after is for the device, not for adb
            if (SERVER_COMMAND.matcher(a).matches()) return "That one would stop the adb this app runs on, so it is not run.";
        }
        return null;
    }
}
