"""Strictly typed MCP tool outputs. Every field that can carry captured content is already redacted."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

TxnState = Literal["in_flight", "complete", "failed", "removed"]


class Header(BaseModel):
    name: str
    value: str | None


class BodyView(BaseModel):
    available: bool
    reason: str | None = None
    kind: Literal["text", "json", "binary"] | None = None
    mime_type: str | None = None
    captured_bytes: int | None = Field(None, description="Body size as Charles recorded it (wire bytes).")
    returned_bytes: int | None = Field(None, description="Bytes fetched from Charles for this view.")
    truncated: bool = Field(False, description="True if the body was longer than the byte limit.")
    text_truncated: bool = Field(False, description="True if decoded text was cut to the character limit.")
    content_encoding: str | None = None
    decoded: bool = Field(False, description="True if Charles removed a Content-Encoding (gzip, br, ...).")
    text: str | None = None
    sha256_of_returned: str | None = None
    redactions: int = 0
    untrusted: bool = True
    notice: str | None = None
    hint: str | None = None


class Timing(BaseModel):
    start_ms: int | None = None
    request_begin_ms: int | None = None
    request_complete_ms: int | None = None
    response_begin_ms: int | None = None
    end_ms: int | None = None
    duration_ms: int | None = Field(None, description="end_ms - start_ms as recorded by Charles.")
    dns_ms: int | None = None
    connect_ms: int | None = None
    tls_handshake_ms: int | None = None


class RequestSummary(BaseModel):
    transaction_id: str
    cursor: int = Field(description="Monotonic position; pass as `after` to list newer requests.")
    session_id: str | None
    state: TxnState
    method: str | None
    url: str | None = Field(description="Redacted URL (sensitive query values replaced).")
    host: str | None
    status: int | None
    content_type: str | None
    start_ms: int | None
    duration_ms: int | None
    request_bytes: int
    response_bytes: int
    error_type: str | None = None


class ListRequestsResult(BaseModel):
    items: list[RequestSummary]
    next_after: int = Field(description="Pass as `after` next time to receive only newer requests.")
    matched: int = Field(description="Total requests in the index matching the filters.")
    returned: int
    index_size: int
    events_lost: bool = Field(False, description="True if the bridge evicted events before we could read them.")
    bridge_connected: bool
    note: str | None = None


class RequestDetail(BaseModel):
    transaction_id: str
    session_id: str | None
    state: TxnState
    attached_to_session: bool | None = Field(None, description="False once cleared from the Charles session.")
    method: str | None
    url: str | None
    scheme: str | None
    host: str | None
    port: int | None
    protocol_version: str | None
    headers: list[Header]
    header_bytes: int | None
    body_bytes: int | None
    content_type: str | None
    timing: Timing
    body: BodyView
    redactions: int = Field(description="Number of values replaced with [REDACTED] in this result.")


class ConnectionInfo(BaseModel):
    remote_address: str | None = None
    alpn: str | None = None
    tls_protocol: str | None = None
    cipher_suite: str | None = None
    websocket: bool | None = None
    tunnel: bool | None = None


class ResponseDetail(BaseModel):
    transaction_id: str
    state: TxnState
    status: int | None
    charles_status: str | None
    error: dict[str, str | None] | None
    headers: list[Header]
    header_bytes: int | None
    body_bytes: int | None
    content_type: str | None
    timing: Timing
    connection: ConnectionInfo
    body: BodyView
    redactions: int


class StatusResult(BaseModel):
    sidecar_version: str
    bridge_connected: bool
    bridge_error: str | None = None
    charles_version: str | None = None
    supported_charles_versions: str | None = None
    charles_version_tested: bool | None = Field(None, description="True if this exact Charles version was verified.")
    agent_state: str | None = None
    recording: bool | None = Field(None, description="Inferred: a recording session is attached in Charles.")
    session_id: str | None = None
    queue: dict[str, int] | None = None
    dropped_events: int | None = Field(None, description="Events dropped by the in-JVM queue (backpressure).")
    evicted_events: int | None = None
    index: dict[str, int | bool]
    settings: dict[str, object]


class SearchMatch(BaseModel):
    transaction_id: str
    url: str | None
    field: Literal["url", "request_header", "response_header", "request_body", "response_body"]
    snippet: str = Field(description="Redacted excerpt around the match. Untrusted captured content.")


class SearchResult(BaseModel):
    query: str
    matches: list[SearchMatch]
    scanned_transactions: int
    scan_limit_reached: bool
    bodies_searched: bool
    note: str | None = None


class CountItem(BaseModel):
    key: str
    count: int


class ErrorItem(BaseModel):
    transaction_id: str
    url: str | None
    status: int | None
    error_type: str | None
    state: TxnState


class SessionSummary(BaseModel):
    transactions: int
    complete: int
    in_flight: int
    failed: int
    removed: int
    by_host: list[CountItem]
    by_method: list[CountItem]
    by_status: list[CountItem]
    by_status_class: list[CountItem]
    recent_errors: list[ErrorItem]
    total_request_bytes: int
    total_response_bytes: int
    first_start_ms: int | None
    last_start_ms: int | None
    session_ids: list[str]
    bridge_connected: bool


class WatchEvent(BaseModel):
    event_id: int
    event_type: str
    transaction_id: str | None
    timestamp_ms: int
    method: str | None = None
    url: str | None = None
    status: int | None = None


class WatchResult(BaseModel):
    events: list[WatchEvent]
    next_after: int
    events_lost: bool
    bridge_connected: bool
