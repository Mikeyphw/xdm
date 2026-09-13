#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$ROOT/app/XDM.Android"
SIGNING_ENV_FILE="${XDM_RELEASE_SIGNING_ENV:-$ANDROID_ROOT/release-signing.env}"
OUT_DIR="${XDM_RELEASE_OUT_DIR:-$ROOT/dist/android}"
BUILD_TYPE="${1:-all}"

usage() {
  cat >&2 <<'USAGE'
Usage: ./build-release-apk.sh [debug|release|all]

Builds Android artifacts. With no argument, builds debug and the canonical signed release.

Release builds require signing inputs exported in the environment or placed in:
  app/XDM.Android/release-signing.env

Required release variables:
  XDM_RELEASE_STORE_FILE       Path to the release keystore
  XDM_RELEASE_STORE_PASSWORD   Keystore password
  XDM_RELEASE_KEY_ALIAS        Signing key alias
  XDM_RELEASE_KEY_PASSWORD     Signing key password
  XDM_RELEASE_SIGNER_SHA256    SHA-256 fingerprint of the configured signing certificate
  XDM_ARIA2_ARCHIVE_SHA256     SHA-256 of the pinned upstream aria2 release payload

Optional variables:
  XDM_RELEASE_OUT_DIR          Output directory, default: dist/android
  BUNDLETOOL_JAR               Override the self-provisioned pinned bundletool jar

The release path executes the same strict signed-release gate used by CI and copies the
already-verified publication directory, including checksums and release metadata.
USAGE
}

if [[ "$BUILD_TYPE" == "-h" || "$BUILD_TYPE" == "--help" ]]; then
  usage
  exit 0
fi

build_debug() {
  local apk dest name
  mkdir -p "$OUT_DIR"
  "$ANDROID_ROOT/tools/devtool-gradle.sh" clean lintDebug testDebugUnitTest assembleDebug
  mapfile -t debug_apks < <(find "$ANDROID_ROOT/app/build/outputs/apk/debug" -maxdepth 1 -type f -name '*.apk' | sort)
  if (( ${#debug_apks[@]} == 0 )); then
    echo "No debug APK found in $ANDROID_ROOT/app/build/outputs/apk/debug" >&2
    exit 1
  fi
  apk="${debug_apks[-1]}"
  name="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' "$ANDROID_ROOT/app/build.gradle.kts" | head -n 1)"
  dest="$OUT_DIR/xdm-android-${name:-debug}-debug.apk"
  cp "$apk" "$dest"
  sha256sum "$dest" > "$dest.sha256"
  echo "Debug APK: $dest"
}

require_release_inputs() {
  if [[ -f "$SIGNING_ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$SIGNING_ENV_FILE"
    set +a
  fi
  local missing=() name
  for name in \
    XDM_RELEASE_STORE_FILE \
    XDM_RELEASE_STORE_PASSWORD \
    XDM_RELEASE_KEY_ALIAS \
    XDM_RELEASE_KEY_PASSWORD \
    XDM_RELEASE_SIGNER_SHA256 \
    XDM_ARIA2_ARCHIVE_SHA256; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  if (( ${#missing[@]} > 0 )); then
    echo "Missing release input(s): ${missing[*]}" >&2
    usage
    exit 2
  fi
  [[ -f "$XDM_RELEASE_STORE_FILE" ]] || { echo "Release keystore not found: $XDM_RELEASE_STORE_FILE" >&2; exit 2; }
  [[ "$XDM_RELEASE_SIGNER_SHA256" =~ ^[0-9A-Fa-f]{64}$ ]] || { echo "XDM_RELEASE_SIGNER_SHA256 must be 64 hex characters" >&2; exit 2; }
  [[ "$XDM_ARIA2_ARCHIVE_SHA256" =~ ^[0-9A-Fa-f]{64}$ ]] || { echo "XDM_ARIA2_ARCHIVE_SHA256 must be 64 hex characters" >&2; exit 2; }
  export XDM_RELEASE_STORE_FILE XDM_RELEASE_STORE_PASSWORD XDM_RELEASE_KEY_ALIAS XDM_RELEASE_KEY_PASSWORD
  export XDM_RELEASE_SIGNER_SHA256 XDM_ARIA2_ARCHIVE_SHA256
}

build_release() {
  require_release_inputs
  mkdir -p "$OUT_DIR"
  cd "$ANDROID_ROOT"
  if [[ -z "${BUNDLETOOL_JAR:-}" ]]; then
    BUNDLETOOL_JAR="$(python3 tools/ensure-bundletool.py --print-path)"
    export BUNDLETOOL_JAR
  fi
  bash tools/run-bug-hunt-phase10-release-gate.sh
  local publication="$ANDROID_ROOT/build/release/publication"
  [[ -f "$publication/SHA256SUMS" ]] || { echo "Canonical release gate produced no publication checksums" >&2; exit 1; }
  rm -rf "$OUT_DIR/release"
  mkdir -p "$OUT_DIR/release"
  cp -a "$publication/." "$OUT_DIR/release/"
  (cd "$OUT_DIR/release" && sha256sum -c SHA256SUMS)
  echo "Verified signed release publication: $OUT_DIR/release"
}

case "$BUILD_TYPE" in
  debug) build_debug ;;
  release) build_release ;;
  all) build_debug; build_release ;;
  *) echo "Unsupported build type: $BUILD_TYPE" >&2; usage; exit 2 ;;
esac
