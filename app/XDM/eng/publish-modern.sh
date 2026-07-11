#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
project="$repo_root/app/XDM/src/XDM.App/XDM.App.csproj"
out_root="${1:-$repo_root/artifacts/publish}"
for rid in linux-x64 linux-arm64; do
  dotnet publish "$project" -c Release -r "$rid" --self-contained true \
    -p:PublishSingleFile=false -p:PublishTrimmed=false \
    -o "$out_root/$rid"
done
printf 'Published XDM to %s\n' "$out_root"
