#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SOLUTION="$ROOT/app/XDM/XDM.Modern.sln"
PROJECT="$ROOT/app/XDM/src/XDM.App/XDM.App.csproj"

cd "$ROOT"
dotnet --version
dotnet restore "$SOLUTION"
dotnet build "$SOLUTION" --configuration Release --no-restore
dotnet test "$SOLUTION" --configuration Release --no-build
dotnet run --project "$PROJECT" --configuration Release --no-build -- --validate-bootstrap
