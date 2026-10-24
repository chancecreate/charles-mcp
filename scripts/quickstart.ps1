<#
.SYNOPSIS
  One-step setup used by Install.cmd / Uninstall.cmd in the release ZIP.

.DESCRIPTION
  1. Finds Charles 4.6.x and installs the bridge into it (asks for administrator rights once, because
     Charles lives in Program Files).
  2. Installs the charles-mcp MCP server from the bundled wheel with Python 3.11+.
  3. Registers the server with Claude Code if the `claude` command is available.
  Nothing else on the machine is changed.
#>
[CmdletBinding()]
param(
    [switch]$Uninstall,
    [string]$CharlesDir,
    [string]$Python,          # explicit python.exe (testing / unusual setups)
    [switch]$NoClient         # skip Claude Code registration
)

$ErrorActionPreference = "Stop"
$Here = $PSScriptRoot
$Installer = Join-Path $Here "install-windows.ps1"

function Say([string]$msg, [string]$color = "Gray") { Write-Host $msg -ForegroundColor $color }
function Step([string]$msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }

function Find-Charles {
    $candidates = @(
        $CharlesDir,
        "$env:ProgramFiles\Charles",
        "${env:ProgramFiles(x86)}\Charles",
        "$env:LOCALAPPDATA\Programs\Charles"
    ) | Where-Object { $_ }
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c "Charles.ini")) { return (Resolve-Path $c).Path }
    }
    return $null
}

function Find-Python {
    if ($Python) { return $Python }
    $probe = "import sys; print(sys.executable if sys.version_info >= (3, 11) else '')"
    foreach ($launcher in @(@("py", "-3"), @("python"))) {
        if (-not (Get-Command $launcher[0] -ErrorAction SilentlyContinue)) { continue }
        $extra = @($launcher | Select-Object -Skip 1)
        $exe = & $launcher[0] @extra -c $probe 2>$null
        if ($LASTEXITCODE -eq 0 -and $exe) { return ([string]$exe).Trim() }
    }
    return $null
}

function Invoke-Elevated([string[]]$installerArgs) {
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$Installer`"") + $installerArgs
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    $needsAdmin = -not (Test-WritableDir $script:Charles)
    if ($needsAdmin -and -not $isAdmin) {
        Say "Windows will ask for permission to update Charles's settings file..." "Yellow"
        $p = Start-Process powershell -Verb RunAs -ArgumentList $argList -Wait -PassThru
    } else {
        $p = Start-Process powershell -ArgumentList $argList -Wait -PassThru -NoNewWindow
    }
    if ($p.ExitCode -ne 0) { throw "The Charles step did not finish (exit code $($p.ExitCode))." }
}

function Test-WritableDir([string]$dir) {
    try {
        $probe = Join-Path $dir ".charles-mcp-write-test"
        [IO.File]::WriteAllText($probe, "x"); Remove-Item $probe -Force; return $true
    } catch { return $false }
}

try {
    $script:Charles = Find-Charles
    if (-not $script:Charles) {
        throw "Couldn't find Charles. Install Charles 4.6.8, or run: quickstart.ps1 -CharlesDir 'C:\path\to\Charles'"
    }

    if ($Uninstall) {
        Step "Removing the bridge from Charles ($script:Charles)"
        Invoke-Elevated @("-CharlesDir", "`"$script:Charles`"", "-Uninstall")
        if (-not $NoClient -and (Get-Command claude -ErrorAction SilentlyContinue)) {
            Step "Removing charles from Claude Code"
            & claude mcp remove charles 2>$null | Out-Null
        }
        $py = Find-Python
        if ($py) {
            Step "Uninstalling the MCP server"
            & $py -m pip uninstall -y charles-mcp | Out-Null
        }
        Say ""; Say "Done. Restart Charles." "Green"
        exit 0
    }

    Step "Installing the bridge into Charles ($script:Charles)"
    $jar = Join-Path $Here "charles-mcp-agent.jar"
    if (-not (Test-Path $jar)) { throw "charles-mcp-agent.jar is missing next to this script." }
    Invoke-Elevated @("-CharlesDir", "`"$script:Charles`"", "-AgentJar", "`"$jar`"")

    Step "Installing the MCP server"
    $py = Find-Python
    if (-not $py) {
        throw "Python 3.11 or newer is required. Install it from https://www.python.org/downloads/ " +
              "(tick 'Add python.exe to PATH'), then run Install.cmd again."
    }
    $wheel = Get-ChildItem $Here -Filter "charles_mcp-*.whl" | Select-Object -First 1
    if (-not $wheel) { throw "The charles_mcp wheel is missing next to this script." }
    & $py -m pip install --disable-pip-version-check --quiet --upgrade $wheel.FullName
    if ($LASTEXITCODE -ne 0) { throw "pip could not install the MCP server." }
    Say "Installed with $py"

    if (-not $NoClient) {
        if (Get-Command claude -ErrorAction SilentlyContinue) {
            Step "Adding charles to Claude Code"
            & claude mcp remove charles 2>$null | Out-Null
            & claude mcp add --scope user charles -- $py -m charles_mcp
        } else {
            Say ""
            Say "To use it from your MCP client, point it at this command:" "Yellow"
            Say "  $py -m charles_mcp"
        }
    }

    Say ""
    Say "All set. Restart Charles, browse something, and ask your agent about the traffic." "Green"
    Say "Check the connection any time with:  $py -m charles_mcp status"
    exit 0
} catch {
    Say ""
    Say "Setup stopped: $($_.Exception.Message)" "Red"
    exit 1
}
