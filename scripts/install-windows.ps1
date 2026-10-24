<#
.SYNOPSIS
  Installs (or removes) the charles-mcp bridge into a Charles 4.6.x installation on Windows.

.DESCRIPTION
  * Copies charles-mcp-agent.jar into <CharlesDir>\lib\
  * Backs up Charles.ini once to Charles.ini.pre-charles-mcp
  * Adds  classpath.N=lib/charles-mcp-agent.jar
  * Sets  main.class=io.github.charlesmcp.agent.Launcher   (the launcher then starts Charles's real main class)
  * Optionally adds -Dcharlesmcp.<key>=<value> vmargs

  It does NOT modify charles.jar, the system proxy, certificates, or your Charles preferences. Editing "C:\Program Files\Charles" requires an elevated PowerShell.

.EXAMPLE
  .\install-windows.ps1 -AgentJar ..\java\agent\target\charles-mcp-agent.jar
.EXAMPLE
  .\install-windows.ps1 -AgentOption debugLifecycle=true,"logFile=$env:LOCALAPPDATA\charles-mcp\agent.log"
.EXAMPLE
  .\install-windows.ps1 -Uninstall
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$CharlesDir = "C:\Program Files\Charles",
    [string]$AgentJar,
    [string[]]$AgentOption = @(),
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"
# Windows PowerShell 5.1 leaves $PSScriptRoot empty in parameter defaults, so the default is resolved here:
# next to this script (release ZIP) or the Maven build output (source checkout).
if (-not $AgentJar) {
    $candidates = @((Join-Path $PSScriptRoot "charles-mcp-agent.jar"),
                    (Join-Path $PSScriptRoot "..\java\agent\target\charles-mcp-agent.jar"))
    $AgentJar = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $AgentJar) { $AgentJar = $candidates[1] }
}
$Launcher = "io.github.charlesmcp.agent.Launcher"
$Ini = Join-Path $CharlesDir "Charles.ini"
$Backup = "$Ini.pre-charles-mcp"
$LibJar = Join-Path $CharlesDir "lib\charles-mcp-agent.jar"

if (-not (Test-Path $Ini)) { throw "Charles.ini not found in '$CharlesDir'. Pass -CharlesDir." }

function Get-CharlesVersion([string]$jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry = $zip.GetEntry("com/xk72/charles/init.properties")
        if (-not $entry) { $entry = $zip.GetEntry("com/charlesproxy/init.properties") }
        if (-not $entry) { return $null }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            foreach ($line in ($reader.ReadToEnd() -split "`n")) {
                if ($line -match '^\s*charlesVersion\s*=\s*(\S+)') { return $Matches[1] }
            }
        } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
    return $null
}

if ($Uninstall) {
    if (Test-Path $Backup) {
        if ($PSCmdlet.ShouldProcess($Ini, "restore from $Backup")) {
            Copy-Item $Backup $Ini -Force
            Remove-Item $Backup
        }
    } else {
        Write-Warning "No backup found ($Backup); leaving Charles.ini unchanged."
    }
    if (Test-Path $LibJar) {
        if ($PSCmdlet.ShouldProcess($LibJar, "remove")) { Remove-Item $LibJar }
    }
    Write-Host "charles-mcp removed. Restart Charles."
    return
}

$charlesJar = Join-Path $CharlesDir "lib\charles.jar"
$version = Get-CharlesVersion $charlesJar
if ($version -notmatch '^4\.6(\.|$)') { throw "Expected Charles 4.6.x, found '$version' in $charlesJar" }
if (-not (Test-Path $AgentJar)) { throw "Agent JAR not found at '$AgentJar'. Build it with: mvn -f java/pom.xml package" }

$raw = [System.IO.File]::ReadAllText($Ini)
$nl = if ($raw.Contains("`r`n")) { "`r`n" } else { "`n" }
$lines = [System.Collections.Generic.List[string]]($raw -split "\r?\n")
while ($lines.Count -gt 0 -and $lines[$lines.Count - 1] -eq "") { $lines.RemoveAt($lines.Count - 1) }

# Only the global section (before the first [Section]) holds launcher keys.
$sectionStart = $lines.Count
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^\s*\[') { $sectionStart = $i; break } }

function Find-Key([string]$pattern) {
    for ($i = 0; $i -lt $sectionStart; $i++) { if ($lines[$i] -match $pattern) { return $i } }
    return -1
}
function Next-Index([string]$prefix) {
    $n = 1
    while ((Find-Key "^\s*$([regex]::Escape($prefix))\.$n\s*=") -ge 0) { $n++ }
    return $n
}
function Insert-Global([string]$line) {
    $script:lines.Insert($script:sectionStart, $line)
    $script:sectionStart++
}

$mainIdx = Find-Key '^\s*main\.class\s*='
if ($mainIdx -lt 0) { throw "main.class not found in Charles.ini" }
$currentMain = ($lines[$mainIdx] -split '=', 2)[1].Trim()
if ($currentMain -ne $Launcher -and $currentMain -ne "com.xk72.charles.gui.MainWithClassLoader") {
    throw "Unexpected main.class '$currentMain'; refusing to edit (another tool may have changed Charles.ini)."
}

if (-not (Test-Path $Backup)) {
    if ($PSCmdlet.ShouldProcess($Ini, "back up to $Backup")) { Copy-Item $Ini $Backup }
}
if ($PSCmdlet.ShouldProcess($LibJar, "copy agent")) { Copy-Item $AgentJar $LibJar -Force }

$lines[$mainIdx] = "main.class=$Launcher"
if ((Find-Key '^\s*classpath\.\d+\s*=\s*lib/charles-mcp-agent\.jar\s*$') -lt 0) {
    Insert-Global ("classpath.{0}=lib/charles-mcp-agent.jar" -f (Next-Index "classpath"))
}
foreach ($opt in $AgentOption) {
    $k, $v = $opt -split '=', 2
    if (-not $k -or $null -eq $v) { throw "AgentOption must be key=value (got '$opt')" }
    $existing = Find-Key ("^\s*vmarg\.\d+\s*=\s*-Dcharlesmcp\.{0}=" -f [regex]::Escape($k))
    $value = "-Dcharlesmcp.$k=$v"
    if ($existing -ge 0) {
        $lines[$existing] = ($lines[$existing] -split '=', 2)[0] + "=" + $value
    } else {
        Insert-Global ("vmarg.{0}={1}" -f (Next-Index "vmarg"), $value)
    }
}

if ($PSCmdlet.ShouldProcess($Ini, "write")) {
    [System.IO.File]::WriteAllText($Ini, ($lines -join $nl) + $nl, [System.Text.Encoding]::ASCII)
}
Write-Host "charles-mcp installed into $CharlesDir. Restart Charles; the bridge writes"
Write-Host "  $env:LOCALAPPDATA\charles-mcp\bridge.json"
Write-Host "once Charles is up. Undo with: .\install-windows.ps1 -Uninstall"
