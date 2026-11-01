# AX8 PRO CONTROL v1.5.7 (versionCode 25)

## SMS notifications
- Notify separately for **every** newly received unread SMS when several messages arrive between background polling cycles.
- Preserve the first-scan baseline to avoid notifying for messages already on the SIM.
- Use a stable notification tag derived from the router SMS ID, so later messages no longer overwrite earlier notifications in the Android notification shade.
- De-duplicate repeated SMS IDs and ignore missing IDs.

## Verification
- Added eight pure Java regression cases for first scan, existing messages, batches, duplicates and malformed IDs.
- The Android release workflow compiles and runs these tests before building and publishing the signed APK.
- No router firmware, Band settings or network configuration changes.
- Actual delivery and notification permissions still require testing on a physical device.
