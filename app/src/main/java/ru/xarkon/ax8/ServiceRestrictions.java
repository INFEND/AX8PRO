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
    private static void checked(TelnetShell sh,String cmd)throws Exception{
        TelnetShell.Result r=sh.exec(cmd,10000);
        if(r.code!=0)throw new IOException("AX8: "+r.output);
    }
    private static void upload(TelnetShell sh,String path,byte[] content)throws Exception{
        String base64=java.util.Base64.getEncoder().encodeToString(content);
        checked(sh,": > "+quote(path+".b64"));
        for(int i=0;i<base64.length();i+=720)
            checked(sh,"printf %s "+quote(base64.substring(i,Math.min(i+720,base64.length())))+" >> "+quote(path+".b64"));
        checked(sh,"base64 -d "+quote(path+".b64")+" > "+quote(path)+" && rm -f "+quote(path+".b64"));
    }
    private byte[] agent()throws IOException{
        try(InputStream in=context.getAssets().open("ax8_service_agent.sh");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();
        }
    }
    void deploy(RouterClient.Progress progress)throws Exception{
        String rev=revision();if(rev.isEmpty()){save(rules());rev=revision();}
        String plan=ServiceCatalog.plan(rev,rules());
        try(RouterOperationGate.Lease ignored=RouterOperationGate.enter();TelnetShell sh=open()){
            progress.note("Проверка возможностей AX8");
            checked(sh,"test ! -L "+ROOT+" && mkdir -p "+ROOT+" && chmod 700 "+ROOT+
                " && command -v iptables >/dev/null && command -v ipset >/dev/null && command -v nslookup >/dev/null"+
                " && command -v timeout >/dev/null && command -v base64 >/dev/null && command -v nohup >/dev/null"+
                " && test -d /sys/class/net/br0 && iptables -L FORWARD -n >/dev/null");
            String alive="p=$(cat "+ROOT+"/pid 2>/dev/null); case \"$p\" in ''|*[!0-9]*) false;; *) "+
                "test -r /proc/$p/cmdline && tr '\\000' ' ' </proc/$p/cmdline | grep -F "+quote(ROOT+"/agent.sh")+" >/dev/null;; esac";
            boolean running=sh.exec(alive).code==0;
            if(!running){
                progress.note("Установка автономного модуля");
                upload(sh,ROOT+"/agent.new",agent());
                checked(sh,"sh -n "+ROOT+"/agent.new && mv "+ROOT+"/agent.new "+ROOT+"/agent.sh && chmod 700 "+ROOT+"/agent.sh");
            }
            upload(sh,ROOT+"/plan.new",plan.getBytes(StandardCharsets.UTF_8));
            checked(sh,"mv "+ROOT+"/plan.new "+ROOT+"/plan");
            if(!running){
                checked(sh,"rm -f "+ROOT+"/pid; nohup sh "+ROOT+"/agent.sh </dev/null >"+ROOT+"/agent.log 2>&1 &");
                Thread.sleep(400);
                if(sh.exec(alive).code!=0)throw new IOException("Автономный модуль не запустился; проверьте прошивку AX8");
            }
            progress.note("План передан AX8. Проверьте статус установки правил.");
        }
    }
    String status()throws Exception{
        try(RouterOperationGate.Lease ignored=RouterOperationGate.enter();TelnetShell sh=open()){
            TelnetShell.Result r=sh.exec("if test -f "+ROOT+"/status; then cat "+ROOT+"/status; else echo AX8SVC_NOT_INSTALLED; fi");
            if(r.code!=0)throw new IOException(r.output);
            for(String line:r.output.split("\\r?\\n")){
                String[] parts=line.trim().split("\\|",4);
                if(parts.length==4&&Arrays.asList("OFF","WORK","ERROR","INSTALLED").contains(parts[0])){
                    String state=parts[0].equals("INSTALLED")?"Правила установлены (эффективность не подтверждена)":parts[0].equals("OFF")?"Отключено":
                        parts[0].equals("WORK")?"Установка выполняется":"Ошибка";
                    return state+"\nПравил: "+parts[2]+"\n"+parts[3]+
                        (parts[1].equals(revision())?"":"\nНа AX8 другая версия правил; локальные изменения не подтверждены.");
                }
            }
            return "Нет подтверждённого статуса на AX8. Модуль не установлен или ещё запускается.";
        }
    }
}
