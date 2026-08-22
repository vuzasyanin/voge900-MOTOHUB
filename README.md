# VOGE 900DSX MOTOHUB

Clone of [MOTO-HUB](https://github.com/vincenzobpt/MOTO-HUB), remade specifically for **VOGE 900DSX** motorcycles.

Android 14+ app that connects the phone to the motorcycle T-Box and projects **Android Auto** or **screen mirroring** (whole screen or a single app) onto the 900DSX TFT. Local-first: no account, no vendor affiliation with VOGE or Loncin.

This is an experimental proof-of-concept. Do not rely on it as the only navigation or safety system. Use it at your own risk, and configure everything while parked.

**APK:** [latest release](https://github.com/vuzasyanin/voge900-MOTOHUB/releases/latest) — download the `.apk` under Assets, not the source archives.

Upstream MOTO-HUB is multi-brand. This fork is developed and tested against the **VOGE 900DSX**. For other motorcycles, use [the original](https://github.com/vincenzobpt/MOTO-HUB). Optional companion: [MOTO-HUB ADVANCED](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases) (IPC compatibility with this fork is not guaranteed).

## Build

JDK 21, Android SDK API 36, a physical device, and `apps/android/app/libs/hudlib.aar`. From `apps/android/`:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
```

A default source build does pairing, mirroring and diagnostics. Android Auto needs identity files in `tooling/private/android-auto/` and `-PincludeAndroidAutoIdentity=true`. See [`documentation/`](documentation/).

## License

[AGPL-3.0](LICENSE). Independent fork; VOGE, Loncin, EasyConn, Android Auto and related names remain their owners'.
