package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Two decisions of the patcher service that need no Android classes, so they can be tested on a computer: what happens to a RUN that arrives while
 * a job is going, and which Cancel may stop the job that is going.
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
}
