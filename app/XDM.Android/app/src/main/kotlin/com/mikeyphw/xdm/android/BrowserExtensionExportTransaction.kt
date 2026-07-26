package com.mikeyphw.xdm.android

import java.io.File
import java.util.UUID

internal data class BrowserExtensionDocumentSnapshot(
    val file: File,
    val byteCount: Long,
    val sha256: String,
)

internal interface BrowserExtensionDocumentGateway<Document> {
    fun find(displayName: String): Document?
    fun create(displayName: String, mimeType: String): Document
    fun writeAndVerify(document: Document, source: File, expectedBytes: Long, expectedSha256: String)
    fun snapshot(document: Document): BrowserExtensionDocumentSnapshot
    fun rename(document: Document, displayName: String): Document?
    fun delete(document: Document): Boolean
}

internal class BrowserExtensionExportTransaction<Document>(
    private val gateway: BrowserExtensionDocumentGateway<Document>,
) {
    fun commit(
        finalName: String,
        source: File,
        expectedBytes: Long,
        expectedSha256: String,
    ): Document {
        require(finalName.endsWith(".xpi")) { "Final browser extension filename must end in .xpi" }
        val nonce = UUID.randomUUID().toString().take(12)
        val stageName = ".$finalName.$nonce.part"
        var stage: Document? = gateway.create(stageName, StagingMimeType)
        var providerBackup: Document?
        var localBackup: BrowserExtensionDocumentSnapshot? = null
        var retainLocalBackup = false
        try {
            gateway.writeAndVerify(requireNotNull(stage), source, expectedBytes, expectedSha256)
            val original = gateway.find(finalName)

            if (original == null) {
                gateway.rename(requireNotNull(stage), finalName)?.let { promoted ->
                    stage = null
                    return promoted
                }
                return copyIntoNewFinal(
                    finalName = finalName,
                    source = source,
                    expectedBytes = expectedBytes,
                    expectedSha256 = expectedSha256,
                    stage = requireNotNull(stage),
                ).also { stage = null }
            }

            val backupName = ".$finalName.$nonce.backup"
            providerBackup = gateway.rename(original, backupName)
            if (providerBackup != null) {
                gateway.rename(requireNotNull(stage), finalName)?.let { promoted ->
                    stage = null
                    gateway.delete(requireNotNull(providerBackup))
                    providerBackup = null
                    return promoted
                }

                var finalDocument: Document? = null
                return try {
                    finalDocument = gateway.create(finalName, FirefoxExtensionMimeType)
                    gateway.writeAndVerify(requireNotNull(finalDocument), source, expectedBytes, expectedSha256)
                    gateway.delete(requireNotNull(stage))
                    stage = null
                    gateway.delete(requireNotNull(providerBackup))
                    providerBackup = null
                    requireNotNull(finalDocument)
                } catch (failure: Throwable) {
                    finalDocument?.let(gateway::delete)
                    gateway.rename(requireNotNull(providerBackup), finalName)?.let { providerBackup = null }
                    throw failure
                }
            }

            // Some SAF providers can create and write documents but refuse rename. Snapshot the
            // existing XPI locally before overwriting its stable URI so a failed write can recover.
            localBackup = gateway.snapshot(original)
            return try {
                gateway.writeAndVerify(original, source, expectedBytes, expectedSha256)
                gateway.delete(requireNotNull(stage))
                stage = null
                original
            } catch (failure: Throwable) {
                val snapshot = requireNotNull(localBackup)
                runCatching {
                    gateway.writeAndVerify(original, snapshot.file, snapshot.byteCount, snapshot.sha256)
                }.onFailure { restoreFailure ->
                    retainLocalBackup = true
                    failure.addSuppressed(
                        IllegalStateException(
                            "Existing XPI restoration failed; verified cache backup retained at ${snapshot.file.absolutePath}",
                            restoreFailure,
                        ),
                    )
                }
                throw failure
            }
        } finally {
            stage?.let(gateway::delete)
            if (!retainLocalBackup) localBackup?.file?.delete()
            // A provider backup is retained only when the provider refused restoration. Keeping
            // verified old bytes is safer than deleting the last recoverable package.
        }
    }

    private fun copyIntoNewFinal(
        finalName: String,
        source: File,
        expectedBytes: Long,
        expectedSha256: String,
        stage: Document,
    ): Document {
        val finalDocument = gateway.create(finalName, FirefoxExtensionMimeType)
        return try {
            gateway.writeAndVerify(finalDocument, source, expectedBytes, expectedSha256)
            gateway.delete(stage)
            finalDocument
        } catch (failure: Throwable) {
            gateway.delete(finalDocument)
            throw failure
        }
    }

    companion object {
        const val FirefoxExtensionMimeType = "application/x-xpinstall"
        const val StagingMimeType = "application/octet-stream"
    }
}
