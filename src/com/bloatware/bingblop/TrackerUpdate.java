package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fetches a fresh copy of the Exodus Privacy tracker list ("Update the tracker list" in the Trackers sheet) and cuts it down to what the app uses (the same shape as
 * assets/trackers.json). The list is public and needs no key. Nothing about the user or the apps is sent: it is a plain GET.
 * Contains information from the exodus Privacy tracker database (https://exodus-privacy.eu.org), made available under the Open Database License (ODbL) 1.0.
 */
final class TrackerUpdate {
    static String apiUrl = "https://reports.exodus-privacy.eu.org/api/trackers";
    /** A real answer holds hundreds of trackers; fewer means a cut-off or changed feed, which must not replace a good list. */
    static final int MIN_TRACKERS = 300;

    private TrackerUpdate() { }

    /** Downloads and checks the list. {@code today} (yyyy-mm-dd) is written into it as the date it was copied. */
    static String fetch(String today) throws IOException {
        MorpheNet.Response r;
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json");
        try {
            r = MorpheNet.request("GET", apiUrl, h, null);
        } catch (IOException e) {
            throw new IOException("Could not reach Exodus Privacy: " + (e.getMessage() == null ? "no connection" : e.getMessage()));
        }
        if (r.status < 200 || r.status >= 300) throw new IOException("Exodus Privacy answered HTTP " + r.status);
        return slim(r.text, today);
    }

    static String slim(String exodusJson, String today) throws IOException {
        try {
            JSONObject root = new JSONObject(exodusJson == null ? "" : exodusJson.trim());
            JSONObject in = root.optJSONObject("trackers");
            if (in == null) throw new IOException("The answer has no list of trackers");
            JSONObject out = new JSONObject();
            int usable = 0;
            for (Iterator<String> it = in.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject t = in.optJSONObject(key);
                if (t == null || !t.has("id")) continue;
                String sig = t.optString("code_signature", "");
                for (int i = 0; i < sig.length(); i++) {
                    char c = sig.charAt(i);
                    if (!(c == '.' || c == '_' || c == '-' || c == '|' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) { sig = ""; break; }       // the matcher only knows plain names
                }
                if (sig.length() > 3) usable++;
                JSONArray cats = t.optJSONArray("categories");
                out.put(String.valueOf(t.getInt("id")), new JSONObject().put("id", t.getInt("id")).put("name", t.optString("name", "?")).put("code_signature", sig)
                        .put("categories", cats == null ? new JSONArray() : cats).put("website", t.optString("website", "")));
            }
            if (out.length() < MIN_TRACKERS || usable < MIN_TRACKERS - 50) throw new IOException("The answer holds only " + out.length() + " trackers (" + usable + " with code names): not used");
            JSONObject meta = new JSONObject().put("description", "Copy of the Exodus Privacy tracker database (id, name, code_signature, categories, website only).")
                    .put("source", apiUrl).put("retrieved", today).put("count", out.length()).put("license", "ODbL-1.0").put("license_url", "https://opendatacommons.org/licenses/odbl/1-0/")
                    .put("attribution", "Contains information from the exodus Privacy tracker database (https://exodus-privacy.eu.org), made available under the Open Database License (ODbL) 1.0.");
            return new JSONObject().put("meta", meta).put("trackers", out).toString();
        } catch (JSONException e) {
            throw new IOException("The answer is not a tracker list");
        }
    }
}
