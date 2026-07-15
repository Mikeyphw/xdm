#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILDER="${XDM_ANDROID_BUILD_APK:-$HOME/.local/bin/build-apk}"
if [[ ! -x "$BUILDER" ]]; then
  BUILDER="$(command -v build-apk || true)"
fi
if [[ -z "$BUILDER" || ! -x "$BUILDER" ]]; then
  echo "build-apk was not found. Install it at ~/.local/bin/build-apk or set XDM_ANDROID_BUILD_APK." >&2
  exit 127
fi
exec "$BUILDER" "$ROOT" --gradle-version 9.4.1 --task wrapper --no-daemon --fail-fast --no-artifact-scan
