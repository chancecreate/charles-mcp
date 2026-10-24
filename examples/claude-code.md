# Claude Code

```bash
claude mcp add charles -- charles-mcp
```

With bodies enabled for one project only (`.mcp.json` in the project root):

```json
{
  "mcpServers": {
    "charles": {
      "command": "charles-mcp",
      "args": [],
      "env": { "CHARLES_MCP_ALLOW_BODIES": "1", "CHARLES_MCP_HOST_ALLOWLIST": "api.example.com" }
    }
  }
}
```

If `charles-mcp` is not on `PATH`, use `"command": "python", "args": ["-m", "charles_mcp"]`.
