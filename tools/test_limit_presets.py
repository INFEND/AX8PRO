import pathlib
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE=(ROOT/"app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")
class LimitPresetsTest(unittest.TestCase):
    def test_presets(self):
        self.assertIn("100,150,250,300,500,750,1000,-1", SOURCE)
        self.assertIn('kb==-1?"✎":limitName(kb)', SOURCE)
    def test_slider_bounds(self):
        self.assertIn("seek.setMax(1000)", SOURCE)
        self.assertIn("custom.setMax(1000)", SOURCE)
        self.assertIn("Math.min(1000,current)", SOURCE)
    def test_manual_validation_and_apply(self):
        self.assertIn("value<0||value>125000", SOURCE)
        self.assertIn("Integer.parseInt(raw)", SOURCE)
        self.assertIn("showManualLimit(current,value->applyLimitFromSheet", SOURCE)
        self.assertIn("showManualLimit(null,value->applyBulkLimit", SOURCE)
