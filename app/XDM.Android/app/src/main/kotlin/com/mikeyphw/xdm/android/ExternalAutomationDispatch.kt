package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandIds
import com.mikeyphw.xdm.android.model.AutomationCommandRecord
import com.mikeyphw.xdm.android.model.AutomationCommandStatus
import com.mikeyphw.xdm.android.model.AutomationRejectionReason
import com.mikeyphw.xdm.android.model.ExternalCommandAuthorization
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import java.util.Locale
import org.json.JSONObject

/** Durable boundary between exported review activities and MainActivity. */
internal object ExternalAutomationDispatch {
    suspend fun persist(repository: DownloadRepository, draft: AutomationCommandDraft): String? {
        if (draft.authorization == ExternalCommandAuthorization.Untrusted) return null
        val key = draft.stableIdempotencyKey
        val existing = repository.findAutomationCommandByKey(key)
        if (existing != null && existing.status !in setOf(
                AutomationCommandStatus.Rejected,
                AutomationCommandStatus.Failed,
                AutomationCommandStatus.Duplicate,
            )
        ) return existing.id

        val now = System.currentTimeMillis()
        val commandId = existing?.id ?: AutomationCommandIds.commandId(key)
        val record = AutomationCommandRecord(
            id = commandId,
            idempotencyKey = key,
            source = draft.source,
            action = draft.action,
            url = ExternalUrlPolicy.persistableUrl(draft.normalizedUrl),
            fileName = draft.fileName?.trim()?.takeIf(String::isNotBlank),
            pageTitle = draft.pageTitle?.trim()?.takeIf(String::isNotBlank),
            pageUrl = ExternalUrlPolicy.persistableUrl(draft.normalizedPageUrl),
            mediaCaptureId = null,
            downloadId = null,
            status = AutomationCommandStatus.Received,
            resultMessage = if (existing == null) "Accepted durably through ${draft.authorization.name}" else "External command re-approved through ${draft.authorization.name}",
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            originPackage = draft.originPackage?.trim()?.takeIf(String::isNotBlank),
            claimedOriginPackage = draft.claimedOriginPackage?.trim()?.takeIf(String::isNotBlank),
            verifiedIntegrationId = draft.verifiedIntegrationId?.trim()?.takeIf(String::isNotBlank),
            authorization = draft.authorization,
            privateNetworkApproved = draft.privateNetworkApproved,
            cleartextCredentialsApproved = draft.cleartextCredentialsApproved,
            originHost = draft.originHost,
            sanitizedHeaders = draft.sanitizedHeaders,
            rejectionReason = AutomationRejectionReason.None,
            metadataJson = metadataFor(draft),
        )

        // Persist the exact request envelope first. A crash can leave an unreferenced encrypted
        // envelope, but can never leave an executable Room command without its recovery material.
        val envelopeStored = runCatching {
            MediaRequestHandoffStore.rememberCommand(
                commandId = commandId,
                exactUrl = draft.normalizedUrl,
                pageUrl = draft.normalizedPageUrl,
                headers = headersFor(draft),
                redactedSummary = "source=${draft.source.name}; host=${draft.originHost ?: "none"}",
                privateNetworkApproved = draft.privateNetworkApproved,
                cleartextCredentialsApproved = draft.cleartextCredentialsApproved,
            )
        }.isSuccess
        if (!envelopeStored) {
            repository.saveAutomationCommand(
                record.copy(
                    status = AutomationCommandStatus.Failed,
                    resultMessage = "Durable request envelope could not be stored",
                    rejectionReason = AutomationRejectionReason.DurableHandoffFailed,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return null
        }

        if (repository.saveAutomationCommand(record)) return commandId
        val raced = repository.findAutomationCommandByKey(key)
        if (raced != null && raced.id == commandId) return raced.id
        MediaRequestHandoffStore.forgetCommand(commandId)
        return null
    }

    suspend fun persistRejected(repository: DownloadRepository, draft: AutomationCommandDraft, reason: AutomationRejectionReason, message: String) {
        val key = draft.stableIdempotencyKey
        if (repository.findAutomationCommandByKey(key) != null) return
        val now = System.currentTimeMillis()
        repository.saveAutomationCommand(
            AutomationCommandRecord(
                id = AutomationCommandIds.commandId(key), idempotencyKey = key, source = draft.source, action = draft.action,
                url = ExternalUrlPolicy.persistableUrl(draft.normalizedUrl), fileName = draft.fileName, pageTitle = draft.pageTitle,
                pageUrl = ExternalUrlPolicy.persistableUrl(draft.normalizedPageUrl), mediaCaptureId = null, downloadId = null,
                status = AutomationCommandStatus.Rejected, resultMessage = message, createdAtEpochMs = now, updatedAtEpochMs = now,
                originPackage = draft.originPackage, claimedOriginPackage = draft.claimedOriginPackage,
                verifiedIntegrationId = draft.verifiedIntegrationId, authorization = draft.authorization,
                privateNetworkApproved = false, cleartextCredentialsApproved = false, originHost = draft.originHost,
                sanitizedHeaders = draft.sanitizedHeaders, rejectionReason = reason, metadataJson = metadataFor(draft),
            ),
        )
    }

    fun restore(record: AutomationCommandRecord): AutomationCommandDraft {
        val secure = MediaRequestHandoffStore.forCommand(record.id)
        val meta = record.metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        return AutomationCommandDraft(
            source = record.source,
            action = record.action,
            url = secure?.exactUrl ?: record.url,
            fileName = record.fileName,
            pageTitle = record.pageTitle,
            pageUrl = secure?.pageUrl ?: record.pageUrl,
            originPackage = record.originPackage,
            claimedOriginPackage = record.claimedOriginPackage,
            verifiedIntegrationId = record.verifiedIntegrationId,
            authorization = record.authorization,
            privateNetworkApproved = record.privateNetworkApproved,
            cleartextCredentialsApproved = record.cleartextCredentialsApproved,
            rawHeaders = secure?.headers?.entries?.joinToString("\n") { (name, value) -> "$name: $value" },
            mimeType = meta?.optString("mimeType")?.takeIf(String::isNotBlank),
            mediaKind = meta?.optString("mediaKind")?.takeIf(String::isNotBlank),
            contentLength = meta?.optLong("contentLength", -1L)?.takeIf { it > 0L },
            durationMs = meta?.optLong("durationMs", -1L)?.takeIf { it > 0L },
            thumbnailUrl = meta?.optString("thumbnailUrl")?.takeIf(String::isNotBlank),
            frameUrl = meta?.optString("frameUrl")?.takeIf(String::isNotBlank),
            stableMediaId = meta?.optString("stableMediaId")?.takeIf(String::isNotBlank),
            sessionRevision = meta?.optLong("sessionRevision", -1L)?.takeIf { it > 0L },
            receivedAtEpochMs = record.createdAtEpochMs,
        )
    }

    private fun metadataFor(draft: AutomationCommandDraft): String? {
        val json = JSONObject()
        fun putText(name: String, value: String?) { value?.trim()?.takeIf(String::isNotBlank)?.let { json.put(name, ExternalUrlPolicy.persistableUrl(it) ?: it.take(512)) } }
        putText("mimeType", draft.mimeType)
        putText("mediaKind", draft.mediaKind)
        draft.contentLength?.takeIf { it > 0 }?.let { json.put("contentLength", it) }
        draft.durationMs?.takeIf { it > 0 }?.let { json.put("durationMs", it) }
        putText("thumbnailUrl", draft.thumbnailUrl)
        putText("frameUrl", draft.frameUrl)
        putText("stableMediaId", draft.stableMediaId)
        draft.sessionRevision?.takeIf { it > 0 }?.let { json.put("sessionRevision", it) }
        return json.takeIf { it.length() > 0 }?.toString()
    }

    private fun headersFor(draft: AutomationCommandDraft): Map<String, String> {
        val allowed = setOf("cookie", "authorization", "referer", "user-agent", "origin", "accept", "accept-language")
        val result = linkedMapOf<String, String>()
        draft.rawHeaders.orEmpty().lineSequence().forEach { line ->
            val split = line.indexOf(':')
            if (split <= 0) return@forEach
            val name = line.substring(0, split).trim()
            val value = line.substring(split + 1).trim()
            if (name.lowercase(Locale.US) !in allowed || value.isBlank() || '\n' in value || '\r' in value) return@forEach
            if (ExternalUrlPolicy.isCleartext(draft.normalizedUrl) && !draft.cleartextCredentialsApproved && name.lowercase(Locale.US) in setOf("cookie", "authorization")) return@forEach
            result[name] = value.take(8192)
        }
        return result
    }
}
