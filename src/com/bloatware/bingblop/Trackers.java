package com.bloatware.bingblop;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the known tracker libraries (the Exodus Privacy list) in an app by reading the class names in its dex files; nothing is run and nothing is sent anywhere.
 * An app's own apk and its split apks are read together. See {@link TrackerDb} for the list and its licence, {@link DexTypeScanner} for the dex reading.
 *
 * Limits worth knowing: code that was renamed by a code shrinker (R8, ProGuard) hides the library's class names, and a tracker that sits in native code or is only
 * downloaded when the app runs is not seen. A count is "trackers the class names show", never "no tracking at all".
 */
final class Trackers {
    final TrackerDb db;
    private final TrackerMatcher matcher;

    /** What a scan found: the apk(s) had dex code ({@code scannable}) and the ids of the trackers in it, ascending. */
    static final class Scan {
        final boolean scannable;
        final int[] ids;
        Scan(boolean scannable, int[] ids) { this.scannable = scannable; this.ids = ids; }
    }

    private Trackers(TrackerDb db) {
        this.db = db;
        this.matcher = new TrackerMatcher(db);
    }

    static Trackers load(String json) throws JSONException {
        return new Trackers(TrackerDb.parse(json));
    }

    /** A number for the cache: changes whenever the list does. */
    String dbVersion() {
        return db.retrieved + ":" + db.n;
    }

    /**
     * Reads every apk given (base first, then the splits). A file that cannot be read as a zip, or that has no dex code at all (a split with resources only), counts as nothing;
     * when no file had dex code the result is not scannable. A tracker counts when one of its classes is DEFINED in the dex files.
     */
    Scan scan(List<File> apks) {
        TrackerMatcher.Result r = new TrackerMatcher.Result(db.n);
        DexTypeScanner.Stats st = new DexTypeScanner.Stats();
        for (File f : apks) {
            try {
                DexTypeScanner.scanApk(f, matcher, r, st);
            } catch (IOException | RuntimeException ignored) {
                // an unreadable or corrupt file: whatever the others show still counts
            }
        }
        if (st.dexFiles == 0) return new Scan(false, new int[0]);
        List<Integer> ids = new ArrayList<Integer>();
        for (int t = 0; t < db.n; t++) if (r.defined[t]) ids.add(db.id[t]);
        java.util.Collections.sort(ids);
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return new Scan(true, out);
    }
}
