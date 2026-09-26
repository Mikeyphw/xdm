#!/usr/bin/env python3
from __future__ import annotations

import json
import re
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


def require(text: str, marker: str, owner: str) -> None:
    if marker not in text:
        errors.append(f"{owner} missing marker: {marker}")


manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
expected_overlay = "xdm_android_browser_removal_phase8c_queue_intelligence_execution_policy_overlay.zip"
current_overlay = str(manifest.get("current_overlay", ""))
approved_successors = {
    "xdm_android_phase61_final_gate_validator_harmony_overlay.zip",
    "xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip",
    "xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip",
    "xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip",
    "XAR13", "XAR14", "XAR15", "XAR16", "XAR17",
}
if current_overlay not in approved_successors and current_overlay != expected_overlay and not current_overlay.startswith(("xdm_android_browser_removal_phase8d_", "xdm_android_browser_removal_phase8e_")):
    errors.append("current_overlay must identify Phase 8C or a recorded later downloader/remediation successor")
phase = manifest.get("downloader_experience_phase8c", {})
for key in (
    "explainable_execution_policy",
    "validated_network_gate",
    "network_power_battery_storage_constraints",
    "schedule_window_enforcement",
    "overnight_schedule_start_day_semantics",
    "per_queue_concurrency",
    "priority_fairness_ranking",
    "classified_retry_backoff",
    "explicit_policy_override",
    "persistent_decision_ledger",
    "condition_change_monitoring",
    "foreground_workmanager_ownership",
    "external_handoff_preserved",
    "all_download_engines_preserved",
    "browser_runtime_remains_absent",
):
    if phase.get(key) is not True:
        errors.append(f"downloader_experience_phase8c.{key} must be true")
if phase.get("stable_routes") != ["Downloads", "Add", "Media", "Library", "Activity", "Settings"]:
    errors.append("Stable downloader routes changed")
if phase.get("room_schema_unchanged") != 14:
    errors.append("Phase 8C manifest must preserve its historical Room schema 14 marker")
if phase.get("version_name_unchanged") != "0.20.0-rc08" or phase.get("version_code_unchanged") != 21:
    errors.append("Phase 8C manifest must preserve its historical app version 0.20.0-rc08 / 21 marker")

model = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueIntelligence.kt")
for marker in (
    "data class QueueExecutionPolicy",
    "data class QueueRuntimeConditions",
    "enum class QueueHoldReason",
    "data class QueueLaunchDecision",
    "data class QueueFailureAssessment",
    "data class QueueDecisionEvent",
    "object QueueIntelligencePlanner",
    "policyOverride: Boolean = false",
    "No validated internet connection is available.",
    "Overnight windows belong to the day on which they start",
    "fun assessFailure(message: String)",
    "fun retryRecord(",
    "fun rank(downloads: List<Download>",
):
    require(model, marker, "Queue intelligence model")
for forbidden in ("android.content", "androidx.work", "DownloadRepository", "TransferExecutionStarter"):
    if forbidden in model:
        errors.append(f"Pure queue policy model depends on runtime token: {forbidden}")

coordinator = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
for marker in (
    "data class QueueReconcileOutcome",
    "suspend fun evaluateAndClaim(): QueueReconcileOutcome",
    "scheduleSummary = resolved.nextWindowSummary",
    "policyOverride = policyOverride",
    "decisionLedger.record",
    "QueueIntelligencePlanner.rank",
    "QueueIntelligencePlanner.decision",
    "executionStarter.start(current.id, current.totalBytes, userVisible, current.updatedAtEpochMs)",
):
    require(coordinator, marker, "Queue coordinator")

worker = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
for marker in (
    "AndroidSchedulerHost.wake(",
    "ExistingWorkPolicy.KEEP",
):
    require(worker, marker, "Queue Go-host worker")
for forbidden in ("coordinator.evaluateAndClaim()", "runtime.execute(", "runtime.pauseOwned(", "executionStarter.start"):
    if forbidden in worker:
        errors.append(f"Background queue worker retained Kotlin execution authority after XGO-71: {forbidden}")

for path, markers in {
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueDecisionLedger.kt": (
        "class QueueDecisionLedger",
        "MAX_EVENTS = 240",
        "xdm_queue_decision_ledger",
    ),
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueConditionMonitor.kt": (
        "registerDefaultNetworkCallback",
        "ACTION_POWER_CONNECTED",
        "ACTION_DEVICE_STORAGE_LOW",
        "ACTION_TIMEZONE_CHANGED",
    ),
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidQueueConditionsReader.kt": (
        "NET_CAPABILITY_VALIDATED",
        "availableStorageBytes",
    ),
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueRetryLedger.kt": (
        "QueueIntelligencePlanner.retryRecord",
        "xdm_queue_retry_ledger",
    ),
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueuePolicyCodec.kt": (
        "networkRequirement",
        "retryStrategy",
        "minimumFreeStorageMb",
        "maxAutoRetries",
    ),
}.items():
    text = read(path)
    for marker in markers:
        require(text, marker, path)

application = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
for marker in ("QueueConditionMonitor", "QueueIntelligenceWorker.schedule(this)", "QueueIntelligenceWorker.enqueueImmediate"):
    require(application, marker, "XdmApplication")

view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
ui_broker = read("app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidLegacyDownloadUiBroker.kt")
for marker in (
    "val queueIntelligence: QueueIntelligenceSummary",
    "fun runQueueIntelligenceNow()",
    "fun startIgnoringQueuePolicy(download: Download)",
    "androidDownloadUiClient.command",
    "policyOverride = DownloadActionExecutionTruth.policyOverrideFromCurrent(download)",
):
    require(view_model, marker, "MainViewModel")
for marker in ("queueCoordinator.requestStart", 'payload.optBoolean("policy_override", false)'):
    require(ui_broker, marker, "Go-routed queue host broker")
if "executionStarter.start" in view_model:
    errors.append("MainViewModel bypasses Go command ownership")

screens = read("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
for marker in (
    "QueueNetworkRequirement.entries",
    "QueueRetryStrategy.entries",
    "Maximum automatic retries",
    "Storage reserve (MB)",
    "Queue policy status",
    "recentDecisions.take(4)",
    "Start anyway",
    "Explicit override bypasses soft queue policy",
):
    require(screens, marker, "Compose queue UX")

scheduler_manifest = read("scheduler/src/main/AndroidManifest.xml")
require(scheduler_manifest, 'android.permission.ACCESS_NETWORK_STATE', "Scheduler manifest")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
for route in ("Downloads", "Add", "Media", "Library", "Activity", "Settings"):
    require(routes, f'{route}("{route}"', "AppRoute")
for forbidden in ("Browser(", "AppRoute.Browser", "android.webkit", "WebView(", "WebViewClient", "WebChromeClient"):
    if forbidden in routes + shell + model + coordinator + worker + screens:
        errors.append(f"Browser runtime token returned: {forbidden}")

for preserved in (
    "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
    "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
    "browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt",
):
    if not (ROOT / preserved).is_file():
        errors.append(f"Preserved downloader implementation missing: {preserved}")

build = read("app/build.gradle.kts")
if 'versionName = "0.21.0"' not in build or not re.search(r"versionCode\s*=\s*22\b", build):
    errors.append("Current successor app version must remain 0.21.0 / 22 while the Phase 8C manifest preserves its historical marker")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*25\b", database):
    errors.append("Current successor Room schema must remain 25 while the Phase 8C manifest preserves schema 14 historically")

for path in (
    "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/QueueIntelligenceTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/DownloaderExperiencePhase8CContractTest.kt",
    "docs/downloader/PHASE-8C-QUEUE-INTELLIGENCE.md",
):
    if not (ROOT / path).is_file():
        errors.append(f"Phase 8C contract path missing: {path}")

validator = "tools/validate-downloader-experience-phase-8c.py"
final_gate = read("tools/run-final-release-gate.sh")
phase64 = read("tools/validate-phase64-final-android-downloader-rc-seal.py")
workflow = read(".github/workflows/android.yml")
if validator not in final_gate and not ("validate-phase64-final-android-downloader-rc-seal.py" in final_gate and validator in phase64):
    errors.append("Final release gate must run the Phase 8C validator directly or through the Phase 64 seal")
require(workflow, validator, "Android CI")

if errors:
    print("Downloader experience Phase 8C validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Downloader experience Phase 8C validation passed: explainable queue policy, foreground automatic execution, retries, and overrides are sealed")
