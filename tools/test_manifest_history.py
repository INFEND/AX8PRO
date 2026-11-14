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
        self.assertEqual(MODULE.cumulative_history("1.5.2", 20, history), history)

    def test_minor_rollover_preserves_history(self):
        history = [self.entry(code) for code in range(19, 118)]
        history += [dict(self.entry(118), versionName="1.6.0"),
                    dict(self.entry(119), versionName="1.6.1")]
        self.assertEqual(MODULE.cumulative_history("1.5.99", 117, history), history[:-2])
        self.assertEqual(MODULE.cumulative_history("1.6.0", 118, history), history[:-1])
        self.assertEqual(MODULE.cumulative_history("1.6.1", 119, history), history)
        history[-2] = dict(history[-2], versionName="1.5.100")
        with self.assertRaises(ValueError):
            MODULE.cumulative_history("1.6.1", 119, history)

    def test_rejects_gap_duplicate_and_wrong_version(self):
        for history in (
            [self.entry(19), self.entry(21)],
            [self.entry(19), self.entry(19)],
            [self.entry(19), dict(self.entry(20), versionName="1.5.99")],
        ):
            with self.subTest(history=history):
                with self.assertRaises(ValueError):
                    MODULE.cumulative_history("1.5.2", 20, history)

    def test_rejects_malformed_notes(self):
        for notes in ("", "Без маркера", "[=]    ", "[+] \t", "[=] Полезное изменение\n[-]   ", "[=] " + "x" * 260, ["[=] список"]):
            with self.subTest(notes=notes):
                with self.assertRaises(ValueError):
                    MODULE.cumulative_history("1.5.1", 19, [self.entry(19, notes)])

    def test_rejects_missing_requested_version(self):
        with self.assertRaises(ValueError):
            MODULE.cumulative_history("1.5.2", 20, [self.entry(19)])


if __name__ == "__main__":
    unittest.main()
