package ru.xarkon.ax8;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ForegroundHandoffTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(!ForegroundHandoff.pending(), "initially idle");
        ForegroundHandoff.begin();
        check(ForegroundHandoff.pending(), "first cleanup blocks service");
        ForegroundHandoff.begin();
        ForegroundHandoff.end();
        check(ForegroundHandoff.pending(), "second cleanup still pending");
        Thread worker = new Thread(ForegroundHandoff::end);
        worker.start();
        worker.join(5000);
        check(!worker.isAlive(), "cross-thread completion must finish");
        check(!ForegroundHandoff.pending(), "cross-thread completion visible");
        ForegroundHandoff.end();
        check(!ForegroundHandoff.pending(), "extra completion cannot underflow");

        // A completed cleanup must not release the barrier while another is blocked.
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        ForegroundHandoff.begin();
        Thread heldCleanup = new Thread(() -> {
            ForegroundHandoff.begin();
            secondStarted.countDown();
            await(releaseSecond);
            ForegroundHandoff.end();
        });
        heldCleanup.start();
        try {
            check(secondStarted.await(5, TimeUnit.SECONDS), "held cleanup must begin");
            ForegroundHandoff.end();
            check(ForegroundHandoff.pending(), "finishing first cleanup must not release held cleanup");
        } finally {
            releaseSecond.countDown();
            heldCleanup.join(5000);
        }
        check(!heldCleanup.isAlive(), "held cleanup must finish");
        check(!ForegroundHandoff.pending(), "barrier must clear after held cleanup");

        // Concurrent cleanups must not expose an idle barrier until all have ended.
        for (int iteration = 0; iteration < 100; iteration++) {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch finish = new CountDownLatch(1);
            Thread first = new Thread(() -> {
                ForegroundHandoff.begin();
                started.countDown();
                await(finish);
                ForegroundHandoff.end();
            });
            Thread second = new Thread(() -> {
                ForegroundHandoff.begin();
                started.countDown();
                await(finish);
                ForegroundHandoff.end();
            });
            first.start();
            second.start();
            try {
                check(started.await(5, TimeUnit.SECONDS), "both cleanup workers must begin");
                check(ForegroundHandoff.pending(), "concurrent cleanups keep barrier active");
            } finally {
                // Always unblock and join workers, even when an assertion fails.
                finish.countDown();
                first.join(5000);
                second.join(5000);
            }
            check(!first.isAlive() && !second.isAlive(), "cleanup workers must finish");
            check(!ForegroundHandoff.pending(), "barrier must clear after both cleanups");
        }
        System.out.println("Foreground handoff: " + checks + " checks passed.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("cleanup timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
