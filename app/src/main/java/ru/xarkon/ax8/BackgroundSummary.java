package ru.xarkon.ax8;

import java.util.List;

/** Present missing client data as unknown, never as zero or a stale count. */
public final class BackgroundSummary {
    private BackgroundSummary(){}
    public static String clientCount(List<?> devices){
        return devices==null?"?":Integer.toString(devices.size());
    }
    /** Reject missing, expired and future-dated cached measurements. */
    public static boolean isFresh(long sampleAt,long now,long maxAgeMs){
        return sampleAt>0 && maxAgeMs>0 && now>=sampleAt && now-sampleAt<=maxAgeMs;
    }
}
