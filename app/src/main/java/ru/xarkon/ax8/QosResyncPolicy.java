package ru.xarkon.ax8;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Monotonic rate limiter for MAC/IP QoS resynchronization attempts. */
final class QosResyncPolicy {
    static final long RETRY_INTERVAL_NS = TimeUnit.SECONDS.toNanos(30);
    static final long MISSING_CLIENT_CONFIRM_NS = TimeUnit.SECONDS.toNanos(5);
    private String lastAppliedSignature = "";
    private String pendingReducedSignature = "";
    private long reducedSinceNs;
    private boolean attempted;
    private long lastAttemptNs;

    boolean shouldResync(String signature, long nowNs) {
        if (signature == null || signature.isEmpty() || signature.equals(lastAppliedSignature)) {
            pendingReducedSignature = "";
            return false;
        }
        // A partially returned station list must not immediately remove QoS
        // filters for clients present in the last successfully applied snapshot.
        if (losesAppliedClient(signature)) {
            if (!signature.equals(pendingReducedSignature)) {
                pendingReducedSignature = signature;
                reducedSinceNs = nowNs;
                return false;
            }
            if (Long.compareUnsigned(nowNs - reducedSinceNs, MISSING_CLIENT_CONFIRM_NS) < 0) return false;
        } else {
            pendingReducedSignature = "";
        }
        return !attempted || Long.compareUnsigned(nowNs - lastAttemptNs, RETRY_INTERVAL_NS) >= 0;
    }

    private boolean losesAppliedClient(String signature) {
        Set<String> previous = macs(lastAppliedSignature);
        return !previous.isEmpty() && !macs(signature).containsAll(previous);
    }

    private static Set<String> macs(String signature) {
        Set<String> out = new HashSet<>();
        for (String entry : signature.split(";")) {
            int equals = entry.indexOf('=');
            if (equals > 0) out.add(entry.substring(0, equals));
        }
        return out;
    }

    /** A failed or uncertain router transaction must never leave a trusted applied snapshot. */
    void invalidate() {
        lastAppliedSignature = "";
        pendingReducedSignature = "";
        attempted = false;
    }

    void onAttempt(long nowNs) {
        attempted = true;
        lastAttemptNs = nowNs;
    }

    void onSuccess(String signature) {
        lastAppliedSignature = signature;
        pendingReducedSignature = "";
    }
}
