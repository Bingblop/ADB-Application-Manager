package com.bloatware.bingblop;

import java.util.HashMap;

/**
 * Which open of an archive is the newest. Opening a compressed tar runs on a worker from a cached thread pool, so the order in which workers reach
 * the code is not the order the page asked in: a ticket is therefore taken when the open is <em>requested</em> ({@link #issue}, on the bridge
 * thread) and handed to the worker, never when the worker starts. A worker that finds its ticket is no longer the newest of its archive
 * ({@link #isNewest}: another open of it was requested, or Close / Release / the end of the window dropped it) stops without touching the
 * newer open's ticket, and only the newest open may publish its result. Pure Java, no android.* classes.
 */
final class ArchiveTickets {
    private long seq;
    private final HashMap<String, Long> newest = new HashMap<String, Long>();

    /** A new ticket for {@code path}; every older one for it is stale from now on. */
    synchronized long issue(String path) {
        long t = ++seq;
        newest.put(path, t);
        return t;
    }

    /** Whether {@code ticket} is still the newest of {@code path}. */
    synchronized boolean isNewest(String path, long ticket) {
        Long n = newest.get(path);
        return n != null && n.longValue() == ticket;
    }

    /** The open that held {@code ticket} is over: forgets it, unless a newer open of {@code path} has taken over (that one keeps its ticket). */
    synchronized void finish(String path, long ticket) {
        Long n = newest.get(path);
        if (n != null && n.longValue() == ticket) newest.remove(path);
    }

    /** Whatever is opening {@code path} is not wanted any more (Release, the file changed, the window went). */
    synchronized void remove(String path) { newest.remove(path); }

    /** Nothing that is still opening may publish (Close). */
    synchronized void clear() { newest.clear(); }
}
