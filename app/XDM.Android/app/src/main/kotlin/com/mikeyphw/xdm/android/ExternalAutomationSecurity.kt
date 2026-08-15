package com.mikeyphw.xdm.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.mikeyphw.xdm.android.browser.BrowserHandoffContract
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import com.mikeyphw.xdm.android.model.ExternalCommandAuthorization
import com.mikeyphw.xdm.android.model.ExternalNetworkTarget
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.tasker.TaskerContract
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import org.json.JSONObject

internal data class ExternalCallerIdentity(
    val observedPackage: String?,
    val claimedPackage: String?,
) {
    companion object {
        fun from(activity: Activity, intent: Intent): ExternalCallerIdentity {
            // Only Android's callingPackage is treated as observed provenance. Referrer/origin
            // extras are caller-controlled diagnostics and must never become authority.
            val observed = activity.callingPackage?.trim()?.takeIf(String::isNotBlank)?.take(160)
            val claimed = sequenceOf(
                intent.getStringExtra(BrowserHandoffContract.ExtraOriginPackage),
                intent.getStringExtra(Intent.EXTRA_REFERRER_NAME),
            ).firstOrNull { !it.isNullOrBlank() }?.trim()?.take(160)
            return ExternalCallerIdentity(observed, claimed)
        }
    }
}

/** Stores only a salted SHA-256 verifier under noBackupFilesDir. The generated integration secret
 * is shown once and is never recoverable from app storage. */
internal class ExternalAutomationTrustStore(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val random = SecureRandom()

    fun isConfigured(): Boolean = readVerifier() != null

    fun generateAndRotate(): String {
        val secretBytes = ByteArray(32).also(random::nextBytes)
        val secret = Base64.encodeToString(secretBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val salt = ByteArray(24).also(random::nextBytes)
        val payload = JSONObject()
            .put("version", 1)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("digest", Base64.encodeToString(digest(salt, secret), Base64.NO_WRAP))
            .toString()
            .toByteArray(Charsets.UTF_8)
        atomicWrite(payload)
        return secret
    }

    fun revoke() {
        file.delete()
    }

    fun verify(candidate: String?): Boolean {
        val supplied = candidate?.trim()?.takeIf { it.length in 32..256 } ?: return false
        val verifier = readVerifier() ?: return false
        return MessageDigest.isEqual(verifier.digest, digest(verifier.salt, supplied))
    }

    private fun readVerifier(): Verifier? = runCatching {
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("version") != 1) return null
        Verifier(
            salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP),
            digest = Base64.decode(json.getString("digest"), Base64.NO_WRAP),
        )
    }.getOrNull()

    private fun digest(salt: ByteArray, secret: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(salt + secret.toByteArray(Charsets.UTF_8))

    private fun atomicWrite(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                file.delete()
                check(temporary.renameTo(file)) { "Could not persist external automation verifier" }
            }
        } finally {
            temporary.delete()
        }
    }

    private data class Verifier(val salt: ByteArray, val digest: ByteArray)

    private companion object {
        const val FILE_NAME = "external-automation-verifier-v1.json"
    }
}

internal object ExternalIntentDraftFactory {
    fun tasker(activity: Activity, intent: Intent): AutomationCommandDraft? {
        val identity = ExternalCallerIdentity.from(activity, intent)
        return TaskerContract.draftFor(
            actionName = intent.action,
            url = intent.getStringExtra(TaskerContract.ExtraUrl) ?: handoffUrl(activity, intent),
            fileName = intent.getStringExtra(TaskerContract.ExtraFileName) ?: handoffFileName(intent),
            pageTitle = intent.getStringExtra(TaskerContract.ExtraPageTitle) ?: handoffTitle(intent),
            pageUrl = intent.getStringExtra(TaskerContract.ExtraPageUrl) ?: handoffPageUrl(intent),
            idempotencyKey = intent.getStringExtra(TaskerContract.ExtraIdempotencyKey),
            originPackage = identity.observedPackage,
            claimedOriginPackage = identity.claimedPackage,
            rawHeaders = browserHeaders(intent),
        )
    }

    fun general(activity: Activity, intent: Intent): AutomationCommandDraft? {
        val identity = ExternalCallerIdentity.from(activity, intent)
        val deepLink = com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParser.parseDetailed(
            rawDeepLink = intent.dataString,
            expectedScheme = BuildConfig.XDM_BROWSER_SCHEME,
        )
        if (deepLink is com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParseResult.Accepted) {
            val payload = deepLink.payload
            // Encrypted capture sessions are handled by the dedicated review/journal path.
            // Never flatten ciphertext-bearing v2 capture data into a legacy URL automation draft.
            if (payload.hasEncryptedCaptureEnvelope) return null
            return payload.toAutomationCommandDraft(originPackage = identity.observedPackage).copy(
                claimedOriginPackage = identity.claimedPackage,
            )
        }
        if (deepLink is com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParseResult.Rejected) return null

        val action = intent.action.orEmpty()
        val sharedText = sharedText(activity, intent)
        val url = handoffUrl(activity, intent, sharedText)
        val shouldPrompt = action in BrowserHandoffContract.DownloadManagerActions || action == Intent.ACTION_VIEW
        val commandAction = if (shouldPrompt) AutomationCommandAction.PromptAddDownload else AutomationCommandAction.CaptureMedia
        if (action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW) &&
            action !in BrowserHandoffContract.DownloadManagerActions
        ) return null
        return AutomationCommandDraft(
            source = when {
                action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE -> AutomationCommandSource.ShareSheet
                else -> AutomationCommandSource.ViewIntent
            },
            action = commandAction,
            url = url,
            fileName = handoffFileName(intent),
            pageTitle = handoffTitle(intent),
            pageUrl = handoffPageUrl(intent, url),
            explicitIdempotencyKey = intent.getStringExtra(TaskerContract.ExtraIdempotencyKey),
            originPackage = identity.observedPackage,
            claimedOriginPackage = identity.claimedPackage,
            rawHeaders = browserHeaders(intent),
            mimeType = handoffMimeType(intent),
            contentLength = handoffContentLength(intent),
            frameUrl = handoffFrameUrl(intent),
            stableMediaId = handoffStableMediaId(intent),
            sessionRevision = handoffSessionRevision(intent),
            proposedHeaders = browserProposedHeaders(intent),
            finalHeaders = browserFinalHeaders(intent),
            pageObservationNonce = intent.getStringExtra(BrowserHandoffContract.ExtraPageObservationNonce),
            pageObservationCreatedAtEpochMs = intent.getLongExtra(BrowserHandoffContract.ExtraPageObservationCreatedAt, -1L).takeIf { it > 0L },
            pageObservationExpiresAtEpochMs = intent.getLongExtra(BrowserHandoffContract.ExtraPageObservationExpiresAt, -1L).takeIf { it > 0L },
        )
    }

    fun displaySummary(draft: AutomationCommandDraft): String = buildString {
        append(
            when (draft.action) {
                AutomationCommandAction.PauseAll -> "Pause every active XDM transfer?"
                AutomationCommandAction.ResumeAll -> "Resume eligible paused XDM transfers?"
                AutomationCommandAction.CaptureMedia -> "Review this media capture in XDM?"
                else -> "Review this download in XDM?"
            },
        )
        val redacted = draft.normalizedUrl?.let(ExternalUrlPolicy::persistableUrl)
        if (!redacted.isNullOrBlank()) append("\n\n").append(redacted.take(500))
        when (ExternalUrlPolicy.classifyNetworkTarget(draft.normalizedUrl)) {
            ExternalNetworkTarget.Public -> Unit
            else -> append("\n\nThis address targets a local, private, link-local, reserved, or unresolved network location. Continue only if you trust the sender and destination.")
        }
        if (ExternalUrlPolicy.isCleartext(draft.normalizedUrl)) {
            append("\n\nThis request is not protected by HTTPS. Authentication headers will not be sent unless you explicitly approve this request.")
        }
    }

    private fun handoffUrl(activity: Activity, intent: Intent, sharedText: String? = null): String? = sequenceOf(
        intent.dataString,
        sharedText,
        intent.getStringExtra(BrowserHandoffContract.ExtraDownloadUrl),
        intent.getStringExtra(TaskerContract.ExtraUrl),
        intent.getStringExtra(Intent.EXTRA_TEXT),
        intent.getStringExtra("android.intent.extra.URL"),
        intent.getStringExtra("url"),
        intent.getStringExtra("downloadUrl"),
        intent.getStringExtra("download_url"),
        intent.getStringExtra("downloadUri"),
        intent.getStringExtra("com.android.browser.extra.URL"),
        intent.getStringExtra("org.mozilla.gecko.extra.URI"),
        intent.getStringExtra(Intent.EXTRA_SUBJECT),
    ).plus(clipValues(activity, intent)).firstNotNullOfOrNull(ExternalUrlPolicy::normalizedUrl)

    private fun sharedText(activity: Activity, intent: Intent): String? = sequenceOf(
        intent.getStringExtra(Intent.EXTRA_TEXT),
        intent.getStringExtra(Intent.EXTRA_SUBJECT),
    ).plus(clipValues(activity, intent)).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) }

    private fun clipValues(activity: Activity, intent: Intent): Sequence<String?> = sequence {
        val clip = intent.clipData ?: return@sequence
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            yield(item.uri?.toString())
            yield(item.coerceToText(activity)?.toString())
        }
    }

    private fun handoffFileName(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraFileName),
        intent.getStringExtra(TaskerContract.ExtraFileName),
        intent.getStringExtra(Intent.EXTRA_TITLE),
        intent.getStringExtra("android.intent.extra.FILE_NAME"),
        intent.getStringExtra("filename"),
        intent.getStringExtra("fileName"),
        intent.getStringExtra("downloadFileName"),
        intent.getStringExtra("com.android.browser.extra.FILENAME"),
    ).firstNotNullOfOrNull { it.trim().takeIf(String::isNotBlank) }
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.replace(Regex("[\\u0000-\\u001F\\u007F]"), "_")
        ?.take(160)

    private fun handoffTitle(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(TaskerContract.ExtraPageTitle),
        intent.getStringExtra(Intent.EXTRA_SUBJECT),
        intent.getStringExtra(Intent.EXTRA_TITLE),
        intent.getStringExtra("title"),
        intent.getStringExtra("com.android.browser.extra.TITLE"),
    ).firstNotNullOfOrNull { it.trim().takeIf(String::isNotBlank) }?.take(300)

    private fun handoffMimeType(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraMimeType),
        intent.type,
        intent.getStringExtra("mimeType"),
        intent.getStringExtra("mime_type"),
        intent.getStringExtra("com.android.browser.extra.MIME_TYPE"),
    ).firstNotNullOfOrNull { value ->
        value.substringBefore(';').trim().lowercase(Locale.US).takeIf { '/' in it && it.length <= 120 }
    }

    private fun handoffContentLength(intent: Intent): Long? = listOf(
        BrowserHandoffContract.ExtraContentLength,
        "contentLength",
        "content_length",
        "android.intent.extra.SIZE",
        "com.android.browser.extra.CONTENT_LENGTH",
    ).firstNotNullOfOrNull { key -> intent.getLongExtra(key, -1L).takeIf { it > 0L } }

    private fun handoffPageUrl(intent: Intent, fallbackUrl: String? = null): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraPageUrl),
        intent.getStringExtra(TaskerContract.ExtraPageUrl),
        intent.getStringExtra("pageUrl"),
        intent.getStringExtra("page_url"),
        intent.getStringExtra("com.android.browser.extra.REFERRER"),
        intent.getStringExtra("android.intent.extra.REFERRER_NAME"),
        fallbackUrl,
    ).firstNotNullOfOrNull(ExternalUrlPolicy::normalizedUrl)

    private fun handoffFrameUrl(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraFrameUrl),
        intent.getStringExtra("frameUrl"),
        intent.getStringExtra("frame_url"),
    ).firstNotNullOfOrNull(ExternalUrlPolicy::normalizedUrl)

    private fun handoffStableMediaId(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraStableMediaId),
        intent.getStringExtra("stableMediaId"),
        intent.getStringExtra("stable_media_id"),
    ).firstNotNullOfOrNull { value -> value.trim().takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,160}")) } }

    private fun handoffSessionRevision(intent: Intent): Long? = listOf(
        BrowserHandoffContract.ExtraSessionRevision,
        "sessionRevision",
        "session_revision",
    ).firstNotNullOfOrNull { key ->
        intent.getStringExtra(key)?.toLongOrNull()?.takeIf { it > 0L }
            ?: intent.getLongExtra(key, -1L).takeIf { it > 0L }
    }

    private fun browserProposedHeaders(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraProposedRequestHeaders),
        intent.getStringExtra("proposedHeaders"),
        intent.getStringExtra("proposed_headers"),
    ).joinToString("\n").take(32_768).takeIf { it.isNotBlank() }

    private fun browserFinalHeaders(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraFinalRequestHeaders),
        intent.getStringExtra("finalHeaders"),
        intent.getStringExtra("final_headers"),
    ).joinToString("\n").take(32_768).takeIf { it.isNotBlank() }

    private fun browserHeaders(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraRequestHeaders),
        intent.getStringExtra("com.android.browser.extra.HEADERS"),
        intent.getStringExtra("headers"),
        intent.getStringExtra("requestHeaders"),
        intent.getStringExtra(BrowserHandoffContract.ExtraCookieHeader)?.let { "Cookie: $it" },
        intent.getStringExtra("cookie")?.let { "Cookie: $it" },
        intent.getStringExtra("Cookie")?.let { "Cookie: $it" },
    ).joinToString("\n").take(32_768).takeIf(String::isNotBlank)
}

internal fun AutomationCommandDraft.approvedForDispatch(
    authorization: ExternalCommandAuthorization,
    privateNetworkApproved: Boolean,
    cleartextCredentialsApproved: Boolean,
    verifiedIntegrationId: String? = null,
): AutomationCommandDraft = copy(
    authorization = authorization,
    privateNetworkApproved = privateNetworkApproved,
    cleartextCredentialsApproved = cleartextCredentialsApproved,
    verifiedIntegrationId = verifiedIntegrationId,
)
