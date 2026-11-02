import ru.xarkon.ax8.BackgroundCommandPolicy;
import static ru.xarkon.ax8.BackgroundCommandPolicy.Decision;

public final class BackgroundCommandPolicyTest {
    private static int assertions;
    private static void check(Decision actual, Decision expected, String description) {
        assertions++;
        if (actual != expected) throw new AssertionError(description + ": " + actual);
    }
    public static void main(String[] args) {
        check(BackgroundCommandPolicy.decide(false,false,false),Decision.STOP,"disabled monitor does not start");
        check(BackgroundCommandPolicy.decide(false,false,true),Decision.TEST_ONLY,"test works without background monitor");
        check(BackgroundCommandPolicy.decide(true,false,true),Decision.MONITOR,"test preserves running monitor");
        check(BackgroundCommandPolicy.decide(true,false,false),Decision.MONITOR,"enabled monitor starts");
        check(BackgroundCommandPolicy.decide(true,true,false),Decision.STOP,"explicit stop wins");
        check(BackgroundCommandPolicy.decide(false,true,true),Decision.STOP,"stop wins over test");
        check(BackgroundCommandPolicy.decide(true,true,true),Decision.STOP,"stop wins with monitor active");
        System.out.println("Background command policy: " + assertions + " checks PASSED");
    }
}
