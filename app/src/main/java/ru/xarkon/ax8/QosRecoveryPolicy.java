package ru.xarkon.ax8;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Monotonic overspeed persistence and rate limiting for QoS repair attempts. */
final class QosRecoveryPolicy {
    static final long PERSISTENCE_NS = TimeUnit.SECONDS.toNanos(4);
    static final long REPAIR_COOLDOWN_NS = TimeUnit.SECONDS.toNanos(90);
    private final Map<String, Long> overspeedSince = new HashMap<>();
    private boolean attempted;
    private long lastAttemptNs;

    boolean isPersistent(String mac, long nowNs) {
        Long first = overspeedSince.get(mac);
        if (first == null) {
            overspeedSince.put(mac, nowNs);
            return false;
        }
        return Long.compareUnsigned(nowNs - first, PERSISTENCE_NS) >= 0;
    }

    boolean beginRepair(long nowNs) {
        if (attempted && Long.compareUnsigned(nowNs - lastAttemptNs, REPAIR_COOLDOWN_NS) < 0) {
            return false;
        }
        attempted = true;
        lastAttemptNs = nowNs;
        return true;
    }

    void clear(String mac) { overspeedSince.remove(mac); }

    void retainOnly(Set<String> presentMacs) { overspeedSince.keySet().retainAll(presentMacs); }
}
