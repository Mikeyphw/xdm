#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-$(pwd)}"
cd "$ROOT"

./gradlew   assembleDebug   :app:assembleDebugAndroidTest   :browser-extension:packageFirefoxExtensionDark   :browser-extension:packageFirefoxExtensionAmoled   :browser-extension:verifyFirefoxExtensionReleaseArtifacts   :browser-extension:test   :browser-extension:jsTest   :browser-extension:validateFirefoxExtension   :app:checkBrowserIntegration   :core-model:test   :core-utils:test   :transfer-api:test   :browser-integration:testDebugUnitTest   :storage:testDebugUnitTest   :transfer-native:testDebugUnitTest   :transfer-aria2:test   :scheduler:testDebugUnitTest   :media:test   :persistence:testDebugUnitTest   :app:testDebugUnitTest

python3 tools/validate-phase-48-final-ux-release-gate.py
