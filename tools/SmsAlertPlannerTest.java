import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import ru.xarkon.ax8.SmsAlertPlanner;

public final class SmsAlertPlannerTest {
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    private static List<String> plan(List<String> unread,String prior,boolean initialized){
        return SmsAlertPlanner.newUnreadIds(unread,prior,initialized);
    }
    public static void main(String[] args){
        check(plan(Arrays.asList("3","2","1"),"",false).isEmpty(),"first poll must be silent");
        check(plan(Arrays.asList("3","2"),"3,2,1",true).isEmpty(),"previously seen SMS must stay silent");
        check(plan(Arrays.asList("5","4","3"),"3,2,1",true)
            .equals(Arrays.asList("5","4")),"all new SMS in a batch must alert");
        check(plan(Arrays.asList("5","5","4"),"3",true)
            .equals(Arrays.asList("5","4")),"duplicate message IDs must alert once");
        check(plan(Arrays.asList("",null,"6"),"3",true)
            .equals(Collections.singletonList("6")),"missing IDs cannot create alerts");
        check(plan(Collections.emptyList(),"3",true).isEmpty(),"empty inbox has no alerts");
        check(plan(null,"3",true).isEmpty(),"missing data has no alerts");
        check(plan(Arrays.asList("7"),null,true)
            .equals(Collections.singletonList("7")),"empty prior history after initialization");
        System.out.println("SMS alert planner: 8 cases PASSED");
    }
}
