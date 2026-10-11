package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Runs one engine command at a time in the Morphe service and follows the events file it writes. The service itself is behind {@link Launcher}
 * (the bridge gives it the real MorpheService; a test gives it a fake), so the rules around it can be tested on a computer.
 *
 * The job file and the events file of a run belong to the run that holds the engine: a second caller waits for the engine <em>before</em> it
 * touches either file. (It used to write the job file and delete the events file first, which cut the file out from under a run in progress.)
 */
public final class MorpheRunner {
    /** The patcher service, as far as a run needs it. */
    public interface Launcher {
        /** Starts the engine command in the service. False when the system refused. */
        boolean start(String cmd, File job, File events, String title);
        /** Stops the service (and the engine in it). */
        void cancel();
        /** Whether the service process is still there. */
        boolean alive();
    }

    public interface EventSink { void accept(JSONObject e); }

    /** The waiting times of a run; the app uses {@link #DEFAULT}, a test shortens them. */
    public static final class Timing {
        public final long poll, quietPoll, quietTries, diedAfter, diedQuiet, cancelQuiet;
        public Timing(long poll, long quietPoll, long quietTries, long diedAfter, long diedQuiet, long cancelQuiet) {
            this.poll = poll; this.quietPoll = quietPoll; this.quietTries = quietTries; this.diedAfter = diedAfter; this.diedQuiet = diedQuiet; this.cancelQuiet = cancelQuiet;
        }
    }
    public static final Timing DEFAULT = new Timing(150, 200, 30, 10000, 3000, 4000);

    private final Launcher launcher;
    private final Timing timing;
    private final Semaphore engine = new Semaphore(1);
    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<String, Boolean>();
    private volatile String serviceJob = "";                                           // the job whose engine command the service has been started for
    private volatile String stopSentFor = "";                                          // the job the service was already told to stop

    public MorpheRunner(Launcher launcher) { this(launcher, DEFAULT); }

    public MorpheRunner(Launcher launcher, Timing timing) {
        this.launcher = launcher;
        this.timing = timing;
    }

    /** The page pressed Stop for this run. */
    public void markCancelled(String job) { if (job != null) cancelled.put(job, Boolean.TRUE); }

    /**
     * Stop for {@code job}: remembered, so a run that has not started its engine yet never starts it, and passed to the service when that is
     * running this job's command. A Stop for any other job changes nothing. Returns whether the job was marked.
     */
    public boolean cancel(String job) {
        if (job == null || job.isEmpty()) return false;
        markCancelled(job);
        stopService(job);
        return true;
    }

    /** Tells the service to stop, once per job, and only when it was started for that job. */
    private void stopService(String job) {
        if (!job.equals(serviceJob)) return;
        synchronized (this) {
            if (job.equals(stopSentFor)) return;
            stopSentFor = job;
        }
        launcher.cancel();
    }

    public void clearCancelled(String job) { if (job != null) cancelled.remove(job); }

    public boolean isCancelled(String job) { return job != null && cancelled.containsKey(job); }

    /** Waits (a few seconds) for the process of an earlier job to end, so the new one does not start on a service that is about to be stopped. */
    private void waitForQuiet() throws InterruptedException {
        for (int i = 0; i < timing.quietTries && launcher.alive(); i++) Thread.sleep(timing.quietPoll);
    }

    /** Runs one engine command and follows its events. Returns the RESULT of the engine (a synthetic failure when it died). */
    public JSONObject run(String cmd, JSONObject job, File dir, String title, String jobId, EventSink sink, long timeoutMs) throws Exception {
        engine.acquire();                                                                  // first: the files below belong to the run that holds the engine
        try {
            if (jobId != null && cancelled.containsKey(jobId)) return new JSONObject().put("success", false).put("cancelled", true).put("error", "Cancelled");   // Stop was pressed before the engine started: do not start it
            dir.mkdirs();
            File jobFile = new File(dir, "job.json");
            File events = new File(dir, "events.log");
            writeText(jobFile, job.toString());
            events.delete();
            waitForQuiet();
            if (!launcher.start(cmd, jobFile, events, title)) throw new IOException("Android would not start the patcher service. Open the app and try again.");
            serviceJob = jobId == null ? "" : jobId;
            if (jobId != null && cancelled.containsKey(jobId)) stopService(jobId);            // Stop came in while the service was being started
            MorpheEvents.Tail tail = new MorpheEvents.Tail(events);
            long started = System.currentTimeMillis(), lastData = started;
            StringBuilder lastLog = new StringBuilder();
            while (true) {
                List<JSONObject> got = tail.poll();
                for (JSONObject e : got) {
                    if ("log".equals(e.optString("t"))) { lastLog.append(e.optString("text")).append('\n'); if (lastLog.length() > 4000) lastLog.delete(0, lastLog.length() - 3000); }
                    if (!"result".equals(e.optString("t")) && sink != null) sink.accept(e);
                }
                if (tail.sawResult()) return tail.result();
                long now = System.currentTimeMillis();
                if (!got.isEmpty()) lastData = now;
                if (jobId != null && cancelled.containsKey(jobId)) {
                    stopService(jobId);                                                    // the engine keeps patching unless the service is told to stop
                    if (now - lastData > timing.cancelQuiet) return new JSONObject().put("success", false).put("cancelled", true).put("error", "Cancelled");
                }
                if (!launcher.alive() && now - started > timing.diedAfter && now - lastData > timing.diedQuiet) {
                    tail.poll();
                    if (tail.sawResult()) return tail.result();
                    return new JSONObject().put("success", false).put("error", MorpheJobs.diedMessage(lastLog.toString()));
                }
                if (now - started > timeoutMs) {
                    launcher.cancel();
                    return new JSONObject().put("success", false).put("error", "The patcher took too long and was stopped.");
                }
                Thread.sleep(timing.poll);
            }
        } finally {
            serviceJob = "";
            stopSentFor = "";
            engine.release();
        }
    }

    private static void writeText(File f, String s) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }
}
