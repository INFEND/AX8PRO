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
        return new Lease();
    }
    static final class Lease implements AutoCloseable {
        private boolean closed;
        @Override public void close(){
            if(closed)return;
            closed=true;
            LOCK.unlock();
        }
    }
}
