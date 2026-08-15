#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# Final publication must first prove the current Overlay-13 contracts. This gate is
# intentionally static/fail-closed and does not self-certify runtime evidence.
bash tools/run-final-release-gate.sh --ci
python3 tools/validate-bug-hunt-phase10-release-upgrade-packaging.py
python3 tools/verify-phase10-backup-policy.py
: "${XDM_ARIA2_ARCHIVE_SHA256:?XDM_ARIA2_ARCHIVE_SHA256 pins the trusted aria2 official archive digest}"
python3 tools/install-aria2-runtime.py --download-official --expected-archive-sha256 "$XDM_ARIA2_ARCHIVE_SHA256" --require-trusted-digest
python3 tools/verify-aria2-runtime.py --require-payload --require-16kb-alignment --require-trusted-archive-digest --expected-archive-sha256 "$XDM_ARIA2_ARCHIVE_SHA256"
# Run the complete Overlay-13 common matrix before release artifacts are assembled.
bash tools/run-final-common-validation.sh

# Only after the common matrix is green do we compile/package the signed release. Static/full
# evidence is therefore earned by an earlier invocation rather than asserted optimistically.
./gradlew -Pxdm.requireAria2Runtime=true \
  -Pxdm.validation.staticPassed=true -Pxdm.validation.fullPassed=true -Pxdm.validation.aria2PayloadVerified=true \
  :browser-extension:validateFirefoxExtension :browser-extension:packageFirefoxExtensionDark \
  :browser-extension:packageFirefoxExtensionAmoled :browser-extension:verifyFirefoxExtensionReleaseArtifacts \
  lintRelease testReleaseUnitTest :app:assembleRelease :app:bundleRelease
APK="$(find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' -print -quit)"
AAB="$(find app/build/outputs/bundle/release -maxdepth 1 -type f -name '*.aab' -print -quit)"
test -n "$APK"
test -n "$AAB"
python3 tools/verify-aria2-runtime.py --require-payload --require-16kb-alignment --require-trusted-archive-digest --expected-archive-sha256 "$XDM_ARIA2_ARCHIVE_SHA256" --apk "$APK"
APKS="build/outputs/apks/release/xdm-release.apks"
mkdir -p "$(dirname "$APKS")"
if [[ -n "${BUNDLETOOL_JAR:-}" ]]; then
  java -jar "$BUNDLETOOL_JAR" build-apks     --bundle "$AAB"     --output "$APKS"     --ks "$XDM_RELEASE_STORE_FILE"     --ks-pass "pass:$XDM_RELEASE_STORE_PASSWORD"     --ks-key-alias "$XDM_RELEASE_KEY_ALIAS"     --key-pass "pass:$XDM_RELEASE_KEY_PASSWORD"
elif command -v bundletool >/dev/null 2>&1; then
  bundletool build-apks     --bundle "$AAB"     --output "$APKS"     --ks "$XDM_RELEASE_STORE_FILE"     --ks-pass "pass:$XDM_RELEASE_STORE_PASSWORD"     --ks-key-alias "$XDM_RELEASE_KEY_ALIAS"     --key-pass "pass:$XDM_RELEASE_KEY_PASSWORD"
else
  echo "bundletool or BUNDLETOOL_JAR is required; debug-key fallback is forbidden" >&2
  exit 1
fi
python3 tools/verify-phase10-release-artifacts.py --require-16kb --bundletool-jar "${BUNDLETOOL_JAR:-}" --inventory tools/phase10-release-inventory.json --apk "$APK" --aab "$AAB" --apks "$APKS"
bash tools/generate-phase10-publication-bundle.sh "$APK" "$AAB" "$APKS"
if [[ -n "${XDM_PHASE10_PREVIOUS_RELEASE_APK:-}" && -n "${XDM_PHASE10_RUN_DEVICE_MATRIX:-}" ]]; then
  bash tools/run-phase10-install-upgrade-matrix.sh "$XDM_PHASE10_PREVIOUS_RELEASE_APK" "$APK"
else
  echo "Phase 10 device upgrade/D2D matrix not run here; set XDM_PHASE10_PREVIOUS_RELEASE_APK and XDM_PHASE10_RUN_DEVICE_MATRIX=1 on a device runner."
fi
echo "Phase 10 signed release gate passed"
