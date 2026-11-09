# CamCast

Ad-free LAN camera streamer for an old Android phone (MJPEG over HTTP). See `PLAN.md` for scope and milestones.

## Build

JDK 17 and the Android SDK are needed (the system Java here is a JRE only):

```
export JAVA_HOME=~/dev/jdk17 ANDROID_HOME=~/dev/android-sdk
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. On the Redmi (MIUI, no SIM) adb installs are blocked:
copy the APK over and tap it in File Manager.

## Status

C1 skeleton done (0.1.0): empty settings screen, EN + IT strings, edge-to-edge insets, adaptive icon.
