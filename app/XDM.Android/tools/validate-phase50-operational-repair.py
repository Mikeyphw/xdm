#!/usr/bin/env python3
from pathlib import Path
import json
import sys


def find_root() -> Path:
    cursor = Path.cwd().resolve()
    for parent in [cursor, *cursor.parents]:
        if (parent / 'app' / 'XDM.Android' / 'settings.gradle.kts').is_file():
            return parent
        if (parent / 'settings.gradle.kts').is_file() and (parent / 'app' / 'src').is_dir():
            return parent.parent.parent
    raise SystemExit('Android root not found')

ROOT = find_root()
ANDROID = ROOT / 'app' / 'XDM.Android'
errors = []


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f'missing {rel}')
        return ''
    return path.read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

main_vm = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
intake = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt')
handoff = read('app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt')
runtime = read('app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt')
native = read('app/XDM.Android/transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt')
checkpoint = read('app/XDM.Android/transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeCheckpointStore.kt')
storage = read('app/XDM.Android/storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt')
ext_review = read('app/XDM.Android/media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt')
manifest_text = read('app/XDM.Android/PROJECT_MANIFEST.json')
changelog = read('CHANGELOG.md')
doc = read('app/XDM.Android/docs/architecture/PHASE-50-OPERATIONAL-REPAIR.md')
field_test = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/FieldBugfixDownloadActionsStorageContractTest.kt')
checkpoint_test = read('app/XDM.Android/transfer-native/src/test/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeCheckpointStoreTest.kt')
storage_test = read('app/XDM.Android/storage/src/test/kotlin/com/mikeyphw/xdm/android/storage/MediaStoreVisibilityContractTest.kt')

require('Regex("\\\\{([^{}]+)\\\\}")' in checkpoint, 'checkpoint segment regex must escape both braces')
require('Regex("\\\\{([^{}]+)}")' not in checkpoint, 'broken checkpoint regex must not remain')
require('segmentObjectRegexEscapesClosingBraceForAndroidRuntime' in checkpoint_test, 'checkpoint regression test missing')

require('val requestHeaders: Map<String, String> = emptyMap()' in intake, 'download intake draft must carry transient request headers')
require('redactedHeaderSummary' in intake, 'download intake draft must carry redacted header summary')
require('transientSessionHeaders(draft.rawHeaders, draft.pageUrl)' in main_vm, 'ViewModel must parse transient session headers from handoff drafts')
require('MediaRequestHandoffStore.rememberCapture' in main_vm, 'ViewModel must remember capture-scoped transient session handoff')
require('MediaRequestHandoffStore.forgetCapture(record.id)' in main_vm, 'ViewModel must clear capture-scoped handoff after promoting it to a download handoff')
require('headers = externalSessionHeaders' in main_vm, 'manual/external add flow must feed transient headers into preview request')
require('requestHeaders = draft.requestHeaders' in ext_review, 'external media review must pass transient headers into sniffing')
require('captureHandoffs' in handoff and 'rememberCapture' in handoff and 'forCapture' in handoff, 'handoff store must support capture-scoped process-local session headers')
require('Room' not in handoff, 'handoff store must not persist raw headers to Room')
require('headers = mediaHandoff?.headers.orEmpty()' in runtime, 'runtime must consume process-local session headers')

require('applyBrowserLikeDefaults(request)' in native, 'native metadata probe must apply browser-like defaults')
require('DEFAULT_USER_AGENT' in native and 'Accept-Language' in native and 'Accept-Encoding' in native, 'native probe defaults incomplete')
require('Authentication required' in native and 'Server access was denied (HTTP $statusCode)' in native, 'native 401/403 diagnostics missing')

require('publishMediaItem' in storage, 'MediaStore publish helper missing')
require('put(MediaStore.MediaColumns.IS_PENDING, 0)' in storage, 'MediaStore completion must clear pending')
require('resolver.notifyChange(uri, null)' in storage, 'MediaStore completion must notify resolver')
require('MediaScannerConnection.scanFile' in storage, 'MediaStore completion must request scan')
require('MediaScannerConnection.scanFile' in storage_test and 'resolver.notifyChange(uri, null)' in storage_test, 'MediaStore visibility contract not updated')

require('externalBrowserSessionHandoffStaysTransientAndFeedsRuntimeRequests' in field_test, 'session handoff contract test missing')
require('field_bugfix_phase_50' in manifest_text, 'manifest missing phase 50 block')
try:
    manifest = json.loads(manifest_text)
    require(50 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 50')
    p50 = manifest.get('field_bugfix_phase_50', {})
    require(p50.get('room_schema_unchanged') == 14, 'phase 50 must not change Room schema')
    require(p50.get('top_level_route_added') is False, 'phase 50 must not add top-level route')
    require(p50.get('privacy', {}).get('raw_headers_persisted') is False, 'phase 50 privacy must forbid raw header persistence')
except Exception as exc:
    errors.append(f'manifest parse failed: {exc}')

require('Phase 50 Operational Repair' in changelog, 'changelog missing phase 50 entry')
require('Raw Cookie and Authorization values remain transient' in doc, 'phase 50 doc missing privacy boundary')

if errors:
    print('Phase 50 operational repair validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 50 operational repair validator passed')
