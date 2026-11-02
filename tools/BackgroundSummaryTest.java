import java.util.Arrays;
import java.util.Collections;
import ru.xarkon.ax8.BackgroundSummary;

public final class BackgroundSummaryTest {
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args){
        check("?".equals(BackgroundSummary.clientCount(null)),"failed client poll must show unknown");
        check("0".equals(BackgroundSummary.clientCount(Collections.emptyList())),"successful empty poll must show zero");
        check("2".equals(BackgroundSummary.clientCount(Arrays.asList("A","B"))),"successful poll must show actual count");
        check(BackgroundSummary.isFresh(1000L,1100L,100L),"boundary is fresh");
        check(!BackgroundSummary.isFresh(1000L,1101L,100L),"expired cache");
        check(!BackgroundSummary.isFresh(1200L,1100L,100L),"future cache");
        check(!BackgroundSummary.isFresh(0L,1100L,100L),"missing cache");
        check(!BackgroundSummary.isFresh(1000L,1100L,0L),"invalid window");
        check(BackgroundSummary.isFresh(100000L,115000L,15000L),"startup cache boundary");
        check(!BackgroundSummary.isFresh(100000L,115001L,15000L),"startup cache expires");
        check(!BackgroundSummary.isFresh(116000L,115000L,15000L),"future-dated startup cache must be rejected");
        System.out.println("Background summary: 11 cases PASSED");
    }
}
