package ru.xarkon.ax8;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes router-side tc/iptables transactions across the Activity and
 * background Service. This is process-local; it does not lock other apps.
 */
final class RouterOperationGate {
    private static final ReentrantLock LOCK=new ReentrantLock(true);
    private RouterOperationGate(){}
    static Lease enter(){
        LOCK.lock();
        return new Lease(Thread.currentThread());
    }
    static final class Lease implements AutoCloseable {
        private final Thread owner;
        private boolean closed;
        private Lease(Thread owner){this.owner=owner;}
        @Override public void close(){
            // Never mark a lease closed before validating ownership. Otherwise a
            // mistaken cross-thread close permanently strands the lock.
            if(Thread.currentThread()!=owner)
                throw new IllegalMonitorStateException("Router operation lease belongs to another thread");
            if(closed)return;
            LOCK.unlock();
            closed=true;
        }
    }
}
