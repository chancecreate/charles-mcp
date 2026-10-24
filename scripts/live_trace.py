"""Gate C/D live trace: drive controlled traffic through a running Charles and print the lifecycle
events observed by the in-process bridge.

Prerequisites: Charles 4.6.x running with the charles-mcp agent, listening as an HTTP proxy, and
`python scripts/fixture_server.py` running. Prints only methods, hosts, paths of *fixture* URLs,
statuses and timestamps.

Usage:
  python scripts/live_trace.py --proxy http://127.0.0.1:18888 --fixture-port 8765 [--discovery PATH]
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

from charles_mcp.bridge_client import BridgeClient
from charles_mcp.discovery import default_discovery_file


def send(opener: urllib.request.OpenerDirector, method: str, url: str, body: bytes | None = None,
         headers: dict[str, str] | None = None) -> str:
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with opener.open(req, timeout=30) as r:
            r.read()
            return str(r.status)
    except urllib.error.HTTPError as e:
        e.read()
        return str(e.code)
    except Exception as e:  # noqa: BLE001 - we want to report failures, not crash
        return type(e).__name__


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--proxy", default="http://127.0.0.1:18888")
    ap.add_argument("--fixture-port", type=int, required=True)
    ap.add_argument("--discovery", type=Path, default=default_discovery_file())
    ap.add_argument("--json-out", type=Path)
    args = ap.parse_args()

    client = BridgeClient(args.discovery)
    status = await client.call("status")
    cursor = status["events"]["latest_event_id"]
    print(f"bridge ok: charles={status.get('charles_version')} session={status.get('session_id')} "
          f"recording={status.get('recording')} cursor={cursor}")

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": args.proxy, "https": args.proxy}))
    base = f"http://127.0.0.1:{args.fixture_port}"
    plan = [
        ("GET", f"{base}/json?api_key=synthetic-key-1", None, {"Authorization": "Bearer synthetic-token"}),
        ("POST", f"{base}/echo", json.dumps({"password": "synthetic", "n": 1}).encode(),
         {"Content-Type": "application/json"}),
        ("GET", f"{base}/redirect", None, None),
        ("GET", f"{base}/slow?ms=1500", None, None),
        ("GET", f"{base}/large?bytes=300000", None, None),
        ("GET", f"{base}/gzip", None, None),
        ("GET", f"{base}/binary", None, None),
        ("GET", f"{base}/secret", None, {"Cookie": "session=synthetic-cookie"}),
        ("GET", f"{base}/status/500", None, None),
        ("GET", "http://127.0.0.1:9/unreachable", None, None),  # connection refused → failure
    ]
    t0 = time.time()
    for method, url, body, headers in plan:
        code = send(opener, method, url, body, headers)
        print(f"client: {method} {url.split('?')[0]} -> {code}")
    await asyncio.sleep(1.5)

    page = await client.call("events", after=cursor, limit=1000)
    events = page["events"]
    print(f"\n{len(events)} events since cursor {cursor} (gap={page['gap']}) in {time.time() - t0:.1f}s")
    print(f"{'event_id':>8} {'timestamp_ms':>14} {'txn':>5} {'event_type':<24} {'method':<6} {'status':>6} url")
    for e in events:
        req = e.get("request") or {}
        resp = e.get("response") or {}
        url = (req.get("url") or "").split("?")[0]
        print(f"{e['event_id']:>8} {e['timestamp_ms']:>14} {str(e.get('transaction_id')):>5} {e['event_type']:<24} "
              f"{str(req.get('method') or ''):<6} {str(resp.get('status') or ''):>6} {url}")

    terminal: dict[str, list[str]] = {}
    for e in events:
        if e["event_type"] in ("response_complete", "failed", "removed"):
            terminal.setdefault(e["transaction_id"], []).append(e["event_type"])
    dupes = {k: v for k, v in terminal.items() if len(v) > 1}
    print(f"\ntransactions with a terminal event: {len(terminal)}; with >1 terminal event: {len(dupes)}")
    final = await client.call("status")
    print(f"queue: {final.get('queue')}  listener: {final.get('listener')}")
    if args.json_out:
        args.json_out.write_text(json.dumps({"events": events, "status": final}, indent=2))
    await client.close()


if __name__ == "__main__":
    asyncio.run(main())
