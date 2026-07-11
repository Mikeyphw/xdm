$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
$Solution = Join-Path $Root "app/XDM/XDM.Modern.sln"
$Project = Join-Path $Root "app/XDM/src/XDM.App/XDM.App.csproj"

Set-Location $Root
dotnet --version
dotnet restore $Solution
dotnet build $Solution --configuration Release --no-restore
dotnet test $Solution --configuration Release --no-build
dotnet run --project $Project --configuration Release --no-build -- --validate-bootstrap
