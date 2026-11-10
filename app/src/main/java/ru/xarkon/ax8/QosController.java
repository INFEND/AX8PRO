package ru.xarkon.ax8;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.*;
import java.net.*;
import java.io.*;

/** Port of v33 tc/iptables traffic shaping. Limits are KB/s, bound to MAC and current IPs. */
final class QosController implements AutoCloseable {
    private final SharedPreferences prefs;
    private final TelnetShell shell;
    private final RouterClient api;
    private String diagnostics="Не применялось";
    private Map<String,Integer> priorityOverrides=new HashMap<>();
    private final QosResyncPolicy resyncPolicy=new QosResyncPolicy();
    QosController(RouterClient client,SharedPreferences p){api=client;prefs=p;shell=new TelnetShell(client,p.getString("telnet_user","admin"),p.getString("telnet_pass","admin"));}
    Map<String,Integer> limits(){
        Map<String,Integer> out=new HashMap<>();
        try{JSONObject obj=new JSONObject(prefs.getString("limits","{}"));for(Iterator<String> it=obj.keys();it.hasNext();){String mac=it.next();out.put(mac,obj.getInt(mac));}}catch(Exception ignored){}
        return out;
    }
    /** Actual per-MAC cap: the stricter of saved manual QoS and temporary priority. */
    synchronized Integer effectiveLimit(String mac){return merge(limits()).get(mac);}
    void persist(Map<String,Integer> limits) {prefs.edit().putString("limits",new JSONObject(limits).toString()).apply();}
    String diagnostics(){return diagnostics;}
    private TelnetShell.Result exec(String cmd)throws Exception{try(RouterOperationGate.Lease ignored=RouterOperationGate.enter()){return shell.exec(cmd);}}
    private void checked(String cmd)throws Exception {TelnetShell.Result r=exec(cmd);if(r.code!=0)throw new IOException("Команда tc/iptables отклонена: "+r.output+" / "+cmd);}
    private ArrayList<String> interfaces()throws Exception{
        ArrayList<String> result=new ArrayList<>();
        TelnetShell.Result r=exec("for d in /sys/class/net/br0/brif/*; do [ -e \"$d\" ] || continue; n=${d##*/}; case \"$n\" in wlan*|ra*|ath*|ap*) echo \"$n\";; esac; done");
        for(String i:r.output.split("\n")){String n=i.trim();if(n.matches("[a-zA-Z0-9_.:-]+")&&!result.contains(n))result.add(n);}
        if(result.isEmpty()){
            TelnetShell.Result fallback=exec("test -e /sys/class/net/wlan0");
            if(fallback.code==0)result.add("wlan0");
        }
        if(result.isEmpty())throw new IOException("Не найдены интерфейсы Wi-Fi в br0: tc нельзя безопасно применить (возможно другой bridge/offload)");
        return result;
    }
    private static final class Active {String ip,mac;List<String> v6;int kb;Active(RouterClient.Device d,int limit){mac=d.mac;ip=d.ip;v6=d.ipv6;kb=limit;}}
    private List<Active> selected(List<RouterClient.Device> dev,Map<String,Integer> requested){
        ArrayList<Active> a=new ArrayList<>();
        for(RouterClient.Device d:dev){Integer limit=requested.get(d.mac);if(limit==null)continue;
            if(!d.type.equals("?")&&!d.type.toLowerCase(Locale.US).contains("wifi")&&!d.type.toLowerCase(Locale.US).contains("wlan"))continue;
            if((d.ip==null||!d.ip.matches("(?:\\d{1,3}\\.){3}\\d{1,3}"))&&d.ipv6.isEmpty())continue;
            a.add(new Active(d,limit));
        }return a;
    }
    synchronized void setLimit(String mac,Integer kb,List<RouterClient.Device> devices,RouterClient.Progress progress)throws Exception{
        if(kb!=null&&(kb<0||kb>125000))throw new IllegalArgumentException("Лимит за пределами диапазона");
        String normalized=RouterClient.normalizeMac(mac);if(normalized.isEmpty())throw new IOException("Некорректный MAC");
        Map<String,Integer> old=limits();Map<String,Integer> next=new HashMap<>(old);
        if(kb==null)next.remove(normalized);else next.put(normalized,kb);
        try {Map<String,Integer> applied=merge(next);apply(devices,applied,progress);persist(next);rememberApplied(devices,applied);}
        catch(Exception e){
            try{apply(devices,merge(old),m->{});}catch(Exception ignored){}
            throw e;
        }
    }
    synchronized void restore(List<RouterClient.Device> devices,RouterClient.Progress progress)throws Exception{
        Map<String,Integer> applied=merge(limits());apply(devices,applied,progress);rememberApplied(devices,applied);
    }
    private Map<String,Integer> merge(Map<String,Integer> saved){
        Map<String,Integer> joined=new HashMap<>(saved);
        for(Map.Entry<String,Integer> e:priorityOverrides.entrySet()){
            Integer existing=joined.get(e.getKey());
            joined.put(e.getKey(),existing==null?e.getValue():Math.min(existing,e.getValue()));
        }
        return joined;
    }
    synchronized void applyPriority(String ownerMac,Integer kb,List<RouterClient.Device> devices,RouterClient.Progress progress)throws Exception{
        Map<String,Integer> former=new HashMap<>(priorityOverrides),wanted=new HashMap<>();
        if(kb!=null){
            if(ownerMac==null||ownerMac.isEmpty())throw new IOException("Выберите управляющее устройство");
            for(RouterClient.Device d:devices){
                if(!d.mac.equals(ownerMac)&&d.ip!=null&&!d.ip.isEmpty()&&(d.type.isEmpty()||d.type.equalsIgnoreCase("wifi")))wanted.put(d.mac,kb);
            }
        }
        if(wanted.equals(former))return;
        priorityOverrides=wanted;
        try{Map<String,Integer> applied=merge(limits());apply(devices,applied,progress);rememberApplied(devices,applied);}
        catch(Exception e){priorityOverrides=former;try{apply(devices,merge(limits()),m->{});}catch(Exception ignored){}throw e;}
    }
    synchronized boolean priorityActive(){return !priorityOverrides.isEmpty();}

    synchronized void limitEveryoneExcept(String ownerMac,Integer kb,List<RouterClient.Device> devices,RouterClient.Progress progress)throws Exception{
        if(ownerMac==null||ownerMac.isEmpty())throw new IOException("Сначала выберите своё устройство");
        if(kb!=null&&(kb<0||kb>125000))throw new IOException("Некорректный лимит");
        Map<String,Integer> current=limits(),wanted=new HashMap<>(current);
        for(RouterClient.Device d:devices){
            if(d.mac.equals(ownerMac))continue;
            if(kb==null)wanted.remove(d.mac);else wanted.put(d.mac,kb);
        }
        try{Map<String,Integer> applied=merge(wanted);apply(devices,applied,progress);persist(wanted);rememberApplied(devices,applied);}
        catch(Exception e){try{apply(devices,merge(current),m->{});}catch(Exception ignored){}throw e;}
    }
    private void enrichIpv6Neighbors(List<RouterClient.Device> devices) {
        try {
            TelnetShell.Result neighbors=exec("ip -6 neigh show 2>/dev/null");
            java.util.regex.Pattern row=java.util.regex.Pattern.compile("(?m)^\\s*(\\S+).*?\\blladdr\\s+((?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})\\b");
            java.util.regex.Matcher match=row.matcher(neighbors.output);
            Map<String,List<String>> byMac=new HashMap<>();
            while(match.find()) {
                String mac=RouterClient.normalizeMac(match.group(2));
                String ipv6=RouterClient.cleanIp6(match.group(1));
                if(!mac.isEmpty()&&!ipv6.isEmpty())byMac.computeIfAbsent(mac,k->new ArrayList<>()).add(ipv6);
            }
            for(RouterClient.Device device:devices) for(String ipv6:byMac.getOrDefault(device.mac,Collections.emptyList())) {
                if(!device.ipv6.contains(ipv6))device.ipv6.add(ipv6);
            }
        }catch(Exception ignored) { /* station_list values still usable */ }
    }
    private void resetStock(List<String> ifaces)throws Exception{
        for(String iface:ifaces){
            TelnetShell.Result r=exec("tc qdisc show dev "+iface);
            if(r.output.contains("htb 1:"))exec("tc qdisc del dev "+iface+" root 2>/dev/null");
            if(r.output.contains("ingress ffff:"))exec("tc qdisc del dev "+iface+" ingress 2>/dev/null");
            exec("tc qdisc add dev "+iface+" root pfifo_fast 2>/dev/null");
        }
    }
    synchronized void apply(List<RouterClient.Device> devices,Map<String,Integer> requested,RouterClient.Progress progress)throws Exception{
        // Hold one process-wide lease for the complete tc/iptables transaction.
        try(RouterOperationGate.Lease ignored=RouterOperationGate.enter()){
        progress.note("Подключение к служебной оболочке AX8...");
        enrichIpv6Neighbors(devices);
        List<Active> active=selected(devices,requested);
        ArrayList<String> interfaces=interfaces();
        List<Active> limited=new ArrayList<>(),zero=new ArrayList<>();
        for(Active a:active){if(a.kb==0)zero.add(a);else limited.add(a);}
        // Verify dual stack zero-limit capability before modifying any firewall rules.
        boolean needV6=false;
        for(Active a:zero)if(!a.v6.isEmpty())needV6=true;
        boolean hasV6=exec("ip6tables -L FORWARD -n >/dev/null 2>&1").code==0;
        if(needV6&&!hasV6)throw new IOException("ip6tables недоступен. Полный IPv6 0 KB/s невозможен");
        for(String iface:interfaces){String q=exec("tc qdisc show dev "+iface).output;
            if(!limited.isEmpty()&&!q.contains("htb 1:")&&!q.contains("pfifo_fast")&&!q.trim().isEmpty())throw new IOException("На "+iface+" уже есть чужой qdisc; перезапись запрещена");
        }
        progress.note("Настройка IPv4/IPv6 firewall...");
        checked("while iptables -D FORWARD -j AX8_ZERO 2>/dev/null; do :; done; iptables -N AX8_ZERO 2>/dev/null; iptables -F AX8_ZERO");
        if(!zero.isEmpty())checked("iptables -I FORWARD 1 -j AX8_ZERO");
        for(Active a:zero)if(a.ip!=null&&a.ip.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")){
            checked("iptables -A AX8_ZERO -s "+a.ip+"/32 -j DROP");checked("iptables -A AX8_ZERO -d "+a.ip+"/32 -j DROP");
        }
        if(zero.isEmpty())exec("iptables -F AX8_ZERO 2>/dev/null; iptables -X AX8_ZERO 2>/dev/null");
        if(hasV6){
            checked("while ip6tables -D FORWARD -j AX8_ZERO6 2>/dev/null; do :; done; ip6tables -N AX8_ZERO6 2>/dev/null; ip6tables -F AX8_ZERO6");
            if(needV6)checked("ip6tables -I FORWARD 1 -j AX8_ZERO6");
            for(Active a:zero)for(String v6:a.v6){if(!v6.matches("[0-9a-fA-F:]+"))continue;
                checked("ip6tables -A AX8_ZERO6 -s "+v6+"/128 -j DROP");checked("ip6tables -A AX8_ZERO6 -d "+v6+"/128 -j DROP");
            }
            if(!needV6)exec("ip6tables -F AX8_ZERO6 2>/dev/null; ip6tables -X AX8_ZERO6 2>/dev/null");
        }
        progress.note("Настройка ограничения загрузки/отдачи...");
        for(String iface:interfaces) {
            exec("tc qdisc del dev "+iface+" ingress 2>/dev/null");exec("tc qdisc del dev "+iface+" root 2>/dev/null");
        }
        if(limited.isEmpty()){resetStock(interfaces);diagnostics="QoS: 0 положительных лимитов";return;}
        try {
            for(String iface:interfaces){
                checked("tc qdisc add dev "+iface+" root handle 1: htb default 999");
                checked("tc class add dev "+iface+" parent 1: classid 1:999 htb rate 1000mbit ceil 1000mbit burst 64k cburst 64k");
                checked("tc qdisc add dev "+iface+" handle ffff: ingress");
                int index=10;
                for(Active a:limited){
                    int kbits=Math.max(8,a.kb*8);
                    checked("tc class add dev "+iface+" parent 1: classid 1:"+index+" htb rate "+kbits+"kbit ceil "+kbits+"kbit burst 32k cburst 32k");
                    if(a.ip!=null&&a.ip.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")){
                        checked("tc filter add dev "+iface+" protocol ip parent 1: prio 1 u32 match ip dst "+a.ip+"/32 flowid 1:"+index);
                        checked("tc filter add dev "+iface+" parent ffff: protocol ip prio 1 u32 match ip src "+a.ip+"/32 police rate "+kbits+"kbit burst 32k drop flowid :1");
                    }
                    for(String ip6:a.v6) {if(!ip6.matches("[0-9a-fA-F:]+"))continue;
                        checked("tc filter add dev "+iface+" protocol ipv6 parent 1: prio 2 u32 match ip6 dst "+ip6+"/128 flowid 1:"+index);
                        checked("tc filter add dev "+iface+" parent ffff: protocol ipv6 prio 2 u32 match ip6 src "+ip6+"/128 police rate "+kbits+"kbit burst 32k drop flowid :1");
                    }
                    index++;
                }
            }
        }catch(Exception e){resetStock(interfaces);throw e;}
        diagnostics="QoS: "+active.size()+" конфигураций tc; интерфейсы: "+interfaces+"; трафик не подтверждён";
        progress.note("tc настроен. Проверьте фильтры и реальную скорость через диагностику QoS");
        }
    }
    /** Stable fingerprint for an applied client/IP/rate snapshot. */
    private static String signature(List<Active> active){
        ArrayList<String> ids=new ArrayList<>();
        for(Active a:active){
            ArrayList<String> ipv6=new ArrayList<>(a.v6);
            Collections.sort(ipv6);
            ids.add(a.mac+"="+a.ip+"/"+String.join(",",ipv6)+"/"+a.kb);
        }
        Collections.sort(ids);
        return String.join(";",ids);
    }
    /** A successful explicit QoS write already installed these rules.
     *  Avoid repeating the entire tc/iptables transaction on the next monitor tick.
     */
    private void rememberApplied(List<RouterClient.Device> devices,Map<String,Integer> requested){
        List<Active> active=selected(devices,requested);
        if(!active.isEmpty())resyncPolicy.onSuccess(signature(active));
    }
    /** Reapply saved MAC-based rules when a client gets another IP or after a new session.
     *  This prevents an old tc u32 filter remaining tied to the previous DHCP address.
     */
    synchronized void resyncAddresses(List<RouterClient.Device> devices,RouterClient.Progress progress)throws Exception{
        Map<String,Integer> saved=merge(limits());
        if(saved.isEmpty())return;
        // Do not rewrite all tc/firewall rules from a client snapshot that
        // contains no currently eligible capped device. A transient station
        // list or missing IP must not be interpreted as a QoS reset request.
        // IPv6-only clients may receive their address from the neighbor table,
        // not station_list. Resolve those before deciding that no capped client
        // is eligible; otherwise a valid IPv6 QoS rule could be silently dropped.
        boolean needsIpv6Lookup=false;
        for(RouterClient.Device d:devices)if(saved.containsKey(d.mac)){
            boolean wifiType=d.type.equals("?")||d.type.toLowerCase(Locale.US).contains("wifi")||d.type.toLowerCase(Locale.US).contains("wlan");
            if(wifiType&&(d.ip==null||!d.ip.matches("(?:\\d{1,3}\\.){3}\\d{1,3}"))&&d.ipv6.isEmpty()){
                needsIpv6Lookup=true;break;
            }
        }
        if(needsIpv6Lookup)enrichIpv6Neighbors(devices);
        List<Active> eligible=selected(devices,saved);
        if(eligible.isEmpty())return;
        String signature=signature(eligible);
        long nowNs=System.nanoTime();
        if(!resyncPolicy.shouldResync(signature,nowNs))return;
        resyncPolicy.onAttempt(nowNs);
        progress.note("QoS: проверка соответствия MAC и IP...");
        apply(devices,saved,progress);
        // apply() may enrich IPv6 neighbors; remember the rules actually
        // installed, not the earlier incomplete station-list snapshot.
        rememberApplied(devices,saved);
    }
    /** Query actual tc classes and ingress/egress filters on the router. Presence of filters
     *  is not proof of effective shaping if the firmware uses hardware/offload forwarding.
     */
    synchronized String audit(String onlyMac,List<RouterClient.Device> devices)throws Exception{
        Map<String,Integer> saved=merge(limits());
        if(onlyMac!=null&&!onlyMac.isEmpty())saved.keySet().retainAll(Collections.singleton(onlyMac));
        if(saved.isEmpty())return "Нет активного сохранённого лимита для проверки.";
        enrichIpv6Neighbors(devices);
        List<Active> matched=selected(devices,saved);
        if(matched.isEmpty())return "Сохранённые правила есть, но ни одно устройство с текущим IP не обнаружено. Ограничение нельзя подтвердить.";
        List<String> ifaces=interfaces();StringBuilder report=new StringBuilder();
        report.append("Реальные интерфейсы tc: ").append(ifaces).append("\n");
        for(String iface:ifaces){
            TelnetShell.Result q=exec("tc qdisc show dev "+iface);
            TelnetShell.Result classes=exec("tc -s class show dev "+iface);
            TelnetShell.Result egress=exec("tc -s filter show dev "+iface+" parent 1:");
            TelnetShell.Result ingress=exec("tc -s filter show dev "+iface+" parent ffff:");
            boolean htb=q.output.contains("htb 1:");
            boolean hasEgress=egress.code==0&&!egress.output.trim().isEmpty();
            boolean hasIngress=ingress.code==0&&!ingress.output.trim().isEmpty();
            report.append("\n").append(iface).append(": HTB ").append(htb?"есть":"НЕ НАЙДЕН")
                .append(", DL-фильтры ").append(hasEgress?"есть":"НЕ НАЙДЕНЫ")
                .append(", UL-фильтры ").append(hasIngress?"есть":"НЕ НАЙДЕНЫ").append("\n");
            int index=10;
            // Index order is assigned by current client list, not just requested rules.
            List<Active> actual=selected(devices,merge(limits()));
            for(Active a:actual){
                if(saved.containsKey(a.mac)){
                    boolean present=classes.code==0&&classes.output.contains("1:"+index);
                    report.append(a.mac).append(" / ").append(a.ip).append(" / ")
                        .append(a.kb).append(" KB/s: ");
                    if(a.kb==0){
                        TelnetShell.Result firewall=exec("iptables -L AX8_ZERO -n 2>/dev/null");
                        report.append(firewall.code==0&&firewall.output.contains(a.ip)?"блокировка IPv4 есть":"блокировка IPv4 НЕ ПОДТВЕРЖДЕНА");
                    }else report.append("HTB-класс ").append(present?"есть":"НЕ НАЙДЕН");
                    report.append("\n");
                }
                if(a.kb>0)index++;
            }
        }
        report.append("\nВажно: классы tc подтверждают установку правил, НЕ гарантируют ограничение реального трафика. ")
            .append("Если скорость стабильно выше лимита, возможны аппаратное ускорение, bridge/offload или неподходящий интерфейс. ")
            .append("Не отключайте ускорение без проверки совместимости AX8.");
        return report.toString();
    }
    @Override public synchronized void close(){shell.close();}
}
