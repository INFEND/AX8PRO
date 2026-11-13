"""Source regressions: do not use stale station snapshots for QoS writes."""
import pathlib
import unittest
SOURCE=(pathlib.Path(__file__).resolve().parents[1]/"app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")
class QosFreshnessTest(unittest.TestCase):
    def test_client_freshness_required_for_direct_apply(self):
        part=SOURCE.split("private void applyLimitFromSheet(",1)[1].split("private void sms()",1)[0]
        self.assertIn("System.currentTimeMillis()-clientsAt<=Math.max(10000L,refreshInterval()*3L)",part)
        self.assertIn("&&sectionError.isEmpty()",part)
        self.assertIn("if(!live||!present)",part)
        local=part.split("if(!live||!present)",1)[1].split("return;",1)[0]
        self.assertIn("qos.persist(saved,mac)",local)
        self.assertNotIn("qos.setLimit(",local)
    def test_stale_client_warning(self):
        self.assertIn('rowCard("Список клиентов устарел"',SOURCE)
if __name__=="__main__":unittest.main()
