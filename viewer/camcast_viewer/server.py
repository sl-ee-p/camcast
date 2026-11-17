"""Minimal asyncio HTTP/1.1 server: one request per connection, an allowlist of routes, LAN clients only."""
import asyncio
import json
import logging
import os
import posixpath
import time
from contextlib import suppress
from http import HTTPStatus

from . import mjpeg, upstream
from .config import is_allowed
from .hub import Hub

log = logging.getLogger("camcast_viewer")

MAX_REQUEST_LINE = 2048
MAX_HEADERS = 50
STATIC_TYPES = {
    ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml", ".png": "image/png", ".ico": "image/x-icon",
    ".webmanifest": "application/manifest+json",
}
PAGES = {"/": "index.html", "/index.html": "index.html", "/manifest.webmanifest": "manifest.webmanifest"}
STATIC_PREFIX = "/static/"
COMMON_HEADERS = (
    "X-Content-Type-Options: nosniff\r\n"
    "Referrer-Policy: no-referrer\r\n"
    "X-Frame-Options: DENY\r\n"
)
PAGE_CSP = ("default-src 'self'; img-src 'self' blob:; style-src 'self'; script-src 'self'; "
            "connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")


class BadRequest(Exception):
    def __init__(self, status: int):
        self.status = status


def static_file(web_dir: str | None, path: str) -> str | None:
    """Map a request path to a file in web_dir, or None. No dotfiles, no traversal, known types only."""
    if not web_dir:
        return None
    if path in PAGES:
        rel = PAGES[path]
    elif path.startswith(STATIC_PREFIX):
        rel = path[len(STATIC_PREFIX):]
    else:
        return None
    parts = rel.split("/")
    if any(p in ("", ".", "..") or p.startswith(".") or "\\" in p for p in parts):
        return None
    if posixpath.splitext(rel)[1] not in STATIC_TYPES:
        return None
    root = os.path.realpath(web_dir)
    full = os.path.realpath(os.path.join(root, *parts))
    if not full.startswith(root + os.sep) or not os.path.isfile(full):
        return None
    return full


async def read_request(reader: asyncio.StreamReader) -> tuple[str, str, dict[str, str]]:
    try:
        line = await reader.readuntil(b"\n")
    except asyncio.LimitOverrunError:
        raise BadRequest(414) from None
    if len(line) > MAX_REQUEST_LINE:
        raise BadRequest(414)
    parts = line.decode("latin-1").split()
    if len(parts) != 3 or not parts[2].startswith("HTTP/1."):
        raise BadRequest(400)
    method, target, _ = parts
    headers = {}
    for _ in range(MAX_HEADERS):
        raw = await reader.readuntil(b"\n")
        if len(raw) > MAX_REQUEST_LINE:
            raise BadRequest(431)
        text = raw.decode("latin-1").strip()
        if not text:
            return method, target, headers
        name, _, value = text.partition(":")
        headers[name.strip().lower()] = value.strip()
    raise BadRequest(431)


class ViewerServer:
    def __init__(self, hub: Hub, *, allow, web_dir: str | None = None, version: str = "dev",
                 request_timeout=10.0, write_timeout=10.0):
        self.hub = hub
        self.allow = allow
        self.web_dir = web_dir
        self.version = version
        self.request_timeout = request_timeout
        self.write_timeout = write_timeout

    async def start(self, host: str, port: int) -> asyncio.Server:
        return await asyncio.start_server(self.handle, host, port, limit=8192)

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername") or ("?", 0)
        try:
            # Read the request even from refused clients: closing with unread data would send a reset, not the 403.
            try:
                method, target, headers = await asyncio.wait_for(read_request(reader), self.request_timeout)
            except BadRequest as error:
                await self._text(writer, error.status, HTTPStatus(error.status).phrase)
                return
            if not is_allowed(peer[0], self.allow):
                await self._text(writer, 403, "Forbidden: LAN only")
                return
            await self._route(reader, writer, method, target.split("?", 1)[0])
        except (ConnectionError, TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
            pass
        except Exception:
            log.exception("request from %s failed", peer[0])
        finally:
            writer.close()
            with suppress(OSError, TimeoutError):
                await asyncio.wait_for(writer.wait_closed(), 1)

    async def _route(self, reader, writer, method: str, path: str) -> None:
        if method not in ("GET", "HEAD"):
            await self._text(writer, 405, "Method Not Allowed", head=method == "HEAD", extra="Allow: GET, HEAD\r\n")
            return
        head = method == "HEAD"
        if path == "/video":
            await self._video(reader, writer, head)
        elif path == "/snapshot.jpg":
            await self._snapshot(writer, head)
        elif path == "/api/status":
            body = json.dumps({**self.hub.status(), "version": self.version}).encode()
            await self._send(writer, 200, body, "application/json", head=head)
        elif path == "/healthz":
            await self._text(writer, 200, "ok", head=head)
        else:
            full = static_file(self.web_dir, path)
            if full is None:
                await self._text(writer, 404, "Not Found", head=head)
                return
            with open(full, "rb") as handle:
                body = handle.read()
            content_type = STATIC_TYPES[posixpath.splitext(full)[1]]
            extra = f"Content-Security-Policy: {PAGE_CSP}\r\n" if content_type.startswith("text/html") else ""
            await self._send(writer, 200, body, content_type, head=head, cache="no-cache", extra=extra)

    # --- responses ------------------------------------------------------------

    async def _send(self, writer, status: int, body: bytes, content_type: str, *, head=False,
                    cache="no-store", extra="") -> None:
        writer.write(
            (f"HTTP/1.1 {status} {HTTPStatus(status).phrase}\r\nContent-Type: {content_type}\r\n"
             f"Content-Length: {len(body)}\r\nCache-Control: {cache}\r\n{COMMON_HEADERS}{extra}"
             "Connection: close\r\n\r\n"
             ).encode("latin-1")
        )
        if not head:
            writer.write(body)
        await asyncio.wait_for(writer.drain(), self.write_timeout)

    async def _text(self, writer, status: int, text: str, *, head=False, extra="") -> None:
        await self._send(writer, status, (text + "\n").encode(), "text/plain; charset=utf-8", head=head, extra=extra)

    async def _snapshot(self, writer, head: bool) -> None:
        try:
            frame = await self.hub.snapshot()
        except upstream.UpstreamError as error:
            await self._text(writer, 503, f"Camera unavailable ({error.reason})", head=head, extra="Retry-After: 5\r\n")
            return
        name = time.strftime("camcast-%Y%m%d-%H%M%S.jpg", time.localtime(frame.wall))
        await self._send(writer, 200, frame.jpeg, "image/jpeg", head=head,
                         extra=f'Content-Disposition: attachment; filename="{name}"\r\n')

    async def _video(self, reader, writer, head: bool) -> None:
        viewer = self.hub.join()
        if viewer is None:
            await self._text(writer, 503, "Too many viewers", head=head, extra="Retry-After: 10\r\n")
            return
        eof = None
        try:
            writer.write(
                (f"HTTP/1.1 200 OK\r\nContent-Type: {mjpeg.CONTENT_TYPE}\r\nCache-Control: no-store\r\n"
                 f"{COMMON_HEADERS}Connection: close\r\n\r\n").encode("latin-1")
            )
            await asyncio.wait_for(writer.drain(), self.write_timeout)
            if head:
                return
            # The browser sends nothing after the request, so a finished read means it went away.
            eof = asyncio.ensure_future(reader.read(1))
            while True:
                waiter = asyncio.ensure_future(viewer.event.wait())
                await asyncio.wait({waiter, eof}, return_when=asyncio.FIRST_COMPLETED)
                if eof.done():
                    waiter.cancel()
                    return
                frame = viewer.take()
                writer.write(mjpeg.part_header(len(frame.jpeg)))
                writer.write(frame.jpeg)
                writer.write(b"\r\n")
                await asyncio.wait_for(writer.drain(), self.write_timeout)
        finally:
            if eof:
                eof.cancel()
                if eof.done() and not eof.cancelled():
                    eof.exception()  # a reset client is normal; mark the error as seen
            self.hub.leave(viewer)
