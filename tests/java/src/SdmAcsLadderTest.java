package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The part of the accessibility cache clearing that gets past a Storage entry that does not open (SdmAcsPlan.StorageEnter), against a fake model of the One UI 9 App info page of the
 * owner's screenshot: a scrollable list, the Storage row last and partly under a floating Uninstall / Force stop bar, ACTION_CLICK that answers true and does nothing. Covers: the
 * row scrolled clear of the bar, the tap at the visible part (never under the bar), the order of the ways (node click, parent click, tap, D-pad through the accessibility actions,
 * input tap, input keys), no input injection without a working mode, no blind centre key, the focus read before the first key, the row looked up again after the keys scrolled
 * the list, no centre on a focused control that only lies over the row, a click that only flickers, the no-progress watchdog (NO_PROGRESS, the stop after three stuck apps in a
 * row, another failure breaks the streak), the failure classes, the screen dump (size cap, only the Settings app's labels, node by node) and the report.
 */
public class SdmAcsLadderTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    static final int[] BAR = { 402, 2780, 1043, 2950 };
    static final int[] STORAGE_ROW = { 33, 2680, 1411, 2910 };
    static final String LABEL = "Samsung on-device Resource English (United States)";

    // ---------------------------------------------------------------------------------------------------------- the fake phone

    static boolean inside(int[] b, int x, int y) { return x >= b[0] && x < b[2] && y >= b[1] && y < b[3]; }

    /** The host: records what the engine does and answers the way the scenario says. */
    static final class LHost extends SdmAcsPlanTest.Host {
        final Page page;
        boolean inject;
        String launchErr;
        LHost(Page page) { this.page = page; }
        @Override public boolean tap(int x, int y) { return page.onTap(x, y, "tap"); }
        @Override public boolean dpadGlobal(int key) { return page.onKey(key, "dpad"); }
        @Override public boolean canInject() { return inject; }
        @Override public boolean injectKey(int key) { return page.onKey(key, "inject-key"); }
        @Override public boolean injectTap(int x, int y) { return page.onTap(x, y, "inject-tap"); }
        @Override public String launchError() { String e = launchErr; launchErr = null; return e; }
    }

    /** The list of the page: counts the times something tried to scroll it. */
    static final class ScrollList extends SdmAcsPlanTest.N {
        int tries;
        ScrollList() { super(null); }
        @Override public boolean scroll(boolean forward) { tries++; return super.scroll(forward); }
    }

    /** The App info page of the screenshot and the Storage page behind it. */
    static final class Page {
        final LHost host = new LHost(this);
        final SdmAcsPlanTest.Src src = new SdmAcsPlanTest.Src();
        final List<String> log = new ArrayList<String>();
        boolean nodeWorks, parentWorks, tapWorks, dpadWorks, injTapWorks, injKeysWorks, focusReadable = true;
        boolean wrapRow, listScrolls, flicker;
        /** A gesture tap on the row leaves the focus on it; a DOWN key scrolls the list (the window is built anew, the old nodes keep their old places); a focusable overlay lies over the row, before it in the focus order. */
        boolean tapFocuses, dpadScrolls, overlayFocus;
        int focus = -1, cleared, scrolls, scrolledBy;
        long flickerUntil = -1;
        SdmAcsPlanTest.N root, storageRow, wrapper, bar, overlay;
        ScrollList list;
        boolean emptyStorage, locked, barHidden;
        final List<SdmAcsPlanTest.N> focusables = new ArrayList<SdmAcsPlanTest.N>();

        Page() {
            host.sdk = 37;
            host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String p) { host.root = appInfo(); focus = -1; } };
            host.tick = new Runnable() { @Override public void run() { if (flickerUntil >= 0 && host.clock >= flickerUntil) { flickerUntil = -1; host.root = appInfo(); } } };
        }

        SdmAcsPlanTest.N tvAt(String text, String id, int[] b) { SdmAcsPlanTest.N t = SdmAcsPlanTest.tv(text, id); t.bounds = b; return t; }

        SdmAcsPlanTest.N rowAt(SdmAcsPlanTest.N parent, String title, String summary, int top, Runnable click) {
            SdmAcsPlanTest.N r = SdmAcsPlanTest.row(parent, title, summary, click);
            r.bounds = new int[] { 33, top, 1411, top + 160 };
            ((SdmAcsPlanTest.N) r.kids.get(0)).bounds = new int[] { 60, top + 20, 700, top + 80 };
            if (summary != null) ((SdmAcsPlanTest.N) r.kids.get(1)).bounds = new int[] { 60, top + 90, 900, top + 140 };
            focusables.add(r);
            return r;
        }

        SdmAcsPlanTest.N appInfo() { return appInfo(0); }

        /** The page scrolled up by {@code dy} pixels: every row that much higher, the bar where it is. */
        SdmAcsPlanTest.N appInfo(int dy) {
            focusables.clear();
            root = new SdmAcsPlanTest.N(null);
            root.bounds = new int[] { 0, 0, 1440, 2971 };
            root.add(tvAt(LABEL, "com.android.settings:id/entity_header_title", new int[] { 220, 250, 1200, 400 }));
            list = new ScrollList();
            list.scrollable = true; list.bounds = new int[] { 0, 420, 1440, 2971 };
            root.add(list);
            rowAt(list, "Notifications", "Blocked", 700 - dy, null);
            rowAt(list, "Permissions", "No permissions allowed", 940 - dy, null);
            rowAt(list, "Mobile data", "No data used", 2200 - dy, null);
            rowAt(list, "Battery", "0% used since last fully charged", 2440 - dy, null);
            Runnable open = new Runnable() { @Override public void run() { host.root = storagePage(); } };
            SdmAcsPlanTest.N host0 = list;
            if (wrapRow) {
                wrapper = new SdmAcsPlanTest.N(null);
                wrapper.clickable = true; wrapper.bounds = shifted(STORAGE_ROW, dy);
                wrapper.onClick = new Runnable() { @Override public void run() { log.add("parent-click"); if (parentWorks) host.root = storagePage(); } };
                list.add(wrapper);
                host0 = wrapper;
            }
            if (overlayFocus) {
                // a control of the page that lies over the whole row (its place covers the row's middle) and comes before the row in the focus order; it is not the row
                overlay = new SdmAcsPlanTest.N(null);
                overlay.cls = "android.widget.FrameLayout"; overlay.clickable = true; overlay.bounds = new int[] { 0, 2600 - dy, 1440, 2971 };
                root.add(overlay);
                focusables.add(overlay);
            }
            storageRow = rowAt(host0, "Storage", "680 MB used in Internal storage", 2680 - dy, new Runnable() { @Override public void run() { log.add("node-click"); if (nodeWorks) host.root = storagePage(); else if (flicker) { host.root = flickerPage(); flickerUntil = host.clock + 300; } } });
            storageRow.bounds = shifted(STORAGE_ROW, dy);
            ((SdmAcsPlanTest.N) storageRow.kids.get(0)).bounds = new int[] { 60, 2700 - dy, 700, 2790 - dy };
            ((SdmAcsPlanTest.N) storageRow.kids.get(1)).bounds = new int[] { 60, 2800 - dy, 900, 2870 - dy };
            if (listScrolls) {
                list.onScroll = new Runnable() { @Override public void run() { scrolls++; log.add("scroll"); moveBarAway(); } };
            } else {
                list.onScroll = null;
            }
            bar = new SdmAcsPlanTest.N(null);
            bar.bounds = BAR.clone();
            SdmAcsPlanTest.N un = SdmAcsPlanTest.button(bar, "Uninstall", null); un.bounds = new int[] { 440, 2800, 670, 2930 };
            SdmAcsPlanTest.N fs = SdmAcsPlanTest.button(bar, "Force stop", null); fs.bounds = new int[] { 720, 2800, 1000, 2930 };
            if (!barHidden) root.add(bar);                // a bar in a window of its own is not in this tree, but it still takes the taps
            focus = -1;
            final SdmAcsPlanTest.N r0 = root;
            root.focusFn = new java.util.function.Supplier<SdmAcsPlan.Node>() { @Override public SdmAcsPlan.Node get() { return focusReadable && focus >= 0 && focus < focusables.size() ? focusables.get(focus) : null; } };
            return r0;
        }

        static int[] shifted(int[] b, int dy) { return new int[] { b[0], b[1] - dy, b[2], b[3] - dy }; }

        void moveBarAway() {
            bar.bounds = new int[] { 402, 3000, 1043, 3170 };
            for (Object k : bar.kids) { SdmAcsPlanTest.N b = (SdmAcsPlanTest.N) k; b.bounds = new int[] { b.bounds[0], 3020, b.bounds[2], 3150 }; }
        }

        SdmAcsPlanTest.N flickerPage() {
            SdmAcsPlanTest.N r = new SdmAcsPlanTest.N(null);
            r.bounds = new int[] { 0, 0, 1440, 2971 };
            r.add(SdmAcsPlanTest.tv("Loading", null));
            return r;
        }

        SdmAcsPlanTest.N storagePage() {
            SdmAcsPlanTest.N r = new SdmAcsPlanTest.N(null);
            r.bounds = new int[] { 0, 0, 1440, 2971 };
            r.add(SdmAcsPlanTest.tv("Storage", "com.android.settings:id/title"));
            SdmAcsPlanTest.N cr = new SdmAcsPlanTest.N(null); r.add(cr);
            cr.add(SdmAcsPlanTest.tv("Cache", "android:id/title")); cr.add(SdmAcsPlanTest.tv("2.1 MB", "android:id/summary"));
            if (emptyStorage) return r;
            SdmAcsPlanTest.button(r, "Clear data", null);
            SdmAcsPlanTest.N cc = SdmAcsPlanTest.button(r, "Clear cache", new Runnable() { @Override public void run() { cleared++; log.add("clear-cache"); } });
            if (locked) cc.enabled = false;
            return r;
        }

        boolean onTap(int x, int y, String how) {
            log.add(how + " " + x + "," + y);
            if (inside(BAR, x, y) && bar.bounds[1] < 3000) return true;          // the bar takes it
            boolean works = how.equals("tap") ? tapWorks : injTapWorks;
            if (works && inside(STORAGE_ROW, x, y)) host.root = storagePage();
            else if (tapFocuses && inside(STORAGE_ROW, x, y)) focus = focusables.indexOf(storageRow);     // the tap did not open the row, but the focus is on it now
            return true;
        }

        boolean onKey(int key, String how) {
            log.add(how + " " + key);
            boolean works = how.equals("dpad") ? dpadWorks : injKeysWorks;
            if (key == 20) {
                if (focus < focusables.size() - 1) focus++;
                if (dpadScrolls && host.root == root) {                 // the list scrolls to the focused item: a new window, the old nodes keep their old places
                    int keep = focus;
                    scrolledBy += 200;
                    host.root = appInfo(scrolledBy);
                    focus = keep;
                }
                return true;
            }
            if (key == 23) { if (works && focus >= 0 && focus < focusables.size() && focusables.get(focus) == storageRow) host.root = storagePage(); return true; }
            return true;
        }

        SdmAcsPlan.Result run(SdmAcsPlan.PkgInfo... pkgs) throws Exception {
            host.root = appInfo();
            return SdmAcsPlan.clearAll(host, new SdmAcsPlan.Timing(), SdmAcsPlanTest.labels(), src, SdmAcsPlan.ONEUI, Arrays.asList(pkgs), null);
        }

        List<String> actions() { List<String> o = new ArrayList<String>(); for (String l : log) if (!l.equals("scroll")) o.add(l); return o; }
    }

    static SdmAcsPlan.PkgInfo app(String pkg) {
        SdmAcsPlan.PkgInfo p = new SdmAcsPlan.PkgInfo();
        p.pkg = pkg; p.label = LABEL; p.system = true;
        return p;
    }

    static boolean startsWith(List<String> l, String... want) {
        if (l.size() < want.length) return false;
        for (int i = 0; i < want.length; i++) if (!l.get(i).startsWith(want[i])) return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------- the owner's screen

    static void testScreenshotTapAtVisiblePart() throws Exception {
        // the click on the row answers true and does nothing; the list is at its end, so the bar stays over the row: tap the part of the row the bar does not cover
        Page p = new Page();
        p.tapWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.samsung.android.bixby.x"));
        is("the app is cleared", r.cleared.contains("com.samsung.android.bixby.x") && p.cleared == 1, true);
        List<String> a = p.actions();
        eq("node click first, then a tap", a.get(0), "node-click");
        is("then the tap", a.get(1).startsWith("tap "), true);
        String[] xy = a.get(1).substring(4).split(",");
        int x = Integer.parseInt(xy[0]), y = Integer.parseInt(xy[1]);
        is("the tap is on the row", inside(STORAGE_ROW, x, y), true);
        is("the tap is not under the bar", !inside(BAR, x, y), true);
        is("it tried to scroll the row clear of the bar first (the list was at its end)", p.list.tries >= 1, true);
        eq("one tap only: node click, tap, and the Clear cache click", a, Arrays.asList("node-click", a.get(1), "clear-cache"));
        is("no input injection in a mode without a shell", !a.toString().contains("inject"), true);
        is("no failure", r.failed.isEmpty() && r.failures.isEmpty() && r.stop == null, true);
    }

    static void testBarOutsideTheTree() throws Exception {
        // the bar is a window of its own: the node tree does not know it, the tap goes to the label of the row, not to the middle of the row (which is under the bar)
        Page p = new Page();
        p.barHidden = true; p.tapWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        is("cleared", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        String tap = null; for (String a : p.actions()) if (a.startsWith("tap ")) tap = a;
        is("a tap was made", tap != null, true);
        String[] xy = tap.substring(4).split(",");
        int x = Integer.parseInt(xy[0]), y = Integer.parseInt(xy[1]);
        is("it is on the label of the Storage row (" + tap + ")", x >= 60 && x < 700 && y >= 2700 && y < 2790, true);
        is("and not under the bar", !inside(BAR, x, y), true);
        is("the middle of the row would have been under the bar", inside(BAR, (STORAGE_ROW[0] + STORAGE_ROW[2]) / 2, (STORAGE_ROW[1] + STORAGE_ROW[3]) / 2), true);
    }

    static void testScrolledClear() throws Exception {
        // the list can scroll: the row is scrolled clear of the bar before any click, and the first click works
        Page p = new Page();
        p.listScrolls = true; p.nodeWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.samsung.android.bixby.x"));
        is("cleared", r.cleared.contains("com.samsung.android.bixby.x") && p.cleared == 1, true);
        eq("scrolled once, then clicked", p.log.subList(0, 2), Arrays.asList("scroll", "node-click"));
        is("no tap was needed", !p.log.toString().contains("tap "), true);
    }

    static void testNodeClickWorksUntouched() throws Exception {
        Page p = new Page();
        p.nodeWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        is("a click that works is the only thing done", r.cleared.contains("com.alpha") && p.actions().equals(Arrays.asList("node-click", "clear-cache")), true);
    }

    // ---------------------------------------------------------------------------------------------------------- the order of the ways

    static void testOrder() throws Exception {
        // node click, the clickable parent, the tap, the D-pad: each tried only when the one before changed nothing
        Page p = new Page();
        p.wrapRow = true; p.dpadWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        List<String> a = p.actions();
        is("cleared by the D-pad", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        is("order: node click, parent click, tap, then the keys", startsWith(a, "node-click", "parent-click", "tap "), true);
        int firstKey = -1; for (int i = 0; i < a.size(); i++) if (a.get(i).startsWith("dpad")) { firstKey = i; break; }
        is("the keys come after the tap", firstKey == 3, true);
        is("keys down until the row has focus (storage is the fifth row), then centre", a.subList(firstKey, firstKey + 6).equals(Arrays.asList("dpad 20", "dpad 20", "dpad 20", "dpad 20", "dpad 20", "dpad 23")), true);
        is("nothing injected", !a.toString().contains("inject"), true);

        // the parent click is what works
        p = new Page();
        p.wrapRow = true; p.parentWorks = true;
        r = p.run(app("com.alpha"));
        is("the parent click works: no tap, no keys", r.cleared.contains("com.alpha") && p.actions().equals(Arrays.asList("node-click", "parent-click", "clear-cache")), true);

        // below Android 13 there are no D-pad actions
        p = new Page();
        p.host.sdk = 31; p.dpadWorks = true;
        r = p.run(app("com.alpha"));
        is("no accessibility D-pad before Android 13", !p.log.toString().contains("dpad"), true);
        eq("so the app is stuck", r.failed.get("com.alpha"), "NO_PROGRESS");
    }

    static void testPrivileged() throws Exception {
        // with a working mode: input tap comes after the accessibility ways
        Page p = new Page();
        p.host.inject = true; p.injTapWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        List<String> a = p.actions();
        is("cleared by input tap", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        int tap = -1, dp = -1, inj = -1;
        for (int i = 0; i < a.size(); i++) { if (tap < 0 && a.get(i).startsWith("tap ")) tap = i; if (dp < 0 && a.get(i).startsWith("dpad")) dp = i; if (inj < 0 && a.get(i).startsWith("inject-tap")) inj = i; }
        is("input tap after the gesture tap and the accessibility D-pad", tap >= 0 && dp > tap && inj > dp, true);
        is("the injected tap is not under the bar", !inside(BAR, Integer.parseInt(a.get(inj).substring(11).split(",")[0]), Integer.parseInt(a.get(inj).substring(11).split(",")[1])), true);
        is("no input keys needed", !a.toString().contains("inject-key"), true);

        // only the keys work
        p = new Page();
        p.host.inject = true; p.injKeysWorks = true;
        r = p.run(app("com.alpha"));
        a = p.actions();
        is("cleared by input keys", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        int c = 0; for (String s : a) if (s.equals("inject-key 23")) c++;
        eq("one centre key", c, 1);
        // the accessibility D-pad way (whose centre did nothing here) left the focus on the Storage row; the input-keys way reads the focus before its first key and presses centre at once
        is("no DOWN key by input: the focus was on the Storage row already", a.indexOf("inject-key 23") > 0 && !a.contains("inject-key 20") && a.get(a.indexOf("inject-key 23") - 1).startsWith("inject-tap "), true);
        is("the D-pad way had moved it there with DOWN keys", count(a, "dpad 20") == 5 && count(a, "dpad 23") == 1, true);

        // without a working mode nothing is injected, however stuck it is
        p = new Page();
        p.host.inject = false; p.injTapWorks = true; p.injKeysWorks = true;
        r = p.run(app("com.alpha"));
        is("no injection without a privileged working mode", !p.log.toString().contains("inject") && r.failed.containsKey("com.alpha"), true);
        SdmAcsDiag.Failure f = r.failures.get("com.alpha");
        boolean said = false; for (String t : f.tried) if (t.contains("needs a working mode")) said = true;
        is("the report says why the privileged ways were skipped", said, true);
    }

    static void testNoBlindCentre() throws Exception {
        // the focus cannot be read: the keys go down a few times, centre is never pressed
        Page p = new Page();
        p.dpadWorks = true; p.focusReadable = false; p.host.inject = true; p.injKeysWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        is("no centre key without a focus that was read as the Storage row", !p.log.contains("dpad 23") && !p.log.contains("inject-key 23"), true);
        eq("the app is stuck", r.failed.get("com.alpha"), "NO_PROGRESS");
        int downs = 0; for (String s : p.log) if (s.equals("dpad 20")) downs++;
        is("only a few down keys per round", downs <= 2 * 4, true);
    }

    static int count(List<String> l, String s) { return Collections.frequency(l, s); }

    static void testDpadFocusAlreadyOnRow() throws Exception {
        // the gesture tap did not open the row but left the focus on it: the D-pad way reads the focus before its first key and presses centre at once (a first DOWN would move past the row)
        Page p = new Page();
        p.tapFocuses = true; p.dpadWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        List<String> a = p.actions();
        is("cleared by the centre key", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        eq("no DOWN key at all: the focus was on the row already", count(a, "dpad 20"), 0);
        eq("one centre key", count(a, "dpad 23"), 1);
        is("after the tap", a.indexOf("dpad 23") > 0 && a.get(a.indexOf("dpad 23") - 1).startsWith("tap "), true);
        is("no failure to report", r.failed.isEmpty() && r.failures.isEmpty(), true);

        // the focus is read after each key as well: with the focus on the row from the start and the keys the only way, still no DOWN
        p = new Page();
        p.tapFocuses = true; p.host.inject = true; p.injKeysWorks = true;
        r = p.run(app("com.alpha"));
        a = p.actions();
        is("cleared by the input keys", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        is("the accessibility D-pad found the focus on the row and pressed centre (which did nothing here), the input keys did the same", count(a, "dpad 23") == 1 && count(a, "inject-key 23") == 1 && count(a, "dpad 20") == 0 && count(a, "inject-key 20") == 0, true);
    }

    static void testDpadScrollsTheList() throws Exception {
        // every DOWN scrolls the list and the window is built anew; the Storage row that was found before the keys keeps its old place: the row is looked up again in the window the focus comes from
        Page p = new Page();
        p.dpadScrolls = true; p.dpadWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        List<String> a = p.actions();
        is("cleared by the D-pad although the list scrolled under it", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        is("the page was scrolled by the keys", p.scrolledBy >= 5 * 200, true);
        eq("DOWN until the fifth row, then centre", a.subList(a.indexOf("dpad 20"), a.indexOf("dpad 23") + 1), Arrays.asList("dpad 20", "dpad 20", "dpad 20", "dpad 20", "dpad 20", "dpad 23"));
        is("the old place of the row is not the place of the focus any more (the check had to use the new one)", p.storageRow.bounds[1] < STORAGE_ROW[1] - 500, true);
    }

    static void testFocusGuardNeedsTheRow() throws Exception {
        // a focusable control that lies over the row (its bounds hold the row's middle) comes before the row in the focus order: no centre on it; the row itself gets the centre
        Page p = new Page();
        p.overlayFocus = true; p.dpadWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        List<String> a = p.actions();
        is("cleared by the D-pad", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        eq("one centre key only", count(a, "dpad 23"), 1);
        int centre = a.indexOf("dpad 23"), downs = 0;
        for (int i = 0; i < centre; i++) if (a.get(i).equals("dpad 20")) downs++;
        eq("six DOWN keys before it: four rows, the overlay, then Storage", downs, 6);
        is("the overlay's place would have passed a check by geometry alone", inside(p.overlay.bounds, (STORAGE_ROW[0] + STORAGE_ROW[2]) / 2, (STORAGE_ROW[1] + STORAGE_ROW[3]) / 2), true);

        // the guard itself: the row, its label, its holder pass; a stranger at the same place does not; the whole list does not
        SdmAcsPlan.Run run = new SdmAcsPlan.Run(p.host, new SdmAcsPlan.Timing(), SdmAcsPlanTest.labels(), p.src, app("com.x"));
        SdmAcsPlan.StorageEnter se = new SdmAcsPlan.StorageEnter(run, Arrays.asList("Storage"), new ArrayList<String>(), 6);
        int[] win = { 0, 0, 1440, 2971 };
        SdmAcsPlanTest.N row = p.storageRow, label = (SdmAcsPlanTest.N) row.kids.get(0);
        is("the row itself", se.focusOnRow(row, row, win), true);
        is("its label (inside it)", se.focusOnRow(label, row, win), true);
        is("the label as the anchor, the row as the focus (holds it)", se.focusOnRow(row, label, win), true);
        SdmAcsPlanTest.N stranger = new SdmAcsPlanTest.N(null); stranger.bounds = row.bounds.clone();
        is("a stranger with exactly the row's place: not the row", se.focusOnRow(stranger, row, win), false);
        is("the list that holds the row: too big", se.focusOnRow(p.list, row, win), false);
        SdmAcsPlanTest.N other = (SdmAcsPlanTest.N) p.list.kids.get(0);
        is("another row of the list: not the row", se.focusOnRow(other, row, win), false);
    }

    static void testWatchdogStreak() throws Exception {
        // the watchdog counts stuck apps in a row: an app that fails another way (no Storage entry on its page) breaks the streak
        final Page p = new Page();
        p.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String pkg) {
            if (pkg.equals("com.a3")) { SdmAcsPlanTest.N o = new SdmAcsPlanTest.N(null); o.bounds = new int[] { 0, 0, 1440, 2971 }; o.add(SdmAcsPlanTest.tv(LABEL, null)); SdmAcsPlanTest.row(o, "Notifications", "Blocked", null); p.host.root = o; }
            else { p.host.root = p.appInfo(); p.focus = -1; }
        } };
        SdmAcsPlan.Result r = p.run(app("com.a1"), app("com.a2"), app("com.a3"), app("com.a4"), app("com.a5"));
        is("no stop: two stuck, one other failure, two stuck", r.stop == null, r.stop == null);
        eq("all five failed", r.failed.size(), 5);
        is("the third failed another way", "OTHER".equals(r.failed.get("com.a3")) && "STORAGE_NOT_FOUND".equals(r.failures.get("com.a3").code), true);
        is("the others are stuck", "NO_PROGRESS".equals(r.failed.get("com.a1")) && "NO_PROGRESS".equals(r.failed.get("com.a2")) && "NO_PROGRESS".equals(r.failed.get("com.a4")) && "NO_PROGRESS".equals(r.failed.get("com.a5")), true);

        // three in a row after a break still stop it
        p.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String pkg) {
            if (pkg.equals("com.b1")) { SdmAcsPlanTest.N o = new SdmAcsPlanTest.N(null); o.bounds = new int[] { 0, 0, 1440, 2971 }; o.add(SdmAcsPlanTest.tv(LABEL, null)); SdmAcsPlanTest.row(o, "Notifications", "Blocked", null); p.host.root = o; }
            else { p.host.root = p.appInfo(); p.focus = -1; }
        } };
        r = p.run(app("com.b1"), app("com.b2"), app("com.b3"), app("com.b4"), app("com.b5"));
        eq("stopped after the three stuck in a row", r.stop, "ERROR");
        eq("four failed (the other failure and three stuck)", r.failed.size(), 4);
    }

    // ---------------------------------------------------------------------------------------------------------- a click that only flickers

    static void testFlicker() throws Exception {
        // the click redraws the page for a moment (the window "changed") and then App info is back: step 2 sees it, and the plan goes on with the next way
        Page p = new Page();
        p.flicker = true; p.tapWorks = true;
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        is("the next way worked", r.cleared.contains("com.alpha") && p.cleared == 1, true);
        is("App info was opened again", p.host.launched.size() >= 2, true);
        List<String> a = p.actions();
        eq("the node click was made once: after the flicker the next way was taken", Collections.frequency(a, "node-click"), 1);
        is("the tap came after it", a.indexOf("node-click") >= 0 && a.size() > 2 && a.get(a.size() - 2).startsWith("tap "), true);
    }

    // ---------------------------------------------------------------------------------------------------------- the watchdog

    static void testWatchdog() throws Exception {
        Page p = new Page();                      // nothing works
        SdmAcsPlan.Result r = p.run(app("com.alpha"));
        eq("NO_PROGRESS", r.failed.get("com.alpha"), "NO_PROGRESS");
        is("it ended in bounded time (the ladder has its own limit)", p.host.clock < 120000, true);
        is("the ladder ran twice (two rounds), the first way each time", Collections.frequency(p.log, "node-click") == 2, true);
        SdmAcsDiag.Failure f = r.failures.get("com.alpha");
        eq("failure code", f.code, "NO_PROGRESS");
        is("plan and step are named", f.plan.equals("One UI") && f.step.equals("Storage entry"), true);
        boolean tapListed = false, nodeListed = false;
        for (String t : f.tried) { if (t.startsWith("gesture tap at ")) tapListed = true; if (t.startsWith("node click on ")) nodeListed = true; }
        is("what was tried is listed", f.tried.size() >= 6 && tapListed && nodeListed && f.tried.get(0).contains("partly covered"), true);
        is("the screen was recorded", f.screen.startsWith("Screen: window com.android.settings") && f.screen.contains("\"Storage\"") && f.screen.contains("Force stop"), true);
        String rep = f.toReport("Samsung SM-948U1, Android 17 (API 37)");
        is("the report names phone, plan, step, reason, tries and screen", rep.contains("Samsung SM-948U1") && rep.contains("Plan: One UI   Step: Storage entry") && rep.contains("Reason: NO_PROGRESS") && rep.contains("Tried (") && rep.contains("d=0 "), true);
        is("the label of the app is in it, nothing else personal", rep.contains(LABEL) && !rep.contains("@"), true);

        // three apps in a row stuck, none cleared: the run stops with a message that says so
        p = new Page();
        r = p.run(app("com.a1"), app("com.a2"), app("com.a3"), app("com.a4"), app("com.a5"));
        eq("stop after three", r.stop, "ERROR");
        eq("three apps failed", r.failed.size(), 3);
        is("the message tells about App info and the report", r.stopMessage.contains("App info") && r.stopMessage.contains("Copy screen report"), true);
        is("the failures are kept for the report", r.failures.size() == 3, true);

        // a success first lifts the limit
        p = new Page();
        final Page pp = p;
        final int[] launches = { 0 };
        p.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String s) { pp.nodeWorks = launches[0]++ == 0; pp.host.root = pp.appInfo(); } };
        r = p.run(app("com.ok"), app("com.b1"), app("com.b2"), app("com.b3"), app("com.b4"));
        is("one cleared app: no stop", r.stop == null && r.cleared.contains("com.ok") && r.failed.size() == 4, true);
        is("only the first " + SdmAcsPlan.MAX_FAILURES + " failures keep a report", r.failures.size() == SdmAcsPlan.MAX_FAILURES, true);
    }

    // ---------------------------------------------------------------------------------------------------------- why it failed

    static SdmAcsPlan.Result runFast(Page p) throws Exception {
        SdmAcsPlan.PkgInfo info = app("com.alpha");
        info.system = false;
        return SdmAcsPlan.clearAll(p.host, SdmAcsPlan.Timing.scaled(0.05), SdmAcsPlanTest.labels(), p.src, SdmAcsPlan.ONEUI, Arrays.asList(info), null);
    }

    static void testClasses() throws Exception {
        // a foreign window (an app locker) in front the whole time
        final Page q = new Page();
        q.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String s) { SdmAcsPlanTest.N o = new SdmAcsPlanTest.N(null); o.pkg = "com.avast.android.applocker"; o.add(SdmAcsPlanTest.tv("Unlock " + LABEL, null)); q.host.root = o; } };
        q.host.root = null;
        SdmAcsPlan.Result r = runFast(q);
        SdmAcsDiag.Failure f = r.failures.get("com.alpha");
        eq("a foreign window: SETTINGS_NOT_OPENED", f.code, "SETTINGS_NOT_OPENED");
        eq("the window in front is named", f.windowPkg, "com.avast.android.applocker");
        is("its content is not in the report", f.screen.contains("not the Settings app") && !f.screen.contains("Unlock"), true);

        // Settings in front but not showing the app
        final Page s = new Page();
        s.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String x) { SdmAcsPlanTest.N o = new SdmAcsPlanTest.N(null); o.add(SdmAcsPlanTest.tv("Some other app", null)); s.host.root = o; } };
        eq("Settings without the app's name: APP_NOT_ON_SCREEN", runFast(s).failures.get("com.alpha").code, "APP_NOT_ON_SCREEN");

        // Android refused to open App info
        final Page l = new Page();
        l.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String x) { l.host.launchErr = "java.lang.SecurityException: Starting activity from background"; l.host.root = null; } };
        r = runFast(l);
        eq("a refused launch: LAUNCH_FAILED", r.failures.get("com.alpha").code, "LAUNCH_FAILED");
        is("the error text is kept", r.failures.get("com.alpha").launchError.contains("SecurityException"), true);

        // the page opens but has no Storage entry
        final Page m = new Page();
        m.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String x) { SdmAcsPlanTest.N o = new SdmAcsPlanTest.N(null); o.add(SdmAcsPlanTest.tv(LABEL, null)); SdmAcsPlanTest.row(o, "Notifications", "Blocked", null); m.host.root = o; } };
        r = runFast(m);
        eq("no Storage entry: STORAGE_NOT_FOUND", r.failures.get("com.alpha").code, "STORAGE_NOT_FOUND");
        is("the screen of that page is in the report", r.failures.get("com.alpha").screen.contains("\"Notifications\""), true);

        // the Storage page opens but has no Clear cache button
        final Page c = new Page();
        c.nodeWorks = true; c.emptyStorage = true;
        r = runFast(c);
        eq("no Clear cache button: CLEAR_NOT_FOUND", r.failures.get("com.alpha").code, "CLEAR_NOT_FOUND");
        eq("the step is named", r.failures.get("com.alpha").step, "Clear cache button");
    }

    static void testHeadline() throws Exception {
        // a locked system app is what Android allows: told about only when nothing at all was cleared
        final Page p = new Page();
        p.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String x) { p.nodeWorks = true; p.host.root = p.appInfo(); } };
        p.locked = true;
        SdmAcsPlan.PkgInfo sys = app("com.sys");                    // a system app, Clear cache greyed out
        SdmAcsPlan.Result r = SdmAcsPlan.clearAll(p.host, SdmAcsPlan.Timing.scaled(0.1), SdmAcsPlanTest.labels(), p.src, SdmAcsPlan.ONEUI, Arrays.asList(sys), null);
        eq("a locked system app is a LOCKED failure", r.failed.get("com.sys"), "LOCKED");
        is("nothing was cleared: it is the headline", r.headline() != null && "LOCKED".equals(r.headline().code), true);
        SdmAcsPlan.PkgInfo ok = app("com.ok"); ok.system = false;
        p.locked = false;
        final int[] n = { 0 };
        p.host.launcher = new java.util.function.Consumer<String>() { @Override public void accept(String x) { p.locked = n[0]++ > 0; p.nodeWorks = true; p.host.root = p.appInfo(); } };
        r = SdmAcsPlan.clearAll(p.host, SdmAcsPlan.Timing.scaled(0.1), SdmAcsPlanTest.labels(), p.src, SdmAcsPlan.ONEUI, Arrays.asList(ok, sys), null);
        is("one cleared, one locked: no headline, the run is not a failure", r.cleared.contains("com.ok") && "LOCKED".equals(r.failed.get("com.sys")) && r.headline() == null, true);
        // a real problem comes before a locked app, and a locked app does not use up the room for real ones
        r = new SdmAcsPlan.Result();
        SdmAcsDiag.Failure a = new SdmAcsDiag.Failure(); a.code = "LOCKED"; r.failures.put("a", a);
        SdmAcsDiag.Failure b = new SdmAcsDiag.Failure(); b.code = "NO_PROGRESS"; r.failures.put("b", b);
        eq("the first real problem is the headline", r.headline().code, "NO_PROGRESS");
        is("which codes are limits of Android", SdmAcsPlan.expectedLimit("NO_SETTINGS") && SdmAcsPlan.expectedLimit("DISABLED_APP") && SdmAcsPlan.expectedLimit("LOCKED") && !SdmAcsPlan.expectedLimit("NO_PROGRESS") && !SdmAcsPlan.expectedLimit("OTHER"), true);
    }

    // ---------------------------------------------------------------------------------------------------------- the dump

    static void testDump() throws Exception {
        // 1000 nodes: at most 300 lines, the rest counted; text cut
        SdmAcsPlanTest.N root = new SdmAcsPlanTest.N(null);
        root.bounds = new int[] { 0, 0, 1080, 2000 };
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 400; i++) longText.append('x');
        for (int i = 0; i < 999; i++) { SdmAcsPlanTest.N c = SdmAcsPlanTest.tv(i == 0 ? longText.toString() : "row " + i, "com.android.settings:id/title"); c.bounds = new int[] { 0, i, 100, i + 1 }; root.add(c); }
        String d = SdmAcsDiag.dump(root, SdmAcsPlan.Run.REPORT_PKGS);
        int lines = 0; for (String l : d.split("\n")) if (l.startsWith("d=")) lines++;
        eq("at most 300 node lines", lines, SdmAcsDiag.MAX_NODES);
        is("the rest is counted", d.contains("... 700 more nodes not listed") && d.contains(", 1000 nodes"), true);
        is("long text is cut", !d.contains(longText.substring(0, 100)) && d.contains("…"), true);
        is("the line holds class, id, text, desc, flags and place", d.contains("d=1 TextView id=title text=\"row 5\" desc=- flags=-e- [0,5][100,6]"), true);
        is("the whole dump stays small", d.length() < 60000, true);

        // another app's window: named, never read
        SdmAcsPlanTest.N other = new SdmAcsPlanTest.N(null);
        other.pkg = "com.bank.app"; other.add(SdmAcsPlanTest.tv("Balance 1234.56", null));
        String o = SdmAcsDiag.dump(other, SdmAcsPlan.Run.REPORT_PKGS);
        is("a foreign window is named and its content not recorded", o.contains("com.bank.app") && o.contains("not recorded") && !o.contains("Balance"), true);
        is("no window", SdmAcsDiag.dump(null, SdmAcsPlan.Run.REPORT_PKGS).contains("no window"), true);

        // another app's node inside the Settings window (an embedded view, an overlay): the check is per node, its text, description and id are not recorded, its place is
        SdmAcsPlanTest.N mixed = new SdmAcsPlanTest.N(null);
        mixed.bounds = new int[] { 0, 0, 1080, 2000 };
        mixed.add(SdmAcsPlanTest.tv("Storage", "com.android.settings:id/title"));
        SdmAcsPlanTest.N foreign = new SdmAcsPlanTest.N("Balance 1234.56"); foreign.pkg = "com.bank.app"; foreign.desc = "Account of Jane"; foreign.id = "com.bank.app:id/balance"; foreign.bounds = new int[] { 0, 500, 1080, 600 };
        mixed.add(foreign);
        SdmAcsPlanTest.N inner = SdmAcsPlanTest.tv("PIN 9876", null); inner.pkg = "com.bank.app"; foreign.add(inner);
        SdmAcsPlanTest.N unknown = SdmAcsPlanTest.tv("whose?", null); mixed.add(unknown); unknown.pkg = null;
        String m = SdmAcsDiag.dump(mixed, SdmAcsPlan.Run.REPORT_PKGS);
        is("the Settings node is written", m.contains("text=\"Storage\""), true);
        is("the foreign node's text, description and id are not", !m.contains("Balance") && !m.contains("Jane") && !m.contains("balance"), true);
        is("nor its child's", !m.contains("9876"), true);
        is("nor a node without a package", !m.contains("whose"), true);
        is("the foreign node is still listed by package, class and place", m.contains("d=1 View other-app=com.bank.app (content not recorded) flags=-e- [0,500][1080,600]"), true);
        is("every node is counted", m.contains(", 5 nodes"), true);

        // the tried list is capped
        SdmAcsPlan.Run run = new SdmAcsPlan.Run(new SdmAcsPlanTest.Host(), new SdmAcsPlan.Timing(), null, null, app("com.x"));
        for (int i = 0; i < 100; i++) run.note("try " + i);
        eq("the newest tries are kept", run.diag.tried.size(), SdmAcsDiag.MAX_TRIED);
        eq("... the oldest dropped", run.diag.tried.get(0), "try 60");
    }

    static void testGeometry() throws Exception {
        SdmAcsPlanTest.N anchor = new SdmAcsPlanTest.N(null); anchor.bounds = STORAGE_ROW.clone();
        SdmAcsPlanTest.N bar = new SdmAcsPlanTest.N(null); bar.bounds = BAR.clone(); bar.clickable = true;
        List<SdmAcsPlan.Node> tree = new ArrayList<SdmAcsPlan.Node>(Arrays.<SdmAcsPlan.Node>asList(anchor, bar));
        int[] win = { 0, 0, 1440, 2971 };
        List<int[]> ob = SdmAcsDiag.obstacles(tree, anchor, win);
        eq("the bar is one obstacle", ob.size(), 1);
        int[] at = SdmAcsDiag.visibleCentre(anchor, ob, win, 20);
        is("the centre of the biggest visible piece is above the bar", at != null && at[1] < BAR[1] && at[1] > STORAGE_ROW[1] && at[0] == 722, true);
        is("nothing visible: null", SdmAcsDiag.visibleCentre(anchor, Arrays.asList(new int[] { 0, 0, 1440, 2971 }), win, 20) == null, true);
        is("a row that is not covered: its own centre", Arrays.equals(SdmAcsDiag.visibleCentre(anchor, new ArrayList<int[]>(), win, 20), new int[] { 722, 2795 }), true);
        bar.bounds = new int[] { 0, 2600, 1440, 2971 };
        is("a node that covers the whole row is not a bar over it", SdmAcsDiag.obstacles(tree, anchor, win).isEmpty(), true);
        bar.bounds = new int[] { 0, 0, 100, 100 };
        is("a node that does not touch it is not an obstacle", SdmAcsDiag.obstacles(tree, anchor, win).isEmpty(), true);
        Set<String> a = new HashSet<String>(Arrays.asList("a", "b", "c", "d")), b = new HashSet<String>(Arrays.asList("a", "b", "c", "x"));
        is("similarity of pages", Math.abs(SdmAcsDiag.similarity(a, b) - 0.6) < 1e-9 && SdmAcsDiag.similarity(a, a) == 1.0 && SdmAcsDiag.similarity(a, new HashSet<String>()) == 0.0, true);
    }

    /** The body of the first method whose declaration holds {@code decl}, braces matched (comments and strings in these bodies hold no braces). */
    static String body(String src, String decl) {
        int i = src.indexOf(decl);
        if (i < 0) return null;
        int open = src.indexOf('{', i), depth = 0;
        for (int j = open; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, j + 1);
        }
        return null;
    }

    /**
     * SDM-2, a source scan of SdmAutomation (it needs Android classes, so it is not run here): {@code input tap} lands on the topmost window like a finger,
     * so the cover is made touch-through around it exactly as around the gesture tap; else the injected tap hits the cover and may press its Cancel.
     */
    static void testInjectTapPassthrough() throws Exception {
        java.io.File d = new java.io.File(System.getProperty("user.dir")).getAbsoluteFile(), f = null;
        for (int i = 0; d != null && i < 8 && f == null; i++, d = d.getParentFile()) {
            java.io.File c = new java.io.File(d, "src/com/bloatware/bingblop/SdmAutomation.java");
            if (c.isFile()) f = c;
        }
        if (f == null) { System.out.println("SKIP testInjectTapPassthrough: src/com/bloatware/bingblop/SdmAutomation.java not found"); return; }
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        for (String decl : new String[] { "public boolean tap(int x, int y)", "public boolean injectTap(int x, int y)" }) {
            String b = body(src, decl);
            is(decl + ": found", b != null, true);
            if (b == null) continue;
            String compact = b.replaceAll("\\s+", " ");
            int on = compact.indexOf("cover.passthrough(true);"), tr = compact.indexOf("try {"), fin = compact.indexOf("} finally { cover.passthrough(false); }");
            is(decl + ": the cover is made touch-through before the tap", on >= 0 && tr > on, true);
            is(decl + ": and takes touches again in a finally", tr >= 0 && fin > tr, true);
        }
        String inj = body(src, "public boolean injectTap(int x, int y)");
        is("injectTap runs input tap inside the try", inj != null && inj.indexOf("\"input tap \"") > inj.indexOf("try {") && inj.indexOf("try {") >= 0, true);
    }

    public static void main(String[] argv) throws Exception {
        try {
            testGeometry();
            testScreenshotTapAtVisiblePart();
            testBarOutsideTheTree();
            testScrolledClear();
            testNodeClickWorksUntouched();
            testOrder();
            testPrivileged();
            testNoBlindCentre();
            testDpadFocusAlreadyOnRow();
            testDpadScrollsTheList();
            testFocusGuardNeedsTheRow();
            testFlicker();
            testWatchdog();
            testWatchdogStreak();
            testClasses();
            testHeadline();
            testDump();
            testInjectTapPassthrough();
        } catch (Throwable t) {
            fails++;
            System.out.println("FAIL uncaught " + t);
            t.printStackTrace(System.out);
        }
        System.out.println((fails == 0 ? "PASS " : "FAIL ") + "SdmAcsLadderTest: " + n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
