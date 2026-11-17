# CamCast viewer plan

LAN-only web viewer on the Raspberry Pi 3B+ (192.168.0.12:8091), next to homelab-status (:8088). One MJPEG upstream to
the phone (http://192.168.0.15:8080, DHCP reservation), fanned out to up to 4 browsers, with no transcoding.

## Decisions (2026-11-17)
- Python 3.13 stdlib asyncio service `camcast-viewer`, deployed like homelab-status (git archive to staging, install.sh).
- Upstream on demand: starts with the first viewer and stops 15 s after the last one leaves. While idle, status uses a HEAD probe (at most every 30 s, only
  while a page polls).
- Clients: LAN 192.168.0.0/24, Tailscale 100.64.0.0/10 (added 2026-11-17 at the owner's request) and loopback. Cap 4 viewers (the phone allows 6).
- Credentials in `/etc/camcast-viewer/upstream` (root 0600), handed to the service with systemd `LoadCredential=`.
- No Kuma monitor and no homelab-status link (can be added on request).
- PWA: manifest + icons only. A service worker or real install needs HTTPS, which plain-HTTP LAN IPs don't have.

## Step 1 (Pi check, 2026-11-17)
905 MB RAM / 439 MB available, load 0.02, 5.2 GB disk free, eth0 1 Gb/s. Kuma (157 MB) is the largest process.
Verdict: fits.

## Milestones
- [x] **V1** hub + proxy core, fake CamCast, unit/integration tests (0.1.0)
- [x] **V2** page: live view, rotate, fullscreen, snapshot, status, EN/IT, manifest; Playwright checks (0.2.0)
- [x] **V3** deploy: install.sh + hardened unit, installed on the Pi (:8091) and verified with the fake phone on dev (0.3.0)
- [ ] **V4** live: real CamCast, curl + Playwright, Pi CPU/RAM with 3 viewers; then 1.0.0, Setup Record, memory

## Results
**V3 load test on the Pi (2026-11-17, fake phone on dev, worst-case 249 KB frames at 10 fps ≈ 2.5 MB/s in):**

| Viewers | Service CPU (% of one core) | Pi CPU (all cores) | MemAvailable | Temp |
|---|---|---|---|---|
| 0 (idle) | 0.1 | 1-2 % | 422-425 MB | 59 °C |
| 1 | 8.3-9.0 | 3-18 % | 391-423 MB | 59-60 °C |
| 3 | 11.4-13.3 | 5.5-25 % (spikes are the minutely collector/Kuma) | 379-421 MB | 59-61 °C |

Service RSS 22.9 MB, peak 23.3 MB. All 3 pages kept moving at 9.9-10 fps. Exposure score (`systemd-analyze security`) 3.0.
`MemoryMax` is not enforced: the Pi kernel runs with `cgroup_disable=memory`. `CPUQuota` is.
Raw CSV: `out/load-fake-heavy.csv` (not committed).
