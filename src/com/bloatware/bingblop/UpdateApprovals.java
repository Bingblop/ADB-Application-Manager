package com.bloatware.bingblop;

import java.util.HashMap;
import java.util.Map;

/**
 * The yes a person gave to one "Update all" question, kept per app until that app's install uses it. A yes covers one particular update, as the update check
 * found it (its version and its source) and for the working mode the question named, once: it is spent when used, it is dropped when the working mode is
 * not a privileged one, it asks again when the mode is another privileged one, it expires after
 * {@link #TTL_MS}, and the whole set goes when the queue ends, when a new question is asked or when the update check runs again. Anything else asks its own
 * question. Pure Java, no android.* classes.
 */
final class UpdateApprovals {
    /** How long a yes holds: the queue downloads one app after the other, so it must outlast a normal queue but not a forgotten one. */
    static final long TTL_MS = 15L * 60 * 1000;

    private static final class Entry {
        final String version, source, mode;
        final long at;
        Entry(String version, String source, String mode, long at) { this.version = version; this.source = source; this.mode = mode; this.at = at; }
    }

    private final Map<String, Entry> map = new HashMap<String, Entry>();

    /** The answer to a new question, asked for {@code mode}: the apps approved now (package -> {version, source}) replace every earlier approval. */
    synchronized void replace(Map<String, String[]> apps, String mode, long now) {
        map.clear();
        for (Map.Entry<String, String[]> e : apps.entrySet()) map.put(e.getKey(), new Entry(e.getValue()[0], e.getValue()[1], mode == null ? "" : mode, now));
    }

    /**
     * Whether {@code pkg} may install without its own question: it was approved, for this version and this source, through the mode that is the working
     * mode now, not longer ago than {@link #TTL_MS}. The approval is spent either way; a mode that is not privileged drops all of them.
     */
    synchronized boolean take(String pkg, String version, String source, String mode, long now) {
        if (!InstallGuards.isPrivilegedMode(mode)) { map.clear(); return false; }
        Entry e = map.remove(pkg);
        return e != null && now - e.at >= 0 && now - e.at < TTL_MS && e.mode.equals(mode) && e.version.equals(version == null ? "" : version) && e.source.equals(source == null ? "" : source);
    }

    synchronized void clear() { map.clear(); }

    synchronized int size() { return map.size(); }
}
