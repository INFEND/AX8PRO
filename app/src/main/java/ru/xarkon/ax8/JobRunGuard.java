package ru.xarkon.ax8;

/** Prevents stopped or replaced JobService runs from publishing stale results. */
public final class JobRunGuard {
    private long generation;

    public synchronized long begin() { return ++generation; }
    public synchronized void cancel() { generation++; }
    public synchronized boolean isCurrent(long run) { return run > 0 && run == generation; }

    /** The callback and cancellation are mutually exclusive. Keep callbacks brief. */
    public synchronized boolean runIfCurrent(long run, Runnable callback) {
        if (!isCurrent(run)) return false;
        callback.run();
        return true;
    }
}
