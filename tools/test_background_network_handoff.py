"""Prevent stale background network requests after foreground ownership handoff."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java")


class BackgroundNetworkHandoffTest(unittest.TestCase):
    def test_each_followup_read_checks_ownership(self):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index("private void backgroundCycle(){")
        end = source.index("private ArrayDeque<Float> history(", start)
        body = source[start:end]
        radio = body.index("try{radio=router.status();}")
        devices = body.index("try{devices=router.devices();}", radio)
        blocked = body.index("try{blockState=router.blockState();}", devices)
        guard = "if(!ownsBackgroundCycle()){releaseLocks();return;}"
        self.assertIn(guard, body[radio:devices])
        self.assertIn(guard, body[devices:blocked])
        # The final snapshot must still be protected after the last request.
        self.assertIn(guard, body[blocked:body.index("long now=System.currentTimeMillis();", blocked)])


if __name__ == "__main__":
    unittest.main()
