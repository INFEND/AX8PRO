import ru.xarkon.ax8.BackgroundTickPolicy;

public final class BackgroundTickPolicyTest {
    private static int assertions;
    private static void check(int actual,int expected,String description){
        assertions++;
        if(actual!=expected)throw new AssertionError(description+": expected "+expected+", got "+actual);
    }
    public static void main(String[] args){
        check(BackgroundTickPolicy.nextDelay(false,true,500),-1,"busy worker owns next tick");
        check(BackgroundTickPolicy.nextDelay(false,true,5000),-1,"busy worker at slow cadence");
        check(BackgroundTickPolicy.nextDelay(true,false,500),-1,"stopped service never reschedules");
        check(BackgroundTickPolicy.nextDelay(true,true,500),-1,"stop wins over busy");
        check(BackgroundTickPolicy.nextDelay(false,false,500),500,"manual fast cadence");
        check(BackgroundTickPolicy.nextDelay(false,false,5000),5000,"manual slow cadence");
        check(BackgroundTickPolicy.nextDelay(false,false,2500),2500,"intermediate cadence");
        check(BackgroundTickPolicy.nextDelay(false,false,1),500,"clamp lower bound");
        check(BackgroundTickPolicy.nextDelay(false,false,9999),5000,"clamp upper bound");
        check(BackgroundTickPolicy.nextDelay(false,false,Integer.MAX_VALUE),5000,"avoid overflow");
        System.out.println("Background tick policy: "+assertions+" checks PASSED");
    }
}
