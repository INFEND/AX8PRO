"""Regression guard for rate-limited IPv6 backend recovery."""
import re
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/ru/xarkon/ax8/DeviceTrafficMonitor.java"

class Ipv6RecoveryTest(unittest.TestCase):
    def test_read_failure_schedules_backend_rediscovery(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("if(active6!=null)try{readCounters(active6,totals);}", 1)[1].split("long now=System.nanoTime()", 1)[0]
        self.assertIn("ipv6ReadFailed=true;", block)
        self.assertIn("active6=null;", block)
        self.assertIn("nextIpv6ProbeAt=System.nanoTime()+IPV6_PROBE_INTERVAL_NS;", block)
        self.assertNotIn("choose(false", block)

    def test_retry_is_bounded_and_does_not_rebuild_ipv4(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("IPV6_PROBE_INTERVAL_NS=30_000_000_000L", source)
        self.assertIn("if(started && active6==null && !a6.isEmpty() &&", source)
        self.assertIn("System.nanoTime()-nextIpv6ProbeAt>=0", source)
        self.assertIn("active6=choose(true,a6);", source)
        block = source.split("// The initial IPv6 probe can fail", 1)[1].split("Map<String,long[]> totals", 1)[0]
        self.assertNotIn("choose(false", block)
        self.assertNotIn("remove(active4)", block)
        self.assertIn("prior.clear();rates.clear();lastAt=0;", block)

if __name__ == "__main__":
    unittest.main()
