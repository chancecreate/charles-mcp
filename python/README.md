# charles-mcp (Python MCP server)

Read-only MCP server for live Charles Proxy traffic. It talks to the `charles-mcp-agent` bridge running
inside Charles over an authenticated loopback socket.

```bash
pip install .
charles-mcp status        # check the bridge
charles-mcp               # run the MCP server on stdio (what MCP clients launch)
```

See the [project README](../README.md) for installing the Charles-side agent, configuration and security notes.
