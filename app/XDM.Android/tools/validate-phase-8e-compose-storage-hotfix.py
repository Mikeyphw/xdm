#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
expected = {
    "xdm_android_browser_removal_phase8e_compile_test_repair_overlay.zip",
    "xdm_android_browser_removal_phase8e_gradle_contract_repair_overlay.zip",
}
if manifest.get("current_overlay") not in {"xdm_android_phase61_final_gate_validator_harmony_overlay.zip", "xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip"} and manifest.get("current_overlay") not in expected:
    errors.append("current_overlay must identify the Phase 8E compile or Gradle contract repair")

contract = manifest.get("downloader_experience_phase8e_compile_hotfix", {})
for key in (
    "compose_weight_import_repaired",
    "scoped_row_column_weight_preserved",
    "deprecated_storage_intent_fields_removed",
    "storage_policy_rechecks_actual_free_space",
    "phase8e_activity_diagnostics_preserved",
    "phase8c_queue_policy_preserved",
    "browser_runtime_remains_absent",
    "core_model_junit4_test_imports_repaired",
    "gradle_architecture_contracts_rebased",
    "handoff_state_flow_contract_updated",
    "downloads_dashboard_ordering_contract_updated",
    "activity_library_contract_updated",
    "route_restore_contract_updated",
    "empty_retired_browser_directory_tolerated",
    "all_app_architecture_contracts_pass",
):
    if contract.get(key) is not True:
        errors.append(f"downloader_experience_phase8e_compile_hotfix.{key} must be true")

for relative in (
    "app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityScreens.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
):
    source = read(relative)
    if "import androidx.compose.foundation.layout.weight" in source:
        errors.append(f"{relative} imports the internal Compose weight symbol")
    if ".weight(1f)" not in source:
        errors.append(f"{relative} unexpectedly lost scoped weight usage")

monitor = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueConditionMonitor.kt")
for forbidden in ("Intent.ACTION_DEVICE_STORAGE_LOW", "Intent.ACTION_DEVICE_STORAGE_OK"):
    if forbidden in monitor:
        errors.append(f"QueueConditionMonitor still references deprecated field: {forbidden}")
for marker in (
    'private const val ACTION_DEVICE_STORAGE_LOW = "android.intent.action.DEVICE_STORAGE_LOW"',
    'private const val ACTION_DEVICE_STORAGE_OK = "android.intent.action.DEVICE_STORAGE_OK"',
    "actual free space before starting a transfer",
):
    if marker not in monitor:
        errors.append(f"QueueConditionMonitor missing marker: {marker}")

contract_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/Phase8ECompileHotfixContractTest.kt")
for marker in (
    "scopedWeightExtensionsAreResolvedFromLayoutScopes",
    "storageReevaluationAvoidsDeprecatedIntentFields",
    "operationalActivityTestsUseTheModuleJUnit4Contract",
):
    if marker not in contract_test:
        errors.append(f"Compile hotfix contract test missing marker: {marker}")


operational_test = read("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/OperationalActivityTest.kt")
for required in (
    "import org.junit.Test",
    "import org.junit.Assert.assertEquals",
    "import org.junit.Assert.assertFalse",
    "import org.junit.Assert.assertTrue",
):
    if required not in operational_test:
        errors.append(f"OperationalActivityTest missing module-compatible import: {required}")
if "import kotlin.test" in operational_test:
    errors.append("OperationalActivityTest must not use kotlin.test without kotlin-test dependency")


architecture_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
for required in (
    "DownloadDashboardOrdering",
    "Activity and Library Operational Rules",
    "externalAddDraft.value = downloadIntakePlanner.fromExternal(",
    "externalAddDraft = review.externalAddDraft",
    "Review download",
    "Add to queue",
    "retiredBrowserDocs.walkTopDown().none { it.isFile }",
):
    if required not in architecture_test:
        errors.append(f"Architecture contract repair missing marker: {required}")
for forbidden in (
    "externalAddDraft = addDraft",
    "screens.contains(\"DownloadSort\")",
    "Secondary Route Operational Rules",
    "val canSubmit = url.isNotBlank() && destinationUri.isNotBlank()",
):
    if forbidden in architecture_test:
        errors.append(f"Stale architecture assertion remains: {forbidden}")

phase3_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase3ContractTest.kt")
for required in (
    "state.externalAddDraft?.let(viewModel::inspectExternalMedia)",
    "viewModel.inspectManualMedia(url, fileName)",
):
    if required not in phase3_test:
        errors.append(f"Phase 3 handoff contract repair missing marker: {required}")

phase4_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase4ContractTest.kt")
for required in (
    "lastRoute = AppRoute.restore(preferences[Keys.LastRoute])",
    "fun restore(storedName: String?): AppRoute",
):
    if required not in phase4_test:
        errors.append(f"Phase 4 route contract repair missing marker: {required}")

phase5_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase5ContractTest.kt")
if "retiredBrowserDocs.walkTopDown().none { it.isFile }" not in phase5_test:
    errors.append("Phase 5 browser-directory contract must reject files rather than an empty directory")

phase6_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserRemovalPhase6ContractTest.kt")
if 'screens.contains("fun ActivityOverviewScreen(")' not in phase6_test:
    errors.append("Phase 6 Activity ownership contract still requires obsolete one-line formatting")

joined = "\n".join(
    read(path) for path in (
        "app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityScreens.kt",
        "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
        "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueConditionMonitor.kt",
    )
)
for forbidden in ("android.webkit.WebView", "BrowserActivity", "AppRoute.Browser"):
    if forbidden in joined:
        errors.append(f"Browser runtime token returned: {forbidden}")

if errors:
    for error in errors:
        print(f"ERROR: {error}")
    sys.exit(1)
print("Phase 8E Compose/storage/test compile repair validation passed")
