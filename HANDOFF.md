# CamCast handoff

## Done
- C1 skeleton (0.1.0) and C2 camera to JPEG (0.2.0), committed locally on master. No remote yet.
- C2: `camera/` package: `StreamConfig`, `FrameThrottle`, `FrameBuffer`, `JpegEncoder`, `CameraSession`; activity harness with preview/stats.
- 18 unit tests green (throttle, frame buffer, config, EN/IT string parity), lint clean.

## Verified on the emulator (a16, virtual back camera)
Permission dialog (allow + deny toast), streaming at 720x1280 / ~10 fps, EN + IT, 2x font, Stop, camera released on Home.

## Not verified
- Real camera on the Redmi (needs manual APK install via File Manager).
- Front camera, 480p and 1080p presets, JPEG orientation on a real sensor.
- GitHub: `gh repo create sl-ee-p/camcast --private` was denied by the auto-mode classifier; push still pending.

## Next
- C3 HTTP server (`ServerSocket`, `/video`, `/snapshot.jpg`, `/`, basic auth) reading from `FrameBuffer.awaitNewer`.
- Build: `JAVA_HOME=~/dev/jdk17 ANDROID_HOME=~/dev/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug`.
