"""Regression guard for immediate foreground refresh after a stale in-flight cycle."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")

class RefreshResumeTest(unittest.TestCase):
    def test_forced_refresh_is_deferred_while_worker_busy(self):
        gate=MAIN.split("private void refresh(boolean forced){",1)[1].split("long now=System.currentTimeMillis();",1)[0]
        self.assertIn("if(refreshBusy){",gate)
        self.assertIn("if(forced&&running)refreshSession.deferForced();",gate)
        self.assertIn("if(actionBusy||!running)return;",gate)

    def test_stale_result_restarts_queued_foreground_refresh(self):
        callback=MAIN.split("handler.post(()->{\n                refreshBusy=false;",1)[1].split("observePoll(",1)[0]
        self.assertIn("!refreshSession.accepts(cycleGeneration,running)",callback)
        self.assertIn("refreshSession.takeDeferredForced(running)",callback)
        self.assertIn("refresh(true);",callback)

    def test_valid_cycle_honours_queued_request_too(self):
        callback=MAIN.split("if(refreshSession.takeDeferredForced(running))refresh(true);",1)
        self.assertEqual(len(callback),2)
        self.assertIn("private void clearPendingBand()",callback[1])

    def test_pause_invalidates_old_generation(self):
        self.assertIn("refreshSession.invalidate();",MAIN)

if __name__=="__main__":
    unittest.main()
