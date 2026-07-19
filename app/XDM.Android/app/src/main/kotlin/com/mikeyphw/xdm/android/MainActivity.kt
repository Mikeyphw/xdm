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
            MaterialTheme(colorScheme = scheme) { XdmApp(viewModel, requestNotifications = ::requestNotificationPermissionIfNeeded) }
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
        val taskerDraft = TaskerContract.draftFor(
            actionName = incoming.action,
            url = incoming.getStringExtra(TaskerContract.ExtraUrl) ?: incoming.dataString,
            fileName = incoming.getStringExtra(TaskerContract.ExtraFileName),
            pageTitle = incoming.getStringExtra(TaskerContract.ExtraPageTitle),
            pageUrl = incoming.getStringExtra(TaskerContract.ExtraPageUrl),
            idempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
            originPackage = browserOriginPackage(incoming),
            rawHeaders = browserHeaders(incoming),
        )
        if (taskerDraft != null) {
            viewModel.ingestAutomationCommand(taskerDraft)
            return
        }
        val draft = when (incoming.action) {
            Intent.ACTION_SEND -> AutomationCommandDraft(
                source = AutomationCommandSource.ShareSheet,
                action = AutomationCommandAction.CaptureMedia,
                url = incoming.getStringExtra(Intent.EXTRA_TEXT),
                pageTitle = incoming.getStringExtra(Intent.EXTRA_SUBJECT),
                explicitIdempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
                originPackage = browserOriginPackage(incoming),
                rawHeaders = browserHeaders(incoming),
            )
            Intent.ACTION_VIEW -> AutomationCommandDraft(
                source = AutomationCommandSource.ViewIntent,
                action = AutomationCommandAction.CaptureMedia,
                url = incoming.dataString,
                pageUrl = incoming.dataString,
                explicitIdempotencyKey = incoming.getStringExtra(TaskerContract.ExtraIdempotencyKey),
                originPackage = browserOriginPackage(incoming),
                rawHeaders = browserHeaders(incoming),
            )
            else -> null
        }
        if (draft?.normalizedUrl != null) viewModel.ingestAutomationCommand(draft)
    }

    private fun browserOriginPackage(intent: Intent): String? =
        intent.getStringExtra(BrowserHandoffContract.ExtraOriginPackage)
            ?: referrer?.host
            ?: intent.component?.packageName

    private fun browserHeaders(intent: Intent): String? = listOfNotNull(
        intent.getStringExtra(BrowserHandoffContract.ExtraRequestHeaders),
        intent.getStringExtra(BrowserHandoffContract.ExtraCookieHeader)?.let { "Cookie: $it" },
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
