"""Guard against displaying partial dual-stack traffic when IPv6 is unavailable."""
import re
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/ru/xarkon/ax8/DeviceTrafficMonitor.java"


class MissingIpv6BackendTest(unittest.TestCase):
    def test_dual_stack_is_invalidated_on_missing_backend_or_read_failure(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertRegex(
            source,
            r"if\s*\(\s*\(ipv6ReadFailed\s*\|\|\s*active6\s*==\s*null\)\s*&&\s*d\.ipv6\s*!=\s*null\s*&&\s*!d\.ipv6\.isEmpty\(\)\s*\)\s*\{\s*prior\.remove\(d\.mac\);\s*rates\.remove\(d\.mac\);\s*continue;",
        )
        self.assertIn("if(active4==null && active6==null)", source)


if __name__ == "__main__":
    unittest.main()
