# 32steps

<p align="center">
  <a href="https://apt.izzysoft.de/packages/com.thirtytwo.steps"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="80" alt="Get it at IzzyOnDroid"></a>
  <br><br>
  <a href="https://apt.izzysoft.de/packages/com.thirtytwo.steps"><img src="https://img.shields.io/endpoint?url=https://apt.izzysoft.de/fdroid/api/v1/shield/com.thirtytwo.steps&label=IzzyOnDroid&style=for-the-badge" alt="IzzyOnDroid"></a>
  <a href="https://shields.rbtlog.dev/com.thirtytwo.steps"><img src="https://shields.rbtlog.dev/simple/com.thirtytwo.steps?style=for-the-badge" alt="RB Status"></a>
</p>

Override Android's default volume steps. Set your own custom step count (1-1000). No root required. Includes 6000+ headphone sound profiles from [AutoEQ](https://github.com/jaakkopasanen/AutoEq), updated automatically.

<p float="left">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="150" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="150" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="150" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="150" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="150" />
</p>

**Android TV**

<p float="left">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="300" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" width="300" />
</p>

## Features

- **Custom volume steps** - 1 to 1000 steps, replacing Android's default 15-25
- **6000+ headphone sound profiles** - AutoEQ corrections for accurate sound, updated automatically
- **10-band graphic equalizer** - Create custom profiles or fine-tune AutoEQ profiles
- **Channel balance** - Left/right volume control for hearing compensation
- **Volume overlay** - Floating popup with expandable stream sliders (Media, Call, Ring, Notification, Alarm)
- **Ringer mode toggle** - Switch Ring/Vibrate/Silent from the overlay
- **Preset system** - Save multiple headphone + step count combos, switch with one tap
- **Haptic feedback** - Tactile response on all controls
- **Android TV support** - Sound profiles and graphic EQ with D-pad navigation (custom steps not supported)
- **No root, no internet, no ads, no tracking**

> **Note:** Custom volume stepping is not available on Android TV due to platform limitations. On TV, the app provides sound profiles and graphic EQ only.

## How it works

Android defaults to 15-25 volume steps. 32steps lets you set your own count by splitting each system step into smaller sub-steps using a gain offset through Android's DynamicsProcessing API (falls back to Equalizer on older devices). An accessibility service intercepts your volume buttons, and a foreground service keeps it running in the background. Works across all apps.

## Sound Profiles

Pick your headphones from 6000+ models and the app corrects the sound based on measured data from AutoEQ. Save presets with different headphones and step counts. The headphone database updates automatically.

## Requirements

- Android 8+

## Setup

1. Install the APK
2. Open the app, set your preferred number of steps
3. Follow the guided setup (accessibility service, overlay, battery)
4. Close the app and use your volume buttons

## Permissions

- **Accessibility Service** - intercepts volume button presses
- **Overlay** - shows volume popup when you change volume
- **No internet** - the app can't send or receive any data

On Android 13+, you may need to allow restricted settings first. Go to **Settings > Apps > 32steps**, tap the three dots in the top right corner, then tap **Allow restricted settings**.


## Download

Available on [Droid-ify](https://github.com/Droid-ify/client) and [Neo Store](https://github.com/NeoApplications/Neo-Store) (IzzyOnDroid repo is pre-configured), or add the [IzzyOnDroid repo](https://apt.izzysoft.de/fdroid/repo) to the F-Droid client.

You can also grab the APK directly from the [Releases](https://github.com/nulldio/32steps/releases) page.

## Building from source

1. Open the project in Android Studio
2. **Build > Select Build Variant > release**
3. **Build > Generate App Bundles or APKs > Build APK**

## License

MIT
