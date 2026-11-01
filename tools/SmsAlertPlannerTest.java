import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import ru.xarkon.ax8.SmsAlertPlanner;

public final class SmsAlertPlannerTest {
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    private static List<String> plan(List<String> unread,String prior,boolean initialized){
        return SmsAlertPlanner.newUnreadIds(unread,prior,initialized);
    }
    private static String merge(String prior,List<String> current){
        return SmsAlertPlanner.mergeSeenIds(prior,current);
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
        check(plan(Arrays.asList("?","abc","-1","8"),"3",true)
            .equals(Collections.singletonList("8")),"malformed IDs must not notify");
        check(merge("1,2",Arrays.asList("3","2")).equals("1,3,2"),
            "recent observations must be retained and refreshed");
        check(merge("1,2",Collections.emptyList()).equals("1,2"),
            "valid empty page must not erase the history");
        check(merge("1,2",null).equals("1,2"),"missing page must not erase the history");
        check(merge("1,2",Arrays.asList("2","2","3")).equals("1,2,3"),
            "duplicate IDs must be stored once");
        check(merge("1,?,-2",Arrays.asList("4",null,"bad")).equals("1,4"),
            "invalid IDs must not enter the history");
        String afterEmpty=merge(merge("20,21",Collections.emptyList()),Arrays.asList("21","20"));
        check(plan(Arrays.asList("20","21"),afterEmpty,true).isEmpty(),
            "returning SMS after an empty page must not notify twice");
        List<String> large=new ArrayList<>();
        for(int i=0;i<1100;i++)large.add(Integer.toString(i));
        String capped=merge("",large);
        String[] ids=capped.split(",");
        check(ids.length==1000 && ids[0].equals("100") && ids[999].equals("1099"),
            "history must be bounded to the newest 1000 observed IDs");
        System.out.println("SMS alert planner: 16 cases PASSED");
    }
}
