"""HTTP/1.1 client for CamCast: the MJPEG stream, a one-shot snapshot and a reachability probe."""
import asyncio
from collections.abc import AsyncIterator
from contextlib import suppress

from . import mjpeg
from .config import Upstream

MAX_SNAPSHOT = mjpeg.MAX_PART
MAX_HEADERS = 64


class UpstreamError(Exception):
    """`reason` is a short code safe to show in the page: it never carries hosts or credentials."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


async def _request(up: Upstream, path: str, method: str, timeout: float):
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(up.host, up.port), timeout)
    except (OSError, TimeoutError):
        raise UpstreamError("unreachable") from None
    try:
        writer.write(
            (f"{method} {up.prefix}{path} HTTP/1.1\r\nHost: {up.host}:{up.port}\r\n"
             f"Authorization: {up.authorization()}\r\nUser-Agent: camcast-viewer\r\nConnection: close\r\n\r\n"
             ).encode("latin-1")
        )
        status, headers = await asyncio.wait_for(_read_head(reader), timeout)
    except (OSError, TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError, ValueError):
        writer.close()
        raise UpstreamError("unreachable") from None
    except BaseException:
        writer.close()
        raise
    if status != 200:
        writer.close()
        raise UpstreamError({401: "unauthorized", 404: "not_found", 503: "busy"}.get(status, "bad_response"))
    return reader, writer, headers


async def _read_head(reader: asyncio.StreamReader) -> tuple[int, dict[str, str]]:
    status_line = (await reader.readuntil(b"\n")).decode("latin-1").split()
    if len(status_line) < 2 or not status_line[0].startswith("HTTP/"):
        raise ValueError("bad status line")
    headers = {}
    for _ in range(MAX_HEADERS):
        line = (await reader.readuntil(b"\n")).decode("latin-1").strip()
        if not line:
            return int(status_line[1]), headers
        name, _, value = line.partition(":")
        headers[name.strip().lower()] = value.strip()
    raise ValueError("too many headers")


async def _close(writer: asyncio.StreamWriter) -> None:
    writer.close()
    with suppress(OSError, TimeoutError):
        await asyncio.wait_for(writer.wait_closed(), 1)


async def frames(up: Upstream, connect_timeout: float, stall_timeout: float) -> AsyncIterator[bytes]:
    """Yield JPEG frames from /video until the stream ends, stalls or breaks (raises UpstreamError)."""
    reader, writer, headers = await _request(up, "/video", "GET", connect_timeout)
    try:
        try:
            boundary = mjpeg.boundary_from(headers.get("content-type", ""))
        except mjpeg.MjpegError:
            raise UpstreamError("bad_response") from None
        while True:
            try:
                yield await asyncio.wait_for(mjpeg.read_part(reader, boundary), stall_timeout)
            except TimeoutError:
                raise UpstreamError("stalled") from None
            except EOFError:
                raise UpstreamError("closed") from None
            except mjpeg.MjpegError:
                raise UpstreamError("bad_response") from None
            except OSError:
                raise UpstreamError("unreachable") from None
    finally:
        await _close(writer)


async def snapshot(up: Upstream, timeout: float) -> bytes:
    reader, writer, headers = await _request(up, "/snapshot.jpg", "GET", timeout)
    try:
        try:
            length = int(headers.get("content-length", ""))
        except ValueError:
            raise UpstreamError("bad_response") from None
        if not 0 < length <= MAX_SNAPSHOT:
            raise UpstreamError("bad_response")
        try:
            return await asyncio.wait_for(reader.readexactly(length), timeout)
        except (OSError, TimeoutError, asyncio.IncompleteReadError):
            raise UpstreamError("unreachable") from None
    finally:
        await _close(writer)


async def fetch(up: Upstream, path: str, timeout: float, max_bytes: int) -> tuple[dict[str, str], bytes]:
    """GET a small resource (events JSON, a thumbnail) whole."""
    reader, writer, headers = await _request(up, path, "GET", timeout)
    try:
        try:
            length = int(headers.get("content-length", ""))
        except ValueError:
            raise UpstreamError("bad_response") from None
        if not 0 <= length <= max_bytes:
            raise UpstreamError("bad_response")
        try:
            return headers, await asyncio.wait_for(reader.readexactly(length), timeout)
        except (OSError, TimeoutError, asyncio.IncompleteReadError):
            raise UpstreamError("unreachable") from None
    finally:
        await _close(writer)


async def open_stream(up: Upstream, path: str, timeout: float):
    """GET a long response (clip download or replay): returns (reader, writer, headers); close with close()."""
    return await _request(up, path, "GET", timeout)


async def close(writer: asyncio.StreamWriter) -> None:
    await _close(writer)


async def probe(up: Upstream, timeout: float) -> None:
    """HEAD / with credentials: returns if the phone answers 200, raises UpstreamError otherwise."""
    _, writer, _ = await _request(up, "/", "HEAD", timeout)
    await _close(writer)
