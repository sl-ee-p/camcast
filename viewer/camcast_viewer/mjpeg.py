"""multipart/x-mixed-replace framing. Only part headers are parsed; the JPEG bytes pass through untouched."""
import asyncio

MAX_PART = 8 * 1024 * 1024
MAX_LINE = 1024
MAX_SKIPPED_LINES = 16
MAX_PART_HEADERS = 32

# What the viewer sends to browsers. A part is header + JPEG + CRLF, written without copying the JPEG.
BOUNDARY = b"camcastview"
CONTENT_TYPE = "multipart/x-mixed-replace; boundary=" + BOUNDARY.decode()


class MjpegError(Exception):
    pass


def boundary_from(content_type: str) -> bytes:
    kind, _, params = content_type.partition(";")
    if kind.strip().lower() != "multipart/x-mixed-replace":
        raise MjpegError("not an MJPEG stream")
    for param in params.split(";"):
        key, _, value = param.strip().partition("=")
        if key.lower() == "boundary" and value.strip('"'):
            return value.strip('"').encode("latin-1")
    raise MjpegError("no boundary")


def part_header(length: int) -> bytes:
    return b"--%s\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n" % (BOUNDARY, length)


async def _line(reader: asyncio.StreamReader) -> bytes:
    line = await reader.readuntil(b"\n")
    if len(line) > MAX_LINE:
        raise MjpegError("header line too long")
    return line.rstrip(b"\r\n")


async def read_part(reader: asyncio.StreamReader, boundary: bytes) -> bytes:
    """Return the next part's body. Raises EOFError at the closing delimiter or end of stream."""
    delimiter = b"--" + boundary
    try:
        for _ in range(MAX_SKIPPED_LINES):
            line = await _line(reader)
            # Some cameras write the boundary without the leading dashes; accept both.
            if line in (delimiter, boundary):
                break
            if line == delimiter + b"--":
                raise EOFError
        else:
            raise MjpegError("boundary not found")
        length = None
        for _ in range(MAX_PART_HEADERS):
            line = await _line(reader)
            if not line:
                break
            name, _, value = line.partition(b":")
            if name.strip().lower() == b"content-length":
                try:
                    length = int(value.strip())
                except ValueError:
                    raise MjpegError("bad Content-Length") from None
        else:
            raise MjpegError("too many part headers")
        if length is None:
            raise MjpegError("part without Content-Length")
        if not 0 < length <= MAX_PART:
            raise MjpegError(f"part size {length} out of range")
        return await reader.readexactly(length)
    except asyncio.IncompleteReadError:
        raise EOFError from None
    except asyncio.LimitOverrunError:
        raise MjpegError("header line too long") from None
