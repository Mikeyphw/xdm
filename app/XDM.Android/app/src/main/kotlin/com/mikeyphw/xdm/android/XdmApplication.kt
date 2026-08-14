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

class XdmApplication : Application(), TransferRuntimeProvider, QueueIntelligenceProvider, QueueSchedulingRecoveryProvider, DebugRecorderProvider, TermuxResultRouterProvider {
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
            )
            .build()
        val repository = DownloadRepository(database)
        debugEventRecorder = RollingJsonlDebugEventRecorder(
            rootDirectory = File(filesDir, "debug-sessions"),
            sessionId = "xdm-debug-workbench",
        )
        val ownershipStore = RoomBackendOwnershipStore(database)
        val migrationStore = RoomBackendMigrationStore(database)
        val aria2MappingStore = RoomAria2TaskMappingStore(database)
        val checksumStore = RoomChecksumWorkflowStore(database)
        val finalizationStore = RoomFinalizationJournalStore(database)
        val recoveryStore = RoomRecoveryWorkflowStore(database)
        val destinationWriter = AndroidDestinationWriter(this)
        MediaRequestHandoffStore.initialize(AndroidSecureRequestEnvelopeStore(this))
        val sensitivePersistenceMigrator = SensitivePersistenceMigrator(this, repository)
        val runtimeIdentities = BackendRuntimeIdentityStore(this)
        val aria2SessionStore = Aria2SessionStore(this)
        val termuxBridgeManager = TermuxBridgeManager(this)
        val termuxAria2CockpitManager = TermuxAria2CockpitManager(this)
        val preferences = UserPreferencesStore(this)
        val termuxMediaPipelineManager = TermuxMediaPipelineManager(this, database, repository, destinationWriter)
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
        TransferExecutionStopReasonRecorder.installPersistentRoot(File(filesDir, "queue-scheduling-recovery"))
        TransferNotifications(this).ensureChannels()
        val executionStarter = TransferExecutionStarter(this)
        queueIntelligenceCoordinator = QueueIntelligenceCoordinator(
            context = this,
            repository = repository,
            executionStarter = executionStarter,
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
            debugEventRecorder = debugEventRecorder,
        )
        termuxMediaPipelineManager.recoverInterruptedJobs()
        postProcessingAutomationManager.startAutomaticProcessing()
        queueConditionMonitor = QueueConditionMonitor(this) {
            QueueIntelligenceWorker.enqueueImmediate(this)
        }
        QueueIntelligenceWorker.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Each phase is isolated so one failure cannot silently suppress later reconciliation.
            // Admission stays fail-closed unless every critical startup phase succeeds.
            val migration = runCatching { sensitivePersistenceMigrator.migrateIfNeeded() }
            val recovery = transferRuntime.recoverForStartup()
            val monitor = runCatching { queueConditionMonitor.start() }
            if (migration.isSuccess && recovery.admissionSafe && monitor.isSuccess) {
                queueIntelligenceCoordinator.clearStartupRecoveryHold()
                QueueIntelligenceWorker.enqueueImmediate(this@XdmApplication)
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            transferRuntime.terminalEvents.collectLatest { event ->
                queueIntelligenceCoordinator.recordTerminalEvent(event)
                postProcessingAutomationManager.handleTransferTerminalEvent(event)
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
    val debugEventRecorder: DebugEventRecorder,
)
