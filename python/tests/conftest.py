from __future__ import annotations

import json
from pathlib import Path

import pytest

from charles_mcp.mock_bridge import MockBridge

FIXTURE = Path(__file__).resolve().parents[2] / "fixtures" / "sample-bridge.json"


@pytest.fixture
def fixture_data() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


@pytest.fixture
async def bridge(tmp_path, fixture_data):
    b = MockBridge(fixture_data)
    await b.start()
    b.discovery_path = tmp_path / "bridge.json"
    b.write_discovery(b.discovery_path)
    yield b
    await b.stop()


def txn_id_for(fixture_data: dict, path_fragment: str) -> str:
    for tid, d in fixture_data["transactions"].items():
        if path_fragment in ((d.get("request") or {}).get("url") or ""):
            return tid
    raise KeyError(path_fragment)
