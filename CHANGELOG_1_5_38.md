# AX8 PRO CONTROL 1.5.38 (versionCode 56)

## Resume refresh is no longer lost behind an in-flight worker
- When the Activity resumes while an earlier WebUI request is still in progress, queue one forced refresh rather than silently dropping it.
- Discard responses from an invalidated foreground session and immediately run the deferred request once the worker becomes free.
- Coalesce multiple forced requests, without concurrent router polls or changing the selected polling interval.
- Clear deferred requests on pause so an old UI session cannot trigger extra network activity.
- Preserve existing router API, LTE probe, Band Lab, OTA signature, package identity and all settings.

## Validation
- Extend pure-Java RefreshSession tests to cover coalescing, lifecycle invalidation and exactly-once consumption.
- Add Python source integration guards for foreground wiring.
- CI gates Java tests, Python tests, Android build, signing and OTA publication.
- Real AX8 hardware and Android 17 / OriginOS 7 behavior are not yet verified.
