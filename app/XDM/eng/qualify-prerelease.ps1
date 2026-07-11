param([string]$Version = "")
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = (Get-Content (Join-Path $RepoRoot "VERSION") -Raw).Trim()
}

& (Join-Path $PSScriptRoot "validate-modern.ps1")
& (Join-Path $PSScriptRoot "package-windows.ps1") -Version $Version

$Archive = Join-Path $RepoRoot "artifacts/packages/xdm-modern-$Version-win-x64.zip"
if (-not (Test-Path $Archive)) { throw "Package not found: $Archive" }
$Temp = Join-Path ([System.IO.Path]::GetTempPath()) ("xdm-qualify-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $Temp | Out-Null
try {
    Expand-Archive -Path $Archive -DestinationPath $Temp
    $App = Join-Path $Temp "XDM.exe"
    $Host = Join-Path $Temp "XDM.NativeHost.exe"
    if (-not (Test-Path $App)) { throw "XDM.exe missing from package." }
    if (-not (Test-Path $Host)) { throw "XDM.NativeHost.exe missing from package." }
    & $App --validate-bootstrap
    if ($LASTEXITCODE -ne 0) { throw "Packaged bootstrap validation failed." }

    Get-FileHash -Algorithm SHA256 $Archive |
        Format-List |
        Out-File (Join-Path $RepoRoot "artifacts/packages/SHA256SUMS.windows.txt")
}
finally {
    Remove-Item -Recurse -Force $Temp -ErrorAction SilentlyContinue
}
