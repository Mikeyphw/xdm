#!/usr/bin/env python3
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
checks = []


def read(path: str) -> str:
    file = ROOT / path
    if not file.exists():
        raise AssertionError(f"missing {path}")
    return file.read_text()


def require(path: str, needles: list[str]) -> str:
    text = read(path)
    for needle in needles:
        if needle not in text:
            raise AssertionError(f"{path} missing {needle!r}")
        checks.append(f"{path}: {needle}")
    return text


def forbid(path: str, needles: list[str]) -> str:
    text = read(path)
    for needle in needles:
        if needle in text:
            raise AssertionError(f"{path} must not contain {needle!r}")
        checks.append(f"{path}: no {needle}")
    return text


require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt", [
    "enum class DebugArea",
    "enum class DebugSeverity",
    "data class DebugEvent",
    "interface DebugEventRecorder",
    "object NoOpDebugEventRecorder",
    "interface DebugRecorderProvider",
    "object DebugRedactor",
    "class RollingJsonlDebugEventRecorder",
    "current.jsonl",
    "maxSessionBytes: Long = 2L * 1024L * 1024L",
    "retainedSessions: Int = 5",
    "exportSupportBundle",
    "ZipOutputStream",
    "No automatic upload",
    "PrivacyDiagnosticsRedactor",
])
require("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DebugEventModelsTest.kt", [
    "redactorRemovesSecretsFromEventJson",
    "rollingRecorderWritesBoundedJsonlAndExportsSanitizedBundle",
    "downloadIntakePlannerEmitsSafeDebugEventsWhenRecorderIsProvided",
    "createTempDirectory(\"xdm-debug-d1\").toFile()",
    "secret-token",
    "assertFalse",
    'Authorization\\":\\"<redacted>',
])
forbid("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DebugEventModelsTest.kt", [
    "createTempDir(prefix =",
])
require("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloadIntakePlannerTest.kt", [
    "DownloadIntakePlanner(idFactory = { prefix -> \"$prefix-test-id\" })",
])
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt", [
    "private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder",
    "recordDebugIntake",
    "area = DebugArea.AddDownload",
    "draft-created",
    "unsupported-scheme",
])
require("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt", [
    "private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder",
    "recordDebugSniff",
    "area = DebugArea.MediaSniffing",
    "shared-sniff",
    "static-no-js-no-drm",
    "page-probe",
])
require("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaBatchIntake.kt", [
    "private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder",
    "action = \"batch-intake\"",
    "acceptedCount",
    "pageInspectionCount",
])
require("media/src/main/kotlin/com/mikeyphw/xdm/android/media/ExternalMediaReviewIntake.kt", [
    "private val debugRecorder: DebugEventRecorder = NoOpDebugEventRecorder",
    "action = \"external-media-review\"",
    "sniffed-media-record",
    "page-probe-placeholder",
])
require("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedNotificationDebugEvents.kt", [
    "fallback-to-xdm-details",
    "DebugRedactor::fingerprint",
    "DebugArea.FileOpen",
])
require("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/OpenDownloadedFileActivity.kt", [
    "DebugRecorderProvider",
    "NoOpDebugEventRecorder",
    "CompletedNotificationDebugEvents.fallback",
])
# R5: the redaction test must assert key-preserving value redaction for both common sensitive headers.
debug_event_test_text = (ROOT / "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DebugEventModelsTest.kt").read_text()
for expected in ['Authorization\\":\\"<redacted>', 'Cookie\\":\\"<redacted>']:
    if expected not in debug_event_test_text:
        raise AssertionError(f"DebugEventModelsTest missing key-preserving redaction assertion: {expected}")
    checks.append(f"test: key-preserving redaction {expected}")

require("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaDebugEventRecorderTest.kt", [
    "sniffingEngineEmitsSanitizedDebugEventWhenRecorderIsProvided",
    "mediaBatchPlannerEmitsReviewFirstSummaryEvent",
    "secret-token",
    "assertFalse",
])
require("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedNotificationDebugEventsTest.kt", [
    "fallbackEventRedactsUriAndFingerprintsDownloadId",
    "token=<redacted>",
    "assertFalse",
])
require("app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD1EventRecorderContractTest.kt", [
    "debugFoundationUsesBoundedJsonlAndNoRoomMigration",
    "safeRuntimeHooksArePresentWithoutStartingDownloads",
    "d1DocumentationAndValidatorAreRecorded",
])
require("docs/architecture/DEBUG-WORKBENCH-D1-EVENT-RECORDER.md", [
    "rolling JSONL",
    "No automatic upload",
    "MediaSniffingEngine",
    "Completed notification",
    "R5 redaction test fix",
])
require("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserBridgePhase48FinalReleaseGateContractTest.kt", [
    "debugRoadmapContinuesAfterReleaseGate",
    "debug_workbench_phase_d2_shell",
    "Phase 48 must remain complete or explicitly hand off to the Debug Workbench roadmap",
])
manifest_path = ROOT / "PROJECT_MANIFEST.json"
manifest = json.loads(manifest_path.read_text())
entry = manifest.get("debug_workbench_phase_d1_event_recorder")
if not entry:
    raise AssertionError("PROJECT_MANIFEST.json missing debug_workbench_phase_d1_event_recorder")
for key in ["rolling_jsonl", "redaction", "support_bundle", "instrumentation", "phase48_release_gate_handoff"]:
    if key not in json.dumps(entry):
        raise AssertionError(f"manifest D1 entry missing {key}")
    checks.append(f"manifest: {key}")
if manifest.get("next_phase") != "debug_workbench_phase_d2_shell":
    raise AssertionError("PROJECT_MANIFEST.json must hand off to debug_workbench_phase_d2_shell")
checks.append("manifest: next_phase")
if entry.get("revision") != "r5":
    raise AssertionError("debug_workbench_phase_d1_event_recorder revision must be r5")
checks.append("manifest: revision r5")
require("tools/run-phase-48-final-release-gate.sh", [
    "validate-debug-workbench-d1-event-recorder.py",
])

print(f"Debug Workbench D1 validator passed ({len(checks)} checks)")
