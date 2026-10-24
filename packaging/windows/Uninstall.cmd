@echo off
rem Double-click to remove charles-mcp from Charles and Claude Code.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0quickstart.ps1" -Uninstall %*
echo.
pause
