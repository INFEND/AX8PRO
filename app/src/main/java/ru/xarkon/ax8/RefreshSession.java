package ru.xarkon.ax8;

/** Rejects stale polling results and preserves a forced request across an in-flight cycle. */
final class RefreshSession {
    // All state is guarded by this instance: worker and lifecycle callbacks may race.
    private long generation;
    private boolean deferredForced;

    synchronized long capture() { return generation; }

    synchronized void invalidate() {
        generation++;
        // A request from a previous foreground session must not leak forward.
        deferredForced = false;
    }

    synchronized boolean accepts(long captured, boolean active) {
        return active && captured == generation;
    }

    /** Coalesce any number of forced requests while the single worker is occupied. */
    synchronized void deferForced() { deferredForced = true; }

    /** Consume exactly once, only while the foreground UI is active. */
    synchronized boolean takeDeferredForced(boolean active) {
        boolean requested = active && deferredForced;
        deferredForced = false;
        return requested;
    }
}
