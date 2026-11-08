"""Regression guard for foreground QoS handoff ordering.

This is a source-level contract check, not a device-level QoS test.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/ru/xarkon/ax8/MainActivity.java"


class ForegroundPriorityLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text(encoding="utf-8")

    def test_resume_activation_is_queued_and_session_guarded(self):
        source = self.source
        resume = source.split("@Override protected void onResume()", 1)[1].split(
            "@Override protected void onPause()", 1
        )[0]
        self.assertRegex(
            resume,
            r"running\s*=\s*true\s*;\s*final long resumeGeneration\s*=\s*refreshSession\.capture\(\)",
        )
        self.assertRegex(
            resume,
            r"workers\.execute\(\(\)->\{\s*if\(refreshSession\.accepts\(resumeGeneration,running\)",
        )
        self.assertIn("priority.enable(ownerMac)", resume)
        self.assertLess(resume.index("workers.execute("), resume.index("priority.enable(ownerMac)"))

    def test_pause_invalidates_then_queues_cleanup_with_barrier(self):
        pause = self.source.split("@Override protected void onPause()", 1)[1].split(
            "@Override protected void onNewIntent(", 1
        )[0]
        for token in (
            "refreshSession.invalidate()",
            "running=false",
            "ForegroundHandoff.begin()",
            "workers.execute(",
            "priority.disable()",
            "ForegroundHandoff.end()",
        ):
            self.assertIn(token, pause)
        self.assertLess(pause.index("refreshSession.invalidate()"), pause.index("workers.execute("))
        self.assertLess(pause.index("ForegroundHandoff.begin()"), pause.index("workers.execute("))
        self.assertRegex(pause, r"finally\s*\{\s*ForegroundHandoff\.end\(\);\s*\}")
        self.assertIn("catch(RejectedExecutionException rejected)", pause)


if __name__ == "__main__":
    unittest.main()
