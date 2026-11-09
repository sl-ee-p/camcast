# CamCast handoff

## Done
- C1 skeleton (0.1.0), C2 camera to JPEG (0.2.0), C3 HTTP server (0.3.0). Committed locally on master, no remote yet.
- C3: `server/` package (parser, basic auth, MJPEG writer, `CamServer`), wired into the activity harness
  (URL + random password on screen, server and camera stop together on Stop / onStop).
- 58 unit tests green (incl. 15 real-socket server tests with an uncaught-exception trap), lint clean.

## Verified on the emulator (a16, virtual back camera)
Start/Stop, permission flow, via `adb forward` + curl: 401 without/with wrong auth, snapshot (valid 720x1280 JPEG),
status page (viewer count), 404/405, `/video` with two simultaneous clients (30 valid parts in 3 s, correct
boundaries and JPEG markers), port closed after Stop and Home, new password every run (old one -> 401),
no crash when stopping with a viewer connected, EN + IT at 2x font.

## Bugs found by verification
- `ByteArrayOutputStream.toString(Charset)` needs API 33 (lint NewApi); passes JVM tests, would crash on API 26-32.
- `stop()` interrupted a streaming thread -> uncaught `InterruptedException` killed the app on device only.
  Fixed in `FrameBuffer.awaitNewer` + stream loop; test now traps uncaught exceptions.

## Not verified
- Real camera and a real LAN client on the Redmi (manual APK install via File Manager).
- ffmpeg/VLC/go2rtc as clients (none installed here; curl + a framing check only).
- Browser rendering of `/` (the `<img src=/video>` with basic auth).
- Front camera, 480p/1080p presets.
- GitHub: `gh repo create sl-ee-p/camcast --private` was denied by the auto-mode classifier; push still pending.

## Next
- C4 foreground service + settings UI (DataStore; camera, resolution, fps, quality, port, username/password),
  notification, wake lock, permission flow (camera + notifications). Move `CameraSession` and `CamServer` into the service
  (CameraSession needs a LifecycleOwner: use `LifecycleService`).
- Build: `JAVA_HOME=~/dev/jdk17 ANDROID_HOME=~/dev/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug`.
