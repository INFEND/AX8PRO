package ru.xarkon.ax8;

import java.util.*;
/** Detects persistent overspeed; limits in KB/s and rates in bytes/s. */
final class QosWatchdog {
    private final QosRecoveryPolicy recovery=new QosRecoveryPolicy();
    private String status="Не проверено";
    String status(){return status;}
    void check(List<RouterClient.Device> devices,DeviceTrafficMonitor traffic,QosController qos){
        Map<String,Integer> caps=qos.limits();long now=System.nanoTime();boolean hasOver=false;
        Set<String> present=new HashSet<>();
        for(RouterClient.Device dev:devices){
            present.add(dev.mac);
            Integer cap=caps.get(dev.mac);DeviceTrafficMonitor.Rate r=traffic.get(dev.mac);
            if(cap==null||cap<=0||r==null){recovery.clear(dev.mac);continue;}
            // 30% tolerance for short packet bursts / sampling jitter.
            boolean exceeds=r.dl>(cap*1000.0*1.30)||r.ul>(cap*1000.0*1.30);
            if(!exceeds){recovery.clear(dev.mac);continue;}
            hasOver=true;
            if(!recovery.isPersistent(dev.mac,now))continue;
            status="ПРЕВЫШЕНИЕ: "+dev.mac+" · "+cap+" KB/s. Проверка tc обязательна";
            if(recovery.beginRepair(now)){
                try{qos.restore(devices,note->{});status="QoS повторно применён. Требуется проверить скорость";}
                catch(Exception e){status="QoS НЕ ПРИМЕНЁН: "+e.getMessage();}
            }
        }
        // Forget clients that vanished; their old overspeed timer must not carry over.
        recovery.retainOnly(present);
        if(!hasOver)status=caps.isEmpty()?"Лимиты не установлены":"Превышений не выявлено (по выборкам)";
    }
}
