"""Upstream settings from a root-only KEY=VALUE file, and the LAN allowlist."""
import base64
import ipaddress
import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit

DEFAULT_CREDENTIAL = "upstream"


class ConfigError(Exception):
    pass


@dataclass(frozen=True)
class Upstream:
    """Where the phone is and how to log in. The password is kept out of repr so it never reaches a log."""
    host: str
    port: int
    prefix: str
    user: str
    password: str = field(repr=False)

    def authorization(self) -> str:
        token = base64.b64encode(f"{self.user}:{self.password}".encode()).decode("ascii")
        return f"Basic {token}"

    def label(self) -> str:
        return f"http://{self.host}:{self.port}{self.prefix}"


def read_env_file(path: str) -> dict[str, str]:
    values = {}
    with open(path, encoding="utf-8") as handle:
        for number, raw in enumerate(handle, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            key, sep, value = line.partition("=")
            if not sep:
                raise ConfigError(f"{path}:{number}: expected KEY=VALUE")
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                value = value[1:-1]
            values[key.strip()] = value
    return values


def upstream_from(values: dict[str, str]) -> Upstream:
    missing = [k for k in ("CAMCAST_URL", "CAMCAST_USER", "CAMCAST_PASS") if not values.get(k)]
    if missing:
        raise ConfigError("missing " + ", ".join(missing))
    url = urlsplit(values["CAMCAST_URL"])
    if url.scheme != "http" or not url.hostname:
        raise ConfigError("CAMCAST_URL must look like http://host:port")
    if url.username or url.password or url.query or url.fragment:
        raise ConfigError("CAMCAST_URL must not carry credentials, a query or a fragment")
    user = values["CAMCAST_USER"]
    if ":" in user:
        raise ConfigError("CAMCAST_USER must not contain ':'")
    return Upstream(url.hostname, url.port or 80, url.path.rstrip("/"), user, values["CAMCAST_PASS"])


def default_credential_path() -> str | None:
    """systemd LoadCredential= puts the file in $CREDENTIALS_DIRECTORY, readable only by the service."""
    directory = os.environ.get("CREDENTIALS_DIRECTORY")
    return os.path.join(directory, DEFAULT_CREDENTIAL) if directory else None


def load_upstream(path: str | None) -> Upstream:
    path = path or default_credential_path()
    if not path:
        raise ConfigError("no credential file: pass --credentials or run under systemd LoadCredential=")
    try:
        return upstream_from(read_env_file(path))
    except OSError as error:
        raise ConfigError(f"cannot read {path}: {error.strerror}") from None


def parse_networks(values: list[str]) -> list[ipaddress.IPv4Network | ipaddress.IPv6Network]:
    try:
        return [ipaddress.ip_network(v.strip(), strict=False) for v in values]
    except ValueError as error:
        raise ConfigError(str(error)) from None


def is_allowed(address: str, networks) -> bool:
    try:
        ip = ipaddress.ip_address(address.split("%", 1)[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return any(ip in net for net in networks)
