package com.bloatware.bingblop;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The phone side of the SD Maid SE tab: what {@link SdmBridge} asks of the app (the working mode's shell, the data areas, the apps, the accessibility service,
 * the way back to the page), built from the few things MainActivity has to give ({@link Hooks}). It also keeps the progress notification: while a tool works
 * the app shows what it does and a Cancel button, and stays alive in the background through {@link JobService}.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se; this class is the port's own glue (spec 8.5).
 */
final class SdmHost implements SdmBridge.Host {

    /** What MainActivity gives. */
    interface Hooks {
        /** The working mode now: adb_tcp, adb_wireless, shizuku, root or standard. */
        String mode();
        /** Starts the script in the working mode (stdout and stderr merged), without reading it. */
        Process open(String script) throws IOException;
        /** Sends a line of JavaScript to the page. */
        void js(String code);
        boolean storage();
        boolean usage();
    }

    private final Context ctx;
    private final Hooks hooks;
    private final SdmShell shell;
    private final SdmAreas areas;
    private final SdmFs fs;
    private final SdmPackagesAndroid pkgs;
    private final SdmAutomation automation;
    private final Runnable cancelAll = new Runnable() { @Override public void run() { if (bridge != null) bridge.engine().cancelAll(); } };
    private volatile SdmBridge bridge;
    private boolean notifying;

    SdmHost(Context context, final Hooks hooks) {
        this.ctx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.hooks = hooks;
        SdmAppSieve.setAssets(new SdmAppSieve.Assets() {
            @Override public String read(String path) throws IOException {
                InputStream in = ctx.getAssets().open(path);
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    return new String(bos.toByteArray(), "UTF-8");
                } finally { in.close(); }
            }
        });
        this.shell = new SdmShell(new SdmShell.ModeRunner() {
            @Override public String mode() { return hooks.mode(); }
            @Override public Process open(String script) throws IOException { return hooks.open(script); }
        });
        SdmFsJava javaFs = new SdmFsJava();
        SdmAreas.Config cfg = new SdmAreas.Config();
        cfg.sdkInt = Build.VERSION.SDK_INT;
        cfg.ownPackage = ctx.getPackageName();
        List<String> roots = SdmAreas.discoverPublicRoots(javaFs, "/storage/emulated/0");
        if (roots != null && !roots.isEmpty()) cfg.publicRoots = roots;
        this.areas = new SdmAreas(shell, javaFs, cfg, new SdmAreas.Access() { @Override public boolean storage() { return hooks.storage(); } });
        this.fs = new SdmFs(areas, javaFs, new SdmFsShell(shell));
        this.pkgs = new SdmPackagesAndroid(ctx, shell);
        this.automation = new SdmAutomation(ctx);
        this.bridge = new SdmBridge(this);
    }

    SdmBridge bridge() { return bridge; }

    // ---------------------------------------------------------------------------------------------------------- Host

    @Override public Sdm.Fs fs() { return fs; }
    @Override public Sdm.Areas areas() { return areas; }
    @Override public Sdm.Packages pkgs() { return pkgs; }
    @Override public Sdm.Shell shell() { return shell; }
    @Override public Sdm.Automation automation() { return automation.ready() ? automation : null; }
    @Override public String modeName() { return hooks.mode(); }
    @Override public boolean hasStorageAccess() { return hooks.storage(); }
    @Override public boolean hasUsageAccess() { return hooks.usage(); }
    @Override public File filesDir() { return ctx.getFilesDir(); }

    @Override
    public void emit(String json) {
        hooks.js("window.onSdm && window.onSdm(" + json + ")");
        try { track(new JSONObject(json)); } catch (JSONException ignored) {}
    }

    /** The notification: starts with the first task, follows the progress, ends with the last one. */
    private void track(JSONObject ev) {
        SdmBridge b = bridge;
        if (b == null) return;
        String kind = ev.optString("ev");
        if ("progress".equals(kind)) {
            JSONObject p = ev.optJSONObject("progress") != null ? ev.optJSONObject("progress") : ev;
            if (!notifying || p.optBoolean("queued")) return;
            long max = p.optLong("max"), cur = p.optLong("current");
            int pct = max > 0 && !"indeterminate".equals(p.optString("countType")) && !"none".equals(p.optString("countType")) ? (int) Math.min(100, 100 * cur / max) : -1;
            String line = ev.optString("tool") + ": " + p.optString("primary") + (p.optString("secondary").isEmpty() ? "" : " - " + p.optString("secondary"));
            JobService.progress(ctx, line, pct);
        } else if ("state".equals(kind) || "done".equals(kind)) {
            JSONObject st = b.engine().states();
            boolean active = st.optInt("running") + st.optInt("queued") > 0;
            if (active && !notifying) { notifying = true; JobService.begin(ctx, "SD Maid SE", cancelAll); }
            else if (!active && notifying) {
                notifying = false;
                JobService.end(ctx, cancelAll, "done".equals(kind) ? ev.optJSONObject("d") != null ? doneText(ev.optJSONObject("d")) : "" : "");
            }
        }
    }

    private static String doneText(JSONObject d) {
        String p = d.optString("primary"), s = d.optString("secondary");
        return (p + (s.isEmpty() ? "" : " " + s)).trim();
    }

    // ---------------------------------------------------------------------------------------------------------- the accessibility service

    @Override
    public JSONObject acs(String op, JSONObject args) {
        String note = "";
        try {
            if ("consent".equals(op)) SdmAutomation.setConsent(ctx, true);
            else if ("revoke".equals(op)) SdmAutomation.setConsent(ctx, false);
            else if ("openSettings".equals(op)) {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } else if ("enableViaShell".equals(op)) {
                note = enableViaShell();
            }
        } catch (RuntimeException e) {
            note = "That did not work: " + e.getMessage();
        }
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", SdmAutomation.isEnabledInSettings(ctx));
            o.put("connected", SdmAutomation.isConnected());
            o.put("consent", SdmAutomation.hasConsent(ctx));
            if (!note.isEmpty()) o.put("note", note);
        } catch (JSONException ignored) {}
        return o;
    }

    /** With a working mode: switches the service on through the secure settings, keeping the services that are already on. Needs the consent first. */
    private String enableViaShell() {
        if (!SdmAutomation.hasConsent(ctx)) return "Give consent first.";
        if (!SdmShell.isPrivilegedMode(hooks.mode())) return "Needs a working mode (ADB, Wireless Debugging, Shizuku or Root).";
        try {
            String me = SdmAutomation.componentName(ctx);
            String cur = shell.run("settings get secure enabled_accessibility_services", 8000).trim();
            if (cur.contains("Error") || cur.contains("Exception")) return "The phone refused to read the list of services.";
            if (cur.equals("null") || cur.isEmpty()) cur = "";
            for (String s : cur.split(":")) if (s.equalsIgnoreCase(me)) cur = null;
            if (cur != null) {
                String list = cur.isEmpty() ? me : cur + ":" + me;
                String out = shell.run("settings put secure enabled_accessibility_services " + BackupScripts.quote(list) + "; settings put secure accessibility_enabled 1", 8000);
                if (out.contains("Exception") || out.contains("denied")) return "The phone refused: " + out.trim();
            }
            return SdmAutomation.isEnabledInSettings(ctx) ? "" : "Done. If the service does not start, turn it on in the accessibility settings (on Android 13 and newer, allow restricted settings for this app first).";
        } catch (IOException e) {
            return "That did not work: " + e.getMessage();
        }
    }
}
