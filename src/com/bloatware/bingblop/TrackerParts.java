package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of an app (its activities, services, receivers and providers) that belong to a known tracker library, so they can be switched off and on
 * with {@code pm disable} / {@code pm enable} on each part (the Trackers box of the app menu, and Disable / Enable Trackers in the batch menu).
 * A part belongs to a tracker when one of the tracker's code signatures occurs in the part's class name on whole name segments ({@link #onSegments}):
 * stricter than the dex scan's plain substring ({@link TrackerDb}), because here a match is switched off. Tune's "com.tune" names a tracker in
 * "com.tune.TuneTracker" but not in TuneIn Radio's own "com.tunein.player.Main". A signature that overlaps the app's own package is skipped too
 * ({@link #ownCode}): the app is the tracker's maker, and its parts are its own code (LiveMe, com.cmcm.live, is not "Cheetah Ads" com.cmcm.).
 * When two trackers match one name, the longer signature (the more specific library) wins.
 * No Android classes: tested on a computer (tests/java/src/TrackerPartsTest.java). MainActivity reads the parts from Android and runs the commands.
 *
 * What switching parts off does and does not do: the tracker's own receivers, services, screens and providers stop (a disabled receiver hears nothing,
 * a disabled service does not start). A tracker that runs inside the app's own code is not stopped by it; a part's state is read back after every
 * command, and only that read decides success (AGENTS.md).
 */
final class TrackerParts {
    private TrackerParts() { }

    /**
     * The batch actions (MainActivity.executeAppAction): every tracker of the app, only the ids after the colon ("trackers_off:49,312"), or only the
     * parts named after "parts=" ("trackers_on:parts=com.a.B,com.a.C": the History's Undo, which switches back exactly what one step changed).
     */
    static final String OFF = "trackers_off", ON = "trackers_on", PARTS = "parts=";
    /** No more ids (or part names) than this in one action: more than the list holds is not a real request. */
    static final int MAX_IDS = 1000;

    /** One part of the app, as Android lists it. {@code override} is the state someone set with pm (or the app itself): true, false, or null for none. */
    static final class Part {
        final String name, kind;
        final boolean manifestEnabled;
        final Boolean override;
        Part(String name, String kind, boolean manifestEnabled, Boolean override) {
            this.name = name; this.kind = kind; this.manifestEnabled = manifestEnabled; this.override = override;
        }
        /** Whether the part runs now: the override when there is one, else what the app's manifest says. */
        boolean on() { return override != null ? override.booleanValue() : manifestEnabled; }
        /** Switched off with pm (an explicit DISABLED state), so Enable has something to undo. A part the manifest ships off, that nobody changed, is not. */
        boolean switchedOff() { return override != null && !override.booleanValue(); }
    }

    /** One tracker (its index in the {@link TrackerDb}) with the app's parts that belong to it, sorted by name. */
    static final class Group {
        final int index;
        final List<Part> parts = new ArrayList<Part>();
        Group(int index) { this.index = index; }
        int on() { int c = 0; for (Part p : parts) if (p.on()) c++; return c; }
        int switchedOff() { int c = 0; for (Part p : parts) if (p.switchedOff()) c++; return c; }
    }

    /**
     * The tracker a part of the app {@code pkg} belongs to: the index of the one whose signature matches the class name ({@link #onSegments}), is not
     * the app's own code ({@link #ownCode}) and is the longest (the lower id on a tie), or -1 for none. A null pkg skips the own-code test.
     */
    static int trackerOf(TrackerDb db, String pkg, String className) {
        if (db == null || className == null || className.isEmpty()) return -1;
        int best = -1, bestLen = 0;
        for (int t = 0; t < db.n; t++) {
            for (String alt : db.alts[t]) {
                int len = alt.length();
                if (len < bestLen || (len == bestLen && best >= 0 && db.id[t] >= db.id[best])) continue;
                if (onSegments(className, alt) && !ownCode(pkg, alt)) { best = t; bestLen = len; }
            }
        }
        return best;
    }

    /**
     * Whether a signature overlaps the app's own package, so what it matches is the app's own code: the package lies in the signature's namespace
     * (LiveMe com.cmcm.live under com.cmcm., the Hound app com.hound.android.app under com.hound), or the signature names a package inside the app's
     * (com.tencent.mm.plugin.location. inside WeChat, com.tencent.mm; com.google.android.gms.measurement inside Google Play services).
     */
    static boolean ownCode(String pkg, String sig) {
        if (pkg == null || pkg.isEmpty() || sig == null || sig.isEmpty()) return false;
        if (onSegments(pkg + ".", sig)) return true;
        String s = sig.endsWith(".") ? sig.substring(0, sig.length() - 1) : sig;
        return s.equals(pkg) || s.startsWith(pkg + ".");
    }

    /**
     * Whether a signature occurs in a class name on whole name segments: it starts at the start of the name or right after a '.' or '$' (or itself starts
     * with '.'), and ends at the end of the name or right before a '.' or '$' (or itself ends with '.'). So "com.tune" is in "com.tune.Receiver" and not in
     * "com.tunein.player.Main"; ".sizmek." is anywhere it occurs.
     */
    static boolean onSegments(String name, String sig) {
        if (name == null || sig == null || sig.isEmpty()) return false;
        for (int i = name.indexOf(sig); i >= 0; i = name.indexOf(sig, i + 1)) {
            int end = i + sig.length();
            boolean head = i == 0 || sig.charAt(0) == '.' || isSep(name.charAt(i - 1));
            boolean tail = end == name.length() || sig.charAt(sig.length() - 1) == '.' || isSep(name.charAt(end));
            if (head && tail) return true;
        }
        return false;
    }

    private static boolean isSep(char c) { return c == '.' || c == '$'; }

    /** The parts of the app {@code pkg} that belong to a tracker, grouped by tracker (in the order of the trackers' names), each group's parts by name. Other parts are left out. */
    static List<Group> group(TrackerDb db, String pkg, List<Part> parts) {
        Map<Integer, Group> by = new LinkedHashMap<Integer, Group>();
        if (parts != null) {
            for (Part p : parts) {
                int t = trackerOf(db, pkg, p.name);
                if (t < 0) continue;
                Group g = by.get(t);
                if (g == null) { g = new Group(t); by.put(t, g); }
                g.parts.add(p);
            }
        }
        List<Group> out = new ArrayList<Group>(by.values());
        final TrackerDb d = db;
        Collections.sort(out, new Comparator<Group>() {
            @Override public int compare(Group a, Group b) {
                int c = d.name[a.index].compareToIgnoreCase(d.name[b.index]);
                return c != 0 ? c : Integer.compare(d.id[a.index], d.id[b.index]);
            }
        });
        for (Group g : out) {
            Collections.sort(g.parts, new Comparator<Part>() {
                @Override public int compare(Part a, Part b) { return a.name.compareTo(b.name); }
            });
        }
        return out;
    }

    /** Whether an action string is one of the tracker actions: trackers_off or trackers_on, alone or followed by ":" and ids. */
    static boolean isAction(String action) {
        return action != null && (action.equals(OFF) || action.equals(ON) || action.startsWith(OFF + ":") || action.startsWith(ON + ":"));
    }

    /** True for trackers_on (switch parts back on), false for trackers_off. Only meaningful when {@link #isAction} holds. */
    static boolean enables(String action) { return action != null && action.startsWith(ON); }

    /**
     * The tracker ids an action names: null when it names none (no colon: every tracker of the app), the ids otherwise (duplicates dropped, in the order
     * given). Anything that is not a list of whole numbers, or more of them than {@link #MAX_IDS}, gives an empty array: nothing is switched.
     */
    static int[] ids(String action) {
        if (action == null) return new int[0];
        int c = action.indexOf(':');
        if (c < 0 || action.startsWith(PARTS, c + 1)) return null;
        String rest = action.substring(c + 1).trim();
        if (rest.isEmpty()) return new int[0];
        String[] bits = rest.split(",", -1);
        if (bits.length > MAX_IDS) return new int[0];
        List<Integer> out = new ArrayList<Integer>();
        for (String b : bits) {
            String s = b.trim();
            if (!s.matches("[0-9]{1,9}")) return new int[0];
            Integer v = Integer.valueOf(s);
            if (!out.contains(v)) out.add(v);
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /**
     * The part names an action names after "parts=": null when it names none (every part of the trackers asked for), the names otherwise (duplicates
     * dropped). Anything that is not a list of class names, or more of them than {@link #MAX_IDS}, gives an empty list: nothing is switched.
     */
    static List<String> parts(String action) {
        if (action == null) return null;
        int c = action.indexOf(':');
        if (c < 0 || !action.startsWith(PARTS, c + 1)) return null;
        List<String> out = new ArrayList<String>();
        String rest = action.substring(c + 1 + PARTS.length());
        if (rest.isEmpty()) return out;
        String[] bits = rest.split(",", -1);
        if (bits.length > MAX_IDS) return out;
        for (String b : bits) {
            if (!b.matches("[A-Za-z0-9_$][A-Za-z0-9._$]{0,299}")) { out.clear(); return out; }
            if (!out.contains(b)) out.add(b);
        }
        return out;
    }

    /** Whether a part is one of the names asked for (null: every part). */
    static boolean wanted(Part p, List<String> parts) { return parts == null || parts.contains(p.name); }

    /** Whether a group is one of the ids asked for (null: every tracker). */
    static boolean wanted(TrackerDb db, Group g, int[] ids) {
        if (ids == null) return true;
        for (int id : ids) if (db.id[g.index] == id) return true;
        return false;
    }

    /** Whether a part needs a command for the asked state: to switch off, a part that runs; to switch on, a part that was switched off (never one the manifest ships off). */
    static boolean needs(Part p, boolean enable) { return enable ? p.switchedOff() : p.on(); }

    /** The short name of a part for messages: the last segment of its class name. */
    static String shortName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;
    }

    /**
     * The text a run leaves in the results: what was switched (with each tracker's name and its parts), what was already so, and every part Android
     * did not change, with its answer. {@code changed} and {@code failed} hold "tracker name / part name" pairs; {@code failedOut} the answers, in order.
     */
    static String report(boolean enable, int partsInApp, List<String[]> changed, int already, List<String[]> failed, List<String> failedOut) {
        StringBuilder sb = new StringBuilder();
        if (partsInApp == 0) return "No tracker parts in this app: the trackers it holds, if any, run inside its own code.";
        if (!changed.isEmpty()) {
            sb.append(enable ? "Enabled " : "Disabled ").append(changed.size()).append(changed.size() == 1 ? " tracker part" : " tracker parts").append(":");
            appendByTracker(sb, changed);
        }
        if (already > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(already).append(already == 1 ? " part was " : " parts were ").append(enable ? "already on." : "already off.");
        }
        if (!failed.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Not changed by Android (").append(failed.size()).append("):");
            for (int i = 0; i < failed.size(); i++) {
                String out = i < failedOut.size() ? failedOut.get(i) : "";
                sb.append("\n  ").append(failed.get(i)[0]).append(": ").append(shortName(failed.get(i)[1]));
                if (out != null && !out.trim().isEmpty()) sb.append(" - ").append(out.trim());
            }
        }
        if (sb.length() == 0) sb.append(enable ? "No tracker part was switched off, so nothing was enabled." : "Every tracker part was already off.");
        return sb.toString();
    }

    private static void appendByTracker(StringBuilder sb, List<String[]> rows) {
        Map<String, List<String>> by = new LinkedHashMap<String, List<String>>();
        for (String[] r : rows) {
            List<String> l = by.get(r[0]);
            if (l == null) { l = new ArrayList<String>(); by.put(r[0], l); }
            l.add(shortName(r[1]));
        }
        for (Map.Entry<String, List<String>> e : by.entrySet()) {
            sb.append("\n  ").append(e.getKey()).append(": ");
            for (int i = 0; i < e.getValue().size(); i++) { if (i > 0) sb.append(", "); sb.append(e.getValue().get(i)); }
        }
    }
}
