package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The decisions of the patcher service that need no Android classes, so they can be tested on a computer: what happens to a RUN that arrives while
 * a job is going, which Cancel may stop the job that is going, how the events file is opened and the Cancel lines are written into it, and
 * what the notification says about a line of the engine.
 */
final class MorpheServiceRules {
    private MorpheServiceRules() {}

    static final String BUSY = "The patcher is busy with another job.";

    /**
     * A RUN arrived while another job is going. Writes the answer into the events file of the NEW run (the one its runner is waiting on), never into the
     * file of the running job. Returns whether it wrote.
     */
    static boolean answerBusy(File newEvents, File runningEvents) {
        if (newEvents == null) return false;
        try {
            if (runningEvents != null && runningEvents.getCanonicalFile().equals(newEvents.getCanonicalFile())) return false;     // the same file: that is the running job's own
            FileOutputStream o = new FileOutputStream(newEvents, false);
            try { o.write(("RESULT {\"success\":false,\"busy\":true,\"error\":" + org.json.JSONObject.quote(BUSY) + "}\n").getBytes("UTF-8")); } finally { o.close(); }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether a Cancel stops the job that is going. A Cancel with no events file (the notification's button) is the person's and stops it. */
    static boolean cancelApplies(boolean running, File runningEvents, String requestedEvents) {
        if (requestedEvents == null || requestedEvents.isEmpty()) return true;
        return running && runningEvents != null && requestedEvents.equals(runningEvents.getAbsolutePath());
    }

    /** What a Cancel writes into the events file of the job it stops. */
    static final String CANCELLED_LINES = "LOG WARN Cancelled.\nRESULT {\"success\":false,\"cancelled\":true,\"error\":\"Cancelled\"}\n";

    /**
     * Opens the events file of a run for the engine's stream: emptied first, then in append mode. A plain stream keeps its own file offset, so lines that
     * Cancel appends through another descriptor would be overwritten by the engine's next line; in append mode both always write at the end.
     */
    static OutputStream openEvents(File events) throws IOException {
        new FileOutputStream(events, false).close();
        return new FileOutputStream(events, true);
    }

    /** Appends text to a file (the Cancel lines go to the events file of the running job this way). */
    static void append(File f, String s) {
        try {
            FileOutputStream o = new FileOutputStream(f, true);
            try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
        } catch (IOException ignored) {}
    }

    /**
     * Collects the bytes of the engine's output into lines and decodes each line as UTF-8 (the engine prints UTF-8, so a patch name with a curly quote
     * or a non-Latin letter stays whole). A line is kept up to 400 bytes; the rest of a longer one is dropped.
     */
    static final class LineDecoder {
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        private boolean cut;

        /** Feeds one byte; returns the finished line at a line break, otherwise null. */
        String feed(byte b) {
            if (b == '\n') {
                String l;
                try { l = new String(line.toByteArray(), "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { l = ""; }
                if (cut) { int n = l.length(); while (n > 0 && l.charAt(n - 1) == '\ufffd') n--; l = l.substring(0, n); }   // the cut may have split a character
                line.reset();
                cut = false;
                return l;
            }
            if (line.size() < 400) line.write(b); else cut = true;
            return null;
        }
    }

    /** The text the notification shows for a line of the engine, or null when the line is not one it shows. */
    static String noteText(String l) {
        String t = null;
        if (l.startsWith("STEP ")) t = l.substring(5);
        else if (l.startsWith("PATCH ")) t = l.substring(6);
        if (t == null) return null;
        return t.replace("OK ", "Applied: ").replace("FAIL ", "Failed: ");
    }
}
