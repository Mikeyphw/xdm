#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def text(rel: str) -> str:
    try:
        return (ROOT / rel).read_text()
    except Exception as exc:
        errors.append(f"cannot read {rel}: {exc}")
        return ""

def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

backend = text("transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt")
main = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
runtime = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
migration = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt")
handoff = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt")
envelope = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/SecureRequestEnvelopeStore.kt")
planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
exec_lib = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
workspace = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt")
inbox = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt")
locator = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
migrator = text("app/src/main/kotlin/com/mikeyphw/xdm/android/SensitivePersistenceMigrator.kt")
transfer_test = text("transfer-api/src/test/kotlin/com/mikeyphw/xdm/android/transfer/ExecutionSemanticsRepairTest.kt")
media_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionSemanticsRepairTest.kt")
legacy_media_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt")
scheduler_test = text("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/SecureRequestEnvelopeStoreTest.kt")
app_test = text("app/src/test/kotlin/com/mikeyphw/xdm/android/ExecutionMediaSemanticsRepairContractTest.kt")
post_dl03_test = text("app/src/test/kotlin/com/mikeyphw/xdm/android/PostDl03ReleaseFollowupContractTest.kt")
final_gate = text("tools/run-final-release-gate.sh")
manifest = json.loads(text("PROJECT_MANIFEST.json") or "{}")
entry = manifest.get("execution_media_semantics_repair", {})

need("val transferShape: MediaTransferShape" in backend, "DownloadRequest must carry explicit transferShape")
need("inferTransferShape(sourceUrl, mimeType)" in backend, "legacy-safe request construction must infer direct-media/adaptive shape from target")
need("val transferShape: MediaTransferShape = inferTransferShape(sourceUrl, mimeType)" in backend,
     "legacy media boolean must be ignored by default transfer-shape inference")
need("MediaTransferShape.DirectMedia ->" in backend and "Direct media request" in backend, "direct media must have a dedicated Native preference")
need("MediaTransferShape.AdaptivePlaylist -> if (!capability.supportsMediaPlaylists)" in backend, "playlist capability must apply only to adaptive playlists")
need("request.isMediaRequest && !capabilities.supportsMediaPlaylists" not in backend, "coordinator must not use legacy media boolean as playlist capability")
need("selectionPolicy.compatibilityIssue(request, capabilities)" in backend, "coordinator and preview must share one compatibility policy")
need("supportsMediaPlaylists = false" in backend, "default capability truth must not invent playlist support")

need("isMediaRequest = mediaHandoff != null" not in runtime, "runtime must not turn presence of a handoff into playlist semantics")
need("transferShape = mediaHandoff?.transferShape" in runtime, "runtime must restore durable transferShape")
need("isMediaRequest = handoff != null" not in migration, "backend migration must not turn handoff presence into playlist semantics")
need("transferShape = handoff?.transferShape" in migration, "backend migration must preserve transferShape")
need("val transferShape: MediaTransferShape" in handoff and "transferShape = transferShape" in handoff and "val transferShape: MediaTransferShape" in envelope, "encrypted request handoff must persist transferShape")
need("refreshedTransferShape(source.transferShape" in handoff and "refreshedTransferShape(it.transferShape, exactUrl)" in handoff,
     "clone/refresh must preserve specialized transfer shape across opaque signed URL replacement")
need('.put("transferShape", transferShape.name)' in envelope and "MediaTransferShape.valueOf" in envelope, "secure envelope JSON must round-trip transferShape")

need("shape == MediaTransferShape.DirectMedia || shape == MediaTransferShape.DirectFile -> MediaDownloadStrategy.Native" in planner,
     "progressive/direct media must use Native direct HTTP")
need("needsCookieContext = session.hasCredentialContext" in planner, "referer/User-Agent context must not masquerade as credential context")
need("requestHeaders = plan.sessionHandoff.requestHeaders()" in exec_lib and "transferShape = plan.transferShape" in exec_lib,
     "media execution queue must preserve captured headers and explicit transfer shape together")
need("ExternalUrlPolicy.hasCredentialBearingQuery(plan.primaryUrl)" in exec_lib, "media expiry must be based on URL lifetime, not ordinary replay headers")
need("variants.isEmpty() && plan.transferShape in setOf" in workspace and "MediaTransferShape.AdaptivePlaylist" in workspace,
     "empty variants must require resolution only for adaptive/site media")
need('"Direct"' in workspace and "canDownload = state == MediaConsumerState.Ready" in workspace,
     "direct progressive media must be represented as a ready downloadable resource")
need("record.isPlaylist && variants.isEmpty()" in inbox, "empty variants must fail resolution only for playlist records")

need("transferShape == MediaTransferShape.DirectFile || transferShape == MediaTransferShape.DirectMedia" in main,
     "Check media must short-circuit direct resources instead of invoking playlist resolution")
need('"Direct media is ready"' in main, "direct-media refresh action must report executable readiness")
need("resolutionStatus = MediaResolutionStatus.Failed" not in main, "backend/execution failures must not overwrite media resolver state")
need("candidate?.let(::transferShapeForCandidate) ?: inferTransferShape(url)" in main,
     "Add Download must classify target shape independently of browser headers")
need("transferShape = spec.transferShape" in main, "media queue admission must use the planner's exact transfer shape")

need("private object MediaLocatorRequestContextCache" in locator, "Live Locator must retain request replay context process-locally across recreation")
need('put("requestContextKey", requestContextKey)' in locator, "Live Locator Bundle may carry only a non-secret request-context key")
need('put("requestHeaders"' not in locator and 'put("headers"' not in locator.split("private fun encodeSavedCandidate",1)[-1].split("private fun restoreLocatorState",1)[0],
     "Live Locator saved-state JSON must not serialize raw request headers")
locator_saved = locator.split("private fun encodeSavedCandidate",1)[-1].split("private fun restoreLocatorState",1)[0]
need('put("url"' not in locator_saved and 'put("pageUrl"' not in locator_saved,
     "Live Locator saved-state JSON must not serialize exact source/page/variant URLs")
need("variantUrls = candidate.variants.associate" in locator and "requestContext.variantUrls[variantId]" in locator,
     "Live Locator exact variant URLs must remain process-local and reattach by id")
need("savedKind?.restoreMimeHint()" in locator and "kind = savedKind ?: base.kind" in locator,
     "Live Locator must restore saved semantic kind for opaque HLS/DASH candidates")
need("MediaLocatorRequestContextCache.get" in locator, "Live Locator restore must reattach process-local request context")
need("isExpiringUrl = candidate.requestHeaders.isNotEmpty()" not in locator, "Live Locator must not mark ordinary browser headers as URL expiry")
need("isExpiringUrl = headers.isNotEmpty()" not in migrator, "legacy sensitive migration must not turn replay headers into URL expiry")

need("browserRefererDoesNotTurnDirectFileIntoMediaWorkflow" in transfer_test, "golden test missing: browser direct file")
need("progressiveMp4WithCapturedContextIsNativeDirectHttpNotPlaylist" in transfer_test, "golden test missing: protected-context progressive MP4")
need("adaptivePlaylistStillRequiresPlaylistCapableExecution" in transfer_test, "golden test missing: adaptive playlist capability")
need("legacyMediaBooleanCannotPromoteDirectResourcesToPlaylistSemantics" in transfer_test,
     "golden test missing: legacy media boolean ignored by shape inference")
need("progressiveMp4IsReadyWithoutPlaylistVariants" in media_test, "golden test missing: direct MP4 ready without variants")
need("refererAndUserAgentDoNotClaimCredentialOrExpiryContext" in media_test, "golden test missing: replay context versus credentials/expiry")
need("cookieIsCredentialContextWithoutChangingDirectMediaShape" in media_test, "golden test missing: credentials remain orthogonal to transfer shape")
need("opaqueSpecializedShapeSurvivesCloneAndSignedUrlRefresh" in scheduler_test,
     "golden test missing: opaque specialized shape survives redownload/link refresh")
need("mediaEngineHardeningKeepsProgressiveReplayContextOnNativeLane" in legacy_media_test,
     "legacy media suite must carry progressive replay context forward on the Native lane")
need("directProgressiveMediaNeverSynthesizesAria2TransientFiles" in legacy_media_test,
     "legacy media suite must reject synthetic aria2 transient files for direct progressive media")
need("mediaEngineHardeningPlansAria2TransientInputAndUidtPolicy" not in legacy_media_test and
     "termuxRuntimeAdapterBuildsAria2TransientInputAndSessionCleanup" not in legacy_media_test,
     "pre-repair progressive-media aria2 expectations must not return")
need("directBrowserFileAndProgressiveMediaStayDirectAcrossLayers" in app_test, "app source contract missing cross-layer direct semantics")
need("liveLocatorRecreationKeepsSecretsOutOfBundleAndContextInProcess" in app_test, "app source contract missing locator lifecycle semantics")
need('requireNotNull(System.getProperty("user.dir"))' in app_test,
     "repair app contract must use a non-null user.dir before constructing java.io.File")
need("tools/validate-execution-media-semantics-repair.py" in post_dl03_test,
     "post-DL03 carry-forward contract must include the execution/media repair validator")
need("post-UX13 roadmap-completion hotfix is the current UI release authority" in post_dl03_test and
     "UX13 remains the end-to-end UI/UX baseline" in post_dl03_test and
     "Add/Media UX remodel remains a retained functional milestone" in post_dl03_test and
     "execution/media semantics repair remains its functional baseline" in post_dl03_test and
     "post-DL03 roadmap seal remains its historical baseline" in post_dl03_test,
     "post-DL03 carry-forward contract must recognize the post-UX13 hotfix as current authority while retaining UX13/Add-Media/execution provenance")
need('assertTrue(gate.contains("post-DL03 roadmap seal is the current final source of truth"))' not in post_dl03_test,
     "post-DL03 carry-forward contract must not positively require stale current-authority wording")
need("post-UX13 roadmap-completion hotfix is the current UI release authority" in final_gate and
     "UX13 remains the end-to-end UI/UX baseline" in final_gate and
     "Add/Media UX remodel remains a retained functional milestone" in final_gate and
     "execution/media semantics repair remains its functional baseline" in final_gate,
     "canonical final gate must expose the post-UX13 hotfix as current authority while retaining UX13/Add-Media/execution provenance")

need(entry.get("room_schema_current") == 21 and entry.get("room_schema_changed") is False,
     "execution/media repair must retain Room schema 21")
need(entry.get("browser_headers_are_media_identity") is False, "manifest must reject headers-as-media identity")
need(entry.get("progressive_media_backend") == "native_direct_http", "manifest must record progressive media execution path")
need(entry.get("execution_failure_mutates_resolution_state") is False, "manifest must separate execution and resolver failures")
need(entry.get("live_locator_bundle_contains_raw_headers") is False, "manifest must record no raw locator headers in Bundle state")
need(entry.get("legacy_media_suite_reconciled") is True, "manifest must record reconciliation of the historical media suite")
need(entry.get("progressive_media_aria2_expectations_retired") is True, "manifest must retire progressive-media aria2 expectations")
need(entry.get("validation_deferred") is False, "repair validation cannot be deferred")

if errors:
    print("Execution/media semantics repair validator failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("Execution/media semantics repair validator: OK")
