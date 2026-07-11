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
if (Test-Path $Output) {
    Remove-Item -Recurse -Force $Output
}

dotnet publish $Project `
    --configuration Release `
    --runtime $Runtime `
    --self-contained true `
    -p:PublishSingleFile=false `
    -p:PublishTrimmed=false `
    --output $Output

$Executable = Join-Path $Output "XDM.exe"
if (-not (Test-Path $Executable)) {
    throw "Published executable was not found: $Executable"
}

& $Executable --validate-bootstrap
if ($LASTEXITCODE -ne 0) {
    throw "Published bootstrap validation failed with exit code $LASTEXITCODE"
}

Write-Host "Package smoke test passed: $Output"
