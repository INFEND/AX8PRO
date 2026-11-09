"""Source-level guard for foreground/background QoS ownership.

These checks do not replace an AX8 hardware integration test.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java"
ACTIVITY = ROOT / "app/src/main/java/ru/xarkon/ax8/MainActivity.java"


class BackgroundHandoffContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.service = SERVICE.read_text(encoding="utf-8")
        cls.activity = ACTIVITY.read_text(encoding="utf-8")

    def test_background_worker_waits_for_foreground_cleanup(self):
        tick = self.service.split("private final Runnable tick=", 1)[1].split(
            "private void scheduleNextTick()", 1
        )[0]
        self.assertIn("ForegroundHandoff.pending()", tick)
        self.assertIn("scheduleNextTick();return;", tick)

    def test_background_ownership_requires_hidden_ui_and_matching_router(self):
        ownership = self.service.split("private boolean ownsBackgroundCycle()", 1)[1].split(
            "static void clearCachedSnapshot(", 1
        )[0]
        for requirement in (
            "!stopping",
            "!ForegroundHandoff.pending()",
            '!prefs.getBoolean("ui_visible",true)',
            "endpoint.sameConnection(configuredEndpoint())",
        ):
            self.assertIn(requirement, ownership)

    def test_pause_announces_handoff_before_queued_cleanup(self):
        pause = self.activity.split("@Override protected void onPause()", 1)[1].split(
            "@Override protected void onNewIntent(", 1
        )[0]
        self.assertLess(pause.index("ForegroundHandoff.begin()"), pause.index("workers.execute("))
        self.assertLess(pause.index('putBoolean("ui_visible",false)'), pause.index("workers.execute("))
        self.assertIn("finally{ForegroundHandoff.end();}", pause)
        self.assertIn("catch(RejectedExecutionException rejected)", pause)


if __name__ == "__main__":
    unittest.main()
