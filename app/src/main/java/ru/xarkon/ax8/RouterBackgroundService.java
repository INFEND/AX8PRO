package ru.xarkon.ax8;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** User-enabled connected-device foreground service. No server/cloud. */
public final class RouterBackgroundService extends Service {
    static final String ACTION_START="ru.xarkon.ax8.START_MONITOR";
    static final String ACTION_STOP="ru.xarkon.ax8.STOP_MONITOR";
    static final String ACTION_TEST_SMS="ru.xarkon.ax8.TEST_SMS_NOTIFICATION";
    static final String ACTION_BACKGROUND_NOW="ru.xarkon.ax8.BACKGROUND_NOW";
    private static final String CHANNEL="ax8_background_control";
    private static final String CHANNEL_SMS="ax8_sms_alerts";
    private static final int NOTIFICATION=8064;
    private static final int SMS_NOTIFICATION=8065;
    private static volatile boolean running=false;
    static boolean isRunning(){return running;}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;
    private RouterClient router;
    private QosController qos;
    private DeviceTrafficMonitor traffic;
    private OwnerPriorityController priority;
    private QosWatchdog watchdog;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private volatile boolean busy=false,stopping=false,backgroundActive=false;
    private final Runnable tick=new Runnable(){public void run(){
        if(stopping)return;
        boolean visible=prefs.getBoolean("ui_visible",true);
        if(visible){
            releaseLocks();
            if(backgroundActive && !busy){busy=true;worker.execute(()->{try{endBackgroundCycle();}finally{busy=false;}});}
        }else if(!busy){
            busy=true;worker.execute(()->{
                long started=android.os.SystemClock.elapsedRealtime();
                long startedWall=System.currentTimeMillis();
                try{backgroundCycle();}
                catch(Exception e){
                    String reason=e.getMessage()==null?e.toString():e.getMessage();
                    prefs.edit().putLong("bg_last_poll",System.currentTimeMillis())
                        .putString("bg_error",reason).apply();
                    notifyState("Фоновый опрос AX8: "+reason);
                }finally{
                    if(prefs.getBoolean("refresh_auto",true)){
                        long elapsed=android.os.SystemClock.elapsedRealtime()-started;
                        int old=prefs.getInt("auto_poll_ms",500);
                        // A current successful radio sample confirms that AX8 has answered.
                        boolean ok=prefs.getLong("bg_snapshot_at",0L)>=startedWall
                            && prefs.getString("bg_error","").isEmpty();
                        int adjusted=AutoPollController.next(old,elapsed,ok);
                        if(adjusted!=old)prefs.edit().putInt("auto_poll_ms",adjusted).apply();
                    }
                    busy=false;
                }
            });
        }
        // Use the same adaptive/manual cadence while the app is closed.
        int interval=prefs.getBoolean("refresh_auto",true)?
            AutoPollController.clamp(prefs.getInt("auto_poll_ms",500)):
            AutoPollController.clamp(prefs.getInt("refresh_ms",500));
        handler.postDelayed(this,interval);
    }};

    @Override public void onCreate(){
        super.onCreate();running=true;stopping=false;
        prefs=getSharedPreferences("ax8_control",MODE_PRIVATE);
        openControllers();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"AX8 · Фоновое управление",NotificationManager.IMPORTANCE_LOW));
        NotificationChannel sms=new NotificationChannel(CHANNEL_SMS,"AX8 · SMS",NotificationManager.IMPORTANCE_HIGH);
        sms.enableVibration(true);nm.createNotificationChannel(sms);
    }
    private synchronized void openControllers(){
        router=new RouterClient(prefs.getString("host","192.168.150.1"),prefs.getString("username","root"),prefs.getString("password","admin"));
        qos=new QosController(router,prefs);traffic=new DeviceTrafficMonitor(router,prefs);
        priority=new OwnerPriorityController();watchdog=new QosWatchdog();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && ACTION_STOP.equals(intent.getAction())){
            prefs.edit().putBoolean("background_mode",false).apply();stopSelf();return START_NOT_STICKY;
        }
        if(!prefs.getBoolean("background_mode",false)){stopSelf();return START_NOT_STICKY;}
        try {
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification("Ожидание сворачивания приложения"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION,notification("Ожидание сворачивания приложения"));
        }catch(Exception e){prefs.edit().putString("bg_error",e.toString()).putBoolean("background_mode",false).apply();stopSelf();return START_NOT_STICKY;}
        if(intent!=null && ACTION_BACKGROUND_NOW.equals(intent.getAction())){
            handler.removeCallbacks(tick);handler.post(tick);
            return START_STICKY;
        }
        if(intent!=null && ACTION_TEST_SMS.equals(intent.getAction())){
            RouterClient.Sms test=new RouterClient.Sms();test.number="Тестовое SMS";
            test.content="Проверка уведомлений AX 8 PRO. Новое SMS не получено.";
            notifyNewSms(test,1);
            return START_STICKY;
        }
        handler.removeCallbacks(tick);handler.post(tick);
        return START_STICKY;
    }
    private Notification notification(String status){
        Intent launch=new Intent(this,MainActivity.class);
        PendingIntent open=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,RouterBackgroundService.class).setAction(ACTION_STOP);
        PendingIntent stopPending=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_app)
            .setContentTitle("AX 8 PRO · Фоновый режим").setContentText(status)
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Остановить",stopPending).build();
    }
    private void notifyState(String msg){
        try {((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(msg));}
        catch(Exception ignored){}
    }
    private void notifyNewSms(RouterClient.Sms sms,int unread){
        try{
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(!nm.areNotificationsEnabled()){
                prefs.edit().putString("bg_notification_error","Уведомления приложения запрещены в Android").apply();return;
            }
            NotificationChannel channel=nm.getNotificationChannel(CHANNEL_SMS);
            if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE){
                prefs.edit().putString("bg_notification_error","Канал AX8 · SMS отключён в настройках Android").apply();return;
            }
            Intent launch=new Intent(this,MainActivity.class);
            launch.putExtra("open_tab",2).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open=PendingIntent.getActivity(this,11,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            String text=sms.content==null?"":sms.content.trim();
            if(text.length()>72)text=text.substring(0,72)+"…";
            Notification n=new Notification.Builder(this,CHANNEL_SMS)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Новое SMS: "+sms.number)
                .setContentText(text.isEmpty()?"Получено новое сообщение":text)
                .setSubText(unread>1?"Непрочитанных: "+unread:null)
                .setContentIntent(open).setAutoCancel(true)
                .setStyle(new Notification.BigTextStyle().bigText(text.isEmpty()?"Получено новое сообщение":text))
                .build();
            // Tagged notifications preserve separate SMS alerts instead of overwriting
            // the previous message when several arrive between two background polls.
            nm.notify("ax8_sms_"+(sms.id==null?"test":sms.id),SMS_NOTIFICATION,n);
            prefs.edit().putLong("bg_last_notification_at",System.currentTimeMillis()).putString("bg_notification_error","").apply();
        }catch(Exception e){prefs.edit().putString("bg_notification_error",e.toString()).apply();}
    }
    private static String joinUnreadIds(List<RouterClient.Sms> list){
        StringBuilder b=new StringBuilder();
        for(RouterClient.Sms sms:list)if(sms!=null&&sms.unread()&&sms.id!=null&&!sms.id.isEmpty())b.append(sms.id).append(',');
        return b.toString();
    }
    private static String serializeHistory(ArrayDeque<Float> q){
        StringBuilder b=new StringBuilder();for(Float v:q){if(b.length()>0)b.append(',');b.append(v==null?0f:v);}return b.toString();
    }
    private static JSONArray devicesJson(List<RouterClient.Device> list){
        JSONArray arr=new JSONArray();if(list==null)return arr;for(RouterClient.Device d:list){
            JSONObject o=new JSONObject();try{o.put("mac",d.mac);o.put("name",d.name);o.put("ip",d.ip);o.put("type",d.type);o.put("connectionTime",d.connectionTime);
                JSONArray ip6=new JSONArray();for(String x:d.ipv6)ip6.put(x);o.put("ipv6",ip6);}catch(Exception ignored){}
            arr.put(o);
        }return arr;
    }
    private static JSONArray smsJson(List<RouterClient.Sms> list){
        JSONArray arr=new JSONArray();if(list==null)return arr;for(RouterClient.Sms s:list){
            JSONObject o=new JSONObject();try{o.put("id",s.id);o.put("number",s.number);o.put("date",s.date);o.put("content",s.content);o.put("tag",s.tag);}catch(Exception ignored){}
            arr.put(o);
        }return arr;
    }
    private static JSONObject blockedJson(RouterClient.BlockState b){
        JSONObject o=new JSONObject();if(b==null)return o;try{o.put("backend",b.backend);o.put("mode",b.mode);o.put("white",b.white);o.put("whiteNames",b.whiteNames);o.put("userMac",b.userMac);
            JSONArray a=new JSONArray();for(String x:b.black)a.put(x);o.put("black",a);}catch(Exception ignored){}
        return o;
    }
    private void acquireLocks(){
        // The setting can be disabled while the service is alive. Drop any
        // previously acquired CPU/Wi-Fi locks instead of keeping them indefinitely.
        if(!prefs.getBoolean("background_keep_awake",true)){releaseLocks();return;}
        try{
            if(cpu==null){PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
                cpu=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ax8:background_poll");cpu.setReferenceCounted(false);}
            if(!cpu.isHeld())cpu.acquire();
            if(wifi==null){WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                if(wm!=null){wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"ax8:router_wifi");wifi.setReferenceCounted(false);}}
            if(wifi!=null&&!wifi.isHeld())wifi.acquire();
        }catch(Exception e){prefs.edit().putString("bg_error","WakeLock: "+e.getMessage()).apply();}
    }
    private void releaseLocks(){
        try{if(cpu!=null&&cpu.isHeld())cpu.release();}catch(Exception ignored){}
        try{if(wifi!=null&&wifi.isHeld())wifi.release();}catch(Exception ignored){}
    }
    private static double parseNum(String raw){
        try{return Double.parseDouble(String.valueOf(raw).replace("%","").trim());}catch(Exception e){return Double.NaN;}
    }
    private void backgroundCycle(){
        acquireLocks();
        if(prefs.getBoolean("ui_visible",true)){releaseLocks();return;}
        backgroundActive=true;
        JSONObject radio=null;
        List<RouterClient.Device> devices=null;
        List<RouterClient.Sms> smsList=null;
        RouterClient.BlockState blockState=null;
        String statusIssue="",deviceIssue="",smsIssue="",qosIssue="";
        // SMS is polled first; a Telnet/QoS or Wi-Fi-client error must never suppress SMS alerts.
        try{smsList=router.sms();}catch(Exception e){smsIssue=e.getMessage()==null?e.toString():e.getMessage();}
        if(smsList!=null){
            int unread=0;
            LinkedHashSet<String> seen=new LinkedHashSet<>();
            List<String> unreadIds=new ArrayList<>();
            for(RouterClient.Sms item:smsList){
                if(item.id!=null&&!item.id.isEmpty())seen.add(item.id);
                if(item.unread()){
                    unread++;
                    if(item.id!=null&&!item.id.isEmpty())unreadIds.add(item.id);
                }
            }
            String previous=prefs.getString("bg_sms_seen_ids","");
            boolean initialized=prefs.getBoolean("bg_sms_seen_initialized",false);
            List<String> freshIds=SmsAlertPlanner.newUnreadIds(unreadIds,previous,initialized);
            // Preserve recently observed IDs even if the router returns a valid
            // but temporarily empty or truncated page. A missing ID is not a new SMS.
            String mergedIds=SmsAlertPlanner.mergeSeenIds(previous,seen);
            prefs.edit().putInt("bg_sms",smsList.size()).putInt("bg_sms_unread",unread)
                .putString("bg_sms_seen_ids",mergedIds).putBoolean("bg_sms_seen_initialized",true)
                .putString("bg_sms_json",smsJson(smsList).toString()).putLong("bg_sms_at",System.currentTimeMillis()).apply();
            // The first scan establishes a baseline. Notify for every new unread message,
            // oldest first, rather than silently dropping all but one from a batch.
            if(!freshIds.isEmpty()){
                Set<String> pending=new HashSet<>(freshIds);
                for(int i=smsList.size()-1;i>=0;i--){
                    RouterClient.Sms item=smsList.get(i);
                    if(pending.remove(item.id))notifyNewSms(item,unread);
                }
            }
        }
        try{radio=router.status();}catch(Exception e){statusIssue=e.getMessage()==null?e.toString():e.getMessage();}
        try{devices=router.devices();}catch(Exception e){deviceIssue=e.getMessage()==null?e.toString():e.getMessage();}
        try{blockState=router.blockState();}catch(Exception ignored){}
        if(devices!=null){
            try{qos.resyncAddresses(devices,note->{});}catch(Exception e){qosIssue="QoS: "+e.getMessage();}
            try{
                if(prefs.getBoolean("traffic_monitor",false)){
                    traffic.sample(devices);
                    if(!traffic.error().isEmpty())qosIssue="Монитор: "+traffic.error();
                    boolean prioritize=prefs.getBoolean("background_priority",false)&&traffic.error().isEmpty();
                    String owner=prefs.getString("owner_mac","");
                    if(prioritize&&!owner.isEmpty()){
                        if(!priority.enabled||!owner.equals(priority.owner))priority.enable(owner);
                        priority.update(devices,traffic,qos);
                    }else if(priority.enabled){qos.applyPriority(owner,null,devices,msg->{});priority.disable();}
                    if(traffic.error().isEmpty()&&prefs.getBoolean("background_qos_watch",true))
                        watchdog.check(devices,traffic,qos);
                }else if(priority.enabled){qos.applyPriority("",null,devices,msg->{});priority.disable();}
            }catch(Exception e){qosIssue="QoS: "+(e.getMessage()==null?e.toString():e.getMessage());}
        }
        long now=System.currentTimeMillis();
        android.content.SharedPreferences.Editor editor=prefs.edit().putLong("bg_last_poll",now);
        if(radio!=null){
            editor.putString("bg_status_json",radio.toString()).putLong("bg_snapshot_at",now)
                .putString("bg_wan",radio.optString("ppp_status","?"));
            // If a field is absent, don't substitute an old speed as a current measurement.
            double rx=parseNum(radio.optString("realtime_rx_thrpt",""));
            double tx=parseNum(radio.optString("realtime_tx_thrpt",""));
            ArrayDeque<Float> histDl=history("bg_history_dl"),histUl=history("bg_history_ul");
            histDl.add((float)(Double.isFinite(rx)?Math.max(0,rx):0));
            histUl.add((float)(Double.isFinite(tx)?Math.max(0,tx):0));
            while(histDl.size()>28)histDl.poll();while(histUl.size()>28)histUl.poll();
            editor.putString("bg_history_dl",serializeHistory(histDl)).putString("bg_history_ul",serializeHistory(histUl));
            editor.putLong("bg_fallback_dl_bits",Double.doubleToRawLongBits(rx));
            editor.putLong("bg_fallback_ul_bits",Double.doubleToRawLongBits(tx));
        }
        if(devices!=null){editor.putInt("bg_clients",devices.size())
            .putString("bg_devices_json",devicesJson(devices).toString())
            .putLong("bg_devices_at",now);}
        if(blockState!=null){editor.putInt("bg_blocked",blockState.black.size())
            .putString("bg_blocked_json",blockedJson(blockState).toString())
            .putLong("bg_blocked_at",now);}
        // Link SMS to this completed cycle, not merely to a previous successful read.
        if(smsList!=null)editor.putLong("bg_sms_cycle_at",now);
        String issues=(!statusIssue.isEmpty()?"LTE: "+statusIssue+"; ":"")+
            (!smsIssue.isEmpty()?"SMS: "+smsIssue+"; ":"")+
            (!deviceIssue.isEmpty()?"Клиенты: "+deviceIssue+"; ":"")+qosIssue;
        editor.putString("bg_error",issues).putString("bg_qos",qosIssue.isEmpty()?watchdog.status():qosIssue)
            .putString("bg_priority_status",priority.mode).apply();
        if(!issues.isEmpty())notifyState("AX8: "+issues);
        else notifyState("Клиентов: "+BackgroundSummary.clientCount(devices)+" · SMS: "+(smsList==null?"?":smsList.size())+
            " · "+(radio!=null&&"ppp_connected".equals(radio.optString("ppp_status"))?"LTE online":"LTE проверяется"));
    }
    private ArrayDeque<Float> history(String key){
        ArrayDeque<Float> out=new ArrayDeque<>();String raw=prefs.getString(key,"");
        if(raw!=null&&!raw.isEmpty())for(String piece:raw.split(","))try{out.add(Float.parseFloat(piece));}catch(Exception ignored){}
        return out;
    }
    private void endBackgroundCycle(){
        backgroundActive=false;releaseLocks();
        try{if(priority.enabled){qos.applyPriority(priority.owner,null,router.devices(),msg->{});priority.disable();}}
        catch(Exception e){prefs.edit().putString("bg_error","Не удалось снять временный приоритет: "+e.getMessage()).apply();}
        try{traffic.stop();}catch(Exception ignored){}
        notifyState("Приложение открыто · мониторинг через интерфейс");
    }
    @Override public void onDestroy(){
        stopping=true;running=false;handler.removeCallbacks(tick);releaseLocks();
        // The in-flight background poll may still own CPU/Wi-Fi locks when
        // onDestroy() is called. Release again after the queued worker cleanup,
        // otherwise a poll can reacquire a lock after the early release above.
        worker.execute(()->{
            try{
                try{if(priority.enabled)qos.applyPriority(priority.owner,null,router.devices(),msg->{});}catch(Exception ignored){}
                try{traffic.close();}catch(Exception ignored){}
                try{qos.close();}catch(Exception ignored){}
            }finally{releaseLocks();}
        });
        worker.shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}