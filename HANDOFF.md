# CamCast handoff

## Done
- C1 skeleton (0.1.0), C2 camera to JPEG (0.2.0), C3 HTTP server (0.3.0), C4 foreground service + settings UI (0.4.0).
  All committed locally on master. No remote yet.
- C4: `service/` (StreamService, StreamController/StreamState, StreamNotification), `settings/` (AppSettings, validator,
  password generator, DataStore store), `net/LocalAddress`, new MainActivity (status + start/stop + settings form).
- 70 unit tests green, lint clean (0 findings).

## Verified on the emulator (a16 / API 36, virtual back camera)
Fresh install, first-run password, camera + notification permission dialogs (allow and deny paths), validation error
(port 80), start/stop from the app and from the notification Stop action, streaming with the app in the background and
with the screen off (frames keep changing), wake lock + Wi-Fi lock held while running and released after stop,
notification text "ip:port · N viewers", settings persisted across force-stop (front camera, 480p, port 9090),
no front camera -> plain "This phone has no front camera." message, 480p preset (480x640 upright, 10 fps), EN + IT at 2x
font (fixed a truncated "1080p" button), no crashes in logcat.

## Not verified
- Real camera on the Redmi (manual APK install via File Manager); real Wi-Fi LAN client; MIUI battery/autostart behaviour.
- 1080p (emulator camera tops out at 720p; the request fell back to 720x1280), front camera that exists, camera error
  mid-run (unplug/disconnect), process kill / low memory.
- ffmpeg/VLC/go2rtc/Frigate as clients; browser view of `/`.
- GitHub: `gh repo create sl-ee-p/camcast --private` was denied by the auto-mode classifier; push still pending.

## Next
- C5 boot and hardening: start on boot (setting), restart on camera error/disconnect, battery-optimisation prompt,
  MIUI autostart notes in README, failed-login throttling (optional), 2x font + Italian check again.
  See PLAN.md "Notes from C4" about START_NOT_STICKY and background camera-service limits.
- Build: `JAVA_HOME=~/dev/jdk17 ANDROID_HOME=~/dev/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug`.
- Emulator tip: `uiautomator dump` fails while the stats line updates; tap Stop by coordinates.
