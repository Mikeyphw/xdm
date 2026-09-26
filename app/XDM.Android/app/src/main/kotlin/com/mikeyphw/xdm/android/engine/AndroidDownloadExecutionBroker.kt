package com.mikeyphw.xdm.android.engine

import android.content.Context
import com.mikeyphw.xdm.android.ffmpeg.NativeHlsMediaManager
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.persistence.DownloadAdmissionResult
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import com.mikeyphw.xdm.android.scheduler.QueueIntelligenceWorker
import com.mikeyphw.xdm.android.scheduler.TransferExecutionRuntime
import com.mikeyphw.xdm.android.scheduler.TransferSystemIdRegistry
import com.mikeyphw.xdm.android.termux.TermuxMediaPipelineManager
import org.json.JSONObject

/**
 * XGO-75 narrow Android execution broker.
 *
 * Go has already accepted the user command before this broker is invoked. Android may materialize
 * the legacy execution row while it is still required by transfer backends, pause/cancel an active
 * owner, and schedule a wake back into Go. It never evaluates queue eligibility, retry policy,
 * backend policy, or media selection and it never writes a UI projection back into Go.
 */
class AndroidDownloadExecutionBroker(
    context: Context,
    private val repository: DownloadRepository,
    private val transferRuntime: TransferExecutionRuntime,
    private val nativeHls: NativeHlsMediaManager,
    private val termuxMedia: TermuxMediaPipelineManager,
) {
    private val appContext = context.applicationContext

    suspend fun execute(payload: JSONObject): JSONObject = when (payload.optString("action")) {
        "add" -> add(payload)
        "pause" -> mutate(payload, "paused") { id ->
            if (nativeHls.ownsDownload(id)) nativeHls.pause(id) else transferRuntime.pause(id)
        }
        "resume", "retry" -> mutate(payload, "queued") { id ->
            if (nativeHls.ownsDownload(id)) nativeHls.resume(id)
            QueueIntelligenceWorker.enqueueManual(appContext, id)
        }
        "cancel" -> mutate(payload, "cancelled") { id ->
            if (nativeHls.ownsDownload(id)) nativeHls.cancel(id) else transferRuntime.cancel(id)
        }
        "pause_all" -> pauseAll()
        "resume_all" -> resumeAll()
        "delete" -> delete(payload)
        else -> rejected("unsupported_action", "Unsupported download action")
    }

    private suspend fun add(payload: JSONObject): JSONObject {
        val downloadJson = payload.optJSONObject("download")
            ?: return rejected("invalid_download", "Add command did not include a download")
        val download = runCatching { AndroidDownloadUiWire.download(downloadJson) }
            .getOrElse { return rejected("invalid_download", it.message ?: "Invalid download") }
        val duplicateAction = payload.optString("duplicate_action").takeIf(String::isNotBlank)?.let {
            runCatching { DuplicateUrlAction.valueOf(it) }.getOrNull()
        }
        return when (val result = repository.admitDownload(
            download = download,
            duplicateLookupUrl = download.sourceUrl,
            checksumExpectation = AndroidDownloadUiWire.checksum(payload.optJSONObject("checksum")),
            duplicateActionOverride = duplicateAction,
        )) {
            is DownloadAdmissionResult.Created -> {
                QueueIntelligenceWorker.enqueueManual(appContext, result.download.id)
                accepted("created", result.download.id)
            }
            is DownloadAdmissionResult.NeedsConfirmation -> existing("needs_confirmation", result.existing.id, result.existing.fileName)
            is DownloadAdmissionResult.OpenExisting -> existing("open_existing", result.existing.id, result.existing.fileName)
            is DownloadAdmissionResult.Skipped -> existing("skipped", result.existing.id, result.existing.fileName)
            is DownloadAdmissionResult.Rejected -> rejected("admission_rejected", result.message)
        }
    }

    private suspend fun mutate(payload: JSONObject, status: String, action: suspend (String) -> Unit): JSONObject {
        val id = payload.optString("download_id").trim()
        if (id.isBlank()) return rejected("invalid_download_id", "Download id is missing")
        if (repository.findDownload(id) == null) return rejected("download_not_found", "This download entry no longer exists")
        return runCatching { action(id); accepted(status, id) }
            .getOrElse { rejected("execution_adapter_failed", it.message ?: it::class.java.simpleName) }
    }

    private suspend fun pauseAll(): JSONObject {
        transferRuntime.pauseAll()
        nativeHls.pauseAll()
        return JSONObject().put("ok", true).put("status", "paused_all")
    }

    private suspend fun resumeAll(): JSONObject {
        nativeHls.resumeAll()
        QueueIntelligenceWorker.enqueueImmediate(appContext)
        return JSONObject().put("ok", true).put("status", "resumed_all")
    }

    private suspend fun delete(payload: JSONObject): JSONObject {
        val id = payload.optString("download_id").trim()
        val current = repository.findDownload(id) ?: return accepted("already_deleted", id)
        val terminal = setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled, DownloadState.RecoveryRequired)
        if (current.state !in terminal) {
            val cancel = runCatching {
                if (nativeHls.ownsDownload(id)) nativeHls.cancel(id) else transferRuntime.cancel(id)
            }
            if (cancel.isFailure) return rejected("active_owner_stop_failed", cancel.exceptionOrNull()?.message ?: "Active owner could not be stopped")
        }
        val refreshed = repository.findDownload(id) ?: return accepted("deleted", id)
        if (refreshed.state !in terminal) return rejected("active_owner_retained", "The active transfer still owns this download")
        if (!termuxMedia.prepareDownloadGraphDeletion(id)) return rejected("post_processing_owned", "Post-processing still owns this download")
        if (!repository.deleteDownloadEntryIfTerminal(refreshed, terminal)) return rejected("delete_conflict", "The download changed before deletion committed")
        MediaRequestHandoffStore.forget(id)
        TransferSystemIdRegistry(appContext).retire(id)
        return accepted("deleted", id)
    }

    private fun accepted(status: String, id: String) = JSONObject().put("ok", true).put("status", status).put("download_id", id)
    private fun existing(status: String, id: String, fileName: String) = JSONObject()
        .put("ok", true)
        .put("status", status)
        .put("existing_download_id", id)
        .put("existing_download_file_name", fileName)
    private fun rejected(code: String, message: String) = JSONObject().put("ok", false).put("error_code", code).put("message", message)
}

interface AndroidDownloadExecutionBrokerProvider {
    fun androidDownloadExecutionBrokerOrNull(): AndroidDownloadExecutionBroker?
}
