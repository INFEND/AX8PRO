import ru.xarkon.ax8.JobRunGuard;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class JobRunGuardTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        JobRunGuard guard = new JobRunGuard();
        AtomicInteger delivered = new AtomicInteger();
        check(!guard.isCurrent(0), "zero token is never active");
        long first = guard.begin();
        check(guard.isCurrent(first), "new job is current");
        check(guard.runIfCurrent(first, delivered::incrementAndGet), "current job can publish");
        check(delivered.get() == 1, "callback runs exactly once");
        guard.cancel();
        check(!guard.isCurrent(first), "stopped job is invalid");
        check(!guard.runIfCurrent(first, delivered::incrementAndGet), "stopped job cannot notify or finish");
        long second = guard.begin();
        check(second != first && guard.isCurrent(second), "new job supersedes old job");
        check(!guard.runIfCurrent(first, delivered::incrementAndGet), "old job cannot publish during retry");
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                ready.countDown();
                resume.await();
                guard.runIfCurrent(second, delivered::incrementAndGet);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        worker.start();
        ready.await();
        guard.cancel();
        resume.countDown();
        worker.join();
        check(delivered.get() == 1, "stopped network worker cannot publish after cancellation");
        long third = guard.begin();
        check(guard.runIfCurrent(third, delivered::incrementAndGet), "new retry can publish");
        check(delivered.get() == 2, "only active jobs publish");
        System.out.println("Job run guard: " + checks + " checks PASSED");
    }
}
