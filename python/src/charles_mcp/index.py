"""In-memory index of observed transactions, fed incrementally from the bridge's event log."""

from __future__ import annotations

import fnmatch
import logging
from collections import OrderedDict
from dataclasses import dataclass
from typing import Any

from .bridge_client import BridgeClient
from .config import Settings
from .models import RequestSummary, TxnState
from .redaction import Redactor

log = logging.getLogger("charles_mcp.index")

PAGE_SIZE = 1000
MAX_PAGES_PER_SYNC = 50

_STATE_BY_EVENT: dict[str, TxnState] = {
    "response_complete": "complete",
    "failed": "failed",
    "removed": "removed",
}


@dataclass
class TxnEntry:
    transaction_id: str
    seq: int
    session_id: str | None
    method: str | None = None
    url: str | None = None  # raw; only ever emitted through Redactor.url()
    host: str | None = None
    status: int | None = None
    content_type: str | None = None
    start_ms: int | None = None
    end_ms: int | None = None
    duration_ms: int | None = None
    request_bytes: int = 0
    response_bytes: int = 0
    state: TxnState = "in_flight"
    error_type: str | None = None
    last_event_id: int = 0


def host_matches(host: str, patterns: list[str]) -> bool:
    h = host.lower()
    return any(fnmatch.fnmatchcase(h, p.lower()) for p in patterns)


class TrafficIndex:
    def __init__(self, settings: Settings, redactor: Redactor):
        self.settings = settings
        self.redactor = redactor
        self.entries: OrderedDict[str, TxnEntry] = OrderedDict()
        self.cursor = 0
        self.identity: tuple[Any, ...] | None = None
        self.events_lost = False
        self.filtered_out = 0
        self.evicted = 0
        self.resets = 0
        self.session_ids: list[str] = []

    # -- filtering -----------------------------------------------------------------------------

    def host_allowed(self, host: str | None) -> bool:
        if not host:
            return not self.settings.host_allowlist
        if host_matches(host, self.settings.host_denylist):
            return False
        if self.settings.host_allowlist and not host_matches(host, self.settings.host_allowlist):
            return False
        return True

    # -- ingestion -----------------------------------------------------------------------------

    def reset(self) -> None:
        self.entries.clear()
        self.cursor = 0
        self.events_lost = False
        self.session_ids.clear()
        self.resets += 1

    def apply(self, ev: dict[str, Any]) -> None:
        etype = ev.get("event_type")
        sid = ev.get("session_id")
        if isinstance(sid, str) and sid not in self.session_ids and sid != "none":
            self.session_ids.append(sid)
        tid = ev.get("transaction_id")
        if not isinstance(tid, str):
            return
        req = ev.get("request") or {}
        host = req.get("host")
        entry = self.entries.get(tid)
        if entry is None:
            if not self.host_allowed(host):
                self.filtered_out += 1
                return
            entry = TxnEntry(transaction_id=tid, seq=int(ev.get("event_id", 0)), session_id=sid)
            self.entries[tid] = entry
            while len(self.entries) > self.settings.max_index_transactions:
                self.entries.popitem(last=False)
                self.evicted += 1
        resp = ev.get("response") or {}
        timing = ev.get("timing") or {}
        sizes = ev.get("sizes") or {}
        entry.method = req.get("method") or entry.method
        entry.url = req.get("url") or entry.url
        entry.host = host or entry.host
        entry.status = resp.get("status") or entry.status
        entry.content_type = resp.get("content_type") or entry.content_type
        entry.start_ms = timing.get("start_ms") or entry.start_ms
        entry.end_ms = timing.get("end_ms") or entry.end_ms
        entry.duration_ms = timing.get("duration_ms") if timing.get("duration_ms") is not None else entry.duration_ms
        entry.request_bytes = int(sizes.get("request_bytes") or entry.request_bytes)
        entry.response_bytes = int(sizes.get("response_bytes") or entry.response_bytes)
        entry.error_type = ev.get("error_type") or entry.error_type
        entry.last_event_id = int(ev.get("event_id", entry.last_event_id))
        if etype in _STATE_BY_EVENT:
            entry.state = _STATE_BY_EVENT[etype]

    async def sync(self, client: BridgeClient) -> None:
        """Pull all new events since our cursor. Raises BridgeUnavailable if Charles is down."""
        for _ in range(MAX_PAGES_PER_SYNC):
            page = await client.call("events", after=self.cursor, limit=PAGE_SIZE)
            ep = client.endpoint
            identity = ep.identity() if ep else None
            if self.identity is not None and identity != self.identity:
                log.info("bridge restarted (Charles relaunched); resetting index")
                self.identity = identity
                self.reset()
                continue
            self.identity = identity
            if int(page.get("latest_event_id", 0)) < self.cursor:
                self.reset()
                continue
            if page.get("gap"):
                self.events_lost = True
            for ev in page.get("events", []):
                if isinstance(ev, dict):
                    self.apply(ev)
            self.cursor = int(page.get("next_cursor", self.cursor))
            if not page.get("has_more"):
                return

    # -- queries -------------------------------------------------------------------------------

    def summary(self, e: TxnEntry) -> RequestSummary:
        url, _ = self.redactor.url(e.url)
        return RequestSummary(
            transaction_id=e.transaction_id, cursor=e.seq, session_id=e.session_id, state=e.state, method=e.method,
            url=url, host=e.host, status=e.status, content_type=e.content_type, start_ms=e.start_ms,
            duration_ms=e.duration_ms, request_bytes=e.request_bytes, response_bytes=e.response_bytes,
            error_type=e.error_type,
        )

    def filter(self, *, host: str | None = None, method: str | None = None, status: int | None = None,
               status_class: str | None = None, url_contains: str | None = None, url_glob: str | None = None,
               content_type: str | None = None, since_ms: int | None = None, until_ms: int | None = None,
               state: str | None = None, session_id: str | None = None) -> list[TxnEntry]:
        out = []
        for e in self.entries.values():
            if host and not (e.host and (host.lower() in e.host.lower() or host_matches(e.host, [host]))):
                continue
            if method and (e.method or "").upper() != method.upper():
                continue
            if status is not None and e.status != status:
                continue
            if status_class and (e.status is None or f"{e.status // 100}xx" != status_class.lower()):
                continue
            if url_contains or url_glob:
                red, _ = self.redactor.url(e.url)
                red = red or ""
                if url_contains and url_contains.lower() not in red.lower():
                    continue
                if url_glob and not fnmatch.fnmatchcase(red.lower(), url_glob.lower()):
                    continue
            if content_type and content_type.lower() not in (e.content_type or ""):
                continue
            if since_ms is not None and (e.start_ms is None or e.start_ms < since_ms):
                continue
            if until_ms is not None and (e.start_ms is None or e.start_ms > until_ms):
                continue
            if state and e.state != state:
                continue
            if session_id and e.session_id != session_id:
                continue
            out.append(e)
        return out
