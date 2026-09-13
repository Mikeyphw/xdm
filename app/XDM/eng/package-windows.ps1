param(
    [string]$Version = "",
    [switch]$OfficialRelease,
    [string]$CertificateBase64 = $env:WINDOWS_SIGNING_CERTIFICATE,
    [string]$CertificatePassword = $env:WINDOWS_SIGNING_PASSWORD
)
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = (Get-Content (Join-Path $RepoRoot "VERSION") -Raw).Trim() }
$Packages = Join-Path $RepoRoot "artifacts/packages"
Remove-Item -Recurse -Force $Packages -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Packages | Out-Null
foreach ($Runtime in @("win-x64", "win-arm64")) {
    & (Join-Path $PSScriptRoot "publish-one.ps1") -Runtime $Runtime -Version $Version
    $PublishDirectory = Join-Path $RepoRoot "artifacts/publish/$Runtime"
    if ($OfficialRelease) {
        if ([string]::IsNullOrWhiteSpace($CertificateBase64) -or [string]::IsNullOrWhiteSpace($CertificatePassword)) {
            throw "Official Windows releases require WINDOWS_SIGNING_CERTIFICATE and WINDOWS_SIGNING_PASSWORD."
        }
        & (Join-Path $PSScriptRoot "sign-windows.ps1") -PublishDirectory $PublishDirectory -CertificateBase64 $CertificateBase64 -CertificatePassword $CertificatePassword
        & (Join-Path $PSScriptRoot "verify-windows-signatures.ps1") -PublishDirectory $PublishDirectory
        python (Join-Path $RepoRoot "app/XDM/eng/package-portable.py") --source $PublishDirectory --output (Join-Path $RepoRoot "artifacts/packages") --name "xdm-modern-$Version-$Runtime"
    }
}
