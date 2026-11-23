package ru.xarkon.ax8;

import java.util.*;
import java.util.regex.Pattern;

/** Pure, testable ACL planner. Names are never network identifiers: only explicit MACs are sent. */
final class MacBlockPolicy {
    private static final Pattern MAC=Pattern.compile("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}");
    private MacBlockPolicy(){}

    private static String normalize(String input){
        if(input==null)return "";
        String normalized=input.trim().replace('-',':').toUpperCase(Locale.ROOT);
        return MAC.matcher(normalized).matches()?normalized:"";
    }

    static ArrayList<String> target(Collection<String> current,Collection<String> selection,
                                    boolean block,String... protectedMacs){
        if(selection==null||selection.isEmpty())throw new IllegalArgumentException("Не выбраны MAC-адреса");
        LinkedHashSet<String> group=new LinkedHashSet<>();
        for(String raw:selection){
            String mac=normalize(raw);
            if(mac.isEmpty())throw new IllegalArgumentException("Некорректный MAC: "+raw);
            group.add(mac);
        }
        if(block&&protectedMacs!=null)for(String raw:protectedMacs){
            String protectedMac=normalize(raw);
            if(!protectedMac.isEmpty()&&group.contains(protectedMac))
                throw new IllegalArgumentException("Нельзя заблокировать управляющее устройство: "+protectedMac);
        }
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(current!=null)for(String raw:current){
            String mac=normalize(raw);
            if(mac.isEmpty())throw new IllegalArgumentException("Некорректный MAC в списке роутера: "+raw);
            result.add(mac);
        }
        if(block)result.addAll(group);else result.removeAll(group);
        return new ArrayList<>(result);
    }
}
