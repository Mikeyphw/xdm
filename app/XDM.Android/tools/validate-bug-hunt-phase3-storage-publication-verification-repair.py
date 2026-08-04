#!/usr/bin/env python3
from pathlib import Path
import json
root = Path(__file__).resolve().parents[1]

def read(rel):
    return (root / rel).read_text(encoding="utf-8")

def require(condition, message):
    if not condition:
        raise SystemExit(message)

manifest = json.loads(read("PROJECT_MANIFEST.json"))
phase = manifest.get("bug_hunt_remediation_phase_3", {})
require(phase.get("status") == "implemented", "Phase 3 manifest status missing")
require(bool(phase.get("commit_message")), "Phase 3 project manifest commit_message missing")
models = read("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PublicationSafety.kt")
android_writer = read("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
file_writer = read("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/FileDestinationWriter.kt")
repair = read("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeSelectiveRepairService.kt")
checksum = read("transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/ChecksumVerification.kt")
for token in ["PublicationCommitBoundary", "CompletedArtifactHealthStatus", "committedUri", "attemptGeneration", "artifactGeneration", "expectedDigest", "actualDigest", "verificationTimestampEpochMs"]:
    require(token in models, f"missing publication model token {token}")
require("PublicationJournalCodec.write" in file_writer, "filesystem writer does not write journal")
require("PublicationJournalCodec.write" in android_writer, "Android writer does not write journal")
require("RELATIVE_PATH}=?" in android_writer and "RELATIVE_PATH} LIKE ?" not in android_writer, "MediaStore lookup must use exact relative path")
require("rowsUpdated > 0" in android_writer and "queryIsPending" in android_writer, "MediaStore publication must verify update count and pending state")
require("parseExpectedChecksum" in checksum and "exactly one hexadecimal digest" in checksum, "strict checksum parser missing")
for token in ["Selective repair requires HTTP 206", "Content-Range mismatch", "If-Range", ".repair-", "trailing bytes"]:
    require(token in repair, f"selective repair contract missing {token}")
require((root / "docs/audits/BUG-HUNT-REMEDIATION-PHASE-3.md").is_file(), "Phase 3 audit doc missing")
print("Phase 3 storage/publication/verification/repair validator passed")
