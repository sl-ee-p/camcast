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
```
