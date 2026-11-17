"""Shared fixtures: a fake CamCast plus a viewer server on free loopback ports, and raw HTTP clients."""
import asyncio
import ipaddress
import unittest

from camcast_viewer import mjpeg
from camcast_viewer.config import Upstream
from camcast_viewer.hub import Hub
from camcast_viewer.server import ViewerServer
from tests.fake_camcast import FakeCamCast

LOOPBACK = [ipaddress.ip_network("127.0.0.0/8")]


async def http_get(port, path, method="GET", headers=None):
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    extra = "".join(f"{k}: {v}\r\n" for k, v in (headers or {}).items())
    writer.write(f"{method} {path} HTTP/1.1\r\nHost: test\r\n{extra}\r\n".encode())
    raw = await asyncio.wait_for(reader.read(), 10)
    writer.close()
    head, _, body = raw.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    status = int(lines[0].split()[1])
    hdrs = {k.strip().lower(): v.strip() for k, _, v in (line.partition(":") for line in lines[1:])}
    return status, hdrs, body, raw


class Stream:
    """A raw /video client that reads parts with the same parser the hub uses."""

    def __init__(self, port):
        self.port = port
        self.frames: list[bytes] = []

    async def open(self):
        self.reader, self.writer = await asyncio.open_connection("127.0.0.1", self.port)
        self.writer.write(b"GET /video HTTP/1.1\r\nHost: test\r\n\r\n")
        head = await asyncio.wait_for(self.reader.readuntil(b"\r\n\r\n"), 5)
        self.head = head.decode("latin-1")
        self.status = int(self.head.split()[1])
        return self

    async def read(self, n, timeout=5.0):
        for _ in range(n):
            self.frames.append(await asyncio.wait_for(mjpeg.read_part(self.reader, mjpeg.BOUNDARY), timeout))
        return self.frames[-n:]

    def close(self):
        self.writer.close()


class ProxyCase(unittest.IsolatedAsyncioTestCase):
    """Fast timings so reconnects, stalls and the linger run in well under a second."""
    hub_options = {}

    async def asyncSetUp(self):
        self.fake = FakeCamCast()
        await self.fake.start()
        self.up = Upstream("127.0.0.1", self.fake.port, "", self.fake.user, self.fake.password)
        options = {"linger": 0.3, "stall_timeout": 0.5, "connect_timeout": 1.0, "backoff_base": 0.05,
                   "backoff_max": 0.2, "probe_interval": 0.0, **self.hub_options}
        self.hub = Hub(self.up, **options)
        self.server = await ViewerServer(self.hub, allow=LOOPBACK, web_dir=None, version="test").start("127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]
        self.streams: list[Stream] = []

    async def asyncTearDown(self):
        for stream in self.streams:
            stream.close()
        await self.hub.close()
        self.server.close()
        self.server.close_clients()
        await self.server.wait_closed()
        await self.fake.stop()

    async def stream(self):
        stream = await Stream(self.port).open()
        self.streams.append(stream)
        return stream

    async def wait_for(self, predicate, timeout=3.0, step=0.02):
        loop = asyncio.get_running_loop()
        end = loop.time() + timeout
        while not predicate():
            if loop.time() > end:
                self.fail("condition not met in time")
            await asyncio.sleep(step)
