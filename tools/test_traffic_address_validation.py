"""Exercise the actual IP validators used for router counter commands."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/ru/xarkon/ax8/DeviceTrafficMonitor.java"


def extract_method(source, name):
    match = re.search(r"private static boolean " + name + r"\(String s\)\s*\{", source)
    if not match:
        raise AssertionError(f"Missing validator {name}")
    depth = 1
    index = match.end()
    while depth and index < len(source):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
        index += 1
    if depth:
        raise AssertionError(f"Unclosed validator {name}")
    return source[match.start():index]


class TrafficAddressValidationTest(unittest.TestCase):
    def test_validators_reject_malformed_addresses(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.fail("Java 17 toolchain is required for address validation tests")
        source = SOURCE.read_text(encoding="utf-8")
        methods = "\n".join(extract_method(source, name) for name in ("validIp4", "validIp6"))
        harness = """
public class TrafficAddressValidationHarness {
%s
private static void check(boolean expected, boolean actual, String address) {
    if (expected != actual) throw new AssertionError(address + ": expected " + expected + ", got " + actual);
}
public static void main(String[] args) {
    check(true, validIp4("192.168.8.1"), "192.168.8.1");
    check(true, validIp4("0.0.0.0"), "0.0.0.0");
    check(true, validIp4("255.255.255.255"), "255.255.255.255");
    check(false, validIp4("999.999.999.999"), "999.999.999.999");
    check(false, validIp4("192.168.8"), "192.168.8");
    check(false, validIp4("192.168.8.256"), "192.168.8.256");
    check(false, validIp4("192.168.8.1;reboot"), "shell injection");
    check(false, validIp4("192..8.1"), "empty octet");
    check(true, validIp6("2001:db8::1"), "2001:db8::1");
    check(true, validIp6("fe80::abcd"), "fe80::abcd");
    check(false, validIp6("::::"), "invalid compression");
    check(false, validIp6("2001:db8:::1"), "triple colon");
    check(false, validIp6("2001:db8::1;reboot"), "shell injection v6");
    check(false, validIp6("192.168.8.1"), "IPv4 passed as IPv6");
}
}
""" % methods
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "TrafficAddressValidationHarness.java"
            file.write_text(harness, encoding="utf-8")
            subprocess.run(["javac", str(file)], check=True, capture_output=True, text=True)
            subprocess.run(["java", "-cp", tmp, "TrafficAddressValidationHarness"],
                           check=True, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
