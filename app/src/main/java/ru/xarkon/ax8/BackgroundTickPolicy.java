package ru.xarkon.ax8;

/** Prevents repeated Handler wakeups while a router request is still running. */
public final class BackgroundTickPolicy {
    private BackgroundTickPolicy(){}

    /** -1 means the worker will schedule the next tick after completion. */
    public static int nextDelay(boolean stopping, boolean workerBusy, int requestedMs){
        if(stopping || workerBusy)return -1;
        return AutoPollController.clamp(requestedMs);
    }
}
