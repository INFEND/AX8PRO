"""Regression: a finished background poll must not notify after UI handoff."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/RouterBackgroundService.java")


class BackgroundStatusHandoffTest(unittest.TestCase):
    def test_final_notification_requires_background_ownership(self):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index('String issues=(!rebindWarning.isEmpty()')
        end = source.index('private ArrayDeque<Float> history(', start)
        section = source[start:end]
        guard = section.index('if(!ownsBackgroundCycle())return;')
        self.assertLess(section.index('editor.putString("bg_error",issues)'), guard)
        self.assertLess(guard, section.index('if(!issues.isEmpty())notifyState('))
        self.assertLess(guard, section.index('else notifyState("Клиентов:'))


if __name__ == "__main__":
    unittest.main()
