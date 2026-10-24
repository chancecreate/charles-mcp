# Reverse-engineering findings (Charles 4.6.2)

Everything here was established by inspecting the JAR with `javap`, CFR 0.152 decompilation, and live runs
of Charles 4.6.2 on Windows 10 (bundled Java 11.0.17 runtime) in October 2026. **No Charles code is
included in this repository**; names below are cited only to explain how the bridge attaches.

Legend: ✅ verified at runtime · 🔎 verified statically (bytecode/decompilation) · ❓ inferred / unverified

## 1. The hook we use: `com.xk72.proxy.ProxyListener`

| Finding | Evidence | Status |
| --- | --- | --- |
| `com.xk72.proxy.ProxyListener` is a **public, non-obfuscated** interface with 23 `void` callbacks (`receivedRequestURL`, `receivedRequestHeader`, `receivedRequest`, `receivedResponseHeader`, `receivedResponse`, `transactionComplete`, `transactionException`, `transactionBlocked`, `cancelTransaction`, body/websocket chunk callbacks, …). | `javap`; the agent verifies the exact method set at startup and refuses to attach on mismatch. | ✅ |
| The ProxyManager is `com.xk72.charles.u`, reachable via the non-obfuscated `CharlesContext.getInstance().getProxyManager()`. It exposes `a(ProxyListener)` (add) and `b(ProxyListener)` (remove), `p()` returning the listener multiplexer `com.xk72.proxy.n`, and `q()` returning the current recording `Session`. | `javap -cp charles.jar com.xk72.charles.u` | ✅ |
| The multiplexer (`com.xk72.proxy.g extends n`) keeps listeners in a copy-on-write list and **stably sorts** it on add, using `OrderedProxyListener.getOrder()` (un-ordered listeners = `Integer.MAX_VALUE`). | Decompiled `com.xk72.proxy.g`, `com.xk72.proxy.h` (comparator). | 🔎 |
| The session recorder (`com.xk72.charles.E`, delegating to `com.xk72.charles.v extends com.xk72.charles.model.b`) is added **first**, in the ProxyManager constructor. A plain listener added later therefore runs **after** the recorder for every callback, i.e. the `Transaction` is already updated when we read it. | Decompiled `u.<init>`; live trace shows populated status/headers at `response_headers_ready`. | ✅ |
| The recorder stores the `Transaction` it created on the `ProxyEvent` under attribute key `"com.xk72.charles.model.Transaction"` (a sentinel `Object` marks cancelled ones). Unrecorded traffic (recording off, `DONT_RECORD`) has no such attribute. | Decompiled `com.xk72.charles.model.b`; live: no events while headless recording was off, events after `/recording/start`. | ✅ |
| Every lifecycle callback funnels through `model.G.a(Transaction, ProxyEvent)`, which copies ~60 fields from the event and ends with `transaction.fireModelListenerModelChanged(...)`. Body-chunk callbacks only do so at most once per second. | Decompiled `com.xk72.charles.model.G` (CFR: `g_0`). | 🔎 |
| `transactionComplete` → `G.m(...)` finalises request/response bodies (`setRequestBody`/`setResponseBody`) and calls `clearChannel()`. So at `transactionComplete`, bodies are materialised. | Decompiled `G.m`, `G.a(Transaction)`, `G.b(Transaction)`; live: bodies available immediately after `response_complete`. | ✅ |
| On a failed connection Charles calls `transactionException` (recorder internally synthesises response + completion); in our runs **no separate `transactionComplete` reached listeners afterwards** (`duplicate_terminals_suppressed = 0`). The agent dedupes anyway. | Live trace (transaction `t12`, connection refused). | ✅ (one scenario) |

### Why not the other candidates?

| Candidate | Verdict |
| --- | --- |
| `ModelNode.addModelDescendantListener(com.xk72.charles.model.e)` on the Session | Works in principle (model-changed events bubble up), but the interface is **obfuscated** (`e`), events carry no lifecycle stage (every update is just "changed"), and body updates are throttled to 1/s. We would have to diff Transaction state to infer stages. Rejected in favour of the named callbacks. |
| `Session.addTransaction()` / `addToModel()` instrumentation | Only fires on creation. The session recorder (decompiled `model.b`) calls `addToModel` directly, not `addTransaction`, so instrumenting `addTransaction` would miss live traffic. Response completion is not signalled there. Rejected. |
| Transaction setters (`setResponseHeader`, …) | Plain field assignments; no notification. Would need bytecode instrumentation of many methods. Rejected. |
| GUI panels / selection | Explicitly avoided: invisible/unselected requests must be observed. |

## 2. Lifecycle mapping (as implemented)

| ProxyListener callback | Bridge event | Notes |
| --- | --- | --- |
| `receivedRequestURL` | `request_created` | Recorder has just created the Transaction and added it to the session tree. |
| `receivedRequestHeader` | `request_headers_ready` | |
| `receivedRequest` | `request_complete` | Request body fully received by Charles. Absent for connection failures. |
| `receivedResponseHeader` | `response_headers_ready` | Status code + headers available. |
| `transactionComplete` (no exception) | `response_complete` | Terminal, at most once. |
| `transactionException`, `transactionBlocked`, or `transactionComplete` with `getException() != null` | `failed` | Terminal, at most once. |
| `cancelTransaction` (for a transaction we saw) | `removed` | Terminal. |
| first sighting of a Transaction in any other callback | `request_created` (synthesised once) | Covers CONNECT tunnels the recorder materialises late (after 2.5 s). ❓ not exercised live |
| recording `Session` object changes | `session_changed` | Detected via `ProxyManager.q()` identity. |
| body/WebSocket chunk callbacks | *(ignored)* | Returned immediately; never touch the queue. |

`receivedResponse` (response body done, before `transactionComplete`) is intentionally not surfaced; it adds an
event with no new client-visible information.

### Live evidence (Gate C)

Captured from Charles 4.6.2 headless with the agent, traffic from `scripts/fixture_server.py`, via
`python scripts/live_trace.py` (2026-10-24, timestamps are epoch ms recorded inside Charles's JVM):

```text
event_id   timestamp_ms   txn event_type               method status url
       4  1792871746031    t2 request_created          GET           http://127.0.0.1:18765/json
       5  1792871746031    t2 request_headers_ready    GET           http://127.0.0.1:18765/json
       6  1792871746033    t2 request_complete         GET           http://127.0.0.1:18765/json
       7  1792871746033    t2 response_headers_ready   GET       200 http://127.0.0.1:18765/json
       8  1792871746034    t2 response_complete        GET       200 http://127.0.0.1:18765/json
      14  1792871746056    t4 request_created          GET           http://127.0.0.1:18765/redirect
      17  1792871746061    t4 response_headers_ready   GET       302 http://127.0.0.1:18765/redirect
      18  1792871746062    t4 response_complete        GET       302 http://127.0.0.1:18765/redirect
      19  1792871746063    t5 request_created          GET           http://127.0.0.1:18765/json   (redirect target: new transaction)
      26  1792871746089    t6 request_complete         GET           http://127.0.0.1:18765/slow
      27  1792871747589    t6 response_headers_ready   GET       200 http://127.0.0.1:18765/slow   (+1500 ms server delay)
      28  1792871747590    t6 response_complete        GET       200 http://127.0.0.1:18765/slow
      54  1792871747674   t12 request_created          GET           http://127.0.0.1:9/unreachable
      55  1792871747676   t12 request_headers_ready    GET           http://127.0.0.1:9/unreachable
      56  1792871747688   t12 response_headers_ready   GET       503 http://127.0.0.1:9/unreachable (Charles-generated error page)
      57  1792871747688   t12 failed                   GET       503 http://127.0.0.1:9/unreachable

transactions with a terminal event: 11; with >1 terminal event: 0
queue: {'capacity': 8192, 'queued': 0, 'offered': 57, 'dropped': 0, 'worker_errors': 0}
```

The full 57-event run (with host names rewritten to `api.example.test`) is the fixture
[`fixtures/sample-bridge.json`](../fixtures/sample-bridge.json). These events were read from the bridge's
in-memory queue while Charles was running; no session export was involved.

## 3. Other internals the agent reads

| Item | Evidence | Status |
| --- | --- | --- |
| Charles version: `com/xk72/charles/init.properties` → `charlesVersion=4.6.2`; at runtime `CharlesContext.getVersionName()`. | JAR contents; live status. | ✅ |
| `ProxyEventStatus` constants are obfuscated (`a`…`o`) but `getRawValue()` is public. Raw values map to Charles's own labels from `Transaction.a(ProxyEventStatus)`: 0 Initialising, 5/10/20 Sending request header, 23 Resolving, 24 Connecting, 25 Connected, 30 Sending request body, 40 Waiting for response, 50 Waiting for response body, 60 Receiving response body, 70 Received response body, 80 Complete, 90 Failed, 100 Blocked. | Decompiled `ProxyEventStatus`, `Transaction`. | 🔎 (80/90 seen live) |
| `Transaction` getters used are all non-obfuscated (`getMethod`, `getHost`, `getPort`, `getPath`, `getQuery`, `getRequestHeader`, `getResponseStatus`, `getStartTime`, `getDecodedResponseBodyStream`, …); `Fields` exposes `getFieldCount/getFieldName/getFieldValue`. | `javap`; live detail responses. | ✅ |
| `getDecoded*BodyStream()` transparently removes `Content-Encoding` (gzip etc.). | Live: gzip body 53 wire bytes → 531 decoded bytes. | ✅ |
| `CharlesContext`'s static initialiser only creates a `Logger`, so resolving it from the bootstrap thread has no side effects. | `javap -c` of `<clinit>`. | 🔎 |
| Charles configures java.util.logging during start-up (its own console format appears after our bootstrap begins); a JUL reconfiguration resets handlers added earlier. The agent therefore attaches its optional log file only after Charles is up. | Precaution based on JUL semantics; the post-start handler is verified to work, the failure mode was not reproduced. | ❓ |

## 4. Runtime constraints discovered

| Finding | Impact |
| --- | --- |
| **Charles's bundled runtime is a jlink image without `java.instrument`** (`jdk/release` MODULES list; launching with `-javaagent` fails: *"Could not find agent library instrument … Module java.instrument may be missing from runtime image."*). | `-javaagent` is impossible with the stock runtime. The bridge is instead started by a wrapper main class (`io.github.charlesmcp.agent.Launcher`) configured in `Charles.ini` (`classpath.N` + `main.class`). `-javaagent` remains supported for full JDKs. |
| `Charles.exe` reads `classpath.N`, `vmarg.N` and `main.class` from `Charles.ini`; `vmarg.N` indices must be contiguous. | The installer appends at the next free index. |
| Headless Charles (`-headless`) does **not** record until told to (Web Interface `/recording/start`); GUI Charles records on start. | Documented; `get_status.recording` reports it. |
| Headless mode never loads `charles_win32.dll`, so it cannot change the Windows system proxy. The Firefox integration *could* still run (`enableAtStartup` defaults to `true` in code). | Our test harness uses a private config with all integrations disabled. |
| Charles's config loader is line-ending sensitive: a CRLF-written `charles.config` failed with *"Unsupported element: configuration"* and Charles fell back to defaults. | Test configs are written with LF. |
| `-Djava.net.preferIPv6Addresses=true` is set by `Charles.ini`; `InetAddress.getLoopbackAddress()` would return `::1`. | The bridge binds explicitly to `127.0.0.1`. |
| Charles's own proxy listens on `0.0.0.0:<port>` (all interfaces) by default. | Not changed by this project; noted in docs/security.md. |
| Charles checks for updates on start (a `CONNECT www.charlesproxy.com` shows up as a recorded transaction). | Expected noise in captures. |
