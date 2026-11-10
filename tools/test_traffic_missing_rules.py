"""Guard against silently reporting zero traffic when router accounting rules disappear."""
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/ru/xarkon/ax8/DeviceTrafficMonitor.java"

class MissingRulesTest(unittest.TestCase):
    def test_empty_chain_is_an_error(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("private void readCounters(", 1)[1].split("private void remove(", 1)[0]
        self.assertIn("int matchedRules=0;", block)
        self.assertIn("matchedRules++;", block)
        self.assertIn("if(matchedRules==0)throw new IOException", block)
        self.assertIn("if(output.code!=0)throw new IOException", block)

if __name__ == "__main__":
    unittest.main()
