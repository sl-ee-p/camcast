#!/usr/bin/env bash
# Dev only: start/stop the fake CamCast (:18080) and the viewer (:18091) on loopback for browser checks.
# Usage: tests/browser/servers.sh start [viewer args] | stop | stop-fake | restart-fake | stop-viewer | start-viewer
set -euo pipefail
cd "$(dirname "$0")/../.."
RUN=out/run
FAKE_PORT=18080
VIEW_PORT=18091

start_viewer() {
  # shellcheck disable=SC2046
  python3 -m camcast_viewer --bind 127.0.0.1 --port $VIEW_PORT --credentials $RUN/up $(cat $RUN/viewer.args) \
    >> $RUN/viewer.log 2>&1 &
  echo $! > $RUN/viewer.pid
  sleep 0.5
  kill -0 "$(cat $RUN/viewer.pid)" 2>/dev/null || { echo "viewer did not start (port $VIEW_PORT busy?)" >&2; exit 1; }
}

start_fake() {
  python3 -m tests.fake_camcast --host 127.0.0.1 --port $FAKE_PORT --password-file $RUN/pw \
    --frames-dir out/frames --fps "${FPS:-10}" >> $RUN/fake.log 2>&1 &
  echo $! > $RUN/fake.pid
  sleep 0.5
  kill -0 "$(cat $RUN/fake.pid)" 2>/dev/null || { echo "fake CamCast did not start (port $FAKE_PORT busy?)" >&2; exit 1; }
}

stop_pid() { [ -f "$1" ] && kill "$(cat "$1")" 2>/dev/null || true; rm -f "$1"; }

case "${1:-}" in
  start)
    shift
    mkdir -p $RUN && chmod 700 $RUN
    [ -d out/frames ] || { echo "run: node tests/browser/make-assets.cjs --frames out/frames" >&2; exit 1; }
    echo 'fakepw-123' > $RUN/pw
    printf 'CAMCAST_URL=http://127.0.0.1:%s\nCAMCAST_USER=camcast\nCAMCAST_PASS=fakepw-123\n' $FAKE_PORT > $RUN/up
    chmod 600 $RUN/pw $RUN/up
    start_fake
    echo "$@" > $RUN/viewer.args
    : > $RUN/viewer.log
    start_viewer
    sleep 1
    curl -sf http://127.0.0.1:$VIEW_PORT/healthz > /dev/null && echo "viewer http://127.0.0.1:$VIEW_PORT/ up"
    ;;
  stop) stop_pid $RUN/viewer.pid; stop_pid $RUN/fake.pid ;;
  stop-fake) stop_pid $RUN/fake.pid ;;
  stop-viewer) stop_pid $RUN/viewer.pid ;;
  start-viewer) start_viewer; sleep 1 ;;
  restart-fake) stop_pid $RUN/fake.pid; start_fake; sleep 0.5 ;;
  *) echo "usage: $0 start|stop|stop-fake|restart-fake|stop-viewer|start-viewer" >&2; exit 2 ;;
esac
