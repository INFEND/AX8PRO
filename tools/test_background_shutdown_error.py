"""Regression: a stopped service must not publish a late poll failure."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java")


class BackgroundShutdownErrorTest(unittest.TestCase):
    def test_late_poll_failure_does_not_publish_error_or_notification(self):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index('catch(Exception e){', source.index('backgroundCycle();'))
        end = source.index('}finally{', start)
        catch_body = source[start:end]
        guard = catch_body.index('if(!stopping){')
        self.assertLess(guard, catch_body.index('putString("bg_error",reason)'))
        self.assertLess(guard, catch_body.index('notifyState("Фоновый опрос AX8: "+reason)'))
        self.assertIn('if(!stopping && prefs.getBoolean("refresh_auto",true))', source)


if __name__ == "__main__":
    unittest.main()
