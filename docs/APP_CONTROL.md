# Voice app control

Jarvis handles these on the phone, in English and Arabic, before a cloud brain sees them.

- "open YouTube" / "افتح يوتيوب" launches an installed app by its label. Matching ignores case and Arabic diacritics, and tolerates a short or slightly misspelled name.
- "play Amr Diab on YouTube" / "شغل عمرو دياب على يوتيوب" searches YouTube and starts the first real video.
- "play …" with no app uses an installed player that supports `MEDIA_PLAY_FROM_SEARCH` and actually starts audio (YouTube Music, Spotify, or Samsung Music). This Galaxy S10 has none of those, so Jarvis falls back to YouTube.
- "pause", "play", "next", "previous", and the Arabic equivalents send media keys. Volume up, down, mute, unmute, and "set the volume to N" use the music stream.
- "skip ad" / "تخطي الاعلان" taps YouTube's Skip button when it is on screen.
- "go back to Jarvis", "stop", and "ارجع لجارفس" bring Jarvis back to the front.

## YouTube actually playing

On this phone, YouTube's `MEDIA_PLAY_FROM_SEARCH` activity opens and leaves the current session paused. Jarvis does not treat that as success. It opens

`https://www.youtube.com/results?search_query=…`

and the accessibility service taps the first playable row.

The service is limited to `com.google.android.youtube`. It reports view ids, reads interactive windows, and can perform gestures. It skips Sponsored rows, Shorts, and channel shelves, scrolls the results list until a video with a duration is visible, and taps that row. If a skip-ad button is showing, it taps that too so the session reaches playing. Pause, play, and next inside YouTube use the media key first and the on-screen control if the key did not change playback.

You have to turn the service on once. The Jarvis screen says this while it is off:

Settings > Accessibility > Installed apps > Jarvis > On

The same sentence is the service description on that settings page. Until it is on, "play … on YouTube" still opens the search and Jarvis says it could not start playback.

A debug phone can enable it without the settings UI (this replaces the enabled-services list):

```bash
adb shell settings put secure enabled_accessibility_services com.jarvis.app/com.jarvis.app.system.JarvisAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

On this Galaxy S10, force-stopping Jarvis or installing an update clears that list. Turn the service on again after an update. Do not force-stop Jarvis while you still need the service.

## Kiosk

Jarvis is not the device owner on this Galaxy S10 (`dpm list-owners` prints "no owners"), and lock task is off. Launching another app needs nothing special: there is no lock task to leave. When a video later stops, or you say "go back to Jarvis", Jarvis comes back to the front.

If you later make Jarvis the device owner, it adds the target package to `setLockTaskPackages` and stays inside lock task instead of calling `stopLockTask`. The admin component is `com.jarvis.app/.system.JarvisDeviceAdmin`.

```bash
adb shell dpm set-device-owner com.jarvis.app/.system.JarvisDeviceAdmin
```

That command only works when all of these are true:

- No device owner and no profile owner is already set.
- The user has no accounts. A Galaxy S10 signed into Google will refuse. Removing accounts, or setting the owner before the first account on a factory-reset phone, is the usual way.
- The debug app is installed, and the admin receiver above is in the manifest.
- You run it as the primary user.

`dpm remove-active-admin` only removes a test-only admin. A real device owner is cleared by a factory reset. Do not run `set-device-owner` on a daily phone you still need signed in.

If the phone is not the owner but screen pinning has Jarvis locked, Jarvis calls `stopLockTask` before the other app opens and `startLockTask` when it returns. Pinning may ask you to confirm again.

## Android 12 while another app is in front

Hey Jarvis keeps listening while YouTube is in front. The wake recorder starts in a microphone foreground service while Jarvis is still visible, then stays up after the other app opens. It uses `VOICE_RECOGNITION` and does not take audio focus, so playback keeps going until a real wake ducks it. Hold the orb for push-to-talk if the wake word misses. Details, licenses, and the debug wav command are in `docs/WAKE_WORD.md`.

Android 12 refuses to start that service from the background, and it refuses microphone access in the background unless a microphone foreground service was started from the foreground. Leaving YouTube or saying "back" does not stop the service. Commands still arrive through the voice loop while the activity is alive, including the debug broadcast `com.jarvis.app.DEBUG_ASK`. If the activity is destroyed, the service still detects the wake word, and speech waits until Jarvis is visible again. Speech uses assistant audio so a short reply ducks the player instead of pausing it. If a reply still pauses YouTube, Jarvis sends play again for a few seconds. "pause" cancels that.

The notification needs no extra permission on Android 12. Android 13 and later also need notification permission before the text is visible. The service can still run.

Log tag `JarvisAppControl` records the matched command, the package launched, lock-task changes, and media-key or volume changes. `JarvisAccessibility` records taps, scrolls, and skip-ad.
