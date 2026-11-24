package ru.xarkon.ax8;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.provider.ContactsContract;
import android.database.Cursor;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
    // Timed Wi-Fi actions must never occupy the single-flight status polling queue.
    private final ExecutorService temporaryWorker=Executors.newSingleThreadExecutor();
    // Internet tests never delay the WebUI/SMS/QoS polling worker.
    private final ExecutorService internetWorker=Executors.newSingleThreadExecutor();
    // Heavy on-demand diagnostics use their own authenticated read-only connection.
    private final ExecutorService diagnosticWorker=Executors.newSingleThreadExecutor();
    private volatile boolean diagnosticBusy=false;
    private boolean internetBusy=false,internetWatching=false,internetChangeScheduled=false;
    private long internetLastStart=0L,internetCheckedAt=0L,internetRevision=0L;
    private int internetResult=InternetStatusPolicy.UNKNOWN;
    private String internetNetworkSignature="";
    private final Runnable internetChange=()->{
        internetChangeScheduled=false;
        if(!MainActivity.this.running)return;
        String signature=routerNetworkFingerprint();
        // Repeated capability callbacks must not invalidate a still-valid probe.
        if(signature.equals(internetNetworkSignature))return;
        internetNetworkSignature=signature;
        // A WebUI poll may still be running on the previous physical link.
        MainActivity.this.refreshSession.invalidate();
        statusAt=0L;
        lastBytesAt=0L;lastRx=Double.NaN;lastTx=Double.NaN;
        fallbackDl=Double.NaN;fallbackUl=Double.NaN;
        resetInternetResult();
        updateConnection();updateInternetChip();
        refresh(true);
    };
    private final ConnectivityManager.NetworkCallback internetCallback=
        new ConnectivityManager.NetworkCallback(){
            private void changed(){
                handler.post(()->{
                    if(!running||internetChangeScheduled)return;
                    // Fixed delay: event storms cannot indefinitely postpone checking.
                    internetChangeScheduled=true;
                    handler.postDelayed(internetChange,450L);
                });
            }
            @Override public void onAvailable(Network network){changed();}
            @Override public void onLost(Network network){changed();}
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities c){changed();}
            @Override public void onLinkPropertiesChanged(Network network,android.net.LinkProperties lp){changed();}
        };
    private final ArrayDeque<Float> historyDl=new ArrayDeque<>();
    private final ArrayDeque<Float> historyUl=new ArrayDeque<>();
    private RouterClient api;
    private QosController qos;
    private DeviceTrafficMonitor traffic;
    // UI-owned immutable snapshot; never read the worker's changing rate map.
    private Map<String,DeviceTrafficMonitor.Rate> displayedRates=Collections.emptyMap();
    private List<RouterClient.Device> ratedDevices;
    private long ratedGeneration=-1;
    private DeviceTrafficMonitor.Rate displayedRate(RouterClient.Device dev){
        return monitorOn && ratedDevices==devices && devices.contains(dev)
            && !clientListStale() && refreshSession.accepts(ratedGeneration,running)
            ? displayedRates.get(dev.mac):null;
    }
    private final OwnerPriorityController priority=new OwnerPriorityController();
    private final QosWatchdog uiQosWatch=new QosWatchdog();
    private final BandSwitchTracker bandTracker=new BandSwitchTracker();
    private volatile boolean monitorOn=false;
    private volatile long trafficMonitorEpoch=0;
    private String ownerMac="";
    private final java.util.Set<String> expandedSettings=new java.util.LinkedHashSet<>();
    private LinearLayout settingsTarget=null;
    private long lastCompleteAt=0,lastDrawAt=0;
    private int autoPollMs=500;
    private String trafficError="";

    private android.content.SharedPreferences prefs;
    private LinearLayout root,content,bar,nav,header;
    private ScrollView scroll;
    private View themeAnchorView;
    private String themeAnchorKey,pendingThemeAnchorKey;
    private int pendingThemeAnchorOffset;
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
    private BatteryIndicator overviewBattery;
    private Ui.SpeedGraph overviewGraph;
    private String sectionError="";
    private boolean knownClientsExpanded=false,savedLimitsExpanded=false;
    private final Set<String> expandedRememberedGroups=new HashSet<>();
    // Selection is transient and refers to a complete remembered-name group, not a single IP.
    private final Set<String> selectedClientGroups=new LinkedHashSet<>();
    private final Set<String> expandedRuleGroups=new HashSet<>();
    private List<RouterClient.Device> devices=new ArrayList<>();
    private List<RouterClient.Sms> messages=new ArrayList<>();
    private RouterClient.BlockState blocked;
    private volatile boolean actionBusy=false,refreshBusy=false,temporaryAction=false;
    private volatile boolean operatorScanBusy=false;
    private volatile boolean running=false;
    private final RefreshSession refreshSession=new RefreshSession();
    private int tab=0,lastNavTab=-1,lastNavUnread=-1;
    private final int[] tabScrollY=new int[TABS.length];
    private int pendingScrollY=-1,scrollRestoreRevision;
    // Preserve relative reading position when compact cards change the page height.
    private float pendingCompactScrollFraction=-1f;
    private String error="",notice="";
    private long statusAt=0,clientsAt=0,smsAt=0,blockedAt=0,lastBytesAt=0;
    private double lastRx=Double.NaN,lastTx=Double.NaN,fallbackDl=Double.NaN,fallbackUl=Double.NaN;
    private final Runnable ticker=new Runnable(){@Override public void run(){
        if(!running)return;
        // Do not present expired client, SMS or ACL snapshots as live readings.
        if(expireStaleSections(System.currentTimeMillis())){rebuildNav();draw(true);}
        refresh(false);
        maybeProbeInternet(false);
        updateInternetChip();
        // Never hammer a router WebUI while it is still booting.
        handler.postDelayed(this,refreshInterval());
    }};
    private int d(float x){return Ui.dp(this,x);}

    @Override public void onCreate(Bundle state){super.onCreate(state);
        prefs=getSharedPreferences("ax8_control",MODE_PRIVATE);
        expandedSettings.addAll(prefs.getStringSet("expanded_settings",Collections.emptySet()));
        autoPollMs=AutoPollController.clamp(prefs.getInt("auto_poll_ms",500));
        api=new RouterClient(prefs.getString("host","192.168.150.1"),prefs.getString("username","root"),prefs.getString("password","admin"));
        qos=new QosController(api,prefs);traffic=new DeviceTrafficMonitor(api,prefs);
        ownerMac=prefs.getString("owner_mac","");monitorOn=prefs.getBoolean("traffic_monitor",false);
        // Never restore a blocking Band verification after a router reboot.
        clearPendingBand();
        restoreRecentRouterSnapshot();
        Ui.load(prefs);applySystemBarTheme();requestNotificationPermission();createShell();
        if(state!=null){
            java.util.ArrayList<String> savedGroups=state.getStringArrayList("expanded_settings");
            if(savedGroups!=null){expandedSettings.clear();expandedSettings.addAll(savedGroups);}
            int restored=state.getInt("active_tab",0);
            tab=restored>=0&&restored<TABS.length?restored:0;
            int[] positions=state.getIntArray("tab_scroll_y");
            if(positions!=null&&positions.length==tabScrollY.length)
                for(int i=0;i<positions.length;i++)tabScrollY[i]=Math.max(0,positions[i]);
        }
        pendingScrollY=tabScrollY[tab];
        rebuildNav();draw(true);UpdateSchedule.configure(this);
    }
    @Override protected void onSaveInstanceState(Bundle state){
        if(scroll!=null)tabScrollY[tab]=pendingScrollY>=0?pendingScrollY:scroll.getScrollY();
        state.putStringArrayList("expanded_settings",new java.util.ArrayList<>(expandedSettings));
        state.putInt("active_tab",tab);
        state.putIntArray("tab_scroll_y",tabScrollY.clone());
        super.onSaveInstanceState(state);
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
        // Queue priority activation behind any onPause() cleanup. An immediate enable
        // here can be undone by a delayed cleanup from the previous foreground session.
        running=true;
        final long resumeGeneration=refreshSession.capture();
        try{workers.execute(()->{
            if(refreshSession.accepts(resumeGeneration,running)
                    &&prefs.getBoolean("background_mode",false)
                    &&prefs.getBoolean("background_priority",false)
                    &&monitorOn&&!ownerMac.isEmpty()&&!priority.enabled)
                priority.enable(ownerMac);
        });}catch(RejectedExecutionException ignored){/* Activity is being destroyed. */}
        autoPollMs=AutoPollController.clamp(prefs.getInt("auto_poll_ms",500));
        resetInternetResult();
        internetNetworkSignature=routerNetworkFingerprint();
        watchRouterNetwork();
        handler.removeCallbacks(ticker);refresh(true);maybeProbeInternet(true);
        handler.postDelayed(ticker,refreshInterval());
        if(!startupOtaChecked){
            startupOtaChecked=true;
            if(prefs.getBoolean("ota_on_launch",true))
                handler.postDelayed(()->{if(!isFinishing()&&!isDestroyed())checkOta(true);},700);
        }
    }
    @Override protected void onPause(){
        // A foreground poll may complete after the Activity is paused or resumed.
        // Reject its queued UI callbacks without allowing overlapping WebUI requests.
        refreshSession.invalidate();
        // Do not average counters across an unobserved background interval.
        lastBytesAt=0;lastRx=Double.NaN;lastTx=Double.NaN;
        fallbackDl=Double.NaN;fallbackUl=Double.NaN;
        running=false;handler.removeCallbacks(ticker);
        stopWatchingRouterNetwork();resetInternetResult();
        ForegroundHandoff.begin(); // Block background QoS until queued UI cleanup finishes.
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
        try{
            workers.execute(()->{
                try{
                    try{if(qos.priorityActive())qos.applyPriority(ownerMac,null,new ArrayList<>(devices),m->{});}catch(Exception ignored){}
                    try{traffic.close();}catch(Exception ignored){}
                    priority.disable();
                }finally{ForegroundHandoff.end();}
            });
        }catch(RejectedExecutionException rejected){
            ForegroundHandoff.end();
            prefs.edit().putString("bg_error","Очистка экранного QoS не запущена: "+rejected).apply();
        }
        super.onPause();
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(intent!=null&&intent.getIntExtra("open_tab",-1)==2){
            intent.removeExtra("open_tab");showTab(2);
        }
    }
    @Override protected void onDestroy(){
        internetWorker.shutdownNow();diagnosticWorker.shutdownNow();temporaryWorker.shutdown();workers.shutdown();super.onDestroy();
    }
    private void applySystemBarTheme(){
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        int flags=Ui.isLight()?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }
    private void applyAppearance(){
        int keep=tab;
        // Anchor the selected theme control to its on-screen position across a rebuild.
        // This avoids jumping to a different category when text/card heights change.
        if(keep==TABS.length-1 && scroll!=null && themeAnchorView!=null){
            int[] control=new int[2],viewport=new int[2];
            themeAnchorView.getLocationOnScreen(control);
            scroll.getLocationOnScreen(viewport);
            pendingThemeAnchorOffset=control[1]-viewport[1];
            pendingThemeAnchorKey=themeAnchorKey;
        }
        int keepY=pendingScrollY>=0?pendingScrollY:((scroll==null)?0:scroll.getScrollY());
        // Appearance changes can alter card and text heights. Preserve the
        // relative reading position instead of restoring stale absolute pixels.
        // Only the settings page uses this policy; other tabs keep their own Y.
        if(keep==TABS.length-1 && scroll!=null && pendingCompactScrollFraction<0f){
            int maxY=Math.max(0,content.getHeight()-scroll.getHeight());
            pendingCompactScrollFraction=maxY>0
                ?Math.max(0f,Math.min(1f,keepY/(float)maxY)):0f;
        }
        Ui.load(prefs);applySystemBarTheme();
        createShell();tab=keep;pendingScrollY=keepY;rebuildNav();draw(true);
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
            refresh(true);
        });
        header.addView(connectionLabel);
        root.addView(header);
        workLabel=Ui.text(this,"",12,Ui.CYAN,true);workLabel.setGravity(Gravity.CENTER_VERTICAL);
        // Let long progress messages wrap instead of clipping inside a fixed-height banner.
        workLabel.setMinHeight(d(37));
        workLabel.setPadding(d(18),d(8),d(18),d(8));workLabel.setVisibility(View.GONE);
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
        long now=System.currentTimeMillis();
        long limit=Math.max(15000L,refreshInterval()*3L);
        if(BackgroundSummary.isFresh(smsAt,now,limit)){
            int local=0;for(RouterClient.Sms sms:messages)if(sms.unread())local++;
            return local;
        }
        if(BackgroundSummary.isFresh(prefs.getLong("bg_sms_at",0L),now,limit))
            return Math.max(0,prefs.getInt("bg_sms_unread",0));
        return 0;
    }
    private void showTab(int index){
        if(index==tab)return;
        tabScrollY[tab]=pendingScrollY>=0?pendingScrollY:scroll.getScrollY();
        // A theme/compact-layout anchor belongs only to the settings page.
        // Discard it on navigation so a pending restore cannot move another tab.
        pendingCompactScrollFraction=-1f;
        pendingThemeAnchorKey=null;
        selectedClientGroups.clear();
        tab=index;pendingScrollY=tabScrollY[index];rebuildNav();draw(true);
        // Cancel the previous tab transition before starting another one. Rapid taps
        // must not let an older animation alter the newly selected page.
        content.animate().cancel();
        content.setAlpha(1f);
        content.setTranslationY(0f);
        if(Ui.MOTION){
            content.setAlpha(0f);
            content.setTranslationY(d(14));
            content.animate().alpha(1f).translationY(0f).setDuration(230).start();
        }
        if(index==1||index==2||index==3)refresh(true);
    }
    /** Update the shared progress banner only when its visible state actually changes. */
    private void displayWork(String message){handler.post(()->{
        if(workLabel==null)return;
        String label=message==null||message.isEmpty()?"":"◌  "+message;
        if(label.isEmpty()){
            if(workLabel.getVisibility()!=View.GONE)workLabel.setVisibility(View.GONE);
            return;
        }
        if(!label.contentEquals(workLabel.getText()))workLabel.setText(label);
        if(workLabel.getVisibility()!=View.VISIBLE){
            workLabel.setBackgroundColor(Ui.CARD2);
            workLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            workLabel.setVisibility(View.VISIBLE);
        }
    });}
    private void alert(String title,String text){Ui.modal(this,title,text,null,"Закрыть",null);}
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
    /** Clear expired UI snapshots without changing SMS notification deduplication history. */
    private boolean expireStaleSections(long now){
        long limit=Math.max(15000L,refreshInterval()*3L);
        boolean changed=false;
        if(clientsAt>0&&!BackgroundSummary.isFresh(clientsAt,now,limit)){
            devices=new ArrayList<>();clientsAt=0;changed=true;
        }
        if(smsAt>0&&!BackgroundSummary.isFresh(smsAt,now,limit)){
            messages=new ArrayList<>();smsAt=0;changed=true;
        }
        if(blocked!=null&&!BackgroundSummary.isFresh(blockedAt,now,limit)){
            blocked=null;blockedAt=0;changed=true;
        }
        return changed;
    }
    private void loadBackgroundSnapshot(){
        long now=System.currentTimeMillis();
        long ts=prefs.getLong("bg_snapshot_at",0L);
        long limit=Math.max(15000L,refreshInterval()*3L);
        expireStaleSections(now);
        if(!BackgroundSummary.isFresh(ts,now,limit)||ts<lastCompleteAt){
            long smsTime=prefs.getLong("bg_sms_at",0L);
            if(smsTime>smsAt && BackgroundSummary.isFresh(smsTime,now,limit)){
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
            if(prefs.getLong("bg_devices_cycle_at",0L)==ts && BackgroundSummary.isFresh(deviceAt,now,limit)){
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
            if(prefs.getLong("bg_blocked_cycle_at",0L)==ts && BackgroundSummary.isFresh(blockedAt,now,limit)){
                String rawBlocked=prefs.getString("bg_blocked_json","");
                blocked=rawBlocked==null||rawBlocked.trim().isEmpty()
                    ?null:parseBlocked(new JSONObject(rawBlocked));
                this.blockedAt=blocked==null?0:blockedAt;
            }else{blocked=null;this.blockedAt=0;}
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
        
        if(bandAction){error="";notice="";}
        actionBusy=true;temporaryAction=title.equals("Временное отключение")||title.equals("Отключение остальных");notice="";displayWork(title);
        (temporaryAction?temporaryWorker:workers).execute(()->{
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
                actionBusy=false;temporaryAction=false;displayWork("");
                if(currentAcl!=null)blocked=currentAcl;
                if(bandAction && fail==null){
                    // A successful WebUI POST is not proof of a new active Band.
                    long now=System.currentTimeMillis();
                    clearPendingBand();
                    status=null;statusAt=0;error="";api.invalidate();
                    toast("Команда Band отправлена. Приложение остаётся доступным во время перезапуска AX8.");
                }else if(fail!=null){
                    alert("Ошибка выполнения",fail);
                }else toast(ok);
                clientsAt=0;smsAt=0;statusAt=0;
                updateConnection();draw(true);refresh(true);
            });
        });
    }
    private RouterClient temporaryRouter(){return new RouterClient(prefs.getString("host","192.168.150.1"),prefs.getString("username","root"),prefs.getString("password","admin"));}
    private interface Work{void run(RouterClient.Progress p)throws Exception;}
    /** Never let a poll from an earlier foreground session operate on router QoS. */
    private boolean foregroundCycleActive(long generation){
        return refreshSession.accepts(generation,running)
            && prefs.getBoolean("ui_visible",true);
    }
    private void refresh(boolean forced){
        if(refreshBusy){
            // A forced foreground refresh must not disappear behind a poll
            // started before pause/resume. The worker remains single-flight.
            if(forced&&running)refreshSession.deferForced();
            return;
        }
        if((actionBusy&&!temporaryAction)||!running)return;
        long now=System.currentTimeMillis();
        // One global interval for all WebUI parameters. Never overlap cycles.
        boolean wantStatus=forced||now-statusAt>=refreshInterval();
        boolean wantClients=wantStatus;
        boolean wantBlocked=wantStatus;
        boolean wantSms=wantStatus;
        if(!wantStatus&&!wantClients&&!wantBlocked&&!wantSms)return;
        refreshBusy=true;
        final long cycleGeneration=refreshSession.capture();
        final long cycleTrafficEpoch=trafficMonitorEpoch;
        final long cycleStart=android.os.SystemClock.elapsedRealtime();
        workers.execute(()->{
            JSONObject next=null;List<RouterClient.Device> d=null;List<RouterClient.Sms> m=null;RouterClient.BlockState b=null;
            String failure=null,partialFailure=null;
            long statusSampleWall=0L,statusSampleElapsed=0L;
            long clientsSampleWall=0L,blockedSampleWall=0L,smsSampleWall=0L;
            if(wantStatus&&foregroundCycleActive(cycleGeneration))try{
                next=api.status();
                statusSampleWall=System.currentTimeMillis();
                statusSampleElapsed=android.os.SystemClock.elapsedRealtime();
                if(next!=null && forced){
                    final JSONObject early=next;
                    final long earlySampleWall=statusSampleWall;
                    handler.post(()->{
                        if(!refreshSession.accepts(cycleGeneration,running)
                                ||isFinishing()||isDestroyed()||actionBusy)return;
                        invalidateInternetOnPppChange(early);
                        // This snapshot has not reached the counter calculation yet.
                        // Never pair it with a fallback from a different snapshot.
                        fallbackDl=Double.NaN;fallbackUl=Double.NaN;
                        status=early;statusAt=earlySampleWall;error="";
                        updateConnection();
                        maybeProbeInternet(true);
                        if(tab==0)draw(true);
                    });
                }
            }catch(Exception e){failure=e.getMessage()==null?e.toString():e.getMessage();}
            // After a requested Band reboot only status is needed. Other WebUI calls
            // would multiply failed login attempts and delay recovery by many seconds.
            {
                if(wantClients&&foregroundCycleActive(cycleGeneration))try{d=api.devices();clientsSampleWall=System.currentTimeMillis();}catch(Exception e){partialFailure="Устройства: "+e.getMessage();}
                if(wantBlocked&&foregroundCycleActive(cycleGeneration))try{b=api.blockState();blockedSampleWall=System.currentTimeMillis();}catch(Exception e){if(tab==1)partialFailure="Список блокировок: "+e.getMessage();}
                // Publish a coherent client/ACL pair before SMS and optional QoS.
                // Rates are published separately, only with their matching client snapshot.
                if(d!=null&&b!=null&&foregroundCycleActive(cycleGeneration)){
                    final List<RouterClient.Device> earlyDevices=d;
                    final RouterClient.BlockState earlyBlocked=b;
                    final long earlyClientsAt=clientsSampleWall,earlyBlockedAt=blockedSampleWall;
                    handler.post(()->{
                        if(!refreshSession.accepts(cycleGeneration,running)
                                ||isFinishing()||isDestroyed()||actionBusy||tab!=1)return;
                        long nowWall=System.currentTimeMillis();
                        long limit=Math.max(10000L,refreshInterval()*3L);
                        if(!BackgroundSummary.isFresh(earlyClientsAt,nowWall,limit)
                                ||!BackgroundSummary.isFresh(earlyBlockedAt,nowWall,limit))return;
                        devices=earlyDevices;clientsAt=earlyClientsAt;
                        blocked=earlyBlocked;blockedAt=earlyBlockedAt;
                        autoSelectOwner(false);
                        updateClientsLive();
                    });
                }
                if(wantSms&&foregroundCycleActive(cycleGeneration))try{m=api.sms();smsSampleWall=System.currentTimeMillis();}catch(Exception e){partialFailure="SMS: "+e.getMessage();}
            }
            // SMS is independent of Telnet traffic/QoS. Publish the acquired
            // snapshot before slow optional work, without releasing single-flight.
            if(m!=null&&foregroundCycleActive(cycleGeneration)){
                final List<RouterClient.Sms> earlySms=m;
                final long earlySmsAt=smsSampleWall;
                handler.post(()->{
                    if(!refreshSession.accepts(cycleGeneration,running)
                            ||isFinishing()||isDestroyed()||actionBusy)return;
                    messages=earlySms;smsAt=earlySmsAt;
                    rebuildNav();
                    if(tab==2)updateSmsLive();
                });
            }
            // Optional Telnet counters: the entire snapshot is still governed by the same
            // refresh interval, with skipped ticks if AX8 has not yet answered.
            String qosIssue="";
            String cycleTrafficError="";
            final Map<String,DeviceTrafficMonitor.Rate> sampledRates=new HashMap<>();
            // A poll can outlive onPause(), while the background service takes
            // ownership of QoS. Reject its router-side effects and stale follow-up
            // reads, not only its eventual UI callback.
            if(d!=null&&foregroundCycleActive(cycleGeneration)){
                try{qos.resyncAddresses(d,message->{});}
                catch(Exception e){qosIssue="QoS: "+e.getMessage();}
            }
            if(monitorOn&&d!=null&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration)){
                // Never run traffic/QoS writes against a failed client fetch or
                // a foreground generation that no longer owns router monitoring.
                try{
                    traffic.sample(d);
                    if(monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration)){
                        cycleTrafficError=qosIssue;
                        if(traffic.error().isEmpty()){
                            for(RouterClient.Device dev:d){
                                DeviceTrafficMonitor.Rate rate=traffic.get(dev.mac);
                                if(rate!=null)sampledRates.put(dev.mac,rate);
                            }
                            // Publish measured rates before optional priority/watchdog writes.
                            // Keep the router worker single-flight and preserve QoS ordering.
                            final List<RouterClient.Device> measuredDevices=d;
                            final Map<String,DeviceTrafficMonitor.Rate> measuredRates=
                                Collections.unmodifiableMap(new HashMap<>(sampledRates));
                            handler.post(()->{
                                if(!refreshSession.accepts(cycleGeneration,running)
                                        ||isFinishing()||isDestroyed()||actionBusy
                                        ||!monitorOn||cycleTrafficEpoch!=trafficMonitorEpoch||tab!=1||devices!=measuredDevices
                                        ||clientListStale())return;
                                displayedRates=measuredRates;
                                ratedDevices=measuredDevices; ratedGeneration=cycleGeneration;
                                updateClientsLive();
                            });
                            if(priority.enabled&&monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration))
                                priority.update(d,traffic,qos);
                            if(prefs.getBoolean("background_qos_watch",true)
                                    &&monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration))
                                uiQosWatch.check(d,traffic,qos);
                        }
                    }
                }catch(Exception e){
                    if(monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration))
                        cycleTrafficError=e.getMessage()==null?e.toString():e.getMessage();
                }
            }else if(monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration))
                cycleTrafficError="Список клиентов временно недоступен";
            final String sampledTrafficError=cycleTrafficError;
            final long sampledWall=statusSampleWall,sampledElapsed=statusSampleElapsed;
            final long clientsSample=clientsSampleWall,blockedSample=blockedSampleWall,smsSample=smsSampleWall;
            final String limitedError=partialFailure;
            final JSONObject status2=next;final List<RouterClient.Device> devices2=d;final List<RouterClient.Sms> sms2=m;
            final RouterClient.BlockState blacklist=b;final String fail=failure;
            handler.post(()->{
                refreshBusy=false;
                // Discard stale results, then immediately honour a forced refresh
                // requested on resume while this old worker was still occupied.
                if(!refreshSession.accepts(cycleGeneration,running)||isFinishing()||isDestroyed()){
                    if(!isFinishing()&&!isDestroyed()&&refreshSession.takeDeferredForced(running))
                        refresh(true);
                    return;
                }
                observePoll(android.os.SystemClock.elapsedRealtime()-cycleStart,
                    status2!=null && sms2!=null && devices2!=null);
                expireStaleSections(System.currentTimeMillis());
                if(status2!=null){
                    double rx=num(status2,"realtime_rx_bytes"),tx=num(status2,"realtime_tx_bytes");
                    long sampleTime=sampledWall;
                    long counterTime=sampledElapsed;
                    double sec=(counterTime-lastBytesAt)/1000.0;
                    // Never reuse a previous rate after missing/reset counters or an invalid interval.
                    fallbackDl=counterRate(rx,lastRx,sec,lastBytesAt>0);
                    fallbackUl=counterRate(tx,lastTx,sec,lastBytesAt>0);
                    lastRx=rx;lastTx=tx;lastBytesAt=counterTime;
                    invalidateInternetOnPppChange(status2);
                    status=status2;statusAt=sampleTime;
                    prefs.edit().putString("last_router_status",status2.toString())
                        .putLong("last_router_status_at",sampleTime).apply();
                    float liveDl=(float)Math.max(0,rateValue(status2,"realtime_rx_thrpt",fallbackDl));
                    float liveUl=(float)Math.max(0,rateValue(status2,"realtime_tx_thrpt",fallbackUl));
                    historyDl.add(liveDl);historyUl.add(liveUl);
                    while(historyDl.size()>28)historyDl.removeFirst();
                    while(historyUl.size()>28)historyUl.removeFirst();
                }
                if(monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch){
                    displayedRates=Collections.unmodifiableMap(sampledRates);
                    ratedDevices=devices2; ratedGeneration=cycleGeneration;
                    trafficError=sampledTrafficError;
                }
                if(devices2!=null){
                    devices=devices2;clientsAt=clientsSample;
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
                if(sms2!=null){messages=sms2;smsAt=smsSample;}
                if(blacklist!=null){blocked=blacklist;blockedAt=blockedSample;}
                autoSelectOwner(false);
                lastCompleteAt=System.currentTimeMillis();
                rebuildNav();
                if(fail!=null){
                    // Loss of WebUI during a known reboot is expected. Do not show a
                    // misleading login failure while the band verification is pending.
                    if(statusAt==0||System.currentTimeMillis()-statusAt>30000){
                        error=fail.contains("Failed to connect")||fail.contains("Connection refused")||fail.contains("connect timed out")?
                            "WebUI AX8 временно недоступен. Проверьте подключение к Wi-Fi роутера.\n"+fail:fail;
                    }
                }else if(status2!=null)error="";
                if(limitedError!=null)sectionError=limitedError;
                else if(devices2!=null||sms2!=null||blacklist!=null)sectionError="";
                updateConnection();
                maybeProbeInternet(false);
                if(!actionBusy||temporaryAction){
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
                // Also honour a forced refresh requested during an otherwise valid
                // cycle (for example, switching to the SMS tab mid-request).
                if(refreshSession.takeDeferredForced(running))refresh(true);
            });
        });
    }
    private void clearPendingBand(){
        bandTracker.clear();
        prefs.edit().remove("pending_band").remove("pending_band_since").apply();
    }
    /** An LTE disconnect/reconnect must never inherit the previous WAN probe. */
    private void invalidateInternetOnPppChange(JSONObject next){
        if(status!=null && next!=null &&
                InternetStatusPolicy.pppChanged(status.optString("ppp_status",""),
                    next.optString("ppp_status","")))resetInternetResult();
    }
    private void resetInternetResult(){
        internetRevision++;
        internetResult=InternetStatusPolicy.UNKNOWN;
        internetLastStart=0L;internetCheckedAt=0L;
    }
    /** Physical-network identity: ignore VPN and unrelated Android capability churn. */
    private String routerNetworkFingerprint(){
        try{
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if(cm==null)return "";
            List<String> links=new ArrayList<>();
            for(Network network:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||
                        (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&
                         !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))continue;
                StringBuilder link=new StringBuilder().append(network.getNetworkHandle())
                    .append(":validated=")
                    .append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                android.net.LinkProperties properties=cm.getLinkProperties(network);
                if(properties!=null){
                    link.append(":iface=").append(properties.getInterfaceName());
                    for(android.net.RouteInfo route:properties.getRoutes())
                        if(route.isDefaultRoute())link.append(":gateway=").append(route.getGateway());
                    for(java.net.InetAddress dns:properties.getDnsServers())
                        link.append(":dns=").append(dns.getHostAddress());
                }
                links.add(link.toString());
            }
            Collections.sort(links);
            return String.join("|",links);
        }catch(Exception ignored){return "";}
    }
    private void watchRouterNetwork(){
        if(internetWatching)return;
        try{
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            NetworkRequest request=new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build();
            cm.registerNetworkCallback(request,internetCallback);
            internetWatching=true;
        }catch(Exception ignored){}
    }
    private void stopWatchingRouterNetwork(){
        handler.removeCallbacks(internetChange);
        internetChangeScheduled=false;
        if(!internetWatching)return;
        internetWatching=false;
        try{
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            cm.unregisterNetworkCallback(internetCallback);
        }catch(Exception ignored){}
    }
    private boolean routerStatusFresh(){
        long now=System.currentTimeMillis();
        return status!=null&&statusAt>0&&now>=statusAt&&now-statusAt<30000L&&error.isEmpty();
    }
    private int displayedInternet(){
        return InternetStatusPolicy.displayed(routerStatusFresh(),
            "ppp_connected".equals(s("ppp_status")),
            internetResult,internetCheckedAt,SystemClock.elapsedRealtime());
    }
    private void updateInternetChip(){
        TextView chip=overviewValues.get("ppp");
        if(tab!=0||chip==null)return;
        int state=displayedInternet();
        String label=InternetStatusPolicy.label(state);
        if(!label.contentEquals(chip.getText()))chip.setText(label);
        chip.setTextColor(state==InternetStatusPolicy.ONLINE?Ui.GREEN:
            state==InternetStatusPolicy.OFFLINE?Ui.RED:Ui.ORANGE);
        chip.setContentDescription(InternetStatusPolicy.detail(state));
    }
    /** Slow, independent HTTPS probe over AX8's Wi-Fi, never over cellular fallback. */
    private void maybeProbeInternet(boolean force){
        if(!running||internetBusy||!routerStatusFresh()||
                !"ppp_connected".equals(s("ppp_status")))return;
        long now=SystemClock.elapsedRealtime();
        if(!force && !InternetStatusPolicy.shouldProbe(internetLastStart,now,false))return;
        internetLastStart=now;
        internetBusy=true;
        final long generation=internetRevision;
        final String networkSignature=routerNetworkFingerprint();
        final String target=api.host();
        internetWorker.execute(()->{
            RouterInternetProbe.Result result=RouterInternetProbe.check(getApplicationContext(),target);
            handler.post(()->{
                internetBusy=false;
                if(!running)return;
                if(generation!=internetRevision||!target.equals(api.host())||
                        !networkSignature.equals(routerNetworkFingerprint())){
                    internetNetworkSignature=routerNetworkFingerprint();
                    resetInternetResult();updateInternetChip();
                    maybeProbeInternet(true);return;
                }
                if(result.network!=null &&
                        !RouterInternetProbe.networkStillPresent(getApplicationContext(),result.network)){
                    resetInternetResult();updateInternetChip();maybeProbeInternet(true);return;
                }
                internetResult=result.state;
                internetCheckedAt=SystemClock.elapsedRealtime();
                updateInternetChip();
            });
        });
    }
    private void updateConnection(){
        boolean fresh=status!=null&&statusAt>0&&System.currentTimeMillis()-statusAt<30000;
        boolean online=fresh&&error.isEmpty();
        String label;
        label=online?"●  ONLINE":(error.isEmpty()?"◌  ОБНОВЛЕНИЕ":"●  ОФЛАЙН");
        connectionLabel.setText(label);
        connectionLabel.setContentDescription("Соединение с роутером AX8 Pro: "+label);
        connectionLabel.setTextColor((online?Ui.GREEN:(statusAt==0&&error.isEmpty()?Ui.ORANGE:Ui.RED)));
    }
    private static boolean routerTransportFailure(String message){
        String s=message==null?"":message.toLowerCase(Locale.ROOT);
        return s.contains("connection refused")||s.contains("econnrefused")||
            s.contains("connectexception")||s.contains("sockettimeoutexception")||
            s.contains("timed out")||s.contains("unreachable")||
            s.contains("no route to host")||s.contains("failed to connect");
    }
    private void draw(boolean preserveScroll){
        int y=preserveScroll?(pendingScrollY>=0?pendingScrollY:scroll.getScrollY()):0;
        rebuildNav();
        content.removeAllViews();
        if(!error.isEmpty()) {
            LinearLayout err=Ui.card(this);err.setBackground(Ui.shape(this,0xFF301B2C,0xFF77405B,16));
            err.addView(Ui.text(this,"ПРОБЛЕМА С ПОДКЛЮЧЕНИЕМ",12,Ui.RED,true));Ui.addSpace(err,this,8);
            String visibleError=routerTransportFailure(error)?"Отсутствует подключение к роутеру.":error;
            err.addView(Ui.text(this,visibleError,12,Ui.TEXT,false));Ui.addSpace(err,this,11);
            err.addView(Ui.button(this,"↻ Повторить",Ui.CARD2,Ui.TEXT,()->refresh(true)));addCard(err);
        } else if(!notice.isEmpty()){
            LinearLayout n=Ui.card(this);n.addView(Ui.text(this,"✓ "+notice,12,Ui.GREEN,true));addCard(n);
        }
        if(error.isEmpty() && !sectionError.isEmpty() && (tab==1||tab==2)){
            LinearLayout n=Ui.card(this);n.addView(Ui.text(this,sectionError,12,Ui.ORANGE,false));addCard(n);
        }
        if(tab==0)overview();else if(tab==1)clients();else if(tab==2)sms();else if(tab==3)network();else settings();
        if(preserveScroll){
            final int expectedTab=tab,revision=++scrollRestoreRevision;
            final ScrollView target=scroll;
            pendingScrollY=y;
            target.post(()->{
                if(tab!=expectedTab||scroll!=target||revision!=scrollRestoreRevision)return;
                pendingScrollY=-1;
                int maxY=Math.max(0,content.getHeight()-target.getHeight());
                int restoredY=pendingCompactScrollFraction>=0f
                    ?Math.round(pendingCompactScrollFraction*maxY):y;
                pendingCompactScrollFraction=-1f;
                target.scrollTo(0,Math.max(0,Math.min(restoredY,maxY)));
                if(expectedTab==TABS.length-1 && pendingThemeAnchorKey!=null && themeAnchorView!=null
                        && pendingThemeAnchorKey.equals(themeAnchorKey)){
                    int[] control=new int[2],viewport=new int[2];
                    themeAnchorView.getLocationOnScreen(control);
                    target.getLocationOnScreen(viewport);
                    int delta=control[1]-viewport[1]-pendingThemeAnchorOffset;
                    target.scrollTo(0,Math.max(0,Math.min(target.getScrollY()+delta,maxY)));
                }
                pendingThemeAnchorKey=null;
            });
        }
    }
    private void addCard(LinearLayout v){LinearLayout.LayoutParams p=Ui.lp(this,-1,-2);p.bottomMargin=d(13);(settingsTarget!=null?settingsTarget:content).addView(v,p);}
    private void addTitle(String s,String sub){LinearLayout v=Ui.vertical(this);v.setPadding(d(4),d(8),d(3),d(18));
        Ui.title(v,this,s,sub);content.addView(v);
    }
    private void rowCard(String heading,String detail,String badge,int accent,Runnable click){
        LinearLayout card=Ui.card(this);
        // Reserve room for the badge before Android measures the title. On narrow
        // screens and enlarged system fonts, horizontal layout clips long labels.
        boolean stacked=badge!=null&&(getResources().getConfiguration().screenWidthDp<=400
                ||getResources().getConfiguration().fontScale>=1.3f);
        LinearLayout head=stacked?Ui.vertical(this):Ui.horizontal(this);
        LinearLayout left=Ui.vertical(this);
        left.addView(Ui.text(this,heading,15,Ui.TEXT,true));Ui.addSpace(left,this,5);
        left.addView(Ui.text(this,detail,12,Ui.MUTED,false));
        if(stacked)head.addView(left,new LinearLayout.LayoutParams(-1,-2));
        else head.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        if(badge!=null){
            TextView b=Ui.chip(this,badge,accent);
            if(stacked){
                LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(-2,-2);
                badgeParams.topMargin=d(10);
                head.addView(b,badgeParams);
            }else head.addView(b);
        }
        card.addView(head);
        if(click!=null){
            // The entire row is one accessible action, not a collection of
            // unrelated text and badge elements. Preserve readable labels.
            card.setMinimumHeight(d(48));
            card.setFocusable(true);
            card.setContentDescription(heading+(detail==null||detail.isEmpty()?"":". "+detail)
                +(badge==null||badge.isEmpty()?"":". "+badge));
            card.setOnClickListener(v->click.run());
            Ui.animateTouch(card);
        }
        addCard(card);
    }
    private static double parse(Object raw){try{return Double.parseDouble(String.valueOf(raw).replace("%","").trim());}catch(Exception e){return Double.NaN;}}
    private static double num(JSONObject o,String key){return o==null?Double.NaN:parse(o.optString(key,""));}
    private String s(String key){return status==null?"":status.optString(key,"");}
    private boolean noCellService(){
        return "No Service".equalsIgnoreCase(s("network_type").trim()) ||
            "No Service".equalsIgnoreCase(s("sub_network_type").trim());
    }
    private String band(){if(noCellService())return "—";String b=stat("nv_band");return b.equals("—")?"—":b.toUpperCase(Locale.US).startsWith("B")?b:"B"+b;}
    private String stat(String key){String raw=s(key).trim();return raw.isEmpty()||raw.equals("?")||raw.equals("--")||raw.equalsIgnoreCase("null")?"—":raw;}
    private double rsrpValue(){if(noCellService())return Double.NaN;double v=num(status,"nv_rsrp");return v>0?v-141:v;}
    private double rsrqValue(){if(noCellService())return Double.NaN;double v=num(status,"nv_rsrq");return v>0?(v-40)/2:v;}
    private double sinrValue(){return noCellService()?Double.NaN:num(status,"nv_sinr");}
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
    static double counterRate(double current,double previous,double seconds,boolean hasBaseline){
        if(!hasBaseline||!Double.isFinite(current)||!Double.isFinite(previous)
                ||current<0||previous<0||current<previous||!Double.isFinite(seconds)||seconds<=.3)
            return Double.NaN;
        double rate=(current-previous)/seconds;
        return Double.isFinite(rate)?rate:Double.NaN;
    }
    private double rateValue(JSONObject from,String key,double fallback){
        double x=num(from,key);return Double.isNaN(x)?(Double.isNaN(fallback)?0d:fallback):x;
    }
    private void overview(){
        overviewValues.clear();overviewSignalValues.clear();overviewGauge=null;overviewGraph=null;overviewBattery=null;
        addTitle("Центр управления","Мобильная сеть и состояние AX8 Pro в реальном времени");
        LinearLayout hero=Ui.card(this);hero.setBackground(Ui.gradient(this,24,0xFF1D374D,0xFF102438,0xFF0D1A2B));
        LinearLayout top=Ui.horizontal(this);TextView badge=Ui.chip(this,band(),Ui.CYAN);overviewValues.put("band",badge);top.addView(badge);
        View spacer=new View(this);top.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        int internetState=displayedInternet();
        TextView ppp=Ui.chip(this,InternetStatusPolicy.label(internetState),
            internetState==InternetStatusPolicy.ONLINE?Ui.GREEN:
                internetState==InternetStatusPolicy.OFFLINE?Ui.RED:Ui.ORANGE);
        ppp.setContentDescription(InternetStatusPolicy.detail(internetState));
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
        ul.setGravity(Gravity.END);
        TextView upLabel=Ui.text(this,"↑  ОТДАЧА",12,Ui.ORANGE,true);
        upLabel.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);ul.addView(upLabel);Ui.addSpace(ul,this,6);
        TextView upValue=liveValue("ul",speed("realtime_tx_thrpt"),19,Ui.TEXT,true);
        upValue.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);ul.addView(upValue);
        summary.addView(dl,new LinearLayout.LayoutParams(0,-2,1));summary.addView(ul,new LinearLayout.LayoutParams(0,-2,1));speeds.addView(summary);
        Ui.addSpace(speeds,this,13);Ui.SpeedGraph graph=new Ui.SpeedGraph(this);
        java.util.Iterator<Float> itDl=historyDl.iterator(),itUl=historyUl.iterator();
        while(itDl.hasNext()||itUl.hasNext())graph.push(itDl.hasNext()?itDl.next():0f,itUl.hasNext()?itUl.next():0f);
        overviewGraph=graph;
        speeds.addView(graph,Ui.lp(this,-1,94));addCard(speeds);
        LinearLayout cell=Ui.card(this);Ui.title(cell,this,"Сотовая станция","Технические параметры радиосети");Ui.addSpace(cell,this,10);
        addRows(cell,new String[][]{{"Оператор / PLMN",stat("network_provider")},{"Технология",stat("network_type")},{"EARFCN",stat("nv_arfcn")},
            {"PCI",stat("nv_pci")},{"eNodeB",stat("nv_enodbid")},{"CELL ID",stat("nv_globecellid")},
            {"Устройств Wi‑Fi",wifiCount()},{"Батарея",battery()}},true);addCard(cell);
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
    private TextView liveValue(String key,String value,int size,int color,boolean bold){
        TextView t=Ui.text(this,value,size,color,bold);overviewValues.put(key,t);return t;
    }
    private LinearLayout signalRow(String metric,String value){
        // Restore the original compact two-column layout on every screen size.
        // The value column stays right aligned even when accessibility text wraps.
        LinearLayout row=Ui.horizontal(this);
        TextView name=Ui.text(this,metric,13,Ui.MUTED,true);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView val=Ui.text(this,value,12,signalColorForMetric(metric),true);
        val.setSingleLine(false);
        val.setHorizontallyScrolling(false);
        val.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        val.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
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
        updateInternetChip();
        setSignalLive("СИГНАЛ",rssi());
        setSignalLive("SINR",reading(sinr(),"dB"));
        setSignalLive("RSRQ",reading(rsrq(),"dB"));
        setSignalLive("RSRP",reading(rsrp(),"dBm"));
        setLive("dl",speed("realtime_rx_thrpt"));setLive("ul",speed("realtime_tx_thrpt"));
        setLive("Оператор / PLMN",stat("network_provider"));setLive("Технология",stat("network_type"));
        setLive("EARFCN",stat("nv_arfcn"));setLive("PCI",stat("nv_pci"));
        setLive("eNodeB",stat("nv_enodbid"));setLive("CELL ID",stat("nv_globecellid"));
        setLive("Устройств Wi‑Fi",wifiCount());
        if(overviewBattery!=null)overviewBattery.setBattery(batteryIconLevel(),battery(),
            prefs.getBoolean("battery_level_icon",true),batteryCharging());
        if(overviewGauge!=null && newSample)overviewGauge.setLevel(quality());
        if(overviewGraph!=null && newSample)overviewGraph.push(historyDl.isEmpty()?0f:historyDl.peekLast(),historyUl.isEmpty()?0f:historyUl.peekLast());
    }
    private boolean batteryCharging(){
        // A full battery is not evidence of charging; only trust the router's charge flag.
        return BatteryLevelPolicy.isCharging(s("battery_charging"));
    }
    /** Only the router's actual percent field is precise. battery_pers is a coarse icon step. */
    private int batteryPercent(){
        // AX8 WebUI's Battery_voltage is a confirmed percentage on supported firmware.
        // Fall back to the legacy numeric percent, never the coarse icon-step field.
        return BatteryLevelPolicy.firstValid(num(status,"Battery_voltage"),s("Battery_voltage"),
            num(status,"battery_vol_percent"),s("battery_vol_percent"));
    }
    private int batteryIconLevel(){
        int percent=batteryPercent();
        return percent>=0?percent:BatteryLevelPolicy.coarseLevel(num(status,"battery_pers"));
    }
    private String battery(){int level=batteryPercent();return level>=0?level+"%":"—";}
    private void addRows(LinearLayout c,String[][] rows){addRows(c,rows,false);}
    private void addRows(LinearLayout c,String[][] rows,boolean accentValues){for(int i=0;i<rows.length;i++){
        int valueColor=accentValues?Ui.CYAN:(rows[i][1].equals("—")?Ui.MUTED:i%2==0?Ui.TEXT:Ui.CYAN);
        LinearLayout line=Ui.infoRow(this,rows[i][0],rows[i][1],valueColor);
        if(tab==0&&"Батарея".equals(rows[i][0])){
            BatteryIndicator indicator=new BatteryIndicator(this);
            indicator.setBattery(batteryIconLevel(),rows[i][1],
                prefs.getBoolean("battery_level_icon",true),batteryCharging());
            line.removeViewAt(1);
            line.addView(indicator,new LinearLayout.LayoutParams(0,-2,1.06f));
            overviewBattery=indicator;
        }else{
            TextView value=(TextView)line.getChildAt(1);
            if(tab==0)overviewValues.put(rows[i][0],value);
            if(tab==3)networkValues.put(rows[i][0],value);
        }
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
        row.setOnClickListener(v->{
            RouterClient.Device known=new RouterClient.Device();
            known.mac=mac;known.name=savedLimitDeviceName(mac);
            chooseLimit(known);
        });
        Ui.animateTouch(row);target.addView(row);
    }
    private void clients(){
        clientDlValues.clear();clientUlValues.clear();clientLimitValues.clear();qosStatusLive=null;
        clientsSignature=clientSkeletonSignature();
        addTitle("Подключённые устройства","Wi-Fi-клиенты, блокировки и контроль скорости");
        LinearLayout summary=Ui.card(this);LinearLayout row=Ui.horizontal(this);
        LinearLayout stats=Ui.vertical(this);stats.addView(Ui.text(this,clientsAt>0?String.valueOf(devices.size()):"—",32,Ui.CYAN,true));
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
        addClientSelectionBar(content);
        if(devices.isEmpty())rowCard(clientsAt>0?"Устройства не обнаружены":"Список клиентов ещё не загружен",
            clientsAt>0?"Подключитесь к AX8 и обновите список":"Ожидание ответа AX8. При необходимости повторите обновление",null,Ui.MUTED,null);
        if(clientListStale()){
            rowCard("Список клиентов устарел","Показаны последние известные данные. Действия QoS не применяются по старым IP-адресам. Обновите соединение с AX8.",null,Ui.ORANGE,null);
        }
        for(RouterClient.Device dev:devices){
            boolean deny=blocked!=null&&blocked.black.contains(dev.mac);
            Integer limit=qos.effectiveLimit(dev.mac);
            DeviceTrafficMonitor.Rate rate=displayedRate(dev);
            LinearLayout card=Ui.card(this);
            LinearLayout head=Ui.horizontal(this);
            String key=clientGroupKey(dev);
            boolean picking=!selectedClientGroups.isEmpty();
            boolean chosen=selectedClientGroups.contains(key);
            if(picking&&chosen)card.setBackground(Ui.shape(this,Ui.CARD2,Ui.CYAN,Ui.CORNER));
            TextView name=Ui.text(this,(picking?(chosen?"☑  ":"☐  "):"")+friendlyName(dev),16,Ui.TEXT,true);
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
            card.setOnClickListener(v->{if(!selectedClientGroups.isEmpty())toggleClientSelection(key);else showDevice(dev);});
            card.setOnLongClickListener(v->{toggleClientSelection(key);return true;});
            card.setContentDescription(friendlyName(dev)+(picking?(chosen?", выбрано":", не выбрано"):", удерживайте для выбора"));
            Ui.animateTouch(card);addCard(card);
            clientDlValues.put(dev.mac,down[0]);clientUlValues.put(dev.mac,up[0]);clientLimitValues.put(dev.mac,cap[0]);
        }
        showRememberedClients();
    }
    private Map<String,Integer> activeGroupLimits(){
        Map<String,Integer> active=new TreeMap<>();
        for(RouterClient.Device dev:devices){
            if(dev==null)continue;
            String mac=RouterClient.normalizeMac(dev.mac);
            if(!mac.isEmpty())active.put(mac,qos.effectiveLimit(mac));
        }
        return active;
    }
    private String savedGroupSignature(){
        Map<String,Integer> saved=new TreeMap<>(qos.limits());
        StringBuilder signature=new StringBuilder(saved.toString());
        for(String mac:saved.keySet())signature.append('|').append(mac).append(':').append(qos.limitRevision(mac));
        for(Map.Entry<String,Integer> live:activeGroupLimits().entrySet())
            signature.append('|').append(live.getKey()).append(':').append(live.getValue())
                .append(':').append(savedLimitDeviceName(live.getKey()));
        return signature.toString();
    }
    /** MACs are remembered by alias, cached hostname, QoS and the router blacklist. */
    private TreeSet<String> rememberedMacs(boolean includeLive){
        TreeSet<String> known=new TreeSet<>();
        for(String key:prefs.getAll().keySet()){
            String mac="";
            if(key.startsWith("alias_"))mac=key.substring("alias_".length());
            else if(key.startsWith("cached_client_name_"))mac=key.substring("cached_client_name_".length());
            if(!mac.isEmpty()){
                String normalized=RouterClient.normalizeMac(mac);
                if(!normalized.isEmpty())known.add(normalized);
            }
        }
        for(String mac:qos.limits().keySet()){
            String normalized=RouterClient.normalizeMac(mac);
            if(!normalized.isEmpty())known.add(normalized);
        }
        if(blocked!=null)for(String mac:blocked.black){
            String normalized=RouterClient.normalizeMac(mac);
            if(!normalized.isEmpty())known.add(normalized);
        }
        for(RouterClient.Device active:devices)if(active!=null){
            String mac=RouterClient.normalizeMac(active.mac);
            if(!mac.isEmpty()){if(includeLive)known.add(mac);else known.remove(mac);}
        }
        return known;
    }
    private List<SavedClientGroups.Group> allClientGroups(){
        return SavedClientGroups.build(rememberedMacs(true),this::savedLimitDeviceName,
            qos.limits(),qos::limitRevision,activeGroupLimits());
    }
    private String clientGroupKey(RouterClient.Device dev){
        return SavedClientGroups.key(friendlyName(dev),RouterClient.normalizeMac(dev.mac));
    }
    private SavedClientGroups.Group groupForMac(String mac){
        String normalized=RouterClient.normalizeMac(mac);
        for(SavedClientGroups.Group group:allClientGroups())if(group.macs.contains(normalized))return group;
        return null;
    }
    private void toggleClientSelection(String key){
        if(key==null||key.isEmpty())return;
        if(!selectedClientGroups.add(key))selectedClientGroups.remove(key);
        draw(true);
    }
    private void clearClientSelection(){selectedClientGroups.clear();draw(true);}
    private void addClientSelectionBar(LinearLayout target){
        if(selectedClientGroups.isEmpty())return;
        LinearLayout bar=Ui.card(this);
        bar.setBackground(Ui.shape(this,Ui.CARD2,Ui.CYAN,Math.max(6,Ui.CORNER-5)));
        bar.addView(Ui.text(this,"☑  Выбрано устройств: "+selectedClientGroups.size(),14,Ui.CYAN,true));
        Ui.addSpace(bar,this,8);
        bar.addView(Ui.button(this,"⊘  Заблокировать выбранные",0xFF442637,Ui.RED,this::blockSelectedClients));
        Ui.addSpace(bar,this,7);
        bar.addView(Ui.button(this,"✕  Снять выделение",Ui.CARD,Ui.TEXT,this::clearClientSelection));
        if(target==content)addCard(bar);else{target.addView(bar);Ui.addSpace(target,this,7);}
    }
    private void blockSelectedClients(){
        if(selectedClientGroups.isEmpty())return;
        List<SavedClientGroups.Group> all=allClientGroups();
        LinkedHashSet<String> addresses=new LinkedHashSet<>();
        List<String> selectedNames=new ArrayList<>();
        for(String selectedKey:selectedClientGroups){
            SavedClientGroups.Group found=null;
            for(SavedClientGroups.Group group:all)if(group.key.equals(selectedKey)){found=group;break;}
            if(found==null){
                alert("Выделение устарело","Состав устройств изменился. Снимите выделение и выберите их повторно.");
                return;
            }
            selectedNames.add(found.name);
            addresses.addAll(found.macs);
        }
        String label=selectedNames.size()+" устройств: "+String.join(", ",selectedNames);
        changeMacBlocks(label,addresses,true,true);
    }
    private void showRememberedClients(){
        TreeSet<String> known=rememberedMacs(false);
        if(known.isEmpty())return;
        Map<String,Integer> limits=qos.limits();
        Map<String,Integer> liveLimits=activeGroupLimits();
        List<SavedClientGroups.Group> groups=SavedClientGroups.build(known,this::savedLimitDeviceName,limits,qos::limitRevision,liveLimits);
        LinearLayout saved=Ui.card(this);
        saved.addView(Ui.button(this,(knownClientsExpanded?"⌃  ":"⌄  ")+"Сохранённые устройства ("+groups.size()+")",
            Ui.CARD2,Ui.TEXT,()->{knownClientsExpanded=!knownClientsExpanded;
                if(!knownClientsExpanded)expandedRememberedGroups.clear();draw(true);}));
        if(knownClientsExpanded){
            Ui.addSpace(saved,this,9);
            saved.addView(Ui.text(this,"Нажмите на устройство для подробностей и блокировки всех его MAC. Удерживайте для выбора нескольких; стрелка раскрывает отдельные MAC и лимиты.",12,Ui.MUTED,false));
            Ui.addSpace(saved,this,9);
            addClientSelectionBar(saved);
            for(SavedClientGroups.Group group:groups)
                addSavedClientGroup(saved,group,limits,liveLimits,expandedRememberedGroups);
        }
        addCard(saved);
    }
    private void editRememberedLimit(String mac){
        RouterClient.Device remembered=new RouterClient.Device();
        remembered.mac=mac;remembered.name=savedLimitDeviceName(mac);
        chooseLimit(remembered);
    }
    private void addSavedClientGroup(LinearLayout target,SavedClientGroups.Group group,
                                     Map<String,Integer> limits,Map<String,Integer> liveLimits,Set<String> expanded){
        boolean multiple=group.macs.size()>1;
        boolean open=multiple&&expanded.contains(group.key);
        // A device name and its saved speed belong on the same horizontal row.
        // The name column may wrap for accessibility fonts; the limit stays at the right edge.
        LinearLayout header=Ui.horizontal(this);
        boolean chosen=selectedClientGroups.contains(group.key);
        header.setBackground(Ui.shape(this,Ui.CARD2,chosen?Ui.CYAN:0,Math.max(4,Ui.CORNER-7)));
        header.setMinimumHeight(d(48));header.setPadding(d(12),d(8),d(12),d(8));
        LinearLayout identity=Ui.vertical(this);
        LinearLayout titleRow=Ui.horizontal(this);
        TextView name=Ui.text(this,(selectedClientGroups.isEmpty()?"":(chosen?"☑  ":"☐  "))+group.name,14,Ui.TEXT,true);
        titleRow.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        if(multiple){
            // Expansion belongs to the device identity, not the speed column.
            TextView arrow=Ui.text(this,open?"⌃":"⌄",18,Ui.CYAN,true);
            arrow.setGravity(Gravity.CENTER);
            arrow.setContentDescription(open?"Скрыть MAC-адреса":"Показать MAC-адреса");
            arrow.setOnClickListener(v->{
                if(!expanded.add(group.key))expanded.remove(group.key);
                draw(true);
            });
            titleRow.addView(arrow,new LinearLayout.LayoutParams(d(36),d(40)));
        }
        identity.addView(titleRow);
        if(multiple){
            Ui.addSpace(identity,this,4);
            identity.addView(Ui.text(this,group.macs.size()+" MAC"+(group.live?" · текущий лимит":group.limit!=null&&!group.conflicting?" · сохранённый лимит":""),10,Ui.MUTED,false));
        }
        header.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        String caption=group.conflicting?"Разные лимиты":limitName(group.limit);
        LinearLayout trailing=Ui.horizontal(this);
        int blockedCount=0;
        if(blocked!=null){
            for(String mac:group.macs)if(blocked.black.contains(mac))blockedCount++;
        }
        if(blockedCount>0){
            // Read-only outline indicator: no router/blocking commands are invoked.
            ImageView lock=new ImageView(this);
            lock.setImageResource(R.drawable.ic_lock_outline);
            lock.setColorFilter(blockedCount==group.macs.size()?Ui.RED:Ui.ORANGE);
            lock.setContentDescription(blockedCount==group.macs.size()?"Устройство заблокировано":"Часть MAC-адресов заблокирована");
            lock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams lockParams=new LinearLayout.LayoutParams(d(18),d(18));
            lockParams.rightMargin=d(6);
            trailing.addView(lock,lockParams);
        }
        TextView value=Ui.text(this,caption,14,group.conflicting?Ui.ORANGE:group.limit==null?Ui.GREEN:Ui.RED,true);
        value.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        value.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        value.setPadding(d(8),0,0,0);trailing.addView(value);
        header.addView(trailing);
        header.setClickable(true);header.setFocusable(true);
        header.setOnClickListener(v->{
            if(!selectedClientGroups.isEmpty())toggleClientSelection(group.key);
            else showSavedClientGroup(group);
        });
        header.setOnLongClickListener(v->{toggleClientSelection(group.key);return true;});
        header.setContentDescription(group.name+", "+caption+
            (blockedCount==0?"":blockedCount==group.macs.size()?", заблокировано":", часть MAC-адресов заблокирована")+
            (chosen?", выбрано":", удерживайте для множественного выбора"));
        Ui.animateTouch(header);target.addView(header);Ui.addSpace(target,this,5);
        if(open){
            for(String mac:group.macs){
                boolean live=liveLimits.containsKey(mac);
                Integer savedCap=limits.get(mac);
                Integer cap=live?liveLimits.get(mac):savedCap;
                boolean stackedRow=getResources().getConfiguration().screenWidthDp<=400
                        ||getResources().getConfiguration().fontScale>=1.3f;
                LinearLayout row=stackedRow?Ui.vertical(this):Ui.horizontal(this);
                row.setMinimumHeight(d(48));
                row.setPadding(d(14),d(8),d(10),d(8));
                LinearLayout address=Ui.vertical(this);
                address.addView(Ui.text(this,mac,12,Ui.TEXT,true));
                String state="";
                if(live){
                    state="● Подключён";
                    if(!Objects.equals(cap,savedCap))state+=" · сохранено "+limitName(savedCap);
                    address.addView(Ui.text(this,state,10,Ui.MUTED,false));
                }
                row.addView(address,stackedRow?new LinearLayout.LayoutParams(-1,-2)
                        :new LinearLayout.LayoutParams(0,-2,1));
                TextView speed=Ui.text(this,limitName(cap),13,cap==null?Ui.GREEN:Ui.RED,true);
                speed.setGravity(stackedRow?Gravity.START|Gravity.CENTER_VERTICAL:Gravity.END|Gravity.CENTER_VERTICAL);
                if(stackedRow)Ui.addSpace(row,this,5);
                row.addView(speed);
                row.setClickable(true);row.setFocusable(true);
                row.setContentDescription("MAC "+mac+(live?", подключён":"")+
                        (state.contains("сохранено")?", "+state.substring(state.indexOf("сохранено")):"")+
                        ", лимит "+limitName(cap)+", изменить ограничение");
                row.setOnClickListener(v->editRememberedLimit(mac));Ui.animateTouch(row);
                target.addView(row);
            }
            Ui.addSpace(target,this,8);
        }
    }
    /** Display the access action inside a device sheet, never below each list row. */
    private void showSavedClientGroup(SavedClientGroups.Group group){
        LinearLayout body=Ui.vertical(this);
        body.addView(Ui.text(this,group.macs.size()+" сохранённых MAC-адресов",13,Ui.MUTED,true));
        Ui.addSpace(body,this,10);
        final Dialog[] sheet={null};
        for(String mac:group.macs){
            LinearLayout row=Ui.horizontal(this);
            TextView name=Ui.text(this,mac,12,Ui.TEXT,true);
            row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView limit=Ui.button(this,"◉  "+limitName(qos.effectiveLimit(mac)),Ui.CARD2,Ui.CYAN,()->{
                sheet[0].dismiss();editRememberedLimit(mac);
            });
            LinearLayout.LayoutParams lp=Ui.lp(this,-2,-2);lp.leftMargin=d(8);
            row.addView(limit,lp);
            body.addView(row);Ui.addSpace(body,this,7);
        }
        Ui.addSpace(body,this,8);
        addGroupAccessButton(body,group,()->sheet[0].dismiss());
        sheet[0]=Ui.sheet(this,group.name,"Подробности сохранённого устройства",body);
    }
    private boolean groupFullyBlocked(SavedClientGroups.Group group){
        if(blocked==null||group.macs.isEmpty())return false;
        for(String mac:group.macs)if(!blocked.black.contains(mac))return false;
        return true;
    }
    private void addGroupAccessButton(LinearLayout body,SavedClientGroups.Group group,Runnable before){
        boolean allBlocked=groupFullyBlocked(group);
        String label=allBlocked?"✓  Разблокировать":"⊘  Заблокировать";
        if(group.macs.size()>1)label+=" все "+group.macs.size()+" MAC";
        body.addView(Ui.button(this,label,allBlocked?Ui.CARD2:0xFF442637,
            allBlocked?Ui.GREEN:Ui.RED,()->{
                if(before!=null)before.run();
                changeSavedGroupBlock(group,!allBlocked);
            }));
    }
    private void changeSavedGroupBlock(SavedClientGroups.Group group,boolean block){
        changeMacBlocks("устройство «"+group.name+"»",group.macs,block,false);
    }
    /** One verified router write for all selected devices and their remembered MACs. */
    private void changeMacBlocks(String description,Collection<String> members,boolean block,boolean clearSelection){
        if(blocked==null){
            toast("Список блокировок ещё загружается. Обновите данные AX8.");
            refresh(true);return;
        }
        LinkedHashSet<String> distinct=new LinkedHashSet<>();
        for(String mac:members){
            String normalized=RouterClient.normalizeMac(mac);
            if(!normalized.isEmpty())distinct.add(normalized);
        }
        if(distinct.isEmpty()){toast("У устройства нет корректных MAC-адресов");return;}
        OwnerIdentity.Result identity=OwnerIdentity.resolve(this,devices,blocked,ownerMac);
        final String protectedMac=identity.resolved()?identity.mac:ownerMac;
        String fromRouter=RouterClient.normalizeMac(blocked.userMac);
        if(block&&(distinct.contains(RouterClient.normalizeMac(protectedMac))||distinct.contains(fromRouter))){
            alert("Управляющее устройство","Нельзя заблокировать MAC телефона, управляющего AX8.");
            return;
        }
        final ArrayList<String> macs=new ArrayList<>(distinct);
        String verb=block?"Заблокировать":"Разблокировать";
        String message=verb+" "+description+" по "+macs.size()+" MAC-адресам?\n\n"+
            String.join("\n",macs)+"\n\nДругие записи чёрного списка останутся без изменений.";
        if(block&&!identity.resolved())
            message+="\n\nВнимание: управляющий телефон не удалось надёжно определить. Проверьте MAC-адреса перед подтверждением.";
        confirm("Контроль доступа",message,()->action("Список доступа",p->{
            api.setBlockedGroup(macs,block,protectedMac);
            if(clearSelection)handler.post(()->{selectedClientGroups.clear();draw(true);});
        }));
    }
    private static String humanRate(double bps){
        if(Double.isNaN(bps))return "—";
        if(bps>=1_000_000)return String.format(Locale.US,"%.2f MB/s",bps/1_000_000);
        if(bps>=1000)return String.format(Locale.US,"%.1f KB/s",bps/1000);
        return String.format(Locale.US,"%.0f B/s",bps);
    }
    private static String limitName(Integer kb){if(kb==null)return "∞";if(kb==0)return "0 KB/s";
        return kb>=1000?String.format(Locale.US,"%.2f MB/s",kb/1000.):kb+" KB/s";
    }
    private void showDevice(RouterClient.Device dev){
        LinearLayout body=Ui.vertical(this);
        String name=friendlyName(dev);
        SavedClientGroups.Group matchingGroup=groupForMac(dev.mac);
        boolean isBlocked=blocked!=null&&blocked.black.contains(dev.mac);
        TextView state=Ui.chip(this,isBlocked?"● ЗАБЛОКИРОВАН":"● ПОДКЛЮЧЕНО",isBlocked?Ui.RED:Ui.GREEN);
        body.addView(state);Ui.addSpace(body,this,12);
        DeviceTrafficMonitor.Rate rate=displayedRate(dev);
        if(rate!=null){
            TextView[] incoming={null};body.addView(clientMetric("↓  Входящая",humanRate(rate.dl),Ui.CYAN,incoming));
            TextView[] outgoing={null};body.addView(clientMetric("↑  Исходящая",humanRate(rate.ul),Ui.ORANGE,outgoing));}
        addRows(body,new String[][]{{"IPv4",dev.ip.isEmpty()?"—":dev.ip},
            {"MAC",dev.mac},{"IPv6",dev.ipv6.isEmpty()?"—":String.join(", ",dev.ipv6)}});
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
        body.addView(Ui.button(this,"Ограничение сервисов",Ui.CARD2,Ui.CYAN,()->{sheet[0].dismiss();serviceEditor(dev.mac);}));
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
        if(matchingGroup!=null){
            addGroupAccessButton(body,matchingGroup,()->sheet[0].dismiss());
            Ui.addSpace(body,this,9);
        }
        if(matchingGroup==null||matchingGroup.macs.size()>1){
            body.addView(Ui.button(this,isBlocked?"✓  Разблокировать этот MAC":"⊘  Заблокировать этот MAC",
                isBlocked?Ui.CARD2:0xFF442637,isBlocked?Ui.GREEN:Ui.RED,()->{
                    sheet[0].dismiss();
                    changeMacBlocks("MAC "+dev.mac,Collections.singletonList(dev.mac),!isBlocked,false);
                }));
            Ui.addSpace(body,this,9);
        }
        body.addView(Ui.button(this,"↯  Отключить на 10 секунд",Ui.CARD2,Ui.ORANGE,()->{
            sheet[0].dismiss();confirm("Временно отключить клиента",
                "MAC будет временно добавлен в чёрный список на 10 секунд и затем восстановлен. При сбое проверьте blacklist.",
                ()->action("Временное отключение",p->temporaryRouter().kick(dev.mac,p)));
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
                RouterClient api=temporaryRouter();
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
        int[] presets={15,20,30,50,100,150,250,300,500,750,1000,-1};
        for(int i=0;i<presets.length;i+=3){
            LinearLayout row=Ui.horizontal(this);
            for(int j=i;j<Math.min(i+3,presets.length);j++){
                final int kb=presets[j];boolean selected=Integer.valueOf(kb).equals(current);
                TextView x=Ui.button(this,kb==-1?"✎":limitName(kb),selected?0xFF15504D:Ui.CARD2,selected?Ui.CYAN:Ui.TEXT,
                    ()->{if(kb==-1)showManualLimit(current,value->applyLimitFromSheet(dialog,dev,value));
                        else applyLimitFromSheet(dialog,dev,kb);});
                if(kb==-1)x.setContentDescription("Ввести собственный лимит");
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
        int initial=current==null?500:Math.max(0,Math.min(1000,current));
        TextView amount=Ui.text(this,limitName(initial),22,Ui.CYAN,true);amount.setGravity(Gravity.CENTER);
        body.addView(amount);Ui.addSpace(body,this,6);
        SeekBar seek=new SeekBar(this);seek.setMax(1000);seek.setProgress(initial);
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
        body.addView(Ui.text(this,"Значения указаны в KB/s, одновременно для входящего и исходящего трафика. Диапазон ползунка: 0–1000 KB/s. Для других значений используйте ручной ввод. Требуется Telnet на роутере.",12,Ui.MUTED,false));
        dialog[0]=Ui.sheet(this,"Лимит скорости",name+" · IPv4/IPv6 · DL + UL",body);
    }
    private void showManualLimit(Integer current,java.util.function.IntConsumer apply){
        LinearLayout body=Ui.vertical(this);
        body.addView(Ui.text(this,"СКОРОСТЬ · KB/s",12,Ui.MUTED,true));
        Ui.addSpace(body,this,8);
        EditText input=themedField(current==null?"":String.valueOf(current),"Введите целое число",false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTypeface(Ui.typeface(this,false));
        input.setSelectAllOnFocus(true);
        body.addView(input);
        Ui.addSpace(body,this,8);
        body.addView(Ui.text(this,"0–125000 KB/s. Ноль блокирует трафик; для безлимита используйте ∞.",12,Ui.MUTED,false));
        Ui.addSpace(body,this,12);
        final Dialog[] manual={null};
        body.addView(Ui.button(this,"✓  Применить",0xFF15504D,Ui.CYAN,()->{
            String raw=input.getText().toString().trim();
            int value;
            try{value=Integer.parseInt(raw);}
            catch(NumberFormatException e){input.setError("Введите целое число от 0 до 125000");return;}
            if(value<0||value>125000){input.setError("Допустимо от 0 до 125000 KB/s");return;}
            if(manual[0]!=null)manual[0].dismiss();
            apply.accept(value);
        }));
        manual[0]=Ui.sheet(this,"Собственный лимит","Точный ручной ввод · DL + UL",body);
    }
    private void applyLimitFromSheet(Dialog[] sheet,RouterClient.Device dev,Integer kb){
        if(sheet[0]!=null)sheet[0].dismiss();
        String mac=RouterClient.normalizeMac(dev.mac);
        if(mac.isEmpty()){toast("Некорректный MAC устройства");return;}
        // A fresh status reply does not make an older station_list fresh.
        // Never apply router-side tc rules using a stale client/IP snapshot.
        boolean live=routerStatusFresh()&&error.isEmpty()
            &&clientsAt>0&&System.currentTimeMillis()-clientsAt<=Math.max(10000L,refreshInterval()*3L)
            &&sectionError.isEmpty();
        boolean present=false;
        for(RouterClient.Device active:devices)if(active!=null&&mac.equals(RouterClient.normalizeMac(active.mac))){present=true;break;}
        if(!live||!present){
            confirm("Сохранить лимит локально","Свежий список подключённых устройств AX8 недоступен либо устройство отсутствует в нём. Сохранить «"+
                limitName(kb)+"» для "+prefs.getString("alias_"+mac,dev.name)+
                "? Это НЕ отправит команду роутеру. Применение возможно только после получения свежего списка клиентов и при активном мониторинге AX8.",
                ()->{
                    Map<String,Integer> saved=qos.limits();
                    if(kb==null)saved.remove(mac);else saved.put(mac,kb);
                    qos.persist(saved,mac);
                    toast("Лимит сохранён локально. Применение на AX8 не подтверждено.");
                    draw(true);
                });
            return;
        }
        confirm("Применить QoS","Установить для "+prefs.getString("alias_"+mac,dev.name)+" лимит «"+
            limitName(kb)+"» на приём и отдачу через Telnet?",
            ()->action("QoS · "+limitName(kb),p->{
                // Confirmation can remain open while a client reconnects or receives
                // a different DHCP address. Re-read the station list on the worker,
                // and never use the UI snapshot for router-side QoS changes.
                List<RouterClient.Device> current=api.devices();
                boolean found=false;
                for(RouterClient.Device candidate:current){
                    if(candidate!=null&&mac.equals(RouterClient.normalizeMac(candidate.mac))){
                        found=true;break;
                    }
                }
                if(!found)throw new java.io.IOException(
                    "Устройство больше не подключено. Лимит не применён; обновите список клиентов.");
                qos.setLimit(mac,kb,current,p);
            }));
    }
    private void sms(){
        smsSignature=smsFingerprint();
        addTitle("Сообщения SIM","SMS из памяти маршрутизатора, без веб-браузера");
        long unread=messages.stream().filter(RouterClient.Sms::unread).count();
        LinearLayout counter=Ui.card(this);LinearLayout row=Ui.horizontal(this);
        LinearLayout a=Ui.vertical(this);a.addView(Ui.text(this,smsAt>0?String.valueOf(messages.size()):"—",29,Ui.CYAN,true));
        a.addView(Ui.text(this,"СООБЩЕНИЙ",11,Ui.MUTED,true));row.addView(a,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout b=Ui.vertical(this);b.addView(Ui.text(this,smsAt>0?String.valueOf(unread):"—",29,unread>0?Ui.ORANGE:Ui.GREEN,true));
        b.addView(Ui.text(this,"НЕПРОЧИТАННЫХ",11,Ui.MUTED,true));row.addView(b,new LinearLayout.LayoutParams(0,-2,1));counter.addView(row);
        Ui.addSpace(counter,this,14);
        LinearLayout buttons=Ui.horizontal(this);
        TextView send=Ui.button(this,"✉  Отправить",Ui.CARD2,Ui.CYAN,()->composeSms());
        TextView clear=Ui.button(this,"⌫  Удалить все",Ui.CARD2,Ui.RED,()->clearSms());
        if(smsAt==0)clear.setAlpha(.45f);
        buttons.addView(send,new LinearLayout.LayoutParams(0,d(48),1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(48),1);lp.leftMargin=d(8);buttons.addView(clear,lp);
        counter.addView(buttons);addCard(counter);
        if(messages.isEmpty())rowCard(smsAt>0?"Сообщений нет":"Список SMS ещё не загружен",
            smsAt>0?"Новые сообщения появятся автоматически после опроса AX8":"Ожидание ответа AX8. При необходимости повторите обновление",null,Ui.MUTED,null);
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
        EditText x=new EditText(this);x.setTypeface(Ui.typeface(this,false));x.setText(value);x.setHint(hint);x.setHintTextColor(Ui.MUTED);
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
        smsRecipientInput.setInputType(InputType.TYPE_CLASS_PHONE);smsRecipientInput.setTypeface(Ui.typeface(this,false));
        recipient.addView(smsRecipientInput,new LinearLayout.LayoutParams(0,-2,1));
        TextView pick=Ui.button(this,"◉",Ui.CARD2,Ui.CYAN,()->pickPhoneContact());
        pick.setContentDescription("Выбрать номер из контактов");
        LinearLayout.LayoutParams pickParams=Ui.lp(this,52,50);pickParams.leftMargin=d(10);
        recipient.addView(pick,pickParams);body.addView(recipient);Ui.addSpace(body,this,17);
        body.addView(Ui.text(this,"СООБЩЕНИЕ",11,Ui.MUTED,true));Ui.addSpace(body,this,7);
        smsMessageInput=themedField(prefillMessage,"Текст SMS",true);
        smsMessageInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);smsMessageInput.setTypeface(Ui.typeface(this,false));
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
    private ServiceRestrictions services(){return new ServiceRestrictions(this,prefs,api);}
    private void serviceScopes(){
        ServiceRestrictions controller=services();
        LinearLayout body=Ui.vertical(this);
        body.addView(Ui.text(this,"Правила хранятся на телефоне и передаются на AX8 отдельно. Общие правила действуют дополнительно к индивидуальным.",12,Ui.MUTED,false));
        Ui.addSpace(body,this,10);
        final Dialog[] sheet={null};
        body.addView(Ui.button(this,"Все устройства",Ui.CARD2,Ui.CYAN,()->{sheet[0].dismiss();serviceEditor("ALL");}));
        Ui.addSpace(body,this,8);
        body.addView(Ui.text(this,"КОНКРЕТНОЕ УСТРОЙСТВО",12,Ui.MUTED,true));
        TreeSet<String> macs=new TreeSet<>();
        for(RouterClient.Device d:devices){String m=RouterClient.normalizeMac(d.mac);if(!m.isEmpty())macs.add(m);}
        for(String key:prefs.getAll().keySet()){
            String raw=key.startsWith("cached_client_name_")?key.substring(19):key.startsWith("alias_")?key.substring(6):"";
            String m=RouterClient.normalizeMac(raw);if(!m.isEmpty())macs.add(m);
        }
        for(String scope:controller.rules().keySet())if(!"ALL".equals(scope))macs.add(scope);
        for(String mac:macs){
            Ui.addSpace(body,this,6);
            body.addView(Ui.button(this,savedLimitDeviceName(mac)+" · "+mac,Ui.CARD2,Ui.TEXT,()->{sheet[0].dismiss();serviceEditor(mac);}));
        }
        Ui.addSpace(body,this,12);
        body.addView(Ui.button(this,"↻  Проверить статус на AX8",Ui.CARD2,Ui.CYAN,()->{
            sheet[0].dismiss();
            workers.execute(()->{String result;try{result=controller.status();}catch(Exception e){result="AX8 недоступен: "+e.getMessage();}
                final String report=result;handler.post(()->alert("Ограничение сервисов",report));});
        }));
        Ui.addSpace(body,this,8);
        body.addView(Ui.button(this,"✓  Передать сохранённые правила на AX8",Ui.CARD2,Ui.CYAN,()->{
            sheet[0].dismiss();confirmServiceDeploy(controller);
        }));
        Ui.addSpace(body,this,8);
        body.addView(Ui.button(this,"⊘  Снять все ограничения сервисов",Ui.CARD2,Ui.RED,()->{
            sheet[0].dismiss();
            confirm("Снять ограничения сервисов?","Удалить только правила этого раздела, не меняя QoS и список блокировок?",()->{
                controller.save(new TreeMap<>());action("Передача пустого плана сервисов",controller::deploy);
            });
        }));
        sheet[0]=Ui.sheet(this,"Ограничение сервисов","Локальных правил: "+controller.count(),body);
    }
    private void confirmServiceDeploy(ServiceRestrictions controller){
        confirm("Передать правила на AX8?","IP-фильтр может затронуть другие сервисы с общими IP. Блокируются и ручные загрузки, и обновления. VPN/новые адреса могут обходить правила. Модуль работает без телефона до перезагрузки AX8; после неё требуется повторная передача. Продолжить?",
            ()->action("Передача плана сервисов",controller::deploy));
    }
    private void serviceEditor(String scope){
        ServiceRestrictions controller=services();
        Map<String,Set<String>> desired=controller.rules();
        Set<String> selected=new TreeSet<>(desired.getOrDefault(scope,Collections.emptySet()));
        LinearLayout body=Ui.vertical(this);final Dialog[] sheet={null};
        body.addView(Ui.text(this,"Выбранные сервисы ограничиваются целиком по известным IP. Не является гарантированной блокировкой всех доменов или только автообновлений.",12,Ui.MUTED,false));
        Ui.addSpace(body,this,10);
        String previous="";
        for(ServiceCatalog.Item item:ServiceCatalog.ITEMS){
            if(!previous.equals(item.category)){
                previous=item.category;Ui.addSpace(body,this,9);
                body.addView(Ui.text(this,previous.toUpperCase(Locale.ROOT),12,Ui.MUTED,true));Ui.addSpace(body,this,6);
            }
            final TextView[] btn={null};boolean on=selected.contains(item.id);
            btn[0]=Ui.button(this,(on?"✓  ":"○  ")+item.name,Ui.CARD2,on?Ui.RED:Ui.TEXT,()->{
                if(!selected.add(item.id))selected.remove(item.id);
                boolean enabled=selected.contains(item.id);
                btn[0].setText((enabled?"✓  ":"○  ")+item.name);btn[0].setTextColor(enabled?Ui.RED:Ui.TEXT);
            });
            body.addView(btn[0]);Ui.addSpace(body,this,5);
        }
        Ui.addSpace(body,this,12);
        Runnable save=()->{
            Map<String,Set<String>> latest=controller.rules();
            if(selected.isEmpty())latest.remove(scope);else latest.put(scope,new TreeSet<>(selected));
            controller.save(latest);
        };
        body.addView(Ui.button(this,"Сохранить локально",Ui.CARD2,Ui.TEXT,()->{
            try{save.run();sheet[0].dismiss();toast("Сохранено на телефоне. На AX8 ещё не применено.");}
            catch(Exception e){alert("Ошибка",e.getMessage());}
        }));
        Ui.addSpace(body,this,8);
        body.addView(Ui.button(this,"✓  Сохранить и передать на AX8",Ui.CARD2,Ui.CYAN,()->{
            try{save.run();sheet[0].dismiss();confirmServiceDeploy(controller);}
            catch(Exception e){alert("Ошибка",e.getMessage());}
        }));
        sheet[0]=Ui.sheet(this,"Ограничение сервисов","ALL".equals(scope)?"Все устройства":savedLimitDeviceName(scope)+" · "+scope,body);
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
        LinearLayout mobile=Ui.card(this);
        Ui.title(mobile,this,"Выбор оператора","Автоматическая регистрация или ручной поиск доступных мобильных сетей");
        Ui.addSpace(mobile,this,10);
        mobile.addView(Ui.infoRow(this,"Текущий оператор",stat("network_provider"),Ui.CYAN));
        mobile.addView(Ui.infoRow(this,"Текущая технология",stat("network_type"),Ui.CYAN));
        Ui.addSpace(mobile,this,9);
        mobile.addView(Ui.button(this,"◉  Автоматически / Ручной поиск",Ui.CARD2,Ui.CYAN,()->showOperatorSelection()));
        Ui.addSpace(mobile,this,8);
        mobile.addView(Ui.button(this,"⌁  Технология сети: Авто / Только 4G",Ui.CARD2,Ui.TEXT,()->showNetworkModes()));
        addCard(mobile);
        LinearLayout apnCard=Ui.card(this);
        Ui.title(apnCard,this,"Профили APN","Переключение существующих профилей, сохранённых на роутере");
        Ui.addSpace(apnCard,this,12);
        apnCard.addView(Ui.button(this,"◉  Список профилей и переключение",Ui.CARD2,Ui.CYAN,()->showApnProfiles()));
        addCard(apnCard);
        LinearLayout bands=Ui.card(this);Ui.title(bands,this,"Фиксация LTE Band","Текущий: "+band()+". После смены потребуется перезагрузка AX8.");Ui.addSpace(bands,this,15);
        LinearLayout grid=Ui.vertical(this);
        int[] bs=RouterClient.SUPPORTED_BANDS;
        for(int i=0;i<bs.length;i+=3){LinearLayout one=Ui.horizontal(this);
            for(int j=i;j<Math.min(i+3,bs.length);j++){int num=bs[j];boolean active=band().equals("B"+num);
                TextView btn=Ui.button(this,"B"+num,active?Ui.CYAN:Ui.CARD2,active?Ui.BG:Ui.TEXT,()->bandConfirm(num));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(49),1);lp.rightMargin=d(8);one.addView(btn,lp);
            }grid.addView(one);Ui.addSpace(grid,this,8);
        }
        bands.addView(grid);bands.addView(Ui.button(this,"AUTO",0xFF174A4B,Ui.CYAN,()->bandConfirm(0)));addCard(bands);
        LinearLayout technical=Ui.card(this);Ui.title(technical,this,"Расширенная диагностика","Поля радиомодуля и параметры LTE");Ui.addSpace(technical,this,9);
        addRows(technical,new String[][]{{"RSRP",reading(rsrp(),"dBm")},{"RSRQ",reading(rsrq(),"dB")},{"SINR",reading(sinr(),"dB")},{"RSSI",rssi()},
            {"Bandwidth",bandwidth()}});
        Ui.addSpace(technical,this,10);
        technical.addView(Ui.button(this,"◈  Полная диагностика модема и роутера",Ui.CARD2,Ui.CYAN,this::showDeepDiagnostics));
        addCard(technical);
        LinearLayout q=Ui.card(this);Ui.title(q,this,"Ограничения трафика","Установленные лимиты хранятся по MAC-адресу и требуют Telnet на AX8.");
        Map<String,Integer> savedLimits=qos.limits();
        Ui.addSpace(q,this,12);q.addView(Ui.infoRow(this,"Сохранено правил",String.valueOf(savedLimits.size()),Ui.CYAN));
        if(savedLimits.isEmpty())q.addView(Ui.text(this,"Сохранённых правил пока нет. Добавьте лимит в разделе «Клиенты».",12,Ui.MUTED,false));
        else {
            // Restore the former compact saved-limit list by default:
            // one device per line, with its configured speed against the right edge.
            Ui.addSpace(q,this,8);
            q.addView(Ui.text(this,"Список сохранённых ограничений",13,Ui.MUTED,true));
            Ui.addSpace(q,this,5);
            if(!savedLimitsExpanded){
                ArrayList<String> macs=new ArrayList<>(savedLimits.keySet());
                macs.sort((left,right)->savedLimitDeviceName(left).compareToIgnoreCase(savedLimitDeviceName(right)));
                for(String mac:macs)savedLimitRow(q,mac,savedLimits.get(mac));
            }
            // Preserve the newer grouped MAC editing UI without replacing the old overview.
            Ui.addSpace(q,this,8);
            q.addView(Ui.button(this,(savedLimitsExpanded?"⌃  ":"⌄  ")+"Управление группами MAC",
                Ui.CARD2,Ui.TEXT,()->{savedLimitsExpanded=!savedLimitsExpanded;if(!savedLimitsExpanded)expandedRuleGroups.clear();draw(true);}));
            if(savedLimitsExpanded){
                Ui.addSpace(q,this,8);
                Map<String,Integer> liveLimits=activeGroupLimits();
                List<SavedClientGroups.Group> groups=SavedClientGroups.build(savedLimits.keySet(),this::savedLimitDeviceName,savedLimits,qos::limitRevision,liveLimits);
                for(SavedClientGroups.Group group:groups)addSavedClientGroup(q,group,savedLimits,liveLimits,expandedRuleGroups);
                Ui.addSpace(q,this,8);
                q.addView(Ui.button(this,"↻  Применить сохранённые лимиты",Ui.CARD2,Ui.TEXT,
                    ()->confirm("Восстановить QoS","Пересобрать правила IPv4/IPv6 на роутере?",()->action("Применение QoS",p->{
                        List<RouterClient.Device> list=api.devices();qos.restore(list,p);
                    }))));
            }
        }
        Ui.addSpace(q,this,10);
        q.addView(Ui.button(this,"◎  Проверить реальные правила tc",Ui.CARD2,Ui.YELLOW,()->{
            workers.execute(()->{String report;try{report=qos.audit("",devices);}catch(Exception e){report="Ошибка диагностики: "+e.getMessage();}
                final String r=report;handler.post(()->alert("Диагностика QoS",r));});
        }));
        Ui.addSpace(q,this,11);
        q.addView(Ui.button(this,"⚡  Массовые лимиты и приоритет",Ui.CARD2,Ui.CYAN,()->bulkControls()));
        addCard(q);
        LinearLayout filters=Ui.card(this);Ui.title(filters,this,"Ограничение сервисов","Автономные правила AX8 · IPv4/IPv6");
        Ui.addSpace(filters,this,10);
        filters.addView(Ui.button(this,"Настроить сервисы",Ui.CARD2,Ui.CYAN,()->serviceScopes()));
        addCard(filters);
    }
    /** Advanced operator selection is firmware-dependent; open the real WebUI instead of guessing SET_NETWORK. */
    private void openRouterNetworkPage(){
        try{
            String host=api.host();
            if(!host.startsWith("http://")&&!host.startsWith("https://"))
                throw new IllegalArgumentException("Некорректный адрес WebUI");
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(host+"/index.html")));
        }catch(Exception e){alert("Выбор оператора","Не удалось открыть WebUI: "+e.getMessage());}
    }
    /** Operator selection is separate from 4G/3G bearer preference. */
    private void showOperatorSelection(){
        if(actionBusy||operatorScanBusy){toast("Дождитесь завершения предыдущего действия");return;}
        displayWork("Проверка режима выбора оператора...");
        workers.execute(()->{
            String mode="",failure=null;
            try{mode=api.mobileOperatorSelectionMode();}
            catch(Exception e){failure=e.getMessage();}
            final String current=mode,err=failure;
            handler.post(()->{
                displayWork("");
                if(!running)return;
                LinearLayout body=Ui.vertical(this);
                String state="auto_select".equals(current)?"Автоматически":
                    "manual_select".equals(current)?"Ручной выбор":"Не определён прошивкой";
                body.addView(Ui.infoRow(this,"Текущий режим",state,Ui.CYAN));
                if(err!=null){
                    Ui.addSpace(body,this,8);
                    body.addView(Ui.text(this,"Не удалось прочитать режим: "+err,12,Ui.ORANGE,false));
                }
                Ui.addSpace(body,this,10);
                body.addView(Ui.text(this,
                    "Автоматически: роутер сам регистрируется в доступной сети. Ручной поиск: модем показывает операторов и их PLMN. Для сканирования мобильный интернет должен быть отключён.",
                    12,Ui.MUTED,false));
                Ui.addSpace(body,this,12);
                final Dialog[] sheet={null};
                body.addView(Ui.button(this,"◉  Автоматически"+("auto_select".equals(current)?" · ВЫБРАНО":" · WebUI"),
                    "auto_select".equals(current)?Ui.CARD:Ui.CARD2,Ui.GREEN,()->{
                        if(sheet[0]!=null)sheet[0].dismiss();
                        if("auto_select".equals(current)){toast("Автоматический выбор уже включён");return;}
                        // SET_BEARER_PREFERENCE chooses a radio technology, not the
                        // operator. Never send it as an unverified auto-registration fix.
                        toast("В WebUI выберите режим регистрации «Автоматически»");
                        openRouterNetworkPage();
                    }));
                Ui.addSpace(body,this,9);
                body.addView(Ui.button(this,"⌕  Найти доступные сети",Ui.CARD2,Ui.CYAN,()->{
                    if(sheet[0]!=null)sheet[0].dismiss();
                    confirm("Поиск мобильных сетей",
                        "Роутер выполнит поиск операторов. Перед этим необходимо отключить мобильные данные в разделе «Мобильное подключение». Wi-Fi останется включённым. Поиск может занимать до 75 секунд. Начать?",
                        ()->scanAvailableOperators());
                }));
                sheet[0]=Ui.sheet(this,"Выбор оператора","Мобильные сети · не Wi-Fi",body);
            });
        });
    }
    private void scanAvailableOperators(){
        if(actionBusy||operatorScanBusy){toast("Дождитесь завершения предыдущего действия");return;}
        operatorScanBusy=true;
        displayWork("Поиск доступных мобильных сетей...");
        workers.execute(()->{
            List<RouterClient.MobileOperator> result=null;String failure=null;
            try{result=api.scanMobileOperators(message->displayWork(message));}
            catch(Exception e){failure=e.getMessage()==null?e.toString():e.getMessage();}
            final List<RouterClient.MobileOperator> found=result;final String err=failure;
            handler.post(()->{
                operatorScanBusy=false;displayWork("");
                if(!running)return;
                if(err!=null){
                    alert("Поиск операторов",err+"\n\nНи одна сеть не выбиралась. Можно открыть WebUI для проверки поддержки поиска.");
                    return;
                }
                if(found==null||found.isEmpty()){alert("Поиск операторов","Модем не вернул доступных сетей.");return;}
                LinearLayout body=Ui.vertical(this);
                body.addView(Ui.text(this,"Найдено сетей: "+found.size()+
                    ". Наличие оператора не гарантирует разрешение регистрации для вашей SIM-карты.",
                    12,Ui.MUTED,false));
                Ui.addSpace(body,this,12);
                final Dialog[] sheet={null};
                for(RouterClient.MobileOperator operator:found){
                    boolean selectable=operator.selectable();
                    String note=operator.plmn+" · "+operator.technology()+
                        (operator.state==2?" · Текущая":"");
                    LinearLayout item=Ui.vertical(this);
                    item.addView(Ui.text(this,operator.name,15,Ui.TEXT,true));
                    Ui.addSpace(item,this,3);
                    item.addView(Ui.text(this,note,12,Ui.MUTED,false));
                    body.addView(item);Ui.addSpace(body,this,6);
                    TextView choose=Ui.button(this,
                        selectable?"Выбрать эту сеть":"Регистрация недоступна (код "+operator.state+")",
                        Ui.CARD2,selectable?Ui.CYAN:Ui.MUTED,()->{
                            if(!selectable)return;
                            if(sheet[0]!=null)sheet[0].dismiss();
                            confirm("Зарегистрироваться в сети?",
                                operator.name+" · "+operator.plmn+" · "+operator.technology()+
                                "\n\nРучная регистрация может прервать интернет или быть отклонена оператором. При неудаче верните автоматический выбор через WebUI. Продолжить?",
                                ()->action("Регистрация оператора",p->api.registerMobileOperator(operator,p)));
                        });
                    if(!selectable){choose.setEnabled(false);choose.setAlpha(.6f);}
                    body.addView(choose);Ui.addSpace(body,this,12);
                }
                body.addView(Ui.button(this,"↻  Повторить поиск",Ui.CARD2,Ui.TEXT,()->{
                    if(sheet[0]!=null)sheet[0].dismiss();
                    scanAvailableOperators();
                }));
                Ui.addSpace(body,this,9);
                body.addView(Ui.button(this,"◉  Автоматически · WebUI",Ui.CARD2,Ui.GREEN,()->{
                    if(sheet[0]!=null)sheet[0].dismiss();
                    openRouterNetworkPage();
                }));
                sheet[0]=Ui.sheet(this,"Найденные мобильные сети","Список, полученный непосредственно от модема",body);
            });
        });
    }
    private void showNetworkModes(){
        if(actionBusy){toast("Дождитесь завершения действия");return;}
        displayWork("Чтение режима сети...");
        workers.execute(()->{
            String raw="",failure=null;
            try{raw=api.networkPreference();}
            catch(Exception e){failure=e.getMessage();}
            final String current=raw,err=failure;
            handler.post(()->{
                displayWork("");
                if(!running)return;
                if(err!=null){alert("Режим сети","Не удалось получить настройки: "+err);return;}
                if(current.isEmpty()){
                    alert("Режим сети","Эта прошивка не возвращает подтверждённый режим. Команды переключения отключены. Используйте WebUI роутера.");
                    return;
                }
                String shown="NETWORK_auto".equals(current)?"Автоматически":
                    "Only_LTE".equals(current)?"Только 4G":
                    ("Only_WCDMA".equals(current)||"TD_W".equals(current))?"Только 3G":"Текущий: "+current;
                LinearLayout body=Ui.vertical(this);
                body.addView(Ui.text(this,"Сейчас: "+shown,14,Ui.TEXT,true));
                Ui.addSpace(body,this,8);
                body.addView(Ui.text(this,"После переключения связь с роутером может временно пропасть. 3G и ручная регистрация оператора зависят от прошивки.",12,Ui.MUTED,false));
                Ui.addSpace(body,this,12);
                final Dialog[] sheet={null};
                String[] codes={"NETWORK_auto","Only_LTE"};
                String[] names={"Автоматически","Только 4G (LTE)"};
                for(int i=0;i<codes.length;i++){
                    String code=codes[i],name=names[i];boolean active=code.equals(current);
                    body.addView(Ui.button(this,(active?"✓  ":"○  ")+name,
                        active?Ui.CYAN:Ui.CARD2,active?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                            if(active)return;
                            if(sheet[0]!=null)sheet[0].dismiss();
                            confirm("Сменить режим сети",
                                "Переключить роутер на «"+name+"»? Мобильный интернет может временно отключиться. Продолжить?",
                                ()->action("Выбор режима сети",p->{
                                    p.note("Применение режима сети...");
                                    api.setNetworkPreference(code);
                                }));
                        }));
                    Ui.addSpace(body,this,8);
                }
                sheet[0]=Ui.sheet(this,"Выбор режима сети","Проверенные значения WebUI",body);
            });
        });
    }
    private void showApnProfiles(){
        if(actionBusy){toast("Дождитесь завершения действия");return;}
        displayWork("Чтение профилей APN...");
        workers.execute(()->{
            RouterClient.ApnCatalog result=null;String failure=null;
            try{result=api.apnProfiles();}
            catch(Exception e){failure=e.getMessage();}
            final RouterClient.ApnCatalog catalog=result;final String err=failure;
            handler.post(()->{
                displayWork("");
                if(!running)return;
                if(err!=null){
                    alert("Профили APN",err+"\n\nКоманды не отправлялись. Для другой прошивки используйте настройки APN в WebUI.");
                    return;
                }
                LinearLayout body=Ui.vertical(this);
                body.addView(Ui.text(this,"Профили прочитаны из памяти роутера. Выбор изменяет APN мобильного подключения, а не настройки телефона.",12,Ui.MUTED,false));
                Ui.addSpace(body,this,12);
                final Dialog[] sheet={null};
                for(RouterClient.ApnProfile profile:catalog.profiles){
                    boolean active=profile.index==catalog.currentIndex;
                    LinearLayout item=Ui.vertical(this);
                    item.addView(Ui.text(this,(active?"✓  ":"○  ")+profile.name,15,active?Ui.CYAN:Ui.TEXT,true));
                    Ui.addSpace(item,this,3);
                    item.addView(Ui.text(this,"APN: "+profile.apn+" · "+profile.pdpType,12,Ui.MUTED,false));
                    body.addView(item);
                    Ui.addSpace(body,this,6);
                    body.addView(Ui.button(this,active?"Активный профиль":"Выбрать этот профиль",
                        active?Ui.CARD:Ui.CARD2,active?Ui.GREEN:Ui.CYAN,()->{
                            if(active)return;
                            if(sheet[0]!=null)sheet[0].dismiss();
                            confirm("Сменить APN",
                                "Сделать «"+profile.name+"» ("+profile.apn+") основным профилем? Интернет может отключиться. При ошибке потребуется вернуть прежний APN в WebUI.",
                                ()->action("Переключение APN",p->{
                                    p.note("Запись профиля APN...");
                                    api.selectApnProfile(profile.index);
                                }));
                        }));
                    Ui.addSpace(body,this,14);
                }
                sheet[0]=Ui.sheet(this,"Профили APN","Только существующие настройки AX8",body);
            });
        });
    }
    private String rssi(){
        if(noCellService())return "—";
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
    private String bandwidth(){if(noCellService())return "—";String nativeVal=firstStatus("lte_bandwidth","lte_bw","current_bandwidth","dl_bandwidth");
        if(!nativeVal.equals("—"))return nativeVal.toLowerCase(Locale.US).contains("mhz")?nativeVal:nativeVal+" MHz";
        String code=stat("nv_bandwidth");return code.equals("1")?"20 MHz":code.equals("2")?"10 MHz":"—";
    }
    private void networkReconnect(){confirm("Переподключить WAN","Мобильный интернет временно отключится. Продолжить?",()->action("Переподключение",p->api.reconnect(p)));}
    private void bandConfirm(int b){
        String label=b==0?"AUTO":"B"+b;
        confirm("Сменить LTE Band","Применить "+label+"? Роутер перезагрузится. Дождитесь подтверждения LockBandSuc перед перезагрузкой.",
            ()->action("Band Lock "+label,p->api.setBand(b,p),b));
    }
    private boolean clientListStale(){
        return clientsAt>0&&System.currentTimeMillis()-clientsAt>Math.max(10000L,refreshInterval()*3L);
    }
    private String clientSkeletonSignature(){
        StringBuilder b=new StringBuilder(sectionError).append(";CLIENTS_READY:").append(clientsAt>0)
            .append(";CLIENTS_STALE:").append(clientListStale())
            .append(";OWNER:").append(ownerMac).append(";MONITOR:").append(monitorOn).append(';');
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
        if(knownClientsExpanded)b.append(savedGroupSignature());
        return b.toString();
    }
    private void updateClientsLive(){
        if(!clientsSignature.equals(clientSkeletonSignature())){draw(true);return;}
        if(qosStatusLive!=null)qosStatusLive.setText("QoS · "+uiQosWatch.status());
        for(RouterClient.Device dev:devices){
            DeviceTrafficMonitor.Rate r=displayedRate(dev);
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
        StringBuilder b=new StringBuilder(sectionError).append(";SMS_READY:").append(smsAt>0).append(';');
        // Include all fields captured by the card and its click listener.
        // JSON array encoding avoids delimiter ambiguity and String.hashCode collisions.
        for(RouterClient.Sms sms:messages){
            b.append(new org.json.JSONArray().put(sms.id).put(sms.tag)
                .put(sms.number).put(sms.date).put(sms.content).toString()).append(';');
        }
        return b.toString();
    }
    private void updateSmsLive(){if(!smsSignature.equals(smsFingerprint()))draw(true);}
    private String networkSkeletonSignature(){
        return band()+"/"+stat("ppp_status")+"/"+qos.limits().size()+
            (savedLimitsExpanded?"/"+savedGroupSignature():"");
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
        // Keep collapsible settings headers comfortably tappable at large font scales.
        head.setMinimumHeight(d(48));
        head.setClickable(true);
        head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        head.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.setClassName(android.widget.Button.class.getName());
                boolean expanded=expandedSettings.contains(key);
                info.setCheckable(true);
                info.setChecked(expanded);
                // Make the action explicit instead of announcing an unlabeled click.
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                    expanded?"Свернуть раздел":"Раскрыть раздел"));
            }
        });
        TextView emblem=Ui.text(this,symbol,22,Ui.CYAN,true);
        emblem.setGravity(Gravity.CENTER);emblem.setMinWidth(d(39));emblem.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);head.addView(emblem);
        LinearLayout words=Ui.vertical(this);words.setPadding(d(9),0,d(5),0);
        words.addView(Ui.text(this,title,16,Ui.TEXT,true));Ui.addSpace(words,this,4);
        words.addView(Ui.text(this,hint,12,Ui.MUTED,false));
        words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        head.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=Ui.text(this,expandedSettings.contains(key)?"⌃":"⌄",24,Ui.CYAN,true);
        arrow.setGravity(Gravity.CENTER);arrow.setMinWidth(d(30));arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);head.addView(arrow);
        head.setContentDescription(title+", "+hint+(expandedSettings.contains(key)?", раскрыто":", свёрнуто"));
        head.setFocusable(true);
        wrapper.addView(head);
        LinearLayout body=Ui.vertical(this);body.setPadding(0,d(12),0,0);
        boolean opened=expandedSettings.contains(key);
        body.setVisibility(opened?View.VISIBLE:View.GONE);wrapper.addView(body);
        if(opened)populateSettings(body,populate);
        head.setOnClickListener(v->{
            if(expandedSettings.contains(key)){
                expandedSettings.remove(key);arrow.setText("⌄");body.animate().cancel();
                // A rapid close during the fade-in must not leave a transparent
                // expanded section on the next tap.
                body.setAlpha(1f);body.setVisibility(View.GONE);
                // Collapsing a long group can move its header above the visible
                // viewport when ScrollView clamps the old scroll position.
                // Keep the tapped header reachable without resetting the page.
                head.post(()->{
                    if(tab==4&&head.isAttachedToWindow()&&scroll!=null)
                        head.requestRectangleOnScreen(new android.graphics.Rect(
                            0,0,head.getWidth(),head.getHeight()),false);
                });
            }else{
                expandedSettings.add(key);arrow.setText("⌃");body.removeAllViews();
                populateSettings(body,populate);body.setVisibility(View.VISIBLE);
                body.animate().cancel();
                if(Ui.MOTION){body.setAlpha(0f);body.animate().alpha(1f).setDuration(190).start();}
                else body.setAlpha(1f);
            }
            prefs.edit().putStringSet("expanded_settings",new java.util.LinkedHashSet<>(expandedSettings)).apply();
            head.setContentDescription(title+", "+hint+(expandedSettings.contains(key)?", раскрыто":", свёрнуто"));
            // Notify TalkBack immediately when a settings group changes state.
            head.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
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
        final TextView modeLabel=Ui.text(this,initialAuto?"РЕЖИМ: АВТОМАТИЧЕСКИЙ":"РЕЖИМ: РУЧНОЙ",11,initialAuto?Ui.CYAN:Ui.MUTED,true);
        modeLabel.setContentDescription(initialAuto?"Режим мониторинга: автоматический":"Режим мониторинга: ручной");
        refresh.addView(modeLabel);Ui.addSpace(refresh,this,6);
        final TextView manualLabel=Ui.text(this,initialAuto?"РУЧНОЙ ИНТЕРВАЛ · НЕАКТИВЕН":"РУЧНОЙ ИНТЕРВАЛ · АКТИВЕН",11,initialAuto?Ui.MUTED:Ui.CYAN,true);
        final TextView[] autoButton={null};
        autoButton[0]=Ui.button(this,initialAuto?"✓  Автоматически":"○  Автоматически",initialAuto?Ui.CYAN:Ui.CARD2,
            initialAuto?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                // The selected mode is already active: keep scroll, focus and polling cadence intact.
                if(automaticPolling())return;
                prefs.edit().putBoolean("refresh_auto",true).apply();
                currentRate.setText(String.format(Locale.US,"%.1f с",autoPollMs/1000f));
                modeLabel.setText("РЕЖИМ: АВТОМАТИЧЕСКИЙ");
                modeLabel.setTextColor(Ui.CYAN);
                modeLabel.setContentDescription("Режим мониторинга: автоматический");
                manualLabel.setText("РУЧНОЙ ИНТЕРВАЛ · НЕАКТИВЕН");
                manualLabel.setTextColor(Ui.MUTED);
                autoButton[0].setText("✓  Автоматически");
                autoButton[0].setSelected(true);
                if(Build.VERSION.SDK_INT>=30)autoButton[0].setStateDescription("Выбрано");
                autoButton[0].setTextColor(Ui.contrastOn(Ui.CYAN));
                autoButton[0].setBackground(Ui.shape(this,Ui.CYAN,0,Math.max(4,Ui.CORNER-7)));
                handler.removeCallbacks(ticker);if(running)handler.post(ticker);
            });
        // Expose the selected state without relying on the decorative checkmark.
        autoButton[0].setContentDescription("Автоматический интервал мониторинга");
        autoButton[0].setSelected(initialAuto);
        if(Build.VERSION.SDK_INT>=30)autoButton[0].setStateDescription(initialAuto?"Выбрано":"Не выбрано");
        autoButton[0].setMinimumHeight(d(48));
        LinearLayout autoRow=Ui.horizontal(this);
        autoRow.addView(autoButton[0],new LinearLayout.LayoutParams(0,-2,1.6f));
        currentRate.setPadding(d(8),0,0,0);
        autoRow.addView(currentRate,new LinearLayout.LayoutParams(0,-2,1f));
        refresh.addView(autoRow);Ui.addSpace(refresh,this,12);
        refresh.addView(manualLabel);Ui.addSpace(refresh,this,4);
        SeekBar poll=new SeekBar(this);poll.setMax(45);poll.setProgress((manual-500)/100);
        poll.setContentDescription("Ручной интервал опроса, от 0,5 до 5 секунд");
        if(Build.VERSION.SDK_INT>=30)poll.setStateDescription(String.format(Locale.US,"%.1f секунды",manual/1000f));
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
                if(fromUser){
                    currentRate.setText(String.format(Locale.US,"%.1f с",(value+5)/10f));
                    if(Build.VERSION.SDK_INT>=30)b.setStateDescription(String.format(Locale.US,"%.1f секунды",(value+5)/10f));
                }
            }
            public void onStopTrackingTouch(SeekBar b){
                int selected=(Math.max(0,Math.min(45,b.getProgress()))+5)*100;
                prefs.edit().putBoolean("refresh_auto",false).putInt("refresh_ms",selected).apply();
                currentRate.setText(String.format(Locale.US,"%.1f с",selected/1000f));
                modeLabel.setText("РЕЖИМ: РУЧНОЙ");
                modeLabel.setTextColor(Ui.MUTED);
                modeLabel.setContentDescription("Режим мониторинга: ручной");
                manualLabel.setText("РУЧНОЙ ИНТЕРВАЛ · АКТИВЕН");
                manualLabel.setTextColor(Ui.CYAN);
                autoButton[0].setText("○  Автоматически");
                autoButton[0].setSelected(false);
                if(Build.VERSION.SDK_INT>=30)autoButton[0].setStateDescription("Не выбрано");
                autoButton[0].setTextColor(Ui.TEXT);
                autoButton[0].setBackground(Ui.shape(MainActivity.this,Ui.CARD2,0,Math.max(4,Ui.CORNER-7)));
                handler.removeCallbacks(ticker);if(running)handler.post(ticker);
                b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        });
        refresh.addView(Ui.text(this,"Автоматически: интервал 0,5–5 с подбирается по скорости ответа роутера. При ошибках опрос замедляется, после восстановления ускоряется. Для ручного режима переместите ползунок.",12,Ui.MUTED,false));
        Ui.addSpace(refresh,this,8);
        LinearLayout lastRow=Ui.infoRow(this,"Последний полный опрос",lastCompleteAt==0?"—":android.text.format.DateFormat.format("HH:mm:ss",lastCompleteAt).toString(),Ui.CYAN);
        settingsValues.put("last_poll",(TextView)lastRow.getChildAt(1));refresh.addView(lastRow);
        addCard(refresh);
    }
    private void addDiagnosticsInfoSettings(){
        LinearLayout storage=Ui.card(this);Ui.title(storage,this,"Данные и диагностика","Экспорт настроек для проверки и восстановления");Ui.addSpace(storage,this,11);
        storage.addView(Ui.button(this,"◈  Полная диагностика AX8 (только чтение)",Ui.CARD2,Ui.CYAN,this::showDeepDiagnostics));
        Ui.addSpace(storage,this,9);
        storage.addView(Ui.button(this,"⇧  Экспорт текущего снимка (JSON)",Ui.CARD2,Ui.TEXT,()->exportDiagnostic()));
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
        // Update toggle labels in place: rebuilding the settings page would
        // collapse the user's context and jump the scroll position.
        final TextView[] launchButton={null};
        launchButton[0]=Ui.button(this,
            prefs.getBoolean("ota_on_launch",true)?"☑ Проверять при запуске":"☐ Проверять при запуске",
            Ui.CARD2,Ui.TEXT,()->{
                boolean next=!prefs.getBoolean("ota_on_launch",true);
                prefs.edit().putBoolean("ota_on_launch",next).apply();
                launchButton[0].setText(next?"☑ Проверять при запуске":"☐ Проверять при запуске");
                launchButton[0].setContentDescription((next?"Включено: ":"Выключено: ")+"проверять обновления при запуске");
            });
        launchButton[0].setContentDescription(
            (prefs.getBoolean("ota_on_launch",true)?"Включено: ":"Выключено: ")+"проверять обновления при запуске");
        panel.addView(launchButton[0]);
        Ui.addSpace(panel,this,9);
        final TextView[] backgroundButton={null};
        backgroundButton[0]=Ui.button(this,
            prefs.getBoolean("ota_background",false)?"☑ Проверять в фоне (24 ч)":"☐ Проверять в фоне (24 ч)",
            Ui.CARD2,Ui.TEXT,()->{
                boolean next=!prefs.getBoolean("ota_background",false);
                prefs.edit().putBoolean("ota_background",next).apply();
                UpdateSchedule.configure(this);
                backgroundButton[0].setText(next?"☑ Проверять в фоне (24 ч)":"☐ Проверять в фоне (24 ч)");
                backgroundButton[0].setContentDescription((next?"Включено: ":"Выключено: ")+"проверять обновления в фоне каждые 24 часа");
            });
        backgroundButton[0].setContentDescription(
            (prefs.getBoolean("ota_background",false)?"Включено: ":"Выключено: ")+"проверять обновления в фоне каждые 24 часа");
        panel.addView(backgroundButton[0]);
        Ui.addSpace(panel,this,9);
        panel.addView(Ui.text(this,"Перед установкой проверяются версия, пакет, контрольная сумма APK и цифровая подпись приложения.",12,Ui.MUTED,false));
        addCard(panel);
    }
    private String BuildConfigVersion(){
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
        catch(Exception ignored){return "1.5.3";}
    }
    private boolean startupOtaChecked=false,otaCheckBusy=false;
    private long installedOtaCode(){
        try{return UpdateManager.currentCode(this);}
        catch(Exception ignored){return 0L;}
    }
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
                dialog.offer(release.versionName,release.notesFor(installedOtaCode()),
                    ()->downloadOta(release,dialog));
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
        final TextView[] awakeButton={null};
        boolean awake=prefs.getBoolean("background_keep_awake",true);
        awakeButton[0]=Ui.button(this,awake?"☑ Не засыпать в фоне (CPU + Wi-Fi)":"☐ Не засыпать в фоне (CPU + Wi-Fi)",Ui.CARD2,Ui.TEXT,()->{
            boolean next=!prefs.getBoolean("background_keep_awake",true);
            prefs.edit().putBoolean("background_keep_awake",next).apply();
            awakeButton[0].setText((next?"☑ ":"☐ ")+"Не засыпать в фоне (CPU + Wi-Fi)");
            awakeButton[0].setContentDescription((next?"Включено: ":"Выключено: ")+"не засыпать в фоне, процессор и Wi-Fi");
            if(Build.VERSION.SDK_INT>=30)awakeButton[0].setStateDescription(next?"Включено":"Выключено");
        });
        awakeButton[0].setContentDescription((awake?"Включено: ":"Выключено: ")+"не засыпать в фоне, процессор и Wi-Fi");
        if(Build.VERSION.SDK_INT>=30)awakeButton[0].setStateDescription(awake?"Включено":"Выключено");
        c.addView(awakeButton[0]);
        Ui.addSpace(c,this,9);
        final TextView[] watchButton={null};
        boolean watch=prefs.getBoolean("background_qos_watch",true);
        watchButton[0]=Ui.button(this,watch?"☑ Контроль превышения QoS":"☐ Контроль превышения QoS",Ui.CARD2,Ui.YELLOW,()->{
            boolean next=!prefs.getBoolean("background_qos_watch",true);
            prefs.edit().putBoolean("background_qos_watch",next).apply();
            watchButton[0].setText((next?"☑ ":"☐ ")+"Контроль превышения QoS");
            watchButton[0].setContentDescription((next?"Включено: ":"Выключено: ")+"контроль превышения QoS");
            if(Build.VERSION.SDK_INT>=30)watchButton[0].setStateDescription(next?"Включено":"Выключено");
        });
        watchButton[0].setContentDescription((watch?"Включено: ":"Выключено: ")+"контроль превышения QoS");
        if(Build.VERSION.SDK_INT>=30)watchButton[0].setStateDescription(watch?"Включено":"Выключено");
        c.addView(watchButton[0]);
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
            RouterEndpointConfig previous=new RouterEndpointConfig(prefs.getString("host","192.168.150.1"),"","");
            RouterEndpointConfig selected=new RouterEndpointConfig(h,"","");
            if(!previous.sameHost(selected))RouterBackgroundService.clearCachedSnapshot(prefs);
            prefs.edit().putString("host",h).putString("username",username.getText().toString())
                .putString("password",password.getText().toString()).putString("telnet_user",telnetUser.getText().toString())
                .putString("telnet_pass",telnetPass.getText().toString()).apply();
            qos.close();traffic.close();api.configure(h,username.getText().toString(),password.getText().toString());
            qos=new QosController(api,prefs);traffic=new DeviceTrafficMonitor(api,prefs);
            error="";blocked=null;status=null;statusAt=clientsAt=smsAt=0;
            resetInternetResult();refresh(true);draw(true);
        });
    }
    private EditText settingField(LinearLayout parent,String title,String value,boolean secret){
        parent.addView(Ui.text(this,title,12,Ui.MUTED,true));Ui.addSpace(parent,this,6);
        EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setTextSize(15);e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTypeface(Ui.typeface(this,false));
        parent.addView(e);Ui.addSpace(parent,this,10);return e;
    }
    private void addTrafficSettings(){
        LinearLayout c=Ui.card(this);Ui.title(c,this,"Трафик устройств","Счётчики IPv4/IPv6 и адаптивный приоритет хозяина");
        Ui.addSpace(c,this,12);
        c.addView(Ui.button(this,monitorOn?"● Мониторинг клиентов ВКЛ":"○ Мониторинг клиентов ВЫКЛ",
            monitorOn?Ui.CYAN:Ui.CARD2,monitorOn?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                boolean turnOn=!monitorOn;
                if(turnOn){confirm("Включить мониторинг","На AX8 будут созданы цепочки AX8_APP_MON и AX8_APP_MON6 через Telnet. Может увеличить нагрузку.",()->{
                    trafficMonitorEpoch++;monitorOn=true;trafficError="";prefs.edit().putBoolean("traffic_monitor",true).apply();draw(true);refresh(true);
                });}
                else{monitorOn=false;trafficMonitorEpoch++;ratedDevices=null;displayedRates=Collections.emptyMap();trafficError="";prefs.edit().putBoolean("traffic_monitor",false).apply();
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
        themeAnchorView=null;themeAnchorKey=null;
        String[] keys={"dark","amoled","slate","light"};String[] labels={"Тёмная","AMOLED","Графит","Светлая"};
        for(int i=0;i<4;i+=2){LinearLayout line=Ui.horizontal(this);
            for(int j=i;j<i+2;j++){final String key=keys[j];boolean active=key.equals(prefs.getString("theme","dark"));
                TextView v=Ui.button(this,labels[j],active?Ui.CYAN:Ui.CARD2,active?Ui.contrastOn(Ui.CYAN):Ui.TEXT,()->{
                    // Selecting the current theme must not rebuild the screen or reset custom colors.
                    if(key.equals(prefs.getString("theme","dark")))return;
                    prefs.edit().putString("theme",key).remove("background_color").remove("card_color").apply();applyAppearance();
                });
                // Allow two-line theme names at enlarged system font sizes.
                // A fixed 48 dp height clipped labels on accessibility font scales.
                v.setMinHeight(d(48));v.setMinimumHeight(d(48));
                v.setSingleLine(false);v.setMaxLines(2);
                if((pendingThemeAnchorKey!=null && pendingThemeAnchorKey.equals(key)) ||
                        (pendingThemeAnchorKey==null && active)){
                    themeAnchorView=v;themeAnchorKey=key;
                }
                LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,-2,1);
                l.rightMargin=d(8);line.addView(v,l);
            }
            themes.addView(line);Ui.addSpace(themes,this,8);
        }
        theme.addView(themes);Ui.addSpace(theme,this,10);
        theme.addView(Ui.button(this,"◉  Цвет акцента",Ui.CARD2,Ui.CYAN,()->chooseAccentPalette()));
        Ui.addSpace(theme,this,8);
        theme.addView(Ui.button(this,"▣  Цвет фона",Ui.CARD2,Ui.TEXT,()->pickColor("background_color","Цвет фона",Ui.BG)));
        Ui.addSpace(theme,this,8);
        theme.addView(Ui.button(this,"▤  Цвет карточек",Ui.CARD2,Ui.TEXT,()->pickColor("card_color","Цвет карточек",Ui.CARD)));
        Ui.addSpace(theme,this,10);
        final TextView[] batteryToggle={null};
        boolean showBattery=prefs.getBoolean("battery_level_icon",true);
        batteryToggle[0]=Ui.button(this,"▣  Значок уровня батареи · "+(showBattery?"ВКЛ":"ВЫКЛ"),
            Ui.CARD2,Ui.TEXT,()->{
                boolean enabled=!prefs.getBoolean("battery_level_icon",true);
                prefs.edit().putBoolean("battery_level_icon",enabled).apply();
                batteryToggle[0].setText("▣  Значок уровня батареи · "+(enabled?"ВКЛ":"ВЫКЛ"));
                batteryToggle[0].setContentDescription("Значок уровня батареи");
                if(Build.VERSION.SDK_INT>=30)batteryToggle[0].setStateDescription(enabled?"Включено":"Выключено");
                // No screen rebuild: keep the current personalization scroll position.
            });
        batteryToggle[0].setContentDescription("Значок уровня батареи");
        if(Build.VERSION.SDK_INT>=30)batteryToggle[0].setStateDescription(showBattery?"Включено":"Выключено");
        theme.addView(batteryToggle[0]);
        Ui.addSpace(theme,this,5);
        theme.addView(Ui.text(this,"Молния появляется только при подтверждении зарядки роутером, независимо от значка уровня.",
            12,Ui.MUTED,false));
        Ui.addSpace(theme,this,8);
        theme.addView(Ui.button(this,"⚡  Проверить состояние зарядки",Ui.CARD2,Ui.CYAN,()->{
            workers.execute(()->{
                try{
                    JSONObject info=api.batteryDiagnostics();
                    String grouped=info.optString("multi_battery_charging","");
                    String single=info.optString("single_battery_charging","");
                    int multiState=BatteryLevelPolicy.chargingState(grouped);
                    int singleState=BatteryLevelPolicy.chargingState(single);
                    int state=singleState>=0?singleState:multiState;
                    String title=state==1?"Заряжается":state==0?"Не заряжается":"Роутер не сообщил состояние";
                    String report="Состояние: "+title+"\n"+
                        "Общий запрос: "+(grouped.isEmpty()?"нет данных":grouped)+"\n"+
                        "Отдельный запрос: "+(single.isEmpty()?"нет данных":single)+"\n"+
                        "Уровень (WebUI): "+info.optString("Battery_voltage","—")+"\n"+
                        "Заряд (резервное поле): "+info.optString("battery_vol_percent","—")+"\n"+
                        "Индекс уровня: "+info.optString("battery_pers","—")+"\n\n"+
                        "Если оба ответа пустые, значок зарядки нельзя показывать достоверно.";
                    handler.post(()->{
                        if(isFinishing()||isDestroyed())return;
                        LinearLayout content=Ui.vertical(this);
                        TextView value=Ui.text(this,report,13,Ui.TEXT,false);
                        value.setTextIsSelectable(true);content.addView(value);
                        Ui.sheet(this,"Зарядка AX8",title,content);
                    });
                }catch(Exception e){
                    handler.post(()->{
                        if(!isFinishing()&&!isDestroyed())
                            alert("Диагностика зарядки","Не удалось прочитать поля AX8: "+e.getMessage());
                    });
                }
            });
        }));
        Ui.addSpace(theme,this,13);
        final TextView fontScaleLabel=Ui.text(this,"Масштаб текста · "+prefs.getInt("font_scale",100)+"%",14,Ui.TEXT,true);
        theme.addView(fontScaleLabel);
        SeekBar size=new SeekBar(this);size.setMax(55);size.setProgress(prefs.getInt("font_scale",100)-80);
        size.setContentDescription("Масштаб текста, от 80 до 135 процентов");
        size.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                if(fromUser)fontScaleLabel.setText("Масштаб текста · "+(80+value)+"%");
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){
                int next=80+b.getProgress();
                if(next!=prefs.getInt("font_scale",100)){
                    prefs.edit().putInt("font_scale",next).apply();applyAppearance();
                }
            }
        });theme.addView(size);
        Ui.addSpace(theme,this,8);
        final TextView cornerLabel=Ui.text(this,"Скругления · "+prefs.getInt("corner",22)+" dp",14,Ui.TEXT,true);
        theme.addView(cornerLabel);
        SeekBar corner=new SeekBar(this);corner.setMax(34);corner.setProgress(Math.max(0,prefs.getInt("corner",22)-6));
        corner.setContentDescription("Скругления карточек, от 6 до 40 dp");
        corner.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                if(fromUser)cornerLabel.setText("Скругления · "+(6+value)+" dp");
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){
                int next=6+b.getProgress();
                if(next!=prefs.getInt("corner",22)){
                    prefs.edit().putInt("corner",next).apply();applyAppearance();
                }
            }
        });theme.addView(corner);Ui.addSpace(theme,this,10);
        final TextView[] motionToggle={null};
        motionToggle[0]=Ui.button(this,(Ui.MOTION?"●":"○")+"  Анимации · "+(Ui.MOTION?"ВКЛ":"ВЫКЛ"),Ui.CARD2,Ui.TEXT,()->{
            boolean enabled=!prefs.getBoolean("motion",true);
            prefs.edit().putBoolean("motion",enabled).apply();
            Ui.MOTION=enabled;
            motionToggle[0].setText((enabled?"●":"○")+"  Анимации · "+(enabled?"ВКЛ":"ВЫКЛ"));
            motionToggle[0].setContentDescription("Анимации, "+(enabled?"включены":"выключены"));
        });
        motionToggle[0].setContentDescription("Анимации, "+(Ui.MOTION?"включены":"выключены"));
        theme.addView(motionToggle[0]);Ui.addSpace(theme,this,8);
        TextView compactToggle=Ui.button(this,(Ui.COMPACT?"●":"○")+"  Компактные карточки · "+(Ui.COMPACT?"ВКЛ":"ВЫКЛ"),Ui.CARD2,Ui.TEXT,()->{
            int maxY=scroll==null?0:Math.max(0,content.getHeight()-scroll.getHeight());
            int currentY=scroll==null?0:(pendingScrollY>=0?pendingScrollY:scroll.getScrollY());
            pendingCompactScrollFraction=maxY>0?Math.max(0f,Math.min(1f,currentY/(float)maxY)):0f;
            prefs.edit().putBoolean("compact",!Ui.COMPACT).apply();
            applyAppearance();
        });
        compactToggle.setMinHeight(d(48));
        compactToggle.setContentDescription("Компактные карточки, "+(Ui.COMPACT?"включены":"выключены")+
            ". Изменяет плотность карточек, сохраняя положение в настройках");
        theme.addView(compactToggle);Ui.addSpace(theme,this,8);
        theme.addView(Ui.button(this,"↺  Сбросить оформление",Ui.CARD2,Ui.ORANGE,()->confirm("Сброс оформления","Восстановить стандартную тёмную тему и цвета?",()->{
            prefs.edit().remove("theme").remove("accent_color").remove("background_color").remove("card_color")
                .remove("font").remove("font_scale").remove("corner").remove("motion").remove("compact")
                .remove("battery_level_icon").apply();applyAppearance();
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
                item.addView(swatch,Ui.lp(this,-1,48));
                TextView text=Ui.text(this,names[j],12,Ui.TEXT,false);text.setGravity(Gravity.CENTER);
                Ui.addSpace(item,this,5);item.addView(text);
                boolean selected=Ui.CYAN==value;
                item.setContentDescription("Акцентный цвет: "+names[j]+(selected?", выбран":""));
                item.setSelected(selected);
                item.setFocusable(true);
                item.setClickable(true);
                item.setMinimumHeight(d(64));
                item.setOnClickListener(v->{
                    if(value==Ui.CYAN){dlog[0].dismiss();return;}
                    prefs.edit().putInt("accent_color",value).apply();
                    dlog[0].dismiss();applyAppearance();
                });
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
        Integer[] values={null,0,15,20,30,50,100,150,250,300,500,750,1000,-1,2000,5000,10000};
        LinearLayout body=Ui.vertical(this);final Dialog[] sheet={null};
        for(int i=0;i<values.length;i+=3){LinearLayout line=Ui.horizontal(this);
            for(int j=i;j<Math.min(i+3,values.length);j++){
                Integer limit=values[j];boolean manual=Integer.valueOf(-1).equals(limit);
                TextView b=Ui.button(this,manual?"✎":limitName(limit),Ui.CARD2,Ui.CYAN,()->{
                    if(manual){showManualLimit(null,value->applyBulkLimit(sheet,value));return;}
                    applyBulkLimit(sheet,limit);
                });
                if(manual)b.setContentDescription("Ввести собственный массовый лимит");
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(54),1);lp.rightMargin=d(6);line.addView(b,lp);
            }
            for(int j=Math.min(3,values.length-i);j<3;j++)line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
            body.addView(line);Ui.addSpace(body,this,8);
        }
        Ui.addSpace(body,this,8);body.addView(Ui.text(this,"Свой лимит · ползунок 0–1000 KB/s",13,Ui.MUTED,true));
        TextView customText=Ui.text(this,"500 KB/s",17,Ui.CYAN,true);body.addView(customText);
        SeekBar custom=new SeekBar(this);custom.setMax(1000);custom.setProgress(500);
        custom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int v,boolean user){customText.setText(limitName(v));}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });body.addView(custom);Ui.addSpace(body,this,8);
        body.addView(Ui.button(this,"✓  Применить свой лимит",Ui.CARD2,Ui.CYAN,()->{
            applyBulkLimit(sheet,custom.getProgress());
        }));
        sheet[0]=Ui.sheet(this,"Лимит для остальных","Загрузка и отдача · IPv4/IPv6",body);
    }
    private void applyBulkLimit(Dialog[] sheet,Integer limit){
        if(sheet[0]!=null)sheet[0].dismiss();
        confirm("Массовый QoS","Установить "+limitName(limit)+" для всех кроме "+ownerMac+"?",
            ()->action("Массовый QoS",pr->qos.limitEveryoneExcept(ownerMac,limit,api.devices(),pr)));
    }
    /**
     * Explicitly started deep diagnosis. Never runs in the 500 ms poller, in
     * background tasks or on a shared RouterClient session.
     */
    private void showDeepDiagnostics(){
        if(diagnosticBusy){toast("Диагностика уже выполняется");return;}
        diagnosticBusy=true;
        final RouterDiagnostics.Input input=new RouterDiagnostics.Input();
        try{input.cachedRadio=status==null?null:new JSONObject(status.toString());}
        catch(Exception ignored){input.cachedRadio=null;}
        input.cachedRadioAt=statusAt;
        input.deviceCount=devices.size();
        input.devicesAt=clientsAt;
        input.savedLimitCount=qos.limits().size();
        input.qosState=qos.diagnostics();
        input.trafficState=monitorOn?
            (trafficError.isEmpty()?traffic.status():trafficError):"отключён";
        input.connectionError=error;
        input.refreshMs=refreshInterval();
        input.internetState=internetResult;
        input.internetCheckedAgeMs=internetCheckedAt>0?
            android.os.SystemClock.elapsedRealtime()-internetCheckedAt:-1L;
        final String host=prefs.getString("host","192.168.150.1");
        final String username=prefs.getString("username","root");
        final String password=prefs.getString("password","admin");
        final LinearLayout panel=Ui.vertical(this);
        final TextView stage=Ui.text(this,"Подготовка отдельного безопасного опроса…",13,Ui.CYAN,true);
        // Progress messages can be long at large font scales and on narrow screens.
        stage.setSingleLine(false);
        stage.setHorizontallyScrolling(false);
        stage.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
        stage.setPadding(Ui.dp(this,2),Ui.dp(this,4),Ui.dp(this,2),Ui.dp(this,4));
        panel.addView(stage);Ui.addSpace(panel,this,12);
        final TextView report=Ui.text(this,"Собираем подтверждённые данные WebUI. "+
            "Текущий опрос интерфейса не блокируется. Никакие настройки не меняются.",12,Ui.TEXT,false);
        report.setTextIsSelectable(true);
        // Long diagnostic values must wrap instead of clipping on narrow displays.
        report.setHorizontallyScrolling(false);
        report.setSingleLine(false);
        report.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
        stage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        final String[] complete={null};
        final TextView copyReport=Ui.button(this,"▣  Скопировать отчёт",Ui.CARD2,Ui.TEXT,()->{
            if(complete[0]==null){toast("Отчёт ещё формируется");return;}
            android.content.ClipboardManager manager=(android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
            if(manager!=null){
                manager.setPrimaryClip(ClipData.newPlainText("AX8 diagnostics",complete[0]));
                toast("Отчёт скопирован");
            }
        });
        copyReport.setMinHeight(Ui.dp(this,48));
        copyReport.setEnabled(false);
        copyReport.setAlpha(0.5f);
        copyReport.setContentDescription("Скопировать отчёт. Доступно после завершения диагностики");
        panel.addView(copyReport);
        Ui.addSpace(panel,this,8);
        final TextView shareReport=Ui.button(this,"⇧  Поделиться отчётом",Ui.CARD2,Ui.CYAN,()->{
            if(complete[0]==null){toast("Отчёт ещё формируется");return;}
            Intent intent=new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT,"AX8 PRO · глубокая диагностика");
            intent.putExtra(Intent.EXTRA_TEXT,complete[0]);
            startActivity(Intent.createChooser(intent,"Экспорт диагностики AX8"));
        });
        shareReport.setMinHeight(Ui.dp(this,48));
        shareReport.setEnabled(false);
        shareReport.setAlpha(0.5f);
        shareReport.setContentDescription("Поделиться отчётом. Доступно после завершения диагностики");
        panel.addView(shareReport);
        Ui.addSpace(panel,this,11);
        panel.addView(Ui.text(this,"Отчёт может содержать IP-адреса и сведения о сети. Проверьте их перед отправкой.",11,Ui.MUTED,false));
        Ui.addSpace(panel,this,10);
        panel.addView(report);
        final Dialog dialog=Ui.sheet(this,"Полная диагностика AX8",
            "SIM · LTE/CA · WAN · Wi-Fi · питание · QoS",panel);
        try{
            diagnosticWorker.execute(()->{
                RouterDiagnostics.Result result;
                boolean collectionFailed=false;
                try{
                    RouterClient isolated=new RouterClient(host,username,password);
                    result=RouterDiagnostics.collect(isolated,input,message->handler.post(()->{
                        if(!isFinishing()&&!isDestroyed()&&dialog.isShowing())stage.setText("◌  "+message);
                    }));
                    handler.post(()->{
                        if(!isFinishing()&&!isDestroyed()&&dialog.isShowing())
                            stage.setText("◌  Чтение счётчиков tc / iptables (только чтение)");
                    });
                    String counters;
                    try { counters=qos.auditReadOnlyCounters(); }
                    catch(Exception readError){
                        counters="Счётчики не получены: "+
                            String.valueOf(readError.getMessage())+
                            "\nЭто не означает, что QoS выключен. Проверка не меняла правила.";
                    }
                    String section="\n━━ СЧЁТЧИКИ QOS (TELNET, ТОЛЬКО ЧТЕНИЕ) ━━\n"+
                            counters+"\n";
                    String original=result.report;
                    int marker=original.indexOf("\n━━ ПОЛЯ ПРОШИВКИ");
                    String combined=marker<0?original+section:
                            original.substring(0,marker)+section+original.substring(marker);
                    try{result.raw.put("qos_counters_report",counters);}catch(Exception ignored){}
                    result=new RouterDiagnostics.Result(combined,result.raw);
                }catch(Exception e){
                    collectionFailed=true;
                    result=new RouterDiagnostics.Result("Ошибка глубокой диагностики: "+
                        String.valueOf(e.getMessage()),new JSONObject());
                }
                final boolean failed=collectionFailed;
                final String text=result.report;
                handler.post(()->{
                    diagnosticBusy=false;
                    if(isFinishing()||isDestroyed()||!dialog.isShowing())return;
                    complete[0]=text;
                    copyReport.setEnabled(true);
                    copyReport.setAlpha(1f);
                    copyReport.setContentDescription("Скопировать готовый отчёт");
                    shareReport.setEnabled(true);
                    shareReport.setAlpha(1f);
                    shareReport.setContentDescription("Поделиться готовым отчётом");
                    stage.setText(failed
                        ?"Не удалось завершить диагностику · сообщение можно скопировать или отправить"
                        :"✓  Проверка завершена · можно скопировать или отправить");
                    stage.setTextColor(failed?Ui.MUTED:Ui.GREEN);
                    report.setText(text);
                });
            });
        }catch(RejectedExecutionException e){
            diagnosticBusy=false;
            stage.setText("Не удалось запустить диагностику");
            report.setText("Очередь диагностики сейчас недоступна. Закройте окно и повторите попытку позже.");
        }
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