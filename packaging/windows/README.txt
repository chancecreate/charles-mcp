charles-mcp for Windows
=======================

Lets AI coding agents (Claude Code, Codex, Cursor) read the traffic Charles Proxy captures.

Before you start
----------------
* Charles Proxy 4.6.x installed (4.6.8 is the latest 4.x release).
* Python 3.11 or newer: https://www.python.org/downloads/
  (on the first installer screen, tick "Add python.exe to PATH").
* Close Charles.

Install
-------
1. Unzip this folder anywhere.
2. Double-click Install.cmd.
3. Click "Yes" when Windows asks for permission (this lets it update Charles's settings file).
4. Start Charles and make sure it's recording.

If you use Claude Code, it's set up automatically. Ask it something like
"what requests failed in Charles in the last few minutes?"

For other MCP clients, use the command the installer prints, for example:
  C:\...\python.exe -m charles_mcp

Request bodies are hidden by default. To let your agent see them, add the environment variable
CHARLES_MCP_ALLOW_BODIES=1 to your MCP client's config for this server.

Uninstall
---------
Double-click Uninstall.cmd, then restart Charles.

What gets changed
-----------------
* charles-mcp-agent.jar is copied into Charles's lib folder.
* Charles.ini gets two lines (a backup is saved as Charles.ini.pre-charles-mcp).
* The charles-mcp Python package is installed.
Nothing else: no system proxy, certificate or Charles preference changes.

More: https://github.com/chancecreate/charles-mcp
