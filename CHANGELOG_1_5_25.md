# AX8 PRO CONTROL 1.5.25 (43)

## Safe router reconnection in background
- Detect WebUI host, username and password changes before each background cycle; replace stale RouterClient, QoS, traffic and priority controllers on the existing single worker.
- Best-effort cleanup of temporary QoS priority and traffic instrumentation on the previous endpoint before switching. Preserve an explicit warning in background diagnostics if cleanup fails.
- On router address changes, invalidate endpoint-scoped LTE, clients, SMS, ACL and throughput caches. Reset SMS deduplication baseline to avoid replaying alerts from a different router.
- Do not publish old-router SMS notifications, QoS writes or cached results if the visible UI or connection settings changed during an in-flight background poll.
- Keep manual/automatic polling, user-enabled wake-lock behavior, package identity, signing and OTA unchanged.

## Verification
- Nine standalone Java assertions for endpoint equivalence, credentials changes, host/protocol changes and secret-safe diagnostics.
- Release pipeline compiles and executes the new tests before Android build and signing.
- Device behavior and cleanup of live Telnet/QoS rules remain unverified without AX8 hardware.
