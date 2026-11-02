package ru.xarkon.ax8;

/** Rejects stale polling results and preserves a forced request across an in-flight cycle. */
final class RefreshSession {
    private long generation;
    private boolean deferredForced;

    long capture() { return generation; }

    void invalidate() {
        generation++;
        // A request from a previous foreground session must not leak forward.
        deferredForced = false;
    }

    boolean accepts(long captured, boolean active) {
        return active && captured == generation;
    }

    /** Coalesce any number of forced requests while the single worker is occupied. */
    void deferForced() { deferredForced = true; }

    /** Consume exactly once, only while the foreground UI is active. */
    boolean takeDeferredForced(boolean active) {
        boolean requested = active && deferredForced;
        deferredForced = false;
        return requested;
    }
}
