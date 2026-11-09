"""Guard foreground handoff notification against service shutdown."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java")


class BackgroundShutdownNotificationTest(unittest.TestCase):
    def test_handoff_cleanup_does_not_notify_after_shutdown(self):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index("private void endBackgroundCycle(){")
        end = source.index("@Override public void onDestroy()", start)
        body = source[start:end]
        self.assertIn('if(!stopping)notifyState("Приложение открыто · мониторинг через интерфейс");', body)
        self.assertLess(body.index("releaseLocks();"), body.index("if(!stopping)notifyState("))


if __name__ == "__main__":
    unittest.main()
