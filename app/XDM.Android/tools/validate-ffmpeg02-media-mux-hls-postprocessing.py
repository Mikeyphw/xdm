#!/usr/bin/env python3
"""FF02 contract: resolved adaptive media and post-processing are app-owned, verified, cancellable and atomic."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


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

models = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegRuntimeModels.kt")
compiler = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegCommandCompiler.kt")
launcher = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegProcessLauncher.kt")
progress = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegProgressParser.kt")
verifier = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegMediaVerifier.kt")
post = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegPostProcessor.kt")
need(has(models, "MuxRemoteTracks", "ExtractRemoteAudio", "FinalizeAdaptive", "FinalizeHlsSegments", "MuxTracks", "AttachSubtitle", "ExtractAudio", "FastStart"), "FF02 typed FFmpeg operation surface is incomplete")
need(has(models, "FfmpegProgressSnapshot", "FfmpegVerificationExpectation", "FfmpegVerificationReport", "VerificationFailed"), "FF02 progress/verification models are incomplete")
need(has(compiler, '"-progress", "pipe:1"', '"-nostats"', '"-c", "copy"', '"-tls_verify", "1"', '"-ca_file"'), "FF02 compiler must emit stream-copy, machine progress, and verified HTTPS arguments")
need("ProcessBuilder" in launcher and "sh -c" not in launcher, "FF02 process execution must stay shell-free")
need(has(launcher, "currentCoroutineContext().ensureActive()", "process.destroy()", "process.destroyForcibly()", "AtomicReference"), "FF02 launcher lacks prompt coroutine cancellation/process teardown or race-safe progress")
need(has(progress, '"out_time_us"', '"total_size"', '"speed"', '"progress"', "expectedDurationMs", "percent"), "FF02 machine-readable progress parser is incomplete")
need(has(verifier, "minimumBytes", "requireVideo", "requireAudio", "requireSubtitle", "expectedDurationMs", "probe.videoStreams", "probe.audioStreams"), "FF02 FFprobe verification does not reject incomplete/wrong-stream artifacts")
need(has(post, "stagingFile", "FFprobe", "publishAtomically", "ATOMIC_MOVE", "FinalizeHlsSegments", "finally", "concat.delete()"), "FF02 local post-processing must stage, probe, atomically publish, and clean HLS manifests")

planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
execution = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
dispatch = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
worker = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt")
termux = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt")
native_direct = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaNativeDirectDownloadEngine.kt")
consumer = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt")
need(has(planner, "FfmpegAdaptive", "embeddedAdaptiveEligible", "MediaManifestRole.HlsMaster", "strategy == MediaDownloadStrategy.YtDlp"), "FF02 planner does not own resolved adaptive tracks while preserving unresolved yt-dlp fallback")
need("variants.any { it.kind in setOf(MediaVariantKind.Video, MediaVariantKind.Audio, MediaVariantKind.Subtitle) }" in planner, "HLS master renditions may still be misrouted into the simple native segment engine")
need(has(execution, "EmbeddedFfmpegAdaptive", "MediaSelectedTrackInput", "MediaPostProcessingPlan", "AdaptiveMux", "SubtitleMux", "AudioExtract", "intent: MediaDownloadIntent", '"--verify"', '"ffprobe"'), "FF02 execution spec does not carry typed selected-track/post-processing intent")
need(has(execution, "ffmpegNeedsContainer", '-> ".mkv"', "embeddedAudioExtension", 'return if (aacCompatible) ".m4a" else ".mka"', "val baseBudget = (120 - extension.length)", "normalizedLeaf"), "FF02 embedded FFmpeg outputs are not normalized to codec-safe stream-copy containers with extension-preserving filename bounds")
need(has(dispatch, "NeedsEmbeddedFfmpegRuntime", "LaunchEmbeddedFfmpeg", "FFprobe verification precedes publication", "EmbeddedFfmpegAdaptive"), "FF02 dispatch does not expose/gate adaptive embedded FFmpeg execution")
need("MediaWorkerBridgeKind.EmbeddedFfmpeg" in worker and "EmbeddedFfmpegAdaptive" in worker, "FF02 worker bridge does not assign adaptive jobs to the embedded runtime")
need("EmbeddedFfmpegAdaptive" in termux and "BlockedDiagnostic" in termux, "Termux adapter must refuse embedded adaptive ownership")
need("EmbeddedFfmpegAdaptive" in native_direct and "UnsupportedAdaptive" in native_direct, "native direct engine must not pretend to own adaptive FFmpeg jobs")
need(has(consumer, "processingNotice", "FfmpegAdaptive", "FFprobe", "combine the selected video and audio"), "Media consumer UI planner does not explain required processing before admission")

manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt")
hls_bridge = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsFfmpegFinalizer.kt")
hls_manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")
post_actions = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationModels.kt")
post_execution = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt")
post_manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
need("private suspend fun embeddedToolVersionsJson(): String" in post_manager, "embedded FFmpeg tool-version helper must be suspend before calling EmbeddedFfmpegRuntime.capabilities()")
post_automation = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt")
application = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
native_hls_dao = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/NativeHlsDao.kt")
vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
app_ui = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
library_ui = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
inbox = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
card = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
need(has(manager, "enqueueAdaptiveProcessing", "MuxRemoteTracks", "ExtractRemoteAudio", "runtime.execute(operation)", "runtime.probe", "FfmpegMediaVerifier.verify", "prepared.promote()", "CancellationException", "prepared.deleteArtifacts()", "PublicationJournalCodec.read", "committedPublicationProven", "withContext(NonCancellable)", "committedPromotion != null"), "embedded FFmpeg manager lacks complete adaptive execute -> progress -> probe -> crash-safe atomic publication/cancellation flow")
need(has(manager, "MutableStateFlow", "EmbeddedFfmpegJobProgress", "Processing", "Verifying", "Publishing", "Completed"), "embedded FFmpeg lifecycle/progress states are incomplete")
need(has(hls_bridge, "NativeHlsManifestPlan", "FfmpegPostProcessor", "finalizeNativeHls", "completedSegmentFiles"), "native HLS has no bridge into verified embedded FFmpeg finalization")
need(has(hls_manager, "NativeHlsExecutionEngine()", "NativeHlsFfmpegFinalizer", "finalizer.finalize", "prepared.promote()", "AndroidTransferRequestSecurityGuard", "MediaRequestHandoffStore.forDownload", "AES/CBC/PKCS5Padding", "response.code == 206", "for (attempt in 0 until 3)"), "native HLS finalizer exists but still has no complete production execution owner")
need(has(hls_manager, "suspendCancellableCoroutine", "invokeOnCancellation { call.cancel() }", "partEntityId(jobId, part.index)", "partIdentityMatches(current, part)", "persistableMapIdentity(part.initMap)", "#xdm-map-range=", "nativeHlsOutputMime(finalFileName, plan)", "worker?.join()", "running.compute(row.downloadId)", "CoroutineStart.LAZY", 'digest(key.toByteArray(Charsets.UTF_8))'), "native HLS owner lacks prompt cancellation, atomic single-worker ownership, generation/map-range-safe recovery, or final media MIME/container ownership")
need("key.substringAfterLast('/')" not in hls_manager, "native HLS temp directories still collide across unrelated jobs sharing the same generation")
native_hls_engine = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngine.kt")
native_hls_tests = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngineTest.kt")
need(has(native_hls_engine, "MasterPlaylist", "SeparateRenditionMuxRequired", "EncryptedInitMap", "requireVideoStream", "requireAudioStream"), "native HLS capability negotiation/stream-shape truth is incomplete")
need(has(hls_bridge, "requireVideo = plan.requireVideoStream", "requireAudio = plan.requireAudioStream") and "requireVideo = true" not in hls_bridge, "native HLS finalizer still assumes every supported playlist contains video")
need(has(native_hls_tests, "audioOnlyVodHlsRequiresAudioInsteadOfHardCodingVideoVerification", "masterSeparateRenditionsAndEncryptedInitMapsFailClosedToFallback"), "native HLS audio-only/fallback capability regressions are not covered")
need(has(vm, "enginePlan.lane == MediaExecutionLane.NativeHlsSegmented", "nativeHlsMediaManager.enqueue", "nativeHlsMediaManager.pause", "nativeHlsMediaManager.resume", "nativeHlsMediaManager.cancel"), "ViewModel does not dispatch/control the production native HLS executor")
need(has(application, "NativeHlsMediaManager(", "val recovery = transferRuntime.recoverForStartup()", "val nativeHlsRecovery = runCatching { nativeHlsMediaManager.recoverInterruptedJobs() }"), "application startup does not order native HLS recovery after publication-journal reconciliation")
need(has(hls_manager, "reconcileCommittedPublication(job)", "repository.finalizationForDownload(job.downloadId)", "FinalizationJournalStage.DestinationCommitted", "repository.saveFinalizationJournal", "completedArtifactSha256 = digest"), "native HLS publication recovery can duplicate a destination committed before Room completion metadata")
need(has(native_hls_dao, "findByDownloadId", "findLatestByAdmissionKey", "activeJobs", "pausedJobs"), "native HLS DAO lacks production recovery/control queries")
need('FfprobeInspect("FFprobe inspect", requiresTermux = false)' in post_actions and 'RemuxFastStart("Fast-start MP4", requiresTermux = false)' in post_actions and 'ExtractAudio("Extract audio", requiresTermux = false)' in post_actions and 'FfmpegRemux("FFmpeg remux", requiresTermux = false)' in post_actions, "ordinary FFmpeg/FFprobe post-processing still declares a Termux requirement")
need(has(post_execution, "externalFfmpegFallback", "spec.kind.requiresTermux || spec.externalFfmpegFallback"), "FF03 explicit external fallback is not durably separated from normal embedded post-processing")
need(has(post_manager, "runEmbeddedMediaAction", "embeddedPostProcessor", "embeddedFfmpegRuntime.probe", "externalFfmpegFallback = true", "ffmpegFallbackReadinessIssue"), "post-processing does not route normal local FFmpeg/FFprobe work embedded-first while preserving explicit Termux fallback")
need(has(post_automation, "PostProcessingActionKind.FfprobeInspect, PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.ExtractAudio, PostProcessingActionKind.FfmpegRemux -> emptySet()"), "completed-file automation still requires external FFmpeg/FFprobe tools")
need(has(vm, "embeddedFfmpegProgress", "cancelEmbeddedFfmpegOutput", "enqueueAdaptiveProcessing", "EmbeddedFfmpegAdaptive"), "ViewModel does not expose adaptive progress/cancel/execute integration")
need(has(app_ui, "embeddedFfmpegProgress = state.embeddedFfmpegProgress", "onCancelEmbeddedFfmpeg = viewModel::cancelEmbeddedFfmpegOutput"), "app surface does not wire FFmpeg progress/cancellation")
need(has(inbox, "embeddedFfmpegProgress", "onCancelEmbeddedFfmpeg"), "Media inbox does not route embedded FFmpeg progress/cancellation to cards")
need(has(card, "processingNotice", "embeddedFfmpegProgress", 'TextButton(onClick = { onCancelEmbeddedFfmpeg(latestOutput) })'), "Media card does not show processing expectations/live progress/cancel")
retry_section = vm.split("fun retryEmbeddedFfmpegOutput(output: MediaOutputRecord)", 1)[1].split("fun removeMediaCapture", 1)[0] if "fun retryEmbeddedFfmpegOutput(output: MediaOutputRecord)" in vm else ""
need(has(vm, "fun retryEmbeddedFfmpegOutput(output: MediaOutputRecord)", "MediaRequestHandoffStore.forCapture(capture.id)", "MediaRequestHandoffStore.forVariant(variant.id)", "destinationUri = output.destinationUri", ".copy(fileName = output.fileName)", "MediaOutputAdmissionMode.AdditionalGeneration"), "FF02 RecoveryRequired/failed embedded adaptive outputs have no executable in-app retry reconstruction")
need("termuxMediaPipelineManager" not in retry_section and "enqueueFfmpegFallback" not in retry_section, "FF02 embedded retry can silently escape into the Termux fallback lane")
need("catch (cancelled: CancellationException)" in retry_section and "throw cancelled" in retry_section, "embedded adaptive/live retry swallows coroutine cancellation")
need("onRetryEmbeddedFfmpegOutput = viewModel::retryEmbeddedFfmpegOutput" in app_ui, "app does not wire embedded FFmpeg Retry")
retry_marker = "MediaOutputOwnerKind.EmbeddedFfmpeg -> outputs.firstOrNull { it.id == item.outputId }?.let(onRetryEmbeddedFfmpegOutput)"
need(library_ui.count(retry_marker) >= 3, "Library still renders one or more dead Retry actions for EmbeddedFfmpeg output generations")
need("DownloadState.Paused, DownloadState.RecoveryRequired, DownloadState.Failed -> nativeHlsMediaManager.resume(download.id)" in vm, "native-HLS RecoveryRequired retry is not intercepted by its durable native owner")

command_tests = text("media-ffmpeg/src/test/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegCommandCompilerTest.kt")
progress_tests = text("media-ffmpeg/src/test/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegProgressParserTest.kt")
verify_tests = text("media-ffmpeg/src/test/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegMediaVerifierTest.kt")
adaptive_tests = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/FfmpegAdaptiveExecutionFf02Test.kt")
publication = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PublicationSafety.kt")
destination_writer = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
publication_tests = text("storage/src/test/kotlin/com/mikeyphw/xdm/android/storage/PublicationJournalCodecTest.kt")
need(has(command_tests, "remoteMuxUsesTypedInputsProgressAndStreamCopy", "muxTracksMapsVideoAndAudioWithoutTranscoding"), "FF02 command compiler regressions are not covered")
need(has(progress_tests, "progress", "out_time", "percent"), "FF02 progress parser has no focused regression coverage")
need(has(verify_tests, "requireVideo", "requireAudio", "minimumBytes"), "FF02 media verifier has no focused regression coverage")
need(has(adaptive_tests, "selectedDashVideoAndAudioBecomeVerifiedEmbeddedMuxPlan", "audioOnlyIntentIsPreservedThroughAdmissionAndUsesAudioContainer", "nonAacAudioOnlyUsesMatroskaInsteadOfForcingM4aStreamCopy", "incompleteBestVideoSelectionKeepsResolverFallback", "embeddedAdaptiveDispatchBlocksWhenRuntimeIsUnavailable"), "FF02 adaptive planner/dispatcher/container tests are incomplete")
mc03_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/AdaptiveMediaExecutionMc03Test.kt")
need("MediaNativeCapability.NativeHls" not in mc03_test and "nativeCapability = MediaNativeCapability.NativeCandidate" in mc03_test, "FF02 MC03 regression uses nonexistent MediaNativeCapability.NativeHls instead of NativeCandidate")
need(has(publication, "fun decode(text: String): PublicationCommitRecord", "fun read(file: File): PublicationCommitRecord"), "FF02 publication journal is write-only and cannot support crash reconciliation")
need(has(destination_writer, "suspend fun publicationCommitMatches", "PublicationCommitBoundary.DestinationCommitInProgress", "committedPublicationSize", "sha256File", "sha256Content", "stagedDigest == committedDigest"), "FF02 destination publication cannot prove/adopt a provider commit after process death")
need(has(publication_tests, "committedJournalRoundTripsForCrashRecovery", "fileReadUsesTheSameDurableCodec"), "FF02 publication journal crash-recovery codec lacks regression coverage")

# Preserve FF01 as a strict prerequisite; FF02 must not regress the runtime foundation.
ff01 = subprocess.run([sys.executable, str(ROOT / "tools/validate-ffmpeg01-embedded-runtime-media-execution.py")], cwd=ROOT, text=True, capture_output=True)
need(ff01.returncode == 0, "FF01 prerequisite contract regressed: " + (ff01.stderr or ff01.stdout).strip())

# Post-seal FF02 execution-ownership hardening.
manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")
processor = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegPostProcessor.kt")
library = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
need("existing != null && !existing.isCompleted" in manager, "native HLS launch ownership can replace a reserved lazy worker")
need("import com.mikeyphw.xdm.android.model.MediaVariantKind" in manager, "native HLS manager audio-container branch is missing MediaVariantKind import")
need("import com.mikeyphw.xdm.android.model.MediaVariantKind" in library, "media execution audio-container branch is missing MediaVariantKind import")
need("requireAnyStream = true" in processor, "native HLS verification must require at least one FFprobe media stream")
need("nativeHlsAudioExtension" in library and '".m4a" else ".mka"' in library and "embeddedAudioExtension" in library, "embedded/native-HLS audio container selection must avoid forcing non-AAC codecs into M4A")
need("persistableMapIdentity(part.initMap)" in manager and "#xdm-map-range=" in manager, "native HLS refresh recovery ignores init-map byte-range identity")

if errors:
    print("FF02 media mux/HLS/post-processing contract FAILED", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("FF02 media mux/HLS/post-processing contract passed")
