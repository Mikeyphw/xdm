#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
version="${1:-$(tr -d '[:space:]' < "$repo_root/VERSION")}"
packages="$repo_root/artifacts/packages"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

"$repo_root/app/XDM/eng/validate-modern.sh"
"$repo_root/app/XDM/eng/benchmark-modern.sh"
"$repo_root/app/XDM/eng/package-linux.sh" "$version"

archive="$packages/xdm-modern-$version-linux-x64.tar.gz"
test -s "$archive"
tar -xzf "$archive" -C "$temp_dir"
test -x "$temp_dir/XDM"
test -x "$temp_dir/XDM.NativeHost"
"$temp_dir/XDM" --validate-bootstrap
"$temp_dir/XDM.NativeHost" </dev/null

if find "$temp_dir" -maxdepth 1 -type f \( -iname '*wpf*' -o -iname '*gtk*' -o -iname '*winforms*' -o -iname '*msix*' \) | grep -q .; then
  echo 'Legacy UI artifacts were found in the modern package.' >&2
  exit 1
fi

sha256sum "$packages"/* > "$packages/SHA256SUMS"
printf 'Qualified XDM %s packages in %s\n' "$version" "$packages"
