import ru.xarkon.ax8.LteDiagnosticsPolicy;
import java.util.Arrays;

public final class LteDiagnosticsPolicyTest {
    private static void contains(String value,String fragment) {
        if(!value.contains(fragment))
            throw new AssertionError("Expected ["+fragment+"] in: "+value);
    }
    private static void check(boolean condition,String label){
        if(!condition)throw new AssertionError(label);
    }
    public static void main(String[] args){
        contains(LteDiagnosticsPolicy.rsrp("30"),"-111");
        contains(LteDiagnosticsPolicy.rsrp("30"),"не подтверждена");
        contains(LteDiagnosticsPolicy.rsrp("-111"),"dBm");
        contains(LteDiagnosticsPolicy.rsrp("97"),"−44");
        contains(LteDiagnosticsPolicy.rsrp("0"),"−140");
        contains(LteDiagnosticsPolicy.rsrp("999"),"не интерпретировано");
        contains(LteDiagnosticsPolicy.rsrq("21"),"-9,5");
        contains(LteDiagnosticsPolicy.rsrq("21"),"-9");
        contains(LteDiagnosticsPolicy.rsrq("-8.5"),"-8,5");
        contains(LteDiagnosticsPolicy.bandwidth(null,"2"),"10 МГц");
        contains(LteDiagnosticsPolicy.bandwidth(null,"2"),"проверить");
        contains(LteDiagnosticsPolicy.provider("99960",""),"MCC 999");
        check("MCS".equals(LteDiagnosticsPolicy.provider("MCS","")),"operator text");
        contains(LteDiagnosticsPolicy.cellIdentity("100401","11","25702667"),"совпадает");
        contains(LteDiagnosticsPolicy.cellIdentity("100401","12","25702667"),"НЕ совпадает");
        contains(LteDiagnosticsPolicy.bandFrequency("3","1600"),"1845");
        contains(LteDiagnosticsPolicy.bandFrequency("3","1600"),"1750");
        contains(LteDiagnosticsPolicy.consistency("30","-111","15","0","0","23"),"RSSI");
        contains(LteDiagnosticsPolicy.consistency("30","-111","15","0","0","23"),"CQI");
        contains(LteDiagnosticsPolicy.trend(Arrays.asList("30","31","29"),Arrays.asList("15","14","16")),"29…31");
        check(LteDiagnosticsPolicy.number("NaN")==null,"NaN rejected");
        check(LteDiagnosticsPolicy.number("bad")==null,"bad numeric rejected");
        System.out.println("LteDiagnosticsPolicyTest OK");
    }
}
