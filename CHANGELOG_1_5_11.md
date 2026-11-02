# AX8 PRO CONTROL v1.5.11 (29)

## Startup data freshness
- Only restore a saved LTE snapshot when it is at most 15 seconds old. Older cached readings must not look current while the app reconnects.
- Reject future-dated background snapshots (for example, after a device clock correction). They previously passed the age check and could be treated as current.
- When background data is expired, discard an expired in-memory LTE snapshot instead of retaining the old values until the next successful request.
- Extend the pure-Java freshness regression tests to cover startup expiry boundaries and clock skew.

## Background battery usage
- When the user turns off the CPU + Wi-Fi keep-awake setting, the background service now releases any locks it already holds on its next cycle. Previously, the toggle prevented new acquisitions but did not free locks held from an earlier cycle.

No router firmware or band settings are changed. Release requires successful GitHub Actions build, tests, signing and OTA publication. Hardware testing remains pending.
