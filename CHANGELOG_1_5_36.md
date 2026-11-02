# AX8 PRO CONTROL 1.5.36 (versionCode 54)

## Prevent stale LTE internet badge after PPP reconnect
- Invalidate the previous router-bound HTTPS probe whenever the AX8 WebUI reports a changed PPP session state.
- Apply invalidation before publishing both the fast initial status and the completed polling snapshot.
- Re-probe promptly after reconnection using the existing independent network worker and revision guard. An in-flight probe from the old PPP session cannot publish an outdated ONLINE result.
- No changes to router settings, firmware, Band, QoS, SMS, OTA identity, or signing material.

## Validation
- Add JVM checks for PPP disconnect/reconnect, unchanged states, and missing samples.
- Add source regression checks that both foreground status update paths invalidate before assigning new status.
- Android build, OTA history validation, signing and publication remain gated by GitHub Actions.
- Hardware verification on OLAX AX8 Pro and Android 17 / OriginOS 7 remains pending.
