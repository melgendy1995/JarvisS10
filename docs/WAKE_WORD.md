# Hey Jarvis wake word

Hands-free listening uses the pre-trained openWakeWord `hey_jarvis` model on ONNX Runtime. Push-to-talk (hold the orb) stays as the backup.

## Licenses

| Piece | License |
| --- | --- |
| openWakeWord code | Apache-2.0 |
| Pre-trained models (`melspectrogram.onnx`, `embedding_model.onnx`, `hey_jarvis_v0.1.onnx`) | CC BY-NC-SA 4.0 |
| ONNX Runtime Android `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` | MIT |

The model files come from the official [v0.5.1 release](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1). Release v0.6.0 ships no model files; the library still downloads these URLs. The project README applies CC BY-NC-SA 4.0 to all included pre-trained models because training used datasets with unknown or restrictive licenses. Non-commercial use with attribution and the same license on adaptations. The same note is in `audio/src/main/assets/wake/NOTICE.txt`.

The speech-embedding architecture comes from Google's speech_embedding TFHub module (Apache-2.0). The ONNX file in that release is openWakeWord's re-implementation.

## Models in the APK

| File | Bytes |
| --- | --- |
| `melspectrogram.onnx` | 1,087,958 |
| `embedding_model.onnx` | 1,326,578 |
| `hey_jarvis_v0.1.onnx` | 1,271,370 |

The audio module sets `androidResources.noCompress` for `onnx`. The debug APK still deflates the three model files (they are read fully into memory) and stores the runtime libraries uncompressed. The app packages `arm64-v8a` only (Galaxy S10). ONNX Runtime 1.31.0 adds `libonnxruntime.so` (34,208,680 bytes) and `libonnxruntime4j_jni.so` (122,576 bytes). The debug APK grew from 37,117,645 bytes to 74,713,072 bytes (about 37.6 MB). That increase is the arm64 runtime plus these three models.

There is no plain "Jarvis" model in the official v0.5.1 assets (`jarvis.onnx` and `jarvis_v0.1.onnx` return 404). `WakeConfig.PLAIN_JARVIS_MODEL` is false. A separate check of the hey_jarvis model on macOS `say -v Daniel` audio scored "hey jarvis" at about 0.995 and the bare word "jarvis" at about 0.022, under the 0.35 threshold. Saying only "Jarvis" does not wake the phone.

## Pipeline

16 kHz mono int16. Frames are 80 ms (1280 samples). The mel network sees float32 values still in the int16 range. Its output is transformed with `x / 10 + 2`. Embeddings use 76-frame windows stepped by 8. The wake network sees the last 16 embeddings of size 96. The first five scores of a fresh stream are forced to 0. A frame fires when it is at or above 0.35, or when two of the last four frames are at or above 0.30. A hit then suppresses another hit for 2 s. Scores at or above 0.2 are logged under `JarvisWake` with the threshold. If the listener should be passive and no wake frames arrive for 3 s, it restarts capture (`wake watchdog restart`).

Streaming matches openWakeWord: the mel buffer starts as 76 frames of ones, each new 1280 samples are scored with 480 samples of mel context, and the feature buffer is primed with four seconds of low-level noise.

## Foreground service

`ListenForegroundService` is `foregroundServiceType="microphone"` with `FOREGROUND_SERVICE_MICROPHONE` and a low-importance ongoing notification ("Listening for Hey Jarvis"). `startForeground` runs before the models load. The activity starts it from `ON_RESUME` and again before opening another app, which is the Android 12 rule: a microphone foreground service has to be started while the app is visible. It is sticky, but a process death in the background may not be allowed to record until Jarvis is opened again. `onResume` starts it when the screen is back.

The recorder is `VOICE_RECOGNITION`, with `MIC` as the fallback. `VOICE_COMMUNICATION` is not used: it switches the audio mode and can pause YouTube, and assistant TTS does not route as a call. AcousticEchoCanceler and NoiseSuppressor are attached when the platform allows and the result is logged. Passive listening does not request audio focus.

Returning from YouTube, or a "back" command, leaves this service running.

## After a wake

1. The wake recorder is released so `SpeechRecognizer` can take the microphone.
2. If media is actually playing, Jarvis requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` on the assistant stream. The focus is held through the answer and the follow-up window, then released.
3. A short generated chime plays (assistant usage, about 180 ms, then a short wait) before recognition starts, so the recognizer does not hear it.
4. The orb shows listening. End of speech is the recognizer's RMS plus its silence extras. Silero VAD is not bundled: it wants PCM from our recorder, and a second recorder silences recognition on this phone.
5. The text goes through the existing brain chain and is spoken with assistant TTS.
6. An empty result after a wake counts as a false trigger (`falseTrigger` in the log). The line "I didn't hear anything." is the same idle copy as push-to-talk.

## Follow-up

After a hands-free answer, Jarvis listens for about 7 seconds (`WakeConfig.FOLLOW_UP_MS`) with no wake word. That window is time-to-start-speech. If the recognizer returns with no speech before the window ends, it is started again until the 7 seconds are up. If speech has started, the endpointer waits for silence past the 7 seconds. Silence at the end of the window is quiet: no "I didn't hear anything." A finger-hold does not open this window. A Gemini Live follow-up also skips it, because that path already kept the microphone.

## Barge-in and self-trigger

While Jarvis is speaking, the wake recorder is paused and a recognizer listens for barge phrases. Hardware echo cancellation on `VOICE_RECOGNITION` does not cancel `USAGE_ASSISTANT` playback, so the gate is the phrase list:

- "hey jarvis" or "hey jarvis …" stops TTS and listens. If the rest of the phrase is already the command, that text is sent to the brain without a second listen.
- "jarvis stop", "hey jarvis stop", "stop jarvis", "jarvis be quiet", and "hey jarvis be quiet" stop TTS and do not call the brain.
- A long partial that is only a slice of the sentence Jarvis is speaking (24 characters or more, contained in that sentence) is ignored.
- The bare word "jarvis" is neither a model hit nor a barge phrase. Bare "stop" still goes to the brain on a normal listen.

If the barge recognizer fails before it is ready, the wake model resumes at threshold 0.7 and needs two hot frames (`TTS_PATIENCE`). A timeout in the middle of a long sentence restarts the recognizer after 200 ms. The restart is tied to the speech session, so it cannot start after TTS has ended.

Push-to-talk cancels a hands-free listen, pauses the wake microphone, and starts the hold recognizer only if the finger is still down when the pause finishes.

## What still needs the screen

The service can detect a wake while YouTube is in front and the activity is only stopped. The voice loop lives in the activity view model. If that activity is destroyed, the detection has nowhere to speak until Jarvis is visible again. The usual path on this phone is stop, not destroy, so the answer still plays over the video.

## Debug

The wake listener must already be running (open Jarvis once so the foreground service starts). Push a 16 kHz mono wav into the app's files directory and broadcast:

```bash
adb -s R3CM501081Z shell run-as com.jarvis.app mkdir -p files
adb -s R3CM501081Z push hey_jarvis.wav /data/local/tmp/hey_jarvis.wav
adb -s R3CM501081Z shell run-as com.jarvis.app cp /data/local/tmp/hey_jarvis.wav files/hey_jarvis.wav
adb -s R3CM501081Z shell am broadcast -a com.jarvis.app.DEBUG_WAKE -p com.jarvis.app --es path hey_jarvis.wav
```

`run-as` can copy from `/data/local/tmp` on a debuggable build. The receiver scores the file on the in-process listener, so the broadcast still works while YouTube is in front. An absolute path the app can read also works. The broadcast is debug-only. Logcat tag: `JarvisWake`.

The broadcast scores the file on a fresh pipeline (one second of silence on each side, same idea as openWakeWord `predict_clip`) and, when the score crosses 0.35 after warmup and refractory (or two of four frames reach 0.30), runs the same orb path as a live wake.

## Checked on the Galaxy S10

Android 12 (API 31), serial R3CM501081Z. The service started while Jarvis was visible (`uidState: TOP`), stayed `isForeground=true` with the microphone notification, and kept scoring 80 ms frames while YouTube was the resumed activity.

A 16 kHz `say -v Daniel "hey jarvis"` clip scored 0.995 and triggered. The same voice saying only "jarvis" scored 0.022 and did not. Mac speaker playback at volume 81 did not raise a live score above 0.2, so the phone mic did not hear that speaker. Over the next two minutes, with YouTube in front, the highest live score was 0.464 and nothing triggered. Inference on each frame was about 21–34 ms. The `jarvis-wake` thread used about 36% of one core during that idle (about 47% with the Jarvis screen open). `dumpsys batterystats` had no separate mAh figure for this short run.
