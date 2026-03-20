# 32steps

Override Android's default 15 volume steps. Set your own custom step count (2–1000). No root required.

## How it works

Android limits media volume to ~15 steps. 32steps splits each system step into smaller sub-steps using audio effects (DynamicsProcessing or Equalizer), giving you real, audible volume changes on every button press.

Works system-wide — YouTube, Spotify, games, everything.

## Setup

1. Install the APK
2. Open the app, set your preferred number of steps
3. Follow the guided setup (accessibility service, overlay, battery)
4. Done — close the app and use your volume buttons

## Permissions

- **Accessibility Service** — intercepts volume button presses
- **Overlay** — shows volume popup when you change volume
- **No internet** — the app can't send or receive any data

## Building

Open the project in Android Studio and build the APK:

**Build → Generate App Bundles or APKs → Build APK**

APK output: `app/build/outputs/apk/debug/app-debug.apk`

For a signed release build, see [Android docs on app signing](https://developer.android.com/studio/publish/app-signing).

## License

MIT
