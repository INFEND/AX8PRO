# AX8 PRO CONTROL 1.5.40 (versionCode 58)

## Guard foreground router work during background handoff
- Skip remaining status/client/SMS/ACL reads when a foreground poll is invalidated by pause/resume.
- Gate traffic sampling, QoS address resynchronization, owner priority updates and the QoS watchdog on the active foreground session and visible UI state.
- Make refresh generation visible to the WebUI worker thread with a volatile field; continue to reject stale UI callbacks and replay a deferred forced refresh.
- Avoid changing router Band, firmware, SMS, or saved QoS limits. Operations already in progress are not forcibly interrupted.

## Validation
- Extend Python regression checks for stale-cycle read suppression and router-side QoS gating.
- Keep the existing Java refresh-session tests and add a generation invalidation assertion.
- CI must pass Java/Python tests, Android compilation, signing and OTA publication before declaring a release ready.
- Hardware validation on OLAX AX8 Pro and Android 17 / OriginOS 7 remains outstanding.
