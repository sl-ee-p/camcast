import asyncio
import ipaddress
import json
import logging
import os

from camcast_viewer.hub import Hub
from camcast_viewer.server import ViewerServer
from tests.fake_camcast import frame_index, make_frames
from tests.helpers import LOOPBACK, ProxyCase, Stream, http_get


class FanOutTest(ProxyCase):
    async def test_three_viewers_share_one_upstream_and_get_unchanged_bytes(self):
        streams = [await self.stream() for _ in range(3)]
        for stream in streams:
            self.assertEqual(stream.status, 200)
            self.assertIn("multipart/x-mixed-replace; boundary=camcastview", stream.head)
            await stream.read(8)
        self.assertEqual(self.fake.video_total, 1)
        self.assertEqual(self.fake.video_peak, 1)
        known = set(self.fake.frames)
        for stream in streams:
            self.assertTrue(set(stream.frames) <= known)
        status = json.loads((await http_get(self.port, "/api/status"))[2])
        self.assertEqual((status["state"], status["viewers"], status["max_viewers"]), ("online", 3, 4))
        self.assertGreater(status["fps"], 10)
        self.assertEqual(status["version"], "test")

    async def test_cap_answers_503(self):
        for _ in range(4):
            await (await self.stream()).read(1)
        fifth = await self.stream()
        self.assertEqual(fifth.status, 503)
        self.assertIn("Retry-After", fifth.head)
        self.assertEqual(self.fake.video_total, 1)

    async def test_slow_viewer_skips_frames_and_does_not_hold_others(self):
        self.fake.frames = make_frames(256, 100_000)
        slow = await self.stream()  # reads nothing while the fast one takes 1.2 s of video
        fast = await self.stream()
        await fast.read(60, timeout=3)
        caught_up = [frame_index(f) for f in await slow.read(40, timeout=3)]
        gaps = [b - a for a, b in zip(caught_up, caught_up[1:], strict=False)]
        self.assertTrue(any(g > 1 for g in gaps), f"slow viewer got every frame: {caught_up}")
        self.assertEqual(self.fake.video_total, 1)

    async def test_upstream_stops_after_linger_and_restarts(self):
        stream = await self.stream()
        await stream.read(2)
        stream.close()
        self.streams.clear()
        await self.wait_for(lambda: self.fake.video_active == 0)
        status = json.loads((await http_get(self.port, "/api/status"))[2])
        self.assertEqual((status["state"], status["viewers"]), ("standby", 0))
        await (await self.stream()).read(2)
        self.assertEqual(self.fake.video_total, 2)

    async def test_viewer_leaving_is_noticed_without_frames(self):
        self.fake.stall_after = 1
        stream = await self.stream()
        await stream.read(1)
        stream.close()
        self.streams.clear()
        await self.wait_for(lambda: self.hub.viewers == 0)


class RecoveryTest(ProxyCase):
    hub_options = {"stall_timeout": 0.3}

    async def test_reconnects_when_the_phone_drops_and_viewers_stay(self):
        self.fake.drop_after = 5
        stream = await self.stream()
        await stream.read(15)
        self.assertGreaterEqual(self.fake.video_total, 3)
        self.assertEqual(self.fake.video_peak, 1)

    async def test_stall_triggers_reconnect(self):
        self.fake.stall_after = 3
        stream = await self.stream()
        await stream.read(3)
        await self.wait_for(lambda: self.fake.video_total >= 2)
        self.fake.stall_after = None
        await stream.read(3)

    async def test_phone_off_then_on(self):
        port = self.fake.port
        await self.fake.stop()
        stream = await self.stream()
        await self.wait_for(lambda: self.hub.status()["state"] == "offline")
        self.assertEqual(self.hub.status()["reason"], "unreachable")
        await self.fake.start(port)
        await stream.read(3)
        self.assertEqual(self.hub.status()["state"], "online")

    async def test_wrong_password_reason(self):
        self.fake.password = "changed"
        await self.stream()
        await self.wait_for(lambda: self.hub.status()["reason"] == "unauthorized")

    async def test_phone_busy_reason(self):
        self.fake.max_clients = 0
        await self.stream()
        await self.wait_for(lambda: self.hub.status()["reason"] == "busy")

    def test_backoff_grows_and_caps_with_jitter(self):
        hub = Hub(self.up, backoff_base=1, backoff_max=30, rng=lambda: 0.5)
        self.assertEqual([hub.backoff(n) for n in (1, 2, 3, 6, 10)], [1, 2, 4, 30, 30])
        low = Hub(self.up, rng=lambda: 0.0).backoff(1)
        high = Hub(self.up, rng=lambda: 1.0).backoff(1)
        self.assertEqual((round(low, 2), round(high, 2)), (0.8, 1.2))


class RoutesTest(ProxyCase):
    async def test_snapshot_when_idle_fetches_once(self):
        status, headers, body, _ = await http_get(self.port, "/snapshot.jpg")
        self.assertEqual(status, 200)
        self.assertEqual(body, self.fake.frames[0])
        self.assertEqual(headers["content-type"], "image/jpeg")
        self.assertRegex(headers["content-disposition"], r'attachment; filename="camcast-\d{8}-\d{6}\.jpg"')
        self.assertEqual(self.fake.video_total, 0)

    async def test_snapshot_while_live_uses_the_stream(self):
        await (await self.stream()).read(2)
        before = len(self.fake.requests)
        status, _, body, _ = await http_get(self.port, "/snapshot.jpg")
        self.assertEqual(status, 200)
        self.assertIn(body, self.fake.frames)
        self.assertEqual(len(self.fake.requests), before)

    async def test_snapshot_phone_down_is_503(self):
        await self.fake.stop()
        status, headers, body, _ = await http_get(self.port, "/snapshot.jpg")
        self.assertEqual(status, 503)
        self.assertIn(b"unreachable", body)

    async def test_idle_status_probes(self):
        first = json.loads((await http_get(self.port, "/api/status"))[2])
        self.assertEqual(first["state"], "unknown")
        await self.wait_for(lambda: ("HEAD", "/") in self.fake.requests)
        await self.wait_for(lambda: self.hub.status()["state"] == "standby")

    async def test_misc_routes(self):
        self.assertEqual((await http_get(self.port, "/healthz"))[0], 200)
        self.assertEqual((await http_get(self.port, "/nope"))[0], 404)
        self.assertEqual((await http_get(self.port, "/../etc/passwd"))[0], 404)
        self.assertEqual((await http_get(self.port, "/api/status", method="POST"))[0], 405)
        status, headers, body, _ = await http_get(self.port, "/api/status", method="HEAD")
        self.assertEqual((status, body), (200, b""))
        self.assertEqual(headers["x-content-type-options"], "nosniff")

    async def test_bad_request(self):
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        writer.write(b"nonsense\r\n\r\n")
        raw = await reader.read()
        writer.close()
        self.assertTrue(raw.startswith(b"HTTP/1.1 400"))

    async def test_lan_filter(self):
        hub = Hub(self.up)
        server = await ViewerServer(hub, allow=[ipaddress.ip_network("192.168.0.0/24")]).start("127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            status, _, body, _ = await http_get(port, "/video")
            self.assertEqual(status, 403)
            self.assertEqual(self.fake.requests, [])
        finally:
            server.close()
            await server.wait_closed()
            await hub.close()


class StaticTest(ProxyCase):
    web_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web")

    async def asyncSetUp(self):
        await super().asyncSetUp()
        self.server.close()
        await self.server.wait_closed()
        self.server = await ViewerServer(self.hub, allow=LOOPBACK, web_dir=self.web_dir).start("127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def test_page_and_assets(self):
        status, headers, body, _ = await http_get(self.port, "/")
        self.assertEqual(status, 200)
        self.assertIn("text/html", headers["content-type"])
        self.assertIn("script-src 'self'", headers["content-security-policy"])
        self.assertIn(b'src="/static/app.js"', body)
        for path, kind in (("/static/app.js", "text/javascript"), ("/static/app.css", "text/css"),
                           ("/static/i18n/it.json", "application/json"), ("/static/icon-192.png", "image/png"),
                           ("/static/icon.svg", "image/svg+xml"),
                           ("/manifest.webmanifest", "application/manifest+json")):
            status, headers, _, _ = await http_get(self.port, path)
            self.assertEqual((path, status), (path, 200))
            self.assertTrue(headers["content-type"].startswith(kind), path)

    async def test_no_escape_from_web_dir(self):
        for path in ("/static/../camcast_viewer/config.py", "/static/%2e%2e/VERSION", "/static/.hidden.js",
                     "/static/i18n//en.json", "/index.htm", "/static/", "/web/index.html", "/static/x.py"):
            self.assertEqual((path, (await http_get(self.port, path))[0]), (path, 404))


class SecretTest(ProxyCase):
    async def test_password_and_auth_never_leave_the_pi(self):
        secret = self.fake.password.encode()
        token = self.up.authorization().split()[1].encode()
        with self.assertLogs("camcast_viewer", level=logging.DEBUG) as logs:
            logging.getLogger("camcast_viewer").debug("marker")
            raws = []
            for path in ("/", "/api/status", "/snapshot.jpg", "/healthz", "/nope"):
                raws.append((await http_get(self.port, path))[3])
            stream = await self.stream()
            await stream.read(3)
            raws.append(stream.head.encode() + b"".join(stream.frames))
            self.fake.password = "rotated"  # forces 401 + reconnect logging
            self.fake.drop_after = 0
            await self.wait_for(lambda: self.hub.status()["reason"] in ("unauthorized", "closed"))
            raws.append((await http_get(self.port, "/api/status"))[3])
        for raw in raws:
            self.assertNotIn(secret, raw)
            self.assertNotIn(token, raw)
            self.assertNotIn(b"authorization", raw.lower())
            self.assertNotIn(b"camcast:", raw)
        text = "\n".join(logs.output)
        self.assertNotIn(self.fake.password, text)
        self.assertNotIn("s3cret-pass", text)
        self.assertNotIn(token.decode(), text)


class StreamHelperTest(ProxyCase):
    async def test_head_video(self):
        status, headers, body, _ = await http_get(self.port, "/video", method="HEAD")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"")
        await self.wait_for(lambda: self.hub.viewers == 0)

    async def test_stream_class_reads(self):
        stream = await Stream(self.port).open()
        self.streams.append(stream)
        self.assertEqual(len(await stream.read(2)), 2)
