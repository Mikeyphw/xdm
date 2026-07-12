param(
    [string]$Runtime = "win-x64",
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $RepoRoot "artifacts/smoke/$Runtime"
}

$Project = Join-Path $RepoRoot "app/XDM/src/XDM.App/XDM.App.csproj"
$HostProject = Join-Path $RepoRoot "app/XDM/src/XDM.NativeHost/XDM.NativeHost.csproj"
$HostOutput = "$Output-native-host"
if (Test-Path $Output) {
    Remove-Item -Recurse -Force $Output
}
if (Test-Path $HostOutput) {
    Remove-Item -Recurse -Force $HostOutput
}

dotnet publish $Project `
    --configuration Release `
    --runtime $Runtime `
    --self-contained true `
    -p:PublishSingleFile=false `
    -p:PublishTrimmed=false `
    --output $Output

dotnet publish $HostProject `
    --configuration Release `
    --runtime $Runtime `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:PublishTrimmed=false `
    --output $HostOutput

$HostExecutable = Join-Path $HostOutput "XDM.NativeHost.exe"
$PackagedHost = Join-Path $Output "XDM.NativeHost.exe"
if (-not (Test-Path $HostExecutable)) {
    throw "Published native host was not found: $HostExecutable"
}
Copy-Item $HostExecutable $PackagedHost -Force

$Executable = Join-Path $Output "XDM.exe"
if (-not (Test-Path $Executable)) {
    throw "Published executable was not found: $Executable"
}
if (-not (Test-Path $PackagedHost)) {
    throw "Packaged native host was not found: $PackagedHost"
}

& $Executable --validate-bootstrap
if ($LASTEXITCODE -ne 0) {
    throw "Published bootstrap validation failed with exit code $LASTEXITCODE"
}

Remove-Item -Recurse -Force $HostOutput -ErrorAction SilentlyContinue
Write-Host "Package smoke test passed: $Output"
