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
        /**
         * Opens the share sheet for the staged copy of the signing key ({@link KeyShare#stage}) and arranges for the copy to be removed (and the grant
         * revoked) {@link KeyShare#KEEP_MS} later. Throws when the sheet cannot be opened; the caller then removes the copy.
         */
        void shareKey(File staged) throws Exception;
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
        /** Copies a file into a folder the person chose with Android's folder picker (a content:// tree); returns the name it was saved as. */
        String copyToTree(File file, String treeUri, String name) throws Exception;
        /**
         * Asks, in a dialog of the app that the page cannot press, whether a patch bundle (code) may be loaded. Blocks the calling (background) thread until
         * the person answers: ALLOW or DENY for what they pressed (or the screen closing), TIMEOUT only when nobody answered in
         * {@link BundleApprovals#ANSWER_WAIT_SECONDS} seconds.
         */
        BundleApprovals.Answer confirmBundle(String title, String text);
    }

    private static final String ENGINE_CLASS = "com.bloatware.bingblop.morphe.EngineMain";
    private static final Object NO_REPLY = new Object();

    private final Host host;
    private final File base;
    private final MorpheStore store;
    private final MorpheLibrary library;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean closing;                                                   // the window is gone: its Helper downloads and VirusTotal uploads stop (a patch goes on)
    private static final Object catalogLock = new Object();                         // one catalog read at a time in the process: every window's bridge shares jobs/list
    private final MorpheShared shared;                                                   // what every window of the app shares: the runner, the slot, the events of the patch that is going
    private final MorpheRunner runner;                                                  // the one engine lock of the process (see MorpheShared)
    private final MorpheRunner.EventSink sink;                                          // this window's side of the patch's events
    private final MorpheSlot slot;                                                      // the one patch run at a time (an atomic take, see MorpheSlot)
    private static final MorpheShared.Windows<Host> windows = new MorpheShared.Windows<Host>();   // the open windows: the newest is the one a patch that is going asks for the installer
    private final BundleApprovals approvals;                                            // which custom bundles the person has allowed to run (see BundleApprovals)
    private final BundleApprovals.Asker asker = new BundleApprovals.Asker() {
        @Override public BundleApprovals.Answer ask(String title, String text) { return host.confirmBundle(title, text); }
    };
    private final Object grandfatherLock = new Object();
    private boolean grandfatherDone;

    public MorpheBridge(Host host, File base) {
        this.host = host;
        this.base = base;
        base.mkdirs();
        this.store = new MorpheStore(new File(base, "sources"));
        this.library = new MorpheLibrary(new File(base, "patched"));
        this.approvals = new BundleApprovals(new File(base, "bundle_approvals.json"));
        // A patch outlives the window that started it: the runner, the slot and the patch's events are the process's, and this (newest) window takes over the events
        final Context app = host.context().getApplicationContext();
        this.shared = MorpheShared.get(new ServiceLauncher(app));
        this.runner = shared.runner;
        this.slot = shared.slot;
        this.sink = new MorpheRunner.EventSink() { @Override public void accept(JSONObject e) { event(e); } };
        this.shared.bind(sink);
        windows.opened(host);
    }

    /**
     * A window of the app was created. It is the one a patch that is going asks for the installer from now on (until a newer one opens), also when its
     * page has not used the Morphe tab yet (its bridge is only made on the first call). The activity calls this from onCreate.
     */
    public static void windowOpened(Host h) { windows.opened(h); }

    /** The window of {@code h} is destroyed: it is never asked again; the newest window still open is. The activity calls this from onDestroy. */
    public static void windowClosed(Host h) { windows.closed(h); }

    /**
     * The window of this bridge is going away. The process-wide holders stop pointing at it (they would keep the destroyed window alive); a patch that is
     * going goes on, and the next window's bridge takes over its events. A Helper download or a VirusTotal upload this window started is for its page
     * only, so it stops (its part file is deleted) instead of running on for a page that is gone.
     */
    public void release() {
        closing = true;
        shared.unbind(sink);
        windows.closed(host);
        synchronized (this) {                                                            // the download list is the process's: this page is no longer told about it
            if (downloadList != null && pagePush != null) downloadList.removeListener(pagePush);
            pagePush = null;
        }
    }

    /** The patcher service as the runner uses it. A class of its own (not an inner one) and with the application context only: the process keeps it, and it must not keep a window alive. */
    private static final class ServiceLauncher implements MorpheRunner.Launcher {
        private final Context app;
        ServiceLauncher(Context app) { this.app = app; }
        @Override public boolean start(String cmd, File job, File events, String title) { return MorpheService.start(app, cmd, job, events, title); }
        @Override public void cancel(File events) { MorpheService.cancel(app, events); }
        @Override public boolean alive() { return serviceAlive(app); }
    }

    /** The first call of all: bundles already on disk when this check first runs count as approved, once (before a call can add another). */
    private void grandfatherBundles() {
        synchronized (grandfatherLock) {
            if (grandfatherDone) return;
            grandfatherDone = true;
            try {
                Map<String, File> present = new java.util.LinkedHashMap<String, File>();
                JSONArray all = store.list();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject s = all.getJSONObject(i);
                    if (s.optBoolean("builtIn") || s.optString("file").isEmpty()) continue;
                    present.put(s.optString("id"), new File(s.optString("file")));
                }
                approvals.grandfatherOnce(present);
            } catch (Exception ignored) { /* nothing approved: the person is asked */ }
        }
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
            grandfatherBundles();
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
            case "sourceRemove": store.remove(a.optString("id")); approvals.forget(a.optString("id")); return null;
            case "sourceRename": store.rename(a.optString("id"), a.optString("name")); return null;
            case "sourceEnable": store.setEnabled(a.optString("id"), a.optBoolean("on")); return null;
            case "sourcePre": store.setPrerelease(a.optString("id"), a.optBoolean("on")); return null;
            case "community": return store.community(a.optBoolean("refresh"), null);
            case "catalog": return catalog(a.optJSONArray("ids"), a.optBoolean("force"));
            case "apkInstalled": return apkInstalled(a.optString("pkg"));
            case "apkInspect": return apkInspect(a.optString("path"));
            case "pick": return pick(tag, a.optString("kind", "any"));
            case "patch": return startPatch(a);
            case "cancel": {
                String job = a.optString("job");
                if (MorpheJobs.validJobId(job) && job.equals(slot.current())) runner.cancel(job);   // only the run that is going; a stale or foreign id changes nothing
                return null;
            }
            case "jobStatus": {
                // Whether a patch is going that this window did not start (the window was closed and opened again): built and posted under the holder's lock, so every event is either in it or comes after it
                shared.atomically(new Runnable() {
                    @Override public void run() {
                        try { reply(tag, true, shared.status(), null); } catch (JSONException ignored) {}
                    }
                });
                return NO_REPLY;
            }
            case "patchedList": adoptOrphans(); return new JSONObject().put("items", library.list());
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
            case "helperSources": return new JSONObject().put("sources", MorpheHelper.sources()).put("defaults", MorpheHelper.settingsDefaults()).put("abi", android.text.TextUtils.join(",", Build.SUPPORTED_ABIS));
            case "helperFind": return MorpheHelper.find(a.optString("query"));
            case "copyToTree": return copyToTree(a);
            case "helperManual": return new JSONObject().put("url", RepoSources.handles(a.optString("source")) ? RepoSources.pageUrl(a.optString("source"), a.optString("pkg")) : MorpheHelper.manualUrl(a.optString("source"), a.optString("pkg"), a.optString("version")));
            case "helperVersions": return RepoSources.handles(a.optString("source")) ? RepoSources.versions(a.optString("source"), a.optString("pkg")) : MorpheHelper.versions(a.optString("source"), a.optString("pkg"));
            case "helperBrowse": return helperBrowse(a);
            case "helperDownloadList": return new JSONObject().put("jobs", downloads().pageList()).put("background", backgroundOn());
            case "helperDownloadOp": return helperDownloadOp(a);
            case "helperBrowseClose": if (browserCloser != null) { final Runnable c = browserCloser; browserCloser = null; c.run(); } return null;
            case "helperDownloads": return helperDownloads(a);
            case "helperAdopt": return helperAdopt(a);
            case "helperGet": return helperGet(a, false);
            case "helperFast": return helperGet(a, true);
            case "vtQuota": return new MorpheVirusTotal("", new File(base, "vt_state.json")).quota();
            case "vtQuotaLive": return MorpheVirusTotal.accountQuota(a.optString("key"), new File(base, "vt_state.json"));
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
        return MorpheJobs.pageRoots(base, new File(host.context().getCacheDir(), "morphe_pick"), host.downloadsDir());   // not base itself: it holds the signing key
    }

    private File helperDir(String save) {
        if ("updater".equals(save)) return new File(host.downloadsDir(), "App Updater");
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

    private static boolean serviceAlive(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return true;
            String want = c.getPackageName() + ":morphe";
            for (ActivityManager.RunningAppProcessInfo p : ps) if (want.equals(p.processName)) return true;
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** Runs one engine command in the service and follows its events file (see MorpheRunner). */
    private JSONObject runEngine(String cmd, JSONObject job, File dir, String title, String jobId, MorpheRunner.EventSink sink, long timeoutMs) throws Exception {
        return runner.run(cmd, job, dir, title, jobId, sink, timeoutMs);
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
        synchronized (catalogLock) {                                                       // a second call waits, then finds the first one's answer in the cache
            return catalogLocked(ids, force);
        }
    }

    private JSONObject catalogLocked(JSONArray ids, boolean force) throws Exception {
        JSONObject out = new JSONObject();
        File cacheDir = new File(base, "catalog");
        cacheDir.mkdirs();
        List<String> need = new ArrayList<String>();
        List<File> needFiles = new ArrayList<File>();
        List<File> needCache = new ArrayList<File>();
        if (ids == null) ids = new JSONArray();
        Map<String, String> refused = refuseUnapproved(ids);                               // asks about each custom bundle not yet allowed, before anything is read
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            if (refused.containsKey(id)) { out.put(id, denied(refused.get(id))); continue; }
            File mpp = store.bundleFile(id);                                               // null for a source that is switched off
            JSONObject s = store.get(id);
            if (mpp == null || s == null) continue;
            BundleApprovals.Source bs = bundleSource(id);
            boolean custom = bs != null && !bs.builtIn;
            File cache = null;                                                             // a custom bundle's list is cached per approved file (its SHA-256), never per version and size
            if (!custom) cache = new File(cacheDir, BundleApprovals.catalogCacheName(MorpheJobs.safeName(id), true, MorpheJobs.safeName(s.optString("version")), mpp.length(), null));
            else if (approvals.approvedHash(id) != null) cache = new File(cacheDir, BundleApprovals.catalogCacheName(MorpheJobs.safeName(id), false, "", 0, approvals.approvedHash(id)));
            if (cache != null && !force && cache.isFile()) {
                try { out.put(id, new JSONObject(readText(cache))); continue; } catch (Exception bad) { cache.delete(); }
            }
            need.add(id); needFiles.add(mpp); needCache.add(cache);
        }
        File copies = new File(base, "jobs/list/bundles");                                 // the engine reads these private copies, never the live files
        deleteTree(copies);
        try {
            for (int i = need.size() - 1; i >= 0; i--) {                                   // the file may have changed while a question was open (or since): the copy made now is checked against the approval
                String[] sha = { null };
                File copy = approvals.approvedCopy(bundleSource(need.get(i)), copies, sha);
                if (copy != null) {
                    if (sha[0] != null) {                                                  // a custom bundle: cached under the hash of the very bytes the engine reads
                        needCache.set(i, new File(cacheDir, BundleApprovals.catalogCacheName(MorpheJobs.safeName(need.get(i)), false, "", 0, sha[0])));
                        BundleApprovals.dropOtherCatalogs(cacheDir, MorpheJobs.safeName(need.get(i)), needCache.get(i).getName());
                    } else if (copy.length() != needFiles.get(i).length()) needCache.set(i, null);   // not the file the cache name was made from: do not cache under that name
                    needFiles.set(i, copy);
                    continue;
                }
                out.put(need.get(i), denied(BundleApprovals.NOT_ALLOWED));
                need.remove(i); needFiles.remove(i); needCache.remove(i);
            }
            if (need.isEmpty()) return new JSONObject().put("catalogs", out);
            return catalogEngine(out, need, needFiles, needCache);
        } finally {
            deleteTree(copies);
        }
    }

    /** Runs the engine over the private copies in {@code needFiles} and fills {@code out} (and the cache) with what it read. */
    private JSONObject catalogEngine(JSONObject out, List<String> need, List<File> needFiles, List<File> needCache) throws Exception {
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
                if (needCache.get(i) != null) try { writeText(needCache.get(i), c.toString()); } catch (IOException ignored) {}
                store.setPatchCount(need.get(i), c.getJSONArray("patches").length());
            }
        }
        answer.delete();
        return new JSONObject().put("catalogs", out);
    }

    private static JSONObject denied(String error) throws JSONException {
        return new JSONObject().put("ok", false).put("error", error).put("patches", new JSONArray());
    }

    /** The bundle a source id would hand to the engine, with what the store itself knows about it; null when there is none or the source is switched off. */
    private BundleApprovals.Source bundleSource(String id) {
        File f = store.bundleFile(id);
        JSONObject facts = store.bundleFacts(id);
        return f == null || facts == null ? null : BundleApprovals.Source.of(facts, f);
    }

    /** Asks about every custom bundle of these ids that is not approved yet, one question at a time; the answer is source id -> error for each one that is not allowed. */
    private Map<String, String> refuseUnapproved(JSONArray ids) throws JSONException {
        List<BundleApprovals.Source> list = new ArrayList<BundleApprovals.Source>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            BundleApprovals.Source s = seen.add(id) ? bundleSource(id) : null;
            if (s != null) list.add(s);
        }
        return approvals.requireAll(list, asker);
    }

    // ------------------------------------------------------------------------------------------------------------------------ patching

    /** Every bundle the patch job names must be allowed before the engine starts: a switched-off source is refused, the ones not approved are asked about. */
    private void approveBundles(JSONObject a, String jobId) throws Exception {
        JSONArray in = a.optJSONArray("bundles");
        if (in == null) return;                                                            // the job file says "no patch is selected"
        List<BundleApprovals.Source> list = new ArrayList<BundleApprovals.Source>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < in.length(); i++) {
            JSONObject b = in.optJSONObject(i);
            if (b == null || !seen.add(b.optString("id"))) continue;
            JSONObject facts = store.bundleFacts(b.optString("id"));
            if (facts != null && !facts.optBoolean("enabled", true)) throw new IOException("The source \"" + facts.optString("name") + "\" is switched off. Turn it on to use it.");
            BundleApprovals.Source s = bundleSource(b.optString("id"));
            if (s != null) list.add(s);
        }
        final boolean[] told = { false };                                                  // no file is read here just to count the questions: the line is written when the first one is asked
        final String run = jobId;
        BundleApprovals.Asker noting = new BundleApprovals.Asker() {
            @Override public BundleApprovals.Answer ask(String title, String text) {
                if (!told[0]) { told[0] = true; try { note(run, "INFO", "Waiting for your OK to run a patch source..."); } catch (JSONException ignored) {} }
                return asker.ask(title, text);
            }
        };
        Map<String, String> refused = approvals.requireAll(list, noting);
        if (!refused.isEmpty()) throw new IOException(refused.values().iterator().next() + " (" + refused.keySet().iterator().next() + ")");
    }

    private JSONObject startPatch(final JSONObject a) throws Exception {
        if (!engineThere()) throw new IOException("This build of the app does not contain the Morphe engine.");
        final String jobId = a.optString("job");
        if (jobId.isEmpty()) throw new IOException("no job id");
        if (!MorpheJobs.validJobId(jobId)) throw new IOException("bad job id");           // it names a folder that the run empties first
        if (!slot.tryStart(jobId)) throw new IOException("A patch is already running.");
        runner.clearCancelled(jobId);
        shared.begin(jobId, a);
        try {
            pool.submit(new Runnable() {
                @Override public void run() {
                    try { patch(a, jobId); } finally { slot.finish(jobId); runner.clearCancelled(jobId); }
                }
            });
        } catch (RuntimeException refused) {                                               // the pool would not take it: do not keep the slot for a run that never starts
            slot.finish(jobId);
            throw refused;
        }
        return new JSONObject().put("job", jobId);
    }

    private void ev(String jobId, JSONObject e) {
        try { e.put("job", jobId); e.put("ts", System.currentTimeMillis()); } catch (JSONException ignored) {}
        shared.emit(e);
    }

    private void step(String jobId, String name, String state) throws JSONException {
        ev(jobId, new JSONObject().put("t", "step").put("name", name).put("state", state));
    }

    private void note(String jobId, String level, String text) throws JSONException {
        ev(jobId, new JSONObject().put("t", "log").put("level", level).put("text", text));
    }

    private void patch(JSONObject a, final String jobId) {
        File dir = new File(base, "jobs/" + MorpheJobs.safeName(jobId));
        if (!MorpheJobs.validJobId(jobId)) {                                              // startPatch refuses these; never empty a folder named by anything else
            try { ev(jobId, new JSONObject().put("t", "result").put("result", new JSONObject().put("success", false).put("error", "bad job id"))); } catch (JSONException ignored) {}
            return;
        }
        JSONObject result = null;
        JSONObject saved = null;
        try {
            deleteTree(dir);
            dir.mkdirs();
            try { writeText(new File(dir, "request.json"), a.toString()); } catch (IOException ignored) {}   // lets a later start of the app file the result if this process is killed
            step(jobId, "Preparing", "RUNNING");
            approveBundles(a, jobId);
            File input = prepareInput(a, dir, jobId);
            File output = new File(dir, "patched.apk");
            File keystore = new File(base, "morphe.keystore");
            JSONObject keyInfo = null;
            File ki = new File(base, "morphe_key.json");
            if (ki.isFile()) try { keyInfo = new JSONObject(readText(ki)); } catch (Exception ignored) {}
            final File copies = new File(dir, "bundles");                                  // the engine reads these private copies, never the live files
            final Map<String, File> given = new java.util.HashMap<String, File>();
            JSONObject job = MorpheJobs.patchJob(a, input, output, new File(dir, "work"), new MorpheJobs.Bundles() {
                @Override public File fileOf(String id) {
                    if (given.containsKey(id)) return given.get(id);
                    BundleApprovals.Source s = bundleSource(id);                           // null: not downloaded, or switched off
                    if (s == null) return null;
                    File copy = approvals.approvedCopy(s, copies);                         // copied and hashed now: what was approved is what the engine gets
                    if (copy == null) throw new IllegalStateException(BundleApprovals.NOT_ALLOWED);   // changed since the question
                    given.put(id, copy);
                    return copy;
                }
            }, keystore, keyInfo, MorpheJobs.abiName(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : ""));
            step(jobId, "Preparing", "OK");
            final JSONObject engineResult = MorpheJobs.checkApplied(runEngine("patch", job, dir, "Patching " + a.optString("name", a.optString("pkg")), jobId, new MorpheRunner.EventSink() {
                @Override public void accept(JSONObject e) { ev(jobId, e); }
            }, 45L * 60 * 1000));                                                              // nothing applied is a failure: the APK is the unpatched app
            result = engineResult;
            if (engineResult.optBoolean("success") && output.isFile() && runner.isCancelled(jobId)) {
                // Stop came in while the engine was writing its answer: the person was told that nothing is saved, so the APK is not filed
                output.delete();
                note(jobId, "INFO", "Stopped: the patched APK was not saved.");
                result = new JSONObject().put("success", false).put("cancelled", true).put("error", "Cancelled");
            } else if (engineResult.optBoolean("success") && output.isFile()) {
                step(jobId, "Saving", "RUNNING");
                saved = save(a, output, dir, engineResult);
                ev(jobId, new JSONObject().put("t", "saved").put("item", saved));
                step(jobId, "Saving", "OK");
                if (a.optBoolean("installAfter") && runner.isCancelled(jobId)) {
                    // Stop came in while the APK was being saved: it is saved (the result says where, with an Install button), so the run is not
                    // reported as "nothing saved"; it is only not installed
                    note(jobId, "WARN", "Stopped before the install: the patched APK is saved in Patched APKs and was not installed.");
                } else if (a.optBoolean("installAfter")) {
                    step(jobId, "Installing", "RUNNING");
                    JSONObject ir;
                    Host asks = windows.newest();                                         // the newest open window asks for the installer, never one that is gone (MOR-1)
                    try { ir = (asks != null ? asks : host).install(new File(saved.optString("file")), a.optString("pkg"), false); }
                    catch (Exception e) { ir = new JSONObject().put("ok", false).put("output", "Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
                    ir.put("t", "install");
                    ev(jobId, ir);
                    step(jobId, "Installing", MorpheInstallStep.stepState(ir));                    // only handed to the system installer: not marked done
                    if (MorpheInstallStep.settle(library, saved.optString("id"), ir, a.optBoolean("deleteAfter"))) ev(jobId, new JSONObject().put("t", "deleted"));
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
            deleteTree(new File(dir, "bundles"));
            new File(dir, "input.apk").delete();
            new File(dir, "input.apks").delete();
            File splits = new File(dir, "splits");
            deleteTree(splits);
            try { ev(jobId, new JSONObject().put("t", "result").put("result", result)); } catch (JSONException ignored) {}
            if (a.optBoolean("cleanUp", true)) deleteTree(dir);
        }
    }

    /**
     * Files the results of runs that finished while the app was not running (the engine is a process of its own), and removes the folders of runs
     * that failed or never answered. The run that is going now is left alone.
     */
    private synchronized void adoptOrphans() {
        MorpheOrphans.Scan scan = MorpheOrphans.scan(new File(base, "jobs"), slot.current(), System.currentTimeMillis(), 6L * 60 * 60 * 1000);
        for (MorpheOrphans.Orphan o : scan.finished) {
            try {
                save(o.request, o.apk, o.dir, o.result);
                deleteTree(o.dir);
            } catch (Throwable t) { /* stays where it is; the next list tries again */ }
        }
        for (File d : scan.debris) deleteTree(d);
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
        if (!a.optString("id").isEmpty()) MorpheInstallStep.settle(library, a.optString("id"), r, false);
        return r;
    }

    // ------------------------------------------------------------------------------------------------------------------------ keys

    /**
     * Export goes through the share sheet, never to a shared folder: the key lets whoever holds it sign apps as this person (and Android installs them as
     * updates of the apps patched here), so a copy in Downloads would stay readable for other apps for good. The private key file is copied once into a
     * folder of its own (KeyShare) and handed to the sheet; the copy is removed after a short time. The shareFile call is not used for this and is not
     * widened: it only reaches the folders of allowedRoots(), which do not include that folder.
     */
    private JSONObject keyExport() throws Exception {
        File k = new File(base, "morphe.keystore");
        if (!k.isFile()) throw new IOException("There is no signing key yet: it is made the first time you patch.");
        File staged = KeyShare.stage(host.context().getCacheDir(), k);
        try {
            host.shareKey(staged);
        } catch (Throwable t) {
            KeyShare.remove(staged);
            throw t instanceof Exception ? (Exception) t : new IOException(String.valueOf(t));
        }
        return new JSONObject().put("shared", true);
    }

    private JSONObject keyImport(JSONObject a) throws Exception {
        File f = new File(a.optString("path"));
        if (!MorpheJobs.inside(f, allowedRoots()) || !f.isFile()) throw new IOException("that file is not one this app may read");
        // the file is opened with the password and must hold the signing key before it replaces anything; the old key stays as .bak
        String why = KeyImport.install(base, f, a.optString("password"));
        if (why != null) throw new IOException(why);
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
            resolved = RepoSources.handles(a.optString("source")) ? RepoSources.resolve(a.optString("source"), pkg, ver.isEmpty() ? null : ver, policy)
                    : MorpheHelper.resolve(a.optString("source"), pkg, ver.isEmpty() ? null : ver, abi, policy);
        }
        final String label = "Downloading " + pkg;
        File dir = helperDir(a.optString("save", "cache"));
        dir.mkdirs();
        JSONObject got = MorpheHelper.download(resolved, dir, new MorpheNet.Progress() {
            @Override public void onProgress(long done, long total) { download(null, label, done, total); }
            @Override public boolean cancelled() { return closing; }
        });
        if (RepoSources.handles(resolved.optString("source")) && !RepoSources.abiFits(got.optJSONArray("abis"), Build.SUPPORTED_ABIS)) {
            new File(got.optString("path")).delete();
            throw new IOException("That build is made for " + got.optJSONArray("abis").join(", ").replace("\"", "") + ", and this phone runs " + android.text.TextUtils.join(", ", Build.SUPPORTED_ABIS)
                    + ". Pick another build from Versions.");
        }
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
    private HelperDownloads downloadList;                                               // the process's list (HelperDownloads.shared): every window sees the same jobs
    private HelperDownloads.Listener pagePush;                                          // tells this window's page about the list; taken off in release()
    private final Map<String, long[]> lastPush = new ConcurrentHashMap<String, long[]>();

    /** Downloads go on in the background, with a notification (a foreground service), unless the person switched that off: a marker file says so. */
    private boolean backgroundOn() { return backgroundOn(base); }

    private static boolean backgroundOn(File base) { return !new File(base, "hd_background_off").exists(); }

    /** Which folders a journal entry may name: the Helper's own. Holds only the folders (the list is the process's and must not keep a window alive). */
    private static final class HelperFolders implements java.util.function.Predicate<File> {
        private final File[] dirs;
        HelperFolders(File... dirs) { this.dirs = dirs; }
        @Override public boolean test(File f) {
            try {
                File c = f.getCanonicalFile();
                for (File d : dirs) if (c.equals(d.getCanonicalFile())) return true;
            } catch (IOException e) { return false; }
            return false;
        }
    }

    /** Keeps the downloads' notification service in step with the list. Added once, when the process's list is made; the application context only. */
    private static final class ServiceSync implements HelperDownloads.Listener {
        private final Context app;
        private final File base;
        private final HelperDownloads list;
        private final Map<String, Integer> lastState = new ConcurrentHashMap<String, Integer>();
        ServiceSync(Context app, File base, HelperDownloads list) { this.app = app; this.base = base; this.list = list; }
        @Override public void onChange(BrowserDownload.Job j) {
            Integer before = lastState.put(j.id, j.state.ordinal());
            try { DownloadService.sync(app, list, before == null || before != j.state.ordinal(), backgroundOn(base)); } catch (RuntimeException ignored) {}
        }
    }

    private void setBackground(boolean on) {
        File f = new File(base, "hd_background_off");
        if (on) f.delete();
        else try { base.mkdirs(); f.createNewFile(); } catch (IOException ignored) {}
        if (downloadList != null) { try { DownloadService.sync(host.context(), downloadList, true, on); } catch (RuntimeException ignored) {} }
    }

    /** The Helper's download list (it outlives the browser window, and the journal outlives the app). */
    private synchronized HelperDownloads downloads() {
        if (downloadList == null) {
            // One list for the process, not one per window (MOR-2): a second window's own list would read a running job from the journal as Paused,
            // and its Resume would start a second writer on the same part file
            final Context app = host.context().getApplicationContext();
            final File baseDir = base;
            downloadList = HelperDownloads.shared(new File(base, "helper_downloads.json"), host.cookies(),
                    new HelperFolders(helperDir("cache"), helperDir("downloads"), helperDir("updater")), new java.util.function.Consumer<HelperDownloads>() {
                        @Override public void accept(HelperDownloads made) { made.addListener(new ServiceSync(app, baseDir, made)); }
                    });
            pagePush = new HelperDownloads.Listener() {
                @Override public void onChange(BrowserDownload.Job j) {
                    long now = System.currentTimeMillis();
                    long[] last = lastPush.get(j.id);
                    long st = j.state.ordinal();
                    if (last != null && last[1] == st && now - last[0] < 400) return;      // progress is passed on a few times a second, a change of state at once
                    lastPush.put(j.id, new long[]{now, st});
                    try { event(new JSONObject().put("t", "hd").put("job", HelperDownloads.forPage(j))); } catch (JSONException ignored) {}
                }
            };
            downloadList.addListener(pagePush);
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
            case "background": setBackground(a.optBoolean("on", true)); return new JSONObject().put("background", backgroundOn());
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

    /** A downloaded file is also copied into the folder the person chose (Android's folder picker): {name}. */
    private JSONObject copyToTree(JSONObject a) throws Exception {
        File f = new File(a.optString("path"));
        if (!MorpheJobs.inside(f, allowedRoots()) || !f.isFile()) throw new IOException("that file is not one this app may copy");
        String tree = a.optString("tree");
        if (!tree.startsWith("content://")) throw new IOException("no folder was chosen");
        return new JSONObject().put("name", host.copyToTree(f, tree, f.getName()));
    }

    private JSONObject vtScan(JSONObject a) throws Exception {
        File f = new File(a.optString("path"));
        if (!MorpheJobs.inside(f, allowedRoots())) throw new IOException("that file is not one this app may read");
        MorpheVirusTotal vt = new MorpheVirusTotal(a.optString("key"), new File(base, "vt_state.json"));
        return vt.scan(f, new MorpheNet.Progress() {
            @Override public void onProgress(long done, long total) { download(null, "Uploading to VirusTotal", done, total); }
            @Override public boolean cancelled() { return closing; }
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
