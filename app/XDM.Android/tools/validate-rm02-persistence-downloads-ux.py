#!/usr/bin/env python3
"""RM02 static seal: media persistence integrity + truthful Downloads/notification UX."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]

def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise AssertionError(f"missing RM02 source: {rel}")
    return path.read_text(encoding="utf-8")

def need(h: str, n: str, label: str) -> None:
    if n not in h:
        raise AssertionError(f"RM02 missing {label}: {n!r}")

def ordered(h: str, needles: list[str], label: str) -> None:
    cursor = 0
    for needle in needles:
        pos = h.find(needle, cursor)
        if pos < 0:
            raise AssertionError(f"RM02 missing ordered marker for {label}: {needle!r}")
        cursor = pos + len(needle)

def main() -> int:
    media = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt")
    graph = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt")
    repo = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
    main = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
    debug = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt")
    presentation = text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadPresentationPolicy.kt")
    truth = text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt")
    planner = text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt")
    row = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt")
    destination = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDestinationUi.kt")
    media_ui = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
    notif = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
    notif_policy = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt")

    need(repo, "database.withTransaction", "Room transactional admission")
    need(repo, "Cannot link media capture to a missing Download row", "legacy link guard")
    need(media, "repairOrphanedDownloadLinks", "orphaned capture repair")
    need(media, "hideOrphanedAppDownloadOutputs", "orphaned output repair")
    ordered(graph, [
        "hideAppMediaOutputsForDownload(downloadId, mediaRevision)",
        "rebindMediaCapturesBeforeDownloadDeletion(downloadId, mediaRevision)",
        "deleteDownloadRow(downloadId)",
    ], "download deletion media reconciliation")
    need(main, "repository.repairMediaCaptureDownloadLinks()", "startup integrity repair")
    need(debug, "check(linkedDownload.state == DownloadState.Cancelled)", "debug transaction truthful probe state")
    if "check(linkedDownload.state == DownloadState.Queued)" in debug:
        raise AssertionError("RM02 debug transaction still expects a queued row despite constructing Cancelled")

    for needle, label in [
        ("isOpaqueIdentifierName", "opaque-id detection"),
        ("download-$hostBase", "host fallback name"),
        ("isFinalizationFailure", "finalization failure classification"),
        ("The file was transferred, but XDM couldn't finish saving it.", "friendly finalization copy"),
    ]:
        need(presentation, needle, label)
    need(main, "DownloadPresentationPolicy.resolvedFileName", "external/intake filename fallback")

    need(row, "quickActionsFor(download, actionContext).take(1)", "single visible quick action")
    need(row, "DownloadPresentationPolicy.displayName(download)", "human-facing title")
    need(row, "Text(quickAction.label)", "visible action label")
    need(row, "destinationCardLabel(download)", "state-aware destination wording")
    need(destination, '"Saved to $compactLabel"', "completed destination wording")
    need(destination, '"Destination: $compactLabel"', "non-completed destination wording")
    need(truth, '"Finalization failed"', "phase-specific status")
    need(truth, '"Needs attention"', "phase-specific badge")
    need(truth, "if (DownloadPresentationPolicy.isFinalizationFailure(download)) return null", "no misleading 100 percent bar")
    need(planner, '"Retry finalization"', "phase-specific recovery action")
    need(media_ui, '"Retry finalization"', "media parity finalization action")

    need(notif, '"Couldn\'t finish download"', "friendly notification title")
    need(notif, '"The file was transferred, but XDM couldn\'t finish saving it."', "friendly notification copy")
    need(notif, "record.actions", "durable notification action replay")
    need(notif_policy, '"Retry finalization"', "notification finalization action")
    need(notif_policy, "isRetryableFinalSaveMessage(message)", "message-aware terminal action policy")

    # Regression anchors.
    for rel, needle in [
        ("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadPresentationPolicyTest.kt", "opaqueUuidNeverBecomesPrimaryDisplayName"),
        ("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruthTest.kt", "finalSaveRecoveryShowsTransferCompleteWithoutMisleadingFullProgress"),
        ("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlannerTest.kt", "historicalPublicationFailureDoesNotOfferNetworkRetryAsPrimaryAction"),
        ("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotificationPolicyContractTest.kt", "finalizationFailureUsesFriendlyCopyAndPhaseSpecificAction"),
        ("app/src/test/kotlin/com/mikeyphw/xdm/android/Rm02PersistenceDownloadsUxContractTest.kt", "mediaCaptureLinksAreTransactionalAndDeletionSafe"),
    ]:
        need(text(rel), needle, f"regression anchor {needle}")

    manifest = json.loads(text("PROJECT_MANIFEST.json"))
    rm02 = manifest.get("rm02_persistence_downloads_ux")
    if not isinstance(rm02, dict) or rm02.get("status") != "implemented":
        raise AssertionError("PROJECT_MANIFEST.json must mark rm02_persistence_downloads_ux implemented")
    for key in (
        "media_capture_download_integrity",
        "orphan_link_startup_repair",
        "deletion_rebind_before_fk_null",
        "human_readable_download_identity",
        "single_visible_card_recovery_action",
        "finalization_failure_truthful_ui",
        "phase_specific_notification_recovery",
    ):
        if rm02.get(key) is not True:
            raise AssertionError(f"RM02 manifest invariant is not sealed: {key}")

    print("RM02 persistence/downloads UX contract passed: media links, deletion repair, human identity, finalization semantics, card actions, and notifications are sealed")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
