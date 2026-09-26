#!/usr/bin/env python3
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
ARTIFACT = Path(__file__).resolve().parents[2] / ".devtool-artifact.json"

def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise AssertionError(f"Missing required file: {rel}")
    return path.read_text(encoding="utf-8")

def require(text: str, needle: str, label: str):
    if needle not in text:
        raise AssertionError(f"Missing {label}: {needle}")

def reject(text: str, needle: str, label: str):
    if needle in text:
        raise AssertionError(f"Forbidden {label}: {needle}")

model = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueStateMachineModels.kt")
model_test = read("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/QueueStateMachineModelsTest.kt")
coordinator = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt")
coordinator_test = read("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinatorTest.kt")
worker = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
queue_coordinator = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
foreground_service = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
job = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt")
notifications = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
terminal_policy = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt")
receiver = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferActionReceiver.kt")
view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
execution_broker = read("app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidDownloadExecutionBroker.kt")
application = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase4QueueSchedulingStateMachinesContractTest.kt")
doc = read("docs/audits/BUG-HUNT-REMEDIATION-PHASE-4.md")
manifest = json.loads(read("PROJECT_MANIFEST.json"))

for needle, label in [
    ("QueueConditionScope", "condition scope classification"),
    ("QueueDrainPolicy", "active drain policy"),
    ("QueueBudget", "global concurrency/bandwidth budget"),
    ("reserveSlotAtomically", "atomic slot reservation"),
    ("planPauseAll", "durable pause all plan"),
    ("blocksNewStarts = true", "pause all blocks new starts"),
    ("DownloadState.Finalizing", "finalizing covered by pause all"),
    ("DownloadState.Verifying", "verifying covered by pause all"),
    ("DownloadState.Repairing", "repairing covered by pause all"),
    ("validateScheduleWindow", "fail-closed schedule validator"),
    ("preciseShortWindowRequired", "precise short-window flag"),
    ("failureGeneration", "retry deadline generation binding"),
    ("QueueDeletionDisposition", "queue deletion anti-dangling plan"),
    ("SystemStopReasonRecord", "system stop reason model"),
    ("pendingJobReasons", "Android 16 pending-job reasons model"),
    ("pendingJobReasonHistory", "Android 16 pending-job reason history model"),
    ("RecoveryOperationOutcome", "typed recovery outcomes"),
    ("RecoveryArtifactIdentity", "artifact identity recovery record"),
    ("TerminalNotificationKey", "idempotent terminal notification key"),
    ("NotificationPermissionState", "notification permission denial state"),
]:
    require(model, needle, label)

for needle, label in [
    ("QueueSchedulingRecoveryStore", "durable coordinator store"),
    ("saveImmediateReevaluation", "durable reevaluation store"),
    ("coalesceKey", "reevaluation coalescing"),
    ("if (terminalNotifications.containsKey(record.idempotencyKey)) return false", "terminal notification idempotency"),
    ("TransferExecutionStopReasonRecorder", "stop reason recorder"),
]:
    require(coordinator, needle, label)


for needle, label in [
    ("FileBackedQueueSchedulingRecoveryStore", "file-backed durable Phase 4 store"),
    ("out.fd.sync()", "fsynced durable Phase 4 writes"),
    ("QueueSchedulingRecoveryProvider", "production Phase 4 provider"),
    ("saveQueueReservation", "persistent queue-slot reservations"),
    ("terminalIfFirst", "terminal notification idempotency wiring"),
]:
    require(coordinator + notifications + application, needle, label)


# XGO-75 removes Kotlin queue admission/retry/execution authority while retaining
# historical queue data models and metadata-only maintenance surfaces.
require(queue_coordinator, "AndroidGoDownloadCommands.submit", "queue compatibility facade submits user intent to Go")
require(queue_coordinator, "AndroidSchedulerHost.wake(", "queue reconcile only wakes Go")
require(queue_coordinator, "deleteQueueSafely", "queue deletion anti-dangling metadata path remains")
for forbidden, label in [
    ("QueueIntelligencePlanner", "Kotlin queue ranking planner"),
    ("QueueRetryLedger", "Kotlin retry authority"),
    ("reserveSlotAtomically", "Kotlin slot claiming"),
    ("executionStarter.start(", "Kotlin execution start"),
]:
    reject(queue_coordinator, forbidden, label)
require(foreground_service, 'submitGoCommand("pause_all")', "foreground Pause All crosses Go command authority")
require(worker, "AndroidSchedulerHost.wake(", "WorkManager hosts/wakes Go scheduler authority")
require(worker, "ExistingWorkPolicy.KEEP", "WorkManager duplicate wake suppression")
reject(worker, "coordinator.evaluateAndClaim()", "WorkManager Kotlin eligibility authority after XGO-71")
reject(worker, "runtime.execute(", "WorkManager Kotlin execution authority after XGO-71")
reject(worker, "runtime.pauseOwned(", "WorkManager exact-owner mutation after XGO-71")
require(receiver, 'TransferNotifications.ACTION_PAUSE_ALL -> "pause_all"', "broadcast Pause All maps to Go command")
require(receiver, "AndroidGoDownloadCommands.submit(", "broadcast actions cross Go command authority")
require(view_model, 'action = "pause_all"', "UI Pause All crosses Go command boundary")
require(execution_broker, "transferRuntime.pauseAll()", "Go-routed Pause All reaches the narrow Android execution adapter")
require(execution_broker, "nativeHls.pauseAll()", "Go-routed Pause All reaches native HLS platform owner through the narrow adapter")
require(view_model, "deleteQueueSafely(queue.id)", "UI queue delete goes through anti-dangling plan")
require(notifications, "notificationPermissionState()", "notification permission denial runtime wiring")
require(notifications, "recordTerminalNotification(record)", "terminal idempotency runtime dispatch gate")
require(application, "FileBackedQueueSchedulingRecoveryStore(File(filesDir", "app uses file-backed Phase 4 store")
require(application, "TransferExecutionStopReasonRecorder.installPersistentRoot", "stop reason recorder persistence root")

reject(worker, "getStopReason()", "WorkManager execution stop-reason ownership after Go host cutover")
require(coordinator, "TransferExecutionStopReasonRecorder", "owner-side durable stop reason recorder remains available")
require(job, "AndroidSchedulerHost.wake(", "UIDT job hosts/wakes Go scheduler authority")
reject(job, "params.stopReason", "UIDT Kotlin execution stop-reason ownership after Go host cutover")
require(job, "No Kotlin transfer owner exists to pause", "UIDT stop callback documents host-only ownership")
require(notifications + terminal_policy, "Review recovery", "RecoveryRequired notification review action")
require(notifications, "Dismiss", "dismiss copy")
require(receiver, "ACTION_REVIEW_RECOVERY", "review recovery action routing")
require(receiver, "ACTION_DISMISS", "dismiss action routing")
reject(notifications, "DownloadState.RecoveryRequired -> addAction(android.R.drawable.ic_popup_sync, \"Retry\"", "blind recovery retry action")

for text, label in [(model_test, "core model tests"), (coordinator_test, "scheduler coordinator tests"), (contract, "app contract tests"), (doc, "phase docs")]:
    require(text, "Phase 4", f"{label} phase marker")

phase = manifest.get("bug_hunt_remediation_phase_4", {})
if not phase.get("queue_scheduling_state_machines"):
    raise AssertionError("PROJECT_MANIFEST missing bug_hunt_remediation_phase_4.queue_scheduling_state_machines")
if not phase.get("commit_message"):
    raise AssertionError("PROJECT_MANIFEST missing phase 4 commit message mirror")

if ARTIFACT.is_file():
    meta = json.loads(ARTIFACT.read_text(encoding="utf-8"))
    if not meta.get("commit_message"):
        raise AssertionError("Artifact metadata missing commit_message")
    validation = meta.get("validation", {})
    if validation.get("mode") not in {"disabled", "required", "optional", "normal"}:
        raise AssertionError("Artifact metadata has invalid validation.mode")

print("Phase 4 r2 queue/scheduling/state-machine runtime-wiring validator passed")
