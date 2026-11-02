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
        eq(100,BatteryLevelPolicy.percent(130,"130"),"clamp high");
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
        System.out.println("Battery level policy: "+assertions+" assertions passed.");
    }
}
