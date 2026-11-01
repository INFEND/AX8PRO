"""Regression checks for the OTA manifest generator; no Android SDK needed."""
import hashlib
import importlib.util
import json
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
