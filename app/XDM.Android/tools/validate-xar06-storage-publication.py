#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
OWNED = [*(f"S06-{i:02d}" for i in range(1, 16)), *(f"DS3-S06-{i:02d}" for i in range(1, 7)), "RERUN23-S06-01"]
checks = []

def fail(msg: str) -> None:
    raise AssertionError(msg)

def read(rel: str) -> str:
    path = ROOT / rel
    if not path.exists():
        fail(f"missing {rel}")
    return path.read_text(encoding="utf-8")

def require(rel: str, *needles: str) -> str:
    text = read(rel)
    missing = [needle for needle in needles if needle not in text]
    if missing:
        fail(f"{rel} missing {missing}")
    checks.extend(f"{rel}:{needle}" for needle in needles)
    return text

def coverage() -> None:
    report = read("docs/remediation/XAR06-STORAGE-PUBLICATION.md")
    missing = [cid for cid in OWNED if cid not in report]
    if missing:
        fail(f"report missing canonical IDs: {missing}")
    if "22/22" not in report:
        fail("report must explicitly claim 22/22 closure")
    checks.append("22/22 S06 coverage")

def source_contracts() -> None:
    require("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DestinationProvider.kt", "artifactGeneration", "expectedTotalBytes", "stagingPathRetained")
    require("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PublicationSafety.kt", "PublicationArtifactToken", "PublicationTransaction", "phase=xar06-publication-transaction", "sizeMatches", "attemptDirectory", "requiredBytesForPublication")
    require("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/FileDestinationWriter.kt", "PublicationStagingNames.stagingFileName", "Resume cannot replace an existing final file", "arrayOf(StandardCopyOption.ATOMIC_MOVE)", "firstExistingAncestor")
    require("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt", "PublicationStagingNames.attemptDirectory", "request.artifactGeneration", "requireNotNull(querySize(committed.uri))", "stagingPathRetained = artifacts.stagingFile.absolutePath", "canCreateWritableTreeProbe", "Android provider destinations cannot replace an existing final artifact")
    require("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt", "PublicationJournalCodec.read", "record?.stagingPath", "delete()")
    require("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedFileGrantPolicy.kt", "PersonalDirectStorage.downloadsDirectory")
    require("scheduler/src/main/res/xml/xdm_completed_download_paths.xml", "completed_personal_direct_downloads", "Download/XDM/")
    require("app/src/main/kotlin/com/mikeyphw/xdm/android/DownloadArtifactActions.kt", "PersonalDirectStorage.downloadsDirectory")
    require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "Saved artifact deletion pending durable metadata reconciliation", "Saved artifact rename pending durable metadata reconciliation", "The saved file was not touched")
    require("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt", "expectedTotalBytes = expectedLength", "artifactGeneration = attemptGeneration")
    require("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt", "expectedTotalBytes = expectedLength", "artifactGeneration = attemptGeneration")
    require("storage/src/test/kotlin/com/mikeyphw/xdm/android/storage/Xar06PublicationTransactionContractTest.kt", "stagingIdentityIncludesAttemptAndArtifactGeneration", "fileResumeRefusesToReplaceExistingFinalFromStaleStaging", "renameMoveDoesNotOverwriteToctouCreatedTarget", "zeroBytePublicationIsValidWhenExpectedSizeIsZero")
    require("app/build.gradle.kts", "verifyXar06StoragePublication", "validate-xar06-storage-publication.py")
    require("tools/run-final-release-gate.sh", "tools/validate-xar06-storage-publication.py")

def manifest_contract() -> None:
    manifest = read("PROJECT_MANIFEST.json")
    for needle in ["xar06_storage_publication", "XAR06", "22", "tools/validate-xar06-storage-publication.py"]:
        if needle not in manifest:
            fail(f"PROJECT_MANIFEST missing {needle}")
    checks.append("manifest registered")

def main() -> int:
    coverage()
    source_contracts()
    manifest_contract()
    print(f"XAR06 storage publication contract passed: {len(checks)} checks; 22/22 S06 findings covered.")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"XAR06 validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
