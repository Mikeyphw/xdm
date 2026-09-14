#!/usr/bin/env python3
"""Post-seal audit gate for FF01-FF04 production execution ownership."""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--skip-prerequisites", action="store_true", help="Skip prerequisite validators when the caller owns the validation DAG.")
args = parser.parse_args()

def text(path: str) -> str:
    p = ROOT / path
    if not p.is_file():
        errors.append(f"missing {path}")
        return ""
    return p.read_text(encoding="utf-8")

def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

def has(source: str, *needles: str) -> bool:
    return all(item in source for item in needles)

native = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")
vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
application = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
xdm_app = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
library_ui = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
dao = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/NativeHlsDao.kt")
hls_finalizer = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsFfmpegFinalizer.kt")
actions = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationModels.kt")
execution = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt")
termux_manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
need("private suspend fun embeddedToolVersionsJson(): String" in termux_manager, "embedded FFmpeg tool-version helper must be suspend before calling EmbeddedFfmpegRuntime.capabilities()")
automation = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt")
contract_test = text("app/src/test/kotlin/com/mikeyphw/xdm/android/FfmpegRoadmapPostSealHotfixContractTest.kt")
native_engine = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngine.kt")
native_engine_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngineTest.kt")
adaptive_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/AdaptiveMediaExecutionMc03Test.kt")
media_library = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
embedded_manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt")
publication = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PublicationSafety.kt")
destination_writer = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
publication_test = text("storage/src/test/kotlin/com/mikeyphw/xdm/android/storage/PublicationJournalCodecTest.kt")
adaptive_ff02_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/FfmpegAdaptiveExecutionFf02Test.kt")
planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
postseal_report = text("../../XDM_FFMPEG_ROADMAP_POSTSEAL_HOTFIX_REPORT.md")

need(has(native, "NativeHlsExecutionEngine()", "NativeHlsFfmpegFinalizer", "finalizer.finalize", "prepared.promote()"), "supported native HLS still lacks a production executor/finalizer/publication owner")
need("import com.mikeyphw.xdm.android.model.MediaVariantKind" in native, "NativeHlsMediaManager uses MediaVariantKind without the required model import")
need("import com.mikeyphw.xdm.android.model.MediaVariantKind" in media_library, "MediaExecutionLibrary uses MediaVariantKind without the required model import")
need(has(native, "AndroidTransferRequestSecurityGuard", "MediaRequestHandoffStore.forDownload", "filteredHeaders", "DownloadRequestApprovalScope.forUrl"), "native HLS execution does not reuse Android request-security/session boundaries")
need(has(native, "for (attempt in 0 until 3)", 'response.code == 206', 'AES/CBC/PKCS5Padding', "sha256File", "RecoveryRequired"), "native HLS execution lacks bounded retry/range/AES-128/integrity/recovery behavior")
need('catch (cancelled: CancellationException)' in native and 'throw cancelled' in native, "native HLS part retries may swallow coroutine cancellation instead of honoring Pause/Cancel")
need(has(native, "suspendCancellableCoroutine", "invokeOnCancellation { call.cancel() }", "worker?.cancel", "worker?.join()", "running.compute(row.downloadId)", "existing != null && !existing.isCompleted", "CoroutineStart.LAZY", "requestedControl[row.downloadId] != null"), "native HLS network/control cancellation or single-worker ownership is not prompt and race-safe")
need("persistableUrl(plan.manifestUrl)" in native and "persistableUrl(url)" in native, "native HLS durable rows may persist exact signed URLs")
need(has(native, "partEntityId(jobId, part.index)", "partIdentityMatches(current, part)", "row.mediaSequence == part.mediaSequence", "persistableMapIdentity(part.initMap)", "#xdm-map-range=", 'digest(key.toByteArray(Charsets.UTF_8))'), "native HLS Add-again/recovery can collide generations or reuse stale sequence/init-map-range parts")
need("key.substringAfterLast('/')" not in native, "native HLS temp storage still collapses unrelated jobs onto generation-only directories")
need(has(native, "val finalFileName = nativeHlsFinalFileName", "nativeHlsOutputMime(finalFileName, plan)", '"m4a" -> "audio/mp4"', '"mka" -> "audio/x-matroska"', '"mkv" -> "video/x-matroska"'), "native HLS final publication may retain playlist naming/MIME instead of a codec-compatible media container")
need(has(hls_finalizer, "requireVideo = plan.requireVideoStream", "requireAudio = plan.requireAudioStream") and "requireVideo = true" not in hls_finalizer, "native HLS FFprobe verification still hard-codes video and breaks audio-only HLS")
post_processor = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegPostProcessor.kt")
need("requireAnyStream = true" in post_processor, "native HLS FFprobe verification can still accept an unknown-shape artifact without any media stream")
need(has(native_engine, "MasterPlaylist", "SeparateRenditionMuxRequired", "EncryptedInitMap", '#EXT-X-STREAM-INF'), "native HLS support negotiation still claims structures that require the adaptive mux lane or unsupported encrypted init maps")
need(has(planner, "intent == MediaDownloadIntent.Subtitles -> MediaDownloadStrategy.YtDlp", "MediaManifestRole.HlsMaster", "nativeHlsEligible"), "subtitle/master HLS intent can still be misrouted into the simple native media-rendition executor")
need(has(media_library, "MediaDownloadStrategy.NativeHls", "nativeHlsAudioOnly", "nativeHlsAudioExtension", "embeddedAudioExtension", '".m4a" else ".mka"', '-> ".mkv"', "val baseBudget = (120 - extension.length)", "normalizedLeaf"), "embedded/native-HLS output naming does not force/preserve a codec-compatible final media container under long or path-like captured names")
need('"mka" -> "audio/x-matroska"' in native, "native HLS destination MIME does not match conservative non-AAC audio Matroska output")
need(has(native_engine_test, "audioOnlyVodHlsRequiresAudioInsteadOfHardCodingVideoVerification", "masterSeparateRenditionsAndEncryptedInitMapsFailClosedToFallback"), "native HLS capability/stream-shape regression tests are incomplete")
need(has(adaptive_test, "subtitleOnlyRequestNeverUsesNativeHlsMediaRendition", "nativeHlsUsesARealFinalMediaContainerInsteadOfManifestFilename"), "planner/output regression tests do not cover subtitle intent and manifest-filename finalization")
need("MediaNativeCapability.NativeHls" not in adaptive_test and "nativeCapability = MediaNativeCapability.NativeCandidate" in adaptive_test, "adaptive HLS regression conflates MediaNativeCapability with MediaDownloadStrategy.NativeHls and will not compile")
need(has(adaptive_ff02_test, "nonAacAudioOnlyUsesMatroskaInsteadOfForcingM4aStreamCopy"), "adaptive audio-only stream-copy regression does not cover non-AAC container compatibility")
need(has(vm, "enginePlan.lane == MediaExecutionLane.NativeHlsSegmented", "nativeHlsMediaManager.enqueue", "nativeHlsMediaManager.pause", "nativeHlsMediaManager.resume", "nativeHlsMediaManager.cancel"), "MainViewModel does not actually dispatch/control the native HLS owner")
need(has(application, "NativeHlsMediaManager(", "transferRuntime.recoverForStartup()", "val nativeHlsMediaManager: NativeHlsMediaManager") and "nativeHlsRecovery" in application and "runCatching { nativeHlsMediaManager.recoverInterruptedJobs() }" in application, "application startup does not order native HLS recovery after canonical publication-journal reconciliation")
need(has(native, "reconcileCommittedPublication(job)", "repository.finalizationForDownload(job.downloadId)", "FinalizationJournalStage.DestinationCommitted", "repository.saveFinalizationJournal", "completedArtifactSha256 = digest", "repository.deleteRecoveryForDownload(download.id)"), "native HLS cannot adopt an already committed publication after process death without duplicate remux/publication")
need(has(dao, "findByDownloadId", "findLatestByAdmissionKey", "activeJobs", "pausedJobs"), "native HLS DAO lacks execution/recovery queries")
need(has(hls_finalizer, "FfmpegPostProcessor", "finalizeNativeHls"), "native HLS finalizer no longer terminates in embedded FFmpeg")
need(has(embedded_manager, "PublicationJournalCodec.read", "committedPublicationProven", "withContext(NonCancellable)", "committedPromotion != null", "prepared.deleteArtifacts()"), "embedded adaptive/live FFmpeg publication is not crash-recoverable around the destination-commit boundary")
need(has(publication, "fun decode(text: String): PublicationCommitRecord", "fun read(file: File): PublicationCommitRecord"), "publication journal cannot be decoded for process-death recovery")
need(has(destination_writer, "suspend fun publicationCommitMatches", "PublicationCommitBoundary.DestinationCommitInProgress", "committedPublicationSize", "sha256File", "sha256Content", "stagedDigest == committedDigest"), "destination writer cannot safely adopt a commit-in-progress after exact-size requery")
need(has(publication_test, "committedJournalRoundTripsForCrashRecovery", "fileReadUsesTheSameDurableCodec"), "publication crash-recovery journal has no focused codec regression test")

for kind in ("FfprobeInspect", "RemuxFastStart", "ExtractAudio", "FfmpegRemux"):
    need(f'{kind}("' in actions and f'{kind}("' in actions and f'{kind}' in actions, f"missing post-processing action {kind}")
need('FfprobeInspect("FFprobe inspect", requiresTermux = false)' in actions, "ordinary FFprobe inspection still requires Termux")
need('RemuxFastStart("Fast-start MP4", requiresTermux = false)' in actions, "ordinary fast-start still requires Termux")
need('ExtractAudio("Extract audio", requiresTermux = false)' in actions, "ordinary audio extraction still requires Termux")
need('FfmpegRemux("FFmpeg remux", requiresTermux = false)' in actions, "ordinary remux still requires Termux")
need(has(execution, "externalFfmpegFallback", "spec.kind.requiresTermux || spec.externalFfmpegFallback"), "explicit external FFmpeg fallback cannot be distinguished from normal embedded work")
need(has(termux_manager, "runEmbeddedMediaAction", "embeddedPostProcessor", "embeddedFfmpegRuntime.probe", "embeddedLocalJobs"), "ordinary local FFmpeg/FFprobe actions are not executed by the embedded runtime")
need(has(termux_manager, "enqueueFfmpegFallback", "externalFfmpegFallback = true", "ffmpegFallbackReadinessIssue"), "FF03 explicit Termux FFmpeg fallback was weakened while closing embedded ownership")
need(has(automation, "PostProcessingActionKind.FfprobeInspect, PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.ExtractAudio, PostProcessingActionKind.FfmpegRemux -> emptySet()"), "completed-file automation still declares Termux FFmpeg/FFprobe prerequisites")
need(has(contract_test, "supportedNativeHlsHasARealAndroidExecutionOwner", "ordinaryLocalFfmpegWorkIsEmbeddedButExplicitFallbackStaysTermux", "embeddedAdaptiveRecoveryHasAnExecutableRetryPathWithoutExternalizingSecrets"), "post-seal production-wiring/recovery regression contract is missing")

retry_section = vm.split("fun retryEmbeddedFfmpegOutput(output: MediaOutputRecord)", 1)[1].split("fun removeMediaCapture", 1)[0] if "fun retryEmbeddedFfmpegOutput(output: MediaOutputRecord)" in vm else ""
need(has(vm, "fun retryEmbeddedFfmpegOutput(output: MediaOutputRecord)", "MediaRequestHandoffStore.forCapture(capture.id)", "MediaRequestHandoffStore.forVariant(variant.id)", "destinationUri = output.destinationUri", ".copy(fileName = output.fileName)", "MediaOutputAdmissionMode.AdditionalGeneration", "The capture no longer resolves to an embedded FFmpeg lane"), "embedded adaptive/live RecoveryRequired outputs still have no durable in-app retry reconstruction path")
need("termuxMediaPipelineManager" not in retry_section and "enqueueFfmpegFallback" not in retry_section, "embedded adaptive/live retry can silently externalize into Termux")
need("catch (cancelled: CancellationException)" in retry_section and "throw cancelled" in retry_section, "embedded adaptive/live retry swallows coroutine cancellation")
need("onRetryEmbeddedFfmpegOutput = viewModel::retryEmbeddedFfmpegOutput" in xdm_app, "XdmApp does not wire embedded FFmpeg Retry to the ViewModel")
embedded_retry_marker = "MediaOutputOwnerKind.EmbeddedFfmpeg -> outputs.firstOrNull { it.id == item.outputId }?.let(onRetryEmbeddedFfmpegOutput)"
need(library_ui.count(embedded_retry_marker) >= 3, "one or more Library embedded-FFmpeg Retry surfaces are still dead actions")
need(("DownloadState.Paused, DownloadState.RecoveryRequired, DownloadState.Failed -> nativeHlsMediaManager.resume(download.id)" in vm or "DownloadState.Paused, DownloadState.RecoveryRequired, DownloadState.Failed -> nativeHlsMediaManager.resume(current.id)" in vm), "native-HLS RecoveryRequired retry no longer returns to its durable native owner")

need(has(postseal_report, "Second-pass roadmap re-audit", "Literal handoff roadmap closure matrix", "Third-pass roadmap re-audit", "Fourth-pass roadmap re-audit", "Embedded adaptive/live publication crash recovery", "Init-map byte-range recovery identity", "dead Retry action", "No remaining source-level FF01–FF04 promise gap"), "post-seal report does not record the literal v2/v3/v4 roadmap closure audits")

# The strengthened FF02 validator is itself part of the closure: the original seal missed this exact gap.
ff02_source = text("tools/validate-ffmpeg02-media-mux-hls-postprocessing.py")
need("NativeHlsMediaManager.kt" in ff02_source and "runEmbeddedMediaAction" in ff02_source, "FF02 validator still allows class-without-production-caller false positives")

if not args.skip_prerequisites:
    for prerequisite in (
        "tools/validate-ffmpeg01-embedded-runtime-media-execution.py",
        "tools/validate-ffmpeg02-media-mux-hls-postprocessing.py",
        "tools/validate-ffmpeg03-runtime-routing-termux-ui-reliability.py",
        "tools/validate-execution-media-semantics-repair.py",
    ):
        result = subprocess.run([sys.executable, str(ROOT / prerequisite)], cwd=ROOT, text=True, capture_output=True)
        need(result.returncode == 0, f"prerequisite regressed: {prerequisite}: " + (result.stderr or result.stdout).strip())

if errors:
    print("FFmpeg roadmap post-seal hotfix contract FAILED", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("FFmpeg roadmap post-seal hotfix contract passed")
