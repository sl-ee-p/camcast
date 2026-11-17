#!/usr/bin/env bash
# Install or update camcast-viewer. Run as root on the Pi; safe to re-run.
# A staged `upstream` file (CAMCAST_URL=, CAMCAST_USER=, CAMCAST_PASS=) replaces the stored one and is deleted.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Run with sudo." >&2; exit 1; }
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USER_NAME=camcast-viewer
UNIT=camcast-viewer.service
PORT=8091

echo "==> Service user"
id "$USER_NAME" >/dev/null 2>&1 || \
  useradd --system --user-group --no-create-home --shell /usr/sbin/nologin "$USER_NAME"

echo "==> Code"
install -d -m 755 -o root -g root /opt/camcast-viewer
rm -rf /opt/camcast-viewer/camcast_viewer /opt/camcast-viewer/web
cp -r "$SRC/camcast_viewer" "$SRC/web" "$SRC/VERSION" /opt/camcast-viewer/
find /opt/camcast-viewer -name __pycache__ -type d -prune -exec rm -rf {} +
chown -R root:root /opt/camcast-viewer
chmod -R u=rwX,go=rX /opt/camcast-viewer

echo "==> Upstream credentials"
install -d -m 700 -o root -g root /etc/camcast-viewer
if [ -f "$SRC/upstream" ]; then
  install -m 600 -o root -g root "$SRC/upstream" /etc/camcast-viewer/upstream
  rm -f "$SRC/upstream"
elif [ ! -f /etc/camcast-viewer/upstream ]; then
  echo "No upstream file in $SRC or /etc/camcast-viewer." >&2
  exit 1
fi
# Fail before touching the running service if the file is malformed (prints the URL, never the password).
(cd /opt/camcast-viewer && python3 -B -c \
  "from camcast_viewer.config import load_upstream; print('upstream', load_upstream('/etc/camcast-viewer/upstream').label())")

echo "==> systemd unit"
install -m 644 -o root -g root "$SRC/deploy/$UNIT" /etc/systemd/system/$UNIT
systemctl daemon-reload
systemctl enable $UNIT
systemctl restart $UNIT
sleep 2

echo "==> Result"
systemctl is-active $UNIT
curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null && echo "healthz ok"
journalctl -u $UNIT -n 3 --no-pager -o cat
ls -l /etc/camcast-viewer
echo "Done: http://192.168.0.12:$PORT/"
