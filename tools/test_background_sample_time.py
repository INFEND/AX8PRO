"""Background samples retain their own acquisition timestamps."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java")

class BackgroundSampleTimeTest(unittest.TestCase):
    def test_each_sample_is_timestamped_before_request(self):
        source = SOURCE.read_text(encoding="utf-8")
        body = source.split("private void backgroundCycle(){", 1)[1]
        for stamp, request, key in (
            ("smsAt", "smsList=router.sms()", "bg_sms_at"),
            ("radioAt", "radio=router.status()", "bg_snapshot_at"),
            ("devicesAt", "devices=router.devices()", "bg_devices_at"),
            ("blockedAt", "blockState=router.blockState()", "bg_blocked_at"),
        ):
            self.assertLess(body.index("long "+stamp+"=System.currentTimeMillis();"),
                            body.index(request))
            self.assertIn('putLong("'+key+'",'+stamp+')', body)
            self.assertNotIn('putLong("'+key+'",now)', body)

    def test_handoff_uses_cycle_identity_not_sample_time(self):
        service = SOURCE.read_text(encoding="utf-8")
        main = SOURCE.with_name("MainActivity.java").read_text(encoding="utf-8")
        for key, stamp in (("bg_devices_cycle_at", "deviceAt"),
                           ("bg_blocked_cycle_at", "blockedAt"),
                           ("bg_sms_cycle_at", "smsTime")):
            self.assertIn('putLong("'+key+'",radio!=null?radioAt:0L)', service)
            self.assertIn('prefs.getLong("'+key+'",0L)==ts', main)
            self.assertIn('BackgroundSummary.isFresh('+stamp+',now,limit)', main)
        self.assertNotIn("deviceAt==ts", main)
        self.assertNotIn("blockedAt==ts", main)
        self.assertIn('.remove("bg_sms_cycle_at").commit()', service)

    def test_blocklist_failure_is_reported_and_affects_adaptive_poll(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("try{blockState=router.blockState();}catch(Exception e){blockedIssue=", source)
        self.assertIn('(!blockedIssue.isEmpty()?"Блокировки: "+blockedIssue+"; ":"")', source)
        self.assertIn("cycleSucceeded=radio!=null && issues.isEmpty();", source)

if __name__ == "__main__":
    unittest.main()
