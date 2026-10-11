package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * (app-common-automation: automation/core/specs/AutomationExplorer.kt, common/stepper/Stepper.kt, StepperExtensions.kt, specs/SpecGeneratorExtensions.kt,
 * common/ACSNodeInfoExtensions.kt, common/AutomationLabelSource.kt, errors/*; app-tool-appcleaner: automation/ClearCacheModule.kt, ClearCacheLoop.kt,
 * specs/StorageEntryFinder.kt, specs/AppCleanerSpecGeneratorExtensions.kt, specs/aosp/*, specs/oneui/*, specs/miui/*, specs/hyperos/*, the label tables of every ROM;
 * app-common-pkgs: NoSettingsDetector.kt; app-common: device/DeviceDetective.kt).
 * Changed for this port: the step engine is plain Java behind a tiny node and host abstraction ({@link Node}, {@link Host}) so that it runs on a JDK against a fake
 * screen; coroutines and the accessibility event flow are replaced by polling the window root every 100 ms; all the timeouts live in {@link Timing} so a test can turn
 * them down; the label tables are read from assets/sdm/acs_labels.json (machine-extracted from the Kotlin files and compared with an independent extraction for
 * AOSP / One UI / the data-driven ROMs); the Android 16/17 DPAD fallback, the input injector and the app-locker interference detector are not ported.
 *
 * Plans: {@link #AOSP} (the default, also Lineage), {@link #ONEUI} (Samsung) and {@link #MIUI} / {@link #HYPEROS} (Xiaomi, the security-center plan) are written out; ColorOS,
 * Realme UI, OxygenOS, Huawei, Honor, LG, Nubia, Funtouch OS, OriginOS, Alcatel, Doogee and Oukitel are the AOSP flow with the label tables of their ROM (data driven,
 * {@link Plan#experimental} = true: they cannot be tested without the devices). Android TV and Flyme have no plan here and fail with an unsupported error.
 * No android.* in this file.
 */
public final class SdmAcsPlan {
    private SdmAcsPlan() {}

    /** The ROM the user forced in the settings ("AUTO" = detect). The values of upstream's RomType: AUTO, AOSP, SAMSUNG, MIUI, HYPEROS, ONEPLUS, ... */
    public static volatile String romOverride = "AUTO";

    // ---------------------------------------------------------------------------------------------------------- the optional extra of an Automation

    /** Optional extra of an {@link Sdm.Automation}: why a package cannot be reached, and why the last attempt failed. */
    public interface AutomationInfo {
        /** null when the settings page of the package can be opened; "NO_SETTINGS" or "DISABLED_APP" otherwise. */
        String unreachableReason(String pkg, boolean enabled);
        /** Why the last attempt for the package failed: "NO_SETTINGS", "DISABLED_APP", "LOCKED" (the system disabled the button) or "OTHER"; null when unknown. */
        String failureOf(String pkg);
    }

    // ---------------------------------------------------------------------------------------------------------- the screen

    /** One node of the accessibility tree. */
    public interface Node {
        String text();
        String contentDesc();
        String className();
        String packageName();
        String viewId();
        boolean clickable();
        boolean enabled();
        boolean scrollable();
        Node parent();
        int childCount();
        Node child(int i);
        /** ACTION_CLICK; true when it was dispatched. */
        boolean click();
        /** ACTION_SCROLL_FORWARD / BACKWARD. */
        boolean scroll(boolean forward);
        boolean refresh();
        /** left, top, right, bottom on the screen. */
        int[] bounds();
    }

    /** What the engine needs from the phone. */
    public interface Host {
        /** The root of the active window, or null. */
        Node root();
        /** Opens the App info page of the package. */
        void launchAppInfo(String pkg);
        void sleep(long ms);
        long now();
        /** Screen on and unlocked. */
        boolean screenAvailable();
        /** The user pressed Cancel on the overlay. */
        boolean cancelled();
        /** Closes the notification shade / system dialogs (from the third attempt on). */
        void dismissDialogs();
        /** A tap at screen coordinates (last resort for a node that cannot be clicked); false when it could not be done. */
        boolean tap(int x, int y);
        int sdk();
        int screenWidthPx();
        float density();
        /** The text of the current step for the overlay ("Searching for "Storage" entry (keywords: ...)"). */
        void status(String text);
    }

    /** The user's languages and the strings of other apps' resources (the Settings APK) in each of them. */
    public interface LabelSource {
        /** Language tags in the user's order: "en-US", "de", "zh-Hant-TW". */
        List<String> locales();
        /** The strings of these resource names in the package, for every locale in order (names that do not exist are left out). */
        List<String> resourceStrings(String pkg, String... names);
    }

    /** What the engine needs to know about the app it clears. */
    public static final class PkgInfo {
        public String pkg = "", label = "";
        public boolean system, enabled = true, hasNoSettings;
        /** Other names the Settings page can show for the app (the labels in every locale, the launcher label, the application class). */
        public final List<String> identifiers = new ArrayList<String>();
        /** The texts Settings can show for the size of the app (formatFileSize, short, 1000-based, with , and . swapped); empty when it cannot be told. */
        public final List<String> sizeTexts = new ArrayList<String>();
    }

    // ---------------------------------------------------------------------------------------------------------- errors

    /** Ends the plan of this app (upstream PlanAbortException and its subclasses). code: NO_SETTINGS | DISABLED_APP | LOCKED | OTHER. */
    public static class PlanAbort extends RuntimeException {
        public final String code;
        public final boolean treatAsSuccess;
        public PlanAbort(String code, String message, boolean treatAsSuccess) { super(message); this.code = code; this.treatAsSuccess = treatAsSuccess; }
    }

    /** Ends the step without a retry; a deliberate skip when treatAsSuccess. */
    public static class StepAbort extends RuntimeException {
        public final boolean treatAsSuccess;
        public StepAbort(String message, boolean treatAsSuccess) { super(message); this.treatAsSuccess = treatAsSuccess; }
    }

    public static final class ScreenUnavailable extends RuntimeException { public ScreenUnavailable() { super("Screen is unavailable!"); } }
    public static final class UserCancelled extends RuntimeException { public UserCancelled() { super("User has cancelled automation"); } }
    public static final class Timeout extends RuntimeException { public Timeout(String m) { super(m); } }
    /** A disabled button (upstream DisabledTargetException). */
    public static final class DisabledTarget extends RuntimeException { public DisabledTarget(String m) { super(m); } }
    public static final class Unclickable extends RuntimeException { public Unclickable(String m) { super(m); } }

    /** The give-up budget: this many targets may fail with an unusable automation (timeout, unretryable step abort) while there is still no success. */
    public static final int FAILURE_LIMIT = 8;

    // ---------------------------------------------------------------------------------------------------------- timing

    /** All the waits of the engine (spec 3.7.3). */
    public static final class Timing {
        public long planTimeout = 30000, stepTimeout = 40000, attemptTimeout = 15000, windowCheckTimeout = 4000, retryDelay = 300, launchDelay = 50;
        public long recoveryDelay = 200, plainDelay = 100, busyDelay = 1000, pollDelay = 50, planReplays = 3, scrollSettle = 1000, dialogWait = 3000, sizeWait = 250;
        public static Timing scaled(double f) {
            Timing t = new Timing();
            t.planTimeout = (long) (t.planTimeout * f); t.stepTimeout = (long) (t.stepTimeout * f); t.attemptTimeout = (long) (t.attemptTimeout * f);
            t.windowCheckTimeout = (long) (t.windowCheckTimeout * f); t.retryDelay = Math.max(1, (long) (t.retryDelay * f)); t.launchDelay = Math.max(1, (long) (t.launchDelay * f));
            t.recoveryDelay = Math.max(1, (long) (t.recoveryDelay * f)); t.plainDelay = Math.max(1, (long) (t.plainDelay * f)); t.busyDelay = Math.max(1, (long) (t.busyDelay * f));
            t.pollDelay = Math.max(1, (long) (t.pollDelay * f)); t.scrollSettle = Math.max(1, (long) (t.scrollSettle * f)); t.dialogWait = Math.max(1, (long) (t.dialogWait * f));
            t.sizeWait = Math.max(1, (long) (t.sizeWait * f));
            return t;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- node helpers (ACSNodeInfoExtensions)

    static String nb(String s) { return s == null ? null : s.replace(' ', ' '); }

    public static boolean textMatches(Node n, String candidate) {
        String t = n.text();
        if (t == null || candidate == null) return false;
        return t.equalsIgnoreCase(candidate) || nb(t).equalsIgnoreCase(candidate) || t.equalsIgnoreCase(nb(candidate)) || nb(t).equalsIgnoreCase(nb(candidate));
    }

    public static boolean textMatchesAny(Node n, Collection<String> c) { for (String s : c) if (textMatches(n, s)) return true; return false; }

    public static boolean textContains(Node n, String part) {
        String t = n.text();
        if (t == null) return false;
        return t.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT)) || nb(t).toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    public static boolean textEndsWithAny(Node n, Collection<String> c) {
        String t = n.text();
        if (t == null) return false;
        for (String s : c) if (t.toLowerCase(Locale.ROOT).endsWith(s.toLowerCase(Locale.ROOT)) || nb(t).toLowerCase(Locale.ROOT).endsWith(s.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    public static boolean contentDescMatches(Node n, String candidate) {
        String t = n.contentDesc();
        return t != null && candidate != null && (t.equalsIgnoreCase(candidate) || nb(t).equalsIgnoreCase(candidate));
    }

    public static boolean idContains(Node n, String part) { String id = n.viewId(); return id != null && id.contains(part); }

    public static boolean isClickyButton(Node n) { return n.clickable() && "android.widget.Button".equals(n.className()); }

    public static boolean isTextView(Node n) { return "android.widget.TextView".equals(n.className()); }

    /** Depth-first, first child first (upstream crawl()); refreshes the root when it has no first child. */
    public static List<Node> crawl(Node root) {
        List<Node> out = new ArrayList<Node>();
        if (root == null) return out;
        try { if (root.childCount() > 0 && root.child(0) == null) root.refresh(); } catch (RuntimeException ignored) { /* the tree changed under us */ }
        java.util.ArrayDeque<Node> stack = new java.util.ArrayDeque<Node>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node cur = stack.pop();
            out.add(cur);
            for (int i = cur.childCount() - 1; i >= 0; i--) { Node c = cur.child(i); if (c != null) stack.push(c); }
        }
        return out;
    }

    /** The first label in priority order that a node (in document order) matches; earlier labels win whatever their position. */
    public static Node findByLabel(List<Node> tree, Collection<String> labels, NodePredicate pred) {
        for (String label : labels) for (Node n : tree) if (textMatches(n, label) && (pred == null || pred.ok(n))) return n;
        return null;
    }

    public static Node findByContentDesc(List<Node> tree, Collection<String> labels, NodePredicate pred) {
        for (String label : labels) for (Node n : tree) if (contentDescMatches(n, label) && (pred == null || pred.ok(n))) return n;
        return null;
    }

    public interface NodePredicate { boolean ok(Node n); }

    public static Node clickableParent(Node n, int maxNesting, boolean includeSelf) {
        if (includeSelf && n.clickable()) return n;
        Node t = n.parent();
        for (int i = 1; i <= maxNesting && t != null; i++) { if (t.clickable()) return t; t = t.parent(); }
        return null;
    }

    public static Node clickableSibling(Node n, int maxNesting) {
        Node parent = n.parent();
        for (int k = 0; k < maxNesting && parent != null; k++) {
            for (int i = 0; i < parent.childCount(); i++) { Node s = parent.child(i); if (s != null && !s.equals(n) && s.clickable()) return s; }
            parent = parent.parent();
        }
        return null;
    }

    public static Node rootOf(Node n, int maxNesting) { Node t = n; for (int i = 0; i < maxNesting; i++) { Node p = t.parent(); if (p == null) break; t = p; } return t; }

    static boolean boundsEmpty(int[] b) { return b == null || b.length < 4 || b[0] >= b[2] || b[1] >= b[3]; }

    // ---------------------------------------------------------------------------------------------------------- labels (AutomationLabelSource + the tables)

    /** The label tables of every ROM (assets/sdm/acs_labels.json: {rom: {file: {function: {dynamicResourceKeys, staticByLanguage}}}}). */
    public static final class Labels {
        private final JSONObject roms;
        private Labels(JSONObject roms) { this.roms = roms; }

        public static Labels fromJson(String json) throws JSONException { return new Labels(new JSONObject(json)); }

        /** Only the English labels of the AOSP flow, for a phone where the asset cannot be read. */
        public static Labels fallback() {
            try {
                return fromJson("{\"aosp\":{\"AOSPLabels14Plus.kt\":{\"getStorageEntryDynamic\":{\"dynamicResourceKeys\":[\"storage_settings\"]},"
                        + "\"getStorageEntryStatic\":{\"staticByLanguage\":{\"*\":[\"Storage\",\"Storage space\",\"Storage & memory\"]}},"
                        + "\"getClearCacheDynamic\":{\"dynamicResourceKeys\":[\"clear_cache_btn_text\"]},\"getClearCacheStatic\":{\"staticByLanguage\":{\"*\":[\"Clear cache\"]}}},"
                        + "\"AOSPLabels29Plus.kt\":{\"getStorageEntryDynamic\":{\"dynamicResourceKeys\":[\"storage_settings_for_app\"]},"
                        + "\"getStorageEntryStatic\":{\"staticByLanguage\":{\"*\":[\"Storage & cache\",\"Storage and cache\",\"Storage usage\"]}},"
                        + "\"getComputingSizeDynamic\":{\"dynamicResourceKeys\":[\"computing_size\"]},\"getCacheSizeLabelDynamic\":{\"dynamicResourceKeys\":[\"cache_size_label\"]}}}}");
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }

        public boolean has(String rom) { return roms.has(rom); }

        /** The files of a ROM that apply on this SDK ("...29Plus.kt" from API 29; for a ROM with several steps (one of them per SDK) only the highest one). */
        List<String> files(String rom, int sdk, boolean highestOnly) {
            List<String> out = new ArrayList<String>();
            JSONObject r = roms.optJSONObject(rom);
            if (r == null) return out;
            int best = -1; String bestFile = null;
            for (Iterator<String> it = r.keys(); it.hasNext(); ) {
                String f = it.next();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)Plus\\.kt$").matcher(f);
                if (!m.find()) { out.add(f); continue; }
                int n = Integer.parseInt(m.group(1));
                if (n > sdk) continue;
                if (highestOnly) { if (n > best) { best = n; bestFile = f; } } else out.add(f);
            }
            if (bestFile != null) out.add(bestFile);
            java.util.Collections.sort(out);
            return out;
        }

        /**
         * Every list of this ROM whose function name starts with the prefix ("getStorageEntry", "getClearCache", "getDialogTitles"): the strings of the Settings APK in the user's
         * languages first (dynamicResourceKeys), then the static texts of the user's languages, as upstream's labels(resNames, staticByLang).
         */
        public List<String> collect(LabelSource src, String rom, int sdk, boolean highestOnly, String prefix, String resourcePkg) {
            Set<String> dyn = new LinkedHashSet<String>(), stat = new LinkedHashSet<String>();
            JSONObject r = roms.optJSONObject(rom);
            if (r == null) return new ArrayList<String>();
            for (String file : files(rom, sdk, highestOnly)) {
                JSONObject fo = r.optJSONObject(file);
                if (fo == null) continue;
                List<String> fns = new ArrayList<String>();
                for (Iterator<String> it = fo.keys(); it.hasNext(); ) fns.add(it.next());
                for (String fn : fns) {
                    if (!fn.startsWith(prefix)) continue;
                    JSONObject def = fo.optJSONObject(fn);
                    if (def == null) continue;
                    JSONArray keys = def.optJSONArray("dynamicResourceKeys");
                    if (keys != null && keys.length() > 0 && src != null) {
                        String[] names = new String[keys.length()];
                        for (int i = 0; i < names.length; i++) names[i] = keys.optString(i);
                        dyn.addAll(src.resourceStrings(resourcePkg, names));
                    }
                    JSONObject by = def.optJSONObject("staticByLanguage");
                    if (by != null && src != null) stat.addAll(staticFor(by, src.locales()));
                }
            }
            List<String> out = new ArrayList<String>(dyn);
            for (String s : stat) if (!dyn.contains(s)) out.add(s);
            return out;
        }

        /** The static texts for the user's languages (zh by script, "*" for a language without an entry). */
        static List<String> staticFor(JSONObject byLang, List<String> locales) {
            List<String> out = new ArrayList<String>();
            for (String tag : locales) {
                Locale loc = Locale.forLanguageTag(tag);
                String lang = loc.getLanguage(), script = loc.getScript(), country = loc.getCountry();
                if (lang.equals("zh") && script.isEmpty()) script = (country.equals("TW") || country.equals("HK") || country.equals("MO")) ? "Hant" : "Hans";
                JSONArray arr = null;
                if (!script.isEmpty()) arr = byLang.optJSONArray(lang + "-" + script);
                if (arr == null) arr = byLang.optJSONArray(lang);
                if (arr == null) arr = byLang.optJSONArray("*");
                if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
            }
            return out;
        }
    }

    static List<String> plus(List<String> a, List<String> b) { LinkedHashSet<String> s = new LinkedHashSet<String>(a); s.addAll(b); return new ArrayList<String>(s); }

    // ---------------------------------------------------------------------------------------------------------- the ROM

    /** What the ROM detection looks at (upstream BuildWrap + the installed apps). */
    public static final class Device {
        public String manufacturer = "", brand = "", display = "", product = "", incremental = "";
        public int sdk = 30;
        public boolean tv;
        public final Set<String> installed = new HashSet<String>();
        boolean has(String pkg) { return installed.contains(pkg); }
    }

    /** DeviceDetective.getROMType (spec 3.7.2), by the names of the RomType enum. */
    public static String detectRom(Device d) {
        String man = d.manufacturer.toLowerCase(Locale.ROOT), brand = d.brand.toLowerCase(Locale.ROOT);
        String display = d.display.toLowerCase(Locale.ROOT), product = d.product.toLowerCase(Locale.ROOT);
        if (d.tv && !man.equals("ugoos")) return "ANDROID_TV";
        if (display.contains("lineage") || product.contains("lineage") || d.has("org.lineageos.lineagesettings") || d.has("lineageos.platform") || d.has("org.lineageos.settings.device")) return "LINEAGE";
        if (brand.equals("alcatel")) return "ALCATEL";
        if (man.equals("oppo") && (d.has("com.coloros.simsettings") || d.has("com.coloros.filemanager"))) return "COLOROS";
        if (man.equals("meizu") && d.has("com.meizu.flyme.update")) return "FLYME";
        if (man.equals("huawei") && d.has("com.miui.securitycenter")) return "HUAWEI";
        if (man.equals("lge")) return "LGE";
        if (man.equals("xiaomi") || man.equals("poco") || man.equals("blackshark")) {
            String inc = d.incremental;
            if (inc.startsWith("V816.") || inc.startsWith("OS1") || inc.startsWith("OS2") || inc.startsWith("OS3")) return d.sdk >= 33 ? "HYPEROS" : "MIUI";
            if (d.has("com.miui.securitycenter") && (inc.startsWith("V10") || inc.startsWith("V11") || inc.startsWith("V12") || inc.startsWith("V13") || inc.startsWith("V14"))) return "MIUI";
            return "AOSP";
        }
        if (man.equals("nubia")) return "NUBIA";
        if (man.equals("oneplus")) return "ONEPLUS";
        if (man.equals("realme")) return "REALME";
        if (man.equals("samsung")) return "SAMSUNG";
        if (man.equals("vivo")) return d.sdk < 30 || d.has("com.funtouch.uiengine") || product.contains("eea") ? "VIVO" : "ORIGINOS";
        if (man.equals("honor")) return "HONOR";
        if (man.equals("doogee")) return "DOOGEE";
        if (man.equals("oukitel")) return "OUKITEL";
        return "AOSP";
    }

    /** The ROM that decides the plan: the override of the settings, else the detected one. */
    public static String effectiveRom(Device d) {
        String o = romOverride;
        return o == null || o.isEmpty() || o.equals("AUTO") ? detectRom(d) : o;
    }

    // ---------------------------------------------------------------------------------------------------------- the engine

    /** One step of a plan (upstream AutomationStep). */
    public static final class Step {
        public String label = "";
        public boolean launch;
        /** Decides whether the window is the one we wait for; null: any root. */
        public Check windowCheck;
        public Recovery recovery;
        public Action action;
    }
    public interface Check { boolean ok(Node root, int attempt); }
    public interface Action { boolean act(Node root, int attempt); }
    public interface Recovery { boolean recover(Node root, int attempt); }

    /** Everything one plan run works with. */
    public static final class Run {
        public final Host host;
        public final Timing timing;
        public final Labels labels;
        public final LabelSource src;
        public final PkgInfo pkg;
        public Run(Host host, Timing timing, Labels labels, LabelSource src, PkgInfo pkg) { this.host = host; this.timing = timing; this.labels = labels; this.src = src; this.pkg = pkg; }

        void guard() {
            if (host.cancelled()) throw new UserCancelled();
            if (!host.screenAvailable()) throw new ScreenUnavailable();
        }

        /** The active window root, waiting until there is one that passes the check (upstream windowCheck) or the timeout is over. */
        Node awaitRoot(Check check, int attempt, long timeoutMs) {
            long end = host.now() + timeoutMs;
            while (true) {
                guard();
                Node r = host.root();
                if (r != null && (check == null || check.ok(r, attempt))) return r;
                if (host.now() >= end) throw new Timeout("window check timed out");
                host.sleep(timing.pollDelay);
            }
        }

        /** One step: launch, window check, node action loop; failed attempts are repeated until the step timeout (upstream Stepper.process). */
        public void step(Step s) {
            host.status(s.label);
            long stepEnd = host.now() + timing.stepTimeout;
            int attempts = 0;
            while (true) {
                guard();
                int attempt = attempts++;
                try {
                    attempt(s, attempt);
                    return;
                } catch (PlanAbort e) {
                    throw e;
                } catch (StepAbort e) {
                    if (!e.treatAsSuccess) throw e;
                    return;
                } catch (ScreenUnavailable e) {
                    throw e;
                } catch (UserCancelled e) {
                    throw e;
                } catch (RuntimeException e) {
                    // a failed attempt: wait and run the step again
                    if (host.now() >= stepEnd) throw new Timeout("step timed out: " + s.label);
                    host.sleep(timing.retryDelay);
                }
                if (host.now() >= stepEnd) throw new Timeout("step timed out: " + s.label);
            }
        }

        private void attempt(Step s, int attempt) {
            long end = host.now() + timing.attemptTimeout;
            if (attempt > 1) host.dismissDialogs();
            if (s.launch) host.launchAppInfo(pkg.pkg);
            host.sleep(timing.launchDelay);
            Node root = awaitRoot(s.windowCheck, attempt, timing.windowCheckTimeout);
            if (s.action == null) return;
            while (true) {
                guard();
                root = host.root() != null ? host.root() : root;
                if (s.action.act(root, attempt)) return;
                if (host.now() >= end) throw new Timeout("nodeAction failed");
                if (s.recovery != null) { s.recovery.recover(root, attempt); host.sleep(timing.recoveryDelay); } else host.sleep(timing.plainDelay);
            }
        }

        // ---- shared pieces of the plans

        /** The identifiers of the app on the Settings page: its package name, its labels. A window passes when one of them is in a node's text. */
        public boolean mentionsApp(Node root) {
            List<String> ids = new ArrayList<String>(pkg.identifiers);
            ids.add(pkg.pkg);
            if (!pkg.label.isEmpty()) ids.add(pkg.label);
            for (Node n : crawl(root)) {
                String t = n.text();
                if (t == null) continue;
                for (String id : ids) if (!id.isEmpty() && (t.equals(id) || t.contains(id))) return true;
            }
            return false;
        }

        /** Busy node ("Computing..." under 30 characters) -> wait; else scroll forward, back when nothing moved (upstream defaultNodeRecovery). */
        public Recovery nodeRecovery(final Collection<String> extraBusy) {
            final int[] lastAttempt = { -1 };
            final boolean[] lastForward = { false };
            return new Recovery() {
                @Override public boolean recover(Node root, int attempt) {
                    if (attempt != lastAttempt[0]) { lastAttempt[0] = attempt; lastForward[0] = false; }
                    List<Node> tree = crawl(root);
                    for (Node n : tree) {
                        String t = n.text();
                        if (t == null || t.length() > 30) continue;
                        if (t.contains("...") || t.contains("…") || (extraBusy != null && !extraBusy.isEmpty() && textMatchesAny(n, extraBusy))) {
                            host.sleep(timing.busyDelay);
                            root.refresh();
                            return true;
                        }
                    }
                    boolean scrolled = false, forward = false;
                    for (Node n : tree) if (n.scrollable() && n.scroll(true)) { scrolled = true; forward = true; n.refresh(); }
                    if (!scrolled && !lastForward[0]) {
                        for (Node n : tree) if (n.scrollable() && n.scroll(false)) { scrolled = true; n.refresh(); }
                    }
                    if (scrolled) host.sleep(Math.min(timing.scrollSettle, 100));
                    lastForward[0] = forward;
                    return scrolled;
                }
            };
        }

        /** Click as upstream's clickNormal. */
        public boolean clickNormal(Node n) {
            if (!n.enabled()) throw new DisabledTarget("Clickable target is disabled.");
            if (n.clickable()) return n.click();
            throw new Unclickable("Target is not clickable");
        }

        /** A tap in the centre of the node; refused when its bounds are empty. */
        public boolean clickGesture(Node n) {
            int[] b = n.bounds();
            if (boundsEmpty(b)) return false;
            return host.tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- plans

    /** A way to clear the cache of one app on one ROM. */
    public interface Plan {
        String name();
        boolean experimental();
        /** Runs the whole plan for the app of the run; throws the errors above. */
        void run(Run r);
    }

    public static final String SETTINGS_PKG = "com.android.settings", SECURITY_CENTER = "com.miui.securitycenter";

    /** The text a Settings storage row's size takes: a text matches when it contains one of the texts at the start or after a space ("80 MB" is not in "1.80 MB"). */
    static boolean sizeMatches(Node n, List<String> sizeTexts) {
        String t = n.text();
        if (t == null || sizeTexts.isEmpty()) return false;
        for (String cand : new String[] { t, nb(t) }) {
            for (String target : sizeTexts) {
                int i = cand.indexOf(target);
                if (i == -1) continue;
                if (i == 0 || Character.isWhitespace(cand.charAt(i - 1))) return true;
            }
        }
        return false;
    }

    static boolean hasRowTitleIn(Node n, Collection<String> anti) {
        if (anti.isEmpty()) return false;
        Node row = clickableParent(n, 6, false);
        if (row == null) return false;
        for (Node w : crawl(row)) if (idContains(w, "android:id/title") && textMatchesAny(w, anti)) return true;
        return false;
    }

    /** The "Storage" row of the App info page (upstream StorageEntryFinder.storageFinderAOSP). */
    static Node findStorageEntry(Run r, Node root, List<String> labels, List<String> anti) {
        final List<Node> matches = new ArrayList<Node>();
        final List<Integer> prio = new ArrayList<Integer>();
        boolean api33 = r.host.sdk() >= 33;
        for (Node n : crawl(root)) {
            int p = -1;
            if (api33) {
                if (textMatchesAny(n, labels)) p = 0;
                else if (isTextView(n) && sizeMatches(n, r.pkg.sizeTexts) && !hasRowTitleIn(n, anti)) p = 1;
            } else if (isTextView(n)) {
                if (idContains(n, "android:id/title") && textMatchesAny(n, labels)) p = 0;
                else if (idContains(n, "android:id/summary") && sizeMatches(n, r.pkg.sizeTexts) && !hasRowTitleIn(n, anti)) p = 1;
            }
            if (p >= 0) { matches.add(n); prio.add(p); }
        }
        // stable sort by priority
        List<Node> sorted = new ArrayList<Node>();
        for (int p = 0; p <= 1; p++) for (int i = 0; i < matches.size(); i++) if (prio.get(i) == p) sorted.add(matches.get(i));
        if (sorted.size() == 2) {
            Node a = sorted.get(0), b = sorted.get(1);
            if (a.parent() != null && a.parent().equals(b.parent())) sorted.remove(1);      // title and summary of the same row
        }
        // the left pane of a two-pane layout is a false positive
        for (Iterator<Node> it = sorted.iterator(); it.hasNext(); ) {
            if (sorted.size() < 2) break;
            Node n = it.next();
            Node p = n.parent();
            boolean left = false;
            for (int i = 0; i < 11 && p != null && !left; i++, p = p.parent()) left = idContains(p, "ll_landleft") || idContains(p, "left_fragment");
            if (left) it.remove();
        }
        float dpWidth = r.host.density() > 0 ? r.host.screenWidthPx() / r.host.density() : 0;
        if (dpWidth >= 600) {
            int mid = r.host.screenWidthPx() / 2;
            for (Iterator<Node> it = sorted.iterator(); it.hasNext(); ) {
                if (sorted.size() < 2) break;
                Node n = it.next();
                Node gp = n.parent() == null ? null : n.parent().parent();
                if (gp != null) { int[] b = gp.bounds(); if (b != null && b.length >= 4 && b[2] < mid - 50) it.remove(); }
            }
        }
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    /** "1.23 MB" -> 0 only when the number is zero; any other number is "something". null when there is no number. */
    static Long cacheSizeOf(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*([0-9][0-9.,  ]*)").matcher(text);
        if (!m.find()) return null;
        String num = m.group(1).replaceAll("[^0-9]", "");
        if (num.isEmpty()) return null;
        for (int i = 0; i < num.length(); i++) if (num.charAt(i) != '0') return 1L;
        return 0L;
    }

    static String rowSummary(Node title) {
        Node cur = title.parent();
        for (int up = 0; up < 2 && cur != null; up++) {
            for (int i = 0; i < cur.childCount(); i++) {
                Node c = cur.child(i);
                if (c != null && "android:id/summary".equals(c.viewId())) return c.text();
            }
            cur = cur.parent();
        }
        return null;
    }

    /** How the Clear cache label is turned into the node to click. */
    public static final int TARGET_AOSP = 0, TARGET_ANY_THEN_PARENT = 1, TARGET_CLICKY_BUTTON = 2;

    /** The AOSP flow and every ROM that reuses it with its own labels: App info > Storage > Clear cache. */
    static final class AospPlan implements Plan {
        final String name, rom;
        final boolean experimental;
        final int target;
        final boolean oneUiLabels;
        final boolean highestOnly;
        final int parentNesting;

        AospPlan(String name, String rom, boolean experimental, int target, boolean highestOnly, int parentNesting) {
            this.name = name; this.rom = rom; this.experimental = experimental; this.target = target; this.highestOnly = highestOnly; this.parentNesting = parentNesting;
            this.oneUiLabels = rom.equals("oneui");
        }
        @Override public String name() { return name; }
        @Override public boolean experimental() { return experimental; }

        List<String> storageLabels(Run r) {
            int sdk = r.host.sdk();
            List<String> own = rom.equals("aosp") ? new ArrayList<String>() : r.labels.collect(r.src, rom, sdk, highestOnly, "getStorageEntry", SETTINGS_PKG);
            List<String> aosp = r.labels.collect(r.src, "aosp", sdk, true, "getStorageEntry", SETTINGS_PKG);
            return plus(own, aosp);
        }

        List<String> clearLabels(Run r) {
            int sdk = r.host.sdk();
            List<String> own = rom.equals("aosp") ? new ArrayList<String>() : r.labels.collect(r.src, rom, sdk, false, "getClearCache", SETTINGS_PKG);
            List<String> aosp = r.labels.collect(r.src, "aosp", sdk, false, "getClearCache", SETTINGS_PKG);
            return plus(own, aosp);
        }

        @Override public void run(Run r) { go(r, true); }

        /** The same plan when the App info page is already open (the MIUI settings variant). */
        void runFromStorage(Run r) { go(r, false); }

        void go(final Run r, boolean launch) {
            final List<String> storage = storageLabels(r);
            final List<String> clear = clearLabels(r);
            final List<String> computing = r.labels.collect(r.src, "aosp", r.host.sdk(), false, "getComputingSize", SETTINGS_PKG);
            final List<String> cacheRow = plus(r.labels.collect(r.src, "aosp", r.host.sdk(), false, "getCacheSizeLabel", SETTINGS_PKG), Arrays.asList("Cache"));
            final List<String> anti = oneUiLabels ? r.labels.collect(r.src, "oneui", r.host.sdk(), false, "getMobileDataAnti", SETTINGS_PKG) : new ArrayList<String>();

            Step s1 = new Step();
            s1.label = "Searching for \"Storage\" entry (keywords: " + join(storage) + ")";
            s1.launch = launch;
            s1.windowCheck = settingsWindow(r);
            s1.recovery = r.nodeRecovery(computing);
            s1.action = new Action() {
                @Override public boolean act(Node root, int attempt) {
                    Node found = findStorageEntry(r, root, storage, anti);
                    if (found == null) return false;
                    Node t = clickableParent(found, parentNesting, true);
                    return t != null && r.clickNormal(t);
                }
            };
            r.step(s1);

            final int[] attempts = { 0 }, zeroStreak = { 0 };
            final int[] lastVerdict = { 0 };
            Step s2 = new Step();
            s2.label = "Looking for \"Clear cache\" button (keywords: " + join(clear) + ")";
            s2.windowCheck = new Check() { @Override public boolean ok(Node root, int attempt) { return SETTINGS_PKG.equals(root.packageName()); } };
            s2.action = new Action() {
                @Override public boolean act(Node root, int attempt) {
                    int a = attempts[0]++;
                    List<Node> tree = crawl(root);
                    Node cand = findByLabel(tree, clear, null);
                    if (cand == null && r.host.sdk() >= 36) cand = findByContentDesc(tree, clear, new NodePredicate() { @Override public boolean ok(Node n) { return n.clickable(); } });
                    if (cand != null) {
                        Node t = resolveTarget(r, cand);
                        if (t != null) return clickClearCache(r, t, attempt);
                    }
                    // nothing to click: Settings shows the cache size on this page; an empty cache is a success, a system app without a button is locked
                    boolean due = a >= 2 && (a - 2) % 10 == 0;
                    if (due) {
                        Node title = findByLabel(tree, cacheRow, new NodePredicate() { @Override public boolean ok(Node n) { return "android:id/title".equals(n.viewId()); } });
                        Long size = title == null ? null : cacheSizeOf(rowSummary(title));
                        int verdict = size == null ? 0 : size == 0 ? 1 : (r.pkg.system ? 2 : 0);
                        if (verdict != 0 && verdict == lastVerdict[0]) {
                            if (verdict == 1) throw new StepAbort("Cache is already empty for " + r.pkg.pkg, true);
                            throw new PlanAbort("LOCKED", "No clear cache button: " + r.pkg.pkg, false);
                        }
                        lastVerdict[0] = verdict;
                    }
                    return false;
                }
            };
            r.step(s2);
        }

        Node resolveTarget(Run r, Node cand) {
            if (target == TARGET_CLICKY_BUTTON) return isClickyButton(cand) ? cand : null;
            if (target == TARGET_ANY_THEN_PARENT) return cand.clickable() ? cand : clickableParent(cand, 6, false);
            if (isClickyButton(cand)) return cand;
            if (cand.clickable() && !isTextView(cand)) return cand;
            Node p = clickableParent(cand, 6, false);
            if (p != null) return p;
            return clickableSibling(cand, 1);
        }

        /** upstream clickClearCache: a disabled button is a 0 byte cache, a locked system app, a size that is still being computed, or stale information. */
        boolean clickClearCache(Run r, Node node, int attempt) {
            try {
                return r.clickNormal(node);
            } catch (DisabledTarget e) {
                if ("android.widget.Button".equals(node.className()) && !node.clickable() && !node.enabled()) return true;      // zero bytes
                boolean allDisabled = true;
                try { for (Node n : crawl(rootOf(node, 4))) if (isClickyButton(n) && n.enabled()) { allDisabled = false; break; } } catch (RuntimeException ex) { allDisabled = false; }
                if (r.host.sdk() >= 30 && r.pkg.system) throw new PlanAbort("LOCKED", "Locked system app, can't clear cache: " + r.pkg.pkg, false);
                if (allDisabled) { r.host.sleep(r.timing.sizeWait * (attempt + 1)); return false; }          // still computing the size
                return true;                                                                                  // only this button is disabled: stale information
            }
        }
    }

    static String join(List<String> l) { StringBuilder b = new StringBuilder(); for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(", "); b.append(l.get(i)); } return b.toString(); }

    /** The window is the Settings app and it names the app. */
    static Check settingsWindow(final Run r) {
        return new Check() {
            @Override public boolean ok(Node root, int attempt) {
                if (attempt >= 1 && r.pkg.hasNoSettings) throw new PlanAbort("NO_SETTINGS", r.pkg.pkg + " has no settings window.", false);
                return SETTINGS_PKG.equals(root.packageName()) && r.mentionsApp(root);
            }
        };
    }

    /** MIUI / HyperOS: the window is the security center (clear data > clear cache sheet > confirm) or Settings (the AOSP flow with a clicky button). */
    static final class MiuiPlan implements Plan {
        final boolean hyper;
        MiuiPlan(boolean hyper) { this.hyper = hyper; }
        @Override public String name() { return hyper ? "HyperOS" : "MIUI"; }
        @Override public boolean experimental() { return false; }

        @Override public void run(final Run r) {
            final String rom = hyper ? "hyperos" : "miui";
            final int sdk = r.host.sdk();
            final List<String> clearData = r.labels.collect(r.src, rom, sdk, false, "getClearDataButton", SECURITY_CENTER);
            final List<String> clearCache = r.labels.collect(r.src, rom, sdk, false, "getClearCacheButton", SECURITY_CENTER);
            final List<String> dialogTitles = r.labels.collect(r.src, rom, sdk, false, "getDialogTitles", SECURITY_CENTER);
            final List<String> clearAll = hyper ? r.labels.collect(r.src, rom, sdk, false, "getClearAllDataButton", SECURITY_CENTER) : new ArrayList<String>();
            final List<String> manage = hyper ? r.labels.collect(r.src, rom, sdk, false, "getManageSpaceButton", SECURITY_CENTER) : new ArrayList<String>();

            final String[] windowPkg = { null };
            Step open = new Step();
            open.label = "Searching for \"Storage\" entry (keywords: )";
            open.launch = true;
            open.windowCheck = new Check() {
                @Override public boolean ok(Node root, int attempt) {
                    if (attempt >= 1 && r.pkg.hasNoSettings) throw new PlanAbort("NO_SETTINGS", r.pkg.pkg + " has no settings window.", false);
                    String p = root.packageName();
                    if (!SECURITY_CENTER.equals(p) && !SETTINGS_PKG.equals(p)) return false;
                    if (!r.mentionsApp(root)) return false;
                    windowPkg[0] = p;
                    return true;
                }
            };
            r.step(open);
            if (SETTINGS_PKG.equals(windowPkg[0])) {
                new AospPlan("MIUI settings", "aosp", false, TARGET_CLICKY_BUTTON, true, 6).runFromStorage(r);
                return;
            }
            securityCenter(r, clearData, clearCache, dialogTitles, clearAll, manage);
        }

        void securityCenter(final Run r, final List<String> clearData, final List<String> clearCache, final List<String> dialogTitles, final List<String> clearAll, final List<String> manage) {
            final boolean[] useAlternative = { false };
            Step data = new Step();
            data.label = "Looking for \"Clear data\" button (keywords: " + join(clearData) + ")";
            data.windowCheck = new Check() { @Override public boolean ok(Node root, int attempt) { return SECURITY_CENTER.equals(root.packageName()); } };
            data.recovery = r.nodeRecovery(null);
            data.action = new Action() {
                @Override public boolean act(Node root, int attempt) {
                    List<Node> tree = crawl(root);
                    if (findByLabel(tree, clearCache, null) != null) { useAlternative[0] = true; throw new StepAbort("Got 'Clear cache' instead of 'Clear data'", true); }
                    if (hyper) {
                        if (findByLabel(tree, clearAll, null) != null && findByLabel(tree, clearData, null) == null) throw new PlanAbort("OTHER", "Got 'Clear all data'. App has no cache", true);
                        if (findByLabel(tree, manage, null) != null && findByLabel(tree, clearData, null) == null) throw new PlanAbort("OTHER", "Got 'Manage space'. Skipping", true);
                    }
                    Node t = findByLabel(tree, clearData, null);
                    if (t == null) return false;
                    if (!t.clickable()) { t = clickableParent(t, 3, false); if (t == null) return false; }
                    try {
                        return r.clickNormal(t);
                    } catch (DisabledTarget e) {
                        if (r.pkg.system) throw new PlanAbort("LOCKED", "Clear data button disabled for system app " + r.pkg.pkg, false);
                        throw e;
                    }
                }
            };
            r.step(data);

            Step second = new Step();
            if (useAlternative[0]) {
                second.label = "Looking for \"Clear cache\" button (keywords: " + join(clearCache) + ")";
                second.windowCheck = data.windowCheck;
                second.action = new Action() {
                    @Override public boolean act(Node root, int attempt) {
                        Node t = findByLabel(crawl(root), clearCache, null);
                        if (t == null) return false;
                        if (!t.clickable()) { t = clickableParent(t, 3, false); if (t == null) return false; }
                        return r.clickNormal(t);
                    }
                };
            } else {
                // the bottom sheet: wait until a "Clear cache" node is there and its bounds stopped moving for a moment
                final int[][] lastBounds = { null };
                final long[] stable = { 0 };
                second.label = "Looking for \"Clear cache\" button (keywords: " + join(clearCache) + ")";
                second.windowCheck = new Check() {
                    @Override public boolean ok(Node root, int attempt) {
                        if (!SECURITY_CENTER.equals(root.packageName())) return false;
                        List<Node> tree = crawl(root);
                        boolean title = false;
                        for (Node n : tree) if (idContains(n, "id/alertTitle")) { title = true; break; }
                        Node btn = findByLabel(tree, clearCache, null);
                        if (!title && btn == null) return false;
                        if (btn == null) return false;
                        int[] b = btn.bounds();
                        if (lastBounds[0] != null && Arrays.equals(lastBounds[0], b)) { if (r.host.now() - stable[0] >= (hyper ? 100 : 100)) return true; }
                        else { lastBounds[0] = b; stable[0] = r.host.now(); }
                        return false;
                    }
                };
                second.action = new Action() {
                    @Override public boolean act(Node root, int attempt) {
                        Node t = findByLabel(crawl(root), clearCache, null);
                        if (t == null) return false;
                        return t.clickable() ? r.clickNormal(t) : r.clickGesture(t);
                    }
                };
            }
            r.step(second);

            // the confirmation dialog; some versions clear without asking
            final long[] seenSince = { -1 };
            Step confirm = new Step();
            confirm.label = "Trying to confirm previous action (keywords: )";
            confirm.windowCheck = new Check() {
                @Override public boolean ok(Node root, int attempt) {
                    if (seenSince[0] < 0) seenSince[0] = r.host.now();
                    if (SECURITY_CENTER.equals(root.packageName())) {
                        for (Node n : crawl(root)) {
                            if (!idContains(n, "id/alertTitle")) continue;
                            if (textMatchesAny(n, dialogTitles)) return true;
                            List<String> loose = new ArrayList<String>();
                            for (String t : dialogTitles) { loose.add(t.replace("?", "")); loose.add(t + "?"); }
                            if (textMatchesAny(n, loose)) return true;
                            List<String> q = new ArrayList<String>();
                            for (String c : clearCache) q.add(c + "?");
                            if (textEndsWithAny(n, q) || textEndsWithAny(n, clearCache)) return true;
                        }
                    }
                    if (r.host.now() - seenSince[0] >= r.timing.dialogWait) throw new PlanAbort("OTHER", "Confirmation dialog not found. Cache was likely cleared without confirmation.", true);
                    return false;
                }
            };
            confirm.action = new Action() {
                @Override public boolean act(Node root, int attempt) {
                    for (Node n : crawl(root)) if (isClickyButton(n) && "android:id/button1".equals(n.viewId())) return r.clickNormal(n);
                    return false;
                }
            };
            r.step(confirm);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the plans by ROM

    public static final Plan AOSP = new AospPlan("AOSP", "aosp", false, TARGET_AOSP, true, 6);
    public static final Plan ONEUI = new AospPlan("One UI", "oneui", false, TARGET_ANY_THEN_PARENT, false, 6);
    public static final Plan MIUI = new MiuiPlan(false);
    public static final Plan HYPEROS = new MiuiPlan(true);

    /** A plan that cannot work here (no step written for this ROM). */
    static final class Unsupported implements Plan {
        final String name;
        Unsupported(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public boolean experimental() { return true; }
        @Override public void run(Run r) { throw new PlanAbort("OTHER", "This system language is not supported (" + name + ")", false); }
    }

    /** The plan for a ROM (RomType names) on an SDK; ROMs that start later than their plan fall back to AOSP. */
    public static Plan planFor(String rom, int sdk) {
        if (rom == null) rom = "AOSP";
        switch (rom) {
            case "MIUI": return MIUI;
            case "HYPEROS": return HYPEROS;
            case "SAMSUNG": return ONEUI;
            case "REALME": return new AospPlan("Realme UI", "realme", true, sdk >= 35 ? TARGET_ANY_THEN_PARENT : TARGET_CLICKY_BUTTON, true, 3);
            case "COLOROS": return sdk >= 26 ? new AospPlan("ColorOS", "coloros", true, sdk >= 35 ? TARGET_ANY_THEN_PARENT : TARGET_CLICKY_BUTTON, true, 6) : AOSP;
            case "ONEPLUS": return new AospPlan("OxygenOS", "oxygenos", true, sdk >= 34 ? TARGET_ANY_THEN_PARENT : TARGET_CLICKY_BUTTON, true, 6);
            case "HUAWEI": return sdk >= 29 ? new AospPlan("Huawei", "huawei", true, TARGET_CLICKY_BUTTON, true, 7) : AOSP;
            case "HONOR": return new AospPlan("Honor", "honor", true, TARGET_AOSP, true, 6);
            case "LGE": return sdk >= 29 ? new AospPlan("LG UX", "lge", true, TARGET_AOSP, true, 6) : AOSP;
            case "ALCATEL": return sdk >= 29 ? new AospPlan("Alcatel", "aosp", true, TARGET_AOSP, true, 6) : AOSP;
            case "NUBIA": return sdk >= 29 ? new AospPlan("Nubia", "nubia", true, TARGET_AOSP, true, 6) : AOSP;
            case "VIVO": return new AospPlan("Funtouch OS", "funtouchos", true, TARGET_ANY_THEN_PARENT, false, 4);
            case "ORIGINOS": return new AospPlan("OriginOS", "originos", true, TARGET_ANY_THEN_PARENT, false, 4);
            case "DOOGEE": return new AospPlan("Doogee", "aosp", true, TARGET_AOSP, true, 6);
            case "OUKITEL": return new AospPlan("Oukitel", "aosp", true, TARGET_AOSP, true, 6);
            case "FLYME": return new Unsupported("Flyme");
            case "ANDROID_TV": return new Unsupported("Android TV");
            default: return AOSP;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the loop over the apps (ClearCacheLoop + the plan timeout of AutomationExplorer)

    /** What a run over several apps came to. */
    public static final class Result {
        public final Set<String> cleared = new LinkedHashSet<String>();
        public final Map<String, String> failed = new LinkedHashMap<String, String>();     // package -> NO_SETTINGS | DISABLED_APP | LOCKED | OTHER
        public String stop;                       // null | SCREEN_UNAVAILABLE | ERROR
        public String stopMessage;
        public boolean userCancelled;
    }

    /** One app: the plan with a 30 s deadline, retried every 300 ms; a step that aborts for good may be replayed three times. */
    public static void explore(Plan plan, Run r) {
        long end = r.host.now() + r.timing.planTimeout;
        int replays = 0;
        while (true) {
            r.guard();
            if (r.host.now() >= end) throw new Timeout("plan timed out");
            try {
                plan.run(r);
                return;
            } catch (PlanAbort e) {
                if (e.treatAsSuccess) return;
                throw e;
            } catch (StepAbort e) {
                if (e.treatAsSuccess) return;
                if (replays >= r.timing.planReplays) throw e;
                replays++;
                r.host.sleep(r.timing.retryDelay);
            } catch (ScreenUnavailable e) {
                throw e;
            } catch (UserCancelled e) {
                throw e;
            } catch (Timeout e) {
                if (r.host.now() >= end) throw e;
                r.host.sleep(r.timing.retryDelay);
            } catch (RuntimeException e) {
                r.host.sleep(r.timing.retryDelay);
            }
        }
    }

    /** Is the failure one of an unusable automation (a timeout or a step that gave up)? */
    static boolean unusable(RuntimeException e) { return e instanceof Timeout || (e instanceof StepAbort && !((StepAbort) e).treatAsSuccess); }

    /**
     * Clears the caches of the apps one by one. Stops when the screen goes off (stop = SCREEN_UNAVAILABLE), on Cancel (userCancelled, the results so far are kept) and when
     * eight apps failed with an unusable automation without a single success (stop = ERROR, the compatibility text).
     */
    public static Result clearAll(Host host, Timing timing, Labels labels, LabelSource src, Plan plan, List<PkgInfo> pkgs, Sdm.Progress progress) {
        Result res = new Result();
        int unusableCount = 0, done = 0;
        for (PkgInfo p : pkgs) {
            if (host.cancelled()) { res.userCancelled = true; break; }
            if (!host.screenAvailable()) { res.stop = "SCREEN_UNAVAILABLE"; res.stopMessage = "Screen is unavailable!"; break; }
            if (progress != null) progress.update(p.label.isEmpty() ? p.pkg : p.label, "", done, pkgs.size(), 0);
            Run run = new Run(host, timing, labels, src, p);
            try {
                explore(plan, run);
                res.cleared.add(p.pkg);
            } catch (PlanAbort e) {
                if (e.treatAsSuccess) res.cleared.add(p.pkg); else res.failed.put(p.pkg, e.code);
            } catch (UserCancelled e) {
                res.userCancelled = true;
                break;
            } catch (ScreenUnavailable e) {
                res.stop = "SCREEN_UNAVAILABLE"; res.stopMessage = e.getMessage();
                break;
            } catch (RuntimeException e) {
                res.failed.put(p.pkg, "OTHER");
                if (unusable(e)) unusableCount++;
                if (res.cleared.isEmpty() && unusableCount >= FAILURE_LIMIT) {
                    res.stop = "ERROR";
                    res.stopMessage = "SD Maid couldn't figure out the screen layout. If this keeps happening, your language or setup might not be fully supported. Check for updates or reach out to me so I can fix it."
                            + " This can also happen if the \"System UI\" system app was recently force-stopped, e.g. by an app cleaning tool. Android then blocks SD Maid's automated button presses until you restart the device.";
                    break;
                }
            }
            done++;
        }
        return res;
    }
}
