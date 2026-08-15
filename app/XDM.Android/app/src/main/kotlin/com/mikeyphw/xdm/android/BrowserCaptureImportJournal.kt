package com.mikeyphw.xdm.android

import android.util.AtomicFile
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties

internal interface BrowserCaptureImportJournalFileIo {
    fun read(target: File): ByteArray?
    fun write(target: File, bytes: ByteArray)
    fun delete(target: File)
}

internal object AndroidAtomicBrowserCaptureImportJournalFileIo : BrowserCaptureImportJournalFileIo {
    override fun read(target: File): ByteArray? = try {
        AtomicFile(target).openRead().use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }

    override fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    override fun delete(target: File) {
        AtomicFile(target).delete()
    }
}

/**
 * Crash-recovery journal for encrypted browser capture imports.
 *
 * The durable journal contains ciphertext/envelope metadata only. Decrypted URLs, request headers,
 * cookies, authorization values, and observed caller/package labels never enter this journal.
 */
class BrowserCaptureImportJournal internal constructor(
    private val root: File,
    private val fileIo: BrowserCaptureImportJournalFileIo = AndroidAtomicBrowserCaptureImportJournalFileIo,
) {
    data class Entry(
        val payload: XdmBrowserDeepLinkPayload,
        val receivedAtEpochMs: Long,
    )

    init { root.mkdirs() }

    @Synchronized
    fun begin(payload: XdmBrowserDeepLinkPayload, receivedAtEpochMs: Long = System.currentTimeMillis()) {
        require(payload.action == AutomationCommandAction.CaptureMedia && payload.hasEncryptedCaptureEnvelope) {
            "Only encrypted browser capture sessions may enter the import journal"
        }
        val sessionId = requireNotNull(payload.captureSessionId).requireSafeToken()
        val target = fileFor(sessionId)
        val existingBytes = fileIo.read(target)
        if (existingBytes != null) {
            val existing = read(existingBytes) ?: error("Existing browser capture import journal is unreadable")
            check(existing.payload == payload) { "Conflicting encrypted browser capture session id" }
            return
        }
        val props = Properties().apply {
            setProperty("version", payload.version.toString())
            setProperty("action", payload.action.name)
            setProperty("captureSessionId", sessionId)
            setProperty("captureKeyId", requireNotNull(payload.captureKeyId))
            setProperty("wrappedKey", requireNotNull(payload.wrappedKey))
            setProperty("envelopeIv", requireNotNull(payload.envelopeIv))
            setProperty("envelopeCiphertext", requireNotNull(payload.envelopeCiphertext))
            setProperty("receivedAtEpochMs", receivedAtEpochMs.toString())
        }
        root.mkdirs()
        val bytes = ByteArrayOutputStream().use { output ->
            props.store(output, "XDM encrypted browser-capture import journal; ciphertext only")
            output.toByteArray()
        }
        fileIo.write(target, bytes)
    }

    @Synchronized
    fun pending(sessionId: String? = null): List<Entry> {
        val wanted = sessionId?.let(::safeTokenOrNull)
        if (sessionId != null && wanted == null) return emptyList()
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".properties") }
            ?.filter { wanted == null || it.name == "${wanted}.properties" }
            ?.mapNotNull { file -> fileIo.read(file)?.let(::read) }
            ?.sortedBy(Entry::receivedAtEpochMs)
            ?.toList()
            .orEmpty()
    }

    @Synchronized
    fun complete(sessionId: String) {
        safeTokenOrNull(sessionId)?.let(::fileFor)?.let(fileIo::delete)
    }

    private fun read(bytes: ByteArray): Entry? = runCatching {
        val props = Properties().also { values -> ByteArrayInputStream(bytes).use(values::load) }
        val sessionId = props.getProperty("captureSessionId")?.let(::safeTokenOrNull) ?: return@runCatching null
        val action = runCatching { AutomationCommandAction.valueOf(props.getProperty("action")) }.getOrNull()
            ?: return@runCatching null
        if (action != AutomationCommandAction.CaptureMedia) return@runCatching null
        val payload = XdmBrowserDeepLinkPayload(
            version = props.getProperty("version")?.toIntOrNull() ?: return@runCatching null,
            action = action,
            captureSessionId = sessionId,
            captureKeyId = props.getProperty("captureKeyId")?.takeIf(String::isNotBlank) ?: return@runCatching null,
            wrappedKey = props.getProperty("wrappedKey")?.takeIf(String::isNotBlank) ?: return@runCatching null,
            envelopeIv = props.getProperty("envelopeIv")?.takeIf(String::isNotBlank) ?: return@runCatching null,
            envelopeCiphertext = props.getProperty("envelopeCiphertext")?.takeIf(String::isNotBlank) ?: return@runCatching null,
        )
        if (!payload.hasEncryptedCaptureEnvelope) return@runCatching null
        Entry(
            payload = payload,
            receivedAtEpochMs = props.getProperty("receivedAtEpochMs")?.toLongOrNull() ?: return@runCatching null,
        )
    }.getOrNull()

    private fun fileFor(sessionId: String): File = File(root, "$sessionId.properties")
    private fun String.requireSafeToken(): String = safeTokenOrNull(this)
        ?: throw IllegalArgumentException("Invalid browser capture session id")
    private fun safeTokenOrNull(value: String): String? = value.trim().takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,96}")) }
}
