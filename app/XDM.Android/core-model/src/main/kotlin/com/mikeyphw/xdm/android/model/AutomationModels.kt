package com.mikeyphw.xdm.android.model

import java.security.MessageDigest

/** Durable external automation command sources. These are persisted so repeated Tasker,
 * browser, or share-sheet deliveries can be recognized without duplicating downloads. */
enum class AutomationCommandSource { ShareSheet, ViewIntent, Tasker, BrowserExtension, DeepLink, Internal }
enum class AutomationCommandAction { EnqueueDownload, CaptureMedia, PauseAll, ResumeAll, Unknown }
enum class AutomationCommandStatus { Accepted, Duplicate, Rejected, Executed, Failed }

data class AutomationCommandDraft(
    val source: AutomationCommandSource,
    val action: AutomationCommandAction,
    val url: String? = null,
    val fileName: String? = null,
    val pageTitle: String? = null,
    val pageUrl: String? = null,
    val explicitIdempotencyKey: String? = null,
) {
    val normalizedUrl: String? get() = url?.trim()?.takeIf { it.isNotBlank() }
    val stableIdempotencyKey: String get() = AutomationCommandIds.stableKey(this)
}

data class AutomationCommandRecord(
    val id: String,
    val idempotencyKey: String,
    val source: AutomationCommandSource,
    val action: AutomationCommandAction,
    val url: String?,
    val fileName: String?,
    val pageTitle: String?,
    val pageUrl: String?,
    val mediaCaptureId: String?,
    val downloadId: String?,
    val status: AutomationCommandStatus,
    val resultMessage: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

object AutomationCommandIds {
    fun stableKey(draft: AutomationCommandDraft): String {
        val explicit = draft.explicitIdempotencyKey?.trim()?.takeIf { it.isNotBlank() }
        if (explicit != null) return "external:${draft.source.name}:$explicit"
        return stableKey(
            source = draft.source,
            action = draft.action,
            url = draft.url,
            fileName = draft.fileName,
            pageUrl = draft.pageUrl,
        )
    }

    fun stableKey(
        source: AutomationCommandSource,
        action: AutomationCommandAction,
        url: String?,
        fileName: String? = null,
        pageUrl: String? = null,
    ): String {
        val raw = listOf(
            source.name,
            action.name,
            url.normalizedCommandPart(),
            fileName.normalizedCommandPart(),
            pageUrl.normalizedCommandPart(),
        ).joinToString("|")
        return "auto:" + sha256(raw).take(32)
    }

    fun commandId(idempotencyKey: String): String = "cmd-" + sha256(idempotencyKey).take(32)

    private fun String?.normalizedCommandPart(): String = this?.trim()?.lowercase().orEmpty()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
