package ru.xarkon.ax8;

public final class BatteryLevelPolicyTest {
    private static int assertions;
    private static void eq(int expected,int actual,String name){
        if(expected!=actual)throw new AssertionError(name+": expected "+expected+", got "+actual);
        assertions++;
    }
    private static void ok(boolean condition,String name){
        if(!condition)throw new AssertionError(name);
        assertions++;
    }
    public static void main(String[] args){
        eq(100,BatteryLevelPolicy.percent(100,"100"),"full battery");
        eq(0,BatteryLevelPolicy.percent(0,"0"),"empty battery");
        eq(85,BatteryLevelPolicy.percent(.85,"0.85"),"firmware decimal fraction");
        eq(100,BatteryLevelPolicy.percent(1,"1.0"),"one as fraction");
        eq(1,BatteryLevelPolicy.percent(1,"1"),"integer one is not fraction");
        eq(-1,BatteryLevelPolicy.percent(130,"130"),"reject out-of-range reading");
        eq(-1,BatteryLevelPolicy.percent(-1,"-1"),"reject negative");
        eq(-1,BatteryLevelPolicy.percent(Double.NaN,"NaN"),"reject NaN");
        eq(-1,BatteryLevelPolicy.percent(Double.POSITIVE_INFINITY,"inf"),"reject infinity");
        eq(100,BatteryLevelPolicy.coarseLevel(4),"four icon bars are full, not four percent");
        eq(75,BatteryLevelPolicy.coarseLevel(3),"three icon bars");
        eq(0,BatteryLevelPolicy.coarseLevel(0),"empty coarse gauge");
        eq(-1,BatteryLevelPolicy.coarseLevel(7),"reject invalid gauge");
        eq(-1,BatteryLevelPolicy.coarseLevel(2.5),"reject fractional gauge");
        int red=BatteryLevelPolicy.color(0,false),green=BatteryLevelPolicy.color(100,false);
        ok(((red>>16)&255)>((red>>8)&255),"red at empty");
        ok(((green>>8)&255)>((green>>16)&255),"green at full");
        ok(BatteryLevelPolicy.color(50,false)!=BatteryLevelPolicy.color(90,false),"dynamic levels");
        ok(BatteryLevelPolicy.color(100,true)!=BatteryLevelPolicy.color(100,false),"light contrast");
        eq(0xFF617180,BatteryLevelPolicy.color(-1,true),"unknown light");
        eq(0xFF9BA9B8,BatteryLevelPolicy.color(-1,false),"unknown dark");
        eq(50,BatteryLevelPolicy.firstValid(50,"50",99,"99"),"prefer confirmed percent");
        eq(75,BatteryLevelPolicy.firstValid(Double.NaN,"—",75,"75"),"legacy fallback");
        eq(85,BatteryLevelPolicy.firstValid(130,"130",85,"85"),"invalid primary fallback");
        eq(-1,BatteryLevelPolicy.firstValid(130,"130",-1,"-1"),"no false full battery");
        eq(90,BatteryLevelPolicy.firstValid(.9,"0.9",85,"85"),"preferred fractional");
        eq(0,BatteryLevelPolicy.firstValid(0,"0",99,"99"),"valid empty battery");
        ok(BatteryLevelPolicy.isCharging("1"),"router charging=1 shows lightning");
        ok(BatteryLevelPolicy.isCharging("true"),"boolean charging true");
        ok(BatteryLevelPolicy.isCharging(" CHARGING "),"charging string case-insensitive");
        ok(BatteryLevelPolicy.isCharging("yes"),"charging yes");
        ok(BatteryLevelPolicy.isCharging("on"),"charging on");
        ok(!BatteryLevelPolicy.isCharging("0"),"not charging hides lightning");
        ok(!BatteryLevelPolicy.isCharging("false"),"explicit false hides lightning");
        ok(!BatteryLevelPolicy.isCharging("full"),"100 percent/full is not charging evidence");
        ok(!BatteryLevelPolicy.isCharging("100"),"100% is never interpreted as charging");
        ok(!BatteryLevelPolicy.isCharging("—"),"unknown charger state hides lightning");
        ok(!BatteryLevelPolicy.isCharging(null),"missing charger state hides lightning");
        eq(1,BatteryLevelPolicy.chargingState("1"),"explicit charging from AX8");
        eq(1,BatteryLevelPolicy.chargingState("CHARGING"),"text charging from AX8");
        eq(0,BatteryLevelPolicy.chargingState("0"),"explicit not charging");
        eq(0,BatteryLevelPolicy.chargingState("false"),"explicit false charging");
        eq(-1,BatteryLevelPolicy.chargingState(""),"missing response stays unknown");
        eq(-1,BatteryLevelPolicy.chargingState("100"),"battery percent cannot be charge state");
        eq(-1,BatteryLevelPolicy.chargingState(null),"missing field stays unknown");
        System.out.println("Battery level policy: "+assertions+" assertions passed.");
    }
}
