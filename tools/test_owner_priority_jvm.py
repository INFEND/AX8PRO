"""Executable JVM regression tests for OwnerPriorityController without Android or a router."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/ru/xarkon/ax8/OwnerPriorityController.java"

STUBS = r"""
package ru.xarkon.ax8;
import java.util.*;
class RouterClient {
    static class Device {
        String mac;
        Device(String mac) { this.mac = mac; }
    }
}
class DeviceTrafficMonitor {
    static class Rate { double dl, ul; }
    Rate get(String mac) { return null; }
}
class QosController {
    boolean fail;
    int calls;
    Integer applied;
    interface Progress { void accept(String message); }
    void applyPriority(String owner, Integer wanted, List<RouterClient.Device> devices,
                       Progress progress) throws Exception {
        calls++;
        if (fail) throw new Exception("simulated Telnet failure");
        applied = wanted;
    }
}
"""

HARNESS = r"""
package ru.xarkon.ax8;
import java.lang.reflect.*;
import java.util.*;
public class OwnerPriorityHarness {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void apply(OwnerPriorityController controller, Integer value, QosController qos)
            throws Exception {
        Method method = OwnerPriorityController.class.getDeclaredMethod(
            "apply", Integer.class, List.class, QosController.class);
        method.setAccessible(true);
        try {
            method.invoke(controller, value, Collections.emptyList(), qos);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
    public static void main(String[] args) throws Exception {
        OwnerPriorityController controller = new OwnerPriorityController();
        QosController qos = new QosController();
        controller.enable("AA:BB:CC:DD:EE:FF");
        qos.fail = true;
        boolean threw = false;
        try { apply(controller, 500, qos); }
        catch (Exception expected) { threw = true; }
        check(threw, "failed router operation must propagate");
        check(controller.cap == null, "failed router operation must not commit cap");
        qos.fail = false;
        apply(controller, 500, qos);
        check(Integer.valueOf(500).equals(controller.cap), "successful apply commits cap");
        check(Integer.valueOf(500).equals(qos.applied), "router receives cap");
        int calls = qos.calls;
        apply(controller, 500, qos);
        check(qos.calls == calls, "unchanged cap must not trigger another router command");
        qos.fail = true;
        threw = false;
        try { apply(controller, null, qos); }
        catch (Exception expected) { threw = true; }
        check(threw && Integer.valueOf(500).equals(controller.cap),
              "failed cap removal must preserve tracked cap");
        qos.fail = false;
        apply(controller, null, qos);
        check(controller.cap == null && qos.applied == null,
              "successful cap removal clears tracked cap");
        // Exercise the real update() path, not only the private apply() helper.
        // A failed refresh must preserve the previously committed cap.
        RouterClient.Device owner = new RouterClient.Device("AA:BB:CC:DD:EE:FF");
        apply(controller, 500, qos);
        qos.fail = true;
        Field lastEval = OwnerPriorityController.class.getDeclaredField("lastEval");
        lastEval.setAccessible(true);
        lastEval.setLong(controller, 0L);
        threw = false;
        try {
            controller.update(Collections.singletonList(owner),
                              new DeviceTrafficMonitor(), qos);
        } catch (Exception expected) { threw = true; }
        check(threw, "update must propagate failed cap refresh");
        check(Integer.valueOf(500).equals(controller.cap),
              "failed update must preserve committed cap");
        qos.fail = false;
        lastEval.setLong(controller, 0L);
        controller.update(Collections.singletonList(owner),
                          new DeviceTrafficMonitor(), qos);
        check(Integer.valueOf(500).equals(controller.cap),
              "successful update must preserve active cap during idle grace period");
        // Owner departure must remove the temporary priority cap.
        lastEval.setLong(controller, 0L);
        int beforeDeparture = qos.calls;
        controller.update(Collections.emptyList(), new DeviceTrafficMonitor(), qos);
        check(controller.cap == null && qos.applied == null,
              "owner departure must remove the temporary cap");
        check(qos.calls == beforeDeparture + 2,
              "owner departure must refresh the cap then remove it");
        check("ХОЗЯИН ВНЕ СЕТИ".equals(controller.mode),
              "owner departure must expose disconnected state");
        controller.disable();
        check(!controller.enabled && controller.cap == null, "disable clears state");
        System.out.println("OwnerPriorityController JVM regression: PASS");
    }
}
"""

class OwnerPriorityJvmTest(unittest.TestCase):
    def test_real_java_cap_commit_and_exception_paths(self):
        javac = shutil.which("javac")
        java = shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK unavailable; Android CI should run this test with a JDK")
        with tempfile.TemporaryDirectory(prefix="ax8-owner-qos-") as tmp:
            folder = Path(tmp) / "ru/xarkon/ax8"
            folder.mkdir(parents=True)
            (folder / "OwnerPriorityController.java").write_text(
                SOURCE.read_text(encoding="utf-8"), encoding="utf-8")
            (folder / "RouterStubs.java").write_text(STUBS, encoding="utf-8")
            (folder / "OwnerPriorityHarness.java").write_text(HARNESS, encoding="utf-8")
            subprocess.run([javac, "-encoding", "UTF-8", "-d", tmp,
                            *map(str, folder.glob("*.java"))],
                           check=True, capture_output=True, text=True, timeout=45)
            subprocess.run([java, "-cp", tmp, "ru.xarkon.ax8.OwnerPriorityHarness"],
                           check=True, capture_output=True, text=True, timeout=15)

if __name__ == "__main__":
    unittest.main()
