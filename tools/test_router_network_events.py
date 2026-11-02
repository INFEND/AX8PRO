"""Regression guard for LTE internet probes during Android network callback storms."""
from pathlib import Path
import unittest

MAIN=(Path(__file__).resolve().parents[1]/"app/src/main/java/ru/xarkon/ax8/MainActivity.java").read_text(encoding="utf-8")

class RouterNetworkEventsTest(unittest.TestCase):
    def test_duplicate_events_do_not_invalidate_internet(self):
        self.assertIn("if(signature.equals(internetNetworkSignature))return;",MAIN)
        self.assertIn("internetNetworkSignature=signature;",MAIN)
        self.assertLess(MAIN.index("if(signature.equals(internetNetworkSignature))return;"),MAIN.index("internetNetworkSignature=signature;"))
    def test_callback_storm_is_coalesced(self):
        self.assertIn("if(!running||internetChangeScheduled)return;",MAIN)
        self.assertIn("internetChangeScheduled=true;",MAIN)
        self.assertIn("handler.postDelayed(internetChange,450L);",MAIN)
        callback=MAIN.split("private final ConnectivityManager.NetworkCallback internetCallback=",1)[1].split("private final ArrayDeque",1)[0]
        self.assertNotIn("handler.removeCallbacks(internetChange)",callback)
    def test_physical_router_network_signature(self):
        for marker in ("network.getNetworkHandle()", "TRANSPORT_VPN", "TRANSPORT_WIFI",
                       "TRANSPORT_ETHERNET", "NET_CAPABILITY_VALIDATED", "getInterfaceName()",
                       "route.isDefaultRoute()", "getDnsServers()", "Collections.sort(links)",
                       "onLinkPropertiesChanged("):
            self.assertIn(marker,MAIN)
    def test_lifecycle_resets_callback_guard(self):
        self.assertIn("internetNetworkSignature=routerNetworkFingerprint();\n        watchRouterNetwork();",MAIN)
        self.assertIn("handler.removeCallbacks(internetChange);\n        internetChangeScheduled=false;",MAIN)

if __name__=="__main__":unittest.main()
