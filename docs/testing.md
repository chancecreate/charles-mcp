# Testing

Two tiers, kept strictly separate. CI runs tier 1 only; nothing in CI needs a Charles binary.

## 1. Unit and integration tests (CI)

```bash
cd java && ./mvnw -B verify          # 45 agent tests
cd python && pip install -e ".[dev]" && pytest   # 54 tests
cd python && ruff check src tests ../scripts
```

* **Agent** tests run against clean-room *test doubles* in `java/agent/src/test/java/com/xk72/...` that have
  the same class/method **shapes** as Charles 4.6.2 (no Charles code). They cover: listener lifecycle and
  ordering, async header arrival, exactly-once terminal events, failure classification, unrecorded traffic,
  late first sighting, transaction replacement, session changes, error isolation, cursor pagination, gap
  detection, count/age/byte/transaction eviction, queue overflow without blocking, loopback-only binding,
  auth, connection/size limits, bridge-side body kill switch, end-to-end capture over the real socket,
  discovery file lifecycle, unsupported-version fail-closed.
* **Python** tests use `MockBridge` (same NDJSON protocol) loaded with `fixtures/sample-bridge.json` and a real
  MCP client session (in-memory transport): tool listing/schemas, filtering, pagination, redaction,
  default-deny bodies, binary/truncation handling, prompt-injection flagging, search, summaries, error
  reporting, bridge-down behaviour, reconnect after restart, stale discovery files, host filters, bounds.

## 2. Real Charles runtime tests (local, manual)

Run an isolated copy so your installation and system proxy are untouched:

1. Copy `C:\Program Files\Charles` to a scratch folder.
2. `scripts\install-windows.ps1 -CharlesDir <copy> -AgentOption debugLifecycle=true,"discoveryFile=<copy>\bridge.json"`
3. Create a private `charles.config` (LF line endings) with port 18888, all Windows/macOS/Firefox proxy
   integrations `enableAtStartup=false`, and (headless only) `remoteControlConfiguration/enabled=true`.
4. `python scripts/fixture_server.py --port 18765`
5. `<copy>\Charles.exe -headless -config <copy>\charles.config`, then
   `curl -x http://127.0.0.1:18888 http://control.charles/recording/start` (GUI mode records automatically).
6. `python scripts/live_trace.py --proxy http://127.0.0.1:18888 --fixture-port 18765 --discovery <copy>\bridge.json`
   (Gate C/D: lifecycle table, exactly-once terminals, queue counters)
7. `python scripts/mcp_smoke.py --discovery <copy>\bridge.json --generate http://127.0.0.1:18888 18765 [--allow-bodies]`
   (Gate E: real MCP stdio client → server → bridge → Charles, with a secret-leakage check)

Results of the runs performed during development are recorded in [IMPLEMENTATION_STATUS.md](../IMPLEMENTATION_STATUS.md).
