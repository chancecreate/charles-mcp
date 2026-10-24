# Security policy

charles-mcp handles captured network traffic, which routinely contains credentials and personal data.
The threat model and its limitations are described in [docs/security.md](docs/security.md).

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private vulnerability reporting
("Report a vulnerability" on the Security tab) for this repository. Include affected versions, a description
and reproduction steps using synthetic data only. Never attach real captured traffic.

We aim to acknowledge reports within 7 days.

## Scope

In scope: authentication/authorisation of the loopback bridge, redaction bypasses, information leaks through
MCP tool output or logs, memory/DoS issues that affect Charles.

Out of scope: Charles Proxy itself, MCP clients and the models they talk to, and malware already running as
the same OS user.
