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
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val broker = source("app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidDownloadExecutionBroker.kt")

        assertTrue(viewModel.contains("action = \"pause_all\"") && viewModel.contains("action = \"resume_all\""))
        assertTrue(coordinator.contains("AndroidGoDownloadCommands.submit(appContext, action = \"pause_all\")"))
        assertTrue(coordinator.contains("AndroidGoDownloadCommands.submit(appContext, action = \"resume_all\")"))
        assertFalse(coordinator.contains("claimQueueSlotAtomically(") || coordinator.contains("admissionGate") || coordinator.contains("AndroidExecutionClaimRegistry.install"))
        assertTrue(worker.contains("AndroidSchedulerHost.wake("))
        assertFalse(worker.contains("coordinator.evaluateAndClaim()") || worker.contains("runtime.pauseOwned("))
        assertTrue(broker.contains("\"pause_all\" -> pauseAll()") && broker.contains("\"resume_all\" -> resumeAll()"))
        assertTrue(broker.contains("transferRuntime.pauseAll()") && broker.contains("nativeHls.pauseAll()"))
        assertTrue(broker.contains("nativeHls.resumeAll()") && broker.contains("QueueIntelligenceWorker.enqueueImmediate(appContext)"))
        assertFalse(broker.contains("QueueIntelligenceCoordinator") || broker.contains("QueueRetryLedger") || broker.contains("DurableQueueAdmissionGate"))
    }

    @Test fun restartControlAndAndroidExecutionOwnersRemainBoundToDurableState() {
        val runtime = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
        val starter = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionStarter.kt")
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        val job = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt")
        val receiver = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferActionReceiver.kt")
        val service = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
        val application = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")

        assertTrue(runtime.contains("resolveBackendControl"))
        assertTrue(runtime.contains("reconciler.reconcile(download.id)"))
        assertTrue(runtime.contains("BackendReconciliationClassification.ActiveTaskVerified"))
        assertTrue(runtime.contains("state = DownloadState.RecoveryRequired"))
        assertTrue(starter.contains("QueueIntelligenceWorker.enqueueClaimed"))
        assertTrue(worker.contains("claimedWorkName(downloadId, queueClaimToken)"))
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(worker.contains("\$CLAIMED_PREFIX\$downloadId-c\$queueClaimToken"))
        assertTrue(worker.contains("AndroidSchedulerHost.wake("))
        assertFalse(worker.contains("activeAttemptGenerationOwned") || worker.contains("authorizeClaimedExecution(") || worker.contains("runtime.pauseOwned("))
        assertTrue(job.contains("AndroidSchedulerHost.wake("))
        assertTrue(job.contains("AndroidEngineWakeReason.USER_INITIATED_DATA_TRANSFER"))
        assertFalse(job.contains("activeAttemptGenerationOwned") || job.contains("requestPauseOwnedAsync"))
        assertTrue(service.contains("AndroidSchedulerHost.wake("))
        assertTrue(service.contains("submitGoCommand(\"pause_all\")") && service.contains("submitGoCommand(\"cancel\""))
        assertFalse(service.contains("runtime.pauseOwned(") || service.contains("runtime.requestPauseOwnedAsync("))
        assertTrue(receiver.contains("AndroidGoDownloadCommands.submit("))
        assertFalse(receiver.contains("startForegroundService") || receiver.contains("recordNotificationControlCommand"))
        assertFalse(application.contains("TransferExecutionStarter("))
        assertFalse(application.contains("transferRuntime.recoverForStartup()"))
    }

    @Test fun startupRetryAndQueuePolicyFailClosed() {
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        val restore = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferRestoreWorker.kt")
        val codec = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueuePolicyCodec.kt")
        val conditions = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidQueueConditionsReader.kt")
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueIntelligence.kt")
        val retry = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueRetryLedger.kt")

        assertFalse(app.contains("installStartupRecoveryHold()"))
        assertFalse(app.contains("transferRuntime.recoverForStartup()"))
        assertFalse(app.contains("clearStartupRecoveryHold()"))
        assertTrue(app.contains("QueueIntelligenceWorker.enqueueImmediate(this@XdmApplication)"))
        assertTrue(restore.contains("AndroidSchedulerHost.wake("))
        assertTrue(restore.contains("AndroidEngineWakeReason.BOOT_OR_PACKAGE_RESTART"))
        assertTrue(restore.contains("afterBootOrPackageRestart = true"))
        assertFalse(restore.contains("runtime.recoverForStartup()") || restore.contains("installStartupRecoveryHold()") || restore.contains("clearStartupRecoveryHold()"))
        assertTrue(codec.contains("isDaysExpression(days)"))
        assertTrue(codec.contains("if (normalized.isBlank()) return false"))
        assertTrue(codec.contains("Invalid schedule configuration — review required"))
        assertTrue(conditions.contains("destinationWriter.health(raw)"))
        assertFalse(conditions.contains("public-downloads://"))
        assertTrue(conditions.contains("DestinationSpacePolicy::fromHealth"))
        assertTrue(model.contains("val battery = conditions.batteryPercent") && model.contains("if (battery == null)"))
        assertTrue(model.contains("val availableStorage = conditions.availableStorageBytes") && model.contains("DestinationSpaceState.Unknown -> Unit"))
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
