"""A stand-in for CamCast's HTTP server: same routes, basic auth, framing and 503 when busy.

Tests drive it in-process. For browser checks it also runs alone:
    python3 -m tests.fake_camcast --port 8080 --password-file FILE [--frames-dir DIR] [--fps 10]
"""
import argparse
import asyncio
import base64
import contextlib
import glob
import hmac
import os
import sys

BOUNDARY = b"camcastframe"


def make_frames(count=4, size=50_000) -> list[bytes]:
    """Fake JPEGs: SOI, a 4-byte index (see frame_index), filler, EOI. Distinct, so pass-through is checkable."""
    return [b"\xff\xd8" + i.to_bytes(4, "big") + bytes([i & 0xFF]) * (size - 8) + b"\xff\xd9" for i in range(count)]


def frame_index(jpeg: bytes) -> int:
    return int.from_bytes(jpeg[2:6], "big")


class FakeCamCast:
    def __init__(self, user="camcast", password="s3cret-pass", frames=None, fps=50.0, max_clients=6):
        self.user = user
        self.password = password
        self.frames = frames or make_frames()
        self.fps = fps
        self.max_clients = max_clients
        self.drop_after: int | None = None  # close each /video connection after n frames
        self.stall_after: int | None = None  # stop sending after n frames, keep the socket open
        self.video_total = 0
        self.video_active = 0
        self.video_peak = 0
        self.clients = 0
        self.requests: list[tuple[str, str]] = []
        self._server: asyncio.Server | None = None
        self.port = 0

    async def start(self, port=0, host="127.0.0.1") -> int:
        self._server = await asyncio.start_server(self._handle, host, port)
        self.port = self._server.sockets[0].getsockname()[1]
        return self.port

    async def stop(self) -> None:
        if self._server:
            self._server.close()
            self._server.close_clients()
            await self._server.wait_closed()
            self._server = None

    def _authorized(self, value: str | None) -> bool:
        if not value or not value.lower().startswith("basic "):
            return False
        try:
            decoded = base64.b64decode(value[6:].strip(), validate=True)
        except ValueError:
            return False
        return hmac.compare_digest(decoded, f"{self.user}:{self.password}".encode())

    async def _handle(self, reader, writer):
        self.clients += 1
        try:
            if self.clients > self.max_clients:
                writer.write(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                await writer.drain()
                return
            line = (await reader.readuntil(b"\n")).decode().split()
            headers = {}
            while True:
                raw = (await reader.readuntil(b"\n")).decode().strip()
                if not raw:
                    break
                name, _, value = raw.partition(":")
                headers[name.strip().lower()] = value.strip()
            method, path = line[0], line[1]
            self.requests.append((method, path))
            if not self._authorized(headers.get("authorization")):
                writer.write(b'HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm="CamCast"\r\n'
                             b"Content-Length: 0\r\nConnection: close\r\n\r\n")
            elif path == "/video":
                await self._video(writer)
            elif path == "/snapshot.jpg":
                jpeg = self.frames[0]
                writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n"
                             b"Connection: close\r\n\r\n" % len(jpeg))
                if method != "HEAD":
                    writer.write(jpeg)
            elif path == "/":
                body = b"<h1>CamCast</h1>"
                writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: %d\r\n"
                             b"Connection: close\r\n\r\n" % len(body))
                if method != "HEAD":
                    writer.write(body)
            else:
                writer.write(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            await writer.drain()
        except (ConnectionError, asyncio.IncompleteReadError, IndexError):
            pass
        finally:
            self.clients -= 1
            writer.close()

    async def _video(self, writer):
        self.video_total += 1
        self.video_active += 1
        self.video_peak = max(self.video_peak, self.video_active)
        try:
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=camcastframe\r\n"
                         b"Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
            sent = 0
            while True:
                if self.drop_after is not None and sent >= self.drop_after:
                    return
                if self.stall_after is not None and sent >= self.stall_after:
                    await asyncio.sleep(3600)
                jpeg = self.frames[sent % len(self.frames)]
                writer.write(b"--%s\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\nX-Timestamp: %d\r\n\r\n"
                             % (BOUNDARY, len(jpeg), sent))
                writer.write(jpeg)
                writer.write(b"\r\n")
                await writer.drain()
                sent += 1
                await asyncio.sleep(1 / self.fps)
        finally:
            self.video_active -= 1


async def _main(args) -> None:
    with open(args.password_file, encoding="utf-8") as handle:
        password = handle.read().strip()
    frames = None
    if args.frames_dir:
        frames = []
        for path in sorted(glob.glob(os.path.join(args.frames_dir, "*.jpg"))):
            with open(path, "rb") as handle:
                frames.append(handle.read())
        if not frames:
            sys.exit(f"no .jpg files in {args.frames_dir}")
    fake = FakeCamCast(password=password, frames=frames, fps=args.fps)
    await fake.start(args.port, args.host)
    print(f"fake CamCast on {args.host}:{fake.port}, {len(fake.frames)} frames at {args.fps} fps", flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--password-file", required=True)
    parser.add_argument("--frames-dir")
    parser.add_argument("--fps", type=float, default=10.0)
    with contextlib.suppress(KeyboardInterrupt):
        asyncio.run(_main(parser.parse_args()))
