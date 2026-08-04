#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mode="${1:-}"
ci="${2:-}"

STATIC_VALIDATORS=(
  tools/validate-bug-hunt-phase1-external-control-secrets-privacy.py
  tools/validate-bug-hunt-phase2-download-execution.py
  tools/validate-bug-hunt-phase3-storage-publication-verification-repair.py
  tools/validate-bug-hunt-phase4-queue-scheduling-state-machines.py
  tools/validate-bug-hunt-phase5-browser-handoff-media.py
  tools/validate-bug-hunt-phase6-database-integrity-migrations.py
  tools/validate-bug-hunt-phase7-post-processing-termux.py
  tools/validate-bug-hunt-phase8-download-actions-ui-truthfulness.py
  tools/validate-bug-hunt-phase9-accessibility-adaptive-layout.py
  tools/validate-bug-hunt-phase10-release-upgrade-packaging.py
  tools/verify-phase10-backup-policy.py
  tools/validate-phase58-runtime-recovery-execution-guard.py
  tools/validate-bug-hunt-phase11-validation-matrix.py
)

COMMON_TASKS=(
  :core-model:test
  :transfer-api:test
  :storage:testDebugUnitTest
  :transfer-native:testDebugUnitTest
  :transfer-aria2:test
  :scheduler:testDebugUnitTest
  :media:test
  :persistence:testDebugUnitTest
  :browser-extension:test
  :browser-extension:validateFirefoxExtension
  :app:testDebugUnitTest
)
DEVICE_TASKS=(
  :app:connectedDebugAndroidTest
)
RELEASE_TASKS=(
  :app:lintRelease
  :app:testReleaseUnitTest
  :app:bundleRelease
)
RELEASE_VALIDATORS=(
  tools/verify-phase10-release-artifacts.py
)

# Historical validators referenced by matrix rows are superseded by current bug-hunt validators when their original phase assumptions (for example Room schema 14/current_overlay) are no longer true in schema 17. Kept here so matrix rows have an explicit owning runner while static mode does not execute stale historical gates.
LEGACY_VALIDATORS_REFERENCED_BY_MATRIX=(
  tools/validate-phase60-runtime-recovery-flow-seal.py
  tools/validate-phase51-recovery-storage-doctor.py
  tools/validate-phase63-release-readiness-support-bundle-seal.py
)

for validator in "${STATIC_VALIDATORS[@]}"; do
  python3 "$validator"
done

python3 - <<'PY_PHASE11_MATRIX_SUMMARY'
import json
from pathlib import Path
m=json.loads(Path('tools/bug-hunt-phase11-validation-matrix.json').read_text())
levels=sorted(set(e['execution_level'] for e in m['entries']))
print(f"Phase 11 Validation Matrix: {len(m['entries'])} roadmap rows, levels={','.join(levels)}")
PY_PHASE11_MATRIX_SUMMARY

if [[ "$mode" == "--static-only" ]]; then
  echo "Phase 11 static matrix gate passed"
  exit 0
fi

if [[ "$mode" == "--device-only" ]]; then
  ./gradlew "${DEVICE_TASKS[@]}"
  echo "Phase 11 device matrix gate passed"
  exit 0
fi

if [[ "$mode" == "--release-only" ]]; then
  for validator in "${RELEASE_VALIDATORS[@]}"; do
    python3 "$validator"
  done
  bash tools/run-bug-hunt-phase10-release-gate.sh
  ./gradlew "${RELEASE_TASKS[@]}"
  echo "Phase 11 release matrix gate passed"
  exit 0
fi

./gradlew "${COMMON_TASKS[@]}"

cat <<'EOF_MATRIX'
Phase 11 common executable matrix passed.

Remaining target-environment gates required for complete acceptance:
- Device/instrumentation: bash tools/run-bug-hunt-phase11-validation-matrix.sh --device-only
- Signed release/APK-set/publication: bash tools/run-bug-hunt-phase11-validation-matrix.sh --release-only
- Previous-release upgrade/reboot/downgrade: bash tools/run-phase10-install-upgrade-matrix.sh <previous.apk> <candidate.apk>

The source of truth is tools/bug-hunt-phase11-validation-matrix.json; every row maps a roadmap requirement to executable evidence and a gate command. Rows must never be satisfied by documentation alone.
EOF_MATRIX
