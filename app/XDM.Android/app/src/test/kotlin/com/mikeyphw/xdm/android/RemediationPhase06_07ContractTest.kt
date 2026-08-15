package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level regression seal for master remediation phases 06 + 07. */
class RemediationPhase06_07ContractTest {
    private val root = androidRoot()

    @Test fun backendMigrationKeepsSourceEvidenceUntilTargetOwnershipIsDurable() {
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt")
        val migrationStore = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendMigrationStore.kt")
        val dao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/BackendMigrationDao.kt")
        val api = source("transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt")

        assertTrue(coordinator.contains("migrationStore.tryCreate(record)"))
        assertTrue(coordinator.contains("requestSecurityGuard.validate(request)"))
        assertTrue(coordinator.contains("sourceBackend.retireForMigration(taskId)"))
        assertTrue(coordinator.contains("target.onOwnershipAttached(started.taskId, active)"))
        assertTrue(coordinator.contains("store.saveBackendTask(downloadId, targetBackend, started.taskId, active)"))
        assertTrue(coordinator.contains("sourceBackend.finalizeMigrationRetirement(taskId)"))
        assertTrue(coordinator.indexOf("requestSecurityGuard.validate(request)") < coordinator.indexOf("sourceBackend.retireForMigration(taskId)"))
        assertTrue(coordinator.indexOf("store.saveBackendTask(downloadId, targetBackend, started.taskId, active)") < coordinator.indexOf("sourceBackend.finalizeMigrationRetirement(taskId)"))
        assertTrue(migrationStore.contains("ACTIVE_MIGRATION_CLAIM"))
        assertTrue(migrationStore.contains("stage in setOf(BackendMigrationStage.Completed, BackendMigrationStage.Failed)"))
        assertTrue(dao.contains("insertIfAbsent"))
        assertTrue(api.contains("finalizeMigrationRetirement"))
    }

    @Test fun nativeResumeBindsExactRepresentationValidatorAndPersistedBytes() {
        val models = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeTransferModels.kt")
        val backend = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
        val repair = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeSelectiveRepairService.kt")
        val checkpointStore = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeCheckpointStore.kt")

        listOf("completedSha256", "sourceIdentitySha256", "effectiveIdentitySha256", "resumeValidatorKind", "resumeValidatorValue")
            .forEach { assertTrue("native checkpoint contract missing $it", models.contains(it)) }
        assertTrue(backend.contains("builder.header(\"If-Range\", metadata.resumeValidator.value)"))
        assertTrue(backend.contains("!trimmedEtag.startsWith(\"W/\", ignoreCase = true)"))
        assertTrue(backend.contains("sha256Range(paths.partial"))
        assertTrue(backend.contains("checkpoint.sourceIdentitySha256 != sha256Identity(request.sourceUrl)"))
        assertTrue(backend.contains("checkpoint.effectiveIdentitySha256 != sha256Identity(metadata.effectiveUrl)"))
        assertTrue(backend.contains("Native checkpoint range has no byte digest"))
        assertTrue(repair.contains("header(\"If-Range\", validator.value)"))
        assertTrue(repair.contains("requestSecurityValidator(request)"))
        assertTrue(repair.contains("manifest: TrustedBlockManifest"))
        assertTrue(repair.contains("repair-backup-"))
        assertTrue(repair.contains("Selective repair requires atomic replacement"))
        assertTrue(repair.contains("fileSha256(backup) == originalDigest"))
        assertTrue(repair.contains("verifyRepairedBlocks(target, plan, manifest)"))
        assertTrue(checkpointStore.contains("completedSha256"))
    }

    @Test fun aria2StateAndCapabilitiesAreTruthfulAndTerminalSafe() {
        val backend = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt")
        val rpc = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RpcClient.kt")
        val session = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2SessionStore.kt")

        assertTrue(backend.contains("supportsProxy = false"))
        assertTrue(backend.contains("supportsMetalink = false"))
        assertTrue(backend.contains("DownloadRequestKind.Torrent") && backend.contains("DownloadRequestKind.Metalink"))
        assertTrue(backend.contains("TERMINAL_MAPPING_STATES"))
        assertTrue(backend.contains("MAPPING_RETIRED_FOR_MIGRATION"))
        assertTrue(backend.contains("Terminal aria2 mappings cannot be resumed"))
        assertTrue(backend.contains("finalizeMigrationRetirement"))
        assertFalse(rpc.contains("toLongOrNull() ?: 0"))
        assertTrue(session.contains("AtomicFile(target)"))
        assertTrue(session.contains("output.fd.sync()"))
        assertTrue(session.contains("finishWrite(output)"))
        assertTrue(session.contains("failWrite(output)"))
    }

    @Test fun storagePublicationIsGenerationBoundAndDoesNotOverwriteProviderDataInPlace() {
        val writer = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
        val destination = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DestinationProvider.kt")
        val safety = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PublicationSafety.kt")

        assertTrue(destination.contains("val attemptGeneration: Long"))
        assertTrue(destination.contains("requiresPublicationCopy"))
        assertTrue(destination.contains("publicationJournalPath"))
        assertTrue(writer.contains("attemptGeneration = request.attemptGeneration"))
        assertTrue(writer.contains("DestinationCommitInProgress"))
        assertTrue(writer.contains("publicationJournalPath = artifacts.journalFile.absolutePath"))
        assertTrue(writer.contains("cannot prove crash-safe replacement"))
        assertTrue(writer.contains("selected document already contains data and cannot be replaced crash-safely"))
        assertTrue(writer.contains("MediaStore.MediaColumns.IS_PENDING, 1"))
        assertTrue(writer.contains("MediaStore.MediaColumns.IS_PENDING, 0"))
        assertTrue(writer.contains("IS_PENDING}=0"))
        assertTrue(writer.contains("Raw filesystem destinations are restricted"))
        assertTrue(safety.contains("attemptGeneration"))
        assertTrue(safety.contains("requiredBytesForPublication"))
        assertTrue(safety.contains("publicationCopy"))
        assertTrue(safety.contains("FileChannel.open(parent.toPath(), StandardOpenOption.READ)"))
    }

    @Test fun completionUsesAtomicFinalizationAndGenerationBoundArtifactIdentity() {
        val runtime = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        val finalization = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AtomicFinalizationCoordinator.kt")
        val verification = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletionVerificationCoordinator.kt")
        val reader = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedArtifactReader.kt")
        val grants = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedFileGrantPolicy.kt")
        val postProcessing = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt")
        val mediaLibrary = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")

        assertTrue(runtime.contains("finalizationCoordinator.prepareCommitted"))
        assertTrue(runtime.contains("state = DownloadState.Verifying"))
        assertTrue(runtime.indexOf("finalizationCoordinator.prepareCommitted") < runtime.indexOf("completionVerifier.complete(original, snapshot)"))
        assertTrue(runtime.contains("finalizationCoordinator.findIncomplete(downloadId)"))
        assertTrue(runtime.contains("Cancel was requested while validating a committed artifact"))
        assertTrue(runtime.contains("Pause was requested while validating a committed artifact"))
        assertTrue(runtime.contains("quarantineInterruptedFinalization"))
        assertTrue(runtime.contains("retirePublicationJournal(verifiedSnapshot.publicationJournalPath)"))
        assertTrue(runtime.contains("val journalClosed = runCatching"))
        assertTrue(runtime.contains("Completion metadata is authoritative once durably committed"))
        assertTrue(runtime.contains("Completed artifact metadata is durable, but backend ownership cleanup still requires reconciliation"))
        assertTrue(runtime.contains("finalizationCoordinator.recordDestinationCommitted"))
        assertTrue(runtime.contains("finalizationCoordinator.recordMetadataCommitted"))
        assertTrue(runtime.contains("completedArtifactUri = committedUri"))
        assertTrue(runtime.contains("completedArtifactGeneration = ownership.generation"))
        assertTrue(runtime.contains("stored.completedArtifactGeneration == stored.attemptGeneration"))
        assertTrue(runtime.contains("stored.completedArtifactUri"))
        assertTrue(finalization.contains("prepareCommitted"))
        assertTrue(finalization.contains("findIncomplete(downloadId: String)"))
        assertTrue(finalization.contains("Verification completed for the committed artifact."))
        val startupRecovery = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/StartupRecoveryCoordinator.kt")
        assertTrue(startupRecovery.contains("scanPublicationJournals(records)"))
        assertTrue(startupRecovery.contains("Recovered a storage publication journal after destination commit"))
        assertTrue(startupRecovery.contains("DestinationCommitInProgress"))
        assertTrue(startupRecovery.contains("stagingPath = if (commitProven) null else evidence.stagingPath"))
        assertTrue(startupRecovery.contains("Startup reconciled the finalization journal from already-durable completed artifact metadata"))
        assertTrue(verification.contains("CompletedArtifactReader"))
        assertTrue(reader.contains("ContentResolver.SCHEME_CONTENT"))
        assertTrue(grants.contains("download.completedArtifactGeneration != download.attemptGeneration"))
        assertTrue(postProcessing.contains("download.completedArtifactUri"))
        assertTrue(postProcessing.contains("download.completedArtifactGeneration == attemptGeneration"))
        assertTrue(mediaLibrary.contains("download.completedArtifactUri"))
        assertTrue(mediaLibrary.contains("download.completedArtifactGeneration != download.attemptGeneration"))
        val mainViewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        assertTrue(mainViewModel.contains("liveValidatedDestinationUris"))
        assertTrue(mainViewModel.contains("revalidatedDestinationPermissions"))
        assertTrue(mainViewModel.contains("destinationWriter.canWrite"))
        assertFalse(mediaLibrary.contains("private fun completedPlaybackUrl(download: Download): String? = if (download.destinationUri"))
    }

    @Test fun notificationsRecoveryAndFreshRestartUseExactDurableIdentity() {
        val activity = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
        val notifications = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val screens = source("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")

        assertTrue(activity.contains("if (savedInstanceState == null) consumeLaunchIntent(intent)"))
        assertTrue(activity.contains("override fun onNewIntent(intent: Intent)"))
        assertTrue(activity.contains("ACTION_OPEN_DOWNLOAD_DETAILS"))
        assertTrue(activity.contains("ACTION_REVIEW_RECOVERY"))
        assertTrue(notifications.contains("fields.getOrNull(1) == record.idempotencyKey"))
        assertTrue(viewModel.contains("when (record.recommendedAction)"))
        assertTrue(viewModel.contains("createFreshRedownload(current, startImmediately = false)"))
        assertTrue(viewModel.contains("destinationUri = originalDestination"))
        assertTrue(viewModel.contains("completedArtifactUri = null"))
        assertTrue(viewModel.contains("completedArtifactGeneration = null"))
        assertTrue(viewModel.contains("completedArtifactBytes = null"))
        assertTrue(viewModel.indexOf("createFreshRedownload(current, startImmediately = false)") < viewModel.indexOf("repository.deleteFinalizationForDownload(current.id)"))
        assertTrue(screens.contains("Review repair requirements"))
        assertTrue(screens.contains("Review adoption requirements"))
        assertTrue(screens.contains("Review locate options"))
    }

    @Test fun roomSchemaTwentyPreservesNineteenAndAddsMediaOutputHistory() {
        val database = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
        val migrations = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt")
        val entities = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        val schema = source("persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/19.json")
        val schema20 = source("persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/20.json")

        assertTrue(database.contains("version = 20"))
        assertTrue(migrations.contains("object Migration18To19"))
        assertTrue(migrations.contains("completedArtifactUri"))
        assertTrue(migrations.contains("completedArtifactGeneration"))
        assertTrue(migrations.contains("completedArtifactBytes"))
        assertTrue(migrations.contains("activeClaim"))
        assertTrue(entities.contains("index_backend_migrations_downloadId_activeClaim") || schema.contains("index_backend_migrations_downloadId_activeClaim"))
        assertTrue(app.contains("Migration18To19"))
        assertTrue(app.contains("Migration19To20"))
        assertTrue(migrations.contains("Migration19To20"))
        assertTrue(schema.contains("\"version\": 19"))
        assertTrue(schema20.contains("\"version\": 20"))
        assertTrue(schema20.contains("media_outputs"))
    }


    @Test fun destinationAndRecoverySurfacesUseLiveProviderAndReconciliationTruth() {
        val provider = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DestinationProvider.kt")
        val native = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
        val aria2 = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt")
        val recovery = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/StartupRecoveryCoordinator.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val add = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        val recoveryUi = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/recovery/RecoveryScreen.kt")

        assertTrue(provider.contains("interface DestinationWriter : DestinationProvider"))
        assertTrue(provider.contains("override suspend fun canWrite(destinationUri: String)"))
        assertTrue(native.contains("destinationWriter.canWrite(request.destinationUri)"))
        assertTrue(aria2.contains("destinationWriter.canWrite(request.destinationUri)"))
        assertTrue(viewModel.contains("refreshSavedDestinations()"))
        assertTrue(viewModel.contains("destinationWriter.canWrite(uri)"))
        assertTrue(viewModel.contains("Direct filesystem engine probes are not applicable to this destination type"))
        assertTrue(add.contains("it.persistedWrite && it.status == com.mikeyphw.xdm.android.model.DestinationHealthStatus.Healthy"))
        assertTrue(add.contains("\${destination.displayName} · \${destination.type.uiLabel()}"))
        assertTrue(recovery.contains("activeScannerIds"))
        assertTrue(recovery.contains("recoveryStore.deleteRecovery(it.id)"))
        assertTrue(recoveryUi.contains("Forget unresolved recovery record?"))
        assertTrue(recoveryUi.contains("confirmForget = true"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
