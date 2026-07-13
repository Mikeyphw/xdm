#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
rid="${1:?runtime identifier required}"
version="${2:-$(tr -d '[:space:]' < "$repo_root/VERSION")}"
out_root="${3:-$repo_root/artifacts/publish}"
output="$out_root/$rid"
host_output="$out_root/native-host-$rid"
updater_output="$out_root/updater-$rid"
rm -rf "$output" "$host_output" "$updater_output"
dotnet publish "$repo_root/app/XDM/src/XDM.App/XDM.App.csproj" -c Release -r "$rid" --self-contained true \
  -p:Version="$version" -p:ContinuousIntegrationBuild=true -p:PublishSingleFile=false -p:PublishTrimmed=false -o "$output"
dotnet publish "$repo_root/app/XDM/src/XDM.NativeHost/XDM.NativeHost.csproj" -c Release -r "$rid" --self-contained true \
  -p:Version="$version" -p:ContinuousIntegrationBuild=true -p:PublishSingleFile=true -p:PublishTrimmed=false -o "$host_output"
dotnet publish "$repo_root/app/XDM/src/XDM.Updater/XDM.Updater.csproj" -c Release -r "$rid" --self-contained true \
  -p:Version="$version" -p:ContinuousIntegrationBuild=true -p:PublishSingleFile=true -p:PublishTrimmed=false -o "$updater_output"
cp "$host_output/XDM.NativeHost" "$output/XDM.NativeHost"
cp "$updater_output/XDM.Updater" "$output/XDM.Updater"
chmod +x "$output/XDM" "$output/XDM.NativeHost" "$output/XDM.Updater"
python3 "$repo_root/app/XDM/eng/package-portable.py" --source "$output" --output "$repo_root/artifacts/packages" \
  --name "xdm-modern-$version-$rid" --tar-gz

if [[ "$rid" == linux-* ]] && command -v dpkg-deb >/dev/null 2>&1; then
  case "$rid" in
    linux-x64) deb_arch=amd64 ;;
    linux-arm64) deb_arch=arm64 ;;
    *) deb_arch=all ;;
  esac
  stage="$repo_root/artifacts/deb-$rid"
  rm -rf "$stage"
  mkdir -p "$stage/DEBIAN" "$stage/opt/xdm" "$stage/usr/bin" "$stage/usr/share/applications" "$stage/usr/share/icons/hicolor/512x512/apps"
  cp -a "$output/." "$stage/opt/xdm/"
  ln -s /opt/xdm/XDM "$stage/usr/bin/xdm-modern"
  cp "$repo_root/app/XDM/packaging/linux/xdm-modern.desktop" "$stage/usr/share/applications/xdm-modern.desktop"
  cp "$repo_root/app/XDM/xdm-logo.png" "$stage/usr/share/icons/hicolor/512x512/apps/xdm-modern.png"
  cat > "$stage/DEBIAN/control" <<CONTROL
Package: xdm-modern
Version: $version
Section: net
Priority: optional
Architecture: $deb_arch
Maintainer: XDM Modern contributors
Description: Avalonia-based Xtreme Download Manager
CONTROL
  dpkg-deb --root-owner-group --build "$stage" "$repo_root/artifacts/packages/xdm-modern_${version}_${deb_arch}.deb"
fi
