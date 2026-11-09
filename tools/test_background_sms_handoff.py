"""Guard against stale SMS alerts when the UI regains ownership mid-poll."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java")


class BackgroundSmsHandoffTest(unittest.TestCase):
    def test_sms_cache_write_rechecks_background_ownership(self):
        source = SOURCE.read_text(encoding="utf-8")
        section = source.split("if(smsList!=null){", 1)[1].split(
            "try{radio=router.status();", 1
        )[0]
        self.assertLess(section.index("if(!ownsBackgroundCycle())"),
                        section.index('putString("bg_sms_seen_ids",mergedIds)'))

    def test_sms_alert_loop_rechecks_ownership_for_each_message(self):
        source = SOURCE.read_text(encoding="utf-8")
        section = source.split("if(!pending.isEmpty()){", 1)[1].split(
            "try{radio=router.status();", 1
        )[0]
        self.assertIn("i>=0&&ownsBackgroundCycle()", section)


if __name__ == "__main__":
    unittest.main()
