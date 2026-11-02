# AX8 PRO CONTROL 1.5.13 (versionCode 31)

## Client snapshot integrity

- Do not treat a malformed station-list response as a valid empty client list.
- Accept empty arrays as genuine zero-client results; reject partial arrays and unrecognized online rows.
- Continue trying the other supported station-list endpoints when one endpoint responds with invalid data.
- Prevent incomplete responses from overwriting cached client snapshots or triggering QoS address reconciliation.
- Add eight JVM regression checks and run them in GitHub Actions.

The router firmware is unchanged. No band-selection or destructive operations are added.
Hardware behavior requires verification on an actual OLAX AX8 Pro.
