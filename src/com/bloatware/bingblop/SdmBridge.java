package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The one door between the SD Maid SE tab's page and the Java side: {@code String call(String op, String argsJson)} answers
 * {@code {"ok":true,...}} or {@code {"ok":false,"error":"..."}}. Quick ops answer at once (run them off the UI thread: the first {@code areas} or
 * {@code mode} asks the shell for its uid); {@code scan}, {@code delete}, {@code oneclick} and {@code runAll} start tasks and the answers come through
 * {@code window.onSdm({ev: "progress" | "state" | "done" | "history", tool, ...})} by way of {@link Host#emit}. Results and state live in the engine
 * ({@code state} lets a rebuilt WebView re-attach).
 *
 * <p>Ops: state | areas | mode | scan{tool} | oneclick{tool} | cancel{tool|"all"} | delete{tool, selection} | groups{tool, offset, limit, sort?, q?} |
 * items{tool, group, offset, limit} | discard{tool} | exclude{tool, paths?, pkgs?} | undoExclude{handle} | exclusions | exclusionSave{exclusion} |
 * exclusionRemove{ids} | exclusionsRestoreDefaults | exclusionsExport | exclusionsImport{json} | resolvePkgs{q} | settingsGet{tool?} |
 * settingsSet{tool, key, value} | settingsReset{tool} | historyList{offset, limit} | historyPaths{id, offset, limit} | historyReset | historyStats |
 * acs{op: status|consent|revoke|openSettings|enableViaShell} | runAll{kind: scan|delete|oneclick}. Tool ids: systemcleaner, appcleaner, corpsefinder,
 * deduplicator; "general" is the tab's own settings group.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se; the bridge is this port's own
 * (spec 8.5). Pure Java apart from the {@link Host} the app implements, no android.* classes.
 */
public final class SdmBridge implements SdmEngine.Env, SdmEngine.Events {

    /** What MainActivity implements: the phone's file system, areas, apps, shell and automation, and the way back to the page. */
    public interface Host {
        Sdm.Fs fs();
        Sdm.Areas areas();
        Sdm.Packages pkgs();
        Sdm.Shell shell();
        /** Null when the accessibility service is not connected or has no consent. */
        Sdm.Automation automation();
        /** Sends one JSON object (as text) to the page: {@code window.onSdm(JSON.parse(json))}. Called from worker threads. */
        void emit(String json);
        /** The accessibility service: op is status | consent | revoke | openSettings | enableViaShell; answers {enabled, connected, consent, note?}. */
        JSONObject acs(String op, JSONObject args);
        /** The working mode now: adb_tcp, adb_wireless, shizuku, root or standard. */
        String modeName();
        boolean hasStorageAccess();
        boolean hasUsageAccess();
        /** The app's private files folder; the tab keeps its data in a folder "sdm" below it. */
        File filesDir();
        /** The clock (a hook for tests). */
        default long nowSec() { return System.currentTimeMillis() / 1000; }
    }

    private final Host host;
    private final SdmEngine engine;

    public SdmBridge(Host host) {
        this.host = host;
        this.engine = new SdmEngine(this, this);
    }

    public SdmEngine engine() { return engine; }

    public void shutdown() { engine.shutdown(); }

    // ---------------------------------------------------------------------------------------------------------- Env

    @Override public Sdm.Fs fs() { return host.fs(); }
    @Override public Sdm.Areas areas() { return host.areas(); }
    @Override public Sdm.Packages pkgs() { return host.pkgs(); }
    @Override public Sdm.Shell shell() { return host.shell(); }
    @Override public Sdm.Automation automation() { return host.automation(); }
    @Override public File dataDir() { return new File(host.filesDir(), "sdm"); }
    @Override public long nowSec() { return host.nowSec(); }

    // ---------------------------------------------------------------------------------------------------------- Events

    private void emit(JSONObject o) {
        try { host.emit(o.toString()); } catch (RuntimeException ignored) {}
    }

    @Override
    public void progress(String tool, JSONObject p) {
        try {
            JSONObject o = new JSONObject(p.toString());
            o.put("ev", "progress").put("tool", tool);
            emit(o);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void state(String tool, JSONObject s) {
        try { emit(new JSONObject().put("ev", "state").put("tool", tool).put("state", s)); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    @Override
    public void done(String tool, JSONObject d) {
        try {
            JSONObject o = new JSONObject(d.toString());
            o.put("ev", "done").put("tool", tool).put("d", new JSONObject(d.toString()));
            emit(o);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void history() {
        try { emit(new JSONObject().put("ev", "history")); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    // ---------------------------------------------------------------------------------------------------------- call

    private static final Sdm.Tool[] DASHBOARD_ORDER = { Sdm.Tool.CORPSEFINDER, Sdm.Tool.SYSTEMCLEANER, Sdm.Tool.APPCLEANER, Sdm.Tool.DEDUPLICATOR };

    public String call(String op, String argsJson) {
        JSONObject res;
        try {
            JSONObject a = argsJson == null || argsJson.trim().isEmpty() ? new JSONObject() : new JSONObject(argsJson);
            res = dispatch(op == null ? "" : op, a);
            if (!res.has("ok")) res.put("ok", true);
        } catch (JSONException e) {
            res = fail("Bad request: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            res = fail(e.getMessage() == null ? "Bad request" : e.getMessage());
        } catch (IllegalStateException e) {
            res = fail(e.getMessage() == null ? "Not possible now" : e.getMessage());
        } catch (RuntimeException e) {
            res = fail(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        return res.toString();
    }

    private static JSONObject fail(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private static Sdm.Tool tool(JSONObject a) {
        String id = a.optString("tool", "");
        return Sdm.Tool.of(id);
    }

    private JSONObject dispatch(String op, JSONObject a) throws JSONException {
        switch (op) {
            case "state": {
                JSONObject s = engine.states();
                return s.put("ok", true);
            }
            case "areas": return areas(a);
            case "mode": return mode();
            case "scan": {
                Sdm.Tool t = tool(a);
                if (!engine.has(t)) return fail("This tool is not part of this build.");
                engine.scan(t);
                return new JSONObject().put("started", true);
            }
            case "oneclick": {
                Sdm.Tool t = tool(a);
                if (!engine.has(t)) return fail("This tool is not part of this build.");
                engine.oneClick(t);
                return new JSONObject().put("started", true);
            }
            case "cancel": {
                String id = a.optString("tool", "");
                if ("all".equals(id)) engine.cancelAll(); else engine.cancel(Sdm.Tool.of(id));
                return new JSONObject();
            }
            case "delete": {
                Sdm.Tool t = tool(a);
                if (!engine.has(t)) return fail("This tool is not part of this build.");
                Sdm.Selection sel = Sdm.Selection.of(a.optJSONObject("selection"));
                engine.delete(t, sel);
                return new JSONObject().put("started", true);
            }
            case "groups": {
                JSONObject r = engine.groups(tool(a), a.optInt("offset", 0), a.optInt("limit", 200), a.optString("sort", ""), a.optString("q", ""));
                return r;
            }
            case "items": return engine.items(tool(a), a.optString("group", ""), a.optInt("offset", 0), a.optInt("limit", 200));
            case "discard": engine.discard(tool(a)); return new JSONObject();
            case "exclude": return engine.exclude(tool(a), a);
            case "undoExclude": return engine.undoExclude(a.optString("handle", ""));
            case "exclusions": return exclusionList();
            case "exclusionSave": {
                JSONObject e = a.optJSONObject("exclusion");
                if (e == null) throw new IllegalArgumentException("An exclusion is needed");
                String id = engine.exclusions().save(SdmExclusions.fromPage(e));
                engine.exclusions();
                return new JSONObject().put("id", id);
            }
            case "exclusionRemove": {
                JSONArray ids = a.optJSONArray("ids");
                List<String> l = new ArrayList<String>();
                if (ids != null) for (int i = 0; i < ids.length(); i++) l.add(ids.optString(i));
                engine.exclusions().remove(l);
                return new JSONObject().put("removed", l.size());
            }
            case "exclusionsRestoreDefaults": engine.exclusions().restoreDefaults(); return new JSONObject();
            case "exclusionsExport": return new JSONObject().put("json", engine.exclusions().export()).put("count", engine.exclusions().exportCount());
            case "exclusionsImport": return new JSONObject().put("count", engine.exclusions().importJson(a.optString("json", "")));
            case "resolvePkgs": return resolvePkgs(a.optString("q", ""));
            case "settingsGet": return settingsGet(a);
            case "settingsSet": {
                String t = a.optString("tool", "general");
                if (!a.has("key")) throw new IllegalArgumentException("A key is needed");
                if (!a.has("value") || a.isNull("value")) throw new IllegalArgumentException("A value is needed");
                String key = a.getString("key");
                engine.settings().set(t, key, a.get("value"));
                if (SdmSettings.GENERAL.equals(t) && key.startsWith("retention")) { engine.history().prune(host.nowSec() * 1000L); emitHistory(); }
                return new JSONObject().put("values", engine.settings().values(t));
            }
            case "settingsReset": {
                String t = a.optString("tool", "general");
                JSONObject v = engine.settings().reset(t);
                if (SdmSettings.GENERAL.equals(t)) { engine.history().prune(host.nowSec() * 1000L); emitHistory(); }
                return new JSONObject().put("values", v);
            }
            case "historyList": return engine.history().list(a.optInt("offset", 0), a.optInt("limit", 50));
            case "historyPaths": return engine.history().paths(a.optString("id", ""), a.optInt("offset", 0), a.optInt("limit", 200));
            case "historyReset": engine.history().reset(); emitHistory(); return new JSONObject();
            case "historyStats": return engine.history().stats();
            case "acs": {
                JSONObject r = host.acs(a.optString("op", "status"), a);
                JSONObject out = r == null ? new JSONObject() : new JSONObject(r.toString());
                if (!out.has("ok")) out.put("ok", true);
                return out;
            }
            case "runAll": return runAll(a.optString("kind", ""));
            default: return fail("Unknown request: " + op);
        }
    }

    private void emitHistory() {
        history();
    }

    // ---------------------------------------------------------------------------------------------------------- the bigger ops

    private JSONObject areas(JSONObject a) throws JSONException {
        JSONArray arr = new JSONArray();
        for (Sdm.AreaInfo i : host.areas().all()) {
            arr.put(new JSONObject().put("area", i.area.name()).put("label", SdmAreas.label(i.area)).put("root", i.root).put("via", i.via).put("reason", i.reason)
                    .put("available", i.available()).put("primary", i.primary).put("main", SdmAreas.isMainArea(i.area)));
        }
        JSONObject acs = new JSONObject();
        try {
            JSONObject s = host.acs("status", new JSONObject());
            acs.put("enabled", s != null && s.optBoolean("enabled")).put("connected", s != null && s.optBoolean("connected")).put("consent", s != null && s.optBoolean("consent"));
        } catch (RuntimeException e) {
            acs.put("enabled", false).put("connected", false).put("consent", false);
        }
        return new JSONObject().put("areas", arr).put("mode", mode()).put("access", new JSONObject().put("storage", host.hasStorageAccess()).put("usage", host.hasUsageAccess()))
                .put("acs", acs);
    }

    private JSONObject mode() throws JSONException {
        int uid = -1;
        try { Sdm.Shell sh = host.shell(); uid = sh == null ? -1 : sh.uid(); } catch (RuntimeException ignored) {}
        return new JSONObject().put("name", host.modeName()).put("uid", uid).put("privileged", uid >= 0);
    }

    private JSONObject exclusionList() throws JSONException {
        JSONArray arr = new JSONArray();
        for (SdmExclusions.Exclusion e : engine.exclusions().list()) {
            String label = e.label();
            if (SdmExclusions.PKG.equals(e.kind)) {
                try {
                    Sdm.Pkg p = host.pkgs().get(e.pkg);
                    if (p != null && p.label != null && !p.label.isEmpty()) label = p.label;
                } catch (RuntimeException ignored) {}
            }
            arr.put(SdmExclusions.toPage(e, label));
        }
        return new JSONObject().put("list", arr).put("defaultsRemoved", engine.exclusions().defaultsRemoved());
    }

    private JSONObject resolvePkgs(String q) throws JSONException {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Sdm.Pkg> hits = new ArrayList<Sdm.Pkg>();
        for (Sdm.Pkg p : host.pkgs().installed()) {
            if (needle.isEmpty() || p.pkg.toLowerCase(Locale.ROOT).contains(needle) || p.label.toLowerCase(Locale.ROOT).contains(needle)) hits.add(p);
        }
        Collections.sort(hits, new Comparator<Sdm.Pkg>() {
            @Override public int compare(Sdm.Pkg x, Sdm.Pkg y) { return (x.label.isEmpty() ? x.pkg : x.label).compareToIgnoreCase(y.label.isEmpty() ? y.pkg : y.label); }
        });
        JSONArray apps = new JSONArray();
        for (int i = 0; i < hits.size() && i < 50; i++) {
            Sdm.Pkg p = hits.get(i);
            apps.put(new JSONObject().put("pkg", p.pkg).put("label", p.label.isEmpty() ? p.pkg : p.label).put("system", p.system));
        }
        return new JSONObject().put("apps", apps);
    }

    private JSONObject settingsGet(JSONObject a) throws JSONException {
        String t = a.optString("tool", "");
        if (!t.isEmpty()) return new JSONObject().put("values", engine.settings().values(t)).put("meta", SdmSettings.meta(t));
        JSONObject all = new JSONObject();
        all.put(SdmSettings.GENERAL, engine.settings().values(SdmSettings.GENERAL));
        for (Sdm.Tool k : Sdm.Tool.values()) all.put(k.id, engine.settings().values(k.id));
        return new JSONObject().put("all", all);
    }

    /** The main action over the tools (spec 7.6): scan the tools that are not disabled, delete the ones that have data, or one-click the one-tap tools. */
    private JSONObject runAll(String kind) throws JSONException {
        if (!kind.equals("scan") && !kind.equals("delete") && !kind.equals("oneclick")) throw new IllegalArgumentException("kind must be scan, delete or oneclick");
        List<Sdm.Tool> picked = new ArrayList<Sdm.Tool>();
        for (Sdm.Tool t : DASHBOARD_ORDER) {
            if (!engine.has(t)) continue;
            if (kind.equals("oneclick")) { if (engine.settings().oneTapTools().contains(t.id)) picked.add(t); }
            else if (engine.settings().toolEnabled(t)) {
                if (kind.equals("scan") || engine.hasData(t)) picked.add(t);
            }
        }
        JSONArray started = new JSONArray();
        for (Sdm.Tool t : picked) if (engine.busy(t)) return new JSONObject().put("started", started).put("busy", true);      // single flight: a batch is still pending
        for (Sdm.Tool t : picked) {
            if (kind.equals("scan")) engine.scan(t);
            else if (kind.equals("delete")) engine.delete(t, new Sdm.Selection());
            else engine.oneClick(t);
            started.put(t.id);
        }
        return new JSONObject().put("started", started);
    }
}
