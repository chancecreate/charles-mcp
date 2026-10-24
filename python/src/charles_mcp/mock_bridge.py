"""A protocol-compatible stand-in for the in-JVM bridge, serving a synthetic fixture.

Used by the test-suite and for demos without Charles. It implements the same authenticated NDJSON
protocol (hello/status/events/transaction/ping) as the Java agent.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import secrets
import time
from pathlib import Path
from typing import Any

from . import BRIDGE_PROTOCOL


class MockBridge:
    def __init__(self, fixture: dict[str, Any], token: str | None = None):
        self.token = token or secrets.token_hex(32)
        self.events: list[dict[str, Any]] = list(fixture.get("events", []))
        self.transactions: dict[str, dict[str, Any]] = dict(fixture.get("transactions", {}))
        self.charles_version = fixture.get("charles_version", "4.6.2")
        self.server: asyncio.base_events.Server | None = None
        self.port = 0
        self.started_ms = int(time.time() * 1000)
        self.auth_failures = 0
        self.connections = 0
        self._writers: set[asyncio.StreamWriter] = set()

    @classmethod
    def from_file(cls, path: Path, token: str | None = None) -> MockBridge:
        return cls(json.loads(path.read_text(encoding="utf-8")), token)

    def add_event(self, ev: dict[str, Any], detail: dict[str, Any] | None = None) -> None:
        ev = dict(ev)
        ev["event_id"] = (self.events[-1]["event_id"] + 1) if self.events else 1
        self.events.append(ev)
        if detail and ev.get("transaction_id"):
            self.transactions[ev["transaction_id"]] = detail

    async def start(self) -> None:
        self.server = await asyncio.start_server(self._serve, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def stop(self) -> None:
        """Stop like a dying JVM would: stop listening *and* drop every open connection."""
        if self.server:
            self.server.close()
        for w in list(self._writers):
            w.close()
        if self.server:
            await self.server.wait_closed()

    def discovery(self) -> dict[str, Any]:
        return {"schema_version": 1, "host": "127.0.0.1", "port": self.port, "token": self.token,
                "pid": os.getpid(), "bridge_version": "mock", "protocol": BRIDGE_PROTOCOL,
                "charles_version": self.charles_version, "started_ms": self.started_ms}

    def write_discovery(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.discovery()), encoding="utf-8")

    async def _serve(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.connections += 1
        self._writers.add(writer)
        authed = False
        try:
            while True:
                line = await reader.readline()
                if not line:
                    return
                try:
                    req = json.loads(line)
                except ValueError:
                    await self._send(writer, None, error=("bad_request", "invalid JSON"))
                    if not authed:
                        return
                    continue
                rid = req.get("id")
                if not authed:
                    if req.get("op") != "hello" or req.get("token") != self.token:
                        self.auth_failures += 1
                        await self._send(writer, rid, error=("unauthorized", "missing or invalid token"))
                        return
                    authed = True
                    await self._send(writer, rid, result={"protocol": BRIDGE_PROTOCOL, "bridge_version": "mock"})
                    continue
                await self._send(writer, rid, **self._handle(req))
        except (ConnectionError, OSError):
            return
        finally:
            self._writers.discard(writer)
            writer.close()

    def _handle(self, req: dict[str, Any]) -> dict[str, Any]:
        op = req.get("op")
        if op == "ping":
            return {"result": {"pong": True}}
        if op == "status":
            latest = self.events[-1]["event_id"] if self.events else 0
            return {"result": {
                "agent_state": "active", "charles_version": self.charles_version,
                "supported_charles_versions": "4.6.x", "charles_version_tested": True,
                "recording": True, "session_id": next((e["session_id"] for e in reversed(self.events)), None),
                "queue": {"capacity": 8192, "queued": 0, "offered": len(self.events), "dropped": 0, "worker_errors": 0},
                "events": {"retained": len(self.events), "appended": len(self.events), "evicted": 0,
                           "latest_event_id": latest},
                "protocol": BRIDGE_PROTOCOL, "bridge_version": "mock",
            }}
        if op == "events":
            after = int(req.get("after") or 0)
            limit = max(1, min(1000, int(req.get("limit") or 200)))
            newer = [e for e in self.events if e["event_id"] > after]
            page = newer[:limit]
            latest = self.events[-1]["event_id"] if self.events else 0
            oldest = self.events[0]["event_id"] if self.events else latest + 1
            nxt = page[-1]["event_id"] if page else max(after, oldest - 1)
            return {"result": {"events": page, "next_cursor": nxt, "oldest_event_id": oldest,
                               "latest_event_id": latest, "gap": after + 1 < oldest and bool(self.events),
                               "has_more": len(newer) > limit}}
        if op == "transaction":
            tid = req.get("transaction_id")
            detail = self.transactions.get(tid) if isinstance(tid, str) else None
            if detail is None:
                return {"error": ("not_found", "unknown or evicted transaction")}
            out = {k: v for k, v in detail.items() if k not in ("request_body", "response_body")}
            cap = int(req.get("max_body_bytes") or 65536)
            for side in ("request", "response"):
                if req.get(f"include_{side}_body"):
                    out[f"{side}_body"] = _cap_body(detail.get(f"{side}_body"), cap)
            return {"result": out}
        return {"error": ("unknown_op", "unsupported operation")}

    @staticmethod
    async def _send(writer: asyncio.StreamWriter, rid: Any, result: dict | None = None,
                    error: tuple[str, str] | None = None) -> None:
        msg: dict[str, Any] = {"id": rid, "ok": error is None}
        if error:
            msg["error"] = {"code": error[0], "message": error[1]}
        else:
            msg["result"] = result or {}
        writer.write(json.dumps(msg).encode() + b"\n")
        await writer.drain()


def _cap_body(body: dict | None, cap: int) -> dict:
    if not body or not body.get("available"):
        return body or {"available": False, "reason": "no_body"}
    data = base64.b64decode(body["base64"])
    return {**body, "base64": base64.b64encode(data[:cap]).decode(), "returned_bytes": min(len(data), cap),
            "truncated": body.get("truncated", False) or len(data) > cap}
