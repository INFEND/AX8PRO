package ru.xarkon.ax8;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stores desired rules separately from the router's reported applied status. */
final class ServiceRestrictions {
    private static final String ROOT="/tmp/ax8pro-services";
    private static final String AGENT_VERSION="3";
    private final Context context;
    private final SharedPreferences prefs;
    private final RouterClient router;
    private final String key;
    ServiceRestrictions(Context c,SharedPreferences p,RouterClient r){
        context=c.getApplicationContext();prefs=p;router=r;key="svc_rules_v1_"+r.host();
    }
    Map<String,Set<String>> rules(){
        Map<String,Set<String>> result=new TreeMap<>();
        try{
            JSONObject obj=new JSONObject(prefs.getString(key,"{}"));
            for(Iterator<String> it=obj.keys();it.hasNext();){
                String scope=it.next();if(!ServiceCatalog.validScope(scope))continue;
                JSONArray arr=obj.optJSONArray(scope);if(arr==null)continue;
                Set<String> ids=new TreeSet<>();
                for(int i=0;i<arr.length();i++){String id=arr.optString(i);if(ServiceCatalog.get(id)!=null)ids.add(id);}
                if(!ids.isEmpty())result.put(scope,ids);
            }
        }catch(Exception ignored){}
        return result;
    }
    int count(){int count=0;for(Set<String> ids:rules().values())count+=ids.size();return count;}
    void save(Map<String,Set<String>> rules){
        String revision=UUID.randomUUID().toString();
        ServiceCatalog.plan(revision,rules);
        JSONObject obj=new JSONObject();
        for(Map.Entry<String,Set<String>> e:rules.entrySet())if(!e.getValue().isEmpty())
            try{obj.put(e.getKey(),new JSONArray(e.getValue()));}catch(JSONException ex){throw new IllegalStateException(ex);}
        prefs.edit().putString(key,obj.toString()).putString(key+"_rev",revision).apply();
    }
    String revision(){return prefs.getString(key+"_rev","");}
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private TelnetShell open(){return new TelnetShell(router,prefs.getString("telnet_user","admin"),prefs.getString("telnet_pass","admin"));}
    private static void require(TelnetShell sh,String cmd,String failure)throws Exception{
        TelnetShell.Result r=sh.exec(cmd,10000);
        if(r.code!=0)throw new IOException(failure+" (код "+r.code+")");
    }
    private static void checked(TelnetShell sh,String cmd)throws Exception{
        require(sh,cmd,"Не удалось записать или запустить модуль сервисов на AX8");
    }
    // POSIX printf is a shell builtin. Octal encoding avoids base64 dependency,
    // preserves UTF-8 bytes and keeps all commands below typical Telnet line limits.
    private static void upload(TelnetShell sh,String path,byte[] content)throws Exception{
        checked(sh,": > "+quote(path));
        for(int i=0;i<content.length;i+=120){
            StringBuilder escaped=new StringBuilder();
            for(int j=i;j<Math.min(i+120,content.length);j++){
                int n=content[j]&255;
                escaped.append("\\0").append((char)('0'+(n>>6)))
                    .append((char)('0'+((n>>3)&7))).append((char)('0'+(n&7)));
            }
            checked(sh,"printf '%b' "+quote(escaped.toString())+" >> "+quote(path));
        }
        require(sh,"test \"$(wc -c < "+quote(path)+")\" -eq "+content.length,
            "Передача файла завершилась не полностью");
    }
    private byte[] agent()throws IOException{
        try(InputStream in=context.getAssets().open("ax8_service_agent.sh");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();
        }
    }
    private static String aliveCommand(){
        return "p=$(cat "+ROOT+"/pid 2>/dev/null); case \"$p\" in ''|*[!0-9]*) false;; *) "+
            "test -r /proc/$p/cmdline && tr '\\000' '\\n' </proc/$p/cmdline | grep -Fx "+quote(ROOT+"/agent.sh")+" >/dev/null;; esac";
    }
    // Only used if no router DNS resolver executable is available. The plan
    // remains autonomous for enforcement, but these addresses are a snapshot.
    private String phoneSeeds(String rev,Map<String,Set<String>> rules)throws Exception{
        TreeSet<String> hosts=new TreeSet<>();
        for(Set<String> ids:rules.values())for(String id:ids)
            Collections.addAll(hosts,ServiceCatalog.get(id).hosts.split(" "));
        StringBuilder out=new StringBuilder(rev).append('\n');
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4,r->{
            Thread t=new Thread(r,"AX8-DNS");t.setDaemon(true);return t;
        });
        try{
            Map<String,java.util.concurrent.Future<java.net.InetAddress[]>> jobs=new TreeMap<>();
            for(String host:hosts)jobs.put(host,pool.submit(()->java.net.InetAddress.getAllByName(host)));
            long deadline=android.os.SystemClock.elapsedRealtime()+20000;
            for(Map.Entry<String,java.util.concurrent.Future<java.net.InetAddress[]>> e:jobs.entrySet()){
                try{
                    long left=deadline-android.os.SystemClock.elapsedRealtime();
                    if(left<=0&&!e.getValue().isDone())continue;
                    for(java.net.InetAddress ip:e.getValue().get(Math.max(1,left),java.util.concurrent.TimeUnit.MILLISECONDS)){
                        if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())continue;
                        String value=ip.getHostAddress();
                        if(value!=null&&value.matches("[0-9a-fA-F:.]+"))out.append(e.getKey()).append(' ').append(value).append('\n');
                    }
                }catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException ignored){}
            }
        }finally{pool.shutdownNow();}
        if(!hosts.isEmpty()&&out.toString().equals(rev+"\n"))
            throw new IOException("Нет DNS-ответов ни на AX8, ни на телефоне. Проверьте интернет и DNS телефона.");
        return out.toString();
    }
    void deploy(RouterClient.Progress progress)throws Exception{
        String rev=revision();if(rev.isEmpty()){save(rules());rev=revision();}
        String plan=ServiceCatalog.plan(rev,rules());
        try(RouterOperationGate.Lease ignored=RouterOperationGate.enter();TelnetShell sh=open()){
            progress.note("Проверка возможностей AX8");
            require(sh,"test ! -L "+ROOT+" && mkdir -p "+ROOT+" && chmod 700 "+ROOT+" && test -w "+ROOT,
                "Нет доступа к рабочей папке /tmp/ax8pro-services");
            require(sh,"command -v iptables >/dev/null && iptables -L FORWARD -n >/dev/null 2>&1",
                "iptables: таблица filter/FORWARD недоступна");
            require(sh,"test -d /sys/class/net/br0","Не найден LAN-интерфейс br0; автоматическая установка отменена");

            require(sh,"(for c in awk grep sort head wc cp mv rm chmod date sleep tr; do command -v \"$c\" >/dev/null || exit 1; done)",
                "В прошивке отсутствуют необходимые базовые утилиты shell");
            String alive=aliveCommand();
            boolean running=sh.exec(alive).code==0;
            boolean current=sh.exec("test \"$(cat "+ROOT+"/agent.version 2>/dev/null)\" = "+AGENT_VERSION).code==0;
            if(!running||!current){
                progress.note("Установка совместимого модуля (ipset необязателен)");
                upload(sh,ROOT+"/agent.new",agent());
                require(sh,"sh -n "+ROOT+"/agent.new","Прошивка не поддерживает синтаксис автономного модуля");
                if(running){
                    require(sh,alive+" && kill -TERM \"$p\"","Не удалось остановить прежний модуль");
                    for(int i=0;i<12&&sh.exec(alive).code==0;i++)Thread.sleep(500);
                    if(sh.exec(alive).code==0)throw new IOException("Прежний модуль ещё завершает работу; повторите передачу");
                    running=false;
                }
                checked(sh,"mv "+ROOT+"/agent.new "+ROOT+"/agent.sh && chmod 700 "+ROOT+"/agent.sh && printf %s "+AGENT_VERSION+" > "+ROOT+"/agent.version");
            }
            boolean localResolver=sh.exec("(command -v nslookup || command -v getent || command -v ping || command -v ping6 || "+
                "(command -v busybox && busybox --list | grep -Ex 'nslookup|getent|ping|ping6')) >/dev/null 2>&1").code==0;
            // Revision-tagged seed files cannot be applied to an older plan.
            if(!localResolver&&!rules().isEmpty()){
                progress.note("DNS на телефоне: резервный снимок IP");
                upload(sh,ROOT+"/seeds.new",phoneSeeds(rev,rules()).getBytes(StandardCharsets.UTF_8));
                checked(sh,"mv "+ROOT+"/seeds.new "+ROOT+"/seeds");
            }else checked(sh,"rm -f "+ROOT+"/seeds");
            upload(sh,ROOT+"/plan.new",plan.getBytes(StandardCharsets.UTF_8));
            checked(sh,"mv "+ROOT+"/plan.new "+ROOT+"/plan");
            if(!running){
                checked(sh,"rm -f "+ROOT+"/pid; (trap '' HUP; exec sh "+ROOT+"/agent.sh) </dev/null >"+ROOT+"/agent.log 2>&1 &");
                Thread.sleep(400);
                if(sh.exec(alive).code!=0)throw new IOException("Автономный модуль не запустился; проверьте прошивку AX8");
            }
            progress.note("План передан AX8. Проверьте статус установки правил.");
        }
    }
    String status()throws Exception{
        try(RouterOperationGate.Lease ignored=RouterOperationGate.enter();TelnetShell sh=open()){
            boolean running=sh.exec(aliveCommand()).code==0;
            TelnetShell.Result r=sh.exec("if test -f "+ROOT+"/status; then cat "+ROOT+"/status; else echo AX8SVC_NOT_INSTALLED; fi");
            if(r.code!=0)throw new IOException(r.output);
            for(String line:r.output.split("\\r?\\n")){
                String[] parts=line.trim().split("\\|",4);
                if(parts.length==4&&Arrays.asList("OFF","WORK","ERROR","INSTALLED").contains(parts[0])){
                    String state=parts[0].equals("INSTALLED")?"Правила установлены (эффективность не подтверждена)":parts[0].equals("OFF")?"Отключено":
                        parts[0].equals("WORK")?"Установка выполняется":"Ошибка";
                    String health="";
                    if(!running&&!parts[0].equals("OFF")){
                        state="Состояние фильтра не подтверждено";
                        health+="\nАвтономный процесс не работает; передайте правила заново.";
                    }
                    if(parts[0].equals("INSTALLED")){
                        TelnetShell.Result proof=sh.exec("s=$(cat "+ROOT+"/active 2>/dev/null); case \"$s\" in A|B) "+
                            "iptables -L FORWARD -n | grep -E \"^[[:space:]]*AX8SVC$s[[:space:]]\" >/dev/null;; *) false;; esac");
                        if(proof.code!=0){
                            state="Состояние фильтра не подтверждено";
                            health+="\nАктивная цепочка не обнаружена.";
                        }
                    }
                    if(parts[3].contains("DNS=phone_snapshot"))
                        health+="\nDNS: снимок адресов с телефона. Автоматического обновления IP нет; передайте правила повторно после смены адресов.";
                    return state+"\nПравил: "+parts[2]+"\n"+parts[3]+health+
                        (parts[1].equals(revision())?"":"\nНа AX8 другая версия правил; локальные изменения не подтверждены.");
                }
            }
            return "Нет подтверждённого статуса на AX8. Модуль не установлен или ещё запускается.";
        }
    }
}
