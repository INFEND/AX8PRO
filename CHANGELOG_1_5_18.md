# AX8 PRO CONTROL 1.5.18 (36)

## QoS MAC/IP resynchronization reliability
- Replace wall-clock-based MAC/IP resync throttling with monotonic elapsed time, so changing Android date/time cannot indefinitely defer a needed DHCP address reconciliation.
- Preserve the existing 30-second cooldown between attempts and do not retry already successfully applied address signatures.
- Allow the first address reconciliation immediately, including shortly after device boot.
- Record attempts before applying router rules, so failures are rate-limited and cannot cause a rapid loop of tc/iptables rewrites.
- Mark a signature as applied only after successful completion, so failed attempts remain eligible for a later retry.
- Keep existing QoS commands and Band behavior unchanged; no router firmware or radio changes.

## Validation
- Add 16 deterministic Java assertions for empty signatures, first attempt, cooldown, success, failure, changed addresses, and monotonic-counter wrap.
- Add the new tests to the Android OTA CI workflow.
- Actual AX8 and Android 17 behavior remains unverified until device testing.
