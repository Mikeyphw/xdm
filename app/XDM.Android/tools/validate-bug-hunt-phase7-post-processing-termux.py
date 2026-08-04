#!/usr/bin/env python3
"""Executable source contract for Android bug-hunt remediation Phase 7."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


def locate_android_root(start: Path) -> Path:
    start = start.resolve()
    candidates = [start, start / "app" / "XDM.Android"]
    candidates += [parent / "app" / "XDM.Android" for parent in start.parents]
    for candidate in candidates:
        if (candidate / "settings.gradle.kts").is_file() and (candidate / "app").is_dir():
            return candidate
    raise SystemExit(f"Unable to locate app/XDM.Android from {start}")


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def text(root: Path, relative: str, errors: list[str]) -> str:
    path = root / relative
    if not path.is_file():
        errors.append(f"missing file: {relative}")
        return ""
    raw = path.read_bytes()
    if b"\x00" in raw:
        errors.append(f"embedded NUL byte: {relative}")
    return raw.decode("utf-8")


def contains_all(source: str, markers: tuple[str, ...], label: str, errors: list[str]) -> None:
    for marker in markers:
        require(marker in source, f"{label} missing marker: {marker}", errors)


def validate(root: Path) -> list[str]:
    errors: list[str] = []
    manifest = text(root, "app/src/main/AndroidManifest.xml", errors)
    app_db = text(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt", errors)
    migrations = text(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt", errors)
    entities = text(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt", errors)
    dao = text(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/PostProcessingDao.kt", errors)
    graph = text(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt", errors)
    models = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt", errors)
    auto = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt", errors)
    manager = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt", errors)
    bridge = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/AndroidPostProcessingArtifactBridge.kt", errors)
    runner = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxCommandRunner.kt", errors)
    result_service = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxResultService.kt", errors)
    shell = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt", errors)
    run_store = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxRunStore.kt", errors)
    app = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt", errors)
    vm = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", errors)
    ui = text(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt", errors)
    transfer = text(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt", errors)
    contract = text(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/PostProcessingPhase7ContractTest.kt", errors)
    migration_test = text(root, "persistence/src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/PostProcessingMigrationTest.kt", errors)
    documentation = text(root, "docs/audits/BUG-HUNT-REMEDIATION-PHASE-7.md", errors)

    contains_all(manifest, (
        "android.permission.READ_EXTERNAL_STORAGE", 'android:maxSdkVersion="28"',
        "android.permission.WRITE_EXTERNAL_STORAGE", "com.termux.permission.RUN_COMMAND",
        '.termux.TermuxResultService', 'android:exported="false"',
    ), "manifest", errors)
    contains_all(app_db, ("PostProcessingJobEntity::class", "PostProcessingClaimEntity::class", "version = 17", "postProcessingDao"), "database", errors)
    contains_all(migrations, ("Migration14To15", "Migration15To16", "Migration16To17", "post_processing_jobs", "post_processing_claims", "ON DELETE CASCADE", "publicationState", "committedOutputUri"), "migrations", errors)
    contains_all(entities, ("attemptGeneration", "immutableSpecJson", "processToken", "controlGeneration", "progressBridgeUri", "timeoutAtEpochMs", "claimKey"), "entities", errors)
    contains_all(dao, ("claimAndInsert", "insertJob(job)", "insertClaimIgnore(claim)", "controlGeneration = controlGeneration + 1", "findJobByRunId", "maxAttemptGeneration", "claimKey IS NULL"), "DAO", errors)
    contains_all(graph, ("post_processing_jobs", "deletePostProcessingForDownload"), "delete graph", errors)

    contains_all(models, (
        "subjectGeneration", "inputMimeType", "inputContainer", "inputCodecs", "fun toJson()", "fun fromJson",
        "sensitiveArgumentReason", "inputContainsBearerSecret", "claimKey", "processToken",
        "mediaSubjectGeneration", "preflightIssue", "formatCompatibilityIssue", "MaxOutputNameBytes",
        "Verify SHA-256 requires an expected digest",
    ), "execution policy", errors)
    contains_all(auto, (
        "handleTransferTerminalEvent", "event.attemptGeneration", "mediaSubjectGeneration",
        "durableClaim = true", "preferences.values", "postProcessingSettings",
    ), "automation", errors)
    contains_all(transfer, ("attemptGenerations", "existingOwnership.generation", "coordinated.ownership.generation", "requestGeneration(download.id)"), "terminal generation propagation", errors)
    contains_all(manager, (
        "recoverInterruptedJobs", "immutableSpecJson", "maxAttemptGeneration", "parentJobId",
        "preflightIssue", "runAndroidChecksum", "requestControlNow", "ForceCancel", "TimedOut",
        "findJobByRunId", "processToken", "PrivacyDiagnosticsRedactor.redactText",
        "reconcileCommittedPublication", "PostProcessingResultMode.SideEffectOnly", "ownerSnapshot?.finished", "updateFromFfprobe", "updateFromYtDlp", "replaceMediaVariants",
        "clearManualTerminalJobs", "mediaSubjectGeneration",
    ), "pipeline manager", errors)

    require(bridge.find("bridgePeak") != -1 and bridge.find("bridgePeak") < bridge.find("createInputBridge"), "capacity preflight must precede input staging", errors)
    contains_all(bridge, (
        "ContentResolver.SCHEME_CONTENT", "openAssetFileDescriptor", "copyWithLimit",
        "estimateToolScratch", "preflightFinalDestination", "availableSpace", "fd.sync()",
        "expectedBytes", "sha256", "preparePublication", "publishPrepared", "recoverPublished", "Build.VERSION.SDK_INT < Build.VERSION_CODES.Q",
    ), "artifact bridge", errors)
    contains_all(runner, ("ExtraStdin", ".putExtra(ExtraStdin, script)", "shellArguments", 'arrayOf("-s")', "ExtraJobId", "ExtraProcessToken", "FLAG_ONE_SHOT"), "Termux runner", errors)
    require("payloadBridgeUri" not in runner and "openOutputStream" not in runner, "managed scripts must be delivered through Termux stdin, not shared payload files", errors)
    contains_all(result_service, ("ExtraRunId", "ExtraJobId", "ExtraProcessToken", "TermuxResultRouterProvider"), "result routing", errors)
    contains_all(shell, (
        "setsid", "processGroup", "owner_mismatch", "force_required", "kill -TERM", "TermuxProcessControlAction.ForceCancel -> \"KILL\"",
        "XDM_TOOL_VERSION", "phase=preflight", "ffprobe -v error", "yt-dlp --simulate",
        "--force-overwrites", " -y -i ", "-progress", "XDM_YTDLP",
    ), "managed shell", errors)
    require(" -n -i " not in shell, "managed output must not reject its own newly allocated staging file", errors)
    contains_all(run_store, ("PrivacyDiagnosticsRedactor.redactText", "parseProbe", "toolRows"), "Termux diagnostics", errors)

    contains_all(app, ("recoverInterruptedJobs", "startAutomaticProcessing", "terminalEvents.collect"), "application wiring", errors)
    contains_all(auto, ("reconcileMissedTerminalEvents", "findDownloadsByStates", "attemptGenerationForDownload", "AutomaticCaptureStates"), "durable startup reconciliation", errors)
    contains_all(vm, ("pauseTermuxMediaJob", "resumeTermuxMediaJob", "cancelTermuxMediaJob", "forceCancelTermuxMediaJob", "recoverTermuxMediaPublication", "retryTermuxMediaJob"), "ViewModel controls", errors)
    contains_all(ui, ("Force owned process", "Publish staged output", "New attempt", "attempt", "PID"), "durable job UI", errors)
    contains_all(contract, ("preflightRequiresFreshVerifiedToolsAndAdvertisedMuxers", "immutableSpecificationRoundTripPreservesResultModeAndInputFacts", "event.attemptGeneration", "preparePublication", "publishPrepared"), "unit contract", errors)
    gap_contract = text(root, "app/src/test/kotlin/com/mikeyphw/xdm/android/PostProcessingPhase7GapClosureContractTest.kt", errors)
    contains_all(gap_contract, ("signedUrlsAreNeverPersistedAsVariantUrls", "metadataSanitizerRedactsNestedCredentials"), "gap-closure unit contract", errors)
    contains_all(migration_test, ("MigrationTestHelper", "Migration16To17", "Migration15To16", "Migration14To15", "runMigrationsAndValidate", "post_processing_jobs", "post_processing_claims"), "migration test", errors)
    contains_all(documentation, ("Durable execution model", "Artifact and secret boundary", "Process ownership and controls", "Environment limitation"), "documentation", errors)

    schema_path = root / "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/17.json"
    if not schema_path.is_file():
        errors.append("missing Room schema export 17.json")
    else:
        try:
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            require(schema.get("database", {}).get("version") == 17, "schema export version is not 17", errors)
            names = {entity.get("tableName") for entity in schema.get("database", {}).get("entities", [])}
            require({"post_processing_jobs", "post_processing_claims"}.issubset(names), "schema 17 is missing post-processing tables", errors)
            require(bool(schema.get("database", {}).get("identityHash")), "schema 17 identity hash is missing", errors)
        except (OSError, ValueError) as exc:
            errors.append(f"invalid schema 17 JSON: {exc}")

    for source in root.rglob("*"):
        if source.is_file() and source.suffix in {".kt", ".kts", ".xml", ".json", ".py", ".md", ".sh"}:
            if b"\x00" in source.read_bytes():
                errors.append(f"embedded NUL byte: {source.relative_to(root)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".")
    args = parser.parse_args()
    root = locate_android_root(Path(args.root))
    errors = validate(root)
    if errors:
        print(f"Phase 7 validation FAILED ({len(errors)} issue(s))")
        for item in errors:
            print(f"- {item}")
        return 1
    print("Phase 7 validation PASSED")
    print(f"Android root: {root}")
    print("Durable automation, Room schema 17, exact process ownership, provider bridging, transactional publication, preflight, retry, recovery, UI, and regression contracts are present.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
