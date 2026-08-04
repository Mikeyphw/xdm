package com.mikeyphw.xdm.android

import android.content.Context
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import java.io.File
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** One-time migration for data written before encrypted request envelopes were introduced. Exact
 * request URLs are moved into the envelope store before Room rows and JSON sidecars are redacted. */
internal class SensitivePersistenceMigrator(
    context: Context,
    private val repository: DownloadRepository,
) {
    private val appContext = context.applicationContext
    private val marker = File(appContext.noBackupFilesDir, "sensitive-persistence-v1.complete")

    suspend fun migrateIfNeeded() {
        if (marker.isFile) return
        repository.downloads.first().forEach { download ->
            if (ExternalUrlPolicy.persistableUrl(download.sourceUrl) != download.sourceUrl) {
                MediaRequestHandoffStore.remember(
                    downloadId = download.id,
                    headers = emptyMap(),
                    redactedSummary = "Migrated legacy request URL",
                    isExpiringUrl = ExternalUrlPolicy.hasCredentialBearingQuery(download.sourceUrl),
                    exactUrl = download.sourceUrl,
                    privateNetworkApproved = true,
                )
                repository.save(download)
            }
        }
        val captures = repository.mediaCaptures.first()
        captures.forEach { capture ->
            if (
                ExternalUrlPolicy.persistableUrl(capture.sourceUrl) != capture.sourceUrl ||
                ExternalUrlPolicy.persistableUrl(capture.pageUrl) != capture.pageUrl
            ) {
                MediaRequestHandoffStore.rememberCapture(
                    captureId = capture.id,
                    headers = emptyMap(),
                    redactedSummary = "Migrated legacy media URL",
                    isExpiringUrl = ExternalUrlPolicy.hasCredentialBearingQuery(capture.sourceUrl),
                    exactUrl = capture.sourceUrl,
                    pageUrl = capture.pageUrl,
                    privateNetworkApproved = true,
                )
                repository.saveMediaCapture(capture)
            }
        }
        repository.mediaVariants.first().forEach { variant ->
            if (ExternalUrlPolicy.persistableUrl(variant.url) != variant.url) {
                MediaRequestHandoffStore.rememberVariant(
                    variantId = variant.id,
                    exactUrl = variant.url,
                    expiresAtEpochMs = variant.expiresAtEpochMs ?: Long.MAX_VALUE,
                )
                repository.saveMediaVariants(listOf(variant))
            }
        }
        repository.automationCommands.first().forEach { command ->
            if (
                ExternalUrlPolicy.persistableUrl(command.url) != command.url ||
                ExternalUrlPolicy.persistableUrl(command.pageUrl) != command.pageUrl
            ) {
                MediaRequestHandoffStore.rememberCommand(
                    commandId = command.id,
                    exactUrl = command.url,
                    pageUrl = command.pageUrl,
                    headers = emptyMap(),
                    redactedSummary = command.sanitizedHeaders.orEmpty(),
                    privateNetworkApproved = command.privateNetworkApproved,
                    cleartextCredentialsApproved = command.cleartextCredentialsApproved,
                )
                repository.saveAutomationCommand(command)
            }
        }
        repository.clipboardInbox.first().forEach { item -> repository.saveClipboardItem(item) }
        scrubJsonSidecars(listOf(appContext.filesDir, appContext.cacheDir))
        marker.parentFile?.mkdirs()
        marker.writeText("complete\n", Charsets.UTF_8)
    }

    private fun scrubJsonSidecars(roots: List<File>) {
        roots.filter(File::exists).forEach { root ->
            root.walkTopDown()
                .onEnter { directory -> directory.name != "secure-request-envelopes-v1" }
                .filter { file -> file.isFile && file.extension.equals("json", true) && file.length() <= 4L * 1024L * 1024L }
                .forEach { file ->
                    runCatching {
                        val original = file.readText(Charsets.UTF_8)
                        val rootValue: Any = if (original.trimStart().startsWith("[")) JSONArray(original) else JSONObject(original)
                        redactJson(rootValue)
                        val updated = rootValue.toString()
                        if (updated != original) file.writeText(updated, Charsets.UTF_8)
                    }
                }
        }
    }

    private fun redactJson(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                val child = value.opt(key)
                when {
                    child is JSONObject || child is JSONArray -> redactJson(child)
                    child is String && key.contains("url", true) -> value.put(key, ExternalUrlPolicy.persistableUrl(child) ?: child.substringBefore('?'))
                    child is String && key.contains("header", true) -> value.put(key, "<redacted>")
                    key.contains("cookie", true) || key.contains("authorization", true) || key.contains("token", true) || key.contains("secret", true) -> value.put(key, "<redacted>")
                }
            }
            is JSONArray -> for (index in 0 until value.length()) redactJson(value.opt(index))
        }
    }
}
