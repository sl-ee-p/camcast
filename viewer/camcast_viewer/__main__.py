"""Entry point: python3 -m camcast_viewer --port 8091 [--credentials FILE]."""
import argparse
import asyncio
import logging
import os
import sys

from .config import ConfigError, load_upstream, parse_networks
from .hub import Hub
from .server import ViewerServer

HERE = os.path.dirname(os.path.abspath(__file__))


def parse_args(argv=None):
    parser = argparse.ArgumentParser(prog="camcast_viewer", description=__doc__)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--credentials", help="KEY=VALUE file with CAMCAST_URL, CAMCAST_USER, CAMCAST_PASS "
                        "(default: $CREDENTIALS_DIRECTORY/upstream)")
    parser.add_argument("--allow", action="append", help="client network allowed in (repeatable); "
                        "default 192.168.0.0/24 and loopback")
    parser.add_argument("--max-viewers", type=int, default=4)
    parser.add_argument("--linger", type=float, default=15.0,
                        help="seconds the upstream stays up after the last viewer")
    parser.add_argument("--web", default=os.path.join(os.path.dirname(HERE), "web"))
    return parser.parse_args(argv)


def read_version() -> str:
    try:
        with open(os.path.join(os.path.dirname(HERE), "VERSION"), encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return "dev"


async def serve(args) -> None:
    up = load_upstream(args.credentials)
    allow = parse_networks(args.allow or ["192.168.0.0/24", "127.0.0.0/8", "::1/128"])
    hub = Hub(up, max_viewers=args.max_viewers, linger=args.linger)
    version = read_version()
    server = await ViewerServer(hub, allow=allow, web_dir=args.web, version=version).start(args.bind, args.port)
    logging.info("camcast-viewer %s on %s:%d, upstream %s, max %d viewers, allow %s",
                 version, args.bind, args.port, up.label(), args.max_viewers, ", ".join(map(str, allow)))
    try:
        async with server:
            await server.serve_forever()
    finally:
        await hub.close()


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s", stream=sys.stderr)
    args = parse_args(argv)
    try:
        asyncio.run(serve(args))
    except ConfigError as error:
        logging.error("config: %s", error)
        return 2
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
