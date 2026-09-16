#!/usr/bin/env python3
"""Write same-run RM04 validation evidence after the canonical non-device gate succeeds."""
from __future__ import annotations
import argparse
import hashlib
import json
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--final-common-log', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    args = parser.parse_args()
    log = args.final_common_log
    if not log.is_file() or log.stat().st_size == 0:
        raise SystemExit(f'RM04 final-common validation log is missing/empty: {log}')
    required_sources = [
        ROOT / 'tools/run-final-release-gate.sh',
        ROOT / 'tools/validate-rm04-diagnostics-release-final-seal.py',
        ROOT / 'core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DiagnosticExportIntegrity.kt',
        ROOT / 'core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt',
        ROOT / 'core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessSeal.kt',
    ]
    missing = [str(path) for path in required_sources if not path.is_file()]
    if missing:
        raise SystemExit('RM04 source evidence is incomplete: ' + ', '.join(missing))
    payload = {
        'schemaVersion': 1,
        'roadmapOverlay': 'RM04',
        'runId': args.run_id,
        'generatedAtEpochMs': int(time.time() * 1000),
        'canonicalNonDeviceValidationPassed': True,
        'runtimeDiagnosticsPrivacyContract': 'validated-by-test-and-static-chain',
        'finalDiagnosticsZipPrivacyIntegrity': 'validated-by-test-and-static-chain',
        'staticValidatorChain': 'passed',
        'releaseDocsValidator': 'passed',
        'routeTopologyValidator': 'passed',
        'fullSelectedTaskValidation': 'passed',
        'finalCommonValidationLog': {
            'path': str(log),
            'sha256': sha256(log),
            'bytes': log.stat().st_size,
        },
        'sourceEvidence': [
            {'path': str(path.relative_to(ROOT)), 'sha256': sha256(path)} for path in required_sources
        ],
        'note': 'Generated only after run-final-common-validation.sh returned success under set -e in the XAR16 artifact builder.',
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
    print(args.out)
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
