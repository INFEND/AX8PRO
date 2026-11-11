"""Guard against silently reporting zero traffic when router accounting rules disappear."""
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/ru/xarkon/ax8/DeviceTrafficMonitor.java"

class MissingRulesTest(unittest.TestCase):
    def test_empty_chain_is_an_error(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("private void readCounters(", 1)[1].split("private void remove(", 1)[0]
        self.assertIn("Set<String> expected=new HashSet<>();", block)
        self.assertIn("if(!upload.equals(expected)||!download.equals(expected))", block)
        self.assertIn("if(output.code!=0)throw new IOException", block)

    def test_missing_ipv6_list_is_safe_in_both_polling_paths(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("if(d.ipv6!=null)for(String ip:d.ipv6)if(validIp6(ip))a6.add(normalizeIp6(ip));", source)
        self.assertIn("if(d.ipv6!=null)for(String ip:d.ipv6)if(validIp6(ip))addresses.add(normalizeIp6(ip));", source)

    def test_ipv6_text_forms_are_normalized_for_matching(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("private void readCounters(", 1)[1].split("private void remove(", 1)[0]
        self.assertIn("if(validIp6(src))src=normalizeIp6(src);", block)
        self.assertIn("if(validIp6(dst))dst=normalizeIp6(dst);", block)
        self.assertIn("if((ipv6ReadFailed || active6==null) && hasValidIpv6(d.ipv6))", source)

    def test_partial_or_duplicate_rules_are_rejected_before_publish(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("private void readCounters(", 1)[1].split("private void remove(", 1)[0]
        self.assertIn("if(!upload.equals(expected)||!download.equals(expected))", block)
        self.assertIn("if(!upload.add(src))throw new IOException", block)
        self.assertIn("if(!download.add(dst))throw new IOException", block)
        self.assertIn("Map<String,long[]> parsed=new HashMap<>();", block)
        self.assertLess(block.index("if(!upload.equals(expected)||!download.equals(expected))",
                        block.index("for(Map.Entry<String,long[]> entry:parsed.entrySet())"))

if __name__ == "__main__":
    unittest.main()
