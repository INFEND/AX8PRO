# AX8 PRO CONTROL 1.5.35 (versionCode 53)

## Accurate router-bound internet verification
- Explicitly reject VPN transport even when Android also reports an underlying Wi-Fi/Ethernet transport. The LTE indicator must never infer router internet access from a VPN tunnel.
- Check every reachable physical router network before concluding LTE OFFLINE. A second valid AX8-bound interface can now prove internet access even when the first one fails external HTTPS checks.
- Use the same physical-link validation when confirming that the network still exists after an asynchronous probe.
- No router settings, Band, firmware, QoS, SMS or signing configuration are changed.

## Validation
- Add source regression checks for VPN exclusion and multi-interface fallback.
- Android compilation, static regression tests, signing, manifest verification and release publication are gated by GitHub Actions.
- Live verification on OLAX AX8 Pro and Android 17 / OriginOS 7 remains pending.
