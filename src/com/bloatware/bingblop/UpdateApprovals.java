package com.bloatware.bingblop;

import java.util.HashMap;
import java.util.Map;

/**
 * The yes a person gave to one "Update all" question, kept per app until that app's install uses it. A yes covers one particular update, as the update check
 * found it (its version and its source), once: it is spent when used, it is dropped when the working mode is not a privileged one, it expires after
 * {@link #TTL_MS}, and the whole set goes when the queue ends, when a new question is asked or when the update check runs again. Anything else asks its own
 * question. Pure Java, no android.* classes.
 */
final class UpdateApprovals {
    /** How long a yes holds: the queue downloads one app after the other, so it must outlast a normal queue but not a forgotten one. */
    static final long TTL_MS = 15L * 60 * 1000;

    private static final class Entry {
        final String version, source;
        final long at;
        Entry(String version, String source, long at) { this.version = version; this.source = source; this.at = at; }
    }

    private final Map<String, Entry> map = new HashMap<String, Entry>();

    /** The answer to a new question: the apps approved now replace every earlier approval. */
    synchronized void replace(Map<String, String[]> apps, long now) {
        map.clear();
        for (Map.Entry<String, String[]> e : apps.entrySet()) map.put(e.getKey(), new Entry(e.getValue()[0], e.getValue()[1], now));
    }

    /**
     * Whether {@code pkg} may install without its own question: it was approved, for this version and this source, not longer ago than {@link #TTL_MS},
     * and the working mode is privileged now. The approval is spent either way; a mode that is not privileged drops all of them.
     */
    synchronized boolean take(String pkg, String version, String source, long now, boolean privileged) {
        if (!privileged) { map.clear(); return false; }
        Entry e = map.remove(pkg);
        return e != null && now - e.at >= 0 && now - e.at < TTL_MS && e.version.equals(version == null ? "" : version) && e.source.equals(source == null ? "" : source);
    }

    synchronized void clear() { map.clear(); }

    synchronized int size() { return map.size(); }
}
