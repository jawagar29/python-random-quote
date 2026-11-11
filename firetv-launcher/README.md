# Clean Home — a quiet launcher for Fire TV

A minimal home screen for Fire TV devices (built for the Fire TV Stick 4K, Fire OS 6):
a clock and a grid of your apps on a dark background. The focused app grows and lifts,
and nothing else moves.

- **No ads, by construction.** The app does not request the `INTERNET` permission, so it
  cannot download ads, recommendations or anything else.
- Apps with wide TV artwork show it; other apps show their icon on a card tinted to match.
- Hold **Select** (or press **Menu**) on an app to move it, hide it, open its info page or
  uninstall it.
- System apps are hidden by default, apart from the Appstore, Prime Video and Amazon Music.
  Bring any of them back from the **Hidden apps** tile.
- Built-in tiles: **Settings**, **Fire TV Home** (the original Amazon screen, for the
  Appstore and search) and **Hidden apps**.

> Status: runs on a Fire TV Stick 4K (1st generation, Fire OS 6). Other models are untested.

## Install

With ADB connected to the stick (`adb connect <FIRE_TV_IP>:5555`):

```
adb install -r dist/clean-home.apk
adb shell am start -n com.cleanhome.tv/.MainActivity
```

At this point Clean Home is an app you can open; the Home button still goes to Amazon's
screen.

## Make the Home button open Clean Home

Fire OS does not let you choose a default launcher. Clean Home includes a small
accessibility service that brings it forward whenever Amazon's home screen appears. Turn
it on with:

```
adb shell settings put secure enabled_accessibility_services com.cleanhome.tv/com.cleanhome.tv.HomeService
adb shell settings put secure accessibility_enabled 1
```

Amazon's launcher stays installed, so nothing can be left without a home screen. You may
see it flash for a moment before Clean Home takes over.

If the Home button still shows Amazon's screen, open that screen and run:

```
adb shell "dumpsys window windows | grep mCurrentFocus"
```

The service only redirects a window from `com.amazon.tv.launcher` whose class name
contains `HomeActivity`; if your firmware uses a different name, adjust `HomeService.java`.

## Undo

```
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0
adb uninstall com.cleanhome.tv
```

## Build

Needs a JDK and the Android SDK (`platforms;android-34`, `build-tools;35.0.0`):

```
ANDROID_HOME=/path/to/android-sdk ./build.sh
```

`debug.keystore` (password `android`) is a throwaway signing key kept in the repo so each
build installs over the last one. It protects nothing; do not reuse it elsewhere.

## Last known good version

**Version 1.2** (commit `ac238fd`) is confirmed working on a Fire TV Stick 4K (1st
generation). A copy of that build is kept at `dist/clean-home-1.2-known-good.apk` and is
not overwritten by later builds.

To go back to it on the device:

```
adb install -r -d dist/clean-home-1.2-known-good.apk
```

To restore the source to that version:

```
git checkout ac238fd -- firetv-launcher/src firetv-launcher/res firetv-launcher/AndroidManifest.xml
```
