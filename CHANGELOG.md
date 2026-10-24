# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-24

### Added
- In-JVM bridge for Charles 4.6.x, tested on 4.6.2 and 4.6.8 (`charles-mcp-agent.jar`): registers a read-only `ProxyListener` through
  Charles's own API, emits `request_created`, `request_headers_ready`, `request_complete`,
  `response_headers_ready`, `response_complete`, `failed`, `removed` and `session_changed` events; bounded
  queue/event store; authenticated 127.0.0.1 NDJSON bridge with discovery file.
- Launcher entry point for Charles's bundled runtime (which lacks `java.instrument`), plus `-javaagent`
  support for full JDKs.
- Python MCP server (`charles-mcp`) with `get_status`, `list_requests`, `get_request`, `get_response`,
  `search_traffic`, `get_session_summary`, `watch_requests`; layered redaction; default-deny bodies; host
  filters; mock bridge for demos and tests.
- Windows installer script, synthetic fixtures, versioned event schema, documentation and CI.
