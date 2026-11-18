"""Motion events from the phone (/events.json), cached briefly and shared by every page that polls."""
import asyncio
import json
import re
import time

from . import upstream
from .config import Upstream

# Same shape as the phone's clip ids: a timestamp, maybe with a -2 suffix. Safe in paths and URLs.
CLIP_ID = re.compile(r"[0-9]{8}-[0-9]{6}(?:-[0-9]{1,3})?")
MAX_EVENTS_BYTES = 256 * 1024
CLIP_FIELDS = ("start", "motion", "end", "frames", "bytes", "width", "height")


def _int_or_none(value):
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def sanitize(raw: dict) -> dict:
    """Keep only the fields the page uses, with the types it expects; drop clips with odd ids."""
    clips = []
    for clip in raw.get("clips") or []:
        if not isinstance(clip, dict) or not isinstance(clip.get("id"), str) or not CLIP_ID.fullmatch(clip["id"]):
            continue
        clips.append({"id": clip["id"], **{key: _int_or_none(clip.get(key)) or 0 for key in CLIP_FIELDS}})
    return {
        "available": True,
        "detection": raw.get("detection") is True,
        "motion": raw.get("motion") is True,
        "recording": raw.get("recording") is True,
        "recordingSince": _int_or_none(raw.get("recordingSince")),
        "lastMotion": _int_or_none(raw.get("lastMotion")),
        "phoneNow": _int_or_none(raw.get("now")),
        "clips": clips,
    }


class Events:
    def __init__(self, up: Upstream, *, ttl=2.0, timeout=5.0, clock=time.monotonic):
        self.up = up
        self.ttl = ttl
        self.timeout = timeout
        self._clock = clock
        self._lock = asyncio.Lock()
        self._value: dict | None = None
        self._at = 0.0

    async def get(self) -> dict:
        async with self._lock:  # pages polling at once share one request to the phone
            if self._value is not None and self._clock() - self._at < self.ttl:
                return self._value
            try:
                _, body = await upstream.fetch(self.up, "/events.json", self.timeout, MAX_EVENTS_BYTES)
                raw = json.loads(body)
                value = sanitize(raw) if isinstance(raw, dict) else {"available": False, "reason": "bad_response"}
            except upstream.UpstreamError as error:
                value = {"available": False, "reason": error.reason}
            except ValueError:
                value = {"available": False, "reason": "bad_response"}
            self._value = value
            self._at = self._clock()
            return value
