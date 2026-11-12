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
            ("radioAt", "radio=router.status()", "bg_snapshot_at"),
            ("devicesAt", "devices=router.devices()", "bg_devices_at"),
            ("blockedAt", "blockState=router.blockState()", "bg_blocked_at"),
        ):
            self.assertLess(body.index("long "+stamp+"=System.currentTimeMillis();"),
                            body.index(request))
            self.assertIn('putLong("'+key+'",'+stamp+')', body)
            self.assertNotIn('putLong("'+key+'",now)', body)

if __name__ == "__main__":
    unittest.main()
