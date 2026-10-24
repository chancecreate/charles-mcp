# Client configuration examples

| Client | File |
| --- | --- |
| Claude Code | [claude-code.md](claude-code.md) |
| OpenAI Codex CLI | [codex-config.toml](codex-config.toml) → `~/.codex/config.toml` |
| Cursor | [cursor-mcp.json](cursor-mcp.json) → `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` |
| Sidecar settings | [charles-mcp.toml](charles-mcp.toml) |

All clients launch the same stdio server. Settings can be passed via `--config <file>` or environment
variables (`CHARLES_MCP_ALLOW_BODIES`, `CHARLES_MCP_MAX_BODY_BYTES`, `CHARLES_MCP_HOST_ALLOWLIST`,
`CHARLES_MCP_HOST_DENYLIST`, `CHARLES_MCP_DISCOVERY_FILE`, `CHARLES_MCP_CONFIG`).
