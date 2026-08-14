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
import com.mikeyphw.xdm.android.scheduler.TransferNotifications

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as XdmApplication).container)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
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
                XdmApp(viewModel, requestNotifications = ::requestNotificationPermissionIfNeeded)
            }
        }
        requestLegacyStoragePermissionsIfNeeded()
        // A recreated Activity must not replay the launch intent. New deliveries arrive in onNewIntent.
        if (savedInstanceState == null) consumeLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    private fun consumeLaunchIntent(incoming: Intent?): Boolean =
        consumeInternalAutomation(incoming) || consumeNotificationNavigation(incoming)

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
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        internal const val ACTION_INTERNAL_AUTOMATION_DISPATCH = "com.mikeyphw.xdm.android.INTERNAL_AUTOMATION_DISPATCH"
        internal const val EXTRA_INTERNAL_COMMAND_ID = "com.mikeyphw.xdm.android.extra.INTERNAL_COMMAND_ID"
    }
}
