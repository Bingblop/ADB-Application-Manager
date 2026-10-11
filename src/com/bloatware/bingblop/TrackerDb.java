package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The Exodus Privacy tracker list ({"trackers": {"<id>": {id, name, code_signature, categories, ...}}}), as the app bundles it (assets/trackers.json).
 *
 * code_signature: alternatives joined by '|'; a tracker is present when ANY alternative occurs ANYWHERE (as a substring) in a class name of the app's dex files.
 * Exodus compiles the string as a regular expression, but every real signature only uses letters, digits, '_', '.', '-' and '|', so a plain substring test says the same
 * (the one difference is that a '.' here matches only '.', not '/' or '$'). Signatures of three characters or fewer are ignored, as Exodus does.
 *
 * Contains information from the exodus Privacy tracker database (https://exodus-privacy.eu.org), made available under the Open Database License (ODbL) 1.0.
 */
final class TrackerDb {
    final int n;
    final int[] id;
    final String[] name;
    final String[][] alts;         // code_signature split on '|', empty parts removed
    final String[] categories;     // joined with ", "
    final String[] website;
    final String retrieved;        // when the list was copied from Exodus (yyyy-mm-dd), "" if unknown

    private TrackerDb(int n, String retrieved) {
        this.n = n;
        this.retrieved = retrieved;
        id = new int[n];
        name = new String[n];
        alts = new String[n][];
        categories = new String[n];
        website = new String[n];
    }

    static TrackerDb parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject trackers = root.getJSONObject("trackers");
        JSONObject meta = root.optJSONObject("meta");
        TrackerDb db = new TrackerDb(trackers.length(), meta == null ? "" : meta.optString("retrieved", ""));
        int k = 0;
        List<String> keys = new ArrayList<String>();
        for (Iterator<String> it = trackers.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            JSONObject t = trackers.getJSONObject(key);
            db.id[k] = t.optInt("id", -1);
            db.name[k] = t.optString("name", "?");
            String sig = t.optString("code_signature", "");
            List<String> parts = new ArrayList<String>();
            if (sig.length() > 3) {
                for (String a : sig.split("\\|")) if (!a.isEmpty()) parts.add(a);
            }
            db.alts[k] = parts.toArray(new String[0]);
            StringBuilder c = new StringBuilder();
            JSONArray cats = t.optJSONArray("categories");
            if (cats != null) for (int i = 0; i < cats.length(); i++) { if (c.length() > 0) c.append(", "); c.append(cats.optString(i)); }
            db.categories[k] = c.toString();
            db.website[k] = t.optString("website", "");
            k++;
        }
        return db;
    }

    /** The row of one tracker as the page needs it. */
    JSONObject info(int t) throws JSONException {
        return new JSONObject().put("id", id[t]).put("name", name[t]).put("categories", categories[t]).put("website", website[t]);
    }

    /** Tracker index for an id, or -1. */
    int indexOf(int trackerId) {
        for (int i = 0; i < n; i++) if (id[i] == trackerId) return i;
        return -1;
    }
}
