# AX8 PRO CONTROL 1.5.23 (41)

## OTA and Android installer
- The system installer is launched in its own task using an application-context `ACTION_VIEW` with `FLAG_ACTIVITY_NEW_TASK`, mirroring the working QR LINK installer-open mechanism without changing the trusted APK validation.
- Expose a read-only APK filename and size through the OTA provider for installer compatibility.
- Keep the launcher activity exported and singleTop. The system installer's **Open** action is the normal way to reopen the updated app.
- Remove post-install "Tap to open" notifications and their receiver registration; unrelated SMS and monitoring notifications are preserved.

## Cumulative changelog
- Show a scrollable, themed, formatted list of user-facing changes directly inside the OTA dialog.
- Reuse the same [+] green, [=] orange and [–] red visual markers and bold emphasis as the QR LINK updater.
- Include every published release since the installed `versionCode`, with validated gap-free history sourced from curated release notes for versions 1.5.1–1.5.23.
- Reject missing, duplicated, malformed or oversized history; show last-release notes rather than falsely claiming an incomplete cumulative list.
- Extend both Java and Python OTA regression tests.

## Validation and limitations
- App package ID, official signing certificate and the permanent OTA endpoint remain unchanged.
- No Band, firmware or router hardware commands are modified.
- Compile, signature validation and publication are gated by the existing GitHub Actions workflow.
- Testing the installer **Open** button on a real Android 17 / OriginOS device remains necessary.
