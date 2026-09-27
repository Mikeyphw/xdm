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
provenance=xdmcore-provenance.json
if [[ -n "${XDM_XGO_NATIVE_DIR:-}" && -f "$XDM_XGO_NATIVE_DIR/$rid/$library" ]]; then
  if [[ ! -f "$XDM_XGO_NATIVE_DIR/$rid/$provenance" ]]; then
    printf 'Prebuilt xdmcore %s is missing %s provenance.\n' "$rid" "$provenance" >&2
    exit 4
  fi
  cp "$XDM_XGO_NATIVE_DIR/$rid/$library" "$out/$library"
  cp "$XDM_XGO_NATIVE_DIR/$rid/$provenance" "$out/$provenance"
  (
    cd "$repo_root"
    go run ./engine/cmd/xgo-provenance \
      --validate "$out/$provenance" \
      --goos "$goos" \
      --goarch "$goarch" \
      --build-mode c-shared
  )
  exit 0
fi
host_arch="$(go env GOARCH)"
if [[ "$(go env GOOS)" != "$goos" || "$host_arch" != "$goarch" ]]; then
  printf 'xdmcore %s must be built on a matching host or supplied via XDM_XGO_NATIVE_DIR. host=%s-%s\n' "$rid" "$(go env GOOS)" "$host_arch" >&2
  exit 3
fi
revision="$(git -C "$repo_root" rev-parse HEAD)"
go_version="$(go env GOVERSION)"
modified=false
if [[ -n "$(git -C "$repo_root" status --porcelain --untracked-files=no)" ]]; then
  modified=true
fi
ldflags="-X github.com/subhra74/xdm/engine/api/v1.BuildRevision=$revision -X github.com/subhra74/xdm/engine/api/v1.BuildModified=$modified -X github.com/subhra74/xdm/engine/api/v1.BuildMode=c-shared"
(
  cd "$repo_root"
  CGO_ENABLED=1 GOOS="$goos" GOARCH="$goarch" go build \
    -trimpath \
    -buildmode=c-shared \
    -ldflags "$ldflags" \
    -o "$out/$library" \
    ./engine/bridge/cabi
  go run ./engine/cmd/xgo-provenance \
    --revision "$revision" \
    --modified="$modified" \
    --go-version "$go_version" \
    --goos "$goos" \
    --goarch "$goarch" \
    --build-mode c-shared \
    --output "$out/$provenance"
)
test -s "$out/$library"
test -s "$out/$provenance"
