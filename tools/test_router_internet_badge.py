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
        self.assertEqual(source.count("isRouterLink("),4,
                         "Both initial selection and revalidation must reject VPN")
    def test_checks_all_reachable_router_links_before_declaring_offline(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        self.assertIn("Network offlineCandidate=null;",source)
        self.assertIn("if(offlineCandidate==null && networkStillPresent(context,network))offlineCandidate=network;",source)
        self.assertIn("if(offlineCandidate!=null && networkStillPresent(context,offlineCandidate))",source)
        self.assertIn("return new Result(InternetStatusPolicy.OFFLINE,offlineCandidate);",source)
        self.assertNotIn("return new Result(InternetStatusPolicy.OFFLINE,network);",source)
    def test_rechecks_link_after_blocking_operations(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        after_socket=source.split("!canReachRouter(network,host,port))",1)[1]
        self.assertLess(after_socket.index("caps=cm.getNetworkCapabilities(network);"),
                        after_socket.index("NET_CAPABILITY_VALIDATED"))
        self.assertIn("if(!isRouterLink(caps))continue;",after_socket)
        self.assertIn("if(!networkStillPresent(context,network))break;",source)
        self.assertIn("reachesInternet(network,endpoint) && networkStillPresent(context,network)",source)

    def test_physical_router_gateway_fallback(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        self.assertIn("isRouterGateway(cm,network,host)",source)
        self.assertIn("route.isDefaultRoute()",source)
        self.assertIn("host.equalsIgnoreCase(gateway.getHostAddress())",source)
        self.assertIn("if(!isRouterGateway(cm,network,host) && !canReachRouter(network,host,port))",source)
        self.assertIn("network.openConnection",source)

    def test_android_validated_wifi_fallback_never_uses_phone_vpn(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        self.assertIn("NET_CAPABILITY_VALIDATED",source)
        self.assertIn("if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))",source)
        self.assertIn("return new Result(InternetStatusPolicy.ONLINE,network);",source)
        self.assertLess(source.index("if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))"),
                        source.index("for(String endpoint:ENDPOINTS)"))
        self.assertIn("!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)",source)
        self.assertIn("TRANSPORT_WIFI",source)
        self.assertNotIn("TRANSPORT_CELLULAR",source)
        self.assertLess(source.index("if(!isRouterGateway(cm,network,host) && !canReachRouter(network,host,port))"),
                        source.index("if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)"))
        self.assertIn("connectivitycheck.gstatic.com/generate_204",source)

    def test_external_probe_is_bounded_and_does_not_follow_redirects(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        # Redirects (including captive portals) are not proof of Internet.
        self.assertIn('connection.setInstanceFollowRedirects(false);',source)
        self.assertIn('connection.setUseCaches(false);',source)
        self.assertIn('connection.setRequestProperty("Cache-Control","no-cache");',source)
        self.assertIn('return connection.getResponseCode()==204;',source)
        # Keep requests bounded on poor LTE without silently falling back to
        # the phone's default cellular connection.
        self.assertIn('connection.setConnectTimeout(2500);',source)
        self.assertIn('connection.setReadTimeout(2500);',source)
        self.assertIn('network.openConnection(new URL(endpoint))',source)
        self.assertIn('finally{if(connection!=null)connection.disconnect();}',source)

    def test_gateway_shortcut_precedes_router_socket_probe(self):
        source=(SRC/"RouterInternetProbe.java").read_text(encoding="utf-8")
        # A matching AX8 gateway already proves the local link, so avoid a
        # potentially slow TCP timeout before checking Android's verdict.
        self.assertIn("if(!isRouterGateway(cm,network,host) && !canReachRouter(network,host,port))",source)
        self.assertLess(source.index("!isRouterGateway(cm,network,host) && !canReachRouter"),
                        source.index("NET_CAPABILITY_VALIDATED"))

    def test_network_fingerprint_rechecked_before_accepting_probe(self):
        main=(SRC/"MainActivity.java").read_text(encoding="utf-8")
        self.assertIn("final String networkSignature=routerNetworkFingerprint();",main)
        self.assertIn("!networkSignature.equals(routerNetworkFingerprint())",main)
        self.assertIn("resetInternetResult();updateInternetChip();",main)
        self.assertLess(main.index("!networkSignature.equals(routerNetworkFingerprint())"),
                        main.index("internetResult=result.state;"))

    def test_disappeared_probe_network_clears_badge_before_retry(self):
        main=(SRC/"MainActivity.java").read_text(encoding="utf-8")
        start=main.index("if(result.network!=null &&")
        end=main.index("internetResult=result.state;",start)
        branch=main[start:end]
        self.assertIn("!RouterInternetProbe.networkStillPresent(",branch)
        self.assertIn("resetInternetResult();updateInternetChip();maybeProbeInternet(true);return;",branch)

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
        self.assertIn('label=online?"●  ONLINE"',main)
        # A pending Band reboot must not replace the normal connectivity badge.
        connection=main.split("private void updateConnection(){",1)[1].split("private void draw(",1)[0]
        self.assertNotIn("bandTracker.isPending()",connection)
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
