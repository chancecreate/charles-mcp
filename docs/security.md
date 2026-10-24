# Security model

Charles sees everything it proxies: credentials, cookies, personal and medical data, session tokens.
charles-mcp exposes a *view* of that to MCP clients, so it is built default-deny.

> **Important:** your MCP client may send tool results to a cloud LLM. Anything a tool returns can leave
> your machine through the client. charles-mcp itself never sends traffic anywhere, but it cannot control
> what your client does with tool output. Enable body access only for traffic you are comfortable sharing
> with your model provider, and use host allowlists.

## Threats and controls

| Threat | Control | Tested by |
| --- | --- | --- |
| Another local process reads captured traffic from the bridge | Bridge binds `127.0.0.1` only; every connection must present a 256-bit per-process token (constant-time compare) within 5 s; failures are counted and slowed. The token lives only in the discovery file, written atomically with an owner-only ACL (Windows) / `0600` (POSIX). | `BridgeServerTest.bindsToIpv4LoopbackOnly`, `rejectsMissingOrWrongToken`, `tokenComparisonIsExact`; Python `test_wrong_token_is_rejected`; live hostile-client run |
| Remote network access | No non-loopback bind, no remote dependencies at runtime, no telemetry. The sidecar refuses discovery files pointing at non-loopback hosts. | `test_invalid_discovery_refused` |
| Credentials reach the model | Header values redacted **inside the JVM** before leaving it (Authorization/Proxy-Authorization keep only the scheme; Cookie, Set-Cookie, API-key and `*token*`/`*secret*`/`*session*` headers). The sidecar re-applies header rules and additionally redacts URL query values and userinfo, JSON/form body fields by key (recursively), and well-known token shapes (JWT, bearer, AWS/Google/GitHub/Slack/Stripe keys, PEM private keys) in all text. Redaction cannot be disabled; you can only add rules. | `HeaderRedactorTest`, `test_redaction.py`, `test_get_request_redacts_credentials`, live `mcp_smoke.py` leakage check |
| Bodies leak by default | Body access is **off** unless `CHARLES_MCP_ALLOW_BODIES=1` / `allow_bodies = true`. Even then: per-request byte cap (16 KiB default, 256 KiB hard cap in the sidecar; 1 MiB default in the bridge), character cap, explicit truncation flags. The bridge has its own kill switch (`-Dcharlesmcp.allowBodies=false`). | `test_bodies_default_deny`, `bodiesCanBeDisabledAtTheBridge` |
| Binary data decoded into text / logs | Binary bodies (by MIME type or content sniffing: NUL bytes, invalid UTF-8, control characters) are returned only as a placeholder with size + SHA-256. The agent never logs bodies, headers, paths or queries (debug lifecycle logs contain method, host, status only). | `test_binary_is_never_decoded` |
| Searching for a secret confirms it exists | Search runs over **redacted** text only. | `test_search` |
| Prompt injection via captured content | Server instructions and every body view mark captured content as untrusted third-party data (`untrusted: true`, `notice`). The tools are read-only, so injected instructions cannot make the server change traffic, replay requests or touch certificates. **Limitation:** an MCP client/model can still be influenced by text it reads; this is mitigation, not prevention. | `test_prompt_injection_content_is_flagged` |
| Feedback loops / noise | Bridge excludes its own port; sidecar denylists `control.charles` by default; host allow/deny globs. | `ownBridgeTrafficIsExcluded`, `test_host_allow_and_deny_lists` |
| Memory exhaustion inside Charles | Bounded queue (drop + count), bounded event log by count/age/bytes, bounded transaction table, weak references to Charles objects, capped headers. | `EventStoreTest`, `EventPipelineTest` |
| Bridge denial of service | Max 4 concurrent connections, 64 KiB request lines, 5 s auth timeout, 30 min idle timeout, page size ≤ 1000. Bridge work never runs on proxy threads. | `connectionLimitIsEnforced`, `oversizedRequestIsRejected`, live stalled-client test |
| Malformed input | Strict bounded JSON parser (depth ≤ 16, number length ≤ 32), strict Pydantic tool schemas with length/range limits, transaction-id pattern. | `JsonTest`, `test_errors_are_reported_not_raised` |
| Writes / dangerous actions | v1 has **no** write operations: no replay, breakpoints, recording control, certificate/key access or fuzzing. Future write operations must live in a separate, opt-in module. | `BridgeServerTest` (`clear_session` → `unknown_op`) |
| Persistence of raw captures | Nothing is persisted by default. The sidecar keeps metadata in memory only; the agent keeps state in memory only. | — |

## Known limitations

* Redaction is pattern/key based. Secrets with no recognisable name or format (e.g. a password in a free-text
  field called `note`, a token inside a custom binary protocol) **will not be caught**.
* URLs in bridge *events* (as opposed to MCP output) are not redacted; only an authenticated local client
  can read them.
* Any process running as **your Windows user** can read the discovery file and therefore the token. The
  bridge protects against other users and unauthenticated local clients, not against malware running as you
  (which could read Charles's memory anyway).
* Charles itself listens on all interfaces (`0.0.0.0:8888` by default) and has its own access control; this
  project does not change that.
* The optional debug log (`charlesmcp.debugLifecycle`) records hostnames and methods.

## Reporting

See [SECURITY.md](../SECURITY.md).
