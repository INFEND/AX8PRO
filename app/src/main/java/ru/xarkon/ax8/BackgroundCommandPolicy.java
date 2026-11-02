package ru.xarkon.ax8;

/** Determines how a service start should behave without depending on Android. */
public final class BackgroundCommandPolicy {
    public enum Decision { STOP, TEST_ONLY, MONITOR }
    private BackgroundCommandPolicy() {}

    public static Decision decide(boolean monitorEnabled, boolean stopRequested, boolean testRequested) {
        if (stopRequested) return Decision.STOP;
        if (!monitorEnabled) return testRequested ? Decision.TEST_ONLY : Decision.STOP;
        return Decision.MONITOR;
    }
}
