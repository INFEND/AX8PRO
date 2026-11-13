"""Guard against applying a stale UI client snapshot after QoS confirmation."""
import pathlib
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE=(ROOT/"app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")
class QosConfirmationTest(unittest.TestCase):
    def test_refresh_clients_after_confirmation(self):
        section=SOURCE.split("private void applyLimitFromSheet(",1)[1].split("private void sms()",1)[0]
        self.assertIn("List<RouterClient.Device> current=api.devices();",section)
        self.assertIn("qos.setLimit(mac,kb,current,p)",section)
        self.assertNotIn("qos.setLimit(mac,kb,devices,p)",section)
    def test_disconnected_client_does_not_get_qos(self):
        section=SOURCE.split("private void applyLimitFromSheet(",1)[1].split("private void sms()",1)[0]
        self.assertIn("if(!found)throw new java.io.IOException(",section)
        self.assertLess(section.index("if(!found)throw"),section.index("qos.setLimit(mac,kb,current,p)"))
    def test_offline_policy_stays_local(self):
        section=SOURCE.split("if(!live||!present){",1)[1].split("return;",1)[0]
        self.assertIn("qos.persist(saved,mac)",section)
        self.assertNotIn("qos.setLimit(",section)
if __name__=="__main__":
    unittest.main()
