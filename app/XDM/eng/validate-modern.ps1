$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
$solution = Join-Path $root "app/XDM/XDM.Modern.sln"
$project = Join-Path $root "app/XDM/src/XDM.App/XDM.App.csproj"

Set-Location $root
dotnet --version
dotnet restore $solution
dotnet build $solution --configuration Release --no-restore
dotnet run --project $project --configuration Release --no-build -- --validate-bootstrap
