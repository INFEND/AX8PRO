package ru.xarkon.ax8;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.provider.ContactsContract;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.InputType;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Full native, touch-first front-end for OLAX AX8 Pro. No external UI libraries. */
public final class MainActivity extends Activity {
    private static final String[] TABS={"Обзор","Клиенты","SMS","Сеть","Настройки"};
    private static final String[] SYMBOL={"⌂","◫","✉","⌁","⚙"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Float> historyDl=new ArrayDeque<>();
    private final ArrayDeque<Float> historyUl=new ArrayDeque<>();
    private RouterClient api;
    private QosController qos;
    private DeviceTrafficMonitor traffic;
    private final OwnerPriorityController priority=new OwnerPriorityController();
    private final QosWatchdog uiQosWatch=new QosWatchdog();
    private final BandSwitchTracker bandTracker=new BandSwitchTracker();
    private boolean monitorOn=false;
    private String ownerMac="";
    private final java.util.Set<String> expandedSettings=new java.util.LinkedHashSet<>();
    private LinearLayout settingsTarget=null;
    private long lastCompleteAt=0,lastDrawAt=0;
    private int autoPollMs=500;
    private String trafficError="";

    private android.content.SharedPreferences prefs;
    private LinearLayout root,content,bar,nav,header;
    private ScrollView scroll;
    private TextView connectionLabel,workLabel;
    private JSONObject status;
    private final Map<String,TextView> overviewValues=new HashMap<>();
    private final Map<String,TextView> overviewSignalValues=new HashMap<>();
    private final Map<String,TextView> clientDlValues=new HashMap<>();
    private final Map<String,TextView> clientUlValues=new HashMap<>();
    private final Map<String,TextView> clientLimitValues=new HashMap<>();
    private TextView qosStatusLive;
    private static final int PICK_PHONE_REQUEST=801;
    private EditText smsRecipientInput,smsMessageInput;
    private Dialog smsComposeDialog;
    private final Map<String,TextView> networkValues=new HashMap<>();
    private final Map<String,TextView> settingsValues=new HashMap<>();
    private String clientsSignature="",smsSignature="",networkSignature="";
    private Ui.SignalGauge overviewGauge;
    private Ui.SpeedGraph overviewGraph;
    private String sectionError="";
    private List<RouterClient.Device> devices=new ArrayList<>();
    private List<RouterClient.Sms> messages=new ArrayList<>();
    private RouterClient.BlockState blocked;
    private volatile boolean actionBusy=false,refreshBusy=false;
    private volatile boolean running=false;
    private int tab=0,lastNavTab=-1,lastNavUnread=-1;
    private String error="",notice="";
    private long statusAt=0,clientsAt=0,smsAt=0,lastBytesAt=0;
    private double lastRx=Double.NaN,lastTx=Double.NaN,fallbackDl=Double.NaN,fallbackUl=Double.NaN;
    private final Runnable ticker=new Runnable(){@Override public void run(){
        if(!running)return;
        refresh(false);
        // Never hammer a router WebUI while it is still booting.
        handler.postDelayed(this,bandTracker.isPending()?Math.max(1500,refreshInterval()):refreshInterval());
    }};
    private int d(float x){return Ui.dp(this,x);}

    @Override public void onCreate(Bundle state){super.onCreate(state);
        prefs=getSharedPreferences("ax8_control",MODE_PRIVATE);
        autoPollMs=AutoPollController.clamp(prefs.getInt("auto_poll_ms",500));
        api=new RouterClient(prefs.getString("host","192.168.150.1"),prefs.getString("username","root"),prefs.getString("password","admin"));
        qos=new QosController(api,prefs);traffic=new DeviceTrafficMonitor(api,prefs);
        ownerMac=prefs.getString("owner_mac","");monitorOn=prefs.getBoolean("traffic_monitor",false);
        bandTracker.restore(prefs.getInt("pending_band",BandSwitchTracker.NONE),
            prefs.getLong("pending_band_since",0L),System.currentTimeMillis());
        if(!bandTracker.isPending() && prefs.contains("pending_band"))clearPendingBand();
        restoreRecentRouterSnapshot();
        Ui.load(prefs);applySystemBarTheme();requestNotificationPermission();createShell();showTab(0);UpdateSchedule.configure(this);
    }
    @Override protected void onResume(){
        super.onResume();
        prefs.edit().putBoolean("ui_visible",true).apply();
        if(prefs.getBoolean("background_mode",false)&&!RouterBackgroundService.isRunning()){
            try{Intent resume=new Intent(this,RouterBackgroundService.class).setAction(RouterBackgroundService.ACTION_START);
                if(Build.VERSION.SDK_INT>=26)startForegroundService(resume);else startService(resume);
            }catch(Exception e){prefs.edit().putString("bg_error",e.toString()).apply();}
        }
        loadBackgroundSnapshot();
        if(getIntent()!=null&&getIntent().getIntExtra("open_tab",-1)==2){
            getIntent().removeExtra("open_tab");showTab(2);
        }
        // Background service owns priority only when the UI is not visible.
        if(prefs.getBoolean("background_mode",false)&&prefs.getBoolean("background_priority",false)
                &&monitorOn&&!ownerMac.isEmpty()&&!priority.enabled)priority.enable(ownerMac);
        autoPollMs=AutoPollController.clamp(prefs.getInt("auto_poll_ms",500));
        running=true;handler.removeCallbacks(ticker);refresh(true);handler.postDelayed(ticker,refreshInterval());
        if(!startupOtaChecked){
            startupOtaChecked=true;
            if(prefs.getBoolean("ota_on_launch",true))
                handler.postDelayed(()->{if(!isFinishing()&&!isDestroyed())checkOta(true);},700);
        }
    }
    @Override protected void onPause(){
        running=false;handler.removeCallbacks(ticker);
        boolean persistent=prefs.getBoolean("background_mode",false)&&RouterBackgroundService.isRunning();
        // Update foreground state NOW, not after Telnet cleanup. Otherwise the background
        // service may wait several cycles before checking SMS.
        android.content.SharedPreferences.Editor bg=prefs.edit()
            .putBoolean("background_priority",persistent&&priority.enabled)
            .putBoolean("ui_visible",false);
        if(smsAt>0){
            java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();
            for(RouterClient.Sms x:messages)if(x.id!=null&&!x.id.isEmpty())ids.add(x.id);
            bg.putString("bg_sms_seen_ids",SmsAlertPlanner.mergeSeenIds(
                prefs.getString("bg_sms_seen_ids",""),ids))
                .putBoolean("bg_sms_seen_initialized",true)
                .putInt("bg_sms_unread",(int)messages.stream().filter(RouterClient.Sms::unread).count());
        }
        bg.apply();
        if(persistent)try{
            startService(new Intent(this,RouterBackgroundService.class)
                .setAction(RouterBackgroundService.ACTION_BACKGROUND_NOW));
        }catch(Exception e){prefs.edit().putString("bg_error","Запуск фонового опроса: "+e).apply();}
        // Release UI-owned tc/iptables chains before background QoS takes ownership.
        workers.execute(()->{
            try{if(qos.priorityActive())qos.applyPriority(ownerMac,null,new ArrayList<>(devices),m->{});}catch(Exception ignored){}
            try{traffic.close();}catch(Exception ignored){}
            priority.disable();
        });
        super.onPause();
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(intent!=null&&intent.getIntExtra("open_tab",-1)==2){
            intent.removeExtra("open_tab");showTab(2);
        }
    }
    @Override protected void onDestroy(){workers.shutdown();super.onDestroy();}
    private void applySystemBarTheme(){
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        int flags=Ui.isLight()?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }
    private void applyAppearance(){
        int keep=tab;
        int keepY=(scroll==null)?0:scroll.getScrollY();
        Ui.load(prefs);applySystemBarTheme();
        createShell();tab=keep;rebuildNav();draw(true);
        scroll.post(()->scroll.scrollTo(0,Math.max(0,keepY)));
    }
    private boolean automaticPolling(){return prefs.getBoolean("refresh_auto",true);}
    private int refreshInterval(){
        if(automaticPolling())return autoPollMs;
        int ms=prefs.getInt("refresh_ms",500);
        return AutoPollController.clamp(ms);
    }
    private void observePoll(long elapsedMs,boolean success){
        if(!automaticPolling())return;
        int previous=autoPollMs;
        autoPollMs=AutoPollController.next(previous,elapsedMs,success);
        if(previous!=autoPollMs)prefs.edit().putInt("auto_poll_ms",autoPollMs).apply();
    }
    private void createShell(){
        root=Ui.vertical(this);root.setBackgroundColor(Ui.BG);
        // Keep contents away from gesture navigation and status icons on new Android releases.
        if(Build.VERSION.SDK_INT>=30){root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets x=insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0,x.top,0,x.bottom);return insets;
        });}
        else root.setFitsSystemWindows(true);
        setContentView(root);
        header=Ui.horizontal(this);header.setPadding(d(19),d(13),d(19),d(12));
        LinearLayout titles=Ui.vertical(this);
        TextView brand=Ui.text(this,"AX 8 PRO",20,Ui.TEXT,true);titles.addView(brand);
        Ui.addSpace(titles,this,4);
        TextView sub=Ui.text(this,"CONTROL CENTER",10,Ui.CYAN,true);sub.setLetterSpacing(.23f);titles.addView(sub);
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        connectionLabel=Ui.chip(this,"●  ОФЛАЙН",Ui.RED);
        connectionLabel.setOnClickListener(v->{
            if(bandTracker.isPending()){
                confirm("Прекратить проверку Band",
                    "Прекратить ожидание подтверждения? Это отменит только проверку в приложении. " +
                    "Роутер продолжит запуск с уже записанной настройкой.",()->{
                        clearPendingBand();error="";updateConnection();draw(true);refresh(true);
                    });
            }else refresh(true);
        });
        header.addView(connectionLabel);
        root.addView(header);
        workLabel=Ui.text(this,"",12,Ui.CYAN,true);workLabel.setGravity(Gravity.CENTER_VERTICAL);
        workLabel.setPadding(d(18),0,d(18),0);workLabel.setVisibility(View.GONE);
        root.addView(workLabel,Ui.lp(this,-1,0));
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        content=Ui.vertical(this);content.setPadding(d(16),d(6),d(16),d(20));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=Ui.horizontal(this);nav.setPadding(d(9),d(7),d(9),d(10));nav.setBackground(Ui.shape(this,Ui.CARD,0,21));
        LinearLayout.LayoutParams n=Ui.lp(this,-1,-2);n.setMargins(d(11),0,d(11),d(8));root.addView(nav,n);
        rebuildNav();
    }
    private void rebuildNav(){
        if(nav==null)return;
        int unread=unreadBadgeCount();
        // Preserve the navigation hierarchy until a selection or badge count actually changes.
        if(nav.getChildCount()==TABS.length&&lastNavTab==tab&&lastNavUnread==unread)return;
        lastNavTab=tab;lastNavUnread=unread;nav.removeAllViews();
        for(int i=0;i<TABS.length;i++){
            final int index=i;int badge=index==2?unread:0;
            nav.addView(Ui.navItem(this,SYMBOL[i],TABS[i],tab==i,badge,()->showTab(index)),new LinearLayout.LayoutParams(0,-2,1));
        }
    }
    private int unreadBadgeCount(){
        if(smsAt>0){
            int local=0;for(RouterClient.Sms sms:messages)if(sms.unread())local++;
            return local;
        }
        return Math.max(0,prefs.getInt("bg_sms_unread",0));
    }
    private void showTab(int index){
        if(index==tab)return;
        tab=index;rebuildNav();draw(false);scroll.scrollTo(0,0);
        if(Ui.MOTION){content.setAlpha(0);content.setTranslationY(d(14));
            content.animate().alpha(1).translationY(0).setDuration(230).start();}
        else{content.setAlpha(1);content.setTranslationY(0);}
        if(index==1||index==2||index==3)refresh(true);
    }
    private void displayWork(String message){handler.post(()->{
        if(message==null||message.isEmpty()){workLabel.setVisibility(View.GONE);workLabel.getLayoutParams().height=0;}
        else{workLabel.setText("◌  "+message);workLabel.setBackgroundColor(Ui.CARD2);workLabel.getLayoutParams().height=d(37);workLabel.setVisibility(View.VISIBLE);}
        workLabel.requestLayout();
    });}
    private void alert(String title,String text){Ui.modal(this,title,text,null,"Понятно",null);}
    private void confirm(String title,String text,Runnable yes){Ui.modal(this,title,text,null,"Подтвердить",yes);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void requestNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},902);
        }
    }
    private void restoreRecentRouterSnapshot(){
        long ts=prefs.getLong("last_router_status_at",0L);
        // A previous session's LTE data is only a short-lived preview, not a live reading.
        // Reject future timestamps too (the system clock may have been corrected).
        if(!BackgroundSummary.isFresh(ts,System.currentTimeMillis(),15000L))return;
        String saved=prefs.getString("last_router_status","");
        if(saved.isEmpty())return;
        try{status=new JSONObject(saved);statusAt=ts;}catch(Exception ignored){}
    }
    private void loadBackgroundSnapshot(){
        long now=System.currentTimeMillis();
        long ts=prefs.getLong("bg_snapshot_at",0L);
        long limit=Math.max(15000L,refreshInterval()*3L);
        if(!BackgroundSummary.isFresh(ts,now,limit)||ts<lastCompleteAt){
            long smsTime=prefs.getLong("bg_sms_at",0L);
            if(smsTime>smsAt && now-smsTime<=limit){
                try{messages=parseSms(new JSONArray(prefs.getString("bg_sms_json","[]")));smsAt=smsTime;}catch(Exception ignored){}
            }
            // Never label a cached router status as ONLINE if the sample is stale.
            if(!BackgroundSummary.isFresh(statusAt,now,limit)){status=null;statusAt=0;}error="";
            updateConnection();draw(true);return;
        }
        try{
            String rawStatus=prefs.getString("bg_status_json","");
            if(rawStatus!=null&&!rawStatus.trim().isEmpty())status=new JSONObject(rawStatus);
            // Do not give stale clients, SMS or ACL data a newer LTE timestamp.
            long deviceAt=prefs.getLong("bg_devices_at",0L);
            if(deviceAt==ts && BackgroundSummary.isFresh(deviceAt,now,limit)){
                String rawDevices=prefs.getString("bg_devices_json","");
                devices=rawDevices==null||rawDevices.trim().isEmpty()
                    ?new ArrayList<>():parseDevices(new JSONArray(rawDevices));
                clientsAt=deviceAt;
            }else{devices=new ArrayList<>();clientsAt=0;}
            long smsTime=prefs.getLong("bg_sms_at",0L);
            if(prefs.getLong("bg_sms_cycle_at",0L)==ts &&
                    BackgroundSummary.isFresh(smsTime,now,limit)){
                String rawSms=prefs.getString("bg_sms_json","");
                messages=rawSms==null||rawSms.trim().isEmpty()
                    ?new ArrayList<>():parseSms(new JSONArray(rawSms));
                smsAt=smsTime;
            }else{messages=new ArrayList<>();smsAt=0;}
            long blockedAt=prefs.getLong("bg_blocked_at",0L);
            if(blockedAt==ts && BackgroundSummary.isFresh(blockedAt,now,limit)){
                String rawBlocked=prefs.getString("bg_blocked_json","");
                blocked=rawBlocked==null||rawBlocked.trim().isEmpty()
                    ?null:parseBlocked(new JSONObject(rawBlocked));
            }else blocked=null;
            fallbackDl=Double.longBitsToDouble(prefs.getLong("bg_fallback_dl_bits",Double.doubleToRawLongBits(fallbackDl)));
            fallbackUl=Double.longBitsToDouble(prefs.getLong("bg_fallback_ul_bits",Double.doubleToRawLongBits(fallbackUl)));
            String rawDl=prefs.getString("bg_history_dl","");if(rawDl!=null&&!rawDl.isEmpty())restoreHistory(rawDl,historyDl);
            String rawUl=prefs.getString("bg_history_ul","");if(rawUl!=null&&!rawUl.isEmpty())restoreHistory(rawUl,historyUl);
            statusAt=lastCompleteAt=ts;
            error="";sectionError="";
            updateConnection();rebuildNav();draw(true);
        }catch(Exception ignored){}
    }
    private static void restoreHistory(String raw,ArrayDeque<Float> target){
        target.clear();for(String part:raw.split(",")){
            if(part.trim().isEmpty())continue;try{target.add(Float.parseFloat(part.trim()));}catch(Exception ignored){}
        }while(target.size()<28)target.addFirst(0f);while(target.size()>28)target.removeFirst();
    }
    private static List<RouterClient.Device> parseDevices(JSONArray rows){
        ArrayList<RouterClient.Device> out=new ArrayList<>();
        for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o==null)continue;
            RouterClient.Device d=new RouterClient.Device();d.mac=o.optString("mac","");d.name=o.optString("name","Без имени");
            d.ip=o.optString("ip","");d.type=o.optString("type","?");d.connectionTime=o.optString("connectionTime","");
            JSONArray a=o.optJSONArray("ipv6");if(a!=null)for(int j=0;j<a.length();j++)d.ipv6.add(a.optString(j));out.add(d);
        }return out;
    }
    private static List<RouterClient.Sms> parseSms(JSONArray rows){
        ArrayList<RouterClient.Sms> out=new ArrayList<>();
        for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o==null)continue;
            RouterClient.Sms x=new RouterClient.Sms();x.id=o.optString("id","");x.number=o.optString("number","");
            x.date=o.optString("date","");x.content=o.optString("content","");x.tag=o.optString("tag","");out.add(x);
        }return out;
    }
    private static RouterClient.BlockState parseBlocked(JSONObject o){
        RouterClient.BlockState b=new RouterClient.BlockState();b.backend=o.optString("backend","");b.mode=o.optString("mode","0");
        b.white=o.optString("white","");b.whiteNames=o.optString("whiteNames","");b.userMac=o.optString("userMac","");
        JSONArray list=o.optJSONArray("black");if(list!=null)for(int i=0;i<list.length();i++)b.black.add(list.optString(i));return b;
    }
    private void action(String title,Work operation){action(title,operation,BandSwitchTracker.NONE);}
    private void action(String title,Work operation,int requestedBand){
        if(actionBusy){toast("Дождитесь завершения предыдущего действия");return;}
        boolean bandAction=requestedBand!=BandSwitchTracker.NONE;
        if(bandAction && bandTracker.isPending()){
            toast("Сначала дождитесь проверки предыдущей смены Band");return;
        }
        if(bandAction){error="";notice="";}
        actionBusy=true;notice="";displayWork(title);
        workers.execute(()->{
            String result=null,failed=null;
            try{operation.run(message->displayWork(message));result=title.equals("Отправка SMS")?
                "Роутер принял запрос отправки. Доставка получателю не подтверждена.":title+": выполнено";}
            catch(Exception e){failed=e.getMessage()==null?e.toString():e.getMessage();}
            // Read the actual ACL after every relevant operation, even on failure.
            // A blocked client disappears from the online list, so its presence there
            // cannot be used to determine whether the blacklist changed.
            boolean aclAction=title.equals("Список доступа")||title.equals("Разблокировка")||
                title.equals("Очистка DENY")||title.equals("Отключение остальных")||
                title.equals("Временное отключение");
            RouterClient.BlockState actual=null;
            if(aclAction)try{actual=api.blockState();}catch(Exception ignored){}
            final RouterClient.BlockState currentAcl=actual;
            final String ok=result,fail=failed;
            handler.post(()->{
                actionBusy=false;displayWork("");
                if(currentAcl!=null)blocked=currentAcl;
                if(bandAction && fail==null){
                    // A successful WebUI POST is not proof of a new active Band.
                    long now=System.currentTimeMillis();
                    bandTracker.begin(requestedBand,now);
                    prefs.edit().putInt("pending_band",requestedBand).putLong("pending_band_since",now).apply();
                    status=null;statusAt=0;error="";api.invalidate();
                    toast("Запрос принят. Проверяю Band после запуска AX8…");
                }else if(fail!=null){
                    alert("Ошибка выполнения",fail);
                }else toast(ok);
                clientsAt=0;smsAt=0;statusAt=0;
                updateConnection();draw(true);refresh(true);
            });
        });
    }
    private interface Work{void run(RouterClient.Progress p)throws Exception;}
    private void refresh(boolean forced){
        if(refreshBusy||actionBusy||!running)return;
        long now=System.currentTimeMillis();
        // One global interval for all WebUI parameters. Never overlap cycles.
        boolean wantStatus=forced||now-statusAt>=refreshInterval();
        boolean wantClients=wantStatus;
        boolean wantBlocked=wantStatus;
        boolean wantSms=wantStatus;
        if(!wantStatus&&!wantClients&&!wantBlocked&&!wantSms)return;
        refreshBusy=true;
        final long cycleStart=android.os.SystemClock.elapsedRealtime();
        workers.execute(()->{
            JSONObject next=null;List<RouterClient.Device> d=null;List<RouterClient.Sms> m=null;RouterClient.BlockState b=null;
            String failure=null,partialFailure=null;
            if(wantStatus)try{
                next=api.status();
                if(next!=null && forced && !bandTracker.isPending()){
                    final JSONObject early=next;
                    handler.post(()->{
                        if(!running||bandTracker.isPending())return;
                        status=early;statusAt=System.currentTimeMillis();error="";
                        updateConnection();
                        if(tab==0)draw(true);
                    });
                }
            }catch(Exception e){failure=e.getMessage()==null?e.toString():e.getMessage();}
            // After a requested Band reboot only status is needed. Other WebUI calls
            // would multiply failed login attempts and delay recovery by many seconds.
            if(!bandTracker.isPending()){
                if(wantClients)try{d=api.devices();}catch(Exception e){partialFailure="Устройства: "+e.getMessage();}
                if(wantBlocked)try{b=api.blockState();}catch(Exception e){if(tab==1)partialFailure="Список блокировок: "+e.getMessage();}
                if(wantSms)try{m=api.sms();}catch(Exception e){partialFailure="SMS: "+e.getMessage();}
            }
            // Optional Telnet counters: the entire snapshot is still governed by the same
            // refresh interval, with skipped ticks if AX8 has not yet answered.
            String qosIssue="";
            if(d!=null){try{qos.resyncAddresses(d,message->{});}catch(Exception e){qosIssue="QoS: "+e.getMessage();}}
            if(monitorOn){
                try{traffic.sample(d!=null?d:new ArrayList<>(devices));trafficError=qosIssue;
                    if(traffic.error().isEmpty()){
                        if(priority.enabled)priority.update(d!=null?d:new ArrayList<>(devices),traffic,qos);
                        if(prefs.getBoolean("background_qos_watch",true))uiQosWatch.check(d!=null?d:new ArrayList<>(devices),traffic,qos);
                    }
                }catch(Exception e){trafficError=e.getMessage()==null?e.toString():e.getMessage();}
            }
            final String limitedError=partialFailure;
            final JSONObject status2=next;final List<RouterClient.Device> devices2=d;final List<RouterClient.Sms> sms2=m;
            final RouterClient.BlockState blacklist=b;final String fail=failure;
            handler.post(()->{
                refreshBusy=false;
                observePoll(android.os.SystemClock.elapsedRealtime()-cycleStart,
                    status2!=null && sms2!=null && devices2!=null);
                if(status2!=null){
                    double rx=num(status2,"realtime_rx_bytes"),tx=num(status2,"realtime_tx_bytes");
                    long sampleTime=System.currentTimeMillis();double sec=(sampleTime-lastBytesAt)/1000.0;
                    if(lastBytesAt>0&&sec>.3&&rx>=lastRx&&tx>=lastTx){fallbackDl=(rx-lastRx)/sec;fallbackUl=(tx-lastTx)/sec;}
                    lastRx=rx;lastTx=tx;lastBytesAt=sampleTime;
                    status=status2;statusAt=sampleTime;
                    prefs.edit().putString("last_router_status",status2.toString())
                        .putLong("last_router_status_at",sampleTime).apply();
                    float liveDl=(float)Math.max(0,rateValue(status2,"realtime_rx_thrpt",fallbackDl));
                    float liveUl=(float)Math.max(0,rateValue(status2,"realtime_tx_thrpt",fallbackUl));
                    historyDl.add(liveDl);historyUl.add(liveUl);
                    while(historyDl.size()>28)historyDl.removeFirst();
                    while(historyUl.size()>28)historyUl.removeFirst();
                }
                if(devices2!=null){
                    devices=devices2;clientsAt=System.currentTimeMillis();
                    android.content.SharedPreferences.Editor cache=null;
                    for(RouterClient.Device device:devices2){
                        String prev=prefs.getString("cached_client_name_"+device.mac,"");
                        if(device.name!=null&&!device.name.trim().isEmpty()&&!device.name.equals(prev)){
                            if(cache==null)cache=prefs.edit();
                            cache.putString("cached_client_name_"+device.mac,device.name);
                        }
                    }
                    if(cache!=null)cache.apply();
                }
                if(sms2!=null){messages=sms2;smsAt=System.currentTimeMillis();}
                if(blacklist!=null)blocked=blacklist;
                autoSelectOwner(false);
                lastCompleteAt=System.currentTimeMillis();
                rebuildNav();
                if(bandTracker.isPending()){
                    String result;
                    if(status2!=null){
                        result=bandTracker.observe(status2.optString("nv_band",""),
                            status2.optString("ppp_status",""),System.currentTimeMillis());
                    }else{
                        bandTracker.recordUnavailable();
                        result=bandTracker.checkTimeout(System.currentTimeMillis());
                    }
                    if(BandSwitchTracker.CONFIRMED.equals(result)){
                        clearPendingBand();
                        toast("LTE Band подтверждён: "+band());
                    }else if(BandSwitchTracker.AUTO_UNVERIFIED.equals(result)){
                        clearPendingBand();
                        toast("LTE восстановлена. Маска AUTO не подтверждена WebUI");
                    }else if(BandSwitchTracker.TIMED_OUT.equals(result)){
                        clearPendingBand();
                        alert("Band не подтверждён",
                            "В течение 180 секунд не получены два устойчивых подтверждения нового Band и LTE. " +
                            "Текущий: "+band()+". Роутер может оставаться доступным с прежней настройкой.");
                    }
                }
                if(fail!=null){
                    // Loss of WebUI during a known reboot is expected. Do not show a
                    // misleading login failure while the band verification is pending.
                    if(!bandTracker.isPending() && (statusAt==0||System.currentTimeMillis()-statusAt>30000)){
                        error=fail.contains("Failed to connect")||fail.contains("Connection refused")||fail.contains("connect timed out")?
                            "WebUI AX8 временно недоступен. Проверьте подключение к Wi-Fi роутера.\n"+fail:fail;
                    }
                }else if(status2!=null)error="";
                if(limitedError!=null)sectionError=limitedError;
                else if(devices2!=null||sms2!=null||blacklist!=null)sectionError="";
                updateConnection();
                if(!actionBusy){
                    // Every value is polled on the selected interval, but View hierarchies
                    // survive a poll: focus, touch interactions, scroll and gauge are retained.
                    if(!error.isEmpty() && overviewGauge==null && tab==0){draw(true);}
                    else if(tab==0 && overviewGauge!=null && error.isEmpty() && notice.isEmpty())updateOverviewLive(status2!=null);
                    else if(tab==1)updateClientsLive();
                    else if(tab==2)updateSmsLive();
                    else if(tab==3)updateNetworkLive();
                    else if(tab==4)updateSettingsLive();
                    else draw(true);
                }
            });
        });
    }
    private void clearPendingBand(){
        bandTracker.clear();
        prefs.edit().remove("pending_band").remove("pending_band_since").apply();
    }
    private void updateConnection(){
        boolean fresh=status!=null&&statusAt>0&&System.currentTimeMillis()-statusAt<30000;
        boolean online=fresh&&error.isEmpty();
        String label;
        if(bandTracker.isPending())label="◌  ПРОВЕРКА "+(bandTracker.requestedBand()==0?"AUTO":"B"+bandTracker.requestedBand());
        else label=online?"●  ONLINE":(error.isEmpty()?"◌  ОБНОВЛЕНИЕ":"●  ОФЛАЙН");
        connectionLabel.setText(label);
        connectionLabel.setTextColor(bandTracker.isPending()?Ui.ORANGE:
            (online?Ui.GREEN:(statusAt==0&&error.isEmpty()?Ui.ORANGE:Ui.RED)));
    }
    private void draw(boolean preserveScroll){
        int y=preserveScroll?scroll.getScrollY():0;
        rebuildNav();
        content.removeAllViews();
        if(!error.isEmpty()) {
            LinearLayout err=Ui.card(this);err.setBackground(Ui.shape(this,0xFF301B2C,0xFF77405B,16));
            err.addView(Ui.text(this,"ПРОБЛЕМА С ПОДКЛЮЧЕНИЕМ",12,Ui.RED,true));Ui.addSpace(err,this,8);
            err.addView(Ui.text(this,error,12,Ui.TEXT,false));Ui.addSpace(err,this,11);
            err.addView(Ui.button(this,"↻ Повторить",Ui.CARD2,Ui.TEXT,()->refresh(true)));addCard(err);
        } else if(!notice.isEmpty()){
            LinearLayout n=Ui.card(this);n.addView(Ui.text(this,"✓ "+notice,12,Ui.GREEN,true));addCard(n);
        }
        if(error.isEmpty() && !sectionError.isEmpty() && (tab==1||tab==2)){
            LinearLayout n=Ui.card(this);n.addView(Ui.text(this,sectionError,12,Ui.ORANGE,false));addCard(n);
        }
        if(tab==0)overview();else if(tab==1)clients();else if(tab==2)sms();else if(tab==3)network();else settings();
        if(preserveScroll){final int expectedTab=tab;scroll.post(()->{
            if(tab==expectedTab)scroll.scrollTo(0,Math.min(y,Math.max(0,content.getHeight()-scroll.getHeight())));
        });}
    }
    private void addCard(LinearLayout v){LinearLayout.LayoutParams p=Ui.lp(this,-1,-2);p.bottomMargin=d(13);(settingsTarget!=null?settingsTarget:content).addView(v,p);}
    private void addTitle(String s,String sub){LinearLayout v=Ui.vertical(this);v.setPadding(d(4),d(8),d(3),d(18));
        Ui.title(v,this,s,sub);content.addView(v);
    }
    private void rowCard(String heading,String detail,String badge,int accent,Runnable click){
        LinearLayout card=Ui.card(this);LinearLayout head=Ui.horizontal(this);
        LinearLayout left=Ui.vertical(this);left.addView(Ui.text(this,heading,15,Ui.TEXT,true));Ui.addSpace(left,this,5);
        left.addView(Ui.text(this,detail,12,Ui.MUTED,false));head.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        if(badge!=null){TextView b=Ui.chip(this,badge,accent);head.addView(b);}
        card.addView(head);if(click!=null){card.setOnClickListener(v->click.run());Ui.animateTouch(card);}
        addCard(card);
    }
    private static double parse(Object raw){try{return Double.parseDouble(String.valueOf(raw).replace("%","").trim());}catch(Exception e){return Double.NaN;}}
    private static double num(JSONObject o,String key){return o==null?Double.NaN:parse(o.optString(key,""));}
    private String s(String key){return status==null?"":status.optString(key,"");}
    private String band(){String b=stat("nv_band");return b.equals("—")?"—":b.toUpperCase(Locale.US).startsWith("B")?b:"B"+b;}
    private String stat(String key){String raw=s(key).trim();return raw.isEmpty()||raw.equals("?")||raw.equals("--")||raw.equalsIgnoreCase("null")?"—":raw;}
    private double rsrpValue(){double v=num(status,"nv_rsrp");return v>0?v-141:v;}
    private double rsrqValue(){double v=num(status,"nv_rsrq");return v>0?(v-40)/2:v;}
    private double sinrValue(){return num(status,"nv_sinr");}
    private String rsrp(){return fmt(rsrpValue());}
    private String rsrq(){return fmt(rsrqValue());}
    private String sinr(){return fmt(sinrValue());}
    private static String fmt(double x){return Double.isNaN(x)?"—":Math.abs(x-Math.rint(x))<.05?String.format(Locale.US,"%.0f",x):String.format(Locale.US,"%.1f",x);}
    private static float clamp01(double x){return (float)Math.max(0,Math.min(1,x));}
    private float rsrpScore(){double x=rsrpValue();return Double.isNaN(x)?Float.NaN:clamp01((x+120d)/35d);} // ~-120..-85 dBm
    private float rsrqScore(){double x=rsrqValue();return Double.isNaN(x)?Float.NaN:clamp01((x+20d)/17d);} // ~-20..-3 dB
    private float sinrScore(){double x=sinrValue();return Double.isNaN(x)?Float.NaN:clamp01((x+5d)/25d);} // ~-5..20 dB
    /** Separate RSSI signal strength. Read the actual modem value where available;
     * fallback values from rssi() carry the ≈ marker when estimated. */
    private float signalLevelScore(){
        String raw=rssi();
        if(raw.equals("—"))return Float.NaN;
        double dbm=parse(raw.replace("≈","").replace("dBm","").trim());
        return Double.isNaN(dbm)?Float.NaN:clamp01((dbm+110d)/45d); // ~-110..-65 dBm
    }
    private static final int SIGNAL_BAD=0xFFFF5A67, SIGNAL_MID=0xFFFFD34D, SIGNAL_GOOD=0xFF54E38E, SIGNAL_GREAT=0xFF00E436, SIGNAL_UNKNOWN=0xFF9BA9B8;
    private int signalColorForScore(float score){
        if(Float.isNaN(score))return SIGNAL_UNKNOWN;
        if(score<0.25f)return SIGNAL_BAD;
        if(score<0.55f)return 0xFFFFB84F;
        if(score<0.75f)return SIGNAL_MID;
        if(score<0.9f)return SIGNAL_GOOD;
        return SIGNAL_GREAT;
    }
    private int signalColorForMetric(String metric){
        if("СИГНАЛ".equals(metric))return signalColorForScore(signalLevelScore());
        if("SINR".equals(metric))return signalColorForScore(sinrScore());
        if("RSRQ".equals(metric))return signalColorForScore(rsrqScore());
        if("RSRP".equals(metric))return signalColorForScore(rsrpScore());
        return SIGNAL_UNKNOWN;
    }
    /** Equal-weight composite of RSSI, SINR, RSRQ and RSRP (when available).
     * This is a local heuristic rating, not a router-provided percentage.
     * Unavailable readings are excluded rather than replaced with zeros. */
    private float quality(){
        float[] scores=new float[]{signalLevelScore(),sinrScore(),rsrqScore(),rsrpScore()};
        float sum=0f;int count=0;
        for(float score:scores){if(!Float.isNaN(score)){sum+=score;count++;}}
        return count==0?Float.NaN:clamp01(sum/count);
    }
    private String speed(String key){double x=num(status,key);
        if(Double.isNaN(x))x=key.equals("realtime_rx_thrpt")?fallbackDl:fallbackUl;
        if(Double.isNaN(x))return "—";
        if(x>=1_000_000)return String.format(Locale.US,"%.2f MB/s",x/1_000_000);
        if(x>=1_000)return String.format(Locale.US,"%.1f KB/s",x/1_000);
        return String.format(Locale.US,"%.0f B/s",x);
    }
    private double rateValue(JSONObject from,String key,double fallback){
        double x=num(from,key);return Double.isNaN(x)?(Double.isNaN(fallback)?0d:fallback):x;
    }
    private void overview(){
        overviewValues.clear();overviewSignalValues.clear();overviewGauge=null;overviewGraph=null;
        addTitle("Центр управления","Мобильная сеть и состояние AX8 Pro в реальном времени");
        LinearLayout hero=Ui.card(this);hero.setBackground(Ui.gradient(this,24,0xFF1D374D,0xFF102438,0xFF0D1A2B));
        LinearLayout top=Ui.horizontal(this);TextView badge=Ui.chip(this,band(),Ui.CYAN);overviewValues.put("band",badge);top.addView(badge);
        View spacer=new View(this);top.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        boolean radioFresh=statusAt>0&&System.currentTimeMillis()-statusAt<30000;
        boolean lteOnline=radioFresh&&stat("ppp_status").equals("ppp_connected");
        TextView ppp=Ui.chip(this,!radioFresh?"◌ ОБНОВЛЕНИЕ":lteOnline?"● LTE ONLINE":"○ LTE OFFLINE",
            !radioFresh?Ui.ORANGE:lteOnline?Ui.GREEN:Ui.RED);
        overviewValues.put("ppp",ppp);top.addView(ppp);hero.addView(top);
        LinearLayout center=Ui.horizontal(this);Ui.SignalGauge gauge=new Ui.SignalGauge(this);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(0,d(166),1.15f);center.addView(gauge,gp);
        overviewGauge=gauge;gauge.setLevelImmediate(quality());
        LinearLayout info=Ui.vertical(this);
        info.addView(signalRow("СИГНАЛ",rssi()));Ui.addSpace(info,this,8);
        info.addView(signalRow("SINR",reading(sinr(),"dB")));Ui.addSpace(info,this,8);
        info.addView(signalRow("RSRQ",reading(rsrq(),"dB")));Ui.addSpace(info,this,8);
        info.addView(signalRow("RSRP",reading(rsrp(),"dBm")));
        center.addView(info,new LinearLayout.LayoutParams(0,-2,1));hero.addView(center);addCard(hero);
        LinearLayout speeds=Ui.card(this);Ui.title(speeds,this,"Трафик сейчас","Скорость приёма и передачи данных");
        Ui.addSpace(speeds,this,14);
        LinearLayout summary=Ui.horizontal(this);
        LinearLayout dl=Ui.vertical(this),ul=Ui.vertical(this);
        dl.addView(Ui.text(this,"↓  ЗАГРУЗКА",12,Ui.CYAN,true));Ui.addSpace(dl,this,6);
        dl.addView(liveValue("dl",speed("realtime_rx_thrpt"),19,Ui.TEXT,true));
        ul.addView(Ui.text(this,"↑  ОТДАЧА",12,Ui.ORANGE,true));Ui.addSpace(ul,this,6);
        ul.addView(liveValue("ul",speed("realtime_tx_thrpt"),19,Ui.TEXT,true));
        summary.addView(dl,new LinearLayout.LayoutParams(0,-2,1));summary.addView(ul,new LinearLayout.LayoutParams(0,-2,1));speeds.addView(summary);
        Ui.addSpace(speeds,this,13);Ui.SpeedGraph graph=new Ui.SpeedGraph(this);
        java.util.Iterator<Float> itDl=historyDl.iterator(),itUl=historyUl.iterator();
        while(itDl.hasNext()||itUl.hasNext())graph.push(itDl.hasNext()?itDl.next():0f,itUl.hasNext()?itUl.next():0f);
        overviewGraph=graph;
        speeds.addView(graph,Ui.lp(this,-1,94));addCard(speeds);
        LinearLayout cell=Ui.card(this);Ui.title(cell,this,"Сотовая станция","Технические параметры радиосети");Ui.addSpace(cell,this,10);
        addRows(cell,new String[][]{{"Оператор / PLMN",stat("network_provider")},{"Технология",stat("network_type")},{"EARFCN",stat("nv_arfcn")},
            {"PCI",stat("nv_pci")},{"eNodeB",stat("nv_enodbid")},{"CELL ID",stat("nv_globecellid")},{"TX Power",txPower()},
            {"Батарея",battery()},{"Устройств Wi‑Fi",wifiCount()}});addCard(cell);
        LinearLayout quick=Ui.card(this);Ui.title(quick,this,"Быстрые действия","Одно нажатие вместо команд терминала");Ui.addSpace(quick,this,12);
        LinearLayout first=Ui.horizontal(this);TextView b3=Ui.button(this,"B3",band().equals("B3")?Ui.CYAN:Ui.CARD2,band().equals("B3")?Ui.BG:Ui.CYAN,()->bandConfirm(3));
        TextView b20=Ui.button(this,"B20",band().equals("B20")?Ui.CYAN:Ui.CARD2,band().equals("B20")?Ui.BG:Ui.CYAN,()->bandConfirm(20));
        TextView auto=Ui.button(this,"AUTO",Ui.CARD2,Ui.CYAN,()->bandConfirm(0));
        overviewValues.put("quick_b3",b3);overviewValues.put("quick_b20",b20);
        for(TextView v:new TextView[]{b3,b20,auto}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(48),1);lp.rightMargin=d(7);first.addView(v,lp);}
        quick.addView(first);Ui.addSpace(quick,this,10);
        quick.addView(Ui.button(this,"↻  Переподключить сеть",Ui.CARD2,Ui.TEXT,()->networkReconnect()));addCard(quick);
    }
    private String firstStatus(String...keys){for(String key:keys){String v=stat(key);if(!v.equals("—"))return v;}return "—";}
    private String reading(String v,String unit){return v.equals("—")?"—":v+" "+unit;}
    private String wifiCount(){String x=stat("wifi_access_sta_num");return !x.equals("—")?x:clientsAt>0?String.valueOf(devices.size()):"—";}
    private String txPower(){String raw=firstStatus("nv_txpower","nv_tx_power","txpower","tx_power","lte_txpower","lte_tx_power");
        return raw.equals("—")?"—":raw.toLowerCase(Locale.US).contains("dbm")?raw:reading(raw,"dBm");}
    private TextView liveValue(String key,String value,int size,int color,boolean bold){
        TextView t=Ui.text(this,value,size,color,bold);overviewValues.put(key,t);return t;
    }
    private LinearLayout signalRow(String metric,String value){
        LinearLayout row=Ui.horizontal(this);
        TextView name=Ui.text(this,metric,13,Ui.MUTED,true);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView val=Ui.text(this,value,12,signalColorForMetric(metric),true);
        val.setSingleLine(true);val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        val.setGravity(Gravity.END);
        row.addView(val,new LinearLayout.LayoutParams(0,-2,1));
        overviewSignalValues.put(metric,val);
        return row;
    }
    private void setLive(String key,String value){TextView t=overviewValues.get(key);if(t!=null&&!value.contentEquals(t.getText()))t.setText(value);}
    private void setSignalLive(String metric,String value){
        TextView t=overviewSignalValues.get(metric);
        if(t!=null){if(!value.contentEquals(t.getText()))t.setText(value);t.setTextColor(signalColorForMetric(metric));}
    }
    private void updateOverviewLive(boolean newSample){
        setLive("band",band());
        for(int frequency:new int[]{3,20}){
            TextView btn=overviewValues.get("quick_b"+frequency);
            if(btn!=null){boolean selected=band().equals("B"+frequency);
                btn.setBackground(Ui.shape(this,selected?Ui.CYAN:Ui.CARD2,0,15));
                btn.setTextColor(selected?Ui.BG:Ui.CYAN);
            }
        }
        boolean connected="ppp_connected".equals(s("ppp_status"));
        setLive("ppp",connected?"● LTE ONLINE":"○ LTE OFFLINE");
        TextView p=overviewValues.get("ppp");if(p!=null)p.setTextColor(connected?Ui.GREEN:Ui.RED);
        setSignalLive("СИГНАЛ",rssi());
        setSignalLive("SINR",reading(sinr(),"dB"));
        setSignalLive("RSRQ",reading(rsrq(),"dB"));
        setSignalLive("RSRP",reading(rsrp(),"dBm"));
        setLive("dl",speed("realtime_rx_thrpt"));setLive("ul",speed("realtime_tx_thrpt"));
        setLive("Оператор / PLMN",stat("network_provider"));setLive("Технология",stat("network_type"));
        setLive("EARFCN",stat("nv_arfcn"));setLive("PCI",stat("nv_pci"));
        setLive("eNodeB",stat("nv_enodbid"));setLive("CELL ID",stat("nv_globecellid"));
        setLive("TX Power",txPower());setLive("Батарея",battery());setLive("Устройств Wi‑Fi",wifiCount());
        TextView batteryValue=overviewValues.get("Батарея");if(batteryValue!=null)batteryValue.setTextColor(batteryCharging()?Ui.GREEN:Ui.TEXT);
        if(overviewGauge!=null && newSample)overviewGauge.setLevel(quality());
        if(overviewGraph!=null && newSample)overviewGraph.push(historyDl.isEmpty()?0f:historyDl.peekLast(),historyUl.isEmpty()?0f:historyUl.peekLast());
    }
    private boolean batteryCharging(){
        String value=stat("battery_charging").trim().toLowerCase(Locale.US);
        return value.equals("1")||value.equals("true")||value.equals("charging")||
            value.equals("yes")||value.equals("on");
    }
    private String battery(){
        double n=num(status,"battery_vol_percent");
        if(Double.isNaN(n))n=num(status,"battery_pers");
        if(Double.isNaN(n))return "—";
        String raw=s("battery_vol_percent");
        if(n>0&&n<=1&&raw.contains("."))n*=100;
        return (batteryCharging()?"⚡ ":"")+Math.round(n)+"%";
    }
    private void addRows(LinearLayout c,String[][] rows){for(int i=0;i<rows.length;i++){
        LinearLayout line=Ui.infoRow(this,rows[i][0],rows[i][1],rows[i][1].equals("—")?Ui.MUTED:i%2==0?Ui.TEXT:Ui.CYAN);
        if("Батарея".equals(rows[i][0])&&batteryCharging())
            ((TextView)line.getChildAt(1)).setTextColor(Ui.GREEN);
        if(tab==0)overviewValues.put(rows[i][0],(TextView)line.getChildAt(1));
        if(tab==3)networkValues.put(rows[i][0],(TextView)line.getChildAt(1));
        c.addView(line);
        if(i<rows.length-1)c.addView(Ui.rule(this),Ui.lp(this,-1,1));
    }}

    private void addSignalNetworkRows(LinearLayout c){
        addSignalNetworkRow(c,"RSRP",reading(rsrp(),"dBm"),true);
        addSignalNetworkRow(c,"RSRQ",reading(rsrq(),"dB"),true);
        addSignalNetworkRow(c,"SINR",reading(sinr(),"dB"),true);
        addSignalNetworkRow(c,"RSSI",rssi(),false);
        addSignalNetworkRow(c,"Bandwidth",bandwidth(),false);
    }
    private void addSignalNetworkRow(LinearLayout c,String key,String value,boolean signalColor){
        int color=value.equals("—")?Ui.MUTED:(signalColor?signalColorForMetric(key):Ui.CYAN);
        LinearLayout line=Ui.infoRow(this,key,value,color);
        networkValues.put(key,(TextView)line.getChildAt(1));
        c.addView(line);
        if(!"Bandwidth".equals(key))c.addView(Ui.rule(this),Ui.lp(this,-1,1));
    }
    /** Never change the router's raw hostname, only its presentation. */
    private String friendlyName(RouterClient.Device dev){
        String name=prefs.getString("alias_"+dev.mac,dev.name);
        if(name==null||name.trim().isEmpty())name="Без имени";
        return name.replace('_',' ').replace('-',' ').replaceAll("\\s+"," ").trim();
    }
    private LinearLayout clientMetric(String label,String value,int color,TextView[] output){
        LinearLayout line=Ui.horizontal(this);line.setPadding(0,d(3),0,d(3));
        String icon="";String rest=label;
        int gap=label.indexOf(' ');
        if(gap>0){icon=label.substring(0,gap).trim();rest=label.substring(gap+1).trim();}
        if(!icon.isEmpty()){
            TextView arrow=Ui.text(this,icon,14,color,true);arrow.setPadding(0,0,d(6),0);
            line.addView(arrow);
        }
        TextView key=Ui.text(this,rest,13,Ui.MUTED,false);
        line.addView(key,new LinearLayout.LayoutParams(0,-2,1));
        TextView val=Ui.text(this,value,14,color,true);val.setGravity(Gravity.END);
        line.addView(val,new LinearLayout.LayoutParams(0,-2,1));
        output[0]=val;return line;
    }
    private LinearLayout strongMetric(String icon,String label,String value,int color,TextView[] output){
        LinearLayout line=Ui.horizontal(this);line.setPadding(0,d(4),0,d(4));
        TextView ico=Ui.text(this,icon,14,color,true);ico.setPadding(0,0,d(6),0);line.addView(ico);
        TextView key=Ui.text(this,label,13,Ui.TEXT,true);line.addView(key,new LinearLayout.LayoutParams(0,-2,1));
        TextView val=Ui.text(this,value,15,color,true);val.setGravity(Gravity.END);
        line.addView(val,new LinearLayout.LayoutParams(0,-2,1));
        output[0]=val;return line;
    }
    private String savedLimitDeviceName(String mac){
        for(RouterClient.Device device:devices)if(device.mac.equals(mac))return friendlyName(device);
        String cached=prefs.getString("cached_client_name_"+mac,"");
        String alias=prefs.getString("alias_"+mac,cached);
        return alias.isEmpty()?"Устройство вне сети":alias.replace('_',' ').replace('-',' ').replaceAll("\\s+"," ").trim();
    }
    private void savedLimitRow(LinearLayout target,String mac,Integer kb){
        LinearLayout row=Ui.horizontal(this);row.setPadding(0,d(8),0,d(8));
        TextView name=Ui.text(this,savedLimitDeviceName(mac),13,Ui.CYAN,true);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView limit=Ui.text(this,limitName(kb),14,kb==null?Ui.GREEN:Ui.RED,true);
        limit.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);
        params.leftMargin=d(10);row.addView(limit,params);
        target.addView(row);
    }
    private void clients(){
        clientDlValues.clear();clientUlValues.clear();clientLimitValues.clear();qosStatusLive=null;
        clientsSignature=clientSkeletonSignature();
        addTitle("Подключённые устройства","Wi-Fi-клиенты, блокировки и контроль скорости");
        LinearLayout summary=Ui.card(this);LinearLayout row=Ui.horizontal(this);
        LinearLayout stats=Ui.vertical(this);stats.addView(Ui.text(this,String.valueOf(devices.size()),32,Ui.CYAN,true));
        stats.addView(Ui.text(this,"АКТИВНЫХ УСТРОЙСТВ",11,Ui.MUTED,true));row.addView(stats,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(Ui.button(this,"↻  Обновить",Ui.CARD2,Ui.TEXT,()->refresh(true)));summary.addView(row);
        Ui.addSpace(summary,this,12);
        summary.addView(Ui.button(this,"⊘  Список блокировок"+(blocked==null?"":" ("+blocked.black.size()+")"),Ui.CARD2,Ui.ORANGE,()->showBlocked()));
        Ui.addSpace(summary,this,8);
        summary.addView(Ui.button(this,"⚡  Массовые лимиты / Приоритет",Ui.CARD2,Ui.CYAN,()->bulkControls()));
        Ui.addSpace(summary,this,8);
        summary.addView(Ui.button(this,"⌂  Мой телефон: "+(ownerMac.isEmpty()?"выбрать":ownerMac),Ui.CARD2,Ui.CYAN,()->chooseOwner()));
        Ui.addSpace(summary,this,8);
        summary.addView(Ui.button(this,"↯  Отключить остальных на 10 сек.",Ui.CARD2,Ui.RED,()->kickOthers()));
        if(monitorOn&&!qos.limits().isEmpty()){
            Ui.addSpace(summary,this,10);qosStatusLive=Ui.text(this,"QoS · "+uiQosWatch.status(),11,Ui.MUTED,true);
            summary.addView(qosStatusLive);
        }
        addCard(summary);
        if(devices.isEmpty())rowCard("Устройства не обнаружены","Подключитесь к AX8 и обновите список",null,Ui.MUTED,null);
        for(RouterClient.Device dev:devices){
            boolean deny=blocked!=null&&blocked.black.contains(dev.mac);
            Integer limit=qos.effectiveLimit(dev.mac);
            DeviceTrafficMonitor.Rate rate=monitorOn?traffic.get(dev.mac):null;
            LinearLayout card=Ui.card(this);
            LinearLayout head=Ui.horizontal(this);
            TextView name=Ui.text(this,friendlyName(dev),16,Ui.TEXT,true);
            head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView badge=Ui.chip(this,deny?"ЗАБЛОКИРОВАН":"ДЕТАЛИ",deny?Ui.RED:Ui.CYAN);
            LinearLayout.LayoutParams bp=Ui.lp(this,-2,-2);bp.leftMargin=d(8);head.addView(badge,bp);
            card.addView(head);Ui.addSpace(card,this,10);
            TextView[] down={null},up={null},cap={null};
            card.addView(clientMetric("↓  Входящая",rate==null?"—":humanRate(rate.dl),Ui.CYAN,down));
            card.addView(clientMetric("↑  Исходящая",rate==null?"—":humanRate(rate.ul),Ui.ORANGE,up));
            boolean overspeed=limit!=null&&limit>0&&rate!=null&&
                (rate.dl>limit*1000d*1.30||rate.ul>limit*1000d*1.30);
            card.addView(strongMetric("◈","Лимит",limitName(limit)+(overspeed?" · ↑!":""),
                limit==null?Ui.GREEN:Ui.RED,cap));
            card.setOnClickListener(v->showDevice(dev));Ui.animateTouch(card);addCard(card);
            clientDlValues.put(dev.mac,down[0]);clientUlValues.put(dev.mac,up[0]);clientLimitValues.put(dev.mac,cap[0]);
        }
    }
    private static String humanRate(double bps){
        if(Double.isNaN(bps))return "—";
        if(bps>=1_000_000)return String.format(Locale.US,"%.2f MB/s",bps/1_000_000);
        if(bps>=1000)return String.format(Locale.US,"%.1f KB/s",bps/1000);
        return String.format(Locale.US,"%.0f B/s",bps);
    }
    private static String limitName(Integer kb){if(kb==null)return "Без лимита";if(kb==0)return "0 KB/s";
        return kb>=1000?String.format(Locale.US,"%.2f MB/s",kb/1000.):kb+" KB/s";
    }
    private void showDevice(RouterClient.Device dev){
        LinearLayout body=Ui.vertical(this);
        String name=friendlyName(dev);
        boolean isBlocked=blocked!=null&&blocked.black.contains(dev.mac);
        TextView state=Ui.chip(this,isBlocked?"● ЗАБЛОКИРОВАН":"● ПОДКЛЮЧЕНО",isBlocked?Ui.RED:Ui.GREEN);
        body.addView(state);Ui.addSpace(body,this,12);
        DeviceTrafficMonitor.Rate rate=traffic.get(dev.mac);
        if(rate!=null){
            TextView[] incoming={null};body.addView(clientMetric("↓  Входящая",humanRate(rate.dl),Ui.CYAN,incoming));
            TextView[] outgoing={null};body.addView(clientMetric("↑  Исходящая",humanRate(rate.ul),Ui.ORANGE,outgoing));}
        addRows(body,new String[][]{{"IPv4",dev.ip.isEmpty()?"—":dev.ip},
            {"MAC",dev.mac},{"IPv6",dev.ipv6.isEmpty()?"—":String.join(", ",dev.ipv6)},
            {"Тип подключения",dev.type}});
        TextView[] limitValue={null};body.addView(strongMetric("◈","Лимит",limitName(qos.effectiveLimit(dev.mac)),qos.effectiveLimit(dev.mac)==null?Ui.GREEN:Ui.RED,limitValue));
        Ui.addSpace(body,this,9);
        body.addView(Ui.button(this,"◎ Проверить ограничения на роутере",Ui.CARD2,Ui.YELLOW,()->{
            workers.execute(()->{
                String report;try{report=qos.audit(dev.mac,devices);}catch(Exception e){report="Ошибка диагностики: "+e.getMessage();}
                final String result=report;handler.post(()->alert("Проверка QoS",result));
            });
        }));
        Ui.addSpace(body,this,9);
        body.addView(Ui.button(this,"↻ Переустановить текущий лимит",Ui.CARD2,Ui.ORANGE,()->{
            confirm("Восстановить QoS?","Повторно создать tc/iptables-фильтры с сохранёнными лимитами? Это может на короткое время прервать трафик.",
                ()->action("Проверка и восстановление QoS",progress->qos.restore(api.devices(),progress)));
        }));
        Ui.addSpace(body,this,16);
        final Dialog[] sheet={null};
        body.addView(Ui.button(this,"◉  Настроить скорость",0xFF155050,Ui.CYAN,()->{
            sheet[0].dismiss();chooseLimit(dev);
        }));
        Ui.addSpace(body,this,9);
        LinearLayout pair=Ui.horizontal(this);
        TextView rename=Ui.button(this,"✎  Имя",Ui.CARD2,Ui.TEXT,()->{sheet[0].dismiss();editAlias(dev);});
        TextView copy=Ui.button(this,"⧉  MAC",Ui.CARD2,Ui.TEXT,()->{
            android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("MAC",dev.mac));toast("MAC скопирован");
        });
        pair.addView(rename,new LinearLayout.LayoutParams(0,d(46),1));
        LinearLayout.LayoutParams pr=new LinearLayout.LayoutParams(0,d(46),1);pr.leftMargin=d(8);pair.addView(copy,pr);
        body.addView(pair);Ui.addSpace(body,this,9);
        body.addView(Ui.button(this,isBlocked?"✓  Разблокировать":"⊘  Заблокировать",
            isBlocked?0xFF173A39:0xFF442637,isBlocked?Ui.GREEN:Ui.RED,()->{
                sheet[0].dismiss();confirm("Контроль доступа",(isBlocked?"Разблокировать ":"Заблокировать ")+name+"?",
                    ()->action("Список доступа",p->api.setBlocked(dev.mac,!isBlocked)));
            }));
        Ui.addSpace(body,this,9);
        body.addView(Ui.button(this,"↯  Отключить на 10 секунд",Ui.CARD2,Ui.ORANGE,()->{
            sheet[0].dismiss();confirm("Временно отключить клиента",
                "MAC будет временно добавлен в чёрный список на 10 секунд и затем восстановлен. При сбое проверьте blacklist.",
                ()->action("Временное отключение",p->api.kick(dev.mac,p)));
        }));
        sheet[0]=Ui.sheet(this,name,"Управление устройством · "+dev.ip,body);
    }
    private void editAlias(RouterClient.Device dev){
        LinearLayout body=Ui.vertical(this);
        EditText name=themedField(friendlyName(dev),"Название устройства",false);
        name.selectAll();body.addView(name);Ui.addSpace(body,this,11);
        Dialog[] ref={null};
        body.addView(Ui.button(this,"↺  Сбросить название",Ui.CARD2,Ui.ORANGE,()->{
            prefs.edit().remove("alias_"+dev.mac).apply();
            if(ref[0]!=null)ref[0].dismiss();draw(true);
        }));
        ref[0]=Ui.modal(this,"Название устройства","Отображается только в приложении",body,"Сохранить",()->{
            String requested=RouterClient.clean(name.getText().toString());
            if(requested.isEmpty()){toast("Укажите название");return;}
            prefs.edit().putString("alias_"+dev.mac,requested).apply();draw(true);
        });
    }
    private void showBlocked(){
        if(blocked==null){toast("Список ещё загружается");refresh(true);return;}
        LinearLayout body=Ui.vertical(this);final Dialog[] dialog={null};
        if(blocked.black.isEmpty())body.addView(Ui.text(this,"Заблокированных устройств нет",14,Ui.MUTED,false));
        for(String mac:blocked.black){
            // The router may remove a blocked client from station_list immediately.
            // Prefer its user alias, then the previously cached station hostname.
            String name=savedLimitDeviceName(mac);
            if("Устройство вне сети".equals(name)||"Без имени".equals(name))name="Имя неизвестно";
            final String displayName=name;
            body.addView(Ui.text(this,displayName,15,Ui.TEXT,true));
            Ui.addSpace(body,this,5);
            body.addView(Ui.text(this,mac,12,Ui.MUTED,false));
            Ui.addSpace(body,this,9);
            body.addView(Ui.button(this,"✓  Разблокировать",Ui.CARD2,Ui.GREEN,()->{
                dialog[0].dismiss();confirm("Разблокировать устройство",displayName+" · "+mac,
                    ()->action("Разблокировка",p->api.setBlocked(mac,false)));
            }));Ui.addSpace(body,this,13);
        }
        if(!blocked.black.isEmpty()){
            Ui.addSpace(body,this,6);
            body.addView(Ui.button(this,"⌫  Очистить все блокировки",0xFF442637,Ui.RED,()->{
                dialog[0].dismiss();confirm("Очистить список","Удалить ВСЕ блокировки?",
                    ()->action("Очистка DENY",p->api.writeBlocked(Collections.emptyList(),api.blockState())));
            }));
        }
        dialog[0]=Ui.sheet(this,"Список блокировок","Управление MAC-адресами",body);
    }
    private void kickOthers(){
        if(devices.isEmpty()){alert("Нет клиентов","Обновите список устройств AX8 и повторите действие.");return;}
        OwnerIdentity.Result owner=OwnerIdentity.resolve(this,devices,blocked,ownerMac);
        if(!owner.resolved()){
            if(owner.manualSelectionPossible)chooseOwnerForKick(owner.reason);
            else alert("Не удалось проверить свой телефон",owner.reason);
            return;
        }
        if(!owner.mac.equals(ownerMac)){
            ownerMac=owner.mac;
            prefs.edit().putString("owner_mac",owner.mac).apply();
        }
        confirmKickOthers(owner.mac,owner.origin);
    }
    private void chooseOwnerForKick(String reason){
        LinearLayout body=Ui.vertical(this);
        body.addView(Ui.text(this,reason,13,Ui.MUTED,false));
        Ui.addSpace(body,this,10);
        body.addView(Ui.text(this,"Выберите именно телефон, на котором открыто приложение. Неверный выбор может отключить его от Wi-Fi на 10 секунд.",13,Ui.RED,true));
        Ui.addSpace(body,this,12);
        final Dialog[] dialog={null};
        for(RouterClient.Device d:new ArrayList<>(devices)){
            body.addView(Ui.button(this,friendlyName(d)+" · "+d.ip,Ui.CARD2,Ui.TEXT,()->{
                if(dialog[0]!=null)dialog[0].dismiss();
                confirm("Это управляющий телефон?","Имя: "+friendlyName(d)+"\nIP: "+d.ip+
                    "\nMAC: "+d.mac+"\n\nНеправильный выбор может отключить текущий телефон. Продолжить?",()->{
                    ownerMac=d.mac;
                    prefs.edit().putString("owner_mac",ownerMac).apply();
                    confirmKickOthers(d.mac,"ручной выбор");
                });
            }));
            Ui.addSpace(body,this,8);
        }
        dialog[0]=Ui.sheet(this,"Мой телефон","Защита от самоотключения",body);
    }
    private void confirmKickOthers(String savedMac,String source){
        confirm("Отключить остальных на 10 секунд?",
            "Останется подключённым: "+savedMac+" ("+source+").\n\n"+
            "Остальные подключённые устройства временно попадут в blacklist. После 10 секунд приложение попытается вернуть исходный список. При обрыве питания/процесса проверьте blacklist.",
            ()->action("Отключение остальных",progress->{
                // The state might have changed between the button tap and worker execution.
                List<RouterClient.Device> latest=api.devices();
                RouterClient.BlockState initial=api.blockState();
                OwnerIdentity.Result verified=OwnerIdentity.resolve(MainActivity.this,latest,initial,savedMac);
                if(!verified.resolved()||!verified.mac.equals(savedMac)){
                    throw new IllegalStateException("Отмена: управляющее устройство больше нельзя безопасно подтвердить. "+verified.reason);
                }
                ArrayList<String> target=new ArrayList<>(initial.black);
                for(RouterClient.Device dev:latest){
                    if(!verified.mac.equals(dev.mac)&&!target.contains(dev.mac))target.add(dev.mac);
                }
                if(target.equals(initial.black))throw new IllegalStateException("Других устройств для временного отключения нет.");
                boolean attempted=false;
                String kickProblem="";
                try{
                    progress.note("Применяю временный DENY к "+(target.size()-initial.black.size())+" устройствам...");
                    attempted=true;
                    api.writeBlocked(target,initial);
                    long holdStarted=System.currentTimeMillis();
                    Thread.sleep(1200);
                    try{
                        List<RouterClient.Device> stillOnline=api.devices();
                        ArrayList<String> pending=new ArrayList<>();
                        for(RouterClient.Device d:stillOnline)
                            if(!initial.black.contains(d.mac)&&target.contains(d.mac)&&!d.mac.equals(verified.mac))pending.add(d.mac);
                        if(!pending.isEmpty()){
                            progress.note("Wi-Fi-сессии ещё числятся подключёнными; пробую точечное отключение...");
                            WifiDisassoc.Result kick=WifiDisassoc.disconnect(api,prefs,pending,verified.mac,progress);
                            kickProblem=kick.message;
                        }
                    }catch(Exception e){
                        kickProblem="Не удалось проверить список Wi-Fi-клиентов после DENY: "+e.getMessage();
                    }
                    long left=10000L-(System.currentTimeMillis()-holdStarted);
                    if(left>0)Thread.sleep(left);
                }finally{
                    if(attempted){
                        progress.note("Восстановление исходного списка блокировок...");
                        api.writeBlocked(initial.black,api.blockState());
                    }
                }
                if(!kickProblem.isEmpty())throw new IllegalStateException(
                    "DENY действовал 10 секунд и снят. Дополнительный разрыв Wi-Fi-сессий не подтверждён: "+kickProblem);
            }));
    }
    private void chooseLimit(RouterClient.Device dev){
        String name=friendlyName(dev);
        LinearLayout body=Ui.vertical(this);
        Integer current=qos.limits().get(dev.mac);
        TextView hint=Ui.text(this,"СЕЙЧАС  ·  "+limitName(current),12,current==null?Ui.GREEN:Ui.RED,true);body.addView(hint);
        Ui.addSpace(body,this,12);
        final Dialog[] dialog={null};
        TextView free=Ui.button(this,"∞  Без ограничения",current==null?0xFF15504D:Ui.CARD2,
            current==null?Ui.CYAN:Ui.TEXT,()->applyLimitFromSheet(dialog,dev,null));
        body.addView(free);Ui.addSpace(body,this,9);
        TextView off=Ui.button(this,"⊘  0 KB/s  ·  Блокировка трафика",Integer.valueOf(0).equals(current)?0xFF442637:Ui.CARD2,Ui.RED,
            ()->applyLimitFromSheet(dialog,dev,0));
        body.addView(off);Ui.addSpace(body,this,15);
        body.addView(Ui.text(this,"БЫСТРЫЕ ПРЕСЕТЫ · DL + UL",12,Ui.MUTED,true));Ui.addSpace(body,this,10);
        int[] presets={15,20,30,50,100,250,300,500,750,1000};
        for(int i=0;i<presets.length;i+=3){
            LinearLayout row=Ui.horizontal(this);
            for(int j=i;j<Math.min(i+3,presets.length);j++){
                final int kb=presets[j];boolean selected=Integer.valueOf(kb).equals(current);
                TextView x=Ui.button(this,limitName(kb),selected?0xFF15504D:Ui.CARD2,selected?Ui.CYAN:Ui.TEXT,
                    ()->applyLimitFromSheet(dialog,dev,kb));
                x.setTextSize(12);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(45),1);lp.rightMargin=d(6);row.addView(x,lp);
            }
            // Complete the last row with inert spacers so no preset is accidentally oversized.
            int count=Math.min(3,presets.length-i);
            for(int j=count;j<3;j++)row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
            body.addView(row);Ui.addSpace(body,this,7);
        }
        Ui.addSpace(body,this,12);
        body.addView(Ui.text(this,"ПОЛЬЗОВАТЕЛЬСКИЙ ЛИМИТ",12,Ui.MUTED,true));Ui.addSpace(body,this,8);
        int initial=current==null?500:Math.max(0,Math.min(20000,current));
        TextView amount=Ui.text(this,limitName(initial),22,Ui.CYAN,true);amount.setGravity(Gravity.CENTER);
        body.addView(amount);Ui.addSpace(body,this,6);
        SeekBar seek=new SeekBar(this);seek.setMax(20000);seek.setProgress(initial);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));body.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}
            public void onProgressChanged(SeekBar b,int value,boolean user){amount.setText(limitName(value));}
        });
        Ui.addSpace(body,this,8);
        body.addView(Ui.button(this,"✓  Применить выбранную скорость",0xFF15504D,Ui.CYAN,
            ()->applyLimitFromSheet(dialog,dev,seek.getProgress())));
        Ui.addSpace(body,this,7);
        body.addView(Ui.text(this,"Значения указаны в KB/s, одновременно для входящего и исходящего трафика. Диапазон ползунка: 0–20000 KB/s. Требуется Telnet на роутере.",12,Ui.MUTED,false));
        dialog[0]=Ui.sheet(this,"Лимит скорости",name+" · IPv4/IPv6 · DL + UL",body);
    }
    private void applyLimitFromSheet(Dialog[] sheet,RouterClient.Device dev,Integer kb){
        if(sheet[0]!=null)sheet[0].dismiss();
        confirm("Применить QoS","Установить для "+prefs.getString("alias_"+dev.mac,dev.name)+" лимит «"+
            limitName(kb)+"» на приём и отдачу через Telnet?",
            ()->action("QoS · "+limitName(kb),p->qos.setLimit(dev.mac,kb,devices,p)));
    }
    private void sms(){
        smsSignature=smsFingerprint();
        addTitle("Сообщения SIM","SMS из памяти маршрутизатора, без веб-браузера");
        long unread=messages.stream().filter(RouterClient.Sms::unread).count();
        LinearLayout counter=Ui.card(this);LinearLayout row=Ui.horizontal(this);
        LinearLayout a=Ui.vertical(this);a.addView(Ui.text(this,""+messages.size(),29,Ui.CYAN,true));
        a.addView(Ui.text(this,"СООБЩЕНИЙ",11,Ui.MUTED,true));row.addView(a,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout b=Ui.vertical(this);b.addView(Ui.text(this,""+unread,29,unread>0?Ui.ORANGE:Ui.GREEN,true));
        b.addView(Ui.text(this,"НЕПРОЧИТАННЫХ",11,Ui.MUTED,true));row.addView(b,new LinearLayout.LayoutParams(0,-2,1));counter.addView(row);
        Ui.addSpace(counter,this,14);
        LinearLayout buttons=Ui.horizontal(this);
        TextView send=Ui.button(this,"✉  Отправить",Ui.CARD2,Ui.CYAN,()->composeSms());
        TextView clear=Ui.button(this,"⌫  Удалить все",Ui.CARD2,Ui.RED,()->clearSms());
        buttons.addView(send,new LinearLayout.LayoutParams(0,d(48),1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(48),1);lp.leftMargin=d(8);buttons.addView(clear,lp);
        counter.addView(buttons);addCard(counter);
        if(messages.isEmpty())rowCard("Сообщений нет","Новые сообщения появятся автоматически после опроса AX8",null,Ui.MUTED,null);
        for(RouterClient.Sms msg:messages){
            LinearLayout card=Ui.card(this);
            LinearLayout head=Ui.horizontal(this);
            LinearLayout sender=Ui.vertical(this);
            sender.addView(Ui.text(this,msg.number,16,Ui.TEXT,true));Ui.addSpace(sender,this,5);
            String date=msg.date;int timezone=date.indexOf(" TZ ");
            if(timezone>=0)date=date.substring(0,timezone);
            sender.addView(Ui.text(this,date,12,Ui.MUTED,false));
            head.addView(sender,new LinearLayout.LayoutParams(0,-2,1));
            head.addView(Ui.chip(this,msg.unread()?"НОВОЕ":"ПРОЧИТАНО",msg.unread()?Ui.ORANGE:Ui.MUTED));
            card.addView(head);Ui.addSpace(card,this,11);
            String text=msg.content.replace('\n',' ');
            if(text.length()>120)text=text.substring(0,120)+"…";
            TextView preview=Ui.text(this,text,13,Ui.MUTED,false);preview.setMaxLines(3);card.addView(preview);
            card.setOnClickListener(v->showSms(msg));Ui.animateTouch(card);addCard(card);
        }
    }
    /** The contact picker grants temporary access only to the chosen phone number. */
    private void pickPhoneContact(){
        try{startActivityForResult(new Intent(Intent.ACTION_PICK,ContactsContract.CommonDataKinds.Phone.CONTENT_URI),PICK_PHONE_REQUEST);}
        catch(Exception e){alert("Контакты недоступны","Не удалось открыть телефонную книгу: "+e.getMessage());}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_PHONE_REQUEST||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try(Cursor cursor=getContentResolver().query(uri,new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){
                int idx=cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                if(idx>=0&&smsRecipientInput!=null){smsRecipientInput.setText(cursor.getString(idx));
                    smsRecipientInput.setSelection(smsRecipientInput.length());}
            }else toast("В контакте не найден номер");
        }catch(Exception e){alert("Не удалось получить номер",e.getMessage()==null?e.toString():e.getMessage());}
    }
    private EditText themedField(String value,String hint,boolean multiLine){
        EditText x=new EditText(this);x.setText(value);x.setHint(hint);x.setHintTextColor(Ui.MUTED);
        x.setTextSize(15*Ui.FONT_SCALE);x.setTextColor(Ui.TEXT);
        x.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        if(multiLine){x.setSingleLine(false);x.setMinLines(4);x.setMaxLines(7);x.setGravity(Gravity.TOP);}
        else x.setSingleLine(true);
        return x;
    }
    private void composeSms(){composeSms("","");}
    private void composeSms(String prefillNumber,String prefillMessage){
        LinearLayout body=Ui.vertical(this);
        body.addView(Ui.text(this,"ПОЛУЧАТЕЛЬ",11,Ui.MUTED,true));Ui.addSpace(body,this,6);
        LinearLayout recipient=Ui.horizontal(this);
        smsRecipientInput=themedField(prefillNumber,"+7… или другой номер",false);
        smsRecipientInput.setInputType(InputType.TYPE_CLASS_PHONE);
        recipient.addView(smsRecipientInput,new LinearLayout.LayoutParams(0,-2,1));
        TextView pick=Ui.button(this,"◉",Ui.CARD2,Ui.CYAN,()->pickPhoneContact());
        pick.setContentDescription("Выбрать номер из контактов");
        LinearLayout.LayoutParams pickParams=Ui.lp(this,52,50);pickParams.leftMargin=d(10);
        recipient.addView(pick,pickParams);body.addView(recipient);Ui.addSpace(body,this,17);
        body.addView(Ui.text(this,"СООБЩЕНИЕ",11,Ui.MUTED,true));Ui.addSpace(body,this,7);
        smsMessageInput=themedField(prefillMessage,"Текст SMS",true);
        smsMessageInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.addView(smsMessageInput);Ui.addSpace(body,this,9);
        TextView chars=Ui.text(this,"SMS с кириллицей: обычно 70 символов на одно сообщение (части могут тарифицироваться отдельно).",11,Ui.MUTED,false);
        body.addView(chars);Ui.addSpace(body,this,18);
        body.addView(Ui.button(this,"➤  Отправить SMS",Ui.CYAN,Ui.contrastOn(Ui.CYAN),()->{
            String number=smsRecipientInput.getText().toString().trim();
            String message=smsMessageInput.getText().toString();
            if(!RouterClient.validSmsNumber(number)){toast("Проверьте номер получателя");return;}
            if(message.trim().isEmpty()){toast("Введите текст сообщения");return;}
            if(message.length()>700){toast("Сообщение слишком длинное (максимум 700 символов)");return;}
            String preview=message.length()>150?message.substring(0,150)+"…":message;
            confirm("Отправить SMS?","Получатель: "+number+"\nТекст: "+preview+
                (message.length()>70?"\n\nВозможно разделение на несколько платных SMS.":""),()->{
                if(smsComposeDialog!=null){smsComposeDialog.dismiss();smsComposeDialog=null;}
                action("Отправка SMS",progress->api.sendSms(number,message));
            });
        }));
        smsComposeDialog=Ui.sheet(this,prefillNumber.trim().isEmpty()?"Новое SMS":"Ответить","Отправка с SIM-карты роутера AX8",body);
        if(!prefillNumber.trim().isEmpty()){smsRecipientInput.setSelection(smsRecipientInput.length());}
        if(!prefillMessage.trim().isEmpty()){smsMessageInput.setSelection(smsMessageInput.length());}
    }
    private void showSms(RouterClient.Sms sms){
        LinearLayout body=Ui.vertical(this);final Dialog[] dialog={null};
        TextView when=Ui.text(this,sms.date,12,Ui.MUTED,false);body.addView(when);Ui.addSpace(body,this,13);
        TextView text=Ui.text(this,sms.content,15,Ui.TEXT,false);text.setTextIsSelectable(true);body.addView(text);
        Ui.addSpace(body,this,22);
        body.addView(Ui.button(this,"↩  Ответить",Ui.CARD2,Ui.CYAN,()->{
            if(dialog[0]!=null)dialog[0].dismiss();
            composeSms(sms.number,"");
        }));Ui.addSpace(body,this,9);
        body.addView(Ui.button(this,"⧉  Копировать текст",Ui.CARD2,Ui.CYAN,()->{
            android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("SMS",sms.content));toast("Текст скопирован");
        }));Ui.addSpace(body,this,9);
        body.addView(Ui.button(this,"⌫  Удалить сообщение",0xFF442637,Ui.RED,()->{
            dialog[0].dismiss();confirm("Удалить SMS","Сообщение будет удалено из роутера.",
                ()->action("Удаление SMS",p->api.deleteSms(sms)));
        }));
        dialog[0]=Ui.sheet(this,sms.number,"Сообщение SIM",body);
        if(sms.unread()){
            sms.tag="0";draw(true);
            workers.execute(()->{try{api.readSms(sms);}catch(Exception e){
                handler.post(()->{sms.tag="1";toast("Не удалось отметить SMS прочитанным");if(tab==2)draw(true);});
            }});
        }
    }
    private void clearSms(){if(messages.isEmpty())return;
        confirm("Удалить все SMS","Безвозвратно удалить "+messages.size()+" сообщений?",
            ()->action("Очистка SMS",p->{int done=0;ArrayList<String> failures=new ArrayList<>();
                for(RouterClient.Sms sms:new ArrayList<>(messages)){
                    try{api.deleteSms(sms);done++;p.note("Удалено "+done+" из "+messages.size());}catch(Exception e){failures.add(sms.id);}
                }if(!failures.isEmpty())throw new IllegalStateException("Не удалены SMS: "+failures);
            }));
    }
    private void network(){
        networkValues.clear();networkSignature=networkSkeletonSignature();
        addTitle("Сеть и радиомодуль","Настройки подключения, Band Lock и диагностика");
        boolean connected="ppp_connected".equals(s("ppp_status"));
        LinearLayout wan=Ui.card(this);Ui.title(wan,this,"Мобильное подключение",connected?"Интернет подключён":"Мобильный интерфейс отключён или подключается");
        Ui.addSpace(wan,this,13);
        Runnable netToggle=()->confirm("Изменение WAN",
            connected?"Отключить мобильную передачу данных?":"Включить мобильную передачу данных?",
            ()->action("WAN",p->{api.network(!connected);api.waitPpp(!connected,!connected?60:20,p);}));
        wan.addView(Ui.button(this,connected?"◯  Отключить мобильную сеть":"●  Подключить мобильную сеть",
            connected?0xFF43303A:0xFF154B4A,connected?Ui.RED:Ui.GREEN,netToggle));
        Ui.addSpace(wan,this,9);wan.addView(Ui.button(this,"↻  Переподключить WAN",Ui.CARD2,Ui.TEXT,()->networkReconnect()));addCard(wan);
        LinearLayout bands=Ui.card(this);Ui.title(bands,this,"Фиксация LTE Band","Текущий: "+band()+". После смены потребуется перезагрузка AX8.");Ui.addSpace(bands,this,15);
        LinearLayout grid=Ui.vertical(this);
        int[] bs=RouterClient.SUPPORTED_BANDS;
        for(int i=0;i<bs.length;i+=3){LinearLayout one=Ui.horizontal(this);
            for(int j=i;j<Math.min(i+3,bs.length);j++){int num=bs[j];boolean active=band().equals("B"+num);
                TextView btn=Ui.button(this,"B"+num,active?Ui.CYAN:Ui.CARD2,active?Ui.BG:Ui.TEXT,()->bandConfirm(num));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(49),1);lp.rightMargin=d(8);one.addView(btn,lp);
            }grid.addView(one);Ui.addSpace(grid,this,8);
        }
        bands.addView(grid);bands.addView(Ui.button(this,"◎  AUTO BAND  ·  Все поддерживаемые",0xFF174A4B,Ui.CYAN,()->bandConfirm(0)));addCard(bands);
        LinearLayout technical=Ui.card(this);Ui.title(technical,this,"Расширенная диагностика","Поля радиомодуля и параметры LTE");Ui.addSpace(technical,this,9);
        addRows(technical,new String[][]{{"RSRP",reading(rsrp(),"dBm")},{"RSRQ",reading(rsrq(),"dB")},{"SINR",reading(sinr(),"dB")},{"RSSI",rssi()},
            {"Bandwidth",bandwidth()}});addCard(technical);
        LinearLayout q=Ui.card(this);Ui.title(q,this,"Ограничения трафика","Установленные лимиты хранятся по MAC-адресу и требуют Telnet на AX8.");
        Map<String,Integer> savedLimits=qos.limits();
        Ui.addSpace(q,this,12);q.addView(Ui.infoRow(this,"Сохранено правил",String.valueOf(savedLimits.size()),Ui.CYAN));
        if(savedLimits.isEmpty())q.addView(Ui.text(this,"Сохранённых правил пока нет. Добавьте лимит в разделе «Клиенты».",12,Ui.MUTED,false));
        else {
            Ui.addSpace(q,this,8);
            q.addView(Ui.text(this,"Список сохранённых ограничений",12,Ui.MUTED,true));
            ArrayList<String> macs=new ArrayList<>(savedLimits.keySet());
            macs.sort((a,b)->savedLimitDeviceName(a).compareToIgnoreCase(savedLimitDeviceName(b)));
            for(String mac:macs)savedLimitRow(q,mac,savedLimits.get(mac));
            Ui.addSpace(q,this,8);
            q.addView(Ui.button(this,"↻  Применить сохранённые лимиты",Ui.CARD2,Ui.TEXT,
                ()->confirm("Восстановить QoS","Пересобрать правила IPv4/IPv6 на роутере?",()->action("Применение QoS",p->{
                    List<RouterClient.Device> list=api.devices();qos.restore(list,p);
                }))));
        }
        Ui.addSpace(q,this,10);
        q.addView(Ui.button(this,"◎  Проверить реальные правила tc",Ui.CARD2,Ui.YELLOW,()->{
            workers.execute(()->{String report;try{report=qos.audit("",devices);}catch(Exception e){report="Ошибка диагностики: "+e.getMessage();}
                final String r=report;handler.post(()->alert("Диагностика QoS",r));});
        }));
        Ui.addSpace(q,this,11);
        q.addView(Ui.button(this,"⚡  Массовые лимиты и приоритет",Ui.CARD2,Ui.CYAN,()->bulkControls()));
        addCard(q);
    }
    private String rssi(){
        double v=Double.NaN;for(String key:new String[]{"nv_rssi","lte_rssi"}){v=num(status,key);if(!Double.isNaN(v))break;}
        if(!Double.isNaN(v)){if(v>0&&v<=150)v=-v;if(v>=-150&&v<=-20)return fmt(v)+" dBm";}
        double csq=num(status,"wan_csq");if(!Double.isNaN(csq)&&csq>=0&&csq<=31)return fmt(-113+2*csq)+" dBm";
        double a=num(status,"rssi"),r=num(status,"nv_rsrp");if(r>0)r-=141;
        if(a>0&&a<=150)a=-a;
        if(!Double.isNaN(a)&&a>=-150&&a<=-20&&(Double.isNaN(r)||Math.abs(a-r)>=3))return fmt(a)+" dBm";
        double q=num(status,"nv_rsrq");if(q>0)q=(q-40)/2;
        String bw=bandwidth().replace(" MHz","");
        double mhz=parse(bw);int rb=mhz==1.4?6:mhz==3?15:mhz==5?25:mhz==10?50:mhz==15?75:mhz==20?100:0;
        if(!Double.isNaN(r)&&!Double.isNaN(q)&&rb>0)return "≈ "+fmt(r+10*Math.log10(rb)-q)+" dBm";
        return "—";
    }
    private String bandwidth(){String nativeVal=firstStatus("lte_bandwidth","lte_bw","current_bandwidth","dl_bandwidth");
        if(!nativeVal.equals("—"))return nativeVal.toLowerCase(Locale.US).contains("mhz")?nativeVal:nativeVal+" MHz";
        String code=stat("nv_bandwidth");return code.equals("1")?"20 MHz":code.equals("2")?"10 MHz":"—";
    }
    private void networkReconnect(){confirm("Переподключить WAN","Мобильный интернет временно отключится. Продолжить?",()->action("Переподключение",p->api.reconnect(p)));}
    private void bandConfirm(int b){
        String label=b==0?"AUTO BAND":"B"+b;
        confirm("Сменить LTE Band","Применить "+label+"? Роутер перезагрузится. Дождитесь подтверждения LockBandSuc перед перезагрузкой.",
            ()->action("Band Lock "+label,p->api.setBand(b,p),b));
    }
    private String clientSkeletonSignature(){
        StringBuilder b=new StringBuilder(sectionError).append(';');
        // Include the entire blacklist, not only blocked currently-online clients.
        if(blocked==null)b.append("ACL_PENDING;");
        else{
            ArrayList<String> blacklist=new ArrayList<>(blocked.black);
            Collections.sort(blacklist);
            b.append("ACL:").append(blacklist).append(';');
        }
        for(RouterClient.Device d:devices){
            b.append(d.mac).append('|').append(d.ip).append('|')
                .append(prefs.getString("alias_"+d.mac,d.name)).append('|')
                .append(blocked!=null&&blocked.black.contains(d.mac)).append('|')
                .append(qos.limits().get(d.mac)).append(';');
        }
        return b.toString();
    }
    private void updateClientsLive(){
        if(!clientsSignature.equals(clientSkeletonSignature())){draw(true);return;}
        if(qosStatusLive!=null)qosStatusLive.setText("QoS · "+uiQosWatch.status());
        for(RouterClient.Device dev:devices){
            DeviceTrafficMonitor.Rate r=monitorOn?traffic.get(dev.mac):null;
            TextView dl=clientDlValues.get(dev.mac),ul=clientUlValues.get(dev.mac),limitView=clientLimitValues.get(dev.mac);
            if(dl!=null)dl.setText(r==null?"—":humanRate(r.dl));
            if(ul!=null)ul.setText(r==null?"—":humanRate(r.ul));
            if(limitView!=null){Integer value=qos.effectiveLimit(dev.mac);
                boolean over=value!=null&&value>0&&r!=null&&
                    (r.dl>value*1000d*1.30||r.ul>value*1000d*1.30);
                limitView.setText(limitName(value)+(over?" · ↑!":""));
                limitView.setTextColor(value==null?Ui.GREEN:Ui.RED);
            }
        }
    }
    private String smsFingerprint(){
        StringBuilder b=new StringBuilder(sectionError).append(';');
        for(RouterClient.Sms sms:messages)b.append(sms.id).append('|').append(sms.tag)
            .append('|').append(sms.content.hashCode()).append(';');
        return b.toString();
    }
    private void updateSmsLive(){if(!smsSignature.equals(smsFingerprint()))draw(true);}
    private String networkSkeletonSignature(){
        return band()+"/"+stat("ppp_status")+"/"+qos.limits().size();
    }
    private void setNetwork(String key,String text){
        TextView label=networkValues.get(key);
        if(label!=null&&!text.contentEquals(label.getText()))label.setText(text);
    }
    private void updateNetworkLive(){
        if(!networkSignature.equals(networkSkeletonSignature())){draw(true);return;}
        setNetwork("RSRP",reading(rsrp(),"dBm"));setNetwork("RSRQ",reading(rsrq(),"dB"));
        setNetwork("SINR",reading(sinr(),"dB"));setNetwork("RSSI",rssi());
        setNetwork("Bandwidth",bandwidth());
        if(networkValues.get("RSRP")!=null)networkValues.get("RSRP").setTextColor(signalColorForMetric("RSRP"));
        if(networkValues.get("RSRQ")!=null)networkValues.get("RSRQ").setTextColor(signalColorForMetric("RSRQ"));
        if(networkValues.get("SINR")!=null)networkValues.get("SINR").setTextColor(signalColorForMetric("SINR"));
    }
    private void updateSettingsLive(){
        TextView label=settingsValues.get("last_poll");
        if(label!=null){String v=lastCompleteAt==0?"—":android.text.format.DateFormat.format("HH:mm:ss",lastCompleteAt).toString();
            if(!v.contentEquals(label.getText()))label.setText(v);
        }
        TextView current=settingsValues.get("poll_selected");
        if(current!=null&&automaticPolling()){
            String text=String.format(Locale.US,"%.1f с",autoPollMs/1000f);
            if(!text.contentEquals(current.getText()))current.setText(text);
        }
    }
    private void settings(){
        settingsValues.clear();
        addTitle("Параметры","Категории настроек · нажмите для раскрытия");
        addSettingsSection("connection","⌁","Подключение",
            "WebUI, Telnet и авторизация",this::addConnectionSettings);
        addSettingsSection("monitoring","◷","Мониторинг",
            "Автоматически или вручную: 0,5–5 секунд",this::addMonitorSettings);
        addSettingsSection("appearance","◉","Персонализация",
            "Тема, цвет акцента, фон, шрифт и анимации",this::addAppearanceSettings);
        addSettingsSection("traffic","◈","Трафик и устройства",
            "Мониторинг клиентов, приоритет и хозяин",this::addTrafficSettings);
        addSettingsSection("background","◌","Работа в фоне",
            "Служба, уведомления и контроль ограничений",this::addBackgroundSettings);
        addSettingsSection("updates","↻","Обновления",
            "Проверка, установка и периодические обновления",this::addOtaSettings);
        addSettingsSection("diagnostics","⚙","Инструменты и информация",
            "Экспорт диагностики, сброс и безопасность",this::addDiagnosticsInfoSettings);
    }
    private void addSettingsSection(String key,String symbol,String title,String hint,Runnable populate){
        LinearLayout wrapper=Ui.card(this);
        wrapper.setPadding(d(13),d(11),d(13),d(11));
        LinearLayout head=Ui.horizontal(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView emblem=Ui.text(this,symbol,22,Ui.CYAN,true);
        emblem.setGravity(Gravity.CENTER);emblem.setMinWidth(d(39));head.addView(emblem);
        LinearLayout words=Ui.vertical(this);words.setPadding(d(9),0,d(5),0);
        words.addView(Ui.text(this,title,16,Ui.TEXT,true));Ui.addSpace(words,this,4);
        words.addView(Ui.text(this,hint,12,Ui.MUTED,false));
        head.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=Ui.text(this,expandedSettings.contains(key)?"⌃":"⌄",24,Ui.CYAN,true);
        arrow.setGravity(Gravity.CENTER);arrow.setMinWidth(d(30));head.addView(arrow);
        wrapper.addView(head);
        LinearLayout body=Ui.vertical(this);body.setPadding(0,d(12),0,0);
        boolean opened=expandedSettings.contains(key);
        body.setVisibility(opened?View.VISIBLE:View.GONE);wrapper.addView(body);
        if(opened)populateSettings(body,populate);
        head.setOnClickListener(v->{
            if(expandedSettings.contains(key)){
                expandedSettings.remove(key);arrow.setText("⌄");body.animate().cancel();body.setVisibility(View.GONE);
            }else{
                expandedSettings.add(key);arrow.setText("⌃");body.removeAllViews();
                populateSettings(body,populate);body.setVisibility(View.VISIBLE);
                if(Ui.MOTION){body.setAlpha(0f);body.animate().alpha(1f).setDuration(190).start();}
                else body.setAlpha(1f);
            }
            head.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        });
        addCard(wrapper);
    }
    private void populateSettings(LinearLayout body,Runnable populate){
        LinearLayout old=settingsTarget;settingsTarget=body;
        try{populate.run();}finally{settingsTarget=old;}
    }
    private void addConnectionSettings(){
        LinearLayout conn=Ui.card(this);Ui.title(conn,this,"Подключение к роутеру","Адрес AX8 и учётные данные");Ui.addSpace(conn,this,12);
        addRows(conn,new String[][]{{"Адрес WebUI",prefs.getString("host","192.168.150.1")},{"Пользователь",prefs.getString("username","root")},
            {"Telnet",prefs.getString("telnet_user","admin")+" / порт 4719"}});
        Ui.addSpace(conn,this,10);conn.addView(Ui.button(this,"⚙  Изменить подключение",Ui.CARD2,Ui.CYAN,()->editConnection()));addCard(conn);
    }
    private void addMonitorSettings(){
        LinearLayout refresh=Ui.card(this);Ui.title(refresh,this,"Мониторинг","Частота получения данных");Ui.addSpace(refresh,this,12);
        boolean initialAuto=automaticPolling();
        int manual=AutoPollController.clamp(prefs.getInt("refresh_ms",500));
        TextView currentRate=Ui.text(this,String.format(Locale.US,"%.1f с",(initialAuto?autoPollMs:manual)/1000f),17,Ui.CYAN,true);
        currentRate.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        settingsValues.put("poll_selected",currentRate);
        Ui.addSpace(refresh,this,8);
        final TextView[] autoButton={null};
        autoButton[0]=Ui.button(this,initialAuto?"✓  Автоматически":"○  Автоматически",initialAuto?Ui.CYAN:Ui.CARD2,
            initialAuto?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                prefs.edit().putBoolean("refresh_auto",true).apply();
                currentRate.setText(String.format(Locale.US,"%.1f с",autoPollMs/1000f));
                autoButton[0].setText("✓  Автоматически");
                autoButton[0].setTextColor(Ui.contrastOn(Ui.CYAN));
                autoButton[0].setBackground(Ui.shape(this,Ui.CYAN,0,Math.max(4,Ui.CORNER-7)));
                handler.removeCallbacks(ticker);if(running)handler.post(ticker);
            });
        LinearLayout autoRow=Ui.horizontal(this);
        autoRow.addView(autoButton[0],new LinearLayout.LayoutParams(0,-2,1.6f));
        currentRate.setPadding(d(8),0,0,0);
        autoRow.addView(currentRate,new LinearLayout.LayoutParams(0,-2,1f));
        refresh.addView(autoRow);Ui.addSpace(refresh,this,12);
        refresh.addView(Ui.text(this,"РУЧНОЙ ИНТЕРВАЛ",11,Ui.MUTED,true));Ui.addSpace(refresh,this,4);
        SeekBar poll=new SeekBar(this);poll.setMax(45);poll.setProgress((manual-500)/100);
        poll.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        poll.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        refresh.addView(poll);
        LinearLayout scale=Ui.horizontal(this);
        TextView minimum=Ui.text(this,"0,5 с",11,Ui.MUTED,false);
        TextView maximum=Ui.text(this,"5 с",11,Ui.MUTED,false);
        maximum.setGravity(Gravity.END);
        scale.addView(minimum,new LinearLayout.LayoutParams(0,-2,1));
        scale.addView(maximum,new LinearLayout.LayoutParams(0,-2,1));
        refresh.addView(scale);Ui.addSpace(refresh,this,9);
        poll.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){}
            public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                if(fromUser)currentRate.setText(String.format(Locale.US,"%.1f с",(value+5)/10f));
            }
            public void onStopTrackingTouch(SeekBar b){
                int selected=(Math.max(0,Math.min(45,b.getProgress()))+5)*100;
                prefs.edit().putBoolean("refresh_auto",false).putInt("refresh_ms",selected).apply();
                currentRate.setText(String.format(Locale.US,"%.1f с",selected/1000f));
                autoButton[0].setText("○  Автоматически");autoButton[0].setTextColor(Ui.TEXT);
                autoButton[0].setBackground(Ui.shape(MainActivity.this,Ui.CARD2,0,Math.max(4,Ui.CORNER-7)));
                handler.removeCallbacks(ticker);if(running)handler.post(ticker);
                b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        });
        refresh.addView(Ui.text(this,"Автоматически: приложение измеряет длительность полных ответов роутера и выбирает кратчайший устойчивый интервал от 0,5 до 5 секунд. При ошибках интервал увеличивается, после восстановления связи сокращается. Один последовательный цикл для всех данных, без одновременных запросов.",12,Ui.MUTED,false));
        Ui.addSpace(refresh,this,8);
        LinearLayout lastRow=Ui.infoRow(this,"Последний полный опрос",lastCompleteAt==0?"—":android.text.format.DateFormat.format("HH:mm:ss",lastCompleteAt).toString(),Ui.CYAN);
        settingsValues.put("last_poll",(TextView)lastRow.getChildAt(1));refresh.addView(lastRow);
        addCard(refresh);
    }
    private void addDiagnosticsInfoSettings(){
        LinearLayout storage=Ui.card(this);Ui.title(storage,this,"Данные и диагностика","Экспорт настроек для проверки и восстановления");Ui.addSpace(storage,this,11);
        storage.addView(Ui.button(this,"⇧  Поделиться диагностикой",Ui.CARD2,Ui.TEXT,()->exportDiagnostic()));
        Ui.addSpace(storage,this,9);
        storage.addView(Ui.button(this,"↻  Сбросить соединение",Ui.CARD2,Ui.ORANGE,()->{
            api.invalidate();statusAt=0;devices.clear();messages.clear();blocked=null;error="";refresh(true);
        }));Ui.addSpace(storage,this,9);
        storage.addView(Ui.button(this,"⌫  Стереть локальные псевдонимы",Ui.CARD2,Ui.RED,()->confirm("Сброс имён","Удалить локальные имена клиентов?",
            ()->{android.content.SharedPreferences.Editor e=prefs.edit();for(String key:prefs.getAll().keySet())if(key.startsWith("alias_"))e.remove(key);e.apply();draw(true);})));addCard(storage);
        LinearLayout about=Ui.card(this);Ui.title(about,this,"О приложении","AX 8 PRO · "+BuildConfigVersion());Ui.addSpace(about,this,9);
        about.addView(Ui.text(this,"Клиент для OLAX AX8 Pro / Sanechips. Для безопасности все управляющие действия выполняются в локальной сети. Telnet не шифрует пароль и команды.",12,Ui.MUTED,false));
        Ui.addSpace(about,this,12);about.addView(Ui.text(this,"Мониторинг каждого клиента использует Telnet и правила AX8_APP_MON/AX8_APP_MON6. Приоритет может продолжаться в включённом вручную фоновом режиме. Реальное соблюдение лимитов зависит от прошивки AX8, маршрутизации и аппаратного ускорения.",12,Ui.ORANGE,false));
        addCard(about);
    }
    private void addOtaSettings(){
        LinearLayout panel=Ui.card(this);
        Ui.title(panel,this,"Обновления","Установка новых версий приложения");
        Ui.addSpace(panel,this,12);
        panel.addView(Ui.infoRow(this,"Версия",BuildConfigVersion(),Ui.CYAN));
        Ui.addSpace(panel,this,9);
        panel.addView(Ui.button(this,"↻  Проверить обновления",Ui.CARD2,Ui.CYAN,()->checkOta(false)));
        Ui.addSpace(panel,this,9);
        boolean launch=prefs.getBoolean("ota_on_launch",true);
        panel.addView(Ui.button(this,launch?"☑ Проверять при запуске":"☐ Проверять при запуске",Ui.CARD2,Ui.TEXT,()->{
            prefs.edit().putBoolean("ota_on_launch",!launch).apply();draw(true);
        }));
        Ui.addSpace(panel,this,9);
        boolean background=prefs.getBoolean("ota_background",false);
        panel.addView(Ui.button(this,background?"☑ Проверять в фоне (24 ч)":"☐ Проверять в фоне (24 ч)",Ui.CARD2,Ui.TEXT,()->{
            prefs.edit().putBoolean("ota_background",!background).apply();UpdateSchedule.configure(this);draw(true);
        }));
        Ui.addSpace(panel,this,9);
        panel.addView(Ui.text(this,"Перед установкой проверяются версия, пакет, контрольная сумма APK и цифровая подпись приложения.",12,Ui.MUTED,false));
        addCard(panel);
    }
    private String BuildConfigVersion(){
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
        catch(Exception ignored){return "1.5.3";}
    }
    private boolean startupOtaChecked=false,otaCheckBusy=false;
    private void checkOta(boolean silent){
        if(isFinishing()||isDestroyed()||otaCheckBusy)return;
        otaCheckBusy=true;
        final OtaProgressDialog ui=silent?null:new OtaProgressDialog(this);
        if(ui!=null){ui.show();ui.stage("Проверка обновлений…","Подключаемся к серверу");}
        UpdateManager.check(this,new UpdateManager.Listener(){
            @Override public void status(String message){
                if(ui!=null)ui.stage(message,"Проверяем новую версию");
            }
            @Override public void available(UpdateManager.Release release,boolean newer){
                otaCheckBusy=false;
                if(isFinishing()||isDestroyed())return;
                if(!newer){if(ui!=null)ui.current("Версия "+BuildConfigVersion());return;}
                OtaProgressDialog dialog=ui!=null?ui:new OtaProgressDialog(MainActivity.this);
                if(ui==null)dialog.show();
                dialog.offer(release.versionName,()->downloadOta(release,dialog));
            }
            @Override public void downloaded(java.io.File apk){}
            @Override public void error(String message){
                otaCheckBusy=false;
                if(ui!=null&&!isFinishing()&&!isDestroyed())
                    ui.fail(message,()->{ui.dismiss();checkOta(false);});
            }
        });
    }
    private void downloadOta(UpdateManager.Release release,OtaProgressDialog dialog){
        dialog.stage("Подготовка загрузки…","Проверка обновления");
        UpdateManager.download(this,release,new UpdateManager.Listener(){
            @Override public void status(String message){if(!isFinishing()&&!isDestroyed())dialog.stage(message,"Защищённое соединение");}
            @Override public void available(UpdateManager.Release r,boolean newer){}
            @Override public void progress(long downloaded,long total){
                if(!isFinishing()&&!isDestroyed())dialog.downloading(downloaded,total);
            }
            @Override public void downloaded(java.io.File apk){
                if(isFinishing()||isDestroyed())return;
                dialog.dismiss();UpdateManager.install(MainActivity.this);
            }
            @Override public void error(String message){
                if(!isFinishing()&&!isDestroyed())dialog.fail(message,()->downloadOta(release,dialog));
            }
        });
    }
    private void startBackgroundMode(){
        prefs.edit().putBoolean("background_mode",true).putBoolean("ui_visible",true).apply();
        try{
            Intent intent=new Intent(this,RouterBackgroundService.class).setAction(RouterBackgroundService.ACTION_START);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},8064);
            toast("Фоновый режим запущен. Состояние видно в уведомлении.");draw(true);
        }catch(Exception e){prefs.edit().putBoolean("background_mode",false).apply();alert("Не удалось запустить службу",e.toString());draw(true);}
    }
    private void stopBackgroundMode(){
        prefs.edit().putBoolean("background_mode",false).putBoolean("background_priority",false).apply();
        stopService(new Intent(this,RouterBackgroundService.class));
        toast("Фоновое управление остановлено");draw(true);
    }
    private void addBackgroundSettings(){
        boolean active=prefs.getBoolean("background_mode",false);
        LinearLayout c=Ui.card(this);Ui.title(c,this,"Фоновый режим","Мониторинг и контроль роутера после сворачивания и при блокировке экрана");
        Ui.addSpace(c,this,13);
        c.addView(Ui.button(this,active?"● Остановить фоновый режим":"▶ Включить фоновый режим",
            active?Ui.CARD2:Ui.CYAN,active?Ui.ORANGE:Ui.contrastOn(Ui.CYAN),()->{
                if(active)confirm("Остановить службу?","Фоновый опрос и временный приоритет перестанут поддерживаться.",()->stopBackgroundMode());
                else confirm("Фоновый мониторинг","Android покажет постоянное уведомление. При блокировке возможен повышенный расход батареи. Продолжить?",()->startBackgroundMode());
            }));
        Ui.addSpace(c,this,10);
        boolean awake=prefs.getBoolean("background_keep_awake",true);
        c.addView(Ui.button(this,awake?"☑ Не засыпать в фоне (CPU + Wi-Fi)":"☐ Не засыпать в фоне (CPU + Wi-Fi)",Ui.CARD2,Ui.TEXT,()->{
            prefs.edit().putBoolean("background_keep_awake",!awake).apply();draw(true);
        }));
        Ui.addSpace(c,this,9);
        boolean watch=prefs.getBoolean("background_qos_watch",true);
        c.addView(Ui.button(this,watch?"☑ Контроль превышения QoS":"☐ Контроль превышения QoS",Ui.CARD2,Ui.YELLOW,()->{
            prefs.edit().putBoolean("background_qos_watch",!watch).apply();draw(true);
        }));
        Ui.addSpace(c,this,11);
        if(active){
            c.addView(Ui.button(this,"✉  Проверить SMS-уведомление",Ui.CARD2,Ui.CYAN,()->{
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    !=android.content.pm.PackageManager.PERMISSION_GRANTED){
                    requestNotificationPermission();toast("Разрешите уведомления приложению и повторите проверку");return;
                }
                try{startService(new Intent(this,RouterBackgroundService.class)
                    .setAction(RouterBackgroundService.ACTION_TEST_SMS));
                    toast("Запрос отправлен. Проверьте шторку уведомлений.");
                }catch(Exception ex){alert("Ошибка теста уведомления",ex.toString());}
            }));
            Ui.addSpace(c,this,9);
        }
        String noticeError=prefs.getString("bg_notification_error","");
        if(!noticeError.isEmpty())c.addView(Ui.text(this,"Уведомления: "+noticeError,12,Ui.RED,false));
        Ui.addSpace(c,this,11);
        long last=prefs.getLong("bg_last_poll",0);
        c.addView(Ui.infoRow(this,"Фоновая служба",RouterBackgroundService.isRunning()?"Запущена":"Не запущена",
            RouterBackgroundService.isRunning()?Ui.GREEN:Ui.ORANGE));
        c.addView(Ui.infoRow(this,"Последний фоновый опрос",last<=0?"—":android.text.format.DateFormat.format("HH:mm:ss",last).toString(),Ui.CYAN));
        long smsLast=prefs.getLong("bg_sms_at",0);
        c.addView(Ui.infoRow(this,"Последняя проверка SMS",smsLast<=0?"—":android.text.format.DateFormat.format("HH:mm:ss",smsLast).toString(),Ui.CYAN));
        long notificationLast=prefs.getLong("bg_last_notification_at",0);
        c.addView(Ui.infoRow(this,"Последнее уведомление",notificationLast<=0?"—":android.text.format.DateFormat.format("HH:mm:ss",notificationLast).toString(),Ui.CYAN));
        c.addView(Ui.infoRow(this,"Клиентов / SMS",prefs.getInt("bg_clients",0)+" / "+prefs.getInt("bg_sms",0),Ui.TEXT));
        c.addView(Ui.infoRow(this,"Контроль QoS",prefs.getString("bg_qos","Не проверено"),Ui.YELLOW));
        String e=prefs.getString("bg_error","");if(!e.isEmpty()){
            Ui.addSpace(c,this,9);c.addView(Ui.text(this,"Ошибка фона: "+e,12,Ui.RED,false));
        }
        Ui.addSpace(c,this,9);
        c.addView(Ui.text(this,"Служба работает только после ручного включения. Android может остановить её при принудительном завершении, отключении Wi-Fi и в режиме энергосбережения. Для надёжности настройте исключение из оптимизации батареи в системе.",12,Ui.MUTED,false));
        addCard(c);
    }
    private void editConnection(){
        LinearLayout stack=Ui.vertical(this);stack.setPadding(d(20),d(12),d(20),d(5));
        EditText host=settingField(stack,"Адрес или IP",prefs.getString("host","192.168.150.1"),false);
        EditText username=settingField(stack,"WebUI логин",prefs.getString("username","root"),false);
        EditText password=settingField(stack,"WebUI пароль",prefs.getString("password","admin"),true);
        EditText telnetUser=settingField(stack,"Telnet логин",prefs.getString("telnet_user","admin"),false);
        EditText telnetPass=settingField(stack,"Telnet пароль",prefs.getString("telnet_pass","admin"),true);
        Ui.modal(this,"Настройки подключения","Пароли используются только для подключения к AX8",stack,"Сохранить",()->{
            String h=host.getText().toString().trim();if(h.isEmpty()||h.contains(" ")){toast("Укажите корректный адрес");return;}
            prefs.edit().putString("host",h).putString("username",username.getText().toString())
                .putString("password",password.getText().toString()).putString("telnet_user",telnetUser.getText().toString())
                .putString("telnet_pass",telnetPass.getText().toString()).apply();
            qos.close();traffic.close();api.configure(h,username.getText().toString(),password.getText().toString());
            qos=new QosController(api,prefs);traffic=new DeviceTrafficMonitor(api,prefs);
            error="";blocked=null;status=null;statusAt=clientsAt=smsAt=0;refresh(true);draw(true);
        });
    }
    private EditText settingField(LinearLayout parent,String title,String value,boolean secret){
        parent.addView(Ui.text(this,title,12,Ui.MUTED,true));Ui.addSpace(parent,this,6);
        EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setTextSize(15);e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e);Ui.addSpace(parent,this,10);return e;
    }
    private void addTrafficSettings(){
        LinearLayout c=Ui.card(this);Ui.title(c,this,"Трафик устройств","Счётчики IPv4/IPv6 и адаптивный приоритет хозяина");
        Ui.addSpace(c,this,12);
        c.addView(Ui.button(this,monitorOn?"● Мониторинг клиентов ВКЛ":"○ Мониторинг клиентов ВЫКЛ",
            monitorOn?Ui.CYAN:Ui.CARD2,monitorOn?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                boolean turnOn=!monitorOn;
                if(turnOn){confirm("Включить мониторинг","На AX8 будут созданы цепочки AX8_APP_MON и AX8_APP_MON6 через Telnet. Может увеличить нагрузку.",()->{
                    monitorOn=true;prefs.edit().putBoolean("traffic_monitor",true).apply();draw(true);refresh(true);
                });}
                else{monitorOn=false;prefs.edit().putBoolean("traffic_monitor",false).apply();
                    action("Отключение мониторинга",pr->{
                        if(qos.priorityActive())qos.applyPriority(ownerMac,null,api.devices(),pr);
                        priority.disable();traffic.stop();
                    });
                }
            }));
        Ui.addSpace(c,this,9);
        c.addView(Ui.button(this,"⚡ Приоритет хозяина и массовые лимиты",Ui.CARD2,Ui.TEXT,()->bulkControls()));
        Ui.addSpace(c,this,9);
        c.addView(Ui.infoRow(this,"Мой MAC",ownerMac.isEmpty()?"не выбран":ownerMac,Ui.CYAN));
        boolean autoOwner=prefs.getBoolean("auto_owner",true);
        c.addView(Ui.button(this,autoOwner?"☑ Автовыбор хозяина":"☐ Автовыбор хозяина",Ui.CARD2,Ui.TEXT,()->{
            boolean next=!prefs.getBoolean("auto_owner",true);
            prefs.edit().putBoolean("auto_owner",next).apply();
            if(next)autoSelectOwner(true);
            draw(true);
        }));
        Ui.addSpace(c,this,8);
        c.addView(Ui.infoRow(this,"Приоритет",priority.enabled?priority.mode:"выключен",priority.enabled?Ui.GREEN:Ui.MUTED));
        if(monitorOn){
            Ui.addSpace(c,this,9);
            c.addView(Ui.text(this,"Монитор: "+(trafficError.isEmpty()?traffic.status():trafficError),12,trafficError.isEmpty()?Ui.MUTED:Ui.ORANGE,false));
            Ui.addSpace(c,this,8);
            c.addView(Ui.button(this,"◎ Проверить iptables на AX8",Ui.CARD2,Ui.CYAN,()->{
                workers.execute(()->{
                    String report=traffic.diagnose();
                    handler.post(()->{
                        LinearLayout body=Ui.vertical(this);
                        TextView result=Ui.text(this,report,12,Ui.TEXT,false);
                        result.setTextIsSelectable(true);body.addView(result);
                        Ui.sheet(this,"Диагностика монитора","Проверка mangle / filter",body);
                    });
                });
            }));
        }
        addCard(c);
    }
    private void addAppearanceSettings(){
        LinearLayout theme=Ui.card(this);Ui.title(theme,this,"Персонализация","Тема, цвета, шрифт и форма интерфейса");
        Ui.addSpace(theme,this,15);
        LinearLayout themes=Ui.vertical(this);
        String[] keys={"dark","amoled","slate","light"};String[] labels={"Тёмная","AMOLED","Графит","Светлая"};
        for(int i=0;i<4;i+=2){LinearLayout line=Ui.horizontal(this);
            for(int j=i;j<i+2;j++){final String key=keys[j];boolean active=key.equals(prefs.getString("theme","dark"));
                TextView v=Ui.button(this,labels[j],active?Ui.CYAN:Ui.CARD2,active?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                    prefs.edit().putString("theme",key).remove("background_color").remove("card_color").apply();applyAppearance();
                });LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,d(48),1);l.rightMargin=d(8);line.addView(v,l);
            }
            themes.addView(line);Ui.addSpace(themes,this,8);
        }
        theme.addView(themes);Ui.addSpace(theme,this,10);
        theme.addView(Ui.button(this,"◉  Цвет акцента",Ui.CARD2,Ui.CYAN,()->chooseAccentPalette()));
        Ui.addSpace(theme,this,8);
        theme.addView(Ui.button(this,"▣  Цвет фона",Ui.CARD2,Ui.TEXT,()->pickColor("background_color","Цвет фона",Ui.BG)));
        Ui.addSpace(theme,this,8);
        theme.addView(Ui.button(this,"▤  Цвет карточек",Ui.CARD2,Ui.TEXT,()->pickColor("card_color","Цвет карточек",Ui.CARD)));
        Ui.addSpace(theme,this,13);
        theme.addView(Ui.button(this,"Aa  Шрифт · "+fontLabel(prefs.getString("font","sans-serif")),Ui.CARD2,Ui.TEXT,()->chooseFont()));
        Ui.addSpace(theme,this,12);
        theme.addView(Ui.infoRow(this,"Масштаб текста",prefs.getInt("font_scale",100)+"%",Ui.CYAN));
        SeekBar size=new SeekBar(this);size.setMax(55);size.setProgress(prefs.getInt("font_scale",100)-80);
        size.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean fromUser){}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){prefs.edit().putInt("font_scale",80+b.getProgress()).apply();applyAppearance();}
        });theme.addView(size);
        theme.addView(Ui.infoRow(this,"Скругления",prefs.getInt("corner",22)+" dp",Ui.CYAN));
        SeekBar corner=new SeekBar(this);corner.setMax(34);corner.setProgress(Math.max(0,prefs.getInt("corner",22)-6));
        corner.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean fromUser){}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){prefs.edit().putInt("corner",6+b.getProgress()).apply();applyAppearance();}
        });theme.addView(corner);Ui.addSpace(theme,this,10);
        theme.addView(Ui.button(this,(Ui.MOTION?"●":"○")+"  Анимации · "+(Ui.MOTION?"ВКЛ":"ВЫКЛ"),Ui.CARD2,Ui.TEXT,()->{
            prefs.edit().putBoolean("motion",!Ui.MOTION).apply();applyAppearance();
        }));Ui.addSpace(theme,this,8);
        theme.addView(Ui.button(this,(Ui.COMPACT?"●":"○")+"  Компактные карточки · "+(Ui.COMPACT?"ВКЛ":"ВЫКЛ"),Ui.CARD2,Ui.TEXT,()->{
            prefs.edit().putBoolean("compact",!Ui.COMPACT).apply();applyAppearance();
        }));Ui.addSpace(theme,this,8);
        theme.addView(Ui.button(this,"↺  Сбросить оформление",Ui.CARD2,Ui.ORANGE,()->confirm("Сброс оформления","Восстановить стандартную тёмную тему и цвета?",()->{
            prefs.edit().remove("theme").remove("accent_color").remove("background_color").remove("card_color")
                .remove("font").remove("font_scale").remove("corner").remove("motion").remove("compact").apply();applyAppearance();
        })));
        addCard(theme);
    }
    private void chooseAccentPalette(){
        final int[] colors={
            0xff55dfcb,0xff43baf5,0xff658aff,0xffac8dff,0xffdb91ff,0xffff77ce,
            0xffff7085,0xffff936d,0xffffba63,0xffffd45a,0xffd0e46d,0xff90df79,
            0xff57cda3,0xff4ad5de,0xff35a6de,0xff8773e4,0xffba6ef6,0xffed77a3,
            0xffe75f5f,0xffd39c78,0xffa3bbc8,0xffe8edf5,0xff76a4ab,0xff9a86ab
        };
        final String[] names={
            "Мята","Лазурь","Сапфир","Лаванда","Сирень","Фуксия",
            "Коралл","Персик","Янтарь","Лимон","Лайм","Весна",
            "Изумруд","Бирюза","Океан","Ирис","Аметист","Пион",
            "Рубин","Бронза","Сталь","Лёд","Шалфей","Слива"
        };
        LinearLayout body=Ui.vertical(this);final Dialog[] dlog={null};
        body.addView(Ui.text(this,"Выбор применяется ко всем акцентным элементам интерфейса.",12,Ui.MUTED,false));
        Ui.addSpace(body,this,12);
        for(int i=0;i<colors.length;i+=3){
            LinearLayout row=Ui.horizontal(this);
            for(int j=i;j<Math.min(i+3,colors.length);j++){
                final int value=colors[j];
                LinearLayout item=Ui.vertical(this);item.setGravity(Gravity.CENTER);item.setPadding(d(3),d(5),d(3),d(5));
                TextView swatch=Ui.text(this,Ui.CYAN==value?"✓":"●",20,Ui.contrastOn(value),true);
                swatch.setGravity(Gravity.CENTER);swatch.setMinHeight(d(38));swatch.setBackground(Ui.shape(this,value,0,12));
                item.addView(swatch,Ui.lp(this,-1,40));
                TextView text=Ui.text(this,names[j],10,Ui.TEXT,false);text.setGravity(Gravity.CENTER);
                Ui.addSpace(item,this,5);item.addView(text);
                item.setOnClickListener(v->{prefs.edit().putInt("accent_color",value).apply();dlog[0].dismiss();applyAppearance();});
                LinearLayout.LayoutParams w=new LinearLayout.LayoutParams(0,-2,1);w.setMargins(d(2),0,d(2),0);row.addView(item,w);
            }
            body.addView(row);Ui.addSpace(body,this,5);
        }
        dlog[0]=Ui.sheet(this,"Цвет акцента","24 акцентных оттенка",body);
    }
    private String fontLabel(String key){
        if(key.equals("monospace"))return "Моноширинный";
        if(key.equals("serif"))return "С засечками";
        if(key.equals("sans-serif-condensed"))return "Узкий";
        if(key.equals("sans-serif-light"))return "Лёгкий";
        if(key.equals("sans-serif-medium"))return "Medium";
        return "Стандартный";
    }
    private void chooseFont(){
        String[] fonts={"sans-serif","sans-serif-medium","sans-serif-light","sans-serif-condensed","serif","monospace"};
        LinearLayout col=Ui.vertical(this);final Dialog[] sheet={null};
        for(String font:fonts){boolean selected=font.equals(prefs.getString("font","sans-serif"));
            TextView button=Ui.button(this,(selected?"✓  ":"Aa  ")+fontLabel(font),selected?Ui.CYAN:Ui.CARD2,selected?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                sheet[0].dismiss();prefs.edit().putString("font",font).apply();applyAppearance();
            });col.addView(button);Ui.addSpace(col,this,8);
        }sheet[0]=Ui.sheet(this,"Выбор шрифта","Системные гарнитуры Android",col);
    }
    private void pickColor(String prefKey,String heading,int current){
        LinearLayout body=Ui.vertical(this);int[] rgb={android.graphics.Color.red(current),android.graphics.Color.green(current),android.graphics.Color.blue(current)};
        TextView preview=Ui.text(this,String.format(Locale.US,"#%06X",current&0xFFFFFF),17,Ui.contrastOn(current),true);
        preview.setGravity(Gravity.CENTER);preview.setMinHeight(d(65));preview.setBackground(Ui.shape(this,current,Ui.BORDER,14));
        body.addView(preview);Ui.addSpace(body,this,10);
        for(int i=0;i<3;i++){
            int index=i;String name=new String[]{"Красный","Зелёный","Синий"}[i];
            TextView lab=Ui.text(this,name+"  "+rgb[i],13,Ui.TEXT,true);body.addView(lab);
            SeekBar seek=new SeekBar(this);seek.setMax(255);seek.setProgress(rgb[i]);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar bar,int value,boolean user){
                    rgb[index]=value;int color=android.graphics.Color.rgb(rgb[0],rgb[1],rgb[2]);
                    preview.setText(String.format(Locale.US,"#%06X",color&0xFFFFFF));preview.setTextColor(Ui.contrastOn(color));
                    preview.setBackground(Ui.shape(MainActivity.this,color,Ui.BORDER,14));lab.setText(name+"  "+value);
                }
                public void onStartTrackingTouch(SeekBar b){}
                public void onStopTrackingTouch(SeekBar b){}
            });body.addView(seek);
        }
        final Dialog[] dialog={null};
        body.addView(Ui.button(this,"✓  Применить цвет",Ui.CARD2,Ui.CYAN,()->{
            prefs.edit().putInt(prefKey,android.graphics.Color.rgb(rgb[0],rgb[1],rgb[2])).apply();
            dialog[0].dismiss();applyAppearance();
        }));
        Ui.addSpace(body,this,8);body.addView(Ui.button(this,"↺  Сбросить цвет",Ui.CARD2,Ui.ORANGE,()->{
            prefs.edit().remove(prefKey).apply();dialog[0].dismiss();applyAppearance();
        }));
        dialog[0]=Ui.sheet(this,heading,"Регулировка RGB, 0–255",body);
    }
    private void bulkControls(){
        LinearLayout body=Ui.vertical(this);final Dialog[] sheet={null};
        body.addView(Ui.text(this,"Выбери управляющее устройство. На него не распространяются массовые лимиты и автоматические ограничения.",13,Ui.MUTED,false));
        Ui.addSpace(body,this,14);
        body.addView(Ui.button(this,"⌂  Хозяин: "+(ownerMac.isEmpty()?"не выбран":ownerMac),Ui.CARD2,Ui.CYAN,()->{
            sheet[0].dismiss();chooseOwner();
        }));
        Ui.addSpace(body,this,11);
        body.addView(Ui.button(this,"◈  Лимит всем остальным",Ui.CARD2,Ui.TEXT,()->{
            sheet[0].dismiss();if(ownerMac.isEmpty()){toast("Сначала выберите свой MAC");chooseOwner();return;}
            chooseBulkLimit();
        }));Ui.addSpace(body,this,9);
        body.addView(Ui.button(this,"✓  Снять массовые лимиты",Ui.CARD2,Ui.ORANGE,()->{
            sheet[0].dismiss();confirm("Снятие массовых лимитов","Будут сняты сохранённые ручные лимиты со всех клиентов кроме хозяина.",
                ()->action("Снятие массовых лимитов",pr->qos.limitEveryoneExcept(ownerMac,null,api.devices(),pr)));
        }));Ui.addSpace(body,this,13);
        body.addView(Ui.button(this,priority.enabled?"● Адаптивный приоритет ВКЛ":"○ Адаптивный приоритет ВЫКЛ",
            priority.enabled?Ui.CYAN:Ui.CARD2,priority.enabled?Ui.contrastOn(Ui.CYAN):Ui.GREEN,()->{
            sheet[0].dismiss();if(priority.enabled){
                action("Отключение приоритета",pr->{qos.applyPriority(ownerMac,null,api.devices(),pr);priority.disable();});
            }else{
                if(ownerMac.isEmpty()){toast("Выберите хозяина");chooseOwner();return;}
                if(!monitorOn){toast("Включите мониторинг трафика в настройках");return;}
                priority.enable(ownerMac);toast("Приоритет включён на время работы приложения");draw(true);
            }
        }));Ui.addSpace(body,this,9);
        body.addView(Ui.infoRow(this,"Режим",priority.mode,Ui.CYAN));
        body.addView(Ui.infoRow(this,"Текущий AUTO лимит",limitName(priority.cap),Ui.TEXT));
        body.addView(Ui.infoRow(this,"Трафик хозяина",humanRate(priority.ownerRate),Ui.GREEN));
        body.addView(Ui.infoRow(this,"Трафик остальных",humanRate(priority.othersRate),Ui.ORANGE));
        sheet[0]=Ui.sheet(this,"Управление клиентами","Массовый QoS · приоритет",body);
    }
    private void chooseOwner(){
        LinearLayout body=Ui.vertical(this);final Dialog[] sheet={null};
        body.addView(Ui.button(this,prefs.getBoolean("auto_owner",true)?"☑ Автовыбор хозяина":"☐ Автовыбор хозяина",Ui.CARD2,Ui.CYAN,()->{
            prefs.edit().putBoolean("auto_owner",!prefs.getBoolean("auto_owner",true)).apply();
            if(prefs.getBoolean("auto_owner",true))autoSelectOwner(true);
            if(sheet[0]!=null)sheet[0].dismiss();
            chooseOwner();
        }));
        Ui.addSpace(body,this,10);
        if(devices.isEmpty())body.addView(Ui.text(this,"Нет доступных устройств. Обновите клиентов.",13,Ui.MUTED,false));
        for(RouterClient.Device dev:devices){
            // Do not present stale DHCP records without an active IPv4 address as owners.
            if(dev.ip==null||!dev.ip.matches("[0-9]+(\\.[0-9]+){3}"))continue;
            boolean selected=dev.mac.equals(ownerMac);
            body.addView(Ui.button(this,(selected?"✓  ":"")+prefs.getString("alias_"+dev.mac,dev.name)+" · "+dev.ip,
                selected?Ui.CYAN:Ui.CARD2,selected?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                    OwnerIdentity.Result identity=OwnerIdentity.resolve(MainActivity.this,devices,blocked,dev.mac);
                    if(identity.resolved()&&!identity.mac.equals(dev.mac)){
                        alert("Неверное управляющее устройство","IP текущего телефона соответствует другому клиенту: "+identity.mac+". Выбор отменён.");
                        return;
                    }
                    if(!identity.resolved()&&!identity.manualSelectionPossible){
                        alert("Не удалось подтвердить устройство",identity.reason);return;
                    }
                    confirm("Назначить мой телефон?",friendlyName(dev)+" · "+dev.ip+"\nMAC: "+dev.mac+
                        "\n\nЭтот клиент будет исключён из массовых ограничений и временных блокировок. Убедитесь, что это телефон с приложением.",()->{
                            if(sheet[0]!=null)sheet[0].dismiss();
                            ownerMac=dev.mac;prefs.edit().putString("owner_mac",ownerMac).apply();
                            if(priority.enabled){priority.disable();action("Переназначение хозяина",pr->qos.applyPriority("",null,api.devices(),pr));}
                            else bulkControls();
                        });
                }));Ui.addSpace(body,this,8);
        }
        sheet[0]=Ui.sheet(this,"Кто хозяин?","Выбранный MAC защищён от массового QoS. Автовыбор использует текущее Wi‑Fi подключение телефона.",body);
    }
    private void autoSelectOwner(boolean showToast){
        if(!prefs.getBoolean("auto_owner",true)||devices.isEmpty())return;
        try{
            OwnerIdentity.Result identity=OwnerIdentity.resolve(this,devices,blocked,ownerMac);
            if(identity.resolved()&&!identity.mac.equals(ownerMac)){
                ownerMac=identity.mac;
                prefs.edit().putString("owner_mac",ownerMac).apply();
                if(showToast)toast("Хозяин определён автоматически: "+ownerMac);
            }
        }catch(Exception ignored){}
    }

    private void chooseBulkLimit(){
        Integer[] values={null,0,15,20,30,50,100,250,300,500,750,1000,2000,5000,10000};
        LinearLayout body=Ui.vertical(this);final Dialog[] sheet={null};
        for(int i=0;i<values.length;i+=3){LinearLayout line=Ui.horizontal(this);
            for(int j=i;j<Math.min(i+3,values.length);j++){
                Integer limit=values[j];TextView b=Ui.button(this,limit==null?"Без лимита":limitName(limit),Ui.CARD2,Ui.CYAN,()->{
                    sheet[0].dismiss();confirm("Массовый лимит","Применить "+limitName(limit)+" ко всем кроме "+ownerMac+"?",
                        ()->action("Массовый QoS",pr->qos.limitEveryoneExcept(ownerMac,limit,api.devices(),pr)));
                });LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(54),1);lp.rightMargin=d(6);line.addView(b,lp);
            }body.addView(line);Ui.addSpace(body,this,8);
        }
        Ui.addSpace(body,this,8);body.addView(Ui.text(this,"Свой лимит · ползунок 0–20000 KB/s",13,Ui.MUTED,true));
        TextView customText=Ui.text(this,"500 KB/s",17,Ui.CYAN,true);body.addView(customText);
        SeekBar custom=new SeekBar(this);custom.setMax(20000);custom.setProgress(500);
        custom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int v,boolean user){customText.setText(limitName(v));}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });body.addView(custom);Ui.addSpace(body,this,8);
        body.addView(Ui.button(this,"✓  Применить свой лимит",Ui.CARD2,Ui.CYAN,()->{
            int limit=custom.getProgress();sheet[0].dismiss();
            confirm("Массовый QoS","Установить "+limitName(limit)+" для всех кроме "+ownerMac+"?",
                ()->action("Массовый QoS",pr->qos.limitEveryoneExcept(ownerMac,limit,api.devices(),pr)));
        }));
        sheet[0]=Ui.sheet(this,"Лимит для остальных","Загрузка и отдача · IPv4/IPv6",body);
    }
    private void exportDiagnostic(){
        try {JSONObject j=new JSONObject();j.put("time",System.currentTimeMillis());j.put("host",api.host());
            j.put("radio",status==null?JSONObject.NULL:status);j.put("devices",devices.size());
            j.put("sms_count",messages.size());j.put("block_count",blocked==null?JSONObject.NULL:blocked.black.size());
            j.put("limits",new JSONObject(qos.limits()));j.put("qos",qos.diagnostics());j.put("connection_error",error);
            j.put("refresh_ms",refreshInterval());j.put("theme",prefs.getString("theme","dark"));
            j.put("traffic_monitor",monitorOn);j.put("traffic_error",trafficError);
            j.put("owner_mac",ownerMac);j.put("priority_mode",priority.mode);
            Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,j.toString(2));
            startActivity(Intent.createChooser(i,"Экспорт диагностики AX8"));
        }catch(Exception e){toast(e.toString());}
    }
}