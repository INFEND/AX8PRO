package ru.xarkon.ax8;

public final class RefreshSessionTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        RefreshSession session = new RefreshSession();
        long first = session.capture();
        check(session.accepts(first, true), "active cycle must be accepted");
        check(!session.accepts(first, false), "paused UI must reject pending results");
        session.deferForced();
        session.deferForced();
        check(session.takeDeferredForced(true), "multiple forced requests must coalesce");
        check(!session.takeDeferredForced(true), "forced request must be consumed only once");
        session.deferForced();
        check(!session.takeDeferredForced(false), "paused UI must not restart polling");
        check(!session.takeDeferredForced(true), "paused request must be cleared");
        session.deferForced();
        session.invalidate();
        check(!session.accepts(first, true), "old result must remain invalid after resume");
        check(!session.takeDeferredForced(true), "pause must clear old forced requests");
        long resumed = session.capture();
        check(session.accepts(resumed, true), "new cycle must be accepted");
        // onResume() queues a forced request if the old worker is still busy.
        session.deferForced();
        check(session.takeDeferredForced(true), "resumed UI must restart after old worker exits");
        session.invalidate();
        session.invalidate();
        check(!session.accepts(resumed, true), "multiple pauses invalidate old work");
        check(session.accepts(session.capture(), true), "latest cycle must be accepted");
        System.out.println("Refresh session: " + checks + " checks passed.");
    }
}
