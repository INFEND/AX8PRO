package ru.xarkon.ax8;

/** Monotonic cooldown for failed router probes. Independent of Android wall-clock changes. */
public final class RetryBackoff {
    private final long delayNanos;
    private long failedAtNanos;
    private boolean pending;

    public RetryBackoff(long delayMillis) {
        if (delayMillis <= 0 || delayMillis > Long.MAX_VALUE / 1_000_000L)
            throw new IllegalArgumentException("Invalid retry delay");
        delayNanos = delayMillis * 1_000_000L;
    }

    public boolean isReady(long nowNanos) {
        // Subtraction remains correct across nanoTime() signed-long wraparound.
        return !pending || nowNanos - failedAtNanos >= delayNanos;
    }

    public void onFailure(long nowNanos) {
        failedAtNanos = nowNanos;
        pending = true;
    }

    public void reset() {
        pending = false;
    }
}
