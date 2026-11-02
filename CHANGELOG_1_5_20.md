# AX8 PRO CONTROL 1.5.20 (38)

## Preserve IPv6-only QoS eligibility during automatic reconciliation
- Correct the new incomplete-snapshot guard so a capped Wi-Fi client without an IPv4 address is not discarded before the router's IPv6 neighbor table can supply its IPv6 address.
- Query the IPv6 neighbor table only when a saved Wi-Fi client lacks both an eligible IPv4 address and an IPv6 address in the station list. Normal IPv4 snapshots require no additional Telnet lookup.
- Continue to skip automatic tc/firewall rewrites if no capped client has a usable address after enrichment. Keep the five-second reduced-snapshot confirmation and 30-second retry cooldown from v1.5.19.
- No changes to user-initiated QoS commands, Band, or router firmware.

## Validation
- Compile Android Java/resources, run the existing 36 QoS resync assertions and other regression/OTA tests in CI.
- Verify pinned signing certificate, signed APK and OTA manifest before release publication.
- Actual IPv6-only OLAX AX8 Pro behavior and Android 17 remain unverified.
