import pathlib
import subprocess
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
SRC=ROOT/"app/src/main/java/ru/xarkon/ax8"
AGENT=ROOT/"app/src/main/assets/ax8_service_agent.sh"

class ServiceRestrictionsTest(unittest.TestCase):
    def test_shell_syntax(self):
        subprocess.run(["sh","-n",str(AGENT)],check=True)
    def test_catalog_plan(self):
        java="""package ru.xarkon.ax8;
import java.util.*;
public class ServiceTest {
    public static void main(String[] args){
        if(ServiceCatalog.ITEMS.size()!=26)throw new AssertionError("catalog");
        Map<String,Set<String>> rules=new TreeMap<>();
        rules.put("ALL",Set.of("play","ios"));
        rules.put("AA:BB:CC:DD:EE:FF",Set.of("drive"));
        String p=ServiceCatalog.plan("12345678-1234-1234-1234-123456789abc",rules);
        if(!p.contains("ALL play play.google.com")||!p.contains("AA:BB:CC:DD:EE:FF drive drive.google.com"))throw new AssertionError(p);
        rules.put("AA; echo fail",Set.of("play"));
        try{ServiceCatalog.plan("12345678-1234-1234-1234-123456789abc",rules);throw new AssertionError("unsafe");}catch(IllegalArgumentException ok){}
    }
}"""
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp)/"ServiceTest.java";p.write_text(java,encoding="utf-8")
            subprocess.run(["javac","-encoding","UTF-8","-d",tmp,str(SRC/"ServiceCatalog.java"),str(p)],check=True)
            subprocess.run(["java","-cp",tmp,"ru.xarkon.ax8.ServiceTest"],check=True)
    def test_isolated_chains_and_no_automatic_enable(self):
        s=AGENT.read_text()
        self.assertIn('-I FORWARD 1 -i br0 -j "AX8SVC$new"',s)
        self.assertIn('--mac-source "$scope"',s)
        self.assertIn('same_revision',s)
        self.assertNotIn('iptables -F FORWARD',s)
        self.assertNotIn('iptables -F INPUT',s)
        self.assertNotIn('dnsmasq.conf',s)
        main=(SRC/"MainActivity.java").read_text()
        self.assertIn('Сохранить локально',main)
        self.assertIn('confirmServiceDeploy(controller)',main)
