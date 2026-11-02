# AX8 PRO CONTROL 1.5.24 (42)

## Foreground refresh lifecycle
- Prevent a slow foreground router poll from applying its LTE, clients, SMS, ACL and traffic results after the Activity has been paused or resumed.
- Invalidate pending UI callbacks on `onPause()`, including the early LTE status callback.
- Keep `refreshBusy` until the previous worker finishes, avoiding overlapping WebUI requests; the next scheduled poll can then fetch fresh data.
- Keep scroll position, manual/automatic refresh settings and existing router operations unchanged.

## Verification
- Added six standalone Java assertions covering active, paused, resumed and repeated invalidation scenarios.
- Included the new policy and test in the Android release workflow.
- No changes to router Band, firmware, package ID, OTA endpoint or pinned signing certificate.
- Android build, release signing and publication are validated by the existing CI pipeline. Real-device lifecycle behavior still requires confirmation.
