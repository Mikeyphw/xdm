param(
    [Parameter(Mandatory = $true)][string]$Runtime,
    [string]$Version = "",
    [string]$OutputRoot = ""
)
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = (Get-Content (Join-Path $RepoRoot "VERSION") -Raw).Trim() }
if ([string]::IsNullOrWhiteSpace($OutputRoot)) { $OutputRoot = Join-Path $RepoRoot "artifacts/publish" }
$Output = Join-Path $OutputRoot $Runtime
$HostOutput = Join-Path $OutputRoot "native-host-$Runtime"
$UpdaterOutput = Join-Path $OutputRoot "updater-$Runtime"
Remove-Item -Recurse -Force $Output, $HostOutput, $UpdaterOutput -ErrorAction SilentlyContinue
dotnet publish (Join-Path $RepoRoot "app/XDM/src/XDM.App/XDM.App.csproj") -c Release -r $Runtime --self-contained true `
    -p:Version=$Version -p:ContinuousIntegrationBuild=true -p:PublishSingleFile=false -p:PublishTrimmed=false -o $Output
dotnet publish (Join-Path $RepoRoot "app/XDM/src/XDM.NativeHost/XDM.NativeHost.csproj") -c Release -r $Runtime --self-contained true `
    -p:Version=$Version -p:ContinuousIntegrationBuild=true -p:PublishSingleFile=true -p:PublishTrimmed=false -o $HostOutput
dotnet publish (Join-Path $RepoRoot "app/XDM/src/XDM.Updater/XDM.Updater.csproj") -c Release -r $Runtime --self-contained true `
    -p:Version=$Version -p:ContinuousIntegrationBuild=true -p:PublishSingleFile=true -p:PublishTrimmed=false -o $UpdaterOutput
Copy-Item (Join-Path $HostOutput "XDM.NativeHost.exe") (Join-Path $Output "XDM.NativeHost.exe") -Force
Copy-Item (Join-Path $UpdaterOutput "XDM.Updater.exe") (Join-Path $Output "XDM.Updater.exe") -Force
python (Join-Path $RepoRoot "app/XDM/eng/package-portable.py") --source $Output --output (Join-Path $RepoRoot "artifacts/packages") --name "xdm-modern-$Version-$Runtime"
