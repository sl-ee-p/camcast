# CamCast handoff

## Done
- C1 skeleton (0.1.0), C2 camera to JPEG (0.2.0), C3 HTTP server (0.3.0), C4 foreground service + settings UI (0.4.0),
  C5 boot + hardening (0.5.0). All committed on master and pushed to sl-ee-p/camcast (origin).
- C5: `service/RestartPolicy` + watchdog/recovery in `StreamService` (new `StreamState.Recovering`), `startOnBoot` setting
  (switch in the Access section) + `BootReceiver`, "problems" notification, battery-optimisation prompt, README MIUI notes.
- 76 unit tests green, lint clean (0 findings).

## Verified on the emulator (a16 / API 36)
Start-on-boot switch persists; real `adb reboot` starts the app process from `BootReceiver` and the platform refuses the
camera FGS, which surfaces as the "CamCast is not streaming" notification; battery prompt (system dialog, hint hides once
allowed, shows again after removing the whitelist); camera outage (`cameraserver` stopped ~40 s): status and
notification go to "Camera lost, retrying (attempt N)", no flapping, and it returns to Streaming by itself once the
camera is back (snapshot 200); IT + 2x font for the Recovering status and the new rows.

## Not verified
- Real Redmi (Android 11): whether a boot-started camera service delivers frames, MIUI Autostart/battery behaviour.
- The 10-attempt give-up path on device (logic is unit tested; ~5 min run), the Italian text of the boot-blocked notification.
- 1080p, front camera that exists, real Wi-Fi LAN client, ffmpeg/VLC/go2rtc/Frigate as clients (C6).

## Next
- C6 on the phone: install on the Redmi (tap APK in File Manager), enable Autostart + battery, 24 h soak, view with
  ffplay/VLC, relay with go2rtc on a Pi or CT (not CT 100 while the emulator runs).
- Build: `JAVA_HOME=~/dev/jdk17 ANDROID_HOME=~/dev/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug`
  (set the variables in the same command; the shell does not keep them between calls).
- Emulator tips: `adb root` works on a16; `uiautomator dump` fails while the stats update, tap by coordinates.
