package com.mikeyphw.xdm.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikeyphw.xdm.android.browser.BrowserHandoffContract
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParseResult
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParser
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import com.mikeyphw.xdm.android.model.ExternalCommandAuthorization
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.tasker.TaskerContract
import java.util.Locale

open class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as XdmApplication).container)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            XdmTheme(mode = state.themeMode) {
                XdmApp(viewModel, requestNotifications = ::requestNotificationPermissionIfNeeded)
            }
        }
        handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(intent: Intent?) {
        val incoming = intent ?: return
        if (consumeInternalAutomation(incoming)) return
        if (this !is ExternalAddDownloadActivity) return

        val identity = ExternalCallerIdentity.from(this, incoming)
        val browserDeepLinkResult = XdmBrowserDeepLinkParser.parseDetailed(
            rawDeepLink = incoming.dataString,
            expectedScheme = BuildConfig.XDM_BROWSER_SCHEME,
        )
        viewModel.recordBrowserDeepLinkResult(browserDeepLinkResult)
        when (browserDeepLinkResult) {
            is XdmBrowserDeepLinkParseResult.Accepted -> {
                val baseDraft = browserDeepLinkResult.payload.toAutomationCommandDraft(
                    originPackage = browserOriginPackage(incoming),
                ).copy(claimedOriginPackage = identity.claimedPackage)
                val draft = reviewApproved(baseDraft)
                viewModel.ingestAutomationCommand(draft)
                return
            }
            is XdmBrowserDeepLinkParseResult.Rejected -> return
            XdmBrowserDeepLinkParseResult.NotApplicable -> Unit
        }

        val sharedUrl = sharedText(incoming)
        val url = handoffUrl(incoming, sharedUrl)
        val fileName = handoffFileName(incoming)
        val mimeType = handoffMimeType(incoming)
        val contentLength = handoffContentLength(incoming)
        val pageUrl = handoffPageUrl(incoming, url)
        val frameUrl = handoffFrameUrl(incoming)
        val stableMediaId = handoffStableMediaId(incoming)
        val sessionRevision = handoffSessionRevision(incoming)
        val proposedHeaders = browserProposedHeaders(incoming)
        val finalHeaders = browserFinalHeaders(incoming)
        val pageObservationNonce = incoming.getStringExtra(BrowserHandoffContract.ExtraPageObservationNonce)
        val pageObservationCreatedAt = incoming.getLongExtra(BrowserHandoffContract.ExtraPageObservationCreatedAt, -1L).takeIf { it > 0L }
        val pageObservationExpiresAt = incoming.getLongExtra(BrowserHandoffContract.ExtraPageObservationExpiresAt, -1L).takeIf { it > 0L }
        val pageTitle = handoffTitle(incoming)
        val action = incoming.action.orEmpty()
        val promptAddDownload = shouldOpenExternalAddPrompt(incoming, action)
        val handoffAction = if (promptAddDownload) AutomationCommandAction.PromptAddDownload else AutomationCommandAction.CaptureMedia
        val baseDraft = when {
            action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE -> AutomationCommandDraft(
                source = AutomationCommandSource.ShareSheet,
                action = handoffAction,
                url = url,
                fileName = fileName,
                pageTitle = pageTitle,
                pageUrl = pageUrl,
                explicitIdempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
                originPackage = browserOriginPackage(incoming),
                claimedOriginPackage = identity.claimedPackage,
                rawHeaders = browserHeaders(incoming),
                mimeType = mimeType,
                contentLength = contentLength,
                frameUrl = frameUrl,
                stableMediaId = stableMediaId,
                sessionRevision = sessionRevision,
                proposedHeaders = proposedHeaders,
                finalHeaders = finalHeaders,
                pageObservationNonce = pageObservationNonce,
                pageObservationCreatedAtEpochMs = pageObservationCreatedAt,
                pageObservationExpiresAtEpochMs = pageObservationExpiresAt,
            )
            action == Intent.ACTION_VIEW -> AutomationCommandDraft(
                source = AutomationCommandSource.ViewIntent,
                action = handoffAction,
                url = url,
                fileName = fileName,
                pageTitle = pageTitle,
                pageUrl = pageUrl,
                explicitIdempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
                originPackage = browserOriginPackage(incoming),
                claimedOriginPackage = identity.claimedPackage,
                rawHeaders = browserHeaders(incoming),
                mimeType = mimeType,
                contentLength = contentLength,
                frameUrl = frameUrl,
                stableMediaId = stableMediaId,
                sessionRevision = sessionRevision,
                proposedHeaders = proposedHeaders,
                finalHeaders = finalHeaders,
                pageObservationNonce = pageObservationNonce,
                pageObservationCreatedAtEpochMs = pageObservationCreatedAt,
                pageObservationExpiresAtEpochMs = pageObservationExpiresAt,
            )
            action in BrowserHandoffContract.DownloadManagerActions -> AutomationCommandDraft(
                source = AutomationCommandSource.ViewIntent,
                action = AutomationCommandAction.PromptAddDownload,
                url = url,
                fileName = fileName,
                pageTitle = pageTitle,
                pageUrl = pageUrl,
                explicitIdempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
                originPackage = browserOriginPackage(incoming),
                claimedOriginPackage = identity.claimedPackage,
                rawHeaders = browserHeaders(incoming),
                mimeType = mimeType,
                contentLength = contentLength,
                frameUrl = frameUrl,
                stableMediaId = stableMediaId,
                sessionRevision = sessionRevision,
                proposedHeaders = proposedHeaders,
                finalHeaders = finalHeaders,
                pageObservationNonce = pageObservationNonce,
                pageObservationCreatedAtEpochMs = pageObservationCreatedAt,
                pageObservationExpiresAtEpochMs = pageObservationExpiresAt,
            )
            else -> null
        }
        if (baseDraft?.normalizedUrl != null) {
            val draft = reviewApproved(baseDraft)
            viewModel.ingestAutomationCommand(draft)
        }
    }

    private fun consumeInternalAutomation(incoming: Intent?): Boolean {
        if (incoming?.action != ACTION_INTERNAL_AUTOMATION_DISPATCH) return false
        val nonce = incoming.getStringExtra(EXTRA_INTERNAL_DISPATCH_NONCE)
        incoming.removeExtra(EXTRA_INTERNAL_DISPATCH_NONCE)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        InternalAutomationDispatchStore.consume(nonce)?.let(viewModel::ingestAutomationCommand)
        return true
    }

    private fun reviewApproved(draft: AutomationCommandDraft): AutomationCommandDraft = draft.approvedForDispatch(
        authorization = ExternalCommandAuthorization.UserConfirmed,
        privateNetworkApproved = draft.normalizedUrl != null && ExternalUrlPolicy.requiresPrivateNetworkApproval(draft.normalizedUrl),
        cleartextCredentialsApproved = false,
    )

    private fun shouldOpenExternalAddPrompt(intent: Intent, action: String): Boolean {
        val componentName = intent.component?.className.orEmpty()
        return componentName.endsWith(".ExternalAddDownloadActivity") || action in BrowserHandoffContract.DownloadManagerActions
    }

    private fun handoffUrl(intent: Intent, sharedText: String? = null): String? =
        urlCandidates(intent, sharedText).firstNotNullOfOrNull(ExternalUrlPolicy::normalizedUrl)

    private fun sharedText(intent: Intent): String? = sharedTextCandidates(intent)
        .firstNotNullOfOrNull { value -> value?.trim()?.takeIf { it.isNotBlank() } }

    private fun sharedTextCandidates(intent: Intent): Sequence<String?> = sequence {
        yield(intent.getStringExtra(Intent.EXTRA_TEXT))
        yield(intent.getStringExtra(Intent.EXTRA_SUBJECT))
        val clipData = intent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(index)
                yield(item.uri?.toString())
                yield(item.coerceToText(this@MainActivity)?.toString())
            }
        }
    }

    private fun urlCandidates(intent: Intent, sharedText: String? = null): Sequence<String?> = sequence {
        yield(intent.dataString)
        yield(sharedText)
        yield(intent.getStringExtra(BrowserHandoffContract.ExtraDownloadUrl))
        yield(intent.getStringExtra(TaskerContract.ExtraUrl))
        yield(intent.getStringExtra(Intent.EXTRA_TEXT))
        yield(intent.getStringExtra("android.intent.extra.URL"))
        yield(intent.getStringExtra("url"))
        yield(intent.getStringExtra("downloadUrl"))
        yield(intent.getStringExtra("download_url"))
        yield(intent.getStringExtra("downloadUri"))
        yield(intent.getStringExtra("com.android.browser.extra.URL"))
        yield(intent.getStringExtra("org.mozilla.gecko.extra.URI"))
        yield(intent.getStringExtra(Intent.EXTRA_SUBJECT))
        val clipData = intent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(index)
                yield(item.uri?.toString())
                yield(item.coerceToText(this@MainActivity)?.toString())
            }
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
    ).firstNotNullOfOrNull { value -> value.trim().takeIf { it.isNotBlank() } }
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.take(160)

    private fun handoffTitle(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(TaskerContract.ExtraPageTitle),
        intent.getStringExtra(Intent.EXTRA_SUBJECT),
        intent.getStringExtra(Intent.EXTRA_TITLE),
        intent.getStringExtra("title"),
        intent.getStringExtra("com.android.browser.extra.TITLE"),
    ).firstNotNullOfOrNull { value -> value.trim().takeIf { it.isNotBlank() } }

    private fun browserOriginPackage(intent: Intent): String? = ExternalCallerIdentity.from(this, intent).observedPackage
        ?: intent.component?.packageName

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

    private fun handoffPageUrl(intent: Intent, fallbackUrl: String?): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraPageUrl),
        intent.getStringExtra(TaskerContract.ExtraPageUrl),
        intent.getStringExtra("pageUrl"),
        intent.getStringExtra("page_url"),
        intent.getStringExtra("com.android.browser.extra.REFERRER"),
        intent.getStringExtra("android.intent.extra.REFERRER_NAME"),
        intent.dataString,
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
    ).joinToString("\n").takeIf { it.isNotBlank() }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        internal const val ACTION_INTERNAL_AUTOMATION_DISPATCH = "com.mikeyphw.xdm.android.INTERNAL_AUTOMATION_DISPATCH"
        internal const val EXTRA_INTERNAL_DISPATCH_NONCE = "com.mikeyphw.xdm.android.extra.INTERNAL_DISPATCH_NONCE"
    }
}
