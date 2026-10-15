package com.bloatware.bingblop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ADB Stub for Wear OS watches (Connected Devices): the small app of wearstub/, built by build.sh into assets/adb_stub.apk. Installed on a watch it
 * keeps the screen on and Wi-Fi connected while this phone is connected, and shows the state of the connection. These are the shell commands the
 * phone sends for it and the reading of their answers; the adb itself runs in MainActivity. No Android classes.
 */
public final class WearStub {
    private WearStub() {}

    public static final String PKG = "com.bloatware.bingblop.adbstub";
    /** android:versionCode of wearstub/AndroidManifest.xml (the WearStub suite checks they agree). A watch with an older one is offered Update. */
    public static final int VERSION = 2;
    static final String ACTIVITY = PKG + "/.StubActivity";
    static final String STOP_ACTION = PKG + ".STOP";
    static final String RENEW_ACTION = PKG + ".RENEW";
    static final String RECEIVER = PKG + "/.StopReceiver";

    /** What the watch has: installed (and which version), running. */
    public static final class Status {
        public final boolean installed;
        public final int version;
        public final boolean running;

        Status(boolean installed, int version, boolean running) {
            this.installed = installed;
            this.version = version;
            this.running = running;
        }
    }

    /** Prints "inst:package:/data/app/..." (nothing after "inst:" when it is not installed), its versionCode line, and "pid:" with its process ids. */
    static String statusCmd() {
        return "p=$(pm path " + PKG + " 2>/dev/null | head -n 1); echo \"inst:$p\"; "
                + "case \"$p\" in package:*) dumpsys package " + PKG + " 2>/dev/null | grep -m 1 'versionCode=';; esac; "
                + "echo \"pid:$(pidof " + PKG + " 2>/dev/null)\"";
    }

    private static final Pattern VER = Pattern.compile("versionCode=(\\d+)");
    private static final Pattern PID = Pattern.compile("(?m)^pid:\\s*\\d+");
    private static final Pattern INST = Pattern.compile("(?m)^inst:package:\\S");

    static Status parseStatus(String out) {
        String s = out == null ? "" : out;
        boolean inst = INST.matcher(s).find();
        int ver = 0;
        if (inst) {
            Matcher m = VER.matcher(s);
            if (m.find()) {
                try { ver = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        return new Status(inst, ver, inst && PID.matcher(s).find());
    }

    /** The phone's name as the stub's screen shows it: printable, at most 60 characters, quoted for the device's shell. */
    static String quotedPhone(String phone) {
        String p = phone == null ? "" : phone.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (p.length() > 60) p = p.substring(0, 60);
        return "'" + p.replace("'", "'\\''") + "'";
    }

    /** Opens the stub's screen, which starts its service (am start from the shell may open an activity of an app in the background). */
    static String startCmd(String phone) {
        return "am start -n " + ACTIVITY + " --es phone " + quotedPhone(phone);
    }

    /** am start prints "Starting: Intent { ... }", and "Error: ..." / "Error type N" when the activity cannot be opened. */
    static boolean started(String out) {
        String s = out == null ? "" : out;
        return s.contains("Starting:") && !s.contains("Error");
    }

    /**
     * Renews the stub's lease (version 2 and later): the stub lets go of the watch by itself when it has not heard from the phone for 5 minutes, so the
     * page sends this about once a minute while the stub runs on a connected watch. Only the shell and the system may send it (DUMP).
     */
    static String renewCmd() {
        return "am broadcast -a " + RENEW_ACTION + " -n " + RECEIVER;
    }

    /** am broadcast prints "Broadcast completed: result=0" when the broadcast was delivered (to the stub, or to nothing when it is not running). */
    static boolean renewed(String out) {
        String s = out == null ? "" : out;
        return s.contains("Broadcast completed") && !s.contains("Error");
    }

    /**
     * Asks the stub to let go and close (its Stop receiver), then ends its process: when the process is gone the watch drops its screen wake lock,
     * its Wi-Fi lock and its Wi-Fi request too, so Wi-Fi goes back to normal even if the broadcast was not taken.
     */
    static String stopCmd() {
        return "am broadcast -a " + STOP_ACTION + " -n " + RECEIVER + " >/dev/null 2>&1; sleep 1; am force-stop " + PKG + "; echo stopped";
    }
}
