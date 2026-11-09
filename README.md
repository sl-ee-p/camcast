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

- C1 skeleton (0.1.0): settings screen shell, EN + IT strings, edge-to-edge insets, adaptive icon.
- C2 camera to JPEG (0.2.0): CameraX `ImageAnalysis` -> upright JPEG frames (`camera/`), capped by `FrameThrottle`
  (10% jitter tolerance), published into a `FrameBuffer` that C3's HTTP clients will wait on. The Start button runs
  the camera inside the activity and shows frame number, fps, size and a preview; C4 moves this into a foreground service.
- C3 HTTP server (0.3.0): `/video` (MJPEG), `/snapshot.jpg`, `/` status page, all behind basic auth (`server/`).
  The test screen shows the URL and a random per-run password; try it with
  `adb forward tcp:18080 tcp:8080 && curl -u camcast:<password> -o snap.jpg http://127.0.0.1:18080/snapshot.jpg`.
