package com.mikeyphw.xdm.android.scheduler

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class Xar09SchedulerRecoveryContractTest {
    private val root: Path = Path.of(System.getProperty("user.dir"))

    private fun source(relative: String): String {
        val candidates = listOf(root.resolve(relative), root.parent?.resolve(relative)).filterNotNull()
        val file = candidates.firstOrNull { it.exists() } ?: error("Missing source contract: $relative")
        return file.readText()
    }

    @Test
    fun xar09NamesEverySchedulerRecoveryBoundary() {
        val report = source("docs/remediation/XAR09-SCHEDULER-RECOVERY.md")
        listOf(
            "S09-01", "S09-02", "S09-03", "S09-04", "S09-05", "S09-06", "S09-07", "S09-08", "S09-09", "S09-10", "S09-11", "S09-12", "S09-13", "S09-14", "S09-15",
            "DS5-S09-01", "DS5-S09-02", "DS5-S09-03", "DS5-S09-04", "DS5-S09-05", "RERUN56-S09-01",
        ).forEach { assertTrue("missing canonical finding $it", report.contains(it)) }
        assertTrue(report.contains("21/21"))
    }

    @Test
    fun startupBootAndPackageRestoreShareARecoveryLease() {
        val lease = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/SchedulerRecoveryLeaseCoordinator.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        val restore = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferRestoreWorker.kt")
        assertTrue(lease.contains("tryAcquire"))
        assertTrue(app.contains("tryAcquire(\"application-startup\")"))
        assertTrue(restore.contains("tryAcquire(\"restore-worker\")"))
        assertTrue(restore.indexOf("if (recovery.admissionSafe)") < restore.indexOf("notifyRestored"))
    }

    @Test
    fun executionOwnerFailuresReleaseClaimsAndWakeQueue() {
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        val uidt = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt")
        val fgs = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
        assertTrue(coordinator.contains("releaseFailedExecutionOwner"))
        assertTrue(coordinator.contains("recordImmediateReevaluation(\"execution-owner-release\""))
        assertTrue(worker.contains("releaseFailedExecutionOwner"))
        assertTrue(uidt.contains("releaseFailedExecutionOwner(downloadId, queueClaimToken"))
        assertTrue(fgs.contains("releaseFailedExecutionOwner(id, queueClaimToken"))
    }

    @Test
    fun terminalNotificationsAreFencedByRequestIdentity() {
        val models = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueStateMachineModels.kt")
        val notifications = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
        val runtime = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        assertTrue(models.contains("val requestIdentity: String = \"\""))
        assertTrue(models.contains("${'$'}{key.requestIdentity}"))
        assertTrue(notifications.contains("requestIdentity: String = \"\""))
        assertTrue(runtime.contains("terminalRequestIdentity"))
    }

    @Test
    fun retryLedgerIdentityIncludesRequestAndBackend() {
        val retry = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueRetryLedger.kt")
        assertTrue(retry.contains("download.sourceUrl"))
        assertTrue(retry.contains("download.destinationUri"))
        assertTrue(retry.contains("download.backend.name"))
        assertTrue(retry.contains("putBoolean(prefix + \"secureRequired\", secureContextPresent)"))
    }

    @Test
    fun durableImmediateWakeupsAreConsumedAndLogsCompact() {
        val queue = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt")
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        assertTrue(queue.contains("reevaluate-consumed"))
        assertTrue(queue.contains("consumeImmediateReevaluations"))
        assertTrue(queue.contains("compactLogIfNeededLocked"))
        assertTrue(worker.contains("PRECISION_WAKEUP_TAG"))
    }

    @Test
    fun deletionRetiresSystemIdsAndTermuxMediaOutputsTogether() {
        val registry = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferSystemIdRegistry.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val termux = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt")
        assertTrue(registry.contains("fun retire(downloadId: String)"))
        assertTrue(viewModel.contains("retireAndroidSystemId"))
        assertTrue(termux.contains("repository.hideMediaOutput(\"media-output:termuxjob:${'$'}{job.id}:${'$'}{job.attemptGeneration}\")"))
    }
}
