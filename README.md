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
  Try it with `adb forward tcp:18080 tcp:8080 && curl -u camcast:<password> -o snap.jpg http://127.0.0.1:18080/snapshot.jpg`.
- C4 foreground service + settings (0.4.0): `StreamService` runs the camera and server with a notification (Stop action),
  wake lock and Wi-Fi lock, so streaming continues with the screen off or the app closed. One settings screen
  (camera, resolution, frame-rate limit, JPEG quality, port, user name, password) persisted with DataStore.
  The password is generated on first run; read it with the eye icon in the Password field.
- C5 boot and hardening (0.5.0): the camera restarts itself when it fails or stops delivering frames for 10 s
  (waits 2, 4, 8, 16, then 30 s; gives up after 10 tries and shows the error). The server stays up meanwhile and the
  app shows "Camera lost, retrying". "Start when the phone boots" (off by default) starts the stream from
  `BootReceiver`; if Android refuses, a notification says so and opens the app. The app also offers to exempt itself
  from battery optimisation.

## Keeping it alive on the Redmi (MIUI)

MIUI is aggressive about stopping background apps. For an always-on camera:

1. Settings > Apps > Manage apps > CamCast > **Autostart**: on (needed for "Start when the phone boots").
2. Same page > **Battery saver**: "No restrictions" (the in-app "Allow background running" button covers the standard
   Android part of this).
3. Recent apps: long-press the CamCast card and **lock** it, so "clear all" does not kill it.
4. Keep the phone on a charger and on Wi-Fi with "Keep Wi-Fi on during sleep" set to always.

Boot start is best effort: Android 14+ refuses a camera foreground service from boot, and Android 11 (the Redmi) may
deliver no frames to a service started from the background. Either way the retry/give-up logic ends in a
"CamCast is not streaming" notification instead of a silent failure; tap it and press Start.
