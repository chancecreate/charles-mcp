import pytest

from charles_mcp.bridge_client import BridgeClient, BridgeUnavailable
from charles_mcp.config import Settings
from charles_mcp.index import TrafficIndex
from charles_mcp.mock_bridge import MockBridge
from charles_mcp.redaction import Redactor


async def test_client_authenticates_and_pages(bridge):
    c = BridgeClient(bridge.discovery_path)
    st = await c.call("status")
    assert st["charles_version"] == "4.6.2"
    page = await c.call("events", after=0, limit=10)
    assert len(page["events"]) == 10 and page["has_more"]
    await c.close()


async def test_wrong_token_is_rejected(bridge, tmp_path):
    bad = tmp_path / "bad.json"
    d = bridge.discovery()
    d["token"] = "b" * 64
    bad.write_text(__import__("json").dumps(d))
    with pytest.raises(BridgeUnavailable):
        await BridgeClient(bad).call("status")
    assert bridge.auth_failures >= 1


async def test_charles_not_running(tmp_path):
    with pytest.raises(BridgeUnavailable, match="discovery file not found"):
        await BridgeClient(tmp_path / "missing.json").call("status")


async def test_reconnects_after_charles_restart(tmp_path, fixture_data):
    disc = tmp_path / "bridge.json"
    b1 = MockBridge(fixture_data)
    await b1.start()
    b1.write_discovery(disc)
    c = BridgeClient(disc)
    await c.call("ping")
    await b1.stop()
    # Charles restarts: new port + new token in the discovery file.
    b2 = MockBridge(fixture_data)
    await b2.start()
    b2.write_discovery(disc)
    assert (await c.call("ping"))["pong"] is True
    assert c.endpoint.port == b2.port
    await c.close()
    await b2.stop()


async def test_stale_discovery_file(tmp_path, fixture_data):
    disc = tmp_path / "bridge.json"
    b = MockBridge(fixture_data)
    await b.start()
    b.write_discovery(disc)
    await b.stop()  # killed without cleanup
    with pytest.raises(BridgeUnavailable, match="not reachable"):
        await BridgeClient(disc).call("status")


def make_index(**kw):
    s = Settings(**kw)
    return TrafficIndex(s, Redactor())


async def test_index_sync_states_and_dedup(bridge):
    idx = make_index()
    c = BridgeClient(bridge.discovery_path)
    await idx.sync(c)
    states = {e.transaction_id: e.state for e in idx.entries.values()}
    # control.charles is denied by default; 11 fixture transactions remain
    assert len(idx.entries) == 11
    assert list(states.values()).count("failed") == 1
    assert all(s in ("complete", "failed") for s in states.values())
    cursor = idx.cursor
    await idx.sync(c)  # nothing new: idempotent
    assert idx.cursor == cursor and len(idx.entries) == 11
    await c.close()


async def test_index_incremental_updates(bridge):
    idx = make_index()
    c = BridgeClient(bridge.discovery_path)
    await idx.sync(c)
    bridge.add_event({"event_type": "request_created", "session_id": "s-x", "transaction_id": "t900",
                      "timestamp_ms": 1, "request": {"method": "GET", "url": "http://new.example.test/a",
                                                     "host": "new.example.test"}})
    await idx.sync(c)
    assert idx.entries["t900"].state == "in_flight"
    bridge.add_event({"event_type": "response_complete", "session_id": "s-x", "transaction_id": "t900",
                      "timestamp_ms": 2, "request": {"method": "GET", "url": "http://new.example.test/a",
                                                     "host": "new.example.test"}, "response": {"status": 204}})
    await idx.sync(c)
    assert idx.entries["t900"].state == "complete" and idx.entries["t900"].status == 204
    await c.close()


async def test_host_allow_and_deny_lists(bridge):
    idx = make_index(host_allowlist=["unreachable.*"])
    c = BridgeClient(bridge.discovery_path)
    await idx.sync(c)
    assert {e.host for e in idx.entries.values()} == {"unreachable.example.test"}
    idx = make_index(host_denylist=["api.example.test", "control.charles"])
    await idx.sync(c)
    assert "api.example.test" not in {e.host for e in idx.entries.values()}
    assert idx.filtered_out > 0
    await c.close()


async def test_index_is_bounded(bridge):
    idx = make_index(max_index_transactions=100)
    for i in range(300):
        bridge.add_event({"event_type": "request_created", "session_id": "s", "transaction_id": f"x{i}",
                          "timestamp_ms": i, "request": {"url": f"http://b.test/{i}", "host": "b.test"}})
    c = BridgeClient(bridge.discovery_path)
    await idx.sync(c)
    assert len(idx.entries) == 100 and idx.evicted > 0
    await c.close()


async def test_bridge_restart_resets_index(tmp_path, fixture_data):
    disc = tmp_path / "bridge.json"
    b1 = MockBridge(fixture_data)
    await b1.start()
    b1.write_discovery(disc)
    idx = make_index()
    c = BridgeClient(disc)
    await idx.sync(c)
    assert len(idx.entries) == 11
    await b1.stop()
    b2 = MockBridge({"events": [], "transactions": {}})
    b2.started_ms += 1
    await b2.start()
    b2.write_discovery(disc)
    await idx.sync(c)
    assert len(idx.entries) == 0 and idx.resets == 1
    await c.close()
    await b2.stop()


async def test_gap_sets_events_lost(bridge):
    bridge.events = bridge.events[20:]  # simulate eviction in the JVM
    idx = make_index()
    c = BridgeClient(bridge.discovery_path)
    await idx.sync(c)
    assert idx.events_lost
    await c.close()
