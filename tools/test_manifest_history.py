"""Regression coverage for cumulative OTA changelog validation."""
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("make_manifest", ROOT / "scripts" / "make_manifest.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ManifestHistoryTest(unittest.TestCase):
    @staticmethod
    def entry(code, notes="[=] Проверено обновление"):
        return {"versionCode": code, "versionName": f"1.5.{code - 18}", "notes": notes}

    def test_accepts_contiguous_history(self):
        history = [self.entry(19), self.entry(20)]
        self.assertEqual(MODULE.cumulative_history("1.5.20", 20, history), history)

    def test_rejects_gap_duplicate_and_wrong_version(self):
        for history in (
            [self.entry(19), self.entry(21)],
            [self.entry(19), self.entry(19)],
            [self.entry(19), dict(self.entry(20), versionName="1.5.99")],
        ):
            with self.subTest(history=history):
                with self.assertRaises(ValueError):
                    MODULE.cumulative_history("1.5.20", 20, history)

    def test_rejects_malformed_notes(self):
        for notes in ("", "Без маркера", "[=] " + "x" * 260, ["[=] список"]):
            with self.subTest(notes=notes):
                with self.assertRaises(ValueError):
                    MODULE.cumulative_history("1.5.19", 19, [self.entry(19, notes)])

    def test_rejects_missing_requested_version(self):
        with self.assertRaises(ValueError):
            MODULE.cumulative_history("1.5.20", 20, [self.entry(19)])


if __name__ == "__main__":
    unittest.main()
