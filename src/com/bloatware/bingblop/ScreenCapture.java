package com.bloatware.bingblop;

import java.util.Locale;
import java.util.TimeZone;

/**
 * Screenshots and screen recordings taken through a ready privileged shell (screencap and screenrecord are shell tools; this app cannot run them
 * as itself): the folders, the file names, the shell commands, and the reading of what they printed. Whether a file was really written is decided
 * from the size the phone reports for it, never from the command's exit text alone. No Android classes, so the rules run in tests/java.
 */
final class ScreenCapture {
    private ScreenCapture() {}

    static final String SHOT_DIR = "/sdcard/Pictures/ADB App Manager";
    static final String VIDEO_DIR = "/sdcard/Movies/ADB App Manager";
    static final int MAX_DELAY_SEC = 10;
    static final int MIN_RECORD_SEC = 5;
    static final int MAX_RECORD_SEC = 180;                // screenrecord's own limit is 3 minutes
    static final String EXIT_MARK = "EXIT:";
    static final String SIZE_MARK = "SIZE:";

    static int clampDelay(int sec) { return Math.max(0, Math.min(MAX_DELAY_SEC, sec)); }
    static int clampRecord(int sec) { return Math.max(MIN_RECORD_SEC, Math.min(MAX_RECORD_SEC, sec)); }

    private static String stamp(long epochMs, TimeZone tz) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        f.setTimeZone(tz);
        return f.format(new java.util.Date(epochMs));
    }

    static String shotPath(long epochMs, TimeZone tz) { return SHOT_DIR + "/screenshot-" + stamp(epochMs, tz) + ".png"; }
    static String videoPath(long epochMs, TimeZone tz) { return VIDEO_DIR + "/screenrecord-" + stamp(epochMs, tz) + ".mp4"; }

    /** True only for a path this class made: one of the two folders, then a plain file name. Anything else is never put into a command. */
    static boolean ownPath(String path) {
        if (path == null) return false;
        return path.matches("/sdcard/Pictures/ADB App Manager/screenshot-\\d{8}-\\d{6}\\.png")
            || path.matches("/sdcard/Movies/ADB App Manager/screenrecord-\\d{8}-\\d{6}\\.mp4");
    }

    private static String dirOf(String path) { return path.substring(0, path.lastIndexOf('/')); }

    /** The shell line that makes the folder, runs {@code tool}, and prints the exit status and the size of the file. Null for a path that is not ours. */
    private static String line(String tool, String path) {
        if (!ownPath(path)) return null;
        String q = RishShell.quote(path);
        return "mkdir -p " + RishShell.quote(dirOf(path)) + " && " + tool + " " + q + "; echo \"" + EXIT_MARK + "$?\"; echo \"" + SIZE_MARK + "$(wc -c < " + q + " 2>/dev/null)\"";
    }

    static String shotCommand(String path) { return line("screencap -p", path); }

    static String recordCommand(String path, int seconds) {
        return line("screenrecord --time-limit " + clampRecord(seconds), path);
    }

    /** SIGINT is how screenrecord is asked to finish the file properly; pkill is tried first, killall second. */
    static String stopCommand() { return "pkill -2 -x screenrecord || killall -2 screenrecord"; }

    static final class Result {
        int exit = -1;
        long bytes = -1;
        String text = "";
        /** A file was written: the phone reports a size above zero. */
        boolean wrote() { return bytes > 0; }
    }

    static Result parse(String raw) {
        Result r = new Result();
        if (raw == null) return r;
        StringBuilder text = new StringBuilder();
        for (String line : raw.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.startsWith(EXIT_MARK)) { try { r.exit = Integer.parseInt(t.substring(EXIT_MARK.length()).trim()); } catch (NumberFormatException ignored) {} continue; }
            if (t.startsWith(SIZE_MARK)) { try { r.bytes = Long.parseLong(t.substring(SIZE_MARK.length()).trim()); } catch (NumberFormatException ignored) {} continue; }
            if (t.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        r.text = text.length() > 1000 ? text.substring(text.length() - 1000) : text.toString();
        return r;
    }

    /** "ok", "failed" or "no_access" for the answer to a capture. The shell's own refusal ("Error: ...") means there was nothing to run it with. */
    static String status(String raw, Result r) {
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("Error:") && r.exit == -1) return "no_access";
        return r.wrote() ? "ok" : "failed";
    }
}
