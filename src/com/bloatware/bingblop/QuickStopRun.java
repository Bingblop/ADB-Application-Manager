package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One run of the Stop apps tile or widget button ({@link QuickActions#ACTION_STOP_LIST}): which apps of the quick list it force-stops and which it
 * leaves alone, how far it is, what its notification says ({@link QuickStopNotice}), and Cancel from that notification. No Android classes: tested
 * on a computer.
 *
 * Left alone, whatever the list says, because stopping them would break the run itself or the window that shows it: this app (the run lives in it),
 * the app that draws this app's page (the WebView provider) and Media Picker (com.google.android.providers.media.module).
 */
public final class QuickStopRun {

    /** Media Picker, Google's media provider module. */
    static final String MEDIA_PICKER = "com.google.android.providers.media.module";
    /** The other names of the same two parts: the media provider module in the Android Open Source Project and before Android 11, and the WebView apps. */
    private static final String[] ALWAYS_LEFT_ALONE = {
        MEDIA_PICKER, "com.android.providers.media.module", "com.android.providers.media",
        "com.google.android.webview", "com.android.webview",
    };

    /** The notification is posted at most this often (the system drops updates above about five a second). */
    static final long MIN_POST_GAP_MS = 250;
    /** A run that has not ended after this long is taken to be dead (its process was killed): the next tap may start a new one. */
    static final long STALE_MS = 10 * 60 * 1000L;

    private final String listId, listName;
    private final List<String> targets = new ArrayList<String>(), skipped = new ArrayList<String>();
    private int done, ok, failed;
    private String current = "";
    private boolean cancelled, closed, hidden;
    private long lastPostAt = -1;

    /**
     * @param listId     the quick list's id (what a tap on the notification opens)
     * @param listName   its name, shown in the notification
     * @param listed     the package names as the list holds them: blanks and repeats are dropped, the order is kept
     * @param leaveAlone the package names that are never stopped (see {@link #protectedPackages})
     */
    public QuickStopRun(String listId, String listName, List<String> listed, Set<String> leaveAlone) {
        this.listId = listId == null ? "" : listId;
        this.listName = listName == null ? "" : listName.trim();
        Set<String> seen = new LinkedHashSet<String>();
        if (listed != null) {
            for (String raw : listed) {
                String p = raw == null ? "" : raw.trim();
                if (p.isEmpty() || !seen.add(p)) continue;
                if (leaveAlone != null && leaveAlone.contains(p)) skipped.add(p); else targets.add(p);
            }
        }
    }

    /**
     * The packages the tile never force-stops: this app, the WebView provider the phone uses now (it can be Chrome, a WebView app or a beta; blank
     * when the phone does not say), Media Picker, and the other names of the media provider and of the WebView apps.
     */
    public static Set<String> protectedPackages(String ownPackage, String webViewPackage) {
        Set<String> s = new LinkedHashSet<String>();
        if (ownPackage != null && !ownPackage.trim().isEmpty()) s.add(ownPackage.trim());
        if (webViewPackage != null && !webViewPackage.trim().isEmpty()) s.add(webViewPackage.trim());
        Collections.addAll(s, ALWAYS_LEFT_ALONE);
        return s;
    }

    public String listId() { return listId; }
    public String listName() { return listName; }
    /** The apps this run force-stops, in the list's order. */
    public List<String> targets() { return Collections.unmodifiableList(targets); }
    /** The apps of the list that are left alone. */
    public List<String> skipped() { return Collections.unmodifiableList(skipped); }
    public int total() { return targets.size(); }

    // ------------------------------------------------------------------------------------------ progress

    /** The app that is about to be stopped (its name as people know it). */
    public synchronized void starting(String label) { current = label == null ? "" : label.trim(); }

    /** The app that was being stopped is done: {@code success} is whether the phone said it worked. */
    public synchronized void finished(boolean success) {
        if (done >= targets.size()) return;
        done++;
        if (success) ok++; else failed++;
    }

    public synchronized int done() { return done; }
    public synchronized int ok() { return ok; }
    public synchronized int failed() { return failed; }

    /** The place of the app being stopped, counted from 1 (never past the last). */
    public synchronized int position() { return Math.min(targets.size(), done + 1); }

    /** How much of the list is stopped, 0 to 100. */
    public synchronized int percent() { return targets.isEmpty() ? 0 : Math.min(100, done * 100 / targets.size()); }

    /** The line under the title of the notification: "3 of 12 · Chrome". */
    public synchronized String progressText() {
        String s = position() + " of " + targets.size();
        return current.isEmpty() ? s : s + " · " + current;
    }

    /** The few characters of a Live Update's chip in the status bar: "3/12". */
    public synchronized String shortText() { return position() + "/" + targets.size(); }

    /** True when the notification may be posted at {@code now} (milliseconds): the first time, then not again within {@link #MIN_POST_GAP_MS}. */
    public synchronized boolean shouldPost(long now) {
        if (lastPostAt >= 0 && now >= lastPostAt && now - lastPostAt < MIN_POST_GAP_MS) return false;
        lastPostAt = now;
        return true;
    }

    // ------------------------------------------------------------------------------------------ Cancel and the notification

    /** Cancel: the apps not reached yet are left as they are, and the notification is never posted again. */
    public synchronized void cancel() { cancelled = true; closed = true; }
    public synchronized boolean cancelled() { return cancelled; }
    /** The run is over (or cancelled): the notification is never posted again. */
    public synchronized void close() { closed = true; }
    /** The notification was swiped away: the run goes on, without showing it again. */
    public synchronized void hide() { hidden = true; }
    /** True while the notification may be posted. */
    public synchronized boolean open() { return !closed && !hidden; }

    // ------------------------------------------------------------------------------------------ the words at the end

    /** What the toast says when the run is over. */
    public synchronized String summary() {
        int t = targets.size();
        String aside = skipped.isEmpty() ? "" : ", " + skipped.size() + " left alone";
        if (t == 0) {
            return skipped.isEmpty() ? "The quick list has no apps to stop"
                    : "Nothing to stop: " + (skipped.size() == 1 ? "the only app in the list is" : "the apps in the list are") + " left alone on purpose";
        }
        if (cancelled) return "Cancelled after " + done + " of " + t + " app" + (t == 1 ? "" : "s") + (failed > 0 ? ", " + failed + " failed" : "") + aside;
        return "Force-stopped " + ok + " app" + (ok == 1 ? "" : "s") + (failed > 0 ? ", " + failed + " failed" : "") + aside;
    }

    // ------------------------------------------------------------------------------------------ the run that is going

    private static QuickStopRun active;
    private static long activeSince;

    /** Makes {@code run} the one that is going. False when another run is going (and is not stale): a second tap does not start a second run. */
    public static synchronized boolean begin(QuickStopRun run, long now) {
        if (active != null && active != run && now >= activeSince && now - activeSince < STALE_MS) return false;
        active = run;
        activeSince = now;
        return true;
    }

    /** The run is over. */
    public static synchronized void end(QuickStopRun run) { if (active == run) active = null; }

    /** The run that is going, or null. */
    public static synchronized QuickStopRun active() { return active; }

    /** Whether the Kill Apps tile should show its ACTIVE (lit) state: only while a run is going; idle (no run) is INACTIVE. */
    public static synchronized boolean tileActive() { return active != null; }

    /** Cancel for the run that is going; false when none is. */
    public static synchronized boolean cancelActive() {
        QuickStopRun r = active;
        if (r == null) return false;
        r.cancel();
        return true;
    }

    // ------------------------------------------------------------------------------------------ the tap on the notification

    /**
     * In onCreate: a tap on the notification that starts the window shows the quick list's sheet once. {@code restored} is true with a saved state
     * (recreate() or a restore), and the flag marks a start from Recents: both bring the same old intent back, which must not open the sheet again.
     */
    static boolean openAtCreate(boolean restored, int flags) {
        return !restored && (flags & IncomingInstall.FLAG_LAUNCHED_FROM_HISTORY) == 0;
    }
}
