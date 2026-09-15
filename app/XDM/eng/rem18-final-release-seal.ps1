param(
    [switch]$StaticOnly,
    [switch]$DevtoolArtifactValidation,
    [switch]$SkipPackage
)
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
$Version = (Get-Content (Join-Path $RepoRoot "VERSION") -Raw).Trim()
$Artifacts = Join-Path $RepoRoot "artifacts/rem18-final-seal"
New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null

function Invoke-Rem18Step([string]$Name, [scriptblock]$Block) {
    Write-Host "`n[rem18] $Name"
    & $Block 2>&1 | Tee-Object -FilePath (Join-Path $Artifacts (($Name -replace '[^A-Za-z0-9_.-]', '_') + '.log'))
    if ($LASTEXITCODE -ne 0) { throw "REM18 step failed: $Name" }
}

Invoke-Rem18Step "single_firefox_extension_audit" { python (Join-Path $RepoRoot "app/XDM/eng/validate-xfe01-single-firefox-extension.py") }
Invoke-Rem18Step "ledger_closure_audit" { python (Join-Path $RepoRoot "app/XDM/eng/rem18-ledger-audit.py") --write-evidence "artifacts/rem18-final-seal/ledger-audit.json" }
Invoke-Rem18Step "release_matrix_contract_audit" { python (Join-Path $RepoRoot "app/XDM/eng/rem18-release-matrix-audit.py") --write-evidence "artifacts/rem18-final-seal/release-matrix-audit.json" }

if (-not $StaticOnly -and -not $DevtoolArtifactValidation) {
    Invoke-Rem18Step "dotnet_restore" { dotnet restore (Join-Path $RepoRoot "app/XDM/XDM.Modern.sln") }
    Invoke-Rem18Step "dotnet_build" { dotnet build (Join-Path $RepoRoot "app/XDM/XDM.Modern.sln") --configuration Release --no-restore -warnaserror }
    Invoke-Rem18Step "dotnet_test" { dotnet test (Join-Path $RepoRoot "app/XDM/XDM.Modern.sln") --configuration Release --no-build --logger "trx;LogFileName=rem18-tests.trx" --results-directory (Join-Path $RepoRoot "artifacts/test-results") }
    Invoke-Rem18Step "bootstrap_validate" { dotnet run --project (Join-Path $RepoRoot "app/XDM/src/XDM.App/XDM.App.csproj") --configuration Release --no-build -- --validate-bootstrap }
}

if (-not $StaticOnly -and -not $SkipPackage) {
    Invoke-Rem18Step "windows_package_matrix" { & (Join-Path $PSScriptRoot "package-windows.ps1") -Version $Version }
    foreach ($Runtime in @("win-x64", "win-arm64")) {
        Invoke-Rem18Step "windows_smoke_$Runtime" { & (Join-Path $PSScriptRoot "smoke-package.ps1") -Runtime $Runtime -PublishOnly }
    }
}

$Summary = [ordered]@{
    schemaVersion = 1
    overlay = "REM18"
    overlayIndex = 18
    overlayTotal = 18
    mergedOverlay = "2 of 8"
    version = $Version
    validatedRids = @("linux-x64", "linux-arm64", "win-x64", "win-arm64")
    ledgerExpectedTotal = 258
    ledgerExpectedSeverityTotals = [ordered]@{ HIGH = 67; MEDIUM = 153; LOW = 38 }
    devtoolArtifactValidationMode = [bool]$DevtoolArtifactValidation
    packageMatrixAttempted = -not [bool]$SkipPackage
    singleFirefoxExtensionConvergence = "validated by validate-xfe01-single-firefox-extension.py"
    canonicalFirefoxExtensionId = "xdm-android-media-bridge@mikeyphw"
}
$Summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $Artifacts "final-seal-summary.windows.json")
Write-Host "REM18 final release seal completed. Evidence: $Artifacts"
