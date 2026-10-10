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
    /** Sessions that were replaced by a newer one: their late end is never told to the page, even after the replacement is gone too. */
    private final java.util.Set<T> superseded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<T, Boolean>());
    /** How many sessions were stored under each id so far: a notice about an older session is stale once this has moved. */
    private final Map<String, Long> generations = new HashMap<String, Long>();
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
        Long g = generations.get(id);
        generations.put(id, g == null ? 1L : g.longValue() + 1);
        if (prev != null && prev != session) { replaced.add(prev); superseded.add(prev); }
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
        return endedAt(id, session) >= 0;
    }

    /**
     * Like {@link #ended}, but returns -1 when the page should not be told, otherwise the {@link #generation} of the id at the moment of the decision.
     * The page is told later (on the UI thread); a start that is stored in between moves the generation, and the notice is then dropped
     * (otherwise the page would see "started B" followed by "A ended" and take B for ended).
     */
    synchronized long endedAt(String id, T session) {
        if (session != null && superseded.remove(session)) return -1;
        boolean tell;
        if (map.get(id) == session && session != null) {
            map.remove(id);
            tell = true;
        } else {
            tell = !map.containsKey(id);
        }
        return tell ? generation(id) : -1;
    }

    /** The number of sessions stored under {@code id} so far (0 when none). */
    synchronized long generation(String id) {
        Long g = generations.get(id);
        return g == null ? 0 : g.longValue();
    }

    /** Shuts the map: returns what was in it, and every later {@link #publish} is refused. */
    synchronized List<T> drain() {
        closed = true;
        List<T> all = new ArrayList<T>(map.values());
        map.clear();
        return all;
    }
}
