"""Gate E smoke test: spawn the real `charles-mcp` stdio server and call its tools as an MCP client.

Usage:
  python scripts/mcp_smoke.py [--discovery PATH] [--allow-bodies] [--generate http://127.0.0.1:18888 PORT]

With --generate, first sends a few requests to the fixture server through the given Charles proxy so there
is fresh traffic to inspect. Prints compact JSON results (already redacted by the server).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import urllib.request
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


def short(obj: object, n: int = 900) -> str:
    s = json.dumps(obj, separators=(",", ":"))
    return s if len(s) <= n else s[:n] + f"... ({len(s)} chars)"


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--discovery", type=Path)
    ap.add_argument("--allow-bodies", action="store_true")
    ap.add_argument("--generate", nargs=2, metavar=("PROXY", "FIXTURE_PORT"))
    args = ap.parse_args()

    if args.generate:
        proxy, port = args.generate
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": proxy}))
        for path, hdrs in (("/secret?api_key=live-smoke-key", {"Authorization": "Bearer live-smoke-token"}),
                           ("/json", {}), ("/gzip", {})):
            req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers=hdrs)
            opener.open(req, timeout=10).read()

    env = dict(os.environ)
    if args.discovery:
        env["CHARLES_MCP_DISCOVERY_FILE"] = str(args.discovery)
    if args.allow_bodies:
        env["CHARLES_MCP_ALLOW_BODIES"] = "1"
    params = StdioServerParameters(command=sys.executable, args=["-m", "charles_mcp"], env=env)
    async with stdio_client(params) as (read, write), ClientSession(read, write) as session:
        init = await session.initialize()
        print(f"server: {init.serverInfo.name} {init.serverInfo.version}")
        tools = [t.name for t in (await session.list_tools()).tools]
        print("tools:", tools)

        async def call(name: str, **kw: object) -> dict:
            res = await session.call_tool(name, kw)
            if res.isError:
                raise SystemExit(f"{name} failed: {res.content[0].text}")
            return res.structuredContent

        st = await call("get_status")
        print("get_status:", short({k: st[k] for k in ("bridge_connected", "charles_version", "recording",
                                                         "session_id", "dropped_events", "index")}))
        lr = await call("list_requests", limit=5, host="127.0.0.1")
        print("list_requests:", short([{k: i[k] for k in ("transaction_id", "method", "url", "status", "state")}
                                       for i in lr["items"]]))
        secret = next(i for i in lr["items"] if "/secret" in (i["url"] or ""))
        tid = secret["transaction_id"]
        rq = await call("get_request", transaction_id=tid)
        print("get_request:", short({"url": rq["url"], "headers": rq["headers"], "redactions": rq["redactions"]}))
        rs = await call("get_response", transaction_id=tid, include_body=True)
        print("get_response:", short({"status": rs["status"], "state": rs["state"], "headers": rs["headers"],
                                      "body": rs["body"]}))
        blob = json.dumps([rq, rs])
        leaked = [s for s in ("live-smoke-token", "live-smoke-key", "synthetic-access-token-123",
                              "synthetic-session-cookie") if s in blob]
        print("secret leakage check:", "LEAKED " + str(leaked) if leaked else "no secrets in tool output")


if __name__ == "__main__":
    asyncio.run(main())
