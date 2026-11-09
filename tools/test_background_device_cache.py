"""Regression guards for malformed device entries in background snapshots."""
import pathlib
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java").read_text(encoding="utf-8")


class BackgroundDeviceCacheTest(unittest.TestCase):
    def test_null_device_does_not_break_json_serialization(self):
        section = SOURCE.split("private static JSONArray devicesJson(", 1)[1].split(
            "private static JSONArray smsJson(", 1)[0]
        self.assertIn("if(d==null)continue;", section)
        self.assertLess(section.index("if(d==null)continue;"), section.index("d.mac"))

    def test_missing_ipv6_list_is_allowed(self):
        section = SOURCE.split("private static JSONArray devicesJson(", 1)[1].split(
            "private static JSONArray smsJson(", 1)[0]
        self.assertIn("if(d.ipv6!=null)for(String x:d.ipv6)", section)


if __name__ == "__main__":
    unittest.main()
