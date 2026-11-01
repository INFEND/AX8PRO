package ru.xarkon.ax8;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Identifies newly received unread SMS and remembers recently observed message IDs. */
public final class SmsAlertPlanner {
    // The router returns up to 500 messages per scan. Retain an additional 500
    // IDs so temporary empty pages and pagination changes cannot trigger duplicates.
    private static final int MAX_HISTORY=1000;
    private SmsAlertPlanner(){}

    private static boolean validId(String id){
        if(id==null || id.isEmpty() || id.length()>32)return false;
        for(int i=0;i<id.length();i++){
            char c=id.charAt(i);
            if(c<'0' || c>'9')return false;
        }
        return true;
    }

    private static LinkedHashSet<String> parseSeen(String raw){
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        if(raw!=null && !raw.isEmpty())
            for(String id:raw.split(","))if(validId(id))seen.add(id);
        return seen;
    }

    public static List<String> newUnreadIds(Collection<String> unreadIds,
                                            String previousSeenIds, boolean initialized) {
        List<String> result=new ArrayList<>();
        if(!initialized || unreadIds==null)return result;
        Set<String> seen=parseSeen(previousSeenIds);
        Set<String> emitted=new LinkedHashSet<>();
        for(String id:unreadIds){
            if(validId(id) && !seen.contains(id) && emitted.add(id))
                result.add(id);
        }
        return result;
    }

    /** Merge successful scans without forgetting IDs temporarily absent from a page.
     * The bounded history is most-recently-observed first for eviction purposes.
     * Call only after a successfully parsed router SMS response.
     */
    public static String mergeSeenIds(String previousSeenIds, Collection<String> currentIds){
        LinkedHashSet<String> seen=parseSeen(previousSeenIds);
        if(currentIds!=null)for(String id:currentIds)if(validId(id)){
            seen.remove(id);
            seen.add(id);
        }
        while(seen.size()>MAX_HISTORY)seen.remove(seen.iterator().next());
        return String.join(",",seen);
    }
}
