"""Exercise saved-device grouping without Android or a router."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]

class SavedClientGroupsTest(unittest.TestCase):
    def test_grouping_and_latest_manual_limit(self):
        src=ROOT/"app/src/main/java/ru/xarkon/ax8/SavedClientGroups.java"
        java="""package ru.xarkon.ax8;
import java.util.*;
public class GroupTest {
  static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  public static void main(String[] args){
    List<String> macs=Arrays.asList("A","B","C","D","E");
    Map<String,String> names=Map.of("A","S24 ULTRA","B","s24 ultra","C","S24 ULTRA","D","Без имени","E","Без имени");
    Map<String,Integer> limits=new HashMap<>();limits.put("C",807);
    Map<String,Long> revisions=new HashMap<>();
    List<SavedClientGroups.Group> g=SavedClientGroups.build(macs,names::get,limits,k->revisions.getOrDefault(k,0L));
    check(g.size()==3,"unknown names stay separate; same display name grouped");
    SavedClientGroups.Group x=g.stream().filter(a->a.macs.size()==3).findFirst().orElseThrow();
    check(x.primary.equals("C")&&x.limit==807&&!x.conflicting,"existing finite limit must not be lost");
    limits.put("B",250);
    x=SavedClientGroups.build(macs,names::get,limits,k->revisions.getOrDefault(k,0L)).stream().filter(a->a.macs.size()==3).findFirst().orElseThrow();
    check(x.conflicting,"different legacy caps without history must not pretend newest");
    revisions.put("B",2L);revisions.put("C",1L);
    x=SavedClientGroups.build(macs,names::get,limits,k->revisions.getOrDefault(k,0L)).stream().filter(a->a.macs.size()==3).findFirst().orElseThrow();
    check(x.primary.equals("B")&&x.limit==250&&!x.conflicting,"latest cap selected");
    check(limits.get("C")==807&&limits.get("B")==250&&!limits.containsKey("A"),"no rule copied or erased");
    limits.remove("B");revisions.put("B",3L);
    x=SavedClientGroups.build(macs,names::get,limits,k->revisions.getOrDefault(k,0L)).stream().filter(a->a.macs.size()==3).findFirst().orElseThrow();
    check(x.primary.equals("C")&&x.limit==807,"remaining actual limit preserved");
    limits.clear();
    x=SavedClientGroups.build(macs,names::get,limits,k->revisions.getOrDefault(k,0L)).stream().filter(a->a.macs.size()==3).findFirst().orElseThrow();
    check(x.limit==null&&!x.conflicting,"all unlimited");
  }
}"""
        with tempfile.TemporaryDirectory() as temp:
            p=pathlib.Path(temp); (p/"GroupTest.java").write_text(java,encoding="utf-8")
            subprocess.run(["javac","-encoding","UTF-8","-d",temp,str(src),str(p/"GroupTest.java")],check=True)
            subprocess.run(["java","-cp",temp,"ru.xarkon.ax8.GroupTest"],check=True)

if __name__=="__main__":
    unittest.main()
