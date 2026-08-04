#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"missing {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(relative: str, *needles: str) -> str:
    text = read(relative)
    for needle in needles:
        if needle not in text:
            ERRORS.append(f"{relative} missing {needle!r}")
    return text


def reject(relative: str, *needles: str) -> str:
    text = read(relative)
    for needle in needles:
        if needle in text:
            ERRORS.append(f"{relative} contains forbidden {needle!r}")
    return text

planner = require(
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt",
    "fun actionsFor(download: Download, context: DownloadActionContext",
    "DownloadState.Verifying",
    "details(primary = true)",
    "cancel(download, supporting =",
    "Start now",
    "This never routes through Pause",
    "Copy Android URI",
    "Delete file and download entry",
    "context.canMoveUp()",
    "context.canMoveDown()",
)
truth = require(
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt",
    "CompletedArtifactHealth",
    "Queue position $it of ${context.queueSize}",
    "context.verificationPassed() -> \"Verified and ready\"",
    "Download complete; verification not confirmed",
    "DownloadState.Downloading && download.speedBytesPerSecond > 0L",
    "Payload received; integrity verification in progress",
    "validatedPartialAvailable = validatedPartialAvailable && download.state in resumableStates",
    'it.startsWith("Queue policy:")',
)
manager = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/DownloadArtifactActions.kt",
    "withContext(Dispatchers.IO)",
    "DocumentsContract.renameDocument",
    "DocumentsContract.deleteDocument",
    "resolver.delete(sourceUri, null, null) == 1",
    "safeOwnedFile",
    "resolveContentProvider",
    "providerIdentityMatches",
    "locationBrowsable = false",
    "if (!capability.renameable)",
    "if (!capability.deletable)",
)
view_model = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
    "fun startNow(download: Download)",
    "queueIntelligenceCoordinator.requestStart",
    "fun deleteDownloadEntry",
    "fun deleteSavedFile",
    "downloadArtifactActionManager.delete(download)",
    "repository.deleteDownloadEntryIfTerminal",
    "atomic terminal-state check",
    "fun renameCompletedFile",
    "fun refreshDownloadLink",
    "MediaRequestHandoffStore.replaceDownloadUrl",
    "fun redownloadPreserving",
    "suspend fun inspectResumeCapability",
    "MediaRequestHandoffStore.cloneDownload",
    "repository.checksumExpectations(current.id)",
    "repository.clonePostProcessingJobsForRedownload(current.id, newId, now)",
    "repository.finalizationForDownload(current.id)",
    "destinationUri = originalDestination",
    "explicit post-processing job/rule record",
    "fun restartFromZero",
    "selectedRecoveryDownloadId = download.id",
    "selectedRecoveryAction = action.name",
)
row = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt",
    "DownloadUiTruthPlanner.truth(download, actionContext)",
    "DownloadActionPlanner.primaryActionFor(download, actionContext)",
    "download.state == DownloadState.Downloading && download.speedBytesPerSecond > 0L",
    "contentDescription = \"More actions for",
)
screen = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt",
    "onInspectArtifact",
    "onInspectResumeCapability",
    "durableResumeCapabilities",
    "onStartNow",
    "onRenameCompleted",
    "onRefreshLink",
    "onDeleteEntry",
    "onDeleteSavedFile",
    "onOpenRecovery",
    "DownloadActionKind.StartNow -> onStartNow(download)",
    "DownloadActionKind.DeleteFileAndRecord -> onDeleteSavedFile(download, true)",
    "copySensitiveTextToClipboard(context, \"XDM Android URI\"",
)
details = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt",
    "DownloadUiTruthPlanner.truth(download, actionContext)",
    "DownloadDetailRow(\"Payload bytes\"",
    "DownloadDetailRow(\"Overall completion\"",
    "DownloadDetailRow(\"Saved location\", actionContext.artifact.friendlyLocation)",
    "DownloadDetailRow(\"Provider\", actionContext.artifact.providerLabel)",
    "Copy redacted file information",
    "actionContext.postProcessingInputAvailable",
    "actionContext.backendMigrationAvailable",
)
workspace = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt",
    "Paused(\"Paused\")",
    "DownloadWorkspaceFilter.Paused -> download.state == DownloadState.Paused",
    "DownloadState.Verifying",
    "DownloadState.Repairing",
    "DownloadState.Finalizing",
    "downloads.filter { it.state == DownloadState.Downloading }",
)
recovery = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/recovery/RecoveryScreen.kt",
    "selectedDownloadId",
    "selectedAction",
    "records.sortedByDescending { it.downloadId == selectedDownloadId }",
)
require(
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt",
    "fun cloneDownload",
    "fun replaceDownloadUrl",
)
require(
    "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt",
    "suspend fun checksumExpectations",
    "suspend fun finalizationForDownload",
    "suspend fun hasDurableResumeEvidence",
    "suspend fun deleteDownloadEntryIfTerminal",
    "suspend fun clonePostProcessingJobsForRedownload",
    "rewritePostProcessingSpecForRedownload",
)
require(
    "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt",
    "deleteDownloadGraphIfTerminal",
    "expectedUpdatedAtEpochMs",
    "terminalStates",
    "findDownloadRowForGraphDeletion(downloadId) == null",
)
require(
    "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/PostProcessingDao.kt",
    "jobsForDownload",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt",
    "Waiting for the redownloaded artifact",
    "xdm://downloads/",
)
require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt",
    "Waiting for the redownloaded artifact",
)
require(
    "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlannerTest.kt",
    "verifyingAndRepairingNeverAdvertisePauseButAlwaysOfferCancel",
    "queuedStartNowIsDirectPrimaryAndMovementReflectsRealPosition",
    "completedActionsAreCapabilityAwareAndUseTruthfulLabels",
)
require(
    "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruthTest.kt",
    "completedIsVerifiedOnlyWithEvidence",
    "pausedItemNeverReceivesQueuePosition",
    "staleSpeedIsIgnoredOutsideDownloading",
    "resumeClaimRequiresDurableEvidenceFromPersistence",
    "verifyingSeparatesPayloadFromOverallCompletion",
)
require(
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase8DownloadActionsUiTruthfulnessContractTest.kt",
    "actionsUseRealCapabilitiesAndOffMainStorageBoundary",
    "listAndDetailsUseOneTruthModel",
)
require("docs/audits/BUG-HUNT-REMEDIATION-PHASE-8.md", "Phase 8", "DocumentsContract", "EXTRA_IS_SENSITIVE")

for relative, forbidden in (
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt", "contentResolver.delete("),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt", "Rename not available"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt", "Refresh link needs"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt", "Next in queue"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt", "Request data: Protected and redacted"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "fun startNow(download: Download) {\n        togglePause"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "appendLine(\"URL: $sourceUrl\")"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "appendLine(\"Destination: $destinationUri\")"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/common/UiTextHelpers.kt", "appendLine(\"URL: $sourceUrl\")"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/common/UiTextHelpers.kt", "appendLine(\"Destination: $destinationUri\")"),
):
    reject(relative, forbidden)

# Verifying/Repairing branch must end before pause is added.
state_branch = planner.split("DownloadState.Verifying,", 1)[-1].split("DownloadState.Queued,", 1)[0]
if "pause(" in state_branch:
    ERRORS.append("Verifying/Repairing branch still advertises Pause")

# Exact historical validators must remain green after the planner/context evolution.
for validator in (
    "tools/validate-phase-44-download-list-actions.py",
    "tools/validate-uix-r3-downloads-add-workspace.py",
    "tools/validate-phase61-final-gate-validator-harmony.py",
    "tools/validate-phase49-field-bugfix.py",
    "tools/validate-phase65-diagnostic-export-download-action-fix.py",
):
    result = subprocess.run([sys.executable, validator], cwd=ROOT, text=True, capture_output=True)
    if result.returncode != 0:
        ERRORS.append(f"{validator} failed after Phase 8 harmony: {result.stdout}{result.stderr}".strip())

for gate in ("tools/run-final-release-gate.sh", ".github/workflows/android.yml"):
    if "tools/validate-bug-hunt-phase8-download-actions-ui-truthfulness.py" not in read(gate):
        ERRORS.append(f"{gate} does not run the Phase 8 validator")

if ERRORS:
    print("Bug-hunt Phase 8 validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)

print("Bug-hunt Phase 8 download-actions and UI-truthfulness validation passed.")
