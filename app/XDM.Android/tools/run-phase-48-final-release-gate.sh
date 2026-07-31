#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-$(pwd)}"
cd "$ROOT"

./gradlew   assembleDebug   :app:assembleDebugAndroidTest   :browser-extension:packageFirefoxExtensionDark   :browser-extension:packageFirefoxExtensionAmoled   :browser-extension:verifyFirefoxExtensionReleaseArtifacts   :browser-extension:test   :browser-extension:jsTest   :browser-extension:validateFirefoxExtension   :app:checkBrowserIntegration   :core-model:test   :core-utils:test   :transfer-api:test   :browser-integration:testDebugUnitTest   :storage:testDebugUnitTest   :transfer-native:testDebugUnitTest   :transfer-aria2:test   :scheduler:testDebugUnitTest   :media:test   :persistence:testDebugUnitTest   :app:testDebugUnitTest

python3 tools/validate-phase-47-real-shared-media-sniffing-engine.py
python3 tools/validate-phase-48-final-ux-release-gate.py
python3 tools/validate-debug-workbench-d7-final-debug-seal.py
python3 tools/validate-phase49-field-bugfix.py
python3 tools/validate-phase50-operational-repair.py
python3 tools/validate-phase51-recovery-storage-doctor.py
python3 tools/validate-phase52-browser-session-health.py
python3 tools/validate-phase53-extension-detection-quality-gate.py
python3 tools/validate-phase54-engine-escalation-planner.py
python3 tools/validate-phase55-final-release-warning-explainer.py
python3 tools/validate-phase56-stale-copy-architecture-noise-sweep.py
python3 tools/validate-phase57-runtime-failure-recovery-ux.py
python3 tools/validate-phase58-runtime-recovery-execution-guard.py
python3 tools/validate-phase59-runtime-recovery-action-transparency.py
python3 tools/validate-phase60-runtime-recovery-flow-seal.py
python3 tools/validate-phase61-final-gate-validator-harmony.py
python3 tools/validate-phase62-real-device-operational-smoke-seal.py
python3 tools/validate-phase63-release-readiness-support-bundle-seal.py

python3 tools/validate-phase64-final-android-downloader-rc-seal.py
python3 tools/validate-phase65-diagnostic-export-download-action-fix.py
