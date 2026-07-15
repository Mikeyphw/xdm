package com.mikeyphw.xdm.android

import android.app.Application
import androidx.room.Room
import com.mikeyphw.xdm.android.persistence.AppDatabase
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.persistence.Migrations
import com.mikeyphw.xdm.android.persistence.RoomBackendOwnershipStore
import com.mikeyphw.xdm.android.scheduler.RepositoryTransferDownloadStore
import com.mikeyphw.xdm.android.scheduler.TransferExecutionRuntime
import com.mikeyphw.xdm.android.scheduler.TransferExecutionStarter
import com.mikeyphw.xdm.android.scheduler.TransferNotifications
import com.mikeyphw.xdm.android.scheduler.TransferRuntimeProvider
import com.mikeyphw.xdm.android.transfer.BackendOwnershipStore
import com.mikeyphw.xdm.android.transfer.BackendSelectionPolicy
import com.mikeyphw.xdm.android.transfer.aria2.Aria2BackendPlaceholder
import com.mikeyphw.xdm.android.transfer.nativeengine.NativeHttpDownloadBackend
import com.mikeyphw.xdm.android.storage.AndroidDestinationWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XdmApplication : Application(), TransferRuntimeProvider {
    lateinit var container: AppContainer
        private set

    override lateinit var transferRuntime: TransferExecutionRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, AppDatabase::class.java, "xdm-android.db")
            .addMigrations(Migrations.Migration1To2, Migrations.Migration2To3, Migrations.Migration3To4)
            .build()
        val repository = DownloadRepository(database)
        val ownershipStore = RoomBackendOwnershipStore(database)
        val destinationWriter = AndroidDestinationWriter(this)
        transferRuntime = TransferExecutionRuntime(
            store = RepositoryTransferDownloadStore(repository),
            ownershipStore = ownershipStore,
            backends = listOf(NativeHttpDownloadBackend(destinationWriter = destinationWriter), Aria2BackendPlaceholder()),
        )
        TransferNotifications(this).ensureChannels()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            transferRuntime.restoreInterruptedTransfers()
        }
        container = AppContainer(
            repository = repository,
            preferences = UserPreferencesStore(this),
            ownershipStore = ownershipStore,
            backendSelectionPolicy = BackendSelectionPolicy(),
            transferRuntime = transferRuntime,
            executionStarter = TransferExecutionStarter(this),
            destinationWriter = destinationWriter,
        )
    }
}

data class AppContainer(
    val repository: DownloadRepository,
    val preferences: UserPreferencesStore,
    val ownershipStore: BackendOwnershipStore,
    val backendSelectionPolicy: BackendSelectionPolicy,
    val transferRuntime: TransferExecutionRuntime,
    val executionStarter: TransferExecutionStarter,
    val destinationWriter: AndroidDestinationWriter,
)
