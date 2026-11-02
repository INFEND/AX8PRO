package ru.xarkon.ax8;

/** Rejects results from a foreground polling cycle after the UI is paused. */
final class RefreshSession {
    private long generation;

    long capture() { return generation; }

    void invalidate() { generation++; }

    boolean accepts(long captured, boolean active) {
        return active && captured == generation;
    }
}
