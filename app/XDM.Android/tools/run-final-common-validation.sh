#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Overlay 13 non-device final matrix. Keep this synchronized with the xdm_android
# Devtool target and the final overlay artifact validation tasks.
./gradlew -Pxdm.requireAria2Runtime=true \
  :app:finalRemediationStaticGate \
  :app:compileDebugKotlin \
  :core-model:test :core-utils:test :transfer-api:test \
  :browser-integration:testDebugUnitTest :storage:testDebugUnitTest :transfer-native:testDebugUnitTest \
  :transfer-aria2:test :scheduler:testDebugUnitTest :media:test :persistence:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug \
  :browser-extension:test :browser-extension:jsTest :browser-extension:validateFirefoxExtension :app:checkBrowserIntegration \
  assembleDebug :app:assembleDebugAndroidTest
