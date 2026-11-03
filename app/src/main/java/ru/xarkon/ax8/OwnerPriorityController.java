package ru.xarkon.ax8;
import java.util.*;

/** Foreground-only adaptation from v33's owner-priority heuristic; no permanent manual-limit changes. */
final class OwnerPriorityController {
    private static final int[] TIERS={2000,1500,1000,750,500,300,250,200,150,100,75,50,30,20,15};
    boolean enabled=false;
    String owner="",mode="ВЫКЛ",failure="";
    Integer cap=null;
    private long lastEval=0,idleSince=0;
    private int good=0;
    double ownerRate=0,othersRate=0;
    void enable(String mac){owner=mac;enabled=true;mode="ОЖИДАНИЕ";failure="";lastEval=idleSince=0;good=0;}
    void disable(){enabled=false;mode="ВЫКЛ";cap=null;good=0;idleSince=0;}
    private int initial(double otherBps){for(int tier:TIERS)if(tier*1000<otherBps)return tier;return TIERS[TIERS.length-1];}
    private Integer next(Integer current,boolean tighter){if(current==null)return null;
        for(int i=0;i<TIERS.length;i++)if(TIERS[i]<=current){
            int next=i+(tighter?1:-1);return next<0?null:TIERS[Math.min(TIERS.length-1,next)];
        }
        return tighter?TIERS[TIERS.length-1]:null;
    }
    void update(List<RouterClient.Device> devices,DeviceTrafficMonitor traffic,QosController qos)throws Exception{
        if(!enabled)return;
        long now=System.nanoTime()/1_000_000L;if(lastEval!=0&&now-lastEval>=0&&now-lastEval<2500)return;lastEval=now;
        boolean present=false;double oDl=0,oUl=0;for(RouterClient.Device d:devices)if(d.mac.equals(owner)){
            present=true;DeviceTrafficMonitor.Rate rate=traffic.get(d.mac);if(rate!=null){oDl=rate.dl;oUl=rate.ul;}break;
        }
        if(!present){mode="ХОЗЯИН ВНЕ СЕТИ";apply(null,devices,qos);return;}
        // Only refresh the cap while the priority owner is connected.
        if(cap!=null)qos.applyPriority(owner,cap,devices,message->{});
        boolean down=oDl>=oUl;ownerRate=down?oDl:oUl;
        othersRate=0;double max=0;
        for(RouterClient.Device d:devices){if(d.mac.equals(owner))continue;
            DeviceTrafficMonitor.Rate r=traffic.get(d.mac);if(r==null)continue;
            double val=down?r.dl:r.ul;othersRate+=val;max=Math.max(max,val);
        }
        if(ownerRate<8000){
            mode="ОЖИДАНИЕ";good=0;if(idleSince==0)idleSince=now;
            if(cap!=null&&now-idleSince>=6000)apply(null,devices,qos);return;
        }
        idleSince=0;
        if(othersRate<30000){mode="СВОБОДНО";if(cap!=null&&++good>=2){good=0;apply(next(cap,false),devices,qos);}return;}
        double share=ownerRate/(ownerRate+othersRate);
        if(share<.60){mode="ЗАЩИТА";good=0;apply(cap==null?initial(max):next(cap,true),devices,qos);}
        else if(share>.82){mode="ОСЛАБЛЕНИЕ";if(cap!=null&&++good>=2){good=0;apply(next(cap,false),devices,qos);}}
        else{mode="БАЛАНС";good=0;}
    }
    private void apply(Integer wanted,List<RouterClient.Device> devices,QosController qos)throws Exception{
        if(Objects.equals(cap,wanted))return;
        qos.applyPriority(owner,wanted,devices,message->{});cap=wanted;failure="";
    }
}
