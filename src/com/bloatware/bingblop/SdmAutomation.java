package com.bloatware.bingblop;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link Sdm.Automation} on top of {@link SdmAccessService}: clears the cache of apps by driving the system settings. The steps, the plans per ROM and the label
 * tables are {@link SdmAcsPlan}; this class is the phone side of it: the window of the service as nodes, App info opened from the service, the screen state, a
 * cover over the screen with a Cancel button, and the consent that has to be given in the app before the service may act at all.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se (app-common-automation: AutomationService.kt,
 * ui/AutomationOverlay.kt, common/AutomationLabelSource.kt; app-tool-appcleaner: automation/ClearCacheModule.kt). Changed for this port: the cover is a plain
 * window built in code (title, step, Cancel), no SD Maid artwork; consent is a flag of this app's own preferences. Added: the gesture tap lets the cover pass touches
 * through while it is dispatched (upstream's "passthrough"; without it the tap lands on the cover), D-pad keys through the accessibility global actions, {@code input tap} and
 * {@code input keyevent} through the working mode's shell, the screen report of the last failure ({@link #lastFailureJson}, {@link #lastReport}) and the reason the service is not
 * ready ({@link #notReadyReason}).
 */
public final class SdmAutomation implements Sdm.Automation, SdmAcsPlan.AutomationInfo {
    private static final String PREFS = "sdm_acs";
    private static final String KEY_CONSENT = "consent";

    private static final String KEY_LAST = "last_failure", REPORT_FILE = "sdm_acs_report.txt";

    private final Context ctx;
    private final SdmShell shell;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> failures = new HashMap<String, String>();
    /** What the pre-flight of the current deletion left out (package -> NO_SETTINGS | DISABLED_APP), folded into the record of the run that follows it. */
    private final Map<String, String> leftOut = new LinkedHashMap<String, String>();
    private volatile boolean cancelFlag;
    private volatile SdmAcsDiag.Failure lastFailure;
    private volatile String lastReport = "";
    private boolean lastLoaded;

    public SdmAutomation(Context ctx) { this(ctx, null); }

    /** {@code shell}: the working mode's shell, for {@code input tap} / {@code input keyevent} when the accessibility ways do not open a row; null: none. */
    public SdmAutomation(Context ctx, SdmShell shell) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.shell = shell;
    }

    // ---------------------------------------------------------------------------------------------------------- consent and state

    public static boolean hasConsent(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, false);
    }

    public static void setConsent(Context c, boolean on) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean(KEY_CONSENT, on).apply();
    }

    public static String componentName(Context c) {
        return c.getPackageName() + "/" + SdmAccessService.class.getName();
    }

    /** True when Android lists the service as switched on (it may still be starting). */
    public static boolean isEnabledInSettings(Context c) {
        try {
            String v = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (v == null) return false;
            String me = componentName(c), short1 = c.getPackageName() + "/." + SdmAccessService.class.getSimpleName();
            for (String s : v.split(":")) if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(short1)) return true;
        } catch (RuntimeException ignored) {}
        return false;
    }

    public static boolean isConnected() { return SdmAccessService.instance != null; }

    @Override
    public boolean ready() { return isConnected() && hasConsent(ctx); }

    /** Why {@link #ready} is false: NO_CONSENT, NOT_ENABLED (switched off in the accessibility settings) or NOT_CONNECTED (switched on, Android does not run it); null when it is ready. */
    @Override
    public String notReadyReason() {
        if (!hasConsent(ctx)) return "NO_CONSENT";
        if (!isEnabledInSettings(ctx)) return "NOT_ENABLED";
        if (!isConnected()) return "NOT_CONNECTED";
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------- AutomationInfo

    @Override
    public String unreachableReason(String pkg, boolean enabled) {
        if (!enabled) return "DISABLED_APP";
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            if (i.resolveActivity(ctx.getPackageManager()) == null) return "NO_SETTINGS";
        } catch (RuntimeException ignored) {}
        return null;
    }

    @Override
    public synchronized String failureOf(String pkg) { return failures.get(pkg); }

    /**
     * The apps the pre-flight left out are recorded at once (when nothing is left to run, they are the whole outcome of the attempt and the SD Maid tab has to say so), and kept
     * for the run that may follow, whose record then holds both.
     */
    @Override
    public void preflight(Map<String, String> unreachable) {
        synchronized (this) { leftOut.clear(); leftOut.putAll(unreachable); }
        if (unreachable.isEmpty()) return;
        PackageManager pm = ctx.getPackageManager();
        SdmAcsPlan.Result r = new SdmAcsPlan.Result();
        addLeftOut(r, new LinkedHashMap<String, String>(unreachable), pm);
        SdmAcsPlan.Device dev = device(pm);
        record(r, SdmAcsPlan.planFor(SdmAcsPlan.effectiveRom(dev), Build.VERSION.SDK_INT), dev);
    }

    /** Takes what the pre-flight left out, once. */
    private synchronized Map<String, String> takeLeftOut() {
        Map<String, String> m = new LinkedHashMap<String, String>(leftOut);
        leftOut.clear();
        return m;
    }

    /** The left-out apps as failures of the result (code, name, no screen: nothing was opened for them). */
    private void addLeftOut(SdmAcsPlan.Result r, Map<String, String> left, PackageManager pm) {
        for (Map.Entry<String, String> e : left.entrySet()) {
            if (r.cleared.contains(e.getKey())) continue;
            r.failed.put(e.getKey(), e.getValue());
            SdmAcsDiag.Failure f = new SdmAcsDiag.Failure();
            f.pkg = e.getKey();
            f.label = labelOf(pm, e.getKey());
            f.code = e.getValue();
            f.step = "Before the run";
            f.message = "DISABLED_APP".equals(e.getValue()) ? "the app is turned off, so it has no App info page" : "Android has no App info page for it";
            f.when = System.currentTimeMillis();
            r.failures.put(e.getKey(), f);
        }
    }

    private static String labelOf(PackageManager pm, String pkg) {
        try {
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return l == null ? "" : l.toString();
        } catch (PackageManager.NameNotFoundException e) { return ""; } catch (RuntimeException e) { return ""; }
    }

    // ---------------------------------------------------------------------------------------------------------- the run

    @Override
    public Set<String> clearCaches(List<String> pkgs, Sdm.Progress progress, Sdm.Cancel cancel) throws Sdm.AutomationError {
        if (!hasConsent(ctx)) throw new Sdm.AutomationError("NO_CONSENT", "Accessibility service is not set up. Complete the setup and give consent.", null);
        final SdmAccessService svc = SdmAccessService.instance;
        if (svc == null) throw new Sdm.AutomationError("NO_CONSENT", "Accessibility service is not running. Turn it on, or try rebooting the device.", null);
        cancelFlag = false;
        synchronized (this) { failures.clear(); }
        final Map<String, String> left = takeLeftOut();                  // the pre-flight of this deletion; read once, so an earlier one never reaches a later run
        final Cover cover = new Cover(svc, new Runnable() { @Override public void run() { cancelFlag = true; } });
        cover.show();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<SdmAcsPlan.PkgInfo> infos = new ArrayList<SdmAcsPlan.PkgInfo>();
            for (String p : pkgs) infos.add(info(pm, p));
            SdmAcsPlan.Device dev = device(pm);
            SdmAcsPlan.Plan plan = SdmAcsPlan.planFor(SdmAcsPlan.effectiveRom(dev), Build.VERSION.SDK_INT);
            SdmAcsPlan.Labels labels;
            try { labels = SdmAcsPlan.Labels.fromJson(SdmAppSieve.readAsset("sdm/acs_labels.json")); } catch (Exception e) { labels = SdmAcsPlan.Labels.fallback(); }
            PhoneHost host = new PhoneHost(svc, cover, cancel);
            SdmAcsPlan.Result r = SdmAcsPlan.clearAll(host, new SdmAcsPlan.Timing(), labels, new Source(pm), plan, infos, progress);
            synchronized (this) { failures.putAll(r.failed); }
            addLeftOut(r, left, pm);                                     // after the run's own failures: a real problem stays the headline
            record(r, plan, dev);
            if (r.stop != null) {
                String code = "SCREEN_UNAVAILABLE".equals(r.stop) ? "SCREEN_UNAVAILABLE" : "ERROR";
                throw new Sdm.AutomationError(code, r.stopMessage == null ? "The automation stopped." : r.stopMessage, r.cleared);
            }
            return new LinkedHashSet<String>(r.cleared);
        } finally {
            cover.hide();
        }
    }

    private SdmAcsPlan.PkgInfo info(PackageManager pm, String pkg) {
        SdmAcsPlan.PkgInfo p = new SdmAcsPlan.PkgInfo();
        p.pkg = pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            p.label = l == null ? pkg : l.toString();
            p.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            p.enabled = ai.enabled;
            if (!p.label.equals(pkg)) p.identifiers.add(p.label);
            addIdentifiers(pm, ai, p);
            String reason = unreachableReason(pkg, ai.enabled);
            p.hasNoSettings = "NO_SETTINGS".equals(reason);
        } catch (PackageManager.NameNotFoundException e) {
            p.label = pkg;
        }
        return p;
    }

    /** The other names Settings may show for the app (upstream checkIdentifiers): its label in each of the user's languages, the launcher label, the application class. */
    private void addIdentifiers(PackageManager pm, ApplicationInfo ai, SdmAcsPlan.PkgInfo p) {
        try {
            if (ai.labelRes != 0) {
                Resources base = pm.getResourcesForApplication(ai);
                for (String tag : new Source(pm).locales()) {
                    Configuration c = new Configuration(base.getConfiguration());
                    c.setLocale(Locale.forLanguageTag(tag));
                    @SuppressWarnings("deprecation")
                    Resources r = new Resources(base.getAssets(), base.getDisplayMetrics(), c);
                    try { String l = r.getString(ai.labelRes); if (l != null && !l.isEmpty() && !p.identifiers.contains(l)) p.identifiers.add(l); } catch (Resources.NotFoundException ignored) {}
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {} catch (RuntimeException ignored) {}
        try {
            Intent li = pm.getLaunchIntentForPackage(p.pkg);
            if (li != null && li.getComponent() != null) {
                CharSequence l = pm.getActivityInfo(li.getComponent(), 0).loadLabel(pm);
                if (l != null && l.length() > 0 && !p.identifiers.contains(l.toString())) p.identifiers.add(l.toString());
            }
        } catch (PackageManager.NameNotFoundException ignored) {} catch (RuntimeException ignored) {}
        if (ai.className != null && !ai.className.isEmpty() && !p.identifiers.contains(ai.className)) p.identifiers.add(ai.className);
    }

    private SdmAcsPlan.Device device(PackageManager pm) {
        SdmAcsPlan.Device d = new SdmAcsPlan.Device();
        d.manufacturer = String.valueOf(Build.MANUFACTURER);
        d.brand = String.valueOf(Build.BRAND);
        d.display = String.valueOf(Build.DISPLAY);
        d.product = String.valueOf(Build.PRODUCT);
        d.incremental = String.valueOf(Build.VERSION.INCREMENTAL);
        d.sdk = Build.VERSION.SDK_INT;
        d.tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        String[] probe = { "com.miui.securitycenter", "org.lineageos.lineagesettings", "lineageos.platform", "org.lineageos.settings.device", "com.coloros.simsettings",
                "com.coloros.filemanager", "com.meizu.flyme.update", "com.funtouch.uiengine" };
        for (String s : probe) {
            try { PackageInfo pi = pm.getPackageInfo(s, 0); if (pi != null) d.installed.add(s); } catch (PackageManager.NameNotFoundException ignored) {} catch (RuntimeException ignored) {}
        }
        return d;
    }

    // ---------------------------------------------------------------------------------------------------------- the last failure and its screen report

    /** What the last run came to, for the SD Maid tab: the first app that failed (its code, name, window, plan, step), or the stop; a run with no failure clears it. */
    private void record(SdmAcsPlan.Result r, SdmAcsPlan.Plan plan, SdmAcsPlan.Device dev) {
        SdmAcsDiag.Failure first = r.headline();
        if (first == null && r.stop != null) {
            first = new SdmAcsDiag.Failure();
            first.code = "SCREEN_UNAVAILABLE".equals(r.stop) ? "SCREEN_UNAVAILABLE" : "GAVE_UP";
            first.message = r.stopMessage == null ? "" : r.stopMessage;
            first.plan = plan.name();
            first.when = System.currentTimeMillis();
        }
        String device = String.valueOf(Build.MANUFACTURER) + " " + String.valueOf(Build.MODEL) + ", Android " + String.valueOf(Build.VERSION.RELEASE) + " (API " + Build.VERSION.SDK_INT
                + "), " + plan.name() + " plan (ROM " + SdmAcsPlan.effectiveRom(dev) + ")";
        StringBuilder rep = new StringBuilder();
        if (first != null) {
            for (SdmAcsDiag.Failure f : r.failures.values()) {
                if (SdmAcsPlan.expectedLimit(f.code) && f != first) continue;               // what Android allows is not worth a screen dump
                if (rep.length() > 0) rep.append("\n----\n");
                rep.append(f.toReport(device));
            }
            if (rep.length() == 0) rep.append(first.toReport(device));
            if (r.stop != null && r.stopMessage != null) rep.append("\nRun stopped: ").append(r.stop).append(" - ").append(SdmAcsDiag.clip(r.stopMessage, 400)).append('\n');
        }
        synchronized (this) {
            lastLoaded = true;
            lastFailure = first;
            lastReport = rep.toString();
        }
        persist(first, rep.toString());
    }

    private void persist(SdmAcsDiag.Failure f, String report) {
        try {
            SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            File file = new File(ctx.getFilesDir(), REPORT_FILE);
            if (f == null) { e.remove(KEY_LAST).apply(); //noinspection ResultOfMethodCallIgnored
                file.delete(); return; }
            e.putString(KEY_LAST, meta(f).toString()).apply();
            FileOutputStream out = new FileOutputStream(file);
            try { out.write(report.getBytes("UTF-8")); } finally { out.close(); }
        } catch (IOException ignored) {} catch (RuntimeException ignored) {} catch (JSONException ignored) {}
    }

    private static JSONObject meta(SdmAcsDiag.Failure f) throws JSONException {
        return new JSONObject().put("code", f.code).put("pkg", f.pkg).put("label", f.label.isEmpty() ? f.pkg : f.label).put("window", f.windowPkg).put("plan", f.plan).put("step", f.step).put("when", f.when);
    }

    private synchronized void loadLast() {
        if (lastLoaded) return;
        lastLoaded = true;
        try {
            String m = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null);
            if (m == null) return;
            JSONObject o = new JSONObject(m);
            SdmAcsDiag.Failure f = new SdmAcsDiag.Failure();
            f.code = o.optString("code"); f.pkg = o.optString("pkg"); f.label = o.optString("label"); f.windowPkg = o.optString("window"); f.plan = o.optString("plan"); f.step = o.optString("step"); f.when = o.optLong("when");
            File file = new File(ctx.getFilesDir(), REPORT_FILE);
            String rep = "";
            if (file.isFile() && file.length() < 2_000_000) {
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                try { java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int k; while ((k = in.read(buf)) > 0) bos.write(buf, 0, k); rep = new String(bos.toByteArray(), "UTF-8"); } finally { in.close(); }
            }
            lastFailure = f; lastReport = rep;
        } catch (IOException ignored) {} catch (RuntimeException ignored) {} catch (JSONException ignored) {}
    }

    /** {code, pkg, label, window, plan, step, when, hasReport} of the last failure, or null when the last run had none. The code is one of SdmAcsPlan's (NO_PROGRESS, STORAGE_NOT_FOUND ...). */
    public JSONObject lastFailureJson() {
        loadLast();
        SdmAcsDiag.Failure f = lastFailure;
        if (f == null) return null;
        try { return meta(f).put("hasReport", !lastReport.isEmpty()); } catch (JSONException e) { return null; }
    }

    /** The text to copy: the screen report of the last failure (labels of the Settings page, what was tried); empty when there is none. */
    public String lastReport() { loadLast(); return lastReport; }

    // ---------------------------------------------------------------------------------------------------------- the strings of the Settings app, in the user's languages

    private static final class Source implements SdmAcsPlan.LabelSource {
        private final PackageManager pm;
        Source(PackageManager pm) { this.pm = pm; }

        @Override public List<String> locales() {
            List<String> out = new ArrayList<String>();
            try {
                android.os.LocaleList ll = Resources.getSystem().getConfiguration().getLocales();
                for (int i = 0; i < ll.size(); i++) out.add(ll.get(i).toLanguageTag());
            } catch (RuntimeException ignored) {}
            if (out.isEmpty()) out.add(Locale.getDefault().toLanguageTag());
            return out;
        }

        @Override public List<String> resourceStrings(String pkg, String... names) {
            List<String> out = new ArrayList<String>();
            try {
                Resources base = pm.getResourcesForApplication(pkg);
                for (String tag : locales()) {
                    Configuration c = new Configuration(base.getConfiguration());
                    c.setLocale(Locale.forLanguageTag(tag));
                    @SuppressWarnings("deprecation")
                    Resources r = new Resources(base.getAssets(), base.getDisplayMetrics(), c);
                    for (String n : names) {
                        int id = r.getIdentifier(n, "string", pkg);
                        if (id == 0) continue;
                        try { String s = r.getString(id); if (s != null && !s.isEmpty() && !out.contains(s)) out.add(s); } catch (Resources.NotFoundException ignored) {}
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (RuntimeException ignored) {}
            return out;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the phone as the engine's host

    private final class PhoneHost implements SdmAcsPlan.Host {
        final SdmAccessService svc;
        final Cover cover;
        final Sdm.Cancel cancel;
        PhoneHost(SdmAccessService svc, Cover cover, Sdm.Cancel cancel) { this.svc = svc; this.cover = cover; this.cancel = cancel; }

        @Override public SdmAcsPlan.Node root() {
            try {
                AccessibilityNodeInfo r = svc.getRootInActiveWindow();
                return r == null ? null : new W(r);
            } catch (RuntimeException e) { return null; }
        }

        private volatile String launchError;

        @Override public void launchAppInfo(String pkg) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_HISTORY);
            launchError = null;
            try { svc.startActivity(i); } catch (RuntimeException e) { launchError = e.toString(); }
        }

        @Override public String launchError() { String e = launchError; launchError = null; return e; }

        @Override public void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        @Override public long now() { return SystemClock.uptimeMillis(); }

        @Override public boolean screenAvailable() {
            try {
                PowerManager pw = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
                KeyguardManager km = (KeyguardManager) svc.getSystemService(Context.KEYGUARD_SERVICE);
                return pw != null && pw.isInteractive() && (km == null || !km.isKeyguardLocked());
            } catch (RuntimeException e) { return true; }
        }

        @Override public boolean cancelled() { return cancelFlag || (cancel != null && cancel.cancelled()); }

        @Override public void dismissDialogs() {
            try { svc.performGlobalAction(Build.VERSION.SDK_INT >= 31 ? AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE : AccessibilityService.GLOBAL_ACTION_BACK); } catch (RuntimeException ignored) {}
        }

        /** A tap at screen coordinates through the service's gesture. The cover is made touch-through for the moment (upstream's "passthrough"): else the tap lands on the cover. */
        @Override public boolean tap(int x, int y) {
            cover.passthrough(true);
            try {
                Path p = new Path();
                p.moveTo(x, y);
                p.lineTo(x + 1f, y + 1f);
                GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, ViewConfiguration.getTapTimeout())).build();
                final CountDownLatch done = new CountDownLatch(1);
                final boolean[] ok = { false };
                boolean sent = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) { ok[0] = true; done.countDown(); }
                    @Override public void onCancelled(GestureDescription d) { done.countDown(); }
                }, main);
                if (!sent) return false;
                try { done.await(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                return ok[0];
            } catch (RuntimeException e) {
                return false;
            } finally {
                cover.passthrough(false);
            }
        }

        @Override public boolean dpadGlobal(int keyCode) {
            if (Build.VERSION.SDK_INT < 33) return false;
            int a;
            switch (keyCode) {
                case 19: a = AccessibilityService.GLOBAL_ACTION_DPAD_UP; break;
                case 20: a = AccessibilityService.GLOBAL_ACTION_DPAD_DOWN; break;
                case 21: a = AccessibilityService.GLOBAL_ACTION_DPAD_LEFT; break;
                case 22: a = AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT; break;
                case 23: a = AccessibilityService.GLOBAL_ACTION_DPAD_CENTER; break;
                default: return false;
            }
            try { return svc.performGlobalAction(a); } catch (RuntimeException e) { return false; }
        }

        @Override public boolean canInject() { return shell != null && shell.privileged(); }
        @Override public boolean injectKey(int keyCode) { return runInput("input keyevent " + keyCode); }
        @Override public boolean injectTap(int x, int y) { return runInput("input tap " + x + " " + y); }

        /** One fixed {@code input} command (numbers only: nothing from the screen is put into it) in the working mode; false when it was not run or printed an error. */
        private boolean runInput(String cmd) {
            if (shell == null || !shell.privileged()) return false;
            try {
                String out = shell.run(cmd, 6000);
                return out == null || !(out.contains("Exception") || out.contains("denied") || out.contains("not found") || out.contains("Error"));
            } catch (IOException e) {
                return false;
            }
        }

        @Override public int sdk() { return Build.VERSION.SDK_INT; }
        @Override public int screenWidthPx() { return svc.getResources().getDisplayMetrics().widthPixels; }
        @Override public float density() { return svc.getResources().getDisplayMetrics().density; }
        @Override public void status(String text) { cover.status(text); }
    }

    /** An accessibility node as the plan's {@link SdmAcsPlan.Node}. */
    private static final class W implements SdmAcsPlan.Node {
        final AccessibilityNodeInfo n;
        W(AccessibilityNodeInfo n) { this.n = n; }
        private static String s(CharSequence c) { return c == null ? null : c.toString(); }
        @Override public String text() { return s(n.getText()); }
        @Override public String contentDesc() { return s(n.getContentDescription()); }
        @Override public String className() { return s(n.getClassName()); }
        @Override public String packageName() { return s(n.getPackageName()); }
        @Override public String viewId() { return n.getViewIdResourceName(); }
        @Override public boolean clickable() { return n.isClickable(); }
        @Override public boolean enabled() { return n.isEnabled(); }
        @Override public boolean scrollable() { return n.isScrollable(); }
        @Override public SdmAcsPlan.Node parent() { AccessibilityNodeInfo p = n.getParent(); return p == null ? null : new W(p); }
        @Override public int childCount() { return n.getChildCount(); }
        @Override public SdmAcsPlan.Node child(int i) { AccessibilityNodeInfo c = n.getChild(i); return c == null ? null : new W(c); }
        @Override public boolean click() { return n.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
        @Override public boolean scroll(boolean forward) { return n.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); }
        @Override public boolean refresh() { return n.refresh(); }
        @Override public SdmAcsPlan.Node findFocus() {
            AccessibilityNodeInfo f = n.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) f = n.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            return f == null ? null : new W(f);
        }
        @Override public int[] bounds() { Rect r = new Rect(); n.getBoundsInScreen(r); return new int[]{r.left, r.top, r.right, r.bottom}; }
        @Override public boolean equals(Object o) { return o instanceof W && ((W) o).n.equals(n); }
        @Override public int hashCode() { return n.hashCode(); }
    }

    // ---------------------------------------------------------------------------------------------------------- the cover over the screen

    /** A window over everything while the service taps through Settings: what it is doing, and a Cancel button (spec 3.7.1: always a way out). */
    private final class Cover {
        final AccessibilityService svc;
        final Runnable onCancel;
        View view;
        TextView step;
        WindowManager.LayoutParams params;

        Cover(AccessibilityService svc, Runnable onCancel) { this.svc = svc; this.onCancel = onCancel; }

        void show() {
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
                        LinearLayout box = new LinearLayout(svc);
                        box.setOrientation(LinearLayout.VERTICAL);
                        box.setGravity(Gravity.CENTER);
                        box.setBackgroundColor(Color.argb(235, 8, 10, 15));
                        int pad = (int) (24 * svc.getResources().getDisplayMetrics().density);
                        box.setPadding(pad, pad, pad, pad);
                        TextView title = new TextView(svc);
                        title.setText("AppCleaner automation");
                        title.setTextColor(Color.WHITE);
                        title.setTextSize(20);
                        title.setGravity(Gravity.CENTER);
                        TextView sub = new TextView(svc);
                        sub.setText("This app is tapping through the settings of your apps. It is not possible to use the screen at the same time. Wait until it is finished or cancel it.");
                        sub.setTextColor(Color.LTGRAY);
                        sub.setGravity(Gravity.CENTER);
                        sub.setPadding(0, pad / 2, 0, pad / 2);
                        step = new TextView(svc);
                        step.setTextColor(Color.rgb(0, 229, 255));
                        step.setGravity(Gravity.CENTER);
                        Button cancel = new Button(svc);
                        cancel.setText("Cancel");
                        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onCancel.run(); } });
                        box.addView(title);
                        box.addView(sub);
                        box.addView(step);
                        box.addView(cancel);
                        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                PixelFormat.TRANSLUCENT);
                        wm.addView(box, lp);
                        params = lp;
                        view = box;
                    } catch (RuntimeException e) {
                        view = null;                       // no cover (no permission to draw it): the automation still runs, Cancel is then the notification's
                    }
                }
            });
        }

        /** Lets touches go through the cover (on) or takes them again (off); returns when the window has been updated, or after half a second. */
        void passthrough(final boolean on) {
            final CountDownLatch done = new CountDownLatch(1);
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (view != null && params != null) {
                            params.flags = on ? params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : params.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                            ((WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(view, params);
                        }
                    } catch (RuntimeException ignored) {
                    } finally { done.countDown(); }
                }
            });
            try { done.await(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        void status(final String text) {
            main.post(new Runnable() { @Override public void run() { if (step != null) step.setText(text); } });
        }

        void hide() {
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (view != null) ((WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)).removeView(view);
                    } catch (RuntimeException ignored) {}
                    view = null;
                }
            });
        }
    }
}
