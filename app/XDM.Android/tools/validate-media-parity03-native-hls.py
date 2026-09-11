#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]

def text(path: str) -> str:
    p = ROOT / path
    if not p.exists():
        raise AssertionError(f"Missing {path}")
    return p.read_text()

def require(haystack: str, needle: str, label: str) -> None:
    if needle not in haystack:
        raise AssertionError(f"Missing {label}: {needle}")

engine = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngine.kt")
planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
library = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
dispatcher = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
worker = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt")
db = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
entities = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt")
dao = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/NativeHlsDao.kt")
migrations = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt")
app = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
report = text("XDM_MEDIA_PARITY03_NATIVE_HLS_EXECUTION_ADMISSION_INTEGRITY_REPORT.md")
manifest = json.loads(text("PROJECT_MANIFEST.json"))

for needle, label in [
    ("enum class NativeHlsSupportStatus", "support status taxonomy"),
    ("NativeUnsupportedFallback", "fallback status"),
    ("ProtectedUnsupported", "protected unsupported status"),
    ("NativeHlsExecutionStage", "stage-aware execution model"),
    ("NativeHlsPartState", "durable part state model"),
    ("NativeHlsFinalizationState", "finalization state model"),
    ("NativeHlsByteRange", "byte range support"),
    ("NativeHlsMap", "init map support"),
    ("NativeHlsKey", "key support"),
    ("implicitIvHex", "implicit IV support"),
    ("EXT-X-MAP", "MAP parser"),
    ("EXT-X-BYTERANGE", "BYTERANGE parser"),
    ("EXT-X-MEDIA-SEQUENCE", "media sequence support"),
    ("EXT-X-DISCONTINUITY", "discontinuity support"),
    ("EXT-X-PART", "LL-HLS fallback detection"),
    ("EXT-X-PRELOAD-HINT", "LL-HLS preload detection"),
    ("SAMPLE-AES", "protected SAMPLE-AES detection"),
    ("storagePreflight", "storage preflight"),
    ("verifyCompletion", "completion verifier"),
    ("Artifact is playlist text, not finalized media", "playlist text completion rejection"),
    ("Artifact looks like an HTML/error response", "HTML error rejection"),
    ("coerceAtMost(99)", "100 percent completion guard"),
    ("existing logical-media HLS job reused", "admission idempotency"),
    ("explicit Add again created another HLS generation", "Add again override"),
]:
    require(engine, needle, label)

for needle, label in [
    ("NativeHls", "strategy enum"),
    ("shape == MediaTransferShape.AdaptivePlaylist && capture.kind == MediaSourceKind.HlsPlaylist && nativeHlsEligible", "native HLS planner routing before yt-dlp"),
    ("MediaDownloadStrategy.NativeHls -> \"Native HLS\"", "native HLS display label"),
    ("Supported VOD HLS is executed as one durable native segmented job", "native HLS explanation"),
    ("MediaNativeCapability.FallbackRequired", "capability fallback respected"),
]:
    require(planner, needle, label)

for needle, label in [
    ("NativeHlsSegmented", "execution lane"),
    ("NativeHlsSegmented -> \"native-hls\"", "typed executor name literal"),
    ("--stage-model", "typed native HLS arguments"),
    ("MediaExecutionFailureKind.NativeHlsFailed", "native HLS retry classification"),
]:
    require(library.replace(" ", ""), needle.replace(" ", ""), label)

for needle, label in [
    ("Queue native HLS", "primary action"),
    ("native HLS ledger", "progress signal"),
    ("finalizing/publishing", "stage feedback"),
    ("completion verification", "verification feedback"),
    ("Direct native compatible", "dashboard keeps native family"),
]:
    require(dispatcher, needle, label)

for needle, label in [
    ("MediaExecutionLane.NativeHlsSegmented", "worker bridge handles native HLS"),
    ("MediaWorkerBridgeKind.NativeDirect", "native worker dispatch remains app-side"),
]:
    require(worker, needle, label)

for needle, label in [
    ("version = 24", "Room schema 24"),
    ("NativeHlsJobEntity::class", "native HLS job entity registration"),
    ("NativeHlsPartEntity::class", "native HLS part entity registration"),
    ("abstract fun nativeHlsDao(): NativeHlsDao", "DAO registration"),
]:
    require(db, needle, label)
for needle, label in [
    ("tableName = \"native_hls_jobs\"", "native_hls_jobs entity"),
    ("admissionKey", "admission key persistence"),
    ("finalizationState", "finalization state persistence"),
    ("completedArtifactSha256", "completion hash persistence"),
    ("tableName = \"native_hls_parts\"", "native_hls_parts entity"),
    ("mediaSequence", "media sequence persistence"),
    ("byteRangeLength", "byte range persistence"),
    ("keyIvHex", "AES IV persistence"),
]:
    require(entities, needle, label)
for needle, label in [
    ("findActiveByAdmissionKey", "idempotent lookup DAO"),
    ("updateStage", "stage update DAO"),
    ("updatePart", "part update DAO"),
]:
    require(dao, needle, label)
for needle, label in [
    ("Migration23To24 = object : Migration(23, 24)", "23 to 24 migration"),
    ("CREATE TABLE IF NOT EXISTS native_hls_jobs", "job migration table"),
    ("CREATE TABLE IF NOT EXISTS native_hls_parts", "parts migration table"),
    ("index_native_hls_jobs_admissionKey_attemptGeneration", "admission uniqueness index"),
]:
    require(migrations, needle, label)
require(app, "Migrations.Migration23To24", "migration registration")
if not (ROOT / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/24.json").exists():
    raise AssertionError("Missing exported Room schema 24.json")

for path in [
    "media/src/test/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngineTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/MediaParity03NativeHlsExecutionContractTest.kt",
]:
    body = text(path)
    for needle in ["supported", "lowSpace", "unsupported", "Tiny", "Add"]:
        if needle not in body:
            raise AssertionError(f"{path} missing fixture marker {needle}")

for needle in [
    "118 internal parts -> one aggregate Download row",
    "pause/resume/process-death recovery",
    "low storage during segments and finalization",
    "tiny/incoherent artifacts cannot become Completed",
    "unsupported LL-HLS routes to fallback",
    "AES-128 explicit and implicit IV",
]:
    require(report, needle, f"report traceability: {needle}")

mp = manifest.get("media_parity03_native_hls_execution_admission_integrity")
if not isinstance(mp, dict):
    raise AssertionError("PROJECT_MANIFEST missing media_parity03_native_hls_execution_admission_integrity")
for key in [
    "room_schema_current", "migration", "native_hls_strategy", "durable_part_ledger", "admission_idempotency", "progress_truth", "storage_recovery", "completion_verification", "fallback_detection", "next_overlay",
]:
    if key not in mp:
        raise AssertionError(f"manifest parity03 missing {key}")
if mp.get("room_schema_current") != 24 or mp.get("migration") != "23_to_24":
    raise AssertionError("manifest parity03 schema truth is not 24/23_to_24")

print("Media Parity03 native HLS/admission/progress/completion validation passed")
