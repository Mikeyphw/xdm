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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TransferForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: TransferExecutionRuntime
    private lateinit var notifications: TransferNotifications
    private lateinit var systemIds: TransferSystemIdRegistry
    private var summaryJob: Job? = null
    private var terminalJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        runtime = (application as TransferRuntimeProvider).transferRuntime
        notifications = TransferNotifications(this)
        systemIds = TransferSystemIdRegistry(this)
        if (!startForeground()) {
            stopSelf()
            return
        }
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
                    requestIdentity = event.requestIdentity,
                )?.let { notification ->
                    runCatching {
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(systemIds.idFor(event.downloadId), notification)
                    }.onSuccess {
                        notifications.markTerminalDispatched(event.downloadId, event.attemptGeneration, event.state, event.requestIdentity)
                    }
                }
            }
        }
        summaryJob = scope.launch {
            val throttle = NotificationUpdateThrottle()
            runtime.summary.collectLatest { summary ->
                if (throttle.shouldPublish(summary.activeCount == 0)) {
                    runCatching {
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(TransferNotifications.ACTIVE_NOTIFICATION_ID, notifications.active(summary))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id ->
                val queueClaimToken = intent.getLongExtra(TransferExecutionStarter.EXTRA_QUEUE_CLAIM_TOKEN, 0L)
                val result = AndroidSchedulerHost.wake(
                    this,
                    AndroidEngineWakeRequest(
                        eventId = "fgs:$id:$queueClaimToken:$startId",
                        downloadId = id,
                        reason = AndroidEngineWakeReason.FOREGROUND_SERVICE,
                        requiresForeground = true,
                    ),
                )
                if (result.disposition == AndroidEngineWakeDisposition.FAILED) stopSelf(startId)
            }
            TransferNotifications.ACTION_PAUSE_ALL -> submitGoCommand("pause_all")
            TransferNotifications.ACTION_RESUME_ALL -> submitGoCommand("resume_all")
            TransferNotifications.ACTION_CANCEL -> submitGoCommand("cancel", intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID))
            TransferNotifications.ACTION_PAUSE -> submitGoCommand("pause", intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID))
            TransferNotifications.ACTION_RESUME -> submitGoCommand("resume", intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID))
            TransferNotifications.ACTION_RETRY -> submitGoCommand("retry", intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID))
        }
        return START_NOT_STICKY
    }

    private fun submitGoCommand(action: String, downloadId: String? = null) {
        scope.launch(Dispatchers.IO) {
            AndroidGoDownloadCommands.submit(this@TransferForegroundService, action, downloadId)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) stopSelf(startId)
    }

    override fun onDestroy() {
        summaryJob?.cancel()
        terminalJob?.cancel()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InlinedApi")
    private fun startForeground(): Boolean = runCatching {
        ServiceCompat.startForeground(
            this,
            TransferNotifications.ACTIVE_NOTIFICATION_ID,
            notifications.active(ActiveTransferSummary()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }.isSuccess

    companion object { const val ACTION_START = "com.mikeyphw.xdm.android.action.START_TRANSFER" }
}
