package ru.xarkon.ax8;

/** Avoids unnecessary Handler wakeups while the UI handles monitoring. */
public final class BackgroundTickPolicy {
    private static final int VISIBLE_IDLE_MS = 5000;
    private BackgroundTickPolicy(){}

    /** A delayed cleanup must not announce a visible UI after it was hidden again. */
    public static boolean showForegroundState(boolean stopping, boolean uiVisible){
        return !stopping && uiVisible;
    }

    /** -1 means no Handler tick should be scheduled (a busy worker owns the next cycle). */
    public static int nextDelay(boolean stopping, boolean workerBusy, boolean uiVisible, int requestedMs){
        if(stopping || workerBusy)return -1;
        // MainActivity signals ACTION_BACKGROUND_NOW on pause; a bounded fallback
        // still detects backgrounding if that immediate start cannot be delivered.
        if(uiVisible)return VISIBLE_IDLE_MS;
        return AutoPollController.clamp(requestedMs);
    }
}
