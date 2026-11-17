# Viewer handoff (2026-11-17)

## Done
- V1-V3 (0.3.0, commit aa6f165 and later): proxy, page, deploy. Live on the Pi at http://192.168.0.12:8091/.
- 37 unit/integration tests and ruff are green. Playwright checks pass 30/30 on dev and 30/30 against the Pi, with the fake phone on dev,
  including phone off/on and a Pi service stop/start mid-stream.
- 3-viewer load test on the Pi with 249 KB fake frames: ~12 % of one core, 23 MB RSS (numbers in PLAN.md).

## State on the Pi
`/etc/camcast-viewer/upstream` currently points at the **fake phone on dev** (http://192.168.0.100:18080, throwaway
password); that fake is stopped, so the page shows "not reachable".

## Next (V4): needs CamCast running on the phone + its password
1. Stage the real credentials without writing them on dev (password typed by the owner):
   `ssh claude@192.168.0.12 'umask 077; cat > ~/camcast-viewer-staging/upstream'` with
   `CAMCAST_URL=http://192.168.0.15:8080`, `CAMCAST_USER=camcast`, `CAMCAST_PASS=...`, then
   `ssh claude@192.168.0.12 sudo -n bash ~/camcast-viewer-staging/deploy/install.sh`.
2. curl `/api/status`, `/video` (frame count over 10 s), `/snapshot.jpg`.
3. `node tests/browser/check.cjs --base http://192.168.0.12:8091 --shots out/shots-live` (no --outages).
4. Load: `ssh claude@192.168.0.12 'bash -s 205 5' < tests/browser/pi-sample.sh > out/load-live.csv &` +
   `node tests/browser/load.cjs`. Confirm the phone's own page/status shows 1 client.
5. VERSION 1.0.0, PLAN/README, Setup Record row (credentials now real), memory.

## Not verified
- Real phone stream (CamCast was off), real frame sizes, iOS Safari, a 24 h soak, behaviour across a Pi reboot.
- MemoryMax is not enforced (kernel `cgroup_disable=memory`); enabling it needs `cgroup_enable=memory` in
  /boot/firmware/cmdline.txt + a reboot (Pi-hole DNS goes down briefly): owner's call.
- PWA install / service worker: needs HTTPS, out of scope.
