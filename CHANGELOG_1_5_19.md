# AX8 PRO CONTROL 1.5.19 (37)

## Guard QoS from incomplete client snapshots
- Build automatic MAC/IP reconciliation signatures only from currently eligible capped Wi-Fi clients with usable IP addresses.
- Do not automatically rewrite tc/iptables rules when a router snapshot contains no eligible capped client. Explicit user-initiated QoS actions are unchanged.
- If a newly returned client list omits a MAC from the last successfully applied QoS snapshot, require the same reduced signature to remain stable for at least five monotonic seconds before applying. This prevents a single transient partial station list from removing another client's rules.
- Cancel pending removal when the missing client reappears or the candidate signature changes; unchanged/full snapshots retain normal immediate behavior subject to the existing 30-second retry limit.
- No Band or router firmware commands were changed.

## Validation
- Extend the deterministic QoS resync regression suite from 16 to 36 assertions, including partial snapshots, transient omissions, reconnects, changed IPs, timer wrap and cooldown.
- CI compiles the Android app, runs Java and OTA tests, verifies the release signature and publishes OTA only on successful workflow completion.
- Real OLAX AX8 Pro and Android 17 behavior remains unverified.
