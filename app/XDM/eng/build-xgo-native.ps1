param(
    [Parameter(Mandatory = $true)][string]$Runtime,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ($Runtime -notin @("win-x64", "win-arm64")) { throw "Unsupported Windows xdmcore runtime: $Runtime" }
$Arch = if ($Runtime -eq "win-x64") { "amd64" } else { "arm64" }
$Library = "xdmcore.dll"
$Provenance = "xdmcore-provenance.json"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
if (-not [string]::IsNullOrWhiteSpace($env:XDM_XGO_NATIVE_DIR)) {
    $Prebuilt = Join-Path $env:XDM_XGO_NATIVE_DIR "$Runtime/$Library"
    $PrebuiltProvenance = Join-Path $env:XDM_XGO_NATIVE_DIR "$Runtime/$Provenance"
    if (Test-Path $Prebuilt) {
        if (-not (Test-Path $PrebuiltProvenance)) { throw "Prebuilt xdmcore $Runtime is missing $Provenance provenance." }
        Copy-Item $Prebuilt (Join-Path $OutputDirectory $Library) -Force
        Copy-Item $PrebuiltProvenance (Join-Path $OutputDirectory $Provenance) -Force
        Push-Location $RepoRoot
        try {
            & go run ./engine/cmd/xgo-provenance --validate (Join-Path $OutputDirectory $Provenance) --goos windows --goarch $Arch --build-mode c-shared
            if ($LASTEXITCODE -ne 0) { throw "prebuilt xdmcore provenance validation failed with exit code $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
        return
    }
}
$HostOs = (& go env GOOS).Trim()
$HostArch = (& go env GOARCH).Trim()
if ($HostOs -ne "windows" -or $HostArch -ne $Arch) {
    throw "xdmcore $Runtime must be built on a matching host or supplied via XDM_XGO_NATIVE_DIR. host=$HostOs-$HostArch"
}
$Revision = (& git -C $RepoRoot rev-parse HEAD).Trim()
$GoVersion = (& go env GOVERSION).Trim()
$Status = @(& git -C $RepoRoot status --porcelain --untracked-files=no)
$Modified = $Status.Count -gt 0
$ModifiedText = if ($Modified) { "true" } else { "false" }
$LdFlags = "-X github.com/subhra74/xdm/engine/api/v1.BuildRevision=$Revision -X github.com/subhra74/xdm/engine/api/v1.BuildModified=$ModifiedText -X github.com/subhra74/xdm/engine/api/v1.BuildMode=c-shared"
Push-Location $RepoRoot
try {
    $env:CGO_ENABLED = "1"
    $env:GOOS = "windows"
    $env:GOARCH = $Arch
    & go build -trimpath -buildmode=c-shared -ldflags $LdFlags -o (Join-Path $OutputDirectory $Library) ./engine/bridge/cabi
    if ($LASTEXITCODE -ne 0) { throw "xdmcore build failed with exit code $LASTEXITCODE" }
    & go run ./engine/cmd/xgo-provenance --revision $Revision "--modified=$ModifiedText" --go-version $GoVersion --goos windows --goarch $Arch --build-mode c-shared --output (Join-Path $OutputDirectory $Provenance)
    if ($LASTEXITCODE -ne 0) { throw "xdmcore provenance generation failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
if (-not (Test-Path (Join-Path $OutputDirectory $Library))) { throw "xdmcore output missing" }
if (-not (Test-Path (Join-Path $OutputDirectory $Provenance))) { throw "xdmcore provenance output missing" }
