# AX8 PRO CONTROL 1.5.6

## Background snapshot freshness

- Client, SMS and access-control results now carry their own successful-cycle markers.
- Restoring an LTE snapshot cannot incorrectly mark failed or outdated client/SMS/ACL requests as fresh.
- Absent data is discarded from the displayed background snapshot until a new read succeeds.
- Added five Java regression assertions for timestamp freshness boundaries.
- No router write operations, signing keys, package IDs or OTA endpoints were changed.

Android CI and physical-router verification are required to confirm behaviour.
