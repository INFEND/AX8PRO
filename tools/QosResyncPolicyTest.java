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

        String both = "AA:00=192.168.1.2/10;BB:00=192.168.1.3/10";
        String one = "AA:00=192.168.1.2/10";
        QosResyncPolicy reduced = new QosResyncPolicy();
        reduced.onSuccess(both);
        check(!reduced.shouldResync(one, 100 * second), "first partial snapshot must not erase client rules");
        check(!reduced.shouldResync(one, 105 * second - 1), "partial snapshot requires five seconds of stability");
        check(reduced.shouldResync(one, 105 * second), "confirmed disconnect can reconcile");
        reduced.onAttempt(105 * second);
        reduced.onSuccess(one);
        check(!reduced.shouldResync(one, 110 * second), "reconciled partial snapshot is stable");
        check(!reduced.shouldResync(both, 110 * second), "reappearance still respects retry cooldown");
        check(reduced.shouldResync(both, 135 * second), "reappearance can restore rules");

        QosResyncPolicy transientDrop = new QosResyncPolicy();
        transientDrop.onSuccess(both);
        check(!transientDrop.shouldResync(one, 10 * second), "start drop confirmation");
        check(!transientDrop.shouldResync(both, 12 * second), "full snapshot cancels drop confirmation");
        check(!transientDrop.shouldResync(one, 13 * second), "new drop must restart confirmation");
        check(!transientDrop.shouldResync(one, 17 * second), "stability is measured from latest drop");
        check(transientDrop.shouldResync(one, 18 * second), "confirmed drop may proceed");

        QosResyncPolicy changedAddress = new QosResyncPolicy();
        changedAddress.onSuccess(both);
        check(changedAddress.shouldResync("AA:00=192.168.1.9/10;BB:00=192.168.1.3/10", 0),
            "IP changes with all clients present must not be delayed");
        check(!changedAddress.shouldResync("", 1), "empty snapshot must never clear QoS");

        QosResyncPolicy changingReduced = new QosResyncPolicy();
        changingReduced.onSuccess(both);
        check(!changingReduced.shouldResync(one, 1 * second), "start reduced candidate");
        check(!changingReduced.shouldResync("AA:00=192.168.1.4/10", 5 * second), "changed candidate restarts timer");
        check(!changingReduced.shouldResync("AA:00=192.168.1.4/10", 9 * second), "changed candidate remains pending");
        check(changingReduced.shouldResync("AA:00=192.168.1.4/10", 10 * second), "stable changed candidate accepted");

        QosResyncPolicy wrappedReduction = new QosResyncPolicy();
        wrappedReduction.onSuccess(both);
        check(!wrappedReduction.shouldResync(one, start), "start confirmation near wrap");
        check(!wrappedReduction.shouldResync(one, start + 5 * second - 1), "wrap before confirmation");
        check(wrappedReduction.shouldResync(one, start + 5 * second), "wrap at confirmation");

        System.out.println("QosResyncPolicyTest: " + checks + " checks passed");
    }
}
