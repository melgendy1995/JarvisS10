# Jarvis S10 setup

The app builds and runs with no Firebase project. Time, timers, and the flashlight stay on the phone. Gemini reports "not configured" and the chain falls through until the file below is present. Do not commit `google-services.json` or any key.

## Firebase project

1. Create a Firebase project in the [Firebase console](https://console.firebase.google.com/). This is the billed project Live runs on. Do not paste a Gemini API key into the app.
2. Add an Android app with package name `com.jarvis.app`.
3. Register the debug signing certificate. From the repo root:

   ```bash
   keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android -keypass android
   ```

   Add the SHA-1 (and SHA-256 if the console asks) to that Android app.
4. Download `google-services.json` and put it at `app/google-services.json`. The Google Services plugin applies only when that file exists. It is gitignored.
5. In Firebase, open AI Logic and enable the Gemini Developer API backend. The app calls `gemini-3.1-flash-live-preview` for Live and `gemini-3.1-flash-lite` for text. Both go through Firebase AI Logic. There is no Gemini key in the APK.
6. Rebuild and install the debug app (`./gradlew installDebug` or Android Studio).

## App Check

1. In the Firebase console, open App Check and enable Play Integrity for `com.jarvis.app`. Release builds use the Play Integrity provider.
2. Debug builds use the debug provider only. They do not ship in release.
3. Launch the debug build and ask something the phone cannot answer (so Gemini is contacted). In logcat, filter for `DebugAppCheckProvider`. The line looks like: `Enter this debug secret into the allow list in the Firebase Console for your project: <token>`.
4. Register that debug token in App Check for this phone. Ask again after it is saved.

Play Integrity often rejects a pure sideload. For a release build, upload the same signing cert to an internal testing track and install that build. A release APK must not use the debug provider.

## Groq proxy

The phone never calls `api.groq.com` and never holds a Groq key. A Cloudflare Worker checks a bearer, allows only the three chat models plus `whisper-large-v3-turbo`, and forwards with `GROQ_API_KEY`.

1. Install Wrangler (`npm install -g wrangler`) and log in (`npx wrangler login`).
2. From `worker/`:

   ```bash
   npx wrangler secret put GROQ_API_KEY
   npx wrangler secret put JARVIS_BEARER
   npx wrangler deploy
   ```

   `GROQ_API_KEY` is the key from the Groq console. `JARVIS_BEARER` is a long random string you invent. Neither value belongs in git, `wrangler.toml`, or `local.properties`.
3. Copy the deployed `*.workers.dev` URL into `local.properties` as `GROQ_PROXY_URL` (no trailing path). Rebuild the app. An empty URL leaves Groq as not configured and the chain keeps working.
4. On the phone, long-press the drifting state word (IDLE, LISTENING, and the rest). Paste the same bearer and save. It is stored in Android Keystore-backed encrypted preferences.

The worker's daily counters (1,200 chat requests, 2,000 Whisper requests) live in the isolate and reset on a cold start. The phone also rotates `openai/gpt-oss-120b`, `qwen/qwen3.8-27b`, and `openai/gpt-oss-20b`, cools only the model that returned 429, and stops each one near 180K tokens in a rolling 24 hours. Replace `JARVIS_BEARER` to revoke a lost phone without shipping a new APK.

Whisper runs only after on-device speech recognition fails, and only when Gemini or Groq can still answer. The transcript still goes through the on-device router first.

## Voice control of other apps

"Open YouTube", "play … on YouTube", pause, and volume are documented in [APP_CONTROL.md](APP_CONTROL.md), including the accessibility switch, kiosk device-owner command, and what Android 12 allows while another app is in front.

## Install script

`scripts/install.sh [--no-build] [--serial SERIAL] [--no-pin]` builds `app-debug.apk` (skipped with `--no-build`, or when the APK is newer than everything under `*/src`), installs it with `adb install -r`, re-enables the accessibility service (without dropping other enabled services), re-grants the notification listener, relaunches `MainActivity`, and prints a status block. The default serial comes from `$ANDROID_SERIAL`, else `R3CM501081Z`.

Kiosk: this install is not device owner, so the script pins the Jarvis task with `am task lock <id>` (screen pinning). Pass `--no-pin` to skip, or leave it to `KioskGate` when the app does become device owner.

## Device smoke test

`scripts/device-smoke.sh [--serial SERIAL]` (default serial from `$ANDROID_SERIAL`, else `R3CM501081Z`) runs a battery of end-to-end checks on the phone and prints one `PASS|FAIL|SKIP  <name>  <detail>` line per check plus a `N pass, N fail, N skip` summary, to stdout and to `logs/smoke-<timestamp>.txt`. It checks the app process and crash buffer, the shelf Wi-Fi IP, that port 8088 answers 401 without credentials, a question in English and one in Arabic through the debug `DebugAskReceiver` broadcast, YouTube Music play/pause/resume in both languages (media session state 3/2), the "Nothing is playing." line when nothing runs, camera stream start/stop, and a generated `say -v Daniel "hey jarvis"` wav replayed into the wake listener per [WAKE_WORD.md](WAKE_WORD.md). The script is file-only (no speaker output), never unlocks the phone, and never reads a secret store. A failing check never stops the run.

Unlock the phone for the YouTube Music checks — play/search needs YT Music on screen, and those checks SKIP with "phone locked" otherwise. If the kiosk pin from install.sh was active, the script unpins before the media checks and re-pins Jarvis when it exits.
