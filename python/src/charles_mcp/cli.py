"""Command line: `charles-mcp` (stdio MCP server), `charles-mcp status`, `charles-mcp mock-bridge`."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

from . import __version__
from .bridge_client import BridgeClient, BridgeUnavailable
from .config import load_settings


def _serve(args: argparse.Namespace) -> None:
    from .server import build_server

    settings = load_settings(args.config)
    if args.allow_bodies:
        settings.allow_bodies = True
    mcp, _ = build_server(settings)
    mcp.run(transport="stdio")


async def _status(args: argparse.Namespace) -> int:
    settings = load_settings(args.config)
    client = BridgeClient(settings.discovery_file)
    try:
        st = await client.call("status")
    except BridgeUnavailable as e:
        print(json.dumps({"bridge_connected": False, "error": str(e),
                          "discovery_file": str(settings.discovery_file)}, indent=2))
        return 1
    finally:
        await client.close()
    print(json.dumps({"bridge_connected": True, **st}, indent=2))
    return 0


async def _mock(args: argparse.Namespace) -> None:
    from .mock_bridge import MockBridge

    bridge = MockBridge.from_file(args.fixture)
    await bridge.start()
    bridge.write_discovery(args.discovery)
    print(f"mock bridge on 127.0.0.1:{bridge.port}; discovery file: {args.discovery}", file=sys.stderr)
    print("set CHARLES_MCP_DISCOVERY_FILE to that path for the MCP server. Ctrl+C to stop.", file=sys.stderr)
    try:
        await asyncio.Event().wait()
    finally:
        await bridge.stop()
        args.discovery.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="charles-mcp", description="Read-only MCP server for live Charles traffic")
    ap.add_argument("--version", action="version", version=f"charles-mcp {__version__}")
    ap.add_argument("--config", type=Path, help="TOML settings file (see examples/charles-mcp.toml)")
    ap.add_argument("--allow-bodies", action="store_true", help="Enable bounded, redacted body access")
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("serve", help="Run the MCP server on stdio (default)")
    sub.add_parser("status", help="Print bridge status and exit")
    m = sub.add_parser("mock-bridge", help="Serve a synthetic fixture over the bridge protocol (demo/testing)")
    m.add_argument("--fixture", type=Path, required=True)
    m.add_argument("--discovery", type=Path, required=True, help="Where to write the mock discovery file")
    args = ap.parse_args(argv)

    # stdout is the MCP channel: logs must go to stderr.
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING, stream=sys.stderr,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if args.cmd == "status":
        sys.exit(asyncio.run(_status(args)))
    elif args.cmd == "mock-bridge":
        try:
            asyncio.run(_mock(args))
        except KeyboardInterrupt:
            pass
    else:
        _serve(args)


if __name__ == "__main__":
    main()
