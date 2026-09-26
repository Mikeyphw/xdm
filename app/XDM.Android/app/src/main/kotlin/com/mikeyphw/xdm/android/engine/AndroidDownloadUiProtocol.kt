package com.mikeyphw.xdm.android.engine

import com.mikeyphw.xdm.android.model.BackendSelectionReason
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.ChecksumExpectation
import com.mikeyphw.xdm.android.model.ChecksumSource
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DuplicateUrlAction
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import org.json.JSONArray
import org.json.JSONObject

enum class AndroidEngineUiConnection { Connecting, Connected, Rebinding, Disconnected }

data class AndroidDownloadUiProjection(
    val revision: Long = 0L,
    val downloads: List<Download> = emptyList(),
)

data class AndroidDownloadUiCommandResult(
    val requestId: String,
    val action: String,
    val ok: Boolean,
    val status: String? = null,
    val existingDownloadId: String? = null,
    val existingDownloadFileName: String? = null,
    val message: String? = null,
    val errorCode: String? = null,
)

internal object AndroidDownloadUiWire {
    fun projectionJson(revision: Long, downloads: List<Download>): JSONObject = JSONObject()
        .put("revision", revision)
        .put("downloads", JSONArray().also { array -> downloads.forEach { array.put(downloadJson(it)) } })

    fun projection(payload: JSONObject): AndroidDownloadUiProjection {
        val array = payload.optJSONArray("downloads") ?: JSONArray()
        return AndroidDownloadUiProjection(
            revision = payload.optLong("revision", 0L),
            downloads = buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(download(it)) }
                }
            },
        )
    }

    fun result(payload: JSONObject): AndroidDownloadUiCommandResult = AndroidDownloadUiCommandResult(
        requestId = payload.optString("client_request_id"),
        action = payload.optString("action"),
        ok = payload.optBoolean("ok", false),
        status = payload.optString("status").takeIf(String::isNotBlank),
        existingDownloadId = payload.optString("existing_download_id").takeIf(String::isNotBlank),
        existingDownloadFileName = payload.optString("existing_download_file_name").takeIf(String::isNotBlank),
        message = payload.optString("message").takeIf(String::isNotBlank),
        errorCode = payload.optString("error_code").takeIf(String::isNotBlank),
    )

    fun command(
        requestId: String,
        action: String,
        downloadId: String? = null,
        download: Download? = null,
        duplicateAction: DuplicateUrlAction? = null,
        checksum: ChecksumExpectation? = null,
        policyOverride: Boolean = false,
    ): JSONObject = JSONObject()
        .put("client_request_id", requestId)
        .put("action", action)
        .apply {
            downloadId?.let { put("download_id", it) }
            download?.let { put("download", downloadJson(it)) }
            duplicateAction?.let { put("duplicate_action", it.name) }
            checksum?.let { put("checksum", checksumJson(it)) }
            if (policyOverride) put("policy_override", true)
        }

    fun downloadJson(download: Download): JSONObject = JSONObject()
        .put("id", download.id)
        .put("file_name", download.fileName)
        .put("source_url", download.sourceUrl)
        .put("destination_uri", download.destinationUri)
        .put("state", download.state.name)
        .put("backend", download.backend.name)
        .put("bytes_received", download.bytesReceived)
        .put("total_bytes", download.totalBytes ?: JSONObject.NULL)
        .put("speed_bytes_per_second", download.speedBytesPerSecond)
        .put("queue_id", download.queueId ?: JSONObject.NULL)
        .put("priority", download.priority)
        .put("created_at_epoch_ms", download.createdAtEpochMs)
        .put("updated_at_epoch_ms", download.updatedAtEpochMs)
        .put("error_message", download.errorMessage ?: JSONObject.NULL)
        .put("user_label", download.userLabel ?: JSONObject.NULL)
        .put("conflict_policy", download.conflictPolicy.name)
        .put("mime_type", download.mimeType ?: JSONObject.NULL)
        .put("requested_backend", download.requestedBackend.name)
        .put("backend_selection_reason", download.backendSelectionReason.name)
        .put("backend_selection_explanation", download.backendSelectionExplanation)
        .put("allow_backend_fallback", download.allowBackendFallback)
        .put("archived", download.archived)
        .put("attempt_generation", download.attemptGeneration)
        .put("completed_artifact_uri", download.completedArtifactUri ?: JSONObject.NULL)
        .put("completed_artifact_generation", download.completedArtifactGeneration ?: JSONObject.NULL)
        .put("completed_artifact_bytes", download.completedArtifactBytes ?: JSONObject.NULL)
        .put("observed_attempt_generation", download.observedAttemptGeneration)
        .put("row_revision", download.rowRevision)

    fun download(json: JSONObject): Download = Download(
        id = json.getString("id"),
        fileName = json.getString("file_name"),
        sourceUrl = json.optString("source_url"),
        destinationUri = json.optString("destination_uri"),
        state = enumValue(json, "state", DownloadState.Queued),
        backend = enumValue(json, "backend", BackendType.Native),
        bytesReceived = json.optLong("bytes_received", 0L),
        totalBytes = json.nullableLong("total_bytes"),
        speedBytesPerSecond = json.optLong("speed_bytes_per_second", 0L),
        queueId = json.nullableString("queue_id"),
        priority = json.optInt("priority", 0),
        createdAtEpochMs = json.optLong("created_at_epoch_ms", 0L),
        updatedAtEpochMs = json.optLong("updated_at_epoch_ms", 0L),
        errorMessage = json.nullableString("error_message"),
        userLabel = json.nullableString("user_label"),
        conflictPolicy = enumValue(json, "conflict_policy", FilenameConflictPolicy.Rename),
        mimeType = json.nullableString("mime_type"),
        requestedBackend = enumValue(json, "requested_backend", BackendType.Automatic),
        backendSelectionReason = enumValue(json, "backend_selection_reason", BackendSelectionReason.DefaultNative),
        backendSelectionExplanation = json.optString("backend_selection_explanation"),
        allowBackendFallback = json.optBoolean("allow_backend_fallback", true),
        archived = json.optBoolean("archived", false),
        attemptGeneration = json.optLong("attempt_generation", 1L),
        completedArtifactUri = json.nullableString("completed_artifact_uri"),
        completedArtifactGeneration = json.nullableLong("completed_artifact_generation"),
        completedArtifactBytes = json.nullableLong("completed_artifact_bytes"),
        observedAttemptGeneration = json.optLong("observed_attempt_generation", json.optLong("attempt_generation", 1L)),
        rowRevision = json.optLong("row_revision", json.optLong("updated_at_epoch_ms", 0L)),
    )

    fun checksum(json: JSONObject?): ChecksumExpectation? = json?.let {
        ChecksumExpectation(
            id = it.getString("id"),
            downloadId = it.getString("download_id"),
            algorithm = enumValue(it, "algorithm", ChecksumAlgorithm.Sha256),
            expectedHex = it.getString("expected_hex"),
            source = enumValue(it, "source", ChecksumSource.UserInput),
            createdAtEpochMs = it.optLong("created_at_epoch_ms", 0L),
            attemptGeneration = it.optLong("attempt_generation", 1L),
        )
    }

    private fun checksumJson(value: ChecksumExpectation): JSONObject = JSONObject()
        .put("id", value.id)
        .put("download_id", value.downloadId)
        .put("algorithm", value.algorithm.name)
        .put("expected_hex", value.expectedHex)
        .put("source", value.source.name)
        .put("created_at_epoch_ms", value.createdAtEpochMs)
        .put("attempt_generation", value.attemptGeneration)

    private inline fun <reified T : Enum<T>> enumValue(json: JSONObject, key: String, fallback: T): T =
        runCatching { enumValueOf<T>(json.optString(key)) }.getOrDefault(fallback)

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
}
