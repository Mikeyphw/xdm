package com.mikeyphw.xdm.android.engine

import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaManifestRole
import com.mikeyphw.xdm.android.model.MediaNativeCapability
import com.mikeyphw.xdm.android.model.MediaProtectionKind
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaThumbnailProvenance
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import org.json.JSONArray
import org.json.JSONObject

/** Go-owned media projection consumed by Android Compose/ViewModel surfaces. */
data class AndroidMediaUiProjection(
    val revision: Long = 0L,
    val captures: List<MediaCaptureRecord> = emptyList(),
    val variants: List<MediaVariant> = emptyList(),
    val selections: Map<String, MediaTrackSelection> = emptyMap(),
)

data class AndroidMediaUiCommandResult(
    val requestId: String,
    val action: String,
    val ok: Boolean,
    val status: String? = null,
    val captureId: String? = null,
    val outputPath: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

internal object AndroidMediaUiWire {
    private val sensitiveHeaderNames = setOf(
        "authorization", "cookie", "proxy-authorization", "set-cookie", "x-api-key", "api-key",
    )

    private fun isSensitiveHeaderName(value: String): Boolean {
        val name = value.trim().lowercase()
        return name in sensitiveHeaderNames || name.contains("token") || name.endsWith("-key")
    }

    fun syncPayload(revision: Long, captures: List<MediaCaptureRecord>, variants: List<MediaVariant>): JSONObject {
        val byCapture = variants.groupBy(MediaVariant::captureId)
        return JSONObject()
            .put("revision", revision)
            .put("captures", JSONArray().also { array ->
                captures.sortedBy(MediaCaptureRecord::id).forEach { capture ->
                    array.put(capturePayload(
                        requestId = "mirror:${capture.id}:$revision",
                        record = capture,
                        variants = byCapture[capture.id].orEmpty(),
                        sessionId = "legacy-room:${capture.id}",
                        documentGeneration = maxOf(1L, capture.rowRevision),
                        headers = emptyMap(),
                    ))
                }
            })
    }

    fun capturePayload(
        requestId: String,
        record: MediaCaptureRecord,
        variants: List<MediaVariant>,
        sessionId: String,
        documentGeneration: Long,
        headers: Map<String, String>,
    ): JSONObject = JSONObject()
        .put("client_request_id", requestId)
        .put("capture_record", captureJson(record))
        .put("variants", JSONArray().also { array -> variants.sortedBy(MediaVariant::position).forEach { array.put(variantJson(it)) } })
        .put("envelope", captureEnvelope(record, sessionId, documentGeneration, headers))

    fun selectionPayload(requestId: String, captureId: String, selection: MediaTrackSelection): JSONObject = JSONObject()
        .put("client_request_id", requestId)
        .put("capture_id", captureId)
        .put("selection", JSONObject()
            .putNullable("video_variant_id", selection.videoVariantId)
            .putNullable("audio_variant_id", selection.audioVariantId)
            .putNullable("subtitle_variant_id", selection.subtitleVariantId))

    fun executePayload(requestId: String, captureId: String): JSONObject = JSONObject()
        .put("client_request_id", requestId)
        .put("capture_id", captureId)

    fun projection(payload: JSONObject): AndroidMediaUiProjection {
        val captures = payload.optJSONArray("captures") ?: JSONArray()
        val variants = payload.optJSONArray("variants") ?: JSONArray()
        val selections = payload.optJSONArray("selections") ?: JSONArray()
        return AndroidMediaUiProjection(
            revision = payload.optLong("revision", 0L),
            captures = buildList {
                for (index in 0 until captures.length()) captures.optJSONObject(index)?.let { add(capture(it)) }
            },
            variants = buildList {
                for (index in 0 until variants.length()) variants.optJSONObject(index)?.let { add(variant(it)) }
            },
            selections = buildMap {
                for (index in 0 until selections.length()) {
                    val item = selections.optJSONObject(index) ?: continue
                    val captureId = item.optString("capture_id").takeIf(String::isNotBlank) ?: continue
                    put(captureId, MediaTrackSelection(
                        videoVariantId = item.nullableString("video_variant_id"),
                        audioVariantId = item.nullableString("audio_variant_id"),
                        subtitleVariantId = item.nullableString("subtitle_variant_id"),
                    ))
                }
            },
        )
    }

    fun result(payload: JSONObject): AndroidMediaUiCommandResult = AndroidMediaUiCommandResult(
        requestId = payload.optString("client_request_id"),
        action = payload.optString("action"),
        ok = payload.optBoolean("ok", false),
        status = payload.nullableString("status"),
        captureId = payload.nullableString("capture_id"),
        outputPath = payload.nullableString("output_path"),
        errorCode = payload.nullableString("error_code"),
        message = payload.nullableString("message"),
    )

    fun captureJson(record: MediaCaptureRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("source_url", record.sourceUrl)
        .putNullable("page_url", record.pageUrl)
        .put("title", record.title)
        .put("status", record.status.name)
        .put("kind", record.kind.name)
        .putNullable("mime_type", record.mimeType)
        .putNullable("container", record.container)
        .putNullable("codecs", record.codecs)
        .putNullable("duration_ms", record.durationMs)
        .putNullable("thumbnail_url", record.thumbnailUrl)
        .put("thumbnail_provenance", record.thumbnailProvenance.name)
        .put("file_name", record.fileName)
        .put("variant_count", record.variantCount)
        .putNullable("download_id", record.downloadId)
        .put("created_at_epoch_ms", record.createdAtEpochMs)
        .put("updated_at_epoch_ms", record.updatedAtEpochMs)
        .putNullable("selected_variant_id", record.selectedVariantId)
        .putNullable("selected_variant_url", record.selectedVariantUrl)
        .putNullable("manifest_expires_at_epoch_ms", record.manifestExpiresAtEpochMs)
        .putNullable("last_resolved_at_epoch_ms", record.lastResolvedAtEpochMs)
        .put("resolution_status", record.resolutionStatus.name)
        .put("manifest_role", record.manifestRole.name)
        .putNullable("manifest_is_live", record.manifestIsLive)
        .put("manifest_protected", record.manifestProtected)
        .putNullable("manifest_protection_scheme", record.manifestProtectionScheme)
        .putNullable("logical_media_id", record.logicalMediaId)
        .putNullable("canonical_media_url", record.canonicalMediaUrl)
        .put("observation_count", record.observationCount)
        .put("segment_count", record.segmentCount)
        .put("protection_kind", record.protectionKind.name)
        .put("native_capability", record.nativeCapability.name)
        .put("logical_confidence", record.logicalConfidence)
        .put("row_revision", record.rowRevision)

    fun variantJson(value: MediaVariant): JSONObject = JSONObject()
        .put("id", value.id)
        .put("capture_id", value.captureId)
        .put("url", value.url)
        .put("kind", value.kind.name)
        .putNullable("mime_type", value.mimeType)
        .putNullable("width", value.width)
        .putNullable("height", value.height)
        .putNullable("bitrate_bits_per_second", value.bitrateBitsPerSecond)
        .putNullable("codecs", value.codecs)
        .putNullable("language", value.language)
        .put("position", value.position)
        .put("display_label", value.displayLabel)
        .putNullable("expires_at_epoch_ms", value.expiresAtEpochMs)
        .putNullable("group_id", value.groupId)
        .putNullable("audio_group_id", value.audioGroupId)
        .putNullable("subtitle_group_id", value.subtitleGroupId)
        .put("is_default", value.isDefault)
        .put("is_autoselect", value.isAutoselect)
        .put("is_forced", value.isForced)
        .putNullable("channels", value.channels)
        .putNullable("in_stream_id", value.inStreamId)
        .put("requires_network_fetch", value.requiresNetworkFetch)
        .putNullable("manifest_timeline_group_id", value.manifestTimelineGroupId)
        .putNullable("manifest_execution_url_template", value.manifestExecutionUrlTemplate)
        .putNullable("manifest_initialization_url", value.manifestInitializationUrl)
        .putNullable("manifest_role_label", value.manifestRoleLabel)

    private fun captureEnvelope(
        record: MediaCaptureRecord,
        sessionId: String,
        documentGeneration: Long,
        headers: Map<String, String>,
    ): JSONObject {
        val safeHeaders = headers.entries
            .filterNot { isSensitiveHeaderName(it.key) }
            .sortedBy { it.key.lowercase() }
        return JSONObject()
            .put("version", 1)
            .put("request", JSONObject()
                .put("url", record.sourceUrl)
                .put("method", "GET")
                .put("headers", JSONArray().also { array ->
                    safeHeaders.forEach { (name, value) -> array.put(JSONObject().put("name", name).put("value", value)) }
                }))
            .put("page", JSONObject()
                .put("page_url", record.pageUrl?.takeIf(String::isNotBlank) ?: record.sourceUrl)
                .put("session_id", sessionId.ifBlank { "android-media:${record.id}" })
                .put("document_generation", maxOf(1L, documentGeneration)))
            .put("media_hints", JSONArray().also { array ->
                array.put(JSONObject().put("kind", "android_source_kind").put("value", record.kind.name))
                record.mimeType?.takeIf(String::isNotBlank)?.let { array.put(JSONObject().put("kind", "mime_type").put("value", it)) }
            })
            .put("credential_scope", JSONObject().put("ref", "capture:${record.id}"))
    }

    private fun capture(json: JSONObject): MediaCaptureRecord = MediaCaptureRecord(
        id = json.getString("id"),
        sourceUrl = json.getString("source_url"),
        pageUrl = json.nullableString("page_url"),
        title = json.optString("title"),
        status = enumValue(json, "status", MediaCaptureStatus.Captured),
        kind = enumValue(json, "kind", MediaSourceKind.Unknown),
        mimeType = json.nullableString("mime_type"),
        container = json.nullableString("container"),
        codecs = json.nullableString("codecs"),
        durationMs = json.nullableLong("duration_ms"),
        thumbnailUrl = json.nullableString("thumbnail_url"),
        thumbnailProvenance = enumValue(json, "thumbnail_provenance", MediaThumbnailProvenance.Unknown),
        fileName = json.optString("file_name"),
        variantCount = json.optInt("variant_count", 0),
        downloadId = json.nullableString("download_id"),
        createdAtEpochMs = json.optLong("created_at_epoch_ms", 0L),
        updatedAtEpochMs = json.optLong("updated_at_epoch_ms", 0L),
        selectedVariantId = json.nullableString("selected_variant_id"),
        selectedVariantUrl = json.nullableString("selected_variant_url"),
        manifestExpiresAtEpochMs = json.nullableLong("manifest_expires_at_epoch_ms"),
        lastResolvedAtEpochMs = json.nullableLong("last_resolved_at_epoch_ms"),
        resolutionStatus = enumValue(json, "resolution_status", MediaResolutionStatus.Unresolved),
        manifestRole = enumValue(json, "manifest_role", MediaManifestRole.Unknown),
        manifestIsLive = json.nullableBoolean("manifest_is_live"),
        manifestProtected = json.optBoolean("manifest_protected", false),
        manifestProtectionScheme = json.nullableString("manifest_protection_scheme"),
        logicalMediaId = json.nullableString("logical_media_id"),
        canonicalMediaUrl = json.nullableString("canonical_media_url"),
        observationCount = json.optInt("observation_count", 1),
        segmentCount = json.optInt("segment_count", 0),
        protectionKind = enumValue(json, "protection_kind", MediaProtectionKind.None),
        nativeCapability = enumValue(json, "native_capability", MediaNativeCapability.Unknown),
        logicalConfidence = json.optInt("logical_confidence", 0),
        rowRevision = json.optLong("row_revision", json.optLong("updated_at_epoch_ms", 0L)),
    )

    private fun variant(json: JSONObject): MediaVariant = MediaVariant(
        id = json.getString("id"),
        captureId = json.getString("capture_id"),
        url = json.getString("url"),
        kind = enumValue(json, "kind", MediaVariantKind.Primary),
        mimeType = json.nullableString("mime_type"),
        width = json.nullableInt("width"),
        height = json.nullableInt("height"),
        bitrateBitsPerSecond = json.nullableLong("bitrate_bits_per_second"),
        codecs = json.nullableString("codecs"),
        language = json.nullableString("language"),
        position = json.optInt("position", 0),
        displayLabel = json.optString("display_label"),
        expiresAtEpochMs = json.nullableLong("expires_at_epoch_ms"),
        groupId = json.nullableString("group_id"),
        audioGroupId = json.nullableString("audio_group_id"),
        subtitleGroupId = json.nullableString("subtitle_group_id"),
        isDefault = json.optBoolean("is_default", false),
        isAutoselect = json.optBoolean("is_autoselect", false),
        isForced = json.optBoolean("is_forced", false),
        channels = json.nullableString("channels"),
        inStreamId = json.nullableString("in_stream_id"),
        requiresNetworkFetch = json.optBoolean("requires_network_fetch", true),
        manifestTimelineGroupId = json.nullableString("manifest_timeline_group_id"),
        manifestExecutionUrlTemplate = json.nullableString("manifest_execution_url_template"),
        manifestInitializationUrl = json.nullableString("manifest_initialization_url"),
        manifestRoleLabel = json.nullableString("manifest_role_label"),
    )

    private inline fun <reified T : Enum<T>> enumValue(json: JSONObject, key: String, fallback: T): T =
        runCatching { enumValueOf<T>(json.optString(key)) }.getOrDefault(fallback)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    private fun JSONObject.nullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
    private fun JSONObject.nullableBoolean(key: String): Boolean? = if (!has(key) || isNull(key)) null else optBoolean(key)
}
