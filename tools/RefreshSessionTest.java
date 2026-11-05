package ru.xarkon.ax8;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RefreshSessionTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        RefreshSession session = new RefreshSession();
        long first = session.capture();
        check(session.accepts(first, true), "active cycle must be accepted");
        check(!session.accepts(first, false), "paused UI must reject pending results");
        session.deferForced();
        session.deferForced();
        check(session.takeDeferredForced(true), "multiple forced requests must coalesce");
        check(!session.takeDeferredForced(true), "forced request must be consumed only once");
        session.deferForced();
        check(!session.takeDeferredForced(false), "paused UI must not restart polling");
        check(!session.takeDeferredForced(true), "paused request must be cleared");
        session.deferForced();
        session.invalidate();
        check(!session.accepts(first, true), "old result must remain invalid after resume");
        check(!session.takeDeferredForced(true), "pause must clear old forced requests");
        long resumed = session.capture();
        check(session.accepts(resumed, true), "new cycle must be accepted");
        session.deferForced();
        check(session.takeDeferredForced(true), "resumed UI must restart after old worker exits");
        session.invalidate();
        session.invalidate();
        check(!session.accepts(resumed, true), "multiple pauses invalidate old work");
        check(session.accepts(session.capture(), true), "latest cycle must be accepted");
        final long oldGeneration = session.capture();
        session.invalidate();
        check(!session.accepts(oldGeneration, true), "worker must reject invalidated generation");

        // Two concurrent invalidations must both advance the generation, without lost increments.
        for (int iteration = 0; iteration < 100; iteration++) {
            final long before = session.capture();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Thread a = new Thread(() -> { ready.countDown(); await(go); session.invalidate(); });
            Thread b = new Thread(() -> { ready.countDown(); await(go); session.invalidate(); });
            a.start(); b.start();
            check(ready.await(5, TimeUnit.SECONDS), "invalidation workers must start");
            go.countDown();
            a.join(5000); b.join(5000);
            check(!a.isAlive() && !b.isAlive(), "invalidation workers must finish");
            check(session.capture() == before + 2, "concurrent invalidations must not lose increments");
        }
        System.out.println("Refresh session: " + checks + " checks passed.");
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
