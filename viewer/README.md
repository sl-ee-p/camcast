# CamCast viewer

A LAN-only web viewer for CamCast that runs on the Raspberry Pi. It keeps **one** MJPEG connection to the phone and
fans it out to up to 4 browsers. JPEG bytes pass through untouched (no transcoding). The Pi adds the basic-auth header,
so browsers never see the credentials. The upstream runs only while someone watches, plus a 15 s linger.

Python 3.13 standard library only.

## Run (dev)
```sh
python3 -m camcast_viewer --port 8091 --credentials FILE   # FILE: CAMCAST_URL=, CAMCAST_USER=, CAMCAST_PASS=
python3 -m tests.fake_camcast --port 8080 --password-file PWFILE   # stand-in for the phone
```

## Routes
`/` page, `/video` MJPEG, `/snapshot.jpg` (download), `/api/status` JSON, `/healthz`.

## Checks
```sh
python3 -m unittest            # full suite
ruff check .                   # lint (pyproject.toml)

# Browser checks (dev box): Playwright from homelab-twin's node_modules, Chromium from ~/.cache/ms-playwright
export NODE_PATH=~/projects/homelab-twin/node_modules
node tests/browser/make-assets.cjs --frames out/frames   # PNG icons + 720x1280 test JPEGs
tests/browser/servers.sh start                          # fake phone :18080 + viewer :18091 on loopback
node tests/browser/check.cjs --local                    # screenshots in out/shots
tests/browser/servers.sh stop
```

## Page
EN/IT (browser language, `?lang=it`, or the language button). Rotation and language are remembered per browser.
A hidden tab closes its stream, so it frees its viewer slot. Breakpoints are in `em`, so large text gets the compact
layout too.
