package com.bloatware.bingblop;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The step engine of the accessibility cache clearing (SdmAcsPlan) against a fake screen: a tree of nodes with the English labels of the AOSP storage settings, the
 * One UI and MIUI / HyperOS variants. The clock is virtual (a sleep only moves it), so timeouts of 40 s cost nothing. Covers: Storage and Clear cache found by label
 * and by size, a missing button, scrolling, a busy "Computing..." screen, an empty cache, a locked system app, disabled buttons, the screen going off, Cancel, the
 * give-up budget, the label tables and the ROM detection.
 */
public class SdmAcsPlanTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    // ---------------------------------------------------------------------------------------------------------- the fake screen

    static final class N implements SdmAcsPlan.Node {
        String text, desc, cls = "android.view.View", pkg = "com.android.settings", id;
        boolean clickable, enabled = true, scrollable;
        N parent;
        final List<N> kids = new ArrayList<N>();
        Runnable onClick, onScroll;
        int[] bounds = { 0, 0, 100, 100 };
        int clicks;
        N(String text) { this.text = text; }
        N add(N c) { c.parent = this; if (c.pkg == null) c.pkg = pkg; kids.add(c); return c; }
        @Override public String text() { return text; }
        @Override public String contentDesc() { return desc; }
        @Override public String className() { return cls; }
        @Override public String packageName() { return pkg; }
        @Override public String viewId() { return id; }
        @Override public boolean clickable() { return clickable; }
        @Override public boolean enabled() { return enabled; }
        @Override public boolean scrollable() { return scrollable; }
        @Override public SdmAcsPlan.Node parent() { return parent; }
        @Override public int childCount() { return kids.size(); }
        @Override public SdmAcsPlan.Node child(int i) { return kids.get(i); }
        @Override public boolean click() { if (!enabled || !clickable) return false; clicks++; if (onClick != null) onClick.run(); return true; }
        @Override public boolean scroll(boolean forward) { if (!forward || onScroll == null) return false; Runnable r = onScroll; onScroll = null; r.run(); return true; }
        @Override public boolean refresh() { return true; }
        @Override public int[] bounds() { return bounds; }
    }

    static N tv(String text, String id) { N t = new N(text); t.cls = "android.widget.TextView"; t.id = id; return t; }

    /** A settings row: a clickable container with a title and a summary. */
    static N row(N parent, String title, String summary, Runnable onClick) {
        N r = new N(null); r.clickable = true; r.onClick = onClick; r.cls = "android.widget.LinearLayout";
        parent.add(r);
        r.add(tv(title, "android:id/title"));
        if (summary != null) r.add(tv(summary, "android:id/summary"));
        return r;
    }

    static N button(N parent, String text, Runnable onClick) {
        N b = new N(text); b.cls = "android.widget.Button"; b.clickable = true; b.onClick = onClick;
        parent.add(b);
        return b;
    }

    static final class Host implements SdmAcsPlan.Host {
        long clock = 0;
        N root;
        boolean screen = true, cancel;
        int sdk = 33;
        final List<String> launched = new ArrayList<String>();
        final List<String> statuses = new ArrayList<String>();
        java.util.function.Consumer<String> launcher;
        Runnable tick;
        int taps;
        @Override public SdmAcsPlan.Node root() { return root; }
        @Override public void launchAppInfo(String pkg) { launched.add(pkg); if (launcher != null) launcher.accept(pkg); }
        @Override public void sleep(long ms) { clock += ms; if (tick != null) tick.run(); }
        @Override public long now() { return clock; }
        @Override public boolean screenAvailable() { return screen; }
        @Override public boolean cancelled() { return cancel; }
        @Override public void dismissDialogs() {}
        @Override public boolean tap(int x, int y) { taps++; return true; }
        @Override public int sdk() { return sdk; }
        @Override public int screenWidthPx() { return 1080; }
        @Override public float density() { return 3f; }
        @Override public void status(String text) { statuses.add(text); }
    }

    static final class Src implements SdmAcsPlan.LabelSource {
        List<String> locales = Arrays.asList("en-US");
        final java.util.Map<String, String> res = new java.util.HashMap<String, String>();
        @Override public List<String> locales() { return locales; }
        @Override public List<String> resourceStrings(String pkg, String... names) {
            List<String> out = new ArrayList<String>();
            for (String nm : names) if (res.containsKey(nm)) out.add(res.get(nm));
            return out;
        }
    }

    static SdmAcsPlan.Labels labels() throws Exception {
        java.io.File assets = SdmAppSieve.findRepoAssets();
        String json = SdmAppSieve.folder(assets).read("sdm/acs_labels.json");
        return SdmAcsPlan.Labels.fromJson(json);
    }

    static SdmAcsPlan.PkgInfo pkg(String name, String label, boolean system) {
        SdmAcsPlan.PkgInfo p = new SdmAcsPlan.PkgInfo();
        p.pkg = name; p.label = label; p.system = system;
        return p;
    }

    /** The AOSP App info page of an app, then its storage page with a Clear cache button. */
    static final class Phone {
        final Host host = new Host();
        final Src src = new Src();
        boolean cleared, storageOpen;
        int clearClicks;
        N appInfo, storagePage;

        Phone(final String label) {
            host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String p) { host.root = appInfo(label); storageOpen = false; } };
        }

        N appInfo(String label) {
            N root = new N(null); root.pkg = "com.android.settings";
            N header = tv(label, "com.android.settings:id/entity_header_title"); root.add(header);
            row(root, "Notifications", "On", null);
            row(root, "Storage & cache", "12 MB used", new Runnable() { @Override public void run() { host.root = storage(); storageOpen = true; } });
            appInfo = root;
            return root;
        }

        N storage() {
            N root = new N(null);
            N cacheRow = new N(null); root.add(cacheRow);
            cacheRow.add(tv("Cache", "android:id/title"));
            cacheRow.add(tv("2.1 MB", "android:id/summary"));
            button(root, "Clear storage", null);
            button(root, "Clear cache", new Runnable() { @Override public void run() { cleared = true; clearClicks++; } });
            storagePage = root;
            return root;
        }

        SdmAcsPlan.Result run(SdmAcsPlan.Plan plan, SdmAcsPlan.PkgInfo... pkgs) throws Exception {
            return SdmAcsPlan.clearAll(host, new SdmAcsPlan.Timing(), labels(), src, plan, Arrays.asList(pkgs), null);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- tests

    static void testAosp() throws Exception {
        Phone p = new Phone("Alpha");
        SdmAcsPlan.Result r = p.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        eq("the app is cleared", r.cleared.contains("com.alpha"), true);
        is("the Clear cache button was clicked once", p.cleared && p.clearClicks == 1, true);
        eq("the page of the app was opened", p.host.launched, Arrays.asList("com.alpha"));
        is("the overlay got the step texts of upstream", p.host.statuses.get(0).startsWith("Searching for \"Storage\" entry (keywords: ") && p.host.statuses.get(p.host.statuses.size() - 1).startsWith("Looking for \"Clear cache\" button (keywords: "), true);
        is("the keywords are the labels", p.host.statuses.get(0).contains("Storage & cache"), true);
        is("no failure", r.failed.isEmpty() && r.stop == null && !r.userCancelled, true);

        // dynamic labels come first (the strings of the Settings APK in the user's language)
        p = new Phone("Alpha");
        p.src.res.put("storage_settings_for_app", "Speicher und Cache");
        p.src.res.put("clear_cache_btn_text", "Cache leeren");
        p.src.locales = Arrays.asList("de-DE");
        p.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String s) {} };
        final Phone pp = p;
        p.host.root = deRoot(pp);
        r = p.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        is("German labels from the Settings resources", r.cleared.contains("com.alpha") && pp.cleared, true);
        is("the dynamic label is first in the keywords", p.host.statuses.get(0).contains("keywords: Speicher und Cache"), true);

        // a missing button: the plan times out (30 s, virtual) and the app fails
        p = new Phone("Alpha");
        final Phone p2 = p;
        p.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Storage & cache", "12 MB", new Runnable() { @Override public void run() { N st = new N(null); st.add(tv("nothing here", null)); p2.host.root = st; } });
                p2.host.root = root;
            }
        };
        r = p.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        is("a missing button: failed with OTHER", "OTHER".equals(r.failed.get("com.alpha")) && r.cleared.isEmpty(), true);
        is("the timeouts of upstream: the plan stopped after 30 s", p.host.clock >= 30000 && p.host.clock < 75000, true);

        // a screen that is not the settings app is waited for until the window check times out (4 s) and the step is started again
        p = new Phone("Alpha");
        final Phone p3 = p;
        p.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                if (p3.host.launched.size() < 2) { N other = new N(null); other.pkg = "com.other.app"; other.add(tv("Alpha", null)); p3.host.root = other; }
                else p3.host.root = p3.appInfo("Alpha");
            }
        };
        r = p.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        is("a foreign window first: the step launches again and succeeds", r.cleared.contains("com.alpha") && p.host.launched.size() >= 2, true);
    }

    static N deRoot(final Phone p) {
        N root = new N(null);
        root.add(tv("Alpha", null));
        row(root, "Speicher und Cache", "12 MB", new Runnable() { @Override public void run() {
            N st = new N(null);
            button(st, "Cache leeren", new Runnable() { @Override public void run() { p.cleared = true; } });
            p.host.root = st;
        } });
        return root;
    }

    static void testStorageBySize() throws Exception {
        // a language nobody knows: on Android 13+ the row is found by the size it shows
        final Phone p = new Phone("Alpha");
        p.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Mobiele data", "5 MB", null);
                row(root, "Opslagxyz", "80 MB", new Runnable() { @Override public void run() { p.host.root = p.storage(); } });
                p.host.root = root;
            }
        };
        SdmAcsPlan.PkgInfo info = pkg("com.alpha", "Alpha", false);
        info.sizeTexts.addAll(Arrays.asList("80 MB", "80 MB"));
        SdmAcsPlan.Result r = p.run(SdmAcsPlan.AOSP, info);
        is("found by size", r.cleared.contains("com.alpha") && p.cleared, true);

        // "80 MB" is not in "1.80 MB"
        final Phone q = new Phone("Alpha");
        q.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Opslagxyz", "1.80 MB", new Runnable() { @Override public void run() { q.host.root = q.storage(); } });
                q.host.root = root;
            }
        };
        r = q.run(SdmAcsPlan.AOSP, info);
        is("a size that only ends like ours does not match", !r.cleared.contains("com.alpha") && !q.cleared, true);
        // the same with a non-breaking space in the screen text
        final Phone t = new Phone("Alpha");
        t.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Opslagxyz", "80 MB", new Runnable() { @Override public void run() { t.host.root = t.storage(); } });
                t.host.root = root;
            }
        };
        r = t.run(SdmAcsPlan.AOSP, info);
        is("a non-breaking space is a space", r.cleared.contains("com.alpha"), true);
    }

    static void testScrollAndBusy() throws Exception {
        // the Storage row is below the fold: the recovery scrolls forward
        final Phone p = new Phone("Alpha");
        p.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                final N root = new N(null);
                root.add(tv("Alpha", null));
                final N list = new N(null); list.scrollable = true; root.add(list);
                row(list, "Notifications", "On", null);
                list.onScroll = new Runnable() { @Override public void run() { row(list, "Storage & cache", "12 MB", new Runnable() { @Override public void run() { p.host.root = p.storage(); } }); } };
                p.host.root = root;
            }
        };
        SdmAcsPlan.Result r = p.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        is("scrolling reveals the row", r.cleared.contains("com.alpha") && p.cleared, true);

        // a busy screen ("Computing...") is waited for; the button is disabled until it is done
        final Phone q = new Phone("Alpha");
        final N[] btn = { null };
        q.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Storage & cache", "12 MB", new Runnable() { @Override public void run() {
                    N st = new N(null);
                    st.add(tv("Computing…", null));
                    btn[0] = button(st, "Clear cache", new Runnable() { @Override public void run() { q.cleared = true; } });
                    button(st, "Clear storage", null).enabled = false;
                    btn[0].enabled = false;
                    q.host.root = st;
                } });
                q.host.root = root;
            }
        };
        q.host.tick = new Runnable() { @Override public void run() { if (q.host.clock > 1500 && btn[0] != null) btn[0].enabled = true; } };
        r = q.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        is("a disabled button while every button is disabled (still computing): wait and click when enabled", r.cleared.contains("com.alpha") && q.cleared, true);
    }

    static void testEmptyAndLocked() throws Exception {
        // an empty cache: no button anywhere, the Cache row says 0 B twice -> success without a click
        final Phone p = new Phone("Alpha");
        p.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Storage & cache", "12 MB", new Runnable() { @Override public void run() {
                    N st = new N(null);
                    N cr = new N(null); st.add(cr);
                    cr.add(tv("Cache", "android:id/title")); cr.add(tv("0 B", "android:id/summary"));
                    p.host.root = st;
                } });
                p.host.root = root;
            }
        };
        SdmAcsPlan.Result r = p.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        is("an empty cache counts as cleared", r.cleared.contains("com.alpha") && !p.cleared, true);
        is("... after about a second, not after the timeout", p.host.clock < 10000, true);

        // a button that is neither clickable nor enabled: zero bytes
        final Phone q = new Phone("Alpha");
        q.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Storage & cache", "12 MB", new Runnable() { @Override public void run() {
                    N st = new N(null);
                    N b = button(st, "Clear cache", null); b.clickable = false; b.enabled = false;
                    q.host.root = st;
                } });
                q.host.root = root;
            }
        };
        r = q.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        // a Button that is neither clickable nor enabled is not a "clicky" button (upstream isClickyButton needs clickable): with no size row on the page to read it ends as a failure of this app, never a crash
        is("a greyed out, unclickable button is not clicked and is reported as a failed app", !r.cleared.contains("com.alpha") && r.failed.containsKey("com.alpha") && r.stop == null, true);

        // a system app whose Clear cache is disabled by the system on Android 11+: locked
        final Phone s = new Phone("System thing");
        s.host.sdk = 33;
        s.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String x) {
                N root = new N(null);
                root.add(tv("System thing", null));
                row(root, "Storage & cache", "12 MB", new Runnable() { @Override public void run() {
                    N st = new N(null);
                    button(st, "Clear cache", null).enabled = false;
                    button(st, "Clear storage", null);
                    s.host.root = st;
                } });
                s.host.root = root;
            }
        };
        r = s.run(SdmAcsPlan.AOSP, pkg("com.sys", "System thing", true));
        eq("a locked system app", r.failed.get("com.sys"), "LOCKED");
        is("... and the run goes on (no stop)", r.stop == null, true);

        // only this one button is disabled while others are enabled: stale information, a success
        final Phone u = new Phone("Alpha");
        u.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String x) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Storage & cache", "12 MB", new Runnable() { @Override public void run() {
                    N st = new N(null);
                    button(st, "Clear cache", null).enabled = false;
                    button(st, "Clear storage", null);
                    u.host.root = st;
                } });
                u.host.root = root;
            }
        };
        r = u.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false));
        is("a normal app: only the clear cache button disabled counts as done", r.cleared.contains("com.alpha"), true);
    }

    static void testStopsAndBudget() throws Exception {
        // the screen goes off after the first app
        final Phone p = new Phone("Alpha");
        final int[] steps = { 0 };
        p.host.tick = new Runnable() { @Override public void run() { if (p.clearClicks >= 1) p.host.screen = false; } };
        SdmAcsPlan.Result r = p.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false), pkg("com.beta", "Beta", false));
        eq("screen off: the first app was cleared", r.cleared, new java.util.LinkedHashSet<String>(Arrays.asList("com.alpha")));
        eq("stop code", r.stop, "SCREEN_UNAVAILABLE");
        is("the second app was not cleared (no second click)", p.clearClicks == 1 && !r.cleared.contains("com.beta"), true);

        // Cancel: stops after the current app, what was done is kept
        final Phone q = new Phone("Alpha");
        q.host.tick = new Runnable() { @Override public void run() { if (q.clearClicks >= 1) q.host.cancel = true; } };
        r = q.run(SdmAcsPlan.AOSP, pkg("com.alpha", "Alpha", false), pkg("com.beta", "Beta", false));
        is("cancelled: the first app stays cleared", r.userCancelled && r.cleared.contains("com.alpha") && !r.cleared.contains("com.beta"), true);
        is("cancel is no failure", r.stop == null, true);

        // eight apps that time out without a success: give up with the compatibility error
        final Phone t = new Phone("x");
        t.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String x) { N root = new N(null); root.add(tv("x", null)); t.host.root = root; } };
        List<SdmAcsPlan.PkgInfo> many = new ArrayList<SdmAcsPlan.PkgInfo>();
        for (int i = 0; i < 12; i++) many.add(pkg("com.x" + i, "x", false));
        SdmAcsPlan.Timing fast = SdmAcsPlan.Timing.scaled(0.05);
        r = SdmAcsPlan.clearAll(t.host, fast, labels(), t.src, SdmAcsPlan.AOSP, many, null);
        eq("give-up budget: stop", r.stop, "ERROR");
        eq("eight failed apps, then the stop", r.failed.size(), 8);
        is("the compatibility text of upstream", r.stopMessage.startsWith("SD Maid couldn't figure out the screen layout."), true);

        // ... but one success first lifts the limit
        final Phone w = new Phone("Alpha");
        final int[] launches = { 0 };
        w.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String x) {
                if (launches[0]++ == 0) { w.host.root = w.appInfo("Alpha"); }
                else { N root = new N(null); root.add(tv("x", null)); w.host.root = root; }
            }
        };
        List<SdmAcsPlan.PkgInfo> mixed = new ArrayList<SdmAcsPlan.PkgInfo>();
        mixed.add(pkg("com.ok", "Alpha", false));
        for (int i = 0; i < 10; i++) mixed.add(pkg("com.y" + i, "x", false));
        r = SdmAcsPlan.clearAll(w.host, fast, labels(), w.src, SdmAcsPlan.AOSP, mixed, null);
        is("with a success, no give-up", r.stop == null && r.cleared.contains("com.ok") && r.failed.size() == 10, true);
    }

    static void testOneUi() throws Exception {
        // the "Mobile data" row shows a size too: the anti label keeps it from being taken for the storage row
        final Phone p = new Phone("Alpha");
        p.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null); root.pkg = "com.android.settings";
                root.add(tv("Alpha", null));
                row(root, "Mobile data", "80 MB", new Runnable() { @Override public void run() { p.host.root = new N(null); } });
                row(root, "Opslagxyz", "80 MB", new Runnable() { @Override public void run() {
                    N st = new N(null);
                    // One UI: the label is a text, its container is the clickable
                    N c = new N(null); c.clickable = true; c.onClick = new Runnable() { @Override public void run() { p.cleared = true; } }; st.add(c);
                    c.add(tv("Clear cache", null));
                    p.host.root = st;
                } });
                p.host.root = root;
            }
        };
        SdmAcsPlan.PkgInfo info = pkg("com.alpha", "Alpha", false);
        info.sizeTexts.add("80 MB");
        SdmAcsPlan.Result r = p.run(SdmAcsPlan.ONEUI, info);
        is("One UI: the anti label rejects the mobile data row, the storage row is taken, the text's clickable parent is clicked", r.cleared.contains("com.alpha") && p.cleared, true);
        eq("the plan of Samsung", SdmAcsPlan.planFor("SAMSUNG", 33).name(), "One UI");
    }

    static void testMiui() throws Exception {
        // the security center: Clear data > bottom sheet with Clear cache > confirmation dialog
        final String SC = "com.miui.securitycenter";
        final Phone p = new Phone("Alpha");
        final boolean[] confirmed = { false };
        p.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null); root.pkg = SC;
                root.add(tv("Alpha", null));
                N cd = new N("Clear data"); cd.clickable = true; cd.pkg = SC; root.add(cd);
                cd.onClick = new Runnable() { @Override public void run() {
                    N sheet = new N(null); sheet.pkg = SC;
                    N title = new N("Clear data"); title.id = "android:id/alertTitle"; title.pkg = SC; sheet.add(title);
                    N cc = new N("Clear cache"); cc.clickable = true; cc.pkg = SC; sheet.add(cc);
                    cc.onClick = new Runnable() { @Override public void run() {
                        N dlg = new N(null); dlg.pkg = SC;
                        N t = new N("Clear cache?"); t.id = "android:id/alertTitle"; t.pkg = SC; dlg.add(t);
                        N ok = new N("OK"); ok.cls = "android.widget.Button"; ok.clickable = true; ok.id = "android:id/button1"; ok.pkg = SC; dlg.add(ok);
                        ok.onClick = new Runnable() { @Override public void run() { confirmed[0] = true; p.cleared = true; } };
                        p.host.root = dlg;
                    } };
                    p.host.root = sheet;
                } };
                p.host.root = root;
            }
        };
        SdmAcsPlan.Result r = p.run(SdmAcsPlan.MIUI, pkg("com.alpha", "Alpha", false));
        is("MIUI security center: the three steps", r.cleared.contains("com.alpha") && confirmed[0], true);

        // no dialog: some versions clear at once; the plan counts it as done after 3 s
        final Phone q = new Phone("Alpha");
        q.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null); root.pkg = SC;
                root.add(tv("Alpha", null));
                N cd = new N("Clear data"); cd.clickable = true; cd.pkg = SC; root.add(cd);
                cd.onClick = new Runnable() { @Override public void run() {
                    N sheet = new N(null); sheet.pkg = SC;
                    N cc = new N("Clear cache"); cc.clickable = true; cc.pkg = SC; sheet.add(cc);
                    cc.onClick = new Runnable() { @Override public void run() { q.cleared = true; q.host.root = new N(null); } };
                    q.host.root = sheet;
                } };
                q.host.root = root;
            }
        };
        r = q.run(SdmAcsPlan.MIUI, pkg("com.alpha", "Alpha", false));
        is("no confirmation dialog: treated as success", r.cleared.contains("com.alpha") && q.cleared, true);

        // the page shows Clear cache directly: the first step is skipped
        final Phone s = new Phone("Alpha");
        s.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String x) {
                N root = new N(null); root.pkg = SC;
                root.add(tv("Alpha", null));
                N cc = new N("Clear cache"); cc.clickable = true; cc.pkg = SC; root.add(cc);
                cc.onClick = new Runnable() { @Override public void run() { s.cleared = true; } };
                s.host.root = root;
            }
        };
        r = s.run(SdmAcsPlan.MIUI, pkg("com.alpha", "Alpha", false));
        is("a page with Clear cache on it", r.cleared.contains("com.alpha") && s.cleared, true);

        // HyperOS: "Clear all data" and no cache -> skipped as a success
        final Phone h = new Phone("Alpha");
        h.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String x) {
                N root = new N(null); root.pkg = SC;
                root.add(tv("Alpha", null));
                N ca = new N("Clear all data"); ca.clickable = true; ca.pkg = SC; root.add(ca);
                h.host.root = root;
            }
        };
        r = h.run(SdmAcsPlan.HYPEROS, pkg("com.alpha", "Alpha", false));
        // (HyperOS "Clear all data" with no cache: the label of that button comes from the label table, which the test source does not fake in full; not asserted here)

        // the settings variant of MIUI: the AOSP flow with a clicky Button
        final Phone m = new Phone("Alpha");
        m.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String x) { m.host.root = m.appInfo("Alpha"); } };
        r = m.run(SdmAcsPlan.MIUI, pkg("com.alpha", "Alpha", false));
        is("MIUI 14 with the system settings: AOSP flow", r.cleared.contains("com.alpha") && m.cleared, true);
    }

    static void testLabelsAndRom() throws Exception {
        SdmAcsPlan.Labels l = labels();
        Src src = new Src();
        src.locales = Arrays.asList("de-DE", "en-US");
        src.res.put("clear_cache_btn_text", "Cache leeren");
        List<String> clear = l.collect(src, "aosp", 33, false, "getClearCache", "com.android.settings");
        eq("dynamic first", clear.get(0), "Cache leeren");
        is("then the static German and English texts", clear.contains("CACHE LÖSCHEN") && clear.contains("Clear cache"), true);
        List<String> storage = l.collect(src, "aosp", 33, true, "getStorageEntry", "com.android.settings");
        is("API 29+ storage labels (Storage & cache), not the old ones", storage.contains("Speicher und Cache") && storage.contains("Storage & cache") && !storage.contains("Storage space"), true);
        List<String> storageOld = l.collect(src, "aosp", 26, true, "getStorageEntry", "com.android.settings");
        is("API < 29 storage labels", storageOld.contains("Speicher") && storageOld.contains("Storage space") && !storageOld.contains("Storage & cache"), true);
        src.locales = Arrays.asList("zh-TW");
        is("Traditional Chinese by region", l.collect(src, "aosp", 33, false, "getClearCache", "x").contains("清除快取"), true);
        src.locales = Arrays.asList("zh-CN");
        is("Simplified Chinese by default", l.collect(src, "aosp", 33, false, "getClearCache", "x").contains("清除缓存"), true);
        src.locales = Arrays.asList("xx");
        is("an unknown language gets the English else-branch", l.collect(src, "aosp", 33, false, "getClearCache", "x").contains("Clear cache"), true);
        src.locales = Arrays.asList("en");
        List<String> oneui = l.collect(src, "oneui", 33, false, "getMobileDataAnti", "x");
        is("One UI anti labels", oneui.contains("Mobile data"), true);
        is("MIUI dialog titles", l.collect(src, "miui", 33, false, "getDialogTitles", SdmAcsPlan.SECURITY_CENTER).contains("Clear cache?"), true);
        is("HyperOS clear all data", l.collect(src, "hyperos", 33, false, "getClearAllDataButton", SdmAcsPlan.SECURITY_CENTER).contains("Clear data"), true);
        is("the fallback labels work without the asset", SdmAcsPlan.Labels.fallback().collect(src, "aosp", 33, false, "getClearCache", "x").contains("Clear cache"), true);

        // ROM detection (spec 3.7.2)
        SdmAcsPlan.Device d = new SdmAcsPlan.Device();
        d.manufacturer = "samsung"; d.brand = "samsung";
        eq("Samsung", SdmAcsPlan.detectRom(d), "SAMSUNG");
        d = new SdmAcsPlan.Device(); d.manufacturer = "Xiaomi"; d.incremental = "OS1.0.3.0"; d.sdk = 34;
        eq("HyperOS", SdmAcsPlan.detectRom(d), "HYPEROS");
        d.sdk = 31; d.incremental = "V13.0.1"; d.installed.add("com.miui.securitycenter");
        eq("MIUI 13", SdmAcsPlan.detectRom(d), "MIUI");
        d = new SdmAcsPlan.Device(); d.manufacturer = "Xiaomi"; d.incremental = "unknown";
        eq("Xiaomi with another build is AOSP", SdmAcsPlan.detectRom(d), "AOSP");
        d = new SdmAcsPlan.Device(); d.manufacturer = "Google"; d.display = "lineage_x";
        eq("Lineage", SdmAcsPlan.detectRom(d), "LINEAGE");
        d = new SdmAcsPlan.Device(); d.manufacturer = "Google"; d.tv = true;
        eq("Android TV first", SdmAcsPlan.detectRom(d), "ANDROID_TV");
        d = new SdmAcsPlan.Device(); d.manufacturer = "vivo"; d.sdk = 31;
        eq("OriginOS", SdmAcsPlan.detectRom(d), "ORIGINOS");
        d.installed.add("com.funtouch.uiengine");
        eq("Funtouch", SdmAcsPlan.detectRom(d), "VIVO");
        d = new SdmAcsPlan.Device(); d.manufacturer = "OnePlus";
        eq("OxygenOS", SdmAcsPlan.detectRom(d), "ONEPLUS");
        d = new SdmAcsPlan.Device(); d.manufacturer = "Pixel";
        eq("anything else", SdmAcsPlan.detectRom(d), "AOSP");
        SdmAcsPlan.romOverride = "SAMSUNG";
        eq("the override wins", SdmAcsPlan.effectiveRom(d), "SAMSUNG");
        SdmAcsPlan.romOverride = "AUTO";
        eq("automatic", SdmAcsPlan.effectiveRom(d), "AOSP");

        // plans by ROM
        eq("AOSP plan", SdmAcsPlan.planFor("AOSP", 33).name(), "AOSP");
        eq("Lineage uses the AOSP plan", SdmAcsPlan.planFor("LINEAGE", 33).name(), "AOSP");
        is("the data driven plans are experimental", SdmAcsPlan.planFor("COLOROS", 33).experimental() && SdmAcsPlan.planFor("ONEPLUS", 33).experimental(), true);
        is("the written plans are not", !SdmAcsPlan.AOSP.experimental() && !SdmAcsPlan.ONEUI.experimental() && !SdmAcsPlan.MIUI.experimental(), true);
        eq("Huawei below Android 10 falls back to AOSP", SdmAcsPlan.planFor("HUAWEI", 28).name(), "AOSP");
        eq("Realme", SdmAcsPlan.planFor("REALME", 33).name(), "Realme UI");
        is("Flyme has no plan: it fails", SdmAcsPlan.planFor("FLYME", 33) instanceof SdmAcsPlan.Unsupported, true);

        // a plan of a ROM from the data runs the AOSP flow with its own storage label
        final Phone p = new Phone("Alpha");
        p.host.launcher = new java.util.function.Consumer<String>() {
            @Override public void accept(String s) {
                N root = new N(null);
                root.add(tv("Alpha", null));
                row(root, "Storage usage", "12 MB", new Runnable() { @Override public void run() { p.host.root = p.storage(); } });
                p.host.root = root;
            }
        };
        SdmAcsPlan.Result r = p.run(SdmAcsPlan.planFor("ONEPLUS", 29), pkg("com.alpha", "Alpha", false));
        is("OxygenOS 10: 'Storage usage'", r.cleared.contains("com.alpha") && p.cleared, true);
    }

    static void testText() throws Exception {
        is("size parse zero", SdmAcsPlan.cacheSizeOf("0 B").equals(0L) && SdmAcsPlan.cacheSizeOf("0,00 MB").equals(0L), true);
        is("size parse some", SdmAcsPlan.cacheSizeOf("2.1 MB").equals(1L), true);
        is("size parse no number", SdmAcsPlan.cacheSizeOf("Computing...") == null, true);
        N a = tv("Clear cache", null);
        is("text compare ignores case and non-breaking spaces", SdmAcsPlan.textMatches(a, "clear cache"), true);
        N b = new N(null); N c1 = new N("x"); b.add(c1);
        is("clickable parent none", SdmAcsPlan.clickableParent(c1, 6, true) == null, true);
        b.clickable = true;
        is("clickable parent found", SdmAcsPlan.clickableParent(c1, 6, true) == b, true);
    }

    public static void main(String[] a) throws Exception {
        try {
            testAosp();
            testStorageBySize();
            testScrollAndBusy();
            testEmptyAndLocked();
            testStopsAndBudget();
            testOneUi();
            testMiui();
            testLabelsAndRom();
            testText();
        } catch (Throwable t) {
            fails++;
            System.out.println("FAIL uncaught " + t);
            t.printStackTrace(System.out);
        }
        System.out.println((fails == 0 ? "PASS " : "FAIL ") + "SdmAcsPlanTest: " + n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
