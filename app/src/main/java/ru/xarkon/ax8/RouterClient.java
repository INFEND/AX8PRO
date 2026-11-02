package ru.xarkon.ax8;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import android.util.Base64;

/** OLAX / Sanechips API port of v33. All calls must execute off the UI thread. */
public final class RouterClient {
    public static final int[] SUPPORTED_BANDS = {1,3,7,8,20,28,38,40,41};
    private static final int[] ALL_BANDS = {1,2,3,4,5,7,8,17,20,28,34,38,39,40,41,66};
    public static final String RADIO_FIELDS = "loginfo,ppp_status,network_provider,network_type,sub_network_type,"+
        "battery_pers,battery_vol_percent,battery_charging,nv_band,nv_bandwidth,nv_arfcn,nv_globecellid,nv_enodbid,nv_cellid,nv_pci,"+
        "nv_rsrp,nv_rsrq,nv_sinr,nv_rssi,lte_rssi,rssi,wan_csq,bandwidth,lte_bandwidth,lte_bw,current_bandwidth,CurrentBandwidth,"+
        "dl_bandwidth,lte_dl_bandwidth,nv_dl_bandwidth,nv_rank,nv_rank_index,rank,rank_index,ri,lte_ri,lte_rank,"+
        "nv_txpower,nv_tx_power,txpower,tx_power,lte_txpower,lte_tx_power,nv_cqi,nv_cqi0,nv_cqi1,cqi,cqi0,cqi1,tb0_cqi,tb1_cqi,"+
        "lte_cqi,lte_cqi0,lte_cqi1,nv_ul_mcs,ul_mcs,lte_ul_mcs,tb0_ul_mcs,ul_mcs0,nv_dl_mcs,nv_dl_mcs0,nv_dl_mcs1,"+
        "dl_mcs,dl_mcs0,dl_mcs1,lte_dl_mcs,lte_dl_mcs0,lte_dl_mcs1,tb0_dl_mcs,tb1_dl_mcs,wifi_access_sta_num,"+
        "realtime_rx_thrpt,realtime_tx_thrpt,realtime_rx_bytes,realtime_tx_bytes";

    private String base;
    private String user;
    private String password;
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private boolean authenticated = false;

    public RouterClient(String base, String user, String password) { configure(base,user,password); }
    public synchronized void configure(String host, String username, String pass) {
        String clean = host == null ? "" : host.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) clean = "http://" + clean;
        while(clean.endsWith("/")) clean = clean.substring(0,clean.length()-1);
        this.base = clean;
        this.user = username;
        this.password = pass;
        this.authenticated = false;
        cookieManager.getCookieStore().removeAll();
    }
    public synchronized String host() { return base; }
    public synchronized String hostname() {
        try { return URI.create(base).getHost(); } catch(Exception e) {return "192.168.150.1";}
    }
    public synchronized void invalidate() { authenticated=false;cookieManager.getCookieStore().removeAll(); }

    private String encode(Map<String,String> params) throws Exception {
        StringBuilder out=new StringBuilder();
        for(Map.Entry<String,String> p:params.entrySet()) {
            if(out.length()>0)out.append('&');
            out.append(URLEncoder.encode(p.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(p.getValue(),"UTF-8"));
        }
        return out.toString();
    }
    public static LinkedHashMap<String,String> map(String... vals) {
        LinkedHashMap<String,String> m=new LinkedHashMap<>();
        for(int i=0;i+1<vals.length;i+=2)m.put(vals[i],vals[i+1]);
        return m;
    }
    private synchronized JSONObject request(String path, Map<String,String> params, boolean post, int timeoutMs) throws Exception {
        String address=base+path;
        String body=encode(params);
        if(!post)address+="?"+body;
        HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();
        try {
            conn.setConnectTimeout(timeoutMs);conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent","Mozilla/5.0");
            conn.setRequestProperty("Referer",base+"/index.html");
            conn.setRequestProperty("Origin",base);
            conn.setRequestProperty("X-Requested-With","XMLHttpRequest");
            for(HttpCookie cookie:cookieManager.getCookieStore().getCookies()) conn.addRequestProperty("Cookie",cookie.toString());
            if(post) {
                conn.setRequestMethod("POST");conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
                try(OutputStream out=conn.getOutputStream()) {out.write(body.getBytes(StandardCharsets.UTF_8));}
            }
            int status=conn.getResponseCode();
            Map<String,List<String>> headers=conn.getHeaderFields();
            if(headers!=null) for(Map.Entry<String,List<String>> entry:headers.entrySet()) {
                if(entry.getKey()!=null && entry.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for(String raw:entry.getValue()) try {cookieManager.getCookieStore().add(new URI(base), HttpCookie.parse(raw).get(0));}catch(Exception ignored){}
                }
            }
            if(status<200 || status>=300)throw new IOException("HTTP "+status);
            try(InputStream input=conn.getInputStream();ByteArrayOutputStream buffer=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[4096];int n;
                while((n=input.read(bytes))!=-1){buffer.write(bytes,0,n);if(buffer.size()>2_000_000)throw new IOException("Ответ слишком большой");}
                String json=buffer.toString("UTF-8");
                return new JSONObject(sanitizeJson(json));
            }
        } finally {conn.disconnect();}
    }
    /** Fix raw control characters inside JSON strings, retaining valid escaped sequences. */
    static String sanitizeJson(String src) {
        StringBuilder b=new StringBuilder(src.length()); boolean quoted=false,escaped=false;
        for(int i=0;i<src.length();i++) {
            char c=src.charAt(i);
            if(escaped){b.append(c);escaped=false;continue;}
            if(c=='\\' && quoted){b.append(c);escaped=true;continue;}
            if(c=='"'){quoted=!quoted;b.append(c);continue;}
            if(quoted && c<32){ if(c=='\n')b.append("\\n"); else if(c=='\r')b.append("\\r"); else if(c=='\t')b.append("\\t"); else b.append(' '); }
            else b.append(c);
        }
        return b.toString();
    }
    public synchronized JSONObject get(String cmd) throws Exception {
        return request("/goform/goform_get_cmd_process",map("isTest","false","multi_data","1","cmd",cmd),false,4500);
    }
    public synchronized JSONObject getSingle(String cmd) throws Exception {
        return request("/goform/goform_get_cmd_process",map("isTest","false","cmd",cmd),false,4500);
    }
    public synchronized JSONObject post(Map<String,String> args) throws Exception {
        LinkedHashMap<String,String> payload=new LinkedHashMap<>(args);payload.putIfAbsent("isTest","false");
        return request("/goform/goform_set_cmd_process",payload,true,6500);
    }
    private static boolean success(JSONObject obj) {
        String v=obj.optString("result","").trim().toLowerCase(Locale.US);
        return v.equals("success")||v.equals("ok")||v.equals("0");
    }
    private synchronized void login() throws Exception {
        String pass=Base64.encodeToString(password.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        Exception failure=null;
        for(int mode=0;mode<2;mode++) {
            cookieManager.getCookieStore().removeAll();
            LinkedHashMap<String,String> args=map("goformId","LOGIN","password",pass);
            if(mode==0){args.put("user",user);args.put("username",user);}
            try {post(args);JSONObject check=get("loginfo");if("ok".equals(check.optString("loginfo"))) {authenticated=true;return;}}
            catch(Exception e) {failure=e;}
        }
        authenticated=false;
        throw new IOException("Авторизация WebUI не подтверждена ("+(failure==null?"loginfo != ok":failure.getMessage())+")");
    }
    public synchronized void ensureAuth() throws Exception {
        if(authenticated){try{if("ok".equals(get("loginfo").optString("loginfo")))return;}catch(Exception ignored){}}
        login();
    }
    public synchronized JSONObject status() throws Exception {
        ensureAuth();
        JSONObject obj=get(RADIO_FIELDS);
        if(!"ok".equals(obj.optString("loginfo"))) {login();obj=get(RADIO_FIELDS);}
        return obj;
    }
    public synchronized void network(boolean enabled) throws Exception {
        ensureAuth();String command=enabled?"CONNECT_NETWORK":"DISCONNECT_NETWORK";
        JSONObject result=post(map("goformId",command,"notCallback","true"));
        if(!success(result))throw new IOException(command+" : "+result);
    }
    public synchronized void reconnect(Progress progress) throws Exception {
        progress.note("Отключение мобильной сети...");network(false);
        waitPpp(false,20,progress);
        progress.note("Подключение мобильной сети...");network(true);
        waitPpp(true,60,progress);
    }
    public interface Progress {void note(String message);}
    public synchronized void waitPpp(boolean want,int seconds,Progress progress)throws Exception {
        for(int i=0;i<seconds;i++) {
            String state=get("ppp_status").optString("ppp_status","");
            if(want && state.equals("ppp_connected"))return;
            if(!want && !state.isEmpty() && !state.equals("ppp_connected") && !state.contains("ing"))return;
            if(i%3==0)progress.note("Состояние: "+state);
            Thread.sleep(1000);
        }
        throw new IOException("Не удалось подтвердить состояние WAN");
    }
    public synchronized void setBand(int band,Progress progress) throws Exception {
        ensureAuth();
        if(band!=0 && Arrays.stream(SUPPORTED_BANDS).noneMatch(b->b==band))
            throw new IllegalArgumentException("Band не поддерживается");
        LinkedHashMap<String,String> args=map("goformId","SET_LOCK_LTE_BAND_MODE");
        for(int b:ALL_BANDS){
            boolean enabled=band==0?Arrays.stream(SUPPORTED_BANDS).anyMatch(x->x==b):band==b;
            args.put("band"+b,enabled?"1":"0");
        }
        progress.note("Запись "+(band==0?"AUTO":"B"+band)+" в WebUI...");
        JSONObject r=post(args);
        if(!success(r))throw new IOException("SET_LOCK_LTE_BAND_MODE: "+r);
        boolean confirmed=false;
        for(int i=0;i<15;i++){
            Thread.sleep(2000);
            String value=get("LteBandLockSet").optString("LteBandLockSet","");
            if("LockBandFail".equals(value))throw new IOException("Роутер вернул LockBandFail");
            if("LockBandSuc".equals(value)){confirmed=true;break;}
            if(i%3==0)progress.note("Ожидание LockBandSuc...");
        }
        if(!confirmed)throw new IOException("LockBandSuc не получен. Команда перезагрузки не отправлена.");
        progress.note("LockBandSuc получен. Перезагрузка AX8, временная недоступность WebUI ожидаема.");
        try {
            JSONObject reboot=post(map("goformId","REBOOT_DEVICE"));
            if(reboot.has("result")&&!success(reboot))throw new IOException("REBOOT_DEVICE: "+reboot);
        }catch(SocketException|SocketTimeoutException e){
            // Connection may close immediately when reboot starts; this is expected.
        }
        invalidate();
        // Do not occupy the only UI worker for up to three minutes while the radio reboots.
        // MainActivity now verifies a fresh, authenticated LTE snapshot asynchronously.
        progress.note("Команда перезагрузки отправлена. Проверка нового Band продолжится на экране.");

    }

    public static final class Sms {
        public String id,number,date,content,tag;
        public boolean unread(){return "1".equals(tag);}
    }
    public synchronized List<Sms> sms() throws Exception {
        ensureAuth();
        JSONObject obj=request("/goform/goform_get_cmd_process",map("isTest","false","cmd","sms_data_total","page","0","data_per_page","500","mem_store","1","tags","10","order_by","order by id desc"),false,6000);
        ArrayList<Sms> out=new ArrayList<>();JSONArray rows=obj.optJSONArray("messages");
        // A malformed/partial response must not be treated as an empty inbox:
        // the background monitor would otherwise forget previously seen SMS IDs
        // and announce old messages again after the next successful request.
        if(rows==null){
            Object raw=obj.opt("messages");
            if(raw instanceof String){
                try{rows=new JSONArray((String)raw);}
                catch(org.json.JSONException e){throw new IOException("AX8: повреждён список SMS",e);}
            }
            if(rows==null)throw new IOException("AX8: ответ SMS не содержит массив messages");
        }
        for(int i=0;i<rows.length();i++) {JSONObject x=rows.optJSONObject(i);if(x==null)continue;
            Sms s=new Sms();s.id=clean(x.optString("id","?"));s.number=clean(x.optString("number","?"));s.tag=x.optString("tag","");s.date=smsDate(x.optString("date",""));s.content=decodeSms(x.optString("content",""));out.add(s);
        }return out;
    }
    static String smsDate(String raw){
        if(raw==null||raw.trim().isEmpty())return "?";
        String[] parts=raw.trim().split("[,;]");
        if(parts.length<6)return clean(raw);
        try {
            int year=Integer.parseInt(parts[0].trim()); year=year<70?2000+year:1900+year;
            String date=String.format(Locale.US,"%02d.%02d.%04d %02d:%02d:%02d",
                Integer.parseInt(parts[2].trim()),Integer.parseInt(parts[1].trim()),year,
                Integer.parseInt(parts[3].trim()),Integer.parseInt(parts[4].trim()),Integer.parseInt(parts[5].trim()));
            if(parts.length>6&&!parts[6].trim().isEmpty())date+=" TZ "+parts[6].trim();
            return date;
        }catch(Exception e){return clean(raw);}
    }
    /** WebUI-compatible SMS send, via router SIM (NOT Android SmsManager). */
    public static boolean validSmsNumber(String raw){
        if(raw==null)return false;
        String cleaned=raw.replaceAll("[\\s()\\-]", "");
        return cleaned.matches("\\+?[0-9]{5,16}");
    }
    public synchronized void sendSms(String to,String text)throws Exception{
        if(!validSmsNumber(to))throw new IllegalArgumentException("Номер получателя некорректен");
        if(text==null||text.trim().isEmpty())throw new IllegalArgumentException("Введите текст SMS");
        if(text.length()>700)throw new IllegalArgumentException("Максимальная длина: 700 символов");
        String number=to.replaceAll("[\\s()\\-]", "");
        byte[] body=text.getBytes(StandardCharsets.UTF_16BE);
        StringBuilder hex=new StringBuilder(body.length*2);
        for(byte ch:body)hex.append(String.format(Locale.US,"%02X",ch&0xFF));
        Calendar time=Calendar.getInstance();
        int offsetMinutes=time.getTimeZone().getOffset(time.getTimeInMillis())/60000;
        String offset=String.format(Locale.US,"%+d",offsetMinutes/60);
        if(offsetMinutes%60!=0)offset=String.format(Locale.US,"%+d:%02d",offsetMinutes/60,Math.abs(offsetMinutes%60));
        String smsTime=String.format(Locale.US,"%02d;%02d;%02d;%02d;%02d;%02d;%s",
            time.get(Calendar.YEAR)%100,time.get(Calendar.MONTH)+1,time.get(Calendar.DAY_OF_MONTH),
            time.get(Calendar.HOUR_OF_DAY),time.get(Calendar.MINUTE),time.get(Calendar.SECOND),offset);
        ensureAuth();
        JSONObject r=post(map("goformId","SEND_SMS","notCallback","true","Number",number,
            "sms_time",smsTime,"MessageBody",hex.toString(),"ID","-1","encode_type","UNICODE"));
        if(!success(r))throw new IOException("AX8 не подтвердил отправку SMS: "+r.toString());
    }
    public synchronized void readSms(Sms s)throws Exception{smsAction("SET_MSG_READ",s.id,true);}
    public synchronized void deleteSms(Sms s)throws Exception{smsAction("DELETE_SMS",s.id,false);}
    private void smsAction(String action,String id,boolean read)throws Exception{
        if(!id.matches("[0-9]+"))throw new IllegalArgumentException("Некорректный ID SMS");
        LinkedHashMap<String,String> args=map("goformId",action,"msg_id",id+";","notCallback","true");
        if(read)args.put("tag","0");ensureAuth();JSONObject r=post(args);
        if(!success(r))throw new IOException(action+": "+r);
    }
    static String clean(String s) {return s==null?"":s.replaceAll("[\\x00-\\x1F\\x7F-\\x9F]", " ").trim();}
    static String decodeSms(String raw) {
        if(raw==null)return "";
        String s=clean(raw);
        // AX8 normally stores Unicode SMS as UTF-16BE hex.
        if(s.length()>=4 && s.length()%4==0 && s.matches("(?i)[0-9a-f]+")) {
            try {byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
                String out=new String(b,StandardCharsets.UTF_16BE);
                if(out.codePoints().filter(c->c==0xFFFD).count()==0)return clean(out);
            }catch(Exception ignored){}
        }
        return s;
    }
    public static final class Device {
        public String mac="",name="Без имени",ip="",type="?",connectionTime="";
        public ArrayList<String> ipv6=new ArrayList<>();
    }
    public synchronized List<Device> devices()throws Exception {
        ensureAuth();LinkedHashMap<String,Device> all=new LinkedHashMap<>();boolean supported=false;
        for(String cmd:new String[]{"station_list","lan_station_list","lan_station_list_all"}){
            JSONObject reply;
            try {reply=getSingle(cmd);}catch(Exception ignored){continue;}
            Object obj=reply.opt(cmd);
            if(obj instanceof String){try{obj=new JSONArray((String)obj);}catch(Exception ignored){try{obj=new JSONObject((String)obj);}catch(Exception ignored2){}}}
            if(obj instanceof JSONObject) {
                JSONObject wrapper=(JSONObject)obj;
                for(String key:new String[]{"devices","station_list","list","data","items"}){
                    JSONArray arr=wrapper.optJSONArray(key);if(arr!=null){obj=arr;break;}
                }
            }
            if(!(obj instanceof JSONArray))continue;
            JSONArray rows=(JSONArray)obj;
            // Never interpret a malformed or partially decoded client list as
            // an empty list: that could overwrite the UI snapshot and trigger
            // QoS reconciliation against devices that are still connected.
            int validRows=0;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);
                if(row==null)continue;
                String state=first(row,"connected","is_connected","online","is_online","status").toLowerCase(Locale.US);
                boolean offline=state.equals("0")||state.equals("false")||state.equals("offline")||state.equals("disconnected");
                if(offline || !normalizeMac(first(row,"mac_addr","mac","MAC","macAddress","MacAddress","station_mac","client_mac_address")).isEmpty())
                    validRows++;
            }
            if(!ClientSnapshotPolicy.accept(rows.length(),validRows))continue;
            supported=true;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                String online=first(row,"connected","is_connected","online","is_online","status").toLowerCase(Locale.US);
                if(online.equals("0")||online.equals("false")||online.equals("offline")||online.equals("disconnected"))continue;
                String mac=normalizeMac(first(row,"mac_addr","mac","MAC","macAddress","MacAddress","station_mac","client_mac_address"));
                if(mac.isEmpty())continue;
                Device dev=all.getOrDefault(mac,new Device());dev.mac=mac;
                String name=first(row,"hostname","host_name","name","device_name","HostName","hostName","dev_name");
                if(!name.isEmpty()&&!name.equalsIgnoreCase("unknown"))dev.name=clean(name);
                String ip=first(row,"ip_addr","ip","IP","ipAddress","ipv4","ip_address");if(!ip.isEmpty())dev.ip=ip;
                String type=first(row,"dev_type","device_type","conn_type","connect_type","interface","ssid_index","ssid","type");if(!type.isEmpty())dev.type=type;
                String time=first(row,"connected_time","online_time","connect_time","connection_time","assoc_time","uptime");if(!time.isEmpty())dev.connectionTime=time;
                for(String key:new String[]{"ipv6_addr","ipv6","ipv6_address","ip6_addr","IPv6","ipv6Address","global_ipv6","wan_ipv6"}){
                    String raw=row.optString(key,"");for(String token:raw.split("[\\s,;]+")) {
                        String a=cleanIp6(token);if(!a.isEmpty()&&!dev.ipv6.contains(a))dev.ipv6.add(a);
                    }
                }
                all.put(mac,dev);
            }
        }
        if(!supported)throw new IOException("Прошивка не отдала station_list");
        return new ArrayList<>(all.values());
    }
    static String first(JSONObject obj,String... keys){for(String key:keys){String v=obj.optString(key,"").trim();if(!v.isEmpty()&&!v.equals("null"))return v;}return "";}
    static String normalizeMac(String value){
        if(value==null)return "";java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}").matcher(value);
        return m.find()?m.group().replace('-',':').toUpperCase(Locale.US):"";
    }
    static String cleanIp6(String ip){
        if(ip==null)return "";String a=ip.replaceAll("[%/].*$","").replace("[","").replace("]","");
        try {InetAddress n=InetAddress.getByName(a); if(n instanceof Inet6Address&&!n.isLinkLocalAddress()&&!n.isLoopbackAddress()&&!n.isMulticastAddress()&&!n.isAnyLocalAddress())return n.getHostAddress().split("%")[0];}
        catch(Exception ignored){}return "";
    }
    public static final class BlockState {public ArrayList<String> black=new ArrayList<>();public String backend="",mode="0",white="",whiteNames="",userMac="";}
    static ArrayList<String> macList(String src){
        ArrayList<String> out=new ArrayList<>();if(src==null)return out;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}").matcher(src);
        while(m.find()){String mac=normalizeMac(m.group());if(!out.contains(mac))out.add(mac);}return out;
    }
    private BlockState filterState()throws Exception {
        JSONObject j=get("ACL_mode,wifi_mac_black_list,wifi_hostname_black_list,wifi_mac_white_list,wifi_hostname_white_list,user_ip_addr,client_ip_address,get_user_mac_addr");
        if(!j.has("ACL_mode")&&!j.has("wifi_mac_black_list"))throw new IOException("WIFI_MAC_FILTER не поддерживается");
        BlockState s=new BlockState();s.backend="wifi";s.mode=j.optString("ACL_mode","0");
        s.black=macList(j.optString("wifi_mac_black_list"));s.white=j.optString("wifi_mac_white_list","");
        s.whiteNames=j.optString("wifi_hostname_white_list","");s.userMac=normalizeMac(j.optString("get_user_mac_addr"));return s;
    }
    private BlockState accessState()throws Exception {
        JSONObject j=getSingle("queryDeviceAccessControlList");
        Object payload=j.opt("queryDeviceAccessControlList");
        if(payload instanceof String)payload=new JSONObject((String)payload);
        if(!(payload instanceof JSONObject))throw new IOException("DeviceAccessControl не поддерживается");
        JSONObject a=(JSONObject)payload;if(!a.has("AclMode")&&!a.has("BlackMacList"))throw new IOException("AccessControl не поддерживается");
        BlockState s=new BlockState();s.backend="access";s.mode=a.optString("AclMode","0");s.black=macList(a.optString("BlackMacList"));s.white=a.optString("WhiteMacList","");s.whiteNames=a.optString("WhiteNameList","");return s;
    }
    public synchronized BlockState blockState()throws Exception {
        ensureAuth();try{return filterState();}catch(Exception e){return accessState();}
    }
    private static String digest(String s)throws Exception {
        byte[] d=MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(Locale.US,"%02x",x&0xff));return b.toString();
    }
    private JSONObject postWithAd(Map<String,String> args)throws Exception{
        JSONObject r=post(args);if(success(r))return r;
        try {JSONObject j=get("wa_inner_version,cr_version,RD");String rd=j.optString("RD","");
            if(rd.isEmpty())return r;String token=digest(digest(j.optString("wa_inner_version")+j.optString("cr_version"))+rd);
            LinkedHashMap<String,String> next=new LinkedHashMap<>(args);next.put("AD",token);return post(next);
        }catch(Exception ignored){return r;}
    }
    private void writeOnce(BlockState state,Collection<String> macs,int forcedMode)throws Exception {
        String list=String.join(";",macs);int mode=forcedMode>=0?forcedMode:(macs.isEmpty()?0:2);
        LinkedHashMap<String,String> p;
        if(state.backend.equals("access")){
            p=map("goformId","setDeviceAccessControlList","AclMode",String.valueOf(mode),"WhiteMacList",state.white,"BlackMacList",list,"WhiteNameList",state.whiteNames,"BlackNameList","");
        }else{
            p=map("goformId","WIFI_MAC_FILTER","ACL_mode",String.valueOf(mode),"macFilteringMode",String.valueOf(mode),"wifi_hostname_black_list","","wifi_mac_black_list",list);
        }
        JSONObject r=postWithAd(p);if(!success(r))throw new IOException("Блокировка не принята: "+r);
    }
    public synchronized void setBlocked(String mac,boolean block)throws Exception {
        ensureAuth();mac=normalizeMac(mac);if(mac.isEmpty())throw new IllegalArgumentException("MAC не распознан");
        BlockState state=blockState();
        if(block&&!state.userMac.isEmpty()&&state.userMac.equals(mac))throw new IOException("Нельзя заблокировать устройство управления");
        ArrayList<String> wanted=new ArrayList<>(state.black);
        if(block){if(!wanted.contains(mac))wanted.add(mac);}else wanted.remove(mac);
        writeBlocked(wanted,state);
    }
    public synchronized void writeBlocked(List<String> wanted,BlockState state)throws Exception {
        if(!wanted.isEmpty()&&!macList(state.white).isEmpty()&&!state.mode.matches("0|2|"))throw new IOException("Whitelist активен; DENY не включён");
        Exception last=null;
        for(String backend:new String[]{"wifi","access"}) {
            BlockState s=state;
            try{s=backend.equals("wifi")?filterState():accessState();}catch(Exception ignored){if(!backend.equals(state.backend))continue;}
            try {
                if(wanted.isEmpty() && backend.equals("wifi")) {
                    writeOnce(s,wanted,2);Thread.sleep(600);
                    if(!filterState().black.isEmpty()) {
                        postWithAd(map("goformId","GOFORM_WLAN_SAVE_WIFI_MAC_BLACK_LIST","wifi_mac_black_list","","wifi_hostname_black_list",""));
                        Thread.sleep(600);
                    }
                    if(!filterState().black.isEmpty())throw new IOException("Старые MAC не очищены");
                    writeOnce(s,wanted,0);Thread.sleep(400);
                    if(!filterState().black.isEmpty())writeOnce(s,wanted,2);
                }else writeOnce(s,wanted,-1);
                Thread.sleep(450);
                BlockState actual=backend.equals("wifi")?filterState():accessState();
                if(new HashSet<>(actual.black).equals(new HashSet<>(wanted)))return;
                throw new IOException("Проверка списка: ожидалось "+wanted+", пришло "+actual.black);
            }catch(Exception e){last=e;}
        }
        throw new IOException("Ни один backend не подтвердил блокировку: "+(last==null?"":last.getMessage()));
    }
    public synchronized void kick(String mac,Progress progress)throws Exception{
        BlockState original=blockState();
        ArrayList<String> temp=new ArrayList<>(original.black);
        mac=normalizeMac(mac);if(mac.isEmpty()||original.black.contains(mac))throw new IOException("Недоступно для этого устройства");
        if(!original.userMac.isEmpty() && original.userMac.equals(mac))throw new IOException("Нельзя отключить устройство управления");
        temp.add(mac); boolean attempted=false;
        try{attempted=true;writeBlocked(temp,original);progress.note("Клиент отключается на 10 секунд...");Thread.sleep(10000);}
        finally{if(attempted){progress.note("Восстановление исходного blacklist...");writeBlocked(original.black,blockState());}}
    }
}
