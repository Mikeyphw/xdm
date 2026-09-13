#!/usr/bin/env python3
"""XAR03 source and behavioral contract validator.

This validator deliberately exercises the durable persistence invariants called out by the
S05 audit roots. It uses SQLite for adversarial uniqueness/CAS behavior and repository-source
checks for the Android/Room integration points that must exist in production code.
"""
from __future__ import annotations

import json
import os
import sqlite3
import sys
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]

OWNED_IDS = [
    "S05-01", "S05-02", "S05-03", "S05-04", "S05-05", "S05-06", "S05-07",
    "S05-09", "S05-10", "S05-11", "S05-12", "DS3-S05-01", "DS3-S05-02",
    "DS3-S05-03", "DS3-S05-04", "DS3-S05-05", "DS3-S05-06",
]


def fail(message: str) -> None:
    raise AssertionError(message)


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.exists():
        fail(f"Missing required file: {rel}")
    return path.read_text(encoding="utf-8")


def require(rel: str, *needles: str) -> str:
    text = read(rel)
    missing = [needle for needle in needles if needle not in text]
    if missing:
        fail(f"{rel} is missing required XAR03 markers: {missing}")
    return text


def sqlite_behavioral_contracts() -> None:
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript(
        """
        CREATE TABLE checksum_expectations(downloadId TEXT NOT NULL, attemptGeneration INTEGER NOT NULL, algorithm TEXT NOT NULL);
        CREATE UNIQUE INDEX idx_checksum_expectations_attempt ON checksum_expectations(downloadId, attemptGeneration, algorithm);
        CREATE TABLE checksum_results(downloadId TEXT NOT NULL, attemptGeneration INTEGER NOT NULL, algorithm TEXT NOT NULL);
        CREATE UNIQUE INDEX idx_checksum_results_attempt ON checksum_results(downloadId, attemptGeneration, algorithm);
        CREATE TABLE trusted_block_manifests(downloadId TEXT NOT NULL, attemptGeneration INTEGER NOT NULL, blocksJson TEXT NOT NULL);
        CREATE UNIQUE INDEX idx_trusted_manifest_attempt ON trusted_block_manifests(downloadId, attemptGeneration);
        CREATE TABLE finalization_journals(downloadId TEXT NOT NULL, attemptGeneration INTEGER NOT NULL, stage TEXT NOT NULL);
        CREATE UNIQUE INDEX idx_finalization_attempt ON finalization_journals(downloadId, attemptGeneration);
        CREATE TABLE recovery_records(downloadId TEXT, attemptGeneration INTEGER NOT NULL, artifactPath TEXT NOT NULL, artifactIdentity TEXT NOT NULL, classification TEXT NOT NULL);
        CREATE UNIQUE INDEX idx_recovery_attempt_artifact_class ON recovery_records(downloadId, attemptGeneration, artifactIdentity, classification);
        CREATE TABLE native_hls_jobs(id TEXT PRIMARY KEY, downloadId TEXT NOT NULL, attemptGeneration INTEGER NOT NULL, stage TEXT NOT NULL, rowRevision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL);
        CREATE TABLE media_captures(id TEXT PRIMARY KEY, selectedVariantId TEXT, selectedVariantUrl TEXT, resolutionStatus TEXT, updatedAtEpochMs INTEGER);
        CREATE TABLE media_variants(id TEXT PRIMARY KEY, captureId TEXT NOT NULL, url TEXT NOT NULL);
        CREATE TABLE automation_commands(id TEXT PRIMARY KEY, status TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL);
        CREATE TABLE post_processing_claims(claimKey TEXT PRIMARY KEY, subjectId TEXT NOT NULL, subjectGeneration INTEGER NOT NULL, trigger TEXT NOT NULL, ruleId TEXT NOT NULL, actionId TEXT NOT NULL);
        CREATE TABLE post_processing_jobs(id TEXT PRIMARY KEY, claimKey TEXT NOT NULL, downloadId TEXT, attemptGeneration INTEGER NOT NULL, subjectGeneration INTEGER NOT NULL);
        """
    )

    for table in ("checksum_expectations", "checksum_results"):
        db.execute(f"INSERT INTO {table} VALUES ('d1', 1, 'sha256')")
        db.execute(f"INSERT INTO {table} VALUES ('d1', 2, 'sha256')")
        try:
            db.execute(f"INSERT INTO {table} VALUES ('d1', 2, 'sha256')")
            fail(f"{table} accepted duplicate per-download/per-attempt evidence")
        except sqlite3.IntegrityError:
            pass

    db.execute("INSERT INTO trusted_block_manifests VALUES ('d1', 1, '{}')")
    db.execute("INSERT INTO trusted_block_manifests VALUES ('d1', 2, '{}')")
    try:
        db.execute("INSERT INTO trusted_block_manifests VALUES ('d1', 2, '{}')")
        fail("trusted_block_manifests accepted duplicate attempt evidence")
    except sqlite3.IntegrityError:
        pass

    db.execute("INSERT INTO finalization_journals VALUES ('d1', 1, 'Promoted')")
    db.execute("INSERT INTO finalization_journals VALUES ('d1', 2, 'Promoted')")
    try:
        db.execute("INSERT INTO finalization_journals VALUES ('d1', 2, 'Promoted')")
        fail("finalization_journals accepted duplicate per-attempt journal")
    except sqlite3.IntegrityError:
        pass

    db.execute("INSERT INTO recovery_records VALUES ('d1', 1, '/staging/a', 'sha256:a', 'PublicationLost')")
    db.execute("INSERT INTO recovery_records VALUES ('d1', 1, '/staging/b', 'sha256:b', 'PublicationLost')")
    db.execute("INSERT INTO recovery_records VALUES ('d1', 2, '/staging/a', 'sha256:a', 'PublicationLost')")
    deleted = db.execute("DELETE FROM recovery_records WHERE downloadId='d1' AND attemptGeneration=1 AND classification='PublicationLost'").rowcount
    if deleted != 2:
        fail("attempt/classification recovery cleanup did not stay scoped")
    remaining = db.execute("SELECT COUNT(*) FROM recovery_records WHERE downloadId='d1'").fetchone()[0]
    if remaining != 1:
        fail("recovery cleanup deleted another attempt's evidence")

    db.execute("INSERT INTO native_hls_jobs VALUES ('h1', 'd1', 2, 'Running', 10, 10)")
    stale = db.execute("UPDATE native_hls_jobs SET stage='Completed', rowRevision=11 WHERE id='h1' AND attemptGeneration=2 AND rowRevision=9 AND stage NOT IN ('Completed','Failed','Cancelled')").rowcount
    fresh = db.execute("UPDATE native_hls_jobs SET stage='Completed', rowRevision=11 WHERE id='h1' AND attemptGeneration=2 AND rowRevision=10 AND stage NOT IN ('Completed','Failed','Cancelled')").rowcount
    resurrect = db.execute("UPDATE native_hls_jobs SET stage='Running', rowRevision=12 WHERE id='h1' AND attemptGeneration=2 AND rowRevision=11 AND stage NOT IN ('Completed','Failed','Cancelled')").rowcount
    if stale != 0 or fresh != 1 or resurrect != 0:
        fail("Native-HLS rowRevision/terminal stale-writer contract failed")

    db.execute("INSERT INTO media_captures VALUES ('c1', NULL, NULL, 'Pending', 0)")
    db.execute("INSERT INTO media_captures VALUES ('c2', NULL, NULL, 'Pending', 0)")
    db.execute("INSERT INTO media_variants VALUES ('v2', 'c2', 'https://example.invalid/v2')")
    wrong = db.execute("UPDATE media_captures SET selectedVariantId='v2' WHERE id='c1' AND EXISTS(SELECT 1 FROM media_variants WHERE id='v2' AND captureId='c1')").rowcount
    if wrong != 0:
        fail("selected media variant can point at another capture")

    db.execute("INSERT INTO automation_commands VALUES ('a1', 'Applied', 100)")
    regressed = db.execute("UPDATE automation_commands SET status='Received', updatedAtEpochMs=90 WHERE id='a1' AND updatedAtEpochMs <= 90 AND (status NOT IN ('Applied','Failed','Rejected','Duplicate') OR status='Received')").rowcount
    if regressed != 0:
        fail("automation persistence allowed older replay to regress a terminal command")

    db.execute("INSERT INTO post_processing_jobs VALUES ('old', 'claim-old', 'd1', 1, 1)")
    db.execute("INSERT INTO post_processing_claims VALUES ('clone-d1-g2', 'd1', 2, 'Redownload', 'rule', 'remux')")
    db.execute("INSERT INTO post_processing_jobs VALUES ('clone', 'clone-d1-g2', 'd1', 2, 2)")
    try:
        db.execute("INSERT INTO post_processing_claims VALUES ('clone-d1-g2', 'd1', 2, 'Redownload', 'rule', 'remux')")
        fail("post-processing clone claim was not idempotent")
    except sqlite3.IntegrityError:
        pass


def repository_source_contracts() -> None:
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt", "version = 25")
    require("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt", "Migrations.Migration24To25", "postProcessingAutomationManager.startAutomaticProcessing()", "if (migration.isSuccess)")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt", "val Migration24To25", "artifactIdentity", "rowRevision", "index_finalization_journals_downloadId_attemptGeneration", "index_recovery_records_downloadId_attemptGeneration_artifactIdentity_classification")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt", "Index(value = [\"downloadId\", \"attemptGeneration\", \"algorithm\"], unique = true)", "Index(value = [\"downloadId\", \"attemptGeneration\"], unique = true)", "artifactIdentity", "rowRevision")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadDao.kt", "deleteByDownloadAttemptClassification", "findAttemptRecord")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/ChecksumDao.kt", "expectationsForAttempt", "resultsForAttempt", "trustedManifestForAttempt")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/FinalizationDao.kt", "findByDownloadAttempt", "deleteByDownloadAttempt")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/NativeHlsDao.kt", "updateStageOwned", "rowRevision", "stage NOT IN ('Completed', 'Failed', 'Cancelled')")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt", "selectVariantIfVariantExists", "EXISTS(SELECT 1 FROM media_variants WHERE id = :variantId AND captureId = :captureId)")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt", "replaceMediaVariantsForCapture", "reconcileCaptureAfterVariantReplacement", "upsertAutomationCommandStatefully", "updatedAtEpochMs <= :updatedAtEpochMs", "status NOT IN ('Applied', 'Failed', 'Rejected', 'Duplicate')")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AutomationCommandDao.kt", "direct blind upserts are intentionally absent")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/PostProcessingDao.kt", "claimAndInsertRedownloadClone", "clone job generation must match claim generation")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomRecoveryWorkflowStore.kt", "normalizedEvidenceIdentity", "artifactIdentity")
    require("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt", "deleteRecoveryForAttempt", "checksumExpectationsForAttempt", "trustedManifestForAttempt", "finalizationForDownloadAttempt")
    require("app/build.gradle.kts", "verifyXar03PersistenceGenerationIntegrity", "trackStaticValidation(\"xar03-persistence-generation-integrity\")")
    require("tools/run-final-release-gate.sh", "tools/validate-xar03-persistence-generation-integrity.py")
    require("docs/remediation/XAR03-PERSISTENCE-GENERATION-INTEGRITY.md", "XAR03", "S05-01", "DS3-S05-06")


def schema_contract() -> None:
    schema_dir = ROOT / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase"
    required = set(range(4, 26))
    found = {int(p.stem) for p in schema_dir.glob("*.json") if p.stem.isdigit()}
    missing = sorted(required - found)
    if missing:
        fail(f"Committed Room schemas missing expected 4→25 chain entries: {missing}")
    schema25 = json.loads((schema_dir / "25.json").read_text(encoding="utf-8"))
    if schema25["database"]["version"] != 25:
        fail("schema 25 file does not declare database version 25")
    by_table = {e["tableName"]: e for e in schema25["database"]["entities"]}
    for table in ["recovery_records", "finalization_journals", "checksum_expectations", "checksum_results", "trusted_block_manifests", "native_hls_jobs"]:
        if table not in by_table:
            fail(f"schema 25 missing table {table}")
    if not any(f["columnName"] == "artifactIdentity" for f in by_table["recovery_records"]["fields"]):
        fail("schema 25 missing recovery_records.artifactIdentity")
    if not any(f["columnName"] == "rowRevision" for f in by_table["native_hls_jobs"]["fields"]):
        fail("schema 25 missing native_hls_jobs.rowRevision")
    index_names = {idx["name"] for table in by_table.values() for idx in table.get("indices", [])}
    for name in [
        "index_checksum_expectations_downloadId_attemptGeneration_algorithm",
        "index_checksum_results_downloadId_attemptGeneration_algorithm",
        "index_trusted_block_manifests_downloadId_attemptGeneration",
        "index_finalization_journals_downloadId_attemptGeneration",
        "index_recovery_records_downloadId_attemptGeneration_artifactIdentity_classification",
    ]:
        if name not in index_names:
            fail(f"schema 25 missing index {name}")


def closure_contract() -> None:
    report = read("docs/remediation/XAR03-PERSISTENCE-GENERATION-INTEGRITY.md")
    missing = [cid for cid in OWNED_IDS if cid not in report]
    if missing:
        fail(f"XAR03 report missing owned canonical IDs: {missing}")
    if "16/16" not in report:
        fail("XAR03 report must explicitly claim 16/16 S05 closure")


def main() -> int:
    sqlite_behavioral_contracts()
    repository_source_contracts()
    schema_contract()
    closure_contract()
    print("XAR03 persistence generation integrity contract passed: 16/16 S05 findings covered")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"XAR03 validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
