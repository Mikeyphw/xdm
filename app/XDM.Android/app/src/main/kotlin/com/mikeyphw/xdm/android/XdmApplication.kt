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
import com.mikeyphw.xdm.android.media.FileBackedBrowserHandoffMediaSessionStore
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collectLatest

class XdmApplication : Application(), TransferRuntimeProvider, QueueIntelligenceProvider, QueueSchedulingRecoveryProvider, DebugRecorderProvider {
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
        runBlocking(Dispatchers.IO) { SensitivePersistenceMigrator(this@XdmApplication, repository).migrateIfNeeded() }
        val runtimeIdentities = BackendRuntimeIdentityStore(this)
        val aria2SessionStore = Aria2SessionStore(this)
        val termuxBridgeManager = TermuxBridgeManager(this)
        val termuxAria2CockpitManager = TermuxAria2CockpitManager(this)
        val termuxMediaPipelineManager = TermuxMediaPipelineManager(this)
        val postProcessingAutomationManager = PostProcessingAutomationManager(this, termuxMediaPipelineManager, termuxBridgeManager)
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
        val browserHandoffMediaCoordinator = BrowserHandoffMediaCoordinator(
            store = FileBackedBrowserHandoffMediaSessionStore(File(filesDir, "browser-handoff-media-sessions")),
        )
        TransferExecutionStopReasonRecorder.installPersistentRoot(File(filesDir, "queue-scheduling-recovery"))
        TransferNotifications(this).ensureChannels()
        val executionStarter = TransferExecutionStarter(this)
        queueIntelligenceCoordinator = QueueIntelligenceCoordinator(
            context = this,
            repository = repository,
            executionStarter = executionStarter,
            phase4Coordinator = queueSchedulingRecoveryCoordinator,
        )
        container = AppContainer(
            repository = repository,
            preferences = UserPreferencesStore(this),
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
            mediaResolverSelectionStore = mediaResolverSelectionStore,
            operationalActivityStore = operationalActivityStore,
            browserExtensionExportManager = browserExtensionExportManager,
            browserHandoffMediaCoordinator = browserHandoffMediaCoordinator,
            debugEventRecorder = debugEventRecorder,
        )
        queueConditionMonitor = QueueConditionMonitor(this) {
            QueueIntelligenceWorker.enqueueImmediate(this)
        }
        QueueIntelligenceWorker.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            transferRuntime.scanStartupRecovery()
            transferRuntime.restoreInterruptedTransfers()
            transferRuntime.reconcilePersistedOwnership()
            queueConditionMonitor.start()
            QueueIntelligenceWorker.enqueueImmediate(this@XdmApplication)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            transferRuntime.terminalEvents.collectLatest { event ->
                queueIntelligenceCoordinator.recordTerminalEvent(event)
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
    val mediaResolverSelectionStore: MediaResolverSelectionStore,
    val operationalActivityStore: OperationalActivityStore,
    val browserExtensionExportManager: BrowserExtensionExportManager,
    val browserHandoffMediaCoordinator: BrowserHandoffMediaCoordinator,
    val debugEventRecorder: DebugEventRecorder,
)
