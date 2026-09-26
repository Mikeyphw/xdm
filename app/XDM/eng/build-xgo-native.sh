#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
rid="${1:?runtime identifier required}"
out="${2:?output directory required}"
mkdir -p "$out"
case "$rid" in
  linux-x64) goos=linux; goarch=amd64; library=libxdmcore.so ;;
  linux-arm64) goos=linux; goarch=arm64; library=libxdmcore.so ;;
  *) printf 'Unsupported Unix xdmcore runtime: %s\n' "$rid" >&2; exit 2 ;;
esac
if [[ -n "${XDM_XGO_NATIVE_DIR:-}" && -f "$XDM_XGO_NATIVE_DIR/$rid/$library" ]]; then
  cp "$XDM_XGO_NATIVE_DIR/$rid/$library" "$out/$library"
  exit 0
fi
host_arch="$(go env GOARCH)"
if [[ "$(go env GOOS)" != "$goos" || "$host_arch" != "$goarch" ]]; then
  printf 'xdmcore %s must be built on a matching host or supplied via XDM_XGO_NATIVE_DIR. host=%s-%s\n' "$rid" "$(go env GOOS)" "$host_arch" >&2
  exit 3
fi
(
  cd "$repo_root"
  CGO_ENABLED=1 GOOS="$goos" GOARCH="$goarch" go build -trimpath -buildmode=c-shared -o "$out/$library" ./engine/bridge/cabi
)
test -s "$out/$library"
