package ru.xarkon.ax8;

import java.util.concurrent.*;

/** Bound caller wait and worker count even when a HTTPS stack ignores interruption. */
final class BoundedHttps {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(
        0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "ax8-https");
            thread.setDaemon(true);
            return thread;
        });
    private static final ThreadPoolExecutor CLOSERS = new ThreadPoolExecutor(
        0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "ax8-https-close");
            thread.setDaemon(true);
            return thread;
        });
    private BoundedHttps() {}

    // A cancellation may race with connection creation or normal completion.
    // Only one thread claims the cleanup; never disconnect on the waiting caller.
    static final class Cancellation {
        private Runnable cleanup;
        private boolean cancelled;
        synchronized boolean register(Runnable action) {
            if (cancelled) return false;
            cleanup = action;
            return true;
        }
        void cancel() {
            synchronized (this) {
                cancelled = true;
                if (cleanup == null) return;
                try {
                    CLOSERS.execute(cleanup);
                    cleanup = null;
                } catch (RejectedExecutionException busy) {
                    // The worker retains ownership and closes in its finally block.
                }
            }
        }
        void finish() {
            Runnable action;
            synchronized (this) {
                action = cleanup;
                cleanup = null;
            }
            if (action != null) action.run();
        }
    }
    static <T> T resolve(Callable<T> query, long timeoutMs) {
        return resolve(query, timeoutMs, new Cancellation());
    }
    static <T> T resolve(Callable<T> query, long timeoutMs, Cancellation cancellation) {
        if (timeoutMs <= 0 || Thread.currentThread().isInterrupted()) return null;
        Future<T> future;
        try { future = WORKERS.submit(query); }
        catch (RejectedExecutionException busy) { return null; }
        try { return future.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException failed) {
            return null;
        } finally { if (!future.isDone()) { future.cancel(true); cancellation.cancel(); } }
    }
}
