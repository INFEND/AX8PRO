"""Foreground cleanup must finish before background QoS starts."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")
BG=(ROOT/"app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java").read_text(encoding="utf-8")

class BackgroundHandoffTest(unittest.TestCase):
    def test_barrier_precedes_background_visibility(self):
        pause=MAIN.split("@Override protected void onPause(){",1)[1].split("@Override protected void onNewIntent(",1)[0]
        self.assertLess(pause.index("ForegroundHandoff.begin();"),pause.index('.putBoolean("ui_visible",false)'))
        self.assertIn("}finally{ForegroundHandoff.end();}",pause)
        self.assertIn("catch(RejectedExecutionException rejected)",pause)
    def test_service_waits_for_cleanup(self):
        tick=BG.split("private final Runnable tick=",1)[1].split("private void scheduleNextTick()",1)[0]
        self.assertIn("if(ForegroundHandoff.pending()){scheduleNextTick();return;}",tick)
        cycle=BG.split("private void backgroundCycle(){",1)[1].split("private ArrayDeque<Float> history(",1)[0]
        self.assertIn("if(stopping||ForegroundHandoff.pending())return;",cycle)
    def test_recheck_after_long_operations(self):
        cycle=BG.split("private void backgroundCycle(){",1)[1].split("private ArrayDeque<Float> history(",1)[0]
        for marker in ("qos.resyncAddresses(devices,note->{});","traffic.sample(devices);","priority.update(devices,traffic,qos);"):
            self.assertIn("if(!ownsBackgroundCycle()){releaseLocks();return;}",cycle.split(marker,1)[1][:350])
        helper=BG.split("private boolean ownsBackgroundCycle(){",1)[1].split("\n    }",1)[0]
        for item in ('!ForegroundHandoff.pending()','prefs.getBoolean("ui_visible",true)','endpoint.sameConnection(configuredEndpoint())'):
            self.assertIn(item,helper)

if __name__=="__main__":unittest.main()
