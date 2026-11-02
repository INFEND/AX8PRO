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

    def test_old_worker_skips_router_reads_after_pause(self):
        worker=MAIN.split("private void refresh(boolean forced){",1)[1].split("final String limitedError=partialFailure;",1)[0]
        for marker in ("wantStatus", "wantClients", "wantBlocked", "wantSms"):
            self.assertIn(f"if({marker}&&foregroundCycleActive(cycleGeneration))",worker)

    def test_qos_and_traffic_require_foreground_ownership(self):
        worker=MAIN.split("String qosIssue=\"\";",1)[1].split("final String limitedError=partialFailure;",1)[0]
        self.assertIn("if(d!=null&&foregroundCycleActive(cycleGeneration))",worker)
        self.assertIn("if(monitorOn&&d!=null&&foregroundCycleActive(cycleGeneration))",worker)
        self.assertIn("if(priority.enabled&&foregroundCycleActive(cycleGeneration))",worker)
        self.assertIn("&&foregroundCycleActive(cycleGeneration))\n                                uiQosWatch.check",worker)
        self.assertIn("if(foregroundCycleActive(cycleGeneration)){",worker)

    def test_cross_thread_generation_is_volatile(self):
        session=(ROOT/"app/src/main/java/ru/xarkon/ax8/RefreshSession.java").read_text(encoding="utf-8")
        self.assertIn("private volatile long generation;",session)
        self.assertIn("refreshSession.accepts(generation,running)",MAIN)
        self.assertIn('prefs.getBoolean("ui_visible",true)',MAIN)

if __name__=="__main__":
    unittest.main()
