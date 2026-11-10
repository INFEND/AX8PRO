"""Source regression: QoS must remember the post-apply enriched IPv6 snapshot."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
SRC=(ROOT/"app/src/main/java/ru/xarkon/ax8/QosController.java").read_text(encoding="utf-8")
class QosIpv6ResyncTest(unittest.TestCase):
    def test_remembers_after_apply_enriches_ipv6(self):
        body=SRC.split("synchronized void resyncAddresses(",1)[1].split("synchronized String audit(",1)[0]
        self.assertIn("apply(devices,saved,progress);",body)
        self.assertIn("rememberApplied(devices,saved);",body)
        self.assertLess(body.index("apply(devices,saved,progress);"),body.index("rememberApplied(devices,saved);"))
        self.assertNotIn("resyncPolicy.onSuccess(signature);",body)
    def test_remember_uses_actual_device_ipv6(self):
        body=SRC.split("private void rememberApplied(",1)[1].split("synchronized void resyncAddresses(",1)[0]
        self.assertIn("selected(devices,requested)",body)
        self.assertIn("resyncPolicy.onSuccess(signature(active))",body)
    def test_apply_still_enriches_before_configuring_rules(self):
        body=SRC.split("synchronized void apply(",1)[1].split("private static String signature(",1)[0]
        self.assertLess(body.index("enrichIpv6Neighbors(devices)"),body.index("List<Active> active=selected(devices,requested)"))
if __name__=="__main__":unittest.main()
