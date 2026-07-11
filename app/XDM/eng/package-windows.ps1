param([string]$Version = "0.1.0-dev")
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
$PublishRoot = Join-Path $RepoRoot "artifacts/publish"
& (Join-Path $PSScriptRoot "publish-modern.ps1") -OutputRoot $PublishRoot -Runtime "win-x64"
$Packages = Join-Path $RepoRoot "artifacts/packages"
New-Item -ItemType Directory -Force -Path $Packages | Out-Null
$Archive = Join-Path $Packages "xdm-modern-$Version-win-x64.zip"
if (Test-Path $Archive) { Remove-Item $Archive -Force }
Compress-Archive -Path (Join-Path $PublishRoot "win-x64/*") -DestinationPath $Archive
Write-Host "Created $Archive"
