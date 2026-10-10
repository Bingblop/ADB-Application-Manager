package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The open terminal sessions by id. The page always uses the same id for its terminal and restarts it by closing the old session and starting a new
 * one at once, so the old session's end (reported later, from its reader thread) can arrive after the new session was stored under the same id. An end
 * therefore only removes the session that reported it, and only tells the page when no other session holds the id (otherwise the page would be told
 * its new shell had ended, and could no longer reach it).
 *
 * <p>A start takes a ticket for its id when it is requested ({@link #ticket}); the session it creates is published with that ticket
 * ({@link #publish}) and is refused when a newer start or a close of the id came in between, or when the map was drained for shutdown. The caller
 * then closes the refused session itself. Pure Java, no android.* classes.
 */
final class SessionMap<T> {
    private final Map<String, T> map = new HashMap<String, T>();
    private final Map<String, Long> tickets = new HashMap<String, Long>();
    private long last;
    private boolean closed;

    synchronized T get(String id) {
        return id == null ? null : map.get(id);
    }

    /** A start of the session for {@code id} is requested: a new ticket, which makes every older one stale. */
    synchronized long ticket(String id) {
        long t = ++last;
        tickets.put(id, t);
        return t;
    }

    /** Whether {@code ticket} is still the newest start for {@code id} (and the map is open): the page still waits for that start. */
    synchronized boolean isCurrent(String id, long ticket) {
        Long cur = tickets.get(id);
        return !closed && cur != null && cur.longValue() == ticket;
    }

    /**
     * Stores {@code session} under {@code id} if {@code ticket} is still the newest for it and the map is open. Returns false when it is refused (the
     * caller closes the session). The session it replaced, if any, is added to {@code replaced} (the caller closes it).
     */
    synchronized boolean publish(String id, long ticket, T session, List<T> replaced) {
        if (closed) return false;
        Long cur = tickets.get(id);
        if (cur == null || cur.longValue() != ticket) return false;
        T prev = map.put(id, session);
        if (prev != null && prev != session) replaced.add(prev);
        return true;
    }

    /** Removes whatever is stored under {@code id} (an explicit close); a start still in progress for it becomes stale. */
    synchronized T remove(String id) {
        if (id == null) return null;
        tickets.put(id, ++last);
        return map.remove(id);
    }

    /**
     * {@code session} has ended. Removes it if it is still the one stored under {@code id}. Returns whether the page should be told: true when it was
     * the stored session, or when nothing is stored (it was closed on purpose and the page still waits for the end); false when another session now
     * holds the id.
     */
    synchronized boolean ended(String id, T session) {
        if (map.get(id) == session && session != null) {
            map.remove(id);
            return true;
        }
        return !map.containsKey(id);
    }

    /** Shuts the map: returns what was in it, and every later {@link #publish} is refused. */
    synchronized List<T> drain() {
        closed = true;
        List<T> all = new ArrayList<T>(map.values());
        map.clear();
        return all;
    }
}
