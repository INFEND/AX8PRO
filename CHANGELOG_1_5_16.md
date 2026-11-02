# AX8 PRO CONTROL 1.5.16 (34)

## Network recovery and traffic monitoring
- Switched the 15-second traffic-counter retry cooldown from wall-clock timestamps to monotonic `System.nanoTime()`. Changing Android's date/time can no longer leave the traffic probe blocked for hours or cause an immediate retry storm.
- Reset the cooldown after successful counter initialization or an explicit monitor stop, preserving existing counter setup and QoS behavior.
- Added 13 pure-Java regression checks covering retry timing, repeated failures, reset, negative monotonic timestamps, signed-long wraparound, and invalid delays.

## Verification
- Pure-Java RetryBackoffTest passed locally (13 checks).
- Android compilation, complete regression suite, APK signing, and OTA publication are gated by the existing Actions workflow.
- No router firmware, LTE Band, or live QoS settings are changed by this release.
