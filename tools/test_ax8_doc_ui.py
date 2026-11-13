"""Regression checks for UI and local-only disconnected-client QoS editing."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")
UI = (ROOT / "app/src/main/java/ru/xarkon/ax8/Ui.java").read_text(encoding="utf-8")

class Ax8DocumentUiTest(unittest.TestCase):
    def test_upload_aligns_right(self):
        self.assertIn("ul.setGravity(Gravity.END)", MAIN)
        self.assertIn("upValue.setGravity(Gravity.END|Gravity.CENTER_VERTICAL)", MAIN)

    def test_five_graph_levels_with_matching_labels(self):
        self.assertIn("for(int mark=0;mark<=4;mark++)", UI)
        self.assertIn("rate(max*ratio)", UI)
        self.assertNotIn("for(float ratio:new float[]{0f,.5f,1f})", UI)

    def test_offline_limit_is_local_only(self):
        body = MAIN.split("private void applyLimitFromSheet(", 1)[1].split("private void sms()", 1)[0]
        local = body.split("if(!live||!present){", 1)[1].split("return;", 1)[0]
        self.assertIn("qos.persist(saved,mac)", local)
        self.assertNotIn("qos.setLimit(", local)
        self.assertIn("Применение на AX8 не подтверждено", local)

    def test_remembered_clients_collapsible_and_not_reported_online(self):
        body = MAIN.split("private void showRememberedClients()", 1)[1].split("private static String humanRate", 1)[0]
        self.assertIn("knownClientsExpanded=false", MAIN)
        self.assertIn("known.remove(RouterClient.normalizeMac(active.mac))", body)
        self.assertIn("chooseLimit(remembered)", body)
        self.assertIn("SavedClientGroups.build(known", body)
        self.assertIn("qos::limitRevision,liveLimits", body)
        self.assertIn("live?liveLimits.get(mac):savedCap", body)
        self.assertIn("if(knownClientsExpanded)b.append(savedGroupSignature())", MAIN)
        self.assertIn("savedLimitsExpanded?\"/\"+savedGroupSignature()", MAIN)

    def test_band_command_not_changed(self):
        self.assertIn('"AUTO"', MAIN)
        self.assertNotIn('"◎  AUTO BAND  ·  Все поддерживаемые"', MAIN)

    def test_router_connection_error_is_specific(self):
        self.assertIn("Отсутствует подключение к роутеру.", MAIN)
        self.assertIn("routerTransportFailure(error)", MAIN)

    def test_font_picker_is_removed(self):
        self.assertNotIn("theme.addView(Ui.button(this,\"Aa  Шрифт", MAIN)
        self.assertIn('getFont(R.font.tektur_family)', UI)
        self.assertNotIn('sans-serif-condensed', UI)

if __name__ == "__main__":
    unittest.main()
