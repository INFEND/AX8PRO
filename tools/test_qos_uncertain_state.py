"""Regression tests: never trust stale QoS fingerprints after failed/empty transactions."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
Q=(ROOT/"app/src/main/java/ru/xarkon/ax8/QosController.java").read_text(encoding="utf-8")
P=(ROOT/"app/src/main/java/ru/xarkon/ax8/QosResyncPolicy.java").read_text(encoding="utf-8")

class QosUncertainStateTest(unittest.TestCase):
    def test_empty_success_clears_signature(self):
        body=Q.split("private void rememberApplied(",1)[1].split("synchronized void resyncAddresses(",1)[0]
        self.assertIn("resyncPolicy.onSuccess(signature(active));",body)
        self.assertNotIn("if(!active.isEmpty())",body)

    def test_failed_transactions_invalidate_trust(self):
        for start,end in [
            ("synchronized void setLimit(","synchronized void restore("),
            ("synchronized void applyPriority(","synchronized boolean priorityActive("),
            ("synchronized void limitEveryoneExcept(","private void enrichIpv6Neighbors("),
            ("synchronized void resyncAddresses(","synchronized String audit("),
        ]:
            body=Q.split(start,1)[1].split(end,1)[0]
            self.assertIn("resyncPolicy.invalidate()",body,start)

    def test_rollback_only_remembered_after_success(self):
        for start,end in [
            ("synchronized void setLimit(","synchronized void restore("),
            ("synchronized void applyPriority(","synchronized boolean priorityActive("),
            ("synchronized void limitEveryoneExcept(","private void enrichIpv6Neighbors("),
        ]:
            body=Q.split(start,1)[1].split(end,1)[0]
            rollback=body.split("catch(Exception e)",1)[1]
            self.assertLess(rollback.index("apply(devices,"),rollback.index("rememberApplied(devices,"),start)

    def test_policy_invalidates_success_and_retry_throttle(self):
        body=P.split("void invalidate()",1)[1].split("void onAttempt(",1)[0]
        for marker in ['lastAppliedSignature = "";','pendingReducedSignature = "";','attempted = false;']:
            self.assertIn(marker,body)

if __name__=="__main__":unittest.main()
