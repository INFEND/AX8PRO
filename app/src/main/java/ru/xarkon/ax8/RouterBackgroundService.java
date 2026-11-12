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
    private RouterEndpointConfig endpoint;
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
            if(backgroundActive && !busy){
                busy=true;
                worker.execute(()->{
                    try{endBackgroundCycle();}
                    finally{busy=false;scheduleNextTick();}
                });
                return;
            }
        }else if(!busy){
            if(ForegroundHandoff.pending()){scheduleNextTick();return;}
            busy=true;worker.execute(()->{
                long started=android.os.SystemClock.elapsedRealtime();
                long startedWall=System.currentTimeMillis();
                try{backgroundCycle();}
                catch(Exception e){
                    String reason=e.getMessage()==null?e.toString():e.getMessage();
                    // A late failure must not overwrite status or notify after
                    // the UI takes ownership, the endpoint changes, or shutdown.
                    if(ownsBackgroundCycle()){
                        prefs.edit().putLong("bg_last_poll",System.currentTimeMillis())
                            .putString("bg_error",reason).apply();
                        notifyState("Фоновый опрос AX8: "+reason);
                    }
                }finally{
                    // A queued poll may outlive stopSelf()/onDestroy(). Never
                    // keep its wake locks or tune cadence after shutdown.
                    if(stopping)releaseLocks();
                    // A poll that lost ownership must not retune the foreground cadence.
                    if(ownsBackgroundCycle() && prefs.getBoolean("refresh_auto",true)){
                        long elapsed=android.os.SystemClock.elapsedRealtime()-started;
                        int old=prefs.getInt("auto_poll_ms",500);
                        // A current successful radio sample confirms that AX8 has answered.
                        boolean ok=prefs.getLong("bg_snapshot_at",0L)>=startedWall
                            && prefs.getString("bg_error","").isEmpty();
                        int adjusted=AutoPollController.next(old,elapsed,ok);
                        if(adjusted!=old)prefs.edit().putInt("auto_poll_ms",adjusted).apply();
                    }
                    busy=false;
                    // Schedule after the in-flight request instead of waking the
                    // main thread every 0.5 s while the worker is still busy.
                    scheduleNextTick();
                }
            });
            return;
        }
        if(!busy)scheduleNextTick();
        // A busy worker is responsible for the next tick on completion.
    }};
    private void scheduleNextTick(){
        if(stopping)return;
        int requested=prefs.getBoolean("refresh_auto",true)?
            prefs.getInt("auto_poll_ms",500):prefs.getInt("refresh_ms",500);
        int delay=BackgroundTickPolicy.nextDelay(stopping,busy,prefs.getBoolean("ui_visible",true),requested);
        if(delay<0)return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick,delay);
    }

    @Override public void onCreate(){
        super.onCreate();running=true;stopping=false;
        prefs=getSharedPreferences("ax8_control",MODE_PRIVATE);
        openControllers();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"AX8 · Фоновое управление",NotificationManager.IMPORTANCE_LOW));
        NotificationChannel sms=new NotificationChannel(CHANNEL_SMS,"AX8 · SMS",NotificationManager.IMPORTANCE_HIGH);
        sms.enableVibration(true);nm.createNotificationChannel(sms);
    }
    private RouterEndpointConfig configuredEndpoint(){
        return new RouterEndpointConfig(prefs.getString("host","192.168.150.1"),
            prefs.getString("username","root"),prefs.getString("password","admin"));
    }
    private synchronized void openControllers(){
        endpoint=configuredEndpoint();
        router=new RouterClient(endpoint.host,endpoint.username,endpoint.password);
        qos=new QosController(router,prefs);traffic=new DeviceTrafficMonitor(router,prefs);
        priority=new OwnerPriorityController();watchdog=new QosWatchdog();
    }
    /** Do not start new router-side work after ownership returns to the UI. */
    private boolean ownsBackgroundCycle(){
        return !stopping && !ForegroundHandoff.pending()
            && !prefs.getBoolean("ui_visible",true)
            && endpoint.sameConnection(configuredEndpoint());
    }
    /** Invalidate data and SMS deduplication only when switching to another router. */
    static void clearCachedSnapshot(android.content.SharedPreferences preferences){
        android.content.SharedPreferences.Editor edit=preferences.edit();
        for(String key:new String[]{
            "bg_status_json","bg_snapshot_at","bg_wan","bg_devices_json","bg_devices_at","bg_clients",
            "bg_sms_json","bg_sms_at","bg_sms_cycle_at","bg_sms","bg_sms_unread",
            "bg_sms_seen_ids","bg_sms_seen_initialized","bg_blocked_json","bg_blocked_at","bg_blocked",
            "bg_history_dl","bg_history_ul","bg_fallback_dl_bits","bg_fallback_ul_bits",
            "bg_last_poll","bg_error","bg_qos","bg_priority_status"
        })edit.remove(key);
        edit.apply();
    }
    /** Runs on the single background worker, never concurrently with a router poll. */
    private void syncEndpoint(){
        RouterEndpointConfig desired=configuredEndpoint();
        if(endpoint.sameConnection(desired))return;
        String warning="";
        if(priority.enabled){
            try{qos.applyPriority(priority.owner,null,router.devices(),note->{});}
            catch(Exception e){warning="Не удалось снять временный QoS с прежнего AX8: "+
                (e.getMessage()==null?e.toString():e.getMessage());}
            finally{priority.disable();}
        }
        try{traffic.close();}
        catch(Exception e){warning+=" Не удалось остановить прежний монитор трафика: "+
            (e.getMessage()==null?e.toString():e.getMessage());}
        try{qos.close();}catch(Exception ignored){}
        boolean hostChanged=!endpoint.sameHost(desired);
        openControllers();
        if(hostChanged)clearCachedSnapshot(prefs);
        prefs.edit().putString("bg_rebind_warning",warning.trim()).apply();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        boolean stopRequested=ACTION_STOP.equals(action);
        boolean testRequested=ACTION_TEST_SMS.equals(action);
        BackgroundCommandPolicy.Decision decision=BackgroundCommandPolicy.decide(
            prefs.getBoolean("background_mode",false),stopRequested,testRequested);
        if(stopRequested){
            prefs.edit().putBoolean("background_mode",false).apply();
            stopSelf();return START_NOT_STICKY;
        }
        if(decision==BackgroundCommandPolicy.Decision.TEST_ONLY){
            // A notification test must also work when background monitoring is off.
            sendTestSms();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if(decision==BackgroundCommandPolicy.Decision.STOP){
            stopSelfResult(startId);return START_NOT_STICKY;
        }
        try {
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification("Ожидание сворачивания приложения"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION,notification("Ожидание сворачивания приложения"));
        }catch(Exception e){prefs.edit().putString("bg_error",e.toString()).putBoolean("background_mode",false).apply();stopSelf();return START_NOT_STICKY;}
        if(ACTION_BACKGROUND_NOW.equals(action)){
            handler.removeCallbacks(tick);handler.post(tick);
            return START_STICKY;
        }
        if(testRequested){
            sendTestSms();
            return START_STICKY;
        }
        handler.removeCallbacks(tick);handler.post(tick);
        return START_STICKY;
    }
    private void sendTestSms(){
        RouterClient.Sms test=new RouterClient.Sms();test.number="Тестовое SMS";
        test.content="Проверка уведомлений AX 8 PRO. Новое SMS не получено.";
        notifyNewSms(test,1);
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
    private boolean notifyNewSms(RouterClient.Sms sms,int unread){
        try{
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(!nm.areNotificationsEnabled()){
                prefs.edit().putString("bg_notification_error","Уведомления приложения запрещены в Android").apply();return false;
            }
            NotificationChannel channel=nm.getNotificationChannel(CHANNEL_SMS);
            if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE){
                prefs.edit().putString("bg_notification_error","Канал AX8 · SMS отключён в настройках Android").apply();return false;
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
            return true;
        }catch(Exception e){prefs.edit().putString("bg_notification_error",e.toString()).apply();return false;}
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
            if(d==null)continue;
            JSONObject o=new JSONObject();try{o.put("mac",d.mac);o.put("name",d.name);o.put("ip",d.ip);o.put("type",d.type);o.put("connectionTime",d.connectionTime);
                JSONArray ip6=new JSONArray();if(d.ipv6!=null)for(String x:d.ipv6)ip6.put(x);o.put("ipv6",ip6);}catch(Exception ignored){}
            arr.put(o);
        }return arr;
    }
    private static JSONArray smsJson(List<RouterClient.Sms> list){
        JSONArray arr=new JSONArray();if(list==null)return arr;for(RouterClient.Sms s:list){
            if(s==null)continue;
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
        // A queued worker can start after the service has been destroyed.
        if(stopping||ForegroundHandoff.pending())return;
        acquireLocks();
        if(stopping || prefs.getBoolean("ui_visible",true) || ForegroundHandoff.pending()){releaseLocks();return;}
        // Credentials or router address may have changed in the visible settings.
        // Never keep polling the old router with a stale authenticated session.
        syncEndpoint();
        if(!ownsBackgroundCycle()){releaseLocks();return;}
        backgroundActive=true;
        JSONObject radio=null;
        List<RouterClient.Device> devices=null;
        List<RouterClient.Sms> smsList=null;
        RouterClient.BlockState blockState=null;
        String statusIssue="",deviceIssue="",smsIssue="",qosIssue="";
        // SMS is polled first; a Telnet/QoS or Wi-Fi-client error must never suppress SMS alerts.
        try{smsList=router.sms();}catch(Exception e){smsIssue=e.getMessage()==null?e.toString():e.getMessage();}
        if(!ownsBackgroundCycle()){releaseLocks();return;}
        if(smsList!=null){
            int unread=0;
            int validSms=0;
            LinkedHashSet<String> seen=new LinkedHashSet<>();
            List<String> unreadIds=new ArrayList<>();
            for(RouterClient.Sms item:smsList){
                if(item==null)continue;
                validSms++;
                if(item.id!=null&&!item.id.isEmpty())seen.add(item.id);
                if(item.unread()){
                    unread++;
                    if(item.id!=null&&!item.id.isEmpty())unreadIds.add(item.id);
                }
            }
            // The UI may regain ownership while SMS IDs are being processed.
            // Do not publish a stale background SMS snapshot or alert afterward.
            if(!ownsBackgroundCycle()){releaseLocks();return;}
            String previous=prefs.getString("bg_sms_seen_ids","");
            boolean initialized=prefs.getBoolean("bg_sms_seen_initialized",false);
            List<String> freshIds=SmsAlertPlanner.newUnreadIds(unreadIds,previous,initialized);
            // Do not mark an unread SMS as handled before Android accepts its
            // notification. Otherwise a foreground handoff or disabled channel
            // silently loses the alert on the next scan.
            Set<String> pending=new HashSet<>(freshIds);
            Set<String> delivered=new HashSet<>();
            if(!pending.isEmpty()){
                for(int i=smsList.size()-1;i>=0&&ownsBackgroundCycle();i--){
                    RouterClient.Sms item=smsList.get(i);
                    if(item==null)continue;
                    if(pending.contains(item.id) && notifyNewSms(item,unread)){
                        pending.remove(item.id);
                        delivered.add(item.id);
                    }
                }
            }
            if(!ownsBackgroundCycle()){releaseLocks();return;}
            // Keep the baseline and already-seen IDs, but retry alerts whose
            // notification failed or was skipped during a handoff.
            seen.removeAll(freshIds);
            seen.addAll(delivered);
            String mergedIds=SmsAlertPlanner.mergeSeenIds(previous,seen);
            boolean smsPersisted=prefs.edit().putInt("bg_sms",validSms).putInt("bg_sms_unread",unread)
                .putString("bg_sms_seen_ids",mergedIds).putBoolean("bg_sms_seen_initialized",true)
                // Persist delivered SMS IDs before continuing with LTE/network work.
                // apply() can lose the deduplication record on process death.
                .putString("bg_sms_json",smsJson(smsList).toString()).putLong("bg_sms_at",System.currentTimeMillis()).commit();
            if(!smsPersisted){
                // A failed durable write must not be silently treated as success.
                // Stop this cycle rather than publishing a misleading SMS snapshot.
                prefs.edit().putString("bg_sms_persistence_error","Не удалось сохранить состояние SMS").apply();
                releaseLocks();
                return;
            }
            prefs.edit().remove("bg_sms_persistence_error").apply();
        }
        long radioAt=System.currentTimeMillis();
        try{radio=router.status();}catch(Exception e){statusIssue=e.getMessage()==null?e.toString():e.getMessage();}
        // A slow LTE request can finish after the UI takes ownership or the
        // router endpoint changes. Do not start more requests in that case.
        if(!ownsBackgroundCycle()){releaseLocks();return;}
        long devicesAt=System.currentTimeMillis();
        try{devices=router.devices();}catch(Exception e){deviceIssue=e.getMessage()==null?e.toString():e.getMessage();}
        if(!ownsBackgroundCycle()){releaseLocks();return;}
        long blockedAt=System.currentTimeMillis();
        try{blockState=router.blockState();}catch(Exception ignored){}
        // No QoS write may target an endpoint whose settings changed mid-poll.
        if(!ownsBackgroundCycle()){releaseLocks();return;}
        if(devices!=null){
            if(!ownsBackgroundCycle()){releaseLocks();return;}
            try{qos.resyncAddresses(devices,note->{});}catch(Exception e){qosIssue="QoS: "+e.getMessage();}
            if(!ownsBackgroundCycle()){releaseLocks();return;}
            try{
                if(prefs.getBoolean("traffic_monitor",false)){
                    if(!ownsBackgroundCycle()){releaseLocks();return;}
                    traffic.sample(devices);
                    if(!ownsBackgroundCycle()){releaseLocks();return;}
                    if(!traffic.error().isEmpty())qosIssue="Монитор: "+traffic.error();
                    boolean prioritize=prefs.getBoolean("background_priority",false)&&traffic.error().isEmpty();
                    String owner=prefs.getString("owner_mac","");
                    if(prioritize&&!owner.isEmpty()){
                        if(!priority.enabled||!owner.equals(priority.owner))priority.enable(owner);
                        if(!ownsBackgroundCycle()){releaseLocks();return;}
                        priority.update(devices,traffic,qos);
                    }else if(priority.enabled&&ownsBackgroundCycle()){qos.applyPriority(owner,null,devices,msg->{});priority.disable();}
                    if(!ownsBackgroundCycle()){releaseLocks();return;}
                    if(traffic.error().isEmpty()&&prefs.getBoolean("background_qos_watch",true))
                        watchdog.check(devices,traffic,qos);
                }else if(priority.enabled&&ownsBackgroundCycle()){qos.applyPriority("",null,devices,msg->{});priority.disable();}
            }catch(Exception e){qosIssue="QoS: "+(e.getMessage()==null?e.toString():e.getMessage());}
        }
        // Never publish a completed snapshot from a poll whose service was stopped.
        if(!ownsBackgroundCycle()){releaseLocks();return;}
        long now=System.currentTimeMillis();
        android.content.SharedPreferences.Editor editor=prefs.edit().putLong("bg_last_poll",now);
        if(radio!=null){
            editor.putString("bg_status_json",radio.toString()).putLong("bg_snapshot_at",radioAt)
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
        if(devices!=null){int validClients=0;for(RouterClient.Device device:devices)if(device!=null)validClients++;editor.putInt("bg_clients",validClients)
            .putString("bg_devices_json",devicesJson(devices).toString())
            .putLong("bg_devices_at",devicesAt);}
        if(blockState!=null){editor.putInt("bg_blocked",blockState.black.size())
            .putString("bg_blocked_json",blockedJson(blockState).toString())
            .putLong("bg_blocked_at",blockedAt);}
        // Link SMS to this completed cycle, not merely to a previous successful read.
        if(smsList!=null)editor.putLong("bg_sms_cycle_at",now);
        String rebindWarning=prefs.getString("bg_rebind_warning","");
        String issues=(!rebindWarning.isEmpty()?"Подключение: "+rebindWarning+"; ":"")+
            (!statusIssue.isEmpty()?"LTE: "+statusIssue+"; ":"")+
            (!smsIssue.isEmpty()?"SMS: "+smsIssue+"; ":"")+
            (!deviceIssue.isEmpty()?"Клиенты: "+deviceIssue+"; ":"")+qosIssue;
        // Building a snapshot can take time (JSON/history serialization). Recheck
        // ownership immediately before publishing it to shared preferences.
        if(!ownsBackgroundCycle()){releaseLocks();return;}
        editor.putString("bg_error",issues).putString("bg_qos",qosIssue.isEmpty()?watchdog.status():qosIssue)
            .putString("bg_priority_status",priority.mode).apply();
        // The UI may become visible after the snapshot was assembled.
        // Do not overwrite its foreground notification with a stale background result.
        if(!ownsBackgroundCycle())return;
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
        // Shutdown can race with the queued foreground handoff cleanup.
        // Never recreate the persistent notification after stopSelf()/onDestroy().
        if(!stopping)notifyState("Приложение открыто · мониторинг через интерфейс");
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