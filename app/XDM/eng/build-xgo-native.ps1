param(
    [Parameter(Mandatory = $true)][string]$Runtime,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ($Runtime -notin @("win-x64", "win-arm64")) { throw "Unsupported Windows xdmcore runtime: $Runtime" }
$Arch = if ($Runtime -eq "win-x64") { "amd64" } else { "arm64" }
$Library = "xdmcore.dll"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
if (-not [string]::IsNullOrWhiteSpace($env:XDM_XGO_NATIVE_DIR)) {
    $Prebuilt = Join-Path $env:XDM_XGO_NATIVE_DIR "$Runtime/$Library"
    if (Test-Path $Prebuilt) {
        Copy-Item $Prebuilt (Join-Path $OutputDirectory $Library) -Force
        return
    }
}
$HostOs = (& go env GOOS).Trim()
$HostArch = (& go env GOARCH).Trim()
if ($HostOs -ne "windows" -or $HostArch -ne $Arch) {
    throw "xdmcore $Runtime must be built on a matching host or supplied via XDM_XGO_NATIVE_DIR. host=$HostOs-$HostArch"
}
Push-Location $RepoRoot
try {
    $env:CGO_ENABLED = "1"
    $env:GOOS = "windows"
    $env:GOARCH = $Arch
    & go build -trimpath -buildmode=c-shared -o (Join-Path $OutputDirectory $Library) ./engine/bridge/cabi
    if ($LASTEXITCODE -ne 0) { throw "xdmcore build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
if (-not (Test-Path (Join-Path $OutputDirectory $Library))) { throw "xdmcore output missing" }
