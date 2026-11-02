# AX8 PRO CONTROL 1.5.41 (versionCode 59)

## Foreground/background QoS handoff
- Wait for queued foreground Telnet and traffic cleanup before background monitoring starts.
- Count outstanding cleanups so quick pause/resume cycles cannot release another pending handoff.
- Recheck service ownership after slow client and traffic operations before starting more QoS writes.
- Preserve Band Lab isolation, saved limits, application ID and OTA signing identity.
- Existing in-flight Telnet commands cannot be forcibly cancelled.

## Validation
- Add Java cross-thread handoff tests and Python source guards.
- Android compilation, signing, CI and OTA publication must pass before declaring the release ready.
- Physical AX8 and Android 17 / OriginOS 7 verification remains outstanding.
