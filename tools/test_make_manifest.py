"""Regression checks for the OTA manifest generator; no Android SDK needed."""
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "make_manifest.py"
spec = importlib.util.spec_from_file_location("ax8_manifest", SCRIPT)
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


class ManifestTests(unittest.TestCase):
    def generate(self, root, repo, package="ru.xarkon.ax8pro"):
        apk = root / "AX8PRO.apk"
        apk.write_bytes(b"test-apk-bytes")
        output = root / "update.json"
        gh_output = root / "github-output.txt"
        args = [
            "make_manifest.py", "--apk", str(apk), "--aapt", "/unused/aapt",
            "--repo", repo, "--output", str(output),
            "--github-output", str(gh_output),
        ]
        badging = (
            f"package: name='{package}' versionCode='22' "
            "versionName='1.5.4'\n"
        )
        with mock.patch.object(sys, "argv", args), mock.patch.object(
            manifest.subprocess, "check_output", return_value=badging
        ):
            manifest.main()
        return json.loads(output.read_text(encoding="utf-8")), gh_output.read_text()

    def test_current_and_legacy_repository_names(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for repo in ("INFEND/AX8PRO", "ANTIFERMENT/AX8PRO", "infend/ax8pro"):
                with self.subTest(repo=repo):
                    data, outputs = self.generate(root, repo)
                    self.assertEqual(data["packageName"], "ru.xarkon.ax8pro")
                    self.assertEqual(data["versionCode"], 22)
                    self.assertEqual(data["versionName"], "1.5.4")
                    self.assertEqual(data["sha256"], hashlib.sha256(b"test-apk-bytes").hexdigest())
                    self.assertEqual(data["sizeBytes"], len(b"test-apk-bytes"))
                    # Older installed clients only accept the legacy release prefix.
                    self.assertEqual(
                        data["apkUrl"],
                        "https://github.com/ANTIFERMENT/AX8PRO/releases/download/v1.5.4/AX8PRO.apk",
                    )
                    self.assertIn("tag=v1.5.4", outputs)
                    self.assertEqual([v["versionCode"] for v in data["history"]],
                                     [19, 20, 21, 22])
                    self.assertEqual(data["notes"], data["history"][-1]["notes"])
                    self.assertTrue(data["notes"].startswith(("[+] ", "[=] ", "[-] ")))

    def test_apk_digest_is_streamed_without_reading_whole_file(self):
        """Large APKs must not be loaded with Path.read_bytes for hashing."""
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            real_digest = hashlib.file_digest
            calls = []

            def checked_digest(stream, algorithm):
                self.assertEqual(algorithm, "sha256")
                self.assertFalse(stream.closed)
                self.assertEqual(stream.mode, "rb")
                calls.append(stream.name)
                return real_digest(stream, algorithm)

            with mock.patch.object(manifest.hashlib, "file_digest",
                                   side_effect=checked_digest), mock.patch.object(
                    Path, "read_bytes", side_effect=AssertionError(
                        "Whole-file APK read is forbidden")):
                data, _ = self.generate(root, "INFEND/AX8PRO")

            self.assertEqual(calls, [str(root / "AX8PRO.apk")])
            self.assertEqual(data["sha256"],
                             hashlib.sha256(b"test-apk-bytes").hexdigest())

    def test_checked_in_android_version_has_release_notes(self):
        """Catch a missing changelog before spending time building and signing an APK."""
        root = SCRIPT.parents[1]
        gradle = (root / "app" / "build.gradle").read_text(encoding="utf-8")
        code = re.search(r"(?m)^\s*versionCode\s+(\d+)\s*$", gradle)
        version = re.search(r"(?m)^\s*versionName\s+['\"]([^'\"]+)['\"]\s*$", gradle)
        self.assertIsNotNone(code, "Android versionCode must be present")
        self.assertIsNotNone(version, "Android versionName must be present")
        history = json.loads((root / "release_notes" / "history.json").read_text(encoding="utf-8"))
        selected = manifest.cumulative_history(version.group(1), int(code.group(1)), history)
        self.assertEqual(selected[-1]["versionCode"], int(code.group(1)))
        self.assertEqual(selected[-1]["versionName"], version.group(1))

    def test_gap_or_duplicate_changelog_rejected(self):
        entries = [
            {"versionCode": 19, "versionName": "1.5.1", "notes": "[+] First."},
            {"versionCode": 21, "versionName": "1.5.3", "notes": "[=] Third."},
        ]
        with self.assertRaisesRegex(ValueError, "Missing, duplicated"):
            manifest.cumulative_history("1.5.3", 21, entries)

    def test_missing_current_changelog_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing notes"):
            manifest.cumulative_history("1.5.23", 41, [
                {"versionCode": 19, "versionName": "1.5.1", "notes": "[+] First."}
            ])

    def test_invalid_notes_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid release note"):
            manifest.cumulative_history("1.5.1", 19, [
                {"versionCode": 19, "versionName": "1.5.1",
                 "notes": "Unformatted notes are forbidden"}
            ])

    def test_untrusted_repository_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(SystemExit, "Unexpected repository"):
                self.generate(Path(folder), "untrusted/AX8PRO")

    def test_wrong_package_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(SystemExit, "Unexpected APK package"):
                self.generate(Path(folder), "INFEND/AX8PRO", package="example.invalid")


if __name__ == "__main__":
    unittest.main()
