param([string]$Version = "")
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = (Get-Content (Join-Path $RepoRoot "VERSION") -Raw).Trim() }
$Packages = Join-Path $RepoRoot "artifacts/packages"
Remove-Item -Recurse -Force $Packages -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Packages | Out-Null
foreach ($Runtime in @("win-x64", "win-arm64")) {
    & (Join-Path $PSScriptRoot "publish-one.ps1") -Runtime $Runtime -Version $Version
}
