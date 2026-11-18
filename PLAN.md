# CamCast: LAN camera streamer for the old phone

Ad-free replacement for IP Webcam. Streams the phone camera as MJPEG over the LAN. Nothing else.
Target device: Redmi Note 10 Pro (Android 11, 192.168.0.15). Package: `com.slp.camcast`.

## Scope (v1)
- Native Kotlin, one module, CameraX, no third-party HTTP server (plain `ServerSocket` is enough).
- Foreground service owns the camera and the HTTP server; the UI only configures and starts/stops it.
- Endpoints: `/video` (MJPEG, multipart/x-mixed-replace), `/snapshot.jpg`, `/` (tiny status page).
- HTTP basic auth (username + password set in the app), LAN only, never port-forwarded.
- Settings: camera (back/front), resolution (480p/720p/1080p), fps cap, JPEG quality, port (default 8080).
- Start on boot (optional), wake lock, persistent notification, screen-off streaming.
- UI: one settings screen with Start/Stop and the stream URL. EN + IT strings from day one.

## Out of scope (v1)
Audio, RTSP, AI detection, cloud, multiple clients beyond a handful, two-way talk.
Later, if wanted: audio, RTSP via a relay (go2rtc), motion clips.

## Milestones
Each one follows the Definition of Done in ~/.claude/CLAUDE.md (analyze/lint clean, tests green, fresh debug APK,
screens exercised, version bump, docs, commit + push). Debug APK on the emulator first (virtual camera);
on the Redmi `adb install -r` works since 2026-11-18 ("Install via USB" is on).

- **C1 Skeleton (DONE 0.1.0):** Gradle project, package, theme, EN/IT strings, empty settings screen, CI-less local build, git + private repo.
- **C2 Camera to JPEG (DONE 0.2.0):** CameraX ImageAnalysis -> JPEG frames at the chosen resolution/fps/quality. Unit test the frame
  throttling/encoding glue.
- **C3 HTTP server (DONE 0.3.0):** `ServerSocket` server with `/video`, `/snapshot.jpg`, `/`; basic auth; several clients; clean shutdown.
  Unit tests for request parsing, auth and the MJPEG framing.
- **C4 Foreground service + UI (DONE 0.4.0):** service lifecycle, notification, wake lock, settings persisted (DataStore),
  start/stop, URL display, permission flow (camera, notifications).
- **C5 Boot and hardening (DONE 0.5.0):** start on boot, restart on camera error/disconnect, battery-optimisation prompt,
  MIUI autostart notes in the README, 2x font and Italian layout check.
- **C6 On the phone:** install on the Redmi, 24 h soak test, view from the LAN (`ffplay`/VLC), then relay with
  go2rtc on a Pi or CT (not CT 100 while the emulator runs).

- **C6 status (2026-11-18, 0.5.1):** tested on the Redmi over Wi-Fi: auth, snapshot, MJPEG, ffmpeg/ffprobe, screen-off streaming,
  6-client cap, a stalled and a vanished viewer, front camera, 480p/720p/1080p, notification Stop, EN/IT/2x. Fixed: throttle
  (8.3 -> 10 fps), vanished viewers held a slot ~15 min (now 20 s), no camera for a background-started service on Android 11
  (AutoStartActivity), resume after an app update. Still open: 24 h soak, real reboot, go2rtc relay.

### Motion clips (requested 2026-11-18)
Movement detection without AI, a clip with 30 s before and 30 s after the movement saved in a folder on the phone,
a "movement at HH:MM:SS" notice on the web viewer and clips playable there.
- **C7 Motion detector:** luma grid (64x48 cell averages of the Y plane, before any JPEG work) against a slowly adapting
  background; a cell counts as changed past a threshold after removing the global brightness shift (auto exposure);
  motion = changed share above the sensitivity (low/medium/high) on 2 frames in a row; >60 % changed = lighting change,
  re-learn. 2 s warm-up. Pure Kotlin, unit tested with synthetic grids.
- **C8 Clip recorder:** 30 s ring of the JPEG frames already encoded for the stream (memory capped), on motion writes
  pre-roll + live frames to `<id>.mjpeg.part` on a writer thread, every new motion extends the end to +30 s, clips capped at
  5 min; then `<id>.mjpeg` + `<id>.json` (times, frames, size) + `<id>.jpg` (the motion frame). Folder: the app's external
  files dir `clips/` (`/sdcard/Android/data/com.slp.camcast/files/clips`, no storage permission). Oldest clips deleted
  above 2 GB or below 500 MB free. Stale `.part` files from a crash are kept as clips.
- **C9 Wiring + API + UI:** detector and recorder in the camera pipeline; settings "Record clips on motion" (on) +
  sensitivity; last motion time in the app; endpoints (behind the same auth) `/events.json`, `/clips/<id>.jpg`,
  `/clips/<id>.mjpeg` (download), `/clips/<id>/play` (paced replay as MJPEG, so an `<img>` plays it). EN/IT, device test.
- **V5 Viewer events:** viewer proxies `/api/events` (cached, shared) and `/clips/*`; page polls, shows a toast
  "Movement detected at 19:07:03" (no browser notifications: they need HTTPS), an events list with thumbnails, play in
  an overlay, download. EN/IT, Playwright, deploy to the Pi.

Why MJPEG clips and not MP4: the frames are already JPEG, so recording costs only disk writes (no second encoder, no
YUV plumbing), and replay reuses the live-view path. Cost: ~1.3 MB/s at 1080p (~80 MB per 60 s clip), ~0.5 MB/s at
720p. Downloads play in VLC/ffplay. MP4 via MediaCodec can come later if storage matters.

## Decisions
- Native Kotlin over Flutter: camera + background service + socket server are all platform code.
- MJPEG first because go2rtc, ffmpeg and Frigate all read it directly; RTSP can be added via the relay.
- No third-party server library: ~200 lines, fewer dependencies, easier to audit.

## Open questions
- Where will the phone sit (Wi-Fi strength, power)? Needed only for C6.
- Back or front camera as the default?

## Notes from C2
- `StreamConfig` (camera, resolution, fps cap, JPEG quality) has defaults (back, 720p, 10 fps, q70); C4 persists it.
- The activity runs the camera and stops it in `onStop`; C4 replaces that with a foreground service.
- Emulator virtual camera delivers a pixel-art scene at 720x1280 (rotated upright) and ~10 fps at the cap.
- Not yet tried: front camera, 480p/1080p presets, a real device camera (the Redmi).

## Notes from C3
- `server/`: `RequestParser` (GET/HEAD, size limits), `BasicAuth` (constant-time, blank credentials never match),
  `MjpegWriter` (boundary `camcastframe`), `HttpResponse`, `CamServer` (accept loop, thread per client, max 6, 503 beyond).
- Auth runs before routing, so unauthenticated clients can't probe paths. Every response closes the connection.
- Threads: `stop()` interrupts handlers; an uncaught exception on any thread kills the Android app, so
  `CamServerTest` traps uncaught exceptions (this caught a real `InterruptedException` crash on device).
- The activity harness uses user `camcast` and a random 8-char password per run, shown on screen. C4 replaces
  this with persisted settings. No rate limiting on failed logins (LAN only); revisit in C5 if wanted.
- `adb forward tcp:18080 tcp:8080` + curl is the quick way to test a running emulator build.

## Notes from C4
- `service/StreamService` (a `LifecycleService`, foreground type `camera`) owns `CameraSession` + `CamServer`, a partial
  wake lock and a Wi-Fi lock; `StreamController` (process-wide `StateFlow<StreamState>`) is how the activity sees it.
  Stopped / Starting / Running(host, port, viewers, frames, fps, size) / Failed(message).
- Settings: `settings/` (AppSettings, SettingsValidator, SettingsStore on DataStore). First run generates a random
  10-char password; port 1024-65535, user name without ':', password >= 6 chars. The form locks while running.
- Permission flow: camera, then notifications (API 33+). Denying notifications still runs the stream (the notification is hidden).
- Return value is `START_NOT_STICKY`: a killed service stays dead. C5 decides on restart (note: from API 31 a camera
  foreground service cannot be started from the background, and from API 34 not from BOOT_COMPLETED; the Redmi runs Android 11).
- Missing camera gives a plain message (`CameraMissingException`); other camera errors show the raw message.
- Verification gotcha: `uiautomator dump` fails ("could not get idle state") while the status line updates every second;
  tap the running-state Stop button by coordinates.

## Notes from C5
- `service/RestartPolicy` (pure, tested): backoff 2/4/8/16/30 s, 10 attempts, `isRetryable` (a missing camera or a
  `SecurityException` is not retried), 10 s stall threshold. The ticker in `StreamService` is the watchdog: no new frame
  for 10 s after the camera started means restart. `StreamState.Recovering` is shown until a fresh frame arrives.
- CameraX alone recovers from a quick `cameraserver` restart (a few seconds); the watchdog matters for longer outages.
  Test on the emulator with `adb root`, then `setprop ctl.stop cameraserver` / `ctl.start cameraserver`. (The
  `appops set CAMERA deny` route does nothing.)
- Boot: `BootReceiver` (`RECEIVE_BOOT_COMPLETED`) starts the service with `EXTRA_FROM_BOOT`; a failure while started that
  way posts a "problems" notification (`StreamNotification.postProblem`). On API 36 the platform refuses a camera FGS
  from boot ("must be in the eligible state") unless exempt, so only the fallback was verified there.
- `adb shell am broadcast BOOT_COMPLETED` is denied to the shell; use a real `adb reboot` (do not `force-stop` first,
  and wait: the emulator delivers boot broadcasts long after `sys.boot_completed`).
- Battery: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` direct prompt, hidden once granted (sideloaded app, not for Play).
  `adb shell dumpsys deviceidle whitelist -com.slp.camcast` removes it again for testing.
- Left out: failed-login throttling (LAN only). `START_NOT_STICKY` kept: a sticky restart comes from the background
  with no intent and cannot open the camera on newer Android.
