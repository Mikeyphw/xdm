package com.mikeyphw.xdm.android.scheduler

import android.content.Context

/**
 * XGO-71 narrow Android -> Go scheduler boundary.
 *
 * Android is allowed to report why a platform primitive woke the process and to
 * preserve a retry deadline that was already chosen by Go. It is not allowed to
 * decide queue eligibility, retry policy, backend selection, or attempt ownership.
 */
data class AndroidEngineWakeRequest(
    val eventId: String,
    val downloadId: String? = null,
    val reason: AndroidEngineWakeReason,
    val engineRetryDueAtEpochMs: Long? = null,
    val requiresForeground: Boolean = false,
    val userInitiated: Boolean = false,
    val afterBootOrPackageRestart: Boolean = false,
)

enum class AndroidEngineWakeReason {
    PERIODIC_WORK,
    CONDITION_CHANGED,
    CLAIMED_EXECUTION_OPPORTUNITY,
    RETRY_DEADLINE,
    FOREGROUND_SERVICE,
    USER_INITIATED_DATA_TRANSFER,
    BOOT_OR_PACKAGE_RESTART,
    MANUAL_RECONCILE,
}

enum class AndroidEngineWakeDisposition { ACCEPTED, DUPLICATE, RETRYABLE, FAILED }

data class AndroidEngineWakeResult(
    val disposition: AndroidEngineWakeDisposition,
    val detail: String,
)

fun interface AndroidGoEngineHost {
    fun wake(request: AndroidEngineWakeRequest): AndroidEngineWakeResult
}

interface AndroidGoEngineHostProvider {
    val androidGoEngineHost: AndroidGoEngineHost
}

/**
 * XGO-75 command boundary used by Android notification/service compatibility surfaces.
 *
 * These surfaces are not allowed to mutate the legacy queue/runtime directly. They submit
 * the same download command that Compose uses; Go owns the state transition and may request
 * narrow Android side effects through the platform broker afterwards.
 */
data class AndroidGoDownloadCommand(
    val action: String,
    val downloadId: String? = null,
)

data class AndroidGoDownloadCommandResult(
    val accepted: Boolean,
    val detail: String,
)

fun interface AndroidGoDownloadCommandHost {
    suspend fun submit(command: AndroidGoDownloadCommand): AndroidGoDownloadCommandResult
}

interface AndroidGoDownloadCommandHostProvider {
    val androidGoDownloadCommandHost: AndroidGoDownloadCommandHost
}

object AndroidGoDownloadCommands {
    suspend fun submit(context: Context, action: String, downloadId: String? = null): AndroidGoDownloadCommandResult {
        val host = (context.applicationContext as? AndroidGoDownloadCommandHostProvider)?.androidGoDownloadCommandHost
            ?: return AndroidGoDownloadCommandResult(false, "Go download command host provider is not ready")
        return host.submit(AndroidGoDownloadCommand(action = action, downloadId = downloadId))
    }
}

object AndroidSchedulerHost {
    fun wake(context: Context, request: AndroidEngineWakeRequest): AndroidEngineWakeResult {
        val host = (context.applicationContext as? AndroidGoEngineHostProvider)?.androidGoEngineHost
            ?: return AndroidEngineWakeResult(AndroidEngineWakeDisposition.RETRYABLE, "Go engine host provider is not ready")
        return host.wake(request)
    }
}
