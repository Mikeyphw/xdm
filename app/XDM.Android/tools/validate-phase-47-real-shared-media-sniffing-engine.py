#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = [
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt', [
        'data class MediaSniffingInput',
        'data class MediaSniffingCandidate',
        'data class MediaSniffingPlan',
        'class MediaSniffingEngine',
        'class MediaPageProbe',
        'MediaPageProbePolicy',
        'bodyPrefixBytes: Int = 768 * 1024',
        'connectTimeoutMillis: Int = 10_000',
        'readTimeoutMillis: Int = 10_000',
        '#EXTM3U',
        '<MPD',
        'json-or-script-url',
        'html-attribute',
        'css-url',
        'unicodeEscapePattern',
        'resolve(uri)',
        'isFragmentOrNoise',
        'rankFor',
        'signed media query strings preserved',
        'PrivacyDiagnosticsRedactor',
        'no arbitrary JavaScript execution',
        'no DRM bypass',
    ]),
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBatchIntake.kt', [
        'MediaSniffingEngine',
        'MediaSniffingSource.BatchInput',
        'sniffingPlan.records',
        'sniffingPlan.variants',
        'sniffingCandidates',
        'sniffingDiagnostics',
    ]),
    (ROOT / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt', [
        'MediaSniffingEngine',
        'MediaSniffingInput',
        'MediaSniffingSource.ManualPage',
        'sniffingPlan.records.firstOrNull',
    ]),
    (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', [
        'private val mediaSniffingEngine = MediaSniffingEngine(mediaCaptureService',
        'MediaSniffingSource.SharedText',
        'MediaSniffingSource.BrowserExtension',
        'sniffingPlan.records',
        'sniffingPlan.variants',
    ]),
    (ROOT / 'app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt', [
        'shared app-side media sniffing engine',
        'Static sniffing only',
        'no arbitrary JavaScript execution',
        'no DRM bypass',
        'Checkbox',
        'selectedUrls',
        'onAddSelected',
    ]),
    (ROOT / 'media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngineTest.kt', [
        'snifferExtractsJsonHtmlEscapedCssAndRelativeMediaUrls',
        'bodySignaturesDetectManifestEvenWithoutExtension',
        'snifferRedactsHeadersAndDoesNotExposeCredentials',
    ]),
    (ROOT / 'app/src/test/kotlin/com/mikeyphw/xdm/android/MediaSniffingPhase47ContractTest.kt', [
        'sharedEngineExistsWithBoundedProbeAndPrivacyContract',
        'appRoutesSharesAutomationBatchAndExternalReviewThroughSharedSniffer',
        'mediaBatchAddSelectedIsSelectionBasedAndFinalSealRecordsCorrection',
    ]),
    (ROOT / 'docs/architecture/PHASE-47-REAL-SHARED-MEDIA-SNIFFING-ENGINE.md', [
        'Phase 47 correction',
        'real shared app-side media sniffing engine',
        'Bounded',
        'No arbitrary JavaScript execution',
        'No DRM bypass',
        'Add selected is now selection-based',
        'phase48_corrected_after_audit',
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
phase = manifest.get('browser_bridge_phase47_real_shared_media_sniffing_engine', {})
expected = {
    'shared_engine': 'MediaSniffingEngine',
    'input_model': 'MediaSniffingInput',
    'candidate_model': 'MediaSniffingCandidate',
    'bounded_page_probe': 'MediaPageProbe',
    'probe_timeout_millis': 10000,
    'body_prefix_bytes': 786432,
    'detects_hls_by_body_signature': True,
    'detects_dash_by_body_signature': True,
    'extracts_json_urls': True,
    'extracts_html_script_urls': True,
    'extracts_css_urls': True,
    'decodes_escaped_urls': True,
    'decodes_unicode_escaped_urls': True,
    'resolves_relative_urls': True,
    'filters_segments_fragments_ads': True,
    'ranks_manifests_over_segments': True,
    'signed_query_strings_preserved': True,
    'redacted_diagnostics': True,
    'add_selected_selection_based': True,
    'javascript_execution_added': False,
    'drm_bypass_added': False,
    'embedded_browser_added': False,
    'room_schema_unchanged': 14,
}
for key, value in expected.items():
    if phase.get(key) != value:
        errors.append(f'manifest {key} expected {value!r}, got {phase.get(key)!r}')
if not manifest.get('phase48_corrected_after_audit'):
    errors.append('manifest missing phase48_corrected_after_audit')
if manifest.get('current_overlay') not in {'xdm_android_phase47_real_shared_media_sniffing_engine_overlay.zip', 'xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip'}:
    errors.append('current_overlay does not point at real Phase 47 correction overlay or final RC seal')
if errors:
    print('Phase 47 real shared media sniffing engine validation failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 47 real shared media sniffing engine validation passed with', sum(len(markers) for _, markers in checks) + len(expected) + 2, 'checks')
