#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

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

runtime = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
native = read("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
native_models = read("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeTransferModels.kt")
aria2 = read("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt")
worker = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
job = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt")
service = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase2DownloadExecutionContractTest.kt")
manifest = read("PROJECT_MANIFEST.json")
doc = read("docs/audits/BUG-HUNT-REMEDIATION-PHASE-2.md")

for needle, label in [
    ("commandControls = ConcurrentHashMap<String, DownloadCommandControl>()", "per-download command ledger"),
    ("val mutex = Mutex()", "serialized job creation"),
    ("AtomicLong", "control generation"),
    ("DesiredTransferState.PauseRequested", "pause intent"),
    ("DesiredTransferState.CancelRequested", "cancel intent"),
    ("ensureExecutionJob(downloadId)", "single execution entrypoint"),
    ("current.state in TERMINAL_STATES", "terminal guard"),
    ("current?.state == DownloadState.Failed", "fresh retry from failed backend"),
    ("generationBeforeVerification", "cancel during verification generation guard"),
    ("DownloadState.Verifying", "foreground summary includes verifying"),
]:
    require(runtime, needle, label)

for text, needle, label in [
    (worker, "if (isStopped) pauseAndRecordStop()", "WorkManager stop handling"),
    (worker, "runtime.pauseAll()", "WorkManager pauses runtime"),
    (job, "withTimeoutOrNull(5_000)", "UIDT stop wait"),
    (job, "transferRuntime.pause(downloadId)", "UIDT pauses item"),
    (service, "runtime.summary.value.activeCount > 0", "foreground destruction guard"),
    (service, "withTimeoutOrNull(3_000)", "bounded foreground pause"),
    (service, "runtime.pauseAll()", "foreground service pauses active transfers"),
]:
    require(text, needle, label)

for needle, label in [
    ("newTransferRequestBuilder", "shared request builder"),
    ("isEngineOwnedHeader", "engine-owned header filtering"),
    ("If-Range", "If-Range on resume"),
    ("Remote ETag validator disappeared", "validator fail-closed"),
    ("Remote redirect target changed", "redirect identity check"),
    ("Server no longer supports byte ranges required by the segmented checkpoint", "zero-byte segmented range-loss guard"),
    ("normalizePreviousSegments", "complete segment normalization"),
    ("trustedLength", "expected length without Content-Length"),
    ("rejectUnexpectedHtmlOrCompressedResponse", "HTML/compression rejection"),
    ("HostRetryBackoff", "host retry coordination"),
    ("Retry-After", "server backoff support"),
    ("control.activeCalls.remove(call)", "active-call cleanup"),
    ("bytesAtAttemptStart", "resume speed correction"),
    ("control.activeCalls.forEach(Call::cancel)", "sibling call cancellation"),
]:
    require(native, needle, label)
require(native_models, "retryAfterMillis", "HTTP retry-after model")
reject(native, "request.headers.forEach { (name, value) -> builder.header(name, value) }", "unfiltered external headers")

for needle, label in [
    ("rpc.remove(taskId, force = true)", "confirmed aria2 removal"),
    ("rpc.saveSession()", "durable aria2 state"),
    ("Recovered aria2 destination key no longer matches the original ownership claim", "aria2 destination identity check"),
]:
    require(aria2, needle, label)
reject(aria2, "if (status?.status !in TERMINAL_RPC_STATES) runCatching { rpc?.remove(taskId, force = true) }", "swallowed aria2 removal failure")

for text, label in [(contract, "Phase 2 contract test"), (manifest, "manifest"), (doc, "phase document")]:
    for needle in ["Download Execution Correctness", "Phase 2"]:
        require(text, needle, f"{label} mentions {needle}")

print("Phase 2 download execution validator passed")
