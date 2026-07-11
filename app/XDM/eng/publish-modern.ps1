param(
    [string]$OutputRoot = "",
    [string]$Runtime = "win-x64"
)
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $RepoRoot "artifacts/publish"
}
$Project = Join-Path $RepoRoot "app/XDM/src/XDM.App/XDM.App.csproj"
$Output = Join-Path $OutputRoot $Runtime
dotnet publish $Project -c Release -r $Runtime --self-contained true `
    -p:PublishSingleFile=false -p:PublishTrimmed=false -o $Output
Write-Host "Published XDM to $Output"
