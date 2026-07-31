#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

validators=(
  tools/validate-foundation.py
  tools/validate-uix-r1-surface-boundary.py
  tools/validate-uix-r2-flat-dark-shell.py
  tools/validate-uix-r3-downloads-add-workspace.py
  tools/validate-uix-r4-media-library-consumer-workflows.py
  tools/validate-uix-r5-activity-settings-developer-boundary.py
  tools/validate-uix-r6-accessibility-performance-release-seal.py
  tools/validate-phase-2-3.py
  tools/validate-phase-4.py
  tools/validate-phase-5.py
  tools/validate-ownership-hardening.py
  tools/validate-phase-6b.py
  tools/validate-phase-6.py
  tools/validate-phase-7.py
  tools/validate-phase-8.py
  tools/validate-phase-9.py
  tools/validate-phase-10.py
  tools/validate-phase-11.py
  tools/validate-phase-12.py
  tools/validate-phase-13.py
  tools/validate-phase-14.py
  tools/validate-phase-15.py
  tools/validate-phase-16.py
  tools/validate-phase-17.py
  tools/validate-post17-desktop-parity.py
  tools/validate-termux-bridge.py
  tools/validate-termux-media-pipeline.py
  tools/validate-media-resolver-player.py
  tools/validate-media-execution-library.py
  tools/validate-media-download-engine-hardening.py
  tools/validate-media-dispatch-control-tower.py
  tools/validate-media-queue-telemetry.py
  tools/validate-media-queue-actions.py
  tools/validate-media-worker-bridge.py
  tools/validate-media-termux-runtime-adapter.py
  tools/validate-media-native-direct-download-engine.py
  tools/validate-media-offline-library-v2.py
  tools/validate-media-player-diagnostics.py
  tools/validate-media-capture-quality.py
  tools/validate-media-session-privacy-audit.py
  tools/validate-media-mobile-polish.py
  tools/validate-media-final-validation-gate.py
  tools/validate-phase-34-release-handoff.py
  tools/validate-phase-35-release-candidate-polish.py
  tools/validate-phase-36-external-download-handoff.py
  tools/validate-phase-37-browser-scheme-contract.py
  tools/validate-phase-38-repo-owned-extension.py
  tools/validate-phase-39-xpi-export.py
  tools/validate-phase-40-theme-fab.py
  tools/validate-phase-41-browser-bridge-integration.py
  tools/validate-phase-42-browser-bridge-release-gate.py
  tools/validate-phase-42-kotlin-compile-recovery.py
  tools/validate-retired-prerelease-channel.py
  tools/validate-browser-removal-phase-0-1.py
  tools/validate-browser-removal-phase-2.py
  tools/validate-browser-removal-phase-3.py
  tools/validate-browser-removal-phase-4.py
  tools/validate-browser-removal-phase-5.py
  tools/validate-browser-removal-phase-6.py
  tools/validate-browser-removal-phase-7.py
  tools/validate-downloader-experience-phase-8ab.py
  tools/validate-downloader-experience-phase-8c.py
  tools/validate-downloader-experience-phase-8d.py
  tools/validate-downloader-experience-phase-8e.py
  tools/validate-phase-8e-compose-storage-hotfix.py
  tools/validate-phase49-field-bugfix.py
  tools/validate-phase50-operational-repair.py
  tools/validate-phase51-recovery-storage-doctor.py
  tools/validate-phase52-browser-session-health.py
  tools/validate-phase53-extension-detection-quality-gate.py
  tools/validate-phase54-engine-escalation-planner.py
  tools/validate-phase55-final-release-warning-explainer.py
  tools/validate-phase56-stale-copy-architecture-noise-sweep.py
  tools/validate-phase57-runtime-failure-recovery-ux.py
  tools/validate-phase58-runtime-recovery-execution-guard.py
  tools/validate-phase59-runtime-recovery-action-transparency.py
  tools/validate-phase60-runtime-recovery-flow-seal.py
  tools/validate-phase61-final-gate-validator-harmony.py
  tools/validate-phase62-real-device-operational-smoke-seal.py
  tools/validate-phase63-release-readiness-support-bundle-seal.py
  tools/validate-phase64-final-android-downloader-rc-seal.py
  tools/validate-phase65-diagnostic-export-download-action-fix.py
)

for validator in "${validators[@]}"; do
  python3 "$validator"
done

FULL_GRADLE_GATE='./gradlew -Pxdm.requireAria2Runtime=true -Pxdm.cleanKotlinValidation=true -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process --no-daemon --max-workers=1 --no-parallel --no-build-cache --no-configuration-cache --stacktrace :browser-extension:test :browser-extension:jsTest :browser-extension:validateFirefoxExtension :browser-extension:packageFirefoxExtensionDark :browser-extension:packageFirefoxExtensionAmoled :browser-extension:verifyFirefoxExtensionReleaseArtifacts :app:checkBrowserIntegration :core-model:test :core-utils:test :media:test :transfer-api:test :browser-integration:testDebugUnitTest :storage:testDebugUnitTest :transfer-native:testDebugUnitTest :transfer-aria2:test :scheduler:testDebugUnitTest :persistence:testDebugUnitTest :app:testDebugUnitTest lintDebug :app:assembleDebugAndroidTest assembleDebug'

if [[ "${1:-}" == "--ci" ]]; then
  echo "CI final static gate passed"
  exit 0
fi

cat <<EOF2
Final static gate passed.

Run the full build/test/lint gate in the target Android build environment:

$FULL_GRADLE_GATE

UIX R6 seals the dark adaptive five-destination experience with 48 dp touch targets, stable semantics, 200% font-scale qualification, compact/medium/expanded layout contracts, lazy developer planners, and consumer-safe source scans.
The browser-free product boundary remains complete only after the full target-environment Gradle gate, clean-install and upgrade checks, and manual PackageManager validation pass. Device acceptance remains a separate IronFox physical-device sign-off documented in docs/browser-extension/DEVICE-ACCEPTANCE.md.
EOF2
