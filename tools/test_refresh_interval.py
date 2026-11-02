"""Regression guard for manual polling cadence in the Android activity."""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = ROOT / "app/src/main/java/ru/xarkon/ax8/MainActivity.java"


class ManualPollingIntervalTest(unittest.TestCase):
    def test_manual_refresh_uses_overflow_safe_clamp(self):
        source = ACTIVITY.read_text(encoding="utf-8")
        match = re.search(r"private int refreshInterval\(\)\s*\{(.*?)\n    \}", source, re.S)
        self.assertIsNotNone(match, "refreshInterval() must exist")
        body = match.group(1)
        self.assertIn("AutoPollController.clamp(ms)", body)
        self.assertNotRegex(body, r"ms\s*\+\s*50", "Do not pre-round an untrusted persisted int")


if __name__ == "__main__":
    unittest.main()
