"""Snapshot bridge events + transaction details into a mock-bridge fixture (and a small HAR).

ONLY run this against synthetic traffic from scripts/fixture_server.py. Hosts are rewritten to
api.example.test, and header values the bridge already redacted are replaced with obviously synthetic
placeholders so the sidecar's own redaction can be exercised in tests.

Usage: python scripts/capture_fixture.py --discovery PATH --fixture-port 18765 --out fixtures/
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
from urllib.parse import urlsplit

from charles_mcp.bridge_client import BridgeClient

SYNTHETIC = {
    "authorization": "Bearer synthetic-token-not-real",
    "cookie": "session=synthetic-cookie-not-real",
    "set-cookie": "session=synthetic-session-cookie; HttpOnly",
}


def rewrite(obj: object, needle: str, repl: str) -> object:
    if isinstance(obj, str):
        return obj.replace(needle, repl)
    if isinstance(obj, list):
        return [rewrite(x, needle, repl) for x in obj]
    if isinstance(obj, dict):
        return {k: rewrite(v, needle, repl) for k, v in obj.items()}
    return obj


def unredact_synthetic(detail: dict) -> None:
    for side in ("request", "response"):
        for h in (detail.get(side) or {}).get("headers") or []:
            if "[REDACTED]" in str(h.get("value")) and h["name"].lower() in SYNTHETIC:
                h["value"] = SYNTHETIC[h["name"].lower()]


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--discovery", type=Path, required=True)
    ap.add_argument("--fixture-port", type=int, required=True)
    ap.add_argument("--out", type=Path, default=Path("fixtures"))
    ap.add_argument("--skip-url-substring", action="append", default=[],
                    help="Drop transactions whose URL contains this (e.g. load-test noise)")
    args = ap.parse_args()
    c = BridgeClient(args.discovery)
    page = await c.call("events", after=0, limit=1000)
    events = page["events"]
    skip = {e["transaction_id"] for e in events if e.get("transaction_id")
            and any(x in ((e.get("request") or {}).get("url") or "") for x in args.skip_url_substring)}
    events = [e for e in events if e.get("transaction_id") not in skip]
    details = {}
    for tid in sorted({e["transaction_id"] for e in events if e.get("transaction_id")}):
        d = await c.call("transaction", transaction_id=tid, include_request_body=True, include_response_body=True,
                         max_body_bytes=4096)
        unredact_synthetic(d)
        details[tid] = d
    await c.close()
    needle = f"127.0.0.1:{args.fixture_port}"
    fixture = rewrite({"charles_version": "4.6.2", "events": events, "transactions": details}, needle,
                      "api.example.test")
    fixture = rewrite(fixture, "127.0.0.1:9", "unreachable.example.test:9")
    for item in fixture["events"] + list(fixture["transactions"].values()):
        req = item.get("request")
        if req and req.get("url"):
            req["host"] = urlsplit(req["url"]).hostname
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "sample-bridge.json").write_text(json.dumps(fixture, indent=1) + "\n", encoding="utf-8")

    entries = []
    for tid, d in fixture["transactions"].items():
        req, resp, t = d.get("request") or {}, d.get("response") or {}, d.get("timing") or {}
        if not req.get("url") or "control.charles" in req["url"]:
            continue
        entries.append({
            "startedDateTime": t.get("start_ms"),
            "time": t.get("duration_ms") or 0,
            "request": {"method": req.get("method"), "url": req.get("url"), "httpVersion": "HTTP/1.1",
                        "headers": req.get("headers") or [], "queryString": [], "headersSize": -1,
                        "bodySize": req.get("body_bytes") or 0},
            "response": {"status": resp.get("status") or 0, "statusText": "", "httpVersion": "HTTP/1.1",
                         "headers": resp.get("headers") or [],
                         "content": {"size": resp.get("body_bytes") or 0, "mimeType": resp.get("content_type") or ""},
                         "redirectURL": "", "headersSize": -1, "bodySize": resp.get("body_bytes") or 0},
            "cache": {}, "timings": {"send": 0, "wait": t.get("duration_ms") or 0, "receive": 0},
            "comment": f"charles-mcp transaction {tid} (synthetic)",
        })
    har = {"log": {"version": "1.2", "creator": {"name": "charles-mcp capture_fixture", "version": "0.1.0"},
                   "comment": "Synthetic traffic from scripts/fixture_server.py; no real user data.",
                   "entries": entries}}
    (args.out / "sample.har").write_text(json.dumps(har, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {len(events)} events, {len(details)} transactions, {len(entries)} HAR entries to {args.out}")


if __name__ == "__main__":
    asyncio.run(main())
