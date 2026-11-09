"""Regression guard: SMS deduplication must follow notification delivery."""
import pathlib
import re
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java").read_text(encoding="utf-8")


class SmsDeliveryHandoffTest(unittest.TestCase):
    def test_notification_reports_success(self):
        section = SOURCE.split("private boolean notifyNewSms(", 1)[1].split(
            "private static String joinUnreadIds", 1)[0]
        self.assertIn("return true;", section)
        self.assertGreaterEqual(section.count("return false;"), 3)

    def test_failed_duplicate_id_remains_pending_in_same_scan(self):
        section = SOURCE.split("Set<String> pending=new HashSet<>(freshIds);", 1)[1].split(
            "if(!ownsBackgroundCycle()){releaseLocks();return;}", 1)[0]
        self.assertIn("pending.contains(item.id) && notifyNewSms(item,unread)", section)
        self.assertLess(section.index("notifyNewSms(item,unread)"),
                        section.index("pending.remove(item.id)"))
        self.assertLess(section.index("pending.remove(item.id)"),
                        section.index("delivered.add(item.id)"))

    def test_seen_ids_committed_after_notification_attempts(self):
        section = SOURCE.split("List<String> freshIds=SmsAlertPlanner.newUnreadIds(", 1)[1].split(
            "try{radio=router.status()", 1)[0]
        self.assertLess(section.index("notifyNewSms(item,unread)"),
                        section.index('putString("bg_sms_seen_ids",mergedIds)'))
        self.assertIn("seen.removeAll(freshIds)", section)
        self.assertIn("seen.addAll(delivered)", section)
        self.assertRegex(section, r"if\(!ownsBackgroundCycle\(\)\)\{releaseLocks\(\);return;\}")

if __name__ == "__main__":
    unittest.main()
