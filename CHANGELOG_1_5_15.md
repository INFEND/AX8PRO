# AX8 PRO CONTROL 1.5.15 (versionCode 33)

## Telnet monitoring stability

- Replace wall-clock-based Telnet command deadlines with monotonic nanosecond deadlines. Android time changes no longer extend or prematurely end a pending Telnet command.
- Limit accumulated Telnet responses to 2,000,000 characters. A malfunctioning or excessively verbose shell can no longer grow the in-memory response without bound.
- Preserve existing Telnet connection, command and timeout values, QoS behavior, Android package identity and OTA signing scheme.
- Add 12 JVM regression checks for timeout boundaries, nanosecond counter wraparound, normal output and oversized responses.

No router firmware or Band commands were changed. Real OLAX AX8 Pro and Android 17 testing remains pending.
