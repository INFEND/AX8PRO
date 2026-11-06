"""Keep the Gradle release version and OTA changelog in sync."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class ReleaseHistorySyncTest(unittest.TestCase):
    def test_current_version_has_nonempty_ota_notes(self):
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        code = re.search(r"(?m)^\s*versionCode\s+(\d+)\s*$", gradle)
        name = re.search(r"(?m)^\s*versionName\s+['\"]([^'\"]+)['\"]\s*$", gradle)
        self.assertIsNotNone(code, "Missing Gradle versionCode")
        self.assertIsNotNone(name, "Missing Gradle versionName")
        history = json.loads((ROOT / "release_notes/history.json").read_text(encoding="utf-8"))
        matching = [
            entry for entry in history
            if entry.get("versionCode") == int(code.group(1))
            and entry.get("versionName") == name.group(1)
        ]
        self.assertEqual(len(matching), 1, "Current APK version needs exactly one matching OTA history entry")
        notes = matching[0].get("notes")
        self.assertIsInstance(notes, str, "OTA notes must be a string, not a list/object/null")
        self.assertTrue(notes.strip(), "OTA notes must not be empty")


if __name__ == "__main__":
    unittest.main()
