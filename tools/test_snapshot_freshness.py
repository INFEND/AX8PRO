"""Regression guards for expiring foreground client, SMS and ACL snapshots."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")

class SnapshotFreshnessTest(unittest.TestCase):
    def test_resume_and_ticker_expire_stale_sections(self):
        self.assertIn("expireStaleSections(now);",MAIN)
        self.assertIn("if(expireStaleSections(System.currentTimeMillis())){rebuildNav();draw(true);}",MAIN)
        self.assertIn("expireStaleSections(System.currentTimeMillis());",MAIN)
    def test_each_section_has_own_freshness_guard(self):
        block=MAIN.split("private boolean expireStaleSections(long now){",1)[1].split("private void loadBackgroundSnapshot()",1)[0]
        for section in ("clientsAt", "smsAt", "blockedAt"):
            self.assertIn(f"BackgroundSummary.isFresh({section},now,limit)",block)
        self.assertIn("devices=new ArrayList<>();clientsAt=0;",block)
        self.assertIn("messages=new ArrayList<>();smsAt=0;",block)
        self.assertIn("blocked=null;blockedAt=0;",block)
    def test_missing_data_is_unknown_not_zero(self):
        for marker in ('clientsAt>0?String.valueOf(devices.size()):"—"',
                       'smsAt>0?String.valueOf(messages.size()):"—"',
                       'smsAt>0?String.valueOf(unread):"—"',
                       '"Список клиентов ещё не загружен"',
                       '"Список SMS ещё не загружен"'):
            self.assertIn(marker,MAIN)
        self.assertIn("CLIENTS_READY:",MAIN)
        self.assertIn("SMS_READY:",MAIN)
    def test_unread_badge_never_uses_expired_background_cache(self):
        badge=MAIN.split("private int unreadBadgeCount(){",1)[1].split("private void showTab(",1)[0]
        self.assertIn('BackgroundSummary.isFresh(prefs.getLong("bg_sms_at",0L),now,limit)',badge)
        self.assertIn("return 0;",badge)
        self.assertIn("BackgroundSummary.isFresh(smsTime,now,limit)",MAIN)
    def test_qos_never_falls_back_to_previous_clients(self):
        worker=MAIN.split("String qosIssue=\"\";",1)[1].split("final String limitedError=partialFailure;",1)[0]
        self.assertIn("if(monitorOn&&d!=null&&foregroundCycleActive(cycleGeneration))",worker)
        self.assertIn("traffic.sample(d)",worker)
        self.assertIn("priority.update(d,traffic,qos)",worker)
        self.assertIn("uiQosWatch.check(d,traffic,qos)",worker)
        self.assertNotIn("new ArrayList<>(devices)",worker)
    def test_acl_timestamp_set_from_both_foreground_and_background(self):
        self.assertIn("blockedAt=System.currentTimeMillis();",MAIN)
        self.assertIn("this.blockedAt=blocked==null?0:blockedAt;",MAIN)

if __name__=="__main__":unittest.main()
