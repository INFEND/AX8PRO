import ru.xarkon.ax8.BackgroundTickPolicy;

public final class BackgroundTickPolicyTest {
    private static int assertions;
    private static void check(int actual,int expected,String description){
        assertions++;
        if(actual!=expected)throw new AssertionError(description+": expected "+expected+", got "+actual);
    }
    public static void main(String[] args){
        check(BackgroundTickPolicy.nextDelay(false,true,false,500),-1,"busy worker owns next tick");
        check(BackgroundTickPolicy.nextDelay(false,true,false,5000),-1,"busy worker at slow cadence");
        check(BackgroundTickPolicy.nextDelay(true,false,false,500),-1,"stopped service never reschedules");
        check(BackgroundTickPolicy.nextDelay(true,true,false,500),-1,"stop wins over busy");
        check(BackgroundTickPolicy.nextDelay(false,false,false,500),500,"manual fast cadence");
        check(BackgroundTickPolicy.nextDelay(false,false,false,5000),5000,"manual slow cadence");
        check(BackgroundTickPolicy.nextDelay(false,false,false,2500),2500,"intermediate cadence");
        check(BackgroundTickPolicy.nextDelay(false,false,false,1),500,"clamp lower bound");
        check(BackgroundTickPolicy.nextDelay(false,false,false,9999),5000,"clamp upper bound");
        check(BackgroundTickPolicy.nextDelay(false,false,false,Integer.MAX_VALUE),5000,"avoid overflow");
        check(BackgroundTickPolicy.nextDelay(false,false,true,500),5000,"visible UI reduces idle wakeups");
        check(BackgroundTickPolicy.nextDelay(false,false,true,2500),5000,"visible UI ignores manual poll rate");
        check(BackgroundTickPolicy.nextDelay(false,true,true,500),-1,"visible busy worker retains scheduling ownership");
        check(BackgroundTickPolicy.nextDelay(true,false,true,500),-1,"visible stopped service never reschedules");
        if(!BackgroundTickPolicy.showForegroundState(false,true))throw new AssertionError("visible UI may be announced");
        if(BackgroundTickPolicy.showForegroundState(false,false))throw new AssertionError("hidden UI must not be announced after delayed cleanup");
        if(BackgroundTickPolicy.showForegroundState(true,true))throw new AssertionError("stopped service must not announce UI");
        if(BackgroundTickPolicy.showForegroundState(true,false))throw new AssertionError("stopped hidden service must not announce UI");
        assertions+=4;
        System.out.println("Background tick policy: "+assertions+" checks PASSED");
    }
}
