package com.bloatware.bingblop;

/**
 * What a privileged command that was run as {@code cmd; echo "<marker>:$?"} came to. The exit status after the marker is the
 * verdict. Without the marker the command never got to finish: a timeout, a backend that did not start (adb, su) or Shizuku
 * without permission leave only an {@code Error: ...} line (or adb's own lowercase {@code error: ...} / {@code adb: error: ...}) or a {@code [Process timed out ...]} note, and that is a failure,
 * not a success. Any other output without the marker keeps the old reading (the backend did not run a real shell, so there is
 * nothing to parse and the output is taken as fine).
 */
final class ShellOutcome {
    final boolean ok;
    final String text;

    private ShellOutcome(boolean ok, String text) { this.ok = ok; this.text = text; }

    static ShellOutcome parse(String raw, String marker) {
        if (raw == null) raw = "";
        int idx = raw.lastIndexOf(marker + ":");
        if (idx < 0) return new ShellOutcome(!backendFailed(raw), raw);
        String text = raw.substring(0, idx);
        while (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        int rc;
        try { rc = Integer.parseInt(raw.substring(idx + marker.length() + 1).trim()); }
        catch (NumberFormatException e) { rc = 0; }
        return new ShellOutcome(rc == 0, text);
    }

    /** The backend itself reported that nothing ran: an "Error: ..." first line, or the timeout note. */
    static boolean backendFailed(String raw) {
        if (raw == null) return false;
        if (raw.contains("[Process timed out")) return true;
        // our own "Error: ..." lines, and what adb prints when it cannot reach the device ("error: device offline",
        // "error: device unauthorized", "error: no devices/emulators found", "adb: error: ...", "adb: device offline")
        String head = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return head.startsWith("error:") || head.startsWith("adb: error:") || head.startsWith("adb: device ");
    }
}
