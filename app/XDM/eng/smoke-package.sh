#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
runtime="${1:-linux-x64}"
output="${2:-$repo_root/artifacts/smoke/$runtime}"
project="$repo_root/app/XDM/src/XDM.App/XDM.App.csproj"

rm -rf "$output"
dotnet publish "$project" \
  --configuration Release \
  --runtime "$runtime" \
  --self-contained true \
  -p:PublishSingleFile=false \
  -p:PublishTrimmed=false \
  --output "$output"

test -x "$output/XDM"
"$output/XDM" --validate-bootstrap
printf 'Package smoke test passed: %s\n' "$output"
