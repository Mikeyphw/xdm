#!/usr/bin/env python3
"""Static contract seal for RM01 Transfer Core / Recovery Integrity.

RM01 exists to prevent a backend from preparing/writing with one attempt generation while durable
ownership/publication is committed under another. The validator intentionally checks the cross-module
ordering contract as well as the regression-test anchors that exercise it.
"""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise AssertionError(f"missing RM01 source: {rel}")
    return path.read_text(encoding="utf-8")


def require(haystack: str, needle: str, label: str) -> None:
    if needle not in haystack:
        raise AssertionError(f"RM01 missing {label}: {needle!r}")


def require_order(haystack: str, needles: list[str], label: str) -> None:
    positions = []
    start = 0
    for needle in needles:
        pos = haystack.find(needle, start)
        if pos < 0:
            raise AssertionError(f"RM01 missing ordered marker for {label}: {needle!r}")
        positions.append(pos)
        start = pos + len(needle)
    if positions != sorted(positions):
        raise AssertionError(f"RM01 ordering violation: {label}")


def main() -> int:
    transfer = text("transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt")
    room = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendOwnershipStore.kt")
    dao = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/BackendOwnershipDao.kt")
    runtime = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
    migration = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt")
    destination = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DestinationProvider.kt")
    file_writer = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/FileDestinationWriter.kt")
    android_writer = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
    native = text("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")

    # 1. Generation is reserved before preparation and then consumed exactly by ownership.
    require(transfer, "suspend fun reserveGeneration(minimumExclusive: Long = 0L): Long", "ownership generation reservation API")
    require(transfer, "reservedGeneration: Long? = null", "reserved generation handoff")
    require_order(
        transfer,
        [
            "val reservedGeneration = ownershipStore.reserveGeneration(minimumGeneration)",
            "return addWithBackend(request, backend, recommendation, reservedGeneration, existingOwnership, beforeActivation)",
        ],
        "coordinator reservation before backend preparation dispatch",
    )
    require_order(
        transfer,
        [
            "attemptGeneration = reservedGeneration",
            "backend.prepareForAdoption(selectedRequest, adoptionSource)",
            "reservedGeneration = reservedGeneration",
            "check(claimedOwnership.generation == reservedGeneration)",
            "val startedTask = backend.add(selectedRequest, preparation)",
            "beforeActivation(coordinated)",
            "if (startedTask.requiresActivation) backend.activate(startedTask.taskId)",
        ],
        "prepare/claim/add/persist-callback/activate generation fence",
    )

    # 2. Room-backed generation allocation stays transactional and monotonic.
    require(dao, "raiseCounterToAtLeast", "Room counter floor update")
    require(room, "override suspend fun reserveGeneration", "Room generation reservation")
    require(room, "reserveGenerationInTransaction", "transactional generation reservation")
    require(room, "generationForClaimInTransaction(reservedGeneration", "exact reserved generation claim/adoption")

    # 3. Runtime persists the exact ownership generation before backend activation.
    require(runtime, "attemptGeneration = maxOf(download.attemptGeneration, mediaHandoff?.attemptGeneration ?: 0L).coerceAtLeast(1L)", "positive request-generation floor")
    require_order(
        runtime,
        [
            "val coordinated = coordinator.add(request) { pending ->",
            "val generation = pending.ownership.generation",
            "check(generation > 0L)",
            "attemptGeneration = generation",
            "persistOrThrow(selected)",
            "selectedForRun = selected",
            "val selected = requireNotNull(selectedForRun)",
        ],
        "durable Download generation binding before coordinator returns",
    )

    # 4. Migration reserves the target generation before preparation and persists it before activation.
    require_order(
        migration,
        [
            "val reservedTargetGeneration = ownershipStore.reserveGeneration",
            "val targetRequest = request.copy(attemptGeneration = reservedTargetGeneration)",
            "val prepared = target.prepare(targetRequest)",
            "reservedGeneration = reservedTargetGeneration",
            "check(targetOwnership.generation == reservedTargetGeneration)",
            "val started = target.add(targetRequest, prepared)",
            "store.saveBackendTask(downloadId, targetBackend, started.taskId, active)",
            "persistOrThrow(",
            "attemptGeneration = targetOwnership.generation",
            "if (started.requiresActivation) target.activate(started.taskId)",
        ],
        "migration target generation fence",
    )

    # 5. Reconciled native artifacts are rebound in-place, never copied solely to advance generation.
    require(destination, "suspend fun prepareExisting(request: DestinationRequest, artifacts: DestinationArtifacts)", "existing staging rebind API")
    require(file_writer, "override suspend fun prepareExisting", "file destination rebind")
    require(android_writer, "override suspend fun prepareExisting", "Android destination rebind")
    require(native, "override suspend fun prepareForAdoption", "native reconciled-artifact adoption")
    require(native, "require(artifacts == previousOwnership.artifacts)", "exact physical artifact identity preservation")
    require(native, "rebindAdoptedCheckpoint(control, ownership)", "checkpoint generation rebind before activation")
    require(native, "attemptGeneration = ownership.generation", "checkpoint rewritten to adopted generation")

    # 6. Regression anchors must remain executable source, including no-redownload publication recovery.
    api_test = text("transfer-api/src/test/kotlin/com/mikeyphw/xdm/android/transfer/BackendCoordinatorTest.kt")
    runtime_test = text("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntimeTest.kt")
    migration_test = text("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinatorTest.kt")
    room_test = text("persistence/src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/BackendOwnershipStoreTest.kt")
    native_test = text("transfer-native/src/test/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackendTest.kt")

    for source, needle, label in [
        (api_test, "generationIsReservedBeforePreparationAndBoundBeforeActivation", "coordinator generation-order regression"),
        (api_test, "beforeActivationFailureNeverStartsPayloadWriterAndPreservesRecoveryOwnership", "pre-activation failure regression"),
        (runtime_test, "executionPersistsReservedGenerationBeforeBackendActivation", "runtime persistence-before-activation regression"),
        (migration_test, "emptySourceTransfersGenerationBeforeTargetActivation", "migration prepared-generation assertion"),
        (room_test, "reservedGenerationIsExactAcrossPreparationClaimAndAdoption", "Room reservation regression"),
        (native_test, "reconciledNativeArtifactsRebindToNewGenerationWithoutPayloadCopy", "native in-place adoption regression"),
        (native_test, "finalSaveRecoveryRetriesPublicationWithoutNetworkRedownload", "publication retry without redownload regression"),
    ]:
        require(source, needle, label)

    manifest = json.loads(text("PROJECT_MANIFEST.json"))
    rm01 = manifest.get("rm01_transfer_core_recovery_integrity")
    if not isinstance(rm01, dict) or rm01.get("status") != "implemented":
        raise AssertionError("PROJECT_MANIFEST.json must mark rm01_transfer_core_recovery_integrity implemented")
    for key in (
        "generation_reserved_before_prepare",
        "download_generation_persisted_before_activation",
        "native_adoption_preserves_physical_artifacts",
        "publication_retry_without_redownload",
        "queue_and_recovery_idempotency_fenced",
    ):
        if rm01.get(key) is not True:
            raise AssertionError(f"RM01 manifest invariant is not sealed: {key}")

    print("RM01 transfer core/recovery integrity contract passed: generation reservation, pre-activation persistence, in-place adoption, migration fencing, and no-redownload recovery are sealed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"RM01 validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
