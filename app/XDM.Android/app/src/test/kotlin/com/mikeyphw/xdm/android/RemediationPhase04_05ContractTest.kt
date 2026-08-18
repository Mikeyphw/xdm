package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level regression seal for master remediation overlays 04 + 05. */
class RemediationPhase04_05ContractTest {
    private val root = androidRoot()

    @Test fun requestSecurityUsesExactDurableApprovalAcrossStartsMigrationMirrorsAndMediaProbes() {
        val request = source("transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt")
        val guard = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidTransferRequestSecurityGuard.kt")
        val migration = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt")
        val probe = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt")
        val native = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
        val handoff = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt")

        listOf("DownloadRequestKind", "Torrent", "Magnet", "Metalink", "DownloadRequestApprovalScope", "privateNetworkApprovalScopes", "cleartextCredentialApprovalScopes")
            .forEach { assertTrue("request contract missing $it", request.contains(it)) }
        assertTrue(guard.contains("request.mirrors.forEach"))
        assertTrue(guard.contains("approvalScope in request.privateNetworkApprovalScopes"))
        assertTrue(guard.contains("approvalScope in request.cleartextCredentialApprovalScopes"))
        assertTrue(guard.contains("NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)"))
        assertTrue(guard.contains("InetAddress.getAllByName(host)"))
        assertTrue(guard.contains("normalized !in ALLOWED_HEADERS"))
        assertTrue(migration.contains("MediaRequestHandoffStore.forDownload(id)"))
        assertTrue(migration.contains("privateNetworkApprovalScopes = handoff?.privateNetworkApprovalScopes.orEmpty()"))
        assertTrue(migration.contains("requestKind = handoff?.requestKind ?: inferDownloadRequestKind(exactUrl)"))
        assertTrue(migration.contains("mirrors = handoff?.mirrors.orEmpty()"))
        assertTrue(handoff.contains("requestKind: DownloadRequestKind"))
        assertTrue(handoff.contains("mirrors: List<String>"))
        assertTrue(handoff.contains("durableStore.put(handoff.toEnvelope(subjectId))"))
        assertTrue(probe.contains("securityValidator("))
        assertTrue(probe.contains("instanceFollowRedirects = false"))
        assertTrue(native.contains("HTTPS-to-HTTP redirect blocked"))
        assertTrue(native.contains("lastObservedScheme.getAndSet(scheme)"))
        assertTrue(native.contains("NativeRequestSecurityDns"))
        assertTrue(native.contains("Dns.SYSTEM.lookup(hostname)"))
        assertTrue(native.contains("chain.connection()?.route()?.socketAddress?.address"))
        assertTrue(native.contains("targetScope !in context.privateNetworkApprovalScopes"))
        assertTrue(handoff.indexOf("durableStore.put") < handoff.indexOf("cache[subjectId] = handoff"))
    }

    @Test fun legacySensitiveMigrationFailsClosedAndDoesNotInheritOldApproval() {
        val migration = source("app/src/main/kotlin/com/mikeyphw/xdm/android/SensitivePersistenceMigrator.kt")
        val parser = source("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt")
        assertTrue(migration.contains("sensitive-persistence-v2.complete"))
        assertTrue(migration.contains("promoteLegacyRequestMaterial(rootValue)"))
        assertTrue(migration.indexOf("promoteLegacyRequestMaterial(rootValue)") < migration.indexOf("redactJson(rootValue)"))
        assertTrue(migration.contains("privateNetworkApproved = false"))
        assertTrue(migration.contains("cleartextCredentialsApproved = false"))
        assertTrue(migration.contains("check(failures.isEmpty())"))
        assertTrue(migration.contains("AtomicFile(target)"))
        assertTrue(migration.contains("finishWrite(output)"))
        assertTrue(migration.contains("failWrite(output)"))
        assertTrue(migration.indexOf("scrubJsonSidecars") < migration.indexOf("writeMarkerAtomically()"))
        assertTrue(parser.contains("CurrentVersion"))
        assertTrue(parser.contains("UnsafeEnvelope"))
    }

    @Test fun queueAdmissionAndPauseAllAuthorityAreDurableAndAtomic() {
        val dao = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt")
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
        val gate = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/DurableQueueAdmissionGate.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")

        assertTrue(dao.contains("@Transaction\n    suspend fun claimQueueSlot"))
        assertTrue(dao.contains("SET state = 'Connecting'"))
        assertTrue(dao.contains("queueId IS NULL OR queueId = 'default'"))
        assertTrue(dao.contains("attemptGeneration = :attemptGeneration"))
        assertTrue(dao.contains("suspend fun releaseQueueLaunchClaim("))
        assertTrue(dao.contains("expectedQueueClaimToken: Long"))
        assertTrue(dao.contains("current.updatedAtEpochMs != expectedQueueClaimToken"))
        assertTrue(dao.contains("expectedQueueClaimToken + 1L"))
        assertTrue(dao.contains("expectedUpdatedAtEpochMs = expectedQueueClaimToken"))
        assertTrue(coordinator.contains("repository.claimQueueSlotAtomically("))
        assertTrue(coordinator.contains("admissionGate.currentHold()"))
        assertTrue(coordinator.contains("admissionGate.clearPauseAll()"))
        assertTrue(coordinator.contains("authorizeClaimedExecution"))
        assertTrue(coordinator.contains("current.state != DownloadState.Connecting"))
        assertTrue(coordinator.contains("current.updatedAtEpochMs != queueClaimToken"))
        assertTrue(coordinator.contains("download.updatedAtEpochMs + 1L"))
        assertTrue(gate.contains("commit = true"))
        assertTrue(viewModel.contains("queueIntelligenceCoordinator.pauseAllDurably()"))
        assertTrue(viewModel.contains("queueIntelligenceCoordinator.resumeAllManual()"))
    }

    @Test fun restartControlAndAndroidExecutionOwnersRemainBoundToDurableState() {
        val runtime = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        val starter = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionStarter.kt")
        val launchPolicy = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferLaunchPolicy.kt")
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        val job = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt")
        val receiver = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferActionReceiver.kt")
        val ids = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferSystemIdRegistry.kt")
        val ownerClaims = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidExecutionClaimRegistry.kt")

        assertTrue(runtime.contains("resolveBackendControl"))
        assertTrue(runtime.contains("reconciler.reconcile(download.id)"))
        assertTrue(runtime.contains("BackendReconciliationClassification.ActiveTaskVerified"))
        assertTrue(runtime.contains("state = DownloadState.RecoveryRequired"))
        assertFalse(runtime.contains("suspend fun resumeAll()"))
        assertFalse(runtime.contains("suspend fun resume(downloadId: String)"))
        assertFalse(runtime.contains("requestPauseAllAsync"))
        assertTrue(runtime.contains("private fun launch(downloadId: String)"))
        assertTrue(runtime.contains("private fun Download.nextUpdatedAt"))
        assertTrue(runtime.contains("maxOf(nowEpochMs, updatedAtEpochMs + 1L)"))
        assertTrue(launchPolicy.contains("sdkInt >= 34 && userVisible -> TransferLaunchMode.UserInitiatedJob"))
        assertTrue(launchPolicy.contains("else -> TransferLaunchMode.WorkManager"))
        assertTrue(starter.contains("QueueIntelligenceWorker.enqueueClaimed"))
        assertTrue(worker.contains("claimedWorkName(downloadId, queueClaimToken)"))
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(worker.contains("\$CLAIMED_PREFIX\$downloadId-c\$queueClaimToken"))
        assertTrue(worker.contains("attemptGeneration = durableGeneration"))
        assertTrue(worker.contains("activeAttemptGenerationOwned(downloadId, queueClaimToken)"))
        assertFalse(worker.contains("else runtime.pauseAll()"))
        val schedulerManifest = source("scheduler/src/main/AndroidManifest.xml")
        assertTrue(schedulerManifest.contains("androidx.work.impl.foreground.SystemForegroundService"))
        assertTrue(schedulerManifest.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(schedulerManifest.contains("tools:node=\"merge\""))
        assertTrue(worker.contains("authorizeClaimedExecution(claimedDownloadId, queueClaimToken)"))
        assertTrue(worker.contains("authorizeClaimedExecution(download.id, download.updatedAtEpochMs)"))
        assertTrue(starter.contains("putExtra(EXTRA_QUEUE_CLAIM_TOKEN, queueClaimToken)"))
        assertTrue(starter.contains("internal fun start("))
        assertFalse(starter.contains("startFromNotification"))
        assertTrue(worker.contains("terminalIfFirst"))
        assertTrue(worker.contains("setInitialDelay(delay, TimeUnit.MILLISECONDS)"))
        assertTrue(job.contains("EXTRA_QUEUE_CLAIM_TOKEN"))
        assertTrue(job.contains("queue.authorizeClaimedExecution(downloadId, queueClaimToken)"))
        assertTrue(job.contains("activeAttemptGenerationOwned(downloadId, queueClaimToken)"))
        assertTrue(job.contains("requestPauseOwnedAsync(downloadId, queueClaimToken)"))
        val service = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
        assertTrue(service.contains("ownedClaims"))
        assertTrue(service.contains("runtime.pauseOwned(downloadId, queueClaimToken)"))
        assertTrue(service.contains("runtime.requestPauseOwnedAsync(downloadId, queueClaimToken)"))
        assertFalse(service.contains("runtime.requestPauseAllAsync()"))
        assertFalse(receiver.contains("startForegroundService"))
        assertTrue(receiver.contains("ACTION_PAUSE_ALL -> if (queue != null)"))
        assertTrue(ids.contains("synchronized(PROCESS_LOCK)"))
        assertTrue(ids.contains("preferences.edit(commit = true)"))
        assertTrue(ownerClaims.contains("withCurrentClaim"))
        assertTrue(ownerClaims.contains("bindAttemptGeneration(downloadId: String, queueClaimToken: Long"))
        assertTrue(ownerClaims.contains("queueClaimToken"))
        assertTrue(runtime.contains("pauseOwned(downloadId: String, queueClaimToken: Long)"))
        assertTrue(runtime.contains("execute(downloadId: String, queueClaimToken: Long)"))
    }

    @Test fun startupRetryAndQueuePolicyFailClosed() {
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        val restore = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferRestoreWorker.kt")
        val codec = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueuePolicyCodec.kt")
        val conditions = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidQueueConditionsReader.kt")
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueIntelligence.kt")
        val retry = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueRetryLedger.kt")

        assertTrue(app.contains("installStartupRecoveryHold()"))
        assertTrue(app.contains("transferRuntime.recoverForStartup()"))
        assertTrue(app.contains("clearStartupRecoveryHold()"))
        assertTrue(restore.contains("runtime.recoverForStartup()"))
        assertTrue(restore.contains("queue.installStartupRecoveryHold()"))
        assertTrue(restore.contains("queue.clearStartupRecoveryHold()"))
        assertTrue(source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/StartupRecoveryCoordinator.kt").contains("maxOf(clock(), download.updatedAtEpochMs + 1L)"))
        assertTrue(source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/BackendMigrationCoordinator.kt").contains("private fun Download.nextUpdatedAt"))
        assertTrue(codec.contains("isDaysExpression(days)"))
        assertTrue(codec.contains("if (normalized.isBlank()) return false"))
        assertTrue(codec.contains("Invalid schedule configuration — review required"))
        assertTrue(conditions.contains("availableBytesForDestination(destinationUri)"))
        assertTrue(conditions.contains("public-downloads://"))
        assertTrue(model.contains("val battery = conditions.batteryPercent") && model.contains("if (battery == null)"))
        assertTrue(model.contains("val availableStorage = conditions.availableStorageBytes") && model.contains("if (availableStorage == null)"))
        assertTrue(retry.contains("attemptGeneration"))
        assertTrue(retry.contains("MessageDigest.getInstance(\"SHA-256\")"))
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
