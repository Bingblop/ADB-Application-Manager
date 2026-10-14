package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the page may ask the Connected Devices tab to run as {@code adb ...}. The page already refuses a few things (the adb server this app runs on
 * must not be stopped or moved), but the page is also what a hostile script would be; the same rules are applied here, before the command is built.
 * The device and its commands are not limited: the tab is an adb console for other devices on purpose. Pure Java, no android.* classes.
 */
final class AdbArgs {
    private AdbArgs() {}

    /** adb's own options that move it to another server or make it listen on every interface (also attached to their value, as in -P5037): the app sets the server itself. */
    private static final Pattern SERVER_OPTION = Pattern.compile("-(?:[PLH].*|a)|--(?:server-socket|one-device-server).*");
    /** Options that take the next argument as their value, so that argument is not the command (adb -s shell kill-server). */
    private static final Pattern VALUE_OPTION = Pattern.compile("-s|-t|--one-device");
    /** Options that stand alone before the command. */
    private static final Pattern FLAG_OPTION = Pattern.compile("-d|-e|--exit-on-write-error");
    /** Options after which adb prints text and exits, without a command. */
    private static final Pattern INFO_OPTION = Pattern.compile("--version|--help|-h");
    /** adb's commands that wait for a device and then carry on with the next word as the command. */
    private static final Pattern WAIT_COMMAND = Pattern.compile("wait-for(?:-[a-z]+)+");
    /** Subcommands that stop, start or detach the adb server this app depends on; {@code raw} talks to the server's own host services ({@code adb raw host:kill}). */
    private static final Pattern SERVER_COMMAND = Pattern.compile("kill-server|start-server|server|nodaemon|fork-server|reconnect-server|raw");

    /**
     * @param args the arguments after {@code adb} (and after the {@code -s serial} the app adds itself)
     * @return null when they may be run, otherwise what to tell the user
     */
    /** Subcommands that read or write files on this phone (push, pull, install, sideload, backup, restore): their arguments may name local files. */
    private static final Pattern LOCAL_FILE_COMMAND = Pattern.compile("push|pull|install|install-multiple|install-multi-package|sideload|backup|restore");

    /**
     * The arguments of a command that may name a file on this phone, so the caller can refuse one that is part of the app's own private data (the adb key,
     * the sealed secrets): every argument after a subcommand that reads or writes local files, the value of an {@code --option=value} included. It takes
     * more than only the local ones (a remote path of push or pull, an option's value); that is on purpose: the caller only refuses names that lead into
     * the app's private folder. Relative names are given as adb would read them from its working folder, which is the root of the file system here.
     */
    static List<String> localFileArgs(List<String> args) {
        List<String> out = new ArrayList<String>();
        if (args == null) return out;
        int i = 0;
        while (i < args.size() && args.get(i) != null && args.get(i).startsWith("-") && args.get(i).length() > 1) {
            i += VALUE_OPTION.matcher(args.get(i)).matches() ? 2 : 1;
        }
        while (i < args.size() && args.get(i) != null && WAIT_COMMAND.matcher(args.get(i)).matches()) i++;
        if (i >= args.size() || args.get(i) == null || !LOCAL_FILE_COMMAND.matcher(args.get(i)).matches()) return out;
        for (int k = i + 1; k < args.size(); k++) {
            String a = args.get(k);
            if (a == null || a.isEmpty()) continue;
            if (a.startsWith("-")) {
                int eq = a.indexOf('=');
                if (eq < 0 || eq == a.length() - 1) continue;
                a = a.substring(eq + 1);
            }
            out.add(a.startsWith("/") ? a : "/" + a);
        }
        return out;
    }

    /** host:port of a pairing address (name, IPv4 or bracketed IPv6; port 1-65535). It never starts with "-", so adb cannot read it as an option. */
    private static final Pattern PAIR_ENDPOINT = Pattern.compile("(?:\\[[0-9A-Fa-f:.]{2,45}\\]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):(\\d{1,5})");

    static boolean validPairEndpoint(String s) {
        if (s == null) return false;
        java.util.regex.Matcher m = PAIR_ENDPOINT.matcher(s);
        if (!m.matches()) return false;
        int port = Integer.parseInt(m.group(1));
        return port >= 1 && port <= 65535;
    }

    /** The code Android shows for Wireless debugging pairing: six digits. */
    static boolean validPairCode(String s) {
        return s != null && s.matches("\\d{6}");
    }

    static String check(List<String> args) {
        if (args == null || args.isEmpty() || args.size() > 80) return "no command";
        for (String a : args) {
            if (a == null || a.indexOf('\u0000') >= 0) return "bad argument";
        }
        // Read adb's global options the way adb does, with their values, to find the actual command; only that word is the subcommand.
        int i = 0;
        while (i < args.size() && args.get(i).startsWith("-") && args.get(i).length() > 1) {
            String o = args.get(i);
            if (SERVER_OPTION.matcher(o).matches()) return "That option would move adb to another server or make it listen on every interface, so it is not run.";
            if (VALUE_OPTION.matcher(o).matches()) { i += 2; continue; }
            if (INFO_OPTION.matcher(o).matches()) return null;
            if (FLAG_OPTION.matcher(o).matches()) { i++; continue; }
            return "That adb option is not run from here.";
        }
        // wait-for-device (and its -usb- / -local- / -any- forms) waits and then runs the command that follows it, so that one is the real subcommand
        boolean waited = false;
        while (i < args.size() && WAIT_COMMAND.matcher(args.get(i)).matches()) { i++; waited = true; }
        if (i >= args.size()) return waited ? null : "no command";      // "adb wait-for-device" alone just waits
        if (SERVER_COMMAND.matcher(args.get(i)).matches()) return "That one would stop the adb this app runs on, so it is not run.";
        return null;
    }
}
