"""Guard against silently reporting zero traffic when router accounting rules disappear."""
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/ru/xarkon/ax8/DeviceTrafficMonitor.java"

class MissingRulesTest(unittest.TestCase):
    def test_ui_status_is_visible_across_polling_threads(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn('private volatile String error="",note="Ожидание первого опроса";', source)
        self.assertIn("String error(){return error;}", source)
        self.assertIn("String status(){return note;}", source)

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

    def test_ipv4_octets_are_canonicalized_before_rule_install_and_rate_lookup(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("if(validIp4(d.ip))a4.add(normalizeIp4(d.ip));", source)
        self.assertIn("if(validIp4(d.ip))addresses.add(normalizeIp4(d.ip));", source)
        self.assertIn('Integer.parseInt(octets[0])', source)
        self.assertIn('Integer.parseInt(octets[3])', source)

    def test_ipv4_counter_output_is_normalized_before_matching(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("private void readCounters(", 1)[1].split("private void remove(", 1)[0]
        self.assertIn("if(validIp4(src))src=normalizeIp4(src);", block)
        self.assertIn("if(validIp4(dst))dst=normalizeIp4(dst);", block)
        self.assertLess(block.index("if(validIp4(src))src=normalizeIp4(src);"),
                        block.index("boolean isUpload=expected.contains(src)"))

    def test_ipv6_text_forms_are_normalized_for_matching(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("private void readCounters(", 1)[1].split("private void remove(", 1)[0]
        self.assertIn("if(validIp6(src))src=normalizeIp6(src);", block)
        self.assertIn("if(validIp6(dst))dst=normalizeIp6(dst);", block)
        self.assertIn("if((ipv6ReadFailed || active6==null) && hasValidIpv6(d.ipv6))", source)

    def test_aggregated_counter_overflow_rejects_sample(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("private void readCounters(", 1)[1].split("private void remove(", 1)[0]
        self.assertIn("Math.addExact(value[0],entry.getValue()[0])", block)
        self.assertIn("Math.addExact(value[1],entry.getValue()[1])", block)
        self.assertIn('throw new IOException("Переполнение счётчиков', block)
        self.assertNotIn("value[0]+=entry.getValue()[0]", block)

    def test_multi_address_client_totals_reject_overflow(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("synchronized void sample(", 1)[1].split("private static boolean validIp4(", 1)[0]
        self.assertIn("dl=Math.addExact(dl,n[0]);", block)
        self.assertIn("ul=Math.addExact(ul,n[1]);", block)
        self.assertIn('throw new IOException("Переполнение счётчиков клиента",e);', block)
        self.assertNotIn("dl+=n[0];", block)
        self.assertNotIn("ul+=n[1];", block)

    def test_duplicate_client_addresses_are_counted_once(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("synchronized void sample(", 1)[1].split("private static boolean validIp4(", 1)[0]
        self.assertIn("Set<String> addresses=new LinkedHashSet<>();", block)
        self.assertIn("addresses.add(normalizeIp6(ip));", block)
        self.assertLess(block.index("Set<String> addresses=new LinkedHashSet<>();"),
                        block.index("for(String ip:addresses)"))

    def test_partial_or_duplicate_rules_are_rejected_before_publish(self):
        source = SOURCE.read_text(encoding="utf-8")
        block = source.split("private void readCounters(", 1)[1].split("private void remove(", 1)[0]
        self.assertIn("if(!upload.equals(expected)||!download.equals(expected))", block)
        self.assertIn("if(!upload.add(src))throw new IOException", block)
        self.assertIn("if(!download.add(dst))throw new IOException", block)
        self.assertIn("Map<String,long[]> parsed=new HashMap<>();", block)
        self.assertLess(block.index("if(!upload.equals(expected)||!download.equals(expected))"),
                        block.index("for(Map.Entry<String,long[]> entry:parsed.entrySet())"))

if __name__ == "__main__":
    unittest.main()
