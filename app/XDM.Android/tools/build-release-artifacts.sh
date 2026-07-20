#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
variant="${1:-beta}"
case "$variant" in
  beta) task="assembleBeta"; output_dir="app/build/outputs/apk/beta" ;;
  release) task="assembleRelease"; output_dir="app/build/outputs/apk/release" ;;
  *) echo "usage: $0 [beta|release]" >&2; exit 64 ;;
esac
./gradlew "$task" --no-daemon --build-cache
mapfile -t apks < <(find "$output_dir" -maxdepth 1 -type f -name "*.apk" | sort)
if [[ "${#apks[@]}" -eq 0 ]]; then echo "No APK produced in $output_dir" >&2; exit 1; fi
for apk in "${apks[@]}"; do sha256sum "$apk" > "$apk.sha256"; echo "APK: $apk"; cat "$apk.sha256"; done
