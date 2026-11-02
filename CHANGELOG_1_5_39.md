# AX8 PRO CONTROL 1.5.39 (versionCode 57)

## Prevent expired client, SMS and ACL snapshots from looking live
- On foreground resume, discard client, SMS and block-list snapshots that have exceeded the bounded freshness window; reject future-dated SMS cache entries.
- Re-check snapshot freshness during periodic ticks and after WebUI poll completion, including when individual sections fail.
- Display unknown counts as “—” instead of zero while awaiting a fresh client or SMS response. Keep legitimate empty responses at zero.
- Expire the unread-SMS navigation badge when the background sample is too old.
- Never feed the previous device list to traffic sampling, owner priority or QoS watchdog after a failed client fetch.
- Preserve SMS notification deduplication history, OTA identity, signing key, and all router command APIs. No Band or firmware writes added.

## Validation
- Add six regression guards for resume, expiry, unknown counts, badge freshness, QoS safety and ACL timestamps.
- Existing Java policy tests already cover boundary, missing and future timestamps.
- CI must pass Java/Python tests, Android compilation, signing and OTA publication before declaring the release ready.
- Real AX8 and Android 17 / OriginOS 7 testing remains outstanding.
