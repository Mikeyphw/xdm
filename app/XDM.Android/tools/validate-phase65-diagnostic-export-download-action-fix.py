#!/usr/bin/env python3
from pathlib import Path
import json
import sys


def find_root() -> Path:
    cur = Path.cwd().resolve()
    for cand in [cur, *cur.parents]:
        if (cand / 'app/XDM.Android/PROJECT_MANIFEST.json').is_file():
            return cand
        if (cand / 'PROJECT_MANIFEST.json').is_file() and cand.name == 'XDM.Android':
            return cand.parent.parent
    raise SystemExit('Cannot locate repo root')

root = find_root()
errors = []

def read(rel: str) -> str:
    p = root / rel
    if not p.is_file():
        errors.append(f'missing {rel}')
        return ''
    return p.read_text(encoding='utf-8')

def require(cond: bool, msg: str):
    if not cond:
        errors.append(msg)

manifest = json.loads(read('app/XDM.Android/PROJECT_MANIFEST.json') or '{}')
phase = manifest.get('field_bugfix_phase_65', {})
require(phase.get('diagnostic_share_export_added') is True, 'manifest must record diagnostic share export')
require(phase.get('runtime_self_test_check_ids_exported') is True, 'manifest must record self-test check ids')
require(phase.get('list_cancel_supported') is True, 'manifest must record list cancel support')
require(phase.get('list_delete_record_supported') is True, 'manifest must record list delete support')
require(phase.get('room_schema_unchanged') in {14, 17}, 'Phase65 itself must record that it did not change Room schema')
# Phase 10 may bump app version to 0.21.0/versionCode 22 while keeping Phase65 behavior intact
for key in ['automatic_transfer_start','automatic_deletion','automatic_upload','all_files_permission_added','debug_workbench_reopened']:
    require(phase.get(key) is False, f'{key} must stay false')

helper = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/common/UiTextHelpers.kt')
self_test = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD6RuntimeSelfTestModels.kt')
self_card = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt')
debug_screen = self_card
settings = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt')
planner = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt')
screen = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt')
vm = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
repo = read('app/XDM.Android/persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt')
dao = read('app/XDM.Android/persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadDao.kt')
contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase65DiagnosticExportDownloadActionContractTest.kt')

def has(text, needle, msg):
    require(needle in text, msg)

has(helper, 'shareTextReport', 'shared text export helper missing')
has(helper, 'Intent.ACTION_SEND', 'export must use Android share sheet')
has(self_test, 'Ran check IDs:', 'self-test export must include check id list')
has(self_test, '[${check.id}]', 'self-test export must prefix each check with id')
has(self_card, 'Export self-test report', 'runtime self-test export button missing')
has(debug_screen, 'Export support report', 'Debug Workbench support export button missing')
has(settings, 'Export support report', 'Settings support export action missing')
has(planner, 'deleteHistory(download, label = "Delete download entry")', 'queued delete-entry action missing')
has(planner, 'DownloadActionKind.Cancel', 'cancel action missing from planner')
has(screen, 'DownloadActionKind.Cancel -> onCancelDownload(download)', 'cancel menu dispatch missing')
has(screen, 'DownloadActionKind.DeleteRecord -> onDeleteRecord(download)', 'delete record dispatch missing')
has(vm, 'runCatching { transferRuntime.cancel(download.id) }', 'cancel must not no-op if backend cancel throws')
has(vm, 'repository.deleteDownloadEntryIfTerminal', 'delete entry must route through transactional terminal-state graph cleanup')
has(repo, 'database.downloadGraphTransactionDao().deleteDownloadGraph(id)', 'repository must retain graph transaction deletion entry point')
has(repo, 'deleteDownloadGraphIfTerminal', 'repository must expose terminal-state graph deletion')
has(repo, 'suspend fun deleteDownload(id: String)', 'repository graph deletion entry point missing')
has(screen, 'DownloadActionKind.DeleteFileAndRecord -> onDeleteSavedFile(download, true)', 'file plus entry deletion must be explicitly ordered')
has(repo, 'deleteRecoveryForDownload', 'repository cleanup method missing')
has(repo, 'deleteFinalizationForDownload', 'repository finalization cleanup missing')
has(dao, 'DELETE FROM recovery_records WHERE downloadId = :downloadId', 'recovery DAO cleanup missing')
has(contract, 'Phase65DiagnosticExportDownloadActionContractTest', 'Phase65 contract test missing')

for forbidden in ['MANAGE_EXTERNAL_STORAGE', 'ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION']:
    require(forbidden not in helper + self_card + debug_screen + settings + planner + screen + vm + manifest.__str__(), f'forbidden storage permission/request found: {forbidden}')

if errors:
    print('Phase65 diagnostic export/download action fix validation failed:')
    for e in errors:
        print(f'- {e}')
    sys.exit(1)
print('Phase65 diagnostic export/download action fix validator passed')
