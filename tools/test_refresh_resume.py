"""Regression guard for immediate foreground refresh after a stale in-flight cycle."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")

class RefreshResumeTest(unittest.TestCase):
    def test_sms_fingerprint_includes_all_card_fields(self):
        fingerprint=MAIN.split("private String smsFingerprint(){",1)[1].split("private void updateSmsLive()",1)[0]
        for field in ("id", "tag", "number", "date", "content"):
            self.assertIn(".put(sms."+field+")",fingerprint)
        self.assertNotIn("hashCode()",fingerprint.replace("String.hashCode collisions",""))
        self.assertIn("new org.json.JSONArray()",fingerprint)

    def test_forced_refresh_is_deferred_while_worker_busy(self):
        gate=MAIN.split("private void refresh(boolean forced){",1)[1].split("long now=System.currentTimeMillis();",1)[0]
        self.assertIn("if(refreshBusy){",gate)
        self.assertIn("if(forced&&running)refreshSession.deferForced();",gate)
        self.assertIn("if((actionBusy&&!temporaryAction)||!running)return;",gate)
        self.assertIn("if(forced&&running)refreshSession.deferForced();",gate)

    def test_stale_result_restarts_queued_foreground_refresh(self):
        callback=MAIN.split("handler.post(()->{\n                refreshBusy=false;",1)[1].split("observePoll(",1)[0]
        self.assertIn("!refreshSession.accepts(cycleGeneration,running)",callback)
        self.assertIn("refreshSession.takeDeferredForced(running)",callback)
        self.assertIn("refresh(true);",callback)

    def test_valid_cycle_honours_queued_request_too(self):
        callback=MAIN.split("if(refreshSession.takeDeferredForced(running))refresh(true);",1)
        self.assertEqual(len(callback),2)
        self.assertIn("private void clearPendingBand()",callback[1])

    def test_status_timestamp_precedes_slow_followup_reads(self):
        worker=MAIN.split("private void refresh(boolean forced){",1)[1].split("private void clearPendingBand()",1)[0]
        self.assertLess(worker.index("statusSampleElapsed=android.os.SystemClock.elapsedRealtime();"),worker.index("d=api.devices()"))
        self.assertIn("statusAt=earlySampleWall",worker)
        self.assertIn("long sampleTime=sampledWall;",worker)
        self.assertIn("long counterTime=sampledElapsed;",worker)
        self.assertIn("lastBytesAt=counterTime",worker)

    def test_early_status_rejects_inactive_ui_and_router_actions(self):
        early=MAIN.split("final JSONObject early=next;",1)[1].split("invalidateInternetOnPppChange(early);",1)[0]
        self.assertIn("!refreshSession.accepts(cycleGeneration,running)",early)
        self.assertIn("||isFinishing()||isDestroyed()||actionBusy",early)

    def test_section_timestamps_are_captured_before_qos(self):
        worker=MAIN.split("private void refresh(boolean forced){",1)[1].split("private void clearPendingBand()",1)[0]
        for read, stamp, assignment in (
            ("d=api.devices();", "clientsSampleWall", "clientsAt=clientsSample;"),
            ("b=api.blockState();", "blockedSampleWall", "blockedAt=blockedSample;"),
            ("m=api.sms();", "smsSampleWall", "smsAt=smsSample;"),
        ):
            capture=read+stamp+"=System.currentTimeMillis();"
            self.assertIn(capture,worker)
            self.assertLess(worker.index(capture),worker.index("qos.resyncAddresses"))
            self.assertIn(assignment,worker)

    def test_sms_is_published_before_optional_qos(self):
        worker=MAIN.split("private void refresh(boolean forced){",1)[1].split("private void clearPendingBand()",1)[0]
        early=worker.split("if(m!=null&&foregroundCycleActive(cycleGeneration)){",1)[1].split("// Optional Telnet counters:",1)[0]
        self.assertLess(worker.index("messages=earlySms;smsAt=earlySmsAt;"),worker.index("qos.resyncAddresses"))
        self.assertIn("!refreshSession.accepts(cycleGeneration,running)",early)
        self.assertIn("||isFinishing()||isDestroyed()||actionBusy",early)
        self.assertIn("if(tab==2)updateSmsLive();",early)
        self.assertNotIn("refreshBusy=false",early)

    def test_action_completion_forces_refresh_after_releasing_busy_gate(self):
        callback=MAIN.split("actionBusy=false;temporaryAction=false;displayWork",1)[1].split("private RouterClient temporaryRouter()",1)[0]
        self.assertIn("clientsAt=0;smsAt=0;statusAt=0;",callback)
        self.assertIn("updateConnection();draw(true);refresh(true);",callback)
        self.assertLess(callback.index("clientsAt=0;"),callback.index("refresh(true);"))

    def test_tab_scroll_is_preserved_and_stale_callbacks_rejected(self):
        switch=MAIN.split("private void showTab(int index){",1)[1].split("private void displayWork",1)[0]
        self.assertIn("if(index==tab)return;",switch)
        self.assertIn("tabScrollY[tab]=pendingScrollY>=0?pendingScrollY:scroll.getScrollY();",switch)
        self.assertIn("pendingScrollY=tabScrollY[index]",switch)
        draw=MAIN.split("private void draw(boolean preserveScroll){",1)[1].split("private void addCard",1)[0]
        self.assertIn("revision!=scrollRestoreRevision",draw)
        self.assertIn("scroll!=target",draw)
        self.assertIn("pendingScrollY=-1;",draw)

    def test_initial_draw_and_activity_state(self):
        create=MAIN.split("public void onCreate(Bundle state)",1)[1].split("protected void onSaveInstanceState",1)[0]
        self.assertNotIn("showTab(0)",create)
        self.assertIn("rebuildNav();draw(true);",create)
        self.assertIn('state.getInt("active_tab",0)',create)
        self.assertIn("restored<TABS.length",create)
        self.assertIn("positions.length==tabScrollY.length",create)
        self.assertIn("pendingScrollY=tabScrollY[tab]",create)
        save=MAIN.split("protected void onSaveInstanceState",1)[1].split("protected void onResume",1)[0]
        self.assertIn('state.putInt("active_tab",tab)',save)
        self.assertIn('state.putIntArray("tab_scroll_y",tabScrollY.clone())',save)
        self.assertIn("super.onSaveInstanceState(state)",save)

    def test_pause_invalidates_old_generation(self):
        self.assertIn("refreshSession.invalidate();",MAIN)

    def test_old_worker_skips_router_reads_after_pause(self):
        worker=MAIN.split("private void refresh(boolean forced){",1)[1].split("final String limitedError=partialFailure;",1)[0]
        for marker in ("wantStatus", "wantClients", "wantBlocked", "wantSms"):
            self.assertIn(f"if({marker}&&foregroundCycleActive(cycleGeneration))",worker)

    def test_qos_and_traffic_require_foreground_ownership(self):
        worker=MAIN.split("String qosIssue=\"\";",1)[1].split("final String limitedError=partialFailure;",1)[0]
        self.assertIn("if(d!=null&&foregroundCycleActive(cycleGeneration))",worker)
        self.assertIn("if(monitorOn&&d!=null&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration))",worker)
        self.assertIn("if(priority.enabled&&monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration))",worker)
        self.assertIn("&&monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration))\n                                uiQosWatch.check",worker)
        self.assertIn("if(monitorOn&&cycleTrafficEpoch==trafficMonitorEpoch&&foregroundCycleActive(cycleGeneration)){",worker)

    def test_cross_thread_generation_uses_monitor_lock(self):
        session=(ROOT/"app/src/main/java/ru/xarkon/ax8/RefreshSession.java").read_text(encoding="utf-8")
        self.assertIn("private long generation;",session)
        self.assertIn("synchronized long capture()",session)
        self.assertIn("synchronized void invalidate()",session)
        self.assertIn("synchronized boolean accepts(long captured, boolean active)",session)
        self.assertIn("synchronized void deferForced()",session)
        self.assertIn("synchronized boolean takeDeferredForced(boolean active)",session)
        self.assertIn("refreshSession.accepts(generation,running)",MAIN)
        self.assertIn('prefs.getBoolean("ui_visible",true)',MAIN)

if __name__=="__main__":
    unittest.main()
