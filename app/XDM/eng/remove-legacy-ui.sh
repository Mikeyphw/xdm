#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
legacy_paths=(
  "$repo_root/app/XDM/XDM.Wpf.UI"
  "$repo_root/app/XDM/XDM.Gtk.UI"
  "$repo_root/app/XDM/XDM.WinForms.IntegrationUI"
  "$repo_root/app/XDM/MsixPackaging"
  "$repo_root/app/XDM/XDM.Msix.AutoLaunch"
)
for path in "${legacy_paths[@]}"; do
  if [[ -e "$path" ]]; then
    rm -rf -- "$path"
    printf 'Removed %s\n' "$path"
  fi
done
printf 'Legacy UI cleanup complete. XDM.Modern.sln is the supported solution.\n'
