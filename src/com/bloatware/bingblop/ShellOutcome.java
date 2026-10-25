package com.bloatware.bingblop;

/**
 * What a privileged command that was run as {@code cmd} followed by {@code echo "<marker>:$?"} (see {@link #withStatus}) came to. The exit status right after the marker is the
 * verdict: the digits up to the end of the line, nothing else, so a note the runner appends after it (the {@code [Process timed out ...]}
 * note) is not read as part of the number. The verdict is "fail closed": without the marker the command never got to finish (a timeout,
 * a backend that did not start (adb, su), Shizuku without permission, an output cut short), and that is a failure, not a success; so is a
 * marker whose status is missing or not a number (the output was cut off right there), and a status that is followed by the timeout note
 * (the shell was killed before the backend could pass the result on in full). {@link #backendFailed} still tells the typical markerless
 * failures apart (an {@code Error: ...} line, adb's own {@code error: ...} lines, the timeout note) for callers that want to word them.
 */
final class ShellOutcome {
    final boolean ok;
    final String text;

    private ShellOutcome(boolean ok, String text) { this.ok = ok; this.text = text; }

    /**
     * {@code cmd} with the status line after it. The echo is on a line of its own: on the same line a command that ends in a
     * {@code # comment} would swallow it (and one that ends in a lone {@code &} would be a syntax error), so a command that worked would
     * read as one that never ran.
     */
    static String withStatus(String cmd, String marker) {
        return cmd + "\necho \"" + marker + ":$?\"";
    }

    static ShellOutcome parse(String raw, String marker) {
        if (raw == null) raw = "";
        int idx = raw.lastIndexOf(marker + ":");
        if (idx < 0) return new ShellOutcome(false, raw);                  // no marker: the command never reached its end, nothing to trust
        String text = raw.substring(0, idx);
        while (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        String after = raw.substring(idx + marker.length() + 1);
        int n = 0;
        while (n < after.length() && after.charAt(n) >= '0' && after.charAt(n) <= '9') n++;
        // only the leading digits are the status; a status that is missing, too long or glued to other text was cut off or is not a status
        if (n == 0 || n > 9 || (n < after.length() && !Character.isWhitespace(after.charAt(n)))) return new ShellOutcome(false, text);
        int rc = Integer.parseInt(after.substring(0, n));
        String rest = after.substring(n).trim();
        if (rest.contains("[Process timed out")) {                           // the runner killed the shell after the status: fail closed, and say so
            return new ShellOutcome(false, text.isEmpty() ? rest : text + "\n" + rest);
        }
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
