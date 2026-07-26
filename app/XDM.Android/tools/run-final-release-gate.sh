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
)

for validator in "${validators[@]}"; do
  python3 "$validator"
done

FULL_GRADLE_GATE='./gradlew -Pxdm.requireAria2Runtime=true --stacktrace lintDebug lintBeta :media:test :transfer-api:test :storage:test :transfer-native:test :transfer-aria2:test :scheduler:test :persistence:testDebugUnitTest testDebugUnitTest :app:assembleDebugAndroidTest assembleDebug assembleBeta'

if [[ "${1:-}" == "--ci" ]]; then
  echo "CI final static gate passed"
  exit 0
fi

cat <<EOF2
Final static gate passed.

Run the full build/test/lint gate in the target Android build environment:

$FULL_GRADLE_GATE

Phase 7 seals the downloader-only product, Phase 8A + 8B refines intake and Downloads, Phase 8C adds explainable queue policy, Phase 8D adds a first-class review-first media resolver workspace, and Phase 8E adds searchable operational visibility and privacy-safe diagnostics. The Phase 8E repair chain preserves scoped Compose layout extensions, warning-free storage reevaluation, JUnit 4 test compilation, and downloader-first architecture contracts.
The browser-free product boundary remains complete after the full target-environment Gradle gate and manual PackageManager check pass.
EOF2
