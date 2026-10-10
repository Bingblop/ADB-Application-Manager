package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open terminal sessions by id. The page always uses the same id for its terminal and restarts it by closing the old session and starting a new
 * one at once, so the old session's end (reported later, from its reader thread) can arrive after the new session was stored under the same id. An end
 * therefore only removes the session that reported it, and only tells the page when no other session holds the id (otherwise the page would be told
 * its new shell had ended, and could no longer reach it). Pure Java, no android.* classes.
 */
final class SessionMap<T> {
    private final Map<String, T> map = new ConcurrentHashMap<String, T>();

    T get(String id) {
        return id == null ? null : map.get(id);
    }

    /** Stores {@code session} under {@code id}; returns the session it replaced (the caller closes it), or null. */
    T put(String id, T session) {
        return map.put(id, session);
    }

    /** Removes whatever is stored under {@code id} (an explicit close). */
    T remove(String id) {
        return id == null ? null : map.remove(id);
    }

    /**
     * {@code session} has ended. Removes it if it is still the one stored under {@code id}. Returns whether the page should be told: true when it was
     * the stored session, or when nothing is stored (it was closed on purpose and the page still waits for the end); false when another session now
     * holds the id.
     */
    boolean ended(String id, T session) {
        if (map.remove(id, session)) return true;
        return !map.containsKey(id);
    }

    /** Empties the map and returns what was in it. */
    List<T> drain() {
        List<T> all = new ArrayList<T>(map.values());
        map.clear();
        return all;
    }
}
