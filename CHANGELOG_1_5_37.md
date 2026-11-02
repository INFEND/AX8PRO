# AX8 PRO CONTROL 1.5.37 (versionCode 55)

## Prevent repeated LTE internet probes from Android network callback storms
- Coalesce Wi-Fi/Ethernet callback bursts into a bounded 450 ms check; repeated callbacks cannot postpone the check indefinitely.
- Compare physical-network fingerprints (network handle, validation, interface, gateway and DNS) before invalidating a verified internet result.
- Ignore VPN and duplicate initial callback events, but respond to route/DNS changes with onLinkPropertiesChanged.
- Reset callback state when the Activity pauses. No router writes, Band, firmware, QoS, SMS or signing changes.

## Validation
- Add source regression checks for event coalescing, fingerprint comparison, VPN filtering and lifecycle.
- Android compilation, JVM and Python tests, signing and OTA publication are gated by GitHub Actions.
- Physical AX8 and Android 17 / OriginOS 7 behavior remains unverified.
