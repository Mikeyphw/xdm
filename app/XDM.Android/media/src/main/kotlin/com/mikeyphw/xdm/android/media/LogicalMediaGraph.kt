package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaManifestRole
import com.mikeyphw.xdm.android.model.MediaNativeCapability
import com.mikeyphw.xdm.android.model.MediaObservationRecord
import com.mikeyphw.xdm.android.model.MediaProtectionKind
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaThumbnailProvenance
import com.mikeyphw.xdm.android.model.MediaVariant
import com.mikeyphw.xdm.android.model.MediaVariantKind
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Browser-neutral capture evidence. Exact headers are transient execution context; callers must never persist them. */
data class MediaObservation(
    val url: String,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val thumbnailProvenance: MediaThumbnailProvenance = MediaThumbnailProvenance.Unknown,
    val bodyPrefix: String? = null,
    val source: MediaSniffingSource = MediaSniffingSource.NetworkObservation,
    val initiator: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val stableMediaIdHint: String? = null,
    val observedAtEpochMs: Long = System.currentTimeMillis(),
)

data class LogicalMediaItem(
    val logicalMediaId: String,
    /** Redacted durable URL for UI/Room. */
    val canonicalUrl: String,
    /** Exact process-local URL for execution/handoff. Never persist this field directly. */
    val requestUrl: String,
    val record: MediaCaptureRecord,
    val variants: List<MediaVariant>,
    val requestHeaders: Map<String, String>,
    val observationCount: Int,
    val segmentCount: Int,
    val rawEvidence: List<MediaObservationRecord>,
)

data class LogicalMediaGraphSnapshot(
    val items: List<LogicalMediaItem>,
    val rawObservationCount: Long,
    val retainedEvidenceCount: Int,
    val evidence: List<MediaObservationRecord> = emptyList(),
) {
    val userVisibleCount: Int get() = items.size
}

/**
 * Converges noisy browser/WebView observations into user-facing media.
 *
 * Invariants:
 *  - a segment/thumbnail/API observation can enrich evidence but never become a normal media row;
 *  - signed URL refreshes and repeated observers converge on one logical identity;
 *  - HLS child playlists are reparented when a master appears later;
 *  - memory is bounded independently for logical items and raw evidence;
 *  - request credentials stay transient and are never included in MediaObservationEvidence.
 */
class LogicalMediaGraphEngine(
    private val sniffingEngine: MediaSniffingEngine = MediaSniffingEngine(),
    private val captureService: MediaCaptureService = MediaCaptureService(),
    private val maxLogicalItems: Int = 96,
    private val maxEvidence: Int = 384,
    private val maxAliases: Int = 768,
) {
    private data class Node(
        val id: String,
        var canonicalUrl: String,
        var record: MediaCaptureRecord,
        val variants: LinkedHashMap<String, MediaVariant> = linkedMapOf(),
        val aliases: LinkedHashSet<String> = linkedSetOf(),
        var requestHeaders: Map<String, String> = emptyMap(),
        var observations: Int = 0,
        var segments: Int = 0,
        var rank: Int = 0,
        var updatedAt: Long = 0L,
    )

    private val nodes = linkedMapOf<String, Node>()
    private val aliasToNode = linkedMapOf<String, String>()
    private val childToMaster = linkedMapOf<String, String>()
    private val evidence = ArrayDeque<MediaObservationRecord>()
    private var totalObservations: Long = 0

    @Synchronized
    fun observe(observation: MediaObservation): LogicalMediaGraphSnapshot {
        totalObservations++
        val exactUrl = normalizeHttp(observation.url) ?: return snapshot()
        val safeIdentity = identityUrl(exactUrl)
        val mime = normalizedMime(observation.mimeType)
        val body = observation.bodyPrefix?.take(MAX_BODY_PREFIX)
        val hardNonMedia = mime?.let { MediaCandidateClassifier.isHardNonMediaMime(it) } == true
        val segment = isSegment(exactUrl, mime, observation.initiator)
        val manifestBody = body?.trimStart()?.startsWith("#EXTM3U", ignoreCase = true) == true
        val manifestUrl = isHlsUrl(exactUrl, mime)

        if (hardNonMedia || isObviousApi(exactUrl, mime)) {
            appendEvidence(observation, null, if (mime?.startsWith("image/") == true) "image" else "non-media")
            return snapshot()
        }

        if (segment) {
            val parentId = findSegmentParent(exactUrl, observation.pageUrl)
            parentId?.let { nodes[it] }?.apply {
                observations++
                segments++
                updatedAt = observation.observedAtEpochMs
            }
            appendEvidence(observation, parentId, "segment")
            return snapshot()
        }

        val plan = sniffingEngine.sniff(
            MediaSniffingInput(
                url = exactUrl,
                mimeType = observation.mimeType,
                contentLength = observation.contentLength,
                durationMs = observation.durationMs,
                thumbnailUrl = observation.thumbnailUrl,
                thumbnailProvenance = observation.thumbnailProvenance,
                bodyPrefix = body,
                pageUrl = observation.pageUrl,
                pageTitle = observation.pageTitle,
                requestHeaders = observation.requestHeaders,
                source = observation.source,
            ),
        )
        val primary = plan.records.maxByOrNull { recordPriority(it) }
        if (primary == null) {
            appendEvidence(observation, null, "unclassified")
            return snapshot()
        }

        val summary = if (primary.kind == MediaSourceKind.HlsPlaylist && manifestBody) captureService.inspectHlsPlaylist(body.orEmpty()) else null
        val hlsRefs = if (primary.kind == MediaSourceKind.HlsPlaylist && manifestBody) parseHlsReferences(exactUrl, body.orEmpty()) else HlsReferences()
        val preferredMasterIdentity = when {
            summary?.role == MediaManifestRole.HlsMaster -> safeIdentity
            childToMaster.containsKey(safeIdentity) -> childToMaster[safeIdentity]
            else -> null
        }
        val existingByAlias = aliasToNode[safeIdentity]
        val logicalId = existingByAlias
            ?: preferredMasterIdentity?.let(aliasToNode::get)
            ?: logicalIdFor(preferredMasterIdentity ?: safeIdentity, observation.pageUrl, primary.kind, observation.stableMediaIdHint)

        val recordId = "media-logical-${logicalId.substringAfterLast('-').take(28)}"
        val protection = classifyProtection(primary, summary, body)
        val canonicalExact = if (summary?.role == MediaManifestRole.HlsMaster) exactUrl else nodes[logicalId]?.canonicalUrl ?: exactUrl
        val canonicalSafe = identityUrl(canonicalExact)
        val rekeyedVariants = plan.variants
            .filter { it.captureId == primary.id }
            .mapIndexed { index, v -> v.copy(id = "$recordId:${v.kind.name.lowercase(Locale.US)}:$index", captureId = recordId) }

        val incoming = primary.copy(
            id = recordId,
            sourceUrl = canonicalSafe,
            canonicalMediaUrl = canonicalSafe,
            logicalMediaId = logicalId,
            observationCount = 1,
            segmentCount = hlsRefs.segments.size,
            protectionKind = protection.first,
            nativeCapability = protection.second,
            logicalConfidence = plan.candidates.maxOfOrNull { it.rank } ?: 0,
            manifestRole = summary?.role ?: primary.manifestRole,
            manifestIsLive = summary?.isLive ?: primary.manifestIsLive,
            manifestProtected = protection.first in setOf(MediaProtectionKind.SampleAes, MediaProtectionKind.Drm, MediaProtectionKind.UnknownEncrypted),
            manifestProtectionScheme = primary.manifestProtectionScheme ?: protection.first.name.takeUnless { protection.first == MediaProtectionKind.None },
            variantCount = rekeyedVariants.size.coerceAtLeast(1),
            selectedVariantId = rekeyedVariants.firstOrNull()?.id,
            selectedVariantUrl = rekeyedVariants.firstOrNull()?.url?.let(::identityUrl) ?: canonicalSafe,
        )

        val node = nodes[logicalId] ?: Node(logicalId, canonicalExact, incoming).also { nodes[logicalId] = it }
        node.observations++
        node.updatedAt = observation.observedAtEpochMs
        node.rank = maxOf(node.rank, incoming.logicalConfidence)
        node.requestHeaders = mergeHeaders(node.requestHeaders, observation.requestHeaders)
        node.aliases += safeIdentity
        aliasToNode[safeIdentity] = logicalId
        if (summary?.role == MediaManifestRole.HlsMaster) {
            node.canonicalUrl = exactUrl
            node.record = mergeRecord(node.record, incoming.copy(sourceUrl = canonicalSafe, canonicalMediaUrl = canonicalSafe))
            hlsRefs.playlists.forEach { child ->
                val childIdentity = identityUrl(child)
                childToMaster[childIdentity] = safeIdentity
                val childNodeId = aliasToNode[childIdentity]
                if (childNodeId != null && childNodeId != logicalId) mergeNodes(logicalId, childNodeId)
                aliasToNode[childIdentity] = logicalId
                node.aliases += childIdentity
            }
        } else {
            node.record = mergeRecord(node.record, incoming)
        }
        rekeyedVariants.forEach { node.variants[variantIdentity(it)] = it }
        hlsRefs.playlists.forEach { alias -> node.aliases += identityUrl(alias); aliasToNode[identityUrl(alias)] = logicalId }
        hlsRefs.segments.forEach { segmentUrl -> node.aliases += identityUrl(segmentUrl) }
        node.segments = maxOf(node.segments, hlsRefs.segments.size)
        node.record = node.record.copy(
            observationCount = node.observations,
            segmentCount = node.segments,
            logicalConfidence = node.rank,
            variantCount = node.variants.size.coerceAtLeast(node.record.variantCount).coerceAtLeast(1),
        )
        appendEvidence(observation, logicalId, if (manifestUrl || manifestBody) "manifest" else primary.kind.name)
        trim()
        return snapshot()
    }

    @Synchronized
    fun snapshot(): LogicalMediaGraphSnapshot {
        val evidenceByLogical = evidence.groupBy { it.logicalMediaId }
        val items = nodes.values
            .sortedWith(compareByDescending<Node> { it.rank }.thenByDescending { it.updatedAt }.thenBy { it.canonicalUrl })
            .map { node ->
                val safeCanonical = identityUrl(node.canonicalUrl)
                val variants = node.variants.values.sortedBy(MediaVariant::position)
                LogicalMediaItem(
                    logicalMediaId = node.id,
                    canonicalUrl = safeCanonical,
                    requestUrl = node.canonicalUrl,
                    record = node.record.copy(
                        sourceUrl = safeCanonical,
                        canonicalMediaUrl = safeCanonical,
                        observationCount = node.observations,
                        segmentCount = node.segments,
                        variantCount = variants.size.coerceAtLeast(node.record.variantCount).coerceAtLeast(1),
                    ),
                    variants = variants,
                    requestHeaders = node.requestHeaders.toMap(),
                    observationCount = node.observations,
                    segmentCount = node.segments,
                    rawEvidence = evidenceByLogical[node.id].orEmpty(),
                )
            }
        return LogicalMediaGraphSnapshot(items, totalObservations, evidence.size, evidence.toList())
    }

    @Synchronized
    fun clear() {
        nodes.clear(); aliasToNode.clear(); childToMaster.clear(); evidence.clear(); totalObservations = 0
    }

    private fun mergeNodes(targetId: String, sourceId: String) {
        val target = nodes[targetId] ?: return
        val source = nodes.remove(sourceId) ?: return
        target.observations += source.observations
        target.segments += source.segments
        target.rank = maxOf(target.rank, source.rank)
        target.updatedAt = maxOf(target.updatedAt, source.updatedAt)
        target.requestHeaders = mergeHeaders(target.requestHeaders, source.requestHeaders)
        source.variants.values.forEach { target.variants[variantIdentity(it)] = it.copy(captureId = target.record.id) }
        source.aliases.forEach { alias -> target.aliases += alias; aliasToNode[alias] = targetId }
        val remappedEvidence = evidence.map { item -> if (item.logicalMediaId == sourceId) item.copy(logicalMediaId = targetId, captureId = target.record.id) else item }
        evidence.clear()
        evidence.addAll(remappedEvidence)
        target.record = mergeRecord(target.record, source.record.copy(id = target.record.id, logicalMediaId = targetId))
    }

    private fun mergeRecord(existing: MediaCaptureRecord, incoming: MediaCaptureRecord): MediaCaptureRecord {
        val incomingMaster = incoming.manifestRole == MediaManifestRole.HlsMaster
        val existingMaster = existing.manifestRole == MediaManifestRole.HlsMaster
        val preferred = if (incomingMaster || !existingMaster && incoming.logicalConfidence >= existing.logicalConfidence) incoming else existing
        return preferred.copy(
            createdAtEpochMs = minOf(existing.createdAtEpochMs, incoming.createdAtEpochMs),
            updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, incoming.updatedAtEpochMs),
            durationMs = incoming.durationMs ?: existing.durationMs,
            thumbnailUrl = incoming.thumbnailUrl ?: existing.thumbnailUrl,
            thumbnailProvenance = if (incoming.thumbnailUrl != null) incoming.thumbnailProvenance else existing.thumbnailProvenance,
            observationCount = existing.observationCount + incoming.observationCount,
            segmentCount = maxOf(existing.segmentCount, incoming.segmentCount),
            logicalConfidence = maxOf(existing.logicalConfidence, incoming.logicalConfidence),
            protectionKind = strongestProtection(existing.protectionKind, incoming.protectionKind),
            nativeCapability = strongestCapability(existing.nativeCapability, incoming.nativeCapability),
        )
    }

    private fun appendEvidence(observation: MediaObservation, logicalId: String?, semantic: String) {
        val safeUrl = ExternalUrlPolicy.persistableUrl(observation.url) ?: identityUrl(observation.url)
        val safePage = observation.pageUrl?.let { ExternalUrlPolicy.persistableUrl(it) ?: identityUrl(it) }
        val key = listOf(logicalId.orEmpty(), safeUrl, observation.source.name, observation.initiator.orEmpty(), semantic).joinToString("|")
        evidence.add(
            MediaObservationRecord(
                id = "obs-${sha256(key).take(28)}-${observation.observedAtEpochMs}-${totalObservations}",
                logicalMediaId = logicalId,
                // Evidence is captured before a user necessarily admits the logical item.
                // Keep the FK nullable so observation persistence can never fail simply
                // because the media_captures parent has not been admitted yet.
                captureId = null,
                url = safeUrl,
                pageUrl = safePage,
                mimeType = normalizedMime(observation.mimeType),
                source = observation.source.name,
                initiator = observation.initiator?.take(64),
                semanticKind = semantic.take(48),
                observationCount = 1,
                firstObservedAtEpochMs = observation.observedAtEpochMs,
                lastObservedAtEpochMs = observation.observedAtEpochMs,
            ),
        )
        while (evidence.size > maxEvidence) evidence.removeFirst()
    }

    private fun trim() {
        while (nodes.size > maxLogicalItems) {
            val victim = nodes.values.minWithOrNull(compareBy<Node> { it.updatedAt }.thenBy { it.rank }) ?: break
            nodes.remove(victim.id)
            aliasToNode.entries.removeAll { it.value == victim.id }
        }
        while (aliasToNode.size > maxAliases) aliasToNode.remove(aliasToNode.entries.first().key)
        while (childToMaster.size > maxAliases) childToMaster.remove(childToMaster.entries.first().key)
    }

    private fun findSegmentParent(url: String, pageUrl: String?): String? {
        val id = identityUrl(url)
        aliasToNode[id]?.let { return it }
        val uri = runCatching { URI(id) }.getOrNull() ?: return null
        val path = uri.path.orEmpty()
        val parentPrefix = path.substringBeforeLast('/', "")
        return nodes.values
            .asSequence()
            .filter { it.record.kind == MediaSourceKind.HlsPlaylist }
            .filter { node ->
                val candidate = runCatching { URI(identityUrl(node.canonicalUrl)) }.getOrNull()
                candidate != null && candidate.host == uri.host && (parentPrefix.isBlank() || candidate.path.orEmpty().substringBeforeLast('/', "") == parentPrefix)
            }
            .maxByOrNull { it.updatedAt }
            ?.id
            ?: pageUrl?.let(::identityUrl)?.let(aliasToNode::get)
    }

    private fun classifyProtection(record: MediaCaptureRecord, summary: com.mikeyphw.xdm.android.media.MediaManifestSummary?, body: String?): Pair<MediaProtectionKind, MediaNativeCapability> {
        if (record.kind != MediaSourceKind.HlsPlaylist) {
            return if (record.manifestProtected) MediaProtectionKind.Drm to MediaNativeCapability.ProtectedUnsupported
            else MediaProtectionKind.None to MediaNativeCapability.NativeCandidate
        }
        val lines = body.orEmpty().lineSequence().filter { it.startsWith("#EXT-X-KEY", true) || it.startsWith("#EXT-X-SESSION-KEY", true) }.toList()
        val methods = lines.mapNotNull { Regex("METHOD=([^,]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim()?.uppercase(Locale.US) }
        val keyFormats = lines.mapNotNull { Regex("KEYFORMAT=\\\"?([^,\\\"]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim()?.lowercase(Locale.US) }
        val drmFormat = keyFormats.any { it != "identity" }
        val kind = when {
            drmFormat -> MediaProtectionKind.Drm
            methods.any { it.startsWith("SAMPLE-AES") } -> MediaProtectionKind.SampleAes
            methods.any { it == "AES-128" } -> MediaProtectionKind.Aes128
            methods.any { it != "NONE" } -> MediaProtectionKind.UnknownEncrypted
            summary?.hasDrm == true -> MediaProtectionKind.Drm
            else -> MediaProtectionKind.None
        }
        val capability = when (kind) {
            MediaProtectionKind.None, MediaProtectionKind.Aes128 -> if (isLowLatencyHls(body)) MediaNativeCapability.FallbackRequired else MediaNativeCapability.NativeCandidate
            MediaProtectionKind.SampleAes, MediaProtectionKind.Drm, MediaProtectionKind.UnknownEncrypted -> MediaNativeCapability.ProtectedUnsupported
        }
        return kind to capability
    }

    private fun parseHlsReferences(base: String, body: String): HlsReferences {
        val playlists = linkedSetOf<String>()
        val segments = linkedSetOf<String>()
        var expectPlaylist = false
        body.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF", true) -> expectPlaylist = true
                line.startsWith("#EXT-X-MEDIA", true) -> {
                    Regex("URI=\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.let { playlists += resolve(base, it) }
                }
                line.startsWith("#") -> Unit
                expectPlaylist || line.substringBefore('?').endsWith(".m3u8", true) -> { playlists += resolve(base, line); expectPlaylist = false }
                else -> segments += resolve(base, line)
            }
        }
        return HlsReferences(playlists.toList(), segments.toList())
    }

    private data class HlsReferences(val playlists: List<String> = emptyList(), val segments: List<String> = emptyList())

    companion object {
        private const val MAX_BODY_PREFIX = 768 * 1024
        fun identityUrl(raw: String): String {
            val normalized = ExternalUrlPolicy.normalizedUrl(raw) ?: raw.substringBefore('#').trim()
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized
            val volatileNames = setOf("token", "access_token", "auth", "auth_token", "sig", "signature", "expires", "exp", "md5", "sess", "session", "sessionid", "policy", "key", "hdnea", "hdnts", "jwt", "ticket")
            val query = uri.rawQuery?.split('&').orEmpty().mapNotNull { part ->
                val name = part.substringBefore('=').lowercase(Locale.US).replace('-', '_')
                val cloudCredential = name.matches(Regex("x_(?:amz|goog)_(?:credential|security_token|signature|expires|date)"))
                if (name in volatileNames || name.endsWith("_token") || name.endsWith("_signature") || name.endsWith("_session") || name.endsWith("_credential") || cloudCredential) null else part
            }.sorted().joinToString("&").takeIf(String::isNotBlank)
            val port = if (uri.port < 0 || uri.scheme.equals("https", true) && uri.port == 443 || uri.scheme.equals("http", true) && uri.port == 80) "" else ":${uri.port}"
            return buildString {
                append(uri.scheme?.lowercase(Locale.US)).append("://").append(uri.host?.lowercase(Locale.US)).append(port)
                append(uri.rawPath?.ifBlank { "/" } ?: "/")
                if (query != null) append('?').append(query)
            }
        }

        fun logicalIdFor(identity: String, pageUrl: String?, kind: MediaSourceKind, stableHint: String? = null): String {
            @Suppress("UNUSED_VARIABLE") val evidenceOnly = pageUrl to kind
            val key = stableHint?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,160}")) } ?: identityUrl(identity)
            return "logical-media-${sha256("logical-media-v2|$key").take(32)}"
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun normalizedMime(mime: String?): String? = mime?.substringBefore(';')?.trim()?.lowercase(Locale.US)?.takeIf(String::isNotBlank)
        private fun normalizeHttp(raw: String): String? = ExternalUrlPolicy.normalizedUrl(raw)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        private fun isHlsUrl(url: String, mime: String?): Boolean = normalizedMime(mime) in MediaCandidateClassifier.HlsMimeTypes || runCatching { URI(url).path.orEmpty().endsWith(".m3u8", true) }.getOrDefault(false)
        private fun isSegment(url: String, mime: String?, initiator: String?): Boolean {
            val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.US) }.getOrDefault(url.lowercase(Locale.US))
            if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".cmfv") || path.endsWith(".cmfa")) return true
            if (initiator?.contains("segment", true) == true || initiator?.contains("fragment", true) == true) return true
            return normalizedMime(mime) in setOf("video/mp2t") && Regex("(?:segment|seg|part|chunk|/\\d{2,})(?:[-_.?/]|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)
        }
        private fun isObviousApi(url: String, mime: String?): Boolean {
            val m = normalizedMime(mime)
            if (m in setOf("application/json", "application/ld+json", "text/json")) return true
            val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.US) }.getOrDefault("")
            return (path.endsWith(".json") || path.contains("/api/")) && m?.startsWith("video/") != true && m?.startsWith("audio/") != true
        }
        private fun resolve(base: String, value: String): String = runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
        private fun recordPriority(record: MediaCaptureRecord): Int = when (record.kind) {
            MediaSourceKind.HlsPlaylist, MediaSourceKind.DashManifest -> 5
            MediaSourceKind.ProgressiveMedia, MediaSourceKind.VideoStream -> 4
            MediaSourceKind.AudioStream -> 3
            MediaSourceKind.DirectFile -> 2
            MediaSourceKind.Unknown -> 0
        }
        private fun variantIdentity(v: MediaVariant): String = listOf(v.kind.name, identityUrl(v.url), v.language.orEmpty(), v.groupId.orEmpty()).joinToString("|")
        private fun mergeHeaders(old: Map<String, String>, incoming: Map<String, String>): Map<String, String> = if (incoming.isEmpty()) old else old + incoming
        private fun isLowLatencyHls(body: String?): Boolean = body?.let { it.contains("#EXT-X-PART", true) || it.contains("#EXT-X-PRELOAD-HINT", true) || it.contains("#EXT-X-SERVER-CONTROL", true) } == true
        private fun strongestProtection(a: MediaProtectionKind, b: MediaProtectionKind): MediaProtectionKind = listOf(a, b).maxBy { when (it) { MediaProtectionKind.None -> 0; MediaProtectionKind.Aes128 -> 1; MediaProtectionKind.UnknownEncrypted -> 2; MediaProtectionKind.SampleAes -> 3; MediaProtectionKind.Drm -> 4 } }
        private fun strongestCapability(a: MediaNativeCapability, b: MediaNativeCapability): MediaNativeCapability = listOf(a, b).maxBy { when (it) { MediaNativeCapability.Unknown -> 0; MediaNativeCapability.NativeCandidate -> 1; MediaNativeCapability.FallbackRequired -> 2; MediaNativeCapability.ProtectedUnsupported -> 3 } }
    }
}
