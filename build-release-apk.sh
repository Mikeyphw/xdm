#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$ROOT/app/XDM.Android"
SIGNING_ENV_FILE="${XDM_RELEASE_SIGNING_ENV:-$ANDROID_ROOT/release-signing.env}"
OUT_DIR="${XDM_RELEASE_OUT_DIR:-$ROOT/dist/android}"
BUILD_TYPE="${1:-all}"

usage() {
  cat >&2 <<'EOF'
Usage: ./build-release-apk.sh [debug|release|all]

Builds Android APKs. With no argument, builds debug and release.

Release builds require signing inputs exported in the environment or placed in:
  app/XDM.Android/release-signing.env

Required variables:
  XDM_RELEASE_STORE_FILE       Path to the release keystore
  XDM_RELEASE_STORE_PASSWORD   Keystore password
  XDM_RELEASE_KEY_ALIAS        Signing key alias
  XDM_RELEASE_KEY_PASSWORD     Signing key password

Optional variables:
  XDM_RELEASE_OUT_DIR          Output directory, default: dist/android
  XDM_ANDROID_BUILD_APK        Override tools/devtool-gradle.sh builder
EOF
}

if [[ "$BUILD_TYPE" == "-h" || "$BUILD_TYPE" == "--help" ]]; then
  usage
  exit 0
fi

version_name() {
  sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' "$ANDROID_ROOT/app/build.gradle.kts" | head -n 1
}

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
  name="$(version_name)"
  dest="$OUT_DIR/xdm-android-${name:-debug}-debug.apk"
  cp "$apk" "$dest"
  echo "Debug APK: $dest"
}

require_release_signing() {
  if [[ -f "$SIGNING_ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$SIGNING_ENV_FILE"
    set +a
  fi

  missing=()
  for name in \
    XDM_RELEASE_STORE_FILE \
    XDM_RELEASE_STORE_PASSWORD \
    XDM_RELEASE_KEY_ALIAS \
    XDM_RELEASE_KEY_PASSWORD; do
    if [[ -z "${!name:-}" ]]; then
      missing+=("$name")
    fi
  done

  if (( ${#missing[@]} > 0 )); then
    echo "Missing release signing input(s): ${missing[*]}" >&2
    usage
    exit 2
  fi

  if [[ ! -f "$XDM_RELEASE_STORE_FILE" ]]; then
    echo "Release keystore not found: $XDM_RELEASE_STORE_FILE" >&2
    exit 2
  fi
}

build_release() {
  local apk dest name
  require_release_signing
  mkdir -p "$OUT_DIR"

  "$ANDROID_ROOT/tools/devtool-gradle.sh" clean lintRelease testReleaseUnitTest assembleRelease

  APK_DIR="$ANDROID_ROOT/app/build/outputs/apk/release"
  mapfile -t release_apks < <(find "$APK_DIR" -maxdepth 1 -type f -name '*release*.apk' ! -name '*unsigned*' | sort)
  if (( ${#release_apks[@]} == 0 )); then
    echo "No signed release APK found in $APK_DIR" >&2
    exit 1
  fi

  apk="${release_apks[-1]}"
  name="$(version_name)"
  dest="$OUT_DIR/xdm-android-${name:-release}-release.apk"

  cp "$apk" "$dest"

  if command -v apksigner >/dev/null 2>&1; then
    apksigner verify --verbose --print-certs "$dest"
  else
    echo "apksigner not found; skipped signature verification." >&2
  fi

  echo "Release APK: $dest"
}

case "$BUILD_TYPE" in
  debug)
    build_debug
    ;;
  release)
    build_release
    ;;
  all)
    build_debug
    build_release
    ;;
  *)
    echo "Unsupported build type: $BUILD_TYPE" >&2
    usage
    exit 2
    ;;
esac
