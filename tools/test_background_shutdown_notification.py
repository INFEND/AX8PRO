"""Guard foreground handoff notification against service shutdown."""
from pathlib import Path
import unittest
import re

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java")


class BackgroundShutdownNotificationTest(unittest.TestCase):
    def test_handoff_cleanup_does_not_notify_after_shutdown(self):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index("private void endBackgroundCycle(){")
        end = source.index("@Override public void onDestroy()", start)
        body = source[start:end]
        guard = 'if(BackgroundTickPolicy.showForegroundState(stopping,prefs.getBoolean("ui_visible",true)))'
        self.assertRegex(body, r'(?s)' + re.escape(guard) +
                         r'\s*notifyState\("Приложение открыто · мониторинг через интерфейс"\);')
        self.assertLess(body.index("releaseLocks();"), body.index(guard))
        policy = SOURCE.with_name("BackgroundTickPolicy.java").read_text(encoding="utf-8")
        self.assertRegex(policy, r'showForegroundState\(boolean stopping, boolean uiVisible\)\s*\{\s*return !stopping && uiVisible;\s*\}')


if __name__ == "__main__":
    unittest.main()
