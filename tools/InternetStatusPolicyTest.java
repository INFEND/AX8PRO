package ru.xarkon.ax8;

public final class InternetStatusPolicyTest {
    private static int checks=0;
    private static void check(boolean condition,String caseName){
        if(!condition)throw new AssertionError(caseName);
        checks++;
    }
    private static void state(int wanted,int observed,String caseName){check(wanted==observed,caseName);}
    private static final long T=1_000_000;
    public static void main(String[] args){
        state(InternetStatusPolicy.UNKNOWN,
            InternetStatusPolicy.displayed(false,true,InternetStatusPolicy.ONLINE,T,T+1000),"stale router never online");
        state(InternetStatusPolicy.OFFLINE,
            InternetStatusPolicy.displayed(true,false,InternetStatusPolicy.ONLINE,T,T+1000),"PPP disconnected cannot be online");
        state(InternetStatusPolicy.ONLINE,
            InternetStatusPolicy.displayed(true,true,InternetStatusPolicy.ONLINE,T,T+1000),"verified Internet online");
        state(InternetStatusPolicy.OFFLINE,
            InternetStatusPolicy.displayed(true,true,InternetStatusPolicy.OFFLINE,T,T+1000),"failed external probe offline");
        state(InternetStatusPolicy.UNAVAILABLE,
            InternetStatusPolicy.displayed(true,true,InternetStatusPolicy.UNAVAILABLE,T,T+1000),"no router-bound network is unverified");
        state(InternetStatusPolicy.UNKNOWN,
            InternetStatusPolicy.displayed(true,true,InternetStatusPolicy.ONLINE,T,T+30001),"expired online must not persist");
        state(InternetStatusPolicy.ONLINE,
            InternetStatusPolicy.displayed(true,true,InternetStatusPolicy.ONLINE,T,T+30000),"freshness boundary");
        state(InternetStatusPolicy.UNKNOWN,
            InternetStatusPolicy.displayed(true,true,InternetStatusPolicy.ONLINE,T,T-1),"monotonic rollback");
        state(InternetStatusPolicy.UNKNOWN,
            InternetStatusPolicy.displayed(true,true,InternetStatusPolicy.ONLINE,0,T),"never tested unknown");
        check(InternetStatusPolicy.pppChanged("ppp_connected","ppp_disconnected"),"disconnect invalidates WAN result");
        check(InternetStatusPolicy.pppChanged("ppp_disconnected","ppp_connected"),"reconnect invalidates WAN result");
        check(!InternetStatusPolicy.pppChanged("ppp_connected","ppp_connected"),"stable LTE retains fresh probe");
        check(!InternetStatusPolicy.pppChanged(null,"ppp_connected"),"no previous sample is not a transition");
        check(!InternetStatusPolicy.pppChanged("ppp_connected",null),"missing new sample is not a transition");
        check(InternetStatusPolicy.shouldProbe(0,T,false),"immediate first check");
        check(!InternetStatusPolicy.shouldProbe(T,T+100,false),"no rapid repeated check");
        check(InternetStatusPolicy.shouldProbe(T,T+15000,false),"fixed interval boundary");
        check(!InternetStatusPolicy.shouldProbe(T,T+20000,true),"never overlap requests");
        check(InternetStatusPolicy.shouldProbe(T,T-1,false),"clock mismatch should recover");
        check(InternetStatusPolicy.label(InternetStatusPolicy.ONLINE).contains("ONLINE"),"online label");
        check(InternetStatusPolicy.label(InternetStatusPolicy.OFFLINE).contains("OFFLINE"),"offline label");
        check(InternetStatusPolicy.label(InternetStatusPolicy.UNAVAILABLE).contains("НЕ ПРОВЕРЕНО"),"unverified label");
        check(!InternetStatusPolicy.detail(InternetStatusPolicy.UNAVAILABLE).contains("подтверждён"),"no false confirmation");
        System.out.println("Internet status policy: "+checks+" checks passed.");
    }
}
