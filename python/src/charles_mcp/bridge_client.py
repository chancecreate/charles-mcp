"""Async client for the agent's authenticated loopback NDJSON bridge."""

from __future__ import annotations

import asyncio
import itertools
import json
import logging
from pathlib import Path
from typing import Any

from . import BRIDGE_PROTOCOL, __version__
from .discovery import BridgeEndpoint, DiscoveryError, read_endpoint

log = logging.getLogger("charles_mcp.bridge")

MAX_RESPONSE_BYTES = 32 * 1024 * 1024  # bodies are capped far below this by the bridge
CONNECT_TIMEOUT_S = 3.0
REQUEST_TIMEOUT_S = 15.0


class BridgeUnavailable(Exception):
    """Charles / the agent is not reachable right now. Safe to retry later."""


class BridgeError(Exception):
    """The bridge answered with an error."""

    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class BridgeClient:
    """One persistent, lazily (re)connected bridge connection.

    * Re-reads the discovery file on every reconnect, so restarting Charles (new port + token) is
      handled transparently.
    * Never raises on the happy path for a missing Charles: callers get :class:`BridgeUnavailable`.
    * Requests are serialized with a lock; the bridge protocol is strictly request/response.
    """

    def __init__(self, discovery_file: Path):
        self._discovery_file = discovery_file
        self._reader: asyncio.StreamReader | None = None
        self._writer: asyncio.StreamWriter | None = None
        self._endpoint: BridgeEndpoint | None = None
        self._lock = asyncio.Lock()
        self._ids = itertools.count(1)
        self._discovery_mtime: int | None = None
        self.last_error: str | None = None

    @property
    def endpoint(self) -> BridgeEndpoint | None:
        return self._endpoint

    async def close(self) -> None:
        writer, self._writer, self._reader = self._writer, None, None
        if writer is not None:
            writer.close()
            try:
                await writer.wait_closed()
            except (ConnectionError, OSError):
                pass

    def _discovery_changed(self) -> bool:
        try:
            return self._discovery_file.stat().st_mtime_ns != self._discovery_mtime
        except OSError:
            return True

    async def _connect(self) -> None:
        try:
            self._discovery_mtime = self._discovery_file.stat().st_mtime_ns
            ep = read_endpoint(self._discovery_file)
        except OSError as e:
            raise BridgeUnavailable(
                f"discovery file not found at {self._discovery_file}; is Charles running with the charles-mcp agent?"
            ) from e
        except DiscoveryError as e:
            raise BridgeUnavailable(str(e)) from e
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(ep.host, ep.port, limit=MAX_RESPONSE_BYTES), CONNECT_TIMEOUT_S
            )
        except (OSError, TimeoutError) as e:
            raise BridgeUnavailable(
                f"bridge at {ep.host}:{ep.port} not reachable (stale discovery file or Charles stopped)"
            ) from e
        self._reader, self._writer = reader, writer
        try:
            hello = await self._roundtrip(
                {"op": "hello", "token": ep.token, "client": f"charles-mcp/{__version__}", "protocol": BRIDGE_PROTOCOL}
            )
        except Exception:
            await self.close()
            raise
        if hello.get("protocol") != BRIDGE_PROTOCOL:
            await self.close()
            raise BridgeUnavailable(f"bridge speaks protocol {hello.get('protocol')}, expected {BRIDGE_PROTOCOL}")
        self._endpoint = ep
        log.info("connected to charles-mcp bridge %s:%s", ep.host, ep.port)

    async def _roundtrip(self, message: dict[str, Any]) -> dict[str, Any]:
        assert self._reader is not None and self._writer is not None
        message = {"id": next(self._ids), **message}
        self._writer.write(json.dumps(message, separators=(",", ":")).encode("utf-8") + b"\n")
        await self._writer.drain()
        line = await asyncio.wait_for(self._reader.readline(), REQUEST_TIMEOUT_S)
        if not line:
            raise ConnectionResetError("bridge closed the connection")
        reply = json.loads(line)
        if not isinstance(reply, dict):
            raise BridgeError("bad_reply", "bridge reply is not an object")
        if not reply.get("ok"):
            err = reply.get("error") or {}
            raise BridgeError(str(err.get("code", "error")), str(err.get("message", "")))
        result = reply.get("result")
        return result if isinstance(result, dict) else {}

    async def call(self, op: str, **params: Any) -> dict[str, Any]:
        """Send one request, reconnecting once if the connection was lost."""
        async with self._lock:
            for attempt in (1, 2):
                try:
                    if self._writer is not None and self._discovery_changed():
                        await self.close()  # Charles restarted or another instance took over
                    if self._writer is None:
                        await self._connect()
                    result = await self._roundtrip({"op": op, **params})
                    self.last_error = None
                    return result
                except BridgeError as e:
                    if e.code == "unauthorized":
                        await self.close()
                        self.last_error = "bridge rejected our token (stale discovery file?)"
                        if attempt == 1:
                            continue
                        raise BridgeUnavailable(self.last_error) from e
                    raise
                except BridgeUnavailable as e:
                    self.last_error = str(e)
                    raise
                except (OSError, ConnectionError, TimeoutError, json.JSONDecodeError, asyncio.IncompleteReadError) as e:
                    await self.close()
                    self.last_error = f"bridge connection lost ({type(e).__name__})"
                    if attempt == 2:
                        raise BridgeUnavailable(self.last_error) from e
            raise BridgeUnavailable("unreachable")  # pragma: no cover
