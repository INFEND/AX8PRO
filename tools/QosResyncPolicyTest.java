package ru.xarkon.ax8;

public final class QosResyncPolicyTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long second = 1_000_000_000L;
        QosResyncPolicy policy = new QosResyncPolicy();
        check(!policy.shouldResync(null, 0), "null signature must not resync");
        check(!policy.shouldResync("", 0), "empty signature must not resync");
        check(policy.shouldResync("A", 0), "first attempt is immediate at zero uptime");
        policy.onAttempt(0);
        check(!policy.shouldResync("A", 0), "same instant cannot retry");
        check(!policy.shouldResync("A", 30 * second - 1), "before 30 seconds");
        check(policy.shouldResync("A", 30 * second), "retry at 30 seconds after failure");
        policy.onAttempt(30 * second);
        policy.onSuccess("A");
        check(!policy.shouldResync("A", 100 * second), "applied signature never retried");
        check(!policy.shouldResync("B", 60 * second - 1), "new signature respects cooldown");
        check(policy.shouldResync("B", 60 * second), "changed signature retries after cooldown");
        policy.onAttempt(60 * second);
        check(!policy.shouldResync("B", 61 * second), "failure still rate limited");
        check(policy.shouldResync("B", 90 * second), "failed apply remains eligible after cooldown");
        policy.onAttempt(90 * second);
        policy.onSuccess("B");
        check(!policy.shouldResync("B", 120 * second), "successful apply is remembered");
        check(policy.shouldResync("C", 120 * second), "new address can be applied after cooldown");

        QosResyncPolicy wrapped = new QosResyncPolicy();
        long start = Long.MAX_VALUE - 5 * second;
        check(wrapped.shouldResync("A", start), "first attempt near signed wrap");
        wrapped.onAttempt(start);
        check(!wrapped.shouldResync("A", start + 30 * second - 1), "wrap before threshold");
        check(wrapped.shouldResync("A", start + 30 * second), "wrap at threshold");
        System.out.println("QosResyncPolicyTest: " + checks + " checks passed");
    }
}
