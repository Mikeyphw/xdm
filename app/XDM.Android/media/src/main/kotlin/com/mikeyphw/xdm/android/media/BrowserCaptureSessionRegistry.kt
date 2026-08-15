package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.BrowserCaptureCandidateSummary
import com.mikeyphw.xdm.android.model.BrowserCaptureSessionSummary
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Durable, non-secret index used to group browser captures in the Media inbox. */
class BrowserCaptureSessionRegistry(private val root: File) {
    private val _sessions = MutableStateFlow(loadAll())
    val sessions: StateFlow<List<BrowserCaptureSessionSummary>> = _sessions

    init { root.mkdirs() }

    @Synchronized
    fun record(summary: BrowserCaptureSessionSummary) {
        root.mkdirs()
        val target = fileFor(summary.sessionId)
        val existing = load(target)
        if (existing != null && existing.revision > summary.revision) {
            refresh()
            return
        }
        atomicWrite(target, propertiesFor(summary))
        refresh()
    }

    @Synchronized
    fun removeCapture(captureId: String) {
        val updated = loadAll().mapNotNull { session ->
            val remaining = session.candidates.filterNot { it.captureId == captureId }
            when {
                remaining.size == session.candidates.size -> session
                remaining.isEmpty() -> {
                    fileFor(session.sessionId).delete()
                    null
                }
                else -> session.copy(
                    candidates = remaining,
                    importedCandidateCount = remaining.size,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ).also(::recordWithoutRefresh)
            }
        }
        _sessions.value = updated.sortedByDescending(BrowserCaptureSessionSummary::updatedAtEpochMs)
    }

    fun snapshot(): List<BrowserCaptureSessionSummary> = _sessions.value

    private fun recordWithoutRefresh(summary: BrowserCaptureSessionSummary) {
        val target = fileFor(summary.sessionId)
        val existing = load(target)
        if (existing != null && existing.revision > summary.revision) return
        atomicWrite(target, propertiesFor(summary))
    }

    private fun propertiesFor(summary: BrowserCaptureSessionSummary): Properties = Properties().apply {
        setProperty("sessionId", summary.sessionId)
        setProperty("revision", summary.revision.toString())
        setProperty("pageTitle", summary.pageTitle)
        setProperty("pageHost", summary.pageHost)
        setProperty("createdAt", summary.createdAtEpochMs.toString())
        setProperty("updatedAt", summary.updatedAtEpochMs.toString())
        setProperty("totalCandidateCount", summary.totalCandidateCount.toString())
        setProperty("importedCandidateCount", summary.importedCandidateCount.toString())
        setProperty("truncated", summary.truncated.toString())
        summary.candidates.take(MAX_CANDIDATES).forEachIndexed { index, candidate ->
            setProperty("candidate.$index.captureId", candidate.captureId)
            setProperty("candidate.$index.stableMediaId", candidate.stableMediaId)
            setProperty("candidate.$index.quality", candidate.quality)
            setProperty("candidate.$index.reason", candidate.reason)
            setProperty("candidate.$index.mediaKind", candidate.mediaKind)
            setProperty("candidate.$index.evidence", candidate.evidence.joinToString("|") { encode(it) })
        }
        setProperty("candidate.count", summary.candidates.take(MAX_CANDIDATES).size.toString())
    }

    private fun atomicWrite(target: File, props: Properties) {
        root.mkdirs()
        val bytes = ByteArrayOutputStream().use { output ->
            props.store(output, "XDM browser capture session index; no URLs or request headers")
            output.toByteArray()
        }
        val temp = File(root, target.name + ".new")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun refresh() {
        _sessions.value = loadAll().sortedByDescending(BrowserCaptureSessionSummary::updatedAtEpochMs)
    }

    private fun loadAll(): List<BrowserCaptureSessionSummary> = root.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.name.endsWith(".properties") }
        ?.mapNotNull(::load)
        ?.sortedByDescending(BrowserCaptureSessionSummary::updatedAtEpochMs)
        ?.toList()
        .orEmpty()

    private fun load(file: File): BrowserCaptureSessionSummary? = runCatching {
        val props = Properties().also { values -> file.inputStream().use(values::load) }
        val count = props.getProperty("candidate.count")?.toIntOrNull()?.coerceIn(0, MAX_CANDIDATES) ?: 0
        val candidates = (0 until count).mapNotNull { index ->
            val captureId = props.getProperty("candidate.$index.captureId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            BrowserCaptureCandidateSummary(
                captureId = captureId,
                stableMediaId = props.getProperty("candidate.$index.stableMediaId").orEmpty(),
                quality = props.getProperty("candidate.$index.quality").orEmpty(),
                reason = props.getProperty("candidate.$index.reason").orEmpty(),
                mediaKind = props.getProperty("candidate.$index.mediaKind").orEmpty(),
                evidence = props.getProperty("candidate.$index.evidence").orEmpty().split('|').mapNotNull { decode(it).takeIf(String::isNotBlank) },
            )
        }
        BrowserCaptureSessionSummary(
            sessionId = props.getProperty("sessionId") ?: return@runCatching null,
            revision = props.getProperty("revision")?.toLongOrNull() ?: return@runCatching null,
            pageTitle = props.getProperty("pageTitle").orEmpty(),
            pageHost = props.getProperty("pageHost").orEmpty(),
            createdAtEpochMs = props.getProperty("createdAt")?.toLongOrNull() ?: 0L,
            updatedAtEpochMs = props.getProperty("updatedAt")?.toLongOrNull() ?: 0L,
            totalCandidateCount = props.getProperty("totalCandidateCount")?.toIntOrNull() ?: candidates.size,
            importedCandidateCount = props.getProperty("importedCandidateCount")?.toIntOrNull() ?: candidates.size,
            truncated = props.getProperty("truncated")?.toBoolean() ?: false,
            candidates = candidates,
        )
    }.getOrNull()

    private fun fileFor(sessionId: String): File = File(root, sessionId.filter { it.isLetterOrDigit() || it in "._:-" }.take(96).ifBlank { "unknown" } + ".properties")
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    companion object { private const val MAX_CANDIDATES = 24 }
}
