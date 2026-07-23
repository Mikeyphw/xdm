#!/usr/bin/env python3
from pathlib import Path
import json, re, sys
ROOT = Path(__file__).resolve().parents[1]
checks = {
    "recovery actions model": (ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "enum class RecoveryAction"),
    "finalization journal model": (ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "enum class FinalizationJournalStage"),
    "startup recovery coordinator": (ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/StartupRecoveryCoordinator.kt", "class StartupRecoveryCoordinator"),
    "atomic finalization coordinator": (ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AtomicFinalizationCoordinator.kt", "FinalizationJournalStage.DestinationCommitted"),
    "recovery workflow store": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomRecoveryWorkflowStore.kt", "class RoomRecoveryWorkflowStore"),
    "finalization journal store": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomFinalizationJournalStore.kt", "class RoomFinalizationJournalStore"),
    "migration 8 to 9": (ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt", "Migration8To9"),
    "schema": (ROOT / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/9.json", '"version": 9'),
    "recovery ui actions": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "record.recommendedAction"),
    "startup wiring": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt", "scanStartupRecovery"),
    "architecture": (ROOT / "docs/architecture/PHASE-9-STARTUP-RECOVERY-FINALIZATION.md", "Recovered jobs remain paused until the user explicitly acts"),
    "tests": (ROOT / "scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/StartupRecoveryCoordinatorTest.kt", "activeDownloadBecomesRecoveryRequiredAndPaused"),
}
errors=[]
for label,(path,needle) in checks.items():
    if not path.is_file(): errors.append(f"missing {label}: {path.relative_to(ROOT)}"); continue
    text=path.read_text(encoding="utf-8")
    if needle not in text: errors.append(f"{label} marker missing: {needle}")
    if "TODO(" in text or "TODO:" in text: errors.append(f"unfinished TODO in {path.relative_to(ROOT)}")
manifest=json.loads((ROOT/'PROJECT_MANIFEST.json').read_text())
if 9 not in manifest['project']['implemented_phases']: errors.append('project manifest does not declare Phase 9')
if manifest['database']['version'] < 9: errors.append('project manifest regressed below database v9')
app_database=(ROOT/'persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt').read_text()
match=re.search(r"version\s*=\s*(\d+)", app_database)
if match is None or int(match.group(1)) < 9: errors.append('Room database regressed below v9')
screens=(ROOT/'app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt').read_text()
if 'onClick = {}' in screens: errors.append('Phase 9 introduced an inert UI action')
if 'AppRoute.Finalization' in screens or 'AppRoute.StartupRecovery' in screens: errors.append('Phase 9 introduced a forbidden top-level route')
if errors:
    print('Phase 9 validation failed:')
    for error in errors: print(f'- {error}')
    sys.exit(1)
print('Phase 9 validation passed: startup recovery, recovery actions, finalization journals, deterministic staging, and schema v9 are present')
