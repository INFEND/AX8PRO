package ru.xarkon.ax8;

import java.util.List;

/** Present missing client data as unknown, never as zero or a stale count. */
public final class BackgroundSummary {
    private BackgroundSummary(){}
    public static String clientCount(List<?> devices){
        return devices==null?"?":Integer.toString(devices.size());
    }
}
