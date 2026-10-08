package com.bloatware.bingblop;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the notification of the Helper downloads says (see {@link DownloadService}): how many are going, how far they are, how fast, how long is left,
 * and, when the last one is over, what became of them. Fed with the job list every second or so. No Android classes: tested on a computer.
 */
public final class DownloadNotice {

    /** One look at the list. */
    public static final class Snapshot {
        public int running, queued;
        public long done, total;           // bytes of the running jobs; total counts only the sizes that are known
        public boolean totalKnown;         // every running job has told its size
        public int percent = -1;           // -1 when that cannot be said
        public long speed;                 // bytes per second, smoothed
        public long etaSec = -1;
        public String title = "", text = "";

        public boolean active() { return running + queued > 0; }
    }

    private final Map<String, Long> lastDone = new HashMap<String, Long>();
    private final Set<String> seenActive = new HashSet<String>();
    private final Set<String> counted = new HashSet<String>();
    private long accum, windowStart;
    private double speed;
    private int finished, failed;
    private String lastName = "";

    /** Reads the list. {@code now} is in milliseconds. */
    public synchronized Snapshot update(List<BrowserDownload.Job> jobs, long now) {
        Snapshot s = new Snapshot();
        s.totalKnown = true;
        String firstName = "";
        for (BrowserDownload.Job j : jobs) {
            BrowserDownload.State st = j.state;
            if (st == BrowserDownload.State.RUNNING) {
                s.running++;
                s.done += j.done;
                if (j.total > 0) s.total += j.total; else s.totalKnown = false;
                Long prev = lastDone.get(j.id);
                if (prev != null && j.done > prev) accum += j.done - prev;
                lastDone.put(j.id, j.done);
                seenActive.add(j.id);
                if (firstName.isEmpty()) firstName = j.name;
            } else if (st == BrowserDownload.State.QUEUED) {
                s.queued++;
                seenActive.add(j.id);
                if (firstName.isEmpty()) firstName = j.name;
            } else {
                lastDone.remove(j.id);
                if (seenActive.contains(j.id) && counted.add(j.id)) {
                    if (st == BrowserDownload.State.DONE) { finished++; if (!j.name.isEmpty()) lastName = j.name; }
                    else if (st == BrowserDownload.State.FAILED) failed++;
                }
            }
        }
        if (windowStart == 0) windowStart = now;
        long dt = now - windowStart;
        if (dt >= 1000) {
            double inst = accum * 1000.0 / dt;
            speed = speed > 0 ? speed * 0.6 + inst * 0.4 : inst;
            accum = 0; windowStart = now;
        }
        s.speed = s.running > 0 ? Math.round(speed) : 0;
        if (s.running > 0 && s.totalKnown && s.total > 0) s.percent = (int) Math.min(100, s.done * 100 / s.total);
        if (s.running > 0 && s.totalKnown && s.speed > 0 && s.total > s.done) s.etaSec = (s.total - s.done) / s.speed;
        int all = s.running + s.queued;
        if (all == 1 && !firstName.isEmpty()) s.title = "Downloading " + firstName;
        else s.title = all == 1 ? "Downloading a file" : "Downloading " + all + " files";
        StringBuilder t = new StringBuilder();
        if (s.running == 0) t.append("Waiting for a free place");
        else {
            if (s.percent >= 0) t.append(s.percent).append("% · ").append(bytes(s.done)).append(" of ").append(bytes(s.total));
            else t.append(bytes(s.done));
            if (s.speed > 0) t.append(" · ").append(bytes(s.speed)).append("/s");
            if (s.etaSec >= 0) t.append(" · ").append(eta(s.etaSec)).append(" left");
        }
        if (s.queued > 0 && s.running > 0) t.append(" · ").append(s.queued).append(" waiting");
        s.text = t.toString();
        return s;
    }

    /** What to say once nothing is going on any more, or null when nothing was started and finished. */
    public synchronized String summary() {
        if (finished == 0 && failed == 0) return null;
        if (failed == 0) return finished == 1 ? (lastName.isEmpty() ? "Download finished" : lastName + " is saved") : finished + " downloads finished";
        if (finished == 0) return failed == 1 ? "A download stopped. Open the app to retry it" : failed + " downloads stopped. Open the app to retry them";
        return finished + " finished, " + failed + " stopped. Open the app to retry";
    }

    /** True when the last summary was about a failure (it is worth a louder note). */
    public synchronized boolean hadFailure() { return failed > 0; }

    /** A new round: forget what the last one counted. */
    public synchronized void reset() {
        lastDone.clear(); seenActive.clear(); counted.clear();
        accum = 0; windowStart = 0; speed = 0; finished = 0; failed = 0; lastName = "";
    }

    public static String bytes(long n) {
        if (n >= 1073741824L) return String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0);
        if (n >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        if (n >= 1024L) return Math.round(n / 1024.0) + " KB";
        return n + " B";
    }

    public static String eta(long sec) {
        if (sec >= 3600) return (sec / 3600) + " h " + (sec % 3600 / 60) + " min";
        if (sec >= 60) return (sec / 60) + " min " + (sec % 60) + " s";
        return sec + " s";
    }
}
