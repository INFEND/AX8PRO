"""JVM checks for MAC group ACL semantics, independent of a physical router."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class MacBlockPolicyTest(unittest.TestCase):
    def test_group_actions_are_atomic_and_preserve_unrelated_entries(self):
        java = r'''package ru.xarkon.ax8;
import java.util.*;
public class MacPolicyTest {
    private static final String A="10:20:30:40:50:61", B="10:20:30:40:50:62",
        OTHER="10:20:30:40:50:77", OWNER="10:20:30:40:50:99";
    static void check(boolean yes, String why) { if(!yes)throw new AssertionError(why); }
    static void fails(Runnable work, String why) {
        try { work.run(); }catch(IllegalArgumentException expected){return;}
        throw new AssertionError(why);
    }
    public static void main(String[] args) {
        List<String> existing=new ArrayList<>(Arrays.asList(OTHER,B));
        List<String> group=Arrays.asList(A.toLowerCase(Locale.US).replace(':','-'),B,A);
        ArrayList<String> result=MacBlockPolicy.target(existing,group,true,OWNER);
        check(result.equals(Arrays.asList(OTHER,B,A)),"merge all known group MACs, no duplicates");
        check(existing.equals(Arrays.asList(OTHER,B)),"never modify caller's existing state");
        check(MacBlockPolicy.target(result,group,true,OWNER).equals(result),"blocking twice is idempotent");
        check(MacBlockPolicy.target(result,group,false,OWNER).equals(Arrays.asList(OTHER)),"unblock only selected group");
        fails(()->MacBlockPolicy.target(existing,Arrays.asList(A,OWNER),true,OWNER),"protect owner");
        fails(()->MacBlockPolicy.target(existing,Arrays.asList(A,B),true,B),"protect WebUI mac");
        fails(()->MacBlockPolicy.target(existing,Arrays.asList("bogus",B),true,OWNER),"invalid selection must fail fully");
        fails(()->MacBlockPolicy.target(existing,Collections.emptyList(),true,OWNER),"empty group rejected");
        check(MacBlockPolicy.target(existing,Arrays.asList(B),false,OWNER).equals(Arrays.asList(OTHER)),
            "unblock is permitted even if protected MAC appears in group");
    }
}
'''
        with tempfile.TemporaryDirectory() as temp:
            p = pathlib.Path(temp)
            source = ROOT / "app/src/main/java/ru/xarkon/ax8/MacBlockPolicy.java"
            fixture = p / "MacPolicyTest.java"
            fixture.write_text(java, encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", temp, str(source), str(fixture)], check=True)
            subprocess.run(["java", "-cp", temp, "ru.xarkon.ax8.MacPolicyTest"], check=True)


if __name__ == "__main__":
    unittest.main()
