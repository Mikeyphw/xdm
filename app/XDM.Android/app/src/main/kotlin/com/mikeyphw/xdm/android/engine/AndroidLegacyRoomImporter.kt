package com.mikeyphw.xdm.android.engine

import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** One-shot XGO-74 bridge from legacy Room rows into the durable Go authority snapshot. */
class AndroidLegacyRoomImporter(
    private val repository: DownloadRepository,
) {
    suspend fun importOnce(authority: AndroidEngineProcessAuthority): AndroidLegacyRoomImportResult {
        val downloads = repository.downloads.first().sortedBy { it.id }
        val queues = repository.queues.first().sortedBy { it.id }
        val schedules = repository.schedules.first().sortedBy { it.id }
        val recovery = repository.recoveryRecords.first().sortedBy { it.id }
        val captures = repository.mediaCaptures.first().sortedBy { it.id }
        val variants = repository.mediaVariants.first().sortedBy { it.id }

        val packageJson = JSONObject()
            .put("package_version", 1)
            .put("room_schema_version", ROOM_SCHEMA_VERSION)
            .put("downloads", JSONArray().also { array ->
                downloads.forEach { array.put(AndroidDownloadUiWire.downloadJson(it)) }
            })
            .put("attempts", genericArray(downloads.map { download ->
                download.id + ":" + download.attemptGeneration to JSONObject()
                    .put("download_id", download.id)
                    .put("attempt_generation", download.attemptGeneration)
                    .put("state", download.state.name)
                    .put("backend", download.backend.name)
            }))
            .put("history", genericArray(downloads.filter { it.state in terminalStates }.map { download ->
                download.id + ":" + download.updatedAtEpochMs to JSONObject()
                    .put("download_id", download.id)
                    .put("state", download.state.name)
                    .put("updated_at_epoch_ms", download.updatedAtEpochMs)
            }))
            .put("queues", genericArray(queues.map { queue ->
                queue.id to JSONObject()
                    .put("name", queue.name)
                    .put("enabled", queue.isEnabled)
                    .put("max_concurrent", queue.maxConcurrent)
                    .put("created_at_epoch_ms", queue.createdAtEpochMs)
            }))
            .put("schedules", genericArray(schedules.map { rule ->
                rule.id to JSONObject()
                    .put("queue_id", rule.queueId ?: JSONObject.NULL)
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("constraints_json", rule.constraintsJson)
            }))
            .put("recovery", genericArray(recovery.map { record ->
                record.id to JSONObject()
                    .put("download_id", record.downloadId ?: JSONObject.NULL)
                    .put("classification", record.classification.name)
                    .put("reason", record.reason)
                    .put("attempt_generation", record.attemptGeneration)
                    .put("safe_to_resume", record.safeToResume)
            }))
            .put("media", genericArray(
                captures.map { capture ->
                    "capture:${capture.id}" to JSONObject()
                        .put("record_type", "capture")
                        .put("capture_id", capture.id)
                        .put("status", capture.status.name)
                        .put("kind", capture.kind.name)
                } + variants.map { variant ->
                    "variant:${variant.id}" to JSONObject()
                        .put("record_type", "variant")
                        .put("variant_id", variant.id)
                        .put("capture_id", variant.captureId)
                        .put("kind", variant.kind.name)
                },
            ))
            .put("media_sync", AndroidMediaUiWire.syncPayload(1L, captures, variants))

        return authority.importLegacyRoom(packageJson)
    }

    private fun genericArray(records: List<Pair<String, JSONObject>>): JSONArray = JSONArray().also { array ->
        records.forEach { (id, payload) -> array.put(JSONObject().put("id", id).put("payload", payload)) }
    }

    private companion object {
        const val ROOM_SCHEMA_VERSION = 25
        val terminalStates = setOf(
            DownloadState.Completed,
            DownloadState.Failed,
            DownloadState.Cancelled,
            DownloadState.RecoveryRequired,
        )
    }
}

data class AndroidLegacyRoomImportResult(
    val requestId: String,
    val ok: Boolean,
    val status: String,
    val duplicate: Boolean,
    val importedCount: Int,
    val roomSchemaVersion: Int,
    val importKey: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

internal object AndroidLegacyRoomImportWire {
    fun result(payload: JSONObject): AndroidLegacyRoomImportResult = AndroidLegacyRoomImportResult(
        requestId = payload.optString("client_request_id"),
        ok = payload.optBoolean("ok", false),
        status = payload.optString("status"),
        duplicate = payload.optBoolean("duplicate", false),
        importedCount = payload.optInt("imported_count", 0),
        roomSchemaVersion = payload.optInt("room_schema_version", 0),
        importKey = payload.optString("import_key").takeIf(String::isNotBlank),
        errorCode = payload.optString("error_code").takeIf(String::isNotBlank),
        message = payload.optString("message").takeIf(String::isNotBlank),
    )
}
