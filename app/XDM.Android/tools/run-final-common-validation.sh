#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Post-DL03 non-device final matrix. Keep this synchronized with the xdm_android
# Devtool target and the canonical final release gate.
# The hosted matrix exercises lint and native build tooling in a single JVM.
# Allow it enough metaspace without changing the memory-constrained Termux defaults.
# Opt out of configuration cache only for this large hosted integration matrix;
# validators and tests remain enabled, and other builds retain the project default.
ci_gradle_args=()
if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
  ci_gradle_args+=(
    "--no-configuration-cache"
    "-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=1536m -XX:ReservedCodeCacheSize=256m -XX:+UseCodeCacheFlushing -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=2"
  )
fi
./gradlew -Pxdm.requireAria2Runtime=true \
  "${ci_gradle_args[@]}" \
  :app:finalRemediationStaticGate \
  :app:compileDebugKotlin \
  :core-model:test :core-utils:test :transfer-api:test \
  :browser-integration:testDebugUnitTest :storage:testDebugUnitTest :transfer-native:testDebugUnitTest \
  :transfer-aria2:test :scheduler:testDebugUnitTest :media:test :persistence:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug \
  :browser-extension:test :browser-extension:jsTest :browser-extension:validateFirefoxExtension :app:checkBrowserIntegration \
  assembleDebug :app:assembleDebugAndroidTest
