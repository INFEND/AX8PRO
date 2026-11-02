package ru.xarkon.ax8;

import java.util.concurrent.atomic.AtomicInteger;

/** Process-local barrier for queued foreground QoS cleanup. */
final class ForegroundHandoff {
    private static final AtomicInteger pendingCleanups = new AtomicInteger();
    private ForegroundHandoff() {}
    static void begin(){pendingCleanups.incrementAndGet();}
    static void end(){pendingCleanups.updateAndGet(n -> n > 0 ? n - 1 : 0);}
    static boolean pending(){return pendingCleanups.get() > 0;}
}
