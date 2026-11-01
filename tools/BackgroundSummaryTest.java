import java.util.Arrays;
import java.util.Collections;
import ru.xarkon.ax8.BackgroundSummary;

public final class BackgroundSummaryTest {
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args){
        check("?".equals(BackgroundSummary.clientCount(null)),"failed client poll must show unknown");
        check("0".equals(BackgroundSummary.clientCount(Collections.emptyList())),"successful empty poll must show zero");
        check("2".equals(BackgroundSummary.clientCount(Arrays.asList("A","B"))),"successful poll must show actual count");
        System.out.println("Background summary: 3 cases PASSED");
    }
}
