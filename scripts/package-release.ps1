<#
.SYNOPSIS
  Builds the release assets into dist/: the Windows ZIP, the agent JAR, the Python wheel and SHA256SUMS.txt.
  Used by .github/workflows/release.yml and runnable locally.
.EXAMPLE
  .\scripts\package-release.ps1 -Version 0.1.0
#>
param(
    [Parameter(Mandatory)][string]$Version,
    [string]$OutDir = (Join-Path $PSScriptRoot "..\dist"),
    [switch]$SkipTests
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

# The tag, the Python package and the agent must all agree on the version.
$pyVersion = (Select-String -Path "$Root\python\pyproject.toml" -Pattern '^version\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$javaVersion = (Select-String -Path "$Root\java\agent\src\main\java\io\github\charlesmcp\agent\AgentInfo.java" -Pattern 'VERSION = "([^"]+)"').Matches[0].Groups[1].Value
# Pre-release tags (e.g. 0.1.0-rc.1) package the same code, so compare the base version.
$baseVersion = $Version.Split("-")[0]
if ($pyVersion -ne $baseVersion -or $javaVersion -ne $baseVersion) {
    throw "Version mismatch: requested $Version, pyproject $pyVersion, AgentInfo $javaVersion"
}

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

Write-Host "==> Building agent JAR"
Push-Location "$Root\java"
try {
    $mvnArgs = @("-B", "-q", "package")
    if ($SkipTests) { $mvnArgs += "-DskipTests" }
    & .\mvnw.cmd @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
} finally { Pop-Location }
$jar = "$Root\java\agent\target\charles-mcp-agent.jar"

Write-Host "==> Building Python wheel"
python -m pip install --quiet --disable-pip-version-check build
python -m build --wheel --outdir "$OutDir\wheel" "$Root\python"
if ($LASTEXITCODE -ne 0) { throw "wheel build failed" }
$wheel = Get-ChildItem "$OutDir\wheel" -Filter "charles_mcp-$baseVersion-*.whl" | Select-Object -First 1
if (-not $wheel) { throw "wheel not found" }

Write-Host "==> Assembling Windows ZIP"
$name = "charles-mcp-$Version-windows"
$stage = Join-Path $OutDir $name
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item $jar, $wheel.FullName, "$Root\scripts\install-windows.ps1", "$Root\scripts\quickstart.ps1" $stage
Copy-Item "$Root\packaging\windows\*" $stage
Copy-Item "$Root\LICENSE" "$stage\LICENSE.txt"
Compress-Archive -Path $stage -DestinationPath "$OutDir\$name.zip" -Force

Copy-Item $jar "$OutDir\charles-mcp-agent-$Version.jar"
Copy-Item $wheel.FullName $OutDir
Remove-Item $stage, "$OutDir\wheel" -Recurse -Force

$sums = Get-ChildItem $OutDir -File | Sort-Object Name | ForEach-Object {
    "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name
}
Set-Content -Path "$OutDir\SHA256SUMS.txt" -Value $sums -Encoding ascii
Get-ChildItem $OutDir | Format-Table Name, Length -AutoSize
