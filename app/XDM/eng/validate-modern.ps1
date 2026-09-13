$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Root = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
$Solution = Join-Path $Root "app/XDM/XDM.Modern.sln"
$Project = Join-Path $Root "app/XDM/src/XDM.App/XDM.App.csproj"

function Invoke-Native([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE: $($Arguments -join ' ')" }
}

Push-Location $Root
try {
    Invoke-Native dotnet @("--version")
    Invoke-Native dotnet @("restore", $Solution)
    Invoke-Native dotnet @("build", $Solution, "--configuration", "Release", "--no-restore", "-warnaserror")
    Invoke-Native dotnet @("test", $Solution, "--configuration", "Release", "--no-build")
    Invoke-Native dotnet @("run", "--project", $Project, "--configuration", "Release", "--no-build", "--", "--validate-bootstrap")
}
finally { Pop-Location }
