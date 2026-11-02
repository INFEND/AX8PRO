# AX8 PRO CONTROL 1.5.34 (versionCode 52)

## LTE internet availability status
- The top **ONLINE** badge continues to describe connectivity to the AX8 WebUI.
- The separate **LTE ONLINE** badge now requires a real HTTP 204 response from an external HTTPS service, bound specifically to the Wi-Fi/Ethernet network that reaches the configured router.
- A router-reported `ppp_connected` is a prerequisite but **never sufficient proof** of internet access. Cellular data, default network routing and VPN fallback cannot incorrectly turn the router's internet indicator green.
- A disconnected LTE session or failed external probes display LTE OFFLINE. Unknown, expired and untestable situations display a distinct neutral CHECKING or UNVERIFIED state.
- Two external no-content probes provide redundancy. All requests are asynchronous, time-bounded, foreground-only and throttled to at most every 15 seconds. The Android network callback accelerates status invalidation after connectivity changes.
- Use a monotonic clock for result expiry and prevent stale task responses from surviving a pause, network change or router address switch.
- The router API, network configuration commands, Band, firmware, QoS, SMS and OTA signing remain unchanged.

## Validation
- Add 18 deterministic Java policy assertions covering PPP-only status, fresh/stale verification, unavailable routes and polling cadence.
- Add static regression guards for network-bound probes and separation of router/WebUI vs internet status.
- Android compilation, signing, manifest validation and release publication are gated by GitHub Actions.
- End-to-end behavior on OLAX AX8 Pro and OriginOS 7 / Android 17 has not yet been tested.
