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
    if os.environ.get("FAIL_DNS")=="1" or os.environ.get("NO_NSLOOKUP")=="1": sys.exit(1)
    print("Server: 192.168.150.1\\nAddress: 192.168.150.1\\n\\nName: "+a[-1])
    print("Address: "+("2001:db8::123" if "-type=AAAA" in a else "203.0.113.42"))
elif name in ("getent","ping","ping6","busybox"):
    if os.environ.get("FAIL_DNS")=="1" or name=="busybox":sys.exit(1)
    if name=="getent" and os.environ.get("GETENT_DNS")=="1":
        if a[0]=="ahostsv4":print("203.0.113.43 STREAM "+a[-1])
        elif a[0]=="ahostsv6":print("2001:db8::124 STREAM "+a[-1])
        else:sys.exit(1)
    elif name in ("ping","ping6") and os.environ.get("PING_DNS")=="1":
        print("PING "+a[-1]+" ("+("2001:db8::125" if name=="ping6" or "-6" in a else "203.0.113.44")+") 56 data bytes")
        sys.exit(1) # ICMP reply not needed for hostname resolution.
    else:sys.exit(1)
elif name=="ipset":
    if os.environ.get("NO_IPSET")=="1": sys.exit(127)
    if os.environ.get("FAIL_IPSET_CREATE")=="1" and a[0]=="create": sys.exit(1)
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
            for name in ("ipset","iptables","ip6tables","nslookup","getent","ping","ping6","busybox"):(bin/name).symlink_to(fake)
            env=dict(os.environ,PATH=str(bin)+os.pathsep+os.environ["PATH"],MOCK_STATE=str(p/"state.json"))
            rev="12345678-1234-1234-1234-123456789abc"
            (p/"plan").write_text(rev+"\nALL play play.google.com\nAA:BB:CC:DD:EE:FF drive drive.google.com\n")
            def run(fail=False,**extra):
                return subprocess.run(["sh",str(AGENT),"--test-root",str(p),"--once"],env=dict(env,FAIL_DNS="1" if fail else "0",**extra),capture_output=True,text=True)
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

            # No ipset: plain destination-IP rules, still MAC-specific.
            (p/"plan").write_text(rev+"\nALL play play.google.com\nAA:BB:CC:DD:EE:FF drive drive.google.com\n")
            r=run(NO_IPSET="1");self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            d=json.loads((p/"state.json").read_text())
            self.assertEqual(d["jumps"]["iptables"],["AX8SVCA"])
            self.assertEqual(d["sets"],{})
            self.assertIn("-d",d["iptables"]["AX8SVCA"][0])
            self.assertIn("--mac-source",d["iptables"]["AX8SVCA"][1])
            self.assertIn("backend=direct",(p/"status").read_text())
            r=run(True,NO_IPSET="1");self.assertNotEqual(r.returncode,0)
            self.assertEqual(json.loads((p/"state.json").read_text())["jumps"]["iptables"],["AX8SVCA"])
            r=run(FAIL_IPSET_CREATE="1");self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            self.assertEqual(json.loads((p/"state.json").read_text())["jumps"]["iptables"],["AX8SVCB"])
            (p/"plan").write_text(rev+"\n")
            r=run(NO_IPSET="1");self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            self.assertEqual(json.loads((p/"state.json").read_text())["jumps"]["iptables"],[])

            (p/"plan").write_text(rev+"\nALL play play.google.com\n")
            r=run(NO_NSLOOKUP="1",GETENT_DNS="1");self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            d=json.loads((p/"state.json").read_text())
            self.assertEqual(d["sets"]["ax8svc_A_4_play"],["203.0.113.43"])
            self.assertEqual(d["sets"]["ax8svc_A_6_play"],["2001:db8::124"])
            r=run(NO_NSLOOKUP="1",PING_DNS="1");self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            d=json.loads((p/"state.json").read_text())
            self.assertEqual(d["sets"]["ax8svc_B_4_play"],["203.0.113.44"])
            self.assertEqual(d["sets"]["ax8svc_B_6_play"],["2001:db8::125"])
            (p/"seeds").write_text(rev+"\nplay.google.com 203.0.113.45\nplay.google.com 192.168.150.1\n")
            r=run(True);self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            d=json.loads((p/"state.json").read_text())
            self.assertEqual(d["sets"]["ax8svc_A_4_play"],["203.0.113.45"])
            self.assertIn("DNS=phone_snapshot",(p/"status").read_text())
            (p/"plan").write_text("12345678-1234-1234-1234-123456789abd\nALL play play.google.com\n")
            r=run(True);self.assertNotEqual(r.returncode,0)
            self.assertEqual(json.loads((p/"state.json").read_text())["jumps"]["iptables"],["AX8SVCA"])

    def test_legacy_firmware_dependencies(self):
        java=(SRC/"ServiceRestrictions.java").read_text(encoding="utf-8")
        self.assertIn("ServiceScriptTransfer.commands(path,content)",java)
        self.assertIn("ServiceScriptTransfer.chunkMismatch(content,readback.output)",java)
        self.assertIn("ServiceScriptTransfer.heredocCommands(path,content)",java)
        self.assertIn("Запись ",java)
        self.assertIn("Активация нового плана сервисов",java)
        self.assertNotIn("Не удалось записать или запустить модуль сервисов на AX8",java)
        self.assertIn("shellForAgent(sh)",java)
        self.assertNotIn("command -v base64",java)
        self.assertNotIn("command -v nohup",java)
        self.assertNotIn("command -v ipset",java)
        self.assertIn("agent.version",java)
        self.assertIn("iptables: таблица filter/FORWARD недоступна",java)

    def test_literal_transfer_roundtrip(self):
        # Exercise the exact Java encoder through /bin/sh, not a reimplementation.
        driver="""package ru.xarkon.ax8;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public class TransferTest {
    public static void main(String[] args)throws Exception{
        byte[] data=Files.readAllBytes(Path.of(args[1]));
        if(args[0].equals("write")){
            for(String command:ServiceScriptTransfer.commands("/tmp/ax8pro-services/agent.new",data))
                System.out.println(command);
        }else if(args[0].equals("heredoc")){
            for(String command:ServiceScriptTransfer.heredocCommands("/tmp/ax8pro-services/agent.new",data))
                System.out.println(command);
        }else if(args[0].equals("chunks")){
            System.out.println(ServiceScriptTransfer.chunkReadCommand("/tmp/ax8pro-services/agent.new"));
        }else{
            String remote=Files.readString(Path.of(args[2]),StandardCharsets.US_ASCII);
            System.out.println(args[0].equals("verifyChunks")?ServiceScriptTransfer.chunkMismatch(data,remote):ServiceScriptTransfer.mismatch(data,remote));
        }
    }
}"""
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp)
            driver_file=p/"TransferTest.java";driver_file.write_text(driver)
            subprocess.run(["javac","-encoding","UTF-8","-d",tmp,str(SRC/"ServiceScriptTransfer.java"),str(driver_file)],check=True)
            cmd=["java","-cp",tmp,"ru.xarkon.ax8.TransferTest"]
            original=AGENT.read_bytes()
            self.assertTrue(original.isascii())
            commands=subprocess.check_output(cmd+["write",str(AGENT)],text=True)
            target=p/"agent.new"
            self.assertLess(max(map(len,commands.splitlines())),400)
            subprocess.run(["sh","-e","-c",commands.replace("/tmp/ax8pro-services/agent.new",str(target))],check=True)
            self.assertEqual(target.read_bytes(),original)
            subprocess.run(["sh","-n",str(target)],check=True)
            proof=p/"proof"
            proof.write_bytes(subprocess.check_output(["awk",'{printf "AX8FILE:%d:%s\\n", NR, $0}',str(target)]))
            self.assertEqual(subprocess.check_output(cmd+["verify",str(AGENT),str(proof)],text=True).strip(),"0")
            proof.write_bytes(proof.read_bytes().replace(b"#!/bin/sh",b"#!/bin/xx",1))
            self.assertEqual(subprocess.check_output(cmd+["verify",str(AGENT),str(proof)],text=True).strip(),"1")

            # Alternate transport must preserve every literal byte, including
            # quotes, backslashes and empty lines, even when printf is absent.
            data=p/"literal.sh"
            data.write_bytes(original+b"\n# AX8 literal: $HOME ' \" \\ \nAX8_SERVICE_FILE_END\n")
            commands=subprocess.check_output(cmd+["heredoc",str(data)],text=True)
            subprocess.run(["sh","-e","-c",commands.replace("/tmp/ax8pro-services/agent.new",str(target))],check=True)
            self.assertEqual(target.read_bytes(),data.read_bytes())
            chunk_cmd=subprocess.check_output(cmd+["chunks",str(data)],text=True)
            proof.write_bytes(subprocess.check_output(["sh","-c",chunk_cmd.replace("/tmp/ax8pro-services/agent.new",str(target))]))
            self.assertEqual(subprocess.check_output(cmd+["verifyChunks",str(data),str(proof)],text=True).strip(),"0")
            self.assertLess(max(map(len,proof.read_text().splitlines())),60)
            proof.write_bytes(proof.read_bytes().replace(b"#!/bin/sh",b"#!/bin/xx",1))
            self.assertEqual(subprocess.check_output(cmd+["verifyChunks",str(data),str(proof)],text=True).strip(),"1")
