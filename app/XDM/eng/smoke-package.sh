#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
runtime="${1:-linux-x64}"
output="${2:-$repo_root/artifacts/smoke/$runtime}"
project="$repo_root/app/XDM/src/XDM.App/XDM.App.csproj"
host_project="$repo_root/app/XDM/src/XDM.NativeHost/XDM.NativeHost.csproj"
host_output="${output}-native-host"

rm -rf "$output" "$host_output"
dotnet publish "$project" \
  --configuration Release \
  --runtime "$runtime" \
  --self-contained true \
  -p:PublishSingleFile=false \
  -p:PublishTrimmed=false \
  --output "$output"

dotnet publish "$host_project" \
  --configuration Release \
  --runtime "$runtime" \
  --self-contained true \
  -p:PublishSingleFile=true \
  -p:PublishTrimmed=false \
  --output "$host_output"

cp "$host_output/XDM.NativeHost" "$output/XDM.NativeHost"
chmod +x "$output/XDM.NativeHost"
test -x "$output/XDM"
test -x "$output/XDM.NativeHost"
"$output/XDM" --validate-bootstrap
"$output/XDM.NativeHost" </dev/null
rm -rf "$host_output"
printf 'Package smoke test passed: %s\n' "$output"
