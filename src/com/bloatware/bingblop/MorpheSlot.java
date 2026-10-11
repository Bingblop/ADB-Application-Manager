package com.bloatware.bingblop;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The one patch run the Morphe tab allows at a time. Taking the slot is a single atomic step: two {@code patch} calls that arrive together
 * (a double tap, a script in the page) cannot both be accepted, which a read of the name followed by a write of it let through.
 */
public final class MorpheSlot {
    private final AtomicReference<String> job = new AtomicReference<String>("");

    /** True when {@code id} now holds the slot; false when another run holds it (or the id is empty). */
    public boolean tryStart(String id) {
        if (id == null || id.isEmpty()) return false;
        return job.compareAndSet("", id);
    }

    /** Frees the slot, but only when {@code id} is the run that holds it, so a late finish of an old run cannot free a newer one. */
    public boolean finish(String id) {
        return id != null && !id.isEmpty() && job.compareAndSet(id, "");
    }

    /** The id of the run that holds the slot, or "" when it is free. */
    public String current() { return job.get(); }

    public boolean busy() { return !job.get().isEmpty(); }
}
