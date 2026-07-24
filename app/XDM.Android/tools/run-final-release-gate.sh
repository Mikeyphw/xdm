#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

validators=(
  tools/validate-foundation.py
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
  tools/validate-browser-media-downloader.py
  tools/validate-browser-media-continuity.py
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
  tools/validate-media-browser-capture-quality.py
  tools/validate-media-session-privacy-audit.py
  tools/validate-media-mobile-polish.py
  tools/validate-media-final-validation-gate.py
  tools/validate-phase-34-release-handoff.py
  tools/validate-phase-35-release-candidate-polish.py
  tools/validate-phase-36-external-download-handoff.py
  tools/validate-phase-37a-browser-downloader-roadmap.py
  tools/validate-phase-37b-dual-launcher-navigation-split.py
)

for validator in "${validators[@]}"; do
  python3 "$validator"
done

FULL_GRADLE_GATE='./gradlew -Pxdm.requireAria2Runtime=true --stacktrace lintDebug lintBeta :media:test :transfer-api:test :storage:test :transfer-native:test :transfer-aria2:test :scheduler:test :persistence:testDebugUnitTest testDebugUnitTest assembleDebug assembleBeta'

if [[ "${1:-}" == "--ci" ]]; then
  echo "CI final static gate passed"
  exit 0
fi

cat <<EOF2
Final static gate passed.

Run the full build/test/lint gate in the target Android build environment:

$FULL_GRADLE_GATE

Then apply the Phase 37B dual launcher/navigation split overlay with validation enabled if this overlay has not been applied yet:

devtool --copy --auto-hud --hud-mode desktop-window --yes -r "\$HOME/Code/xdm" --target xdm_android apply-overlay "\$HOME/Downloads/xdm_android_phase37b_dual_launcher_navigation_split_overlay.zip" --validate
EOF2
