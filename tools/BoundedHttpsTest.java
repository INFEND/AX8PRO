package ru.xarkon.ax8;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BoundedHttpsTest {
    public static void main(String[] args) throws Exception {
        if (!"ok".equals(BoundedHttps.resolve(() -> "ok", 1000))) throw new AssertionError("success");
        if (BoundedHttps.resolve(() -> { throw new Exception(); }, 1000) != null) throw new AssertionError("failure");
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        Runnable stuck = () -> BoundedHttps.resolve(() -> {
            entered.countDown();
            while (true) {
                try { release.await(); break; } catch (InterruptedException ignored) {}
            }
            return "late";
        }, 1000);
        Thread a = new Thread(stuck), b = new Thread(stuck);
        a.start(); b.start();
        try {
            if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("workers");
            if (BoundedHttps.resolve(() -> "unexpected", 1000) != null) throw new AssertionError("saturation");
            a.join(3000); b.join(3000);
            if (a.isAlive() || b.isAlive()) throw new AssertionError("timeout");
        } finally { release.countDown(); }
        // Wait for the original saturated workers to exit before testing cancellation.
        a.join(); b.join();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!"ready".equals(BoundedHttps.resolve(() -> "ready", 100)) && System.nanoTime() < until)
            Thread.sleep(10);
        BoundedHttps.Cancellation cancellation = new BoundedHttps.Cancellation();
        CountDownLatch closed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        Object result = BoundedHttps.resolve(() -> {
            if (!cancellation.register(() -> { closes.incrementAndGet(); closed.countDown(); }))
                throw new AssertionError("registration");
            try {
                while (true) {
                    try { closed.await(); break; } catch (InterruptedException ignored) {}
                }
                return "late";
            } finally { cancellation.finish(); }
        }, 500, cancellation);
        if (result != null || !closed.await(2, TimeUnit.SECONDS) || closes.get() != 1)
            throw new AssertionError("asynchronous cancellation");
        BoundedHttps.Cancellation late = new BoundedHttps.Cancellation();
        late.cancel();
        if (late.register(() -> {})) throw new AssertionError("late registration");
        Thread.currentThread().interrupt();
        if (BoundedHttps.resolve(() -> "unexpected", 1000) != null || !Thread.interrupted())
            throw new AssertionError("interrupt");
    }
}
