package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.browserextension.BrowserExtensionHash
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionExportTransactionTest {
    private val finalName = "XDM-Android-Firefox-1.0.0-release-dark.xpi"

    @Test
    fun `rename-capable provider promotes staged file and removes old version`() {
        val source = tempXpi("new package")
        val gateway = FakeGateway(renameSupported = true).apply {
            create(finalName, "application/x-xpinstall").bytes = "old package".toByteArray()
        }
        val result = commit(gateway, source)
        assertEquals(finalName, result.name)
        assertEquals("new package", String(result.bytes))
        assertFalse(gateway.documents.any { it.name.endsWith(".part") || it.name.endsWith(".backup") })
    }

    @Test
    fun `rename-capable provider restores old xpi when replacement document creation fails`() {
        val source = tempXpi("new package")
        val gateway = FakeGateway(renameSupported = true).apply {
            create(finalName, "application/x-xpinstall").bytes = "old package".toByteArray()
            failStagePromotion = true
            failNextFinalCreate = true
        }
        val failure = runCatching { commit(gateway, source) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("old package", String(requireNotNull(gateway.find(finalName)).bytes))
        assertFalse(gateway.documents.any { it.name.endsWith(".part") || it.name.endsWith(".backup") })
    }

    @Test
    fun `provider without rename uses verified copy fallback for first export`() {
        val source = tempXpi("fallback package")
        val gateway = FakeGateway(renameSupported = false)
        val result = commit(gateway, source)
        assertEquals("fallback package", String(result.bytes))
        assertEquals(1, gateway.documents.count { it.name.endsWith(".xpi") })
        assertFalse(gateway.documents.any { it.name.endsWith(".part") })
    }

    @Test
    fun `provider without rename snapshots and safely replaces existing xpi`() {
        val source = tempXpi("replacement package")
        val gateway = FakeGateway(renameSupported = false).apply {
            create(finalName, "application/x-xpinstall").bytes = "old package".toByteArray()
        }
        val result = commit(gateway, source)
        assertEquals(finalName, result.name)
        assertEquals("replacement package", String(result.bytes))
        assertEquals(1, gateway.snapshotCount)
        assertFalse(gateway.documents.any { it.name.endsWith(".part") })
    }

    @Test
    fun `failed replacement restores old xpi when provider cannot rename`() {
        val source = tempXpi("will fail")
        val gateway = FakeGateway(renameSupported = false, failSourceText = "will fail").apply {
            create(finalName, "application/x-xpinstall").bytes = "old package".toByteArray()
        }
        val failure = runCatching { commit(gateway, source) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("old package", String(requireNotNull(gateway.find(finalName)).bytes))
        assertEquals(1, gateway.snapshotCount)
        assertFalse(gateway.documents.any { it.name.endsWith(".part") })
    }

    @Test
    fun `failed first final write removes partial xpi and staging file`() {
        val source = tempXpi("will fail")
        val gateway = FakeGateway(renameSupported = false, failSourceText = "will fail")
        val failure = runCatching { commit(gateway, source) }.exceptionOrNull()
        assertNotNull(failure)
        assertNull(gateway.documents.firstOrNull { it.name.endsWith(".xpi") })
        assertFalse(gateway.documents.any { it.name.endsWith(".part") })
    }

    private fun commit(gateway: FakeGateway, source: File): Document =
        BrowserExtensionExportTransaction(gateway).commit(
            finalName = finalName,
            source = source,
            expectedBytes = source.length(),
            expectedSha256 = source.inputStream().use(BrowserExtensionHash::digest),
        )

    private fun tempXpi(text: String): File = File.createTempFile("browser-extension-export", ".xpi").apply {
        writeText(text)
        deleteOnExit()
    }

    private data class Document(var name: String, var bytes: ByteArray = byteArrayOf())

    private class FakeGateway(
        private val renameSupported: Boolean,
        private val failSourceText: String? = null,
    ) : BrowserExtensionDocumentGateway<Document> {
        val documents = mutableListOf<Document>()
        var snapshotCount: Int = 0
            private set
        var failNextFinalCreate: Boolean = false
        var failStagePromotion: Boolean = false

        override fun find(displayName: String): Document? = documents.firstOrNull { it.name == displayName }

        override fun create(displayName: String, mimeType: String): Document {
            if (failNextFinalCreate && displayName.endsWith(".xpi")) {
                failNextFinalCreate = false
                error("simulated provider create failure")
            }
            return Document(displayName).also(documents::add)
        }

        override fun writeAndVerify(document: Document, source: File, expectedBytes: Long, expectedSha256: String) {
            val bytes = source.readBytes()
            if (failSourceText != null && document.name.endsWith(".xpi") && String(bytes) == failSourceText) {
                error("simulated low-storage failure")
            }
            document.bytes = bytes
            assertEquals(expectedBytes, document.bytes.size.toLong())
            assertEquals(expectedSha256, BrowserExtensionHash.sha256(document.bytes))
        }

        override fun snapshot(document: Document): BrowserExtensionDocumentSnapshot {
            snapshotCount += 1
            val file = File.createTempFile("browser-extension-snapshot", ".xpi").apply {
                writeBytes(document.bytes)
                deleteOnExit()
            }
            return BrowserExtensionDocumentSnapshot(
                file = file,
                byteCount = file.length(),
                sha256 = file.inputStream().use(BrowserExtensionHash::digest),
            )
        }

        override fun rename(document: Document, displayName: String): Document? {
            if (!renameSupported) return null
            if (failStagePromotion && document.name.endsWith(".part") && displayName.endsWith(".xpi")) return null
            document.name = displayName
            return document
        }

        override fun delete(document: Document): Boolean = documents.remove(document)
    }
}
