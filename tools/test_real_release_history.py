"""Check the actual shipped changelog, not only synthetic OTA fixtures."""
import importlib.util
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("ax8_make_manifest", ROOT / "scripts" / "make_manifest.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RealReleaseHistoryTest(unittest.TestCase):
    def test_checked_in_history_is_valid_for_current_gradle_version(self):
        gradle = (ROOT / "app" / "build.gradle").read_text(encoding="utf-8")
        code_match = re.search(r"(?m)^\s*versionCode\s+(\d+)\s*$", gradle)
        name_match = re.search(r"(?m)^\s*versionName\s+['\"]([^'\"]+)['\"]\s*$", gradle)
        self.assertIsNotNone(code_match, "Gradle versionCode not found")
        self.assertIsNotNone(name_match, "Gradle versionName not found")
        code = int(code_match.group(1))
        name = name_match.group(1)
        history = json.loads((ROOT / "release_notes" / "history.json").read_text(encoding="utf-8"))
        selected = MODULE.cumulative_history(name, code, history)
        self.assertEqual(len(selected), code - 18)
        self.assertEqual(selected[-1]["versionName"], name)
        self.assertEqual(selected[-1]["versionCode"], code)


if __name__ == "__main__":
    unittest.main()
