#!/usr/bin/env bash
set -euo pipefail

# Retained validation-authority markers for app source-contract tests. These are
# historical baselines, not current authority replacements:
# post-UX13 roadmap-completion hotfix remains the historical UI release baseline
# ACT01/ACT02 list quick-actions final seal is the current experience-polish validation authority
# UX13 remains the end-to-end UI/UX baseline
# Add/Media UX remodel remains a retained functional milestone
# execution/media semantics repair remains its functional baseline
# post-DL03 roadmap seal remains its historical baseline
# Media Parity04 browser UX/userscripts/notification seal is the current final source of truth
export PYTHONDONTWRITEBYTECODE=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The Phase-11 static matrix owns these validators. Keep the literals here for
# release-contract/source-harmony checks, but do not execute them twice.
matrix_owned_validators=(
  tools/validate-ux03-downloads-queue-product-ui.py
  tools/validate-ux02-navigation-visual-hierarchy.py
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

# Validators outside the Phase-11 static matrix, including current roadmap
# carry-forward seals, run exactly once here.
validators=(
  tools/validate-uix-r3-downloads-add-workspace.py
  tools/validate-uix-r6-accessibility-performance-release-seal.py
  tools/validate-ux13-end-to-end-ui-ux-release-seal.py
  tools/validate-post-ux13-roadmap-completion-hotfix.py
  tools/validate-debug-workbench-d7-final-debug-seal.py
  tools/validate-phase61-final-gate-validator-harmony.py
  tools/validate-runtime-foundation-phase59-61.py
  tools/validate-phase65-diagnostic-export-download-action-fix.py
  tools/validate-phase64-final-android-downloader-rc-seal.py
  tools/validate-phase63-release-readiness-support-bundle-seal.py
  tools/validate-phase62-real-device-operational-smoke-seal.py
  tools/validate-phase60-runtime-recovery-flow-seal.py
  tools/validate-phase59-runtime-recovery-action-transparency.py
  tools/validate-phase57-runtime-failure-recovery-ux.py
  tools/validate-phase56-stale-copy-architecture-noise-sweep.py
  tools/validate-phase55-final-release-warning-explainer.py
  tools/validate-1dm-media-locator-xpi-v3.py
  tools/validate-media-capture-quality.py
  tools/validate-media-session-privacy-audit.py
  tools/validate-media-mobile-polish.py
  tools/validate-media-final-validation-gate.py
  tools/validate-remediation-phase13-final-gate.py
  tools/validate-dl02-dl03-progress-seal.py
  tools/validate-post-dl03-release-followup.py
  tools/validate-execution-media-semantics-repair.py
  tools/validate-add-media-ux-remodel.py
  tools/validate-promise-delivery-audit.py
  tools/validate-observability-problem-reporting.py
  tools/validate-media-thumbnail-mime-presentation.py
  tools/validate-live-locator-title-naming.py
  tools/validate-list-quick-actions-final-seal.py
  tools/validate-notification-webview-gap-hotfix.py
  tools/validate-media-parity01-runtime-truth.py
  tools/validate-media-parity02-logical-capture.py
  tools/validate-media-parity03-native-hls.py
  tools/validate-media-parity04-browser-ux-release-seal.py
)

for validator in "${matrix_owned_validators[@]}" "${validators[@]}"; do
  [[ -f "$validator" ]] || { echo "Missing final-gate validator: $validator" >&2; exit 1; }
done

for validator in "${validators[@]}"; do
  python3 "$validator"
done

# Executes the matrix-owned static validators once and validates the retained
# 80-row executable-evidence matrix.
bash tools/run-bug-hunt-phase11-validation-matrix.sh --static-only --ci

FULL_GRADLE_GATE='bash tools/run-final-common-validation.sh && bash tools/run-bug-hunt-phase11-validation-matrix.sh --device-only && bash tools/run-bug-hunt-phase11-validation-matrix.sh --release-only'

if [[ "${1:-}" == "--ci" ]]; then
  echo "CI final static gate passed including Media Parity04 browser UX/userscripts/notification seal"
  exit 0
fi

cat <<EOF2
Final static gate passed, including Media Parity04 browser UX/userscripts/notification release seal.

Run the full matrix in the target Android build environment:

$FULL_GRADLE_GATE

Media Parity04 is the current browser UX, userscripts, notification, and validation-truth authority; the post-UX13 roadmap-completion hotfix remains the historical UI release baseline; the 2026-09-10 ACT01/ACT02 list quick-actions final seal remains a retained experience-polish validation baseline for the observability, thumbnail/MIME, Live Locator/title-naming, and card-action roadmap. UX13 remains the end-to-end UI/UX baseline and UX12 remains its accessibility/terminology baseline, the Add/Media UX remodel remains a retained functional milestone, the execution/media semantics repair remains its functional baseline, and the post-DL03 roadmap seal remains its historical baseline. The 2026-09-08 promise-delivery audit seals carry-forward correctness for legacy shape/wire isolation, opaque URL refresh, Live Locator recreation privacy, direct-media Options gating, the scoped AndroidX WebKit COOKIE_INTERCEPT and Kotlin renderer-detector lint guards, resource-backed Live Locator status text, and recovery-resume unwind synchronization. The common validation runner, current MC/DL validators, all four 2026-09-10 roadmap validators plus the notification/WebView gap hotfix validator, and retained Phase-11 device/release matrix provide executable evidence; documentation-only coverage is rejected.
EOF2
