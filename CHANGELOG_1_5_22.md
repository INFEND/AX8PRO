# AX8 PRO CONTROL 1.5.22 (40)

## Accurate AX8 battery percentage and invalid-data handling
- Prioritize the confirmed `Battery_voltage` percentage field used by the AX8 WebUI/diagnostic script. Keep the existing `battery_vol_percent` field as a fallback for other firmware variants.
- Preserve `battery_pers` only as a coarse pictogram-level fallback; never display it as an exact percent.
- Reject impossible readings above 100% instead of silently clamping them to a misleading full charge. If the preferred reading is invalid, try the legacy percent before showing unknown.
- Retain the animated level-dependent battery icon and percent gradient from v1.5.21; no router write operations, Band changes or firmware changes.

## Validation
- Extend the pure-Java battery policy suite from 20 to 26 assertions covering preferred/legacy values, fractional percentages, invalid input and exact zero.
- CI assembles the Android app, runs Java and OTA regression tests, verifies the pinned signing identity and publishes APK/manifest only after successful checks.
- Real AX8 Pro and Android 17 display testing remains pending.
