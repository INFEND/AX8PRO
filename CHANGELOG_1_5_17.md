# AX8 PRO CONTROL 1.5.17 (35)

## QoS watchdog stability
- Use monotonic elapsed time for the four-second sustained-overspeed check and the 90-second repair cooldown, independent of Android wall-clock changes.
- Allow the first automatic repair after sustained overspeed without waiting for an arbitrary device uptime threshold.
- Keep the repair cooldown after failed attempts, preventing repeated tc rewrites on every polling cycle.
- Clear overspeed timers for clients that disappeared from the latest snapshot, so reconnecting devices must satisfy a fresh four-second window.
- Preserve existing 30% rate tolerance and QoS commands. No new router capabilities or Band operations are introduced.

## Validation
- Add 16 deterministic Java assertions covering thresholds, multiple clients, first repair, cooldown, disappearing clients and nanosecond counter wrap.
- The Android build, signing and release are validated by GitHub Actions; actual AX8/Android 17 behavior remains unverified until device testing.
