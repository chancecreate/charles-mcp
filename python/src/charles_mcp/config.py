"""Sidecar settings: secure defaults, optional TOML file, environment overrides."""

from __future__ import annotations

import os
import tomllib
from pathlib import Path

from pydantic import BaseModel, Field, field_validator

from .discovery import default_discovery_file

HARD_MAX_BODY_BYTES = 256 * 1024
HARD_MAX_TEXT_CHARS = 64 * 1024


class Settings(BaseModel):
    """All knobs. Defaults are the secure choice; loosening them is always explicit."""

    discovery_file: Path = Field(default_factory=default_discovery_file)

    # Body access is default-deny. Even when enabled, bodies are size-capped, redacted and binary-filtered.
    allow_bodies: bool = False
    max_body_bytes: int = 16 * 1024
    max_text_chars: int = 8_000

    # Host filters (fnmatch-style globs, case-insensitive). Allowlist empty = allow all not denied.
    host_allowlist: list[str] = Field(default_factory=list)
    host_denylist: list[str] = Field(default_factory=lambda: ["control.charles"])

    # Extra redaction rules on top of the built-in ones (which cannot be disabled).
    redact_header_names: list[str] = Field(default_factory=list)
    redact_query_keys: list[str] = Field(default_factory=list)
    redact_json_keys: list[str] = Field(default_factory=list)

    max_index_transactions: int = 20_000
    search_max_scan: int = 200

    @field_validator("max_body_bytes")
    @classmethod
    def _cap_body(cls, v: int) -> int:
        return max(0, min(v, HARD_MAX_BODY_BYTES))

    @field_validator("max_text_chars")
    @classmethod
    def _cap_text(cls, v: int) -> int:
        return max(200, min(v, HARD_MAX_TEXT_CHARS))

    @field_validator("max_index_transactions")
    @classmethod
    def _cap_index(cls, v: int) -> int:
        return max(100, min(v, 200_000))

    @field_validator("search_max_scan")
    @classmethod
    def _cap_scan(cls, v: int) -> int:
        return max(10, min(v, 1000))


_TRUE = {"1", "true", "yes", "on"}


def _split(v: str) -> list[str]:
    return [p.strip() for p in v.split(",") if p.strip()]


def load_settings(config_file: Path | None = None, env: dict[str, str] | None = None) -> Settings:
    """Load settings: defaults < TOML file (``[charles_mcp]`` table or top level) < environment."""
    env = dict(os.environ if env is None else env)
    data: dict[str, object] = {}
    path = config_file or (Path(env["CHARLES_MCP_CONFIG"]) if env.get("CHARLES_MCP_CONFIG") else None)
    if path is not None:
        with open(path, "rb") as f:
            raw = tomllib.load(f)
        table = raw.get("charles_mcp", raw)
        if isinstance(table, dict):
            data.update(table)
    if env.get("CHARLES_MCP_DISCOVERY_FILE"):
        data["discovery_file"] = env["CHARLES_MCP_DISCOVERY_FILE"]
    if "CHARLES_MCP_ALLOW_BODIES" in env:
        data["allow_bodies"] = env["CHARLES_MCP_ALLOW_BODIES"].strip().lower() in _TRUE
    if env.get("CHARLES_MCP_MAX_BODY_BYTES"):
        data["max_body_bytes"] = int(env["CHARLES_MCP_MAX_BODY_BYTES"])
    if env.get("CHARLES_MCP_HOST_ALLOWLIST"):
        data["host_allowlist"] = _split(env["CHARLES_MCP_HOST_ALLOWLIST"])
    if env.get("CHARLES_MCP_HOST_DENYLIST"):
        data["host_denylist"] = _split(env["CHARLES_MCP_HOST_DENYLIST"])
    return Settings.model_validate(data)
