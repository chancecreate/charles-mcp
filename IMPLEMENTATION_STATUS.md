# Implementation status — v0.1.0 (2026-10-24)

Environment: Windows 10 Enterprise LTSC 2021 (10.0.19044), Charles 4.6.2 with its bundled Java 11.0.17,
build JDK Temurin 21.0.12 (`--release 11`), Maven 3.9.9 (wrapper), Python 3.11.0, MCP Python SDK 1.27.2.

## Gate summary

| Gate | Result | Evidence |
| --- | --- | --- |
| **A** Baseline & provenance | ✅ | Charles 4.6.2 JAR inspected (`charlesVersion=4.6.2`, manifest, entries); internal APIs used by the bridge mapped and verified. The JAR is never modified. → [docs/compatibility.md](docs/compatibility.md), [docs/reverse-engineering-findings.md](docs/reverse-engineering-findings.md) |
| **B** Charles functionality intact | ✅ | The bridge changes no Charles bytecode. Charles started in GUI and headless mode with the bridge installed and proxied and recorded traffic normally; system proxy, preferences and the installed JAR were unchanged. |
| **C** Native event hook | ✅ | `ProxyListener` registered via Charles's API; live lifecycle sequences with Charles timestamps for GET/POST/redirect/slow/large/gzip/binary/secret/500/connection-refused; 11/11 completed transactions had exactly one terminal event. Reproduced with the final build. → trace in [reverse-engineering-findings.md](docs/reverse-engineering-findings.md#live-evidence-gate-c) |
| **D** JVM bridge | ✅ | Controlled live requests readable over the authenticated loopback bridge with cursors; wrong token rejected; 5th concurrent connection rejected; with 3 stalled authenticated clients + 1 idle unauthenticated client, proxy latency over 100 requests was median 1.9 ms / max 22.7 ms vs. 3.3 / 28.1 ms baseline; Charles hard-killed → sidecar reports *stale discovery file* and reconnects after restart. Queue overflow bounded and counted (unit test: 64-slot queue, 1,000 offers → 936 dropped, non-blocking). |
| **E** MCP tools | ✅ | `scripts/mcp_smoke.py` (official MCP stdio client) spawned `charles-mcp` and called `get_status`, `list_requests`, `get_request`, `get_response` against live Charles (headless and GUI). `Authorization`, `Cookie`, `Set-Cookie`, `api_key` query and JSON `access_token`/`password` were redacted; bodies were refused until enabled; the leakage check found no secrets in tool output. |
| **F** Polish | ✅ | README, docs, CHANGELOG, LICENSE (MIT), SECURITY/CONTRIBUTING, CI workflow, synthetic fixtures, versioned schema, installer, examples. |

## Commands run and results

```text
java> mvnw.cmd -B clean verify
  agent:   Tests run: 45, Failures: 0, Errors: 0     BUILD SUCCESS
python> pytest -q                         54 passed
python> ruff check src tests ../scripts   All checks passed!


python scripts/live_trace.py --proxy http://127.0.0.1:18888 --fixture-port 18765 --discovery …\bridge.json
  54 events since cursor 3 (gap=False); transactions with a terminal event: 11; with >1 terminal event: 0
  queue: {'capacity': 8192, 'queued': 0, 'offered': 57, 'dropped': 0, 'worker_errors': 0}
python scripts/mcp_smoke.py --discovery …\bridge.json --allow-bodies --generate http://127.0.0.1:18888 18765
  server: charles-mcp 0.1.0; 7 tools; get_status bridge_connected=true recording=true; secret leakage check: no secrets in tool output
scripts\install-windows.ps1 (against a scratch copy): install → idempotent re-install → uninstall restored Charles.ini byte-for-byte
```

All live runs used an isolated copy of the Charles install under a temp folder, with a private
`charles.config` (port 18888, every OS/browser proxy integration disabled). Before and after: system proxy
`ProxyEnable=0`, the user's `%APPDATA%\Charles\charles.config` unmodified (timestamp unchanged), installed
`charles.jar` and `Charles.ini` unmodified.

## Design decisions worth knowing

1. **No bytecode instrumentation for the hook.** Charles exposes public `ProxyListener` callbacks that map
   1:1 to the lifecycle we need, and a later-registered listener is guaranteed to run after the recorder.
2. **Launcher instead of `-javaagent`.** Charles's bundled runtime lacks `java.instrument`, which was found
   by trying it. `Charles.ini` → `main.class=io.github.charlesmcp.agent.Launcher`.
3. **Redaction twice** (JVM headers + sidecar headers/query/body/patterns), and bodies are default-deny.

## Pending / not production-ready yet

- [ ] HTTPS with SSL proxying, HTTP/2, WebSockets, breakpoints/rewrite/throttling interplay: not exercised
      live (the listener is passive, but unverified).
- [ ] CONNECT-tunnel late-materialisation path (`request_created` synthesised on first sighting) is unit
      tested only.
- [ ] Confirm CI is green on GitHub (Linux/Windows × Java 11/21 × Python 3.11–3.13); it was validated
      locally on Windows/JDK 21/Python 3.11.
- [ ] Release packaging (signed JAR artifacts, PyPI) not set up; CI uploads the agent JAR as a workflow artifact.
- [ ] Optional SQLite persistence of sanitised metadata was **not** implemented (in-memory only by design).
- [ ] No demo GIF yet (README shows a real transcript instead).
- [ ] Redaction is heuristic; consider per-host rules and a "metadata only" mode for regulated data.

## Next steps

1. Check CI results on GitHub and fix anything platform-specific.
2. Exercise HTTPS + SSL proxying and HTTP/2 through the live harness and add their traces.
3. Tag v0.1.0 with the agent JAR; publish the Python package.
4. Post-v1: RequestFlow consumer of `schemas/bridge-event.v1.schema.json`, opt-in write module (recording
   control) kept separate from the read-only core.
