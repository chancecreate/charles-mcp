"""Locate and validate the bridge discovery file written by the Java agent."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

MAX_DISCOVERY_BYTES = 4096


class DiscoveryError(Exception):
    """The discovery file is missing or invalid (usually: Charles is not running with the agent)."""


@dataclass(frozen=True)
class BridgeEndpoint:
    host: str
    port: int
    token: str
    pid: int | None
    charles_version: str | None
    bridge_version: str | None
    started_ms: int | None

    def identity(self) -> tuple[int | None, int | None, int]:
        """Changes whenever Charles (and therefore the bridge) restarts."""
        return (self.pid, self.started_ms, self.port)


def default_discovery_file() -> Path:
    local = os.environ.get("LOCALAPPDATA")
    if local:
        return Path(local) / "charles-mcp" / "bridge.json"
    return Path.home() / ".charles-mcp" / "bridge.json"


def read_endpoint(path: Path) -> BridgeEndpoint:
    try:
        raw = path.read_bytes()
    except FileNotFoundError as e:
        raise DiscoveryError(
            f"discovery file not found at {path}; is Charles running with the charles-mcp agent?"
        ) from e
    except OSError as e:
        raise DiscoveryError(f"cannot read discovery file {path}: {e.strerror}") from e
    if len(raw) > MAX_DISCOVERY_BYTES:
        raise DiscoveryError("discovery file is unexpectedly large")
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise DiscoveryError("discovery file is not valid JSON") from e
    if not isinstance(data, dict):
        raise DiscoveryError("discovery file must contain a JSON object")
    host = data.get("host")
    port = data.get("port")
    token = data.get("token")
    # The bridge only ever binds loopback; refuse anything else so a tampered file can't redirect us.
    if host not in ("127.0.0.1", "::1", "localhost"):
        raise DiscoveryError(f"refusing non-loopback bridge host {host!r}")
    if not isinstance(port, int) or not (0 < port < 65536):
        raise DiscoveryError("invalid bridge port")
    if not isinstance(token, str) or len(token) < 32:
        raise DiscoveryError("invalid bridge token")
    return BridgeEndpoint(
        host=host,
        port=port,
        token=token,
        pid=data.get("pid") if isinstance(data.get("pid"), int) else None,
        charles_version=data.get("charles_version") if isinstance(data.get("charles_version"), str) else None,
        bridge_version=data.get("bridge_version") if isinstance(data.get("bridge_version"), str) else None,
        started_ms=data.get("started_ms") if isinstance(data.get("started_ms"), int) else None,
    )
