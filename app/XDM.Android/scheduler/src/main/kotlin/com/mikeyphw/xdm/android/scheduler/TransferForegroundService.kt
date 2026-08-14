package com.mikeyphw.xdm.android.scheduler

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TransferForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: TransferExecutionRuntime
    private lateinit var queueIntelligence: QueueIntelligenceCoordinator
    private lateinit var notifications: TransferNotifications
    private lateinit var systemIds: TransferSystemIdRegistry
    private var summaryJob: Job? = null
    private var terminalJob: Job? = null
    private val ownedClaims = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        runtime = (application as TransferRuntimeProvider).transferRuntime
        queueIntelligence = (application as QueueIntelligenceProvider).queueIntelligenceCoordinator
        notifications = TransferNotifications(this)
        systemIds = TransferSystemIdRegistry(this)
        startForeground()
        terminalJob = scope.launch {
            runtime.terminalEvents.collectLatest { event ->
                notifications.terminalIfFirst(
                    downloadId = event.downloadId,
                    fileName = event.fileName,
                    state = event.state,
                    message = event.message,
                    destinationUri = event.destinationUri,
                    mimeType = event.mimeType,
                    attemptGeneration = event.attemptGeneration,
                )?.let { notification ->
                    getSystemService(android.app.NotificationManager::class.java).notify(systemIds.idFor(event.downloadId), notification)
                }
            }
        }
        summaryJob = scope.launch {
            runtime.summary.collectLatest { summary ->
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(TransferNotifications.ACTIVE_NOTIFICATION_ID, notifications.active(summary))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id ->
                val queueClaimToken = intent.getLongExtra(TransferExecutionStarter.EXTRA_QUEUE_CLAIM_TOKEN, Long.MIN_VALUE)
                // Component delivery may race with Pause All or startup recovery. Re-prove the
                // durable claim before any backend side effect instead of trusting the old Intent.
                scope.launch {
                    when (queueIntelligence.authorizeClaimedExecution(id, queueClaimToken)) {
                        ClaimedExecutionAuthorization.Ready -> {
                            ownedClaims[id] = queueClaimToken
                            try {
                                runtime.execute(id, queueClaimToken)
                            } finally {
                                ownedClaims.remove(id, queueClaimToken)
                                AndroidExecutionClaimRegistry.release(id, queueClaimToken)
                            }
                        }
                        ClaimedExecutionAuthorization.TemporarilyHeld ->
                            QueueIntelligenceWorker.enqueueClaimed(this@TransferForegroundService, id, queueClaimToken)
                        ClaimedExecutionAuthorization.Stale -> Unit
                    }
                    delay(250)
                    if (runtime.summary.value.activeCount == 0) stopSelf(startId)
                }
            }
            TransferNotifications.ACTION_PAUSE_ALL -> scope.launch { queueIntelligence.pauseAllDurably(); runtime.pauseAll() }
            TransferNotifications.ACTION_RESUME_ALL -> scope.launch { queueIntelligence.resumeAllManual() }
            TransferNotifications.ACTION_CANCEL -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id -> scope.launch { runtime.cancel(id) } }
            TransferNotifications.ACTION_PAUSE -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id -> scope.launch { runtime.pause(id) } }
            TransferNotifications.ACTION_RESUME, TransferNotifications.ACTION_RETRY -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id -> scope.launch { queueIntelligence.requestStart(id, userVisible = true, manual = true) } }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            scope.launch {
                ownedClaims.toMap().forEach { (downloadId, queueClaimToken) ->
                    runtime.pauseOwned(downloadId, queueClaimToken)
                    ownedClaims.remove(downloadId, queueClaimToken)
                    AndroidExecutionClaimRegistry.release(downloadId, queueClaimToken)
                }
                stopSelf(startId)
            }
        }
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) ownedClaims.toMap().forEach { (downloadId, queueClaimToken) ->
            runtime.requestPauseOwnedAsync(downloadId, queueClaimToken)
        }
        summaryJob?.cancel()
        terminalJob?.cancel()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InlinedApi")
    private fun startForeground() {
        ServiceCompat.startForeground(
            this,
            TransferNotifications.ACTIVE_NOTIFICATION_ID,
            notifications.active(ActiveTransferSummary()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object { const val ACTION_START = "com.mikeyphw.xdm.android.action.START_TRANSFER" }
}
