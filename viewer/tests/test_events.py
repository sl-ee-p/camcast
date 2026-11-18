"""Motion events and clips: /api/events (cached, sanitised) and the /clips/* relay to the phone."""
import asyncio
import json

from camcast_viewer.events import sanitize
from camcast_viewer.server import ViewerServer
from tests.fake_camcast import make_frames
from tests.helpers import LOOPBACK, ProxyCase, http_get

CLIP = "20261118-191939"


class EventsTest(ProxyCase):
    async def asyncSetUp(self):
        await super().asyncSetUp()
        self.frames = make_frames(5, size=3_000)
        self.fake.clips[CLIP] = (b"\xff\xd8thumb\xff\xd9", self.frames)

    def phone_calls(self, path):
        return sum(1 for _, p in self.fake.requests if p == path)

    async def test_events_are_passed_on_and_cached(self):
        self.fake.events_extra = {"motion": True, "recording": True, "recordingSince": 123, "lastMotion": 456}
        status, headers, body, _ = await http_get(self.port, "/api/events")
        self.assertEqual(status, 200)
        self.assertEqual(headers["content-type"], "application/json")
        data = json.loads(body)
        self.assertEqual((data["available"], data["motion"], data["recording"]), (True, True, True))
        self.assertEqual((data["recordingSince"], data["lastMotion"]), (123, 456))
        self.assertEqual([c["id"] for c in data["clips"]], [CLIP])
        self.assertEqual(data["clips"][0]["frames"], 5)
        await asyncio.gather(*(http_get(self.port, "/api/events") for _ in range(5)))
        self.assertEqual(self.phone_calls("/events.json"), 1)  # shared within the cache time

    async def test_phone_down_says_so(self):
        await self.fake.stop()
        data = json.loads((await http_get(self.port, "/api/events"))[2])
        self.assertEqual(data, {"available": False, "reason": "unreachable"})

    def test_sanitize_drops_odd_ids_and_types(self):
        data = sanitize({"motion": "yes", "lastMotion": True, "recordingSince": 5, "clips": [
            {"id": "../../etc/passwd"}, {"id": 7}, "x", {"id": CLIP, "frames": "9", "end": 3, "extra": "<b>"}]})
        self.assertFalse(data["motion"])
        self.assertIsNone(data["lastMotion"])
        self.assertEqual(data["recordingSince"], 5)
        self.assertEqual(data["clips"], [{"id": CLIP, "start": 0, "motion": 0, "end": 3, "frames": 0, "bytes": 0,
                                          "width": 0, "height": 0}])

    async def test_thumbnail(self):
        status, headers, body, _ = await http_get(self.port, f"/clips/{CLIP}.jpg")
        self.assertEqual((status, body), (200, b"\xff\xd8thumb\xff\xd9"))
        self.assertIn("immutable", headers["cache-control"])

    async def test_download_is_the_phone_file_unchanged(self):
        status, headers, body, _ = await http_get(self.port, f"/clips/{CLIP}.mjpeg")
        self.assertEqual(status, 200)
        self.assertEqual(body, self.fake._clip_bytes(self.frames))
        self.assertEqual(int(headers["content-length"]), len(body))
        self.assertEqual(headers["content-disposition"], f'attachment; filename="camcast-{CLIP}.mjpeg"')

    async def test_play_relays_the_multipart_replay(self):
        status, headers, body, _ = await http_get(self.port, f"/clips/{CLIP}/play")
        self.assertEqual(status, 200)
        self.assertTrue(headers["content-type"].startswith("multipart/x-mixed-replace"))
        for frame in self.frames:
            self.assertIn(frame, body)

    async def test_unknown_and_malformed_clips(self):
        self.assertEqual((await http_get(self.port, "/clips/20200101-000000.jpg"))[0], 404)
        before = len(self.fake.requests)
        for path in ("/clips/../events.json", "/clips/x.jpg", f"/clips/{CLIP}.json", f"/clips/{CLIP}/../../video"):
            self.assertEqual((await http_get(self.port, path))[0], 404, path)
        self.assertEqual(len(self.fake.requests), before)  # never forwarded

    async def test_phone_down_clip_is_503(self):
        await self.fake.stop()
        self.assertEqual((await http_get(self.port, f"/clips/{CLIP}.jpg"))[0], 503)

    async def test_clip_streams_are_capped(self):
        self.fake.clips[CLIP] = (b"t", make_frames(200, size=3_000))  # ~4 s replay
        viewer = ViewerServer(self.hub, allow=LOOPBACK, max_clip_streams=1)
        server = await viewer.start("127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            reader, writer = await asyncio.open_connection("127.0.0.1", port)
            writer.write(f"GET /clips/{CLIP}/play HTTP/1.1\r\n\r\n".encode())
            self.assertTrue((await reader.readuntil(b"\r\n")).startswith(b"HTTP/1.1 200"))
            self.assertEqual((await http_get(port, f"/clips/{CLIP}.mjpeg"))[0], 503)
            writer.close()  # leaving frees the slot
            await self.wait_for(lambda: not viewer.clip_slots.locked())
            self.assertEqual((await http_get(port, f"/clips/{CLIP}.jpg"))[0], 200)
        finally:
            server.close()
            server.close_clients()
            await server.wait_closed()
