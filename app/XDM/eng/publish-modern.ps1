param(
    [string]$OutputRoot = "",
    [string]$Runtime = "win-x64"
)
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $RepoRoot "artifacts/publish"
}
$AppProject = Join-Path $RepoRoot "app/XDM/src/XDM.App/XDM.App.csproj"
$HostProject = Join-Path $RepoRoot "app/XDM/src/XDM.NativeHost/XDM.NativeHost.csproj"
$Output = Join-Path $OutputRoot $Runtime
$HostOutput = Join-Path $OutputRoot "native-host-$Runtime"
dotnet publish $AppProject -c Release -r $Runtime --self-contained true `
    -p:PublishSingleFile=false -p:PublishTrimmed=false -o $Output
dotnet publish $HostProject -c Release -r $Runtime --self-contained true `
    -p:PublishSingleFile=true -p:PublishTrimmed=false -o $HostOutput
Copy-Item (Join-Path $HostOutput "XDM.NativeHost.exe") (Join-Path $Output "XDM.NativeHost.exe") -Force
Write-Host "Published XDM and native host to $Output"
