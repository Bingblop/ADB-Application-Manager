package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What a patch run leaves in {@code jobs/<id>} when the app was killed before it filed the result. The engine runs in a process of its own and
 * can finish after the app process died; its patched APK then sits in the run's folder, and nothing looked at it again. {@link #scan} sorts the
 * folders into runs that finished (their APK can still be filed), folders that are only debris (a failed run, or one that never finished and
 * has not been touched for a long time) and everything else, which is left alone.
 */
public final class MorpheOrphans {
    private MorpheOrphans() {}

    /** The folder of the catalog read (jobs/list): it belongs to the run that reads the patches and is never a patch run. */
    static final String CATALOG = "list";

    /** A run that finished with success and left its APK. */
    public static final class Orphan {
        public final File dir, apk;
        public final JSONObject request, result;
        Orphan(File dir, File apk, JSONObject request, JSONObject result) { this.dir = dir; this.apk = apk; this.request = request; this.result = result; }
    }

    public static final class Scan {
        public final List<Orphan> finished = new ArrayList<Orphan>();
        public final List<File> debris = new ArrayList<File>();
    }

    /**
     * @param jobsDir    the folder with one subfolder per run
     * @param runningJob the run that is going now (never touched); may be empty
     * @param now        the time, in milliseconds
     * @param staleMs    how long a folder with no answer must have been untouched before it is debris
     */
    public static Scan scan(File jobsDir, String runningJob, long now, long staleMs) {
        Scan out = new Scan();
        File[] kids = jobsDir.listFiles();
        if (kids == null) return out;
        for (File d : kids) {
            if (!d.isDirectory() || !MorpheJobs.validJobId(d.getName()) || d.getName().equals(runningJob) || d.getName().equals(CATALOG)) continue;
            File events = new File(d, "events.log");
            File apk = new File(d, "patched.apk");
            JSONObject result = lastResult(events);
            if (result != null) {
                if (result.optBoolean("success") && apk.isFile()) out.finished.add(new Orphan(d, apk, request(d, result), result));
                else out.debris.add(d);                                                              // a failed run, or a success whose APK is gone: nothing to file
                continue;
            }
            long newest = Math.max(d.lastModified(), Math.max(events.lastModified(), new File(d, "job.json").lastModified()));
            if (now - newest > staleMs) out.debris.add(d);                                          // never finished and untouched for long: the engine is not going to answer
        }
        return out;
    }

    /** The RESULT line of an events file, or null when the run has not answered. */
    static JSONObject lastResult(File events) {
        if (!events.isFile()) return null;
        try {
            MorpheEvents.Tail t = new MorpheEvents.Tail(events);
            t.poll();
            return t.sawResult() ? t.result() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** What the page asked for (saved with the run), or, for a run that left no request, what the engine itself reported. */
    static JSONObject request(File dir, JSONObject result) {
        File f = new File(dir, "request.json");
        if (f.isFile()) {
            try {
                InputStream in = new FileInputStream(f);
                try {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    return new JSONObject(new String(b.toByteArray(), "UTF-8"));
                } finally { in.close(); }
            } catch (Exception ignored) { /* fall through to what the engine said */ }
        }
        try {
            String pkg = result.optString("pkg");
            return new JSONObject().put("pkg", pkg).put("name", pkg).put("version", result.optString("versionName")).put("versionCode", result.optString("versionCode"))
                    .put("keepIn", "app").put("job", dir.getName());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
