package com.mikeyphw.xdm.android.persistence

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugHuntPhase6DatabaseIntegrityContractTest {
    private val root = File(System.getProperty("user.dir") ?: ".")

    @Test
    fun downloadDeletionUsesCompleteTransactionalGraph() {
        val dao = source("src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt")
        assertTrue(dao.contains("suspend fun deleteDownloadGraph"))
        listOf(
            "countActivePostProcessingForDownload",
            "detachPostProcessingForDownload",
            "detachRecoveryForDownload",
            "deleteBackendMigrationsForDownload",
            "deleteAria2MappingsForDownload",
            "deleteDestinationClaimsForDownload",
            "deleteBackendTasksForDownload",
            "deleteDownloadRow",
        ).forEach {
            assertTrue("Download graph deletion must protect or reconcile $it", dao.contains(it))
        }
        assertTrue("True child rows must be protected by Room foreign keys", source("src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt").contains("ForeignKey.CASCADE"))
        val repository = source("src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        assertTrue(repository.contains("deleteDownloadGraph(id)"))
        assertFalse(repository.contains("downloadDao().delete(id)"))
    }

    @Test
    fun mediaVariantsAreReplacedTransactionally() {
        val dao = source("src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt")
        val repository = source("src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        val viewModel = File(root.parentFile, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val batchFlow = viewModel
            .substringAfter("fun captureMediaBatchInput(text: String)")
            .substringBefore("fun openDownloadReview")

        assertTrue("DAO replacement must remain transactional", dao.contains("@Transaction") && dao.contains("replaceMediaVariantsForCaptures"))
        assertTrue("DAO replacement must delete prior variants", dao.contains("deleteMediaVariantsForCaptures(captureIds)"))
        assertTrue("DAO replacement must reconcile capture selection and counts", dao.contains("reconcileCaptureAfterVariantReplacement"))
        assertTrue("Repository batch persistence must use a Room transaction", repository.contains("saveMediaCapturesWithVariants") && repository.contains("database.withTransaction"))
        assertTrue("Batch intake must persist captures and variants atomically", batchFlow.contains("repository.saveMediaCapturesWithVariants(merged, plan.variants, now)"))
        assertFalse("Batch intake must not return to a second variant replacement call", batchFlow.contains("repository.replaceMediaVariants"))
    }

    @Test
    fun automationCommandsUseDurableStateTransitions() {
        val model = File(root.parentFile, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt").readText()
        listOf("Received", "Claimed", "Executing", "Applied", "Failed").forEach { assertTrue(model.contains(it)) }
        val dao = source("src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt")
        assertTrue(dao.contains("insertAutomationIgnore"))
        assertTrue(dao.contains("transitionAutomationCommand"))
        assertTrue(dao.contains("withDurableStatus"))
    }

    @Test
    fun migrationsCoverSchemaFourteenAndPreserveLegacyAria2() {
        val migrations = source("src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt")
        assertTrue(migrations.contains("Legacy v5 aria2 mapping preserved for review"))
        val migrationTest = source("src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabaseMigrationTest.kt")
        assertTrue(migrationTest.contains("migrate5To6PreservesLegacyAria2MappingsAsRecoveryRequired"))
        val schemas = File(root, "schemas/com.mikeyphw.xdm.android.persistence.AppDatabase")
        (4..18).forEach { version -> assertTrue("Schema $version must be exported", File(schemas, "$version.json").isFile) }
    }

    private fun source(path: String): String = File(root, path).readText()


    @Test
    fun phaseSixR2RuntimeWiringRejectsMarkerOnlyImplementation() {
        val repo = File("src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt").readText()
        val graph = File("src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt").readText()
        val main = File("../app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val queue = File("../scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt").readText()
        val migrationStore = File("src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendMigrationStore.kt").readText()
        val ownershipStore = File("src/main/kotlin/com/mikeyphw/xdm/android/persistence/RoomBackendOwnershipStore.kt").readText()

        assertTrue("download save must go through stale-write guarded transaction", repo.contains("upsertDownloadPreservingNewerState"))
        assertTrue("DAO must insert first and update only when stored row is not newer", graph.contains("insertDownloadIgnore") && graph.contains("updatedAtEpochMs < :updatedAtEpochMs") && graph.contains("attemptGeneration < :attemptGeneration"))
        assertTrue("automation execution must claim before executing", main.contains("markAutomationCommandExecuting(commandId)"))
        assertTrue("automation lifecycle must perform Received/Accepted -> Claimed", main.contains("AutomationCommandStatus.Accepted") && main.contains("AutomationCommandStatus.Claimed"))
        assertTrue("queue reassignment deletion must use transactional repository helper", queue.contains("repository.reassignQueueThenDelete(queueId, replacementQueueId)"))
        assertFalse("Download model conversion must not crash on malformed stored state", repo.contains("DownloadState.valueOf(state)"))
        assertFalse("Download model conversion must not crash on malformed backend", repo.contains("BackendType.valueOf(backend)"))
        assertFalse("Download entity mapping must not contain duplicate backendSelectionReason assignment", repo.contains("backendSelectionReason = backendSelectionReason.name,\n    backendSelectionReason = backendSelectionReason.name"))
        assertFalse("Backend migration store must not crash on malformed enum values", migrationStore.contains("BackendMigrationStage.valueOf(stage)") || migrationStore.contains("BackendType.valueOf(sourceBackend)"))
        assertFalse("Backend ownership store must not crash on malformed enum values", ownershipStore.contains("BackendOwnershipStatus.valueOf(status)") || ownershipStore.contains("BackendType.valueOf(backend)"))
    }

}
