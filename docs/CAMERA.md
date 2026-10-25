# Camera viewer disconnects on "Hey Jarvis"

Investigation of the 10:13–10:14 Riyadh logcat (WebRTC CONNECTED → ice=DISCONNECTED → CLOSED → camera idle-stopped while Groq answered "no one is watching").

## What the log shows

1. The ICE drops (10:13:40) happened **before** the wake trigger (10:14:13), so the wake path did not cause the disconnect itself.
2. `PeerLiveness` already gives a DISCONNECTED peer a 10 s grace, then drops it (CLOSED). `CameraOnDemand` then sees zero viewers and stops the camera after the 45 s idle window ("camera service stopped" ≈ 40 s after the last viewer left). The stop is by design; the phone correctly reaped a dead session.
3. Groq answered "No one is currently viewing a live camera feed." from its own reasoning — there was no camera status tool for it, and the old viewer page had no reconnect, so every transient break ended as "camera gone".

## Findings

- (a) Wake/listen path: `WakeListener.pauseBlocking()` closes the mic recorder before SpeechRecognizer and never touches the camera service or WebRTC. `WebRtcPublisher` is video-only with no audio track, but the factory's default audio device module was left implicit. It is now built explicitly (`JavaAudioDeviceModule` with hardware AEC/NS disabled and released on dispose) so WebRTC can never wire a recorder or grab hardware audio effects from the phone's mic session (WebRtcPublisher.kt).
- (b) Viewer-less pruning: `ViewerIdleWatch` now takes a `departGraceMillis` (15 s for the WebRTC peer). After the last peer drops, the shelf is read as "still has a viewer" until the grace elapses, giving the 1–8 s reconnect backoff in the page time to recover before the on-demand idle stop (CameraOnDemand.kt, ViewerIdleWatch.kt).
- "Is anyone watching" is now answered by a **read-only** skill (`CameraStatusSkill`, id `camera_status`) that reports streaming state and the connected viewer count and never touches the camera. Groq/Gemini can call it instead of guessing and stopping the stream.
- The first ICE drop itself was not reproduced in code (no caller stops/rebinds the camera during wake). The viewer-side reconnection below covers it.

## camera_viewer.html auto-reconnect

- On `iceConnectionState` failed/closed, `connectionState` failed/closed, or no decoded frame for 5 s, the page keeps showing the last frame (a fresh `/snapshot.jpg` stands in), shows a small "reconnecting…" badge, and re-runs the `/offer` handshake with a fresh peer and 1 s, 2 s, 4 s, capped 8 s backoff. No page reload, no auth re-prompt (basic auth is already cached).
- Boot still connects immediately; a successful track hides the badge and resets the backoff.

## Tests

- `ViewerIdleWatchGraceTest`: grace delays the empty reading but leaves the 45 s idle cutoff unchanged.
- `CameraStatusSkillTest`: watching questions match in English/Arabic, control phrases never do, and `run` only reports.
