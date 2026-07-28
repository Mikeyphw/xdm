#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

REQUIRED_TASKS = [
    'assembleDebug',
    ':app:assembleDebugAndroidTest',
    ':browser-extension:packageFirefoxExtensionDark',
    ':browser-extension:packageFirefoxExtensionAmoled',
    ':browser-extension:verifyFirefoxExtensionReleaseArtifacts',
    ':browser-extension:test',
    ':browser-extension:jsTest',
    ':browser-extension:validateFirefoxExtension',
    ':app:checkBrowserIntegration',
    ':core-model:test',
    ':core-utils:test',
    ':transfer-api:test',
    ':browser-integration:testDebugUnitTest',
    ':storage:testDebugUnitTest',
    ':transfer-native:testDebugUnitTest',
    ':transfer-aria2:test',
    ':scheduler:testDebugUnitTest',
    ':media:test',
    ':persistence:testDebugUnitTest',
    ':app:testDebugUnitTest',
]


def root() -> Path:
    cursor = Path.cwd().resolve()
    for candidate in [cursor, *cursor.parents]:
        if (candidate / 'settings.gradle.kts').is_file() and (candidate / 'PROJECT_MANIFEST.json').is_file():
            return candidate
    raise SystemExit('Unable to locate XDM Android root')


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def main() -> int:
    project = root()
    failures: list[str] = []

    manifest_path = project / 'PROJECT_MANIFEST.json'
    manifest_text = manifest_path.read_text(encoding='utf-8')
    manifest = json.loads(manifest_text)
    gate = manifest.get('browser_bridge_phase48_final_ux_release_gate') or {}

    require(manifest.get('next_phase') == 'complete', 'manifest next_phase must be complete', failures)
    require(48 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 48', failures)
    require(gate.get('final_phase_complete') is True, 'Phase 48 final_phase_complete must be true', failures)
    require(gate.get('baseline_commit') == '6e3ad8d', 'Phase 48 must record baseline commit 6e3ad8d', failures)
    require(gate.get('runtime_behavior_changed') is False, 'Phase 48 must not change runtime behavior', failures)
    require(gate.get('browser_runtime_added') is False, 'Phase 48 must not add browser runtime', failures)
    require(gate.get('top_level_route_added') is False, 'Phase 48 must not add a top-level route', failures)
    require(gate.get('room_schema_unchanged') == 14, 'Phase 48 must keep Room schema 14', failures)
    require(gate.get('version_name_unchanged') == '0.20.0-rc08', 'Phase 48 must keep versionName 0.20.0-rc08', failures)
    require(gate.get('version_code_unchanged') == 21, 'Phase 48 must keep versionCode 21', failures)

    phase47 = gate.get('phase47_validation') or {}
    require(phase47.get('tests_passed') == 358, 'Phase 48 must record 358 passing Phase 47 r6 tests', failures)
    require(phase47.get('tests_failed') == 0, 'Phase 48 must record zero Phase 47 r6 failures', failures)
    require(phase47.get('diagnostic_warnings') == 0, 'Phase 48 must record zero Phase 47 r6 warnings', failures)
    require(phase47.get('diagnostic_errors') == 0, 'Phase 48 must record zero Phase 47 r6 errors', failures)

    tasks = gate.get('full_validation_tasks') or []
    for task in REQUIRED_TASKS:
        require(task in tasks, f'Phase 48 full validation task missing: {task}', failures)

    ship_gate = gate.get('ship_no_ship_gates') or {}
    for key in [
        'zero_gradle_failures_required',
        'zero_diagnostics_warnings_required',
        'zero_diagnostics_errors_required',
        'release_extension_inventory_required',
        'browser_runtime_reintroduction_forbidden',
        'raw_secret_diagnostics_forbidden',
        'generated_xpi_source_commit_forbidden',
    ]:
        require(ship_gate.get(key) is True, f'Phase 48 ship gate missing {key}', failures)

    doc = project / 'docs/architecture/PHASE-48-FINAL-UX-RELEASE-GATE.md'
    require(doc.is_file(), 'Phase 48 architecture document is missing', failures)
    doc_text = doc.read_text(encoding='utf-8') if doc.is_file() else ''
    for needle in [
        'review-first',
        '358 passed, 0 failed, 0 skipped',
        '0 warnings, 0 errors',
        'Browser runtime must not be reintroduced',
        'Generated XPI files must not be committed as source',
        'Ship / no-ship gate',
    ]:
        require(needle in doc_text, f'Phase 48 document missing {needle!r}', failures)

    gate_script = project / 'tools/run-phase-48-final-release-gate.sh'
    require(gate_script.is_file(), 'Phase 48 release gate script is missing', failures)
    gate_text = gate_script.read_text(encoding='utf-8') if gate_script.is_file() else ''
    for task in REQUIRED_TASKS:
        require(task in gate_text, f'Phase 48 release script missing task {task}', failures)
    require('validate-phase-48-final-ux-release-gate.py' in gate_text, 'Phase 48 release script must run the static validator', failures)

    test = project / 'app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserBridgePhase48FinalReleaseGateContractTest.kt'
    require(test.is_file(), 'Phase 48 app contract test is missing', failures)
    test_text = test.read_text(encoding='utf-8') if test.is_file() else ''
    for needle in ['browser_bridge_phase48_final_ux_release_gate', 'run-phase-48-final-release-gate.sh', 'noPlaceholderClickHandlersRemainSealed']:
        require(needle in test_text, f'Phase 48 contract test missing {needle!r}', failures)

    extension_source = project / 'browser-extension/src/main/extension/xdm-firefox'
    committed_xpis = list(extension_source.rglob('*.xpi')) if extension_source.is_dir() else []
    require(not committed_xpis, 'Generated XPI files must not be committed under extension source', failures)

    if failures:
        print('Phase 48 final UX release gate validation failed:')
        for item in failures:
            print(f'- {item}')
        return 1
    print('Phase 48 final UX release gate validation passed.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
