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
need(has(execution, "ffmpegNeedsContainer", '-> ".mkv"', '-> ".m4a"'), "FF02 embedded FFmpeg outputs are not normalized to safe stream-copy containers")
need(has(dispatch, "NeedsEmbeddedFfmpegRuntime", "LaunchEmbeddedFfmpeg", "FFprobe verification precedes publication", "EmbeddedFfmpegAdaptive"), "FF02 dispatch does not expose/gate adaptive embedded FFmpeg execution")
need("MediaWorkerBridgeKind.EmbeddedFfmpeg" in worker and "EmbeddedFfmpegAdaptive" in worker, "FF02 worker bridge does not assign adaptive jobs to the embedded runtime")
need("EmbeddedFfmpegAdaptive" in termux and "BlockedDiagnostic" in termux, "Termux adapter must refuse embedded adaptive ownership")
need("EmbeddedFfmpegAdaptive" in native_direct and "UnsupportedAdaptive" in native_direct, "native direct engine must not pretend to own adaptive FFmpeg jobs")
need(has(consumer, "processingNotice", "FfmpegAdaptive", "FFprobe", "combine the selected video and audio"), "Media consumer UI planner does not explain required processing before admission")

manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt")
hls_bridge = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsFfmpegFinalizer.kt")
vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
app_ui = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
inbox = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
card = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
need(has(manager, "enqueueAdaptiveProcessing", "MuxRemoteTracks", "ExtractRemoteAudio", "runtime.execute(operation)", "runtime.probe", "FfmpegMediaVerifier.verify", "prepared.promote()", "CancellationException", "prepared.deleteArtifacts()"), "embedded FFmpeg manager lacks complete adaptive execute -> progress -> probe -> atomic publication/cancellation flow")
need(has(manager, "MutableStateFlow", "EmbeddedFfmpegJobProgress", "Processing", "Verifying", "Publishing", "Completed"), "embedded FFmpeg lifecycle/progress states are incomplete")
need(has(hls_bridge, "NativeHlsManifestPlan", "FfmpegPostProcessor", "finalizeNativeHls", "completedSegmentFiles"), "native HLS has no bridge into verified embedded FFmpeg finalization")
need(has(vm, "embeddedFfmpegProgress", "cancelEmbeddedFfmpegOutput", "enqueueAdaptiveProcessing", "EmbeddedFfmpegAdaptive"), "ViewModel does not expose adaptive progress/cancel/execute integration")
need(has(app_ui, "embeddedFfmpegProgress = state.embeddedFfmpegProgress", "onCancelEmbeddedFfmpeg = viewModel::cancelEmbeddedFfmpegOutput"), "app surface does not wire FFmpeg progress/cancellation")
need(has(inbox, "embeddedFfmpegProgress", "onCancelEmbeddedFfmpeg"), "Media inbox does not route embedded FFmpeg progress/cancellation to cards")
need(has(card, "processingNotice", "embeddedFfmpegProgress", 'TextButton(onClick = { onCancelEmbeddedFfmpeg(latestOutput) })'), "Media card does not show processing expectations/live progress/cancel")

command_tests = text("media-ffmpeg/src/test/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegCommandCompilerTest.kt")
progress_tests = text("media-ffmpeg/src/test/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegProgressParserTest.kt")
verify_tests = text("media-ffmpeg/src/test/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegMediaVerifierTest.kt")
adaptive_tests = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/FfmpegAdaptiveExecutionFf02Test.kt")
need(has(command_tests, "remoteMuxUsesTypedInputsProgressAndStreamCopy", "muxTracksMapsVideoAndAudioWithoutTranscoding"), "FF02 command compiler regressions are not covered")
need(has(progress_tests, "progress", "out_time", "percent"), "FF02 progress parser has no focused regression coverage")
need(has(verify_tests, "requireVideo", "requireAudio", "minimumBytes"), "FF02 media verifier has no focused regression coverage")
need(has(adaptive_tests, "selectedDashVideoAndAudioBecomeVerifiedEmbeddedMuxPlan", "audioOnlyIntentIsPreservedThroughAdmissionAndUsesAudioContainer", "incompleteBestVideoSelectionKeepsResolverFallback", "embeddedAdaptiveDispatchBlocksWhenRuntimeIsUnavailable"), "FF02 adaptive planner/dispatcher tests are incomplete")

# Preserve FF01 as a strict prerequisite; FF02 must not regress the runtime foundation.
ff01 = subprocess.run([sys.executable, str(ROOT / "tools/validate-ffmpeg01-embedded-runtime-media-execution.py")], cwd=ROOT, text=True, capture_output=True)
need(ff01.returncode == 0, "FF01 prerequisite contract regressed: " + (ff01.stderr or ff01.stdout).strip())

if errors:
    print("FF02 media mux/HLS/post-processing contract FAILED", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("FF02 media mux/HLS/post-processing contract passed")
