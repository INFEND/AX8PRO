"""Regression guard: successful explicit QoS writes must seed resync state."""
import pathlib
import unittest
src=(pathlib.Path(__file__).resolve().parents[1]/"app/src/main/java/ru/xarkon/ax8/QosController.java").read_text(encoding="utf-8")
class QosResyncAfterManualWriteTest(unittest.TestCase):
    def test_explicit_operations_record_applied_snapshot(self):
        for method,end in [("setLimit(","restore("),("restore(","private Map<String,Integer> merge("),("applyPriority(","priorityActive("),("limitEveryoneExcept(","enrichIpv6Neighbors(")]:
            part=src.split(method,1)[1].split(end,1)[0]
            self.assertIn("rememberApplied(devices,applied)",part,method)
    def test_resync_uses_same_signature(self):
        self.assertIn("String signature=signature(eligible);",src)
        self.assertIn("resyncPolicy.onSuccess(signature(active))",src)
        self.assertIn("if(!active.isEmpty())",src)
    def test_no_router_write_in_remember(self):
        part=src.split("private void rememberApplied(",1)[1].split("/** Reapply",1)[0]
        self.assertNotIn("apply(",part)
        self.assertNotIn("exec(",part)
if __name__=="__main__":unittest.main()
