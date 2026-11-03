# AX8 PRO CONTROL 1.5.42 (versionCode 60)

## Router-side QoS transaction serialization
- Serialize complete tc/iptables QoS updates across foreground and background controllers.
- Serialize traffic-counter chain installation and removal with QoS rewrites.
- Use a fair, reentrant process-local gate; release it on exceptions and allow nested calls.
- Keep existing handoff checks, Band Lab isolation, saved limits, application ID and OTA signing identity.
- Existing in-flight Telnet commands are not forcibly cancelled. Other apps/processes are not locked.

## Validation
- Add concurrent Java lock tests and Python source-structure guards.
- Require Android compilation, CI, signing and OTA publication before declaring release ready.
- Physical AX8 and Android 17 / OriginOS 7 validation is still pending.
