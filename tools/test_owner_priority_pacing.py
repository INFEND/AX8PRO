"""Guard the owner-priority pacing contract without requiring an Android device.

These checks are intentionally source-level: they do not replace integration
tests with a real router, but catch accidental reintroduction of wall-clock
timing and premature mutation of the recorded QoS cap.
"""
from pathlib import Path
import re
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / "app/src/main/java/ru/xarkon/ax8/OwnerPriorityController.java")


class OwnerPriorityPacingSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text(encoding="utf-8")

    def test_pacing_uses_monotonic_clock(self):
        self.assertIn("System.nanoTime()", self.source)
        self.assertNotIn("System.currentTimeMillis()", self.source)

    def test_pacing_remains_rate_limited(self):
        self.assertRegex(self.source, r"now\s*-\s*lastEval\s*<\s*2500")
        self.assertRegex(self.source, r"lastEval\s*=\s*now")

    def test_cap_changes_only_after_qos_returns(self):
        match = re.search(r"private void apply\s*\([^)]*\)\s*throws Exception\s*\{(.*?)\n    \}", self.source, re.S)
        self.assertIsNotNone(match, "Cannot locate apply() for QoS contract check")
        body = match.group(1)
        call = body.find("qos.applyPriority(")
        assignment = body.find("cap=wanted")
        self.assertGreaterEqual(call, 0)
        self.assertGreater(assignment, call, "QoS cap must not be committed before the router call returns")


if __name__ == "__main__":
    unittest.main()
