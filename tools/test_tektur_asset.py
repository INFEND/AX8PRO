"""Check the exact bundled font from the pinned source before Android compilation."""
import hashlib
import importlib.util
import pathlib
import tempfile
import unittest
import urllib.request

ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("prepare_tektur",ROOT/"scripts/prepare_tektur.py")
font=importlib.util.module_from_spec(spec)
spec.loader.exec_module(font)

class TekturArchiveTest(unittest.TestCase):
    def test_exact_font_reconstruction(self):
        self.assertEqual(hashlib.sha256(font.load_exact_font()).hexdigest(),font.EXPECTED)
