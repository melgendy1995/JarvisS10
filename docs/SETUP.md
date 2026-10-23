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
