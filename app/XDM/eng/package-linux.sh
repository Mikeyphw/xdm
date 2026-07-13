#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
version="${1:-$(tr -d '[:space:]' < "$repo_root/VERSION")}"
rm -rf "$repo_root/artifacts/packages"
mkdir -p "$repo_root/artifacts/packages"
for rid in linux-x64 linux-arm64; do
  "$repo_root/app/XDM/eng/publish-one.sh" "$rid" "$version"
done
