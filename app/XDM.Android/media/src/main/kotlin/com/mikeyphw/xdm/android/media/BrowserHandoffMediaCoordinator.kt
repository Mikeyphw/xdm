package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.BrowserFrameContext
import com.mikeyphw.xdm.android.model.BrowserHandoffMediaPolicy
import com.mikeyphw.xdm.android.model.BrowserHeaderObservation
import com.mikeyphw.xdm.android.model.BrowserHeaderObservationKind
import com.mikeyphw.xdm.android.model.BrowserMediaSessionEviction
import com.mikeyphw.xdm.android.model.BrowserMediaSessionRevision
import com.mikeyphw.xdm.android.model.MediaSessionEvictionReason
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaTransferShape
import com.mikeyphw.xdm.android.model.PageObservationProof
import com.mikeyphw.xdm.android.model.PrivacyDiagnosticsRedactor
import com.mikeyphw.xdm.android.model.BackendCapabilities
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

interface BrowserHandoffMediaSessionStore {
    fun load(stableMediaId: String): BrowserMediaSessionRevision?
    fun put(session: BrowserMediaSessionRevision)
    fun remove(stableMediaId: String)
    fun ids(): Set<String>
}

class InMemoryBrowserHandoffMediaSessionStore : BrowserHandoffMediaSessionStore {
    private val sessions = ConcurrentHashMap<String, BrowserMediaSessionRevision>()
    override fun load(stableMediaId: String): BrowserMediaSessionRevision? = sessions[stableMediaId]
    @Synchronized
    override fun put(session: BrowserMediaSessionRevision) { sessions[session.stableMediaId] = session }
    override fun remove(stableMediaId: String) { sessions.remove(stableMediaId) }
    override fun ids(): Set<String> = sessions.keys.toSet()
}

class FileBackedBrowserHandoffMediaSessionStore(private val root: File) : BrowserHandoffMediaSessionStore {
    init { root.mkdirs() }

    @Synchronized
    override fun load(stableMediaId: String): BrowserMediaSessionRevision? {
        val file = fileFor(stableMediaId)
        if (!file.isFile) return null
        val props = Properties()
        file.inputStream().use(props::load)
        val proposed = readHeaders(props, "proposed")
        val final = readHeaders(props, "final")
        return BrowserMediaSessionRevision(
            stableMediaId = props.getProperty("stableMediaId") ?: stableMediaId,
            exactRequestUrl = props.getProperty("exactRequestUrl") ?: return null,
            pageUrl = props.getProperty("pageUrl")?.takeIf(String::isNotBlank),
            frameUrl = props.getProperty("frameUrl")?.takeIf(String::isNotBlank),
            requestFingerprint = props.getProperty("requestFingerprint")?.takeIf(String::isNotBlank) ?: stableMediaId,
            proposedHeaders = BrowserHeaderObservation(BrowserHeaderObservationKind.ProposedBeforeSend, proposed),
            finalHeaders = if (props.getProperty("finalAvailable") == "true") {
                BrowserHeaderObservation(BrowserHeaderObservationKind.FinalSent, final)
            } else {
                BrowserHeaderObservation(BrowserHeaderObservationKind.Unavailable, emptyMap(), props.getProperty("finalUnavailableReason") ?: "browser did not provide onSendHeaders data")
            },
            revision = props.getProperty("revision")?.toLongOrNull() ?: return null,
            expiresAtEpochMs = props.getProperty("expiresAtEpochMs")?.toLongOrNull() ?: return null,
            acknowledgedByAndroid = props.getProperty("acknowledgedByAndroid")?.toBoolean() ?: false,
        )
    }

    @Synchronized
    override fun put(session: BrowserMediaSessionRevision) {
        root.mkdirs()
        val target = fileFor(session.stableMediaId)
        val temp = File(root, target.name + ".tmp")
        val props = Properties()
        props["stableMediaId"] = session.stableMediaId
        props["exactRequestUrl"] = session.exactRequestUrl
        props["pageUrl"] = session.pageUrl.orEmpty()
        props["frameUrl"] = session.frameUrl.orEmpty()
        props["requestFingerprint"] = session.requestFingerprint
        props["revision"] = session.revision.toString()
        props["expiresAtEpochMs"] = session.expiresAtEpochMs.toString()
        props["acknowledgedByAndroid"] = session.acknowledgedByAndroid.toString()
        writeHeaders(props, "proposed", session.proposedHeaders.headers)
        if (session.finalHeaders.kind == BrowserHeaderObservationKind.FinalSent) {
            props["finalAvailable"] = "true"
            writeHeaders(props, "final", session.finalHeaders.headers)
        } else {
            props["finalAvailable"] = "false"
            props["finalUnavailableReason"] = session.finalHeaders.unavailableReason ?: "browser did not provide onSendHeaders data"
        }
        FileOutputStream(temp).use { out ->
            props.store(out, "XDM browser handoff media session")
            out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    override fun remove(stableMediaId: String) { fileFor(stableMediaId).delete() }

    @Synchronized
    override fun ids(): Set<String> = root.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".properties") }
        ?.map { it.name.removeSuffix(".properties") }
        ?.toSet()
        .orEmpty()

    private fun fileFor(id: String): File = File(root, sanitizeId(id) + ".properties")
    private fun sanitizeId(id: String): String = id.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ':' }.take(160).ifBlank { "unknown" }

    private fun writeHeaders(props: Properties, prefix: String, headers: Map<String, String>) {
        headers.entries.sortedBy { it.key.lowercase() }.forEach { (name, value) ->
            props["$prefix.${encode(name)}"] = value
        }
    }

    private fun readHeaders(props: Properties, prefix: String): Map<String, String> = props.stringPropertyNames()
        .filter { it.startsWith("$prefix.") }
        .associate { key -> decode(key.removePrefix("$prefix.")) to props.getProperty(key).orEmpty() }
        .filterKeys(String::isNotBlank)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}

class BrowserHandoffMediaCoordinator(
    private val maxSessions: Int = 128,
    private val clock: () -> Long = System::currentTimeMillis,
    private val store: BrowserHandoffMediaSessionStore = InMemoryBrowserHandoffMediaSessionStore(),
) {
    private val sessions = ConcurrentHashMap<String, BrowserMediaSessionRevision>()
    private val evictions = mutableListOf<BrowserMediaSessionEviction>()

    fun prepareBrowserRevision(
        requestUrl: String,
        topPageUrl: String?,
        frameUrl: String?,
        kind: MediaSourceKind,
        mimeType: String?,
        proposedHeaders: Map<String, String>,
        finalHeaders: Map<String, String>?,
        revision: Long,
        expiresAtEpochMs: Long,
        live: Boolean = false,
        protectedEvidence: Boolean = false,
        requestFingerprint: String? = null,
        pageObservationProof: PageObservationProof? = null,
        requirePageObservationProof: Boolean = false,
    ): BrowserMediaSessionRevision {
        if (requirePageObservationProof && !authenticatePageObservation(pageObservationProof)) {
            throw IllegalArgumentException("page observation proof required")
        }
        val fingerprint = requestFingerprint?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,96}")) }
            ?: "app-request-" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(listOf(requestUrl.substringBefore('#'), revision.toString(), frameUrl.orEmpty()).joinToString("|").toByteArray())
                .joinToString("") { "%02x".format(it) }.take(24)
        val shape = BrowserHandoffMediaPolicy.classifyShape(kind, topPageUrl, mimeType, live, protectedEvidence)
        val stableId = BrowserHandoffMediaPolicy.stableMediaId(topPageUrl, frameUrl, requestUrl, shape, fingerprint)
        return BrowserMediaSessionRevision(
            stableMediaId = stableId,
            exactRequestUrl = requestUrl,
            pageUrl = topPageUrl,
            frameUrl = frameUrl,
            requestFingerprint = fingerprint,
            proposedHeaders = BrowserHeaderObservation(BrowserHeaderObservationKind.ProposedBeforeSend, proposedHeaders.sanitizeHeaders()),
            finalHeaders = finalHeaders?.let { BrowserHeaderObservation(BrowserHeaderObservationKind.FinalSent, it.sanitizeHeaders()) }
                ?: BrowserHeaderObservation(BrowserHeaderObservationKind.Unavailable, emptyMap(), "browser did not provide onSendHeaders data"),
            revision = revision,
            expiresAtEpochMs = expiresAtEpochMs,
            acknowledgedByAndroid = true,
        )
    }

    fun rememberPreparedRevision(prepared: BrowserMediaSessionRevision): BrowserMediaSessionRevision {
        val existing = sessions[prepared.stableMediaId] ?: store.load(prepared.stableMediaId)
        if (!BrowserHandoffMediaPolicy.shouldReplaceSession(existing?.revision, prepared.revision)) return existing!!
        evictExpired(clock())
        evictForCapacity()
        sessions[prepared.stableMediaId] = prepared
        store.put(prepared)
        return prepared
    }

    fun rememberBrowserRevision(
        requestUrl: String,
        topPageUrl: String?,
        frameUrl: String?,
        kind: MediaSourceKind,
        mimeType: String?,
        proposedHeaders: Map<String, String>,
        finalHeaders: Map<String, String>?,
        revision: Long,
        expiresAtEpochMs: Long,
        live: Boolean = false,
        protectedEvidence: Boolean = false,
        declaredStableMediaId: String? = null,
        requestFingerprint: String? = null,
        pageObservationProof: PageObservationProof? = null,
        requirePageObservationProof: Boolean = false,
    ): BrowserMediaSessionRevision {
        // Browser-declared stable IDs are diagnostic compatibility data only; Android computes
        // authoritative identity from exact request context plus requestFingerprint.
        @Suppress("UNUSED_VARIABLE") val ignoredDeclaredStableMediaId = declaredStableMediaId
        return rememberPreparedRevision(
            prepareBrowserRevision(
                requestUrl = requestUrl,
                topPageUrl = topPageUrl,
                frameUrl = frameUrl,
                kind = kind,
                mimeType = mimeType,
                proposedHeaders = proposedHeaders,
                finalHeaders = finalHeaders,
                revision = revision,
                expiresAtEpochMs = expiresAtEpochMs,
                live = live,
                protectedEvidence = protectedEvidence,
                requestFingerprint = requestFingerprint,
                pageObservationProof = pageObservationProof,
                requirePageObservationProof = requirePageObservationProof,
            ),
        )
    }

    fun sessionFor(stableMediaId: String): BrowserMediaSessionRevision? {
        val id = stableMediaId.sanitizedStableMediaId() ?: return null
        val session = sessions[id] ?: store.load(id) ?: return null
        return session.takeUnless { it.expiresAtEpochMs <= clock() }
    }

    fun forget(stableMediaId: String, reason: MediaSessionEvictionReason = MediaSessionEvictionReason.ManualForget) {
        val id = stableMediaId.sanitizedStableMediaId() ?: return
        val removed = sessions.remove(id) ?: store.load(id) ?: return
        store.remove(id)
        synchronized(evictions) { evictions += BrowserMediaSessionEviction(id, removed.revision, reason, markCaptureSessionLost = reason != MediaSessionEvictionReason.TerminalCapture) }
    }

    fun evictionSnapshot(): List<BrowserMediaSessionEviction> = synchronized(evictions) { evictions.toList() }

    fun authenticatePageObservation(proof: PageObservationProof?): Boolean = proof?.accepts(clock()) == true

    fun frameContext(topPageUrl: String?, frameUrl: String?, requestUrl: String): BrowserFrameContext = BrowserFrameContext(topPageUrl, frameUrl, requestUrl)

    fun selectBackendForShape(shape: MediaTransferShape, capabilities: Map<BackendType, BackendCapabilities>): BackendSelectionForMedia {
        val rejected = mutableListOf<String>()
        fun available(backend: BackendType): Boolean {
            val caps = capabilities[backend]
            val ok = caps != null
            if (!ok) rejected += "${backend.name}:unavailable"
            return ok
        }
        val selected = when (shape) {
            MediaTransferShape.DirectFile -> if (available(BackendType.Aria2)) BackendType.Aria2 else BackendType.Native
            MediaTransferShape.DirectMedia -> if (available(BackendType.Aria2)) BackendType.Aria2 else BackendType.Native
            MediaTransferShape.AdaptivePlaylist, MediaTransferShape.SiteResolver, MediaTransferShape.LiveRecording -> BackendType.Automatic
            MediaTransferShape.ProtectedDiagnostic -> BackendType.Automatic
        }
        return BackendSelectionForMedia(shape, selected, rejected, shape != MediaTransferShape.ProtectedDiagnostic)
    }

    private fun evictExpired(now: Long) {
        val ids = (sessions.keys + store.ids()).toSet()
        ids.forEach { id ->
            val session = sessions[id] ?: store.load(id)
            if (session != null && session.expiresAtEpochMs <= now) forget(id, MediaSessionEvictionReason.Expired)
        }
    }

    private fun evictForCapacity() {
        while ((sessions.keys + store.ids()).size >= maxSessions) {
            val oldest = (sessions.keys + store.ids())
                .mapNotNull { id -> (sessions[id] ?: store.load(id))?.let { id to it } }
                .minByOrNull { it.second.expiresAtEpochMs }
                ?: return
            forget(oldest.first, MediaSessionEvictionReason.Capacity)
        }
    }

    private fun String?.sanitizedStableMediaId(): String? = this
        ?.trim()
        ?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,160}")) }

    private fun Map<String, String>.sanitizeHeaders(): Map<String, String> = entries
        .filter { (name, value) -> name.isNotBlank() && value.isNotBlank() && name.none { it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' } }
        .associate { (name, value) -> name.trim() to value.take(8192) }

    fun safeDiagnosticsFor(session: BrowserMediaSessionRevision): List<String> = listOf(
        session.redactedSummary,
        "headers=${PrivacyDiagnosticsRedactor.redactHeaders(session.usableHeaders.entries.joinToString("\n") { it.key + ": " + it.value })}",
    )
}

data class BackendSelectionForMedia(
    val shape: MediaTransferShape,
    val selectedBackend: BackendType,
    val rejectedEngines: List<String>,
    val canStartWithoutReview: Boolean,
)
