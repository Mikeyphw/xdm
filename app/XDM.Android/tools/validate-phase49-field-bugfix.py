#!/usr/bin/env python3
from __future__ import annotations
import json
import sys
from pathlib import Path

REQUIRED = [
    'app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDestinationUi.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt',
    'app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt',
    'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt',
    'storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt',
    'browser-extension/src/main/extension/xdm-firefox/detector-core.js',
    'browser-extension/src/main/extension/xdm-firefox/frame-bridge.js',
    'browser-extension/tests/test_detector.js',
    'app/src/test/kotlin/com/mikeyphw/xdm/android/FieldBugfixDownloadActionsStorageContractTest.kt',
    'media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaProbeFieldBugfixContractTest.kt',
    'storage/src/test/kotlin/com/mikeyphw/xdm/android/storage/MediaStoreVisibilityContractTest.kt',
    'docs/architecture/PHASE-49-FIELD-BUGFIX-DOWNLOAD-ACTIONS-STORAGE-SNIFFER.md',
    'PROJECT_MANIFEST.json',
]

def find_root() -> Path:
    candidates = []
    start = Path.cwd().resolve()
    candidates.extend([start, *start.parents])
    env = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else None
    if env:
        candidates.extend([env, *env.parents])
    for base in candidates:
        if ((base / 'settings.gradle.kts').is_file() or (base / 'PROJECT_MANIFEST.json').is_file()) and (base / 'app/src/main').is_dir():
            return base
        nested = base / 'app' / 'XDM.Android'
        if ((nested / 'settings.gradle.kts').is_file() or (nested / 'PROJECT_MANIFEST.json').is_file()) and (nested / 'app/src/main').is_dir():
            return nested
    raise SystemExit('Unable to locate XDM Android root')

def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)

def read(root: Path, rel: str) -> str:
    path = root / rel
    require(path.is_file(), f'Missing required file: {rel}')
    return path.read_text(encoding='utf-8')

def main() -> int:
    root = find_root()
    for rel in REQUIRED:
        require((root / rel).is_file(), f'Missing required file: {rel}')

    downloads = read(root, 'app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt')
    details = read(root, 'app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt')
    labels = read(root, 'app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDestinationUi.kt')
    vm = read(root, 'app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
    app = read(root, 'app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt')
    media = read(root, 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt')
    storage = read(root, 'storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt')
    detector = read(root, 'browser-extension/src/main/extension/xdm-firefox/detector-core.js')
    frame = read(root, 'browser-extension/src/main/extension/xdm-firefox/frame-bridge.js')
    detector_test = read(root, 'browser-extension/tests/test_detector.js')
    manifest = json.loads(read(root, 'PROJECT_MANIFEST.json'))

    require('DownloadActionKind.OpenFile -> openCompletedFile' in downloads, 'Open file must dispatch to file opener')
    require('DownloadActionKind.ShareFile -> shareCompletedFile' in downloads, 'Share file must dispatch to file sharing')
    require('DownloadActionKind.Cancel -> onCancelDownload' in downloads, 'Cancel must dispatch to ViewModel')
    require('DownloadActionKind.Redownload -> onRedownload' in downloads, 'Redownload must dispatch to ViewModel')
    require('DownloadActionKind.DeleteFileAndRecord -> deleteSavedFileAndRecord' in downloads, 'Delete file+record must dispatch explicitly')
    require('-> onMoveDownloadInQueue(download, action.kind)' in downloads, 'Queue moves must dispatch explicitly')
    require('DownloadActionKind.OpenFile,\n        DownloadActionKind.OpenDetails' not in downloads, 'Actions must not be grouped into details fallback')
    require('requiresConfirmation' in downloads and 'Button(onClick' in downloads, 'Confirmation sheet must remain wired')

    require('DownloadDetailRow("Save to", destinationUiLabel(download.destinationUri))' in details, 'Normal UI must use destination label')
    require('DownloadDetailRow("Save to", download.destinationUri)' not in details, 'Normal UI must not render raw destination')
    require('Saved in Android shared storage' in labels, 'Content URI label must be human')
    require('Android stores shared files with access-safe content links instead of raw paths.' in labels, 'Content URI hint must explain scoped storage')

    for symbol in ['fun cancelDownload', 'fun redownload', 'fun moveDownloadInQueue', 'transferRuntime.cancel', 'repository.saveAll(reprioritized)']:
        require(symbol in vm, f'MainViewModel missing {symbol}')
    for symbol in ['viewModel::cancelDownload', 'viewModel::redownload', 'viewModel::moveDownloadInQueue']:
        require(symbol in app, f'XdmApp missing {symbol}')

    require('applyDefaultProbeHeaders(connection, normalized, requestHeaders)' in media, 'Media probe must apply default headers')
    require('connection.responseCode' in media and media.index('connection.responseCode') < media.index('connection.inputStream'), 'HTTP status must be checked before body read')
    require('page-probe blocked by the site (HTTP' in media, '403 diagnostic must be explicit')
    require('browser extension capture so cookies, referer, and the active session stay in the browser' in media, '403 diagnostic must direct to extension capture')

    require('put(MediaStore.MediaColumns.IS_PENDING, 1)' in storage, 'MediaStore insert must mark pending')
    require('put(MediaStore.MediaColumns.IS_PENDING, 0)' in storage, 'MediaStore commit must clear pending')
    require(('put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)' in storage) or ('put(MediaStore.MediaColumns.DATE_MODIFIED, modifiedAtSeconds)' in storage), 'MediaStore commit must refresh modified date')

    require('GENERIC_URL_KEY_RE' not in detector, 'Generic JSON url/src keys must not create candidates')
    require('streamHint && mediaRequest' in detector, 'Stream hints must not accept arbitrary XHR/fetch JSON')
    require('streamHint && (mediaRequest || xhrLike)' not in detector, 'Loose stream-hint classifier must not return')
    require('mp4|url|src' not in detector, 'Generic url/src key extraction must be removed from keyed group')
    require('https://api.example/video/metadata' in detector_test, 'False-positive API test missing')
    require('posterUrl' in detector_test, 'Generic poster/src false-positive body test missing')
    require('|| STREAM_HINT_RE.test(responseUrl)' not in frame, 'Frame bridge must not trust stream-hint alone')

    require(49 in manifest['project']['implemented_phases'], 'Phase 49 not recorded')
    require(manifest['field_bugfix_phase_49']['room_schema_unchanged'] == 14, 'Room schema boundary changed')
    require(manifest['field_bugfix_phase_49']['top_level_route_added'] is False, 'Top-level route must not be added')

    print('Phase 49 field bugfix validator passed')
    return 0

if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f'Phase 49 field bugfix validator failed: {error}', file=sys.stderr)
        raise SystemExit(1)
