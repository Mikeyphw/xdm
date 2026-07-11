#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
mode="${1:---check}"
legacy_paths=(
  "app/XDM/XDM.Wpf.UI"
  "app/XDM/XDM.Gtk.UI"
  "app/XDM/XDM.WinForms.IntegrationUI"
  "app/XDM/MsixPackaging"
  "app/XDM/XDM.Msix.AutoLaunch"
  "app/XDM/XDM.App.Host"
  "app/XDM/XDM.Core"
  "app/XDM/XDM.Messaging"
  "app/XDM/XDM.Compatibility"
  "app/XDM/XDM.Tests"
  "app/XDM/XDM_Tests"
  "app/XDM/MockServer"
  "app/XDM/XDM_CoreFx.sln"
)

case "$mode" in
  --check)
    found=0
    for relative in "${legacy_paths[@]}"; do
      if [[ -e "$repo_root/$relative" ]]; then
        printf 'Legacy path present: %s\n' "$relative"
        found=1
      fi
    done
    if [[ "$found" -eq 0 ]]; then
      echo 'No known legacy application paths remain.'
    fi
    ;;
  --apply)
    if [[ -n "$(git -C "$repo_root" status --porcelain)" ]]; then
      echo 'Refusing cleanup because the working tree is not clean.' >&2
      exit 2
    fi
    for relative in "${legacy_paths[@]}"; do
      if [[ -e "$repo_root/$relative" ]]; then
        rm -rf -- "$repo_root/$relative"
        printf 'Removed %s\n' "$relative"
      fi
    done
    echo 'Legacy cleanup complete. Validate and commit the deletion separately.'
    ;;
  *)
    echo 'Usage: remove-legacy-ui.sh [--check|--apply]' >&2
    exit 2
    ;;
esac
