import ru.xarkon.ax8.RetryBackoff;

public final class RetryBackoffTest {
    private static int assertions;
    private static void check(boolean ok, String reason) {
        assertions++;
        if (!ok) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        RetryBackoff retry = new RetryBackoff(15_000);
        check(retry.isReady(0), "new gate is ready");
        retry.onFailure(1_000_000_000L);
        check(!retry.isReady(1_000_000_000L), "failure starts cooldown");
        check(!retry.isReady(15_999_999_999L), "still blocked just before boundary");
        check(retry.isReady(16_000_000_000L), "ready exactly after 15 seconds");
        check(retry.isReady(18_000_000_000L), "remains ready afterward");
        retry.onFailure(20_000_000_000L);
        check(!retry.isReady(21_000_000_000L), "subsequent failure restarts cooldown");
        retry.reset();
        check(retry.isReady(21_000_000_000L), "success or stop clears cooldown");
        retry.onFailure(Long.MAX_VALUE - 2_000_000_000L);
        check(!retry.isReady(Long.MAX_VALUE - 1_000_000_000L), "near-wrap before boundary");
        check(retry.isReady((Long.MAX_VALUE - 2_000_000_000L) + 15_000_000_000L), "wrap-safe at boundary");
        RetryBackoff negative = new RetryBackoff(1000);
        negative.onFailure(-5_000_000_000L);
        check(!negative.isReady(-4_500_000_000L), "negative nanoTime supported");
        check(negative.isReady(-4_000_000_000L), "negative nanoTime reaches boundary");
        boolean rejected = false;
        try { new RetryBackoff(0); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "zero delay rejected");
        rejected = false;
        try { new RetryBackoff(Long.MAX_VALUE); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "overflow delay rejected");
        System.out.println("Retry backoff: " + assertions + " checks PASSED");
    }
}
