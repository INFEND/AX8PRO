package ru.xarkon.ax8;

import java.util.concurrent.TimeUnit;

/** Monotonic rate limiter for MAC/IP QoS resynchronization attempts. */
final class QosResyncPolicy {
    static final long RETRY_INTERVAL_NS = TimeUnit.SECONDS.toNanos(30);
    private String lastAppliedSignature = "";
    private boolean attempted;
    private long lastAttemptNs;

    boolean shouldResync(String signature, long nowNs) {
        if (signature == null || signature.isEmpty() || signature.equals(lastAppliedSignature)) return false;
        return !attempted || Long.compareUnsigned(nowNs - lastAttemptNs, RETRY_INTERVAL_NS) >= 0;
    }

    void onAttempt(long nowNs) {
        attempted = true;
        lastAttemptNs = nowNs;
    }

    void onSuccess(String signature) {
        lastAppliedSignature = signature;
    }
}
