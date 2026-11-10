package ru.xarkon.ax8;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/**
 * Per-device router counters (IPv4 and optional IPv6). The AX8 firmware is not
 * guaranteed to expose the mangle table. We probe mangle first and then filter,
 * and NEVER pretend that unavailable counters have measured a transfer rate.
 * Commands are executed on the router through its authenticated Telnet session.
 */
final class DeviceTrafficMonitor implements AutoCloseable {
    static final class Rate {
        final double dl,ul;
        Rate(double down,double up){dl=down;ul=up;}
    }
    private static final String V4_CHAIN="AX8_APP_MON",V6_CHAIN="AX8_APP_MON6";
    private static final class Backend {
        final String tool,table,chain;
        Backend(String tool,String table,String chain){this.tool=tool;this.table=table;this.chain=chain;}
        String cmd(String args){return tool+" -t "+table+" "+args;}
        String label(){return tool+"/"+table;}
    }
    private static List<Backend> candidates(boolean ipv6){
        String name=ipv6?"ip6tables":"iptables",chain=ipv6?V6_CHAIN:V4_CHAIN;
        ArrayList<Backend> backends=new ArrayList<>();
        for(String tool:new String[]{name,"busybox "+name,"/sbin/"+name}){
            backends.add(new Backend(tool,"mangle",chain));
            backends.add(new Backend(tool,"filter",chain));
        }
        return backends;
    }
    private final TelnetShell shell;
    private final Map<String,long[]> prior=new HashMap<>();
    private final Map<String,Rate> rates=new java.util.concurrent.ConcurrentHashMap<>();
    private String ips4="",ips6="",error="",note="Ожидание первого опроса";
    private Backend active4,active6;
    private boolean started=false;
    private long lastAt=0;
    // Retry an unavailable IPv6 backend without repeatedly rebuilding IPv4 chains.
    private long nextIpv6ProbeAt=0;
    private static final long IPV6_PROBE_INTERVAL_NS=30_000_000_000L;
    private final RetryBackoff retry=new RetryBackoff(15_000);

    DeviceTrafficMonitor(RouterClient router,android.content.SharedPreferences prefs){
        shell=new TelnetShell(router,prefs.getString("telnet_user","admin"),prefs.getString("telnet_pass","admin"));
    }
    String error(){return error;}
    String status(){return note;}
    Rate get(String mac){return rates.get(mac);}

    synchronized void sample(List<RouterClient.Device> devices)throws Exception {
        // Counter-chain installation/removal must not interleave with QoS rewrites.
        try(RouterOperationGate.Lease gateLease=RouterOperationGate.enter()){
        if(!started && !retry.isReady(System.nanoTime())){
            // A failed poll invalidates the time baseline as well as displayed rates.
            // Otherwise the first successful poll after backoff can use stale counters.
            prior.clear();rates.clear();lastAt=0;return;
        }
        TreeSet<String> a4=new TreeSet<>(),a6=new TreeSet<>();
        for(RouterClient.Device d:devices){
            if(validIp4(d.ip))a4.add(d.ip);
            for(String ip:d.ipv6)if(validIp6(ip))a6.add(ip);
        }
        String next4=String.join(",",a4),next6=String.join(",",a6);
        if(a4.isEmpty() && a6.isEmpty()){
            if(started)stop();
            note="Нет устройств с IP-адресами для счётчиков";
            error="";rates.clear();return;
        }
        try{
            if(!started||!ips4.equals(next4)||!ips6.equals(next6)){
                if(active4!=null)remove(active4);
                if(active6!=null)remove(active6);
                active4=null;active6=null;nextIpv6ProbeAt=0;
                if(!a4.isEmpty())active4=choose(false,a4);
                if(!a6.isEmpty()){
                    try{active6=choose(true,a6);}catch(Exception ignored){
                        active6=null;nextIpv6ProbeAt=System.nanoTime()+IPV6_PROBE_INTERVAL_NS;
                    }
                }
                if(active4==null && active6==null)throw new IOException("Нет поддерживаемого механизма учёта IPv4/IPv6");
                ips4=next4;ips6=next6;started=true;retry.reset();prior.clear();rates.clear();lastAt=0;
                note="IPv4: "+(a4.isEmpty()?"нет адресов":active4==null?"недоступен":active4.label())+
                    " · IPv6: "+(a6.isEmpty()?"нет адресов":active6==null?"недоступен":active6.label());
            }
            // The initial IPv6 probe can fail while IPv4 remains operational. Retry
            // periodically without touching IPv4 rules or using partial dual-stack rates.
            if(started && active6==null && !a6.isEmpty() &&
               System.nanoTime()-nextIpv6ProbeAt>=0){
                nextIpv6ProbeAt=System.nanoTime()+IPV6_PROBE_INTERVAL_NS;
                try{
                    active6=choose(true,a6);
                    // A newly installed chain starts with fresh counters. Discard the
                    // previous time baseline before the next complete measurement.
                    prior.clear();rates.clear();lastAt=0;
                    note="IPv6: восстановлен "+active6.label();
                }catch(Exception ignored){active6=null;}
            }
            Map<String,long[]> totals=new HashMap<>();
            readCounters(active4,totals);
            boolean ipv6ReadFailed=false;
            if(active6!=null)try{readCounters(active6,totals);}catch(Exception e){
                ipv6ReadFailed=true;
                note="IPv6: ошибка счётчика · "+shortText(e.getMessage());
            }
            long now=System.nanoTime();double secs=lastAt==0?0:(now-lastAt)/1_000_000_000.0;
            for(RouterClient.Device d:devices){
                // Missing IPv6 backend or failed IPv6 read makes dual-stack totals partial.
                // Never present IPv4-only bytes as a complete dual-stack rate.
                // Keep IPv4-only stations updating, but re-baseline dual-stack stations.
                if((ipv6ReadFailed || active6==null) && d.ipv6!=null && !d.ipv6.isEmpty()){
                    prior.remove(d.mac);rates.remove(d.mac);continue;
                }
                long dl=0,ul=0;boolean found=false;
                ArrayList<String> addresses=new ArrayList<>();addresses.add(d.ip);addresses.addAll(d.ipv6);
                for(String ip:addresses){long[] n=totals.get(ip);if(n!=null){found=true;dl+=n[0];ul+=n[1];}}
                if(!found){prior.remove(d.mac);rates.remove(d.mac);continue;}
                long[] prev=prior.put(d.mac,new long[]{dl,ul});
                if(prev!=null&&secs>.09&&dl>=prev[0]&&ul>=prev[1])
                    rates.put(d.mac,new Rate((dl-prev[0])/secs,(ul-prev[1])/secs));
                else rates.remove(d.mac);
            }
            // Drop samples for stations absent from this poll; otherwise a returning MAC
            // can briefly display a stale rate and trigger an incorrect QoS watchdog alarm.
            Set<String> currentMacs=new HashSet<>();
            for(RouterClient.Device d:devices)currentMacs.add(d.mac);
            prior.keySet().retainAll(currentMacs);
            rates.keySet().retainAll(currentMacs);
            lastAt=now;error="";
        }catch(Exception e){
            error=shortText(e.getMessage());note="Монитор недоступен: "+error;
            started=false;lastAt=0;retry.onFailure(System.nanoTime());
            prior.clear();rates.clear();throw e;
        }
        }
    }
    // Validate before embedding an address in router shell commands. A character-only
    // check is insufficient: 999.999.999.999 and "::::" are not IP addresses.
    private static boolean validIp4(String s){
        if(s==null)return false;
        String[] parts=s.split("\\.",-1);
        if(parts.length!=4)return false;
        for(String part:parts){
            if(part.isEmpty()||part.length()>3)return false;
            int value=0;
            for(int i=0;i<part.length();i++){
                char c=part.charAt(i);
                if(c<'0'||c>'9')return false;
                value=value*10+(c-'0');
            }
            if(value>255)return false;
        }
        return true;
    }
    private static boolean validIp6(String s){
        if(s==null||s.isEmpty()||s.indexOf(':')<0||
           !s.matches("[0-9a-fA-F:]+"))return false;
        try{
            return java.net.InetAddress.getByName(s) instanceof java.net.Inet6Address;
        }catch(java.net.UnknownHostException ignored){
            return false;
        }
    }
    private static String shortText(String s){
        if(s==null||s.trim().isEmpty())return "неизвестная ошибка";
        s=s.replace('\r',' ').replace('\n',' ').trim();return s.length()>250?s.substring(0,250)+"…":s;
    }
    private static String reason(TelnetShell.Result r){return "rc="+r.code+" "+shortText(r.output);}
    private TelnetShell.Result call(Backend b,String args)throws Exception{try(RouterOperationGate.Lease ignored=RouterOperationGate.enter()){return shell.exec(b.cmd(args));}}
    private void required(Backend b,String args)throws Exception{
        TelnetShell.Result result=call(b,args);
        if(result.code!=0)throw new IOException(b.label()+" "+args+" → "+reason(result));
    }
    private Backend choose(boolean ipv6,Collection<String> ips)throws Exception{
        ArrayList<String> errors=new ArrayList<>();
        for(Backend b:candidates(ipv6)){
            try{
                TelnetShell.Result availability=call(b,"-L FORWARD -n");
                if(availability.code!=0){errors.add(b.label()+" ("+reason(availability)+")");continue;}
                install(b,ips);
                return b;
            }catch(Exception e){errors.add(b.label()+" ("+shortText(e.getMessage())+")");
                try{remove(b);}catch(Exception ignored){}
            }
        }
        throw new IOException((ipv6?"IPv6":"IPv4")+" счётчики недоступны. Пробовали mangle и filter: "+String.join("; ",errors));
    }
    private void install(Backend b,Collection<String> ips)throws Exception{
        TelnetShell.Result chain=call(b,"-L "+b.chain+" -n");
        if(chain.code!=0)required(b,"-N "+b.chain);
        // Remove *only* our jump. The chain itself is private to this application.
        shell.exec("while "+b.cmd("-D FORWARD -j "+b.chain)+" 2>/dev/null; do :; done");
        required(b,"-F "+b.chain);
        for(String ip:ips){
            if(b.chain.equals(V4_CHAIN) ? !validIp4(ip) : !validIp6(ip))continue;
            String mask=b.chain.equals(V4_CHAIN)?"/32":"/128";
            required(b,"-A "+b.chain+" -s "+ip+mask+" -j RETURN");
            required(b,"-A "+b.chain+" -d "+ip+mask+" -j RETURN");
        }
        required(b,"-I FORWARD 1 -j "+b.chain);
        TelnetShell.Result proof=call(b,"-L "+b.chain+" -n -v -x");
        if(proof.code!=0||(!ips.isEmpty()&&!proof.output.contains("RETURN")))
            throw new IOException("Счётчики не появились в "+b.label()+" ("+reason(proof)+")");
    }
    private static final Pattern COUNTER=Pattern.compile("(?m)^\\s*\\d+\\s+(\\d+)\\s+RETURN\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\S+)\\s+(\\S+)");
    private static String address(String raw){return raw.replaceAll("/.*$","");}
    private void readCounters(Backend b,Map<String,long[]> counts)throws Exception{
        if(b==null)return;
        TelnetShell.Result output=call(b,"-L "+b.chain+" -n -v -x");
        if(output.code!=0)throw new IOException("Не удалось прочитать "+b.label()+": "+reason(output));
        Matcher m=COUNTER.matcher(output.output);
        while(m.find()){
            long n;try{n=Long.parseLong(m.group(1));}catch(NumberFormatException e){continue;}
            String src=address(m.group(2)),dst=address(m.group(3));
            if(!src.equals("0.0.0.0")&&!src.equals("::"))counts.computeIfAbsent(src,k->new long[2])[1]+=n;
            if(!dst.equals("0.0.0.0")&&!dst.equals("::"))counts.computeIfAbsent(dst,k->new long[2])[0]+=n;
        }
    }
    private void remove(Backend b){
        if(b==null)return;
        try{shell.exec("while "+b.cmd("-D FORWARD -j "+b.chain)+" 2>/dev/null; do :; done; "+
                b.cmd("-F "+b.chain)+" 2>/dev/null; "+b.cmd("-X "+b.chain)+" 2>/dev/null");}
        catch(Exception ignored){}
    }
    synchronized String diagnose(){
        StringBuilder out=new StringBuilder("Статус: ").append(note).append('\n');
        out.append("IPv4 backend: ").append(active4==null?"не выбран":active4.label()).append('\n');
        out.append("IPv6 backend: ").append(active6==null?"не выбран":active6.label()).append('\n');
        out.append("Клиентов со скоростью: ").append(rates.size()).append('\n');
        if(!error.isEmpty())out.append("Ошибка: ").append(error).append('\n');
        for(Backend b:candidates(false)){
            try{TelnetShell.Result r=call(b,"-L FORWARD -n");
                out.append(b.label()).append(": ").append(r.code==0?"доступно":"нет ("+reason(r)+")").append('\n');
            }catch(Exception e){out.append(b.label()).append(": ").append(shortText(e.getMessage())).append('\n');}
        }
        try{TelnetShell.Result r=shell.exec("cat /proc/sys/net/bridge/bridge-nf-call-iptables 2>/dev/null");
            out.append("bridge-nf-call-iptables: ").append(r.code==0?r.output.trim():"неизвестно").append('\n');}
        catch(Exception ignored){}
        out.append("Если FORWARD не видит пакеты (аппаратный NAT/bridge offload), скорость отдельных клиентов нельзя надёжно измерить этим способом.");
        return out.toString();
    }
    synchronized void stop(){
        try(RouterOperationGate.Lease ignored=RouterOperationGate.enter()){
        remove(active4);remove(active6);
        active4=null;active6=null;started=false;lastAt=0;nextIpv6ProbeAt=0;retry.reset();prior.clear();rates.clear();ips4=ips6="";
        note="Монитор остановлен";error="";
        }
    }
    @Override public synchronized void close(){stop();shell.close();}
}
