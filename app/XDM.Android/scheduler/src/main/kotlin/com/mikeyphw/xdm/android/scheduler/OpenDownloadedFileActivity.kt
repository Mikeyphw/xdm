package com.mikeyphw.xdm.android.scheduler

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.mikeyphw.xdm.android.model.DebugRecorderProvider
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.NoOpDebugEventRecorder
import com.mikeyphw.xdm.android.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenDownloadedFileActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val downloadId = intent.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)
        if (downloadId.isNullOrBlank()) {
            openXdmDetails(null, "missing-download-id")
            return
        }
        scope.launch {
            val runtime = application as? TransferRuntimeProvider
            val download = runtime?.transferRuntime?.findDownload(downloadId)
            when {
                download == null -> openXdmDetails(downloadId, "download-not-found")
                download.state != DownloadState.Completed -> openXdmDetails(downloadId, "download-not-completed")
                else -> withContext(Dispatchers.IO) { CompletedFileGrantPolicy.resolve(this@OpenDownloadedFileActivity, download) }
                    ?.let { uri -> openCompletedDownload(download, uri) }
                    ?: openXdmDetails(download.id, "completed-file-missing-or-unowned")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openCompletedDownload(download: Download, uri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, download.mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (viewIntent.resolveActivity(packageManager) == null) {
            openXdmDetails(download.id, "no-viewer")
            return
        }
        try {
            val chooser = Intent.createChooser(viewIntent, "Open ${download.fileName}")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(chooser)
            finish()
        } catch (_: ActivityNotFoundException) {
            openXdmDetails(download.id, "no-viewer")
        } catch (_: SecurityException) {
            openXdmDetails(download.id, "uri-permission-lost")
        } catch (_: IllegalArgumentException) {
            openXdmDetails(download.id, "invalid-completed-uri")
        }
    }

    private fun openXdmDetails(downloadId: String?, reason: String) {
        val recorder = (application as? DebugRecorderProvider)?.debugEventRecorder ?: NoOpDebugEventRecorder
        recorder.record(CompletedNotificationDebugEvents.fallback(downloadId = downloadId, reason = reason))
        val fallback = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        fallback.action = TransferNotifications.ACTION_OPEN_DOWNLOAD_DETAILS
        fallback.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (downloadId != null) fallback.putExtra(TransferNotifications.EXTRA_DOWNLOAD_ID, downloadId)
        fallback.putExtra(TransferNotifications.EXTRA_OPEN_FALLBACK_REASON, reason)
        startActivity(fallback)
        finish()
    }
}
