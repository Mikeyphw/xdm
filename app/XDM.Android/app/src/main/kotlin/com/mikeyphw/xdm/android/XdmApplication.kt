package com.mikeyphw.xdm.android

import android.app.Application
import androidx.room.Room
import com.mikeyphw.xdm.android.persistence.AppDatabase
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.persistence.Migrations
import com.mikeyphw.xdm.android.persistence.RoomBackendOwnershipStore
import com.mikeyphw.xdm.android.persistence.RoomBackendMigrationStore
import com.mikeyphw.xdm.android.persistence.RoomAria2TaskMappingStore
import com.mikeyphw.xdm.android.persistence.RoomChecksumWorkflowStore
import com.mikeyphw.xdm.android.persistence.RoomRecoveryWorkflowStore
import com.mikeyphw.xdm.android.persistence.RoomFinalizationJournalStore
import com.mikeyphw.xdm.android.scheduler.RepositoryTransferDownloadStore
import com.mikeyphw.xdm.android.scheduler.QueueConditionMonitor
import com.mikeyphw.xdm.android.scheduler.QueueIntelligenceCoordinator
import com.mikeyphw.xdm.android.scheduler.AndroidSecureRequestEnvelopeStore
import com.mikeyphw.xdm.android.scheduler.AndroidTransferRequestSecurityGuard
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import com.mikeyphw.xdm.android.scheduler.FileBackedQueueSchedulingRecoveryStore
import com.mikeyphw.xdm.android.scheduler.QueueSchedulingRecoveryCoordinator
import com.mikeyphw.xdm.android.scheduler.QueueSchedulingRecoveryProvider
import com.mikeyphw.xdm.android.scheduler.SchedulerRecoveryLeaseCoordinator
import com.mikeyphw.xdm.android.scheduler.TransferExecutionStopReasonRecorder
import com.mikeyphw.xdm.android.scheduler.QueueIntelligenceProvider
import com.mikeyphw.xdm.android.scheduler.QueueIntelligenceWorker
import com.mikeyphw.xdm.android.scheduler.AndroidCompletedArtifactReader
import com.mikeyphw.xdm.android.scheduler.TransferExecutionRuntime
import com.mikeyphw.xdm.android.scheduler.TransferExecutionStarter
import com.mikeyphw.xdm.android.scheduler.TransferNotifications
import com.mikeyphw.xdm.android.scheduler.TransferRuntimeProvider
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.BackendSelectionPolicy
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.DebugRecorderProvider
import com.mikeyphw.xdm.android.model.RollingJsonlDebugEventRecorder
import java.io.File
import com.mikeyphw.xdm.android.media.BrowserHandoffMediaCoordinator
import com.mikeyphw.xdm.android.media.BrowserCaptureSessionRegistry
import com.mikeyphw.xdm.android.media.ffmpeg.EmbeddedFfmpegRuntime
import com.mikeyphw.xdm.android.ffmpeg.EmbeddedFfmpegMediaManager
import com.mikeyphw.xdm.android.ffmpeg.NativeHlsMediaManager
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.transfer.aria2.AndroidAria2CapabilityProbe
import com.mikeyphw.xdm.android.transfer.aria2.AppPrivateAria2SecretProvider
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessManager
import com.mikeyphw.xdm.android.transfer.aria2.Aria2SessionStore
import com.mikeyphw.xdm.android.transfer.aria2.EmbeddedAria2Backend
import com.mikeyphw.xdm.android.transfer.nativeengine.NativeHttpDownloadBackend
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import com.mikeyphw.xdm.android.termux.TermuxBridgeManager
import com.mikeyphw.xdm.android.termux.TermuxAria2CockpitManager
import com.mikeyphw.xdm.android.termux.TermuxMediaPipelineManager
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationManager
import com.mikeyphw.xdm.android.termux.TermuxResultRouter
import com.mikeyphw.xdm.android.termux.TermuxResultRouterProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class XdmApplication : Application(), TransferRuntimeProvider, QueueIntelligenceProvider, QueueSchedulingRecoveryProvider, DebugRecorderProvider, ProblemReporterProvider, TermuxResultRouterProvider {
    lateinit var container: AppContainer
        private set

    override lateinit var transferRuntime: TransferExecutionRuntime
        private set

    override lateinit var queueIntelligenceCoordinator: QueueIntelligenceCoordinator
        private set

    override lateinit var queueSchedulingRecoveryCoordinator: QueueSchedulingRecoveryCoordinator
        private set

    override lateinit var debugEventRecorder: DebugEventRecorder
        private set

    override lateinit var problemReporter: AppProblemReporter
        private set

    private lateinit var queueConditionMonitor: QueueConditionMonitor

    override val termuxResultRouter: TermuxResultRouter
        get() = container.termuxMediaPipelineManager

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, AppDatabase::class.java, "xdm-android.db")
            .addMigrations(
                Migrations.Migration1To2,
                Migrations.Migration2To3,
                Migrations.Migration3To4,
                Migrations.Migration4To5,
                Migrations.Migration5To6,
                Migrations.Migration6To7,
                Migrations.Migration7To8,
                Migrations.Migration8To9,
                Migrations.Migration9To10,
                Migrations.Migration10To11,
                Migrations.Migration11To12,
                Migrations.Migration12To13,
                Migrations.Migration13To14,
                Migrations.Migration14To15,
                Migrations.Migration15To16,
                Migrations.Migration16To17,
                Migrations.Migration17To18,
                Migrations.Migration18To19,
                Migrations.Migration19To20,
                Migrations.Migration20To21,
                Migrations.Migration21To22,
                Migrations.Migration22To23,
                Migrations.Migration23To24,
                Migrations.Migration24To25,
            )
            .build()
        val repository = DownloadRepository(database)
        val rollingDebugRecorder = RollingJsonlDebugEventRecorder(
            rootDirectory = File(filesDir, "debug-sessions"),
            sessionId = "xdm-debug-workbench",
        )
        debugEventRecorder = rollingDebugRecorder
        val ownershipStore = RoomBackendOwnershipStore(database)
        val migrationStore = RoomBackendMigrationStore(database)
        val aria2MappingStore = RoomAria2TaskMappingStore(database)
        val checksumStore = RoomChecksumWorkflowStore(database)
        val finalizationStore = RoomFinalizationJournalStore(database)
        val recoveryStore = RoomRecoveryWorkflowStore(database)
        val destinationWriter = AndroidDestinationWriter(this)
        val embeddedFfmpegRuntime = EmbeddedFfmpegRuntime(this)
        val embeddedFfmpegMediaManager = EmbeddedFfmpegMediaManager(repository, destinationWriter, embeddedFfmpegRuntime)
        MediaRequestHandoffStore.initialize(AndroidSecureRequestEnvelopeStore(this))
        val nativeHlsMediaManager = NativeHlsMediaManager(this, database, repository, destinationWriter, embeddedFfmpegRuntime)
        val sensitivePersistenceMigrator = SensitivePersistenceMigrator(this, repository)
        val runtimeIdentities = BackendRuntimeIdentityStore(this)
        val aria2SessionStore = Aria2SessionStore(this)
        val termuxBridgeManager = TermuxBridgeManager(this)
        val termuxAria2CockpitManager = TermuxAria2CockpitManager(this)
        val preferences = UserPreferencesStore(this)
        problemReporter = AppProblemReporter(this, debugEventRecorder).also { it.ensureNotificationChannel() }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            preferences.values.collectLatest { prefs ->
                rollingDebugRecorder.setVerboseLoggingEnabled(prefs.verboseDebugLoggingEnabled)
            }
        }
        val termuxMediaPipelineManager = TermuxMediaPipelineManager(this, database, repository, destinationWriter, embeddedFfmpegRuntime)
        val postProcessingAutomationManager = PostProcessingAutomationManager(preferences, repository, termuxMediaPipelineManager)
        val downloadArtifactActionManager = DownloadArtifactActionManager(this)
        val mediaResolverSelectionStore = MediaResolverSelectionStore(this)
        val operationalActivityStore = OperationalActivityStore(this)
        val browserExtensionExportManager = BrowserExtensionExportManager(this)
        val aria2ProcessManager = Aria2ProcessManager(
            capabilityProbe = AndroidAria2CapabilityProbe(this, aria2SessionStore),
            sessionStore = aria2SessionStore,
            secretProvider = AppPrivateAria2SecretProvider(this),
        )
        transferRuntime = TransferExecutionRuntime(
            store = RepositoryTransferDownloadStore(repository),
            ownershipStore = ownershipStore,
            migrationStore = migrationStore,
            checksumStore = checksumStore,
            finalizationStore = finalizationStore,
            recoveryStore = recoveryStore,
            artifactRoots = listOf(filesDir, cacheDir).filterNotNull(),
            completedArtifactReader = AndroidCompletedArtifactReader(this),
            requestSecurityGuard = AndroidTransferRequestSecurityGuard(this),
            backends = listOf(
                NativeHttpDownloadBackend(
                    destinationWriter = destinationWriter,
                    runtimeIdentity = runtimeIdentities.identityFor(BackendType.Native),
                ),
                EmbeddedAria2Backend(
                    processManager = aria2ProcessManager,
                    sessionStore = aria2SessionStore,
                    mappingStore = aria2MappingStore,
                    destinationWriter = destinationWriter,
                    runtimeIdentity = runtimeIdentities.identityFor(BackendType.Aria2),
                ),
            ),
        )
        queueSchedulingRecoveryCoordinator = QueueSchedulingRecoveryCoordinator(
            FileBackedQueueSchedulingRecoveryStore(File(filesDir, "queue-scheduling-recovery")),
        )
        // Legacy v1 browser observations remain process-local. Phase 60 promotes any
        // execution-sensitive v2 URL/header context into MediaRequestHandoffStore, whose
        // durable implementation is protected by Android Keystore. Never persist browser
        // Cookie/Authorization/header material in the old plaintext Properties store.
        val browserHandoffMediaCoordinator = BrowserHandoffMediaCoordinator()
        val browserCaptureEnvelopeManager = BrowserCaptureEnvelopeManager()
        val browserCaptureSessionRegistry = BrowserCaptureSessionRegistry(File(filesDir, "browser-capture-session-index"))
        val browserCaptureImportJournal = BrowserCaptureImportJournal(File(filesDir, "browser-capture-import-journal"))
        TransferExecutionStopReasonRecorder.installPersistentRoot(File(filesDir, "queue-scheduling-recovery"))
        TransferNotifications(this).apply {
            ensureChannels()
            reconcilePendingTerminalNotifications()
        }
        val executionStarter = TransferExecutionStarter(this)
        queueIntelligenceCoordinator = QueueIntelligenceCoordinator(
            context = this,
            repository = repository,
            executionStarter = executionStarter,
            destinationWriter = destinationWriter,
            phase4Coordinator = queueSchedulingRecoveryCoordinator,
        )
        // Queue admission remains durably closed until migration and ownership recovery both finish.
        queueIntelligenceCoordinator.installStartupRecoveryHold()
        container = AppContainer(
            repository = repository,
            preferences = preferences,
            ownershipStore = ownershipStore,
            backendSelectionPolicy = BackendSelectionPolicy(),
            transferRuntime = transferRuntime,
            executionStarter = executionStarter,
            queueIntelligenceCoordinator = queueIntelligenceCoordinator,
            queueSchedulingRecoveryCoordinator = queueSchedulingRecoveryCoordinator,
            destinationWriter = destinationWriter,
            aria2ProcessManager = aria2ProcessManager,
            embeddedFfmpegMediaManager = embeddedFfmpegMediaManager,
            nativeHlsMediaManager = nativeHlsMediaManager,
            termuxBridgeManager = termuxBridgeManager,
            termuxAria2CockpitManager = termuxAria2CockpitManager,
            termuxMediaPipelineManager = termuxMediaPipelineManager,
            postProcessingAutomationManager = postProcessingAutomationManager,
            downloadArtifactActionManager = downloadArtifactActionManager,
            mediaResolverSelectionStore = mediaResolverSelectionStore,
            operationalActivityStore = operationalActivityStore,
            browserExtensionExportManager = browserExtensionExportManager,
            browserHandoffMediaCoordinator = browserHandoffMediaCoordinator,
            browserCaptureEnvelopeManager = browserCaptureEnvelopeManager,
            browserCaptureSessionRegistry = browserCaptureSessionRegistry,
            browserCaptureImportJournal = browserCaptureImportJournal,
            debugEventRecorder = debugEventRecorder,
            problemReporter = problemReporter,
        )
        termuxMediaPipelineManager.recoverInterruptedJobs()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            embeddedFfmpegMediaManager.recoverInterruptedJobs()
        }
        queueConditionMonitor = QueueConditionMonitor(this) {
            QueueIntelligenceWorker.enqueueImmediate(this)
        }
        QueueIntelligenceWorker.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // XAR09: process-independent lease prevents app startup, boot restore, and package
            // restore from running the same ownership recovery concurrently.
            val recoveryLeaseCoordinator = SchedulerRecoveryLeaseCoordinator(this@XdmApplication)
            val recoveryLease = recoveryLeaseCoordinator.tryAcquire("application-startup")
            // Each phase is isolated so one failure cannot silently suppress later reconciliation.
            // Admission stays fail-closed only for migration/runtime/native-HLS recovery failures;
            // condition-monitor startup failure must not keep the durable hold once transfer recovery is safe.
            val migration = runCatching { sensitivePersistenceMigrator.migrateIfNeeded() }
            if (migration.isSuccess) {
                postProcessingAutomationManager.startAutomaticProcessing()
            }
            val recovery = if (recoveryLease != null) {
                transferRuntime.recoverForStartup()
            } else {
                TransferExecutionRuntime.RuntimeStartupRecovery(
                    scanSucceeded = false,
                    ownershipSucceeded = false,
                    interruptedSucceeded = false,
                    restoredCount = 0,
                    reconciledCount = 0,
                )
            }
            // Native HLS waits for canonical publication-journal recovery so a destination that
            // committed just before process death is adopted instead of remuxed/published twice.
            val nativeHlsRecovery: Result<Int> = if (recoveryLease != null) {
                runCatching { nativeHlsMediaManager.recoverInterruptedJobs() }
            } else {
                Result.failure(IllegalStateException("Startup recovery lease is held by another owner."))
            }
            val monitor = runCatching { queueConditionMonitor.start() }
            val monitorStarted = monitor.isSuccess
            migration.exceptionOrNull()?.let { error ->
                problemReporter.report(
                    area = com.mikeyphw.xdm.android.model.DebugArea.Persistence,
                    title = "Startup data migration failed",
                    summary = error.message ?: error::class.java.simpleName,
                    suggestedAction = "Open Diagnostics & support, review the problem, then restart XDM after correcting the reported storage or database issue.",
                    dedupeKey = "startup-sensitive-persistence-migration",
                )
            }
            if (!recovery.admissionSafe) {
                problemReporter.report(
                    area = com.mikeyphw.xdm.android.model.DebugArea.Scheduler,
                    title = "Download recovery needs attention",
                    summary = "XDM kept new transfer admission paused because startup recovery did not complete safely.",
                    suggestedAction = "Open Recovery and Diagnostics & support before starting new downloads.",
                    dedupeKey = "startup-transfer-recovery",
                )
            }
            monitor.exceptionOrNull()?.let { error ->
                problemReporter.report(
                    area = com.mikeyphw.xdm.android.model.DebugArea.Scheduler,
                    title = "Queue condition monitoring failed",
                    summary = error.message ?: error::class.java.simpleName,
                    suggestedAction = "Open Diagnostics & support and restart XDM after reviewing network and power scheduling state.",
                    dedupeKey = "startup-queue-condition-monitor",
                )
            }
            nativeHlsRecovery.exceptionOrNull()?.let { error ->
                problemReporter.report(
                    area = com.mikeyphw.xdm.android.model.DebugArea.Scheduler,
                    title = "Native HLS recovery needs attention",
                    summary = error.message ?: error::class.java.simpleName,
                    suggestedAction = "Open Recovery and Diagnostics & support before retrying the media download.",
                    dedupeKey = "startup-native-hls-recovery",
                )
            }
            if (migration.isSuccess && recovery.admissionSafe && nativeHlsRecovery.isSuccess) {
                queueIntelligenceCoordinator.clearStartupRecoveryHold()
                QueueIntelligenceWorker.enqueueImmediate(this@XdmApplication)
            }
            recoveryLease?.let { recoveryLeaseCoordinator.release(it, "startup-recovery-finished") }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            transferRuntime.terminalEvents.collectLatest { event ->
                queueIntelligenceCoordinator.recordTerminalEvent(event)
                postProcessingAutomationManager.handleTransferTerminalEvent(event)
                if (event.state == com.mikeyphw.xdm.android.model.DownloadState.Failed ||
                    event.state == com.mikeyphw.xdm.android.model.DownloadState.RecoveryRequired
                ) {
                    problemReporter.report(
                        area = com.mikeyphw.xdm.android.model.DebugArea.Backend,
                        title = if (event.state == com.mikeyphw.xdm.android.model.DownloadState.RecoveryRequired) "Download needs recovery" else "Download failed",
                        summary = event.message ?: "The transfer ended without a usable completed file.",
                        suggestedAction = if (event.state == com.mikeyphw.xdm.android.model.DownloadState.RecoveryRequired) "Open Recovery and review the recommended action." else "Open the download details, review the error, and retry when ready.",
                        operationId = "download-${event.downloadId}",
                        downloadId = event.downloadId,
                        dedupeKey = "terminal-${event.state.name}",
                        // TransferNotifications already owns the user-facing terminal notification.
                        notifyUser = false,
                    )
                }
                QueueIntelligenceWorker.enqueueImmediate(this@XdmApplication)
            }
        }
    }
}

data class AppContainer(
    val repository: DownloadRepository,
    val preferences: UserPreferencesStore,
    val ownershipStore: BackendOwnershipStore,
    val backendSelectionPolicy: BackendSelectionPolicy,
    val transferRuntime: TransferExecutionRuntime,
    val executionStarter: TransferExecutionStarter,
    val queueIntelligenceCoordinator: QueueIntelligenceCoordinator,
    val queueSchedulingRecoveryCoordinator: QueueSchedulingRecoveryCoordinator,
    val destinationWriter: AndroidDestinationWriter,
    val aria2ProcessManager: Aria2ProcessManager,
    val embeddedFfmpegMediaManager: EmbeddedFfmpegMediaManager,
    val nativeHlsMediaManager: NativeHlsMediaManager,
    val termuxBridgeManager: TermuxBridgeManager,
    val termuxAria2CockpitManager: TermuxAria2CockpitManager,
    val termuxMediaPipelineManager: TermuxMediaPipelineManager,
    val postProcessingAutomationManager: PostProcessingAutomationManager,
    val downloadArtifactActionManager: DownloadArtifactActionManager,
    val mediaResolverSelectionStore: MediaResolverSelectionStore,
    val operationalActivityStore: OperationalActivityStore,
    val browserExtensionExportManager: BrowserExtensionExportManager,
    val browserHandoffMediaCoordinator: BrowserHandoffMediaCoordinator,
    val browserCaptureEnvelopeManager: BrowserCaptureEnvelopeManager,
    val browserCaptureSessionRegistry: BrowserCaptureSessionRegistry,
    val browserCaptureImportJournal: BrowserCaptureImportJournal,
    val debugEventRecorder: DebugEventRecorder,
    val problemReporter: AppProblemReporter,
)
