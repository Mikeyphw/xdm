#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
version="${1:-$(tr -d '[:space:]' < "$repo_root/VERSION")}"
artifacts="$repo_root/artifacts"
"$repo_root/app/XDM/eng/publish-modern.sh" "$artifacts/publish"
mkdir -p "$artifacts/packages"
for rid in linux-x64 linux-arm64; do
  tar -C "$artifacts/publish/$rid" -czf "$artifacts/packages/xdm-modern-${version}-${rid}.tar.gz" .
done

if command -v dpkg-deb >/dev/null 2>&1; then
  stage="$artifacts/deb-stage"
  rm -rf "$stage"
  mkdir -p "$stage/DEBIAN" "$stage/opt/xdm" "$stage/usr/bin" "$stage/usr/share/applications" "$stage/usr/share/icons/hicolor/512x512/apps" "$stage/usr/share/icons/hicolor/scalable/apps"
  cp -a "$artifacts/publish/linux-x64/." "$stage/opt/xdm/"
  ln -s /opt/xdm/XDM "$stage/usr/bin/xdm-modern"
  cp "$repo_root/app/XDM/packaging/linux/xdm-modern.desktop" "$stage/usr/share/applications/xdm-modern.desktop"
  cp "$repo_root/app/XDM/xdm-logo.png" "$stage/usr/share/icons/hicolor/512x512/apps/xdm-modern.png"
  cp "$repo_root/app/XDM/xdm-logo.svg" "$stage/usr/share/icons/hicolor/scalable/apps/xdm-modern.svg"
  cat > "$stage/DEBIAN/control" <<CONTROL
Package: xdm-modern
Version: $version
Section: net
Priority: optional
Architecture: amd64
Maintainer: XDM Modern contributors
Description: Avalonia-based Xtreme Download Manager preview
CONTROL
  dpkg-deb --build "$stage" "$artifacts/packages/xdm-modern_${version}_amd64.deb"
fi
