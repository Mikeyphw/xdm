#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Final static release gate is intentionally routed through the bug-hunt
# validation matrix. Older UIX/field-fix validators encoded historical
# assumptions such as Room schema 14 and pre-0.21.0 metadata; Phase 11 keeps
# their still-relevant checks through current phase validators and executable
# evidence rows instead of replaying stale release-candidate constants.
validators=(
  tools/validate-uix-r3-downloads-add-workspace.py
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
  tools/validate-phase61-final-gate-validator-harmony.py
  tools/validate-bug-hunt-phase11-validation-matrix.py
)

for validator in "${validators[@]}"; do
  python3 "$validator"
done

bash tools/run-bug-hunt-phase11-validation-matrix.sh --static-only --ci

FULL_GRADLE_GATE='bash tools/run-bug-hunt-phase11-validation-matrix.sh && bash tools/run-bug-hunt-phase11-validation-matrix.sh --device-only && bash tools/run-bug-hunt-phase11-validation-matrix.sh --release-only'

if [[ "${1:-}" == "--ci" ]]; then
  echo "CI final static gate passed"
  exit 0
fi

cat <<EOF2
Final static gate passed.

Run the full matrix in the target Android build environment:

$FULL_GRADLE_GATE

Phase 11 is the source of truth for bug-hunt acceptance: 80 roadmap rows, executable evidence for every row, a static CI gate, device/instrumentation mode, and signed-release mode. Documentation-only coverage is rejected.
EOF2
