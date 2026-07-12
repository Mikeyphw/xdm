$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path

& (Join-Path $PSScriptRoot "validate-modern.ps1")

$Forbidden = @(
    "app/XDM/XDM.Wpf.UI",
    "app/XDM/XDM.Gtk.UI",
    "app/XDM/XDM.WinForms.IntegrationUI",
    "app/XDM/MsixPackaging",
    "app/XDM/XDM.Msix.AutoLaunch",
    "app/XDM/XDM.App.Host",
    "app/XDM/XDM.Core",
    "app/XDM/XDM.Messaging",
    "app/XDM/XDM.Compatibility",
    "app/XDM/XDM.Tests",
    "app/XDM/XDM_Tests",
    "app/XDM/MockServer",
    "app/XDM/XDM_CoreFx.sln"
)
foreach ($Relative in $Forbidden) {
    $Candidate = Join-Path $RepoRoot $Relative
    if (Test-Path $Candidate) {
        throw "Legacy application source is still present: $Relative"
    }
}

& (Join-Path $PSScriptRoot "smoke-package.ps1") -Runtime "win-x64"
Write-Host "XDM final gate passed on Windows."
