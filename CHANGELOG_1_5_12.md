# AX8 PRO CONTROL v1.5.12 (30)

## Stop/background lifecycle
- Prevent a queued background poll from acquiring CPU/Wi-Fi wake locks after the service has stopped.
- Abort subsequent router requests at safe boundaries when a stop is requested; do not continue an entire polling sequence after shutdown.
- Release locks in the polling worker's final cleanup if shutdown occurred, and do not adjust adaptive polling preferences after shutdown.
- Avoid publishing a stale background snapshot or issuing an SMS notification from a poll after the service was stopped.

## Adaptive polling robustness
- Clamp out-of-range refresh settings before integer rounding to prevent overflow from making extremely large values turn into the fastest polling interval.
- Bound extreme measured request durations before adaptive calculations to prevent integer overflow.
- Add four regression assertions for extreme inputs and upper-bound rounding.

No router firmware, Band settings, or network configuration is modified. This change requires a successful CI build, tests, official signing, and OTA publication; behavior on a physical AX8/Android 17 remains unverified.
