"""Regression guards for nonblocking AX8 controls and displayed unlimited speed."""
import pathlib
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] /
          "app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")


class InteractionRecoveryTests(unittest.TestCase):
    def test_unlimited_is_infinity(self):
        self.assertIn('if(kb==null)return "∞";', SOURCE)

    def test_temporary_operations_have_separate_worker(self):
        self.assertIn('temporaryWorker=Executors.newSingleThreadExecutor()', SOURCE)
        self.assertIn('(temporaryAction?temporaryWorker:workers).execute', SOURCE)
        self.assertIn('temporaryRouter().kick(dev.mac,p)', SOURCE)
        self.assertIn('RouterClient api=temporaryRouter()', SOURCE)

    def test_poll_and_ui_remain_active_during_timed_action(self):
        self.assertIn('if((actionBusy&&!temporaryAction)||!running)return;', SOURCE)
        self.assertIn('if(!actionBusy||temporaryAction)', SOURCE)
        self.assertIn('private void showTab(int index)', SOURCE)

    def test_no_blocking_band_verification_after_restart(self):
        self.assertNotIn('bandTracker.restore(', SOURCE)
        self.assertNotIn('bandTracker.isPending()', SOURCE)
        self.assertIn('clearPendingBand();', SOURCE)


if __name__ == "__main__":
    unittest.main()
