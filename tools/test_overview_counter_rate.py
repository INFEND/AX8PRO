"""Exercise the actual Java counter-rate helper without Android dependencies."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")

class OverviewCounterRateTest(unittest.TestCase):
    def test_counter_rate(self):
        helper = MAIN.split("    static double counterRate(", 1)[1].split("    private double rateValue(", 1)[0]
        source = "public class CounterRateTest { static double counterRate(" + helper + """
    public static void main(String[] args) {
        if (counterRate(300,100,2,true)!=100) throw new AssertionError("normal");
        if (counterRate(100,100,2,true)!=0) throw new AssertionError("idle");
        if (!Double.isNaN(counterRate(1,100,2,true))) throw new AssertionError("reset");
        if (!Double.isNaN(counterRate(Double.NaN,100,2,true))) throw new AssertionError("missing");
        if (!Double.isNaN(counterRate(100,Double.NaN,2,true))) throw new AssertionError("missing baseline");
        if (!Double.isNaN(counterRate(100,0,2,false))) throw new AssertionError("first");
        if (!Double.isNaN(counterRate(100,0,.1,true))) throw new AssertionError("short");
        if (!Double.isNaN(counterRate(100,-1,2,true))) throw new AssertionError("negative");
    }
}
"""
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "CounterRateTest.java"
            path.write_text(source, encoding="utf-8")
            subprocess.run(["javac", str(path)], check=True)
            subprocess.run(["java", "-cp", tmp, "CounterRateTest"], check=True)

    def test_each_direction_replaces_previous_rate(self):
        self.assertIn("fallbackDl=counterRate(rx,lastRx,sec,lastBytesAt>0);", MAIN)
        self.assertIn("fallbackUl=counterRate(tx,lastTx,sec,lastBytesAt>0);", MAIN)
        self.assertIn("lastRx=rx;lastTx=tx;lastBytesAt=counterTime;", MAIN)

if __name__ == "__main__":
    unittest.main()
