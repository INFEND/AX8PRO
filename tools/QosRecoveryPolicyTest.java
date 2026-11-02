package ru.xarkon.ax8;

public final class QosRecoveryPolicyTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        QosRecoveryPolicy policy = new QosRecoveryPolicy();
        long second = 1_000_000_000L;
        check(!policy.isPersistent("AA", 0), "initial sample must not be persistent");
        check(!policy.isPersistent("AA", 4 * second - 1), "before 4 seconds");
        check(policy.isPersistent("AA", 4 * second), "at 4 seconds");
        check(policy.beginRepair(4 * second), "first repair allowed immediately after persistence");
        check(!policy.beginRepair(4 * second), "same cycle cannot repair twice");
        check(!policy.beginRepair(94 * second - 1), "before 90-second cooldown");
        check(policy.beginRepair(94 * second), "after 90-second cooldown");
        policy.clear("AA");
        check(!policy.isPersistent("AA", 95 * second), "clearing resets persistence");
        check(!policy.isPersistent("BB", 96 * second), "second MAC starts independently");
        check(policy.isPersistent("AA", 99 * second), "first MAC persists independently");
        policy.retainOnly(java.util.Collections.singleton("BB"));
        check(!policy.isPersistent("AA", 100 * second), "removed MAC state is discarded");
        QosRecoveryPolicy wrap = new QosRecoveryPolicy();
        long start = Long.MAX_VALUE - 2 * second;
        check(!wrap.isPersistent("CC", start), "wrap initial");
        check(wrap.isPersistent("CC", start + 4 * second), "wrap persistence");
        check(wrap.beginRepair(start + 4 * second), "wrap first repair");
        check(!wrap.beginRepair(start + 94 * second - 1), "wrap cooldown before limit");
        check(wrap.beginRepair(start + 94 * second), "wrap cooldown at limit");
        System.out.println("QosRecoveryPolicyTest: " + checks + " checks passed");
    }
}
