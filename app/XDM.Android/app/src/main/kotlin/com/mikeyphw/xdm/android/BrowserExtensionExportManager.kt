package com.mikeyphw.xdm.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionBuildConfig
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionHash
import com.mikeyphw.xdm.android.browserextension.BrowserExtensionPackageGenerator
import java.io.File
import java.io.FileNotFoundException

class BrowserExtensionExportManager(
    context: Context,
    private val generator: BrowserExtensionPackageGenerator = BrowserExtensionPackageGenerator(),
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    data class Success(
        val uri: String,
        val fileName: String,
        val byteCount: Long,
        val sha256: String,
    )

    fun persistDirectoryPermission(uriString: String): Result<Unit> = runCatching {
        val uri = Uri.parse(uriString)
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        require(hasWritePermission(uri)) { "Android did not retain write access to the selected folder" }
    }

    fun export(treeUriString: String, config: BrowserExtensionBuildConfig): Result<Success> = runCatching {
        val treeUri = Uri.parse(treeUriString)
        require(treeUri.scheme == "content") { "Choose an Android document-tree folder" }
        require(hasWritePermission(treeUri)) { "Export folder permission is no longer available" }

        val temp = File.createTempFile("xdm-firefox-", ".xpi.tmp", appContext.cacheDir)
        try {
            val generated = generator.generateToFile(config, temp)
            require(temp.length() == generated.byteCount) { "Temporary XPI byte-count mismatch" }
            val localHash = temp.inputStream().buffered().use(BrowserExtensionHash::digest)
            require(localHash == generated.sha256) { "Temporary XPI checksum mismatch" }

            val gateway = SafDocumentGateway(treeUri)
            val finalDocument = BrowserExtensionExportTransaction(gateway).commit(
                finalName = generated.fileName,
                source = temp,
                expectedBytes = generated.byteCount,
                expectedSha256 = generated.sha256,
            )
            Success(
                uri = finalDocument.uri.toString(),
                fileName = generated.fileName,
                byteCount = generated.byteCount,
                sha256 = generated.sha256,
            )
        } finally {
            temp.delete()
        }
    }

    private fun hasWritePermission(uri: Uri): Boolean = resolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isWritePermission
    }

    private inner class SafDocumentGateway(
        private val treeUri: Uri,
    ) : BrowserExtensionDocumentGateway<SafDocument> {
        private val rootDocumentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

        override fun find(displayName: String): SafDocument? {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == displayName) {
                        return SafDocument(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn)),
                            displayName = displayName,
                        )
                    }
                }
            }
            return null
        }

        override fun create(displayName: String, mimeType: String): SafDocument {
            val uri = DocumentsContract.createDocument(resolver, rootDocumentUri, mimeType, displayName)
                ?: throw FileNotFoundException("The selected folder could not create $displayName")
            return SafDocument(uri, displayName)
        }

        override fun writeAndVerify(
            document: SafDocument,
            source: File,
            expectedBytes: Long,
            expectedSha256: String,
        ) {
            resolver.openOutputStream(document.uri, "w")?.buffered().use { output ->
                requireNotNull(output) { "The selected folder did not provide an output stream" }
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            val actualBytes = resolver.openAssetFileDescriptor(document.uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            } ?: resolver.openInputStream(document.uri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            } ?: -1L
            require(actualBytes == expectedBytes) { "Exported XPI byte-count mismatch" }
            val actualHash = resolver.openInputStream(document.uri)?.buffered()?.use(BrowserExtensionHash::digest)
                ?: throw FileNotFoundException("The exported XPI could not be reopened")
            require(actualHash == expectedSha256) { "Exported XPI checksum mismatch" }
        }

        override fun snapshot(document: SafDocument): BrowserExtensionDocumentSnapshot {
            val backup = File.createTempFile("xdm-firefox-existing-", ".xpi.backup", appContext.cacheDir)
            try {
                resolver.openInputStream(document.uri)?.buffered().use { input ->
                    requireNotNull(input) { "The existing XPI could not be opened for safe replacement" }
                    backup.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                val byteCount = backup.length()
                val sha256 = backup.inputStream().buffered().use(BrowserExtensionHash::digest)
                return BrowserExtensionDocumentSnapshot(backup, byteCount, sha256)
            } catch (failure: Throwable) {
                backup.delete()
                throw failure
            }
        }

        override fun rename(document: SafDocument, displayName: String): SafDocument? = runCatching {
            DocumentsContract.renameDocument(resolver, document.uri, displayName)?.let { SafDocument(it, displayName) }
        }.getOrNull()

        override fun delete(document: SafDocument): Boolean = runCatching {
            DocumentsContract.deleteDocument(resolver, document.uri)
        }.getOrDefault(false)
    }

    private data class SafDocument(
        val uri: Uri,
        val displayName: String,
    )
}
