#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = [
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBatchIntake.kt', [
        'class MediaBatchInputParser',
        'class MediaBatchIntakePlanner',
        'MediaBatchParseResult',
        'MediaBatchUrlDisposition',
        'NeedsPageInspection',
        'maxInputChars: Int = 256 * 1024',
        'maxUrls: Int = 200',
        'https?://',
        'Only HTTP and HTTPS media links are supported',
        'dedup',
        'signed query strings',
    ]),
    (ROOT / 'media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaBatchInputParserTest.kt', [
        'parserAcceptsLfAndCrlfExtractsTextUrlsAndDedupes',
        'plannerCreatesRecordsOnlyForConcreteMediaCandidates',
        'parserRejectsUnsafeSchemesAndCapsInput',
    ]),
    (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt', [
        'MediaBatchInputPanel',
        'Paste URLs or page text',
        'Inspect all',
        'Add selected',
        'Clear invalid',
        'Copy rejected lines',
        'onBatchInput',
    ]),
    (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', [
        'MediaBatchIntakePlanner',
        'captureMediaBatchInput',
        'mediaBatchIntakePlanner.plan',
        'repository.saveMediaCaptures',
        'repository.saveMediaVariants',
    ]),
    (ROOT / 'app/src/test/kotlin/com/mikeyphw/xdm/android/MediaBatchPhase46ContractTest.kt', [
        'mediaScreenExposesReviewFirstBatchActions',
        'viewModelRoutesBatchThroughMediaBatchPlanner',
    ]),
    (ROOT / 'docs/architecture/PHASE-46-MEDIA-BATCH-INTAKE.md', [
        'Phase 46',
        'review-first batch intake',
        'does not execute JavaScript',
        'Phase 47',
    ]),
]
errors = []
for path, markers in checks:
    if not path.exists():
        errors.append(f'missing {path.relative_to(ROOT)}')
        continue
    text = path.read_text(encoding='utf-8')
    for marker in markers:
        if marker not in text:
            errors.append(f'missing marker {marker!r} in {path.relative_to(ROOT)}')
manifest = json.loads((ROOT / 'PROJECT_MANIFEST.json').read_text(encoding='utf-8'))
phase = manifest.get('browser_bridge_phase46_media_batch_intake', {})
expected = {
    'batch_input_panel': True,
    'parser': 'MediaBatchInputParser',
    'planner': 'MediaBatchIntakePlanner',
    'unsafe_schemes_rejected': True,
    'dedupe_normalized_urls': True,
    'page_urls_deferred_to_phase47': True,
    'network_probe_added': False,
    'javascript_execution_added': False,
    'room_schema_unchanged': 14,
}
for key, value in expected.items():
    if phase.get(key) != value:
        errors.append(f'manifest {key} expected {value!r}, got {phase.get(key)!r}')
if 46 not in manifest.get('project', {}).get('implemented_phases', []):
    errors.append('implemented_phases does not include 46')
if errors:
    print('Phase 46 media batch intake validation failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 46 media batch intake validation passed with', sum(len(m) for _, m in checks) + len(expected) + 1, 'checks')
