package ru.xarkon.ax8;

import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * On-demand, read-only diagnostics. Uses a SEPARATE WebUI session so it cannot
 * stall or invalidate the foreground 0.5 s polling/authentication session.
 * No Telnet, configuration writes, automatic reconnects or network scans.
 */
public final class RouterDiagnostics {
    private RouterDiagnostics() {}

    public interface Progress { void stage(String message); }

    public static final class Input {
        public JSONObject cachedRadio;
        public long cachedRadioAt;
        public int deviceCount = -1;
        public long devicesAt;
        public int savedLimitCount;
        public String qosState = "";
        public String trafficState = "";
        public String connectionError = "";
        public int refreshMs;
        public int internetState = InternetStatusPolicy.UNKNOWN;
        public long internetCheckedAgeMs = -1;
    }

    public static final class Result {
        public final String report;
        public final JSONObject raw;
        Result(String report, JSONObject raw) { this.report=report; this.raw=raw; }
    }

    private static final class Group {
        final String id, title, fields;
        Group(String id, String title, String fields) {
            this.id=id; this.title=title; this.fields=fields;
        }
    }

    // Candidate read-only fields from common Sanechips/ZTE-style WebUI variants.
    // An empty value is NOT evidence that the modem supports the field.
    private static final Group[] GROUPS = {
        new Group("radio", "РАДИО / LTE", RouterClient.RADIO_FIELDS),
        new Group("sim", "SIM / РЕГИСТРАЦИЯ",
            "sim_status,simcard_status,sim_card_status,pin_status,sim_pin_status,"+
            "modem_main_state,network_register_status,network_reg_status,reg_status,"+
            "network_selection,net_select,net_select_mode,m_netselect_save,"+
            "roam_status,roaming_status,plmn,plmn_mode,service_domain"),
        new Group("wan", "WAN / APN / IP",
            "ppp_status,wan_ipaddr,wan_ip,wan_connect_status,wan_state,"+
            "wan_gateway,wan_dns,wan_dns1,wan_dns2,dns1,dns2,"+
            "ipv6_wan_ipaddr,wan_ipv6_addr,ipv4_pdp_type,ipv6_pdp_type,"+
            "pdp_type,wan_apn,Current_index,network_provider"),
        new Group("advanced", "ДОПОЛНИТЕЛЬНЫЕ ПОКАЗАТЕЛИ LTE / CA / NR",
            "LteBandLockSet,wan_active_band,wan_active_channel,"+
            "lte_ca_status,lte_ca_state,lte_ca_pcell_band,lte_ca_scell_band,"+
            "scell_band,scell_pci,scell_rsrp,scell_rsrq,"+
            "nv_ca_status,nv_ca_state,nv_ca_num,nv_scell_band,nv_scell_rsrp,"+
            "nr5g_rsrp,nr5g_rsrq,nr5g_sinr,nr5g_band,network_type"),
        new Group("wifi", "WI-FI / КЛИЕНТЫ",
            "wifi_onoff,wifi_enable,wifi_status,wifi_band,wifi_channel,"+
            "wifi_channel_2g,wifi_channel_5g,wifi_2g_channel,wifi_5g_channel,"+
            "wifi_access_sta_num,wifi_2g_access_sta_num,wifi_5g_access_sta_num,"+
            "wifi_max_sta_num,wifi_5g_max_sta_num,wlan_mode,wifi_mode"),
        new Group("system", "СИСТЕМА / ПИТАНИЕ",
            "battery_charging,battery_vol_percent,battery_pers,Battery_voltage,battery_value,"+
            "device_uptime,uptime,system_uptime,wa_inner_version,cr_version,"+
            "hardware_version,sw_version,current_temperature,modem_temperature")
    };

    public static Result collect(RouterClient client, Input input, Progress progress) {
        if(input==null) input=new Input();
        final long start=System.nanoTime();
        final JSONObject all=new JSONObject();
        final Map<String,JSONObject> answers=new LinkedHashMap<>();
        final Map<String,String> errors=new LinkedHashMap<>();
        int found=0,requested=0;
        boolean authorized=false;
        String authError="";
        try {
            if(progress!=null) progress.stage("Авторизация WebUI (отдельная сессия)");
            client.ensureAuth();
            authorized=true;
        } catch(Exception ex) { authError=safe(ex.getMessage()); }

        for(Group group:GROUPS) {
            if(Thread.currentThread().isInterrupted()) {
                errors.put(group.id,"Диагностика прервана");
                break;
            }
            if(!authorized) break;
            if(progress!=null) progress.stage("Чтение: "+group.title);
            JSONObject values=new JSONObject();
            String[] keys=group.fields.split(",");
            requested+=keys.length;
            try {
                JSONObject response=client.get(group.fields);
                for(String field:keys) {
                    String name=field.trim();
                    // Do not include unexpected firmware fields or credentials in reports.
                    String value=response.optString(name,"").trim();
                    if(value.isEmpty()||"null".equalsIgnoreCase(value)||
                       "undefined".equalsIgnoreCase(value)||"?".equals(value))continue;
                    if(value.length()>160)value=value.substring(0,160)+"…";
                    values.put(name,value);found++;
                }
                answers.put(group.id,values);
                all.put(group.id,values);
            } catch(Exception ex) {
                String reason=safe(ex.getMessage());
                errors.put(group.id,reason);
                answers.put(group.id,values);
                try {all.put(group.id,values);}catch(Exception ignored){}
            }
        }

        long elapsed=(System.nanoTime()-start)/1_000_000L;
        JSONObject live=answers.get("radio");
        boolean hasLive=live!=null && (!raw(live,"network_type").isEmpty() ||
                !raw(live,"ppp_status").isEmpty() || !raw(live,"nv_rsrp").isEmpty() ||
                !raw(live,"network_provider").isEmpty());
        long now=System.currentTimeMillis();
        boolean cacheFresh=input.cachedRadio!=null&&input.cachedRadioAt>0&&
                input.cachedRadioAt<=now&&now-input.cachedRadioAt<=30000;
        JSONObject radio=hasLive?live:(cacheFresh?input.cachedRadio:new JSONObject());
        JSONObject sim=object(answers,"sim");
        JSONObject wan=object(answers,"wan");
        JSONObject advanced=object(answers,"advanced");
        JSONObject wifi=object(answers,"wifi");
        JSONObject system=object(answers,"system");

        String ppp=first(radio,wan,"ppp_status");
        String tech=first(radio,advanced,"network_type","sub_network_type");
        boolean noService="no service".equalsIgnoreCase(tech)||
                "no service".equalsIgnoreCase(raw(radio,"sub_network_type"));
        boolean registered=knownRegistered(sim);
        String simState=first(sim,radio,"sim_status","simcard_status","sim_card_status",
                "pin_status","sim_pin_status");
        StringBuilder out=new StringBuilder(6500);
        out.append("AX8 PRO CONTROL · ГЛУБОКАЯ ДИАГНОСТИКА\n");
        out.append(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault()).format(new Date(now))).append("\n");
        out.append("Только чтение. Никаких изменений настроек, поиска операторов или перезапусков.\n\n");
        out.append("━━ СВОДКА ━━\n");
        out.append("Адрес WebUI: ").append(client.host()).append("\n");
        out.append("WebUI: ").append(authorized?"авторизация подтверждена":"не удалось подтвердить авторизацию").append("\n");
        if(!authError.isEmpty())out.append("Причина WebUI: ").append(authError).append("\n");
        out.append("Радиоданные: ").append(hasLive?"текущий отдельный запрос":
                cacheFresh?"только последний снимок приложения (НЕ новый опрос)":"нет достоверных данных").append("\n");
        out.append("Мобильная сеть: ").append(show(tech)).append("\n");
        out.append("Оператор: ").append(show(first(radio,wan,"network_provider"))).append("\n");
        out.append("PPP / WAN: ").append(show(ppp)).append("\n");
        out.append("SIM: ").append(show(simState)).append("\n");
        out.append("Регистрация: ").append(show(first(sim,radio,
                "network_register_status","network_reg_status","reg_status","modem_main_state"))).append("\n");
        out.append("Режим: ").append(show(first(sim,advanced,"net_select","net_select_mode",
                "m_netselect_save"))).append("\n");
        out.append("Band Lock: ").append(show(raw(advanced,"LteBandLockSet"))).append("\n");
        out.append("Данные WebUI: ").append(found).append("/").append(requested)
                .append(" непустых ответов; ").append(errors.size()).append(" ошибок групп\n");
        out.append("Время глубокого опроса: ").append(elapsed).append(" мс\n\n");

        out.append("━━ СИГНАЛ И РАДИОТРАКТ ━━\n");
        metric(out,"RSRP",radio,noService,"dBm",-140,-40,"nv_rsrp");
        metric(out,"RSRQ",radio,noService,"dB",-30,0,"nv_rsrq");
        metric(out,"SINR",radio,noService,"dB",-25,45,"nv_sinr");
        metric(out,"RSSI",radio,noService,"dBm",-120,-20,"nv_rssi","lte_rssi","rssi");
        out.append("Band: ").append(show(raw(radio,"nv_band"))).append("\n");
        out.append("Ширина канала: ").append(show(first(radio,advanced,"nv_bandwidth",
                "lte_bandwidth","bandwidth","current_bandwidth"))).append("\n");
        out.append("EARFCN: ").append(show(raw(radio,"nv_arfcn"))).append("\n");
        out.append("PCI: ").append(show(raw(radio,"nv_pci"))).append("\n");
        out.append("eNodeB / Cell ID: ").append(show(raw(radio,"nv_enodbid")))
                .append(" / ").append(show(raw(radio,"nv_cellid"))).append("\n");
        out.append("RI / Rank: ").append(show(first(radio,advanced,"nv_rank",
                "nv_rank_index","lte_ri","ri"))).append("\n");
        out.append("CQI-0 / CQI-1: ").append(noService?"— / — (нет обслуживания)":
                show(first(radio,advanced,"nv_cqi0","cqi0","lte_cqi0"))+" / "+
                show(first(radio,advanced,"nv_cqi1","cqi1","lte_cqi1"))).append("\n");
        out.append("MCS UL / DL: ").append(noService?"— / — (нет обслуживания)":
                show(first(radio,advanced,"nv_ul_mcs","ul_mcs","lte_ul_mcs"))+" / "+
                show(first(radio,advanced,"nv_dl_mcs","nv_dl_mcs0","dl_mcs"))).append("\n");
        out.append("TX power: ").append(noService?"— (ноль без сети не измерение)":
                show(first(radio,advanced,"nv_txpower","nv_tx_power","txpower"))).append("\n\n");

        out.append("━━ WI-FI, ПИТАНИЕ И ЛОКАЛЬНЫЕ ДАННЫЕ ━━\n");
        out.append("Клиентов по отдельному WebUI-запросу: ")
                .append(show(first(wifi,radio,"wifi_access_sta_num"))).append("\n");
        boolean clientsFresh=input.devicesAt>0&&input.devicesAt<=now&&now-input.devicesAt<30000;
        out.append("Список клиентов приложения: ").append(clientsFresh&&input.deviceCount>=0?
                String.valueOf(input.deviceCount):"нет свежего списка").append("\n");
        out.append("Wi-Fi канал: ").append(show(first(wifi,radio,
                "wifi_channel","wifi_channel_2g","wifi_2g_channel"))).append("\n");
        out.append("Wi-Fi статус: ").append(show(first(wifi,radio,"wifi_onoff","wifi_enable",
                "wifi_status"))).append("\n");
        String batteryPercent=first(system,radio,"battery_vol_percent");
        out.append("Батарея (процент от модема): ")
                .append(batteryPercent.isEmpty()?"—":batteryPercent+"%").append("\n");
        String coarse=first(system,radio,"battery_pers");
        out.append("Индикатор батареи (ступень): ").append(show(coarse));
        if("4".equals(coarse))out.append(" (4/4, НЕ 4%)");
        out.append("\n");
        out.append("Зарядка (по полю модема): ").append(charging(first(system,radio,"battery_charging"))).append("\n");
        out.append("Сохранённые лимиты (без подтверждения трафиком): ").append(input.savedLimitCount)
                .append(" сохранённых записей; ").append(show(input.qosState)).append("\n");
        out.append("Монитор трафика: ").append(show(input.trafficState)).append("\n");
        out.append("Интервал опроса приложения: ").append(input.refreshMs).append(" мс\n");
        out.append("Проверка внешнего интернета: ").append(
                input.internetCheckedAgeMs<0||input.internetCheckedAgeMs>30000?
                "нет актуальной отдельной проверки":
                InternetStatusPolicy.label(input.internetState)+" ("+
                input.internetCheckedAgeMs/1000+" с назад)").append("\n");
        if(!input.connectionError.isEmpty())out.append("Ошибка фонового WebUI: ")
                .append(safe(input.connectionError)).append("\n");
        out.append("\n━━ СЕТЕВАЯ ЦЕПОЧКА ━━\n");
        out.append("WAN IPv4: ").append(show(first(wan,radio,"wan_ipaddr","wan_ip"))).append("\n");
        out.append("WAN IPv6: ").append(show(first(wan,radio,"ipv6_wan_ipaddr","wan_ipv6_addr"))).append("\n");
        out.append("Шлюз WAN: ").append(show(first(wan,radio,"wan_gateway"))).append("\n");
        out.append("DNS WAN: ").append(show(first(wan,radio,"wan_dns","wan_dns1","dns1")))
                .append(" / ").append(show(first(wan,radio,"wan_dns2","dns2"))).append("\n");
        out.append("APN: ").append(show(first(wan,radio,"wan_apn"))).append("\n");
        out.append("Тип PDP: ").append(show(first(wan,radio,"pdp_type","ipv4_pdp_type"))).append("\n");
        out.append("\n━━ ДИАГНОСТИЧЕСКИЕ ВЫВОДЫ ━━\n");
        if(!authorized)out.append("• Сначала восстановите доступ/авторизацию WebUI. Остальные проверки не выполнены.\n");
        if(noService) {
            out.append("• Модем сообщает No Service. Отсутствие RSRP/RSRQ/PCI и нулевой SINR не являются замером качества сигнала.\n");
            out.append("• Проверьте распознавание SIM, PIN, поиск оператора, покрытие и фиксацию Band. APN проверяют после регистрации в сети.\n");
        } else if(!tech.isEmpty()&&!"ppp_connected".equals(ppp)) {
            out.append("• Радиотехнология определена, но PPP не подключён. После подтверждения регистрации проверьте APN/PDP и активацию передачи данных.\n");
        } else if("ppp_connected".equals(ppp)) {
            out.append("• PPP подключён, но это само по себе не доказывает доступ в интернет. Нужна отдельная проверка через Wi-Fi AX8.\n");
        } else {
            out.append("• Состояние мобильной сети не определено однозначно: не интерпретировать отсутствующие поля как неисправность.\n");
        }
        if(simState.toLowerCase(Locale.ROOT).contains("pin")||
           simState.toLowerCase(Locale.ROOT).contains("not inserted")||
           simState.toLowerCase(Locale.ROOT).contains("no sim"))
            out.append("• Ответ SIM требует внимания: ").append(safe(simState)).append(".\n");
        if(registered)out.append("• Прошивка сообщает признаки регистрации в сети; сопоставьте с PPP и сигналом.\n");
        if(input.savedLimitCount>0)
            out.append("• Наличие конфигурации tc не доказывает ограничения реальной скорости при аппаратном NAT/offload. Используйте отдельную проверку QoS.\n");
        if(!hasLive&&cacheFresh)out.append("• Радиосводка взята из свежего кэша, а не из успешного глубокого запроса.\n");

        out.append("\n━━ ПОЛЯ ПРОШИВКИ (ТОЛЬКО ПОЛУЧЕННЫЕ) ━━\n");
        for(Group group:GROUPS){
            out.append("\n[").append(group.title).append("]\n");
            JSONObject values=answers.get(group.id);
            int count=0;
            if(values!=null)for(String name:group.fields.split(",")){
                String key=name.trim(), value=raw(values,key);
                if(value.isEmpty())continue;
                out.append(key).append(" = ").append(value).append("\n");
                count++;
            }
            if(count==0)out.append("Нет заполненных полей в ответе\n");
            if(errors.containsKey(group.id))out.append("Ошибка чтения: ")
                .append(errors.get(group.id)).append("\n");
        }
        out.append("\nПримечание: пустое поле означает «не получено», а не ноль, отсутствие функции или аппаратный дефект. " +
                "Этот тест намеренно не читает пароли, IMSI, ICCID, IMEI и не изменяет конфигурацию. " +
                "Отчёт содержит сетевые адреса и технические данные. Перед отправкой проверьте их.\n");

        try{
            all.put("generated_at",now);
            all.put("authorized",authorized);
            all.put("radio_is_live",hasLive);
            all.put("requested_fields",requested);
            all.put("nonempty_fields",found);
            all.put("elapsed_ms",elapsed);
            all.put("errors",new JSONObject(errors));
        }catch(Exception ignored){}
        if(progress!=null)progress.stage("Отчёт готов");
        return new Result(out.toString(),all);
    }

    private static JSONObject object(Map<String,JSONObject> values,String key){
        JSONObject result=values.get(key);
        return result==null?new JSONObject():result;
    }
    private static String raw(JSONObject obj,String key){
        if(obj==null)return "";
        String x=obj.optString(key,"").trim();
        return "null".equalsIgnoreCase(x)?"":x;
    }
    private static String first(JSONObject a,JSONObject b,String... keys){
        for(String key:keys){
            String v=raw(a,key);
            if(v.isEmpty())v=raw(b,key);
            if(!v.isEmpty())return v;
        }
        return "";
    }
    private static String show(String s){return s==null||s.trim().isEmpty()?"—":s.trim();}
    private static String safe(String s){
        if(s==null)return "без подробностей";
        String x=s.replace('\n',' ').replace('\r',' ').trim();
        return x.length()>200?x.substring(0,200)+"…":x;
    }
    private static boolean knownRegistered(JSONObject sim){
        String status=first(sim,sim,"network_register_status","network_reg_status","reg_status");
        return "1".equals(status)||"5".equals(status)||
               "registered".equalsIgnoreCase(status)||"roaming".equalsIgnoreCase(status);
    }
    private static String charging(String s){
        int state=BatteryLevelPolicy.chargingState(s);
        return state==1?"заряжается":state==0?"не заряжается":"не подтверждено";
    }
    private static void metric(StringBuilder out,String title,JSONObject radio,boolean noService,
                               String unit,double min,double max,String... fields){
        String raw="";
        for(String key:fields){raw=raw(radio,key);if(!raw.isEmpty())break;}
        out.append(title).append(": ");
        if(noService){out.append("— (нет обслуживания)\n");return;}
        if(raw.isEmpty()){out.append("— (нет данных)\n");return;}
        try{
            String cleaned=raw.toLowerCase(Locale.ROOT).replace("dbm","")
                    .replace("db","").replace(',','.').trim();
            double value=Double.parseDouble(cleaned);
            if(Double.isNaN(value)||Double.isInfinite(value)||value<min||value>max){
                out.append("недостоверно (сырой ответ: ").append(safe(raw)).append(")\n");
            }else out.append(raw).append(" ").append(unit).append("\n");
        }catch(NumberFormatException ex){
            out.append("не распознано (").append(safe(raw)).append(")\n");
        }
    }
}
