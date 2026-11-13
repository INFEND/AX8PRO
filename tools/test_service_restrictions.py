import pathlib
import os
import json
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

    def test_router_agent_transaction(self):
        # Execute the actual POSIX shell agent in an isolated mock firewall.
        # No root, network, or real iptables operations are used.
        mock="""#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
state=Path(os.environ["MOCK_STATE"])
d=json.loads(state.read_text()) if state.exists() else {"sets":{},"iptables":{},"ip6tables":{},"jumps":{"iptables":[],"ip6tables":[]}}
name=Path(sys.argv[0]).name
a=sys.argv[1:]
rc=0
if name=="nslookup":
    if os.environ.get("FAIL_DNS")=="1": sys.exit(1)
    print("Server: 192.168.150.1\\nAddress: 192.168.150.1\\n\\nName: "+a[-1])
    print("Address: "+("2001:db8::123" if "-type=AAAA" in a else "203.0.113.42"))
elif name=="ipset":
    if a[0]=="-exist":a=a[1:]
    op=a[0]
    if op=="list":
        if a[1]=="-n":print("\\n".join(d["sets"]))
        elif a[1] not in d["sets"]:rc=1
    elif op=="create":
        if a[1] in d["sets"]:rc=1
        else:d["sets"][a[1]]=[]
    elif op=="add":
        if a[1] not in d["sets"]:rc=1
        elif a[2] not in d["sets"][a[1]]:d["sets"][a[1]].append(a[2])
    elif op=="destroy":
        if a[1] in d["sets"]:del d["sets"][a[1]]
        else:rc=1
else:
    chains=d[name];jumps=d["jumps"][name];op=a[0];chain=a[1]
    if op=="-L":
        if chain=="FORWARD":print("\\n".join(jumps))
        elif chain not in chains:rc=1
    elif op=="-N":
        if chain in chains:rc=1
        else:chains[chain]=[]
    elif op=="-A":
        if chain not in chains:rc=1
        else:chains[chain].append(a[2:])
    elif op=="-F":
        if chain in chains:chains[chain]=[]
        else:rc=1
    elif op=="-X":
        if chain in chains:del chains[chain]
        else:rc=1
    elif op=="-I":jumps.insert(0,a[-1])
    elif op=="-D":
        if a[-1] in jumps:jumps.remove(a[-1])
        else:rc=1
state.write_text(json.dumps(d));sys.exit(rc)
"""
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp);bin=p/"bin";bin.mkdir()
            fake=bin/"fake";fake.write_text(mock,encoding="utf-8");fake.chmod(0o755)
            for name in ("ipset","iptables","ip6tables","nslookup"):(bin/name).symlink_to(fake)
            env=dict(os.environ,PATH=str(bin)+os.pathsep+os.environ["PATH"],MOCK_STATE=str(p/"state.json"))
            rev="12345678-1234-1234-1234-123456789abc"
            (p/"plan").write_text(rev+"\nALL play play.google.com\nAA:BB:CC:DD:EE:FF drive drive.google.com\n")
            def run(fail=False):
                return subprocess.run(["sh",str(AGENT),"--test-root",str(p),"--once"],env=dict(env,FAIL_DNS="1" if fail else "0"),capture_output=True,text=True)
            r=run();self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            d=json.loads((p/"state.json").read_text())
            self.assertEqual(d["jumps"]["iptables"],["AX8SVCA"])
            self.assertEqual(d["jumps"]["ip6tables"],["AX8SVCA"])
            self.assertIn("--mac-source",d["iptables"]["AX8SVCA"][1])
            self.assertEqual(d["sets"]["ax8svc_A_4_play"],["203.0.113.42"])
            self.assertEqual(d["sets"]["ax8svc_A_6_play"],["2001:db8::123"])
            self.assertIn("INSTALLED|"+rev+"|2|",(p/"status").read_text())
            r=run(True);self.assertNotEqual(r.returncode,0)
            d=json.loads((p/"state.json").read_text())
            self.assertEqual(d["jumps"]["iptables"],["AX8SVCA"])
            self.assertIn("ax8svc_A_4_play",d["sets"])
            r=run();self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            d=json.loads((p/"state.json").read_text())
            self.assertEqual(d["jumps"]["iptables"],["AX8SVCB"])
            self.assertNotIn("ax8svc_A_4_play",d["sets"])
            (p/"plan").write_text(rev+"\n")
            r=run(True);self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            d=json.loads((p/"state.json").read_text())
            self.assertEqual(d["jumps"]["iptables"],[])
            self.assertEqual(d["jumps"]["ip6tables"],[])
            self.assertEqual(d["sets"],{})
            self.assertIn("OFF|"+rev+"|0|",(p/"status").read_text())
