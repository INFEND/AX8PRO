package ru.xarkon.ax8;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BoundedDnsTest {
    public static void main(String[] args) throws Exception {
        if (!"ok".equals(BoundedDns.resolve(() -> "ok", 1000))) throw new AssertionError("success");
        if (BoundedDns.resolve(() -> { throw new Exception(); }, 1000) != null) throw new AssertionError("failure");
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        Runnable stuck = () -> BoundedDns.resolve(() -> {
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
            if (BoundedDns.resolve(() -> "unexpected", 1000) != null) throw new AssertionError("saturation");
            a.join(3000); b.join(3000);
            if (a.isAlive() || b.isAlive()) throw new AssertionError("timeout");
        } finally { release.countDown(); }
        Thread.currentThread().interrupt();
        if (BoundedDns.resolve(() -> "unexpected", 1000) != null || !Thread.interrupted())
            throw new AssertionError("interrupt");
    }
}
