import ru.xarkon.ax8.AutoPollController;
public final class AutoPollControllerTest {
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    public static void main(String[] a){
        check(AutoPollController.clamp(100)==500,"0.1 s must not be selectable");
        check(AutoPollController.clamp(5300)==5000,"manual cap");
        check(AutoPollController.clamp(Integer.MAX_VALUE)==5000,"extreme positive interval must clamp to max");
        check(AutoPollController.clamp(Integer.MAX_VALUE-49)==5000,"near-max persisted manual interval must not overflow");
        check(AutoPollController.clamp(3451)==3500,"manual interval rounds to the nearest 0.1 second");
        check(AutoPollController.clamp(Integer.MIN_VALUE)==500,"extreme negative interval must clamp to min");
        check(AutoPollController.clamp(4950)==5000,"rounding near upper bound");
        check(AutoPollController.next(500,Long.MAX_VALUE,true)==5000,"huge elapsed time must not wrap into fast polling");
        check(AutoPollController.next(500,120,true)==500,"fast router gets minimum");
        check(AutoPollController.next(500,1200,true)>=1500,"slow response gets safe wait");
        int p=AutoPollController.next(500,1300,true);
        check(AutoPollController.next(p,100,true)<p,"recover after slowdown");
        check(AutoPollController.next(500,100,false)>500,"backoff on failure");
        int x=500;for(int i=0;i<200;i++){
            x=AutoPollController.next(x, i%5==0?2800:120,i%11!=0);
            check(x>=500&&x<=5000&&x%100==0,"always within allowed range");
        }
        System.out.println("Auto polling: 11 groups PASSED");
    }
}
