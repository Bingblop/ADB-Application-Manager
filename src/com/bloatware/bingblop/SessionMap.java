package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The open terminal sessions by id. The page always uses the same id for its terminal and restarts it by closing the old session and starting a new
 * one at once, so the old session's end (reported later, from its reader thread) can arrive after the new session was stored under the same id. An end
 * therefore only removes the session that reported it, and only tells the page when no newer session has taken over the id (otherwise the page would be
 * told its new shell had ended, and could no longer reach it).
 *
 * <p>A start takes a ticket for its id when it is requested ({@link #ticket}); the session it creates is published with that ticket
 * ({@link #publish}) and is refused when a newer start or a close of the id came in between, or when the map was drained for shutdown. The caller
 * then closes the refused session itself.
 *
 * <p>Every id has a <em>generation</em> that moves when a session is stored under it and when one is removed (a close or a restart). What a session
 * tells the page later (its start, its output, its end) is queued for the UI thread and checked there against the generation it was decided under:
 * a newer start or a close since then makes it stale and it is dropped, while a session that merely ended on its own keeps everything it queued
 * (its last output still reaches the page, in order, before its end). A session that was removed is remembered with the generation of its removal, so
 * its late end is told only while nothing newer has been stored under the id since (a closed session whose end the page waits for), and a session
 * that was replaced is never told. Pure Java, no android.* classes.
 */
final class SessionMap<T> {
    private final Map<String, T> map = new HashMap<String, T>();
    private final Map<String, Long> tickets = new HashMap<String, Long>();
    private final Map<String, Long> generations = new HashMap<String, Long>();
    /**
     * Sessions that left the map without ending: the generation of the id right after (a close), or -1 (replaced by a newer one). Their end is looked
     * up here when it comes.
     */
    private final Map<T, Long> gone = new IdentityHashMap<T, Long>();
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
        return publishGen(id, ticket, session, replaced) >= 0;
    }

    /** Like {@link #publish}, but returns the generation the session was stored under, or -1 when it is refused. */
    synchronized long publishGen(String id, long ticket, T session, List<T> replaced) {
        if (closed) return -1;
        Long cur = tickets.get(id);
        if (cur == null || cur.longValue() != ticket) return -1;
        T prev = map.put(id, session);
        long g = bump(id);
        if (prev != null && prev != session) { replaced.add(prev); gone.put(prev, -1L); }
        return g;
    }

    /** Removes whatever is stored under {@code id} (an explicit close); a start still in progress for it becomes stale. */
    synchronized T remove(String id) {
        if (id == null) return null;
        tickets.put(id, ++last);
        T prev = map.remove(id);
        long g = bump(id);
        if (prev != null) gone.put(prev, g);
        return prev;
    }

    /**
     * {@code session} has ended. Removes it if it is still the one stored under {@code id}. Returns whether the page should be told: true when it was
     * the stored session, or when it was closed on purpose and nothing newer has been stored since (the page still waits for the end); false when
     * another session now holds or has held the id.
     */
    synchronized boolean ended(String id, T session) {
        return endedAt(id, session) >= 0;
    }

    /**
     * Like {@link #ended}, but returns -1 when the page should not be told, otherwise the {@link #generation} of the id at the moment of the decision.
     * The page is told later (on the UI thread); a start or close that comes in between moves the generation, and the notice is then dropped
     * (otherwise the page would see "started B" followed by "A ended" and take B for ended).
     */
    synchronized long endedAt(String id, T session) {
        if (session != null && gone.containsKey(session)) {
            long at = gone.remove(session);
            return at >= 0 && at == generation(id) ? at : -1;
        }
        boolean tell;
        if (map.get(id) == session && session != null) {
            map.remove(id);
            tell = true;
        } else {
            tell = !map.containsKey(id);
        }
        return tell ? generation(id) : -1;
    }

    /** The generation of {@code id}: it moves when a session is stored under it and when one is removed (0 when neither has happened). */
    synchronized long generation(String id) {
        Long g = generations.get(id);
        return g == null ? 0 : g.longValue();
    }

    private long bump(String id) {
        Long g = generations.get(id);
        long n = g == null ? 1L : g.longValue() + 1;
        generations.put(id, n);
        return n;
    }

    /** Shuts the map: returns what was in it, and every later {@link #publish} is refused. */
    synchronized List<T> drain() {
        closed = true;
        List<T> all = new ArrayList<T>(map.values());
        map.clear();
        gone.clear();
        return all;
    }
}
