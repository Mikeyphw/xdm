package com.mikeyphw.xdm.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandSource
import com.mikeyphw.xdm.android.model.BrowserHandoffPolicy
import com.mikeyphw.xdm.android.tasker.TaskerContract
import com.mikeyphw.xdm.android.browser.BrowserHandoffContract
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as XdmApplication).container)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme, typography = XdmTypography) { XdmApp(viewModel, requestNotifications = ::requestNotificationPermissionIfNeeded) }
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
        val sharedUrl = sharedText(incoming)
        val url = handoffUrl(incoming, sharedUrl)
        val fileName = handoffFileName(incoming)
        val taskerDraft = TaskerContract.draftFor(
            actionName = incoming.action,
            url = incoming.getStringExtra(TaskerContract.ExtraUrl) ?: url,
            fileName = incoming.getStringExtra(TaskerContract.ExtraFileName) ?: fileName,
            pageTitle = incoming.getStringExtra(TaskerContract.ExtraPageTitle) ?: handoffTitle(incoming),
            pageUrl = incoming.getStringExtra(TaskerContract.ExtraPageUrl) ?: incoming.dataString,
            idempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
            originPackage = browserOriginPackage(incoming),
            rawHeaders = browserHeaders(incoming),
        )
        if (taskerDraft != null) {
            viewModel.ingestAutomationCommand(taskerDraft)
            return
        }
        val action = incoming.action.orEmpty()
        val draft = when {
            action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE -> AutomationCommandDraft(
                source = AutomationCommandSource.ShareSheet,
                action = AutomationCommandAction.CaptureMedia,
                url = url,
                fileName = fileName,
                pageTitle = handoffTitle(incoming),
                explicitIdempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
                originPackage = browserOriginPackage(incoming),
                rawHeaders = browserHeaders(incoming),
            )
            action == Intent.ACTION_VIEW -> AutomationCommandDraft(
                source = AutomationCommandSource.ViewIntent,
                action = AutomationCommandAction.CaptureMedia,
                url = url,
                fileName = fileName,
                pageTitle = handoffTitle(incoming),
                pageUrl = incoming.dataString,
                explicitIdempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
                originPackage = browserOriginPackage(incoming),
                rawHeaders = browserHeaders(incoming),
            )
            action in BrowserHandoffContract.DownloadManagerActions -> AutomationCommandDraft(
                source = AutomationCommandSource.ViewIntent,
                action = AutomationCommandAction.CaptureMedia,
                url = url,
                fileName = fileName,
                pageTitle = handoffTitle(incoming),
                pageUrl = incoming.dataString,
                explicitIdempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
                originPackage = browserOriginPackage(incoming),
                rawHeaders = browserHeaders(incoming),
            )
            else -> null
        }
        if (draft?.normalizedUrl != null) viewModel.ingestAutomationCommand(draft)
    }

    private fun handoffUrl(intent: Intent, sharedText: String? = null): String? =
        urlCandidates(intent, sharedText).firstNotNullOfOrNull(BrowserHandoffPolicy::normalizedUrl)

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
                yield(clipData.getItemAt(index).uri?.toString())
                yield(clipData.getItemAt(index).coerceToText(this@MainActivity)?.toString())
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

    private fun browserOriginPackage(intent: Intent): String? =
        intent.getStringExtra(BrowserHandoffContract.ExtraOriginPackage)
            ?: intent.getStringExtra("android.intent.extra.REFERRER_NAME")
            ?: referrer?.host
            ?: callingPackage
            ?: intent.component?.packageName

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
}
