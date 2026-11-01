# AX8 PRO CONTROL

Android control center for OLAX AX8 Pro. The application sources are maintained directly in this repository, under `app/`.

## Build, signing and OTA

The release workflow (`.github/workflows/ota-release.yml`) runs on changes to Android sources, OTA scripts and workflow configuration. It:

1. Tests automatic monitoring and OTA manifest generation.
2. Builds the release APK with Gradle and Android SDK.
3. Signs the APK using the existing `AX8PRO_SIGNING_BUNDLE` repository secret and checks the certificate pinned in `UpdateManager.java`.
4. Generates `update.json` from the signed APK, including version, SHA-256 and size.
5. Uploads the APK and manifest as Actions artifacts and publishes them to Releases.

**Current canonical repository:** https://github.com/INFEND/AX8PRO

**OTA manifest:** https://github.com/INFEND/AX8PRO/releases/latest/download/update.json

**APK:** https://github.com/INFEND/AX8PRO/releases/latest/download/AX8PRO.apk

For compatibility with installed pre-rename clients, generated manifests continue to use the legacy `ANTIFERMENT/AX8PRO` APK URL. GitHub redirects that path to the canonical repository. The Android updater accepts both legacy and canonical release paths.

**Package:** `ru.xarkon.ax8pro`. Do not change its application ID, signing certificate, or OTA trust pin when releasing an update. Every new application release requires a higher `versionCode` and an updated `versionName`.

## Release status

Check [Releases](https://github.com/INFEND/AX8PRO/releases) for the latest **published** version and [Actions](https://github.com/INFEND/AX8PRO/actions) for build, signing, test and publication status. A passing APK build alone does not mean a release was published. Never commit private signing material.
