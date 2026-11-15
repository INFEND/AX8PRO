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
    private BoundedHttps() {}
    static <T> T resolve(Callable<T> query, long timeoutMs) {
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
        } finally { future.cancel(true); }
    }
}
