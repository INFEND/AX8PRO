# AX8 PRO CONTROL 1.5.21 (39)

## Battery charge UI
- Show a compact battery silhouette with fill proportional to the AX8 router's reported charge level.
- Animate a subtle percentage-text gradient that changes continuously from red/orange to yellow/green as the battery level changes, with a high-contrast variant for light themes.
- Stop the shimmer when its view is detached/hidden or when motion or system power saving is disabled. No heavyweight images or external dependencies.
- Display neutral color and an empty outline when the router returns no valid charge reading; never invent 0% or 100%.
- Use `battery_vol_percent` for the real numeric percentage; `battery_pers` is only a coarse indicator (0..4), never an exact percentage.
- Remove the unrelated charging bolt from the percentage text. No battery hardware controls or router writes.
- Preserve the card layout, app id, signature, existing router commands and OTA flow.

## Validation
- Add pure Java battery normalization, color and fallback policy with 20 regression assertions.
- Execute battery tests in GitHub Actions before Android release build.
- Real-world Android 17/OriginOS and OLAX AX8 Pro visual testing remains pending.
