"""Prevent a PPP-only or phone-mobile internet shortcut from returning in the LTE badge."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/"app/src/main/java/ru/xarkon/ax8"

class InternetBadgeSourceTests(unittest.TestCase):
    def test_uses_router_bound_external_check(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        self.assertIn("network.openConnection",source)
        self.assertIn("network.getSocketFactory()",source)
        self.assertIn("TRANSPORT_WIFI",source)
        self.assertIn("TRANSPORT_ETHERNET",source)
        self.assertIn("getResponseCode()==204",source)
        self.assertNotIn("TRANSPORT_CELLULAR",source)
    def test_excludes_vpn_even_when_it_advertises_wifi(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        self.assertIn("!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)",source)
        self.assertEqual(source.count("isRouterLink("),3,
                         "Both initial selection and revalidation must reject VPN")
    def test_checks_all_reachable_router_links_before_declaring_offline(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        self.assertIn("Network offlineCandidate=null;",source)
        self.assertIn("if(offlineCandidate==null)offlineCandidate=network;",source)
        self.assertIn("if(offlineCandidate!=null)",source)
        self.assertIn("return new Result(InternetStatusPolicy.OFFLINE,offlineCandidate);",source)
        self.assertNotIn("return new Result(InternetStatusPolicy.OFFLINE,network);",source)
    def test_physical_router_gateway_fallback(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        self.assertIn("isRouterGateway(cm,network,host)",source)
        self.assertIn("route.isDefaultRoute()",source)
        self.assertIn("host.equalsIgnoreCase(gateway.getHostAddress())",source)
        self.assertIn("if(!canReachRouter(network,host,port) && !isRouterGateway(cm,network,host))",source)
        self.assertIn("network.openConnection",source)

    def test_android_validated_wifi_fallback_never_uses_phone_vpn(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        self.assertIn("NET_CAPABILITY_VALIDATED",source)
        self.assertIn("Network androidValidatedCandidate=null;",source)
        self.assertIn("if(androidValidatedCandidate!=null)",source)
        self.assertIn("!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)",source)
        self.assertIn("TRANSPORT_WIFI",source)
        self.assertNotIn("TRANSPORT_CELLULAR",source)
        self.assertLess(source.index("if(!canReachRouter("),
                        source.index("if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)"))
        self.assertIn("connectivitycheck.gstatic.com/generate_204",source)

    def test_ppp_transitions_invalidate_external_probe(self):
        main=(SRC/"MainActivity.java").read_text(encoding="utf-8")
        policy=(SRC/"InternetStatusPolicy.java").read_text(encoding="utf-8")
        self.assertIn("InternetStatusPolicy.pppChanged(",main)
        self.assertIn("invalidateInternetOnPppChange(early);",main)
        self.assertIn("invalidateInternetOnPppChange(status2);",main)
        self.assertLess(main.index("invalidateInternetOnPppChange(early);"),main.index("status=early;"))
        self.assertLess(main.index("invalidateInternetOnPppChange(status2);"),main.index("status=status2;"))
        self.assertIn("if(status!=null && next!=null",main)
        self.assertIn("!previous.equals(current)",policy)
    def test_badges_use_independent_policy(self):
        main=(SRC/"MainActivity.java").read_text(encoding="utf-8")
        self.assertIn('else label=online?"●  ONLINE"',main)
        self.assertIn("InternetStatusPolicy.label(internetState)",main)
        self.assertIn("RouterInternetProbe.check(getApplicationContext(),target)",main)
        self.assertNotIn('setLive("ppp",connected?',main)
        self.assertIn("internetWorker.execute",main)
    def test_policy_has_nonpositive_states(self):
        policy=(SRC/"InternetStatusPolicy.java").read_text(encoding="utf-8")
        self.assertIn("if(!pppConnected)return OFFLINE",policy)
        self.assertIn("if (!isFresh(checkedAt,now)) return UNKNOWN;",policy)
        self.assertIn("result==ONLINE||result==OFFLINE||result==UNAVAILABLE ? result : UNKNOWN",policy)
        self.assertIn("PROBE_INTERVAL_MS=15000L",policy)
if __name__=="__main__":
    unittest.main()
