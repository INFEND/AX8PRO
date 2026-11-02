package ru.xarkon.ax8;

import java.util.ArrayList;
import java.util.List;

public final class OtaHistoryTest {
    private static int assertions;
    private static void same(String expected,String actual,String label){
        if(!expected.equals(actual))throw new AssertionError(label+": "+actual);
        assertions++;
    }
    private static void yes(boolean condition,String label){
        if(!condition)throw new AssertionError(label);
        assertions++;
    }
    private static OtaHistory.Entry entry(long code,String name,String notes){
        return new OtaHistory.Entry(code,name,notes);
    }
    private static List<OtaHistory.Entry> baseline(){
        List<OtaHistory.Entry> entries=new ArrayList<>();
        entries.add(entry(39,"1.5.21","[+] Добавлена **иконка батареи**."));
        entries.add(entry(40,"1.5.22","[=] Улучшено **измерение заряда**."));
        entries.add(entry(41,"1.5.23","[-] Убрано **лишнее уведомление**."));
        return entries;
    }
    public static void main(String[] args){
        List<OtaHistory.Entry> entries=baseline();
        String fallback="[=] Новая версия.";
        String full=OtaHistory.merge(entries,38,41,fallback);
        yes(full.contains("иконка батареи")&&full.contains("измерение заряда")
            &&full.contains("лишнее уведомление"),"all skipped releases retained");
        same("[-] Убрано **лишнее уведомление**.",
             OtaHistory.merge(entries,40,41,fallback),"only new version");
        same(fallback,OtaHistory.merge(entries,42,41,fallback),"already current");
        same(fallback,OtaHistory.merge(entries,30,41,fallback),"no incomplete old history");
        List<OtaHistory.Entry> invalid=baseline();
        invalid.remove(1);
        same(fallback,OtaHistory.merge(invalid,38,41,fallback),"gap rejected");
        invalid=baseline();invalid.add(invalid.get(1));
        same(fallback,OtaHistory.merge(invalid,38,41,fallback),"duplicate rejected");
        invalid=baseline();invalid.set(1,entry(40,"1.5.21","[=] Повтор."));
        same(fallback,OtaHistory.merge(invalid,38,41,fallback),"repeated version name rejected");
        invalid=baseline();invalid.set(0,entry(39,"1.5.21","unknown"));
        same(fallback,OtaHistory.merge(invalid,38,41,fallback),"invalid note rejected");
        same("[+] Исправлена **установка**.",ReleaseNotes.visible(
                "[+] Исправлена **установка**.\nJVM TEST\n[-] github tools"),
                "safe customer note formatting");
        yes(!full.contains("JVM"),"no developer log");
        System.out.println("OTA cumulative changelog: "+assertions+" checks passed.");
    }
}
