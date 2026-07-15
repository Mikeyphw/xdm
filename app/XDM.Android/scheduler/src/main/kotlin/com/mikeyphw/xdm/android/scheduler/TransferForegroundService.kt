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
    private lateinit var notifications: TransferNotifications
    private var summaryJob: Job? = null
    private var terminalJob: Job? = null
    @Volatile private var commandReceived = false

    override fun onCreate() {
        super.onCreate()
        runtime = (application as TransferRuntimeProvider).transferRuntime
        notifications = TransferNotifications(this)
        startForeground()
        terminalJob = scope.launch {
            runtime.terminalEvents.collectLatest { event ->
                val completed = event.state == com.mikeyphw.xdm.android.model.DownloadState.Completed
                getSystemService(android.app.NotificationManager::class.java).notify(
                    5000 + event.downloadId.stableSystemId(),
                    notifications.terminal(event.downloadId, event.fileName, completed, event.message),
                )
            }
        }
        summaryJob = scope.launch {
            runtime.summary.collectLatest { summary ->
                val manager = getSystemService(android.app.NotificationManager::class.java)
                manager.notify(TransferNotifications.ACTIVE_NOTIFICATION_ID, notifications.active(summary))
                if (commandReceived && summary.activeCount == 0) {
                    delay(750)
                    if (runtime.summary.value.activeCount == 0) stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        commandReceived = true
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let(runtime::launch)
            TransferNotifications.ACTION_PAUSE_ALL -> scope.launch { runtime.pauseAll() }
            TransferNotifications.ACTION_RESUME_ALL -> scope.launch { runtime.resumeAll() }
            TransferNotifications.ACTION_CANCEL -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id -> scope.launch { runtime.cancel(id) } }
            TransferNotifications.ACTION_PAUSE -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id -> scope.launch { runtime.pause(id) } }
            TransferNotifications.ACTION_RESUME, TransferNotifications.ACTION_RETRY -> intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.let { id -> scope.launch { runtime.resume(id) } }
        }
        scope.launch {
            delay(1_500)
            if (runtime.summary.value.activeCount == 0) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            scope.launch {
                runtime.pauseAll()
                stopSelf(startId)
            }
        }
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
