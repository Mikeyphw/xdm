package com.mikeyphw.xdm.android.engine

import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import com.mikeyphw.xdm.android.ffmpeg.NativeHlsMediaManager
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.persistence.DownloadAdmissionResult
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.scheduler.QueueIntelligenceCoordinator
import com.mikeyphw.xdm.android.termux.TermuxMediaPipelineManager
import com.mikeyphw.xdm.android.scheduler.TransferExecutionRuntime
import org.json.JSONObject

/**
 * Compatibility execution broker. UI never mutates Room/transfer runtime directly: commands
 * cross Go first, then Go requests this narrow host operation. XGO-74 removed its live Room
 * projection role; XGO-75 removes/reduces the remaining legacy execution side effects.
 */
class AndroidLegacyDownloadUiBroker(
    private val repository: DownloadRepository,
    private val transferRuntime: TransferExecutionRuntime,
    private val queueCoordinator: QueueIntelligenceCoordinator,
    private val nativeHls: NativeHlsMediaManager,
    private val termuxMedia: TermuxMediaPipelineManager,
) {
    suspend fun execute(payload: JSONObject): JSONObject = when (payload.optString("action")) {
        "add" -> add(payload)
        "pause" -> mutate(payload, "paused") { id ->
            if (nativeHls.ownsDownload(id)) nativeHls.pause(id) else transferRuntime.pause(id)
        }
        "resume", "retry" -> mutate(payload, "queued") { id ->
            if (nativeHls.ownsDownload(id)) nativeHls.resume(id)
            else queueCoordinator.requestStart(
                id,
                userVisible = true,
                manual = true,
                policyOverride = payload.optBoolean("policy_override", false),
            )
        }
        "cancel" -> mutate(payload, "cancelled") { id ->
            if (nativeHls.ownsDownload(id)) nativeHls.cancel(id) else transferRuntime.cancel(id)
        }
        "pause_all" -> pauseAll()
        "resume_all" -> resumeAll()
        "delete" -> delete(payload)
        else -> JSONObject().put("ok", false).put("error_code", "unsupported_action").put("message", "Unsupported download action")
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
            is DownloadAdmissionResult.Created -> accepted("created", result.download.id)
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
            .getOrElse { rejected("legacy_execution_failed", it.message ?: it::class.java.simpleName) }
    }


    private suspend fun pauseAll(): JSONObject {
        queueCoordinator.pauseAllDurably()
        transferRuntime.pauseAll()
        nativeHls.pauseAll()
        return JSONObject().put("ok", true).put("status", "paused_all")
    }

    private suspend fun resumeAll(): JSONObject {
        queueCoordinator.resumeAllManual()
        nativeHls.resumeAll()
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
        queueCoordinator.retireAndroidSystemId(id)
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

interface AndroidDownloadUiPlatformBrokerProvider {
    fun androidDownloadUiPlatformBrokerOrNull(): AndroidLegacyDownloadUiBroker?
}
