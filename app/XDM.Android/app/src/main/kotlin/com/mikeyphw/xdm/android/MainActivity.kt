package com.mikeyphw.xdm.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParseResult
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParser
import com.mikeyphw.xdm.android.scheduler.TransferNotifications
import com.mikeyphw.xdm.android.scheduler.NotificationPermissionStore

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as XdmApplication).container)
    }
    private val notificationPermissionState = mutableStateOf<com.mikeyphw.xdm.android.model.NotificationPermissionState?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        NotificationPermissionStore(this).recordPromptResult(granted)
        refreshNotificationPermissionState()
    }
    private val legacyStoragePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            XdmTheme(mode = state.themeMode) {
                XdmApp(
                    viewModel = viewModel,
                    requestNotifications = ::requestNotificationPermissionIfNeeded,
                    notificationPermissionState = notificationPermissionState.value,
                    openNotificationSettings = ::openNotificationSettings,
                )
            }
        }
        requestLegacyStoragePermissionsIfNeeded()
        refreshNotificationPermissionState()
        // A recreated Activity must not replay the launch intent. New deliveries arrive in onNewIntent.
        if (savedInstanceState == null) consumeLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationPermissionState()
    }

    private fun consumeLaunchIntent(incoming: Intent?): Boolean =
        consumeInternalMediaCapture(incoming) || consumeInternalDirectBrowserCapture(incoming) || consumeInternalBrowserCapture(incoming) || consumeInternalAutomation(incoming) || consumeProblemNavigation(incoming) || consumeNotificationNavigation(incoming)

    private fun consumeInternalDirectBrowserCapture(incoming: Intent?): Boolean {
        if (incoming?.action != ACTION_INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT) return false
        val rawDeepLink = incoming.getStringExtra(EXTRA_INTERNAL_BROWSER_DIRECT_CAPTURE_URI)
        val privateNetworkApproved = incoming.getBooleanExtra(EXTRA_INTERNAL_BROWSER_DIRECT_PRIVATE_NETWORK_APPROVED, false)
        incoming.removeExtra(EXTRA_INTERNAL_BROWSER_DIRECT_CAPTURE_URI)
        incoming.removeExtra(EXTRA_INTERNAL_BROWSER_DIRECT_PRIVATE_NETWORK_APPROVED)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        val parsed = XdmBrowserDeepLinkParser.parseDetailed(rawDeepLink, BuildConfig.XDM_BROWSER_SCHEME)
        val payload = (parsed as? XdmBrowserDeepLinkParseResult.Accepted)?.payload
        if (payload?.hasDirectCaptureSession == true) {
            viewModel.ingestDirectBrowserCaptureSession(payload, privateNetworkApproved)
        }
        return true
    }

    private fun consumeInternalMediaCapture(incoming: Intent?): Boolean {
        if (incoming?.action != ACTION_INTERNAL_MEDIA_CAPTURE_READY) return false
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        viewModel.navigate(AppRoute.Media)
        return true
    }

    private fun consumeInternalBrowserCapture(incoming: Intent?): Boolean {
        if (incoming?.action != ACTION_INTERNAL_BROWSER_CAPTURE_IMPORT) return false
        val sessionId = incoming.getStringExtra(EXTRA_INTERNAL_BROWSER_CAPTURE_SESSION_ID)?.trim()?.takeIf(String::isNotBlank)
        incoming.removeExtra(EXTRA_INTERNAL_BROWSER_CAPTURE_SESSION_ID)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        viewModel.recoverPendingBrowserCaptureImports(sessionId)
        return true
    }


    private fun consumeProblemNavigation(incoming: Intent?): Boolean {
        if (incoming?.action != AppProblemNotifications.ACTION_OPEN_PROBLEM) return false
        val problemId = incoming.getStringExtra(AppProblemNotifications.EXTRA_PROBLEM_ID)?.trim()?.takeIf(String::isNotBlank)
        incoming.removeExtra(AppProblemNotifications.EXTRA_PROBLEM_ID)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        if (problemId != null) viewModel.openProblemFromNotification(problemId) else viewModel.selectSettingsPanel(SettingsPanel.DebugWorkbench)
        return true
    }

    private fun consumeNotificationNavigation(incoming: Intent?): Boolean {
        val downloadId = incoming?.getStringExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)?.trim()?.takeIf(String::isNotBlank)
        val consumed = when (incoming?.action) {
            TransferNotifications.ACTION_OPEN_DOWNLOAD_DETAILS -> {
                downloadId?.let(viewModel::openDownloadFromNotification)
                true
            }
            TransferNotifications.ACTION_REVIEW_RECOVERY -> {
                downloadId?.let(viewModel::openRecoveryFromNotification)
                true
            }
            else -> false
        }
        if (consumed) {
            incoming?.removeExtra(TransferNotifications.EXTRA_DOWNLOAD_ID)
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
        return consumed
    }

    private fun consumeInternalAutomation(incoming: Intent?): Boolean {
        if (incoming?.action != ACTION_INTERNAL_AUTOMATION_DISPATCH) return false
        val commandId = incoming.getStringExtra(EXTRA_INTERNAL_COMMAND_ID)?.trim()?.takeIf(String::isNotBlank)
        incoming.removeExtra(EXTRA_INTERNAL_COMMAND_ID)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        commandId?.let(viewModel::ingestPersistedAutomationCommand)
        return true
    }

    private fun requestLegacyStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val missing = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (missing.isNotEmpty()) legacyStoragePermissions.launch(missing)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            NotificationPermissionStore(this).recordPromptRequested()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshNotificationPermissionState()
        }
    }

    private fun refreshNotificationPermissionState() {
        notificationPermissionState.value = runCatching {
            TransferNotifications(this).also { notifications ->
                // A user can re-enable app/channels in Settings while XDM is backgrounded. Reconcile
                // any terminal rows that intentionally stayed Pending while drawer delivery was blocked.
                notifications.reconcilePendingTerminalNotifications()
            }.notificationPermissionState()
        }.getOrNull()
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }

    companion object {
        internal const val ACTION_INTERNAL_MEDIA_CAPTURE_READY = "com.mikeyphw.xdm.android.INTERNAL_MEDIA_CAPTURE_READY"
        internal const val ACTION_INTERNAL_AUTOMATION_DISPATCH = "com.mikeyphw.xdm.android.INTERNAL_AUTOMATION_DISPATCH"
        internal const val EXTRA_INTERNAL_COMMAND_ID = "com.mikeyphw.xdm.android.extra.INTERNAL_COMMAND_ID"
        internal const val ACTION_INTERNAL_BROWSER_CAPTURE_IMPORT = "com.mikeyphw.xdm.android.INTERNAL_BROWSER_CAPTURE_IMPORT"
        internal const val EXTRA_INTERNAL_BROWSER_CAPTURE_SESSION_ID = "com.mikeyphw.xdm.android.extra.INTERNAL_BROWSER_CAPTURE_SESSION_ID"
        internal const val ACTION_INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT = "com.mikeyphw.xdm.android.INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT"
        internal const val EXTRA_INTERNAL_BROWSER_DIRECT_CAPTURE_URI = "com.mikeyphw.xdm.android.extra.INTERNAL_BROWSER_DIRECT_CAPTURE_URI"
        internal const val EXTRA_INTERNAL_BROWSER_DIRECT_PRIVATE_NETWORK_APPROVED = "com.mikeyphw.xdm.android.extra.INTERNAL_BROWSER_DIRECT_PRIVATE_NETWORK_APPROVED"
    }
}
