# Compatibility

**Supported:** Charles Proxy **4.6.x** on **Windows**, using Charles's bundled Java 11 runtime.
Tested end to end on **4.6.2** and **4.6.8**. Other versions are refused (fail closed).

## Tested environment

* Charles 4.6.2 (`charlesVersion=4.6.2`) and **4.6.8** (official MSI, SHA-256
  `e5619c0258cd43a8d6ad7a1e23ce31cb445519f649725a15005ab7b1c70f2c1b`), GUI and headless mode. Both ship the
  same Java 11.0.17 runtime and `Charles.ini` layout; 4.6.8 only reshuffles obfuscated class names, which the
  agent doesn't use.
* Charles's bundled JDK `11.0.17` (jlink image, **no `java.instrument` module**).
* Windows 10 Enterprise LTSC 2021 (10.0.19044).
* Build: Temurin 21 targeting Java 11 (`--release 11`); Python 3.11, MCP Python SDK 1.27.2.

The agent does not modify `charles.jar`. It works with whatever 4.6.x JAR you run, as long as the internal
API shape it checks at startup matches (see below).

## Fail-closed behaviour

| Situation | Agent (inside Charles) |
| --- | --- |
| Charles version outside 4.6.x (including Charles 5) | bridge stays inert, logs a warning, `agent_state=unsupported` |
| `ProxyListener` is missing any expected callback (extra ones are ignored) | refuses to attach (`IncompatibleCharlesException`) |
| ProxyManager members missing or ambiguous | refuses to attach |
| Required `Transaction` / `Fields` getter missing | refuses to attach |
| Charles never finishes starting (e.g. CLI `convert` mode) | gives up after 15 minutes, no bridge |

In every case Charles itself starts and runs normally. A developer override exists
(`-Dcharlesmcp.allowUnsupported=true`) for investigation only; results with it are unsupported.

## Not empirically verified

* HTTPS with SSL proxying, HTTP/2, WebSockets, breakpoints, rewrite and throttling through the bridge (the
  listener is passive, but these paths were not exercised in tests).
* Charles on macOS/Linux (the agent is platform-neutral Java; the installer and paths are Windows-only).
