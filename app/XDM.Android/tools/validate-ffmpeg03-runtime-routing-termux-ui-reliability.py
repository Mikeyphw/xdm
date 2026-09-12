#!/usr/bin/env python3
"""FF03 contract: FFmpeg routing is explicit, secret-safe, observable, and reliability-complete."""
from __future__ import annotations
import argparse
import subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--skip-prerequisites", action="store_true", help="Skip prerequisite validators when the caller owns the validation DAG.")
args = parser.parse_args()
def text(path: str) -> str:
    p = ROOT / path
    if not p.is_file(): errors.append(f"missing {path}"); return ""
    return p.read_text(encoding="utf-8")
def need(cond: bool, msg: str):
    if not cond: errors.append(msg)
def has(src: str, *needles: str) -> bool: return all(n in src for n in needles)

routing = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaRuntimeRouting.kt")
execution = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
dispatch = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
worker = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt")
termux_adapter = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt")
need(has(routing, "Automatic", "Embedded FFmpeg", "Termux FFmpeg", "termuxFallbackEligible", "hasCredentialBearingQuery", "requiresPrivateNetworkApproval"), "runtime policy lacks full automatic/embedded/Termux and secret/private-network boundary")
need(has(routing, "context.embeddedReady", "context.termuxReady && context.termuxNetworkFallbackEligible", "signed media session cannot safely cross into Termux"), "Automatic routing is not embedded-first/fail-closed")
need(has(execution, "TermuxFfmpegAdaptive", "TermuxFfmpegLive", "ffmpegRuntimeDecision", '"termux-ffmpeg"', '"--session-safe"'), "execution planner does not own the external FFmpeg fallback lanes")
need(has(dispatch, "termuxFfmpegReady", "NeedsTermuxSetup", "LaunchTermuxJob", "session-safe public inputs only"), "dispatch does not gate/explain Termux FFmpeg fallback")
need(has(worker, "TermuxFfmpeg", "TermuxFfmpegAdaptive", "TermuxFfmpegLive"), "worker bridge does not classify Termux FFmpeg jobs")
need(has(termux_adapter, "FfmpegAdaptive", "FfmpegLive", "TermuxMediaRuntimeTool.Ffmpeg", "TermuxMediaRuntimeTool.Ffprobe"), "typed Termux adapter does not require FFmpeg + FFprobe")

models = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt")
manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
shell = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt")
need(has(models, "FfmpegFallbackInputSpec", "expectedDurationMs", "inputContainsBearerSecret", "ffmpegFallbackInputs"), "durable fallback model lacks guarded inputs or duration/progress metadata")
need(has(manager, "ytDlpResolverReadinessIssue", "requiredTools = setOf(ExternalTool.YtDlp)", "ffmpegFallbackReadinessIssue", "enqueueFfmpegFallback", "termuxFallbackEligible", "86_400L", "7_200L", 'values["out_time_us"]', "timelinePercent"), "Termux media manager lacks yt-dlp resolver separation, verified FFmpeg readiness, live-safe timeout, or timeline progress")
need(has(shell, "ffmpegFallbackInputs", "-c copy", "test -s", "output has no playable audio/video stream", "ffprobe -v error"), "Termux FFmpeg execution lacks stream-copy and mandatory output verification")
need("--add-header" not in shell[shell.find("PostProcessingActionKind.FfmpegRemux"):shell.find("PostProcessingActionKind.YtDlpMetadata")], "FFmpeg fallback must not inject captured headers into Termux")

prefs = text("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
settings = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt")
dev = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
need(has(prefs, "media_ffmpeg_runtime_preference", "setMediaFfmpegRuntimePreference", "MediaFfmpegRuntimePreference.Automatic"), "runtime preference is not durably persisted with Automatic default")
need(has(vm, "MediaFfmpegRuntimeContext", "ffmpegFallbackReady", "enqueueFfmpegFallback", "Media runtime routing", "Termux FFmpeg fallback", '"owner" to "TermuxFfmpeg"'), "ViewModel lacks runtime decision/execution/support-bundle wiring")
need(has(settings, "Media runtime routing", "MediaFfmpegRuntimePreference.entries", "authenticated, signed, or private-network sessions never cross that boundary"), "advanced settings lack explicit safe runtime selector")
need(has(dev, "FFmpeg routing policy", "Termux pair", "public header-free URLs"), "Developer Center lacks runtime source/trust diagnostics")

routing_tests = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaRuntimeRoutingFf03Test.kt")
ui_tests = text("app/src/test/kotlin/com/mikeyphw/xdm/android/Ffmpeg03RuntimeRoutingUiContractTest.kt")
need(has(routing_tests, "automaticPrefersEmbeddedRuntime", "automaticFallsBackOnlyForVerifiedPublicHeaderFreeSession", "signedUrlAndHeadersNeverCrossTermuxBoundary", "explicitEmbeddedNeverFallsBack", "explicitTermuxUnavailableIsBlockedInsteadOfSilentlyUsingEmbedded", "privateNetworkTargetNeverCrossesTermuxBoundary", "engineAndDispatcherRouteSafeFallbackToTermuxLane", "unsafeAutomaticFallbackFailsClosedToEmbeddedRepair"), "FF03 routing regression tests are incomplete")
need(has(ui_tests, "settingsExposeAutomaticEmbeddedAndTermuxPolicy", "supportAndDeveloperDiagnosticsExposeRuntimeTruth"), "FF03 UI/support diagnostics contract tests are incomplete")

if not args.skip_prerequisites:
    ff02 = subprocess.run([sys.executable, str(ROOT / "tools/validate-ffmpeg02-media-mux-hls-postprocessing.py")], cwd=ROOT, text=True, capture_output=True)
    need(ff02.returncode == 0, "FF02 prerequisite regressed: " + (ff02.stderr or ff02.stdout).strip())
if errors:
    print("FF03 runtime routing/Termux/UI/reliability contract FAILED", file=sys.stderr)
    for e in errors: print(f"- {e}", file=sys.stderr)
    raise SystemExit(1)
print("FF03 runtime routing/Termux/UI/reliability contract passed")
