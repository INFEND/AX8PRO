# AX8 PRO CONTROL 1.5.14 (versionCode 32)

## Manual polling interval safety

- Fix an integer overflow in the activity's manual refresh scheduling path when persisted `refresh_ms` is near `Integer.MAX_VALUE`.
- Pass the stored interval directly to the already overflow-safe `AutoPollController.clamp()` instead of adding 50 before clamping.
- Keep the existing 0.5–5 second slider behavior and automatic cadence unchanged.
- Add a JVM boundary test and a CI regression guard for the Android callsite.

No router firmware, Band, or other hardware-changing commands were modified. Hardware testing on OLAX AX8 Pro and Android 17 is still required.
