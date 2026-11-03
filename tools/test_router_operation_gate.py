"""Router-side QoS and counter-chain writes must share one process-wide gate."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/"app/src/main/java/ru/xarkon/ax8"

class RouterOperationGateSourceTest(unittest.TestCase):
    def test_qos_transaction_owns_gate(self):
        text=(SRC/"QosController.java").read_text(encoding="utf-8")
        apply=text.split("synchronized void apply(",1)[1].split("synchronized void resyncAddresses(",1)[0]
        self.assertIn("try(RouterOperationGate.Lease ignored=RouterOperationGate.enter())",apply)
        self.assertIn("resetStock(interfaces);throw e;",apply)
        self.assertIn("try(RouterOperationGate.Lease ignored=RouterOperationGate.enter()){return shell.exec(cmd);}",text)
    def test_counter_install_and_removal_own_gate(self):
        text=(SRC/"DeviceTrafficMonitor.java").read_text(encoding="utf-8")
        sample=text.split("synchronized void sample(",1)[1].split("private static boolean validIp4(",1)[0]
        stop=text.split("synchronized void stop(){",1)[1].split("@Override public synchronized void close()",1)[0]
        self.assertIn("try(RouterOperationGate.Lease gateLease=RouterOperationGate.enter())",sample)
        self.assertIn("try(RouterOperationGate.Lease ignored=RouterOperationGate.enter())",stop)

if __name__=="__main__":unittest.main()
