# AX8 PRO CONTROL 1.5.43 (versionCode 61)

## Safe router-operation lease ownership
- Reject attempts to close a QoS/Telnet transaction lease from a different thread.
- Preserve the original thread's ability to release its lease after an invalid close attempt.
- Keep nested transaction locking and idempotent close behavior.
- Do not change router commands, saved QoS limits, Band Lab, application ID, or signing identity.

## Validation
- Extend Java concurrency tests with cross-thread close rejection and recovery.
- Require Android compilation, CI, signing and OTA publication before declaring release ready.
- Physical AX8 and Android 17 / OriginOS 7 validation remains pending.
