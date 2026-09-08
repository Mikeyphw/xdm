#!/usr/bin/env python3
from pathlib import Path
import json, sys
ROOT=Path(__file__).resolve().parents[1]

def text(rel): return (ROOT/rel).read_text()
def need(cond,msg):
    if not cond:
        print(f"ERROR: {msg}", file=sys.stderr); raise SystemExit(1)

models=text('transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeTransferModels.kt')
integrity=text('transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeCheckpointIntegrity.kt')
native=text('transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt')
poller=text('transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2EventPoller.kt')
runtime=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt')
verify=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletionVerificationCoordinator.kt')
throttle=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/VerificationProgressThrottle.kt')
main=text('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
screen=text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt')
workspace=text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt')
experience=text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloaderExperience.kt')
truth=text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadUiTruth.kt')
api=text('transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt')
manifest=json.loads(text('PROJECT_MANIFEST.json'))

need('checkpointIntegrityBlockBytes' in models and 'integrityProof' in models, 'fixed-block checkpoint model missing')
need('sha256-blocks-v1' in integrity and 'CachedDigest' in integrity, 'incremental block proof implementation missing')
need('checkpointSaveMutex = Mutex()' in native and 'stateMutex.withLock { segments.map { it.copy() } }' in native, 'checkpoint hashing is not separated from progress mutex')
need('bytesAtAttemptStart' not in native and 'NativeRollingSpeedMeter' in native and 'speedMeter.record(totalReceived, clock())' in native, 'task-wide rolling speed not sealed')
need('.buffer(Channel.CONFLATED)' in poller, 'aria2 poller is still downstream-backpressured')
need('DURABLE_PROGRESS_INTERVAL_MS = 333L' in runtime and 'val liveProgress:' in runtime, 'live/durable transfer split missing')
publish_body=runtime.split('    private suspend fun publish(',1)[1].split('    private suspend fun quarantineInterruptedFinalization',1)[0]
need('ownershipStore.findByDownload' not in publish_body, 'live publish path still performs durable ownership lookup per snapshot')
need('PublicationFence' in runtime and 'durableOwnershipMismatch(publicationFence)' in runtime, 'publication fence / terminal durable ownership revalidation missing')
need('current.attemptGeneration != publicationFence.generation' in publish_body, 'durable progress writes are not fenced by attempt generation')
need('completedArtifactGeneration = publicationFence.generation' in runtime, 'completed artifact generation is not bound to the validated publication fence')
need('completedArtifactGeneration = ownership.generation' not in runtime, 'stale pre-publication-fence completion generation assignment remains')
completed_contract = text('scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedNotificationOpenFileContractTest.kt')
remediation_contract = text('app/src/test/kotlin/com/mikeyphw/xdm/android/RemediationPhase06_07ContractTest.kt')
bug_hunt_phase2 = text('app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase2DownloadExecutionContractTest.kt')
need('completedArtifactGeneration = publicationFence.generation' in completed_contract, 'notification completion contract was not carried forward to publication-fence generation')
need('completedArtifactGeneration = publicationFence.generation' in remediation_contract, 'remediation completion contract was not carried forward to publication-fence generation')
need('bytesAtAttemptStart' not in native, 'attempt-local native speed baseline remains in production')
need('Task speed must not reset to an attempt-local baseline.' in bug_hunt_phase2 and 'NativeRollingSpeedMeter' in bug_hunt_phase2, 'Phase 2 native contract was not carried forward to task-wide rolling speed')
need('verifyPersistedSegment(paths.partial, segment)' in remediation_contract and 'NativeCheckpointIntegrity.verify(path, segment, ::sha256Range)' in remediation_contract, 'remediation resume contract was not carried forward to incremental integrity proofs')
need('Native checkpoint range has no byte digest' not in remediation_contract, 'stale whole-prefix checkpoint assertion remains in remediation contract')
need('VerificationProgressThrottle()' in verify and 'minIntervalMillis: Long = 350L' in throttle and 'minBytesDelta: Long = 4L * 1024L * 1024L' in throttle, 'verification durable throttle missing')
need('semanticDownloads = repository.downloads.distinctUntilChangedBy' in main and 'private val durableUiState' in main and 'liveTransferUi' in main, 'broad UI invalidation split missing')
need('artifactInspectionKeys' in screen and 'resumeInspectionKeys' in screen and 'LaunchedEffect(completedInspectionInputs, resumeInspectionInputs)' in screen, 'artifact inspection semantic cache missing')
need('thenByDescending { it.updatedAtEpochMs }' not in workspace, 'workspace ordering still depends on updatedAt')
active_block=experience.split('DownloadDashboardBucket.Active ->',1)[1].split('\n',1)[0]
need('speedBytesPerSecond' not in active_block and 'updatedAtEpochMs' not in active_block, 'Smart active ordering still depends on live progress')
need('fun phaseProgress' in truth and 'bytes verified' in truth, 'verification UI still reuses payload progress')
for stage in ('Resolving','Preparing','Downloading','Merging','Verifying','Finalizing'):
    need(stage in api, f'missing explicit stage {stage}')
entry=manifest.get('download_progress_dl02_dl03',{})
need(entry.get('room_schema_current') == 21 and entry.get('room_schema_changed') is False, 'DL02/DL03 must retain Room schema 21')
need(entry.get('validation_deferred') is False, 'DL02/DL03 validation cannot be deferred')
print('DL02+DL03 progress seal validator: OK')
