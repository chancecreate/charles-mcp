"""Keep schemas/bridge-event.v1.schema.json honest against events captured from a real Charles run."""

import json
from pathlib import Path

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / "schemas" / "bridge-event.v1.schema.json").read_text())


def test_fixture_events_conform(fixture_data):
    allowed = set(SCHEMA["properties"]["event_type"]["enum"])
    last_id = 0
    terminal_seen: set[str] = set()
    for ev in fixture_data["events"]:
        for key in SCHEMA["required"]:
            assert key in ev, (key, ev)
        assert ev["schema_version"] == 1
        assert ev["event_type"] in allowed
        assert ev["event_id"] > last_id
        last_id = ev["event_id"]
        if ev["event_type"] in ("response_complete", "failed", "removed"):
            assert ev["transaction_id"] not in terminal_seen, "terminal event emitted twice"
            terminal_seen.add(ev["transaction_id"])
