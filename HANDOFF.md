# CamCast handoff (2026-11-18)

## Done
- C1-C5 (0.1.0-0.5.0), C6 device pass (0.5.1), motion clips C7-C9 (0.6.0) on the phone, viewer V4 + V5 (0.4.0) on the Pi.
  All committed and pushed. See PLAN.md (C6 status, "Motion clips") and viewer/PLAN.md.
- Tests: app 108 unit tests, lint clean (only the "Gradle 9.9.1 available" notice); viewer 46 unit tests, ruff,
  Playwright `events.cjs` 29/29 and `check.cjs` 25/25 (local viewer -> emulator phone).

## Verified on the Redmi (Android 11, Wi-Fi)
Auth, snapshot, MJPEG, ffmpeg, screen-off streaming, 6-client cap, stalled and vanished viewers, front camera,
480p/720p/1080p fps after the throttle fix, notification Stop, user Stop respected across updates, resume after update
while locked with the screen off, EN/IT/2x settings incl. the new rows. Motion: a person walking past recorded with
30 s before/after; no false clip in ~10 min of a still dim scene. 40 min soak at 1080p/10 fps on battery: steady
10 fps, 35.7 C, battery 59 -> 41 % (~27 %/h): it needs a charger.

## State left behind
- The phone: stream stopped and "Record a clip when something moves" OFF (changed by the owner during the session;
  not touched). "Start when the phone boots" ON, overlay permission granted. Phone language still Italian and
  screen timeout 30 min from the test session (owner interrupted the restore; ask before changing).
- One 95 MB clip (20261118-191939) is on the phone.
- The emulator a16: CamCast 0.6.0 (without recordingSince) stopped, ~1.8 GB of clips of its moving test scene in the app
  folder (uninstall or clear data to drop them).
- Pi viewer 0.4.0 deployed with the real phone credentials.

## Not verified
- A real reboot of the phone with start-on-boot (wireless debugging drops on reboot); the update path, which uses the
  same AutoStartActivity, is verified.
- The viewer V5 against the real phone on the Pi (phone was stopped by the owner); verified locally against the emulator.
- 24 h soak, clip retention at the 2 GB limit on the phone, motion outdoors / at night, Pi load with real frames.

## Next
- Owner: turn motion clips back on if wanted; put the phone on a charger.
- Maybe: MP4 clips via MediaCodec (5-10x smaller), clip delete from the viewer, a mask for areas to ignore,
  phone-side notification on movement.
- Build: `JAVA_HOME=~/dev/jdk17 ANDROID_HOME=~/dev/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug`.
- Locked phone tricks: `tools/prefs.py` edits settings over `run-as`; a reinstall restarts the stream.
