package com.bloatware.bingblop;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * The Morphe Patcher tab's side of the page (window.AndroidBridge.morphe(tag, op, argsJson)): the patch sources and the community finder
 * (MorpheStore), the APK downloads and VirusTotal (MorpheHelper, MorpheVirusTotal), the patched APKs (MorpheLibrary), and the patch run itself,
 * which the engine does in MorpheService (a process of its own). Every call answers once through window.onMorphe({tag, ok, data | error});
 * a run also reports window.onMorpheEvent(...). The activity gives what only it can do through {@link Host}.
 */
public final class MorpheBridge {
    /** What the bridge needs from the activity. */
    public interface Host {
        Context context();
        /** Runs a script on the page (any thread). */
        void js(String script);
        /** Opens the system file picker; the answer is given to {@link #pickDone}. */
        boolean pick(String tag, String kind);
        /** Installs an APK through the working mode: {ok, output, advice, retry}. */
        JSONObject install(File apk, String pkg, boolean uninstallFirst) throws Exception;
        void share(File f, String mime);
        boolean privileged();
        /** "both", "wifi" or "mobile": whether the network now fits the choice. */
        boolean connectionOk(String conn);
        File downloadsDir();
        /** Whether this app may read shared storage (All files access): without it the Downloads folder looks empty to it. */
        boolean storageAccess();
        /** Opens the in-app browser at {@code url}; downloads go to {@code dir} and are reported to {@code events}. Returns a way to close it. */
        Runnable openBrowser(String url, File dir, HelperDownloads downloads, BrowserDownload.Events events);
        /** The cookies the in-app browser holds for an address (the Helper's downloads send them like the browser would). */
        BrowserDownload.Cookies cookies();
    }

    private static final String ENGINE_CLASS = "com.bloatware.bingblop.morphe.EngineMain";
    private static final Object NO_REPLY = new Object();

    private final Host host;
    private final File base;
    private final MorpheStore store;
    private final MorpheLibrary library;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Semaphore engine = new Semaphore(1);
    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<String, Boolean>();
    private volatile String runningJob = "";

    public MorpheBridge(Host host, File base) {
        this.host = host;
        this.base = base;
        base.mkdirs();
        this.store = new MorpheStore(new File(base, "sources"));
        this.library = new MorpheLibrary(new File(base, "patched"));
    }

    // ------------------------------------------------------------------------------------------------------------------------ calls

    public void call(final String tag, final String op, final String argsJson) {
        try {
            pool.submit(new Runnable() {
                @Override public void run() { handle(tag, op, argsJson); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            reply(tag, false, null, "the app is closing");
        }
    }

    private void handle(String tag, String op, String argsJson) {
        try {
            JSONObject a = argsJson == null || argsJson.isEmpty() ? new JSONObject() : new JSONObject(argsJson);
            Object data = dispatch(tag, op, a);
            if (data == NO_REPLY) return;
            reply(tag, true, data == null ? new JSONObject() : data, null);
        } catch (Throwable t) {
            String m = t.getMessage();
            String browse = t instanceof MorpheHelper.NeedsBrowser ? ((MorpheHelper.NeedsBrowser) t).page : null;
            reply(tag, false, null, m == null || m.isEmpty() ? t.getClass().getSimpleName() : m, browse);
        }
    }

    private void reply(String tag, boolean ok, Object data, String error) {
        reply(tag, ok, data, error, null);
    }

    /** {@code browse}: the page a source that needs a browser should be opened at (the page then offers to open it in the in-app browser). */
    private void reply(String tag, boolean ok, Object data, String error, String browse) {
        try {
            JSONObject r = new JSONObject();
            r.put("tag", tag);
            r.put("ok", ok);
            if (browse != null && !browse.isEmpty()) r.put("browse", browse);
            if (ok) r.put("data", data); else r.put("error", error == null ? "" : error);
            host.js("window.onMorphe && window.onMorphe(" + literal(r.toString()) + ")");
        } catch (JSONException ignored) {}
    }

    private void event(JSONObject e) {
        host.js("window.onMorpheEvent && window.onMorpheEvent(" + literal(e.toString()) + ")");
    }

    private static String literal(String json) {
        return json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private Object dispatch(String tag, String op, JSONObject a) throws Exception {
        switch (op) {
            case "info": return info();
            case "sources": return new JSONObject().put("sources", store.list());
            case "sourceAdd": return new JSONObject().put("source", store.addRemote(a.optString("input"), a.optString("name"), a.optBoolean("prerelease"), null));
            case "sourceAddLocal": {
                File f = new File(a.optString("path"));
                if (!MorpheJobs.inside(f, allowedRoots())) throw new IOException("that file is not one this app may read");
                return new JSONObject().put("source", store.addLocal(f, a.optString("name")));
            }
            case "sourceUpdate": return new JSONObject().put("source", store.update(a.optString("id"), null));
            case "sourceCheck": return store.checkUpdate(a.optString("id"));
            case "sourceRemove": store.remove(a.optString("id")); return null;
            case "sourceRename": store.rename(a.optString("id"), a.optString("name")); return null;
            case "sourceEnable": store.setEnabled(a.optString("id"), a.optBoolean("on")); return null;
            case "sourcePre": store.setPrerelease(a.optString("id"), a.optBoolean("on")); return null;
            case "community": return store.community(a.optBoolean("refresh"), null);
            case "catalog": return catalog(a.optJSONArray("ids"), a.optBoolean("force"));
            case "apkInstalled": return apkInstalled(a.optString("pkg"));
            case "apkInspect": return apkInspect(a.optString("path"));
            case "pick": return pick(tag, a.optString("kind", "any"));
            case "patch": return startPatch(a);
            case "cancel": cancelled.put(a.optString("job"), Boolean.TRUE); MorpheService.cancel(host.context()); return null;
            case "patchedList": return new JSONObject().put("items", library.list());
            case "patchedDelete": library.delete(a.optString("id")); return null;
            case "patchedExport": return patchedExport(a.optString("id"));
            case "patchedLog": return new JSONObject().put("text", library.readLog(a.optString("id"), 400 * 1024));
            case "patchedShare": {
                JSONObject m = library.get(a.optString("id"));
                if (m == null) throw new IOException("no such patched APK");
                host.share(new File(m.optString("file")), "application/vnd.android.package-archive");
                return null;
            }
            case "shareFile": {
                File f = new File(a.optString("path"));
                if (!MorpheJobs.inside(f, allowedRoots())) throw new IOException("that file is not one this app may share");
                host.share(f, "application/vnd.android.package-archive");
                return null;
            }
            case "install": return install(a);
            case "helperSources": return new JSONObject().put("sources", MorpheHelper.sources()).put("defaults", MorpheHelper.settingsDefaults());
            case "helperManual": return new JSONObject().put("url", MorpheHelper.manualUrl(a.optString("source"), a.optString("pkg"), a.optString("version")));
            case "helperVersions": return MorpheHelper.versions(a.optString("source"), a.optString("pkg"));
            case "helperBrowse": return helperBrowse(a);
            case "helperDownloadList": return new JSONObject().put("jobs", downloads().pageList());
            case "helperDownloadOp": return helperDownloadOp(a);
            case "helperBrowseClose": if (browserCloser != null) { final Runnable c = browserCloser; browserCloser = null; c.run(); } return null;
            case "helperDownloads": return helperDownloads(a);
            case "helperAdopt": return helperAdopt(a);
            case "helperGet": return helperGet(a, false);
            case "helperFast": return helperGet(a, true);
            case "vtQuota": return new MorpheVirusTotal("", new File(base, "vt_state.json")).quota();
            case "vtValidate": MorpheVirusTotal.validateKey(a.optString("key")); return null;
            case "vtScan": return vtScan(a);
            case "keyExport": return keyExport();
            case "keyImport": return keyImport(a);
            case "keyReset": new File(base, "morphe.keystore").delete(); new File(base, "morphe_key.json").delete(); return null;
            default: throw new IOException("unknown operation: " + op);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------ small things

    private boolean engineThere() {
        try { Class.forName(ENGINE_CLASS); return true; } catch (Throwable t) { return false; }
    }

    private JSONObject info() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("engine", engineThere());
        o.put("patcher", MorpheStore.ENGINE_PATCHER_VERSION);
        o.put("maxMemoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        o.put("downloads", new File(host.downloadsDir(), "Morphe Patcher").getAbsolutePath());
        return o;
    }

    private List<File> allowedRoots() {
        List<File> r = new ArrayList<File>();
        Context c = host.context();
        r.add(new File(c.getCacheDir(), "morphe_pick"));
        r.add(base);
        r.add(new File(host.downloadsDir(), "Morphe Patcher"));
        r.add(new File(host.downloadsDir(), "Helper for Morphe"));
        return r;
    }

    private File helperDir(String save) {
        return "downloads".equals(save) ? new File(host.downloadsDir(), "Helper for Morphe") : new File(base, "helper");
    }

    private JSONObject apkInstalled(String pkg) throws JSONException {
        JSONObject o = new JSONObject();
        try {
            PackageManager pm = host.context().getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            JSONArray paths = new JSONArray();
            paths.put(ai.sourceDir);
            if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) paths.put(s);
            CharSequence label = pm.getApplicationLabel(ai);
            o.put("ok", true);
            o.put("pkg", pkg);
            o.put("label", label == null ? pkg : label.toString());
            o.put("versionName", pi.versionName == null ? "" : pi.versionName);
            o.put("versionCode", Build.VERSION.SDK_INT >= 28 ? String.valueOf(pi.getLongVersionCode()) : String.valueOf(pi.versionCode));
            o.put("paths", paths);
            o.put("splits", paths.length() - 1);
        } catch (PackageManager.NameNotFoundException e) {
            o.put("ok", false);
        }
        return o;
    }

    private JSONObject apkInspect(String path) throws Exception {
        File f = new File(path);
        if (!MorpheJobs.inside(f, allowedRoots())) throw new IOException("that file is not one this app may read");
        JSONObject r = MorpheHelper.inspect(f);
        if (!r.optBoolean("ok")) throw new IOException(r.optString("error", "That is not an APK, APKS, APKM or XAPK file"));
        try {
            PackageManager pm = host.context().getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(r.optString("pkg"), 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null) r.put("label", l.toString());
        } catch (Exception ignored) {}
        r.put("fileName", f.getName());
        return r;
    }

    private Object pick(String tag, String kind) {
        if (!host.pick(tag, kind)) throw new IllegalStateException("the file picker could not be opened");
        return NO_REPLY;
    }

    /** The activity has the picked files (copied into this app's cache): answers the page's pick call. */
    public void pickDone(String tag, JSONArray files) {
        try { reply(tag, true, new JSONObject().put("files", files), null); } catch (JSONException ignored) {}
    }

    // ------------------------------------------------------------------------------------------------------------------------ the engine

    private boolean serviceAlive() {
        try {
            ActivityManager am = (ActivityManager) host.context().getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return true;
            String want = host.context().getPackageName() + ":morphe";
            for (ActivityManager.RunningAppProcessInfo p : ps) if (want.equals(p.processName)) return true;
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** Waits (a few seconds) for the process of an earlier job to end, so the new one does not start on a service that is about to be stopped. */
    private void waitForQuiet() throws InterruptedException {
        for (int i = 0; i < 30 && serviceAlive(); i++) Thread.sleep(200);
    }

    interface EventSink { void accept(JSONObject e); }

    /** Runs one engine command in the service and follows its events file. Returns the RESULT of the engine (a synthetic failure when it died). */
    private JSONObject runEngine(String cmd, JSONObject job, File dir, String title, String jobId, EventSink sink, long timeoutMs) throws Exception {
        dir.mkdirs();
        File jobFile = new File(dir, "job.json");
        File events = new File(dir, "events.log");
        writeText(jobFile, job.toString());
        events.delete();
        engine.acquire();
        try {
            waitForQuiet();
            if (!MorpheService.start(host.context(), cmd, jobFile, events, title)) throw new IOException("Android would not start the patcher service. Open the app and try again.");
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
                if (jobId != null && cancelled.containsKey(jobId) && now - lastData > 4000) {
                    return new JSONObject().put("success", false).put("cancelled", true).put("error", "Cancelled");
                }
                if (!serviceAlive() && now - started > 10000 && now - lastData > 3000) {
                    tail.poll();
                    if (tail.sawResult()) return tail.result();
                    return new JSONObject().put("success", false).put("error", MorpheJobs.diedMessage(lastLog.toString()));
                }
                if (now - started > timeoutMs) {
                    MorpheService.cancel(host.context());
                    return new JSONObject().put("success", false).put("error", "The patcher took too long and was stopped.");
                }
                Thread.sleep(150);
            }
        } finally {
            engine.release();
        }
    }

    private static void writeText(File f, String s) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    private static String readText(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } finally { in.close(); }
    }

    // ------------------------------------------------------------------------------------------------------------------------ the patches of the sources

    /** The patches of the downloaded sources, read by the engine once per bundle version and kept in a file. */
    private JSONObject catalog(JSONArray ids, boolean force) throws Exception {
        JSONObject out = new JSONObject();
        File cacheDir = new File(base, "catalog");
        cacheDir.mkdirs();
        List<String> need = new ArrayList<String>();
        List<File> needFiles = new ArrayList<File>();
        List<File> needCache = new ArrayList<File>();
        if (ids == null) ids = new JSONArray();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            File mpp = store.bundleFile(id);
            JSONObject s = store.get(id);
            if (mpp == null || s == null) continue;
            File cache = new File(cacheDir, MorpheJobs.safeName(id) + "-" + MorpheJobs.safeName(s.optString("version")) + "-" + mpp.length() + ".json");
            if (!force && cache.isFile()) {
                try { out.put(id, new JSONObject(readText(cache))); continue; } catch (Exception bad) { cache.delete(); }
            }
            need.add(id); needFiles.add(mpp); needCache.add(cache);
        }
        if (need.isEmpty()) return new JSONObject().put("catalogs", out);
        if (!engineThere()) {
            for (String id : need) out.put(id, new JSONObject().put("ok", false).put("error", "This build of the app does not contain the Morphe engine").put("patches", new JSONArray()));
            return new JSONObject().put("catalogs", out);
        }
        File dir = new File(base, "jobs/list");
        File answer = new File(dir, "out.json");
        dir.mkdirs();
        answer.delete();
        JSONObject res = runEngine("list", MorpheJobs.listJob(needFiles, answer), dir, "Reading Morphe patches", null, null, 240000);
        if (!res.optBoolean("success") || !answer.isFile()) {
            String err = res.optString("error", "The patch engine could not read the patches");
            for (String id : need) out.put(id, new JSONObject().put("ok", false).put("error", err).put("patches", new JSONArray()));
            return new JSONObject().put("catalogs", out);
        }
        JSONArray bundles = new JSONObject(readText(answer)).getJSONArray("bundles");
        for (int i = 0; i < bundles.length() && i < need.size(); i++) {
            JSONObject b = bundles.getJSONObject(i);
            JSONObject c = new JSONObject();
            c.put("ok", b.optBoolean("ok"));
            c.put("patches", b.optJSONArray("patches") == null ? new JSONArray() : b.getJSONArray("patches"));
            if (!b.optBoolean("ok")) c.put("error", b.optString("error", "The patches could not be read"));
            out.put(need.get(i), c);
            if (b.optBoolean("ok")) {
                try { writeText(needCache.get(i), c.toString()); } catch (IOException ignored) {}
                store.setPatchCount(need.get(i), c.getJSONArray("patches").length());
            }
        }
        answer.delete();
        return new JSONObject().put("catalogs", out);
    }

    // ------------------------------------------------------------------------------------------------------------------------ patching

    private JSONObject startPatch(final JSONObject a) throws Exception {
        if (!engineThere()) throw new IOException("This build of the app does not contain the Morphe engine.");
        final String jobId = a.optString("job");
        if (jobId.isEmpty()) throw new IOException("no job id");
        if (!runningJob.isEmpty()) throw new IOException("A patch is already running.");
        runningJob = jobId;
        cancelled.remove(jobId);
        pool.submit(new Runnable() {
            @Override public void run() {
                try { patch(a, jobId); } finally { runningJob = ""; cancelled.remove(jobId); }
            }
        });
        return new JSONObject().put("job", jobId);
    }

    private void ev(String jobId, JSONObject e) {
        try { e.put("job", jobId); e.put("ts", System.currentTimeMillis()); } catch (JSONException ignored) {}
        event(e);
    }

    private void step(String jobId, String name, String state) throws JSONException {
        ev(jobId, new JSONObject().put("t", "step").put("name", name).put("state", state));
    }

    private void note(String jobId, String level, String text) throws JSONException {
        ev(jobId, new JSONObject().put("t", "log").put("level", level).put("text", text));
    }

    private void patch(JSONObject a, final String jobId) {
        File dir = new File(base, "jobs/" + MorpheJobs.safeName(jobId));
        JSONObject result = null;
        JSONObject saved = null;
        try {
            deleteTree(dir);
            dir.mkdirs();
            step(jobId, "Preparing", "RUNNING");
            File input = prepareInput(a, dir, jobId);
            File output = new File(dir, "patched.apk");
            File keystore = new File(base, "morphe.keystore");
            JSONObject keyInfo = null;
            File ki = new File(base, "morphe_key.json");
            if (ki.isFile()) try { keyInfo = new JSONObject(readText(ki)); } catch (Exception ignored) {}
            JSONObject job = MorpheJobs.patchJob(a, input, output, new File(dir, "work"), new MorpheJobs.Bundles() {
                @Override public File fileOf(String id) { return store.bundleFile(id); }
            }, keystore, keyInfo, MorpheJobs.abiName(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : ""));
            step(jobId, "Preparing", "OK");
            final JSONObject engineResult = runEngine("patch", job, dir, "Patching " + a.optString("name", a.optString("pkg")), jobId, new EventSink() {
                @Override public void accept(JSONObject e) { ev(jobId, e); }
            }, 45L * 60 * 1000);
            result = engineResult;
            if (engineResult.optBoolean("success") && output.isFile()) {
                step(jobId, "Saving", "RUNNING");
                saved = save(a, output, dir, engineResult);
                ev(jobId, new JSONObject().put("t", "saved").put("item", saved));
                step(jobId, "Saving", "OK");
                if (a.optBoolean("installAfter")) {
                    step(jobId, "Installing", "RUNNING");
                    JSONObject ir;
                    try { ir = host.install(new File(saved.optString("file")), a.optString("pkg"), false); }
                    catch (Exception e) { ir = new JSONObject().put("ok", false).put("output", "Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
                    ir.put("t", "install");
                    ev(jobId, ir);
                    step(jobId, "Installing", ir.optBoolean("ok") ? "OK" : "FAIL");
                    if (ir.optBoolean("ok")) {
                        markInstalled(saved.optString("id"));
                        if (a.optBoolean("deleteAfter")) {
                            library.delete(saved.optString("id"));
                            ev(jobId, new JSONObject().put("t", "deleted"));
                        }
                    }
                }
                result.put("item", saved);
            } else if (engineResult.optBoolean("success")) {
                result = new JSONObject().put("success", false).put("error", "The engine said it was done but wrote no APK.");
            }
        } catch (Throwable t) {
            try {
                result = new JSONObject().put("success", false).put("error", t.getMessage() == null ? t.toString() : t.getMessage());
                note(jobId, "ERROR", t.toString());
            } catch (JSONException ignored) {}
        } finally {
            try { if (result == null) result = new JSONObject().put("success", false).put("error", "The patch did not finish"); } catch (JSONException ignored) {}
            // The run's own folder (input copy, work files) is not needed any more; the log stays with the patched APK
            deleteTree(new File(dir, "work"));
            new File(dir, "input.apk").delete();
            new File(dir, "input.apks").delete();
            File splits = new File(dir, "splits");
            deleteTree(splits);
            try { ev(jobId, new JSONObject().put("t", "result").put("result", result)); } catch (JSONException ignored) {}
            if (a.optBoolean("cleanUp", true)) deleteTree(dir);
        }
    }

    private File prepareInput(JSONObject a, File dir, String jobId) throws Exception {
        String from = a.optString("from");
        if ("installed".equals(from)) {
            JSONObject inst = apkInstalled(a.optString("pkg"));
            if (!inst.optBoolean("ok")) throw new IOException(a.optString("pkg") + " is not installed on this phone");
            JSONArray paths = inst.getJSONArray("paths");
            if (paths.length() == 1) {
                File in = new File(dir, "input.apk");
                note(jobId, "INFO", "Copying the installed APK");
                MorpheLibrary.copy(new File(paths.getString(0)), in);
                return in;
            }
            File splits = new File(dir, "splits");
            splits.mkdirs();
            List<File> parts = new ArrayList<File>();
            for (int i = 0; i < paths.length(); i++) {
                File src = new File(paths.getString(i));
                File dst = new File(splits, i == 0 ? "base.apk" : src.getName());
                MorpheLibrary.copy(src, dst);
                parts.add(dst);
            }
            note(jobId, "INFO", "Bundling " + parts.size() + " split APKs");
            File apks = new File(dir, "input.apks");
            MorpheLibrary.zipApks(parts, apks);
            deleteTree(splits);
            return apks;
        }
        File f = new File(a.optString("path"));
        if (!MorpheJobs.inside(f, allowedRoots()) || !f.isFile()) throw new IOException("The APK file is not available any more. Choose it again.");
        return f;
    }

    /** Files the patched APK: the app folder, Downloads/Morphe Patcher, or both, as the person chose. */
    private JSONObject save(JSONObject a, File output, File dir, JSONObject engineResult) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("pkg", a.optString("pkg"));
        meta.put("appName", a.optString("name", a.optString("pkg")));
        meta.put("versionName", a.optString("version"));
        meta.put("versionCode", a.optString("versionCode"));
        meta.put("mode", a.optString("mode"));
        JSONArray applied = engineResult.optJSONArray("applied");
        meta.put("patches", applied == null ? new JSONArray() : applied);
        JSONArray bs = new JSONArray();
        JSONArray in = a.optJSONArray("bundles");
        if (in != null) for (int i = 0; i < in.length(); i++) {
            JSONObject b = in.getJSONObject(i);
            bs.put(new JSONObject().put("id", b.optString("id")).put("name", b.optString("name")).put("version", b.optString("version")));
        }
        meta.put("bundles", bs);
        meta.put("installed", false);
        String keep = a.optString("keepIn", "both");
        File log = new File(dir, "events.log");
        File dl = new File(host.downloadsDir(), "Morphe Patcher");
        JSONArray kept = new JSONArray();
        JSONObject item;
        if ("downloads".equals(keep)) {
            try { item = library.add(output, meta, log, dl); kept.put("Downloads/Morphe Patcher"); }
            catch (IOException e) { item = library.add(output, meta, log); kept.put("the app folder (Downloads was not writable)"); }
        } else {
            item = library.add(output, meta, log);
            kept.put("the app folder");
            if ("both".equals(keep)) {
                try { library.exportTo(item.getString("id"), dl); kept.put("Downloads/Morphe Patcher"); }
                catch (IOException e) { note(a.optString("job"), "WARN", "Could not copy the APK to Downloads: " + e.getMessage()); }
            }
        }
        item.put("keptIn", kept);
        return item;
    }

    private void markInstalled(String id) {
        try {
            JSONObject m = library.get(id);
            if (m == null) return;
            m.put("installed", true);
            File f = new File(new File(base, "patched"), id + "/meta.json");
            writeText(f, m.toString());
        } catch (Exception ignored) {}
    }

    private JSONObject patchedExport(String id) throws Exception {
        File dest = library.exportTo(id, new File(host.downloadsDir(), "Morphe Patcher"));
        return new JSONObject().put("path", dest.getAbsolutePath());
    }

    private JSONObject install(JSONObject a) throws Exception {
        File apk;
        String pkg = a.optString("pkg");
        if (!a.optString("id").isEmpty()) {
            JSONObject m = library.get(a.optString("id"));
            if (m == null) throw new IOException("that patched APK is gone");
            apk = new File(m.optString("file"));
            if (pkg.isEmpty()) pkg = m.optString("pkg");
        } else {
            apk = new File(a.optString("path"));
            if (!MorpheJobs.inside(apk, allowedRoots())) throw new IOException("that file is not one this app may install");
        }
        if (!apk.isFile()) throw new IOException("the APK is gone");
        JSONObject r = host.install(apk, pkg, a.optBoolean("uninstallFirst"));
        if (r.optBoolean("ok") && !a.optString("id").isEmpty()) markInstalled(a.optString("id"));
        return r;
    }

    // ------------------------------------------------------------------------------------------------------------------------ keys

    private JSONObject keyExport() throws Exception {
        File k = new File(base, "morphe.keystore");
        if (!k.isFile()) throw new IOException("There is no signing key yet: it is made the first time you patch.");
        File dir = new File(host.downloadsDir(), "Morphe Patcher");
        dir.mkdirs();
        File dest = new File(dir, "morphe.keystore");
        MorpheLibrary.copy(k, dest);
        return new JSONObject().put("path", dest.getAbsolutePath());
    }

    private JSONObject keyImport(JSONObject a) throws Exception {
        File f = new File(a.optString("path"));
        if (!MorpheJobs.inside(f, allowedRoots()) || !f.isFile()) throw new IOException("that file is not one this app may read");
        String pw = a.optString("password");
        File k = new File(base, "morphe.keystore");
        MorpheLibrary.copy(f, k);
        JSONObject info = new JSONObject();
        info.put("alias", "Morphe");
        info.put("password", pw.isEmpty() ? "Morphe" : pw);
        if (!pw.isEmpty()) info.put("storePassword", pw);
        writeText(new File(base, "morphe_key.json"), info.toString());
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------------------ Morphe Helper

    private void download(JSONObject h, String text, long done, long total) {
        try { event(new JSONObject().put("t", "dl").put("pct", total > 0 ? (int) (100 * done / total) : 0).put("text", text + (total > 0 ? " " + (100 * done / total) + "%" : ""))); } catch (JSONException ignored) {}
    }

    private JSONObject helperGet(JSONObject a, boolean fast) throws Exception {
        String conn = a.optString("conn", "both");
        if (!host.connectionOk(conn)) throw new IOException("wifi".equals(conn) ? "Morphe Helper is set to Wi-Fi only and this phone is not on Wi-Fi." : "Morphe Helper is set to mobile data only and this phone is not on mobile data.");
        String pkg = a.optString("pkg");
        String ver = a.optString("version").trim();
        String abi = a.optString("abi");
        String policy = a.optString("policy", "requested");
        if (ver.isEmpty()) policy = "latest";                       // no version named: the newest
        JSONObject resolved;
        if (fast) {
            JSONArray srcs = a.optJSONArray("sources");
            resolved = MorpheHelper.fast(srcs == null ? new JSONArray() : srcs, pkg, ver.isEmpty() ? null : ver, abi, policy);
        } else {
            resolved = MorpheHelper.resolve(a.optString("source"), pkg, ver.isEmpty() ? null : ver, abi, policy);
        }
        final String label = "Downloading " + pkg;
        File dir = helperDir(a.optString("save", "cache"));
        dir.mkdirs();
        JSONObject got = MorpheHelper.download(resolved, dir, new MorpheNet.Progress() {
            @Override public void onProgress(long done, long total) { download(null, label, done, total); }
            @Override public boolean cancelled() { return false; }
        });
        got.put("fileName", new File(got.optString("path")).getName());
        got.put("source", resolved.optString("source"));
        try {
            PackageManager pm = host.context().getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(got.optString("pkg"), 0));
            if (l != null) got.put("label", l.toString());
        } catch (Exception ignored) {}
        return got;
    }

    /** The APK / APKM / APKS / XAPK files in Downloads (what the browser saved), for the package asked for: {access, items, ...}. */
    private JSONObject helperDownloads(JSONObject a) throws Exception {
        if (!host.storageAccess()) return new JSONObject().put("ok", true).put("access", false).put("items", new JSONArray());
        File dir = host.downloadsDir();
        JSONObject r = MorpheHelper.scanFolder(dir, a.optString("pkg"), Math.max(1, Math.min(40, a.optInt("limit", 12))));
        r.put("access", true);
        r.put("folder", dir.getAbsolutePath());
        return r;
    }

    /** A file from Downloads becomes the Helper's result: copied (not moved) into the Helper's folder, then described like a download. */
    private JSONObject helperAdopt(JSONObject a) throws Exception {
        File src = new File(a.optString("path"));
        File dl = host.downloadsDir();
        if (!host.storageAccess() || !MorpheJobs.inside(src, java.util.Collections.singletonList(dl))) throw new IOException("that file is not one this app may read");
        JSONObject info = MorpheHelper.inspect(src);
        if (!info.optBoolean("ok")) throw new IOException(info.optString("error", "That is not an APK, APKS, APKM or XAPK file"));
        File dir = helperDir(a.optString("save", "cache"));
        dir.mkdirs();
        File dest = new File(dir, src.getName());
        if (!dest.getCanonicalPath().equals(src.getCanonicalPath())) MorpheLibrary.copy(src, dest);
        JSONObject got = MorpheHelper.inspect(dest);
        got.put("fileName", dest.getName());
        got.put("source", "downloads");
        try {
            PackageManager pm = host.context().getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(got.optString("pkg"), 0));
            if (l != null) got.put("label", l.toString());
        } catch (Exception ignored) {}
        return got;
    }

    private volatile Runnable browserCloser;
    private HelperDownloads downloadList;
    private final Map<String, long[]> lastPush = new ConcurrentHashMap<String, long[]>();

    /** The Helper's download list (it outlives the browser window, and the journal outlives the app). */
    private synchronized HelperDownloads downloads() {
        if (downloadList == null) {
            downloadList = new HelperDownloads(new File(base, "helper_downloads.json"), host.cookies(), new java.util.function.Predicate<File>() {
                @Override public boolean test(File f) {
                    try { return f.getCanonicalFile().equals(helperDir("cache").getCanonicalFile()) || f.getCanonicalFile().equals(helperDir("downloads").getCanonicalFile()); }
                    catch (IOException e) { return false; }
                }
            });
            downloadList.addListener(new HelperDownloads.Listener() {
                @Override public void onChange(BrowserDownload.Job j) {
                    long now = System.currentTimeMillis();
                    long[] last = lastPush.get(j.id);
                    long st = j.state.ordinal();
                    if (last != null && last[1] == st && now - last[0] < 400) return;      // progress is passed on a few times a second, a change of state at once
                    lastPush.put(j.id, new long[]{now, st});
                    try { event(new JSONObject().put("t", "hd").put("job", HelperDownloads.forPage(j))); } catch (JSONException ignored) {}
                }
            });
        }
        return downloadList;
    }

    /** Pause, resume (also Retry), cancel, remove, use (the saved file becomes the Helper's result), clear (finished ones off the list). */
    private JSONObject helperDownloadOp(JSONObject a) throws Exception {
        HelperDownloads dl = downloads();
        String op = a.optString("op"), id = a.optString("id");
        switch (op) {
            case "pause": dl.pause(id); break;
            case "resume": dl.resume(id); break;
            case "cancel": dl.cancel(id); break;
            case "remove": dl.remove(id); break;
            case "clear": dl.clearFinished(); break;
            case "use": {
                BrowserDownload.Job j = dl.get(id);
                if (j == null || j.state != BrowserDownload.State.DONE || j.file == null || !j.file.isFile()) throw new IOException("that download is not finished");
                JSONObject d = describeDownloaded(j.file);
                if (!d.optBoolean("ok")) throw new IOException(d.optString("error"));
                return new JSONObject().put("info", d.getJSONObject("info"));
            }
            default: throw new IOException("unknown download action");
        }
        return new JSONObject().put("jobs", dl.pageList());
    }

    /** {ok, info} for a saved file that is an APK / APKS / APKM / XAPK (info as for any Helper result), else {ok:false, error}. */
    private JSONObject describeDownloaded(File f) {
        JSONObject e = new JSONObject();
        try {
            JSONObject info = MorpheHelper.inspect(f);
            if (info.optBoolean("ok")) {
                info.put("fileName", f.getName());
                info.put("source", "browser");
                try {
                    PackageManager pm = host.context().getPackageManager();
                    CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(info.optString("pkg"), 0));
                    if (l != null) info.put("label", l.toString());
                } catch (Exception ignored) {}
                e.put("ok", true).put("info", info);
            } else {
                e.put("ok", false).put("error", info.optString("error", "that is not an APK, APKS, APKM or XAPK file"));
            }
        } catch (Exception x) {
            try { e.put("ok", false).put("error", String.valueOf(x.getMessage())); } catch (JSONException ignored) {}
        }
        return e;
    }

    /**
     * The in-app browser for the sources that need a real browser (APKMirror's browser check): opens {@code url} (or the source's own page), and what the person
     * downloads there is saved into the Helper's folder (as a job of the Helper's download list) and described to the page as events {t:"hb", k:"status"|"done"|"failed"|"closed"}.
     */
    private JSONObject helperBrowse(JSONObject a) throws Exception {
        String url = a.optString("url").trim();
        if (url.isEmpty()) url = MorpheHelper.manualUrl(a.optString("source"), a.optString("pkg"), a.optString("version"));
        if (!(url.startsWith("https://") || url.startsWith("http://"))) throw new IOException("only web addresses open in the browser");
        File dir = helperDir(a.optString("save", "cache"));
        dir.mkdirs();
        final File folder = dir;
        browserCloser = host.openBrowser(url, folder, downloads(), new BrowserDownload.Events() {
            @Override public void onStatus(String text, int pct) {
                try { event(new JSONObject().put("t", "hb").put("k", "status").put("text", text).put("pct", pct)); } catch (JSONException ignored) {}
            }
            @Override public boolean onDownloaded(File f) {
                JSONObject d = describeDownloaded(f);
                JSONObject e = new JSONObject();
                try {
                    e.put("t", "hb").put("k", "done").put("name", f.getName());
                    e.put("ok", d.optBoolean("ok"));
                    if (d.has("info")) e.put("info", d.get("info"));
                    if (d.has("error")) e.put("error", d.get("error"));
                } catch (JSONException ignored) {}
                event(e);
                return d.optBoolean("ok");
            }
            @Override public void onFailed(String why) {
                try { event(new JSONObject().put("t", "hb").put("k", "failed").put("error", why)); } catch (JSONException ignored) {}
            }
            @Override public void onClosed() {
                browserCloser = null;
                try { event(new JSONObject().put("t", "hb").put("k", "closed")); } catch (JSONException ignored) {}
            }
        });
        return new JSONObject().put("url", url);
    }

    private JSONObject vtScan(JSONObject a) throws Exception {
        File f = new File(a.optString("path"));
        if (!MorpheJobs.inside(f, allowedRoots())) throw new IOException("that file is not one this app may read");
        MorpheVirusTotal vt = new MorpheVirusTotal(a.optString("key"), new File(base, "vt_state.json"));
        return vt.scan(f, new MorpheNet.Progress() {
            @Override public void onProgress(long done, long total) { download(null, "Uploading to VirusTotal", done, total); }
            @Override public boolean cancelled() { return false; }
        });
    }

    // ------------------------------------------------------------------------------------------------------------------------ files

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
