package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the accessibility cache clearing needs to see and say about a screen, without any android.* class: a compact dump of the Settings window for a failure report, a
 * signature of a window to tell whether a click changed anything, and the geometry that finds the part of a row that is really visible (a floating bar of the page can cover
 * a row, as the bottom pill of One UI 9's App info does).
 *
 * <p>Written for this port (SD Maid SE has a log line per node in debug builds only). The dump holds the labels of the Settings app's own page and nothing else: the window
 * package, and for each node its class, text, content description, view id, three flags, its place on the screen and its depth, at most {@link #MAX_NODES} nodes. A window of any
 * other app (a lock screen, an app locker) is named by its package and nothing of its content is recorded.
 */
public final class SdmAcsDiag {
    private SdmAcsDiag() {}

    public static final int MAX_NODES = 300, MAX_TEXT = 80, HARD_WALK = 5000, MAX_TRIED = 40;

    /** Why the attempt for one app failed, and what is needed to find out why: the plan and step, what was tried, and the screen it ended on. */
    public static final class Failure {
        public String pkg = "", label = "", code = "OTHER", plan = "", step = "", windowPkg = "", launchError = "", message = "", screen = "";
        public long when;
        public final List<String> tried = new ArrayList<String>();

        /** The text the person copies: a header with the phone, the plan, the step, the reason and what was tried, then the screen dump. {@code device}: "Samsung SM-948U1, Android 17 (API 37)". */
        public String toReport(String device) {
            StringBuilder b = new StringBuilder();
            b.append("SD Maid accessibility automation: screen report\n");
            if (device != null && !device.isEmpty()) b.append("Phone: ").append(device).append('\n');
            b.append("Plan: ").append(plan).append("   Step: ").append(step).append('\n');
            b.append("App: ").append(label.isEmpty() ? pkg : label + " (" + pkg + ")").append('\n');
            b.append("Reason: ").append(code);
            if (!message.isEmpty()) b.append(" - ").append(clip(message, 300));
            b.append('\n');
            if (!launchError.isEmpty()) b.append("Opening App info failed: ").append(clip(launchError, 200)).append('\n');
            if (!windowPkg.isEmpty()) b.append("Window in front: ").append(windowPkg).append('\n');
            b.append("Tried (").append(tried.size()).append("):\n");
            for (int i = 0; i < tried.size(); i++) b.append(' ').append(i + 1).append(". ").append(clip(tried.get(i), 240)).append('\n');
            b.append(screen.isEmpty() ? "Screen: not recorded\n" : screen);
            return b.toString();
        }
    }

    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String q(String s) { return s == null || s.isEmpty() ? "-" : "\"" + clip(s, MAX_TEXT) + "\""; }

    private static String shortId(String id) {
        if (id == null || id.isEmpty()) return "-";
        int i = id.indexOf(":id/");
        return i >= 0 && id.startsWith("com.android.settings") ? id.substring(i + 4) : id;
    }

    /**
     * The window as text: a header line (package, window bounds, node count), then one line per node in document order, at most {@link #MAX_NODES}; the rest is counted. Only the
     * packages in {@code textPackages} get their nodes written; any other window is named and not read. The check is made for every node, not only for the root: a node of another
     * package inside the Settings window (an embedded view, an overlay) is listed by its class, place and flags only, its text, description and id are not recorded.
     */
    public static String dump(SdmAcsPlan.Node root, Collection<String> textPackages) {
        if (root == null) return "Screen: no window\n";
        StringBuilder b = new StringBuilder();
        String pkg = root.packageName();
        int[] wb = root.bounds();
        b.append("Screen: window ").append(pkg).append(wb == null || wb.length < 4 ? "" : " [" + wb[0] + "," + wb[1] + "][" + wb[2] + "," + wb[3] + "]");
        if (pkg == null || !textPackages.contains(pkg)) {
            b.append(" - not the Settings app: its content is not recorded\n");
            return b.toString();
        }
        List<SdmAcsPlan.Node> nodes = new ArrayList<SdmAcsPlan.Node>();
        List<Integer> depths = new ArrayList<Integer>();
        int total = 0;
        java.util.ArrayDeque<SdmAcsPlan.Node> stack = new java.util.ArrayDeque<SdmAcsPlan.Node>();
        java.util.ArrayDeque<Integer> dstack = new java.util.ArrayDeque<Integer>();
        stack.push(root); dstack.push(0);
        while (!stack.isEmpty() && total < HARD_WALK) {
            SdmAcsPlan.Node cur = stack.pop();
            int d = dstack.pop();
            total++;
            if (nodes.size() < MAX_NODES) { nodes.add(cur); depths.add(d); }
            int n;
            try { n = cur.childCount(); } catch (RuntimeException e) { n = 0; }
            for (int i = n - 1; i >= 0; i--) {
                SdmAcsPlan.Node c;
                try { c = cur.child(i); } catch (RuntimeException e) { c = null; }
                if (c != null) { stack.push(c); dstack.push(d + 1); }
            }
        }
        b.append(", ").append(total).append(total >= HARD_WALK ? "+" : "").append(" nodes\n");
        b.append("(d=depth, flags c=clickable e=enabled s=scrollable)\n");
        for (int i = 0; i < nodes.size(); i++) {
            SdmAcsPlan.Node n = nodes.get(i);
            int[] bd = n.bounds();
            String np;
            try { np = n.packageName(); } catch (RuntimeException e) { np = null; }
            boolean own = np != null && textPackages.contains(np);
            b.append("d=").append(depths.get(i)).append(' ').append(shortClass(n.className()));
            if (own) {
                b.append(" id=").append(shortId(n.viewId()))
                        .append(" text=").append(q(n.text()))
                        .append(" desc=").append(q(n.contentDesc()));
            } else {
                b.append(" other-app=").append(np == null ? "?" : np).append(" (content not recorded)");
            }
            b.append(" flags=").append(n.clickable() ? 'c' : '-').append(n.enabled() ? 'e' : '-').append(n.scrollable() ? 's' : '-')
                    .append(bd == null || bd.length < 4 ? "" : " [" + bd[0] + "," + bd[1] + "][" + bd[2] + "," + bd[3] + "]").append('\n');
        }
        if (total > nodes.size()) b.append("... ").append(total - nodes.size()).append(total >= HARD_WALK ? "+" : "").append(" more nodes not listed\n");
        return b.toString();
    }

    private static String shortClass(String c) {
        if (c == null || c.isEmpty()) return "-";
        int i = c.lastIndexOf('.');
        return i >= 0 ? c.substring(i + 1) : c;
    }

    // ---------------------------------------------------------------------------------------------------------- did the screen change

    /** The words of a window: its package and every text and content description on it. */
    public static Set<String> signature(SdmAcsPlan.Node root) {
        Set<String> out = new HashSet<String>();
        if (root == null) return out;
        out.add("pkg:" + root.packageName());
        for (SdmAcsPlan.Node n : SdmAcsPlan.crawl(root)) {
            String t = n.text(), d = n.contentDesc();
            if (t != null && !t.isEmpty()) out.add("t:" + t.toLowerCase(Locale.ROOT));
            if (d != null && !d.isEmpty()) out.add("d:" + d.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** |a and b| / |a or b|: 1.0 for the same words, near 0 for another page; two empty sets are the same. */
    public static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int common = 0;
        for (String s : a) if (b.contains(s)) common++;
        int union = a.size() + b.size() - common;
        return union == 0 ? 1.0 : (double) common / union;
    }

    // ---------------------------------------------------------------------------------------------------------- geometry

    static boolean usable(int[] b) { return b != null && b.length >= 4 && b[0] < b[2] && b[1] < b[3]; }

    static long area(int[] b) { return usable(b) ? (long) (b[2] - b[0]) * (b[3] - b[1]) : 0; }

    static int[] intersect(int[] a, int[] b) {
        if (!usable(a) || !usable(b)) return new int[] { 0, 0, 0, 0 };
        int l = Math.max(a[0], b[0]), t = Math.max(a[1], b[1]), r = Math.min(a[2], b[2]), bo = Math.min(a[3], b[3]);
        return l < r && t < bo ? new int[] { l, t, r, bo } : new int[] { 0, 0, 0, 0 };
    }

    static boolean contains(int[] outer, int[] inner) { return usable(outer) && usable(inner) && outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3]; }

    static boolean isAncestor(SdmAcsPlan.Node maybeAncestor, SdmAcsPlan.Node n) {
        SdmAcsPlan.Node p = n.parent();
        for (int i = 0; i < 64 && p != null; i++) { if (p.equals(maybeAncestor)) return true; p = p.parent(); }
        return false;
    }

    /**
     * The places on the screen of what sits on top of {@code anchor}: a node that is not the anchor, nothing the anchor holds and nothing that holds it, has some text or can be clicked,
     * overlaps it in part and does not cover it completely. A floating bar is taken as the whole bar (the nearest parents up to four levels, while they are no bigger than half the window and
     * do not hold the anchor), because the gaps between its buttons are as covered as the buttons.
     */
    public static List<int[]> obstacles(List<SdmAcsPlan.Node> tree, SdmAcsPlan.Node anchor, int[] window) {
        List<int[]> out = new ArrayList<int[]>();
        int[] ab = anchor.bounds();
        if (!usable(ab)) return out;
        long half = usable(window) ? area(window) / 2 : Long.MAX_VALUE;
        for (SdmAcsPlan.Node n : tree) {
            if (n.equals(anchor) || isAncestor(anchor, n) || isAncestor(n, anchor)) continue;
            boolean substantial = n.clickable() || (n.text() != null && !n.text().isEmpty()) || (n.contentDesc() != null && !n.contentDesc().isEmpty());
            if (!substantial) continue;
            int[] nb = n.bounds();
            int[] hit = intersect(ab, nb);
            if (!usable(hit) || contains(nb, ab)) continue;
            SdmAcsPlan.Node cur = n;
            for (int up = 0; up < 4; up++) {
                SdmAcsPlan.Node p = cur.parent();
                if (p == null || isAncestor(p, anchor) || p.equals(anchor)) break;
                int[] pb = p.bounds();
                if (!usable(pb) || area(pb) > half || contains(pb, ab)) break;
                cur = p;
            }
            int[] cb = cur.bounds();
            out.add(new int[] { cb[0], cb[1], cb[2], cb[3] });
        }
        return out;
    }

    /**
     * The middle of the biggest part of {@code anchor} (cut to the window) that none of the obstacles covers, as {x, y}; null when no part is at least {@code minPx} wide and tall.
     * The pieces are what is left of the anchor on the four sides of each obstacle.
     */
    public static int[] visibleCentre(SdmAcsPlan.Node anchor, List<int[]> obstacles, int[] window, int minPx) {
        int[] base = anchor.bounds();
        if (!usable(base)) return null;
        if (usable(window)) base = intersect(base, window);
        if (!usable(base)) return null;
        List<int[]> pieces = new ArrayList<int[]>();
        pieces.add(base);
        for (int[] o : obstacles) {
            List<int[]> next = new ArrayList<int[]>();
            for (int[] p : pieces) {
                int[] hit = intersect(p, o);
                if (!usable(hit)) { next.add(p); continue; }
                if (hit[1] > p[1]) next.add(new int[] { p[0], p[1], p[2], hit[1] });
                if (hit[3] < p[3]) next.add(new int[] { p[0], hit[3], p[2], p[3] });
                if (hit[0] > p[0]) next.add(new int[] { p[0], hit[1], hit[0], hit[3] });
                if (hit[2] < p[2]) next.add(new int[] { hit[2], hit[1], p[2], hit[3] });
            }
            pieces = next;
        }
        int[] best = null;
        for (int[] p : pieces) {
            if (p[2] - p[0] < minPx || p[3] - p[1] < minPx) continue;
            if (best == null || area(p) > area(best)) best = p;
        }
        return best == null ? null : new int[] { (best[0] + best[2]) / 2, (best[1] + best[3]) / 2 };
    }
}
