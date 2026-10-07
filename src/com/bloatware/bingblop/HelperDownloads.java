package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * The downloads the in-app browser started, kept apart from the browser window: they go on when it is closed, can be paused, resumed (the rest is asked for, not the whole
 * file again), retried after a failure, cancelled and removed from the list, and the list (not the cookies) is written to a journal so a paused or broken download is still
 * there after the app was closed. At most three run at a time; the others wait. No Android classes: tested on a computer.
 */
public final class HelperDownloads {

    public interface Listener {
        /** A job changed (state at once, progress every 150 ms or so). Called from the downloading thread. */
        void onChange(BrowserDownload.Job job);
    }

    private static final int PARALLEL = 3;

    private final File journal;
    private final BrowserDownload.Cookies cookies;
    private final java.util.function.Predicate<File> folderOk;
    private final Map<String, BrowserDownload.Job> jobs = new LinkedHashMap<String, BrowserDownload.Job>();
    private final Map<String, Future<?>> futures = new java.util.HashMap<String, Future<?>>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final ExecutorService pool = Executors.newFixedThreadPool(PARALLEL, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "helper-download");
            t.setDaemon(true);
            return t;
        }
    });

    /** {@code folderOk} says which folders a journal entry may name (the Helper's two download folders): anything else in the journal is ignored. */
    public HelperDownloads(File journal, BrowserDownload.Cookies cookies, java.util.function.Predicate<File> folderOk) {
        this.journal = journal;
        this.cookies = cookies;
        this.folderOk = folderOk;
        load();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------------------------------------------------------------ the list

    public synchronized List<BrowserDownload.Job> list() {
        return new ArrayList<BrowserDownload.Job>(jobs.values());
    }

    public synchronized BrowserDownload.Job get(String id) {
        return jobs.get(id);
    }

    /**
     * Starts a download of {@code url} into {@code dir}. The same address again, while that job is not finished, is the same job (resumed if it was paused or broken), so a second tap
     * on a download button does not make a second copy.
     */
    public BrowserDownload.Job start(String url, String userAgent, String referer, File dir) {
        BrowserDownload.Job j;
        synchronized (this) {
            for (BrowserDownload.Job o : jobs.values()) {
                if (o.url.equals(url) && o.dir.equals(dir) && (o.state == BrowserDownload.State.RUNNING || o.state == BrowserDownload.State.QUEUED
                        || o.state == BrowserDownload.State.PAUSED || o.state == BrowserDownload.State.FAILED)) {
                    j = o;
                    if (o.state == BrowserDownload.State.PAUSED || o.state == BrowserDownload.State.FAILED) resume(o.id);
                    return j;
                }
            }
            j = new BrowserDownload.Job(UUID.randomUUID().toString().substring(0, 8), url, userAgent, referer, dir);
            jobs.put(j.id, j);
        }
        submit(j);
        return j;
    }

    private void submit(final BrowserDownload.Job j) {
        synchronized (this) {
            j.state = BrowserDownload.State.QUEUED;
            futures.put(j.id, pool.submit(new Runnable() {
                @Override
                public void run() {
                    BrowserDownload.run(j, cookies, new BrowserDownload.Listener() {
                        private BrowserDownload.State lastState;
                        @Override
                        public void onChange(BrowserDownload.Job job) {
                            boolean stateChanged = job.state != lastState;
                            lastState = job.state;
                            fire(job);
                            if (stateChanged && job.state != BrowserDownload.State.RUNNING) save();
                        }
                    });
                    synchronized (HelperDownloads.this) {
                        futures.remove(j.id);
                    }
                }
            }));
        }
        fire(j);
        save();
    }

    /** Pause: a running job stops after the bytes in hand, a waiting one leaves the queue. The part file stays. */
    public void pause(String id) {
        BrowserDownload.Job j = get(id);
        if (j == null) return;
        Future<?> f;
        synchronized (this) {
            f = futures.get(id);
        }
        if (j.state == BrowserDownload.State.QUEUED && f != null && f.cancel(false)) {
            synchronized (this) {
                futures.remove(id);
            }
            j.state = BrowserDownload.State.PAUSED;
            j.updated = System.currentTimeMillis();
            fire(j);
            save();
        } else if (j.state == BrowserDownload.State.RUNNING || j.state == BrowserDownload.State.QUEUED) {
            j.pause();
        }
    }

    /** Resume a paused job, or retry a failed one (the same thing: the rest is asked for, or the file starts over when the server cannot do that). */
    public void resume(String id) {
        BrowserDownload.Job j = get(id);
        if (j == null) return;
        if (j.state != BrowserDownload.State.PAUSED && j.state != BrowserDownload.State.FAILED) return;
        j.pauseRequested = false;
        j.cancelRequested = false;
        submit(j);
    }

    /** Stops the job and removes its part file; the entry stays in the list as Cancelled until it is removed. */
    public void cancel(String id) {
        BrowserDownload.Job j = get(id);
        if (j == null) return;
        if (j.state == BrowserDownload.State.RUNNING || j.state == BrowserDownload.State.QUEUED) {
            Future<?> f;
            synchronized (this) {
                f = futures.get(id);
            }
            j.cancel();
            if (j.state == BrowserDownload.State.QUEUED && f != null && f.cancel(false)) {
                synchronized (this) {
                    futures.remove(id);
                }
                finishCancelled(j);
            }
        } else if (j.state == BrowserDownload.State.PAUSED || j.state == BrowserDownload.State.FAILED) {
            finishCancelled(j);
        }
    }

    private void finishCancelled(BrowserDownload.Job j) {
        File p = j.part();
        if (p != null) p.delete();
        j.state = BrowserDownload.State.CANCELLED;
        j.updated = System.currentTimeMillis();
        fire(j);
        save();
    }

    /** Takes a job off the list. A job that is still going is cancelled first; a finished file is kept (it is the person's now). */
    public void remove(String id) {
        BrowserDownload.Job j = get(id);
        if (j == null) return;
        if (j.state == BrowserDownload.State.RUNNING || j.state == BrowserDownload.State.QUEUED || j.state == BrowserDownload.State.PAUSED || j.state == BrowserDownload.State.FAILED) cancel(id);
        synchronized (this) {
            jobs.remove(id);
        }
        save();
    }

    /** Takes every finished, failed-and-cancelled entry off the list (not the running or paused ones). Returns how many went. */
    public int clearFinished() {
        List<String> gone = new ArrayList<String>();
        for (BrowserDownload.Job j : list()) if (j.state == BrowserDownload.State.DONE || j.state == BrowserDownload.State.CANCELLED) gone.add(j.id);
        synchronized (this) {
            for (String id : gone) jobs.remove(id);
        }
        if (!gone.isEmpty()) save();
        return gone.size();
    }

    private void fire(BrowserDownload.Job j) {
        for (Listener l : listeners) {
            try {
                l.onChange(j);
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------ journal

    public static JSONObject toJson(BrowserDownload.Job j) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", j.id).put("url", j.url).put("userAgent", j.userAgent).put("referer", j.referer).put("dir", j.dir.getAbsolutePath())
                    .put("name", j.name).put("resolvedUrl", j.resolvedUrl).put("etag", j.etag).put("lastModified", j.lastModified)
                    .put("done", j.done).put("total", j.total).put("state", j.state.name()).put("error", j.error).put("mime", j.mime)
                    .put("file", j.file == null ? "" : j.file.getAbsolutePath()).put("updated", j.updated);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** What the page shows: no address with a key in it, just the host. */
    public static JSONObject forPage(BrowserDownload.Job j) {
        JSONObject o = new JSONObject();
        try {
            String host = "";
            try {
                host = new java.net.URI(j.url).getHost();
            } catch (Exception ignored) {
            }
            o.put("id", j.id).put("name", j.file != null ? j.file.getName() : j.name).put("host", host == null ? "" : host)
                    .put("state", j.state.name().toLowerCase(java.util.Locale.ROOT)).put("done", j.done).put("total", j.total)
                    .put("error", j.error).put("updated", j.updated).put("canUse", j.state == BrowserDownload.State.DONE && j.file != null && j.file.isFile());
        } catch (JSONException ignored) {
        }
        return o;
    }

    public JSONArray pageList() {
        JSONArray a = new JSONArray();
        for (BrowserDownload.Job j : list()) a.put(forPage(j));
        return a;
    }

    private synchronized void save() {
        if (journal == null) return;
        try {
            JSONArray a = new JSONArray();
            for (BrowserDownload.Job j : jobs.values()) a.put(toJson(j));
            File parent = journal.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(journal.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(journal)) {
                journal.delete();
                tmp.renameTo(journal);
            }
        } catch (IOException ignored) {
        }
    }

    /** Brings back the list: what was running or waiting is Paused (its part file is there), a finished file that is gone is dropped, a part that is gone makes the job start over. */
    private void load() {
        if (journal == null || !journal.isFile()) return;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                File dir = new File(o.optString("dir"));
                if (folderOk != null && !folderOk.test(dir)) continue;
                String url = o.optString("url");
                if (!(url.startsWith("https://") || url.startsWith("http://"))) continue;
                BrowserDownload.Job j = new BrowserDownload.Job(o.optString("id", UUID.randomUUID().toString().substring(0, 8)), url, o.optString("userAgent"), o.optString("referer"), dir);
                String name = o.optString("name");
                if (!name.isEmpty() && name.equals(new File(name).getName())) j.name = name;      // a name is a name, never a path
                j.resolvedUrl = o.optString("resolvedUrl");
                j.etag = o.optString("etag");
                j.lastModified = o.optString("lastModified");
                j.total = o.optLong("total", -1);
                j.mime = o.optString("mime");
                j.updated = o.optLong("updated", System.currentTimeMillis());
                j.error = o.optString("error");
                String st = o.optString("state", "PAUSED");
                BrowserDownload.State s;
                try {
                    s = BrowserDownload.State.valueOf(st);
                } catch (IllegalArgumentException e) {
                    s = BrowserDownload.State.PAUSED;
                }
                if (s == BrowserDownload.State.DONE) {
                    File f = new File(o.optString("file"));
                    if (!f.isFile() || !f.getParentFile().equals(dir)) continue;
                    j.file = f;
                    j.done = f.length();
                    j.total = f.length();
                } else {
                    File p = j.part();
                    long have = p != null && p.isFile() ? p.length() : 0;
                    j.done = have;
                    if (s == BrowserDownload.State.RUNNING || s == BrowserDownload.State.QUEUED) {
                        s = BrowserDownload.State.PAUSED;
                    }
                    if (s == BrowserDownload.State.PAUSED && have == 0 && j.name.isEmpty()) j.error = "";
                }
                j.state = s;
                jobs.put(j.id, j);
            }
        } catch (Exception ignored) {
        }
    }

    /** Stops what is running (the part files stay) and ends the threads: used when the app goes away. */
    public void shutdown() {
        for (BrowserDownload.Job j : list()) if (j.state == BrowserDownload.State.RUNNING || j.state == BrowserDownload.State.QUEUED) j.pause();
        pool.shutdown();
    }
}
