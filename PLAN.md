# CamCast: LAN camera streamer for the old phone

Ad-free replacement for IP Webcam. Streams the phone camera as MJPEG over the LAN. Nothing else.
Target device: Redmi Note 10 Pro (Android 11, 192.168.0.18). Package: `com.slp.camcast`.

## Scope (v1)
- Native Kotlin, one module, CameraX, no third-party HTTP server (plain `ServerSocket` is enough).
- Foreground service owns the camera and the HTTP server; the UI only configures and starts/stops it.
- Endpoints: `/video` (MJPEG, multipart/x-mixed-replace), `/snapshot.jpg`, `/` (tiny status page).
- HTTP basic auth (username + password set in the app), LAN only, never port-forwarded.
- Settings: camera (back/front), resolution (480p/720p/1080p), fps cap, JPEG quality, port (default 8080).
- Start on boot (optional), wake lock, persistent notification, screen-off streaming.
- UI: one settings screen with Start/Stop and the stream URL. EN + IT strings from day one.

## Out of scope (v1)
Audio, RTSP, motion detection/AI, recording, cloud, multiple clients beyond a handful, two-way talk.
Later, if wanted: audio, RTSP via a relay (go2rtc), motion clips.

## Milestones
Each one follows the Definition of Done in ~/.claude/CLAUDE.md (analyze/lint clean, tests green, fresh debug APK,
screens exercised, version bump, docs, commit + push). Debug APK on the emulator first (virtual camera);
the Redmi needs a manual tap in File Manager to install (MIUI blocks adb installs without a SIM).

- **C1 Skeleton (DONE 0.1.0):** Gradle project, package, theme, EN/IT strings, empty settings screen, CI-less local build, git + private repo.
- **C2 Camera to JPEG:** CameraX ImageAnalysis -> JPEG frames at the chosen resolution/fps/quality. Unit test the frame
  throttling/encoding glue.
- **C3 HTTP server:** `ServerSocket` server with `/video`, `/snapshot.jpg`, `/`; basic auth; several clients; clean shutdown.
  Unit tests for request parsing, auth and the MJPEG framing.
- **C4 Foreground service + UI:** service lifecycle, notification, wake lock, settings persisted (DataStore),
  start/stop, URL display, permission flow (camera, notifications).
- **C5 Boot and hardening:** start on boot, restart on camera error/disconnect, battery-optimisation prompt,
  MIUI autostart notes in the README, 2x font and Italian layout check.
- **C6 On the phone:** install on the Redmi, 24 h soak test, view from the LAN (`ffplay`/VLC), then relay with
  go2rtc on a Pi or CT (not CT 100 while the emulator runs).

## Decisions
- Native Kotlin over Flutter: camera + background service + socket server are all platform code.
- MJPEG first because go2rtc, ffmpeg and Frigate all read it directly; RTSP can be added via the relay.
- No third-party server library: ~200 lines, fewer dependencies, easier to audit.

## Open questions
- Where will the phone sit (Wi-Fi strength, power)? Needed only for C6.
- Back or front camera as the default?
