package ru.xarkon.ax8;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Identifies all newly received unread SMS without alerting on the initial baseline. */
public final class SmsAlertPlanner {
    private SmsAlertPlanner(){}

    public static List<String> newUnreadIds(Collection<String> unreadIds,
                                            String previousSeenIds, boolean initialized) {
        List<String> result=new ArrayList<>();
        if(!initialized || unreadIds==null)return result;
        Set<String> seen=new HashSet<>();
        if(previousSeenIds!=null && !previousSeenIds.isEmpty())
            Collections.addAll(seen,previousSeenIds.split(","));
        Set<String> emitted=new HashSet<>();
        for(String id:unreadIds){
            if(id!=null && !id.isEmpty() && !seen.contains(id) && emitted.add(id))
                result.add(id);
        }
        return result;
    }
}
