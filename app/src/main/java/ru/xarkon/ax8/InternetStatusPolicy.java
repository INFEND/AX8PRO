package ru.xarkon.ax8;

/** Internet access must be checked outside the AX8 WebUI, on the same router-bound network. */
public final class InternetStatusPolicy {
    public static final int UNKNOWN=0,ONLINE=1,OFFLINE=2,UNAVAILABLE=3;
    public static final long PROBE_INTERVAL_MS=15000L;
    public static final long FRESH_MS=30000L;
    private InternetStatusPolicy(){}

    /** A new LTE/PPP session invalidates an earlier external internet result. */
    public static boolean pppChanged(String previous,String current){
        return previous!=null && current!=null && !previous.equals(current);
    }
    public static boolean isFresh(long checkedAt,long now){
        return checkedAt>0&&now>=checkedAt&&now-checkedAt<=FRESH_MS;
    }
    public static boolean shouldProbe(long lastStart,long now,boolean inFlight){
        return !inFlight&&(lastStart<=0||now<lastStart||now-lastStart>=PROBE_INTERVAL_MS);
    }
    public static int displayed(boolean routerFresh,boolean pppConnected,int result,long checkedAt,long now){
        if(!routerFresh)return UNKNOWN;
        if(!pppConnected)return OFFLINE;
        if (!isFresh(checkedAt,now)) return UNKNOWN;
        // Never propagate an unexpected probe value into the status UI.
        return result==ONLINE||result==OFFLINE||result==UNAVAILABLE ? result : UNKNOWN;
    }
    public static String label(int result){
        if(result==ONLINE)return "● LTE ONLINE";
        if(result==OFFLINE)return "○ LTE OFFLINE";
        if(result==UNAVAILABLE)return "◌ LTE НЕ ПРОВЕРЕНО";
        return "◌ LTE ПРОВЕРКА";
    }
    public static String detail(int result){
        if(result==ONLINE)return "Доступ к интернету через сеть роутера подтверждён";
        if(result==OFFLINE)return "Интернет через роутер не подтверждён; LTE-соединение не гарантирует доступ";
        if(result==UNAVAILABLE)return "Не удалось проверить интернет через сеть роутера; мобильный интернет телефона не учитывается";
        return "Проверяем доступ к интернету через роутер";
    }
}
