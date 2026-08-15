package com.mikeyphw.xdm.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    fun releaseDirectoryPermission(uriString: String): Result<Unit> = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "content") {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }


    fun inspect(
        preferences: BrowserExtensionExportPreferences,
        diagnostics: BrowserBridgeDiagnosticsPreferences,
        appTheme: XdmThemeMode,
        appVersion: String,
        applicationId: String,
        scheme: String,
    ): BrowserBridgeIntegrationStatus {
        // Resolver-only probe: never manufacture a plaintext media capture URI.
        val schemeProbe = Intent(
            Intent.ACTION_VIEW,
            Uri.Builder().scheme(scheme).authority("capture").build(),
        ).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = appContext.packageManager.resolveActivity(schemeProbe, PackageManager.MATCH_DEFAULT_ONLY)
        val resolvedPackage = resolved?.activityInfo?.packageName.orEmpty()
        val resolvedClass = resolved?.activityInfo?.name.orEmpty()
        val schemeState = when {
            resolved == null -> BrowserBridgeSchemeState.Missing
            resolvedPackage != applicationId || !resolvedClass.endsWith(".ExternalAddDownloadActivity") -> BrowserBridgeSchemeState.WrongHandler
            else -> BrowserBridgeSchemeState.Ready
        }
        val schemeDetail = when (schemeState) {
            BrowserBridgeSchemeState.Ready -> "$scheme is registered to this XDM build."
            BrowserBridgeSchemeState.Missing -> "Android did not resolve $scheme to an activity."
            BrowserBridgeSchemeState.WrongHandler -> "$scheme resolves to ${resolvedPackage.ifBlank { "another app" }}."
        }

        val treeUri = preferences.exportTreeUri.takeIf(String::isNotBlank)?.let(Uri::parse)
        val retained = treeUri != null && hasWritePermission(treeUri)
        var safState = when {
            treeUri == null -> BrowserBridgeSafState.NotConfigured
            !retained -> BrowserBridgeSafState.PermissionRevoked
            else -> BrowserBridgeSafState.Ready
        }
        var safDetail = when (safState) {
            BrowserBridgeSafState.NotConfigured -> "Choose an Android document-tree folder."
            BrowserBridgeSafState.PermissionRevoked -> "Android no longer grants write access to the selected folder."
            else -> "Export folder permission is retained."
        }
        var canOpen = false
        val documentUri = preferences.lastExportDocumentUri.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (retained && preferences.lastExportFileName.isNotBlank()) {
            if (documentUri == null) {
                safState = BrowserBridgeSafState.ExportMissing
                safDetail = "The last export has no persisted document reference. Regenerate it."
            } else {
                val verification = runCatching {
                    val byteCount = resolver.openAssetFileDescriptor(documentUri, "r")?.use { descriptor ->
                        descriptor.length.takeIf { it >= 0L }
                    } ?: throw FileNotFoundException("The exported XPI could not be opened")
                    require(byteCount == preferences.lastExportByteCount) { "Exported XPI size changed" }
                    val hash = resolver.openInputStream(documentUri)?.buffered()?.use(BrowserExtensionHash::digest)
                        ?: throw FileNotFoundException("The exported XPI could not be read")
                    require(hash == preferences.lastExportSha256) { "Exported XPI checksum changed" }
                }
                if (verification.isSuccess) {
                    safState = BrowserBridgeSafState.Ready
                    safDetail = "The last exported XPI is present and checksum-verified."
                    canOpen = true
                } else {
                    val message = verification.exceptionOrNull()?.message.orEmpty()
                    safState = when {
                        message.contains("checksum", ignoreCase = true) || message.contains("size", ignoreCase = true) -> BrowserBridgeSafState.ChecksumMismatch
                        verification.exceptionOrNull() is FileNotFoundException -> BrowserBridgeSafState.ExportMissing
                        else -> BrowserBridgeSafState.Unreadable
                    }
                    safDetail = BrowserBridgeDiagnosticsRedactor.sanitize(
                        verification.exceptionOrNull()?.message ?: "The exported XPI could not be verified.",
                    )
                }
            }
        }

        val issues = preferences.staleReasons(appTheme, appVersion, applicationId, scheme).toMutableList()
        if (preferences.lastExportFileName.isBlank()) issues += "No verified XPI has been generated yet."
        if (diagnostics.lastGenerationPhase == "exporting") {
            issues += "A previous XPI generation was interrupted. The previous verified XPI was retained."
        }
        return BrowserBridgeIntegrationStatus(
            schemeState = schemeState,
            schemeDetail = schemeDetail,
            safState = safState,
            safDetail = safDetail,
            compatibilityIssues = issues.distinct().take(8),
            canOpenExport = canOpen,
            currentExportUri = if (canOpen) preferences.lastExportDocumentUri else "",
        )
    }

    fun openExportedFile(uriString: String): Result<Unit> = runCatching {
        val uri = Uri.parse(uriString)
        require(uri.scheme == "content") { "The exported XPI document is unavailable" }
        resolver.openAssetFileDescriptor(uri, "r")?.close()
            ?: throw FileNotFoundException("The exported XPI document is missing")
        val candidates = listOf("application/x-xpinstall", "application/zip", "application/octet-stream")
        var lastFailure: Throwable? = null
        for (mimeType in candidates) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                appContext.startActivity(intent)
                return@runCatching
            } catch (failure: ActivityNotFoundException) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: ActivityNotFoundException("No installed app can open XPI files")
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
