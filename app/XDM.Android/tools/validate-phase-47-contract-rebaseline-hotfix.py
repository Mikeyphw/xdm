#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
arch = (ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt").read_text()
scheme = (ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserSchemePhase37ContractTest.kt").read_text()
manifest = (ROOT / "PROJECT_MANIFEST.json").read_text()
errors = []

def require(condition, message):
    if not condition:
        errors.append(message)

require('hasBrowserRemovalLineage(manifest)' in arch, 'ArchitectureContractTest must use browser-removal lineage helper')
require('isCurrentBrowserRemovalOverlay' not in arch, 'stale current-overlay-only helper must be removed')
require('final_phase' in arch and 'media_final_validation_gate' in arch, 'Phase 33 assertion must accept final_phase manifest record')
require('phase33_landed' in arch and 'phase34_release_handoff' in arch, 'Phase 34 assertion must use the landed ledger')
require('browser_removal_phase0_1' in arch and 'browser_removal_phase4' in arch and 'browser_removal_phase7' in arch, 'lineage helper must check retained removal records')
require('defaultCategory = defaultCategory ||' in scheme, 'scheme test must accumulate DEFAULT category')
require('browsableCategory = browsableCategory ||' in scheme, 'scheme test must accumulate BROWSABLE category')
require('captureHost = captureHost ||' in scheme, 'scheme test must accumulate capture host')
require('addHost = addHost ||' in scheme, 'scheme test must accumulate add host')
require('browser_bridge_phase47_contract_rebaseline_hotfix' in manifest, 'manifest must record this hotfix')
require('browser_runtime_added": false' in manifest, 'hotfix must not add a browser runtime')

if errors:
    for error in errors:
        print(f"phase47-contract-rebaseline-hotfix: {error}", file=sys.stderr)
    sys.exit(1)
print('phase47-contract-rebaseline-hotfix: OK')
