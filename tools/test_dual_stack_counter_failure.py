"""Guard against combining incomplete IPv6 counter samples with IPv4 rates."""
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/ru/xarkon/ax8/DeviceTrafficMonitor.java"

class DualStackCounterFailureTest(unittest.TestCase):
    def test_failed_ipv6_sample_invalidates_dual_stack_baseline(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("boolean ipv6ReadFailed=false;", source)
        self.assertIn("ipv6ReadFailed=true;", source)
        condition = "if(ipv6ReadFailed && d.ipv6!=null && !d.ipv6.isEmpty())"
        self.assertIn(condition, source)
        body = source.split(condition, 1)[1].split("}", 1)[0]
        self.assertIn("prior.remove(d.mac)", body)
        self.assertIn("rates.remove(d.mac)", body)
        self.assertIn("continue;", body)

if __name__ == "__main__":
    unittest.main()
