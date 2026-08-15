package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCaptureImportJournalTest {
    @Test
    fun encryptedEnvelopeIsDurableWithoutPlaintextOrCallerMetadata() {
        val root = Files.createTempDirectory("xdm-browser-import").toFile()
        try {
            val journal = BrowserCaptureImportJournal(root, JvmAtomicJournalFileIo())
            val payload = encryptedPayload()
            journal.begin(payload, receivedAtEpochMs = 1234L)

            val entry = journal.pending(payload.captureSessionId).single()
            assertEquals(payload, entry.payload)
            assertEquals(1234L, entry.receivedAtEpochMs)

            val raw = root.listFiles()!!.filter { it.extension == "properties" }.single().readText()
            assertTrue(raw.contains("envelopeCiphertext"))
            assertTrue(raw.contains("wrappedKey"))
            assertFalse(raw.contains("observedPackage"))
            assertFalse(raw.contains("org.mozilla.firefox"))
            assertFalse(raw.contains("https://media.example"))
            assertFalse(raw.contains("Authorization:"))
            assertFalse(raw.contains("Cookie:"))

            journal.complete(requireNotNull(payload.captureSessionId))
            assertTrue(journal.pending().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidOrConflictingSessionCannotReplaceExistingEncryptedJournal() {
        val root = Files.createTempDirectory("xdm-browser-import-conflict").toFile()
        try {
            val journal = BrowserCaptureImportJournal(root, JvmAtomicJournalFileIo())
            val first = encryptedPayload()
            journal.begin(first, receivedAtEpochMs = 1L)
            journal.begin(first, receivedAtEpochMs = 2L)
            assertEquals(1L, journal.pending().single().receivedAtEpochMs)

            val conflicting = first.copy(envelopeCiphertext = "different-ciphertext")
            assertTrue(runCatching { journal.begin(conflicting, 3L) }.isFailure)
            assertEquals(first, journal.pending().single().payload)
            assertTrue(journal.pending("../../bad").isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedAtomicPublishLeavesNoCommittedEntryAndRetryCanRecover() {
        val root = Files.createTempDirectory("xdm-browser-import-failure").toFile()
        try {
            val io = JvmAtomicJournalFileIo(failBeforeCommit = true)
            val journal = BrowserCaptureImportJournal(root, io)
            val payload = encryptedPayload()
            assertTrue(runCatching { journal.begin(payload, receivedAtEpochMs = 7L) }.isFailure)
            assertTrue(journal.pending().isEmpty())

            io.failBeforeCommit = false
            journal.begin(payload, receivedAtEpochMs = 8L)
            assertEquals(8L, journal.pending().single().receivedAtEpochMs)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun encryptedPayload() = XdmBrowserDeepLinkPayload(
        version = 2,
        action = AutomationCommandAction.CaptureMedia,
        captureSessionId = "session-12345678",
        captureKeyId = "capture-key-1234",
        wrappedKey = "wrapped-key-material",
        envelopeIv = "nonce-material",
        envelopeCiphertext = "ciphertext-material",
    )

    private class JvmAtomicJournalFileIo(
        var failBeforeCommit: Boolean = false,
    ) : BrowserCaptureImportJournalFileIo {
        override fun read(target: File): ByteArray? = target.takeIf(File::isFile)?.readBytes()

        override fun write(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".new")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (failBeforeCommit) throw IOException("simulated publish interruption")
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.delete()
            }
        }

        override fun delete(target: File) { target.delete() }
    }
}
