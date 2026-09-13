#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="${1:-full}"
case "$MODE" in
  full|--full) MODE="full" ;;
  --static) MODE="static" ;;
  --verify-artifacts) MODE="verify-artifacts" ;;
  *) echo "Usage: $0 [--full|--static|--verify-artifacts]" >&2; exit 2 ;;
esac

find_node() {
  if command -v node >/dev/null 2>&1; then printf '%s\n' node
  elif command -v nodejs >/dev/null 2>&1; then printf '%s\n' nodejs
  else echo "Node.js is required for the Firefox extension release gate." >&2; exit 1
  fi
}

extension_version() {
  sed -n 's/^[[:space:]]*val extensionVersion = "\([^"]*\)"/\1/p' browser-extension/build.gradle.kts | head -n 1
}

app_version() {
  sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' app/build.gradle.kts | head -n 1
}

verify_artifacts() {
  command -v unzip >/dev/null
  command -v sha256sum >/dev/null
  local ext_version app_ver dark_xpi amoled_xpi
  ext_version="$(extension_version)"
  app_ver="$(app_version)"
  test -n "$ext_version" && test -n "$app_ver"
  dark_xpi="browser-extension/build/outputs/xpi/XDM-Android-Firefox-${ext_version}-release-dark.xpi"
  amoled_xpi="browser-extension/build/outputs/xpi/XDM-Android-Firefox-${ext_version}-release-amoled.xpi"
  python3 browser-extension/tools/verify_release_artifacts.py \
    --output-dir browser-extension/build/outputs/xpi \
    --metadata browser-extension/build/outputs/xpi/release-artifacts.json \
    --extension-version "$ext_version" --app-version "$app_ver"
  unzip -t "$dark_xpi"
  unzip -t "$amoled_xpi"
  sha256sum "$dark_xpi" "$amoled_xpi"
}

if [[ "$MODE" == "verify-artifacts" ]]; then
  verify_artifacts
  exit 0
fi

validators=(
  tools/validate-phase-37-browser-scheme-contract.py
  tools/validate-phase-38-repo-owned-extension.py
  tools/validate-phase-39-xpi-export.py
  tools/validate-phase-40-theme-fab.py
  tools/validate-phase-41-browser-bridge-integration.py
  tools/validate-phase-42-browser-bridge-release-gate.py
  tools/validate-phase-42-kotlin-compile-recovery.py
  tools/validate-retired-prerelease-channel.py
  tools/validate-phase-42-kotlin-source-compatibility.py
  tools/validate-phase-42-contract-test-modernization.py
)
for validator in "${validators[@]}"; do
  python3 "$validator"
done

NODE="$(find_node)"
for source in browser-extension/src/main/extension/xdm-firefox/*.js browser-extension/tests/*.js; do
  "$NODE" --check "$source"
done
for test in \
  browser-extension/tests/test_detector.js \
  browser-extension/tests/test_handoff.js \
  browser-extension/tests/test_fab.js \
  browser-extension/tests/test_background.js \
  browser-extension/tests/test_release_gate.js; do
  "$NODE" "$test"
done

bash -n tools/run-browser-bridge-device-acceptance.sh
bash tools/run-browser-bridge-device-acceptance.sh --print >/dev/null

if [[ "$MODE" == "static" ]]; then
  echo "Browser bridge static release gate passed."
  exit 0
fi

./gradlew -Pxdm.cleanKotlinValidation=true \
  -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  --no-daemon --max-workers=1 --no-parallel --no-build-cache --no-configuration-cache --stacktrace \
  help \
  :browser-extension:test \
  :browser-extension:jsTest \
  :browser-extension:validateFirefoxExtension \
  :browser-extension:packageFirefoxExtensionDark \
  :browser-extension:packageFirefoxExtensionAmoled \
  :browser-extension:verifyFirefoxExtensionReleaseArtifacts \
  :app:checkBrowserIntegration \
  :core-model:test \
  :core-utils:test \
  :transfer-api:test \
  :browser-integration:testDebugUnitTest \
  :storage:testDebugUnitTest \
  :transfer-native:testDebugUnitTest \
  :transfer-aria2:test \
  :scheduler:testDebugUnitTest \
  :media:test \
  :persistence:testDebugUnitTest \
  :app:testDebugUnitTest \
  lintDebug \
  :app:assembleDebugAndroidTest \
  assembleDebug

verify_artifacts
printf '\nBrowser bridge full release gate passed. Device acceptance remains a separate physical-device sign-off.\n'
