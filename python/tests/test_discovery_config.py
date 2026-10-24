import json

import pytest

from charles_mcp.config import HARD_MAX_BODY_BYTES, load_settings
from charles_mcp.discovery import DiscoveryError, read_endpoint

TOKEN = "a" * 64


def write(tmp_path, obj):
    p = tmp_path / "bridge.json"
    p.write_text(obj if isinstance(obj, str) else json.dumps(obj))
    return p


def test_valid_discovery(tmp_path):
    ep = read_endpoint(write(tmp_path, {"host": "127.0.0.1", "port": 5000, "token": TOKEN, "pid": 1}))
    assert ep.port == 5000 and ep.token == TOKEN


@pytest.mark.parametrize("bad", [
    {"host": "10.0.0.5", "port": 5000, "token": TOKEN},
    {"host": "0.0.0.0", "port": 5000, "token": TOKEN},  # noqa: S104 - asserting it is refused
    {"host": "127.0.0.1", "port": 70000, "token": TOKEN},
    {"host": "127.0.0.1", "port": 5000, "token": "short"},
    [1, 2],
])
def test_invalid_discovery_refused(tmp_path, bad):
    with pytest.raises(DiscoveryError):
        read_endpoint(write(tmp_path, bad))


def test_missing_and_garbage(tmp_path):
    with pytest.raises(DiscoveryError, match="not found"):
        read_endpoint(tmp_path / "nope.json")
    with pytest.raises(DiscoveryError):
        read_endpoint(write(tmp_path, "{nope"))
    with pytest.raises(DiscoveryError, match="large"):
        read_endpoint(write(tmp_path, "x" * 10_000))


def test_settings_defaults_are_secure():
    s = load_settings(env={})
    assert s.allow_bodies is False
    assert "control.charles" in s.host_denylist


def test_settings_env_and_file(tmp_path):
    cfg = tmp_path / "c.toml"
    cfg.write_text('[charles_mcp]\nallow_bodies = true\nmax_body_bytes = 99999999\n'
                   'host_allowlist = ["*.example.test"]\n')
    s = load_settings(cfg, env={"CHARLES_MCP_HOST_DENYLIST": "ads.example.test, tracker.test"})
    assert s.allow_bodies is True
    assert s.max_body_bytes == HARD_MAX_BODY_BYTES
    assert s.host_allowlist == ["*.example.test"]
    assert s.host_denylist == ["ads.example.test", "tracker.test"]
    s = load_settings(cfg, env={"CHARLES_MCP_ALLOW_BODIES": "0"})
    assert s.allow_bodies is False, "environment overrides the file"
