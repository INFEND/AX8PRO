package ru.xarkon.ax8;

public final class RefreshSessionTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        RefreshSession session = new RefreshSession();
        long first = session.capture();
        check(session.accepts(first, true), "active cycle must be accepted");
        check(!session.accepts(first, false), "paused UI must reject pending results");
        session.invalidate();
        check(!session.accepts(first, true), "old result must remain invalid after resume");
        long resumed = session.capture();
        check(session.accepts(resumed, true), "new cycle must be accepted");
        session.invalidate();
        session.invalidate();
        check(!session.accepts(resumed, true), "multiple pause transitions must invalidate old work");
        check(session.accepts(session.capture(), true), "latest cycle must still be accepted");
        System.out.println("Refresh session: 6 checks passed.");
    }
}
