"""One upstream connection, fanned out to every viewer. Runs only while someone watches."""
import asyncio
import logging
import random
import time
from collections import deque
from contextlib import aclosing
from dataclasses import dataclass

from . import upstream
from .config import Upstream

log = logging.getLogger("camcast_viewer")

FPS_WINDOW = 5.0
# A frame older than this is not "live": fps drops to 0 and snapshots fetch a fresh one.
FRESH = 2.0


@dataclass(frozen=True, slots=True)
class Frame:
    seq: int
    jpeg: bytes
    at: float  # monotonic arrival time
    wall: float  # time.time() at arrival, for snapshot file names


class Viewer:
    """A one-slot mailbox: a slow viewer skips frames instead of queueing them."""
    __slots__ = ("frame", "event")

    def __init__(self):
        self.frame: Frame | None = None
        self.event = asyncio.Event()

    def offer(self, frame: Frame) -> None:
        self.frame = frame
        self.event.set()

    def take(self) -> Frame | None:
        self.event.clear()
        return self.frame


class Hub:
    def __init__(self, up: Upstream, *, max_viewers=4, linger=15.0, stall_timeout=10.0, connect_timeout=5.0,
                 backoff_base=1.0, backoff_max=30.0, probe_interval=30.0, rng=random.random, clock=time.monotonic):
        self.up = up
        self.max_viewers = max_viewers
        self.linger = linger
        self.stall_timeout = stall_timeout
        self.connect_timeout = connect_timeout
        self.backoff_base = backoff_base
        self.backoff_max = backoff_max
        self.probe_interval = probe_interval
        self._rng = rng
        self._clock = clock
        self._viewers: set[Viewer] = set()
        self._task: asyncio.Task | None = None
        self._linger_handle: asyncio.TimerHandle | None = None
        self._state = "standby"
        self._reason: str | None = None
        self._seq = 0
        self._times: deque[float] = deque()
        self.latest: Frame | None = None
        self._probe_task: asyncio.Task | None = None
        self._probe_at = float("-inf")
        self._probe_ok: bool | None = None
        self._probe_reason: str | None = None
        self._snapshot_task: asyncio.Task | None = None

    # --- viewers -------------------------------------------------------------

    @property
    def viewers(self) -> int:
        return len(self._viewers)

    def join(self) -> Viewer | None:
        if len(self._viewers) >= self.max_viewers:
            return None
        viewer = Viewer()
        self._viewers.add(viewer)
        if self._linger_handle:
            self._linger_handle.cancel()
            self._linger_handle = None
        if self._task is None:
            self._state, self._reason = "connecting", None
            self._task = asyncio.create_task(self._run(), name="camcast-upstream")
            log.info("upstream: starting for the first viewer")
        frame = self.fresh_frame()
        if frame:
            viewer.offer(frame)
        return viewer

    def leave(self, viewer: Viewer) -> None:
        self._viewers.discard(viewer)
        if not self._viewers and self._task and not self._linger_handle:
            self._linger_handle = asyncio.get_running_loop().call_later(self.linger, self._stop_if_unused)

    def _stop_if_unused(self) -> None:
        self._linger_handle = None
        if self._viewers or self._task is None:
            return
        self._task.cancel()
        self._task = None
        self._state, self._reason = "standby", None
        self._times.clear()
        # The phone answered a moment ago; treat it as reachable until the next probe says otherwise.
        self._probe_ok, self._probe_at = True, self._clock()
        log.info("upstream: stopped, no viewers for %.0f s", self.linger)

    # --- upstream loop -------------------------------------------------------

    def backoff(self, attempt: int) -> float:
        delay = min(self.backoff_max, self.backoff_base * 2 ** max(0, attempt - 1))
        return delay * (0.8 + 0.4 * self._rng())

    async def _run(self) -> None:
        attempt = 0
        while True:
            self._state = "connecting"
            try:
                async with aclosing(upstream.frames(self.up, self.connect_timeout, self.stall_timeout)) as stream:
                    async for jpeg in stream:
                        if attempt or self._state != "online":
                            log.info("upstream: online")
                        attempt = 0
                        self._state, self._reason = "online", None
                        self._publish(jpeg)
            except upstream.UpstreamError as error:
                self._reason = error.reason
            except Exception:
                log.exception("upstream: unexpected error")
                self._reason = "error"
            attempt += 1
            self._state = "offline"
            delay = self.backoff(attempt)
            log.warning("upstream: %s, retry %d in %.1f s", self._reason, attempt, delay)
            await asyncio.sleep(delay)

    def _publish(self, jpeg: bytes) -> None:
        now = self._clock()
        self._seq += 1
        frame = Frame(self._seq, jpeg, now, time.time())
        self.latest = frame
        self._times.append(now)
        while self._times and now - self._times[0] > FPS_WINDOW:
            self._times.popleft()
        for viewer in self._viewers:
            viewer.offer(frame)

    # --- status, probe, snapshot --------------------------------------------

    def fresh_frame(self) -> Frame | None:
        frame = self.latest
        if frame and self._state == "online" and self._clock() - frame.at <= FRESH:
            return frame
        return None

    def fps(self) -> float:
        now = self._clock()
        times = [t for t in self._times if now - t <= FPS_WINDOW]
        if len(times) < 2 or now - times[-1] > FRESH:
            return 0.0
        return round((len(times) - 1) / (times[-1] - times[0]), 1)

    def _maybe_probe(self) -> None:
        if self._task or self._probe_task or self._clock() - self._probe_at < self.probe_interval:
            return
        self._probe_task = asyncio.create_task(self._probe(), name="camcast-probe")

    async def _probe(self) -> None:
        try:
            await upstream.probe(self.up, self.connect_timeout)
            self._probe_ok, self._probe_reason = True, None
        except upstream.UpstreamError as error:
            self._probe_ok, self._probe_reason = False, error.reason
        finally:
            self._probe_at = self._clock()
            self._probe_task = None

    def status(self) -> dict:
        """Called by /api/status. While idle it also schedules a cheap probe (at most every probe_interval)."""
        self._maybe_probe()
        if self._task:
            state, reason = self._state, self._reason
        elif self._probe_ok is None:
            state, reason = "unknown", None
        else:
            state, reason = ("standby", None) if self._probe_ok else ("offline", self._probe_reason)
        frame = self.latest
        return {
            "state": state,
            "reason": reason,
            "fps": self.fps() if self._task else 0.0,
            "viewers": len(self._viewers),
            "max_viewers": self.max_viewers,
            "frame_age": round(self._clock() - frame.at, 1) if frame else None,
        }

    async def snapshot(self) -> Frame:
        """The live frame if there is one, else a single fetch from the phone (shared by concurrent callers)."""
        frame = self.fresh_frame()
        if frame:
            return frame
        if self._snapshot_task is None:
            self._snapshot_task = asyncio.create_task(self._fetch_snapshot(), name="camcast-snapshot")
        return await asyncio.shield(self._snapshot_task)

    async def _fetch_snapshot(self) -> Frame:
        try:
            jpeg = await upstream.snapshot(self.up, self.connect_timeout)
            return Frame(0, jpeg, self._clock(), time.time())
        finally:
            self._snapshot_task = None

    async def close(self) -> None:
        if self._linger_handle:
            self._linger_handle.cancel()
        for task in (self._task, self._probe_task, self._snapshot_task):
            if task:
                task.cancel()
        self._task = None
