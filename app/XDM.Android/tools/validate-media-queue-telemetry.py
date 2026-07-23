#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)

telemetry_path = ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaQueueTelemetry.kt"
screens_path = ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt"
tests_path = ROOT / "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt"
contract_path = ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt"
manifest_path = ROOT / "PROJECT_MANIFEST.json"
doc_path = ROOT / "docs/architecture/PHASE-23-MEDIA-QUEUE-TELEMETRY.md"

for path in [telemetry_path, screens_path, tests_path, contract_path, manifest_path, doc_path]:
    require(path.exists(), f"Missing required Phase 23 file: {path.relative_to(ROOT)}")

telemetry = telemetry_path.read_text(encoding="utf-8")
screens = screens_path.read_text(encoding="utf-8")
tests = tests_path.read_text(encoding="utf-8")
contract = contract_path.read_text(encoding="utf-8")
manifest = manifest_path.read_text(encoding="utf-8")

for token in [
    "MediaQueueTelemetryTone",
    "MediaQueueTelemetryRow",
    "MediaQueueTelemetryDeck",
    "MediaQueueTelemetryPlanner",
    "nextActionLabel",
    "cleanupArmed",
    "safeDiagnostic",
    "containsKnownSecret",
]:
    require(token in telemetry, f"Telemetry planner missing token: {token}")

for forbidden in [
    "MediaQueueTelemetryRow::cleanupArmed",
    "MediaDispatchStep::terminalCleanup",
    "buildList",
    "buildMap",
    "toBooleanStrictOrNull",
]:
    require(forbidden not in telemetry, f"Phase 23 uses known Kotlin/BTAPI trap: {forbidden}")

for token in [
    "Media queue telemetry",
    "MediaQueueTelemetryCard(queueTelemetry)",
    "toneForQueueTelemetry",
    "secret-safe telemetry",
]:
    require(token in screens, f"Screens missing Phase 23 UI token: {token}")

for token in [
    "mediaQueueTelemetryDeckShowsReadyCleanup",
    "mediaQueueTelemetryRedactsFailedJobDetails",
    "secret-cookie",
    "Retry media",
]:
    require(token in tests, f"Media tests missing Phase 23 coverage token: {token}")

for token in [
    "mediaQueueTelemetryContractsArePresent",
    "PHASE-23-MEDIA-QUEUE-TELEMETRY.md",
    "Media queue telemetry must not add top-level routes",
]:
    require(token in contract, f"Architecture contract missing Phase 23 token: {token}")

require('"media_queue_telemetry"' in manifest, "Project manifest missing media_queue_telemetry")
require('"top_level_route_added": false' in manifest, "Project manifest must keep top_level_route_added false")
require('"room_schema_migration": false' in manifest, "Project manifest must keep Room schema unchanged")

print("Phase 23 media queue telemetry validation passed")
