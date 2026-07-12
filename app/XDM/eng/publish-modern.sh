#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
app_project="$repo_root/app/XDM/src/XDM.App/XDM.App.csproj"
host_project="$repo_root/app/XDM/src/XDM.NativeHost/XDM.NativeHost.csproj"
out_root="${1:-$repo_root/artifacts/publish}"
for rid in linux-x64 linux-arm64; do
  output="$out_root/$rid"
  dotnet publish "$app_project" -c Release -r "$rid" --self-contained true \
    -p:PublishSingleFile=false -p:PublishTrimmed=false -o "$output"
  host_output="$out_root/native-host-$rid"
  dotnet publish "$host_project" -c Release -r "$rid" --self-contained true \
    -p:PublishSingleFile=true -p:PublishTrimmed=false -o "$host_output"
  cp "$host_output/XDM.NativeHost" "$output/XDM.NativeHost"
  chmod +x "$output/XDM.NativeHost"
done
printf 'Published XDM and native host to %s\n' "$out_root"
