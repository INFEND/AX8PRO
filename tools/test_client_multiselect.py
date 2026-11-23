"""Regression: grouped ACL belongs to device details and multi-selection works in both lists."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/ru/xarkon/ax8/MainActivity.java"


class ClientMultiSelectTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = MAIN.read_text(encoding="utf-8")

    def test_saved_group_access_is_inside_details(self):
        view = self.src.split("private void addSavedClientGroup(", 1)[1].split("private void showSavedClientGroup(", 1)[0]
        self.assertNotIn("changeSavedGroupBlock(group,!allBlocked)", view)
        self.assertIn("showSavedClientGroup(group)", view)
        self.assertIn("addGroupAccessButton(body,group", self.src)
        self.assertIn("showDevice(RouterClient.Device dev)", self.src)

    def test_long_press_connected_and_saved(self):
        self.assertRegex(self.src, r"card\.setOnLongClickListener\(v->\{toggleClientSelection\(key\)")
        self.assertRegex(self.src, r"header\.setOnLongClickListener\(v->\{toggleClientSelection\(group\.key\)")
        self.assertIn("Заблокировать выбранные", self.src)
        self.assertIn("addClientSelectionBar(saved)", self.src)
        self.assertIn("addClientSelectionBar(content)", self.src)

    def test_whole_group_transaction_and_owner_guard(self):
        self.assertIn("addresses.addAll(found.macs)", self.src)
        self.assertIn("api.setBlockedGroup(macs,block,protectedMac)", self.src)
        self.assertIn("distinct.contains(fromRouter)", self.src)
        self.assertIn("selectedClientGroups.clear()", self.src)

if __name__ == "__main__":
    unittest.main()
