package ru.xarkon.ax8;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Presentation-only grouping. A display name is never a network identity. */
final class SavedClientGroups {
    static final class Group {
        final String key,name,primary;
        final List<String> macs;
        final Integer limit;
        final boolean conflicting;
        Group(String key,String name,List<String> macs,String primary,Integer limit,boolean conflicting){
            this.key=key;this.name=name;this.macs=Collections.unmodifiableList(new ArrayList<>(macs));
            this.primary=primary;this.limit=limit;this.conflicting=conflicting;
        }
    }
    private SavedClientGroups(){}
    static String key(String name,String mac){
        String n=name==null?"":name.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        if(n.isEmpty()||n.equals("устройство вне сети")||n.equals("без имени")||n.equals("имя неизвестно")
                ||n.equals("unknown")||n.equals("device")||n.equals("?")||n.equals("—")
                ||n.matches("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"))return "mac:"+mac;
        return "name:"+n;
    }
    static List<Group> build(Collection<String> macs,Function<String,String> name,
                              Map<String,Integer> limits,ToLongFunction<String> revision){
        Map<String,List<String>> buckets=new TreeMap<>();
        for(String mac:new TreeSet<>(macs)){
            if(mac==null||mac.isEmpty())continue;
            buckets.computeIfAbsent(key(name.apply(mac),mac),k->new ArrayList<>()).add(mac);
        }
        List<Group> groups=new ArrayList<>();
        for(Map.Entry<String,List<String>> entry:buckets.entrySet()){
            List<String> members=entry.getValue();
            members.sort((a,b)->{
                Integer ca=limits.get(a),cb=limits.get(b);
                if((ca==null)!=(cb==null))return ca==null?1:-1;
                int byRevision=Long.compare(revision.applyAsLong(b),revision.applyAsLong(a));
                return byRevision!=0?byRevision:a.compareTo(b);
            });
            String primary=members.get(0);
            Integer cap=limits.get(primary);
            long rev=revision.applyAsLong(primary);
            boolean conflict=false;
            // For legacy records there is no date. Don't fabricate chronological order
            // if two different finite limits have equal/latest unknown revisions.
            if(cap!=null)for(String mac:members){
                Integer other=limits.get(mac);
                if(other!=null&&!other.equals(cap)&&revision.applyAsLong(mac)==rev){conflict=true;break;}
            }
            groups.add(new Group(entry.getKey(),name.apply(primary),members,primary,cap,conflict));
        }
        groups.sort(Comparator.comparing((Group g)->g.name,String.CASE_INSENSITIVE_ORDER).thenComparing(g->g.key));
        return groups;
    }
}
