import asyncio
import unittest

from camcast_viewer import mjpeg


def reader_with(data: bytes, chunk=7) -> asyncio.StreamReader:
    reader = asyncio.StreamReader()
    for i in range(0, len(data), chunk):
        reader.feed_data(data[i:i + chunk])
    reader.feed_eof()
    return reader


def part(body: bytes, boundary=b"camcastframe", extra=b"X-Timestamp: 1\r\n") -> bytes:
    head = b"--%s\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n%s\r\n" % (boundary, len(body), extra)
    return head + body + b"\r\n"


class MjpegTest(unittest.IsolatedAsyncioTestCase):
    def test_boundary(self):
        self.assertEqual(mjpeg.boundary_from("multipart/x-mixed-replace; boundary=camcastframe"), b"camcastframe")
        self.assertEqual(mjpeg.boundary_from('multipart/x-mixed-replace;boundary="b"'), b"b")
        for bad in ("image/jpeg", "multipart/x-mixed-replace", "multipart/x-mixed-replace; boundary="):
            with self.assertRaises(mjpeg.MjpegError):
                mjpeg.boundary_from(bad)

    async def test_parts_in_small_chunks_are_unchanged(self):
        bodies = [b"\xff\xd8" + bytes(range(256)) * 3 + b"\r\n--camcastframe\r\n" + b"\xff\xd9", b"\xff\xd8x\xff\xd9"]
        reader = reader_with(b"".join(part(b) for b in bodies))
        self.assertEqual([await mjpeg.read_part(reader, b"camcastframe") for _ in bodies], bodies)
        with self.assertRaises(EOFError):
            await mjpeg.read_part(reader, b"camcastframe")

    async def test_truncated_part_is_eof(self):
        reader = reader_with(part(b"abcdef")[:-5])
        with self.assertRaises(EOFError):
            await mjpeg.read_part(reader, b"camcastframe")

    async def test_bad_parts(self):
        cases = [
            b"--camcastframe\r\nContent-Type: image/jpeg\r\n\r\nabc\r\n",  # no length
            b"--camcastframe\r\nContent-Length: nope\r\n\r\n",
            b"--camcastframe\r\nContent-Length: 999999999\r\n\r\n",
            b"junk\r\n" * 20,
            b"--camcastframe\r\nX: " + b"a" * 2000 + b"\r\n\r\n",
        ]
        for data in cases:
            with self.subTest(data=data[:40]), self.assertRaises(mjpeg.MjpegError):
                await mjpeg.read_part(reader_with(data), b"camcastframe")

    async def test_closing_delimiter(self):
        with self.assertRaises(EOFError):
            await mjpeg.read_part(reader_with(b"--camcastframe--\r\n"), b"camcastframe")
